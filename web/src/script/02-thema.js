/* ---------- Thema: Systeem, Licht, Donker, Automatisch op tijd of van zonsondergang tot zonsopkomst ----------
 * Hoe het werkt: de opmaak gebruikt overal @media (prefers-color-scheme: dark). thCss (body-begin.html) zet die regels
 * vast op donker of licht, of laat ze de telefoon volgen. Zo doet alle opmaak mee, ook #dyn-theme (kleuren uit je
 * achtergrond) en wat later nog bijkomt, zonder een tweede set kleuren. uiDark() geeft de stand; bij een wissel komt er
 * een gebeurtenis 'themechange' en gaan de systeembalken mee. De zon wordt op de telefoon berekend (NOAA), zonder internet.
 * Java krijgt de keuze (Android.themeSet) voor de achtergrond bij het opstarten en de Telefoon-skin. */
const TH_MODES = [['system', 'Systeem (volgt je telefoon)'], ['light', 'Licht'], ['dark', 'Donker'], ['time', 'Automatisch op tijd'], ['sun', 'Automatisch zonsondergang–zonsopkomst']];
const TH_OFF = [['-60', 'Een uur eerder donker'], ['-30', 'Half uur eerder donker'], ['-15', 'Kwartier eerder donker'], ['0', 'Bij zonsondergang'], ['15', 'Kwartier later donker'], ['30', 'Half uur later donker'], ['60', 'Een uur later donker']];
/* Plaatsen voor als de app je locatie niet weet (of je die niet wilt gebruiken) */
const TH_PLACES = [['auto', 'Mijn locatie (anders Rotterdam)'], ['rotterdam', 'Rotterdam', 51.92, 4.48], ['amsterdam', 'Amsterdam', 52.37, 4.9], ['den-haag', 'Den Haag', 52.08, 4.31],
  ['utrecht', 'Utrecht', 52.09, 5.12], ['eindhoven', 'Eindhoven', 51.44, 5.48], ['groningen', 'Groningen', 53.22, 6.57], ['maastricht', 'Maastricht', 50.85, 5.69],
  ['middelburg', 'Middelburg', 51.5, 3.61], ['leeuwarden', 'Leeuwarden', 53.2, 5.8], ['enschede', 'Enschede', 52.22, 6.89], ['brussel', 'Brussel', 50.85, 4.35]];
const TH_DEF = { mode: 'system', amoled: false, dark: '20:00', light: '07:00', off: 0, place: 'auto' };

/* ---------- puur rekenwerk (getest in tests/ui/thema-reken.test.js) ---------- */
function thMin(t){ const m = /^(\d{1,2}):(\d{2})$/.exec(String(t == null ? '' : t)); return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : -1; }
/* Is minuut min (van de dag) donker, met donker vanaf darkFrom en licht vanaf lightFrom? Ook over middernacht heen. */
function thInWindow(min, darkFrom, lightFrom){
  if (darkFrom === lightFrom) return false;
  return darkFrom > lightFrom ? (min >= darkFrom || min < lightFrom) : (min >= darkFrom && min < lightFrom);
}
/* Zonsopkomst en -ondergang (NOAA, "General Solar Position Calculations") voor een kalenderdag op lat/lon.
 * Geeft { rise, set } in ms (UTC-tijdstip), of { polar: 'night' } (zon komt niet op) / { polar: 'day' } (gaat niet onder). */
