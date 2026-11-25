// Documentenkluis als verkenner: mappen (ook in mappen), het pad, de documentkaart met eigenschappen, zoeken, sorteren,
// verplaatsen, hernoemen, facturen als map, gegevensbehoud en vergrendeling.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));await p.evaluate(()=>show('kluis'));
    assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'),true,'op slot: het centrale slotscherm');assert.equal(await p.locator('#s-kluis button',{hasText:'Ontgrendelen'}).count(),0,'geen eigen ontgrendelknop');
    const open=async()=>{await p.click('#ts-open');await p.waitForFunction(()=>$('#kl-open').style.display==='block');};
    await open();
    const first=await p.evaluate(()=>JSON.parse(JSON.stringify(Android._kl.docs[0]))),A=first.id,B='k2abcdef';
    const doc=id=>p.evaluate(id=>JSON.parse(JSON.stringify(Android._kl.docs.find(d=>d.id===id)||null)),id);
    const map=m=>p.locator('#kl-mappen button[data-map="'+m+'"]'),pad=m=>p.locator('#kl-pad button[data-map="'+m+'"]'),hier=()=>p.getAttribute('#kl-pad','data-map');
    const mappen=()=>p.locator('#kl-mappen button').evaluateAll(l=>l.map(b=>b.dataset.map+'='+b.querySelector('.kl-n').textContent));
    const rijen=()=>p.locator('#kl-list .trip').evaluateAll(l=>l.map(b=>b.dataset.id));
    // Naar de plek van een document gaan en zijn kaart openen (tik op de rij); de overige acties staan onder Meer.
    const kaart=async id=>{await p.evaluate(id=>{if(klEditId)klOrganizeClose();const d=Android._kl.docs.find(x=>x.id===id);klKies(d.category?'map:'+klPad(d.category):'all');},id);await p.locator('#kl-list .trip[data-id="'+id+'"]').click();await p.waitForSelector('#kl-edit:not([hidden])');};
    const meer=async(id,label)=>{await kaart(id);await p.click('#kl-doc-meer');await p.locator('#sheet-acts').getByRole('button',{name:label,exact:true}).click();};
    const dicht=()=>p.evaluate(()=>{if(klEditId)klOrganizeClose();});
    const over=n=>p.evaluate(n=>rgDayAdd(rgToday(),n),n);

    // 1. Bovenste niveau: het pad, een los document; de kaart bewaart map, toelichting en favoriet zonder de rest te raken.
    assert.equal(await hier(),'all');assert.deepEqual(await rijen(),[A]);assert.equal(await p.locator('#kl-mappen button').count(),0);
    await kaart(A);assert.equal(await p.isVisible('#kl-lijstkaart'),false,'de kaart komt in plaats van de lijst');
    await p.fill('#kl-edit-category','Identiteit');await p.fill('#kl-edit-note','Paspoort geldig tot december');await p.check('#kl-edit-favorite');await p.click('#kl-edit-save');
    let d=await doc(A);assert.equal(d.category,'Identiteit');assert.equal(d.favorite,true);assert.equal(d.note,'Paspoort geldig tot december');assert.equal(d.size,first.size);assert.equal(d.mime,first.mime);
    await p.getByRole('button',{name:'＋ Document toevoegen',exact:true}).click();if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');await p.waitForFunction(()=>Android._kl.docs.length===2);await p.waitForSelector('#kl-list .trip[data-id="k2abcdef"]');
    assert.equal(await p.locator('#kl-list b b').count(),0,'documentnaam blijft tekst');
    // 2. Mappen als rijen met aantallen; het losse document staat eronder; een map in gaan en via het pad terug.
    assert.deepEqual(await mappen(),['fav=1','map:Identiteit=1']);assert.deepEqual(await rijen(),[B]);
    await map('map:Identiteit').click();assert.equal(await hier(),'map:Identiteit');assert.deepEqual(await rijen(),[A]);assert.equal(await p.isVisible('#kl-maphernoem'),true);assert.deepEqual(await p.locator('#kl-pad button').allTextContents(),['Documenten','Identiteit']);
    await pad('all').click();assert.equal(await hier(),'all');assert.equal(await p.isVisible('#kl-maphernoem'),false);
    await map('fav').click();assert.deepEqual(await rijen(),[A]);
    // Terug (pijl of telefoon) gaat eerst een map omhoog, daarna pas de kluis uit.
    await p.evaluate(()=>goBack());assert.equal(await hier(),'all');assert.equal(await p.evaluate(()=>current),'kluis');assert.equal(await p.evaluate(()=>Android._kl.open),true);
    // 3. Zoeken kijkt ook in de mappen eronder en zegt in welke map iets staat.
    await p.fill('#kl-search','december');assert.deepEqual(await rijen(),[A]);assert.match(await p.textContent('#kl-list'),/Identiteit/);assert.match(await p.textContent('#kl-count'),/1 van 2/);
    await p.fill('#kl-search','bestaat niet');assert.deepEqual(await rijen(),[]);assert.match(await p.textContent('#kl-empty'),/zoekopdracht/);await p.fill('#kl-search','');
    // 4. Naam wijzigen en favoriet via Meer; de ster in de rij; een lege plek valt terug naar boven.
    await meer(A,'Naam wijzigen');await p.fill('#modal-input','Mijn paspoort.pdf');await p.click('#modal-ok');assert.equal(await p.textContent('#kl-edit-title'),'Mijn paspoort.pdf');
    d=await doc(A);assert.equal(d.category,'Identiteit');assert.equal(d.note,'Paspoort geldig tot december');await dicht();
    await pad('all').click();await map('fav').click();await p.locator('#kl-list .kl-ster[data-id="'+A+'"]').click();assert.equal(await hier(),'all','Favorieten raakte leeg: terug naar boven');assert.equal(await map('fav').count(),0);
    const ster=p.locator('#kl-list .kl-ster[data-id="'+B+'"]');await ster.click();assert.equal(await ster.getAttribute('aria-pressed'),'true');assert.equal((await doc(B)).favorite,true);assert.ok((await ster.boundingBox()).width>=44);await ster.click();assert.equal((await doc(B)).favorite,false);
    // 5. Verplaatsen: bestaande map, nieuwe map in een map (Wonen/Polissen), geen map.
    await meer(B,'Verplaatsen naar map…');assert.equal(await p.locator('#sheet-acts button').first().getAttribute('translate'),'no','mapnamen in het menu zijn gegevens');await p.locator('#sheet-acts button',{hasText:'Identiteit'}).click();assert.equal((await doc(B)).category,'Identiteit');assert.equal(await p.inputValue('#kl-edit-category'),'Identiteit','de kaart volgt');await dicht();
    await meer(B,'Verplaatsen naar map…');await p.locator('#sheet-acts button',{hasText:'Nieuwe map…'}).click();assert.equal(await p.inputValue('#modal-input'),'Identiteit/','begint in de map waar je staat');await p.fill('#modal-input','  Wonen / Polissen ');await p.click('#modal-ok');assert.equal((await doc(B)).category,'Wonen/Polissen');await dicht();
    await pad('all').click();assert.deepEqual(await mappen(),['map:Identiteit=1','map:Wonen=1']);assert.deepEqual(await rijen(),[],'niets los op het bovenste niveau');
    await map('map:Wonen').click();assert.deepEqual(await mappen(),['map:Wonen/Polissen=1']);assert.deepEqual(await rijen(),[]);assert.match(await p.textContent('#kl-count'),/^$/);
    await map('map:Wonen/Polissen').click();assert.deepEqual(await rijen(),[B]);assert.deepEqual(await p.locator('#kl-pad button').allTextContents(),['Documenten','Wonen','Polissen']);
    // Toevoegen vanuit een map zet het nieuwe document in die map.
    await p.evaluate(()=>{const A2=Android,oud=A2.kluisAdd;A2.kluisAdd=function(){this._kl.docs.push({id:'k3abcdef',name:'Nieuw.pdf',mime:'application/pdf',size:5,t:Date.now()});setTimeout(()=>onKluisChanged('ok'),30);A2.kluisAdd=oud;};});
    await p.getByRole('button',{name:'＋ Document toevoegen',exact:true}).click();if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');await p.waitForFunction(()=>Android._kl.docs.length===3);
    await p.waitForFunction(()=>(Android._kl.docs.find(d=>d.id==='k3abcdef')||{}).category==='Wonen/Polissen');assert.equal((await rijen()).length,2);
    await p.evaluate(()=>{Android._kl.docs=Android._kl.docs.filter(d=>d.id!=='k3abcdef');klRender();});
    // 6. Een map hernoemen neemt alles eronder mee; een fout halverwege wordt gemeld; een lange naam past op een smal scherm.
    await p.evaluate(()=>goBack());assert.equal(await hier(),'map:Wonen');
    await p.click('#kl-maphernoem');await p.fill('#modal-input','Huis');await p.click('#modal-ok');assert.equal((await doc(B)).category,'Huis/Polissen');assert.equal(await hier(),'map:Huis');assert.equal(await p.evaluate(()=>Android._kl.mapHernoemd),1,'hernoemen in één keer (één brugaanroep voor de hele map)');
    await p.click('#kl-maphernoem');await p.fill('#modal-input','Huis/Kelder');await p.click('#modal-ok');assert.equal((await doc(B)).category,'Huis/Polissen','een map kan niet in zichzelf');
    await p.evaluate(()=>Android._kl.error='Opslaan lukt niet');await p.click('#kl-maphernoem');await p.fill('#modal-input','Anders');await p.click('#modal-ok');assert.equal((await doc(B)).category,'Huis/Polissen');assert.equal(await hier(),'map:Huis');await p.evaluate(()=>Android._kl.error='');
    await p.click('#kl-maphernoem');await p.fill('#modal-input','Verzekeringspolissenenanderebelangrijkepapieren');await p.click('#modal-ok');await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'lange mapnaam loopt niet buiten beeld');
    await pad('all').click();assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);await p.setViewportSize({width:390,height:844});
    await meer(B,'Verplaatsen naar map…');await p.locator('#sheet-acts button',{hasText:'Geen map'}).click();assert.equal((await doc(B)).category,'');await dicht();
    assert.equal((await doc(A)).note,'Paspoort geldig tot december');
    // 7. Eigenschappen: bewaren, terugzien in de lijst, zoeken, sorteren en "Verloopt binnenkort".
    await kaart(A);assert.equal(await p.textContent('#kl-edit-title'),'Mijn paspoort.pdf');
    await p.fill('#kl-edit-soort','Identiteitsbewijs');await p.fill('#kl-edit-partij','Gemeente <b>Rotterdam</b>');await p.fill('#kl-edit-datum','2021-12-01');await p.fill('#kl-edit-verloopt',await over(30));
    await p.fill('#kl-edit-bedrag','abc');await p.click('#kl-edit-save');assert.match(await p.textContent('#kl-edit-status'),/geldig bedrag/);assert.equal(await p.isVisible('#kl-edit'),true,'ongeldig bedrag: de kaart blijft open');
    await p.fill('#kl-edit-bedrag','77,5');await p.fill('#kl-edit-labels','reizen, Reizen , 2031,');await p.click('#kl-edit-save');assert.equal(await p.isVisible('#kl-lijstkaart'),true);
    d=await doc(A);assert.deepEqual(d.props,{soort:'Identiteitsbewijs',partij:'Gemeente <b>Rotterdam</b>',datum:'2021-12-01',verloopt:await over(30),bedrag:'77.50',labels:['reizen','2031']});assert.equal(d.note,'Paspoort geldig tot december');assert.equal(d.category,'Identiteit');
    const rijTekst=()=>p.locator('#kl-list .kl-rij',{has:p.locator('.trip[data-id="'+A+'"]')}).textContent();
    assert.match(await rijTekst(),/Identiteitsbewijs · Gemeente <b>Rotterdam<\/b>/);assert.match(await rijTekst(),/Verloopt op/);assert.match(await rijTekst(),/77,50/);assert.equal(await p.locator('#kl-list .kl-rij b b').count(),0,'partij blijft tekst');
    await pad('all').click();await p.fill('#kl-search','reizen');assert.deepEqual(await rijen(),[A],'zoeken vindt labels');await p.fill('#kl-search','gemeente');assert.deepEqual(await rijen(),[A],'zoeken vindt de partij');
    await p.fill('#kl-search','pdf');await p.selectOption('#kl-sort','verloopt');assert.deepEqual(await rijen(),[A,B],'met verloopdatum eerst');await p.selectOption('#kl-sort','name');assert.deepEqual(await rijen(),[A,B]);await p.selectOption('#kl-sort','recent');assert.deepEqual(await rijen(),[B,A]);await p.fill('#kl-search','');
    assert.equal(await map('exp').locator('.kl-n').textContent(),'1');await map('exp').click();assert.deepEqual(await rijen(),[A]);
    // De kaart vult de bewaarde waarden weer in; verlopen wordt gemeld; favoriet via Meer werkt het vinkje bij.
    await kaart(A);assert.equal(await p.inputValue('#kl-edit-bedrag'),'77,50');assert.equal(await p.inputValue('#kl-edit-labels'),'reizen, 2031');assert.equal(await p.inputValue('#kl-edit-datum'),'2021-12-01');
    await p.fill('#kl-edit-verloopt',await over(-3));await p.click('#kl-edit-save');assert.match(await rijTekst(),/Verlopen op/);
    await meer(A,'Als favoriet markeren');assert.equal(await p.isChecked('#kl-edit-favorite'),true);await p.uncheck('#kl-edit-favorite');await p.click('#kl-edit-save');assert.equal(!!(await doc(A)).favorite,false);
    // Terug op de kaart gaat eerst naar de lijst (zelfde map), en blijft in de kluis.
    await kaart(A);await p.evaluate(()=>goBack());assert.equal(await p.isVisible('#kl-lijstkaart'),true);assert.equal(await hier(),'map:Identiteit');assert.equal(await p.evaluate(()=>current),'kluis');assert.equal(await p.evaluate(()=>Android._kl.open),true);
    await kaart(A);for(const k of ['soort','partij','datum','verloopt','bedrag','labels'])await p.fill('#kl-edit-'+k,'');await p.click('#kl-edit-save');assert.equal('props' in (await doc(A)),false);await pad('all').click();assert.equal(await map('exp').count(),0);
    // 8. Mislukt opslaan behoudt het document én het formulier om opnieuw te proberen.
    await kaart(A);await p.fill('#kl-edit-category','Wonen');await p.fill('#kl-edit-note','Nieuw concept');
    await p.evaluate(()=>Android._kl.error='Opslaan lukt niet');await p.click('#kl-edit-save');
    assert.equal(await p.inputValue('#kl-edit-category'),'Wonen');assert.equal(await p.inputValue('#kl-edit-note'),'Nieuw concept');assert.equal((await doc(A)).category,'Identiteit');
    await p.evaluate(()=>Android._kl.error='');await p.click('#kl-edit-save');assert.equal((await doc(A)).category,'Wonen');
    // 9. Map en toelichting met HTML blijven veilig en zijn doorzoekbaar.
    await kaart(A);await p.fill('#kl-edit-category','<img src=x>');await p.fill('#kl-edit-note','<script>alert(1)</script>');await p.click('#kl-edit-save');
    await pad('all').click();assert.equal(await p.locator('#kl-lijstkaart img,#kl-lijstkaart script').count(),0);await p.fill('#kl-search','alert');assert.deepEqual(await rijen(),[A]);assert.equal(await p.locator('#kl-list img,#kl-list script').count(),0);await p.fill('#kl-search','');
    // 10. Bij verlopen sessie komt niets door en verdwijnen de gegevens uit de bediening.
    await kaart(A);await p.fill('#kl-edit-note','Nog niet bewaard');await p.evaluate(()=>Android._kl.open=false);await p.click('#kl-edit-save');
    await p.waitForFunction(()=>tsZichtbaar()&&ts.tool==='kluis');assert.equal(await p.isVisible('#kl-open'),false);assert.deepEqual(await rijen(),[]);assert.equal(await p.inputValue('#kl-edit-note'),'');assert.equal(await p.locator('#kl-pad button, #kl-mappen button').count(),0,'pad en mappen zijn weg');assert.equal(await p.evaluate(()=>'map' in $('#kl-pad').dataset||'diep' in $('#kl-pad').dataset||klTeken.fac.length>0),false,'ook de plek en de facturen zijn uit de pagina en het geheugen');
    await open();assert.equal((await doc(A)).note,'<script>alert(1)</script>');assert.equal(await hier(),'all','na op slot begint de kluis weer bovenaan');
    await p.evaluate(()=>{show('pdf');show('kluis');});assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'),true);await open();
    await map('map:<img src=x>').click();assert.deepEqual(await rijen(),[A]);assert.equal(await p.locator('#kl-mappen img, #kl-pad img').count(),0);
    // 11. Facturen uit het dossier staan als map in de verkenner (per leverancier) en doen mee met zoeken; tik = bekijken.
    await p.evaluate(()=>{const n=Date.now(),s=(sha,naam,lev,levNaam,nr,app)=>({sha:sha.repeat(64),naam,leverancier:lev,leverancierNaam:levNaam,opgehaald:n,meta:{nummer:nr,datum:'2026-10-28',bedrag:54.45},plekken:app?{app:{t:n}}:{}});
      Android._fa.dos={a:s('a','KPN <b>okt</b>.pdf','kpn','KPN Zakelijk','F-1001',true),b:s('b','Hosting.pdf','host','Hosting BV','H-22',true),c:s('c','Alleen op Drive.pdf','host','Hosting BV','H-23',false)};klKies('all');});
    assert.equal(await map('fac').locator('.kl-n').textContent(),'2','alleen wat de app zelf bewaart');
    await map('fac').click();assert.deepEqual(await mappen(),['fac:host=1','fac:kpn=1']);assert.equal(await p.isVisible('#kl-fachint'),true);
    await map('fac:kpn').click();assert.equal(await p.locator('#kl-list .kl-facrij').count(),1);assert.match(await p.textContent('#kl-list'),/F-1001/);assert.match(await p.textContent('#kl-list'),/54,45/);assert.equal(await p.locator('#kl-list b b').count(),0,'factuurnaam blijft tekst');assert.equal(await p.isVisible('#kl-sort'),false);
    await p.click('#kl-list .kl-facrij');assert.deepEqual(await p.evaluate(()=>Android._fa.calls.filter(c=>c[0]==='open').pop()),['open','kpn','KPN <b>okt</b>.pdf']);
    await p.evaluate(()=>goBack());assert.equal(await hier(),'fac');await p.evaluate(()=>goBack());assert.equal(await hier(),'all');
    await p.evaluate(()=>{window._fd=0;const o=Android.facturenDossier;Android.facturenDossier=function(){_fd++;return o.call(this);};});
    await p.fill('#kl-search','h-22');assert.equal(await p.evaluate(()=>_fd),0,'zoeken haalt het dossier niet telkens opnieuw op');assert.equal(await p.isVisible('#kl-mappen'),false,'bij zoeken alleen treffers');assert.equal(await p.locator('#kl-list .kl-facrij').count(),1);assert.deepEqual(await rijen(),[]);await p.fill('#kl-search','');
    // Facturen op slot (Extra ontgrendelen): de map staat er niet.
    await p.evaluate(()=>{window._tv=tsVerborgen;tsVerborgen=t=>t==='facturen'||_tv(t);klRender();});assert.equal(await map('fac').count(),0);await p.evaluate(()=>{tsVerborgen=_tv;Android._fa.dos={};klKies('all');});
    // 12. Verwijderen: annuleren houdt het document; daarna is de map weg en sta je weer bovenaan.
    await meer(A,'Verwijderen');await p.click('#modal-cancel');assert.equal(await p.evaluate(()=>Android._kl.docs.length),2);await dicht();
    await meer(A,'Verwijderen');await p.click('#modal-ok');assert.equal(await p.evaluate(()=>Android._kl.docs.length),1);assert.equal(await p.isVisible('#kl-lijstkaart'),true,'de kaart sluit');assert.equal(await hier(),'all');assert.equal(await map('map:<img src=x>').count(),0);
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.screenshot({path:__dirname+'/shots/documents-'+scheme+'.png',fullPage:true});
    assert.deepEqual(errors,[]);console.log(scheme,'documenten: verkenner, mappen, kaart, eigenschappen, facturen, gegevensbehoud en vergrendeling: errors []');await p.close();
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
