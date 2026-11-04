/* ---------- Play-versie: alleen wat Google Play toestaat ---------- */
// De Play-versie (pakket nl.rene.tools.play, gebouwd met build-play.sh; zie docs/play-store.md) mist een paar
// toestemmingen en onderdelen. Tools die daarzonder niet werken, verdwijnen uit beeld; updates komen via Google Play.
// In de gewone (GitHub-)versie doet dit script niets. Staat vóór 29-start.js, zodat ook openen bij het starten
// (snelkoppeling, widget, melding, losse app) al langs de controle gaat.
const PLAY_WEG = ['redial', 'sms', 'calls', 'recorder']; // zonder belgeschiedenis, sms of gespreksopname
const PLAY = !!ver().play;
function isPlay(){ return PLAY; }
/** Hoort dit scherm (of een scherm erboven, via BACK) bij een tool die niet in de Play-versie zit? */
function playWeg(name){
  if (!PLAY) return false;
  for (let n = name, i = 0; n && i < 20; n = BACK[n], i++) if (PLAY_WEG.includes(n)) return true;
  return false;
}
(function(){
  if (!PLAY) return;
  document.documentElement.classList.add('play');
  // Zo'n tool ook niet via zoeken, de assistent, een snelkoppeling of bij het starten openen
  const showGewoon = show;
  show = function(name){
    if (playWeg(name)) { toast('Deze tool zit niet in de Play-versie'); return; }
    return showGewoon.apply(this, arguments);
  };
  // Overal zoeken: geen sms'jes of oproepen
  delete GS.sms; delete GS.calls;
  // Wat pas later in de pagina staat (tegels, instellingen, losse apps): na het laden
  setTimeout(() => {
    Object.keys(TILE_TOOL).forEach(id => { if (PLAY_WEG.includes(TILE_TOOL[id])) { const el = document.getElementById(id); if (el) el.classList.add('play-weg'); } });
    for (let i = SET_REG.length - 1; i >= 0; i--) if (playWeg(SET_REG[i].scr)) SET_REG.splice(i, 1);
    for (let i = SOLO_TOOLS.length - 1; i >= 0; i--) if (PLAY_WEG.includes(SOLO_TOOLS[i])) SOLO_TOOLS.splice(i, 1);
    // Updates: geen eigen controle, dat doet Google Play
    const chk = document.getElementById('chk-btn');
    if (chk) {
      chk.classList.add('play-weg');
      const n = document.createElement('p'); n.className = 'note'; n.id = 'play-upd';
      n.textContent = 'Updates van deze versie komen via Google Play.';
      chk.after(n);
    }
    // Gebruik: geen apparaatbeheer en geen stille foto's bij ontgrendelen
    const gbFoto = document.querySelector('#gb-tabs [data-tab="fotos"]'); if (gbFoto) gbFoto.classList.add('play-weg');
    ['gb-admin', 'gb-pfail'].forEach(id => { const el = document.getElementById(id); const rij = el && el.closest('.sw'); if (rij) rij.classList.add('play-weg'); });
    const adminUit = document.getElementById('gb-admin-off'); if (adminUit) adminUit.classList.add('play-weg');
    const fotoKaart = document.getElementById('gb-photo-status'); if (fotoKaart && fotoKaart.closest('.card')) fotoKaart.closest('.card').classList.add('play-weg');
  }, 0);
})();