function thSun(y, m, d, lat, lon){
  const rad = Math.PI / 180, leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  const doy = Math.round((Date.UTC(y, m, d) - Date.UTC(y, 0, 1)) / 864e5) + 1, la = lat * rad;
  // Minuut (UTC, vanaf middernacht) van opkomst (s = 1) of ondergang (s = -1), met de zonnestand op uur h (UTC); null = pool
  const at = (h, s) => {
    const g = 2 * Math.PI / (leap ? 366 : 365) * (doy - 1 + (h - 12) / 24);
    const eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
    const decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
    const x = Math.cos(90.833 * rad) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl);
    if (!(x <= 1)) return 'night';
    if (x < -1) return 'day';
    return 720 - 4 * (lon + s * Math.acos(x) / rad) - eq;
  };
  const noon = 12 - lon / 15, r0 = at(noon, 1);
  if (typeof r0 === 'string') return { polar: r0 }; // poolnacht of middernachtzon
  const s0 = at(noon, -1), r1 = at(r0 / 60, 1), s1 = at(s0 / 60, -1); // tweede ronde: zonnestand op het moment zelf
  const base = Date.UTC(y, m, d);
  return { rise: base + (typeof r1 === 'number' ? r1 : r0) * 6e4, set: base + (typeof s1 === 'number' ? s1 : s0) * 6e4 };
}
function thDay(t, k){ const d = new Date(t); return new Date(d.getFullYear(), d.getMonth(), d.getDate() + k); }
/* Wissels [tijdstip, donker] van dag `from` tot en met `to` (0 = vandaag), op volgorde */
function thTrans(c, now, loc, from, to){
  const out = [];
  for (let k = from; k <= to; k++) {
    const d = thDay(now, k), y = d.getFullYear(), m = d.getMonth(), dd = d.getDate();
    if (c.mode === 'time') {
      const df = thMin(c.dark), lf = thMin(c.light);
      if (df < 0 || lf < 0 || df === lf) continue;
      out.push([new Date(y, m, dd, 0, lf).getTime(), false], [new Date(y, m, dd, 0, df).getTime(), true]);
    } else if (c.mode === 'sun' && loc) {
      const s = thSun(y, m, dd, loc.lat, loc.lon), off = (+c.off || 0) * 6e4;
      if (s.polar) { out.push([d.getTime(), s.polar === 'night']); continue; }
      const li = s.rise - off, da = s.set + off;
      if (li >= da) { out.push([d.getTime(), true]); continue; } // zo'n korte dag dat het met de verschuiving donker blijft
      out.push([li, false], [da, true]);
    }
  }
  return out.sort((a, b) => a[0] - b[0]);
}
/* De stand op tijdstip now: { dark, next } (next = volgende wissel in ms, 0 als die er niet binnen twee dagen is) */
function thDecide(c, now, sysDark, loc){
  if (c.mode === 'light' || c.mode === 'dark') return { dark: c.mode === 'dark', next: 0 };
  if (c.mode !== 'time' && c.mode !== 'sun') return { dark: !!sysDark, next: 0 };
  const tr = thTrans(c, now, loc, -1, 2);
  let dark = false, next = 0;
  for (const x of tr) if (x[0] <= now) dark = x[1];
  for (const x of tr) if (x[0] > now && x[1] !== dark) { next = x[0]; break; }
  return { dark, next };
}
/* De keuze, gecontroleerd (wat er ook in de opslag staat) */
function thClean(o){
  const c = Object.assign({}, TH_DEF, o && typeof o === 'object' ? o : {});
  if (!TH_MODES.some(x => x[0] === c.mode)) c.mode = 'system';
  if (thMin(c.dark) < 0) c.dark = TH_DEF.dark;
  if (thMin(c.light) < 0) c.light = TH_DEF.light;
  c.off = TH_OFF.some(x => +x[0] === +c.off) ? +c.off : 0;
  if (!TH_PLACES.some(x => x[0] === c.place)) c.place = 'auto';
  c.amoled = !!c.amoled;
  return { mode: c.mode, amoled: c.amoled, dark: c.dark.padStart(5, '0'), light: c.light.padStart(5, '0'), off: c.off, place: c.place };
}

