/* ---------- Toiletten en douches ----------
 * Bronnen downloaden en bijwerken (Toiletten*.java), dichtbij zoeken op de telefoon, de eigen laag (aanpassen, afwijzen,
 * toevoegen) en doorgeven aan OpenStreetMap. Je plek gaat nooit naar een bron: de app downloadt elke bron in zijn geheel. */
const WC_SCR = ['wc', 'wcplek', 'wcbewerk', 'wcdeel', 'wcset', 'wcbronnen', 'wcbron', 'wceigen', 'wcgedeeld'];
const WC_B = { toilet: 1, douche: 2, meestal: 4, gratis: 8, betaald: 16, rolstoel: 32, rolstoelBeperkt: 64, verschoon: 128, klanten: 256, urinoir: 512, altijd: 1024 };
const WC_VELDEN = ['id', 'naam', 'uren', 'lat', 'lon', 'rolstoel', 'verschoon', 'gratis', 'prijs', 'douche'];
const WC_ZET = ['klanten', 'rolstoel', 'urinoir', 'gratis', 'betaald', 'verschoon', 'douche', 'overslaan'];
let wcSt = null, wcPunt = null, wcRes = [], wcMap = null, wcLaag = null;
let wcK = '', wcP = null, wcBew = null, wcDeel = null, wcBronId = '', wcSj = '', wcTip = '';
let wcHereWait = false, wcPermWait = false, wcPermAsked = false, wcSetupFout = '';
let wcF = Object.assign({ toilet: false, douche: false, gratis: false, rolstoel: false, meestal: false }, store.get('wcFilter', {}));

function wcState(){ wcSt = rdJson(Android.wcState(), {}) || {}; return wcSt; }
function wcFout(r){ const o = r ? rdJson(r, null) : null; return o && o.error ? o.error : ''; }

function wcSoortNaam(s){
  switch (s) {
    case 'toilet': return t('Openbaar toilet');
    case 'douche': return t('Douche');
    case 'tank': return t('Tankstation');
    case 'verzorg': return t('Verzorgingsplaats');
    case 'rust': return t('Parkeerplaats langs de weg');
    case 'camping': return t('Camping');
    case 'haven': return t('Jachthaven');
    case 'station': return t('Station');
    case 'horeca': return t('Restaurant of café');
    case 'winkel': return t('Winkelcentrum');
    case 'biblio': return t('Bibliotheek');
    case 'zorg': return t('Ziekenhuis');
    case 'sport': return t('Sportplek of zwembad');
    case 'strand': return t('Strand');
    default: return t('Anders');
  }
}
/** Korte eigenschappen voor in de lijst. */
function wcKenmerken(f){
  const l = [];
  if (f & WC_B.meestal) l.push(t('meestal een toilet'));
  if (f & WC_B.urinoir) l.push(t('urinoir'));
  if ((f & WC_B.douche) && (f & WC_B.toilet)) l.push(t('ook douche'));
  if (f & WC_B.gratis) l.push(t('gratis')); else if (f & WC_B.betaald) l.push(t('betaald'));
  if (f & WC_B.rolstoel) l.push(t('rolstoel')); else if (f & WC_B.rolstoelBeperkt) l.push(t('rolstoel beperkt'));
  if (f & WC_B.verschoon) l.push(t('verschoontafel'));
  if (f & WC_B.klanten) l.push(t('voor klanten'));
  if (f & WC_B.altijd) l.push(t('altijd open'));
  return l;
}
/** Eigenschappen voluit, voor de plek zelf. */
function wcKenmerkenLang(f){
  const l = [];
  if (f & WC_B.urinoir) l.push(t('Alleen een urinoir')); else if (f & WC_B.toilet) l.push(t('Toilet'));
  if (f & WC_B.douche) l.push(t('Douche'));
  if (f & WC_B.gratis) l.push(t('Gratis')); else if (f & WC_B.betaald) l.push(t('Betaald'));
  if (f & WC_B.rolstoel) l.push(t('Rolstoeltoegankelijk')); else if (f & WC_B.rolstoelBeperkt) l.push(t('Beperkt rolstoeltoegankelijk'));
  if (f & WC_B.verschoon) l.push(t('Verschoontafel'));
  if (f & WC_B.klanten) l.push(t('Alleen voor klanten of gasten'));
  if (f & WC_B.altijd) l.push(t('Altijd open'));
  return l;
}
function wcReden(r){
  switch (r) {
    case 'weg': return t('Bestaat niet meer');
    case 'dicht': return t('Gesloten');
    case 'prive': return t('Niet openbaar');
    case 'klanten': return t('Alleen voor klanten');
    default: return t('Iets anders');
  }
}
function wcAfstand(d){ return d == null || d < 0 ? '' : d < 950 ? fmtMeters(Math.max(10, Math.round(d / 10) * 10)) : fmtDist(d, d < 10000 ? 1 : 0); }
function wcMeters(a, b){ return rgMeters({ lat: a.lat, lon: a.lon }, { lat: b.la, lon: b.lo }); }
function wcBronNaam(id){ const b = ((wcSt || {}).bronnen || []).find(x => x.id === id); return b ? b.naam : id; }

/* ================= Toiletten en douches (s-wc) ================= */
function enterWc(name, vorige){
  wcState();
  if (name === 'wc') { if (!WC_SCR.includes(vorige)) wcPermAsked = false; wcRender(); setTimeout(wcKaartInit, 60); }
  else if (name === 'wcplek') wcpRender();
  else if (name === 'wcset') wcsRender();
  else if (name === 'wcbronnen') wcbrRender();
  else if (name === 'wcbron') wcnStand();
  else if (name === 'wceigen') wceRender();
  else if (name === 'wcgedeeld') wcgRender();
  wcBezigVolg();
}
function wcResume(){
  if (!WC_SCR.includes(current)) return;
  wcState();
  if (current === 'wc') wcRender(); else if (current === 'wcplek') wcpRender(); else if (current === 'wcset') wcsRender();
  else if (current === 'wcbronnen') wcbrRender(); else if (current === 'wcbron') wcnStand(); else if (current === 'wceigen') wceRender(); else if (current === 'wcgedeeld') wcgRender();
  wcBezigVolg();
}
/** Een scan van de achtergrondtaak meldt zich niet bij het scherm: zolang er een loopt, af en toe kijken of hij klaar is. */
let wcBezigT = 0;
function wcBezigVolg(){
  if (wcBezigT || !(wcSt && wcSt.bezig)) return;
  wcBezigT = setTimeout(() => {
    wcBezigT = 0;
    if (!WC_SCR.includes(current)) return;
    wcState();
    if (wcSt.bezig) wcBezigVolg(); else wcResume();
  }, 3000);
}

