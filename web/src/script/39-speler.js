/* ---------- De speler: één voor radio en podcasts ----------
   Mini-balk onderaan op elk scherm; tikken of omhoog vegen opent hem op volledig scherm. De achtergrond krijgt de
   kleur van de hoes. Vegen op de hoes = volgende/vorige; Hierna = de wachtrij (podcasts) of je favorieten (radio). */
let plIsOpen = false, plSeeking = false, plHint = null, plQueueSig = '', plQueue = [], plBarSwiped = 0;
const plColors = {}, plColorAsked = {};

const PL_IC = {
  play: '<svg viewBox="0 0 24 24" width="100%" height="100%" fill="currentColor" aria-hidden="true"><path d="M8 5.6v12.8a1 1 0 0 0 1.52.85l10.2-6.4a1 1 0 0 0 0-1.7L9.52 4.75A1 1 0 0 0 8 5.6z"/></svg>',
  pause: '<svg viewBox="0 0 24 24" width="100%" height="100%" fill="currentColor" aria-hidden="true"><rect x="6" y="5" width="4.2" height="14" rx="1.3"/><rect x="13.8" y="5" width="4.2" height="14" rx="1.3"/></svg>',
  next: '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor" aria-hidden="true"><path d="M5.5 6.3v11.4a1 1 0 0 0 1.55.83l8.2-5.7a1 1 0 0 0 0-1.66l-8.2-5.7A1 1 0 0 0 5.5 6.3z"/><rect x="16.6" y="5.5" width="2.6" height="13" rx="1.1"/></svg>',
  prev: '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor" aria-hidden="true"><path d="M18.5 6.3v11.4a1 1 0 0 1-1.55.83l-8.2-5.7a1 1 0 0 1 0-1.66l8.2-5.7a1 1 0 0 1 1.55.83z"/><rect x="4.8" y="5.5" width="2.6" height="13" rx="1.1"/></svg>',
  back: n => '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M6.7 2.8v3.9h3.9"/><text x="12" y="15.3" font-size="7.4" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="system-ui,sans-serif">' + n + '</text></svg>',
  fwd: n => '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M17.3 2.8v3.9h-3.9"/><text x="12" y="15.3" font-size="7.4" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="system-ui,sans-serif">' + n + '</text></svg>',
  star: on => '<svg viewBox="0 0 24 24" width="26" height="26" fill="' + (on ? 'currentColor' : 'none') + '" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M12 3.2l2.7 5.6 6.1.8-4.5 4.2 1.1 6.1L12 17l-5.4 2.9 1.1-6.1-4.5-4.2 6.1-.8z"/></svg>'
};

function plSetText(el, t){ t = t == null ? '' : String(t); if (el.textContent !== t) el.textContent = t; }
function plSetHtml(el, h){ if (el._h !== h) { el._h = h; el.innerHTML = h; } }

/* Wat er nu in de speler hoort: wat net gestart is (even), anders wat de app zegt, anders wat er is. */
function plSrc(){
  const radioHas = !!(rdState && (rdState.station) && rdState.status && rdState.status !== 'stopped');
  const podHas = !!(pcSt && pcSt.ep && pcSt.ep.url && pcSt.status !== 'stopped');
  let want = plHint && Date.now() - plHint.t < 4000 ? plHint.src : (Android.playerSrc() || '');
  if (want === 'radio' && radioHas) return 'radio';
  if (want === 'podcast' && podHas) return 'podcast';
  return podHas ? 'podcast' : radioHas ? 'radio' : '';
}
function plHintSrc(src){ plHint = { src, t: Date.now() }; }

