/* ---------- Agenda ----------
 * Dag, week, maand en lijst, met ISO-weeknummers. Zoeken kijkt ook in het verleden.
 * Op de telefoon rekent Java (Agenda). agSpiegel* is dezelfde weergave voor de tests. */
let agStaat = null, agView = 'week', agAnker = '', agKlaar = false, agData = null, agEdit = null, agZoekT = null;

function agEnter(name){
  if (typeof Android.agendaStaat !== 'function') { agOud(); return; }
  agStaat = rdJson(Android.agendaStaat(), {}) || {};
  if (name === 'agenda') {
    if (!agKlaar) { agView = agStaat.view || 'week'; agAnker = rgToday(); agKlaar = true; }
    const z = $('#ag-zoek'); if (z && !z.value.trim()) agLaad(); else agZoekNu();
    agBalk();
  } else if (name === 'agaset') agSetRender();
  else if (name === 'agafspraak') { if (!agEdit) agNieuwKlaar(); agFormRender(); }
}
function agOud(){
  const el = $('#ag-oud'); if (el) el.hidden = false;
  ['ag-perm', 'ag-views', 'ag-nav', 'ag-lijst', 'ag-nieuw'].forEach(id => { const e = $('#' + id); if (e) e.hidden = true; });
}
function agBalk(){
  const b = $('#ag-perm'); if (!b) return;
  const mag = !agStaat || agStaat.perm !== false;
  b.hidden = mag;
}
function agPerm(){ if (typeof Android.permRequest === 'function') Android.permRequest('agendasync'); }
function agKleur(k){ return /^#[0-9A-Fa-f]{6}$/.test(String(k || '')) ? k : ''; }
function agPlusMaand(dag, n){
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(dag || '')); if (!m) return dag || rgToday();
  const d = new Date(Date.UTC(+m[1], +m[2] - 1 + n, 1));
  const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  return rgKey(d.getUTCFullYear(), d.getUTCMonth() + 1, Math.min(+m[3], last));
}

