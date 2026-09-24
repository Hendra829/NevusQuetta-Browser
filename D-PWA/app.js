/* NevusQuetta PWA — logika aplikasi.
 *
 * Tanggung jawab:
 *   1. mendaftarkan service worker (kecuali konteks tidak aman)
 *   2. melaporkan status instalabilitas secara jujur (termasuk yang GAGAL)
 *   3. menangani prompt pemasangan (beforeinstallprompt)
 *   4. menampilkan isi cache & menyediakan pembersihan
 *   5. alur pembaruan: SW baru menunggu, pengguna yang memutuskan
 */

(function () {
  'use strict';

  var CACHE_VERSION_PREFIX = 'nq-pwa-v';

  var el = {
    netStatus: document.getElementById('netStatus'),
    installBtn: document.getElementById('installBtn'),
    installHint: document.getElementById('installHint'),
    installChecks: document.getElementById('installChecks'),
    cacheVersion: document.getElementById('cacheVersion'),
    cacheShellCount: document.getElementById('cacheShellCount'),
    cacheAssetCount: document.getElementById('cacheAssetCount'),
    clearCacheBtn: document.getElementById('clearCacheBtn'),
    swState: document.getElementById('swState'),
    year: document.getElementById('year'),
    offlineBtn: document.getElementById('offlineBtn'),
    retryBtn: document.getElementById('retryBtn'),
    onlineHint: document.getElementById('onlineHint')
  };

  if (el.year) el.year.textContent = String(new Date().getFullYear());

  /* ------------------------------------------------------------ utilitas */

  function setCheck(name, state, detail) {
    if (!el.installChecks) return;
    var node = el.installChecks.querySelector('[data-check="' + name + '"]');
    if (!node) return;
    node.setAttribute('data-state', state);
    if (detail) node.textContent = detail;
  }

  function setStatus(text, kind) {
    if (!el.netStatus) return;
    el.netStatus.textContent = text;
    el.netStatus.className = 'status' + (kind ? ' is-' + kind : '');
  }

  function isSecureContext() {
    return window.isSecureContext === true;
  }

  /* ----------------------------------------------------- status jaringan */

  function refreshNetworkStatus() {
    if (navigator.onLine) {
      setStatus('Online', 'online');
    } else {
      setStatus('Offline — memakai cache', 'offline');
    }
    if (el.onlineHint) {
      el.onlineHint.textContent = navigator.onLine
        ? 'Koneksi kembali terdeteksi. Muat ulang halaman ini.'
        : 'Masih offline. Coba lagi setelah jaringan tersedia.';
    }
    if (el.retryBtn) el.retryBtn.disabled = false;
  }

  window.addEventListener('online', refreshNetworkStatus);
  window.addEventListener('offline', refreshNetworkStatus);

  if (el.retryBtn) {
    el.retryBtn.addEventListener('click', function () {
      window.location.reload();
    });
  }

  /* ------------------------------------------------- pendaftaran SW */

  var registration = null;

  function updateSwState(text) {
    if (el.swState) el.swState.textContent = text;
  }

  function waitForInstalling(worker) {
    worker.addEventListener('statechange', function () {
      switch (worker.state) {
        case 'installed':
          updateSwState(navigator.serviceWorker.controller ? 'update menunggu' : 'terpasang');
          break;
        case 'activated':
          updateSwState('aktif');
          break;
        case 'redundant':
          updateSwState('usang');
          break;
      }
    });
  }

  function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) {
      setCheck('sw', 'fail', 'Service worker tidak didukung browser ini');
      updateSwState('tidak didukung');
      return;
    }
    if (!isSecureContext()) {
      setCheck('sw', 'fail', 'Butuh HTTPS atau localhost');
      updateSwState('dilewati (konteks tidak aman)');
      return;
    }

    navigator.serviceWorker
      .register('service-worker.js', { scope: './' })
      .then(function (reg) {
        registration = reg;
        setCheck('sw', 'pass');

        // Label status harus mencerminkan kondisi NYATA worker, bukan asumsi.
        // `reg.active` bisa null sesaat saat pendaftaran memicu pemeriksaan
        // pembaruan, jadi status final ditunggu lewat `navigator.serviceWorker.ready`
        // (menyelesaikan ketika sudah ada worker aktif) — sebelumnya label bisa
        // tertinggal di "mendaftar…" walau worker sudah mengendalikan halaman.
        if (reg.active) {
          updateSwState('aktif');
        } else if (reg.waiting) {
          updateSwState('update menunggu');
        } else {
          updateSwState('mendaftar…');
        }

        if ('ready' in navigator.serviceWorker) {
          navigator.serviceWorker.ready
            .then(function () {
              if (navigator.serviceWorker.controller) updateSwState('aktif');
            })
            .catch(function () { /* dibiarkan pada label terakhir yang diketahui */ });
        }

        if (reg.installing) waitForInstalling(reg.installing);
        if (reg.waiting) updateSwState('update menunggu');

        reg.addEventListener('updatefound', function () {
          if (reg.installing) waitForInstalling(reg.installing);
        });
      })
      .catch(function (error) {
        setCheck('sw', 'fail', 'Gagal mendaftar: ' + error.message);
        updateSwState('gagal');
      });
  }

  /* ------------------------------------------------- instalabilitas */

  var deferredPrompt = null;

  function checkInstallability() {
    if (isSecureContext()) {
      setCheck('secure', 'pass');
    } else {
      setCheck('secure', 'fail', 'Butuh HTTPS atau localhost');
    }

    fetch('manifest.json', { cache: 'no-store' })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (manifest) {
        setCheck('manifest', 'pass');

        var icons = manifest.icons || [];
        var sizes = icons.map(function (i) { return i.sizes; });
        var has192 = sizes.indexOf('192x192') !== -1;
        var has512 = sizes.indexOf('512x512') !== -1;
        if (has192 && has512) {
          setCheck('icons', 'pass');
        } else {
          setCheck('icons', 'fail', 'Ikon 192/512 tidak lengkap di manifest');
        }

        var display = manifest.display;
        if (display === 'standalone' || display === 'fullscreen' || display === 'minimal-ui') {
          setCheck('display', 'pass', 'display: ' + display);
        } else {
          setCheck('display', 'fail', 'display bukan mode aplikasi');
        }
      })
      .catch(function (error) {
        setCheck('manifest', 'fail', 'Manifest tidak terbaca: ' + error.message);
        setCheck('icons', 'fail');
        setCheck('display', 'fail');
      });
  }

  window.addEventListener('beforeinstallprompt', function (event) {
    event.preventDefault();
    deferredPrompt = event;
    if (el.installBtn) {
      el.installBtn.hidden = false;
      el.installBtn.disabled = false;
    }
    if (el.installHint) el.installHint.textContent = 'Aplikasi siap dipasang.';
  });

  window.addEventListener('appinstalled', function () {
    deferredPrompt = null;
    if (el.installBtn) el.installBtn.hidden = true;
    if (el.installHint) el.installHint.textContent = 'Aplikasi sudah dipasang.';
  });

  if (el.installBtn) {
    el.installBtn.addEventListener('click', function () {
      if (!deferredPrompt) {
        if (el.installHint) {
          el.installHint.textContent =
            'Prompt pemasangan belum tersedia. Gunakan menu browser: "Instal aplikasi" / "Tambahkan ke layar utama".';
        }
        return;
      }
      deferredPrompt.prompt();
      deferredPrompt.userChoice.then(function (choice) {
        if (el.installHint) {
          el.installHint.textContent =
            choice.outcome === 'accepted' ? 'Pemasangan diterima.' : 'Pemasangan dibatalkan.';
        }
        deferredPrompt = null;
        if (el.installBtn) el.installBtn.hidden = true;
      });
    });
  }

  /* --------------------------------------------------- inspeksi cache */

  function refreshCacheInfo() {
    if (!('caches' in window)) {
      if (el.cacheVersion) el.cacheVersion.textContent = 'tidak didukung';
      return;
    }

    caches
      .keys()
      .then(function (names) {
        var ours = names.filter(function (n) { return n.indexOf(CACHE_VERSION_PREFIX) === 0; });
        if (el.cacheVersion) {
          el.cacheVersion.textContent = ours.length ? ours.join(', ') : 'belum ada';
        }
        return Promise.all(
          ours.map(function (name) {
            return caches.open(name).then(function (cache) {
              return cache.keys().then(function (reqs) {
                return { name: name, count: reqs.length };
              });
            });
          })
        );
      })
      .then(function (entries) {
        var shell = 0;
        var asset = 0;
        entries.forEach(function (entry) {
          if (entry.name.indexOf('-shell') !== -1) shell += entry.count;
          if (entry.name.indexOf('-assets') !== -1) asset += entry.count;
        });
        if (el.cacheShellCount) el.cacheShellCount.textContent = String(shell);
        if (el.cacheAssetCount) el.cacheAssetCount.textContent = String(asset);
      })
      .catch(function () {
        if (el.cacheVersion) el.cacheVersion.textContent = 'gagal dibaca';
      });
  }

  if (el.clearCacheBtn) {
    el.clearCacheBtn.addEventListener('click', function () {
      if (!('caches' in window)) return;
      caches
        .keys()
        .then(function (names) {
          return Promise.all(names.map(function (n) { return caches.delete(n); }));
        })
        .then(function () {
          refreshCacheInfo();
          if (el.installHint) el.installHint.textContent = 'Seluruh cache dihapus.';
        });
    });
  }

  /* ------------------------------------------------- uji mode offline */

  if (el.offlineBtn) {
    el.offlineBtn.addEventListener('click', function () {
      if (!navigator.serviceWorker || !navigator.serviceWorker.controller) {
        if (el.installHint) {
          el.installHint.textContent = 'Service worker belum aktif — muat ulang halaman ini dulu.';
        }
        return;
      }
      var probe = 'offline-probe-' + Date.now() + '.html';
      fetch(probe, { cache: 'no-store' })
        .then(function () {
          if (el.installHint) el.installHint.textContent = 'Permintaan gagal seharusnya ditangani SW (tak terduga).';
        })
        .catch(function () {
          caches.match('offline.html').then(function (hit) {
            if (el.installHint) {
              el.installHint.textContent = hit
                ? 'Mode offline siap: offline.html tersedia di cache.'
                : 'offline.html TIDAK ada di cache — instalasi tidak lengkap.';
            }
          });
        });
    });
  }

  /* ------------------------------------------------------------- mulai */

  refreshNetworkStatus();
  checkInstallability();
  registerServiceWorker();
  refreshCacheInfo();
})();
