const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async()=>{
  const b=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await b.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));
    await p.evaluate(()=>openTool('pdf'));await p.click('#pdf-edit-pick');
    await p.waitForFunction(()=>pdfEd.revision&&!pdfEd.loading&&pdfEd.meta[0]);
    assert.equal(await p.isDisabled('#pdf-edit-save'),true);
    await p.fill('#pdf-edit-text','René <akkoord> €\nRotterdam');await p.click('#pdf-edit-add-text');
    assert.equal(await p.evaluate(()=>pdfEd.items.length),1);
    assert.match(await p.textContent('#pdf-edit-items'),/<akkoord>/);
    assert.equal(await p.locator('#pdf-edit-items akkoord').count(),0,'tekst blijft tekst');
    await p.focus('#pdf-edit-paper');await p.keyboard.press('ArrowRight');
    assert.ok(await p.evaluate(()=>pdfEd.items[0].x>.1),'verplaatsen met toetsenbord');
    await p.locator('#pdf-edit-width').fill('40');await p.locator('#pdf-edit-width').dispatchEvent('change');
    assert.equal(await p.evaluate(()=>pdfEd.items[0].w),.4);
    await p.fill('#pdf-edit-text','René van Es');await p.click('#pdf-edit-add-text');
    assert.equal(await p.evaluate(()=>pdfEd.items[0].text),'René van Es');
    await p.click('#pdf-edit-undo');assert.match(await p.evaluate(()=>pdfEd.items[0].text),/<akkoord>/);
    await p.getByRole('button',{name:'Handtekening tekenen',exact:true}).click();
    await p.getByRole('button',{name:'Handtekening plaatsen',exact:true}).click();
    assert.equal(await p.evaluate(()=>pdfEd.items.length),1,'lege handtekening niet geplaatst');
    const pad=await p.locator('#pdf-edit-sign').boundingBox();
    await p.mouse.move(pad.x+20,pad.y+40);await p.mouse.down();await p.mouse.move(pad.x+60,pad.y+65,{steps:5});await p.mouse.move(pad.x+120,pad.y+20,{steps:5});await p.mouse.up();
    await p.getByRole('button',{name:'Handtekening plaatsen',exact:true}).click();
    assert.equal(await p.evaluate(()=>pdfEd.items[1].type),'ink');
    assert.equal(await p.isVisible('#pdf-edit-sign-box'),false,'tekening na plaatsen gesloten');
    // Aanwijzen en slepen van de handtekening, binnen de paginagrenzen.
    await p.locator('#pdf-edit-paper').scrollIntoViewIfNeeded();
    const paper=await p.locator('#pdf-edit-layer').boundingBox(),ink=await p.evaluate(()=>pdfEd.items[1]);
    const x=paper.x+(ink.x+ink.w/2)*paper.width,y=paper.y+(ink.y+ink.h/2)*paper.height;
    await p.mouse.move(x,y);await p.mouse.down();await p.mouse.move(x+20,y-15,{steps:5});await p.mouse.up();
    assert.ok(await p.evaluate(()=>pdfEd.items[1].x>.1),'handtekening verplaatst');
    await p.screenshot({path:__dirname+'/shots/pdf-edit-document-'+scheme+'.png'});
    await p.click('#pdf-edit-next');await p.waitForFunction(()=>pdfEd.page===1&&!pdfEd.loading);
    await p.getByRole('button',{name:'Datum plaatsen',exact:true}).click();
    assert.equal(await p.evaluate(()=>pdfEd.items[2].page),1);
    await p.fill('#pdf-edit-name','Contract ondertekend');
    await p.click('#pdf-edit-save');await p.waitForFunction(()=>!pdfBusy);
    const result=await p.evaluate(()=>Android._pdf.edits);
    assert.equal(result.layers.length,2);assert.deepEqual(result.layers.map(x=>x.page),[0,1]);
    assert.ok(result.layers.every(x=>x.png.startsWith('data:image/png;base64,')));
    assert.equal(result.name,'Contract ondertekend');
    require('fs').writeFileSync(__dirname+'/shots/pdf-edit-layers-'+scheme+'.json',JSON.stringify(result));
    assert.match(await p.textContent('#pdf-out'),/Contract ondertekend.pdf/);
    assert.equal(await p.isDisabled('#pdf-edit-save'),true,'geen ongewijzigde dubbele export');
    // Een mislukte export houdt de bewerkingen vast; een oud preview-resultaat overschrijft de huidige pagina niet.
    await p.getByRole('button',{name:'Datum plaatsen',exact:true}).click();
    await p.evaluate(()=>{Android._pdf.editError='Geen opslagruimte';onPdfEditPreview({revision:'ander-document',page:1,width:100,height:100,preview:'ongeldig'});});
    await p.click('#pdf-edit-save');await p.waitForFunction(()=>!pdfBusy);
    assert.equal(await p.evaluate(()=>pdfEd.items.length),4);
    assert.equal(await p.isDisabled('#pdf-edit-save'),false,'opnieuw proberen mogelijk');
    // Annuleren van documentvervanging behoudt het werk.
    await p.click('#pdf-edit-pick');await p.getByRole('button',{name:'Annuleren',exact:true}).click();
    assert.equal(await p.evaluate(()=>pdfEd.items.length),4);
    await p.click('#pdf-edit-pick');await p.locator('#modal-ok').click();
    await p.waitForFunction(()=>pdfEd.items.length===0&&!pdfEd.loading);
    await p.evaluate(()=>{Android._pdf.previewError='Deze PDF kan niet veilig bewerkt worden';pdfEditLoad();});
    await p.waitForFunction(()=>!pdfEd.loading);
    assert.match(await p.textContent('#pdf-edit-status'),/niet veilig/);
    assert.equal(await p.isDisabled('#pdf-edit-add-text'),true);
    assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.screenshot({path:__dirname+'/shots/pdf-edit-'+scheme+'.png'});
    assert.deepEqual(errors,[]);console.log(scheme,'tekst, verplaatsen, handtekening, meerdere pagina’s, export en foutafhandeling gecontroleerd; errors []');await p.close();
  }
  await b.close();
})().catch(e=>{console.error(e);process.exit(1);});
