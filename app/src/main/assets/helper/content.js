// Runs in every page and frame. Talks to the app over a native port.
// App -> page: speed, seek, pause, pip (fill window with the playing video), theme.
// Page -> app: hello, playing (+ video size for the PiP window shape).
(() => {
  if (window.__afvLoaded) return;
  window.__afvLoaded = true;

  const TOP = window === window.top;
  let port = null;
  let tries = 0;
  let speed = 1;
  let pip = false;
  let theme = null;
  let lastReport = '';

  // ---------- connection ----------

  function send(m) {
    try { if (port) port.postMessage(m); } catch (e) { /* port gone */ }
  }

  function connect() {
    try {
      port = browser.runtime.connectNative('browser');
    } catch (e) {
      port = null;
      retry();
      return;
    }
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => { port = null; retry(); });
    send({ t: 'hello', top: TOP });
    lastReport = '';
    report();
  }

  function retry() {
    if (++tries <= 20) setTimeout(connect, 1000);
  }

  function onMessage(m) {
    if (!m || !m.t) return;
    switch (m.t) {
      case 'speed': speed = Number(m.v) || 1; videos().forEach(applySpeed); break;
      case 'seek': seek(Number(m.v) || 0); break;
      case 'pause': videos().forEach(v => { try { v.pause(); } catch (e) {} }); break;
      case 'pip': pip = !!m.v; pipApply(); break;
      case 'theme': if (TOP) { theme = m; applyTheme(); } break;
    }
  }

  // ---------- videos ----------

  const videos = () => Array.from(document.getElementsByTagName('video'));
  const playing = v => !v.paused && !v.ended;
  const area = v => v.clientWidth * v.clientHeight;

  function target() {
    const vs = videos();
    return vs.find(playing)
      || vs.filter(v => v.duration > 0).sort((a, b) => area(b) - area(a))[0]
      || (vs.length === 1 ? vs[0] : null);
  }

  function applySpeed(v) {
    if (Math.abs(v.playbackRate - speed) > 0.001) {
      try { v.playbackRate = speed; } catch (e) {}
    }
  }

  function seek(d) {
    const v = target();
    if (!v) return;
    let t = v.currentTime + d;
    if (isFinite(v.duration)) t = Math.min(v.duration - 0.25, t);
    v.currentTime = Math.max(0, t);
  }

  function report() {
    const v = videos().find(playing);
    const state = v ? `1:${v.videoWidth}x${v.videoHeight}` : '0';
    if (state === lastReport) return;
    lastReport = state;
    send({ t: 'playing', v: !!v, w: v ? v.videoWidth : 0, h: v ? v.videoHeight : 0 });
  }

  // Media events don't bubble, but capture on document still sees them.
  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'emptied', 'resize'].forEach(ev =>
    document.addEventListener(ev, e => {
      const v = e.target;
      if (!v || v.tagName !== 'VIDEO') return;
      if (ev !== 'pause' && ev !== 'ended') applySpeed(v);
      report();
      if (pip) pipApply();
    }, true));

  // Some players reset the speed; put ours back (only when the user picked one).
  document.addEventListener('ratechange', e => {
    const v = e.target;
    if (v && v.tagName === 'VIDEO' && speed !== 1 && Math.abs(v.playbackRate - speed) > 0.001) {
      setTimeout(() => applySpeed(v), 0);
    }
  }, true);

  // ---------- styles ----------

  function style(id, css) {
    const root = document.documentElement;
    if (!root) return;
    let s = document.getElementById(id);
    if (!s) {
      s = document.createElement('style');
      s.id = id;
      (document.head || root).appendChild(s);
    }
    if (s.textContent !== css) s.textContent = css;
  }

  // ---------- picture-in-picture: make the playing video fill the window ----------

  const PIP_CSS = `
.__afv_pipfull{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;
 width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;
 min-width:0!important;min-height:0!important;margin:0!important;padding:0!important;border:0!important;
 z-index:2147483647!important;background:#000!important;transform:none!important;object-fit:contain!important;
 visibility:visible!important;opacity:1!important;display:block!important}
html.__afv_piproot,html.__afv_piproot body{overflow:hidden!important}`;

  function clearPip() {
    document.querySelectorAll('.__afv_pipfull').forEach(e => e.classList.remove('__afv_pipfull'));
    if (document.documentElement) document.documentElement.classList.remove('__afv_piproot');
  }

  function fill(el) {
    el.classList.add('__afv_pipfull');
    document.documentElement.classList.add('__afv_piproot');
    if (!TOP) { try { window.parent.postMessage('__afvPip', '*'); } catch (e) {} }
  }

  function pipApply() {
    if (!pip) { clearPip(); return; }
    if (document.fullscreenElement) return; // real full-screen already fills the window
    style('__afv_pip', PIP_CSS);
    const v = videos().find(playing);
    if (v) { clearPip(); fill(v); }
  }

  // A child frame with the playing video asks us to make its frame fill the window too.
  window.addEventListener('message', e => {
    if (!pip || e.data !== '__afvPip') return;
    const frames = Array.from(document.querySelectorAll('iframe,frame'));
    let f = frames.find(x => { try { return x.contentWindow === e.source; } catch (err) { return false; } });
    if (!f) f = frames.sort((a, b) => area(b) - area(a))[0];
    if (f) { style('__afv_pip', PIP_CSS); clearPip(); fill(f); }
  });

  // ---------- colour themes (top page only) ----------
  // A see-through layer on top of the page re-colours everything behind it
  // (backdrop-filter), so fixed headers keep working. Pictures and video get the
  // opposite filter so they look normal. Real full-screen sits above the layer.

  const OVERLAY = 'afv-colour-layer';
  const MEDIA = 'img,video,canvas,iframe,embed,object,svg image,[style*="background-image"]';
  let watcher = null;

  function themeCss(t) {
    const fx = [];
    if (t.invert) fx.push('invert(1)', 'hue-rotate(180deg)');
    if (t.hue) fx.push(`hue-rotate(${t.hue}deg)`);
    // undo the reversible part for pictures, in reverse order
    const undo = fx.slice().reverse().map(f =>
      f.startsWith('hue-rotate') ? f.replace(/hue-rotate\((-?\d+)deg\)/, (m, a) => `hue-rotate(${-a}deg)`) : f);
    const extra = [];
    if (t.warm > 0) extra.push(`sepia(${t.warm / 100})`);
    if (t.dim > 0 && t.dim < 100) extra.push(`brightness(${t.dim / 100})`);
    const all = fx.concat(extra);

    let css = '';
    if (all.length) {
      css += `${OVERLAY}{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;
        z-index:2147483646!important;pointer-events:none!important;display:block!important;background:transparent!important;
        backdrop-filter:${all.join(' ')}!important}
        html:has(:fullscreen) ${OVERLAY},html.__afv_piproot ${OVERLAY}{display:none!important}`;
    }
    if (undo.length) {
      const scope = 'html:not(:has(:fullscreen)):not(.__afv_piproot)';
      css += MEDIA.split(',').map(s => `${scope} ${s}`).join(',') + `{filter:${undo.join(' ')}!important}`;
    }
    return { css: css + '\n' + (t.css || ''), layer: all.length > 0 };
  }

  function ensureLayer(on) {
    const root = document.documentElement;
    if (!root) return;
    let el = document.querySelector(OVERLAY);
    if (on && !el) { el = document.createElement(OVERLAY); root.appendChild(el); }
    if (!on && el) el.remove();
  }

  function applyTheme() {
    if (!TOP || !theme) return;
    if (!document.documentElement) { document.addEventListener('DOMContentLoaded', applyTheme, { once: true }); return; }
    const r = themeCss(theme);
    style('__afv_theme', r.css);
    ensureLayer(r.layer);
    // Pages that rebuild themselves can drop our layer/style; put them back.
    if (!watcher) {
      watcher = new MutationObserver(() => {
        const rr = themeCss(theme);
        if (!document.getElementById('__afv_theme')) style('__afv_theme', rr.css);
        if (rr.layer && !document.querySelector(OVERLAY)) ensureLayer(true);
      });
      watcher.observe(document.documentElement, { childList: true, subtree: false });
      if (document.head) watcher.observe(document.head, { childList: true });
    }
  }

  connect();
})();