/* Speelt het op een Chromecast? Dan komt alles van daar. */
let plCast = {}, plCastErr = '', plCastHint = null;
function plCastPoll(){
  plCast = rdJson(Android.castState(), {});
  // Na een verbroken verbinding: één keer melden (per sessie) en terug naar de telefoon
  if (!plCast.active && plCast.id) { const k = plCast.id + '|' + (plCast.error || ''); if (plCast.error && k !== plCastErr) { plCastErr = k; toast(plCast.error); } Android.castForget(); }
  // Net op ▶/❚❚ getikt: dat even laten zien tot de Chromecast het bevestigt
  if (plCast.active && plCastHint && Date.now() - plCastHint.t < 1500) plCast.playing = plCastHint.playing;
  return plCast;
}
function plModel(){
  const cs = plCast;
  if (cs.active) {
    const status = cs.status === 'connecting' ? 'connecting' : cs.status;
    const radio = cs.src === 'radio', st = cs.station || {};
    return { src: cs.src, status, playing: !!cs.playing, key: cs.key, title: cs.title || '', sub: cs.sub || '', kind: radio ? 'Radio' : 'Podcast',
      from: radio ? (st.name || 'Radio') : ((cs.pod && cs.pod.title) || 'Podcast'), art: pcImg(cs.art), ini: radio ? String(st.name || '?').replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '♪' : '🎧',
      pos: +cs.pos || 0, dur: +cs.dur || 0, error: cs.error, sleepAt: +cs.sleepAt || 0, sleepEnd: !!cs.sleepEnd, speed: cs.rate || 1,
      desc: (cs.ep && cs.ep.desc) || '', station: st, cast: cs.device, volume: cs.volume };
  }
  const src = plSrc();
  if (src === 'podcast') {
    const s = pcSt, e = s.ep || {}, p = s.pod || {}, status = s.status === 'idle' ? 'paused' : s.status;
    return { src, status, playing: status === 'playing' || status === 'connecting', key: e.key, title: e.title || 'Aflevering', sub: p.title || '',
      kind: 'Podcast', from: p.title || 'Podcast', art: pcImg(e.image) || pcImg(p.image), ini: '🎧', pos: +s.pos || 0, dur: +s.dur || 0,
      error: s.error, sleepAt: +s.sleepAt || 0, sleepEnd: !!s.sleepEnd, speed: s.speed || 1, desc: e.desc || '' };
  }
  if (src === 'radio') {
    const s = rdState, st = s.station || {}, inf = s.info || {}, status = s.status || 'stopped';
    const song = status === 'playing' ? (inf.song || s.title || '') : '';
    const sub = status === 'connecting' ? 'Verbinden…' : status === 'paused' ? 'Gepauzeerd · ' + (st.name || '') : status === 'error' ? (s.error || 'Zender niet te bereiken')
      : song ? [inf.song ? inf.artist : '', st.name].filter(Boolean).join(' · ') : (inf.desc || inf.genre || 'Live');
    const ini = String(st.name || '?').replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '♪';
    return { src, status, playing: status === 'playing' || status === 'connecting', key: st.url, title: song || st.name || 'Radio', sub,
      kind: 'Radio', from: st.name || 'Radio', art: pcImg(st.logo), ini, shift: s.shift, error: s.error, sleepAt: +s.sleepAt || 0, sleepEnd: false, station: st };
  }
  return null;
}

/* Kleur uit de hoes (de app rekent die uit); zonder plaatje een vaste kleur bij de naam. */
function plHashColor(s){ let h = 0; s = String(s || ''); for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return 'hsl(' + (Math.abs(h) % 360) + ',42%,30%)'; }
function plColorFor(m){
  if (m.art) {
    if (plColors[m.art]) return plColors[m.art];
    if (!plColorAsked[m.art]) { plColorAsked[m.art] = 1; Android.playerArt(m.art); }
  }
  return plHashColor(m.from + '|' + (m.src === 'podcast' ? '' : m.title));
}
window.onPlayerArt = function(r){
  if (!r || !r.url) return;
  const ok = /^#[0-9a-f]{6}$/i.test(r.color || '');
  plColors[r.url] = ok ? r.color : plHashColor(r.url);
  if (!ok) setTimeout(() => { delete plColors[r.url]; delete plColorAsked[r.url]; }, 120000); // geen netwerk? straks nog eens
  plRender();
};
function plSetArt(el, m){
  const k = (m.art || '') + '|' + m.ini;
  if (el.dataset.k === k) return;
  el.dataset.k = k;
  el.innerHTML = m.art ? '<img src="' + esc(m.art) + '" alt="" onerror="this.parentNode.textContent=' + jsq(m.ini) + '">' : esc(m.ini);
  el.classList.toggle('ini', !m.art);
}

