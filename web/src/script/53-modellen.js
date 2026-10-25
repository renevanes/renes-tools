/* ---------- Modellen beheren: taalmodellen, het zoekmodel en de spraakmodellen op één plek ---------- */
let mdSt = {}, mdPollT = null;
const MD_KIND = { ai: 'Assistent', zoek: 'Slim zoeken', spraak: 'Spraak naar tekst' };
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
/* Een model dat door een nieuwer vervangen is, alleen tonen als je het hebt of downloadt */
function mdListed(m){ return m.kind === 'spraak' || m.state !== 'none' || !mdModels().some(x => x.kind !== 'spraak' && x.replaces === m.id); }
function mdPct(m){ return m.state === 'verifying' ? (m.pct || 0) : m.total > 0 ? Math.min(100, m.bytes / m.total * 100) : 0; }

/** De stand van een download in één regel. Zuiver. */
function mdStateTxt(m){
  const part = m.total > 0 ? Math.floor(m.bytes / m.total * 100) + '% · ' + mdGB(m.bytes) + ' van ' + mdGB(m.total) : '';
  const via = m.via === 'app' ? ' · via de app' : '';
  switch (m.state) {
    case 'running': return (part || 'Bezig') + via;
    case 'queued': return 'In de wachtrij' + via;
    case 'paused': return (m.reason || 'Gepauzeerd') + (part ? ' · ' + part : '');
    case 'verifying': return 'Controleren… ' + (m.pct || 0) + '%';
    case 'failed': return 'Mislukt: ' + (m.reason || 'onbekende fout');
  }
  return '';
}
/** Uitleg bij een download die blijft wachten. Zuiver (krijgt de stand van het netwerk mee). */
function mdHint(m, net){
  if (m.state === 'paused' && m.via !== 'app' && /internet/i.test(m.reason || ''))
    return net.online ? 'Je telefoon heeft wel internet, maar het downloadbeheer van Android blijft wachten. Download hem via de app: die gaat verder waar hij was.'
      : 'Er is nu geen internet. Het downloaden gaat vanzelf verder zodra er weer verbinding is.';
  if ((m.state === 'paused' || m.state === 'queued') && /wifi/i.test(m.reason || ''))
    return 'Je zit niet op wifi. Wacht tot je op wifi bent, of zet hieronder Alleen via wifi uit om via mobiel internet te downloaden.';
  if (m.state === 'queued' && m.via !== 'app') return 'Android heeft hem in de wachtrij gezet. Duurt dat lang, download hem dan via de app.';
  if (m.state === 'paused' && m.via === 'app' && m.waiting) return 'De app wacht even en gaat vanzelf verder als het weer mag.';
  if (m.state === 'paused' && m.via === 'app') return 'Tik op Verder om door te gaan waar hij was.';
  return '';
}
/** Knop; aria = de naam voor een schermlezer (met het model erbij, want er staan er meer van op het scherm). */
function mdBtn(label, act, on, aria){ return '<button class="mini' + (on ? ' on' : '') + '" onclick="' + act + '"' + (aria ? ' aria-label="' + esc(aria) + '"' : '') + '>' + label + '</button>'; }
function mdLeave(){ clearTimeout(mdPollT); }

function enterModels(){ mdRefresh(); }
function mdRefresh(){
  mdLoad();
  if (current === 'models') mdRender();
  clearTimeout(mdPollT);
  if (current === 'models' && mdModels().some(m => ['running', 'queued', 'verifying'].includes(m.state) || (m.state === 'paused' && m.via !== 'app'))) mdPollT = setTimeout(mdRefresh, 1500);
}
const _mdOnAiModels = window.onAiModels;
window.onAiModels = function(){ if (_mdOnAiModels) _mdOnAiModels(); if (current === 'models') mdRefresh(); };

