// PDF → Foto's uit PDF: elke foto uit een PDF als losse JPG (een pagina zonder losse foto wordt zelf een foto). Na het
// omzetten staan de foto's op het scherm (de controle), onder Gemaakt en, als dat aan staat, in de documentenkluis.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  await p.evaluate(()=>{store.set('pdfKluisVanzelf',false);openTool('pdf');});await p.waitForFunction(()=>current==='pdf');
  await p.click('#pdf-t-foto');await p.waitForFunction(()=>current==='pdffoto');
  assert.equal(await p.textContent('#s-pdffoto h1'),"Foto's uit PDF");
  assert.equal(await p.isVisible('#pdf-foto'),false,'eerst een PDF kiezen');
  // PDF kiezen (geen andere plek: meteen de kiezer)
  await p.click('#pdf-foto-pick');
  if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');
  await p.waitForFunction(()=>current==='pdffoto'&&!$('#pdf-foto').hidden);
  assert.match(await p.textContent('#pdf-foto-info'),/Contract · 8 pagina/);
  assert.equal(await p.evaluate(()=>$('#pdf-foto-pick').classList.contains('ghost')),true,'daarna is omzetten de hoofdknop');
  await p.click('#pdf-foto-go');
  await p.waitForFunction(()=>!$('#pdf-foto-res').hidden);
  assert.equal(await p.locator('#pdf-foto-grid figure').count(),3);
  assert.deepEqual(await p.$$eval('#pdf-foto-grid figcaption',x=>x.map(e=>e.textContent)),['Foto 1 · pagina 1 · 1.200×800','Foto 2 · pagina 1 · 1.200×800','Pagina 2 · 1.200×800']);
  assert.match(await p.textContent('#pdf-foto-sum'),/3 foto's uit 8 pagina's · waarvan 1 hele pagina/);
  assert.equal(await p.evaluate(()=>[...document.querySelectorAll('#pdf-foto-grid img')].every(i=>i.naturalWidth>0||/^data:image\/jpeg/.test(i.src))),true,'voorbeelden te zien');
  // Onder Gemaakt, met het pictogram van een foto
  assert.match(await p.textContent('.pdfres[data-scr="pdffoto"]'),/Contract foto 1\.jpg/);
  assert.equal(await p.evaluate(()=>Android._pdf.fotosGevraagd),1);
  // Een fout: zichtbaar, en geen oude foto's van een andere PDF
  await p.evaluate(()=>{Android._pdf.fotoFout="Er staan geen foto's in deze PDF";});
  await p.click('#pdf-foto-pick');if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');
  await p.waitForFunction(()=>$('#pdf-foto-res').hidden,null,{timeout:5000});
  await p.click('#pdf-foto-go');await p.waitForFunction(()=>!pdfBusy);
  assert.equal(await p.isVisible('#pdf-foto-res'),false);
  // Vanzelf naar de kluis: map Foto's
  await p.evaluate(()=>{delete Android._pdf.fotoFout;store.set('pdfKluisVanzelf',true);Android._kl.open=true;Android._kl.docs=[];});
  await p.click('#pdf-foto-go');
  await p.waitForFunction(()=>(Android._kl.docs||[]).length>=3,null,{timeout:8000});
  assert.deepEqual(await p.evaluate(()=>[...new Set(Android._kl.docs.map(d=>d.category))]),["Foto's"]);
  await p.close();
}
await b.close();console.log('errors',errors);if(errors.length)process.exit(1);})().catch(e=>{console.error(e);process.exit(1);});
