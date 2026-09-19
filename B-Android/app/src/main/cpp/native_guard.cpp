#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <memory>
#include <string>
#include <unordered_set>

namespace {
using HostSet = std::unordered_set<std::string>;

std::shared_ptr<const HostSet> g_hosts = std::make_shared<const HostSet>(HostSet{
    "doubleclick.net",
    "googlesyndication.com",
    "google-analytics.com",
    "facebook.net",
    "scorecardresearch.com",
    "app-measurement.com",
});
std::atomic<int> g_version{0};

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    while (!value.empty() && value.back() == '.') value.pop_back();
    return value;
}

bool blocked_by_suffix(const std::string& host, const HostSet& rules) {
    if (host.empty()) return false;
    std::string candidate = host;
    while (true) {
        if (rules.find(candidate) != rules.end()) return true;
        const auto dot = candidate.find('.');
        if (dot == std::string::npos) return false;
        candidate.erase(0, dot + 1);
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_nevus_quetta_NativeGuard_nativeLoadHosts(JNIEnv* env, jobject, jobjectArray hosts, jint version) {
    if (hosts == nullptr) return;
    const jsize n = env->GetArrayLength(hosts);
    auto next = std::make_shared<HostSet>();
    next->reserve(static_cast<size_t>(n) * 2U + 1U);

    for (jsize i = 0; i < n; ++i) {
        auto* value = static_cast<jstring>(env->GetObjectArrayElement(hosts, i));
        if (value == nullptr) continue;
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars != nullptr) {
            const std::string normalized = lower(chars);
            if (!normalized.empty()) next->insert(normalized);
            env->ReleaseStringUTFChars(value, chars);
        }
        env->DeleteLocalRef(value);
    }

    std::atomic_store_explicit(
        &g_hosts,
        std::static_pointer_cast<const HostSet>(next),
        std::memory_order_release
    );
    g_version.store(version, std::memory_order_release);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nevus_quetta_NativeGuard_isBlockedHost(JNIEnv* env, jobject, jstring value) {
    if (value == nullptr) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const std::string host = lower(chars);
    env->ReleaseStringUTFChars(value, chars);

    const auto snapshot = std::atomic_load_explicit(&g_hosts, std::memory_order_acquire);
    if (snapshot == nullptr) return JNI_FALSE;
    return blocked_by_suffix(host, *snapshot) ? JNI_TRUE : JNI_FALSE;
}
