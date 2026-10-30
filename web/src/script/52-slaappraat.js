/* ---------- Slaappraat: 's nachts zuinig luisteren, alleen bewaren wat er te horen is, daarna uitschrijven ---------- */
let slSt = {}, slPollT = null, slNightCur = null, slNightId = '', slFilterV = 'praten', slPlaying = '', slFocusClip = '';
const SL_SENS_NOTE = { '1': 'Pakt ook zacht gemompel op (en dus vaker een geluidje).', '2': 'Gewoon praten in je slaap.', '3': 'Alleen duidelijk en hardop praten.' };

/* Alleen vervangen als er echt iets anders staat: dan blijft een tik of de focus van TalkBack gewoon werken */
function slHtml(el, html){ if (el && el._h !== html) { el.innerHTML = html; el._h = html; } }
function slLoad(){ slSt = rdJson(Android.sleepState(), {}); return slSt; }
function enterSleep(){ slLoad(); slRender(); slPoll(); }
function stopSleepPoll(){ clearTimeout(slPollT); slPollT = null; }
/* Zolang het scherm open is: elke seconde (tijdens luisteren voor de meter), anders niet */
function slPoll(){
  stopSleepPoll();
  if (current !== 'sleep' && current !== 'sleepnight') return;
  if (!slSt.phase) return;
  slPollT = setTimeout(() => { slLoad(); if (current === 'sleep') slRender(); else if (current === 'sleepnight' && slNightId) slnLoad(slNightId); slPoll(); }, 1000);
}
window.onSleep = function(o){
  o = o || {};
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
    slHtml(st, '<div class="sl-live"><span class="sl-dot" aria-hidden="true"></span><b>Luistert</b> sinds ' + esc(slTime(s.since)) + '</div><p class="note">' +
      esc((s.heard ? s.heard + ' keer iets gehoord' : 'Nog niets gehoord') + (s.stopAt ? ' · stopt om ' + slTime(s.stopAt) : '')) + '</p>' + slLiveHtml(s));
    btn.textContent = 'Stoppen'; btn.classList.add('ghost');
    meter.style.display = '';
    // Meter: -70 dB (stil) … -20 dB (hard)
    const pct = v => Math.max(0, Math.min(100, (v + 70) * 2));
    $('#sl-meter-bar').style.width = pct(s.level) + '%';
    $('#sl-meter-floor').style.left = pct(s.floor) + '%';
  } else if (s.phase === 'proef') {
    const left = Math.ceil((s.probeLeft || 0) / 1000);
    // Het aftellen staat buiten de voorleesregio (anders leest TalkBack elke seconde voor); de uitkomst wel
    slHtml(st, '<span class="sr-only">Proef van 30 seconden loopt</span><div aria-hidden="true"><div class="sl-live"><span class="sl-dot"></span><b>Proef</b>: praat eens zoals je in je slaap zou doen · nog ' + left + ' s</div>' +
      '<p class="note sl-probe' + (s.hearing ? ' on' : '') + '">' + (s.hearing ? 'Hoort nu iets' : 'Stil') + ' · ' + (s.probeHits || 0) + ' keer opgepikt</p></div>');
    btn.textContent = 'Stoppen'; btn.classList.add('ghost');
    meter.style.display = '';
    const pct = v => Math.max(0, Math.min(100, (v + 70) * 2));
    $('#sl-meter-bar').style.width = pct(s.level) + '%';
    $('#sl-meter-floor').style.left = pct(s.floor) + '%';
  } else if (s.phase === 'uitschrijven') {
    slHtml(st, '<div class="sl-live"><b>Uitschrijven</b>… ' + (s.txDone || 0) + ' van ' + (s.txTotal || 0) + '</div><p class="note">Op je telefoon, met het spraakmodel van Gesprekken uitschrijven. Tik je op luisteren, dan stopt dit; de rest kun je later bij de nacht uitschrijven.</p>');
    btn.textContent = 'Opnieuw beginnen met luisteren'; btn.classList.remove('ghost'); meter.style.display = 'none';
  } else {
    slHtml(st, s.message ? '<p class="note sl-msg">' + esc(s.message) + '</p>' : '');
    btn.textContent = 'Begin met luisteren'; btn.classList.remove('ghost'); meter.style.display = 'none';
  }
  $('#sl-probe').style.display = s.phase ? 'none' : '';
  // Accu: gemiddeld per uur in nachten zonder lader
  const bh = (s.nights || []).map(n => slBatPerHour(n)).filter(v => v >= 0);
  $('#sl-bat').textContent = bh.length ? 'Zonder lader kostte Slaappraat gemiddeld ' + (bh.reduce((a, b) => a + b, 0) / bh.length).toFixed(1).replace('.', ',') + '% accu per uur (' + bh.length + (bh.length === 1 ? ' nacht' : ' nachten') + ').' : '';
  $('#sl-bat').style.display = bh.length ? '' : 'none';
  const ro = !!set.remindAt, rt = $('#sl-rem');
  if (document.activeElement !== rt) rt.value = set.remindAt || '22:30';
  $('#sl-remrow').style.display = ro ? '' : 'none';
  const rb = $('#sl-rem-on'); rb.classList.toggle('on', ro); rb.setAttribute('aria-checked', ro ? 'true' : 'false');
  $('#sl-rem-n').textContent = ro ? 'Elke avond rond ' + set.remindAt + ' een melding om Slaappraat aan te zetten' : 'Een melding om Slaappraat aan te zetten';
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
  const dOn = set.direct !== false, dr = $('#sl-direct');
  dr.classList.toggle('on', dOn); dr.setAttribute('aria-checked', dOn ? 'true' : 'false');
  $('#sl-directrow').style.display = set.transcribe !== false && s.model ? '' : 'none';
  $('#sl-direct-n').textContent = dOn ? 'Aan: al tijdens de nacht, dus je ziet meteen wat je zei. Kost accu en kan de telefoon wat warm maken; ' + slWhenShort(set) + ' Wat overblijft gebeurt na het luisteren.'
    : 'Uit: pas na het luisteren uitschrijven.';
  // Hoeveel ruimte Slaappraat inneemt
  const tot = s.bytes || 0;
  $('#sl-size').textContent = (s.nights || []).length ? 'Slaappraat gebruikt ' + fmtB(tot) + ' op je telefoon (' + s.nights.length + (s.nights.length === 1 ? ' nacht' : ' nachten') + ').' : '';
  const no = $('#sl-noise'); no.classList.toggle('on', !!set.keepNoise); no.setAttribute('aria-checked', set.keepNoise ? 'true' : 'false');
  const quiet = set.quiet !== false;
  const qu = $('#sl-quiet'); qu.classList.toggle('on', quiet); qu.setAttribute('aria-checked', quiet ? 'true' : 'false');
  $('#sl-quietrow').style.display = quiet ? '' : 'none';
  $('#sl-quiet-n').textContent = quiet ? "Aan: de melding is stil, niet op het vergrendelscherm en zonder tijdteller; 's ochtends geen melding (kijk hier wat er gehoord is)"
    : "Uit: een gewone, stille melding met tijdteller en stopknop, en 's ochtends een melding als er iets gehoord is";
  // Nachten
  const ns = s.nights || [];
  slHtml($('#sl-nights'), ns.map((n, i) => {
    const live = n.id === s.night && s.phase, l = s.live || {}, lt = live && s.phase === 'luistert' && (l.talk || l.noise);
    const first = live && s.phase === 'luistert' ? l.text || '' : n.first;
    const sum = (live ? (s.phase === 'luistert' ? 'Bezig…' + (lt ? ' · ' + slLiveLine(l) : '') : 'Uitschrijven…') : (n.talk ? n.talk + ' keer gepraat' : n.open ? n.open + ' keer iets gehoord' : 'Niets gezegd') + (n.noise ? ' · ' + n.noise + (n.noise === 1 ? ' geluid' : ' geluiden') : '') + (n.end ? ' · ' + slDur(n.end - n.start) : '')) +
      (n.bytes > 65536 ? ' · ' + fmtB(n.bytes) : '');
    return '<button class="strow" onclick="slOpen(' + i + ')"><span class="stic">' + uiIcon('moon') + '</span><span class="sttxt"><b>' + esc(slNightName(n.start)) + '</b><small>' + esc(sum) + '</small>' +
      (first ? '<small class="sl-first">"' + esc(first) + '"</small>' : '') + '</span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>';
  }).join('') || '<p class="note" style="padding:14px 16px">Nog geen nachten. Tik vanavond op Begin met luisteren.</p>');
}

