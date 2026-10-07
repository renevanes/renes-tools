/* ---------- Notities: herinneringen en delen ---------- */
let noteRems = {};
function loadRems(){ try { noteRems = JSON.parse(Android.noteReminders()) || {}; } catch(e){ noteRems = {}; } }
function localIso(t){ const d = new Date(t - new Date(t).getTimezoneOffset() * 6e4); return d.toISOString().slice(0, 16); }
const REM_REP = { day: 'elke dag', weekdays: 'werkdagen', week: 'elke week', month: 'elke maand' };
function noteRemList(id){
  if (typeof Android.noteRemindList !== 'function') { const t = noteRems[id]; return t ? [{ id, t, rep: '' }] : []; }
  try { return JSON.parse(Android.noteRemindList(id)) || []; } catch(e){ return []; }
}
function noteRemShow(){
  loadRems();
  const l = noteRemList(noteCur);
  $('#note-rem').value = localIso(Date.now() + 36e5);
  $('#note-rem-del').style.display = l.length > 1 ? 'block' : 'none';
  $('#note-rem-list').innerHTML = l.map(r => '<div class="remrow"><span>🔔 ' + esc(new Date(r.t).toLocaleString('nl-NL', {weekday:'short', day:'numeric', month:'short', hour:'2-digit', minute:'2-digit'})) +
    (r.rep ? ' <small>· ' + esc(REM_REP[r.rep] || '') + '</small>' : '') + '</span><button class="mini" data-id="' + esc(r.id) + '" onclick="noteDelOneRem(this.dataset.id)" aria-label="Herinnering weghalen">Weghalen</button></div>').join('');
}
function noteDelOneRem(rid){ if (typeof Android.noteRemindDel === 'function') Android.noteRemindDel(rid); else Android.noteRemind(rid, '', '0'); noteRemShow(); }
function noteSetRem(){
  const n = noteById(noteCur); if (!n) return;
  const v = $('#note-rem').value; if (!v) { toast('Kies een datum en tijd'); return; }
  const t = new Date(v).getTime();
  if (!(t > Date.now())) { toast('Kies een moment in de toekomst'); return; }
  notesSaveNow();
  const rep = $('#note-rem-rep').value;
  if (typeof Android.noteRemindAdd === 'function') Android.noteRemindAdd(n.id, n.title || 'Notitie', String(t), rep);
  else Android.noteRemind(n.id, n.title || 'Notitie', String(t));
  $('#note-rem-rep').value = '';
  noteRemShow(); toast('🔔 Herinnering gezet' + (rep ? ' (' + REM_REP[rep] + ')' : ''));
}
/* ---------- Snelkoppelingen naar één notitie ---------- */
function notePins(where){ try { return JSON.parse(Android.notePins(where)) || []; } catch(e){ return []; } }
function notePinShow(){
  const id = noteCur, skin = Android.homeIsDefault();
  $('#note-pin-app').checked = notePins('app').includes(id);
  const onSkin = notePins('skin').includes(id);
  $('#note-pin-phone').textContent = skin ? (onSkin ? 'Van de telefoon-skin halen' : 'Op de telefoon-skin zetten') : 'Op het startscherm van je telefoon';
  $('#note-pin-info').textContent = skin && onSkin ? '✓ Staat op je telefoon-skin.' : 'Zo open je dit lijstje (bijvoorbeeld je boodschappen) met één tik.';
}
function notePinApp(on){ notesSaveNow(); Android.notePinSet('app', noteCur, on); toast(on ? '📌 Staat op het startscherm van Rene\'s Tools' : 'Van het startscherm gehaald'); }
function notePinPhone(){
  const n = noteById(noteCur); if (!n) return;
  notesSaveNow();
  if (Android.homeIsDefault()) {
    // De telefoon-skin is het startscherm: die toont vastgezette notities zelf.
    const on = !notePins('skin').includes(n.id);
    Android.notePinSet('skin', n.id, on); notePinShow();
    toast(on ? '📌 Staat op je telefoon-skin' : 'Van de telefoon-skin gehaald'); return;
  }
  const e = Android.shortcutPinNote(n.id, n.title || 'Notitie');
  toast(e || 'Bevestig op je startscherm om ' + (n.title || 'deze notitie') + ' toe te voegen');
}
function noteDelRem(){ askConfirm('Alle herinneringen weghalen?', '', 'Weghalen', () => { if (typeof Android.noteRemindClear === 'function') Android.noteRemindClear(noteCur); else Android.noteRemind(noteCur, '', '0'); noteRemShow(); }); }
function noteShareNow(){
  const n = noteById(noteCur); if (!n) return;
  const items = n.items || [];
  if (items.some(i => i.done) && items.some(i => !i.done))
    openSheet('Delen', n.title || 'Notitie', [['Hele lijst (met vinkjes)', () => noteShareDo(false)], ['Alleen wat nog moet', () => noteShareDo(true)]]);
  else noteShareDo(false);
}
function noteShareDo(openOnly){
  const n = noteById(noteCur); if (!n) return;
  const lines = [];
  (n.items || []).filter(i => !i.done).forEach(i => lines.push('☐ ' + i.text));
  if (!openOnly) (n.items || []).filter(i => i.done).forEach(i => lines.push('☑ ' + i.text));
  const text = (n.title ? n.title + '\n\n' : '') + lines.join('\n') + (n.text ? (lines.length ? '\n\n' : '') + n.text : '');
  if (!text.trim()) { toast('De notitie is nog leeg'); return; }
  Android.noteShare(n.title || '', text);
}
/** Tekst gedeeld vanuit een andere app → nieuwe notitie. Regels die op een lijstje lijken worden items. */
function noteFromShare(){
  let d; try { d = JSON.parse(Android.pendingShare() || '{}'); } catch(e){ d = {}; }
  if (!d.text) { show('notes'); return; }
  const lines = d.text.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
  const listy = lines.length >= 2 && lines.every(x => x.length < 120);
  const n = { id: uid(), title: (d.title || (listy ? '' : lines[0] || '')).slice(0, 80), text: listy ? '' : d.text, items: [], updated: Date.now() };
  if (listy) lines.forEach(x => n.items.push({ id: uid(), text: x.replace(/^([-*•☐☑✓]|\[[ x]\])\s*/i, ''), done: /^(☑|✓|\[x\])/i.test(x) }));
  notesGet().push(n); notesSaveNow();
  show('notes'); openNote(n.id); toast('Nieuwe notitie gemaakt van gedeelde tekst');
}

