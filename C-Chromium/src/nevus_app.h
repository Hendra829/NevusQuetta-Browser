#pragma once

#include "include/cef_app.h"

namespace nq {

// Subclass CefApp. Tanggung jawab: memilih proses (browser/renderer/utility)
// dan memasang skema kustom. TIDAK melakukan I/O jaringan di sini.
class NevusApp : public CefApp, public CefBrowserProcessHandler {
 public:
  NevusApp() = default;

  // CefApp
  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override {
    return this;
  }
  void OnRegisterCustomSchemes(CefRawPtr<CefSchemeRegistrar> registrar) override;

  // CefBrowserProcessHandler
  void OnContextInitialized() override;
  void OnBeforeChildProcessLaunch(CefRefPtr<CefCommandLine> command_line) override;

 private:
  IMPLEMENT_REFCOUNTING(NevusApp);
  DISALLOW_COPY_AND_ASSIGN(NevusApp);
};

}  // namespace nq
