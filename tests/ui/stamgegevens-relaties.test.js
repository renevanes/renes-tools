// Stamgegevens → het kaartje van een persoon: relaties zien en toevoegen (kind, ouder, partner, andere), vaste relaties en status.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');const fs=require('fs');
// Vaste relaties en de relaties met ouderlijk gezag: interface en MasterIdentity.java hetzelfde, elke relatie in precies één groep
{const js=fs.readFileSync(path.join(__dirname,'../../web/src/script/71-stamgegevens-profiel.js'),'utf8'),java=fs.readFileSync(path.join(__dirname,'../../app/src/nl/rene/tools/MasterIdentity.java'),'utf8');
 const codes=re=>[...java.match(re)[0].matchAll(/"(\w+)"/g)].map(m=>m[1]);
 const names=Object.keys(Function('return '+js.match(/const CMI_RELATIONS=(\{[^;]*\});/)[1])()),groups=Function('return '+js.match(/const CMI_RELATION_GROUPS=(\[[^;]*\]);/)[1])().flatMap(g=>g[1]);
 assert.deepEqual([...names].sort(),codes(/RELATIONS=[^;]*;/).sort(),'CMI_RELATIONS = MasterIdentity.RELATIONS');assert.deepEqual([...groups].sort(),[...names].sort(),'elke relatie in precies één groep');
 assert.deepEqual(Function('return '+js.match(/const CMI_OUDER_KIND=(\[[^;]*\]);/)[1])(),codes(/OUDER_KIND=[^;]*;/),'CMI_OUDER_KIND = MasterIdentity.OUDER_KIND');}
