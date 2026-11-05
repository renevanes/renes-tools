const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('fs');
const path=require('path');
(async()=>{
  const b=await chromium.launch();
  for(const colorScheme of ['light','dark']){
    const p=await b.newPage({viewport:{width:360,height:800},colorScheme});
    const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+path.join(__dirname,'ui','index.html'));
    await p.evaluate(()=>openTool('pdf'));await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
    assert.equal(await p.evaluate(()=>current),'formulieren');
    await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),0,'verplichte velden blokkeren doorgaan');
    const fill=async(id,value)=>p.fill('#pff-v-'+id,value);
    await fill('van','2027-07-15');await fill('tot','2027-07-01');
    await fill('kind-naam','René <akkoord>');await fill('kind-voornamen','Zoë');await fill('kind-geboortedatum','2015-02-03');
    await p.click('#pff-volgende');assert.match(await p.textContent('#pff-melding'),/laatste reisdag/);
    await fill('tot','2027-07-30');
    await fill('kind-bsn','123');await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),0);
    await fill('kind-bsn','');await p.click('#pff-volgende');
    await fill('begeleider1-naam','Begeleider met accent É');
    await p.click('#pff-volgende');await fill('gever1-naam','Ouder & voogd');await fill('gever1-postcode','1234 ab');
    await fill('gever1-postcode','12345 AB');await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),2,'te lange postcode blokkeert doorgaan');
    await fill('gever1-postcode','1234 ab');await p.click('#pff-volgende');await fill('land','Frankrijk');await p.click('#pff-volgende');
    await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);
    assert.equal(await p.locator('#pff-samenvatting akkoord').count(),0,'persoonsgegevens blijven tekst');
    assert.match(await p.textContent('#pff-samenvatting'),/René <akkoord>/);
    // Controleer de tekenopdrachten, inclusief vaste PDF-datum en gesplitste postcode.
    const ink=async()=>p.evaluate(()=>{
      const out=[],original=CanvasRenderingContext2D.prototype.fillText;
      CanvasRenderingContext2D.prototype.fillText=function(value,x,y){out.push([value,x,y]);};
      try{fmDef().pages.forEach((_,page)=>fmTeken(document.createElement('canvas'),page));}finally{CanvasRenderingContext2D.prototype.fillText=original;}
      return out;
    });
    let text=await ink();assert.ok(text.some(x=>x[0]==='15-07-2027'));assert.ok(text.some(x=>x[0]==='1234'));assert.ok(text.some(x=>x[0]==='AB'));
    await p.click('#pff-pagina-volgende');await p.waitForFunction(()=>fm.page===1&&!document.querySelector('#pff-maak').disabled);
    await p.fill('#pff-naam','Reistoestemming Zoë');await p.click('#pff-maak');await p.waitForFunction(()=>!fm.busy);
    assert.equal(await p.isVisible('#pff-resultaat'),true);
    const exported=await p.evaluate(()=>Android._pdf.form);assert.equal(exported.id,'reistoestemming');assert.deepEqual(exported.layers.map(x=>x.page),[0,1]);
    assert.ok(exported.layers.every(x=>x.png.startsWith('data:image/png;base64,')));
    fs.writeFileSync(path.join(__dirname,'shots','formulier-lagen-'+colorScheme+'.json'),JSON.stringify(exported));
    await p.getByRole('button',{name:'Opslaan als…',exact:true}).click();assert.equal(await p.evaluate(()=>Android._pdf.saved),'Reistoestemming Zoë.pdf');
    await p.getByRole('button',{name:'Delen',exact:true}).click();assert.deepEqual(await p.evaluate(()=>Android._pdf.shared),['Reistoestemming Zoë.pdf']);
    await p.getByRole('button',{name:'Bekijken en afdrukken',exact:true}).click();assert.equal(await p.evaluate(()=>Android._pdf.opened),'Reistoestemming Zoë.pdf');
    assert.equal(await p.evaluate(()=>Object.values(localStorage).join('').includes('Ouder & voogd')),false,'geen concept in lokale opslag');
    // Terug en conditionele personen: eerder ingevulde, verborgen begeleiders mogen niet worden geëxporteerd.
    await p.evaluate(()=>{fm.step=0;fmRender();});
    await p.selectOption('#pff-v-alleen','ja');await p.selectOption('#pff-v-tweedeGever','ja');
    assert.equal(await p.isVisible('#pff-resultaat'),false,'oude PDF niet als actuele kopie tonen na invoerwijziging');
    await p.click('#pff-volgende');assert.match(await p.textContent('#pff-titel'),/Toestemminggever 1/);
    await p.click('#pff-volgende');await fill('gever2-naam','Tweede ouder');await p.click('#pff-volgende');await p.click('#pff-volgende');
    await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);
    text=await ink();assert.ok(!text.some(x=>x[0].includes('Begeleider met accent')));assert.ok(text.some(x=>x[0]==='Tweede ouder'));
    assert.ok(text.some(x=>x[0]==='X'&&x[1]===208),'ja bij alleen reizen op de juiste positie');
    // Beide begeleiders, tweede gever weer uit: geen oude gegevens in de laag.
    await p.evaluate(()=>{fm.step=0;fmRender();});await p.selectOption('#pff-v-alleen','nee');await p.selectOption('#pff-v-tweedeBegeleider','ja');await p.selectOption('#pff-v-tweedeGever','nee');
    await p.click('#pff-volgende');await p.click('#pff-volgende');await fill('begeleider2-naam','Tweede begeleider');
    await p.click('#pff-volgende');await p.click('#pff-volgende');await p.click('#pff-volgende');
    text=await ink();assert.ok(text.some(x=>x[0]==='Tweede begeleider'));assert.ok(!text.some(x=>x[0]==='Tweede ouder'));
    // Een fout in export wist geen invoer; callbacks van oude aanvragen worden genegeerd.
    await p.evaluate(()=>{Android._pdf.formError='Geen opslagruimte';});await p.click('#pff-maak');await p.waitForFunction(()=>!fm.busy);
    assert.equal(await p.evaluate(()=>fm.values['kind-voornamen']),'Zoë');assert.match(await p.textContent('#pff-melding'),/Geen opslagruimte/);
    await p.evaluate(()=>onPdfFormPreview({id:fm.id,page:fm.page,request:'oud',error:'Oude aanvraag'}));assert.doesNotMatch(await p.textContent('#pff-preview-status'),/Oude aanvraag/);
    // Overlopende invoer krijgt een fout, wordt nooit onzichtbaar afgeknipt.
    await p.evaluate(()=>{fm.values.reisgegevens='W'.repeat(200);fmPreview();});
    assert.equal(await p.isDisabled('#pff-maak'),true);assert.match(await p.textContent('#pff-preview-status'),/past niet/);
    await p.evaluate(()=>{fm.values.reisgegevens='';fmPreview();});
    await p.screenshot({path:path.join(__dirname,'shots','formulieren-'+colorScheme+'.png'),fullPage:true});
    assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.evaluate(()=>show('home'));assert.equal(await p.evaluate(()=>uiCanReload()),false,'concept blijft beschermd na stapwissels en verlaten van formulier');
    await p.evaluate(()=>show('formulieren'));
    await p.click('#pff-wissen');await p.locator('#modal-cancel').click();assert.equal(await p.evaluate(()=>fm.values['kind-voornamen']),'Zoë');
    await p.click('#pff-wissen');await p.locator('#modal-ok').click();assert.equal(await p.evaluate(()=>fm.values['kind-voornamen']),'');
    // Een tweede definitie gebruikt dezelfde invuller, zonder aanpassingen aan de engine.
    await p.evaluate(()=>{PDF_FORMULIEREN.proef=()=>({id:'proef',title:t('Titel'),description:'',source:'https://example.org',pages:[{width:595.276,height:841.89}],instruction:'',sections:[{title:t('Titel'),fields:[{id:'proef-tekst',label:t('Tekst'),required:true,pdf:[{page:0,x:40,y:40,w:300,h:12}]}]}]});enterFormulieren();});
    await p.selectOption('#pff-keuze','proef');await fill('proef-tekst','Tweede soort formulier');await p.click('#pff-volgende');
    await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);assert.ok((await ink()).some(x=>x[0]==='Tweede soort formulier'));
    assert.deepEqual(errors,[]);console.log(colorScheme,'formulier, validatie, voorwaarden, twee PDF-pagina’s, export, privacy en tweede definitie; errors []');await p.close();
  }
  await b.close();
})().catch(e=>{console.error(e);process.exit(1);});
