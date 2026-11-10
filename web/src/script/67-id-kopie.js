/* ID-kopie: tijdelijke bron en vlakken alleen in het geheugen; export door de native pixelbewerker. */
let idCopyCounter=0;
const idc = {session:'',revision:'', pages:[], page:0, busy:false, loading:false, point:null, draft:null, result:'', meta:null};
function idCopyEnter(){ if(!idc.session)idc.session=Date.now()+'-'+(++idCopyCounter)+'-'+Math.random().toString(36).slice(2); Android.idCopySecure(true,idc.session); if (!$('#id-date').value) $('#id-date').value = rgToday(); idCopyControls(); idCopyStamHalen(); }
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
  idc.bron=null;idc.stamKlaar=null;$('#id-bron').hidden=true;
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
  if(r.master&&idc.bron)idCopyStamVoorstel(previous);
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
  $('#id-status').textContent=idc.melding||t('Voeg ook de achterkant toe als die nodig is.');idc.melding='';idCopyPaint();idCopyControls();
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
  ['id-turn','id-add','id-checked','id-voorstel'].forEach(id=>$('#'+id).disabled=blocked||!idc.meta);
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
function idCopySave(){if($('#id-save').disabled)return;idc.laatste={ontvanger:$('#id-recipient').value.trim(),doel:$('#id-purpose').value.trim(),datum:$('#id-date').value};idc.busy=true;uiWachtTekst($('#id-status'),t('Afgeschermde kopie maken…'));idCopyControls();Android.idCopySave(idc.revision,JSON.stringify({pages:idc.pages,watermark:idCopyWatermark()}));}
window.onIdCopySaved=function(r){
  if(current!=='idcopy'||r.session!==idc.session||r.revision!==idc.revision)return;idc.busy=false;
  if(r.error){$('#id-status').textContent=t(r.error);idCopyControls();return;}
  if(r.revision!==idc.revision)return;
  const bron=idc.bron,w=idc.laatste||{};
  idCopyReset();idc.result=r.name;$('#id-result-name').textContent=r.name;$('#id-result').hidden=false;
  $('#id-status').textContent=t('Kopie gemaakt. Bekijk de afscherming voordat je deelt.');idCopyControls();
  // Vanzelf in de documentenkluis (49b-kluis-centraal.js), met wie hem kreeg; uit Stamgegevens ook gekoppeld aan dat ID-document
  const titel=t('ID-kopie voor {ontvanger} {datum}',{ontvanger:w.ontvanger||'…',datum:w.datum||''}).trim()+'.pdf';
  const link=bron?{kind:'id',ref:bron.doc,soort:bron.soort||'',ontvanger:w.ontvanger||'',doel:w.doel||'',datum:w.datum||''}:undefined;
  kcNaMaken([{name:r.name,title:titel,category:t('Identiteit'),source:'idkopie',link,klaar:doc=>{if(doc&&idc.result===r.name)$('#id-result-name').textContent=doc.title;}}]);
  idc.bron=null;$('#id-bron').hidden=true;
};
function idCopyOpen(){if(idc.result)kcOpen(idc.result);}
function idCopyExport(){if(idc.result)kcSave(idc.result);}
function idCopyShare(){if(idc.result)kcShare(idc.result);}

/* ---------- Vanuit Stamgegevens (documentkaartje van een identiteitsdocument) ----------
 * Stamgegevens leest de kaartfoto's (in Activiteiten: Kaartfoto bekeken door ID-kopie afschermen) en zet ze in Java klaar;
 * dit scherm haalt ze op (idCopyTakeMaster). De foto's gaan nooit door de interface. Daarna stelt de app vlakken voor
 * volgens de vaste indeling van dat soort document; Rene controleert en past aan, zoals altijd. */
function idCopyVanStam(bron){ idc.stamKlaar=bron; show('idcopy'); }
function idCopyStamHalen(){
  const bron=idc.stamKlaar; if(!bron||idc.busy||idc.loading)return;
  idc.stamKlaar=null;
  if(idc.pages.length){idCopyReset();}
  idc.bron=bron;idc.busy=true;uiWachtTekst($('#id-status'),t('Foto\'s uit Stamgegevens laden…'));
  $('#id-bron').hidden=false;$('#id-bron-naam').textContent=bron.label||t('Identiteitsdocument');
  idCopyControls();Android.idCopyTakeMaster();
}
/* Vaste indeling (op de kaart of fotopagina, liggend; links, boven, breedte, hoogte als deel van het geheel). Ruim genomen:
 * liever iets te veel zwart dan te weinig. Alleen een voorstel; de pagina blijft "niet gecontroleerd" tot Rene het aanvinkt. */
const ID_VOORSTEL={
  paspoort:{front:[[.02,.16,.34,.66],[.58,.06,.40,.16],[.34,.58,.46,.12],[.34,.70,.46,.12],[0,.79,1,.21]]},
  idkaart:{front:[[.02,.14,.36,.80],[.52,.02,.46,.18],[.36,.76,.46,.20]],back:[[.02,.02,.66,.24],[0,.58,1,.42]]},
  rijbewijs:{front:[[.02,.18,.32,.70],[.34,.56,.50,.14],[.34,.68,.50,.12],[.34,.80,.50,.16]],back:[]}
};
function idCopySoort(s){s=String(s||'').toLowerCase();return /pas/.test(s)?'paspoort':/rijbewijs/.test(s)?'rijbewijs':/kaart|identiteit|id/.test(s)?'idkaart':'';}
function idCopyStamVoorstel(vanaf){
  const soort=idCopySoort(idc.bron&&idc.bron.soort),sides=String(idc.bron&&idc.bron.sides||'').split(',');let n=0;
  for(let k=vanaf;k<idc.pages.length;k++){const v=(ID_VOORSTEL[soort]||{})[sides[k-vanaf]||'front']||[];idc.pages[k].rects=v.map(r=>r.slice());n+=v.length;}
  idc.melding=n?t('Voorstel: zwarte vlakken op de pasfoto, de nummers, de handtekening en de machineleesbare strook. Controleer elke pagina en pas aan waar nodig.'):t('Geen voorstel voor dit soort document: scherm zelf af wat de ontvanger niet nodig heeft.');
}
/** Ook bij een gekozen foto of PDF: vlakken voorstellen voor deze pagina */
function idCopyVoorstelKiezen(){
  if(idc.busy||idc.loading||!idc.meta)return;
  const zet=(soort,side)=>{const p=idc.pages[idc.page];p.rects=(ID_VOORSTEL[soort][side]||[]).map(r=>r.slice());p.checked=false;idCopyPaint();idCopyControls();$('#id-status').textContent=t('Voorstel geplaatst. Controleer of de vlakken precies goed liggen.');};
  openSheet(t('Vlakken voorstellen'),t('Ligt de foto recht en zie je alleen het document? Dan passen de vlakken het best.'),[
    [t('Paspoort (fotopagina)'),()=>zet('paspoort','front')],[t('ID-kaart, voorkant'),()=>zet('idkaart','front')],[t('ID-kaart, achterkant'),()=>zet('idkaart','back')],[t('Rijbewijs, voorkant'),()=>zet('rijbewijs','front')]]);
}

window.onIdCopyCleanup=function(r){if(r.error)toast(t(r.error));};

function idCopyHasWork(){return idc.busy||idc.loading||idc.pages.length>0;}
window.addEventListener('pagehide',()=>{if(idc.session)idCopyLeave();});
