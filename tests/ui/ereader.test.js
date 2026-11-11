// E-reader: boeken bewaren en terugvinden, lezen, zoeken, annoteren en aanpassen zonder de leesplek kwijt te raken.
const {chromium}=require('playwright');
const path=require('path');
(async()=>{
 const browser=await chromium.launch(),errors=[];
 for(const scheme of ['light','dark']){
  const page=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});page.on('pageerror',e=>errors.push(e.message));
  const ok=(v,m)=>{console.log((v?'✓ ':'✗ ')+scheme+': '+m);if(!v)errors.push(scheme+': '+m);};
  await page.goto('file://'+path.join(__dirname,'ui/index.html'));await page.waitForTimeout(300);
  await page.click('#tile-ereader');ok((await page.locator('#er-list').innerText()).includes('Nog geen boeken'),'mooie lege boekenkast');
  await page.evaluate(()=>{Android.pdfViewPick=()=>window._erPdf=true;});await page.click('#er-pdf');await (async()=>{if(await page.isVisible('#sheet.on'))await page.click('#sheet-acts >> text=Bestand op de telefoon');})();ok(await page.evaluate(()=>window._erPdf&&current==='ereader'),'PDF openen blijft beschikbaar');
  await page.click('#er-add');await (async()=>{if(await page.isVisible('#sheet.on'))await page.click('#sheet-acts >> text=Bestand op de telefoon');})();await page.waitForFunction(()=>!erBusy);ok(await page.locator('#er-status').isHidden(),'import annuleren');
  await page.evaluate(()=>{
   const first=Array(45).fill('De ochtendzon viel door de ramen. Buiten kwam de stad langzaam tot leven. Er was vandaag alle tijd om iets nieuws te ontdekken.').join('\n\n')+'\n<img src=x onerror=alert(1)>';
   Android._erBooks=[{id:'a',title:'Instellingen',author:'Opslaan',chapters:['Een begin','Het kompas','Thuiskomen'],chapter:0,offset:0,last:20,format:'EPUB'},{id:'b',title:'Een reis langs zee',author:'Anna van Dijk',chapters:['De kust'],chapter:0,offset:0,last:10,format:'TXT'}];
   Android._erTexts.a=[first,'Een kompas wijst de weg. Een nieuwe ontmoeting bleef ons lang bij.','We kwamen weer thuis.'];Android._erTexts.b=['Een verhaal aan zee.'];enterEreader();
  });
  ok(await page.locator('#er-list .er-card').count()===2,'boeken met omslagen in de kast');
  await page.evaluate(()=>{Android._erBooks[1].cover=true;Android._erCovers={b:'https://example.com/geen-omslag.jpg'};enterEreader();});
  ok(await page.evaluate(()=>!document.querySelector('#er-list img[data-er-cover]').src),'omslagen laden nooit een externe URL');
  await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=4;canvas.height=6;canvas.getContext('2d').fillRect(0,0,4,6);Android._erCovers.b=canvas.toDataURL('image/jpeg');delete erCoverCache.b;erRender();});
  await page.waitForFunction(()=>document.querySelector('#er-list img[data-er-cover]').src.startsWith('data:image/jpeg;base64,'));
  ok(await page.locator('#er-list img[data-er-cover]').isVisible(),'lokale JPEG-omslag wordt getoond');

  await page.locator('#er-list .er-favorite').first().click();await page.click('#er-filters [data-filter=favorite]');ok(await page.locator('#er-list .er-card').count()===1,'favoriet bewaren en filteren');
  await page.click('#er-filters [data-filter=all]');await page.selectOption('#er-sort','title');ok((await page.locator('#er-list .er-book-info b').first().innerText())==='Een reis langs zee','op titel sorteren');
  await page.click('#er-layout');ok(await page.locator('#er-list .er-list').count()===1,'lijstweergave');await page.click('#er-layout');
  await page.fill('#er-search','onbekend');await page.waitForTimeout(400);ok((await page.locator('#er-list').innerText()).includes('Geen boeken'),'zoeken zonder resultaat');await page.fill('#er-search','Opslaan');await page.waitForTimeout(400);
  await page.locator('#er-list .er-book-open').click();
  ok(await page.evaluate(()=>current==='erbook'&&!document.querySelector('#er-content img')&&document.body.classList.contains('er-reading')),'veilig schermvullend lezen, boek-HTML blijft tekst');
  ok(await page.evaluate(()=>document.body.classList.contains('er-immersive')&&!document.body.classList.contains('er-controls')&&Android._erWin.full===true)&&!(await page.locator('#er-next').isVisible()),'boek opent op het volle scherm zonder knoppen');
  await page.evaluate(()=>erControls(true));
  ok(await page.locator('#er-prev').isDisabled()&&await page.locator('#er-next').isEnabled(),'eerste pagina en navigatiegrenzen');
  await page.click('#er-next');await page.waitForFunction(()=>erAnchor()>0);const anchor=await page.evaluate(()=>{erSave();return erAnchor();});
  ok(anchor>0&&(await page.locator('#er-pages').innerText()).includes('Pagina 2'),'bladzijde omslaan');
  await page.click('#er-bookmark');ok(await page.evaluate(()=>erBook.annotations.some(a=>a.kind==='bookmark')),'bladwijzer bewaren');
  ok(await page.evaluate(()=>{const before=JSON.stringify(erBook.annotations),original=Android.ereaderAnnotation;Android.ereaderAnnotation=()=>JSON.stringify({error:'Opslaan mislukt'});erBookmark();Android.ereaderAnnotation=original;return JSON.stringify(erBook.annotations)===before;}),'opslagfout bewaart bestaande leeswijzers');

  await page.click('#er-style');ok(await page.locator('#er-quick').isVisible(),'Aa opent de snelle leesweergave');await page.locator('#er-quick .er-q-actions .ghost').click();ok(await page.evaluate(()=>current==='erset'&&erBook.id==='a'),'leesweergave opent zonder het boek te verliezen');
  await page.selectOption('#er-size','32');await page.selectOption('#er-line','2');await page.selectOption('#er-margin','44');await page.selectOption('#er-font','sans-serif');await page.selectOption('#er-theme','sepia');
  await page.evaluate(()=>goBack());ok(await page.evaluate(a=>Math.abs(erAnchor()-a)<180&&erStyle().size===32&&document.getElementById('s-erbook').dataset.erTheme==='sepia',anchor),'letters, regelafstand, marges en thema veranderen met dezelfde leesplek');
  await page.evaluate(()=>goBack());ok(await page.locator('#er-continue').isVisible(),'verder-lezenkaart na sluiten');
  await page.locator('#er-continue button.er-continue').click();ok(await page.evaluate(a=>Math.abs(erAnchor()-a)<180,anchor),'heropenen op dezelfde tekst');
  ok(!(await page.locator('.er-toolbar').isVisible())&&await page.evaluate(()=>document.body.classList.contains('er-immersive')),'heropend boek leest weer rustig op het volle scherm');
  await page.evaluate(()=>erBrowse('marks'));ok((await page.locator('#er-browse-list').innerText()).includes('Bladwijzer'),'bladwijzers terugvinden');await page.locator('.er-mark-open').click();ok(await page.evaluate(a=>Math.abs(erAnchor()-a)<180,anchor),'bladwijzer springt naar tekst');
  await page.evaluate(()=>erChapter(0,0));
  await page.evaluate(()=>{const start=erText.indexOf('ochtendzon'),a=erPoint(start),b=erPoint(start+10),range=document.createRange();range.setStart(a.node,a.offset);range.setEnd(b.node,b.offset);const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);erReadSelection();});
  await page.locator('#er-selection button').last().click();await page.fill('#modal-input','Instellingen');await page.click('#modal-ok');
  ok(await page.locator('#er-content mark[data-er-ann]').count()>0&&await page.evaluate(()=>erBook.annotations.some(x=>x.note==='Instellingen')),'tekst markeren met eigen notitie');
  await page.evaluate(()=>erBrowse('marks'));ok((await page.locator('#er-browse-list').innerText()).includes('Instellingen'),'notitie staat bij de markering');
  const noteId=await page.evaluate(()=>erBook.annotations.find(x=>x.kind==='highlight').id);
  await page.evaluate(id=>erEditMark(id),noteId);await page.fill('#modal-input','Mijn eigen notitie');await page.click('#modal-ok');ok((await page.locator('#er-browse-list').innerText()).includes('Mijn eigen notitie'),'notitie bewerken');
  await page.click('#er-tabs [data-tab=search]');await page.fill('#er-find','KOMPAS');await page.waitForFunction(()=>document.getElementById('er-browse-list').querySelectorAll('button').length===1);
  ok((await page.locator('#er-find-status').innerText()).includes('1 resultaat'),'zoeken door het hele boek, ook andere hoofdstukken');
  await page.evaluate(()=>onEreaderSearch({id:'a',request:'oude-vraag',hits:[{chapter:0,start:0,end:5,excerpt:'verouderd antwoord'}]}));ok(!(await page.locator('#er-browse-list').innerText()).includes('verouderd'),'late zoekresultaten worden genegeerd');
  await page.locator('#er-browse-list button').click();ok(await page.evaluate(()=>current==='erbook'&&erIndex===1)&&await page.locator('.er-match').count()>0,'zoekresultaat opent en markeert de juiste tekst');
  await page.evaluate(()=>{const ordinary=Android.ereaderChapter;window._erOriginalChapter=ordinary;Android.ereaderChapter=()=>JSON.stringify({error:'Hoofdstuk onleesbaar'});erChapter(2);});ok(await page.evaluate(()=>erIndex===1&&erText.includes('kompas')),'leesfout houdt huidig hoofdstuk intact');await page.evaluate(()=>Android.ereaderChapter=window._erOriginalChapter);
  await page.evaluate(()=>{erSeek(1000);erControls(true);});ok(await page.evaluate(()=>erIndex===2),'voortgangsschuif springt door het boek');await page.click('#er-finished');ok(await page.evaluate(()=>erBook.finished),'markeren als uitgelezen');
  await page.evaluate(()=>goBack());await page.fill('#er-search','');await page.waitForTimeout(400);await page.click('#er-filters [data-filter=finished]');ok(await page.locator('#er-list .er-card').count()===1,'uitgelezen boeken apart bekijken');
  await page.click('#er-filters [data-filter=all]');await page.evaluate(()=>erOpen('a'));await page.evaluate(()=>erChapter(0,0));
  await page.evaluate(()=>show('setregio'));await page.selectOption('#rg-lang','en');await page.evaluate(()=>{show('ereader');erOpen('a');});await page.waitForTimeout(200);
  ok((await page.locator('#er-title').innerText())==='Instellingen'&&(await page.locator('#er-author').innerText())==='Opslaan'&&(await page.locator('#er-progress').innerText()).includes('Chapter'),'Engelse bediening, eigen teksten blijven behouden');
  const oldAnchor=await page.evaluate(()=>erAnchor());await page.setViewportSize({width:360,height:780});ok(await page.evaluate(a=>Math.abs(erAnchor()-a)<180&&document.documentElement.scrollWidth<=innerWidth,oldAnchor),'smal scherm en draaien behouden leesplek');
  await page.waitForTimeout(550);await page.screenshot({path:path.join(__dirname,'shots','ereader-reader-'+scheme+'.png')});
  await page.evaluate(()=>{show('erset');erPreference('theme','night');});await page.waitForTimeout(100);ok(await page.evaluate(()=>getComputedStyle(document.getElementById('er-sample')).backgroundColor==='rgb(25, 33, 39)'),'nachtpapier werkt los van het app-thema');
  await page.evaluate(()=>show('erbook'));ok(await page.evaluate(()=>Android._bars[0]==='#192127'&&Android._bars[2]===false),'systeembalken volgen het nachtpapier');
  await page.evaluate(()=>{show('ereader');rgSave({lang:'nl'});erFilter('all');});await page.waitForTimeout(550);await page.screenshot({path:path.join(__dirname,'shots','ereader-library-'+scheme+'.png'),fullPage:true});
  await page.evaluate(()=>{erOpen('a');erBrowse('marks');});await page.evaluate(id=>erRemoveMark(id),noteId);await page.click('#modal-ok');ok(await page.evaluate(id=>!erBook.annotations.some(x=>x.id===id)&&erBook.annotations.some(x=>x.kind==='bookmark'),noteId),'één markering verwijderen bewaart andere leeswijzers');
  await page.evaluate(()=>{show('erbook');erMenu();});await page.locator('#sheet-acts button').last().click();await page.click('#modal-cancel');ok(await page.evaluate(()=>Android._erBooks.length===2),'boek verwijderen annuleren');
  await page.evaluate(()=>erMenu());await page.locator('#sheet-acts button').last().click();await page.click('#modal-ok');ok(await page.evaluate(()=>current==='ereader'&&Android._erBooks.length===1&&!document.body.classList.contains('er-reading')),'boek verwijderen ruimt leesomgeving op');
  await page.close();
 }
 const p=await browser.newPage();p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(()=>window.__testOpen='ereader');await p.goto('file://'+path.join(__dirname,'ui/index.html'));if(!(await p.evaluate(()=>current==='ereader')))errors.push('starten vanuit snelkoppeling');await p.close();
 await browser.close();console.log('errors',JSON.stringify(errors));if(errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
