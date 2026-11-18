/* Nieuws: onthouden waar je was in een video (in het leesvenster, NieuwsInlogActivity).
 * Draait in de pagina van de videospeler (vooraan in het document, vóór de scripts van de speler) of in het artikel
 * zelf. Bewaart per video alleen een tijd in de opslag van die pagina (localStorage); er gaat niets naar Tools of
 * naar buiten. Zie NieuwsVideo.java. De teksten (TEKST) vult Tools in, vertaald: {"verder":"Verder vanaf {tijd}","begin":"Vanaf het begin"}. */
(function () {
  'use strict';
  if (window.__toolsPlek) return;
  window.__toolsPlek = 1;
  var TEKST = __TEKST__;
  var BASIS = 'tools.plek:' + location.origin + location.pathname;
  var MIN = 10, EIND = 30, ELKE = 5000, OUD = 90 * 864e5; // pas na 10 s onthouden; laatste 30 s = gezien; hooguit elke 5 s; 90 dagen bewaren

  // Per video een eigen sleutel: het vaste adres van de video, anders de plek op de pagina (niet de volgorde van afspelen)
  function sleutel(el) {
    var src = String(el.currentSrc || el.src || '');
    if (/^https:/i.test(src)) return BASIS + '|' + src.split(/[?#]/)[0];
    var i = 0;
    try { i = Array.prototype.indexOf.call((el.getRootNode ? el.getRootNode() : document).querySelectorAll('video,audio'), el); } catch (e) { }
    if (i < 0) return null; // niet op de pagina (een los geluid): niets onthouden
    return i > 0 ? BASIS + '#' + i : BASIS;
  }
  // Oude plekken opruimen (een keer per pagina)
  try {
    for (var j = localStorage.length - 1; j >= 0; j--) {
      var nm = localStorage.key(j);
      if (!nm || nm.indexOf('tools.plek:') !== 0) continue;
      var ou = null;
      try { ou = JSON.parse(localStorage.getItem(nm)); } catch (e) { }
      if (!ou || !(ou.w > 0) || Date.now() - ou.w > OUD) localStorage.removeItem(nm);
    }
  } catch (e) { }
  function lees(k) {
    try { var o = JSON.parse(localStorage.getItem(k) || 'null'); return o && o.t > 0 ? o : null; } catch (e) { return null; }
  }
  function weg(k) { try { localStorage.removeItem(k); } catch (e) { } }
  function schrijf(k, t, d) {
    if (!(t >= 0) || !isFinite(t)) return;
    if (t < MIN || (d > 0 && isFinite(d) && d - t < EIND)) { weg(k); return; }
    try { localStorage.setItem(k, JSON.stringify({ t: Math.floor(t), d: d > 0 && isFinite(d) ? Math.floor(d) : 0, w: Date.now() })); } catch (e) { }
  }
  function tijd(s) {
    s = Math.max(0, Math.floor(s));
    var u = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (u ? u + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }

  var gezien = typeof WeakSet === 'function' ? new WeakSet() : null;
  function volg(el) {
    if (!el || !(el instanceof HTMLMediaElement)) return;
    if (gezien) { if (gezien.has(el)) return; gezien.add(el); } else { if (el.__toolsPlek) return; el.__toolsPlek = 1; }
    var k = null, src = null, laatst = 0, hervat = false;
    function kk() { return k || (k = sleutel(el)); } // pas bij het spelen: dan is het adres bekend
    // een andere video in hetzelfde element (eerst een reclame, dan de video zelf): opnieuw beginnen
    function nieuw() { var s = String(el.currentSrc || el.src || ''); if (src !== null && s !== src) { k = null; hervat = false; } src = s; }
    el.addEventListener('loadstart', nieuw);
    el.addEventListener('emptied', nieuw);
    el.addEventListener('timeupdate', function () {
      var n = Date.now();
      if (n - laatst < ELKE) return;
      if (el.currentTime >= MIN) laatst = n; // de eerste seconden tellen niet mee voor "elke 5 s"
      if (kk()) schrijf(k, el.currentTime, el.duration);
    });
    el.addEventListener('pause', function () { if (kk()) schrijf(k, el.currentTime, el.duration); });
    el.addEventListener('ended', function () { if (kk()) weg(k); });
    el.addEventListener('playing', function () {
      if (hervat) return;
      hervat = true;
      if (src === null) src = String(el.currentSrc || el.src || '');
      var sl = kk(), o = sl && lees(sl);
      if (!o || el.currentTime > 5) return; // de speler begon zelf al ergens anders
      var d = el.duration;
      if (d > 0 && isFinite(d)) {
        if (o.d > 0 && Math.abs(o.d - d) > 2) return; // een andere video op die plek
        if (o.t > d - EIND) { weg(sl); return; }
      }
      try { el.currentTime = o.t; } catch (e) { return; }
      toon(el, sl, o.t);
    });
  }

  // Een korte regel bovenaan de speler, met een knop om toch bij het begin te beginnen
  function toon(el, k, t) {
    try {
      var doc = el.ownerDocument || document, box = doc.createElement('div'), txt = doc.createElement('span'), knop = doc.createElement('button');
      box.setAttribute('role', 'status');
      box.style.cssText = 'position:fixed;left:50%;top:12px;transform:translateX(-50%);z-index:2147483647;display:flex;align-items:center;gap:12px;' +
        'max-width:calc(100% - 24px);box-sizing:border-box;padding:8px 8px 8px 14px;border-radius:12px;background:rgba(20,20,20,.88);color:#fff;' +
        'font:500 14px/1.3 system-ui,-apple-system,Roboto,sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.35)';
      txt.textContent = String(TEKST.verder || '').replace('{tijd}', tijd(t));
      knop.type = 'button';
      knop.textContent = String(TEKST.begin || '');
      knop.style.cssText = 'min-height:44px;padding:0 12px;border:0;border-radius:8px;background:rgba(255,255,255,.18);color:#fff;font:inherit;cursor:pointer';
      knop.addEventListener('click', function (e) {
        e.stopPropagation();
        try { el.currentTime = 0; } catch (x) { }
        weg(k); box.remove();
      });
      box.appendChild(txt); box.appendChild(knop);
      (doc.body || doc.documentElement).appendChild(box);
      setTimeout(function () { box.remove(); }, 8000);
    } catch (e) { }
  }

  // Elke video die gaat spelen: via play() van de speler, of via de knoppen van de video zelf (ook in een schaduw-DOM)
  try {
    var P = HTMLMediaElement.prototype, play = P.play;
    P.play = function () { volg(this); return play.apply(this, arguments); };
  } catch (e) { }
  document.addEventListener('play', function (e) { volg(e.target); }, true);
  try {
    var schaduw = Element.prototype.attachShadow;
    if (schaduw) Element.prototype.attachShadow = function () {
      var r = schaduw.apply(this, arguments);
      try { r.addEventListener('play', function (e) { volg(e.target); }, true); } catch (e) { }
      return r;
    };
  } catch (e) { }
  // Video's die er al zijn (in het artikel zelf, waar dit script pas na het laden draait)
  try { var v = document.querySelectorAll('video,audio'); for (var i = 0; i < v.length; i++) if (!v[i].paused) volg(v[i]); } catch (e) { }
})();
