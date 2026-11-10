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
 * Tijdzone: standaard volgt de app de telefoon; kiest Rene een tijdzone, dan rekent de hele app in die zone (de app-zone):
 * tijden tonen, daglogica (vandaag, gisteren, per dag/week/maand: rgDayKey, rgIsToday …), invoervelden (rgFromLocal,
 * rgLocalIn) en tijden uit de opslag ("07:30" = wandklok in de app-zone; Java plant ze met Regio.zone). Datum-alleen-
 * waarden ('2026-11-03' of '2026-11-03T12:00') verschuiven nooit: fmtDate toont ze als kalenderdatum.
 * Tekst voor het taalmodel blijft vast nl-NL (zie de bewaker). */

/* Talen: een register (de vertalingen staan in web/src/taal/<id>.json, zie 02-taal.js). Elke taal heet zoals in die taal zelf. */
const RG_LANGS = [{ id: 'nl', name: 'Nederlands', locale: 'nl-NL' }, { id: 'en', name: 'English', locale: 'en-GB' }];
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
/* Vaste plaatsen voor de standaardplaats (en het thema met de zon): [id, naam, breedte, lengte, land (ISO 3166)] */
const RG_CITIES = [['rotterdam', 'Rotterdam', 51.92, 4.48, 'NL'], ['amsterdam', 'Amsterdam', 52.37, 4.9, 'NL'], ['den-haag', 'Den Haag', 52.08, 4.31, 'NL'], ['utrecht', 'Utrecht', 52.09, 5.12, 'NL'],
  ['eindhoven', 'Eindhoven', 51.44, 5.48, 'NL'], ['groningen', 'Groningen', 53.22, 6.57, 'NL'], ['maastricht', 'Maastricht', 50.85, 5.69, 'NL'], ['middelburg', 'Middelburg', 51.5, 3.61, 'NL'],
  ['leeuwarden', 'Leeuwarden', 53.2, 5.8, 'NL'], ['enschede', 'Enschede', 52.22, 6.89, 'NL'], ['brussel', 'Brussel', 50.85, 4.35, 'BE']];
const RG_DEF = { lang: 'nl', clock: '24', date: 'dmy', week: 1, tz: '', place: 'auto', dist: 'km', temp: 'c', size: '1024', speed: 'kmh', cur: 'EUR', dec: ',', grp: '.' };

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
    // Tijdzone: '' = volg de telefoon. Een onbekende zone blijft bewaard (de app volgt dan de telefoon en zegt dat)
    tz: typeof c.tz === 'string' && RG_TZ_RE.test(c.tz) ? c.tz : '',
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

/* ---------- tijdzone (de app-zone) ----------
 * rgZone() is de zone waarin de hele app rekent: de gekozen zone, of die van de telefoon. Volgt de app de telefoon
 * (de standaard), dan gaat alles via de gewone Date-functies, precies zoals altijd. Java doet hetzelfde (Regio.zone). */
const RG_TZ_RE = /^[A-Za-z][A-Za-z0-9_+\-\/]{0,63}$/;
const rgTzC = new Map();
let rgTzSt = null;
function rgPhoneTz(){ try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (e) { return 'UTC'; } }
/* Kent deze telefoon de zone? De WebView moet hem kennen (ook een andere naam voor dezelfde zone, zoals Asia/Kolkata voor
 * Asia/Calcutta) en Java precies zo geschreven (Regio.tzOk: hoofdletters tellen). Zonder Java (tests): alleen geen
 * andere hoofdletters dan de WebView. WebView en Android hebben elk een eigen tijdzonedatabase; wat één van beide niet
 * kent, telt niet: dan volgt de app (JS, Java en de skin samen) de telefoon. */
function rgTzOk(id){
  if (typeof id !== 'string' || !RG_TZ_RE.test(id)) return false;
  if (rgTzC.has(id)) return rgTzC.get(id);
  let ok = false, java = null;
  try { if (typeof Android !== 'undefined' && typeof Android.regioTzOk === 'function') java = !!Android.regioTzOk(id); } catch (e) { java = null; }
  try { const r = new Intl.DateTimeFormat('en-US', { timeZone: id }).resolvedOptions().timeZone; ok = java !== null ? java : r === id || r.toLowerCase() !== id.toLowerCase(); } catch (e) { ok = false; }
  if (rgTzC.size > 500) rgTzC.clear();
  rgTzC.set(id, ok);
  return ok;
}
/* { sel: gekozen en bekend ('' = volg de telefoon), phone, zone, own: de app rekent in een andere zone dan de telefoon }.
 * Even onthouden (de telefoon kan intussen van zone wisselen, bijv. op reis). */
