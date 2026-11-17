// Video İndirici — sayfadaki videoların üstünde IDM tarzı "⬇ İndir" butonu.
(() => {
  if (window.__videoIndirici) return;
  window.__videoIndirici = true;

  // Arka plan, korumalı m3u8 listelerini sayfanın kendi bağlamından okutur (çerez + Referer otomatik)
  chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
    if (msg.type !== "fetchText") return;
    fetch(msg.url, { credentials: "include" })
      .then(async r => reply(r.ok ? { ok: true, text: await r.text() } : { ok: false }))
      .catch(() => reply({ ok: false }));
    return true;
  });

  const MIN_W = 200, MIN_H = 110;
  let videos = [];
  let current = null;
  let hideTimer = null;
  let busy = false;

  // Sayfanın CSS'i butonu bozmasın diye shadow DOM
  const host = document.createElement("div");
  host.style.cssText = "all:initial;position:fixed;z-index:2147483647;top:0;left:0;display:none;";
  const root = host.attachShadow({ mode: "closed" });
  root.innerHTML = `
    <style>
      button { font: 600 13px/1 system-ui, -apple-system, "Segoe UI", sans-serif; color: #fff;
        background: #4c8dff; border: 0; border-radius: 8px; padding: 8px 12px; cursor: pointer;
        box-shadow: 0 2px 10px rgba(0,0,0,.4); opacity: .93; white-space: nowrap; }
      button:hover { opacity: 1; filter: brightness(1.08); }
      button.ok { background: #3fb37f; } button.err { background: #e5596b; }
    </style>
    <button type="button">⬇ İndir</button>`;
  const btn = root.querySelector("button");

  function attach() {
    const parent = document.fullscreenElement || document.webkitFullscreenElement || document.documentElement;
    if (host.parentNode !== parent) parent.appendChild(host);
  }

  function refreshVideos() {
    videos = [...document.querySelectorAll("video")];
  }
  refreshVideos();
  setInterval(refreshVideos, 2000);

  function videoAt(x, y) {
    for (const v of videos) {
      const r = v.getBoundingClientRect();
      if (r.width < MIN_W || r.height < MIN_H) continue;
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return v;
    }
    return null;
  }

  function place(v) {
    const r = v.getBoundingClientRect();
    const top = Math.max(r.top + 10, 6);
    const left = Math.min(r.right - 10, window.innerWidth - 6);
    host.style.top = `${top}px`;
    host.style.left = `${left}px`;
    host.style.transform = "translateX(-100%)";
  }

  function show(v) {
    current = v;
    attach();
    place(v);
    host.style.display = "block";
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!busy) host.style.display = "none"; }, 3000);
  }

  let frame = 0;
  document.addEventListener("mousemove", e => {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (e.composedPath && e.composedPath().includes(host)) return show(current);
      const v = videoAt(e.clientX, e.clientY);
      if (v) show(v);
    });
  }, { passive: true, capture: true });

  window.addEventListener("scroll", () => { if (current && host.style.display !== "none") place(current); }, { passive: true });
  document.addEventListener("fullscreenchange", () => { if (current) { attach(); place(current); } });

  function flash(text, cls, ms = 2500) {
    btn.textContent = text;
    btn.className = cls || "";
    setTimeout(() => { btn.textContent = "⬇ İndir"; btn.className = ""; busy = false; }, ms);
  }

  btn.addEventListener("click", async e => {
    e.preventDefault();
    e.stopPropagation();
    if (busy || !current) return;
    busy = true;
    btn.textContent = "⏳ Gönderiliyor…";
    try {
      const res = await chrome.runtime.sendMessage({ type: "best", src: current.currentSrc || current.src || "" });
      if (res && res.ok) flash("✓ İndirme başladı", "ok");
      else flash(`✗ ${(res && res.error) || "Hata"}`, "err", 5000);
    } catch (err) {
      flash("✗ Eklenti yeniden yüklendi, sayfayı yenile", "err", 5000);
    }
  }, true);
})();
