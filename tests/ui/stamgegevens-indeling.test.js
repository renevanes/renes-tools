// Stamgegevens op de telefoon: korte schermen zonder scrollen (390×844), één zone van schermen en de kluis blijft dicht erbuiten.
// Overzicht (zoeken, filter, Toevoegen), bewerken met tabbladen en een vaste opslaanbalk, Activiteiten en Opnieuw beginnen apart.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
const W=+process.env.UI_WACHT||1;
(async()=>{const browser=await chromium.launch(),errors=[];
for(const [colorScheme,en] of [['light',false],['dark',false],['light',true]]){
 const tag=colorScheme+(en?' (Engels)':'');
 const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
 await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300*W);
 if(en){await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForFunction(()=>TAAL.on);}
 const past=async wat=>{const m=await p.evaluate(()=>({h:document.documentElement.scrollHeight,v:innerHeight}));assert.ok(m.h<=m.v+1,tag+': '+wat+' past zonder scrollen ('+m.h+' > '+m.v+')');};

 // Vergrendeld: korte kaart, uitleg ingeklapt, wat er gedeeld is in één regel
 await p.evaluate(()=>show('masterdata'));await p.waitForTimeout(120*W);
 await past('vergrendeld overzicht');
 assert.equal(await p.isVisible('#cm-add'),false,tag+': geen Toevoegen zolang het slot dicht is');
 assert.equal(await p.isVisible('#cmi-shared-stand'),true);assert.equal(await p.textContent('#cmi-shared-stand'),en?'Nothing shared':'Niets gedeeld');
 // Opnieuw beginnen kan ook vergrendeld (⋯), op een eigen scherm; terug naar het overzicht
 await p.click('#cm-menu');await p.waitForSelector('#sheet.on');
 assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),[en?'Start over':'Opnieuw beginnen'],tag+': vergrendeld alleen opnieuw beginnen');
 await p.click('#sheet-acts button');await p.waitForFunction(()=>current==='cmwis');assert.equal(await p.isDisabled('#cm-reset'),true);await past('opnieuw beginnen');
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata');

 await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
 assert.equal(await p.isVisible('#cm-list .empty'),true,tag+': lege lijst met uitleg');
 // Een paar stamgegevens, zoals Rene ze aanmaakt
 const save=async()=>{await p.click('#cm-save');await p.waitForFunction(()=>!cm.mutating&&!cm.edit&&cm.recordsReady&&current==='masterdata');};
 const nieuw=async(type,label,velden={})=>{await p.evaluate(type=>cmiNew(type),type);await p.waitForFunction(()=>current==='cmedit'&&!!cm.edit);
  if(label!==undefined){if(await p.evaluate(()=>!!$('#cm-label').closest('.cm-off')))await p.click('#cm-tab-'+await p.evaluate(()=>$('#cm-label-box').dataset.cmTab));await p.fill('#cm-label',label);}
  for(const [k,v] of Object.entries(velden)){const tab=await p.evaluate(k=>$('#cm-field-'+k).closest('[data-cm-tab]').dataset.cmTab,k);if(await p.evaluate(()=>!$('#cm-tabs').hidden))await p.click('#cm-tab-'+tab);if(await p.evaluate(k=>$('#cm-field-'+k).tagName==='SELECT',k))await p.selectOption('#cm-field-'+k,v);else await p.fill('#cm-field-'+k,v);}};
 await nieuw('persoon','Rene',{naam:'van Es',voornamen:'Rene',geboortedatum:'1980-01-01',bsn:'123456782',plaats:'Rotterdam',context:'Houdt van treinen'});await save();
 await nieuw('persoon','Anna',{naam:'van Es'});await save();
 const rene=await p.evaluate(()=>cm.records.find(r=>r.label==='Rene').id),anna=await p.evaluate(()=>cm.records.find(r=>r.label==='Anna').id);
 await nieuw('identiteit','Paspoort Rene',{persoon:rene,document:'DOC123',geldig:'2030-01-01'});await save();
 await nieuw('relatie',undefined,{vanPersoon:rene,naarPersoon:anna,relatie:'kind'});await save();
 await nieuw('organisatie','Gemeente',{naam:'Gemeente Rotterdam'});await save();
 await nieuw('adres','Huis',{adres:'Coolsingel 1'});await save();
 await nieuw('reis','Zomer',{van:'2027-07-01',tot:'2027-07-20'});await save();
 assert.equal(await p.evaluate(()=>cm.records.length),7);
 assert.equal(await p.evaluate(()=>cm.records.find(r=>r.type==='relatie').label),en?'Anna (child of Rene)':'Anna (kind van Rene)','naam van de relatie vult zich vanzelf, ook op een ander tabblad');

 // Overzicht: lijst met soort, filter, zoeken; Toevoegen kiest eerst de soort
 // Een lange lijst mag schuiven, maar de lijst begint bovenin en Toevoegen blijft in beeld
 assert.ok(await p.evaluate(()=>$('#cm-list').getBoundingClientRect().top<innerHeight/2),tag+': lijst begint in de bovenste helft');
 assert.ok(await p.evaluate(()=>{const r=$('#cm-add').getBoundingClientRect();return r.bottom<=innerHeight&&r.top>innerHeight/2;}),tag+': Toevoegen onderin in beeld');
 assert.deepEqual(await p.$$eval('#cm-filter button',b=>b.map(x=>x.textContent)),en?['All','People','Relationships','Documents','Other']:['Alles','Personen','Relaties','Documenten','Overig']);
 await p.click('#cm-filter button:nth-child(2)');assert.deepEqual(await p.$$eval('#cm-list .strow b',b=>b.map(x=>x.textContent)),['Anna','Rene'],'filter op personen');
 assert.equal(await p.getAttribute('#cm-filter button:nth-child(2)','aria-pressed'),'true');
 await p.fill('#cm-search','ren');assert.deepEqual(await p.$$eval('#cm-list .strow b',b=>b.map(x=>x.textContent)),['Rene'],'zoeken binnen het filter');
 await p.fill('#cm-search','');await p.click('#cm-filter button:nth-child(1)');assert.equal(await p.locator('#cm-list .strow').count(),5,'documenten en relaties staan bij hun persoon, niet los in Alles');assert.equal(await p.isVisible('#cm-list .cmk-start'),true,'zonder jou: Begin met jezelf');
 await p.click('#cm-filter button:nth-child(4)');assert.equal(await p.locator('#cm-list .strow').count(),1,'filter Documenten toont ze wel');await p.click('#cm-filter button:nth-child(1)');
 const minTik=await p.$$eval('#cm-filter button,#cm-list .strow,#cm-add,#cm-menu,#cm-lock-btn',b=>Math.min(...b.map(x=>Math.min(x.getBoundingClientRect().height,x.getBoundingClientRect().width))));assert.ok(minTik>=44,tag+': tikvlakken minstens 44 px ('+minTik+')');
 await p.click('#cm-add');await p.waitForSelector('#sheet.on');
 assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),en?['Myself','Person','Relationship','Identity document','Organisation','Address','Trip','Saved travel consent details']:['Mijzelf','Persoon','Relatie','Identiteitsdocument','Organisatie','Adres','Reis','Vaste gegevens reistoestemming']);
 await p.click('#sheet-acts button:nth-child(3)');await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.type==='relatie'&&!cm.edit.id);
 assert.equal(await p.textContent('#cm-editor-title'),en?'Add relationship':'Relatie toevoegen');
 // Opslaan zonder personen: blijft op het tabblad Relatie, bij de lege keuze
 await p.click('#cm-tab-meer');await p.click('#cm-save');assert.equal(await p.evaluate(()=>cm.tab+' '+document.activeElement.id),'relatie cm-field-vanPersoon');
 assert.equal(await p.textContent('#cm-edit-status'),en?'Choose both people and their relationship':'Kies beide personen en hun relatie');assert.equal(await p.evaluate(()=>/personen|people/.test($('#cm-status').textContent)),false,'melding alleen op het bewerkscherm');
 assert.equal(await p.isVisible('#cm-edit-menu'),false,'nog niets te verwijderen');
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata');

 // Elk tabblad van elke soort past op het scherm, met de opslaanknop in beeld
 for(const label of ['Rene','Paspoort Rene','Anna (kind van Rene)','Gemeente','Huis','Zomer']){
  const id=await p.evaluate(l=>(cm.records.find(r=>r.label===l)||cm.records.find(r=>r.type==='relatie'&&l.includes('kind'))).id,label);
  await p.evaluate(id=>cmEdit(id),id);await p.waitForFunction(id=>current==='cmedit'&&cm.edit?.id===id,id);await p.waitForTimeout(60*W);
  assert.equal(await p.getAttribute('#cm-editor-title','translate'),'no','titel is de naam (gegevens)');
  const tabs=await p.$$eval('#cm-tabs [role=tab]',b=>b.map(x=>x.dataset.tab));
  for(const tab of tabs.length?tabs:['']){
   if(tab)await p.click('#cm-tab-'+tab);await p.waitForTimeout(30*W);
   await past(label+(tab?' → '+tab:''));
   const bar=await p.evaluate(()=>{const r=$('#cm-save').getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;});assert.ok(bar,tag+': opslaan in beeld bij '+label+' '+tab);
   if(tab)assert.equal(await p.getAttribute('#cm-tab-'+tab,'aria-selected'),'true');
  }
  await p.evaluate(()=>cmCancelEdit());await p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady);
 }
 // Persoon: zes tabbladen passen naast elkaar; identiteitsgegevens tonen alleen waar ze staan
 await p.evaluate(id=>cmEdit(id),rene);await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.id);
 assert.deepEqual(await p.$$eval('#cm-tabs [role=tab]',b=>b.map(x=>x.textContent)),en?['Name','Birth','Contact','ID','Relations','More']:['Naam','Geboorte','Contact','ID','Relaties','Meer']);
 assert.equal(await p.evaluate(()=>$('#cm-tabs').scrollWidth<=$('#cm-tabs').clientWidth+1),true,tag+': tabbladen naast elkaar zonder schuiven');
 assert.equal(await p.isVisible('#cm-secret-row'),false,'niet op Naam');await p.click('#cm-tab-geboorte');assert.equal(await p.isVisible('#cm-secret-row'),true,'wel bij het BSN');
 assert.equal(await p.getAttribute('#cm-field-bsn','type'),'password');
 // Toetsenbord: pijltjes wisselen van tabblad
 await p.focus('#cm-tab-geboorte');await p.keyboard.press('ArrowRight');assert.equal(await p.evaluate(()=>document.activeElement.id+' '+cm.tab),'cm-tab-contact contact');
 await p.keyboard.press('End');assert.equal(await p.evaluate(()=>cm.tab),'meer');await p.keyboard.press('Home');assert.equal(await p.evaluate(()=>cm.tab),'naam');
 // Terug met niet-bewaarde wijziging: eerst vragen
 await p.fill('#cm-field-roepnaam','Reen');await p.evaluate(()=>goBack());await p.waitForSelector('#modal.on');assert.equal(await p.evaluate(()=>current),'cmedit','blijft staan tot je kiest');
 await p.evaluate(()=>closeModal(null));await p.waitForTimeout(60*W);assert.equal(await p.evaluate(()=>current+' '+cm.edit?.fields.roepnaam),'cmedit Reen','annuleren laat de wijziging staan');
 // Laat leesantwoord na wisselen naar Opnieuw beginnen: blijft daar
 await p.evaluate(()=>{cm.dirty=false;cmCancelEdit();});await p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady);
 await p.evaluate(id=>{Android._cm.replyDelay=150;cmEdit(id);show('cmwis');},rene);await p.waitForTimeout(300*W);assert.equal(await p.evaluate(()=>current+' '+!!cm.edit),'cmwis false','geen sprong terug naar bewerken');
 await p.evaluate(()=>{Android._cm.replyDelay=10;goBack();});await p.waitForFunction(()=>current==='masterdata'&&cm.recordsReady);
 await p.evaluate(id=>cmEdit(id),rene);await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.id&&!cm.pending.size);
 // Partner toevoegen vanaf de persoon: na opslaan terug bij de persoon, op Relaties
 await p.evaluate(()=>{cm.dirty=false;});await p.click('#cm-tab-relaties');await p.click('#cmk-anderen');await p.waitForSelector('#sheet.on');await p.click('#sheet-acts button:nth-child(3)');await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!cm.edit.id);
 await p.selectOption('#cm-field-naarPersoon',anna);await p.click('#cm-save');await p.waitForFunction(id=>current==='cmedit'&&cm.edit?.id===id&&cm.recordsReady,rene);
 assert.equal(await p.evaluate(()=>cm.tab),'relaties','terug op Relaties');assert.equal(await p.locator('#cmi-person-relation-list .strow').count(),2);
 await past('persoon met twee relaties');
 // Relatie openen vanaf de persoon en terug: weer bij de persoon
 await p.locator('#cmi-person-relation-list .strow').first().click();await p.waitForFunction(()=>cm.edit?.type==='relatie'&&!!cm.edit.id);
 await p.evaluate(()=>goBack());await p.waitForFunction(id=>cm.edit?.id===id&&cm.tab==='relaties',rene);
 // Verwijderen staat in het menu ⋯ van het bewerkscherm
 await p.click('#cm-edit-menu');await p.waitForSelector('#sheet.on');assert.deepEqual(await p.$$eval('#sheet-acts button',b=>b.map(x=>x.textContent)),[en?'Delete master data':'Stamgegeven verwijderen']);await p.evaluate(()=>closeSheet());

 // Binnen de zone blijft het slot open; ontgrendeld zijn Activiteiten via ⋯
 await p.evaluate(()=>cmCancelEdit());await p.waitForFunction(()=>current==='masterdata');
 await p.click('#cm-menu');await p.waitForSelector('#sheet.on');await p.click('#sheet-acts button:first-child');await p.waitForFunction(()=>current==='cmlog'&&cm.events.length>0);
 assert.equal(await p.evaluate(()=>cmOpen()),true,'slot blijft open binnen Stamgegevens');assert.equal(await p.locator('#cm-audit .cm-event').count()>0,true);
 assert.equal(await p.evaluate(()=>/123456782|DOC123/.test($('#cm-audit').textContent)),false);
 await p.evaluate(()=>goBack());await p.waitForFunction(()=>current==='masterdata'&&cmOpen());
 // Vergrendelen tijdens bewerken (met de vraag "Bewerking sluiten?" open): vraag weg, terug naar het overzicht, niets meer in de schermen
 await p.evaluate(id=>cmEdit(id),rene);await p.waitForFunction(()=>current==='cmedit'&&cm.edit?.id&&!cm.pending.size);
 await p.fill('#cm-field-roepnaam','Reen');await p.evaluate(()=>goBack());await p.waitForSelector('#modal.on');
 await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>current==='masterdata'&&!cm.token);
 assert.equal(await p.evaluate(()=>$('#modal').classList.contains('on')),false,'vraag gesloten bij vergrendelen');
 assert.equal(await p.textContent('#cm-status'),en?'Master data is locked. Unsaved data has been cleared.':'Stamgegevens zijn vergrendeld. Niet-bewaarde gegevens zijn gewist.');
 for(const s of ['#s-masterdata','#s-cmedit','#s-cmlog'])assert.equal(await p.evaluate(s=>/Rene|Anna|Paspoort Rene|123456782|Gemeente/.test($(s).innerHTML),s),false,tag+': geen gegevens meer in '+s);
 // Een subscherm openen zonder ontgrendelen kan niet
 await p.evaluate(()=>show('cmedit'));await p.waitForFunction(()=>current==='masterdata');
 // De zone verlaten vergrendelt; Overal zoeken neemt de schermen niet op
 await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);await p.evaluate(()=>show('cmlog'));await p.waitForTimeout(80*W);
 await p.evaluate(()=>show('home'));await p.waitForFunction(()=>!cm.token);
 assert.equal(await p.evaluate(()=>{gsIndex=null;gsBuildIndex();return gsIndex.some(x=>['masterdata','cmedit','cmlog','cmwis'].includes(x.scr));}),false,'niet in Overal zoeken');
 assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|identity|profiel|relati/.test(k))),false);
 await p.close();
}
console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