function plRender(){
  plCastPoll();
  const m = plModel(), bar = $('#plbar');
  const showBar = !!m && !plIsOpen;
  bar.style.display = showBar ? 'flex' : 'none';
  document.body.classList.toggle('has-plbar', showBar);
  if (!m) { if (plIsOpen) plClose(); return; }
  const col = plColorFor(m);
  bar.style.setProperty('--pl', col); $('#player').style.setProperty('--pl', col);
  // Mini-balk
  plSetArt($('#plbar-art'), m);
  plSetText($('#plbar-t'), m.title);
  plSetText($('#plbar-s'), m.status === 'error' ? (m.error || 'Fout') : m.cast ? '📺 ' + m.cast + (m.sub && !/^Op /.test(m.sub) ? ' · ' + m.sub : '') : m.sub || m.from);
  const pp = m.playing ? 'pause' : 'play', ppLabel = m.playing ? 'Pauze' : 'Afspelen';
  for (const b of [$('#plbar-pp'), $('#pl-pp')]) { plSetHtml(b, PL_IC[pp]); if (b.getAttribute('aria-label') !== ppLabel) b.setAttribute('aria-label', ppLabel); }
  plSetHtml($('#plbar-next'), PL_IC.next);
  $('#plbar-next').setAttribute('aria-label', m.src === 'radio' ? 'Volgende zender' : 'Volgende aflevering');
  $('#plbar-open').setAttribute('aria-label', 'Speler openen: ' + m.title + (m.sub ? ', ' + m.sub : ''));
  const pct = m.src === 'podcast' && m.dur > 0 ? Math.min(100, m.pos / m.dur * 100) : 0;
  $('#plbar-prog').style.width = pct.toFixed(2) + '%';
  $('#plbar-prog').parentNode.style.visibility = m.src === 'podcast' ? 'visible' : 'hidden'; // radio: live, geen voortgang
  if (!plIsOpen) return;

  // Volledig scherm
  plSetText($('#pl-kind'), m.src === 'podcast' ? 'Podcast' : 'Radio');
  plSetText($('#pl-from'), m.from);
  plSetArt($('#pl-art'), m);
  $('#pl-cover').setAttribute('aria-label', 'Hoes van ' + m.title + '. Veeg naar links voor ' + (m.src === 'radio' ? 'de volgende zender' : 'de volgende aflevering') + ', naar rechts voor de vorige.');
  plSetText($('#pl-title'), m.title);
  plSetText($('#pl-sub'), m.sub);
  $('#pl-sub').classList.toggle('bad', m.status === 'error');
  const fav = $('#pl-fav');
  fav.style.visibility = m.src === 'radio' ? 'visible' : 'hidden';
  if (m.src === 'radio') { const on = rdIsFav(m.station); plSetHtml(fav, PL_IC.star(on)); fav.setAttribute('aria-label', on ? 'Uit favorieten' : 'Bij favorieten'); fav.classList.toggle('on', on); }

  // Plek: podcast = aflevering; radio = de buffer (terugspoelen), anders "live"
  const sh = m.src === 'radio' && m.shift && m.status !== 'stopped' ? m.shift : null;
  const behind = sh ? +sh.behind || 0 : 0, atLive = !sh || behind <= 8;
  const seekable = m.src === 'podcast' ? m.dur > 0 : !!sh;
  $('#pl-seekwrap').style.display = m.src === 'podcast' || sh ? '' : 'none';
  $('#pl-livebadge').style.display = m.src === 'radio' && !sh ? '' : 'none';
  $('#pl-livebadge').classList.toggle('on', m.playing);
  const seek = $('#pl-seek');
  seek.disabled = !seekable;
  if (!plSeeking) {
    if (m.src === 'podcast') {
      seek.value = m.dur > 0 ? Math.round(m.pos / m.dur * 1000) : 0;
      plSetText($('#pl-pos'), fmtClock(m.pos));
      plSetText($('#pl-left'), m.dur > 0 ? '−' + fmtClock(m.dur - m.pos) : '');
      seek.setAttribute('aria-label', 'Plek in de aflevering');
    } else if (sh) {
      const total = Math.max(1, (+sh.back || 0) + behind);
      seek.value = Math.round((+sh.back || 0) / total * 1000);
      plSetText($('#pl-pos'), '−' + fmtBehind(total));
      plSetText($('#pl-left'), atLive ? 'Live' : '−' + fmtBehind(behind));
      seek.setAttribute('aria-label', 'Plek in de buffer (terugspoelen)');
    }
  }
  $('#pl-left').classList.toggle('live', m.src === 'radio' && atLive);
  plSetText($('#pl-state'), m.status === 'connecting' ? 'Laden…' : m.status === 'error' && m.src === 'podcast' ? (m.error || 'Fout') : m.status === 'ended' ? 'Afgelopen' : '');
  $('#pl-state').classList.toggle('bad', m.status === 'error');

  // Knoppen
  const back = $('#pl-back'), fwd = $('#pl-fwd');
  if (m.src === 'podcast') {
    plSetHtml(back, PL_IC.back(15)); back.setAttribute('aria-label', '15 seconden terug');
    plSetHtml(fwd, PL_IC.fwd(30)); fwd.setAttribute('aria-label', '30 seconden vooruit');
    back.style.visibility = fwd.style.visibility = 'visible'; back.disabled = fwd.disabled = false;
  } else {
    plSetHtml(back, PL_IC.back(30)); back.setAttribute('aria-label', '30 seconden terug');
    plSetHtml(fwd, PL_IC.fwd(30)); fwd.setAttribute('aria-label', '30 seconden vooruit');
    back.style.visibility = fwd.style.visibility = sh ? 'visible' : 'hidden';
    back.disabled = !sh; fwd.disabled = !sh || atLive;
  }
  plSetHtml($('#pl-prev'), PL_IC.prev); plSetHtml($('#pl-next'), PL_IC.next);
  $('#pl-prev').setAttribute('aria-label', m.src === 'radio' ? 'Vorige zender' : 'Vorige (of terug naar het begin)');
  $('#pl-next').setAttribute('aria-label', m.src === 'radio' ? 'Volgende zender' : 'Volgende aflevering');

  // Waar het speelt
  $('#pl-castbar').style.display = m.cast ? '' : 'none';
  if (m.cast) plSetText($('#pl-castbar'), '📺 Speelt op ' + m.cast);
  plSetText($('#pl-out'), m.cast ? '📺 ' + m.cast : '🔈 Deze telefoon');
  $('#pl-out').classList.toggle('on', !!m.cast);
  $('#pl-volwrap').style.display = m.cast && m.volume >= 0 ? '' : 'none';
  if (m.cast && m.volume >= 0 && document.activeElement !== $('#pl-vol')) $('#pl-vol').value = Math.round(m.volume * 20);
  // Opties
  $('#pl-speed').style.display = m.src === 'podcast' ? '' : 'none';
  plSetText($('#pl-speed'), pcSpeedTxt(m.speed));
  $('#pl-golive').style.display = sh && !atLive ? '' : 'none';
  const sl = $('#pl-sleep');
  const slTxt = m.sleepAt ? '⏾ nog ' + fmtMin((m.sleepAt - Date.now()) / 6e4) : m.sleepEnd ? '⏾ na deze aflevering' : '⏾ Slaaptimer';
  plSetText(sl, slTxt); sl.classList.toggle('on', !!(m.sleepAt || m.sleepEnd));
  plSetHtml($('#pl-sleepinfo'), m.sleepAt ? 'Stopt om ' + new Date(m.sleepAt).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }) + ' · <a href="#" onclick="plSleepOff();return false">uitzetten</a>'
    : m.sleepEnd ? 'Stopt aan het einde van deze aflevering · <a href="#" onclick="plSleepOff();return false">uitzetten</a>' : '');

  // Extra's
  $('#pl-radio').style.display = m.src === 'radio' && !m.cast ? '' : 'none';
  if (m.src === 'radio') { $('#rd-rec').disabled = m.status !== 'playing'; }
  $('#pl-about').style.display = m.src === 'podcast' && m.desc ? '' : 'none';
  if (m.src === 'podcast') plSetText($('#pl-desc'), m.desc);
  plRenderQueue(m);
}

