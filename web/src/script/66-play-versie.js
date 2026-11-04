/* ---------- Play-versie: alleen wat Google Play toestaat ---------- */
// De Play-versie (pakket nl.rene.tools.play, gebouwd met build-play.sh; zie docs/play-store.md) mist een paar
// toestemmingen en onderdelen. Tools die daarzonder niet werken, verdwijnen uit beeld; updates komen via Google Play.
// In de gewone (GitHub-)versie doet dit script niets.
const PLAY_WEG = ['redial', 'sms', 'calls', 'recorder']; // zonder belgeschiedenis, sms of gespreksopname
function isPlay(){ return !!ver().play; }
function playWeg(tool){ return isPlay() && PLAY_WEG.includes(tool); }
(function(){
  if (!isPlay()) return;
  document.documentElement.classList.add('play');
  // Tegels op het startscherm
  Object.keys(TILE_TOOL).forEach(id => { if (PLAY_WEG.includes(TILE_TOOL[id])) { const el = document.getElementById(id); if (el) el.classList.add('play-weg'); } });
  // Zo'n tool ook niet via zoeken, de assistent of een snelkoppeling openen
  const showGewoon = show;
  show = function(name){
    if (PLAY_WEG.includes(name)) { toast('Deze tool zit niet in de Play-versie'); return; }
    return showGewoon.apply(this, arguments);
  };
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
})();
