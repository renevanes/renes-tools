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
  await p.locator('#cm-list .strow',{hasText:'Zoë <b>'}).click();await p.waitForFunction(()=>cm.edit&&cm.edit.label==='Zoë <b>');
  await p.click('#cm-tab-documenten');assert.equal(await p.isVisible('#cmh-scan'),true,'knop Herkennen uit foto');
  // 1. Foto kiezen verlaat de app (vergrendelt); daarna ontgrendelen en herkennen.
  await p.click('#cmh-scan');await p.waitForFunction(()=>!$('#cmi-photo-pending').hidden);
  assert.match(await p.textContent('#cmi-photo-pending'),/gegevens te herkennen.*niet bewaard/);
  await p.click('#cmi-photo-pending-btn');await p.waitForFunction(()=>cm.edit&&cm.edit.type==='identiteit'&&!cm.edit.id&&!$('#cmh-panel').hidden&&document.querySelectorAll('#cmh-list input').length>0);
  assert.equal(await p.inputValue('#cm-field-persoon'),kid,'nieuw document bij de persoon');
  assert.equal(await p.evaluate(()=>cm.edit.fields.document||''),'','nog niets vanzelf ingevuld');
  const rows=await p.$$eval('#cmh-list input',x=>x.map(i=>i.dataset.key+':'+(i.checked?'v':'-')+(i.disabled?'x':'')));
  assert.deepEqual(rows,['documentsoort:v','document:v','geldig:v','uitgifteland:v','naam:vx','voornamen:-','geboortedatum:v','geslacht:v','nationaliteit:v'],'standaard: lege velden aangevinkt, gelijke vast');
  assert.match(await p.textContent('#cmh-list'),/nu: Zoë/);assert.match(await p.textContent('#cmh-list'),/staat er al/);
  assert.doesNotMatch(await p.textContent('#cmh-list'),/NX7P41KD9/,'documentnummer afgeschermd');assert.equal(await p.locator('#cmh-list b b').count(),0,'namen blijven tekst');
  // 2. Overnemen vult het document; opslaan, daarna de persoon met de gekozen gegevens.
  await p.getByRole('button',{name:'Gekozen gegevens overnemen',exact:true}).click();
  assert.equal(await p.evaluate(()=>cm.edit.fields.document),'NX7P41KD9');assert.equal(await p.evaluate(()=>cm.edit.fields.geldig),'2031-06-11');
  assert.equal(await p.inputValue('#cm-label'),'Identiteitskaart Zoë <b>');assert.equal(await p.isVisible('#cmh-panel'),false);
  await p.click('#cm-save');await p.waitForFunction(()=>cm.edit&&cm.edit.id==='00000000-0000-0000-0000-000000000001'&&cm.dirty);
  assert.equal(await p.evaluate(()=>[cm.edit.fields.geboortedatum,cm.edit.fields.geslacht,cm.edit.fields.voornamen].join('|')),'2015-02-03|V|Zoë','alleen de aangevinkte persoonsgegevens');
  assert.equal(await p.evaluate(()=>cm.tab),'geboorte','persoon opent waar de gegevens staan');
  assert.match(await p.textContent('#cm-edit-status'),/uit de foto ingevuld/);
  await p.click('#cm-save');await p.waitForFunction(()=>Android._cm.records.find(r=>r.id==='00000000-0000-0000-0000-000000000001').fields.geboortedatum==='2015-02-03');
  assert.equal(await p.evaluate(()=>Android._cm.records.some(r=>r.type==='identiteit'&&r.fields.document==='NX7P41KD9'&&r.fields.persoon==='00000000-0000-0000-0000-000000000001')),true);
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='scan')),'start in het logboek');
  // 3. Geen strook gevonden: duidelijke melding, geen document.
  await p.evaluate(()=>{Android._cm.herkend=null;});await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  await p.locator('#cm-list .strow',{hasText:'Zoë <b>'}).click();await p.waitForFunction(()=>cm.edit&&cm.edit.label==='Zoë <b>');await p.click('#cm-tab-documenten');
  await p.click('#cmh-scan');await p.waitForFunction(()=>!$('#cmi-photo-pending').hidden);await p.click('#cmi-photo-pending-btn');
  await p.waitForFunction(()=>/Geen strook/.test($('#cm-status').textContent+$('#cm-edit-status').textContent));
  assert.equal(await p.evaluate(()=>Android._cm.records.filter(r=>r.type==='identiteit').length),1);
  // 4. Vergrendelen wist voorstellen.
  await p.evaluate(()=>{Android._cm.herkend={document:{document:'X1'},persoon:{},zeker:true};});
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
  assert.equal(await p.evaluate(()=>cmh.data===null&&$('#cmh-list').children.length===0),true);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
