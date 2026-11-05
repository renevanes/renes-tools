/* Stamgegevens: uitsluitend vluchtig schermgeheugen; opslag, toegang en audit blijven native. */
const cm={token:'',seq:0,pending:new Map(),records:[],edit:null,dirty:false,events:[],before:0,picker:null,view:'',generation:0,privateReady:false,unlocking:false,resetting:false,mutating:false,editorEpoch:0};
// taal-vast: veldnamen worden bij het tekenen met t() vertaald
const CM_TYPES={persoon:'Persoon',organisatie:'Organisatie',adres:'Adres',identiteit:'Identiteitsdocument',reis:'Reis',relatie:'Relatie',profiel:'App-profiel'};
// taal-vast: veldnamen worden bij het tekenen met t() vertaald
const CM_FIELDS={roepnaam:'Roepnaam',context:'Context voor app en AI',vanPersoon:'Eerste persoon',naarPersoon:'Tweede persoon',relatie:'Relatie van de tweede persoon',toelichting:'Toelichting bij de relatie',delen:'Delen met app en lokale AI?',uitgifteland:'Land van afgifte',afgegevenDoor:'Afgegeven door',front:'Voorzijde',back:'Achterzijde',naam:'Achternaam of naam',voornamen:'Voornamen',initialen:'Initialen',geboortedatum:'Geboortedatum',geboorteplaats:'Geboorteplaats',nationaliteit:'Nationaliteit',bsn:'BSN',adres:'Straat en huisnummer',postcode:'Postcode',plaats:'Plaats',land:'Land',telefoon:'Telefoonnummer',email:'E-mailadres',registratienummer:'Registratienummer',persoon:'Gekoppelde persoon',documentsoort:'Documentsoort',document:'Documentnummer',geldig:'Geldig tot',afgifte:'Afgiftedatum',afgifteplaats:'Plaats van afgifte',ontvanger:'Ontvanger',instelling:'Instelling',accommodatie:'Naam familie of accommodatie',label:'Naam in het overzicht',van:'Eerste reisdag',tot:'Laatste reisdag',alleen:'Reist het kind alleen?',tweedeBegeleider:'Een tweede begeleider invullen?',tweedeGever:'Een tweede toestemminggever invullen?',reisgegevens:'Reisgegevens (bijvoorbeeld vluchtnummer)'};
const CM_SCHEMA={persoon:['naam','voornamen','roepnaam','initialen','geboortedatum','geboorteplaats','nationaliteit','bsn','adres','postcode','plaats','land','telefoon','email','context'],organisatie:['naam','registratienummer','adres','postcode','plaats','land','telefoon','email'],adres:['naam','adres','postcode','plaats','land','telefoon'],identiteit:['persoon','documentsoort','document','geldig','afgifte','afgifteplaats','uitgifteland','afgegevenDoor'],profiel:['persoon','delen','context'],relatie:['vanPersoon','naarPersoon','relatie','toelichting','delen'],reis:['van','tot','alleen','tweedeBegeleider','tweedeGever','accommodatie','postcode','plaats','land','telefoon','reisgegevens']};
const CM_SECRET=new Set(['bsn','document','geldig','afgifte']);
function cmState(){try{return JSON.parse(Android.masterState());}catch(_){return {open:false};}}
function cmOpen(){return !!cm.token&&cmState().open&&cm.privateReady;}
function cmNote(message){$('#cm-status').textContent=message;$('#cm-pick-status').textContent=message;}
function cmEl(tag,text,cls){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;}
function cmData(e){e.setAttribute('translate','no');return e;}
function cmRequest(method,args,done,publicRequest=false,fail=null){
  const request='cm-'+(++cm.seq),token=cm.token;
  cm.pending.set(request,{done,token,publicRequest,fail,mutation:['masterSave','masterDelete','masterPhotoDelete'].includes(method)});
  Android[method](...(publicRequest?args:[token,...args]),request);return request;
}
window.onMasterReply=function(r){
  if(!r)return;const p=cm.pending.get(r.request);if(!p)return;cm.pending.delete(r.request);
  if(!p.publicRequest&&(p.token!==cm.token||r.token!==cm.token))return;
  if(r.error){if(p.fail)p.fail(r);if(p.mutation)cmBusy(false);if(r.locked)cmLockedUI();cmNote(t(r.error));if(p.publicRequest){cm.unlocking=false;cm.resetting=false;}return;}
  if(!p.publicRequest&&!cmOpen())return;
  p.done(r);
};
function cmPrivacy(on){
  const view=on?'cm-view-'+(++cm.seq):'';cm.view=view;cm.privateReady=false;
  Android.masterView(on,view);
}
window.onMasterPrivacy=function(r){
  if(!r||r.view!==cm.view)return;cm.generation=r.generation;cm.privateReady=true;
  cmRenderState();Android.masterSafeFrame(cm.view,cm.generation);
};
function cmRenderState(){
  const open=cmOpen();$('#cm-locked').hidden=open;$('#cm-open').hidden=!open;
  $('#cm-pick-unlock').hidden=open;$('#cm-pick-search').hidden=!open;
}
function enterMasterdata(){cmClearCentral();cmPrivacy(true);cmNote(t('Niet-bewaarde wijzigingen en overgenomen concepten worden bij vergrendelen gewist.'));}
function cmLeave(){cmClearCentral();Android.masterLock();cmPrivacy(false);}
function cmClearCentral(){
  cm.records=[];cm.events=[];cm.edit=null;cm.dirty=false;cm.before=0;cm.editorEpoch++;cmBusy(false);
  ['cm-list','cm-fields','cm-audit','cm-pick-list','cm-pick-fields'].forEach(id=>$('#'+id).replaceChildren());
  ['cm-label','cm-search','cm-log-search','cm-pick-search','cm-reset-word'].forEach(id=>$('#'+id).value='');
  $('#cm-show-secret').checked=false;$('#cm-editor').hidden=true;$('#cm-reset').disabled=true;
  document.querySelectorAll('#s-masterdata [data-ui-edited],#cm-picker [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
}
function cmClearFormDOM(){
  $('#pff-velden').replaceChildren();$('#pff-samenvatting').replaceChildren();$('#pff-voorbeeld').removeAttribute('src');
  const canvas=$('#pff-laag');canvas.getContext('2d').clearRect(0,0,canvas.width,canvas.height);
  $('#pff-papier').hidden=true;$('#pff-controle').hidden=true;$('#pff-bestand').textContent='';
  document.querySelectorAll('#s-formulieren [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
}
function cmLockedUI(){
  cm.token='';cmClearCentral();for(const [id,p] of cm.pending)if(!p.publicRequest)cm.pending.delete(id);
  if(cm.picker){cm.picker.record=null;cm.picker.fields={};}
  if(fm.masterSensitive){cmClearFormDOM();fm.busy=false;fm.exportToken='';fm.masterSensitive=false;fmInit(fm.id);if(current==='formulieren')fmRender();}
  if(idc.masterSensitive){idc.masterSensitive=false;idc.busy=false;idc.loading=false;idCopyReset(current==='idcopy');idCopyControls();}
  cmRenderState();cmNote(t('Stamgegevens zijn vergrendeld. Niet-bewaarde gegevens zijn gewist.'));
}
window.onMasterLocked=function(generation){cm.generation=generation;cmLockedUI();Android.masterSafeFrame(cm.view,generation);};
window.onMasterReset=function(){cm.unlocking=false;cm.resetting=false;cm.pending.clear();cmLockedUI();cmNote(t('Stamgegevens en activiteiten zijn gewist.'));};
function cmUnlock(){
  if(cm.unlocking||cm.resetting)return;if(!cmState().secure){cmNote(t('Stel eerst een schermvergrendeling in op je telefoon'));return;}cm.unlocking=true;cmNote(t('Bevestig op je telefoon.'));
  cmRequest('masterUnlock',[],r=>{cm.unlocking=false;if(!r.opened||!cmState().open)return;cm.token=r.token;cmRenderState();cmNote('');appIdentityRefresh();cmiPhotoAfterUnlock(r.photo);if(cm.picker)cmPickerLoad();else cmListOpen();},true);
}
function cmLock(){Android.masterLock();cmLockedUI();}
function cmTouch(){if(cmOpen())Android.masterTouch(cm.token);}
function cmDirty(){cm.dirty=true;cmTouch();}
function cmListOpen(){
  if(!cmOpen()||cm.mutating)return;cm.editorEpoch++;$('#cm-list-card').hidden=false;$('#cm-audit-card').hidden=true;$('#cm-editor').hidden=true;
  cmRequest('masterList',['masterdata',''],r=>{cm.records=r.records;cmRenderList();cmiAfterList();});
}
function cmRenderList(){
  const box=$('#cm-list');box.replaceChildren();if(!cmOpen())return;const q=$('#cm-search').value.toLowerCase();
  cm.records.filter(r=>r.label.toLowerCase().includes(q)).sort((a,b)=>a.label.localeCompare(b.label)).forEach(r=>{
    const row=cmEl('div',undefined,'cm-entry'),b=cmData(cmEl('button',r.label,'big ghost'));b.onclick=()=>cmEdit(r.id);
    row.append(b,cmEl('small',t(CM_TYPES[r.type])));box.append(row);
  });if(!box.children.length)box.append(cmEl('p',t('Nog geen passende stamgegevens.'),'note'));
}
function cmNew(){if(!cmOpen()||cm.mutating)return;cm.editorEpoch++;cm.edit={type:'persoon',label:'',fields:{},revision:0};cmEditor();}
function cmEdit(id){if(cm.mutating)return;const epoch=++cm.editorEpoch;cmRequest('masterRead',[id,'masterdata',''],r=>{if(epoch!==cm.editorEpoch)return;cm.edit=r.record;cmEditor();});}
function cmType(type){if(!cm.edit||cm.edit.id)return;cm.edit.type=type;cm.edit.fields=cmiDefaults(type);cm.dirty=true;cmEditorFields();}
function cmEditor(){
  $('#cm-editor').hidden=false;$('#cm-audit-card').hidden=true;$('#cm-kind').value=cm.edit.type;$('#cm-kind').disabled=!!cm.edit.id;
  $('#cm-label').value=cm.edit.label;$('#cm-show-secret').checked=false;$('#cm-delete').hidden=!cm.edit.id;cm.dirty=false;cmEditorFields();$('#cm-label').focus();
}
function cmEditorFields(){
  const box=$('#cm-fields');box.replaceChildren();
  for(const key of CM_SCHEMA[cm.edit.type]){
    const label=cmEl('label',t(cm.edit.type==='profiel'&&key==='persoon'?'Dit ben ik':CM_FIELDS[key]),'f');label.htmlFor='cm-field-'+key;
    const person=['persoon','vanPersoon','naarPersoon'].includes(key),choices=cmiChoices(key),area=['context','toelichting'].includes(key);
    const input=cmData(cmEl(person||choices?'select':area?'textarea':'input',undefined,'search'));input.id=label.htmlFor;input.name=key;
    if(person){
      const empty=cmEl('option',t('Kies een persoon'));empty.value='';input.append(empty);
      cm.records.filter(r=>r.type==='persoon').forEach(r=>{const o=cmData(cmEl('option',r.label));o.value=r.id;input.append(o);});
    }else if(choices){
      for(const [value,text] of choices){const o=cmEl('option',text);o.value=value;input.append(o);}
      const old=cm.edit.fields[key];if(old&&!choices.some(c=>c[0]===old)){const o=cmData(cmEl('option',old));o.value=old;input.append(o);}
    }else{input.autocomplete='off';input.maxLength=key==='bsn'?9:240;if(!area)input.type=CM_SECRET.has(key)?'password':['geboortedatum','van','tot'].includes(key)?'date':key==='email'?'email':key==='telefoon'?'tel':'text';else input.rows=3;if(key==='bsn')input.inputMode='numeric';}
    input.value=cm.edit.fields[key]||'';input.addEventListener(person||choices?'change':'input',()=>{cm.edit.fields[key]=input.value;cmDirty();});
    box.append(label,input);if(['geldig','afgifte'].includes(key))box.append(cmEl('small',t('Notatie: JJJJ-MM-DD')));
  }cmiEditor();
}
function cmShowSecret(show){CM_SECRET.forEach(key=>{const input=$('#cm-field-'+key);if(input)input.type=show?'text':'password';});cmTouch();}
function cmBusy(busy){cm.mutating=busy;$('#cm-save').disabled=busy;$('#cm-delete').disabled=busy;document.querySelectorAll('#cm-fields input,#cm-fields select,#cm-fields textarea,#cmi-photos button,#cmi-person-actions button').forEach(e=>e.disabled=busy);$('#cm-label').disabled=busy;$('#cm-kind').disabled=busy||!!(cm.edit&&cm.edit.id);}
function cmSave(){if(!cmOpen()||!cm.edit||cm.mutating)return;cm.edit.label=$('#cm-label').value;cmBusy(true);cmRequest('masterSave',[JSON.stringify(cm.edit)],r=>{cmBusy(false);cm.edit=null;cm.dirty=false;cmClearEdited();cmListOpen();cmNote(r.contextError?t(r.contextError):t('Stamgegeven opgeslagen.'));appIdentityRefresh();});}
function cmClearEdited(){document.querySelectorAll('#s-masterdata [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);}
function cmCancelEdit(){if(cm.mutating)return;cm.editorEpoch++;if(cm.dirty){askConfirm('Bewerking sluiten?','Niet-bewaarde wijzigingen gaan verloren.','Sluiten',()=>{cm.edit=null;cm.dirty=false;cmClearEdited();$('#cm-fields').replaceChildren();$('#cm-label').value='';$('#cm-editor').hidden=true;});return;}cm.edit=null;$('#cm-fields').replaceChildren();$('#cm-label').value='';$('#cm-editor').hidden=true;}
function cmDelete(){if(!cmOpen()||!cm.edit||!cm.edit.id||cm.mutating)return;const r=cm.edit;askConfirm('Stamgegeven verwijderen?','De gegevens worden verwijderd. De weergavenaam en activiteiten blijven in het logboek staan.','Verwijderen',()=>{if(cmOpen()&&!cm.mutating){cmBusy(true);cmRequest('masterDelete',[r.id,r.revision],()=>{cmBusy(false);cm.edit=null;cm.dirty=false;cmClearEdited();cmListOpen();});}});}
function cmAuditOpen(){if(!cmOpen()||cm.mutating)return;cm.editorEpoch++;cm.events=[];cm.before=0;$('#cm-editor').hidden=true;$('#cm-list-card').hidden=true;$('#cm-audit-card').hidden=false;cmAuditMore();}
function cmAuditMore(){if(!cmOpen())return;cmRequest('masterAudit',[cm.before],r=>{cm.events.unshift(...r.events);cm.before=r.before;$('#cm-more').hidden=!r.more;cmRenderAudit();});}
function cmRenderAudit(){
  const names={unlock:t('Ontgrendeld'),list:t('Overzicht bekeken'),view:t('Gegevens bekeken'),create:t('Toegevoegd'),update:t('Gewijzigd'),delete:t('Verwijderd'),use:t('Velden overgenomen'),audit:t('Logboek bekeken'),export_release:t('Bestand vrijgegeven'),export_attempt:t('Bestand maken gestart'),export:t('Bestand gemaakt'),app_context:t('App-context vernieuwd'),photo_view:t('Kaartfoto bekeken'),photo_add:t('Kaartfoto toegevoegd'),photo_delete:t('Kaartfoto verwijderd'),relation_use:t('Relatie voor formulier bekeken')};
  const roles={reis:t('Reis'),kind:tc('formulier','Kind'),begeleider1:t('Begeleider 1'),begeleider2:t('Begeleider 2'),gever1:t('Toestemminggever 1'),gever2:t('Toestemminggever 2'),bestemming:t('Bestemming'),ontvanger:t('Ontvanger')};
  const source={masterdata:t('Stamgegevens'),formulieren:t('Formulieren invullen'),idcopy:t('ID-kopie anonimiseren')};
  const box=$('#cm-audit');box.replaceChildren();if(!cmOpen())return;const q=$('#cm-log-search').value.toLowerCase();
  [...cm.events].reverse().forEach(e=>{
    const text=[names[e.action]||e.action,source[e.source]||e.source,roles[e.role]||e.role,e.label,e.revision?t('Revisie {n}',{n:fmtNum(e.revision)}):'',(e.fields||[]).map(k=>t(CM_FIELDS[k]||k)).join(', ')].filter(Boolean).join(' · ');if(!text.toLowerCase().includes(q))return;
    const row=cmEl('div',undefined,'cm-event');row.append(cmEl('small',fmtDT(e.t)),cmEl('p',text));box.append(row);
  });
}
function cmResetReady(){$('#cm-reset').disabled=$('#cm-reset-word').value!==t('WISSEN')||cm.resetting;}
function cmReset(){if($('#cm-reset-word').value!==t('WISSEN')||cm.resetting)return;cm.resetting=true;cmNote(t('Bevestig het wissen op je telefoon.'));cmRequest('masterReset',['WISSEN'],()=>{cm.resetting=false;cmLockedUI();cmNote(t('Stamgegevens en activiteiten zijn gewist.'));},true);}
function cmPick(source,role,mapping,apply){
  if(cm.picker)return;cm.picker={source,role,mapping,apply,record:null,fields:{},origin:current,focus:document.activeElement};
  $('#cm-picker').hidden=false;$('#cm-pick-target').textContent=t('Onderdeel: {naam}',{naam:source==='formulieren'?t('Formulieren invullen'):t('ID-kopie anonimiseren')});
  $('#cm-pick-search').value='';$('#cm-pick-list').replaceChildren();$('#cm-pick-fields').replaceChildren();$('#cm-sensitive-row').hidden=true;$('#cm-apply').disabled=true;
  $('#s-'+current).inert=true;cmPrivacy(true);$('#cm-pick-title').focus();
}
function cmPickerLoad(){if(!cm.picker||!cmOpen())return;const pick=cm.picker;cmRequest('masterList',[pick.source,pick.role],r=>{if(cm.picker!==pick)return;cm.records=r.records;cmRenderPicker();});}
function cmRenderPicker(){
  const box=$('#cm-pick-list');box.replaceChildren();if(!cmOpen()||!cm.picker)return;const q=$('#cm-pick-search').value.toLowerCase();
  cm.records.filter(r=>r.label.toLowerCase().includes(q)).forEach(r=>{const person=r.person&&cm.records.find(p=>p.id===r.person);const title=person?t('{document} · gekoppeld aan {persoon}',{document:r.label,persoon:person.label}):r.label;const b=cmData(cmEl('button',title,'big ghost'));b.onclick=()=>cmPickRead(r.id);box.append(b);});
  if(!box.children.length)box.append(cmEl('p',t('Nog geen passende stamgegevens. Voeg ze toe onder Stamgegevens.'),'note'));
}
function cmPickRead(id){
  const pick=cm.picker;if(!pick||pick.applying)return;const epoch=(pick.readEpoch||0)+1;pick.readEpoch=epoch;pick.record=null;pick.fields={};
  $('#cm-pick-fields').replaceChildren();$('#cm-apply').disabled=true;$('#cm-sensitive-row').hidden=true;
  cmRequest('masterRead',[id,pick.source,pick.role],out=>{if(cm.picker!==pick||pick.readEpoch!==epoch)return;pick.record=out.record;pick.fields=out.record.fields;cmPickerFields();});
}
function cmPickerFields(){
  const box=$('#cm-pick-fields');box.replaceChildren();const pick=cm.picker;if(!pick)return;
  box.append(cmData(cmEl('h3',pick.record.label)));$('#cm-sensitive').checked=false;
  for(const [key,value] of Object.entries(pick.fields)){
    const target=pick.mapping[key];if(!target)continue;
    const label=cmEl('label',undefined,'chk'),input=cmEl('input');input.type='checkbox';input.dataset.field=key;input.checked=!CM_SECRET.has(key)&&!target.current();input.onchange=cmConsentReady;
    label.append(input,cmEl('span',t(CM_FIELDS[key]||key)),cmData(cmEl('span',CM_SECRET.has(key)?t('Afgeschermd identiteitsgegeven'):value)));box.append(label);
  }cmConsentReady();
}
function cmChosen(){return [...document.querySelectorAll('#cm-pick-fields input:checked')].map(e=>e.dataset.field);}
function cmConsentReady(){const fields=cmChosen(),secret=fields.some(k=>CM_SECRET.has(k));$('#cm-sensitive-row').hidden=!secret;$('#cm-apply').disabled=!cmOpen()||!!(cm.picker&&cm.picker.applying)||!fields.length||(secret&&!$('#cm-sensitive').checked);}
function cmApply(){
  const pick=cm.picker;if(!pick||!pick.record||!cmOpen()||$('#cm-apply').disabled)return;
  const record=pick.record;pick.applying=true;$('#cm-apply').disabled=true;cmRequest('masterUse',[record.id,record.revision,pick.source,pick.role,JSON.stringify(cmChosen()),$('#cm-sensitive').checked],r=>{
    if(cm.picker!==pick||current!==pick.origin||!Android.masterApplied(cm.token,r.grant))return;
    pick.apply(r.fields);cmClosePicker();
  },false,()=>{if(cm.picker===pick){pick.applying=false;cmConsentReady();}});
}
function cmClosePicker(){
  const pick=cm.picker;if(!pick)return;cm.picker=null;$('#cm-picker').hidden=true;$('#s-'+pick.origin).inert=false;
  cm.records=[];$('#cm-pick-list').replaceChildren();$('#cm-pick-fields').replaceChildren();$('#cm-pick-search').value='';$('#cm-sensitive').checked=false;cmPrivacy(current==='formulieren'&&!!fm.masterSensitive||current==='idcopy'&&!!idc.masterSensitive);
  if(pick.focus&&pick.focus.isConnected)pick.focus.focus();
}
function cmFormPick(){
  if(fm.busy)return;const s=fmSecties()[fm.step];if(!s)return;const first=s.fields.find(f=>/^(kind|begeleider[12]|gever[12])-/.test(f.id));
  const role=first?first.id.split('-')[0]:s.fields.some(f=>f.id==='accommodatie')?'bestemming':'';if(!role)return;
  const mapping={};for(const f of s.fields){let key=f.id.startsWith(role+'-')?f.id.slice(role.length+1):f.id;if(role==='bestemming'&&key.startsWith('bestemming-'))key=key.slice(11);mapping[key]={id:f.id,current:()=>fm.values[f.id]};}
  cmPick('formulieren',role,mapping,fields=>{for(const [key,value] of Object.entries(fields))if(mapping[key])fm.values[mapping[key].id]=value;fm.masterSensitive=true;fmWijzig();fmRender();});
}
function cmIdPick(){if(idc.busy||idc.loading)return;cmPick('idcopy','ontvanger',{ontvanger:{current:()=>$('#id-recipient').value}},fields=>{idc.masterSensitive=true;$('#id-recipient').value=fields.ontvanger;idCopyWatermarkChanged();});}
function cmNavigation(name){if(cm.picker)cmClosePicker();if(current==='masterdata'&&name!=='masterdata')cmLeave();else if(name!==current)cmPrivacy(name==='formulieren'&&!!fm.masterSensitive||name==='idcopy'&&!!idc.masterSensitive);}
const cmFmInit=fmInit;fmInit=function(id){cmFmInit(id);fm.masterSensitive=false;Android.masterReleaseForm();};
const cmIdReset=idCopyReset;idCopyReset=function(secure=true){idc.masterSensitive=false;cmIdReset(secure);};
const cmFmRender=fmRender;fmRender=function(){if(fm.masterSensitive&&!cmState().open){cmLockedUI();return;}cmFmRender();const s=fmSecties()[fm.step];$('#cm-form-pick').hidden=!s||!s.fields.some(f=>/^(kind|begeleider[12]|gever[12])-/.test(f.id)||f.id==='accommodatie');$('#cm-form-pick').disabled=fm.busy;};
const cmOnPrivacy=window.onMasterPrivacy;window.onMasterPrivacy=function(r){cmOnPrivacy(r);if(cm.picker&&cmOpen())cmPickerLoad();};
$('#cm-picker').addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();cmClosePicker();}if(e.key==='Tab'){const nodes=[...$('#cm-picker').querySelectorAll('button,input,select,[tabindex="0"]')].filter(n=>!n.disabled&&n.getClientRects().length);if(!nodes.length)return;const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});
document.addEventListener('input',e=>{if(e.target.closest('#s-masterdata,#cm-picker')||fm.masterSensitive&&e.target.closest('#s-formulieren')||idc.masterSensitive&&e.target.closest('#s-idcopy'))cmTouch();});
window.addEventListener('pagehide',()=>{Android.masterLock();cmLockedUI();});
