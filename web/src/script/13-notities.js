/* ---------- Notities ---------- */
let notes = null, noteCur = null, notesSaveTmr = null, notesBroken = false, notesVer = 0;
let notesBase = []; // de versie zoals hij het laatst gelezen of bewaard is (om samen te voegen bij een conflict)
let notesDirty = false;
function notesUnsaved(){ return notesDirty || !!notesSaveTmr || (notes !== null && JSON.stringify(notes) !== JSON.stringify(notesBase)); }
function notesSaveState(error){
  document.querySelectorAll('.notes-save-state').forEach(box => {
    box.hidden = !error; box.querySelector('p').textContent = error || '';
  });
}
function notesRecover(){
  const local = notes, base = notesBase, dirty = notesUnsaved();
  notesBroken = false; notes = null;
  const fresh = notesGet();
  if (notesBroken) { notes = local; notesBase = base; notesDirty = dirty; return false; }
  if (dirty && local !== null) { notes = merge3(base, local, fresh, mergeNote); notesDirty = true; }
  return true;
}
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
function notesSaveSoon(){ notesDirty = true; clearTimeout(notesSaveTmr); notesSaveTmr = setTimeout(notesSaveNow, 400); }
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
  const dirty = notesUnsaved();
  clearTimeout(notesSaveTmr); notesSaveTmr = null;
  if (notesBroken && !notesRecover()) { notesSaveState('Notities konden niet worden gelezen. Je bewerkingen blijven staan.'); return false; }
  if (notes === null) return false;
  notesDirty = dirty || notesUnsaved();
  if (typeof Android.notesSaveIf === 'function') {
    for (let attempt = 0; attempt < 3; attempt++) {
      let r; try { r = JSON.parse(Android.notesSaveIf(JSON.stringify(notesGet()), notesVer)); } catch(e) { r = {err: 'Opslaan lukt niet'}; }
      if (r.conflict) {
        // Intussen buiten de app veranderd (bijv. de widget): samenvoegen, niets weggooien
        const local = notes, base = notesBase;
        notes = null;
        const fresh = notesGet();
        if (notesBroken) { notes = local; notesBase = base; notesDirty = true; notesSaveState('Notities konden niet worden gelezen. Je bewerkingen blijven staan.'); return false; }
        notes = merge3(base, local, fresh, mergeNote);
        const typing = document.activeElement && document.activeElement.closest && document.activeElement.closest('#note-open, #note-done');
        if (current === 'note' && noteCur) { if (!noteById(noteCur)) show('notes'); else if (!typing) renderItems(); } // niet onder je vingers verversen
        else if (current === 'notes') renderNotes();
        continue;
      }
      if (r.err) { notesSaveState(t('Opslaan mislukt: {fout}', { fout: t(r.err) })); toast(t('Opslaan mislukt: {fout}', { fout: t(r.err) })); return false; }
      notesVer = r.ver || notesVerNow(); notesBase = notesCopy(notes); notesDirty = false;
      notesSaveState('');
      return true;
    }
    toast('Opslaan lukt even niet (het lijstje verandert steeds). Probeer het zo opnieuw.');
    notesSaveState('Het lijstje verandert steeds. Probeer opnieuw op te slaan.');
    return false;
  }
  const err = Android.notesSave(JSON.stringify(notesGet()));
  if (err) { notesSaveState(t('Opslaan mislukt: {fout}', { fout: t(err) })); toast(t('Opslaan mislukt: {fout}', { fout: t(err) })); return false; }
  notesVer = notesVerNow(); notesBase = notesCopy(notes); notesDirty = false; notesSaveState(''); return true;
}
/* Buiten de app veranderd (bijv. afgestreept in de widget op het startscherm): opnieuw inlezen, zodat de app
   die wijziging niet bij het volgende opslaan overschrijft. */
