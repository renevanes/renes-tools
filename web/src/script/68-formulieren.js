/* Algemene invuller: definities bepalen stappen, voorwaarden, controles en PDF-posities.
 * Concepten blijven uitsluitend in het geheugen. Exporteer alleen zichtbare, actieve velden. */
const fm = {id:'',values:{},step:0,page:0,view:'start',meer:{},previews:{},token:0,previewToken:'',exportToken:'',busy:false,bestand:''};
function fmDef(){ return PDF_FORMULIEREN[fm.id](); }
function fmDirty(){return fm.busy || !!fm.id && JSON.stringify(fm.values)!==fm.initial;}
function fmWijzig(){fm.previewToken='';fm.bestand='';$('#pff-resultaat').hidden=true;$('#pff-melding').textContent='';$('#pff-maak').classList.remove('tonal');}
function fmActief(when){ return !when || Object.entries(when).every(([key,value]) => fm.values[key] === value); }
function fmSecties(){ return fmDef().sections.filter(s => fmActief(s.when)); }
function fmVelden(s){ return s.fields.filter(f => fmActief(f.when)); }
function fmInit(id){
  fm.id=id;fm.values={};fm.step=0;fm.page=0;fm.view='start';fm.meer={};fm.previews={};fm.previewToken='';fm.bestand='';
  for (const s of fmDef().sections) for (const f of s.fields) fm.values[f.id]=f.value || '';
  fm.initial=JSON.stringify(fm.values);
  $('#pff-naam').value=fmDef().title;
  document.querySelectorAll('#s-formulieren [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
  $('#pff-resultaat').hidden=true;$('#pff-melding').textContent='';$('#pff-alles').open=false;$('#pff-samenvatting').replaceChildren();
}
function enterFormulieren(){
  if (!fm.id) fmInit(Object.keys(PDF_FORMULIEREN)[0]);
  const select=$('#pff-keuze');select.replaceChildren();
  Object.entries(PDF_FORMULIEREN).forEach(([id,make]) => { const o=document.createElement('option');o.value=id;o.textContent=make().title;select.appendChild(o); });
  select.value=fm.id;$('#pff-keuze-rij').hidden=select.options.length<2;fmRender();
}
function fmKies(id){
  $('#pff-keuze').value=fm.id;
  if (fm.busy || id===fm.id || !PDF_FORMULIEREN[id]) return;
  const choose=()=>{fmInit(id);enterFormulieren();};
  if (fmDirty()) askConfirm('Ander formulier kiezen?','Je huidige invoer wordt gewist.','Formulier kiezen',choose); else choose();
}
function fmWissen(){
  if(fm.busy)return;
  askConfirm('Formulier leegmaken?','Alle invoer in dit formulier wordt gewist. Een al gemaakte PDF blijft onder PDF → Gemaakt staan.','Leegmaken',()=>{fmInit(fm.id);fmRender();});
}
function fmBron(){ Android.openUrl(fmDef().source); }
/* Twee weergaven op één scherm: de start (hoofdknop en rijen) en de stappen. Zo blijven vergrendelen,
 * wissen en afschermen gekoppeld aan het ene scherm "formulieren". */
function fmStart(){if(!fm.id)return;fm.view='stap';fmRender();$('#pff-titel').focus();}
function fmOverzicht(){fm.view='start';fmRender();const rows=!$('#pff-start-rijen').hidden;(rows?$('#pff-zelf'):$('#pff-start-knop')).focus();}
function fmTerugNaarStart(){if(fm.view!=='stap')return false;if(!fm.busy)fmOverzicht();return true;}
function fmMenuActs(){
  const acts=[[t('Officiële bron openen'),fmBron,'external']];
  if(!fm.busy&&(fmDirty()||fm.bestand))acts.push([t('Formulier leegmaken'),fmWissen,'trash']);
  return acts;
}
function fmMenu(){if(fm.id)openSheet(fmDef().title,'',fmMenuActs());}
function fmStartRender(def,sections){
  const dirty=fmDirty();
  $('#pff-form-titel').textContent=def.title;
  $('#pff-uitleg').textContent=def.intro||def.description;
  $('#pff-omschrijving').textContent=def.description;
  $('#pff-zelf-stand').textContent=fm.bestand?t('PDF gemaakt: {naam}',{naam:fm.bestand})
    :!dirty?t('Alle velden, stap voor stap')
    :fm.step>=sections.length?t('Klaar om de PDF te maken')
    :t('Verder bij stap {n} van {totaal}: {stap}',{n:fm.step+1,totaal:sections.length+1,stap:sections[fm.step].title});
  $('#pff-start-knop').textContent=dirty?t('Verder met invullen'):t('Formulier invullen');
  $('#pff-keuze').disabled=fm.busy;
}
function fmInvoer(f){
  const label=document.createElement('label');label.className='f';label.htmlFor='pff-v-'+f.id;label.textContent=f.label+(f.required?' *':'');
  const input=document.createElement(f.type==='select'?'select':'input');input.id=label.htmlFor;input.className='search';input.name=f.id;
  input.setAttribute('translate','no');input.required=!!f.required;
  if(f.type==='select')for(const [value,text] of f.options){const o=document.createElement('option');o.value=value;o.textContent=text;input.appendChild(o);}
  else{
    input.type=['date','tel'].includes(f.type)?f.type:'text';input.autocomplete='off';input.maxLength=200;
    if(f.type==='bsn'){input.inputMode='numeric';input.pattern='[0-9]{8,9}';input.maxLength=9;}
    if(f.pattern)input.pattern=f.pattern;
  }
  input.value=fm.values[f.id]||'';
  input.addEventListener(f.type==='select'?'change':'input',()=>{
    fm.values[f.id]=input.value;fmWijzig();
    if(f.type==='select'){fmRender();const el=$('#pff-v-'+f.id);if(el)el.focus();}
  });
  return [label,input];
}
function fmMeerSleutel(s){return s.fields[0].id;}
function fmRender(){
  const def=fmDef(),sections=fmSecties();fm.step=Math.min(fm.step,sections.length);
  const review=fm.step===sections.length,start=fm.view!=='stap';
  $('#pff-start').hidden=!start;$('#pff-werk').hidden=start;
  fmStartRender(def,sections);
  $('#pff-stap').textContent=t('Stap {n} van {totaal}',{n:fm.step+1,totaal:sections.length+1});
  $('#pff-titel').textContent=review?t('Controleren en PDF maken'):sections[fm.step].title;
  const box=$('#pff-velden');box.replaceChildren();box.hidden=review;
  if(!review){
    const note=document.createElement('p');note.className='note';note.textContent=t('Velden met * zijn verplicht.');box.appendChild(note);
    const fields=fmVelden(sections[fm.step]),meer=fields.filter(f=>f.more);
    fields.filter(f=>!f.more).forEach(f=>box.append(...fmInvoer(f)));
    if(meer.length){
      // Optionele velden ingeklapt; open als er al iets in staat of als je ze zelf opende.
      const key=fmMeerSleutel(sections[fm.step]),details=document.createElement('details'),summary=document.createElement('summary');
      details.className='pff-meer';details.id='pff-meer';summary.dataset.icon='chevron-down';summary.textContent=t('Meer gegevens (optioneel)');
      details.open=key in fm.meer?fm.meer[key]:meer.some(f=>fm.values[f.id]);
      details.addEventListener('toggle',()=>{fm.meer[key]=details.open;});
      details.append(summary);meer.forEach(f=>details.append(...fmInvoer(f)));box.append(details);
    }
  }
  $('#pff-controle').hidden=!review;$('#pff-alles').hidden=!review;
  $('#pff-volgende').hidden=review;$('#pff-maak').hidden=!review;
  $('#pff-vorige').disabled=fm.busy||fm.step===0;$('#pff-volgende').disabled=fm.busy;
  $('#pff-maak').disabled=fm.busy;$('#pff-maak').classList.toggle('tonal',!!fm.bestand);$('#pff-naam').disabled=fm.busy;
  if(review){fmSamenvatting();if(!start)fmPreview();}
}
function fmSamenvatting(){
  $('#pff-instructie').textContent=fmDef().instruction;
  const box=$('#pff-samenvatting');box.replaceChildren();
  fmSecties().forEach((s,index)=>{
    const button=document.createElement('button');button.className='mini';button.textContent=t('{stap} aanpassen',{stap:s.title});button.onclick=()=>{if(!fm.busy){fm.step=index;fmRender();$('#pff-titel').focus();}};
    const dl=document.createElement('dl');
    fmVelden(s).forEach(f=>{
      const value=fm.values[f.id];if(!value)return;
      const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=f.label;dd.setAttribute('translate','no');
      dd.textContent=f.type==='date'?fmtDate(value):f.type==='select'?(f.options.find(o=>o[0]===value)||[])[1]:value;
      dl.append(dt,dd);
    });box.append(button,dl);
  });
}
function fmGeldig(all){
  const sections=fmSecties();
  for(let index=0;index<sections.length;index++){
    if(!all&&index!==fm.step)continue;
    for(const f of fmVelden(sections[index])){
      const value=(fm.values[f.id]||'').trim();
      const invalid=(f.required&&!value)||(value&&f.type==='bsn'&&!/^[0-9]{8,9}$/.test(value))||(value&&f.pattern&&!new RegExp('^(?:'+f.pattern+')$').test(value));
      if(invalid){fm.step=index;if(f.more)fm.meer[fmMeerSleutel(sections[index])]=true;fmRender();const input=$('#pff-v-'+f.id);input.focus();input.reportValidity();$('#pff-melding').textContent=t('Controleer het veld: {veld}',{veld:f.label});return false;}
    }
  }
  for(const rule of fmDef().rules||[]){
    const a=fm.values[rule.from],b=fm.values[rule.to];
    const index=sections.findIndex(s=>s.fields.some(f=>f.id===rule.to));
    if(a&&b&&a>b&&index>=0){fm.step=index;const s=sections[index];if(s.fields.some(f=>f.id===rule.to&&f.more))fm.meer[fmMeerSleutel(s)]=true;fmRender();$('#pff-v-'+rule.to).focus();$('#pff-melding').textContent=rule.message;return false;}
  }
  return true;
}
function fmVerder(){if(fm.busy||!fmGeldig(false))return;fm.step++;fmRender();$('#pff-titel').focus();}
function fmTerug(){if(fm.busy||!fm.step)return;fm.step--;fmRender();$('#pff-titel').focus();}
function fmPagina(delta){if(fm.busy)return;fm.page=Math.max(0,Math.min(fmDef().pages.length-1,fm.page+delta));fmPreview();}
function fmPreview(){
  const def=fmDef();
  $('#pff-pagina-nummer').textContent=t('Pagina {n} van {totaal}',{n:fm.page+1,totaal:def.pages.length});
  $('#pff-pagina-vorige').disabled=fm.busy||fm.page===0;$('#pff-pagina-volgende').disabled=fm.busy||fm.page===def.pages.length-1;
  $('#pff-papier').hidden=true;
  const cached=fm.previews[fm.page];
  if(cached){fmToonPreview(cached);return;}
  $('#pff-preview-status').textContent=t('Voorbeeld laden…');
  $('#pff-maak').disabled=true;
  fm.previewToken=String(++fm.token);
  if(typeof Android.pdfFormPreview!=='function'){$('#pff-preview-status').textContent=t('Installeer de nieuwste APK om formulieren in te vullen.');return;}
  Android.pdfFormPreview(fm.id,fm.page,fm.previewToken);
}
window.onPdfFormPreview=function(r){
  if(!r||r.request!==fm.previewToken||r.id!==fm.id||r.page!==fm.page)return;
  if(r.error||!/^data:image\/jpeg;base64,/.test(r.preview||'')){
    $('#pff-preview-status').textContent=r.error?t(r.error):t('Het voorbeeld kan niet worden geladen. Ga een stap terug en probeer opnieuw.');$('#pff-maak').disabled=true;return;
  }
  const size=fmDef().pages[fm.page];
  if(Math.abs(r.width/size.width-1)>.002||Math.abs(r.height/size.height-1)>.002){$('#pff-preview-status').textContent=t('Het formulier heeft een ander formaat. Installeer de nieuwste APK.');return;}
  fm.previews[r.page]=r.preview;fmToonPreview(r.preview);
};
function fmToonPreview(src){
  try{
    fmTeken($('#pff-laag'),fm.page);
    $('#pff-voorbeeld').src=src;$('#pff-papier').hidden=false;$('#pff-preview-status').textContent='';$('#pff-maak').disabled=fm.busy;
  }catch(e){$('#pff-preview-status').textContent=e.message;$('#pff-maak').disabled=true;}
}
function fmPdfWaarde(f){
  let value=(fm.values[f.id]||'').trim();
  // De officiële PDF vraagt dag-maand-jaar, onafhankelijk van de weergave-instellingen in Tools.
  if(f.type==='date'&&value)value=value.split('-').reverse().join('-');
  if(f.compact)value=value.replace(/\s/g,'').toUpperCase();
  const parts=(f.pdf||[]).filter(target=>target.part);
  if(parts.length&&value.length>Math.max(...parts.map(target=>target.part[1])))throw new Error(t('De tekst bij {veld} past niet in de PDF. Kort de invoer in.',{veld:f.label}));
  return value;
}
function fmTeken(canvas,page){
  const size=fmDef().pages[page],scale=Math.min(2.5,4096/size.width,4096/size.height,Math.sqrt(5_800_000/(size.width*size.height)));
  canvas.width=Math.round(size.width*scale);canvas.height=Math.round(size.height*scale);
  const ctx=canvas.getContext('2d');ctx.scale(canvas.width/size.width,canvas.height/size.height);ctx.fillStyle='#000000';ctx.textBaseline='middle';
  for(const s of fmSecties())for(const f of fmVelden(s))for(const target of f.pdf||[]){
    if(target.page!==page)continue;
    let value=fmPdfWaarde(f);if(!value)continue;
    if(target.choice!==undefined)value=fm.values[f.id]===target.choice?'X':'';
    if(target.part)value=value.slice(target.part[0],target.part[1]);if(!value)continue;
    let font=Math.min(target.font||9,target.h-1);ctx.font=font+'px sans-serif';
    while(ctx.measureText(value).width>target.w-2&&font>6){font=Math.max(6,font-.25);ctx.font=font+'px sans-serif';}
    if(ctx.measureText(value).width>target.w-2)throw new Error(t('De tekst bij {veld} past niet in de PDF. Kort de invoer in.',{veld:f.label}));
    ctx.fillText(value,target.x+1,target.y+target.h/2);
  }
  return canvas;
}
function fmMaak(){
  if(fm.busy||!fmGeldig(true))return;
  try{
    const layers=fmDef().pages.map((_,page)=>({page,png:fmTeken(document.createElement('canvas'),page).toDataURL('image/png')}));
    const json=JSON.stringify(layers);if(json.length>12_000_000)throw new Error(t('Het ingevulde formulier is te groot om te maken.'));
    fm.exportToken=String(++fm.token);fm.busy=true;$('#pff-melding').textContent=t('Ingevulde PDF maken…');fmRender();
    Android.pdfFormMake(fm.id,json,$('#pff-naam').value.trim()||fmDef().title,fm.exportToken);
  }catch(e){fm.busy=false;$('#pff-melding').textContent=e.message;fmRender();}
}
window.onPdfFormMade=function(r){
  if(!r||r.request!==fm.exportToken||r.id!==fm.id||!fm.busy)return;
  fm.busy=false;if(!r.error&&r.name)fm.bestand=r.name;fmRender();
  if(r.error){$('#pff-melding').textContent=t(r.error);return;}
  if(!r.name){$('#pff-melding').textContent=t('De PDF kan niet worden gemaakt. Probeer opnieuw.');return;}
  $('#pff-bestand').textContent=r.name;$('#pff-klaar').textContent=fmDef().instruction;
  $('#pff-resultaat').hidden=false;$('#pff-melding').textContent=t('De PDF is gemaakt. Bewaar hem en print hem uit om te ondertekenen.');
  if(current==='formulieren')$('#pff-resultaat').scrollIntoView({block:'start'});
};
