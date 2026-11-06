// Bewaker: alle scripts uit web/src/script komen samen in één <script>. Twee functies met dezelfde naam op het hoogste
// niveau geven dan geen fout: de laatste wint stil (zo verdween ooit de lijst recente nummers van Auto redial).
// Als ES-module gelezen is een dubbele naam op het hoogste niveau wel een fout ("has already been declared"), dus:
// alles in de volgorde van web/src/volgorde.txt samenvoegen en met `node --check` als module controleren.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const root = path.join(__dirname, '..', '..');
const errs = [];
const delen = fs.readFileSync(path.join(root, 'web/src/volgorde.txt'), 'utf8').split('\n').map(x => x.trim()).filter(x => /^script\/.+\.js$/.test(x));
if (delen.length < 50) errs.push('te weinig scripts gevonden in volgorde.txt: ' + delen.length);
const bron = delen.map(f => fs.readFileSync(path.join(root, 'web/src', f), 'utf8').replace(/^<script>/, '')).join('\n;\n');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rt-namen-'));
const bestand = path.join(tmp, 'alles.mjs');
fs.writeFileSync(bestand, bron);
const r = spawnSync(process.execPath, ['--check', bestand], { encoding: 'utf8' });
fs.rmSync(tmp, { recursive: true, force: true });
if (r.status !== 0) {
  const m = (r.stderr || '').match(/SyntaxError: [^\n]+/);
  errs.push('scripts samen niet geldig: ' + (m ? m[0] : r.stderr.slice(0, 300)));
} else console.log('✓ ' + delen.length + ' scripts: geen dubbele namen op het hoogste niveau');
// En de bewaker zelf werkt: twee keer dezelfde functie wordt gezien
const tmp2 = fs.mkdtempSync(path.join(os.tmpdir(), 'rt-namen-'));
fs.writeFileSync(path.join(tmp2, 'proef.mjs'), 'function renderRecent(){}\nfunction renderRecent(){}\n');
const p = spawnSync(process.execPath, ['--check', path.join(tmp2, 'proef.mjs')], { encoding: 'utf8' });
fs.rmSync(tmp2, { recursive: true, force: true });
if (p.status === 0 || !/already been declared/.test(p.stderr)) errs.push('de bewaker ziet een dubbele functie niet');
else console.log('✓ bewaker ziet een dubbele functie');
console.log('errors', errs);