window.onNotesMaybeChanged = function(){
  if (notesBroken && !notesRecover()) return;
  if (notes === null || notesSaveTmr || notesBroken) return;
  const v = notesVerNow();
  if (!v || v === notesVer) return;
  if (notesUnsaved()) {
    const local = notes, base = notesBase;
    notes = null;
    const fresh = notesGet();
    if (notesBroken) { notes = local; notesBase = base; notesDirty = true; return; }
    notes = merge3(base, local, fresh, mergeNote); notesDirty = true;
    notesSaveSoon();
  } else notes = null;
  if (current === 'note' && noteCur) { if (noteById(noteCur)) renderItems(); else show('notes'); }
  else if (current === 'notes') renderNotes();
  if (typeof refreshNotesTile === 'function') refreshNotesTile();
};
document.addEventListener('visibilitychange', () => { if (!document.hidden) window.onNotesMaybeChanged(); });
function hl(text, q){
  const tt = esc(text); if (!q) return tt;
  const low = text.toLowerCase();
  if (low.length !== text.length) return tt; // bijv. "İ" wordt bij kleine letters 2 tekens: dan klopt de plek niet
  const i = low.indexOf(q); if (i < 0) return tt;
  return esc(text.slice(0, i)) + '<mark>' + esc(text.slice(i, i + q.length)) + '</mark>' + esc(text.slice(i + q.length));
}
function enterNotes(){ loadRems(); renderNotes(); refreshNotesTile(); }
/* kind 'note' of 'list'. Ontbreekt het, dan is het een lijst zodra er items, een bovenliggende lijst of een herhaling is. */
function noteKind(n){
  if (!n) return 'note';
  if (n.kind === 'note' || n.kind === 'list') return n.kind;
  if ((n.items || []).length || n.parentNoteId || (n.repeat && n.repeat.every)) return 'list';
  return 'note';
}
function noteCut(s, max){
  if (!s || s.length <= max) return s || '';
  const sp = s.lastIndexOf(' ', max);
  return (sp >= 40 ? s.slice(0, sp) : s.slice(0, max)).trim();
}
/* Naam in het overzicht: de titel, anders de eerste regel. De opgeslagen tekst blijft heel. */
function noteLabel(n){
  const title = (n && n.title || '').trim();
  if (title) return noteCut(title, 80);
  const line = ((n && n.text || '').trim().split(/\n/).find(x => x.trim()) || '').trim();
  return noteCut(line, 80);
}
/* Tekst onder de naam. Is de naam de eerste regel, dan zonder die regel. */
function notePreview(n){
  const text = (n && n.text || '').trim();
  if (!text) return '';
  if ((n.title || '').trim()) return text.replace(/\s+/g, ' ').trim();
  const nl = text.indexOf('\n');
  return nl < 0 ? '' : text.slice(nl + 1).replace(/\s+/g, ' ').trim();
}
function saveQuickNote(){
  const el = $('#note-quick'); if (!el) return;
  const text = el.value.trim();
  if (!text) { el.focus(); toast('Schrijf eerst je notitie'); return; }
  if (typeof once === 'function' && !once('quicknote', 700)) return;
  notesGet().push({ id: uid(), kind: 'note', title: '', text, items: [], updated: Date.now() });
  el.value = '';
  const ok = notesSaveNow();
  if (current === 'notes') renderNotes();
  if (ok) toast('Notitie bewaard');
  el.focus();
}
function renderNotes(){
  const q = $('#notes-search').value.trim().toLowerCase();
  const compose = $('#note-compose'); if (compose) compose.hidden = q.length > 0;
  const all = notesGet();
  const list = noteListTree(all);
  const byId=new Map(list.map(r=>[r.n.id,r])),childCounts=new Map();
  for(const r of list)if(r.parent!==null)childCounts.set(r.parent,(childCounts.get(r.parent)||0)+1);
  $('#notes-total').textContent=all.filter(n=>noteKind(n)==='note').length;
  const listsEl=$('#notes-list-total'); if(listsEl) listsEl.textContent=list.filter(r=>noteKind(r.n)==='list'&&r.parent===null).length;
  $('#notes-open-total').textContent=all.reduce((sum,n)=>sum+(n.items||[]).filter(i=>!i.done).length,0);
  document.querySelectorAll('.notes-scopes button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.scope===notesScope)));
  const rows = [];
  for (const r of list) {
    const n=r.n;
    if(notesScope==='main'&&r.parent!==null||notesScope==='children'&&r.parent===null)continue;
    if(notesScope==='notes'&&noteKind(n)!=='note')continue;
    if(notesScope==='lists'&&(noteKind(n)!=='list'||r.parent!==null))continue;
    if(!q&&notesScope==='all'){
      let ancestor=r.parent,hidden=false;
      while(ancestor!==null){if(!notesExpanded.has(ancestor)){hidden=true;break;}const a=byId.get(ancestor);ancestor=a?a.parent:null;}
      if(hidden)continue;
    }
    const parent=r.parent!==null?noteById(r.parent):null;
    const items = n.items || [], open = items.filter(i => !i.done).length;
    const shown = noteLabel(n) || t('Zonder titel'), flat = (n.text||'').replace(/\s+/g, ' ');
    let sub = '';
    if (q) {
      const hits = items.filter(i => (i.text || '').toLowerCase().includes(q));
      const titleLow = (n.title || '').toLowerCase(), labelLow = (noteLabel(n) || '').toLowerCase();
      const inTitle = titleLow.includes(q) || (!titleLow.trim() && labelLow.includes(q));
      const inText = flat.toLowerCase().includes(q);
      if (!inTitle && !inText && !hits.length) continue;
      if (hits.length) sub = hits.slice(0, 4).map(i => (i.done ? '✓ ' : '') + hl(i.text, q)).join(' · ');
      else if ((n.title || '').trim() && inText) sub = hl(flat.slice(Math.max(0, flat.toLowerCase().indexOf(q) - 20)), q);
      else if (!(n.title || '').trim() && inText) {
        const rest = notePreview(n);
        if (rest.toLowerCase().includes(q)) sub = hl(rest.slice(Math.max(0, rest.toLowerCase().indexOf(q) - 20)), q);
      }
    } else if (items.length) {
      sub = esc(items.filter(i => !i.done).slice(0, 5).map(i => i.text).join(' · ') || t('Alles afgestreept'));
    } else {
      const prev = notePreview(n);
      sub = prev ? esc(prev.slice(0, 80)) : (noteKind(n) === 'list' ? esc(t('Nog geen items')) : '');
    }
    const rem = noteRems[n.id];
    const count=childCounts.get(n.id)||0,expanded=notesExpanded.has(n.id),depth=q||notesScope==='children'?0:Math.min(r.depth,3);
    const meta = noteKind(n)==='list' && items.length ? (items.length - open) + '/' + items.length + ' ✓' : esc(fmtD(n.updated||0));
    rows.push('<div class="notes-tree-row" style="padding-left:'+depth*14+'px"><button class="nrow" data-note-id="'+esc(n.id)+'" onclick="openNote(' + jsq(n.id) + ')"><span class="note-overview-avatar" aria-hidden="true">'+uiIcon(noteKind(n)==='list'?'notes':'document')+'</span><span class="note-row-content"><span class=r1><b translate="no">' + (rem ? '<span class=nrem>'+uiIcon('bell')+'</span> ' : '') + hl(shown, q) + '</b><small>' +
      meta + '</small></span>' +
      (parent?'<span class=note-parent-label>'+t('↳ Onder {titel}', { titel: esc(noteLabel(parent)||t('Zonder titel')) })+'</span>':'')+
      (sub?'<span class=r2 translate="no">' + sub + '</span>':'')+'</span></button>'+
      (count&&notesScope==='all'&&!q?'<button class="note-tree-toggle '+(expanded?'expanded':'')+'" data-expand-id="'+esc(n.id)+'" aria-label="'+t('Sublijsten van {titel}', { titel: esc(shown) })+'" aria-expanded="'+expanded+'" onclick="notesExpand('+jsq(n.id)+')">'+uiIcon('chevron-right')+'<small>'+count+'</small></button>':'')+'</div>');
  }
  const empty = q ? t('Niets gevonden.')
    : notesScope==='children' ? t('Nog geen sublijsten. Open een lijst om er een toe te voegen.')
    : notesScope==='notes' ? t('Nog geen losse notities. Schrijf er hierboven een.')
    : notesScope==='lists' ? t('Nog geen lijsten. Tik op Nieuwe lijst.')
    : t('Nog geen notities. Schrijf er hierboven een, of tik op Nieuwe lijst.');
  $('#notes-list').innerHTML = rows.join('') || '<p class=note style="padding:14px">' + esc(empty) + '</p>';
}
function noteById(id){ return notesGet().find(n => n.id === id); }
function newNote(){
  const n = { id: uid(), kind: 'list', title: '', text: '', items: [], updated: Date.now() };
  notesGet().push(n); openNote(n.id, true);
}
function openNote(id, fresh){
  noteSplitClose();
  noteCur = id; const n = noteById(id); if (!n) return;
  noteAddReset();
  $('#note-title').value = n.title || ''; $('#note-text').value = n.text || ''; $('#note-add').value = '';
  if (typeof noteApplyKind === 'function') noteApplyKind();
  noteUiReset();show('note'); renderItems(); noteRemShow(); notePinShow(); noteRepeatShow();noteUiSummaries();
  if (fresh) setTimeout(() => (noteKind(n) === 'note' ? $('#note-text') : $('#note-title')).focus(), 50);
  else setTimeout(()=>$('#s-note .hdr h1').focus({preventScroll:true}),0);
}
function touch(n){ n.updated = Date.now(); notesSaveSoon(); }
function noteEdit(field, v){ const n = noteById(noteCur); if (!n) return; n[field] = v; touch(n); }
function renderItems(){
  const n = noteById(noteCur); if (!n) return;
  const rows = noteTree(n.items || []);
  const row = r => { const it=r.it; return '<li data-id="' + esc(it.id) + '" data-parent="' + esc(r.parent||'') + '" data-depth="'+r.depth+'" style="padding-left:'+Math.min(r.depth,3)*12+'px" class="' + (it.done ? 'done' : '') + '">' + (r.groupDone ? '' : '<span class=drag aria-hidden="true">⋮⋮</span>') + '<input type=checkbox ' + (it.done ? 'checked' : '') +
    ' onchange="toggleItem(' + jsq(it.id) + ')" aria-label="'+t('Afstrepen: {item}', { item: esc(it.text) })+'"><input class=it value="' + esc(it.text) +
    '" aria-label="'+(r.depth?t('Subitem, niveau {n}', { n: r.depth }):t('Item'))+'" oninput="typeItem(' + jsq(it.id) + ', this.value)" onchange="editItem(' + jsq(it.id) + ', this.value)"><button class="del item-menu" onclick="noteItemMenu(' + jsq(it.id) + ')" aria-label="'+t('Opties voor {item}', { item: esc(it.text) })+'">⋯</button></li>'; };
  const done = rows.filter(r => r.it.done);
  $('#note-open').innerHTML = rows.filter(r => !r.groupDone).map(row).join('');
  $('#note-done').innerHTML = rows.filter(r => r.groupDone).map(row).join('');
  $('#note-donehdr').style.display = done.length ? 'flex' : 'none';
  $('#note-donecount').textContent = t('{n} afgestreept', { n: done.length });
  noteAddTargetShow();noteListLocation();
  const count=(n.items||[]).length,completed=done.length;
  $('#note-progress').textContent=count?t('{klaar} van {n} items klaar', { klaar: completed, n: count }):t('Nog geen items');
  $('#note-progress-bar').max=count||1;$('#note-progress-bar').value=completed;
  $('#note-empty').hidden=count>0;$('#note-item-move').disabled=count===0;
  if (typeof noteApplyKind === 'function') noteApplyKind();
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
    : t('Alle vinkjes gaan weg {wanneer}. De items zelf blijven staan.', { wanneer: r.every === 'day' ? t('elke nacht') : r.every === 'week' ? t('elke {dag} om middernacht', { dag: t(DAYS_NL[r.n]) }) : t('op de {n}e van elke maand', { n: r.n }) });
}
function noteSetRepeat(v){
  const n = noteById(noteCur); if (!n) return;
  if (!v) delete n.repeat; else { const [every, k] = v.split(':'); n.repeat = {every, n: +k, last: Date.now()}; }
  touch(n); noteRepeatShow(); toast(v ? 'Dit lijstje herhaalt' : 'Herhalen staat uit');
}
function addItem(){
  const inp = $('#note-add'), tt = inp.value.trim(); if (!tt) { inp.focus(); return; }
  const n = noteById(noteCur); if (!n) return;
  if(noteAddParent!==null){
    const parent=noteAddParent;
    if(!noteSplitCommit(n.id,m=>{
      if(!m.items.some(i=>i.id===parent))return 'Het bovenliggende item is verwijderd. Kies Hoofdlijst om verder te gaan.';
      tt.split(/\n+/).map(x=>x.trim()).filter(Boolean).forEach(text=>m.items.push({id:uid(),text,done:false,parentId:parent}));
    }))return;
    inp.value='';renderItems();inp.focus();return;
  }
  // Meerdere regels plakken = meerdere items
  tt.split(/\n+/).map(x => x.trim()).filter(Boolean).forEach(x => n.items.push({ id: uid(), text: x, done: false }));
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
document.addEventListener('visibilitychange', () => { if (document.hidden && notesUnsaved()) notesSaveNow(); });
window.onPauseApp = function(){ appPaused = true; if (notesUnsaved()) notesSaveNow(); };
function delItem(id){
  const n = noteById(noteCur); if (!n) return;
  const at = n.items.findIndex(i => i.id === id); if (at < 0) return;
  const it = n.items[at];
  n.items = n.items.filter(i => i.id !== id); touch(n); renderItems();
  undoToast(t('"{item}" verwijderd', { item: it.text || t('Item') }), () => { const m = noteById(n.id); if (!m || m.items.some(i => i.id === it.id)) return; m.items.splice(Math.min(at, m.items.length), 0, it); touch(m); if (noteCur === m.id) renderItems(); });
}
function clearDone(){
  const n = noteById(noteCur); if (!n) return;
  const k = n.items.filter(i => i.done).length;
  if (!k) return;
  const before = n.items.slice();
  n.items = n.items.filter(i => !i.done); touch(n); renderItems();
  undoToast(tn(k, '{n} afgestreept item verwijderd', '{n} afgestreepte items verwijderd'), () => { const m = noteById(n.id); if (!m) return; m.items = before.concat(m.items.filter(i => !before.some(b => b.id === i.id))); touch(m); if (noteCur === m.id) renderItems(); });
}
function deleteNote(){
  const n = noteById(noteCur); if (!n) return;
  const childCount=notesGet().filter(x=>x.parentNoteId===n.id).length;
  askConfirm('Notitie verwijderen?', t('"{titel}" wordt definitief verwijderd.', { titel: noteLabel(n) || t('Zonder titel') })+(childCount?' '+t('De sublijsten blijven bewaard.'):''), 'Verwijderen', () => {
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
  else if (n && noteRems[n.id]) { const naam = noteLabel(n) || t('Notitie'); if (typeof Android.noteRemindRetitle === 'function') Android.noteRemindRetitle(n.id, naam); else Android.noteRemind(n.id, naam, String(noteRems[n.id])); } // titel van de herinnering bijwerken
  notesSaveNow(); noteCur = null;
}
function notesBackup(){
  if (isPlay() && !JSON.parse(Android.settingsInfo()).dest) { Android.waPickFolder(); toast(t('Kies een map en tik daarna opnieuw op Backup maken')); return; }
  if (!plainOk('De notities', notesBackup)) return;
  if (!notesSaveNow()) return;
  uiBezigNu(t('Notities opslaan…'), () => Android.notesExport()).then(r =>
  toast(r.startsWith('ok:') ? t('✓ {n} notities in de map Notities gezet', { n: r.slice(3) }) : r));
}
function refreshNotesTile(){
  const sub = $('#tile-notes-sub'); if (!sub) return;
  const l = notesGet(); const open = l.reduce((a, n) => a + (n.items||[]).filter(i => !i.done).length, 0);
  sub.textContent = l.length ? tn(l.length, '{n} notitie', '{n} notities') + (open ? ' · ' + t('{n} open', { n: open }) : '') : t('Schrijf een notitie of maak een lijst');
}