function wcRender(){
  const st = wcSt || {}, klaar = !!st.gegevens;
  uiSetup('#wc-setup', klaar);
  $('#wc-ophalen').disabled = !!st.bezig;
  $('#wc-setup-st').textContent = st.bezig ? t('Bezig met ophalen…') : wcSetupFout;
  $('#wc-status').hidden = !klaar;
  $('#wc-chips').hidden = !klaar;
  $('#wc-chips').querySelectorAll('button').forEach(b => { const on = !!wcF[b.dataset.f]; b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); });
  const mis = (st.bronnen || []).filter(b => b.aan !== false && b.toestand && b.toestand.fout && (b.toestand.lastTry || 0) >= (b.toestand.lastOk || 0));
  $('#wc-sub').textContent = !klaar ? '' : mis.length ? t('Bijwerken lukte de laatste keer niet; je ziet de vorige versie (zie Instellingen → Bronnen).')
    : st.bezig ? t('Bezig met bijwerken…') : st.laatste ? t('Bijgewerkt {wanneer}', { wanneer: faMoment(st.laatste) }) : '';
  const bs = (st.bronnen || []).filter(b => b.aan !== false && b.toestand && b.toestand.lastOk);
  $('#wc-voet').textContent = klaar && bs.length ? t('Gegevens: {lijst}.', { lijst: bs.map(b => b.licentie).filter((x, i, a) => a.indexOf(x) === i).join('; ') }) : '';
  $('#wc-voet').setAttribute('translate', 'no');
  wcKaartInit();
  wcZoek(false);
}
function wcFilter(f){ wcF[f] = !wcF[f]; store.set('wcFilter', wcF); Android.vibrate(10); wcRender(); }
function wcOphalen(){ wcSetupFout = ''; wcScanStart(''); }

function wcHier(){
  if (!wcSt || !wcSt.gegevens) { toast(t('Haal eerst de gegevens op')); return; }
  wcHereWait = true; $('#wc-hier').disabled = true; $('#wc-hier-t').textContent = t('Locatie bepalen…');
  Android.autoHere();
}
setTimeout(function(){ // na het laden van alle scripts (Automatiseren definieert onAutoHere eerder)
  const prev = window.onAutoHere;
  window.onAutoHere = function(r){
    if (!wcHereWait) { if (prev) prev(r); return; }
    wcHereWait = false; $('#wc-hier').disabled = false; $('#wc-hier-t').textContent = t('Zoek bij mij');
    if (current !== 'wc') return;
    if (!r || r.error) {
      if (r && r.error === 'perm' && !wcPermAsked) { wcPermAsked = true; wcPermWait = true; Android.autoPerm('loc'); return; }
      toast(r && r.error === 'perm' ? t('Zonder toestemming voor je locatie: tik op de kaart om daar te zoeken.') : r && r.error ? r.error : t('Geen locatie gevonden'));
      return;
    }
    wcPunt = { lat: r.lat, lon: r.lng, acc: r.acc || 0, bron: 'gps' };
    wcZoek(true);
  };
  const prevA = window.onAutoChanged;
  window.onAutoChanged = function(){ if (prevA) prevA.apply(this, arguments); if (wcPermWait && current === 'wc') { wcPermWait = false; wcHier(); } };
}, 0);

function wcZoek(fly){
  if (!wcSt || !wcSt.gegevens) { $('#wc-list').hidden = true; return; }
  $('#wc-list').hidden = false;
  if (!wcPunt) { wcRes = []; uiHtml($('#wc-list'), uiEmpty('pin', t('Tik op Zoek bij mij of op de kaart om plekken in de buurt te zien.'))); wcKaartTeken(false); return; }
  const r = rdJson(Android.wcNear(wcPunt.lat, wcPunt.lon, JSON.stringify(Object.assign({ max: 25 }, wcF))), {}) || {};
  if (r.error) { toast(r.error); return; }
  wcRes = r.plekken || [];
  const filter = Object.keys(wcF).some(k => k !== 'meestal' && wcF[k]);
  uiHtml($('#wc-list'), wcRes.length ? wcRes.map(wcRij).join('') : uiEmpty('toilet', filter ? t('Niets gevonden met dit filter binnen 50 km.') : t('Niets gevonden binnen 50 km.')));
  wcKaartTeken(fly);
}
function wcRij(p){
  const sub = (p.n ? [wcSoortNaam(p.s)] : []).concat(wcKenmerken(p.f));
  if (p.e === 'toegevoegd') sub.push(t('jouw plek')); else if (p.e === 'aangepast') sub.push(t('door jou aangepast'));
  const naam = p.n ? '<b translate="no">' + esc(p.n) + '</b>' : '<b>' + esc(wcSoortNaam(p.s)) + '</b>';
  return '<div class="srow"><button class="sbtn" onclick="wcOpen(' + jsq(p.k) + ')">' + uiIcon(p.f & WC_B.toilet ? 'toilet' : 'shower') +
    '<span class="stxt">' + naam + '<small>' + esc(sub.join(' · ')) + '</small></span><span class="wc-d">' + esc(wcAfstand(p.d)) + '</span></button></div>';
}

/* De kaart: tikken = daar zoeken, lang indrukken = plek toevoegen. Alleen als Kaart tonen aan staat. */
function wcKaartAan(){ return store.get('wcKaart', true) !== false; }
function wcKaartInit(){
  const aan = wcKaartAan() && !!(wcSt && wcSt.gegevens) && typeof L !== 'undefined';
  $('#wc-kaartwrap').hidden = !aan;
  if (!aan || current !== 'wc') return;
  if (!wcMap) {
    wcMap = L.map('wc-map', { attributionControl: true, zoomControl: true });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(wcMap);
    wcLaag = L.layerGroup().addTo(wcMap);
    wcMap.on('click', e => { wcPunt = { lat: e.latlng.lat, lon: e.latlng.lng, bron: 'kaart' }; wcZoek(false); });
    wcMap.on('contextmenu', e => wcNieuw(e.latlng.lat, e.latlng.lng));
    const dp = wcPunt ? { lat: wcPunt.lat, lon: wcPunt.lon } : rgPlace() || rgPlace('home');
    wcMap.setView(dp ? [dp.lat, dp.lon] : [51.92, 4.48], wcPunt ? 15 : 12); // standaardplaats (Regio en taal), anders Rotterdam
  }
  wcMap.invalidateSize();
  wcKaartTeken(false);
}
function wcKaartTeken(fly){
  if (!wcMap || !wcLaag) return;
  wcLaag.clearLayers();
  for (const p of wcRes) {
    const kleur = p.f & WC_B.meestal ? '#8D6E63' : p.f & WC_B.toilet ? '#1E6FD9' : '#00897B';
    L.circleMarker([p.la, p.lo], { radius: 9, color: '#fff', weight: 2, fillColor: kleur, fillOpacity: 1, bubblingMouseEvents: false }).on('click', () => wcOpen(p.k)).addTo(wcLaag);
  }
  if (wcPunt) L.circleMarker([wcPunt.lat, wcPunt.lon], { radius: 7, color: '#fff', weight: 3, fillColor: '#B03A2E', fillOpacity: 1, interactive: false }).addTo(wcLaag);
  if (fly && wcPunt) wcMap.fitBounds([[wcPunt.lat, wcPunt.lon]].concat(wcRes.slice(0, 5).map(p => [p.la, p.lo])), { padding: [28, 28], maxZoom: 17 });
}

