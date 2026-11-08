// Stamgegevens vanuit jou: "Ik" bovenaan met je documenten, "Mijn mensen" per relatie tot jou, persoon toevoegen begint met
// "Wie is het voor jou?" (persoon en relatie samen), relaties tussen anderen als extra keuze.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  const rows=()=>p.$$eval('#cm-list > *',xs=>xs.map(x=>x.classList.contains('strow')?'· '+x.querySelector('b').textContent+' | '+x.querySelector('small').textContent:x.tagName+' '+(x.querySelector('h3')||x).textContent.split('\n')[0].trim()));
  const ready=()=>p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady&&!cm.mutating&&!cm.edit);
  // 1. Leeg: begin met jezelf
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  await p.evaluate(()=>{Android._cm.records.push({id:'p-anna',type:'persoon',label:'Anna',revision:1,fields:{naam:'de Boer'}});cmListLoad();});await ready();
  assert.equal(await p.isVisible('#cm-list .cmk-start'),true,'Begin met jezelf');
  assert.equal(await p.isVisible('#cm-list .cmk-start >> text=Ik sta er al tussen'),true);
  await p.click('#cm-list .cmk-start >> text=Mijzelf toevoegen');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon');
  assert.equal(await p.textContent('#cm-editor-title'),'Mijzelf toevoegen');
  await p.fill('#cm-label','Rene');await p.fill('#cm-field-naam','van Es');await p.click('#cm-save');await ready();
  const me=await p.evaluate(()=>cmkMe());assert.ok(me,'profiel wijst jou aan');
  assert.equal(await p.evaluate(id=>Android._cm.records.find(r=>r.type==='profiel').fields.persoon===id,me),true);
  let list=await rows();assert.equal(list[0],'H3 Ik');assert.match(list[1],/^· Rene \| Dit ben jij/);assert.ok(list.includes('H3 Mijn mensen'));
  assert.ok(list.includes('H4 Nog geen relatie met jou')&&list.some(x=>/^· Anna \| Tik om vast te leggen/.test(x)),'Anna nog zonder relatie: '+list);
  // 2. Persoon toevoegen vanuit jou: Wie is het voor jou? → Mijn kind
  await p.click('#cm-list >> text=Persoon toevoegen');await p.waitForSelector('#sheet.on');
  assert.equal(await p.textContent('#sheet-title'),'Wie is het voor jou?');
  await p.click('#sheet-acts >> text=Mijn kind');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);
  assert.equal(await p.textContent('#cm-editor-title'),'Mijn kind toevoegen');
  await p.fill('#cm-label','Zoë');await p.click('#cm-save');await ready();
  assert.match(await p.textContent('#cm-status'),/Zoë is toegevoegd als je kind/);
  const rel=await p.evaluate(()=>Android._cm.records.find(r=>r.type==='relatie'));
  assert.equal(rel.fields.vanPersoon,me);assert.equal(rel.fields.relatie,'kind');assert.equal(rel.label,'Zoë (mijn kind)');
  // 3. Andersom vastgelegd (Rene is kind van Piet): Piet is je ouder; alles in de groep Gezin
  await p.evaluate(me=>{const m=Android._cm;m.records.push({id:'p-piet',type:'persoon',label:'Piet',revision:1,fields:{}},{id:'r-piet',type:'relatie',label:'Rene (kind van Piet)',revision:1,fields:{vanPersoon:'p-piet',naarPersoon:me,relatie:'kind',delen:'ja'}});cmListLoad();},me);await ready();
  list=await rows();const gezin=list.indexOf('H4 Gezin');assert.ok(gezin>0,'groep Gezin: '+list);
  assert.ok(list.some(x=>/^· Zoë \| mijn kind/.test(x))&&list.some(x=>/^· Piet \| mijn ouder/.test(x)),'wat ze voor jou zijn: '+list);
  assert.ok(list.indexOf('H4 Nog geen relatie met jou')>gezin,'zonder relatie onderaan');
  // 4. Anna: relatie met mij vastleggen vanaf de persoon
  await p.locator('#cm-list .strow',{hasText:'Anna'}).click();await p.waitForFunction(()=>cm.edit?.label==='Anna');await p.click('#cm-tab-relaties');
  assert.match(await p.textContent('#cmk-voor-mij'),/Nog niet vastgelegd wat Anna voor jou is/);
  await p.click('#cmk-mij-btn');await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!cm.edit.id);
  assert.equal(await p.inputValue('#cm-field-vanPersoon'),me);await p.selectOption('#cm-field-relatie','partner');
  assert.equal(await p.textContent('#cmi-relation-zin'),'Anna is je partner.');assert.equal(await p.inputValue('#cm-label'),'Anna (mijn partner)');
  await p.click('#cm-save');await p.waitForFunction(()=>cm.edit?.label==='Anna'&&cm.tab==='relaties'&&cm.recordsReady);
  assert.match(await p.textContent('#cmk-voor-mij'),/Anna is je partner/);assert.equal(await p.textContent('#cmk-mij-btn'),'Relatie met mij wijzigen');
  // Relatie tussen anderen blijft mogelijk
  await p.click('#cmk-anderen');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-acts button:first-child'),'Kind van Anna');await p.evaluate(()=>closeSheet());
  await p.evaluate(()=>{cm.dirty=false;cmCancelEdit();});await ready();
  // 5. Mijn document staat onder Ik; zoeken geeft de gewone lijst
  await p.evaluate(me=>{Android._cm.records.push({id:'d-1',type:'identiteit',label:'Rijbewijs Rene',revision:1,fields:{persoon:me,documentsoort:'rijbewijs',geldig:'2031-06-11'}});cmListLoad();},me);await ready();
  list=await rows();assert.match(list[2],/^· Rijbewijs Rene \| Rijbewijs · geldig tot/,'document onder Ik: '+list);
  await p.fill('#cm-search','an');assert.equal(await p.locator('#cm-list h3').count(),0,'zoeken: gewone lijst');await p.fill('#cm-search','');
  assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'past in de breedte');
  const minTik=await p.$$eval('#cm-list .strow,#cm-list button',b=>Math.min(...b.map(x=>x.getBoundingClientRect().height)));assert.ok(minTik>=44,'tikvlakken '+minTik);
  // Lukt het bewaren van de relatie niet, dan zegt de app dat de persoon er wel is (na de foutmelding).
  await p.evaluate(()=>{Android._cm.saveFail='relatie';});await p.click('#cm-list >> text=Persoon toevoegen');await p.waitForSelector('#sheet.on');
  await p.click('#sheet-acts >> text=Mijn partner');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);
  await p.fill('#cm-label','Bram');await p.click('#cm-save');
  await p.waitForFunction(()=>/Bram is toegevoegd, maar de relatie met jou niet/.test($('#cm-status').textContent+$('#cm-edit-status').textContent));
  await p.evaluate(()=>{Android._cm.saveFail='';});await p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady&&!cm.mutating);
  assert.ok((await rows()).some(x=>/^· Bram \| Tik om vast te leggen/.test(x)),'Bram staat er, zonder relatie');
  // "Andere relatie": geen "mijn anders" in de zinnen
  await p.evaluate(me=>{Android._cm.records.push({id:'r-bram',type:'relatie',label:'Bram en Rene',revision:1,fields:{vanPersoon:me,naarPersoon:Android._cm.records.find(r=>r.label==='Bram').id,relatie:'anders',delen:'ja'}});cmListLoad();},me);await ready();
  assert.ok((await rows()).some(x=>/^· Bram \| andere relatie met jou/.test(x)),'andere relatie in gewone woorden');
  // Vergrendelen sluit een open keuzelijst met namen en wist de zin bij de persoon
  await p.click('#cm-add');await p.waitForSelector('#sheet.on');await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
  assert.equal(await p.evaluate(()=>$('#sheet').classList.contains('on')),false,'keuzelijst dicht na vergrendelen');assert.equal(await p.evaluate(()=>$('#cmk-voor-mij').textContent),'');
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  // 6. Toevoegen-menu met jou: persoon met wat die voor jou is; relatie heet dan "tussen anderen"
  await p.click('#cm-add');await p.waitForSelector('#sheet.on');
  assert.deepEqual((await p.$$eval('#sheet-acts button',x=>x.map(b=>b.textContent))).slice(0,2),['Persoon, met wat die voor jou is','Relatie tussen anderen']);await p.evaluate(()=>closeSheet());
  // 7. Ik sta er al tussen: kies jezelf uit de personen
  await p.evaluate(()=>{const m=Android._cm;m.records=m.records.filter(r=>r.type!=='profiel');cmListLoad();});await ready();
  await p.click('#cm-list .cmk-start >> text=Ik sta er al tussen');await p.waitForSelector('#sheet.on');await p.click('#sheet-acts >> text=Piet');await ready();
  assert.equal(await p.evaluate(()=>cmkMe()),'p-piet');list=await rows();assert.match(list[1],/^· Piet \| Dit ben jij/);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
