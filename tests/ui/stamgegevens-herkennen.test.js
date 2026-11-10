// Stamgegevens: ID-gegevens herkennen uit een foto (de testbrug geeft wat MrzOcr zou vinden). Voorstellen aanvinken, niets vanzelf.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const kid='00000000-0000-0000-0000-000000000001';
  await p.evaluate(kid=>{
    Android._cm.records=[{id:kid,type:'persoon',label:'Zoë <b>',revision:1,fields:{naam:'Jansen',voornamen:'Zoë'}}];
    Android._cm.herkend={document:{documentsoort:'identiteitskaart',document:'NX7P41KD9',geldig:'2031-06-11',uitgifteland:'Nederland'},
      persoon:{naam:'Jansen',voornamen:'Zoe Anna',geboortedatum:'2015-02-03',geslacht:'V',nationaliteit:'Nederlandse'},soort:'TD1',zeker:true};
  },kid);
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  // Document toevoegen begint op het kaartje van de persoon: eerst de soort, dan een foto
  const scan=async soort=>{
    await T.kaart(kid);await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');
    await p.getByRole('button',{name:soort,exact:true}).click();await p.waitForFunction(s=>$('#sheet.on')&&$('#sheet-title').textContent===s,soort);
    await p.getByRole('button',{name:'Foto kiezen',exact:true}).click();
  };
  // 1. Foto kiezen: terug uit de kiezer gaat het meteen verder, zonder opnieuw ontgrendelen; een nieuw document met de foto als bijlage, en voorstellen.
  await scan('Identiteitskaart');await p.waitForFunction(()=>current==='cmedit'&&cm.edit&&cm.edit.type==='identiteit'&&cm.edit.id&&cm.tab==='document'&&!$('#cmh-panel').hidden&&document.querySelectorAll('#cmh-list input').length>0);
  const doc=await p.evaluate(()=>cm.edit.id);
  assert.deepEqual(await p.evaluate(()=>Android._cm.scanSoorten),['identiteitskaart'],'de gekozen soort gaat mee');
  assert.equal(await p.isVisible('#cmi-photo-pending'),false,'geen kaart Ontgrendelen');assert.equal(await p.evaluate(()=>cmOpen()),true,'bleef ontgrendeld');
  assert.equal(await p.evaluate(()=>JSON.stringify(Object.keys(Android._cm.records.find(r=>r.id===cm.edit.id).images||{}))),'["back"]','foto als achterzijde bewaard (ID-kaart)');
  assert.deepEqual(await p.evaluate(()=>[cm.edit.fields.persoon,cm.edit.label]),[kid,'Identiteitskaart Zoë <b>'],'document bij de persoon');
  assert.equal(await p.evaluate(()=>cm.edit.fields.document||''),'','gegevens nog niet vanzelf ingevuld');
  const rows=await p.$$eval('#cmh-list input',x=>x.map(i=>i.dataset.key+':'+(i.checked?'v':'-')+(i.disabled?'x':'')));
  assert.deepEqual(rows,['documentsoort:vx','document:v','geldig:v','uitgifteland:v','naam:vx','voornamen:-','geboortedatum:v','geslacht:v','nationaliteit:v'],'standaard: lege velden aangevinkt, gelijke vast');
  assert.match(await p.textContent('#cmh-list'),/nu: Zoë/);assert.match(await p.textContent('#cmh-list'),/staat er al/);
  assert.doesNotMatch(await p.textContent('#cmh-list'),/NX7P41KD9/,'documentnummer afgeschermd');assert.equal(await p.locator('#cmh-list b b').count(),0,'namen blijven tekst');
  // 2. Eén expliciete actie bewaart alleen gekozen document- en persoonsgegevens samen; daarna het documentkaartje.
  assert.equal(await p.isVisible('#cm-fields'),false,'voorstellen staan centraal');assert.equal(await p.textContent('#cm-save'),'Bewaar gekozen gegevens');
  await p.click('#cm-save');await p.waitForFunction(doc=>current==='cmkaart'&&cmc.rec?.id===doc&&!cm.navigating&&!cm.mutating,doc);
  assert.equal(await p.evaluate(kid=>{const f=Android._cm.records.find(r=>r.id===kid).fields;return [f.geboortedatum,f.geslacht,f.voornamen].join('|');},kid),'2015-02-03|V|Zoë','alleen de aangevinkte persoonsgegevens');
  assert.equal(await p.evaluate(()=>cm.dirty),false,'geen tweede opslagstap nodig');
  assert.equal(await p.evaluate(doc=>{const r=Android._cm.records.find(r=>r.id===doc);return r.fields.document==='NX7P41KD9'&&!!r.images.back;},doc),true,'document met nummer en foto');
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='scan')),'start in het logboek');
  await p.waitForFunction(()=>$('#cmc-foto-back').textContent.trim()==='Achterkant'&&!!$('#cmc-foto-back img').getAttribute('src'));assert.match(await p.textContent('#cmc-foto-front'),/Voorkant toevoegen/);
  assert.equal(await p.evaluate(()=>/NX7P41KD9/.test($('#cm-kaart').textContent)),false,'nummer afgeschermd op het kaartje');
  // Een gewone kaartfoto vanaf het kaartje: bewaard, terug op het kaartje
  await p.evaluate(()=>{Android._cm.herkend=null;});await p.click('#cmc-foto-front');
  await p.waitForFunction(doc=>current==='cmkaart'&&cmc.rec?.id===doc&&!!Android._cm.records.find(r=>r.id===doc).images.front&&$('#cm-kaart-status').textContent==='Kaartfoto opgeslagen.'&&!cm.busy,doc);
  await p.waitForFunction(()=>$('#cmc-foto-front').textContent.trim()==='Voorkant'&&!!$('#cmc-foto-front img').getAttribute('src'));
  // 3. Vanaf de bewaarde foto opnieuw herkennen, zonder de app te verlaten; daarna de foto verwijderen.
  await p.evaluate(()=>{Android._cm.herkend={document:{documentsoort:'identiteitskaart',document:'NX7P41KD9',geldig:'2031-06-11',uitgifteland:'Nederland'},persoon:{naam:'Jansen',voornamen:'Zoe Anna',geboortedatum:'2015-02-03',geslacht:'V',nationaliteit:'Nederlandse'},soort:'TD1',zeker:true};});
  await T.blok(doc,'fotos');assert.equal(await p.isDisabled('#cmh-photo-scan'),false);
  // Herkennen duurt even: de balk onderin zegt wat er gebeurt en hoe lang het hooguit duurt
  await p.evaluate(()=>Android._cm.replyDelay=900);await p.click('#cmh-photo-scan');await p.waitForFunction(()=>!$('#vg').hidden);
  assert.equal(await p.textContent('#vg-wat'),'Kaartfoto verwerken');assert.equal(await p.textContent('#vg-stand'),'Gegevens herkennen… nog hooguit 30 s');
  await p.waitForFunction(()=>!$('#cmh-panel').hidden);assert.equal(await p.isVisible('#vg'),false,'balk weg na herkennen');await p.evaluate(()=>Android._cm.replyDelay=10);
  assert.equal(await p.evaluate(()=>cmOpen()),true,'blijft ontgrendeld');
  assert.match(await p.textContent('#cmh-list'),/staat er al/,'nummer staat er al');
  await p.evaluate(()=>{cmhDismiss();cmTab('fotos');});
  for(const side of ['back','front']){await p.click('#cmi-'+side+'-delete');await p.waitForFunction(()=>$('#modal').classList.contains('on'));await p.click('#modal-ok');await p.waitForFunction(([doc,side])=>!Android._cm.records.find(r=>r.id===doc).images?.[side]&&!cm.mutating,[doc,side]);}
  assert.equal(await p.isDisabled('#cmh-photo-scan'),true,'na verwijderen niets meer te herkennen');
  // Geen strook gevonden, en te lang weg uit de app (kluis vergrendeld): na ontgrendelen wel een document met de foto, en hulp.
  await p.evaluate(()=>{Android._cm.herkend=null;Android._cm.slowPick=true;cm.dirty=false;cmCancelEdit();});await p.waitForFunction(()=>current==='cmkaart'&&!cm.navigating);
  await scan('Paspoort');await p.waitForFunction(()=>!$('#cmi-photo-pending').hidden&&current==='masterdata');
  assert.equal(await p.textContent('#cmi-photo-pending-btn'),'Ontgrendelen en gegevens herkennen');await p.click('#cmi-photo-pending-btn');
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit&&cm.edit.type==='identiteit'&&cm.tab==='fotos'&&/geen gegevens herkend/.test($('#cm-edit-status').textContent));
  assert.equal(await p.isVisible('#cmh-hulp'),true,'wat je kunt proberen');
  assert.equal(await p.evaluate(()=>Android._cm.records.filter(r=>r.type==='identiteit').length),2);
  assert.equal(await p.evaluate(()=>{const r=Android._cm.records.filter(r=>r.type==='identiteit').pop();return r.label+'|'+(r.fields.documentsoort||'')+'|'+Object.keys(r.images);}),'Paspoort Zoë <b>|paspoort|front','niets herkend: de gekozen soort, foto als voorzijde');
  // Daarna alsnog herkend vanaf de foto: het nummer gaat mee, de naam blijft.
  await p.evaluate(()=>{Android._cm.herkend={document:{documentsoort:'paspoort',document:'NW3RP8K25'},persoon:{},soort:'TD3',zeker:true};});
  await p.click('#cmh-photo-scan');await p.waitForFunction(()=>!$('#cmh-panel').hidden&&cm.tab==='document');
  const emptyDoc=await p.evaluate(()=>cm.edit.id);await p.click('#cm-save');
  await p.waitForFunction(id=>{const r=Android._cm.records.find(r=>r.id===id);return r.fields.document==='NW3RP8K25'&&r.label==='Paspoort Zoë <b>'&&current==='cmkaart'&&cmc.rec?.id===id;},emptyDoc);
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='photo_scan')),'herkennen vanaf foto in het logboek');
  // Tijdens het verwerken op slot gegaan: de foto vervalt met een melding, en de fotoknoppen blijven werken.
  const doc2=await p.evaluate(()=>Android._cm.records.filter(r=>r.type==='identiteit').pop().id);
  const openDoc2=async()=>{await p.evaluate(()=>{cm.dirty=false;cmCancelEdit();});await p.waitForFunction(()=>cm.recordsReady&&cmOpen()&&!cm.mutating);await p.evaluate(id=>cmEdit(id,'fotos'),doc2);await p.waitForFunction(id=>cm.edit&&cm.edit.id===id&&cm.tab==='fotos',doc2);};
  await openDoc2();await p.evaluate(()=>{Android._cm.slowPick=false;Android._cm.lateLock=true;});
  await p.evaluate(()=>cmiPhotoPick('back'));await p.waitForFunction(()=>!cmOpen());
  await p.waitForFunction(()=>!cmi.photoRequest);await p.evaluate(()=>Android._cm.lateLock=false);
  await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);assert.equal(await p.isVisible('#cmi-photo-pending'),false,'geen hangende fotokeuze');
  await openDoc2();await p.evaluate(()=>cmiPhotoPick('back'));await p.waitForFunction(()=>cm.edit?.images?.back&&!$('#cmh-panel').hidden);
  // 4. Vergrendelen wist voorstellen.
  await p.evaluate(()=>{Android._cm.herkend={document:{document:'X1'},persoon:{},zeker:true};});
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
  assert.equal(await p.evaluate(()=>cmh.data===null&&$('#cmh-list').children.length===0),true);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