function wcMenu(){
  openSheet(t('Toiletten en douches'), '', [
    [t('Plek toevoegen'), () => wcToevoegen(), 'plus'],
    [t('Nu bijwerken'), () => wcScanAlles(), 'refresh'],
    [t('Mijn wijzigingen'), () => wcNaarEigen('wc'), 'edit']]);
}
function wcNaarEigen(van){ BACK.wceigen = van; show('wceigen'); }
function wcToevoegen(){
  if (!wcPunt) { toast(t('Zoek eerst bij mij of tik op de kaart: daar komt de nieuwe plek.')); return; }
  wcNieuw(wcPunt.lat, wcPunt.lon);
}

/* ================= bijwerken ================= */
function wcScanAlles(){ wcScanStart(''); }
function wcScanStart(id){
  const r = Android.wcScan(id || '');
  if (r) { const f = wcFout(r) || r; if (current === 'wc') wcSetupFout = f; toast(f); return; }
  if (wcSt) wcSt.bezig = true;
  wcResume();
}
window.onWcScan = function(r){
  r = typeof r === 'string' ? rdJson(r, {}) : r || {};
  wcState();
  const res = r.resultaat || [], mis = res.filter(x => !x.ok);
  if (r.error) { wcSetupFout = r.error; toast(r.error); }
  else if (r.bezig) toast(t('Er loopt al een scan'));
  else if (mis.length) { wcSetupFout = t('Ophalen lukte niet: {fout}', { fout: mis[0].fout }); toast(wcSetupFout); }
  else if (res.length) { wcSetupFout = ''; const n = res.reduce((a, x) => a + (x.aantal || 0), 0); toast(tn(n, 'Bijgewerkt: één plek', 'Bijgewerkt: {n} plekken')); }
  wcResume();
};

