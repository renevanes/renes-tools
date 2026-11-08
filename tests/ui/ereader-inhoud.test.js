// De Java-test importeert een werkelijk EPUB en levert dezelfde inhoudsboom die Android teruggeeft.
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path');
(async()=>{
 const fixturePath=path.join(__dirname,'../../build/test-data/ereader-rich.json');
 if(!fs.existsSync(fixturePath)){if(process.env.CI)throw Error('EreaderContentTest moet eerst draaien');console.log('Java-inhoud ontbreekt; eerst tests/run-jvm.sh draaien. errors []');return;}
 const fixture=JSON.parse(fs.readFileSync(fixturePath)),browser=await chromium.launch(),errors=[];
 for(const scheme of ['light','dark']){
  const page=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme}),requests=[];page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});
  const ok=(value,message)=>{console.log((value?'✓ ':'✗ ')+scheme+': '+message);if(!value)errors.push(scheme+': '+message);};
  await page.goto('file://'+path.join(__dirname,'ui/index.html'));await page.waitForTimeout(200);
  await page.evaluate(f=>{
   window._erFixture=f;const original=Android.ereaderChapter;Android.ereaderChapter=(id,ch)=>id==='rich'?JSON.stringify(f.content[ch]):original.call(Android,id,ch);
   Android._erBooks=[{id:'rich',title:f.title,author:'De tekenaar',language:f.language,chapters:f.chapters,linear:f.linear,toc:f.toc,edition:3,format:'EPUB',chapter:0,offset:0,last:1}];
   Android._erTexts.rich=f.content.map(c=>c.text);Android._erImages=Object.fromEntries(f.images.map((image,i)=>['rich-'+i,image]));show('ereader');erOpen('rich');
  },fixture);
  await page.waitForFunction(()=>document.querySelector('#er-content img')?.naturalWidth>0);
  ok(await page.evaluate(()=>!erText.length&&erBook.chapter===0&&document.querySelector('#er-content img').naturalWidth>0),'beeldpagina met SVG en ingesloten PNG blijft zichtbaar');
  await page.locator('#er-content .er-media').first().click();ok(await page.locator('#er-image').isVisible()&&await page.evaluate(()=>document.activeElement.id==='er-image-close'),'afbeelding opent in toegankelijke vergrootweergave');
  await page.click('#er-image-more');await page.click('#er-image-more');ok(await page.evaluate(()=>erZoom.scale===2),'inzoomen');
  await page.evaluate(()=>goBack());await page.locator('#er-content .er-media').first().focus();await page.keyboard.press('Space');ok(await page.locator('#er-image').isVisible(),'spatie activeert een gefocuste afbeelding');await page.evaluate(()=>goBack());ok(await page.evaluate(()=>current==='erbook'&&!document.querySelector('#er-image.on')),'terug sluit eerst de afbeelding');
  await page.evaluate(()=>erChapter(1));ok(await page.evaluate(()=>erIndex===1&&document.querySelector('#er-content h1')&&document.querySelector('#er-content strong')&&document.querySelector('#er-content em')),'hoofdstuk met echte koppen, vet en cursief');
  await page.waitForFunction(()=>[...document.querySelectorAll('#er-content img')].some(i=>i.naturalWidth===640));
  ok(await page.evaluate(()=>document.querySelector('#er-content pre').textContent.includes('  één\n    twee  drie')&&document.querySelector('#er-content ol').start===3&&document.querySelector('#er-content table th').scope==='col'&&document.querySelector('#er-content sub').textContent==='2'),'codewitruimte, lijstnummering, tabelkoppen en subscript');
  ok(await page.evaluate(()=>!window.bookRan&&!document.querySelector('#er-content script,#er-content iframe')&&getComputedStyle(document.querySelector('#er-content h1')).textAlign==='center'&&!document.querySelector('#er-content [style*="url("]')),'veilige opmaak zonder boekcode of externe CSS');
  await page.evaluate(()=>{const link=document.querySelector('#er-content a[role="doc-noteref"]');link.click();});
  ok(await page.evaluate(()=>!erLinear(erBook,erIndex)&&erText.includes('Dit is de uitleg')&&erLinks.length===1),'voetnoot opent ook buiten de leesvolgorde');
  const before=await page.evaluate(()=>erPercent(erBook));await page.click('#er-link-back');
  ok(await page.evaluate(n=>erIndex===1&&erLinks.length===0&&Math.abs(erPercent(erBook)-n)<=2,before),'terug naar tekst zonder voortgang van de voetnoot mee te tellen');
  await page.evaluate(()=>{const link=[...document.querySelectorAll('#er-content a')].find(a=>a.textContent==='website');link.click();});ok(await page.locator('#modal.on').count()===1&&requests.length===0,'website pas openen na expliciete keuze');await page.click('#modal-cancel');
  await page.evaluate(()=>{erChapter(1,0);const start=erText.indexOf('bijzonder'),a=erPoint(start),b=erPoint(start+9),range=document.createRange();range.setStart(a.node,a.offset);range.setEnd(b.node,b.offset);getSelection().removeAllRanges();getSelection().addRange(range);erReadSelection();});
  ok(await page.evaluate(()=>erText.slice(erSelection.start,erSelection.end)==='bijzonder'),'selectie houdt exacte bronpositie tussen opmaakelementen');await page.locator('#er-selection button').first().click();
  ok(await page.evaluate(()=>document.querySelector('#er-content strong mark').textContent==='bijzonder'&&erBook.annotations[0].text==='bijzonder'),'markeren bewaart opmaak en geselecteerde tekst');
  await page.evaluate(()=>erBrowse('chapters'));ok((await page.locator('.er-chapter-row b').allTextContents()).join('|')==='De reis begint|Een nadere blik|Thuiskomst','echte geneste inhoudsopgave met eigen labels');await page.locator('.er-chapter-row').nth(1).click();
  ok(await page.evaluate(()=>erIndex===1&&document.querySelector('[data-er-id="deel twee"]').getBoundingClientRect().top>=document.querySelector('#er-text').getBoundingClientRect().top-5),'inhoudsopgave springt naar paragraafanker');
  await page.evaluate(()=>{erChapter(1,0);erRestoreAnchor(erText.indexOf('De ochtendzon'));erSave();});const anchor=await page.evaluate(()=>erAnchor());
  await page.evaluate(()=>{show('erset');erPreference('size',32);erPreference('theme','night');show('erbook');});
  ok(await page.evaluate(a=>Math.abs(erAnchor()-a)<150&&erStyle().size===32,anchor),'leesplek blijft bij andere letters en nachtthema');
  await page.evaluate(()=>{erPreference('size',22);erPreference('theme','paper');erChapter(1,0);});await page.waitForFunction(()=>[...document.querySelectorAll('#er-content img')].some(i=>i.naturalWidth));await page.waitForTimeout(550);await page.screenshot({path:path.join(__dirname,'shots','ereader-inhoud-'+scheme+'.png')});
  await page.evaluate(()=>{erChapter(2,0);});ok(await page.locator('#er-content math mfrac').count()===1,'MathML-inhoud blijft beschikbaar');await page.evaluate(()=>erTurn(1));ok(await page.evaluate(()=>erIndex===2),'normaal bladeren slaat losse noten en navigatiedocumenten over');
  await page.evaluate(()=>{erChapter(0);});await page.waitForFunction(()=>document.querySelector('#er-content img')?.naturalHeight>document.querySelector('#er-content img')?.naturalWidth);await page.evaluate(()=>{document.querySelector('#er-text').scrollTop=500;erPendingLocation=null;erSave();erBookmark();});const offset=await page.evaluate(()=>erOffset());
  await page.evaluate(()=>{show('erset');show('erbook');});ok(await page.evaluate(n=>Math.abs(erOffset()-n)<.02,offset),'beeldpagina bewaart scrollpositie zonder tekstanker');
  await page.evaluate(()=>{erChapter(2);erBrowse('marks');});await page.locator('.er-mark-open').filter({hasText:'Bladwijzer'}).click();await page.waitForFunction(()=>document.querySelector('#er-content img')?.naturalHeight>document.querySelector('#er-content img')?.naturalWidth);ok(await page.evaluate(n=>erIndex===0&&Math.abs(erOffset()-n)<.03,offset),'bladwijzer bij beeldpagina gebruikt beeldpositie');
  await page.evaluate(()=>{erChapter(0);erFragment('second',0);});await page.waitForFunction(()=>[...document.querySelectorAll('#er-content img')].every(i=>i.naturalWidth>0));
  await page.evaluate(()=>erSave());const fragmentOffset=await page.evaluate(()=>erOffset());await page.evaluate(()=>{show('erset');show('erbook');});
  ok(await page.evaluate(n=>n>.1&&Math.abs(erOffset()-n)<.03&&Math.abs(erBook.offset-n)<.03,fragmentOffset),'fragmentsprong naar tweede beeld bewaart de zichtbare positie');
  await page.evaluate(()=>{show('ereader');Android._erBooks[0].edition=2;erOpen('rich');});ok(await page.locator('#er-upgrade').isVisible(),'oude tekstimport krijgt uitleg om bron opnieuw te kiezen');
  await page.evaluate(()=>{erSave();Android._erPick={cancelled:true};erUpgrade();});await page.waitForFunction(()=>!erBusy);ok(await page.evaluate(()=>!erUpgrading&&erBook.edition===2),'upgrade annuleren behoudt leesgegevens');
  await page.evaluate(()=>{
   const original=Android.ereaderPick;Android.ereaderPick=()=>setTimeout(()=>{const b=Android._erBooks[0];b.edition=3;b.chapter=1;b.anchor=15;window._erSavedAnchor=b.anchor;onEreaderImport({book:JSON.parse(JSON.stringify(b))});Android.ereaderPick=original;},80);erUpgrade();
  });await page.waitForFunction(()=>!erBusy);ok(await page.evaluate(()=>erBook.edition===3&&erIndex===1&&document.querySelector('#er-upgrade').hidden),'herimport opent nieuwe inhoud zonder oude hoofdstukpositie terug te schrijven');
  await page.evaluate(()=>{show('setregio');rgSave({lang:'en'});show('ereader');erOpen('rich');});await page.waitForTimeout(150);
  ok(await page.evaluate(()=>document.querySelector('#er-content h1').textContent.includes('Een reis')&&document.querySelector('#er-title').textContent==='Een boek met beelden'),'boektekst blijft ongewijzigd bij Engelse bediening');
  await page.evaluate(()=>{erChapter(2);document.querySelector('#er-text').scrollTop=1800;erSave();});const tablePos=await page.evaluate(()=>document.querySelector('#er-text').scrollTop);
  await page.evaluate(()=>{show('erset');show('erbook');});ok(await page.evaluate(n=>Math.abs(document.querySelector('#er-text').scrollTop-n)<80,tablePos),'leesplek tussen ongelijke tabelkolommen blijft op dezelfde regel');
  await page.evaluate(()=>{const notes=erBook.chapters.findIndex((_,i)=>!erLinear(erBook,i)&&_erFixture.content[i].text.includes('Dit is de uitleg'));erChapter(notes);});await page.waitForFunction(()=>document.querySelector('#er-content img')?.naturalHeight>500);
  await page.evaluate(()=>{document.querySelector('#er-text').scrollTop=400;erPendingLocation=null;erSave();});const imagePos=await page.evaluate(()=>({offset:erOffset(),anchor:erAnchor(),chapter:erIndex}));
  await page.evaluate(()=>{show('ereader');erOpen('rich');});await page.waitForFunction(()=>document.querySelector('#er-content img')?.naturalHeight>500);
  ok(await page.evaluate(p=>erIndex===p.chapter&&p.anchor===-1&&Math.abs(erOffset()-p.offset)<.03,imagePos),'lange afbeelding met bijschrift heropent midden in het beeld');
  ok(requests.length===0,'alle afbeeldingen en boekonderdelen werken zonder netwerk');await page.close();
 }
 await browser.close();console.log('errors',JSON.stringify(errors));if(errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exit(1);});
