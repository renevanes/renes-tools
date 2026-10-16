/* Alleen expliciet bewaren maakt een herbruikbare handtekening op de telefoon. */
const pdfSig={value:null,error:''};
let pdfKeepPending=null;
function pdfSignatureValid(s){
  if(!s||!Number.isFinite(s.ratio)||s.ratio<.01||s.ratio>10||!/^#[0-9a-f]{6}$/i.test(s.color||'')||!Array.isArray(s.paths)||!s.paths.length||s.paths.length>200)return false;
  let count=0,moved=false;
  for(const path of s.paths){
    if(!Array.isArray(path)||path.length<2)return false;
    count+=path.length;if(count>10000)return false;
    for(const p of path){if(!Array.isArray(p)||p.length!==2||p.some(v=>!Number.isFinite(v)||v<0||v>1))return false;if(p[0]!==path[0][0]||p[1]!==path[0][1])moved=true;}
  }
  return moved;
}
function pdfSignatureFromPad(){
  const paths=pdfEd.strokes.filter(p=>p.length>1);if(!paths.length){toast('Teken eerst je handtekening');return null;}
  const points=paths.flat(),xs=points.map(p=>p[0]),ys=points.map(p=>p[1]);
  const x=Math.max(0,Math.min(...xs)-.02),y=Math.max(0,Math.min(...ys)-.06),w=Math.min(1,Math.max(...xs)+.02)-x,h=Math.min(1,Math.max(...ys)+.06)-y;
  const s={version:1,ratio:h*300/(w*900),color:$('#pdf-edit-color').value,paths:paths.map(p=>p.map(q=>[(q[0]-x)/w,(q[1]-y)/h]))};
  if(!pdfSignatureValid(s)){toast('Teken een handtekening met maximaal 10.000 punten en 200 lijnen');return null;}
  return s;
}
function pdfSignatureLoad(){
  try{const r=JSON.parse(Android.pdfSignatureLoad());if(r.error)throw Error(r.error);if(r.signature&&!pdfSignatureValid(r.signature))throw Error('Bewaarde handtekening is niet te lezen');pdfSig.value=r.signature||null;pdfSig.error='';}
  catch(e){pdfSig.value=null;pdfSig.error=e.message||'Handtekening laden lukt niet';}
  pdfSignatureRender();
}
function pdfSignatureControls(){
  const live=pdfEd.revision&&pdfEd.meta[pdfEd.page]&&!pdfEd.loading&&!pdfBusy;
  $('#pdf-signature-use').disabled=!pdfSig.value||!live;
  $('#pdf-signature-delete').disabled=pdfBusy||!(pdfSig.value||pdfSig.error);
  $('#pdf-signature-save').disabled=pdfBusy;
}
function pdfSignatureRender(){
  const c=$('#pdf-signature-preview'),ctx=c.getContext('2d'),s=pdfSig.value;
  ctx.clearRect(0,0,c.width,c.height);c.hidden=!s;
  if(s){
    const w=Math.min(c.width-24,(c.height-24)/s.ratio),h=w*s.ratio,x=(c.width-w)/2,y=(c.height-h)/2;
    ctx.strokeStyle=s.color;ctx.lineWidth=Math.max(2,w*.008);ctx.lineCap=ctx.lineJoin='round';
    for(const path of s.paths){ctx.beginPath();path.forEach((p,i)=>{if(i)ctx.lineTo(x+p[0]*w,y+p[1]*h);else ctx.moveTo(x+p[0]*w,y+p[1]*h);});ctx.stroke();}
  }
  $('#pdf-signature-status').textContent=pdfSig.error||(s?'Je handtekening is versleuteld bewaard op deze telefoon. Kies een PDF om haar opnieuw te plaatsen.':'Nog geen handtekening bewaard. Teken er een in de PDF-bewerker en kies Handtekening bewaren.');
  pdfSignatureControls();
}
function pdfSignatureSaveDraw(){
  if(pdfBusy)return;const s=pdfSignatureFromPad();if(!s)return;
  const save=()=>{
    let err;try{err=Android.pdfSignatureSave(JSON.stringify(s));}catch(e){err='Handtekening bewaren lukt niet';}
    if(err){toast(err);return;}
    pdfSig.value=JSON.parse(JSON.stringify(s));pdfSig.error='';pdfSignatureRender();toast('Handtekening bewaard voor volgende PDF’s');
  };
  if(pdfSig.value||pdfSig.error)askConfirm('Bewaarde handtekening vervangen?','De nieuwe tekening vervangt je eerder bewaarde handtekening.','Vervangen',save);else save();
}
function pdfSignaturePut(s){
  if(!pdfSignatureValid(s)||pdfBusy||pdfEd.loading||!pdfEd.meta[pdfEd.page])return false;
  const m=pdfEd.meta[pdfEd.page],w=Math.min(.4,.8/(s.ratio*m.width/m.height)),before=pdfEd.items.length;
  pdfEd.selected=-1;
  pdfEditPut({type:'ink',page:pdfEd.page,x:.1,y:.1,w,h:w*s.ratio*m.width/m.height,color:s.color,paths:JSON.parse(JSON.stringify(s.paths))});
  return pdfEd.items.length>before;
}
function pdfSignatureUse(){if(pdfSig.value&&pdfSignaturePut(pdfSig.value)){pdfEditSignHide();$('#pdf-edit-paper').scrollIntoView({block:'center'});toast('Handtekening geplaatst. Sleep haar naar de juiste plek.');}}
function pdfSignatureDelete(){
  if(pdfBusy)return;
  askConfirm('Bewaarde handtekening verwijderen?','Je kunt haar daarna niet meer opnieuw gebruiken. Handtekeningen die al in een PDF staan blijven staan.','Verwijderen',()=>{
    let err;try{err=Android.pdfSignatureDelete();}catch(e){err='Handtekening verwijderen lukt niet';}
    if(err){toast(err);return;}
    pdfSig.value=null;pdfSig.error='';pdfSignatureRender();toast('Bewaarde handtekening verwijderd');
  });
}
function pdfKeep(name){
  if(pdfKeepPending||pdfBusy)return;
  askInput('Bewaren in documentenkluis','Categorie (mag leeg). De PDF blijft ook onder Gemaakt staan.','PDF','Bewaren',category=>{
    if(category.trim().length>60){toast('Kies een categorie van maximaal 60 tekens');return;}
    pdfKeepPending={name,category:category.trim(),saving:false};pdfSetBusy('Documentenkluis openen…');
    try{const d=JSON.parse(Android.kluisList());if(d.error)throw Error(d.error);if(d.locked)Android.kluisUnlock();else pdfKeepUnlocked(true);}
    catch(e){pdfKeepPending=null;pdfSetBusy('');toast(e.message||'Kluis openen lukt niet');}
  });
}
function pdfKeepUnlocked(result){
  const p=pdfKeepPending;if(!p||p.saving)return;
  if(result!==true){pdfKeepPending=null;pdfSetBusy('');toast(typeof result==='string'?result:'Kluis niet ontgrendeld');return;}
  p.saving=true;pdfSetBusy('PDF bewaren in documentenkluis…');
  try{Android.kluisSavePdf(p.name,p.category);}catch(e){onPdfStored({error:'Bewaren in de kluis lukt niet'});}
}
window.onPdfStored=function(r){
  if(!pdfKeepPending)return;pdfKeepPending=null;pdfSetBusy('');
  const ok=!!(r&&r.ok);
  $('#pdf-keep-status').textContent=ok?'Bewaard in je documentenkluis: '+r.name:(r&&r.error||'Bewaren lukt niet');
  toast(ok?'PDF bewaard in documentenkluis':r&&r.error||'Bewaren lukt niet');
};
