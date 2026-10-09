/* ---------- Mijn auto: parkeerplek, parkeerlogboek, ritten ---------- */
let carSt = {}, carMap = null, carMarker = null, carMonth = '';
const carMonthOf = t => { const d = new Date(t); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); }; // lokale maand (zoals de export)
const carFmtKm = m => (m / 1000).toLocaleString('nl-NL', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
function carLoad(){ try { carSt = JSON.parse(Android.carState()) || {}; } catch(e){ carSt = {}; } }
function enterCar(){ carLoad(); carRender(); }
window.onCarChanged = function(r){ if (r === 'noloc') toast('Je locatie was niet te bepalen; plek bewaard zonder kaart'); else toast('📍 Plek bewaard'); if (current === 'car') enterCar(); };
window.onCarExport = function(r){ toast(r.startsWith('ok:') ? '✓ ' + r.slice(3) + ' ritten geëxporteerd naar Ritten/' : r); };
function carRender(){
  const p = carSt.park;
  const st = $('#car-park-state');
  if (p) {
    st.textContent = (p.how === 'hand' ? 'Bewaard op ' : 'Geparkeerd op ') + new Date(p.t).toLocaleString('nl-NL', { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) +
      (p.lat == null ? ' (zonder locatie)' : p.acc > 50 ? ' · ongeveer (± ' + p.acc + ' m)' : '');
  } else st.textContent = carSt.addr ? 'Nog geen plek bewaard. Zodra je de auto uitzet, verschijnt hier waar hij staat.' : 'Kies hieronder de Bluetooth van je auto, of tik op "Hier staat hij".';
  $('#car-nav').style.display = p && p.lat != null ? 'block' : 'none';
  $('#car-left').style.display = p ? 'block' : 'none';
  carShowMap(p);
  // ritten
  const trips = carSt.tripList || [];
  const months = [...new Set(trips.map(t => carMonthOf(t.start)))];
  if (!carMonth || !months.includes(carMonth)) carMonth = months[0] || carMonthOf(Date.now());
  $('#car-months').innerHTML = months.slice(0, 12).map(m => '<button class="chip' + (m === carMonth ? ' on' : '') + '" data-m="' + m + '" onclick="carMonth=this.dataset.m;carRender()">' +
    esc(new Date(m + '-15').toLocaleDateString('nl-NL', { month: 'short', year: 'numeric' })) + '</button>').join('');
  const inMonth = trips.filter(t => carMonthOf(t.start) === carMonth);
  const zak = inMonth.filter(t => t.type === 'zakelijk').reduce((a, t) => a + t.m, 0), pri = inMonth.filter(t => t.type !== 'zakelijk').reduce((a, t) => a + t.m, 0);
  $('#car-trip-state').textContent = carSt.tripActive ? '● Rit wordt opgenomen…' : inMonth.length ? inMonth.length + ' ritten · zakelijk ' + carFmtKm(zak) + ' km · privé ' + carFmtKm(pri) + ' km' :
    (carSt.trips ? 'Nog geen ritten deze maand.' : 'Zet "Ritten vanzelf opnemen" aan, of neem een rit met de hand op.');
  $('#car-trips').innerHTML = inMonth.map(t => '<button class="trip" data-id="' + esc(t.id) + '" onclick="carTripMenu(this.dataset.id)"><span><b>' +
    esc(new Date(t.start).toLocaleString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })) + '</b><small>' +
    carFmtKm(t.m) + ' km · ' + Math.round((t.end - t.start) / 60000) + ' min' + (t.note ? ' · ' + esc(t.note) : '') + '</small></span><span class="ttype' + (t.type === 'zakelijk' ? ' zak' : '') + '">' +
    (t.type === 'zakelijk' ? 'Zakelijk' : 'Privé') + '</span></button>').join('');
  $('#car-trip-btn').textContent = carSt.tripActive ? 'Rit stoppen en opslaan' : 'Rit nu opnemen';
  $('#car-export').disabled = !inMonth.length;
  // logboek
  const log = carSt.log || [];
  $('#car-log').innerHTML = log.length ? log.slice(0, 30).map(l => '<div class="clog"><span>' + esc(new Date(l.t).toLocaleString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })) +
    (l.end ? ' – ' + esc(new Date(l.end).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' })) : ' · staat er nog') + '</span>' +
    (l.lat != null ? '<button class="mini" data-u="geo:' + l.lat + ',' + l.lon + '?q=' + l.lat + ',' + l.lon + '" onclick="Android.openUrl(this.dataset.u)">Kaart</button>' : '') + '</div>').join('') :
    '<p class="note" style="margin:0">Nog niets geparkeerd.</p>';
  // instellingen
  let devs = []; try { const d = JSON.parse(Android.autoBt()); devs = d.devices || []; carSt.btErr = d.error; } catch(e){}
  const sel = $('#car-dev');
  sel.innerHTML = '<option value="">— Geen —</option>' + devs.sort((a, b) => (b.car ? 1 : 0) - (a.car ? 1 : 0)).map(d => '<option value="' + esc(d.a) + '"' + (d.a === carSt.addr ? ' selected' : '') + '>' + esc(d.n) + (d.car ? ' 🚗' : '') + '</option>').join('');
  if (carSt.addr && !devs.some(d => d.a === carSt.addr)) sel.insertAdjacentHTML('beforeend', '<option value="' + esc(carSt.addr) + '" selected>' + esc(carSt.name || carSt.addr) + '</option>');
  $('#car-trips-on').classList.toggle('on', !!carSt.trips);
  $('#car-def').value = carSt.defType || 'prive';
  const perm = $('#car-perm'); const need = [];
  if (carSt.btErr === 'perm' || !carSt.btOk) need.push(['Bluetooth-apparaten zien', "Android.autoPerm('bt')"]);
  if (!carSt.locOk) need.push(['Locatie', "Android.autoPerm('loc')"]);
  else if (!carSt.bgLoc && carSt.addr) need.push(['Locatie ook op de achtergrond (anders geen plek als de app dicht is)', "Android.autoPerm('bgloc')"]);
  if (carSt.trips && !carSt.battery) need.push(['Batterij-uitzondering (anders kan een rit niet vanzelf starten)', 'Android.batterySettings()']);
  perm.style.display = need.length ? 'block' : 'none';
  perm.innerHTML = need.map(n => '<div style="margin:4px 0">⚠️ ' + esc(n[0]) + ' · <a href="#" onclick="' + n[1] + ';return false">Toestaan</a></div>').join('');
}
function carShowMap(p){
  const el = $('#car-map');
  if (!p || p.lat == null || typeof L === 'undefined') { el.style.display = 'none'; return; }
  el.style.display = 'block';
  try {
    if (!carMap) {
      carMap = L.map('car-map', { attributionControl: true, zoomControl: true });
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(carMap);
      carMap.attributionControl.setPrefix(false);
    }
    const ll = [p.lat, p.lon];
    if (carMarker) carMarker.setLatLng(ll); else carMarker = L.marker(ll).addTo(carMap);
    carMap.setView(ll, 17); setTimeout(() => carMap.invalidateSize(), 50);
  } catch(e){ el.style.display = 'none'; }
}
function carNavigate(){ const p = carSt.park; if (!p || p.lat == null) return; Android.openUrl('https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=' + p.lat + ',' + p.lon); }
function carParkNow(){ toast('Locatie bepalen…'); Android.carParkNow(); }
function carLeft(){ Android.carLeft(); enterCar(); }
function carSave(){
  const sel = $('#car-dev'), opt = sel.options[sel.selectedIndex];
  Android.carSet(sel.value, opt && sel.value ? opt.textContent.replace(' 🚗', '') : '', $('#car-trips-on').classList.contains('on'), $('#car-def').value);
  enterCar();
}
function carTripToggle(){ if (carSt.tripActive) Android.carTripStop(); else Android.carTripStart(); setTimeout(enterCar, 600); }
function carExport(){ if (!plainOk('Het rittenoverzicht', carExport)) return; if (!carSt.dest) { toast('Kies eerst een backup-map (Instellingen)'); return; } Android.carExport(carMonth); }
function carTripMenu(id){
  const t = (carSt.tripList || []).find(x => x.id === id); if (!t) return;
  openSheet(new Date(t.start).toLocaleString('nl-NL', { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }), carFmtKm(t.m) + ' km', [
    [t.type === 'zakelijk' ? 'Maak privé' : 'Maak zakelijk', () => { Android.carTripSet(id, t.type === 'zakelijk' ? 'prive' : 'zakelijk', null); enterCar(); }],
    ['Omschrijving…', () => askInput('Omschrijving', 'Bijv. klant of reden van de rit.', t.note || '', 'Opslaan', v => { Android.carTripSet(id, null, v.trim()); enterCar(); })],
    ['Route bekijken', () => { if (t.route && typeof openTrack === 'function') jumpTo('track', () => openTrack(t.route)); else toast('Route niet gevonden'); }],
    ['Rit verwijderen', () => askConfirm('Rit verwijderen?', 'De route zelf blijft in Mijn routes.', 'Verwijderen', () => { Android.carTripDelete(id); enterCar(); })]]);
}
