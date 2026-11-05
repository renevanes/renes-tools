/* ---------- Play-versie: alleen wat Google Play toestaat ---------- */
// De Play-basis (pakket nl.rene.tools.play, gebouwd met build-play.sh; zie docs/play-store.md) gebruikt
// alleen expliciet gekozen tools en schermen; updates komen via Google Play.
// In de gewone (GitHub-)versie doet dit script niets. Staat vóór 29-start.js, zodat ook openen bij het starten
// (snelkoppeling, widget, melding, losse app) al langs de controle gaat.
const PLAY = !!ver().play;
const PLAY_TOOLS = new Set(ver().playTools || ['notes', 'pdf', 'radio', 'podcasts', 'masterdata']);
const PLAY_NAV = new Set(['home', 'search', 'settings', 'info', 'homeedit', 'setnotif', 'setlook', 'setregio', 'setregiotz', 'setlock', 'setsolo', 'setperm', 'player']);
function isPlay(){ return PLAY; }
/** Alleen bekende schermen van de behouden functies; nieuwe subschermen worden bewust beoordeeld. */
function playWeg(name){
  if (!PLAY) return false;
  const children = {idcopy: 'pdf', pdfedit: 'pdf', pdfimg: 'pdf', pdfsplit: 'pdf', pdftext: 'pdf', pdfmade: 'pdf', note: 'notes', ralarm: 'radio', podcast: 'podcasts', podlijst: 'podcasts'};
  return !PLAY_NAV.has(name) && !PLAY_TOOLS.has(name) && !PLAY_TOOLS.has(children[name]);
}
function openPrivacy(){ Android.openUrl('https://github.com/renevanes/renes-tools/blob/main/docs/' + (PLAY ? 'privacybeleid-play.md' : 'privacybeleid.md')); }
(function(){
  if (!PLAY) return;
  document.documentElement.classList.add('play');
  // Zo'n tool ook niet via zoeken, de assistent, een snelkoppeling of bij het starten openen
  const showGewoon = show;
  show = function(name){
    if (playWeg(name)) { toast(t('Deze tool zit niet in de Play-versie')); return; }
    return showGewoon.apply(this, arguments);
  };
  // Overal zoeken: geen sms'jes of oproepen
  Object.keys(GS).forEach(k => { if (k !== 'app' && !PLAY_TOOLS.has(k)) delete GS[k]; });
  // Wat pas later in de pagina staat (tegels, instellingen, losse apps): na het laden
  setTimeout(() => {
    Object.keys(TILE_TOOL).forEach(id => { if (playWeg(TILE_TOOL[id])) { const el = document.getElementById(id); if (el) el.classList.add('play-weg'); } });
    document.querySelectorAll('[onclick]').forEach(el => {
      const m = el.getAttribute('onclick').match(/(?:show|jumpTo|openTool)\(['"]([a-zA-Z0-9]+)['"]/);
      if (m && playWeg(m[1])) el.classList.add('play-weg');
    });
    for (let i = SET_REG.length - 1; i >= 0; i--) if (playWeg(SET_REG[i].scr)) SET_REG.splice(i, 1);
    for (let i = SOLO_TOOLS.length - 1; i >= 0; i--) if (playWeg(SOLO_TOOLS[i])) SOLO_TOOLS.splice(i, 1);
    document.querySelectorAll('[onclick*="voiceOpen"], [onclick*="pspOpen"]').forEach(el => el.classList.add('play-weg'));
    const homePlace = document.getElementById('rg-home-btn');
    if (homePlace) homePlace.closest('.card').classList.add('play-weg');
    // Updates: geen eigen controle, dat doet Google Play
    const chk = document.getElementById('chk-btn');
    if (chk) {
      chk.classList.add('play-weg');
      const n = document.createElement('p'); n.className = 'note'; n.id = 'play-upd';
      n.textContent = t('Updates van deze versie komen via Google Play.');
      chk.after(n);
      const note = chk.nextElementSibling && n.nextElementSibling;
      if (note && note.classList.contains('note')) note.classList.add('play-weg');
    }
    // Gebruik: geen apparaatbeheer en geen stille foto's bij ontgrendelen
    const gbFoto = document.querySelector('#gb-tabs [data-tab="fotos"]'); if (gbFoto) gbFoto.classList.add('play-weg');
    ['gb-admin', 'gb-pfail'].forEach(id => { const el = document.getElementById(id); const rij = el && el.closest('.sw'); if (rij) rij.classList.add('play-weg'); });
    const adminUit = document.getElementById('gb-admin-off'); if (adminUit) adminUit.classList.add('play-weg');
    const fotoKaart = document.getElementById('gb-photo-status'); if (fotoKaart && fotoKaart.closest('.card')) fotoKaart.closest('.card').classList.add('play-weg');
  }, 0);
})();
