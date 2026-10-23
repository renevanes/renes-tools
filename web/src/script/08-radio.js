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
  const cur = rdIsCur(s);
  const tags = (s.tags || '').split(',').filter(Boolean).slice(0, 3).join(', ');
  return '<div class="srow' + (cur ? ' playing' : '') + '"><button class=sbtn onclick="rdPlay(\'' + list + '\',' + i + ')">' + rdLogo(s) +
    '<span class=stxt><b>' + esc(s.name) + '</b><small>' + esc(tags || (s.codec ? s.codec + (s.bitrate ? ' ' + s.bitrate + ' kbps' : '') : '')) + '</small></span>' +
    (cur ? '<span class=eq><i></i><i></i><i></i></span>' : '') + '</button>' +
    '<button class=star onclick="rdFav(\'' + list + '\',' + i + ')" aria-label="' + (rdIsFav(s) ? esc(s.name) + ' uit favorieten' : esc(s.name) + ' bij favorieten') + '" aria-pressed="' + rdIsFav(s) + '">' + (rdIsFav(s) ? '★' : '☆') + '</button></div>';
}
/* Eén favoriet: afspelen, verplaatsen (ook de volgorde in de auto en in de widget), weghalen. */
function rdFavMenu(i){
  const s = rdFavs[i]; if (!s) return;
  const n = rdFavs.length, mv = to => { Android.radioMoveFavTo(i, to); rdFavs = rdJson(Android.radioFavorites(), []); rdRender(); };
  const acts = [['Afspelen', () => rdPlay('f', i)]];
  if (i > 0) acts.push(['⤒ Bovenaan', () => mv(0)], ['↑ Eén naar voren', () => mv(i - 1)]);
  if (i < n - 1) acts.push(['↓ Eén naar achteren', () => mv(i + 1)], ['⤓ Achteraan', () => mv(n - 1)]);
  acts.push(['Op startscherm', () => { const e = Android.shortcutPinStation(JSON.stringify(s)); toast(e || 'Bevestig op je startscherm om ' + s.name + ' toe te voegen'); }]);
  acts.push(['☆ Uit favorieten', () => rdFav('f', i)]);
  openSheet(s.name, 'Plek ' + (i + 1) + ' van ' + n + ' · ook de volgorde in de auto', acts);
}
/* Favoriet als voorkeuzetoets: tikken = afspelen */
function rdFavTile(s, i){
  const cur = rdIsCur(s);
  return '<div class="rdfav' + (cur ? ' playing' : '') + '"><button class="rdfavb" onclick="rdPlay(\'f\',' + i + ')" aria-label="' + esc(s.name) + (cur ? ', speelt nu' : ' afspelen') + '">' +
    (cur ? '<span class=eq aria-hidden="true"><i></i><i></i><i></i></span>' : '') + rdLogo(s) + '<b>' + esc(s.name) + '</b></button>' +
    '<button class="rdfavm" onclick="rdFavMenu(' + i + ')" aria-label="Meer over ' + esc(s.name) + '">⋯</button></div>';
}
function rdIsCur(s){ return !!(rdState.station && (rdState.station.id ? rdState.station.id === s.id : rdState.station.url === s.url) && rdState.status !== 'stopped'); }

