/* ---------- Podcasts: zoeken, volgen, luisteren, slaaptimer ---------- */
let pcSt = {}, pcPollT = null, pcSeeking = false, pcSearchT = null, pcTop = null, pcRes = null, pcLastSig = '';
let pdPod = null, pdFeed = null, pdShown = 50;

/* Tijd als 1:02:03 of 4:05 */
function fmtClock(ms){
  const s = Math.max(0, Math.floor((+ms || 0) / 1000));
  return s >= 3600 ? Math.floor(s / 3600) + ':' + String(Math.floor(s / 60) % 60).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
    : Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}
/* Minuten als "45 min" of "1 u 20 min" */
function fmtMin(m){ m = Math.max(0, Math.round(m)); return m >= 60 ? Math.floor(m / 60) + ' u' + (m % 60 ? ' ' + (m % 60) + ' min' : '') : m + ' min'; }
function pcDate(t){
  if (!t) return '';
  const d = new Date(t), now = new Date(), day = 864e5;
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (t >= start) return 'vandaag';
  if (t >= start - day) return 'gisteren';
  if (t >= start - 6 * day) return d.toLocaleDateString('nl-NL', { weekday: 'long' });
  return d.toLocaleDateString('nl-NL', d.getFullYear() === now.getFullYear() ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' });
}
/* Hoesje: alleen https (de app laadt niets onveiligs); anders een 🎧 */
function pcImg(u){ u = String(u || ''); if (u.startsWith('http://')) u = 'https://' + u.slice(7); return /^https:\/\//.test(u) ? u : ''; }
function pcArtInner(u){ const s = pcImg(u); return s ? '<img src="' + esc(s) + '" alt="" loading="lazy" onerror="this.parentNode.textContent=\'🎧\'">' : '🎧'; }
function pcArt(u, cls){ return '<span class="pcart' + (cls ? ' ' + cls : '') + '" aria-hidden="true">' + pcArtInner(u) + '</span>'; }
function pcSetArt(el, u){ const s = pcImg(u); if (el.dataset.src === s && el.innerHTML) return; el.dataset.src = s; el.innerHTML = pcArtInner(u); }
function pcSpeedTxt(v){ return String(+(+v || 1).toFixed(2)).replace('.', ',') + '×'; }
function pcSlimEp(e){ return { key: e.key, guid: e.guid, title: e.title, url: e.url, type: e.type, date: e.date, dur: e.dur, image: e.image, desc: String(e.desc || '').slice(0, 1500) }; }
function pcSlimPod(p){ p = p || {}; return { id: p.id, feed: p.feed, title: p.title, author: p.author, image: p.image, link: p.link }; }

/* ---------- hoofdscherm ---------- */
function enterPodcasts(){
  pcPoll(true);
  pcRenderCont(); pcRenderSubs();
  if (!$('#pc-q').value.trim()) pcShowTop();
  pcStartPoll();
  // Abonnementen af en toe bijwerken (nieuwe afleveringen)
  const subs = rdJson(Android.podSubs(), []);
  if (subs.length && Date.now() - (+Android.podRefreshedAt() || 0) > 3 * 36e5) Android.podRefresh();
}
function pcStartPoll(){ if (!pcPollT) pcPollT = setInterval(() => { if (appPaused) return; if (current === 'podcasts' || current === 'podcast') pcPoll(false); else pcStopPoll(); }, 1000); }
function pcStopPoll(){ if (pcPollT) { clearInterval(pcPollT); pcPollT = null; } }
function pcPoll(force){
  pcSt = rdJson(Android.podState(), {});
  const sig = JSON.stringify([pcSt.status, pcSt.ep && pcSt.ep.key, pcSt.speed, pcSt.sleepAt, pcSt.sleepEnd, pcSt.error, Math.floor(Date.now() / 3e4)]);
  if (force || sig !== pcLastSig) {
    const keyChanged = !pcLastSig || JSON.parse(pcLastSig)[1] !== (pcSt.ep && pcSt.ep.key);
    pcLastSig = sig; pcRenderNow();
    if (keyChanged && current === 'podcasts') pcRenderCont();
    if (current === 'podcast') pdRefreshRows(); // ▶/❚❚ en voortgang in de lijst bijwerken
  }
  pcRenderTimes();
}
function pcActive(){ return pcSt.status === 'playing' || pcSt.status === 'connecting'; }
function pcRenderNow(){
  const st = pcSt, has = !!(st.ep && st.ep.url);
  $('#pc-now').style.display = has ? 'block' : 'none';
  $('#pd-mini').style.display = has ? 'flex' : 'none';
  if (!has) return;
  pcSetArt($('#pc-now-art'), st.ep.image || (st.pod && st.pod.image));
  pcSetArt($('#pd-mini-art'), st.ep.image || (st.pod && st.pod.image));
  $('#pc-now-title').textContent = st.ep.title || 'Aflevering';
  $('#pd-mini-t').textContent = st.ep.title || 'Aflevering';
  $('#pc-now-pod').textContent = (st.pod && st.pod.title) || '';
  const playing = pcActive();
  for (const b of [$('#pc-pp'), $('#pd-mini-pp')]) { b.textContent = playing ? '❚❚' : '▶'; b.setAttribute('aria-label', playing ? 'Pauze' : 'Afspelen'); }
  $('#pc-speed').textContent = pcSpeedTxt(st.speed);
  const sl = $('#pc-sleep');
  if (st.sleepAt) { sl.textContent = '⏾ nog ' + fmtMin((st.sleepAt - Date.now()) / 6e4); sl.classList.add('on'); }
  else if (st.sleepEnd) { sl.textContent = '⏾ na deze aflevering'; sl.classList.add('on'); }
  else { sl.textContent = '⏾ Slaaptimer'; sl.classList.remove('on'); }
  $('#pc-sleepinfo').innerHTML = st.sleepAt ? 'Stopt om ' + new Date(st.sleepAt).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }) + ' · <a href="#" onclick="Android.podSleep(0);setTimeout(()=>pcPoll(true),200);return false">uitzetten</a>'
    : st.sleepEnd ? 'Stopt aan het einde van deze aflevering · <a href="#" onclick="Android.podSleep(0);setTimeout(()=>pcPoll(true),200);return false">uitzetten</a>' : '';
  $('#pc-state').textContent = st.status === 'connecting' ? 'Laden…' : st.status === 'error' ? (st.error || 'Fout') : st.status === 'ended' ? 'Afgelopen' : '';
  $('#pc-state').classList.toggle('bad', st.status === 'error');
}
function pcRenderTimes(){
  const st = pcSt; if (!st.ep) return;
  const dur = +st.dur || 0, pos = +st.pos || 0;
  if (!pcSeeking) {
    $('#pc-seek').value = dur > 0 ? Math.round(pos / dur * 1000) : 0;
    $('#pc-pos').textContent = fmtClock(pos);
  }
  $('#pc-left').textContent = dur > 0 ? '−' + fmtClock(dur - pos) : '';
  $('#pc-seek').disabled = !(dur > 0);
}
// Losgelaten zonder echte verandering (geen change-event): de tijd niet laten bevriezen
['pointerup', 'touchend', 'blur'].forEach(ev => document.getElementById('pc-seek').addEventListener(ev, () => setTimeout(() => { pcSeeking = false; }, 50)));
function pcSeekDrag(){ pcSeeking = true; const dur = +pcSt.dur || 0; $('#pc-pos').textContent = fmtClock(dur * $('#pc-seek').value / 1000); }
function pcSeekDone(){
  const dur = +pcSt.dur || 0; pcSeeking = false;
  if (!(dur > 0)) return;
  const ms = Math.round(dur * $('#pc-seek').value / 1000);
  Android.podSeek(ms); pcSt.pos = ms; pcRenderTimes();
}
function pcPlayPause(){
  if (pcActive()) Android.podPause(); else Android.podResume();
  pcSt.status = pcActive() ? 'paused' : 'connecting'; pcRenderNow();
  setTimeout(() => pcPoll(true), 300);
}
function pcSkip(s){ Android.podSkip(s); pcSt.pos = Math.max(0, (+pcSt.pos || 0) + s * 1000); pcRenderTimes(); }
function pcSpeedMenu(){
  const opts = [0.8, 1, 1.1, 1.25, 1.5, 1.75, 2];
  openSheet('Afspeelsnelheid', 'Nu ' + pcSpeedTxt(pcSt.speed), opts.map(v => [pcSpeedTxt(v) + (v === 1 ? ' (normaal)' : ''), () => { Android.podSpeed(String(v)); pcSt.speed = v; pcRenderNow(); }]));
}
function pcOpenNowPod(){ const p = pcSt.pod; if (p && p.feed) pdOpen(p); }
function pcNowMenu(){
  const st = pcSt; if (!st.ep) return;
  const acts = [];
  if (st.pod && st.pod.feed) acts.push(['Naar de podcast', () => pdOpen(st.pod)]);
  if (st.ep.desc) acts.push(['Beschrijving', () => pcShowDesc(st.ep)]);
  acts.push(['Delen', () => Android.podShare(st.ep.title + (st.pod && st.pod.title ? ' · ' + st.pod.title : ''), (st.pod && st.pod.link) || st.ep.url)]);
  acts.push(['Stoppen', () => { Android.podStop(); setTimeout(() => pcPoll(true), 300); }]);
  openSheet(st.ep.title || 'Aflevering', (st.pod && st.pod.title) || '', acts);
}
function pcShowDesc(ep, onPlay){
  if (onPlay) askConfirm(ep.title || 'Aflevering', String(ep.desc || 'Geen beschrijving.'), 'Afspelen', onPlay);
  else askConfirm(ep.title || 'Aflevering', String(ep.desc || 'Geen beschrijving.'), 'Sluiten', () => {});
}
function pcPlayEp(ep, pod, fromStart){
  // De plek zelf haalt de app uit wat hij bewaard heeft (actueler dan deze lijst)
  const e = Android.podPlay(JSON.stringify({ ep: pcSlimEp(ep), pod: pcSlimPod(pod), fromStart: !!fromStart }));
  if (e) { toast(e); return; }
  pcSt = { status: 'connecting', ep: pcSlimEp(ep), pod: pcSlimPod(pod), pos: fromStart ? 0 : (+ep.pos || 0), dur: (+ep.dur || 0) * 1000, speed: pcSt.speed || 1 };
  pcLastSig = ''; pcRenderNow(); pcRenderTimes(); pcStartPoll();
  if (current === 'podcast') pdRefreshRows(); else pcRenderCont();
}

