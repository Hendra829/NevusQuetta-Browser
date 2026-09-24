#include "nevus_client.h"

#include "include/cef_cookie.h"
#include "include/wrapper/cef_helpers.h"

#include "nq_privacy_policy.h"

namespace nq {

// ---------------------------------------------------------------------------
// NevusRequestHandler
// ---------------------------------------------------------------------------
bool NevusRequestHandler::IsBlocked(const std::string& url) {
  // Satu-satunya sumber kebenaran: kebijakan yang berasal dari ruleset.
  return PrivacyPolicy::IsBlockedHost(url);
}

bool NevusRequestHandler::IsInsecureDowngrade(
    const std::string& top_level_url, const std::string& subresource_url) {
  // Penurunan https -> http adalah persis yang dicegah kebijakan. Dihitung dari
  // kebijakan aktif, bukan dari konstanta yang ditanam di kode.
  if (!PrivacyPolicy::IsAllowedTopLevelScheme(top_level_url)) return true;
  if (PrivacyPolicy::IsAllowedTopLevelScheme(subresource_url) &&
      !PrivacyPolicy::IsAllowedSubresourceScheme(subresource_url)) {
    return true;
  }
  return false;
}

bool NevusRequestHandler::OnBeforeBrowse(CefRefPtr<CefBrowser> browser,
                                         CefRefPtr<CefFrame> frame,
                                         CefRefPtr<CefRequest> request,
                                         bool user_gesture,
                                         bool is_redirect) {
  CEF_REQUIRE_UI_THREAD();
  const std::string url = request->GetURL().ToString();

  // 1. Host terblokir dibatalkan SEBELUM keluar jaringan. Berlaku untuk semua
  //    frame: sub-frame yang memuat pelacak tetap harus diblokir.
  if (IsBlocked(url)) {
    return true;  // true = batalkan navigasi
  }

  // 2. HTTPS-only untuk navigasi tingkat-atas. http://, file://, dan skema
  //    lokal lain ditolak di sini, bukan diserahkan ke pembuat request.
  if (frame->IsMain() && !PrivacyPolicy::IsAllowedTopLevelScheme(url)) {
    return true;
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
  if (IsBlocked(url)) {
    return RV_CANCEL;
  }

  // Skema: sub-sumber daya yang tidak diizinkan kebijakan dibatalkan. Ini juga
  // mencegah mixed-content http:// dari halaman https://.
  if (!PrivacyPolicy::IsAllowedSubresourceScheme(url)) {
    return RV_CANCEL;
  }

  // Global Privacy Control pada setiap permintaan (bukan hanya navigasi).
  // Diambil dari kebijakan, sehingga ruleset dapat mematikannya bila kelak
  // diizinkan; sekarang bernilai true dan header selalu disuntikkan.
  const std::string gpc = PrivacyPolicy::GlobalPrivacyControlHeader();
  if (!gpc.empty()) {
    const std::string::size_type colon = gpc.find(':');
    if (colon != std::string::npos) {
      const std::string name = gpc.substr(0, colon);
      std::string value = gpc.substr(colon + 1);
      while (!value.empty() && value.front() == ' ') value.erase(0, 1);
      CefRequest::HeaderMap headers;
      request->GetHeaderMap(headers);
      // Timpa bila sudah ada: nilai yang datang dari halaman tidak boleh
      // mengalahkan kebijakan aplikasi.
      headers.erase(name);
      headers.insert(std::make_pair(name, value));
      request->SetHeaderMap(headers);
    }
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

  // Kebijakan dari ruleset menentukan apakah izin boleh mengalir tanpa
  // interaksi. Ruleset saat ini hanya boleh memperketat (lihat
  // kPolicyRelaxationRejected di nq_ruleset.cpp), jadi nilainya selalu true;
  // cabang longgar tetap dijaga agar perubahan kebijakan tidak diam-diam
  // membuka izin.
  //
  // Gagal-tertutup: nilai bitmask izin yang tidak dikenali pun berakhir di
  // cabang default -> DITOLAK.
  if (PrivacyPolicy::IsPermissionDeniedByDefault(
          static_cast<int>(requested_permissions))) {
    callback->Cancel();
    return true;
  }

  // Belum ada jalur ini yang aktif. Dibiarkan eksplisit supaya pembaca kode
  // tidak menyimpulkan bahwa izin lain (geolokasi, notifikasi, klipboar)
  // sudah ditangani — CEF menyalurkannya lewat callback lain yang belum
  // dipasang.
  return false;
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
