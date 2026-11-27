/* ID-kopie: tijdelijke bron en vlakken alleen in het geheugen; export door de native pixelbewerker. */
let idCopyCounter=0;
const idc = {volg:0, session:'',revision:'', pages:[], page:0, busy:false, loading:false, point:null, draft:null, result:'', meta:null};
function idCopyEnter(){ if(!idc.session)idc.session=Date.now()+'-'+(++idCopyCounter)+'-'+Math.random().toString(36).slice(2); Android.idCopySecure(true,idc.session); if (!$('#id-date').value) $('#id-date').value = rgToday(); idCopyControls(); idCopyStamHalen(); }
function idCopyReset(secure=true){
  if(secure){idc.session=Date.now()+'-'+(++idCopyCounter)+'-'+Math.random().toString(36).slice(2);Android.idCopySecure(true,idc.session);}
  Android.idCopyClear(); idc.revision=''; idc.pages=[]; idc.page=0; idc.point=null; idc.draft=null; idc.meta=null; idCopyVeldenToon();
  $('#id-image').removeAttribute('src'); const c=$('#id-layer'); c.getContext('2d').clearRect(0,0,c.width,c.height); $('#id-work').hidden=true;
  ['id-recipient','id-purpose','id-date'].forEach(id=>$('#'+id).value='');
  ['id-x','id-y','id-w','id-h'].forEach((id,i)=>$('#'+id).value=fmtNumIn([10,10,40,10][i]));
  idc.bz.leeg();$('#id-pan').checked=false;idCopyPan();
  document.querySelectorAll('#s-idcopy [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
}
function idCopyLeave(){
  idc.bron=null;idc.stamKlaar=null;$('#id-bron').hidden=true;
  idc.session='';idCopyReset(false); Android.idCopySecure(false,''); idc.busy=false; idc.loading=false; idc.result='';
  ['id-recipient','id-purpose','id-date'].forEach(id=>$('#'+id).value=''); $('#id-result').hidden=true; $('#id-status').textContent='';
}
function idCopyChoose(){ if(idc.busy||idc.loading)return; bronKies({ titel: t('Foto of PDF kiezen'), soort: 'pdfbeeld', kiezer: 'idkopie', bestand: idCopyKiezer,
  // Uit Stamgegevens begint een nieuwe kopie (via Stamgegevens terug naar dit scherm): alleen als er nog niets staat
  voor: idCopyStamKan() && !idc.pages.length ? [[t('Uit Stamgegevens'), idCopyUitStam, 'user']] : [] }); }
function idCopyKiezer(){ if(idc.busy||idc.loading)return; if(!$('#id-date').value)$('#id-date').value=rgToday(); idc.busy=true; $('#id-status').textContent=t('Bestand kiezen…'); idCopyControls(); Android.idCopyPick(); }
window.onIdCopyPicked=function(r){
  if(current!=='idcopy'||r.session!==idc.session)return; idc.busy=false;
  if(r.error){$('#id-status').textContent=t(r.error);idCopyControls();return;}
  if(r.cancelled){$('#id-status').textContent='';idCopyControls();return;}
  const previous=idc.pages.length; idc.revision=r.revision;
  while(idc.pages.length<r.pages)idc.pages.push({turn:0,rects:[],velden:[],checked:false});
  idc.pages.forEach(q=>{q.veldKey='';q.herken='';q.velden=[];}); // nieuwe werkbestanden: per pagina opnieuw zoeken zodra hij in beeld komt
  if(r.master&&idc.bron)idCopyStamVoorstel(previous);
  idc.pages.forEach(p=>p.checked=false); idc.page=Math.min(previous,r.pages-1); idc.result=''; $('#id-result').hidden=true; $('#id-work').hidden=false;
  idCopyLoad();
};
function idCopyLoad(){
  if(!idc.revision||!idc.pages[idc.page])return;
  idc.loading=true; idc.meta=null; idc.point=null; idc.draft=null; $('#id-image').removeAttribute('src'); idc.bz.leeg();
  const c=$('#id-layer');c.getContext('2d').clearRect(0,0,c.width,c.height);
  uiWachtTekst($('#id-status'),t('Pagina laden…'));idCopyControls();
  // Met een bewerking uit Foto bewerken (bijsnijden, rechtzetten; 82-foto-bewerken.js) via idCopyPreviewBewerkt
  const p=idc.pages[idc.page];if(p.ops&&p.opsTurn!==p.turn)p.ops=null; // intussen gedraaid
  if(typeof Android.idCopyPreviewBewerkt==='function')Android.idCopyPreviewBewerkt(idc.revision,idc.page,p.turn,p.ops?JSON.stringify(p.ops):'',++idc.volg,false);
  else Android.idCopyPreview(idc.revision,idc.page,p.turn);
}
window.onIdCopyPreview=function(r){
  if(current!=='idcopy'||r.session!==idc.session||!idc.revision)return;
  if(r.revision!==idc.revision||r.page!==idc.page||r.turn!==idc.pages[idc.page].turn)return;
  if(r.error){idc.loading=false;$('#id-status').textContent=t(r.error);idCopyControls();return;}
  idc.loading=false;idc.meta=r;$('#id-image').width=r.w;$('#id-image').height=r.h;$('#id-image').src=r.image;$('#id-layer').width=r.w;$('#id-layer').height=r.h;
  $('#id-status').textContent=idc.melding||t('Voeg ook de achterkant toe als die nodig is.');idc.melding='';idCopyPaint();idCopyControls();
  setTimeout(idCopyHerkenStart,0); // na de andere luisteraars (82-foto-bewerken.js stelt eerst bijsnijden voor)
};
function idCopyWatermark(){
  const who=$('#id-recipient').value.trim(), why=$('#id-purpose').value.trim(), date=$('#id-date').value;
  return t('ID-KOPIE VOOR {ontvanger}',{ontvanger:who||'…'})+'\n'+(why||'…')+'\n'+(date?fmtDate(date):'…');
}
function idCopyWatermarkChanged(){idc.pages.forEach(p=>p.checked=false);idCopyPaint();idCopyControls();}
function idCopyPaint(){
  const c=$('#id-layer'),ctx=c.getContext('2d');ctx.clearRect(0,0,c.width,c.height);if(!idc.meta)return;
  ctx.fillStyle='#000000';ctx.globalAlpha=1;
  const p=idc.pages[idc.page];for(const r of idCopyVlakken(p).concat(idc.draft?[idc.draft]:[]))ctx.fillRect(Math.floor(r[0]*c.width),Math.floor(r[1]*c.height),Math.ceil(r[2]*c.width)+1,Math.ceil(r[3]*c.height)+1);
  idCopyPaintNamen(ctx,c,p);ctx.fillStyle='#000000';
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
  $('#id-masks').textContent=p?tn(idCopyVlakken(p).length,'Eén afschermvlak','{n} afschermvlakken'):'';
  const zoekt=!!p&&p.herken==='bezig'; // eerst weten wat er zwart wordt, dan pas controleren
  ['id-turn','id-add','id-voorstel'].forEach(id=>$('#'+id).disabled=blocked||!idc.meta);$('#id-checked').disabled=blocked||!idc.meta||zoekt;
  $('#id-undo').disabled=blocked||!p||!p.rects.length;$('#id-checked').checked=!!(p&&p.checked);
  const fields=['id-recipient','id-purpose','id-date'];fields.forEach(id=>$('#'+id).disabled=idc.busy);
  $('#id-save').disabled=blocked||idc.pages.some(q=>q.herken==='bezig')||!idc.pages.length||!idc.pages.every(q=>q.checked)||!idc.pages.some(q=>idCopyVlakken(q).length)||fields.some(id=>!$('#'+id).value.trim());
}
function idCopyPage(delta){if(idc.busy||idc.loading)return;idc.page=Math.max(0,Math.min(idc.pages.length-1,idc.page+delta));idCopyLoad();}
function idCopyTurn(){
  if(idc.busy||idc.loading||!idc.meta)return;
  const turn=()=>{const p=idc.pages[idc.page];p.turn=(p.turn+1)%4;p.rechtop='zelf';p.rects=[];p.velden=[];p.veldKey='';p.herken='';p.checked=false;idCopyLoad();};
  if(idc.pages[idc.page].rects.length)askConfirm('Pagina draaien?','De afschermvlakken van deze pagina worden gewist. Plaats ze opnieuw na het draaien.','Draaien',turn);else turn();
}
function idCopyAdd(rect){
  const p=idc.pages[idc.page];if(!p||!idc.meta||idc.busy||idc.loading)return;
  if(idCopyVlakken(p).length>=60){toast(t('Maximaal 60 vlakken per pagina'));return;}
  if(rect.some(n=>!Number.isFinite(n))||rect[0]<0||rect[1]<0||rect[2]<=0||rect[3]<=0||rect[0]+rect[2]>1.0000001||rect[1]+rect[3]>1.0000001){toast(t('Ongeldig afschermvlak'));return;}
  p.rects.push(rect);p.checked=false;idCopyPaint();idCopyControls();
}
function idCopyManual(){idCopyAdd(['id-x','id-y','id-w','id-h'].map(id=>parseNum($('#'+id).value)/100));}
function idCopyUndo(){if(idc.busy||idc.loading)return;const p=idc.pages[idc.page];if(!p)return;p.rects.pop();p.checked=false;idCopyPaint();idCopyControls();}
function idCopyChecked(on){if(idc.pages[idc.page])idc.pages[idc.page].checked=on;idCopyControls();}
function idCopyPan(){$('#id-layer').style.touchAction=$('#id-pan').checked?'pan-y':'none';}
// De vaste set bij een foto (02-beeldzoom.js): vergroten met de knoppen of twee vingers; één vinger tekent een vlak, tenzij Verschuiven aan staat
idc.bz=beeldZoom($('#id-scroll'),{inhoud:$('#id-paper'),balk:$('#id-zoom'),id:'id-zoom',rand:4,max:5,eenVinger:()=>$('#id-pan').checked,gebaar:()=>{idc.point=null;idc.draft=null;idCopyPaint();}});
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
function idCopySave(){if($('#id-save').disabled)return;idc.laatste={ontvanger:$('#id-recipient').value.trim(),doel:$('#id-purpose').value.trim(),datum:$('#id-date').value};idc.busy=true;uiWachtTekst($('#id-status'),t('Afgeschermde kopie maken…'));idCopyControls();Android.idCopySave(idc.revision,JSON.stringify({pages:idc.pages.map(p=>({turn:p.turn,checked:!!p.checked,rects:idCopyVlakken(p),ops:p.ops||undefined})),watermark:idCopyWatermark()}));}
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
  $('#id-result-note').textContent=kcAan()?t('Het tijdelijke bronbestand is gewist. De afgeschermde kopie gaat versleuteld de documentenkluis in, met voor wie en waarvoor.'):t('Het tijdelijke bronbestand is gewist. De afgeschermde kopie staat ook bij PDF → Gemaakt.');
};
function idCopyOpen(){if(idc.result)kcOpen(idc.result);}
function idCopyExport(){if(idc.result)kcSave(idc.result);}
function idCopyShare(){if(idc.result)kcShare(idc.result);}

