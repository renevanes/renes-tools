// E-reader lezen: volle scherm, knoppen met een tik in het midden, terug, snelle leesweergave (helderheid, thema,
// prettig voor je ogen, tekstgrootte, leesmodus), scherm aan, volumeknoppen en voorlezen met de gedeelde lokale stem (nagebootst).
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
   store.set('erLezen',{});store.set('erStyle',{});Android._speech.ms=60000;
   show('ereader');
  });
 };
 const middle=async page=>{const b=await page.locator('#er-text').boundingBox();await page.mouse.click(b.x+b.width/2,b.y+b.height/2);await page.waitForTimeout(60);};
 const side=async(page,right)=>{const b=await page.locator('#er-text').boundingBox();await page.mouse.click(b.x+b.width*(right?.9:.1),b.y+b.height/2);await page.waitForTimeout(60);};
 const tick=async(page,n=1)=>{for(let i=0;i<n;i++){await page.evaluate(()=>Android._speechTick());await page.waitForTimeout(25);}};
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

  // ---------- voorlezen met de gedeelde lokale stem ----------
  await page.evaluate(()=>{const v=Android._speech;v.voice='';v.voices.forEach(x=>{x.state='none';});erLezenSet('keys',true);document.querySelector('#er-text').scrollTop=0;erTurn(1);erTurn(1);erSave();erControls(true);});
  await page.click('#er-speak');await page.waitForTimeout(60);
  ok(await page.locator('#er-tts-panel').isVisible()&&await page.locator('#er-tts-novoice').isVisible()&&await page.evaluate(()=>Android._speech.said.length===0&&!sp.settings),'nog geen stem: korte uitleg in het paneel, geen audio of download');
  await page.click('#er-tts-novoice button');
  ok(await page.evaluate(()=>sp.settings&&current==='erbook'&&!document.querySelector('#er-tts-panel.on'))&&await page.locator('#speech-heading').isVisible(),'knop opent de gedeelde stemkiezer zonder het boek te verlaten');
  await page.locator('#speech-voices .speech-voice').first().getByRole('button',{name:'Stem downloaden',exact:true}).click();await page.waitForFunction(()=>Android._speech.voices[0].state==='ready'&&Android._speech.voice);await page.evaluate(()=>spSettingsClose());
  ok(await page.evaluate(()=>current==='erbook'&&!document.getElementById('s-erbook').inert),'stem gekozen, terug in het boek');
  await page.evaluate(()=>erControls(true));const from=await page.evaluate(()=>erAnchor());await page.click('#er-speak');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(a=>{const l=Android._speech.said.slice(-1)[0];return l.source==='reader'&&l.chapter===0&&l.offset<=a&&(l.offset===0||/[.!?]\s$/.test(erText.slice(l.offset-2,l.offset)))&&!/[.!?]\s/.test(erText.slice(l.offset,a));},from),'begint bij de eerste zichtbare zin (ook als die op de vorige pagina begon)');
  let h=await spoken(page);
  ok(h&&h.inView&&await page.evaluate(()=>erText.slice(sp.active.offset,sp.active.end)===CSS.highlights.get('er-speak').values().next().value.toString()),'de zin uit het playing-event licht op');
  ok(await page.locator('#er-tts').isVisible()&&!(await page.locator('#speech-player').isVisible())&&await page.locator('#er-tts-play').getAttribute('aria-label')==='Pauzeren','in het boek één voorleesbalk (die van de e-reader)');
  ok(await page.evaluate(()=>Android._erWin.keys===false),'tijdens voorlezen zijn de volumeknoppen voor het volume');
  await page.evaluate(()=>erControls(false));await shot('voorlezen');
  const scroll0=await page.evaluate(()=>document.querySelector('#er-text').scrollTop);let follow=true;
  for(let i=0;i<14;i++){await tick(page);h=await spoken(page);if(!h||!h.inView)follow=false;}
  ok(follow&&await page.evaluate(s=>document.querySelector('#er-text').scrollTop>s,scroll0),'bladert mee: de zin blijft in beeld');
  await page.evaluate(()=>{erUserAt=Date.now();erTurn(1);});await tick(page);
  ok(await page.evaluate(()=>sp.active&&sp.active.state==='playing'),'zelf bladeren stopt het voorlezen niet');
  ok(await page.evaluate(()=>{const h=CSS.highlights.get('er-speak'),r=h&&[...h][0].getClientRects()[0],box=document.querySelector('#er-text').getBoundingClientRect();return !r||r.bottom<box.top+2;}),'en meebladeren wacht even na zelf bladeren');
  await page.evaluate(()=>{const a=Android._speech.active;a.seg=a.segs.length-1;[a.offset,a.end]=a.segs[a.seg];erUserAt=0;});await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>erIndex===1&&sp.active.chapter===1&&erText.startsWith('De haven')),'gaat door naar het volgende hoofdstuk');
  h=await spoken(page);ok(h&&h.text==='De haven lag stil.','eerste zin van het nieuwe hoofdstuk licht op');
  await tick(page);await page.click('#er-tts-play');await page.waitForFunction(()=>sp.active.state==='paused');await page.waitForTimeout(250);
  ok(await page.evaluate(()=>{const b=Android._erBooks[0];return b.chapter===1&&b.anchor<=sp.active.offset&&erSpeakPausedHere();})&&await page.locator('#er-tts-play').getAttribute('aria-label')==='Verder voorlezen'&&await page.evaluate(()=>Android._erWin.keys===true),'pauzeren: leesplek bij de zin, volumeknoppen weer om te bladeren');
  await page.click('#er-tts-play');await page.waitForFunction(()=>sp.active.state==='playing');const cur=await page.evaluate(()=>sp.active.offset);
  await page.click('#er-tts-prev');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');ok(await page.evaluate(c=>Android._speech.said.slice(-1)[0].offset===0&&c>0,cur),'vorige zin');
  await page.click('#er-tts-next');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');ok(await page.evaluate(()=>Android._speech.said.slice(-1)[0].offset===erText.indexOf('Er was')),'volgende zin');
  await page.click('#er-tts-rate');ok(await page.evaluate(()=>Android._speech.speed===1.2)&&(await page.locator('#er-tts-rate').innerText()).replace(',','.')==='1.2×','snelheid via de gedeelde instelling');
  await page.click('#er-tts-more');
  ok(await page.evaluate(()=>document.querySelectorAll('#er-tts-voice option').length===1&&document.querySelectorAll('#er-tts-rates [role=radio]').length===6&&document.querySelector('#er-tts-rates [aria-checked=true]').dataset.v==='1.2'&&document.getElementById('er-tts-bg').getAttribute('aria-checked')==='false'),'paneel: gedownloade stemmen, snelheden en achtergrond');
  await page.click('#er-tts-bg');ok(await page.evaluate(()=>Android._speech.background===true&&document.getElementById('er-tts-bg').getAttribute('aria-checked')==='true'),'doorgaan op de achtergrond (gedeelde instelling)');await page.click('#er-tts-bg');
  await page.evaluate(()=>{Android._speech.voices[1].state='ready';erSpeakPanelRender();});const at=await page.evaluate(()=>sp.active.offset);await page.selectOption('#er-tts-voice','tts-en-ljspeech');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(a=>{const l=Android._speech.said.slice(-1)[0];return l.voice==='tts-en-ljspeech'&&l.offset===a&&l.chapter===1;},at),'andere stem: verder met dezelfde zin');
  await page.selectOption('#er-tts-voice','tts-nl-ronnie');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  await page.click('#er-tts-sleep [data-v="15"]');ok((await page.locator('#er-tts-status').innerText()).includes('Stopt om'),'slaaptimer 15 minuten');
  await page.evaluate(()=>{erSleep.at=Date.now()-1;});await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>!sp.active&&Android._erBooks[0].chapter===1)&&!(await page.locator('#er-tts').isVisible()),'slaaptimer loopt af: gestopt, leesplek bewaard');
  await page.evaluate(()=>{erPanel('');erSpeakFrom(0,Android._erTexts.z[0].lastIndexOf('Einde'));});await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  await page.evaluate(()=>erPanel('tts'));await page.click('#er-tts-sleep [data-v=chapter]');ok((await page.locator('#er-tts-status').innerText()).includes('hoofdstuk'),'slaaptimer einde hoofdstuk');
  await page.evaluate(()=>erPanel(''));await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>!sp.active&&Android._erBooks[0].chapter===1&&Android._erBooks[0].anchor===0),'einde hoofdstuk: gestopt aan het begin van het volgende');
  await page.evaluate(()=>{Android._speech.voices[1].state='none';});

  // ---------- gepauzeerd voorlezen en de leesplek ----------
  await page.evaluate(()=>{erChapter(0,0);erSpeakFrom(0,0);});await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');await tick(page,2);await page.click('#er-tts-play');await page.waitForFunction(()=>sp.active.state==='paused');
  ok(await page.evaluate(()=>erSpeakPausedHere()),'gepauzeerd, de zin staat nog op de pagina');
  const n0=await page.evaluate(()=>Android._speech.said.length);await page.evaluate(()=>erControls(true));await page.click('#er-speak');await page.waitForFunction(()=>sp.active.state==='playing');
  ok(await page.evaluate(n=>Android._speech.said.length===n,n0),'Voorlezen gaat verder bij dezelfde zin');
  await page.click('#er-tts-play');await page.waitForFunction(()=>sp.active.state==='paused');await page.evaluate(()=>{erUserAt=Date.now();erTurn(1);erTurn(1);erTurn(1);});await page.waitForTimeout(300);
  ok(await page.evaluate(()=>Android._erBooks[0].anchor===erAnchor()&&erAnchor()>sp.active.end),'gepauzeerd en verder gebladerd: de gewone leesplek wordt bewaard');
  const seen=await page.evaluate(()=>erAnchor());await page.click('#er-speak');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(a=>{const l=Android._speech.said.slice(-1)[0];return l.offset<=a&&!/[.!?]\s/.test(erText.slice(l.offset,a));},seen),'Voorlezen begint dan bij de zichtbare zin');
  await page.evaluate(()=>goBack());await page.waitForTimeout(60);
  ok(await page.evaluate(()=>current==='ereader'&&sp.active&&sp.active.state==='playing')&&await page.locator('#speech-player').isVisible(),'buiten het boek: voorlezen gaat door in de gedeelde balk');
  await page.evaluate(()=>openTool('ereader'));await page.waitForTimeout(60);ok(await page.evaluate(()=>current==='erbook'&&erBook.id==='z'),'de e-reader openen brengt je naar het boek dat wordt voorgelezen');
  await page.evaluate(()=>erChapter(1,0));ok(await page.evaluate(()=>!erSpeakActive()),'naar een ander hoofdstuk springen stopt het voorlezen');
  await page.evaluate(()=>erLezenSet('keys',false));

  // ---------- zinnen zoals de motor, slaaptimer, stem, volumeknoppen, koude start (eindreview) ----------
  const fx=path.join(__dirname,'../../build/test-data/readaloud-segments.json');
  if(fs.existsSync(fx)){const cases=JSON.parse(fs.readFileSync(fx,'utf8'));ok(await page.evaluate(cs=>cs.every(c=>JSON.stringify(erSegments(c.text,0))===JSON.stringify(c.segs)&&JSON.stringify(erSegments(c.text,c.from))===JSON.stringify(c.mid)),cases),'zinnen gelijk aan ReadAloud.segments in Java ('+cases.length+' teksten, ook aanhalingstekens, haakjes, harde spaties en knippen)');}
  else if(process.env.CI)ok(false,'ReadAloudSegmentsParityTest moet eerst draaien');else console.log('Java-zinnen ontbreken; eerst tests/run-jvm.sh draaien');
  await page.evaluate(()=>{Android._erBooks.push({id:'q',title:'Dialoog',author:'',language:'nl',chapters:['Een','Twee'],chapter:0,offset:0,last:1,format:'EPUB',edition:3},{id:'en',title:'English book',author:'',language:'en',chapters:['One'],chapter:0,offset:0,last:0,format:'EPUB',edition:3});
    Android._erTexts.q=["'Waar ga je heen?' vroeg ze. 'Naar de haven.' Hij zei: \"Kom.\" Toen ging hij. Dit klopt (zie boven.) Daarna verder.",'Tweede hoofdstuk. Nog een zin.'];Android._erTexts.en=['It was late. She left the house.'];show('ereader');erOpen('q');});
  ok(await page.evaluate(()=>{const t=erText,at=x=>t.indexOf(x);return erSentenceStart(t,at('ging'))===at('Toen')&&erSentenceStart(t,at('haven'))===at("'Naar")&&erSentenceStart(t,at('boven'))===at('Dit klopt')&&erSentenceStep(t,at('Toen'),-1)===at('Hij zei')&&erSentenceStep(t,at('Hij zei'),-1)===at("'Naar")&&erSentenceStep(t,at('Dit klopt'),1)===at('Daarna')&&erSentenceStep(t,at('Daarna'),1)===-1;}),'zinsbegin, vorige en volgende zin met aanhalingstekens en haakjes zoals de motor');
  await page.evaluate(()=>erSpeakFrom(0,erText.indexOf('Toen')));await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  await page.evaluate(()=>erControls(true));await page.click('#er-tts-prev');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(()=>Android._speech.said.slice(-1)[0].offset===erText.indexOf('Hij zei')),'vorige zin slaat geen dialoog over');
  await page.evaluate(()=>{erSpeakFrom(0,erText.indexOf("'Naar"));erSpeakStep(1);});await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(()=>Android._speech.said.slice(-1)[0].offset===erText.indexOf('Hij zei')),'volgende zin tijdens het laden: de echte volgende zin, niet één teken verder');
  await page.click('#er-speak');await page.waitForFunction(()=>sp.active.state==='paused');await page.click('#er-speak');await page.waitForFunction(()=>sp.active.state==='playing');
  ok(true,'de luidspreker tijdens het voorlezen pauzeert en gaat verder');
  await page.evaluate(()=>{const e=$('#er-text');e.scrollTop=e.scrollHeight;erTurn(1);});await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>erIndex===1&&sp.active&&sp.active.state==='playing'&&sp.active.chapter===0),'naar het volgende hoofdstuk bladeren stopt het voorlezen niet en springt niet meteen terug');
  await page.evaluate(()=>erPanel('tts'));await page.click('#er-tts-sleep [data-v=chapter]');await page.evaluate(()=>erPanel(''));
  await page.evaluate(()=>erChapter(0,0));ok(await page.evaluate(()=>!erSpeakActive()&&!erSleep.chapter&&!erSleep.at),'naar een hoofdstuk springen stopt het voorlezen én de slaaptimer');
  await page.evaluate(()=>erControls(true));await page.click('#er-speak');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(!(await page.locator('#er-tts-status').innerText()).includes('hoofdstuk'),'een nieuwe sessie begint zonder de oude slaaptimer');
  await page.evaluate(()=>erPanel('tts'));await page.click('#er-tts-sleep [data-v=chapter]');await page.evaluate(()=>{erPanel('');goBack();});
  await page.evaluate(()=>{const a=Android._speech.active;a.seg=a.segs.length-1;[a.offset,a.end]=a.segs[a.seg];});await tick(page);await page.waitForTimeout(60);
  ok(await page.evaluate(()=>current==='ereader'&&!sp.active&&!erSleep.chapter),'slaaptimer "einde hoofdstuk" werkt ook buiten het boek');
  await page.evaluate(()=>{erOpen('q');erSpeakFrom(0,0);});await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  await page.evaluate(()=>{show('home');sp.active=null;spSpeechRender();openTool('ereader');});await page.waitForTimeout(60);
  ok(await page.evaluate(()=>current==='erbook'&&erBook.id==='q'&&sp.active&&sp.active.book==='q'),'koude start vanuit de melding: meteen naar het boek dat wordt voorgelezen');
  await page.evaluate(()=>{erSpeakStop();Android._speech.voices[1].state='ready';Android._speech.voice='tts-nl-ronnie';show('ereader');erOpen('en');erControls(true);});
  await page.click('#er-speak');await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(()=>Android._speech.said.slice(-1)[0].voice==='tts-en-ljspeech'&&Android._speech.voice==='tts-nl-ronnie'),'Engels boek met de Engelse stem; de gekozen stem blijft Nederlands');
  await page.evaluate(()=>{erSpeakStop();spText('Hallo daar.','note');});await page.waitForFunction(()=>sp.active&&sp.active.source==='note');
  ok(await page.evaluate(()=>Android._speech.said.slice(-1)[0].voice==='tts-nl-ronnie'),'daarna lezen notities en de assistent weer met de gekozen stem');
  await page.evaluate(()=>erLezenSet('keys',true));ok(await page.evaluate(()=>Android._erWin.keys===false),'ook een notitie die klinkt houdt de volumeknoppen voor het volume');
  await page.evaluate(()=>spStop(true));await page.waitForFunction(()=>Android._erWin.keys===true);
  await page.evaluate(()=>spSettings());await page.waitForFunction(()=>Android._erWin.keys===false);await page.evaluate(()=>spSettingsClose());await page.waitForFunction(()=>Android._erWin.keys===true);
  ok(true,'stemkiezer open: volumeknoppen voor het volume, daarna weer bladeren');
  await page.evaluate(()=>{erPanel('tts');erSpeakVoices();spSettingsClose();});
  ok(await page.evaluate(()=>{const a=document.activeElement;return !!a&&a!==document.body&&a.getClientRects().length>0&&!a.closest('#er-tts-panel');}),'na de stemkiezer staat de focus op iets zichtbaars');
  await page.evaluate(()=>{erLezenSet('keys',false);Android._speech.voices[1].state='none';show('ereader');erOpen('z');});

  // ---------- buiten een boek blijft het venster ongemoeid ----------
  await page.evaluate(()=>{goBack();show('ai');Android.aiTalkScreen(true);});const nWin=await page.evaluate(()=>Android._erWinLog.length);
  await page.evaluate(()=>{erLezenSet('bright',.4);erLezenSet('awake',false);erLezenSet('awake',true);setEntry('ereader.helderheid').set('telefoon');setEntry('ereader.volscherm').set('aan');});
  ok(await page.evaluate(n=>Android._erWinLog.length===n&&Android._tkScreen===true,nWin),'instellingen aanpassen buiten een boek (bijv. tijdens een gesprek met de assistent) raakt scherm aan, balken en helderheid niet');
  await page.evaluate(()=>{Android.aiTalkScreen(false);show('ereader');erOpen('z');});

  // ---------- assistent en zoeken ----------
  ok(await page.evaluate(()=>{const id=q=>{const r=setHandle(q);return r&&r.text||'';};return /Helderheid in de e-reader[\s\S]*50/.test(id('zet de helderheid op 50%'))&&/Helderheid in de e-reader[\s\S]*50/.test(id('zet de helderheid in de e-reader op 50%'))&&/Tekstgrootte in de e-reader[\s\S]*11\d/.test(id('zet de tekstgrootte in de e-reader op 120%'));}),'assistent: helderheid op 50% (ook "in de e-reader") en tekstgrootte in de e-reader op 120%');
  ok(await page.evaluate(()=>{const r=setHandle('zet het volume uit');return !(r&&/volumeknoppen/i.test(r.text||''))&&((setFind('scherm donkerder')[0]||{}).e||{}).id==='weergave.amoled'&&((setFind('tekstgrootte')[0]||{}).e||{}).id==='weergave.tekstgrootte';}),'niet: volume uit, scherm donkerder of de gewone tekstgrootte');
  ok(await page.evaluate(()=>setPath('erset')==='E-reader → Leesweergave'),'pad zonder dubbele stap of boektitel');
  ok(await page.evaluate(()=>[['nachtfilter','ereader.ogen'],['blauw licht','ereader.ogen'],['helderheid','ereader.helderheid'],['voorleessnelheid','voorlezen.snelheid'],['scherm aan laten tijdens het lezen','ereader.schermaan']].every(([q,id])=>((setFind(q)[0]||{}).e||{}).id===id)&&((setFind('tekstgrootte')[0]||{}).e||{}).id==='weergave.tekstgrootte'),'assistent vindt helderheid, nachtfilter, voorleessnelheid en scherm aan (tekstgrootte blijft die van de app)');
  ok(await page.evaluate(()=>{const e=setEntry('ereader.helderheid');e.set('25');const r=Android._erWin.bright===.25&&erLezen().bright===.25;e.set('telefoon');return r&&erLezen().bright===-1;}),'helderheid via het register, meteen in het boek');
  ok(await page.evaluate(()=>GS_SYN['e-reader'].includes('voorlezen')&&GS_SYN['e-reader'].includes('nachtfilter')),'zoekwoorden voorlezen en nachtfilter bij de e-reader');

  // ---------- TalkBack ----------
  await page.evaluate(()=>{goBack();Android._talkback=true;erOpen('z');});
  ok(await page.evaluate(()=>!document.body.classList.contains('er-immersive'))&&await page.locator('.er-toolbar').isVisible(),'TalkBack: knoppen blijven altijd zichtbaar');
  await middle(page);ok(await page.locator('.er-toolbar').isVisible(),'TalkBack: een tik verbergt niets');
  await page.evaluate(()=>{goBack();Android._talkback=false;});

  // ---------- smal scherm en Engels ----------
  await page.setViewportSize({width:360,height:740});await page.evaluate(()=>{erOpen('z');erControls(true);erSpeakStart();erPanel('quick');});await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');
  ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth&&[...document.querySelectorAll('#er-quick button,#er-tts button')].filter(b=>b.getClientRects().length&&!b.classList.contains('tg')).every(b=>{const r=b.getBoundingClientRect();return r.height>=44&&r.width>=44||b.closest('.er-q-actions');})),'360 px: past, tikvlakken minstens 44 px');
  await page.evaluate(()=>{erPanel('');erSpeakStop();goBack();show('setregio');});await page.selectOption('#rg-lang','en');await page.evaluate(()=>{show('ereader');erOpen('z');erControls(true);erSpeakStart();erPanel('quick');});await page.waitForFunction(()=>sp.active&&sp.active.state==='playing');await page.waitForTimeout(100);
  ok(await page.evaluate(()=>document.getElementById('er-quick-title').textContent==='Reading appearance'&&document.getElementById('er-tts-status').textContent==='Reading aloud'&&document.getElementById('er-title').textContent==='Een reis langs zee'),'Engelse bediening, boektitel blijft');
  await page.evaluate(()=>{erPanel('');erSpeakStop();goBack();rgSave({lang:'nl'});});
  await page.close();
 }
 await browser.close();console.log('errors',JSON.stringify(errors));if(errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
