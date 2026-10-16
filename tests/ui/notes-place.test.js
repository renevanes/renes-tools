const {noteChildren}=require('./note-navigation');
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});
    const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));
    const reset=()=>p.evaluate(()=>{
      closeSheet();notes=notesGet().filter(n=>!n.id.startsWith('plaats-'));
      const item=(id,text,parentId)=>({id,text,done:id==='i2',...(parentId?{parentId}:{})});
      notes.push({id:'plaats-a',title:'Weekend',text:'Planning',updated:10,items:[item('i1','Reis'),item('i2','Ticket','i1'),item('i3','Hotel')],repeat:{every:'week',n:6,last:10}},
        {id:'plaats-b',title:'Boodschappen',text:'Voor zondag',updated:20,items:[item('b1','Brood')],extra:'behouden'},
        {id:'plaats-c',title:'Werk',text:'',updated:30,items:[]},{id:'plaats-d',title:'Jaarplanning',text:'',updated:40,items:[]});
      notesSaveNow();openNote('plaats-a');
    });
    const note=id=>p.evaluate(id=>noteById(id),'plaats-'+id);
    const choose=id=>p.locator('#note-move-choices button[data-target="'+id+'"]').click();
    const apply=()=>p.click('#note-move-apply');
    const cancel=()=>p.locator('#sheet button.cancel').click();
    await reset();
    assert.equal(await p.textContent('#note-progress'),'1 van 3 items klaar');
    // Een complete bestaande lijst hieronder hangen, zonder inhoud, ids of instellingen te veranderen.
    const original=await note('b');await noteChildren(p);await p.click('#note-list-attach');await choose('plaats-b');
    assert.equal(await p.evaluate(()=>document.activeElement.dataset.target),'plaats-b','focus blijft op gekozen bestemming');
    assert.match(await p.textContent('#note-move-status'),/Boodschappen komt onder Weekend/);await apply();
    assert.equal((await note('b')).parentNoteId,'plaats-a');
    assert.deepEqual((await note('b')).items,original.items);assert.equal((await note('b')).extra,'behouden');
    assert.equal(await p.locator('#note-child-list [data-note-id="plaats-b"]').count(),1);
    await p.click('#undo-b');assert.equal((await note('b')).parentNoteId,undefined);
    // Andere richting: deze lijst onder een bestaande lijst, weer losmaken en undo.
    await noteChildren(p);await p.click('#note-list-move');await p.fill('#note-move-search','Werk');await choose('plaats-c');await apply();
    assert.equal((await note('a')).parentNoteId,'plaats-c');assert.match(await p.textContent('#note-list-location'),/Werk/);
    assert.deepEqual((await note('a')).repeat,{every:'week',n:6,last:10});
    await noteChildren(p);await p.click('#note-list-move');await choose('');await apply();assert.equal((await note('a')).parentNoteId,undefined);
    await p.click('#undo-b');assert.equal((await note('a')).parentNoteId,'plaats-c');
    // Ouder en kind omwisselen met behoud van andere sublijsten en de grootouder.
    await reset();await p.evaluate(()=>{noteById('plaats-a').parentNoteId='plaats-d';noteById('plaats-b').parentNoteId='plaats-a';noteById('plaats-c').parentNoteId='plaats-a';notesSaveNow();openNote('plaats-b');});
    await noteChildren(p);await p.click('#note-list-swap');await p.click('#modal-ok');
    assert.equal((await note('a')).parentNoteId,'plaats-b');assert.equal((await note('b')).parentNoteId,'plaats-d');assert.equal((await note('c')).parentNoteId,'plaats-a');
    await p.click('#undo-b');assert.equal((await note('a')).parentNoteId,'plaats-d');assert.equal((await note('b')).parentNoteId,'plaats-a');
    // Een lijst kan niet onder zichzelf, een kind of een kleinkind terechtkomen.
    await p.evaluate(()=>openNote('plaats-a'));await noteChildren(p);await p.click('#note-list-move');
    assert.equal(await p.locator('#note-move-choices [data-target="plaats-a"],#note-move-choices [data-target="plaats-b"]').count(),0);await cancel();
    await noteChildren(p);await p.click('#note-list-attach');assert.equal(await p.locator('#note-move-choices [data-target="plaats-d"]').count(),0);await cancel();
    // Overzicht en zoekresultaat tonen de relatie en openen de oorspronkelijke notitie.
    await p.evaluate(()=>show('notes'));await p.fill('#notes-search','Boodschappen');
    assert.match(await p.textContent('#notes-list [data-note-id="plaats-b"]'),/Onder Weekend/);
    await p.click('#notes-list [data-note-id="plaats-b"]');assert.equal(await p.evaluate(()=>noteCur),'plaats-b');
    await p.getByRole('button',{name:'Bovenliggende lijst: Weekend',exact:true}).click();assert.equal(await p.evaluate(()=>noteCur),'plaats-a');
    // Verwijderen van een ouder bewaart de kindlijsten onder de grootouder, ook na opnieuw inlezen.
    await p.click('#s-note button[aria-label="Verwijderen"]');assert.match(await p.textContent('#modal-text'),/sublijsten blijven/);await p.click('#modal-ok');
    assert.equal(await note('a'),undefined);assert.equal((await note('b')).parentNoteId,'plaats-d');assert.equal((await note('c')).parentNoteId,'plaats-d');
    await p.evaluate(()=>{notes=null;notesGet();});assert.deepEqual((await note('b')).items,original.items);
    // Een lege naamloze lijst met sublijsten blijft bestaan.
    await reset();await p.evaluate(()=>{noteById('plaats-c').title='';noteById('plaats-b').parentNoteId='plaats-c';notesSaveNow();openNote('plaats-c');show('notes');});assert.ok(await note('c'));
    // Nieuwe sublijst krijgt meteen de juiste plaats; een leeg concept verdwijnt bij teruggaan.
    await p.evaluate(()=>openNote('plaats-a'));await noteChildren(p);await p.getByRole('button',{name:'+ Nieuwe sublijst',exact:true}).click();
    assert.equal(await p.evaluate(()=>noteById(noteCur).parentNoteId),'plaats-a');await p.fill('#note-title','Inpakken');await p.click('#s-note .back');
    assert.ok(await p.evaluate(()=>notesGet().some(n=>n.title==='Inpakken'&&n.parentNoteId==='plaats-a')));
    // Items onder een willekeurig item hangen, inclusief hun eigen subitems.
    await p.evaluate(()=>openNote('plaats-a'));await p.click('#note-item-move');await p.selectOption('#note-move-item','i1');
    assert.equal(await p.locator('#note-move-choices [data-target="i2"]').count(),0);await choose('i3');await apply();
    assert.equal((await note('a')).items.find(i=>i.id==='i1').parentId,'i3');assert.equal((await note('a')).items.find(i=>i.id==='i2').parentId,'i1');
    await p.click('#undo-b');assert.equal((await note('a')).items.find(i=>i.id==='i1').parentId,undefined);
    await p.click('[data-id="i2"] .item-menu');await p.getByRole('button',{name:'Verplaatsen naar…',exact:true}).click();
    await choose('');await apply();assert.equal((await note('a')).items.find(i=>i.id==='i2').parentId,undefined);
    // Annuleren, Android-terug en Escape veranderen niets en herstellen focus; Tab blijft in de dialoog.
    await noteChildren(p);await p.click('#note-list-move');await choose('plaats-c');await p.keyboard.press('Escape');assert.equal((await note('a')).parentNoteId,undefined);
    assert.equal(await p.evaluate(()=>document.activeElement.id),'note-list-move');
    await noteChildren(p);await p.click('#note-list-move');await p.locator('#sheet button.cancel').focus();await p.keyboard.press('Tab');assert.equal(await p.evaluate(()=>document.activeElement.id),'note-move-search');
    await p.evaluate(()=>goBack());assert.equal(await p.isVisible('#sheet'),false);
    // Wijziging tijdens de compare-and-save: vinkje blijft behouden, selectie verhuist maar één keer.
    await reset();await noteChildren(p);await p.click('#note-list-move');await choose('plaats-c');
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){if(++calls===2){const all=JSON.parse(this._notes);all.find(n=>n.id==='plaats-a').items[0].done=true;this._notes=JSON.stringify(all);this._nv++;}return realSave.call(this,j,v);};});
    await apply();await p.evaluate(()=>Android.notesSaveIf=realSave);
    assert.equal((await note('a')).items[0].done,true);assert.equal((await note('a')).parentNoteId,'plaats-c');
    // Een gelijktijdige verplaatsing die een cyclus zou maken wordt geweigerd.
    await reset();await noteChildren(p);await p.click('#note-list-attach');await choose('plaats-b');
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){if(++calls===2){const all=JSON.parse(this._notes);all.find(n=>n.id==='plaats-a').parentNoteId='plaats-b';this._notes=JSON.stringify(all);this._nv++;}return realSave.call(this,j,v);};});
    await apply();await p.evaluate(()=>Android.notesSaveIf=realSave);assert.equal((await note('b')).parentNoteId,undefined);assert.equal((await note('a')).parentNoteId,'plaats-b');assert.equal(await p.isDisabled('#note-move-apply'),true);assert.match(await p.textContent('#note-move-status'),/kan niet meer/);await cancel();
    // Na een intussen gewijzigde plaatsing kun je de bestemming opnieuw kiezen en verdergaan.
    await reset();await noteChildren(p);await p.click('#note-list-move');await choose('plaats-c');
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){if(++calls===2){const all=JSON.parse(this._notes);all.find(n=>n.id==='plaats-a').parentNoteId='plaats-d';this._notes=JSON.stringify(all);this._nv++;}return realSave.call(this,j,v);};});
    await apply();await p.evaluate(()=>Android.notesSaveIf=realSave);assert.equal((await note('a')).parentNoteId,'plaats-d');assert.equal(await p.isDisabled('#note-move-apply'),true);
    await choose('plaats-c');await apply();assert.equal((await note('a')).parentNoteId,'plaats-c');
    await p.click('#undo-b');assert.equal((await note('a')).parentNoteId,'plaats-d');
    // Opslagfout laat model en opslag gelijk, de keuze blijft staan om opnieuw te proberen.
    await reset();await noteChildren(p);await p.click('#note-list-move');await choose('plaats-c');
    await p.evaluate(()=>{window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){return ++calls===2?JSON.stringify({err:'Geen ruimte'}):realSave.call(this,j,v);};});
    await apply();assert.equal((await note('a')).parentNoteId,undefined);assert.equal(await p.evaluate(()=>JSON.parse(Android._notes).find(n=>n.id==='plaats-a').parentNoteId),undefined);
    assert.equal(await p.isVisible('#sheet'),true);await p.evaluate(()=>Android.notesSaveIf=realSave);await apply();assert.equal((await note('a')).parentNoteId,'plaats-c');
    // Undo maakt na een andere wijziging geen cyclus en bewaart nieuwe tekst.
    await p.evaluate(()=>{const all=JSON.parse(Android._notes);all.find(n=>n.id==='plaats-a').text='Later aangepast';all.find(n=>n.id==='plaats-a').parentNoteId='plaats-d';Android._notes=JSON.stringify(all);Android._nv++;});
    await p.click('#undo-b');assert.equal((await note('a')).parentNoteId,'plaats-d');assert.equal((await note('a')).text,'Later aangepast');
    // Onjuiste relaties in een backup verbergen geen lijsten, titels zijn veilig als tekst.
    await reset();await p.evaluate(()=>{noteById('plaats-a').parentNoteId='plaats-b';noteById('plaats-b').parentNoteId='plaats-a';noteById('plaats-c').parentNoteId='verwijderd';noteById('plaats-d').title='<img src=x onerror=alert(1)>';notesSaveNow();show('notes');});
    await p.fill('#notes-search','');assert.equal(await p.locator('#notes-list [data-note-id^="plaats-"]').count(),4);assert.equal(await p.locator('#notes-list img').count(),0);
    await reset();await p.evaluate(()=>{noteById('plaats-b').parentNoteId='plaats-a';notesSaveNow();renderItems();});
    await p.evaluate(()=>{$('#undo').classList.remove('on');$('#toast').classList.remove('on');});await p.waitForTimeout(250);await p.screenshot({path:__dirname+'/shots/notes-place-'+scheme+'.png',fullPage:true});
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await noteChildren(p);await p.click('#note-list-move');await choose('plaats-c');assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.screenshot({path:__dirname+'/shots/notes-place-picker-'+scheme+'.png'});await cancel();
    assert.deepEqual(errors,[]);console.log(scheme,'lijsten verplaatsen, omwisselen, items indelen, opslagconflicten, undo en toetsenbord: errors []');await p.close();
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
