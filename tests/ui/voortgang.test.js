// Voortgang bij het toevoegen van bestanden (boek, document, factuur, afschriften, foto's): de balk onderin (Voortgang.java → onVoortgang).
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  // 1. Een boek toevoegen: lezen met procenten, verwerken (balk loopt), opslaan met aantallen; daarna weg en het boek open.
  await p.click('#tile-ereader');
  await p.evaluate(()=>{Android._vgMs=250;Android._erBooks=Android._erBooks||[];Android._erPick={book:{id:'nieuw',title:'Nieuw boek',author:'',chapters:['Een'],chapter:0,offset:0,last:1,format:'EPUB'}};Android._erTexts=Android._erTexts||{};Android._erTexts.nieuw=['Tekst'];Android._erBooks.push({id:'nieuw',title:'Nieuw boek',author:'',chapters:['Een'],chapter:0,offset:0,last:1,format:'EPUB'});});
  const seen=[];await p.exposeFunction('vgZag',s=>seen.push(s));
  await p.evaluate(()=>{new MutationObserver(()=>{if(!$('#vg').hidden)vgZag($('#vg-wat').textContent+' | '+$('#vg-stand').textContent+' | '+($('#vg-prog').getAttribute('aria-valuenow')||'-')+' | '+$('#vg-prog').classList.contains('busy'));}).observe($('#vg'),{subtree:true,childList:true,characterData:true,attributes:true});});
  await p.click('#er-add');
  assert.equal(await p.isVisible('#vg'),false,'niet meteen (kleine bestanden flitsen niet)');
  await p.waitForSelector('#vg',{state:'visible'});
  await p.waitForFunction(()=>/Opslaan… 12 van 12/.test($('#vg-stand').textContent));
  await p.waitForSelector('#vg',{state:'hidden'});await p.waitForFunction(()=>!erBusy);
  const all=seen.join('\n');
  assert.match(all,/Boek toevoegen \| Lezen… 50% \| 50 \| false/,'lezen met procenten');
  assert.match(all,/Boek toevoegen \| Verwerken… \| - \| true/,'verwerken: balk zonder getal');
  assert.match(all,/Opslaan… 3 van 12 \| 25 \| false/,'opslaan met aantallen');
  // 2. Meer bestanden: welk bestand; een "klaar" van iets anders laat de balk staan; zonder grootte de gelezen hoeveelheid.
  await p.evaluate(()=>onVoortgang({soort:'bank',stap:'lezen',gedaan:1536,totaal:0,bestand:2,bestanden:3}));
  await p.waitForSelector('#vg',{state:'visible'});
  assert.equal(await p.textContent('#vg-wat'),'Afschriften inlezen: bestand 2 van 3');
  assert.equal(await p.textContent("#vg-stand"),await p.evaluate(()=>"Lezen… "+fmtSize(1536)));
  const box=await p.locator('#vg').boundingBox();assert.ok(box.x>=16&&box.x+box.width<=374,'past binnen de kantlijnen: '+JSON.stringify(box));
  const kleur=await p.evaluate(()=>[getComputedStyle($('#vg')).backgroundColor,getComputedStyle(document.body).backgroundColor]);assert.notEqual(kleur[0],kleur[1]);
  await p.evaluate(()=>onVoortgang({soort:'pdf',klaar:true,bestand:1,bestanden:1}));await p.waitForTimeout(50);assert.equal(await p.isVisible('#vg'),true,'klaar van een ander laat staan');
  await p.evaluate(()=>onVoortgang({soort:'bank',klaar:true,bestand:1,bestanden:3}));assert.equal(await p.isVisible('#vg'),false,'eigen klaar: weg');
  // 3. Snel klaar: nooit zichtbaar.
  await p.evaluate(()=>{onVoortgang({soort:'kluis',stap:'lezen',gedaan:10,totaal:100,bestand:1,bestanden:1});onVoortgang({soort:'kluis',klaar:true,bestand:1,bestanden:1});});
  await p.waitForTimeout(600);assert.equal(await p.isVisible('#vg'),false,'snel klaar: geen balk');
  // 4. Engels.
  await p.evaluate(()=>{rgSave({lang:'en'});taalApply();});await p.waitForFunction(()=>TAAL.on);
  await p.evaluate(()=>onVoortgang({soort:'ereader',stap:'opslaan',gedaan:2,totaal:5,bestand:1,bestanden:1}));await p.waitForSelector('#vg',{state:'visible'});
  assert.equal(await p.textContent('#vg-wat'),'Adding book');assert.equal(await p.textContent('#vg-stand'),'Saving… 2 of 5');
  await p.evaluate(()=>onVoortgang({soort:'pdf',stap:'verwerken',gedaan:0,totaal:0,bestand:2,bestanden:4}));
  assert.equal(await p.textContent('#vg-wat'),'Adding files: file 2 of 4');assert.equal(await p.textContent('#vg-stand'),'Processing…');
  await p.evaluate(()=>rgSave({lang:'nl'}));
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
