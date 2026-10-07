/* ---------- Notities ---------- */
let notes = null, noteCur = null, notesSaveTmr = null, notesBroken = false, notesVer = 0;
const notesVerNow = () => { try { return +Android.notesVersion() || 0; } catch(e) { return 0; } };
function notesGet(){
  if (notes === null) {
    notesVer = notesVerNow(); // vóór het lezen: verandert er tussendoor iets, dan merkt de volgende keer dat op
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
  if (typeof Android.notesSaveIf === 'function') {
    let r; try { r = JSON.parse(Android.notesSaveIf(JSON.stringify(notesGet()), notesVer)); } catch(e) { r = {err: 'Opslaan lukt niet'}; }
    if (r.conflict) {
      // Intussen veranderd (bijv. afgestreept in de widget): niet overschrijven, opnieuw inlezen
      notes = null;
      if (current === 'note' && noteCur) { if (noteById(noteCur)) { openNote(noteCur); } else show('notes'); }
      else if (current === 'notes') renderNotes();
      toast('Het lijstje is intussen veranderd (bijv. in de widget). Je laatste wijziging staat er nog niet in; doe hem opnieuw.');
    } else if (r.err) toast('Opslaan mislukt: ' + r.err);
    else notesVer = r.ver || notesVerNow();
    return;
  }
  const err = Android.notesSave(JSON.stringify(notesGet()));
  if (err) toast('Opslaan mislukt: ' + err); else notesVer = notesVerNow();
}
/* Buiten de app veranderd (bijv. afgestreept in de widget op het startscherm): opnieuw inlezen, zodat de app
   die wijziging niet bij het volgende opslaan overschrijft. */
window.onNotesMaybeChanged = function(){
  if (notes === null || notesSaveTmr || notesBroken) return;
  const v = notesVerNow();
  if (!v || v === notesVer) return;
  notes = null;
  if (current === 'note' && noteCur) { if (noteById(noteCur)) renderItems(); else show('notes'); }
  else if (current === 'notes') renderNotes();
  if (typeof refreshNotesTile === 'function') refreshNotesTile();
};
document.addEventListener('visibilitychange', () => { if (!document.hidden) window.onNotesMaybeChanged(); });
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
    rows.push('<button class="nrow" onclick="openNote(' + jsq(n.id) + ')"><span class=r1><b>' + (rem ? '<span class=nrem>🔔</span> ' : '') + hl(title, q) + '</b><small>' +
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
  show('note'); renderItems(); noteRemShow(); notePinShow(); noteRepeatShow();
  if (fresh) setTimeout(() => $('#note-title').focus(), 50);
}
function touch(n){ n.updated = Date.now(); notesSaveSoon(); }
function noteEdit(field, v){ const n = noteById(noteCur); if (!n) return; n[field] = v; touch(n); }
function renderItems(){
  const n = noteById(noteCur); if (!n) return;
  const row = it => '<li data-id="' + esc(it.id) + '" class="' + (it.done ? 'done' : '') + '">' + (it.done ? '' : '<span class=drag aria-hidden="true">⋮⋮</span>') + '<input type=checkbox ' + (it.done ? 'checked' : '') +
    ' onchange="toggleItem(' + jsq(it.id) + ')" aria-label="Afstrepen"><input class=it value="' + esc(it.text) +
    '" oninput="typeItem(' + jsq(it.id) + ', this.value)" onchange="editItem(' + jsq(it.id) + ', this.value)"><button class=del onclick="delItem(' + jsq(it.id) + ')" aria-label="Verwijderen">×</button></li>';
  const items = n.items || [];
  const done = items.filter(i => i.done);
  $('#note-open').innerHTML = items.filter(i => !i.done).map(row).join('');
  $('#note-done').innerHTML = done.map(row).join('');
  $('#note-donehdr').style.display = done.length ? 'flex' : 'none';
  $('#note-donecount').textContent = done.length + ' afgestreept';
}
/* ---------- volgorde: slepen aan ⋮⋮, of sorteren ---------- */
let dragLi = null;
document.addEventListener('pointerdown', e => {
  const h = e.target.closest && e.target.closest('#note-open .drag'); if (!h) return;
  e.preventDefault(); dragLi = h.closest('li'); dragLi.classList.add('dragging');
  try { h.setPointerCapture(e.pointerId); } catch(x){}
});
document.addEventListener('pointermove', e => {
  if (!dragLi) return;
  const list = $('#note-open'), over = [...list.children].find(li => { const r = li.getBoundingClientRect(); return e.clientY >= r.top && e.clientY < r.bottom; });
  if (!over || over === dragLi) return;
  const r = over.getBoundingClientRect();
  if (e.clientY < r.top + r.height / 2) list.insertBefore(dragLi, over); else list.insertBefore(dragLi, over.nextSibling);
});
function dragEnd(){
  if (!dragLi) return;
  dragLi.classList.remove('dragging'); dragLi = null;
  const n = noteById(noteCur); if (!n) return;
  const order = [...$('#note-open').children].map(li => li.dataset.id);
  const byId = Object.fromEntries((n.items || []).map(i => [i.id, i]));
  const open = order.map(id => byId[id]).filter(Boolean);
  n.items = open.concat((n.items || []).filter(i => !open.includes(i)));
  touch(n);
}
document.addEventListener('pointerup', dragEnd); document.addEventListener('pointercancel', dragEnd);
function noteSort(){
  const n = noteById(noteCur); if (!n) return;
  const by = f => () => { const open = n.items.filter(i => !i.done).sort(f), done = n.items.filter(i => i.done); n.items = open.concat(done); touch(n); renderItems(); };
  openSheet('Sorteren', 'De open items', [['A tot Z', by((a, b) => a.text.localeCompare(b.text, 'nl', {sensitivity: 'base'}))], ['Z tot A', by((a, b) => b.text.localeCompare(a.text, 'nl', {sensitivity: 'base'}))]]);
}
/* ---------- terugkerend lijstje ---------- */
const DAYS_NL = ['', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
function noteRepeatShow(){
  const n = noteById(noteCur); if (!n) return;
  const r = n.repeat, v = r && r.every ? r.every + ':' + (r.n || 1) : '';
  const sel = $('#note-repeat'); sel.value = v; if (sel.value !== v) sel.value = '';
  $('#note-repeat-info').textContent = !v ? 'Voor een vast lijstje, zoals de weekboodschappen: op het gekozen moment gaan alle vinkjes weer weg.'
    : 'Alle vinkjes gaan weg ' + (r.every === 'day' ? 'elke nacht' : r.every === 'week' ? 'elke ' + DAYS_NL[r.n] + ' om middernacht' : 'op de ' + r.n + 'e van elke maand') + '. De items zelf blijven staan.';
}
function noteSetRepeat(v){
  const n = noteById(noteCur); if (!n) return;
  if (!v) delete n.repeat; else { const [every, k] = v.split(':'); n.repeat = {every, n: +k, last: Date.now()}; }
  touch(n); noteRepeatShow(); toast(v ? '🔁 Dit lijstje herhaalt' : 'Herhalen staat uit');
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
window.onPauseApp = function(){ appPaused = true; if (notesSaveTmr) notesSaveNow(); };
function delItem(id){
  const n = noteById(noteCur); if (!n) return;
  const at = n.items.findIndex(i => i.id === id); if (at < 0) return;
  const it = n.items[at];
  n.items = n.items.filter(i => i.id !== id); touch(n); renderItems();
  undoToast('"' + (it.text || 'Item') + '" verwijderd', () => { const m = noteById(n.id); if (!m || m.items.some(i => i.id === it.id)) return; m.items.splice(Math.min(at, m.items.length), 0, it); touch(m); if (noteCur === m.id) renderItems(); });
}
function clearDone(){
  const n = noteById(noteCur); if (!n) return;
  const k = n.items.filter(i => i.done).length;
  if (!k) return;
  const before = n.items.slice();
  n.items = n.items.filter(i => !i.done); touch(n); renderItems();
  undoToast(k + (k === 1 ? ' afgestreept item' : ' afgestreepte items') + ' verwijderd', () => { const m = noteById(n.id); if (!m) return; m.items = before.concat(m.items.filter(i => !before.some(b => b.id === i.id))); touch(m); if (noteCur === m.id) renderItems(); });
}
function deleteNote(){
  const n = noteById(noteCur); if (!n) return;
  askConfirm('Notitie verwijderen?', '"' + (n.title || 'Zonder titel') + '" wordt definitief verwijderd.', 'Verwijderen', () => {
    if (typeof Android.noteRemindClear === 'function') Android.noteRemindClear(noteCur); else Android.noteRemind(noteCur, '', '0');
    notes = notesGet().filter(x => x.id !== noteCur); noteCur = null; notesSaveNow(); show('notes'); });
}
function leaveNote(){
  const n = noteById(noteCur);
  if (n && !(n.title||'').trim() && !(n.text||'').trim() && !(n.items||[]).length) { notes = notesGet().filter(x => x.id !== n.id); if (typeof Android.noteRemindClear === 'function') Android.noteRemindClear(n.id); else Android.noteRemind(n.id, '', '0'); }
  else if (n && noteRems[n.id]) { if (typeof Android.noteRemindRetitle === 'function') Android.noteRemindRetitle(n.id, n.title || 'Notitie'); else Android.noteRemind(n.id, n.title || 'Notitie', String(noteRems[n.id])); } // titel van de herinnering bijwerken
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

