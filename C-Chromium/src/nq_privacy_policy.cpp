#include "nq_privacy_policy.h"

#include <algorithm>
#include <cctype>
#include <mutex>
#include <string>
#include <vector>

namespace nq {
namespace {

// Nilai enum CEF yang relevan. Disalin sebagai konstanta agar berkas ini dapat
// dikompilasi dan diuji tanpa menyertakan header CEF penuh.
constexpr int kPermGeolocation = 0;
constexpr int kPermMidiSysex = 1;
constexpr int kPermNotifications = 2;
constexpr int kPermMediaStreamMic = 3;
constexpr int kPermMediaStreamCamera = 4;
constexpr int kPermMediaStreamCameraPanTiltZoom = 5;
constexpr int kPermClipboard = 6;

struct PolicyState {
  bool installed = false;
  RulesetPolicy policy = BuildFailsafePolicy();
};

std::mutex& PolicyMutex() {
  static std::mutex mutex;
  return mutex;
}

PolicyState& State() {
  static PolicyState state;
  return state;
}

std::string Lower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return value;
}

// Ekstraksi host dari URL tanpa dependensi. Semua kasus "tidak yakin"
// mengembalikan string kosong; pemanggil memperlakukannya sebagai TIDAK
// dapat dinilai (dan untuk HTTPS-only, sebagai TIDAK diizinkan).
std::string HostOf(const std::string& url) {
  const auto scheme_end = url.find("://");
  if (scheme_end == std::string::npos) return {};
  std::size_t begin = scheme_end + 3;
  const auto at = url.find('@', begin);
  const auto slash = url.find('/', begin);
  if (at != std::string::npos && (slash == std::string::npos || at < slash)) {
    begin = at + 1;  // buang userinfo: mencegah trik "https://situs.aman@evil"
  }
  const auto end = url.find_first_of("/?#", begin);
  std::string authority = url.substr(
      begin, end == std::string::npos ? std::string::npos : end - begin);
  // IPv6 literal "[::1]:443" -> buang kurung siku dan port.
  if (!authority.empty() && authority.front() == '[') {
    const auto close = authority.find(']');
    if (close == std::string::npos) return {};
    return Lower(authority.substr(1, close - 1));
  }
  const auto colon = authority.rfind(':');
  if (colon != std::string::npos) authority = authority.substr(0, colon);
  // Buang titik akar opsional: "example.com." dan "example.com" harus sama.
  if (!authority.empty() && authority.back() == '.') authority.pop_back();
  return Lower(authority);
}

bool HasScheme(const std::string& url, const char* scheme) {
  const auto lowered = Lower(url);
  const std::size_t len = std::char_traits<char>::length(scheme);
  if (lowered.size() < len) return false;
  if (lowered.compare(0, len, scheme) != 0) return false;
  return true;
}

// Skema yang boleh muncul pada sub-sumber daya tanpa membocorkan kebijakan.
// Data URI dan blob tetap aman untuk CSS/gambar namun tidak melewati jaringan.
bool IsLocallyInertScheme(const std::string& lowered) {
  return lowered.rfind("data:", 0) == 0 || lowered.rfind("blob:", 0) == 0 ||
         lowered.rfind("about:", 0) == 0;
}

}  // namespace

bool PrivacyPolicy::InstallPolicy(const RulesetPolicy& policy) {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  PolicyState& state = State();
  if (state.installed) {
    // Pemasangan kedua selalu ditolak: mencegah penurunan kebijakan runtime.
    return false;
  }
  state.policy = policy;
  state.installed = true;
  return true;
}

bool PrivacyPolicy::HasInstalledPolicy() {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  return State().installed;
}

RulesetPolicy PrivacyPolicy::ActivePolicyForTesting() {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  return State().policy;
}

void PrivacyPolicy::ResetPolicyForTesting() {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  State().installed = false;
  State().policy = BuildFailsafePolicy();
}

std::size_t PrivacyPolicy::BlockedHostCount() {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  return State().policy.blocked_hosts.size();
}

bool PrivacyPolicy::IsAllowedTopLevelScheme(const std::string& url) {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  const RulesetPolicy& policy = State().policy;
  const std::string lowered = Lower(url);
  if (policy.https_only_top_level) {
    return HasScheme(lowered, "https://") ||
           HasScheme(lowered, "nevus://");  // halaman internal read-only
  }
  return HasScheme(lowered, "https://") || HasScheme(lowered, "http://") ||
         HasScheme(lowered, "nevus://");
}

bool PrivacyPolicy::IsAllowedSubresourceScheme(const std::string& url) {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  const RulesetPolicy& policy = State().policy;
  const std::string lowered = Lower(url);
  if (IsLocallyInertScheme(lowered)) return true;
  if (policy.https_only_subresource) {
    return HasScheme(lowered, "https://");
  }
  return HasScheme(lowered, "https://") || HasScheme(lowered, "http://");
}

bool PrivacyPolicy::IsBlockedHost(const std::string& url) {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  const std::vector<BlockRule>& rules = State().policy.blocked_hosts;
  const std::string host = HostOf(url);
  if (host.empty()) return false;  // bukan permintaan jaringan yang dapat dinilai
  for (const BlockRule& rule : rules) {
    if (rule.mode == MatchMode::kExact) {
      if (host == rule.host) return true;
      continue;
    }
    // suffix: cocokkan host persis atau sebagai label terpisah
    // ("evil-doubleclick.net" TIDAK boleh cocok dengan "doubleclick.net").
    if (host == rule.host) return true;
    if (host.size() > rule.host.size() &&
        host.compare(host.size() - rule.host.size(), rule.host.size(),
                     rule.host) == 0 &&
        host[host.size() - rule.host.size() - 1] == '.') {
      return true;
    }
  }
  return false;
}

bool PrivacyPolicy::IsPermissionDeniedByDefault(int permission) {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  if (!State().policy.deny_permissions_by_default) {
    // Nilai ini tidak dapat dilonggarkan oleh ruleset saat ini (lihat
    // kPolicyRelaxationRejected), sehingga cabang ini hanya dapat tercapai bila
    // kebijakan dipasang langsung oleh kode pengujian.
    return false;
  }
  switch (permission) {
    case kPermGeolocation:
    case kPermMidiSysex:
    case kPermNotifications:
    case kPermMediaStreamMic:
    case kPermMediaStreamCamera:
    case kPermMediaStreamCameraPanTiltZoom:
    case kPermClipboard:
      return true;  // butuh persetujuan eksplisit per-situs, bukan default-boleh
    default:
      return true;  // gagal-tertutup: izin tak dikenal DITOLAK
  }
}

bool PrivacyPolicy::ShouldSendGlobalPrivacyControl() {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  return State().policy.send_gpc;
}

std::string PrivacyPolicy::GlobalPrivacyControlHeader() {
  std::lock_guard<std::mutex> lock(PolicyMutex());
  if (!State().policy.send_gpc) return std::string();
  return "Sec-GPC: 1";
}

}  // namespace nq
