// Je handtekening is één onderdeel (web/src/script/02-handtekening.js) voor de hele app: op je eigen kaartje in
// Stamgegevens tekenen, zien en verwijderen; PDF en Formulieren zien dezelfde; op slot gaat het venster dicht; alleen dat
// bestand gebruikt de opslag (Android.pdfSignature*). Licht en donker, 390 en 320 px, en in het Engels.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');const fs=require('fs');
const src=path.join(__dirname,'../../web');
const bronnen=[...fs.readdirSync(path.join(src,'src/script')).map(f=>'src/script/'+f),...fs.readdirSync(path.join(src,'src/schermen')).map(f=>'src/schermen/'+f),'src/vensters.html','start.html'];
for(const f of bronnen){
  if(/\/(02-handtekening|01-testbrug)\.js$/.test(f))continue;
  assert.doesNotMatch(fs.readFileSync(path.join(src,f),'utf8'),/Android\.pdfSignature(Load|Save|Delete)/,f+': gebruik sigLoad/sigSave/sigVraagWeg uit 02-handtekening.js');
}
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const me='00000000-0000-0000-0000-000000000001';
  await p.evaluate(()=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n,rec=(n,type,label,fields)=>({id:id(n),type,label,revision:1,fields});
    Android._cm.records=[rec(1,'persoon','Rene van Es',{roepnaam:'Rene',voornamen:'Rene',naam:'van Es'}),rec(2,'profiel','Mijn app-profiel',{persoon:id(1)})];
  });
  const draw=async()=>{
    await p.waitForSelector('#sigdlg.on');const r=await p.locator('#sig-pad').boundingBox();
    await p.mouse.move(r.x+20,r.y+50);await p.mouse.down();await p.mouse.move(r.x+90,r.y+90,{steps:6});await p.mouse.move(r.x+170,r.y+30,{steps:6});await p.mouse.up();
  };
  const inkt=sel=>p.evaluate(sel=>{const c=$(sel),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4)if(d[i]>0)n++;return n;},sel);
  const unlock=async()=>{await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);await T.kaart(me);};

  // 1. Je eigen kaartje: nog geen handtekening, tekenen en bewaren (blauw)
  await unlock();
  assert.equal(await p.isVisible('#cmc-sig'),true,'blok Handtekening op je eigen kaartje');
  assert.match(await p.textContent('#cmc-sig'),/Nog geen handtekening/);assert.equal(await p.locator('#cmc-sig-weg').count(),0);assert.equal(await p.locator('#cmc-sig-voorbeeld').count(),0);
  const events=await p.evaluate(()=>Android._cm.events.length);
  await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');
  assert.equal(await p.textContent('#sig-title'),'Handtekening tekenen');assert.equal(await p.textContent('#sig-ok'),'Bewaren');
  for(const s of ['#sig-ok','#sig-cancel','#sig-wis','#sig-kleur']){const r=await p.evaluate(s=>[$(s).offsetWidth,$(s).offsetHeight],s);assert.ok(r[0]>=44&&r[1]>=44,s+' tikvlak '+r);}
  await draw();await p.selectOption('#sig-kleur','#1649b8');await p.click('#sig-ok');await p.waitForFunction(()=>!sigOpen());
  const sigBewaard=await p.evaluate(()=>Android._signature);assert.equal(sigBewaard.color,'#1649b8');assert.ok(sigBewaard.paths.length);
  assert.equal(await p.textContent('#toast'),'Handtekening bewaard');
  assert.equal(await p.isVisible('#cmc-sig-voorbeeld'),true);assert.ok(await inkt('#cmc-sig-voorbeeld')>50,'voorbeeld toont de handtekening');
  assert.equal(await p.textContent('#cmc-sig-teken'),'Opnieuw tekenen');assert.equal(await p.isVisible('#cmc-sig-weg'),true);
  assert.equal(await p.evaluate(()=>Android._cm.events.length),events,'niet in de kluis van Stamgegevens');
  assert.doesNotMatch(await p.evaluate(()=>JSON.stringify(Android._cm.records)),/paths/);
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  await p.locator('#cmc-sig').scrollIntoViewIfNeeded();await p.screenshot({path:__dirname+'/shots/handtekening-kaart-'+colorScheme+'.png'});
  // Alleen op je eigen kaartje
  await p.evaluate(()=>Android._cm.records.push({id:'00000000-0000-0000-0000-000000000003',type:'persoon',label:'Anna',revision:1,fields:{voornamen:'Anna'}}));
  await p.evaluate(()=>cmListLoad(()=>{}));await p.waitForFunction(()=>cm.recordsReady&&!cm.pending.size);
  await T.kaart('00000000-0000-0000-0000-000000000003');assert.equal(await p.locator('#cmc-sig').count(),0,'niet bij een ander');

  // 2. PDF ziet dezelfde handtekening en kan hem plaatsen
  await p.evaluate(()=>show('pdfedit'));assert.equal(await p.isVisible('#pdf-signature-preview'),true);assert.ok(await inkt('#pdf-signature-preview')>50);
  assert.match(await p.textContent('#pdf-signature-status'),/versleuteld bewaard/);
  await p.click('#pdf-edit-pick');await p.waitForFunction(()=>pdfEd.meta[0]&&!pdfEd.loading);await p.click('#pdf-signature-use');
  assert.deepEqual(await p.evaluate(()=>pdfEd.items[0].paths),sigBewaard.paths);assert.equal(await p.evaluate(()=>pdfEd.items[0].color),'#1649b8');
  await p.evaluate(()=>{pdfEd.items=[];});
  // Formulieren tekent dezelfde, vers uit de opslag
  assert.deepEqual(await p.evaluate(()=>fmHandtekening()),sigBewaard);

  // 3. Op slot terwijl het venster vanuit Stamgegevens open is: het venster gaat dicht en er wordt niets bewaard
  await p.waitForFunction(()=>!cmOpen());await unlock();await p.click('#cmc-sig-teken');await draw();
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!cm.token&&!sigOpen());
  assert.deepEqual(await p.evaluate(()=>Android._signature),sigBewaard);assert.equal(await p.evaluate(()=>sig.pad.strokes.length),0,'tekening gewist');

  // 4. Verwijderen vanaf het kaartje (met bevestiging); PDF weet het meteen
  await unlock();await p.click('#cmc-sig-weg');await p.waitForSelector('#modal.on');assert.match(await p.textContent('#modal'),/Bewaarde handtekening verwijderen\?/);
  await p.click('#modal-cancel');assert.ok(await p.evaluate(()=>Android._signature));
  await p.click('#cmc-sig-weg');await p.click('#modal-ok');await p.waitForFunction(()=>!Android._signature);
  assert.match(await p.textContent('#cmc-sig'),/Nog geen handtekening/);assert.equal(await p.textContent('#cmc-sig-teken'),'Handtekening tekenen');
  assert.equal(await p.evaluate(()=>{show('pdfedit');return $('#pdf-signature-preview').hidden;}),true,'PDF: weg');

  // 5. Een fout bij het lezen staat op het kaartje; verwijderen blijft mogelijk
  await p.evaluate(()=>{Android._signatureReadError='Handtekening is niet te lezen';sigLoad();});await p.waitForFunction(()=>!cmOpen());await unlock();
  assert.match(await p.textContent('#cmc-sig'),/niet te lezen/);assert.equal(await p.isVisible('#cmc-sig-weg'),true);
  await p.evaluate(()=>{Android._signatureReadError='';sigLoad();});

  // 6. Smal scherm en Engels
  await p.setViewportSize({width:320,height:740});await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');
  const pad=await p.locator('#sig-pad').boundingBox(),box=await p.locator('.sig-box').boundingBox();
  assert.ok(pad.height>=100&&box.x>=0&&box.x+box.width<=320,'venster past op 320 px');
  await p.screenshot({path:__dirname+'/shots/handtekening-venster-'+colorScheme+'.png'});await p.evaluate(()=>goBack());assert.equal(await p.evaluate(()=>sigOpen()||current),'cmkaart');
  await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForTimeout(150);await T.kaart(me);
  assert.equal(await p.textContent('#cmc-sig h2'),'Signature');assert.equal(await p.textContent('#cmc-sig-teken'),'Draw signature');
  await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');
  assert.equal(await p.textContent('#sig-title'),'Draw signature');assert.equal(await p.textContent('#sig-ok'),'Save');assert.equal(await p.textContent('#sig-cancel'),'Cancel');
  await p.click('#sig-ok');assert.equal(await p.textContent('#sig-fout'),'Draw your signature first');
  await p.click('#sig-cancel');await p.evaluate(()=>rgSave({lang:'nl'}));
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
