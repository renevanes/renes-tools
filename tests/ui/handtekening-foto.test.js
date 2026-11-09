// Handtekening uit een foto of bestand (02-handtekening.js: sigFoto, sigLijnen): kiezen houdt Stamgegevens open, de inkt wordt
// lijnen op het tekenvlak (daarna gewoon bewaren, zelfde formaat als getekend), papier zonder inkt of annuleren geeft een
// duidelijke stand, en zonder de brugfunctie (oudere APK) is de knop er niet. Licht en donker, en in het Engels.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const me='00000000-0000-0000-0000-000000000001';
  await p.evaluate(()=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n,rec=(n,type,label,fields)=>({id:id(n),type,label,revision:1,fields});
    Android._cm.records=[rec(1,'persoon','Rene van Es',{roepnaam:'Rene',voornamen:'Rene',naam:'van Es'}),rec(2,'profiel','Mijn app-profiel',{persoon:id(1)})];
  });
  const inkt=()=>p.evaluate(()=>{const c=$('#sig-pad'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4)if(d[i]>0)n++;return n;});
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);await T.kaart(me);

  // 1. Uit een foto: de lijnen staan op het tekenvlak, bewaren geeft een gewone (geldige) handtekening
  await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');
  assert.equal(await p.isVisible('#sig-foto'),true);assert.equal(await p.textContent('#sig-foto'),'Uit foto of bestand');
  const maat=await p.evaluate(()=>[$('#sig-foto').offsetWidth,$('#sig-foto').offsetHeight]);assert.ok(maat[0]>=44&&maat[1]>=44,'tikvlak '+maat);
  await p.click('#sig-foto');
  await p.waitForFunction(()=>/Zo ziet je handtekening eruit/.test($('#sig-status').textContent));
  assert.equal(await p.evaluate(()=>Android._sigPicks.at(-1)===cm.token&&!!cm.token),true,'kiezen houdt Stamgegevens open');
  assert.ok(await inkt()>300,'handtekening op het tekenvlak');assert.equal(await p.textContent('#sig-fout'),'');
  const blauw=await p.evaluate(()=>{$('#sig-kleur').value='#1649b8';$('#sig-kleur').dispatchEvent(new Event('change'));const c=$('#sig-pad'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
    for(let i=0;i<d.length;i+=4)if(d[i+3]===255)return [d[i],d[i+1],d[i+2]].join(',');return '';});
  assert.equal(blauw,'22,73,184','andere kleur tekent de lijnen opnieuw');
  await p.click('#sig-ok');await p.waitForFunction(()=>!sigOpen());
  const s=await p.evaluate(()=>Android._signature);
  assert.equal(await p.evaluate(s=>sigValid(s),s),true,'geldige handtekening');assert.equal(s.color,'#1649b8');
  assert.ok(s.paths.length>=2&&s.paths.length<=6,'lijn en stip, geen ruis of schaduw: '+s.paths.length);
  assert.ok(s.ratio>.25&&s.ratio<.75,'verhouding als op de foto: '+s.ratio);
  const xs=s.paths.flat().map(q=>q[0]);assert.ok(Math.max(...xs)-Math.min(...xs)>.85,'hele breedte');
  assert.equal(await p.textContent('#toast'),'Handtekening bewaard');

  // 2. Papier zonder inkt, een onleesbaar bestand en annuleren
  await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');
  await p.evaluate(()=>Android._sigFoto='leeg');await p.click('#sig-foto');await p.waitForFunction(()=>!!$('#sig-fout').textContent);
  assert.match(await p.textContent('#sig-fout'),/Geen (duidelijke )?handtekening gevonden/);assert.equal(await inkt(),0);assert.equal(await p.textContent('#sig-status'),'');
  await p.evaluate(()=>Android._sigFoto='fout');await p.click('#sig-foto');await p.waitForFunction(()=>$('#sig-fout').textContent==='Deze afbeelding kan niet worden geopend');
  await p.evaluate(()=>Android._sigFoto='annuleer');await p.click('#sig-foto');await p.waitForFunction(()=>!sig.kiezen);
  assert.equal(await p.textContent('#sig-status'),'');assert.equal(await p.textContent('#sig-fout'),'');
  // Een antwoord dat pas komt als het venster al dicht is, doet niets
  await p.evaluate(()=>{Android._sigFoto=null;sigFoto();sigClose();});await p.waitForTimeout(150);assert.equal(await p.evaluate(()=>sigOpen()),false);
  assert.equal(await p.textContent('#cmc-sig-teken'),'Opnieuw tekenen');

  // 3. De herkenning zelf: een dikke ring wordt één gesloten lijn, alleen ruis is geen handtekening
  const ring=await p.evaluate(()=>{const w=300,h=200,g=new Uint8Array(w*h).fill(235);
    for(let y=0;y<h;y++)for(let x=0;x<w;x++){const d=Math.hypot(x-150,y-100);if(Math.abs(d-60)<6)g[y*w+x]=30;}
    const s=sigLijnen(g,w,h);const l=s.paths.reduce((a,c)=>c.length>a.length?c:a);return {n:s.paths.length,ratio:s.ratio,dicht:Math.hypot(l[0][0]-l.at(-1)[0],l[0][1]-l.at(-1)[1])};});
  assert.equal(ring.n,1,'één lijn');assert.ok(Math.abs(ring.ratio-1)<.05,'rond '+ring.ratio);assert.ok(ring.dicht<.08,'gesloten '+ring.dicht);
  const rand=await p.evaluate(()=>{const w=300,h=200,g=new Uint8Array(w*h).fill(235);
    for(let y=0;y<h;y++)for(let x=0;x<w;x++){if(y>=95&&y<105&&x>=60&&x<240)g[y*w+x]=30;if(x<4&&y>20&&y<60)g[y*w+x]=40;if(y>h-8&&x>100&&x<200)g[y*w+x]=20;}
    const s=sigLijnen(g,w,h);return {n:s.paths.length,ratio:s.ratio};});
  assert.equal(rand.n,1,'kleine stukken aan de rand (tafel, schaduw) vallen weg');assert.ok(rand.ratio<.2,'alleen de streep: '+rand.ratio);
  const ruis=await p.evaluate(()=>{const w=200,h=120,g=new Uint8Array(w*h);for(let i=0;i<g.length;i++)g[i]=Math.imul(i+1,2654435761)>>>24;
    try{sigLijnen(g,w,h);return 'ok';}catch(e){return e.message;}});
  assert.match(ruis,/Geen (duidelijke )?handtekening gevonden/);

  // 4. Engels, en een oudere APK zonder de brugfunctie
  await p.evaluate(()=>rgSave({lang:'en'}));await p.waitForTimeout(150);await T.kaart(me);
  await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');assert.equal(await p.textContent('#sig-foto'),'From photo or file');
  await p.evaluate(()=>Android._sigFoto='fout');await p.click('#sig-foto');await p.waitForFunction(()=>$('#sig-fout').textContent==='This image cannot be opened');
  await p.click('#sig-cancel');await p.evaluate(()=>rgSave({lang:'nl'}));
  await p.evaluate(()=>{Android.pdfSignaturePick=undefined;});await p.click('#cmc-sig-teken');await p.waitForSelector('#sigdlg.on');
  assert.equal(await p.isVisible('#sig-foto'),false,'zonder brugfunctie geen knop');await p.click('#sig-cancel');
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
