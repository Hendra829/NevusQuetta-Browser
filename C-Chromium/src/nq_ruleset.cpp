#include "nq_ruleset.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <set>
#include <sstream>

#include "nq_json.h"
#include "nq_sha256.h"

namespace nq {
namespace {

constexpr const char* kSchemaV2 = "nevus-ruleset/2";

std::string Trim(const std::string& in) {
  std::size_t begin = 0;
  std::size_t end = in.size();
  while (begin < end && (in[begin] == ' ' || in[begin] == '\t' ||
                         in[begin] == '\r' || in[begin] == '\n')) {
    ++begin;
  }
  while (end > begin && (in[end - 1] == ' ' || in[end - 1] == '\t' ||
                         in[end - 1] == '\r' || in[end - 1] == '\n')) {
    --end;
  }
  return in.substr(begin, end - begin);
}

std::string Lower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return value;
}

// Nilai string wajib: kunci tidak ada, bukan string, atau kosong -> gagal.
bool RequireString(const json::Value& object, const char* key, std::string* out,
                   std::string* error) {
  const json::Value* value = object.Find(key);
  if (value == nullptr) {
    *error = std::string("kunci wajib '") + key + "' tidak ada";
    return false;
  }
  if (!value->IsString()) {
    *error = std::string("kunci '") + key + "' harus berupa string";
    return false;
  }
  if (Trim(value->string_value()).empty()) {
    *error = std::string("kunci '") + key + "' tidak boleh kosong";
    return false;
  }
  *out = value->string_value();
  return true;
}

bool RequireBool(const json::Value& object, const char* key, bool* out,
                 std::string* error) {
  const json::Value* value = object.Find(key);
  if (value == nullptr) {
    *error = std::string("kunci wajib '") + key + "' tidak ada";
    return false;
  }
  if (!value->IsBool()) {
    *error = std::string("kunci '") + key + "' harus berupa boolean";
    return false;
  }
  *out = value->bool_value();
  return true;
}

bool IsValidHost(const std::string& host) {
  if (host.empty() || host.size() > 253) return false;
  // Hanya huruf, angka, titik, dan tanda hubung. Menolak wildcard, spasi, jalur,
  // dan karakter yang bisa membuat pencocokan sufiks ambigu.
  for (char c : host) {
    const bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
                    c == '.' || c == '-';
    if (!ok) return false;
  }
  if (host.front() == '.' || host.back() == '.') return false;
  if (host.find("..") != std::string::npos) return false;
  if (host.front() == '-') return false;
  // Wajib punya label TLD berupa huruf, mencegah entri seperti "127.0.0.1".
  const auto dot = host.rfind('.');
  if (dot == std::string::npos || dot + 1 >= host.size()) return false;
  for (std::size_t i = dot + 1; i < host.size(); ++i) {
    if (!(host[i] >= 'a' && host[i] <= 'z')) return false;
  }
  return true;
}

bool IsSupportedSchema(const std::vector<std::string>& supported,
                       const std::string& schema) {
  return std::find(supported.begin(), supported.end(), schema) !=
         supported.end();
}

RulesetLoadResult Fail(RulesetStatus status, const std::string& detail) {
  RulesetLoadResult r;
  r.status = status;
  r.detail = detail;
  return r;
}

// Membaca berkas dengan batas ukuran DITERAPKAN SAAT MEMBACA: berkas 8 GiB tidak
// pernah masuk memori hanya untuk ditolak.
bool ReadFileBounded(const std::string& path, std::size_t max_bytes,
                     std::string* out, std::string* error, bool* too_large,
                     bool* missing) {
  *too_large = false;
  *missing = false;
  std::ifstream file(path, std::ios::binary);
  if (!file) {
    *missing = true;
    *error = "berkas tidak dapat dibuka";
    return false;
  }
  out->clear();
  char buffer[64 * 1024];
  while (file) {
    file.read(buffer, sizeof(buffer));
    const std::streamsize got = file.gcount();
    if (got <= 0) break;
    if (out->size() + static_cast<std::size_t>(got) > max_bytes) {
      *too_large = true;
      *error = "berkas melebihi batas ukuran";
      return false;
    }
    out->append(buffer, static_cast<std::size_t>(got));
  }
  if (file.bad()) {
    *error = "kesalahan baca berkas";
    return false;
  }
  return true;
}

// Mengurai sidecar checksum: menerima "<hex>" atau "<hex>  namaberkas".
bool ParseChecksumSidecar(const std::string& text, std::string* hex) {
  const std::string trimmed = Trim(text);
  if (trimmed.empty()) return false;
  const auto space = trimmed.find_first_of(" \t");
  std::string token = (space == std::string::npos) ? trimmed
                                                   : trimmed.substr(0, space);
  token = Trim(token);
  Sha256::Digest digest{};
  if (!Sha256::FromHex(token, &digest)) return false;
  *hex = Sha256::ToHex(digest);  // normalisasi ke huruf kecil
  return true;
}

}  // namespace

