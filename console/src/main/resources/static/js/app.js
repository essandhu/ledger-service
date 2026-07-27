// Browser-local time (M8b presentation contract): the server renders UTC ISO-8601 in both
// the datetime attribute and the text (the JS-off fallback); this converts the TEXT to the
// viewer's locale/zone, keeping datetime + title on the honest UTC value. htmx.onLoad runs
// it on initial load AND on every swapped-in fragment (the load-more rows).
(function () {
  var fmt = new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: 'short', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false, timeZoneName: 'short'
  });
  function localizeTimes(root) {
    (root.querySelectorAll ? root : document).querySelectorAll('time[datetime]').forEach(function (el) {
      var d = new Date(el.getAttribute('datetime'));
      if (!isNaN(d)) {
        el.title = el.getAttribute('datetime');
        el.textContent = fmt.format(d);
      }
    });
  }
  if (window.htmx) {
    htmx.onLoad(localizeTimes);
    // htmx 2's default responseHandling refuses to swap 4xx/5xx bodies — but the console
    // mirrors the API's status ON PURPOSE and the server already rendered the honest
    // problem fragment (ConsoleErrorAdvice). Opt error responses back into the swap, or
    // a failed load-more is a silent no-op in the browser.
    document.body.addEventListener('htmx:beforeSwap', function (e) {
      if (e.detail.xhr.status >= 400) {
        e.detail.shouldSwap = true;
        e.detail.isError = false;
      }
    });
  } else {
    localizeTimes(document);
  }
})();
