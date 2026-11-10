// Documenten vergelijken: A en B kiezen (bestand, kluis, Gemaakt), tekstverschillen (ingekort, alles tonen, data blijft tekst),
// pagina's met verschillen, één pagina in drie standen, omwisselen en opruimen bij weggaan.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  await p.evaluate(()=>{openTool('pdf');});await p.waitForTimeout(120);
  await p.click('#pdf-t-cmp');assert.equal(await p.evaluate(()=>current),'pdfcmp');
  assert.equal(await p.isVisible('#cmp-werk'),false,'eerst twee documenten kiezen');assert.equal(await p.isDisabled('#cmp-wissel'),true);
  // A: een bestand van de telefoon
  await p.click('#cmp-a');await p.locator('#sheet-acts').getByRole('button',{name:'Bestand op de telefoon',exact:true}).click();
  await p.waitForFunction(()=>cmp.a);assert.equal(await p.textContent('#cmp-a-naam'),'Contract oud');assert.equal(await p.getAttribute('#cmp-a-naam','translate'),'no');
  assert.equal(await p.textContent('#cmp-a-info'),"2 pagina's");
  // B: uit de kluis (eerst ontgrendelen)
  await p.evaluate(()=>{Android._kl.open=false;Android._kl.docs=[{id:'kdoc000001',name:'Contract <b>nieuw</b>.pdf',mime:'application/pdf',size:9,t:2},{id:'kdoc000002',name:'Notities.txt',mime:'text/plain',size:3,t:3}];});
  await p.click('#cmp-b');await p.locator('#sheet-acts').getByRole('button',{name:'Uit de documentenkluis',exact:true}).click();
  await p.waitForFunction(()=>$('#sheet').classList.contains('on')&&$('#sheet-title').textContent==='Uit de documentenkluis');
  assert.equal(await p.locator('#sheet-acts button').count(),1,'alleen PDF en foto');
  await p.locator('#sheet-acts button').first().click();await p.waitForFunction(()=>cmp.b);
  assert.equal(await p.textContent('#cmp-b-naam'),'Contract <b>nieuw</b>.pdf');assert.match(await p.textContent('#cmp-b-info'),/uit de documentenkluis/);
  assert.equal(await p.isVisible('#cmp-werk'),true);assert.equal(await p.isDisabled('#cmp-wissel'),false);
  // Tekst: verschillen gemarkeerd, gelijke tekst ingekort, HTML uit het document blijft tekst
  await p.click('#cmp-tekst-start');await p.waitForFunction(()=>cmp.tekst);
  assert.match(await p.textContent('#cmp-tekst-stand'),/Alleen in A: één woord\. Alleen in B: 4 woorden\./);
  assert.equal(await p.textContent('#cmp-tekst del'),'800 ');assert.equal(await p.locator('#cmp-tekst ins').count(),2);
  assert.equal(await p.locator('#cmp-tekst ins b').count(),0,'tekst uit het document is nooit HTML');
  assert.match(await p.textContent('#cmp-tekst'),/regels gelijk/);assert.equal(await p.isVisible('#cmp-alles-wrap'),true);
  await p.check('#cmp-alles');assert.doesNotMatch(await p.textContent('#cmp-tekst'),/regels gelijk/);assert.match(await p.textContent('#cmp-tekst'),/Regel 5/);
  assert.equal(await p.isVisible('#cmp-tekst-start'),false,'knop weg als er een uitkomst is');
  // Pagina's: de pagina met verschillen opent vanzelf, met de drie standen
  await p.click('#cmp-tab-beeld');assert.equal(await p.getAttribute('#cmp-tab-beeld','aria-selected'),'true');assert.equal(await p.isVisible('#cmp-p-tekst'),false);
  await p.click('#cmp-beeld-start');await p.waitForFunction(()=>cmp.paginas&&$('#cmp-img').getAttribute('src'));
  assert.match(await p.textContent('#cmp-beeld-stand'),/Eén pagina heeft verschillen/);
  assert.deepEqual(await p.$$eval('#cmp-paginas .cmp-pag',x=>x.map(e=>e.className)),['cmp-pag','cmp-pag anders']);
  assert.equal(await p.textContent('#cmp-pagina-kop'),'Pagina 2');assert.match(await p.getAttribute('#cmp-img','alt'),/verschillen tussen A en B/);
  assert.equal(await p.isVisible('#cmp-beeld-legenda'),true);
  await p.click('#cmp-modi [data-v="a"]');await p.waitForFunction(()=>Android._cmp.page.mode==='a'&&/van A/.test($('#cmp-img').alt));
  assert.equal(await p.isVisible('#cmp-beeld-legenda'),false,'legenda alleen bij Verschillen');
  await p.click('#cmp-vorige');await p.waitForFunction(()=>Android._cmp.page.i===0);assert.equal(await p.isDisabled('#cmp-vorige'),true);
  for(const sel of ['#cmp-a','#cmp-b','.cmp-pag','#cmp-vorige'])assert.ok((await p.$eval(sel,e=>e.getBoundingClientRect().height))>=44,sel+': raakvlak 44 px');
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'geen horizontaal schuiven');
  await p.screenshot({path:__dirname+'/shots/vergelijken-'+colorScheme+'.png',fullPage:true});
  // Omwisselen: uitkomsten weg, A en B andersom
  await p.click('#cmp-wissel');assert.equal(await p.textContent('#cmp-a-naam'),'Contract <b>nieuw</b>.pdf');assert.equal(await p.isVisible('#cmp-pagina-kaart'),false);
  // Een gemaakte PDF als A (vanuit het menu onder Gemaakt)
  await p.evaluate(()=>{Android._pdf.outs=[{name:'Brief.pdf',size:10}];show('pdfmade');});
  await p.evaluate(()=>pdfOutMenu('Brief.pdf'));await p.locator('#sheet-acts').getByRole('button',{name:'Vergelijken met…',exact:true}).click();
  await p.waitForFunction(()=>current==='pdfcmp'&&cmp.a&&cmp.a.name==='Brief');
  // Weggaan wist de bestanden in Java
  const voor=await p.evaluate(()=>Android._cmp.cleared||0);await p.evaluate(()=>goBack());
  assert.equal(await p.evaluate(()=>current),'pdf');assert.equal(await p.evaluate(()=>Android._cmp.cleared),voor+1);
  assert.equal(await p.evaluate(()=>!!(cmp.a||cmp.b)),false);
  // Een fout bij het kiezen staat in beeld
  await p.click('#pdf-t-cmp');await p.evaluate(()=>onCmp({error:'Deze PDF is beveiligd met een wachtwoord'}));assert.match(await p.textContent('#toast'),/wachtwoord/);
  await p.close();
}
await b.close();assert.deepEqual(errors,[]);console.log('vergelijken: kiezen, tekst, pagina\'s, standen, omwisselen, opruimen: errors []');
})().catch(e=>{console.error(e);console.log('errors ['+e.message+']');process.exit(1);});
