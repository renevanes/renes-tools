/* ---------- Locatie kiezen ---------- */
let lcSt = {}, lcMap = null, lcMark = null, lcPt = null, lcName = '';

function enterLoc(){ lcRender(); setTimeout(lcMapInit, 50); }

function lcFmt(lat, lng){ return (+lat).toFixed(5) + ', ' + (+lng).toFixed(5); }

function lcRender(){
  lcSt = rdJson(Android.locState(), {});
  const st = lcSt;
  $('#lc-setup').style.display = st.allowed ? 'none' : 'block';
  $('#lc-step-dev').style.display = st.dev ? 'none' : '';
  $('#lc-setup-state').textContent = st.allowed ? '' : st.dev ? 'Ontwikkelaarsopties staan aan. Nog: bij "App voor neplocatie selecteren" Rene\'s Tools kiezen.' : 'Ontwikkelaarsopties staan nog uit.';
  $('#lc-dot').className = 'vpdot' + (st.on ? ' on' : '');
  $('#lc-title').textContent = st.on ? 'Locatie kiezen staat aan' : 'Echte locatie';
  $('#lc-sub').textContent = st.on ? (st.name || lcFmt(st.lat, st.lng)) : st.error || (st.allowed ? 'Kies een plek en tik op Hier zetten' : 'Eerst eenmalig instellen (hierboven)');
  $('#lc-stop').style.display = st.on ? 'block' : 'none';
  $('#lc-here').disabled = !!st.on;
  $('#lc-jitter').checked = st.jitter !== false;
  $('#lc-go').disabled = !lcPt;
  $('#lc-go').textContent = st.on ? '📍 Naar deze plek' : '📍 Hier zetten';
  const row = (p, kind, i) => '<div class="srow"><button class="sbtn" onclick="lcPickSaved(\'' + kind + '\',' + i + ')"><span class="vpic" aria-hidden="true">' + (kind === 'p' ? '★' : '🕘') + '</span><span class="stxt"><b>' + esc(p.name || lcFmt(p.lat, p.lng)) + '</b><small>' + esc(lcFmt(p.lat, p.lng)) + '</small></span></button>' +
    (kind === 'p' ? '<button class="mini" style="float:none;margin-right:6px" onclick="lcDelPlace(' + i + ')" aria-label="' + esc(p.name) + ' verwijderen">✕</button>' : '') + '</div>';
  const pl = st.places || [], rc = st.recent || [];
  $('#lc-placeswrap').style.display = pl.length ? 'block' : 'none';
  $('#lc-places').innerHTML = pl.map((p, i) => row(p, 'p', i)).join('');
  $('#lc-recentwrap').style.display = rc.length ? 'block' : 'none';
  $('#lc-recent').innerHTML = rc.map((p, i) => row(p, 'r', i)).join('');
  if (!lcPt && st.lat != null) lcSet(st.lat, st.lng, st.name || '', false);
}
window.onLocChanged = function(){ if (current === 'loc') lcRender(); if (current === 'home') refreshLocTile(); };

function lcMapInit(){
  if (typeof L === 'undefined' || current !== 'loc') return;
  if (!lcMap) {
    lcMap = L.map('lc-map', { attributionControl: true, zoomControl: true });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(lcMap);
    lcMap.on('click', e => lcSet(e.latlng.lat, e.latlng.lng, '', false));
    if (lcPt) lcMap.setView([lcPt.lat, lcPt.lng], 15); else lcMap.setView([51.92, 4.48], 11);
  }
  lcMap.invalidateSize();
  lcDraw();
}
function lcDraw(){
  if (!lcMap || !lcPt) return;
  if (lcMark) lcMap.removeLayer(lcMark);
  lcMark = L.circleMarker([lcPt.lat, lcPt.lng], { radius: 9, color: '#fff', weight: 3, fillColor: '#B03A2E', fillOpacity: 1 }).addTo(lcMap);
}
function lcSet(lat, lng, name, fly){
  lat = Math.round(lat * 1e6) / 1e6; lng = Math.round(lng * 1e6) / 1e6;
  lcPt = { lat, lng }; lcName = name || '';
  $('#lc-pick').textContent = '📍 ' + (lcName ? lcName + ' · ' : '') + lcFmt(lat, lng);
  $('#lc-go').disabled = false;
  if (lcMap) { if (fly) lcMap.setView([lat, lng], Math.max(lcMap.getZoom(), 15)); lcDraw(); }
}

function lcGo(){
  if (!lcPt) return;
  const r = Android.locStart(lcPt.lat, lcPt.lng, lcName);
  if (r === 'perm') { toast('Sta eerst locatie toe'); Android.autoPerm('loc'); return; }
  if (r === 'mock') { toast('Kies eerst Rene\'s Tools bij "App voor neplocatie selecteren" (zie Eenmalig instellen)'); lcRender(); $('#lc-setup').scrollIntoView({ behavior: 'smooth' }); return; }
  if (r) { toast(r); return; }
  const where = lcName || lcFmt(lcPt.lat, lcPt.lng);
  setTimeout(() => { lcRender(); if (lcSt.on) toast('📍 Je telefoon staat nu op ' + where); else if (lcSt.error) toast(lcSt.error); }, 600);
}
function lcStop(){ Android.locStop(); toast('Weer je echte locatie'); setTimeout(lcRender, 300); }

