/* NevusQuetta PWA — service worker.
 *
 * Strategi:
 *   - precache shell aplikasi saat install (offline-first)
 *   - navigasi  : network-first dengan fallback offline.html
 *   - aset statis: stale-while-revalidate
 *   - data       : hanya dari cache saat offline; TIDAK pernah menyimpan respons
 *                  yang mengandung Set-Cookie atau permintaan lintas-origin
 *
 * Aturan keselamatan:
 *   - cache HANYA respons GET dari origin sendiri dengan status 200/basic.
 *     Tanpa filter ini, service worker dapat menyimpan respons halaman login
 *     pengguna lain atau konten lintas-origin yang tidak boleh ada di disk.
 *   - precache bersifat gagal-keras: bila satu berkas inti gagal di-cache,
 *     instalasi DIBATALKAN agar tidak pernah ada shell yang setengah jadi.
 */

const VERSION = 'nq-pwa-v1.0.0';
const SHELL_CACHE = `${VERSION}-shell`;
const ASSET_CACHE = `${VERSION}-assets`;

const PRECACHE_REQUIRED = [
  './',
  './index.html',
  './offline.html',
  './styles.css',
  './app.js',
  './manifest.json',
];

const PRECACHE_OPTIONAL = [
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-512-maskable.png',
];

/* ---------------------------------------------------------------- helpers */

function isCacheableResponse(response, request) {
  if (!response) return false;
  if (request.method !== 'GET') return false;
  if (response.status !== 200) return false;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return false;

  // Jangan simpan respons yang menetapkan cookie (biasanya sesi terautentikasi).
  if (response.headers.has('set-cookie')) return false;

  // Hormati permintaan no-store.
  const cacheControl = (response.headers.get('cache-control') || '').toLowerCase();
  if (cacheControl.includes('no-store') || cacheControl.includes('private')) return false;

  return response.type === 'basic' || response.type === 'default';
}

async function putIfCacheable(cache, request, response) {
  if (!isCacheableResponse(response, request)) return response;
  try {
    await cache.put(request, response.clone());
  } catch (error) {
    // Kuota penuh atau permintaan tidak dapat disimpan: jangan sampai membuat
    // fetch gagal hanya karena cache tidak bisa ditulis.
    console.warn('[nq-sw] gagal menyimpan ke cache:', request.url, error);
  }
  return response;
}

/* ------------------------------------------------------------- life-cycle */

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(SHELL_CACHE);

      // Gagal-keras: berkas inti wajib berhasil di-cache.
      await cache.addAll(PRECACHE_REQUIRED);

      // Ikon masuk ke cache ASET, bukan shell: memisahkan "shell aplikasi" dari
      // "aset pendukung" membuat laporan isi cache di UI (lihat app.js
      // `refreshCacheInfo`) bermakna, dan membuat shell tetap kecil.
      const assetCache = await caches.open(ASSET_CACHE);

      // Opsional: kegagalan tidak membatalkan instalasi, tetapi dicatat.
      await Promise.all(
        PRECACHE_OPTIONAL.map(async (url) => {
          try {
            await assetCache.add(new Request(url, { cache: 'reload' }));
          } catch (error) {
            console.warn('[nq-sw] aset opsional tidak ter-cache:', url, error);
          }
        })
      );
    })()
  );
  // Jangan panggil skipWaiting() otomatis: halaman mengendalikan kapan update
  // diterapkan, sehingga tidak ada tab yang tiba-tiba memuat versi berbeda.
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((name) => !name.startsWith(VERSION))
          .map((name) => caches.delete(name))
      );
      await self.clients.claim();
    })()
  );
});

/* ------------------------------------------------------------------ fetch */

self.addEventListener('fetch', (event) => {
  const request = event.request;

  // Hanya tangani GET. Metode lain diteruskan langsung ke jaringan.
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // Lintas-origin tidak pernah di-cache; biarkan browser menanganinya.
  if (url.origin !== self.location.origin) return;

  // Navigasi: network-first, fallback ke shell lalu offline.html.
  if (request.mode === 'navigate') {
    event.respondWith(
      (async () => {
        try {
          const response = await fetch(request);
          const cache = await caches.open(SHELL_CACHE);
          await putIfCacheable(cache, request, response);
          return response;
        } catch (error) {
          const cached = await caches.match(request, { ignoreSearch: true });
          if (cached) return cached;
          const shell = await caches.match('./index.html');
          if (shell) return shell;
          const offline = await caches.match('./offline.html');
          if (offline) return offline;
          return new Response('Offline', {
            status: 503,
            statusText: 'Offline',
            headers: { 'Content-Type': 'text/plain; charset=utf-8' },
          });
        }
      })()
    );
    return;
  }

  // Aset statis: stale-while-revalidate.
  event.respondWith(
    (async () => {
      const cache = await caches.open(ASSET_CACHE);
      const cached = await cache.match(request);

      const network = fetch(request)
        .then((response) => putIfCacheable(cache, request, response))
        .catch(() => null);

      if (cached) {
        // Perbarui di latar belakang, sajikan versi cache segera.
        event.waitUntil(network);
        return cached;
      }

      const response = await network;
      if (response) return response;

      const shellFallback = await caches.match(request, { ignoreSearch: true });
      if (shellFallback) return shellFallback;

      return new Response('', { status: 504, statusText: 'Gateway Timeout' });
    })()
  );
});

/* --------------------------------------------------------------- messaging */

self.addEventListener('message', (event) => {
  const data = event.data || {};

  if (data.type === 'SKIP_WAITING') {
    self.skipWaiting();
    return;
  }

  if (data.type === 'CLEAR_CACHES') {
    event.waitUntil(
      (async () => {
        const names = await caches.keys();
        await Promise.all(names.map((name) => caches.delete(name)));
        event.source?.postMessage({ type: 'CACHES_CLEARED' });
      })()
    );
  }
});