/* ================= één plek (s-wcplek) ================= */
function wcOpen(k){
  wcK = k;
  if (!['wcplek', 'wcbewerk', 'wcdeel'].includes(current)) BACK.wcplek = current;
  show('wcplek');
}
function wcpRender(){
  const p = wcP = rdJson(Android.wcPlek(wcK), {}) || {}, el = $('#wcp-body');
  if (p.error) { $('#wcp-h1').textContent = t('Plek'); uiHtml(el, uiEmpty('toilet', p.error)); return; }
  $('#wcp-h1').textContent = wcSoortNaam(p.s);
  const d = wcPunt ? wcMeters(wcPunt, p) : -1, kenm = wcKenmerkenLang(p.f);
  let h = '<div class="card">' + (p.n ? '<h2 translate="no" style="margin-top:0">' + esc(p.n) + '</h2>' : '');
  if (d >= 0) h += '<p class="note" style="margin-top:0">' + esc(t('{afstand} hiervandaan', { afstand: wcAfstand(d) })) + '</p>';
  if (kenm.length) h += '<ul class="wc-kenm">' + kenm.map(x => '<li>' + esc(x) + '</li>').join('') + '</ul>';
  if (p.f & WC_B.meestal) h += '<p class="hint">' + esc(t('Hier is meestal een toilet, maar dat staat niet zeker in de gegevens. Vaak alleen voor klanten.')) + '</p>';
  if (p.u) h += '<div class="flabel">' + esc(t('Openingstijden')) + '</div><p class="wc-waarde" translate="no">' + esc(p.u) + '</p>';
  if (p.notitie) h += '<div class="flabel">' + esc(t('Jouw notitie')) + '</div><p class="wc-waarde" translate="no">' + esc(p.notitie) + '</p>';
  h += '<button class="big" onclick="wcRoute()"><span class="ui-icon" data-icon="route" aria-hidden="true"></span>' + esc(t('Route lopend')) + '</button>';
  h += '<div class="row" style="gap:8px;margin-top:8px;flex-wrap:wrap"><button class="big ghost" style="flex:1 1 140px" onclick="wcGeo()">' + esc(t('In kaart-app')) + '</button>' +
    (p.osm ? '<button class="big ghost" style="flex:1 1 140px" onclick="wcOsm()">' + esc(t('In OpenStreetMap')) + '</button>' : '') + '</div></div>';
  h += wcpEigen(p);
  const bron = (p.bronnen || []).map(b => '<li translate="no">' + esc(b.naam || b.id) + (b.licentie ? ' <small>(' + esc(b.licentie) + ')</small>' : '') + '</li>').join('');
  h += '<div class="card"><div class="flabel">' + esc(tc('bron', 'Uit')) + '</div>' + (bron ? '<ul class="wc-bronnen">' + bron + '</ul>' : '<p class="note" style="margin:0">' + esc(t('Door jou toegevoegd')) + '</p>') + '</div>';
  if ((p.gedeeld || []).length) h += '<div class="card"><div class="flabel">' + esc(t('Doorgegeven aan OpenStreetMap')) + '</div>' + p.gedeeld.map(wcgRij).join('') + '</div>';
  uiHtml(el, h);
}
/** Wat jij met deze plek deed, met de keuzes die erbij horen. */
function wcpEigen(p){
  const k = jsq(p.k);
  let h = '';
  if (p.af) {
    const a = p.afwijzing || {};
    h += '<p><b>' + esc(t('Door jou afgewezen: {reden}', { reden: wcReden(a.reden) })) + '</b></p>' + (a.tekst ? '<p class="wc-waarde" translate="no">' + esc(a.tekst) + '</p>' : '') +
      '<p class="note">' + esc(t('Hij staat niet meer in je lijst.')) + '</p><button class="big tonal" onclick="wcTerugDo(' + k + ', \'afgewezen\')">' + esc(t('Terugzetten')) + '</button>';
  }
  if (p.e === 'aangepast') {
    h += '<p><b>' + esc(t('Door jou aangepast')) + '</b></p>';
    if (p.weg) h += '<p class="note">' + esc(t('Deze plek staat niet meer in de bron. Je aanpassing blijft bewaard.')) + '</p>';
    if (p.bronNu) {
      const b = p.bronNu, wat = [b.n ? b.n : wcSoortNaam(b.s)].concat(wcKenmerken(b.f)).join(' · ');
      h += '<p class="note">' + esc(t('De bron zegt nu iets anders:')) + '</p><p class="wc-waarde" translate="no">' + esc(wat) + (b.u ? '<br>' + esc(b.u) : '') + '</p>' +
        '<div class="row" style="gap:8px"><button class="big tonal" style="flex:1" onclick="wcTerugDo(' + k + ', \'wijzig\')">' + esc(t('Overnemen')) + '</button>' +
        '<button class="big ghost" style="flex:1" onclick="wcHouden()">' + esc(t('Mijn versie houden')) + '</button></div>';
    }
  }
  if (p.e === 'toegevoegd') {
    h += '<p><b>' + esc(t('Jouw eigen plek')) + '</b></p>';
    if (p.ib) h += '<p class="note">' + esc(t('Deze plek staat nu ook in een bron. Je eigen plek is dan niet meer nodig.')) + '</p><button class="big tonal" onclick="wcTerugDo(' + k + ', \'toegevoegd\')">' + esc(t('Eigen plek weghalen')) + '</button>';
  }
  return h ? '<div class="card wc-eigen">' + h + '</div>' : '';
}
function wcRoute(){ const p = wcP; if (p && !p.error) Android.openUrl('https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=' + p.la + ',' + p.lo); }
function wcGeo(){ const p = wcP; if (p && !p.error) Android.openUrl('geo:' + p.la + ',' + p.lo + '?q=' + p.la + ',' + p.lo + '(' + encodeURIComponent(p.n || wcSoortNaam(p.s)) + ')'); }
function wcOsm(){ const p = wcP; if (p && p.osm) Android.openUrl(p.osm); }
/** Wat je van deze plek kunt doorgeven; leeg als dat er niet is of al open staat bij OpenStreetMap. */
function wcDeelWat(p){
  const w = p.af ? (p.osm ? 'afwijzing' : '') : p.e === 'aangepast' && !p.weg ? 'wijziging' : p.e === 'toegevoegd' && !p.ib ? 'toevoeging' : '';
  const als = w === 'wijziging' && !p.osm ? 'toevoeging' : w; // een aanpassing van iets wat niet in OpenStreetMap staat, gaat als nieuwe plek
  return w && !(p.gedeeld || []).some(g => g.wat === als && g.status === 'open') ? w : '';
}
function wcpMenu(){
  const p = wcP; if (!p || p.error) return;
  const k = p.k, acts = [[t('Aanpassen'), () => wcBewerk(k), 'edit']];
  if (p.e === 'toegevoegd') acts.push([t('Eigen plek weghalen'), () => wcTerugDo(k, 'toegevoegd'), 'trash']);
  else if (p.af) acts.push([t('Terugzetten'), () => wcTerugDo(k, 'afgewezen'), 'refresh']);
  else acts.push([t('Klopt niet: afwijzen'), () => wcAfwijsKies(k), 'close']);
  if (p.e === 'aangepast') acts.push([t('Mijn aanpassing vergeten'), () => wcTerugDo(k, 'wijzig'), 'refresh']);
  const wat = wcDeelWat(p);
  if (wat) acts.push([t('Doorgeven aan OpenStreetMap'), () => wcDeelOpen(k, wat), 'globe']);
  openSheet(t('Deze plek'), '', acts);
}
function wcNaWijziging(){ wcState(); wcResume(); }
function wcTerugDo(k, wat){
  const doe = () => {
    const f = wcFout(Android.wcTerug(k, wat));
    if (f) { toast(f); return; }
    toast(wat === 'toegevoegd' ? t('Eigen plek weggehaald') : wat === 'afgewezen' ? t('Teruggezet: de plek staat weer in je lijst') : t('Aanpassing vergeten: de bron geldt weer'));
    if (wat === 'toegevoegd' && current === 'wcplek') { wcState(); goBack(); return; }
    wcNaWijziging();
  };
  if (wat === 'toegevoegd') askConfirm(t('Eigen plek weghalen?'), t('Hij staat dan niet meer in je lijst.'), t('Weghalen'), doe); else doe();
}
function wcHouden(){
  const p = wcP; if (!p || p.error) return;
  const r = rdJson(Android.wcBewaar(JSON.stringify({ k: p.k, s: p.s, f: p.f, n: p.n, u: p.u, notitie: p.notitie || '', zeker: !(p.f & WC_B.meestal), houden: true })), {}) || {};
  if (r.error) { toast(r.error); return; }
  toast(t('Je versie blijft staan'));
  wcNaWijziging();
}
function wcAfwijsKies(k){
  openSheet(t('Waarom klopt het niet?'), '', ['weg', 'dicht', 'prive', 'klanten', 'anders'].map(r => [wcReden(r), () => wcAfwijs(k, r)]));
}
function wcAfwijs(k, reden){
  askInput(t('Toelichting'), t('Mag leeg. Blijft op je telefoon, tenzij je het later doorgeeft aan OpenStreetMap.'), '', t('Afwijzen'), v => {
    const f = wcFout(Android.wcAfwijzen(k, reden, String(v || '').trim()));
    if (f) { toast(f); return; }
    undoToast(t('Afgewezen: de plek staat niet meer in je lijst'), () => { Android.wcTerug(k, 'afgewezen'); wcNaWijziging(); });
    wcNaWijziging();
  });
}

