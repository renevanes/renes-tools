/* Stamgegevens vanuit jou. Het beginscherm: jij (of Begin met jezelf), Aandacht nodig, Persoon toevoegen en een rij per soort
 * (Mijn mensen, Documenten, Adressen, Organisaties, Wat weet de assistent?). Een lijst toont één soort; Mijn mensen is
 * gegroepeerd naar wat ze voor jou zijn (Gezin, Familie, …). Een persoon toevoegen begint met "Wie is het voor jou?": de
 * persoon en de relatie met jou worden samen bewaard. Wie "ik" is, staat in het app-profiel. Zoeken toont één lijst met alles. */
const cmk={rel:'',nieuw:false};
/* Wat de ander voor jou is als de relatie andersom is vastgelegd ("Rene is kind van Piet" → Piet is je ouder). */
const CMK_ANDERSOM={kind:'ouder',zoon:'ouder',dochter:'ouder',ouder:'kind',vader:'kind',moeder:'kind',grootouder:'kleinkind',opa:'kleinkind',oma:'kleinkind',
  kleinkind:'grootouder',kleinzoon:'grootouder',kleindochter:'grootouder',broerzus:'broerzus',broer:'broerzus',zus:'broerzus',oomtante:'neefnicht',
  schoonouder:'schoonkind',schoonkind:'schoonouder',stiefouder:'stiefkind',stiefkind:'stiefouder',pleegouder:'pleegkind',pleegkind:'pleegouder',voogd:'pupil',pupil:'voogd',
  partner:'partner',expartner:'expartner',zwagerschoonzus:'zwagerschoonzus',familie:'familie',vriend:'vriend',collega:'collega',buur:'buur',huisgenoot:'huisgenoot'};
