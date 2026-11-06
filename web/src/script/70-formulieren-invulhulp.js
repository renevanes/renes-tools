/* Alleen expliciet vrijgegeven bronvelden. AI-antwoorden zijn voorstellen; geen automatische wijzigingen. */
const fmh={sources:[],rows:[],snapshot:'',context:'',busy:false,request:'',seq:0,shown:false};
function fmHelpFields(){const s=fmSecties()[fm.step];return s?fmVelden(s):[];}
function fmHelpContext(){const s=fmSecties()[fm.step];return fm.id+'|'+(s?s.fields[0].id:'review');}
function fmHelpSnapshot(){return JSON.stringify([fm.id,fm.step,fm.values]);}
function fmHelpDirty(){return fmh.busy||fmh.sources.length>0||fmh.rows.length>0||!!$('#pff-hulp-tekst').value.trim();}
function fmHelpRole(role){return ({kind:tc('formulier','Kind'),reis:t('Reis'),bestemming:t('Bestemming'),begeleider1:t('Begeleider 1'),begeleider2:t('Begeleider 2'),gever1:t('Toestemminggever 1'),gever2:t('Toestemminggever 2')})[role]||role;}
function fmHelpNote(text){$('#pff-hulp-status').textContent=text;}
function fmHelpCancel(){
  const request=fmh.request,wasBusy=fmh.busy;fmh.request='';fmh.busy=false;
  if(request&&typeof Android.pdfFormAiCancel==='function')Android.pdfFormAiCancel(request);
  fmHelpControls();
  if(wasBusy)fmHelpNote(t('Invulhulp gestopt.'));
}
function fmHelpDiscard(){fmHelpCancel();fmh.rows=[];fmh.snapshot='';$('#pff-hulp-voorstellen').replaceChildren();$('#pff-hulp-overnemen').hidden=true;fmHelpNote('');}
function fmHelpClear(){fmHelpDiscard();fmh.shown=false;fmh.sources=[];fmh.context='';$('#pff-hulp-tekst').value='';$('#pff-hulp-bronnen').replaceChildren();fmHelpNote('');}
function fmHelpRelevant(){
  const fields=fmHelpFields();return fmh.sources.map(s=>{
    const values={};for(const f of fields)for(const b of f.sources||[])if(b.role===s.role&&s.fields[b.key])values[b.key]=s.fields[b.key];
    return {...s,fields:values};
  }).filter(s=>Object.keys(s.fields).length);
}
function fmHelpDrop(source){
  fmHelpDiscard();fmh.sources=fmh.sources.filter(s=>s.id!==source.id&&!(source.type==='persoon'&&s.type==='identiteit'&&s.role===source.role&&s.person===source.record));fmHelpRender();
}
function fmHelpControls(){
  const busy=fm.busy||fmh.busy;
  ['pff-hulp-kies','pff-hulp-vast','pff-hulp-ai','pff-hulp-rol'].forEach(id=>$('#'+id).disabled=busy);
  $('#pff-hulp-stop').hidden=!fmh.busy;
  $('#pff-hulp-overnemen').disabled=busy||!document.querySelector('#pff-hulp-voorstellen input:checked');
  $('#pff-volgende').disabled=busy;$('#pff-vorige').disabled=busy||fm.step===0;$('#pff-keuze').disabled=busy;
  $('#cm-form-pick').disabled=busy;
}
function fmHelpRender(){
  if(!fm.id)return;const context=fmHelpContext();
  if(fmh.context&&context!==fmh.context){fmHelpDiscard();$('#pff-hulp-tekst').value='';fmHelpNote('');}
  fmh.context=context;$('#pff-hulp').hidden=!fmh.shown||!fmHelpCan();
  $('#pff-hulp-tekst').placeholder=t('Bijvoorbeeld: de begeleider is de vader. Datum ondertekening: 12-07-2027.');
  const select=$('#pff-hulp-rol'),old=select.value;select.replaceChildren();
  const roles=[...new Set(fmHelpFields().flatMap(f=>(f.sources||[]).map(b=>b.role)))];
  for(const role of roles){const o=cmEl('option',fmHelpRole(role));o.value=role;select.append(o);}
  if(roles.includes(old))select.value=old;
  $('#pff-hulp-kies').hidden=!roles.length;
  const box=$('#pff-hulp-bronnen');box.replaceChildren();
  for(const source of fmHelpRelevant()){
    const row=cmEl('div',undefined,'row'),label=cmData(cmEl('span',t('{rol}: {naam}',{rol:fmHelpRole(source.role),naam:source.label})));
    const button=cmEl('button',t('Bron verwijderen'),'mini');button.disabled=fmh.busy;button.onclick=()=>fmHelpDrop(source);row.append(label,button);box.append(row);
  }
  fmHelpControls();
}
/* Het paneel staat niet standaard in beeld: je opent het via het menu ⋯ van een stap. Sluiten wist bronnen en toelichting. */
function fmHelpCan(){return fm.view==='stap'&&fmHelpFields().some(f=>(f.sources||[]).length);}
function fmHelpToon(on){
  if(!on){const inside=$('#pff-hulp').contains(document.activeElement);fmHelpClear();fmRender();if(inside)$('#pff-titel').focus();return;}
  if(!fmHelpCan())return;fmh.shown=true;fmRender();const panel=$('#pff-hulp');panel.open=true;panel.querySelector('summary').focus();
}
function fmHelpPick(){
  if(fm.busy||fmh.busy)return;fmHelpDiscard();const role=$('#pff-hulp-rol').value,context=fmHelpContext(),mapping={};
  for(const f of fmHelpFields())for(const b of f.sources||[])if(b.role===role)mapping[b.key]={current:()=>''};
  cmPick('formulieren',role,mapping,fields=>{
    if(context!==fmHelpContext())return;
    fm.masterSensitive=true;const record=cm.picker.record;
    if(record.type==='identiteit'&&!fmh.sources.some(s=>s.role===role&&s.type==='persoon'&&s.record===record.person)){
      fmHelpNote(t('Kies eerst de gekoppelde persoon voor deze rol.'));fmRender();return;
    }
    const previous=fmh.sources.find(s=>s.role===role&&s.type===record.type);
    fmh.sources=fmh.sources.filter(s=>s!==previous&&!(record.type==='persoon'&&s.role===role&&s.type==='identiteit'&&s.person!==record.id));
    const kept=previous&&previous.record===record.id?Object.fromEntries(Object.entries(previous.fields).filter(([key])=>!Object.prototype.hasOwnProperty.call(mapping,key))):{};
    fmh.sources.push({id:'bron-'+(++fmh.seq),record:record.id,person:record.person||'',role,type:record.type,label:record.label,fields:{...kept,...fields}});
    fmHelpNote(t('Brongegevens gekozen. Maak een voorstel en controleer de velden.'));fmRender();
  });
}
function fmHelpReady(){
  if(fm.busy||fmh.busy)return false;
  if(!cmOpen()||!fmHelpRelevant().length){fmHelpNote(t('Kies eerst vaste gegevens voor deze stap en ontgrendel Stamgegevens.'));return false;}
  return true;
}
function fmHelpValueOK(field,value){
  if(typeof value!=='string'||!value.trim()||value.length>200||/[\x00-\x1f]/.test(value))return false;
  if(field.type==='select'&&!field.options.some(o=>o[0]===value))return false;
  if(field.type==='date'&&(!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value+'T12:00:00Z'))))return false;
  if(field.type==='bsn'&&!/^[0-9]{8,9}$/.test(value))return false;
  return !field.pattern||new RegExp('^(?:'+field.pattern+')$').test(value);
}
function fmHelpFixed(){
  if(!fmHelpReady())return;fmHelpDiscard();const rows=[],sources=fmHelpRelevant();
  for(const field of fmHelpFields()){
    const candidates=[];for(const source of sources)for(const b of field.sources||[])if(source.role===b.role&&source.fields[b.key])candidates.push({id:field.id,value:source.fields[b.key],source:source.label});
    if(candidates.length&&new Set(candidates.map(c=>c.value)).size===1)rows.push(candidates[0]);
  }
  fmHelpShow(rows);
}
function fmHelpAi(){
  if(!fmHelpReady())return;fmHelpDiscard();
  if(typeof Android.pdfFormAi!=='function'){fmHelpNote(t('Installeer de nieuwste APK voor lokale invulhulp.'));return;}
  const fields=fmHelpFields().map(f=>({id:f.id,label:f.label,type:f.type||'text',sources:f.sources||[],...(f.type==='select'?{options:f.options.map(o=>o[0])}:{})}));
  const sources=fmHelpRelevant().map(({id,label,type,role,fields})=>({id,label,type,role,fields}));
  fmh.request='formulier-ai-'+(++fmh.seq);fmh.snapshot=fmHelpSnapshot();fmh.busy=true;fmHelpControls();
  fmHelpNote(t('De lokale AI maakt een voorstel. Dit kan even duren.'));
  const result=Android.pdfFormAi(cm.token,JSON.stringify({fields,sources,text:$('#pff-hulp-tekst').value}),fmh.request);
  if(result){fmHelpCancel();fmHelpNote(t(result));}
}
window.onPdfFormAi=function(r){
  if(!r||!fmh.busy||r.request!==fmh.request)return;
  if(r.token!==cm.token||!cmOpen()||fmh.snapshot!==fmHelpSnapshot()){fmHelpDiscard();return;}
  fmh.busy=false;fmh.request='';fmHelpControls();
  if(r.error){fmHelpNote(t(r.error));return;}
  if(r.cancelled){fmHelpNote(t('Invulhulp gestopt.'));return;}
  fmHelpShow(r.fields||[]);
};
function fmHelpShow(rows){
  const fields=fmHelpFields();fmh.rows=[];fmh.snapshot=fmHelpSnapshot();
  const box=$('#pff-hulp-voorstellen');box.replaceChildren();const seen=new Set();
  for(const row of rows){
    const field=fields.find(f=>f.id===row.id);if(!field||seen.has(row.id)||!fmHelpValueOK(field,row.value)||fm.values[row.id]===row.value)continue;seen.add(row.id);fmh.rows.push(row);
    const label=cmEl('label',undefined,'chk'),input=cmEl('input');input.type='checkbox';input.dataset.field=row.id;input.checked=!(fm.values[row.id]||'').trim();input.onchange=fmHelpControls;
    const display=value=>field.type==='date'?fmtDate(value):field.type==='select'?(field.options.find(o=>o[0]===value)||[])[1]||value:value;
    const details=cmEl('span');details.append(cmEl('b',field.label),cmData(cmEl('span',display(row.value))));
    if(fm.values[row.id])details.append(cmData(cmEl('small',t('Huidige invoer: {waarde}',{waarde:display(fm.values[row.id])}))));
    details.append(cmData(cmEl('small',t('Bron: {bron}',{bron:row.sourceType==='text'?t('Je toelichting'):row.source}))));label.append(input,details);box.append(label);
  }
  $('#pff-hulp-overnemen').hidden=!fmh.rows.length;fmHelpControls();
  fmHelpNote(fmh.rows.length?t('Controleer de voorstellen. Bestaande invoer vervang je alleen als je het bijbehorende vakje aanvinkt.'):t('Geen nieuwe passende voorstellen. Kies bronvelden of vul je toelichting aan.'));
}
function fmHelpApply(){
  if(fm.busy||fmh.busy||!cmOpen())return;
  if(fmh.snapshot!==fmHelpSnapshot()){fmHelpDiscard();fmHelpNote(t('De invoer is gewijzigd. Maak een nieuw voorstel.'));return;}
  const chosen=new Set([...document.querySelectorAll('#pff-hulp-voorstellen input:checked')].map(e=>e.dataset.field));
  if(!chosen.size)return;
  for(const row of fmh.rows)if(chosen.has(row.id))fm.values[row.id]=row.value;
  fm.meer={};fmWijzig();fmRender();fmHelpNote(t('Gekozen voorstellen overgenomen. Controleer het formulier voordat je een PDF maakt.'));cmTouch();
}
const fmHelpBaseInit=fmInit;fmInit=function(id){fmHelpClear();fmHelpBaseInit(id);};
const fmHelpBaseWijzig=fmWijzig;fmWijzig=function(){fmHelpDiscard();fmHelpBaseWijzig();};
const fmHelpBaseRender=fmRender;fmRender=function(){fmHelpBaseRender();fmHelpRender();};
const fmHelpBaseActs=fmMenuActs;fmMenuActs=function(){const acts=fmHelpBaseActs().filter(a=>!fmh.busy||a[1]!==fmWissen);if(fmHelpCan())acts.unshift(fmh.shown?[t('Invulhulp met lokale AI sluiten'),()=>fmHelpToon(false),'close']:[t('Invulhulp met lokale AI'),()=>fmHelpToon(true),'edit']);return acts;};
const fmHelpBaseOverzicht=fmOverzicht;fmOverzicht=function(){fmHelpClear();fmHelpBaseOverzicht();};
const fmHelpBaseLocked=window.onMasterLocked;window.onMasterLocked=function(generation){fmHelpClear();fmHelpBaseLocked(generation);};
const fmHelpBaseReset=window.onMasterReset;window.onMasterReset=function(){fmHelpClear();fmHelpBaseReset();};
const fmHelpBaseNavigation=cmNavigation;cmNavigation=function(name){if(current==='formulieren'&&name!=='formulieren')fmHelpDiscard();fmHelpBaseNavigation(name);};
const fmHelpBaseDirectPick=cmFormPick;cmFormPick=function(){if(!fmh.busy)fmHelpBaseDirectPick();};
$('#pff-hulp-tekst').addEventListener('input',()=>{fmHelpDiscard();cmTouch();});
