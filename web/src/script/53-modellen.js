/* ---------- Modellen beheren ----------
 * Per soort (categorie): wat het doet, of je het nodig hebt, welke versie je hebt en welke er is. Downloaden gaat
 * altijd door de app zelf (verder waar hij was, voortgang in de meldingenbalk); daar is niets aan in te stellen. */
let mdSt = {}, mdPollT = null;
const MD_CATS = [
  { kind: 'ai', title: 'Assistent (taalmodel)', icon: 'message', what: 'Het model waarmee de assistent antwoordt, schrijft, samenvat en vertaalt.',
    need: 'Nodig voor de assistent: één taalmodel. Klein en snel is genoeg; Groter en slimmer geeft betere antwoorden maar is trager en vraagt 8 GB werkgeheugen.' },
  { kind: 'spraak', title: 'Spraak naar tekst', icon: 'mic', what: 'Maakt tekst van wat er gezegd wordt: gesprekken uitschrijven, spraaknotities, Slaappraat en vragen inspreken bij de assistent.',
    need: 'Nodig voor die functies: één spraakmodel. Snel is genoeg; Nauwkeurig maakt minder fouten maar is trager.' },
  { kind: 'zoek', title: 'Slim zoeken', icon: 'search', what: 'Zoeken op betekenis in je notities, gesprekken en berichten, ook voor de assistent ("Met mijn gegevens").',
    need: 'Niet nodig om de assistent te gebruiken; alleen als je slim zoeken wilt.' },
  { kind: 'tts', title: 'Voorlezen (tekst naar spraak)', icon: 'volume', what: 'De assistent leest antwoorden voor met de stem van je telefoon.',
    need: 'Hiervoor downloadt de app niets: Android levert de stem.' }
];
function mdLoad(){ mdSt = rdJson(Android.modelsState(), {}); return mdSt; }
function mdGB(b){ b = Math.max(0, +b || 0); return b >= 1e9 ? (b / 1e9).toFixed(1).replace('.', ',') + ' GB' : Math.max(1, Math.round(b / 1e6)) + ' MB'; }
/** "vandaag gebruikt", "3 dagen geleden gebruikt" … Zuiver. */
function mdAgo(t, now){
  if (!t) return 'nog niet gebruikt';
  const d = Math.floor(((now || Date.now()) - t) / 86400000);
  return d <= 0 ? 'vandaag gebruikt' : d === 1 ? 'gisteren gebruikt' : d < 31 ? d + ' dagen geleden gebruikt' : 'meer dan een maand niet gebruikt';
}
function mdBusy(m){ return ['running', 'queued', 'paused', 'verifying', 'failed'].includes(m.state); }
function mdModels(){ return mdSt.models || []; }
function mdFind(key){ return mdModels().find(m => m.key === key) || {}; }
function mdPct(m){ return m.state === 'verifying' ? (m.pct || 0) : m.total > 0 ? Math.min(100, m.bytes / m.total * 100) : 0; }
/** Naam van een soort model zonder versie: "Klein en snel (Qwen3.5)" → "Klein en snel". */
function mdLineName(m){ return String(m.label || '').replace(/\s*\(.*\)$/, '').replace(/^Spraakmodel /, '').replace(/^./, c => c.toUpperCase()); }
/**
 * De modellen van één soort als lijnen: per lijn de nieuwste versie eerst, dan de oudere die hij vervangt.
 * Zuiver (krijgt de lijst mee).
 */
function mdLines(all, kind){
  const ms = all.filter(m => m.kind === kind);
  return ms.filter(m => !ms.some(x => x.replaces === m.id)).map(n => {
    const chain = [n]; let p = n;
    for (let i = 0; p.replaces && i < 8; i++) { const o = ms.find(x => x.id === p.replaces); if (!o) break; chain.push(o); p = o; }
    return chain;
  });
}

