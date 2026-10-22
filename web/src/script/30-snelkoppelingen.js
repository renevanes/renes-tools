/* ---------- Snelkoppelingen ---------- */
// Naam, kleur en symbool voor Android-snelkoppelingen. Binnen de app komt het icoon uit UI_TOOL_ICONS.
const TOOLS = {
  car: ['Mijn auto', '#1F6F5C', '🚗'], kluis: ['Kluis', '#4B5563', '🔒'], pdf: ['PDF', '#B03A2E', '📄'],
  recorder: ['Gesprekken opnemen', '#A93226', '🎙'],
  history: ['Meldingsgeschiedenis', '#B9770E', '🔔'],
  radio: ['Radio', '#D35400', '📻'], podcasts: ['Podcasts', '#6C3483', '🎧'], spotify: ['Spotify-backup', '#1DB954', '🟢'], vpn: ['VPN', '#1E8449', '🔑'], loc: ['Locatie kiezen', '#B03A2E', '📍'], music: ['Muziek herkennen', '#2471A3', '🎵'], notes: ['Notities', '#E67E22', '📝'],
  redial: ['Auto redial', '#1E5AA8', '🔁'], calls: ['Oproepen', '#16A085', '📞'], contacts: ['Contacten', '#2C3E50', '👤'],
  sms: ['SMS-backup', '#8E44AD', '💬'], wa: ['WhatsApp backup', '#25A35A', '💾'], tracks: ['Mijn routes', '#C0392B', '📍'],
  transcripts: ['Gesprekken uitschrijven', '#7D3C98', '🗒'], backup: ['Alles back-uppen', '#34495E', '🗄'],
  skin: ['Telefoon-skin', '#6D28D9', '📱'], auto: ['Automatiseringen', '#0E7C86', '⚡'],
  player: ['Verder luisteren', '#6C3483', '▶'] // geen tegel: de speler (radio of podcast) gaat verder waar hij was
};
const TILE_TOOL = { 'tile-car': 'car', 'tile-pdf': 'pdf', 'tile-kluis': 'kluis', 'tile-recorder': 'recorder', 'tile-history': 'history', 'tile-redial': 'redial', 'tile-wa': 'wa', 'tile-tracks': 'tracks', 'tile-sms': 'sms', 'tile-calls': 'calls',
  'tile-notes': 'notes', 'tile-contacts': 'contacts', 'tile-tr': 'transcripts', 'tile-radio': 'radio', 'tile-podcasts': 'podcasts', 'tile-spotify': 'spotify', 'tile-vpn': 'vpn', 'tile-loc': 'loc', 'tile-music': 'music', 'tile-backup': 'backup', 'tile-skin': 'skin', 'tile-auto': 'auto' };
function pinTool(tool){
  const t = TOOLS[tool]; if (!t) return;
  const e = tool === 'player' ? Android.shortcutPinPlayer() : Android.shortcutPin(tool, t[0], t[1], t[2]);
  toast(e || 'Bevestig op je startscherm om ' + t[0] + ' toe te voegen');
}
function toolMenu(tool){
  const t = TOOLS[tool]; if (!t) return;
  const fav = isFav(tool);
  openSheet(t[0], fav ? '★ Favoriet' : '', [
    ['Openen', () => tool === 'skin' ? Android.homeOpen() : show(tool)],
    [fav ? '☆ Uit favorieten' : '★ Favoriet (bovenaan)', () => toggleFav(tool)],
    ['Snelkoppeling op startscherm', () => pinTool(tool)],
  ].concat(SOLO_TOOLS.includes(tool) ? [['Als losse app op startscherm…', () => soloMake(tool)]] : []));
}
// Lang indrukken van een tegel opent het menu (de gewone tik opent de tool).
(function(){
  let timer = null, fired = false, touching = false, sx = 0, sy = 0;
  document.querySelectorAll('.tiles .tile').forEach(el => {
    const tool = TILE_TOOL[el.id]; if (!tool) return;
    el.addEventListener('touchstart', e => { fired = false; touching = true; sx = e.touches[0].clientX; sy = e.touches[0].clientY;
      timer = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(15); toolMenu(tool); }, 550); }, { passive: true });
    el.addEventListener('touchmove', e => { if (Math.abs(e.touches[0].clientX - sx) > 10 || Math.abs(e.touches[0].clientY - sy) > 10) clearTimeout(timer); }, { passive: true });
    el.addEventListener('touchend', e => { clearTimeout(timer); touching = false; if (fired && e.cancelable) e.preventDefault(); }); // loslaten na lang indrukken: geen tik op het menu eronder
    el.addEventListener('touchcancel', () => { clearTimeout(timer); touching = false; });
    // Rechtsklik of het lang-indrukken-signaal van de WebView: menu (tenzij de eigen timer het tijdens deze aanraking al opende)
    el.addEventListener('contextmenu', e => { e.preventDefault(); if (touching) { if (fired) return; clearTimeout(timer); fired = true; } toolMenu(tool); });
    el.addEventListener('click', e => { if (fired) { e.stopImmediatePropagation(); e.preventDefault(); fired = false; } }, true);
  });
})();

