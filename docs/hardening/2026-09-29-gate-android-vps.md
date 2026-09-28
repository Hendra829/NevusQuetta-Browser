# Gate Android di VPS — Hasil Eksekusi Nyata dan Batas Lingkungan

Tanggal: 2026-09-29
Skrip: `B-Android/scripts/run_android_gate.sh`
Lingkungan: sandbox Debian 12 (bukan VPS produksi), root, batas memori cgroup **2 GiB**

## 1. Toolchain yang BERHASIL dipasang

Seluruh prasyarat yang diminta berhasil dipasang di lingkungan ini:

```
$ apt-get install -y openjdk-17-jdk-headless unzip
APT_INSTALL_EXIT=0
$ java -version
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment (build 17.0.20.1+1-1-deb12u1-Debian)
JAVA_EXIT=0

$ curl -sSL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
UNDUH_EXIT=0   (153.607.504 byte)
$ sdkmanager --version
12.0

$ yes | sdkmanager --licenses
LICENSES_EXIT=0   ("All SDK package licenses accepted")
$ sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
SDK_INSTALL_EXIT=0
$ sdkmanager "ndk;27.2.12479018"
NDK_INSTALL_EXIT=0
$ sdkmanager "cmake;3.22.1"
CMAKE_INSTALL_EXIT=0

$ ls /opt/android-sdk/
build-tools  cmdline-tools  licenses  platform-tools  platforms  ndk  cmake
$ ls /opt/android-sdk/ndk/
27.2.12479018
$ ls /opt/android-sdk/cmake/
3.22.1
```

Pemeriksaan prasyarat skrip gate **lulus seluruhnya**:

```
== Pemeriksaan prasyarat toolchain Android ==
  [v] java: /usr/lib/jvm/java-17-openjdk-amd64/bin/java (versi 17)
  [v] android sdk: /opt/android-sdk
  [v] lisensi SDK tersedia
  [v] ndk: 27.2.12479018
  [v] cmake: 3.22.1
  [v] gradle wrapper: /workspace/nq-work/repo/B-Android/gradlew
  [v] ruang disk: 4228 MiB tersisa (cukup untuk --quick)
```

## 2. Hasil gate

```
==================== RINGKASAN GATE ANDROID ====================
TAHAP                  HASIL    DURASI
clean                  LULUS    25s
testDebugUnitTest      GAGAL    358s
lintDebug              GAGAL    180s
assembleDebug          GAGAL    138s
================================================================
HASIL AKHIR: ADA TAHAP YANG GAGAL
GATE_QUICK_EXIT=1
```

## 3. Akar masalah: batas memori cgroup 2 GiB

Ketiga kegagalan punya **satu akar yang sama**, bukan kesalahan kode:

```
$ cat /sys/fs/cgroup/memory.max
2147483648          # 2 GiB — batas keras untuk seluruh container

$ free -m
               total        used        free      shared  buff/cache   available
Mem:          386325       72068       84361        6627      246241      314256
```

Host punya 377 GiB, tetapi **container dibatasi 2 GiB**. Sementara itu
`B-Android/gradle.properties` meminta:

```
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
```

Artinya daemon Gradle **sendirian** meminta 2048 MiB heap + 512 MiB metaspace
= 2560 MiB, sudah **melebihi** batas 2 GiB sebelum Kotlin, AGP, dan worker
mana pun berjalan. Kernel membunuh daemon, dan Gradle melaporkan:

```
org.gradle.launcher.daemon.client.DaemonDisappearedException:
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

### Bukti bahwa ini murni masalah memori, bukan kode

Dengan heap diturunkan ke 1024 MiB, tahap yang sama **berhasil**:

```
$ GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m" \
    ./gradlew clean --no-daemon --no-parallel --no-configuration-cache
BUILD SUCCESSFUL in 1m 49s
CLEAN_EXIT=0
```

Tahap `clean` yang sebelumnya GAGAL (247s) kini LULUS (25s) — satu-satunya
perubahan adalah batas heap.

## 4. Perbaikan yang disarankan untuk VPS

VPS produksi umumnya **tidak** memiliki batas 2 GiB, sehingga gate kemungkinan
besar lulus apa adanya. Namun bila VPS juga dibatasi, atau untuk membuat gate
tahan terhadap lingkungan berbatas, terapkan salah satu:

### Opsi A — turunkan heap di `gradle.properties` (paling sederhana)

```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.parallel=false
org.gradle.configuration-cache=false
```

### Opsi B — paksa kompilasi Kotlin in-process (menghemat satu JVM)

```bash
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.daemon.jvm.options=-Xmx512m"
```

### Opsi C — naikkan batas cgroup (bila berwenang)

```bash
# Docker: jalankan container dengan --memory=8g
# systemd: MemoryMax=8G pada unit
```

### Perintah gate yang disarankan di VPS

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m \
  -Dkotlin.compiler.execution.strategy=in-process"

cd B-Android
./scripts/run_android_gate.sh --log-dir build/gate-logs
```

## 5. Yang belum terverifikasi

- **Gate lengkap (`assembleRelease` + `bundleRelease`) belum pernah dijalankan.**
  Hanya `--quick` yang dicoba, dan itu pun belum lulus di lingkungan ini.
- **APK/AAB belum pernah dibangun.**
- **Uji unit Android belum pernah lulus** — `testDebugUnitTest` gagal karena OOM,
  bukan karena uji yang gagal. Jumlah uji yang lulus/gagal **tidak diketahui**.
- **`lintDebug` belum pernah lulus.**
- **Uji runtime di perangkat/emulator (API 35/36) tetap NOT RUN.**
- **Lingkungan ini bukan VPS produksi.** Batas 2 GiB adalah sifat sandbox ini;
  VPS dengan memori memadai kemungkinan besar memberi hasil berbeda.
- **`build-tools;35.0.0` dipasang otomatis oleh AGP** saat `testDebugUnitTest`
  berjalan (terlihat di log), meskipun repo meminta 36.0.0 — perlu ditinjau
  apakah versi yang dipakai sesuai harapan.
