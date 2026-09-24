#include "nq_ed25519.h"

#include <cstring>

#include "nq_ed25519_constants.h"

namespace nq {
namespace {

// Membandingkan bilangan 32 byte little-endian `value` dengan batas yang
// diwakili 8 limb 32-bit little-endian. Mengembalikan true bila value < limit.
bool IsLessThanLE(const std::uint8_t* value, const std::uint32_t* limit) {
  for (int i = 7; i >= 0; --i) {
    const std::uint32_t v = static_cast<std::uint32_t>(value[i * 4 + 0]) |
                            (static_cast<std::uint32_t>(value[i * 4 + 1]) << 8) |
                            (static_cast<std::uint32_t>(value[i * 4 + 2]) << 16) |
                            (static_cast<std::uint32_t>(value[i * 4 + 3]) << 24);
    if (v < limit[i]) return true;
    if (v > limit[i]) return false;
  }
  return false;  // sama besar -> tidak kurang dari
}

}  // namespace

// ---------------------------------------------------------------------------
// Inti kripto — BELUM DIIMPLEMENTASIKAN
// ---------------------------------------------------------------------------
//
// Sengaja dibiarkan gagal-tertutup. Mengembalikan true di sini akan membuat
// setiap ruleset "bertanda tangan" diterima tanpa diperiksa, yaitu persis mode
// kegagalan yang paling berbahaya.
//
// Untuk mengimplementasikan, ikuti algoritma RFC 8032 §5.1.7 dan vektor uji
// §7.1 yang sudah dicatat di docs/ED25519-VERIFIER-CONTRACT.md. Bila tautan ke
// pustaka yang sudah diaudit (libsodium / OpenSSL EVP_DigestVerify) dapat
// diterima, itu pilihan yang lebih dapat dipertahankan daripada menulis
// aritmetika kurva sendiri.
bool Ed25519Verify(const std::uint8_t public_key[32],
                   const std::uint8_t* message, std::size_t message_len,
                   const std::uint8_t signature[64]) {
  // Pemeriksaan bentuk tetap dijalankan: bila bentuknya sudah tidak sah, tidak
  // ada gunanya melanjutkan, dan hasilnya pasti false.
  if (!Ed25519PublicKeyWellFormed(public_key) ||
      !Ed25519SignatureWellFormed(signature)) {
    return false;
  }
  (void)message;
  (void)message_len;
  return false;  // aritmetika kurva belum ada -> gagal-tertutup
}

bool Ed25519SelfTest() {
  // Vektor resmi RFC 8032 §7.1 TEST 1: pesan kosong.
  static const std::uint8_t kPublicKey[32] = {
      0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe,
      0xd3, 0xc9, 0x64, 0x07, 0x3a, 0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6,
      0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68, 0xf7, 0x07, 0x51, 0x1a};
  static const std::uint8_t kSignature[64] = {
      0xe5, 0x56, 0x43, 0x00, 0xc3, 0x60, 0xac, 0x72, 0x90, 0x86, 0xe2,
      0xcc, 0x80, 0x6e, 0x82, 0x8a, 0x84, 0x87, 0x7f, 0x1e, 0xb8, 0xe5,
      0xd9, 0x74, 0xd8, 0x73, 0xe0, 0x65, 0x22, 0x49, 0x01, 0x55, 0x5f,
      0xb8, 0x82, 0x15, 0x90, 0xa3, 0x3b, 0xac, 0xc6, 0x1e, 0x39, 0x70,
      0x1c, 0xf9, 0xb4, 0x6b, 0xd2, 0x5b, 0xf5, 0xf0, 0x59, 0x5b, 0xbe,
      0x24, 0x65, 0x51, 0x41, 0x43, 0x8e, 0x7a, 0x10, 0x0b};

  static const char kEmpty[] = "";
  // Uji ini lulus hanya bila verifikasi tanda tangan sungguhan berhasil.
  return Ed25519Verify(kPublicKey,
                       reinterpret_cast<const std::uint8_t*>(kEmpty), 0,
                       kSignature);
}

// ---------------------------------------------------------------------------
// Pemeriksaan bentuk (tidak butuh aritmetika kurva)
// ---------------------------------------------------------------------------

bool Ed25519PublicKeyWellFormed(const std::uint8_t public_key[32]) {
  if (public_key == nullptr) return false;
  // y = pk[0..31] dengan bit 255 dibuang.
  std::uint8_t y[32];
  std::memcpy(y, public_key, 32);
  y[31] &= 0x7f;
  // Titik sah selalu memenuhi y < p. Nilai y >= p tidak mungkin berasal dari
  // titik kurva; menerimanya membuka kompresi ganda yang ambigu.
  return IsLessThanLE(y, ed25519_detail::kP);
}

bool Ed25519SignatureWellFormed(const std::uint8_t signature[64]) {
  if (signature == nullptr) return false;
  // S adalah 32 byte terakhir, little-endian, dan WAJIB < L.
  // Tanpa pemeriksaan ini, (R, S) dan (R, S+L) sama-sama dianggap sah
  // (malleability, RFC 8032 §8.4).
  return IsLessThanLE(signature + 32, ed25519_detail::kL);
}

// ---------------------------------------------------------------------------
// Penguraian hex
// ---------------------------------------------------------------------------

bool HexToBytes(const std::string& hex, std::uint8_t* out, std::size_t out_len) {
  if (out == nullptr) return false;
  if (hex.size() != out_len * 2) return false;
  for (std::size_t i = 0; i < out_len; ++i) {
    unsigned value = 0;
    for (int nibble = 0; nibble < 2; ++nibble) {
      const char c = hex[i * 2 + nibble];
      value <<= 4;
      if (c >= '0' && c <= '9') {
        value |= static_cast<unsigned>(c - '0');
      } else if (c >= 'a' && c <= 'f') {
        value |= static_cast<unsigned>(c - 'a' + 10);
      } else if (c >= 'A' && c <= 'F') {
        value |= static_cast<unsigned>(c - 'A' + 10);
      } else {
        return false;
      }
    }
    out[i] = static_cast<std::uint8_t>(value);
  }
  return true;
}

// ---------------------------------------------------------------------------
// Verifier ruleset
// ---------------------------------------------------------------------------

std::string Ed25519RulesetVerifier::algorithm() const { return "ed25519"; }

bool Ed25519RulesetVerifier::Verify(const std::string& payload,
                                    const std::string& signature_value,
                                    const std::string& public_key) const {
  std::uint8_t key[32];
  std::uint8_t sig[64];
  if (!HexToBytes(public_key, key, 32)) return false;
  if (!HexToBytes(signature_value, sig, 64)) return false;
  return Ed25519Verify(key,
                       reinterpret_cast<const std::uint8_t*>(payload.data()),
                       payload.size(), sig);
}

}  // namespace nq
