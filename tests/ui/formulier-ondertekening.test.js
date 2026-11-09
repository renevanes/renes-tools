// Vaste formuliergegevens met plaats van ondertekening en de eigen bewaarde handtekening bij een begeleider.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  await p.evaluate(()=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n,rec=(n,type,label,fields)=>({id:id(n),type,label,revision:1,fields});
    Android._cm.records=[
      rec(1,'persoon','Zoë',{naam:'Jansen',voornamen:'Zoë Anna',geboortedatum:'2015-02-03'}),
      rec(2,'persoon','Mama',{naam:'Jansen',voornamen:'Anna Maria',adres:'Straat 1',plaats:'Utrecht'}),
      rec(3,'persoon','Papa',{naam:'Jansen',voornamen:'Rene'})];
    Android._signature={version:1,ratio:.5,color:'#1a237e',paths:[[[0,0],[1,1]],[[0,1],[1,0]]]};
  });
  // 1. Vaste gegevens: tabblad Ondertekenen met plaats en bij welke begeleider de handtekening komt.
  await p.evaluate(()=>{openTool('pdf');});await p.getByRole('button',{name:'Formulieren invullen',exact:true}).click();
  await p.click('#fmq-instellen');await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.edit&&cm.edit.type==='formulier');
  assert.equal(await p.isVisible('#cm-tab-ondertekenen'),true,'tabblad Ondertekenen');
  assert.deepEqual(await p.$$eval('#cm-field-handtekening option',o=>o.map(x=>x.value)),['','begeleider1','begeleider2'],'nooit bij een toestemminggever');
  await T.select('#cm-field-kind',{label:'Zoë'});await T.select('#cm-field-gever1',{label:'Mama'});
  await T.fill('#cm-field-tekenplaats','Rotterdam');assert.equal(await p.isVisible('#cmi-form-sign'),true,'uitleg bij ondertekenen');
  // Handtekening bij begeleider 2 terwijl er geen is: weigeren.
  await T.select('#cm-field-begeleider1',{label:'Papa'});await T.select('#cm-field-handtekening','begeleider2');
  await p.click('#cm-save');await p.waitForFunction(()=>!cm.mutating&&/meereist/.test($('#cm-edit-status').textContent));
  await T.select('#cm-field-handtekening','begeleider1');
  await p.click('#cm-save');await p.waitForFunction(()=>$('#cm-editor').hidden&&Android._cm.records.some(r=>r.type==='formulier'));
  const vast=await p.evaluate(()=>Android._cm.records.find(r=>r.type==='formulier').fields);
  assert.equal(vast.tekenplaats,'Rotterdam');assert.equal(vast.handtekening,'begeleider1');
  // 2. Nieuwe reis: de datum staat standaard op vandaag en komt met de plaats en handtekening bij de begeleider.
  assert.equal(await p.evaluate(()=>goBack()&&current),'formulieren');
  await p.click('#fmq-start');await p.waitForFunction(()=>fmq.open&&cmOpen()&&!$('#fmq-open').hidden&&fmq.records.length===1);
  assert.equal(await p.inputValue('#fmq-datum'),await p.evaluate(()=>rgDayKey(Date.now())),'datum standaard vandaag');
  await p.fill('#fmq-van','2027-07-15');await p.fill('#fmq-tot','2027-07-30');await p.fill('#fmq-land','Frankrijk');await p.fill('#fmq-datum','2027-07-01');
  await p.click('#fmq-ga');await p.waitForFunction(()=>!fmq.open&&fm.step===fmSecties().length&&fm.view==='stap');
  const v=await p.evaluate(()=>fm.values);
  assert.match(await p.textContent('#pff-melding'),/Je bewaarde handtekening staat bij Begeleider 1, op pagina 2\./,'melding zegt waar de handtekening staat');
  assert.equal(v['begeleider1-datum'],'2027-07-01');assert.equal(v['begeleider1-tekenplaats'],'Rotterdam');assert.equal(v['begeleider1-handtekening'],'ja');
  assert.equal(v['begeleider2-datum']||'','','geen datum voor een begeleider die er niet is');
  assert.equal(v['gever1-datum'],'');assert.equal(v['gever1-tekenplaats'],'','de toestemminggever vult zelf in');
  assert.equal(await p.evaluate(()=>fm.stam.has('begeleider1-handtekening')&&fm.stam.has('begeleider1-tekenplaats')),true,'uit Stamgegevens: niet in het concept');
  assert.ok(await p.evaluate(()=>Android._cm.events.some(e=>e.action==='use'&&e.role==='vast'&&e.fields.includes('tekenplaats')&&e.fields.includes('handtekening'))),'gebruik in het logboek');
  // De handtekening staat op pagina 2 in het vak boven de lijn van begeleider 1, niet bij begeleider 2 of de toestemminggever.
  const inkt=await p.evaluate(()=>{
    const c=fmTeken(document.createElement('canvas'),1),ctx=c.getContext('2d'),k=c.width/fmDef().pages[1].width;
    const ink=(x,y,w,h)=>{const d=ctx.getImageData(Math.round(x*k),Math.round(y*k),Math.round(w*k),Math.round(h*k)).data;let n=0;for(let i=3;i<d.length;i+=4)if(d[i]>0)n++;return n;};
    return {b1:ink(59.5,462,141,44),b2:ink(306.5,462,148,44),g1:ink(59.5,300,160,30)};
  });
  assert.ok(inkt.b1>50,'handtekening bij begeleider 1: '+inkt.b1);assert.equal(inkt.b2,0);assert.equal(inkt.g1,0);
  await p.waitForFunction(()=>!document.querySelector('#pff-maak').disabled);
  // De invulhulp (taalmodel) krijgt het handtekeningveld niet.
  const aiVelden=await p.evaluate(()=>{fm.step=fmSecties().findIndex(s=>s.fields.some(f=>f.id==='begeleider1-handtekening'));fmRender();
    $('#pff-hulp-tekst').value='Papa';fmh.sources=[{id:'00000000-0000-0000-0000-000000000003',label:'Papa',type:'persoon',role:'begeleider1',fields:{voornamen:'Rene',naam:'Jansen'}}];const o=Android.pdfFormAi;let got=null;Android.pdfFormAi=(tk,j)=>{got=JSON.parse(j);return 'Gestopt';};
    try{fmHelpAi();}finally{Android.pdfFormAi=o;fmHelpClear();}return got&&got.fields.map(f=>f.id);});
  assert.ok(aiVelden&&aiVelden.includes('begeleider1-datum')&&!aiVelden.includes('begeleider1-handtekening'),'invulhulp zonder handtekening: '+aiVelden);
  // Twee keer je eigen handtekening kan niet.
  assert.equal(await p.evaluate(()=>{const was={...fm.values};for(const k of Object.keys(was))if(k.startsWith('begeleider1-'))fm.values[k.replace('begeleider1-','begeleider2-')]=was[k];fm.values.tweedeBegeleider='ja';const ok=fmGeldig(true);fm.values=was;return ok;}),false,'hooguit één handtekening');
  assert.match(await p.textContent('#pff-melding'),/maar bij één persoon/);
  await p.evaluate(()=>{fm.step=fmSecties().length;fmRender();});
  // Zelf uitzetten kan bij de begeleider onder Meer gegevens.
  await p.evaluate(()=>{fm.step=fmSecties().findIndex(s=>s.fields.some(f=>f.id==='begeleider1-handtekening'));fmRender();});
  assert.equal(await p.isVisible('#pff-meer'),true);assert.equal(await p.evaluate(()=>$('#pff-meer').open),true,'open: er staat al iets in');
  await p.selectOption('#pff-v-begeleider1-handtekening','nee');
  assert.equal(await p.evaluate(()=>fm.stam.has('begeleider1-handtekening')),false,'zelf gekozen');
  // Geen bewaarde handtekening: duidelijke melding in plaats van een PDF zonder handtekening.
  await p.selectOption('#pff-v-begeleider1-handtekening','ja');await p.evaluate(()=>{Android._signature=null;fm.step=fmSecties().length;fm.page=1;fmRender();});
  await p.waitForFunction(()=>/Bewaar hem bij PDF/.test($('#pff-preview-status').textContent));
  assert.equal(await p.evaluate(()=>$('#pff-maak').disabled),true,'geen PDF zonder de gekozen handtekening');
  // Een leeg formulier: Meer gegevens blijft dicht, de keuze Nee telt niet als ingevuld.
  assert.equal(await p.evaluate(()=>{fm.values={};fm.stam=new Set();fm.meer={};fm.values.alleen='nee';fm.values['begeleider1-handtekening']='nee';fm.step=fmSecties().findIndex(s=>s.fields.some(f=>f.id==='begeleider1-handtekening'));fmRender();return $('#pff-meer').open;}),false,'Meer gegevens dicht');
  // Met een app-profiel kan je handtekening alleen bij jezelf.
  await p.evaluate(()=>Android._cm.records.push({id:'00000000-0000-0000-0000-000000000009',type:'profiel',label:'Mijn app-profiel',revision:1,fields:{persoon:'00000000-0000-0000-0000-000000000002'}}));
  assert.match(await p.evaluate(()=>{try{const r=Android._cm.records.find(x=>x.type==='formulier');Android._cmOwnSignature(r.fields);return 'geen fout';}catch(e){return e.message;}}),/van jou/,'Papa is niet wie ik ben');
  await p.evaluate(()=>Android.masterLock());await p.waitForFunction(()=>!fm.masterSensitive&&!cm.token);
  await p.close();
}
console.log('errors',errors);await b.close();assert.deepEqual(errors,[]);})().catch(e=>{console.error(e);process.exit(1);});
