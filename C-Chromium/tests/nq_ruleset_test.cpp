// Unit test untuk pemuatan ruleset saat runtime.
//
// Tidak bergantung pada CEF: seluruh modul yang diuji (SHA-256, parser JSON,
// pemuat ruleset, kebijakan privasi, bootstrap) sengaja bebas dari header CEF.
//
// Cara menjalankan:
//   g++ -std=c++17 -Wall -Wextra -Werror -I src tests/nq_ruleset_test.cpp
//       src/nq_sha256.cpp src/nq_json.cpp src/nq_ruleset.cpp
//       src/nq_privacy_policy.cpp src/nq_bootstrap.cpp -o build/nq_ruleset_test
//   ./build/nq_ruleset_test

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>

#include "nq_bootstrap.h"
#include "nq_json.h"
#include "nq_privacy_policy.h"
#include "nq_ruleset.h"
#include "nq_sha256.h"

namespace {

int g_checks = 0;
int g_failures = 0;
std::string g_case;

void Check(bool condition, const std::string& what) {
  ++g_checks;
  if (!condition) {
    ++g_failures;
    std::printf("  FAIL [%s] %s\n", g_case.c_str(), what.c_str());
  }
}

void Begin(const std::string& name) {
  g_case = name;
  std::printf("-- %s\n", name.c_str());
}

std::string TempDir() {
  const char* env = std::getenv("NQ_TEST_TMP");
  std::string dir = env != nullptr ? std::string(env) : std::string("nq-test-tmp");
  std::filesystem::create_directories(dir);
  return dir;
}

bool WriteFile(const std::string& path, const std::string& content) {
  std::ofstream out(path, std::ios::binary | std::ios::trunc);
  if (!out) return false;
  out << content;
  return out.good();
}

std::string ChecksumOf(const std::string& content) {
  return nq::Sha256::ToHex(nq::Sha256::Hash(content));
}

// Menulis ruleset beserta sidecar checksum-nya. Bila corrupt_checksum=true,
// sidecar berisi hash yang salah (menguji jalur CHECKSUM_MISMATCH).
std::string WriteRuleset(const std::string& name, const std::string& content,
                        bool write_sidecar, bool corrupt_checksum) {
  const std::string dir = TempDir();
  const std::string path = dir + "/" + name + ".json";
  if (!WriteFile(path, content)) {
    std::printf("  tidak dapat menulis %s\n", path.c_str());
    std::exit(2);
  }
  if (write_sidecar) {
    const std::string checksum =
        corrupt_checksum ? std::string(64, '0') : ChecksumOf(content);
    if (!WriteFile(path + ".sha256", checksum + "\n")) {
      std::printf("  tidak dapat menulis sidecar %s\n", (path + ".sha256").c_str());
      std::exit(2);
    }
  } else {
    std::remove((path + ".sha256").c_str());
  }
  return path;
}

// Ruleset v2 yang sah. Bila relax_key diisi, kunci kebijakan tersebut diubah
// menjadi false untuk menguji penolakan pelonggaran. Mengganti (bukan
// menambahkan) agar tidak menghasilkan kunci duplikat.
std::string ValidRuleset(const std::string& schema = "nevus-ruleset/2",
                        const std::string& relax_key = "") {
  std::string policy =
      std::string("    \"https_only_top_level\": true,\n") +
      "    \"https_only_subresource\": true,\n" +
      "    \"send_gpc\": true,\n" +
      "    \"deny_permissions_by_default\": true\n";
  if (!relax_key.empty()) {
    const std::string from = "    \"" + relax_key + "\": true";
    const std::string to = "    \"" + relax_key + "\": false";
    const auto at = policy.find(from);
    if (at == std::string::npos) {
      std::printf("  helper uji: kunci '%s' tidak ditemukan\n", relax_key.c_str());
      std::exit(2);
    }
    policy.replace(at, from.size(), to);
  }
  return std::string("{\n") +
         "  \"schema\": \"" + schema + "\",\n" +
         "  \"name\": \"Uji\",\n" +
         "  \"version\": \"1.0.0\",\n" +
         "  \"license\": \"CC0-1.0\",\n" +
         "  \"signed\": false,\n" +
         "  \"policy\": {\n" +
         policy +
         "  },\n" +
         "  \"rules\": [\n" +
         "    { \"host\": \"contoh-iklan.test\", \"match\": \"suffix\" },\n" +
         "    { \"host\": \"lacak.test\", \"match\": \"exact\" }\n" +
         "  ]\n" +
         "}\n";
}

nq::RulesetLoadOptions ReleaseOptions() {
  nq::RulesetLoadOptions options;
  options.allow_unsigned = false;
  return options;
}

nq::RulesetLoadOptions LabOptions() {
  nq::RulesetLoadOptions options;
  options.allow_unsigned = true;
  return options;
}

// Verifier palsu: selalu menerima. Dipakai untuk membuktikan bahwa kode TIDAK
// menerima ruleset bertanda tangan tanpa verifier.
class AcceptingVerifier : public nq::RulesetSignatureVerifier {
 public:
  std::string algorithm() const override { return "uji-aman"; }
  bool Verify(const std::string&, const std::string&,
              const std::string&) const override {
    return true;
  }
};

// Verifier palsu: selalu menolak.
class RejectingVerifier : public nq::RulesetSignatureVerifier {
 public:
  std::string algorithm() const override { return "uji-aman"; }
  bool Verify(const std::string&, const std::string&,
              const std::string&) const override {
    return false;
  }
};

// ---------------------------------------------------------------------------
// SHA-256
// ---------------------------------------------------------------------------
void TestSha256() {
  Begin("sha256 vektor uji FIPS 180-4");
  Check(nq::Sha256::ToHex(nq::Sha256::Hash("")) ==
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "hash string kosong");
  Check(nq::Sha256::ToHex(nq::Sha256::Hash("abc")) ==
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "hash \"abc\"");
  Check(nq::Sha256::ToHex(nq::Sha256::Hash(
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")) ==
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        "hash dua blok");

  Begin("sha256 masukan panjang (melewati batas buffer)");
  std::string million(1000000, 'a');
  Check(nq::Sha256::ToHex(nq::Sha256::Hash(million)) ==
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
        "hash 1 juta 'a'");

  Begin("sha256 hex bolak-balik");
  nq::Sha256::Digest digest{};
  Check(nq::Sha256::FromHex(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            &digest),
        "FromHex menerima hex sah");
  Check(nq::Sha256::ToHex(digest) ==
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "ToHex(FromHex(x)) == x");
  Check(!nq::Sha256::FromHex("zz", &digest), "FromHex menolak panjang salah");
  Check(!nq::Sha256::FromHex(std::string(64, 'z'), &digest),
        "FromHex menolak karakter non-hex");
  Check(!nq::Sha256::FromHex(std::string(64, 'a'), nullptr),
        "FromHex menolak out=nullptr");
}

// ---------------------------------------------------------------------------
// Parser JSON
// ---------------------------------------------------------------------------
void TestJson() {
  Begin("json: bentuk sah");
  Check(nq::json::Parse("{}").ok, "objek kosong");
  Check(nq::json::Parse("[]").ok, "array kosong");
  Check(nq::json::Parse("  {\"a\": 1}  ").ok, "spasi di sekitar nilai");
  Check(nq::json::Parse("{\"a\":[1,2,{\"b\":\"x\"}]}").ok, "bersarang");
  const auto parsed = nq::json::Parse("{\"a\": true, \"b\": null}");
  Check(parsed.ok && parsed.value.IsObject(), "objek terbaca");
  Check(parsed.value.Find("a") != nullptr &&
            parsed.value.Find("a")->bool_value(),
        "nilai boolean benar");
  Check(parsed.value.Find("b") != nullptr && parsed.value.Find("b")->IsNull(),
        "nilai null benar");
  Check(parsed.value.Find("tidak-ada") == nullptr, "kunci tidak ada -> nullptr");

  Begin("json: escape unicode");
  const auto escaped = nq::json::Parse("\"\\u00e9\\u0041\"");
  Check(escaped.ok && escaped.value.string_value() == "\xc3\xa9" "A",
        "escape BMP dikonversi ke UTF-8");
  const auto surrogate = nq::json::Parse("\"\\ud83d\\ude00\"");
  Check(surrogate.ok &&
            surrogate.value.string_value() == "\xf0\x9f\x98\x80",
        "pasangan surrogate dikonversi ke UTF-8");

  Begin("json: penolakan (gagal-tertutup)");
  Check(!nq::json::Parse("").ok, "dokumen kosong ditolak");
  Check(!nq::json::Parse("{} {}").ok, "dua nilai ditolak");
  Check(!nq::json::Parse("{\"a\":1} x").ok, "byte tambahan ditolak");
  Check(!nq::json::Parse("{\"a\":1,\"a\":2}").ok, "kunci duplikat ditolak");
  Check(!nq::json::Parse("{'a':1}").ok, "kutip tunggal ditolak");
  Check(!nq::json::Parse("{\"a\":01}").ok, "nol di depan ditolak");
  Check(!nq::json::Parse("{\"a\":+1}").ok, "tanda plus ditolak");
  Check(!nq::json::Parse("{\"a\":1.}").ok, "pecahan tanpa digit ditolak");
  Check(!nq::json::Parse("{\"a\":1e}").ok, "eksponen tanpa digit ditolak");
  Check(!nq::json::Parse("{\"a\":NaN}").ok, "NaN ditolak");
  Check(!nq::json::Parse("{\"a\":\"x}").ok, "string tidak ditutup ditolak");
  Check(!nq::json::Parse("{\"a\":\"\n\"}").ok, "newline mentah ditolak");
  Check(!nq::json::Parse("{\"a\":\"\\ud800\"}").ok,
        "surrogate tinggi menggantung ditolak");
  Check(!nq::json::Parse("{\"a\":1,}").ok, "koma menggantung ditolak");
  Check(!nq::json::Parse("{\"a\":[1,]}").ok, "koma menggantung di array ditolak");

  Begin("json: batas sumber daya");
  nq::json::Limit limit;
  limit.max_depth = 3;
  std::string deep = "[[[[[1]]]]]";
  Check(!nq::json::Parse(deep, limit).ok, "kedalaman melebihi batas ditolak");
  limit.max_depth = 32;
  Check(nq::json::Parse(deep, limit).ok, "kedalaman wajar diterima");
}

// ---------------------------------------------------------------------------
// Pemuat ruleset
// ---------------------------------------------------------------------------
void TestRulesetLoading() {
  Begin("ruleset: berkas valid + checksum cocok -> LOADED (build lab)");
  const std::string path = WriteRuleset("valid", ValidRuleset(), true, false);
  const auto result = nq::LoadRulesetFromFile(path, LabOptions());
  Check(result.ok(), "status kLoaded");
  Check(result.ruleset.version == "1.0.0", "versi terbaca");
  Check(result.ruleset.schema == "nevus-ruleset/2", "skema terbaca");
  Check(result.ruleset.policy.blocked_hosts.size() == 2, "dua aturan host");
  if (result.ruleset.policy.blocked_hosts.size() == 2) {
    Check(result.ruleset.policy.blocked_hosts[0].mode == nq::MatchMode::kSuffix,
          "mode suffix terbaca");
    Check(result.ruleset.policy.blocked_hosts[1].mode == nq::MatchMode::kExact,
          "mode exact terbaca");
  }
  Check(result.ruleset.policy.https_only_top_level, "https_only_top_level");
  Check(result.ruleset.policy.send_gpc, "send_gpc");
  Check(result.ruleset.sha256_hex == ChecksumOf(ValidRuleset()),
        "checksum yang dicatat sama dengan isi berkas");
  Check(!result.ruleset.declared_signed, "signed=false terbaca");

  Begin("ruleset: berkas sah TETAPI unsigned pada build rilis -> DITOLAK");
  {
    // Perilaku fail-closed yang diinginkan: berkas yang sepenuhnya sah tetap
    // ditolak pada build rilis bila tidak bertanda tangan. Memakai
    // ReleaseOptions() di sini adalah uji asertif, bukan kelalaian.
    const auto r = nq::LoadRulesetFromFile(path, ReleaseOptions());
    Check(r.status == nq::RulesetStatus::kUnsignedRejected,
          "status kUnsignedRejected pada build rilis");
    Check(!r.ok(), "tidak dianggap berhasil");
  }

  Begin("ruleset: berkas hilang -> MISSING (bukan LOADED)");
  const auto missing =
      nq::LoadRulesetFromFile(TempDir() + "/tidak-ada.json", ReleaseOptions());
  Check(missing.status == nq::RulesetStatus::kMissing, "status kMissing");
  Check(!missing.ok(), "tidak dianggap berhasil");

  Begin("ruleset: JSON rusak -> MALFORMED_JSON");
  {
    const std::string bad = "{\"schema\": \"nevus-ruleset/2\",}";
    const std::string path_bad = WriteRuleset("malformed", bad, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kMalformedJson, "status kMalformedJson");
    Check(!r.detail.empty(), "detail galat diisi");
  }

  Begin("ruleset: checksum TIDAK cocok -> CHECKSUM_MISMATCH (fatal)");
  {
    const std::string path_bad =
        WriteRuleset("badsum", ValidRuleset(), true, /*corrupt=*/true);
    const auto r = nq::LoadRulesetFromFile(path_bad, ReleaseOptions());
    Check(r.status == nq::RulesetStatus::kChecksumMismatch,
          "status kChecksumMismatch");
    // Bahkan build lab (allow_unsigned) harus menolak checksum yang salah.
    const auto r_lab = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r_lab.status == nq::RulesetStatus::kChecksumMismatch,
          "allow_unsigned TIDAK boleh melewati checksum salah");
  }

  Begin("ruleset: sidecar checksum hilang pada build rilis -> CHECKSUM_MISSING");
  {
    const std::string path_bad =
        WriteRuleset("nosidecar", ValidRuleset(), /*sidecar=*/false, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, ReleaseOptions());
    Check(r.status == nq::RulesetStatus::kChecksumMissing,
          "status kChecksumMissing");
    // Build lab boleh tanpa sidecar.
    const auto r_lab = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r_lab.ok(), "build lab menerima tanpa sidecar");
  }

  Begin("ruleset: sidecar rusak -> CHECKSUM_MISSING");
  {
    const std::string content = ValidRuleset();
    const std::string path_bad = WriteRuleset("badsidecar", content, false, false);
    WriteFile(path_bad + ".sha256", "bukan-hash\n");
    const auto r = nq::LoadRulesetFromFile(path_bad, ReleaseOptions());
    Check(r.status == nq::RulesetStatus::kChecksumMissing,
          "sidecar non-hex ditolak");
  }

  Begin("ruleset: skema tidak didukung -> INVALID_SCHEMA");
  {
    const std::string content = ValidRuleset("nevus-ruleset/99");
    const std::string path_bad = WriteRuleset("badschema", content, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kInvalidSchema, "skema asing ditolak");
  }

  Begin("ruleset: skema hilang -> INVALID_SCHEMA");
  {
    std::string content = ValidRuleset();
    const std::string needle = "  \"schema\": \"nevus-ruleset/2\",\n";
    content.erase(content.find(needle), needle.size());
    const std::string path_bad = WriteRuleset("noschema", content, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kInvalidSchema, "skema wajib");
  }

  Begin("ruleset: permintaan pelonggaran kebijakan -> DITOLAK");
  {
    const std::string content = ValidRuleset("nevus-ruleset/2", "send_gpc");
    // Kirim "send_gpc": false di posisi awal blok policy.
    std::string relaxed = content;
    const std::string needle = "    \"send_gpc\": false,\n";
    Check(relaxed.find(needle) != std::string::npos,
          "penanda pelonggaran terbentuk");
    const std::string path_bad =
        WriteRuleset("relaxed", relaxed, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kPolicyRelaxationRejected,
          "send_gpc=false ditolak");
  }

  Begin("ruleset: policy tidak lengkap -> INVALID_SCHEMA");
  {
    std::string content = ValidRuleset();
    const std::string needle = "    \"send_gpc\": true,\n";
    content.erase(content.find(needle), needle.size());
    const std::string path_bad = WriteRuleset("nopolicy", content, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kInvalidSchema, "kunci policy wajib");
  }

  Begin("ruleset: host tidak sah / duplikat -> INVALID_SCHEMA");
  {
    std::string content = ValidRuleset();
    // Wildcard tidak boleh diterima: pencocokan sufiks akan menjadi ambigu.
    const std::string ok_host = "contoh-iklan.test";
    content.replace(content.find(ok_host), ok_host.size(), "*.iklan.test");
    const std::string path_bad = WriteRuleset("wildcard", content, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kInvalidSchema, "wildcard ditolak");
  }
  {
    std::string content = ValidRuleset();
    const std::string exact = "{ \"host\": \"lacak.test\", \"match\": \"exact\" }";
    content.replace(content.find(exact), exact.size(),
                    "{ \"host\": \"contoh-iklan.test\", \"match\": \"exact\" }");
    const std::string path_bad = WriteRuleset("dup", content, true, false);
    const auto r = nq::LoadRulesetFromFile(path_bad, LabOptions());
    Check(r.status == nq::RulesetStatus::kInvalidSchema, "host duplikat ditolak");
  }

  Begin("ruleset: signed=true tanpa verifier -> DITOLAK (gagal-tertutup)");
  {
    std::string content = ValidRuleset();
    content.replace(content.find("\"signed\": false"), std::string("\"signed\": false").size(),
                    "\"signed\": true");
    content.insert(content.rfind("}"),
                   ",\n  \"signature\": { \"algorithm\": \"uji-aman\", "
                   "\"value\": \"AAAA\" }\n");
    const std::string path_signed =
        WriteRuleset("signed_noverifier", content, true, false);
    const auto r = nq::LoadRulesetFromFile(path_signed, LabOptions());
    Check(r.status == nq::RulesetStatus::kSignatureAlgorithmUnsupported,
          "tanpa verifier terpasang -> ditolak");
  }

  Begin("ruleset: signed=true + verifier menolak -> SIGNATURE_INVALID");
  {
    std::string content = ValidRuleset();
    content.replace(content.find("\"signed\": false"), std::string("\"signed\": false").size(),
                    "\"signed\": true");
    content.insert(content.rfind("}"),
                   ",\n  \"signature\": { \"algorithm\": \"uji-aman\", "
                   "\"value\": \"AAAA\" }\n");
    const std::string path_signed =
        WriteRuleset("signed_reject", content, true, false);
    nq::RulesetLoadOptions options = LabOptions();
    const RejectingVerifier verifier;
    options.verifier = &verifier;
    const auto r = nq::LoadRulesetFromFile(path_signed, options);
    Check(r.status == nq::RulesetStatus::kSignatureInvalid,
          "tanda tangan ditolak verifier -> ditolak");
  }

  Begin("ruleset: signed=true + verifier menerima -> LOADED");
  {
    std::string content = ValidRuleset();
    content.replace(content.find("\"signed\": false"), std::string("\"signed\": false").size(),
                    "\"signed\": true");
    content.insert(content.rfind("}"),
                   ",\n  \"signature\": { \"algorithm\": \"uji-aman\", "
                   "\"value\": \"AAAA\" }\n");
    const std::string path_signed =
        WriteRuleset("signed_accept", content, true, false);
    nq::RulesetLoadOptions options = LabOptions();
    const AcceptingVerifier verifier;
    options.verifier = &verifier;
    const auto r = nq::LoadRulesetFromFile(path_signed, options);
    Check(r.ok(), "tanda tangan sah -> LOADED");
  }

  Begin("ruleset: signed=false pada build rilis -> UNSIGNED_REJECTED");
  {
    const std::string path_u = WriteRuleset("unsigned", ValidRuleset(), true, false);
    const auto r = nq::LoadRulesetFromFile(path_u, ReleaseOptions());
    Check(r.status == nq::RulesetStatus::kUnsignedRejected,
          "tanpa tanda tangan ditolak pada build rilis");
    const auto r_lab = nq::LoadRulesetFromFile(path_u, LabOptions());
    Check(r_lab.ok(), "build lab menerimanya");
  }

  Begin("ruleset: berkas terlalu besar -> TOO_LARGE (tanpa memuat seluruhnya)");
  {
    nq::RulesetLoadOptions options = LabOptions();
    options.max_bytes = 64;
    const std::string path_big =
        WriteRuleset("big", ValidRuleset(), true, false);
    const auto r = nq::LoadRulesetFromFile(path_big, options);
    Check(r.status == nq::RulesetStatus::kTooLarge, "batas ukuran berlaku");
  }

  Begin("ruleset: dari string dengan checksum yang diharapkan");
  {
    const std::string content = ValidRuleset();
    // Memakai LabOptions(): berkas uji ini unsigned, jadi ReleaseOptions() akan
    // menolaknya lebih dulu (kUnsignedRejected) sebelum checksum dinilai.
    const auto ok = nq::LoadRulesetFromString(content, LabOptions(),
                                             ChecksumOf(content));
    Check(ok.ok(), "checksum benar -> LOADED");
    const auto bad = nq::LoadRulesetFromString(content, LabOptions(),
                                              std::string(64, '0'));
    Check(bad.status == nq::RulesetStatus::kChecksumMismatch,
          "checksum salah -> ditolak");
    const auto none = nq::LoadRulesetFromString(content, ReleaseOptions(), "");
    Check(none.status == nq::RulesetStatus::kChecksumMissing,
          "tanpa checksum pada build rilis -> ditolak");
  }
}

// ---------------------------------------------------------------------------
// Kebijakan privasi
// ---------------------------------------------------------------------------
void TestPrivacyPolicy() {
  nq::PrivacyPolicy::ResetPolicyForTesting();

  Begin("kebijakan: keadaan awal adalah kebijakan paling ketat");
  Check(!nq::PrivacyPolicy::HasInstalledPolicy(), "belum terpasang");
  Check(nq::PrivacyPolicy::IsAllowedTopLevelScheme("https://contoh.test/"),
        "HTTPS diizinkan");
  Check(!nq::PrivacyPolicy::IsAllowedTopLevelScheme("http://contoh.test/"),
        "HTTP ditolak saat failsafe");
  Check(nq::PrivacyPolicy::ShouldSendGlobalPrivacyControl(),
        "GPC aktif saat failsafe");
  Check(nq::PrivacyPolicy::BlockedHostCount() == 0,
        "daftar blokir kosong saat failsafe (tidak ada default longgar)");
  Check(nq::PrivacyPolicy::IsPermissionDeniedByDefault(0), "izin ditolak");
  Check(nq::PrivacyPolicy::IsPermissionDeniedByDefault(999),
        "izin tak dikenal ditolak (gagal-tertutup)");

  Begin("kebijakan: pemasangan hanya sekali");
  const auto loaded = nq::LoadRulesetFromString(
      ValidRuleset(), LabOptions(), ChecksumOf(ValidRuleset()));
  Check(loaded.ok(), "ruleset uji dimuat");
  Check(nq::PrivacyPolicy::InstallPolicy(loaded.ruleset.policy),
        "pemasangan pertama diterima");
  Check(nq::PrivacyPolicy::HasInstalledPolicy(), "kebijakan terpasang");
  nq::RulesetPolicy looser = loaded.ruleset.policy;
  looser.blocked_hosts.clear();
  Check(!nq::PrivacyPolicy::InstallPolicy(looser),
        "pemasangan kedua DITOLAK (tidak bisa menurunkan kebijakan)");
  Check(nq::PrivacyPolicy::BlockedHostCount() == 2,
        "kebijakan lama tetap berlaku");

  Begin("kebijakan: pencocokan host terblokir dari ruleset");
  Check(nq::PrivacyPolicy::IsBlockedHost("https://contoh-iklan.test/ads.js"),
        "host persis terblokir");
  Check(nq::PrivacyPolicy::IsBlockedHost("https://sub.contoh-iklan.test/x"),
        "subdomain terblokir (suffix)");
  Check(!nq::PrivacyPolicy::IsBlockedHost("https://evil-contoh-iklan.test/x"),
        "domain yang hanya berakhiran sama TIDAK terblokir");
  Check(nq::PrivacyPolicy::IsBlockedHost("https://lacak.test/x"),
        "host exact terblokir");
  Check(!nq::PrivacyPolicy::IsBlockedHost("https://sub.lacak.test/x"),
        "exact tidak memblokir subdomain");
  Check(!nq::PrivacyPolicy::IsBlockedHost("https://contoh-aman.test/x"),
        "host tidak terkait lolos");
  Check(!nq::PrivacyPolicy::IsBlockedHost("bukan-url"),
        "bukan URL -> tidak dinilai (false)");
  Check(nq::PrivacyPolicy::IsBlockedHost(
            "https://pengguna:rahasia@contoh-iklan.test/x"),
        "userinfo tidak menipu pencocokan host");

  Begin("kebijakan: HTTPS-only untuk sub-sumber daya");
  Check(nq::PrivacyPolicy::IsAllowedSubresourceScheme("https://a.test/x.png"),
        "https diizinkan");
  Check(!nq::PrivacyPolicy::IsAllowedSubresourceScheme("http://a.test/x.png"),
        "http ditolak");
  Check(nq::PrivacyPolicy::IsAllowedSubresourceScheme("data:image/png;base64,AA"),
        "data: diizinkan (tidak melewati jaringan)");

  Begin("kebijakan: skema tingkat-atas");
  Check(nq::PrivacyPolicy::IsAllowedTopLevelScheme("nevus://bantuan"),
        "skema internal diizinkan");
  Check(!nq::PrivacyPolicy::IsAllowedTopLevelScheme("file:///etc/passwd"),
        "file:// ditolak");
  Check(!nq::PrivacyPolicy::IsAllowedTopLevelScheme("javascript:alert(1)"),
        "javascript: ditolak");

  Begin("kebijakan: header GPC");
  Check(nq::PrivacyPolicy::GlobalPrivacyControlHeader() == "Sec-GPC: 1",
        "nilai header benar");

  nq::PrivacyPolicy::ResetPolicyForTesting();
  Check(!nq::PrivacyPolicy::HasInstalledPolicy(), "reset berfungsi");
}

// ---------------------------------------------------------------------------
// Bootstrap (keputusan startup)
// ---------------------------------------------------------------------------
void TestBootstrap() {
  nq::PrivacyPolicy::ResetPolicyForTesting();

  Begin("bootstrap: ruleset hilang -> MENOLAK berjalan");
  {
    nq::BootstrapOptions options;
    options.explicit_path = TempDir() + "/tidak-ada-sama-sekali.json";
    const auto decision = nq::PlanBootstrap(options);
    Check(!decision.should_run, "should_run=false (default aman)");
    Check(!decision.using_failsafe_policy, "bukan mode failsafe");
    Check(decision.status == nq::RulesetStatus::kMissing, "status kMissing");
    Check(!decision.message.empty(), "pesan diisi");
  }

  Begin("bootstrap: ruleset hilang + opt-in failsafe -> berjalan paling ketat");
  {
    nq::PrivacyPolicy::ResetPolicyForTesting();
    nq::BootstrapOptions options;
    options.explicit_path = TempDir() + "/tidak-ada-2.json";
    options.allow_failsafe = true;
    const auto decision = nq::PlanBootstrap(options);
    Check(decision.should_run, "should_run=true (opt-in eksplisit)");
    Check(decision.using_failsafe_policy, "menandai kebijakan failsafe");
    Check(nq::PrivacyPolicy::HasInstalledPolicy(), "kebijakan terpasang");
    Check(!nq::PrivacyPolicy::IsAllowedTopLevelScheme("http://a.test/"),
          "HTTP tetap ditolak pada mode failsafe");
    Check(nq::PrivacyPolicy::BlockedHostCount() == 0, "tanpa aturan host");
  }

  Begin("bootstrap: ruleset sah -> berjalan dengan kebijakan ruleset");
  {
    nq::PrivacyPolicy::ResetPolicyForTesting();
    const std::string content = ValidRuleset();
    const std::string path = WriteRuleset("bootstrap_ok", content, true, false);
    nq::BootstrapOptions options;
    options.explicit_path = path;
    // Berkas uji ini tidak bertanda tangan, jadi build lab yang dipilih di sini.
    options.allow_unsigned = true;
    const auto decision = nq::PlanBootstrap(options);
    Check(decision.should_run, "should_run=true");
    Check(!decision.using_failsafe_policy, "memakai kebijakan ruleset");
    Check(nq::PrivacyPolicy::BlockedHostCount() == 2,
          "aturan host aktif di kebijakan global");
    Check(nq::PrivacyPolicy::IsBlockedHost("https://sub.contoh-iklan.test/a"),
          "pemblokiran berlaku setelah bootstrap");
  }

  Begin("bootstrap: checksum salah -> MENOLAK berjalan");
  {
    nq::PrivacyPolicy::ResetPolicyForTesting();
    const std::string path =
        WriteRuleset("bootstrap_badsum", ValidRuleset(), true, true);
    nq::BootstrapOptions options;
    options.explicit_path = path;
    const auto decision = nq::PlanBootstrap(options);
    Check(!decision.should_run, "should_run=false");
    Check(decision.status == nq::RulesetStatus::kChecksumMismatch,
          "status kChecksumMismatch");
    Check(!nq::PrivacyPolicy::HasInstalledPolicy(),
          "kebijakan TIDAK dipasang saat gagal");
  }

  Begin("bootstrap: prioritas jalur (argumen > env > argv[0])");
  {
    nq::BootstrapOptions options;
    options.argv0 = "/opt/nevus/bin/nevusquetta";
    Check(nq::DefaultRulesetPathFor(options.argv0) ==
              "/opt/nevus/bin/assets/nevus_ruleset.json",
          "jalur default relatif executable");
    options.explicit_path = "/tmp/eksplisit.json";
    options.env_path = "/tmp/env.json";
    const auto decision = nq::PlanBootstrap(options);
    Check(decision.resolved_path == "/tmp/eksplisit.json",
          "argumen eksplisit menang");
  }

  Begin("bootstrap: parsing argumen");
  {
    char arg0[] = "nevusquetta";
    char arg1[] = "--ruleset=/tmp/r.json";
    char arg2[] = "--allow-unsigned-ruleset";
    char arg3[] = "--allow-failsafe-policy";
    char arg4[] = "https://situs-awal.test/";
    char* argv[] = {arg0, arg1, arg2, arg3, arg4};
    nq::BootstrapOptions options;
    std::string error;
    Check(nq::ParseBootstrapArguments(5, argv, &options, &error),
          "argumen sah diterima");
    Check(options.explicit_path == "/tmp/r.json", "jalur ruleset terbaca");
    Check(options.allow_unsigned, "flag unsigned terbaca");
    Check(options.allow_failsafe, "flag failsafe terbaca");
    Check(options.argv0 == "nevusquetta", "argv0 tersimpan");
  }

  Begin("bootstrap: argumen --xxx tidak dikenal -> DITOLAK (fail-closed)");
  {
    char arg0[] = "nevusquetta";
    char arg1[] = "--flags-yang-tidak-ada";
    char* argv[] = {arg0, arg1};
    nq::BootstrapOptions options;
    std::string error;
    Check(!nq::ParseBootstrapArguments(2, argv, &options, &error),
          "argumen tidak dikenal menolak startup");
    Check(!error.empty(), "pesan galat diisi");
  }
}

}  // namespace

int main() {
  std::printf("== Unit test pemuatan ruleset NevusQuetta (C-Chromium) ==\n");
  TestSha256();
  TestJson();
  TestRulesetLoading();
  TestPrivacyPolicy();
  TestBootstrap();

  std::printf("\n== Ringkasan ==\n");
  std::printf("pemeriksaan : %d\n", g_checks);
  std::printf("gagal       : %d\n", g_failures);
  if (g_failures == 0) {
    std::printf("HASIL       : LULUS\n");
    return 0;
  }
  std::printf("HASIL       : GAGAL\n");
  return 1;
}
