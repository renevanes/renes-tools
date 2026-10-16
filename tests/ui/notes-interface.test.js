const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {noteList,noteChildren,noteExtra}=require('./note-navigation');
(async()=>{
  const browser=await chromium.launch();
  try{
    for(const scheme of ['light','dark']){
      const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});
      const errors=[];p.on('pageerror',e=>errors.push(e.message));
      await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));
      await p.evaluate(()=>{
        notesGet();notes=[
          {id:'ui-main',title:'Weekend',text:'Een vrije notitie',updated:40,items:[{id:'ui-i1',text:'Boodschappen',done:false},{id:'ui-i2',text:'Tickets',done:true}]},
          {id:'ui-child',parentNoteId:'ui-main',title:'Reistas',text:'Koffer',updated:30,items:[{id:'ui-i3',text:'Zonnebril',done:false}]},
          {id:'ui-grandchild',parentNoteId:'ui-child',title:'Kleding',text:'',updated:20,items:[]},
          {id:'ui-other',title:'<img src=x onerror=alert(1)>',text:'Veilige tekst',updated:10,items:[]}
        ];notesSaveNow();show('notes');
      });
      const rows=()=>p.locator('#notes-list .nrow').count();
      assert.equal(await p.textContent('#notes-total'),'4');assert.equal(await p.textContent('#notes-open-total'),'2');assert.equal(await p.textContent('#notes-child-total'),'2');
      assert.equal(await rows(),2,'overzicht begint met ingeklapte sublijsten');
      assert.equal(await p.locator('#notes-list img').count(),0);
      await p.click('[data-expand-id="ui-main"]');assert.equal(await rows(),3);
      assert.equal(await p.evaluate(()=>document.activeElement.dataset.expandId),'ui-main');
      await p.click('[data-expand-id="ui-child"]');assert.equal(await rows(),4);
      await p.click('[data-expand-id="ui-main"]');assert.equal(await rows(),2);
      await p.fill('#notes-search','Zonnebril');assert.equal(await rows(),1);
      assert.match(await p.textContent('#notes-list'),/Reistas.*Onder Weekend.*Zonnebril/s);
      await p.fill('#notes-search','');assert.equal(await rows(),2);
      await p.click('[data-scope="children"]');assert.equal(await rows(),2);
      assert.equal(await p.getAttribute('[data-scope="children"]','aria-pressed'),'true');
      await p.click('[data-scope="main"]');assert.equal(await rows(),2);
      await p.click('[data-scope="all"]');await p.click('[data-expand-id="ui-main"]');
      await p.screenshot({path:__dirname+'/shots/notes-interface-overview-'+scheme+'.png',fullPage:true});
      await p.click('#notes-list [data-note-id="ui-main"]');
      assert.equal(await p.isVisible('#note-panel-list'),true);assert.equal(await p.isVisible('#note-list-attach'),false);assert.equal(await p.isVisible('#note-repeat'),false);
      assert.equal(await p.textContent('#note-child-count'),'1');assert.equal(await p.textContent('#note-progress'),'1 van 2 items klaar');
      assert.equal(await p.locator('.note-option-group[open]').count(),0);
      const stored=await p.evaluate(()=>Android._notes);
      await noteChildren(p);assert.equal(await p.isVisible('#note-child-list [data-note-id="ui-child"]'),true);
      await p.click('#note-tab-extra');assert.equal(await p.locator('.note-option-group[open]').count(),0);
      await noteList(p);assert.equal(await p.evaluate(()=>Android._notes),stored,'navigeren verandert geen opgeslagen notitie');
      // Alle tabbladen zijn bereikbaar met pijltjes, Home en End; focus blijft op de geselecteerde tab.
      await p.locator('#note-tab-list').focus();await p.keyboard.press('ArrowRight');
      assert.equal(await p.evaluate(()=>document.activeElement.id),'note-tab-children');
      await p.keyboard.press('End');assert.equal(await p.evaluate(()=>document.activeElement.id),'note-tab-extra');
      await p.keyboard.press('Home');await p.keyboard.press('ArrowLeft');assert.equal(await p.evaluate(()=>document.activeElement.id),'note-tab-extra');
      assert.deepEqual(await p.locator('.note-section-tabs [role=tab]').evaluateAll(t=>t.map(e=>[e.getAttribute('aria-selected'),e.tabIndex])),[['false',-1],['false',-1],['true',0]]);
      // Concepten blijven staan als je naar de sublijsten of opties gaat.
      await noteList(p);await p.fill('#note-title','Weekend weg');await p.fill('#note-text','Deze vrije tekst bewaren');await p.fill('#note-add','Nog niet toegevoegd');
      await p.getByRole('button',{name:'Lijstopties',exact:true}).click();
      assert.equal(await p.evaluate(()=>document.activeElement.parentElement.id),'note-extra-edit','programmatic wissel focust zichtbare groep');
      assert.equal(await p.locator('#note-extra-edit').evaluate(e=>e.open),true);
      await p.click('#note-split-open');await p.selectOption('#note-split-source','paste');await p.fill('#note-split-text','Melk; Brood');await p.selectOption('#note-split-separator','semicolon');
      await noteChildren(p);await noteList(p);
      assert.equal(await p.inputValue('#note-title'),'Weekend weg');assert.equal(await p.inputValue('#note-text'),'Deze vrije tekst bewaren');assert.equal(await p.inputValue('#note-add'),'Nog niet toegevoegd');
      await p.click('#note-tab-extra');assert.equal(await p.inputValue('#note-split-text'),'Melk; Brood');
      // Een widget-update ververst items, zonder gekozen tab, open groep of concept te verliezen.
      await p.evaluate(()=>{notesSaveNow();const a=JSON.parse(Android._notes);a.find(n=>n.id==='ui-main').items[0].done=true;Android._notes=JSON.stringify(a);Android._nv++;onNotesMaybeChanged();});
      assert.equal(await p.getAttribute('#note-tab-extra','aria-selected'),'true');assert.equal(await p.locator('#note-extra-edit').evaluate(e=>e.open),true);
      assert.equal(await p.inputValue('#note-split-text'),'Melk; Brood');assert.equal(await p.inputValue('#note-add'),'Nog niet toegevoegd');
      await p.click('#note-split-apply');assert.equal(await p.isVisible('#note-panel-list'),true);
      assert.equal(await p.evaluate(()=>document.activeElement.id),'note-tab-list','na splitsen is focus zichtbaar op Lijst');
      assert.equal(await p.evaluate(()=>noteById('ui-main').items.length),4);
      assert.equal(await p.inputValue('#note-add'),'Nog niet toegevoegd');
      await noteExtra(p,'repeat');await p.selectOption('#note-repeat','week:1');
      assert.equal(await p.textContent('#note-repeat-summary'),'Elke maandag weer leeg');
      await noteExtra(p,'reminders');await p.getByRole('button',{name:'Herinnering toevoegen',exact:true}).click();
      assert.equal(await p.textContent('#note-rem-summary'),'1 herinnering ingesteld');
      await noteExtra(p,'pins');await p.check('#note-pin-app');assert.match(await p.textContent('#note-pin-summary'),/Vastgezet/);
      await p.evaluate(()=>{$('#undo').classList.remove('on');$('#toast').classList.remove('on');document.querySelectorAll('.note-option-group').forEach(e=>e.open=false);});
      await p.evaluate(()=>scrollTo(0,0));await p.waitForTimeout(300);
      await p.screenshot({path:__dirname+'/shots/notes-interface-extra-'+scheme+'.png',fullPage:true});
      await noteList(p);await p.evaluate(()=>scrollTo(0,0));await p.waitForTimeout(300);
      await p.screenshot({path:__dirname+'/shots/notes-interface-list-'+scheme+'.png',fullPage:true});
      await p.setViewportSize({width:320,height:740});
      for(const tab of ['list','children','extra']){
        await p.click('#note-tab-'+tab);assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,tab+' past op smal scherm');
      }
      await noteExtra(p,'reminders');assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'datum en tijd passen op smal scherm');
      // Een andere notitie begint in Lijst en neemt geen geopend splitsconcept over.
      await p.evaluate(()=>openNote('ui-child'));assert.equal(await p.isVisible('#note-panel-list'),true);assert.equal(await p.isVisible('#note-split-panel'),false);
      assert.equal(await p.locator('.note-option-group[open]').count(),0);
      assert.deepEqual(errors,[]);console.log(scheme,'notitie-overzicht, filters, tabbladen, concepten, widgets en toetsenbord: errors []');await p.close();
    }
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
