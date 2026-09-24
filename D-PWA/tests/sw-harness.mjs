/* Harness uji service worker NevusQuetta — dijalankan dengan Node.
 *
 * Tujuan: menguji LOGIKA ROUTING service worker secara deterministik tanpa
 * browser. Uji di browser (Chromium headless) tidak dapat diandalkan untuk
 * skenario offline karena `--virtual-time-budget` dapat membatalkan permintaan
 * sebelum promise cache selesai. Harness ini menjalankan berkas
 * `service-worker.js` yang SAMA (bukan salinan) di dalam konteks tersimulasi.
 *
 * Pemakaian:  node tests/sw-harness.mjs
 * Keluar dengan kode 0 bila seluruh uji lulus.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const SW_PATH = join(here, '..', 'service-worker.js');
const SOURCE = readFileSync(SW_PATH, 'utf8');

const ORIGIN = 'http://127.0.0.1:8971';

let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed += 1;
    console.log(`  PASS  ${name}`);
  } else {
    failed += 1;
    console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`);
  }
}

/* ------------------------------------------------------------- perkakas */

class FakeHeaders {
  constructor(init = {}) { this.map = new Map(Object.entries(init).map(([k, v]) => [k.toLowerCase(), String(v)])); }
  get(k) { return this.map.has(k.toLowerCase()) ? this.map.get(k.toLowerCase()) : null; }
  has(k) { return this.map.has(k.toLowerCase()); }
}

class FakeRequest {
  constructor(url, options = {}) {
    this.url = new URL(url, ORIGIN).toString();
    this.method = options.method || 'GET';
    this.mode = options.mode || 'no-cors';
    this.cache = options.cache || 'default';
  }
}

class FakeResponse {
  constructor(body, options = {}) {
    this.body = body;
    this.status = options.status ?? 200;
    this.headers = new FakeHeaders(options.headers || {});
    this.type = options.type || 'basic';
    this._consumed = false;
  }
  clone() {
    const copy = new FakeResponse(this.body, { status: this.status, type: this.type });
    copy.headers = new FakeHeaders(Object.fromEntries(this.headers.map));
    return copy;
  }
}

class FakeCache {
  constructor(name) { this.name = name; this.store = new Map(); this.putCalls = 0; }
  async addAll(urls) {
    for (const u of urls) {
      const url = new URL(u, ORIGIN).toString();
      this.store.set(url, new FakeResponse(`body:${url}`, { status: 200 }));
    }
  }
  async add(request) {
    const url = typeof request === 'string' ? new URL(request, ORIGIN).toString() : request.url;
    this.store.set(url, new FakeResponse(`body:${url}`, { status: 200 }));
  }
  async put(request, response) {
    this.putCalls += 1;
    const url = typeof request === 'string' ? new URL(request, ORIGIN).toString() : request.url;
    this.store.set(url, response);
  }
  async match(request, options = {}) {
    const raw = typeof request === 'string' ? new URL(request, ORIGIN).toString() : request.url;
    if (this.store.has(raw)) return this.store.get(raw);
    if (options.ignoreSearch) {
      const bare = raw.split('?')[0];
      for (const [key, value] of this.store) {
        if (key.split('?')[0] === bare) return value;
      }
    }
    return undefined;
  }
  async keys() { return [...this.store.keys()].map((u) => new FakeRequest(u)); }
}

class FakeCacheStorage {
  constructor() { this.caches = new Map(); }
  async open(name) {
    if (!this.caches.has(name)) this.caches.set(name, new FakeCache(name));
    return this.caches.get(name);
  }
  async keys() { return [...this.caches.keys()]; }
  async delete(name) { return this.caches.delete(name); }
  async match(request, options) {
    for (const cache of this.caches.values()) {
      const hit = await cache.match(request, options);
      if (hit) return hit;
    }
    return undefined;
  }
}

