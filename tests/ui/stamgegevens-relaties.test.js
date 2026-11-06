// Stamgegevens → een opgeslagen persoon: relaties zien en toevoegen (kind, ouder, partner, andere), vaste relaties en status.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');const fs=require('fs');
// Vaste relaties en de relaties met ouderlijk gezag: interface en MasterIdentity.java hetzelfde, elke relatie in precies één groep
{const js=fs.readFileSync(path.join(__dirname,'../../web/src/script/71-stamgegevens-profiel.js'),'utf8'),java=fs.readFileSync(path.join(__dirname,'../../app/src/nl/rene/tools/MasterIdentity.java'),'utf8');
 const codes=re=>[...java.match(re)[0].matchAll(/"(\w+)"/g)].map(m=>m[1]);
 const names=Object.keys(Function('return '+js.match(/const CMI_RELATIONS=(\{[^;]*\});/)[1])()),groups=Function('return '+js.match(/const CMI_RELATION_GROUPS=(\[[^;]*\]);/)[1])().flatMap(g=>g[1]);
 assert.deepEqual([...names].sort(),codes(/RELATIONS=[^;]*;/).sort(),'CMI_RELATIONS = MasterIdentity.RELATIONS');assert.deepEqual([...groups].sort(),[...names].sort(),'elke relatie in precies één groep');
 assert.deepEqual(Function('return '+js.match(/const CMI_OUDER_KIND=(\[[^;]*\]);/)[1])(),codes(/OUDER_KIND=[^;]*;/),'CMI_OUDER_KIND = MasterIdentity.OUDER_KIND');}