/* ================= aanpassen of toevoegen (s-wcbewerk) ================= */
function wcBewerk(k){
  const p = wcP && wcP.k === k ? wcP : rdJson(Android.wcPlek(k), {}) || {};
  if (p.error) { toast(p.error); return; }
  wcBewerkOpen(p, p.la, p.lo);
}
function wcNieuw(la, lo){ wcBewerkOpen(null, Math.round(la * 1e6) / 1e6, Math.round(lo * 1e6) / 1e6); }
function wcBewerkOpen(p, la, lo){
  const f = p ? p.f : WC_B.toilet, nieuw = !p || p.e === 'toegevoegd';
  wcBew = { k: p ? p.k : '', la, lo, f0: f };
  $('#wcb-h1').textContent = p ? t('Plek aanpassen') : t('Plek toevoegen');
  $('#wcb-uitleg').textContent = nieuw ? t('Je eigen plek blijft op je telefoon. Wil je hem met iedereen delen, geef hem dan daarna door aan OpenStreetMap.')
    : t('Je aanpassing blijft op je telefoon en gaat voor op wat de bron zegt, ook na het bijwerken.');
  $('#wcb-s').value = p ? p.s : 'toilet';
  $('#wcb-n').value = p ? p.n || '' : '';
  $('#wcb-u').value = p ? p.u || '' : '';
  $('#wcb-notitie').value = p ? p.notitie || '' : '';
  $('#wcb-toilet').checked = !!(f & WC_B.toilet) && !(f & WC_B.urinoir);
  $('#wcb-urinoir').checked = !!(f & WC_B.urinoir);
  $('#wcb-douche').checked = !!(f & WC_B.douche);
  $('#wcb-rolstoel').checked = !!(f & WC_B.rolstoel);
  $('#wcb-verschoon').checked = !!(f & WC_B.verschoon);
  $('#wcb-altijd').checked = !!(f & WC_B.altijd);
  $('#wcb-klanten').checked = !!(f & WC_B.klanten);
  const bronF = p && p.uitBron ? p.uitBron.f : f;
  $('#wcb-zeker-r').hidden = nieuw || !(bronF & WC_B.meestal);
  $('#wcb-zeker').checked = !nieuw && !!(bronF & WC_B.meestal) && !(f & WC_B.meestal);
  $('#wcb-geld').value = f & WC_B.gratis ? 'gratis' : f & WC_B.betaald ? 'betaald' : '';
  $('#wcb-plek').textContent = t('Plek: {coord}', { coord: lcFmt(la, lo) });
  if (current !== 'wcbewerk') BACK.wcbewerk = current;
  show('wcbewerk');
}
function wcbBewaar(){
  const c = id => $('#wcb-' + id).checked;
  let f = 0;
  if (c('toilet')) f |= WC_B.toilet;
  if (c('urinoir')) f |= WC_B.toilet | WC_B.urinoir;
  if (c('douche')) f |= WC_B.douche;
  if (c('rolstoel')) f |= WC_B.rolstoel; else f |= wcBew.f0 & WC_B.rolstoelBeperkt;
  if (c('verschoon')) f |= WC_B.verschoon;
  if (c('altijd')) f |= WC_B.altijd;
  if (c('klanten')) f |= WC_B.klanten;
  const geld = $('#wcb-geld').value;
  if (geld === 'gratis') f |= WC_B.gratis; else if (geld === 'betaald') f |= WC_B.betaald;
  if (!(f & (WC_B.toilet | WC_B.douche))) { toast(t('Vink toilet, urinoir of douche aan')); return; }
  const o = { k: wcBew.k, s: $('#wcb-s').value, f, n: $('#wcb-n').value.trim(), u: $('#wcb-u').value.trim(), notitie: $('#wcb-notitie').value.trim(), zeker: $('#wcb-zeker').checked };
  if (!wcBew.k) { o.la = wcBew.la; o.lo = wcBew.lo; }
  const r = rdJson(Android.wcBewaar(JSON.stringify(o)), {}) || {};
  if (r.error) { toast(r.error); return; }
  wcState();
  if (!wcBew.k) {
    toast(t('Plek toegevoegd'));
    wcK = r.k; BACK.wcplek = BACK.wcbewerk === 'wcplek' ? BACK.wcplek : BACK.wcbewerk || 'wc';
    show('wcplek');
    return;
  }
  toast(t('Aanpassing bewaard'));
  goBack();
}

/* ================= doorgeven aan OpenStreetMap (s-wcdeel) ================= */
function wcDeelOpen(k, wat){
  const n = rdJson(Android.wcNotitie(k, wat, ''), {}) || {};
  if (n.error) { toast(n.error); return; }
  wcDeel = { k, wat };
  $('#wcd-opm').value = n.voorstel || '';
  if (!wcdVoorbeeld()) return;
  show('wcdeel');
}
/** De tekst opnieuw opbouwen met de opmerking zoals hij nu in het veld staat; false = lukt niet (melding getoond). */
function wcdVoorbeeld(){
  if (!wcDeel) return false;
  const n = rdJson(Android.wcNotitie(wcDeel.k, wcDeel.wat, $('#wcd-opm').value.trim()), {}) || {};
  if (n.error) { toast(n.error); return false; }
  Object.assign(wcDeel, { tekst: n.tekst, la: n.la, lo: n.lo });
  $('#wcd-tekst').textContent = n.tekst;
  $('#wcd-plek').textContent = t('Op de kaart: {coord}', { coord: lcFmt(n.la, n.lo) });
  $('#wcd-leeg').hidden = !n.leeg;
  $('#wcd-ok').disabled = !!n.leeg;
  return true;
}
function wcdDoorgeven(){
  if (!wcDeel) return;
  $('#wcd-ok').disabled = true;
  const f = wcFout(Android.wcDoorgeven(wcDeel.k, wcDeel.wat, $('#wcd-opm').value.trim(), wcDeel.tekst));
  if (f) { $('#wcd-ok').disabled = false; toast(f); wcdVoorbeeld(); }
}
window.onWcGedeeld = function(r){
  r = typeof r === 'string' ? rdJson(r, {}) : r || {};
  $('#wcd-ok').disabled = false;
  if (r.error) { toast(r.error); return; }
  if (r.zone) {
    toast(t('Hier kan het alleen via de website: de tekst staat op het klembord, plak hem in de notitie.'));
    if (r.url) Android.openUrl(r.url);
  } else toast(t('Doorgegeven aan OpenStreetMap. Tools kijkt bij het bijwerken of het is opgelost.'));
  wcState();
  if (current === 'wcdeel') goBack(); else wcResume();
};

/* ================= instellingen (s-wcset) ================= */
function wcsRender(){
  const st = wcSt || {}, aan = (st.bronnen || []).filter(b => b.aan !== false), e = st.eigen || {}, g = st.gedeeld || {};
  $('#wcs-bronnen').textContent = !st.ingesteld ? t('Nog niet opgehaald') : tn(aan.length, 'Eén bron aan', '{n} bronnen aan') + (st.laatste ? ' · ' + t('bijgewerkt {wanneer}', { wanneer: faMoment(st.laatste) }) : '');
  const n = (e.aangepast || 0) + (e.afgewezen || 0) + (e.toegevoegd || 0);
  $('#wcs-eigen').textContent = e.leesbaar === false ? t('Niet leesbaar: er wordt niets overschreven') : !n ? t('Nog niets') :
    [e.aangepast ? tn(e.aangepast, 'één aangepast', '{n} aangepast') : '', e.afgewezen ? tn(e.afgewezen, 'één afgewezen', '{n} afgewezen') : '', e.toegevoegd ? tn(e.toegevoegd, 'één toegevoegd', '{n} toegevoegd') : ''].filter(Boolean).join(' · ');
  $('#wcs-gedeeld').textContent = !g.aantal ? t('Nog niets doorgegeven') : tn(g.aantal, 'Eén notitie', '{n} notities') + (g.open ? ' · ' + tn(g.open, 'één open', '{n} open') : '');
  const k = $('#wcs-kaart'), on = wcKaartAan();
  k.classList.toggle('on', on); k.setAttribute('aria-checked', on ? 'true' : 'false');
}
function wcsKaart(b){ togUi(b); store.set('wcKaart', b.classList.contains('on')); }