function mdRender(){
  const s = mdSt, ms = mdModels().filter(mdListed);
  // Opslag
  const used = +s.used || 0, free = +s.free || 0, share = used + free > 0 ? used / (used + free) * 100 : 0;
  $('#md-sum').innerHTML = '<h2><span class="ui-icon" data-icon="folder" aria-hidden="true"></span>Opslag</h2>' +
    '<div class="md-bar" role="img" aria-label="' + esc('Modellen ' + mdGB(used) + ', vrij ' + mdGB(free)) + '"><i style="width:' + share.toFixed(1) + '%"></i></div>' +
    '<p class="md-line"><b>' + (used ? 'Modellen: ' + mdGB(used) : 'Nog geen modellen') + '</b><span>' + (free > 0 ? 'Vrij op je telefoon: ' + mdGB(free) : '') + '</span></p>' +
    (s.leftovers >= 1e6 ? '<div class="sw"><span>Resten van oude en halve downloads<small>' + esc(mdGB(s.leftovers)) + ' die geen bruikbaar model zijn</small></span><button class="mini" onclick="mdClean()">Opruimen</button></div>' : '');
  // Bezig met downloaden
  const busy = ms.filter(mdBusy);
  slHtml($('#md-busy'), busy.length ? '<div class="flabel stgl" role="heading" aria-level="2">Bezig met downloaden</div><div role="list" class="md-busylist">' + busy.map(m => mdBusyCard(m)).join('') + '</div>' : '');
  // Op je telefoon
  const have = ms.filter(m => m.state === 'ready').sort((a, b) => (b.inUse - a.inUse) || (b.size - a.size));
  slHtml($('#md-have'), have.length ? have.map(m => mdHaveCard(m)).join('') : '<p class="note">Nog geen modellen op je telefoon.</p>');
  // Beschikbaar
  const get = ms.filter(m => m.state === 'none');
  $('#md-get-h').style.display = get.length ? '' : 'none';
  slHtml($('#md-get'), get.map(m => mdGetCard(m)).join(''));
  const w = $('#md-wifi'); w.classList.toggle('on', !!s.wifiOnly); w.setAttribute('aria-checked', s.wifiOnly ? 'true' : 'false');
}
function mdHead(m, extra){
  const what = m.desc ? m.desc.replace(/^.*· /, '') : '';
  return '<div class="aimt"><b>' + esc(m.label) + '</b><small>' + esc(m.kind === 'zoek' ? 'Voor slim zoeken: ' + what : MD_KIND[m.kind] + (what ? ' · ' + what : '')) + '</small>' + (extra || '') + '</div>';
}
function mdBusyCard(m){
  const id = jsq(m.id), app = m.via === 'app', hint = mdHint(m, mdSt);
  let b = '';
  const stopB = mdBtn('Stoppen', 'mdStop(' + jsq(m.key) + ')', false, m.label + ': downloaden stoppen');
  if (m.kind === 'spraak') b = ''; // het spraakmodel stopt bij Gesprekken uitschrijven
  else if (m.state === 'failed') b = mdBtn('Opnieuw', 'mdDl(' + jsq(m.key) + ')', true, m.label + ' opnieuw downloaden') + mdBtn('Weghalen', 'mdStop(' + jsq(m.key) + ')', false, m.label + ' weghalen');
  else if (m.state === 'verifying') b = '';
  else if (app && m.state === 'paused' && m.waiting) b = stopB; // de app wacht zelf even (bijv. tot wifi terug is)
  else if (app && m.state === 'paused') b = mdBtn('Verder', 'mdViaApp(' + id + ')', true, m.label + ': verder downloaden') + stopB;
  else if (!app && (m.state === 'paused' || m.state === 'queued')) b = mdBtn('Via de app downloaden', 'mdViaApp(' + id + ')', true, m.label + ' via de app downloaden') + stopB;
  else b = stopB;
  return '<div class="aimodel md-item" role="listitem" data-key="' + esc(m.key) + '">' +
    mdHead(m, '<small class="aims"><span aria-hidden="true">' + (m.state === 'failed' ? '⚠ ' : m.state === 'running' || m.state === 'queued' ? '↓ ' : '') + '</span>' + esc(mdStateTxt(m) || 'Bezig') + '</small>' +
      '<span class="pcprog" role="progressbar" aria-label="' + esc('Voortgang ' + m.label) + '" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + Math.floor(mdPct(m)) + '"><i style="width:' + mdPct(m).toFixed(1) + '%"></i></span>' +
      (hint ? '<small class="md-hint">' + esc(hint) + '</small>' : '')) +
    '<div class="aimb">' + b + '</div></div>';
}
function mdHaveCard(m){
  const use = m.inUse ? '<span class="aisel">In gebruik</span>' : m.kind === 'zoek' ? '' : mdBtn('Gebruiken', 'mdUse(' + jsq(m.key) + ')', false, m.label + ' gebruiken');
  return '<div class="aimodel md-item' + (m.inUse ? ' on' : '') + '" role="listitem" data-key="' + esc(m.key) + '">' +
    mdHead(m, '<small class="aims">' + esc(mdGB(m.size) + ' · ' + mdAgo(m.last)) + '</small>') +
    '<div class="aimb">' + use + mdBtn('Verwijderen', 'mdDel(' + jsq(m.key) + ')', false, m.label + ' verwijderen') + '</div></div>';
}
function mdGetCard(m){
  const need = m.kind === 'spraak' ? m.size : 2 * m.size + 200 * 1048576, fits = !(mdSt.free > 0) || mdSt.free >= need;
  const can = m.kind === 'spraak' || mdSt.supported !== false;
  return '<div class="aimodel md-item" role="listitem" data-key="' + esc(m.key) + '">' +
    mdHead(m, '<small class="aims">' + esc(mdGB(m.size) + (fits ? '' : ' · past nu niet (' + mdGB(mdSt.free) + ' vrij)')) + '</small>' + (m.news ? '<small class="ainew">' + uiIcon('star') + esc('Nieuw: ' + m.news) + '</small>' : '')) +
    '<div class="aimb">' + (can ? mdBtn('Downloaden', 'mdDl(' + jsq(m.key) + ')', true, m.label + ' downloaden') : '') + '</div></div>';
}