function rgTzState(){
  const n = Date.now();
  if (rgTzSt && n - rgTzSt.at < 5000 && n >= rgTzSt.at) return rgTzSt;
  const t = rgCfg().tz, sel = t && rgTzOk(t) ? t : '', phone = rgPhoneTz();
  rgTzSt = { at: n, sel, phone, zone: sel || phone, own: !!sel && sel !== phone, bad: !!t && !sel ? t : '' };
  return rgTzSt;
}
function rgZone(){ return rgTzState().zone; }
function rgTzOwn(){ return rgTzState().own; }
/* Onderdelen van een tijdstip in een zone (en-US, 0–23 uur) */
const rgPartF = new Map(), RG_WDN = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
function rgPartsIn(ms, zone){
  let f = rgPartF.get(zone);
  if (!f) { f = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', weekday: 'short', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }); if (rgPartF.size > 1200) rgPartF.clear(); rgPartF.set(zone, f); }
  const p = {}; f.formatToParts(new Date(ms)).forEach(x => { p[x.type] = x.value; });
  return { y: +p.year, m: +p.month, d: +p.day, hh: +p.hour % 24, mm: +p.minute, ss: +p.second, wd: RG_WDN[p.weekday] };
}
/* Verschil met UTC (ms) in zone z op tijdstip ms, rechtstreeks uit Intl */
function rgOffIn(ms, z){ const s = Math.floor(ms / 1000) * 1000, p = rgPartsIn(s, z); return Date.UTC(p.y, p.m - 1, p.d, p.hh, p.mm, p.ss) - s; }
/* Verschil met UTC (ms) in de app-zone op tijdstip ms. Per uur onthouden: is het verschil aan het begin en het eind van
 * het uur gelijk, dan geldt het voor het hele uur; anders (een wissel van zomer-/wintertijd in dat uur) per minuut. */
const rgOffC = new Map();
function rgOff(ms){
  const st = rgTzState();
  if (!st.own) return -new Date(ms).getTimezoneOffset() * 6e4;
  const h = Math.floor(ms / 36e5), k = st.zone + '|' + h;
  let o = rgOffC.get(k);
  if (o === undefined) {
    const a = rgOffIn(h * 36e5, st.zone), b = rgOffIn(h * 36e5 + 36e5 - 1, st.zone);
    o = a === b ? a : null;
    if (rgOffC.size > 20000) rgOffC.clear();
    rgOffC.set(k, o);
  }
  if (o !== null) return o;
  const km = st.zone + '|m' + Math.floor(ms / 6e4);
  let m = rgOffC.get(km);
  if (m === undefined) { m = rgOffIn(ms, st.zone); rgOffC.set(km, m); }
  return m;
}
/* { y, m (1–12), d, hh, mm, ss, wd (0 = zondag) } van tijdstip t in de app-zone (zonder t: nu) */
function rgParts(t){
  const d = t instanceof Date ? t : new Date(t == null ? Date.now() : t), ms = d.getTime();
  if (isNaN(ms)) return null;
  if (!rgTzOwn()) return { y: d.getFullYear(), m: d.getMonth() + 1, d: d.getDate(), hh: d.getHours(), mm: d.getMinutes(), ss: d.getSeconds(), wd: d.getDay() };
  const w = new Date(ms + rgOff(ms));
  return { y: w.getUTCFullYear(), m: w.getUTCMonth() + 1, d: w.getUTCDate(), hh: w.getUTCHours(), mm: w.getUTCMinutes(), ss: w.getUTCSeconds(), wd: w.getUTCDay() };
}
const rgKey = (y, m, d) => String(y).padStart(4, '0') + '-' + rgP2(m) + '-' + rgP2(d);
/* 'JJJJ-MM-DD' van tijdstip t in de app-zone */
function rgDayKey(t){ const p = rgParts(t); return p ? rgKey(p.y, p.m, p.d) : ''; }
/* 'JJJJ-MM' van tijdstip t in de app-zone */
function rgMonthKey(t){ return rgDayKey(t).slice(0, 7); }
function rgToday(){ return rgDayKey(Date.now()); }
/* Kalenderrekenen op een datum ('JJJJ-MM-DD'), zonder tijdzone */
function rgDayAdd(key, n){ const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(key || '')); if (!m) return ''; const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3] + (n || 0))); return rgKey(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate()); }
/* Dagen van datum a tot datum b ('JJJJ-MM-DD') */
function rgDayDiff(a, b){ const u = k => { const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(k || '')); return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN; }; return Math.round((u(b) - u(a)) / 864e5); }
/* Weekdag (0 = zondag) van een datum ('JJJJ-MM-DD') */
function rgWeekday(key){ const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(key || '')); return m ? new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay() : NaN; }
function rgIsToday(t){ return rgDayKey(t) === rgToday(); }
function rgIsYesterday(t){ return rgDayKey(t) === rgDayAdd(rgToday(), -1); }
function rgIsTomorrow(t){ return rgDayKey(t) === rgDayAdd(rgToday(), 1); }
/* Wandklok (ms alsof het UTC is) → tijdstip in de app-zone. Bestaat de tijd twee keer (de klok gaat terug), dan de
 * eerste; bestaat hij niet (de klok springt vooruit), dan schuift hij mee met de sprong: 02:30 wordt 03:30, zoals een
 * wekker en check-in. Precies zo in Regio.java (wallToMillis). */