// Wat de ander voor jou is, als jij de ouder bent: de status (ouderlijk gezag) gaat dan over jou
const CMK_KIND=['kind','zoon','dochter','stiefkind','pleegkind'];
function cmkMe(){const p=cm.records.find(r=>r.type==='profiel');const id=p?.person||'';return id&&cm.records.some(r=>r.id===id&&r.type==='persoon')?id:'';}
/* Wat deze persoon voor jou is: [{code, orig, rel, status}] (code = wat de ander voor jou is; orig = alleen andersom vastgelegd). */
function cmkToMe(id,me){
  const out=[];if(!me||id===me)return out;
  for(const r of cm.records){
    if(r.type!=='relatie'||!r.relatie)continue;
    if(r.van===me&&r.naar===id)out.push({code:r.relatie,rel:r.id,status:r.status||''});
    else if(r.naar===me&&r.van===id)out.push({code:CMK_ANDERSOM[r.relatie]||'',orig:r.relatie,rel:r.id,status:r.status||''});
  }
  return out;
}
/* "anders" is geen woord voor in een zin: dan "andere relatie". Neef of nicht kan beide kanten op: dan "jij bent … van". */
function cmkRelName(code){return code==='anders'?t('andere relatie'):cmiRelationName(code).toLowerCase();}
function cmkRelText(x,naam){return x.code?(x.code==='anders'?t('andere relatie met jou'):t('mijn {relatie}',{relatie:cmkRelName(x.code)})):t('jij bent {relatie} van {naam}',{relatie:cmkRelName(x.orig),naam});}
/* De status in gewone woorden, vanuit jou: bij je kind gaat ouderlijk gezag over jou, bij je ouder over die ouder. */
function cmkStatusText(x){
  if(!x.status)return '';if(x.code==='partner')return cmiStatusName(x.status);
  const kind=CMK_KIND.includes(x.code);
  return x.status==='gezag'?(kind?t('ik heb ouderlijk gezag'):t('heeft ouderlijk gezag')):(kind?t('ik heb geen ouderlijk gezag'):t('heeft geen ouderlijk gezag'));
}
function cmkWieText(x,naam){
  const rel=x.code?(x.code==='anders'?t('Andere relatie met jou'):t('Mijn {relatie}',{relatie:cmkRelName(x.code)})):t('Jij bent {relatie} van {naam}',{relatie:cmkRelName(x.orig),naam});
  const st=cmkStatusText(x);return st?t('{relatie} · {status}',{relatie:rel,status:st}):rel;
}
function cmkStatusVraag(code){return code==='partner'?t('Hoe zijn jullie samen?'):CMK_KIND.includes(code)?t('Heb jij ouderlijk gezag?'):t('Heeft deze ouder ouderlijk gezag over jou?');}
function cmkStatusKeuzes(code){
  if(!cmiStatusChoices(code))return null;
  if(code==='partner')return [...cmiStatusChoices(code).filter(c=>c[0]),['',t('Niet invullen')]];
  return CMK_KIND.includes(code)?[['gezag',t('Ja, ik heb ouderlijk gezag')],['geengezag',t('Nee, ik heb geen ouderlijk gezag')],['',t('Niet invullen')]]
    :[['gezag',t('Ja, heeft ouderlijk gezag')],['geengezag',t('Nee, heeft geen ouderlijk gezag')],['',t('Niet invullen')]];
}
function cmkGroup(code){const g=CMI_RELATION_GROUPS.find(([,codes])=>codes.includes(code));return g?g[0]:'Anderen';}
/* Documentstand zonder "Persoon": "één document", "een document is verlopen", … */
function cmkDocStand(p){
  const docs=cm.records.filter(r=>r.type==='identiteit'&&r.person===p.id),late=cmiPersonLate(p);
  if(late.some(r=>cmiDays(r.geldig)<0))return t('een document is verlopen');
  if(late.length)return t('een document verloopt binnenkort');
  return docs.length?tn(docs.length,'één document','{n} documenten'):'';
}
/* Vaste gegevens reistoestemming: wat nog ontbreekt om het formulier te kunnen invullen. */
// taal-vast: namen worden met t() vertaald
const CMK_FORM_NODIG={kind:'kind',kindDocument:'reisdocument van het kind',gever1:'toestemminggever',gever1Document:'identiteitsbewijs van de toestemminggever',begeleider1:'begeleider',begeleider1Document:'reisdocument van de begeleider'};
function cmkFormMissing(r){
  const f=r.refs||{},need=['kind','kindDocument','gever1','gever1Document'];if(r.alleen!=='ja')need.push('begeleider1','begeleider1Document');
  return need.filter(k=>!f[k]||!cm.records.some(x=>x.id===f[k]));
}
function cmkSmall(r,me){
  if(r.type==='persoon'){
    if(r.id===me)return t('Dit ben jij');
    const wie=cmkToMe(r.id,me).map(x=>cmkWieText(x,r.label)),stand=cmkDocStand(r),parts=[...wie,stand].filter(Boolean);
    return parts.length?parts.join(' · '):me?t('Tik om vast te leggen wat deze persoon voor je is'):t('Persoon');
  }
  if(r.type==='identiteit'){const owner=cm.records.find(p=>p.id===r.person);return cmiDocStand(r,owner&&!r.label.includes(owner.label)?owner:null);}
  if(r.type==='relatie')return cmiRelationText({vanPersoon:r.van,naarPersoon:r.naar,relatie:r.relatie,status:r.status},'zin')||t('Relatie');
  if(r.type==='formulier'){const miss=cmkFormMissing(r);return miss.length?t('Nog in te vullen: {velden}',{velden:miss.map(k=>t(CMK_FORM_NODIG[k])).join(', ')}):t('Klaar om in te vullen bij Formulieren');}
  if(r.type==='reis'&&r.van)return r.tot?t('{van} tot {tot}',{van:fmtDate(r.van),tot:fmtDate(r.tot)}):fmtDate(r.van);
  return t(CM_TYPES[r.type]);
}
function cmkWarn(r){return r.type==='persoon'?cmiPersonLate(r).length>0:r.type==='identiteit'?cmiDocLate(r):r.type==='formulier'?cmkFormMissing(r).length>0:false;}
/* Openen vanuit een lijst: het app-profiel is het scherm Wat weet de assistent?, de rest een visitekaartje. */
function cmkOpen(r){if(r.type==='profiel')cmaiOpen();else cmKaartOpen(r.id);}

