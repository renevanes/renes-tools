/* ---------- Slaappraat: 's nachts zuinig luisteren, alleen bewaren wat er te horen is, daarna uitschrijven ---------- */
let slSt = {}, slPollT = null, slNightCur = null, slNightId = '', slFilterV = 'praten', slPlaying = '';
const SL_SENS_NOTE = { '1': 'Pakt ook zacht gemompel op (en dus vaker een geluidje).', '2': 'Gewoon praten in je slaap.', '3': 'Alleen duidelijk en hardop praten.' };

function slLoad(){ slSt = rdJson(Android.sleepState(), {}); return slSt; }
function enterSleep(){ slLoad(); slRender(); slPoll(); }
function stopSleepPoll(){ clearTimeout(slPollT); slPollT = null; }
/* Zolang het scherm open is: elke seconde (tijdens luisteren voor de meter), anders niet */
function slPoll(){
  stopSleepPoll();
  if (current !== 'sleep' && current !== 'sleepnight') return;
  if (!slSt.phase) return;
  slPollT = setTimeout(() => { const was = slSt.phase; slLoad(); if (current === 'sleep') slRender(); else if (current === 'sleepnight' && slNightId === slSt.night) slnLoad(slNightId); if (was && !slSt.phase && current === 'sleep') slRender(); slPoll(); }, 1000);
}
window.onSleep = function(o){
  o = o || {};
  if (o.tx != null && current === 'sleepnight') { const e = $('#sln-tx'); if (e) e.innerHTML = '<p class="note">Uitschrijven… ' + o.tx + ' van ' + o.of + '</p>'; return; }
  if (o.txDone) { if (o.error) toast(o.error === 'model' ? 'Download eerst een spraakmodel' : o.error); else toast('Uitgeschreven'); }
  if (o.played) { slPlaying = ''; if (current === 'sleepnight') slnRender(); return; }
  slLoad();
  if (current === 'sleep') slRender();
  if (current === 'sleepnight' && slNightId) slnLoad(slNightId);
};

function slTime(ms){ return ms ? new Date(ms).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }) : ''; }
/* "Nacht van do 23 op vr 24 okt" (gestart voor 12 uur 's middags: die nacht zelf) */
function slNightName(ms){
  const d = new Date(ms), a = new Date(ms);
  if (d.getHours() < 12) a.setDate(a.getDate() - 1);
  const b = new Date(a); b.setDate(b.getDate() + 1);
  const f = x => x.toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric' });
  return 'Nacht van ' + f(a) + ' op ' + f(b) + ' ' + b.toLocaleDateString('nl-NL', { month: 'short' });
}
function slDur(ms){ const m = Math.round(ms / 60000); return m < 60 ? m + ' min' : Math.floor(m / 60) + ' u ' + (m % 60) + ' min'; }

