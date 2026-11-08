const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('path');
/* Formulieren: tussentijds opslaan met lege verplichte velden, duidelijke stand, terug na opnieuw openen,
   PDF maken wijst het eerste lege verplichte veld aan, gegevens uit Stamgegevens gaan niet in het concept. */
(async()=>{
  const b=await chromium.launch();
  for(const colorScheme of ['light','dark']){
    const p=await b.newPage({viewport:{width:360,height:800},colorScheme});
    const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+path.join(__dirname,'ui','index.html'));
    await p.evaluate(()=>openTool('pdf'));await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
    await p.click('#pff-zelf');
    assert.equal(await p.isVisible('#pff-opslaan'),true,'knop Tussentijds opslaan zichtbaar');
    // Alleen het land invullen (eerste en laatste reisdag zijn verplicht en blijven leeg) en opslaan
    await p.fill('#pff-v-land','Frankrijk');
    assert.match(await p.textContent('#pff-concept'),/Niet opgeslagen wijzigingen/);
    await p.click('#pff-opslaan');
    const c=await p.evaluate(()=>Android._formConcept.reistoestemming);
    assert.equal(c&&c.values.land,'Frankrijk','concept opgeslagen ondanks lege verplichte velden');
    assert.match(await p.textContent('#pff-concept'),/Opgeslagen om .* · nog \d+ verplichte velden leeg/);
    assert.match(await p.textContent('#toast'),/Opgeslagen\. Nog \d+ verplichte velden leeg; dat kan later\./);
    await p.screenshot({path:path.join(__dirname,'shots','formulier-concept-'+colorScheme+'.png'),fullPage:true});
    // Verder naar de volgende stap: vanzelf opgeslagen
    await p.click('#pff-volgende');assert.equal(await p.evaluate(()=>fm.step),1);
    await p.fill('#pff-v-kind-naam','Jansen');await p.click('#pff-volgende');
    assert.equal(await p.evaluate(()=>Android._formConcept.reistoestemming.values['kind-naam']),'Jansen','bij een andere stap vanzelf opgeslagen');
    // App opnieuw geopend (geheugen leeg): het concept komt terug, met de stap
    await p.evaluate(()=>{fm.id='';show('pdf');});await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
    assert.equal(await p.evaluate(()=>fm.values.land+'|'+fm.values['kind-naam']+'|'+fm.step),'Frankrijk|Jansen|2','concept terug na opnieuw openen');
    assert.match(await p.textContent('#pff-zelf-stand'),/concept opgeslagen/);
    // PDF maken met lege verplichte velden: duidelijke melding en het veld zelf aangewezen
    await p.click('#pff-zelf');await p.evaluate(()=>{fm.step=fmSecties().length;fmRender();});
    assert.equal(await p.isVisible('#pff-ontbreekt'),true,'lijst met wat nog leeg is');
    assert.match(await p.textContent('#pff-ontbreekt'),/velden leeg die nodig zijn voor de PDF/);
    await p.evaluate(()=>fmMaak());
    assert.equal(await p.evaluate(()=>fm.step),0,'naar de stap met het eerste lege verplichte veld');
    assert.equal(await p.evaluate(()=>document.activeElement.id),'pff-v-van');
    assert.equal(await p.getAttribute('#pff-v-van','aria-invalid'),'true');
    assert.match(await p.textContent('#pff-fout'),/nodig voor de PDF/);
    assert.match(await p.textContent('#pff-melding'),/verplichte velden leeg, eerst: .*Je invoer is opgeslagen\./);
    await p.fill('#pff-v-van','2027-07-01');assert.equal(await p.isVisible('#pff-fout'),false,'foutregel weg na invullen');
    // Echte fouten houden wel tegen, met de reden bij het veld
    await p.fill('#pff-v-tot','2027-06-01');await p.click('#pff-volgende');
    assert.equal(await p.evaluate(()=>fm.step),0);assert.match(await p.textContent('#pff-melding'),/laatste reisdag/);
    // Gegevens uit Stamgegevens gaan niet in het concept
    await p.evaluate(()=>{fm.values['kind-voornamen']='Uit stam';fm.stam.add('kind-voornamen');});
    await p.click('#pff-opslaan');
    const c2=await p.evaluate(()=>Android._formConcept.reistoestemming.values);
    assert.equal(c2['kind-voornamen'],undefined,'stamgegevens niet in het concept');assert.equal(c2.van,'2027-07-01');
    assert.match(await p.textContent('#toast'),/Stamgegevens bewaart het concept niet/);
    // Opslaan mislukt: duidelijke melding
    await p.evaluate(()=>{Android._pdf.conceptError='Opslaan is niet gelukt. Probeer het nog eens.';});
    await p.fill('#pff-v-land','Spanje');await p.click('#pff-opslaan');
    assert.match(await p.textContent('#pff-concept'),/Opslaan is niet gelukt/);
    await p.evaluate(()=>{Android._pdf.conceptError='';});
    // Leegmaken haalt ook het concept weg
    await p.evaluate(()=>{fmWissen();});await p.click('#modal-ok');
    assert.equal(await p.evaluate(()=>Android._formConcept.reistoestemming),undefined,'concept weg na leegmaken');
    console.log(colorScheme,'errors',errors);
    await p.close();
  }
  await b.close();
})();
