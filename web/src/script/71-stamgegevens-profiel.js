/* Gedeelde identiteit is een expliciete, beperkte native momentopname. Geen opslag in webstore. */
let appIdentity={};
const cmi={photoRequest:'',photoEpoch:0,reopen:'',show:false,newType:'',newLijst:'',gevers:null,gevEdit:null};
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
    if(key==='handtekening')return [['',t('Nergens')],['begeleider1',t('Begeleider 1')],['begeleider2',t('Begeleider 2')]];
  }
  if(['alleen','tweedeBegeleider','tweedeGever','delen'].includes(key))return [['',t('Niet ingevuld')],['ja',t('Ja')],['nee',t('Nee')]];
  if(key==='relatie')return [['',t('Kies een relatie')],...cmiRelationOptions()];
  if(key==='status'&&cm.edit?.type==='relatie')return cmiStatusChoices(cm.edit.fields.relatie);
  if(key==='geslacht')return [['',t('Niet ingevuld')],['V',t('Vrouw')],['M',t('Man')],['X',t('X (onbepaald)')]];
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
  if(['kind','begeleider1','begeleider2'].includes(key))cmiFormGevers();
  const focus=document.activeElement?.id;cmEditorFields();if(focus&&$('#'+focus))$('#'+focus).focus();
  cmiFormGezag();
}
/* Ouders van een kind met de status die Rene zelf bij de relatie vastlegde (gezag of geengezag); niets afgeleid. */
const CMI_OUDERS=['ouder','vader','moeder','stiefouder','pleegouder'],CMI_KINDEREN=['kind','zoon','dochter','stiefkind','pleegkind'];
function cmiParents(kid){
  const out=new Map();if(!kid)return out;
  for(const r of cm.records.filter(x=>x.type==='relatie')){
    const p=r.van===kid&&CMI_OUDERS.includes(r.relatie)?r.naar:r.naar===kid&&CMI_KINDEREN.includes(r.relatie)?r.van:'';
    if(p&&p!==kid&&(!out.get(p)||r.status))out.set(p,r.status||'');
  }
  return out;
}
/* Toestemming geven de ouders met gezag die niet meereizen: die stelt de app voor zolang Rene zelf niets anders koos. */
function cmiFormGevers(){
  const f=cm.edit.fields,was=cmi.gevers||['',''];
  if((f.gever1||'')!==was[0]||(f.gever2||'')!==was[1])return;
  const reist=new Set([f.begeleider1,f.begeleider2].filter(Boolean)),label=id=>cmiPersonName(id);
  const ids=[...cmiParents(f.kind)].filter(([id,st])=>st==='gezag'&&!reist.has(id)).map(([id])=>id).sort((a,b)=>label(a).localeCompare(label(b))).slice(0,2);
  f.gever1=ids[0]||'';f.gever2=ids[1]||'';cmi.gevers=[f.gever1,f.gever2];
  for(const k of ['gever1','gever2']){const docs=cm.records.filter(r=>r.type==='identiteit'&&f[k]&&r.person===f[k]);if(!docs.some(r=>r.id===f[k+'Document']))f[k+'Document']=docs.length===1?docs[0].id:'';}
  if(ids.length)cmNote(tn(ids.length,'De ouder met ouderlijk gezag staat voorgesteld bij Toestemming. Kijk na of dat klopt.','De ouders met ouderlijk gezag staan voorgesteld bij Toestemming. Kijk na of dat klopt.'));
}
/* Waarschuwing: toestemming van iemand die volgens je relaties geen ouderlijk gezag heeft. */
function cmiFormGezag(){
  const box=$('#cmi-form-gezag'),f=cm.edit?.type==='formulier'?cm.edit.fields:null;
  const ouders=f?cmiParents(f.kind):new Map(),kind=f?cmiPersonName(f.kind):'';
  const lines=f?['gever1','gever2'].filter(k=>f[k]&&ouders.get(f[k])==='geengezag').map(k=>t('Volgens je relaties heeft {naam} geen ouderlijk gezag over {kind}. Alleen een ouder met gezag kan toestemming geven.',{naam:cmiPersonName(f[k]),kind})):[];
  box.textContent=lines.join('\n');box.hidden=!lines.length;
}
function appIdentityRefresh(){try{appIdentity=JSON.parse(Android.appIdentity())||{};}catch(_){appIdentity={};}cmiSharedRender();}
window.onAppIdentity=function(){appIdentityRefresh();};
function cmiSharedRender(){
  const self=appIdentity.self,people=appIdentity.people||[],box=$('#cmi-shared');box.replaceChildren();$('#cmi-home').hidden=!self;
  $('#cmi-home-name').textContent=self?t('Hallo {naam}',{naam:self.callname||self.name}):'';$('#cmi-home').setAttribute('aria-label',self?t('Hallo {naam}, naar Stamgegevens',{naam:self.callname||self.name}):t('Stamgegevens'));
  const naam=self?self.callname||self.name:'';$('#cmi-shared-stand').textContent=cmiAiStand();
  if(!self){box.append(cmEl('p',t('De assistent weet nu niets uit Stamgegevens.'),'note'));$('#cmi-ai-summary').textContent=t('De assistent weet nu niets uit Stamgegevens.');return;}
  const line=(text,extra)=>{const p=cmData(cmEl('p',text));if(extra){p.append(cmEl('br'),cmData(cmEl('small',extra)));}box.append(p);};
  line(t('Jij: {naam}',{naam:self.name}),cmiAiDetails(self));
  for(const text of [self.context,appIdentity.context])if(text)box.append(cmData(cmEl('p',text,'note')));
  for(const p of people){
    const rel=(p.relations||[]).map(cmiRelationName).join(', '),st=(p.status||[]).map(code=>cmiStatusName(code)).filter(Boolean).join(', ');
    line(rel?t('{naam}: {relaties}',{naam:p.name,relaties:st?rel+' ('+st+')':rel}):p.name,cmiAiDetails(p));
    for(const text of [p.detail,p.context])if(text)box.append(cmData(cmEl('p',text,'note')));
  }
  for(const l of appIdentity.links||[]){const st=l.status?cmiStatusName(l.status):'';line(st?t('{naam} is {relatie} van {ander} ({status}).',{naam:l.naam,relatie:cmiRelationName(l.relatie).toLowerCase(),ander:l.van,status:st}):t('{naam} is {relatie} van {ander}.',{naam:l.naam,relatie:cmiRelationName(l.relatie).toLowerCase(),ander:l.van}));}
  $('#cmi-ai-summary').textContent=tn(people.length,'De assistent kent jou ({naam}) en één persoon.','De assistent kent jou ({naam}) en {n} personen.',{naam});
}
/* Korte stand voor de rij Wat weet de assistent? (ook op slot leesbaar). */
function cmiAiStand(){const self=appIdentity.self,n=(appIdentity.people||[]).length;if(!self)return t('Weet niets uit Stamgegevens');const naam=self.callname||self.name;return n?tn(n,'Kent {naam} en één persoon','Kent {naam} en {n} personen',{naam}):t('Kent alleen {naam}',{naam});}
function cmiStatusName(code){for(const rel of ['partner','ouder']){const c=(cmiStatusChoices(rel)||[]).find(x=>x[0]===code);if(c)return c[1].toLowerCase();}return '';}
function cmiAge(iso){if(!/^\d{4}-\d{2}-\d{2}$/.test(iso||''))return -1;const [y,m,d]=iso.split('-').map(Number),[ty,tm,td]=rgDayKey(Date.now()).split('-').map(Number);let n=ty-y;if(tm<m||tm===m&&td<d)n--;return n;}
/* Wat de assistent daarnaast weet (volgens de vinkjes): leeftijd, woonplaats, documenten met geldigheid, gekoppeld contact. */
function cmiAiDetails(o){
  const parts=[];
  if(o.geboortedatum){const n=cmiAge(o.geboortedatum);parts.push(n>=0?t('geboren {datum} ({n} jaar)',{datum:fmtDate(o.geboortedatum),n:fmtNum(n)}):t('geboren {datum}',{datum:fmtDate(o.geboortedatum)}));}
  if(o.plaats)parts.push(t('woont in {plaats}',{plaats:o.plaats}));
  for(const d of o.documents||[])parts.push(d.geldig?t('{soort} geldig tot {datum}',{soort:cmiDocSoort({soort:d.soort}).toLowerCase(),datum:fmtDate(d.geldig)}):cmiDocSoort({soort:d.soort}).toLowerCase());
  if(o.contact)parts.push(t('gekoppeld aan een contact'));
  return parts.join(' · ');
}
function cmiNew(type){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;cmNew();if(!cm.edit||cm.edit.id)return;cm.edit.type=type;cm.edit.fields=cmiDefaults(type);if(type==='profiel')cm.edit.label=t('Mijn app-profiel');cmEditor();}
function cmiAfterListForm(){
  if(cmi.newType!=='formulier')return false;cmi.newType='';
  const own=cm.records.filter(r=>r.type==='formulier');if(own.length===1)cmEdit(own[0].id,'*',{jump:true});else{cmiNew('formulier');if(cm.edit&&!cm.edit.id)cm.back={jump:true};if(own.length)cmNote(t('Je hebt al vaste gegevens; dit zijn nieuwe, bijvoorbeeld voor een ander kind. Bestaande vind je in de lijst.'));}
  return true;
}
/* Relaties in gewone woorden: "{naar} is {relatie} van {van}". Namen zijn de weergavenamen uit het overzicht. */
function cmiPersonName(id){const p=cm.records.find(r=>r.id===id&&r.type==='persoon');return p?p.label:'';}
function cmiRelationText(f,soort,me=typeof cmkMe==='function'?cmkMe():''){
  const naam=cmiPersonName(f.naarPersoon),ander=cmiPersonName(f.vanPersoon);if(!naam||!ander||!f.relatie)return '';
  const relatie=cmiRelationName(f.relatie).toLowerCase(),status=(cmiStatusChoices(f.relatie)||[]).find(c=>c[0]&&c[0]===f.status);
  // Vanuit jou (Stamgegevens met jou centraal): "Zoë is je dochter", "Je bent kind van Piet".
  if(me&&f.relatie!=='anders'&&(f.vanPersoon===me||f.naarPersoon===me)&&f.vanPersoon!==f.naarPersoon){
    const mijn=f.vanPersoon===me;
    if(soort==='label')return (mijn?t('{naam} (mijn {relatie})',{naam,relatie}):t('Ik ben {relatie} van {ander}',{ander,relatie})).slice(0,120);
    const st=status?status[1].toLowerCase():'';
    if(mijn)return st?t('{naam} is je {relatie} ({status}).',{naam,relatie,status:st}):t('{naam} is je {relatie}.',{naam,relatie});
    return st?t('Je bent {relatie} van {ander} ({status}).',{relatie,ander,status:st}):t('Je bent {relatie} van {ander}.',{relatie,ander});
  }
  if(soort==='label')return (f.relatie==='anders'?t('{naam} en {ander}',{naam,ander}):t('{naam} ({relatie} van {ander})',{naam,relatie,ander})).slice(0,120);
  if(f.relatie==='anders')return t('{naam} heeft een andere relatie met {ander}.',{naam,ander});
  return status?t('{naam} is {relatie} van {ander} ({status}).',{naam,relatie,ander,status:status[1].toLowerCase()}):t('{naam} is {relatie} van {ander}.',{naam,relatie,ander});
}
/* Namen die als automatisch tellen voor een relatie: vanuit jou en de algemene vorm. */
function cmiRelationLabels(f){return [cmiRelationText(f,'label',''),cmiRelationText(f,'label')].filter(Boolean);}
function cmiRelationChange(key){
  const f=cm.edit.fields;
  if(key==='relatie'){
    if(f.status&&!(cmiStatusChoices(f.relatie)||[]).some(c=>c[0]===f.status))delete f.status;
    const focus=document.activeElement?.id;cmEditorFields();if(focus&&$('#'+focus))$('#'+focus).focus();
  }
  cmiRelationSentence();
}
function cmiRelationSentence(){const zin=cm.edit?.type==='relatie'?cmiRelationText(cm.edit.fields,'zin'):'';$('#cmi-relation-zin').textContent=zin;$('#cmi-relation-zin').hidden=!zin;}
/* Documenten bij een persoon: verloopt een document binnenkort, dan staat dat bij de persoon en in de lijst. */
const CMI_DOC_SOON=183; // dagen: veel landen vragen tot zes maanden geldigheid na terugkomst
function cmiDays(iso){if(!/^\d{4}-\d{2}-\d{2}$/.test(iso||''))return null;const [y,m,d]=iso.split('-').map(Number),[ty,tm,td]=rgDayKey(Date.now()).split('-').map(Number);return Math.round((Date.UTC(y,m-1,d)-Date.UTC(ty,tm-1,td))/864e5);}
function cmiDocSoort(r){const c=(cmiChoices('documentsoort')||[]).find(x=>x[0]===r.soort);return c&&r.soort?c[1]:t('Document');}
function cmiDocStand(r,owner){
  const n=cmiDays(r.geldig),soort=cmiDocSoort(r);
  const geldig=n===null?t('geen vervaldatum'):n<0?t('verlopen op {datum}',{datum:fmtDate(r.geldig)}):n===0?t('verloopt vandaag'):n<=CMI_DOC_SOON?tn(n,'verloopt morgen','verloopt over {n} dagen'):t('geldig tot {datum}',{datum:fmtDate(r.geldig)});
  return owner?t('{soort} van {naam} · {geldig}',{soort,naam:owner.label,geldig}):t('{soort} · {geldig}',{soort,geldig});
}
function cmiDocLate(r){const n=cmiDays(r.geldig);return n!==null&&n<=CMI_DOC_SOON;}
function cmiPersonLate(p){return cm.records.filter(r=>r.type==='identiteit'&&r.person===p.id&&cmiDocLate(r));}
function cmiPersonStand(p){
  const docs=cm.records.filter(r=>r.type==='identiteit'&&r.person===p.id),late=cmiPersonLate(p);
  if(late.some(r=>cmiDays(r.geldig)<0))return t('Persoon · een document is verlopen');
  if(late.length)return t('Persoon · een document verloopt binnenkort');
  return docs.length?tn(docs.length,'Persoon · één document','Persoon · {n} documenten'):t('Persoon');
}
function cmiEditor(){
  const r=cm.edit;$('#cmi-form-note').hidden=r.type!=='formulier';$('#cmi-form-sign').hidden=r.type!=='formulier';$('#cmi-relation-note').hidden=r.type!=='relatie';
  if(r.type==='formulier'){sigLoad();const old=$('#cmi-form-sig');if(old)old.replaceWith(cmSigStand('cmi-form-sig',false));}
  if(cmi.gevEdit!==r){cmi.gevEdit=r;cmi.gevers=null;}cmiFormGezag();
  cmiRelationSentence();
  cmiPhotoClear();$('#cmi-photos').hidden=r.type!=='identiteit';
  if(r.type==='identiteit')for(const side of ['front','back']){$('#cmi-'+side+'-status').textContent=r.images?.[side]?t('foto opgeslagen'):t('nog geen foto');$('#cmi-'+side+'-delete').disabled=!r.images?.[side];}
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
function cmiAfterList(){
  if(cmiAfterListForm())return;
  // Vanuit Formulieren (Vaste gegevens, Reizen): meteen die lijst; terug gaat weer naar Formulieren
  if(cmi.newLijst){const l=cmi.newLijst;cmi.newLijst='';cm.jumpBack=true;cmLijst(l);return;}
  if(!cmi.reopen)return;const id=cmi.reopen;cmi.reopen='';cmKaartOpen(id,true,t('Kaartfoto opgeslagen.'));
}
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
function cmiRelationsClear(){$('#cmi-relation-zin').textContent='';$('#cmi-relation-zin').hidden=true;}
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