/* Hierna: podcasts = wachtrij; radio = favorieten (tik = die zender) */
function plRenderQueue(m, force){
  const sig = m.src + '|' + m.key + '|' + (m.src === 'radio' ? JSON.stringify(rdFavs.map(f => f.url)) : '');
  if (!force && sig === plQueueSig) return;
  plQueueSig = sig;
  const box = $('#pl-queue');
  if (m.src === 'podcast') {
    plQueue = rdJson(Android.podQueue(), []);
    plSetText($('#pl-qtitle'), 'Hierna');
    $('#pl-qclear').style.display = plQueue.length > 1 ? '' : 'none';
    box.innerHTML = plQueue.map((r, i) => {
      const e = r.ep || {}, p = r.pod || {};
      return '<div class="pl-qrow"><button class="pl-qb" onclick="plQueuePlay(' + i + ')">' + pcArt(e.image || p.image) +
        '<span class="pltxt"><b>' + esc(e.title || 'Aflevering') + '</b><small>' + esc(p.title || '') + (e.dur ? ' · ' + esc(fmtMin(e.dur / 60)) : '') + '</small></span></button>' +
        '<button class="pl-ic" onclick="plQueueMenu(' + i + ')" aria-label="Meer over ' + esc(e.title || 'aflevering') + '"><svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg></button></div>';
    }).join('') || '<p class="pl-empty">Nog niets. Kies bij een aflevering ⋯ → <b>Als volgende afspelen</b> of <b>Toevoegen aan Hierna</b>. Zonder Hierna gaat ⏭ naar de volgende aflevering van deze podcast.</p>';
  } else {
    plSetText($('#pl-qtitle'), 'Je zenders');
    $('#pl-qclear').style.display = 'none';
    const cur = m.station && m.station.url;
    box.innerHTML = rdFavs.map((s, i) => '<div class="pl-qrow' + (s.url === cur ? ' cur' : '') + '"><button class="pl-qb" onclick="plFavPlay(' + i + ')"' + (s.url === cur ? ' aria-current="true"' : '') + '>' + rdLogo(s) +
      '<span class="pltxt"><b>' + esc(s.name) + '</b><small>' + (s.url === cur ? 'Speelt nu' : esc((s.tags || '').split(',').filter(Boolean).slice(0, 2).join(', '))) + '</small></span></button></div>').join('') ||
      '<p class="pl-empty">Tik op ☆ om zenders bij je favorieten te zetten; dan wissel je hier en met vegen op het logo snel tussen je zenders.</p>';
  }
}
function plQueuePlay(i){
  const r = plQueue[i]; if (!r || !r.ep) return;
  const e = Android.podQueuePlay(r.ep.key); // op sleutel: de lijst kan intussen veranderd zijn
  if (e) { toast(e); plQueueSig = ''; plRender(); return; }
  pcSt = { status: 'connecting', ep: r.ep, pod: r.pod, pos: 0, dur: (+r.ep.dur || 0) * 1000, speed: pcSt.speed || 1 }; plHintSrc('podcast');
  plQueueSig = ''; plRender(); setTimeout(plTick, 400);
}
function plQueueMenu(i){
  const r = plQueue[i]; if (!r) return;
  const acts = [['Nu afspelen', () => plQueuePlay(i)]];
  if (i > 0) acts.push(['⤒ Bovenaan (als volgende)', () => { Android.podQueueMove(r.ep.key, 0); plQueueSig = ''; plRender(); }]);
  if (i < plQueue.length - 1) acts.push(['↓ Eén omlaag', () => { Android.podQueueMove(r.ep.key, i + 1); plQueueSig = ''; plRender(); }]);
  acts.push(['Uit Hierna halen', () => { Android.podQueueRemove(r.ep.key); plQueueSig = ''; plRender(); }]);
  openSheet(r.ep.title || 'Aflevering', (r.pod && r.pod.title) || '', acts);
}
function plQueueClear(){ askConfirm('Hierna wissen?', 'Alle ' + plQueue.length + ' afleveringen gaan uit Hierna (niet uit de podcast).', 'Wissen', () => { Android.podQueueClear(); plQueueSig = ''; plRender(); }); }
function plFavPlay(i){ const s = rdFavs[i]; if (!s) return; Android.radioPlay(JSON.stringify(s)); rdState = { status: 'connecting', station: s, title: '' }; plHintSrc('radio'); plRender(); }
/* Vanuit een afleveringsmenu */
function plAddToQueue(ep, pod, next){
  const e = Android.podQueueAdd(JSON.stringify({ ep: pcSlimEp(ep), pod: pcSlimPod(pod) }), !!next);
  if (e) { toast(e); return; }
  toast(next ? '⏭ Speelt hierna' : '＋ In Hierna gezet');
  plQueueSig = ''; if (plIsOpen) plRender();
}

