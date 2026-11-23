// Stamgegevens: een document dat niet herkend of niet geopend wordt, geeft een zichtbare melding (niet alleen de statusregel),
// en daarna kun je meteen een ander bestand kiezen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const kid='00000000-0000-0000-0000-000000000001';
  const fout='Geen ID-bewijs herkend in dit document. Kies een document met een scherpe foto van het ID-bewijs, of een foto van de kaart zelf.';
  await p.evaluate(([kid,fout])=>{
    Android._cm.records=[{id:kid,type:'persoon',label:'Zoë <b>',revision:1,fields:{naam:'Jansen',voornamen:'Zoë'}}];
    Android._cm.herkend={document:{documentsoort:'identiteitskaart',document:'NX7P41KD9',geldig:'2031-06-11',uitgifteland:'Nederland'},
      persoon:{naam:'Jansen',voornamen:'Zoe Anna',geboortedatum:'2015-02-03',geslacht:'V',nationaliteit:'Nederlandse'},soort:'TD1',zeker:true};
    Android._cm.scanError=fout;
  },[kid,fout]);
  await p.evaluate(()=>show('masterdata'));await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  const scan=async soort=>{
    await T.kaart(kid);await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');
    await p.getByRole('button',{name:soort,exact:true}).click();await p.waitForFunction(s=>$('#sheet.on')&&$('#sheet-title').textContent===s,soort);
    await p.getByRole('button',{name:'Foto of document kiezen',exact:true}).click();
  };
  // 1. Niet herkend: een venster met alleen OK, de tekst van de app, en niets bewaard.
  await scan('Identiteitskaart');
  await p.waitForFunction(()=>$('#modal').classList.contains('on')&&$('#modal-title').textContent==='Document niet toegevoegd');
  assert.equal(await p.textContent('#modal-text'),fout);
  assert.equal(await p.evaluate(()=>getComputedStyle($('#modal-cancel')).display),'none','alleen OK');
  assert.equal(await p.evaluate(()=>Android._cm.records.filter(r=>r.type==='identiteit').length),0,'niets bewaard');
  await p.click('#modal-ok');await p.waitForFunction(()=>!$('#modal').classList.contains('on'));
  assert.deepEqual(await p.evaluate(()=>[cmOpen(),cm.mutating,!!cmi.photoRequest,cmh.photoBusy,!!cmh.scanning]),[true,false,false,false,false],'niet blijven hangen');
  // 2. Daarna meteen opnieuw kiezen: nu wel herkend.
  await p.evaluate(()=>{Android._cm.scanError=null;});
  await scan('Identiteitskaart');
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit&&cm.edit.type==='identiteit'&&cm.edit.id&&!$('#cmh-panel').hidden);
  assert.equal(await p.evaluate(()=>$('#modal').classList.contains('on')),false,'geen melding als het lukt');
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