function rgWallToMs(W){
  const oa = rgOff(W - 864e5), ob = rgOff(W + 864e5), ta = W - oa, tb = W - ob, va = rgOff(ta) === oa, vb = rgOff(tb) === ob;
  if (va && vb) return Math.min(ta, tb);
  if (vb && !va) return tb;
  return ta; // geldig, of in het gat: met de tijd van vóór de sprong (komt na de sprong uit)
}
/* 'JJJJ-MM-DD' en 'UU:MM' (wandklok in de app-zone) → tijdstip in ms; NaN als het geen datum is */
function rgFromLocal(date, hm){
  const a = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date || '')), b = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(hm == null || hm === '' ? '00:00' : String(hm));
  if (!a || !b || +b[1] > 23 || +b[2] > 59) return NaN;
  return rgWallToMs(Date.UTC(+a[1], +a[2] - 1, +a[3], +b[1], +b[2], +(b[3] || 0)));
}
/* Begin van de dag (00:00 in de app-zone) waarin tijdstip t valt */
function rgStartOfDay(t){ return rgFromLocal(rgDayKey(t), '00:00'); }
/* Begin van de dag na die van t */
function rgEndOfDay(t){ return rgFromLocal(rgDayAdd(rgDayKey(t), 1), '00:00'); }
/* 'UU:MM' van tijdstip t (zonder t: nu) in de app-zone, voor invoervelden en vergelijken met tijden uit de opslag */
function rgHm(t){ const p = rgParts(t); return p ? rgP2(p.hh) + ':' + rgP2(p.mm) : ''; }
function rgNowHm(){ return rgHm(Date.now()); }
/* Minuut van de dag (0–1439) in de app-zone */
function rgMinOfDay(t){ const p = rgParts(t); return p ? p.hh * 60 + p.mm : 0; }
/* 'JJJJ-MM-DDTUU:MM' voor <input type=datetime-local>, en terug */
function rgLocalIn(t){ return rgDayKey(t) + 'T' + rgHm(t); }
function rgFromLocalIn(v){ const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(String(v || '')); return m ? rgFromLocal(m[1], m[2]) : NaN; }
/* Eerstvolgend tijdstip na `after` waarop het in de app-zone 'UU:MM' is; okDay(weekdag 0 = zondag) kiest de dagen */
function rgNextAt(hm, after, okDay){
  const now = after == null ? Date.now() : after, k0 = rgDayKey(now);
  for (let i = 0; i < 9; i++) {
    const k = rgDayAdd(k0, i), t = rgFromLocal(k, hm);
    if (t > now && (!okDay || okDay(rgWeekday(k)))) return t;
  }
  return 0;
}
/* Een kalenderdatum als Date om mee te rekenen (12:00 telefoontijd; nooit om te tonen: gebruik fmtDate(key)) */
function rgCalDate(key){ const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(key || '')); return m ? new Date(+m[1], +m[2] - 1, +m[3], 12) : new Date(NaN); }
function rgCalKey(d){ return rgKey(d.getFullYear(), d.getMonth() + 1, d.getDate()); }
/* Na een andere keuze (of een andere tijdzone van de telefoon) opnieuw bepalen */
function rgTzReset(){ rgTzSt = null; rgOffC.clear(); }