/* ---------- bediening ---------- */
function plPlayPause(){
  const m = plModel(); if (!m) return;
  if (m.cast) { Android.playerPlayPause(); plCastHint = { playing: !m.playing, t: Date.now() }; plRender(); setTimeout(plTick, 600); return; }
  if (m.src === 'podcast') pcPlayPause(); else rdPlayPause();
  plHintSrc(m.src);
  if (m.src === 'radio') { rdState.status = m.playing ? 'paused' : 'connecting'; }
  plRender();
}
function plNext(){ plStep(1); }
function plPrev(){ plStep(-1); }
function plStep(dir){
  const m = plModel(); if (!m) return;
  const e = dir > 0 ? Android.playerNext(m.src) : Android.playerPrev(m.src);
  if (e) { toast(e); plCoverBack(); return; }
  plHintSrc(m.src);
  plCoverIn(dir);
  setTimeout(plTick, 350); setTimeout(plTick, 900);
}
function plSkip(dir){
  const m = plModel(); if (!m) return;
  if (m.src === 'podcast') pcSkip(dir < 0 ? -15 : 30); else rdShift(dir < 0 ? 'rew' : 'fwd');
  plRender();
}
function plSleep(){ const m = plModel(); if (m) sleepOpen(m.src === 'radio' ? 'radio' : 'pod'); }
function plSleepOff(){ const m = plModel(); if (!m) return; if (m.src === 'radio') Android.radioSleep(0); else Android.podSleep(0); toast('Slaaptimer uit'); setTimeout(plTick, 200); }
function plStop(){
  const m = plModel(); if (!m) return;
  if (m.src === 'podcast') Android.podStop(); else Android.radioStop();
  plClose(); setTimeout(plTick, 300);
  setTimeout(() => { const h = document.querySelector('.screen.on h1'); if (h) h.focus({ preventScroll: true }); }, 350); // de balk is dan weg
}
function plFav(){ rdFavNow(); plQueueSig = ''; plRender(); }
function plGoSource(){
  const m = plModel(); if (!m) return;
  if (m.src === 'podcast') { if (pcSt.pod && pcSt.pod.feed) pdOpen(pcSt.pod); else show('podcasts'); }
  else show('radio');
}
function plMenu(){
  const m = plModel(); if (!m) return;
  if (m.src === 'podcast') { pcNowMenu(); return; }
  const acts = [['Naar Radio', () => show('radio')], ['📌 Zender op startscherm', () => rdPinStation()]];
  if (m.shift) acts.push(['💾 Fragment bewaren', () => rdClipMenu()]);
  acts.push(plFloatAct(), ['Stoppen', () => plStop()]);
  openSheet(m.from, 'Radio', acts);
}
/* Zwevende speler boven andere apps */
function plFloatAct(){
  const f = rdJson(Android.floatState(), {});
  return [f.on && f.allowed ? '🗗 Zwevende speler uitzetten' : '🗗 Zwevende speler (boven andere apps)', () => {
    const on = !(f.on && f.allowed), r = Android.floatSet(on);
    if (r === 'perm') toast('Zet "Weergeven over andere apps" aan voor Rene\'s Tools en kom terug');
    else toast(on ? 'Zwevende speler aan: je ziet hem zodra je de app verlaat' : 'Zwevende speler uit');
  }];
}

