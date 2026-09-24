# NevusQuetta Chromium (CEF) — skeleton konfigurasi build.
#
# STATUS: KONFIGURASI SAJA. Belum pernah dikompilasi — lingkungan penyusunan
# tidak memiliki toolchain CEF/Chromium (tidak ada CMake, Ninja, clang, maupun
# distribusi biner CEF). Lihat README-BUILD.md bagian "Status verifikasi".
#
# Target: aplikasi desktop berbasis CEF (Chromium Embedded Framework) dengan
# branding clean-room, mengikuti spesifikasi docs/superpowers/specs/
# 2026-09-19-nevusquetta-v09-chromium-design.md §9 (M01–M04).
#
# Cara pakai:
#   cmake -S C-Chromium -B C-Chromium/build -DCEF_ROOT=/path/ke/cef_binary_xxx
#   cmake --build C-Chromium/build --config Release
#
cmake_minimum_required(VERSION 3.22.1)
project(NevusQuettaChromium VERSION 0.9.0 LANGUAGES CXX)

# ---------------------------------------------------------------------------
# Pemeriksaan prasyarat — gagal-keras, bukan diam-diam memakai default
# ---------------------------------------------------------------------------
if(NOT DEFINED CEF_ROOT)
    message(FATAL_ERROR
        "CEF_ROOT belum diset. Unduh distribusi biner CEF dari "
        "https://cef-builds.spotifycdn.com/index.html lalu jalankan cmake dengan "
        "-DCEF_ROOT=<dir hasil ekstrak>. Lihat README-BUILD.md.")
endif()

if(NOT EXISTS "${CEF_ROOT}/cmake/cef_variables.cmake")
    message(FATAL_ERROR
        "CEF_ROOT='${CEF_ROOT}' tidak berisi cmake/cef_variables.cmake. "
        "Direktori ini bukan distribusi biner CEF yang lengkap.")
endif()

# Modul CMake resmi dari distribusi CEF.
list(APPEND CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
include(cef_variables)

if(CEF_VERSION_MAJOR LESS 120)
    message(FATAL_ERROR
        "CEF ${CEF_VERSION} terlalu lama. Proyek menargetkan CEF >= 120 agar "
        "WebView/WebMessageListener dan GPC dapat diimplementasikan.")
endif()

# ---------------------------------------------------------------------------
# Identitas produk — sengaja terpisah agar mudah di-override saat rilis
# ---------------------------------------------------------------------------
set(NQ_PRODUCT_NAME        "NevusQuetta"        CACHE STRING "Nama produk tampilan")
set(NQ_PACKAGE             "com.nevus.quetta"   CACHE STRING "Identitas paket aplikasi")
set(NQ_VERSION_MAJOR       "0"                  CACHE STRING "")
set(NQ_VERSION_MINOR       "9"                  CACHE STRING "")
set(NQ_VERSION_PATCH       "0"                  CACHE STRING "")
set(NQ_HOME_URL            "https://www.nevusquetta.local/" CACHE STRING "Start page")
set(NQ_BLOCKED_HOSTS_ASSET "${CMAKE_CURRENT_SOURCE_DIR}/assets/nevus_ruleset.json"
     CACHE STRING "Ruleset pemblokiran host (sumber, versi, lisensi wajib dicatat)")

# Wajib eksplisit: rilis tidak boleh memakai berkas ruleset lab yang tidak
# bertanda tangan (spec §9.3: filter adblock harus punya sumber & lisensi jelas).
option(NQ_ALLOW_UNSIGNED_RULESET
    "Izinkan ruleset tanpa tanda tangan (HANYA untuk build lab/dev)" OFF)

# ---------------------------------------------------------------------------
# Sumber
# ---------------------------------------------------------------------------
set(NQ_SOURCES
    src/cef_app_main.cpp
    src/nevus_app.cpp
    src/nevus_client.cpp
    src/nq_privacy_policy.cpp
)

set(NQ_HEADERS
    src/nevus_app.h
    src/nevus_client.h
    src/nq_privacy_policy.h
    src/nq_version.h
)

# ---------------------------------------------------------------------------
# Target — pola resmi CEF (PREFIX/SUFFIX dari cef_variables.cmake)
# ---------------------------------------------------------------------------
add_executable(${PROJECT_NAME} ${NQ_SOURCES} ${NQ_HEADERS}
    ${CEF_STANDARD_SOURCES})

target_include_directories(${PROJECT_NAME} PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CEF_ROOT}"
)

target_compile_definitions(${PROJECT_NAME} PRIVATE
    NQ_PRODUCT_NAME="${NQ_PRODUCT_NAME}"
    NQ_PACKAGE="${NQ_PACKAGE}"
    NQ_HOME_URL="${NQ_HOME_URL}"
    NQ_VERSION_STRING="${NQ_VERSION_MAJOR}.${NQ_VERSION_MINOR}.${NQ_VERSION_PATCH}"
    NQ_ALLOW_UNSIGNED_RULESET=$<BOOL:${NQ_ALLOW_UNSIGNED_RULESET}>
    WRAPPING_CEF_SHARED=1
)

# ---------------------------------------------------------------------------
# Tautan platform (pola standar distribusi CEF)
# ---------------------------------------------------------------------------
if(OS_LINUX)
    set(NQ_LIBS ${CEF_STANDARD_LIBS} ${X11_LIBRARIES})
elseif(OS_WINDOWS)
    set(NQ_LIBS ${CEF_STANDARD_LIBS} Winmm Shlwapi)
elseif(OS_MACOS)
    set(NQ_LIBS ${CEF_STANDARD_LIBS})
endif()

target_link_libraries(${PROJECT_NAME} PRIVATE ${NQ_LIBS})

# Salin sumber daya runtime (icudtl.dat, *.pak, v8 snapshot, locales) ke samping
# executable. Tanpa ini aplikasi gagal start dengan pesan "failed to load
# resources" — penyebab paling umum kegagalan runtime CEF.
foreach(res IN LISTS CEF_RESOURCE_FILES)
    if(EXISTS "${CEF_ROOT}/Resources/${res}")
        add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${CEF_ROOT}/Resources/${res}"
                "$<TARGET_FILE_DIR:${PROJECT_NAME}>/${res}"
            VERBATIM)
    elseif(EXISTS "${CEF_ROOT}/${res}")
        add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${CEF_ROOT}/${res}"
                "$<TARGET_FILE_DIR:${PROJECT_NAME}>/${res}"
            VERBATIM)
    endif()
endforeach()

# ---------------------------------------------------------------------------
# Ringkasan konfigurasi (memudahkan audit bukti build)
# ---------------------------------------------------------------------------
message(STATUS "NevusQuetta Chromium")
message(STATUS "  produk      : ${NQ_PRODUCT_NAME} (${NQ_PACKAGE})")
message(STATUS "  versi       : ${NQ_VERSION_MAJOR}.${NQ_VERSION_MINOR}.${NQ_VERSION_PATCH}")
message(STATUS "  cef root    : ${CEF_ROOT}")
message(STATUS "  cef versi   : ${CEF_VERSION}")
message(STATUS "  ruleset     : ${NQ_BLOCKED_HOSTS_ASSET}")
if(NOT NQ_ALLOW_UNSIGNED_RULESET)
    message(STATUS "  ruleset unsigned: DITOLAK (build rilis)")
else()
    message(WARNING "  ruleset unsigned: DIIZINKAN — hanya untuk lab/dev")
endif()
