const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('path');
(async()=>{
 const browser=await chromium.launch();
 for(const colorScheme of ['light','dark']){
  const p=await browser.newPage({viewport:{width:390,height:844},colorScheme}),errors=[];
  p.on('pageerror',e=>errors.push(e.message));await p.goto('file://'+path.join(__dirname,'ui/index.html'));
  // De vaste dataset is de bestaande beveiligde centrale opslag, inclusief herbruikbare reizen.
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen());
  await p.click('button[onclick="cmNew()"]');await p.selectOption('#cm-kind','reis');
  await p.fill('#cm-label','Zomerreis België');await p.fill('#cm-field-van','2027-07-12');await p.fill('#cm-field-tot','2027-07-30');
  await p.fill('#cm-field-land','België');await p.fill('#cm-field-accommodatie','Familie');await p.selectOption('#cm-field-alleen','ja');
  await p.click('#cm-save');await p.waitForFunction(()=>cm.records.length===1&&!cm.mutating);
  await p.evaluate(()=>{Android._cm.records.push({id:crypto.randomUUID(),type:'persoon',label:'Kind <b>Zoë</b>',revision:1,fields:{naam:'Voorbeeld',voornamen:'Zoë',geboortedatum:'2015-02-03',bsn:'123456782'}});});
  await p.evaluate(()=>show('formulieren'));await p.locator('#pff-hulp').evaluate(e=>e.open=true);
  const choose=async(role,label)=>{
   await p.selectOption('#pff-hulp-rol',role);await p.click('#pff-hulp-kies');
   if(await p.isVisible('#cm-pick-unlock'))await p.click('#cm-pick-unlock');
   await p.getByRole('button',{name:label,exact:true}).click();await p.waitForSelector('#cm-pick-fields input[data-field]');
   await p.click('#cm-apply');await p.waitForFunction(()=>!cm.picker);
  };
  await choose('reis','Zomerreis België');await choose('kind','Kind <b>Zoë</b>');
  assert.equal(await p.locator('#pff-hulp-bronnen b').count(),0,'bronnamen blijven tekst');
  assert.equal(await p.evaluate(()=>fmh.sources.some(s=>'bsn' in s.fields)),false,'identiteitsgegevens niet standaard vrijgegeven');
  assert.equal(await p.inputValue('#pff-v-kind-naam'),'','bron kiezen vult nog niets in');
  await p.click('#pff-hulp-vast');assert.ok(await p.locator('#pff-hulp-voorstellen input').count()>=5);
  assert.equal(await p.isChecked('#pff-hulp-voorstellen input[data-field="alleen"]'),false,'bestaande keuzes worden niet automatisch vervangen');
  await p.click('#pff-hulp-overnemen');assert.equal(await p.inputValue('#pff-v-kind-naam'),'Voorbeeld');assert.equal(await p.inputValue('#pff-v-van'),'2027-07-12');
  assert.equal(await p.inputValue('#pff-v-alleen'),'nee');
  // Een lokale AI-vraag krijgt alleen de expliciet gekozen bronvelden en de huidige stap.
  await p.fill('#pff-hulp-tekst','We vertrekken op 14-07-2027.');
  await p.evaluate(()=>{Android._pdf.formAiRows=[{id:'van',value:'2027-07-14',sourceType:'text',source:'Je toelichting'}];});
  await p.click('#pff-hulp-ai');await p.waitForFunction(()=>!fmh.busy&&fmh.rows.length===1);
  const request=await p.evaluate(()=>Android._pdf.formAi);
  assert.ok(!JSON.stringify(request.input).includes('123456782'));assert.equal(request.input.text,'We vertrekken op 14-07-2027.');
  assert.ok(request.input.fields.some(f=>f.id==='van'));assert.ok(!request.input.fields.some(f=>f.id==='gever1-naam'));
  assert.equal(await p.inputValue('#pff-v-van'),'2027-07-12','AI overschrijft niets vóór overnemen');
  assert.equal(await p.isChecked('#pff-hulp-voorstellen input[data-field="van"]'),false);
  await p.check('#pff-hulp-voorstellen input[data-field="van"]');await p.click('#pff-hulp-overnemen');assert.equal(await p.inputValue('#pff-v-van'),'2027-07-14');
  // Wijzigingen tijdens een antwoord annuleren de aanvraag; late antwoorden verliezen hun bevoegdheid.
  await p.evaluate(()=>Android._pdf.formAiDelay=500);await p.click('#pff-hulp-ai');const old=await p.evaluate(()=>({request:fmh.request,token:cm.token}));
  await p.fill('#pff-v-kind-naam','Handmatig');assert.equal(await p.evaluate(()=>fmh.busy),false);
  await p.evaluate(r=>onPdfFormAi({...r,fields:[{id:'kind-naam',value:'Oud',source:'Oud'}]}),old);
  assert.equal(await p.inputValue('#pff-v-kind-naam'),'Handmatig');assert.equal(await p.locator('#pff-hulp-voorstellen input').count(),0);
  await p.click('#pff-hulp-ai');await p.click('#pff-hulp-stop');assert.equal(await p.evaluate(()=>fmh.busy),false);assert.match(await p.textContent('#pff-hulp-status'),/gestopt/);
  await p.evaluate(()=>Android._pdf.formAiError='Download eerst een taalmodel (Instellingen → Taalmodel)');await p.click('#pff-hulp-ai');assert.match(await p.textContent('#pff-hulp-status'),/taalmodel/);
  await p.evaluate(()=>{Android._pdf.formAiError='';show('home');});assert.equal(await p.evaluate(()=>uiCanReload()),false,'gekozen bronnen en toelichting worden beschermd');
  await p.evaluate(()=>{show('formulieren');Android.masterLock();});await p.waitForFunction(()=>!cm.token&&!fmh.sources.length);
  assert.equal(await p.inputValue('#pff-hulp-tekst'),'');assert.equal(await p.locator('#pff-hulp-voorstellen input').count(),0);assert.equal(await p.evaluate(()=>fm.values['kind-naam']),'');
  assert.equal(await p.evaluate(()=>Object.values(localStorage).join('').includes('123456782')),false,'geen brondata in webopslag');
  // De reis bestaat na vergrendelen nog in de native dataset en kan bij de bestemming opnieuw gekozen worden.
  await p.evaluate(()=>{fm.step=fmSecties().findIndex(s=>s.fields.some(f=>f.id==='accommodatie'));fmRender();});
  await choose('reis','Zomerreis België');await p.click('#pff-hulp-vast');await p.click('#pff-hulp-overnemen');assert.equal(await p.inputValue('#pff-v-land'),'België');
  await p.screenshot({path:path.join(__dirname,'shots','formulieren-ai-'+colorScheme+'.png'),fullPage:true});
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  assert.deepEqual(errors,[]);console.log(colorScheme,'vaste reizen/personen, bewuste voorstellen, lokale AI, geen stil overschrijven, annuleren en vergrendelen; errors []');await p.close();
 }
 const en=await browser.newPage({viewport:{width:360,height:800}});
 await en.goto('file://'+path.join(__dirname,'ui/index.html'));await en.evaluate(()=>{rgSave({lang:'en'});show('formulieren');});
 await en.locator('#pff-hulp').evaluate(e=>e.open=true);
 assert.match(await en.textContent('#pff-hulp summary'),/local AI/);
 assert.match(await en.getAttribute('#pff-hulp-tekst','placeholder'),/accompanying adult/);
 await en.evaluate(()=>{Android._cm.records.push({id:crypto.randomUUID(),type:'reis',label:'Instellingen',revision:1,fields:{van:'2027-07-12'}});});
 await en.selectOption('#pff-hulp-rol','reis');await en.click('#pff-hulp-kies');await en.click('#cm-pick-unlock');
 await en.getByRole('button',{name:'Instellingen',exact:true}).click();await en.waitForSelector('#cm-pick-fields input[data-field="van"]');await en.click('#cm-apply');await en.waitForFunction(()=>!cm.picker);
 await en.click('#pff-hulp-vast');assert.match(await en.textContent('#pff-hulp-voorstellen'),/First travel day/);assert.match(await en.textContent('#pff-hulp-voorstellen'),/Source: Instellingen/);
 await en.close();await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
