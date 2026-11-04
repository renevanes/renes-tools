/* ---------- Scherm Instellingen → Regio en taal (de functies zelf staan in 02-regio.js) ---------- */
function rgOpts(sel, list){
  const el = $(sel); if (!el) return null;
  const key = list.map(x => x[0] + '=' + x[1]).join('|');
  if (el.dataset.k !== key) { el.innerHTML = list.map(x => '<option value="' + esc(x[0]) + '">' + esc(x[1]) + '</option>').join(''); el.dataset.k = key; }
  return el;
}
/* ---------- Tijdzone: kiezen uit een lijst (de rekenhulp staat in 02-regio.js) ---------- */
/* Als de WebView geen lijst kan geven (Intl.supportedValuesOf): veelgebruikte zones */
const RG_TZ_BASE = ['UTC', 'Europe/Amsterdam', 'Europe/Brussels', 'Europe/London', 'Europe/Dublin', 'Europe/Lisbon', 'Europe/Paris', 'Europe/Berlin', 'Europe/Madrid', 'Europe/Rome',
  'Europe/Zurich', 'Europe/Vienna', 'Europe/Copenhagen', 'Europe/Stockholm', 'Europe/Oslo', 'Europe/Helsinki', 'Europe/Warsaw', 'Europe/Prague', 'Europe/Budapest', 'Europe/Athens',
  'Europe/Bucharest', 'Europe/Istanbul', 'Europe/Kyiv', 'Europe/Kiev', 'Europe/Moscow', 'Atlantic/Canary', 'Atlantic/Reykjavik', 'Atlantic/Azores', 'Africa/Casablanca', 'Africa/Lagos', 'Africa/Cairo',
  'Africa/Johannesburg', 'Africa/Nairobi', 'Asia/Jerusalem', 'Asia/Dubai', 'Asia/Karachi', 'Asia/Kolkata', 'Asia/Calcutta', 'Asia/Kathmandu', 'Asia/Dhaka', 'Asia/Bangkok', 'Asia/Jakarta',
  'Asia/Singapore', 'Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Manila', 'Asia/Seoul', 'Asia/Tokyo', 'Australia/Perth', 'Australia/Adelaide', 'Australia/Brisbane', 'Australia/Sydney',
  'Pacific/Auckland', 'Pacific/Fiji', 'Pacific/Kiritimati', 'Pacific/Honolulu', 'Pacific/Pago_Pago', 'America/Anchorage', 'America/Los_Angeles', 'America/Denver', 'America/Phoenix',
  'America/Chicago', 'America/Mexico_City', 'America/New_York', 'America/Toronto', 'America/Halifax', 'America/Bogota', 'America/Lima', 'America/Caracas', 'America/Curacao',
  'America/Aruba', 'America/Paramaribo', 'America/Santiago', 'America/Sao_Paulo', 'America/Argentina/Buenos_Aires', 'America/Buenos_Aires', 'America/St_Johns'];
/* Nederlandse namen van plaatsen (de rest: de Engelse naam uit de zone) */
const RG_TZ_NL = { 'Europe/London': 'Londen', 'Europe/Paris': 'Parijs', 'Europe/Brussels': 'Brussel', 'Europe/Lisbon': 'Lissabon', 'Europe/Copenhagen': 'Kopenhagen', 'Europe/Prague': 'Praag',
  'Europe/Warsaw': 'Warschau', 'Europe/Vienna': 'Wenen', 'Europe/Athens': 'Athene', 'Europe/Bucharest': 'Boekarest', 'Europe/Istanbul': 'Istanboel', 'Europe/Moscow': 'Moskou',
  'Europe/Belgrade': 'Belgrado', 'Europe/Zurich': 'Zürich', 'Europe/Kiev': 'Kyiv', 'Europe/Kyiv': 'Kyiv', 'Asia/Tokyo': 'Tokio', 'Asia/Jerusalem': 'Jeruzalem', 'Africa/Cairo': 'Caïro',
  'Asia/Calcutta': 'Kolkata', 'Asia/Kolkata': 'Kolkata', 'Asia/Katmandu': 'Kathmandu', 'Asia/Saigon': 'Ho Chi Minhstad', 'Asia/Ho_Chi_Minh': 'Ho Chi Minhstad', 'America/Mexico_City': 'Mexico-Stad',
  'America/Sao_Paulo': 'São Paulo', 'America/Curacao': 'Curaçao', 'Atlantic/Canary': 'Canarische Eilanden', 'Atlantic/Azores': 'Azoren', 'Asia/Hong_Kong': 'Hongkong', 'Asia/Seoul': 'Seoel',
  'America/Havana': 'Havana', 'Asia/Baghdad': 'Bagdad', 'Asia/Tehran': 'Teheran', 'Asia/Riyadh': 'Riyad', 'Africa/Algiers': 'Algiers', 'Europe/Luxembourg': 'Luxemburg',
  'Europe/Berlin': 'Berlijn', 'Europe/Budapest': 'Boedapest', 'Europe/Bratislava': 'Bratislava', 'Europe/Vilnius': 'Vilnius', 'Europe/Tallinn': 'Tallinn', 'Europe/Riga': 'Riga',
  'Asia/Shanghai': 'Peking', 'Asia/Beirut': 'Beiroet', 'UTC': 'UTC (wereldtijd)' };