/* ---------- waar speelt het: telefoon, Bluetooth (Android) of een Chromecast ---------- */
let plCastDevs = [], plOutScanning = false;
function plOutput(){
  plOutScanning = true; Android.castScan();
  plOutSheet();
}
function plOutSheet(){
  const m = plModel(); if (!m) return;
  const acts = [];
  if (m.cast) acts.push(['📱 Terug naar deze telefoon', () => { Android.castStop(true); toast('Speelt weer op de telefoon'); setTimeout(plTick, 800); }],
                        ['■ Stoppen met casten', () => { Android.castStop(false); setTimeout(plTick, 500); }]);
  acts.push(['🎧 Bluetooth, koptelefoon of speaker…', () => { const e = Android.outputSwitcher(); if (e) toast(e); }]);
  plCastDevs.forEach((d, i) => { if (d.name !== m.cast) acts.push(['📺 ' + d.name + (d.model ? ' (' + d.model + ')' : ''), () => plCastTo(i)]); });
  openSheet('Waar speelt het?', plOutScanning ? 'Zoeken naar Chromecasts in je wifi…' : plCastDevs.length ? 'Chromecasts in je wifi' : 'Geen Chromecast gevonden (zelfde wifi?)', acts);
}
window.onCastDevices = function(r){
  plOutScanning = false;
  plCastDevs = (r && r.devices) || [];
  if ($('#sheet').classList.contains('on') && $('#sheet-title').textContent === 'Waar speelt het?') plOutSheet();
};
function plCastTo(i){
  const d = plCastDevs[i]; if (!d) return;
  const e = Android.castStart(d.id);
  if (e) { toast(e); return; }
  toast('📺 Verbinden met ' + d.name + '…');
  setTimeout(plTick, 500); setTimeout(plTick, 2000);
}

