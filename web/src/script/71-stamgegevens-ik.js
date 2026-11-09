/* Stamgegevens vanuit jou: bovenaan "Ik" (wie jij bent, met je documenten), daaronder "Mijn mensen", gegroepeerd naar wat
 * ze voor jou zijn (Gezin, Familie, …), en de rest onder "Overig". Een persoon toevoegen begint met "Wie is het voor jou?":
 * dan worden de persoon en de relatie met jou in één keer bewaard. Relaties tussen twee anderen kunnen nog steeds, als extra
 * keuze bij een persoon. Wie "ik" is, staat in het app-profiel (Dit ben ik). Zoeken en de filters tonen de gewone lijst. */
const cmk={rel:'',nieuw:false};
/* Wat de ander voor jou is als de relatie andersom is vastgelegd ("Rene is kind van Piet" → Piet is je ouder). */
const CMK_ANDERSOM={kind:'ouder',zoon:'ouder',dochter:'ouder',ouder:'kind',vader:'kind',moeder:'kind',grootouder:'kleinkind',opa:'kleinkind',oma:'kleinkind',
  kleinkind:'grootouder',kleinzoon:'grootouder',kleindochter:'grootouder',broerzus:'broerzus',broer:'broerzus',zus:'broerzus',oomtante:'neefnicht',
  schoonouder:'schoonkind',schoonkind:'schoonouder',stiefouder:'stiefkind',stiefkind:'stiefouder',pleegouder:'pleegkind',pleegkind:'pleegouder',voogd:'pupil',pupil:'voogd',
  partner:'partner',expartner:'expartner',zwagerschoonzus:'zwagerschoonzus',familie:'familie',vriend:'vriend',collega:'collega',buur:'buur',huisgenoot:'huisgenoot'};
