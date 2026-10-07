/* ---------- Radio ---------- */
let rdPoll = null, rdTmr = null, rdStations = [], rdFavs = [], rdState = {};
function rdJson(s, d){ try { return JSON.parse(s); } catch(e){ return d; } }
function rdLogo(s){
  const ini = esc((s.name || '?').replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '♪');
  // Alleen https-logo's (http wordt in de app geblokkeerd); bij een fout de initialen.
  return s.logo && /^https:\/\//i.test(s.logo)
    ? '<span class=logo><img src="' + esc(s.logo) + '" alt="" loading=lazy onerror="this.parentNode.textContent=' + jsq(ini) + '"></span>'
    : '<span class=logo>' + ini + '</span>';
}
function rdIsFav(s){ return rdFavs.some(f => s.id ? f.id === s.id : f.url === s.url); }
function rdRow(s, list, i){
  const cur = rdState.station && (rdState.station.id ? rdState.station.id === s.id : rdState.station.url === s.url) && rdState.status !== 'stopped';
  const tags = (s.tags || '').split(',').filter(Boolean).slice(0, 3).join(', ');
  return '<div class="srow' + (cur ? ' playing' : '') + '"><button class=sbtn onclick="rdPlay(\'' + list + '\',' + i + ')">' + rdLogo(s) +
    '<span class=stxt><b>' + esc(s.name) + '</b><small>' + esc(tags || (s.codec ? s.codec + (s.bitrate ? ' ' + s.bitrate + ' kbps' : '') : '')) + '</small></span>' +
    (cur ? '<span class=eq><i></i><i></i><i></i></span>' : '') + '</button>' +
    (list === 'f' && rdFavs.length > 1 ? '<button class=star onclick="rdFavMenu(' + i + ')" aria-label="Volgorde van ' + esc(s.name) + '">⇅</button>' : '') +
    '<button class=star onclick="rdFav(\'' + list + '\',' + i + ')" aria-label="Favoriet">' + (rdIsFav(s) ? '★' : '☆') + '</button></div>';
}
/* Volgorde van de favorieten (ook de volgorde in de auto en in de widget). */
function rdFavMenu(i){
  const s = rdFavs[i]; if (!s) return;
  const n = rdFavs.length, mv = to => { Android.radioMoveFavTo(i, to); rdFavs = rdJson(Android.radioFavorites(), []); rdRender(); };
  const acts = [];
  if (i > 0) acts.push(['⤒ Bovenaan', () => mv(0)], ['↑ Eén omhoog', () => mv(i - 1)]);
  if (i < n - 1) acts.push(['↓ Eén omlaag', () => mv(i + 1)], ['⤓ Onderaan', () => mv(n - 1)]);
  openSheet(s.name, 'Plek ' + (i + 1) + ' van ' + n + ' · ook de volgorde in de auto', acts);
}
function enterRadio(){
  rdFavs = rdJson(Android.radioFavorites(), []);
  if (!rdStations.length) rdStations = rdJson(Android.radioCache(), []);
  rdRender();
  if (!$('#rd-search').value.trim()) Android.radioLoad('');
  rdPollOnce(); stopRdPoll(); rdPoll = setInterval(rdPollOnce, 1000);
}
function stopRdPoll(){ if (rdPoll) { clearInterval(rdPoll); rdPoll = null; } }
window.onRadioStations = function(d){
  if ((d.q || '') !== $('#rd-search').value.trim()) return; // verouderd antwoord
  if (d.error) { if (!rdStations.length) $('#rd-list').innerHTML = '<p class=note style="padding:14px">' + esc(d.error) + '. <a href="#" onclick="Android.radioLoad($(\'#rd-search\').value.trim());return false">Opnieuw</a></p>'; return; }
  rdStations = d.stations || [];
  rdRender();
};
function rdRender(){
  $('#rd-favs-wrap').style.display = rdFavs.length ? 'block' : 'none';
  $('#rd-favs').innerHTML = rdFavs.map((s, i) => rdRow(s, 'f', i)).join('');
  const q = $('#rd-search').value.trim();
  $('#rd-list-label').textContent = q ? 'Zoekresultaten' : 'Populair in Nederland';
  $('#rd-list').innerHTML = rdStations.map((s, i) => rdRow(s, 's', i)).join('') ||
    '<p class=note style="padding:14px">' + (q ? 'Geen zender gevonden.' : 'Zenders laden…') + '</p>';
}
function rdSearchDo(){
  clearTimeout(rdTmr);
  rdTmr = setTimeout(() => { const q = $('#rd-search').value.trim(); $('#rd-list').innerHTML = '<p class=note style="padding:14px">Zoeken…</p>'; Android.radioLoad(q); }, 400);
}
function rdGet(list, i){ return list === 'f' ? rdFavs[i] : rdStations[i]; }
function rdPlay(list, i){ const s = rdGet(list, i); if (!s) return; Android.radioPlay(JSON.stringify(s)); rdState = { status: 'connecting', station: s, title: '' }; rdShowNow(); rdRender(); window.scrollTo({ top: 0, behavior: 'smooth' }); }
function rdFav(list, i){ const s = rdGet(list, i); if (!s) return; const on = Android.radioToggleFav(JSON.stringify(s)); toast(on ? '★ ' + s.name + ' toegevoegd aan favorieten' : s.name + ' uit favorieten gehaald'); rdFavs = rdJson(Android.radioFavorites(), []); rdRender(); rdShowNow(); }
function rdFavNow(){ const s = rdState.station || rdState.last; if (!s) return; Android.radioToggleFav(JSON.stringify(s)); rdFavs = rdJson(Android.radioFavorites(), []); rdRender(); rdShowNow(); }
function rdPlayPause(){ if (rdState.status === 'playing' || rdState.status === 'connecting') Android.radioPause(); else Android.radioResume(); setTimeout(rdPollOnce, 200); }
function rdPollOnce(){
  const sig = st => JSON.stringify([st.status, st.title, st.station && st.station.url, st.info, (st.recent || []).length, st.sleepAt, Math.floor(Date.now() / 6e4), st.shift]);
  // Lijsten alleen opnieuw opbouwen als zender of afspeelstatus verandert (anders verspringen logo's en scrollpositie)
  const lsig = st => JSON.stringify([st.status, st.station && st.station.url]);
  const prev = sig(rdState), lprev = lsig(rdState);
  rdState = rdJson(Android.radioState(), {});
  if (sig(rdState) !== prev) { rdShowNow(); if (current === 'radio' && lsig(rdState) !== lprev) rdRender(); }
  const t = $('#tile-radio-sub');
  if (t) t.textContent = rdState.station && rdState.status === 'playing' ? '▶ ' + rdState.station.name + (rdState.title ? ' · ' + rdState.title : '') : 'Nederlandse zenders luisteren';
  const tile = $('#tile-radio'); if (tile) tile.classList.toggle('live', rdState.status === 'playing');
}
function rdShowNow(){
  const s = rdState.station || rdState.last;
  $('#rd-now').style.display = s ? 'block' : 'none';
  if (!s) return;
  const logoKey = (s.url || '') + '|' + (s.logo || '');
  if (rdShowNow.logoKey !== logoKey) { rdShowNow.logoKey = logoKey; $('#rd-now-logo').outerHTML = rdLogo(s).replace('<span class=logo>', '<span class=logo id="rd-now-logo">'); }
  $('#rd-now-name').textContent = s.name;
  const st = rdState.status || 'stopped';
  const inf = rdState.info || {};
  $('#rd-now-title').textContent = st === 'connecting' ? 'Verbinden…' : st === 'error' ? (rdState.error || 'Zender niet te bereiken')
    : st === 'paused' ? 'Gepauzeerd' : st === 'stopped' ? 'Tik op afspelen' : (inf.desc || inf.genre || 'Live');
  rdShowSong(st === 'playing' ? rdState.title : '', inf);
  rdShowInfo(s, inf);
  rdShowRecent(rdState.recent || []);
  $('#rd-now-title').classList.toggle('err', st === 'error');
  $('#rd-pp').textContent = st === 'playing' || st === 'connecting' ? '❚❚ Pauze' : '▶ Afspelen';
  $('#rd-now-fav').textContent = rdIsFav(s) ? '★' : '☆';
  $('#rd-rec').disabled = st !== 'playing';
  rdShowSleep(); rdShowShift();
  $('#rd-ts').checked = Android.radioTimeshift();
}
/** Het huidige nummer (of programma) zoals de zender het meestuurt. */
function rdShowSong(t, inf){
  const box = $('#rd-song');
  if (!t) { box.style.display = 'none'; return; }
  box.style.display = 'block';
  const q = encodeURIComponent(t);
  box.innerHTML = (inf.song ? '<b>' + esc(inf.song) + '</b><span>' + esc(inf.artist) + '</span>' : '<b>' + esc(t) + '</b><span>Nu op ' + esc((rdState.station || {}).name || 'de radio') + '</span>') +
    '<div class=lnk><button class=mini data-u="https://music.youtube.com/search?q=' + q + '" onclick="Android.openUrl(this.dataset.u)">YouTube</button>' +
    '<button class=mini data-u="https://open.spotify.com/search/' + q + '" onclick="Android.openUrl(this.dataset.u)">Spotify</button>' +
    (inf.streamUrl && /^https?:\/\//i.test(inf.streamUrl) ? '<button class=mini data-u="' + esc(inf.streamUrl) + '" onclick="Android.openUrl(this.dataset.u)">Meer info</button>' : '') + '</div>';
}
function rdShowInfo(s, inf){
  const rows = [];
  if (inf.name && inf.name !== s.name) rows.push(['Naam', inf.name]);
  if (inf.desc) rows.push(['Omschrijving', inf.desc]);
  if (inf.genre || s.tags) rows.push(['Genre', inf.genre || s.tags]);
  const br = inf.br || (s.bitrate ? String(s.bitrate) : '');
  if (br || s.codec) rows.push(['Kwaliteit', [s.codec, br ? br + ' kbps' : ''].filter(Boolean).join(' · ')]);
  const site = /^https?:\/\//i.test(inf.site || '') ? inf.site : (/^https?:\/\//i.test(s.home || '') ? s.home : '');
  $('#rd-info').style.display = rows.length || site ? 'block' : 'none';
  $('#rd-info-dl').innerHTML = rows.map(r => '<dt>' + esc(r[0]) + '</dt><dd>' + esc(r[1]) + '</dd>').join('') +
    (site ? '<dt>Website</dt><dd><a href="#" data-u="' + esc(site) + '" onclick="Android.openUrl(this.dataset.u);return false">' + esc(site.replace(/^https?:\/\/(www\.)?/i, '').replace(/\/$/, '')) + '</a></dd>' : '');
}
function rdShowRecent(l){
  $('#rd-recent-wrap').style.display = l.length ? 'block' : 'none';
  $('#rd-recent-sum').textContent = 'Eerder gedraaid (' + l.length + ')';
  $('#rd-recent').innerHTML = l.map(x => '<li data-t="' + esc(x.title) + '" onclick="rdRecentTap(this.dataset.t)"><time>' +
    new Date(x.t).toLocaleTimeString('nl-NL', {hour:'2-digit', minute:'2-digit'}) + '</time><span>' + esc(x.title) + '</span></li>').join('');
}
function rdRecentTap(t){
  const q = encodeURIComponent(t);
  openSheet(t, 'Eerder gedraaid op ' + ((rdState.station || {}).name || 'de radio'), [
    ['Zoeken op YouTube', () => Android.openUrl('https://music.youtube.com/search?q=' + q)],
    ['Zoeken op Spotify', () => Android.openUrl('https://open.spotify.com/search/' + q)],
  ]);
}
function rdPinStation(){
  const s = rdState.station || rdState.last; if (!s) return;
  const e = Android.shortcutPinStation(JSON.stringify(s));
  toast(e || 'Bevestig op je startscherm om ' + s.name + ' toe te voegen');
}
/** Slaaptimer: vaste tijden, eigen aantal minuten of tot een tijdstip. */
function rdSleep(v){
  if (v === 'custom') {
    askInput('Slaaptimer', 'Na hoeveel minuten moet de radio stoppen?', '20', 'Instellen', x => {
      const m = parseInt(x, 10);
      if (!(m > 0 && m <= 720)) { toast('Kies 1 tot 720 minuten'); return; }
      Android.radioSleep(m); setTimeout(rdPollOnce, 200);
    });
  } else if (v === 'at') {
    const d = new Date(Date.now() + 60 * 6e4);
    askInput('Slaaptimer', 'Hoe laat moet de radio stoppen? (bijv. 23:30)', String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'), 'Instellen', x => {
      const m = /^(\d{1,2})[:.](\d{2})$/.exec(x.trim());
      if (!m || +m[1] > 23 || +m[2] > 59) { toast('Gebruik uu:mm, bijvoorbeeld 23:30'); return; }
      const t = new Date(); t.setHours(+m[1], +m[2], 0, 0);
      if (t.getTime() <= Date.now()) t.setDate(t.getDate() + 1);
      Android.radioSleep(Math.max(1, Math.ceil((t.getTime() - Date.now()) / 6e4))); setTimeout(rdPollOnce, 200);
    });
  } else Android.radioSleep(+v);
  setTimeout(rdPollOnce, 200);
}
function rdShowSleep(){
  const el = $('#rd-sleepinfo');
  $('#rd-sleep').value = '0';
  if (!rdState.sleepAt) { el.innerHTML = ''; return; }
  const left = Math.max(0, Math.round((rdState.sleepAt - Date.now()) / 6e4));
  el.innerHTML = '⏾ Stopt om ' + new Date(rdState.sleepAt).toLocaleTimeString('nl-NL', {hour:'2-digit', minute:'2-digit'}) +
    ' (nog ' + (left >= 60 ? Math.floor(left / 60) + ' u ' + (left % 60) + ' min' : left + ' min') + ') · <a href="#" onclick="Android.radioSleep(0);setTimeout(rdPollOnce,200);return false">uitzetten</a>';
}
function rdRecognize(){
  const e = Android.musicStartRadio();
  if (e) { toast(e); if (e.includes('sleutel')) jumpTo('music'); return; }
  jumpTo('music');
}

