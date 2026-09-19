(() => {
  'use strict';
  if (window.__nevusQuettaInstalled) return;
  window.__nevusQuettaInstalled = true;

  const seen = new Set();
  const MEDIA_RE = /\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mp3|m4a|aac|flac|ogg|opus|wav|ts|m4s)(?:$|[?#])/i;

  const hintFor = (url, type = '') => {
    const lowerUrl = String(url || '').toLowerCase();
    const lowerType = String(type || '').toLowerCase();
    if (lowerUrl.includes('.m3u8') || lowerType.includes('mpegurl')) return 'hls';
    if (lowerUrl.includes('.mpd') || lowerType.includes('dash')) return 'dash';
    if (lowerType.startsWith('audio/') || /\.(?:mp3|m4a|aac|flac|ogg|opus|wav)(?:$|[?#])/i.test(lowerUrl)) {
      return 'audio';
    }
    if (lowerType.startsWith('video/') || /\.(?:mp4|m4v|webm|mkv|ts|m4s)(?:$|[?#])/i.test(lowerUrl)) {
      return 'video';
    }
    return 'unknown';
  };

  const emitUrl = (value, type = '') => {
    if (typeof value !== 'string' || !value.startsWith('https://')) return;
    const hint = hintFor(value, type);
    if (hint === 'unknown' && !MEDIA_RE.test(value)) return;

    const key = hint + '|' + value;
    if (seen.has(key)) return;
    seen.add(key);
    if (seen.size > 512) seen.delete(seen.values().next().value);

    try {
      window.NevusBridge?.postMessage(JSON.stringify({
        type: 'media',
        url: value,
        hint
      }));
    } catch (_) {
      // Bridge/profile unsupported: discovery fails closed.
    }
  };

  const emitMedia = media => {
    if (!media) return;
    emitUrl(media.currentSrc || media.src, media.getAttribute?.('type') || '');
    if (media.querySelectorAll) {
      media.querySelectorAll('source[src]').forEach(source => {
        emitUrl(source.src, source.type || source.getAttribute('type') || '');
      });
    }
  };

  const scanNode = node => {
    if (!(node instanceof Element)) return;
    if (node.matches?.('video,audio,source[src]')) {
      if (node.matches('source[src]')) emitUrl(node.src, node.type || '');
      else emitMedia(node);
    }
    node.querySelectorAll?.('video,audio,source[src]').forEach(child => {
      if (child.matches('source[src]')) emitUrl(child.src, child.type || '');
      else emitMedia(child);
    });
  };

  document.addEventListener('play', event => emitMedia(event.target), true);
  document.addEventListener('loadedmetadata', event => emitMedia(event.target), true);

  try {
    new MutationObserver(records => {
      for (const record of records) {
        record.addedNodes.forEach(scanNode);
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (_) {}

  try {
    new PerformanceObserver(list => {
      list.getEntries().forEach(entry => {
        if (MEDIA_RE.test(entry.name)) emitUrl(entry.name);
      });
    }).observe({ type: 'resource', buffered: true });
  } catch (_) {}

  try {
    document.querySelectorAll('video,audio,source[src]').forEach(scanNode);
  } catch (_) {}
})();
