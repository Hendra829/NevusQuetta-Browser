// Uji kanonikalisasi JSON RFC 8785 (JCS).
//
// Tiga hal yang dibuktikan:
//   (a) dokumen yang sama dengan urutan kunci / spasi / escape berbeda
//       menghasilkan byte kanonik IDENTIK;
//   (b) dokumen yang isinya diubah menghasilkan byte kanonik BERBEDA, dan
//       tanda tangan atas bentuk kanonik lama DITOLAK;
//   (c) vektor uji resmi RFC 8785 (contoh §3.2.2 dan tabel angka Appendix B)
//       cocok persis.
#include <cstdio>
#include <string>
#include <vector>

#include "nq_ed25519.h"
#include "nq_jcs.h"

#if defined(NQ_HAVE_LIBSODIUM)
#include <sodium.h>
#endif

namespace {

int g_fail = 0;
int g_total = 0;

void Check(bool condition, const std::string& what) {
  ++g_total;
  if (condition) {
    std::printf("  OK    %s\n", what.c_str());
  } else {
    std::printf("  GAGAL %s\n", what.c_str());
    ++g_fail;
  }
}

void CheckEq(const std::string& got, const std::string& want,
             const std::string& what) {
  ++g_total;
  if (got == want) {
    std::printf("  OK    %s\n", what.c_str());
  } else {
    std::printf("  GAGAL %s\n", what.c_str());
    std::printf("        harap: %s\n", want.c_str());
    std::printf("        dapat: %s\n", got.c_str());
    ++g_fail;
  }
}

std::string Canon(const std::string& in) {
  const nq::jcs::Result r = nq::jcs::Canonicalize(in);
  return r.ok ? r.canonical : std::string("<GAGAL: ") + r.error + ">";
}

}  // namespace

