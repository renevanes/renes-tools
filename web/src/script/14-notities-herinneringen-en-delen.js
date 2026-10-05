/* ---------- Notities: herinneringen en delen ---------- */
let noteRems = {};
function loadRems(){ try { noteRems = JSON.parse(Android.noteReminders()) || {}; } catch(e){ noteRems = {}; } }
function localIso(t){ const d = new Date(t - new Date(t).getTimezoneOffset() * 6e4); return d.toISOString().slice(0, 16); }
function noteRemShow(){
  loadRems();
  const t = noteRems[noteCur];
  $('#note-rem').value = t ? localIso(t) : localIso(Date.now() + 36e5);
  $('#note-rem-del').style.display = t ? 'block' : 'none';
  $('#note-rem-info').textContent = t ? '🔔 Herinnering op ' + new Date(t).toLocaleString('nl-NL', {weekday:'long', day:'numeric', month:'long', hour:'2-digit', minute:'2-digit'}) : '';
}
function noteSetRem(){
  const n = noteById(noteCur); if (!n) return;
  const v = $('#note-rem').value; if (!v) { toast('Kies een datum en tijd'); return; }
  const t = new Date(v).getTime();
  if (!(t > Date.now())) { toast('Kies een moment in de toekomst'); return; }
  notesSaveNow();
  Android.noteRemind(n.id, n.title || 'Notitie', String(t)); noteRemShow(); toast('🔔 Herinnering gezet');
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
function noteDelRem(){ Android.noteRemind(noteCur, '', '0'); noteRemShow(); }
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

