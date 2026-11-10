/* ID-kopie: tijdelijke bron en vlakken alleen in het geheugen; export door de native pixelbewerker. */
let idCopyCounter=0;
const idc = {session:'',revision:'', pages:[], page:0, busy:false, loading:false, point:null, draft:null, result:'', meta:null};
function idCopyEnter(){ if(!idc.session)idc.session=Date.now()+'-'+(++idCopyCounter)+'-'+Math.random().toString(36).slice(2); Android.idCopySecure(true,idc.session); if (!$('#id-date').value) $('#id-date').value = rgToday(); idCopyControls(); }
function idCopyReset(secure=true){
  if(secure){idc.session=Date.now()+'-'+(++idCopyCounter)+'-'+Math.random().toString(36).slice(2);Android.idCopySecure(true,idc.session);}
  Android.idCopyClear(); idc.revision=''; idc.pages=[]; idc.page=0; idc.point=null; idc.draft=null; idc.meta=null;
  $('#id-image').removeAttribute('src'); const c=$('#id-layer'); c.getContext('2d').clearRect(0,0,c.width,c.height); $('#id-work').hidden=true;
  ['id-recipient','id-purpose','id-date'].forEach(id=>$('#'+id).value='');
  ['id-x','id-y','id-w','id-h'].forEach((id,i)=>$('#'+id).value=fmtNumIn([10,10,40,10][i]));
  $('#id-zoom').value=100;idCopyZoom(100);$('#id-pan').checked=false;idCopyPan();
  document.querySelectorAll('#s-idcopy [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
}
function idCopyLeave(){
  idc.session='';idCopyReset(false); Android.idCopySecure(false,''); idc.busy=false; idc.loading=false; idc.result='';
  ['id-recipient','id-purpose','id-date'].forEach(id=>$('#'+id).value=''); $('#id-result').hidden=true; $('#id-status').textContent='';
}
function idCopyChoose(){ if(idc.busy||idc.loading)return; if(!$('#id-date').value)$('#id-date').value=rgToday(); idc.busy=true; $('#id-status').textContent=t('Bestand kiezen…'); idCopyControls(); Android.idCopyPick(); }
window.onIdCopyPicked=function(r){
  if(current!=='idcopy'||r.session!==idc.session)return; idc.busy=false;
  if(r.error){$('#id-status').textContent=t(r.error);idCopyControls();return;}
  if(r.cancelled){$('#id-status').textContent='';idCopyControls();return;}
  const previous=idc.pages.length; idc.revision=r.revision;
  while(idc.pages.length<r.pages)idc.pages.push({turn:0,rects:[],checked:false});
  idc.pages.forEach(p=>p.checked=false); idc.page=Math.min(previous,r.pages-1); idc.result=''; $('#id-result').hidden=true; $('#id-work').hidden=false;
  idCopyLoad();
};
function idCopyLoad(){
  if(!idc.revision||!idc.pages[idc.page])return;
  idc.loading=true; idc.meta=null; idc.point=null; idc.draft=null; $('#id-image').removeAttribute('src');
  const c=$('#id-layer');c.getContext('2d').clearRect(0,0,c.width,c.height);
  uiWachtTekst($('#id-status'),t('Pagina laden…'));idCopyControls();Android.idCopyPreview(idc.revision,idc.page,idc.pages[idc.page].turn);
}
window.onIdCopyPreview=function(r){
  if(current!=='idcopy'||r.session!==idc.session||!idc.revision)return;
  if(r.revision!==idc.revision||r.page!==idc.page||r.turn!==idc.pages[idc.page].turn)return;
  if(r.error){idc.loading=false;$('#id-status').textContent=t(r.error);idCopyControls();return;}
  idc.loading=false;idc.meta=r;$('#id-image').width=r.w;$('#id-image').height=r.h;$('#id-image').src=r.image;$('#id-layer').width=r.w;$('#id-layer').height=r.h;
  $('#id-status').textContent=t('Voeg ook de achterkant toe als die nodig is.');idCopyPaint();idCopyControls();
};
function idCopyWatermark(){
  const who=$('#id-recipient').value.trim(), why=$('#id-purpose').value.trim(), date=$('#id-date').value;
  return t('ID-KOPIE VOOR {ontvanger}',{ontvanger:who||'…'})+'\n'+(why||'…')+'\n'+(date?fmtDate(date):'…');
}
function idCopyWatermarkChanged(){idc.pages.forEach(p=>p.checked=false);idCopyPaint();idCopyControls();}
function idCopyPaint(){
  const c=$('#id-layer'),ctx=c.getContext('2d');ctx.clearRect(0,0,c.width,c.height);if(!idc.meta)return;
  ctx.fillStyle='#000000';ctx.globalAlpha=1;
  const p=idc.pages[idc.page];for(const r of p.rects.concat(idc.draft?[idc.draft]:[]))ctx.fillRect(Math.floor(r[0]*c.width),Math.floor(r[1]*c.height),Math.ceil(r[2]*c.width)+1,Math.ceil(r[3]*c.height)+1);
  const lines=idCopyWatermark().split('\n');let size=Math.min(c.width,c.height)*.045;ctx.font='bold '+size+'px sans-serif';
  const longest=Math.max(1,...lines.map(s=>ctx.measureText(s).width));size=Math.min(size,size*c.width*.9/longest);
  ctx.save();ctx.translate(c.width/2,c.height/2);ctx.rotate(-25*Math.PI/180);ctx.fillStyle='rgba(160,25,25,.8)';ctx.font='bold '+size+'px sans-serif';ctx.textAlign='center';
  lines.forEach((line,i)=>ctx.fillText(line,0,(i-(lines.length-1)/2)*size*1.35));ctx.restore();
}
function idCopyControls(){
  const p=idc.pages[idc.page],blocked=idc.busy||idc.loading;
  $('#id-pick').disabled=blocked||idc.pages.length>=4;
  $('#id-prev').disabled=blocked||idc.page<=0;$('#id-next').disabled=blocked||idc.page>=idc.pages.length-1;
  $('#id-page').textContent=p?t('Pagina {n} van {totaal}',{n:idc.page+1,totaal:idc.pages.length}):'';
  $('#id-masks').textContent=p?tn(p.rects.length,'Eén afschermvlak','{n} afschermvlakken'):'';
  ['id-turn','id-add','id-checked'].forEach(id=>$('#'+id).disabled=blocked||!idc.meta);
  $('#id-undo').disabled=blocked||!p||!p.rects.length;$('#id-checked').checked=!!(p&&p.checked);
  const fields=['id-recipient','id-purpose','id-date'];fields.forEach(id=>$('#'+id).disabled=idc.busy);
  $('#id-save').disabled=blocked||!idc.pages.length||!idc.pages.every(q=>q.checked)||!idc.pages.some(q=>q.rects.length)||fields.some(id=>!$('#'+id).value.trim());
}
function idCopyPage(delta){if(idc.busy||idc.loading)return;idc.page=Math.max(0,Math.min(idc.pages.length-1,idc.page+delta));idCopyLoad();}
function idCopyTurn(){
  if(idc.busy||idc.loading||!idc.meta)return;
  const turn=()=>{const p=idc.pages[idc.page];p.turn=(p.turn+1)%4;p.rects=[];p.checked=false;idCopyLoad();};
  if(idc.pages[idc.page].rects.length)askConfirm('Pagina draaien?','De afschermvlakken van deze pagina worden gewist. Plaats ze opnieuw na het draaien.','Draaien',turn);else turn();
}
function idCopyAdd(rect){
  const p=idc.pages[idc.page];if(!p||!idc.meta||idc.busy||idc.loading)return;
  if(p.rects.length>=60){toast(t('Maximaal 60 vlakken per pagina'));return;}
  if(rect.some(n=>!Number.isFinite(n))||rect[0]<0||rect[1]<0||rect[2]<=0||rect[3]<=0||rect[0]+rect[2]>1.0000001||rect[1]+rect[3]>1.0000001){toast(t('Ongeldig afschermvlak'));return;}
  p.rects.push(rect);p.checked=false;idCopyPaint();idCopyControls();
}
function idCopyManual(){idCopyAdd(['id-x','id-y','id-w','id-h'].map(id=>parseNum($('#'+id).value)/100));}
function idCopyUndo(){if(idc.busy||idc.loading)return;const p=idc.pages[idc.page];if(!p)return;p.rects.pop();p.checked=false;idCopyPaint();idCopyControls();}
function idCopyChecked(on){if(idc.pages[idc.page])idc.pages[idc.page].checked=on;idCopyControls();}
function idCopyZoom(value){$('#id-paper').style.width=value+'%';}
function idCopyPan(){$('#id-layer').style.touchAction=$('#id-pan').checked?'auto':'none';}
function idCopyPoint(e){const b=$('#id-layer').getBoundingClientRect();return [Math.max(0,Math.min(1,(e.clientX-b.left)/b.width)),Math.max(0,Math.min(1,(e.clientY-b.top)/b.height))];}
$('#id-layer').addEventListener('pointerdown',e=>{
  if(idc.busy||idc.loading||!idc.meta||$('#id-pan').checked)return;
  idc.point=idCopyPoint(e);e.currentTarget.setPointerCapture(e.pointerId);e.preventDefault();
});
$('#id-layer').addEventListener('pointermove',e=>{
  if(!idc.point)return;const q=idCopyPoint(e),p=idc.point;
  idc.draft=[Math.min(p[0],q[0]),Math.min(p[1],q[1]),Math.abs(q[0]-p[0]),Math.abs(q[1]-p[1])];idCopyPaint();
});
$('#id-layer').addEventListener('pointerup',()=>{const r=idc.draft;idc.point=null;idc.draft=null;if(r&&r[2]>.002&&r[3]>.002)idCopyAdd(r);else idCopyPaint();});
$('#id-layer').addEventListener('pointercancel',()=>{idc.point=null;idc.draft=null;idCopyPaint();});
function idCopySave(){if($('#id-save').disabled)return;idc.busy=true;uiWachtTekst($('#id-status'),t('Afgeschermde kopie maken…'));idCopyControls();Android.idCopySave(idc.revision,JSON.stringify({pages:idc.pages,watermark:idCopyWatermark()}));}
window.onIdCopySaved=function(r){
  if(current!=='idcopy'||r.session!==idc.session||r.revision!==idc.revision)return;idc.busy=false;
  if(r.error){$('#id-status').textContent=t(r.error);idCopyControls();return;}
  if(r.revision!==idc.revision)return;
  idCopyReset();idc.result=r.name;$('#id-result-name').textContent=r.name;$('#id-result').hidden=false;
  $('#id-status').textContent=t('Kopie gemaakt. Bekijk de afscherming voordat je deelt.');idCopyControls();
};
function idCopyOpen(){if(idc.result)Android.pdfOpen(idc.result);}
function idCopyExport(){if(idc.result)Android.pdfSave(idc.result);}
function idCopyShare(){if(idc.result)Android.pdfShare(JSON.stringify([idc.result]));}

window.onIdCopyCleanup=function(r){if(r.error)toast(t(r.error));};

function idCopyHasWork(){return idc.busy||idc.loading||idc.pages.length>0;}
window.addEventListener('pagehide',()=>{if(idc.session)idCopyLeave();});
