#include "include/cef_command_line.h"
#include "include/wrapper/cef_helpers.h"

#include "nevus_app.h"
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
    return 1;
  }

  // Lapisan jendela platform (Shell) disediakan oleh CEF_STANDARD_SOURCES.
  // Lihat README-BUILD.md untuk cara mengaktifkan contoh Shell bila diperlukan.
  CefRunMessageLoop();
  CefShutdown();
  return 0;
}
