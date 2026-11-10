/* Stamgegevens: uitsluitend vluchtig schermgeheugen; opslag, toegang en audit blijven native. */
const cm={tab:'',back:null,keepNote:false,token:'',seq:0,pending:new Map(),records:[],recordsReady:false,edit:null,dirty:false,events:[],before:0,picker:null,view:'',generation:0,privateReady:false,unlocking:false,resetting:false,mutating:false,editorEpoch:0,
  nav:[],here:null,from:null,orig:null,lastId:'',navigating:false,navNote:'',jumpBack:false};
// taal-vast: veldnamen worden bij het tekenen met t() vertaald
const CM_TYPES={persoon:'Persoon',organisatie:'Organisatie',adres:'Adres',identiteit:'Identiteitsdocument',reis:'Reis',relatie:'Relatie',profiel:'App-profiel',formulier:'Vaste formuliergegevens'};
// taal-vast: veldnamen worden bij het tekenen met t() vertaald
const CM_FIELDS={categorieen:'Rijbewijscategorieën',geslacht:'Geslacht (zoals op het identiteitsbewijs)',roepnaam:'Roepnaam',context:'Notitie voor de assistent',contact:'Gekoppeld contact',tekenplaats:'Plaats van ondertekening (begeleiders)',handtekening:'Je bewaarde handtekening zetten bij',vanPersoon:'Eerste persoon',naarPersoon:'Tweede persoon',relatie:'Relatie van de tweede persoon',status:'Status van de relatie',toelichting:'Toelichting bij de relatie',delen:'Delen met app en lokale AI?',uitgifteland:'Land van afgifte',afgegevenDoor:'Afgegeven door',front:'Voorzijde',back:'Achterzijde',naam:'Achternaam of naam',voornamen:'Voornamen',initialen:'Initialen',geboortedatum:'Geboortedatum',geboorteplaats:'Geboorteplaats',nationaliteit:'Nationaliteit',bsn:'BSN',adres:'Straat en huisnummer',postcode:'Postcode',plaats:'Plaats',land:'Land',telefoon:'Telefoonnummer',email:'E-mailadres',registratienummer:'Registratienummer',persoon:'Gekoppelde persoon',documentsoort:'Documentsoort',document:'Documentnummer',geldig:'Geldig tot',afgifte:'Afgiftedatum',afgifteplaats:'Plaats van afgifte',ontvanger:'Ontvanger',instelling:'Instelling',accommodatie:'Naam familie of accommodatie',label:'Naam in het overzicht',van:'Eerste reisdag',tot:'Laatste reisdag',alleen:'Reist het kind alleen?',tweedeBegeleider:'Een tweede begeleider invullen?',tweedeGever:'Een tweede toestemminggever invullen?',reisgegevens:'Reisgegevens (bijvoorbeeld vluchtnummer)',formulier:'Formulier',kind:'Kind dat reist',kindDocument:'Reisdocument van het kind',begeleider1:'Begeleider 1 (reist mee)',begeleider1Document:'Reisdocument van begeleider 1',begeleider1Relatie:'Relatie van begeleider 1 tot het kind',begeleider2:'Begeleider 2 (optioneel)',begeleider2Document:'Reisdocument van begeleider 2',begeleider2Relatie:'Relatie van begeleider 2 tot het kind',gever1:'Toestemminggever 1 (reist niet mee)',gever1Document:'Identiteitsbewijs van toestemminggever 1',gever1Instelling:'Instelling van toestemminggever 1 (optioneel)',gever2:'Toestemminggever 2 (optioneel)',gever2Document:'Identiteitsbewijs van toestemminggever 2',gever2Instelling:'Instelling van toestemminggever 2 (optioneel)',bsnInvullen:'BSN op het formulier zetten?'};
const CM_SCHEMA={persoon:['naam','voornamen','roepnaam','initialen','geboortedatum','geboorteplaats','geslacht','nationaliteit','bsn','adres','postcode','plaats','land','telefoon','email','context','contact','delen'],organisatie:['naam','registratienummer','adres','postcode','plaats','land','telefoon','email'],adres:['naam','adres','postcode','plaats','land','telefoon'],identiteit:['persoon','documentsoort','document','geldig','categorieen','afgifte','afgifteplaats','uitgifteland','afgegevenDoor'],profiel:['persoon','delen','context','aiStatus','aiGeboorte','aiDocumenten','aiWoonplaats','aiContact'],relatie:['vanPersoon','naarPersoon','relatie','status','toelichting','delen'],reis:['van','tot','alleen','tweedeBegeleider','tweedeGever','accommodatie','postcode','plaats','land','telefoon','reisgegevens'],formulier:['formulier','kind','kindDocument','alleen','begeleider1','begeleider1Document','begeleider1Relatie','begeleider2','begeleider2Document','begeleider2Relatie','gever1','gever1Document','gever1Instelling','gever2','gever2Document','gever2Instelling','bsnInvullen','tekenplaats','handtekening']};
// Verwijzingen in vaste formuliergegevens: veld → soort stamgegeven (zelfde regels als MasterFormProfile.reference).
function cmRefType(type,key){if(['persoon','vanPersoon','naarPersoon'].includes(key))return 'persoon';if(type!=='formulier')return '';if(['kind','begeleider1','begeleider2','gever1','gever2'].includes(key))return 'persoon';if(/^(kind|begeleider[12]|gever[12])Document$/.test(key))return 'identiteit';if(/^gever[12]Instelling$/.test(key))return 'organisatie';return '';}
// Extra toestemming nodig bij overnemen naar een onderdeel (zelfde lijst als MasterSchema.sensitive).
const CM_SECRET=new Set(['bsn','document','geldig','afgifte']);
// Afgeschermd op het scherm (wachtwoordveld, tonen met het vinkje): alleen de nummers. Datums zijn gewone datumvelden.
const CM_MASK=new Set(['bsn','document']);
/* Stamgegevens is één zone van schermen: binnen de zone blijft de kluis open, wie de zone verlaat vergrendelt. */
const CM_SCR=['masterdata','cmedit','cmlog','cmwis','cmlijst','cmkaart','cmai'];
function cmZone(name){return CM_SCR.includes(name);}
// Schermen met gegevens: alleen ontgrendeld (Wat weet de assistent? toont op slot de gedeelde momentopname)
const CM_PRIVE=['cmedit','cmlog','cmlijst','cmkaart'];
/* Een visitekaartje bestaat uit blokken; het potlood bij een blok opent alleen die velden. Elk blok past op één scherm.
 * taal-vast: bloknamen worden bij het tekenen met t() vertaald */
