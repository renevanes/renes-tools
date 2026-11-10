// Tekst over jezelf uit de oude indeling: bij ontgrendelen vanzelf naar Over mij van de assistent als je hem al deelde,
// anders bij Aandacht nodig en op je kaartje met Naar Over mij en Weghalen. De Play-versie heeft geen assistent: daar blijft
// de tekst staan en kun je hem alleen weghalen.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
const W=+process.env.UI_WACHT||1;
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark'])for(const play of [false,true]){
  const tag=colorScheme+(play?' (Play)':''),p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.addInitScript(v=>window.__testPlay=v,play);
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300*W);const T=require('./_stamtabs')(p);
  const me='00000000-0000-0000-0000-000000000001';
  const zet=delen=>p.evaluate(([me,delen])=>{
    Android._cm.records=[{id:me,type:'persoon',label:'Rene van Es',revision:1,fields:{voornamen:'Rene',naam:'van Es',context:'Ik ben vegetarisch'}},
      {id:'00000000-0000-0000-0000-000000000009',type:'profiel',label:'Mijn app-profiel',revision:1,fields:{persoon:me,delen}}];
    Android._ai.cfg=Object.assign({},Android._aiCfgDef,{about:''});Android._cmContextRefresh();
  },[me,delen]);
  const over=()=>p.evaluate(()=>(Android._ai.cfg||{}).about||'');
  const oud=()=>p.evaluate(me=>Android._cm.records.find(r=>r.id===me).fields.context||'',me);
  const open=async()=>{await p.evaluate(()=>show('masterdata'));await p.click('#ts-open');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady&&!cm.pending.size);};
  const dicht=()=>p.evaluate(()=>{cmLock();show('home');});
  const aandacht=()=>p.$$eval('#cm-aandacht-list .strow b',r=>r.map(x=>x.textContent));

  // Al gedeeld: bij ontgrendelen vanzelf naar Over mij (niet in de Play-versie)
  await zet('ja');await open();
  if(!play){
    assert.equal(await over(),'Ik ben vegetarisch',tag+': vanzelf bij Over mij');assert.equal(await oud(),'',tag+': niet meer in Stamgegevens');
    assert.equal((await aandacht()).includes('Tekst over jou'),false);
    await T.kaart(me);assert.equal(await p.isVisible('#cmc-over-oud'),false,tag+': niets meer te doen');
  }else{
    assert.equal(await over(),'',tag+': Play: niet naar de assistent');assert.equal(await oud(),'Ik ben vegetarisch');
    assert.equal((await aandacht()).includes('Tekst over jou'),false,tag+': Play: geen Aandacht nodig');
    await T.kaart(me);assert.equal(await p.isVisible('#cmc-over-oud'),true);
    assert.equal(await p.isVisible('#cmc-over-naar'),false,tag+': Play: geen Naar Over mij');assert.equal(await p.isVisible('#cmc-over-mij'),false);
    assert.match(await p.textContent('#cmc-over-oud'),/geen assistent/);
    await p.click('#cmc-over-weg');await p.waitForSelector('#modal.on');
    assert.equal(await p.textContent('#modal-text'),'De tekst wordt uit Stamgegevens verwijderd.',tag+': Play: geen Over mij in de vraag');
    await p.click('#modal-ok');await p.waitForFunction(()=>current==='cmkaart'&&$('#cm-kaart-status').textContent==='De tekst over jou is weggehaald.');
    assert.equal(await oud(),'');assert.equal(await p.isVisible('#cmc-over-oud'),false);
    // Ook via de brug niet naar Over mij
    assert.equal(await p.evaluate(()=>new Promise(ok=>cmRequest('masterAboutMove',['verplaats'],()=>ok('gelukt'),false,r=>ok(r.error)))),'Deze functie zit niet in de Play-versie');
  }
  await dicht();

  // Nog niet gedeeld: de tekst blijft staan; Aandacht nodig en het kaartje bieden Naar Over mij aan
  if(!play){
    await zet('nee');await open();
    assert.equal(await over(),'');assert.equal(await oud(),'Ik ben vegetarisch',tag+': niet gedeeld: blijft staan');
    assert.equal((await aandacht()).includes('Tekst over jou'),true,tag+': bij Aandacht nodig');
    await p.locator('#cm-aandacht-list .strow',{hasText:'Tekst over jou'}).click();await p.waitForFunction(me=>current==='cmkaart'&&cmc.rec?.id===me&&!cm.pending.size,me);
    assert.equal(await p.isVisible('#cmc-over-oud'),true);assert.equal(await p.isVisible('#cmc-over-naar'),true);
    assert.equal(await p.textContent('#cmc-over-oud .cmc-tekst'),'Ik ben vegetarisch');
    await p.click('#cmc-over-naar');await p.waitForFunction(()=>current==='cmkaart'&&$('#cm-kaart-status').textContent==='De tekst staat nu bij Over mij van de assistent.');
    assert.equal(await over(),'Ik ben vegetarisch');assert.equal(await oud(),'');assert.equal(await p.isVisible('#cmc-over-oud'),false);
    await dicht();
  }
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
