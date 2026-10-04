/* ---------- Mijn routes ---------- */
let trackPoll = null;
const fmtKm = m => (m/1000).toFixed(2).replace('.', ',');
const fmtDur = ms => { const s = Math.floor(ms/1000), h = Math.floor(s/3600), m = Math.floor(s%3600/60), ss = s%60;
  return h > 0 ? h + ':' + String(m).padStart(2,'0') + ':' + String(ss).padStart(2,'0') : m + ':' + String(ss).padStart(2,'0'); };
const kmh = ms => (ms*3.6).toFixed(1).replace('.', ',');

function enterTracks(){
  const perm = Android.trackHasPermission();
  const gps = Android.trackGpsOn();
  $('#tr-perm').style.display = perm ? 'none' : 'block';
  $('#tr-gps').style.display = (perm && !gps) ? 'block' : 'none';
  $('#tr-perm-btn').textContent = store.get('trAsked', false) ? 'Instellingen openen' : 'Toestemming geven';
  loadTrackList();
  trackPollOnce();
  stopTrackPoll(); trackPoll = setInterval(trackPollOnce, 1000);
}
function stopTrackPoll(){ if (trackPoll) { clearInterval(trackPoll); trackPoll = null; } }
window.onTrackPerm = function(){ store.set('trAsked', false); enterTracks(); };

function trAskPerm(){
  if (store.get('trAsked', false) && !Android.trackHasPermission()) { Android.openAppSettings(); return; }
  store.set('trAsked', true); Android.trackRequestPermission();
}
function trStart(){
  if (!Android.trackGpsOn()) { toast('Zet eerst locatie (gps) aan'); return; }
  const err = Android.trackStart();
  if (err) { toast(err); if (!Android.trackHasPermission()) trAskPerm(); return; }
  Android.vibrate(25); setTimeout(trackPollOnce, 200);
}
function trPause(){ const s = tstat(); if (s.paused) Android.trackResume(); else Android.trackPause(); Android.vibrate(10); setTimeout(trackPollOnce, 100); }
function trStop(){ askConfirm('Opname stoppen?', 'Je route wordt daarna opgeslagen.', 'Stoppen', () => { Android.trackStop(); Android.vibrate(25); setTimeout(trackPollOnce, 200); }); }
function tstat(){ try { return JSON.parse(Android.trackStatus()); } catch(e){ return {running:false}; } }

