// Landen en nationaliteiten: keuzelijsten in Stamgegevens (nationaliteit, land, land van afgifte) met als standaard het land uit
// Regio en taal (in Nederland: Nederlandse, Nederland), rijbewijscategorieën als knoppen, herkende codes uit de strook
// omgezet naar de lijst, en de lijst klopt met wat Mrz.java herkent.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');const fs=require('fs');
(async()=>{const b=await chromium.launch(),errors=[];
const open=async(opts)=>{
  const ctx=await b.newContext({viewport:{width:390,height:844},...opts});const p=await ctx.newPage();p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  await p.evaluate(()=>{Android._cm.records=[{id:'owner',type:'persoon',label:'Rene',revision:1,fields:{naam:'van Es',voornamen:'Rene',nationaliteit:'Kaapverdiaans'}}];show('masterdata');});
  await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  return {p,ctx,T:require('./_stamtabs')(p)};
};
// Een bewerking sluiten zonder bewaren en wachten tot de lijst weer klaar is
const terug=async p=>{await p.evaluate(()=>{cm.dirty=false;cmCancelEdit();});await p.waitForFunction(()=>current!=='cmedit'&&!cm.edit&&!cm.pending.size&&cm.recordsReady&&!cm.navigating&&!cm.mutating);};
const opties=(p,id)=>p.evaluate(id=>[...$(id).options].map(o=>[o.value,o.textContent,o.parentElement.tagName==='OPTGROUP'?o.parentElement.label:'']),id);

// 1. De lijst zelf: unieke codes, landen en nationaliteiten; Mrz.java herkent dezelfde Nederlandse namen.
{
  const {p,ctx}=await open({timezoneId:'Europe/Amsterdam',colorScheme:'light'});
  const r=await p.evaluate(()=>({n:LANDEN.length,a2:new Set(LANDEN.map(x=>x[0])).size,a3:new Set(LANDEN.map(x=>x[1])).size,land:new Set(LANDEN.map(x=>x[2])).size,
    nat:LANDEN.filter(x=>x[3]).length,natU:new Set(LANDEN.filter(x=>x[3]).map(x=>x[3])).size,rows:LANDEN}));
  assert.equal(r.a2,r.n,'unieke ISO-2');assert.equal(r.a3,r.n,'unieke ISO-3');assert.equal(r.land,r.n,'unieke landnamen');assert.equal(r.natU,r.nat,'unieke nationaliteiten');
  assert.ok(r.n>=190,'alle landen');
  const java=fs.readFileSync(path.join(__dirname,'../../app/src/nl/rene/tools/Mrz.java'),'utf8');
  const blok=/COUNTRIES=\{([\s\S]*?)\};/.exec(java)[1];
  for(const [,code,land,nat] of blok.matchAll(/\{"([A-Z]{1,3})","([^"]+)","([^"]+)"\}/g)){
    const row=code==='D'?r.rows.find(x=>x[0]==='DE'):r.rows.find(x=>x[1]===code);
    assert.ok(row,'Mrz-land in de lijst: '+code);assert.equal(row[2],land,'landnaam '+code);assert.equal(row[3],nat,'nationaliteit '+code);
  }
  // Herkende codes uit de strook (een land dat Mrz.java niet kent, staatloos) worden de tekst uit de lijst.
  assert.deepEqual(await p.evaluate(()=>[lnToon('land','SYR'),lnToon('nationaliteit','GBD'),lnVind('D')[2],lnToon('nationaliteit','XXA')]),['Syrië','Britse','Duitsland','Staatloos'],'codes uit de strook');
  assert.equal(await p.evaluate(()=>{const s=document.createElement('select');cmiVulKeuzes(s,cmiLandKeuzes('land'),'SYR','land');return s.options[1].textContent+'|'+s.value;}),'Syrië (code SYR)|SYR','oude code zichtbaar als code');
  const h=await p.evaluate(()=>cmhLanden({document:{uitgifteland:'SYR'},persoon:{nationaliteit:'XXA'}}));
  assert.deepEqual(h,{document:{uitgifteland:'Syrië'},persoon:{nationaliteit:'Staatloos'}});
  assert.deepEqual(await p.evaluate(()=>cmhLanden({document:{uitgifteland:'Nederland'},persoon:{nationaliteit:'nld'}})),{document:{uitgifteland:'Nederland'},persoon:{nationaliteit:'Nederlandse'}});
  assert.equal(await p.evaluate(()=>cmhLanden({persoon:{nationaliteit:'Onbekend land'}}).persoon.nationaliteit),'Onbekend land','onbekende tekst blijft');
  await ctx.close();
}

// 2. In Nederland (tijdzone van de telefoon): nieuwe persoon met Nationaliteit = Nederlandse, als keuzelijst.
for(const colorScheme of ['light','dark']){
  const {p,ctx,T}=await open({timezoneId:'Europe/Amsterdam',colorScheme});
  await p.evaluate(()=>cmiNew('persoon'));await p.waitForFunction(()=>cm.edit?.type==='persoon'&&!!$('#cm-field-nationaliteit'));
  assert.equal(await p.evaluate(()=>$('#cm-field-nationaliteit').tagName+' '+$('#cm-field-nationaliteit').className),'SELECT search');
  assert.equal(await p.inputValue('#cm-field-nationaliteit'),'Nederlandse','standaard Nederlandse');
  const o=await opties(p,'#cm-field-nationaliteit');
  assert.deepEqual(o[0],['','Niet ingevuld','']);assert.deepEqual(o[1],['Nederlandse','Nederlandse','Jouw regio'],'eigen land bovenaan');
  assert.ok(o.some(x=>x[0]==='Duitse'&&x[2]==='Alle nationaliteiten'));assert.ok(o.some(x=>x[0]==='Staatloos'));
  const alle=o.filter(x=>x[2]==='Alle nationaliteiten').map(x=>x[1]);assert.deepEqual(alle,[...alle].sort((a,c)=>a.localeCompare(c,'nl',{sensitivity:'base'})),'op alfabet');
  await p.fill('#cm-field-voornamen','Anna');await p.fill('#cm-field-naam','Jansen');
  await p.selectOption('#cm-field-nationaliteit','Belgische');
  await T.opslaan();
  const anna=await p.evaluate(()=>Android._cm.records.find(r=>r.fields.voornamen==='Anna'));
  assert.equal(anna.fields.nationaliteit,'Belgische','gekozen nationaliteit bewaard');
  // Land (contact) is een keuzelijst zonder standaard bij een bestaande persoon; een eigen oude tekst blijft zichtbaar.
  await T.blok('owner','geboorte');
  assert.equal(await p.inputValue('#cm-field-nationaliteit'),'Kaapverdiaans','oude eigen tekst blijft staan');
  assert.deepEqual((await opties(p,'#cm-field-nationaliteit'))[1],['Kaapverdiaans','Kaapverdiaans','Zoals ingevuld'],'bovenaan, met een eigen kop');
  // Dubbele nationaliteit: nog een keuzelijst; Weghalen haalt hem weg.
  await p.click('.cmi-nat-meer');await p.waitForSelector('#cm-field-nationaliteit-2');
  assert.equal(await p.evaluate(()=>document.activeElement.id),'cm-field-nationaliteit-2');
  assert.equal(await p.isVisible('.cmi-nat-meer'),false,'eerst de tweede kiezen');
  await p.selectOption('#cm-field-nationaliteit-2','Marokkaanse');
  assert.equal(await p.evaluate(()=>cm.edit.fields.nationaliteit),'Kaapverdiaans, Marokkaanse');
  assert.equal(await p.getAttribute('#cm-field-nationaliteit-2','aria-label'),'Nationaliteit 2');
  await p.selectOption('#cm-field-nationaliteit-2','');
  assert.equal(await p.evaluate(()=>cm.edit.fields.nationaliteit),'Kaapverdiaans');assert.equal(await p.$('#cm-field-nationaliteit-2'),null);
  assert.equal(await p.evaluate(()=>document.activeElement.id),'cm-field-nationaliteit','focus terug op de lijst');
  await p.selectOption('#cm-field-nationaliteit','Marokkaanse');await p.click('.cmi-nat-meer');await p.selectOption('#cm-field-nationaliteit-2','Marokkaanse');
  assert.equal(await p.evaluate(()=>cm.edit.fields.nationaliteit),'Marokkaanse','niet twee keer dezelfde');assert.equal(await p.$('#cm-field-nationaliteit-2'),null);
  // Anders… (zelf typen): een eigen tekst; Annuleren laat alles staan.
  await p.selectOption('#cm-field-nationaliteit',await p.evaluate(()=>CMI_ANDERS));await p.waitForSelector('#modal.on');
  await p.click('#modal-cancel');assert.equal(await p.inputValue('#cm-field-nationaliteit'),'Marokkaanse');
  await p.selectOption('#cm-field-nationaliteit',await p.evaluate(()=>CMI_ANDERS));await p.waitForSelector('#modal.on');
  await p.fill('#modal-input','Nederlandse, Kaapverdische');await p.click('#modal-ok');
  await p.waitForFunction(()=>cm.edit.fields.nationaliteit==='Nederlandse, Kaapverdische');
  assert.equal(await p.inputValue('#cm-field-nationaliteit'),'Nederlandse');assert.equal(await p.inputValue('#cm-field-nationaliteit-2'),'Kaapverdische');
  await terug(p);
  // Land van afgifte: Anders… met een oude landnaam
  const doc=await T.maak('identiteit','Paspoort',{persoon:'owner',documentsoort:'paspoort'});
  await T.blok(doc,'afgifte');
  await p.selectOption('#cm-field-uitgifteland',await p.evaluate(()=>CMI_ANDERS));await p.waitForSelector('#modal.on');
  await p.fill('#modal-input','Joegoslavië');await p.click('#modal-ok');
  await p.waitForFunction(()=>cm.edit.fields.uitgifteland==='Joegoslavië'&&$('#cm-field-uitgifteland').value==='Joegoslavië');
  await terug(p);
  await T.blok('owner','contact');
  assert.equal(await p.inputValue('#cm-field-land'),'','geen land ingevuld bij een bestaande persoon');
  assert.ok((await opties(p,'#cm-field-land')).some(x=>x[0]==='Nederland'&&x[2]==='Jouw regio'));
  await terug(p);
  // Nieuw adres: land Nederland (in beeld); organisatie (land niet in het eerste blok) en reis: geen standaard.
  for(const [type,want] of [['adres','Nederland'],['organisatie',''],['reis','']]){
    await p.evaluate(type=>cmiNew(type),type);await p.waitForFunction(type=>cm.edit?.type===type,type);
    assert.equal(await p.evaluate(()=>cm.edit.fields.land||''),want,'standaardland '+type);
    await terug(p);
  }
  // 3. Nieuw rijbewijs: geen verborgen land van afgifte (dat komt uit de herkenning of zelf); categorieën als knoppen.
  await p.evaluate(()=>cmiNew('identiteit'));await p.waitForFunction(()=>cm.edit?.type==='identiteit');
  assert.equal(await p.evaluate(()=>cm.edit.fields.uitgifteland),undefined);
  await p.evaluate(()=>{cm.edit.fields.persoon='owner';cm.edit.fields.documentsoort='rijbewijs';cm.edit.fields.categorieen='b, AM, L';cmEditorFields();});
  await T.tab('#cm-field-categorieen');
  const knoppen=await p.evaluate(()=>[...document.querySelectorAll('#cm-field-categorieen button')].map(b=>b.textContent+(b.getAttribute('aria-pressed')==='true'?'*':'')));
  assert.deepEqual(knoppen,['AM*','A1','A2','A','B*','BE','C1','C1E','C','CE','D1','D1E','D','DE','T','L*'],'bekende volgorde, onbekende code blijft');
  assert.equal(await p.getAttribute('#cm-field-categorieen','role'),'group');
  assert.match(await p.evaluate(()=>$('#'+$('#cm-field-categorieen').getAttribute('aria-labelledby')).textContent),/Rijbewijscategorieën/);
  await p.click('#cm-field-categorieen button[data-cat="BE"]');await p.click('#cm-field-categorieen button[data-cat="L"]');
  assert.equal(await p.evaluate(()=>cm.edit.fields.categorieen),'AM, B, BE');assert.equal(await p.evaluate(()=>cm.dirty),true);
  const h=await p.evaluate(()=>{const b=$('#cm-field-categorieen button').getBoundingClientRect();return [b.width,b.height];});assert.ok(h[0]>=44&&h[1]>=44,'tikvlak '+h);
  assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'past in de breedte');
  // Een oude vrije tekst die niet alleen uit codes bestaat, blijft een tekstveld (er gaat niets verloren).
  await p.evaluate(()=>{cm.edit.fields.categorieen='B (code 78)';cmEditorFields();});
  assert.equal(await p.evaluate(()=>$('#cm-field-categorieen').tagName),'INPUT');assert.equal(await p.inputValue('#cm-field-categorieen'),'B (code 78)');
  await terug(p);
  await ctx.close();
}

// 4. Zonder herkenbaar land (tijdzone die bij geen land hoort, geen standaardplaats): geen standaard, geen groep "Jouw regio".
{
  const {p,ctx}=await open({timezoneId:'America/New_York',colorScheme:'light'});
  await p.evaluate(()=>cmiNew('persoon'));await p.waitForFunction(()=>!!$('#cm-field-nationaliteit'));
  assert.equal(await p.inputValue('#cm-field-nationaliteit'),'');
  assert.ok(!(await opties(p,'#cm-field-nationaliteit')).some(x=>x[2]==='Jouw regio'));
  // Een standaardplaats in Nederland (Regio en taal) telt ook.
  await terug(p);await p.evaluate(()=>rgSave({place:'utrecht'}));
  await p.evaluate(()=>cmiNew('persoon'));await p.waitForFunction(()=>!!$('#cm-field-nationaliteit'));
  assert.equal(await p.inputValue('#cm-field-nationaliteit'),'Nederlandse','standaardplaats Utrecht');
  await ctx.close();
}
// Op reis (andere tijdzone) telt een Nederlandstalige telefoon (nl-NL); een andere taal op een telefoon in Nederland telt niet
// (de tijdzone gaat voor), alleen als de tijdzone niets zegt; en-US (vaak de standaard van een toestel) telt nooit.
for(const [locale,zone,want] of [['nl-NL','Europe/Madrid','Nederlandse'],['en-US','Europe/Madrid','Spaanse'],['nl-BE','Europe/Amsterdam','Belgische'],['en-GB','Europe/Amsterdam','Nederlandse'],['tr-TR','Europe/Amsterdam','Nederlandse'],['en-GB','UTC','Britse'],['en-US','UTC','']]){
  const {p,ctx}=await open({timezoneId:zone,locale,colorScheme:'light'});
  assert.equal(await p.evaluate(()=>cmiDefaults('persoon').nationaliteit||''),want,locale+' '+zone);
  await ctx.close();
}

// 5. In het Engels: de lijst toont Engelse namen, opgeslagen blijft de Nederlandse tekst; het kaartje toont het Engels.
{
  const {p,ctx,T}=await open({timezoneId:'Europe/Amsterdam',colorScheme:'light'});
  await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForTimeout(200);
  await p.evaluate(()=>cmiNew('persoon'));await p.waitForFunction(()=>!!$('#cm-field-nationaliteit'));
  const o=await opties(p,'#cm-field-nationaliteit');
  assert.deepEqual(o[1],['Nederlandse','Dutch','Your region']);assert.ok(o.some(x=>x[0]==='Duitse'&&x[1]==='German'&&x[2]==='All nationalities'));
  const EN=require('../../web/src/taal/en.json');
  assert.deepEqual(o.slice(1,-1).filter(x=>x[1]!==EN[x[0]]),[],'elke nationaliteit in het Engels (en.json)');
  await terug(p);
  await p.evaluate(()=>cmiNew('adres'));await p.waitForFunction(()=>!!$('#cm-field-land'));
  const l=await opties(p,'#cm-field-land');assert.ok(l.some(x=>x[0]==='Duitsland'&&x[1]==='Germany'));assert.deepEqual(l[1],['Nederland','Netherlands','Your region']);
  await terug(p);
  const id=await T.maak('persoon','Piet',{voornamen:'Piet',naam:'Smit',nationaliteit:'Nederlandse',land:'Duitsland'});
  await T.kaart(id);
  const tekst=await p.textContent('#cm-kaart');assert.match(tekst,/Dutch/);assert.match(tekst,/Germany/);assert.doesNotMatch(tekst,/Nederlandse|Duitsland/);
  assert.deepEqual(await p.evaluate(()=>[lnToon('nationaliteit','Nederlandse, Marokkaanse'),lnToon('land','SYR'),lnToon('nationaliteit','Joegoslavische')]),['Dutch, Moroccan','Syria','Joegoslavische']);
  await ctx.close();
}
// 6. Herkennen: een andere nationaliteit komt erbij (nooit in plaats van) en staat niet vanzelf aangevinkt; een nationaliteit die er
// al tussen staat: "staat er al". Een code uit de strook wordt de naam uit de lijst.
for(const [nu,rij] of [['','nationaliteit:v'],['Nederlandse','nationaliteit:-'],['Nederlandse, Duitse','nationaliteit:vx'],['Belgische','nationaliteit:-']]){
  const ctx=await b.newContext({viewport:{width:390,height:844},timezoneId:'Europe/Amsterdam'});const p=await ctx.newPage();p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  await p.evaluate(nu=>{Android._cm.records=[{id:'kid',type:'persoon',label:'Zoë',revision:1,fields:{naam:'Jansen',voornamen:'Zoë',nationaliteit:nu}}];
    Android._cm.herkend={document:{documentsoort:'paspoort',document:'NX7P41KD9',geldig:'2031-06-11',uitgifteland:'DEU'},persoon:{naam:'Jansen',nationaliteit:'Duitse'},soort:'TD3',zeker:true};show('masterdata');},nu);
  await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  await T.kaart('kid');await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');
  await p.getByRole('button',{name:'Paspoort',exact:true}).click();await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Paspoort');
  await p.getByRole('button',{name:'Foto kiezen',exact:true}).click();
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.id&&!$('#cmh-panel').hidden&&document.querySelectorAll('#cmh-list input').length>0);
  const rows=await p.$$eval('#cmh-list input',x=>x.map(i=>i.dataset.key+':'+(i.checked?'v':'-')+(i.disabled?'x':'')));
  assert.ok(rows.includes(rij),nu+': '+rows);assert.ok(rows.includes('uitgifteland:v'));
  assert.match(await p.textContent('#cmh-list'),/Duitsland/,'DEU als Duitsland');
  if(nu==='Nederlandse'){
    assert.match(await p.textContent('#cmh-list'),/komt erbij; nu: Nederlandse/);
    await p.check('#cmh-list input[data-key="nationaliteit"]');await p.click('#cm-save');
    await p.waitForFunction(()=>!cm.mutating&&!cm.edit);
    assert.equal(await p.evaluate(()=>Android._cm.records.find(r=>r.id==='kid').fields.nationaliteit),'Nederlandse, Duitse','toegevoegd, niet vervangen');
  }
  await ctx.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
