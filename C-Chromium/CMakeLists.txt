# NevusQuetta Chromium (CEF).
#
# DUA MODE:
#
#   1. Aplikasi penuh (butuh distribusi biner CEF):
#        cmake -S C-Chromium -B C-Chromium/build -DCEF_ROOT=/path/ke/cef_binary_xxx
#        cmake --build C-Chromium/build --config Release
#      Prasyarat CEF diperiksa gagal-keras (lihat blok di bawah).
#
#   2. Modul + uji mandiri TANPA CEF (dapat dijalankan di CI biasa):
#        cmake -S C-Chromium -B C-Chromium/build
#        cmake --build C-Chromium/build
#        ctest --test-dir C-Chromium/build --output-on-failure
#      Seluruh modul keamanan (JSON, SHA-256/512, ruleset, kebijakan privasi,
#      Ed25519, bootstrap) tidak menyertakan header CEF, sehingga kebijakan
#      privasi dapat diverifikasi tanpa Chromium sekalipun.
#
# Target: aplikasi desktop berbasis CEF dengan branding clean-room, mengikuti
# spesifikasi docs/superpowers/specs/2026-09-19-nevusquetta-v09-chromium-design.md
# §9 (M01-M04).
#
cmake_minimum_required(VERSION 3.22.1)
project(NevusQuettaChromium VERSION 0.9.0 LANGUAGES CXX)

# ---------------------------------------------------------------------------
# Mode build
# ---------------------------------------------------------------------------
# Target aplikasi (dan karenanya prasyarat CEF) hanya disusun bila CEF_ROOT
# diberikan. Tanpa CEF_ROOT, konfigurasi tetap BERHASIL dan hanya menghasilkan
# target uji mandiri. Ini menghindari CI biasa gagal hanya karena tidak ada
# distribusi CEF, tanpa melonggarkan syarat apa pun saat CEF_ROOT memang dipakai.
set(NQ_BUILD_CEF_APP OFF)
if(DEFINED CEF_ROOT AND NOT CEF_ROOT STREQUAL "")
    set(NQ_BUILD_CEF_APP ON)
endif()

# ---------------------------------------------------------------------------
# Pemeriksaan prasyarat CEF — gagal-keras, bukan diam-diam memakai default
# ---------------------------------------------------------------------------
if(NQ_BUILD_CEF_APP)
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
else()
    message(STATUS
        "CEF_ROOT tidak diset -> HANYA target modul & uji mandiri yang dibangun. "
        "Set -DCEF_ROOT=<dir CEF> untuk membangun aplikasi penuh.")
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
    src/nq_bootstrap.cpp
    src/nq_ed25519.cpp
    src/nq_json.cpp
    src/nq_privacy_policy.cpp
    src/nq_ruleset.cpp
    src/nq_sha256.cpp
    src/nq_sha512.cpp
)

set(NQ_HEADERS
    src/nevus_app.h
    src/nevus_client.h
    src/nq_bootstrap.h
    src/nq_ed25519.h
    src/nq_ed25519_constants.h
    src/nq_json.h
    src/nq_privacy_policy.h
    src/nq_ruleset.h
    src/nq_sha256.h
    src/nq_sha512.h
    src/nq_sha512_constants.h
    src/nq_version.h
)

# ---------------------------------------------------------------------------
# Target aplikasi — hanya bila CEF tersedia (pola resmi CEF)
# ---------------------------------------------------------------------------
if(NQ_BUILD_CEF_APP)
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
endif()  # NQ_BUILD_CEF_APP

# ---------------------------------------------------------------------------
# Target uji mandiri — TIDAK membutuhkan CEF
# ---------------------------------------------------------------------------
# Seluruh modul keamanan (JSON, SHA-256/512, ruleset, kebijakan privasi,
# Ed25519, bootstrap) sengaja tidak menyertakan header CEF, sehingga dapat
# dikompilasi dan dijalankan tanpa distribusi CEF. Inilah yang membuat
# kebijakan privasi dapat diverifikasi di CI biasa.
#
#   cmake -S C-Chromium -B C-Chromium/build
#   cmake --build C-Chromium/build --target nq_ruleset_test nq_sha512_test nq_ed25519_test
#   ctest --test-dir C-Chromium/build --output-on-failure
option(NQ_BUILD_TESTS "Bangun target uji mandiri (tanpa CEF)" ON)

if(NQ_BUILD_TESTS)
    set(NQ_TEST_CORE
        src/nq_bootstrap.cpp
        src/nq_ed25519.cpp
        src/nq_json.cpp
        src/nq_privacy_policy.cpp
        src/nq_ruleset.cpp
        src/nq_sha256.cpp
        src/nq_sha512.cpp
    )
    set(NQ_TEST_WARNINGS $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Werror>)

    add_executable(nq_ruleset_test tests/nq_ruleset_test.cpp ${NQ_TEST_CORE})
    add_executable(nq_sha512_test tests/nq_sha512_quick_test.cpp
        src/nq_sha512.cpp)
    add_executable(nq_ed25519_test tests/nq_ed25519_test.cpp
        src/nq_ed25519.cpp)

    foreach(t nq_ruleset_test nq_sha512_test nq_ed25519_test)
        target_include_directories(${t} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
        target_compile_options(${t} PRIVATE ${NQ_TEST_WARNINGS})
    endforeach()

    # CATATAN: tidak ada add_test yang mengandalkan flag khusus di sini. Biner
    # uji sudah menjalankan jalur gagal-keras di dalam dirinya sendiri; menambah
    # add_test dengan argumen yang tidak dikenali biner hanya akan menghasilkan
    # kegagalan palsu di CI.
    enable_testing()
    add_test(NAME nq_ruleset_test COMMAND nq_ruleset_test)
    add_test(NAME nq_sha512_test COMMAND nq_sha512_test)
    add_test(NAME nq_ed25519_test COMMAND nq_ed25519_test)
endif()

# ---------------------------------------------------------------------------
# Ringkasan konfigurasi (memudahkan audit bukti build)
# ---------------------------------------------------------------------------
message(STATUS "NevusQuetta Chromium")
message(STATUS "  produk      : ${NQ_PRODUCT_NAME} (${NQ_PACKAGE})")
message(STATUS "  versi       : ${NQ_VERSION_MAJOR}.${NQ_VERSION_MINOR}.${NQ_VERSION_PATCH}")
if(NQ_BUILD_CEF_APP)
    message(STATUS "  cef root    : ${CEF_ROOT}")
    message(STATUS "  cef versi   : ${CEF_VERSION}")
else()
    message(STATUS "  cef         : TIDAK ADA (mode modul & uji mandiri)")
endif()
message(STATUS "  ruleset     : ${NQ_BLOCKED_HOSTS_ASSET}")
if(NOT NQ_ALLOW_UNSIGNED_RULESET)
    message(STATUS "  ruleset unsigned: DITOLAK (build rilis)")
else()
    message(WARNING "  ruleset unsigned: DIIZINKAN — hanya untuk lab/dev")
endif()
