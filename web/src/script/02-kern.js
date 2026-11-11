const $ = s => document.querySelector(s);
/* HTML-escapen (ook ' voor attributen tussen enkele aanhalingstekens). */
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
/* Waarde als JS-tekst binnen een onclick="…": eerst JS-veilig (JSON), dan HTML-veilig. Nooit esc() alleen gebruiken in een handler. */
const jsq = s => esc(JSON.stringify(String(s == null ? '' : s)));
const store = {
  get(k, d){ try { const v = localStorage.getItem('rt.'+k); return v == null ? d : JSON.parse(v); } catch(e){ return d; } },
  set(k, v){ try { localStorage.setItem('rt.'+k, JSON.stringify(v)); } catch(e){} webStoreSync(); }
};
/* Instellingen van de interface (startschermindeling, keuzes) ook in de app bewaren, zodat ze mee kunnen in Alles back-uppen. */
const WS_SKIP = /^(autoDraft|seenVersion|askedPerms|themeNow|homeRecent|.*Asked)$/; // themeNow: laatst berekende themastand, hoort bij dit moment; homeRecent: welke tools je laatst opende (gebruik, geen instelling)
let webStoreTmr = null;
function webStoreSync(){
  clearTimeout(webStoreTmr);
  webStoreTmr = setTimeout(() => {
    if (typeof Android === 'undefined' || typeof Android.webStoreSave !== 'function') return;
    const o = {};
    try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k.startsWith('rt.') && !WS_SKIP.test(k.slice(3))) o[k] = localStorage.getItem(k); } } catch(e){ return; }
    Android.webStoreSave(JSON.stringify(o));
  }, 1500);
}
/* Na terugzetten van een backup: ontbrekende interface-instellingen overnemen. */
function webStoreRestore(){
  if (typeof Android === 'undefined' || typeof Android.webStorePending !== 'function') return;
  let o; try { o = JSON.parse(Android.webStorePending() || 'null'); } catch(e){ return; }
  if (!o) return;
  // Na "Alles terugzetten" (herinstallatie): vervangen in plaats van aanvullen (behalve wat bij deze installatie hoort)
  const replace = o.__replace === '1';
  try {
    if (replace) for (let i = localStorage.length - 1; i >= 0; i--) { const k = localStorage.key(i); if (k && k.startsWith('rt.') && !WS_SKIP.test(k.slice(3)) && !(k in o)) localStorage.removeItem(k); }
    Object.keys(o).forEach(k => { if (k.startsWith('rt.') && typeof o[k] === 'string' && (replace ? !WS_SKIP.test(k.slice(3)) : localStorage.getItem(k) == null)) localStorage.setItem(k, o[k]); });
  } catch(e){}
}
webStoreRestore();
setTimeout(webStoreSync, 3000); // eerste keer na een update meteen bewaren
let current = 'home', poll = null, nameFor = '', lastResultShown = '';

/* Kort onthouden van dure brugvragen (tellen van alle sms'en, mappen controleren) tijdens het pollen. */
const _cache = {};
function cached(key, ms, fn){ const c = _cache[key]; if (c && Date.now() - c.t < ms) return c.v; const v = fn(); _cache[key] = { t: Date.now(), v }; return v; }
function uncache(key){ delete _cache[key]; }
/* Pollen stopt als de app op de achtergrond staat (bespaart accu); bij terugkomen draait alles meteen weer. */
let appPaused = false;
const _setInterval = window.setInterval.bind(window);
window.setInterval = function(fn, ms){ const a = Array.prototype.slice.call(arguments, 2); return _setInterval(function(){ if (!appPaused && !document.hidden) fn.apply(null, a); }, ms); };
/* Melding met "Ongedaan maken" (5 s) in plaats van een vraag vooraf. */
function undoToast(text, undo){
  const u = $('#undo'); $('#undo-t').textContent = text; u.classList.add('on');
  clearTimeout(u._h); u._h = setTimeout(() => u.classList.remove('on'), 5000);
  $('#undo-b').onclick = () => { clearTimeout(u._h); u.classList.remove('on'); undo(); };
}
function toast(m){ const tt=$('#toast'); tt.textContent=m; tt.classList.add('on'); clearTimeout(tt._h); tt._h=setTimeout(()=>tt.classList.remove('on'),2600); }

