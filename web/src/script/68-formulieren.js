/* Algemene invuller: definities bepalen stappen, voorwaarden, controles en PDF-posities.
 * Tussentijds opslaan kan altijd, ook met lege verplichte velden: een concept per formulier, versleuteld op de telefoon
 * (FormConcept). Gegevens uit Stamgegevens (fm.stam) gaan niet in het concept. Exporteer alleen zichtbare, actieve velden. */
const fm = {id:'',values:{},step:0,page:0,view:'start',meer:{},previews:{},token:0,previewToken:'',exportToken:'',busy:false,bestand:'',stam:new Set(),opgeslagen:0,bewaard:'',conceptFout:''};
function fmDef(){ return PDF_FORMULIEREN[fm.id](); }
function fmDirty(){return fm.busy || !!fm.id && JSON.stringify(fm.values)!==fm.initial;}
function fmWijzig(){fm.previewToken='';fm.bestand='';$('#pff-resultaat').hidden=true;$('#pff-melding').textContent='';$('#pff-maak').classList.remove('tonal');fmConceptStand();}
function fmActief(when){ return !when || Object.entries(when).every(([key,value]) => fm.values[key] === value); }
function fmSecties(){ return fmDef().sections.filter(s => fmActief(s.when)); }
function fmVelden(s){ return s.fields.filter(f => fmActief(f.when)); }
function fmInit(id){
  fm.id=id;fm.values={};fm.step=0;fm.page=0;fm.view='start';fm.meer={};fm.previews={};fm.previewToken='';fm.bestand='';
  fm.stam=new Set();fm.opgeslagen=0;fm.conceptFout='';
  for (const s of fmDef().sections) for (const f of s.fields) fm.values[f.id]=f.value || '';
  fm.initial=JSON.stringify(fm.values);fm.naamStam=false;$('#pff-naam').value=fmDef().title;fm.bewaard=fmConceptSnap();
  document.querySelectorAll('#s-formulieren [data-ui-edited]').forEach(e=>delete e.dataset.uiEdited);
  $('#pff-resultaat').hidden=true;$('#pff-melding').textContent='';$('#pff-alles').open=false;$('#pff-samenvatting').replaceChildren();
}
function enterFormulieren(){
  if (!fm.id) { fmInit(Object.keys(PDF_FORMULIEREN)[0]); fmConceptLaad(); }
  const select=$('#pff-keuze');select.replaceChildren();
  Object.entries(PDF_FORMULIEREN).forEach(([id,make]) => { const o=document.createElement('option');o.value=id;o.textContent=make().title;select.appendChild(o); });
  select.value=fm.id;$('#pff-keuze-rij').hidden=select.options.length<2;fmRender();
}
function fmKies(id){
  $('#pff-keuze').value=fm.id;
  if (fm.busy || id===fm.id || !PDF_FORMULIEREN[id]) return;
  const choose=()=>{fmInit(id);fmConceptLaad();enterFormulieren();};
  fmConceptVanzelf();
  if (fmDirty()&&fmNietBewaard()) askConfirm('Ander formulier kiezen?','Wijzigingen die niet zijn opgeslagen, gaan verloren.','Formulier kiezen',choose); else choose();
}
function fmWissen(){
  if(fm.busy)return;
  askConfirm('Formulier leegmaken?','Alle invoer in dit formulier wordt gewist, ook het opgeslagen concept. Een al gemaakte PDF blijft onder PDF → Gemaakt staan.','Leegmaken',()=>{fmConceptWeg();fmInit(fm.id);fmRender();});
}
function fmBron(){ Android.openUrl(fmDef().source); }
/* Twee weergaven op één scherm: de start (hoofdknop en rijen) en de stappen. Zo blijven vergrendelen,
 * wissen en afschermen gekoppeld aan het ene scherm "formulieren". */
