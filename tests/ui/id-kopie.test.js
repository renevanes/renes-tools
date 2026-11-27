const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('path');
(async()=>{
 const browser=await chromium.launch();const errors=[];
 for(const colorScheme of ['light','dark'])for(const play of [false,true]){
  const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.addInitScript(play=>window.__testPlay=play,play);
  await p.goto('file://'+path.join(__dirname,'ui','index.html'));await p.waitForTimeout(300);
  await p.evaluate(()=>show('pdf'));await p.getByRole('button',{name:'ID-kopie afschermen',exact:true}).click();
  assert.equal(await p.evaluate(()=>current),'idcopy');assert.equal(await p.evaluate(()=>Android._idc.secure),true);
  await p.click('#id-pick');await (async()=>{if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');})();await p.waitForFunction(()=>idc.pages.length===2&&!idc.loading);
  assert.equal(await p.locator('#id-save').isDisabled(),true);
  assert.equal(await p.evaluate(()=>uiCanReload()),false,'actieve bron blokkeert automatische herlading');
  const ratio=await p.locator('#id-image').evaluate(e=>e.getBoundingClientRect().width/e.getBoundingClientRect().height);assert.ok(Math.abs(ratio-1.6)<.02,'verhouding bron blijft behouden');
  await p.fill('#id-recipient','Testontvanger');await p.fill('#id-purpose','Eenmalige controle');
  // Velden herkend (testbrug: burgerservicenummer en strook): benoemd in de lijst, zwart in het voorbeeld, uit te zetten
  await p.waitForFunction(()=>idc.pages[0].herken==='klaar');
  assert.deepEqual(await p.evaluate(()=>[...document.querySelectorAll('#id-velden .idveld b')].map(e=>e.textContent)),['Burgerservicenummer','Machineleesbare strook']);
  assert.match(await p.textContent('#id-velden-status'),/2 velden herkend en afgeschermd/);assert.match(await p.textContent('#id-velden .idveld small'),/Herkend\. Afschermen, tenzij/);
  assert.match(await p.textContent('#s-idcopy'),/Moet weg.*burgerservicenummer.*Kan weg.*pasfoto/s);
  assert.equal(await p.evaluate(()=>idCopyVlakken(idc.pages[0]).length),2);assert.match(await p.textContent('#id-masks'),/2 afschermvlakken/);
  await p.locator('#id-velden input[data-veld=strook]').uncheck();assert.equal(await p.evaluate(()=>idCopyVlakken(idc.pages[0]).length),1,'veld uitgezet: niet meer afgeschermd');
  await p.locator('#id-velden input[data-veld=strook]').check();
  await p.locator('#id-layer').scrollIntoViewIfNeeded();
  const box=await p.locator('#id-layer').boundingBox();await p.mouse.move(box.x+box.width*.1,box.y+box.height*.2);await p.mouse.down();await p.mouse.move(box.x+box.width*.6,box.y+box.height*.4,{steps:4});await p.mouse.up();
  assert.equal(await p.evaluate(()=>idc.pages[0].rects.length),1);
  await p.check('#id-checked');assert.equal(await p.locator('#id-save').isDisabled(),true,'achterkant moet ook worden bekeken');
  await p.click('#id-next');await p.waitForFunction(()=>idc.page===1&&!idc.loading);
  await p.click('#id-turn');await p.waitForFunction(()=>idc.pages[1].turn===1&&!idc.loading);
  await p.locator('#s-idcopy details').evaluate(e=>e.open=true);await p.fill('#id-x','20');await p.fill('#id-y','25');await p.fill('#id-w','30');await p.fill('#id-h','20');await p.click('#id-add');
  assert.deepEqual(await p.evaluate(()=>idc.pages[1].rects[0]),[.2,.25,.3,.2]);
  await p.check('#id-checked');assert.equal(await p.locator('#id-save').isDisabled(),false);
  await p.fill('#id-purpose','Nieuwe bestemming');assert.equal(await p.locator('#id-checked').isChecked(),false);assert.equal(await p.evaluate(()=>idc.pages[0].checked),false,'watermerk wijzigen trekt alle bevestigingen in');
  await p.check('#id-checked');await p.click('#id-prev');await p.waitForFunction(()=>idc.page===0&&!idc.loading);await p.check('#id-checked');
  const session=await p.evaluate(()=>idc.session);
  await p.click('#id-save');await p.waitForFunction(()=>!!idc.result);
  const result=await p.evaluate(()=>({plan:Android._idc.saved,pages:idc.pages.length,revision:idc.revision,src:$('#id-image').getAttribute('src')}));
  assert.equal(result.pages,0);assert.equal(result.revision,'');assert.equal(result.src,null);assert.equal(result.plan.pages.length,2);assert.equal(result.plan.pages[0].rects.length,3,'herkende velden en het eigen vlak samen bewaard');assert.equal(result.plan.pages[0].velden,undefined);assert.equal(result.plan.pages[1].turn,1);assert.match(result.plan.watermark,/Testontvanger/);
  await p.evaluate(session=>onIdCopyPicked({session,revision:'late',pages:4}),session);assert.equal(await p.evaluate(()=>idc.pages.length),0,'oude callback na export genegeerd');
  await p.evaluate(()=>show('pdf'));assert.equal(await p.evaluate(()=>Android._idc.secure),false);assert.equal(await p.inputValue('#id-recipient'),'');assert.equal(await p.inputValue('#id-x'),'10');assert.equal(await p.locator('#s-idcopy [data-ui-edited]').count(),0,'gewiste invoer blokkeert bijwerken niet');assert.equal(await p.evaluate(()=>{show('home');return uiCanReload();}),true,'na wissen kan de interface bijwerken');
  await p.evaluate(()=>show('idcopy'));await p.evaluate(session=>onIdCopyPicked({session,revision:'stale',pages:1}),session);assert.equal(await p.evaluate(()=>idc.pages.length),0,'oude callback na heropenen genegeerd');
  // Niets herkend en geen soort bekend: de app zegt wat er mist en biedt het soort document aan; herkennen mislukt = hetzelfde
  await p.evaluate(()=>{Android._idc.velden=[];window.__testIdPages=1;});
  await p.click('#id-pick');await (async()=>{if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');})();await p.waitForFunction(()=>idc.pages.length===1&&idc.pages[0].herken==='klaar');
  assert.match(await p.textContent('#id-velden-status'),/Geen velden herkend/);assert.equal(await p.locator('#id-velden .idveld-mist').count(),2,'burgerservicenummer en strook: niet gevonden, zelf kijken');
  await p.click('#id-voorstel');await p.locator('#sheet-acts').getByRole('button',{name:'ID-kaart, achterkant',exact:true}).click();
  assert.deepEqual(await p.evaluate(()=>idc.pages[0].velden.map(v=>v.veld+':'+v.bron)),['bsn:geschat','strook:geschat']);assert.equal(await p.locator('#id-velden .idveld-mist').count(),0);
  // Draaien zoekt opnieuw; een antwoord van voor het draaien telt niet meer
  await p.evaluate(()=>{Android._idc.velden=(page,turn)=>turn?[{veld:'pasfoto',x:.1,y:.1,w:.2,h:.3},{veld:'onbekend',x:0,y:0,w:1,h:1},{veld:'bsn',x:.9,y:.9,w:.5,h:.5}]:[];Android._idc.soort='';});
  await p.evaluate(()=>{idc.pages[0].velden=[];idCopyTurn();});await p.waitForFunction(()=>idc.pages[0].turn===1&&idc.pages[0].herken==='klaar'&&!idc.loading);
  assert.deepEqual(await p.evaluate(()=>idc.pages[0].velden.filter(v=>v.bron==='herkend').map(v=>v.veld)),['pasfoto'],'alleen bekende velden met een geldig vlak');
  await p.evaluate(()=>show('pdf'));
  await p.close();
 }
 console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);
})().catch(e=>{console.error(e);process.exit(1);});
