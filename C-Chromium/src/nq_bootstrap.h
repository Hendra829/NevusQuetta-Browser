#pragma once

#include <string>

#include "nq_ruleset.h"

// Keputusan startup: apakah aplikasi boleh berjalan, dan dengan kebijakan apa.
//
// Modul ini sengaja TIDAK menyertakan header CEF agar dapat diuji penuh tanpa
// menjalankan Chromium. Kebijakan yang dipasang ke PrivacyPolicy ditentukan di
// sini, bukan di dalam kode CEF.
namespace nq {

struct BootstrapOptions {
  // Jalur eksplisit (dari argumen baris perintah). Menang atas env dan default.
  std::string explicit_path;

  // Nilai argv[0], dipakai untuk menghitung jalur default relatif executable.
  std::string argv0;

  // Nilai variabel lingkungan NQ_RULESET_PATH (bila ada).
  std::string env_path;

  // Sidecar checksum. Kosong -> "<path>.sha256".
  std::string checksum_path;

  // Build lab: izinkan ruleset tanpa tanda tangan (checksum tetap wajib).
  bool allow_unsigned = false;

  // Opt-in EKSPLISIT untuk tetap berjalan ketika ruleset gagal dimuat, dengan
  // kebijakan paling ketat (tidak ada akses jaringan sama sekali). Default
  // false: aplikasi MENOLAK berjalan.
  bool allow_failsafe = false;

  // Verifier tanda tangan. nullptr -> ruleset bertanda tangan selalu ditolak.
  const RulesetSignatureVerifier* verifier = nullptr;

  std::string public_key;
};

struct BootstrapDecision {
  // True bila aplikasi boleh melanjutkan startup.
  bool should_run = false;

  // True bila kebijakan yang dipasang bukan berasal dari ruleset.
  bool using_failsafe_policy = false;

  // Status pemuatan ruleset (kLoaded bila berhasil).
  RulesetStatus status = RulesetStatus::kMissing;

  Ruleset ruleset;

  // Jalur yang benar-benar dipakai.
  std::string resolved_path;

  // Pesan siap-tampil untuk log startup (bahasa Indonesia).
  std::string message;
};

// Menghitung jalur ruleset default: "<dir executable>/assets/nevus_ruleset.json".
std::string DefaultRulesetPathFor(const std::string& argv0);

// Mengurai argumen baris perintah yang dikenali dan menyusun BootstrapOptions.
// Mengembalikan false bila argumen tidak dikenal ditemukan (fail-closed: lebih
// baik menolak menjalankan daripada mengabaikan argumen yang mungkin penting).
bool ParseBootstrapArguments(int argc, char* argv[], BootstrapOptions* options,
                             std::string* error);

// Menentukan keputusan startup DAN memasang kebijakan ke PrivacyPolicy.
// Bila ruleset gagal dan allow_failsafe=false, should_run=false.
BootstrapDecision PlanBootstrap(const BootstrapOptions& options);

}  // namespace nq
