#include "include/cef_command_line.h"
#include "include/wrapper/cef_helpers.h"

#include <cstdlib>
#include <string>

#include "nevus_app.h"
#include "nq_bootstrap.h"
#include "nq_ed25519.h"
#include "nq_privacy_policy.h"
#include "nq_version.h"

// Titik masuk. Struktur mengikuti contoh resmi CEF (cefsimple) tetapi dengan
// switch pengamanan yang ditetapkan proyek.

#if defined(OS_LINUX)
#include <X11/Xlib.h>
#endif

namespace {

void ApplySecuritySwitches(CefRefPtr<CefCommandLine> command_line) {
  // Sandbox TIDAK boleh dimatikan dari kode aplikasi. Flag --no-sandbox hanya
  // boleh muncul dari pengembang saat debugging, dan itu pun harus terlihat.
  command_line->AppendSwitch("enable-blink-features=PartitionedCookies");
  command_line->AppendSwitch("disable-features=Translate,MediaRouter");
  command_line->AppendSwitchWithValue("user-agent",
                                      std::string("NevusQuetta/") + nq::kVersion);
}

}  // namespace

int main(int argc, char* argv[]) {
#if defined(OS_LINUX)
  XInitThreads();
#endif

  CefMainArgs main_args(argc, argv);
  CefRefPtr<nq::NevusApp> app(new nq::NevusApp());

  // Jalankan proses anak lebih dulu (renderer/GPU/utility).
  const int exit_code = CefExecuteProcess(main_args, app.get(), nullptr);
  if (exit_code >= 0) {
    return exit_code;
  }

  // -------------------------------------------------------------------------
  // Kebijakan privasi DIMUAT sebelum Chromium diinisialisasi.
  //
  // Urutannya penting: setelah CefInitialize(), thread jaringan dan renderer
  // sudah berjalan dan dapat memulai permintaan sebelum ruleset terpasang.
  // Selain itu PrivacyPolicy::InstallPolicy() hanya menerima pemasangan
  // pertama, sehingga memasang lebih awal juga menutup celah penurunan
  // kebijakan di tahap berikutnya.
  //
  // Gagal memuat ruleset -> aplikasi MENOLAK berjalan (kode keluar berbeda dari
  // kegagalan CefInitialize, agar operator dapat membedakan penyebabnya).
  // -------------------------------------------------------------------------
  constexpr int kExitRulesetRejected = 2;
  constexpr int kExitCefInitFailed = 1;

  nq::BootstrapOptions bootstrap_options;
  std::string bootstrap_error;
  if (!nq::ParseBootstrapArguments(argc, argv, &bootstrap_options,
                                   &bootstrap_error)) {
    std::fprintf(stderr, "NevusQuetta: %s\n", bootstrap_error.c_str());
    return kExitRulesetRejected;
  }
  const char* env_ruleset = std::getenv("NQ_RULESET_PATH");
  if (env_ruleset != nullptr) {
    bootstrap_options.env_path = env_ruleset;
  }

  const nq::BootstrapDecision decision = nq::PlanBootstrap(bootstrap_options);
  if (!decision.should_run) {
    std::fprintf(stderr, "NevusQuetta: %s\n", decision.message.c_str());
    return kExitRulesetRejected;
  }

  // Pemeriksaan diri: bila kripto verifier tidak sehat, kebijakan yang berlaku
  // tidak boleh dianggap dapat memuat ruleset bertanda tangan.
  if (!nq::Ed25519SelfTest()) {
    std::fprintf(stderr,
                 "NevusQuetta: verifier Ed25519 belum sehat -> ruleset "
                 "bertanda tangan akan DITOLAK (gagal-tertutup).\n");
  }

  {
    // Jejak audit: kebijakan apa yang benar-benar berlaku, bukan apa yang
    // seharusnya berlaku.
    std::fprintf(stderr, "NevusQuetta: %s\n", decision.message.c_str());
    std::fprintf(stderr,
                 "NevusQuetta: kebijakan aktif -> host terblokir=%zu, "
                 "https_only_top_level=%d, https_only_subresource=%d, "
                 "gpc=%d, izin_ditolak_default=%d, failsafe=%d\n",
                 nq::PrivacyPolicy::BlockedHostCount(),
                 nq::PrivacyPolicy::IsAllowedTopLevelScheme("http://contoh.test/")
                     ? 0
                     : 1,
                 nq::PrivacyPolicy::IsAllowedSubresourceScheme(
                     "http://contoh.test/gambar.png")
                     ? 0
                     : 1,
                 nq::PrivacyPolicy::ShouldSendGlobalPrivacyControl() ? 1 : 0,
                 nq::PrivacyPolicy::IsPermissionDeniedByDefault(0) ? 1 : 0,
                 decision.using_failsafe_policy ? 1 : 0);
  }

  CefSettings settings;
  settings.no_sandbox = false;   // ditegaskan: sandbox WAJIB aktif
  settings.multi_threaded_message_loop = false;
  settings.persist_session_cookies = false;
  settings.log_severity = LOGSEVERITY_WARNING;
  CefString(&settings.user_agent_product).FromString("NevusQuetta");

  // Direktori state harus berada di jalur yang dikendalikan aplikasi, bukan
  // direktori kerja (bisa tidak dapat ditulis atau dibagikan).
  CefString(&settings.cache_path).FromString("./nevus-profile");

  if (!CefInitialize(main_args, settings, app.get(), nullptr)) {
    return kExitCefInitFailed;
  }

  // Lapisan jendela platform (Shell) disediakan oleh CEF_STANDARD_SOURCES.
  // Lihat README-BUILD.md untuk cara mengaktifkan contoh Shell bila diperlukan.
  CefRunMessageLoop();
  CefShutdown();
  return 0;
}
