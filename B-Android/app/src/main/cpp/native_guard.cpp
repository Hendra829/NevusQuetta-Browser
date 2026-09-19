#include <jni.h>

#include <algorithm>
#include <cctype>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex g_mu;
std::vector<std::string> g_hosts = {
    "doubleclick.net",
    "googlesyndication.com",
    "google-analytics.com",
    "facebook.net",
    "scorecardresearch.com",
    "app-measurement.com",
};
int g_version = 0;

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool suffix_match(const std::string& host, const std::string& rule) {
    if (host == rule) return true;
    return host.size() > rule.size() &&
           host.compare(host.size() - rule.size(), rule.size(), rule) == 0 &&
           host[host.size() - rule.size() - 1] == '.';
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_nevus_quetta_NativeGuard_nativeLoadHosts(JNIEnv* env, jobject, jobjectArray hosts, jint version) {
    if (hosts == nullptr) return;
    const jsize n = env->GetArrayLength(hosts);
    std::vector<std::string> next;
    next.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto* value = static_cast<jstring>(env->GetObjectArrayElement(hosts, i));
        if (value == nullptr) continue;
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars != nullptr) {
            next.push_back(lower(chars));
            env->ReleaseStringUTFChars(value, chars);
        }
        env->DeleteLocalRef(value);
    }
    std::lock_guard<std::mutex> lock(g_mu);
    g_hosts.swap(next);
    g_version = version;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nevus_quetta_NativeGuard_isBlockedHost(JNIEnv* env, jobject, jstring value) {
    if (value == nullptr) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const std::string host = lower(chars);
    env->ReleaseStringUTFChars(value, chars);
    std::lock_guard<std::mutex> lock(g_mu);
    for (const auto& rule : g_hosts) {
        if (suffix_match(host, rule)) return JNI_TRUE;
    }
    return JNI_FALSE;
}
