const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const screens=['home','search','backup','settings','redial','wa','calls','notes','contacts','radio','podcasts','podspotify','spotify','vpn','loc','sms','tracks','car','kluis','pdf','recorder','sleep','checkin','checkinq','checkincmp','apparaten','apset','transcripts','music','history','auto','info','homeedit','selftest'];
(async()=>{
 const browser=await chromium.launch();
 try{
  for(const scheme of ['light','dark']){
   const p=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});const errors=[];p.on('pageerror',e=>errors.push(e.message));
   await p.goto('file://'+require('path').join(__dirname,'ui','index.html'));
   await p.waitForFunction(()=>typeof UI_ICONS!=='undefined');
   const icons=await p.locator('#home-groups .tile .ic').evaluateAll(els=>els.map(e=>({text:e.textContent.trim(),mask:getComputedStyle(e.querySelector('[data-icon]'),'::before').maskImage})));
   assert.ok(icons.length>=20);assert.ok(icons.every(e=>!e.text&&e.mask.startsWith('url(')),'alle tools gebruiken de lokale lijniconen');
   for(const width of [320,390]){
    await p.setViewportSize({width,height:844});
    for(const screen of screens){
     await p.evaluate(s=>show(s),screen);await p.waitForTimeout(40);
     assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,scheme+' '+width+' '+screen+' heeft geen horizontale scroll');
     const small=await p.locator('.screen.on .big').evaluateAll(els=>els.filter(e=>e.getClientRects().length&&e.getBoundingClientRect().height<44).map(e=>e.id||e.textContent));
     assert.deepEqual(small,[],screen+' heeft voldoende grote actieknoppen');
    }
   }
   // Lange toolnamen moeten ook met een breder lettertype ruimte laten voor de bediening.
   await p.setViewportSize({width:320,height:844});
   const wideNames=await p.addStyleTag({content:'#s-homeedit .nm{font-family:monospace}'});
   await p.evaluate(()=>show('homeedit'));
   assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'startscherm-editor blijft binnen een smal scherm met brede letters');
   assert.equal(await p.locator('#he-groups .mv').evaluateAll(els=>els.every(e=>{const r=e.getBoundingClientRect();return r.width>=44&&r.height>=44&&r.right<=innerWidth;})),true,'verplaatsen en favorieten blijven goed bereikbaar');
   await wideNames.evaluate(e=>e.remove());await p.setViewportSize({width:390,height:844});
   await p.evaluate(()=>{show('selftest');sfRender(['ok','warn','fail','skip'].map(status=>({name:'Controle '+status,detail:'Getest',status})),Date.now());});
   assert.deepEqual(await p.locator('#sf-res .sr-only').allTextContents(),['Geslaagd: ','Aandachtspunt: ','Mislukt: ','Overgeslagen: ']);
   // De afgebeelde Android-fout kan nog in de opgeslagen exportstatus staan na de update.
   await p.evaluate(()=>{
    Android.smsRequestPermission();Android._wa.info.dest=true;Android._wa.info.destName='Documenten en backups';
    Android._sms.st={running:false,ok:false,error:"Attempt to invoke virtual method 'java.lang.Object android.os.Bundle.getParcelable(java.lang.String, java.lang.Class)' on a null object reference"};smsLastDone=false;show('sms');
   });
   await p.waitForTimeout(250);
   assert.equal(await p.isVisible('#sms-recovery'),true);assert.match(await p.textContent('#sms-result'),/geen geldig antwoord/);assert.doesNotMatch(await p.textContent('#sms-result'),/getParcelable|object reference/);
   assert.equal(await p.textContent('#sms-dest-name'),'Documenten en backups');
   await p.evaluate(()=>{window.folderCalls=0;Android.waPickFolder=()=>folderCalls++;});
   await p.getByRole('button',{name:'Andere backup-map',exact:true}).click();assert.equal(await p.evaluate(()=>folderCalls),1);
   await p.screenshot({path:__dirname+'/shots/redesign-sms-error-'+scheme+'.png',fullPage:true});
   await p.getByRole('button',{name:'Opnieuw proberen',exact:true}).click();
   await p.waitForFunction(()=>document.querySelector('#sms-result').classList.contains('answered'));
   assert.match(await p.textContent('#sms-result'),/31 berichten geëxporteerd/);assert.equal(await p.isVisible('#sms-recovery'),false);
   // Door de vormgeving veranderen gebruikersgegevens en hun eigen symbolen niet.
   await p.evaluate(()=>{notesGet().push({id:'design-note',title:'📝 Mijn eigen titel',text:'📍 Mijn tekst <img src=x>',items:[],updated:Date.now()});notesSaveNow();show('notes');});
   await p.fill('#notes-search','Mijn eigen titel');assert.match(await p.textContent('#notes-list'),/📝 Mijn eigen titel/);assert.equal(await p.locator('#notes-list img').count(),0);
   await p.click('#notes-list [data-note-id="design-note"]');assert.equal(await p.inputValue('#note-title'),'📝 Mijn eigen titel');assert.equal(await p.inputValue('#note-text'),'📍 Mijn tekst <img src=x>');
   await p.evaluate(()=>show('home'));await p.waitForTimeout(250);await p.screenshot({path:__dirname+'/shots/redesign-home-'+scheme+'.png',fullPage:true});
   await p.evaluate(()=>show('pdf'));await p.waitForTimeout(250);await p.screenshot({path:__dirname+'/shots/redesign-pdf-'+scheme+'.png',fullPage:true});
   assert.deepEqual(errors,[]);await p.close();
   const skin=await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});skin.on('pageerror',e=>errors.push(e.message));
   await skin.goto('file://'+require('path').join(__dirname,'..','..','web','start.html'));
   for(const accent of ['#3b82f6','#facc15','#185d52','#fafafa','#101010']){
    const ratio=await skin.evaluate(accent=>{
     cfg.accent=accent;applyLook();const s=getComputedStyle(document.querySelector('#askok'));
     const lum=rgb=>{const v=rgb.match(/\d+/g).slice(0,3).map(x=>+x/255).map(x=>x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4));return .2126*v[0]+.7152*v[1]+.0722*v[2];};
     const a=lum(s.color),b=lum(s.backgroundColor);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    },accent);assert.ok(ratio>=4.5,accent+' houdt de skin-knoptekst leesbaar');
   }
   assert.deepEqual(errors,[]);await skin.close();console.log(scheme,'appbreed ontwerp, 28 schermen, lokale iconen, SMS-herstel en skincontrast: errors []');
  }
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
