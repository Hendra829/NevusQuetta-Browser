(() => {
  'use strict';
  if (window.__nevusQuettaInstalled) return;
  window.__nevusQuettaInstalled = true;
  document.addEventListener('play', event => {
    const media = event.target;
    const url = media && (media.currentSrc || media.src);
    if (typeof url === 'string' && url.startsWith('https://')) {
      window.NevusBridge?.mediaFound(url);
    }
  }, true);
})();
