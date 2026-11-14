// Runs synchronously in <head>, before hydration and before any app request: reads the fragment, then removes it from history.
// A QR opened in a tab already showing the app only changes the fragment, with no reload, so hashchange runs the same scrub.
// The source contains no token value; it stays in this closure until the pairing session takes it once.
export const PAIRING_TOKEN_EVENT = 'cargo-pairing-token';

export const PAIRING_BOOTSTRAP = `(function(){
  var token = null;
  function scrub() {
    var hash = location.hash;
    if (hash.indexOf('pairing=') === -1) return false;
    var match = /^#pairing=([^&#]+)$/.exec(hash);
    token = match ? match[1] : '';
    history.replaceState(null, '', location.pathname + location.search);
    return true;
  }
  scrub();
  window.addEventListener('hashchange', function () {
    if (scrub()) window.dispatchEvent(new Event('${PAIRING_TOKEN_EVENT}'));
  });
  Object.defineProperty(window, '__cargoTakePairingToken', { value: function () { var taken = token; token = null; return taken; } });
})();`;
