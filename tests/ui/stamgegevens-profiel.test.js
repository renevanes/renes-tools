const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const browser=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));await p.goto('file://'+path.join(__dirname,'ui/index.html'));const T=require('./_stamtabs')(p);
 await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 // Een nieuwe persoon (alleen het korte blok); tijdens het opslaan opent er niets anders
 const person=async(roepnaam,naam,traag)=>{
  await p.evaluate(()=>cmNew());await T.fill('#cm-field-roepnaam',roepnaam);await T.fill('#cm-field-naam',naam);
  if(traag)await p.evaluate(()=>Android._cm.replyDelay=400);await p.click('#cm-save');
  if(traag){await p.evaluate(()=>cmiNew('identiteit'));assert.equal(await p.evaluate(()=>cm.edit.type),'persoon');assert.equal(await p.isDisabled('#cm-save'),true);}
  const label=roepnaam+' '+naam;await p.waitForFunction(label=>!cm.mutating&&!cm.edit&&cm.records.some(r=>r.label===label),label);await p.evaluate(()=>Android._cm.replyDelay=10);
  return p.evaluate(label=>cm.records.find(r=>r.label===label).id,label);
 };
 const me=await person('Rene','Instellingen',true),kid=await person('Zoë','<b>Kind</b>');
 // Jezelf aanwijzen (Ik sta er al tussen) en een BSN invullen; de assistent kent jou pas als je het aanzet
 await p.evaluate(()=>{cmGo({scr:'masterdata'});show('masterdata');});await p.click('#cmk-self-pick');await p.waitForSelector('#sheet.on');
 await p.getByRole('button',{name:'Rene Instellingen',exact:true}).click();await p.waitForFunction(id=>cmkMe()===id&&!cm.mutating,me);
 const prof=await p.evaluate(()=>cm.records.find(r=>r.type==='profiel'));assert.equal(prof.person,me);assert.notEqual(prof.delen,'ja','delen staat niet vanzelf aan');
 await T.blok(me,'geboorte');await T.fill('#cm-field-bsn','123456782');await T.opslaan();
 await p.evaluate(()=>cmaiOpen());await p.waitForFunction(()=>current==='cmai'&&!$('#cm-ai-delen').disabled);await p.click('#cm-ai-delen');await p.waitForFunction(()=>appIdentity.self?.callname==='Rene');
 // Wie is Zoë voor jou? Mijn kind (met status); daarna kent de assistent haar, zonder BSN
 await T.kaart(kid);assert.equal(await p.textContent('#cmc-wie'),'Wie is Zoë voor jou?');await p.click('#cmc-wie');await p.waitForSelector('#sheet.on');
 await p.getByRole('button',{name:'Mijn kind',exact:true}).click();await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Status');
 await p.getByRole('button',{name:'Ja, ik heb ouderlijk gezag',exact:true}).click();await p.waitForFunction(()=>appIdentity.people?.length===1);
 await p.waitForFunction(()=>/Mijn kind/.test($('#cmc-wie')?.textContent||'')&&!cm.navigating);
 await p.evaluate(()=>cmaiOpen());await p.waitForFunction(()=>current==='cmai');assert.equal(await p.locator('#cmi-shared b b').count(),0);assert.equal(await p.evaluate(()=>JSON.stringify(appIdentity).includes('123456782')),false);
 // Een document bij het kind (zelf invullen)
 await T.kaart(kid);await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');await p.getByRole('button',{name:'Identiteitskaart',exact:true}).click();
 await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Identiteitskaart');await p.getByRole('button',{name:'Zelf invullen',exact:true}).click();
 await p.waitForFunction(()=>cm.edit?.type==='identiteit'&&!cm.edit.id);assert.equal(await p.inputValue('#cm-field-documentsoort'),'identiteitskaart');
 await T.fill('#cm-field-document','DOC123456');await T.fill('#cm-field-geldig','2030-06-30');await p.click('#cm-save');
 await p.waitForFunction(()=>!cm.edit&&current==='cmkaart'&&cm.records.some(r=>r.type==='identiteit')&&!cm.navigating);const doc=await p.evaluate(()=>cm.records.find(r=>r.type==='identiteit').id);
 const openDoc=()=>T.blok(doc,'fotos');await openDoc();
 // Voorzijde: te lang weg uit de kiezer (kluis op slot, ontgrendelen); achterzijde: meteen terug, blijft open. Daarna het documentkaartje.
 await p.evaluate(()=>Android._cm.slowPick=true);await p.evaluate(()=>cmiPhotoPick('front'));await p.waitForFunction(()=>!cm.token&&!$('#cmi-photo-pending').hidden);assert.equal(await p.getAttribute('#cmi-front-image','src'),null);
 await p.locator('#cmi-photo-pending button').first().click();await p.waitForFunction(doc=>cmOpen()&&current==='cmkaart'&&cmc.rec?.id===doc&&!!cmc.rec.images?.front,doc);
 assert.equal(await p.textContent('#cm-kaart-status'),'Kaartfoto opgeslagen.');
 await p.evaluate(()=>Android._cm.slowPick=false);await p.click('#cmc-foto-back');await p.waitForFunction(doc=>cmOpen()&&current==='cmkaart'&&cmc.rec?.id===doc&&!!cmc.rec.images?.back,doc);assert.equal(await p.isVisible('#cmi-photo-pending'),false,'achterzijde zonder opnieuw ontgrendelen');
 assert.equal(await p.textContent('#cm-kaart-status'),'Kaartfoto opgeslagen.','terug op het kaartje met bevestiging');
 // Op het kaartje meteen getoond; tikken opent de kijker met zoomen, terug sluit hem en op slot ook
 await p.waitForFunction(()=>!!$('#cmc-foto-front img').getAttribute('src')&&!!$('#cmc-foto-back img').getAttribute('src'));
 assert.equal(await p.evaluate(()=>Android._cm.events.filter(e=>e.action==='photo_view').length>0),true,'bekijken staat in het logboek');
 await p.click('#cmc-foto-front');await p.waitForFunction(()=>$('#er-image').classList.contains('on'));assert.equal(await p.isVisible('#er-image-picture'),true,'kijker over het kaartje');
 assert.equal(await p.getAttribute('#er-image-picture','src'),await p.getAttribute('#cmc-foto-front img','src'));
 await p.click('#er-image-more');assert.match(await p.getAttribute('#er-image-picture','style'),/scale\(1\.5\)/,'inzoomen');
 await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>$('#er-image').classList.contains('on')),false);assert.equal(await p.evaluate(()=>current),'cmkaart','terug sluit alleen de kijker');
 const lezen=await p.evaluate(()=>Android._cm.events.filter(e=>e.action==='photo_view').length);await p.click('#cmc-foto-back');await p.waitForFunction(()=>$('#er-image').classList.contains('on'));
 assert.equal(await p.evaluate(()=>Android._cm.events.filter(e=>e.action==='photo_view').length),lezen,'vergroten leest de foto niet opnieuw');await p.evaluate(()=>erImageClose());
 await T.blok(doc,'afgifte');await T.fill('#cm-field-afgegevenDoor','Testgemeente');await T.opslaan();await openDoc();
 assert.equal(await p.evaluate(()=>cm.edit.images.front&&cm.edit.images.back),true,'tekst opslaan behoudt beide kaartzijden');assert.equal(await p.evaluate(()=>JSON.stringify(cm.edit).includes('base64')),false,'geen grote of leesbare kaartdata in de editorstructuur');
 await p.waitForFunction(()=>!!$('#cmi-front-image').getAttribute('src')&&!!$('#cmi-back-image').getAttribute('src'));await T.click('#cmi-front-image');await p.waitForFunction(()=>$('#er-image').classList.contains('on'));
 assert.equal(await p.getAttribute('#er-image-picture','src'),await p.getAttribute('#cmi-front-image','src'),'Foto\'s-tab: meteen getoond, tikken vergroot');
 // Leesantwoord dat na vergrendeling aankomt mag een kaartfoto niet opnieuw tonen.
 await p.evaluate(()=>{Android._cm.replyDelay=100;cmiPhotoShow();cmLock();});await p.waitForFunction(()=>!cm.token);await p.waitForTimeout(140);assert.equal(await p.getAttribute('#cmi-front-image','src'),null);assert.equal(await p.evaluate(()=>$('#er-image').classList.contains('on')),false,'op slot sluit de kijker');await p.evaluate(()=>Android._cm.replyDelay=10);
 await p.evaluate(()=>show('home'));assert.equal(await p.locator('#cmi-home-name').innerText(),'Hallo Rene');await p.evaluate(()=>show('aisetpers'));assert.equal(await p.locator('#cmi-ai-summary').innerText(),'De assistent kent jou (Rene) en één persoon.');
 // De echte bronkeuze bepaalt welke personen de formulierrelatie mogen leveren.
 await p.evaluate(()=>{show('formulieren');fmStart();fm.step=fmSecties().findIndex(s=>s.fields.some(f=>f.id==='kind-naam'));fmRender();fmHelpToon(true);});
 const choose=async(role,label)=>{await p.selectOption('#pff-hulp-rol',role);await p.click('#pff-hulp-kies');if(await p.isVisible('#cm-pick-unlock'))await p.click('#cm-pick-unlock');await p.getByRole('button',{name:label,exact:true}).click();await p.waitForSelector('#cm-pick-fields input[data-field]');await p.click('#cm-apply');await p.waitForFunction(()=>!cm.picker&&cm.privateReady);};
 await choose('kind','Zoë <b>Kind</b>');await p.evaluate(()=>{fmh.sources.find(s=>s.role==='kind').label='Kind '+ 'a'.repeat(110);fm.values.alleen='nee';fm.step=fmSecties().findIndex(s=>s.fields.some(f=>f.id==='begeleider1-naam'));fmRender();});await choose('begeleider1','Rene Instellingen');await p.waitForFunction(()=>fmh.sources.some(s=>s.type==='relatiecontext'));await p.click('#pff-hulp-vast');await p.waitForFunction(()=>fmh.rows.some(r=>r.id==='begeleider1-relatie'));assert.equal(await p.evaluate(()=>fmh.rows.find(r=>r.id==='begeleider1-relatie').value),'ouder');assert.equal(await p.inputValue('#pff-v-begeleider1-relatie'),'','voorstel is geen automatische invoer');
 await p.click('#pff-hulp-ai');await p.waitForFunction(()=>!fmh.busy&&fmh.rows.some(r=>r.id==='begeleider1-relatie'));assert.equal(await p.evaluate(()=>Android._pdf.formAi.input.sources.some(s=>s.type==='relatiecontext'&&s.fields.relatie==='ouder')),true);assert.equal(await p.evaluate(()=>JSON.stringify(Android._pdf.formAi.input).includes('base64')),false);assert.equal(await p.evaluate(()=>Android._pdf.formAi.input.sources.every(s=>s.label.length<=120)),true,'lange geldige persoonslabels houden een geldige AI-payload');
 // Navigeren tijdens het ophalen van relaties laat geen vastgelopen invulhulp achter.
 await p.evaluate(()=>{fmh.sources=fmh.sources.filter(s=>s.type!=='relatiecontext');cmiFormRelations.asked.clear();Android._cm.replyDelay=200;fmHelpRender();show('home');});await p.waitForTimeout(250);await p.evaluate(()=>show('formulieren'));await p.waitForFunction(()=>!cmiFormRelations.pending.size&&fmh.sources.some(s=>s.type==='relatiecontext'));assert.equal(await p.isDisabled('#pff-hulp-vast'),false);await p.evaluate(()=>Android._cm.replyDelay=10);
 // Intrekken werkt ook bij een laat contextsignaal van een eerdere worker.
 await p.evaluate(()=>show('masterdata'));if(await p.isVisible('#cm-unlock'))await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);await p.evaluate(()=>cmaiOpen());await p.waitForFunction(()=>current==='cmai'&&!$('#cm-ai-delen').disabled);await p.click('#cm-ai-delen');await p.waitForFunction(()=>!appIdentity.self);await p.evaluate(()=>onAppIdentity({self:{name:'VEROUDERD'}}));assert.equal(await p.evaluate(()=>appIdentity.self),undefined);assert.ok(await p.isHidden('#cmi-home'));
 assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|identity|profiel/.test(k))),false);
 await p.close();
}
console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
