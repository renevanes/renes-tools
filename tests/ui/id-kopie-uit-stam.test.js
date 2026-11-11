// ID-kopie afschermen vanaf het scherm zelf: bij "Foto of PDF kiezen" staat "Uit Stamgegevens (mijn documenten)" bovenaan.
// Stamgegevens gaat open (slotscherm), je kiest een identiteitsdocument met een foto (die van jezelf bovenaan) en de kopie
// begint met die foto, net als vanaf het documentkaartje (de foto gaat niet door de interface).
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  const foto=await p.evaluate(()=>{const c=document.createElement('canvas');c.width=80;c.height=50;c.getContext('2d').fillRect(0,0,10,10);return c.toDataURL('image/jpeg');});
  await p.evaluate(foto=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n;
    Android._cm.records=[{id:id(1),type:'persoon',label:'Rene',revision:1,fields:{naam:'Van Es',voornamen:'Rene'}},
      {id:id(4),type:'persoon',label:'Anna',revision:1,fields:{naam:'Jansen',voornamen:'Anna'}},
      {id:id(5),type:'profiel',label:'Mijn app-profiel',revision:1,fields:{persoon:id(1),delen:'nee'}},
      {id:id(6),type:'identiteit',label:'Paspoort Anna',revision:1,fields:{persoon:id(4),documentsoort:'paspoort',document:'NX98',geldig:'2034-01-01'},images:{front:foto}},
      {id:id(2),type:'identiteit',label:'Paspoort Rene',revision:1,fields:{persoon:id(1),documentsoort:'paspoort',document:'NX99',geldig:'2034-01-01'},images:{front:foto}},
      {id:id(3),type:'identiteit',label:'ID-kaart zonder foto',revision:1,fields:{persoon:id(1),documentsoort:'identiteitskaart',document:'IX1',geldig:'2034-01-01'}}];
    show('idcopy');
  },foto);
  await p.waitForFunction(()=>current==='idcopy');
  await p.click('#id-pick');await p.waitForSelector('#sheet.on');
  const keuzes=await p.$$eval('#sheet-acts button',x=>x.map(e=>e.textContent));
  assert.equal(keuzes[0],'Uit Stamgegevens (mijn documenten)','Stamgegevens bovenaan');assert.ok(keuzes.includes('Bestand op de telefoon'));
  await p.click('#sheet-acts >> text=Uit Stamgegevens (mijn documenten)');
  await p.waitForFunction(()=>tsZichtbaar()&&ts.tool==='masterdata'&&!$('#ts-open').disabled);await p.click('#ts-open');
  await p.waitForFunction(()=>$('#sheet').classList.contains('on')&&$('#sheet-title').textContent==='Welk document?');
  assert.deepEqual(await p.$$eval('#sheet-acts button',x=>x.map(e=>e.textContent)),['Paspoort Rene','Paspoort Anna'],'alleen met foto, eigen documenten eerst');
  await p.click('#sheet-acts >> text=Paspoort Rene');
  await p.waitForFunction(()=>current==='idcopy'&&idc.pages.length===1&&!idc.loading&&!idc.busy);
  assert.equal(await p.textContent('#id-bron-naam'),'Paspoort Rene');
  assert.equal(await p.evaluate(()=>Android._idc.master),true,'foto kwam uit Stamgegevens (niet via de interface)');
  assert.equal(await p.evaluate(()=>idc.pages[0].rects.length),5,'voorstel voor een paspoort');
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
