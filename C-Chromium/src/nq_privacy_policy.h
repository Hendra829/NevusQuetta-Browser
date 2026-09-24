#pragma once

#include <cstddef>
#include <string>

#include "nq_ruleset.h"

// Kebijakan privasi/permukaan serangan yang berlaku untuk SETIAP permintaan
// jaringan dan SETIAP prompt izin.
//
// SEBELUMNYA: seluruh nilai (daftar host, HTTPS-only, Sec-GPC) ditanam sebagai
// konstanta di berkas .cpp, sehingga berkas ruleset tidak berpengaruh apa pun.
// SEKARANG: kebijakan diambil dari RulesetPolicy yang dimuat saat startup.
//
// Invarian yang dipertahankan:
//   1. Selama belum ada ruleset yang dipasang, kebijakan aktif adalah kebijakan
//      paling ketat (BuildFailsafePolicy), bukan daftar default yang longgar.
//   2. Pemasangan kebijakan hanya boleh dilakukan SEKALI. Upaya kedua ditolak,
//      sehingga komponen mana pun tidak dapat menurunkan kebijakan saat runtime.
//   3. Semua kasus "tidak yakin" mengarah ke TOLAK (gagal-tertutup).
namespace nq {

class PrivacyPolicy {
 public:
  // Memasang kebijakan aktif. Mengembalikan false bila sudah pernah dipasang.
  // Thread-safe. Panggil dari thread utama sebelum CefInitialize.
  static bool InstallPolicy(const RulesetPolicy& policy);

  // True bila kebijakan dari ruleset sudah terpasang.
  static bool HasInstalledPolicy();

  // Jumlah aturan host yang aktif (untuk log/pemeriksaan).
  static std::size_t BlockedHostCount();

  // Skema yang diizinkan untuk navigasi tingkat-atas.
  static bool IsAllowedTopLevelScheme(const std::string& url);

  // Skema yang diizinkan untuk sub-sumber daya (gambar, skrip, XHR).
  static bool IsAllowedSubresourceScheme(const std::string& url);

  // Host yang tidak boleh diakses (daftar dari ruleset).
  static bool IsBlockedHost(const std::string& url);

  // Izin yang DITOLAK secara default.
  static bool IsPermissionDeniedByDefault(int permission);

  // True bila header Global Privacy Control harus dikirim.
  static bool ShouldSendGlobalPrivacyControl();

  // Nilai header Global Privacy Control (lengkap, "Nama: nilai").
  static std::string GlobalPrivacyControlHeader();

  // Untuk pengujian: salinan kebijakan aktif saat ini.
  static RulesetPolicy ActivePolicyForTesting();

  // Untuk pengujian: kembalikan ke keadaan belum dipasang.
  static void ResetPolicyForTesting();
};

}  // namespace nq
