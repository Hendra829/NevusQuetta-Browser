# Hardening — perbaikan gate unit test V0.9C+D (2026-09-25)

## Gejala

`Android V0.9CD Gate` (job `host-gate`) gagal pada langkah `Unit tests`
(`./gradlew --no-daemon testDebugUnitTest`):

```
BrowserRuntimeViewModelTest > progressIsDebouncedAndLateProgressCannotRegressFinishedPage FAILED
    java.lang.AssertionError at BrowserRuntimeViewModelTest.kt:56
37 tests completed, 1 failed
```

Titik gagal sesungguhnya adalah assertion `assertEquals(0, viewModel.uiState.value.progress)`
(baris 37 berkas), yang dilaporkan CI pada baris 56 (deklarasi method).

## Akar penyebab

`BrowserRuntimeViewModel` menerima event progress melalui:

```kotlin
private val progressEvents = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

`MutableSharedFlow()` tanpa `replay` tidak menyimpan nilai apa pun apabila belum ada kolektor
yang aktif. Kolektor `.distinctUntilChanged().debounce(progressDebounceMillis).collect { }`
diluncurkan di `init` pada `viewModelScope` yang memakai `Dispatchers.Main.immediate`.

Pada unit test, `MainDispatcherRule` memasang `StandardTestDispatcher` sebagai Main, sehingga
kolektor baru mulai berjalan setelah scheduler digeser. Event `onProgressChanged(10|45)` yang
dikirim sebelum itu hanya menempati `extraBufferCapacity` bernilai 1, sehingga nilai **10 hilang**
dan **45 tetap tertahan di buffer** tanpa pernah memulai jendela debounce. Akibatnya:

- `assertEquals(0, progress)` pada `advanceTimeBy(24)` menangkap 45 yang sudah terlanjur
  diterapkan → gagal (`expected:<45> but was:<0>`).

Selain itu, semantik lama membuat sinyal penutup (`onProgressChanged(100)`) **dapat hilang**
sehingga `isLoading` berpotensi tertinggal `true` pada perangkat nyata.

## Perbaikan

Progress tidak lagi dilewatkan buffer lossy, melainkan didebounce dengan *cancel-and-delay* yang
dinamai berdasarkan momen callback:

```kotlin
private var progressDebounceJob: Job? = null

fun onProgressChanged(progress: Int) {
    val next = progress.coerceIn(0, 100)
    progressDebounceJob?.cancel()
    progressDebounceJob = viewModelScope.launch {
        delay(progressDebounceMillis)
        mutableState.update { current ->
            if (!current.isLoading && next < 100) {
                current
            } else {
                current.copy(progress = next, isLoading = next in 0..99)
            }
        }
    }
}
```

Sifat yang dipertahankan/diperbaiki:

1. Jendela debounce mulai tepat saat callback WebView datang, bukan menunggu kolektor aktif.
2. Setiap event progress dapat dibatalkan tepat waktu (tidak ada nilai lama yang menang).
3. Nilai 100 selalu diterapkan (`100 !in 0..99` → `isLoading = false`), termasuk pada navigasi
   pertama saat `isLoading` masih `false`.
4. Callback progress yang sangat sering tetap diredam (`debounce`), hanya nilai terakhir yang dipakai.

## Verifikasi

Reproduksi hermetik di luar Gradle (Kotlin 2.0.21, kotlinx-coroutines 1.10.2 — versi yang sama
dengan `app/build.gradle.kts`, JUnit 4.13.2) menjalankan test asli terhadap dua varian
`BrowserRuntimeViewModel`:

| Varian | Hasil |
|---|---|
| Sebelum perbaikan | `Tests run: 2, Failures: 1` → `AssertionError: expected:<45> but was:<0>` |
| Sesudah perbaikan | `OK (2 tests)` |

Dua test regresi ditambahkan:

- `progressOneHundredClearsLoadingAfterDebounce`
- `rapidProgressBurstAppliesOnlyLastValueAfterDebounce`

## Catatan

`android-v09cd-runtime.yml` (job `runtime-api-35` / `runtime-api-36`) terpisah dari gate ini:
keduanya memakai emulator Android sungguhan lewat `B-Android/scripts/runtime_gate.sh`. Karena
workflow tersebut tidak mengunggah artifact runtime, penyebabnya hanya terbaca dari log job.
