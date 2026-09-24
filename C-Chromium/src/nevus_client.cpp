#include "nevus_client.h"

#include "include/cef_cookie.h"
#include "include/wrapper/cef_helpers.h"

#include "nq_privacy_policy.h"

namespace nq {

// ---------------------------------------------------------------------------
// NevusRequestHandler
// ---------------------------------------------------------------------------
bool NevusRequestHandler::OnBeforeBrowse(CefRefPtr<CefBrowser> browser,
                                         CefRefPtr<CefFrame> frame,
                                         CefRefPtr<CefRequest> request,
                                         bool user_gesture,
                                         bool is_redirect) {
  CEF_REQUIRE_UI_THREAD();
  const std::string url = request->GetURL().ToString();

  // HTTPS-first: hanya navigasi tingkat-atas HTTPS yang diteruskan. Selain itu
  // (mis. http://, file://) ditolak di sini, bukan diserahkan ke pembuat request.
  if (frame->IsMain() && !PrivacyPolicy::IsAllowedTopLevelScheme(url)) {
    return true;  // true = batalkan navigasi
  }
  return false;
}

CefRefPtr<CefResourceRequestHandler>
NevusRequestHandler::GetResourceRequestHandler(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefFrame> frame,
    CefRefPtr<CefRequest> request,
    bool is_navigation,
    bool is_download,
    const CefString& request_initiator,
    bool& disable_default_handling) {
  CEF_REQUIRE_IO_THREAD();
  return new NevusResourceRequestHandler();
}

// ---------------------------------------------------------------------------
// NevusResourceRequestHandler
// ---------------------------------------------------------------------------
CefRefPtr<CefCookieAccessFilter>
NevusResourceRequestHandler::GetCookieAccessFilter(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefFrame> frame,
    CefRefPtr<CefRequest> request) {
  // Tidak ada handler cookie khusus -> CEF memakai perilaku default (yang sudah
  // memisahkan cookie per-profil). Dikembalikan nullptr secara eksplisit.
  return nullptr;
}

CefResourceRequestHandler::ReturnValue
NevusResourceRequestHandler::OnBeforeResourceLoad(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefFrame> frame,
    CefRefPtr<CefRequest> request,
    CefRefPtr<CefCallback> callback) {
  CEF_REQUIRE_IO_THREAD();
  const std::string url = request->GetURL().ToString();

  // Adblock/tracker: blokir sebelum keluar jaringan.
  if (PrivacyPolicy::IsBlockedHost(url)) {
    return RV_CANCEL;
  }

  // Global Privacy Control pada setiap permintaan (bukan hanya navigasi).
  CefRequest::HeaderMap headers;
  request->GetHeaderMap(headers);
  if (headers.find("Sec-GPC") == headers.end()) {
    headers.insert(std::make_pair("Sec-GPC", "1"));
    request->SetHeaderMap(headers);
  }

  return RV_CONTINUE;
}

// ---------------------------------------------------------------------------
// NevusPermissionHandler
// ---------------------------------------------------------------------------
bool NevusPermissionHandler::OnRequestMediaAccessPermission(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefFrame> frame,
    const CefString& requesting_origin,
    uint32_t requested_permissions,
    CefRefPtr<CefMediaAccessCallback> callback) {
  CEF_REQUIRE_UI_THREAD();
  // Tolak tanpa menyimpan keputusan: pengguna harus menyetujui ulang setiap
  // kali, mencegah "izin lengket" yang tak sengaja.
  callback->Cancel();
  return true;
}

// ---------------------------------------------------------------------------
// NevusClient
// ---------------------------------------------------------------------------
class NevusClient::LifespanHandler : public CefLifeSpanHandler {
 public:
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    ++instance_count_;
  }
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    if (--instance_count_ == 0) {
      CefQuitMessageLoop();
    }
  }

 private:
  int instance_count_ = 0;
  IMPLEMENT_REFCOUNTING(LifespanHandler);
  DISALLOW_COPY_AND_ASSIGN(LifespanHandler);
};

class NevusClient::DisplayHandler : public CefDisplayHandler {
 public:
  void OnTitleChange(CefRefPtr<CefBrowser> browser,
                     const CefString& title) override {
    CEF_REQUIRE_UI_THREAD();
    CefDisplayHandler::OnTitleChange(browser, title);
  }

 private:
  IMPLEMENT_REFCOUNTING(DisplayHandler);
  DISALLOW_COPY_AND_ASSIGN(DisplayHandler);
};

NevusClient::NevusClient()
    : request_(new NevusRequestHandler()),
      permission_(new NevusPermissionHandler()),
      lifespan_(new LifespanHandler()),
      display_(new DisplayHandler()) {}

}  // namespace nq
