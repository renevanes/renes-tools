/* Lijsten blijven zelfstandige notities; alleen hun plaats in het overzicht verandert. */
let noteMoveState=null;
function noteListTree(all){
  return noteTree(all.slice().sort((a,b)=>(b.updated||0)-(a.updated||0)).map(n=>({id:n.id,parentId:n.parentNoteId,note:n})))
    .map(r=>({n:r.it.note,parent:r.parent,depth:r.depth}));
}
function noteListCanMove(all,id,parentId){
  const byId=new Map(all.map(n=>[n.id,n]));
  if(!byId.has(id)||parentId!==null&&!byId.has(parentId))return false;
  const seen=new Set([id]);let p=byId.get(parentId);
  while(p){if(seen.has(p.id))return false;seen.add(p.id);p=byId.get(p.parentNoteId);}
  return true;
}
function noteListLocation(){
  const n=noteById(noteCur);if(!n)return;
  const row=noteListTree(notesGet()).find(r=>r.n.id===n.id),parent=row&&row.parent!==null?noteById(row.parent):null;
  const location=$('#note-list-location');location.replaceChildren();
  if(parent){
    const b=document.createElement('button');b.textContent='↰ '+(parent.title||'Zonder titel');b.onclick=()=>{if(notesSaveNow())openNote(parent.id);};
    b.setAttribute('aria-label','Bovenliggende lijst: '+(parent.title||'Zonder titel'));location.appendChild(b);
  }else location.textContent='Losse lijst';
  $('#note-list-swap').hidden=!parent;
  const children=noteListTree(notesGet()).filter(r=>r.parent===n.id);
  $('#note-child-list').replaceChildren();
  for(const r of children){
    const b=document.createElement('button');b.className='note-child';b.dataset.noteId=r.n.id;
    const title=document.createElement('strong');title.textContent='▤ '+(r.n.title||'Zonder titel');
    const sub=document.createElement('small'),items=r.n.items||[];
    sub.textContent=items.length?items.filter(i=>!i.done).length+' van '+items.length+' items open':'Open deze lijst';
    b.append(title,sub);b.onclick=()=>{if(notesSaveNow())openNote(r.n.id);};$('#note-child-list').appendChild(b);
  }
  $('#note-child-empty').hidden=children.length>0;
}
function noteNewChild(){
  const parent=noteCur;if(!noteById(parent)||!notesSaveNow())return;
  const n={id:uid(),title:'',text:'',items:[],updated:Date.now(),parentNoteId:parent};
  notesGet().push(n);openNote(n.id,true);
}
function noteMoveClose(){
  const s=noteMoveState;noteMoveState=null;
  if(s&&s.focus&&s.focus.isConnected)s.focus.focus({preventScroll:true});
}
function noteMoveOpen(kind,itemId){
  if(!noteById(noteCur)||!notesSaveNow())return;
  closeSheet();
  const n=noteById(noteCur);if(!n)return;
  let focus=document.activeElement;
  if(focus&&focus.closest('#sheet'))focus=kind==='item'?$('#note-item-move'):$('#note-list-move');
  const state={kind,noteId:n.id,itemId:itemId||((n.items||[])[0]||{}).id,selected:undefined,
    parents:new Map(notesGet().map(n=>[n.id,n.parentNoteId||null])),focus};
  noteMoveState=state;
  openSheet(kind==='item'?'Item verplaatsen':kind==='attach'?'Bestaande lijst hieronder hangen':'Lijst verplaatsen',
    kind==='item'?'Kies een item en waar het moet komen. Zijn subitems gaan mee.':kind==='attach'?'Kies de lijst die onder deze lijst komt. De inhoud blijft bewaard.':'Kies de bovenliggende lijst. Alle sublijsten verhuizen mee.',[],noteMoveClose);
  const box=$('#sheet-acts');
  if(kind==='item'){
    const label=document.createElement('label');label.className='f';label.htmlFor='note-move-item';label.textContent='Welk item?';
    const select=document.createElement('select');select.id='note-move-item';select.className='sel';
    for(const r of noteTree(n.items||[])){const o=document.createElement('option');o.value=r.it.id;o.textContent='› '.repeat(Math.min(r.depth,5))+r.it.text;select.appendChild(o);}
    select.value=state.itemId||'';select.onchange=()=>{state.itemId=select.value;state.selected=undefined;noteMoveChoices();};box.append(label,select);
  }
  const label=document.createElement('label');label.className='f';label.htmlFor='note-move-search';label.textContent=kind==='item'?'Waar plaatsen?':kind==='attach'?'Welke lijst?':'Onder welke lijst?';
  const search=document.createElement('input');search.id='note-move-search';search.type='search';search.className='search';search.placeholder=kind==='item'?'Zoek een item…':'Zoek een lijst…';search.oninput=noteMoveChoices;
  const choices=document.createElement('div');choices.id='note-move-choices';choices.className='note-move-choices';
  const status=document.createElement('p');status.id='note-move-status';status.className='note';status.setAttribute('role','status');status.setAttribute('aria-live','polite');
  const apply=document.createElement('button');apply.id='note-move-apply';apply.className='big';apply.textContent=kind==='attach'?'Lijst hieronder hangen':'Verplaatsen';apply.onclick=noteMoveApply;
  box.append(label,search,choices,status,apply);noteMoveChoices();
  (kind==='item'?$('#note-move-item'):search).focus();
}
function noteMoveChoices(){
  const s=noteMoveState;if(!s)return;
  const n=noteById(s.noteId);if(!n){closeSheet();return;}
  const q=$('#note-move-search').value.trim().toLowerCase(),box=$('#note-move-choices');
  const focusedChoice=box.contains(document.activeElement)?document.activeElement.dataset.target:undefined;
  box.replaceChildren();
  let choices=[];
  if(s.kind==='item'){
    const rows=noteTree(n.items||[]),row=rows.find(r=>r.it.id===s.itemId);
    if(row){const ids=new Set(noteSubtree(rows,row).map(r=>r.it.id));
      choices=[{id:null,title:'In de hoofdlijst',sub:'Geen bovenliggend item',current:row.parent===null}].concat(rows.filter(r=>!ids.has(r.it.id)).map(r=>({id:r.it.id,title:r.it.text,depth:r.depth,sub:'Onder dit item',current:r.it.id===row.parent})));
    }
  }else{
    const all=notesGet();
    choices=noteListTree(all).filter(r=>s.kind==='attach'?noteListCanMove(all,r.n.id,s.noteId):noteListCanMove(all,s.noteId,r.n.id))
      .map(r=>({id:r.n.id,title:r.n.title||'Zonder titel',depth:r.depth,sub:s.kind==='attach'?'Onder '+(n.title||'Zonder titel'):'Bovenliggende lijst',current:s.kind==='attach'?r.n.parentNoteId===s.noteId:r.n.id===n.parentNoteId}));
    if(s.kind==='move')choices.unshift({id:null,title:'Als losse lijst',sub:'Direct in het overzicht',current:!n.parentNoteId});
  }
  for(const c of choices.filter(c=>c.id===null||c.title.toLowerCase().includes(q))){
    const b=document.createElement('button');b.className='note-move-choice';b.dataset.target=c.id===null?'':c.id;
    b.setAttribute('aria-pressed',String(s.selected===c.id));b.disabled=c.current;
    const title=document.createElement('strong');title.textContent=(s.selected===c.id?'✓ ':'')+c.title;
    const sub=document.createElement('small');sub.textContent=c.current?'Huidige plek':('› '.repeat(Math.min(c.depth||0,4))+c.sub);
    b.append(title,sub);b.onclick=()=>{
      s.selected=c.id;
      if(s.kind==='attach'){const source=noteById(c.id);if(source)s.parents.set(c.id,source.parentNoteId||null);}
      else if(s.kind==='move'){const source=noteById(s.noteId);if(source)s.parents.set(source.id,source.parentNoteId||null);}
      noteMoveChoices();
    };box.appendChild(b);
  }
  if(!box.children.length){const p=document.createElement('p');p.className='note';p.textContent=q?'Geen passende bestemming gevonden.':'Er is nog geen andere lijst beschikbaar.';box.appendChild(p);}
  const selected=choices.find(c=>c.id===s.selected&&!c.current),apply=$('#note-move-apply');apply.disabled=!selected;
  const item=(n.items||[]).find(i=>i.id===s.itemId);
  $('#note-move-status').textContent=selected?(s.kind==='attach'?selected.title+' komt onder '+(n.title||'Zonder titel')+'.':s.kind==='item'?(item?item.text:'Item')+(selected.id===null?' komt in de hoofdlijst.':' komt onder '+selected.title+'.'):(n.title||'Zonder titel')+(selected.id===null?' wordt een losse lijst.':' komt onder '+selected.title+'.')):'Kies een bestemming. Je kunt deze wijziging daarna ongedaan maken.';
  if(focusedChoice!==undefined){const b=[...box.querySelectorAll('button')].find(b=>b.dataset.target===focusedChoice);if(b)b.focus({preventScroll:true});}
}
document.addEventListener('keydown',e=>{
  if(!noteMoveState||!$('#sheet').classList.contains('on'))return;
  if(e.key==='Escape'){e.preventDefault();closeSheet();return;}
  if(e.key!=='Tab')return;
  const controls=[...$('#sheet').querySelectorAll('button:not(:disabled),input,select')].filter(el=>el.getClientRects().length);
  if(!controls.length)return;
  const first=controls[0],last=controls[controls.length-1],active=document.activeElement;
  if(e.shiftKey&&active===first){e.preventDefault();last.focus();}
  else if(!e.shiftKey&&active===last||!$('#sheet').contains(active)){e.preventDefault();first.focus();}
});
function noteMoveApply(){
  const s=noteMoveState;if(!s||s.selected===undefined||$('#note-move-apply').disabled)return;
  if(s.kind==='item'){
    if(noteReparent(s.noteId,s.itemId,s.selected)){closeSheet();noteMoveClose();}return;
  }
  const source=s.kind==='attach'?s.selected:s.noteId,parent=s.kind==='attach'?s.noteId:s.selected;
  const before=s.parents.get(source);
  if(!noteSplitCommit(source,(n,all)=>{
    if((n.parentNoteId||null)!==before)return 'Deze lijst is intussen verplaatst. Kies opnieuw.';
    if(!noteListCanMove(all,source,parent))return 'Deze plaatsing kan niet meer. Kies een andere lijst.';
    if(parent===null)delete n.parentNoteId;else n.parentNoteId=parent;
  },reason=>{
    if(!noteMoveState)return;
    noteMoveChoices();$('#note-move-status').textContent=reason;$('#note-move-apply').disabled=true;
  }))return;
  closeSheet();noteMoveClose();noteListLocation();
  const undo=()=>{
    if(!noteSplitCommit(source,(n,all)=>{
      if((n.parentNoteId||null)!==parent||!noteListCanMove(all,source,before))return 'De indeling is intussen veranderd. Je wijzigingen blijven behouden.';
      if(before===null)delete n.parentNoteId;else n.parentNoteId=before;
    })){undoToast('Verplaatsing niet ongedaan gemaakt',undo);return;}
    if(current==='note')noteListLocation();else if(current==='notes')renderNotes();
  };
  undoToast('Lijst verplaatst',undo);
}
function noteListSwap(){
  const child=noteById(noteCur),parent=child&&noteById(child.parentNoteId);if(!parent)return;
  const id=child.id,pid=parent.id,grand=parent.parentNoteId||null;
  askConfirm('Lijsten omwisselen?', '“'+(child.title||'Zonder titel')+'” komt op de plek van “'+(parent.title||'Zonder titel')+'”. Die lijst komt eronder te hangen. De inhoud van beide lijsten blijft bewaard.', 'Omwisselen',()=>{
    if(!noteSplitCommit(id,(n,all)=>{
      const p=all.find(x=>x.id===pid);
      if(!p||n.parentNoteId!==pid||(p.parentNoteId||null)!==grand)return 'De indeling is intussen veranderd. Kies opnieuw.';
      if(grand===null)delete n.parentNoteId;else n.parentNoteId=grand;p.parentNoteId=id;
      if(!noteListCanMove(all,id,grand)||!noteListCanMove(all,pid,id))return 'Deze lijsten kunnen niet worden omgewisseld.';
      p.updated=Date.now();
    }))return;
    if(current==='note')noteListLocation();
    const undo=()=>{
      if(!noteSplitCommit(id,(n,all)=>{
        const p=all.find(x=>x.id===pid);
        if(!p||(n.parentNoteId||null)!==grand||p.parentNoteId!==id)return 'De indeling is intussen veranderd. Je wijzigingen blijven behouden.';
        n.parentNoteId=pid;if(grand===null)delete p.parentNoteId;else p.parentNoteId=grand;
        if(!noteListCanMove(all,id,pid)||!noteListCanMove(all,pid,grand))return 'De indeling is intussen veranderd. Je wijzigingen blijven behouden.';
        p.updated=Date.now();
      })){undoToast('Omwisselen niet ongedaan gemaakt',undo);return;}
      if(current==='note')noteListLocation();else if(current==='notes')renderNotes();
    };
    undoToast('Lijsten omgewisseld',undo);
  });
}