/* Verder luisteren */
let pcCont = [];
function pcRenderCont(){
  const cur = pcSt.ep && pcSt.ep.key;
  pcCont = rdJson(Android.podContinue(), []).filter(r => r.ep && r.ep.key !== cur).slice(0, 8);
  $('#pc-cont-wrap').style.display = pcCont.length ? 'block' : 'none';
  $('#pc-cont').innerHTML = pcCont.map((r, i) => {
    const e = r.ep, d = (+e.pd || (+e.dur || 0) * 1000), p = +e.pos || 0, pct = d > 0 ? Math.min(100, p / d * 100) : 0;
    return '<div class="pcep"><button class="pcepb" onclick="pcContPlay(' + i + ')">' + pcArt(e.image || (r.pod && r.pod.image)) +
      '<span class="pctxt"><b>' + esc(e.title) + '</b><small>' + esc((r.pod && r.pod.title) || '') + (d > 0 ? ' · nog ' + esc(fmtMin((d - p) / 6e4)) : '') + '</small>' +
      '<span class="pcprog"><i style="width:' + pct.toFixed(1) + '%"></i></span></span></button>' +
      '<button class="pcmore" onclick="pcContMenu(' + i + ')" aria-label="Meer over ' + esc(e.title) + '">⋯</button></div>';
  }).join('');
}
function pcContPlay(i){ const r = pcCont[i]; if (r) pcPlayEp(r.ep, r.pod, false); }
function pcContMenu(i){
  const r = pcCont[i]; if (!r) return;
  openSheet(r.ep.title, (r.pod && r.pod.title) || '', [
    ['Verder luisteren', () => pcPlayEp(r.ep, r.pod, false)],
    ['Vanaf het begin', () => pcPlayEp(r.ep, r.pod, true)],
    ['Markeren als beluisterd', () => { Android.podMarkPlayed(r.ep.key, true); Android.podForget(r.ep.key); pcRenderCont(); }],
    ['Uit deze lijst halen', () => { Android.podForget(r.ep.key); pcRenderCont(); }]]);
}