int main() {
  std::printf("== Uji kanonikalisasi JSON RFC 8785 (JCS) ==\n");

  // --- (c) Vektor resmi RFC 8785 §3.2.2 -----------------------------------
  {
    std::printf("  -- vektor resmi RFC 8785 --\n");
    const std::string input =
        "{\n"
        "  \"numbers\": [333333333.33333329, 1E30, 4.50,\n"
        "              2e-3, 0.000000000000000000000000001],\n"
        "  \"string\": \"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\",\n"
        "  \"literals\": [null, true, false]\n"
        "}";
    // Bentuk kanonik yang diharapkan, disalin dari RFC 8785 §3.2.3.
    const std::string expected =
        R"RAW({"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€$\u000f\nA'B\"\\\\\"/"})RAW";
    CheckEq(Canon(input), expected, "contoh RFC 8785 §3.2.2 -> bentuk kanonik persis");
  }

  // --- (c) Vektor angka Appendix B ----------------------------------------
  {
    std::printf("  -- vektor angka RFC 8785 Appendix B --\n");
    struct NumVector {
      const char* input;
      const char* expected;
    };
    static const NumVector kVectors[] = {
        {"0", "0"},
        {"-0", "0"},
        {"5e-324", "5e-324"},
        {"-5e-324", "-5e-324"},
        {"1.7976931348623157e308", "1.7976931348623157e+308"},
        {"-1.7976931348623157e308", "-1.7976931348623157e+308"},
        {"9007199254740992", "9007199254740992"},
        {"-9007199254740992", "-9007199254740992"},
        {"295147905179352830000", "295147905179352830000"},
        {"9.999999999999997e22", "9.999999999999997e+22"},
        {"1e23", "1e+23"},
        {"1.0000000000000001e23", "1.0000000000000001e+23"},
        {"999999999999999700000", "999999999999999700000"},
        {"1e21", "1e+21"},
        {"9.999999999999997e-7", "9.999999999999997e-7"},
        {"0.000001", "0.000001"},
        {"333333333.3333332", "333333333.3333332"},
        {"333333333.33333325", "333333333.33333325"},
        {"333333333.3333333", "333333333.3333333"},
        {"333333333.3333334", "333333333.3333334"},
        {"333333333.33333343", "333333333.33333343"},
        {"-0.0000033333333333333333", "-0.0000033333333333333333"},
        {"1424953923781206.2", "1424953923781206.2"},
    };
    int ok_count = 0;
    for (const NumVector& v : kVectors) {
      if (Canon(v.input) == v.expected) {
        ++ok_count;
      } else {
        std::printf("        BEDA: %s -> %s (harap %s)\n", v.input,
                    Canon(v.input).c_str(), v.expected);
      }
    }
    Check(ok_count == static_cast<int>(sizeof(kVectors) / sizeof(kVectors[0])),
          "seluruh " + std::to_string(sizeof(kVectors) / sizeof(kVectors[0])) +
              " vektor angka Appendix B cocok (" + std::to_string(ok_count) +
              " cocok)");
  }

  // --- (a) Urutan kunci, spasi, escape -> byte kanonik identik ------------
  {
    std::printf("  -- (a) bentuk setara -> kanonik identik --\n");
    const std::string a = "{\"b\":1,\"a\":2,\"c\":{\"z\":1,\"y\":2}}";
    const std::string b = "{ \"a\" : 2 , \"b\" : 1 , \"c\" : { \"y\" : 2 , \"z\" : 1 } }";
    const std::string c = "{\"c\":{\"y\":2,\"z\":1},\"b\":1,\"a\":2}";
    const std::string want = "{\"a\":2,\"b\":1,\"c\":{\"y\":2,\"z\":1}}";
    CheckEq(Canon(a), want, "urutan kunci asli -> terurut");
    CheckEq(Canon(b), want, "spasi berlebih -> hilang, kanonik sama");
    CheckEq(Canon(c), want, "urutan kunci berbeda -> kanonik sama");
    Check(Canon(a) == Canon(b) && Canon(b) == Canon(c),
          "tiga bentuk setara menghasilkan byte kanonik IDENTIK");

    // Escape setara: "\u0041" dan "A" adalah string yang sama.
    CheckEq(Canon("{\"k\":\"\\u0041\"}"), Canon("{\"k\":\"A\"}"),
            "escape \\u0041 setara dengan A");
    // Escape garis miring: "\/" dan "/" sama.
    CheckEq(Canon("{\"k\":\"\\/\"}"), Canon("{\"k\":\"/\"}"),
            "escape \\/ setara dengan /");
    // Urutan elemen array TIDAK boleh diubah.
    CheckEq(Canon("[3,1,2]"), "[3,1,2]", "urutan elemen array dipertahankan");
  }

  // --- Pengurutan menurut code unit UTF-16 --------------------------------
  {
    std::printf("  -- pengurutan UTF-16 (RFC 8785 §3.2.3) --\n");
    // U+20AC (€) = 0x20AC; U+00E9 (é) = 0x00E9; U+1F600 = surrogate D83D DE00.
    // Urutan code unit: 0x00E9 < 0x20AC < 0xD83D.
    const std::string in =
        "{\"\xf0\x9f\x98\x80\":1,\"\xe2\x82\xac\":2,\"\xc3\xa9\":3}";
    const std::string want =
        "{\"\xc3\xa9\":3,\"\xe2\x82\xac\":2,\"\xf0\x9f\x98\x80\":1}";
    CheckEq(Canon(in), want, "kunci diurutkan menurut code unit UTF-16");
  }

  // --- Escape string menurut ECMAScript -----------------------------------
  {
    std::printf("  -- escape string --\n");
    CheckEq(Canon("{\"k\":\"\\u0000\"}"), "{\"k\":\"\\u0000\"}",
            "U+0000 -> \\u0000 huruf kecil");
    CheckEq(Canon("{\"k\":\"\\u001f\"}"), "{\"k\":\"\\u001f\"}",
            "U+001F -> \\u001f huruf kecil");
    CheckEq(Canon("{\"k\":\"\\b\\t\\n\\f\\r\"}"),
            "{\"k\":\"\\b\\t\\n\\f\\r\"}",
            "kontrol khusus -> \\b \\t \\n \\f \\r");
    CheckEq(Canon("{\"k\":\"a\\\"b\"}"), "{\"k\":\"a\\\"b\"}",
            "kutip ganda -> \\\"");
    CheckEq(Canon("{\"k\":\"a\\\\b\"}"), "{\"k\":\"a\\\\b\"}",
            "garis miring terbalik -> \\\\");
    // Non-ASCII di luar rentang kontrol diserialkan apa adanya.
    CheckEq(Canon("{\"k\":\"\\u20ac\"}"), "{\"k\":\"\xe2\x82\xac\"}",
            "U+20AC diserialkan apa adanya (UTF-8)");
  }

  // --- CanonicalizeWithoutMember ------------------------------------------
  {
    std::printf("  -- keluarkan blok signature --\n");
    const std::string doc =
        "{\"schema\":\"nevus-ruleset/2\",\"signed\":true,"
        "\"signature\":{\"algorithm\":\"ed25519\",\"value\":\"aa\"},"
        "\"policy\":{\"send_gpc\":true}}";
    const nq::jcs::Result r =
        nq::jcs::CanonicalizeWithoutMember(doc, "signature");
    Check(r.ok, "CanonicalizeWithoutMember berhasil");
    CheckEq(r.canonical,
            "{\"policy\":{\"send_gpc\":true},\"schema\":\"nevus-ruleset/2\","
            "\"signed\":true}",
            "blok signature dikeluarkan dari payload");
    Check(r.canonical.find("signature") == std::string::npos,
          "payload kanonik tidak memuat 'signature'");
    // Bila anggota tidak ada, hasilnya sama dengan Canonicalize biasa.
    const nq::jcs::Result r2 =
        nq::jcs::CanonicalizeWithoutMember("{\"a\":1}", "signature");
    Check(r2.ok && r2.canonical == "{\"a\":1}",
          "anggota tidak ada -> sama dengan kanonikalisasi biasa");
  }

  // --- Gagal-tertutup -----------------------------------------------------
  {
    std::printf("  -- gagal-tertutup --\n");
    Check(!nq::jcs::Canonicalize("{").ok, "JSON terpotong -> gagal");
    Check(!nq::jcs::Canonicalize("{\"a\":1} x").ok,
          "byte tambahan setelah nilai -> gagal");
    Check(!nq::jcs::Canonicalize("{\"a\":1,\"a\":2}").ok,
          "kunci duplikat -> gagal");
    Check(!nq::jcs::Canonicalize("NaN").ok, "NaN -> gagal");
    Check(!nq::jcs::Canonicalize("Infinity").ok, "Infinity -> gagal");
    Check(!nq::jcs::Canonicalize("1e999").ok, "1e999 (overflow) -> gagal");
    // UTF-8 tidak sah: byte 0xFF sendirian.
    Check(!nq::jcs::Canonicalize("{\"k\":\"\xff\"}").ok,
          "UTF-8 tidak sah -> gagal");
    // Bentuk overlong: 0xC0 0x80 adalah overlong untuk U+0000.
    Check(!nq::jcs::Canonicalize("{\"k\":\"\xc0\x80\"}").ok,
          "UTF-8 overlong -> gagal");
    // Surrogate mentah dalam UTF-8: ED A0 80 = U+D800.
    Check(!nq::jcs::Canonicalize("{\"k\":\"\xed\xa0\x80\"}").ok,
          "UTF-8 titik kode surrogate -> gagal");
    // Dokumen tingkat atas bukan objek (untuk CanonicalizeWithoutMember).
    Check(!nq::jcs::CanonicalizeWithoutMember("[1,2]", "signature").ok,
          "CanonicalizeWithoutMember menolak non-objek");
  }

  // --- (b) Integrasi tanda tangan: kosmetik tetap sah, isi berubah ditolak -
#if defined(NQ_HAVE_LIBSODIUM)
  {
    std::printf("  -- (b) integrasi tanda tangan atas bentuk kanonik --\n");
    if (sodium_init() < 0) {
      Check(false, "sodium_init gagal");
    } else {
      // Kunci dari RFC 8032 §7.1 TEST 1 (seed resmi), sehingga kunci publik
      // yang dihasilkan dapat diperiksa terhadap vektor resmi.
      const char* seed_hex =
          "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
      std::uint8_t seed[32];
      nq::HexToBytes(seed_hex, seed, 32);
      std::uint8_t pk[32];
      std::uint8_t sk[64];
      crypto_sign_seed_keypair(pk, sk, seed);

      std::uint8_t pk_expected[32];
      nq::HexToBytes(
          "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
          pk_expected, 32);
      Check(std::string(reinterpret_cast<char*>(pk), 32) ==
                std::string(reinterpret_cast<char*>(pk_expected), 32),
            "kunci publik dari seed RFC 8032 TEST 1 cocok vektor resmi");

      // Payload ruleset (TANPA blok signature) dalam dua bentuk setara.
      const std::string payload_a =
          "{\"schema\":\"nevus-ruleset/2\",\"signed\":true,"
          "\"policy\":{\"send_gpc\":true,\"https_only_top_level\":true}}";
      const std::string payload_b =
          "{ \"policy\" : { \"https_only_top_level\" : true , \"send_gpc\" : true } ,"
          " \"signed\" : true , \"schema\" : \"nevus-ruleset/2\" }";

      const nq::jcs::Result ca = nq::jcs::Canonicalize(payload_a);
      const nq::jcs::Result cb = nq::jcs::Canonicalize(payload_b);
      Check(ca.ok && cb.ok && ca.canonical == cb.canonical,
            "dua bentuk payload setara -> kanonik identik");

      // Tanda tangani bentuk kanonik.
      std::uint8_t sig[64];
      crypto_sign_detached(
          sig, nullptr,
          reinterpret_cast<const unsigned char*>(ca.canonical.data()),
          ca.canonical.size(), sk);

      // Verifikasi bentuk kanonik -> HARUS diterima.
      Check(nq::Ed25519Verify(
                pk, reinterpret_cast<const std::uint8_t*>(ca.canonical.data()),
                ca.canonical.size(), sig),
            "tanda tangan atas bentuk kanonik DITERIMA");

      // Verifikasi bentuk kanonik dari payload yang DIEDIT KOSMETIK -> tetap
      // diterima, karena kanoniknya identik. Inilah inti RFC 8785.
      Check(nq::Ed25519Verify(
                pk, reinterpret_cast<const std::uint8_t*>(cb.canonical.data()),
                cb.canonical.size(), sig),
            "penyuntingan kosmetik (urutan kunci + spasi) TIDAK membatalkan tanda tangan");

      // Isi diubah: send_gpc true -> false. Kanonik berbeda -> HARUS ditolak.
      const std::string payload_changed =
          "{\"schema\":\"nevus-ruleset/2\",\"signed\":true,"
          "\"policy\":{\"send_gpc\":false,\"https_only_top_level\":true}}";
      const nq::jcs::Result cc = nq::jcs::Canonicalize(payload_changed);
      Check(cc.ok && cc.canonical != ca.canonical,
            "perubahan isi -> kanonik BERBEDA");
      Check(!nq::Ed25519Verify(
                pk, reinterpret_cast<const std::uint8_t*>(cc.canonical.data()),
                cc.canonical.size(), sig),
            "perubahan isi -> tanda tangan DITOLAK");

      // Tanpa kanonikalisasi (teks apa adanya), penyuntingan kosmetik
      // membatalkan tanda tangan. Ini menunjukkan masalah yang ditutup.
      Check(!nq::Ed25519Verify(
                pk, reinterpret_cast<const std::uint8_t*>(payload_b.data()),
                payload_b.size(), sig),
            "tanpa kanonikalisasi: teks apa adanya yang berbeda -> DITOLAK (masalah lama)");
    }
  }
#else
  {
    std::printf("  -- (b) integrasi tanda tangan: DILEWATI (tanpa libsodium) --\n");
    Check(!nq::Ed25519SelfTest(),
          "tanpa libsodium: verifikasi gagal-tertutup (kanonikalisasi tetap diuji di atas)");
  }
#endif

  std::printf("  ---\n  %d pemeriksaan, %d gagal\n", g_total, g_fail);
  return g_fail == 0 ? 0 : 1;
}
