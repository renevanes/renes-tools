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
const WS_SKIP = /^(autoDraft|seenVersion|askedPerms|.*Asked)$/;
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
  try { Object.keys(o).forEach(k => { if (k.startsWith('rt.') && localStorage.getItem(k) == null) localStorage.setItem(k, o[k]); }); } catch(e){}
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
function toast(m){ const t=$('#toast'); t.textContent=m; t.classList.add('on'); clearTimeout(t._h); t._h=setTimeout(()=>t.classList.remove('on'),2600); }

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
  const inp = $('#modal-input'); inp.type = 'text'; inp.autocomplete = 'on';
  if (inputVal === null) { inp.style.display = 'none'; }
  else { inp.style.display = 'block'; inp.value = inputVal; }
  $('#modal-ok').textContent = okLabel;
  $('#modal').classList.add('on');
  if (inputVal !== null) setTimeout(() => { inp.focus(); inp.select(); }, 50);
}
function closeModal(result){
  $('#modal').classList.remove('on');
  const cb = _modalCb; _modalCb = null;
  $('#modal-input').value = ''; // geen wachtwoord in de pagina laten staan
  if (cb) cb(result);
}
$('#modal-cancel').onclick = () => closeModal(null);
$('#modal-ok').onclick = () => { const inp = $('#modal-input'); closeModal(inp.style.display === 'none' ? true : inp.value); };
$('#modal').onclick = e => { if (e.target === $('#modal')) closeModal(null); };
function dark(){ return matchMedia('(prefers-color-scheme: dark)').matches; }
function bars(){ const cs=getComputedStyle(document.documentElement); Android.setBars(cs.getPropertyValue('--brand').trim(), cs.getPropertyValue('--bg').trim(), !dark()); }

function show(name){
  // Weg uit een automatisering die nog niet bewaard is (bijv. via een melding of snelkoppeling): als concept bewaren
  if (current === 'autoed' && name !== 'autoed' && typeof aeDirty === 'function' && aeDirty()) {
    store.set('autoDraft', { rule: aeRule, isNew: aeIsNew, t: Date.now(), left: true });
    aeRule = null; aeSnap = null;
  }
  document.querySelectorAll('.screen').forEach(s=>s.classList.toggle('on', s.id==='s-'+name));
  const current_prev = current;
  if (current_prev === 'home' && !jumpTo.busy) gsResetTool(name);
  if (name==='home') { jumpStack.length = 0; if (gsQ) setTimeout(gsInput, 0); }
  closeSheet();
  current = name; window.scrollTo(0,0);
  if (current_prev !== name) setTimeout(() => { const h = document.querySelector('#s-' + name + ' h1'); if (h && !document.querySelector('#modal.on, #voice.on, #lockscr.on, #sheet.on')) h.focus({ preventScroll: true }); }, 0);
  if (name==='redial') enterRedial(); else stopPoll();
  if (name==='wa') enterWa(); else stopWaPoll();
  if (name==='chats') enterChats();
  if (name==='tracks') enterTracks(); else stopTrackPoll();
  if (name==='sms') enterSms(); else stopSmsPoll();
  if (name==='recorder') enterRecorder(); else stopRecorderPoll();
  if (name==='car') enterCar();
  if (name==='pdf') enterPdf();
  if (name==='kluis') enterKluis(); else if (current_prev === 'kluis' && typeof Android.kluisClose === 'function') Android.kluisClose();
  if (name==='history') enterHistory(); else stopHistoryPoll();
  if (name==='calls') enterCalls(current_prev==='home'); else stopCallsPoll();
  if (current_prev==='note' && name!=='note') leaveNote();
  if (name==='notes') enterNotes();
  if (name==='contacts') enterContacts();
  if (name==='transcripts') enterTranscripts(); else stopTrPoll();
  if (name==='radio') enterRadio(); else stopRdPoll();
  if (name==='settings') enterSettings();
  if (name==='backup') enterBackup(); else stopBkPoll();
  if (name==='homeedit') enterHomeEdit();
  if (name==='ralarm') enterAlarm();
  if (name==='selftest') enterSelfTest();
  if (name==='music') enterMusic(); else stopMuPoll();
  if (current_prev==='txd' && name!=='txd') leaveTrd();
  if (name==='cversions') enterVersions();
  if (name==='info') renderInfo();
  if (name==='auto') enterAuto();
  if (name==='autoed') enterAutoEd();
  if (name==='home') { refreshTile(); refreshTrackTile(); refreshCallsTile(); refreshNotesTile(); rdPollOnce(); bkPollOnce(); renderDash(); refreshAutoTile(); }
}
const BACK = {redial:'home', wa:'home', info:'home', chats:'wa', chat:'chats', tracks:'home', track:'tracks', sms:'home', smschat:'sms', calls:'home', history:'home', recorder:'home', car:'home', kluis:'home', pdf:'home', notes:'home', note:'notes', contacts:'home', contact:'contacts', cversions:'contacts', cversion:'cversions', transcripts:'home', txd:'transcripts', radio:'home', music:'home', settings:'home', backup:'home', homeedit:'home', ralarm:'radio', selftest:'settings', auto:'home', autoed:'auto'};
window.goBack = function(){
  // Eerst wat bovenop ligt: slotscherm (dan de app verlaten), dialoog, menu
  if ($('#lockscr').classList.contains('on')) return false;
  if ($('#modal').classList.contains('on')) { closeModal(null); return true; }
  if ($('#voice') && $('#voice').classList.contains('on')) { voiceClose(); return true; }
  if ($('#sheet').classList.contains('on')) { closeSheet(); return true; }
  if (current === 'autoed' && typeof aeDirty === 'function' && aeDirty()) {
    askChoice('Wijzigingen opslaan?', 'Je hebt deze automatisering aangepast.', 'Opslaan', 'Weggooien', () => aeSave(), () => { aeRule = null; aeSnap = null; store.set('autoDraft', null); show('auto'); });
    return true;
  }
  if (current==='home' && gsQ) { $('#gs-q').value = ''; gsInput(); return true; }
  // Na een sprong: terug naar waar je vandaan kwam, maar alleen vanaf het scherm waar je naartoe sprong
  // (ben je binnen de tool verder gegaan, dan eerst gewoon terug binnen de tool).
  if (jumpStack.length && jumpStack[jumpStack.length - 1].to === current) { show(jumpStack.pop().from); return true; }
  if (current!=='home'){ show(BACK[current]||'home'); return true; } return false; };
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
  $('#sheet-title').textContent = title; $('#sheet-sub').textContent = sub || '';
  const box = $('#sheet-acts'); box.innerHTML = '';
  acts.forEach(([label, fn]) => { const b = document.createElement('button'); b.textContent = label; b.onclick = () => { sheetDismiss = null; closeSheet(); fn(); }; box.appendChild(b); });
  $('#sheet').classList.add('on');
}
function closeSheet(){ const s = document.getElementById('sheet'); if (s) s.classList.remove('on'); const d = sheetDismiss; sheetDismiss = null; if (d) d(); }

