/* ---------- Automatiseringen (als dit gebeurt, doe dan dat) ---------- */
let aeSnap = null; // de regel zoals hij was bij openen (voor 'Wijzigingen opslaan?')
function aeDirty(){ return !!aeRule && (aeSnap === null || JSON.stringify(aeRule) !== aeSnap); }
let auState = { rules: [], log: [], last: {}, perms: {} }, aeRule = null, aeIsNew = false, aeApps = null, aeBt = null;
let aeMap = null, aeMarker = null, aeCircle = null;
const AU_TRIG = { bt_off: 'Auto uitgezet', bt_on: 'Auto aangezet', arrive: 'Aankomen bij', leave: 'Weggaan van', manual: 'Met de hand', time: 'Om', charge_on: 'Aan de lader' };
const AU_ACT = { note: 'notitie tonen', radio: 'radio', track_start: 'route opnemen', track_stop: 'route stoppen', backup: 'alles back-uppen', redial: 'Auto redial' };
const AU_MODE = { ask: 'vraagt eerst', auto: 'volledig automatisch', notify: 'alleen melding' };
const AU_DAYS = ['ma', 'di', 'wo', 'do', 'vr', 'za', 'zo'];

function auLoad(){ auState = rdJson(Android.autoState(), { rules: [], log: [], last: {}, perms: {} }); auState.rules = auState.rules || []; auState.perms = auState.perms || {}; }
function enterAuto(){ auLoad(); auRender(); }

function auDaysLabel(m){
  if (!m || m === 127) return ''; if (m === 31) return 'werkdagen'; if (m === 96) return 'weekend';
  return AU_DAYS.filter((d, i) => m & (1 << i)).join(', ');
}
function auSummary(r){
  const p = r.place || {}, parts = [];
  const where = p.name || 'de plek';
  if (r.trig === 'arrive' || r.trig === 'leave') parts.push(AU_TRIG[r.trig] + ' ' + where);
  else if (r.trig === 'time') parts.push('Om ' + (r.at || '?'));
  else parts.push((AU_TRIG[r.trig] || '?') + (r.usePlace && r.place ? ' bij ' + where : ''));
  const d = auDaysLabel(r.days); if (d) parts.push(d);
  if (r.from && r.to && r.from !== r.to) parts.push(r.from + '–' + r.to);
  let act = AU_MODE[r.mode] || '';
  if (r.act && r.act !== 'app') return parts.join(' · ') + ' → ' + AU_ACT[r.act] + (r.act === 'note' && r.note ? ': ' + r.note.title : r.act === 'radio' && r.radio ? ': ' + r.radio.name : '') + (r.act === 'note' ? '' : ', ' + (r.mode === 'auto' ? 'meteen' : act));
  if (r.app && r.app.n && r.mode !== 'notify') act = r.app.n + ', ' + act;
  if (r.mode !== 'notify' && r.steps && r.steps.length) act += ' (' + r.steps.length + (r.steps.length === 1 ? ' knop' : ' knoppen') + ')';
  return parts.join(' · ') + ' → ' + act;
}

/** Wat moet er aan staan voor de automatiseringen die er zijn? */
function auNeeds(){
  const rs = auState.rules.filter(r => r.on !== false), P = auState.perms;
  const bt = !rs.length || rs.some(r => (r.trig || '').startsWith('bt_'));
  const place = rs.some(r => r.trig === 'arrive' || r.trig === 'leave' || (r.usePlace && r.place));
  const geo = rs.some(r => r.trig === 'arrive' || r.trig === 'leave');
  const taps = rs.some(r => r.mode === 'auto' || (r.mode !== 'notify' && r.steps && r.steps.length));
  const rows = [['notif', 'Meldingen', 'Voor de vraag "Parkeren starten?" en de uitkomst', P.notif]];
  if (bt) rows.push(['bt', 'Bluetooth', 'Om te zien dat je auto aan- of uitgaat', P.bt]);
  if (place) rows.push(['bgloc', 'Locatie, ook op de achtergrond', geo ? 'Kies "Altijd toestaan" en zet "Precieze locatie" aan, anders merkt de telefoon aankomen en weggaan niet op' : 'Kies "Altijd toestaan" om te weten waar je bent als het gebeurt', P.loc && P.bgloc && (!geo || P.fine !== false)]);
  if (taps) rows.push(['a11y', 'Toegankelijkheid', 'Om knoppen in de app voor je in te drukken', P.a11y]);
  rows.push(['battery', 'Niet beperken op de achtergrond', 'Anders kan Android (vooral op Oppo) de Bluetooth-melding missen', P.battery]);
  return rows;
}

