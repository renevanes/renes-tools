/* ---------- Losse apps: een tool als eigen app op het startscherm ----------
 * In een losse app (SoloActivity) is SOLO de naam van de tool. Dan zijn alleen de schermen van die tool bereikbaar,
 * staan er geen knoppen of menu-keuzes naar andere tools, geen tabbalk, en sluit "terug" op het eerste scherm de app.
 * Welke tools een losse app mogen zijn, kies je in Instellingen (of via lang indrukken van een tegel). */
const SOLO = (typeof Android.soloTool === 'function' && Android.soloTool()) || '';
const SOLO_TOOLS = ['redial', 'wa', 'tracks', 'sms', 'history', 'calls', 'notes', 'contacts', 'pdf', 'kluis', 'car', 'recorder',
  'transcripts', 'radio', 'podcasts', 'spotify', 'vpn', 'loc', 'music', 'ai']; // zelfde lijst als Solo.TOOLS (Java)

/** Hoort dit scherm bij de tool van deze losse app? (via de terug-volgorde naar het eerste scherm) */
function soloOk(name){
  if (!SOLO) return true;
  for (let n = name, i = 0; n && i < 12; n = BACK[n], i++) if (n === SOLO) return true;
  return false;
}
// Functies die een scherm van een andere tool openen
const SOLO_FN = { ctOpen: 'contact', openNote: 'note', openSms: 'smschat', openChat: 'chat', txOpen: 'txd', callsForPerson: 'calls',
  gsMoreCalls: 'calls', gsMore: 'search', gsOpen: 'search', pinTool: 'home', toolMenu: 'home', homeOpen: 'home', tabGo: 'home', muOpen: 'music', waPickFolder: 'settings' };
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
  if (SOLO === 'radio' || SOLO === 'podcasts') document.body.classList.add('solo-player');
  const _show = show;
  show = function(name){
    if (!soloOk(name)) return;
    _show(name);
    document.body.classList.toggle('solo-root', current === SOLO);
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
    const open = document.querySelector('#modal.on, #voice.on, #sleepdlg.on, #vp-scan.on, #sheet.on') || (typeof plIsOpen !== 'undefined' && plIsOpen);
    if (current === SOLO && !open) return false;
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
  const t = TOOLS[tool]; if (!t) return;
  const pin = () => { const e = Android.soloPin(tool, t[0], t[1], t[2]); toast(e || 'Bevestig op je startscherm om ' + t[0] + ' als losse app toe te voegen'); };
  if (soloAllowedList().includes(tool)) { pin(); return; }
  askConfirm('Losse app: ' + t[0], t[0] + ' komt als eigen app op je startscherm. Die opent zonder vingerafdruk of app-code: iedereen die je telefoon heeft, kan erin. Andere tools zijn vanuit die losse app niet te zien.\n\nUitzetten kan altijd bij Instellingen → Losse apps.', 'Losse app maken', () => {
    Android.soloSet(tool, true); pin(); if (after) after();
  });
}
function stSoloRender(){
  const box = $('#st-solo-list'); if (!box) return;
  const on = soloAllowedList();
  box.innerHTML = SOLO_TOOLS.filter(k => TOOLS[k]).map(k => {
    const t = TOOLS[k], is = on.includes(k);
    return '<div class="sw"><span>' + esc(t[0]) + (is ? '<small><button class="linkish" onclick="soloMake(\'' + k + '\')">Op startscherm zetten</button></small>' : '') +
      '</span><button class="tg' + (is ? ' on' : '') + '" role="switch" aria-checked="' + is + '" aria-label="' + esc(t[0]) + ' als losse app" onclick="stSoloToggle(\'' + k + '\', this)"></button></div>';
  }).join('');
}
function stSoloToggle(tool, btn){
  const t = TOOLS[tool]; if (!t) return;
  if (btn.classList.contains('on')) { Android.soloSet(tool, false); toast(t[0] + ': losse app uit'); stSoloRender(); return; }
  askConfirm('Losse app: ' + t[0], t[0] + ' mag dan als eigen app op je startscherm. Die opent zonder vingerafdruk of app-code: iedereen die je telefoon heeft, kan erin.', 'Aanzetten', () => {
    Android.soloSet(tool, true); stSoloRender(); toast(t[0] + ': losse app aan');
  });
}
