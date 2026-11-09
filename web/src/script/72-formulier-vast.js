/* Snel invullen: vaste formuliergegevens uit Stamgegevens + per reis alleen reisdagen en bestemming.
 * De vaste gegevens worden native opgebouwd uit de actuele stamgegevens (MasterFormProfile); hier blijft
 * alles vluchtig, net als bij handmatig overnemen. Bij vergrendelen wordt de invoer gewist. */
const fmq={open:false,records:[],busy:false,focus:null,ask:false,epoch:0};
// Formulieren met vaste gegevens en welke reisvelden je per keer invult (een veld kan op meer plekken komen).
const FM_VAST={reistoestemming:{van:'van',tot:'tot',land:'land',plaats:'bestemming-plaats',datum:['begeleider1-datum','begeleider2-datum'],accommodatie:'accommodatie',postcode:'bestemming-postcode',telefoon:'bestemming-telefoon',reisgegevens:'reisgegevens'}};
const FMQ_REIS=['van','tot','land','plaats','datum','accommodatie','postcode','telefoon','reisgegevens'];
function fmqNote(text){$('#fmq-status').textContent=text;}
// Met vaste gegevens is "Nieuwe reis invullen" de hoofdknop en staat "Zelf invullen" als rij eronder.
function fmqKaart(){
  const vast=!!FM_VAST[fm.id]&&typeof Android.masterFormProfile==='function';
  $('#fmq-start').hidden=!vast;$('#pff-start-knop').hidden=vast;$('#pff-start-rijen').hidden=!vast;$('#fmq-uitleg').hidden=!vast;
  $('#fmq-start').disabled=fm.busy;$('#fmq-instellen').disabled=fm.busy;
}
function fmqRenderState(){
  if(!fmq.open)return;const open=cmOpen();
  $('#fmq-ontgrendel').hidden=open;$('#fmq-annuleer-dicht').hidden=open;$('#fmq-open').hidden=!open;
}
function fmqClearInput(){
  fmq.records=[];$('#fmq-profiel').replaceChildren();$('#fmq-id').checked=false;$('#fmq-geen').hidden=true;fmqNote('');
  for(const key of FMQ_REIS)$('#fmq-'+key).value='';$('#fmq-datum').value=rgDayKey(Date.now());$('#fmq .fmq-meer').open=false;
  document.querySelectorAll('#fmq [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
}
function fmqOpen(){
  if(fmq.open||fm.busy||cm.picker||!FM_VAST[fm.id])return;
  const open=()=>{
    fmq.open=true;fmq.ask=true;fmq.epoch++;fmq.focus=document.activeElement;fmqClearInput();
    $('#fmq').hidden=false;$('#s-'+current).inert=true;fmqRenderState();cmPrivacy(true);$('#fmq-titel').focus();
  };
  if(fmDirty())askConfirm('Nieuwe reis invullen?','Je huidige invoer in dit formulier wordt vervangen, ook het opgeslagen concept. Een al gemaakte PDF blijft onder PDF → Gemaakt staan.','Doorgaan',open);else open();
}
function fmqClose(){
  if(!fmq.open)return;fmq.open=false;fmq.busy=false;fmq.ask=false;fmq.epoch++;fmqClearInput();$('#fmq').hidden=true;$('#s-formulieren').inert=false;
  cmPrivacy(current==='formulieren'&&!!fm.masterSensitive);
  if(fmq.focus&&fmq.focus.isConnected)fmq.focus.focus();fmq.focus=null;
}
function fmqLoad(){
  if(!fmq.open||!cmOpen())return;fmqRenderState();
  cmRequest('masterList',['formulieren','vast'],r=>{
    if(!fmq.open)return;fmq.records=r.records.filter(x=>x.type==='formulier').sort((a,b)=>a.label.localeCompare(b.label));
    const select=$('#fmq-profiel'),old=select.value;select.replaceChildren();
    for(const rec of fmq.records){const o=cmData(cmEl('option',rec.label));o.value=rec.id;select.append(o);}
    if(fmq.records.some(x=>x.id===old))select.value=old;
    const none=!fmq.records.length;$('#fmq-geen').hidden=!none;select.hidden=none;$('label[for="fmq-profiel"]').hidden=none;$('#fmq-ga').disabled=none;
    if(!$('#fmq-van').value)(none?$('#fmq-geen button'):$('#fmq-van')).focus();
  });
}
function fmqGeldig(){
  for(const key of ['van','tot','land']){const input=$('#fmq-'+key);if(!input.value.trim()){input.focus();input.reportValidity();fmqNote(t('Vul de eerste en laatste reisdag en het land in.'));return false;}}
  if($('#fmq-van').value>$('#fmq-tot').value){$('#fmq-tot').focus();fmqNote(t('De laatste reisdag moet op of na de eerste reisdag vallen.'));return false;}
  return true;
}
function fmqGo(){
  if(!fmq.open||fmq.busy||!cmOpen())return;
  const record=fmq.records.find(r=>r.id===$('#fmq-profiel').value);if(!record){fmqNote(t('Kies eerst vaste gegevens.'));return;}
  if(!fmqGeldig())return;
  if(typeof Android.masterFormProfile!=='function'){fmqNote(t('Installeer de nieuwste APK om formulieren in te vullen.'));return;}
  const reis={};for(const key of FMQ_REIS)reis[key]=$('#fmq-'+key).value.trim();
  const consent=$('#fmq-id').checked,id=fm.id,epoch=fmq.epoch;fmq.busy=true;$('#fmq-ga').disabled=true;fmqNote(t('Formulier invullen…'));
  // Eerst de geldigheid van de reisdocumenten (na het sluiten van dit venster is de privésessie even niet bruikbaar).
  if(typeof Android.masterFormExpiry==='function')cmRequest('masterFormExpiry',[record.id,record.revision,reis.van,reis.tot,consent],r=>{if(epoch===fmq.epoch)fmqFill(record,reis,consent,id,epoch,r.warnings||[]);},false,()=>{if(epoch===fmq.epoch)fmqFill(record,reis,consent,id,epoch,[]);});
  else fmqFill(record,reis,consent,id,epoch,[]);
}
function fmqFill(record,reis,consent,id,epoch,warnings){
  if(!fmq.open||!cmOpen()){fmq.busy=false;return;}
  cmRequest('masterFormProfile',[record.id,record.revision,consent],r=>{
    if(epoch!==fmq.epoch)return;fmq.busy=false;$('#fmq-ga').disabled=false;if(!fmq.open||fm.id!==id||fm.busy){fmqNote('');return;}
    // Eerst het oude concept wissen (dat geeft ook de vorige binding vrij), daarna deze vrijgave binden.
    fmInit(id);fmConceptWeg(); // het oude concept hoort niet meer bij deze nieuwe reis
    if(!Android.masterApplied(cm.token,r.grant)){fmqNote(t('Ontgrendel de stamgegevens opnieuw'));return;}
    const known=new Set(fmDef().sections.flatMap(s=>s.fields.map(f=>f.id)));
    for(const [key,value] of Object.entries(r.values||{}))if(known.has(key)&&typeof value==='string'){fm.values[key]=value;fm.stam.add(key);}
    for(const [role,code] of Object.entries(r.relations||{})){const value=CMI_RELATIONS[code]?.toLowerCase();if(value&&known.has(role+'-relatie')){fm.values[role+'-relatie']=value;fm.stam.add(role+'-relatie');}} // taal-vast: veld-id en waarde voor het Nederlandse overheidsformulier
    for(const [key,targets] of Object.entries(FM_VAST[id]))for(const target of [].concat(targets))if(reis[key]&&fmqInBeeld(target))fm.values[target]=reis[key];
    fm.masterSensitive=true;
    const kind=(fm.values['kind-voornamen']||'').split(/\s+/)[0];
    $('#pff-naam').value=kind?t('Reistoestemming {naam} {datum}',{naam:kind,datum:reis.van}):t('{titel} {datum}',{titel:fmDef().title,datum:reis.van});
    fm.naamStam=!!kind; // de naam van het kind komt uit Stamgegevens: niet in het concept
    fmqClose();fm.step=fmSecties().length;fm.view='stap';fmWijzig();fmRender();
    if(fmGeldig(true)){
      $('#pff-titel').focus();
      $('#pff-melding').textContent=r.omitted?t('Ingevuld. Documentnummers en BSN zijn weggelaten; vul ze zelf in of kies Nieuwe reis invullen opnieuw met het vinkje aan.'):t('Ingevuld met je vaste gegevens. Controleer beide pagina’s en maak de PDF.');
    }else $('#pff-melding').textContent=t('Ingevuld met je vaste gegevens, maar er ontbreekt nog iets: {melding}',{melding:$('#pff-melding').textContent});
    const handtekening=fmqHandtekening();if(handtekening)$('#pff-melding').textContent=t('{melding} {handtekening}',{melding:$('#pff-melding').textContent,handtekening});
    fmqVerloop(warnings);
  },false,e=>{if(epoch!==fmq.epoch)return;fmq.busy=false;$('#fmq-ga').disabled=!fmq.records.length;fmqNote(e&&e.error?t(e.error):'');});
}
/* Waar je bewaarde handtekening komt, of waarom dat niet kan: de PDF zelf toont het pas op die pagina. */
function fmqHandtekening(){
  for(const s of fmSecties())for(const f of fmVelden(s))if(fmHandtekeningVeld(f)&&fm.values[f.id]==='ja'){
    try{fmHandtekening();}catch(e){return e.message;}
    return t('Je bewaarde handtekening staat bij {wie}, op pagina {pagina}.',{wie:s.title,pagina:f.pdf[0].page+1});
  }
  return '';
}
/* Alleen in een stap die er met deze vaste gegevens is (geen datum voor een tweede begeleider die er niet is). */
function fmqInBeeld(target){return fmSecties().some(s=>fmVelden(s).some(f=>f.id===target));}
/* Verlopen reisdocumenten: vóór de laatste reisdag, of binnen zes maanden daarna (veel landen eisen dat). */
function fmqVerloop(warnings){
  const box=$('#pff-verloop');
  // De datum staat er alleen bij als je identiteitsgegevens hebt vrijgegeven (vinkje in het venster).
  const lines=warnings.map(w=>{const datum=w.geldig?fmtDate(w.geldig):'';
    if(w.niveau==='verlopen')return datum?t('{document} is op {datum} verlopen. Ondertekenen gaat met een geldig identiteitsbewijs.',{document:w.label,datum}):t('{document} is vóór de eerste reisdag verlopen. Ondertekenen gaat met een geldig identiteitsbewijs.',{document:w.label});
    if(w.niveau==='voor')return datum?t('{document} is op {datum} niet meer geldig, vóór het einde van de reis. Vraag op tijd een nieuw document aan.',{document:w.label,datum}):t('{document} is vóór het einde van de reis niet meer geldig. Vraag op tijd een nieuw document aan.',{document:w.label});
    return datum?t('{document} verloopt op {datum}, binnen zes maanden na de reis. Veel landen buiten Europa vragen langer geldigheid; kijk wat je bestemming eist.',{document:w.label,datum}):t('{document} verloopt binnen zes maanden na de reis. Veel landen buiten Europa vragen langer geldigheid; kijk wat je bestemming eist.',{document:w.label});});
  box.textContent=lines.join('\n');box.hidden=!lines.length;
}
function fmqSetup(){
  if(fm.busy)return;fmqClose();cmi.newType='formulier';jumpTo('masterdata');
}
function fmqZelf(){fmqClose();fmStart();}
// Koppelingen met de bestaande stamgegevens- en formulierlogica.
const fmqBaseInit=fmInit;fmInit=function(id){const box=$('#pff-verloop');if(box){box.hidden=true;box.textContent='';}fmqBaseInit(id);};
const fmqBaseRender=fmRender;fmRender=function(){fmqBaseRender();fmqKaart();};
const fmqBaseLocked=cmLockedUI;cmLockedUI=function(){fmqBaseLocked();if(fmq.open){fmq.busy=false;fmqClearInput();fmqRenderState();}};
const fmqBasePrivacy=window.onMasterPrivacy;window.onMasterPrivacy=function(r){fmqBasePrivacy(r);if(fmq.open&&r&&r.view===cm.view){fmqRenderState();if(cmOpen())fmqLoad();else if(fmq.ask&&cmState().secure)cmUnlock();fmq.ask=false;}};
const fmqBaseNavigation=cmNavigation;cmNavigation=function(name){if(fmq.open&&name!==current)fmqClose();if(current==='formulieren'&&name!=='formulieren')fmConceptVanzelf();fmqBaseNavigation(name);};
$('#fmq').addEventListener('keydown',e=>{
  if(e.key==='Escape'){e.preventDefault();e.stopPropagation();fmqClose();}
  if(e.key==='Tab'){const nodes=[...$('#fmq').querySelectorAll('button,input,select,summary')].filter(n=>!n.disabled&&n.getClientRects().length);if(!nodes.length)return;const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}
});
document.addEventListener('input',e=>{if(fmq.open&&e.target.closest('#fmq'))cmTouch();});
