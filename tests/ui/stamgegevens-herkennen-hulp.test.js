// Stamgegevens: als herkennen (rijbewijs) niet of niet helemaal lukt, zegt de app wat er gebeurde, met tips die bij de
// foto passen en knoppen om opnieuw, grondiger of met een andere foto te proberen, of zelf in te vullen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  await p.evaluate(()=>{
    Android._cm.records=[{id:'owner',type:'persoon',label:'Rene',revision:1,fields:{naam:'van Es',voornamen:'Rene'}}];
    Android._cm.herkend=null;Android._cm.diag={regels:14,rijbewijs:true,onderregel:false,onscherp:true};
    show('masterdata');
  });
  await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  // 1. Rijbewijs toevoegen met een foto, niets gevonden: naar Foto's, met uitleg en tips (onscherp, de regel onderaan, grondiger proberen).
  await T.kaart('owner');await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');
  await p.getByRole('button',{name:'Rijbewijs',exact:true}).click();await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Rijbewijs');
  assert.match(await p.textContent('#sheet-sub'),/voorkant/,'zegt welke kant');
  await p.getByRole('button',{name:'Foto kiezen',exact:true}).click();
  await p.waitForFunction(()=>cm.edit?.type==='identiteit'&&cm.tab==='fotos'&&!$('#cmh-hulp').hidden);
  assert.match(await p.textContent('#cmh-hulp-tekst'),/rijbewijs is herkend/);
  const tips=await p.textContent('#cmh-hulp-tips');
  assert.match(tips,/onscherp/);assert.match(tips,/D1NLD/);assert.match(tips,/Grondiger proberen/);assert.doesNotMatch(tips,/donker/,'alleen tips die bij de foto passen');
  assert.match(await p.textContent('#cm-edit-status'),/geen gegevens herkend/);
  for(const id of ['#cmh-hulp-opnieuw','#cmh-hulp-grondig','#cmh-hulp-foto'])assert.equal(await p.isVisible(id),true,id);
  // Knoppen passen op het scherm (geen horizontaal scrollen).
  assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'past in de breedte');
  // 2. Grondiger proberen: vindt nummer en geboortedatum, niet de einddatum; dat staat er dan bij.
  await p.evaluate(()=>{Android._cm.herkendGrondig={soort:'rijbewijs',bron:'tekst',zeker:false,document:{documentsoort:'rijbewijs',document:'5094962111'},persoon:{geboortedatum:'1981-02-17'},twijfel:['geboortedatum']};});
  await p.click('#cmh-hulp-grondig');
  await p.waitForFunction(()=>!$('#cmh-panel').hidden&&cm.tab==='document');
  assert.equal(await p.evaluate(()=>JSON.stringify(Android._cm.scans.at(-1))),'["front",true]','grondig gevraagd');
  assert.equal(await p.isVisible('#cmh-hulp'),false,'hulp weg als er iets gevonden is');
  const mist=await p.textContent('#cmh-mist');assert.match(mist,/geldig tot/);assert.match(mist,/afgiftedatum/);assert.doesNotMatch(mist,/documentnummer/);
  assert.equal(await p.isVisible('#cmh-mist-acts button >> nth=0'),false,'na grondig zoeken geen tweede keer grondig aangeboden');
  assert.equal(await p.isVisible('#cmh-mist-acts button >> nth=1'),true,'wel een andere foto kiezen');
  // 3. Opnieuw, zonder tekst op de foto: andere uitleg; Zelf invullen gaat naar het documentnummer.
  await p.evaluate(()=>{cmhDismiss();Android._cm.herkend=null;Android._cm.herkendGrondig=undefined;Android._cm.diag={regels:0,donker:true};cmTab('fotos');});
  await p.click('#cmh-photo-scan');await p.waitForFunction(()=>!$('#cmh-hulp').hidden);
  assert.match(await p.textContent('#cmh-hulp-tekst'),/geen leesbare tekst/);assert.match(await p.textContent('#cmh-hulp-tips'),/donker/);
  await p.click('#cmh-hulp >> text=Zelf invullen');await p.waitForFunction(()=>cm.tab==='document');
  await p.waitForFunction(()=>document.activeElement?.id==='cm-field-document');
  assert.equal(await p.isVisible('#cmh-hulp'),false);
  // 4. Een gewone kaartfoto zonder strook (geen rijbewijs) geeft geen foutmelding.
  const doc=await p.evaluate(()=>cm.edit.id);
  await p.evaluate(()=>{Android._cm.diag={regels:5,rijbewijs:false};cmTab('fotos');cmiPhotoPick('back');});
  await p.waitForFunction(doc=>current==='cmkaart'&&cmc.rec?.id===doc&&/Kaartfoto opgeslagen/.test($('#cm-kaart-status').textContent),doc);
  assert.equal(await p.evaluate(()=>cmh.hulp),null,'geen hulp bij een gewone kaartfoto');
  // 5. Vergrendelen wist de hulp.
  await T.blok(doc,'fotos');await p.evaluate(()=>{cmh.hulp={doc:cm.edit.id,diag:{}};cmhHulpRender();});assert.equal(await p.isVisible('#cmh-hulp'),true);
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cmOpen());
  assert.equal(await p.evaluate(()=>cmh.hulp===null&&$('#cmh-hulp').hidden),true);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
