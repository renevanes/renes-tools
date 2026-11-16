const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));await p.evaluate(()=>show('kluis'));
    assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'),true,'op slot: het centrale slotscherm');assert.equal(await p.locator('#s-kluis button',{hasText:'Ontgrendelen'}).count(),0,'geen eigen ontgrendelknop');
    await p.click('#ts-open');await p.waitForFunction(()=>$('#kl-open').style.display==='block');
    const first=await p.evaluate(()=>JSON.parse(JSON.stringify(Android._kl.docs[0])));
    const chip=map=>p.locator('#kl-mappen button[data-map="'+map+'"]'),gekozen=()=>p.getAttribute('#kl-mappen button[aria-pressed="true"]','data-map');
    // Tik op een document opent de documentkaart; de overige acties staan daar onder Meer.
    const kaart=async id=>{await p.evaluate(()=>{if(klEditId)klOrganizeClose();});await p.locator('#kl-list .trip[data-id="'+id+'"]').click();await p.waitForSelector('#kl-edit:not([hidden])');};
    const menu=async(id,label)=>{await kaart(id);if(label==='Map en toelichting')return;await p.click('#kl-doc-meer');await p.locator('#sheet-acts').getByRole('button',{name:label,exact:true}).click();await p.evaluate(()=>{if(klEditId)klOrganizeClose();});};
    await menu(first.id,'Map en toelichting');await p.fill('#kl-edit-category','Identiteit');await p.fill('#kl-edit-note','Paspoort geldig tot december');await p.check('#kl-edit-favorite');await p.click('#kl-edit-save');
    let doc=await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id),first.id);
    assert.equal(doc.category,'Identiteit');assert.equal(doc.favorite,true);assert.equal(doc.note,'Paspoort geldig tot december');assert.equal(doc.size,first.size);assert.equal(doc.mime,first.mime);
    await p.getByRole('button',{name:'＋ Document toevoegen',exact:true}).click();await (async()=>{if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');})();await p.waitForFunction(()=>Android._kl.docs.length===2);
    assert.equal(await p.locator('#kl-list b b').count(),0,'documentnaam blijft tekst');
    await p.fill('#kl-search','december');assert.equal(await p.locator('#kl-list .trip').count(),1);
    await p.fill('#kl-search','');
    // Mappen als chips met aantallen; in Alle staan favorieten bovenaan onder een eigen kop.
    assert.deepEqual(await p.locator('#kl-mappen button').evaluateAll(l=>l.map(b=>b.dataset.map+'='+b.querySelector('.kl-n').textContent)),['all=2','fav=1','map:Identiteit=1','none=1']);
    assert.deepEqual(await p.locator('#kl-list .kl-kop').allTextContents(),['Favorieten','Overige documenten']);assert.ok((await p.locator('#kl-list b').first().textContent()).includes('Paspoort'));
    await chip('map:Identiteit').click();assert.equal(await p.locator('#kl-list .trip').count(),1);assert.equal(await gekozen(),'map:Identiteit');assert.equal(await p.isVisible('#kl-maphernoem'),true);assert.equal(await p.locator('#kl-list .kl-kop').count(),0);
    await chip('none').click();assert.equal(await p.locator('#kl-list .trip').count(),1);assert.equal(await p.isVisible('#kl-maphernoem'),false);
    await chip('fav').click();assert.equal(await p.locator('#kl-list .trip').count(),1);
    await menu(first.id,'Naam wijzigen');await p.fill('#modal-input','Mijn paspoort.pdf');await p.click('#modal-ok');
    doc=await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id),first.id);assert.equal(doc.category,'Identiteit');assert.equal(doc.note,'Paspoort geldig tot december');
    await menu(first.id,'Uit favorieten halen');assert.equal(await gekozen(),'all','een lege keuze valt terug op Alle');assert.equal(await chip('fav').count(),0);assert.equal(await p.locator('#kl-list .trip').count(),2);
    await p.fill('#kl-search','bestaat niet');assert.equal(await p.locator('#kl-list .trip').count(),0);assert.match(await p.textContent('#kl-empty'),/filters/);await p.fill('#kl-search','');
    // De ster in de rij zet een favoriet aan en uit, zonder het menu.
    const ster=p.locator('#kl-list .kl-ster[data-id="'+first.id+'"]');await ster.click();assert.equal(await ster.getAttribute('aria-pressed'),'true');assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).favorite,first.id),true);
    await ster.click();assert.equal(await ster.getAttribute('aria-pressed'),'false');assert.ok((await ster.boundingBox()).width>=44);
    // Verplaatsen naar een bestaande, een nieuwe of geen map; de toelichting blijft.
    await menu('k2abcdef','Verplaatsen naar map…');await p.locator('#sheet-acts button',{hasText:'Identiteit'}).click();assert.equal(await p.evaluate(()=>Android._kl.docs.find(d=>d.id==='k2abcdef').category),'Identiteit');assert.equal(await chip('none').count(),0);
    await menu('k2abcdef','Verplaatsen naar map…');await p.locator('#sheet-acts button',{hasText:'Nieuwe map…'}).click();await p.fill('#modal-input','  Verzekeringen ');await p.click('#modal-ok');assert.equal(await p.evaluate(()=>Android._kl.docs.find(d=>d.id==='k2abcdef').category),'Verzekeringen');
    await p.selectOption('#kl-sort','category');assert.deepEqual(await p.locator('#kl-list .kl-kop').allTextContents(),['Identiteit','Verzekeringen']);
    // Een map hernoemen neemt elk document in die map mee en houdt de map gekozen.
    await chip('map:Verzekeringen').click();await p.click('#kl-maphernoem');await p.fill('#modal-input','Polissen');await p.click('#modal-ok');assert.equal(await p.evaluate(()=>Android._kl.docs.find(d=>d.id==='k2abcdef').category),'Polissen');assert.equal(await gekozen(),'map:Polissen');
    // Een fout halverwege zegt hoeveel er al verplaatst is; een lange mapnaam past ook op een smal scherm.
    await p.evaluate(()=>Android._kl.error='Opslaan lukt niet');await p.click('#kl-maphernoem');await p.fill('#modal-input','Anders');await p.click('#modal-ok');assert.equal(await p.evaluate(()=>Android._kl.docs.find(d=>d.id==='k2abcdef').category),'Polissen');assert.equal(await gekozen(),'map:Polissen');await p.evaluate(()=>Android._kl.error='');
    await p.click('#kl-maphernoem');await p.fill('#modal-input','Verzekeringspolissenenanderebelangrijkepapierenvanditjaar');await p.click('#modal-ok');await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'lange mapnaam loopt niet buiten beeld');await p.setViewportSize({width:390,height:844});
    await menu('k2abcdef','Verplaatsen naar map…');assert.equal(await p.locator('#sheet-acts button').first().getAttribute('translate'),'no','mapnamen in het menu zijn gegevens');await p.evaluate(()=>closeSheet());
    await menu('k2abcdef','Verplaatsen naar map…');await p.locator('#sheet-acts button',{hasText:'Geen map'}).click();assert.equal(await p.evaluate(()=>Android._kl.docs.find(d=>d.id==='k2abcdef').category),'');assert.equal(await gekozen(),'all');
    assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).note,first.id),'Paspoort geldig tot december');
    // Documentkaart: eigenschappen bewaren, terugzien in de lijst, zoeken, sorteren en "Verloopt binnenkort".
    await kaart(first.id);assert.equal(await p.isVisible('#kl-lijstkaart'),false,'de kaart komt in plaats van de lijst');assert.equal(await p.textContent('#kl-edit-title'),'Mijn paspoort.pdf');
    const over=n=>p.evaluate(n=>rgDayAdd(rgToday(),n),n);
    await p.fill('#kl-edit-soort','Identiteitsbewijs');await p.fill('#kl-edit-partij','Gemeente <b>Rotterdam</b>');await p.fill('#kl-edit-datum','2021-12-01');await p.fill('#kl-edit-verloopt',await over(30));
    await p.fill('#kl-edit-bedrag','abc');await p.click('#kl-edit-save');assert.match(await p.textContent('#kl-edit-status'),/geldig bedrag/);assert.equal(await p.isVisible('#kl-edit'),true,'ongeldig bedrag: de kaart blijft open');
    await p.fill('#kl-edit-bedrag','77,5');await p.fill('#kl-edit-labels','reizen, Reizen , 2031,');await p.click('#kl-edit-save');assert.equal(await p.isVisible('#kl-lijstkaart'),true);
    doc=await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id),first.id);
    assert.deepEqual(doc.props,{soort:'Identiteitsbewijs',partij:'Gemeente <b>Rotterdam</b>',datum:'2021-12-01',verloopt:await over(30),bedrag:'77.50',labels:['reizen','2031']});assert.equal(doc.note,'Paspoort geldig tot december');assert.equal(doc.category,'Identiteit');
    const rijTekst=()=>p.locator('#kl-list .kl-rij',{has:p.locator('.trip[data-id="'+first.id+'"]')}).textContent();
    assert.match(await rijTekst(),/Identiteitsbewijs · Gemeente <b>Rotterdam<\/b>/);assert.match(await rijTekst(),/Verloopt op/);assert.match(await rijTekst(),/77,50/);assert.equal(await p.locator('#kl-list .kl-rij b b').count(),0,'partij blijft tekst');
    await p.fill('#kl-search','reizen');assert.equal(await p.locator('#kl-list .trip').count(),1,'zoeken vindt labels');await p.fill('#kl-search','gemeente');assert.equal(await p.locator('#kl-list .trip').count(),1,'zoeken vindt de partij');await p.fill('#kl-search','');
    assert.equal(await chip('exp').locator('.kl-n').textContent(),'1');await chip('exp').click();assert.equal(await p.locator('#kl-list .trip').count(),1);await chip('all').click();
    await p.selectOption('#kl-sort','verloopt');assert.ok((await p.locator('#kl-list b').first().textContent()).includes('Mijn paspoort'),'met verloopdatum eerst');
    // De kaart vult de bewaarde waarden weer in; terug (pijl of telefoon) gaat eerst naar de lijst; verlopen is rood gemeld.
    await kaart(first.id);assert.equal(await p.inputValue('#kl-edit-bedrag'),'77,50');assert.equal(await p.inputValue('#kl-edit-labels'),'reizen, 2031');assert.equal(await p.inputValue('#kl-edit-datum'),'2021-12-01');
    await p.fill('#kl-edit-verloopt',await over(-3));await p.click('#kl-edit-save');assert.match(await rijTekst(),/Verlopen op/);
    await kaart(first.id);await p.click('#kl-doc-meer');await p.locator('#sheet-acts').getByRole('button',{name:'Als favoriet markeren',exact:true}).click();assert.equal(await p.isChecked('#kl-edit-favorite'),true,'favoriet via Meer werkt het vinkje bij');await p.uncheck('#kl-edit-favorite');await p.click('#kl-edit-save');assert.equal(await p.evaluate(id=>!!Android._kl.docs.find(d=>d.id===id).favorite,first.id),false);
    await kaart(first.id);await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>current),'kluis','terug blijft in de kluis');assert.equal(await p.isVisible('#kl-lijstkaart'),true);assert.equal(await p.evaluate(()=>Android._kl.open),true);
    // Alles leegmaken haalt de eigenschappen weer weg; verplaatsen vanaf de kaart werkt het veld Map bij.
    await kaart(first.id);for(const k of ['soort','partij','datum','verloopt','bedrag','labels'])await p.fill('#kl-edit-'+k,'');await p.click('#kl-edit-save');assert.equal(await p.evaluate(id=>'props' in Android._kl.docs.find(d=>d.id===id),first.id),false);assert.equal(await chip('exp').count(),0);
    await kaart(first.id);await p.click('#kl-doc-meer');await p.locator('#sheet-acts button',{hasText:'Nieuwe map…'}).count();await p.locator('#sheet-acts').getByRole('button',{name:'Verplaatsen naar map…',exact:true}).click();await p.locator('#sheet-acts button',{hasText:'Nieuwe map…'}).click();await p.fill('#modal-input','Reizen');await p.click('#modal-ok');assert.equal(await p.inputValue('#kl-edit-category'),'Reizen');
    await p.fill('#kl-edit-category','Identiteit');await p.click('#kl-edit-save');assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).category,first.id),'Identiteit');
    await p.selectOption('#kl-sort','name');
    const names=await p.locator('#kl-list b').allTextContents();assert.ok(names[0].includes('Mijn paspoort'));
    // Mislukt opslaan behoudt het document én het formulier om opnieuw te proberen.
    await menu(first.id,'Map en toelichting');await p.fill('#kl-edit-category','Wonen');await p.fill('#kl-edit-note','Nieuw concept');
    await p.evaluate(()=>Android._kl.error='Opslaan lukt niet');await p.click('#kl-edit-save');
    assert.equal(await p.inputValue('#kl-edit-category'),'Wonen');assert.equal(await p.inputValue('#kl-edit-note'),'Nieuw concept');
    assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).category,first.id),'Identiteit');
    await p.evaluate(()=>Android._kl.error='');await p.click('#kl-edit-save');assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).category,first.id),'Wonen');
    // Categorie en toelichting met HTML blijven veilig en zijn doorzoekbaar.
    await menu(first.id,'Map en toelichting');await p.fill('#kl-edit-category','<img src=x>');await p.fill('#kl-edit-note','<script>alert(1)</script>');await p.click('#kl-edit-save');
    assert.equal(await p.locator('#kl-list img,#kl-list script').count(),0);await p.fill('#kl-search','alert');assert.equal(await p.locator('#kl-list .trip').count(),1);await p.fill('#kl-search','');
    // Bij verlopen sessie komt niets door en verdwijnen de gevoelige gegevens uit de bediening.
    await menu(first.id,'Map en toelichting');await p.fill('#kl-edit-note','Nog niet bewaard');await p.evaluate(()=>Android._kl.open=false);await p.click('#kl-edit-save');
    await p.waitForFunction(()=>tsZichtbaar()&&ts.tool==='kluis');assert.equal(await p.isVisible('#kl-open'),false);assert.equal(await p.locator('#kl-list .trip').count(),0);assert.equal(await p.inputValue('#kl-edit-note'),'');
    await p.click('#ts-open');await p.waitForFunction(()=>$('#kl-open').style.display==='block');
    assert.equal(await p.evaluate(id=>Android._kl.docs.find(d=>d.id===id).note,first.id),'<script>alert(1)</script>');
    // Filter en categorielijst worden hersteld uit de native index, ook na een nieuwe schermopening.
    await p.evaluate(()=>{show('pdf');show('kluis');});assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'),true);await p.click('#ts-open');await p.waitForFunction(()=>$('#kl-open').style.display==='block');
    assert.equal(await gekozen(),'all','na op slot begint de kluis weer bij Alle');await chip('map:<img src=x>').click();assert.equal(await p.locator('#kl-list .trip').count(),1);assert.equal(await p.locator('#kl-mappen img').count(),0);
    await menu(first.id,'Verwijderen');await p.click('#modal-cancel');assert.equal(await p.evaluate(()=>Android._kl.docs.length),2);
    await menu(first.id,'Verwijderen');await p.click('#modal-ok');assert.equal(await p.evaluate(()=>Android._kl.docs.length),1);assert.equal(await gekozen(),'all');assert.equal(await chip('map:<img src=x>').count(),0);
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.screenshot({path:__dirname+'/shots/documents-'+scheme+'.png',fullPage:true});
    assert.deepEqual(errors,[]);console.log(scheme,'documenten opslaan, mappen, verplaatsen, favorieten, zoeken, sorteren, gegevensbehoud en vergrendeling: errors []');await p.close();
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
