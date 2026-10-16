const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));await p.evaluate(()=>show('pdf'));
    assert.equal(await p.isDisabled('#pdf-signature-use'),true);
    await p.click('#pdf-edit-pick');await p.waitForFunction(()=>pdfEd.meta[0]&&!pdfEd.loading);
    const draw=async(offset=0)=>{
      await p.getByRole('button',{name:'Handtekening tekenen',exact:true}).click();
      const r=await p.locator('#pdf-edit-sign').boundingBox();
      await p.mouse.move(r.x+15,r.y+30+offset);await p.mouse.down();await p.mouse.move(r.x+70,r.y+60,{steps:5});await p.mouse.move(r.x+130,r.y+15+offset,{steps:5});await p.mouse.up();
    };
    await p.getByRole('button',{name:'Handtekening tekenen',exact:true}).click();await p.click('#pdf-signature-save');
    assert.equal(await p.evaluate(()=>Android._signature||null),null,'lege tekening wordt niet bewaard');
    await draw();await p.click('#pdf-signature-save');
    const signature=await p.evaluate(()=>Android._signature);assert.ok(signature.paths.length);assert.ok(signature.ratio>0);
    assert.equal(await p.isVisible('#pdf-signature-preview'),true);assert.equal(await p.isDisabled('#pdf-signature-use'),false);
    await p.click('#pdf-signature-use');assert.equal(await p.evaluate(()=>pdfEd.items.length),1);
    const placed=await p.evaluate(()=>pdfEd.items[0]);assert.equal(placed.type,'ink');assert.deepEqual(placed.paths,signature.paths);
    // De bewaarde data blijft onafhankelijk van de toevoeging en wordt opnieuw uit de native opslag geladen.
    await p.evaluate(()=>{pdfEd.items[0].paths[0][0][0]=.99;pdfSig.value=null;pdfSignatureLoad();});
    assert.deepEqual(await p.evaluate(()=>pdfSig.value.paths),signature.paths);
    await p.click('#pdf-edit-pick');await p.click('#modal-ok');await p.waitForFunction(()=>!pdfEd.loading&&pdfEd.items.length===0);
    assert.deepEqual(await p.evaluate(()=>pdfSig.value),signature);await p.click('#pdf-signature-use');
    // Vervangen kan worden geannuleerd; een opslagfout behoudt de bestaande handtekening en de tekening.
    await draw(10);await p.click('#pdf-signature-save');await p.click('#modal-cancel');assert.deepEqual(await p.evaluate(()=>Android._signature),signature);
    await p.evaluate(()=>Android._signatureError='Geen opslagruimte');await p.click('#pdf-signature-save');await p.click('#modal-ok');
    assert.deepEqual(await p.evaluate(()=>Android._signature),signature);assert.deepEqual(await p.evaluate(()=>pdfSig.value),signature);assert.ok(await p.evaluate(()=>pdfEd.strokes.length));
    await p.evaluate(()=>Android._signatureError='');await p.click('#pdf-signature-save');await p.click('#modal-ok');
    const replacement=await p.evaluate(()=>Android._signature);assert.notDeepEqual(replacement.paths,signature.paths);
    await p.getByRole('button',{name:'Handtekening plaatsen',exact:true}).click();assert.equal(await p.evaluate(()=>pdfEd.items.length),2);
    // Een gemaakte PDF direct bewaren: eerst ontgrendelen, geen verwijdering uit de uitvoerlijst.
    await p.click('#pdf-edit-save');await p.waitForFunction(()=>!pdfBusy);
    const name=await p.evaluate(()=>Android._pdf.outs[0].name),before=await p.evaluate(()=>Android._kl.docs.length);
    const keep=async()=>{await p.evaluate(name=>pdfOutMenu(name),name);await p.getByRole('button',{name:'Bewaren in documentenkluis',exact:true}).click();await p.fill('#modal-input','Verzekeringen');await p.click('#modal-ok');};
    await p.evaluate(()=>{window.unlock=Android.kluisUnlock;Android.kluisUnlock=()=>setTimeout(()=>onKluis('Geannuleerd'),30);});
    await keep();await p.waitForFunction(()=>!pdfBusy);assert.equal(await p.evaluate(()=>Android._kl.docs.length),before);
    await p.evaluate(()=>Android.kluisUnlock=window.unlock);await keep();await p.waitForFunction(()=>!pdfBusy&&pdfKeepPending===null);
    assert.equal(await p.evaluate(()=>Android._kl.docs.length),before+1);
    const doc=await p.evaluate(name=>Android._kl.docs.find(d=>d.name===name),name);assert.equal(doc.category,'Verzekeringen');assert.equal(doc.mime,'application/pdf');
    assert.ok(await p.evaluate(name=>Android._pdf.outs.some(d=>d.name===name),name),'de tijdelijke PDF blijft behouden');
    await p.evaluate(()=>Android._kl.error='Kluis vol');await keep();await p.waitForFunction(()=>!pdfBusy);assert.equal(await p.evaluate(()=>Android._kl.docs.length),before+1);
    assert.match(await p.textContent('#pdf-keep-status'),/Kluis vol/);await p.evaluate(()=>Android._kl.error='');
    // Verwijderen kan mislukken of geannuleerd worden; geplaatste exemplaren blijven staan.
    const items=await p.evaluate(()=>JSON.stringify(pdfEd.items));await p.click('#pdf-signature-delete');await p.click('#modal-cancel');assert.ok(await p.evaluate(()=>Android._signature));
    await p.evaluate(()=>Android._signatureError='Kan niet verwijderen');await p.click('#pdf-signature-delete');await p.click('#modal-ok');assert.deepEqual(await p.evaluate(()=>pdfSig.value),replacement);
    await p.evaluate(()=>Android._signatureError='');await p.click('#pdf-signature-delete');await p.click('#modal-ok');
    assert.equal(await p.evaluate(()=>Android._signature),null);assert.equal(await p.isVisible('#pdf-signature-preview'),false);assert.equal(await p.evaluate(()=>JSON.stringify(pdfEd.items)),items);
    // Beschadigde of ongeldige opgeslagen gegevens worden zichtbaar geweigerd.
    await p.evaluate(()=>{Android._signature={ratio:Infinity,color:'#111111',paths:[[[0,0],[1,1]]]};pdfSignatureLoad();});
    assert.equal(await p.isDisabled('#pdf-signature-use'),true);assert.match(await p.textContent('#pdf-signature-status'),/niet te lezen/);
    await p.evaluate(s=>{Android._signature=s;pdfSignatureLoad();},replacement);
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.locator('#pdf-signature-preview').scrollIntoViewIfNeeded();await p.screenshot({path:__dirname+'/shots/pdf-saved-signature-'+scheme+'.png'});
    assert.deepEqual(errors,[]);console.log(scheme,'handtekening bewaren, hergebruiken, vervangen, verwijderen, opslagfouten en PDF naar kluis: errors []');await p.close();
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
