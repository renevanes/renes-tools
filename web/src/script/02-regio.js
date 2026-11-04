/* ---------- Regio en taal: tijd en datum, locatie, eenheden, valuta en getallen, centraal voor de hele app ----------
 * Alle schermen schrijven datums, tijden, getallen, bedragen en eenheden via de functies hieronder (fmtTime, fmtDate,
 * fmtDT, fmtNum, fmtMoney, fmtDist, fmtTemp, fmtSize, fmtSpeed …), nooit meer zelf met toLocaleString('nl-NL') of
 * toFixed().replace('.', ','). tests/ui/regio-guard.test.js bewaakt dat. Machinale notaties (ISO voor opslag,
 * bestandsnamen, parameters) blijven zoals ze zijn.
 * De standaard is precies hoe de app het altijd deed (Nederlands, 24 uur, 3-11-2026, euro, km, °C), dus er verandert
 * niets tot Rene iets kiest. Een wijziging geeft de gebeurtenis 'regiochange' (net als 'themechange') en gaat via
 * Android.regioSet naar Java (meldingen, widgets, Telefoon-skin: Regio.java).
 * Thuis en werk staan alleen in Java (Android.regioSpots / regioSpotsSet, bestand regio.xml): niet in localStorage of
 * webstore, zodat ze nooit in de Android-backup komen; wel in je eigen versleutelde "Alle appgegevens".
 * Tijdzone: de app volgt altijd de telefoon. Datum-alleen-waarden en daglogica ("vandaag") volgen ook de telefoon.
 * Tekst voor het taalmodel blijft vast nl-NL (zie de bewaker). */

/* Talen: een register, zodat er later talen bij kunnen. Er is nu alleen Nederlands (nog geen vertalingen). */
const RG_LANGS = [{ id: 'nl', name: 'Nederlands', locale: 'nl-NL' }];
const RG_CLOCK = [['24', '24-uurs (14:05)'], ['12', '12-uurs (2:05 p.m.)']];
const RG_DATE = [['dmy', '31-10-2026'], ['dmon', '31 okt 2026'], ['iso', '2026-10-31']];
const RG_WEEK = [['1', 'Maandag'], ['0', 'Zondag'], ['6', 'Zaterdag']];
const RG_DIST = [['km', 'Kilometer (km)'], ['mi', 'Mijl (mi)']];
const RG_TEMP = [['c', 'Celsius (°C)'], ['f', 'Fahrenheit (°F)']];
const RG_SIZE = [['1024', '1 MB = 1024 kB'], ['1000', '1 MB = 1000 kB']];
const RG_SPEED = [['kmh', 'km/u'], ['mph', 'mph (mijl per uur)']];
const RG_DEC = [[',', 'Komma (1,5)'], ['.', 'Punt (1.5)']];
const RG_GRP = [['.', 'Punt (1.234)'], [' ', 'Spatie (1\u202f234)'], ['', 'Geen (1234)'], [',', 'Komma (1,234)']];
/* Spatie voor duizendtallen: een smalle vaste spatie (U+202F), dan breekt een getal nooit af */
const RG_NNBSP = '\u202f';
/* Veelgebruikte valuta (bedragen worden niet omgerekend: dit is de standaardvaluta voor bedragen zonder eigen munt en voorbeelden) */
const RG_CUR = [['EUR', 'Euro (€)'], ['USD', 'Amerikaanse dollar ($)'], ['GBP', 'Britse pond (£)'], ['CHF', 'Zwitserse frank (CHF)'], ['DKK', 'Deense kroon (kr)'],
  ['SEK', 'Zweedse kroon (kr)'], ['NOK', 'Noorse kroon (kr)'], ['PLN', 'Poolse zloty (zł)'], ['CZK', 'Tsjechische kroon (Kč)'], ['TRY', 'Turkse lira (₺)'],
  ['CAD', 'Canadese dollar ($)'], ['AUD', 'Australische dollar ($)'], ['JPY', 'Japanse yen (¥)'], ['CNY', 'Chinese yuan (¥)'], ['INR', 'Indiase roepie (₹)'],
  ['SRD', 'Surinaamse dollar ($)'], ['ANG', 'Antilliaanse gulden (ƒ)'], ['MAD', 'Marokkaanse dirham'], ['ZAR', 'Zuid-Afrikaanse rand (R)'], ['BRL', 'Braziliaanse real (R$)']];
