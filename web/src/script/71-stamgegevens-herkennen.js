/* ID-gegevens herkennen uit een foto: de strook met <<< (MRZ) wordt op de telefoon gelezen (MrzOcr), de foto gaat nergens
 * heen. Het resultaat is een voorstel: Rene vinkt aan wat hij overneemt; niets wordt vanzelf opgeslagen. */
const cmh={scanFor:'',data:null,patch:null,current:null,epoch:0};
const CMH_DOC=['documentsoort','document','geldig','uitgifteland'],CMH_PERSON=['naam','voornamen','geboortedatum','geslacht','nationaliteit','bsn'];
function cmhScanPick(){
  if(!cmOpen()||!cm.edit?.id||cm.edit.type!=='persoon'||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  if(cmi.photoRequest||typeof Android.masterScanPick!=='function')return;
  cmh.scanning=true;cmi.photoRequest=cmRequest('masterScanPick',[cm.edit.id],()=>{},false,()=>cmiPhotoCancel());
}
function cmhPendingText(){
  $('#cmi-photo-pending-text').textContent=cmh.scanning?t('Een foto is gekozen. Ontgrendel Stamgegevens om de gegevens te herkennen. De foto zelf wordt niet bewaard.'):t('Een kaartfoto is gekozen. Ontgrendel Stamgegevens om de foto bij het document op te slaan. Je vorige bewerking is bij het verlaten van de app gewist.');
  $('#cmi-photo-pending-btn').textContent=cmh.scanning?t('Ontgrendelen en gegevens herkennen'):t('Ontgrendelen en kaartfoto opslaan');
}
const cmhPicked=window.onMasterPhotoPicked;window.onMasterPhotoPicked=function(r){cmhPendingText();cmhPicked(r);};
const cmhCancel=cmiPhotoCancel;cmiPhotoCancel=function(){cmh.scanning=false;cmhCancel();};
const cmhAfterUnlock=cmiPhotoAfterUnlock;cmiPhotoAfterUnlock=function(photo){
  if(photo&&!photo.error&&photo.herkend)cmh.data={doc:photo.saved?photo.id:'',person:photo.scan?photo.person:'',fields:photo.herkend};
  if(photo&&photo.scan&&!photo.error){cmiPhotoCancel();cmh.scanFor=photo.person;return;}
  cmhAfterUnlock(photo);
};
const cmhAfterList=cmiAfterList;cmiAfterList=function(){
  if(!cmh.scanFor&&cmi.reopen&&cmh.data?.doc===cmi.reopen){const id=cmi.reopen;cmi.reopen='';cmEdit(id,'document',null,t('Kaartfoto opgeslagen. Gegevens herkend uit de strook: kijk ze na en kies wat je overneemt.'));return;}
  if(!cmh.scanFor){cmhAfterList();return;}
  const person=cm.records.find(p=>p.id===cmh.scanFor&&p.type==='persoon');cmh.scanFor='';
  if(!person||!cmh.data){cmh.data=null;cmNote(t('Deze persoon bestaat niet meer.'));return;}
  cmiNew('identiteit');if(!cm.edit||cm.edit.id)return;cm.edit.fields.persoon=person.id;cm.back={id:person.id,tab:'documenten'};cmEditor('document');cmiDocLabel();
};
/* Voorstellen tonen bij het document waar de herkenning bij hoort (nieuw uit een foto, of de zojuist bewaarde kaartfoto). */
const cmhEditor=cmEditor;cmEditor=function(tab){
  cmhEditor(tab);
  const r=cm.edit;
  if(cmh.patch&&r&&r.id&&r.id===cmh.patch.person){
    const patch=cmh.patch;cmh.patch=null;Object.assign(r.fields,patch.fields);cmEditorFields();cmDirty();
    $('#cm-edit-status').textContent=t('Gegevens uit de foto ingevuld. Controleer ze en sla op.');
  }
  const d=cmh.data;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();
  if(d&&r&&r.type==='identiteit'&&(d.doc?r.id===d.doc:!r.id&&r.fields.persoon===d.person))cmhShow();
};
function cmhPersonId(){return cm.edit?.fields?.persoon||cmh.data?.person||'';}
function cmhShow(){
  const epoch=++cmh.epoch,person=cmhPersonId();cmh.current=null;
  const draw=()=>{if(epoch===cmh.epoch&&cm.edit?.type==='identiteit')cmhRender();};
  if(!person){draw();return;}
  cmRequest('masterRead',[person,'masterdata',''],r=>{if(epoch!==cmh.epoch)return;cmh.current=r.record;draw();},false,()=>draw());
}
function cmhValue(key,value){
  if(!value)return '';
  if(['geboortedatum','geldig'].includes(key))return fmtDate(value);
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
      const old=now[key]||'',same=old===found[key];input.checked=key!=='bsn'&&(!old||same);if(same)input.disabled=true;
      const text=cmEl('span');text.append(cmEl('b',t(CM_FIELDS[key]||key)),document.createTextNode(' '),cmData(cmEl('span',cmhValue(key,found[key]))));
      if(old&&!same)text.append(cmEl('small',t('nu: {waarde}',{waarde:cmhValue(key,old)})));else if(same)text.append(cmEl('small',t('staat er al')));
      cmData(text);label.append(input,text);box.append(label);
    }
  }
  if(d.fields.zeker===false)box.append(cmEl('p',t('Niet alle controlecijfers kloppen: kijk de gegevens extra goed na.'),'note cmi-warn'));
  $('#cmh-panel').hidden=!box.querySelector('input');
}
function cmhApply(){
  if(!cmOpen()||!cm.edit||cm.edit.type!=='identiteit'||!cmh.data)return;
  const d=cmh.data,doc={},per={};
  for(const i of document.querySelectorAll('#cmh-list input:checked:not(:disabled)')){const k=i.dataset.key;if(i.dataset.group==='doc')doc[k]=d.fields.document[k];else per[k]=d.fields.persoon[k];}
  Object.assign(cm.edit.fields,doc);
  const person=cmh.current;cmh.data=null;cmh.epoch++;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();
  if(person&&Object.keys(per).length){cmh.patch={person:person.id,fields:per};if(cm.back?.id!==person.id)cm.back={id:person.id,tab:'naam'};}
  if(!cm.edit.id&&!$('#cm-label').value.trim())cmiDocLabel();
  cmEditorFields();cmDirty();
  $('#cm-edit-status').textContent=Object.keys(per).length?t('Overgenomen. Sla het document op; daarna open je {naam} met de gekozen gegevens om te controleren.',{naam:person.label}):t('Overgenomen. Controleer en sla op.');
}
function cmhDismiss(){cmh.data=null;cmh.epoch++;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();}
const cmhAfterEdit=cmAfterEdit;cmAfterEdit=function(saved){if(!saved)cmh.patch=null;cmh.data=null;cmh.epoch++;$('#cmh-panel').hidden=true;$('#cmh-list').replaceChildren();cmhAfterEdit(saved);};
const cmhClear=cmClearCentral;cmClearCentral=function(){cmh.data=null;cmh.patch=null;cmh.current=null;cmh.scanFor='';cmh.epoch++;$('#cmh-list').replaceChildren();$('#cmh-panel').hidden=true;cmhClear();};
// Alleen met de nieuwe APK (MrzOcr); anders de knop niet tonen.
if(typeof Android.masterScanPick!=='function')$('#cmh-scan').hidden=true;