/* Landen en andere woorden om op te zoeken (en voor de assistent: "Londense tijd", "Japanse tijd") */
const RG_TZ_WORDS = { 'Europe/Amsterdam': 'nederland nederlandse holland hollandse amsterdamse rotterdam', 'Europe/London': 'engeland engelse brits britse verenigd koninkrijk londense uk',
  'Europe/Dublin': 'ierland ierse', 'Europe/Paris': 'frankrijk franse parijse', 'Europe/Berlin': 'duitsland duitse berlijnse munchen frankfurt hamburg keulen', 'Europe/Brussels': 'belgie belgische brusselse antwerpen gent',
  'Europe/Budapest': 'hongarije hongaarse', 'Europe/Prague': 'tsjechie tsjechische', 'Europe/Vienna': 'oostenrijk oostenrijkse', 'Europe/Copenhagen': 'denemarken deense',
  'Europe/Stockholm': 'zweden zweedse', 'Europe/Oslo': 'noorwegen noorse', 'Europe/Helsinki': 'finland finse', 'Africa/Johannesburg': 'kaapstad zuid-afrika zuid-afrikaanse',
  'America/Aruba': 'arubaanse', 'America/Kralendijk': 'bonaire', 'America/Lower_Princes': 'sint maarten', 'America/Chicago': 'chicago', 'Asia/Singapore': 'singaporese',
  'Asia/Seoul': 'korea koreaanse', 'Asia/Ho_Chi_Minh': 'vietnam vietnamese', 'Asia/Saigon': 'vietnam vietnamese', 'Pacific/Auckland': 'nieuw-zeeland',
  'Europe/Madrid': 'spanje spaanse', 'Europe/Rome': 'italie italiaanse', 'Europe/Lisbon': 'portugal portugese', 'Europe/Athens': 'griekenland griekse', 'Europe/Istanbul': 'turkije turkse',
  'Europe/Moscow': 'rusland russische', 'Europe/Warsaw': 'polen poolse', 'Europe/Zurich': 'zwitserland zwitserse', 'Africa/Casablanca': 'marokko marokkaanse', 'Asia/Tokyo': 'japan japanse',
  'Asia/Shanghai': 'china chinese shanghai beijing', 'Asia/Kolkata': 'india indiase', 'Asia/Calcutta': 'india indiase', 'Asia/Bangkok': 'thailand thaise', 'Asia/Jakarta': 'indonesie indonesische',
  'Asia/Dubai': 'emiraten', 'Australia/Sydney': 'australie australische', 'America/New_York': 'amerikaanse oostkust new yorkse', 'America/Los_Angeles': 'californie westkust',
  'America/Paramaribo': 'suriname surinaamse', 'America/Curacao': 'antillen antilliaanse', 'America/Sao_Paulo': 'brazilie braziliaanse', 'America/Toronto': 'canada canadese',
  'America/Mexico_City': 'mexico mexicaanse', 'UTC': 'utc gmt wereldtijd' };
