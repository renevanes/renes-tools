// Bestand kiezen (02-bron.js): overal waar een tool een bestand vraagt, eerst kiezen waar het vandaan komt (documentenkluis,
// Gemaakt, facturendossier of de telefoon). Een bestand uit de app wordt in Java klaargezet (bronVolgende) en dan krijgt de
// gewone kiesfunctie van de tool het; bij PDF openen en PDF bewerken doet de kluis iets eigens.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
const SHA='a'.repeat(64);
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  await p.evaluate(SHA=>{
    Android._kl.open=true;
    Android._kl.docs=[{id:'kpdf000001',name:'Polis.pdf',mime:'application/pdf',size:9,t:2},{id:'kfoto00001',name:'Bon.jpg',mime:'image/jpeg',size:9,t:3},{id:'kboek00001',name:'Roman.epub',mime:'application/epub+zip',size:9,t:1}];
    Android._pdf.outs=[{name:'Samengevoegd.pdf',size:1200}];
    Android._fa.dos={[SHA]:{sha:SHA,naam:'KPN oktober.pdf',leverancierNaam:'KPN',opgehaald:5,plekken:{app:{pad:'/x/KPN oktober.pdf',t:5}},verzonden:{},meta:{}},
      ['b'.repeat(64)]:{sha:'b'.repeat(64),naam:'Weg.pdf',leverancierNaam:'Eneco',opgehaald:4,plekken:{app:{pad:'/x/Weg.pdf',t:4,weg:6}},verzonden:{},meta:{}}};
    openTool('pdf');
  },SHA);
  await p.waitForFunction(()=>current==='pdf');
  const keuzes=()=>p.$$eval('#sheet-acts button',x=>x.map(e=>e.textContent));
  const kies=async naam=>{await p.locator('#sheet-acts').getByRole('button',{name:naam,exact:true}).click();};
  const bron=()=>p.evaluate(()=>JSON.stringify((Android._bron||[]).at(-1)||null));

  // 1. PDF openen: vier plekken, in deze volgorde
  await p.click('#pdf-view-open');await p.waitForSelector('#sheet.on');
  assert.equal(await p.textContent('#sheet-title'),'PDF openen');
  assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis','Uit Gemaakt (PDF-tools)','Uit het facturendossier']);
  await kies('Bestand op de telefoon');assert.equal(await p.evaluate(()=>Android._pdf.viewPick),1,'gewone bestandskiezer');
  // Uit de kluis: in de viewer van de kluis (geen kopie), alleen PDF's
  await p.click('#pdf-view-open');await kies('Uit de documentenkluis');await p.waitForFunction(()=>$('#sheet-title').textContent==='Uit de documentenkluis');
  assert.deepEqual(await keuzes(),['Polis.pdf'],'alleen wat past');
  await kies('Polis.pdf');assert.equal(await p.evaluate(()=>JSON.stringify(Android._kl.opened)),'{"id":"kpdf000001","version":0}');
  // Uit Gemaakt: zoals op Gemaakt
  await p.click('#pdf-view-open');await kies('Uit Gemaakt (PDF-tools)');await kies('Samengevoegd.pdf');
  assert.equal(await p.evaluate(()=>Android._pdf.opened),'Samengevoegd.pdf');
  // Uit het facturendossier: alleen facturen die de app nog heeft; klaargezet in Java, dan de gewone kiezer
  await p.click('#pdf-view-open');await kies('Uit het facturendossier');
  assert.deepEqual(await keuzes(),['KPN · KPN oktober.pdf'],'een weggehaalde factuur niet');
  await kies('KPN · KPN oktober.pdf');
  assert.equal(await bron(),JSON.stringify([{bron:'factuur',id:SHA,versie:0}]));assert.equal(await p.evaluate(()=>Android._pdf.viewPick),2);

  // 2. Plaatjes: alleen foto's uit de kluis (Gemaakt en facturen zijn PDF's); de kluis gaat daarna weer op slot
  await p.evaluate(()=>show('pdfimg'));await p.click('#pdf-img-pick');
  assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis']);
  await kies('Uit de documentenkluis');assert.deepEqual(await keuzes(),['Bon.jpg']);
  await p.evaluate(()=>{Android._closed=0;const c=Android.kluisClose;Android.kluisClose=function(){Android._closed++;return c&&c.call(Android);};});
  await kies('Bon.jpg');await p.waitForFunction(()=>pdfImgs.length===2);
  assert.equal(await bron(),JSON.stringify([{bron:'kluis',id:'kfoto00001',versie:0}]));
  await p.waitForFunction(()=>Android._closed>0);
  await p.evaluate(()=>{Android._kl.open=true;});

  // 3. PDF bewerken: een kluisdocument opent als nieuwe versie (kluisEdit), niet als kopie
  await p.evaluate(()=>show('pdfedit'));await p.click('#pdf-edit-pick');await kies('Uit de documentenkluis');await kies('Polis.pdf');
  await p.waitForFunction(()=>pdfIn.revision&&pdfIn.revision.startsWith('rev-k'));
  // Splitsen: dezelfde kluis-PDF gaat als kopie via de gewone kiezer
  await p.evaluate(()=>{Android._kl.open=true;show('pdfsplit');});await p.click('#pdf-split-pick');await kies('Uit Gemaakt (PDF-tools)');await kies('Samengevoegd.pdf');
  assert.equal(await bron(),JSON.stringify([{bron:'gemaakt',id:'Samengevoegd.pdf',versie:0}]));

  // 4. Lukt klaarzetten niet (kluis intussen op slot): een melding en geen bestandskiezer
  await p.evaluate(()=>show('pdf'));
  const voor=await p.evaluate(()=>Android._pdf.viewPick);
  await p.evaluate(()=>{window.__toasts=[];const tt=toast;window.toast=(m,...r)=>{__toasts.push(m);return tt(m,...r);};const d={id:'kpdf000001',name:'Polis.pdf'};Android._kl.open=false;bronNeem({bestand:()=>Android.pdfViewPick()},[{bron:'kluis',id:d.id,versie:0}]);});
  assert.equal(await p.evaluate(()=>Android._pdf.viewPick),voor,'geen kiezer');
  assert.deepEqual(await p.evaluate(()=>__toasts),['De kluis is op slot']);

  // Vergelijken: ook een factuur als A, via de gewone kiezer van vergelijken
  await p.evaluate(()=>show('pdf'));await p.click('#pdf-t-cmp');await p.click('#cmp-a');await kies('Uit het facturendossier');await kies('KPN · KPN oktober.pdf');
  await p.waitForFunction(()=>Android._cmp.picked===0&&cmp.a);
  assert.equal(await bron(),JSON.stringify([{bron:'factuur',id:SHA,versie:0}]));

  // 5. Documentenkluis: Document toevoegen biedt niet de kluis zelf
  await p.evaluate(()=>{Android._kl.open=true;show('kluis');});
  if(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'))await p.click('#ts-open');
  await p.waitForFunction(()=>current==='kluis'&&$('#kl-open').style.display==='block');
  await p.click('#s-kluis button:has-text("Document toevoegen")');
  assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit Gemaakt (PDF-tools)','Uit het facturendossier']);
  await p.evaluate(()=>closeSheet());

  // 6. E-reader: Boek toevoegen; weg zonder keuze zet "nieuwe versie kiezen" terug
  await p.evaluate(()=>{Android._kl.open=true;show('ereader');});await p.waitForFunction(()=>current==='ereader');
  await p.evaluate(()=>{erUpgrading=true;});await p.click('#er-add');
  assert.deepEqual(await keuzes(),['Bestand op de telefoon','Uit de documentenkluis']);
  await p.keyboard.press('Escape');await p.waitForFunction(()=>!$('#sheet').classList.contains('on'));
  assert.equal(await p.evaluate(()=>erUpgrading),false);

  // 7. Zonder andere plek (geen kluis, niets gemaakt, geen facturen): meteen de bestandskiezer
  await p.evaluate(()=>{Android._pdf.outs=[];Android._fa.dos={};window.__play=true;const ip=isPlay;window.isPlay=()=>true;show('pdf');window.__ip=ip;});
  const v2=await p.evaluate(()=>Android._pdf.viewPick);
  await p.click('#pdf-view-open');
  assert.equal(await p.evaluate(()=>Android._pdf.viewPick),v2+1);assert.equal(await p.evaluate(()=>$('#sheet').classList.contains('on')),false);
  await p.evaluate(()=>{window.isPlay=window.__ip;});
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