/* Mijn podcasts */
let pcSubs = [];
function pcRenderSubs(){
  pcSubs = rdJson(Android.podSubs(), []);
  $('#pc-subs-wrap').style.display = pcSubs.length ? 'block' : 'none';
  $('#pc-subs').innerHTML = pcSubs.map((s, i) => '<button class="pcsub" onclick="pdOpenSub(' + i + ')" aria-label="' + esc(s.title) + (s.fresh ? ', ' + s.fresh + ' nieuw' : '') + '">' +
    pcArt(s.image) + (s.fresh ? '<span class="badge">' + (+s.fresh > 99 ? '99+' : +s.fresh) + '</span>' : '') + '<span class="t">' + esc(s.title) + '</span></button>').join('');
  const n = pcSubs.reduce((a, s) => a + (+s.fresh || 0), 0), el = $('#tile-podcasts-sub');
  if (el) el.textContent = n ? n + (n === 1 ? ' nieuwe aflevering' : ' nieuwe afleveringen') : 'Zoeken, volgen en luisteren';
}
function pdOpenSub(i){ const s = pcSubs[i]; if (s) pdOpen(s); }
function pcRefresh(){ $('#pc-refresh').textContent = 'Bijwerken…'; $('#pc-refresh').disabled = true; Android.podRefresh(); }
window.onPodRefreshed = function(r){
  $('#pc-refresh').textContent = '↻ Bijwerken'; $('#pc-refresh').disabled = false;
  if (current === 'podcasts') pcRenderSubs(); else if (typeof refreshPodTile === 'function') refreshPodTile();
};
function refreshPodTile(){
  const el = $('#tile-podcasts-sub'); if (!el) return;
  const n = rdJson(Android.podSubs(), []).reduce((a, s) => a + (+s.fresh || 0), 0);
  el.textContent = n ? n + (n === 1 ? ' nieuwe aflevering' : ' nieuwe afleveringen') : 'Zoeken, volgen en luisteren';
}