const char* RulesetStatusName(RulesetStatus status) {
  switch (status) {
    case RulesetStatus::kLoaded: return "LOADED";
    case RulesetStatus::kMissing: return "MISSING";
    case RulesetStatus::kUnreadable: return "UNREADABLE";
    case RulesetStatus::kTooLarge: return "TOO_LARGE";
    case RulesetStatus::kMalformedJson: return "MALFORMED_JSON";
    case RulesetStatus::kInvalidSchema: return "INVALID_SCHEMA";
    case RulesetStatus::kPolicyRelaxationRejected:
      return "POLICY_RELAXATION_REJECTED";
    case RulesetStatus::kChecksumMissing: return "CHECKSUM_MISSING";
    case RulesetStatus::kChecksumMismatch: return "CHECKSUM_MISMATCH";
    case RulesetStatus::kSignatureMissing: return "SIGNATURE_MISSING";
    case RulesetStatus::kSignatureAlgorithmUnsupported:
      return "SIGNATURE_ALGORITHM_UNSUPPORTED";
    case RulesetStatus::kSignatureInvalid: return "SIGNATURE_INVALID";
    case RulesetStatus::kUnsignedRejected: return "UNSIGNED_REJECTED";
  }
  return "UNKNOWN";
}

RulesetPolicy BuildFailsafePolicy() {
  RulesetPolicy policy;
  policy.https_only_top_level = true;
  policy.https_only_subresource = true;
  policy.send_gpc = true;
  policy.deny_permissions_by_default = true;
  policy.blocked_hosts.clear();
  return policy;
}

std::string DescribeRuleset(const Ruleset& ruleset) {
  std::ostringstream out;
  out << "ruleset name=\"" << ruleset.name << "\" version=\"" << ruleset.version
      << "\" schema=\"" << ruleset.schema << "\" hosts="
      << ruleset.policy.blocked_hosts.size()
      << " signed=" << (ruleset.declared_signed ? "true" : "false")
      << " sha256=" << ruleset.sha256_hex;
  return out.str();
}