/* Schuif: podcast = plek in de aflevering, radio = plek in de buffer */
function plSeekDrag(){
  plSeeking = true;
  const m = plModel(); if (!m) return;
  const v = +$('#pl-seek').value / 1000;
  if (m.src === 'podcast') plSetText($('#pl-pos'), fmtClock(m.dur * v));
  else if (m.shift) { const total = (+m.shift.back || 0) + (+m.shift.behind || 0), b = Math.round(total * (1 - v)); plSetText($('#pl-left'), b <= 8 ? 'Live' : '−' + fmtBehind(b)); }
}
function plSeekDone(){
  plSeeking = false;
  const m = plModel(); if (!m) return;
  const v = +$('#pl-seek').value / 1000;
  if (m.src === 'podcast') { if (!(m.dur > 0)) return; const ms = Math.round(m.dur * v); Android.podSeek(ms); pcSt.pos = ms; }
  else if (m.shift) { const total = (+m.shift.back || 0) + (+m.shift.behind || 0); Android.radioShiftTo(Math.round(total * (1 - v))); setTimeout(plTick, 300); }
  plRender();
}
['pointerup', 'touchend', 'blur'].forEach(ev => $('#pl-seek').addEventListener(ev, () => setTimeout(() => { plSeeking = false; }, 50)));

/* ---------- openen en sluiten ---------- */
function plOpen(){
  if (!plModel()) return;
  plIsOpen = true; plQueueSig = '';
  rdFavs = rdJson(Android.radioFavorites(), []); // ook als het radioscherm nog niet open was (bijv. gestart vanaf de widget)
  const p = $('#player');
  p.classList.add('on'); p.setAttribute('aria-hidden', 'false');
  document.body.classList.add('pl-open');
  plInert(true);
  $('#pl-scroll').scrollTop = 0;
  plRender();
  setTimeout(() => $('#pl-title').focus({ preventScroll: true }), 30);
}
function plClose(){
  if (!plIsOpen) return;
  plIsOpen = false;
  const p = $('#player');
  p.classList.remove('on'); p.setAttribute('aria-hidden', 'true');
  document.body.classList.remove('pl-open');
  plInert(false);
  plRender();
  const b = $('#plbar-open'); if (b && $('#plbar').style.display !== 'none') b.focus({ preventScroll: true });
}

/* Wat achter de speler ligt is niet bereikbaar (TalkBack, toetsenbord) zolang hij open is. */
function plInert(on){ document.querySelectorAll('.screen, #plbar').forEach(el => { if (on) el.setAttribute('inert', ''); else el.removeAttribute('inert'); }); }

