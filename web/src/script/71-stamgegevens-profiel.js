/* Gedeelde identiteit is een expliciete, beperkte native momentopname. Geen opslag in webstore. */
let appIdentity={};
const cmi={photoRequest:'',photoEpoch:0,reopen:'',show:false,labelFor:null,autoLabel:''};
// taal-vast: vaste keuzelabels worden met t() vertaald (ook de waarde voor het Nederlandse formulier, zie cmiFormRelationsLoad)
const CMI_RELATIONS={kind:'Kind',zoon:'Zoon',dochter:'Dochter',ouder:'Ouder',vader:'Vader',moeder:'Moeder',partner:'Partner',expartner:'Ex-partner',broerzus:'Broer of zus',broer:'Broer',zus:'Zus',grootouder:'Grootouder',opa:'Opa',oma:'Oma',kleinkind:'Kleinkind',kleinzoon:'Kleinzoon',kleindochter:'Kleindochter',oomtante:'Oom of tante',neefnicht:'Neef of nicht',schoonouder:'Schoonouder',schoonkind:'Schoonkind',zwagerschoonzus:'Zwager of schoonzus',familie:'Familie',vriend:'Vriend of vriendin',collega:'Collega',buur:'Buurman of buurvrouw',huisgenoot:'Huisgenoot',noodcontact:'Noodcontact',voogd:'Voogd',pupil:'Pupil',pleegouder:'Pleegouder',pleegkind:'Pleegkind',stiefouder:'Stiefouder',stiefkind:'Stiefkind',anders:'Anders'};
// taal-vast: groepsnamen worden met t() vertaald
const CMI_RELATION_GROUPS=[['Gezin',['kind','zoon','dochter','ouder','vader','moeder','partner','expartner','broerzus','broer','zus']],['Familie en schoonfamilie',['grootouder','opa','oma','kleinkind','kleinzoon','kleindochter','oomtante','neefnicht','schoonouder','schoonkind','zwagerschoonzus','familie']],['Stief, pleeg en voogdij',['stiefouder','stiefkind','pleegouder','pleegkind','voogd','pupil']],['Anderen',['vriend','collega','buur','huisgenoot','noodcontact','anders']]];
// Zelfde regels als MasterIdentity.statuses: alleen bij partner en bij ouder en kind
const CMI_OUDER_KIND=['kind','zoon','dochter','ouder','vader','moeder','stiefouder','stiefkind','pleegouder','pleegkind'];
function cmiRelationName(code){return code==='kind'?tc('formulier','Kind'):t(CMI_RELATIONS[code]||code);}
function cmiRelationOptions(){return CMI_RELATION_GROUPS.flatMap(([group,codes])=>codes.map(v=>[v,cmiRelationName(v),t(group)]));}
function cmiStatusChoices(relation){
  if(relation==='partner')return [['',t('Niet ingevuld')],['gehuwd',t('Gehuwd')],['geregistreerd',t('Geregistreerd partnerschap')],['samenwonend',t('Samenwonend')],['latrelatie',t('Latrelatie (niet samenwonend)')]];
  if(CMI_OUDER_KIND.includes(relation))return [['',t('Niet ingevuld')],['gezag',t('Ouder heeft ouderlijk gezag')],['geengezag',t('Ouder heeft geen ouderlijk gezag')]];
  return null;
}
// Een keuzelijst met maar één keuze (Formulier: alleen reistoestemming) laat niets te kiezen; de waarde staat al vast in cmiDefaults
function cmiSkip(key){return cm.edit?.type==='relatie'&&key==='status'&&!cmiStatusChoices(cm.edit.fields.relatie)||cm.edit?.type==='formulier'&&key==='formulier'&&cm.edit.fields.formulier==='reistoestemming'&&cmiChoices(key).length===1;}
function cmiChoices(key){
  if(cm.edit?.type==='formulier'){
    if(key==='formulier')return [['reistoestemming',t('Toestemming reizen met minderjarige')]];
    if(key==='alleen'||key==='bsnInvullen')return [['nee',t('Nee')],['ja',t('Ja')]];
    if(/^begeleider[12]Relatie$/.test(key))return [['',t('Automatisch uit je relaties')],...cmiRelationOptions()];
  }
  if(['alleen','tweedeBegeleider','tweedeGever','delen'].includes(key))return [['',t('Niet ingevuld')],['ja',t('Ja')],['nee',t('Nee')]];
  if(key==='relatie')return [['',t('Kies een relatie')],...cmiRelationOptions()];
  if(key==='status'&&cm.edit?.type==='relatie')return cmiStatusChoices(cm.edit.fields.relatie);
  if(key==='documentsoort')return [['',t('Niet ingevuld')],['identiteitskaart',t('Identiteitskaart')],['paspoort',t('Paspoort')],['rijbewijs',t('Rijbewijs')],['verblijfsdocument',t('Verblijfsdocument')],['anders',t('Anders')]];
  return null;
}
function cmiDefaults(type){return type==='identiteit'?{documentsoort:'identiteitskaart'}:type==='profiel'?{delen:'nee'}:type==='relatie'?{delen:'ja'}:type==='formulier'?{formulier:'reistoestemming',alleen:'nee',bsnInvullen:'nee'}:{};}
/* Vaste formuliergegevens: bij een gekozen persoon het enige bijbehorende document voorstellen en de naam invullen. */
function cmiFormPerson(key){
  const f=cm.edit.fields,docKey=key+'Document';
  if(CM_SCHEMA.formulier.includes(docKey)){
    const docs=cm.records.filter(r=>r.type==='identiteit'&&f[key]&&r.person===f[key]);
    if(!docs.some(r=>r.id===f[docKey]))f[docKey]=docs.length===1?docs[0].id:'';
  }
  if(key==='kind'&&!$('#cm-label').value.trim()){const kid=cm.records.find(r=>r.id===f.kind);if(kid){$('#cm-label').value=t('Reistoestemming {naam}',{naam:kid.label});cm.edit.label=$('#cm-label').value;}}
  const focus=document.activeElement?.id;cmEditorFields();if(focus&&$('#'+focus))$('#'+focus).focus();
}
function appIdentityRefresh(){try{appIdentity=JSON.parse(Android.appIdentity())||{};}catch(_){appIdentity={};}cmiSharedRender();}
window.onAppIdentity=function(){appIdentityRefresh();};
function cmiSharedRender(){
  const self=appIdentity.self,people=appIdentity.people||[],box=$('#cmi-shared');box.replaceChildren();$('#cmi-home').hidden=!self;
  $('#cmi-home-name').textContent=self?t('Hallo {naam}',{naam:self.callname||self.name}):'';$('#cmi-home').setAttribute('aria-label',self?t('Hallo {naam}, naar Stamgegevens',{naam:self.callname||self.name}):t('Stamgegevens'));
  const naam=self?self.callname||self.name:'';$('#cmi-shared-stand').textContent=!self?t('Niets gedeeld'):people.length?tn(people.length,'{naam} en één persoon','{naam} en {n} personen',{naam}):t('Alleen {naam}',{naam});
  if(!self){box.append(cmEl('p',t('Er is geen app-profiel gedeeld.'),'note'));$('#cmi-ai-summary').textContent=t('Er is geen app-profiel gedeeld.');return;}
  box.append(cmData(cmEl('p',t('Dit ben ik: {naam}',{naam:self.name}))));
  for(const text of [self.context,appIdentity.context])if(text)box.append(cmData(cmEl('p',text,'note')));
  for(const p of people){const row=cmData(cmEl('p',t('{naam}: {relaties}',{naam:p.name,relaties:(p.relations||[]).map(cmiRelationName).join(', ')})));box.append(row);for(const text of [p.detail,p.context])if(text)box.append(cmData(cmEl('p',text,'note')));}
  $('#cmi-ai-summary').textContent=t('Profiel: {naam}. Gedeelde personen: {n}.',{naam:self.name,n:fmtNum(people.length)});
}
function cmiNew(type){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;cmNew();if(!cm.edit||cm.edit.id)return;cm.edit.type=type;cm.edit.fields=cmiDefaults(type);if(type==='profiel')cm.edit.label=t('Mijn app-profiel');cmEditor();}
function cmiAfterListForm(){
  if(cmi.newType!=='formulier')return false;cmi.newType='';
  const own=cm.records.filter(r=>r.type==='formulier');if(own.length===1)cmEdit(own[0].id);else{cmiNew('formulier');if(own.length)cmNote(t('Je hebt al vaste gegevens; dit zijn nieuwe, bijvoorbeeld voor een ander kind. Bestaande vind je in de lijst.'));}
  return true;
}
function cmiProfile(){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;const r=cm.records.find(r=>r.type==='profiel');if(r)cmEdit(r.id);else cmiNew('profiel');}
function cmiSelf(){
  if(!cmOpen()||!cm.edit?.id||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  const id=cm.edit.id,profile=cm.records.find(r=>r.type==='profiel');cm.back={id,tab:'relaties'};
  if(profile){const epoch=++cm.editorEpoch;cmRequest('masterRead',[profile.id,'masterdata',''],r=>{if(epoch!==cm.editorEpoch)return;cm.edit=r.record;cm.edit.fields.persoon=id;cmEditor();cmDirty();});}
  else{cmiNew('profiel');cm.edit.fields.persoon=id;cmEditor();cmDirty();}
}
function cmiRelation(){
  if(!cmOpen()||!cm.edit?.id||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  const other=cm.edit.id,profile=cm.records.find(r=>r.type==='profiel');if(!profile){cmNote(t('Wijs jezelf eerst aan bij Mijn app-profiel.'));return;}
  const epoch=++cm.editorEpoch;cmRequest('masterRead',[profile.id,'masterdata',''],r=>{if(epoch!==cm.editorEpoch)return;const me=r.record.fields.persoon;if(me===other){cmNote(t('Kies een andere persoon voor de relatie met jou.'));return;}cmiNew('relatie');cm.edit.fields={vanPersoon:me,naarPersoon:other,delen:'ja'};cm.back={id:other,tab:'relaties'};cmEditor();});
}
/* Relaties in gewone woorden: "{naar} is {relatie} van {van}". Namen zijn de weergavenamen uit het overzicht. */
function cmiPersonName(id){const p=cm.records.find(r=>r.id===id&&r.type==='persoon');return p?p.label:'';}
function cmiRelationText(f,soort){
  const naam=cmiPersonName(f.naarPersoon),ander=cmiPersonName(f.vanPersoon);if(!naam||!ander||!f.relatie)return '';
  const relatie=cmiRelationName(f.relatie).toLowerCase(),status=(cmiStatusChoices(f.relatie)||[]).find(c=>c[0]&&c[0]===f.status);
  if(soort==='label')return (f.relatie==='anders'?t('{naam} en {ander}',{naam,ander}):t('{naam} ({relatie} van {ander})',{naam,relatie,ander})).slice(0,120);
  if(f.relatie==='anders')return t('{naam} heeft een andere relatie met {ander}.',{naam,ander});
  return status?t('{naam} is {relatie} van {ander} ({status}).',{naam,relatie,ander,status:status[1].toLowerCase()}):t('{naam} is {relatie} van {ander}.',{naam,relatie,ander});
}
function cmiRelationChange(key){
  const f=cm.edit.fields;
  if(key==='relatie'){
    if(f.status&&!(cmiStatusChoices(f.relatie)||[]).some(c=>c[0]===f.status))delete f.status;
    const focus=document.activeElement?.id;cmEditorFields();if(focus&&$('#'+focus))$('#'+focus).focus();
  }
  const label=$('#cm-label'),auto=cmiRelationText(f,'label');
  if(auto&&(!label.value.trim()||label.value===cmi.autoLabel)){label.value=auto;cm.edit.label=auto;}
  if(auto)cmi.autoLabel=auto;
  cmiRelationSentence();
}
function cmiRelationSentence(){const zin=cm.edit?.type==='relatie'?cmiRelationText(cm.edit.fields,'zin'):'';$('#cmi-relation-zin').textContent=zin;$('#cmi-relation-zin').hidden=!zin;}
function cmiPersonRelations(){
  const id=cm.edit.id,box=$('#cmi-person-relation-list');box.replaceChildren();
  const rels=cm.records.filter(r=>r.type==='relatie'&&(r.van===id||r.naar===id)).map(r=>({id:r.id,zin:cmiRelationText({vanPersoon:r.van,naarPersoon:r.naar,relatie:r.relatie},'zin')||r.label})).sort((a,b)=>a.zin.localeCompare(b.zin));
  for(const r of rels){const b=cmData(cmEl('button',undefined,'strow')),ic=cmEl('span',undefined,'stic'),chev=cmEl('span',undefined,'ui-icon stchev');b.type='button';ic.innerHTML=uiIcon('share');chev.dataset.icon='chevron-right';chev.setAttribute('aria-hidden','true');b.append(ic,cmEl('span',r.zin,'sttxt'),chev);b.onclick=()=>{cm.back={id,tab:'relaties'};cmEdit(r.id);};box.append(b);}
  if(!rels.length)box.append(cmEl('p',t('Nog geen relaties.'),'note'));
}
/* Vanuit een opgeslagen persoon: "Kind toevoegen" = de gekozen persoon is kind van deze persoon, enzovoort. */
function cmiRelationNew(relatie){
  if(!cmOpen()||!cm.edit?.id||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  const me=cm.edit.id;if(!cm.records.some(r=>r.type==='persoon'&&r.id!==me)){cmNote(t('Voeg eerst de andere persoon toe als stamgegeven (soort Persoon).'));return;}
  cmiNew('relatie');if(!cm.edit||cm.edit.id)return;cm.edit.fields={vanPersoon:me,naarPersoon:'',relatie,delen:'ja'};cm.back={id:me,tab:'relaties'};cmEditor();$('#cm-field-naarPersoon').focus();
}
function cmiEditor(){
  const r=cm.edit;$('#cmi-profile-note').hidden=r.type!=='profiel';$('#cmi-form-note').hidden=r.type!=='formulier';$('#cmi-relation-note').hidden=r.type!=='relatie';$('#cmi-person-actions').hidden=r.type!=='persoon'||!r.id;
  $('#cmi-person-relations').hidden=r.type!=='persoon'||!r.id;if(r.type==='persoon'&&r.id)cmiPersonRelations();
  if(cmi.labelFor!==r){cmi.labelFor=r;cmi.autoLabel=r.type==='relatie'?cmiRelationText(r.fields,'label'):'';}
  cmiRelationSentence();
  cmiPhotoClear();$('#cmi-photos').hidden=r.type!=='identiteit';
  if(r.type==='identiteit')for(const side of ['front','back']){$('#cmi-'+side+'-status').textContent=r.images?.[side]?t('Kaartfoto opgeslagen.'):t('Nog geen kaartfoto opgeslagen.');$('#cmi-'+side+'-delete').disabled=!r.images?.[side];}
  $('#cmi-photo-show').disabled=!r.images?.front&&!r.images?.back;
}
function cmiPhotoClear(){cmi.photoEpoch++;cmi.show=false;for(const side of ['front','back']){$('#cmi-'+side+'-image').removeAttribute('src');$('#cmi-'+side+'-paper').hidden=true;$('#cmi-'+side+'-paper').classList.remove('zoom');$('#cmi-'+side+'-zoom').hidden=true;}$('#cmi-photo-show').hidden=false;}
function cmiPhotoCanChange(){if(!cmOpen()||cm.mutating||!cm.edit?.id||cm.dirty){cmNote(t('Sla je wijzigingen eerst op.'));return false;}return true;}
function cmiPhotoPick(side){
  if(!cmiPhotoCanChange()||cmi.photoRequest)return;
  cmi.photoRequest=cmRequest('masterPhotoPick',[cm.edit.id,cm.edit.revision,side],()=>{},false,()=>cmiPhotoCancel());
}
window.onMasterPhotoPicked=function(r){if(!r||r.request!==cmi.photoRequest)return;if(!r.picked){cmiPhotoCancel();if(r.error)cmNote(t(r.error));return;}$('#cmi-photo-pending').hidden=false;};
function cmiPhotoCancel(){cmi.photoRequest='';$('#cmi-photo-pending').hidden=true;Android.masterPhotoCancel();}
function cmiPhotoAfterUnlock(photo){
  if(!photo)return;cmiPhotoCancel();if(photo.error){cmNote(t(photo.error));return;}if(photo.saved)cmi.reopen=photo.id;
}
function cmiAfterList(){if(cmiAfterListForm())return;if(!cmi.reopen)return;const id=cmi.reopen;cmi.reopen='';cmEdit(id);}
function cmiPhotoShow(){
  if(!cmOpen()||!cm.edit?.id||cm.mutating)return;const record=cm.edit,epoch=++cmi.photoEpoch;cmi.show=true;$('#cmi-photo-show').hidden=true;
  const sides=['front','back'];
  function next(){if(epoch!==cmi.photoEpoch||!cmOpen()||cm.edit!==record)return;const side=sides.shift();if(!side)return;if(!record.images?.[side]){next();return;}
    cmRequest('masterPhotoRead',[record.id,record.revision,side],r=>{if(epoch!==cmi.photoEpoch||!cmOpen()||cm.edit!==record)return;if(typeof r.image==='string'&&r.image.length<=350000&&/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(r.image)){$('#cmi-'+side+'-image').src=r.image;$('#cmi-'+side+'-paper').hidden=false;$('#cmi-'+side+'-zoom').hidden=false;}next();});
  }next();
}
function cmiPhotoZoom(side){if(cmOpen()&&cmi.show)$('#cmi-'+side+'-paper').classList.toggle('zoom');}
function cmiPhotoDelete(side){
  if(!cmiPhotoCanChange()||!cm.edit.images?.[side])return;const r=cm.edit;
  askConfirm('Kaartfoto verwijderen?','De versleutelde kopie van deze zijde wordt verwijderd.','Verwijderen',()=>{if(!cmOpen()||cm.edit!==r||cm.mutating)return;cmBusy(true);cmRequest('masterPhotoDelete',[r.id,r.revision,side],out=>{cmBusy(false);r.revision=out.revision;r.images[side]=false;cmiEditor();cmNote(t('Kaartfoto verwijderd.'));});});
}
function cmiRelationsClear(){cmi.labelFor=null;cmi.autoLabel='';$('#cmi-person-relation-list').replaceChildren();$('#cmi-relation-zin').textContent='';$('#cmi-relation-zin').hidden=true;}
const cmiClearCentral=cmClearCentral;cmClearCentral=function(){cmiPhotoClear();cmiRelationsClear();cmiClearCentral();};
const cmiLock=cmLock;cmLock=function(){cmiPhotoCancel();cmiLock();};
const cmiLeave=cmLeave;cmLeave=function(){cmiPhotoCancel();cmiLeave();};
const cmiCancelEdit=cmCancelEdit;cmCancelEdit=function(){cmiCancelEdit();if(!cm.dirty)cmiPhotoClear();};
const cmiResume=window.onResumeApp;window.onResumeApp=function(){cmiResume();appIdentityRefresh();};
const cmiDash=renderDash;renderDash=function(){cmiDash();appIdentityRefresh();};
const cmiMasterReset=window.onMasterReset;window.onMasterReset=function(){cmiPhotoCancel();appIdentity={};cmiSharedRender();cmiMasterReset();};
appIdentityRefresh();

/* Alleen relaties tussen expliciet gekozen formulierpersonen; nooit gezag of toestemming afleiden. */
const cmiFormRelations={asked:new Set(),pending:new Set(),epoch:0};
function cmiFormRelationKey(child,person){return child.id+'|'+person.id;}
function cmiFormRelationsLoad(){
  const child=fmh.sources.find(s=>s.role==='kind'&&s.type==='persoon');
  fmh.sources=fmh.sources.filter(s=>s.type!=='relatiecontext'||child&&s.child===child.id&&fmh.sources.some(p=>p.id===s.companion));
  if(!cmOpen()||cm.picker||!child)return;
  const roles=new Set(fmHelpFields().flatMap(f=>(f.sources||[]).filter(b=>b.key==='relatie').map(b=>b.role)));
  for(const person of fmh.sources.filter(s=>s.type==='persoon'&&roles.has(s.role))){
    const key=cmiFormRelationKey(child,person);if(cmiFormRelations.asked.has(key))continue;cmiFormRelations.asked.add(key);cmiFormRelations.pending.add(key);const epoch=cmiFormRelations.epoch;
    cmRequest('masterFormRelation',[child.record,person.record,person.role],r=>{if(epoch!==cmiFormRelations.epoch)return;cmiFormRelations.pending.delete(key);
      if(r.relation&&child===fmh.sources.find(s=>s.id===child.id)&&person===fmh.sources.find(s=>s.id===person.id)){
        // taal-vast: waarden voor het Nederlandse overheidsformulier blijven Nederlands
        const value=CMI_RELATIONS[r.relation]?.toLowerCase();
        if(value){fmHelpDiscard();fmh.sources.push({id:'bron-'+(++fmh.seq),type:'relatiecontext',role:person.role,label:t('Relatie tot gekozen kind'),fields:{relatie:value},child:child.id,companion:person.id});}
      }fmHelpRender();
    },false,()=>{if(epoch!==cmiFormRelations.epoch)return;cmiFormRelations.pending.delete(key);cmiFormRelations.asked.delete(key);fmHelpControls();});
  }
}
const cmiHelpRender=fmHelpRender;fmHelpRender=function(){cmiFormRelationsLoad();cmiHelpRender();};
const cmiHelpControls=fmHelpControls;fmHelpControls=function(){cmiHelpControls();if(cmiFormRelations.pending.size)for(const id of ['pff-hulp-kies','pff-hulp-vast','pff-hulp-ai','pff-hulp-overnemen'])$('#'+id).disabled=true;};
const cmiHelpReady=fmHelpReady;fmHelpReady=function(){return !cmiFormRelations.pending.size&&cmiHelpReady();};
const cmiHelpClear=fmHelpClear;fmHelpClear=function(){cmiFormRelations.epoch++;cmiFormRelations.asked.clear();cmiFormRelations.pending.clear();cmiHelpClear();};
const cmiPrivacy=window.onMasterPrivacy;window.onMasterPrivacy=function(r){cmiPrivacy(r);if(!cm.picker&&cmOpen()&&current==='formulieren')fmHelpRender();};

const cmiPrivacyChange=cmPrivacy;cmPrivacy=function(on){cmiFormRelations.epoch++;for(const key of cmiFormRelations.pending)cmiFormRelations.asked.delete(key);cmiFormRelations.pending.clear();cmiPrivacyChange(on);};
