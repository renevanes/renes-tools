// Stamgegevens op de telefoon (390×844): korte schermen, één zone van schermen en de kluis blijft dicht erbuiten.
// Beginscherm (jij, Aandacht nodig, Persoon toevoegen, een rij per soort), lijsten per soort, een visitekaartje met blokken,
// één blok bewerken met een vaste opslaanbalk; Activiteiten en Opnieuw beginnen onder ⋯.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
const W=+process.env.UI_WACHT||1;
(async()=>{const browser=await chromium.launch(),errors=[];
for(const [colorScheme,en] of [['light',false],['dark',false],['light',true]]){
 const tag=colorScheme+(en?' (Engels)':''),L=(nl,eng)=>en?eng:nl;
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
 await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300*W);const T=require('./_stamtabs')(p);
 if(en){await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForFunction(()=>TAAL.on);}
 const past=async wat=>{const m=await p.evaluate(()=>({h:document.documentElement.scrollHeight,v:innerHeight}));assert.ok(m.h<=m.v+1,tag+': '+wat+' past zonder scrollen ('+m.h+' > '+m.v+')');};
 const inBeeld=s=>p.evaluate(s=>{const r=$(s).getBoundingClientRect();return r.height>0&&r.top>=0&&r.bottom<=innerHeight;},s);
 const minTik=s=>p.$$eval(s,b=>Math.min(...b.filter(x=>x.offsetParent).map(x=>Math.min(x.getBoundingClientRect().height,x.getBoundingClientRect().width))));
 const blokken=()=>p.$$eval('#cm-kaart .cmc-blok',s=>s.map(x=>x.dataset.blok));
 const opKaart=id=>p.waitForFunction(id=>current==='cmkaart'&&cmc.rec?.id===id&&!cm.pending.size&&!cm.mutating&&!cm.navigating,id);

 // Vergrendeld: eerst het centrale slotscherm (Stamgegevens heeft geen eigen slotkaart of ontgrendelknop)
 await p.evaluate(()=>show('masterdata'));await p.waitForTimeout(120*W);
 assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='masterdata'),true,tag+': het centrale slotscherm');
 assert.equal(await p.locator('#s-masterdata [onclick^="cmUnlock"]').count(),0,tag+': geen eigen ontgrendelknop');
 // Lukt openen daarna niet (de sleutel is weg): het kale beginscherm met de melding; Opnieuw beginnen kan dan nog (⋯),
 // op een eigen scherm; terug naar het beginscherm probeert het opnieuw
 const sleutelWeg='De beveiligingssleutel ontbreekt; bestaande stamgegevens worden niet vervangen';
 await p.evaluate(f=>{Android._cm.openFout=f;},sleutelWeg);
 await p.click('#ts-open');await p.waitForFunction(t=>current==='masterdata'&&!tsZichtbaar()&&!cm.unlocking&&$('#cm-status').textContent===t,L(sleutelWeg,'The security key is missing; existing master data will not be replaced'));
 await past('vergrendeld beginscherm');
 assert.equal(await p.textContent('#cm-status'),L(sleutelWeg,'The security key is missing; existing master data will not be replaced'));
 assert.equal(await p.isVisible('#cm-add'),false,tag+': geen Persoon toevoegen zolang het slot dicht is');
 await p.click('#cm-menu');await p.waitForSelector('#sheet.on');
 assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),[L('Opnieuw beginnen','Start over')],tag+': vergrendeld alleen opnieuw beginnen');
 await p.click('#sheet-acts button');await p.waitForFunction(()=>current==='cmwis');assert.equal(await p.isDisabled('#cm-reset'),true);await past('opnieuw beginnen');
 await p.evaluate(()=>{Android._cm.openFout='';goBack();});await p.waitForFunction(()=>current==='masterdata');

 // Leeg: Begin met jezelf, rijen met hun stand (terug van Opnieuw beginnen vraagt het slotscherm opnieuw)
 await T.slot();await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 assert.equal(await p.isVisible('#cm-ik .cmk-start'),true,tag+': Begin met jezelf');
 assert.deepEqual(await p.$$eval('#cm-ik .cmk-start button',b=>b.map(x=>x.id)),['cmk-self-contact','cmk-self-new'],'nog niemand om uit te kiezen');
 assert.equal(await p.textContent('#cm-stand-mensen'),L('Nog niemand','Nobody yet'));assert.equal(await p.textContent('#cm-stand-documenten'),L('Nog geen documenten','No documents yet'));
 assert.equal(await p.isVisible('#cm-aandacht'),false,'niets vraagt aandacht');
 await past('leeg beginscherm');

 // Mijzelf toevoegen: een kort blok (namen en geboortedatum); na opslaan je eigen visitekaartje
 await p.click('#cmk-self-new');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);
 assert.equal(await p.textContent('#cm-editor-title'),L('Mijzelf toevoegen','Add myself'));
 assert.deepEqual(await p.$$eval('#cm-fields [id^=cm-field-]',x=>x.map(e=>e.id.slice(9))),['roepnaam','voornamen','naam','geboortedatum'],'nieuwe persoon begint kort');
 assert.equal(await p.isVisible('#cm-label'),false,'naam in het overzicht vult zich vanzelf');
 await past('nieuwe persoon');assert.ok(await inBeeld('#cm-save'),tag+': opslaan in beeld');
 await p.fill('#cm-field-voornamen','Rene');await p.fill('#cm-field-naam','van Es');await p.fill('#cm-field-geboortedatum','1980-01-01');
 await p.click('#cm-save');await p.waitForFunction(()=>current==='cmkaart'&&cm.records.some(r=>r.type==='profiel')&&!cm.mutating&&!cm.navigating);
 const me=await p.evaluate(()=>cmkMe());assert.ok(me,'profiel wijst jou aan');await opKaart(me);
 assert.equal(await p.textContent('#cm-kaart-title'),'Rene van Es','naam in het overzicht vanzelf');
 assert.equal(await p.getAttribute('#cm-kaart-title','translate'),'no','titel is de naam (gegevens)');
 assert.equal(await p.textContent('#cm-kaart-status'),L('Welkom! Dit ben jij. Voeg nu je documenten en je mensen toe.','Welcome! This is you. Now add your documents and your people.'));
 assert.equal(await p.textContent('#cm-kaart .cmc-ik'),L('Dit ben jij','This is you'));
 assert.deepEqual(await blokken(),['persoon','geboorte','contact','documenten','handtekening','relaties'],'blokken op je eigen kaartje (handtekening: 02-handtekening.js)');
 assert.equal(await p.isVisible('#cmc-over-mij'),true,'Over mij staat bij de assistent');
 assert.ok((await p.locator('#cm-kaart [data-blok=geboorte]').innerText()).includes(await p.evaluate(()=>fmtDate('1980-01-01'))),'datum in de regio-notatie');

 // Eén blok bewerken: alleen die velden, met de kop van het blok; BSN afgeschermd
 await p.click('#cm-kaart [data-blok=geboorte] .cmc-pen');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.id&&cm.tab==='geboorte'&&!cm.pending.size);
 assert.equal(await p.textContent('#cm-blok-title'),L('Geboorte en nationaliteit','Birth and nationality'));
 assert.deepEqual(await p.$$eval('#cm-fields [id^=cm-field-]',x=>x.filter(e=>!e.closest('.cm-off')).map(e=>e.id.slice(9))),['geboortedatum','geboorteplaats','nationaliteit','bsn']);
 assert.equal(await p.getAttribute('#cm-field-bsn','type'),'password');assert.equal(await p.isVisible('#cm-secret-row'),true,'wel bij het BSN');
 await past('blok Geboorte');assert.ok(await inBeeld('#cm-save'));
 await p.fill('#cm-field-bsn','123456782');await p.fill('#cm-field-geboorteplaats','Rotterdam');await p.click('#cm-save');await opKaart(me);
 assert.equal(await p.textContent('#cm-kaart-status'),L('Opgeslagen.','Saved.'),'terug op het kaartje met bevestiging');
 const geb=()=>p.locator('#cm-kaart [data-blok=geboorte]').innerText();
 assert.match(await geb(),/••••782/);assert.doesNotMatch(await geb(),/123456782/,'BSN afgeschermd op het kaartje');
 await p.click('#cm-kaart [data-blok=geboorte] button[aria-pressed]');assert.match(await geb(),/123456782/,'Nummers tonen');
 await p.click('#cm-kaart [data-blok=geboorte] button[aria-pressed]');assert.doesNotMatch(await geb(),/123456782/);
 // Terug met een niet-bewaarde wijziging: eerst vragen
 await p.click('#cm-kaart [data-blok=persoon] .cmc-pen');await p.waitForFunction(()=>cm.tab==='persoon'&&!cm.pending.size);
 assert.equal(await p.isVisible('#cm-secret-row'),false,'geen BSN in dit blok');
 await p.fill('#cm-field-roepnaam','Reen');await p.evaluate(()=>goBack());await p.waitForSelector('#modal.on');assert.equal(await p.evaluate(()=>current),'cmedit','blijft staan tot je kiest');
 await p.evaluate(()=>closeModal(null));await p.waitForTimeout(60*W);assert.equal(await p.evaluate(()=>current+' '+cm.edit?.fields.roepnaam),'cmedit Reen','annuleren laat de wijziging staan');
 await p.evaluate(()=>{cm.dirty=false;goBack();});await opKaart(me);
 // Andere naam in het overzicht (⋯): een eigen blok met alleen die naam
 await p.click('#cm-kaart-menu');await p.waitForSelector('#sheet.on');
 assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),[L('Koppelen aan contact','Link to contact'),L('Andere naam in het overzicht','Different name in the overview'),L('Verwijderen','Delete')]);
 await p.click('#sheet-acts button:nth-child(2)');await p.waitForFunction(()=>cm.tab==='naam'&&!cm.pending.size);
 assert.equal(await p.isVisible('#cm-label'),true);assert.equal(await p.$$eval('#cm-fields [id^=cm-field-]',x=>x.filter(e=>!e.closest('.cm-off')).length),0);
 await p.evaluate(()=>{cm.dirty=false;goBack();});await opKaart(me);
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata');

 // Persoon toevoegen vanaf het beginscherm: eerst wie het voor jou is, dan zelf invullen; daarna het kaartje
 assert.equal(await p.textContent('#cm-add'),L('Persoon toevoegen','Add person'));
 await p.click('#cm-add');await p.waitForSelector('#sheet.on');assert.equal(await p.textContent('#sheet-title'),L('Wie is het voor jou?','Who is this to you?'));
 await p.click('#sheet-acts button:first-child');await p.waitForFunction(()=>$('#sheet-title').textContent.length&&$('#sheet').classList.contains('on')&&$('#sheet-acts').textContent.includes(t('Zelf invullen')));
 await p.click('#sheet-acts button:nth-child(2)');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='persoon'&&!cm.edit.id);
 assert.equal(await p.textContent('#cm-editor-title'),L('Mijn kind toevoegen','Add my child'));
 await p.fill('#cm-field-voornamen','Anna');await p.fill('#cm-field-naam','van Es');await p.click('#cm-save');
 await p.waitForFunction(()=>current==='cmkaart'&&cmc.rec?.label==='Anna van Es'&&!cm.mutating&&!cm.navigating);const anna=await p.evaluate(()=>cmc.rec.id);await opKaart(anna);
 assert.match(await p.textContent('#cm-kaart-status'),en?/Anna van Es has been added as your child/:/Anna van Es is toegevoegd als je kind/);
 assert.equal(await p.textContent('#cmc-wie'),L('Mijn kind','My child'),'wat Anna voor jou is, bovenaan');
 assert.deepEqual(await blokken(),['persoon','geboorte','contact','documenten','relaties','over']);
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata');

 // Wat er verder is: een document, een adres, een organisatie
 const pas=await T.maak('identiteit','Paspoort Rene',{persoon:me,documentsoort:'paspoort',document:'DOC123',geldig:'2034-01-01'});
 const huis=await T.maak('adres','Huis',{adres:'Coolsingel 1',plaats:'Rotterdam'}),gem=await T.maak('organisatie','Gemeente',{naam:'Gemeente Rotterdam'});
 await p.waitForFunction(()=>cm.recordsReady&&current==='masterdata');
 assert.deepEqual(await p.$$eval('#cm-rows .strow small',x=>x.map(e=>e.textContent)).then(x=>x.slice(0,4)),en?['One person','One document','One address','One organisation']:['Eén persoon','Eén document','Eén adres','Eén organisatie']);
 assert.match(await p.textContent('#cm-ik'),en?/This is you · one document/:/Dit ben jij · één document/);
 await past('beginscherm');
 assert.ok(await p.evaluate(()=>$('#cm-ik').getBoundingClientRect().top<innerHeight/3),tag+': jij bovenaan');
 assert.ok(await minTik('#cm-ik .strow,#cm-add,#cm-rows .strow,#cm-menu,#cm-lock-btn')>=44,tag+': tikvlakken minstens 44 px');

 // Lijst Mijn mensen, gegroepeerd naar wat ze voor jou zijn; heen en terug over kaartjes
 await p.click('#cm-row-mensen');await p.waitForFunction(()=>current==='cmlijst');
 assert.equal(await p.textContent('#cm-lijst-title'),L('Mijn mensen','My people'));
 assert.deepEqual(await p.$$eval('#cm-lijst > *',x=>x.map(e=>e.tagName==='H3'?'# '+e.textContent:e.querySelector('b').textContent+' | '+e.querySelector('small').textContent)),[L('# Gezin','# Immediate family'),L('Anna van Es | Mijn kind','Anna van Es | My child')]);
 assert.equal(await p.getAttribute('#cm-lijst-add','class'),'big tonal','één gevulde knop per scherm');await past('lijst Mijn mensen');
 await p.locator('#cm-lijst .strow').click();await opKaart(anna);
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='cmlijst'&&cmLijstNu()==='mensen');
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata');
 // Documenten: het documentkaartje met afgeschermd nummer en de kaartfoto's
 await p.click('#cm-row-documenten');await p.waitForFunction(()=>current==='cmlijst'&&cmLijstNu()==='documenten');
 assert.equal(await p.textContent('#cm-lijst-add'),L('Document toevoegen','Add document'));
 await p.locator('#cm-lijst .strow',{hasText:'Paspoort Rene'}).click();await opKaart(pas);
 assert.deepEqual(await blokken(),['document','afgifte','fotos']);
 assert.match(await p.locator('#cm-kaart [data-blok=document]').innerText(),/••••123/);assert.doesNotMatch(await p.textContent('#cm-kaart'),/DOC123/);
 assert.equal((await p.textContent('#cmc-foto-front')).trim(),L('Voorkant toevoegen','Add front'));
 // Elk blok van elk soort past op het scherm, met de opslaanknop in beeld
 for(const id of [me,anna,pas,huis,gem]){
  const tabs=await p.evaluate(id=>{const r=cm.records.find(x=>x.id===id);return cmTabs({...r,fields:{}}).map(b=>b[0]).filter(b=>b!=='fotos');},id);
  for(const b of tabs){await T.blok(id,b);await p.waitForTimeout(30*W);await past(id.slice(0,4)+' → '+b);assert.ok(await inBeeld('#cm-save'),tag+': opslaan in beeld bij '+b);}
  await p.evaluate(()=>cmCancelEdit());await p.waitForFunction(()=>current!=='cmedit'&&!cm.navigating);
 }
 await p.evaluate(()=>show('masterdata'));await p.waitForFunction(()=>current==='masterdata'&&cmOpen());

 // Zoeken: één lijst met alles wat past
 await p.fill('#cm-search','ren');assert.equal(await p.isVisible('#cm-hub'),false);
 assert.deepEqual(await p.$$eval('#cm-list .strow b',b=>b.map(x=>x.textContent)),['Paspoort Rene','Rene van Es']);
 await p.fill('#cm-search','');assert.equal(await p.isVisible('#cm-hub'),true);

 // Binnen de zone blijft het slot open; ontgrendeld zijn Activiteiten via ⋯
 await p.click('#cm-menu');await p.waitForSelector('#sheet.on');await p.click('#sheet-acts button:first-child');await p.waitForFunction(()=>current==='cmlog'&&cm.events.length>0);
 assert.equal(await p.evaluate(()=>cmOpen()),true,'slot blijft open binnen Stamgegevens');
 assert.equal(await p.evaluate(()=>/123456782|DOC123/.test($('#cm-audit').textContent)),false,'geen nummers in het logboek');
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata'&&cmOpen());
 // Vergrendelen tijdens bewerken (met de vraag "Bewerking sluiten?" open): vraag weg, terug naar het beginscherm, niets meer in de schermen
 await T.kaart(me);await p.click('#cm-kaart [data-blok=persoon] .cmc-pen');await p.waitForFunction(()=>cm.tab==='persoon'&&!cm.pending.size);
 await p.fill('#cm-field-roepnaam','Reen');await p.evaluate(()=>goBack());await p.waitForSelector('#modal.on');
 await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>current==='masterdata'&&!cm.token);
 assert.equal(await p.evaluate(()=>$('#modal').classList.contains('on')),false,'vraag gesloten bij vergrendelen');
 assert.equal(await p.textContent('#cm-status'),L('Stamgegevens zijn vergrendeld. Niet-bewaarde gegevens zijn gewist.','Master data is locked. Unsaved data has been cleared.'));
 for(const s of ['#s-masterdata','#s-cmedit','#s-cmlog','#s-cmlijst','#s-cmkaart'])assert.equal(await p.evaluate(s=>/Rene|Anna|Paspoort Rene|123456782|Gemeente|Coolsingel/.test($(s).innerHTML),s),false,tag+': geen gegevens meer in '+s);
 // Verwijderen vanuit het bewerken (potlood, dan ⋯): terug naar de lijst, niet naar het kaartje van wat weg is
 await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 const weg=await T.maak('organisatie','Weg BV',{naam:'Weg BV'});
 await p.click('#cm-row-organisaties');await p.waitForFunction(()=>current==='cmlijst'&&!cm.navigating);
 await T.kaart(weg);await T.blok(weg,'gegevens');
 await p.click('#cm-edit-menu');await p.waitForSelector('#sheet.on');await p.click('#sheet-acts button:first-child');await p.waitForSelector('#modal.on');await p.click('#modal-ok');
 await p.waitForFunction(()=>current==='cmlijst'&&!cm.navigating&&!cm.mutating&&!cm.pending.size);
 assert.equal(await p.textContent('#cm-lijst-status'),L('Stamgegeven verwijderd.','Master data deleted.'),tag+': verwijderd, terug in de lijst');
 assert.equal(await p.evaluate(()=>cm.nav.some(x=>x.scr==='cmkaart')),false,tag+': geen kaartje van wat weg is om naar terug te gaan');
 await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>current==='masterdata'&&!cm.token);
 // Een subscherm openen zonder ontgrendelen kan niet
 for(const s of ['cmedit','cmlijst','cmkaart']){await p.evaluate(s=>show(s),s);await p.waitForFunction(()=>current==='masterdata');}
 // De zone verlaten vergrendelt; Overal zoeken neemt de schermen niet op
 await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);await p.click('#cm-row-adressen');await p.waitForFunction(()=>current==='cmlijst');
 await p.evaluate(()=>show('home'));await p.waitForFunction(()=>!cm.token);
 assert.equal(await p.evaluate(()=>{gsIndex=null;gsBuildIndex();return gsIndex.some(x=>CM_SCR.includes(x.scr));}),false,'niet in Overal zoeken');
 assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|identity|profiel|relati/.test(k))),false);
 await p.close();
}
console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