function auRender(){
  const rows = auNeeds(), bad = rows.filter(r => !r[3]);
  const P = auState.perms;
  let h = '';
  if (!bad.length && auState.rules.length) h = '<div class="auok">✓ Alles staat klaar</div>';
  else {
    h = '<h2>Klaarzetten</h2>' + rows.map(r => '<div class="auperm"><span><b>' + esc(r[1]) + '</b><small>' + esc(r[2]) + '</small></span>' +
      (r[3] ? '<span class="aucheck" aria-label="In orde">✓</span>' : '<button class="aubtn" onclick="auFix(' + jsq(r[0]) + ')">Toestaan</button>') + '</div>').join('');
    if (rows.some(r => r[0] === 'a11y' && !r[3]))
      h += '<p class="note" style="margin-top:8px">Bij Toegankelijkheid: kies <i>Rene\'s Tools: knoppen indrukken</i> en zet hem aan. Is hij grijs of staat er <i>Beperkte instelling</i>? Open dan <a href="#" onclick="Android.openAppSettings();return false">App-info</a> → ⋮ (rechtsboven) → <i>Beperkte instellingen toestaan</i>, en probeer opnieuw.</p>';
    if (P.a11y && !P.a11yRunning && rows.some(r => r[0] === 'a11y'))
      h += '<p class="note bad">Toegankelijkheid staat aan, maar Android heeft de dienst gestopt. Zet hem uit en weer aan.</p>';
  }
  $('#au-perms').innerHTML = h;

  const list = $('#au-list');
  list.innerHTML = auState.rules.map(r => {
    const last = auState.last && auState.last[r.id];
    return '<div class="card aurule' + (r.on === false ? ' off' : '') + '"><button class="aumain" onclick="autoEdit(' + jsq(r.id) + ')"><b>' + esc(r.name || 'Zonder naam') + '</b>' +
      '<small>' + esc(auSummary(r)) + '</small>' + (last ? '<small class="aulast">Laatst: ' + esc(auWhen(last)) + '</small>' : '') + '</button>' +
      '<button class="tg' + (r.on === false ? '' : ' on') + '" aria-label="' + esc(r.name) + ' aan" onclick="auToggle(' + jsq(r.id) + ', this)"></button></div>';
  }).join('');
  $('#au-empty').style.display = auState.rules.length ? 'none' : 'block';

  const log = (auState.log || []).slice(0, 10);
  $('#au-logcard').style.display = log.length ? 'block' : 'none';
  $('#au-log').innerHTML = log.map(l => '<li><span class="t">' + esc(auWhen(l.t)) + '</span><span><b>' + esc(l.name || '') + '</b> ' + esc(l.what || '') + '</span></li>').join('');
}

function auWhen(t){
  const d = new Date(t), now = new Date();
  const time = d.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return 'vandaag ' + time;
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return 'gisteren ' + time;
  return d.toLocaleDateString('nl-NL', { day: 'numeric', month: 'short' }) + ' ' + time;
}