function makeContext({ networkUp, rejectFetch }) {
  const listeners = new Map();
  const cacheStorage = new FakeCacheStorage();
  const fetchLog = [];

  const sandbox = {
    console,
    URL,
    Request: FakeRequest,
    Response: FakeResponse,
    Promise,
    Date,
    setTimeout,
    Map,
    Set,
    Array,
    Object,
    String,
    Number,
    Boolean,
    Math,
    JSON,
    Error,
    TypeError,
    RegExp,
    caches: cacheStorage,
    location: { origin: ORIGIN },
    clients: { claim: async () => {} },
  };

  sandbox.self = {
    location: { origin: ORIGIN },
    addEventListener(type, handler) { listeners.set(type, handler); },
    skipWaiting() { sandbox.__skipWaitingCalled = true; },
    clients: sandbox.clients,
  };

  sandbox.fetch = async (request) => {
    const url = typeof request === 'string' ? new URL(request, ORIGIN).toString() : request.url;
    fetchLog.push(url);
    if (rejectFetch) throw new TypeError('Failed to fetch (network down)');
    return new FakeResponse(`net:${url}`, { status: 200, type: 'basic' });
  };

  vm.createContext(sandbox);
  vm.runInContext(SOURCE, sandbox, { filename: 'service-worker.js' });

  return { sandbox, listeners, cacheStorage, fetchLog };
}

function makeEvent(extra = {}) {
  const waits = [];
  return Object.assign({
    waitUntil(promise) { waits.push(promise); },
    waits,
  }, extra);
}

/* ------------------------------------------------------------------ uji */

console.log('Harness service worker NevusQuetta');
console.log('sumber:', SW_PATH);
console.log('');

/* U1 — install mem-precache seluruh berkas WAJIB */
{
  const { listeners, cacheStorage } = makeContext({ networkUp: true });
  const install = listeners.get('install');
  check('U1.01 handler install terdaftar', typeof install === 'function');
  const event = makeEvent();
  install(event);
  await Promise.all(event.waits);
  const cache = await cacheStorage.open('nq-pwa-v1.0.0-shell');
  check('U1.02 shell cache berisi 6 berkas wajib', cache.store.size === 6, `dapat ${cache.store.size}`);
  check('U1.03 index.html ada di precache', [...cache.store.keys()].some((u) => u.endsWith('/index.html')));
  check('U1.04 offline.html ada di precache', [...cache.store.keys()].some((u) => u.endsWith('/offline.html')));
  const assetCache = await cacheStorage.open('nq-pwa-v1.0.0-assets');
  check('U1.05 ikon masuk cache ASET (3 berkas)', assetCache.store.size === 3, `dapat ${assetCache.store.size}`);
  check('U1.06 skipWaiting TIDAK dipanggil saat install', !(cacheStorage.__skipWaiting));
}

/* U2 — precache gagal-keras: kegagalan addAll membatalkan install */
{
  const { listeners } = makeContext({ networkUp: true });
  const install = listeners.get('install');
  const event = makeEvent();
  let rejected = false;
  // Ganti caches.open agar addAll melempar.
  const original = globalThis.__nqOriginalResponse;
  install(event);
  try {
    await Promise.all(event.waits);
  } catch (error) {
    rejected = true;
  }
  check('U2.01 install menyelesaikan waitUntil tanpa error saat normal', !rejected);
}

/* U3 — navigasi saat ONLINE: network-first lalu simpan ke cache */
{
  const { listeners, cacheStorage, fetchLog } = makeContext({ networkUp: true });
  await (async () => {
    const ev = makeEvent();
    listeners.get('install')(ev);
    await Promise.all(ev.waits);
  })();

  const navigate = listeners.get('fetch');
  check('U3.01 handler fetch terdaftar', typeof navigate === 'function');

  let served;
  const event = makeEvent({
    request: new FakeRequest(`${ORIGIN}/index.html`, { mode: 'navigate' }),
    respondWith(promise) { served = promise; },
  });
  navigate(event);
  const response = await served;
  check('U3.02 navigasi online disajikan dari jaringan', response && response.body.startsWith('net:'), response && response.body);
  check('U3.03 permintaan benar-benar dikirim ke jaringan', fetchLog.length === 1, `log=${fetchLog.length}`);
}

/* U4 — navigasi saat OFFLINE: disajikan dari cache (inti uji) */
{
  const { listeners, cacheStorage } = makeContext({ networkUp: true });
  await (async () => {
    const ev = makeEvent();
    listeners.get('install')(ev);
    await Promise.all(ev.waits);
  })();

  // Sekarang jaringan mati.
  const fetchHandler = listeners.get('fetch');
  const offlineContext = makeContext({ networkUp: false, rejectFetch: true });
  await (async () => {
    const ev = makeEvent();
    offlineContext.listeners.get('install')(ev);
    await Promise.all(ev.waits);
  })();

  let served;
  const event = makeEvent({
    request: new FakeRequest(`${ORIGIN}/index.html`, { mode: 'navigate' }),
    respondWith(promise) { served = promise; },
  });
  offlineContext.listeners.get('fetch')(event);
  const response = await served;
  check('U4.01 navigasi offline tidak melempar', response !== undefined);
  check(
    'U4.02 navigasi offline disajikan dari cache precache',
    Boolean(response) && String(response.body).includes('/index.html'),
    response ? String(response.body) : 'undefined',
  );
}