/* ================= bronnen (s-wcbronnen, s-wcbron) ================= */
function wcBronStand(b){
  const s = b.toestand || {};
  if (b.aan === false) return t('Staat uit');
  if (s.fout && (s.lastTry || 0) >= (s.lastOk || 0)) return t('Bijwerken lukte niet: {fout}', { fout: s.fout });
  if (!s.lastOk) return t('Nog niet opgehaald');
  return tn(s.aantal || 0, 'Eén plek', '{n} plekken') + ' · ' + t('bijgewerkt {wanneer}', { wanneer: faMoment(s.lastOk) });
}
function wcbrRender(){
  const st = wcSt || {}, bs = st.bronnen || [];
  uiHtml($('#wcbr-list'), bs.length ? bs.map(b => '<div class="srow"><button class="sbtn" onclick="wcBronOpen(' + jsq(b.id) + ')">' + uiIcon(b.aan === false ? 'pause' : 'download') +
    '<span class="stxt"><b translate="no">' + esc(b.naam) + '</b><small>' + esc(wcBronStand(b)) + '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button></div>').join('')
    : uiEmpty('download', t('Geen bronnen: voeg er een toe.')));
  $('#wcbr-scan').disabled = !!st.bezig || !bs.length;
  $('#wcbr-st').textContent = st.bezig ? t('Bezig met bijwerken…') : '';
}
function wcBronOpen(id){
  const b = ((wcSt || {}).bronnen || []).find(x => x.id === id);
  if (!b) { toast(t('Deze bron bestaat niet meer')); return; }
  wcBronId = id; wcSj = b.sjabloon || 'eigen'; wcTip = b.tip || '';
  $('#wcn-sjabloonkaart').hidden = true;
  $('#wcn-menu').hidden = false;
  wcnVul(b);
  show('wcbron');
}
function wcBronNieuw(){
  const st = wcSt || {}, sj = st.sjablonen || [], ids = (st.bronnen || []).map(b => b.sjabloon);
  wcBronId = '';
  const sel = $('#wcn-sjabloon');
  sel.innerHTML = '';
  sj.forEach(s => { const o = document.createElement('option'); o.value = s.sjabloon; o.textContent = s.naam + (ids.includes(s.sjabloon) ? ' ' + t('(staat er al)') : ''); o.setAttribute('translate', 'no'); sel.appendChild(o); });
  const eigen = document.createElement('option'); eigen.value = ''; eigen.textContent = t('Zelf invullen'); sel.appendChild(eigen);
  const eerste = sj.find(s => !ids.includes(s.sjabloon));
  sel.value = eerste ? eerste.sjabloon : '';
  $('#wcn-sjabloonkaart').hidden = false;
  $('#wcn-menu').hidden = true;
  wcnSjabloon();
  show('wcbron');
}
function wcnSjabloon(){
  const v = $('#wcn-sjabloon').value, s = ((wcSt || {}).sjablonen || []).find(x => x.sjabloon === v);
  wcSj = s ? s.sjabloon : 'eigen'; wcTip = s ? s.tip || '' : '';
  $('#wcn-tip').textContent = wcTip;
  wcnVul(s ? Object.assign({ aan: true }, s) : { naam: '', soort: 'geojson', url: '', licentie: '', schema: 'week', weekdag: 2, dag: 1, tijd: '04:00', opnieuw: 6, wifi: true, aan: true, plek: 'toilet', velden: {}, extra: [], regels: [] });
}
function wcnVul(b){
  $('#wcn-h1').textContent = wcBronId ? t('Bron') : t('Bron toevoegen');
  const aan = b.aan !== false, tg = $('#wcn-aan');
  tg.classList.toggle('on', aan); tg.setAttribute('aria-checked', aan ? 'true' : 'false');
  $('#wcn-naam').value = b.naam || '';
  $('#wcn-soort').value = b.soort || 'geojson';
  $('#wcn-url').value = b.url || '';
  $('#wcn-meta').value = b.metaUrl || '';
  $('#wcn-lic').value = b.licentie || '';
  $('#wcn-info').value = b.info || '';
  $('#wcn-plek').value = b.plek || 'toilet';
  const v = b.velden || {};
  $('#wcn-velden').innerHTML = WC_VELDEN.map(k => '<label class="f" for="wcn-v-' + k + '">' + esc(wcVeldNaam(k)) + '</label><input class="aein" id="wcn-v-' + k + '" data-v="' + k +
    '" maxlength="80" autocomplete="off" autocapitalize="off" spellcheck="false" translate="no" value="' + esc(v[k] || '') + '">').join('');
  document.querySelectorAll('#s-wcbron [data-x]').forEach(c => { c.checked = (b.extra || []).includes(c.dataset.x); });
  $('#wcn-regels').innerHTML = '';
  (b.regels || []).forEach(r => wcnRegel(r));
  const dag = $('#wcn-dag');
  if (!dag.options.length) for (let d = 1; d <= 31; d++) { const o = document.createElement('option'); o.value = d; o.textContent = d; dag.appendChild(o); }
  $('#wcn-schema').value = b.schema || 'week';
  dag.value = b.dag || 1;
  $('#wcn-wd').value = b.weekdag || 2;
  $('#wcn-tijd').value = b.tijd || '04:00';
  $('#wcn-opnieuw').value = b.opnieuw || 6;
  $('#wcn-wifi').checked = b.wifi !== false;
  wcnSoort(); wcnPlan(); wcnStand();
}
function wcVeldNaam(k){
  switch (k) {
    case 'id': return t('Eigen nummer (id)');
    case 'naam': return t('Naam');
    case 'uren': return t('Openingstijden');
    case 'lat': return t('Breedtegraad (CSV, of zonder geometrie)');
    case 'lon': return t('Lengtegraad');
    case 'rolstoel': return t('Rolstoeltoegankelijk (ja/nee)');
    case 'verschoon': return t('Verschoontafel (ja/nee)');
    case 'gratis': return t('Gratis (ja/nee)');
    case 'prijs': return t('Prijs (0 = gratis)');
    default: return t('Douche (ja/nee)');
  }
}
function wcZetNaam(z){
  switch (z) {
    case 'klanten': return t('alleen voor klanten');
    case 'rolstoel': return t('rolstoeltoegankelijk');
    case 'urinoir': return t('alleen een urinoir');
    case 'gratis': return t('gratis');
    case 'betaald': return t('betaald');
    case 'verschoon': return t('verschoontafel');
    case 'douche': return t('douche');
    default: return t('overslaan (niet tonen)');
  }
}
function wcnRegel(r){
  r = r || { veld: '', patroon: '', zet: 'klanten' };
  const d = document.createElement('div'); d.className = 'wc-regel';
  d.innerHTML = '<input class="aein" data-r="veld" maxlength="80" autocomplete="off" autocapitalize="off" spellcheck="false" translate="no" aria-label="' + esc(t('Veld')) + '" placeholder="' + esc(t('Veld')) + '">' +
    '<input class="aein" data-r="patroon" maxlength="100" autocomplete="off" autocapitalize="off" spellcheck="false" translate="no" aria-label="' + esc(t('Zoekpatroon')) + '" placeholder="' + esc(t('Zoekpatroon')) + '">' +
    '<select class="sel" data-r="zet" aria-label="' + esc(t('Dan')) + '">' + WC_ZET.map(z => '<option value="' + z + '">' + esc(wcZetNaam(z)) + '</option>').join('') + '</select>' +
    '<button class="icon-btn" aria-label="' + esc(t('Regel weghalen')) + '">' + uiIcon('trash') + '</button>';
  d.querySelector('[data-r=veld]').value = r.veld || '';
  d.querySelector('[data-r=patroon]').value = r.patroon || '';
  d.querySelector('[data-r=zet]').value = WC_ZET.includes(r.zet) ? r.zet : 'klanten';
  d.querySelector('button').onclick = () => d.remove();
  $('#wcn-regels').appendChild(d);
}
function wcnSoort(){
  const s = $('#wcn-soort').value;
  $('#wcn-metarij').hidden = s !== 'tools';
  $('#wcn-veldenkaart').hidden = s === 'tools';
}
function wcnPlan(){
  const s = $('#wcn-schema').value;
  $('#wcn-dagrij').style.display = s === 'maand' ? 'flex' : 'none';
  $('#wcn-wdrij').style.display = s === 'week' ? 'flex' : 'none';
  ['tijdrij', 'opnrij', 'wifirij'].forEach(id => { $('#wcn-' + id).style.display = s === 'uit' ? 'none' : 'flex'; });
  const o = { schema: s, dag: Number($('#wcn-dag').value) || 1, weekdag: Number($('#wcn-wd').value) || 2, tijd: $('#wcn-tijd').value || '04:00' };
  $('#wcn-plan').textContent = s === 'uit' ? t('Alleen als je zelf op Nu scannen tikt.') : t('Vanzelf {wanneer}. Eerstvolgende keer: {volgende}.', { wanneer: faPlanTekst(o), volgende: faMoment(faVolgende(o, Date.now())) });
}
/** De laatste scan van deze bron. */
function wcnStand(){
  const st = wcSt || {}, b = (st.bronnen || []).find(x => x.id === wcBronId), el = $('#wcn-stand');
  $('#wcn-scan').disabled = !!st.bezig;
  if (!b) { el.hidden = true; return; }
  el.hidden = false;
  const s = b.toestand || {};
  let h = '<h2>' + esc(t('Laatste scan')) + '</h2>';
  if (st.bezig) h += '<p class="note">' + esc(t('Bezig met scannen…')) + '</p>';
  if (s.lastOk) h += '<p>' + esc(t('{wanneer}: {n} plekken ({nieuw} nieuw, {gewijzigd} gewijzigd, {weg} weg).', { wanneer: faMoment(s.lastOk), n: s.aantal || 0, nieuw: s.nieuw || 0, gewijzigd: s.gewijzigd || 0, weg: s.weg || 0 })) + '</p>';
  else if (!s.fout) h += '<p class="note">' + esc(t('Nog niet gescand.')) + '</p>';
  if (s.fout && (s.lastTry || 0) >= (s.lastOk || 0)) h += '<p class="wc-mis">' + esc(t('Laatste poging {wanneer} lukte niet: {fout}', { wanneer: faMoment(s.lastTry), fout: s.fout })) + '</p>' +
    (s.lastOk ? '<p class="note">' + esc(t('De vorige versie blijft staan.')) + '</p>' : '');
  if (b.volgende) h += '<p class="note">' + esc(t('Volgende keer: {wanneer}', { wanneer: faMoment(b.volgende) })) + '</p>';
  const log = (s.log || []).slice(0, 10);
  if (log.length > 1) h += '<details class="help in" data-k="wcn-log"><summary data-icon="info" aria-expanded="false">' + esc(t('Eerdere scans')) + '</summary><div class="help-body"><ul class="wc-log">' +
    log.map(x => '<li>' + esc(x.ok ? t('{wanneer}: {n} plekken, {nieuw} nieuw, {weg} weg', { wanneer: faMoment(x.t), n: x.aantal || 0, nieuw: x.nieuw || 0, weg: x.weg || 0 }) : t('{wanneer}: mislukt ({fout})', { wanneer: faMoment(x.t), fout: x.fout || '' })) + '</li>').join('') + '</ul></div></details>';
  uiHtml(el, h);
}
function wcnLees(){
  const soort = $('#wcn-soort').value, velden = {}, regels = [];
  document.querySelectorAll('#wcn-velden [data-v]').forEach(i => { if (i.value.trim()) velden[i.dataset.v] = i.value.trim(); });
  document.querySelectorAll('#wcn-regels .wc-regel').forEach(d => { const veld = d.querySelector('[data-r=veld]').value.trim(), patroon = d.querySelector('[data-r=patroon]').value.trim(); if (veld && patroon) regels.push({ veld, patroon, zet: d.querySelector('[data-r=zet]').value }); });
  return { id: wcBronId, sjabloon: wcSj || 'eigen', tip: wcTip, naam: $('#wcn-naam').value.trim(), soort, url: $('#wcn-url').value.trim(), metaUrl: soort === 'tools' ? $('#wcn-meta').value.trim() : '',
    licentie: $('#wcn-lic').value.trim(), info: $('#wcn-info').value.trim(), aan: $('#wcn-aan').classList.contains('on'), plek: $('#wcn-plek').value,
    schema: $('#wcn-schema').value, dag: Number($('#wcn-dag').value) || 1, weekdag: Number($('#wcn-wd').value) || 2, tijd: $('#wcn-tijd').value || '04:00',
    opnieuw: Number($('#wcn-opnieuw').value) || 6, wifi: $('#wcn-wifi').checked, velden, regels,
    extra: [...document.querySelectorAll('#s-wcbron [data-x]')].filter(c => c.checked).map(c => c.dataset.x) };
}
function wcnBewaarStil(){
  const r = rdJson(Android.wcBronBewaar(JSON.stringify(wcnLees())), {}) || {};
  if (r.error) { toast(r.error); return null; }
  wcBronId = r.id;
  $('#wcn-sjabloonkaart').hidden = true; $('#wcn-menu').hidden = false; $('#wcn-h1').textContent = t('Bron');
  wcState();
  return r;
}
function wcnBewaar(){ if (!wcnBewaarStil()) return; toast(t('Bron bewaard')); goBack(); }
function wcnScan(){ const b = wcnBewaarStil(); if (b) wcScanStart(b.id); }
function wcnMenu(){
  if (!wcBronId) return;
  openSheet(t('Deze bron'), '', [[t('Verwijderen'), () => wcnWeg(), 'trash']]);
}
function wcnWeg(){
  askConfirm(t('Bron verwijderen?'), t('De gedownloade plekken van deze bron gaan weg. Je eigen wijzigingen blijven bewaard.'), t('Verwijderen'), () => {
    const f = wcFout(Android.wcBronWeg(wcBronId));
    if (f) { toast(f); return; }
    toast(t('Bron verwijderd')); wcState(); goBack();
  });
}

