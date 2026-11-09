/* Je handtekening in PDF → Bewerken en ondertekenen: plaatsen, tekenen en beheren gaan via 02-handtekening.js
 * (dezelfde als in Formulieren en Stamgegevens). Alleen bewaren maakt hem herbruikbaar. */
let pdfKeepPending=null;
function pdfSignatureControls(){
  const live=pdfEd.revision&&pdfEd.meta[pdfEd.page]&&!pdfEd.loading&&!pdfBusy;
  $('#pdf-signature-use').disabled=!sig.value||!live;
  $('#pdf-signature-delete').disabled=pdfBusy||!(sig.value||sig.error);
  $('#pdf-signature-acts').hidden=!(sig.value||sig.error);
  $('#pdf-signature-draw').disabled=pdfBusy;
  $('#pdf-signature-draw').textContent=sig.value?t('Opnieuw tekenen'):t('Tekenen en bewaren');
}
function pdfSignatureRender(){
  const c=$('#pdf-signature-preview');c.hidden=!sigPreview(c);
  $('#pdf-signature-status').textContent=sig.error||(sig.value?t('Je handtekening is versleuteld bewaard op deze telefoon. Je gebruikt hem hier, in Formulieren en in Stamgegevens.'):t('Nog geen handtekening bewaard. Teken hem één keer; daarna gebruik je hem hier, in Formulieren en in Stamgegevens.'));
  pdfSignatureControls();
}
sigWatch(pdfSignatureRender);
function pdfSignaturePut(s){
  if(!sigValid(s)||pdfBusy||pdfEd.loading||!pdfEd.meta[pdfEd.page])return false;
  const m=pdfEd.meta[pdfEd.page],w=Math.min(.4,.8/(s.ratio*m.width/m.height)),before=pdfEd.items.length;
  pdfEd.selected=-1;
  pdfEditPut({type:'ink',page:pdfEd.page,x:.1,y:.1,w,h:w*s.ratio*m.width/m.height,color:s.color,paths:JSON.parse(JSON.stringify(s.paths))});
  return pdfEd.items.length>before;
}
function pdfSignaturePlaced(){$('#pdf-edit-paper').scrollIntoView({block:'center'});}
function pdfSignatureUse(){if(sig.value&&pdfSignaturePut(sig.value)){pdfSignaturePlaced();toast(t('Handtekening geplaatst. Sleep haar naar de juiste plek.'));}}
/* Tekenen en meteen plaatsen (bewaren mag); zonder geopende PDF alleen bewaren. */
function pdfEditTeken(){
  if(pdfBusy||pdfEd.loading||!pdfEd.meta[pdfEd.page])return;
  const rev=pdfEd.revision,page=pdfEd.page;
  sigTeken({plaatsen:(s,bewaard)=>{
    if(pdfEd.revision!==rev||pdfEd.page!==page){toast(bewaard?t('Handtekening bewaard, maar niet geplaatst: de PDF of pagina is intussen veranderd.'):t('Handtekening niet geplaatst: de PDF of pagina is intussen veranderd.'));return;}
    if(pdfSignaturePut(s)){pdfSignaturePlaced();toast(bewaard?t('Handtekening bewaard en geplaatst. Sleep haar naar de juiste plek.'):t('Handtekening geplaatst. Sleep haar naar de juiste plek.'));}
  }});
}
function pdfSignatureDraw(){if(!pdfBusy)sigTeken();}
function pdfSignatureDelete(){if(!pdfBusy)sigVraagWeg('pdf-signature-draw');}
function pdfKeep(name){
  if (isPlay()) { toast(t('Deze tool zit niet in de Play-versie')); return; }
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
  const msg=ok?t('Bewaard in je documentenkluis: {naam}', { naam: r.name }):t(r&&r.error||'Bewaren lukt niet');
  document.querySelectorAll('.pdfkeep').forEach(e=>{e.textContent=msg;});
  toast(ok?'PDF bewaard in documentenkluis':r&&r.error||'Bewaren lukt niet');
};
