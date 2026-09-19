(() => {
  'use strict';
  if (window.__nevusQuettaInstalled) return;
  window.__nevusQuettaInstalled = true;

  const emit = media => {
    const url = media && (media.currentSrc || media.src);
    if (typeof url !== 'string' || !url.startsWith('https://')) return;
    try {
      window.NevusBridge?.postMessage(JSON.stringify({ type: 'media', url }));
    } catch (_) {
      // Bridge may be unavailable on unsupported WebView/origin; fail closed.
    }
  };

  document.addEventListener('play', event => emit(event.target), true);
  document.addEventListener('loadedmetadata', event => emit(event.target), true);
})();
