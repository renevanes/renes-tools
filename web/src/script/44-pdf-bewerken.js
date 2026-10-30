/* PDF-toevoegingen blijven in het geheugen; alleen expliciet bewaren maakt een document of herbruikbare handtekening. */
const pdfEd = {revision:'', page:0, meta:{}, items:[], undo:[], selected:-1, loading:false, want:false, saved:'[]', strokes:[], stroke:null, drag:null};
function pdfEditDirty(){ return JSON.stringify(pdfEd.items) !== pdfEd.saved; }
function pdfEditPendingInput(){
  const value = $('#pdf-edit-text').value.trim(), selected = pdfEd.items[pdfEd.selected];
  return !!value && (!selected || selected.type !== 'text' || value !== selected.text);
}
function pdfChooseSource(){
  const choose = () => Android.pdfPickPdf();
  if (pdfEditDirty()) askConfirm('Andere PDF kiezen?', 'Je niet-opgeslagen toevoegingen worden vervangen als je een andere PDF opent.', 'PDF kiezen', choose); else choose();
}
function pdfEditChoose(){ pdfEd.want = true; pdfChooseSource(); }
function pdfEditSource(){
  const ready = !!(pdfIn.pages && pdfIn.revision);
  $('#pdf-edit-open').style.display = ready ? '' : 'none';
  if (pdfEd.revision && pdfEd.revision !== pdfIn.revision) {
    pdfEd.items = []; pdfEd.undo = []; pdfEd.meta = {}; pdfEd.revision = ''; pdfEd.saved = '[]'; pdfEd.selected = -1;
    $('#pdf-edit-work').style.display = 'none'; pdfEditSignHide();
  }
  if (pdfEd.want && ready) { pdfEd.want = false; pdfEditOpen(); }
}
function pdfEditOpen(){
  if (pdfBusy || !pdfIn.revision) return;
  if (pdfIn.lossless === false) { toast('Deze PDF kan niet veilig bewerkt worden. Bewaar hem eerst opnieuw als PDF.'); return; }
  if (!pdfEd.revision) { pdfEd.revision = pdfIn.revision; pdfEd.page = 0; $('#pdf-edit-name').value = pdfIn.name + ' bewerkt'; }
  $('#pdf-edit-work').style.display = '';
  $('#pdf-edit-document').textContent = pdfIn.name;
  $('#pdf-edit-page').max = pdfIn.pages; $('#pdf-edit-count').textContent = 'van ' + pdfIn.pages;
  pdfEditLoad();
}
function pdfEditLoad(){
  pdfEd.loading = true; pdfEd.selected = -1; pdfEd.drag = null;
  $('#pdf-edit-page').value = pdfEd.page + 1;
  $('#pdf-edit-paper').style.visibility = 'hidden';
  $('#pdf-edit-status').textContent = 'Pagina laden…';
  pdfEditControls(); Android.pdfEditPreview(pdfEd.revision, pdfEd.page);
}
window.onPdfEditPreview = function(r){
  if (!r || r.revision !== pdfEd.revision || r.page !== pdfEd.page) return;
  pdfEd.loading = false;
  if (r.error || !(r.width > 0 && r.height > 0) || !/^data:image\/jpeg;base64,/.test(r.preview || '')) {
    $('#pdf-edit-status').textContent = r.error || 'De pagina kan niet worden geladen'; pdfEd.meta[pdfEd.page] = null; pdfEditControls(); return;
  }
  pdfEd.meta[r.page] = {width:r.width, height:r.height};
  $('#pdf-edit-preview').src = r.preview;
  $('#pdf-edit-paper').style.aspectRatio = r.width + '/' + r.height;
  $('#pdf-edit-paper').style.visibility = '';
  $('#pdf-edit-status').textContent = 'Pagina ' + (r.page + 1) + '. Voeg tekst of een handtekening toe.';
  pdfEditDraw(); pdfEditControls();
};
function pdfEditPage(delta){ pdfEditGo(pdfEd.page + 1 + delta); }
function pdfEditGo(value){
  if (pdfBusy || pdfEd.loading) return;
  const n = Math.min(pdfIn.pages, Math.max(1, Math.round(+value || 1))) - 1;
  pdfEd.page = n; pdfEditSignHide(); pdfEditLoad();
}
function pdfEditControls(){
  const live = !!(pdfEd.revision && pdfEd.meta[pdfEd.page] && !pdfEd.loading && !pdfBusy);
  $('#pdf-edit-prev').disabled = pdfBusy || pdfEd.loading || pdfEd.page <= 0;
  $('#pdf-edit-next').disabled = pdfBusy || pdfEd.loading || pdfEd.page >= (pdfIn.pages || 1) - 1;
  $('#pdf-edit-page').disabled = pdfBusy || pdfEd.loading;
  $('#pdf-edit-save').disabled = !live || !pdfEd.items.length || !pdfEditDirty();
  $('#pdf-edit-remove').disabled = !live || pdfEd.selected < 0;
  $('#pdf-edit-width').disabled = !live || pdfEd.selected < 0;
  $('#pdf-edit-undo').disabled = !live || !pdfEd.undo.length;
  $('#pdf-edit-add-text').disabled = !live;
  pdfSignatureControls();
}
function pdfEditRemember(){ pdfEd.undo.push(JSON.stringify(pdfEd.items)); if (pdfEd.undo.length > 40) pdfEd.undo.shift(); }
function pdfEditLines(ctx, text, width){
  const lines = [];
  for (const para of text.split('\n')) {
    let line = '';
    for (const ch of para) { if (line && ctx.measureText(line + ch).width > width) { lines.push(line); line = ''; } line += ch; }
    lines.push(line);
  }
  return lines;
}
function pdfEditBox(o){
  const m = pdfEd.meta[o.page];
  if (!m) return false;
  if (o.type === 'text') {
    const c = document.createElement('canvas').getContext('2d'); c.font = o.size + 'px sans-serif';
    o.h = pdfEditLines(c, o.text, o.w * m.width).length * o.size * 1.25 / m.height;
  }
  if (o.h > .95) { toast('Deze tekst past niet op de pagina. Kies minder tekst, een grotere breedte of kleinere letters.'); return false; }
  o.x = Math.max(0, Math.min(1 - o.w, o.x)); o.y = Math.max(0, Math.min(1 - o.h, o.y)); return true;
}
function pdfEditPut(o){
  if (pdfBusy || pdfEd.loading || !pdfEd.meta[pdfEd.page]) return;
  if (pdfEd.items.length >= 100 && pdfEd.selected < 0) { toast('Maximaal 100 toevoegingen per kopie'); return; }
  if (!pdfEditBox(o)) return;
  pdfEditRemember();
  if (pdfEd.selected >= 0) pdfEd.items[pdfEd.selected] = o;
  else { pdfEd.items.push(o); pdfEd.selected = pdfEd.items.length - 1; }
  pdfEditDraw(); pdfEditSelect(pdfEd.selected);
}
function pdfEditText(){
  const text = $('#pdf-edit-text').value.trim(); if (!text) { toast('Vul eerst tekst in'); return; }
  let old = pdfEd.items[pdfEd.selected];
  if (old && old.type !== 'text') { pdfEd.selected = -1; old = null; }
  const o = Object.assign({type:'text',page:pdfEd.page,x:.1,y:.1,w:.6}, old || {}, {text,size:+$('#pdf-edit-size').value,color:$('#pdf-edit-color').value});
  pdfEditPut(o);
}
function pdfEditDate(){ pdfEditSelect(-1); $('#pdf-edit-text').value = new Date().toLocaleDateString('nl-NL'); pdfEditText(); }
function pdfEditStyle(){
  const o = pdfEd.items[pdfEd.selected]; if (!o) return;
  const n = Object.assign({}, o, {color:$('#pdf-edit-color').value}); if (o.type === 'text') n.size = +$('#pdf-edit-size').value;
  pdfEditPut(n);
}
function pdfEditWidth(value){
  const o = pdfEd.items[pdfEd.selected]; if (!o) return;
  const w = Math.max(.1, Math.min(.9, +value / 100));
  pdfEditPut(Object.assign({}, o, {w,h:o.type === 'ink' ? o.h * w / o.w : o.h}));
}
function pdfEditSelect(index){
  pdfEd.selected = index;
  const o = pdfEd.items[index];
  $('#pdf-edit-add-text').textContent = o && o.type === 'text' ? 'Tekst bijwerken' : 'Tekst plaatsen';
  $('#pdf-edit-text').value = o && o.type === 'text' ? o.text : '';
  if (o) { $('#pdf-edit-width').value = o.w * 100; $('#pdf-edit-color').value = o.color; if (o.type === 'text') $('#pdf-edit-size').value = o.size; }
  pdfEditDraw(); pdfEditControls();
}
function pdfEditPaint(canvas, page, selected){
  const m = pdfEd.meta[page], ctx = canvas.getContext('2d'); ctx.clearRect(0,0,canvas.width,canvas.height);
  const w = canvas.width, h = canvas.height;
  pdfEd.items.forEach((o,i) => {
    if (o.page !== page) return;
    ctx.fillStyle = ctx.strokeStyle = o.color;
    if (o.type === 'text') {
      const font = o.size * w / m.width; ctx.font = font + 'px sans-serif'; ctx.textBaseline = 'top';
      pdfEditLines(ctx,o.text,o.w*w).forEach((line,j) => ctx.fillText(line,o.x*w,o.y*h+j*font*1.25));
    } else {
      ctx.lineWidth = Math.max(1, o.w*w*.008); ctx.lineCap = ctx.lineJoin = 'round';
      o.paths.forEach(path => { ctx.beginPath(); path.forEach((p,j) => { const x=(o.x+p[0]*o.w)*w,y=(o.y+p[1]*o.h)*h; if(!j)ctx.moveTo(x,y);else ctx.lineTo(x,y); }); ctx.stroke(); });
    }
    if (selected === i) { ctx.strokeStyle = '#008577'; ctx.lineWidth = 2; ctx.setLineDash([8,5]); ctx.strokeRect(o.x*w,o.y*h,o.w*w,o.h*h); ctx.setLineDash([]); }
  });
}
function pdfEditCanvas(page){
  const m=pdfEd.meta[page], c=document.createElement('canvas');
  const scale=Math.min(2,2048/m.width,4096/m.height,Math.sqrt(5_800_000/(m.width*m.height)));
  c.width=Math.max(1,Math.round(m.width*scale)); c.height=Math.max(1,Math.round(m.height*scale)); return c;
}
function pdfEditDraw(){
  if (!pdfEd.meta[pdfEd.page]) return;
  const c=$('#pdf-edit-layer'), size=pdfEditCanvas(pdfEd.page); c.width=size.width;c.height=size.height;
  pdfEditPaint(c,pdfEd.page,pdfEd.selected);
  const box=$('#pdf-edit-items'); box.replaceChildren();
  pdfEd.items.forEach((o,i)=>{ if(o.page!==pdfEd.page)return;const b=document.createElement('button');b.className='mini';b.textContent=(o.type==='text'?o.text:'Handtekening').slice(0,70);b.setAttribute('aria-pressed',String(i===pdfEd.selected));b.onclick=()=>pdfEditSelect(i);box.appendChild(b); });
}
function pdfEditRemove(){ if(pdfBusy||pdfEd.selected<0)return;pdfEditRemember();pdfEd.items.splice(pdfEd.selected,1);pdfEditSelect(-1); }
function pdfEditUndo(){ if(pdfBusy||!pdfEd.undo.length)return;pdfEd.items=JSON.parse(pdfEd.undo.pop());pdfEditSelect(-1); }
function pdfEditDiscard(){ if(!pdfEd.items.length)return;askConfirm('Toevoegingen wissen?','Verwijdert alle toevoegingen op alle pagina’s van deze werkkopie.','Wissen',()=>{pdfEditRemember();pdfEd.items=[];pdfEditSelect(-1);pdfEditSignHide();}); }
function pdfEditPoint(event,canvas){const r=canvas.getBoundingClientRect();return [Math.max(0,Math.min(1,(event.clientX-r.left)/r.width)),Math.max(0,Math.min(1,(event.clientY-r.top)/r.height))];}
$('#pdf-edit-layer').addEventListener('pointerdown',e=>{
  if(pdfBusy||pdfEd.loading)return;
  const p=pdfEditPoint(e,e.currentTarget);let index=-1;
  pdfEd.items.forEach((o,i)=>{if(o.page===pdfEd.page&&p[0]>=o.x&&p[0]<=o.x+o.w&&p[1]>=o.y&&p[1]<=o.y+o.h)index=i;});
  pdfEditSelect(index);if(index<0)return;
  const o=pdfEd.items[index];pdfEd.drag={point:p,x:o.x,y:o.y,snapshot:JSON.stringify(pdfEd.items),moved:false};e.currentTarget.setPointerCapture(e.pointerId);$('#pdf-edit-paper').focus();e.preventDefault();
});
$('#pdf-edit-layer').addEventListener('pointermove',e=>{
  const d=pdfEd.drag,o=pdfEd.items[pdfEd.selected];if(!d||!o||pdfBusy)return;
  const p=pdfEditPoint(e,e.currentTarget);o.x=d.x+p[0]-d.point[0];o.y=d.y+p[1]-d.point[1];pdfEditBox(o);d.moved=true;pdfEditPaint(e.currentTarget,pdfEd.page,pdfEd.selected);
});
function pdfEditDragEnd(){if(pdfEd.drag&&pdfEd.drag.moved){pdfEd.undo.push(pdfEd.drag.snapshot);if(pdfEd.undo.length>40)pdfEd.undo.shift();}pdfEd.drag=null;pdfEditControls();}
$('#pdf-edit-layer').addEventListener('pointerup',pdfEditDragEnd);$('#pdf-edit-layer').addEventListener('pointercancel',pdfEditDragEnd);
$('#pdf-edit-paper').addEventListener('keydown',e=>{
  const delta={ArrowLeft:[-.005,0],ArrowRight:[.005,0],ArrowUp:[0,-.005],ArrowDown:[0,.005]}[e.key],o=pdfEd.items[pdfEd.selected];
  if(!delta||!o||pdfBusy)return;e.preventDefault();pdfEditPut(Object.assign({},o,{x:o.x+delta[0],y:o.y+delta[1]}));
});
function pdfEditSignShow(){if(pdfBusy||pdfEd.loading||!pdfEd.meta[pdfEd.page])return;pdfEditSignClear();$('#pdf-edit-sign-box').style.display='';$('#pdf-edit-sign').scrollIntoView({block:'center'});}
function pdfEditSignHide(){pdfEditSignClear();$('#pdf-edit-sign-box').style.display='none';}
function pdfEditSignClear(){pdfEd.strokes=[];pdfEd.stroke=null;$('#pdf-edit-sign').getContext('2d').clearRect(0,0,900,300);}
$('#pdf-edit-sign').addEventListener('pointerdown',e=>{pdfEd.stroke=[pdfEditPoint(e,e.currentTarget)];pdfEd.strokes.push(pdfEd.stroke);e.currentTarget.setPointerCapture(e.pointerId);e.preventDefault();});
$('#pdf-edit-sign').addEventListener('pointermove',e=>{
  if(!pdfEd.stroke)return;const p=pdfEditPoint(e,e.currentTarget),q=pdfEd.stroke[pdfEd.stroke.length-1];if(pdfEd.strokes.reduce((s,a)=>s+a.length,0)>=10000)return;
  pdfEd.stroke.push(p);const c=e.currentTarget.getContext('2d');c.strokeStyle=$('#pdf-edit-color').value;c.lineWidth=3;c.lineCap='round';c.beginPath();c.moveTo(q[0]*900,q[1]*300);c.lineTo(p[0]*900,p[1]*300);c.stroke();
});
for(const event of ['pointerup','pointercancel'])$('#pdf-edit-sign').addEventListener(event,()=>{pdfEd.stroke=null;});
function pdfEditSignAdd(){
  const s=pdfSignatureFromPad();if(s&&pdfSignaturePut(s))pdfEditSignHide();
}
function pdfEditSave(){
  if(pdfBusy||pdfEd.loading||!pdfEd.items.length||!pdfEditDirty())return;
  const pages=[...new Set(pdfEd.items.map(o=>o.page))];if(pages.length>30){toast('Bewerk maximaal 30 pagina’s tegelijk');return;}
  try{
    const layers=pages.map(page=>{const c=pdfEditCanvas(page);pdfEditPaint(c,page,-1);return {page,png:c.toDataURL('image/png')};});
    const json=JSON.stringify(layers);if(json.length>12000000){toast('Te veel toevoegingen om tegelijk op te slaan');return;}
    pdfSetBusy('Bewerkte PDF-kopie maken…');pdfEditControls();Android.pdfEditSave(pdfEd.revision,json,$('#pdf-edit-name').value.trim());
  }catch(e){pdfSetBusy('');toast('De bewerkingen kunnen niet worden opgeslagen. Probeer minder toevoegingen.');pdfEditControls();}
}
function pdfEditSaved(r){if(r.revision===pdfEd.revision){pdfEd.saved=JSON.stringify(pdfEd.items);pdfEditSignHide();$('#pdf-edit-status').textContent='Kopie gemaakt. Kies onder Gemaakt: Opslaan als… of Delen.';document.querySelectorAll('#pdf-edit-work [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);}pdfEditControls();}
