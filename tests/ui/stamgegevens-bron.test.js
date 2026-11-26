// Stamgegevens en de handtekening kiezen een bestand zoals overal (02-bron.js): documentenkluis, Gemaakt of de telefoon.
// Uit de kluis houdt Stamgegevens open tijdens het ontgrendelen (masterBuiten). Een document verwerken toont een venster met
// wat er gebeurt en een balk (de voortgang van Java), met Annuleren.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const kid='00000000-0000-0000-0000-000000000001',doc='00000000-0000-0000-0000-000000000002';
  await p.evaluate(([kid,doc])=>{
    Android._cm.records=[{id:kid,type:'persoon',label:'Zoë',revision:1,fields:{naam:'Jansen',voornamen:'Zoë'}},
      {id:doc,type:'identiteit',label:'Paspoort Zoë',revision:1,fields:{documentsoort:'paspoort',persoon:kid}}];
    Android._kl.open=true;
    Android._kl.docs=[{id:'kpdf000001',name:'Paspoort.pdf',mime:'application/pdf',size:9,t:2},{id:'kfoto00001',name:'Bon.jpg',mime:'image/jpeg',size:9,t:3},
      {id:'kword00001',name:'Brief.docx',mime:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',size:9,t:4},{id:'kboek00001',name:'Roman.epub',mime:'application/epub+zip',size:9,t:1}];
    Android._pdf.outs=[{name:'Samengevoegd.pdf',size:1200}];
    const sha='a'.repeat(64);Android._fa.dos={[sha]:{sha,naam:'KPN.pdf',leverancierNaam:'KPN',opgehaald:5,plekken:{app:{pad:'/x/KPN.pdf',t:5}},verzonden:{},meta:{}}};
  },[kid,doc]);
  await p.evaluate(()=>show('masterdata'));await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  const keuzes=()=>p.$$eval('#sheet-acts button',x=>x.map(e=>e.textContent));
  const kies=naam=>p.locator('#sheet-acts').getByRole('button',{name:naam,exact:true}).click();

  // 1. ID-bewijs uit een document: dezelfde keuze als overal (geen facturen); uit de kluis ook PDF en Word
  await T.kaart(kid);await p.click('#cmc-doc-add');await p.waitForSelector('#sheet.on');
  await kies('Identiteitskaart');await p.waitForFunction(()=>$('#sheet-title').textContent==='Identiteitskaart');
  await kies('Foto of document kiezen');await p.waitForFunction(()=>$('#sheet-title').textContent==='Foto of document kiezen');
  assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis','Uit Gemaakt (PDF-tools)']);
  await kies('Uit de documentenkluis');await p.waitForFunction(()=>$('#sheet-title').textContent==='Uit de documentenkluis');
  assert.deepEqual(await keuzes(),['Brief.docx','Bon.jpg','Paspoort.pdf'],'foto, PDF en Word; geen boek');
  assert.equal(await p.evaluate(()=>(Android._cm.buiten||[]).length),0,'de kluis was al open: geen venster om open te blijven');
  await p.evaluate(()=>Android._cm.photoDelay=4000);
  await kies('Paspoort.pdf');
  assert.equal(await p.evaluate(()=>Android._bronKiezer),'stamgegevens');
  assert.equal(await p.evaluate(()=>JSON.stringify(Android._bron.at(-1))),JSON.stringify([{bron:'kluis',id:'kpdf000001',versie:0}]));
  // 2. Verwerken: een venster met wat er gebeurt en een balk, niet alleen een regel onderin
  await p.waitForFunction(()=>$('#modal.on')&&!$('#cmh-wacht').hidden);
  assert.equal(await p.textContent('#modal-title'),'Document verwerken');assert.equal(await p.textContent('#cmh-wacht .ui-wacht-t'),'Bestand lezen…');
  assert.equal(await p.isVisible('#modal-ok'),false);assert.equal(await p.textContent('#modal-cancel'),'Annuleren');
  await p.evaluate(()=>onVoortgang({soort:'kaartfoto',stap:'lezen',gedaan:50,totaal:100}));
  assert.equal(await p.getAttribute('#cmh-wacht .upd-prog','aria-valuenow'),'50');
  await p.evaluate(()=>onVoortgang({soort:'kaartfoto',stap:'omzetten',gedaan:0,totaal:0}));
  assert.equal(await p.textContent('#cmh-wacht .ui-wacht-t'),'Foto\'s uit het document halen…');
  await p.waitForTimeout(500);assert.equal(await p.isVisible('#vg'),false,'geen tweede balk onderin');
  // Annuleren: venster dicht, niets meer verwerkt, Stamgegevens blijft open
  await p.click('#modal-cancel');await p.waitForFunction(()=>!$('#modal.on')&&!cmi.photoRequest&&!cm.mutating);
  assert.equal(await p.evaluate(()=>cmOpen()),true);assert.equal(await p.isVisible('#modal-ok'),false,'venster dicht');
  await p.waitForTimeout(4200);assert.equal(await p.evaluate(()=>current),'cmkaart','een late uitkomst doet niets meer');
  assert.equal(await p.isVisible('#modal'),false);
  // Terug tijdens het wachten = annuleren; het gedeelde venster werkt daarna weer gewoon (OK, geen balk)
  await p.evaluate(()=>Android._cm.photoDelay=4000);await p.click('#cmc-doc-add');await kies('Identiteitskaart');
  await p.waitForFunction(()=>$('#sheet-title').textContent==='Identiteitskaart');await kies('Foto of document kiezen');await kies('Bestand op de telefoon');
  await p.waitForFunction(()=>$('#modal.on')&&!$('#cmh-wacht').hidden);
  await p.evaluate(()=>goBack());await p.waitForFunction(()=>!$('#modal.on')&&!cmi.photoRequest);
  assert.equal(await p.evaluate(()=>cmOpen()&&current==='cmkaart'),true,'terug sloot alleen het venster');
  await p.evaluate(()=>askConfirm('Vraag','Tekst','Ja',()=>{window.__ja=1;}));
  assert.equal(await p.isVisible('#modal-ok'),true);assert.equal(await p.isVisible('#cmh-wacht'),false);
  await p.click('#modal-ok');assert.equal(await p.evaluate(()=>window.__ja),1);
  await p.waitForTimeout(4200);assert.equal(await p.isVisible('#modal'),false,'de late uitkomst sluit geen ander venster');
  // Klaar: het venster gaat vanzelf dicht
  // (een strook in een afwijkende letter: dat staat er zichtbaar bij)
  await p.evaluate(()=>{Android._cm.herkend={document:{documentsoort:'identiteitskaart',document:'NX7P41KD9',geldig:'2031-06-11'},persoon:{naam:'Jansen'},soort:'TD1',zeker:false,afwijkend:true,twijfel:['naam']};});
  await p.evaluate(()=>Android._cm.photoDelay=300);await p.click('#cmc-doc-add');await kies('Identiteitskaart');
  await p.waitForFunction(()=>$('#sheet-title').textContent==='Identiteitskaart');await kies('Foto of document kiezen');await kies('Bestand op de telefoon');
  await p.waitForFunction(()=>$('#modal.on')&&$('#modal-title').textContent==='Document verwerken');
  await p.waitForFunction(()=>current==='cmedit'&&!$('#modal.on'));
  assert.equal(await p.isVisible('#modal-ok'),false);
  await p.waitForFunction(()=>!$('#cmh-panel').hidden);assert.match(await p.textContent('#cmh-source'),/afwijkende letter gelezen/);
  await p.evaluate(()=>{Android._cm.herkend=null;});
  await p.evaluate(()=>{cm.dirty=false;goBack();});await p.waitForFunction(()=>!cm.mutating);

  // 3. Kaartfoto (voor- of achterkant): ook uit een document (PDF, Word), zoals bij Document toevoegen
  await p.evaluate(()=>show('masterdata'));if(!(await p.evaluate(()=>cmOpen()))){await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);}
  assert.equal(await p.evaluate(()=>Android._kl.open),false,'de kluis ging na het kiezen weer op slot');
  await T.kaart(doc);await p.evaluate(()=>cmcPhotoPick('front'));await p.waitForSelector('#sheet.on');
  assert.equal(await p.textContent('#sheet-title'),'Kaartfoto kiezen');assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis','Uit Gemaakt (PDF-tools)']);
  // De kluis staat op slot: het slotscherm vraagt het; Stamgegevens blijft open
  await kies('Uit de documentenkluis');await p.waitForSelector('#toolslot.on');assert.equal(await p.textContent('#ts-naam'),'Kluis');
  await p.click('#ts-open');await p.waitForFunction(()=>$('#sheet.on')&&$('#sheet-title').textContent==='Uit de documentenkluis');
  assert.deepEqual(await keuzes(),['Brief.docx','Bon.jpg','Paspoort.pdf']);assert.equal(await p.evaluate(()=>cmOpen()&&current==='cmkaart'),true);
  assert.deepEqual(await p.evaluate(()=>Android._cm.buiten.map(x=>[x[0]===cm.token,x[1]])),[[true,'kluis']],'Stamgegevens blijft open tijdens het ontgrendelen van de kluis');await p.evaluate(()=>closeSheet());
  // Een PDF met twee foto's voor de voorkant: Tools zet hem om, Rene kiest één foto; die wordt de voorkant
  await p.evaluate(()=>{Android._cm.kaartFotos=[{pagina:1},{pagina:2,heel:true}];Android._cm.photoDelay=200;});
  await p.evaluate(()=>cmcPhotoPick('front'));await p.waitForSelector('#sheet.on');await kies('Bestand op de telefoon');
  await p.waitForFunction(()=>$('#modal.on')&&$('#modal-title').textContent==='Kies de foto van de voorkant'&&!$('#cmh-keuze').hidden);
  assert.match(await p.textContent('#modal-text'),/2 foto's\. Tik op de foto die je wilt bewaren/);
  const fotoKnop=n=>p.locator('#cmh-keuze .cmh-foto').nth(n);
  await fotoKnop(0).click();await fotoKnop(1).click();
  assert.deepEqual(await p.$$eval('#cmh-keuze .cmh-foto',x=>x.map(b=>b.getAttribute('aria-pressed'))),['false','true'],'voor één kant: één foto gekozen');
  await p.click('#modal-ok');await p.waitForFunction(()=>current==='cmkaart'&&/Kaartfoto opgeslagen/.test($('#cm-kaart-status').textContent));
  assert.deepEqual(await p.evaluate(()=>Android._cm.gekozen),[1]);
  assert.equal(await p.evaluate(doc=>!!Android._cm.records.find(r=>r.id===doc).images?.front,doc),true,'de gekozen foto is de voorkant');
  await p.evaluate(()=>{Android._cm.kaartFotos=null;});
  // Op het kaartje: voor- en achterkant omwisselen (staat er één, dan naar de andere kant) en zelf laten herkennen
  await T.kaart(doc);await p.waitForSelector('#cmc-wissel');
  assert.equal(await p.textContent('#cmc-wissel'),'Dit is de achterkant');assert.equal(await p.isVisible('#cmc-herken'),true);
  await p.click('#cmc-wissel');await p.waitForFunction(()=>/andere kant/.test($('#cm-kaart-status').textContent));
  assert.equal(await p.evaluate(doc=>{const i=Android._cm.records.find(r=>r.id===doc).images;return !i.front&&!!i.back;},doc),true,'de foto staat nu achter');
  await p.waitForFunction(()=>$('#cmc-wissel')?.textContent==='Dit is de voorkant');
  await p.click('#cmc-herken');await p.waitForFunction(doc=>current==='cmedit'&&cm.edit?.id===doc&&cm.tab==='fotos',doc);
  await p.waitForFunction(()=>!cmh.scanBusy&&!cm.mutating);
  await p.evaluate(()=>{cm.dirty=false;goBack();});await p.waitForFunction(()=>current==='cmkaart'&&!cm.mutating);
  // Een oudere app (zonder masterKaartDocument): bij een kaartfoto alleen foto's, zoals die app het aankan
  await p.evaluate(()=>{window.__kd=Android.masterKaartDocument;Android.masterKaartDocument=undefined;cmcPhotoPick('back');});await p.waitForSelector('#sheet.on');
  assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis']);await p.evaluate(()=>{closeSheet();Android.masterKaartDocument=window.__kd;});

  // Twee foto's uit een document: wat je eerst kiest is de voorkant, de tweede de achterkant; wisselen kan
  await p.evaluate(()=>{Android._cm.scanFotos=[{pagina:1},{pagina:2}];Android._cm.photoDelay=200;Android._cm.herkend=null;});
  await T.kaart(kid);await p.click('#cmc-doc-add');await kies('Paspoort');await p.waitForFunction(()=>$('#sheet-title').textContent==='Paspoort');
  await kies('Foto of document kiezen');await kies('Bestand op de telefoon');
  await p.waitForFunction(()=>$('#modal.on')&&!$('#cmh-keuze').hidden&&$('#modal-title').textContent==='Kies de foto van het ID-bewijs');
  assert.match(await p.textContent('#modal-text'),/eerst op de voorkant en dan op de achterkant/);
  const tags=()=>p.$$eval('#cmh-keuze .cmh-tag',x=>x.map(e=>e.textContent));
  const foto=n=>p.locator('#cmh-keuze .cmh-foto').nth(n);
  assert.equal(await p.isVisible('#cmh-keuze-wissel'),false);
  await foto(1).click();await foto(0).click();assert.deepEqual(await tags(),['Achterkant','Voorkant']);
  assert.equal(await p.isVisible('#cmh-keuze-wissel'),true);await p.click('#cmh-keuze-wissel');assert.deepEqual(await tags(),['Voorkant','Achterkant']);
  await p.click('#modal-ok');await p.waitForFunction(()=>Android._cm.gekozen&&!cm.mutating&&!$('#modal.on'));
  assert.deepEqual(await p.evaluate(()=>[Android._cm.gekozen,Android._cm.vast]),[[0,1],true],'de volgorde van Rene gaat mee');
  await p.evaluate(()=>{Android._cm.scanFotos=null;});
  await p.waitForFunction(()=>(current==='cmedit'||current==='cmkaart')&&!cm.mutating&&!cm.pending.size);
  await p.evaluate(()=>{cm.dirty=false;if($('#modal').classList.contains('on'))closeModal(null);});await T.kaart(kid);

  // 4. De handtekening: het menu ligt boven het venster
  await p.evaluate(()=>sigTeken({}));await p.waitForSelector('#sigdlg.on');await p.click('#sig-foto');await p.waitForSelector('#sheet.on');
  assert.equal(await p.textContent('#sheet-title'),'Foto van je handtekening');assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis']);
  assert.equal(await p.evaluate(()=>+getComputedStyle($('#sheet')).zIndex>+getComputedStyle($('#sigdlg')).zIndex),true,'menu boven het venster');
  await kies('Bestand op de telefoon');await p.waitForFunction(()=>(Android._sigPicks||[]).length===1);
  await p.waitForFunction(()=>!sig.kiezen);await p.evaluate(()=>sigClose());
  await p.evaluate(()=>openSheet('x','',[['y',()=>{}]]));assert.equal(await p.evaluate(()=>$('#sheet').classList.contains('boven')),false,'zonder venster gewoon');await p.evaluate(()=>closeSheet());
  await p.close();
}
await b.close();console.log('errors',errors);if(errors.length)process.exit(1);})().catch(e=>{console.error(e);process.exit(1);});
