// E-reader lezen: volle scherm, knoppen met een tik in het midden, terug, snelle leesweergave (helderheid, thema,
// prettig voor je ogen, tekstgrootte, leesmodus), scherm aan, volumeknoppen en voorlezen met de nagebootste stem.
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path');
const SHOTS=process.env.ER_SHOTS||path.join(__dirname,'shots');
(async()=>{
 fs.mkdirSync(SHOTS,{recursive:true});
 const browser=await chromium.launch(),errors=[];
 const setup=async(page,scheme)=>{
  await page.goto('file://'+path.join(__dirname,'ui/index.html'));await page.waitForTimeout(250);
  await page.evaluate(()=>{
   const one=Array.from({length:60},(_,i)=>'Zin nummer '+(i+1)+' gaat over de zee en de wind die waait. Buiten kwam de stad langzaam tot leven.').join(' ')+'\n\nEinde van het eerste deel.';
   Android._erBooks=[{id:'z',title:'Een reis langs zee',author:'Anna van Dijk',language:'nl',chapters:['Vertrek','Aankomst'],chapter:0,offset:0,last:5,format:'EPUB',edition:3}];
   Android._erTexts.z=[one,'De haven lag stil. Er was niemand te zien. We stapten aan wal.'];
   store.set('erLezen',{});store.set('erSpeak',{});store.set('erStyle',{});
   show('ereader');
  });
 };
 const middle=async page=>{const b=await page.locator('#er-text').boundingBox();await page.mouse.click(b.x+b.width/2,b.y+b.height/2);await page.waitForTimeout(60);};
 const side=async(page,right)=>{const b=await page.locator('#er-text').boundingBox();await page.mouse.click(b.x+b.width*(right?.9:.1),b.y+b.height/2);await page.waitForTimeout(60);};
 const tick=async(page,n=1)=>{for(let i=0;i<n;i++){await page.evaluate(()=>Android._spkTick());await page.waitForTimeout(25);}};
 const spoken=page=>page.evaluate(()=>{const h=CSS.highlights.get('er-speak');if(!h)return null;const r=[...h][0],box=document.querySelector('#er-text').getBoundingClientRect(),rect=r.getClientRects()[0];return{text:r.toString(),inView:!!rect&&rect.top>=box.top-2&&rect.bottom<=box.bottom+2};});

 for(const scheme of ['light','dark']){
  const page=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});page.on('pageerror',e=>errors.push(e.message));
  const ok=(v,m)=>{console.log((v?'✓ ':'✗ ')+scheme+': '+m);if(!v)errors.push(scheme+': '+m);};
  const shot=async name=>{await page.waitForTimeout(200);await page.screenshot({path:path.join(SHOTS,'ereader-lezen-'+name+'-'+scheme+'.png')});};
  await setup(page,scheme);
  const theme=scheme==='dark'?'night':'paper';
  await page.evaluate(t=>store.set('erStyle',{theme:t}),theme);

  // ---------- volle scherm ----------
  await page.locator('#er-list .er-book-open').click();
  ok(await page.evaluate(()=>{const w=Android._erWin;return current==='erbook'&&w.full===true&&w.awake===true&&w.bright===-1&&w.keys===false;}),'boek openen: systeembalken weg, scherm aan, helderheid zoals de telefoon');
  ok(await page.evaluate(()=>document.body.classList.contains('er-immersive'))&&!(await page.locator('.er-toolbar').isVisible())&&!(await page.locator('.er-footer').isVisible())&&!(await page.locator('#s-erbook .hrow').isVisible()),'kop, werkbalk en voet verborgen');
  await shot('verborgen');
  const top0=await page.evaluate(()=>document.querySelector('#er-text').getBoundingClientRect().top);
  await middle(page);ok(await page.locator('.er-toolbar').isVisible()&&await page.locator('.er-footer').isVisible()&&await page.locator('#er-style').isVisible(),'tik in het midden toont de knoppen');
  ok(await page.evaluate(t=>Math.abs(document.querySelector('#er-text').getBoundingClientRect().top-t)<1,top0),'knoppen liggen over de tekst: de tekst verspringt niet');
  await shot('knoppen');
  await middle(page);ok(!(await page.locator('.er-toolbar').isVisible()),'nog een tik verbergt ze weer');
  await side(page,true);const a1=await page.evaluate(()=>erAnchor());ok(a1>0,'tik rechts: volgende pagina');
  await side(page,false);ok(await page.evaluate(()=>erAnchor()===0),'tik links: vorige pagina');
  await side(page,true);await side(page,true);
  ok(await page.evaluate(()=>{const before=erAnchor();onEreaderKey(-1);const back=erAnchor();onEreaderKey(1);return back<before&&erAnchor()>back;}),'volumeknoppen (via Android) bladeren');

  // ---------- terug ----------
  await middle(page);await page.click('#er-style');ok(await page.locator('#er-quick').isVisible()&&await page.evaluate(()=>document.activeElement.closest('#er-quick')!==null),'Aa opent de snelle leesweergave met de focus erin');
  await page.evaluate(()=>goBack());ok(await page.evaluate(()=>current==='erbook'&&!document.querySelector('#er-quick.on')),'terug sluit eerst het paneel');
  await page.evaluate(()=>goBack());
  ok(await page.evaluate(()=>current==='ereader'&&JSON.stringify(Android._erWin)==='{}'&&!document.body.classList.contains('er-immersive')),'daarna terug naar de boekenkast: balken, helderheid en scherm weer normaal');
  await page.locator('#er-continue button.er-continue').click();ok(await page.evaluate(()=>!document.body.classList.contains('er-controls')&&Android._erWin.full===true),'terug in het boek: weer rustig op het volle scherm');

  // ---------- snelle leesweergave ----------
  await page.evaluate(()=>erControls(true));await page.click('#er-style');
  await page.click('#er-bright-auto');ok(await page.evaluate(()=>Android._erWin.bright===.5&&!document.querySelector('#er-bright').disabled),'helderheid zelf kiezen');
  await page.locator('#er-bright').fill('30');ok(await page.evaluate(()=>Android._erWin.bright===.3&&erLezen().bright===.3)&&(await page.locator('#er-bright-val').innerText()).includes('30'),'schuifje zet de helderheid van dit venster');
  ok(await page.evaluate(()=>document.querySelector('#er-bright').labels.length===1&&!!document.querySelector('#er-bright').getAttribute('aria-valuetext')),'schuifje heeft een naam en een waarde');
  await page.click('#er-q-theme [data-v=sepia]');
  ok(await page.evaluate(()=>document.getElementById('s-erbook').dataset.erTheme==='sepia'&&Android._bars[0].toUpperCase()==='#F3E8D2'&&document.querySelector('#er-q-theme [data-v=sepia]').getAttribute('aria-checked')==='true'),'thema Sepia, ook in de systeembalken');
  await page.click('#er-q-theme [data-v='+theme+']');
  await page.click('#er-warm-on');
  ok(await page.evaluate(()=>{const w=document.getElementById('er-warm'),cs=getComputedStyle(w);return !w.hidden&&cs.mixBlendMode==='multiply'&&Math.abs(+cs.opacity-.3)<.01;}),'prettig voor je ogen: warme laag over het leesvlak');
  ok(await page.evaluate(()=>{const paper=getComputedStyle(document.getElementById('s-erbook')).getPropertyValue('--er-paper').trim();return Android._bars[0].toLowerCase()!==paper.toLowerCase();}),'de balken kleuren mee');
  await page.locator('#er-warm-level').fill('50');ok(await page.evaluate(()=>Math.abs(+getComputedStyle(document.getElementById('er-warm')).opacity-.5)<.01),'sterkte van het filter');
  await page.click('#er-warm-auto');ok(await page.evaluate(()=>erLezen().warmAuto&&document.getElementById('er-warm-auto-s').textContent.length>10),'vanzelf \'s avonds');await page.click('#er-warm-auto');
  await shot('paneel');
  await page.evaluate(()=>{erPanel('');document.querySelector('#er-text').scrollTop=0;erTurn(1);erTurn(1);erSave();});
  const anchor=await page.evaluate(()=>erAnchor());await page.evaluate(()=>erPanel('quick'));
  const size0=await page.evaluate(()=>erStyle().size);await page.click('#er-q-bigger');await page.click('#er-q-bigger');
  ok(await page.evaluate(([a,s])=>erStyle().size>s&&Math.abs(erAnchor()-a)<180,[anchor,size0]),'A+ maakt de letters groter op dezelfde leesplek');
  ok((await page.locator('#er-q-size').innerText()).includes('%'),'tekstgrootte zichtbaar als percentage');
  await page.click('#er-q-smaller');ok(await page.evaluate(([a,s])=>erStyle().size>s&&Math.abs(erAnchor()-a)<180,[anchor,size0]),'A− een stap kleiner, zelfde leesplek');
  await page.click('#er-q-mode [data-v=scroll]');ok(await page.evaluate(a=>erStyle().mode==='scroll'&&Math.abs(erAnchor()-a)<180,anchor),'leesmodus doorlopend op dezelfde leesplek');
  await page.click('#er-q-mode [data-v=pages]');
  await page.evaluate(()=>erPanel(''));
  await page.evaluate(()=>goBack());ok(await page.evaluate(()=>JSON.stringify(Android._erWin)==='{}'&&document.getElementById('er-warm').hidden),'weg uit het boek: helderheid en filter terug');
  await page.locator('#er-continue button.er-continue').click();ok(await page.evaluate(()=>Android._erWin.bright===.3&&!document.getElementById('er-warm').hidden),'terug in het boek: eigen helderheid en filter weer aan');

  // ---------- scherm aan en andere instellingen ----------
  await page.evaluate(()=>show('erset'));await page.waitForTimeout(80);
  ok(await page.evaluate(()=>JSON.stringify(Android._erWin)==='{}'),'instellingen open: scherm mag weer uit');
  await page.click('#er-awake');await page.click('#er-keys');
  ok(await page.evaluate(()=>!erLezen().awake&&erLezen().keys&&document.getElementById('er-awake').getAttribute('aria-checked')==='false'),'scherm aan en volumeknoppen instellen');
  await page.evaluate(()=>goBack());ok(await page.evaluate(()=>current==='erbook'&&Android._erWin.awake===false&&Android._erWin.keys===true),'instellingen gelden meteen in het boek');
  ok(await page.evaluate(()=>{erPanel('quick');return true;})&&await page.waitForFunction(()=>Android._erWin.keys===false).then(()=>true),'paneel open: volumeknoppen weer voor het volume');
  await page.evaluate(()=>erPanel(''));await page.waitForFunction(()=>Android._erWin.keys===true);
  await page.evaluate(()=>{askConfirm('Test','', 'OK',()=>{});});await page.waitForFunction(()=>Android._erWin.keys===false);await page.evaluate(()=>closeModal(null));await page.waitForFunction(()=>Android._erWin.keys===true);
  await page.evaluate(()=>document.getElementById('lockscr').classList.add('on'));await page.waitForFunction(()=>Android._erWin.keys===false);
  ok(await page.evaluate(()=>{const a=erAnchor();onEreaderKey(1);return erAnchor()===a;}),'achter het app-slot bladert er niets');
  await page.evaluate(()=>document.getElementById('lockscr').classList.remove('on'));await page.waitForFunction(()=>Android._erWin.keys===true);
  ok(true,'dialoog en app-slot: volumeknoppen terug zodra ze weg zijn');
  await page.evaluate(()=>{erLezenSet('awake',true);erLezenSet('keys',false);});ok(await page.evaluate(()=>Android._erWin.awake===true),'scherm weer aan tijdens het lezen');
  await page.evaluate(()=>show('erset'));await page.click('#er-full');await page.evaluate(()=>goBack());
  ok(await page.evaluate(()=>!document.body.classList.contains('er-immersive')&&Android._erWin.full===false)&&await page.locator('.er-toolbar').isVisible(),'volle scherm uit: gewone weergave met knoppen');
  await page.evaluate(()=>erLezenSet('full',true));

  // ---------- voorlezen ----------
  await page.evaluate(()=>{document.querySelector('#er-text').scrollTop=0;erTurn(1);erTurn(1);erSave();erControls(true);});
  const from=await page.evaluate(()=>erAnchor());await page.click('#er-speak');await page.waitForTimeout(60);
  ok(await page.evaluate(a=>{const o=Android._spkLog.filter(x=>x[0]==='speak').pop()[1];return o.id==='z'&&o.chapter===0&&o.anchor===a&&o.lang==='nl';},from),'voorlezen begint op de huidige pagina, in de taal van het boek');
  let h=await spoken(page);
  ok(h&&h.inView&&await page.evaluate(a=>{const s=Android._spk;return s.start<=a&&s.end>a&&erText.slice(s.start,s.end)===CSS.highlights.get('er-speak').values().next().value.toString();},from),'de eerste zichtbare zin licht op (ook als die op de vorige pagina begon)');
  ok(await page.locator('#er-tts').isVisible()&&await page.locator('#er-tts-play').getAttribute('aria-label')==='Pauzeren','voorleesbalk met pauzeknop');
  await page.evaluate(()=>erControls(false));await shot('voorlezen');
  const scroll0=await page.evaluate(()=>document.querySelector('#er-text').scrollTop);let follow=true;
  for(let i=0;i<14;i++){await tick(page);h=await spoken(page);if(!h||!h.inView)follow=false;}
  ok(follow&&await page.evaluate(s=>document.querySelector('#er-text').scrollTop>s,scroll0),'bladert mee: de zin blijft in beeld');
  await page.evaluate(()=>{Android._spkIdx=Android._spkSentences.length-1;Android._spkMark();});await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>erIndex===1&&Android._spk.chapter===1&&erText.startsWith('De haven')),'gaat door naar het volgende hoofdstuk');
  h=await spoken(page);ok(h&&h.text==='De haven lag stil.','eerste zin van het nieuwe hoofdstuk licht op');
  await tick(page);await page.click('#er-tts-play');await page.waitForTimeout(40);
  await page.waitForTimeout(250);ok(await page.evaluate(()=>{const b=Android._erBooks[0],s=Android._spk;return s.state==='paused'&&b.chapter===1&&b.anchor<=s.start&&erSpeakPausedHere();})&&await page.locator('#er-tts-play').getAttribute('aria-label')==='Verder voorlezen','pauzeren bewaart de leesplek (de pagina met de zin)');
  await page.click('#er-tts-play');await page.click('#er-tts-prev');await page.waitForTimeout(40);ok(await page.evaluate(()=>Android._spk.start===0&&Android._spk.state==='playing'),'vorige zin');
  await page.click('#er-tts-next');await page.waitForTimeout(40);ok(await page.evaluate(()=>Android._spk.start>0),'volgende zin');
  await page.click('#er-tts-rate');await page.waitForTimeout(40);
  ok(await page.evaluate(()=>Android._spkLog.some(x=>x[0]==='rate'&&x[1]==='1.25')&&erSpeakPrefs().rate===1.25)&&(await page.locator('#er-tts-rate').innerText()).replace(',','.')==='1.25×','snelheid 1,25×');
  await page.click('#er-tts-more');await page.waitForFunction(()=>erVoices);
  ok(await page.evaluate(()=>document.querySelectorAll('#er-tts-voice option').length===3&&document.querySelectorAll('#er-tts-rates [role=radio]').length===6&&document.querySelector('#er-tts-rates [aria-checked=true]').dataset.v==='1.25'),'stemmen op de telefoon en snelheden in het paneel');
  await page.selectOption('#er-tts-voice','nl-nl-x-b-local');await page.waitForTimeout(40);
  ok(await page.evaluate(()=>{const o=Android._spkLog.filter(x=>x[0]==='speak').pop()[1];return o.voice==='nl-nl-x-b-local'&&o.chapter===1&&Android._spk.state==='playing';}),'andere stem: verder met dezelfde zin');
  await page.click('#er-tts-sleep [data-v="15"]');await page.waitForTimeout(40);
  ok(await page.evaluate(()=>Android._spk.sleepAt>Date.now()+14*60000)&&(await page.locator('#er-tts-status').innerText()).includes('Stopt om'),'slaaptimer 15 minuten');
  await page.evaluate(()=>{Android._spk.sleepAt=Date.now()-1;});await tick(page);
  ok(await page.evaluate(()=>Android._spk.state==='paused'&&Android._erBooks[0].anchor===Android._spk.start),'slaaptimer loopt af: pauze, leesplek bewaard');
  await page.evaluate(()=>{erSpeakCmd('resume');});await page.click('#er-tts-sleep [data-v=chapter]');await page.waitForTimeout(40);
  ok((await page.locator('#er-tts-status').innerText()).includes('hoofdstuk'),'slaaptimer einde hoofdstuk');
  await page.evaluate(()=>{erPanel('');erChapter(0,0);Android._spkLoad(0,0);Android._spkIdx=Android._spkSentences.length-1;Android._spkMark();});await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>Android._spk.state==='paused'&&Android._spk.chapter===1&&Android._spk.start===0),'einde hoofdstuk: pauze aan het begin van het volgende');
  await page.evaluate(()=>{Android._spkNoVoice=['en'];erPanel('tts');});await page.selectOption('#er-tts-lang','en');await page.waitForSelector('#modal.on');
  ok((await page.locator('#modal-title').innerText()).includes('Geen stem'),'geen stem voor de taal: korte uitleg');
  await page.click('#modal-ok');ok(await page.evaluate(()=>Android._ttsSettings==='install'),'knop naar tekst-naar-spraak van Android');
  await page.waitForFunction(()=>erVoices);ok(await page.locator('#er-tts-add').isVisible(),'ook in het paneel: stem toevoegen');
  await page.selectOption('#er-tts-lang','');await page.evaluate(()=>{erPanel('');Android._spkNoVoice=[];erSpeakStart();});await page.waitForTimeout(60);
  await page.evaluate(()=>goBack());await page.waitForTimeout(60);
  ok(await page.evaluate(()=>current==='ereader'&&Android._spk.state==='playing')&&await page.locator('#er-speaking').isVisible(),'weg uit het boek: voorlezen gaat door, zichtbaar in de boekenkast');
  await page.locator('#er-speaking [data-icon=chevron-right]').click();await page.waitForTimeout(60);ok(await page.evaluate(()=>current==='erbook'&&erBook.id==='z'&&erIndex===Android._spk.chapter),'terug naar het boek dat wordt voorgelezen');
  await page.evaluate(()=>openTool('ereader-lezen'));ok(await page.evaluate(()=>current==='erbook'),'melding openen houdt het boek open');
  await page.click('#er-tts-stop');ok(await page.evaluate(()=>Android._spk.state==='stopped'&&!CSS.highlights.get('er-speak'))&&!(await page.locator('#er-tts').isVisible()),'stoppen');

  // ---------- gepauzeerd voorlezen en de leesplek ----------
  await page.evaluate(()=>{erChapter(0,0);erControls(true);});await page.click('#er-speak');await tick(page,2);await page.click('#er-tts-play');await page.waitForTimeout(40);
  ok(await page.evaluate(()=>Android._spk.state==='paused'&&erSpeakPausedHere()),'gepauzeerd, de zin staat nog op de pagina');
  await page.click('#er-speak');await page.waitForTimeout(40);ok(await page.evaluate(()=>Android._spkLog[Android._spkLog.length-1][0]==='resume'&&Android._spk.state==='playing'),'Voorlezen gaat verder bij dezelfde zin');
  await page.click('#er-tts-play');await page.waitForTimeout(40);await page.evaluate(()=>{erTurn(1);erTurn(1);erTurn(1);});await page.waitForTimeout(300);
  ok(await page.evaluate(()=>Android._erBooks[0].anchor===erAnchor()&&erAnchor()>Android._spk.end),'gepauzeerd en verder gebladerd: de gewone leesplek wordt bewaard');
  const seen=await page.evaluate(()=>erAnchor());await page.click('#er-speak');await page.waitForTimeout(40);
  ok(await page.evaluate(a=>{const l=Android._spkLog[Android._spkLog.length-1];return l[0]==='speak'&&l[1].anchor===a&&Android._spk.start<=a&&Android._spk.end>a;},seen),'Voorlezen begint dan bij de zichtbare zin');
  await page.click('#er-tts-stop');

  // ---------- buiten een boek blijft het venster ongemoeid ----------
  await page.evaluate(()=>{goBack();show('ai');Android.aiTalkScreen(true);});const nWin=await page.evaluate(()=>Android._erWinLog.length);
  await page.evaluate(()=>{erLezenSet('bright',.4);erLezenSet('awake',false);erLezenSet('awake',true);setEntry('ereader.helderheid').set('telefoon');setEntry('ereader.volscherm').set('aan');});
  ok(await page.evaluate(n=>Android._erWinLog.length===n&&Android._tkScreen===true,nWin),'instellingen aanpassen buiten een boek (bijv. tijdens een gesprek met de assistent) raakt scherm aan, balken en helderheid niet');
  await page.evaluate(()=>{Android.aiTalkScreen(false);show('ereader');erOpen('z');});

  // ---------- assistent en zoeken ----------
  ok(await page.evaluate(()=>{const id=q=>{const r=setHandle(q);return r&&r.text||'';};return /Helderheid in de e-reader[\s\S]*50/.test(id('zet de helderheid op 50%'))&&/Helderheid in de e-reader[\s\S]*50/.test(id('zet de helderheid in de e-reader op 50%'))&&/Tekstgrootte in de e-reader[\s\S]*11\d/.test(id('zet de tekstgrootte in de e-reader op 120%'));}),'assistent: helderheid op 50% (ook "in de e-reader") en tekstgrootte in de e-reader op 120%');
  ok(await page.evaluate(()=>{const r=setHandle('zet het volume uit');return !(r&&/volumeknoppen/i.test(r.text||''))&&((setFind('scherm donkerder')[0]||{}).e||{}).id==='weergave.amoled'&&((setFind('tekstgrootte')[0]||{}).e||{}).id==='weergave.tekstgrootte';}),'niet: volume uit, scherm donkerder of de gewone tekstgrootte');
  ok(await page.evaluate(()=>setPath('erset')==='E-reader → Leesweergave'),'pad zonder dubbele stap of boektitel');
  ok(await page.evaluate(()=>[['nachtfilter','ereader.ogen'],['blauw licht','ereader.ogen'],['helderheid','ereader.helderheid'],['voorleessnelheid','ereader.voorleessnelheid'],['scherm aan laten tijdens het lezen','ereader.schermaan']].every(([q,id])=>((setFind(q)[0]||{}).e||{}).id===id)&&((setFind('tekstgrootte')[0]||{}).e||{}).id==='weergave.tekstgrootte'),'assistent vindt helderheid, nachtfilter, voorleessnelheid en scherm aan (tekstgrootte blijft die van de app)');
  ok(await page.evaluate(()=>{const e=setEntry('ereader.helderheid');e.set('25');const r=Android._erWin.bright===.25&&erLezen().bright===.25;e.set('telefoon');return r&&erLezen().bright===-1;}),'helderheid via het register, meteen in het boek');
  ok(await page.evaluate(()=>GS_SYN['e-reader'].includes('voorlezen')&&GS_SYN['e-reader'].includes('nachtfilter')),'zoekwoorden voorlezen en nachtfilter bij de e-reader');

  // ---------- TalkBack ----------
  await page.evaluate(()=>{goBack();Android._talkback=true;erOpen('z');});
  ok(await page.evaluate(()=>!document.body.classList.contains('er-immersive'))&&await page.locator('.er-toolbar').isVisible(),'TalkBack: knoppen blijven altijd zichtbaar');
  await middle(page);ok(await page.locator('.er-toolbar').isVisible(),'TalkBack: een tik verbergt niets');
  await page.evaluate(()=>{goBack();Android._talkback=false;});

  // ---------- smal scherm en Engels ----------
  await page.setViewportSize({width:360,height:740});await page.evaluate(()=>{erOpen('z');erControls(true);erSpeakStart();erPanel('quick');});await page.waitForTimeout(100);
  ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth&&[...document.querySelectorAll('#er-quick button,#er-tts button')].filter(b=>b.getClientRects().length&&!b.classList.contains('tg')).every(b=>{const r=b.getBoundingClientRect();return r.height>=44&&r.width>=44||b.closest('.er-q-actions');})),'360 px: past, tikvlakken minstens 44 px');
  await page.evaluate(()=>{erPanel('');erSpeakCmd('stop');goBack();show('setregio');});await page.selectOption('#rg-lang','en');await page.evaluate(()=>{show('ereader');erOpen('z');erControls(true);erSpeakStart();erPanel('quick');});await page.waitForTimeout(150);
  ok(await page.evaluate(()=>document.getElementById('er-quick-title').textContent==='Reading appearance'&&document.getElementById('er-tts-status').textContent==='Reading aloud'&&document.getElementById('er-title').textContent==='Een reis langs zee'),'Engelse bediening, boektitel blijft');
  await page.evaluate(()=>{erPanel('');erSpeakCmd('stop');goBack();rgSave({lang:'nl'});});
  await page.close();
 }
 await browser.close();console.log('errors',JSON.stringify(errors));if(errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