function slRender(){
  const s = slSt, set = s.set || {}, st = $('#sl-status'), btn = $('#sl-btn'), meter = $('#sl-meter');
  if (!st) return;
  if (s.phase === 'luistert') {
    st.innerHTML = '<div class="sl-live"><span class="sl-dot" aria-hidden="true"></span><b>Luistert</b> sinds ' + esc(slTime(s.since)) + '</div><p class="note">' +
      esc((s.heard ? s.heard + ' keer iets gehoord' : 'Nog niets gehoord') + (s.stopAt ? ' · stopt om ' + slTime(s.stopAt) : '')) + '</p>';
    btn.textContent = 'Stoppen'; btn.classList.add('ghost');
    meter.style.display = '';
    // Meter: -70 dB (stil) … -20 dB (hard)
    const pct = v => Math.max(0, Math.min(100, (v + 70) * 2));
    $('#sl-meter-bar').style.width = pct(s.level) + '%';
    $('#sl-meter-floor').style.left = pct(s.floor) + '%';
  } else if (s.phase === 'uitschrijven') {
    st.innerHTML = '<div class="sl-live"><b>Uitschrijven</b>… ' + (s.txDone || 0) + ' van ' + (s.txTotal || 0) + '</div><p class="note">Op je telefoon, met het spraakmodel van Gesprekken uitschrijven.</p>';
    btn.textContent = 'Opnieuw beginnen met luisteren'; btn.classList.remove('ghost'); meter.style.display = 'none';
  } else {
    st.innerHTML = s.message ? '<p class="note sl-msg">' + esc(s.message) + '</p>' : '';
    btn.textContent = 'Begin met luisteren'; btn.classList.remove('ghost'); meter.style.display = 'none';
  }
  // Instellingen
  const on = !!set.stopAt;
  const t = $('#sl-stop'); if (document.activeElement !== t) t.value = set.stopAt || '07:30';
  t.disabled = !on; $('#sl-stoprow').style.display = on ? '' : 'none';
  const so = $('#sl-stop-on'); so.classList.toggle('on', on); so.setAttribute('aria-checked', on ? 'true' : 'false');
  $('#sl-stop-n').textContent = on ? "'s Ochtends om deze tijd" : 'Uit: luistert tot je stopt (hooguit 13 uur)';
  [['sl-sens', String(set.sens || 2)], ['sl-keep', String(set.keep || 14)]].forEach(([id, v]) => document.querySelectorAll('#' + id + ' button').forEach(b => { const x = b.dataset.v === v; b.classList.toggle('on', x); b.setAttribute('aria-checked', x ? 'true' : 'false'); }));
  $('#sl-sens-n').textContent = SL_SENS_NOTE[String(set.sens || 2)] || '';
  const tx = $('#sl-tx'); tx.classList.toggle('on', set.transcribe !== false); tx.setAttribute('aria-checked', set.transcribe !== false ? 'true' : 'false');
  $('#sl-tx-n').textContent = s.model ? 'Op je telefoon, met ' + s.model + ': je leest wat je zei' : 'Er is nog geen spraakmodel: download het bij Gesprekken uitschrijven';
  const no = $('#sl-noise'); no.classList.toggle('on', !!set.keepNoise); no.setAttribute('aria-checked', set.keepNoise ? 'true' : 'false');
  // Nachten
  const ns = s.nights || [];
  $('#sl-nights').innerHTML = ns.map((n, i) => {
    const live = n.id === s.night && s.phase;
    const sum = live ? (s.phase === 'luistert' ? 'Bezig…' : 'Uitschrijven…') : (n.talk ? n.talk + ' keer gepraat' : n.open ? n.open + ' keer iets gehoord' : 'Niets gezegd') + (n.noise ? ' · ' + n.noise + (n.noise === 1 ? ' geluid' : ' geluiden') : '') + (n.end ? ' · ' + slDur(n.end - n.start) : '');
    return '<button class="strow" onclick="slOpen(' + i + ')"><span class="stic">' + uiIcon('moon') + '</span><span class="sttxt"><b>' + esc(slNightName(n.start)) + '</b><small>' + esc(sum) + '</small>' +
      (n.first ? '<small class="sl-first">"' + esc(n.first) + '"</small>' : '') + '</span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>';
  }).join('') || '<p class="note" style="padding:14px 16px">Nog geen nachten. Tik vanavond op Begin met luisteren.</p>';
}

function slToggle(){
  if (slSt.phase === 'luistert') { Android.sleepStop(); toast('Stoppen…'); setTimeout(() => { slLoad(); slRender(); slPoll(); }, 600); return; }
  const e = Android.sleepStart();
  if (e === 'mic') { Android.permRequest('mic'); toast('Geef de app toegang tot de microfoon en tik dan opnieuw'); return; }
  if (e === 'notif') { Android.permRequest('notif'); toast('Sta meldingen toe (je ziet dan dat hij luistert) en tik dan opnieuw'); return; }
  if (e) { toast(e); return; }
  toast('Slaappraat luistert. Welterusten!');
  setTimeout(() => { slLoad(); slRender(); slPoll(); }, 500);
}
function slSet(name, v){ const e = Android.sleepSet(name, String(v)); if (e) { toast(e); return; } slLoad(); slRender(); }
function slSwitch(name){ const set = slSt.set || {}; const now = name === 'transcribe' ? set.transcribe !== false : !!set[name]; slSet(name, !now ? 'true' : 'false'); }
function slSetStop(){ const v = $('#sl-stop').value; if (v) slSet('stopAt', v); }
function slStopToggle(){ const on = !!(slSt.set || {}).stopAt; slSet('stopAt', on ? '' : ($('#sl-stop').value || '07:30')); }

