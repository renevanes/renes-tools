// Rijbewijsvoorstel: expliciet controleren, één keer bewaren, conflicten en vergrendelen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const browser=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
 await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(250);const T=require('./_stamtabs')(p);
 await p.evaluate(()=>{
  Android._cm.records=[{id:'owner',type:'persoon',label:'Anna',revision:1,fields:{naam:'van Dijk',voornamen:'Anna'}}];
  Android._cm.photoDelay=450;
  Android._cm.herkend={soort:'rijbewijs',bron:'tekst',zeker:false,document:{documentsoort:'rijbewijs',document:'0123456789',afgifte:'2020-04-03',geldig:'2030-04-03',afgegevenDoor:'RDW',categorieen:'AM, B, BE'},persoon:{naam:'VAN DIJK',voornamen:'ANNA',geboortedatum:'1980-02-01'},twijfel:['afgegevenDoor']};
  show('masterdata');
 });
 await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 await T.kaart('owner');await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');
 await p.getByRole('button',{name:'Rijbewijs',exact:true}).click();await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Rijbewijs');
 await p.getByRole('button',{name:'Foto kiezen',exact:true}).click();await p.waitForFunction(()=>cm.mutating);
 await p.evaluate(()=>{Android._cm.replyDelay=150;});
 assert.equal(await p.isDisabled('#cmc-doc-add'),true,'geen andere acties tijdens verwerking gekozen foto');
 await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>current+' '+cmc.rec?.id),'cmkaart owner','kaartje blijft staan tijdens verwerking');
 await p.waitForFunction(()=>cmh.photoBusy&&cm.recordsReady&&current==='cmkaart'&&cm.pending.size>0);
 assert.equal(await p.isDisabled('#cmc-doc-add'),true,'blijft geblokkeerd tot het document is geladen');
 await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='identiteit'&&!$('#cmh-panel').hidden);
 await p.evaluate(()=>{Android._cm.replyDelay=10;});
 const doc=await p.evaluate(()=>cm.edit.id);
 assert.equal(await p.evaluate(()=>cm.tab),'document','voorstellen in het blok Document');
 assert.equal(await p.evaluate(()=>cm.edit.label),'Rijbewijs Anna');
 assert.ok(await p.evaluate(()=>$('#cm-save').getBoundingClientRect().width>=innerWidth-40),'bewaaractie gebruikt de schermbreedte');
 assert.equal(await p.evaluate(()=>!!cm.edit.images.front),true,'rijbewijsfoto als voorkant');
 assert.match(await p.textContent('#cmh-source'),/geen controlecijfers/);assert.doesNotMatch(await p.textContent('#cmh-source'),/Niet alle controlecijfers kloppen/,'tekstherkenning heeft een eigen uitleg');
 for(const key of ['naam','voornamen','afgegevenDoor'])assert.equal(await p.isChecked(`#cmh-list input[data-key="${key}"]`),false,'afwijkend of onzeker veld uit: '+key);
 assert.equal(await p.isChecked('#cmh-list input[data-key="categorieen"]'),true);
 assert.equal(await p.locator('#cmh-list input[data-key="bsn"]').count(),0,'geen afgeleid BSN');
 await p.click('#cmh-numbers');assert.match(await p.textContent('#cmh-list'),/0123456789/,'nummer helemaal na expliciet tonen');
 assert.equal(await p.isChecked('#cmh-list input[data-key="afgegevenDoor"]'),false,'keuzes blijven bij tonen behouden');
 await p.click('#cmh-numbers');assert.doesNotMatch(await p.textContent('#cmh-list'),/0123456789/);
 await p.click('#cm-save');await p.waitForFunction(doc=>current==='cmkaart'&&cmc.rec?.id===doc&&!cm.mutating,doc);
 assert.equal(await p.evaluate(()=>Android._cm.records.find(r=>r.id==='owner').fields.naam),'van Dijk','bestaande naam behouden');
 assert.equal(await p.evaluate(()=>Android._cm.records.find(r=>r.id==='owner').fields.geboortedatum),'1980-02-01');
 assert.equal(await p.evaluate(doc=>Android._cm.records.find(r=>r.id===doc).fields.categorieen,doc),'AM, B, BE');
 assert.equal(await p.evaluate(doc=>Android._cm.records.find(r=>r.id===doc).fields.afgegevenDoor||'',doc),'','onzekere instantie niet bewaard');
 assert.match(await p.textContent('#cm-kaart [data-blok=document]'),/AM, B, BE/,'categorieën op het kaartje');
 await T.blok(doc,'afgifte');assert.equal(await p.isVisible('#cm-field-afgegevenDoor'),true,'afgifte in een eigen blok');assert.equal(await p.isVisible('#cm-field-categorieen'),false);
 await p.evaluate(()=>{cm.dirty=false;cmCancelEdit();});await p.waitForFunction(()=>current==='cmkaart'&&!cm.navigating);
 await T.blok(doc,'document');
 assert.equal(await p.isVisible('#cm-field-categorieen'),true);assert.equal(await p.isVisible('#cm-field-afgegevenDoor'),false,'alleen het blok Document');
 // Een handmatige wijziging tijdens vertraagde OCR mag niet worden weggegooid door het resultaat.
 await p.evaluate(()=>{Android._cm.replyDelay=400;cmTab('fotos');});await p.click('#cmh-photo-scan');
 await p.evaluate(()=>cmTab('document'));await p.click('#cm-field-categorieen button[data-cat="B"]');
 await p.waitForFunction(()=>!cmh.scanBusy);assert.equal(await p.isVisible('#cmh-panel'),false);
 assert.equal(await p.evaluate(()=>cm.edit.fields.categorieen),'AM, BE','tussentijdse wijziging behouden');assert.equal(await p.getAttribute('#cm-field-categorieen button[data-cat="B"]','aria-pressed'),'false');
 await p.evaluate(()=>{Android._cm.replyDelay=10;cm.dirty=false;});
 await p.evaluate(()=>cmCancelEdit());await p.waitForFunction(()=>current==='cmkaart'&&!cm.navigating);await T.blok(doc,'fotos');
 // Gewijzigde gegevens worden nooit deels overschreven bij een verouderd voorstel.
 await p.click('#cmh-photo-scan');await p.waitForFunction(()=>!$('#cmh-panel').hidden);
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