/* ---------- acties ---------- */
function mdId(key){ return String(key).replace(/^(ai|spraak):/, ''); }
function mdDl(key){
  const m = mdFind(key);
  const e = m.kind === 'spraak' ? Android.txDownload(m.id) : Android.aiDownload(m.id);
  if (e) { toast(e); return; }
  toast(m.kind !== 'spraak' && mdSt.wifiOnly ? '↓ Downloaden (via wifi)' : '↓ Downloaden');
  mdRefresh();
}
function mdViaApp(id){
  mdLoad();
  const m = mdFind('ai:' + id);
  const go = () => {
    const e = Android.aiDownloadApp(id); if (e) { toast(e); return; }
    toast('De app downloadt ' + m.label + ' nu zelf; de voortgang staat ook in de meldingenbalk');
    mdRefresh(); if (current !== 'models' && typeof aiRefresh === 'function') aiRefresh();
  };
  if (mdSt.wifiOnly && mdSt.metered) {
    askConfirm('Via mobiel internet?', 'Je zit niet op wifi. ' + m.label + ' is ' + mdGB(m.size - (m.bytes || 0)) + '. Zet Alleen via wifi uit om hem nu via mobiel internet te downloaden, of wacht tot je op wifi bent.', 'Alleen via wifi uitzetten', () => { mdSetWifi(false); go(); });
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
  if (m.kind === 'spraak') { Android.txSetModel(m.id); toast('Spraak naar tekst gebruikt nu: ' + m.label.replace(/^Spraakmodel /, '')); }
  else { Android.aiUse(m.id); toast('De assistent gebruikt nu: ' + m.label); }
  mdRefresh();
}
/** Wat er niet meer werkt als dit model weg is. Zuiver. */
function mdDelText(m, all){
  const others = all.filter(x => x.key !== m.key && x.kind === m.kind && x.state === 'ready');
  if (m.kind === 'zoek') return 'Slim zoeken werkt dan niet meer en de zoekindex gaat ook weg; de assistent zoekt dan niet in je gegevens.';
  if (m.kind === 'ai') return !others.length ? 'De assistent werkt dan pas weer als je een taalmodel downloadt.' : m.inUse ? 'De assistent gebruikt dan ' + others[0].label + '.' : 'Je kunt het later opnieuw downloaden.';
  return !others.length ? 'Gesprekken uitschrijven, spraaknotities en Slaappraat schrijven dan pas weer uit als je een spraakmodel downloadt. Wat al uitgeschreven is, blijft.' : m.inUse ? 'Spraak naar tekst gebruikt dan ' + others[0].label.replace(/^Spraakmodel /, '') + '.' : 'Je kunt het later opnieuw downloaden.';
}
function mdDel(key){
  const m = mdFind(key);
  if (m.kind === 'spraak' && mdSt.txBusy) { toast('Er wordt nu uitgeschreven; wacht tot dat klaar is'); return; }
  askConfirm(m.label + ' verwijderen?', mdGB(m.size) + ' komt vrij. ' + mdDelText(m, mdModels()), 'Verwijderen', () => {
    if (m.kind === 'spraak') { Android.txDeleteModel(m.id); if (m.inUse) { const o = mdModels().find(x => x.kind === 'spraak' && x.state === 'ready' && x.key !== m.key); if (o) Android.txSetModel(o.id); } }
    else Android.aiDelete(m.id);
    toast(m.label + ' verwijderd');
    mdRefresh();
  });
}
function mdClean(){ const n = +Android.modelsClean() || 0; mdRefresh(); toast(n ? mdGB(n) + ' vrijgemaakt' : 'Niets op te ruimen'); }
function mdSetWifi(on){ Android.aiPref('wifiOnly', on); if (typeof aiSt === 'object') aiSt.wifiOnly = on; mdSt.wifiOnly = on; }
function mdWifi(){ const on = !mdSt.wifiOnly; mdSetWifi(on); mdRender(); toast(on ? 'Downloaden alleen via wifi (voor een nieuwe download)' : 'Downloaden ook via mobiel internet (voor een nieuwe download)'); }
/** Korte samenvatting voor Instellingen. */
function mdSummary(s){
  const have = (s.models || []).filter(m => m.state === 'ready'), busy = (s.models || []).filter(m => ['running', 'queued', 'paused', 'verifying'].includes(m.state));
  return (have.length ? have.length + (have.length === 1 ? ' model' : ' modellen') + ' · ' + mdGB(s.used) : 'Nog geen modellen') + (busy.length ? ' · ' + busy.length + ' aan het downloaden' : '');
}
