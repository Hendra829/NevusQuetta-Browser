# NevusQuetta V0.9A+B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menghasilkan APK NevusQuetta V0.9A+B yang aman, modular, kompatibel API 35/36, mempertahankan unduhan/bookmark/vault/preferensi/data pengguna, serta menyediakan multi-tab, bookmark, riwayat, mode privat, dan pemulihan sesi.

**Architecture:** Aplikasi satu modul Android dipecah dari `MainActivity` monolitik menjadi komponen navigasi, konfigurasi WebView, tab, penyimpanan Room, vault, dan pembersihan aman. UI tetap XML/ViewBinding agar migrasi dari V0.8 terkendali; state persisten mengalir melalui repository dan model terdefinisi, sedangkan Activity hanya mengoordinasikan tampilan serta lifecycle.

**Tech Stack:** Kotlin 2.1.21, Android Gradle Plugin 8.10.1, Gradle 8.11.1, Android SDK 36, minSdk 26, AndroidX Activity/AppCompat, Material Components, Room 2.7.2, Coroutines 1.10.2, Android Keystore, AndroidX WebKit 1.14.0, WebView, JUnit 4, Robolectric 4.14.1, dan AndroidX Test.

**Spec:** `docs/superpowers/specs/2026-09-19-nevusquetta-v09-chromium-design.md`

## Global Constraints

- Urutan resmi tetap V0.9A+B → V0.9C+D → Chromium M01–M04.
- Runtime wajib diuji pada Android API 35 dan API 36.
- `applicationId` tetap `com.nevus.quetta`; versi tahap ini menjadi `0.9.0-ab` dengan `versionCode = 900`.
- `minSdk = 26`, `compileSdk = 36`, dan `targetSdk = 36`.
- Unduhan, bookmark, vault, preferensi, dan data pengguna penting tidak boleh dihapus saat upgrade.
- Cache, log, file sementara, APK/ZIP lama, dan output build hanya dibersihkan setelah artefak baru terverifikasi.
- Keystore produksi dan password signing tidak boleh berada dalam source atau arsip.
- HTTP cleartext, mixed content, file access, universal file URL access, dan third-party cookies nonaktif secara default.
- Tidak melewati DRM, paywall, autentikasi, atau kontrol akses situs.
- Tahap berikutnya terkunci bila gate aktif belum `PASS`.

## Review Focus

- Input address bar kosong, URL Unicode, URL ber-spasi, dan skema berbahaya harus menghasilkan navigasi atau penolakan yang deterministik; diuji pada Task 3.
- Database V0.8 kosong/rusak dan upgrade skema harus mempertahankan bookmark serta state yang masih valid tanpa destructive migration; diuji pada Task 5.
- Process death saat beberapa tab aktif harus memulihkan metadata tanpa mencoba menyimpan object `WebView`; diuji pada Task 7.
- Symlink/path traversal pada cleanup tidak boleh membawa penghapusan keluar dari direktori cache milik aplikasi; diuji pada Task 6.
- Pesan bridge dengan origin berbeda, URL non-HTTPS, payload terlalu panjang, atau tipe tidak dikenal harus ditolak; diuji pada Task 4.

---

## Peta File dan Tanggung Jawab

### File yang dimodifikasi

- `B-Android/settings.gradle.kts` — repository/plugin resolution.
- `B-Android/build.gradle.kts` — versi Android Gradle Plugin dan Kotlin.
- `B-Android/gradle/wrapper/gradle-wrapper.properties` — Gradle 8.11.1 dan checksum distribusi.
- `B-Android/app/build.gradle.kts` — SDK 36, versi, dependency, signing aman, test options.
- `B-Android/gradle.properties` — opsi build non-rahasia.
- `B-Android/app/src/main/AndroidManifest.xml` — network security, backup rules, Activity, dan application class.
- `B-Android/app/src/main/java/com/nevus/quetta/MainActivity.kt` — diperkecil menjadi koordinator UI.
- `B-Android/app/src/main/res/layout/activity_main.xml` — toolbar browser, tab counter, WebView container, dan state kosong.
- `B-Android/app/src/main/res/values/strings.xml` — seluruh teks UI.
- `B-Android/.gitignore` — secret signing dan output lokal.