function auFix(k){
  if (k === 'a11y') { askConfirm('Toegankelijkheid aanzetten', 'In het volgende scherm: kies "Rene\'s Tools: knoppen indrukken" en zet hem aan. Rene\'s Tools drukt dan alleen knoppen in in de app die jij kiest, en alleen als een automatisering loopt of als je opneemt.', 'Naar instellingen', () => Android.autoA11ySettings()); return; }
  if (k === 'battery') { Android.batterySettings(); return; }
  if (k === 'bgloc' && (!auState.perms.loc || auState.perms.fine === false)) { Android.autoPerm('loc'); return; }
  if (k === 'bgloc') { askConfirm('Locatie altijd toestaan', 'Android vraagt nu of Rene\'s Tools je locatie ook mag gebruiken als de app dicht is. Kies "Altijd toestaan"; de locatie wordt alleen bepaald op het moment dat je auto uit- of aangaat, of bij aankomen/weggaan.', 'Verder', () => Android.autoPerm('bgloc')); return; }
  Android.autoPerm(k);
}

function auToggle(id, el){
  el.classList.toggle('on');
  Android.autoSetOn(id, el.classList.contains('on'));
  auLoad(); auRender();
}

/* ---------- bewerken ---------- */
function aeLoadApps(){ if (!aeApps) aeApps = rdJson(Android.autoApps(), []); return aeApps; }
function aeLoadBt(){ if (!aeBt) aeBt = rdJson(Android.autoBt(), { error: 'Bluetooth lezen lukt niet' }); return aeBt; }

function autoNew(tpl){
  auLoad();
  const park = aeLoadApps().find(a => a.park);
  const bt = aeLoadBt(), cars = (bt.devices || []).filter(d => d.car);
  const r = { id: Date.now().toString(36), name: '', on: true, trig: 'bt_off', bt: [], btNames: [], usePlace: false, place: null,
    days: 0, from: '', to: '', mode: 'ask', app: null, steps: [], msg: '', cool: 15 };
  if (cars.length === 1) { r.bt = [cars[0].a]; r.btNames = [cars[0].n]; }
  if (tpl === 'shop') Object.assign(r, { name: 'Boodschappen bij de winkel', trig: 'arrive', usePlace: true, act: 'note', mode: 'notify', bt: [], btNames: [], cool: 240 });
  if (tpl === 'park-start') Object.assign(r, { name: 'Parkeren starten', trig: 'bt_off', usePlace: true, mode: 'ask',
    app: park ? { p: park.p, n: park.n } : null, msg: 'Je auto staat geparkeerd. Parkeeractie starten?' });
  if (tpl === 'park-stop') Object.assign(r, { name: 'Parkeren stoppen', trig: 'bt_on', mode: 'notify',
    app: park ? { p: park.p, n: park.n } : null, msg: 'Je rijdt weer. Vergeet niet je parkeeractie te stoppen.' });
  aeRule = r; aeIsNew = true; aeSnap = JSON.stringify(r);
  show('autoed');
}

function autoEdit(id){
  auLoad();
  const r = auState.rules.find(x => x.id === id); if (!r) return;
  aeRule = JSON.parse(JSON.stringify(r)); aeIsNew = false; aeSnap = JSON.stringify(aeRule);
  show('autoed');
}

function enterAutoEd(){
  if (!aeRule) { show('auto'); return; }
  aeBt = null; auLoad();
  $('#ae-warn').classList.remove('on');
  aeRender();
}

function aePlace(){ if (!aeRule.place) aeRule.place = { name: '', r: 200 }; return aeRule.place; }
function aeNeedsPlace(){ return aeRule.trig === 'arrive' || aeRule.trig === 'leave' || ((aeRule.trig || '').startsWith('bt_') && aeRule.usePlace); }

function aeRadio(box, v){ document.querySelectorAll(box + ' button').forEach(b => { const on = b.dataset.v === v; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }); }