/* ---------- Vanuit Stamgegevens (documentkaartje van een identiteitsdocument) ----------
 * Stamgegevens leest de kaartfoto's (in Activiteiten: Kaartfoto bekeken door ID-kopie afschermen) en zet ze in Java klaar;
 * dit scherm haalt ze op (idCopyTakeMaster). De foto's gaan nooit door de interface. Daarna stelt de app vlakken voor
 * volgens de vaste indeling van dat soort document; Rene controleert en past aan, zoals altijd. */
function idCopyVanStam(bron){ idc.stamKlaar=bron; show('idcopy'); }
/* Vanaf ID-kopie zelf: een identiteitsdocument uit Stamgegevens kiezen (eerst Stamgegevens open, met het slotscherm als dat
 * aan staat). Alleen documenten met een foto; die van jezelf bovenaan. Daarna dezelfde weg als vanaf het documentkaartje. */
function idCopyStamKan(){ return !isPlay()&&typeof cmOpen==='function'&&(typeof soloOk!=='function'||soloOk('masterdata')); }
function idCopyUitStam(){ if(!idCopyStamKan())return; idc.stamKiezen=0; jumpTo('masterdata'); tsDaarna('masterdata',()=>{idc.stamKiezen=Date.now();idCopyStamWacht();}); }
function idCopyStamTerug(){ idc.stamKiezen=0; if(current==='masterdata')goBack(); }
function idCopyStamWacht(){
  const begin=idc.stamKiezen; if(!begin||current!=='masterdata'){idc.stamKiezen=0;return;}
  if(!(cmOpen()&&cm.recordsReady)){ if(Date.now()-begin<30000)setTimeout(idCopyStamWacht,200); else idc.stamKiezen=0; return; }
  idc.stamKiezen=0;
  const me=typeof cmkMe==='function'?cmkMe():'',mijn=r=>!!me&&r.person===me,naam=id=>(cm.records.find(x=>x.id===id)||{}).label||'';
  const docs=cm.records.filter(r=>r.type==='identiteit'&&(r.fotos||0)>0).sort((a,b)=>(mijn(b)-mijn(a))||String(a.label).localeCompare(String(b.label)));
  if(!docs.length){toast(t('Nog geen identiteitsdocument met een foto in Stamgegevens. Voeg eerst een foto toe.'));idCopyStamTerug();return;}
  // Sluiten zonder keuze (ook bij vergrendelen) = terug naar ID-kopie
  openSheet(t('Welk document?'),t('De foto\'s blijven in Stamgegevens; de app maakt er een afgeschermde kopie van.'),
    docs.map(r=>[r.person&&!r.label.includes(naam(r.person))&&naam(r.person)?t('{document} · {persoon}',{document:r.label,persoon:naam(r.person)}):r.label,()=>idCopyStamMaak(r),'shield']),idCopyStamTerug);
  document.querySelectorAll('#sheet-acts button').forEach(b=>b.setAttribute('translate','no')); // namen van documenten zijn gegevens
}
function idCopyStamMaak(r){
  if(!cmOpen()||cm.mutating){idCopyStamTerug();return;}
  cmRequest('masterIdCopySource',[r.id,r.revision],out=>idCopyVanStam({doc:r.id,soort:out.soort||r.soort||'',sides:out.sides||'',label:r.label}));
}
function idCopyStamHalen(){
  const bron=idc.stamKlaar; if(!bron||idc.busy||idc.loading)return;
  idc.stamKlaar=null;
  if(idc.pages.length){idCopyReset();}
  idc.bron=bron;idc.busy=true;uiWachtTekst($('#id-status'),t('Foto\'s uit Stamgegevens laden…'));
  $('#id-bron').hidden=false;$('#id-bron-naam').textContent=bron.label||t('Identiteitsdocument');
  idCopyControls();Android.idCopyTakeMaster();
}
/* ---------- Velden: wat moet of kan er weg, herkennen en afschermen ----------
 * Per pagina een lijst velden (p.velden: {veld, rect, bron: 'herkend' | 'geschat', aan}). Java herkent ze op de foto zelf
 * (idCopyHerken → IdVelden: burgerservicenummer, strook, documentnummer, pasfoto), de QR-code zoekt de interface (jsQR).
 * Wat niet herkend is en wel op dit soort document staat, krijgt een geschat vlak volgens de vaste indeling (ID_VOORSTEL).
 * Alles staat aan; Rene zet uit wat de ontvanger nodig heeft en controleert elke pagina, zoals altijd. */
