#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace nq {

// ---------------------------------------------------------------------------
// Model kebijakan yang berasal dari berkas ruleset
// ---------------------------------------------------------------------------

enum class MatchMode {
  kSuffix,  // "doubleclick.net" memblokir doubleclick.net dan semua subdomain
  kExact,   // hanya host persis
};

struct BlockRule {
  std::string host;
  MatchMode mode = MatchMode::kSuffix;
};

// Kebijakan yang benar-benar dipakai runtime. Dibangun dari ruleset, bukan dari
// konstanta yang ditanam di kode.
struct RulesetPolicy {
  bool https_only_top_level = true;
  bool https_only_subresource = true;
  bool send_gpc = true;
  bool deny_permissions_by_default = true;
  std::vector<BlockRule> blocked_hosts;
};

struct Ruleset {
  std::string schema;
  std::string name;
  std::string version;
  std::string license;
  bool declared_signed = false;
  std::string sha256_hex;  // checksum berkas yang benar-benar terbaca
  RulesetPolicy policy;

  bool IsEmpty() const {
    return version.empty() && policy.blocked_hosts.empty();
  }
};

// ---------------------------------------------------------------------------
// Verifikator tanda tangan digital (pluggable)
// ---------------------------------------------------------------------------
//
// PENTING: build ini TIDAK menyertakan implementasi verifier apa pun. Karena
// itu setiap ruleset dengan "signed": true DITOLAK (gagal-tertutup), bukan
// diterima tanpa diperiksa. Untuk mengaktifkan rilis bertanda tangan, tautkan
// implementasi (mis. Ed25519 dari pustaka yang sudah diaudit) dan oper ke
// RulesetLoadOptions::verifier.
class RulesetSignatureVerifier {
 public:
  virtual ~RulesetSignatureVerifier() = default;

  // Nama algoritma yang diharapkan, mis. "ed25519". Harus cocok dengan
  // signature.algorithm di dalam berkas.
  virtual std::string algorithm() const = 0;

  // Memverifikasi payload terhadap signature (encoding sesuai algoritma).
  virtual bool Verify(const std::string& payload,
                      const std::string& signature_value,
                      const std::string& public_key) const = 0;
};

// Verifier bawaan: tidak ada. Selalu menolak.
class NoSignatureVerifier : public RulesetSignatureVerifier {
 public:
  std::string algorithm() const override { return std::string(); }
  bool Verify(const std::string&, const std::string&,
              const std::string&) const override {
    return false;
  }
};

// ---------------------------------------------------------------------------
// Status pemuatan
// ---------------------------------------------------------------------------

enum class RulesetStatus {
  kLoaded = 0,
  kMissing,               // berkas tidak ada
  kUnreadable,            // ada tetapi tidak dapat dibaca (izin/IO)
  kTooLarge,              // melampaui batas ukuran
  kMalformedJson,         // JSON tidak sah
  kInvalidSchema,         // struktur/kunci/nilai tidak sesuai skema
  kPolicyRelaxationRejected,  // ruleset meminta pelonggaran yang belum didukung UI
  kChecksumMissing,       // sidecar checksum tidak ada
  kChecksumMismatch,      // checksum tidak cocok (SELALU fatal)
  kSignatureMissing,      // signed:true tetapi berkas tanda tangan tidak ada
  kSignatureAlgorithmUnsupported,
  kSignatureInvalid,      // verifier menolak tanda tangan
  kUnsignedRejected,      // ruleset tanpa tanda tangan pada build rilis
};

// Nama status untuk log/laporan. Tidak pernah nullptr.
const char* RulesetStatusName(RulesetStatus status);

// ---------------------------------------------------------------------------
// Opsi & hasil
// ---------------------------------------------------------------------------

struct RulesetLoadOptions {
  // Build rilis: false -> ruleset tanpa tanda tangan DITOLAK.
  // Build lab/dev: true -> tanpa tanda tangan diizinkan, tetap wajib checksum.
  bool allow_unsigned = false;

  // Verifier tanda tangan. nullptr -> gagal-tertutup untuk ruleset bertanda tangan.
  const RulesetSignatureVerifier* verifier = nullptr;

  // Kunci publik untuk verifikasi (format sesuai algoritma).
  std::string public_key;

  // Batas ukuran berkas (anti-DoS). Berkas lebih besar ditolak sebelum di-parse.
  std::size_t max_bytes = 4u * 1024u * 1024u;

  // Skema yang didukung. Ruleset dengan skema lain DITOLAK.
  std::vector<std::string> supported_schemas = {"nevus-ruleset/2"};

  // Jalur sidecar checksum. Kosong -> "<path ruleset>.sha256".
  std::string checksum_path;

  // Jalur sidecar tanda tangan. Kosong -> "<path ruleset>.sig".
  std::string signature_path;
};

struct RulesetLoadResult {
  RulesetStatus status = RulesetStatus::kMissing;
  Ruleset ruleset;
  std::string detail;

  bool ok() const { return status == RulesetStatus::kLoaded; }
};

// Memuat dari string (untuk unit test): sidecar diperlakukan tidak ada,
// sehingga checksum harus diizinkan lewat allow_unsigned, kecuali
// expected_checksum diisi.
RulesetLoadResult LoadRulesetFromString(const std::string& text,
                                        const RulesetLoadOptions& options,
                                        const std::string& expected_checksum);

RulesetLoadResult LoadRulesetFromFile(const std::string& path,
                                      const RulesetLoadOptions& options);

// Kebijakan paling ketat: tidak ada koneksi jaringan sama sekali. Dipakai bila
// ruleset gagal dimuat dan aplikasi masih diizinkan berjalan (mode lab) untuk
// menampilkan halaman galat.
RulesetPolicy BuildFailsafePolicy();

// Ringkasan satu baris untuk log startup.
std::string DescribeRuleset(const Ruleset& ruleset);

}  // namespace nq