function fmStart(){if(!fm.id)return;fm.view='stap';fmRender();$('#pff-titel').focus();}
function fmOverzicht(){fmConceptVanzelf();fm.view='start';fmRender();const rows=!$('#pff-start-rijen').hidden;(rows?$('#pff-zelf'):$('#pff-start-knop')).focus();}
function fmTerugNaarStart(){if(fm.view!=='stap')return false;if(!fm.busy)fmOverzicht();return true;}
function fmMenuActs(){
  const acts=[[t('Officiële bron openen'),fmBron,'external']];
  if(!fm.busy&&(fmDirty()||fm.bestand||fm.opgeslagen))acts.push([t('Formulier leegmaken'),fmWissen,'trash']);
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
  if(!fm.bestand&&dirty&&fm.opgeslagen&&!fmNietBewaard())$('#pff-zelf-stand').textContent=t('{stand} · concept opgeslagen {wanneer}',{stand:$('#pff-zelf-stand').textContent,wanneer:fmtDT(fm.opgeslagen,{day:'numeric',month:'short',hour:'2-digit',minute:'2-digit'})});
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
  if(fm.fout&&fm.fout.id===f.id){input.setAttribute('aria-invalid','true');input.setAttribute('aria-describedby','pff-fout');}
  input.addEventListener(f.type==='select'?'change':'input',()=>{
    fm.values[f.id]=input.value;fm.stam.delete(f.id);fmWijzig();
    if(fm.fout&&fm.fout.id===f.id){fm.fout=null;input.removeAttribute('aria-invalid');input.removeAttribute('aria-describedby');const e=$('#pff-fout');if(e)e.remove();}
    if(f.type==='select'){fmRender();const el=$('#pff-v-'+f.id);if(el)el.focus();}
  });
  if(fm.fout&&fm.fout.id===f.id){const e=document.createElement('p');e.className='note warn pff-fout';e.id='pff-fout';e.textContent=fm.fout.text;return [label,input,e];}
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
    const note=document.createElement('p');note.className='note';note.textContent=t('Velden met * zijn nodig voor de PDF. Tussentijds opslaan kan altijd, ook als ze nog leeg zijn.');box.appendChild(note);
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
  fmOntbreektRender(review);$('#pff-opslaan').disabled=fm.busy;fmConceptStand();
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
/* Eén veld nakijken: '' = goed, anders de reden. Leeg mag (strict=false: tussentijds), behalve bij de PDF. */
function fmVeldFout(f,strict){
  const value=(fm.values[f.id]||'').trim();
  if(!value)return strict&&f.required?t('Dit veld is nodig voor de PDF.'):'';
  if(f.type==='bsn'&&!/^[0-9]{8,9}$/.test(value))return t('Een BSN heeft 8 of 9 cijfers.');
  if(f.pattern&&!new RegExp('^(?:'+f.pattern+')$').test(value))return t('Deze invoer klopt niet.');
  return '';
}
/* Naar het veld met een fout: de stap openen, het veld tonen met de reden eronder, en de melding bovenaan. */
function fmToonFout(index,f,text){
  const sections=fmSecties();
  fm.step=index;if(f.more)fm.meer[fmMeerSleutel(sections[index])]=true;fm.fout={id:f.id,text};fm.view='stap';fmRender();
  const input=$('#pff-v-'+f.id);if(input){input.focus();input.scrollIntoView({block:'center'});}
  $('#pff-melding').textContent=t('{veld}: {reden}',{veld:t(f.label),reden:text});
}
/* Lege verplichte velden: [{index, f}] (alle stappen, of alleen tot en met de huidige). */
function fmOntbreekt(totStap){
  const out=[],sections=fmSecties();
  sections.forEach((s,index)=>{if(totStap!=null&&index>totStap)return;for(const f of fmVelden(s))if(f.required&&!(fm.values[f.id]||'').trim())out.push({index,f});});
  return out;
}
function fmGeldig(all,strict=true){
  const sections=fmSecties();fm.fout=null;
  for(let index=0;index<sections.length;index++){
    if(!all&&index!==fm.step)continue;
    for(const f of fmVelden(sections[index])){
      const reden=fmVeldFout(f,strict);
      if(reden){fmToonFout(index,f,reden);return false;}
    }
  }
  for(const rule of fmDef().rules||[]){
    const a=fm.values[rule.from],b=fm.values[rule.to];
    const index=sections.findIndex(s=>s.fields.some(f=>f.id===rule.to));
    if(a&&b&&a>b&&index>=0){const f=sections[index].fields.find(x=>x.id===rule.to);fmToonFout(index,f,rule.message);$('#pff-melding').textContent=rule.message;return false;}
  }
  return true;
}
/* Bij Controleren: wat nog leeg is, met een knop per veld. */
function fmOntbreektRender(review){
  const box=$('#pff-ontbreekt');if(!box)return;box.replaceChildren();
  const missing=review?fmOntbreekt():[];box.hidden=!missing.length;if(!missing.length)return;
  const p=document.createElement('p');p.className='note warn';
  p.textContent=tn(missing.length,'Nog één veld leeg dat nodig is voor de PDF:','Nog {n} velden leeg die nodig zijn voor de PDF:');box.append(p);
  for(const {index,f} of missing.slice(0,12)){
    const b=document.createElement('button');b.className='mini';b.type='button';b.textContent=t(f.label);b.setAttribute('translate','no');
    b.onclick=()=>{if(!fm.busy)fmToonFout(index,f,t('Dit veld is nodig voor de PDF.'));};box.append(b);
  }
}
/* Volgende stap: alleen echte fouten houden tegen; lege verplichte velden mogen (die zijn pas nodig voor de PDF). */
function fmVerder(){
  if(fm.busy||!fmGeldig(false,false))return;
  const leeg=fmOntbreekt(fm.step).filter(x=>x.index===fm.step).length;
  fm.step++;fmRender();$('#pff-titel').focus();fmConceptVanzelf();
  if(leeg)$('#pff-melding').textContent=tn(leeg,'Eén verplicht veld in de vorige stap is nog leeg; dat kan later.','{n} verplichte velden in de vorige stap zijn nog leeg; dat kan later.');
}
function fmTerug(){if(fm.busy||!fm.step)return;fm.fout=null;fm.step--;fmRender();$('#pff-titel').focus();fmConceptVanzelf();}

/* ---------- tussentijds opslaan (concept) ---------- */
/* Gaat dit veld nooit in een concept? BSN en documentnummers blijven, zoals altijd, alleen in het geheugen. */
function fmConceptGeheim(f){return f.type==='bsn'||/(^|-)(bsn|document)$/.test(f.id);}
function fmConceptData(){
  const values={},geheim=new Set(fmDef().sections.flatMap(s=>s.fields.filter(fmConceptGeheim).map(f=>f.id)));
  for(const [k,v] of Object.entries(fm.values))if(!fm.stam.has(k)&&!geheim.has(k))values[k]=v;
  return {values,step:fm.step,naam:fm.naamStam?'':$('#pff-naam').value.trim(),stam:fm.stam.size>0};
}
/* Wat telt voor "opgeslagen": invoer, stap en naam van de PDF. */
function fmConceptSnap(){return JSON.stringify([fm.values,fm.step,fm.naamStam?'':$('#pff-naam').value.trim()]);}
/* Iets veranderd sinds het laatste opslaan? */
function fmNietBewaard(){return !!fm.id&&fmConceptSnap()!==fm.bewaard;}
function fmConceptStand(){
  const el=$('#pff-concept');if(!el||!fm.id)return;
  if(fm.conceptFout){el.textContent=fm.conceptFout;el.classList.add('warn');return;}
  el.classList.remove('warn');
  const leeg=fmOntbreekt().length;
  if(fm.opgeslagen&&!fmNietBewaard())el.textContent=leeg?tn(leeg,'Opgeslagen om {tijd} · nog één verplicht veld leeg','Opgeslagen om {tijd} · nog {n} verplichte velden leeg',{tijd:fmtTime(fm.opgeslagen)}):t('Opgeslagen om {tijd}',{tijd:fmtTime(fm.opgeslagen)});
  else el.textContent=fmDirty()?t('Niet opgeslagen wijzigingen'):'';
}
function fmConceptOpslaan(stil){
  if(!fm.id||fm.busy)return false;
  if(typeof Android.pdfFormConceptSave!=='function'){if(!stil){fm.conceptFout=t('Installeer de nieuwste APK om tussentijds op te slaan.');fmConceptStand();}return false;}
  const data=fmConceptData();
  let r;try{r=JSON.parse(Android.pdfFormConceptSave(fm.id,JSON.stringify(data))||'{}');}catch(e){r={error:t('Opslaan is niet gelukt. Probeer het nog eens.')};}
  if(r.error||!r.t){fm.conceptFout=r.error||t('Opslaan is niet gelukt. Probeer het nog eens.');fmConceptStand();if(!stil)toast(fm.conceptFout);return false;}
  fm.conceptFout='';fm.opgeslagen=r.t;fm.bewaard=fmConceptSnap();fmConceptStand();
  if(!stil){
    const leeg=fmOntbreekt().length;
    toast(data.stam?t('Opgeslagen. Gegevens uit Stamgegevens bewaart het concept niet; die haal je bij verder gaan opnieuw op.')
      :leeg?tn(leeg,'Opgeslagen. Nog één verplicht veld leeg; dat kan later.','Opgeslagen. Nog {n} verplichte velden leeg; dat kan later.'):t('Opgeslagen.'));
  }
  return true;
}
/* Vanzelf opslaan bij een andere stap en bij weggaan, als er iets ingevuld is en er iets veranderde. */
function fmConceptVanzelf(){if(fm.id&&!fm.busy&&fmNietBewaard()&&(fmDirty()||fm.opgeslagen))return fmConceptOpslaan(true);return !fmNietBewaard()&&!!fm.opgeslagen;}
function fmConceptWeg(){if(typeof Android.pdfFormConceptDelete==='function')Android.pdfFormConceptDelete(fm.id);fm.opgeslagen=0;fm.conceptFout='';}
/* Het opgeslagen concept van dit formulier inlezen (na fmInit). Alleen bekende velden, als tekst. */
function fmConceptLaad(){
  if(!fm.id||typeof Android.pdfFormConceptLoad!=='function')return;
  let r;try{r=JSON.parse(Android.pdfFormConceptLoad(fm.id)||'{}');}catch(e){r={};}
  if(r.error){fm.conceptFout=r.error;return;}
  if(!r.values||typeof r.values!=='object')return;
  const known=new Set(fmDef().sections.flatMap(s=>s.fields.map(f=>f.id)));
  for(const [k,v] of Object.entries(r.values))if(known.has(k)&&typeof v==='string')fm.values[k]=v;
  if(typeof r.naam==='string'&&r.naam)$('#pff-naam').value=r.naam;
  fm.step=Math.max(0,Math.min(fmSecties().length,Number(r.step)||0));fm.opgeslagen=Number(r.t)||0;fm.bewaard=fmConceptSnap();
}
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
    if(target.signature){if(fm.values[f.id]==='ja')fmTekenHandtekening(ctx,target);continue;}
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
/* De handtekening die je zelf bewaarde (PDF → Bewerken en ondertekenen), niet vervormd, onderaan op de lijn van het vak. */
function fmHandtekening(){
  let r;try{r=JSON.parse(Android.pdfSignatureLoad());}catch(e){r={error:'Handtekening laden lukt niet'};}
  if(r.error)throw new Error(t(r.error));
  if(!r.signature||!pdfSignatureValid(r.signature))throw new Error(t('Er is nog geen handtekening bewaard. Bewaar hem bij PDF → Bewerken en ondertekenen, of kies bij Je bewaarde handtekening plaatsen Nee.'));
  return r.signature;
}
function fmTekenHandtekening(ctx,target){
  const s=fmHandtekening(),w=Math.min(target.w,target.h/s.ratio),h=w*s.ratio,x=target.x,y=target.y+target.h-h;
  ctx.save();ctx.strokeStyle=s.color;ctx.lineWidth=Math.max(.6,w*.008);ctx.lineCap=ctx.lineJoin='round';
  for(const path of s.paths){ctx.beginPath();path.forEach((p,i)=>{if(i)ctx.lineTo(x+p[0]*w,y+p[1]*h);else ctx.moveTo(x+p[0]*w,y+p[1]*h);});ctx.stroke();}
  ctx.restore();
}
function fmMaak(){
  if(fm.busy)return;
  const leeg=fmOntbreekt();
  if(leeg.length){
    const ok=fmConceptVanzelf();fmToonFout(leeg[0].index,leeg[0].f,t('Dit veld is nodig voor de PDF.'));
    $('#pff-melding').textContent=ok?tn(leeg.length,'Nog één verplicht veld leeg: {veld}. Je invoer is opgeslagen.','Nog {n} verplichte velden leeg, eerst: {veld}. Je invoer is opgeslagen.',{veld:t(leeg[0].f.label)})
      :tn(leeg.length,'Nog één verplicht veld leeg: {veld}.','Nog {n} verplichte velden leeg, eerst: {veld}.',{veld:t(leeg[0].f.label)});
    return;
  }
  if(!fmGeldig(true))return;
  try{
    const layers=fmDef().pages.map((_,page)=>({page,png:fmTeken(document.createElement('canvas'),page).toDataURL('image/png')}));
    const json=JSON.stringify(layers);if(json.length>12_000_000)throw new Error(t('Het ingevulde formulier is te groot om te maken.'));
    fm.exportToken=String(++fm.token);fm.busy=true;$('#pff-melding').textContent=t('Ingevulde PDF maken…');fmRender();
    Android.pdfFormMake(fm.id,json,$('#pff-naam').value.trim()||fmDef().title,fm.exportToken);
  }catch(e){fm.busy=false;$('#pff-melding').textContent=e.message;fmRender();}
}
window.onPdfFormMade=function(r){
  if(!r||r.request!==fm.exportToken||r.id!==fm.id||!fm.busy)return;
  fm.busy=false;if(!r.error&&r.name){fm.bestand=r.name;fmConceptVanzelf();}fmRender();
  if(r.error){$('#pff-melding').textContent=t(r.error);return;}
  if(!r.name){$('#pff-melding').textContent=t('De PDF kan niet worden gemaakt. Probeer opnieuw.');return;}
  $('#pff-bestand').textContent=r.name;$('#pff-klaar').textContent=fmDef().instruction;
  $('#pff-resultaat').hidden=false;$('#pff-melding').textContent=t('De PDF is gemaakt. Bewaar hem en print hem uit om te ondertekenen.');
  if(current==='formulieren')$('#pff-resultaat').scrollIntoView({block:'start'});
};
// App uit beeld (Android kan hem daarna stoppen): wat je invulde vanzelf opslaan.
document.addEventListener('visibilitychange',()=>{if(document.hidden&&current==='formulieren')fmConceptVanzelf();});
// De naam van de PDF telt mee voor "opgeslagen"; zelf aanpassen maakt hem jouw invoer (niet meer uit Stamgegevens).
$('#pff-naam').addEventListener('input',()=>{fm.naamStam=false;fmConceptStand();});
