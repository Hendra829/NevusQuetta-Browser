(() => {
  'use strict';
  if (window.__nevusQuettaInstalled) return;
  window.__nevusQuettaInstalled = true;

  const seen = new Set();
  const mediaPattern = /\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mp3|m4a|aac|flac|ogg|opus|wav|ts|m4s)(?:$|[?#])/i;

  const emitUrl = value => {
    if (typeof value !== 'string' || !value.startsWith('https://')) return;
    if (seen.has(value)) return;
    seen.add(value);
    if (seen.size > 256) seen.delete(seen.values().next().value);
    try {
      window.NevusBridge?.postMessage(JSON.stringify({ type: 'media', url: value }));
    } catch (_) {
      // Unsupported bridge/profile: discovery fails closed.
    }
  };

  const emitMedia = media => {
    if (!media) return;
    emitUrl(media.currentSrc || media.src);
    if (media.querySelectorAll) {
      media.querySelectorAll('source[src]').forEach(source => emitUrl(source.src));
    }
  };

  document.addEventListener('play', event => emitMedia(event.target), true);
  document.addEventListener('loadedmetadata', event => emitMedia(event.target), true);

  try {
    new PerformanceObserver(list => {
      list.getEntries().forEach(entry => {
        if (mediaPattern.test(entry.name)) emitUrl(entry.name);
      });
    }).observe({ type: 'resource', buffered: true });
  } catch (_) {}

  const originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function(input, init) {
      try {
        const url = typeof input === 'string' ? input : input?.url;
        if (mediaPattern.test(url || '')) emitUrl(new URL(url, location.href).href);
      } catch (_) {}
      return originalFetch.apply(this, arguments);
    };
  }

  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    try {
      const absolute = new URL(String(url), location.href).href;
      if (mediaPattern.test(absolute)) emitUrl(absolute);
    } catch (_) {}
    return originalOpen.apply(this, arguments);
  };
})();