function aeRender(){
  const r = aeRule, isBt = (r.trig || '').startsWith('bt_'), act = r.act || 'app', internal = act !== 'app';
  $('#ae-timebox').style.display = r.trig === 'time' ? 'block' : 'none';
  $('#ae-at').value = r.at || '';
  $('#ae-act').value = act;
  $('#ae-notebox').style.display = act === 'note' ? 'block' : 'none';
  $('#ae-radiobox').style.display = act === 'radio' ? 'block' : 'none';
  $('#ae-redialbox').style.display = act === 'redial' ? 'block' : 'none';
  if (act === 'note') { const ns = (notesGet() || []).filter(n => !n.deleted); $('#ae-note').innerHTML = '<option value="">Kies een notitie…</option>' + ns.map(n => '<option value="' + esc(n.id) + '"' + (r.note && r.note.id === n.id ? ' selected' : '') + '>' + esc(n.title || 'Zonder titel') + '</option>').join(''); }
  if (act === 'radio') { const fs = rdJson(Android.radioFavorites(), []); $('#ae-radio').innerHTML = '<option value="">Kies een zender (uit je favorieten)…</option>' + fs.map((f, i) => '<option value="' + i + '"' + (r.radio && r.radio.url === f.url ? ' selected' : '') + '>' + esc(f.name) + '</option>').join(''); }
  if (act === 'redial') $('#ae-number').value = r.number || '';
  document.querySelector('#ae-mode [data-v="auto"] small').textContent = internal ? 'Meteen doen, zonder te vragen' : 'App openen en de knoppen indrukken, zonder te vragen';
  document.querySelector('#ae-mode [data-v="auto"] b').textContent = internal ? 'Meteen doen' : 'Volledig automatisch';
  $('#ae-mode').style.display = act === 'note' ? 'none' : '';
  $('#ae-h').textContent = aeIsNew ? 'Nieuwe automatisering' : (r.name || 'Automatisering');
  $('#ae-name').value = r.name || '';
  aeRadio('#ae-trig', r.trig);

  // Bluetooth-apparaten
  $('#ae-btbox').style.display = isBt ? 'block' : 'none';
  if (isBt) {
    const bt = aeLoadBt();
    if (bt.error === 'perm') {
      $('#ae-bt').innerHTML = '';
      $('#ae-btnote').innerHTML = 'Rene\'s Tools mag je Bluetooth-apparaten nog niet zien. <a href="#" onclick="Android.autoPerm(\'bt\');return false">Toestaan</a>';
    } else if (bt.error) {
      $('#ae-bt').innerHTML = ''; $('#ae-btnote').textContent = bt.error;
    } else {
      const devs = (bt.devices || []).slice().sort((a, b) => (b.car - a.car) || a.n.localeCompare(b.n));
      // gekozen apparaten die niet (meer) gekoppeld zijn toch tonen
      (r.bt || []).forEach((a, i) => { if (!devs.some(d => d.a === a)) devs.unshift({ a, n: (r.btNames || [])[i] || a, car: true }); });
      $('#ae-bt').innerHTML = devs.map(d => '<button data-a="' + esc(d.a) + '" class="' + ((r.bt || []).includes(d.a) ? 'on' : '') + '">' + (d.car ? '🚗 ' : '') + esc(d.n) + '</button>').join('');
      $('#ae-bt').querySelectorAll('button').forEach(b => b.onclick = () => aeToggleBt(b.dataset.a, b.textContent.replace(/^🚗 /, '')));
      $('#ae-btnote').textContent = devs.length ? 'Kies de Bluetooth van je auto (koppel hem eerst in de Bluetooth-instellingen van je telefoon).' : 'Geen gekoppelde apparaten. Koppel eerst je telefoon met de auto.';
    }
  }

  // Plek
  $('#ae-placecard').style.display = isBt || r.trig === 'arrive' || r.trig === 'leave' ? 'block' : 'none';
  $('#ae-useplace-row').style.display = isBt ? 'flex' : 'none';
  $('#ae-useplace').classList.toggle('on', !!r.usePlace);
  const showPlace = aeNeedsPlace();
  $('#ae-placebox').style.display = showPlace ? 'block' : 'none';
  if (showPlace) {
    const p = aePlace();
    $('#ae-pname').value = p.name || '';
    document.querySelectorAll('#ae-radius button').forEach(b => b.classList.toggle('on', +b.dataset.r === (p.r || 200)));
    $('#ae-pinfo').textContent = p.lat != null ? 'Gekozen: ' + p.lat.toFixed(5) + ', ' + p.lng.toFixed(5) + ' (tik op de kaart om te verplaatsen)' : 'Tik op de kaart om de plek te kiezen, of gebruik je plek van nu.';
    setTimeout(aeMapInit, 30);
  }

  // Dagen en tijden
  document.querySelectorAll('#ae-days button').forEach(b => b.classList.toggle('on', !!((r.days || 0) & (1 << +b.dataset.d))));
  $('#ae-from').value = r.from || ''; $('#ae-to').value = r.to || '';

  // Actie
  aeRadio('#ae-mode', r.mode);
  const apps = aeLoadApps(), cur = r.app && r.app.p;
  let opts = (r.mode === 'notify' ? '<option value="">Geen (alleen tekst)</option>' : '<option value="">Kies een app…</option>');
  if (cur && !apps.some(a => a.p === cur)) opts += '<option value="' + esc(cur) + '">' + esc(r.app.n || cur) + ' (niet gevonden)</option>';
  const park = apps.filter(a => a.park), rest = apps.filter(a => !a.park);
  const o = a => '<option value="' + esc(a.p) + '"' + (a.p === cur ? ' selected' : '') + '>' + esc(a.n) + '</option>';
  if (park.length) opts += '<optgroup label="Parkeren">' + park.map(o).join('') + '</optgroup><optgroup label="Alle apps">' + rest.map(o).join('') + '</optgroup>';
  else opts += rest.map(o).join('');
  $('#ae-app').innerHTML = opts;
  if (cur) $('#ae-app').value = cur;
  document.querySelector('#ae-appbox .flabel label').textContent = r.mode === 'notify' ? 'App openen bij een tik op de melding' : 'App';

  $('#ae-stepsbox').style.display = r.mode === 'notify' || internal ? 'none' : 'block';
  $('#ae-appbox').style.display = internal ? 'none' : '';
  const steps = r.steps || [];
  $('#ae-steps').innerHTML = steps.map((s, i) => '<li><span class="lbl" onclick="aeEditStep(' + i + ')">' + esc(aeStepLabel(s)) + '</span>' +
    '<button aria-label="Omhoog" onclick="aeMoveStep(' + i + ',-1)"' + (i ? '' : ' disabled') + '>↑</button>' +
    '<button aria-label="Omlaag" onclick="aeMoveStep(' + i + ',1)"' + (i < steps.length - 1 ? '' : ' disabled') + '>↓</button>' +
    '<button aria-label="Verwijderen" onclick="aeDelStep(' + i + ')">✕</button></li>').join('');
  $('#ae-stepsnote').textContent = steps.length
    ? (r.mode === 'auto' ? 'Deze knoppen worden vanzelf ingedrukt.' : 'Na een tik op Starten opent de app en worden deze knoppen vanzelf ingedrukt.')
    : (r.mode === 'auto' ? 'Neem minstens één knop op, bijvoorbeeld "Start parkeren".' : 'Leeg = alleen de app openen. Neem de knoppen op om ook dat te automatiseren.');
  $('#ae-msg').value = r.msg || '';
  $('#ae-msg').placeholder = r.mode === 'notify' ? 'Bijv. Vergeet niet de parkeeractie te stoppen' : 'Bijv. Parkeeractie starten?';
  $('#ae-cool').value = String(r.cool || 15);
  $('#ae-del').style.display = aeIsNew ? 'none' : 'flex';
}

