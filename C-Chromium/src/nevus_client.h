#pragma once

#include "include/cef_client.h"
#include "include/cef_request_handler.h"

#include <vector>

namespace nq {

// Handler permintaan jaringan: menegakkan HTTPS-only dan ruleset pemblokiran.
class NevusRequestHandler : public CefRequestHandler {
 public:
  NevusRequestHandler() = default;

  bool OnBeforeBrowse(CefRefPtr<CefBrowser> browser,
                      CefRefPtr<CefFrame> frame,
                      CefRefPtr<CefRequest> request,
                      bool user_gesture,
                      bool is_redirect) override;

  CefRefPtr<CefResourceRequestHandler> GetResourceRequestHandler(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      bool is_navigation,
      bool is_download,
      const CefString& request_initiator,
      bool& disable_default_handling) override;

 private:
  IMPLEMENT_REFCOUNTING(NevusRequestHandler);
  DISALLOW_COPY_AND_ASSIGN(NevusRequestHandler);
};

// Handler sumber daya per-permintaan: menyuntikkan Sec-GPC dan memblokir host.
class NevusResourceRequestHandler : public CefResourceRequestHandler {
 public:
  NevusResourceRequestHandler() = default;

  CefRefPtr<CefCookieAccessFilter> GetCookieAccessFilter(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request) override;

  ReturnValue OnBeforeResourceLoad(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   CefRefPtr<CefRequest> request,
                                   CefRefPtr<CefCallback> callback) override;

 private:
  IMPLEMENT_REFCOUNTING(NevusResourceRequestHandler);
  DISALLOW_COPY_AND_ASSIGN(NevusResourceRequestHandler);
};

// Handler dialog izin: menolak semua izin secara default.
class NevusPermissionHandler : public CefPermissionHandler {
 public:
  NevusPermissionHandler() = default;

  bool OnRequestMediaAccessPermission(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      const CefString& requesting_origin,
      uint32_t requested_permissions,
      CefRefPtr<CefMediaAccessCallback> callback) override;

 private:
  IMPLEMENT_REFCOUNTING(NevusPermissionHandler);
  DISALLOW_COPY_AND_ASSIGN(NevusPermissionHandler);
};

// Klien browser: merangkai seluruh handler.
class NevusClient : public CefClient {
 public:
  NevusClient();

  CefRefPtr<CefRequestHandler> GetRequestHandler() override { return request_; }
  CefRefPtr<CefPermissionHandler> GetPermissionHandler() override {
    return permission_;
  }
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return lifespan_; }
  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return display_; }

 private:
  class LifespanHandler;
  class DisplayHandler;

  CefRefPtr<NevusRequestHandler> request_;
  CefRefPtr<NevusPermissionHandler> permission_;
  CefRefPtr<CefLifeSpanHandler> lifespan_;
  CefRefPtr<CefDisplayHandler> display_;

  IMPLEMENT_REFCOUNTING(NevusClient);
  DISALLOW_COPY_AND_ASSIGN(NevusClient);
};

}  // namespace nq