/* Vaste plaatsen voor de standaardplaats (en het thema met de zon) */
const RG_CITIES = [['rotterdam', 'Rotterdam', 51.92, 4.48], ['amsterdam', 'Amsterdam', 52.37, 4.9], ['den-haag', 'Den Haag', 52.08, 4.31], ['utrecht', 'Utrecht', 52.09, 5.12],
  ['eindhoven', 'Eindhoven', 51.44, 5.48], ['groningen', 'Groningen', 53.22, 6.57], ['maastricht', 'Maastricht', 50.85, 5.69], ['middelburg', 'Middelburg', 51.5, 3.61],
  ['leeuwarden', 'Leeuwarden', 53.2, 5.8], ['enschede', 'Enschede', 52.22, 6.89], ['brussel', 'Brussel', 50.85, 4.35]];
const RG_DEF = { lang: 'nl', clock: '24', date: 'dmy', week: 1, place: 'auto', dist: 'km', temp: 'c', size: '1024', speed: 'kmh', cur: 'EUR', dec: ',', grp: '.' };

/* ---------- puur (getest in tests/ui/regio.test.js) ---------- */
function rgSpot(p){
  if (!p || typeof p !== 'object') return null;
  const lat = +p.lat, lon = +p.lon;
  if (!isFinite(lat) || !isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (p.lat == null || p.lon == null)) return null;
  return { name: String(p.name || '').slice(0, 60), addr: String(p.addr || '').slice(0, 200), lat: Math.round(lat * 1e6) / 1e6, lon: Math.round(lon * 1e6) / 1e6 };
}
/* De keuze, gecontroleerd (wat er ook in de opslag staat). Zonder thuis en werk: die staan alleen in Java. */
function rgClean(o){
  const c = Object.assign({}, RG_DEF, o && typeof o === 'object' ? o : {}), has = (list, v) => list.some(x => x[0] === String(v));
  const out = {
    lang: RG_LANGS.some(l => l.id === c.lang) ? c.lang : 'nl',
    clock: has(RG_CLOCK, c.clock) ? String(c.clock) : '24',
    date: has(RG_DATE, c.date) ? c.date : 'dmy',
    week: has(RG_WEEK, c.week) ? +c.week : 1,
    place: c.place === 'auto' || c.place === 'home' || c.place === 'work' || RG_CITIES.some(x => x[0] === c.place) ? c.place : 'auto',
    dist: has(RG_DIST, c.dist) ? c.dist : 'km', temp: has(RG_TEMP, c.temp) ? c.temp : 'c',
    size: has(RG_SIZE, c.size) ? String(c.size) : '1024', speed: has(RG_SPEED, c.speed) ? c.speed : 'kmh',
    cur: has(RG_CUR, c.cur) ? c.cur : 'EUR', dec: has(RG_DEC, c.dec) ? c.dec : ',', grp: has(RG_GRP, c.grp) ? c.grp : '.'
  };
  // Hetzelfde teken voor decimalen en duizendtallen kan niet: dan de duizendtallen anders
  if (out.grp === out.dec) out.grp = out.dec === ',' ? '.' : ',';
  return out;
}

/* ---------- de keuze ---------- */
let rgC = null, rgSp = null;
/* Thuis en werk uit Java ({home, work}); zonder brug (of kapot): niets */
function rgSpots(){
  if (rgSp) return rgSp;
  let o = {}; try { if (typeof Android !== 'undefined' && typeof Android.regioSpots === 'function') o = JSON.parse(Android.regioSpots() || '{}') || {}; } catch (e) { o = {}; }
  return (rgSp = { home: rgSpot(o.home), work: rgSpot(o.work) });
}
function rgCfg(){ if (!rgC) { const s = rgSpots(); rgC = Object.assign(rgClean(store.get('regio', null)), { home: s.home, work: s.work }); } return rgC; }
function regioLocale(){ const c = rgCfg(); return (RG_LANGS.find(l => l.id === c.lang) || RG_LANGS[0]).locale; }
function rgLang(){ return RG_LANGS.find(l => l.id === rgCfg().lang) || RG_LANGS[0]; }
function weekStart(){ return rgCfg().week; } // 1 = maandag, 0 = zondag, 6 = zaterdag (zoals Date.getDay)
/* Dagen van de week in de gekozen volgorde, als getDay-nummers (0 = zondag) */
function weekDays(){ const s = weekStart(); return [0, 1, 2, 3, 4, 5, 6].map(i => (s + i) % 7); }
/* Hoeveel vakjes vóór de eerste van de maand in een kalender met de gekozen eerste dag */
function weekLead(d){ return (new Date(d).getDay() - weekStart() + 7) % 7; }