/* Het beginscherm */
cmRenderList=function(){
  const q=$('#cm-search').value.trim().toLowerCase();
  $('#cm-zoek').hidden=!q;$('#cm-hub').hidden=!!q;$('#cm-list').replaceChildren();
  if(!cmOpen())return;
  if(q)cmkZoek(q);else cmkHub();
  if(current==='cmlijst')cmLijstRender();
  if(current==='cmai'&&typeof cmaiRender==='function')cmaiRender();
};
function cmkZoek(q){
  const box=$('#cm-list'),me=cmkMe();
  const hits=cm.records.filter(r=>r.label.toLowerCase().includes(q)||t(CM_TYPES[r.type]).toLowerCase().includes(q)).sort((a,b)=>a.label.localeCompare(b.label));
  for(const r of hits)box.append(cmRow(r,cmkSmall(r,me),cmkWarn(r),()=>cmkOpen(r)));
  if(!hits.length)box.append(cmEl('p',t('Niets gevonden.'),'note'));
}
function cmkHub(){
  const me=cmkMe(),ik=$('#cm-ik');ik.replaceChildren();
  if(me){const p=cm.records.find(r=>r.id===me),stand=cmkDocStand(p);ik.append(cmRow(p,stand?t('Dit ben jij · {stand}',{stand}):t('Dit ben jij'),cmiPersonLate(p).length>0,()=>cmKaartOpen(me),'user'));}
  else{
    const wrap=cmEl('div',undefined,'cmk-start'),acts=cmEl('div',undefined,'row cmi-actions');
    wrap.append(cmEl('h2',t('Begin met jezelf'),'cm-kop'),cmEl('p',t('Jij staat centraal: je eigen gegevens en documenten, en iedereen anders met wat die voor jou is (je kind, partner, ouder …).'),'hint'));
    const add=(id,text,fn)=>{const b=cmEl('button',text,'mini');b.type='button';b.id=id;b.onclick=fn;acts.append(b);};
    if(typeof Android.masterContactPick==='function')add('cmk-self-contact',t('Kies uit Contacten'),cmkSelfContact);
    add('cmk-self-new',t('Zelf invullen'),cmkSelfNew);
    if(cm.records.some(r=>r.type==='persoon'))add('cmk-self-pick',t('Ik sta er al tussen'),cmkSelfPick);
    wrap.append(acts);ik.append(wrap);
  }
  const items=cmkAandacht(me),list=$('#cm-aandacht-list');list.replaceChildren(...items.slice(0,3));$('#cm-aandacht').hidden=!items.length;
  const people=cm.records.filter(r=>r.type==='persoon'&&r.id!==me),docs=cm.records.filter(r=>r.type==='identiteit'),late=docs.filter(cmiDocLate),count=type=>cm.records.filter(r=>r.type===type).length;
  const nDocs=tn(docs.length,'Eén document','{n} documenten'),nAdres=count('adres'),nOrg=count('organisatie');
  $('#cm-stand-mensen').textContent=people.length?tn(people.length,'Eén persoon','{n} personen'):t('Nog niemand');
  $('#cm-stand-documenten').textContent=!docs.length?t('Nog geen documenten'):late.length?t('{documenten} · {aandacht}',{documenten:nDocs,aandacht:tn(late.length,'één vraagt aandacht','{n} vragen aandacht')}):nDocs;
  $('#cm-stand-adressen').textContent=nAdres?tn(nAdres,'Eén adres','{n} adressen'):t('Nog geen adressen');
  $('#cm-stand-organisaties').textContent=nOrg?tn(nOrg,'Eén organisatie','{n} organisaties'):t('Nog geen organisaties');
  $('#cm-stand-assistent').textContent=cmiAiStand();
}
/* Aandacht nodig: tekst over jou die nog naar Over mij moet, documenten die (bijna) verlopen en vaste gegevens die niet af zijn. */
function cmkAandacht(me){
  const out=[];
  if(me){const p=cm.records.find(r=>r.id===me),prof=cm.records.find(r=>r.type==='profiel');if(p?.tekst||prof?.tekst)out.push(cmRow(null,t('Zet hem bij Over mij van de assistent, of haal hem weg'),true,()=>cmKaartOpen(me,true,'',true),'user',t('Tekst over jou')));}
  const docs=cm.records.filter(r=>r.type==='identiteit'&&cmiDocLate(r)).sort((a,b)=>(a.geldig||'').localeCompare(b.geldig||''));
  for(const d of docs)out.push(cmRow(d,cmkSmall(d,me),true,()=>cmKaartOpen(d.id)));
  for(const f of cm.records.filter(r=>r.type==='formulier'&&cmkFormMissing(r).length))out.push(cmRow(f,cmkSmall(f,me),true,()=>cmKaartOpen(f.id)));
  return out;
}