/* Eigen dialogen in plaats van de systeem-popups van de WebView. */
let _modalCb = null;
function askConfirm(title, text, okLabel, onOk, onCancel){
  openModal(title, text, null, okLabel || 'OK', v => { if (v !== null) onOk(); else if (onCancel) onCancel(); });
}
function askInput(title, text, value, okLabel, onOk){
  openModal(title, text, value == null ? '' : value, okLabel || 'OK', v => { if (v !== null) onOk(v); });
}
/* Twee keuzes, bijv. Opslaan / Weggooien. Terug of buiten tikken = niets doen (blijven). */
function askChoice(title, text, okLabel, otherLabel, onOk, onOther){
  openModal(title, text, null, okLabel, v => { if (v === 'other') onOther(); else if (v !== null) onOk(); });
  const c = $('#modal-cancel'); c.textContent = otherLabel; c.onclick = () => closeModal('other');
}
function openModal(title, text, inputVal, okLabel, cb){
  _modalCb = cb;
  const mc = $('#modal-cancel'); mc.textContent = 'Annuleren'; mc.onclick = () => closeModal(null);
  $('#modal-title').textContent = title || '';
  $('#modal-text').textContent = text || '';
  $('#modal-text').style.display = text ? 'block' : 'none';
  $('#modal-cancel').style.display = '';
  const inp = $('#modal-input'); inp.type = 'text'; inp.autocomplete = 'on'; inp.inputMode = ''; inp.removeAttribute('pattern');
  if (inputVal === null) { inp.style.display = 'none'; }
  else { inp.style.display = 'block'; inp.value = inputVal; }
  $('#modal-ok').textContent = okLabel;
  $('#modal').classList.add('on');
  dialogFocus.sync(inputVal === null ? $('#modal-cancel') : inp);
  if (inputVal !== null) setTimeout(() => { inp.focus(); inp.select(); }, 50);
}
function closeModal(result){
  $('#modal').classList.remove('on');
  const cb = _modalCb; _modalCb = null;
  $('#modal-input').value = ''; // geen wachtwoord in de pagina laten staan
  dialogFocus.sync();
  if (cb) cb(result);
}
$('#modal-cancel').onclick = () => closeModal(null);
$('#modal-ok').onclick = () => { const inp = $('#modal-input'); closeModal(inp.style.display === 'none' ? true : inp.value); };
$('#modal').onclick = e => { if (e.target === $('#modal')) closeModal(null); };
function dark(){ return uiDark(); } // het thema van de app (02-thema.js), niet alleen dat van de telefoon
/* Systeembalken in de kleur van de kop (boven) en de tabbalk of achtergrond (onder) */
function bars(){
  const cs = getComputedStyle(document.documentElement), v = n => cs.getPropertyValue(n).trim();
  const h = document.querySelector('.screen.on .hdr');
  let top = h && h.classList.contains('compact') ? v('--hi') : v('--bg');
  let bottom = document.body.classList.contains('tabs-on') ? v('--hi') : v('--bg');
  if (typeof plIsOpen !== 'undefined' && plIsOpen) { // speler (altijd donker) over het hele scherm
    top = '#12181B'; bottom = '#12181B';
    const key = top + bottom + 'pl'; if (key === bars.last) return; bars.last = key;
    Android.setBars(top, bottom, false); return;
  }
  if (current === 'erbook' && typeof erStyle === 'function') {
    const theme = erStyle().theme, paper0 = getComputedStyle($('#s-erbook')).getPropertyValue('--er-paper').trim();
    const paper = typeof erWarmTint === 'function' ? erWarmTint(paper0) : paper0; // "Prettig voor je ogen" kleurt de balken mee
    const light = theme === 'auto' ? !dark() : theme !== 'night';
    const key = JSON.stringify(['reader', paper, light]); if (key === bars.last) return; bars.last = key;
    Android.setBars(paper, paper, light); return;
  }
  const key = top + bottom + dark(); if (key === bars.last) return; bars.last = key;
  Android.setBars(top, bottom, !dark());
}

