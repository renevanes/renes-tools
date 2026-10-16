const {noteExtra}=require('./note-navigation');
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch();
  for(const scheme of ['light','dark']){
    const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});
    const errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));
    const reset=async()=>p.evaluate(()=>{
      noteSplitClose();notes=notesGet().filter(n=>n.id!=='sub');
      notes.push({id:'sub',title:'Weekend',text:'',updated:Date.now(),items:[
        {id:'a',text:'Reis',done:false},{id:'b',text:'Boodschappen',done:false},
        {id:'c',text:'Trein',done:false,parentId:'a'},{id:'d',text:'Ticket',done:true,parentId:'c'},
        {id:'e',text:'Hotel',done:false,parentId:'a'},{id:'f',text:'Afgerond',done:true}
      ]});notesSaveNow();openNote('sub');
    });
    const items=()=>p.evaluate(()=>noteById('sub').items);
    const rows=()=>p.locator('#note-open li').evaluateAll(ls=>ls.map(li=>li.dataset.id));
    const menu=async(id,label)=>{await p.click('[data-id="'+id+'"] .item-menu');await p.getByRole('button',{name:label,exact:true}).click();};
    await reset();
    assert.deepEqual(await rows(),['a','c','d','e','b']);
    assert.equal(await p.getAttribute('[data-id="d"]','data-depth'),'2');
    await menu('a','Subitem toevoegen');await p.fill('#note-add','Koffer');await p.click('.additem button[aria-label="Toevoegen"]');
    assert.equal((await items()).find(i=>i.text==='Koffer').parentId,'a');
    assert.match(await p.textContent('#note-add-target'),/Reis/);
    await p.getByRole('button',{name:'Hoofdlijst',exact:true}).click();await p.fill('#note-add','Bellen');await p.press('#note-add','Enter');
    assert.equal((await items()).find(i=>i.text==='Bellen').parentId,undefined);
    // Vinkjes zijn onafhankelijk: de sublijst blijft zichtbaar als het hoofditem afgevinkt wordt.
    await p.click('[data-id="a"] input[type="checkbox"]');
    assert.equal((await items()).find(i=>i.id==='c').done,false);assert.ok((await rows()).includes('c'));
    await p.evaluate(()=>{Android.noteShare=(title,text)=>window.shared=text;noteShareDo(false);});
    assert.match(await p.evaluate(()=>window.shared),/☑ Reis\n  ☐ Trein\n    ☑ Ticket/);
    // Niveau wijzigen via toetsenbordbereikbare menu's; een hele groep verhuist mee.
    await reset();await menu('b','Inspringen onder Reis');
    assert.equal((await items()).find(i=>i.id==='b').parentId,'a');
    await p.click('#undo-b');assert.deepEqual(await rows(),['a','c','d','e','b'],'undo herstelt de oude volgorde');
    await menu('b','Inspringen onder Reis');
    await menu('b','Een niveau terug');assert.equal((await items()).find(i=>i.id==='b').parentId,undefined);
    await p.click('#undo-b');assert.equal((await items()).find(i=>i.id==='b').parentId,'a');
    await p.evaluate(()=>noteReparent('sub','a','d'));
    assert.equal((await items()).find(i=>i.id==='a').parentId,undefined,'cyclische indeling geweigerd');
    // Een latere wijziging mag undo geen cyclus laten maken.
    await p.evaluate(()=>{notesSaveNow();noteReparent('sub','b',null);const ns=JSON.parse(Android._notes);ns.find(n=>n.id==='sub').items.find(i=>i.id==='a').parentId='b';Android._notes=JSON.stringify(ns);Android._nv++;});
    await p.click('#undo-b');assert.equal((await items()).find(i=>i.id==='b').parentId,undefined);
    // Sorteren binnen elke lijst, zonder kinderen bij een ander hoofditem te zetten.
    await reset();await p.evaluate(()=>noteSort());await p.getByRole('button',{name:'A tot Z',exact:true}).click();
    assert.deepEqual(await rows(),['b','a','e','c','d']);
    assert.equal((await items()).find(i=>i.id==='d').parentId,'c');
    // Slepen neemt de volledige groep mee en verandert nooit het niveau.
    const from=await p.locator('[data-id="a"] .drag').boundingBox(),to=await p.locator('[data-id="b"]').boundingBox();
    await p.mouse.move(from.x+from.width/2,from.y+from.height/2);await p.mouse.down();
    await p.mouse.move(to.x+10,to.y+3,{steps:8});await p.mouse.up();
    assert.deepEqual(await rows(),['a','e','c','d','b']);
    // A staat al direct achter B: slepen over de onderste helft van B is een veilige no-op.
    await p.evaluate(()=>{const n=noteById('sub');n.items=[n.items.find(i=>i.id==='b')].concat(n.items.filter(i=>i.id!=='b'));renderItems();});
    const adjacentFrom=await p.locator('[data-id="a"] .drag').boundingBox(),adjacentTo=await p.locator('[data-id="b"]').boundingBox();
    await p.mouse.move(adjacentFrom.x+10,adjacentFrom.y+10);await p.mouse.down();
    await p.mouse.move(adjacentTo.x+10,adjacentTo.y+adjacentTo.height-4,{steps:8});await p.mouse.up();
    assert.deepEqual(await rows(),['b','a','e','c','d']);
    // Verwijderen en opruimen laten open subitems staan; ongedaan maken herstelt de indeling.
    await reset();await menu('a','Item verwijderen');assert.equal((await items()).length,5);
    assert.ok((await rows()).includes('c'));assert.equal(await p.getAttribute('[data-id="c"]','data-depth'),'0');
    await p.click('#undo-b');assert.equal(await p.getAttribute('[data-id="d"]','data-depth'),'2');
    await p.click('[data-id="a"] input[type="checkbox"]');await p.getByRole('button',{name:'Afgevinkte verwijderen',exact:true}).click();
    assert.ok((await items()).some(i=>i.id==='c'));assert.equal((await items()).some(i=>i.id==='a'),false);
    await p.click('#undo-b');assert.equal(await p.getAttribute('[data-id="d"]','data-depth'),'2');
    // Splitsen behoudt bestaande subitems onder het eerste deel en kan veilig teruggedraaid worden.
    await reset();await p.evaluate(()=>{noteById('sub').items.find(i=>i.id==='a').text='Reis; Verblijf';touch(noteById('sub'));});
    await noteExtra(p,'edit');await p.click('#note-split-open');await p.selectOption('#note-split-source','item:a');await p.click('#note-split-apply');
    assert.equal((await items()).find(i=>i.id==='a').text,'Reis');assert.equal(await p.getAttribute('[data-id="c"]','data-depth'),'1');
    await p.click('#undo-b');assert.equal((await items()).find(i=>i.id==='a').text,'Reis; Verblijf');
    // Geplakte lijst als subitems en geen stille verwezing door ongedaan maken.
    await p.evaluate(()=>noteSubAdd('b'));await p.fill('#note-add','Appels, Brood');await noteExtra(p,'edit');await p.click('#note-split-open');await p.click('#note-split-apply');
    let added=(await items()).filter(i=>i.parentId==='b');assert.equal(added.length,2);
    await p.evaluate(id=>{noteSubAdd(id);$('#note-add').value='Nieuw kind';addItem();},added[0].id);
    await p.click('#undo-b');assert.ok((await items()).some(i=>i.text==='Nieuw kind'));
    assert.ok((await items()).some(i=>i.id===added[0].id),'undo wist geen ouder van een nieuw subitem');
    // Een widgetwijziging tijdens opslaan blijft behouden; verwijderde ouders worden opnieuw gecontroleerd.
    await reset();await p.evaluate(()=>{
      window.realSave=Android.notesSaveIf;let calls=0;
      Android.notesSaveIf=function(j,v){if(++calls===2){const ns=JSON.parse(this._notes);ns.find(n=>n.id==='sub').items.find(i=>i.id==='c').done=true;this._notes=JSON.stringify(ns);this._nv++;}return realSave.call(this,j,v);};
      noteReparent('sub','b','a');Android.notesSaveIf=realSave;
    });
    assert.equal((await items()).find(i=>i.id==='c').done,true);assert.equal((await items()).find(i=>i.id==='b').parentId,'a');
    await p.evaluate(()=>{
      noteSubAdd('a');$('#note-add').value='Concept';const ns=JSON.parse(Android._notes);ns.find(n=>n.id==='sub').items=ns.find(n=>n.id==='sub').items.filter(i=>i.id!=='a');Android._notes=JSON.stringify(ns);Android._nv++;addItem();
    });
    assert.equal(await p.inputValue('#note-add'),'Concept');assert.equal((await items()).some(i=>i.text==='Concept'),false);
    // Opslagfouten veranderen de indeling niet en laten het getypte subitem staan.
    await reset();await p.evaluate(()=>{noteSubAdd('a');$('#note-add').value='Niet kwijt';window.realSave=Android.notesSaveIf;let calls=0;Android.notesSaveIf=function(j,v){return ++calls===2?JSON.stringify({err:'Geen ruimte'}):realSave.call(this,j,v);};addItem();Android.notesSaveIf=realSave;});
    assert.equal(await p.inputValue('#note-add'),'Niet kwijt');assert.equal((await items()).some(i=>i.text==='Niet kwijt'),false);
    // Gedeelde ingesprongen lijst houdt subitems en vinkjes.
    await p.evaluate(()=>{Android.pendingShare=()=>JSON.stringify({title:'Gedeeld',text:'☐ Hoofd\n  ☑ Kind\n    ☐ Dieper\n☐ Tweede'});noteFromShare();});
    const imported=await p.evaluate(()=>noteTree(noteById(noteCur).items).map(r=>({text:r.it.text,done:r.it.done,depth:r.depth})));
    assert.deepEqual(imported,[{text:'Hoofd',done:false,depth:0},{text:'Kind',done:true,depth:1},{text:'Dieper',done:false,depth:2},{text:'Tweede',done:false,depth:0}]);
    // Kapotte relaties uit een backup verbergen niets en HTML in teksten blijft tekst.
    await p.evaluate(()=>{const n=noteById(noteCur);n.items=[{id:'x',text:'<img src=x onerror=alert(1)>',parentId:'y'},{id:'y',text:'Y',parentId:'x'},{id:'z',text:'Z',parentId:'weg'}];renderItems();});
    assert.equal(await p.locator('#note-open li').count(),3);assert.equal(await p.locator('#note-open img').count(),0);
    await reset();await p.screenshot({path:__dirname+'/shots/notes-sublists-'+scheme+'.png',fullPage:true});
    await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await p.evaluate(()=>{noteSubAdd('a');openNote('a1');});assert.equal(await p.isVisible('#note-add-target'),false);
    assert.deepEqual(errors,[]);console.log(scheme,'sublijstjes, niveaus, sorteren, slepen, delen, splitsen, widgetconflict en opslagfouten: errors []');await p.close();
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