/* Lijsten per soort (één scherm, cm.here.lijst zegt welke). taal-vast: titels en teksten worden met t() vertaald */
const CM_LIJSTEN={mensen:['Mijn mensen','Persoon toevoegen','user','Nog niemand. Voeg je kind, partner, ouder of een ander toe.'],
  documenten:['Documenten','Document toevoegen','document','Nog geen documenten.'],adressen:['Adressen','Adres toevoegen','pin','Nog geen adressen.'],
  organisaties:['Organisaties','Organisatie toevoegen','shop','Nog geen organisaties.'],reizen:['Reizen','Reis toevoegen','route','Nog geen reizen.'],
  vast:['Vaste gegevens reistoestemming','Vaste gegevens toevoegen','clipboard','Nog geen vaste gegevens.']};
const CM_LIJST_TYPE={documenten:'identiteit',adressen:'adres',organisaties:'organisatie',reizen:'reis',vast:'formulier'};
function cmLijst(l){if(!cmOpen()||!CM_LIJSTEN[l]||!cm.recordsReady)return;cmGo({scr:'cmlijst',lijst:l});}
function cmLijstNu(){return cm.here?.scr==='cmlijst'?cm.here.lijst:'';}
function cmLijstRender(){
  const l=cmLijstNu(),def=CM_LIJSTEN[l],box=$('#cm-lijst');box.replaceChildren();if(!def)return;
  $('#cm-lijst-title').textContent=t(def[0]);$('#cm-lijst-add').textContent=t(def[1]);if(!cmOpen())return;
  const me=cmkMe();
  if(l==='mensen')cmkMensen(box,me);
  else{
    const list=cm.records.filter(r=>r.type===CM_LIJST_TYPE[l]);
    list.sort(l==='documenten'?(a,b)=>(a.geldig||'9999').localeCompare(b.geldig||'9999')||a.label.localeCompare(b.label):l==='reizen'?(a,b)=>(b.van||'').localeCompare(a.van||''):(a,b)=>a.label.localeCompare(b.label));
    for(const r of list)box.append(cmRow(r,cmkSmall(r,me),cmkWarn(r),()=>cmKaartOpen(r.id)));
  }
  if(!box.children.length)box.innerHTML=uiEmpty(def[2],t(def[3]));
}
function cmkMensen(box,me){
  const groups=new Map();
  for(const p of cm.records.filter(r=>r.type==='persoon'&&r.id!==me)){
    const rels=cmkToMe(p.id,me),first=rels.find(x=>x.code)||rels[0],key=me?(first?.code?cmkGroup(first.code):first?'Anderen':''):'';
    if(!groups.has(key))groups.set(key,[]);groups.get(key).push(p);
  }
  for(const key of [...CMI_RELATION_GROUPS.map(g=>g[0]),'']){
    const list=groups.get(key);if(!list)continue;
    if(me)box.append(cmEl('h3',key?t(key):t('Nog geen relatie met jou'),'cm-groep'));
    list.sort((a,b)=>a.label.localeCompare(b.label));
    for(const p of list)box.append(cmRow(p,cmkSmall(p,me),cmkWarn(p),()=>cmKaartOpen(p.id)));
  }
}
function cmLijstAdd(){
  const l=cmLijstNu();if(!cmOpen()||cm.mutating||!cm.recordsReady||!CM_LIJSTEN[l])return;
  if(l==='mensen')cmkPersonNew();else if(l==='documenten')cmkDocWie();else cmiNew(CM_LIJST_TYPE[l]);
}
function cmkDocWie(){
  const me=cmkMe(),people=cm.records.filter(r=>r.type==='persoon').sort((a,b)=>(b.id===me)-(a.id===me)||a.label.localeCompare(b.label));
  if(!people.length){cmNote(t('Voeg eerst een persoon toe.'));return;}
  if(people.length===1){cmcDocAdd(people[0].id);return;}
  openSheet(t('Van wie is het document?'),'',people.map(p=>[p.id===me?t('Van mij ({naam})',{naam:p.label}):p.label,()=>cmcDocAdd(p.id),'user']));
  for(const b of document.querySelectorAll('#sheet-acts button'))b.setAttribute('translate','no');
}

