/* ---------- Snelkoppelingen ---------- */
// Tool → naam, kleur en symbool (zelfde kleur als de tegel).
const TOOLS = {
  car: ['Mijn auto', '#1F6F5C', '🚗'], kluis: ['Kluis', '#4B5563', '🔒'],
  recorder: ['Gesprekken opnemen', '#A93226', '🎙'],
  history: ['Meldingsgeschiedenis', '#B9770E', '🔔'],
  radio: ['Radio', '#D35400', '📻'], music: ['Muziek herkennen', '#2471A3', '🎵'], notes: ['Notities', '#E67E22', '📝'],
  redial: ['Auto redial', '#1E5AA8', '🔁'], calls: ['Oproepen', '#16A085', '📞'], contacts: ['Contacten', '#2C3E50', '👤'],
  sms: ['SMS-backup', '#8E44AD', '💬'], wa: ['WhatsApp backup', '#25A35A', '💾'], tracks: ['Mijn routes', '#C0392B', '📍'],
  transcripts: ['Gesprekken uitschrijven', '#7D3C98', '🗒'], backup: ['Alles back-uppen', '#34495E', '🗄'],
  skin: ['Telefoon-skin', '#6D28D9', '📱'], auto: ['Automatiseringen', '#0E7C86', '⚡']
};
const TILE_TOOL = { 'tile-car': 'car', 'tile-kluis': 'kluis', 'tile-recorder': 'recorder', 'tile-history': 'history', 'tile-redial': 'redial', 'tile-wa': 'wa', 'tile-tracks': 'tracks', 'tile-sms': 'sms', 'tile-calls': 'calls',
  'tile-notes': 'notes', 'tile-contacts': 'contacts', 'tile-tr': 'transcripts', 'tile-radio': 'radio', 'tile-music': 'music', 'tile-backup': 'backup', 'tile-skin': 'skin', 'tile-auto': 'auto' };
function pinTool(tool){
  const t = TOOLS[tool]; if (!t) return;
  const e = Android.shortcutPin(tool, t[0], t[1], t[2]);
  toast(e || 'Bevestig op je startscherm om ' + t[0] + ' toe te voegen');
}
function toolMenu(tool){
  const t = TOOLS[tool]; if (!t) return;
  openSheet(t[0], 'Snelkoppeling', [
    ['Openen', () => tool === 'skin' ? Android.homeOpen() : show(tool)],
    ['Snelkoppeling op startscherm', () => pinTool(tool)],
  ]);
}
// Lang indrukken van een tegel opent het menu (de gewone tik opent de tool).
(function(){
  let timer = null, fired = false, sx = 0, sy = 0;
  document.querySelectorAll('.tiles .tile').forEach(el => {
    const tool = TILE_TOOL[el.id]; if (!tool) return;
    el.addEventListener('touchstart', e => { fired = false; sx = e.touches[0].clientX; sy = e.touches[0].clientY;
      timer = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(15); toolMenu(tool); }, 550); }, { passive: true });
    el.addEventListener('touchmove', e => { if (Math.abs(e.touches[0].clientX - sx) > 10 || Math.abs(e.touches[0].clientY - sy) > 10) clearTimeout(timer); }, { passive: true });
    el.addEventListener('touchend', () => clearTimeout(timer));
    el.addEventListener('contextmenu', e => { e.preventDefault(); if (!fired) { fired = true; toolMenu(tool); } });
    el.addEventListener('click', e => { if (fired) { e.stopImmediatePropagation(); e.preventDefault(); fired = false; } }, true);
  });
})();

