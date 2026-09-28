# Paket B — Android Native: Status dan Cara Membangun

## Ringkasan

| Item | Status |
|---|---|
| Toolchain (JDK 17, SDK 36, NDK 27.2.12479018, CMake 3.22.1) | **TERSEDIA** di `/opt/android-sdk` |
| Gradle wrapper 8.11.1 | **BERJALAN** (`./gradlew --version` exit 0) |
| `clean` | **LULUS** — `BUILD SUCCESSFUL in 24s` |
| `assembleDebug` (APK) | **TERBLOKIR** — OOM cgroup 2 GiB saat kompilasi Kotlin |
| `testDebugUnitTest` | **TERBLOKIR** — OOM cgroup 2 GiB |
| APK/AAB | **BELUM DIHASILKAN** |

## Bug kode yang DITEMUKAN dan DIPERBAIKI

Kompilasi Kotlin mengungkap **5 error nyata** yang sebelumnya tersembunyi di balik
kegagalan OOM:

```
e: .../download/ResumableDownloadWorker.kt:91:38 Unresolved reference 'etag'.
e: .../download/ResumableDownloadWorker.kt:93:37 Unresolved reference 'etag'.
e: .../download/ResumableDownloadWorker.kt:97:37 Unresolved reference 'etag'.
e: .../download/ResumableDownloadWorker.kt:98:38 Unresolved reference 'lastModified'.
e: .../download/ResumableDownloadWorker.kt:100:37 Unresolved reference 'lastModified'.
```

**Akar masalah:** pada blok validasi resume (`offset > 0L`, cabang `HTTP_PARTIAL`),
kode memakai `etag` dan `lastModified` **telanjang**, padahal keduanya adalah field
entitas `DownloadEntity` (`val etag: String?`, `val lastModified: String?`) dan harus
diakses sebagai `item.etag` / `item.lastModified` — persis seperti pada pemanggilan
`openValidated(...)` beberapa baris di atasnya.

**Perbaikan:** mengganti kelima referensi menjadi `item.etag` / `item.lastModified`.
Perbaikan ini **tidak mengubah perilaku** — hanya memperbaiki resolusi nama.

## Akar masalah OOM (bukti mentah)

```
$ cat /sys/fs/cgroup/memory.max
2147483648                      # 2 GiB

$ grep oom_kill /sys/fs/cgroup/memory.events
oom_kill 9                      # sebelum perbaikan
oom_kill 10                     # setelah percobaan ulang

$ tail -3 gate-logs/1_testDebugUnitTest.log
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
org.gradle.launcher.daemon.client.DaemonDisappearedException
```

Bahkan dengan setelan paling hemat (`-Xmx1024m`, `--no-parallel`,
`--no-configuration-cache`, `workers.max=1`, `kotlin.compiler.execution.strategy=in-process`),
kompilasi Kotlin tetap melampaui 2 GiB. Gradle beralih ke
`Using fallback strategy: Compile without Kotlin daemon`, lalu tetap kehabisan memori.

**Kesimpulan:** ini **batas sumber daya lingkungan**, bukan kegagalan kode. Di VPS
dengan RAM ≥ 8 GB, gate ini seharusnya lulus.

## Cara membangun di VPS

```bash
# 1. Prasyarat
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip curl
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# 2. Android SDK + NDK (versi persis yang dipakai repo)
mkdir -p "$HOME/android-sdk/cmdline-tools" && cd "$HOME/android-sdk/cmdline-tools"
curl -fsSLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest 2>/dev/null || true
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
           "ndk;27.2.12479018" "cmake;3.22.1"

# 3. Gate lengkap
cd B-Android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./scripts/run_android_gate.sh --quick        # clean + unit test + lint + assembleDebug
./scripts/run_android_gate.sh                # + assembleRelease + bundleRelease (butuh keystore)

# 4. APK
# app/build/outputs/apk/debug/app-debug.apk
```

## Build rilis (bertanda tangan)

Signing **gagal-tertutup** — `assembleRelease`/`bundleRelease` menolak berjalan tanpa
keystore:

```bash
export NEVUS_RELEASE_STORE=/path/ke/release.jks
export NEVUS_RELEASE_STORE_PASSWORD=...
export NEVUS_RELEASE_ALIAS=...
export NEVUS_RELEASE_KEY_PASSWORD=...
./gradlew --no-daemon assembleRelease bundleRelease
```

## Uji runtime (butuh perangkat/emulator)

```bash
./scripts/runtime_gate.sh 35     # API 35
./scripts/runtime_gate.sh 36     # API 36
```

## Isi kit ini

- `scripts/run_android_gate.sh` — gate lengkap dengan pemeriksaan prasyarat
- `scripts/assemble_b.sh` — assemble di direktori build terpisah
- `scripts/runtime_gate.sh` — uji instrumentasi di perangkat/emulator
- `scripts/test_android_gate.sh` — uji skrip gate itu sendiri
- `scripts/test_line_ending_guard.sh` — penjaga akhir baris
- `README.md` — README modul B-Android
- `local.properties.example` — contoh konfigurasi SDK
- `gradle/wrapper/gradle-wrapper.properties` — konfigurasi wrapper (8.11.1)
- `app/build.gradle.kts` — konfigurasi modul (untuk rujukan versi)

## Yang belum terverifikasi

- **APK belum pernah dihasilkan** di lingkungan mana pun yang tersedia.
- **AAB belum pernah dihasilkan.**
- **Unit test belum pernah lulus** — terblokir OOM sebelum sempat mengeksekusi test.
- **Lint belum pernah lulus.**
- **Uji runtime API 35/36 belum pernah dijalankan** — butuh perangkat/emulator.
- Perbaikan `item.etag`/`item.lastModified` **belum terbukti terkompilasi** — kompilasi
  tidak pernah selesai karena OOM. Perbaikan ini menunggu verifikasi di VPS.
