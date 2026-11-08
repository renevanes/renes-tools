// Rijbewijsvoorstel: expliciet controleren, één keer bewaren, conflicten en vergrendelen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const browser=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
 await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(250);
 await p.evaluate(()=>{
  Android._cm.records=[{id:'owner',type:'persoon',label:'Anna',revision:1,fields:{naam:'van Dijk',voornamen:'Anna'}}];
  Android._cm.photoDelay=450;
  Android._cm.herkend={soort:'rijbewijs',bron:'tekst',zeker:false,document:{documentsoort:'rijbewijs',document:'0123456789',afgifte:'2020-04-03',geldig:'2030-04-03',afgegevenDoor:'RDW',categorieen:'AM, B, BE'},persoon:{naam:'VAN DIJK',voornamen:'ANNA',geboortedatum:'1980-02-01'},twijfel:['afgegevenDoor']};
  show('masterdata');
 });
 await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 await p.evaluate(()=>cmEdit('owner','documenten'));await p.waitForFunction(()=>cm.edit?.id==='owner');
 await p.click('#cmh-scan');await p.waitForFunction(()=>cm.mutating);
 await p.evaluate(()=>{Android._cm.replyDelay=150;});
 assert.equal(await p.isDisabled('#cm-field-naam'),true,'geen handmatige wijziging tijdens verwerking gekozen foto');
 await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>current),'cmedit','bewerking blijft staan tijdens verwerking');
 await p.waitForFunction(()=>cmh.photoBusy&&cm.recordsReady&&cm.edit?.id==='owner'&&cm.pending.size>0);
 assert.equal(await p.isDisabled('#cm-field-naam'),true,'invoer blijft geblokkeerd tot document is geladen');
 await p.waitForFunction(()=>cm.edit?.type==='identiteit'&&!$('#cmh-panel').hidden);
 await p.evaluate(()=>{Android._cm.replyDelay=10;});
 const doc=await p.evaluate(()=>cm.edit.id);
 assert.deepEqual(await p.$$eval('#cm-tabs button',bs=>bs.map(b=>b.textContent)),['Gegevens','Foto’s'],'twee duidelijke tabbladen');
 assert.equal(await p.evaluate(()=>cm.edit.label),'Rijbewijs Anna');
 assert.equal(await p.evaluate(()=>!!cm.edit.images.front),true,'rijbewijsfoto als voorkant');
 assert.match(await p.textContent('#cmh-source'),/geen controlecijfers/);assert.doesNotMatch(await p.textContent('#cmh-source'),/Niet alle controlecijfers kloppen/,'tekstherkenning heeft een eigen uitleg');
 for(const key of ['naam','voornamen','afgegevenDoor'])assert.equal(await p.isChecked(`#cmh-list input[data-key="${key}"]`),false,'afwijkend of onzeker veld uit: '+key);
 assert.equal(await p.isChecked('#cmh-list input[data-key="categorieen"]'),true);
 assert.equal(await p.locator('#cmh-list input[data-key="bsn"]').count(),0,'geen afgeleid BSN');
 await p.click('#cmh-numbers');assert.match(await p.textContent('#cmh-list'),/0123456789/,'nummer helemaal na expliciet tonen');
 assert.equal(await p.isChecked('#cmh-list input[data-key="afgegevenDoor"]'),false,'keuzes blijven bij tonen behouden');
 await p.click('#cmh-numbers');assert.doesNotMatch(await p.textContent('#cmh-list'),/0123456789/);
 await p.click('#cm-save');await p.waitForFunction(()=>cm.edit?.id==='owner'&&cm.tab==='documenten');
 assert.equal(await p.evaluate(()=>Android._cm.records.find(r=>r.id==='owner').fields.naam),'van Dijk','bestaande naam behouden');
 assert.equal(await p.evaluate(()=>Android._cm.records.find(r=>r.id==='owner').fields.geboortedatum),'1980-02-01');
 assert.equal(await p.evaluate(doc=>Android._cm.records.find(r=>r.id===doc).fields.categorieen,doc),'AM, B, BE');
 assert.equal(await p.evaluate(doc=>Android._cm.records.find(r=>r.id===doc).fields.afgegevenDoor||'',doc),'','onzekere instantie niet bewaard');
 await p.evaluate(doc=>cmEdit(doc,'document'),doc);await p.waitForFunction(doc=>cm.edit?.id===doc,doc);
 assert.equal(await p.isVisible('#cm-field-categorieen'),true);assert.equal(await p.isVisible('#cm-field-afgegevenDoor'),false,'extra velden ingeklapt');
 await p.locator('details[data-k="document-extra"] summary').click();assert.equal(await p.isVisible('#cm-field-afgegevenDoor'),true);
 // Een handmatige wijziging tijdens vertraagde OCR mag niet worden weggegooid door het resultaat.
 await p.evaluate(()=>{Android._cm.replyDelay=400;});await p.click('#cm-tab-fotos');await p.click('#cmh-photo-scan');
 await p.click('#cm-tab-document');await p.fill('#cm-field-categorieen','B');
 await p.waitForFunction(()=>!cmh.scanBusy);assert.equal(await p.isVisible('#cmh-panel'),false);
 assert.equal(await p.inputValue('#cm-field-categorieen'),'B','tussentijdse wijziging behouden');
 await p.evaluate(()=>{Android._cm.replyDelay=10;cm.dirty=false;});
 await p.evaluate(doc=>cmEdit(doc,'fotos'),doc);await p.waitForFunction(()=>cm.tab==='fotos'&&!cm.pending.size);
 // Gewijzigde gegevens worden nooit deels overschreven bij een verouderd voorstel.
 await p.click('#cm-tab-fotos');await p.click('#cmh-photo-scan');await p.waitForFunction(()=>!$('#cmh-panel').hidden);
 await p.check('#cmh-list input[data-key="afgegevenDoor"]');
 await p.evaluate(()=>{Android._cm.records.find(r=>r.id==='owner').revision++;});
 const before=await p.evaluate(()=>JSON.stringify(Android._cm.records));
 await p.click('#cm-save');await p.waitForFunction(()=>!cm.mutating&&/intussen gewijzigd/.test($('#cm-edit-status').textContent));
 assert.equal(await p.evaluate(()=>JSON.stringify(Android._cm.records)),before,'conflict verandert geen enkel record');
 assert.equal(await p.isVisible('#cmh-panel'),true,'keuzes blijven leesbaar na conflict');
 await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
 assert.equal(await p.evaluate(()=>cmh.data===null&&cmh.current===null&&$('#cmh-list').children.length===0),true,'voorstel en eigenaar gewist');
 assert.equal(await p.getAttribute('#cmi-front-image','src'),null,'foto gewist bij vergrendelen');
 await p.close();
}
console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
