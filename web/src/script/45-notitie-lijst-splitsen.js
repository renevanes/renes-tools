/* Lijsttekst bewust opsplitsen, na een voorbeeld en zonder andere items te overschrijven. */
let noteSplit = null;
function noteSplitPrefix(text, done){
  text = text.trim();
  for (let i = 0; i < 3; i++) {
    let m;
    if ((m = /^(\[[ xX]\]|[☐☑☒✓✔]\uFE0F?)\s*/u.exec(text))) {
      done = !/^(\[ \]|☐)/u.test(m[1]); text = text.slice(m[0].length);
    } else if ((m = /^(?:[-*•‣▪–—]|\d{1,6}[.)])(?:\s+|$)/u.exec(text))) text = text.slice(m[0].length);
    else break;
  }
  return {text:text.trim(), done:!!done};
}
function noteSplitParse(text, separator, done){
  if (text.length > 100000) return {error:'Splits maximaal 100.000 tekens tegelijk',items:[]};
  const split = {lines:null,comma:/,/,semicolon:/;/,both:/[,;]/}[separator];
  if (split === undefined) return {error:'Kies waarop je wilt splitsen',items:[]};
  const items = [];
  for (const line of text.split(/\r\n?|\n|\u2028|\u2029/)) {
    const base = noteSplitPrefix(line,done);
    for (const part of split ? base.text.split(split) : [base.text]) {
      const item = noteSplitPrefix(part,base.done);
      if (!item.text) continue;
      items.push(item);
      if (items.length > 500) return {error:'Splits maximaal 500 items tegelijk',items:[]};
    }
  }
  return {items};
}
function noteSplitClose(){
  noteSplit = null;
  $('#note-split-panel').hidden = true;
  $('#note-split-preview').replaceChildren(); $('#note-split-text').value = '';
  $('#note-split-open').setAttribute('aria-expanded','false');
}
function noteSplitOpen(){
  if (!noteById(noteCur) || !notesSaveNow()) return;
  noteUiExtra('edit');
  const n = noteById(noteCur); if (!n) return;
  noteSplit = {noteId:n.id, draft:$('#note-add').value, paste:$('#note-add').value, addParent:noteAddParent};
  const select = $('#note-split-source'); select.replaceChildren();
  const option = (value,label) => { const o = document.createElement('option'); o.value=value;o.textContent=label;select.appendChild(o); };
  option('paste','Tekst plakken of typen'); option('body','Vrije tekst van deze notitie');
  (n.items||[]).forEach(i=>option('item:'+i.id,(i.done?'☑ ':'☐ ')+i.text.slice(0,80)));
  select.value = noteSplit.draft.trim() ? 'paste' : (n.text||'').trim() ? 'body' : 'paste';
  $('#note-split-panel').hidden = false; $('#note-split-open').setAttribute('aria-expanded','true');
  noteSplitSource(); $('#note-split-panel').scrollIntoView({block:'nearest'}); $('#note-split-text').focus({preventScroll:true});
}
function noteSplitSource(){
  if (!noteSplit || noteCur !== noteSplit.noteId) return;
  const n = noteById(noteSplit.noteId); if (!n) return;
  if(noteSplit.source==='paste')noteSplit.paste=$('#note-split-text').value;
  const source = $('#note-split-source').value;
  const item = source.startsWith('item:') ? (n.items||[]).find(i=>i.id===source.slice(5)) : null;
  noteSplit.source = source; noteSplit.original = item ? notesCopy([item])[0] : null;
  noteSplit.body = n.text || '';
  const text = source==='body' ? noteSplit.body : item ? item.text : noteSplit.paste;
  $('#note-split-text').value = text;
  $('#note-split-keep-row').hidden = source!=='body'; $('#note-split-keep-row').style.display = source==='body'?'':'none'; $('#note-split-keep').checked = true;
  $('#note-split-separator').value = /[\r\n\u2028\u2029]/.test(text.trim()) ? 'lines' : text.includes(';') ? 'semicolon' : text.includes(',') ? 'comma' : 'lines';
  noteSplitPreview();
}
function noteSplitPreview(){
  if (!noteSplit) return;
  const r = noteSplitParse($('#note-split-text').value,$('#note-split-separator').value,noteSplit.original && noteSplit.original.done);
  const box = $('#note-split-preview'); box.replaceChildren();
  r.items.forEach(i=>{const li=document.createElement('li');li.textContent=(i.done?'☑ ':'☐ ')+i.text;box.appendChild(li);});
  const count = r.items.length;
  $('#note-split-status').textContent = r.error || (count < 2 ? 'Er zijn minstens twee items nodig. Kies een andere scheiding of voeg meer tekst toe.' : count+' losse items. Controleer het voorbeeld voordat je ze maakt.');
  $('#note-split-apply').disabled = !!r.error || count < 2;
  $('#note-split-apply').textContent = count>1 ? count+' items maken' : 'Items maken';
}
/* Alleen een geslaagde compare-and-save vervangt het model. Na ieder conflict opnieuw valideren. */
function noteSplitCommit(id,change,invalid){
  if(!notesSaveNow())return false;
  for(let attempt=0;attempt<3;attempt++){
    const before=notesGet(),next=notesCopy(before),n=next.find(x=>x.id===id);
    if(!n){toast('Deze notitie is intussen verwijderd.');noteSplitClose();if(current==='note'&&noteCur===id)show('notes');return false;}
    const reason=change(n,next);
    if(reason){if(invalid)invalid(reason);toast(reason);return false;}
    n.updated=Date.now();
    let r;
    try{
      if(typeof Android.notesSaveIf==='function')r=JSON.parse(Android.notesSaveIf(JSON.stringify(next),notesVer));
      else{const err=Android.notesSave(JSON.stringify(next));r=err?{err}:{ok:true};}
    }catch(e){r={err:'Opslaan lukt niet'};}
    if(r.conflict){
      const base=notesBase,version=notesVer;notes=null;notesGet();
      if(notesBroken){notes=before;notesBase=base;notesVer=version;return false;}
      const fresh=noteById(id);
      if(fresh&&current==='note'&&noteCur===id){renderItems();$('#note-text').value=fresh.text||'';$('#note-title').value=fresh.title||'';}
      continue;
    }
    if(r.err||!r.ok){toast('Opslaan mislukt: '+(r.err||'Probeer het opnieuw'));return false;}
    notes=next;notesVer=r.ver||notesVerNow();notesBase=notesCopy(next);return true;
  }
  toast('De notitie verandert steeds. Controleer de lijst en probeer opnieuw.');return false;
}
function noteSplitApply(){
  if (!noteSplit || noteCur!==noteSplit.noteId) return;
  const s=noteSplit;
  const sourceText=$('#note-split-text').value,separator=$('#note-split-separator').value;
  const r=noteSplitParse(sourceText,separator,s.original&&s.original.done);
  if(r.error||r.items.length<2){noteSplitPreview();return;}
  const cleared=s.source==='body'&&!$('#note-split-keep').checked;
  let at,original,added;
  const saved=noteSplitCommit(s.noteId,n=>{
    at=s.original ? (n.items||[]).findIndex(i=>i.id===s.original.id) : -1;
    original=at>=0 ? notesCopy([n.items[at]])[0] : null;
    if(s.original&&(!original||original.text!==s.original.text||!!original.done!==!!s.original.done)||s.source==='body'&&(n.text||'')!==s.body)
      return 'De bron is intussen veranderd. Kies de bron opnieuw om verder te gaan.';
    if(!original&&s.addParent!==null&&!(n.items||[]).some(i=>i.id===s.addParent))return 'Het bovenliggende item is verwijderd. Kies de bron opnieuw.';
    added=r.items.map(i=>Object.assign({},original||{},{id:uid(),text:i.text,done:i.done}));
    // Een hoofditem houdt zijn id op het eerste deel, zodat bestaande subitems eraan vast blijven.
    if(original&&(n.items||[]).some(i=>i.parentId===original.id))added[0].id=original.id;
    if(!original&&s.addParent!==null)added.forEach(i=>i.parentId=s.addParent);
    if(original)n.items.splice(at,1,...added);else n.items=(n.items||[]).concat(added);
    if(cleared)n.text='';
  },reason=>{$('#note-split-status').textContent=reason;$('#note-split-apply').disabled=true;});
  if(!saved)return;
  const addedSnapshot=notesCopy(added);
  if(s.source==='paste'&&sourceText===s.draft&&$('#note-add').value===s.draft)$('#note-add').value='';
  noteSplitClose();
  if(noteCur===s.noteId){$('#note-text').value=noteById(s.noteId)?.text||'';renderItems();noteUiShow('list');}
  const ids=new Set(added.map(i=>i.id));
  const undo=()=>{
    const undone=noteSplitCommit(s.noteId,m=>{
      if(s.source==='paste'&&noteSplit)return 'Er staat een nieuwe lijst klaar om te splitsen. Die blijft behouden.';
      if(addedSnapshot.some(i=>JSON.stringify((m.items||[]).find(x=>x.id===i.id))!==JSON.stringify(i))||cleared&&(m.text||'')!=='')
        return 'De gesplitste items of notitietekst zijn intussen gewijzigd. Ze blijven behouden.';
      if(original&&!ids.has(original.id)&&(m.items||[]).some(i=>i.id===original.id))return 'Het oorspronkelijke item is intussen teruggezet. Je wijzigingen blijven behouden.';
      if((m.items||[]).some(i=>!ids.has(i.id)&&ids.has(i.parentId)&&(!original||i.parentId!==original.id)))return 'Een gesplitst item heeft nieuwe subitems. Die blijven behouden.';
      m.items=m.items.filter(i=>!ids.has(i.id));
      if(original)m.items.splice(Math.min(at,m.items.length),0,original);
      if(cleared)m.text=s.body;
    });
    if(!undone){undoToast('Splitsing niet ongedaan gemaakt',undo);return;}
    if(noteCur===s.noteId){$('#note-text').value=noteById(s.noteId)?.text||'';renderItems();}
    if(s.source==='paste'&&noteCur===s.noteId&&current==='note'){
      noteSplitOpen();
      if(noteSplit){$('#note-split-text').value=sourceText;noteSplit.paste=sourceText;$('#note-split-source').value='paste';noteSplitSource();$('#note-split-separator').value=separator;noteSplitPreview();}
    }
  };
  undoToast(added.length+' losse items gemaakt',undo);
}