/** De stand van een download in één regel. Zuiver. */
function mdStateTxt(m){
  const part = m.total > 0 ? Math.floor(m.bytes / m.total * 100) + '% · ' + mdGB(m.bytes) + ' van ' + mdGB(m.total) : '';
  switch (m.state) {
    case 'running': return 'Downloaden: ' + (part || 'bezig');
    case 'queued': return 'Wacht op zijn beurt';
    case 'paused': return (m.reason || 'Onderbroken') + (part ? ' · ' + part : '');
    case 'verifying': return 'Controleren… ' + (m.pct || 0) + '%';
    case 'failed': return 'Mislukt: ' + (m.reason || 'onbekende fout');
  }
  return '';
}
/** Uitleg bij een download die stilstaat. Zuiver (krijgt de stand van het netwerk mee). */
function mdHint(m, net){
  if (m.state === 'paused' && m.waiting) return 'De app wacht even en gaat vanzelf verder zodra het weer mag.';
  if ((m.state === 'paused' || m.state === 'queued') && /wifi/i.test(m.reason || ''))
    return 'Je zit niet op wifi. Het gaat verder als je de app op wifi opent, of zet hieronder Alleen via wifi uit.';
  if (m.state === 'paused' && /roaming/i.test(m.reason || '')) return 'In het buitenland (roaming) downloadt de app geen modellen.';
  if (m.state === 'paused' && net && !net.online) return 'Er is nu geen internet. Het gaat verder als je de app weer opent met internet, of tik op Verder.';
  if (m.state === 'paused' && /Verder/.test(m.reason || '')) return '';
  if (m.state === 'paused') return 'Tik op Verder om door te gaan waar hij was (dat gebeurt ook vanzelf als je de app opent).';
  return '';
}
/** Knop; aria = de naam voor een schermlezer (met het model erbij, want er staan er meer van op het scherm). */
function mdBtn(label, act, on, aria){ return '<button class="mini' + (on ? ' on' : '') + '" onclick="' + act + '"' + (aria ? ' aria-label="' + esc(aria) + '"' : '') + '>' + label + '</button>'; }
function mdLeave(){ clearTimeout(mdPollT); }

function enterModels(){
  if (typeof Android.aiResume === 'function') Android.aiResume();
  mdResumed();
}
/** Terug in de app (ook na de instellingen van de stem): opnieuw kijken, en straks nog eens (een download kan net verder gaan). */
function mdResumed(){
  mdLoad();
  const t = mdSt.tts || {};
  if (t.state !== 'ok' && t.state !== 'checking' && typeof Android.ttsCheck === 'function') Android.ttsCheck();
  mdRefresh();
  setTimeout(() => { if (current === 'models') mdRefresh(); }, 800);
}
function mdRefresh(){
  mdLoad();
  if (current === 'models') mdRender();
  clearTimeout(mdPollT);
  if (current === 'models' && (mdModels().some(m => ['running', 'queued', 'verifying'].includes(m.state) || m.waiting) || (mdSt.tts || {}).state === 'checking')) mdPollT = setTimeout(mdRefresh, 1500);
}
const _mdOnAiModels = window.onAiModels;
window.onAiModels = function(){ if (_mdOnAiModels) _mdOnAiModels(); if (current === 'models') mdRefresh(); };

