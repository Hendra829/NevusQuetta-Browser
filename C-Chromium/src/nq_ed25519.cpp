#include "nq_ed25519.h"

#include <cstring>

#include "nq_ed25519_constants.h"

#if defined(NQ_HAVE_LIBSODIUM)
#include <sodium.h>
#endif

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

#if defined(NQ_HAVE_LIBSODIUM)
// Menyiapkan libsodium tepat sekali. sodium_init() mengembalikan >= 0 bila
// berhasil; nilai negatif berarti pustaka tidak dapat dipakai dan verifikasi
// HARUS gagal-tertutup.
bool SodiumReady() {
  static const bool ready = (sodium_init() >= 0);
  return ready;
}
#endif

}  // namespace

// ---------------------------------------------------------------------------
// Nama backend yang benar-benar dikompilasi
// ---------------------------------------------------------------------------
const char* Ed25519BackendName() {
#if defined(NQ_HAVE_LIBSODIUM)
  return "libsodium";
#else
  return "none";
#endif
}

// ---------------------------------------------------------------------------
// Inti kripto
// ---------------------------------------------------------------------------
//
// Dua mode kompilasi:
//
//   NQ_HAVE_LIBSODIUM  -> verifikasi memakai crypto_sign_verify_detached()
//                         dari libsodium (sudah diaudit). Ini yang dipakai
//                         bila pustaka tersedia saat konfigurasi.
//   tanpa libsodium    -> gagal-tertutup: SELALU false. Mengembalikan true di
//                         sini akan membuat setiap ruleset "bertanda tangan"
//                         diterima tanpa diperiksa, yaitu mode kegagalan yang
//                         paling berbahaya.
//
// Pemeriksaan bentuk (y < p, S < L) tetap dijalankan lebih dulu di kedua mode:
// ia menolak masukan yang jelas tidak sah sebelum menyentuh pustaka, dan
// menutup malleability (RFC 8032 §8.4) secara eksplisit.
bool Ed25519Verify(const std::uint8_t public_key[32],
                   const std::uint8_t* message, std::size_t message_len,
                   const std::uint8_t signature[64]) {
  if (!Ed25519PublicKeyWellFormed(public_key) ||
      !Ed25519SignatureWellFormed(signature)) {
    return false;
  }
#if defined(NQ_HAVE_LIBSODIUM)
  if (!SodiumReady()) return false;
  if (message == nullptr && message_len != 0) return false;
  return crypto_sign_verify_detached(
             signature, message, static_cast<unsigned long long>(message_len),
             public_key) == 0;
#else
  (void)message;
  (void)message_len;
  return false;  // tidak ada backend -> gagal-tertutup
#endif
}

bool Ed25519SelfTest() {
#if !defined(NQ_HAVE_LIBSODIUM)
  return false;
#else
  if (!SodiumReady()) return false;

  // Vektor resmi RFC 8032 §7.1 TEST 1-3. Uji ini memakai vektor resmi, bukan
  // vektor buatan sendiri, sehingga tidak dapat "dibuat lulus".
  struct Vector {
    const char* public_key;
    const char* message_hex;  // "" = pesan kosong
    const char* signature;
  };
  static const Vector kVectors[] = {
      // TEST 1 — pesan kosong
      {"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
       "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
       "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
      // TEST 2 — pesan 1 byte (0x72)
      {"3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
       "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
       "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
      // TEST 3 — pesan 2 byte (0xaf82)
      {"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
       "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac"
       "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
  };

  for (const Vector& v : kVectors) {
    std::uint8_t key[32];
    std::uint8_t sig[64];
    if (!HexToBytes(v.public_key, key, 32)) return false;
    if (!HexToBytes(v.signature, sig, 64)) return false;

    std::uint8_t message[8];
    std::size_t message_len = 0;
    const std::string msg_hex = v.message_hex;
    if (!msg_hex.empty()) {
      message_len = msg_hex.size() / 2;
      if (message_len > sizeof(message)) return false;
      if (!HexToBytes(msg_hex, message, message_len)) return false;
    }

    // Vektor positif WAJIB diterima.
    if (!Ed25519Verify(key, message, message_len, sig)) return false;

    // Vektor negatif WAJIB ditolak: satu bit pesan dibalik.
    if (message_len > 0) {
      std::uint8_t flipped[8];
      std::memcpy(flipped, message, message_len);
      flipped[0] ^= 0x01;
      if (Ed25519Verify(key, flipped, message_len, sig)) return false;
    }

    // Vektor negatif WAJIB ditolak: satu byte S diubah.
    std::uint8_t bad_s[64];
    std::memcpy(bad_s, sig, 64);
    bad_s[32] ^= 0x01;
    if (Ed25519Verify(key, message, message_len, bad_s)) return false;

    // Vektor negatif WAJIB ditolak: S >= L (malleability, RFC 8032 §8.4).
    std::uint8_t s_ge_l[64];
    std::memcpy(s_ge_l, sig, 64);
    for (int i = 32; i < 64; ++i) s_ge_l[i] = 0xff;
    if (Ed25519Verify(key, message, message_len, s_ge_l)) return false;
  }

  return true;
#endif
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