/* ---------- één nacht ---------- */
function slOpen(i){ const n = (slSt.nights || [])[i]; if (!n) return; slNightId = n.id; show('sleepnight'); }
function enterSleepNight(){ slLoad(); slnLoad(slNightId); slPoll(); }
function slnLoad(id){ slNightCur = rdJson(Android.sleepNight(id), null); slnRender(); }
function slnFilter(v){ slFilterV = v; slnRender(); }
function slnRender(){
  const n = slNightCur;
  if (!n) { $('#sln-list').innerHTML = '<p class="note">Deze nacht is er niet meer.</p>'; $('#sln-sum').textContent = ''; $('#sln-tx').innerHTML = ''; return; }
  $('#sln-title').textContent = slNightName(n.start).replace('Nacht van ', '');
  const clips = n.clips || [], live = slSt.phase && slSt.night === n.id;
  const talk = clips.filter(k => k.done && k.kind === 'praten'), open = clips.filter(k => !k.done);
  $('#sln-sum').textContent = slTime(n.start) + ' – ' + (n.end ? slTime(n.end) : 'nu') + (n.reason && n.end ? ' · ' + n.reason : '') + ' · ' +
    (talk.length ? talk.length + ' keer gepraat' : 'niets gezegd') + (clips.length - talk.length - open.length ? ' · ' + (clips.length - talk.length - open.length) + ' keer geluid' : '');
  // Nog uit te schrijven
  const canTx = open.some(k => k.audio) && !live;
  $('#sln-tx').innerHTML = live ? '<p class="note">' + (slSt.phase === 'luistert' ? 'Luistert nog. ' : 'Wordt nu uitgeschreven: ' + (slSt.txDone || 0) + ' van ' + (slSt.txTotal || 0) + '. ') + '</p>'
    : canTx ? '<div class="card sl-txcard"><p class="note">' + open.length + (open.length === 1 ? ' stukje is' : ' stukjes zijn') + ' nog niet uitgeschreven.</p>' +
      (slSt.model ? '<button class="mini on" onclick="slnTranscribe()">Nu uitschrijven</button>' : '<button class="mini" onclick="jumpTo(\'transcripts\')">Spraakmodel downloaden</button>') + '</div>' : '';
  document.querySelectorAll('#sln-filter button').forEach(b => { const x = b.dataset.v === slFilterV; b.classList.toggle('on', x); b.setAttribute('aria-checked', x ? 'true' : 'false'); });
  const list = slFilterV === 'alles' ? clips : clips.filter(k => !k.done || k.kind === 'praten');
  $('#sln-list').innerHTML = list.map(k => {
    const kind = !k.done ? (k.audio ? 'Nog niet uitgeschreven' : 'Niet bewaard (maximum van de nacht bereikt)') : k.kind === 'praten' ? '' : 'Geluid (geen woorden)';
    const play = k.audio ? '<button class="iconbtn" onclick="slnPlay(\'' + esc(k.id) + '\')" aria-label="' + (slPlaying === k.id ? 'Stoppen' : 'Afspelen') + ' ' + esc(slTime(k.t)) + '">' + uiIcon(slPlaying === k.id ? 'stop' : 'play') + '</button>' : '';
    return '<div class="sl-clip' + (k.kind === 'praten' ? ' talk' : '') + '" role="listitem"><div class="sl-ct"><b>' + esc(slTime(k.t)) + '</b><small>' + esc(Math.max(1, Math.round(k.dur)) + ' s') + '</small></div>' +
      '<div class="sl-cx">' + (k.text ? '<p>' + esc(k.text) + '</p>' : '') + (kind ? '<small>' + esc(kind) + '</small>' : '') + '</div>' + play +
      '<button class="iconbtn" onclick="slnDel(\'' + esc(k.id) + '\')" aria-label="Wissen ' + esc(slTime(k.t)) + '">' + uiIcon('trash') + '</button></div>';
  }).join('') || '<p class="note">' + (clips.length ? 'Geen gepraat, alleen geluiden. Kies Alles om ze te zien.' : live ? 'Nog niets gehoord.' : 'Niets gehoord deze nacht.') + '</p>';
}
function slnPlay(clip){
  if (slPlaying === clip) { Android.sleepStopPlay(); slPlaying = ''; slnRender(); return; }
  const e = Android.sleepPlay(slNightId, clip);
  if (e) { toast(e); return; }
  slPlaying = clip; slnRender();
}
function slnDel(clip){
  const k = ((slNightCur || {}).clips || []).find(x => x.id === clip); if (!k) return;
  askConfirm('Dit stukje wissen?', slTime(k.t) + (k.text ? ': "' + k.text.slice(0, 80) + '"' : '') + '. Het geluid en de tekst gaan weg.', 'Wissen', () => {
    if (slPlaying === clip) slPlaying = '';
    Android.sleepDeleteClip(slNightId, clip); slnLoad(slNightId); toast('Gewist');
  });
}
function slnTranscribe(){
  const e = Android.sleepTranscribe(slNightId);
  if (e === 'model') { toast('Download eerst een spraakmodel bij Gesprekken uitschrijven'); return; }
  if (e) { toast(e); return; }
  $('#sln-tx').innerHTML = '<p class="note">Uitschrijven…</p>';
}
function slnMenu(){
  const n = slNightCur; if (!n) return;
  const talk = (n.clips || []).filter(k => k.kind === 'praten' && k.text);
  const acts = [];
  if (talk.length) acts.push(['Als notitie bewaren', () => {
    const text = talk.map(k => slTime(k.t) + ' — ' + k.text).join('\n');
    notesGet().push({ id: uid(), title: 'Slaappraat: ' + slNightName(n.start).replace('Nacht van ', ''), text, items: [], updated: Date.now() }); notesSaveNow();
    toast('Bewaard bij Notities');
  }]);
  if (!(slSt.phase && slSt.night === n.id)) acts.push(['Deze nacht wissen', () => askConfirm('Deze nacht wissen?', 'Alle stukjes van ' + slNightName(n.start).toLowerCase() + ' gaan weg.', 'Wissen', () => {
    Android.sleepStopPlay(); slPlaying = ''; Android.sleepDeleteNight(n.id); toast('Nacht gewist'); goBack();
  })]);
  openSheet(slNightName(n.start), '', acts);
}
