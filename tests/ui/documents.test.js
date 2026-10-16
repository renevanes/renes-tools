const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));await p.evaluate(()=>show('kluis'));
    assert.equal(await p.isVisible('#kl-locked'),true);await p.getByRole('button',{name:'Ontgrendelen',exact:true}).click();await p.waitForFunction(()=>$('#kl-open').style.display==='block');
    const first=await p.evaluate(()=>JSON.parse(JSON.stringify(Android._kl.docs[0])));
    const menu=async(id,label)=>{await p.locator('#kl-list [data-id="'+id+'"]').click();await p.getByRole('button',{name:label,exact:true}).click();};
    await menu(first.id,'Categorie en toelichting');await p.fill('#kl-edit-category','Identiteit');await p.fill('#kl-edit-note','Paspoort geldig tot december');await p.check('#kl-edit-favorite');await p.click('#kl-edit-save');
    let doc=await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id),first.id);
    assert.equal(doc.category,'Identiteit');assert.equal(doc.favorite,true);assert.equal(doc.note,'Paspoort geldig tot december');assert.equal(doc.size,first.size);assert.equal(doc.mime,first.mime);
    await p.getByRole('button',{name:'＋ Document toevoegen',exact:true}).click();await p.waitForFunction(()=>Android._kl.docs.length===2);
    assert.equal(await p.locator('#kl-list b b').count(),0,'documentnaam blijft tekst');
    await p.fill('#kl-search','december');assert.equal(await p.locator('#kl-list .trip').count(),1);
    await p.fill('#kl-search','');await p.selectOption('#kl-category','category:Identiteit');assert.equal(await p.locator('#kl-list .trip').count(),1);
    await p.selectOption('#kl-category','none');assert.equal(await p.locator('#kl-list .trip').count(),1);
    await p.selectOption('#kl-category','all');await p.check('#kl-favorites');assert.equal(await p.locator('#kl-list .trip').count(),1);
    await menu(first.id,'Naam wijzigen');await p.fill('#modal-input','Mijn paspoort.pdf');await p.click('#modal-ok');
    doc=await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id),first.id);assert.equal(doc.category,'Identiteit');assert.equal(doc.note,'Paspoort geldig tot december');
    await menu(first.id,'Uit favorieten halen');assert.equal(await p.locator('#kl-list .trip').count(),0);assert.match(await p.textContent('#kl-empty'),/filters/);
    await p.uncheck('#kl-favorites');await p.selectOption('#kl-sort','name');
    const names=await p.locator('#kl-list b').allTextContents();assert.ok(names[0].includes('Mijn paspoort'));
    // Mislukt opslaan behoudt het document én het formulier om opnieuw te proberen.
    await menu(first.id,'Categorie en toelichting');await p.fill('#kl-edit-category','Wonen');await p.fill('#kl-edit-note','Nieuw concept');
    await p.evaluate(()=>Android._kl.error='Opslaan lukt niet');await p.click('#kl-edit-save');
    assert.equal(await p.inputValue('#kl-edit-category'),'Wonen');assert.equal(await p.inputValue('#kl-edit-note'),'Nieuw concept');
    assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).category,first.id),'Identiteit');
    await p.evaluate(()=>Android._kl.error='');await p.click('#kl-edit-save');assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).category,first.id),'Wonen');
    // Categorie en toelichting met HTML blijven veilig en zijn doorzoekbaar.
    await menu(first.id,'Categorie en toelichting');await p.fill('#kl-edit-category','<img src=x>');await p.fill('#kl-edit-note','<script>alert(1)</script>');await p.click('#kl-edit-save');
    assert.equal(await p.locator('#kl-list img,#kl-list script').count(),0);await p.fill('#kl-search','alert');assert.equal(await p.locator('#kl-list .trip').count(),1);await p.fill('#kl-search','');
    // Bij verlopen sessie komt niets door en verdwijnen de gevoelige gegevens uit de bediening.
    await menu(first.id,'Categorie en toelichting');await p.fill('#kl-edit-note','Nog niet bewaard');await p.evaluate(()=>Android._kl.open=false);await p.click('#kl-edit-save');
    assert.equal(await p.isVisible('#kl-locked'),true);assert.equal(await p.locator('#kl-list .trip').count(),0);assert.equal(await p.inputValue('#kl-edit-note'),'');
    await p.getByRole('button',{name:'Ontgrendelen',exact:true}).click();await p.waitForFunction(()=>$('#kl-open').style.display==='block');
    assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).note,first.id),'<script>alert(1)</script>');
    // Filter en categorielijst worden hersteld uit de native index, ook na een nieuwe schermopening.
    await p.evaluate(()=>{show('pdf');show('kluis');});assert.equal(await p.isVisible('#kl-locked'),true);await p.getByRole('button',{name:'Ontgrendelen',exact:true}).click();await p.waitForFunction(()=>$('#kl-open').style.display==='block');
    await p.selectOption('#kl-category','category:<img src=x>');assert.equal(await p.locator('#kl-list .trip').count(),1);
    await menu(first.id,'Verwijderen');await p.click('#modal-cancel');assert.equal(await p.evaluate(()=>Android._kl.docs.length),2);
    await menu(first.id,'Verwijderen');await p.click('#modal-ok');assert.equal(await p.evaluate(()=>Android._kl.docs.length),1);assert.equal(await p.inputValue('#kl-category'),'all');
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.screenshot({path:__dirname+'/shots/documents-'+scheme+'.png',fullPage:true});
    assert.deepEqual(errors,[]);console.log(scheme,'documenten opslaan, categoriseren, favorieten, zoeken, sorteren, gegevensbehoud en vergrendeling: errors []');await p.close();
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