/* ---------- in de app ---------- */
function thCfg(){ return thClean(store.get('theme', null)); }
let thState = null, thLocC = null;
/* Plek voor de zon: gekozen plaats, of de laatst bekende plek (grof, als de app locatie mag gebruiken), anders Rotterdam */
function thLoc(c){
  const p = TH_PLACES.find(x => x[0] === c.place && x.length > 2);
  if (p) return { lat: p[2], lon: p[3], name: p[1], own: false };
  if (!thLocC || Date.now() - thLocC.t > 30 * 6e4) {
    let v = ''; try { if (typeof Android !== 'undefined' && typeof Android.themeLocation === 'function') v = String(Android.themeLocation() || ''); } catch (e) { }
    const m = /^(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)$/.exec(v);
    thLocC = { t: Date.now(), p: m && Math.abs(+m[1]) <= 90 && Math.abs(+m[2]) <= 180 ? { lat: +m[1], lon: +m[2] } : null };
  }
  return thLocC.p ? { lat: thLocC.p.lat, lon: thLocC.p.lon, name: 'je locatie', own: true } : { lat: 51.92, lon: 4.48, name: 'Rotterdam', own: false, fallback: true };
}
function thSys(){ try { return matchMedia('(prefers-color-scheme: dark)').matches; } catch (e) { return false; } }
/* Donker of licht, zoals de app nu getoond wordt (gebruik dit in plaats van matchMedia('(prefers-color-scheme: dark)')) */
function uiDark(){ return (thState || thApply(true)).dark; }
/* Opnieuw uitrekenen en toepassen. quiet = zonder overgang (bij het starten). */
function thApply(quiet){
  const c = thCfg(), now = Date.now(), loc = c.mode === 'sun' ? thLoc(c) : null;
  const r = thDecide(c, now, thSys(), loc), forced = c.mode === 'system' ? null : r.dark, amoled = c.amoled && r.dark;
  const prev = thState;
  thState = { dark: r.dark, next: r.next, forced, amoled, mode: c.mode, loc };
  const changed = !prev || prev.dark !== r.dark || prev.forced !== forced || prev.amoled !== amoled;
  if (changed) {
    if (prev && !quiet && prev.dark !== r.dark) thAnim();
    thCss(forced); thAmoled(amoled);
    document.documentElement.dataset.thema = r.dark ? 'donker' : 'licht';
    // Voor body-begin.html bij de volgende start (niet in backups: zie WS_SKIP)
    try { localStorage.setItem('rt.themeNow', JSON.stringify({ dark: forced, until: c.mode === 'time' || c.mode === 'sun' ? (r.next || now + 864e5) : 0 })); } catch (e) { }
  }
  thNative(c, now, loc);
  if (changed && prev) {
    if (typeof bars === 'function') { bars.last = null; bars(); }
    try { window.dispatchEvent(new CustomEvent('themechange', { detail: { dark: r.dark } })); } catch (e) { }
  }
  if (changed) thQuickIcon();
  if (typeof current !== 'undefined' && current === 'setlook') thRender();
  return thState;
}
/* Na een nieuwe stylesheet (zoals #dyn-theme) de themaregels opnieuw vastzetten */
function thCssNow(){ if (thState) thCss(thState.forced); }
function thAnim(){
  const h = document.documentElement; h.classList.add('th-anim');
  clearTimeout(thAnim.t); thAnim.t = setTimeout(() => h.classList.remove('th-anim'), 600);
}
/* Zwart (AMOLED): bij donker de achtergrondrollen zwart (:root:root wint van stijl.css en #dyn-theme) */
function thAmoled(on){
  let st = document.getElementById('th-amoled');
  const css = on ? ':root:root{--bg:#000000;--card:#111416;--hi:#0B0D0E;--line:#262C30;--chip:#1C2226}' : '';
  if (!st && !on) return;
  if (!st) { st = document.createElement('style'); st.id = 'th-amoled'; document.head.appendChild(st); }
  if (st.textContent !== css) st.textContent = css;
}
/* Java: de keuze plus (bij de zon) een rooster van wissels voor ruim vier weken */
function thNative(c, now, loc){
  if (typeof Android === 'undefined' || typeof Android.themeSet !== 'function') return;
  const key = JSON.stringify([c, loc && [loc.lat, loc.lon], new Date(now).toDateString()]);
  if (key === thNative.key) return;
  thNative.key = key;
  const o = { mode: c.mode, amoled: c.amoled };
  if (c.mode === 'time') o.win = [thMin(c.dark), thMin(c.light)];
  if (c.mode === 'sun' && loc) {
    const tr = thTrans(c, now, loc, -1, 31), mod = t => { const d = new Date(t); return d.getHours() * 60 + d.getMinutes(); };
    o.sched = tr.map(x => [Math.round(x[0]), x[1] ? 1 : 0]);
    o.end = thDay(now, 32).getTime();
    // Daarna (app lang niet open geweest): het venster van de laatste dag ([0, 1440] = de hele dag donker)
    const d31 = thDay(now, 31).getTime(), last = tr.filter(x => x[0] >= d31);
    const ld = last.find(x => x[1] && x[0] !== d31), ll = last.find(x => !x[1] && x[0] !== d31);
    o.win = ld && ll ? [mod(ld[0]), mod(ll[0])] : last.length && last[0][1] ? [0, 1440] : [0, 0];
  }
  try { Android.themeSet(JSON.stringify(o)); } catch (e) { }
}