let rgzAll = null;
/* Alle zones die de telefoon kent (WebView en Java), zonder dubbele */
function rgTzList(){
  if (rgzAll) return rgzAll;
  let l = [];
  try { if (typeof Intl.supportedValuesOf === 'function') l = Intl.supportedValuesOf('timeZone'); } catch (e) { l = []; }
  if (!l || !l.length) l = RG_TZ_BASE;
  const ph = rgPhoneTz(), seen = new Set(), out = [];
  for (const z of [ph, 'UTC'].concat(l)) if (!seen.has(z) && rgTzOk(z)) { seen.add(z); out.push(z); }
  return (rgzAll = out);
}
/* "New York" (of de Nederlandse naam) */
function rgTzCity(z){
  if (!z) return '';
  if (RG_TZ_NL[z] && !(typeof TAAL !== 'undefined' && TAAL.on)) return RG_TZ_NL[z]; // in het Engels de Engelse naam (London, Tokyo)
  const p = String(z).split('/');
  return p[p.length - 1].replace(/_/g, ' ');
}
/* "UTC−5", "UTC+5:30", "UTC" op tijdstip t (zonder t: nu) */
function rgTzOffLabel(z, t){
  let o = 0;
  try { const ms = t == null ? Date.now() : t, s = Math.floor(ms / 1000) * 1000, p = rgPartsIn(s, z); o = Math.round((Date.UTC(p.y, p.m - 1, p.d, p.hh, p.mm, p.ss) - s) / 6e4); } catch (e) { return ''; }
  return rgTzOffMin(o);
}
/* "UTC−5" uit een verschil in minuten */
function rgTzOffMin(o){ if (!o) return 'UTC'; const a = Math.abs(o), h = Math.floor(a / 60), m = a % 60; return 'UTC' + (o < 0 ? '−' : '+') + h + (m ? ':' + String(m).padStart(2, '0') : ''); }
/* "New York (UTC−5)" */
function rgTzName(z, t){ const off = rgTzOffLabel(z, t); return rgTzCity(z) + (off && off !== rgTzCity(z) && z !== 'UTC' ? ' (' + off + ')' : ''); }
/* De zone bij een naam uit een zin ("New York", "Londen", "japanse"); '' als er geen (of meer dan één) past */
function rgTzFind(q){
  const n = norm2(String(q || '')).replace(/[^a-z0-9 /_-]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^(de |het )/, '').replace(/ (tijd|tijdzone)$/, '');
  if (!n) return '';
  const all = rgTzList(), cityN = z => norm2(rgTzCity(z)), engN = z => norm2(String(z).split('/').pop().replace(/_/g, ' '));
  // Een zone-id ("asia/kolkata"): ook een andere naam voor een zone uit de lijst, als de telefoon hem kent
  if (n.includes('/')) {
    const ex = all.find(z => z.toLowerCase() === n.replace(/ /g, '_'));
    if (ex) return ex;
    const cand = n.replace(/ /g, '_').split('/').map(p => p.replace(/(^|[_-])([a-z])/g, (m, a, b) => a + b.toUpperCase())).join('/');
    return rgTzOk(cand) ? cand : '';
  }
  let hit = all.filter(z => cityN(z) === n || engN(z) === n);
  if (!hit.length) hit = all.filter(z => (' ' + norm2(RG_TZ_WORDS[z] || '') + ' ').includes(' ' + n + ' '));
  // "londense" → Londen, "new yorkse" → New York
  if (!hit.length && /se$/.test(n)) { const b = n.replace(/se$/, ''); hit = all.filter(z => cityN(z) === b || engN(z) === b || cityN(z) === b + 'n'); }
  hit = [...new Set(hit)];
  // Meer dan één: alleen als het dezelfde plaats is (Europe/Kiev en Europe/Kyiv), anders niet raden
  return hit.length && hit.every(z => rgTzCity(z) === rgTzCity(hit[0])) ? hit[0] : '';
}
/* Rij bij Regio en taal: "Volg de telefoon (Europe/Amsterdam)" of "New York (UTC−5) · nu 09:05" */
function rgTzSummary(){
  const st = rgTzState();
  return st.sel ? t('{zone} · nu {tijd}', { zone: rgTzName(st.sel), tijd: fmtTime(Date.now()) }) : t('Volg de telefoon ({zone})', { zone: st.phone });
}
function rgTzBadText(){
  const st = rgTzState();
  return st.bad ? t('De gekozen tijdzone ({zone}) kent deze telefoon niet. De app volgt nu de telefoon ({telefoon}).', { zone: st.bad, telefoon: st.phone }) : '';
}
function rgTzOpen(){ $('#rgz-q').value = ''; show('setregiotz'); }
function rgzRender(){
  const list = $('#rgz-list'); if (!list) return;
  const st = rgTzState(), now = Date.now(), q = norm2($('#rgz-q').value || '').trim();
  // Alleen bij een andere zone of minuut opnieuw schrijven (aria-live: niet bij elke toets voorlezen)
  const nowT = t('Nu: {zone}, {datum} {tijd}.', { zone: st.sel ? rgTzName(st.sel) : t('volgt de telefoon ({zone})', { zone: rgTzName(st.phone) }), datum: fmtDate(now, 'wdm'), tijd: fmtTime(now) });
  if ($('#rgz-now').textContent !== nowT) $('#rgz-now').textContent = nowT;
  const bad = rgTzBadText(); $('#rgz-bad').hidden = !bad; if ($('#rgz-bad').textContent !== bad) $('#rgz-bad').textContent = bad;
  const row = (v, title, sub, on) => '<div class="srow"><button class="sbtn' + (on ? ' on' : '') + '" data-z="' + esc(v) + '" aria-pressed="' + (on ? 'true' : 'false') + '" onclick="rgzPick(this.dataset.z)"><span class="stxt"><b>' + esc(title) + (on ? '<span aria-hidden="true"> ✓</span>' : '') + '</b><small>' + esc(sub) + '</small></span></button></div>';
  // Per zone één keer de onderdelen van nu (verschil met UTC en de tijd daar), zonder een eigen opmaak per zone
  const s0 = Math.floor(now / 1000) * 1000, info = z => { const p = rgPartsIn(s0, z), o = Math.round((Date.UTC(p.y, p.m - 1, p.d, p.hh, p.mm, p.ss) - s0) / 6e4);
    return { z, o, off: rgTzOffMin(o), tm: fmtHm(String(p.hh).padStart(2, '0') + ':' + String(p.mm).padStart(2, '0')) }; };
  const fit = x => !q || (norm2(rgTzCity(x.z)) + ' ' + norm2(x.z.replace(/_/g, ' ')) + ' ' + norm2(RG_TZ_WORDS[x.z] || '') + ' ' + x.off.toLowerCase()).includes(q);
  const zs = rgTzList().map(info).filter(fit);
  zs.sort((a, b) => a.o - b.o || rgTzCity(a.z).localeCompare(rgTzCity(b.z)));
  let h = !q || /volg|telefoon/.test(q) ? row('', t('Volg de telefoon ({zone})', { zone: st.phone }), t('Nu {tijd} · de standaard', { tijd: info(st.phone).tm }), !st.sel) : '';
  // Alle zones (ook UTC+12 tot +14 onderaan: Auckland, Fiji, Tonga, Kiritimati)
  h += zs.map(x => row(x.z, rgTzCity(x.z) + (x.z === 'UTC' ? '' : ' (' + x.off + ')'), t('Nu {tijd} · {zone}', { tijd: x.tm, zone: x.z.replace(/_/g, ' ') }), st.sel === x.z)).join('');
  list.innerHTML = h || '<p class="note">' + esc(t('Geen tijdzone gevonden. Zoek op een stad, zoals New York of Tokio.')) + '</p>';
}
function rgzPick(z){
  const prev = rgCfg().tz || '';
  if (z && !rgTzOk(z)) { toast(t('Deze tijdzone kent de telefoon niet')); return; }
  if (z === prev && rgTzOk(prev)) { goBack(); return; }
  rgSet('tz', z);
  goBack();
  undoToast(t('Tijdzone: {zone}', { zone: z ? rgTzName(z) : t('volgt de telefoon') }), () => rgSet('tz', prev));
}
function rgPlaceList(){
  const c = rgCfg();
  return [['auto', t('Mijn locatie')], ['home', c.home ? t('Thuis') : t('Thuis (nog niet ingesteld)')], ['work', c.work ? t('Werk') : t('Werk (nog niet ingesteld)')]].concat(RG_CITIES.map(x => [x[0], t(x[1])]));
}
function rgName(list, v){ const x = list.find(p => p[0] === String(v)); return x ? x[1] : String(v); }
/* "dinsdag 3-11-2026 · 14:05 · € 1.234,56 · 12,5 km · 18 °C" */
function rgPreview(){
  const n = Date.now();
  return rgPreviewParts(n).join(' · ');
}
function rgPreviewParts(n){ return [fmtDate(n, 'wdl') + ' ' + fmtDate(n), fmtTime(n), fmtMoney(1234.56), fmtDist(12500), fmtTemp(18)]; }
function rgSummary(){
  const c = rgCfg(), tz = rgTzState().sel;
  return rgLang().name + ' · ' + t(c.clock === '12' ? '12 uur' : '24 uur') + ' · ' + fmtDate(Date.now()) + (tz ? ' · ' + rgTzCity(tz) : '') + ' · ' + distUnit() + ' · ' + rgCurInfo(c.cur).sym;
}
function rgSpotText(p){ return p ? (p.addr || fmtNum(p.lat, 5) + '; ' + fmtNum(p.lon, 5)) : t('Nog niet ingesteld'); }
function rgRender(){
  if (!$('#rg-prev')) return;
  const c = rgCfg();
  rgOpts('#rg-lang', RG_LANGS.map(l => [l.id, l.name])).value = c.lang;
  const tl = l => l.map(x => [x[0], t(x[1])]);
  rgOpts('#rg-clock', tl(RG_CLOCK)).value = c.clock;
  rgOpts('#rg-date', RG_DATE).value = c.date;
  rgOpts('#rg-week', tl(RG_WEEK)).value = String(c.week);
  $('#rg-tz').textContent = rgTzSummary();
  $('#rg-tz-btn').textContent = rgCfg().tz ? t('Wijzigen') : t('Kiezen');
  const bad = rgTzBadText(); $('#rg-tz-bad').hidden = !bad; $('#rg-tz-bad').textContent = bad;
  rgOpts('#rg-place', rgPlaceList()).value = c.place;
  ['home', 'work'].forEach(w => { const p = c[w]; $('#rg-' + w + '-sub').textContent = p ? (p.name && p.name !== rgSpotName(w) ? p.name + ' · ' : '') + rgSpotText(p) : t('Nog niet ingesteld'); $('#rg-' + w + '-btn').textContent = p ? t('Wijzigen') : t('Kiezen'); });
  rgOpts('#rg-dist', tl(RG_DIST)).value = c.dist;
  rgOpts('#rg-temp', RG_TEMP).value = c.temp;
  rgOpts('#rg-size', RG_SIZE).value = c.size;
  rgOpts('#rg-speed', tl(RG_SPEED)).value = c.speed;
  rgOpts('#rg-cur', tl(RG_CUR)).value = c.cur;
  rgOpts('#rg-dec', tl(RG_DEC)).value = c.dec;
  // Duizendtallen: niet hetzelfde teken als de decimalen
  rgOpts('#rg-grp', tl(RG_GRP.filter(x => x[0] !== c.dec))).value = c.grp;
  // Elk deel blijft heel (geen "€" aan het eind van een regel)
  const tt = rgPreview(); if ($('#rg-prev').textContent !== tt) $('#rg-prev').innerHTML = rgPreviewParts(Date.now()).map(x => '<span class="rg-part">' + esc(x) + '</span>').join(' · ');
  $('#rg-num-prev').textContent = t('Voorbeeld:') + ' ' + fmtNum(1234567.89, 2) + ' · ' + fmtMoney(-1234.5) + ' · ' + fmtNum(0.5, 1) + ' · ' + fmtSize(1.5 * 1073741824) + ' · ' + fmtSpeed(100, 0);
}
function rgSet(k, v){
  rgSave({ [k]: v });
  rgRender();
  if (typeof stSummaries === 'function' && $('#st-sum-regio')) $('#st-sum-regio').textContent = rgSummary();
}
function rgReset(){
  const prev = rgCfg(), keep = { home: prev.home, work: prev.work };
  askConfirm('Terug naar de standaard?', 'Nederlands, 24 uur, 3-11-2026, maandag, de tijdzone van de telefoon, km, °C, euro en 1.234,56. Thuis en werk blijven bewaard.', 'Terugzetten', () => {
    rgSave(Object.assign({}, RG_DEF, keep)); rgRender();
    undoToast('Regio en taal: standaard', () => { rgSave(prev); rgRender(); });
  });
}

