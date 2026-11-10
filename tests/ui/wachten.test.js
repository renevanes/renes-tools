// Wachten zonder raden: uiBezig (balk onderin tot de terugroepfunctie komt), uiBezigNu (eerst tekenen, dan een trage brugfunctie)
// en uiWachtTekst (een statusregel met een lopende balk eronder, weg zodra de tekst iets anders wordt).
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);

  // 1. uiBezig met een terugroepfunctie: na 400 ms zichtbaar, weg zodra die komt; de functie zelf werkt gewoon.
  await p.evaluate(()=>{window.kreeg=[];window.onProefKlaar=function(a,b){kreeg.push([a,b]);};uiBezig(t('Bezig met openen…'),'onProefKlaar');});
  assert.equal(await p.isVisible('#vg'),false,'niet meteen');
  await p.waitForSelector('#vg',{state:'visible'});
  assert.equal(await p.textContent('#vg-wat'),'Bezig met openen…');assert.equal(await p.textContent('#vg-stand'),'');
  assert.equal(await p.evaluate(()=>$('#vg-prog').classList.contains('busy')),true,'balk zonder getal');
  await p.evaluate(()=>onProefKlaar(1,'x'));
  assert.equal(await p.isVisible('#vg'),false,'weg bij het antwoord');
  assert.deepEqual(await p.evaluate(()=>kreeg),[[1,'x']]);
  await p.evaluate(()=>onProefKlaar(2,'y'));assert.deepEqual(await p.evaluate(()=>kreeg.length),2,'één keer ingepakt');
  // Een oudere uiBezig die klaar is, haalt de nieuwere niet weg; een lijst namen kan ook.
  await p.evaluate(()=>{window.oud=uiBezig(t('Bezig met tellen…'));uiBezig(t('Bezig met bewaren…'),['onProefKlaar','onProefAnders']);window.onProefAnders=window.onProefAnders||null;});
  await p.waitForSelector('#vg',{state:'visible'});await p.evaluate(()=>oud());
  assert.equal(await p.isVisible('#vg'),true);assert.equal(await p.textContent('#vg-wat'),'Bezig met bewaren…','de nieuwste tekst');
  await p.evaluate(()=>onProefKlaar());assert.equal(await p.isVisible('#vg'),false);
  // Echte plek: een backup openen (archief kiezen → onArchive).
  // De brug antwoordt pas als de test de balk gezien heeft (geen wedloop met de 400 ms op een drukke testmachine).
  await p.evaluate(()=>{window.archiefOpen=Android.archiveOpen;Android.archiveOpen=function(pw){window.antwoord=()=>archiefOpen.call(Android,pw);};onArchivePicked();});
  await p.waitForSelector('#vg',{state:'visible'});assert.equal(await p.textContent('#vg-wat'),'Bezig met openen…');
  await p.evaluate(()=>antwoord());await p.waitForSelector('#vg',{state:'hidden'});
  await p.evaluate(()=>closeSheet());
  // Herstel → oudere backup: dat antwoord komt via onArchive (het menu met keuzes), daar gaat de balk weg.
  await p.evaluate(()=>{hsName='oud.rtb';hsPwOld='goedwachtwoord';hsOld();});
  await p.waitForSelector('#vg',{state:'visible'});assert.equal(await p.textContent('#vg-wat'),'Bezig met openen…');
  await p.evaluate(()=>{Android.archiveOpen=archiefOpen;antwoord();});await p.waitForSelector('#vg',{state:'hidden'});
  assert.equal(await p.evaluate(()=>$('#sheet').classList.contains('on')),true,'het menu staat er, de balk niet meer');
  await p.evaluate(()=>closeSheet());
  // Een menu dat dichtgaat terwijl het nog zoekt (Chromecast), laat geen lopende balk achter.
  await p.evaluate(()=>{openSheet('Waar speelt het?','',[]);uiWachtTekst($('#sheet-sub'),t('Zoeken naar Chromecasts in je wifi…'));closeSheet();});
  assert.equal(await p.evaluate(()=>$('#sheet-sub').classList.contains('wacht')),false);

  // 2. uiBezigNu: de balk staat er al voordat de (trage) brugfunctie begint, en gaat daarna weg.
  const nu=await p.evaluate(async()=>{let zichtbaar=null;const r=await uiBezigNu(t('Notities opslaan…'),()=>{zichtbaar=!$('#vg').hidden&&$('#vg-wat').textContent;return 'ok:3';});return [zichtbaar,r,$('#vg').hidden];});
  assert.deepEqual(nu,['Notities opslaan…','ok:3',true]);
  const fout=await p.evaluate(()=>uiBezigNu('x',()=>{throw new Error('kapot');}).then(()=>'geen',e=>e.message+'|'+$('#vg').hidden));
  assert.equal(fout,'kapot|true','een fout komt door en de balk gaat weg');

  // 3. uiWachtTekst: lopende balk onder de regel; weg zodra de tekst iets anders wordt (antwoord of fout).
  await p.evaluate(()=>{const e=document.createElement('p');e.id='proef-st';document.querySelector('#s-home').append(e);uiWachtTekst(e,t('Zoeken…'));});
  // Geen aria-busy: dan zou een schermlezer de regel (een live-gebied) juist niet voorlezen.
  assert.deepEqual(await p.evaluate(()=>{const e=$('#proef-st');return [e.textContent,e.classList.contains('wacht'),e.hasAttribute('aria-busy')];}),['Zoeken…',true,false]);
  const css=await p.evaluate(()=>{const s=getComputedStyle($('#proef-st'),'::after');return [s.animationName,s.position,parseFloat(getComputedStyle($('#proef-st')).paddingBottom)];});
  assert.equal(css[0],'wachtLoop');assert.equal(css[1],'absolute');assert.ok(css[2]>=10,'ruimte voor de balk');
  await p.evaluate(()=>uiWachtTekst($('#proef-st'),t('Bijwerken… 3 van 10')));await p.waitForTimeout(30);
  assert.equal(await p.evaluate(()=>$('#proef-st').classList.contains('wacht')),true,'nieuwe stand: balk blijft');
  await p.evaluate(()=>{$('#proef-st').textContent='3 gevonden';});
  await p.waitForFunction(()=>!$('#proef-st').classList.contains('wacht'));
  await p.evaluate(()=>{uiWachtTekst($('#proef-st'),t('Zoeken…'));uiWachtTekst($('#proef-st'),'');});
  assert.equal(await p.evaluate(()=>$('#proef-st').className+'|'+$('#proef-st').textContent),'|');
  // Een plaatshouder met de klasse wacht (wordt bij het antwoord helemaal vervangen) blijft staan.
  await p.evaluate(()=>{$('#proef-st').innerHTML='<span class="wacht" id="proef-ph">Laden…</span>';const e=document.createElement('p');e.id='proef-2';document.querySelector('#s-home').append(e);uiWachtTekst(e,'a');e.textContent='b';});
  await p.waitForFunction(()=>!$('#proef-2').classList.contains('wacht'));
  assert.equal(await p.evaluate(()=>$('#proef-ph').classList.contains('wacht')),true);
  // De balk van uiBezig/Voortgang loopt via transform: die blijft lopen terwijl de app even rekent.
  assert.match(await p.evaluate(()=>[...document.styleSheets].flatMap(s=>{try{return [...s.cssRules];}catch(e){return [];}}).filter(r=>r.name==='updBusy').map(r=>r.cssText).join()),/transform/);

  // 4. PDF: de bezigregel krijgt de balk en verliest hem als het klaar is; ook in het Engels.
  await p.evaluate(()=>pdfSetBusy('PDF maken…'));
  assert.deepEqual(await p.evaluate(()=>[$('#pdf-busy').textContent,$('#pdf-busy').classList.contains('wacht')]),['PDF maken…',true]);
  await p.evaluate(()=>pdfSetBusy(''));await p.waitForTimeout(30);
  assert.equal(await p.evaluate(()=>$('#pdf-busy').classList.contains('wacht')),false);
  await p.evaluate(()=>{rgSave({lang:'en'});taalApply();});await p.waitForFunction(()=>TAAL.on);
  await p.evaluate(()=>pdfSetBusy('PDF maken…'));await p.waitForTimeout(80);
  assert.deepEqual(await p.evaluate(()=>[$('#pdf-busy').textContent,$('#pdf-busy').classList.contains('wacht')]),[await p.evaluate(()=>t('PDF maken…')),true]);
  await p.evaluate(()=>pdfSetBusy(''));
  await p.evaluate(()=>{uiBezig(t('Bezig met openen…'),'onProefKlaar');});await p.waitForSelector('#vg',{state:'visible'});
  assert.equal(await p.textContent('#vg-wat'),'Opening…');await p.evaluate(()=>onProefKlaar());
  await p.evaluate(()=>rgSave({lang:'nl'}));
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