function mdRender(){
  const s = mdSt;
  // Opslag
  const used = +s.used || 0, free = +s.free || 0, share = used + free > 0 ? used / (used + free) * 100 : 0;
  $('#md-sum').innerHTML = '<h2><span class="ui-icon" data-icon="folder" aria-hidden="true"></span>Opslag</h2>' +
    '<div class="md-bar" role="img" aria-label="' + esc('Modellen ' + mdGB(used) + ', vrij ' + mdGB(free)) + '"><i style="width:' + share.toFixed(1) + '%"></i></div>' +
    '<p class="md-line"><b>' + (used ? 'Modellen: ' + mdGB(used) : 'Nog geen modellen') + '</b><span>' + (free > 0 ? 'Vrij op je telefoon: ' + mdGB(free) : '') + '</span></p>' +
    (s.leftovers >= 1e6 ? '<div class="sw"><span>Resten van oude en halve downloads<small>' + esc(mdGB(s.leftovers)) + ' die geen bruikbaar model zijn</small></span><button class="mini" onclick="mdClean()">Opruimen</button></div>' : '');
  slHtml($('#md-cats'), MD_CATS.map(cat => mdCatCard(cat)).join(''));
  const w = $('#md-wifi'); w.classList.toggle('on', !!s.wifiOnly); w.setAttribute('aria-checked', s.wifiOnly ? 'true' : 'false');
}
/** Kort: is deze soort klaar voor gebruik? Zuiver. [tekst, ok] */
function mdCatStatus(kind, all, tts){
  if (kind === 'tts') return !tts || !tts.state || tts.state === 'checking' ? ['Controleren…', false] : tts.state === 'ok' ? ['✓ Nederlandse stem' + (tts.engine ? ' (' + tts.engine + ')' : ''), true] : tts.state === 'nodutch' ? ['Geen Nederlandse stem', false] : tts.state === 'slow' ? ['De voorleesfunctie reageert niet', false] : ['Geen voorleesfunctie op je telefoon', false];
  const ms = all.filter(m => m.kind === kind), use = ms.find(m => m.inUse) || ms.find(m => m.state === 'ready');
  if (use) return ['✓ In gebruik: ' + mdLineName(use) + ' · ' + use.version, true];
  if (ms.some(m => mdBusy(m) && m.state !== 'failed')) return ['Bezig met downloaden', false];
  if (ms.some(m => m.state === 'failed')) return ['Downloaden mislukt', false];
  return [kind === 'zoek' ? 'Niet gedownload (niet nodig)' : 'Nog niet gedownload', false];
}
function mdCatCard(cat){
  const [st, ok] = mdCatStatus(cat.kind, mdModels(), mdSt.tts);
  const head = '<div class="md-cathead"><span class="md-caticon">' + uiIcon(cat.icon) + '</span><div><h2>' + esc(cat.title) + '</h2><small class="md-catst' + (ok ? ' ok' : '') + '">' + (st.startsWith('✓ ') ? '<span aria-hidden="true">✓ </span>' + esc(st.slice(2)) : esc(st)) + '</small></div></div>' +
    '<p class="note md-what">' + esc(cat.what) + ' <span class="md-need">' + esc(cat.need) + '</span></p>';
  if (cat.kind === 'tts') return '<section class="card md-cat" data-kind="tts" aria-label="' + esc(cat.title) + '">' + head + mdTtsBody(mdSt.tts || {}) + '</section>';
  const lines = mdLines(mdModels(), cat.kind), eng = (mdSt.engines || {})[cat.kind];
  // Het model (dat je downloadt) en de motor die het draait (zit in de app en gaat mee met app-updates)
  const engine = eng ? '<p class="note md-engine">' + esc('Motor: ' + eng + ' (zit in de app, wordt bijgewerkt met de app)') + '</p>' : '';
  return '<section class="card md-cat" data-kind="' + cat.kind + '" aria-label="' + esc(cat.title) + '">' + head + '<div role="list">' + lines.map(l => mdLineHtml(l)).join('') + '</div>' + engine + '</section>';
}
function mdTtsBody(t){
  if (t.state === 'ok') return '<div class="md-acts">' + mdBtn('Stem en snelheid', 'Android.ttsSettings()', false, 'Stem en snelheid van het voorlezen instellen') + '</div>';
  if (t.state === 'nodutch') return '<p class="note">Er is wel een voorleesfunctie' + (t.engine ? ' (' + esc(t.engine) + ')' : '') + ', maar zonder Nederlandse stem. Voeg er een toe; daarna kijkt de app vanzelf opnieuw.</p><div class="md-acts">' + mdBtn('Nederlandse stem toevoegen', 'Android.ttsInstall()', true) + mdBtn('Instellingen', 'Android.ttsSettings()', false, 'Instellingen van het voorlezen') + mdBtn('Opnieuw kijken', 'mdTtsCheck()', false, 'Opnieuw kijken of er een Nederlandse stem is') + '</div>';
  if (t.state === 'noengine' || t.state === 'slow') return '<p class="note">' + (t.state === 'slow' ? 'De voorleesfunctie van je telefoon antwoordde niet op tijd. Probeer het zo opnieuw.' : 'Installeer bijvoorbeeld Spraakservices van Google uit de Play Store en kies daar een Nederlandse stem.') + '</p><div class="md-acts">' + mdBtn('Instellingen', 'Android.ttsSettings()', false, 'Instellingen van het voorlezen') + mdBtn('Opnieuw kijken', 'mdTtsCheck()', false, 'Opnieuw kijken of er een voorleesfunctie is') + '</div>';
  return '';
}
function mdTtsCheck(){ Android.ttsCheck(); mdSt.tts = { state: 'checking' }; mdRender(); clearTimeout(mdPollT); mdPollT = setTimeout(mdRefresh, 1500); }