### File yang dibuat

- `B-Android/app/src/main/java/com/nevus/quetta/NevusApplication.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/navigation/NavigationController.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/web/SecureWebViewFactory.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/web/MediaBridgeMessage.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/web/SafeMediaBridge.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/tabs/BrowserTab.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/tabs/TabManager.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/data/BrowserDatabase.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/data/BookmarkEntity.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/data/HistoryEntity.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/data/TabEntity.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/data/BrowserDao.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/data/BrowserRepository.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/cleanup/CleanupManager.kt`
- `B-Android/app/src/main/java/com/nevus/quetta/security/ReleaseSecurity.kt`
- `B-Android/app/src/main/res/xml/network_security_config.xml`
- `B-Android/app/src/main/res/xml/backup_rules.xml`
- `B-Android/app/src/main/res/layout/dialog_tabs.xml`
- `B-Android/app/src/main/res/layout/dialog_bookmarks.xml`
- Unit tests pada package yang sama di `B-Android/app/src/test/java/com/nevus/quetta/`.
- Instrumentation tests di `B-Android/app/src/androidTest/java/com/nevus/quetta/`.

## Task 1: Baseline, Toolchain, dan Signing Aman

**Files:**
- Modify: `B-Android/build.gradle.kts`
- Modify: `B-Android/gradle/wrapper/gradle-wrapper.properties`
- Modify: `B-Android/app/build.gradle.kts`
- Modify: `B-Android/gradle.properties`
- Modify: `B-Android/.gitignore`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/security/ReleaseSecurity.kt`
- Create: `B-Android/app/src/test/java/com/nevus/quetta/BuildContractTest.kt`

**Interfaces:**
- Consumes: source V0.8 dan Gradle wrapper 8.9.
- Produces: kontrak build API 36 dan helper `ReleaseSecurity.requireReleaseSigning(Map<String, String?>): SigningMaterial` untuk Task 2.

- [ ] **Step 1: Simpan bukti baseline tanpa mengubah source**

Run:

```bash
cd B-Android
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Expected: catat exit code tiap task; kegagalan baseline dicatat sebagai `FAIL`, bukan disembunyikan dengan lint baseline.

- [ ] **Step 2: Tulis test kontrak signing yang gagal**

```kotlin
class BuildContractTest {
    @Test fun `release signing rejects missing secrets`() {
        assertFailsWith<IllegalStateException> {
            ReleaseSecurity.requireReleaseSigning(emptyMap())
        }
    }
}
```

Run: `./gradlew testDebugUnitTest --tests '*BuildContractTest*'`  
Expected: FAIL karena `ReleaseSecurity` belum tersedia.

- [ ] **Step 3: Terapkan konfigurasi build minimum**

Set Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.21, `compileSdk = 36`, `targetSdk = 36`, `versionCode = 900`, dan `versionName = "0.9.0-ab"`. Pin Room 2.7.2, Coroutines 1.10.2, AndroidX WebKit 1.14.0, dan Robolectric 4.14.1; dependency AndroidX lain yang sudah ada dipertahankan sampai dependency resolution dan lint membuktikan perlunya perubahan. Konfigurasi release hanya dibuat bila empat environment variable tersedia:

```kotlin
val releaseStore = providers.environmentVariable("NEVUS_RELEASE_STORE")
val releaseStorePassword = providers.environmentVariable("NEVUS_RELEASE_STORE_PASSWORD")
val releaseAlias = providers.environmentVariable("NEVUS_RELEASE_ALIAS")
val releaseKeyPassword = providers.environmentVariable("NEVUS_RELEASE_KEY_PASSWORD")
```

Debug memakai debug keystore standar Gradle. Hapus password hard-coded dan referensi `keystore/lab.jks` dari build script.

- [ ] **Step 4: Tambahkan kontrak signing murni**

