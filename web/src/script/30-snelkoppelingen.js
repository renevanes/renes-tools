/* ---------- Snelkoppelingen ---------- */
// Naam, kleur en symbool voor Android-snelkoppelingen. Binnen de app komt het icoon uit UI_TOOL_ICONS.
const TILE_TOOL = { 'tile-cpap':'cpap', 'tile-foto':'foto', 'tile-ereader':'ereader', 'tile-nieuws':'nieuws', 'tile-masterdata':'masterdata', 'tile-car': 'car', 'tile-pdf': 'pdf', 'tile-kluis': 'kluis', 'tile-recorder': 'recorder', 'tile-history': 'history', 'tile-redial': 'redial', 'tile-wa': 'wa', 'tile-tracks': 'tracks', 'tile-sms': 'sms', 'tile-calls': 'calls',
  'tile-notes': 'notes', 'tile-contacts': 'contacts', 'tile-tr': 'transcripts', 'tile-radio': 'radio', 'tile-podcasts': 'podcasts', 'tile-spotify': 'spotify', 'tile-vpn': 'vpn', 'tile-loc': 'loc', 'tile-music': 'music', 'tile-ai': 'ai', 'tile-backup': 'backup', 'tile-skin': 'skin', 'tile-auto': 'auto', 'tile-sleep': 'sleep', 'tile-checkin': 'checkin', 'tile-apparaten': 'apparaten', 'tile-gebruik': 'gebruik', 'tile-facturen': 'facturen', 'tile-bank': 'bank', 'tile-betalen': 'betalen', 'tile-wc': 'wc', 'tile-agenda': 'agenda', 'tile-signalen': 'signalen' };
function pinTool(tool){
  const tl = TOOLS[tool]; if (!tl) return;
  const e = tool === 'player' ? Android.shortcutPinPlayer() : Android.shortcutPin(tool, t(tl[0]), tl[1], tl[2]);
  toast(e || t('Bevestig op je startscherm om {naam} toe te voegen', { naam: t(tl[0]) }));
}
function toolMenu(tool){
  const tl = TOOLS[tool]; if (!tl) return;
  const fav = isFav(tool);
  openSheet(t(tl[0]), fav ? '★ Favoriet' : '', [
    ['Openen', () => tool === 'skin' ? Android.homeOpen() : show(tool)],
    [fav ? '☆ Uit favorieten' : '★ Favoriet (bovenaan)', () => toggleFav(tool)],
    ['Naar een andere groep…', () => heMoveSheet(tool)],
    ['Verbergen van het startscherm', () => { const wasFav = isFav(tool); heToggle(tool, false); undoToast(t('{naam} verborgen', { naam: t(TOOLS[tool][0]) }), () => { heToggle(tool, true); if (wasFav) toggleFav(tool); }); }],
    ['Snelkoppeling op startscherm', () => pinTool(tool)],
  ].concat(typeof obMenuItems === 'function' ? obMenuItems(tool) : []).concat(SOLO_TOOLS.includes(tool) ? [['Als losse app op startscherm…', () => soloMake(tool)]] : []).concat(typeof tsMenu === 'function' ? tsMenu(tool) : []));
}
// Lang indrukken van een tegel (of een knop bij Recent gebruikt) opent het menu; de gewone tik opent de tool.
// Kan de tool in de onderbalk, dan doet 43b-onderbalk.js het: ingedrukt houden pakt hem op, loslaten = het menu, schuiven = naar de balk slepen.
function toolPress(el, tool){
  const ob = () => typeof obPak === 'function' && obMag(tool) && !(typeof SOLO !== 'undefined' && SOLO);
  let timer = null, fired = false, touching = false, sx = 0, sy = 0;
  el.addEventListener('pointerdown', e => { if (ob()) obPak(e, tool, 'tegel', el); });
  obGeenScroll(el); // opgepakt: de pagina scrolt niet mee (43b-onderbalk.js)
  el.addEventListener('touchstart', e => { fired = false; touching = true; if (ob()) return; sx = e.touches[0].clientX; sy = e.touches[0].clientY;
    timer = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(15); toolMenu(tool); }, 550); }, { passive: true });
  el.addEventListener('touchmove', e => { if (Math.abs(e.touches[0].clientX - sx) > 10 || Math.abs(e.touches[0].clientY - sy) > 10) clearTimeout(timer); }, { passive: true });
  el.addEventListener('touchend', e => { clearTimeout(timer); touching = false; if (fired && e.cancelable) e.preventDefault(); }); // loslaten na lang indrukken: geen tik op het menu eronder
  el.addEventListener('touchcancel', () => { clearTimeout(timer); touching = false; });
  // Rechtsklik of het lang-indrukken-signaal van de WebView: menu (tenzij de eigen timer of het oppakken het tijdens deze aanraking al regelt)
  el.addEventListener('contextmenu', e => { e.preventDefault(); if (ob() && (touching || (typeof obSleep !== 'undefined' && obSleep))) return; if (touching) { if (fired) return; clearTimeout(timer); fired = true; } toolMenu(tool); });
  el.addEventListener('click', e => { if (fired) { e.stopImmediatePropagation(); e.preventDefault(); fired = false; } }, true);
}
document.querySelectorAll('.tiles .tile').forEach(el => { const tool = TILE_TOOL[el.id]; if (tool) toolPress(el, tool); });