/* ---------- datum en tijd ---------- */
const rgFmtC = new Map();
function rgDtf(o){
  const k = JSON.stringify(o); let f = rgFmtC.get(k);
  if (!f) { f = new Intl.DateTimeFormat(regioLocale(), o); if (rgFmtC.size > 300) rgFmtC.clear(); rgFmtC.set(k, f); }
  return f;
}
const rgP2 = n => String(n).padStart(2, '0');
/* Datum en/of tijd met Intl-opties zoals toLocaleString ze kent, maar met de gekozen klok en datumnotatie (tijdzone van de telefoon) */
function rgFmt(t, o){
  const d = t instanceof Date ? t : new Date(t);
  if (isNaN(d.getTime())) return '';
  const c = rgCfg(), x = Object.assign({}, o);
  if (x.hour) { if (c.clock === '12') { x.hour12 = true; if (x.hour === '2-digit') x.hour = 'numeric'; } else if (!('hour12' in x)) x.hourCycle = 'h23'; }
  // Een volledige datum (dag, maand en jaar, zonder dagnaam): de gekozen notatie
  if (x.day && x.month && x.year && !x.weekday) {
    const num = x.month === 'numeric' || x.month === '2-digit';
    if (c.date === 'iso') {
      const date = d.getFullYear() + '-' + rgP2(d.getMonth() + 1) + '-' + rgP2(d.getDate());
      if (!x.hour && !x.minute) return date;
      const tm = Object.assign({}, x); delete tm.day; delete tm.month; delete tm.year; delete tm.era;
      return date + ' ' + rgDtf(tm).format(d);
    }
    if (c.date === 'dmon' && num) { x.month = 'short'; x.day = 'numeric'; }
  }
  return rgDtf(x).format(d);
}
const RG_DSTYLE = {
  num: { day: 'numeric', month: 'numeric', year: 'numeric' }, // 3-11-2026 (of volgens de gekozen notatie)
  kort: { day: 'numeric', month: 'short', year: 'numeric' }, // 3 nov 2026
  lang: { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }, // dinsdag 3 november 2026
  dm: { day: 'numeric', month: 'short' }, // 3 nov
  wdm: { weekday: 'short', day: 'numeric', month: 'short' }, // di 3 nov
  wd: { weekday: 'short' }, wdl: { weekday: 'long' }, // di, dinsdag
  maand: { month: 'long', year: 'numeric' } // november 2026
};
/* Datum; style is een naam uit RG_DSTYLE of Intl-opties (zonder: zoals toLocaleDateString, 3-11-2026) */
function fmtDate(t, style){ return rgFmt(t, typeof style === 'string' ? RG_DSTYLE[style] || RG_DSTYLE.num : style || RG_DSTYLE.num); }
/* Tijd: 14:05 (of 2:05 p.m.); sec = met seconden. Of Intl-opties. */
function fmtTime(t, o){ return rgFmt(t, o && typeof o === 'object' ? o : o ? { hour: '2-digit', minute: '2-digit', second: '2-digit' } : { hour: '2-digit', minute: '2-digit' }); }
/* Datum met tijd; zonder opties zoals toLocaleString (3-11-2026, 14:05:07) */
function fmtDT(t, o){ return rgFmt(t, o || { day: 'numeric', month: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
function fmtDateTime(t, style){ return typeof style === 'string' ? fmtDT(t, Object.assign({}, RG_DSTYLE[style] || RG_DSTYLE.num, { hour: '2-digit', minute: '2-digit' })) : fmtDT(t, style); }
/* "a.m." / "p.m." in de taal van de app (zonder datum of tijdzone) */
function rgDayPeriod(h){
  try { const p = rgDtf({ hour: 'numeric', hour12: true, timeZone: 'UTC' }).formatToParts(Date.UTC(2000, 0, 1, h)).find(x => x.type === 'dayPeriod'); if (p) return p.value; } catch (e) { }
  return h < 12 ? 'a.m.' : 'p.m.';
}
/* "14:05" uit uren en minuten (een tijd uit de opslag zoals "07:30"), rechtstreeks: nooit via een datum */
function fmtHm(hm){
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hm || ''));
  if (!m || +m[1] > 23 || +m[2] > 59) return String(hm || '');
  const h = +m[1];
  return rgCfg().clock === '12' ? ((h % 12) || 12) + ':' + m[2] + ' ' + rgDayPeriod(h) : rgP2(h) + ':' + m[2];
}

/* ---------- getallen en geld ---------- */
const rgNumC = new Map();
function rgNf(o){ const k = JSON.stringify(o); let f = rgNumC.get(k); if (!f) { f = new Intl.NumberFormat(regioLocale(), o); if (rgNumC.size > 200) rgNumC.clear(); rgNumC.set(k, f); } return f; }
/* Getal met de gekozen tekens; digits = vast aantal decimalen, of Intl-opties (zonder: zoals toLocaleString) */
function fmtNum(v, digits){
  v = +v; if (!isFinite(v)) return '';
  const o = typeof digits === 'number' ? { minimumFractionDigits: digits, maximumFractionDigits: digits } : digits || {};
  const c = rgCfg(), g = c.grp === ' ' ? RG_NNBSP : c.grp;
  return rgNf(o).formatToParts(v).map(p => p.type === 'group' ? g : p.type === 'decimal' ? c.dec : p.value).join('');
}
/* Getal uit een invoerveld: "12,50", "12.50", "1.234,56", "1,234.56", "€ 1 234" → getal (of null).
 * Komen punt en komma allebei voor, dan is de laatste het decimaalteken. Eén teken met precies drie cijfers erna dat
 * het gekozen teken voor duizendtallen is ("1.250" bij komma als decimaalteken) telt als duizendtallen. */
function parseNum(s){
  let t = String(s == null ? '' : s).replace(/eur|[€$£¥₹₺\s\u00a0\u202f]/gi, '');
  const neg = /^[-−]/.test(t); if (neg) t = t.slice(1);
  if (!/^[\d.,]+$/.test(t) || !/\d/.test(t)) return null;
  const c = rgCfg(), lc = t.lastIndexOf(','), ld = t.lastIndexOf('.');
  let dec = null;
  if (lc >= 0 && ld >= 0) dec = lc > ld ? ',' : '.';
  else if (lc >= 0 || ld >= 0) {
    const ch = lc >= 0 ? ',' : '.', n = t.split(ch).length - 1, after = t.length - t.lastIndexOf(ch) - 1;
    dec = n > 1 || (after === 3 && ch === c.grp) ? null : ch;
  }
  t = dec ? t.split(dec === ',' ? '.' : ',').join('').replace(dec, '.') : t.replace(/[.,]/g, '');
  const v = parseFloat(t);
  return isFinite(v) ? (neg ? -v : v) : null;
}
/* Getal om in een invoerveld te zetten (zonder duizendtallen, met het gekozen decimaalteken) */
function fmtNumIn(v, digits){ return fmtNum(v, { minimumFractionDigits: digits || 0, maximumFractionDigits: digits == null ? 6 : digits, useGrouping: false }); }
function rgCurInfo(cur){
  try { const f = rgNf({ style: 'currency', currency: cur, currencyDisplay: 'narrowSymbol' }), p = f.formatToParts(1); return { sym: (p.find(x => x.type === 'currency') || {}).value || cur, dig: f.resolvedOptions().maximumFractionDigits }; }
  catch (e) { return { sym: cur, dig: 2 }; }
}
function rgCur(){ return rgCfg().cur; }
/* Bedrag: "€ 1.234,56". cur = munt van het bedrag zelf (bijv. EUR van de bank), anders de standaardvaluta.
 * o.sign: '+ ' bij positief; o.round: zonder decimalen; o.minus: teken voor negatief (standaard '− ' vóór de munt,
 * zoals Bankzaken); o.minusIn: "€ -12,50" (minteken na de munt, zoals Facturen). Afgerond nul heeft nooit een minteken. */
function fmtMoney(v, cur, o){
  v = +v; if (!isFinite(v)) return '';
  o = o || {};
  const i = rgCurInfo(cur || rgCur()), dig = o.round ? 0 : i.dig, f = Math.pow(10, dig), a = Math.round(Math.abs(v) * f) / f;
  const neg = v < 0 && a > 0, n = fmtNum(a, dig);
  if (o.minusIn) return i.sym + ' ' + (neg ? '-' : '') + n;
  return (o.sign && v > 0 && a > 0 ? '+ ' : '') + (neg ? (o.minus != null ? o.minus : '− ') : '') + i.sym + ' ' + n;
}

/* ---------- eenheden ---------- */
const RG_MI = 1609.344, RG_FT = 0.3048;
function distUnit(){ return rgCfg().dist === 'mi' ? 'mi' : 'km'; }
/* Afstand (in meters) als getal in km of mijl, met vaste decimalen */
function distVal(m, digits){ return fmtNum((+m || 0) / (rgCfg().dist === 'mi' ? RG_MI : 1000), digits == null ? 1 : digits); }
/* "12,5 km" of "7,8 mi" */
function fmtDist(m, digits){ return distVal(m, digits) + ' ' + distUnit(); }
/* Korte afstand of hoogte (in meters): "12 m" of "39 ft"; digits = decimalen (standaard afgerond) */
function fmtMeters(m, digits){
  const ft = rgCfg().dist === 'mi', v = (+m || 0) / (ft ? RG_FT : 1);
  return (digits ? fmtNum(v, { maximumFractionDigits: digits }) : fmtNum(Math.round(v))) + (ft ? ' ft' : ' m');
}
function tempUnit(){ return rgCfg().temp === 'f' ? '°F' : '°C'; }
function tempVal(c){ c = +c; return rgCfg().temp === 'f' ? c * 9 / 5 + 32 : c; }
/* Temperatuur (in °C): "18 °C" / "64 °F"; kort = alleen "18°" (zoals het weer) */
function fmtTemp(c, kort){
  if (c == null || c === '' || !isFinite(+c)) return '–';
  const v = fmtNum(Math.round(tempVal(c)));
  return kort ? v + '°' : v + ' ' + tempUnit();
}
function speedUnit(){ return rgCfg().speed === 'mph' ? 'mph' : 'km/u'; }
/* Snelheid (in km/u) als getal of met eenheid */
function speedVal(kmh, digits){ return fmtNum((+kmh || 0) / (rgCfg().speed === 'mph' ? RG_MI / 1000 : 1), digits == null ? 1 : digits); }
function fmtSpeed(kmh, digits){ return speedVal(kmh, digits) + ' ' + speedUnit(); }
/* Opslag: "12 kB", "340 MB", "1,2 GB" (zoals de app het altijd deed: geen duizendtallen, "1023 kB"; 1024 of 1000 per stap) */
const rgSz = (v, d) => fmtNum(v, { minimumFractionDigits: d, maximumFractionDigits: d, useGrouping: false });
function fmtSize(b, o){
  b = Math.max(0, +b || 0); o = o || {};
  const k = rgCfg().size === '1000' ? 1000 : 1024, M = k * k, G = M * k;
  if (o.bytes && b < k) return rgSz(b, 0) + ' B';
  if (b < M) return rgSz(Math.max(o.bytes ? 0 : 1, Math.round(b / k)), 0) + ' kB';
  if (b < G) return rgSz(b / M, o.mb != null ? o.mb : 0) + ' MB';
  return rgSz(b / G, o.gb != null ? o.gb : 1) + ' GB';
}
/* Altijd in MB (bijv. de voortgang van een download): "12,3 MB" */
function fmtMB(b, digits){ const k = rgCfg().size === '1000' ? 1000 : 1024; return rgSz(Math.max(0, +b || 0) / (k * k), digits == null ? 1 : digits) + ' MB'; }

/* ---------- locatie ---------- */
function rgSpotName(which){ return which === 'home' ? 'Thuis' : which === 'work' ? 'Werk' : ''; }
/* Thuis, werk of de standaardplaats: { name, lat, lon, addr } of null (niet ingesteld / "Mijn locatie") */
function rgPlace(which){
  const c = rgCfg(), w = which || c.place;
  if (w === 'home' || w === 'work') { const p = c[w]; return p ? Object.assign({}, p, { name: p.name || rgSpotName(w), which: w }) : null; }
  const city = RG_CITIES.find(x => x[0] === w);
  return city ? { name: city[1], lat: city[2], lon: city[3], addr: '', which: w } : null;
}
/* Afstand in meters tussen twee punten (haversine) */
function rgMeters(a, b){
  const r = Math.PI / 180, dl = (b.lat - a.lat) * r, dn = ((b.lon != null ? b.lon : b.lng) - (a.lon != null ? a.lon : a.lng)) * r;
  const h = Math.sin(dl / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dn / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(h)));
}
/* "Thuis" of "Werk" als een punt (lat, lon/lng) daar dichtbij ligt (binnen r meter, standaard 300), anders '' */
function rgNear(p, r){
  if (!p || p.lat == null) return '';
  for (const w of ['home', 'work']) { const s = rgCfg()[w]; if (s && rgMeters(s, p) <= (r || 300)) return s.name || rgSpotName(w); }
  return '';
}