/** Eén soort (bijv. "Klein en snel"): welke versie je hebt, welke de nieuwste is, en wat er gedownload wordt. */
function mdLineHtml(chain){
  const newest = chain[0], have = chain.filter(m => m.state === 'ready'), busyAll = chain.filter(mdBusy), busy = busyAll[0], main = have[0];
  const newestBusy = mdBusy(newest);
  const ram = newest.ram > 0 && mdSt.ram > 0 && mdSt.ram < newest.ram ? ' · je telefoon heeft ±' + Math.round(mdSt.ram / 1e9) + ' GB werkgeheugen; dit model kan traag zijn' : '';
  const name = '<b>' + esc(mdLineName(newest)) + '</b>';
  let rows = '';
  if (main) {
    const isNew = main === newest;
    rows += '<div class="md-ver">' +
      '<div class="aimt">' + name + '<small>' + esc('Je hebt versie: ' + main.version + (chain.length > 1 ? (isNew ? ' · nieuwste versie' : ' · oudere versie') : '') + ' · ' + mdGB(main.size)) + '</small>' +
      '<small>' + esc(mdAgo(main.last)) + '</small></div>' +
      '<div class="aimb">' + (main.inUse ? '<span class="aisel">In gebruik</span>' : main.kind === 'zoek' ? '' : mdBtn('Gebruiken', 'mdUse(' + jsq(main.key) + ')', false, mdLineName(main) + ' gebruiken')) +
      mdBtn('Verwijderen', 'mdDel(' + jsq(main.key) + ')', false, mdLineName(main) + ' ' + main.version + ' verwijderen') + '</div></div>';
    // Een nieuwere versie die je nog niet hebt
    if (!isNew && !newestBusy && newest.state !== 'ready') rows += '<div class="md-newer">' + uiIcon('star') + '<span><b>' + esc('Nieuwere versie: ' + newest.version) + '</b> · ' + esc(mdGB(newest.size)) + (newest.news ? '<small>' + esc(newest.news) + '</small>' : '') + '</span>' +
      mdBtn('Bijwerken', 'mdDl(' + jsq(newest.key) + ')', true, mdLineName(newest) + ' bijwerken naar ' + newest.version) + '</div>';
    // Oudere versies die er ook nog staan
    have.slice(1).forEach(o => { rows += '<div class="md-old"><span>' + esc('Ook nog op je telefoon: ' + o.version + ' (oudere versie, ' + mdGB(o.size) + ')') + (o.inUse ? ' · in gebruik' : '') + '</span>' +
      mdBtn('Verwijderen', 'mdDel(' + jsq(o.key) + ')', false, mdLineName(o) + ' ' + o.version + ' verwijderen') + '</div>'; });
  }
  busyAll.forEach((b, i) => { rows += mdBusyHtml(b, !main && i === 0 ? name : '', b !== newest ? newest : null); });
  // Alleen een oudere versie onderbroken of mislukt en de nieuwste nog niet: de nieuwste aanbieden
  if (!main && busy && !newestBusy) rows += '<div class="md-newer">' + uiIcon('star') + '<span><b>' + esc('Nieuwere versie: ' + newest.version) + '</b> · ' + esc(mdGB(newest.size)) + '<small>' + esc('Download liever deze; de oudere kun je stoppen.' + (newest.news ? ' ' + newest.news : '')) + '</small></span>' +
    mdBtn('Downloaden', 'mdDl(' + jsq(newest.key) + ')', true, mdLineName(newest) + ' ' + newest.version + ' downloaden') + '</div>';
  if (!main && !busy) {
    const need = newest.kind === 'spraak' ? newest.size : 2 * newest.size + 200 * 1048576, fits = !(mdSt.free > 0) || mdSt.free >= need;
    const can = newest.kind === 'spraak' || mdSt.supported !== false;
    rows += '<div class="md-ver"><div class="aimt">' + name + '<small>' + esc('Nog niet op je telefoon · nieuwste versie: ' + newest.version + ' · ' + mdGB(newest.size) + ram + (fits ? '' : ' · past nu niet (' + mdGB(mdSt.free) + ' vrij)')) + '</small></div>' +
      '<div class="aimb">' + (can ? mdBtn('Downloaden', 'mdDl(' + jsq(newest.key) + ')', true, mdLineName(newest) + ' downloaden') : '') + '</div></div>';
  }
  return '<div class="md-linebox" role="listitem" data-line="' + esc(newest.key) + '">' + rows + '</div>';
}
function mdBusyHtml(m, name, newer){
  const hint = newer && m.state !== 'running' && m.state !== 'verifying' ? 'Oudere versie: die gaat niet verder. Stop hem en download de nieuwste.' : mdHint(m, mdSt), stopB = mdBtn('Stoppen', 'mdStop(' + jsq(m.key) + ')', false, mdLineName(m) + ': downloaden stoppen');
  let b = '';
  if (m.kind === 'spraak') b = ''; // het spraakmodel stopt bij Gesprekken uitschrijven
  else if (m.state === 'failed') b = (newer ? '' : mdBtn('Opnieuw', 'mdDl(' + jsq(m.key) + ')', true, mdLineName(m) + ' opnieuw downloaden')) + mdBtn('Weghalen', 'mdStop(' + jsq(m.key) + ')', false, mdLineName(m) + ' weghalen');
  else if (newer) b = stopB; // een oudere versie: niet verder, wel stoppen
  else if (m.state === 'verifying') b = '';
  else if (m.state === 'paused' && !m.waiting) b = mdBtn('Verder', 'mdDl(' + jsq(m.key) + ')', true, mdLineName(m) + ': verder downloaden') + stopB;
  else b = stopB;
  return '<div class="md-busy" data-key="' + esc(m.key) + '"><div class="aimt">' + (name || '') + '<small>' + esc(m.version + (newer ? ' (oudere versie)' : '')) + '</small>' +
    '<small class="aims"><span aria-hidden="true">' + (m.state === 'failed' ? '⚠ ' : m.state === 'running' || m.state === 'queued' ? '↓ ' : '') + '</span>' + esc(mdStateTxt(m)) + '</small>' +
    '<span class="pcprog" role="progressbar" aria-label="' + esc('Voortgang ' + mdLineName(m)) + '" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + Math.floor(mdPct(m)) + '"><i style="width:' + mdPct(m).toFixed(1) + '%"></i></span>' +
    (hint ? '<small class="md-hint">' + esc(hint) + '</small>' : '') + '</div>' + (b ? '<div class="md-acts">' + b + '</div>' : '') + '</div>';
}