const ID_VELD=()=>({
  bsn:{naam:t('Burgerservicenummer'),moet:true},strook:{naam:t('Machineleesbare strook'),moet:true},qr:{naam:t('QR-code'),moet:true},
  pasfoto:{naam:t('Pasfoto'),moet:false},handtekening:{naam:t('Handtekening'),moet:false},documentnummer:{naam:t('Documentnummer'),moet:false},overig:{naam:t('Overige gegevens'),moet:false},anderepagina:{naam:t('Andere pagina van het paspoort'),moet:false}});
/* Vaste indeling (op de kaart of fotopagina, liggend; links, boven, breedte, hoogte als deel van het geheel). Ruim genomen:
 * liever iets te veel zwart dan te weinig. Alleen als de foto precies het document toont; daarom "plek geschat". */
const ID_VOORSTEL={
  paspoort:{front:[['pasfoto',[.02,.16,.34,.66]],['documentnummer',[.58,.06,.40,.16]],['bsn',[.34,.58,.46,.12]],['handtekening',[.34,.70,.46,.12]],['strook',[0,.79,1,.21]]]},
  idkaart:{front:[['pasfoto',[.02,.14,.36,.80]],['documentnummer',[.52,.02,.46,.18]],['handtekening',[.36,.76,.46,.20]]],back:[['bsn',[.02,.02,.66,.24]],['strook',[0,.58,1,.42]]]},
  rijbewijs:{front:[['pasfoto',[.02,.18,.32,.70]],['documentnummer',[.34,.56,.50,.14]],['handtekening',[.34,.68,.50,.12]],['overig',[.34,.80,.50,.16]]],back:[]}
};
function idCopySoort(s){s=String(s||'').toLowerCase();return /paspoort/.test(s)?'paspoort':/rijbewijs/.test(s)?'rijbewijs':/identiteitskaart|id-kaart|idkaart/.test(s)?'idkaart':'';}
/** Alle zwarte vlakken van een pagina: de velden die aan staan en wat Rene zelf tekende. */
function idCopyVlakken(p){return p?(p.velden||[]).filter(v=>v.aan).map(v=>v.rect).concat(p.rects):[];}
function idCopyKanHerkennen(){return typeof Android.idCopyHerken==='function';}
/** Uit Stamgegevens weet de app al welk document en welke kant het is. */
function idCopyStamVoorstel(vanaf){
  const soort=idCopySoort(idc.bron&&idc.bron.soort),sides=String(idc.bron&&idc.bron.sides||'').split(',');
  for(let k=vanaf;k<idc.pages.length;k++){idc.pages[k].soort=soort;idc.pages[k].zijde=sides[k-vanaf]||'front';}
}
function idCopyVeldKey(p){return idc.revision+'|'+p.turn+'|'+JSON.stringify(p.ops||null);}
/** Na het laden van een pagina: velden zoeken (één keer per stand van de pagina). */
function idCopyHerkenStart(){
  const p=idc.pages[idc.page];if(current!=='idcopy'||!p||!idc.meta||idc.loading||p.autoBezig)return;
  const key=idCopyVeldKey(p);if(p.veldKey===key){idCopyVeldenToon();return;}
  p.veldKey=key;p.velden=[];p.pagina=null;p.checked=false;p.herken='bezig';p.hvolg=idc.hvolg=(idc.hvolg||0)+1;idCopyVeldenToon();idCopyPaint();idCopyControls();
  idCopyQr(p,key,idc.meta.image);
  const volg=p.hvolg;setTimeout(()=>{if(p.hvolg===volg&&p.herken==='bezig'&&idc.pages.includes(p))idCopyHerkend(p,{velden:[],soort:'',fout:true});},90000); // geen antwoord: verder zonder
  if(idCopyKanHerkennen())Android.idCopyHerken(idc.revision,idc.page,p.turn,p.ops?JSON.stringify(p.ops):'',p.hvolg);
  else idCopyHerkend(p,{velden:[],soort:''});
}
window.onIdCopyHerkend=function(r){
  if(!r||r.session!==idc.session||r.revision!==idc.revision)return;
  const p=idc.pages[r.page];if(!p||p.hvolg!==r.volg||p.herken!=='bezig'||(r.turn!=null&&r.turn!==p.turn))return;
  idCopyHerkend(p,r.error?{velden:[],soort:'',fout:true}:r);
};
function idCopyRechtOk(r){return Array.isArray(r)&&r.length===4&&r.every(Number.isFinite)&&r[0]>=0&&r[1]>=0&&r[2]>0&&r[3]>0&&r[0]+r[2]<=1.0000001&&r[1]+r[3]<=1.0000001;}
function idCopyHerkend(p,r){
  // De foto staat op zijn kant of op zijn kop (de pasfoto zegt het): eerst rechtop zetten en dan opnieuw zoeken. Eén keer per pagina.
  const k=+r.draai;
  if((k===1||k===2||k===3)&&!p.rechtop&&!p.rects.length&&p===idc.pages[idc.page]&&!idc.busy){
    p.rechtop='vanzelf';p.turn=(p.turn+k)%4;p.ops=null;p.auto=undefined;p.velden=[];p.veldKey='';p.herken='';p.checked=false;idCopyLoad();return;
  }
  const kan=ID_VELD();
  // waar de pagina met de gegevens ligt (uit de strook): de foto kan meer tonen, zoals een opengeklapt paspoort
  const pg=r.pagina,pr=pg&&[+pg.x,+pg.y,+pg.w,+pg.h];p.pagina=pr&&idCopyRechtOk(pr)?{rect:pr,soort:idCopySoort(pg.soort)}:null;
  for(const v of (r.velden||[]).slice(0,20)){const rect=[+v.x,+v.y,+v.w,+v.h];if(Object.prototype.hasOwnProperty.call(kan,v.veld)&&idCopyRechtOk(rect))p.velden.push({veld:v.veld,rect,bron:'herkend',aan:true});}
  if(!p.soort)p.soort=idCopySoort(r.soort);
  p.herken=r.fout?'fout':'klaar';idCopyGeschat(p);p.checked=false;
  if(p===idc.pages[idc.page]){idCopyVeldenToon();idCopyPaint();idCopyControls();}
}
/** Wat op dit soort document staat en niet herkend is: een geschat vlak volgens de vaste indeling. */
function idCopyGeschat(p){
  p.velden=p.velden.filter(v=>v.bron!=='geschat');if(!p.soort)return;
  const heeft=v=>p.velden.some(x=>x.veld===v);
  // een herkend veld vervangt het geschatte vlak alleen als het er grotendeels in ligt: anders blijft het geschatte staan (liever te veel zwart)
  const gedekt=(veld,g)=>p.velden.some(x=>{if(x.veld!==veld||x.bron!=='herkend')return false;const r=x.rect,bx=Math.max(0,Math.min(r[0]+r[2],g[0]+g[2])-Math.max(r[0],g[0])),by=Math.max(0,Math.min(r[1]+r[3],g[1]+g[3])-Math.max(r[1],g[1]));return bx*by>=r[2]*r[3]*.5;});
  // welke kant: uit Stamgegevens bekend, anders een achterkant als er een strook of QR-code staat en geen pasfoto
  const zijde=p.zijde||(p.soort==='idkaart'&&(heeft('strook')||heeft('qr'))&&!heeft('pasfoto')?'back':'front');
  // de vaste indeling geldt voor de pagina met de gegevens; ligt die ergens in de foto, dan de vlakken daarheen
  const pg=p.pagina&&p.pagina.soort===p.soort&&(ID_VOORSTEL[p.soort]||{})[zijde]&&ID_VOORSTEL[p.soort][zijde].some(x=>x[0]==='strook')?p.pagina.rect:null;
  const naar=r=>{if(!pg)return r.slice();const x=Math.min(1,pg[0]+r[0]*pg[2]),y=Math.min(1,pg[1]+r[1]*pg[3]);return [x,y,Math.min(1-x,r[2]*pg[2]),Math.min(1-y,r[3]*pg[3])];};
  for(const [veld,rect] of (ID_VOORSTEL[p.soort]||{})[zijde]||[]){const g=naar(rect);if(idCopyRechtOk(g)&&!gedekt(veld,g))p.velden.push({veld,rect:g,bron:'geschat',aan:true});}
  // een opengeklapt paspoort: boven de pagina met de gegevens ligt nog een pagina; de ontvanger heeft die zelden nodig
  if(pg&&p.soort==='paspoort'&&pg[1]>=ID_OPEN)p.velden.push({veld:'anderepagina',rect:[0,0,1,pg[1]],bron:'geschat',aan:true});
}
/** Vanaf dit deel van de hoogte boven de pagina met de gegevens telt een paspoort als opengeklapt (twee pagina's). */
const ID_OPEN=.3;
/** QR-code op de pagina (op een nieuwe identiteitskaart staat het burgerservicenummer erin). */
function idCopyQr(p,key,src){
  const zoek=()=>{const img=new Image();img.onload=()=>{
    if(p.veldKey!==key||!window.jsQR)return;
    const c=document.createElement('canvas');c.width=img.naturalWidth;c.height=img.naturalHeight;const x=c.getContext('2d');x.drawImage(img,0,0);
    let code=null;try{code=jsQR(x.getImageData(0,0,c.width,c.height).data,c.width,c.height);}catch(e){code=null;}
    c.width=1;c.height=1;if(!code||!code.location)return;
    const l=code.location,xs=[l.topLeftCorner,l.topRightCorner,l.bottomRightCorner,l.bottomLeftCorner].map(q=>q.x/img.naturalWidth),ys=[l.topLeftCorner,l.topRightCorner,l.bottomRightCorner,l.bottomLeftCorner].map(q=>q.y/img.naturalHeight);
    const m=.02,x0=Math.max(0,Math.min(...xs)-m),y0=Math.max(0,Math.min(...ys)-m),x1=Math.min(1,Math.max(...xs)+m),y1=Math.min(1,Math.max(...ys)+m),rect=[x0,y0,x1-x0,y1-y0];
    if(!idCopyRechtOk(rect)||p.velden.some(v=>v.veld==='qr'&&v.bron==='herkend'))return;
    p.velden=p.velden.filter(v=>v.veld!=='qr');p.velden.push({veld:'qr',rect,bron:'herkend',aan:true});p.checked=false;
    if(p.herken==='klaar')idCopyGeschat(p);
    if(p===idc.pages[idc.page]){idCopyVeldenToon();idCopyPaint();idCopyControls();}
  };img.src=src;};
  if(window.jsQR)zoek();else{const s=document.createElement('script');s.src='vendor/jsQR.js';s.onload=zoek;s.onerror=()=>{};document.head.appendChild(s);}
}
/** De naam van het veld in het zwarte vlak (alleen in het voorbeeld). */
function idCopyPaintNamen(ctx,c,p){
  const kan=ID_VELD();ctx.save();ctx.fillStyle='#ffffff';ctx.textAlign='center';ctx.textBaseline='middle';
  for(const v of (p.velden||[]).filter(v=>v.aan)){
    const w=v.rect[2]*c.width,h=v.rect[3]*c.height,naam=kan[v.veld].naam;let size=Math.min(h*.5,c.width*.035);ctx.font='bold '+size+'px sans-serif';
    const breed=ctx.measureText(naam).width;if(breed>w*.92)size*=w*.92/breed;if(size<7)continue;
    ctx.font='bold '+size+'px sans-serif';ctx.fillText(naam,v.rect[0]*c.width+w/2,v.rect[1]*c.height+h/2);
  }
  ctx.restore();
}
function idCopyVeldTekst(v,moet){
  if(v.bron==='herkend')return moet?t('Herkend. Afschermen, tenzij de ontvanger het wettelijk nodig heeft.'):t('Herkend. Afschermen als de ontvanger het niet nodig heeft.');
  return moet?t('Niet herkend: plek geschat, controleer het vlak. Afschermen, tenzij de ontvanger het wettelijk nodig heeft.'):t('Niet herkend: plek geschat, controleer het vlak. Afschermen als de ontvanger het niet nodig heeft.');
}
/** De lijst velden van deze pagina, met per veld een schakelaar. */
function idCopyVeldenToon(){
  const el=$('#id-velden'),st=$('#id-velden-status'),p=idc.pages[idc.page];if(!el)return;
  el.textContent='';
  if(!p){st.textContent='';return;}
  if(p.herken==='bezig'){uiWachtTekst(st,t('Velden zoeken op deze pagina…'));return;}
  const kan=ID_VELD(),blok=idc.busy||idc.loading;
  p.velden.forEach((v,i)=>{
    const rij=document.createElement('label');rij.className='chk idveld';
    const sw=document.createElement('input');sw.type='checkbox';sw.className='switch';sw.setAttribute('role','switch');sw.checked=!!v.aan;sw.disabled=blok;sw.dataset.veld=v.veld;sw.onchange=()=>idCopyVeld(i,sw.checked);
    const tekst=document.createElement('span'),b=document.createElement('b'),k=document.createElement('small');b.textContent=kan[v.veld].naam;k.textContent=idCopyVeldTekst(v,kan[v.veld].moet);
    tekst.append(b,k);rij.append(sw,tekst);el.append(rij);
  });
  // wat weg moet en hier niet gevonden is: zeg het, zodat Rene zelf kijkt
  const mist=[['bsn',t('Burgerservicenummer: niet gevonden op deze pagina. Staat het er wel, sleep er dan zelf een vlak over.')],['strook',t('Machineleesbare strook: niet gevonden op deze pagina. Staat hij er wel (onderaan), sleep er dan zelf een vlak over.')]];
  for(const [veld,zin] of mist)if(!p.velden.some(v=>v.veld===veld)){const n=document.createElement('p');n.className='note idveld-mist';n.dataset.veld=veld;n.textContent=zin;el.append(n);}
  if(p.rechtop==='vanzelf'){const n=document.createElement('p');n.className='note idveld-recht';n.textContent=t('De pagina is vanzelf rechtop gezet. Klopt dat niet, tik dan op Draaien.');el.prepend(n);}
  if(p.velden.some(v=>v.veld==='anderepagina')){const n=document.createElement('p');n.className='note idveld-open';n.textContent=t('Dit lijkt een opengeklapt paspoort: twee pagina\'s. De bovenste pagina is afgeschermd; zet dat uit als de ontvanger hem nodig heeft.');el.prepend(n);}
  const herkend=p.velden.filter(v=>v.bron==='herkend').length,geschat=p.velden.length-herkend;
  st.textContent=!p.velden.length?(idCopyKanHerkennen()?t('Geen velden herkend. Kies het soort document voor een voorstel, of sleep zelf vlakken over wat weg moet.'):t('Kies het soort document voor een voorstel, of sleep zelf vlakken over wat weg moet.'))
    :geschat?t('{n} herkend, {m} met een geschatte plek. Controleer vooral de geschatte vlakken.',{n:herkend,m:geschat})
    :tn(herkend,'Eén veld herkend en afgeschermd. Controleer de pagina.','{n} velden herkend en afgeschermd. Controleer de pagina.');
}
function idCopyVeld(i,aan){
  const p=idc.pages[idc.page];if(!p||!p.velden[i]||idc.busy||idc.loading){idCopyVeldenToon();return;}
  p.velden[i].aan=!!aan;p.checked=false;idCopyPaint();idCopyControls();
}
/** Het soort document zelf kiezen: geschatte vlakken voor wat niet herkend is. */
function idCopyVoorstelKiezen(){
  if(idc.busy||idc.loading||!idc.meta)return;
  const zet=(soort,zijde)=>{const p=idc.pages[idc.page];p.soort=soort;p.zijde=zijde;idCopyGeschat(p);p.checked=false;idCopyVeldenToon();idCopyPaint();idCopyControls();$('#id-status').textContent=t('Voorstel geplaatst. Controleer of de vlakken precies goed liggen.');};
  openSheet(t('Soort document'),t('Ligt de foto recht en zie je alleen het document? Dan passen de geschatte vlakken het best.'),[
    [t('Paspoort (fotopagina)'),()=>zet('paspoort','front')],[t('ID-kaart, voorkant'),()=>zet('idkaart','front')],[t('ID-kaart, achterkant'),()=>zet('idkaart','back')],[t('Rijbewijs, voorkant'),()=>zet('rijbewijs','front')]]);
}

window.onIdCopyCleanup=function(r){if(r.error)toast(t(r.error));};

function idCopyHasWork(){return idc.busy||idc.loading||idc.pages.length>0;}
window.addEventListener('pagehide',()=>{if(idc.session)idCopyLeave();});
