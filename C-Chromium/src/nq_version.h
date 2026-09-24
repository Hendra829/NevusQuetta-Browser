#pragma once

// Nilai versi disuntik lewat CMake (lihat CMakeLists.txt) dengan fallback agar
// berkas ini tetap dapat disunting/di-lint tanpa konfigurasi build.

#ifndef NQ_PRODUCT_NAME
#define NQ_PRODUCT_NAME "NevusQuetta"
#endif

#ifndef NQ_PACKAGE
#define NQ_PACKAGE "com.nevus.quetta"
#endif

#ifndef NQ_HOME_URL
#define NQ_HOME_URL "https://www.nevusquetta.local/"
#endif

#ifndef NQ_VERSION_STRING
#define NQ_VERSION_STRING "0.9.0-dev"
#endif

// 0 (default) = build rilis: ruleset tanpa tanda tangan DITOLAK.
#ifndef NQ_ALLOW_UNSIGNED_RULESET
#define NQ_ALLOW_UNSIGNED_RULESET 0
#endif

namespace nq {
inline constexpr const char* kProductName = NQ_PRODUCT_NAME;
inline constexpr const char* kPackage = NQ_PACKAGE;
inline constexpr const char* kHomeUrl = NQ_HOME_URL;
inline constexpr const char* kVersion = NQ_VERSION_STRING;
}  // namespace nq