(async()=>{const browser=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));await p.goto('file://'+path.join(__dirname,'ui/index.html'));const T=require('./_stamtabs')(p);
 await p.evaluate(()=>show('masterdata'));await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 const person=(label,naam)=>T.maak('persoon',label,{naam});
 const open=T.kaart;
 const list=()=>p.evaluate(()=>{const b=$('#cm-kaart [data-blok=relaties]');return [...b.querySelectorAll('.strow b')].map(e=>e.textContent).sort().concat([...b.querySelectorAll('p.hint')].map(e=>e.textContent));});
 const note=()=>p.evaluate(()=>$('#cm-edit-status').textContent);
 const zin=()=>p.evaluate(()=>$('#cmi-relation-zin').hidden?'':$('#cmi-relation-zin').textContent);
 const options=id=>p.evaluate(id=>$('#'+id)?[...$('#'+id).options].map(o=>o.value):null,id);
 // Relaties van deze persoon (kind, ouder, partner, andere) staan in het keuzemenu achter "Relatie toevoegen…" op het kaartje
 const kies=async code=>{await p.click('#cmc-rel-add');await p.waitForSelector('#sheet.on');await p.click('#sheet-acts button:nth-child('+({kind:1,ouder:2,partner:3,'':4}[code])+')');};
 const add=async(code,naam)=>{await kies(code);await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!cm.edit.id);if(naam)await T.select('#cm-field-naarPersoon',naam);};
 // Opslaan vanaf een persoon: daarna weer op het kaartje van die persoon (eindtoestand afwachten, niet het korte moment ertussen)
 const save=async()=>{const n=await p.evaluate(()=>cm.records.length),from=await p.evaluate(()=>cm.back?.id);await p.click('#cm-save');await p.waitForFunction(([n,from])=>!cm.mutating&&cm.recordsReady&&cm.records.length>=n&&current==='cmkaart'&&cmc.rec?.id===from&&!cm.pending.size&&!cm.navigating,[n,from]);};

 // Eén persoon: de knop staat er, maar er is nog niemand om aan te koppelen
 const rene=await person('Rene','van Es');await open(rene);
 assert.deepEqual(await list(),['Geen relaties met anderen.']);assert.equal(await p.textContent('#cmc-rel-add'),'Relatie toevoegen…','zonder jou: gewoon relatie toevoegen');
 await p.click('#cmc-rel-add');await p.waitForSelector('#sheet.on');assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),['Kind van Rene','Ouder van Rene','Partner van Rene','Andere relatie met Rene']);await p.evaluate(()=>closeSheet());
 await kies('kind');assert.equal(await p.textContent('#cm-kaart-status'),'Voeg eerst de andere persoon toe.');assert.equal(await p.evaluate(()=>current+' '+!!cm.edit),'cmkaart false');
 const anna=await person('Anna','van Es'),sam=await person('Sam','de Vries'),kees=await person('Kees','Jansen');

 // Kind toevoegen: de eerste persoon staat vast, je kiest het kind; de zin vult zich in, de naam in het overzicht bij opslaan
 await open(rene);await add('kind');
 assert.deepEqual(await p.evaluate(()=>({...cm.edit.fields})),{vanPersoon:rene,naarPersoon:'',relatie:'kind',delen:'ja'});
 assert.equal(await p.evaluate(()=>document.activeElement.id),'cm-field-naarPersoon','meteen de tweede persoon kiezen');
 assert.equal(await zin(),'');
 assert.deepEqual(await p.evaluate(()=>[...$('#cm-field-relatie').querySelectorAll('optgroup')].map(g=>g.label)),['Gezin','Familie en schoonfamilie','Stief, pleeg en voogdij','Anderen']);
 for(const code of ['expartner','broer','zus','opa','oma','kleinzoon','kleindochter','oomtante','neefnicht','schoonouder','schoonkind','zwagerschoonzus','buur','huisgenoot'])assert.ok((await options('cm-field-relatie')).includes(code),'vaste relatie '+code);
 assert.deepEqual(await options('cm-field-status'),['','gezag','geengezag'],'ouder en kind: ouderlijk gezag');
 await T.select('#cm-field-naarPersoon',anna);
 assert.equal(await p.evaluate(()=>cmLabel(cm.edit)),'Anna (kind van Rene)');assert.equal(await zin(),'Anna is kind van Rene.');
 await T.select('#cm-field-status','gezag');assert.equal(await zin(),'Anna is kind van Rene (ouder heeft ouderlijk gezag).');
 await save();
 assert.equal(await p.evaluate(()=>cm.records.find(r=>r.type==='relatie').label),'Anna (kind van Rene)');
 assert.deepEqual(await list(),['Anna is kind van Rene (ouder heeft ouderlijk gezag).']);
 await open(anna);assert.deepEqual(await list(),['Anna is kind van Rene (ouder heeft ouderlijk gezag).'],'ook bij de andere persoon');
 await p.locator('#cm-kaart [data-blok=relaties] .strow').first().click();await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!!cm.edit.id&&cm.tab==='relatie');
 assert.equal(await p.inputValue('#cm-field-status'),'gezag');assert.equal(await zin(),'Anna is kind van Rene (ouder heeft ouderlijk gezag).');
 await p.evaluate(()=>goBack());await p.waitForFunction(id=>current==='cmkaart'&&cmc.rec?.id===id&&!cm.navigating,anna);

 // Partner: eigen statussen; bij een vriend geen status
 await open(rene);await add('partner',sam);
 assert.equal(await p.evaluate(()=>cmLabel(cm.edit)),'Sam (partner van Rene)');
 assert.deepEqual(await options('cm-field-status'),['','gehuwd','geregistreerd','samenwonend','latrelatie']);
 await T.select('#cm-field-status','samenwonend');assert.equal(await zin(),'Sam is partner van Rene (samenwonend).');
 await T.select('#cm-field-relatie','vriend');
 assert.equal(await options('cm-field-status'),null,'geen status bij een vriend');assert.equal(await p.evaluate(()=>'status' in cm.edit.fields),false,'status vervalt bij een andere relatie');
 assert.equal(await zin(),'Sam is vriend of vriendin van Rene.');
 await T.select('#cm-field-relatie','partner');assert.equal(await p.inputValue('#cm-field-status'),'');
 await T.select('#cm-field-status','samenwonend');await save();
 // Een zelfgekozen naam (⋯ → Andere naam in het overzicht) blijft staan als de relatie verandert
 const samRel=await p.evaluate(id=>cm.records.find(r=>r.type==='relatie'&&r.naar===id).id,sam);
 await T.blok(samRel,'naam');await p.fill('#cm-label','Mijn lief');await T.opslaan();
 await T.blok(samRel,'relatie');await T.select('#cm-field-relatie','expartner');await T.opslaan();
 assert.equal(await p.evaluate(id=>Android._cm.records.find(r=>r.id===id).label,samRel),'Mijn lief','eigen naam niet overschrijven');
 await T.blok(samRel,'relatie');await T.select('#cm-field-relatie','partner');await T.select('#cm-field-status','samenwonend');await T.opslaan();

 // Andere relatie: zelf kiezen; een status die niet past wordt geweigerd
 await open(rene);await add('',kees);
 assert.equal(await options('cm-field-status'),null);
 await T.select('#cm-field-relatie','anders');assert.equal(await zin(),'Kees heeft een andere relatie met Rene.');assert.equal(await p.evaluate(()=>cmLabel(cm.edit)),'Kees en Rene');
 await T.select('#cm-field-relatie','vriend');await p.evaluate(()=>{cm.edit.fields.status='gehuwd';});await p.click('#cm-save');
 await p.waitForFunction(()=>!cm.mutating&&$('#cm-edit-status').textContent==='Kies een status die bij deze relatie past');assert.equal(await p.evaluate(()=>cm.edit?.type),'relatie','bewerking blijft open');
 await p.evaluate(()=>{delete cm.edit.fields.status;});await save();
 await open(sam);assert.deepEqual(await list(),['Sam is partner van Rene (samenwonend).']);
 await open(rene);assert.deepEqual(await list(),['Anna is kind van Rene (ouder heeft ouderlijk gezag).','Kees is vriend of vriendin van Rene.','Sam is partner van Rene (samenwonend).']);
 // Ouder toevoegen bij het kind: de gekozen persoon is de ouder
 await open(anna);await add('ouder',rene);assert.equal(await zin(),'Rene is ouder van Anna.');assert.deepEqual(await options('cm-field-status'),['','gezag','geengezag']);
 await p.evaluate(()=>{cm.dirty=false;cmCancelEdit();});await p.waitForFunction(id=>current==='cmkaart'&&cmc.rec?.id===id&&!cm.navigating,anna);

 // De status gaat alleen naar de assistent als Status van relaties aan staat (Wat weet de assistent?)
 await T.maak('profiel','Mijn app-profiel',{persoon:rene,delen:'ja',aiStatus:'nee'});await p.evaluate(()=>appIdentityRefresh());await p.waitForFunction(()=>appIdentity.self&&(appIdentity.people||[]).length===3);
 let shared=await p.evaluate(()=>JSON.stringify(appIdentity));assert.ok(shared.includes('partner'),shared);for(const s of ['samenwonend','gezag'])assert.ok(!shared.includes(s),'status '+s+' niet gedeeld');
 await p.evaluate(()=>cmaiOpen());await p.waitForFunction(()=>current==='cmai'&&!$('#cm-ai-aiStatus').disabled);assert.equal(await p.getAttribute('#cm-ai-aiStatus','aria-checked'),'false');
 await p.click('#cm-ai-aiStatus');await p.waitForFunction(()=>JSON.stringify(appIdentity).includes('samenwonend'));
 shared=await p.evaluate(()=>JSON.stringify(appIdentity));assert.ok(shared.includes('gezag'),'met het vinkje wel de status: '+shared);

 // Engels: zinnen, knoppen en keuzes vertaald; Rene is nu "ik" (app-profiel): de zinnen gaan vanuit jou
 await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForFunction(()=>TAAL.on);await open(rene);
 assert.deepEqual(await list(),['Anna is your child (parent has parental responsibility).','Kees is your friend.','Sam is your partner (living together).']);
 assert.equal(await p.textContent('#cmc-rel-add'),'Add person','bij jezelf: persoon toevoegen vanuit jou');
 await open(anna);assert.equal(await p.textContent('#cmc-rel-add'),'Relationship between others…');
 await p.click('#cmc-rel-add');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-acts button:first-child'),'Child of Anna');await p.evaluate(()=>closeSheet());
 await add('partner',kees);assert.equal(await p.evaluate(()=>cmLabel(cm.edit)),'Kees (partner of Anna)');
 assert.equal(await p.evaluate(()=>$('#cm-field-relatie optgroup').label),'Immediate family');
 assert.equal(await p.evaluate(()=>$('#cm-field-status option[value="latrelatie"]').textContent),'Living apart together');
 await T.select('#cm-field-status','gehuwd');assert.equal(await zin(),'Kees is the partner of Anna (married).');

 // Vergrendelen wist ook de relatiezin en het kaartje (alleen vluchtig schermgeheugen)
 assert.equal(await p.evaluate(()=>!$('#cmi-relation-zin').hidden),true);
 await p.evaluate(()=>cmLock());await p.waitForFunction(()=>!cm.token);
 assert.equal(await p.evaluate(()=>$('#cmi-relation-zin').hidden&&!$('#cmi-relation-zin').textContent&&!$('#cm-kaart').children.length),true);
 assert.equal(await p.evaluate(()=>/\b(Anna|Kees|Sam|Rene)\b/.test($('#cm-open').innerHTML+$('#s-cmkaart').innerHTML+$('#s-cmedit').innerHTML)),false,'geen namen meer in de verborgen schermen');
 await p.evaluate(()=>rgSave({lang:'nl'}));
 assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|identity|profiel|relati/.test(k))),false);
 await p.close();
}
console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