window.openTool = function(n){
  if (n === 'share') { noteFromShare(); return; }
  if (n === 'skin') { Android.homeOpen(); return; } // snelkoppeling "Telefoon-skin": meteen de skin tonen
  if (n === 'auto-rec') { autoRecBack(); return; } // terug na opnemen in een andere app
  if (n === 'auto') { store.set('autoDraft', null); if (current !== 'autoed') show('auto'); return; } // ✕ bij opnemen
  if (n === 'music-now' && $('#lockscr').classList.contains('on')) { show('music'); return; } // op slot: niet zomaar opnemen
  if (n === 'music-now') { show('music'); setTimeout(() => { const st = rdJson(Android.musicState(), {}); if (current === 'music' && !$('#lockscr').classList.contains('on') && st.state !== 'recording' && st.state !== 'sending') muToggle(); }, 400); return; }
  if (typeof n === 'string' && n.startsWith('note:')) { show('notes'); openNote(n.slice(5)); return; } if (n==='redial') show('redial'); if (n==='wa') show('wa'); if (n==='tracks') show('tracks'); if (n==='sms') show('sms'); if (n==='calls') show('calls'); if (n==='history') show('history'); if (n==='recorder') show('recorder'); if (n==='car') show('car'); if (n==='pdf' || n==='pdf-share') show('pdf'); if (n==='kluis') show('kluis'); if (n==='notes') show('notes'); if (n==='contacts') show('contacts'); if (n==='transcripts') show('transcripts'); if (n==='radio') show('radio'); if (n==='music') show('music'); if (n==='settings') show('settings'); if (n==='backup') show('backup'); if (n==='selftest') show('selftest'); };
window.onResumeApp = function(){ appPaused = false; if (current==='car') enterCar(); if (current==='kluis') klRender(); if (current==='pdf') enterPdf(); uncache('smsInfo'); uncache('waInfo'); if (current==='sms') enterSms(); if (current==='recorder') loadRecorder(); if (current==='history') loadHistory(); if (current==='redial'){ renderPerms(); pollOnce(); } if (current==='wa') renderWa(true); if (current==='tracks') enterTracks(); if (current==='contacts') enterContacts(); if (current==='transcripts') enterTranscripts(); if (current==='settings') enterSettings(); if (current==='home') renderDash(); if (current==='auto') enterAuto(); if (current==='autoed' || store.get('autoDraft', null)) autoDraftBack(); if (current==='selftest' && sfRecheck) { sfRecheck = false; if (!$('#sf-run').disabled) sfRun(); } if (current==='contact') { Android.contactsSnapshot(); ctRenderDetail(); } refreshTile(); refreshWaTile(); refreshTrackTile(); };

