/* ---------- Versie & updates ---------- */
function ver(){ try { return JSON.parse(Android.version()); } catch(e){ return {}; } }
function renderInfo(){
  const v = ver();
  $('#i-ver').textContent = v.webName + ' (' + v.webCode + ')';
  $('#i-app').textContent = v.appName + ' (' + v.appCode + ')';
  $('#i-android').textContent = v.android;
  let log = CHANGELOG;
  try { const m = JSON.parse(Android.lastManifest()); if (m && m.changelog && m.versionCode >= VERSION_CODE) log = m.changelog; } catch(e){}
  $('#log').innerHTML = log.map(e => '<h3>Versie ' + esc(e.version) + ' <small>' + esc(e.date||'') + '</small></h3><ul>' + e.changes.map(c=>'<li>'+esc(c)+'</li>').join('') + '</ul>').join('');
}
function checkUpdates(){ $('#chk-btn').textContent = 'Bezig met controleren…'; Android.checkUpdates(); }

/* Voortgang van een update: in de balk op Home en bij Versie en updates (downloaden, controleren, installeren) */
let updAvail = '', updKind = '', updPhase = '';
function updMB(b){ return (Math.max(0, b) / 1048576).toFixed(1).replace('.', ',') + ' MB'; }
/** Tekst en percentage (-1 = onbekend) bij een stap. Zuiver. */
function updStep(r){
  const pct = r.total > 0 ? Math.min(100, Math.floor(r.done / r.total * 100)) : -1;
  const size = pct >= 0 ? ' ' + pct + '% (' + updMB(r.done) + ' van ' + updMB(r.total) + ')' : r.done > 0 ? ' ' + updMB(r.done) : '';
  switch (r.phase) {
    case 'start': return ['Nieuwe versie ophalen…', -1];
    case 'download': return [(r.kind === 'apk' ? 'Downloaden…' : 'Interface bijwerken…') + size, pct];
    case 'download2': return ['Startscherm bijwerken…' + size, pct];
    case 'check': return ['Controleren of de download klopt…', -1];
    case 'install': return ['Klaarzetten voor installatie…', -1];
    case 'committed': return ['Installeren… de app start zo opnieuw', -1];
    case 'save': return ['Bijna klaar…', -1];
    default: return ['Bezig…', -1];
  }
}
/** Balken bijwerken: text, pct (-1 = bezig zonder percentage, null = geen balk), knop (null = verbergen). */
function updShow(text, pct, btn, live){
  ['', '2'].forEach(n => {
    const b = $('#upd-banner' + n); if (!b) return;
    b.classList.add('on');
    $('#upd-text' + n).textContent = text;
    const p = $('#upd-prog' + n);
    p.hidden = pct === null;
    p.classList.toggle('busy', pct === -1);
    if (pct >= 0) { p.querySelector('i').style.width = pct + '%'; p.setAttribute('aria-valuenow', pct); } else { p.querySelector('i').style.width = ''; p.removeAttribute('aria-valuenow'); }
    const k = $('#upd-btn' + n);
    k.style.display = btn === null ? 'none' : '';
    if (btn) { k.textContent = btn[0]; k.disabled = !!btn[1]; }
    if (live) $('#upd-live' + n).textContent = live;
  });
}
function updAvailShow(){
  if (updAvail) updShow('Nieuwe versie ' + updAvail + ' is beschikbaar', null, ['Installeren', false]);
  else ['', '2'].forEach(n => { const b = $('#upd-banner' + n); if (b) b.classList.remove('on'); });
}
function installUpdate(){
  if (updKind === 'apk' && updPhase && updPhase !== 'confirm') { toast('De update wordt al gedownload'); return; }
  updKind = 'apk'; updPhase = 'start';
  updShow('Nieuwe versie ophalen…', -1, ['Bezig…', true], 'De update wordt gedownload');
  Android.installUpdate();
}
window.onUpdateStatus = function(r){
  if (r.state === 'progress') {
    const [text, pct] = updStep(r), first = updKind !== r.kind || updPhase !== r.phase;
    updKind = r.kind; updPhase = r.phase;
    updShow(text, pct, r.kind === 'apk' ? ['Bezig…', true] : null, first ? text.replace(/\s(\d+%|\d+,\d MB).*$/, '') : '');
    if (r.kind === 'web' && $('#chk-btn')) $('#chk-btn').textContent = text;
    return;
  }
  const wasKind = updKind;
  updKind = ''; updPhase = '';
  $('#chk-btn').textContent = 'Controleren op updates';
  if (r.state === 'confirm') {
    // Android toont nu zijn eigen venster; afgebroken = opnieuw kunnen
    updKind = 'apk'; updPhase = 'confirm';
    updShow('Bevestig de installatie in het venster van Android', null, ['Installeren', false], 'Bevestig de installatie in het venster van Android');
    return;
  }
  if (r.state === 'apk') { updAvail = r.versionName; updAvailShow(); }
  else if (r.state === 'busy') { updKind = 'apk'; updPhase = 'download'; toast('De update wordt al gedownload'); }
  else {
    if (wasKind) updAvailShow(); // voortgang weg: terug naar "beschikbaar" (of niets)
    if (r.state === 'uptodate' && r.manual) toast('Je hebt de nieuwste versie (' + ver().webName + ') ✓');
    else if (r.state === 'needs-permission') toast('Sta "Installeren van onbekende apps" toe en tik daarna nog eens op Installeren');
    else if (r.state === 'error' && (r.manual || wasKind === 'apk' || String(r.error).indexOf('nstall') >= 0 || String(r.error).indexOf('pdate') >= 0)) toast(r.error);
  }
};

/* Licenties: van de app en van de onderdelen van anderen (de teksten zitten ook in de app, in assets/licenties/) */
function infoLicenses(){
  const G = 'https://github.com/renevanes/renes-tools/blob/main/';
  openSheet('Licenties', 'Tools: GPL-3.0-or-later. Onderdelen van anderen staan hieronder.', [
    ['Tools — GNU GPL versie 3', () => Android.openUrl(G + 'LICENSE')],
    ['Leaflet (kaarten) — BSD-2', () => Android.openUrl(G + 'web/vendor/LEAFLET-LICENSE')],
    ['jsQR (QR-codes lezen) — Apache-2.0', () => Android.openUrl(G + 'web/vendor/JSQR-LICENSE')],
    ['whisper.cpp (spraak naar tekst) — MIT', () => Android.openUrl(G + 'app/lib/arm64-v8a/LICENSE-whisper.cpp.txt')],
    ['llama.cpp (lokale AI) — MIT', () => Android.openUrl(G + 'app/lib/arm64-v8a/LICENSE-llama.cpp.txt')],
    ['musl (in whisper.cpp en llama.cpp) — MIT', () => Android.openUrl(G + 'app/lib/arm64-v8a/LICENSE-musl.txt')]
  ]);
}