/* Zoeken en populair */
function pcRowList(list, kind){
  return list.map((p, i) => '<div class="srow"><button class="sbtn" onclick="pdOpenFrom(\'' + kind + '\',' + i + ')">' + pcArt(p.image) +
    '<span class="stxt"><b>' + esc(p.title) + '</b><small>' + esc([p.author, p.genre].filter(Boolean).join(' · ')) + '</small></span></button></div>').join('');
}
function pdOpenFrom(kind, i){ const p = (kind === 't' ? pcTop : pcRes) || []; if (p[i]) pdOpen(p[i]); }
function pcShowTop(){
  $('#pc-list-label').textContent = 'Populair in Nederland';
  if (pcTop) { $('#pc-list').innerHTML = pcRowList(pcTop, 't') || '<p class="note" style="padding:14px">Geen podcasts gevonden.</p>'; return; }
  $('#pc-list').innerHTML = '<p class="note" style="padding:14px">Laden…</p>';
  Android.podTop();
}
window.onPodTop = function(r){
  if (r.error) { if (!$('#pc-q').value.trim()) $('#pc-list').innerHTML = '<p class="note" style="padding:14px">' + esc(r.error) + '. <a href="#" onclick="pcTop=null;pcShowTop();return false">Opnieuw</a></p>'; return; }
  pcTop = r.results || [];
  if (!$('#pc-q').value.trim()) pcShowTop();
};
function pcSearchSoon(){
  clearTimeout(pcSearchT);
  const q = $('#pc-q').value.trim();
  if (!q) { pcShowTop(); return; }
  pcSearchT = setTimeout(() => { $('#pc-list-label').textContent = 'Zoekresultaten'; $('#pc-list').innerHTML = '<p class="note" style="padding:14px">Zoeken…</p>'; Android.podSearch(q); }, 500);
}
window.onPodSearch = function(r){
  if ((r.q || '') !== $('#pc-q').value.trim()) return; // verouderd antwoord
  if (r.error) { $('#pc-list').innerHTML = '<p class="note" style="padding:14px">' + esc(r.error) + '. <a href="#" onclick="pcSearchSoon();return false">Opnieuw</a></p>'; return; }
  pcRes = r.results || [];
  $('#pc-list').innerHTML = pcRowList(pcRes, 's') || '<p class="note" style="padding:14px">Geen podcast gevonden.</p>';
};