/* Meteen uitschrijven terwijl hij luistert: wat er al uitgeschreven is, het laatste wat je zei, en waarom het wacht */
function slDirectOn(s){ const set = s.set || {}; return set.transcribe !== false && set.direct !== false && !!s.model; }
function slLiveLine(l){ return (l.talk ? l.talk + ' keer gepraat' : 'Nog niets gezegd') + (l.noise ? ' · ' + l.noise + (l.noise === 1 ? ' geluid' : ' geluiden') : ''); }
function slLiveHtml(s){
  if (!slDirectOn(s)) return '';
  const l = s.live || {};
  if (!l.talk && !l.noise && !l.open && !l.wait) return '';
  return '<p class="note sl-livetx">' + esc(slLiveLine(l) + (l.open ? ' · ' + l.open + ' nog uit te schrijven' : '') + (l.busy ? ' · bezig…' : '')) + '</p>' +
    (l.text ? '<p class="sl-first sl-lasttx">' + esc(slTime(l.at)) + ': "' + esc(l.text) + '"</p>' : '') +
    (l.wait ? '<p class="note sl-wait">Meteen uitschrijven wacht: ' + esc(l.wait) + '. Wat overblijft wordt na het luisteren uitgeschreven.</p>' : '');
}

/* Accuverbruik per uur van een nacht zonder lader, of -1 (zoals SleepTalk.batPerHour) */
function slBatPerHour(n){
  if (!n || n.charged || n.batStart < 0 || n.batEnd < 0 || !n.end || n.end <= n.start || n.batEnd > n.batStart) return -1;
  const h = (n.end - n.start) / 3600000; return h < 0.5 ? -1 : (n.batStart - n.batEnd) / h;
}
function slProbe(){
  const e = Android.sleepProbe();
  if (e === 'mic') { Android.permRequest('mic'); toast('Geef de app toegang tot de microfoon en tik dan opnieuw'); return; }
  if (e === 'notif') { Android.permRequest('notif'); toast('Sta meldingen toe en tik dan opnieuw'); return; }
  if (e) { toast(e); return; }
  slLoad(); slRender(); slPoll();
}
function slRemToggle(){
  const on = !!(slSt.set || {}).remindAt;
  if (!on && slSt.notifOk === false) { Android.permRequest('notif'); toast('Sta meldingen toe en tik dan opnieuw'); return; }
  slSet('remindAt', on ? '' : ($('#sl-rem').value || '22:30'));
  if (!on) toast('Elke avond een herinnering om ' + ((slSt.set || {}).remindAt || '22:30'));
}
function slSetRem(){ const v = $('#sl-rem').value; if (v) slSet('remindAt', v); }
function slToggle(){
  if (slSt.phase === 'proef') { Android.sleepStop(); setTimeout(() => { slLoad(); slRender(); slPoll(); }, 400); return; }
  if (slSt.phase === 'luistert') { Android.sleepStop(); toast('Stoppen…'); setTimeout(() => { slLoad(); slRender(); slPoll(); }, 600); return; }
  const e = Android.sleepStart();
  if (e === 'mic') { Android.permRequest('mic'); toast('Geef de app toegang tot de microfoon en tik dan opnieuw'); return; }
  if (e === 'notif') { Android.permRequest('notif'); toast('Sta meldingen toe (je ziet dan dat hij luistert) en tik dan opnieuw'); return; }
  if (e) { toast(e); return; }
  toast('Slaappraat luistert. Welterusten!');
  setTimeout(() => { slLoad(); slRender(); slPoll(); }, 500);
}
function slSet(name, v){ const e = Android.sleepSet(name, String(v)); if (e) { toast(e); return; } slLoad(); if (current === 'sleepset') slsRender(); else slRender(); }
function slSwitch(name){ const set = slSt.set || {}; const now = name === 'transcribe' || name === 'quiet' || name === 'direct' ? set[name] !== false : !!set[name]; slSet(name, !now ? 'true' : 'false'); }
function slSetStop(){ const v = $('#sl-stop').value; if (v) slSet('stopAt', v); }
function slStopToggle(){ const on = !!(slSt.set || {}).stopAt; slSet('stopAt', on ? '' : ($('#sl-stop').value || '07:30')); }