/* ================= mijn wijzigingen (s-wceigen) en gedeeld (s-wcgedeeld) ================= */
function wcEigenRij(p, sub){
  const naam = p.n ? '<b translate="no">' + esc(p.n) + '</b>' : '<b>' + esc(wcSoortNaam(p.s)) + '</b>';
  return '<div class="srow"><button class="sbtn" onclick="wcOpen(' + jsq(p.k) + ')">' + uiIcon(p.f & WC_B.douche && !(p.f & WC_B.toilet) ? 'shower' : 'toilet') +
    '<span class="stxt">' + naam + '<small>' + esc(sub) + '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button></div>';
}
function wceRender(){
  const r = rdJson(Android.wcEigen(), {}) || {}, el = $('#wce-body');
  if (r.error) { uiHtml(el, '<div class="card"><p class="note" style="margin:0">' + esc(r.error) + '</p></div>'); return; }
  const aang = r.aangepast || [], af = r.afgewezen || [], toe = r.toegevoegd || [];
  if (!aang.length && !af.length && !toe.length) { uiHtml(el, uiEmpty('edit', t('Nog niets. Open een plek en kies ⋯ om hem aan te passen of af te wijzen, of houd de kaart ingedrukt om een plek toe te voegen.'))); return; }
  let h = '';
  if (aang.length) h += '<div class="flabel">' + esc(t('Aangepast')) + '</div><div class="card stations">' + aang.map(p => wcEigenRij(p, [p.nu ? t('de bron zegt nu iets anders') : '', p.weg ? t('staat niet meer in de bron') : '', p.t ? fmtDate(p.t) : ''].filter(Boolean).join(' · '))).join('') + '</div>';
  if (af.length) h += '<div class="flabel">' + esc(t('Afgewezen')) + '</div><div class="card stations">' + af.map(a => {
    const naam = a.n ? '<b translate="no">' + esc(a.n) + '</b>' : '<b>' + esc(wcSoortNaam(a.s)) + '</b>';
    return '<div class="srow"><span class="sbtn" style="cursor:default">' + uiIcon('close') + '<span class="stxt">' + naam + '<small>' + esc([wcReden(a.reden), a.gevonden ? '' : t('staat niet meer in de bron'), a.t ? fmtDate(a.t) : ''].filter(Boolean).join(' · ')) +
      '</small></span></span><button class="mini" onclick="wcTerugDo(' + jsq(a.k) + ', \'afgewezen\')">' + esc(t('Terugzetten')) + '</button></div>';
  }).join('') + '</div>';
  if (toe.length) h += '<div class="flabel">' + esc(t('Toegevoegd')) + '</div><div class="card stations">' + toe.map(p => wcEigenRij(p, [p.ib ? t('staat nu ook in een bron') : '', p.t ? fmtDate(p.t) : ''].filter(Boolean).join(' · '))).join('') + '</div>';
  uiHtml(el, h);
}
function wcStatusNaam(s){ return s === 'closed' ? t('opgelost') : s === 'hidden' ? t('verborgen door OpenStreetMap') : t('open'); }
function wcWatNaam(w){ return w === 'afwijzing' ? t('Klopt niet meer') : w === 'wijziging' ? t('Aanpassing') : t('Nieuwe plek'); }
function wcgRij(g){
  return '<div class="srow"><span class="sbtn" style="cursor:default">' + uiIcon(g.status === 'closed' ? 'check' : 'globe') + '<span class="stxt"><b>' + esc(wcWatNaam(g.wat)) + '</b><small>' +
    esc(t('{wanneer}, {status}', { wanneer: fmtDate(g.t), status: wcStatusNaam(g.status) })) + '</small></span></span>' +
    '<button class="mini" data-u="' + esc(g.url) + '" onclick="Android.openUrl(this.dataset.u)">' + esc(t('Bekijken')) + '</button></div>';
}
function wcgRender(){
  const r = rdJson(Android.wcEigen(), {}) || {}, el = $('#wcg-body'), g = (r.gedeeld || []).slice().sort((a, b) => (b.t || 0) - (a.t || 0));
  if (r.error) { uiHtml(el, '<div class="card"><p class="note" style="margin:0">' + esc(r.error) + '</p></div>'); return; }
  if (!g.length) { uiHtml(el, uiEmpty('globe', t('Nog niets doorgegeven. Open een plek die je aanpaste, afwees of toevoegde en kies ⋯ → Doorgeven aan OpenStreetMap.'))); return; }
  uiHtml(el, '<div class="card stations">' + g.map(x => wcgRij(x).replace('</div>', '<button class="icon-btn" onclick="wcgVergeet(' + jsq(x.sleutel) + ')" aria-label="' + esc(t('Uit de lijst halen')) + '">' + uiIcon('close') + '</button></div>')).join('') + '</div>');
}
function wcgVergeet(s){ Android.wcVergeet(s); wcState(); wcgRender(); }