const CM_BLOKKEN={persoon:[['persoon','Persoon',['roepnaam','voornamen','naam','initialen','geslacht']],['geboorte','Geboorte en nationaliteit',['geboortedatum','geboorteplaats','nationaliteit','bsn']],['contact','Contact',['telefoon','email','adres','postcode','plaats','land']],['over','Over {naam}',['context']]],
  relatie:[['relatie','Relatie',['vanPersoon','naarPersoon','relatie','status','toelichting']]],
  adres:[['adres','Adres',['naam','adres','postcode','plaats','land','telefoon']]],
  organisatie:[['gegevens','Organisatie',['naam','registratienummer']],['contact','Contact',['adres','postcode','plaats','land','telefoon','email']]],
  identiteit:[['document','Document',['persoon','documentsoort','document','geldig','categorieen']],['afgifte','Afgifte',['afgifte','afgifteplaats','uitgifteland','afgegevenDoor']],['fotos','Foto’s',[]]],
  reis:[['reis','Reis',['van','tot','reisgegevens','alleen','tweedeBegeleider','tweedeGever']],['verblijf','Verblijf',['accommodatie','postcode','plaats','land','telefoon']]],
  formulier:[['kind','Het kind',['formulier','kind','kindDocument','alleen','bsnInvullen']],['begeleiders','Begeleiders',['begeleider1','begeleider1Document','begeleider1Relatie','begeleider2','begeleider2Document','begeleider2Relatie']],['ondertekenen','Ondertekenen',['tekenplaats','handtekening']],['toestemming','Toestemming',['gever1','gever1Document','gever1Instelling','gever2','gever2Document','gever2Instelling']]],
  profiel:[['profiel','App-profiel',['persoon','delen']]]};