/* ---------- één nacht ---------- */
function slOpen(i){ const n = (slSt.nights || [])[i]; if (!n) return; slNightId = n.id; show('sleepnight'); }
function enterSleepNight(){ slLoad(); slnLoad(slNightId); slPoll(); }
function slnLoad(id){ slNightCur = rdJson(Android.sleepNight(id), null); slnRender(); }
function slnFilter(v){ slFilterV = v; slnRender(); }
function slnRender(){
  const n = slNightCur;
  if (!n) { slHtml($('#sln-list'), '<p class="note">Deze nacht is er niet meer.</p>'); $('#sln-sum').textContent = ''; slHtml($('#sln-tx'), ''); return; }
  $('#sln-title').textContent = slNightName(n.start).replace('Nacht van ', '');
  const clips = n.clips || [], live = (slSt.phase === 'luistert' || slSt.phase === 'uitschrijven') && slSt.night === n.id;
  const talk = clips.filter(k => k.done && k.kind === 'praten'), open = clips.filter(k => !k.done);
  const noise = clips.filter(k => k.done && k.kind === 'geluid').length;
  $('#sln-sum').textContent = slTime(n.start) + ' – ' + (n.end ? slTime(n.end) : 'nu') + (n.reason && n.end ? ' · ' + n.reason : '') + ' · ' +
    (talk.length ? talk.length + ' keer gepraat' : 'niets gezegd') + (noise ? ' · ' + noise + ' keer geluid' : '') +
    (n.batStart >= 0 && n.batEnd >= 0 && n.end ? ' · accu ' + n.batStart + '% → ' + n.batEnd + '%' + (n.charged ? ' (aan de lader)' : '') : '');
  // Nog uit te schrijven
  const canTx = open.some(k => k.audio) && !live;
  slHtml($('#sln-tx'), live ? '<p class="note">' + (slSt.phase === 'luistert' ? (slDirectOn(slSt) ? 'Luistert nog. Wat je zegt, verschijnt hier zodra het uitgeschreven is.' : 'Luistert nog. ') : 'Wordt nu uitgeschreven: ' + (slSt.txDone || 0) + ' van ' + (slSt.txTotal || 0) + '. ') + '</p>'
    : canTx ? '<div class="card sl-txcard"><p class="note">' + open.length + (open.length === 1 ? ' stukje is' : ' stukjes zijn') + ' nog niet uitgeschreven.</p>' +
      (slSt.model ? '<button class="mini on" onclick="slnTranscribe()">Nu uitschrijven</button>' : '<button class="mini" onclick="jumpTo(\'transcripts\')">Spraakmodel downloaden</button>') + '</div>' : '');
  document.querySelectorAll('#sln-filter button').forEach(b => { const x = b.dataset.v === slFilterV; b.classList.toggle('on', x); b.setAttribute('aria-checked', x ? 'true' : 'false'); });
  const list = slFilterV === 'alles' ? clips : clips.filter(k => !k.done || k.kind === 'praten');
  slHtml($('#sln-list'), list.map(k => {
    const kind = !k.done ? (k.audio ? 'Nog niet uitgeschreven' : k.nobk ? 'Niet uitgeschreven (geluid zat niet in de backup)' : slNotKept(k)) : k.kind === 'praten' ? '' : k.kind === 'onleesbaar' ? 'Kon dit stukje niet lezen' : 'Geluid (geen woorden)';
    const play = k.audio ? '<button class="iconbtn" onclick="slnPlay(\'' + esc(k.id) + '\')" aria-label="' + (slPlaying === k.id ? 'Stoppen' : 'Afspelen') + ' ' + esc(slTime(k.t)) + '">' + uiIcon(slPlaying === k.id ? 'stop' : 'play') + '</button>' : '';
    return '<div class="sl-clip' + (k.kind === 'praten' ? ' talk' : '') + '" role="listitem" data-clip="' + esc(k.id) + '"><div class="sl-ct"><b>' + esc(slTime(k.t)) + '</b><small>' + esc(Math.max(1, Math.round(k.dur)) + ' s') + '</small></div>' +
      '<div class="sl-cx">' + (k.text ? '<p>' + esc(k.text) + '</p>' : '') + (kind ? '<small>' + esc(kind) + '</small>' : '') + '</div>' + play +
      '<button class="iconbtn" onclick="slnDel(\'' + esc(k.id) + '\')" aria-label="Wissen ' + esc(slTime(k.t)) + '">' + uiIcon('trash') + '</button></div>';
  }).join('') || '<p class="note">' + (clips.length ? 'Geen gepraat, alleen geluiden. Kies Alles om ze te zien.' : live ? 'Nog niets gehoord.' : 'Niets gehoord deze nacht.') + '</p>');
  // Vanuit Zoeken: naar het gevonden stukje
  if (/^t:/.test(slFocusClip)) { const t = +slFocusClip.slice(2), k = clips.find(x => x.t === t); slFocusClip = k ? k.id : ''; }
  if (slFocusClip) { const el = document.querySelector('#sln-list [data-clip="' + slFocusClip + '"]'); slFocusClip = ''; if (el) setTimeout(() => { el.scrollIntoView({ block: 'center' }); el.classList.add('gsflash'); setTimeout(() => el.classList.remove('gsflash'), 1600); }, 80); }
}
/* Waarom het geluid van een stukje niet bewaard is (zie Meer instellingen → Opslag) */
function slNotKept(k){
  return k.why === 'plaats' ? 'Geluid weggedaan om plaats te maken voor iets nieuws (maximum van de nacht bereikt)'
    : k.why === 'ruimte' ? 'Niet bewaard: te weinig opslag vrij op je telefoon'
    : k.why === 'opslaan' ? 'Niet bewaard: opslaan lukte niet'
    : k.why === 'max' ? 'Niet bewaard: het maximum aan geluid voor deze nacht was bereikt (zie Meer instellingen)'
    : 'Niet bewaard (maximum van de nacht bereikt)';
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
  if (e === 'mic') { Android.permRequest('mic'); return; }
  if (e) { toast(e); return; }
  slLoad(); slnRender(); slPoll();
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

/* ---------- meer instellingen ---------- */
const SL_MBH = { aac: 14.4, wav: 115 }; // MB per uur geluid (AAC 32 kbit/s, WAV 16 kHz mono)
function slWhenShort(set){
  const w = set.directWhen || 'accu';
  return w === 'lader' ? 'alleen aan de lader.' : w === 'altijd' ? 'ook zonder lader (tot hij stopt bij ' + (set.stopBat || 15) + '% accu).' : 'aan de lader altijd, zonder lader vanaf ' + (set.directBat || 40) + '% accu.';
}
function slChips(id, v){ document.querySelectorAll('#' + id + ' button').forEach(b => { const x = b.dataset.v === String(v); b.classList.toggle('on', x); b.setAttribute('aria-checked', x ? 'true' : 'false'); }); }
function enterSleepSet(){ slLoad(); slsRender(); }
function slsRender(){
  const s = slSt, set = s.set || {}, w = set.directWhen || 'accu';
  if (!$('#sls-when')) return;
  slChips('sls-when', w); slChips('sls-dbat', set.directBat || 40); slChips('sls-model', set.txModel || '');
  slChips('sls-max', set.maxAudio == null ? 8 : set.maxAudio); slChips('sls-full', set.whenFull || 'geluid'); slChips('sls-free', set.minFree || 500);
  slChips('sls-fmt', set.format || 'aac'); slChips('sls-clip', set.clipMax || 60); slChips('sls-hang', set.hang || 1500); slChips('sls-pre', set.pre || 2); slChips('sls-stop', set.stopBat || 15);
  $('#sls-dbatrow').style.display = w === 'accu' ? '' : 'none';
  $('#sls-when-n').textContent = (w === 'lader' ? 'Alleen als de telefoon aan de lader ligt; anders na het luisteren.'
    : w === 'altijd' ? 'Ook zonder lader en met weinig accu, tot Slaappraat stopt bij ' + (set.stopBat || 15) + '%. Kost het meeste accu.'
    : 'Aan de lader altijd; zonder lader alleen vanaf de accu hieronder en niet met batterijbesparing aan.') +
    ' Nooit tijdens een gesprek of als de telefoon warm is.' + (set.direct === false || set.transcribe === false ? ' (Meteen uitschrijven staat nu uit.)' : '');
  const ms = s.models || [], want = ms.find(m => m.id === set.txModel);
  $('#sls-model-n').textContent = !s.model ? 'Er is nog geen spraakmodel: download het bij Gesprekken uitschrijven.'
    : (want && !want.ok ? want.label.replace(/ \(.*/, '') + ' is nog niet gedownload (bij Gesprekken uitschrijven); nu: ' : 'Nu: ') + s.model + '. Nauwkeurig schrijft beter uit, maar is ±3 keer zo langzaam en kost meer accu.';
  const fmt = set.format === 'wav' ? 'wav' : 'aac', h = set.maxAudio == null ? 8 : set.maxAudio;
  $('#sls-max-n').textContent = (h ? h + ' uur ' + fmt.toUpperCase() + ' is hooguit ±' + Math.round(h * SL_MBH[fmt]) + ' MB per nacht.' : 'Onbeperkt: alleen de vrije ruimte hieronder telt (' + fmt.toUpperCase() + ' is ±' + SL_MBH[fmt] + ' MB per uur).').replace('.4 MB', ',4 MB') +
    ' Alleen geluid dat nog bewaard is telt mee.';
  const f = set.whenFull || 'geluid';
  $('#sls-full-n').textContent = f === 'oudste' ? 'Het nieuwste gaat altijd voor: het geluid van de oudste stukjes gaat weg, ook van gepraat en ook als het nog niet uitgeschreven is (uitgeschreven tekst blijft).'
    : f === 'stop' ? 'Er gaat niets weg; van nieuwe stukjes wordt dan alleen het tijdstip bewaard.'
    : 'Pas als het echt vol is: iets nieuws dat op praten lijkt, neemt de plaats in van het oudste geluid (eerst wat al uitgeschreven is en geen woorden had). Gepraat gaat nooit weg; nieuw geluid wordt dan niet meer bewaard.';
  $('#sls-fmt-n').textContent = fmt === 'wav' ? '±115 MB per uur geluid (8 keer zo groot als AAC): alleen als je het onbewerkte geluid wilt.' : '±14 MB per uur geluid; goed genoeg om terug te luisteren en uit te schrijven.';
  const ns = s.nights || [];
  $('#sls-size').textContent = 'Nu: ' + fmtB(s.bytes || 0) + ' in ' + ns.length + (ns.length === 1 ? ' nacht' : ' nachten') + (ns.length ? ' (grootste nacht ' + fmtB(Math.max(...ns.map(n => n.bytes || 0))) + ')' : '') + '. Oude nachten gaan vanzelf weg na ' + ((set.keep || 14) === 7 ? '1 week' : (set.keep || 14) === 30 ? '30 dagen' : '2 weken') + '.';
}
function slsReset(){
  askConfirm('Terug naar standaard?', 'Alle instellingen op dit scherm gaan terug naar hoe ze standaard staan. Je nachten blijven.', 'Terugzetten', () => { slSet('reset', ''); toast('Terug naar standaard'); });
}