/* ---------- scherm Weergave ---------- */
function thHm(t){ const d = new Date(t); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); }
function thWhen(t){
  const k = Math.round((thDay(t, 0) - thDay(Date.now(), 0)) / 864e5);
  return (k === 0 ? 'om ' : k === 1 ? 'morgen om ' : 'over ' + k + ' dagen om ') + thHm(t);
}
function thModeName(v){ const x = TH_MODES.find(m => m[0] === v); return x ? x[1] : v; }
/* "Nu: licht · wordt donker om 19:42" */
function thStatus(){
  const s = thState || thApply(true), now = s.dark ? 'donker' : 'licht';
  if (s.mode === 'system') return 'Nu: ' + now + ', zoals je telefoon';
  if (s.mode === 'light' || s.mode === 'dark') return 'Nu: ' + now + ' (altijd)';
  return 'Nu: ' + now + (s.next ? ' · wordt ' + (s.dark ? 'licht ' : 'donker ') + thWhen(s.next) : s.mode === 'sun' ? ' (de zon gaat hier nu niet op of onder)' : '');
}
function thSunNote(c){
  const loc = thLoc(c), d = new Date(), s = thSun(d.getFullYear(), d.getMonth(), d.getDate(), loc.lat, loc.lon);
  const where = loc.own ? 'op je laatst bekende locatie' : 'in ' + loc.name + (c.place === 'auto' ? ' (je locatie is niet bekend)' : '');
  const sun = s.polar ? (s.polar === 'night' ? 'Vandaag komt de zon niet op ' : 'Vandaag gaat de zon niet onder ') + where : 'Vandaag zon op ' + thHm(s.rise) + ', onder ' + thHm(s.set) + ' ' + where;
  return sun + '. Berekend op de telefoon, zonder internet.';
}
function thRender(){
  const sel = $('#st-theme'); if (!sel) return;
  const c = thCfg();
  if (!sel.options.length) sel.innerHTML = TH_MODES.map(m => '<option value="' + m[0] + '">' + esc(m[1]) + '</option>').join('');
  const off = $('#st-th-off'); if (!off.options.length) off.innerHTML = TH_OFF.map(m => '<option value="' + m[0] + '">' + esc(m[1]) + '</option>').join('');
  const pl = $('#st-th-loc'); if (!pl.options.length) pl.innerHTML = TH_PLACES.map(m => '<option value="' + m[0] + '">' + esc(m[1]) + '</option>').join('');
  sel.value = c.mode; off.value = String(c.off); pl.value = c.place;
  if (document.activeElement !== $('#st-th-dark')) $('#st-th-dark').value = c.dark;
  if (document.activeElement !== $('#st-th-light')) $('#st-th-light').value = c.light;
  $('#st-th-time').hidden = c.mode !== 'time';
  $('#st-th-sun').hidden = c.mode !== 'sun';
  if (c.mode === 'sun') $('#st-th-sunnote').textContent = thSunNote(c);
  const a = $('#st-amoled'); a.classList.toggle('on', c.amoled); a.setAttribute('aria-checked', c.amoled ? 'true' : 'false');
  const st = $('#st-th-now'), t = thStatus(); if (st.textContent !== t) st.textContent = t;
}
function thSave(patch){ store.set('theme', thClean(Object.assign(thCfg(), patch))); thApply(); thRender(); }
function thSetMode(v){ thSave({ mode: v }); toast('Thema: ' + thModeName(thCfg().mode).replace(/ \(.*\)$/, '').toLowerCase()); }
function thSetVal(k, v){
  if ((k === 'dark' || k === 'light') && thMin(v) < 0) { thRender(); return; }
  thSave({ [k]: k === 'off' ? +v : k === 'amoled' ? !!v : v });
}
/* Snel wisselen (knop in de kop van Instellingen): donker ⇄ licht, met Ongedaan maken */
function thQuick(){
  const prev = thCfg().mode, to = uiDark() ? 'light' : 'dark';
  thSave({ mode: to });
  undoToast('Thema: ' + (to === 'dark' ? 'donker' : 'licht'), () => thSave({ mode: prev }));
}
function thQuickIcon(){
  const b = document.getElementById('st-th-quick'); if (!b || !thState) return;
  const i = b.querySelector('.ui-icon'); if (i) i.dataset.icon = thState.dark ? 'sun' : 'moon';
  b.setAttribute('aria-label', thState.dark ? 'Licht thema' : 'Donker thema');
}

thApply(true);
try { const mq = matchMedia('(prefers-color-scheme: dark)'); if (mq.addEventListener) mq.addEventListener('change', () => thApply()); else if (mq.addListener) mq.addListener(() => thApply()); } catch (e) { }
setInterval(() => thApply(), 60000); // automatisch: elke minuut (alleen als de app zichtbaar is; bij terugkomen via applyTheme)
try { new MutationObserver(() => { if (thState && thState.forced != null) thCss(thState.forced); }).observe(document.head, { childList: true, subtree: true, characterData: true }); } catch (e) { }