function show(name){
  if (typeof tsVoor === 'function' && tsVoor(name)) return; // extra ontgrendelen: eerst het slotscherm van die tool
  if(typeof cmNavigation==='function')cmNavigation(name);
  // Weg uit een automatisering die nog niet bewaard is (bijv. via een melding of snelkoppeling): als concept bewaren
  if (current === 'autoed' && name !== 'autoed' && typeof aeDirty === 'function' && aeDirty()) {
    store.set('autoDraft', { rule: aeRule, isNew: aeIsNew, t: Date.now(), left: true });
    aeRule = null; aeSnap = null;
  }
  if (current === 'erbook' && name !== 'erbook') erLeave(name);
  if (typeof erBook !== 'undefined') {
    if (!ER_SCREENS.includes(name) && ER_SCREENS.includes(current) && current !== 'erbook') erLeave(name);
    document.body.classList.toggle('er-reading', name === 'erbook');
  }
  if (typeof plIsOpen !== 'undefined' && plIsOpen) plClose(); // naar een ander scherm: de speler verkleint
  document.querySelectorAll('.screen').forEach(s=>s.classList.toggle('on', s.id==='s-'+name));
  const current_prev = current;
  if (current_prev==='idcopy' && name!=='idcopy') idCopyLeave();
  if (name==='idcopy') idCopyEnter();
  if (current_prev==='pdfcmp' && name!=='pdfcmp') cmpLeave(); // de te vergelijken bestanden weg uit de cache
  if (typeof pdfScr === 'function' && pdfScr(current_prev) && !pdfScr(name) && pdfIn && pdfIn.kluis && typeof Android.pdfKluisSluit === 'function') { Android.pdfKluisSluit(); pdfIn = {}; } // kluisdocument uit de bewerker
  if (name==='pdfcmp') enterPdfCmp();
  if (current_prev === 'aisetpers' && name !== 'aisetpers' && typeof aicLeavePers === 'function') aicLeavePers(); // concept niet kwijtraken
  if ((current_prev === 'home' || current_prev === 'search') && !jumpTo.busy) gsResetTool(name);
  if (name==='home') jumpStack.length = 0;
  if (name==='search' && gsQ) setTimeout(gsInput, 0); // terug van een treffer: opnieuw zoeken (er kan iets veranderd zijn)
  closeSheet();
  if (name !== current_prev && typeof sigOpen === 'function' && sigOpen()) sigClose(); // getekend voor het vorige scherm
  current = name; window.scrollTo(0,0);
  if (current_prev==='podspotify' && name!=='podspotify') pspPause();
  if (name==='podspotify') enterPodSpotify();
  if (name==='podlijst' && typeof enterPodLijst === 'function') enterPodLijst();
  if (name==='ai') enterAi(); if (name==='aiset' || (typeof AI_SUBS !== 'undefined' && AI_SUBS.includes(name))) enterAiSet(); if (name==='aichats') enterAiChats();
  if (current_prev==='ai' && name!=='ai' && typeof aiTalkStop === 'function') aiTalkStop(); // weg uit de assistent: het gesprek stopt
  if (current_prev==='ai' && name!=='ai' && typeof aiVoiceAbort === 'function') aiVoiceAbort();
  if (current_prev==='ai' && name!=='ai' && typeof aiSpeakStop === 'function') aiSpeakStop(); // weg uit de assistent: niet doorpraten
  // Focus op de titel van het scherm; een scherm kan een zichtbare titel aanwijzen (.titelfocus) als de kleine titel in de balk nog verborgen is
  if (current_prev !== name) setTimeout(() => { const h = document.querySelector('#s-' + name + ' .titelfocus') || document.querySelector('#s-' + name + ' h1'); if (h && !document.querySelector('#modal.on, #sigdlg.on, #voice.on, #ci-ans.on, #ci-rem.on, #lockscr.on, #toolslot.on, #sheet.on, #sleepdlg.on, #player.on')) h.focus({ preventScroll: true }); }, 0);
  if (name==='podcasts') enterPodcasts(); else if (name!=='podcast' && typeof pcStopPoll === 'function') pcStopPoll();
  if (name==='redial') enterRedial(); else stopPoll();
  if (WA_SCR.includes(name)) enterWa(name); else stopWaPoll();
  if (name==='chats') enterChats();
  if (name==='tracks') enterTracks(); else stopTrackPoll();
  if (name==='sms') enterSms(); else stopSmsPoll();
  if (name==='recorder') enterRecorder(); else stopRecorderPoll();
  if (name==='sleep') enterSleep(); else if (name==='sleepnight') enterSleepNight(); else if (typeof stopSleepPoll === 'function') stopSleepPoll();
  if (name==='sleepset') enterSleepSet();
  if (current_prev==='sleepnight' && name!=='sleepnight' && typeof Android.sleepStopPlay === 'function') { Android.sleepStopPlay(); slPlaying = ''; }
  if (name==='checkin') enterCheckin(); else if (name==='checkined') enterCheckinEd(); else if (name==='checkinq') enterCheckinQ(); else if (name==='checkincmp') enterCheckinCmp();
  if (name==='gebruik') enterGebruik(); else if (name==='gebruikset' || name==='gebruikontgr') enterGebruikSet();
  if (name==='models') enterModels(); else if (name==='spraak') enterSpraak(); else if (typeof mdLeave === 'function') mdLeave();
  if (name==='car' || name==='carset') enterCar();
  if (name==='apparaten' || name==='apdev' || name==='apset') enterAp(name); else if (typeof apLeave === 'function') apLeave();
  if (name!=='gebruik' && name!=='gebruikset' && name!=='gebruikontgr' && typeof gbLeave === 'function') gbLeave();
  if (typeof pdfScr === 'function' && pdfScr(name)) enterPdf(); // het PDF-overzicht en zijn taakschermen
  if (name==='formulieren') enterFormulieren();
  if (typeof CM_SCR !== 'undefined' && CM_SCR.includes(name)) enterMasterdata(name, current_prev); // Stamgegevens: één zone van schermen
  if (name==='spotify') enterSpotify();
  if (typeof FA_SCHERMEN !== 'undefined' && FA_SCHERMEN && FA_SCHERMEN.includes(name) && !FA_FORMS.includes(name)) enterFacturen();
  if (name==='ereader') enterEreader();
  if (name==='erbook') erResume();
  if (name==='ercontents') erContents();
  if (name==='erset') erSettings();
  if (name==='nieuws') enterNieuws(current_prev); else if (name==='nieuwsart') nwArtRender(); else if (name==='nieuwsset') enterNieuwsSet(); else if (name==='nieuwsbron') enterNieuwsBron();
  if ((name==='agenda' || name==='agafspraak' || name==='agaset') && typeof agEnter === 'function') agEnter(name);
  if (name==='bank') enterBank(); else if (current_prev==='bank' && typeof bkVerlaat === 'function') bkVerlaat();
  if (name==='vpn') enterVpn();
  if (name==='loc') enterLoc();
  if (name.startsWith('wc') && typeof enterWc === 'function') enterWc(name, current_prev);
  if (current_prev==='vpnedit' && name!=='vpnedit') { const tt = $('#ve-text'); if (tt) tt.value = ''; } // privésleutel niet laten staan
  if (current_prev==='vpn' && name!=='vpn' && typeof vpScanOpen === 'function' && vpScanOpen()) vpScanClose();
  if (name==='kluis') enterKluis(); else if (current_prev === 'kluis' && typeof Android.kluisClose === 'function') Android.kluisClose();
  if (name==='history') enterHistory(); else stopHistoryPoll();
  if (name==='calls') enterCalls(current_prev==='home'); else stopCallsPoll();
  if (current_prev==='note' && name!=='note') leaveNote();
  if (name==='notes') enterNotes();
  if (name==='contacts') enterContacts();
  if (name==='transcripts') enterTranscripts(); else stopTrPoll();
  if (name==='radio') enterRadio(); else stopRdPoll();
  if (name==='settings' || ST_SUBS.includes(name)) enterSettings();
  if (name==='setregioplek' && typeof rgpRender === 'function') rgpRender();
  if (name==='setregiotz' && typeof rgzRender === 'function') rgzRender();
  if (BK_SCR.includes(name)) enterBackup(); else stopBkPoll();
  if (name==='herstel') enterHerstel(); else if (current_prev==='herstel' && typeof hsLeave === 'function') hsLeave();
  if (name==='homeedit') enterHomeEdit(); else if (typeof heDragCancel === 'function') heDragCancel();
  if (name==='ralarm') enterAlarm();
  if (name==='selftest') enterSelfTest();
  if (name==='music') enterMusic(); else stopMuPoll();
  if (current_prev==='txd' && name!=='txd') leaveTrd();
  if (name==='cversions') enterVersions();
  if (name==='info') { if (current_prev !== 'info') infoAllOn = false; renderInfo(); }
  if (name==='auto') enterAuto();
  if (name==='autoed') enterAutoEd();
  if (typeof uiShown === 'function') uiShown(name);
  if (typeof tsNa === 'function') tsNa();
  if (name==='home') { refreshTile(); refreshTrackTile(); refreshCallsTile(); refreshNotesTile(); rdPollOnce(); bkPollOnce(); renderDash(); refreshAutoTile(); refreshPodTile(); refreshVpnTile(); refreshLocTile(); refreshCheckinTile(); }
  if (name==='home' && typeof updTryReload === 'function') setTimeout(() => updTryReload(false), 0);
}
const BACK = {ereader:'home', erbook:'ereader', ercontents:'erbook', erset:'erbook', nieuws:'home', nieuwsart:'nieuws', nieuwsset:'nieuws', nieuwsbron:'nieuwsset', agenda:'home', agafspraak:'agenda', agaset:'agenda', masterdata:'home', cmedit:'masterdata', cmlog:'masterdata', cmwis:'masterdata', cmlijst:'masterdata', cmkaart:'masterdata', cmai:'masterdata',idcopy:'pdf', formulieren:'pdf', pdfedit:'pdf', pdfimg:'pdf', pdfsplit:'pdf', pdftext:'pdf', pdfmade:'pdf', pdfcmp:'pdf', bank:'home', facturen:'home', faclev:'facturen', facoverz:'facturen', facopslag:'facturen', fackopp:'facturen', faclok:'facopslag', facgoogle:'fackopp', facec:'fackopp', facsmtp:'fackopp', facdos:'facturen', facstuk:'facdos', facaanl:'facturen', facontv:'facaanl', models:'aiset', aisetmodel:'aiset', aisetmem:'aiset', aisetdata:'aiset', aisetanswer:'aiset', aisetpers:'aiset', aisetvoice:'aiset', aisetsearch:'aiset', aisetperf:'aiset', aisetpriv:'aiset', setnotif:'settings', setlook:'settings', setregio:'settings', setregioplek:'setregio', setregiotz:'setregio', setskin:'settings', setlock:'settings', setsolo:'settings', setslot:'settings', setperm:'settings', setmusic:'settings', spraak:'settings', setcrash:'settings', ai:'home', aiset:'ai', aichats:'ai', search:'home', redial:'home', wa:'home', wasel:'wa', waauto:'wa', warestore:'wa', info:'home', chats:'wa', chat:'chats', tracks:'home', track:'tracks', sms:'home', smschat:'sms', calls:'home', history:'home', recorder:'home', sleep:'home', sleepnight:'sleep', sleepset:'sleep', checkin:'home', checkined:'checkin', checkinq:'checkin', checkincmp:'checkin', gebruik:'home', gebruikset:'gebruik', gebruikontgr:'gebruikset', apparaten:'home', apdev:'apparaten', apset:'apparaten', car:'home', carset:'car', kluis:'home', pdf:'home', podcasts:'home', podcast:'podcasts', podspotify:'podcasts', podlijst:'podcasts', spotify:'home', vpn:'home', vpnedit:'vpn', loc:'home', wc:'home', wcplek:'wc', wcbewerk:'wcplek', wcdeel:'wcplek', wcset:'wc', wcbronnen:'wcset', wcbron:'wcbronnen', wceigen:'wcset', wcgedeeld:'wcset', notes:'home', note:'notes', contacts:'home', contact:'contacts', cversions:'contacts', cversion:'cversions', transcripts:'home', txd:'transcripts', radio:'home', music:'home', settings:'home', backup:'home', bkparts:'backup', bkset:'backup', bkrestore:'backup', bkmap:'backup', herstel:'home', homeedit:'home', ralarm:'radio', selftest:'settings', auto:'home', autoed:'auto'};
/* Wat er bovenop het scherm ligt, één laag. 'lock' = de app mag sluiten. Zelfde volgorde voor de pijl en voor Start. */
function navSluit(){
  if (typeof sp !== 'undefined' && sp.settings && typeof spSettingsClose === 'function') { spSettingsClose(); return 'laag'; }
  if ($('#lockscr').classList.contains('on')) return 'lock';
  if (typeof tsTerug === 'function' && tsTerug()) return 'laag'; // een tool op slot: weg uit die tool (wat eronder ligt, is nog niet ontgrendeld)
  if (typeof cm !== 'undefined' && cm.picker) { cmClosePicker(); return 'laag'; }
  if (typeof fmq !== 'undefined' && fmq.open) { fmqClose(); return 'laag'; }
  if ($('#modal').classList.contains('on')) { closeModal(null); return 'laag'; }
  if ($('#sigdlg').classList.contains('on')) { sigClose(); return 'laag'; }
  if (typeof gbBack === 'function' && gbBack()) return 'laag';
  if (typeof aiTalkBack === 'function' && aiTalkBack()) return 'laag'; // eerst het gesprek met de assistent stoppen
  if ($('#voice') && $('#voice').classList.contains('on')) { voiceClose(); return 'laag'; }
  if ($('#ci-ans') && $('#ci-ans').classList.contains('on')) { ciAnsClose(); return 'laag'; }
  if ($('#ci-rem') && $('#ci-rem').classList.contains('on')) { ciRemClose(); return 'laag'; }
  if ($('#sleepdlg').classList.contains('on')) { sleepClose(); return 'laag'; }
  if ($('#vp-scan') && $('#vp-scan').classList.contains('on')) { vpScanClose(); return 'laag'; }
  if ($('#sheet').classList.contains('on')) { closeSheet(); return 'laag'; }
  if ($('#er-image').classList.contains('on')) { erImageClose(); return 'laag'; }
  if (typeof erPanelBack === 'function' && erPanelBack()) return 'laag'; // e-reader: eerst het paneel (leesweergave, voorlezen)
  if (current === 'erbook' && typeof erLinks !== 'undefined' && erLinks.length && typeof erLinkBack === 'function' && erLinkBack()) return 'laag';
  if (typeof plIsOpen !== 'undefined' && plIsOpen) { plClose(); return 'laag'; }
  return '';
}
/* Het begin van deze app: het startscherm van Tools, of het eerste scherm van een losse app. */
function navThuisVan(name){
  if (typeof SOLO !== 'undefined' && SOLO) {
    let n = name;
    for (let i = 0; i < 16 && n; i++) {
      if (typeof soloRoot === 'function' && soloRoot(n)) return n;
      const p = BACK[n];
      if (!p || (typeof soloOk === 'function' && !soloOk(p))) break;
      n = p;
    }
    if (typeof soloRoot === 'function' && n && soloRoot(n)) return n;
    return (typeof SOLO_ROOT === 'string' && SOLO_ROOT) || SOLO;
  }
  return 'home';
}
/* Eén stap terug, zoals de pijl (een sprong telt, en een losse app slaat een scherm van een andere tool over). */
function navStap(name){
  if (typeof jumpStack !== 'undefined' && jumpStack.length && jumpStack[jumpStack.length - 1].to === name) return jumpStack[jumpStack.length - 1].from;
  let d = BACK[name] || (name === 'home' ? '' : 'home');
  if (d && typeof soloOk === 'function' && !soloOk(d)) d = navThuisVan(name);
  return d;
}
function navTab(name){
  if (typeof SOLO !== 'undefined' && SOLO) return false;
  const tabs = (typeof TABS !== 'undefined' && TABS) || ['home', 'search', 'ai', 'backup', 'settings'];
  return tabs.includes(name);
}
/* Start tonen als één stap terug nog niet het begin van deze app is. */
function navThuisZicht(name){
  name = name || current;
  if (!name || navTab(name)) return false;
  if (typeof SOLO !== 'undefined' && SOLO && typeof soloRoot === 'function' && soloRoot(name)) return false;
  const thuis = navThuisVan(name);
  if (!thuis || thuis === name) return false;
  return navStap(name) !== thuis;
}
function navGa(){
  const dest = navThuisVan(current);
  jumpStack.length = 0;
  if (dest && dest !== current) show(dest);
}
/* Meteen naar het begin. Een laag gaat eerst dicht; niet-bewaarde wijzigingen vragen we, net als bij de pijl. */
function navHome(){
  const s = navSluit();
  if (s === 'lock') return false;
  if (s) return true;
  if (current === 'autoed' && typeof aeDirty === 'function' && aeDirty()) {
    askChoice('Wijzigingen opslaan?', 'Je hebt deze automatisering aangepast.', 'Opslaan', 'Weggooien', () => aeSave(() => navGa()), () => { aeRule = null; aeSnap = null; store.set('autoDraft', null); navGa(); });
    return true;
  }
  if (current === 'cmedit' && typeof cm !== 'undefined') {
    if (cm.mutating) return true;
    const weg = () => { cm.dirty = false; cm.editorEpoch++; navGa(); };
    if (cm.dirty) { askConfirm('Bewerking sluiten?', 'Niet-bewaarde wijzigingen gaan verloren.', 'Sluiten', weg); return true; }
    weg();
    return true;
  }
  if (current === 'checkined' && typeof cedDirty === 'function' && cedDirty()) {
    askChoice('Wijzigingen bewaren?', 'Je hebt deze vraag aangepast.', 'Bewaren', 'Weggooien', () => { cedNaar = navGa; cedSave(); }, () => { cedQ = null; cedSnap = ''; navGa(); });
    return true;
  }
  if (current === 'formulieren' && typeof fm !== 'undefined' && fm.view === 'stap') {
    if (fm.busy) return true;
    if (typeof fmConceptVanzelf === 'function') fmConceptVanzelf();
  }
  if (current === 'herstel' && typeof hsBack === 'function' && !(jumpStack.length && jumpStack[jumpStack.length - 1].to === 'herstel')) {
    const welkom = $('#hs-welkom');
    try { if (welkom && welkom.style.display !== 'none' && hsState().pending && hsBack()) return true; } catch (e) {}
  }
  navGa();
  return true;
}
window.goBack = function(){
  // Eerst wat bovenop ligt: slotscherm (dan de app verlaten), dialoog, menu
  const s = navSluit();
  if (s === 'lock') return false;
  if (s) return true;
  if (current === 'autoed' && typeof aeDirty === 'function' && aeDirty()) {
    askChoice('Wijzigingen opslaan?', 'Je hebt deze automatisering aangepast.', 'Opslaan', 'Weggooien', () => aeSave(), () => { aeRule = null; aeSnap = null; store.set('autoDraft', null); show('auto'); });
    return true;
  }
  if (current === 'cmedit' && typeof cmEditBack === 'function' && cmEditBack()) return true; // eerst vragen bij niet-bewaarde wijzigingen
  if (typeof cmBack === 'function' && cmBack()) return true; // Stamgegevens: lijsten en visitekaartjes een stap terug
  if (current === 'checkined' && typeof cedDirty === 'function' && cedDirty()) {
    askChoice('Wijzigingen bewaren?', 'Je hebt deze vraag aangepast.', 'Bewaren', 'Weggooien', () => cedSave(), () => cedCancel());
    return true;
  }
  // Na een sprong: terug naar waar je vandaan kwam, maar alleen vanaf het scherm waar je naartoe sprong
  // (ben je binnen de tool verder gegaan, dan eerst gewoon terug binnen de tool).
  if (current === 'formulieren' && typeof fmTerugNaarStart === 'function' && fmTerugNaarStart()) return true;
  if (jumpStack.length && jumpStack[jumpStack.length - 1].to === current) { show(jumpStack.pop().from); return true; }
  if (current === 'herstel' && typeof hsBack === 'function' && hsBack()) return true;
  if (current !== 'home') {
    let dest = BACK[current] || 'home';
    if (typeof soloOk === 'function' && !soloOk(dest)) dest = navThuisVan(current);
    if (dest && dest !== current) show(dest);
    return true;
  }
  return false;
};
/* Sprongen tussen tools (oproep → contact → oproepen): terug gaat naar waar je vandaan kwam. */
const jumpStack = [];
function jumpTo(name, open){ const from = current; jumpTo.busy = true; try { if (open) open(); else show(name); } finally { jumpTo.busy = false; } if (current !== from) jumpStack.push({ from, to: current }); }
let sheetAt = 0;
/* onDismiss: als het menu dicht gaat zonder dat er iets gekozen is (terug, buiten tikken) */
let sheetDismiss = null;
function openSheet(title, sub, acts, onDismiss){
  const prev = sheetDismiss; sheetDismiss = null; if (prev) prev(); // een ander menu dat nog open stond
  sheetDismiss = onDismiss || null;
  sheetAt = Date.now(); // het loslaten na lang indrukken mag het menu niet meteen sluiten
  $('#sheet-title').textContent = title;
  const subEl = $('#sheet-sub'); subEl.removeAttribute('translate'); subEl.textContent = sub || '';
  const box = $('#sheet-acts'); box.innerHTML = '';
  acts.forEach(([label, fn, icon]) => { const b = document.createElement('button'); b.textContent = label; if(UI_ICONS.has(icon))b.dataset.icon=icon; b.onclick = () => { sheetDismiss = null; closeSheet(); fn(); }; box.appendChild(b); });
  $('#sheet').classList.add('on');
  dialogFocus.sync();
}
function closeSheet(){ const s = document.getElementById('sheet'); if (s) s.classList.remove('on'); const sub = document.getElementById('sheet-sub'); if (sub && sub.dataset.wacht !== undefined) uiWachtUit(sub); if (typeof dialogFocus !== 'undefined') dialogFocus.sync(); const d = sheetDismiss; sheetDismiss = null; if (d) d(); }