function lcPickSaved(kind, i){
  const p = (kind === 'p' ? lcSt.places : lcSt.recent)[i]; if (!p) return;
  lcSet(p.lat, p.lng, p.name, true);
  openSheet(p.name || lcFmt(p.lat, p.lng), lcFmt(p.lat, p.lng), [['📍 Hier zetten', lcGo], ['Alleen op de kaart tonen', () => {}]]);
}
function lcDelPlace(i){ const p = (lcSt.places || [])[i]; if (!p) return; askConfirm('Plek verwijderen?', '"' + p.name + '" verdwijnt uit je bewaarde plekken.', 'Verwijderen', () => { Android.locDelete(i); lcRender(); }); }
function lcSavePlace(){
  if (!lcPt) { toast('Kies eerst een plek op de kaart'); return; }
  askInput('Plek bewaren', 'Naam (bijv. Kantoor)', lcName, 'Bewaren', v => { const e = Android.locSave(v, lcPt.lat, lcPt.lng); if (e) toast(e); else { lcName = v.trim(); toast('★ Bewaard'); lcRender(); } });
}

/* zoeken: coördinaten (ook uit een kaartlink) direct, anders adres via Android */
function lcCoords(q){
  try { q = decodeURIComponent(q); } catch (e) { /* zoals het is */ }
  q = q.replace(/^\s*geo:/i, '').replace(/\+(?=\d)/g, '');
  const m = /@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)/.exec(q) || /[?&](?:q|ll|query)=(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)/.exec(q)
    || /^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)(?:[;?].*)?\s*$/.exec(q) || /^\s*(-?\d{1,2},\d+)\s*[;\s]\s*(-?\d{1,3},\d+)\s*$/.exec(q);
  if (!m) return null;
  const lat = parseFloat(m[1].replace(',', '.')), lng = parseFloat(m[2].replace(',', '.'));
  return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 ? { lat, lng } : null;
}
function lcSearch(){
  const q = $('#lc-q').value.trim(); if (!q) return;
  const c = lcCoords(q);
  if (c) { $('#lc-results').innerHTML = ''; lcSet(c.lat, c.lng, '', true); return; }
  $('#lc-results').innerHTML = '<p class="note">Zoeken…</p>';
  Android.locSearch(q);
}
let lcRes = [];
window.onLocSearch = function(r){
  if (!r || r.q !== $('#lc-q').value.trim()) return;
  if (r.error) { $('#lc-results').innerHTML = '<p class="note bad">' + esc(r.error) + '</p>'; return; }
  lcRes = r.results || [];
  $('#lc-results').innerHTML = lcRes.length ? '<div class="stations">' + lcRes.map((p, i) => '<div class="srow"><button class="sbtn" onclick="lcPickRes(' + i + ')"><span class="stxt"><b>' + esc(p.name) + '</b><small>' + esc(lcFmt(p.lat, p.lng)) + '</small></span></button></div>').join('') + '</div>'
    : '<p class="note">Niets gevonden. Probeer een andere schrijfwijze of tik de plek aan op de kaart.</p>';
};
function lcPickRes(i){ const p = lcRes[i]; if (!p) return; $('#lc-results').innerHTML = ''; lcSet(p.lat, p.lng, p.name.split(',')[0], true); }

/* waar ben ik echt? (alleen als Locatie kiezen uit staat) */
let lcHereWait = false;
function lcHere(){
  if (lcSt.on) return;
  if (!lcSt.perm) { Android.autoPerm('loc'); return; }
  lcHereWait = true; $('#lc-here').textContent = 'Zoeken…';
  Android.autoHere();
}
setTimeout(function(){ // na het laden van alle scripts (Automatiseren definieert deze later)
  const prev = window.onAutoHere;
  window.onAutoHere = function(r){
    if (lcHereWait) {
      lcHereWait = false; $('#lc-here').textContent = '◎ Waar ben ik?';
      if (current === 'loc') { if (!r || r.error) toast(r && r.error && r.error !== 'perm' ? r.error : 'Geen locatie gevonden'); else lcSet(r.lat, r.lng, '', true); return; }
    }
    if (prev) prev(r);
  };
  const prevA = window.onAutoChanged;
  window.onAutoChanged = function(){ if (prevA) prevA.apply(this, arguments); if (current === 'loc') lcRender(); };
}, 0);

function refreshLocTile(){
  const el = $('#tile-loc-sub'); if (!el) return;
  const st = rdJson(Android.locState(), {});
  el.textContent = st.on ? '📍 ' + (st.name || lcFmt(st.lat, st.lng)) : 'Zelf je locatie bepalen';
}