function trackPollOnce(){
  const s = tstat();
  const just = !s.running ? Android.trackJustStopped() : '';
  const saving = !!just;
  $('#tr-rec').style.display = s.running ? 'block' : 'none';
  $('#tr-save').style.display = saving ? 'block' : 'none';
  $('#tr-home').style.display = (s.running || saving) ? 'none' : 'block';
  if (s.running) {
    $('#tr-pause').textContent = s.paused ? 'Hervatten' : 'Pauze';
    $('#tr-dist').textContent = fmtKm(s.distance || 0);
    $('#tr-time').textContent = fmtDur((s.now || Date.now()) - s.startT);
    $('#tr-speed').textContent = s.paused ? '–' : kmh(lastSpeed(s));
    $('#tr-avg').textContent = s.movingMs ? kmh((s.distance||0)/(s.movingMs/1000)) : '0,0';
    $('#tr-ele').textContent = s.ele != null ? Math.round(s.ele) + ' m' : '–';
    $('#tr-acc').textContent = s.acc != null ? '± ' + Math.round(s.acc) : '–';
    $('#tr-pts').textContent = s.points || 0;
    $('#tr-wait').style.display = (s.points > 0) ? 'none' : 'block';
    $('#tr-wait').textContent = (s.acc != null && s.acc > 30)
      ? 'Gps nog niet nauwkeurig genoeg (± ' + Math.round(s.acc) + ' m). Buiten met vrij zicht gaat het sneller.'
      : 'Wachten op gps-signaal…';
    if (s.error) { $('#tr-wait').style.display = 'block'; $('#tr-wait').textContent = s.error; }
    $('#tr-map-path').setAttribute('d', s.path || '');
    updateLiveMap(s);
  } else if (saving) {
    try {
      const j = JSON.parse(just), st = j.stats || {};
      $('#tr-save-sum').textContent = fmtKm(st.distance||0) + ' km · ' + fmtDur(st.totalMs||0) + ' · ' + j.points + ' punten';
      if (!$('#tr-name').value) $('#tr-name').value = defaultName();
    } catch(e){}
  }
  refreshTrackTile(s);
}
// live kaart tijdens het opnemen
let recMap = null, recLine = null, recPts = [], recFitted = false, recStartT = 0;
function resetLiveMap(startT){
  recPts = []; recFitted = false; recStartT = startT || 0;
  if (recLine && recMap) { recMap.removeLayer(recLine); recLine = null; }
  // de reeds opgenomen punten ophalen (bij heropenen tijdens een lopende opname)
  try { const seed = JSON.parse(Android.trackLivePoints()); if (Array.isArray(seed)) recPts = seed; } catch(e){}
}
function updateLiveMap(s){
  if (typeof L === 'undefined') { $('#tr-lmap').style.display = 'none'; $('#tr-map').style.display = 'block'; return; }
  if (s.startT && s.startT !== recStartT) resetLiveMap(s.startT);
  $('#tr-lmap').style.display = 'block'; $('#tr-map').style.display = 'none';
  try {
    if (!recMap) {
      recMap = L.map('tr-lmap', { attributionControl: true, zoomControl: true });
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(recMap);
      recMap.setView([52.1, 5.1], 7);
      setTimeout(() => { if (recMap) recMap.invalidateSize(); }, 60);
    }
    if (s.lat != null && s.lon != null) {
      const last = recPts[recPts.length - 1];
      if (!last || last[0] !== s.lat || last[1] !== s.lon) recPts.push([s.lat, s.lon]);
    }
    if (recPts.length) {
      if (!recLine) { recLine = L.polyline(recPts, { color: '#C0392B', weight: 5, opacity: .9 }).addTo(recMap); }
      else recLine.setLatLngs(recPts);
      const cur = recPts[recPts.length - 1];
      if (!recFitted) { recMap.setView(cur, 16); recFitted = true; setTimeout(() => { if (recMap) recMap.invalidateSize(); }, 60); }
      else recMap.panTo(cur, { animate: true });
    }
  } catch(e){ $('#tr-lmap').style.display = 'none'; $('#tr-map').style.display = 'block'; }
}
let _spd = {t:0,d:0,v:0};
function lastSpeed(s){ // afgeleide snelheid uit afstandsverschil
  if (s.distance == null) return 0;
  const now = s.now || Date.now();
  if (_spd.t && now > _spd.t) { const dv = (s.distance - _spd.d) / ((now - _spd.t)/1000); _spd.v = dv >= 0 && dv < 50 ? dv : _spd.v; }
  _spd.t = now; _spd.d = s.distance; return _spd.v;
}
function defaultName(){ const d = new Date(); const h = d.getHours();
  const tod = h < 6 ? 'Nacht' : h < 12 ? 'Ochtend' : h < 18 ? 'Middag' : 'Avond';
  return tod + 'route ' + d.toLocaleDateString('nl-NL', {day:'numeric', month:'short'}); }
function trSave(){ const err = Android.trackSave($('#tr-name').value.trim()); if (err) { toast(err); return; } $('#tr-name').value=''; toast('Route opgeslagen ✓'); loadTrackList(); trackPollOnce(); }
function trDiscard(){ askConfirm('Opname weggooien?', 'De opgenomen punten gaan verloren.', 'Weggooien', () => { Android.trackDiscard(); $('#tr-name').value=''; trackPollOnce(); }); }

