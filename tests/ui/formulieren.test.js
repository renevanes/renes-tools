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
    assert.equal(await p.evaluate(()=>current+' '+fm.view),'formulieren start');
    // Start: één hoofdknop, rijen voor vaste gegevens en zelf invullen; geen keuzelijst bij één formulier.
    assert.equal(await p.isVisible('#pff-keuze'),false,'geen keuzelijst met maar één formulier');
    assert.equal(await p.isVisible('#pff-werk'),false,'stappen staan niet meteen in beeld');
    assert.match(await p.textContent('#pff-zelf-stand'),/Alle velden, stap voor stap/);
    await p.click('#pff-menu');assert.deepEqual(await p.$$eval('#sheet-acts button',x=>x.map(e=>e.textContent)),['Officiële bron openen'],'leegmaken pas als er iets is ingevuld');
    await p.evaluate(()=>closeSheet());
    await p.click('#pff-zelf');assert.equal(await p.evaluate(()=>fm.view),'stap');assert.match(await p.textContent('#pff-titel'),/^Reis$/);
    // Per weergave hooguit één gevulde knop.
    const gevuld=()=>p.evaluate(()=>{
      const probe=document.createElement('div');probe.style.background='var(--primary)';document.body.append(probe);const prim=getComputedStyle(probe).backgroundColor;probe.remove();
      return [...document.querySelectorAll('#s-formulieren button')].filter(e=>e.getClientRects().length&&getComputedStyle(e).backgroundColor===prim).map(e=>e.textContent.trim());
    });
    assert.deepEqual(await gevuld(),['Volgende stap']);
    // Lege verplichte velden houden niet tegen (die zijn pas nodig voor de PDF), maar je ziet het wel.
    await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),1,'lege verplichte velden: toch verder');
    assert.match(await p.textContent('#pff-melding'),/verplichte velden in de vorige stap zijn nog leeg/);
    await p.click('#pff-vorige');assert.equal(await p.evaluate(()=>fm.step),0);
    const fill=async(id,value)=>p.fill('#pff-v-'+id,value);
    assert.equal(await p.evaluate(()=>$('#pff-meer').open),false,'optionele velden ingeklapt');
    await fill('van','2027-07-15');await fill('tot','2027-07-01');await fill('land','Frankrijk');
    await p.click('#pff-volgende');assert.match(await p.textContent('#pff-melding'),/laatste reisdag/);
    await fill('tot','2027-07-30');await p.click('#pff-volgende');assert.match(await p.textContent('#pff-titel'),/^Kind$/);
    // Terug naar de start bewaart het concept; de rij zegt waar je was.
    assert.equal(await p.evaluate(()=>goBack()),true);assert.equal(await p.evaluate(()=>current+' '+fm.view),'formulieren start');
    assert.equal(await p.evaluate(()=>document.activeElement.id),'pff-zelf','focus terug op de rij');
    assert.match(await p.textContent('#pff-zelf-stand'),/Verder bij stap 2 van 5: Kind/);
    await p.click('#pff-zelf');assert.equal(await p.evaluate(()=>fm.step),1);assert.equal(await p.evaluate(()=>fm.values.land),'Frankrijk');
    await fill('kind-naam','René <akkoord>');await fill('kind-voornamen','Zoë');await fill('kind-geboortedatum','2015-02-03');
    // Een fout in een ingeklapt veld klapt het open en zet de focus erop.
    await p.click('#pff-meer summary');await fill('kind-bsn','123');await p.click('#pff-meer summary');assert.equal(await p.evaluate(()=>$('#pff-meer').open),false);
    await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),1);
    assert.equal(await p.evaluate(()=>$('#pff-meer').open&&document.activeElement.id),'pff-v-kind-bsn','fout veld zichtbaar en in focus');
    await fill('kind-bsn','');await p.click('#pff-volgende');
    await fill('begeleider1-naam','Begeleider met accent É');
    await p.click('#pff-volgende');await fill('gever1-naam','Ouder & voogd');await fill('gever1-postcode','1234 ab');
    await fill('gever1-postcode','12345 AB');await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),3,'te lange postcode blokkeert doorgaan');
    await fill('gever1-postcode','1234 ab');await p.click('#pff-volgende');
    assert.match(await p.textContent('#pff-titel'),/Controleren/);
    await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);
    assert.equal(await p.evaluate(()=>$('#pff-alles').open),false,'samenvatting ingeklapt onder het voorbeeld');
    assert.deepEqual(await gevuld(),['Ingevulde PDF maken']);
    assert.equal(await p.locator('#pff-samenvatting akkoord').count(),0,'persoonsgegevens blijven tekst');
    assert.match(await p.textContent('#pff-samenvatting'),/René <akkoord>/);
    // Controleer de tekenopdrachten, inclusief vaste PDF-datum en gesplitste postcode.
    const ink=async()=>p.evaluate(()=>{
      const out=[],original=CanvasRenderingContext2D.prototype.fillText;
      CanvasRenderingContext2D.prototype.fillText=function(value,x,y){out.push([value,x,y]);};
      try{fmDef().pages.forEach((_,page)=>fmTeken(document.createElement('canvas'),page));}finally{CanvasRenderingContext2D.prototype.fillText=original;}
      return out;
    });
    let text=await ink();assert.ok(text.some(x=>x[0]==='15-07-2027'));assert.ok(text.some(x=>x[0]==='1234'));assert.ok(text.some(x=>x[0]==='AB'));assert.ok(text.some(x=>x[0]==='Frankrijk'));
    await p.click('#pff-pagina-volgende');await p.waitForFunction(()=>fm.page===1&&!document.querySelector('#pff-maak').disabled);
    await p.fill('#pff-naam','Reistoestemming Zoë');
    assert.deepEqual(await p.evaluate(()=>{fmMaak();return [fm.busy,goBack(),fm.view,fmMenuActs().map(a=>a[0]).join('|')];}),[true,true,'stap','Officiële bron openen'],'tijdens het maken blijft de stap staan en kun je niet leegmaken');
    await p.waitForFunction(()=>!fm.busy);
    assert.equal(await p.isVisible('#pff-resultaat'),true);
    assert.deepEqual(await gevuld(),['Opslaan als…'],'na het maken is Opslaan als… de hoofdknop');
    const exported=await p.evaluate(()=>Android._pdf.form);assert.equal(exported.id,'reistoestemming');assert.deepEqual(exported.layers.map(x=>x.page),[0,1]);
    assert.ok(exported.layers.every(x=>x.png.startsWith('data:image/png;base64,')));
    fs.writeFileSync(path.join(__dirname,'shots','formulier-lagen-'+colorScheme+'.json'),JSON.stringify(exported));
    // De ingevulde PDF gaat vanzelf de documentenkluis in; opslaan, delen en bekijken gaan dan via de kluis
    await p.waitForFunction(()=>!kc.bezig&&!kc.wacht&&!kc.rij.length&&kcIn('Reistoestemming Zoë.pdf'));
    const kdoc=await p.evaluate(()=>Android._kl.docs.find(d=>d.name==='Reistoestemming Zoë.pdf'));assert.equal(kdoc.source,'formulier');assert.equal(kdoc.category,'Formulieren');
    assert.ok(!await p.evaluate(()=>Android._pdf.outs.some(o=>o.name==='Reistoestemming Zoë.pdf')),'leesbare PDF weg');
    await p.getByRole('button',{name:'Opslaan als…',exact:true}).click();await p.waitForFunction(v=>JSON.stringify(Android._kl.savedAs)===JSON.stringify(v),{id:kdoc.id,version:0});
    await p.getByRole('button',{name:'Delen',exact:true}).click();await p.waitForFunction(v=>JSON.stringify(Android._kl.shared)===JSON.stringify(v),{id:kdoc.id,version:0});
    await p.getByRole('button',{name:'Bekijken en afdrukken',exact:true}).click();await p.waitForFunction(v=>JSON.stringify(Android._kl.opened)===JSON.stringify(v),{id:kdoc.id,version:0});
    assert.equal(await p.evaluate(()=>Object.values(localStorage).join('').includes('Ouder & voogd')),false,'geen concept in lokale opslag (alleen versleuteld in de app)');
    await p.evaluate(()=>goBack());assert.match(await p.textContent('#pff-zelf-stand'),/PDF gemaakt: Reistoestemming Zoë\.pdf/);await p.click('#pff-zelf');
    // Terug en conditionele personen: eerder ingevulde, verborgen begeleiders mogen niet worden geëxporteerd.
    await p.evaluate(()=>{fm.step=0;fmRender();});
    await p.selectOption('#pff-v-alleen','ja');await p.selectOption('#pff-v-tweedeGever','ja');
    assert.equal(await p.isVisible('#pff-resultaat'),false,'oude PDF niet als actuele kopie tonen na invoerwijziging');
    await p.click('#pff-volgende');await p.click('#pff-volgende');assert.match(await p.textContent('#pff-titel'),/Toestemminggever 1/);
    await p.click('#pff-volgende');await fill('gever2-naam','Tweede ouder');await p.click('#pff-volgende');
    await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);
    text=await ink();assert.ok(!text.some(x=>x[0].includes('Begeleider met accent')));assert.ok(text.some(x=>x[0]==='Tweede ouder'));
    assert.ok(text.some(x=>x[0]==='X'&&x[1]===208),'ja bij alleen reizen op de juiste positie');
    // Beide begeleiders, tweede gever weer uit: geen oude gegevens in de laag.
    await p.evaluate(()=>{fm.step=0;fmRender();});await p.selectOption('#pff-v-alleen','nee');await p.selectOption('#pff-v-tweedeBegeleider','ja');await p.selectOption('#pff-v-tweedeGever','nee');
    await p.click('#pff-volgende');await p.click('#pff-volgende');await p.click('#pff-volgende');await fill('begeleider2-naam','Tweede begeleider');
    await p.click('#pff-volgende');await p.click('#pff-volgende');
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
    await p.evaluate(()=>show('formulieren'));assert.equal(await p.evaluate(()=>fm.view),'stap','terugkomen gaat verder waar je was');
    // Leegmaken via het menu, met bevestiging; daarna weer de start.
    const leeg=async()=>{await p.click('#pff-menu');await p.locator('#sheet-acts button',{hasText:'Formulier leegmaken'}).click();};
    await leeg();await p.locator('#modal-cancel').click();assert.equal(await p.evaluate(()=>fm.values['kind-voornamen']),'Zoë');
    await p.evaluate(()=>{fm.step=fmSecties().length;fmRender();$('#pff-alles').open=true;});
    await leeg();await p.locator('#modal-ok').click();assert.equal(await p.evaluate(()=>fm.values['kind-voornamen']+'|'+fm.view),'|start');
    assert.equal(await p.evaluate(()=>$('#pff-alles').open+' '+$('#pff-samenvatting').childElementCount),'false 0','leegmaken klapt de samenvatting dicht en wist hem');
    await p.screenshot({path:path.join(__dirname,'shots','formulieren-start-'+colorScheme+'.png'),fullPage:true});
    // Een tweede definitie gebruikt dezelfde invuller, zonder aanpassingen aan de engine; nu is er een keuzelijst.
    await p.evaluate(()=>{PDF_FORMULIEREN.proef=()=>({id:'proef',title:t('Titel'),description:'',source:'https://example.org',pages:[{width:595.276,height:841.89}],instruction:'',sections:[{title:t('Titel'),fields:[{id:'proef-tekst',label:t('Tekst'),required:true,pdf:[{page:0,x:40,y:40,w:300,h:12}]}]}]});enterFormulieren();});
    assert.equal(await p.isVisible('#pff-keuze'),true,'keuzelijst bij twee formulieren');
    await p.selectOption('#pff-keuze','proef');
    assert.equal(await p.isVisible('#fmq-start'),false,'zonder vaste gegevens geen Nieuwe reis');assert.equal(await p.isVisible('#pff-start-rijen'),false);
    assert.equal(await p.textContent('#pff-start-knop'),'Formulier invullen');await p.click('#pff-start-knop');
    await fill('proef-tekst','Tweede soort formulier');await p.click('#pff-volgende');
    await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);assert.ok((await ink()).some(x=>x[0]==='Tweede soort formulier'));
    assert.deepEqual(errors,[]);console.log(colorScheme,'start, stappen, ingeklapte velden, validatie, voorwaarden, twee PDF-pagina’s, export, privacy en tweede definitie; errors []');await p.close();
  }
  await b.close();
})().catch(e=>{console.error(e);process.exit(1);});
