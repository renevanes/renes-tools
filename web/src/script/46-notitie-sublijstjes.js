/* Items blijven vlak opgeslagen met een parentId: widget, backup en samenvoegen behouden hun eigen id. */
let noteAddParent = null;
function noteTree(items, compare){
  const byId = new Map(items.map(i => [i.id,i])), parents = new Map(), children = new Map();
  for(const it of items){
    let parent = byId.has(it.parentId) ? it.parentId : null;
    const seen = new Set([it.id]); let p = parent;
    while(p != null){
      if(seen.has(p)){parent=null;break;}
      seen.add(p); const ancestor=byId.get(p); p=ancestor&&byId.has(ancestor.parentId)?ancestor.parentId:null;
    }
    parents.set(it.id,parent);
    if(!children.has(parent))children.set(parent,[]);
    children.get(parent).push(it);
  }
  if(compare)for(const siblings of children.values())siblings.sort((a,b)=>Number(!!a.done)-Number(!!b.done)||compare(a,b));
  const rows=[],stack=(children.get(null)||[]).slice().reverse().map(it=>({it,depth:0,root:it.id}));
  while(stack.length){
    const row=stack.pop(); row.parent=parents.get(row.it.id); rows.push(row);
    const kids=children.get(row.it.id)||[];
    for(let i=kids.length-1;i>=0;i--)stack.push({it:kids[i],depth:row.depth+1,root:row.root});
  }
  const openRoots=new Set(rows.filter(r=>!r.it.done).map(r=>r.root));
  rows.forEach(r=>r.groupDone=!openRoots.has(r.root));
  return rows.filter(r=>!r.groupDone).concat(rows.filter(r=>r.groupDone));
}
function noteAddReset(){noteAddParent=null;noteAddTargetShow();}
function noteAddTargetShow(){
  const n=noteById(noteCur),it=n&&(n.items||[]).find(i=>i.id===noteAddParent);
  $('#note-add-target').hidden=noteAddParent===null;
  $('#note-add-target-text').textContent=it?'Subitem van: '+it.text:'Het bovenliggende item is verwijderd';
  $('#note-add').placeholder=noteAddParent===null?'Nieuw item…':'Nieuw subitem…';
}
function noteSubAdd(id){
  const n=noteById(noteCur);if(!n||!n.items.some(i=>i.id===id))return;
  noteAddParent=id;noteAddTargetShow();$('#note-add').focus();
}
function noteItemMenu(id){
  const n=noteById(noteCur);if(!n)return;
  const rows=noteTree(n.items||[]),row=rows.find(r=>r.it.id===id);if(!row)return;
  const siblings=rows.filter(r=>r.parent===row.parent),at=siblings.indexOf(row);
  const acts=[['Subitem toevoegen',()=>noteSubAdd(id)]];
  if(at>0)acts.push(['Inspringen onder '+siblings[at-1].it.text,()=>noteReparent(n.id,id,siblings[at-1].it.id)]);
  if(row.parent!==null){const parent=rows.find(r=>r.it.id===row.parent);acts.push(['Een niveau terug',()=>noteReparent(n.id,id,parent.parent)]);}
  acts.push(['Item verwijderen',()=>{if(noteCur===n.id)delItem(id);}]);
  openSheet(row.it.text,'Elk subitem heeft een eigen vinkje. Bij verwijderen blijven de subitems staan.',acts);
}
function noteSubtree(rows,row){
  const start=rows.indexOf(row);let end=start+1;while(end<rows.length&&rows[end].depth>row.depth)end++;
  return rows.slice(start,end);
}
function noteParentValid(rows,id,parentId){
  let p=rows.find(r=>r.it.id===parentId);
  if(parentId!==null&&!p)return false;
  while(p){if(p.it.id===id)return false;p=rows.find(r=>r.it.id===p.parent);}
  return true;
}
function noteReparent(noteId,id,parentId){
  let before,nextIds,previousIds;
  const saved=noteSplitCommit(noteId,n=>{
    const rows=noteTree(n.items||[]),row=rows.find(r=>r.it.id===id),parent=rows.find(r=>r.it.id===parentId);
    if(!row||parentId!==null&&!parent)return 'Het item is intussen verwijderd. Kies opnieuw.';
    if(!noteParentValid(rows,id,parentId))return 'Een item kan niet onder zichzelf staan.';
    before=row.it.parentId;
    const group=noteSubtree(rows,row),start=rows.indexOf(row);
    nextIds=rows.slice(start+group.length).map(r=>r.it.id);
    previousIds=rows.slice(0,start).reverse().map(r=>r.it.id);
    if(parentId===null)delete row.it.parentId;else row.it.parentId=parentId;
    // Het hele groepje verhuist naar het einde van de nieuwe lijst.
    const ids=new Set(group.map(r=>r.it.id));
    n.items=n.items.filter(i=>!ids.has(i.id)).concat(n.items.filter(i=>ids.has(i.id)));
  });
  if(!saved)return;
  if(noteCur===noteId)renderItems();
  undoToast('Niveau aangepast',()=>{
    if(noteSplitCommit(noteId,n=>{
      const it=n.items.find(i=>i.id===id);
      if(!it||(it.parentId||null)!==parentId)return 'Het niveau is intussen veranderd. Je wijzigingen blijven behouden.';
      const rows=noteTree(n.items),row=rows.find(r=>r.it.id===id);
      if(!noteParentValid(rows,id,before||null))return 'De oorspronkelijke sublijst is intussen veranderd. Je wijzigingen blijven behouden.';
      const group=noteSubtree(rows,row).map(r=>r.it),ids=new Set(group.map(i=>i.id));
      n.items=n.items.filter(i=>!ids.has(i.id));
      const next=nextIds.find(id=>n.items.some(i=>i.id===id)),previous=previousIds.find(id=>n.items.some(i=>i.id===id));
      const at=next!==undefined?n.items.findIndex(i=>i.id===next):previous!==undefined?n.items.findIndex(i=>i.id===previous)+1:0;
      n.items.splice(at,0,...group);
      if(before===undefined)delete it.parentId;else it.parentId=before;
    })&&noteCur===noteId)renderItems();
  });
}
