const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('path');
(async()=>{
 const browser=await chromium.launch();
 for(const colorScheme of ['light','dark']){
  const p=await browser.newPage({viewport:{width:390,height:844},colorScheme}),errors=[];
  p.on('pageerror',e=>errors.push(e.message));await p.goto('file://'+path.join(__dirname,'ui/index.html'));const T=require('./_stamtabs')(p);
  // De vaste dataset is de bestaande beveiligde centrale opslag, inclusief herbruikbare reizen.
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen());
  await p.waitForFunction(()=>cm.recordsReady);await p.evaluate(()=>cmiNew('reis'));
  await T.fill('#cm-label','Zomerreis België');await T.fill('#cm-field-van','2027-07-12');await T.fill('#cm-field-tot','2027-07-30');
  await T.fill('#cm-field-land','België');await T.fill('#cm-field-accommodatie','Familie');await T.select('#cm-field-alleen','ja');
  await p.click('#cm-save');await p.waitForFunction(()=>cm.records.length===1&&!cm.mutating);
  await p.evaluate(()=>{const person=crypto.randomUUID();Android._cm.records.push({id:person,type:'persoon',label:'Kind <b>Zoë</b>',revision:1,fields:{naam:'Voorbeeld',voornamen:'Zoë',geboortedatum:'2015-02-03',bsn:'123456782'}},{id:crypto.randomUUID(),type:'identiteit',label:'Paspoort Zoë',revision:1,fields:{persoon:person,document:'DOC123456',geldig:'2030-06-30'}});});
  // De invulhulp staat niet standaard in beeld: openen via het menu van een stap.
  await p.evaluate(()=>show('formulieren'));await p.click('#pff-zelf');assert.equal(await p.isVisible('#pff-hulp'),false);
  const openHulp=async()=>{await p.click('#pff-menu');await p.locator('#sheet-acts button',{hasText:'Invulhulp met lokale AI'}).click();await p.waitForFunction(()=>!$('#pff-hulp').hidden&&$('#pff-hulp').open);};
  await openHulp();
  const choose=async(role,label,secret=[])=>{
   await p.selectOption('#pff-hulp-rol',role);await p.click('#pff-hulp-kies');
   if(await p.isVisible('#cm-pick-unlock'))await p.click('#cm-pick-unlock');
   await p.getByRole('button',{name:label,exact:true}).click();await p.waitForSelector('#cm-pick-fields input[data-field]');
   for(const key of secret)await p.check('#cm-pick-fields input[data-field="'+key+'"]');
   if(secret.length)await p.check('#cm-sensitive');
   await p.click('#cm-apply');await p.waitForFunction(()=>!cm.picker);
  };
  // Stap Reis: een vaste reis vult reisdagen en bestemming, maar vervangt bestaande keuzes niet vanzelf.
  await p.click('#pff-meer summary');await p.click('#pff-meer summary');assert.equal(await p.evaluate(()=>$('#pff-meer').open),false);
  await choose('reis','Zomerreis België');await p.click('#pff-hulp-vast');assert.ok(await p.locator('#pff-hulp-voorstellen input').count()>=5);
  assert.equal(await p.isChecked('#pff-hulp-voorstellen input[data-field="alleen"]'),false,'bestaande keuzes worden niet automatisch vervangen');
  await p.click('#pff-hulp-overnemen');assert.equal(await p.inputValue('#pff-v-van'),'2027-07-12');assert.equal(await p.inputValue('#pff-v-land'),'België');
  assert.equal(await p.inputValue('#pff-v-accommodatie'),'Familie');assert.equal(await p.inputValue('#pff-v-alleen'),'nee');
  assert.equal(await p.evaluate(()=>$('#pff-meer').open),true,'overgenomen optionele gegevens blijven niet verborgen');
  await p.click('#pff-volgende');assert.match(await p.textContent('#pff-titel'),/^Kind$/);assert.equal(await p.isVisible('#pff-hulp'),true,'invulhulp blijft open bij de volgende stap');
  // Stap Kind: persoon en document als bron; een document hoort bij de gekozen persoon.
  await choose('kind','Kind <b>Zoë</b>');
  await choose('kind',/^Paspoort Zoë/,['document']);assert.equal(await p.evaluate(()=>fmh.sources.filter(s=>s.type==='identiteit').length),1);
  await p.locator('#pff-hulp-bronnen .row').filter({hasText:'Kind <b>Zoë</b>'}).getByRole('button').click();
  assert.equal(await p.evaluate(()=>fmh.sources.some(s=>s.role==='kind')),false,'persoonsbron verwijderen trekt het gekoppelde document ook in');
  assert.equal(await p.evaluate(()=>fmh.sources.filter(s=>s.role==='reis').length),1,'andere rollen blijven geselecteerd');
  await choose('kind','Kind <b>Zoë</b>');
  await choose('kind','Kind <b>Zoë</b>',['bsn']);assert.equal(await p.evaluate(()=>fmh.sources.some(s=>s.fields.bsn==='123456782')),true,'bewust gekozen BSN beschikbaar');
  await choose('kind','Kind <b>Zoë</b>');assert.equal(await p.evaluate(()=>fmh.sources.some(s=>'bsn' in s.fields)),false,'opnieuw niet kiezen trekt een eerdere veldselectie in');
  assert.equal(await p.locator('#pff-hulp-bronnen b').count(),0,'bronnamen blijven tekst');
  assert.equal(await p.evaluate(()=>fmh.sources.some(s=>'bsn' in s.fields)),false,'identiteitsgegevens niet standaard vrijgegeven');
  assert.equal(await p.inputValue('#pff-v-kind-naam'),'','bron kiezen vult nog niets in');
  await p.click('#pff-hulp-vast');assert.ok(await p.locator('#pff-hulp-voorstellen input').count()>=3);
  await p.click('#pff-hulp-overnemen');assert.equal(await p.inputValue('#pff-v-kind-naam'),'Voorbeeld');assert.equal(await p.inputValue('#pff-v-kind-geboortedatum'),'2015-02-03');
  // Een lokale AI-vraag krijgt alleen de expliciet gekozen bronvelden en de huidige stap.
  await p.fill('#pff-hulp-tekst','De achternaam is Jansen.');
  await p.evaluate(()=>{Android._pdf.formAiRows=[{id:'kind-naam',value:'Jansen',sourceType:'text',source:'Je toelichting'}];});
  await p.click('#pff-hulp-ai');await p.waitForFunction(()=>!fmh.busy&&fmh.rows.length===1);
  const request=await p.evaluate(()=>Android._pdf.formAi);
  assert.ok(!JSON.stringify(request.input).includes('123456782'));assert.equal(request.input.text,'De achternaam is Jansen.');
  assert.ok(request.input.fields.some(f=>f.id==='kind-naam'));assert.ok(!request.input.fields.some(f=>f.id==='gever1-naam'||f.id==='van'));
  assert.equal(await p.inputValue('#pff-v-kind-naam'),'Voorbeeld','AI overschrijft niets vóór overnemen');
  assert.equal(await p.isChecked('#pff-hulp-voorstellen input[data-field="kind-naam"]'),false);
  await p.check('#pff-hulp-voorstellen input[data-field="kind-naam"]');await p.click('#pff-hulp-overnemen');assert.equal(await p.inputValue('#pff-v-kind-naam'),'Jansen');
  // Wijzigingen tijdens een antwoord annuleren de aanvraag; late antwoorden verliezen hun bevoegdheid.
  await p.evaluate(()=>Android._pdf.formAiDelay=500);await p.click('#pff-hulp-ai');const old=await p.evaluate(()=>({request:fmh.request,token:cm.token}));
  await p.fill('#pff-v-kind-naam','Handmatig');assert.equal(await p.evaluate(()=>fmh.busy),false);
  await p.evaluate(r=>onPdfFormAi({...r,fields:[{id:'kind-naam',value:'Oud',source:'Oud'}]}),old);
  assert.equal(await p.inputValue('#pff-v-kind-naam'),'Handmatig');assert.equal(await p.locator('#pff-hulp-voorstellen input').count(),0);
  await p.click('#pff-hulp-ai');await p.click('#pff-hulp-stop');assert.equal(await p.evaluate(()=>fmh.busy),false);assert.match(await p.textContent('#pff-hulp-status'),/gestopt/);
  await p.click('#pff-hulp-ai');const leaving=await p.evaluate(()=>({request:fmh.request,token:cm.token}));
  assert.equal(await p.isDisabled('#cm-form-pick'),true,'geen tweede gegevenskiezer tijdens een antwoord');
  assert.equal(await p.evaluate(()=>fmMenuActs().some(a=>a[1]===fmWissen)),false,'niet leegmaken tijdens een antwoord');
  await p.evaluate(()=>show('home'));assert.equal(await p.evaluate(()=>fmh.busy),false,'scherm verlaten beëindigt de eigen AI-vraag');
  await p.evaluate(r=>onPdfFormAi({...r,fields:[{id:'kind-geboorteplaats',value:'Oud',source:'Oud'}]}),leaving);
  await p.evaluate(()=>show('formulieren'));await p.waitForFunction(()=>cmOpen());assert.equal(await p.isDisabled('#pff-hulp-ai'),false,'terugkeren blijft bedienbaar');
  await p.evaluate(()=>Android._pdf.formAiError='Download eerst een taalmodel (Instellingen → Taalmodel)');await p.click('#pff-hulp-ai');assert.match(await p.textContent('#pff-hulp-status'),/taalmodel/);
  await p.evaluate(()=>{Android._pdf.formAiError='';show('home');});assert.equal(await p.evaluate(()=>uiCanReload()),false,'gekozen bronnen en toelichting worden beschermd');
  await p.evaluate(()=>{show('formulieren');Android.masterLock();});await p.waitForFunction(()=>!cm.token&&!fmh.sources.length);
  assert.equal(await p.inputValue('#pff-hulp-tekst'),'');assert.equal(await p.locator('#pff-hulp-voorstellen input').count(),0);assert.equal(await p.evaluate(()=>fm.values['kind-naam']),'Handmatig','eigen invoer blijft als concept; gegevens uit Stamgegevens niet');
  assert.equal(await p.evaluate(()=>fm.values['kind-geboortedatum']),'','overgenomen voorstel uit Stamgegevens is gewist');
  assert.equal(await p.evaluate(()=>fm.view+' '+fmh.shown),'start false','vergrendelen sluit de invulhulp en gaat terug naar de start');
  assert.equal(await p.evaluate(()=>Object.values(localStorage).join('').includes('123456782')),false,'geen brondata in webopslag');
  // De reis bestaat na vergrendelen nog in de native dataset en kan opnieuw gekozen worden.
  // Op de stap Reis kiest "Overnemen uit stamgegevens" een reis (geen adres).
  // Het concept onthield de stap waar je was: eerst terug naar de stap Reis.
  await p.click('#pff-zelf');await p.evaluate(()=>{fm.step=0;fmRender();});await p.click('#cm-form-pick');if(await p.isVisible('#cm-pick-unlock'))await p.click('#cm-pick-unlock');
  await p.getByRole('button',{name:'Zomerreis België',exact:true}).click();await p.waitForSelector('#cm-pick-fields input[data-field]');
  await p.click('#cm-apply');await p.waitForFunction(()=>!cm.picker);
  assert.equal(await p.inputValue('#pff-v-land'),'België');assert.equal(await p.inputValue('#pff-v-accommodatie'),'Familie');assert.equal(await p.evaluate(()=>$('#pff-meer').open),true);
  // Invulhulp sluiten wist de bronnen en zet de focus op de stap.
  await openHulp();await choose('reis','Zomerreis België');
  await p.click('#pff-hulp-sluit');assert.equal(await p.evaluate(()=>document.activeElement.id+' '+fmh.shown+' '+fmh.sources.length+' '+$('#pff-hulp').hidden),'pff-titel false 0 true');
  await openHulp();await choose('reis','Zomerreis België');
  // Terug naar de start sluit de invulhulp en wist de gekozen bronnen.
  await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>fm.view+' '+fmh.sources.length+' '+fmh.shown),'start 0 false');
  await p.screenshot({path:path.join(__dirname,'shots','formulieren-ai-'+colorScheme+'.png'),fullPage:true});
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  assert.deepEqual(errors,[]);console.log(colorScheme,'vaste reizen/personen, bewuste voorstellen, lokale AI, geen stil overschrijven, annuleren en vergrendelen; errors []');await p.close();
 }
 const en=await browser.newPage({viewport:{width:360,height:800}});
 await en.goto('file://'+path.join(__dirname,'ui/index.html'));await en.evaluate(()=>{rgSave({lang:'en'});show('formulieren');});
 await en.click('#pff-zelf');await en.click('#pff-menu');await en.locator('#sheet-acts button',{hasText:'Form assistance with local AI'}).click();await en.waitForFunction(()=>!$('#pff-hulp').hidden);
 assert.match(await en.textContent('#pff-hulp summary'),/local AI/);
 assert.match(await en.getAttribute('#pff-hulp-tekst','placeholder'),/accompanying adult/);
 await en.evaluate(()=>{Android._cm.records.push({id:crypto.randomUUID(),type:'reis',label:'Instellingen',revision:1,fields:{van:'2027-07-12'}});});
 await en.selectOption('#pff-hulp-rol','reis');await en.click('#pff-hulp-kies');await en.click('#cm-pick-unlock');
 await en.getByRole('button',{name:'Instellingen',exact:true}).click();await en.waitForSelector('#cm-pick-fields input[data-field="van"]');await en.click('#cm-apply');await en.waitForFunction(()=>!cm.picker);
 await en.click('#pff-hulp-vast');assert.match(await en.textContent('#pff-hulp-voorstellen'),/First travel day/);assert.match(await en.textContent('#pff-hulp-voorstellen'),/Source: Instellingen/);
 await en.close();await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