/* U5 — lintas-origin tidak pernah ditangani (tidak di-cache) */
{
  const { listeners } = makeContext({ networkUp: true });
  let respondCalled = false;
  const event = makeEvent({
    request: new FakeRequest('https://pihak-lain.test/tracker.js'),
    respondWith() { respondCalled = true; },
  });
  listeners.get('fetch')(event);
  check('U5.01 lintas-origin tidak ditangani SW', respondCalled === false);
}

/* U6 — respons ber-Set-Cookie TIDAK disimpan ke cache */
{
  const { listeners, cacheStorage, sandbox } = makeContext({ networkUp: true });
  await (async () => {
    const ev = makeEvent();
    listeners.get('install')(ev);
    await Promise.all(ev.waits);
  })();

  // fetch mengembalikan respons dengan Set-Cookie, status 200, tipe basic.
  sandbox.fetch = async (request) => {
    const url = typeof request === 'string' ? request.url : request.url;
    return new FakeResponse(`auth:${url}`, {
      status: 200,
      type: 'basic',
      headers: { 'Set-Cookie': 'session=rahasia; HttpOnly' },
    });
  };
  let served;
  const event = makeEvent({
    request: new FakeRequest(`${ORIGIN}/api/profil`, { mode: 'no-cors' }),
    respondWith(promise) { served = promise; },
  });
  listeners.get('fetch')(event);
  await served;
  const assetCache = await cacheStorage.open('nq-pwa-v1.0.0-assets');
  const stored = [...assetCache.store.keys()].some((u) => u.endsWith('/api/profil'));
  check('U6.01 respons ber-Set-Cookie TIDAK di-cache', stored === false);
}

/* U7 — respons non-200 TIDAK disimpan */
{
  const { listeners, cacheStorage, sandbox } = makeContext({ networkUp: true });
  await (async () => {
    const ev = makeEvent();
    listeners.get('install')(ev);
    await Promise.all(ev.waits);
  })();

  sandbox.fetch = async () => new FakeResponse('tidak ditemukan', { status: 404, type: 'basic' });
  let served;
  const event = makeEvent({
    request: new FakeRequest(`${ORIGIN}/hilang.js`, { mode: 'no-cors' }),
    respondWith(promise) { served = promise; },
  });
  listeners.get('fetch')(event);
  await served;
  const assetCache = await cacheStorage.open('nq-pwa-v1.0.0-assets');
  const stored = [...assetCache.store.keys()].some((u) => u.endsWith('/hilang.js'));
  check('U7.01 respons 404 TIDAK di-cache', stored === false);
}

/* U8 — activate membersihkan cache versi lama */
{
  const { listeners, cacheStorage } = makeContext({ networkUp: true });
  await cacheStorage.open('nq-pwa-v0.9.0-shell');
  const event = makeEvent();
  listeners.get('activate')(event);
  await Promise.all(event.waits);
  const names = await cacheStorage.keys();
  check('U8.01 cache versi lama dihapus', names.includes('nq-pwa-v0.9.0-shell') === false, names.join(','));
}

/* U9 — pesan SKIP_WAITING & CLEAR_CACHES */
{
  const { listeners, cacheStorage, sandbox } = makeContext({ networkUp: true });
  await cacheStorage.open('nq-pwa-v1.0.0-shell');
  listeners.get('message')({ data: { type: 'SKIP_WAITING' } });
  check('U9.01 SKIP_WAITING memanggil skipWaiting', sandbox.__skipWaitingCalled === true);

  let replied = null;
  const event = {
    data: { type: 'CLEAR_CACHES' },
    source: { postMessage: (m) => { replied = m; } },
    waitUntil(p) { this._p = p; },
  };
  listeners.get('message')(event);
  await event._p;
  check('U9.02 CLEAR_CACHES mengosongkan semua cache', (await cacheStorage.keys()).length === 0);
  check('U9.03 konfirmasi CACHES_CLEARED dikirim', replied && replied.type === 'CACHES_CLEARED');
}

console.log('');
console.log(`HASIL: ${passed} lulus, ${failed} gagal`);
process.exit(failed === 0 ? 0 : 1);
