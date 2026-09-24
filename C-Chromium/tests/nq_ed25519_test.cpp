// Uji modul Ed25519: penguraian hex, pemeriksaan bentuk, dan — yang paling
// penting — bahwa kripto yang belum ada TIDAK diam-diam menerima apa pun.
#include <cstdio>
#include <string>

#include "nq_ed25519.h"

namespace {

int g_fail = 0;
int g_total = 0;

void Check(bool condition, const char* what) {
  ++g_total;
  if (condition) {
    std::printf("  OK    %s\n", what);
  } else {
    std::printf("  GAGAL %s\n", what);
    ++g_fail;
  }
}

// RFC 8032 §7.1 TEST 1.
const char* kTest1Key =
    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
const char* kTest1Sig =
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

std::uint8_t g_key[32];
std::uint8_t g_sig[64];
bool g_hex_ok_key = false;
bool g_hex_ok_sig = false;

}  // namespace

int main() {
  std::printf("== Uji Ed25519 (kontrak + jalur gagal) ==\n");

  // --- Penguraian hex ------------------------------------------------------
  g_hex_ok_key = nq::HexToBytes(kTest1Key, g_key, 32);
  g_hex_ok_sig = nq::HexToBytes(kTest1Sig, g_sig, 64);
  Check(g_hex_ok_key, "hex kunci publik 32 byte diterima");
  Check(g_hex_ok_sig, "hex tanda tangan 64 byte diterima");
  Check(!nq::HexToBytes(kTest1Key, g_key, 31), "hex panjang salah ditolak");
  Check(!nq::HexToBytes(std::string(64, 'z'), g_key, 32),
        "hex karakter non-hex ditolak");
  Check(!nq::HexToBytes("", g_key, 32), "hex kosong ditolak");
  {
    // Huruf besar harus diterima, dan menghasilkan byte yang sama.
    std::string upper = kTest1Key;
    for (char& c : upper) {
      if (c >= 'a' && c <= 'f') c = static_cast<char>(c - 'a' + 'A');
    }
    std::uint8_t again[32];
    Check(nq::HexToBytes(upper, again, 32) &&
              std::string(reinterpret_cast<char*>(again), 32) ==
                  std::string(reinterpret_cast<char*>(g_key), 32),
          "hex huruf besar sama nilainya dengan huruf kecil");
  }

  // --- Pemeriksaan bentuk --------------------------------------------------
  Check(g_hex_ok_key && nq::Ed25519PublicKeyWellFormed(g_key),
        "kunci publik TEST 1 lolos pemeriksaan bentuk (y < p)");
  Check(g_hex_ok_sig && nq::Ed25519SignatureWellFormed(g_sig),
        "tanda tangan TEST 1 lolos pemeriksaan bentuk (S < L)");
  {
    // y = p persis: harus ditolak, karena y >= p tidak mungkin titik kurva.
    std::uint8_t y_eq_p[32];
    for (int i = 0; i < 32; ++i) {
      y_eq_p[i] = static_cast<std::uint8_t>(0xff);
    }
    y_eq_p[0] = 0xed;   // p = 2^255-19 -> byte paling rendah 0xed
    y_eq_p[31] = 0x7f;  // bit tanda dibuang
    Check(!nq::Ed25519PublicKeyWellFormed(y_eq_p),
          "kunci publik dengan y == p ditolak");
  }
  {
    // S = L persis: harus ditolak (malleability, RFC 8032 §8.4).
    //
    // CATATAN: versi pertama uji ini hanya mengisi dua limb pertama dari L,
    // sehingga nilainya jauh LEBIH KECIL dari L dan uji itu menuntut penolakan
    // atas nilai yang sebenarnya sah. Uji tersebut salah, bukan kodenya.
    // Nilai di bawah adalah L lengkap, little-endian 32 byte.
    const std::uint8_t kLBytes[32] = {
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,  // limb0, limb1
        0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,  // limb2, limb3
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // limb4, limb5
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10};  // limb6, limb7

    std::uint8_t s_eq_l[64];
    for (int i = 0; i < 64; ++i) s_eq_l[i] = 0;
    for (int i = 0; i < 32; ++i) s_eq_l[32 + i] = kLBytes[i];
    Check(!nq::Ed25519SignatureWellFormed(s_eq_l),
          "tanda tangan dengan S == L ditolak");

    // S = L - 1: masih sah, harus DITERIMA.
    std::uint8_t s_l_minus_1[64];
    for (int i = 0; i < 64; ++i) s_l_minus_1[i] = 0;
    for (int i = 0; i < 32; ++i) s_l_minus_1[32 + i] = kLBytes[i];
    s_l_minus_1[32] = 0xec;  // limb0 dari L-1
    Check(nq::Ed25519SignatureWellFormed(s_l_minus_1),
          "tanda tangan dengan S == L-1 diterima");

    // S = L + 1: harus DITOLAK (representasi setara, dapat diubah tanpa kunci).
    std::uint8_t s_l_plus_1[64];
    for (int i = 0; i < 64; ++i) s_l_plus_1[i] = 0;
    for (int i = 0; i < 32; ++i) s_l_plus_1[32 + i] = kLBytes[i];
    s_l_plus_1[32] = 0xee;  // limb0 dari L+1
    Check(!nq::Ed25519SignatureWellFormed(s_l_plus_1),
          "tanda tangan dengan S == L+1 ditolak");
  }
  {
    // S dengan bit tinggi pada byte terakhir (S >= 2^255) harus ditolak.
    std::uint8_t s_big[64];
    for (int i = 0; i < 64; ++i) s_big[i] = 0;
    s_big[63] = 0xff;
    Check(!nq::Ed25519SignatureWellFormed(s_big),
          "tanda tangan dengan S sangat besar ditolak");
  }

  // --- Kripto belum ada: WAJIB menolak, bukan menerima ------------------
  {
    const char* payload = "";
    Check(!nq::Ed25519Verify(g_key,
                             reinterpret_cast<const std::uint8_t*>(payload), 0,
                             g_sig),
          "Ed25519Verify menolak saat aritmetika kurva belum ada (gagal-tertutup)");
    Check(!nq::Ed25519SelfTest(),
          "Ed25519SelfTest() == false (kripto belum sehat) -> ruleset bertanda "
          "tangan ditolak");
  }

  // --- Ed25519RulesetVerifier --------------------------------------------
  {
    const nq::Ed25519RulesetVerifier verifier;
    Check(verifier.algorithm() == "ed25519",
          "algorithm() melaporkan \"ed25519\"");
    Check(!verifier.Verify("{}", kTest1Sig, kTest1Key),
          "Verify menolak (kripto belum ada) meski hex sah");
    Check(!verifier.Verify("{}", "bukan-hex", kTest1Key),
          "Verify menolak tanda tangan non-hex");
    Check(!verifier.Verify("{}", kTest1Sig, "bukan-hex"),
          "Verify menolak kunci non-hex");
    Check(!verifier.Verify("{}", std::string(126, 'a'), kTest1Key),
          "Verify menolak tanda tangan panjang 63 byte");
    Check(!verifier.Verify("{}", kTest1Sig, std::string(62, 'a')),
          "Verify menolak kunci panjang 31 byte");
    Check(!verifier.Verify("{}", "", ""),
          "Verify menolak string kosong");
  }

  std::printf("  ---\n  %d pemeriksaan, %d gagal\n", g_total, g_fail);
  if (g_fail != 0) {
    std::printf("\nCATATAN: kegagalan di atas WAJAR bila aritmetika kurva belum\n"
                "diimplementasikan, TETAPI hanya untuk pemeriksaan yang memang\n"
                "bergantung padanya. Lihat docs/ED25519-VERIFIER-CONTRACT.md.\n");
  }
  return g_fail == 0 ? 0 : 1;
}