function aeSetNote(id){ const n = (notesGet() || []).find(x => x.id === id); aeRule.note = n ? { id: n.id, title: n.title || 'Notitie' } : null; }
function aeSetRadio(i){ const fs = rdJson(Android.radioFavorites(), []); const f = fs[+i]; aeRule.radio = f && i !== '' ? f : null; }
function aeStepLabel(s){
  if (s.t === 'wait') return '⏱ Wachten ' + Math.round((s.ms || 1000) / 100) / 10 + ' s';
  if (s.t === 'xy') return '👆 Tik op het scherm (' + Math.round(s.x * 100) + '%, ' + Math.round(s.y * 100) + '%)';
  return '👆 Tik op "' + (s.text || s.desc || (s.id || '').replace(/.*\//, '')) + '"';
}

function aeToggleBt(a, n){
  const r = aeRule; r.bt = r.bt || []; r.btNames = r.btNames || [];
  const i = r.bt.indexOf(a);
  if (i >= 0) { r.bt.splice(i, 1); r.btNames.splice(i, 1); } else { r.bt.push(a); r.btNames.push(n); }
  aeRender();
}
document.querySelectorAll('#ae-trig button').forEach(b => b.addEventListener('click', () => { aeRule.trig = b.dataset.v; aeRender(); }));
document.querySelectorAll('#ae-mode button').forEach(b => b.addEventListener('click', () => {
  aeRule.mode = b.dataset.v;
  if (aeRule.mode !== 'notify' && !aeRule.app) { const park = aeLoadApps().find(a => a.park); if (park) aeRule.app = { p: park.p, n: park.n }; }
  aeRender();
}));
document.querySelectorAll('#ae-radius button').forEach(b => b.addEventListener('click', () => { aePlace().r = +b.dataset.r; aeRender(); aeMapDraw(); }));
document.querySelectorAll('#ae-days button').forEach(b => b.addEventListener('click', () => { aeRule.days = (aeRule.days || 0) ^ (1 << +b.dataset.d); aeRender(); }));

function aeSetApp(p){
  const a = aeLoadApps().find(x => x.p === p);
  aeRule.app = a ? { p: a.p, n: a.n } : (p ? aeRule.app : null);
  aeRender();
}
function aeMoveStep(i, d){ const s = aeRule.steps, j = i + d; if (j < 0 || j >= s.length) return; [s[i], s[j]] = [s[j], s[i]]; aeRender(); }
function aeDelStep(i){ aeRule.steps.splice(i, 1); aeRender(); }
function aeAddText(){
  askInput('Tik op tekst', 'De tekst op de knop, precies zoals in de app (bijv. Start parkeren)', '', 'Toevoegen', v => {
    v = v.trim(); if (!v) return; (aeRule.steps = aeRule.steps || []).push({ t: 'tap', text: v.slice(0, 60) }); aeRender();
  });
}
function aeAddWait(){ (aeRule.steps = aeRule.steps || []).push({ t: 'wait', ms: 2000 }); aeRender(); }
function aeEditStep(i){
  const s = aeRule.steps[i]; if (!s) return;
  if (s.t === 'wait') {
    askInput('Wachten', 'Aantal seconden', String((s.ms || 1000) / 1000), 'OK', v => { const n = parseFloat(String(v).replace(',', '.')); if (n > 0) { s.ms = Math.round(Math.min(30, n) * 1000); aeRender(); } });
  } else if (s.t === 'tap') {
    askInput('Tik op tekst', 'De tekst op de knop', s.text || s.desc || '', 'OK', v => { v = v.trim(); if (!v) return; s.text = v.slice(0, 60); delete s.desc; aeRender(); });
  }
}

/* Kaart om de plek te kiezen (Leaflet + OpenStreetMap, zoals bij Mijn routes). */
function aeMapInit(){
  if (current !== 'autoed' || !aeNeedsPlace()) return;
  if (typeof L === 'undefined') { $('#ae-map').style.display = 'none'; return; }
  const p = aePlace();
  try {
    if (!aeMap) {
      aeMap = L.map('ae-map', { attributionControl: true, zoomControl: true });
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(aeMap);
      aeMap.on('click', e => { const q = aePlace(); q.lat = Math.round(e.latlng.lat * 1e6) / 1e6; q.lng = Math.round(e.latlng.lng * 1e6) / 1e6; aeRender(); aeMapDraw(); });
      aeMap._ruleId = null;
    }
    aeMap.invalidateSize();
    if (aeMap._ruleId !== aeRule.id) {
      aeMap._ruleId = aeRule.id;
      if (p.lat != null) aeMap.setView([p.lat, p.lng], 16); else aeMap.setView([51.92, 4.48], 11);
    }
    aeMapDraw();
  } catch (e) { $('#ae-map').style.display = 'none'; }
}
function aeMapDraw(){
  if (!aeMap || typeof L === 'undefined') return;
  const p = aeRule && aeRule.place;
  if (aeMarker) { aeMap.removeLayer(aeMarker); aeMarker = null; }
  if (aeCircle) { aeMap.removeLayer(aeCircle); aeCircle = null; }
  if (!p || p.lat == null) return;
  aeCircle = L.circle([p.lat, p.lng], { radius: p.r || 200, color: '#2F74CF', weight: 2, fillOpacity: .15 }).addTo(aeMap);
  aeMarker = L.circleMarker([p.lat, p.lng], { radius: 7, color: '#fff', weight: 2, fillColor: '#2F74CF', fillOpacity: 1 }).addTo(aeMap);
}

function aeHere(){
  if (!auState.perms.loc) { Android.autoPerm('loc'); return; }
  const b = $('#ae-here'); b.disabled = true; b.textContent = 'Locatie bepalen…';
  Android.autoHere();
}
window.onAutoHere = function(r){
  const b = $('#ae-here'); b.disabled = false; b.textContent = '📍 Mijn plek nu gebruiken';
  if (!r || r.error) { if (r && r.error === 'perm') Android.autoPerm('loc'); else toast(r && r.error || 'Geen locatie gevonden'); return; }
  if (!aeRule) return;
  const p = aePlace(); p.lat = Math.round(r.lat * 1e6) / 1e6; p.lng = Math.round(r.lng * 1e6) / 1e6;
  aeRender();
  if (aeMap) { aeMap.setView([p.lat, p.lng], 16); aeMapDraw(); }
  if (r.acc > 100) toast('Let op: de locatie is onnauwkeurig (' + r.acc + ' m). Verschuif de plek eventueel op de kaart.');
};

function aeCheck(){
  const r = aeRule;
  if (!(r.name || '').trim()) return 'Geef de automatisering een naam';
  if ((r.trig || '').startsWith('bt_') && !(r.bt || []).length) return 'Kies de Bluetooth van je auto';
  if (aeNeedsPlace() && (!r.place || r.place.lat == null)) return 'Kies de plek op de kaart';
  if (r.trig === 'time' && !r.at) return 'Kies het tijdstip';
  const act = r.act || 'app';
  if (act === 'note' && !(r.note && r.note.id)) return 'Kies welke notitie getoond moet worden';
  if (act === 'radio' && !(r.radio && r.radio.url)) return 'Kies een radiozender (zet er eerst een in je favorieten)';
  if (act === 'redial' && String(r.number || '').replace(/[^0-9+]/g, '').length < 3) return 'Vul het telefoonnummer in';
  if (act !== 'app') return '';
  if (r.mode !== 'notify' && !(r.app && r.app.p)) return 'Kies welke app geopend moet worden';
  if (r.mode === 'auto' && !(r.steps || []).length) return 'Volledig automatisch heeft minstens één knop nodig: neem ze op';
  return '';
}

function aeSave(after){
  const r = aeRule;
  r.name = (r.name || '').trim();
  if (r.place && !(r.place.name || '').trim()) r.place.name = 'de plek';
  if (!aeNeedsPlace() && r.trig !== 'arrive' && r.trig !== 'leave' && !r.usePlace) { /* plek bewaren voor later, maar niet gebruiken */ }
  let e = aeCheck();
  if (!e) e = Android.autoSave(JSON.stringify(r));
  const w = $('#ae-warn');
  if (e) { w.textContent = e; w.classList.add('on'); w.scrollIntoView({ block: 'center', behavior: 'smooth' }); return false; }
  w.classList.remove('on');
  aeIsNew = false; aeSnap = JSON.stringify(r);
  store.set('autoDraft', null);
  if (after) after(); else { toast('✓ Opgeslagen'); show('auto'); }
  return true;
}
function aeTest(){
  aeSave(() => {
    const e = Android.autoTest(aeRule.id);
    toast(e || (aeRule.mode === 'notify' ? 'Kijk in je meldingen' : aeRule.mode === 'auto' ? 'Bezig: de app wordt geopend' : 'Kijk in je meldingen en tik op Starten'));
    aeRender();
  });
}
function aeDelete(){
  askConfirm('Verwijderen?', '"' + (aeRule.name || 'Deze automatisering') + '" wordt verwijderd.', 'Verwijderen', () => { Android.autoDelete(aeRule.id); aeRule = null; show('auto'); toast('Verwijderd'); });
}

/* Opnemen: Rene's Tools gaat naar de app; na "Klaar" komt hij terug met de stappen (openTool('auto-rec')). */
function aeRecord(){
  if (!(aeRule.app && aeRule.app.p)) { toast('Kies eerst de app'); return; }
  if (!auState.perms.a11yRunning) {
    askConfirm('Eerst toegankelijkheid aanzetten', 'Om je tikken op te nemen en later na te doen, moet "Rene\'s Tools: knoppen indrukken" aan staan bij Toegankelijkheid. Kom daarna terug en tik opnieuw op Opnemen.', 'Naar instellingen', () => { store.set('autoDraft', { rule: aeRule, isNew: aeIsNew, t: Date.now() }); Android.autoA11ySettings(); });
    return;
  }
  askConfirm('Opnemen', aeRule.app.n + ' gaat open. Doe precies wat je anders ook doet (bijv. Start parkeren → Bevestigen) en tik daarna bovenin op Klaar. Werkt een knop niet? Gebruik "Kies knop" in de balk.', 'Starten', () => {
    store.set('autoDraft', { rule: aeRule, isNew: aeIsNew, t: Date.now() });
    const e = Android.autoRecord(aeRule.app.p);
    if (e) toast(e);
  });
}
function autoRecBack(){
  const rec = rdJson(Android.autoRecTake(), null), d = store.get('autoDraft', null);
  if (d && d.rule) { aeRule = d.rule; aeIsNew = !!d.isNew; }
  store.set('autoDraft', null);
  if (!aeRule) { show('auto'); return; }
  if (rec && Array.isArray(rec.steps)) {
    if (rec.steps.length) { aeRule.steps = rec.steps; toast('✓ ' + rec.steps.length + (rec.steps.length === 1 ? ' knop' : ' knoppen') + ' opgenomen. Vergeet niet op te slaan.'); }
    else toast('Er is geen knop opgenomen. Probeer "Kies knop" in de balk, of voeg een tekst toe.');
  }
  show('autoed');
}
function autoDraftBack(){
  // terug uit de toegankelijkheidsinstellingen: verder met de bewerking
  const d = store.get('autoDraft', null);
  if (current === 'autoed' && aeRule) { store.set('autoDraft', null); auLoad(); aeRender(); return; }
  store.set('autoDraft', null);
  if (d && d.rule && Date.now() - (d.t || 0) < 30 * 60e3) { aeRule = d.rule; aeIsNew = !!d.isNew; show('autoed'); }
}

window.onAutoChanged = function(){
  aeBt = null; auLoad();
  if (current === 'auto') auRender();
  if (current === 'autoed') aeRender();
};

/* Tegel op het startscherm: hoeveel automatiseringen er aan staan. */
function refreshAutoTile(){
  const el = $('#tile-auto-sub'); if (!el || !Android.autoState) return;
  const n = (rdJson(Android.autoState(), {}).rules || []).filter(r => r.on !== false).length;
  el.textContent = n ? n + (n === 1 ? ' automatisering aan' : ' automatiseringen aan') : 'Bijv. parkeren starten bij aankomst';
}
refreshAutoTile();