window.openTool = function(n){
  if (n === 'share') { noteFromShare(); return; }
  if (n === 'skin') { Android.homeOpen(); return; }
  if (n === 'auto-rec') { autoRecBack(); return; }
  if (n === 'player') { if (SOLO === 'player') show('radio'); rdPollOnce(true); pcPoll(false); if (plModel()) plOpen(); else show('radio'); return; }
  if (n === 'auto') { store.set('autoDraft', null); if (current !== 'autoed') show('auto'); return; }
  if (n === 'music-now') { show('music'); if (!$('#lockscr').classList.contains('on')) setTimeout(() => { const st = rdJson(Android.musicState(), {}); if (current === 'music' && !$('#lockscr').classList.contains('on') && st.state !== 'recording' && st.state !== 'sending') muToggle(); }, 400); return; }
  if (typeof n === 'string' && n.startsWith('note:')) { show('notes'); openNote(n.slice(5)); return; }
  if (typeof n === 'string' && n.startsWith('checkin')) { ciOpenFrom(n); return; }
  if (n === 'pdf-share') { pdfShared(); return; }
  // E-reader (ook de voorleesmelding) terwijl er een boek wordt voorgelezen: naar dat boek
  if (n === 'ereader' && typeof spSync === 'function') spSync(); // koude start vanuit de melding: eerst de lopende sessie kennen
  if (n === 'ereader' && typeof erSpeakNow === 'function' && erSpeakNow()) { erSpeakOpen(); return; }
  if (typeof n === 'string' && n.startsWith('facturen-meekijken:')) { show('facturen'); tsDaarna('facturen', () => setTimeout(() => askConfirm('Inloggen nodig', 'De app kon niet vanzelf inloggen. Kijk mee om zelf in te loggen; daarna gaat de app verder.', 'Meekijken', () => faNu(n.slice(19), true)), 400)); return; }
  if (n === 'bkmap') { show('backup'); jumpTo('bkmap'); return; }
  // Nieuwe tools openen vanzelf via het register; systeembestemmingen blijven expliciet.
  if ((Object.prototype.hasOwnProperty.call(TOOLS, n) || ['settings', 'herstel', 'selftest', 'models'].includes(n)) && document.getElementById('s-' + n)) show(n);
};
window.onResumeApp = function(){ appPaused = false; if (typeof applyTheme === 'function') applyTheme(); if (typeof plTick === 'function') plTick(); if (current==='car' || current==='carset') enterCar(); if (current==='kluis') klRender(); if (typeof pdfScr === 'function' && pdfScr(current)) pdfResume(); if (current==='podcasts') pcPoll(true); if (current==='podspotify') enterPodSpotify(); if (current==='podlijst') enterPodLijst(); if (current==='spotify') spRender(); if (typeof FA_SCHERMEN !== 'undefined' && FA_SCHERMEN && FA_SCHERMEN.includes(current) && !FA_FORMS.includes(current)) faRender(); if (current==='bank') enterBank(); if (current==='nieuws') nwResume(); if (current==='vpn') vpRender(); if (current==='loc') lcRender(); if (typeof wcResume === 'function') wcResume(); uncache('smsInfo'); uncache('waInfo'); if (current==='sms') enterSms(); if (current==='recorder') loadRecorder(); if (current==='sleep') enterSleep(); if (current==='sleepnight') enterSleepNight(); if (current==='sleepset') enterSleepSet(); if (current==='checkin') enterCheckin(); if (current==='checkinq') ciqLoad(); if (current==='checkincmp') cicRender(); if (current==='gebruik') enterGebruik(); if (current==='gebruikset' || current==='gebruikontgr') enterGebruikSet(); if (current==='home') refreshCheckinTile(); if (current==='history') loadHistory(); if (current==='redial'){ renderPerms(); pollOnce(); } if (WA_SCR.includes(current)) renderWa(current === 'wa' || current === 'wasel'); if (current==='chats') { waInfoC = waInfo(); renderReadable(); } if (current==='tracks') enterTracks(); if (current==='contacts') enterContacts(); if (current==='transcripts') enterTranscripts(); if (stOn()) enterSettings(); if (current==='home') renderDash(); if (current==='auto') enterAuto(); if ((!SOLO || SOLO === 'auto') && (current==='autoed' || store.get('autoDraft', null))) autoDraftBack(); if (current==='models') mdResumed(); else if (current==='spraak' && typeof enterSpraak === 'function') enterSpraak(); if (current==='selftest' && sfRecheck) { sfRecheck = false; if (!$('#sf-run').disabled) sfRun(); } if (current==='contact') { Android.contactsSnapshot(); ctRenderDetail(); } refreshTile(); refreshWaTile(); refreshTrackTile(); };