/* ---------- één podcast ---------- */
function pdOpen(p){
  pdPod = pcSlimPod(p); pdFeed = null; pdShown = 50;
  show('podcast');
  $('#pd-h1').textContent = p.title || 'Podcast';
  pcSetArt($('#pd-art'), p.image);
  $('#pd-title').textContent = p.title || '';
  $('#pd-author').textContent = p.author || '';
  $('#pd-desc').textContent = ''; $('#pd-about').style.display = 'none';
  $('#pd-stale').style.display = 'none';
  pdSubBtn(rdJson(Android.podSubs(), []).some(s => s.feed === p.feed));
  $('#pd-eps').innerHTML = '<p class="note" style="padding:14px">Afleveringen laden…</p>';
  $('#pd-more').style.display = 'none';
  Android.podOpen(p.feed, false);
  pcStartPoll();
}
function pdSubBtn(on){ const b = $('#pd-sub'); b.textContent = on ? '✓ Je volgt deze podcast' : '＋ Volgen'; b.classList.toggle('ghost', on); b.dataset.on = on ? '1' : ''; }
window.onPodFeed = function(r){
  if (!pdPod || r.feedUrl !== pdPod.feed) return;
  if (r.error) { $('#pd-eps').innerHTML = '<p class="note" style="padding:14px">' + esc(r.error) + '. <a href="#" onclick="Android.podOpen(pdPod.feed, true);return false">Opnieuw</a></p>'; return; }
  pdFeed = r;
  if (r.pod) {
    pdPod = Object.assign(pdPod, pcSlimPod(r.pod), { link: r.pod.link });
    $('#pd-h1').textContent = r.pod.title || 'Podcast';
    $('#pd-title').textContent = r.pod.title || '';
    $('#pd-author').textContent = r.pod.author || '';
    if (r.pod.image) pcSetArt($('#pd-art'), r.pod.image);
    $('#pd-desc').textContent = r.pod.desc || '';
    $('#pd-about').style.display = r.pod.desc ? 'block' : 'none';
  }
  if (r.stale) { $('#pd-stale').style.display = 'block'; $('#pd-stale').textContent = 'Bijwerken lukte niet (' + r.stale + '); je ziet de laatst bekende afleveringen.'; }
  pdSubBtn(!!r.subscribed);
  if (r.subscribed && r.pod) Android.podSeen(r.pod.id);
  pdRender();
};
function pdList(){
  let l = (pdFeed && pdFeed.items || []).slice();
  if ($('#pd-sort .on') && $('#pd-sort .on').dataset.v === 'old') l.reverse();
  if ($('#pd-hide').checked) l = l.filter(e => !e.done);
  return l;
}
let pdRows = [], pdCurKey = null;
function pdRowHtml(e, i, cur){
  const isCur = e.key === cur;
  if (isCur && pcSt.status === 'ended') { e.done = true; e.pos = 0; }
  else if (isCur) { e.pos = +pcSt.pos || 0; if (+pcSt.dur) e.pd = +pcSt.dur; if (e.pos > 0) e.done = false; } // blijft kloppen als er later iets anders speelt
  const d = +e.pd || (+e.dur || 0) * 1000, p = +e.pos || 0;
  const info = [pcDate(e.date), e.dur ? fmtMin(e.dur / 60) : '', e.done ? '✓ beluisterd' : p > 0 && d > 0 ? 'nog ' + fmtMin((d - p) / 6e4) : ''].filter(Boolean).join(' · ');
  return '<div class="pcep' + (e.done ? ' done' : '') + (isCur ? ' cur' : '') + '" data-i="' + i + '"><button class="pcepb" onclick="pdPlay(' + i + ')">' +
    '<span class="pctxt"><b>' + esc(e.title) + '</b><small>' + esc(info) + '</small>' +
    (p > 0 && d > 0 && !e.done ? '<span class="pcprog"><i style="width:' + Math.min(100, p / d * 100).toFixed(1) + '%"></i></span>' : '') + '</span>' +
    '<span class="pcplayi" aria-hidden="true">' + (isCur && pcActive() ? '❚❚' : '▶') + '</span></button>' +
    '<button class="pcmore" onclick="pdEpMenu(' + i + ')" aria-label="Meer over ' + esc(e.title) + '">⋯</button></div>';
}
function pdRender(){
  if (!pdFeed) return;
  const cur = pcSt.ep && pcSt.ep.key, l = pdList();
  pdCurKey = cur;
  pdRows = l.slice(0, pdShown);
  $('#pd-eps').innerHTML = pdRows.map((e, i) => pdRowHtml(e, i, cur)).join('') ||
    '<p class="note" style="padding:14px">' + ($('#pd-hide').checked ? 'Alles is beluisterd.' : 'Geen afleveringen gevonden.') + '</p>';
  $('#pd-more').style.display = l.length > pdShown ? 'block' : 'none';
}
/* Bij het pollen alleen de rij van wat speelt (en wat net speelde) vervangen: de rest van de lijst blijft staan,
   zodat een tik of de focus van TalkBack niet verloren gaat. */