/* Heen en terug binnen Stamgegevens: lijsten en visitekaartjes op een eigen stapel (cm.nav); terug gaat één stap terug, en
 * uiteindelijk naar het beginscherm, of naar Formulieren als je daarvandaan kwam (cm.jumpBack). */
function cmGo(entry,push=true,note=''){
  if(!cmOpen())return;
  const here=cm.here,same=here&&here.scr===entry.scr&&here.id===entry.id&&here.lijst===entry.lijst;
  if(push&&here&&!same&&['cmlijst','cmkaart'].includes(current))cm.nav.push(here);
  cm.here={scr:entry.scr,id:entry.id,lijst:entry.lijst};
  if(entry.scr==='cmlijst'){cmc.epoch++;if(current!=='cmlijst')show('cmlijst');$('#cm-lijst-status').textContent='';cmLijstRender();cmNavDone();if(note)cmNote(note);return;}
  cmKaartLoad(entry.id,note);
}
function cmBack(){
  if(!['cmlijst','cmkaart'].includes(current))return false;
  if(cm.mutating)return true;
  const prev=cm.nav.pop();if(prev){cmGo(prev,false);return true;}
  const jump=jumpStack[jumpStack.length-1];
  if(cm.jumpBack&&jump?.to==='masterdata'){cm.jumpBack=false;jumpStack.pop();show(jump.from);return true;}
  show('masterdata');return true;
}

/* Mijzelf: een nieuwe persoon (uit Contacten of zelf ingevuld); na opslaan wordt hij jouw persoon in het app-profiel. */
function cmkSelfContact(){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;cmContactPick(c=>cmkSelfEdit(c));}
function cmkSelfNew(){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;cmkSelfEdit(null);}
function cmkSelfEdit(contact){
  cmiNew('persoon');if(!cm.edit||cm.edit.id)return;cmk.nieuw=true;cmk.rel='';
  if(contact){cmContactFill(cm.edit.fields,contact);cmEditor();}
  $('#cm-editor-title').textContent=t('Mijzelf toevoegen');
}
function cmkSelfPick(){
  if(!cmOpen()||cm.mutating)return;
  const people=cm.records.filter(r=>r.type==='persoon').sort((a,b)=>a.label.localeCompare(b.label));
  openSheet(t('Wie ben jij?'),'',people.map(p=>[p.label,()=>cmkSelfSet(p.id),'user']));
  for(const b of document.querySelectorAll('#sheet-acts button'))b.setAttribute('translate','no');
}
/* Jouw persoon vastleggen in het app-profiel (bestaand profiel bijwerken, anders een nieuw; delen blijft zoals het was of uit). */
function cmkSelfSet(id,after,failed){
  if(!cmOpen())return;
  const profile=cm.records.find(r=>r.type==='profiel'),done=()=>{cmBusy(false);appIdentityRefresh();cmListLoad(after||(()=>{if(current==='cmkaart'&&cm.here?.id===id){cmKaartLoad(id,t('Vastgelegd: dit ben jij.'));return;}if(current!=='masterdata')show('masterdata');cmNote(t('Vastgelegd: dit ben jij.'));}));};
  const fail=()=>{cmBusy(false);if(failed)failed();};
  cmBusy(true);
  const save=rec=>cmRequest('masterSave',[JSON.stringify(rec)],done,false,fail);
  if(profile)cmRequest('masterRead',[profile.id,'masterdata',''],r=>{const rec=r.record;rec.fields.persoon=id;save(rec);},false,fail);
  else save({type:'profiel',label:t('Mijn app-profiel'),fields:{...cmiDefaults('profiel'),persoon:id},revision:0});
}

