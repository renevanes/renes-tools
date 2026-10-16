/* ---------- Notities ---------- */
let notes = null, noteCur = null, notesSaveTmr = null, notesBroken = false, notesVer = 0;
let notesBase = []; // de versie zoals hij het laatst gelezen of bewaard is (om samen te voegen bij een conflict)
const notesCopy = a => JSON.parse(JSON.stringify(a || []));
const notesVerNow = () => { try { return +Android.notesVersion() || 0; } catch(e) { return 0; } };
function notesGet(){
  if (notes === null) {
    notesVer = notesVerNow(); // vóór het lezen: verandert er tussendoor iets, dan merkt de volgende keer dat op
    let raw = ''; try { raw = Android.notesLoad(); } catch(e){}
    try { notes = JSON.parse(raw); if (!Array.isArray(notes)) throw 0; notesBase = notesCopy(notes); }
    catch(e){ notes = []; notesBase = []; notesBroken = true; toast('Notities konden niet worden gelezen; er wordt niets overschreven'); }
  }
  return notes;
}
function uid(){ return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
function notesSaveSoon(){ clearTimeout(notesSaveTmr); notesSaveTmr = setTimeout(notesSaveNow, 400); }
/* Drie-weg samenvoegen: base = wat we gelezen hadden, local = hoe het nu in de app is, fresh = wat er nu bewaard
   staat (bijv. afgestreept in de widget, of leeggemaakt door "elke maandag weer leeg"). Per veld wint wie het
   veranderde; nieuwe notities en items van beide kanten blijven, en wat de app verwijderde blijft weg. */
function merge3(base, local, fresh, mergeOne){
  const B = new Map(base.map(x => [x.id, x])), L = new Map(local.map(x => [x.id, x])), F = new Map(fresh.map(x => [x.id, x]));
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const localOrderChanged = !same(local.map(x => x.id).filter(id => B.has(id)), base.map(x => x.id).filter(id => L.has(id)));
  const out = [];
  const take = (id) => {
    const b = B.get(id), l = L.get(id), f = F.get(id);
    if (l && f) out.push(b ? mergeOne(b, l, f) : l);
    else if (l && !f && !b) out.push(l);          // nieuw in de app
    else if (f && !l && !b) out.push(f);          // nieuw van buiten
    // in base maar aan één kant verwijderd: weg
  };
  const order = localOrderChanged ? local.map(x => x.id).concat(fresh.map(x => x.id)) : fresh.map(x => x.id).concat(local.map(x => x.id));
  const seen = new Set();
  for (const id of order) if (!seen.has(id)) { seen.add(id); take(id); }
  return out;
}
function mergeFields(b, l, f){
  const n = Object.assign({}, f);
  const same = (x, y) => JSON.stringify(x) === JSON.stringify(y);
  for (const k of new Set(Object.keys(b).concat(Object.keys(l), Object.keys(f)))) {
    if (k === 'items') continue;
    if (!same(l[k], b[k])) { if (l[k] === undefined) delete n[k]; else n[k] = l[k]; }
  }
  return n;
}
function mergeNote(b, l, f){
  const n = mergeFields(b, l, f);
  const all = (b.items || []).concat(l.items || [], f.items || []);
  if (all.some(i => !i || !i.id)) {
    // Oude items zonder id: niet per item samen te voegen; de app-versie als die veranderde, anders de nieuwe
    n.items = JSON.stringify(l.items || []) !== JSON.stringify(b.items || []) ? (l.items || []) : (f.items || []);
  } else n.items = merge3(b.items || [], l.items || [], f.items || [], mergeFields);
  n.updated = Math.max(l.updated || 0, f.updated || 0);
  return n;
}
/* Geeft true als het bewaard is. Bij een conflict wordt eerst samengevoegd en opnieuw geprobeerd. */
function notesSaveNow(){
  clearTimeout(notesSaveTmr); notesSaveTmr = null;
  if (notesBroken || notes === null) return false;
  if (typeof Android.notesSaveIf === 'function') {
    for (let attempt = 0; attempt < 3; attempt++) {
      let r; try { r = JSON.parse(Android.notesSaveIf(JSON.stringify(notesGet()), notesVer)); } catch(e) { r = {err: 'Opslaan lukt niet'}; }
      if (r.conflict) {
        // Intussen buiten de app veranderd (bijv. de widget): samenvoegen, niets weggooien
        const local = notes, base = notesBase;
        notes = null;
        const fresh = notesGet();
        if (notesBroken) { notes = local; return false; } // nieuwe versie onleesbaar: eigen wijzigingen niet uit beeld halen
        notes = merge3(base, local, fresh, mergeNote);
        const typing = document.activeElement && document.activeElement.closest && document.activeElement.closest('#note-open, #note-done');
        if (current === 'note' && noteCur) { if (!noteById(noteCur)) show('notes'); else if (!typing) renderItems(); } // niet onder je vingers verversen
        else if (current === 'notes') renderNotes();
        continue;
      }
      if (r.err) { toast('Opslaan mislukt: ' + r.err); return false; }
      notesVer = r.ver || notesVerNow(); notesBase = notesCopy(notes);
      return true;
    }
    toast('Opslaan lukt even niet (het lijstje verandert steeds). Probeer het zo opnieuw.');
    return false;
  }
  const err = Android.notesSave(JSON.stringify(notesGet()));
  if (err) { toast('Opslaan mislukt: ' + err); return false; }
  notesVer = notesVerNow(); notesBase = notesCopy(notes); return true;
}
/* Buiten de app veranderd (bijv. afgestreept in de widget op het startscherm): opnieuw inlezen, zodat de app
   die wijziging niet bij het volgende opslaan overschrijft. */
window.onNotesMaybeChanged = function(){
  if (notesBroken && !notesSaveTmr) { notesBroken = false; notes = null; notesGet(); if (current === 'notes') renderNotes(); return; } // opnieuw proberen te lezen
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
  const low = text.toLowerCase();
  if (low.length !== text.length) return t; // bijv. "İ" wordt bij kleine letters 2 tekens: dan klopt de plek niet
  const i = low.indexOf(q); if (i < 0) return t;
  return esc(text.slice(0, i)) + '<mark>' + esc(text.slice(i, i + q.length)) + '</mark>' + esc(text.slice(i + q.length));
}
function enterNotes(){ loadRems(); renderNotes(); refreshNotesTile(); }
function renderNotes(){
  const q = $('#notes-search').value.trim().toLowerCase();
  const list = noteListTree(notesGet());
  const byId=new Map(list.map(r=>[r.n.id,r])),childCounts=new Map();
  for(const r of list)if(r.parent!==null)childCounts.set(r.parent,(childCounts.get(r.parent)||0)+1);
  $('#notes-total').textContent=list.length;
  $('#notes-open-total').textContent=notesGet().reduce((sum,n)=>sum+(n.items||[]).filter(i=>!i.done).length,0);
  $('#notes-child-total').textContent=list.filter(r=>r.parent!==null).length;
  document.querySelectorAll('.notes-scopes button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.scope===notesScope)));
  const rows = [];
  for (const r of list) {
    if(notesScope==='main'&&r.parent!==null||notesScope==='children'&&r.parent===null)continue;
    if(!q&&notesScope==='all'){
      let ancestor=r.parent,hidden=false;
      while(ancestor!==null){if(!notesExpanded.has(ancestor)){hidden=true;break;}const a=byId.get(ancestor);ancestor=a?a.parent:null;}
      if(hidden)continue;
    }
    const n=r.n, parent=r.parent!==null?noteById(r.parent):null;
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
    const count=childCounts.get(n.id)||0,expanded=notesExpanded.has(n.id),depth=q||notesScope==='children'?0:Math.min(r.depth,3);
    rows.push('<div class="notes-tree-row" style="padding-left:'+depth*14+'px"><button class="nrow" data-note-id="'+esc(n.id)+'" onclick="openNote(' + jsq(n.id) + ')"><span class="note-overview-avatar" aria-hidden="true">'+uiIcon(items.length?'notes':'document')+'</span><span class="note-row-content"><span class=r1><b>' + (rem ? '<span class=nrem>'+uiIcon('bell')+'</span> ' : '') + hl(title, q) + '</b><small>' +
      (items.length ? (items.length - open) + '/' + items.length + ' ✓' : esc(fmtD(n.updated||0))) + '</small></span>' +
      (parent?'<span class=note-parent-label>↳ Onder '+esc(parent.title||'Zonder titel')+'</span>':'')+'<span class=r2>' + (sub || 'Open deze notitie') + '</span></span></button>'+
      (count&&notesScope==='all'&&!q?'<button class="note-tree-toggle '+(expanded?'expanded':'')+'" data-expand-id="'+esc(n.id)+'" aria-label="Sublijsten van '+esc(title)+'" aria-expanded="'+expanded+'" onclick="notesExpand('+jsq(n.id)+')">'+uiIcon('chevron-right')+'<small>'+count+'</small></button>':'')+'</div>');
  }
  $('#notes-list').innerHTML = rows.join('') || '<p class=note style="padding:14px">' +
    (q ? 'Niets gevonden.' : notesScope==='children'?'Nog geen sublijsten. Open een lijst om er een toe te voegen.':'Nog geen notities. Tik op + om een lijstje te maken.') + '</p>';
}
function noteById(id){ return notesGet().find(n => n.id === id); }
function newNote(){
  const n = { id: uid(), title: '', text: '', items: [], updated: Date.now() };
  notesGet().push(n); openNote(n.id, true);
}
function openNote(id, fresh){
  noteSplitClose();
  noteCur = id; const n = noteById(id); if (!n) return;
  noteAddReset();
  $('#note-title').value = n.title || ''; $('#note-text').value = n.text || ''; $('#note-add').value = '';
  noteUiReset();show('note'); renderItems(); noteRemShow(); notePinShow(); noteRepeatShow();noteUiSummaries();
  if (fresh) setTimeout(() => $('#note-title').focus(), 50);else setTimeout(()=>$('#s-note .hdr h1').focus({preventScroll:true}),0);
}
function touch(n){ n.updated = Date.now(); notesSaveSoon(); }
function noteEdit(field, v){ const n = noteById(noteCur); if (!n) return; n[field] = v; touch(n); }
function renderItems(){
  const n = noteById(noteCur); if (!n) return;
  const rows = noteTree(n.items || []);
  const row = r => { const it=r.it; return '<li data-id="' + esc(it.id) + '" data-parent="' + esc(r.parent||'') + '" data-depth="'+r.depth+'" style="padding-left:'+Math.min(r.depth,3)*12+'px" class="' + (it.done ? 'done' : '') + '">' + (r.groupDone ? '' : '<span class=drag aria-hidden="true">⋮⋮</span>') + '<input type=checkbox ' + (it.done ? 'checked' : '') +
    ' onchange="toggleItem(' + jsq(it.id) + ')" aria-label="Afstrepen: '+esc(it.text)+'"><input class=it value="' + esc(it.text) +
    '" aria-label="'+(r.depth?'Subitem, niveau '+r.depth:'Item')+'" oninput="typeItem(' + jsq(it.id) + ', this.value)" onchange="editItem(' + jsq(it.id) + ', this.value)"><button class="del item-menu" onclick="noteItemMenu(' + jsq(it.id) + ')" aria-label="Opties voor '+esc(it.text)+'">⋯</button></li>'; };
  const done = rows.filter(r => r.it.done);
  $('#note-open').innerHTML = rows.filter(r => !r.groupDone).map(row).join('');
  $('#note-done').innerHTML = rows.filter(r => r.groupDone).map(row).join('');
  $('#note-donehdr').style.display = done.length ? 'flex' : 'none';
  $('#note-donecount').textContent = done.length + ' afgestreept';
  noteAddTargetShow();noteListLocation();
  const count=(n.items||[]).length,completed=done.length;
  $('#note-progress').textContent=count?completed+' van '+count+' items klaar':'Nog geen items';
  $('#note-progress-bar').max=count||1;$('#note-progress-bar').value=completed;
  $('#note-empty').hidden=count>0;$('#note-item-move').disabled=count===0;
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
  if (!over || over === dragLi || over.dataset.parent !== dragLi.dataset.parent) return;
  const group = li => { const out=[li]; let next=li.nextElementSibling; while(next && +next.dataset.depth>+li.dataset.depth){out.push(next);next=next.nextElementSibling;}return out; };
  const moving=group(dragLi),target=group(over); if(moving.includes(over))return;
  const r = over.getBoundingClientRect(), before=e.clientY<r.top+r.height/2?over:target[target.length-1].nextSibling;
  if(moving.includes(before))return;
  const fragment=document.createDocumentFragment();moving.forEach(li=>fragment.appendChild(li));list.insertBefore(fragment,before);
});
function dragEnd(){
  if (!dragLi) return;
  dragLi.classList.remove('dragging'); dragLi = null;
  const n = noteById(noteCur); if (!n) return;
  const order = [...$('#note-open').children].map(li => li.dataset.id);
  const byId = Object.fromEntries((n.items || []).map(i => [i.id, i]));
  const open = order.map(id => byId[id]).filter(Boolean);
  n.items = open.concat((n.items || []).filter(i => !open.includes(i)));
  touch(n); renderItems();
}
document.addEventListener('pointerup', dragEnd); document.addEventListener('pointercancel', dragEnd);
function noteSort(){
  const n = noteById(noteCur); if (!n) return;
  const by = f => () => { n.items = noteTree(n.items || [], f).map(r => r.it); touch(n); renderItems(); };
  openSheet('Sorteren', 'De open items, per sublijst', [['A tot Z', by((a, b) => a.text.localeCompare(b.text, 'nl', {sensitivity: 'base'}))], ['Z tot A', by((a, b) => b.text.localeCompare(a.text, 'nl', {sensitivity: 'base'}))]]);
}
/* ---------- terugkerend lijstje ---------- */
const DAYS_NL = ['', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
function noteRepeatShow(){
  const n = noteById(noteCur); if (!n) return;
  const r = n.repeat, v = r && r.every ? r.every + ':' + (r.n || 1) : '';
  const sel = $('#note-repeat'); sel.value = v; if (sel.value !== v) sel.value = '';
  noteUiSummaries();
  $('#note-repeat-info').textContent = !v ? 'Voor een vast lijstje, zoals de weekboodschappen: op het gekozen moment gaan alle vinkjes weer weg.'
    : 'Alle vinkjes gaan weg ' + (r.every === 'day' ? 'elke nacht' : r.every === 'week' ? 'elke ' + DAYS_NL[r.n] + ' om middernacht' : 'op de ' + r.n + 'e van elke maand') + '. De items zelf blijven staan.';
}
function noteSetRepeat(v){
  const n = noteById(noteCur); if (!n) return;
  if (!v) delete n.repeat; else { const [every, k] = v.split(':'); n.repeat = {every, n: +k, last: Date.now()}; }
  touch(n); noteRepeatShow(); toast(v ? 'Dit lijstje herhaalt' : 'Herhalen staat uit');
}
function addItem(){
  const inp = $('#note-add'), t = inp.value.trim(); if (!t) { inp.focus(); return; }
  const n = noteById(noteCur); if (!n) return;
  if(noteAddParent!==null){
    const parent=noteAddParent;
    if(!noteSplitCommit(n.id,m=>{
      if(!m.items.some(i=>i.id===parent))return 'Het bovenliggende item is verwijderd. Kies Hoofdlijst om verder te gaan.';
      t.split(/\n+/).map(x=>x.trim()).filter(Boolean).forEach(text=>m.items.push({id:uid(),text,done:false,parentId:parent}));
    }))return;
    inp.value='';renderItems();inp.focus();return;
  }
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
  const childCount=notesGet().filter(x=>x.parentNoteId===n.id).length;
  askConfirm('Notitie verwijderen?', '"' + (n.title || 'Zonder titel') + '" wordt definitief verwijderd.'+(childCount?' De sublijsten blijven bewaard.':''), 'Verwijderen', () => {
    const id=n.id;
    if(!noteSplitCommit(id,(m,all)=>{
      for(const child of all.filter(x=>x.parentNoteId===id)){
        if(m.parentNoteId&&noteListCanMove(all,child.id,m.parentNoteId))child.parentNoteId=m.parentNoteId;else delete child.parentNoteId;
      }
      all.splice(all.findIndex(x=>x.id===id),1);
    }))return;
    noteCur=null;
    if (typeof Android.noteRemindClear === 'function') Android.noteRemindClear(id); else Android.noteRemind(id, '', '0');
    show('notes'); });
}
function leaveNote(){
  noteSplitClose();
  noteAddParent=null;
  const n = noteById(noteCur);
  if (n && !(n.title||'').trim() && !(n.text||'').trim() && !(n.items||[]).length && !notesGet().some(x=>x.parentNoteId===n.id)) { notes = notesGet().filter(x => x.id !== n.id); if (typeof Android.noteRemindClear === 'function') Android.noteRemindClear(n.id); else Android.noteRemind(n.id, '', '0'); }
  else if (n && noteRems[n.id]) { if (typeof Android.noteRemindRetitle === 'function') Android.noteRemindRetitle(n.id, n.title || 'Notitie'); else Android.noteRemind(n.id, n.title || 'Notitie', String(noteRems[n.id])); } // titel van de herinnering bijwerken
  notesSaveNow(); noteCur = null;
}
function notesBackup(){
  if (!plainOk('De notities', notesBackup)) return;
  notesSaveNow();
  const r = Android.notesExport();
  toast(r.startsWith('ok:') ? '✓ ' + r.slice(3) + ' notities in de map Notities gezet' : r);
}
function refreshNotesTile(){
  const sub = $('#tile-notes-sub'); if (!sub) return;
  const l = notesGet(); const open = l.reduce((a, n) => a + (n.items||[]).filter(i => !i.done).length, 0);
  sub.textContent = l.length ? l.length + (l.length === 1 ? ' notitie' : ' notities') + (open ? ' · ' + open + ' open' : '') : 'Lijstjes maken en afstrepen';
}

