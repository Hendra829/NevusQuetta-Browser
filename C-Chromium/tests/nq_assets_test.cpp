// Uji integrasi: berkas ruleset SUNGGUHAN (assets/nevus_ruleset.json) harus
// memuat, lolos verifikasi checksum, memasang kebijakan, DAN jalur gagal harus
// menolak dengan status yang benar.
//
// Uji ini yang membuktikan klaim "kebijakan digerakkan ruleset, bukan konstanta
// di kode". Tanpanya, klaim itu hanya analisis statis.
//
// Penggunaan:
//   ./nq_assets_test [direktori-assets]
// Default: "assets" relatif ke direktori kerja (jalankan dari C-Chromium/).
//
// Hermetik: uji ini membuat ULANG direktori kerja sementaranya sendiri setiap
// run, jadi hasilnya tidak bergantung pada sisa run sebelumnya dan angkanya
// stabil. Dijalankan lewat ctest, ia selalu menerima direktori assets
// eksplisit sehingga tidak bergantung pada direktori kerja pemanggil.
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>

#include "nq_bootstrap.h"
#include "nq_privacy_policy.h"
#include "nq_ruleset.h"

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

bool WriteFile(const std::string& path, const std::string& text) {
  std::ofstream out(path, std::ios::binary | std::ios::trunc);
  if (!out) return false;
  out << text;
  return static_cast<bool>(out);
}

bool ReadFile(const std::string& path, std::string* out) {
  std::ifstream in(path, std::ios::binary);
  if (!in) return false;
  *out = std::string(std::istreambuf_iterator<char>(in),
                     std::istreambuf_iterator<char>());
  return true;
}

// Membuat ulang direktori sementara dari nol.
//
// Kenapa ini wajib: versi sebelumnya HANYA menulis ke "nq-assets-tmp" tanpa
// pernah membuat direktori itu. Bila direktori belum ada -- yaitu setiap clone
// bersih di CI -- seluruh penulisan gagal, WriteFile() mengembalikan false TANPA
// dilaporkan, dan uji jalur 4b-4g melaporkan status yang salah (kMissing alih-
// alih status yang diharapkan). Lebih buruk, uji itu lalu "lulus" hanya karena
// ada sisa direktori dari run sebelumnya: tidak hermetik dan tidak bisa
// dipercaya. Karena itu ruang kerja dibuat ulang di sini, setiap run.
bool ResetTmpDir(const std::string& dir) {
  std::error_code ec;
  std::filesystem::remove_all(dir, ec);
  std::filesystem::create_directories(dir, ec);
  return std::filesystem::is_directory(dir, ec);
}

}  // namespace

