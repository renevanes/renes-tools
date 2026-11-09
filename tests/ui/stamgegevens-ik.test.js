// Stamgegevens vanuit jou: jij bovenaan (of Begin met jezelf), Mijn mensen gegroepeerd naar wat ze voor jou zijn, persoon
// toevoegen begint met "Wie is het voor jou?" (persoon en relatie samen bewaard), "Wie is dit voor jou?" op het kaartje met
// de status (ouderlijk gezag, samenwonend), en relaties tussen anderen als extra keuze.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  const hub=()=>p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady&&!cm.mutating&&!cm.edit&&!cm.navigating);
  const kaart=id=>p.waitForFunction(id=>current==='cmkaart'&&cmc.rec?.id===id&&!cm.pending.size&&!cm.mutating&&!cm.navigating,id);
  const nieuweKaart=label=>p.waitForFunction(label=>current==='cmkaart'&&cmc.rec?.label===label&&!cm.pending.size&&!cm.mutating&&!cm.navigating,label);
  const mensen=async()=>{await p.evaluate(()=>cmLijst('mensen'));await p.waitForFunction(()=>current==='cmlijst'&&cmLijstNu()==='mensen');
    return p.$$eval('#cm-lijst > *',xs=>xs.map(x=>x.tagName==='H3'?'# '+x.textContent:'· '+x.querySelector('b').textContent+' | '+x.querySelector('small').textContent));};
  const sheet=()=>p.$$eval('#sheet-acts button',x=>x.map(b=>b.textContent));
  const kies=async text=>{await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:text,exact:true}).click();};
  const chip=()=>p.textContent('#cmc-wie');
  const rel=naar=>p.evaluate(naar=>{const r=Android._cm.records.find(r=>r.type==='relatie'&&(r.fields.naarPersoon===naar||r.fields.vanPersoon===naar));return r&&{...r.fields,label:r.label};},naar);

  // 1. Er staat al iemand, maar nog niet jij: Begin met jezelf, of Ik sta er al tussen
  await p.evaluate(()=>{Android._cm.records.push({id:'p-anna',type:'persoon',label:'Anna',revision:1,fields:{voornamen:'Anna',naam:'de Boer'}});show('masterdata');});
  await p.click('#cm-unlock');await hub();
  assert.equal(await p.isVisible('#cm-ik .cmk-start'),true,'Begin met jezelf');assert.equal(await p.textContent('#cmk-self-pick'),'Ik sta er al tussen');
  await p.click('#cmk-self-new');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);
  assert.equal(await p.textContent('#cm-editor-title'),'Mijzelf toevoegen');
  await p.fill('#cm-field-roepnaam','Rene');await p.fill('#cm-field-naam','van Es');await p.click('#cm-save');await nieuweKaart('Rene van Es');
  const me=await p.evaluate(()=>cmkMe());assert.ok(me,'profiel wijst jou aan');
  assert.equal(await p.evaluate(id=>Android._cm.records.find(r=>r.type==='profiel').fields.persoon===id,me),true);
  await p.evaluate(()=>goBack());await hub();
  assert.equal(await p.textContent('#cm-ik .strow b'),'Rene van Es');assert.equal(await p.textContent('#cm-ik .strow small'),'Dit ben jij');
  assert.deepEqual(await mensen(),['# Nog geen relatie met jou','· Anna | Tik om vast te leggen wat deze persoon voor je is']);
  await p.evaluate(()=>goBack());await hub();

  // 2. Persoon toevoegen vanuit jou: Wie is het voor jou? → Mijn kind → Zelf invullen; persoon en relatie samen
  await p.click('#cm-add');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-title'),'Wie is het voor jou?');
  const keuzes=await sheet();assert.deepEqual(keuzes.slice(0,3),['Mijn kind','Mijn partner','Mijn ouder']);assert.deepEqual(keuzes.slice(-2),['Andere relatie…','Iemand anders, geen relatie met mij']);
  await kies('Mijn kind');await p.waitForFunction(()=>$('#sheet-title').textContent==='Mijn kind toevoegen');
  assert.deepEqual(await sheet(),['Kies uit Contacten','Zelf invullen']);
  await kies('Zelf invullen');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);
  assert.equal(await p.textContent('#cm-editor-title'),'Mijn kind toevoegen');
  await p.fill('#cm-field-roepnaam','Zoë');await p.click('#cm-save');await nieuweKaart('Zoë');const zoe=await p.evaluate(()=>cmc.rec.id);
  assert.equal(await p.textContent('#cm-kaart-status'),'Zoë is toegevoegd als je kind. Tik op de relatie om de status in te vullen.');
  let r=await rel(zoe);assert.equal(r.vanPersoon,me);assert.equal(r.relatie,'kind');assert.equal(r.label,'Zoë (mijn kind)');assert.equal(r.delen,'ja');
  assert.equal(await p.evaluate(id=>Android._cm.records.find(r=>r.id===id).fields.delen,zoe),'ja','je kind is bekend bij de assistent');
  assert.equal(await chip(),'Mijn kind');assert.match(await p.textContent('#cm-kaart .cmc-top'),/Tik erop om de status in te vullen, zoals ouderlijk gezag/);
  // Status vanaf het kaartje: bij je kind gaat ouderlijk gezag over jou
  await p.click('#cmc-wie');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-title'),'Wie is Zoë voor jou?');assert.equal(await p.textContent('#sheet-sub'),'Mijn kind');
  await kies('Status wijzigen');await p.waitForFunction(()=>$('#sheet-title').textContent==='Status');
  assert.equal(await p.textContent('#sheet-sub'),'Heb jij ouderlijk gezag?');assert.deepEqual(await sheet(),['Ja, ik heb ouderlijk gezag','Nee, ik heb geen ouderlijk gezag','Niet invullen']);
  await kies('Ja, ik heb ouderlijk gezag');await p.waitForFunction(()=>/Vastgelegd/.test($('#cm-kaart-status').textContent));await kaart(zoe);
  assert.equal(await p.textContent('#cm-kaart-status'),'Vastgelegd: Mijn kind · ik heb ouderlijk gezag.');assert.equal((await rel(zoe)).status,'gezag');
  assert.equal(await chip(),'Mijn kind · ik heb ouderlijk gezag');
  await p.evaluate(()=>goBack());await hub();

  // 3. Andersom vastgelegd (Rene is kind van Piet): Piet is je ouder; alles in de groep Gezin, zonder relatie onderaan
  await p.evaluate(me=>{const m=Android._cm;m.records.push({id:'p-piet',type:'persoon',label:'Piet',revision:1,fields:{roepnaam:'Piet'}},{id:'r-piet',type:'relatie',label:'Rene (kind van Piet)',revision:1,fields:{vanPersoon:'p-piet',naarPersoon:me,relatie:'kind',delen:'ja'}});cmListLoad();},me);await hub();
  assert.deepEqual(await mensen(),['# Gezin','· Piet | Mijn ouder','· Zoë | Mijn kind · ik heb ouderlijk gezag','# Nog geen relatie met jou','· Anna | Tik om vast te leggen wat deze persoon voor je is']);
  // Bij je ouder gaat de vraag over die ouder
  await p.locator('#cm-lijst .strow',{hasText:'Piet'}).click();await kaart('p-piet');await p.click('#cmc-wie');await kies('Status wijzigen');
  await p.waitForFunction(()=>$('#sheet-title').textContent==='Status');assert.equal(await p.textContent('#sheet-sub'),'Heeft deze ouder ouderlijk gezag over jou?');await p.evaluate(()=>closeSheet());
  await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='cmlijst');

  // 4. Anna: Wie is dit voor jou? vastleggen op het kaartje, met de status van jullie samen
  await p.locator('#cm-lijst .strow',{hasText:'Anna'}).click();await kaart('p-anna');
  assert.equal(await chip(),'Wie is Anna voor jou?');
  await p.click('#cmc-wie');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-sub'),'Nog niet vastgelegd.');
  assert.equal((await sheet()).includes('Geen relatie met mij'),false,'nog niets om weg te halen');
  await kies('Mijn partner');await p.waitForFunction(()=>$('#sheet-title').textContent==='Status');assert.equal(await p.textContent('#sheet-sub'),'Hoe zijn jullie samen?');
  await kies('Samenwonend');await p.waitForFunction(()=>/Vastgelegd/.test($('#cm-kaart-status').textContent));await kaart('p-anna');
  r=await rel('p-anna');assert.deepEqual([r.vanPersoon,r.naarPersoon,r.relatie,r.status,r.label],[me,'p-anna','partner','samenwonend','Anna (mijn partner)']);
  assert.equal(await chip(),'Mijn partner · samenwonend');
  // Relatie tussen anderen blijft mogelijk
  assert.equal(await p.textContent('#cmc-rel-add'),'Relatie tussen anderen…');await p.click('#cmc-rel-add');await p.waitForSelector('#sheet.on');
  assert.equal((await sheet())[0],'Kind van Anna');await p.evaluate(()=>closeSheet());
  // Geen relatie met mij: na bevestigen weg, Anna zelf blijft
  await p.click('#cmc-wie');await kies('Geen relatie met mij');await p.waitForSelector('#modal.on');await p.click('#modal-ok');
  await p.waitForFunction(()=>$('#cm-kaart-status').textContent==='De relatie met jou is weggehaald.');await kaart('p-anna');
  assert.equal(await chip(),'Wie is Anna voor jou?');assert.equal(await rel('p-anna'),undefined);
  const minTik=await p.$$eval('#cm-kaart button:not(.tg)',b=>Math.min(...b.filter(x=>x.offsetParent).map(x=>x.getBoundingClientRect().height)));assert.ok(minTik>=44,'tikvlakken '+minTik+' (een schakelaar heeft een groter tikvlak via ::before)');
  assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'past in de breedte');
  await p.evaluate(()=>show('masterdata'));await hub();

  // 5. Je eigen document staat bij jou op het beginscherm
  await p.evaluate(me=>{Android._cm.records.push({id:'d-1',type:'identiteit',label:'Rijbewijs Rene',revision:1,fields:{persoon:me,documentsoort:'rijbewijs',geldig:'2031-06-11'}});cmListLoad();},me);await hub();
  assert.equal(await p.textContent('#cm-ik .strow small'),'Dit ben jij · één document');
  // 6. Vergrendelen sluit een open keuzelijst
  await p.click('#cm-add');await p.waitForSelector('#sheet.on');await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
  assert.equal(await p.evaluate(()=>$('#sheet').classList.contains('on')),false,'keuzelijst dicht na vergrendelen');
  await p.click('#cm-unlock');await hub();

  // 7. Lukt het bewaren van de relatie niet, dan zegt de app dat de persoon er wel is (na de foutmelding)
  await p.evaluate(()=>{Android._cm.saveFail='relatie';});await p.click('#cm-add');await kies('Mijn partner');await kies('Zelf invullen');
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);await p.fill('#cm-field-roepnaam','Bram');await p.click('#cm-save');
  await p.waitForFunction(()=>/Bram is toegevoegd, maar de relatie met jou niet/.test($('#cm-kaart-status').textContent));await p.evaluate(()=>{Android._cm.saveFail='';});
  await nieuweKaart('Bram');assert.equal(await chip(),'Wie is Bram voor jou?','Bram staat er, zonder relatie');
  // 8. "Andere relatie": geen "mijn anders" in de zinnen
  await p.evaluate(me=>{Android._cm.records.push({id:'r-bram',type:'relatie',label:'Bram en Rene',revision:1,fields:{vanPersoon:me,naarPersoon:Android._cm.records.find(r=>r.label==='Bram').id,relatie:'anders',delen:'ja'}});cmListLoad();},me);
  await p.waitForFunction(()=>cm.recordsReady);assert.ok((await mensen()).includes('· Bram | Andere relatie met jou'),'andere relatie in gewone woorden');
  await p.evaluate(()=>show('masterdata'));await hub();
  // 9. Ik sta er al tussen: kies jezelf uit de personen
  await p.evaluate(()=>{const m=Android._cm;m.records=m.records.filter(r=>r.type!=='profiel');cmListLoad();});await hub();
  await p.click('#cmk-self-pick');await kies('Piet');await p.waitForFunction(()=>cmkMe()==='p-piet'&&!cm.mutating);await hub();
  assert.equal(await p.textContent('#cm-ik .strow b'),'Piet');assert.equal(await p.textContent('#cm-status'),'Vastgelegd: dit ben jij.');
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