function pdRefreshRows(){
  if (!pdFeed) return;
  const cur = pcSt.ep && pcSt.ep.key, keys = [cur, pdCurKey].filter(Boolean);
  pdCurKey = cur;
  pdRows.forEach((e, i) => {
    if (keys.indexOf(e.key) < 0) return;
    const old = document.querySelector('#pd-eps .pcep[data-i="' + i + '"]'); if (!old) return;
    const act = document.activeElement, focusCls = act && old.contains(act) ? act.className : null;
    old.outerHTML = pdRowHtml(e, i, cur);
    if (focusCls) { const n = document.querySelector('#pd-eps .pcep[data-i="' + i + '"] .' + focusCls.split(' ')[0]); if (n) n.focus(); }
  });
}
function pdMore(){ pdShown += 50; pdRender(); }
function pdPlay(i){
  const e = pdRows[i]; if (!e) return;
  if (pcSt.ep && pcSt.ep.key === e.key) { pcPlayPause(); return; } // de rij volgt via pcPoll
  pcPlayEp(e, pdPod, !!e.done); // al beluisterd: weer vanaf het begin
}
function pdEpMenu(i){
  const e = pdRows[i]; if (!e) return;
  const acts = [[e.pos > 0 && !e.done ? 'Verder luisteren' : 'Afspelen', () => pcPlayEp(e, pdPod, !!e.done)]];
  if (e.pos > 0 && !e.done) acts.push(['Vanaf het begin', () => pcPlayEp(e, pdPod, true)]);
  acts.push([e.done ? 'Markeren als niet beluisterd' : 'Markeren als beluisterd', () => { Android.podMarkPlayed(e.key, !e.done); e.done = !e.done; e.pos = 0; pdRender(); }]);
  if (e.desc) acts.push(['Beschrijving', () => pcShowDesc(e, () => pcPlayEp(e, pdPod, !!e.done))]);
  acts.push(['Delen', () => Android.podShare(e.title + ' · ' + (pdPod.title || ''), pdPod.link || e.url)]);
  openSheet(e.title, [pcDate(e.date), e.dur ? fmtMin(e.dur / 60) : ''].filter(Boolean).join(' · '), acts);
}
function pdSubToggle(){
  if (!pdPod) return;
  if ($('#pd-sub').dataset.on) {
    askConfirm('Niet meer volgen?', '"' + (pdPod.title || 'Deze podcast') + '" verdwijnt uit Mijn podcasts. Waar je was in afleveringen blijft bewaard.', 'Niet meer volgen', () => { Android.podUnsubscribe(pdPod.id); pdSubBtn(false); });
    return;
  }
  const e = Android.podSubscribe(JSON.stringify(pdPod));
  if (e) { toast(e); return; }
  pdSubBtn(true); toast('✓ Je volgt nu ' + (pdPod.title || 'deze podcast'));
  if (pdPod.id) Android.podSeen(pdPod.id);
}
document.querySelectorAll('#pd-sort button').forEach(b => b.addEventListener('click', () => {
  document.querySelectorAll('#pd-sort button').forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-checked', x === b ? 'true' : 'false'); });
  pdShown = 50; pdRender();
}));

