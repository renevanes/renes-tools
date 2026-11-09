// Stamgegevens: documenten horen bij een persoon (blok Documenten op het kaartje), geslacht, zichtbare datums en verloopmeldingen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  // Een kind met een paspoort dat over 40 dagen verloopt en een ouder met een ruim geldige ID-kaart.
  const soon=await p.evaluate(()=>{const d=new Date(Date.now()+40*864e5);return d.toISOString().slice(0,10);});
  await p.evaluate(soon=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n,rec=(n,type,label,fields)=>({id:id(n),type,label,revision:1,fields});
    Android._cm.records=[
      rec(1,'persoon','Zoë <b>',{naam:'Jansen',voornamen:'Zoë'}),
      rec(2,'persoon','Mama',{naam:'Jansen',voornamen:'Anna'}),
      rec(4,'identiteit','Paspoort Zoë',{persoon:id(1),documentsoort:'paspoort',document:'NX12345',geldig:soon}),
      rec(5,'identiteit','ID-kaart mama',{persoon:id(2),documentsoort:'identiteitskaart',document:'IA999',geldig:'2035-01-01'}),
      rec(6,'formulier','Reistoestemming Zoë',{formulier:'reistoestemming',kind:id(1),kindDocument:id(4),alleen:'ja',gever1:id(2),gever1Document:id(5),bsnInvullen:'nee'})];
  },soon);
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  // 1. Beginscherm: het bijna verlopen document staat bij Aandacht nodig; in Mijn mensen meldt de persoon het.
  assert.match(await p.textContent('#cm-aandacht-list'),/Paspoort Zoë.*Paspoort van Zoë <b> · verloopt over \d+ dagen/);
  assert.equal(await p.textContent('#cm-stand-documenten'),'2 documenten · één vraagt aandacht');
  assert.equal(await p.textContent('#cm-stand-mensen'),'2 personen');
  await p.click('#cm-row-mensen');await p.waitForFunction(()=>current==='cmlijst'&&cmLijstNu()==='mensen');
  const rows=await p.$$eval('#cm-lijst .strow b',x=>x.map(e=>e.textContent));
  assert.ok(!rows.includes('Paspoort Zoë')&&rows.includes('Zoë <b>'),'documenten niet los bij Mijn mensen');
  assert.match(await p.locator('#cm-lijst .strow',{hasText:'Zoë <b>'}).innerText(),/verloopt binnenkort/);
  assert.match(await p.locator('#cm-lijst .strow',{hasText:'Mama'}).innerText(),/één document/);
  assert.equal(await p.locator('#cm-lijst b b').count(),0,'namen blijven tekst');
  await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata'&&!cm.navigating);
  // Documenten: het eerst verlopende bovenaan, met de eigenaar erbij
  await p.click('#cm-row-documenten');await p.waitForFunction(()=>current==='cmlijst'&&cmLijstNu()==='documenten');
  assert.deepEqual(await p.$$eval('#cm-lijst .strow b',x=>x.map(e=>e.textContent)),['Paspoort Zoë','ID-kaart mama']);
  assert.equal(await p.locator('#cm-lijst .strow.warn,#cm-lijst .strow .warn,#cm-lijst .strow .cmi-warn').count()>0,true,'verloopmelding opvallend');
  await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata'&&!cm.navigating);
  await p.fill('#cm-search','paspoort');assert.equal(await p.locator('#cm-list .strow',{hasText:'Paspoort Zoë'}).count(),1,'zoeken vindt het document');
  assert.match(await p.locator('#cm-list .strow',{hasText:'Paspoort Zoë'}).innerText(),/Paspoort van Zoë <b> · verloopt over \d+ dagen/);
  await p.fill('#cm-search','');
  // Het documentkaartje meldt het bovenaan
  await T.kaart('00000000-0000-0000-0000-000000000004');assert.match(await p.textContent('#cm-kaart'),/verloopt over \d+ dagen/);
  // 2. Persoon: blok Documenten met de geldigheid en een knop om een document toe te voegen.
  const zoe='00000000-0000-0000-0000-000000000001';await T.kaart(zoe);
  assert.match(await p.textContent('#cm-kaart [data-blok=documenten]'),/Paspoort Zoë.*verloopt over \d+ dagen/);
  assert.equal(await p.locator('#cm-kaart [data-blok=documenten] .cmi-warn').count(),1,'verloopmelding opvallend');
  // Geslacht staat bij Persoon, met M/V/X.
  await p.click('#cm-kaart [data-blok=persoon] .cmc-pen');await p.waitForFunction(()=>current==='cmedit'&&cm.tab==='persoon');
  assert.deepEqual(await p.$$eval('#cm-field-geslacht option',o=>o.map(x=>x.value)),['','V','M','X']);await T.select('#cm-field-geslacht','X');
  await p.click('#cm-save');await p.waitForFunction(id=>Android._cm.records.find(r=>r.label==='Zoë <b>').fields.geslacht==='X'&&current==='cmkaart'&&cmc.rec?.id===id&&!cm.navigating,zoe);
  // 3. Nieuw document vanuit de persoon: eerst de soort, dan foto of zelf invullen; persoon vooraf gekozen, naam vanzelf.
  await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-title'),'Welk document van Zoë <b>?');
  await p.getByRole('button',{name:'Paspoort',exact:true}).click();await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Paspoort');
  assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),['Foto kiezen','Zelf invullen'],'een foto eerst');
  await p.getByRole('button',{name:'Zelf invullen',exact:true}).click();await p.waitForFunction(()=>cm.edit&&cm.edit.type==='identiteit'&&!cm.edit.id&&current==='cmedit');
  assert.deepEqual(await p.evaluate(()=>[cm.edit.fields.persoon,cm.edit.fields.documentsoort,cmLabel(cm.edit)]),[zoe,'paspoort','Paspoort Zoë <b>']);
  await T.select('#cm-field-documentsoort','rijbewijs');assert.equal(await p.evaluate(()=>cmLabel(cm.edit)),'Rijbewijs Zoë <b>','naam volgt de soort');await T.select('#cm-field-documentsoort','paspoort');
  assert.equal(await p.getAttribute('#cm-field-geldig','type'),'date','geldig tot zichtbaar als datum');assert.equal(await p.getAttribute('#cm-field-document','type'),'password','nummer afgeschermd');
  await T.fill('#cm-field-document','NY777');await T.fill('#cm-field-geldig','2036-03-01');
  await p.click('#cm-save');await p.waitForFunction(()=>Android._cm.records.filter(r=>r.type==='identiteit').length===3&&current==='cmkaart'&&cmc.rec?.label==='Paspoort Zoë <b>'&&!cm.navigating);
  assert.equal(await p.textContent('#cm-kaart-title'),'Paspoort Zoë <b>','na opslaan het documentkaartje');
  await p.evaluate(()=>goBack());await p.waitForFunction(id=>current==='cmkaart'&&cmc.rec?.id===id&&!cm.navigating,zoe);
  assert.match(await p.textContent('#cm-kaart [data-blok=documenten]'),/Paspoort Zoë <b>/,'terug bij de persoon, met het nieuwe document');
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
  // 4. Nieuwe reis: waarschuwing als het paspoort van het kind vóór het einde van de reis verloopt.
  await p.evaluate(()=>{openTool('pdf');});await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
  await p.click('#fmq-start');await p.waitForFunction(()=>fmq.open&&cmOpen()&&!$('#fmq-open').hidden&&fmq.records.length===1);
  const later=await p.evaluate(()=>new Date(Date.now()+60*864e5).toISOString().slice(0,10));
  await p.fill('#fmq-van',soon);await p.fill('#fmq-tot',later);await p.fill('#fmq-land','Spanje');await p.click('#fmq-ga');
  await p.waitForFunction(()=>!fmq.open&&!$('#pff-verloop').hidden);
  assert.match(await p.textContent('#pff-verloop'),/^Paspoort Zoë is vóór het einde van de reis niet meer geldig/,'zonder vinkje geen datum');
  assert.doesNotMatch(await p.textContent('#pff-verloop'),/NX12345|IA999/,'geen documentnummers');
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='expiry_check'&&e.role==='kind')),'controle in het logboek');
  // Vergrendelen wist ook de waarschuwing.
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!fm.masterSensitive);
  assert.equal(await p.textContent('#pff-verloop'),'');
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
