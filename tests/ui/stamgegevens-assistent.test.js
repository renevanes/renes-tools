// Stamgegevens en de assistent: Wat weet de assistent? (op slot alleen bekijken; delen, soorten gegevens en per persoon
// kiezen), Over mij bij de assistent, en "bel mijn dochter"/"call mum" via de relatie en het gekoppelde contact.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const id=n=>'00000000-0000-0000-0000-00000000000'+n,me=id(1),anna=id(2),ria=id(3),kees=id(4);
  await p.evaluate(()=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n,rec=(n,type,label,fields)=>({id:id(n),type,label,revision:1,fields});
    Android._cm.records=[
      rec(1,'persoon','Rene van Es',{roepnaam:'Rene',voornamen:'Rene',naam:'van Es',geboortedatum:'1980-01-01',plaats:'Rotterdam'}),
      rec(2,'persoon','Anna van Es',{voornamen:'Anna',naam:'van Es',geboortedatum:'2015-02-03',contact:'c1'}),
      rec(3,'persoon','Ria Jansen',{voornamen:'Ria',naam:'Jansen',contact:'c2'}),
      rec(4,'persoon','Kees',{roepnaam:'Kees',delen:'nee'}),
      rec(5,'relatie','Anna (mijn kind)',{vanPersoon:id(1),naarPersoon:id(2),relatie:'kind',status:'gezag',delen:'ja'}),
      rec(6,'relatie','Ria (mijn ouder)',{vanPersoon:id(1),naarPersoon:id(3),relatie:'ouder',delen:'ja'}),
      rec(7,'identiteit','Paspoort Anna',{persoon:id(2),documentsoort:'paspoort',document:'NX12345',geldig:'2030-01-01'}),
      rec(8,'profiel','Mijn app-profiel',{persoon:id(1),delen:'nee'})];
    Android._cm.contacts={c1:{weergave:'Anna van Es',fields:{telefoon:'06 1111 2222'}},c2:{weergave:'Ria Jansen',fields:{telefoon:'06 3333 4444'}}};
    Android._cmContextRefresh();Android._ai.st.klein={state:'ready'};
  });
  const sw=s=>p.evaluate(s=>{const b=$(s);return b.getAttribute('aria-checked')+'|'+b.classList.contains('on');},s);
  const shared=()=>p.evaluate(()=>JSON.parse(Android.appIdentity()||'{}'));
  const wachtOp=f=>p.waitForFunction(f);

  // 1. Op slot: het centrale slotscherm van Extra ontgrendelen; Stamgegevens zelf heeft geen slotkaart of ontgrendelknop
  await p.evaluate(()=>show('masterdata'));assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='masterdata'),true,'het centrale slotscherm');
  assert.equal(await p.locator('[onclick^="cmUnlock"]').count(),0,'geen eigen ontgrendelknop');
  assert.equal(await p.textContent('#cmi-shared'),'De assistent weet nu niets uit Stamgegevens.');

  // 2. Ontgrendeld: delen staat uit; aanzetten deelt jou en je mensen, met de vinkjes (woonplaats standaard uit)
  await p.click('#ts-open');await wachtOp(()=>cmOpen()&&cm.recordsReady);
  assert.match(await p.textContent('#cm-stand-assistent'),/.+/);await p.click('#cm-row-assistent');await wachtOp(()=>current==='cmai'&&!$('#cm-ai-set').hidden);
  assert.equal(await sw('#cm-ai-delen'),'false|false');assert.equal(await p.textContent('#cm-ai-delen-s'),'De assistent weet nu niets uit Stamgegevens.');
  assert.equal(await p.isVisible('#cm-ai-soorten'),false);assert.equal(await p.isVisible('#cm-ai-mensen-card'),false);
  await p.click('#cm-ai-delen');await wachtOp(()=>appIdentity.self?.callname==='Rene'&&$('#cm-ai-status').textContent==='De assistent kent nu jou en je mensen.');
  assert.equal(await sw('#cm-ai-delen'),'true|true');assert.equal(await p.textContent('#cm-ai-delen-s'),'De assistent weet wie jij bent en wie je mensen zijn.');
  for(const k of ['aiStatus','aiGeboorte','aiDocumenten','aiContact'])assert.equal(await sw('#cm-ai-'+k),'true|true',k);
  assert.equal(await sw('#cm-ai-aiWoonplaats'),'false|false','woonplaats standaard uit');
  let s=await shared();
  assert.deepEqual(s.people.map(x=>x.name).sort(),['Anna van Es','Ria Jansen'],'Kees staat uit');
  const a=s.people.find(x=>x.name==='Anna van Es');
  assert.deepEqual([a.relations,a.status,a.geboortedatum,a.contact,a.documents],[['kind'],['gezag'],'2015-02-03','c1',[{soort:'paspoort',geldig:'2030-01-01'}]]);
  assert.equal(s.self.plaats,undefined);assert.equal(JSON.stringify(s).includes('NX12345'),false,'nooit documentnummers');
  // Wie de assistent kent: per persoon, met wat die voor jou is
  assert.deepEqual(await p.$$eval('#cm-ai-mensen .cm-sw',r=>r.map(x=>x.querySelector('b').textContent+'|'+x.querySelector('small').textContent+'|'+x.querySelector('.tg').getAttribute('aria-checked'))),
    ['Anna van Es|Mijn kind · ik heb ouderlijk gezag|true','Kees|Geen relatie met jou|false','Ria Jansen|Mijn ouder|true']);

  // 3. Soorten gegevens uitzetten en aanzetten
  await p.click('#cm-ai-aiStatus');await wachtOp(()=>!cm.mutating&&$('#cm-ai-aiStatus').getAttribute('aria-checked')==='false'&&$('#cm-ai-status').textContent==='Bijgewerkt.');s=await shared();assert.equal(s.people.find(x=>x.name==='Anna van Es').status,undefined,'zonder status');
  await p.click('#cm-ai-aiGeboorte');await wachtOp(()=>!cm.mutating&&$('#cm-ai-aiGeboorte').getAttribute('aria-checked')==='false');
  s=await shared();assert.equal(s.people.some(x=>x.geboortedatum)||!!s.self.geboortedatum,false,'zonder geboortedatum');
  await p.click('#cm-ai-aiWoonplaats');await wachtOp(()=>!cm.mutating&&$('#cm-ai-aiWoonplaats').getAttribute('aria-checked')==='true');
  s=await shared();assert.equal(s.self.plaats,'Rotterdam');
  for(const k of ['aiStatus','aiGeboorte']){await p.click('#cm-ai-'+k);await p.waitForFunction(k=>!cm.mutating&&$('#cm-ai-'+k).getAttribute('aria-checked')==='true',k);}

  // 4. Per persoon: hier en op het kaartje
  await p.locator('#cm-ai-mensen .cm-sw',{hasText:'Kees'}).locator('.tg').click();
  await wachtOp(()=>(appIdentity.people||[]).some(x=>x.callname==='Kees')&&$('#cm-ai-status').textContent==='Kees is nu bekend bij de assistent.');
  await T.kaart(kees);assert.equal(await sw('#cmc-ai'),'true|true');
  await p.click('#cmc-ai');await wachtOp(()=>!(appIdentity.people||[]).some(x=>x.callname==='Kees')&&$('#cm-kaart-status').textContent==='De assistent kent Kees nu niet meer.');
  assert.equal(await sw('#cmc-ai'),'false|false');
  // Dit ziet de assistent: namen als tekst
  await p.evaluate(()=>cmaiOpen());await wachtOp(()=>current==='cmai');await p.click('#cm-ai-zie summary');
  assert.match(await p.textContent('#cmi-shared'),/Anna van Es/);assert.equal(await p.locator('#cmi-shared b b').count(),0);

  // 5. Over mij staat bij de assistent: vanaf je eigen kaartje (Stamgegevens gaat dan op slot)
  await T.kaart(me);await p.waitForSelector('#cmc-over-mij',{state:'visible',timeout:3000});
  await p.click('#cmc-over-mij');await wachtOp(()=>current==='aisetpers'&&document.activeElement?.id==='aic-about');
  assert.equal(await p.evaluate(()=>!cm.token),true,'Stamgegevens verlaten vergrendelt');
  // Assistent → Persoonlijk: de rij Wat weet de assistent? met de stand, ook op slot
  assert.equal(await p.textContent('#cmi-ai-summary'),'De assistent kent jou (Rene) en 2 personen.');
  await p.click('#aic-stam');await wachtOp(()=>tsZichtbaar()&&ts.doel==='cmai');assert.equal(await p.evaluate(()=>current),'aisetpers','eerst het slotscherm');
  await p.click('#ts-open');await wachtOp(()=>current==='cmai'&&cmOpen()&&!$('#cm-ai-set').hidden);
  assert.match(await p.textContent('#cmi-shared'),/Anna van Es/,'wat de assistent nu weet');
  assert.equal(await p.evaluate(()=>goBack()&&current),'aisetpers','terug naar de assistent');

  // 6. "bel mijn dochter": wie de assistent kent, met het nummer uit het gekoppelde contact; bellen doe je zelf
  await p.evaluate(()=>show('ai'));await p.waitForTimeout(150);
  const last='#ai-msgs .aimsg:last-child',card=last+' .cmdcard';
  const ask=async q=>{await p.fill('#ai-in',q);await p.click('#ai-send');await p.waitForSelector(card);};
  const done=()=>p.evaluate(()=>(Android._aiDone||[]).slice());
  await ask('bel mijn dochter');
  assert.match(await p.textContent(card),/Anna van Es/);assert.match(await p.textContent(card),/0611112222/);assert.equal((await done()).length,0,'nog niets gedaan');
  await p.click(card+' .cmdgo');await p.waitForFunction(()=>(Android._aiDone||[]).length===1);
  assert.deepEqual((await done())[0],{type:'bel',number:'0611112222'});assert.match(await p.textContent(card),/Nummer van Anna van Es klaargezet/);
  await ask('bel mama');assert.match(await p.textContent(card),/Ria Jansen/);assert.match(await p.textContent(card),/0633334444/);
  // Vinkje Bellen en berichten via Contacten uit: geen nummer via het gekoppelde contact, alleen op naam in je contacten
  await p.evaluate(()=>{Android._cm.records.find(r=>r.type==='profiel').fields.aiContact='nee';Android._cmContextRefresh();});
  assert.equal(await p.evaluate(()=>Android.aiContact('mijn dochter')),'[]');
  await ask('bel mijn dochter');assert.doesNotMatch(await p.textContent(last),/0611112222/);
  await p.evaluate(()=>{delete Android._cm.records.find(r=>r.type==='profiel').fields.aiContact;Android._cmContextRefresh();});
  // Engels: "call mum"
  await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForFunction(()=>TAAL.on);
  await ask('call mum');assert.match(await p.textContent(card),/Ria Jansen/);assert.match(await p.textContent(card),/0633334444/);
  assert.match(await p.textContent(card),/^Call\?Call Ria Jansen/);assert.equal(await p.textContent(card+' .cmdgo'),'Prepare number');
  await p.click(card+' .cmdgo');await p.waitForFunction(()=>(Android._aiDone||[]).length===2);assert.deepEqual((await done())[1],{type:'bel',number:'0633334444'});
  await p.evaluate(()=>rgSave({lang:'nl'}));
  assert.equal(await p.evaluate(()=>Object.keys(localStorage).some(k=>/stam|identity|profiel/.test(k))),false);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
