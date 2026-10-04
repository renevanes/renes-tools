/* ---------- Notities ---------- */
let notes = null, noteCur = null, notesSaveTmr = null, notesBroken = false;
function notesGet(){
  if (notes === null) {
    let raw = ''; try { raw = Android.notesLoad(); } catch(e){}
    try { notes = JSON.parse(raw); if (!Array.isArray(notes)) throw 0; }
    catch(e){ notes = []; notesBroken = true; toast('Notities konden niet worden gelezen; er wordt niets overschreven'); }
  }
  return notes;
}
function uid(){ return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
function notesSaveSoon(){ clearTimeout(notesSaveTmr); notesSaveTmr = setTimeout(notesSaveNow, 400); }
function notesSaveNow(){
  clearTimeout(notesSaveTmr); notesSaveTmr = null;
  if (notesBroken || notes === null) return;
  const err = Android.notesSave(JSON.stringify(notesGet()));
  if (err) toast('Opslaan mislukt: ' + err);
}
function hl(text, q){
  const t = esc(text); if (!q) return t;
  const i = text.toLowerCase().indexOf(q); if (i < 0) return t;
  return esc(text.slice(0, i)) + '<mark>' + esc(text.slice(i, i + q.length)) + '</mark>' + esc(text.slice(i + q.length));
}
function enterNotes(){ loadRems(); renderNotes(); refreshNotesTile(); }
function renderNotes(){
  const q = $('#notes-search').value.trim().toLowerCase();
  const list = notesGet().slice().sort((a, b) => (b.updated||0) - (a.updated||0));
  const rows = [];
  for (const n of list) {
    const items = n.items || [], open = items.filter(i => !i.done).length;
    const title = n.title || 'Zonder titel', flat = (n.text||'').replace(/\s+/g, ' ');
    let sub;
    if (q) {
      const hits = items.filter(i => i.text.toLowerCase().includes(q));
      const inTitle = title.toLowerCase().includes(q), inText = flat.toLowerCase().includes(q);
      if (!inTitle && !inText && !hits.length) continue;
      sub = hits.length ? hits.slice(0, 4).map(i => (i.done ? '✓ ' : '') + hl(i.text, q)).join(' · ')
          : inText ? hl(flat.slice(Math.max(0, flat.toLowerCase().indexOf(q) - 20)), q) : '';
    } else {
      sub = items.length ? esc(items.filter(i => !i.done).slice(0, 5).map(i => i.text).join(' · ') || 'Alles afgestreept') : esc(flat.slice(0, 80));
    }
    const rem = noteRems[n.id];
    rows.push('<button class="nrow" onclick="openNote(\'' + esc(n.id) + '\')"><span class=r1><b>' + (rem ? '<span class=nrem>🔔</span> ' : '') + hl(title, q) + '</b><small>' +
      (items.length ? (items.length - open) + '/' + items.length + ' ✓ · ' : '') + esc(fmtD(n.updated||0)) + '</small></span>' +
      '<span class=r2>' + (sub || '&nbsp;') + '</span></button>');
  }
  $('#notes-list').innerHTML = rows.join('') || '<p class=note style="padding:14px">' +
    (q ? 'Niets gevonden.' : 'Nog geen notities. Tik op + om een lijstje te maken.') + '</p>';
}
function noteById(id){ return notesGet().find(n => n.id === id); }
function newNote(){
  const n = { id: uid(), title: '', text: '', items: [], updated: Date.now() };
  notesGet().push(n); openNote(n.id, true);
}
function openNote(id, fresh){
  noteCur = id; const n = noteById(id); if (!n) return;
  $('#note-title').value = n.title || ''; $('#note-text').value = n.text || ''; $('#note-add').value = '';
  show('note'); renderItems(); noteRemShow();
  if (fresh) setTimeout(() => $('#note-title').focus(), 50);
}
function touch(n){ n.updated = Date.now(); notesSaveSoon(); }
function noteEdit(field, v){ const n = noteById(noteCur); if (!n) return; n[field] = v; touch(n); }
function renderItems(){
  const n = noteById(noteCur); if (!n) return;
  const row = it => '<li class="' + (it.done ? 'done' : '') + '"><input type=checkbox ' + (it.done ? 'checked' : '') +
    ' onchange="toggleItem(\'' + it.id + '\')" aria-label="Afstrepen"><input class=it value="' + esc(it.text) +
    '" oninput="typeItem(\'' + it.id + '\', this.value)" onchange="editItem(\'' + it.id + '\', this.value)"><button class=del onclick="delItem(\'' + it.id + '\')" aria-label="Verwijderen">×</button></li>';
  const items = n.items || [];
  const done = items.filter(i => i.done);
  $('#note-open').innerHTML = items.filter(i => !i.done).map(row).join('');
  $('#note-done').innerHTML = done.map(row).join('');
  $('#note-donehdr').style.display = done.length ? 'flex' : 'none';
  $('#note-donecount').textContent = done.length + ' afgestreept';
}
function addItem(){
  const inp = $('#note-add'), t = inp.value.trim(); if (!t) { inp.focus(); return; }
  const n = noteById(noteCur); if (!n) return;
  // Meerdere regels plakken = meerdere items
  t.split(/\n+/).map(x => x.trim()).filter(Boolean).forEach(x => n.items.push({ id: uid(), text: x, done: false }));
  inp.value = ''; touch(n); renderItems(); inp.focus();
}
function toggleItem(id){
  const n = noteById(noteCur); const it = n && n.items.find(i => i.id === id); if (!it) return;
  it.done = !it.done; touch(n); renderItems();
}
function editItem(id, v){
  const n = noteById(noteCur); const it = n && n.items.find(i => i.id === id); if (!it) return;
  if (!v.trim()) { delItem(id); return; }
  it.text = v.trim(); touch(n);
}
function typeItem(id, v){
  const n = noteById(noteCur); const it = n && n.items.find(i => i.id === id); if (!it || !v.trim()) return;
  it.text = v; touch(n);
}
document.addEventListener('visibilitychange', () => { if (document.hidden && notesSaveTmr) notesSaveNow(); });
window.onPauseApp = function(){ if (notesSaveTmr) notesSaveNow(); };
function delItem(id){ const n = noteById(noteCur); if (!n) return; n.items = n.items.filter(i => i.id !== id); touch(n); renderItems(); }
function clearDone(){
  const n = noteById(noteCur); if (!n) return;
  const k = n.items.filter(i => i.done).length;
  askConfirm('Afgevinkte verwijderen?', k + ' afgestreepte items worden verwijderd.', 'Verwijderen', () => {
    n.items = n.items.filter(i => !i.done); touch(n); renderItems(); });
}
function deleteNote(){
  const n = noteById(noteCur); if (!n) return;
  askConfirm('Notitie verwijderen?', '"' + (n.title || 'Zonder titel') + '" wordt definitief verwijderd.', 'Verwijderen', () => {
    Android.noteRemind(noteCur, '', '0');
    notes = notesGet().filter(x => x.id !== noteCur); noteCur = null; notesSaveNow(); show('notes'); });
}
function leaveNote(){
  const n = noteById(noteCur);
  if (n && !(n.title||'').trim() && !(n.text||'').trim() && !(n.items||[]).length) { notes = notesGet().filter(x => x.id !== n.id); Android.noteRemind(n.id, '', '0'); }
  else if (n && noteRems[n.id]) Android.noteRemind(n.id, n.title || 'Notitie', String(noteRems[n.id])); // titel van de herinnering bijwerken
  notesSaveNow(); noteCur = null;
}
function notesBackup(){
  notesSaveNow();
  const r = Android.notesExport();
  toast(r.startsWith('ok:') ? '✓ ' + r.slice(3) + ' notities in de map Notities gezet' : r);
}
function refreshNotesTile(){
  const sub = $('#tile-notes-sub'); if (!sub) return;
  const l = notesGet(); const open = l.reduce((a, n) => a + (n.items||[]).filter(i => !i.done).length, 0);
  sub.textContent = l.length ? l.length + (l.length === 1 ? ' notitie' : ' notities') + (open ? ' · ' + open + ' open' : '') : 'Lijstjes maken en afstrepen';
}

