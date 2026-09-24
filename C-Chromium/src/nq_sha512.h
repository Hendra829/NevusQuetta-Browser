#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

namespace nq {

// SHA-512 (FIPS 180-4), implementasi mandiri tanpa dependensi kripto eksternal.
//
// Dibutuhkan oleh Ed25519 (RFC 8032), yang memakai SHA-512 dua kali: sekali
// untuk menurunkan scalar dari seed rahasia, sekali lagi untuk menghitung
// scalar r pada saat verifikasi.
//
// Konstanta K[t] TIDAK diketik manual. Berkas nq_sha512_constants.h dihasilkan
// oleh tools/gen_sha512_constants.py memakai aritmetika bilangan besar, lalu
// kebenaran seluruh implementasi ini diperiksa lewat uji vektor terhadap
// sha512sum dan terhadap vektor uji RFC 8032 bagian 7.1.
class Sha512 {
 public:
  static constexpr std::size_t kDigestBytes = 64;
  using Digest = std::array<std::uint8_t, kDigestBytes>;

  Sha512();

  void Update(const void* data, std::size_t len);
  void Update(const std::string& text);

  // Menyelesaikan hash. IDEMPOTEN: pemanggilan kedua dan seterusnya
  // mengembalikan digest yang SAMA, bukan digest nol.
  //
  // Versi pertama modul ini mengembalikan digest nol pada pemanggilan kedua.
  // Itu cacat keamanan: pemanggil yang tanpa sengaja memanggil Final() dua kali
  // akan membandingkan checksum terhadap nol, dan untuk berkas kosong atau
  // harapan yang salah, perbandingan itu bisa lolos. Uji
  // "Final() idempoten" menahan regresi ini.
  //
  // Setelah Final(), Update() diabaikan total (tidak boleh memperpanjang hash).
  Digest Final();

  static Digest Hash(const void* data, std::size_t len);
  static Digest Hash(const std::string& text);

  // Representasi hex huruf kecil, 128 karakter.
  static std::string ToHex(const Digest& digest);

  // Mengurai hex 128 karakter (huruf besar/kecil). Menolak panjang salah atau
  // karakter non-hex. Tidak mengubah *out saat gagal.
  static bool FromHex(const std::string& hex, Digest* out);

 private:
  void Transform(const std::uint8_t* block);

  std::uint64_t state_[8];
  std::uint8_t buffer_[128];
  std::size_t buffer_len_;
  // Panjang pesan dalam bit (128 bit: high, low). Pembilang besar tidak
  // meluap; berkas ruleset dibatasi 4 MiB sehingga high selalu nol, tetapi
  // dukungan 128 bit dipertahankan agar tidak ada pemotongan diam-diam.
  std::uint64_t total_bits_high_;
  std::uint64_t total_bits_low_;
  bool finalized_;
  Digest cached_;  // hasil Final() pertama; dipakai ulang agar idempoten
};

}  // namespace nq
