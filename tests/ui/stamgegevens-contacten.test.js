// Stamgegevens en Contacten: een persoon uit Contacten kiezen (naam, telefoon, e-mail, adres en verjaardag), telefoon en
// e-mail daarna actueel uit het contact, bellen, een bericht of het contact openen vanaf het kaartje, koppelen en loskoppelen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  await p.evaluate(()=>{Android._cm.contacts={
    ik:{weergave:'Rene van Es',fields:{voornamen:'Rene',naam:'van Es',telefoon:'010 123 4567'}},
    c1:{weergave:'Anna de Vries',fields:{voornamen:'Anna',naam:'de Vries',telefoon:'06 1234 5678',email:'anna@voorbeeld.nl',adres:'Straat 1',postcode:'3011 AA',plaats:'Rotterdam',land:'Netherlands',geboortedatum:'2015-02-03'}}};});
  await p.evaluate(()=>show('masterdata'));await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  const kaartTekst=()=>p.textContent('#cm-kaart');
  const herlaad=async id=>{await p.evaluate(id=>cmKaartLoad(id),id);await p.waitForFunction(id=>current==='cmkaart'&&cmc.rec?.id===id&&!cm.pending.size&&!cm.navigating,id);};

  // 1. Mijzelf uit Contacten: velden vooraf ingevuld, met uitleg; geen nieuwe ontgrendeling na de contactkiezer
  await p.evaluate(()=>{Android._cm.contactPick='ik';});await p.click('#cmk-self-contact');
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id&&cm.edit.fields.contact==='ik');
  assert.equal(await p.evaluate(()=>cmOpen()),true,'na het kiezen nog ontgrendeld');
  assert.deepEqual(await p.evaluate(()=>[$('#cm-field-voornamen').value,$('#cm-field-naam').value]),['Rene','van Es']);
  assert.match(await p.textContent('#cm-contact-hint'),/Overgenomen uit Contacten\. Telefoon en e-mail blijven daarna actueel/);assert.equal(await p.isVisible('#cm-contact-hint'),true);
  await p.click('#cm-save');await p.waitForFunction(()=>current==='cmkaart'&&!!cmkMe()&&cmc.rec?.id===cmkMe()&&!cm.navigating);
  const me=await p.evaluate(()=>cmkMe());
  assert.equal(await p.textContent('#cm-kaart-title'),'Rene van Es');assert.equal(await p.locator('#cmc-bel').count(),0,'jezelf bel je niet');assert.equal(await p.isVisible('#cmc-contact'),true,'wel je contact openen');
  assert.match(await kaartTekst(),/010 123 4567.*uit Contacten/);

  // 2. Persoon toevoegen vanuit jou, uit Contacten: persoon en relatie samen, alles uit het contact overgenomen
  await p.evaluate(()=>{goBack();Android._cm.contactPick='c1';});await p.waitForFunction(()=>current==='masterdata'&&!cm.navigating);
  await p.click('#cm-add');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Mijn kind',exact:true}).click();
  await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Mijn kind toevoegen');
  assert.match(await p.textContent('#sheet-sub'),/naam, telefoon, e-mail, adres en verjaardag/);
  await p.locator('#sheet-acts').getByRole('button',{name:'Kies uit Contacten',exact:true}).click();
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.fields?.contact==='c1');
  assert.deepEqual(await p.evaluate(()=>{const f=cm.edit.fields;return [f.voornamen,f.naam,f.geboortedatum,f.telefoon,f.email,f.adres,f.postcode,f.plaats,f.land];}),['Anna','de Vries','2015-02-03','06 1234 5678','anna@voorbeeld.nl','Straat 1','3011 AA','Rotterdam','Nederland']);
  await p.click('#cm-save');await p.waitForFunction(()=>current==='cmkaart'&&cmc.rec?.label==='Anna de Vries'&&!cm.navigating&&!cm.mutating);
  const anna=await p.evaluate(()=>cmc.rec.id);
  assert.match(await p.textContent('#cmc-wie'),/Mijn kind/);
  for(const id of ['#cmc-bel','#cmc-bericht','#cmc-contact'])assert.equal(await p.isVisible(id),true,id);
  assert.equal(await p.textContent('#cmc-bel'),'Bellen');

  // 3. Telefoon en e-mail komen actueel uit Contacten
  await p.evaluate(()=>{Android._cm.contacts.c1.fields.telefoon='06 9999 0000';});await herlaad(anna);
  assert.match(await kaartTekst(),/06 9999 0000.*uit Contacten/);assert.doesNotMatch(await kaartTekst(),/06 1234 5678/,'het oude nummer staat er niet meer');
  // Bellen, bericht en contact openen: Android opent de eigen app (de testbrug houdt bij wat)
  for(const id of ['#cmc-bel','#cmc-bericht','#cmc-contact'])await p.click(id);
  await p.waitForFunction(()=>(Android._cm.intents||[]).length===3);
  assert.deepEqual(await p.evaluate(()=>Android._cm.intents),[{what:'bel',number:'0699990000'},{what:'bericht',number:'0699990000'},{what:'contact',key:'c1'}]);
  assert.equal(await p.evaluate(()=>JSON.stringify(Android._cm.events).includes('9999')),false,'geen nummers in het logboek');
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='contact_use')),'gebruik in het logboek');

  // 4. Contact niet meer gevonden: het nummer uit Stamgegevens, met een hint
  await p.evaluate(()=>{Android._cm.gone=Android._cm.contacts.c1;delete Android._cm.contacts.c1;});await herlaad(anna);
  assert.match(await kaartTekst(),/niet meer gevonden\. Koppel opnieuw via ⋯/);assert.match(await kaartTekst(),/06 1234 5678/);assert.doesNotMatch(await kaartTekst(),/uit Contacten/);
  // Geen toegang tot Contacten: ook het nummer uit Stamgegevens, met een andere hint
  await p.evaluate(()=>{Android._cm.contacts.c1=Android._cm.gone;Android._cm.contactPerm=false;});await herlaad(anna);
  assert.match(await kaartTekst(),/Geen toegang tot Contacten: telefoon en e-mail komen nu uit Stamgegevens/);

  // 5. Loskoppelen (⋯): het contact gaat eraf, wat er stond blijft
  await p.evaluate(()=>{Android._cm.contactPerm=true;});await herlaad(anna);
  await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Loskoppelen van contact',exact:true}).click();
  await p.waitForFunction(id=>!Android._cm.records.find(r=>r.id===id).fields.contact&&/Losgekoppeld van het contact/.test($('#cm-kaart-status').textContent),anna);
  assert.equal(await p.locator('#cmc-contact').count(),0);assert.equal(await p.evaluate(id=>Android._cm.records.find(r=>r.id===id).fields.telefoon,anna),'06 1234 5678');
  // Koppelen zonder toegang: een duidelijke melding, niets veranderd
  await p.evaluate(()=>{Android._cm.contactPerm=false;});const voor=await p.evaluate(()=>JSON.stringify(Android._cm.records));
  await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Koppelen aan contact',exact:true}).click();
  await p.waitForFunction(()=>/Geen toegang tot Contacten\. Je kunt die geven bij Android-instellingen/.test($('#cm-kaart-status').textContent));
  assert.equal(await p.evaluate(()=>JSON.stringify(Android._cm.records)),voor);
  // Kiezen afgebroken: ook niets veranderd
  await p.evaluate(()=>{Android._cm.contactPerm=true;Android._cm.contactCancel=true;});
  await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Koppelen aan contact',exact:true}).click();
  await p.waitForFunction(()=>!cmct.request);assert.equal(await p.evaluate(()=>JSON.stringify(Android._cm.records)),voor);
  // Opnieuw koppelen
  await p.evaluate(()=>{Android._cm.contactCancel=false;});
  await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Koppelen aan contact',exact:true}).click();
  await p.waitForFunction(id=>Android._cm.records.find(r=>r.id===id).fields.contact==='c1'&&/Gekoppeld aan Anna de Vries\. Telefoon en e-mail komen nu uit Contacten/.test($('#cm-kaart-status').textContent),anna);
  assert.match(await kaartTekst(),/06 9999 0000.*uit Contacten/);assert.equal(await p.evaluate(()=>cmOpen()),true,'geen nieuwe ontgrendeling na de contactkiezer');

  // 6. Iemand zonder telefoonnummer: geen bel- of berichtknop
  const kees=await T.maak('persoon','Kees',{roepnaam:'Kees'});await T.kaart(kees);
  assert.equal(await p.locator('#cmc-bel,#cmc-bericht,#cmc-contact').count(),0);

  // 7. Engels: de knoppen vertaald, de namen niet
  await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForFunction(()=>TAAL.on);await T.kaart(anna);
  assert.deepEqual(await p.evaluate(()=>['#cmc-bel','#cmc-bericht','#cmc-contact'].map(s=>$(s).textContent)),['Call','Message','Contacts']);
  assert.equal(await p.textContent('#cm-kaart-title'),'Anna de Vries');
  await p.evaluate(()=>rgSave({lang:'nl'}));await p.waitForFunction(()=>!TAAL.on);

  // 8. Op slot tijdens het kiezen: het late antwoord doet niets
  await T.kaart(anna);await p.evaluate(()=>{Android._cm.contactDelay=150;});
  await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Loskoppelen van contact',exact:true}).click();
  await p.waitForFunction(id=>!Android._cm.records.find(r=>r.id===id).fields.contact&&!cm.mutating&&!cm.pending.size,anna);
  const los=await p.evaluate(()=>JSON.stringify(Android._cm.records));
  await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');await p.locator('#sheet-acts').getByRole('button',{name:'Koppelen aan contact',exact:true}).click();
  await p.evaluate(()=>cmLock());await p.waitForTimeout(300);
  assert.equal(await p.evaluate(()=>JSON.stringify(Android._cm.records)),los,'na vergrendelen niets gekoppeld');
  assert.equal(await p.evaluate(()=>/Anna|Rene|Kees|9999/.test($('#s-cmkaart').innerHTML+$('#s-cmedit').innerHTML+$('#s-masterdata').innerHTML)),false,'geen namen of nummers meer in beeld');
  assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|contact|master/.test(k))),false);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