/* ---------- datum en tijd ---------- */
const rgFmtC = new Map();
function rgDtf(o){
  const k = JSON.stringify(o); let f = rgFmtC.get(k);
  if (!f) { f = new Intl.DateTimeFormat(regioLocale(), o); if (rgFmtC.size > 1200) rgFmtC.clear(); rgFmtC.set(k, f); }
  return f;
}
const rgP2 = n => String(n).padStart(2, '0');
/* Datum en/of tijd met Intl-opties zoals toLocaleString ze kent, maar met de gekozen klok, datumnotatie en de app-zone.
 * Een datum zonder tijd ('2026-11-03', ook met 'T12:00') is een kalenderdatum: die verschuift nooit. */
const RG_DAYONLY = /^(\d{4})-(\d{2})-(\d{2})(?:T12:00(?::00)?)?$/;
function rgFmt(t, o){
  const dm = typeof t === 'string' ? RG_DAYONLY.exec(t) : null;
  const d = dm ? new Date(Date.UTC(+dm[1], +dm[2] - 1, +dm[3], 12)) : t instanceof Date ? t : new Date(t);
  if (isNaN(d.getTime())) return '';
  const c = rgCfg(), x = Object.assign({}, o);
  if (!x.timeZone) { if (dm) x.timeZone = 'UTC'; else if (rgTzOwn()) x.timeZone = rgZone(); }
  if (x.hour) { if (c.clock === '12') { x.hour12 = true; if (x.hour === '2-digit') x.hour = 'numeric'; } else if (!('hour12' in x)) x.hourCycle = 'h23'; }
  // Een volledige datum (dag, maand en jaar, zonder dagnaam): de gekozen notatie
  if (x.day && x.month && x.year && !x.weekday) {
    const num = x.month === 'numeric' || x.month === '2-digit';
    if (c.date === 'iso') {
      const date = dm ? dm[1] + '-' + dm[2] + '-' + dm[3] : rgDayKey(d);
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
function speedUnit(){ return rgCfg().speed === 'mph' ? 'mph' : t('km/u'); }
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
function rgSpotName(which){ return which === 'home' ? t('Thuis') : which === 'work' ? t('Werk') : ''; }
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
  rgC = null; rgFmtC.clear(); rgNumC.clear(); rgTzReset();
  const c = rgCfg();
  rgNative();
  if (JSON.stringify(c) !== prev) { try { window.dispatchEvent(new CustomEvent('regiochange', { detail: c })); } catch (e) { } }
  return c;
}
/* Opnieuw inlezen (na het terugzetten van interface-instellingen) en opnieuw aan Java geven */
function rgReload(){ rgC = null; rgSp = null; rgFmtC.clear(); rgNumC.clear(); rgTzReset(); rgNative(); try { window.dispatchEvent(new CustomEvent('regiochange', { detail: rgCfg() })); } catch (e) { } }
/* Java krijgt dezelfde keuze (zonder thuis en werk, die heeft Java zelf), voor meldingen, widgets en de Telefoon-skin */
function rgNative(){
  if (typeof Android === 'undefined' || typeof Android.regioSet !== 'function') return;
  // De zone die de app echt gebruikt ('' = de telefoon), zodat JS, Java en de Telefoon-skin altijd hetzelfde doen
  const c = Object.assign({}, rgCfg(), { locale: regioLocale(), tz: rgTzState().sel }); delete c.home; delete c.work;
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
  const set = (cls, v) => document.querySelectorAll('.' + cls).forEach(e => { if (e.textContent !== v) e.textContent = v; });
  set('rg-u-dist', distUnit()); set('rg-u-distl', rgCfg().dist === 'mi' ? t('mijl') : t('kilometer')); set('rg-u-speed', speedUnit()); set('rg-u-temp', tempUnit());
}
rgNative();
addEventListener('DOMContentLoaded', () => { rgWeekChips(); rgUnits(); });
addEventListener('regiochange', () => {
  rgWeekChips(); rgUnits();
  // Andere tijdzone: het thema "op tijd" of met de zon opnieuw (wissels in de app-zone)
  try { if (typeof thApply === 'function') thApply(); } catch (e) { }
  // Wat nu in beeld is opnieuw tekenen (andere schermen tekenen zich bij het openen)
  try { if (typeof current !== 'undefined' && current === 'home' && typeof renderDash === 'function') renderDash(); } catch (e) { }
});
