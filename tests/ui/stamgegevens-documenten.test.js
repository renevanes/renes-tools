// Stamgegevens: documenten horen bij een persoon (tabblad Documenten), geslacht, zichtbare datums en verloopmeldingen.
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
  // 1. Overzicht: documenten staan bij de persoon, niet los; de persoon meldt het verlopen document.
  const rows=await p.$$eval('#cm-list .strow b',x=>x.map(e=>e.textContent));
  assert.ok(!rows.includes('Paspoort Zoë')&&rows.includes('Zoë <b>'),'documenten niet los in Alles');
  assert.match(await p.locator('#cm-list .strow',{hasText:'Zoë <b>'}).innerText(),/verloopt binnenkort/);
  assert.match(await p.locator('#cm-list .strow',{hasText:'Mama'}).innerText(),/één document/);
  await p.fill('#cm-search','paspoort');assert.equal(await p.locator('#cm-list .strow',{hasText:'Paspoort Zoë'}).count(),1,'zoeken vindt het document wel');
  assert.match(await p.locator('#cm-list .strow',{hasText:'Paspoort Zoë'}).innerText(),/Paspoort van Zoë <b> · verloopt over \d+ dagen/);
  await p.fill('#cm-search','');await p.evaluate(()=>cmRenderList());
  assert.equal(await p.locator('#cm-list b b').count(),0,'namen blijven tekst');
  // 2. Persoon: tabblad Documenten met de geldigheid en een knop om een document toe te voegen.
  await p.locator('#cm-list .strow',{hasText:'Zoë <b>'}).click();await p.waitForFunction(()=>cm.edit&&cm.edit.label==='Zoë <b>');
  assert.equal(await p.isVisible('#cm-tab-documenten'),true);await p.click('#cm-tab-documenten');
  assert.match(await p.textContent('#cmi-person-doc-list'),/Paspoort Zoë.*verloopt over \d+ dagen/);
  assert.equal(await p.locator('#cmi-person-doc-list .cmi-warn').count(),1,'verloopmelding opvallend');
  // Geslacht staat bij Geboorte, met M/V/X.
  await T.select('#cm-field-geslacht','X');assert.deepEqual(await p.$$eval('#cm-field-geslacht option',o=>o.map(x=>x.value)),['','V','M','X']);
  await p.click('#cm-save');await p.waitForFunction(()=>Android._cm.records.find(r=>r.label==='Zoë <b>').fields.geslacht==='X');
  // 3. Nieuw document vanuit de persoon: persoon vooraf gekozen, naam vanzelf, datums als gewone datumvelden.
  await p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady);await p.locator('#cm-list .strow',{hasText:'Zoë <b>'}).click();
  await p.waitForFunction(()=>cm.edit&&cm.edit.label==='Zoë <b>');await p.click('#cm-tab-documenten');
  await p.locator('button[onclick="cmiDocNew()"]').click();await p.waitForFunction(()=>cm.edit&&cm.edit.type==='identiteit'&&!cm.edit.id);
  assert.equal(await p.inputValue('#cm-field-persoon'),'00000000-0000-0000-0000-000000000001');
  assert.equal(await p.inputValue('#cm-label'),'Identiteitskaart Zoë <b>');
  await T.select('#cm-field-documentsoort','paspoort');assert.equal(await p.inputValue('#cm-label'),'Paspoort Zoë <b>','naam volgt de soort');
  assert.equal(await p.getAttribute('#cm-field-geldig','type'),'date','geldig tot zichtbaar als datum');assert.equal(await p.getAttribute('#cm-field-document','type'),'password','nummer afgeschermd');
  await T.fill('#cm-field-document','NY777');await T.fill('#cm-field-geldig','2036-03-01');
  await p.click('#cm-save');await p.waitForFunction(()=>Android._cm.records.filter(r=>r.type==='identiteit').length===3&&cm.edit&&cm.edit.label==='Zoë <b>'&&cm.tab==='documenten');
  assert.match(await p.textContent('#cmi-person-doc-list'),/Paspoort Zoë <b>/,'na opslaan terug bij de documenten van de persoon');
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
