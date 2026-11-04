// Dekking van de Engelse vertaling (tools/taal.js, web/src/taal/en.json):
// - elke Nederlandse tekst (schermen, vensters, start.html, t()/tn(), meldingen, Taal.t/Taal.T in Java) heeft een vertaling;
// - de vertalingen kloppen (plaatshouders, HTML, geen heen-en-weer-vertaling, geen verboden woord);
// - bewaker: geen aan elkaar geplakte Nederlandse tekst zonder t() (uitzonderingen alleen met reden:
//   tools/taal-uitzonderingen.txt of "taal-vast: reden" in de bron);
// - ongebruikte vertalingen zijn alleen een waarschuwing;
// - het taal-kern-blok staat letterlijk gelijk in 02-taal.js en de Telefoon-skin (start.html).
const fs = require('fs');
const path = require('path');
const taal = require('../../tools/taal.js');
(async () => {
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const r = await taal.check();
  for (const e of r.errs) console.log('  ✗ ' + e);
  for (const w of r.warns.slice(0, 15)) console.log('  · ' + w);
  if (r.warns.length > 15) console.log('  · … en nog ' + (r.warns.length - 15) + ' waarschuwingen');
  ok(r.total > 3000 && r.translated === r.total, 'teksten met een Engelse vertaling: ' + r.translated + ' van ' + r.total + ' (' + r.dict + ' in het woordenboek, ' + r.unused + ' ongebruikt)');
  ok(!r.errs.length, 'woordenboek en bewaker in orde' + (r.errs.length ? ': ' + r.errs.length + ' fouten (zie boven)' : ''));

  // Het woordenboek zelf: geldige JSON, gesorteerd, één sleutel per regel (dan botsen agents er niet op)
  const raw = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'src', 'taal', 'en.json'), 'utf8');
  const keys = Object.keys(JSON.parse(raw));
  const sorted = keys.slice().sort((a, b) => a.localeCompare(b, 'nl'));
  ok(keys.join('\n') === sorted.join('\n'), 'en.json is gesorteerd (localeCompare nl)');
  ok(raw.split('\n').length === keys.length + 3, 'en.json: één vertaling per regel');

  // De kern in 02-taal.js en start.html is letterlijk gelijk
  const kern = f => { const s = fs.readFileSync(path.join(__dirname, '..', '..', f), 'utf8'); const m = /\/\* taal-kern: begin[\s\S]*?\/\* taal-kern: eind \*\//.exec(s); return m ? m[0] : ''; };
  const a = kern('web/src/script/02-taal.js'), b = kern('web/start.html');
  ok(a.length > 1000 && a === b, 'taal-kern gelijk in 02-taal.js en start.html (' + a.length + ' tekens)');

  // De bewaker vangt wat hij moet vangen (op een stukje voorbeeldcode)
  const proef = "function proef(n, x){\n  toast('Klaar: ' + n + ' bestanden gewist');\n  el.innerHTML = '<p>' + n + ' berichten van ' + x + '</p>';\n  el.setAttribute('aria-label', 'Wissen ' + x);\n  toast(t('Klaar: {n} bestanden gewist', { n }));\n  el.innerHTML = '<b>' + esc(x) + '</b><small>' + t('Nog niets') + '</small>';\n  el.textContent = 'Wacht op ' + x; // taal-vast: proef van een uitzondering\n}\n";
  const g = taal.jsScan([['proef.js', proef]]).guard;
  const lines = g.map(([w]) => +w.split(':').pop()).sort();
  ok(lines.join() === '2,3,4', 'bewaker vangt toast, innerHTML en aria-label met aan elkaar geplakt Nederlands, en laat t() en taal-vast staan (regels ' + lines.join(',') + ')');
  console.log('errors', errs);
})().catch(e => { console.log('errors', [String(e && e.stack || e)]); process.exit(1); });
