const {noteExtra}=require('./note-navigation');
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async()=>{
  const b=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await b.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));
    await p.evaluate(()=>{
      notesGet().push({id:'split',title:'Boodschappen',text:'1. Eieren\r\n• Melk\n[x] Kaas',items:[{id:'keep',text:'Bestaand',done:false},{id:'list',text:'Brood; boter; jam',done:true,extra:'behouden'}],updated:Date.now()});
      notesSaveNow();openNote('split');
    });
    const items=()=>p.evaluate(()=>noteById('split').items);
    const open=async()=>{await noteExtra(p,'edit');await p.click('#note-split-open');};
    // Notitietekst blijft standaard staan, nummering/opsommingen en vinkjes worden herkend.
    await open();assert.equal(await p.inputValue('#note-split-source'),'body');
    assert.equal(await p.isChecked('#note-split-keep'),true);
    assert.deepEqual(await p.locator('#note-split-preview li').allTextContents(),['☐ Eieren','☐ Melk','☑ Kaas']);
    await p.click('#note-split-apply');assert.equal((await items()).length,5);
    assert.match(await p.inputValue('#note-text'),/Eieren/);
    await p.click('#undo-b');assert.equal((await items()).length,2);
    // Optioneel de bron omzetten, en ongedaan maken herstelt de volledige bron.
    await open();await p.uncheck('#note-split-keep');await p.click('#note-split-apply');
    assert.equal(await p.inputValue('#note-text'),'');await p.click('#undo-b');assert.match(await p.inputValue('#note-text'),/Kaas/);
    // Een bestaand item wordt op dezelfde plek gesplitst met behoud van zijn afvinkstatus en overige velden.
    await p.fill('#note-add','Dit concept niet weggooien');await open();await p.selectOption('#note-split-source','item:list');
    assert.equal(await p.inputValue('#note-split-separator'),'semicolon');
    assert.equal(await p.isVisible('#note-split-keep-row'),false);
    await p.click('#note-split-apply');let list=await items();
    assert.deepEqual(list.map(x=>x.text),['Bestaand','Brood','boter','jam']);
    assert.ok(list.slice(1).every(x=>x.done&&x.extra==='behouden'));
    assert.equal(new Set(list.map(x=>x.id)).size,4);
    assert.equal(await p.inputValue('#note-add'),'Dit concept niet weggooien');
    // Een wijziging in de widget en een nieuw ander item blijven behouden bij ongedaan maken.
    await p.evaluate(()=>{const ext=JSON.parse(Android._notes);ext.find(n=>n.id==='split').items[0].done=true;ext.find(n=>n.id==='split').items.push({id:'outside',text:'Via widget',done:false});Android._notes=JSON.stringify(ext);Android._nv++;});
    await p.click('#undo-b');list=await items();
    assert.deepEqual(list.map(x=>x.id),['keep','list','outside']);assert.equal(list[0].done,true);
    // Plakken, scheiding kiezen, veilige preview; annuleren wijzigt niets.
    await p.fill('#note-add','');await open();await p.selectOption('#note-split-source','paste');
    await p.fill('#note-split-text','<img src=x onerror=alert(1)>, Brood,, Brood; Jam');
    await p.selectOption('#note-split-separator','both');
    assert.equal(await p.locator('#note-split-preview img').count(),0);
    assert.equal(await p.locator('#note-split-preview li').count(),4);
    await p.selectOption('#note-split-source','body');await p.selectOption('#note-split-source','paste');
    assert.match(await p.inputValue('#note-split-text'),/<img/,'geplakt concept blijft bij bronwissel');
    await p.getByRole('button',{name:'Annuleren',exact:true}).click();assert.equal((await items()).length,3);
    await open();await p.selectOption('#note-split-source','paste');await p.fill('#note-split-text','A, B, C');await p.selectOption('#note-split-separator','comma');
    await p.click('#note-split-apply');assert.equal((await items()).length,6);
    await p.click('#undo-b');assert.equal((await items()).length,3);
    assert.equal(await p.inputValue('#note-split-text'),'A, B, C','undo herstelt ook de geplakte tekst');
    // Afbreken bij gewijzigde bron: geen oude tekst terugschrijven en geen extra items maken.
    await p.selectOption('#note-split-source','item:list');
    await p.evaluate(()=>{const ext=JSON.parse(Android._notes);ext.find(n=>n.id==='split').items.find(i=>i.id==='list').text='Gewijzigd via widget';Android._notes=JSON.stringify(ext);Android._nv++;});
    await p.click('#note-split-apply');assert.equal((await items()).length,3);
    assert.match(await p.textContent('#note-split-status'),/intussen veranderd/);
    assert.equal(await p.isDisabled('#note-split-apply'),true);
    await p.getByRole('button',{name:'Annuleren',exact:true}).click();
    // Race: bronwijziging tussen controle en compare-and-save mag nooit verloren gaan.
    await p.evaluate(()=>{const n=noteById('split');n.items.find(i=>i.id==='list').text='A; B';touch(n);});
    await open();await p.selectOption('#note-split-source','item:list');
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){if(++calls===2){const a=JSON.parse(this._notes);a.find(n=>n.id==='split').items.find(i=>i.id==='list').text='Nieuw via widget';this._notes=JSON.stringify(a);this._nv++;}return window.realSave.call(this,j,v);};});
    await p.click('#note-split-apply');assert.equal((await items()).length,3);
    assert.equal((await items()).find(i=>i.id==='list').text,'Nieuw via widget');
    await p.evaluate(()=>{Android.notesSaveIf=window.realSave;});await p.getByRole('button',{name:'Annuleren',exact:true}).click();
    // Een fout bij de tweede save houdt de bron, het concept én de opgeslagen versie intact.
    await open();await p.selectOption('#note-split-source','paste');await p.fill('#note-split-text','Een\nTwee');
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){return ++calls===2?JSON.stringify({err:'Geen ruimte'}):window.realSave.call(this,j,v);};});
    await p.click('#note-split-apply');assert.equal((await items()).length,3);
    assert.equal(await p.evaluate(()=>JSON.parse(Android._notes).find(n=>n.id==='split').items.length),3);
    assert.equal(await p.inputValue('#note-split-text'),'Een\nTwee');
    await p.evaluate(()=>{Android.notesSaveIf=window.realSave;});
    await p.click('#note-split-apply');assert.equal((await items()).length,5);
    // Ook een undo-savefout mag geheugen en opslag niet uit elkaar laten lopen; opnieuw proberen kan.
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){return ++calls===2?JSON.stringify({err:'Geen ruimte'}):window.realSave.call(this,j,v);};});
    await p.click('#undo-b');assert.equal((await items()).length,5);
    assert.equal(await p.evaluate(()=>JSON.parse(Android._notes).find(n=>n.id==='split').items.length),5);
    await p.evaluate(()=>{Android.notesSaveIf=window.realSave;});await p.click('#undo-b');assert.equal((await items()).length,3);
    await p.getByRole('button',{name:'Annuleren',exact:true}).click();
    // Mislukt opslaan vóór de omzetting verandert de lijst en het concept niet.
    await open();await p.selectOption('#note-split-source','paste');await p.fill('#note-split-text','Een\nTwee');
    await p.evaluate(()=>{window.saveBeforeFailure=Android.notesSaveIf;Android.notesSaveIf=()=>JSON.stringify({err:'Geen ruimte'});});
    await p.click('#note-split-apply');assert.equal((await items()).length,3);
    assert.equal(await p.inputValue('#note-split-text'),'Een\nTwee');
    await p.evaluate(()=>{Android.notesSaveIf=window.saveBeforeFailure;});
    // Niet ongedaan maken als iemand een nieuw item intussen heeft gewijzigd.
    await p.click('#note-split-apply');assert.equal((await items()).length,5);
    await p.evaluate(()=>{const n=noteById('split');n.items[n.items.length-1].text='Verder bewerkt';touch(n);});
    await p.click('#undo-b');assert.equal((await items()).length,5);assert.equal((await items()).at(-1).text,'Verder bewerkt');
    // Randgevallen in de parser: geen stille inhoudsverwijdering of onbegrensde preview.
    const parsed=await p.evaluate(()=>({
      prefixes:noteSplitParse('- [x] Klaar\r☐ Nog doen\n2. Taak\n-2 kilo\n3.5 kg','lines',false),
      empty:noteSplitParse('\n• \n[x] \n','lines',false),
      many:noteSplitParse(Array(502).fill('Item').join('\n'),'lines',false),
      decimal:noteSplitParse('1,5 kg appels; 2,5 liter melk','semicolon',false)
    }));
    assert.deepEqual(parsed.prefixes.items,[{text:'Klaar',done:true},{text:'Nog doen',done:false},{text:'Taak',done:false},{text:'-2 kilo',done:false},{text:'3.5 kg',done:false}]);
    assert.equal(parsed.empty.items.length,0);assert.match(parsed.many.error,/500/);
    assert.deepEqual(parsed.decimal.items.map(i=>i.text),['1,5 kg appels','2,5 liter melk']);
    await open();await p.selectOption('#note-split-source','paste');await p.fill('#note-split-text','Melk, brood, kaas');await p.selectOption('#note-split-separator','comma');
    await p.screenshot({path:__dirname+'/shots/notes-split-'+scheme+'.png',fullPage:true});
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.evaluate(()=>{noteSplitClose();notesSaveNow();openNote('a1');});assert.equal(await p.isVisible('#note-split-panel'),false,'ander notitie heeft geen oude splitsdialoog');
    assert.deepEqual(errors,[]);console.log(scheme,'splitsen, bronbehoud, vinkjes, widgetconflict, ongedaan maken en opslagfouten: errors []');await p.close();
  }
  await b.close();
})().catch(e=>{console.error(e);process.exit(1);});