namespace {

// Memvalidasi objek root ruleset menjadi RulesetPolicy.
RulesetLoadResult Validate(const json::Value& root,
                           const RulesetLoadOptions& options,
                           const std::string& actual_checksum,
                           const std::string& text) {
  if (!root.IsObject()) {
    return Fail(RulesetStatus::kInvalidSchema, "akar JSON harus berupa objek");
  }

  Ruleset ruleset;
  std::string error;

  if (!RequireString(root, "name", &ruleset.name, &error) ||
      !RequireString(root, "version", &ruleset.version, &error) ||
      !RequireString(root, "license", &ruleset.license, &error) ||
      !RequireBool(root, "signed", &ruleset.declared_signed, &error)) {
    return Fail(RulesetStatus::kInvalidSchema, error);
  }

  // Skema: wajib ada dan wajib didukung. Ruleset versi lebih baru bisa memuat
  // semantik yang belum dipahami build ini, jadi harus ditolak, bukan diabaikan.
  std::string schema;
  if (!RequireString(root, "schema", &schema, &error)) {
    return Fail(RulesetStatus::kInvalidSchema, error);
  }
  if (!IsSupportedSchema(options.supported_schemas, schema)) {
    return Fail(RulesetStatus::kInvalidSchema,
                "skema '" + schema + "' tidak didukung build ini");
  }
  ruleset.schema = schema;

  const json::Value* policy = root.Find("policy");
  if (policy == nullptr || !policy->IsObject()) {
    return Fail(RulesetStatus::kInvalidSchema,
                "kunci 'policy' harus ada dan berupa objek");
  }

  // Kebijakan hanya boleh diperketat. Setiap pelonggaran yang belum punya
  // kendali UI eksplisit DITOLAK, bukan diterima diam-diam.
  const char* relaxation_keys[] = {"https_only_top_level",
                                   "https_only_subresource",
                                   "send_gpc",
                                   "deny_permissions_by_default"};
  for (const char* key : relaxation_keys) {
    const json::Value* value = policy->Find(key);
    if (value == nullptr) {
      return Fail(RulesetStatus::kInvalidSchema,
                  std::string("policy.") + key + " wajib ada");
    }
    if (!value->IsBool()) {
      return Fail(RulesetStatus::kInvalidSchema,
                  std::string("policy.") + key + " harus boolean");
    }
    if (!value->bool_value()) {
      return Fail(RulesetStatus::kPolicyRelaxationRejected,
                  std::string("policy.") + key +
                      "=false meminta pelonggaran yang belum didukung UI");
    }
  }
  ruleset.policy.https_only_top_level =
      policy->Find("https_only_top_level")->bool_value();
  ruleset.policy.https_only_subresource =
      policy->Find("https_only_subresource")->bool_value();
  ruleset.policy.send_gpc = policy->Find("send_gpc")->bool_value();
  ruleset.policy.deny_permissions_by_default =
      policy->Find("deny_permissions_by_default")->bool_value();

  const json::Value* rules = root.Find("rules");
  if (rules == nullptr || !rules->IsArray()) {
    return Fail(RulesetStatus::kInvalidSchema,
                "kunci 'rules' harus ada dan berupa array");
  }
  std::set<std::string> seen_hosts;
  for (const json::Value& rule : rules->elements()) {
    if (!rule.IsObject()) {
      return Fail(RulesetStatus::kInvalidSchema,
                  "setiap entri 'rules' harus berupa objek");
    }
    BlockRule block;
    if (!RequireString(rule, "host", &block.host, &error)) {
      return Fail(RulesetStatus::kInvalidSchema, error);
    }
    block.host = Lower(Trim(block.host));
    if (!IsValidHost(block.host)) {
      return Fail(RulesetStatus::kInvalidSchema,
                  "host tidak sah di rules: '" + block.host + "'");
    }
    std::string match;
    if (!RequireString(rule, "match", &match, &error)) {
      return Fail(RulesetStatus::kInvalidSchema, error);
    }
    const std::string lowered_match = Lower(Trim(match));
    if (lowered_match == "suffix") {
      block.mode = MatchMode::kSuffix;
    } else if (lowered_match == "exact") {
      block.mode = MatchMode::kExact;
    } else {
      return Fail(RulesetStatus::kInvalidSchema,
                  "match tidak dikenal: '" + match + "' (harus suffix|exact)");
    }
    // Host duplikat membuat jumlah aturan tidak dapat dipertanggungjawabkan.
    if (!seen_hosts.insert(block.host).second) {
      return Fail(RulesetStatus::kInvalidSchema,
                  "host duplikat di rules: '" + block.host + "'");
    }
    ruleset.policy.blocked_hosts.push_back(block);
  }

  // --- Autentisitas -------------------------------------------------------
  // Urutan: checksum dulu (integritas), lalu tanda tangan (autentisitas).
  if (ruleset.declared_signed) {
    const json::Value* signature = root.Find("signature");
    if (signature == nullptr || !signature->IsObject()) {
      return Fail(RulesetStatus::kSignatureMissing,
                  "'signed': true tetapi blok 'signature' tidak ada");
    }
    if (options.verifier == nullptr) {
      return Fail(RulesetStatus::kSignatureAlgorithmUnsupported,
                  "build ini tidak menyertakan verifier tanda tangan; ruleset "
                  "bertanda tangan tidak dapat diverifikasi");
    }
    std::string algorithm;
    std::string signature_value;
    if (!RequireString(*signature, "algorithm", &algorithm, &error) ||
        !RequireString(*signature, "value", &signature_value, &error)) {
      return Fail(RulesetStatus::kInvalidSchema, error);
    }
    if (Lower(algorithm) != Lower(options.verifier->algorithm())) {
      return Fail(RulesetStatus::kSignatureAlgorithmUnsupported,
                  "algoritma tanda tangan '" + algorithm + "' tidak didukung");
    }
    // Payload = seluruh berkas tanpa blok signature tidak mungkin dihitung di
    // sini; verifier menerima teks penuh dan memutuskan sendiri. Verifier
    // bertanggung jawab menolak bila canonic form tidak dikenali.
    if (!options.verifier->Verify(text, signature_value, options.public_key)) {
      return Fail(RulesetStatus::kSignatureInvalid,
                  "verifikasi tanda tangan GAGAL");
    }
  } else if (!options.allow_unsigned) {
    return Fail(RulesetStatus::kUnsignedRejected,
                "ruleset tanpa tanda tangan ditolak (build rilis)");
  }

  ruleset.sha256_hex = actual_checksum;
  RulesetLoadResult result;
  result.status = RulesetStatus::kLoaded;
  result.ruleset = std::move(ruleset);
  result.detail = "ruleset dimuat";
  return result;
}

}  // namespace

