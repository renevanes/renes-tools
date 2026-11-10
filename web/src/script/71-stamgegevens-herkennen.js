/* ID-gegevens herkennen uit een foto: MRZ en genummerde rijbewijsvelden worden op de telefoon gelezen, de foto gaat nergens
 * heen. De foto blijft als bijlage (voor- of achterzijde) bij het document, versleuteld, en is daar te verwijderen; vanaf
 * een bewaarde foto kan opnieuw herkend worden. Het resultaat is een voorstel: Rene vinkt aan wat hij overneemt. */
const cmh={data:null,current:null,reveal:false,photoBusy:false,epoch:0,back:null,scanEpoch:0,scanBusy:false,hulp:null};
const CMH_DOC=['documentsoort','document','geldig','afgifte','afgegevenDoor','categorieen','uitgifteland'],CMH_PERSON=['naam','voornamen','geboortedatum','geslacht','nationaliteit','bsn'];
/* Document toevoegen met een foto: de soort bepaalt welke kant eerst (ID-kaart: achterkant met de strook; paspoort en rijbewijs: voorkant). */
function cmhScanPick(person,soort=''){
  if(!cmOpen()||!person||cm.mutating||cm.edit&&cm.dirty){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  if(cmi.photoRequest||typeof Android.masterScanPick!=='function')return;
  cmh.scanning=true;cmi.photoRequest=cmRequest('masterScanPick',[person,soort],()=>{},false,()=>cmiPhotoCancel());
}
function cmhPendingText(){
  $('#cmi-photo-pending-text').textContent=cmh.scanning?t('Een foto is gekozen. Ontgrendel Stamgegevens: de app maakt dan een nieuw document met deze foto als bijlage en herkent de gegevens.'):t('Een kaartfoto is gekozen. Ontgrendel Stamgegevens om de foto bij het document op te slaan. Je vorige bewerking is bij het verlaten van de app gewist.');
  $('#cmi-photo-pending-btn').textContent=cmh.scanning?t('Ontgrendelen en gegevens herkennen'):t('Ontgrendelen en kaartfoto opslaan');
}
const cmhPicked=window.onMasterPhotoPicked;window.onMasterPhotoPicked=function(r){
  // Binnen 2 minuten terug uit de kiezer en nog ontgrendeld: geen kaart "Ontgrendel…", de foto wordt meteen verwerkt.
  if(r&&r.request===cmi.photoRequest&&r.picked&&r.direct&&cmOpen()){cmh.photoBusy=true;cmBusy(true);cmNote(t('Foto verwerken…'));return;}
  cmhPendingText();cmhPicked(r);
};
window.onMasterPhotoDone=function(r){
  if(!r||!r.photo||!cmi.photoRequest||r.request!==cmi.photoRequest)return;
  // Intussen op slot of opnieuw ontgrendeld: de foto is weg; niet blijven wachten (anders reageren de fotoknoppen niet meer).
  if(r.token!==cm.token||!cmOpen()){cmhPhotoReady();cmiPhotoCancel();if(cmOpen())cmNote(t('De foto is niet verwerkt. Kies hem opnieuw.'));return;}
  if(r.photo.error)cmhPhotoReady();
  cmiPhotoAfterUnlock(r.photo);if(!r.photo.error)cmListLoad(undefined,cmhPhotoReady);
};
function cmhPhotoReady(){cmh.photoBusy=false;cmBusy(false);}
const cmhCancel=cmiPhotoCancel;cmiPhotoCancel=function(){cmh.scanning=false;if(!cmh.photoBusy)cmBusy(false);cmhCancel();};
const cmhAfterUnlock=cmiPhotoAfterUnlock;cmiPhotoAfterUnlock=function(photo){
  cmh.data=null;cmh.back=null;cmh.hulp=null;
  if(photo&&!photo.error&&photo.saved){
    if(photo.herkend)cmh.data={doc:photo.id,fields:photo.herkend};
    // Niets gevonden bij "Foto herkennen" (of een rijbewijs dat niet te lezen was): de hulp op het tabblad Foto's.
    // Een gewone kaartfoto (bijv. de voorkant van een ID-kaart, zonder strook) geeft geen melding.
    else if(photo.diag&&(photo.scan||photo.diag.rijbewijs))cmh.hulp={doc:photo.id,diag:photo.diag};
    // Uit "Document toevoegen" met een foto: een nieuw document; na nakijken naar het documentkaartje.
    if(photo.scan)cmh.back={id:photo.person};
  }
  cmhAfterUnlock(photo);
};
const cmhAfterList=cmiAfterList;cmiAfterList=function(){
  const id=cmi.reopen,b=cmh.back;
  if(id&&(cmh.data?.doc===id||b||cmh.photoBusy)){
    cmi.reopen='';cmh.back=null;
    const note=b?(cmh.data?t('De foto is bewaard. Controleer de gevonden gegevens en kies wat je bewaart.'):t('De foto is bewaard, maar er zijn geen gegevens herkend. Vul zelf in of kies een scherpere foto.')):cmh.data?t('Controleer de gevonden gegevens en kies wat je bewaart.'):cmh.hulp?t('Kaartfoto opgeslagen, maar er zijn geen gegevens herkend. Hieronder staat wat je kunt proberen.'):t('Kaartfoto opgeslagen.');
    // Gevonden gegevens: nakijken in het blok Document; niets gevonden: de hulp bij Foto's; anders het documentkaartje.
    if(!cmh.data&&!cmh.hulp){if(cmh.photoBusy)cmhPhotoReady();cmKaartOpen(id,true,note);return;}
    cmEdit(id,cmh.data?'document':'fotos',null,note,cmh.photoBusy?cmhPhotoReady:null);return;
  }
  cmhAfterList();
};
/* Opnieuw herkennen vanaf de bewaarde kaartfoto's (eerst de achterzijde: daar staat de strook van een ID-kaart), zonder de
 * app te verlaten en dus zonder opnieuw te ontgrendelen. */
function cmhPhotoScan(grondig){
  grondig=grondig===true&&typeof Android.masterPhotoRescan==='function';
  const r=cm.edit;if(!cmOpen()||!r?.id||r.type!=='identiteit'||cm.mutating||typeof Android.masterPhotoScan!=='function')return;
  if(cm.dirty){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  const sides=(r.fields.documentsoort==='rijbewijs'||!r.fields.documentsoort?['front','back']:['back','front']).filter(x=>r.images?.[x]);if(!sides.length||cmh.scanBusy)return;
  const epoch=++cmh.scanEpoch,revision=r.revision;cmh.scanBusy=true;cmNote(grondig?t('Grondig zoeken… dit kan een paar minuten duren.'):t('Gegevens herkennen…'));cmhScanButton();cmhHulpRender();
  const done=note=>{if(epoch!==cmh.scanEpoch)return;cmh.scanBusy=false;cmhScanButton();cmhHulpRender();if(note)cmNote(note);};
  let diag=null;
  const next=()=>{
    const side=sides.shift();
    if(!side){
      // Niets gevonden: wat er misging en wat je kunt doen, op het tabblad Foto's.
      cmh.hulp={doc:r.id,diag:diag||{},grondig};cmTab('fotos');done(t('Geen gegevens herkend. Hieronder staat wat je kunt proberen.'));
      setTimeout(()=>{const h=$('#cmh-hulp');if(!h.hidden)h.scrollIntoView({block:'start'});},50);return;
    }
    const call=grondig?['masterPhotoRescan',[r.id,r.revision,side,true]]:['masterPhotoScan',[r.id,r.revision,side]];
    cmRequest(call[0],call[1],out=>{
      if(epoch!==cmh.scanEpoch)return;if(cm.edit!==r){done('');return;}if(cm.dirty||r.revision!==revision){done(t('Sla je wijzigingen eerst op.'));return;}
      if(out.diag&&(!diag||(out.diag.regels||0)>(diag.regels||0)||out.diag.rijbewijs&&!diag.rijbewijs))diag=out.diag;
      if(!out.herkend){next();return;}
      cmh.hulp=null;cmh.data={doc:r.id,fields:out.herkend,grondig};
      done(t('Controleer de gevonden gegevens en kies wat je bewaart.'));cmhShow();cmTab('document');
      setTimeout(()=>{const p=$('#cmh-panel');if(!p.hidden)p.scrollIntoView({block:'start'});},50);
    },false,()=>done(''));
  };
  next();
}
function cmhScanButton(){
  const r=cm.edit,b=$('#cmh-photo-scan');b.hidden=typeof Android.masterPhotoScan!=='function';b.disabled=cmh.scanBusy||!r||r.type!=='identiteit'||!r.images?.front&&!r.images?.back;
  // Tijdens herkennen geen andere foto kiezen (die zou op het lezen moeten wachten).
  for(const x of document.querySelectorAll('#cmi-photos .cmi-sides button,#cmh-mist-acts button')){
    if(cmh.scanBusy){if(!x.disabled){x.disabled=true;x.dataset.cmhBusy='1';}}
    else if(x.dataset.cmhBusy){x.disabled=false;delete x.dataset.cmhBusy;}
  }
}
/* Hulp als herkennen niet lukt: wat er gebeurde (geen tekst, wel tekst maar geen document, rijbewijs zonder leesbare velden),
 * tips die bij de foto passen, en opnieuw proberen, grondiger proberen, een andere foto of zelf invullen. */
function cmhHulpRender(){
  const box=$('#cmh-hulp'),r=cm.edit,h=cmh.hulp;
  if(!h||!r||r.id!==h.doc||r.type!=='identiteit'){box.hidden=true;return;}
  const d=h.diag||{},tips=[];
  $('#cmh-hulp-kop').textContent=t('Herkennen lukte niet');
  $('#cmh-hulp-tekst').textContent=d.ocr===false?t('De tekstherkenning kon niet starten. Probeer het opnieuw; helpt dat niet, start de app dan opnieuw.'):!d.regels?t('Er is geen leesbare tekst op de foto gevonden.'):d.rijbewijs?t('Het rijbewijs is herkend, maar het nummer en de datums konden niet gelezen worden.'):t('Er is wel tekst gelezen, maar geen rijbewijs, ID-kaart of paspoort herkend.');
  if(d.onscherp)tips.push(t('De foto lijkt onscherp: houd de telefoon stil en tik op de kaart om scherp te stellen.'));
  if(d.donker)tips.push(t('De foto is donker: zorg voor meer licht, zonder flits.'));
  if(d.glans)tips.push(t('Er lijkt schittering op de kaart: fotografeer zonder flits en kantel de kaart een beetje.'));
  if(d.rijbewijs&&!d.onderregel)tips.push(t('Staat er op je rijbewijs een regel die begint met D1NLD? Zorg dat die helemaal op de foto staat: daarin staat het nummer met een controlecijfer.'));
  if(!d.regels||!d.rijbewijs&&r.fields.documentsoort==='rijbewijs')tips.push(t('Kies de voorkant van het rijbewijs, recht van boven gefotografeerd.'));
  if(d.tijd)tips.push(t('Het zoeken duurde te lang en is gestopt. Een kleinere, scherpe foto van alleen de kaart gaat sneller.'));
  if(!h.grondig&&typeof Android.masterPhotoRescan==='function')tips.push(t('Probeer Grondiger proberen: dat bewerkt de foto op meer manieren.'));
  $('#cmh-hulp-tips').replaceChildren(...tips.map(x=>cmEl('li',x)));
  const busy=cmh.scanBusy||cm.mutating;
  for(const id of ['#cmh-hulp-opnieuw','#cmh-hulp-grondig','#cmh-hulp-foto'])$(id).disabled=busy;
  $('#cmh-hulp-grondig').hidden=typeof Android.masterPhotoRescan!=='function';
  box.hidden=false;
}
/* Welke velden van een rijbewijs (gelezen uit de tekst) niet gevonden zijn: kort onder de voorstellen, met dezelfde knoppen. */
const CMH_RIJBEWIJS=['document','geldig','afgifte','naam','voornamen','geboortedatum'];
function cmhMist(){
  const d=cmh.data,el=$('#cmh-mist'),acts=$('#cmh-mist-acts');
  const now={...(cmh.current?.fields||{}),...(cm.edit?.fields||{})};
  const mist=d&&d.fields.bron==='tekst'?CMH_RIJBEWIJS.filter(k=>!(d.fields.document||{})[k]&&!(d.fields.persoon||{})[k]&&!now[k]):[];
  el.hidden=acts.hidden=!mist.length;
  el.textContent=mist.length?t('Niet gevonden: {velden}. Probeer het grondiger of met een andere foto, of vul ze zelf in.',{velden:mist.map(k=>t(CM_FIELDS[k]||k).toLowerCase()).join(', ')}):'';
  acts.querySelector('button').hidden=!!d?.grondig||typeof Android.masterPhotoRescan!=='function';
}
/* Een nieuwe foto voor de zijde waar de gegevens staan (rijbewijs en onbekend: voorkant; ID-kaart: achterkant). */
function cmhOtherPhoto(){
  const r=cm.edit;if(!r||r.type!=='identiteit')return;
  const soort=r.fields.documentsoort;cmiPhotoPick(soort&&soort!=='rijbewijs'&&soort!=='paspoort'?'back':'front');
}
function cmhSelf(){cmh.hulp=null;cmhHulpRender();cmTab('document');setTimeout(()=>{const f=$('#cm-field-document');if(f)f.focus();},50);}
const cmhPhotoButtons=cmiEditor;cmiEditor=function(){cmhPhotoButtons();cmhScanButton();};
/* Voorstellen tonen bij het document waar de herkenning bij hoort (nieuw uit een foto, of de zojuist bewaarde kaartfoto). */
const cmhEditor=cmEditor;cmEditor=function(tab){
  cmhEditor(tab);
  const r=cm.edit;
  const d=cmh.data;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();cmhReviewState();
  if(d&&r&&r.type==='identiteit'&&r.id===d.doc)cmhShow();
  cmhHulpRender();
};
function cmhPersonId(){return cm.edit?.fields?.persoon||'';}
function cmhShow(){
  const epoch=++cmh.epoch,person=cmhPersonId(),record=cm.edit;cmh.current=null;cmh.reveal=false;
  const draw=()=>{if(epoch!==cmh.epoch||cm.edit!==record)return;if(cm.dirty){cmhDismiss();cmNote(t('Sla je wijzigingen eerst op.'));return;}cmhRender();};
  if(!person){draw();return;}
  cmRequest('masterRead',[person,'masterdata',''],r=>{if(epoch!==cmh.epoch)return;cmh.current=r.record;draw();},false,()=>draw());
}
function cmhValue(key,value){
  if(!value)return '';
  if(['geboortedatum','geldig','afgifte'].includes(key))return fmtDate(value);
  if(key==='geslacht'||key==='documentsoort'){const c=(cmiChoices(key)||[]).find(x=>x[0]===value);return c?c[1]:value;}
  if((key==='document'||key==='bsn')&&!cmh.reveal)return '•••••'+value.slice(-3);
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
  $('#cmh-source').textContent=d.fields.bron==='tekst'?t('Rijbewijs gelezen uit de gedrukte tekst. Er zijn geen controlecijfers: controleer elk gekozen veld.'):d.fields.zeker===false?t('Niet alle controlecijfers kloppen: kijk de gegevens extra goed na.'):t('Namen in de strook kunnen zonder accenten zijn geschreven.');
  const numbers=$('#cmh-numbers');numbers.textContent=cmh.reveal?t('Nummers verbergen'):t('Nummers tonen');numbers.setAttribute('aria-pressed',String(cmh.reveal));
  $('#cmh-panel').hidden=!box.querySelector('input');cmhMist();cmhReviewState();
}
function cmhToggleNumbers(){
  if(!cmOpen()||cm.mutating||!cmh.data)return;
  const choices=new Map([...document.querySelectorAll('#cmh-list input')].map(i=>[i.dataset.key,i.checked]));
  cmh.reveal=!cmh.reveal;cmhRender();for(const i of document.querySelectorAll('#cmh-list input'))if(choices.has(i.dataset.key))i.checked=choices.get(i.dataset.key);cmTouch();
}
function cmhReviewState(){
  const on=!!cmh.data&&!$('#cmh-panel').hidden;
  $('#cm-editor').classList.toggle('cmh-review',on);
  $('#cm-save').textContent=on?t('Bewaar gekozen gegevens'):t('Opslaan');
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
const cmhAfterEdit=cmAfterEdit;cmAfterEdit=function(saved,savedId){cmh.data=null;cmh.hulp=null;cmh.epoch++;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();cmhReviewState();cmhAfterEdit(saved,savedId);};
const cmhClear=cmClearCentral;cmClearCentral=function(){cmh.data=null;cmh.hulp=null;$('#cmh-hulp').hidden=true;cmh.current=null;cmh.reveal=false;cmh.photoBusy=false;cmh.back=null;cmh.epoch++;cmh.scanEpoch++;cmh.scanBusy=false;$('#cmh-list').replaceChildren();$('#cmh-panel').hidden=true;cmhReviewState();cmhClear();};
// Herkennen kan even duren na het ontgrendelen: zeg dat.
const cmhUnlock=cmUnlock;cmUnlock=function(vanzelf){cmhUnlock(vanzelf);if(cmi.photoRequest&&cm.unlocking&&vanzelf!==true)cmNote(t('Bevestig op je telefoon. Daarna wordt de foto verwerkt; dat kan een paar seconden duren.'));};