```kotlin
data class SigningMaterial(val store: String, val storePassword: String, val alias: String, val keyPassword: String)

object ReleaseSecurity {
    fun requireReleaseSigning(env: Map<String, String?>): SigningMaterial {
        fun value(name: String) = env[name]?.takeIf(String::isNotBlank)
            ?: error("Missing release signing variable: $name")
        return SigningMaterial(value("NEVUS_RELEASE_STORE"), value("NEVUS_RELEASE_STORE_PASSWORD"), value("NEVUS_RELEASE_ALIAS"), value("NEVUS_RELEASE_KEY_PASSWORD"))
    }
}
```

- [ ] **Step 5: Abaikan secret dan verifikasi**

Tambahkan `keystore/`, `*.jks`, `*.keystore`, `local.properties`, `.env*`, `build/`, dan `app/build/` ke `.gitignore`.

Run:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
rg -n 'nevuslab122|storePassword\s*=\s*"|keyPassword\s*=\s*"' . --glob '!build/**' --glob '!app/build/**'
```

Expected: build PASS; pencarian secret tidak menghasilkan temuan source.

- [ ] **Step 6: Commit**

```bash
git add B-Android/.gitignore B-Android/build.gradle.kts B-Android/gradle.properties B-Android/gradle/wrapper/gradle-wrapper.properties B-Android/app/build.gradle.kts B-Android/app/src/main/java/com/nevus/quetta/security/ReleaseSecurity.kt B-Android/app/src/test/java/com/nevus/quetta/BuildContractTest.kt
git commit -m "build: harden v09ab toolchain and signing"
```

## Task 2: Manifest dan Kebijakan Platform

**Files:**
- Modify: `B-Android/app/src/main/AndroidManifest.xml`
- Create: `B-Android/app/src/main/res/xml/network_security_config.xml`
- Create: `B-Android/app/src/main/res/xml/backup_rules.xml`
- Create: `B-Android/app/src/test/java/com/nevus/quetta/ManifestPolicyTest.kt`

**Interfaces:**
- Consumes: konfigurasi SDK Task 1.
- Produces: kebijakan manifest yang digunakan seluruh komponen aplikasi.

- [ ] **Step 1: Tulis test manifest yang gagal**

Gunakan Robolectric `ApplicationProvider` dan `PackageManager` untuk memastikan `FLAG_USES_CLEARTEXT_TRAFFIC` tidak aktif, backup nonaktif, application class benar, dan hanya MainActivity launcher yang exported.

```kotlin
@Test fun `production manifest forbids cleartext and backup`() {
    val info = context.packageManager.getApplicationInfo(context.packageName, 0)
    assertThat(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    assertThat(info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC).isEqualTo(0)
}
```

Run: `./gradlew testDebugUnitTest --tests '*ManifestPolicyTest*'`  
Expected: FAIL sampai resource dan application class tersedia.

- [ ] **Step 2: Terapkan manifest**

Set `android:name=".NevusApplication"`, `android:networkSecurityConfig="@xml/network_security_config"`, `android:dataExtractionRules="@xml/backup_rules"`, `android:allowBackup="false"`, dan `android:usesCleartextTraffic="false"`. Jangan menambah permission kamera, mikrofon, lokasi, atau broad storage pada tahap ini.

- [ ] **Step 3: Tambahkan resource kebijakan**

`network_security_config.xml`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

`backup_rules.xml`:

```xml
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true"><exclude domain="root" path="." /></cloud-backup>
    <device-transfer><exclude domain="root" path="." /></device-transfer>
</data-extraction-rules>
```

- [ ] **Step 4: Jalankan test dan lint**

Run: `./gradlew testDebugUnitTest --tests '*ManifestPolicyTest*' lintDebug`  
Expected: PASS tanpa exported/backup/network-security error.

- [ ] **Step 5: Commit**

```bash
git add B-Android/app/src/main/AndroidManifest.xml B-Android/app/src/main/res/xml B-Android/app/src/test/java/com/nevus/quetta/ManifestPolicyTest.kt
git commit -m "security: enforce android platform policies"
```

## Task 3: Navigasi Aman dan Deterministik

**Files:**
- Create: `B-Android/app/src/main/java/com/nevus/quetta/navigation/NavigationController.kt`
- Create: `B-Android/app/src/test/java/com/nevus/quetta/navigation/NavigationControllerTest.kt`

**Interfaces:**
- Consumes: string input pengguna.
- Produces: `NavigationTarget.Web(Uri)`, `NavigationTarget.Search(Uri)`, atau `NavigationTarget.Rejected(reason: String)` melalui `NavigationController.resolve(raw: String): NavigationTarget`.

- [ ] **Step 1: Tulis test tabel input navigasi**

```kotlin
@Test fun `normalizes supported input and rejects dangerous schemes`() {
    val cases = mapOf(
        "example.com" to "https://example.com/",
        "https://example.com/a" to "https://example.com/a",
        "dua kata" to "https://www.google.com/search?q=dua%20kata"
    )
    cases.forEach { (raw, expected) -> assertEquals(expected, controller.resolve(raw).uri.toString()) }
    listOf("", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,x", "intent://x")
        .forEach { assertTrue(controller.resolve(it) is NavigationTarget.Rejected) }
}
```

Tambahkan kasus Unicode IDN, leading/trailing whitespace, port, fragment, dan HTTP upgrade.

Run: `./gradlew testDebugUnitTest --tests '*NavigationControllerTest*'`  
Expected: FAIL karena controller belum ada.

- [ ] **Step 2: Implementasikan model dan resolver**

```kotlin
sealed interface NavigationTarget {
    val uri: Uri
    data class Web(override val uri: Uri) : NavigationTarget
    data class Search(override val uri: Uri) : NavigationTarget
    data class Rejected(val reason: String) : NavigationTarget { override val uri: Uri = Uri.EMPTY }
}
```

Resolver memangkas input, menolak kontrol karakter, hanya menerima `http/https`, meningkatkan HTTP ke HTTPS, menggunakan `IDN.toASCII` untuk host, dan memakai `Uri.Builder` untuk query pencarian.

- [ ] **Step 3: Jalankan seluruh test resolver**

Run: `./gradlew testDebugUnitTest --tests '*NavigationControllerTest*'`  
Expected: PASS untuk semua kasus tabel.

- [ ] **Step 4: Commit**

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/navigation B-Android/app/src/test/java/com/nevus/quetta/navigation
git commit -m "feat: add safe navigation resolver"
```

## Task 4: Secure WebView dan Bridge Terbatas

**Files:**
- Create: `B-Android/app/src/main/java/com/nevus/quetta/web/SecureWebViewFactory.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/web/MediaBridgeMessage.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/web/SafeMediaBridge.kt`
- Delete after migration: `B-Android/app/src/main/java/com/nevus/quetta/BrowserBridge.kt`
- Test: `B-Android/app/src/test/java/com/nevus/quetta/web/SecureWebViewFactoryTest.kt`
- Test: `B-Android/app/src/test/java/com/nevus/quetta/web/SafeMediaBridgeTest.kt`

**Interfaces:**
- Consumes: `Context`, `WebView`, current top-level `Uri`, dan JSON bridge message.
- Produces: WebView hardened dan `BridgeDecision.Accepted(MediaCandidate)`/`Rejected(reason)` melalui `SafeMediaBridge.validate(origin, payload)`.

- [ ] **Step 1: Tulis test setting WebView yang gagal**

Periksa JavaScript/DOM storage sesuai kebutuhan, serta `allowFileAccess=false`, `allowContentAccess=false`, `mixedContentMode=MIXED_CONTENT_NEVER_ALLOW`, safe browsing aktif, third-party cookies false, dan debugging false pada release.

- [ ] **Step 2: Tulis test bridge yang gagal**

```kotlin
@Test fun `accepts only same origin https media messages`() {
    val ok = bridge.validate(Uri.parse("https://site.test/page"), """{"type":"media","url":"https://site.test/v.mp4"}""")
    assertTrue(ok is BridgeDecision.Accepted)
    assertTrue(bridge.validate(Uri.parse("https://site.test"), """{"type":"media","url":"https://evil.test/v.mp4"}""") is BridgeDecision.Rejected)
    assertTrue(bridge.validate(Uri.parse("https://site.test"), "x".repeat(16_385)) is BridgeDecision.Rejected)
}
```

Tambahkan test HTTP, `blob:`, tipe tidak dikenal, JSON rusak, userinfo host, dan default port.

- [ ] **Step 3: Implementasikan factory dan validator murni**

`SafeMediaBridge` tidak menerima callback bebas dari JavaScript. Payload maksimum 16 KiB, tipe harus `media`, skema harus HTTPS, dan normalized origin kandidat harus sama dengan top-level origin.

- [ ] **Step 4: Gunakan AndroidX WebKit message listener bila tersedia**

Gunakan allowed origin rules yang eksplisit untuk halaman aktif. Bila kemampuan document-start/message listener tidak tersedia, nonaktifkan deteksi media; jangan fallback ke `addJavascriptInterface` global.

- [ ] **Step 5: Jalankan test dan commit**

Run: `./gradlew testDebugUnitTest --tests '*web.*'`  
Expected: PASS.

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/web B-Android/app/src/test/java/com/nevus/quetta/web B-Android/app/build.gradle.kts
git rm B-Android/app/src/main/java/com/nevus/quetta/BrowserBridge.kt
git commit -m "security: isolate webview and media bridge"
```

## Task 5: Database, Repository, dan Migrasi Data

**Files:**
- Create: `B-Android/app/src/main/java/com/nevus/quetta/data/BookmarkEntity.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/data/HistoryEntity.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/data/TabEntity.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/data/BrowserDao.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/data/BrowserDatabase.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/data/BrowserRepository.kt`
- Test: `B-Android/app/src/test/java/com/nevus/quetta/data/BrowserRepositoryTest.kt`
- Test: `B-Android/app/src/androidTest/java/com/nevus/quetta/data/DatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: bookmark/history/tab models dan legacy SharedPreferences `nevus`/`nevus_vault_v2`.
- Produces: `Flow<List<BookmarkEntity>>`, `Flow<List<HistoryEntity>>`, `Flow<List<TabEntity>>`, serta suspend functions `upsertBookmark`, `deleteBookmark`, `recordVisit`, `replaceSession`.

- [ ] **Step 1: Tulis repository test yang gagal**

```kotlin
@Test fun `private visits are never persisted`() = runTest {
    repository.recordVisit("https://example.com", "Example", isPrivate = true, visitedAt = 10)
    assertEquals(emptyList<HistoryEntity>(), repository.history().first())
}
```

Tambahkan test bookmark duplicate URL, pencarian case-insensitive, urutan terbaru, dan replace session atomik.

- [ ] **Step 2: Definisikan entity dan DAO**

Gunakan primary key stabil (`Long` autogenerate untuk bookmark/history, `String tabId` untuk tab), unique index normalized URL bookmark, serta transaction untuk mengganti snapshot session.

- [ ] **Step 3: Implementasikan repository**

Normalisasi URL bookmark melalui `NavigationController`. Riwayat private langsung diabaikan. Semua operasi write berjalan pada `Dispatchers.IO`.

- [ ] **Step 4: Tulis migration test**

Buat database versi 1 berisi bookmark dan tab, jalankan migrasi ke versi 2, lalu verifikasi row count, URL, title, private flag, dan active tab. Test legacy SharedPreferences memastikan `lastUrl` dipindahkan hanya sekali dan prefs vault tidak disentuh.

- [ ] **Step 5: Jalankan test**

Run:

```bash
./gradlew testDebugUnitTest --tests '*data.*'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nevus.quetta.data.DatabaseMigrationTest
```

Expected: unit PASS; instrumentation PASS pada emulator/perangkat yang tersedia. Jika perangkat belum ada, status instrumentation `NOT RUN`, bukan PASS.

- [ ] **Step 6: Commit**

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/data B-Android/app/src/test/java/com/nevus/quetta/data B-Android/app/src/androidTest/java/com/nevus/quetta/data
git commit -m "feat: add persistent browser data and migrations"
```

## Task 6: Cleanup Aman dan Terukur

**Files:**
- Create: `B-Android/app/src/main/java/com/nevus/quetta/cleanup/CleanupManager.kt`
- Create: `B-Android/app/src/test/java/com/nevus/quetta/cleanup/CleanupManagerTest.kt`

**Interfaces:**
- Consumes: daftar root yang diizinkan, cutoff time, dan `dryRun`.
- Produces: `CleanupReport(scanned: Int, eligible: Int, deleted: Int, bytesReclaimed: Long, failures: List<String>)` melalui `CleanupManager.run(policy): CleanupReport`.

- [ ] **Step 1: Tulis test batas path yang gagal**

Gunakan temporary directory dengan `cache/`, `files/user/bookmarks.db`, `downloads/keep.mp4`, file tua, file baru, serta symlink yang menunjuk keluar root. Pastikan dry-run tidak menghapus, eksekusi hanya menghapus file tua di cache, dan symlink/outside file tidak disentuh.

- [ ] **Step 2: Implementasikan validasi canonical path**

```kotlin
private fun isInside(candidate: File, root: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val candidatePath = candidate.canonicalFile.toPath()
    return candidatePath.startsWith(rootPath) && candidatePath != rootPath
}
```

Jangan mengikuti symlink. Root yang diizinkan hanya `cacheDir`, `codeCacheDir`, dan subfolder temporary aplikasi yang eksplisit.

- [ ] **Step 3: Tambahkan kebijakan usia dan dry-run**

File baru dari cutoff tidak eligible. Failure per-file masuk laporan tanpa menghentikan seluruh cleanup. Folder Downloads dan `filesDir` tidak diterima sebagai root otomatis.

- [ ] **Step 4: Jalankan test dan commit**

Run: `./gradlew testDebugUnitTest --tests '*CleanupManagerTest*'`  
Expected: PASS termasuk symlink/path traversal.

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/cleanup B-Android/app/src/test/java/com/nevus/quetta/cleanup
git commit -m "feat: add bounded cache cleanup"
```

## Task 7: Tab Manager dan Pemulihan Process Death

**Files:**
- Create: `B-Android/app/src/main/java/com/nevus/quetta/tabs/BrowserTab.kt`
- Create: `B-Android/app/src/main/java/com/nevus/quetta/tabs/TabManager.kt`
- Create: `B-Android/app/src/test/java/com/nevus/quetta/tabs/TabManagerTest.kt`

**Interfaces:**
- Consumes: `BrowserRepository`, `SecureWebViewFactory`, dan `SavedStateHandle`.
- Produces: `StateFlow<TabState>` dan commands `newTab`, `selectTab`, `closeTab`, `updateNavigation`, `restoreSession`.

- [ ] **Step 1: Tulis state-machine test yang gagal**

Uji initial tab, maksimal 12 tab, pemilihan tab, penutupan active tab, penolakan close last tab (diganti home tab), private tab tidak dipersistenkan, dan process-death restore hanya menggunakan metadata.

```kotlin
@Test fun `process restoration never serializes webview`() = runTest {
    manager.newTab(Uri.parse("https://a.test"), private = false)
    manager.persistSession()
    assertEquals(listOf("https://a.test"), repository.tabs().first().map { it.url })
}
```

- [ ] **Step 2: Implementasikan immutable state**

```kotlin
data class BrowserTab(val id: String, val url: String, val title: String?, val isPrivate: Boolean)
data class TabState(val tabs: List<BrowserTab>, val activeTabId: String)
```

Object WebView disimpan di cache runtime terpisah dan tidak pernah masuk Room/SavedState.

- [ ] **Step 3: Tambahkan kebijakan memori**

Tab tidak aktif tertua dilepas WebView-nya ketika `onTrimMemory` mencapai level UI hidden/moderate; metadata tetap ada. Active tab tidak dilepas selama Activity berjalan.

- [ ] **Step 4: Jalankan test dan commit**

Run: `./gradlew testDebugUnitTest --tests '*TabManagerTest*'`  
Expected: PASS.

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/tabs B-Android/app/src/test/java/com/nevus/quetta/tabs
git commit -m "feat: add recoverable multi-tab state"
```

## Task 8: UI Browser, Bookmark, Riwayat, dan Mode Privat

**Files:**
- Modify: `B-Android/app/src/main/res/layout/activity_main.xml`
- Create: `B-Android/app/src/main/res/layout/dialog_tabs.xml`
- Create: `B-Android/app/src/main/res/layout/dialog_bookmarks.xml`
- Modify: `B-Android/app/src/main/res/values/strings.xml`
- Modify: `B-Android/app/src/main/java/com/nevus/quetta/MainActivity.kt`
- Create: `B-Android/app/src/androidTest/java/com/nevus/quetta/BrowserUiTest.kt`

**Interfaces:**
- Consumes: NavigationController, TabManager, BrowserRepository, SecureWebViewFactory, DataVault, CleanupManager.
- Produces: UI pengguna dan lifecycle wiring; tidak memperkenalkan persistence baru.

- [ ] **Step 1: Tulis instrumentation test UI**

Uji launch, address input, tab counter, new tab, switch tab, add bookmark, open bookmark, private indicator, back gesture behavior, dan rotasi. Gunakan server lokal MockWebServer; jangan bergantung pada internet publik.

- [ ] **Step 2: Susun ulang layout**

Tambahkan forward, tab counter, home, progress, WebView container, dan empty/error state. Gunakan resource string, minimum touch target 48dp, serta content description pada semua action.

- [ ] **Step 3: Kecilkan MainActivity**

Activity hanya:

1. inflate binding;
2. membuat coordinator/repository;
3. mengamati `TabState`;
4. merender WebView aktif;
5. meneruskan action UI;
6. menangani predictive back;
7. melepaskan WebView dan mengunci vault saat lifecycle relevan.

Hapus inline URL resolver dan inline konfigurasi WebView lama.

- [ ] **Step 4: Implementasikan menu data**

Menu menyediakan Tab, Bookmark, Riwayat, Mode Privat, Hapus Data Sesi, dan Pengaturan. Penghapusan bookmark/riwayat memerlukan konfirmasi; hapus sesi tidak menghapus unduhan, bookmark, vault, atau preferensi.

- [ ] **Step 5: Jalankan UI test**

Run:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nevus.quetta.BrowserUiTest
```

Expected: PASS pada API 35 dan ulangi pada API 36. Jika salah satu runtime tidak tersedia, gate tetap `NOT RUN`.

- [ ] **Step 6: Commit**

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/MainActivity.kt B-Android/app/src/main/res B-Android/app/src/androidTest/java/com/nevus/quetta/BrowserUiTest.kt
git commit -m "feat: deliver v09ab browser experience"
```

## Task 9: Application Lifecycle, Vault, dan Migrasi V0.8

**Files:**
- Create: `B-Android/app/src/main/java/com/nevus/quetta/NevusApplication.kt`
- Modify: `B-Android/app/src/main/java/com/nevus/quetta/DataVault.kt`
- Create: `B-Android/app/src/test/java/com/nevus/quetta/VaultContractTest.kt`
- Create: `B-Android/app/src/androidTest/java/com/nevus/quetta/UpgradePreservationTest.kt`

**Interfaces:**
- Consumes: legacy preferences dan database baru.
- Produces: initialization sekali per process dan hasil migrasi `MigrationReport`.

- [ ] **Step 1: Tulis contract test vault**

Uji nonce unik untuk ciphertext berulang, AAD berbeda per slot, lock menghapus DEK dari memori, corrupt ciphertext menghasilkan null tanpa crash, dan slot lama tetap terbaca.

- [ ] **Step 2: Tulis upgrade preservation test**

Seed legacy `lastUrl`, vault slot, bookmark test, preference, dan file unduhan dummy. Jalankan initializer V0.9 lalu pastikan semua data yang harus dipertahankan masih ada serta cache dummy eligible dibersihkan hanya setelah verification flag true.

- [ ] **Step 3: Implementasikan Application initializer**

Initializer membuka database, menjalankan migrasi idempotent, memuat ruleset, dan menjadwalkan cleanup setelah `ReleaseVerifier` mengonfirmasi build aktif. Jangan membuka vault otomatis; vault dibuka hanya pada aksi pengguna yang membutuhkannya.

- [ ] **Step 4: Perkuat DataVault**

Pertahankan format V2, tambahkan hasil error bertipe, sinkronisasi thread-safe, zeroization pada lock, dan jangan menuliskan plaintext/ciphertext ke log.

- [ ] **Step 5: Jalankan test dan commit**

Run:

```bash
./gradlew testDebugUnitTest --tests '*VaultContractTest*'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nevus.quetta.UpgradePreservationTest
```

Expected: seluruh test yang dapat dijalankan PASS; runtime yang tidak tersedia tetap `NOT RUN`.

```bash
git add B-Android/app/src/main/java/com/nevus/quetta/NevusApplication.kt B-Android/app/src/main/java/com/nevus/quetta/DataVault.kt B-Android/app/src/test/java/com/nevus/quetta/VaultContractTest.kt B-Android/app/src/androidTest/java/com/nevus/quetta/UpgradePreservationTest.kt
git commit -m "feat: preserve user data across v09ab upgrade"
```

## Task 10: Gate V0.9A+B dan Paket Kandidat

**Files:**
- Create: `B-Android/scripts/gate_v09ab.sh`
- Create: `B-Android/docs/V0.9AB-AUDIT.md`
- Modify: `STATUS.md`
- Create after successful build: `dist/nevusquetta-v0.9.0-ab-debug.apk.sha256`

**Interfaces:**
- Consumes: seluruh deliverable Task 1–9.
- Produces: status gate reproducible dan artefak debug yang dapat diuji.

- [ ] **Step 1: Buat gate script fail-fast**

Script memakai `set -euo pipefail`, memvalidasi root project eksplisit, lalu menjalankan:

```bash
./gradlew --no-daemon clean
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Script tidak menghapus folder di luar `B-Android/build`, `B-Android/app/build`, dan staging dist yang tervalidasi.

- [ ] **Step 2: Jalankan host gate**

Run: `bash scripts/gate_v09ab.sh`  
Expected: `clean`, unit, lint, assemble, APK existence, dan checksum PASS.

- [ ] **Step 3: Jalankan runtime API 35**

Instal APK, jalankan smoke test dan instrumentation: launch, navigasi lokal, tab, bookmark, private mode, rotasi, background/foreground, process death, cache cleanup, dan data preservation.

Expected: semua PASS; catat device/emulator ID, API level, APK SHA-256, waktu, dan logcat fatal scan.

- [ ] **Step 4: Jalankan runtime API 36**

Ulangi matriks API 35 dan tambahkan predictive back serta edge-to-edge behavior.

Expected: seluruh runtime PASS tanpa fatal exception atau ANR.

- [ ] **Step 5: Buat audit report berbasis bukti**

`V0.9AB-AUDIT.md` memuat tabel `PASS/FAIL/BLOCKED/NOT RUN`, command, exit code, artefak, checksum, device, temuan, dan target berikutnya. Jangan mengubah `NOT RUN` menjadi PASS.

- [ ] **Step 6: Stage artefak secara atomik**

Salin APK ke nama sementara di `dist/.staging/`, verifikasi SHA-256, lalu rename menjadi `dist/nevusquetta-v0.9.0-ab-debug.apk`. Setelah itu saja, hapus artefak dist lama yang sudah tercatat dan bukan rollback terakhir.

- [ ] **Step 7: Commit gate dan bukti teks**

```bash
git add B-Android/scripts/gate_v09ab.sh B-Android/docs/V0.9AB-AUDIT.md STATUS.md dist/nevusquetta-v0.9.0-ab-debug.apk.sha256
git commit -m "test: gate nevusquetta v09ab candidate"
```

Jangan commit APK bila kebijakan repository melarang binary; unggah sebagai release artifact dengan checksum yang dicatat di repository.

## Setelah Gate V0.9A+B

Jika seluruh gate `PASS`, buat rencana terpisah `docs/superpowers/plans/2026-09-19-nevusquetta-v09cd-implementation.md` untuk downloader/media/performance/release. Setelah V0.9C+D lulus, buat rencana terpisah Chromium M01–M04. Pemisahan ini mencegah perubahan subsistem besar bercampur dan memastikan setiap tahap menghasilkan perangkat lunak yang dapat diuji sendiri.