function agZicht(v){
  agView = v;
  const z = $('#ag-zoek'); if (z) z.value = '';
  if (typeof Android.agendaZet === 'function') agStaat = rdJson(Android.agendaZet(JSON.stringify({ view: v })), agStaat);
  agLaad();
}
function agStap(d){
  agAnker = (agView === 'maand' || agView === 'lijst') ? agPlusMaand(agAnker, d) : rgDayAdd(agAnker, d * (agView === 'week' ? 7 : 1));
  agLaad();
}
function agVandaag(){ agAnker = rgToday(); agLaad(); }
function agLaad(){
  if (typeof Android.agendaToon !== 'function') return;
  agData = rdJson(Android.agendaToon(agView, agAnker, weekStart()), {}) || {};
  if (agData.anker) agAnker = agData.anker;
  agTeken();
}
function agZoek(){ clearTimeout(agZoekT); agZoekT = setTimeout(agZoekNu, 200); }
function agZoekNu(){
  const q = ($('#ag-zoek') && $('#ag-zoek').value || '').trim();
  if (!q) { agLaad(); return; }
  const r = rdJson(Android.agendaZoek(q, '', ''), { items: [] }) || { items: [] };
  agData = { view: 'zoek', items: r.items || [] };
  const n = (r.items || []).length;
  $('#ag-nav').hidden = true;
  agChips();
  uiHtml($('#ag-lijst'), agRegels(r.items || []));
  if (!n) uiHtml($('#ag-lijst'), uiEmpty('calendar', t('Geen afspraken gevonden.')));
}
function agChips(){
  document.querySelectorAll('#ag-views button').forEach(b => {
    const aan = b.dataset.v === agView && !(agData && agData.view === 'zoek');
    b.classList.toggle('on', aan); b.setAttribute('aria-checked', aan ? 'true' : 'false');
  });
}
function agTeken(){
  const r = agData || {}, items = r.items || [];
  $('#ag-nav').hidden = false;
  agChips();
  const v = r.view || agView;
  let kop = '';
  if (v === 'dag') kop = fmtDate(r.anker, 'lang');
  else if (v === 'week') kop = t('Week {n}', { n: r.week || 0 }) + ' · ' + fmtDate(r.van, 'kort') + ' – ' + fmtDate(r.tot, 'kort');
  else kop = fmtDate(r.anker || agAnker, 'maand');
  $('#ag-periode').textContent = kop;
  if (r.onleesbaar) { uiHtml($('#ag-lijst'), '<p class="note">' + esc(t('De agenda is niet leesbaar. Er is niets overschreven.')) + '</p>'); return; }
  if (v === 'maand') agMaand(r, items);
  else agDagen(r, items, v === 'lijst');
}
function agOpDag(items, dag){ return items.filter(it => it.dag && it.dag <= dag && (it.eind || it.dag) >= dag); }
function agDagStijl(dag){
  return String(dag || '').slice(0, 4) === rgToday().slice(0, 4) ? 'wdm' : { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' };
}
function agRegels(items){
  if (!items.length) return uiEmpty('calendar', t('Geen afspraken in deze periode.'));
  let html = '', vorig = '';
  items.forEach(it => {
    if (it.dag !== vorig) { vorig = it.dag; html += '<p class="ag-dagkop">' + esc(fmtDate(it.dag, agDagStijl(it.dag))) + (it.dag !== it.eind && it.eind ? ' – ' + esc(fmtDate(it.eind, agDagStijl(it.eind))) : '') + '</p>'; }
    html += agItemHtml(it);
  });
  return html;
}
function agItemHtml(it){
  const kleur = agKleur(it.kleur), stijl = kleur ? ' style="--ag:' + kleur + '"' : '';
  const tijd = it.heleDag ? t('Hele dag') : (it.van ? fmtHm(it.van) + (it.tot ? '–' + fmtHm(it.tot) : '') : '');
  const extra = [it.plek, it.naam].filter(Boolean).join(' · ');
  return '<button type="button" class="ag-item" data-id="' + esc(it.id) + '"' + stijl + ' onclick="agOpen(this.dataset.id)"><b translate="no">' + esc(it.titel) + '</b><small>' +
    esc(tijd) + (extra ? ' <span translate="no">' + esc(extra) + '</span>' : '') + '</small></button>';
}
function agDagen(r, items, lijst){
  const dagen = r.dagen || [];
  if (lijst || !dagen.length) { uiHtml($('#ag-lijst'), agRegels(items)); return; }
  let html = '', leeg = [];
  // Lege dagen achter elkaar worden één regel ("di 10 nov t/m do 12 nov: niets"), anders is een rustige week zeven keer "Niets"
  const leegUit = () => {
    if (!leeg.length) return;
    const van = fmtDate(leeg[0].dag, 'wdm'), tot = fmtDate(leeg[leeg.length - 1].dag, 'wdm');
    html += '<p class="note ag-leeg">' + esc(leeg.length > 1 ? t('{van} t/m {tot}: niets', { van, tot }) : t('{dag}: niets', { dag: van })) + '</p>';
    leeg = [];
  };
  dagen.forEach(d => {
    const hier = agOpDag(items, d.dag);
    if (!hier.length && dagen.length > 1) { leeg.push(d); return; }
    leegUit();
    html += '<p class="ag-dagkop">' + esc(fmtDate(d.dag, 'wdm')) + (d.week ? ' · ' + esc(t('Week {n}', { n: d.week })) : '') + '</p>';
    html += hier.length ? hier.map(agItemHtml).join('') : '<p class="note">' + esc(t('Niets')) + '</p>';
  });
  leegUit();
  uiHtml($('#ag-lijst'), html);
}
function agMaand(r, items){
  const dagen = r.dagen || [], ws = weekStart();
  const basis = ws === 0 ? '2026-01-04' : ws === 6 ? '2026-01-03' : '2026-01-05';
  let html = '<div class="ag-kop"></div>';
  for (let i = 0; i < 7; i++) html += '<div class="ag-kop">' + esc(fmtDate(rgDayAdd(basis, i), 'wd')) + '</div>';
  for (let i = 0; i < dagen.length; i++) {
    if (i % 7 === 0) {
      const thu = dagen[i + ((4 - ws + 7) % 7)] || dagen[i];
      html += '<div class="ag-wk">' + (thu && thu.week ? thu.week : '') + '</div>';
    }
    const d = dagen[i], hier = agOpDag(items, d.dag).slice(0, 3);
    html += '<button type="button" class="ag-dag' + (d.vandaag ? ' is-today' : '') + (d.buiten ? ' other' : '') + '" data-dag="' + esc(d.dag) + '" onclick="agNaarDag(this.dataset.dag)"><span class="n">' +
      esc(String(+d.dag.slice(8))) + '</span>' + hier.map(it => '<span class="ag-stip"' + (agKleur(it.kleur) ? ' style="--ag:' + agKleur(it.kleur) + '"' : '') + '></span>').join('') + '</button>';
  }
  uiHtml($('#ag-lijst'), '<div class="ag-maand">' + html + '</div>');
}
function agNaarDag(dag){ agAnker = dag; agZicht('dag'); }

function agOpen(id){
  let it = (agData && agData.items || []).find(x => x.id === id);
  if (typeof Android.agendaHaal === 'function') {
    const r = rdJson(Android.agendaHaal(id), null);
    if (r && r.ok && r.item) it = r.item;
  }
  if (!it) { toast(t('Die afspraak staat er niet.')); return; }
  agEdit = Object.assign({ heleDag: false, herhaal: '', totDag: '', van: '', tot: '', plek: '', notitie: '', kalender: '', ext: 0, naam: '' }, it);
  if (it.reeks && !agEdit.herhaal) agEdit.herhaal = it.reeks;
  show('agafspraak');
}
function agNieuw(){ agNieuwKlaar(); show('agafspraak'); }
function agNieuwKlaar(){
  const dag = agAnker && agAnker.length === 10 ? agAnker : rgToday();
  agEdit = { id: '', titel: '', dag, eind: dag, van: '09:00', tot: '10:00', heleDag: false, herhaal: '', totDag: '', plek: '', notitie: '', kalender: (agStaat && agStaat.standaard) || '', ext: 0 };
}
function agFormRender(){
  const it = agEdit || {};
  $('#agf-kop').textContent = it.id ? t('Afspraak wijzigen') : t('Afspraak');
  $('#agf-titel').value = it.titel || '';
  $('#agf-dag').value = it.dag || rgToday();
  $('#agf-eind').value = it.eind || it.dag || rgToday();
  $('#agf-van').value = it.van || '09:00';
  $('#agf-tot').value = it.tot || '10:00';
  $('#agf-herhaal').value = it.herhaal || '';
  $('#agf-totdag').value = it.totDag || '';
  $('#agf-plek').value = it.plek || '';
  $('#agf-notitie').value = it.notitie || '';
  const heel = $('#agf-heel'); heel.classList.toggle('on', !!it.heleDag); heel.setAttribute('aria-checked', it.heleDag ? 'true' : 'false');
  $('#agf-tijden').hidden = !!it.heleDag;
  $('#agf-wis').hidden = !it.id || String(it.id).charAt(0) === 'x';
  agKalVul();
  agTotZien();
  agKalHint();
}
function agHeel(){
  const b = $('#agf-heel'), aan = b.getAttribute('aria-checked') !== 'true';
  b.classList.toggle('on', aan); b.setAttribute('aria-checked', aan ? 'true' : 'false');
  $('#agf-tijden').hidden = aan;
}
function agTotZien(){
  const aan = !!$('#agf-herhaal').value;
  $('#agf-totdag').hidden = !aan; $('#agf-tot-l').hidden = !aan;
}
function agKals(){ return (agStaat && agStaat.kalenders) || []; }
function agKalVul(){
  const sel = $('#agf-kal'), cur = agEdit && agEdit.kalender || '';
  const opts = ['<option value="">' + esc(t('Alleen in Tools')) + '</option>'].concat(agKals().map(k =>
    '<option value="' + esc(k.id) + '">' + esc(k.naam || k.id) + (k.schrijf ? '' : ' (' + t('Alleen lezen') + ')') + '</option>'));
  sel.innerHTML = opts.join('');
  sel.value = [...sel.options].some(o => o.value === cur) ? cur : '';
}
function agKalHint(){
  const id = $('#agf-kal').value, k = agKals().find(x => x.id === id);
  $('#agf-google').hidden = !(k && k.google);
  const had = agEdit && agEdit.ext > 0 && agEdit.kalender;
  $('#agf-los').hidden = !(had && !id);
  const her = $('#agf-herhaal').value;
  $('#agf-reeks').hidden = !(her && (agEdit && agEdit.ext > 0 || (agEdit && agEdit.reeks)));
}
function agBewaar(){
  const it = {
    id: agEdit && agEdit.id && String(agEdit.id).charAt(0) !== 'x' ? agEdit.id : (agEdit && agEdit.id) || '',
    titel: $('#agf-titel').value, plek: $('#agf-plek').value, notitie: $('#agf-notitie').value,
    dag: $('#agf-dag').value, eind: $('#agf-eind').value,
    van: $('#agf-van').value, tot: $('#agf-tot').value,
    heleDag: $('#agf-heel').getAttribute('aria-checked') === 'true',
    herhaal: $('#agf-herhaal').value, totDag: $('#agf-herhaal').value ? $('#agf-totdag').value : '',
    kalender: $('#agf-kal').value, ext: agEdit && agEdit.ext || 0
  };
  const r = rdJson(Android.agendaBewaar(JSON.stringify(it)), {});
  if (!r || !r.ok) { toast((r && r.fout) || t('Opslaan lukte niet.')); return; }
  if (r.los) toast(t('De kopie in de agenda van je telefoon blijft staan.'));
  agEdit = null; goBack();
}
function agWis(){
  const id = agEdit && agEdit.id; if (!id || String(id).charAt(0) === 'x') return;
  askConfirm(t('Afspraak verwijderen?'), t('Dit haalt de afspraak ook uit de agenda van je telefoon als hij daar stond.'), t('Verwijderen'), () => {
    const r = rdJson(Android.agendaWis(id), {});
    if (!r || !r.ok) { toast((r && r.fout) || t('Opslaan lukte niet.')); return; }
    agEdit = null; goBack();
  });
}

function agSync(){
  if (typeof Android.agendaSync !== 'function') return;
  const r = rdJson(Android.agendaSync(), {});
  if (!r || r.ok === false) { toast((r && r.fout) || t('Opslaan lukte niet.')); return; }
  const n = (+r.bijgewerkt || 0) + (+r.los || 0);
  toast(n ? t('Agenda bijgewerkt') : t('Niets nieuws in de agenda'));
  if (current === 'agenda') agLaad(); else if (current === 'agaset') agSetRender();
}
function agSetRender(){
  if (typeof Android.agendaStaat === 'function') agStaat = rdJson(Android.agendaStaat(), {}) || {};
  const mag = agStaat && agStaat.perm !== false, schrijf = agStaat && agStaat.schrijf !== false;
  $('#ag-toestem-t').textContent = mag ? (schrijf ? t('Je kunt agenda\'s op je telefoon zien en er afspraken in zetten.') : t('Je kunt agenda\'s op je telefoon zien. Zetten mag nog niet.')) : t('Zonder toestemming zie je alleen afspraken die je in Tools zelf zet.');
  $('#ag-toestem-b').hidden = !!(mag && schrijf);
  const box = $('#ag-kals');
  const kals = agKals();
  if (!kals.length) uiHtml(box, '<p class="note">' + esc(t('Geen agenda\'s op je telefoon, of nog geen toestemming.')) + '</p>');
  else uiHtml(box, kals.map(k => '<div class="sw"><span><b translate="no">' + esc(k.naam || k.id) + '</b><small translate="no">' + esc(k.account || '') + '</small>' +
    (k.google ? '<small>' + esc(t('Google')) + '</small>' : '') + (!k.schrijf ? '<small>' + esc(t('Alleen lezen')) + '</small>' : '') +
    '</span><button type="button" class="tg' + (k.aan !== false ? ' on' : '') + '" role="switch" aria-checked="' + (k.aan !== false) + '" data-id="' + esc(k.id) + '" aria-label="' + esc(t('Tonen')) + '" onclick="agKalAan(this)"></button></div>').join(''));
  const sel = $('#ag-standaard');
  sel.innerHTML = ['<option value="">' + esc(t('Alleen in Tools')) + '</option>'].concat(kals.filter(k => k.schrijf).map(k => '<option value="' + esc(k.id) + '">' + esc(k.naam || k.id) + '</option>')).join('');
  const st = agStaat && agStaat.standaard || '';
  sel.value = [...sel.options].some(o => o.value === st) ? st : '';
  const ai = $('#ag-ai'), aan = !!(agStaat && agStaat.ai);
  ai.classList.toggle('on', aan); ai.setAttribute('aria-checked', aan ? 'true' : 'false');
}
function agKalAan(b){
  const id = b.dataset.id;
  const nuAan = b.getAttribute('aria-checked') === 'true';
  const set = new Set(agKals().filter(k => k.aan === false).map(k => k.id));
  if (nuAan) set.add(id); else set.delete(id);
  agStaat = rdJson(Android.agendaZet(JSON.stringify({ uit: [...set] })), agStaat);
  agSetRender();
}
function agStandaard(){
  agStaat = rdJson(Android.agendaZet(JSON.stringify({ standaard: $('#ag-standaard').value })), agStaat);
}
function agAi(){
  const aan = $('#ag-ai').getAttribute('aria-checked') !== 'true';
  if (typeof Android.aiPref === 'function') Android.aiPref('src_agenda', aan);
  if (agStaat) agStaat.ai = aan;
  $('#ag-ai').classList.toggle('on', aan); $('#ag-ai').setAttribute('aria-checked', aan ? 'true' : 'false');
}
(function(){ const prev = window.onSettingsChanged; window.onSettingsChanged = function(msg){ if (typeof prev === 'function') prev(msg);
  if (current === 'agenda' || current === 'agaset' || current === 'agafspraak') agEnter(current); }; })();

/* ---------- spiegel voor de tests (op de telefoon doet Java dit) ---------- */
function agDagOk(key){
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(key || '')); if (!m) return false;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  return d.getUTCFullYear() === +m[1] && d.getUTCMonth() + 1 === +m[2] && d.getUTCDate() === +m[3];
}
function agIsoDow(dag){ const w = rgWeekday(dag); return w === 0 ? 7 : w; }
function agIsoWeek(dag){
  if (!agDagOk(dag)) return 0;
  const thu = rgDayAdd(dag, 4 - agIsoDow(dag)), y = +String(thu).slice(0, 4), jan4 = y + '-01-04';
  const mon = rgDayAdd(jan4, 1 - agIsoDow(jan4));
  return Math.floor(rgDayDiff(mon, thu) / 7) + 1;
}
function agBereik(view, anker, weekStart){
  if (!agDagOk(anker)) return null;
  const ws = weekStart === 0 || weekStart === 6 ? weekStart : 1;
  if (view === 'dag') return [anker, anker];
  if (view === 'week') { const van = rgDayAdd(anker, -((rgWeekday(anker) - ws + 7) % 7)); return [van, rgDayAdd(van, 6)]; }
  const eerste = anker.slice(0, 8) + '01';
  if (view === 'lijst') return [eerste, rgDayAdd(agPlusMaand(eerste, 1), -1)];
  const van = rgDayAdd(eerste, -((rgWeekday(eerste) - ws + 7) % 7));
  return [van, rgDayAdd(van, 41)];
}
function agVouw(s){ return String(s || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase(); }
function agWoorden(q){
  const stop = new Set(('wat wanneer welke zoek toon laat staat heb had ik je mijn de het een in op voor met van naar bij uit aan als ook nog al er is was niet en te tot over door dit dat deze die agenda kalender afspraak afspraken').split(' '));
  return agVouw(q).split(/[^\p{L}\p{N}]+/u).filter(w => w.length >= 2 && !stop.has(w));
}
function agPast(it, woorden){
  if (!woorden.length) return true;
  const hay = agVouw([it.titel, it.plek, it.notitie].join('\n'));
  return woorden.every(w => hay.includes(w));
}
function agStapDatum(start, her, idx){
  if (idx < 0) return '';
  if (her === 'd') return rgDayAdd(start, idx);
  if (her === 'w') return rgDayAdd(start, idx * 7);
  if (her === 'm' || her === 'y') return agPlusMaand(start, her === 'y' ? idx * 12 : idx);
  return idx === 0 ? start : '';
}
function agIndex(start, her, doel){
  if (her === 'd') return Math.max(0, rgDayDiff(start, doel));
  if (her === 'w') return Math.max(0, Math.floor(rgDayDiff(start, doel) / 7));
  const a = String(start).split('-').map(Number), b = String(doel).split('-').map(Number);
  const maanden = (b[0] - a[0]) * 12 + (b[1] - a[1]);
  return Math.max(0, her === 'y' ? Math.floor(maanden / 12) : maanden);
}
function agMomenten(it, van, tot){
  const her = ['d', 'w', 'm', 'y'].includes(it.herhaal) ? it.herhaal : '';
  const sp = it.eind && it.eind > it.dag ? rgDayDiff(it.dag, it.eind) : 0;
  const lim = it.totDag || '9999-12-31';
  const uit = [];
  const push = dag => { if (!dag || dag > lim || dag > tot) return; const eind = rgDayAdd(dag, sp); if (eind >= van) uit.push(Object.assign({}, it, { dag, eind })); };
  if (!her) { if (it.dag <= lim && rgDayAdd(it.dag, sp) >= van && it.dag <= tot) uit.push(Object.assign({}, it, { eind: rgDayAdd(it.dag, sp) })); return uit; }
  if (rgDayDiff(van, tot) <= 45) {
    let idx = Math.max(0, agIndex(it.dag, her, van) - (sp > 0 ? 1 : 0));
    for (let n = 0; n < 80 && uit.length < 80; n++, idx++) {
      const dag = agStapDatum(it.dag, her, idx);
      if (!dag || dag > lim || dag > tot) break;
      push(dag);
    }
    return uit;
  }
  const center = agIndex(it.dag, her, rgToday());
  for (let dist = 0; dist <= 800 && uit.length < 8; dist++) {
    for (const kant of (dist === 0 ? [0] : [-dist, dist])) {
      if (uit.length >= 8) break;
      const idx = center + kant; if (idx < 0) continue;
      const dag = agStapDatum(it.dag, her, idx);
      if (!dag || dag > lim || dag > tot) continue;
      push(dag);
    }
  }
  return uit;
}
function agZichtbaar(st){
  const uit = new Set(st.uit || []), lokaal = [], extern = [];
  (st.items || []).forEach(it => { if (it.kalender && uit.has(String(it.kalender))) return; lokaal.push(it); });
  (st.extern || []).forEach(it => { if (it.kalender && uit.has(String(it.kalender))) return; extern.push(it); });
  return { lokaal, extern };
}
function agSpiegelLijst(st, van, tot, woorden){
  const z = agZichtbaar(st), uit = [];
  z.lokaal.forEach(it => { if (!it.titel || !agPast(it, woorden)) return; uit.push(...agMomenten(it, van, tot)); });
  const heeft = new Set(uit.filter(it => it.ext > 0).map(it => it.ext + '|' + it.dag));
  z.extern.forEach(it => {
    if (!it.titel || !agPast(it, woorden)) return;
    agMomenten(it, van, tot).forEach(m => {
      if (m.ext > 0 && heeft.has(m.ext + '|' + m.dag)) return;
      if (m.ext > 0) heeft.add(m.ext + '|' + m.dag);
      uit.push(m);
    });
  });
  const volgorde = (a, b) => a.dag < b.dag ? -1 : a.dag > b.dag ? 1 : (a.heleDag === b.heleDag ? String(a.van || '').localeCompare(String(b.van || '')) : a.heleDag ? -1 : 1);
  uit.sort(volgorde);
  if (uit.length > 200 && rgDayDiff(van, tot) > 45) {
    const nu = rgToday();
    uit.sort((a, b) => Math.abs(rgDayDiff(nu, a.dag)) - Math.abs(rgDayDiff(nu, b.dag)) || volgorde(a, b));
    uit.length = 200;
    uit.sort(volgorde);
  } else if (uit.length > 200) uit.length = 200;
  return uit;
}
function agSpiegelToon(st, view, anker, weekStart){
  if (!['dag', 'week', 'maand', 'lijst'].includes(view)) view = 'week';
  if (!agDagOk(anker)) anker = rgToday();
  const r = agBereik(view, anker, weekStart);
  const dagen = [];
  if (view !== 'lijst') for (let d = r[0]; d && d <= r[1]; d = rgDayAdd(d, 1)) dagen.push({ dag: d, week: agIsoWeek(d), buiten: view === 'maand' && !d.startsWith(anker.slice(0, 7)), vandaag: d === rgToday() });
  return { view, anker, van: r[0], tot: r[1], week: agIsoWeek(anker), dagen, items: agSpiegelLijst(st, r[0], r[1], []) };
}
function agSpiegelZoek(st, q, van, tot){
  const nu = rgToday();
  const vanD = agDagOk(van) ? van : rgDayAdd(nu, -365 * 15), totD = agDagOk(tot) ? tot : rgDayAdd(nu, 365 * 3);
  return { items: agSpiegelLijst(st, vanD, totD, agWoorden(q)), van: vanD, tot: totD };
}
function agSpiegelBewaar(st, inn){
  const titel = String(inn.titel || '').trim();
  if (!titel) return { ok: false, fout: 'Vul een titel in.' };
  if (!agDagOk(inn.dag)) return { ok: false, fout: 'Die datum klopt niet.' };
  let id = String(inn.id || '');
  const bestaand = (st.items || []).find(x => x.id === id);
  if (!bestaand || id.charAt(0) === 'x') id = 'a' + Date.now().toString(16) + (st._n = (st._n || 0) + 1).toString(16);
  const kal = String(inn.kalender || '');
  if (kal) {
    const k = (st.kalenders || []).find(x => String(x.id) === kal);
    if (!k || !k.schrijf) return { ok: false, fout: 'Die agenda is alleen-lezen.' };
    if (!st.schrijf) return { ok: false, fout: 'Geen toestemming om de agenda van je telefoon te wijzigen.' };
  }
  const it = { id, titel, plek: inn.plek || '', notitie: inn.notitie || '', dag: inn.dag, eind: agDagOk(inn.eind) && inn.eind >= inn.dag ? inn.eind : inn.dag,
    van: inn.heleDag ? '' : (inn.van || '09:00'), tot: inn.heleDag ? '' : (inn.tot || '10:00'), heleDag: !!inn.heleDag,
    herhaal: ['d', 'w', 'm', 'y'].includes(inn.herhaal) ? inn.herhaal : '', totDag: inn.totDag || '', kalender: kal,
    ext: kal ? (inn.ext > 0 ? inn.ext : 9000 + (st.items || []).length) : 0, naam: '', kleur: '' };
  if (kal) { const k = st.kalenders.find(x => String(x.id) === kal); it.naam = k.naam || ''; it.kleur = k.kleur || ''; }
  st.items = (st.items || []).filter(x => x.id !== it.id).concat([it]);
  if (kal && it.ext > 0) {
    st.extern = (st.extern || []).filter(x => x.ext !== it.ext);
    st.extern.push(Object.assign({}, it, { id: 'x' + it.ext + '_' + it.dag }));
  }
  st.laatste = it;
  return { ok: true, id: it.id, los: !kal && inn.ext > 0 };
}
function agWit(c){ return c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\v'; }
function agNormVraag(q){
  const s = String(q || '').toLowerCase().replace(/[’']/g, "'");
  let end = s.length;
  while (end > 0 && agWit(s.charAt(end - 1))) end--;
  let punct = end;
  while (punct > 0 && (s.charAt(punct - 1) === '?' || s.charAt(punct - 1) === '!' || s.charAt(punct - 1) === '.')) punct--;
  if (punct < end) end = punct;
  let uit = '', spatie = false;
  for (let i = 0; i < end; i++) {
    const c = s.charAt(i);
    if (agWit(c)) { if (!spatie && uit) { uit += ' '; spatie = true; } }
    else { uit += c; spatie = false; }
  }
  if (uit.charAt(uit.length - 1) === ' ') uit = uit.slice(0, -1);
  return uit;
}
function agGaatOver(q){
  const s = agNormVraag(q);
  if (!s || s.length > 300) return false;
  if (/\b(zet|plan|maak|voeg|herinner|noteer|schrijf)\b.{0,80}\b(agenda|kalender)\b/.test(s)) return false;
  if (!/\b(agenda|kalender|afspraken|afspraak)\b/.test(s)) return false;
  return /\b(wat|wanneer|welke|hoeveel|zoek|toon|laat|staat|stond|heb|had|hebben|planning)\b/.test(s) || s.split(' ').filter(Boolean).length >= 3;
}
function agWeekdag(s, nu){
  const namen = ['zondag', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag'];
  const pak = (m, dag) => ({ van: dag, tot: dag, a: m.index, b: m.index + m[0].length });
  const op = (doel, plus) => { const r = agBereik('week', nu, 1); return r ? rgDayAdd(r[0], (doel + 6) % 7 + plus) : nu; };
  let m = new RegExp('\\b(volgende|vorige|deze|komende)\\s+week\\s+(' + namen.join('|') + ')\\b').exec(s);
  if (m) {
    const plus = m[1] === 'vorige' ? -7 : m[1] === 'deze' ? 0 : 7;
    return pak(m, op(namen.indexOf(m[2]), plus));
  }
  m = new RegExp('\\b(vorige|volgende|deze|komende|aanstaande)\\s+(' + namen.join('|') + ')\\b').exec(s);
  if (!m) return null;
  let dag = op(namen.indexOf(m[2]), 0);
  if (m[1] === 'volgende') dag = rgDayAdd(dag, 7);
  else if (m[1] === 'vorige') dag = rgDayAdd(dag, -7);
  else if (m[1] !== 'deze' && dag < nu) dag = rgDayAdd(dag, 7);
  return pak(m, dag);
}
function agLosseWeekdag(s, nu){
  const namen = ['zondag', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag'];
  const m = new RegExp('\\b(' + namen.join('|') + ')\\b').exec(s);
  if (!m) return null;
  const r = agBereik('week', nu, 1);
  let dag = r ? rgDayAdd(r[0], (namen.indexOf(m[1]) + 6) % 7) : nu;
  if (dag < nu) dag = rgDayAdd(dag, 7);
  return { van: dag, tot: dag, a: m.index, b: m.index + m[0].length };
}
function agPeriode(s, nu){
  let m;
  const pak = (re, van, tot) => { m = re.exec(s); return m ? { van, tot, a: m.index, b: m.index + m[0].length } : null; };
  return pak(/\bovermorgen\b/, rgDayAdd(nu, 2), rgDayAdd(nu, 2))
    || pak(/\bgisteren\b/, rgDayAdd(nu, -1), rgDayAdd(nu, -1))
    || pak(/\bvandaag\b/, nu, nu)
    || pak(/\bmorgen\b/, rgDayAdd(nu, 1), rgDayAdd(nu, 1))
    || agWeekdag(s, nu)
    || (() => { m = /\b(volgende|vorige|deze|komende)\s+week\b/.exec(s); if (!m) return null; const r = agBereik('week', nu, 1), plus = m[1] === 'vorige' ? -7 : m[1] === 'deze' ? 0 : 7; return { van: rgDayAdd(r[0], plus), tot: rgDayAdd(r[1], plus), a: m.index, b: m.index + m[0].length }; })()
    || agLosseWeekdag(s, nu);
}
function agVenster(q){
  const s = agNormVraag(q), nu = rgToday(), wijdVan = rgDayAdd(nu, -365 * 15), wijdTot = rgDayAdd(nu, 365 * 3);
  const v = { van: wijdVan, tot: wijdTot };
  const per = agPeriode(s, nu);
  let rest = s;
  if (per) { v.van = per.van; v.tot = per.tot; rest = (s.slice(0, per.a) + ' ' + s.slice(per.b)).replace(/\s+/g, ' ').trim(); }
  else if (/\b(wat staat|wat heb|wat had|laat zien|toon)\b/.test(s) && !s.includes('zoek')) { v.van = nu; v.tot = rgDayAdd(nu, 14); }
  v.woorden = agWoorden(rest);
  if (v.woorden.length && !per) { v.van = wijdVan; v.tot = wijdTot; }
  return v;
}
function agSpiegelAntwoord(st, q){
  if (!agGaatOver(q)) return '';
  const v = agVenster(q), items = agSpiegelLijst(st, v.van, v.tot, v.woorden);
  if (!items.length) return 'Niets gevonden.';
  return items.slice(0, 12).map(it => it.dag + (it.heleDag || !it.van ? '' : ' ' + it.van) + ' ' + it.titel).join('\n');
}
