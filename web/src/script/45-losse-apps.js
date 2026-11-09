/* ---------- Losse apps: een tool als eigen app op het startscherm ----------
 * In een losse app (SoloActivity) is SOLO de naam van de tool. Dan zijn alleen de schermen van die tool bereikbaar,
 * staan er geen knoppen of menu-keuzes naar andere tools, geen tabbalk, en sluit "terug" op het eerste scherm de app.
 * Welke tools een losse app mogen zijn, kies je in Instellingen (of via lang indrukken van een tegel). */
const SOLO = (typeof Android.soloTool === 'function' && Android.soloTool()) || '';
const SOLO_ROOT = SOLO === 'player' ? 'radio' : SOLO;
function soloRoot(name){ return name === SOLO_ROOT || (SOLO === 'player' && name === 'podcasts'); }

/** Hoort dit scherm bij de tool van deze losse app? (via de terug-volgorde naar het eerste scherm) */
function soloOk(name){
  if (!SOLO) return true;
  if (SOLO === 'backup' && name === 'herstel') return true;
  for (let n = name, i = 0; n && i < 12; n = BACK[n], i++) if (soloRoot(n)) return true;
  return false;
}
// Functies die een scherm van een andere tool openen
const SOLO_FN = { ctOpen: 'contact', openNote: 'note', openSms: 'smschat', openChat: 'chat', txOpen: 'txd', callsForPerson: 'calls',
  gsMoreCalls: 'calls', gsMore: 'search', gsOpen: 'search', pinTool: 'home', toolMenu: 'home', homeOpen: 'home', tabGo: 'home', muOpen: 'music', waPickFolder: SOLO === 'backup' ? 'bkset' : 'settings', fmqSetup: 'masterdata', fmVast: 'masterdata', cmaiOverMij: 'aisetpers' };
/** Leidt deze code (een onclick of een menu-keuze) alleen naar schermen van deze tool? */
function soloSrcOk(src){
  if (!SOLO) return true;
  src = String(src || '');
  let m; const re = /\b(?:show|jumpTo|tabGo)\(\s*['"]([a-z]+)['"]/g;
  while ((m = re.exec(src))) if (!soloOk(m[1])) return false;
  const re2 = /\b([A-Za-z]+)\(/g;
  while ((m = re2.exec(src))) if (SOLO_FN[m[1]] && !soloOk(SOLO_FN[m[1]])) return false;
  return true;
}
function soloHide(root){
  if (!SOLO || !root || root.nodeType !== 1) return;
  const els = root.matches && root.matches('[onclick]') ? [root] : [];
  root.querySelectorAll('[onclick]').forEach(e => els.push(e));
  for (const e of els) if (!soloSrcOk(e.getAttribute('onclick'))) { e.style.display = 'none'; e.dataset.soloHidden = '1'; }
}

if (SOLO) {
  document.body.classList.add('solo');
  if (SOLO === 'radio' || SOLO === 'podcasts' || SOLO === 'player') document.body.classList.add('solo-player');
  const _show = show;
  show = function(name){
    if (!soloOk(name)) return;
    _show(name);
    document.body.classList.toggle('solo-root', soloRoot(current));
  };
  const _jump = jumpTo;
  jumpTo = function(name, open){ if (soloOk(name)) _jump(name, open); };
  const _sheet = openSheet;
  openSheet = function(title, sub, acts, onDismiss){
    const ok = (acts || []).filter(a => soloSrcOk(a[1]));
    if (!ok.length) { if (onDismiss) onDismiss(); return; }
    _sheet(title, sub, ok, onDismiss);
  };
  const _back = window.goBack;
  window.goBack = function(){
    // Op het eerste scherm en niets open (venster, menu, speler): de losse app sluiten
    const open = document.querySelector('#modal.on, #sigdlg.on, #voice.on, #sleepdlg.on, #vp-scan.on, #sheet.on, #ci-ans.on, #ci-rem.on, #gb-light, #ai-talk:not([hidden])') || (typeof plIsOpen !== 'undefined' && plIsOpen) ||
      (typeof cm !== 'undefined' && cm.picker) || (typeof fmq !== 'undefined' && fmq.open);
    if (soloRoot(current) && !open) return false;
    return _back();
  };
  // Knoppen en links naar andere tools weghalen, ook als ze later worden toegevoegd
  soloHide(document.body);
  new MutationObserver(list => { for (const r of list) r.addedNodes.forEach(n => soloHide(n)); })
    .observe(document.body, { childList: true, subtree: true });
}

/* ---------- Instellen: welke tools als losse app ---------- */
function soloAllowedList(){ try { return JSON.parse(Android.soloList()); } catch(e){ return []; } }
function soloMake(tool, after){
  const tt = TOOLS[tool]; if (!tt) return;
  const pin = () => { const e = Android.soloPin(tool, t(tt[0]), tt[1], tt[2]); toast(e || t('Bevestig op je startscherm om {naam} als losse app toe te voegen', { naam: t(tt[0]) })); };
  if (soloAllowedList().includes(tool)) { pin(); return; }
  askConfirm(t('Losse app: {naam}', { naam: t(tt[0]) }), t('{naam} komt als eigen app op je startscherm. Het algemene app-slot wordt overgeslagen; een eigen ontgrendeling van de tool blijft gelden. Andere tools zijn vanuit die losse app niet te zien.', { naam: t(tt[0]) }) + '\n\n' + t('Uitzetten kan altijd bij Instellingen → Losse apps.'), 'Losse app maken', () => {
    Android.soloSet(tool, true); pin(); if (after) after();
  });
}
function stSoloRender(){
  const box = $('#st-solo-list'); if (!box) return;
  const on = soloAllowedList();
  box.innerHTML = SOLO_TOOLS.filter(k => TOOLS[k]).map(k => {
    const tt = TOOLS[k], is = on.includes(k);
    return '<div class="sw"><span>' + esc(t(tt[0])) + (is ? '<small><button class="linkish" onclick="soloMake(\'' + k + '\')">' + t('Op startscherm zetten') + '</button></small>' : '') +
      '</span><button class="tg' + (is ? ' on' : '') + '" role="switch" aria-checked="' + is + '" aria-label="' + t('{naam} als losse app', { naam: esc(t(tt[0])) }) + '" onclick="stSoloToggle(\'' + k + '\', this)"></button></div>';
  }).join('');
}
function stSoloToggle(tool, btn){
  const tt = TOOLS[tool]; if (!tt) return;
  if (btn.classList.contains('on')) { Android.soloSet(tool, false); toast(t('{naam}: losse app uit', { naam: t(tt[0]) })); stSoloRender(); return; }
  askConfirm(t('Losse app: {naam}', { naam: t(tt[0]) }), t('{naam} mag dan als eigen app op je startscherm. Het algemene app-slot wordt overgeslagen; een eigen ontgrendeling van de tool blijft gelden.', { naam: t(tt[0]) }), 'Aanzetten', () => {
    Android.soloSet(tool, true); stSoloRender(); toast(t('{naam}: losse app aan', { naam: t(tt[0]) }));
  });
}