RulesetLoadResult LoadRulesetFromString(const std::string& text,
                                        const RulesetLoadOptions& options,
                                        const std::string& expected_checksum) {
  const std::string actual = Sha256::ToHex(Sha256::Hash(text));

  // Checksum: bila diharapkan, ketidakcocokan SELALU fatal dan tidak dapat
  // dilewati oleh allow_unsigned.
  if (!expected_checksum.empty()) {
    Sha256::Digest want{};
    if (!Sha256::FromHex(Trim(expected_checksum), &want)) {
      return Fail(RulesetStatus::kChecksumMissing,
                  "checksum yang diharapkan tidak berbentuk hex SHA-256");
    }
    if (Sha256::ToHex(want) != actual) {
      return Fail(RulesetStatus::kChecksumMismatch,
                  "checksum tidak cocok: diharapkan " + Sha256::ToHex(want) +
                      ", dihitung " + actual);
    }
  } else if (!options.allow_unsigned) {
    return Fail(RulesetStatus::kChecksumMissing,
                "sidecar checksum tidak ada; build rilis menolak ruleset tanpa "
                "checksum");
  }

  if (text.size() > options.max_bytes) {
    return Fail(RulesetStatus::kTooLarge, "berkas melebihi batas ukuran");
  }

  const json::ParseResult parsed = json::Parse(text);
  if (!parsed.ok) {
    return Fail(RulesetStatus::kMalformedJson,
                parsed.error + " (offset " + std::to_string(parsed.error_offset) +
                    ")");
  }
  return Validate(parsed.value, options, actual, text);
}

RulesetLoadResult LoadRulesetFromFile(const std::string& path,
                                      const RulesetLoadOptions& options) {
  std::string text;
  std::string error;
  bool too_large = false;
  bool missing = false;

  // Berkas utama diperiksa lebih dulu. Bila ia sendiri tidak ada, status yang
  // jujur adalah kMissing, bukan galat checksum: tanpa urutan ini operator akan
  // mengejar sidecar checksum padahal yang salah adalah jalur berkas ruleset.
  {
    std::ifstream probe(path, std::ios::binary);
    if (!probe) {
      return Fail(RulesetStatus::kMissing, "berkas tidak ada: " + path);
    }
  }

  // Sidecar checksum dibaca berikutnya: tanpa itu build rilis tidak akan
  // menerima berkas, jadi tidak ada gunanya mem-parse isinya.
  const std::string checksum_path =
      options.checksum_path.empty() ? path + ".sha256" : options.checksum_path;

  std::string expected;
  if (!options.allow_unsigned) {
    std::string sidecar;
    if (!ReadFileBounded(checksum_path, 4096, &sidecar, &error, &too_large,
                         &missing)) {
      return Fail(RulesetStatus::kChecksumMissing,
                  "sidecar checksum tidak dapat dibaca: " + checksum_path);
    }
    if (!ParseChecksumSidecar(sidecar, &expected)) {
      return Fail(RulesetStatus::kChecksumMissing,
                  "sidecar checksum tidak berisi hash SHA-256 yang sah: " +
                      checksum_path);
    }
  } else {
    std::string sidecar;
    if (ReadFileBounded(checksum_path, 4096, &sidecar, &error, &too_large,
                        &missing)) {
      ParseChecksumSidecar(sidecar, &expected);  // opsional pada build lab
    }
  }

  if (!ReadFileBounded(path, options.max_bytes, &text, &error, &too_large,
                       &missing)) {
    if (too_large) return Fail(RulesetStatus::kTooLarge, error);
    if (missing) return Fail(RulesetStatus::kMissing, "berkas tidak ada: " + path);
    return Fail(RulesetStatus::kUnreadable, error);
  }

  return LoadRulesetFromString(text, options, expected);
}

}  // namespace nq