/* ---------- acties ---------- */
function mdDl(key){
  mdLoad();
  const m = mdFind(key);
  const go = () => {
    const e = m.kind === 'spraak' ? Android.txDownload(m.id) : Android.aiDownload(m.id);
    if (e) { toast(e); return; }
    toast('↓ ' + mdLineName(m) + ' wordt gedownload; de voortgang staat ook in de meldingenbalk');
    mdRefresh(); if (current !== 'models' && typeof aiRefresh === 'function') aiRefresh();
  };
  // Niet op wifi terwijl Alleen via wifi aan staat: eerst vragen (anders wacht hij op wifi)
  if (m.kind !== 'spraak' && mdSt.wifiOnly && mdSt.metered) {
    askChoice('Niet op wifi', mdLineName(m) + ' is ' + mdGB(m.size - (m.bytes || 0)) + '. Nu via mobiel internet downloaden (Alleen via wifi gaat dan uit), of wachten? Dan gaat hij verder zodra je de app op wifi opent.', 'Nu via mobiel internet', 'Wachten op wifi',
      () => { mdSetWifi(false); go(); setTimeout(() => toast('Alleen via wifi staat nu uit; ' + mdLineName(m) + ' wordt gedownload'), 50); }, go);
    return;
  }
  go();
}
function mdStop(key){
  mdLoad();
  const m = mdFind(key);
  const go = () => { Android.aiDelete(m.id); mdRefresh(); if (current !== 'models' && typeof aiRefresh === 'function') aiRefresh(); toast('Gestopt'); };
  if ((m.bytes || 0) > 100e6) askConfirm('Downloaden stoppen?', 'Wat al binnen is (' + mdGB(m.bytes) + ') gaat weg. Je kunt het later opnieuw downloaden.', 'Stoppen', go);
  else go();
}
function mdUse(key){
  const m = mdFind(key);
  if (m.kind === 'spraak') { Android.txSetModel(m.id); toast('Spraak naar tekst gebruikt nu: ' + mdLineName(m)); }
  else {
    Android.aiUse(m.id); toast('De assistent gebruikt nu: ' + mdLineName(m) + ' (' + m.version + ')');
    // Een nieuwere versie in gebruik genomen: de oude mag weg (vragen, niet vanzelf)
    const old = m.replaces && mdFind('ai:' + m.replaces);
    if (old && old.state === 'ready') setTimeout(() => askConfirm(old.version + ' verwijderen?', 'Je gebruikt nu ' + m.version + '. De oudere versie (' + mdGB(old.size) + ') is niet meer nodig.', 'Verwijderen', () => { Android.aiDelete(old.id); mdRefresh(); toast(old.version + ' verwijderd'); }), 400);
  }
  mdRefresh();
}
/** Wat er niet meer werkt als dit model weg is. Zuiver. */
function mdDelText(m, all){
  const others = all.filter(x => x.key !== m.key && x.kind === m.kind && x.state === 'ready');
  if (m.kind === 'zoek') return 'Slim zoeken werkt dan niet meer en de zoekindex gaat ook weg; de assistent zoekt dan niet in je gegevens.';
  if (m.kind === 'ai') return !others.length ? 'De assistent werkt dan pas weer als je een taalmodel downloadt.' : m.inUse ? 'De assistent gebruikt dan ' + mdLineName(others[0]) + ' (' + others[0].version + ').' : 'Je kunt het later opnieuw downloaden.';
  return !others.length ? 'Gesprekken uitschrijven, spraaknotities en Slaappraat schrijven dan pas weer uit als je een spraakmodel downloadt. Wat al uitgeschreven is, blijft.' : m.inUse ? 'Spraak naar tekst gebruikt dan ' + mdLineName(others[0]) + '.' : 'Je kunt het later opnieuw downloaden.';
}
function mdDel(key){
  mdLoad();
  const m = mdFind(key);
  if (m.kind === 'spraak' && mdSt.txBusy) { toast('Er wordt nu uitgeschreven; wacht tot dat klaar is'); return; }
  askConfirm(mdLineName(m) + ' (' + m.version + ') verwijderen?', mdGB(m.size) + ' komt vrij. ' + mdDelText(m, mdModels()), 'Verwijderen', () => {
    if (m.kind === 'spraak') { Android.txDeleteModel(m.id); if (m.inUse) { const o = mdModels().find(x => x.kind === 'spraak' && x.state === 'ready' && x.key !== m.key); if (o) Android.txSetModel(o.id); } }
    else Android.aiDelete(m.id);
    toast(mdLineName(m) + ' verwijderd');
    mdRefresh();
  });
}
function mdClean(){ const n = +Android.modelsClean() || 0; mdRefresh(); toast(n ? mdGB(n) + ' vrijgemaakt' : 'Niets op te ruimen'); }
function mdSetWifi(on){ Android.aiPref('wifiOnly', on); if (typeof aiSt === 'object') aiSt.wifiOnly = on; mdSt.wifiOnly = on; }
function mdWifi(){ const on = !mdSt.wifiOnly; mdSetWifi(on); mdRender(); toast(on ? 'Modellen alleen via wifi downloaden' : 'Modellen ook via mobiel internet downloaden'); }
/** Korte samenvatting voor Instellingen. */
function mdSummary(s){
  const have = (s.models || []).filter(m => m.state === 'ready'), busy = (s.models || []).filter(m => ['running', 'queued', 'paused', 'verifying'].includes(m.state));
  return (have.length ? have.length + (have.length === 1 ? ' model' : ' modellen') + ' · ' + mdGB(s.used) : 'Nog geen modellen') + (busy.length ? ' · ' + busy.length + ' aan het downloaden' : '');
}
/* Oude naam (Taalmodel-scherm, oudere knoppen): verder downloaden */
function mdViaApp(id){ mdDl('ai:' + id); }