/* ---------- Thuis en Werk kiezen ---------- */
let rgpW = 'home', rgpDraft = null, rgpWait = '', rgpHereWait = false, rgpRes = [];
function rgPlek(w){
  rgpW = w === 'work' ? 'work' : 'home';
  const p = rgCfg()[rgpW];
  rgpDraft = p ? Object.assign({}, p) : null;
  $('#rgp-name').value = p && p.name ? p.name : rgSpotName(rgpW);
  $('#rgp-q').value = ''; $('#rgp-results').innerHTML = '';
  show('setregioplek');
}
function rgpRender(){
  $('#rgp-title').textContent = rgSpotName(rgpW);
  $('#rgp-now').textContent = rgpDraft ? t('Gekozen: {plek}', { plek: rgSpotText(rgpDraft) }) : t('Nog geen plek gekozen. Zoek een adres, tik coördinaten in of gebruik je huidige locatie.');
  $('#rgp-clear').disabled = !rgCfg()[rgpW];
}
/* "51.92, 4.48" of "51,92 4,48" */
function rgCoords(q){
  const m = /^\s*(-?\d{1,2}(?:[.,]\d+)?)\s*[,; ]\s*(-?\d{1,3}(?:[.,]\d+)?)\s*$/.exec(String(q || ''));
  if (!m) return null;
  const lat = parseFloat(m[1].replace(',', '.')), lon = parseFloat(m[2].replace(',', '.'));
  return Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? { lat, lon } : null;
}
function rgpSearch(){
  const q = $('#rgp-q').value.trim(); if (!q) { toast('Tik een adres of coördinaten in'); return; }
  const c = rgCoords(q);
  if (c) { $('#rgp-results').innerHTML = ''; rgpDraft = { lat: c.lat, lon: c.lon, addr: '' }; rgpRender(); return; }
  if (typeof Android.locSearch !== 'function') { toast('Adressen zoeken kan hier niet; tik coördinaten in'); return; }
  rgpWait = q; $('#rgp-results').innerHTML = '<p class="note">Zoeken…</p>';
  Android.locSearch(q);
}
function rgpPick(i){ const p = rgpRes[i]; if (!p) return; rgpDraft = { lat: p.lat, lon: p.lng != null ? p.lng : p.lon, addr: p.name || '' }; $('#rgp-results').innerHTML = ''; rgpRender(); }
function rgpHere(){
  if (typeof Android.autoHere !== 'function') return;
  rgpHereWait = true; const b = $('#rgp-here'); b.disabled = true; b.lastChild.textContent = ' ' + t('Locatie bepalen…');
  Android.autoHere();
}
function rgpSave(){
  if (!rgpDraft) { toast('Kies eerst een plek'); return; }
  const name = $('#rgp-name').value.trim().slice(0, 60) || rgSpotName(rgpW);
  rgSave({ [rgpW]: Object.assign({}, rgpDraft, { name }) });
  toast(t('{naam} bewaard', { naam: rgSpotName(rgpW) }));
  goBack();
}
function rgpClear(){
  askConfirm(t('{naam} wissen?', { naam: rgSpotName(rgpW) }), 'De app gebruikt dan weer je locatie of de standaardplaats.', 'Wissen', () => {
    const patch = { [rgpW]: null }; if (rgCfg().place === rgpW) patch.place = 'auto';
    rgSave(patch); toast(t('{naam} gewist', { naam: rgSpotName(rgpW) })); goBack();
  });
}
setTimeout(function(){ // na het laden van alle scripts: Locatie kiezen en Automatiseren gebruiken dezelfde antwoorden
  const prevS = window.onLocSearch;
  window.onLocSearch = function(r){
    if (rgpWait && r && r.q === rgpWait && current === 'setregioplek') {
      rgpWait = '';
      if (r.error) { $('#rgp-results').innerHTML = '<p class="note bad">' + esc(r.error) + '</p>'; return; }
      rgpRes = r.results || [];
      $('#rgp-results').innerHTML = rgpRes.length ? '<div class="stations">' + rgpRes.map((p, i) => '<div class="srow"><button class="sbtn" onclick="rgpPick(' + i + ')"><span class="stxt"><b>' + esc(p.name) + '</b><small>' + esc(fmtNum(p.lat, 5) + '; ' + fmtNum(p.lng != null ? p.lng : p.lon, 5)) + '</small></span></button></div>').join('') + '</div>'
        : '<p class="note">Niets gevonden. Probeer een andere schrijfwijze of tik coördinaten in.</p>';
      return;
    }
    if (prevS) prevS(r);
  };
  const prevH = window.onAutoHere;
  window.onAutoHere = function(r){
    if (rgpHereWait) {
      rgpHereWait = false; const b = $('#rgp-here'); b.disabled = false; b.lastChild.textContent = ' ' + t('Mijn huidige locatie');
      if (current === 'setregioplek') {
        if (!r || r.error) { if (r && r.error === 'perm') Android.autoPerm('loc'); else toast(r && r.error || 'Geen locatie gevonden'); return; }
        rgpDraft = { lat: r.lat, lon: r.lng, addr: '' }; rgpRender();
        if (r.acc > 100) toast(t('Let op: de locatie is onnauwkeurig ({n})', { n: fmtMeters(r.acc) }));
        return;
      }
    }
    if (prevH) prevH(r);
  };
}, 0);
addEventListener('regiochange', () => { if (typeof current !== 'undefined' && current === 'setregio') rgRender(); if (typeof current !== 'undefined' && current === 'setregiotz') rgzRender(); });