/* ---------- slaaptimer (gedeeld door Podcasts en Radio) ---------- */
let slTarget = 'pod';
function sleepState(){ return slTarget === 'pod' ? rdJson(Android.podState(), {}) : rdJson(Android.radioState(), {}); }
function sleepOpen(target){
  slTarget = target === 'radio' ? 'radio' : 'pod';
  const st = sleepState(), active = !!(st.sleepAt || st.sleepEnd);
  $('#sl-range').value = Math.max(1, Math.min(240, +store.get('sleepMin', 30) || 30));
  $('#sl-quick').innerHTML = [10, 15, 20, 30, 45, 60, 90, 120].map(m => '<button data-m="' + m + '" onclick="$(\'#sl-range\').value=' + m + ';sleepShow()">' + fmtMin(m) + '</button>').join('');
  $('#sl-end').style.display = slTarget === 'pod' ? '' : 'none';
  $('#sl-state').textContent = st.sleepAt ? 'Stopt nu om ' + new Date(st.sleepAt).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }) + ' (nog ' + fmtMin((st.sleepAt - Date.now()) / 6e4) + ').'
    : st.sleepEnd ? 'Stopt nu aan het einde van deze aflevering.' : (slTarget === 'pod' ? 'Stop de podcast na…' : 'Stop de radio na…');
  $('#sl-add').style.display = st.sleepAt ? 'flex' : 'none';
  $('#sl-off').textContent = active ? 'Uitzetten' : 'Annuleren';
  const d = new Date(Date.now() + 60 * 6e4);
  $('#sl-time').value = String(d.getHours()).padStart(2, '0') + ':' + String(Math.floor(d.getMinutes() / 5) * 5).padStart(2, '0');
  sleepShow();
  $('#sleepdlg').classList.add('on');
}
function sleepClose(){ $('#sleepdlg').classList.remove('on'); }
function sleepShow(){
  const m = +$('#sl-range').value;
  $('#sl-min').textContent = m;
  document.querySelectorAll('#sl-quick button').forEach(b => b.classList.toggle('on', +b.dataset.m === m));
}
function sleepStep(d){ $('#sl-range').value = Math.max(1, Math.min(240, +$('#sl-range').value + d)); sleepShow(); }
function sleepApply(m){
  if (slTarget === 'pod') { const e = Android.podSleep(m); if (e) { toast(e); return false; } } else Android.radioSleep(m);
  setTimeout(() => { if (slTarget === 'pod') pcPoll(true); else if (typeof rdPollOnce === 'function') rdPollOnce(); }, 250);
  return true;
}
function sleepStart(){
  const m = +$('#sl-range').value;
  store.set('sleepMin', m);
  sleepClose();
  if (sleepApply(m)) toast('⏾ Stopt over ' + fmtMin(m));
}
function sleepEnd(){ if (slTarget !== 'pod') return; sleepClose(); if (sleepApply(-1)) toast('⏾ Stopt aan het einde van deze aflevering'); }
function sleepAt(){
  const v = /^(\d{1,2}):(\d{2})$/.exec($('#sl-time').value || '');
  if (!v) { toast('Kies een tijd'); return; }
  const t = new Date(); t.setHours(+v[1], +v[2], 0, 0);
  if (t.getTime() <= Date.now()) t.setDate(t.getDate() + 1);
  const m = Math.max(1, Math.ceil((t.getTime() - Date.now()) / 6e4));
  sleepClose();
  if (sleepApply(m)) toast('⏾ Stopt om ' + t.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }));
}
function sleepAdd(n){
  const st = sleepState();
  sleepClose();
  if (slTarget === 'pod') { const e = Android.podSleepAdd(n); if (e) { toast(e); return; } }
  else Android.radioSleep(Math.max(1, Math.ceil(((st.sleepAt || Date.now()) - Date.now()) / 6e4)) + n);
  toast('⏾ ' + n + ' minuten erbij');
  setTimeout(() => { if (slTarget === 'pod') pcPoll(true); else if (typeof rdPollOnce === 'function') rdPollOnce(); }, 250);
}
function sleepOff(){
  const st = sleepState();
  if (st.sleepAt || st.sleepEnd) { sleepApply(0); toast('Slaaptimer uit'); }
  sleepClose();
}