(async()=>{const browser=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));await p.goto('file://'+path.join(__dirname,'ui/index.html'));
 await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 const person=async(label,naam)=>{await p.evaluate(()=>cmNew());await p.fill('#cm-label',label);await p.fill('#cm-field-naam',naam);await p.click('#cm-save');await p.waitForFunction(label=>!cm.mutating&&!cm.edit&&cm.recordsReady&&cm.records.some(r=>r.label===label),label);return p.evaluate(label=>cm.records.find(r=>r.label===label).id,label);};
 const open=async id=>{await p.evaluate(id=>cmEdit(id),id);await p.waitForFunction(id=>cm.edit?.id===id&&cm.recordsReady,id);};
 const list=()=>p.evaluate(()=>[...$('#cmi-person-relation-list').children].map(e=>e.textContent));
 const note=()=>p.evaluate(()=>$('#cm-status').textContent);
 const zin=()=>p.evaluate(()=>$('#cmi-relation-zin').hidden?'':$('#cmi-relation-zin').textContent);
 const options=id=>p.evaluate(id=>$('#'+id)?[...$('#'+id).options].map(o=>o.value):null,id);
 const add=async(code,naam)=>{await p.click(`button[onclick="cmiRelationNew('${code}')"]`);await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!cm.edit.id);if(naam)await p.selectOption('#cm-field-naarPersoon',naam);};
 const save=async()=>{const n=await p.evaluate(()=>cm.records.length);await p.click('#cm-save');await p.waitForFunction(n=>!cm.mutating&&!cm.edit&&cm.recordsReady&&cm.records.length>n,n);};

 // Eén persoon: de knoppen staan er, maar er is nog niemand om aan te koppelen
 const rene=await person('Rene','van Es');await open(rene);
 assert.equal(await p.isVisible('#cmi-person-relations'),true,'relaties bij een opgeslagen persoon');
 assert.deepEqual(await list(),['Nog geen relaties.']);
 for(const name of ['Kind toevoegen','Ouder toevoegen','Partner toevoegen','Andere relatie toevoegen'])assert.equal(await p.getByRole('button',{name,exact:true}).isVisible(),true,name);
 await p.click(`button[onclick="cmiRelationNew('kind')"]`);assert.equal(await note(),'Voeg eerst de andere persoon toe als stamgegeven (soort Persoon).');assert.equal(await p.evaluate(()=>cm.edit.type),'persoon');
 await p.evaluate(()=>cmCancelEdit());
 const anna=await person('Anna','van Es'),sam=await person('Sam','de Vries'),kees=await person('Kees','Jansen');
 await p.evaluate(()=>cmNew());assert.equal(await p.isVisible('#cmi-person-relations'),false,'niet bij een nieuwe, nog niet opgeslagen persoon');await p.evaluate(()=>cmCancelEdit());
 await open(rene);await p.fill('#cm-field-roepnaam','Rene');await p.click(`button[onclick="cmiRelationNew('kind')"]`);assert.equal(await note(),'Sla je wijzigingen eerst op.');assert.equal(await p.evaluate(()=>cm.edit.type),'persoon');

 // Kind toevoegen: de eerste persoon staat vast, je kiest het kind; naam in het overzicht en zin vullen zich in
 await open(rene);await add('kind');
 assert.deepEqual(await p.evaluate(()=>({...cm.edit.fields})),{vanPersoon:rene,naarPersoon:'',relatie:'kind',delen:'ja'});
 assert.equal(await p.evaluate(()=>document.activeElement.id),'cm-field-naarPersoon','meteen de tweede persoon kiezen');
 assert.equal(await p.inputValue('#cm-label'),'');assert.equal(await zin(),'');
 assert.deepEqual(await p.evaluate(()=>[...$('#cm-field-relatie').querySelectorAll('optgroup')].map(g=>g.label)),['Gezin','Familie en schoonfamilie','Stief, pleeg en voogdij','Anderen']);
 for(const code of ['expartner','broer','zus','opa','oma','kleinzoon','kleindochter','oomtante','neefnicht','schoonouder','schoonkind','zwagerschoonzus','buur','huisgenoot'])assert.ok((await options('cm-field-relatie')).includes(code),'vaste relatie '+code);
 assert.deepEqual(await options('cm-field-status'),['','gezag','geengezag'],'ouder en kind: ouderlijk gezag');
 await p.selectOption('#cm-field-naarPersoon',anna);
 assert.equal(await p.inputValue('#cm-label'),'Anna (kind van Rene)');assert.equal(await zin(),'Anna is kind van Rene.');
 await p.selectOption('#cm-field-status','gezag');assert.equal(await zin(),'Anna is kind van Rene (ouder heeft ouderlijk gezag).');
 await save();
 assert.equal(await p.evaluate(()=>cm.records.filter(r=>r.type==='relatie').every(r=>!('status' in r)&&!('toelichting' in r))),true,'overzicht zonder status of toelichting');
 await open(anna);assert.deepEqual(await list(),['Anna is kind van Rene.'],'ook bij de andere persoon');
 await open(rene);assert.deepEqual(await list(),['Anna is kind van Rene.']);
 await p.locator('#cmi-person-relation-list button').first().click();await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!!cm.edit.id);
 assert.equal(await p.inputValue('#cm-field-status'),'gezag');assert.equal(await zin(),'Anna is kind van Rene (ouder heeft ouderlijk gezag).');

 // Partner: eigen statussen; bij een vriend geen status; een zelfgekozen naam blijft staan
 await open(rene);await add('partner',sam);
 assert.equal(await p.inputValue('#cm-label'),'Sam (partner van Rene)');
 assert.deepEqual(await options('cm-field-status'),['','gehuwd','geregistreerd','samenwonend','latrelatie']);
 await p.selectOption('#cm-field-status','samenwonend');assert.equal(await zin(),'Sam is partner van Rene (samenwonend).');
 await p.selectOption('#cm-field-relatie','vriend');
 assert.equal(await options('cm-field-status'),null,'geen status bij een vriend');assert.equal(await p.evaluate(()=>'status' in cm.edit.fields),false,'status vervalt bij een andere relatie');
 assert.equal(await p.inputValue('#cm-label'),'Sam (vriend of vriendin van Rene)');assert.equal(await zin(),'Sam is vriend of vriendin van Rene.');
 await p.selectOption('#cm-field-relatie','partner');assert.equal(await p.inputValue('#cm-field-status'),'');
 await p.fill('#cm-label','Mijn lief');await p.selectOption('#cm-field-relatie','expartner');assert.equal(await p.inputValue('#cm-label'),'Mijn lief','eigen naam niet overschrijven');
 await p.selectOption('#cm-field-relatie','partner');await p.selectOption('#cm-field-status','samenwonend');await save();

 // Andere relatie: zelf kiezen; een status die niet past wordt geweigerd
 await open(rene);await add('',kees);
 assert.equal(await options('cm-field-status'),null);
 await p.selectOption('#cm-field-relatie','anders');assert.equal(await zin(),'Kees heeft een andere relatie met Rene.');assert.equal(await p.inputValue('#cm-label'),'Kees en Rene');
 await p.selectOption('#cm-field-relatie','vriend');await p.evaluate(()=>{cm.edit.fields.status='gehuwd';});await p.click('#cm-save');
 await p.waitForFunction(()=>!cm.mutating&&$('#cm-status').textContent==='Kies een status die bij deze relatie past');assert.equal(await p.evaluate(()=>cm.edit?.type),'relatie','bewerking blijft open');
 await p.evaluate(()=>{delete cm.edit.fields.status;});await save();
 await open(sam);assert.deepEqual(await list(),['Sam is partner van Rene.']);
 await open(rene);assert.deepEqual(await list(),['Anna is kind van Rene.','Kees is vriend of vriendin van Rene.','Sam is partner van Rene.']);
 // Ouder toevoegen bij het kind: de gekozen persoon is de ouder
 await open(anna);await add('ouder',rene);assert.equal(await zin(),'Rene is ouder van Anna.');assert.deepEqual(await options('cm-field-status'),['','gezag','geengezag']);

 // De status blijft in de kluis: niet in het gedeelde app-profiel
 await p.evaluate(()=>cmiProfile());await p.waitForFunction(()=>cm.edit?.type==='profiel');await p.selectOption('#cm-field-persoon',rene);await p.selectOption('#cm-field-delen','ja');await p.click('#cm-save');await p.waitForFunction(()=>appIdentity.self&&(appIdentity.people||[]).length===3);
 const shared=await p.evaluate(()=>JSON.stringify(appIdentity));assert.ok(shared.includes('partner'),shared);for(const s of ['samenwonend','gezag'])assert.ok(!shared.includes(s),'status '+s+' niet gedeeld');

 // Engels: zinnen, knoppen en keuzes vertaald
 await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForFunction(()=>TAAL.on);await open(rene);
 assert.deepEqual(await list(),['Anna is the child of Rene.','Kees is the friend of Rene.','Sam is the partner of Rene.']);
 await p.waitForFunction(()=>$('#cmi-person-relations h3').textContent==='Relationships');assert.equal(await p.getByRole('button',{name:'Add child',exact:true}).isVisible(),true);
 await add('partner',sam);assert.equal(await p.inputValue('#cm-label'),'Sam (partner of Rene)');
 assert.equal(await p.evaluate(()=>$('#cm-field-relatie optgroup').label),'Immediate family');
 assert.equal(await p.evaluate(()=>$('#cm-field-status option[value="latrelatie"]').textContent),'Living apart together');
 await p.selectOption('#cm-field-status','gehuwd');assert.equal(await zin(),'Sam is the partner of Rene (married).');

 // Vergrendelen wist ook de relatiezin en de relatielijst (alleen vluchtig schermgeheugen)
 assert.equal(await p.evaluate(()=>!$('#cmi-relation-zin').hidden&&$('#cmi-person-relation-list').children.length>0&&!!cmi.labelFor),true);
 await p.evaluate(()=>cmLock());await p.waitForFunction(()=>!cm.token);
 assert.equal(await p.evaluate(()=>cmi.labelFor===null&&!cmi.autoLabel&&$('#cmi-relation-zin').hidden&&!$('#cmi-relation-zin').textContent&&!$('#cmi-person-relation-list').children.length),true);
 assert.equal(await p.evaluate(()=>/\b(Anna|Kees|Sam|Rene)\b/.test($('#cm-open').innerHTML)),false,'geen namen meer in het verborgen scherm');
 await p.evaluate(()=>rgSave({lang:'nl'}));
 assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|identity|profiel|relati/.test(k))),false);
 await p.close();
}
console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
