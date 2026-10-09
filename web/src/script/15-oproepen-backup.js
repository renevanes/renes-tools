/* ---------- Oproepen-backup ---------- */
let callsPoll = null, callsTmr = null, callsLastDone = false;
// Filterstatus: richting, periode, wie, duur en eventueel één persoon (nummers van een contact).
const callsF = { kind: 'all', period: 'all', from: '', to: '', who: 'all', dur: 'all', person: null };
function callsInfo(){ try { return JSON.parse(Android.callsInfo()); } catch(e){ return {}; } }
function fmtCallDur(s){ if (!s) return ''; const h = Math.floor(s/3600), m = Math.floor(s%3600/60), x = s%60;
  return h ? h + ':' + String(m).padStart(2,'0') + ':' + String(x).padStart(2,'0') : m + ':' + String(x).padStart(2,'0'); }
function fmtHours(s){ const h = Math.floor(s/3600), m = Math.round(s%3600/60); return h ? h + ' u ' + m + ' m' : m + ' min'; }
function dayStart(d){ const x = new Date(d); x.setHours(0,0,0,0); return x.getTime(); }
/** Filter als JSON voor de app (periode omgerekend naar tijden). */
function callsFilterJson(){
  const f = { kind: callsF.kind, q: $('#calls-search').value.trim(), who: callsF.who };
  const now = Date.now(), today = dayStart(now);
  switch (callsF.period) {
    case 'today': f.from = today; break;
    // Dagen terugtellen met de kalender (niet met 24 uur): klopt ook rond de wissel van zomer- en wintertijd
    case 'yesterday': f.from = daysAgo(1); f.to = today; break;
    case '7': f.from = daysAgo(6); break;
    case '30': f.from = daysAgo(29); break;
    case 'year': f.from = new Date(new Date().getFullYear(), 0, 1).getTime(); break;
    case 'custom':
      if (callsF.from) f.from = dayStart(callsF.from + 'T00:00');
      if (callsF.to) f.to = dayStart(callsF.to + 'T00:00') + 864e5;
      break;
  }
  if (callsF.dur === '0') f.maxDur = 0; else if (callsF.dur !== 'all') f.minDur = +callsF.dur;
  if (callsF.person) f.numbers = callsF.person.numbers;
  return JSON.stringify(f);
}
function callsFilterCount(){
  return (callsF.period !== 'all' ? 1 : 0) + (callsF.who !== 'all' ? 1 : 0) + (callsF.dur !== 'all' ? 1 : 0);
}
function enterCalls(fromHome){
  if (fromHome) callsF.person = null;
  const i = callsInfo(); callsPermCache = !!i.perm;
  try { const st = JSON.parse(Android.callsStatus()); callsLastDone = !st.running; } catch(e){}
  $('#calls-result').className = 'result';
  $('#calls-perm').style.display = i.perm ? 'none' : 'block';
  $('#calls-perm-btn').textContent = store.get('callsAsked', false) && !i.perm ? 'Instellingen openen' : 'Toegang geven';
  $('#calls-home').style.display = i.perm ? 'block' : 'none';
  $('#calls-contacts').style.display = (i.perm && !i.contacts) ? 'block' : 'none';
  callsSyncUi();
  if (i.perm) loadCalls();
  callsPollOnce();
  stopCallsPoll(); callsPoll = setInterval(callsPollOnce, 600);
}
function stopCallsPoll(){ if (callsPoll) { clearInterval(callsPoll); callsPoll = null; } }
window.onCallsChanged = function(){ if (current === 'calls') enterCalls(); };
function callsAskPerm(){
  if (store.get('callsAsked', false) && !Android.callsHasPermission()) { Android.openAppSettings(); return; }
  store.set('callsAsked', true); Android.callsRequestPermission();
}
function callsSyncUi(){
  document.querySelectorAll('#calls-filter button').forEach(b => b.classList.toggle('on', b.dataset.f === callsF.kind));
  document.querySelectorAll('#cf-period button').forEach(b => b.classList.toggle('on', b.dataset.v === callsF.period));
  document.querySelectorAll('#cf-who button').forEach(b => b.classList.toggle('on', b.dataset.v === callsF.who));
  document.querySelectorAll('#cf-dur button').forEach(b => b.classList.toggle('on', b.dataset.v === callsF.dur));
  $('#cf-custom').style.display = callsF.period === 'custom' ? 'flex' : 'none';
  $('#cf-from').value = callsF.from; $('#cf-to').value = callsF.to;
  const n = callsFilterCount();
  $('#calls-fcount').textContent = n ? n : '';
  $('#calls-fbtn').classList.toggle('on', n > 0);
  // Actieve filters als chips om snel weg te halen
  const chips = [];
  const lab = (id, v) => { const b = document.querySelector('#' + id + ' button[data-v="' + v + '"]'); return b ? b.textContent : v; };
  if (callsF.person) chips.push(['person', 'Met: ' + callsF.person.name]);
  if (callsF.period !== 'all') chips.push(['period', callsF.period === 'custom'
      ? (callsF.from ? fmtDay(callsF.from) : '…') + ' – ' + (callsF.to ? fmtDay(callsF.to) : 'nu') : lab('cf-period', callsF.period)]);
  if (callsF.who !== 'all') chips.push(['who', lab('cf-who', callsF.who)]);
  if (callsF.dur !== 'all') chips.push(['dur', lab('cf-dur', callsF.dur)]);
  $('#calls-active').innerHTML = chips.map(c => '<button data-k="' + c[0] + '" onclick="callsDropFilter(this.dataset.k)">' + esc(c[1]) + ' <span aria-hidden=true>×</span></button>').join('');
}
/** Begin van de dag, n dagen geleden (lokale tijd). */
function daysAgo(n){ const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - n); return d.getTime(); }
/** Lokale datum als jjjj-mm-dd (toISOString geeft de UTC-datum: tussen 0 en 2 uur 's nachts een dag te vroeg). */
function isoDay(t){ const d = new Date(t); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
function fmtDay(iso){ return new Date(iso + 'T12:00').toLocaleDateString('nl-NL', {day:'numeric', month:'short', year:'numeric'}); }
function callsSetKind(f){ callsF.kind = f; callsSyncUi(); loadCalls(); }
function callsToggleFilters(){ const p = $('#calls-fpanel'); p.style.display = p.style.display === 'none' ? 'block' : 'none'; }
function callsPickChip(group, v){
  if (group === 'cf-period') { callsF.period = v; if (v === 'custom' && !callsF.from) callsF.from = isoDay(daysAgo(29)); }
  if (group === 'cf-who') callsF.who = v;
  if (group === 'cf-dur') callsF.dur = v;
  callsSyncUi(); loadCalls();
}
function callsCustomDates(){ callsF.from = $('#cf-from').value; callsF.to = $('#cf-to').value; callsSyncUi(); loadCalls(); }
function callsDropFilter(k){
  if (k === 'person') callsF.person = null;
  if (k === 'period') { callsF.period = 'all'; callsF.from = callsF.to = ''; }
  if (k === 'who') callsF.who = 'all';
  if (k === 'dur') callsF.dur = 'all';
  callsSyncUi(); loadCalls();
}
function callsClearFilters(){
  Object.assign(callsF, { kind: 'all', period: 'all', from: '', to: '', who: 'all', dur: 'all', person: null });
  $('#calls-search').value = '';
  callsSyncUi(); loadCalls();
}
function callsSearchDo(){ clearTimeout(callsTmr); callsTmr = setTimeout(loadCalls, 250); }
let callsShown = [], callsSel = { filtered: false, count: 0 };
function loadCalls(){
  let d; try { d = JSON.parse(Android.callsList(callsFilterJson())); } catch(e){ return; }
  const el = $('#calls-list');
  if (d.error) { el.innerHTML = '<p class=note style="padding:14px">' + esc(d.error) + '</p>'; return; }
  callsSel = { filtered: !!d.filtered, count: d.count || 0 };
  $('#calls-sum-title').textContent = d.filtered ? 'Selectie' : 'Overzicht';
  $('#calls-sum').innerHTML =
    '<div><b>' + (d.in||0) + '</b><small>inkomend<br>' + fmtHours(d.secIn||0) + '</small></div>' +
    '<div><b>' + (d.out||0) + '</b><small>uitgaand<br>' + fmtHours(d.secOut||0) + '</small></div>' +
    '<div><b>' + (d.missed||0) + '</b><small>gemist<br>&nbsp;</small></div>';
  $('#calls-sum-note').textContent = (d.count || 0).toLocaleString('nl-NL') + (d.filtered ? ' van ' + (d.total||0).toLocaleString('nl-NL') : '') +
    ' oproepen' + (d.people ? ' · ' + d.people + (d.people === 1 ? ' persoon' : ' personen') : '') +
    (d.count ? ' · ' + fmtDayT(d.first) + (d.first !== d.last ? ' – ' + fmtDayT(d.last) : '') : '');
  $('#calls-export-sel').style.display = d.filtered && d.count ? 'block' : 'none';
  $('#calls-export-sel').textContent = 'Alleen deze selectie exporteren (' + (d.count||0).toLocaleString('nl-NL') + ')';
  callsShown = d.calls || [];
  el.innerHTML = callsShown.map((c, i) =>
    '<button class="' + esc(c.kind) + '" onclick="callsPick(' + i + ')">' +
    '<span class=r1><b>' + (c.fav ? '★ ' : '') + esc(c.name) + '</b><time>' + esc(fmtD(c.date)) + '</time></span>' +
    '<span class=r2><span class=k>' + esc(c.label) + '</span>' + (c.ck || !c.number ? '' : '<span class=unk>onbekend</span> ') + (c.number && c.number !== c.name ? esc(c.number) + ' · ' : '') + esc(fmtCallDur(c.duration)) + '</span></button>').join('');
  if (!callsShown.length) el.innerHTML = '<p class=note style="padding:14px">' + (d.filtered ? 'Geen oproepen met deze filters. <a href="#" onclick="callsClearFilters();return false">Filters wissen</a>' : 'Geen oproepen gevonden.') + '</p>';
}
function fmtDayT(t){ return new Date(t).toLocaleDateString('nl-NL', {day:'numeric', month:'short', year:'numeric'}); }
function callsPick(i){
  const c = callsShown[i]; if (!c) return;
  if (!c.number) { toast('Afgeschermd nummer'); return; }
  const acts = [];
  if (c.ck) acts.push(['Contact bekijken', () => jumpTo('contact', () => ctOpen(c.ck, String(c.cid)))]);
  else acts.push(['Toevoegen aan contacten', () => Android.contactsAddNumber(c.number)]);
  if (!callsF.person) acts.push(['Alle oproepen met ' + (c.ck ? c.name : c.number), () => { callsF.person = { name: c.ck ? c.name : c.number, numbers: [c.number] }; callsSyncUi(); loadCalls(); window.scrollTo(0, 0); }]);
  acts.push(['Opnieuw bellen met Auto redial', () => { jumpTo('redial'); $('#num').value = c.number; nameFor = c.ck ? c.name : ''; $('#pname').textContent = nameFor; }]);
  openSheet(c.name, (c.number !== c.name ? c.number + ' · ' : '') + c.label + ' · ' + fmtFullCall(c.date) + (c.duration ? ' · ' + fmtCallDur(c.duration) : ''), acts);
}
function fmtFullCall(t){ return new Date(t).toLocaleString('nl-NL', {weekday:'short', day:'numeric', month:'short', year:'numeric', hour:'2-digit', minute:'2-digit'}); }
function callsExport(sel){
  if (!plainOk('De oproepenlijst', () => callsExport(sel))) return;
  const err = Android.callsExport(sel ? callsFilterJson() : '');
  if (err) { toast(err); return; }
  $('#calls-result').className = 'result'; callsLastDone = false; setTimeout(callsPollOnce, 150);
}
['cf-period', 'cf-who', 'cf-dur'].forEach(id => {
  const g = document.getElementById(id);
  if (g) g.addEventListener('click', e => { const b = e.target.closest('button'); if (b) callsPickChip(id, b.dataset.v); });
});
/** Alle oproepen met een contact (vanuit Contacten). */
function callsForPerson(name, numbers){
  Object.assign(callsF, { kind: 'all', period: 'all', from: '', to: '', who: 'all', dur: 'all' });
  callsF.person = { name, numbers };
  $('#calls-search').value = '';
  jumpTo('calls');
}
let callsPermCache = false;
function callsPollOnce(){
  let s; try { s = JSON.parse(Android.callsStatus()); } catch(e){ return; }
  $('#calls-run').style.display = s.running ? 'block' : 'none';
  $('#calls-home').style.display = (s.running || !callsPermCache) ? 'none' : 'block';
  if (s.running) {
    $('#calls-bar').style.width = (s.total ? 100 * s.done / s.total : 0).toFixed(0) + '%';
  } else if (s.ok !== undefined && !callsLastDone) {
    callsLastDone = true;
    const r = $('#calls-result');
    r.textContent = s.ok ? '✓ ' + s.count + ' oproepen geëxporteerd naar de map Oproepen backup' : 'Mislukt: ' + (s.error || '');
    r.className = 'result on ' + (s.ok ? 'answered' : 'error');
    refreshCallsTile(s, true);
  }
  if (s.running) refreshCallsTile(s);
}
let callsTileRunning = null;
function refreshCallsTile(s, force){
  const t = $('#tile-calls'); if (!t) return;
  const run = !!(s && s.running);
  if (!force && callsTileRunning === run && s) return;
  callsTileRunning = run;
  t.classList.toggle('live', run);
  const sub = $('#tile-calls-sub');
  if (sub) { const i = run ? {} : callsInfo(); sub.textContent = (s && s.running) ? 'Bezig met exporteren…' : (i.lastExport ? 'Laatste export: ' + fmtD(i.lastExport) : 'Bewaar je belgeschiedenis'); }
}

