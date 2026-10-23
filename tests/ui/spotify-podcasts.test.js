const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('path');
(async()=>{
 const browser=await chromium.launch();
 try{for(const scheme of ['light','dark']){
  const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme}),errors=[];
  p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui','index.html'));
  await p.evaluate(()=>{
   Android._sp.linked=true;
   Android._spPodcastShows=[
    {id:'AAAAAAAAAAAAAAAAAAAAAA',title:'Nieuws van Vandaag',author:'Omroep X'},
    {id:'BBBBBBBBBBBBBBBBBBBBBB',title:'Tech in 20 minuten',author:'René & Co'},
    {id:'CCCCCCCCCCCCCCCCCCCCCC',title:'Dubbele naam',author:'Studio'},
    {id:'DDDDDDDDDDDDDDDDDDDDDD',title:'Alleen op Spotify',author:'Maker'},
    {id:'EEEEEEEEEEEEEEEEEEEEEE',title:'<img src=x onerror="window.injected=true">',author:'<b>Maker</b>'},
    {id:'BBBBBBBBBBBBBBBBBBBBBB',title:'Dubbel bronitem',author:'René & Co'},
    {id:'ongeldig',title:'Ongeldig item'}
   ];
   Android._spPodcastResults={
    AAAAAAAAAAAAAAAAAAAAAA:[Android._pc.top[0]],
    BBBBBBBBBBBBBBBBBBBBBB:[Android._pc.top[1]],
    CCCCCCCCCCCCCCCCCCCCCC:[{id:'c1',title:'Dubbele naam',author:'Studio',feed:'https://feeds.example.org/c1'},{id:'c2',title:'Dubbele naam',author:'Studio',feed:'https://feeds.example.org/c2'},{title:'Nog eens dezelfde feed',author:'Studio',feed:'https://feeds.example.org/c1#fragment'},{title:'Geen feed',feed:'javascript:alert(1)'},{title:'Spotify-pagina',feed:'https://open.spotify.com/show/test'}],
    DDDDDDDDDDDDDDDDDDDDDD:[],EEEEEEEEEEEEEEEEEEEEEE:[]
   };
   Android.podSubscribe(JSON.stringify(Android._pc.top[0]));
   Android.podSubscribe(JSON.stringify({id:'own',title:'Eigen podcast',feed:'https://feeds.example.org/own'}));
   Android._pc.prog.keep={p:123456,d:500000,done:false};
   Android.openUrl=u=>Android._openedUrl=u;
   show('podcasts');
  });
  await p.click('#pc-menu');await p.click('#sheet-acts button:has-text("Uit Spotify overnemen")');
  await p.waitForFunction(()=>pspLoaded&&pspRows.length===5);
  assert.equal(await p.evaluate(()=>Android._spPodcastSource),'live');
  assert.equal(await p.isDisabled('#psp-add'),true);
  assert.match(await p.textContent('#psp-source'),/Huidige Spotify-lijst/);
  await p.click('#psp-find');
  await p.waitForFunction(()=>pspRows[0].searched&&pspTimer!==null);
  await p.click('#psp-pause');
  assert.equal(await p.evaluate(()=>!pspRunning&&!pspActive),true,'pauzeren tijdens de wachttijd laat geen vastgelopen opdracht achter');
  const calls=await p.evaluate(()=>Android._pc.calls.filter(x=>x[0]==='spotify-search').length);
  await p.waitForTimeout(200);assert.equal(await p.evaluate(()=>Android._pc.calls.filter(x=>x[0]==='spotify-search').length),calls);
  await p.click('#psp-find');await p.evaluate(()=>onPauseApp());
  assert.equal(await p.evaluate(()=>!pspRunning&&!pspActive),true,'app verlaten pauzeert ook de nog niet begonnen zoekopdracht');
  await p.evaluate(()=>onResumeApp());assert.equal(await p.evaluate(()=>pspRunning),false,'terugkomen start de batch pas op verzoek');
  await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>current),'podcasts');
  await p.click('#pc-menu');await p.click('#sheet-acts button:has-text("Uit Spotify overnemen")');assert.equal(await p.evaluate(()=>pspRows[0].searched),true,'zoekresultaten behouden bij terugkomen');
  await p.evaluate(()=>onSpotifyPodcastSearch({request:-1,id:pspRows[0].id,results:[]}));
  assert.equal(await p.evaluate(()=>pspRows[0].choice.title),'Nieuws van Vandaag','verouderd antwoord genegeerd');
  await p.click('#psp-find');
  await p.locator('[data-index="2"] summary').click();await p.fill('#psp-q-2','Studio met een andere naam');
  await p.evaluate(()=>document.querySelector('#psp-q-2').setSelectionRange(3,9));
  await p.waitForFunction(()=>pspRows[1].searched);
  assert.equal(await p.inputValue('#psp-q-2'),'Studio met een andere naam');
  assert.deepEqual(await p.evaluate(()=>({id:document.activeElement.id,start:document.activeElement.selectionStart,end:document.activeElement.selectionEnd})),{id:'psp-q-2',start:3,end:9},'antwoorden voor andere rijen behouden zoektekst, focus en cursor');
  await p.waitForFunction(()=>!pspRunning&&!pspActive&&pspRows.every(r=>r.searched),null,{timeout:30000});
  const lookupTimes=await p.evaluate(()=>Android._pc.calls.filter(x=>x[0]==='spotify-search').map(x=>x[3]));
  assert.ok(lookupTimes.every((t,i)=>!i||t-lookupTimes[i-1]>=3100),'zoekopdrachten blijven gespreid');
  assert.equal(await p.locator('[data-pick="0"]').isDisabled(),true,'bestaand abonnement wordt overgeslagen');
  assert.equal(await p.locator('[data-pick="1"]').isChecked(),true,'unieke titel en maker voorgeselecteerd, ook met accenten');
  assert.equal(await p.evaluate(()=>pspRows[2].choice),null,'meerdere gelijknamige versies niet zomaar kiezen');
  assert.equal(await p.evaluate(()=>pspRows[2].candidates.length),2,'dubbele en onveilige feedadressen overslaan');
  assert.equal(await p.locator('[data-pick="3"]').isDisabled(),true,'geen openbare versie betekent geen abonnement');
  assert.equal(await p.locator('#psp-list img, #psp-list b b').count(),0,'titels blijven tekst');
  assert.match(await p.textContent('#psp-list'),/<img src=x/);
  await p.locator('[data-index="3"] summary').click();await p.locator('[data-index="3"] button:has-text("Bekijken op Spotify")').click();
  assert.equal(await p.evaluate(()=>Android._openedUrl),'https://open.spotify.com/show/DDDDDDDDDDDDDDDDDDDDDD');
  await p.evaluate(()=>{Android._spPodcastResults.CCCCCCCCCCCCCCCCCCCCCC=[pspRows[2].candidates[0]];Android._spPodcastSearchDelay=500;});
  await p.locator('[data-index="2"] button:has-text("Zoeken")').click();
  await p.locator('[data-index="2"] select').selectOption('1');
  await p.locator('[data-pick="2"]').uncheck();
  await p.waitForFunction(()=>!pspActive);
  assert.equal(await p.evaluate(()=>pspRows[2].choice.feed),'https://feeds.example.org/c2','late resultaten overschrijven handmatige koppeling niet');
  assert.equal(await p.locator('[data-pick="2"]').isChecked(),false,'late resultaten overschrijven deselectie niet');
  await p.evaluate(()=>{Android._spPodcastSearchDelay=100;});
  // De handmatig gekozen tweede versie staat nu vooraan, de eerste blijft als zoekresultaat beschikbaar.
  await p.locator('[data-index="2"] select').selectOption('1');
  assert.equal(await p.textContent('#psp-count'),'2 geselecteerd');
  await p.fill('#psp-filter','tech');assert.equal(await p.locator('#psp-list .psp-row:visible').count(),1);await p.fill('#psp-filter','');
  await p.evaluate(()=>{const save=Android.podSubscribe.bind(Android);Android._savePod=save;Android.podSubscribe=j=>JSON.parse(j).feed.endsWith('/c1')?'Opslag vol':save(j);});
  await p.click('#psp-add');await p.waitForFunction(()=>!pspAdding&&document.querySelector('#psp-message').textContent.includes('niet opgeslagen'));
  assert.match(await p.textContent('#psp-message'),/1 podcast toegevoegd.*1 niet opgeslagen/);
  assert.equal(await p.locator('[data-pick="1"]').isDisabled(),true);
  assert.equal(await p.textContent('#psp-count'),'1 geselecteerd');
  await p.evaluate(()=>Android.podSubscribe=Android._savePod);
  await p.click('#psp-add');await p.waitForFunction(()=>!pspAdding&&document.querySelector('#psp-message').textContent.includes('daar openen'));
  assert.equal(await p.evaluate(()=>Android._pc.subs.length),4,'alleen de twee nieuwe openbare feeds toegevoegd');
  assert.deepEqual(await p.evaluate(()=>Android._pc.prog.keep),{p:123456,d:500000,done:false});
  assert.equal(await p.evaluate(()=>Android._pc.subs.some(p=>p.id==='own')),true,'eigen podcasts behouden');
  assert.equal(await p.evaluate(()=>store.get('spotifyPodcastFeeds',{}).BBBBBBBBBBBBBBBBBBBBBB.feed),'https://feeds.example.org/tech');
  await p.click('#psp-backup');await p.waitForFunction(()=>!pspReading&&pspSource==='backup');
  assert.match(await p.textContent('#psp-source'),/Spotify-backup/);
  assert.equal(await p.evaluate(()=>pspRows[1].choice.feed),'https://feeds.example.org/tech','eerdere koppeling wordt hergebruikt');
  assert.equal(await p.locator('[data-pick="1"]').isDisabled(),true);
  await p.evaluate(()=>{Android._spPodcastError='Geen internetverbinding';});
  await p.click('#psp-live');await p.waitForFunction(()=>!pspReading&&document.querySelector('#psp-message').textContent.includes('internet'));
  assert.equal(await p.evaluate(()=>pspRows.length),5,'mislukt ophalen bewaart de vorige lijst');
  await p.evaluate(()=>{delete Android._spPodcastError;Android._spPodcastShows=[Android._spPodcastShows[3]];});
  await p.click('#psp-live');await p.waitForFunction(()=>!pspReading&&pspRows.length===1);
  assert.equal(await p.evaluate(()=>Android._pc.subs.length),4,'uit Spotify verdwenen podcasts niet uit de app verwijderen');
  for(const width of [320,390]){await p.setViewportSize({width,height:844});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'smalle weergave past');}
  await p.screenshot({path:__dirname+'/shots/spotify-podcasts-'+scheme+'.png',fullPage:true});
  await p.evaluate(()=>{Android._sp.linked=false;enterPodSpotify();});assert.equal(await p.isDisabled('#psp-live'),true);assert.equal(await p.isVisible('#psp-link'),true);assert.equal(await p.isDisabled('#psp-backup'),false);
  await p.click('#psp-link');assert.equal(await p.evaluate(()=>current),'spotify');
  await p.evaluate(()=>{Android._sp.linked=true;Android._spNow=[];Android._spPodcastShows=[];spRender();});
  await p.click('#sp-lib button[data-k="shows"]');await p.click('#sheet-acts button:has-text("Overnemen in Podcasts")');
  await p.waitForFunction(()=>current==='podspotify'&&!pspReading&&pspRows.length===0);
  assert.equal(await p.evaluate(()=>Android._spPodcastSource),'backup','ook bereikbaar vanuit de Spotify-bibliotheek');
  assert.equal(await p.isVisible('#psp-work'),false);assert.match(await p.textContent('#psp-message'),/geen gevolgde podcasts/);
  assert.equal(await p.evaluate(()=>Android._pc.subs.length),4,'een lege Spotify-lijst verwijdert geen abonnementen');
  assert.deepEqual(errors,[]);console.log(scheme,'Spotify-lijst, openbare versies, pauzeren, selectie, herhalen, opslagfout en bestaande luisterdata: errors []');await p.close();
 }}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
