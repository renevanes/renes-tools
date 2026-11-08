/* ID-gegevens herkennen uit een foto: MRZ en genummerde rijbewijsvelden worden op de telefoon gelezen, de foto gaat nergens
 * heen. De foto blijft als bijlage (voor- of achterzijde) bij het document, versleuteld, en is daar te verwijderen; vanaf
 * een bewaarde foto kan opnieuw herkend worden. Het resultaat is een voorstel: Rene vinkt aan wat hij overneemt. */
const cmh={data:null,current:null,epoch:0,back:null,scanEpoch:0,scanBusy:false};
const CMH_DOC=['documentsoort','document','geldig','afgifte','afgegevenDoor','categorieen','uitgifteland'],CMH_PERSON=['naam','voornamen','geboortedatum','geslacht','nationaliteit','bsn'];
function cmhScanPick(){
  if(!cmOpen()||!cm.edit?.id||cm.edit.type!=='persoon'||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  if(cmi.photoRequest||typeof Android.masterScanPick!=='function')return;
  cmh.scanning=true;cmi.photoRequest=cmRequest('masterScanPick',[cm.edit.id],()=>{},false,()=>cmiPhotoCancel());
}
function cmhPendingText(){
  $('#cmi-photo-pending-text').textContent=cmh.scanning?t('Een foto is gekozen. Ontgrendel Stamgegevens: de app maakt dan een nieuw document met deze foto als bijlage en herkent de gegevens.'):t('Een kaartfoto is gekozen. Ontgrendel Stamgegevens om de foto bij het document op te slaan. Je vorige bewerking is bij het verlaten van de app gewist.');
  $('#cmi-photo-pending-btn').textContent=cmh.scanning?t('Ontgrendelen en gegevens herkennen'):t('Ontgrendelen en kaartfoto opslaan');
}
const cmhPicked=window.onMasterPhotoPicked;window.onMasterPhotoPicked=function(r){cmhPendingText();cmhPicked(r);};
const cmhCancel=cmiPhotoCancel;cmiPhotoCancel=function(){cmh.scanning=false;cmhCancel();};
const cmhAfterUnlock=cmiPhotoAfterUnlock;cmiPhotoAfterUnlock=function(photo){
  cmh.data=null;cmh.back=null;
  if(photo&&!photo.error&&photo.saved){
    if(photo.herkend)cmh.data={doc:photo.id,fields:photo.herkend};
    // Uit "Herkennen uit foto": een nieuw document; na opslaan terug naar de persoon.
    if(photo.scan)cmh.back={id:photo.person,tab:'documenten'};
  }
  cmhAfterUnlock(photo);
};
const cmhAfterList=cmiAfterList;cmiAfterList=function(){
  const id=cmi.reopen,b=cmh.back;
  if(id&&(cmh.data?.doc===id||b)){
    cmi.reopen='';cmh.back=null;
    const note=b?(cmh.data?t('De foto is bewaard. Controleer de gevonden gegevens en kies wat je bewaart.'):t('De foto is bewaard, maar er zijn geen gegevens herkend. Vul zelf in of kies een scherpere foto.')):t('Controleer de gevonden gegevens en kies wat je bewaart.');
    cmEdit(id,cmh.data?'document':'fotos',b&&b.id?{id:b.id,tab:b.tab}:null,note);return;
  }
  cmhAfterList();
};
/* Opnieuw herkennen vanaf de bewaarde kaartfoto's (eerst de achterzijde: daar staat de strook van een ID-kaart), zonder de
 * app te verlaten en dus zonder opnieuw te ontgrendelen. */
function cmhPhotoScan(){
  const r=cm.edit;if(!cmOpen()||!r?.id||r.type!=='identiteit'||cm.mutating||typeof Android.masterPhotoScan!=='function')return;
  if(cm.dirty){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  const sides=(r.fields.documentsoort==='rijbewijs'?['front','back']:['back','front']).filter(x=>r.images?.[x]);if(!sides.length||cmh.scanBusy)return;
  const epoch=++cmh.scanEpoch;cmh.scanBusy=true;cmNote(t('Gegevens herkennen…'));cmhScanButton();
  const done=note=>{if(epoch!==cmh.scanEpoch)return;cmh.scanBusy=false;cmhScanButton();if(note)cmNote(note);};
  const next=()=>{
    const side=sides.shift();
    if(!side){done(t('Geen gegevens herkend. Kies een scherpe foto van de voorkant van je rijbewijs, of van de strook <<< op je ID-kaart of paspoort.'));return;}
    cmRequest('masterPhotoScan',[r.id,r.revision,side],out=>{
      if(epoch!==cmh.scanEpoch)return;if(cm.edit!==r){done('');return;}if(!out.herkend){next();return;}
      done(t('Controleer de gevonden gegevens en kies wat je bewaart.'));cmh.data={doc:r.id,fields:out.herkend};cmhShow();cmTab('document');
      setTimeout(()=>{const p=$('#cmh-panel');if(!p.hidden)p.scrollIntoView({block:'start'});},50);
    },false,()=>done(''));
  };
  next();
}
function cmhScanButton(){const r=cm.edit,b=$('#cmh-photo-scan');b.hidden=typeof Android.masterPhotoScan!=='function';b.disabled=cmh.scanBusy||!r||r.type!=='identiteit'||!r.images?.front&&!r.images?.back;}
const cmhPhotoButtons=cmiEditor;cmiEditor=function(){cmhPhotoButtons();cmhScanButton();};
/* Voorstellen tonen bij het document waar de herkenning bij hoort (nieuw uit een foto, of de zojuist bewaarde kaartfoto). */
const cmhEditor=cmEditor;cmEditor=function(tab){
  cmhEditor(tab);
  const r=cm.edit;
  const d=cmh.data;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();cmhReviewState();
  if(d&&r&&r.type==='identiteit'&&r.id===d.doc)cmhShow();
};
function cmhPersonId(){return cm.edit?.fields?.persoon||'';}
function cmhShow(){
  const epoch=++cmh.epoch,person=cmhPersonId();cmh.current=null;
  const draw=()=>{if(epoch===cmh.epoch&&cm.edit?.type==='identiteit')cmhRender();};
  if(!person){draw();return;}
  cmRequest('masterRead',[person,'masterdata',''],r=>{if(epoch!==cmh.epoch)return;cmh.current=r.record;draw();},false,()=>draw());
}
function cmhValue(key,value){
  if(!value)return '';
  if(['geboortedatum','geldig','afgifte'].includes(key))return fmtDate(value);
  if(key==='geslacht'||key==='documentsoort'){const c=(cmiChoices(key)||[]).find(x=>x[0]===value);return c?c[1]:value;}
  if(key==='document'||key==='bsn')return '•••••'+value.slice(-3);
  return value;
}
function cmhRender(){
  const d=cmh.data,box=$('#cmh-list');box.replaceChildren();if(!d)return;
  const person=cmh.current,groups=[[t('Document'),CMH_DOC,d.fields.document||{},cm.edit.fields],[person?t('Persoon: {naam}',{naam:person.label}):t('Persoon'),person?CMH_PERSON:[],d.fields.persoon||{},person?person.fields:{}]];
  for(const [title,keys,found,now] of groups){
    const rows=keys.filter(k=>found[k]);if(!rows.length)continue;
    box.append(cmData(cmEl('h4',title)));
    for(const key of rows){
      const label=cmEl('label',undefined,'chk cmh-row'),input=cmEl('input');input.type='checkbox';input.dataset.key=key;input.dataset.group=keys===CMH_DOC?'doc':'person';
      // Bij een nieuw document telt de standaardsoort als leeg; een met de tweede keus verbeterd veld staat uit.
      const dflt=keys===CMH_DOC&&!cm.edit.id&&(cmiDefaults('identiteit')[key]||'')===now[key],old=dflt?'':now[key]||'',same=old===found[key];
      const twijfel=(d.fields.twijfel||[]).includes(key);input.checked=key!=='bsn'&&!twijfel&&(!old||same);if(same){input.disabled=true;input.dataset.same='true';}
      const text=cmEl('span');text.append(cmEl('b',t(CM_FIELDS[key]||key)),document.createTextNode(' '),cmData(cmEl('span',cmhValue(key,found[key]))));
      if(old&&!same)text.append(cmEl('small',t('nu: {waarde}',{waarde:cmhValue(key,old)})));else if(same)text.append(cmEl('small',t('staat er al')));
      if(twijfel)text.append(cmEl('small',t('niet zeker gelezen: vergelijk met het document'),'cmi-warn'));
      cmData(text);label.append(input,text);box.append(label);
    }
  }
  if(d.fields.bron==='tekst')box.append(cmEl('p',t('Rijbewijs gelezen uit de gedrukte tekst. Er zijn geen controlecijfers: controleer elk gekozen veld.'),'note cmi-warn'));
  else if(d.fields.zeker===false)box.append(cmEl('p',t('Niet alle controlecijfers kloppen: kijk de gegevens extra goed na.'),'note cmi-warn'));
  else box.append(cmEl('p',t('Namen in de strook kunnen zonder accenten zijn geschreven.'),'hint'));
  $('#cmh-panel').hidden=!box.querySelector('input');cmhReviewState();
}
function cmhReviewState(){
  const on=!!cmh.data&&!$('#cmh-panel').hidden;
  $('#cm-editor').classList.toggle('cmh-review',on);
  $('#cm-save').textContent=on?t('Bewaar gekozen gegevens'):t('Stamgegeven opslaan');
}
function cmhApply(){
  const r=cm.edit,d=cmh.data,person=cmh.current;
  if(!cmOpen()||cm.mutating||r?.type!=='identiteit'||!r.id||!d||d.doc!==r.id)return;
  if(!person||person.id!==r.fields.persoon){cmNote(t('Laad het document opnieuw om de gekoppelde persoon te controleren.'));return;}
  const doc={},per={};
  for(const i of document.querySelectorAll('#cmh-list input:checked:not(:disabled)')){const k=i.dataset.key;if(i.dataset.group==='doc')doc[k]=d.fields.document[k];else per[k]=d.fields.persoon[k];}
  if(!Object.keys(doc).length&&!Object.keys(per).length){cmNote(t('Vink eerst aan welke gegevens je wilt bewaren.'));return;}
  const auto=soort=>t('{soort} {naam}',{soort:cmiDocSoort({soort}),naam:person.label});
  const label=doc.documentsoort&&r.label===auto(r.fields.documentsoort||'')?auto(doc.documentsoort):r.label;
  const input={documentId:r.id,documentRevision:r.revision,personId:person.id,personRevision:person.revision,document:doc,persoon:per,label};
  cmBusy(true);cmRequest('masterScanSave',[JSON.stringify(input)],out=>{
    cmBusy(false);cm.edit=null;cm.dirty=false;cmClearEdited();cmAfterEdit(true);cmNote(out.contextError?t(out.contextError):t('Gekozen document- en persoonsgegevens opgeslagen.'));appIdentityRefresh();
  });
}
const cmhSave=cmSave;cmSave=function(){if(cmh.data&&cm.edit?.id===cmh.data.doc&&!$('#cmh-panel').hidden)cmhApply();else cmhSave();};
function cmhDismiss(){if(cm.mutating)return;cmh.data=null;cmh.epoch++;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();cmhReviewState();}
const cmhAfterEdit=cmAfterEdit;cmAfterEdit=function(saved){cmh.data=null;cmh.epoch++;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();cmhReviewState();cmhAfterEdit(saved);};
const cmhClear=cmClearCentral;cmClearCentral=function(){cmh.data=null;cmh.current=null;cmh.back=null;cmh.epoch++;cmh.scanEpoch++;cmh.scanBusy=false;$('#cmh-list').replaceChildren();$('#cmh-panel').hidden=true;cmhReviewState();cmhClear();};
// Herkennen kan even duren na het ontgrendelen: zeg dat.
const cmhUnlock=cmUnlock;cmUnlock=function(){cmhUnlock();if(cmi.photoRequest&&cm.unlocking)cmNote(t('Bevestig op je telefoon. Daarna wordt de foto verwerkt; dat kan een paar seconden duren.'));};
// Alleen met de nieuwe APK (MrzOcr); anders de knop en uitleg niet tonen.
if(typeof Android.masterScanPick!=='function'){$('#cmh-scan').hidden=true;$('#cmh-scan-hint').hidden=true;}