/* Persoon toevoegen vanuit jou: eerst wat het voor jou is, dan uit Contacten of zelf invullen; persoon en relatie samen bewaard. */
const CMK_VAAK=['kind','partner','ouder','broerzus','grootouder','kleinkind','vriend','noodcontact'];
function cmkPersonNew(){
  if(!cmOpen()||cm.mutating||!cm.recordsReady)return;if(cm.edit&&cm.dirty){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  if(!cmkMe()){cmkSource('');return;}
  const acts=CMK_VAAK.map(code=>[t('Mijn {relatie}',{relatie:cmkRelName(code)}),()=>cmkSource(code),'user']);
  acts.push([t('Andere relatie…'),()=>cmkMeer(code=>cmkSource(code),t('Wie is het voor jou?')),'more'],[t('Iemand anders, geen relatie met mij'),()=>cmkSource('-'),'user']);
  openSheet(t('Wie is het voor jou?'),t('De persoon en de relatie met jou worden samen bewaard.'),acts);
}
function cmkMeer(pick,title){
  const acts=[];
  for(const [group,codes] of CMI_RELATION_GROUPS)for(const code of codes.filter(c=>c!=='anders'&&!CMK_VAAK.includes(c)))acts.push([t('Mijn {relatie} ({groep})',{relatie:cmkRelName(code),groep:t(group).toLowerCase()}),()=>pick(code),'user']);
  acts.push([t('Mijn … (andere relatie)'),()=>pick('anders'),'user']);
  openSheet(title,'',acts);
}
/* Uit Contacten (naam, telefoon, e-mail, adres en verjaardag) of zelf invullen. */
function cmkSource(code){
  if(typeof Android.masterContactPick!=='function'){cmkPersonFor(code,null);return;}
  openSheet(code&&code!=='-'&&code!=='anders'?t('Mijn {relatie} toevoegen',{relatie:cmkRelName(code)}):t('Persoon toevoegen'),t('Uit Contacten neem je naam, telefoon, e-mail, adres en verjaardag over; telefoon en e-mail blijven daarna actueel.'),
    [[t('Kies uit Contacten'),()=>cmContactPick(c=>cmkPersonFor(code,c)),'user'],[t('Zelf invullen'),()=>cmkPersonFor(code,null),'edit']]);
}
function cmkPersonFor(code,contact){
  if(!cmOpen()||cm.mutating||!cm.recordsReady)return;
  cmiNew('persoon');if(!cm.edit||cm.edit.id)return;const rel=code==='-'?'':code;cmk.rel=rel;cmk.nieuw=false;
  if(rel)cm.edit.fields.delen='ja';
  if(contact){cmContactFill(cm.edit.fields,contact);cmEditor();}
  $('#cm-editor-title').textContent=rel&&rel!=='anders'?t('Mijn {relatie} toevoegen',{relatie:cmkRelName(rel)}):t('Persoon toevoegen');
}
const cmkSave=cmSave;cmSave=function(){
  const r=cm.edit,me=cmkMe();
  if(!r||r.id||r.type!=='persoon'||!cmOpen()||cm.mutating||!cmk.nieuw&&!(cmk.rel&&me)){cmkSave();return;}
  const need=cmNeedName(r);if(need){cmShowField(need[0]);cmNote(need[1]);return;}
  r.label=cmLabel(r);const rel=cmk.rel,self=cmk.nieuw;
  cmBusy(true);cmRequest('masterSave',[JSON.stringify(r)],out=>{
    const id=out.record.id;cmk.rel='';cmk.nieuw=false;cm.edit=null;cm.dirty=false;cmClearEdited();
    // Een melding na een fout komt na de foutmelding zelf (die zet onMasterReply), zodat je ziet wat er wel bewaard is.
    const later=text=>setTimeout(()=>cmNote(text),0);
    if(self){cmkSelfSet(id,()=>{cmAfterEdit(true,id);cmNote(t('Welkom! Dit ben jij. Voeg nu je documenten en je mensen toe.'));},()=>{cmAfterEdit(true,id);later(t('Je bent toegevoegd, maar nog niet aangewezen als jezelf. Kies Ik sta er al tussen.'));});return;}
    // De nieuwe persoon staat nog niet in cm.records: naam voor het label zelf invullen
    const relatie={type:'relatie',label:(rel==='anders'?t('{naam} en {ander}',{naam:r.label,ander:cmiPersonName(me)}):t('{naam} (mijn {relatie})',{naam:r.label,relatie:cmkRelName(rel)})).slice(0,120),fields:{vanPersoon:me,naarPersoon:id,relatie:rel,delen:'ja'},revision:0};
    if(!cmOpen()){cmBusy(false);return;}
    const ask=!!cmkStatusKeuzes(rel);
    cmRequest('masterSave',[JSON.stringify(relatie)],()=>{cmBusy(false);cmAfterEdit(true,id);cmNote(rel==='anders'?t('{naam} is toegevoegd.',{naam:r.label}):ask?t('{naam} is toegevoegd als je {relatie}. Tik op de relatie om de status in te vullen.',{naam:r.label,relatie:cmkRelName(rel)}):t('{naam} is toegevoegd als je {relatie}.',{naam:r.label,relatie:cmkRelName(rel)}));appIdentityRefresh();},false,()=>{cmBusy(false);if(cmOpen())cmAfterEdit(true,id);later(t('{naam} is toegevoegd, maar de relatie met jou niet. Tik op Wie is dit voor jou? op het kaartje.',{naam:r.label}));});
  },false,()=>cmBusy(false));
};
const cmkAfter=cmAfterEdit;cmAfterEdit=function(saved,savedId){cmk.rel='';cmk.nieuw=false;cmkAfter(saved,savedId);};
const cmkClear=cmClearCentral;cmClearCentral=function(){
  cmk.rel='';cmk.nieuw=false;
  // Een open keuzelijst met namen (Wie ben jij?, Relatie tussen anderen) sluit mee bij vergrendelen.
  if($('#sheet')?.classList.contains('on')&&cmZone(current))closeSheet();
  cmkClear();
};
/* De hoofdknop op het beginscherm */
function cmAddMenu(){cmkPersonNew();}

/* Wie is dit voor jou? (de chip op het visitekaartje): relatie met jou vastleggen, wijzigen of weghalen, met de status. */
function cmkWie(id){
  const me=cmkMe();if(!cmOpen()||cm.mutating||!me||id===me)return;
  const naam=cmiPersonName(id),cur=cmkToMe(id,me)[0],acts=[];
  if(cur&&cmkStatusKeuzes(cur.code))acts.push([t('Status wijzigen'),()=>cmkWieStatus(id,cur.code),'edit']);
  for(const code of CMK_VAAK)acts.push([t('Mijn {relatie}',{relatie:cmkRelName(code)}),()=>cmkWieKies(id,code),'user']);
  acts.push([t('Andere relatie…'),()=>cmkMeer(code=>cmkWieKies(id,code),t('Wie is {naam} voor jou?',{naam})),'more']);
  if(cur)acts.push([t('Geen relatie met mij'),()=>cmkWieWeg(id),'trash']);
  openSheet(t('Wie is {naam} voor jou?',{naam}),cur?cmkWieText(cur,naam):t('Nog niet vastgelegd.'),acts);
}
function cmkWieKies(id,code){if(cmkStatusKeuzes(code))cmkWieStatus(id,code);else cmkWieSave(id,code,'');}
function cmkWieStatus(id,code){openSheet(t('Status'),cmkStatusVraag(code),cmkStatusKeuzes(code).map(([v,label])=>[label,()=>cmkWieSave(id,code,v),v?'check':'close']));}
function cmkWieSave(id,code,status){
  const me=cmkMe();if(!cmOpen()||cm.mutating||!me)return;const cur=cmkToMe(id,me)[0];
  const fields={vanPersoon:me,naarPersoon:id,relatie:code};if(status)fields.status=status;
  const label=cmiRelationText(fields,'label'),naam=cmiPersonName(id);
  const fail=()=>cmBusy(false),save=rec=>cmRequest('masterSave',[JSON.stringify(rec)],()=>{cmBusy(false);appIdentityRefresh();cmListLoad(()=>cmKaartLoad(id,t('Vastgelegd: {wie}.',{wie:cmkWieText({code,status},naam)})));},false,fail);
  cmBusy(true);
  if(cur)cmRequest('masterRead',[cur.rel,'masterdata',''],r=>{const rec=r.record,old=rec.fields;rec.fields={...old,...fields,delen:old.delen||'ja'};if(!status)delete rec.fields.status;if(label&&(!rec.label||cmAutoLabels('relatie',old).has(rec.label)))rec.label=label;save(rec);},false,fail);
  else save({type:'relatie',label:label||t('Relatie'),fields:{...fields,delen:'ja'},revision:0});
}
function cmkWieWeg(id){
  const me=cmkMe(),cur=cmkToMe(id,me)[0],rel=cur&&cm.records.find(r=>r.id===cur.rel);if(!rel||!cmOpen()||cm.mutating)return;
  askConfirm(t('Geen relatie met jou?'),t('De relatie met {naam} wordt verwijderd. {naam} zelf blijft staan.',{naam:cmiPersonName(id)}),t('Verwijderen'),()=>{
    if(!cmOpen()||cm.mutating)return;cmBusy(true);
    cmRequest('masterDelete',[rel.id,rel.revision],()=>{cmBusy(false);appIdentityRefresh();cmListLoad(()=>cmKaartLoad(id,t('De relatie met jou is weggehaald.')));});
  });
}
/* Relatie tussen twee anderen (bijvoorbeeld het kind van je partner): vanaf het visitekaartje van een van hen. */
function cmkOthers(id){
  if(!cmOpen()||cm.mutating)return;const naam=cmiPersonName(id);
  openSheet(t('Relatie tussen anderen'),t('Bijvoorbeeld het kind van je partner uit een eerdere relatie.'),[
    [t('Kind van {naam}',{naam}),()=>cmiRelationNew('kind',id),'user'],[t('Ouder van {naam}',{naam}),()=>cmiRelationNew('ouder',id),'user'],
    [t('Partner van {naam}',{naam}),()=>cmiRelationNew('partner',id),'user'],[t('Andere relatie met {naam}',{naam}),()=>cmiRelationNew('',id),'share']]);
}
function cmiRelationNew(relatie,id){
  if(!cmOpen()||cm.mutating)return;
  if(!cm.records.some(r=>r.type==='persoon'&&r.id!==id)){cmNote(t('Voeg eerst de andere persoon toe.'));return;}
  cmiNew('relatie');if(!cm.edit||cm.edit.id)return;cm.edit.fields={vanPersoon:id,naarPersoon:'',relatie,delen:'ja'};cm.back={id};cmEditor();
  const f=$('#cm-field-naarPersoon');if(f)f.focus();
}