/* Twee tabbladen: Favorieten en Zenders */
let rdTab = 'favs', rdTabUser = false;
function rdSetTab(t, byUser){
  if (t !== 'favs' && t !== 'all') t = 'favs';
  if (byUser) rdTabUser = true;
  const changed = rdTab !== t; rdTab = t;
  document.querySelectorAll('#s-radio .pctabs [role=tab]').forEach(b => { const on = b.dataset.tab === t; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
  $('#rd-p-favs').hidden = t !== 'favs'; $('#rd-p-all').hidden = t !== 'all';
  const a = document.activeElement;
  if (a && a.closest && a.closest('#s-radio [role=tabpanel][hidden]')) $('#rd-tab-' + t).focus({ preventScroll: true });
  if (changed && byUser && scrollY > 0) scrollTo(0, 0);
}
document.querySelector('#s-radio .pctabs').addEventListener('keydown', e => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
  const tabs = [...e.currentTarget.querySelectorAll('[role=tab]')], at = tabs.indexOf(document.activeElement); if (at < 0) return;
  e.preventDefault();
  const i = e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : (at + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
  rdSetTab(tabs[i].dataset.tab, true); tabs[i].focus({ preventScroll: true });
});
function rdMenu(){
  openSheet('Radio', '', [
    ['Radiowekker', () => show('ralarm')],
    ['Herkende muziek', () => show('music')]
  ]);
}
/* Radiowekker in één regel */
function rdAlarmSub(){
  const el = $('#rd-alarm-sub'); if (!el) return;
  const a = rdJson(Android.alarmState(), {});
  el.textContent = a.on ? 'Aan · ' + String(a.hour != null ? a.hour : 7).padStart(2, '0') + ':' + String(a.minute || 0).padStart(2, '0') + (a.station && a.station.name ? ' · ' + a.station.name : '') : 'Uit';
}

/* Genres: vaste groepen die op de tags van radio-browser passen (alleen als er minstens 2 zenders in zitten) */
const RD_GENRES = [
  ['Pop', /pop|hits|top ?40|charts/], ['Nieuws en praat', /news|talk|nieuws|sport/], ['Nederlandstalig', /dutch|nederlands|levenslied|hollands/],
  ['Dance', /dance|edm|house|techno|trance|hardstyle/], ['Rock', /rock|metal|alternative|indie/], ['Jaren 60–90', /60s|70s|80s|90s|oldies|classic hits|gold/],
  ['Klassiek', /classical|klassiek|opera/], ['Jazz en soul', /jazz|soul|blues|funk|r&b|rnb/], ['Regionaal', /regional|regio|local|lokaal|omroep/]];
let rdGenre = '', rdShown = [], rdListN = 20;
function rdGenreOf(name){ const g = RD_GENRES.find(x => x[0] === name); return g ? g[1] : null; }
function rdRenderGenres(){
  const box = $('#rd-genres'); if (!box) return;
  const have = RD_GENRES.filter(g => rdStations.filter(s => g[1].test(String(s.tags || '').toLowerCase())).length >= 2).map(g => g[0]);
  if (rdGenre && !have.includes(rdGenre)) rdGenre = '';
  box.style.display = have.length ? '' : 'none';
  box.innerHTML = ['', ...have].map(g => '<button role="radio" aria-checked="' + (g === rdGenre) + '"' + (g === rdGenre ? ' class="on"' : '') + ' data-g="' + esc(g) + '" onclick="rdPickGenre(this.dataset.g)">' + esc(g || 'Alles') + '</button>').join('');
}
function rdPickGenre(g){ rdGenre = g; rdListN = 20; rdTabUser = true; rdRender(); }
function rdListMore(){ rdListN += 20; rdRender(); }
function enterRadio(){
  rdFavs = rdJson(Android.radioFavorites(), []);
  if (!rdStations.length) rdStations = rdJson(Android.radioCache(), []);
  // De eerste keer: Favorieten als je die hebt, anders alle zenders; daarna blijft het tabblad waar je was
  if (!rdTabUser) rdSetTab(rdFavs.length ? 'favs' : 'all', true); else rdSetTab(rdTab);
  rdAlarmSub();
  rdRender();
  if (!$('#rd-search').value.trim()) Android.radioLoad('');
  rdPollOnce(); // verder houdt de speler (39-speler.js) alles elke seconde bij
}
function stopRdPoll(){ if (rdPoll) { clearInterval(rdPoll); rdPoll = null; } }
window.onRadioStations = function(d){
  if ((d.q || '') !== $('#rd-search').value.trim()) return; // verouderd antwoord
  // Fout: altijd laten zien (anders blijft "Zoeken…" staan), met de vorige resultaten eronder
  if (d.error) { rdRender(); $('#rd-list').insertAdjacentHTML('afterbegin', '<p class=note style="padding:14px">' + esc(d.error) + '. <a href="#" onclick="Android.radioLoad($(\'#rd-search\').value.trim());return false">Opnieuw</a></p>'); return; }
  rdStations = d.stations || [];
  rdListN = 20;
  rdRender();
};
function rdRender(){
  $('#rd-favs-wrap').style.display = rdFavs.length ? 'block' : 'none';
  $('#rd-favs-empty').style.display = rdFavs.length ? 'none' : '';
  $('#rd-favs').innerHTML = rdFavs.map((s, i) => rdFavTile(s, i)).join('');
  $('#rd-tab-favs-n').textContent = rdFavs.length ? String(rdFavs.length) : '';
  const q = $('#rd-search').value.trim();
  rdRenderGenres();
  const re = rdGenreOf(rdGenre);
  const all = re ? rdStations.filter(s => re.test(String(s.tags || '').toLowerCase())) : rdStations;
  rdShown = all.slice(0, rdListN);
  $('#rd-list-label').textContent = (q ? 'Zoekresultaten' : 'Populair in Nederland') + (rdGenre ? ' · ' + rdGenre : '') + (all.length > rdShown.length ? ' (' + all.length + ')' : '');
  $('#rd-list').innerHTML = rdShown.map((s, i) => rdRow(s, 's', i)).join('') ||
    '<p class=note style="padding:14px">' + (q ? 'Geen zender gevonden.' : rdStations.length ? 'Geen zenders in dit genre.' : 'Zenders laden…') + '</p>';
  $('#rd-list-more').hidden = all.length <= rdShown.length;
}
function rdSearchDo(){
  rdTabUser = true; // je zoekt: bij terugkomen blijf je bij Zenders
  clearTimeout(rdTmr);
  rdTmr = setTimeout(() => { const q = $('#rd-search').value.trim(); $('#rd-list').innerHTML = '<p class=note style="padding:14px">Zoeken…</p>'; Android.radioLoad(q); }, 400);
}
function rdGet(list, i){ return list === 'f' ? rdFavs[i] : rdShown[i]; }
function rdPlay(list, i){ const s = rdGet(list, i); if (!s) return; Android.radioPlay(JSON.stringify(s)); rdState = { status: 'connecting', station: s, title: '' }; plHintSrc('radio'); rdShowNow(); rdRender(); plRender(); }
function rdFav(list, i){ const s = rdGet(list, i); if (!s) return; const on = Android.radioToggleFav(JSON.stringify(s)); toast(on ? '★ ' + s.name + ' toegevoegd aan favorieten' : s.name + ' uit favorieten gehaald'); rdFavs = rdJson(Android.radioFavorites(), []); rdRender(); plRender(); }
function rdFavNow(){ const s = rdState.station || rdState.last; if (!s) return; const on = Android.radioToggleFav(JSON.stringify(s)); toast(on ? '★ ' + s.name + ' bij je favorieten' : s.name + ' uit favorieten'); rdFavs = rdJson(Android.radioFavorites(), []); if (current === 'radio') rdRender(); plRender(); }
function rdPlayPause(){ if (rdState.status === 'playing' || rdState.status === 'connecting') Android.radioPause(); else Android.radioResume(); setTimeout(rdPollOnce, 200); }
/** noRender: de speler wordt zo toch getekend (plTick) */
function rdPollOnce(noRender){
  const sig = st => JSON.stringify([st.status, st.title, st.station && st.station.url, st.info, (st.recent || []).length, st.sleepAt, Math.floor(Date.now() / 6e4), st.shift]);
  // Lijsten alleen opnieuw opbouwen als zender of afspeelstatus verandert (anders verspringen logo's en scrollpositie)
  const lsig = st => JSON.stringify([st.status, st.station && st.station.url]);
  const prev = sig(rdState), lprev = lsig(rdState);
  rdState = rdJson(Android.radioState(), {});
  if (sig(rdState) !== prev) { rdShowNow(); if (current === 'radio' && lsig(rdState) !== lprev) rdRender(); }
  const t = $('#tile-radio-sub');
  if (t) t.textContent = rdState.station && rdState.status === 'playing' ? '▶ ' + rdState.station.name + (rdState.title ? ' · ' + rdState.title : '') : 'Nederlandse zenders luisteren';
  const tile = $('#tile-radio'); if (tile) tile.classList.toggle('live', rdState.status === 'playing');
  if (noRender !== true && typeof plRender === 'function') plRender();
}
/** De radio-extra's in de speler: het nummer, eerder gedraaid, over de zender. De rest tekent de speler zelf. */
function rdShowNow(){
  const s = rdState.station || rdState.last;
  if (!s) return;
  const st = rdState.status || 'stopped', inf = rdState.info || {};
  rdShowSong(st === 'playing' ? rdState.title : '', inf);
  rdShowInfo(s, inf);
  rdShowRecent(rdState.recent || []);
  $('#rd-ts').checked = Android.radioTimeshift();
}
/** Het huidige nummer (of programma) zoals de zender het meestuurt. */
function rdShowSong(t, inf){
  const box = $('#rd-song');
  if (!t) { box.style.display = 'none'; return; }
  box.style.display = 'block';
  const q = encodeURIComponent(t);
  box.innerHTML = (inf.song ? '<b>' + esc(inf.song) + '</b><span>' + esc(inf.artist) + '</span>' : '<b>' + esc(t) + '</b><span>Nu op ' + esc((rdState.station || {}).name || 'de radio') + '</span>') +
    '<div class=lnk><button class=mini onclick="rdSaveSong()" aria-label="Nummer bewaren in Herkende muziek">★ Bewaar</button>' +
    '<button class=mini data-u="https://music.youtube.com/search?q=' + q + '" onclick="Android.openUrl(this.dataset.u)">YouTube</button>' +
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
/* Nummer van de radio bewaren in Herkende muziek (zonder AudD). "Artiest - Titel" wordt gesplitst. */
function rdSplit(t, inf){
  if (inf && inf.song) return [inf.artist || '', inf.song];
  const m = String(t || '').split(/\s+[-–]\s+/);
  return m.length >= 2 ? [m[0], m.slice(1).join(' - ')] : ['', String(t || '')];
}
function rdSaveSong(t, inf){
  if (t === undefined) { t = rdState.title; inf = rdState.info || {}; }
  const [artist, title] = rdSplit(t, inf);
  if (typeof Android.musicSaveFromRadio !== 'function') return;
  const e = Android.musicSaveFromRadio(artist, title, (rdState.station || {}).name || '');
  toast(e === 'dubbel' ? 'Staat al in Herkende muziek' : e ? e : '★ Bewaard in Herkende muziek');
}
function rdRecentTap(t){
  const q = encodeURIComponent(t);
  openSheet(t, 'Eerder gedraaid op ' + ((rdState.station || {}).name || 'de radio'), [
    ['★ Bewaren in Herkende muziek', () => rdSaveSong(t, null)],
    ['Zoeken op YouTube', () => Android.openUrl('https://music.youtube.com/search?q=' + q)],
    ['Zoeken op Spotify', () => Android.openUrl('https://open.spotify.com/search/' + q)],
  ]);
}
function rdPinStation(){
  const s = rdState.station || rdState.last; if (!s) return;
  const e = Android.shortcutPinStation(JSON.stringify(s));
  toast(e || 'Bevestig op je startscherm om ' + s.name + ' toe te voegen');
}
function rdRecognize(){
  const e = Android.musicStartRadio();
  if (e) { toast(e); if (e.includes('sleutel')) jumpTo('music'); return; }
  jumpTo('music');
}

