(function () {
  // Page side of the NATIVE sidebar (NativeSidebar.java draws it).
  // This script only feeds Android with data and runs the page's own actions:
  //  - the chat list, read with Gemini's own list request (batchexecute MaZiqc, the
  //    same call the page makes at load), with paging; refreshed on demand and when
  //    the page's own (hidden) list changes;
  //  - the current path (active chat), the page's sidebar entries and account name;
  //  - where a sideways swipe belongs to the page (text fields, sideways scrollers),
  //    sent ahead of time so Android decides swipes without asking the page;
  //  - actions: open a chat / entry with the page's router (no reload), new chat,
  //    settings, and the fallback that opens the page's own sidebar.
  // Nothing here runs per frame and nothing blocks a touch.
  if (window.__geSB) return;
  var B = window.GESidebar;
  if (!B) return;

  // Hide what cannot work in the app (Get App, New notebook, mic).
  try {
    if (!document.getElementById('ge-hide-css')) {
      var hs = document.createElement('style');
      hs.id = 'ge-hide-css';
      hs.textContent = '.mavatar-signed-in-get-app-button-container, gem-button.mavatar-get-app-button,'
        + ' gem-nav-list-item[data-test-id="new-notebook-button"],'
        + ' speech-dictation-mic-button, .gem-mic-button-wrapper { display: none !important; }';
      (document.head || document.documentElement).appendChild(hs);
    }
  } catch (e) {}

  function nativeOn() { try { return !!B.nativeEnabled(); } catch (e) { return false; } }
  function sidenav() { return document.querySelector('mat-sidenav.mat-drawer') || document.querySelector('mat-sidenav'); }

  // ---------- chat list ----------
  var PAYLOAD_TAIL = [null, null, 1, null, null, null, null, null, null, 1];
  var fails = 0, retryTimer = null;
  var known = [], fetching = false, again = 0, lastFull = 0, lastAny = 0, reqid = 100000 + Math.floor(Math.random() * 800000);
  function wiz() { return window.WIZ_global_data || {}; }
  function rpcList(tok) {
    var w = wiz();
    if (!w.SNlM0e || !w.cfb2h) return Promise.reject(new Error('no page tokens'));
    var freq = JSON.stringify([[['MaZiqc', JSON.stringify([29, tok || null, PAYLOAD_TAIL]), null, 'generic']]]);
    reqid += 100000;
    var u = '/_/BardChatUi/data/batchexecute?rpcids=MaZiqc&source-path=' + encodeURIComponent(location.pathname)
      + '&bl=' + encodeURIComponent(w.cfb2h) + '&f.sid=' + encodeURIComponent(w.FdrFJe || '')
      + '&hl=' + encodeURIComponent(w.TuX5cc || 'en') + '&_reqid=' + reqid + '&rt=c';
    return fetch(u, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'X-Same-Domain': '1' },
      body: 'f.req=' + encodeURIComponent(freq) + '&at=' + encodeURIComponent(w.SNlM0e) + '&'
    }).then(function (r) {
      if (!r.ok) throw new Error('http ' + r.status);
      return r.text();
    }).then(function (txt) {
      var lines = txt.split('\n');
      for (var i = 0; i < lines.length; i++) {
        if (lines[i].indexOf('"MaZiqc"') < 0) continue;
        var a = JSON.parse(lines[i]);
        for (var j = 0; j < a.length; j++) {
          if (a[j] && a[j][0] === 'wrb.fr' && a[j][1] === 'MaZiqc' && typeof a[j][2] === 'string') {
            var inner = JSON.parse(a[j][2]);
            var items = (inner && inner[2]) || [], out = [];
            for (var k = 0; k < items.length; k++) {
              var it = items[k];
              if (!it || typeof it[0] !== 'string' || it[0].indexOf('c_') !== 0) continue;
              out.push({ id: it[0], t: typeof it[1] === 'string' ? it[1] : '', p: it[2] === true });
            }
            return { items: out, tok: (inner && typeof inner[1] === 'string') ? inner[1] : null };
          }
        }
      }
      throw new Error('no MaZiqc in reply');
    });
  }
  // partial = only the newest page: Android keeps its own (cached) tail below it
  function send(list, partial) {
    if (!partial) known = list;
    else {
      var ids = {}; for (var i = 0; i < list.length; i++) ids[list[i].id] = 1;
      var rest = []; for (var j = 0; j < known.length; j++) if (!ids[known[j].id]) rest.push(known[j]);
      known = list.concat(rest);
    }
    try { B.onList(JSON.stringify({ chats: list, partial: !!partial })); } catch (e) {}
  }
  // full: every page (cap 12 pages ~ 350 chats); otherwise page 1 merged into the known tail.
  function refresh(full) {
    if (fetching) { again = full ? 2 : Math.max(again, 1); return; }
    fetching = true; lastAny = Date.now();
    if (full || !known.length) lastFull = Date.now();
    var all = [], pages = 0, isFull = full || !known.length;
    function step(tok) {
      return rpcList(tok).then(function (r) {
        pages++;
        all = all.concat(r.items);
        // page 1 at once; the older chats stay below it until the full list arrives
        if (pages === 1 && (!isFull || r.tok)) send(all, true);
        if (isFull && r.tok && pages < 12) return step(r.tok);
        if (isFull) send(all);
      });
    }
    step(null).then(function () { fails = 0; }, function (e) {
      try { B.onListError(String(e && e.message || e)); } catch (x) {}
      // the page may not be ready yet (no tokens) or offline: try again, slower each time
      var wait = [2000, 5000, 15000, 30000, 60000][Math.min(fails, 4)];
      fails++;
      if (retryTimer) clearTimeout(retryTimer);
      retryTimer = setTimeout(function () { retryTimer = null; refresh(true); }, wait);
    }).then(function () {
      fetching = false;
      if (again) { var f = again === 2; again = 0; setTimeout(function () { refresh(f); }, 300); }
    });
  }

  // The page's own (hidden) sidebar list changes when a chat is created, renamed,
  // deleted or pinned in the page: refresh then. Watches only that list, child changes only.
  var listObs = null, listObsTarget = null, listTimer = null;
  function watchList() {
    try {
      var l = document.querySelector('conversations-list');
      if (!l || l === listObsTarget) return;
      if (listObs) listObs.disconnect();
      listObs = new MutationObserver(function () {
        if (listTimer) clearTimeout(listTimer);
        listTimer = setTimeout(function () { listTimer = null; refresh(false); }, 700);
      });
      listObs.observe(l, { childList: true, subtree: true });
      listObsTarget = l;
    } catch (e) {}
  }

  // ---------- path (active chat) ----------
  var lastPath = null, titleTimers = [];
  function reportPath() {
    var p = location.pathname;
    if (p === lastPath) return;
    lastPath = p;
    try { B.onUrl(p); } catch (e) {}
    watchList();
    // a chat that is not in the list yet (just created): fetch it, and again for its title
    var m = p.match(/^\/app\/([A-Za-z0-9_-]+)/);
    if (m) {
      var id = 'c_' + m[1], have = null;
      for (var i = 0; i < known.length; i++) if (known[i].id === id) { have = known[i]; break; }
      if (!have || !have.t) {
        for (var k = 0; k < titleTimers.length; k++) clearTimeout(titleTimers[k]);
        titleTimers = [setTimeout(function () { refresh(false); }, 1500), setTimeout(function () { refresh(false); }, 8000), setTimeout(function () { refresh(false); }, 25000)];
      }
    }
  }
  try {
    ['pushState', 'replaceState'].forEach(function (k) {
      var o = history[k];
      history[k] = function () { var r = o.apply(this, arguments); setTimeout(reportPath, 0); return r; };
    });
    window.addEventListener('popstate', function () { setTimeout(reportPath, 0); });
  } catch (e) {}

  // ---------- the page's entries and account ----------
  function reportNav() {
    try {
      var d = sidenav(); if (!d) return;
      var nav = [], seen = {};
      var items = d.querySelectorAll('gem-nav-list-item');
      for (var i = 0; i < items.length; i++) {
        var it = items[i], tid = it.getAttribute('data-test-id') || '';
        if (tid === 'conversation' || it.hasAttribute('data-conversation-id')) continue;
        var a = it.querySelector('a[href]');
        if (!a) continue;
        var href = a.getAttribute('href'), label = a.getAttribute('aria-label') || (a.textContent || '').trim();
        if (!href || seen[href] || !label || href.indexOf('http') === 0) continue;
        try { if (getComputedStyle(it).display === 'none') continue; } catch (x) {}
        seen[href] = 1;
        nav.push({ label: label, href: href, key: tid });
      }
      var acc = d.querySelector('a[aria-label^="Google Account"]'), name = '', plan = '';
      if (acc) { var mm = (acc.getAttribute('aria-label') || '').match(/^Google Account:\s*(.*?)\s*(\(|$)/); if (mm) name = mm[1]; }
      try {
        var pl = d.querySelector('.pillbox-text, .plan-name, [data-test-id*="plan"], .subscription-tier');
        if (pl) plan = (pl.textContent || '').trim();
      } catch (x) {}
      if (nav.length || name) B.onNav(JSON.stringify({ nav: nav, name: name, plan: plan }));
    } catch (e) {}
  }

  // ---------- where a sideways swipe belongs to the page ----------
  var TEXT_SEL = 'input:not([type=hidden]),textarea,[contenteditable="true"],[contenteditable=""],rich-textarea';
  var scrolled = [];   // sideways scrollers moved away from their left edge
  var regionTimer = null, lastRegions = '';
  function rectOf(el) {
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || r.bottom < 0 || r.top > innerHeight) return null;
    return [Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom)];
  }
  function overlayUp() {
    var els = document.querySelectorAll('.cdk-overlay-pane');
    for (var i = 0; i < els.length; i++) { var r = els[i].getBoundingClientRect(); if (r.width > 0 && r.height > 0) return true; }
    return false;
  }
  function computeRegions() {
    regionTimer = null;
    try {
      var all = [], right = [];
      if (overlayUp()) all.push(0, 0, 100000, 100000);   // a page menu or dialog is up: no sidebar swipes
      var tf = document.querySelectorAll(TEXT_SEL);
      for (var i = 0; i < tf.length; i++) { var r = rectOf(tf[i]); if (r) all.push(r[0], r[1], r[2], r[3]); }
      var keep = [];
      for (var j = 0; j < scrolled.length; j++) {
        var el = scrolled[j];
        if (!el.isConnected || el.scrollLeft <= 1) continue;
        keep.push(el);
        var q = rectOf(el); if (q) right.push(q[0], q[1], q[2], q[3]);
      }
      scrolled = keep;
      var s = JSON.stringify({ dpr: window.devicePixelRatio || 1, all: all, right: right });
      if (s !== lastRegions) { lastRegions = s; B.onRegions(s); }
    } catch (e) {}
  }
  function regionsSoon(ms) {
    if (regionTimer) clearTimeout(regionTimer);
    regionTimer = setTimeout(computeRegions, ms);
  }
  try {
    document.addEventListener('scroll', function (e) {
      var t = e.target;
      if (t && t.nodeType === 1 && t.scrollLeft > 1 && scrolled.indexOf(t) < 0) scrolled.push(t);
      regionsSoon(150);
    }, { capture: true, passive: true });
    document.addEventListener('touchend', function () { regionsSoon(60); }, { capture: true, passive: true });
    document.addEventListener('focusin', function () { regionsSoon(50); }, true);
    document.addEventListener('focusout', function () { regionsSoon(50); }, true);
    document.addEventListener('input', function () { regionsSoon(200); }, true);
    window.addEventListener('resize', function () { regionsSoon(100); });
    // menus and dialogs come and go in the overlay container only
    var ovObs = new MutationObserver(function () { regionsSoon(30); });
    var hookOverlay = function () {
      var c = document.querySelector('.cdk-overlay-container');
      if (c && !c.__geObs) { c.__geObs = 1; ovObs.observe(c, { childList: true, subtree: true }); }
    };
    hookOverlay();
    document.addEventListener('click', function () { setTimeout(hookOverlay, 50); regionsSoon(80); }, true);
  } catch (e) {}

  // ---------- the page's menu button opens the native sidebar ----------
  try {
    document.addEventListener('click', function (e) {
      try {
        var b = e.target && e.target.closest && e.target.closest('[data-test-id="side-nav-menu-button"], button[aria-label="Main menu"]');
        if (!b || !e.isTrusted || !nativeOn()) return;
        e.preventDefault(); e.stopImmediatePropagation();
        B.openNative();
      } catch (x) {}
    }, true);
  } catch (e) {}

  // ---------- actions (called by Android) ----------
  function route(path) {
    if (location.pathname === path) return;
    history.pushState(null, '', path);
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }
  function pageAnchor(href) {
    var d = sidenav(); if (!d) return null;
    var as = d.querySelectorAll('a[href]');
    for (var i = 0; i < as.length; i++) {
      var h = as[i].getAttribute('href');
      if (h === href || '/' + h === href) return as[i];
    }
    return null;
  }
  function clickOpenPage() {
    var h = document.querySelector('[data-test-id="side-nav-menu-button"]') || document.querySelector('button[aria-label="Main menu"]');
    if (!h) return false;
    var b = h.tagName === 'BUTTON' ? h : (h.querySelector('button') || h);
    b.click();
    return true;
  }
  function pageSidebarOpen() { var d = sidenav(); return !!(d && d.classList.contains('mat-drawer-opened')); }
  window.__geSB = {
    refresh: function (full) {
      var age = Date.now() - lastAny;
      if (full || Date.now() - lastFull > 300000) refresh(true);
      else if (age > 20000) refresh(false);
    },
    openChat: function (id) {
      if (!/^[A-Za-z0-9_-]+$/.test(id)) return;
      route('/app/' + id);
    },
    nav: function (href) {
      if (href === '/app') {
        // the page's own New chat (top bar button), as Gemini does it
        var nb = document.querySelector('button[aria-label="New Chat"], button[aria-label="New chat"]');
        if (nb) { nb.click(); return; }
      }
      var a = pageAnchor(href);
      if (a) { a.click(); setTimeout(function () { if (location.pathname !== href) route(href); }, 400); return; }
      route(href);
    },
    settings: function () {
      // Settings is a menu inside the page's sidebar: open it, then press its Settings button.
      if (!pageSidebarOpen()) clickOpenPage();
      var t0 = Date.now();
      (function tick() {
        var d = sidenav(), s = d && d.querySelector('button[aria-label="Settings"]');
        if (s && pageSidebarOpen() && s.getBoundingClientRect().left >= 0) { s.click(); return; }
        if (Date.now() - t0 < 2000) setTimeout(tick, 60);
      })();
    },
    openPageSidebar: function () { if (!pageSidebarOpen()) clickOpenPage(); },
    hello: function () { lastPath = null; reportPath(); reportNav(); lastRegions = ''; computeRegions(); }
  };

  // ---------- start ----------
  reportPath();
  reportNav();
  computeRegions();
  watchList();
  refresh(true);
  setTimeout(function () { reportNav(); watchList(); }, 3000);
  try { B.onReady(); } catch (e) {}
})();