/* ---------- vegen ---------- */
function plCoverIn(dir){
  const a = $('#pl-art'); if (!a) return;
  a.style.transition = 'none'; a.style.transform = 'translateX(' + (dir > 0 ? 60 : -60) + 'px)'; a.style.opacity = '0';
  requestAnimationFrame(() => { a.style.transition = 'transform .25s ease, opacity .25s ease'; a.style.transform = ''; a.style.opacity = ''; });
}
function plCoverBack(){ const a = $('#pl-art'); if (a) { a.style.transition = 'transform .2s ease'; a.style.transform = ''; a.style.opacity = ''; } }
/* Vegen: begint op de hoes, de bovenrand of de mini-balk; de rest volgt op het hele venster (ook als de vinger
   buiten het element komt). */
(function(){
  let g = null; // { kind: 'cover'|'top'|'bar', x0, y0, dx, dy }
  const art = () => $('#pl-art');
  const start = kind => e => { if (e.button > 0) return; g = { kind, x0: e.clientX, y0: e.clientY, dx: 0, dy: 0 }; if (kind === 'cover') art().style.transition = 'none'; };
  $('#pl-cover').addEventListener('pointerdown', start('cover'));
  // Omlaag vegen op de hoes (bovenaan): niet laten scrollen, anders breekt de browser het vegen af
  let ty0 = 0, tx0 = 0;
  $('#pl-cover').addEventListener('touchstart', e => { ty0 = e.touches[0].clientY; tx0 = e.touches[0].clientX; }, { passive: true });
  $('#pl-cover').addEventListener('touchmove', e => {
    const dy = e.touches[0].clientY - ty0, dx = e.touches[0].clientX - tx0;
    if (dy > 8 && dy > Math.abs(dx) && $('#pl-scroll').scrollTop <= 0 && e.cancelable) e.preventDefault();
  }, { passive: false });
  $('#pl-top').addEventListener('pointerdown', start('top'));
  $('#plbar').addEventListener('pointerdown', start('bar'));
  // Na vegen over de balk geen klik meer op de knop waar de vinger losliet
  $('#plbar').addEventListener('click', e => { if (Date.now() - plBarSwiped < 400) { e.stopPropagation(); e.preventDefault(); } }, true);
  window.addEventListener('pointermove', e => {
    if (!g) return;
    g.dx = e.clientX - g.x0; g.dy = e.clientY - g.y0;
    if (g.kind === 'cover' && Math.abs(g.dx) > Math.abs(g.dy)) {
      art().style.transform = 'translateX(' + g.dx + 'px) rotate(' + (g.dx / 30).toFixed(1) + 'deg)';
      art().style.opacity = String(Math.max(.35, 1 - Math.abs(g.dx) / 400));
    }
  });
  window.addEventListener('pointercancel', () => { if (g && g.kind === 'cover') plCoverBack(); g = null; });
  window.addEventListener('pointerup', () => {
    if (!g) return;
    const { kind, dx, dy } = g; g = null;
    const horiz = Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy), down = dy > 70 && dy > Math.abs(dx), up = dy < -35 && -dy > Math.abs(dx);
    if (kind === 'cover') {
      if (horiz) {
        const a = art(); a.style.transition = 'transform .18s ease, opacity .18s ease'; a.style.transform = 'translateX(' + (dx < 0 ? -320 : 320) + 'px)'; a.style.opacity = '0';
        setTimeout(() => plStep(dx < 0 ? 1 : -1), 160);
      } else { plCoverBack(); if (down && $('#pl-scroll').scrollTop <= 0) plClose(); }
    } else if (kind === 'top') { if (down) plClose(); }
    else if (kind === 'bar') {
      if (up) { plOpen(); plBarSwiped = Date.now(); } // de klik die nog volgt niet nog eens
      else if (horiz) { plBarSwiped = Date.now(); plStep(dx < 0 ? 1 : -1); }
    }
  });
})();

/* ---------- één klok voor alles ---------- */
function plTick(){ if (appPaused) return; rdPollOnce(true); pcPoll(false); } // op de achtergrond niet (scheelt accu)
setInterval(plTick, 1000);
setTimeout(plTick, 0);
