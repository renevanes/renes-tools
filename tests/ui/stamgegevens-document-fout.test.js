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
  // 3. Pas na opnieuw ontgrendelen verwerkt (lang weg uit de app): ook dan het venster.
  await p.evaluate(()=>{Android._cm.scanError='Dit document kan niet worden geopend. Kies een leesbare PDF of een Word-document (.docx).';Android._cm.slowPick=true;cm.dirty=false;cmCancelEdit();});
  await p.waitForFunction(()=>!cm.navigating&&cmOpen());
  await scan('Paspoort');await p.waitForFunction(()=>!$('#cmi-photo-pending').hidden&&tsZichtbaar());await p.click('#ts-open');
  await p.waitForFunction(()=>$('#modal').classList.contains('on')&&$('#modal-title').textContent==='Document niet toegevoegd');
  assert.match(await p.textContent('#modal-text'),/kan niet worden geopend/);
  assert.equal(await p.evaluate(()=>document.activeElement?.id),'modal-ok','focus op OK');
  await p.click('#modal-ok');await p.waitForFunction(()=>!$('#modal').classList.contains('on'));
  assert.equal(await p.evaluate(()=>Android._cm.records.filter(r=>r.type==='identiteit').length),1,'alleen het eerste document');
  // 4. Scherm uit tijdens het herkennen: het gaat door en na ontgrendelen staat het document er (geen fout, niet opnieuw kiezen).
  await p.evaluate(()=>{Android._cm.scanError=null;Android._cm.slowPick=false;Android._cm.lateLock=true;});
  await p.waitForFunction(()=>!cm.navigating&&!cm.unlocking);await p.waitForFunction(()=>cmOpen());
  await scan('Identiteitskaart');await p.waitForFunction(()=>!cmOpen());
  await p.waitForFunction(()=>!$('#cmi-photo-pending').hidden&&/Het bestand wordt nog verwerkt/.test($('#cmi-photo-pending-text').textContent));
  await p.evaluate(()=>{Android._cm.lateLock=false;});
  await p.waitForFunction(()=>tsZichtbaar());await p.click('#ts-open');
  await p.waitForFunction(()=>current==='cmedit'&&cm.edit&&cm.edit.type==='identiteit'&&cm.edit.id&&!$('#cmh-panel').hidden);
  assert.equal(await p.evaluate(()=>Android._cm.records.filter(r=>r.type==='identiteit').length),2,'na ontgrendelen bewaard');
  assert.equal(await p.evaluate(()=>$('#modal').classList.contains('on')),false,'geen foutmelding');
  // 5. Meer foto's, één gekozen, maar niets herkend: toch bewaard, zonder gegevens.
  await p.evaluate(()=>{Android._cm.herkend=null;Android._cm.scanFotos=[{pagina:1},{pagina:2}];cm.dirty=false;cmCancelEdit();});
  await p.waitForFunction(()=>!cm.navigating&&!cm.mutating&&cmOpen());
  await scan('Identiteitskaart');
  await p.waitForSelector('#cmh-keuze .cmh-foto');
  assert.equal(await p.locator('#cmh-keuze-heel').count(),0,'geen hele pagina: geen waarschuwing');
  await p.locator('#cmh-keuze .cmh-foto').nth(1).click();await p.click('#modal-ok');
  await p.waitForFunction(()=>Android._cm.records.filter(r=>r.type==='identiteit').length===3);
  assert.deepEqual(await p.evaluate(()=>Android._cm.gekozen),[1]);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
