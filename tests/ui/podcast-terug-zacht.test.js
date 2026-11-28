// Podcastspeler: terug naar wat al gespeeld is (⏮, terugspoelen aan het begin, de lijst Eerder afgespeeld) en Zacht beginnen
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('path');
(async()=>{
 const browser=await chromium.launch();const errors=[];
 for(const colorScheme of ['light','dark']){
  const p=await browser.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui','index.html'));await p.waitForTimeout(300);
  const ep=(k,t)=>({key:'e00000000000000'+k,title:t,url:'https://voorbeeld.test/'+k+'.mp3',dur:1800});
  await p.evaluate(([a,b,c])=>{const pod={id:'p1',title:'Testpodcast',feed:'https://voorbeeld.test/feed'};window.__eps=[a,b,c];
    for(const e of [a,b,c]){Android.podPlay(JSON.stringify({ep:e,pod}));Android._pc.st.status='playing';Android._pc.st.pos=600000;Android._pc.st.at=Date.now();}
    Android._pc.prog[a.key]={p:0,d:1800000,done:true};Android.podPause();pcPoll(true);},[ep('a','Eerste aflevering'),ep('b','Tweede aflevering'),ep('c','Derde aflevering')]);
  await p.evaluate(()=>plOpen());await p.waitForSelector('#pl-prev');
  assert.equal(await p.getAttribute('#pl-prev','aria-label'),'Vorige aflevering (wat hiervoor speelde)');
  // ⏮ gaat meteen naar wat hiervoor speelde (niet eerst naar het begin), en nog een keer gaat verder terug
  await p.evaluate(()=>plPrev());assert.equal(await p.evaluate(()=>Android._pc.st.ep.title),'Tweede aflevering');
  assert.equal(await p.evaluate(()=>Android._pc.prog[window.__eps[2].key].p),600000,'de plek in de derde blijft bewaard');
  await p.evaluate(()=>plPrev());assert.equal(await p.evaluate(()=>Android._pc.st.ep.title),'Eerste aflevering','verder terug, niet heen en weer');
  assert.equal(await p.evaluate(()=>Android._pc.st.pos),0,'een beluisterde aflevering begint vooraan');
  // ⏭ gaat dezelfde weg weer vooruit, tot waar je was
  await p.evaluate(()=>plNext());assert.equal(await p.evaluate(()=>Android._pc.st.ep.title),'Tweede aflevering');
  await p.evaluate(()=>plNext());assert.equal(await p.evaluate(()=>Android._pc.st.ep.title),'Derde aflevering');assert.equal(await p.evaluate(()=>Android._pc.st.pos),600000,'verder waar je was');
  // Terugspoelen aan het begin van een aflevering: het einde van de vorige, met een melding
  await p.evaluate(()=>{Android._pc.st.pos=1000;Android._pc.st.status='paused';pcPoll(true);pcSkip(-15);});
  assert.equal(await p.evaluate(()=>Android._pc.st.ep.title),'Tweede aflevering');assert.equal(await p.evaluate(()=>Android._pc.st.pos),1780000,'twintig seconden voor het einde');
  await p.evaluate(()=>pcPoll(true));assert.match(await p.textContent('#toast'),/Terug in de vorige aflevering/);
  // midden in een aflevering blijft terugspoelen gewoon terugspoelen
  await p.evaluate(()=>{Android._pc.st.pos=60000;Android._pc.st.status='paused';pcSkip(-15);});assert.equal(await p.evaluate(()=>Android._pc.st.ep.title+' '+Android._pc.st.pos),'Tweede aflevering 45000');
  // De lijst Eerder afgespeeld in de speler: ook wat al beluisterd is; een tik speelt hem
  await p.evaluate(()=>{Android._pc.prog[window.__eps[0].key]={p:0,d:1800000,done:true};plQueueSig='';plRender();});
  assert.match(await p.textContent('#pl-queue .pl-qkop'),/Eerder afgespeeld/);
  const rijen=await p.evaluate(()=>[...document.querySelectorAll('#pl-eerder .pl-qrow')].map(r=>r.textContent));
  assert.equal(rijen.length,2);assert.ok(rijen.some(r=>/Eerste aflevering.*beluisterd/.test(r)),'beluisterd staat erbij');
  await p.locator('#pl-eerder .pl-qb',{hasText:'Eerste aflevering'}).evaluate(b=>b.click());assert.equal(await p.evaluate(()=>Android._pc.st.ep.title),'Eerste aflevering');
  assert.equal(await p.evaluate(()=>Android._pc.terug),null,'zelf gekozen: de rij van ⏮ begint opnieuw');
  await p.evaluate(()=>{plClose();});
  // Zacht beginnen: de kaart op Instellingen van Podcasts
  await p.evaluate(()=>show('podset'));await p.waitForSelector('#ps-zk:not([hidden])');
  assert.equal(await p.inputValue('#ps-zacht'),'avond');assert.equal(await p.inputValue('#ps-zacht-van'),'20:00');assert.equal(await p.inputValue('#ps-zacht-tot'),'07:00');
  assert.match(await p.textContent('#ps-zacht-u'),/tussen 20:00 en 07:00.*begint het zacht/);assert.match(await p.textContent('#ps-zacht-pt'),/7% van het hoogste volume/);
  await p.fill('#ps-zacht-van','21:30');await p.dispatchEvent('#ps-zacht-van','change');assert.equal(await p.evaluate(()=>Android._pc.zacht.van),1290);
  await p.click('#ps-zacht-nu');assert.equal(await p.evaluate(()=>Android._pc.zacht.pct),20,'het volume van nu overgenomen');assert.equal(await p.isHidden('#ps-zacht-nu'),true);
  await p.click('#ps-zacht-ls');assert.equal(await p.getAttribute('#ps-zacht-ls','aria-checked'),'true');assert.match(await p.textContent('#ps-zacht-u'),/Alleen op de luidspreker/);
  await p.selectOption('#ps-zacht','altijd');assert.equal(await p.isHidden('#ps-zacht-tijd'),true);assert.match(await p.textContent('#ps-zacht-u'),/Elke keer dat je begint/);
  await p.selectOption('#ps-zacht','uit');assert.equal(await p.isHidden('#ps-zacht-meer'),true);assert.match(await p.textContent('#ps-zacht-u'),/^Uit:/);
  assert.deepEqual(await p.evaluate(()=>Android._pc.zacht),{wijze:'uit',van:1290,tot:420,pct:20,luidspreker:true});
  // De melding als het zacht begon
  await p.evaluate(()=>{Android._pc.st.meld={wat:'zacht',t:Date.now()+1};pcPoll(true);});assert.match(await p.textContent('#toast'),/Zacht begonnen/);
  // Een oudere app zonder deze brugfuncties: de kaart en de lijst blijven weg
  await p.evaluate(()=>{Android.podZacht=undefined;Android.podEerder=undefined;psRender();plQueueSig='';});assert.equal(await p.isHidden('#ps-zk'),true);
  await p.close();
 }
 console.log('errors',errors);await browser.close();assert.deepEqual(errors,[]);
})().catch(e=>{console.error(e);process.exit(1);});
