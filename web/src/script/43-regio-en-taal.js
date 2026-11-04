/* ---------- Scherm Instellingen → Regio en taal (de functies zelf staan in 02-regio.js) ---------- */
function rgOpts(sel, list){
  const el = $(sel); if (!el) return null;
  const key = list.map(x => x[0]).join('|');
  if (el.dataset.k !== key) { el.innerHTML = list.map(x => '<option value="' + esc(x[0]) + '">' + esc(x[1]) + '</option>').join(''); el.dataset.k = key; }
  return el;
}
/* De tijdzone volgt altijd de telefoon (alleen ter info) */
function rgPhoneTz(){ try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; } }
function rgPlaceList(){
  const c = rgCfg();
  return [['auto', 'Mijn locatie'], ['home', 'Thuis' + (c.home ? '' : ' (nog niet ingesteld)')], ['work', 'Werk' + (c.work ? '' : ' (nog niet ingesteld)')]].concat(RG_CITIES.map(x => [x[0], x[1]]));
}
function rgName(list, v){ const x = list.find(p => p[0] === String(v)); return x ? x[1] : String(v); }
/* "dinsdag 3-11-2026 · 14:05 · € 1.234,56 · 12,5 km · 18 °C" */
function rgPreview(){
  const n = Date.now();
  return rgPreviewParts(n).join(' · ');
}
function rgPreviewParts(n){ return [fmtDate(n, 'wdl') + ' ' + fmtDate(n), fmtTime(n), fmtMoney(1234.56), fmtDist(12500), fmtTemp(18)]; }
function rgSummary(){
  const c = rgCfg();
  return rgLang().name + ' · ' + (c.clock === '12' ? '12 uur' : '24 uur') + ' · ' + fmtDate(Date.now()) + ' · ' + distUnit() + ' · ' + rgCurInfo(c.cur).sym;
}
function rgSpotText(p){ return p ? (p.addr || fmtNum(p.lat, 5) + '; ' + fmtNum(p.lon, 5)) : 'Nog niet ingesteld'; }
function rgRender(){
  if (!$('#rg-prev')) return;
  const c = rgCfg();
  rgOpts('#rg-lang', RG_LANGS.map(l => [l.id, l.name])).value = c.lang;
  rgOpts('#rg-clock', RG_CLOCK).value = c.clock;
  rgOpts('#rg-date', RG_DATE).value = c.date;
  rgOpts('#rg-week', RG_WEEK).value = String(c.week);
  const ptz = rgPhoneTz();
  $('#rg-tz').textContent = (ptz ? ptz.replace(/_/g, ' ') + ' ' : '') + '(volgt de telefoon)';
  rgOpts('#rg-place', rgPlaceList()).value = c.place;
  ['home', 'work'].forEach(w => { const p = c[w]; $('#rg-' + w + '-sub').textContent = p ? (p.name && p.name !== rgSpotName(w) ? p.name + ' · ' : '') + rgSpotText(p) : 'Nog niet ingesteld'; $('#rg-' + w + '-btn').textContent = p ? 'Wijzigen' : 'Kiezen'; });
  rgOpts('#rg-dist', RG_DIST).value = c.dist;
  rgOpts('#rg-temp', RG_TEMP).value = c.temp;
  rgOpts('#rg-size', RG_SIZE).value = c.size;
  rgOpts('#rg-speed', RG_SPEED).value = c.speed;
  rgOpts('#rg-cur', RG_CUR).value = c.cur;
  rgOpts('#rg-dec', RG_DEC).value = c.dec;
  // Duizendtallen: niet hetzelfde teken als de decimalen
  rgOpts('#rg-grp', RG_GRP.filter(x => x[0] !== c.dec)).value = c.grp;
  // Elk deel blijft heel (geen "€" aan het eind van een regel)
  const t = rgPreview(); if ($('#rg-prev').textContent !== t) $('#rg-prev').innerHTML = rgPreviewParts(Date.now()).map(x => '<span class="rg-part">' + esc(x) + '</span>').join(' · ');
  $('#rg-num-prev').textContent = 'Voorbeeld: ' + fmtNum(1234567.89, 2) + ' · ' + fmtMoney(-1234.5) + ' · ' + fmtNum(0.5, 1) + ' · ' + fmtSize(1.5 * 1073741824) + ' · ' + fmtSpeed(100, 0);
}
function rgSet(k, v){
  rgSave({ [k]: v });
  rgRender();
  if (typeof stSummaries === 'function' && $('#st-sum-regio')) $('#st-sum-regio').textContent = rgSummary();
}
function rgReset(){
  const prev = rgCfg(), keep = { home: prev.home, work: prev.work };
  askConfirm('Terug naar de standaard?', 'Nederlands, 24 uur, 3-11-2026, maandag, km, °C, euro en 1.234,56. Thuis en werk blijven bewaard.', 'Terugzetten', () => {
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
  $('#rgp-now').textContent = rgpDraft ? 'Gekozen: ' + rgSpotText(rgpDraft) : 'Nog geen plek gekozen. Zoek een adres, tik coördinaten in of gebruik je huidige locatie.';
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
  rgpHereWait = true; const b = $('#rgp-here'); b.disabled = true; b.lastChild.textContent = ' Locatie bepalen…';
  Android.autoHere();
}
function rgpSave(){
  if (!rgpDraft) { toast('Kies eerst een plek'); return; }
  const name = $('#rgp-name').value.trim().slice(0, 60) || rgSpotName(rgpW);
  rgSave({ [rgpW]: Object.assign({}, rgpDraft, { name }) });
  toast(rgSpotName(rgpW) + ' bewaard');
  goBack();
}
function rgpClear(){
  askConfirm(rgSpotName(rgpW) + ' wissen?', 'De app gebruikt dan weer je locatie of de standaardplaats.', 'Wissen', () => {
    const patch = { [rgpW]: null }; if (rgCfg().place === rgpW) patch.place = 'auto';
    rgSave(patch); toast(rgSpotName(rgpW) + ' gewist'); goBack();
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
      rgpHereWait = false; const b = $('#rgp-here'); b.disabled = false; b.lastChild.textContent = ' Mijn huidige locatie';
      if (current === 'setregioplek') {
        if (!r || r.error) { if (r && r.error === 'perm') Android.autoPerm('loc'); else toast(r && r.error || 'Geen locatie gevonden'); return; }
        rgpDraft = { lat: r.lat, lon: r.lng, addr: '' }; rgpRender();
        if (r.acc > 100) toast('Let op: de locatie is onnauwkeurig (' + fmtMeters(r.acc) + ')');
        return;
      }
    }
    if (prevH) prevH(r);
  };
}, 0);
addEventListener('regiochange', () => { if (typeof current !== 'undefined' && current === 'setregio') rgRender(); });
