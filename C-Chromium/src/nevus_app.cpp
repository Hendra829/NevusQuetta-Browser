#include "nevus_app.h"

#include "include/cef_command_line.h"
#include "include/wrapper/cef_helpers.h"

#include "nevus_client.h"
#include "nq_version.h"

namespace nq {

void NevusApp::OnRegisterCustomSchemes(CefRawPtr<CefSchemeRegistrar> registrar) {
  // Skema internal read-only untuk halaman bantuan/offline. Sengaja TIDAK
  // didaftarkan sebagai "standard" agar tidak bisa dipakai sebagai origin yang
  // dapat menyimpan state atau memuat skrip situs.
  registrar->AddCustomScheme(
      "nevus", /*is_standard=*/false, /*is_local=*/true,
      /*is_display_isolated=*/true, /*is_secure=*/true, /*is_cors_enabled=*/false,
      /*is_csp_bypassing=*/false);
}

void NevusApp::OnBeforeChildProcessLaunch(CefRefPtr<CefCommandLine> command_line) {
  // Proses anak TIDAK boleh mewarisi flag berbahaya. CEF sudah menolak
  // --no-sandbox pada proses anak, tetapi menegaskannya di sini membuat
  // pelanggaran terlihat saat audit.
  if (command_line->HasSwitch("no-sandbox")) {
    command_line->RemoveSwitch("no-sandbox");
  }
  command_line->AppendSwitch("disable-features");
  command_line->AppendSwitchWithValue(
      "disable-features",
      "Translate,OptimizationHints,MediaRouter,InterestFeedContentSuggestions");
}

void NevusApp::OnContextInitialized() {
  CEF_REQUIRE_UI_THREAD();
  // Pembuatan jendela utama dilakukan oleh lapisan platform (Shell) yang
  // menyertakan CEF_STANDARD_SOURCES; lihat cef_app_main.cpp.
}

}  // namespace nq
