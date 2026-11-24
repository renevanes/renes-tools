// Stamgegevens: een persoon woont op een adres uit Adressen (geen eigen, dubbel adres meer). Kiezen, een nieuw adres
// toevoegen, gelijke adressen worden één adres, het adres aanpassen verandert het bij wie er woont, en een adres waar iemand
// woont is niet te verwijderen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  await p.evaluate(()=>show('masterdata'));await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  const rec=id=>p.evaluate(id=>Android._cm.records.find(r=>r.id===id),id);
  const adressen=()=>p.evaluate(()=>Android._cm.records.filter(r=>r.type==='adres').length);

  // 1. Een persoon met een eigen adres (zoals uit Contacten): dat wordt een adres bij Adressen en de persoon woont er
  const anna=await T.maak('persoon','Anna de Vries',{voornamen:'Anna',naam:'de Vries',adres:'Kade 12',postcode:'3011AB',plaats:'Rotterdam'});
  const a1=await rec(anna);assert.ok(a1.fields.woonadres,'Anna woont op een adres');
  const huis=await rec(a1.fields.woonadres);assert.equal(huis.type,'adres');assert.equal(huis.label,'Kade 12, Rotterdam');
  // 2. Hetzelfde adres anders geschreven: geen tweede adres, Bas woont er ook
  const bas=await T.maak('persoon','Bas de Vries',{voornamen:'Bas',naam:'de Vries',adres:'kade  12',postcode:'3011 ab',plaats:'ROTTERDAM'});
  assert.equal((await rec(bas)).fields.woonadres,huis.id,'gedeeld adres');assert.equal(await adressen(),1,'één adres voor het huishouden');
  assert.equal((await rec(bas)).fields.adres,'Kade 12','de schrijfwijze van Adressen');

  // 3. Bij de persoon: Woont op, een keuze uit Adressen (geen losse adresvelden meer)
  await T.blok(anna,'contact');
  assert.equal(await p.locator('#cm-field-adres').count(),0,'geen eigen adresveld');
  assert.equal(await p.$eval('#cm-field-woonadres',e=>e.value),huis.id);
  assert.deepEqual(await p.$$eval('#cm-field-woonadres option',o=>o.map(x=>x.textContent)),['Geen adres','Kade 12, Rotterdam','Nieuw adres…']);
  assert.match(await p.textContent('#cm-woon-info'),/Wijzig je het adres bij Adressen/);
  // Nieuw adres via één regel
  await p.selectOption('#cm-field-woonadres','__nieuw');await p.waitForSelector('#modal.on');
  assert.equal(await p.$eval('#cm-field-woonadres',e=>e.value),huis.id,'keuze blijft staan tot er een adres is');
  await p.fill('#modal-input','Dorpsstraat 3, 1234 cd Ede');await p.click('#modal-ok');
  await p.waitForFunction(()=>cm.edit?.fields?.adres==='Dorpsstraat 3');
  assert.deepEqual(await p.evaluate(()=>[cm.edit.fields.postcode,cm.edit.fields.plaats,cm.edit.fields.woonadres]),['1234 CD','Ede','']);
  assert.match(await p.textContent('#cm-woon-info'),/Nieuw adres, komt bij Adressen:.*Dorpsstraat 3, 1234 CD Ede/);
  await T.opslaan();
  const a2=await rec(anna);assert.notEqual(a2.fields.woonadres,huis.id);assert.equal(await adressen(),2,'nieuw adres bij Adressen');
  // Terug naar het gedeelde huis via de keuzelijst
  await T.blok(anna,'contact');await p.selectOption('#cm-field-woonadres',huis.id);await T.opslaan();
  assert.equal((await rec(anna)).fields.woonadres,huis.id);assert.equal((await rec(anna)).fields.plaats,'Rotterdam');

  // 4. Het adres aanpassen: iedereen die er woont gaat mee
  await T.blok(huis.id);await T.fill('#cm-field-adres','Kade 14');await T.opslaan();
  for(const id of [anna,bas])assert.equal((await rec(id)).fields.adres,'Kade 14',id);
  // Bij het adres: wie er woont (namen zijn gegevens)
  await T.blok(huis.id);
  assert.match(await p.textContent('#cm-bewoners'),/Woont hier:.*Anna de Vries.*Bas de Vries/);
  assert.equal(await p.$eval('#cm-bewoners span[translate=no]',e=>!!e),true);
  await p.evaluate(()=>goBack());await p.waitForFunction(()=>current!=='cmedit'&&!cm.navigating);

  // 5. Verwijderen kan niet zolang er iemand woont
  const fout=await p.evaluate(id=>new Promise(ok=>{const r=Android._cm.records.find(x=>x.id===id);cmRequest('masterDelete',[id,r.revision],()=>ok(''),false,e=>ok(e.error));}),huis.id);
  assert.match(fout,/woont nog iemand/);
  await p.close();
}
await b.close();console.log('errors',errors);if(errors.length)process.exit(1);})().catch(e=>{console.error(e);process.exit(1);});