// Een nieuwe persoon begint kort; de rest vul je daarna per blok op het visitekaartje in.
const CM_NIEUW={persoon:['nieuw','Persoon',['roepnaam','voornamen','naam','geboortedatum','nationaliteit']]};
// Andere naam in het overzicht (menu ⋯): een eigen blok met alleen die naam
const CM_NAAM=['naam','Naam in het overzicht',[]];
// Vaste formuliergegevens en een reis vul je in één keer in (ze horen samen)
const CM_ALLES=['formulier','reis'];
const CM_NEW={persoon:'Persoon toevoegen',organisatie:'Organisatie toevoegen',adres:'Adres toevoegen',identiteit:'Document toevoegen',reis:'Reis toevoegen',relatie:'Relatie toevoegen',profiel:'App-profiel toevoegen',formulier:'Vaste formuliergegevens toevoegen'};
const CM_ICONS={persoon:'user',relatie:'share',identiteit:'document',organisatie:'shop',adres:'pin',reis:'route',profiel:'smile',formulier:'clipboard'};
function cmState(){try{return JSON.parse(Android.masterState());}catch(_){return {open:false};}}
function cmOpen(){return !!cm.token&&cmState().open&&cm.privateReady;}
const CM_NOTE={masterdata:'cm-status',cmedit:'cm-edit-status',cmlog:'cm-log-status',cmwis:'cm-wis-status',cmlijst:'cm-lijst-status',cmkaart:'cm-kaart-status',cmai:'cm-ai-status'};
// Tijdens het teruggaan na opslaan komt een melding op het scherm waar je uitkomt (cmNavDone).
function cmNote(message){if(cm.navigating)cm.navNote=message;for(const e of [CM_NOTE[current]&&$('#'+CM_NOTE[current]),$('#cm-pick-status')])if(e)e.textContent=message;}
function cmNavDone(){if(!cm.navigating)return;cm.navigating=false;const n=cm.navNote;cm.navNote='';if(n)cmNote(n);}
function cmEl(tag,text,cls){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;}
function cmData(e){e.setAttribute('translate','no');return e;}
function cmRequest(method,args,done,publicRequest=false,fail=null){
  const request='cm-'+(++cm.seq),token=cm.token;
  cm.pending.set(request,{done,token,publicRequest,fail,mutation:['masterSave','masterScanSave','masterDelete','masterPhotoDelete'].includes(method)});
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
  const open=cmOpen();$('#cm-locked').hidden=open;$('#cm-locked-ai').hidden=open;$('#cm-open').hidden=!open;$('#cm-lock-btn').hidden=!open;
  $('#cm-pick-unlock').hidden=open;$('#cm-pick-search').hidden=!open;
  if(typeof cmaiRender==='function'&&current==='cmai')cmaiRender();
  // Op slot tijdens bewerken, een lijst, een visitekaartje of het logboek: terug naar het overzicht (na een lopende schermwissel)
  if(!open&&CM_PRIVE.includes(current))setTimeout(()=>{if(CM_PRIVE.includes(current)&&!cmOpen()){const note=$('#'+CM_NOTE[current]).textContent;show('masterdata');cmNote(note);}},0);
}
function enterMasterdata(name='masterdata',prev=''){
  if(!cmZone(prev)){cmClearCentral();cmPrivacy(true);cmNote(t('Niet-bewaarde wijzigingen en overgenomen concepten worden bij vergrendelen gewist.'));}
  if(cmZone(prev)&&name!=='cmedit')cm.editorEpoch++;
  if(name==='masterdata'){cm.nav=[];cm.here=null;}
  if(CM_PRIVE.includes(name)&&!cmOpen()){cmRenderState();return;}
  if(name==='masterdata'&&cmZone(prev)&&prev!=='masterdata'&&cmOpen())cmListLoad();
  if(name==='cmwis'){$('#cm-reset-word').value='';cmResetReady();}
}
function cmLeave(){if(typeof cmi!=='undefined'){cmi.newType='';cmi.newLijst='';cmi.reopen='';}cmClearCentral();Android.masterLock();cmPrivacy(false);}
function cmClearCentral(){
  cm.records=[];cm.recordsReady=false;cm.events=[];cm.edit=null;cm.dirty=false;cm.before=0;cm.editorEpoch++;cm.back=null;cm.tab='';cmBusy(false);
  cm.nav=[];cm.here=null;cm.from=null;cm.orig=null;cm.lastId='';cm.navigating=false;cm.navNote='';cm.jumpBack=false;
  if(typeof cmi!=='undefined'){cmi.gevEdit=null;cmi.gevers=null;}
  ['cm-list','cm-ik','cm-aandacht-list','cm-lijst','cm-kaart','cm-ai-mensen','cm-fields','cm-audit','cm-pick-list','cm-pick-fields'].forEach(id=>$('#'+id).replaceChildren());
  ['cm-label','cm-search','cm-log-search','cm-pick-search','cm-reset-word'].forEach(id=>$('#'+id).value='');$('#cm-label').placeholder='';
  // Meldingen kunnen namen bevatten ("Anna is toegevoegd"): bij vergrendelen weg, ook op de schermen die niet in beeld zijn
  for(const id of Object.values(CM_NOTE))$('#'+id).textContent='';
  ['cm-kaart-title','cm-contact-hint','cmi-form-gezag'].forEach(id=>$('#'+id).textContent='');$('#cm-contact-hint').hidden=true;$('#cmi-form-gezag').hidden=true;
  for(const id of ['mensen','documenten','adressen','organisaties','assistent'])$('#cm-stand-'+id).textContent='';$('#cm-aandacht').hidden=true;$('#cm-zoek').hidden=true;$('#cm-hub').hidden=false;
  $('#cm-show-secret').checked=false;$('#cm-editor').hidden=true;$('#cm-savebar').hidden=true;$('#cm-reset').disabled=true;cmEditorTitle('');
  document.querySelectorAll(CM_SCR.map(n=>'#s-'+n+' [data-ui-edited]').join(',')+',#cm-picker [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
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
  if(fm.masterSensitive){fm.busy=false;if(typeof fmConceptVanzelf==='function')fmConceptVanzelf();cmClearFormDOM();fm.exportToken='';fm.masterSensitive=false;fmInit(fm.id);if(typeof fmConceptLaad==='function')fmConceptLaad();if(current==='formulieren')fmRender();} // eigen invoer blijft als concept; gegevens uit Stamgegevens niet
  if(idc.masterSensitive){idc.masterSensitive=false;idc.busy=false;idc.loading=false;idCopyReset(current==='idcopy');idCopyControls();}
  if(cmZone(current)&&$('#modal').classList.contains('on'))closeModal(null);
  if(cmZone(current)&&sigOpen())sigClose();
  cmRenderState();cmNote(t('Stamgegevens zijn vergrendeld. Niet-bewaarde gegevens zijn gewist.'));
}
window.onMasterLocked=function(generation){cm.generation=generation;cmLockedUI();Android.masterSafeFrame(cm.view,generation);};
window.onMasterReset=function(){cm.unlocking=false;cm.resetting=false;cm.pending.clear();cmLockedUI();if(current==='cmwis')show('masterdata');cmNote(t('Stamgegevens en activiteiten zijn gewist.'));};
/* vanzelf: extra ontgrendelen staat uit, dus zonder vraag openen als dat zeker kan (Java beslist; anders {vraag:true}). */
function cmUnlock(vanzelf=false){
  vanzelf=vanzelf===true;
  if(cm.unlocking||cm.resetting)return;if(!cmState().secure){if(!vanzelf)cmNote(t('Stel eerst een schermvergrendeling in op je telefoon'));return;}cm.unlocking=true;if(!vanzelf)cmNote(t('Bevestig op je telefoon.'));
  cmRequest(vanzelf?'masterOpenVanzelf':'masterUnlock',[],r=>{cm.unlocking=false;if(r.vraag)cmNote(t('Je telefoon is al even ontgrendeld. Bevestig het nog een keer met Ontgrendelen.'));if(!r.opened||!cmState().open)return;cm.token=r.token;cmRenderState();cmNote('');appIdentityRefresh();cmiPhotoAfterUnlock(r.photo);if(cm.picker)cmPickerLoad();else if(typeof fmq!=='undefined'&&fmq.open)fmqLoad();else cmListOpen();},true);
}
/** Extra ontgrendelen uit: Stamgegevens (of de keuzelijst ervan) opent vanzelf zodra het scherm klaar is */
function cmVanzelf(){
  // Onder het slotscherm van een andere tool (bijv. PDF met Formulieren) niets ontsleutelen: dat wacht tot na ontgrendelen
  if(!tsEigenUit('masterdata')||!tsVanzelfMag()||cm.unlocking||cm.resetting||cm.token&&cmOpen())return;
  if(!(cmZone(current)&&current!=='cmwis'||cm.picker||typeof fmq!=='undefined'&&fmq.open))return;
  cmUnlock(true);
}
function cmLock(){Android.masterLock();cmLockedUI();}
function cmTouch(){if(cmOpen())Android.masterTouch(cm.token);}
function cmDirty(){cm.dirty=true;cmTouch();}
/* Naar het overzicht en de lijst opnieuw laden (na opslaan, verwijderen of sluiten). */
function cmListOpen(){
  if(!cmOpen()||cm.mutating)return;
  if(current==='cmai'){cmListLoad(()=>{if(typeof cmaiRender==='function')cmaiRender();});return;}
  if(current!=='masterdata'){show('masterdata');return;}
  cmListLoad();
}
function cmListLoad(after,fail=null){
  if(!cmOpen())return;cm.recordsReady=false;document.querySelectorAll('#cm-open button').forEach(b=>b.disabled=true);const epoch=++cm.editorEpoch;
  cmRequest('masterList',['masterdata',''],r=>{cm.records=r.records;cm.recordsReady=true;cmRenderList();document.querySelectorAll('#cm-open button').forEach(b=>b.disabled=false);if(epoch!==cm.editorEpoch){cmNavDone();return;}if(after)after();else cmiAfterList();},false,r=>{cmNavDone();if(fail)fail(r);});
}
/* Het beginscherm (Ik, Aandacht nodig, rijen) tekent 71-stamgegevens-ik.js; zoeken toont één lijst met alles. */
function cmRenderList(){}
function cmRow(r,small,warn,onclick,icon,title){
  const b=cmData(cmEl('button',undefined,'strow')),ic=cmEl('span',undefined,'stic'),txt=cmEl('span',undefined,'sttxt'),chev=cmEl('span',undefined,'ui-icon stchev');
  b.type='button';ic.innerHTML=uiIcon(icon||CM_ICONS[r?.type]||'document');chev.dataset.icon='chevron-right';chev.setAttribute('aria-hidden','true');
  const s=cmEl('small',small);if(warn)s.classList.add('cmi-warn');txt.append(cmEl('b',title||r.label),s);b.append(ic,txt,chev);b.onclick=onclick;return b;
}
/* Waar je vandaan kwam (lijst of visitekaartje): daar ga je na opslaan of annuleren weer heen. */
function cmHere(){return ['cmlijst','cmkaart'].includes(current)&&cm.here?{...cm.here}:current==='cmedit'?cm.from:null;}
function cmNew(){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;cm.editorEpoch++;cm.from=cmHere();cm.back=null;cm.edit={type:'persoon',label:'',fields:{},revision:0};cmEditor();}
function cmEdit(id,tab,back,note,ready=null){if(cm.mutating&&!ready)return;const epoch=++cm.editorEpoch,from=cmHere();cmRequest('masterRead',[id,'masterdata',''],r=>{if(epoch!==cm.editorEpoch)return;if(ready)ready();cm.edit=r.record;cm.back=back||null;cm.from=from;cmEditor(tab);if(note)cmNote(note);},false,ready);}
function cmEditorTitle(r){const h=$('#cm-editor-title');if(r&&r.id){h.textContent=r.label;h.setAttribute('translate','no');}else{h.removeAttribute('translate');h.textContent=r?t(CM_NEW[r.type]||'Stamgegeven toevoegen'):t('Stamgegeven bewerken');}}
function cmEditor(tab){
  const r=cm.edit;$('#cm-editor').hidden=false;$('#cm-savebar').hidden=false;if(!cm.keepNote)$('#cm-edit-status').textContent='';cm.keepNote=false;$('#cm-edit-menu').hidden=!r.id;cmEditorTitle(r);
  cm.orig=JSON.parse(JSON.stringify(r.fields||{}));cm.lastId=r.id||'';
  const hint=$('#cm-contact-hint');hint.hidden=!!r.id||!r.fields?.contact;hint.textContent=hint.hidden?'':t('Overgenomen uit Contacten. Telefoon en e-mail blijven daarna actueel uit Contacten; de rest vul je aan op het kaartje.');
  $('#cm-label').value=r.id&&!cmLabelAuto(r)?r.label:'';$('#cm-label').placeholder=cmAutoLabel(r.type,r.fields);$('#cm-show-secret').checked=false;cm.dirty=false;cm.tab=tab||'';cmTabsBuild();cmEditorFields();
  const focus=()=>{if(r.id||cm.edit!==r)return;const f=[...document.querySelectorAll('#cm-fields input,#cm-fields select,#cm-fields textarea')].find(x=>!x.closest('.cm-off'));if(f)f.focus();};
  if(current!=='cmedit'){show('cmedit');setTimeout(focus,0);}else focus();
}
/* Blokken van dit stamgegeven: een nieuwe persoon kort, een nieuw ander gegeven het eerste blok, reizen en vaste gegevens alles. */
function cmTabs(r){
  if(!r)return [];if(!r.id&&CM_NIEUW[r.type])return [CM_NIEUW[r.type]];
  const blocks=CM_BLOKKEN[r.type]||[];
  if(!r.id)return CM_ALLES.includes(r.type)?blocks:blocks.filter(b=>b[2].length).slice(0,1);
  return [...blocks,CM_NAAM];
}
function cmBlokTitle(b,r){return b[1]==='Over {naam}'?t('Over {naam}',{naam:r?.fields?.roepnaam||(r?.fields?.voornamen||'').split(/\s+/)[0]||r?.label||''}):t(b[1]);}
function cmTabsBuild(){
  const r=cm.edit,tabs=cmTabs(r);
  if(CM_ALLES.includes(r.type)&&(cm.tab==='*'||!r.id)){cm.tab='*';return;}
  if(!tabs.some(x=>x[0]===cm.tab))cm.tab=tabs[0]?tabs[0][0]:'';
}
function cmTab(id){if(!cm.edit||!cmTabs(cm.edit).some(x=>x[0]===id))return;cm.tab=id;cmTabRender();cmTouch();}
function cmTabRender(){
  const r=cm.edit,all=cm.tab==='*',blok=cmTabs(r).find(x=>x[0]===cm.tab),h=$('#cm-blok-title');
  h.hidden=all||!r.id||!blok;h.textContent=blok&&r.id?cmBlokTitle(blok,r):'';
  document.querySelectorAll('#cm-editor [data-cm-tab]').forEach(e=>e.classList.toggle('cm-off',e.id==='cm-label-box'?cm.tab!=='naam':!all&&e.dataset.cmTab!==cm.tab));
  $('#cm-secret-row').classList.toggle('cm-off',![...CM_MASK].some(k=>{const i=$('#cm-field-'+k);return i&&!i.closest('.cm-off');}));
}
function cmEditorFields(){
  const box=$('#cm-fields');box.replaceChildren();const tabs=cmTabs(cm.edit),all=cm.tab==='*',schema=CM_SCHEMA[cm.edit.type]||[];
  for(const blok of tabs){
    let group=null;
    for(const key of blok[2]){
      if(!schema.includes(key)||cmiSkip(key)||key==='categorieen'&&cm.edit.fields.documentsoort!=='rijbewijs')continue;
      const label=cmEl('label',t(cm.edit.type==='profiel'&&key==='persoon'?'Dit ben ik':CM_FIELDS[key]),'f');label.htmlFor='cm-field-'+key;
      const ref=cmRefType(cm.edit.type,key),person=!!ref,choices=cmiChoices(key),area=['context','toelichting'].includes(key);
      if(key==='categorieen'){const cats=cmiCatsEl(label);if(!group){group=cmEl('div',undefined,'cm-group');group.dataset.cmTab=blok[0];if(all&&tabs.length>1)group.append(cmEl('h3',cmBlokTitle(blok,cm.edit),'cm-blok-h'));box.append(group);}group.append(label,cats);continue;}
      const input=cmData(cmEl(person||choices?'select':area?'textarea':'input',undefined,'search'));input.id=label.htmlFor;input.name=key;
      if(person){
        const empty=cmEl('option',t(ref==='identiteit'?'Kies een document':ref==='organisatie'?'Geen instelling':'Kies een persoon'));empty.value='';input.append(empty);
        const owner=ref==='identiteit'?cm.edit.fields[key.slice(0,-8)]||'':'';
        cm.records.filter(r=>r.type===ref&&(ref!=='identiteit'||!!owner&&r.person===owner)).forEach(r=>{const o=cmData(cmEl('option',r.label));o.value=r.id;input.append(o);});
      }else if(choices){
        let og=null;
        for(const [value,text,name] of choices){const o=cmEl('option',text);o.value=value;if(name&&og?.label!==name){og=cmEl('optgroup');og.label=name;input.append(og);}(name?og:input).append(o);}
        const old=cm.edit.fields[key];if(old&&!choices.some(c=>c[0]===old)){const o=cmData(cmEl('option',old));o.value=old;input.append(o);}
      }else{input.autocomplete='off';input.maxLength=key==='bsn'?9:240;if(!area)input.type=CM_MASK.has(key)?'password':['geboortedatum','van','tot','geldig','afgifte'].includes(key)?'date':key==='email'?'email':key==='telefoon'?'tel':'text';else input.rows=key==='context'?4:3;if(key==='bsn')input.inputMode='numeric';}
      input.value=cm.edit.fields[key]||'';input.addEventListener(person||choices?'change':'input',()=>{cm.edit.fields[key]=input.value;cmDirty();if(cm.edit.type==='formulier'&&ref==='persoon')cmiFormPerson(key);if(cm.edit.type==='relatie')cmiRelationChange(key);if(key==='documentsoort')cmEditorFields();});
      if(!group){group=cmEl('div',undefined,'cm-group');group.dataset.cmTab=blok[0];if(all&&tabs.length>1)group.append(cmEl('h3',cmBlokTitle(blok,cm.edit),'cm-blok-h'));box.append(group);}
      group.append(label,input);
    }
  }cmiEditor();cmTabRender();
}
function cmShowSecret(show){CM_MASK.forEach(key=>{const input=$('#cm-field-'+key);if(input)input.type=show?'text':'password';});cmTouch();}
function cmBusy(busy){cm.mutating=busy;$('#cm-save').disabled=busy;$('#cm-edit-menu').disabled=busy;document.querySelectorAll('#cm-fields input,#cm-fields select,#cm-fields textarea,#cm-fields .cmi-cats button,#cmh-panel button,#cmh-list input:not([data-same]),#cmi-photos button,#cm-kaart button,#cm-ai-set button,#cm-ai-mensen button').forEach(e=>e.disabled=busy);$('#cm-label').disabled=busy;}
const CM_ERR_FIELD={'Vul een naam voor het overzicht in':'cm-label','Kies bij welke begeleider je handtekening komt':'cm-field-handtekening','Kies voor je handtekening een begeleider die meereist':'cm-field-handtekening','Het kind reist alleen: kies bij je handtekening Nergens':'cm-field-handtekening','Je bewaarde handtekening is van jou: kies bij Vaste gegevens → Ondertekenen de begeleider die jij bent':'cm-field-handtekening','Kies een gekoppelde persoon':'cm-field-persoon','Kies een geldige relatie':'cm-field-relatie','Een relatie hoort bij twee verschillende personen':'cm-field-naarPersoon','Kies een status die bij deze relatie past':'cm-field-status','Vul een geldig BSN in':'cm-field-bsn',
  'Kies in de vaste gegevens het kind dat reist':'cm-field-kind','Kies in de vaste gegevens wie toestemming geeft':'cm-field-gever1','Kies in de vaste gegevens wie meereist, of kies dat het kind alleen reist':'cm-field-begeleider1',
  'Kies twee verschillende begeleiders':'cm-field-begeleider2','Kies twee verschillende toestemmingsgevers':'cm-field-gever2','Kies voor welk formulier deze vaste gegevens zijn':'cm-field-formulier'};
function cmShowField(id){const e=$('#'+id);if(!e)return;const box=e.closest('[data-cm-tab]');if(box&&box.classList.contains('cm-off'))cmTab(box.dataset.cmTab);for(const d of [...e.closest('#cm-editor').querySelectorAll('details')])if(d.contains(e))d.open=true;e.focus();}
/* Namen in het overzicht ontstaan vanzelf: een persoon roepnaam (of eerste voornaam) en achternaam, een document "Paspoort Anna",
 * een adres of organisatie hun naam. Een naam die nu een van de naamvarianten is, telt als automatisch en gaat mee met de
 * gegevens; alleen een echt afwijkende naam (zoals "Mama", via Andere naam in het overzicht) blijft staan. */
function cmAutoLabel(type,f){
  f=f||{};const v=s=>(s||'').trim();
  if(type==='persoon'){const first=v(f.voornamen).split(/\s+/)[0];return [v(f.roepnaam)||first,v(f.naam)].filter(Boolean).join(' ').slice(0,120);}
  if(type==='identiteit'){const owner=cm.records.find(p=>p.id===f.persoon);return (owner?t('{soort} {naam}',{soort:cmiDocSoort({soort:f.documentsoort}),naam:owner.label}):cmiDocSoort({soort:f.documentsoort})).slice(0,120);}
  if(type==='adres')return (v(f.naam)||v(f.adres)).slice(0,120);
  if(type==='organisatie')return v(f.naam).slice(0,120);
  if(type==='reis')return f.van?t('Reis van {datum}',{datum:fmtDate(f.van)}):'';
  if(type==='formulier'){const kid=cm.records.find(p=>p.id===f.kind);return kid?t('Reistoestemming {naam}',{naam:kid.label}).slice(0,120):'';}
  if(type==='relatie')return cmiRelationText(f,'label');
  if(type==='profiel')return t('Mijn app-profiel');
  return '';
}
function cmAutoLabels(type,f){
  f=f||{};const v=s=>(s||'').trim(),out=new Set([cmAutoLabel(type,f)]);
  if(type==='persoon'){const first=v(f.voornamen).split(/\s+/)[0];for(const a of [v(f.roepnaam),v(f.voornamen),first,v(f.initialen)])if(a){out.add(a);if(v(f.naam))out.add(a+' '+v(f.naam));}if(v(f.naam))out.add(v(f.naam));}
  if(type==='relatie')for(const x of cmiRelationLabels(f))out.add(x);
  if(type==='formulier'){const kid=cm.records.find(p=>p.id===f.kind);if(kid)out.add(t('Reistoestemming {naam}',{naam:kid.label}));}
  out.delete('');return out;
}
function cmLabelAuto(r){return !r.label||cmAutoLabels(r.type,r.id?cm.orig||r.fields:r.fields).has(r.label);}
function cmLabel(r){
  if(cm.tab==='naam')return $('#cm-label').value.trim()||cmAutoLabel(r.type,r.fields)||r.label;
  return ((!r.id||cmLabelAuto(r))&&cmAutoLabel(r.type,r.fields))||r.label||t(CM_TYPES[r.type]);
}
/* Zonder naam kan een persoon, adres of organisatie niet in het overzicht staan. */
function cmNeedName(r){
  if(cm.tab==='naam'||!['persoon','adres','organisatie'].includes(r.type)||cmAutoLabel(r.type,r.fields)||r.id&&!cmLabelAuto(r))return null;
  const field=['roepnaam','voornamen','naam'].map(k=>'cm-field-'+k).find(id=>$('#'+id)&&!$('#'+id).closest('.cm-off'))||'cm-field-naam';
  return [field,r.type==='persoon'?t('Vul een naam in'):r.type==='adres'?t('Vul een naam of adres in'):t('Vul de naam in')];
}
function cmSave(){
  if(!cmOpen()||!cm.edit||cm.mutating)return;
  if(cm.edit.type==='relatie'){const empty=['vanPersoon','naarPersoon','relatie'].find(k=>!cm.edit.fields[k]);if(empty){cmShowField('cm-field-'+empty);cmNote(t('Kies beide personen en hun relatie'));return;}}
  const need=cmNeedName(cm.edit);if(need){cmShowField(need[0]);cmNote(need[1]);return;}
  cm.edit.label=cmLabel(cm.edit);
  cmBusy(true);cmRequest('masterSave',[JSON.stringify(cm.edit)],r=>{cmBusy(false);cm.edit=null;cm.dirty=false;cmClearEdited();cmAfterEdit(true,r.record&&r.record.id);cmNote(r.contextError?t(r.contextError):t('Opgeslagen.'));appIdentityRefresh();},false,r=>{cmBusy(false);if(cm.edit&&CM_ERR_FIELD[r.error])cmShowField(CM_ERR_FIELD[r.error]);});
}
function cmClearEdited(){document.querySelectorAll(CM_SCR.map(n=>'#s-'+n+' [data-ui-edited]').join(',')).forEach(e=>delete e.dataset.uiEdited);}
/* Na opslaan, verwijderen of sluiten: naar het visitekaartje (van de persoon waar je vandaan kwam, of van wat je bewerkte),
 * anders terug naar de lijst of het beginscherm. Een melding komt op het scherm waar je uitkomt. */
function cmAfterEdit(saved,savedId){
  const back=cm.back,from=cm.from,target=back?.id||savedId||cm.lastId||'';cm.back=null;cm.from=null;cm.orig=null;cm.lastId='';
  $('#cm-editor').hidden=true;$('#cm-savebar').hidden=true;$('#cm-fields').replaceChildren();$('#cm-label').value='';$('#cm-label').placeholder='';cmEditorTitle('');$('#cm-status').textContent='';
  if(typeof cmiRelationsClear==='function'){cmiRelationsClear();cmiPhotoClear();}
  // Vanuit Formulieren (Vaste gegevens) en zonder opslaan terug: meteen terug naar Formulieren
  const jump=jumpStack[jumpStack.length-1];if(back?.jump&&!saved&&current==='cmedit'&&jump?.to==='masterdata'){jumpStack.pop();show(jump.from);return;}
  if(!cmOpen()){if(current==='cmedit')show('masterdata');return;}
  cm.navigating=true;cm.navNote='';
  cmListLoad(()=>{
    if(target&&cm.records.some(r=>r.id===target)){if(from&&!(from.scr==='cmkaart'&&from.id===target))cm.nav.push(from);cmKaartOpen(target,false);return;}
    // Na verwijderen: niet terug naar het kaartje van wat er niet meer is
    const gone=x=>x?.scr==='cmkaart'&&!cm.records.some(r=>r.id===x.id);
    if(from&&!gone(from)){cmGo(from,false);return;}
    while(cm.nav.length&&gone(cm.nav[cm.nav.length-1]))cm.nav.pop();
    const prev=cm.nav.pop();if(prev){cmGo(prev,false);return;}
    if(current!=='masterdata')show('masterdata');cmNavDone();
  });
}
function cmEditBack(){if(!cm.mutating)cmCancelEdit();return true;}
function cmCancelEdit(){if(cm.mutating)return;cm.editorEpoch++;const close=()=>{cm.edit=null;cm.dirty=false;cmClearEdited();cmAfterEdit();};if(cm.dirty){askConfirm('Bewerking sluiten?','Niet-bewaarde wijzigingen gaan verloren.','Sluiten',close);return;}close();}
function cmDelete(){if(!cmOpen()||!cm.edit||!cm.edit.id||cm.mutating)return;const r=cm.edit;askConfirm('Stamgegeven verwijderen?','De gegevens worden verwijderd. De weergavenaam en activiteiten blijven in het logboek staan.','Verwijderen',()=>{if(cmOpen()&&!cm.mutating){cmBusy(true);cmRequest('masterDelete',[r.id,r.revision],()=>{cmBusy(false);cm.edit=null;cm.dirty=false;cmClearEdited();cmAfterEdit(true);cmNote(t('Stamgegeven verwijderd.'));appIdentityRefresh();});}});}
function cmEditMenu(){if(!cmOpen()||!cm.edit?.id||cm.mutating)return;openSheet(t(CM_TYPES[cm.edit.type]),'',[[t('Stamgegeven verwijderen'),cmDelete,'trash']]);}
function cmMenu(){const acts=[];if(cmOpen())acts.push([t('Activiteiten'),cmAuditOpen,'clock']);acts.push([t('Opnieuw beginnen'),()=>show('cmwis'),'trash']);openSheet(t('Stamgegevens'),'',acts);}
function cmAuditOpen(){if(!cmOpen()||cm.mutating)return;cm.editorEpoch++;cm.events=[];cm.before=0;$('#cm-audit').replaceChildren();$('#cm-log-search').value='';$('#cm-more').hidden=true;if(current!=='cmlog')show('cmlog');cmAuditMore();}
function cmAuditMore(){if(!cmOpen())return;cmRequest('masterAudit',[cm.before],r=>{cm.events.unshift(...r.events);cm.before=r.before;$('#cm-more').hidden=!r.more;cmRenderAudit();});}
function cmRenderAudit(){
  const names={unlock:t('Ontgrendeld'),list:t('Overzicht bekeken'),view:t('Gegevens bekeken'),create:t('Toegevoegd'),update:t('Gewijzigd'),delete:t('Verwijderd'),use:t('Velden overgenomen'),audit:t('Logboek bekeken'),export_release:t('Bestand vrijgegeven'),export_attempt:t('Bestand maken gestart'),export:t('Bestand gemaakt'),app_context:t('App-context vernieuwd'),photo_view:t('Kaartfoto bekeken'),photo_add:t('Kaartfoto toegevoegd'),photo_delete:t('Kaartfoto verwijderd'),relation_use:t('Relatie voor formulier bekeken'),expiry_check:t('Geldigheid gecontroleerd'),scan:t('Foto herkennen gestart'),photo_scan:t('Gegevens herkend uit kaartfoto'),contact_use:t('Bellen, bericht of Contacten geopend'),about_move:t('Bijgewerkt naar de nieuwe indeling: tekst over jou naar Over mij'),about_clear:t('Tekst over jou weggehaald')};
  const roles={reis:t('Reis'),kind:tc('formulier','Kind'),begeleider1:t('Begeleider 1'),begeleider2:t('Begeleider 2'),gever1:t('Toestemminggever 1'),gever2:t('Toestemminggever 2'),bestemming:t('Bestemming'),ontvanger:t('Ontvanger'),vast:t('Vaste formuliergegevens')};
  const source={masterdata:t('Stamgegevens'),formulieren:t('Formulieren invullen'),idcopy:t('ID-kopie afschermen')};
  const box=$('#cm-audit');box.replaceChildren();if(!cmOpen())return;const q=$('#cm-log-search').value.toLowerCase();
  [...cm.events].reverse().forEach(e=>{
    const text=[names[e.action]||e.action,source[e.source]||e.source,roles[e.role]||e.role,e.label,e.revision?t('Revisie {n}',{n:fmtNum(e.revision)}):'',(e.fields||[]).map(k=>t(CM_FIELDS[k]||k)).join(', ')].filter(Boolean).join(' · ');if(!text.toLowerCase().includes(q))return;
    const row=cmEl('div',undefined,'cm-event');row.append(cmEl('small',fmtDT(e.t)),cmEl('p',text));box.append(row);
  });
}
function cmResetReady(){$('#cm-reset').disabled=$('#cm-reset-word').value!==t('WISSEN')||cm.resetting;}
function cmReset(){if($('#cm-reset-word').value!==t('WISSEN')||cm.resetting)return;cm.resetting=true;cmNote(t('Bevestig het wissen op je telefoon.'));cmRequest('masterReset',['WISSEN'],()=>{cm.resetting=false;cmLockedUI();if(current==='cmwis')show('masterdata');cmNote(t('Stamgegevens en activiteiten zijn gewist.'));},true);}
function cmPick(source,role,mapping,apply){
  if(cm.picker)return;cm.picker={source,role,mapping,apply,record:null,fields:{},origin:current,focus:document.activeElement};
  $('#cm-picker').hidden=false;$('#cm-pick-target').textContent=t('Onderdeel: {naam}',{naam:source==='formulieren'?t('Formulieren invullen'):t('ID-kopie anonimiseren')});
  $('#cm-pick-search').value='';$('#cm-pick-list').replaceChildren();$('#cm-pick-fields').replaceChildren();$('#cm-sensitive-row').hidden=true;$('#cm-apply').disabled=true;
  dialogFocus.base($('#s-'+current),true);cmPrivacy(true);$('#cm-pick-title').focus();
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
  const pick=cm.picker;if(!pick)return;cm.picker=null;$('#cm-picker').hidden=true;dialogFocus.base($('#s-'+pick.origin),false);
  cm.records=[];$('#cm-pick-list').replaceChildren();$('#cm-pick-fields').replaceChildren();$('#cm-pick-search').value='';$('#cm-sensitive').checked=false;cmPrivacy(current==='formulieren'&&!!fm.masterSensitive||current==='idcopy'&&!!idc.masterSensitive);
  if(pick.focus&&pick.focus.isConnected)pick.focus.focus();
}
function cmFormPick(){
  if(fm.busy)return;const s=fmSecties()[fm.step];if(!s)return;const first=s.fields.find(f=>/^(kind|begeleider[12]|gever[12])-/.test(f.id));
  const role=first?first.id.split('-')[0]:s.fields.some(f=>f.id==='van')?'reis':s.fields.some(f=>f.id==='accommodatie')?'bestemming':'';if(!role)return;
  const mapping={};for(const f of s.fields){let key=f.id.startsWith(role+'-')?f.id.slice(role.length+1):f.id;if((role==='bestemming'||role==='reis')&&key.startsWith('bestemming-'))key=key.slice(11);mapping[key]={id:f.id,current:()=>fm.values[f.id]};}
  cmPick('formulieren',role,mapping,fields=>{for(const [key,value] of Object.entries(fields))if(mapping[key]){fm.values[mapping[key].id]=value;fm.stam.add(mapping[key].id);}fm.masterSensitive=true;fm.meer={};fmWijzig();fmRender();});
}
function cmIdPick(){if(idc.busy||idc.loading)return;cmPick('idcopy','ontvanger',{ontvanger:{current:()=>$('#id-recipient').value}},fields=>{idc.masterSensitive=true;$('#id-recipient').value=fields.ontvanger;idCopyWatermarkChanged();});}
function cmNavigation(name){if(cm.picker)cmClosePicker();if(cmZone(current)&&!cmZone(name))cmLeave();else if(name!==current&&!(cmZone(current)&&cmZone(name)))cmPrivacy(name==='formulieren'&&!!fm.masterSensitive||name==='idcopy'&&!!idc.masterSensitive);}
const cmFmInit=fmInit;fmInit=function(id){cmFmInit(id);fm.masterSensitive=false;Android.masterReleaseForm();};
const cmIdReset=idCopyReset;idCopyReset=function(secure=true){idc.masterSensitive=false;cmIdReset(secure);};
const cmFmRender=fmRender;fmRender=function(){if(fm.masterSensitive&&!cmState().open){cmLockedUI();return;}cmFmRender();const s=fmSecties()[fm.step];$('#cm-form-pick').hidden=!s||!s.fields.some(f=>/^(kind|begeleider[12]|gever[12])-/.test(f.id)||f.id==='accommodatie');$('#cm-form-pick').disabled=fm.busy;};
const cmOnPrivacy=window.onMasterPrivacy;window.onMasterPrivacy=function(r){cmOnPrivacy(r);if(cm.picker&&cmOpen())cmPickerLoad();else if(['masterdata','cmai'].includes(current)&&cmOpen())cmListOpen();};
// Extra ontgrendelen uit: na het openen van het scherm (en terug in de app of na het app-slot) vanzelf ontgrendelen.
// Een lopende vraag (fmq.ask, een ontgrendeling) gaat voor: cmVanzelf wacht één beurt en kijkt dan of er al iets loopt.
(function(){
  const privacy=window.onMasterPrivacy;window.onMasterPrivacy=function(r){privacy(r);if(r&&r.view&&r.view===cm.view)setTimeout(cmVanzelf,0);};
  for(const naam of ['onResumeApp','onUnlocked']){const prev=window[naam];window[naam]=function(){if(prev)prev.apply(this,arguments);if(cm.view&&cm.privateReady)setTimeout(cmVanzelf,0);};}
  const slot=window.onToolSlot;window.onToolSlot=function(r){slot(r);if(r&&r.soort==='open'&&r.ok&&cm.view&&cm.privateReady)setTimeout(cmVanzelf,0);};
})();
$('#cm-picker').addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();cmClosePicker();}if(e.key==='Tab'){const nodes=[...$('#cm-picker').querySelectorAll('button,input,select,[tabindex="0"]')].filter(n=>!n.disabled&&n.getClientRects().length);if(!nodes.length)return;const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});
document.addEventListener('input',e=>{if(e.target.closest(CM_SCR.map(n=>'#s-'+n).join(',')+',#cm-picker')||fm.masterSensitive&&e.target.closest('#s-formulieren')||idc.masterSensitive&&e.target.closest('#s-idcopy'))cmTouch();});
window.addEventListener('pagehide',()=>{Android.masterLock();cmLockedUI();});