function cmkMe(){const p=cm.records.find(r=>r.type==='profiel');const id=p?.person||'';return id&&cm.records.some(r=>r.id===id&&r.type==='persoon')?id:'';}
/* Wat deze persoon voor jou is: [{code, andersom}] (code = wat de ander voor jou is; andersom = alleen zo vastgelegd). */
function cmkToMe(id,me){
  const out=[];if(!me||id===me)return out;
  for(const r of cm.records){
    if(r.type!=='relatie'||!r.relatie)continue;
    if(r.van===me&&r.naar===id)out.push({code:r.relatie,rel:r.id});
    else if(r.naar===me&&r.van===id)out.push({code:CMK_ANDERSOM[r.relatie]||'',orig:r.relatie,rel:r.id});
  }
  return out;
}
/* "anders" is geen woord voor in een zin: dan "andere relatie". Neef of nicht kan beide kanten op: dan "jij bent … van". */
function cmkRelName(code){return code==='anders'?t('andere relatie'):cmiRelationName(code).toLowerCase();}
function cmkRelText(x,naam){return x.code?(x.code==='anders'?t('andere relatie met jou'):t('mijn {relatie}',{relatie:cmkRelName(x.code)})):t('jij bent {relatie} van {naam}',{relatie:cmkRelName(x.orig),naam});}
function cmkGroup(code){const g=CMI_RELATION_GROUPS.find(([,codes])=>codes.includes(code));return g?g[0]:'Anderen';}
/* Documentstand zonder "Persoon": "één document", "een document is verlopen", … */
function cmkDocStand(p){
  const docs=cm.records.filter(r=>r.type==='identiteit'&&r.person===p.id),late=cmiPersonLate(p);
  if(late.some(r=>cmiDays(r.geldig)<0))return t('een document is verlopen');
  if(late.length)return t('een document verloopt binnenkort');
  return docs.length?tn(docs.length,'één document','{n} documenten'):'';
}
function cmkRow(r,small,warn,onclick,icon){
  const b=cmData(cmEl('button',undefined,'strow')),ic=cmEl('span',undefined,'stic'),txt=cmEl('span',undefined,'sttxt'),chev=cmEl('span',undefined,'ui-icon stchev');
  b.type='button';ic.innerHTML=uiIcon(icon||CM_ICONS[r.type]||'document');chev.dataset.icon='chevron-right';chev.setAttribute('aria-hidden','true');
  const s=cmEl('small',small);if(warn)s.classList.add('cmi-warn');txt.append(cmEl('b',r.label),s);b.append(ic,txt,chev);b.onclick=onclick;return b;
}
function cmkHead(text,cls){const h=cmEl('h3',text,'cmk-h'+(cls?' '+cls:''));return h;}
/* Het overzicht vanuit jou (alleen bij Alles en zonder zoekwoord). */
function cmkRender(box){
  const me=cmkMe(),people=cm.records.filter(r=>r.type==='persoon'&&r.id!==me);
  // Ik
  if(me){
    const p=cm.records.find(r=>r.id===me),stand=cmkDocStand(p);
    box.append(cmkHead(t('Ik')));
    box.append(cmkRow(p,stand?t('Dit ben jij · {stand}',{stand}):t('Dit ben jij'),cmiPersonLate(p).length>0,()=>cmEdit(me),'user'));
    for(const d of cm.records.filter(r=>r.type==='identiteit'&&r.person===me).sort((a,b)=>(a.geldig||'').localeCompare(b.geldig||'')))
      box.append(cmkRow(d,cmiDocStand(d,null),cmiDocLate(d),()=>cmEdit(d.id,'',{id:me,tab:'documenten'})));
  }else{
    const card=cmEl('div',undefined,'cmk-start');
    card.append(cmEl('h3',t('Begin met jezelf'),'cmk-h'),cmEl('p',t('Jij staat centraal: je eigen gegevens en documenten bovenaan, en iedereen anders met wat die voor jou is (je kind, partner, ouder …).'),'hint'));
    const acts=cmEl('div',undefined,'row cmi-actions'),add=cmEl('button',t('Mijzelf toevoegen'),'mini');add.type='button';add.onclick=cmkSelfNew;acts.append(add);
    if(people.length){const pick=cmEl('button',t('Ik sta er al tussen'),'mini');pick.type='button';pick.onclick=cmkSelfPick;acts.append(pick);}
    card.append(acts);box.append(card);
  }
  // Mijn mensen, per groep (Gezin, Familie, …); zonder relatie met jou apart
  if(people.length||me){
    box.append(cmkHead(me?t('Mijn mensen'):t('Personen')));
    const groups=new Map();
    for(const p of people){
      const rels=cmkToMe(p.id,me),first=rels.find(x=>x.code)||rels[0],key=me?(first?.code?cmkGroup(first.code):first?'Anderen':''):'';
      if(!groups.has(key))groups.set(key,[]);groups.get(key).push({p,rels});
    }
    const order=[...CMI_RELATION_GROUPS.map(g=>g[0]),''];
    for(const key of order){
      const list=groups.get(key);if(!list)continue;
      if(me)box.append(cmEl('h4',key?t(key):t('Nog geen relatie met jou'),'cmk-g'));
      list.sort((a,b)=>a.p.label.localeCompare(b.p.label));
      for(const {p,rels} of list){
        const wie=rels.map(x=>cmkRelText(x,p.label)),stand=cmkDocStand(p),small=[...wie,stand].filter(Boolean).join(' · ')||(me?t('Tik om vast te leggen wat deze persoon voor je is'):t('Persoon'));
        box.append(cmkRow(p,small,cmiPersonLate(p).length>0,()=>cmEdit(p.id)));
      }
    }
    if(me){const acts=cmEl('div',undefined,'row cmi-actions cmk-add'),add=cmEl('button',t('Persoon toevoegen'),'mini');add.type='button';add.onclick=cmkPersonNew;acts.append(add);box.append(acts);}
  }
  // Overig: organisaties, adressen, reizen, vaste formuliergegevens, app-profiel (relaties staan bij de personen)
  const rest=cm.records.filter(r=>['organisatie','adres','reis','formulier','profiel'].includes(r.type)).sort((a,b)=>a.label.localeCompare(b.label));
  if(rest.length){box.append(cmkHead(t('Overig')));for(const r of rest)box.append(cmkRow(r,t(CM_TYPES[r.type]),false,()=>cmEdit(r.id)));}
}
const cmkList=cmRenderList;cmRenderList=function(){
  cmkList();
  const box=$('#cm-list');if(!cmOpen()||cm.filter||$('#cm-search').value.trim()||!cm.records.length)return;
  box.replaceChildren();cmkRender(box);
};
/* Mijzelf: een nieuwe persoon; na opslaan wordt hij jouw persoon in het app-profiel (Dit ben ik). */
function cmkSelfNew(){if(!cmOpen()||cm.mutating||!cm.recordsReady)return;cmiNew('persoon');cmk.nieuw=true;cmk.rel='';cmEditorTitle(cm.edit);$('#cm-editor-title').textContent=t('Mijzelf toevoegen');}
function cmkSelfPick(){
  if(!cmOpen()||cm.mutating)return;
  const people=cm.records.filter(r=>r.type==='persoon').sort((a,b)=>a.label.localeCompare(b.label));
  openSheet(t('Wie ben jij?'),'',people.map(p=>[p.label,()=>cmkSelfSet(p.id),'user']));
  for(const b of document.querySelectorAll('#sheet-acts button'))b.setAttribute('translate','no');
}
/* Jouw persoon vastleggen in het app-profiel (bestaand profiel bijwerken, anders een nieuw; delen blijft zoals het was of uit). */
function cmkSelfSet(id,after,failed){
  if(!cmOpen())return;
  const profile=cm.records.find(r=>r.type==='profiel'),done=()=>{cmBusy(false);appIdentityRefresh();cmListLoad(after||(()=>{if(current!=='masterdata')show('masterdata');cmNote(t('Vastgelegd: dit ben jij.'));}));};
  const fail=()=>{cmBusy(false);if(failed)failed();};
  cmBusy(true);
  const save=rec=>cmRequest('masterSave',[JSON.stringify(rec)],done,false,fail);
  if(profile)cmRequest('masterRead',[profile.id,'masterdata',''],r=>{const rec=r.record;rec.fields.persoon=id;save(rec);},false,fail);
  else save({type:'profiel',label:t('Mijn app-profiel'),fields:{...cmiDefaults('profiel'),persoon:id},revision:0});
}
/* Persoon toevoegen vanuit jou: eerst wat het voor jou is, dan de gegevens; persoon en relatie worden samen bewaard. */
const CMK_VAAK=['kind','partner','ouder','broerzus','grootouder','kleinkind','vriend','noodcontact'];
function cmkPersonNew(){
  if(!cmOpen()||cm.mutating||!cm.recordsReady)return;if(cm.edit&&cm.dirty){cmNote(t('Sla je wijzigingen eerst op.'));return;}const me=cmkMe();
  if(!me){cmiNew('persoon');return;}
  const acts=CMK_VAAK.map(code=>[t('Mijn {relatie}',{relatie:cmiRelationName(code).toLowerCase()}),()=>cmkPersonFor(code),'user']);
  acts.push([t('Andere relatie…'),cmkPersonMore,'more'],[t('Iemand anders, geen relatie met mij'),()=>cmkPersonFor(''),'user']);
  openSheet(t('Wie is het voor jou?'),t('De persoon en de relatie met jou worden samen bewaard.'),acts);
}
function cmkPersonMore(){
  const acts=[];
  for(const [group,codes] of CMI_RELATION_GROUPS)for(const code of codes.filter(c=>c!=='anders'&&!CMK_VAAK.includes(c)))acts.push([t('Mijn {relatie} ({groep})',{relatie:cmiRelationName(code).toLowerCase(),groep:t(group).toLowerCase()}),()=>cmkPersonFor(code),'user']);
  acts.push([t('Mijn … (andere relatie)'),()=>cmkPersonFor('anders'),'user']);
  openSheet(t('Wie is het voor jou?'),'',acts);
}
function cmkPersonFor(code){
  cmiNew('persoon');if(!cm.edit||cm.edit.id)return;cmk.rel=code;cmk.nieuw=false;
  $('#cm-editor-title').textContent=code&&code!=='anders'?t('Mijn {relatie} toevoegen',{relatie:cmkRelName(code)}):t('Persoon toevoegen');
}
const cmkSave=cmSave;cmSave=function(){
  const r=cm.edit,me=cmkMe();
  if(!r||r.id||r.type!=='persoon'||!cmOpen()||cm.mutating||!cmk.nieuw&&!(cmk.rel&&me)){cmkSave();return;}
  r.label=$('#cm-label').value;const rel=cmk.rel,self=cmk.nieuw;
  cmBusy(true);cmRequest('masterSave',[JSON.stringify(r)],out=>{
    const id=out.record.id;cmk.rel='';cmk.nieuw=false;cm.edit=null;cm.dirty=false;cmClearEdited();
    // Een melding na een fout komt na de foutmelding zelf (die zet onMasterReply), zodat je ziet wat er wel bewaard is.
    const later=text=>setTimeout(()=>cmNote(text),0);
    if(self){cmkSelfSet(id,()=>{cmAfterEdit(true);cmNote(t('Welkom! Dit ben jij. Voeg nu je documenten en je mensen toe.'));},()=>{cmAfterEdit(true);later(t('Je bent toegevoegd, maar nog niet aangewezen als jezelf. Kies Ik sta er al tussen.'));});return;}
    const relatie={type:'relatie',label:cmiRelationText({vanPersoon:me,naarPersoon:id,relatie:rel},'label')||r.label,fields:{vanPersoon:me,naarPersoon:id,relatie:rel,delen:'ja'},revision:0};
    // De nieuwe persoon staat nog niet in cm.records: naam voor het label zelf invullen
    relatie.label=(rel==='anders'?t('{naam} en {ander}',{naam:r.label,ander:cmiPersonName(me)}):t('{naam} (mijn {relatie})',{naam:r.label,relatie:cmkRelName(rel)})).slice(0,120);
    if(!cmOpen()){cmBusy(false);return;}
    cmRequest('masterSave',[JSON.stringify(relatie)],()=>{cmBusy(false);cmAfterEdit(true);cmNote(rel==='anders'?t('{naam} is toegevoegd.',{naam:r.label}):t('{naam} is toegevoegd als je {relatie}.',{naam:r.label,relatie:cmkRelName(rel)}));appIdentityRefresh();},false,()=>{cmBusy(false);if(cmOpen())cmAfterEdit(true);later(t('{naam} is toegevoegd, maar de relatie met jou niet. Leg die vast bij de persoon onder Relaties.',{naam:r.label}));});
  },false,()=>cmBusy(false));
};
const cmkAfter=cmAfterEdit;cmAfterEdit=function(saved){cmk.rel='';cmk.nieuw=false;cmkAfter(saved);};
const cmkClear=cmClearCentral;cmClearCentral=function(){
  cmk.rel='';cmk.nieuw=false;cmk.mijRel='';$('#cmk-voor-mij').replaceChildren();
  // Een open keuzelijst met namen (Wie ben jij?, Relatie tussen anderen) sluit mee bij vergrendelen.
  if($('#sheet')?.classList.contains('on')&&['masterdata','cmedit'].includes(current))closeSheet();
  cmkClear();
};
/* Toevoegen-menu: een persoon begint vanuit jou ("Wie is het voor jou?"); zonder jou eerst Mijzelf. */
cmAddMenu=function(){
  if(!cmOpen()||cm.mutating||!cm.recordsReady)return;const me=cmkMe(),acts=[];
  if(!me)acts.push([t('Mijzelf'),cmkSelfNew,CM_ICONS.persoon]);
  acts.push([me?t('Persoon, met wat die voor jou is'):t(CM_TYPES.persoon),me?cmkPersonNew:()=>cmiNew('persoon'),CM_ICONS.persoon]);
  for(const type of ['relatie','identiteit','organisatie','adres','reis'])acts.push([type==='relatie'&&me?t('Relatie tussen anderen'):t(CM_TYPES[type]),()=>cmiNew(type),CM_ICONS[type]]);
  if(typeof Android.masterFormProfile==='function')acts.push([t('Vaste gegevens reistoestemming'),()=>cmiNew('formulier'),CM_ICONS.formulier]);
  openSheet(t('Wat wil je toevoegen?'),'',acts);
};
function cmkOthers(){
  if(!cmOpen()||!cm.edit?.id||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  const naam=cm.edit.label;
  openSheet(t('Relatie tussen anderen'),t('Bijvoorbeeld het kind van je partner uit een eerdere relatie.'),[
    [t('Kind van {naam}',{naam}),()=>cmiRelationNew('kind'),'user'],[t('Ouder van {naam}',{naam}),()=>cmiRelationNew('ouder'),'user'],
    [t('Partner van {naam}',{naam}),()=>cmiRelationNew('partner'),'user'],[t('Andere relatie met {naam}',{naam}),()=>cmiRelationNew(''),'share']]);
}
/* Bij een persoon (tabblad Relaties): bovenaan wat die voor jou is, met vastleggen of wijzigen; relaties tussen anderen als extra. */
const cmkRelations=cmiPersonRelations;cmiPersonRelations=function(){
  cmkRelations();
  const id=cm.edit.id,me=cmkMe(),box=$('#cmk-voor-mij');
  box.replaceChildren();box.hidden=!me||id===me;$('#cmk-mij-acts').hidden=!me||id===me;$('#cmk-ik-acts').hidden=id!==me;
  cmk.mijRel='';$('#cmk-anderen').textContent=me?t('Relatie tussen anderen…'):t('Relatie toevoegen…');
  if(!me||id===me)return;
  const rels=cmkToMe(id,me);
  box.append(cmData(cmEl('p',rels.length?rels.map(x=>x.code==='anders'?t('{naam} heeft een andere relatie met jou.',{naam:cm.edit.label}):x.code?t('{naam} is je {relatie}.',{naam:cm.edit.label,relatie:cmkRelName(x.code)}):t('Jij bent {relatie} van {naam}.',{naam:cm.edit.label,relatie:cmkRelName(x.orig)})).join(' '):t('Nog niet vastgelegd wat {naam} voor jou is.',{naam:cm.edit.label}),'cmi-zin')));
  $('#cmk-mij-btn').textContent=rels.length?t('Relatie met mij wijzigen'):t('Relatie met mij vastleggen');
  cmk.mijRel=rels[0]?.rel||'';
};
function cmkMine(){
  if(!cmOpen()||!cm.edit?.id||cm.dirty||cm.mutating){cmNote(t('Sla je wijzigingen eerst op.'));return;}
  if(cmk.mijRel){const id=cm.edit.id;cmEdit(cmk.mijRel,'',{id,tab:'relaties'});return;}
  cmiRelation();
}