/* ---------- bewaren, doorgeven en opnieuw tekenen ---------- */
function rgSave(patch){
  const prev = JSON.stringify(rgCfg());
  patch = Object.assign({}, patch);
  // Thuis en werk: alleen naar Java (regio.xml, niet in de Android-backup), nooit in localStorage
  if ('home' in patch || 'work' in patch) {
    const sp = rgSpots(), n = { home: 'home' in patch ? rgSpot(patch.home) : sp.home, work: 'work' in patch ? rgSpot(patch.work) : sp.work };
    try { if (typeof Android !== 'undefined' && typeof Android.regioSpotsSet === 'function') Android.regioSpotsSet(JSON.stringify(n)); } catch (e) { }
    rgSp = null; delete patch.home; delete patch.work;
  }
  rgC = null;
  store.set('regio', rgClean(Object.assign({}, rgCfg(), patch)));
  rgC = null; rgFmtC.clear(); rgNumC.clear();
  const c = rgCfg();
  rgNative();
  if (JSON.stringify(c) !== prev) { try { window.dispatchEvent(new CustomEvent('regiochange', { detail: c })); } catch (e) { } }
  return c;
}
/* Opnieuw inlezen (na het terugzetten van interface-instellingen) en opnieuw aan Java geven */
function rgReload(){ rgC = null; rgSp = null; rgFmtC.clear(); rgNumC.clear(); rgNative(); try { window.dispatchEvent(new CustomEvent('regiochange', { detail: rgCfg() })); } catch (e) { } }
/* Java krijgt dezelfde keuze (zonder thuis en werk, die heeft Java zelf), voor meldingen, widgets en de Telefoon-skin */
function rgNative(){
  if (typeof Android === 'undefined' || typeof Android.regioSet !== 'function') return;
  const c = Object.assign({}, rgCfg(), { locale: regioLocale() }); delete c.home; delete c.work;
  const j = JSON.stringify(c);
  if (j === rgNative.last) return;
  rgNative.last = j;
  try { Android.regioSet(j); } catch (e) { }
}
/* Weekdagknoppen (data-d: 0 = maandag … 6 = zondag) in de gekozen volgorde zetten */
function rgWeekChips(root){
  (root || document).querySelectorAll('.chips').forEach(ch => {
    const bs = [...ch.children].filter(b => b.matches('button[data-d]'));
    if (bs.length !== 7 || bs.map(b => +b.dataset.d).sort().join() !== '0,1,2,3,4,5,6') return;
    const order = weekDays().map(g => (g + 6) % 7); // getDay → maandag = 0
    order.forEach(d => { const b = bs.find(x => +x.dataset.d === d); if (b) ch.appendChild(b); });
  });
}
/* Vaste eenheden in de schermen (class rg-u-dist, rg-u-speed, rg-u-temp) */
function rgUnits(){
  const set = (cls, t) => document.querySelectorAll('.' + cls).forEach(e => { if (e.textContent !== t) e.textContent = t; });
  set('rg-u-dist', distUnit()); set('rg-u-distl', rgCfg().dist === 'mi' ? 'mijl' : 'kilometer'); set('rg-u-speed', speedUnit()); set('rg-u-temp', tempUnit());
}
rgNative();
addEventListener('DOMContentLoaded', () => { rgWeekChips(); rgUnits(); });
addEventListener('regiochange', () => {
  rgWeekChips(); rgUnits();
  // Wat nu in beeld is opnieuw tekenen (andere schermen tekenen zich bij het openen)
  try { if (typeof current !== 'undefined' && current === 'home' && typeof renderDash === 'function') renderDash(); } catch (e) { }
});