int main(int argc, char* argv[]) {
  const std::string assets_dir = (argc > 1) ? argv[1] : "assets";
  const std::string ruleset = assets_dir + "/nevus_ruleset.json";
  const std::string sidecar = ruleset + ".sha256";
  const std::string tmp_dir = "nq-assets-tmp";

  std::printf("== Uji integrasi assets (jalur SUNGGUHAN) ==\n");
  std::printf("  berkas: %s\n", ruleset.c_str());

  // Harness menyiapkan ruang kerjanya sendiri; tidak boleh bergantung sisa run
  // sebelumnya maupun pada direktori yang kebetulan sudah ada.
  if (!ResetTmpDir(tmp_dir)) {
    std::printf("  GAGAL tidak dapat membuat direktori sementara '%s'\n",
                tmp_dir.c_str());
    return 1;
  }

  std::string text;
  if (!ReadFile(ruleset, &text)) {
    std::printf("  GAGAL tidak dapat membaca %s (jalankan dari C-Chromium/)\n",
                ruleset.c_str());
    return 1;
  }

  // --- 1. Build rilis: ruleset tanpa tanda tangan HARUS ditolak -------------
  {
    nq::RulesetLoadOptions options;  // allow_unsigned = false (default rilis)
    const nq::RulesetLoadResult result =
        nq::LoadRulesetFromFile(ruleset, options);
    Check(result.status == nq::RulesetStatus::kUnsignedRejected,
          std::string("build rilis menolak ruleset tanpa tanda tangan (status=") +
              nq::RulesetStatusName(result.status) + ")");
  }

  // --- 2. Build lab: harus dimuat, dengan checksum diverifikasi -------------
  nq::Ruleset loaded;
  {
    nq::RulesetLoadOptions options;
    options.allow_unsigned = true;  // build lab
    const nq::RulesetLoadResult result =
        nq::LoadRulesetFromFile(ruleset, options);
    Check(result.ok(),
          std::string("build lab memuat ruleset (status=") +
              nq::RulesetStatusName(result.status) + ", detail=" +
              result.detail + ")");
    if (!result.ok()) {
      std::printf("  ---\n  %d pemeriksaan, %d gagal (berhenti dini)\n",
                  g_total, g_fail);
      return 1;
    }
    loaded = result.ruleset;
    Check(loaded.schema == "nevus-ruleset/2", "skema terbaca: nevus-ruleset/2");
    Check(loaded.version == "2026.09.2", "versi terbaca: 2026.09.2");
    Check(!loaded.declared_signed, "declared_signed == false");
    Check(loaded.policy.blocked_hosts.size() == 56,
          "jumlah aturan host == 56 (dapat " +
              std::to_string(loaded.policy.blocked_hosts.size()) + ")");
    Check(loaded.sha256_hex.size() == 64, "checksum SHA-256 tercatat (64 hex)");
    Check(loaded.policy.https_only_top_level &&
              loaded.policy.https_only_subresource && loaded.policy.send_gpc &&
              loaded.policy.deny_permissions_by_default,
          "seluruh kebijakan dari berkas bernilai true");
  }

  // --- 3. Kebijakan benar-benar menggerakkan PrivacyPolicy ------------------
  {
    nq::PrivacyPolicy::ResetPolicyForTesting();
    // Sebelum dipasang: kebijakan paling ketat, tidak ada host terblokir.
    Check(nq::PrivacyPolicy::BlockedHostCount() == 0,
          "sebelum dipasang: 0 host terblokir (failsafe, bukan daftar longgar)");
    Check(!nq::PrivacyPolicy::HasInstalledPolicy(),
          "sebelum dipasang: HasInstalledPolicy() == false");

    Check(nq::PrivacyPolicy::InstallPolicy(loaded.policy),
          "InstallPolicy() berhasil pada pemasangan pertama");
    Check(nq::PrivacyPolicy::HasInstalledPolicy(),
          "sesudah dipasang: HasInstalledPolicy() == true");
    Check(nq::PrivacyPolicy::BlockedHostCount() == 56,
          "sesudah dipasang: 56 host terblokir (berasal dari berkas)");

    // Pemasangan kedua HARUS ditolak: menutup penurunan kebijakan runtime.
    nq::RulesetPolicy loose;
    loose.https_only_top_level = false;
    loose.https_only_subresource = false;
    loose.send_gpc = false;
    loose.deny_permissions_by_default = false;
    Check(!nq::PrivacyPolicy::InstallPolicy(loose),
          "pemasangan kedua ditolak (tidak ada penurunan kebijakan runtime)");
    Check(nq::PrivacyPolicy::BlockedHostCount() == 56,
          "kebijakan tetap 56 host setelah upaya penurunan ditolak");

    // Aturan suffix: host itu sendiri dan subdomainnya terblokir.
    Check(nq::PrivacyPolicy::IsBlockedHost("https://doubleclick.net/"),
          "suffix: doubleclick.net terblokir");
    Check(nq::PrivacyPolicy::IsBlockedHost("https://sub.doubleclick.net/x.js"),
          "suffix: sub.doubleclick.net terblokir");
    // Label terpisah: "evil-doubleclick.net" BUKAN subdomain.
    Check(!nq::PrivacyPolicy::IsBlockedHost("https://evil-doubleclick.net/"),
          "suffix: evil-doubleclick.net TIDAK terblokir (batas label)");
    // Aturan exact.
    Check(nq::PrivacyPolicy::IsBlockedHost("https://bidswitch.net/"),
          "exact: bidswitch.net terblokir");
    Check(!nq::PrivacyPolicy::IsBlockedHost("https://sub.bidswitch.net/"),
          "exact: sub.bidswitch.net TIDAK terblokir");

    // HTTPS-only dan Sec-GPC dari kebijakan.
    Check(!nq::PrivacyPolicy::IsAllowedTopLevelScheme("http://contoh.test/"),
          "https_only_top_level: http:// ditolak");
    Check(nq::PrivacyPolicy::IsAllowedTopLevelScheme("https://contoh.test/"),
          "https_only_top_level: https:// diizinkan");
    Check(!nq::PrivacyPolicy::IsAllowedSubresourceScheme("http://c.test/a.png"),
          "https_only_subresource: http:// ditolak");
    Check(nq::PrivacyPolicy::IsAllowedSubresourceScheme("data:image/png;base64,"),
          "sub-sumber daya inert (data:) tetap diizinkan");
    Check(nq::PrivacyPolicy::ShouldSendGlobalPrivacyControl(),
          "send_gpc: header GPC aktif");
    Check(nq::PrivacyPolicy::GlobalPrivacyControlHeader() == "Sec-GPC: 1",
          "nilai header GPC == \"Sec-GPC: 1\"");
    Check(nq::PrivacyPolicy::IsPermissionDeniedByDefault(0),
          "izin tak dikenal pun DITOLAK (gagal-tertutup)");
  }

  // --- 4. Jalur gagal pada berkas nyata -------------------------------------
  {
    std::printf("  -- jalur gagal --\n");
    // 4a. Berkas tidak ada.
    {
      nq::RulesetLoadOptions options;
      options.allow_unsigned = true;
      const nq::RulesetLoadResult r = nq::LoadRulesetFromFile(
          tmp_dir + "/tidak-ada.json", options);
      Check(r.status == nq::RulesetStatus::kMissing,
            "berkas hilang -> kMissing");
    }
    // 4b. Sidecar hilang.
    {
      // Pola pemeriksaan konsisten: kegagalan penulisan pun dihitung lewat
      // Check() sehingga g_total dan g_fail selalu sepasang. Versi lama
      // menaikkan g_fail sendirian di sini -- itulah sebabnya jumlah akhirnya
      // berbeda antar run (42 vs 43).
      Check(WriteFile(tmp_dir + "/nosidecar.json", text),
            "menulis berkas uji sementara (sidecar)");
      {
        nq::RulesetLoadOptions options;  // rilis: checksum wajib
        const nq::RulesetLoadResult r =
            nq::LoadRulesetFromFile(tmp_dir + "/nosidecar.json", options);
        Check(r.status == nq::RulesetStatus::kChecksumMissing,
              "sidecar checksum hilang -> kChecksumMissing");
      }
    }
    // 4c. Checksum tidak cocok (berkas diubah setelah checksum dibuat).
    {
      std::string sidecar_text;
      if (ReadFile(sidecar, &sidecar_text)) {
        // Tulis berkas yang isinya diubah tetapi sidecar-nya tetap asli.
        std::string tampered = text;
        const std::string from = "\"version\": \"2026.09.2\"";
        const std::string to = "\"version\": \"2026.09.3\"";
        const std::string::size_type pos = tampered.find(from);
        if (pos == std::string::npos) {
          Check(false, "penanda versi tidak ditemukan di berkas ruleset");
        } else {
          tampered.replace(pos, from.size(), to);
          WriteFile(tmp_dir + "/tampered.json", tampered);
          // Sidecar memakai nama berkas asli; arahkan lewat checksum_path.
          nq::RulesetLoadOptions options;
          options.checksum_path = sidecar;
          const nq::RulesetLoadResult r =
              nq::LoadRulesetFromFile(tmp_dir + "/tampered.json", options);
          Check(r.status == nq::RulesetStatus::kChecksumMismatch,
                "berkas diubah -> kChecksumMismatch (checksum TIDAK bisa "
                "dilonggarkan)");
        }
      } else {
        Check(false, "tidak dapat membaca sidecar untuk uji checksum");
      }
    }
    // 4d. JSON rusak (checksum sengaja dilewati lewat build lab).
    {
      std::string broken = text;
      broken.erase(0, 5);  // buang "{\n  " -> JSON tidak sah
      WriteFile(tmp_dir + "/broken.json", broken);
      nq::RulesetLoadOptions options;
      options.allow_unsigned = true;
      const nq::RulesetLoadResult r =
          nq::LoadRulesetFromFile(tmp_dir + "/broken.json", options);
      Check(r.status == nq::RulesetStatus::kMalformedJson,
            "JSON rusak -> kMalformedJson");
    }
    // 4e. Skema asing.
    {
      std::string other = text;
      const std::string from = "\"nevus-ruleset/2\"";
      const std::string to = "\"nevus-ruleset/99\"";
      const std::string::size_type pos = other.find(from);
      if (pos != std::string::npos) {
        other.replace(pos, from.size(), to);
      }
      WriteFile(tmp_dir + "/otherschema.json", other);
      nq::RulesetLoadOptions options;
      options.allow_unsigned = true;
      const nq::RulesetLoadResult r =
          nq::LoadRulesetFromFile(tmp_dir + "/otherschema.json", options);
      Check(r.status == nq::RulesetStatus::kInvalidSchema,
            "skema asing -> kInvalidSchema");
    }
    // 4f. Permintaan pelonggaran kebijakan.
    {
      std::string relaxed = text;
      const std::string from = "\"send_gpc\": true";
      const std::string to = "\"send_gpc\": false";
      const std::string::size_type pos = relaxed.find(from);
      if (pos != std::string::npos) {
        relaxed.replace(pos, from.size(), to);
      }
      WriteFile(tmp_dir + "/relaxed.json", relaxed);
      nq::RulesetLoadOptions options;
      options.allow_unsigned = true;
      const nq::RulesetLoadResult r =
          nq::LoadRulesetFromFile(tmp_dir + "/relaxed.json", options);
      Check(r.status == nq::RulesetStatus::kPolicyRelaxationRejected,
            "policy.send_gpc=false -> kPolicyRelaxationRejected");
    }
    // 4g. Ruleset bertanda tangan.
    //
    // DUA jalur berbeda, dan perbedaannya penting:
    //   - LoadRulesetFromFile LANGSUNG tanpa verifier -> kSignatureAlgorithmUnsupported
    //     (jujur: pemanggil tidak menyediakan verifier)
    //   - Lewat PlanBootstrap, yang memasang verifier Ed25519 bawaan ->
    //     kSignatureInvalid (verifier ada, tetapi kriptonya belum sehat)
    // Uji ini mengunci keduanya supaya perbedaan itu tidak hilang diam-diam.
    {
      std::string signed_text = text;
      const std::string from = "\"signed\": false";
      const std::string to =
          "\"signed\": true, \"signature\": {\"algorithm\": \"ed25519\", "
          "\"value\": \"" +
          std::string(128, 'a') + "\"}";
      const std::string::size_type pos = signed_text.find(from);
      if (pos != std::string::npos) {
        signed_text.replace(pos, from.size(), to);
      }
      WriteFile(tmp_dir + "/signed.json", signed_text);

      nq::RulesetLoadOptions direct;
      direct.allow_unsigned = true;
      direct.public_key = std::string(64, 'b');
      const nq::RulesetLoadResult r1 =
          nq::LoadRulesetFromFile(tmp_dir + "/signed.json", direct);
      Check(r1.status == nq::RulesetStatus::kSignatureAlgorithmUnsupported,
            std::string("muat langsung tanpa verifier -> "
                        "kSignatureAlgorithmUnsupported (status=") +
                nq::RulesetStatusName(r1.status) + ")");

      nq::PrivacyPolicy::ResetPolicyForTesting();
      nq::BootstrapOptions via_bootstrap;
      via_bootstrap.explicit_path = tmp_dir + "/signed.json";
      via_bootstrap.allow_unsigned = true;
      via_bootstrap.public_key = std::string(64, 'b');
      const nq::BootstrapDecision d = nq::PlanBootstrap(via_bootstrap);
      Check(d.status == nq::RulesetStatus::kSignatureInvalid,
            std::string("lewat PlanBootstrap (verifier bawaan terpasang) -> "
                        "kSignatureInvalid (status=") +
                nq::RulesetStatusName(d.status) + ")");
      Check(!d.should_run,
            "ruleset bertanda tangan yang gagal diverifikasi -> aplikasi "
            "MENOLAK berjalan (gagal-tertutup)");
    }
  }

  // --- 5. Bootstrap end-to-end --------------------------------------------
  {
    std::printf("  -- bootstrap --\n");
    nq::PrivacyPolicy::ResetPolicyForTesting();
    nq::BootstrapOptions options;
    options.explicit_path = ruleset;
    options.allow_unsigned = true;
    options.allow_failsafe = false;
    const nq::BootstrapDecision ok = nq::PlanBootstrap(options);
    Check(ok.should_run && ok.status == nq::RulesetStatus::kLoaded,
          "bootstrap: ruleset sah -> aplikasi berjalan");
    Check(!ok.using_failsafe_policy,
          "bootstrap: kebijakan dari ruleset (bukan failsafe)");
    Check(nq::PrivacyPolicy::BlockedHostCount() == 56,
          "bootstrap: memasang 56 host terblokir");

    // Berkas hilang + tanpa failsafe -> MENOLAK berjalan.
    nq::PrivacyPolicy::ResetPolicyForTesting();
    nq::BootstrapOptions missing;
    missing.explicit_path = tmp_dir + "/tidak-ada.json";
    const nq::BootstrapDecision rejected = nq::PlanBootstrap(missing);
    Check(!rejected.should_run,
          "bootstrap: ruleset hilang TANPA failsafe -> MENOLAK berjalan");
    Check(!nq::PrivacyPolicy::HasInstalledPolicy(),
          "bootstrap: kebijakan TIDAK dipasang saat menolak berjalan");

    // Berkas hilang + failsafe eksplisit -> berjalan dengan kebijakan terketat.
    nq::PrivacyPolicy::ResetPolicyForTesting();
    nq::BootstrapOptions failsafe;
    failsafe.explicit_path = tmp_dir + "/tidak-ada.json";
    failsafe.allow_failsafe = true;
    const nq::BootstrapDecision ran = nq::PlanBootstrap(failsafe);
    Check(ran.should_run && ran.using_failsafe_policy,
          "bootstrap: failsafe eksplisit -> berjalan dengan kebijakan terketat");
    Check(nq::PrivacyPolicy::BlockedHostCount() == 0 &&
              !nq::PrivacyPolicy::IsAllowedTopLevelScheme("http://x.test/"),
          "bootstrap: kebijakan failsafe = tanpa host, http:// ditolak");
  }

  std::printf("  ---\n  %d pemeriksaan, %d gagal\n", g_total, g_fail);
  return g_fail == 0 ? 0 : 1;
}
