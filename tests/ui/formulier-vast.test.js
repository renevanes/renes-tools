// Vaste formuliergegevens: één keer instellen in Stamgegevens, daarna per reis alleen reisdagen en bestemming.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  // Stamgegevens die er al zijn: kind, twee ouders, documenten, een relatie en een instelling.
  await p.evaluate(()=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n,rec=(n,type,label,fields)=>({id:id(n),type,label,revision:1,fields});
    Android._cm.records=[
      rec(1,'persoon','Zoë <b>',{naam:'Jansen',voornamen:'Zoë Anna',geboortedatum:'2015-02-03',geboorteplaats:'Rotterdam',nationaliteit:'Nederlandse',bsn:'123456782'}),
      rec(2,'persoon','Mama',{naam:'Jansen',voornamen:'Anna Maria',adres:'Straat 1',postcode:'3011 AA',plaats:'Rotterdam',telefoon:'0612345678',bsn:'111222333'}),
      rec(3,'persoon','Papa',{naam:'Jansen',voornamen:'Rene',geboortedatum:'1980-01-01',adres:'Laan 2'}),
      rec(4,'identiteit','Paspoort Zoë',{persoon:id(1),document:'NX12345',geldig:'2030-01-01'}),
      rec(5,'identiteit','ID-kaart papa',{persoon:id(3),document:'IA999',geldig:'2031-01-01'}),
      rec(6,'relatie','Papa – Zoë',{vanPersoon:id(3),naarPersoon:id(1),relatie:'dochter',delen:'ja'}),
      rec(7,'organisatie','Instelling',{naam:'Jeugdzorg BV'})];
  });
  // 0. Nog geen vaste gegevens: het venster biedt ook Zelf invullen.
  await p.evaluate(()=>{openTool('pdf');});await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
  await p.click('#fmq-start');await p.waitForFunction(()=>fmq.open&&cmOpen()&&!$('#fmq-geen').hidden);
  await p.locator('#fmq-geen button',{hasText:'Zelf invullen'}).click();
  assert.equal(await p.evaluate(()=>!fmq.open&&fm.view+' '+fm.step),'stap 0','Zelf invullen opent de eerste stap');
  await p.evaluate(()=>{goBack();Android.masterLock();});await p.waitForFunction(()=>!cmOpen());
  // 1. Eenmalig instellen vanuit Formulieren: de lijst Vaste gegevens in Stamgegevens, daar toevoegen.
  await p.evaluate(()=>{openTool('pdf');});await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
  assert.equal(await p.isVisible('#fmq-start'),true,'Nieuwe reis invullen is de hoofdknop');assert.equal(await p.isVisible('#pff-start-knop'),false);
  assert.equal(await p.isVisible('#fmq-instellen'),true,'rij Vaste gegevens');assert.equal(await p.isVisible('#pff-werk'),false,'stappen pas na een keuze');
  await p.click('#fmq-instellen');assert.equal(await p.evaluate(()=>current),'masterdata');
  await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&current==='cmlijst'&&cmLijstNu()==='vast');
  assert.equal(await p.textContent('#cm-lijst-title'),'Vaste gegevens reistoestemming');assert.match(await p.textContent('#cm-lijst'),/Nog geen vaste gegevens/);
  await p.click('#cm-lijst-add');await p.waitForFunction(()=>cm.edit&&cm.edit.type==='formulier'&&current==='cmedit');
  assert.equal(await p.isVisible('#cmi-form-note'),true);
  assert.equal(await p.evaluate(()=>cm.edit.fields.formulier),'reistoestemming');assert.equal(await p.locator('#cm-field-formulier').count(),0,'één soort formulier: geen keuze in beeld');
  // Opslaan vanaf een ander tabblad terwijl er nog iets ontbreekt: terug naar het tabblad met het veld
  await p.focus('#cm-field-gever1');await p.click('#cm-save');await p.waitForFunction(()=>!cm.mutating&&document.activeElement.id==='cm-field-kind');
  // Alleen personen in de persoonkeuze, alleen documenten van de gekozen persoon.
  assert.deepEqual(await p.$$eval('#cm-field-kind option',o=>o.map(x=>x.textContent)),['Kies een persoon','Zoë <b>','Mama','Papa']);
  await T.select('#cm-field-kind',{label:'Zoë <b>'});
  assert.equal(await p.inputValue('#cm-field-kindDocument'),'00000000-0000-0000-0000-000000000004','enige document vanzelf gekozen');
  assert.equal(await p.evaluate(()=>cmLabel(cm.edit)),'Reistoestemming Zoë <b>','naam vanzelf ingevuld');
  assert.deepEqual(await p.$$eval('#cm-field-kindDocument option',o=>o.map(x=>x.textContent)),['Kies een document','Paspoort Zoë']);
  await T.select('#cm-field-begeleider1',{label:'Papa'});assert.equal(await p.inputValue('#cm-field-begeleider1Document'),'00000000-0000-0000-0000-000000000005');
  await T.select('#cm-field-gever1',{label:'Mama'});assert.equal(await p.inputValue('#cm-field-gever1Document'),'','geen document voor mama');
  await T.select('#cm-field-gever1Instelling',{label:'Instelling'});
  assert.equal(await p.locator('#cm-fields b').count(),0,'namen blijven tekst');
  await p.click('#cm-save');await p.waitForFunction(()=>$('#cm-editor').hidden&&Android._cm.records.some(r=>r.type==='formulier')&&current==='cmkaart'&&!cm.navigating);
  assert.equal(await p.textContent('#cm-kaart-title'),'Reistoestemming Zoë <b>','na opslaan het kaartje');assert.equal(await p.locator('#cm-kaart b b').count(),0);
  // Een gebruikt stamgegeven kun je niet zomaar verwijderen.
  await p.evaluate(()=>{const r=Android._cm.records.find(r=>r.label==='Mama');cmRequest('masterDelete',[r.id,r.revision],()=>{});});
  await p.waitForFunction(()=>$('#cm-kaart-status').textContent.includes('vaste formuliergegevens'));
  // 2. Nieuwe reis: alleen reisdagen en bestemming. Na het verlaten van Stamgegevens is alles vergrendeld;
  // het venster vraagt direct om te ontgrendelen (vingerafdruk), zonder extra tik.
  assert.equal(await p.evaluate(()=>goBack()&&current),'cmlijst','van het kaartje terug naar de lijst');
  assert.deepEqual(await p.$$eval('#cm-lijst .strow b',x=>x.map(e=>e.textContent)),['Reistoestemming Zoë <b>']);assert.match(await p.textContent('#cm-lijst'),/Nog in te vullen: identiteitsbewijs van de toestemminggever/,'wat nog ontbreekt (mama heeft geen document)');
  assert.equal(await p.evaluate(()=>goBack()&&current),'formulieren','terug uit Stamgegevens gaat naar Formulieren');
  // Nog eens Vaste gegevens openen en zonder wijzigen terug: in één keer terug in Formulieren
  await p.click('#fmq-instellen');await p.click('#cm-unlock');await p.waitForFunction(()=>current==='cmlijst'&&cmOpen()&&document.querySelectorAll('#cm-lijst .strow').length===1);
  assert.equal(await p.evaluate(()=>goBack()&&current),'formulieren','terug vanuit de lijst meteen naar Formulieren');assert.equal(await p.evaluate(()=>!cm.token),true,'Stamgegevens verlaten vergrendelt');
  await p.click('#fmq-start');await p.waitForFunction(()=>fmq.open&&cmOpen()&&!$('#fmq-open').hidden&&fmq.records.length===1);
  assert.equal(await p.evaluate(()=>$('#s-formulieren').inert),true,'scherm eronder niet bedienbaar');
  assert.equal(await p.evaluate(()=>getComputedStyle($('#fmq')).position),'fixed','venster over het hele scherm');
  assert.equal(await p.isChecked('#fmq-id'),false,'identiteitsgegevens niet standaard');
  await p.click('#fmq-ga');assert.match(await p.textContent('#fmq-status'),/reisdag/);
  await p.fill('#fmq-van','2027-07-15');await p.fill('#fmq-tot','2027-07-01');await p.selectOption('#fmq-land','Frankrijk');await p.click('#fmq-ga');
  assert.match(await p.textContent('#fmq-status'),/laatste reisdag/);
  await p.fill('#fmq-tot','2027-07-30');await p.fill('#fmq-plaats','Nice');await p.click('#fmq-ga');
  await p.waitForFunction(()=>!fmq.open&&fm.step===fmSecties().length&&fm.view==='stap');
  const v=await p.evaluate(()=>fm.values);
  assert.equal(v['kind-naam'],'Jansen');assert.equal(v['kind-voornamen'],'Zoë Anna');assert.equal(v['van'],'2027-07-15');assert.equal(v['tot'],'2027-07-30');
  assert.equal(v['land'],'Frankrijk');assert.equal(v['bestemming-plaats'],'Nice');assert.equal(v['begeleider1-naam'],'Jansen');assert.equal(v['begeleider1-initialen'],'R.');
  assert.equal(v['begeleider1-relatie'],'ouder','relatie uit stamgegevens, in het Nederlands voor het overheidsformulier');assert.equal(v['gever1-adres'],'Straat 1');assert.equal(v['gever1-instelling'],'Jeugdzorg BV');
  assert.equal(v['kind-document'],'');assert.equal(v['kind-bsn'],'');assert.match(await p.textContent('#pff-melding'),/weggelaten/);
  assert.equal(await p.evaluate(()=>fm.masterSensitive&&Android._cm.form),true,'gebonden aan de ontgrendelde sessie');
  assert.equal(await p.inputValue('#pff-naam'),'Reistoestemming Zoë 2027-07-15');
  await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);
  assert.match(await p.textContent('#pff-samenvatting'),/Frankrijk/);
  // Terug naar de start: het formulier blijft staan en de rij zegt hoe ver het is.
  assert.equal(await p.evaluate(()=>goBack()),true);assert.equal(await p.evaluate(()=>fm.view+' '+current),'start formulieren');
  assert.match(await p.textContent('#pff-zelf-stand'),/Klaar om de PDF te maken/);assert.equal(await p.evaluate(()=>fm.values.land),'Frankrijk');
  // Nog een keer, nu met documentgegevens: vervangt het formulier na bevestiging.
  await p.click('#fmq-start');await p.waitForFunction(()=>$('#modal-ok').getClientRects().length>0);await p.click('#modal-ok');
  await p.waitForFunction(()=>fmq.open&&fmq.records.length===1);
  assert.equal(await p.inputValue('#fmq-land'),'','reisvelden beginnen leeg');
  // Land van bestemming: een keuzelijst; Anders… = zelf typen (bijv. Bonaire)
  await p.selectOption('#fmq-land',await p.evaluate(()=>CMI_ANDERS));await p.waitForSelector('#modal.on');await p.fill('#modal-input','Bonaire');await p.click('#modal-ok');
  await p.waitForFunction(()=>$('#fmq-land').value==='Bonaire');
  await p.fill('#fmq-van','2027-08-01');await p.fill('#fmq-tot','2027-08-10');await p.selectOption('#fmq-land','Spanje');await p.check('#fmq-id');await p.click('#fmq-ga');
  await p.waitForFunction(()=>!fmq.open&&fm.values.land==='Spanje');
  const w=await p.evaluate(()=>fm.values);
  assert.equal(w['kind-document'],'NX12345');assert.equal(w['begeleider1-document'],'IA999');assert.equal(w['kind-bsn'],'','BSN alleen als ingesteld');
  assert.equal(w['bestemming-plaats'],'','oude bestemming niet blijven staan');
  assert.equal(await p.evaluate(()=>Object.values(localStorage).join('').includes('NX12345')),false,'niets in lokale opslag');
  const log=await p.evaluate(()=>Android._cm.events.filter(e=>e.action==='use').map(e=>e.role+':'+e.label));
  assert.ok(log.includes('vast:Reistoestemming Zoë <b>')&&log.includes('kind:Paspoort Zoë')&&log.includes('gever1:Instelling'),'gebruik in het logboek');
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='relation_use'&&e.role==='begeleider1')),'gelezen relatie in het logboek');
  // Een laat antwoord na sluiten wordt genegeerd (geen oude reis of oude toestemming).
  await p.evaluate(()=>{fm.values.land='Behouden';goBack();});
  await p.click('#fmq-start');await p.click('#modal-ok');await p.waitForFunction(()=>fmq.open&&fmq.records.length===1);
  await p.evaluate(()=>{Android._cm.replyDelay=150;});await p.fill('#fmq-van','2027-09-01');await p.fill('#fmq-tot','2027-09-02');await p.selectOption('#fmq-land','Italië');await p.check('#fmq-id');await p.click('#fmq-ga');
  assert.equal(await p.evaluate(()=>goBack()),true,'terugknop sluit eerst het venster');assert.equal(await p.evaluate(()=>fmq.open||current!=='formulieren'),false);
  await p.waitForTimeout(400);assert.equal(await p.evaluate(()=>fm.values.land),'Behouden','laat antwoord vult niets in');await p.evaluate(()=>{Android._cm.replyDelay=10;});
  // Vergrendelen wist alles.
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!fm.masterSensitive&&!cm.token);
  assert.equal(await p.evaluate(()=>JSON.stringify(fm.values).includes('Jansen')),false);
  // Na vergrendelen vraagt het venster vanzelf opnieuw om te ontgrendelen; Escape sluit het.
  // Eigen invoer (hier het land) bleef als concept: eerst de vraag of die vervangen mag worden.
  await p.click('#fmq-start');if(await p.isVisible('#modal.on')){assert.equal(await p.textContent('#modal-title'),'Nieuwe reis invullen?');await p.click('#modal-ok');}
  await p.waitForFunction(()=>fmq.open&&cmOpen()&&!$('#fmq-open').hidden);
  await p.keyboard.press('Escape');assert.equal(await p.evaluate(()=>fmq.open||$('#s-formulieren').inert),false);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
