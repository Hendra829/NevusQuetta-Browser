#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

namespace nq {

// SHA-256 (FIPS 180-4), implementasi mandiri tanpa dependensi kripto eksternal.
//
// PENTING — batas kemampuan (jangan salah pakai):
//   SHA-256 memberi INTEGRITAS (mendeteksi korupsi atau perubahan tak sengaja).
//   SHA-256 BUKAN tanda tangan digital: siapa pun yang bisa mengubah berkas juga
//   bisa menghitung ulang checksum-nya. Autentisitas ditangani terpisah oleh
//   nq_ruleset_verify.h, yang gagal-tertutup bila tidak ada verifier terpasang.
class Sha256 {
 public:
  static constexpr std::size_t kDigestBytes = 32;
  using Digest = std::array<std::uint8_t, kDigestBytes>;

  Sha256();

  void Update(const void* data, std::size_t len);
  void Update(const std::string& text);

  // Menyelesaikan hash. Hanya boleh dipanggil sekali; setelah itu objek tidak
  // boleh dipakai lagi (menghindari hash "diam-diam diperpanjang").
  Digest Final();

  static Digest Hash(const std::string& text);

  // Representasi hex huruf kecil, 64 karakter.
  static std::string ToHex(const Digest& digest);

  // Mengurai hex 64 karakter (huruf besar/kecil). Menolak panjang salah atau
  // karakter non-hex. Mengembalikan false tanpa mengubah *out saat gagal.
  static bool FromHex(const std::string& hex, Digest* out);

 private:
  void Transform(const std::uint8_t* block);

  std::uint32_t state_[8];
  std::uint8_t buffer_[64];
  std::size_t buffer_len_;
  std::uint64_t total_bytes_;
  bool finalized_;
};

}  // namespace nq
