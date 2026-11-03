// Thema: het rekenwerk uit 02-thema.js, zonder browser. Zonsopkomst en -ondergang in Rotterdam (binnen 3 minuten van
// gepubliceerde tijden), de poolstreken, het venster over middernacht en de keuze licht/donker.
process.env.TZ = 'Europe/Amsterdam';
const fs = require('fs'), vm = require('vm'), path = require('path');
const src = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'src', 'script', '02-thema.js'), 'utf8');
const pure = src.split('/* ---------- in de app ---------- */')[0];
const T = vm.runInNewContext(pure + ';({ thMin, thInWindow, thSun, thTrans, thDecide, thClean, thDay })', {});
const errs = [];
const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
const hm = t => { const d = new Date(t); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
const mins = s => +s.slice(0, 2) * 60 + +s.slice(3);

// Rotterdam (51,92 N, 4,48 O), gepubliceerd door sunrisesunsettime.org (lokale tijd, CET/CEST)
const ROT = { lat: 51.92, lon: 4.48 };
const PUB = [[2026, 0, 25, '08:33', '17:17'], [2026, 2, 20, '06:46', '18:55'], [2026, 5, 21, '05:23', '22:06'], [2026, 8, 22, '07:28', '19:43'],
  [2026, 10, 3, '07:41', '17:12'], [2026, 11, 21, '08:48', '16:33'], [2026, 2, 29, '07:25', '20:10'], [2026, 9, 25, '07:24', '17:29']];
// en door timeanddate.com (januari 2026)
PUB.push([2026, 0, 25, '08:31', '17:17'], [2026, 0, 28, '08:27', '17:22'], [2026, 0, 31, '08:23', '17:28']);
const near = t => hm(Math.round(t / 6e4) * 6e4); // op de minuut afgerond, zoals de tabellen
for (const [y, m, d, r, s] of PUB) {
  const x = T.thSun(y, m, d, ROT.lat, ROT.lon);
  const dr = Math.abs(mins(near(x.rise)) - mins(r)), ds = Math.abs(mins(near(x.set)) - mins(s));
  ok(dr <= 3 && ds <= 3, 'Rotterdam ' + d + '-' + (m + 1) + '-' + y + ': op ' + near(x.rise) + ' (gepubliceerd ' + r + '), onder ' + near(x.set) + ' (' + s + ')');
}
// Poolstreken: Tromsø en Longyearbyen; en het zuidelijk halfrond (Sydney, december = zomer)
ok(T.thSun(2026, 11, 21, 69.65, 18.96).polar === 'night', 'Tromsø 21 december: poolnacht');
ok(T.thSun(2026, 5, 21, 69.65, 18.96).polar === 'day', 'Tromsø 21 juni: middernachtzon');
ok(T.thSun(2026, 5, 21, 78.22, 15.65).polar === 'day' && T.thSun(2026, 0, 10, 78.22, 15.65).polar === 'night', 'Longyearbyen: zomer licht, winter donker');
ok(T.thSun(2026, 5, 21, -90, 0).polar === 'night' && T.thSun(2026, 11, 21, 90, 0).polar === 'night', 'de polen zelf');
{ const s = T.thSun(2026, 11, 21, -33.87, 151.21), len = (s.set - s.rise) / 36e5; ok(len > 14 && len < 15, 'Sydney 21 december: lange dag (' + len.toFixed(1) + ' uur)'); }
{ const s = T.thSun(2026, 2, 20, 0, 0), len = (s.set - s.rise) / 36e5; ok(len > 12 && len < 12.3, 'evenaar bij de equinox: ±12 uur (' + len.toFixed(2) + ')'); }

// Venster over middernacht
ok(T.thInWindow(1200, 1200, 420) && T.thInWindow(0, 1200, 420) && T.thInWindow(419, 1200, 420) && !T.thInWindow(420, 1200, 420) && !T.thInWindow(1199, 1200, 420), 'donker 20:00–07:00 over middernacht');
ok(T.thInWindow(60, 60, 300) && !T.thInWindow(300, 60, 300) && !T.thInWindow(30, 60, 300), 'donker 01:00–05:00 binnen één dag');
ok(!T.thInWindow(600, 600, 600), 'zelfde tijd: nooit donker');
ok(T.thMin('7:05') === 425 && T.thMin('24:00') === -1 && T.thMin('x') === -1 && T.thMin(null) === -1, 'tijden lezen');

// Keuze licht/donker op een bepaald moment
const at = (d, h, mi) => new Date(2026, 10, d, h, mi).getTime();
const time = T.thClean({ mode: 'time', dark: '20:00', light: '07:00' });
let r = T.thDecide(time, at(3, 19, 59), false, null);
ok(!r.dark && hm(r.next) === '20:00' && new Date(r.next).getDate() === 3, 'op tijd 19:59: licht, donker om 20:00');
r = T.thDecide(time, at(3, 20, 0), false, null);
ok(r.dark && hm(r.next) === '07:00' && new Date(r.next).getDate() === 4, 'op tijd 20:00: donker tot morgen 07:00');
r = T.thDecide(time, at(4, 3, 0), false, null);
ok(r.dark && hm(r.next) === '07:00' && new Date(r.next).getDate() === 4, 'op tijd 03:00: nog donker (over middernacht)');
r = T.thDecide(time, at(4, 7, 0), true, null);
ok(!r.dark, 'op tijd 07:00: licht, ook als de telefoon donker is');
// Zomertijd: wissel op 29 maart 2026 om 02:00 → 03:00; om 20:00 lokale tijd blijft 20:00
r = T.thDecide(time, new Date(2026, 2, 29, 19, 0).getTime(), false, null);
ok(!r.dark && hm(r.next) === '20:00', 'op tijd: klopt op de dag van de zomertijd');
ok(T.thDecide(T.thClean({ mode: 'dark' }), at(3, 12, 0), false, null).dark && !T.thDecide(T.thClean({ mode: 'light' }), at(3, 23, 0), true, null).dark, 'altijd donker / altijd licht');
ok(T.thDecide(T.thClean({ mode: 'system' }), 0, true, null).dark && !T.thDecide(T.thClean({}), 0, false, null).dark, 'systeem volgt de telefoon');
const sun = T.thClean({ mode: 'sun' });
r = T.thDecide(sun, at(3, 17, 0), false, ROT);
ok(!r.dark && hm(r.next) >= '17:09' && hm(r.next) <= '17:15', 'zon 3 november 17:00: licht, donker om ' + hm(r.next));
r = T.thDecide(sun, at(3, 17, 30), false, ROT);
ok(r.dark && hm(r.next) >= '07:39' && hm(r.next) <= '07:45' && new Date(r.next).getDate() === 4, 'zon 17:30: donker tot ' + hm(r.next) + ' morgen');
r = T.thDecide(T.thClean({ mode: 'sun', off: -30 }), at(3, 16, 50), false, ROT);
ok(r.dark, 'zon, half uur eerder donker: om 16:50 al donker');
r = T.thDecide(T.thClean({ mode: 'sun', off: 30 }), at(3, 17, 30), false, ROT);
ok(!r.dark && hm(r.next) >= '17:39' && hm(r.next) <= '17:45', 'zon, half uur later donker: om 17:30 nog licht');
r = T.thDecide(sun, at(3, 12, 0), false, { lat: 69.65, lon: 18.96 });
ok(T.thDecide(sun, new Date(2026, 11, 21, 12, 0).getTime(), false, { lat: 69.65, lon: 18.96 }).dark, 'zon in Tromsø, 21 december 12:00: donker (poolnacht)');
ok(!T.thDecide(sun, new Date(2026, 5, 21, 1, 0).getTime(), true, { lat: 69.65, lon: 18.96 }).dark, 'zon in Tromsø, 21 juni 01:00: licht (middernachtzon)');
// Opslag die niet klopt
const c = T.thClean({ mode: 'raar', dark: '99:99', off: 7, place: 'mars', amoled: 'ja' });
ok(c.mode === 'system' && c.dark === '20:00' && c.off === 0 && c.place === 'auto' && c.amoled === true && T.thClean(null).light === '07:00', 'onbekende waarden: standaard');
// Rooster voor Java: elke dag een wissel naar licht en naar donker, op volgorde
const tr = T.thTrans(sun, at(3, 12, 0), ROT, -1, 31);
ok(tr.length === 66 && tr.every((x, i) => !i || x[0] > tr[i - 1][0]) && tr.every((x, i) => x[1] === (i % 2 === 1)), 'rooster van 33 dagen, licht en donker om en om');
console.log('errors', errs);
if (errs.length) process.exit(1);