function loadTrackList(){
  let list; try { list = JSON.parse(Android.trackList()); } catch(e){ list = []; }
  const el = $('#tr-list');
  $('#tr-empty').style.display = list.length ? 'none' : 'block';
  el.innerHTML = list.map(r => {
    const st = r.stats || {};
    return '<button onclick="openTrack(' + JSON.stringify(r.id).replace(/"/g,'&quot;') + ')">' +
      '<svg class=mini viewBox="0 0 300 160" preserveAspectRatio="xMidYMid meet"><path d="' + (r.path||'') + '"></path></svg>' +
      '<span class=info><b>' + esc(r.title || 'Route') + '</b>' +
      '<small>' + fmtKm(st.distance||0) + ' km · ' + fmtDur(st.totalMs||0) + ' · ' + fmtD(r.t) + '</small></span></button>';
  }).join('');
}

let tdId = null, tdLine = null, lmap = null, lmapLayer = null;
function openTrack(id){ tdId = id; show('track'); loadTrack(); }
function loadTrack(){
  let d; try { d = JSON.parse(Android.trackDetail(tdId, 320, 213)); } catch(e){ return; }
  if (d.error) { toast(d.error); goBack(); return; }
  const st = d.stats || {};
  tdLine = d.line || [];
  $('#td-title').textContent = d.title || 'Route';
  $('#td-path').setAttribute('d', d.path || '');
  $('#td-dist').textContent = fmtKm(st.distance||0);
  $('#td-time').textContent = fmtDur(st.totalMs||0);
  $('#td-avg').textContent = st.avgSpeed != null ? kmh(st.avgSpeed) : '–';
  $('#td-max').textContent = st.maxSpeed != null ? kmh(st.maxSpeed) : '–';
  $('#td-gain').textContent = st.eleGain != null ? Math.round(st.eleGain) + ' m' : '–';
  $('#td-when').textContent = new Date(d.t).toLocaleDateString('nl-NL', {day:'numeric', month:'short'});
  drawEle(d.ele || []);
  drawMap(tdLine);
}
function drawMap(line){
  const hasMap = typeof L !== 'undefined';
  $('#td-map').style.display = hasMap ? 'block' : 'none';
  $('#td-shape').style.display = hasMap ? 'none' : 'block';
  if (!hasMap || !line || line.length < 1) return;
  try {
    if (!lmap) {
      lmap = L.map('td-map', { attributionControl: true, zoomControl: true });
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19, attribution: '© OpenStreetMap'
      }).addTo(lmap);
    }
    if (lmapLayer) { lmap.removeLayer(lmapLayer); lmapLayer = null; }
    const grp = L.featureGroup();
    if (line.length >= 2) L.polyline(line, { color: '#2F74CF', weight: 5, opacity: .9 }).addTo(grp);
    L.circleMarker(line[0], { radius: 7, color: '#fff', weight: 2, fillColor: '#1D8A4E', fillOpacity: 1 }).addTo(grp);
    L.circleMarker(line[line.length-1], { radius: 7, color: '#fff', weight: 2, fillColor: '#C0392B', fillOpacity: 1 }).addTo(grp);
    grp.addTo(lmap);
    lmapLayer = grp;
    // kaart heeft zijn uiteindelijke grootte pas na het tonen van het scherm
    setTimeout(() => { lmap.invalidateSize(); try { lmap.fitBounds(grp.getBounds(), { padding: [24, 24] }); } catch(e){ lmap.setView(line[0], 15); } }, 60);
  } catch(e) {
    $('#td-map').style.display = 'none';
    $('#td-shape').style.display = 'block';
  }
}
function tdMaps(){
  if (!tdLine || !tdLine.length) { toast('Geen locatie beschikbaar'); return; }
  const s = tdLine[0], e = tdLine[tdLine.length-1];
  // toon de route van start naar eind in Google Maps (wandelroute)
  const url = tdLine.length >= 2
    ? 'https://www.google.com/maps/dir/?api=1&origin=' + s[0] + ',' + s[1] + '&destination=' + e[0] + ',' + e[1] + '&travelmode=walking'
    : 'https://www.google.com/maps/search/?api=1&query=' + s[0] + ',' + s[1];
  Android.openUrl(url);
}
function drawEle(pts){
  const svg = $('#td-ele');
  if (pts.length < 2) { svg.innerHTML = '<text x=160 y=48 text-anchor=middle fill=#999 font-size=13>Geen hoogtegegevens</text>'; return; }
  const xs = pts.map(p=>p[0]), ys = pts.map(p=>p[1]);
  const mnx = Math.min(...xs), mxx = Math.max(...xs), mny = Math.min(...ys), mxy = Math.max(...ys);
  const sx = 320/((mxx-mnx)||1), sy = 70/((mxy-mny)||1);
  const d = pts.map((p,i)=>(i?'L':'M')+((p[0]-mnx)*sx).toFixed(1)+' '+(80-(p[1]-mny)*sy).toFixed(1)).join(' ');
  svg.innerHTML = '<path d="' + d + ' L320 90 L0 90 Z" fill="rgba(47,116,207,.18)" stroke="none"/><path d="' + d + '" fill=none stroke="var(--brand-2)" stroke-width=2/>';
}
function tdShare(){ const e = Android.trackShare(tdId); if (e) toast(e); }
function tdExport(){ const e = Android.trackExportBackup(tdId); toast(e ? e : 'Opgeslagen in backup-map ✓'); }
function tdRename(){ askInput('Naam wijzigen', '', $('#td-title').textContent, 'Opslaan', n => { const e = Android.trackRename(tdId, n.trim()); if (e) { toast(e); return; } loadTrack(); }); }
function tdDelete(){ askConfirm('Route verwijderen?', 'Dit kan niet ongedaan worden gemaakt.', 'Verwijderen', () => { const e = Android.trackDelete(tdId); if (e) { toast(e); return; } toast('Verwijderd'); goBack(); loadTrackList(); }); }

function refreshTrackTile(s){
  if (!s) s = tstat();
  const live = !!s.running;
  const tile = $('#tile-tracks'); if (tile) tile.classList.toggle('live', live);
  const sub = $('#tile-tracks-sub');
  if (sub) sub.textContent = live ? (s.paused ? 'Opname gepauzeerd' : 'Bezig: ' + fmtKm(s.distance||0) + ' km') : 'Leg je route vast met gps';
}
function enterTracksList(){ loadTrackList(); }

