/* Documentenkluis als verkenner: mappen waar je in gaat, een pad bovenaan, de documentenlijst en de documentkaart.
   Een map is de categorie van een document in de versleutelde index (Kluis.java); een map in een map schrijf je met
   een schuine streep (Wonen/Hypotheek). Waar je bent (klMap) staat alleen in het geheugen en is weg zodra de kluis op
   slot gaat. Namen van mappen en documenten zijn gegevens: altijd esc()/textContent en translate="no". */
let klEditId=null, klMap='all'; // 'all' (bovenste niveau) | 'fav' | 'exp' | 'map:<pad>' | 'fac' | 'fac:<leverancier>'
/** Een mappad netjes: delen zonder spaties eromheen, geen lege delen. */
function klPad(v){ return String(v||'').split('/').map(x=>x.trim()).filter(Boolean).join('/'); }
function klMapNamen(docs){ return [...new Set(docs.map(d=>klPad(d.category)).filter(Boolean))].sort((a,b)=>a.localeCompare(b,'nl',{sensitivity:'base'})); }
/** Zit pad in map (de map zelf of dieper)? */
function klOnder(pad,map){ return pad===map||pad.startsWith(map+'/'); }
/** Hoort een document bij deze plek? Met diep ook wat in mappen eronder staat (zoeken). */
function klInMap(d,map,diep){
  const pad=klPad(d.category);
  if(map==='all') return diep||!pad;
  if(map==='fav') return !!d.favorite;
  if(map==='exp') return klVerloopt(d);
  if(map.startsWith('map:')) return diep?klOnder(pad,map.slice(4)):pad===map.slice(4);
  return false;
}
function klKies(map){ klMap=map; klFacLaad(); klRender(); const z=$('#kl-pad'); if(z&&!z.hidden){ z.scrollIntoView({block:'nearest'}); const h=z.querySelector('[aria-current]'); if(h) h.focus({preventScroll:true}); } }
/** Alles van de verkenner uit de pagina en het geheugen: bij op slot gaan en bij weggaan van het scherm. */
function klWis(){ klMap='all'; klFac=null; klTeken.fac=[]; klNieuwIn=null; const p=$('#kl-pad'); if(p){ p.replaceChildren(); delete p.dataset.map; delete p.dataset.diep; p.hidden=true; } const m=$('#kl-mappen'); if(m) m.replaceChildren(); const c=$('#kl-count'); if(c) c.textContent=''; const h=$('#kl-maphernoem'); if(h) h.hidden=true; const f=$('#kl-fachint'); if(f) f.hidden=true; }
/** Eén niveau omhoog; false als je al bovenaan staat. */
function klOmhoog(){
  if(klMap==='all') return false;
  const i=klMap.lastIndexOf('/');
  klKies(klMap.startsWith('map:')&&i>0?klMap.slice(0,i):klMap.startsWith('fac:')?'fac':'all'); return true;
}
/* Facturen uit het facturendossier, als map in de verkenner (alleen wat de app zelf bewaart; beheren doe je bij Facturen).
   Niet als Facturen op slot staat (Extra ontgrendelen) en niet in de kluis als losse app. */
let klFac=null; // het dossier zoals het bij binnenkomen of navigeren was (niet bij elke toets opnieuw ophalen); null = nog niet gelezen
function klFacMag(){ return !(typeof SOLO!=='undefined'&&SOLO)&&typeof Android.facturenDossier==='function'&&!(typeof isPlay==='function'&&isPlay())&&!(typeof tsVerborgen==='function'&&tsVerborgen('facturen')); } // niet in een losse app: die toont geen gegevens van een andere tool
function klFacLaad(){
  klFac=[]; if(!klFacMag()) return;
  let d; try{ d=JSON.parse(Android.facturenDossier()); }catch(e){ d={}; }
  klFac=(d.items||[]).filter(s=>s&&s.sha&&s.plekken&&s.plekken.app&&!s.plekken.app.weg).map(s=>({sha:s.sha,naam:s.naam,leverancier:s.leverancier,leverancierNaam:s.leverancierNaam,opgehaald:s.opgehaald,meta:{nummer:klFacMeta(s).nummer,datum:klFacMeta(s).datum,bedrag:klFacMeta(s).bedrag}})); // alleen wat de lijst nodig heeft
}
function klFacturen(){ if(!klFacMag()) return []; if(klFac===null) klFacLaad(); return klFac; }
function klFacMeta(s){ return s.meta&&typeof s.meta==='object'?s.meta:{}; }
function klFacRij(s,metLev){
  const m=klFacMeta(s), sub=[];
  if(metLev&&s.leverancierNaam) sub.push(s.leverancierNaam); if(m.nummer) sub.push(m.nummer);
  if(m.bedrag!==undefined&&m.bedrag!==''&&isFinite(+m.bedrag)) sub.push(fmtMoney(+m.bedrag));
  if(/^\d{4}-\d{2}-\d{2}/.test(m.datum||'')) sub.push(fmtDate(String(m.datum).slice(0,10),{day:'numeric',month:'short',year:'numeric'})); else if(s.opgehaald) sub.push(fmtDate(s.opgehaald,{day:'numeric',month:'short',year:'numeric'}));
  return '<div class="kl-rij" translate="no"><button class="kl-facrij" data-sha="'+esc(s.sha)+'" onclick="klFactuur(this.dataset.sha)"><span class="stic">'+uiIcon('document')+'</span><span class="kl-tekst"><b>'+esc(s.naam||t('Factuur'))+'</b><small>'+esc(sub.filter(Boolean).join(' · '))+'</small></span></button></div>';
}
function klFactuur(sha){ const s=(klTeken.fac||[]).find(x=>x.sha===sha); if(s) Android.facturenOpen(s.leverancier,s.naam); }
/* Eigenschappen van een document (soort, partij, datum, bedrag, verloopt, labels) staan in props, in de versleutelde index. */
const KL_BINNENKORT=90; // dagen: vanaf dan telt een document als "verloopt binnenkort"
function klProps(d){ return d&&d.props&&typeof d.props==='object'?d.props:{}; }
/** Dagen tot de verloopdatum (negatief = verlopen); null zonder verloopdatum. */
function klDagen(d){ const v=klProps(d).verloopt; if(!v) return null; const n=rgDayDiff(rgToday(),v); return isFinite(n)?n:null; }
function klVerloopt(d){ const n=klDagen(d); return n!==null&&n<=KL_BINNENKORT; }
function klVerloopTekst(d){ const n=klDagen(d); if(n===null) return ''; const dag=fmtDate(klProps(d).verloopt,{day:'numeric',month:'short',year:'numeric'}); return n<0?t('Verlopen op {dag}',{dag}):t('Verloopt op {dag}',{dag}); }
function klZoekTekst(d){ const p=klProps(d); return [d.name,d.category,d.note,p.soort,p.partij].concat(Array.isArray(p.labels)?p.labels:[]); }
/** Het pad bovenaan (Documenten › Wonen › Hypotheek) en de mappen van deze plek als rijen. */
function klTekenMappen(docs,fac){
  const namen=klMapNamen(docs), heeft=m=>m==='all'||m==='fac'?m==='all'||fac.length>0:m.startsWith('fac:')?fac.some(s=>s.leverancier===m.slice(4)):docs.some(d=>klInMap(d,m,true));
  while(klMap!=='all'&&!heeft(klMap)){ const i=klMap.lastIndexOf('/'); klMap=klMap.startsWith('map:')&&i>0?klMap.slice(0,i):klMap.startsWith('fac:')?'fac':'all'; } // een plek die leeg raakte: omhoog
  // Het pad
  const pad=$('#kl-pad'), kruimels=[['all',t('Documenten')]];
  if(klMap==='fav') kruimels.push(['fav',t('Favorieten')]);
  else if(klMap==='exp') kruimels.push(['exp',t('Verloopt binnenkort')]);
  else if(klMap==='fac'||klMap.startsWith('fac:')){ kruimels.push(['fac',t('Facturen')]); if(klMap!=='fac'){ const s=fac.find(x=>x.leverancier===klMap.slice(4)); kruimels.push([klMap,(s&&s.leverancierNaam)||t('Leverancier')]); } }
  else if(klMap.startsWith('map:')){ let p=''; klMap.slice(4).split('/').forEach(deel=>{ p=p?p+'/'+deel:deel; kruimels.push(['map:'+p,deel]); }); }
  pad.replaceChildren(); pad.dataset.map=klMap; if(klMap==='all') delete pad.dataset.diep; else pad.dataset.diep='1';
  kruimels.forEach(([map,naam],i)=>{
    if(i){ const st=document.createElement('span'); st.className='kl-padst'; st.setAttribute('aria-hidden','true'); st.textContent='›'; pad.appendChild(st); }
    const b=document.createElement('button'), hier=i===kruimels.length-1; b.type='button'; b.dataset.map=map; b.textContent=naam; b.setAttribute('translate','no');
    if(hier) b.setAttribute('aria-current','location');
    b.addEventListener('click',()=>klKies(map)); pad.appendChild(b);
  });
  pad.hidden=!docs.length&&!fac.length;
  // De mappen van deze plek
  const rijen=[];
  if(klMap==='all'){
    const fav=docs.filter(d=>d.favorite).length, exp=docs.filter(klVerloopt).length;
    if(fav) rijen.push(['fav',t('Favorieten'),fav,'star']);
    if(exp) rijen.push(['exp',t('Verloopt binnenkort'),exp,'clock']);
  }
  if(klMap==='all'||klMap.startsWith('map:')){
    const basis=klMap==='all'?'':klMap.slice(4)+'/';
    [...new Set(namen.filter(n=>n.startsWith(basis)&&n.length>basis.length).map(n=>n.slice(basis.length).split('/')[0]))]
      .sort((a,b)=>a.localeCompare(b,'nl',{sensitivity:'base'})).forEach(deel=>rijen.push(['map:'+basis+deel,deel,docs.filter(d=>klOnder(klPad(d.category),basis+deel)).length,'folder']));
  }
  if(klMap==='all'&&fac.length) rijen.push(['fac',t('Facturen'),fac.length,'document']);
  if(klMap==='fac'){ const per=new Map(); fac.forEach(s=>{ const k=s.leverancier||''; if(!per.has(k)) per.set(k,[s.leverancierNaam||t('Leverancier'),0]); per.get(k)[1]++; });
    [...per.entries()].sort((a,b)=>a[1][0].localeCompare(b[1][0],'nl',{sensitivity:'base'})).forEach(([k,[naam,n]])=>rijen.push(['fac:'+k,naam,n,'folder'])); }
  const box=$('#kl-mappen'); box.replaceChildren(); box.hidden=!rijen.length;
  rijen.forEach(([map,naam,n,icoon])=>{
    const b=document.createElement('button'); b.type='button'; b.className='kl-maprij'; b.dataset.map=map; b.setAttribute('translate','no');
    b.insertAdjacentHTML('beforeend','<span class="stic">'+uiIcon(icoon)+'</span>');
    const tk=document.createElement('span'); tk.className='kl-tekst'; const nm=document.createElement('b'); nm.className='kl-naam'; nm.textContent=naam; tk.appendChild(nm);
    const sm=document.createElement('small'); sm.textContent=tn(n,'Eén document','{n} documenten'); tk.appendChild(sm); b.appendChild(tk);
    const k=document.createElement('span'); k.className='kl-n'; k.hidden=true; k.textContent=fmtNum(n); b.appendChild(k);
    b.insertAdjacentHTML('beforeend','<span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span>');
    b.setAttribute('aria-label',tn(n,'Map {naam}: één document','Map {naam}: {n} documenten',{naam}));
    b.addEventListener('click',()=>klKies(map)); box.appendChild(b);
  });
  $('#kl-maphernoem').hidden=!klMap.startsWith('map:');
  return rijen.length;
}
function klRij(x,metMap,stuk){
  const soort=/pdf/.test(x.mime||'')?'document':/^image/.test(x.mime||'')?'image':'folder', p=klProps(x);
  const sub=[]; if(p.soort) sub.push(p.soort); if(p.partij) sub.push(p.partij); if(metMap&&x.category) sub.push(klPad(x.category).split('/').join(' › '));
  if(p.bedrag) sub.push(fmtMoney(p.bedrag));
  sub.push(p.datum?fmtDate(p.datum,{day:'numeric',month:'short',year:'numeric'}):fmtDate(x.t,{day:'numeric',month:'short',year:'numeric'}));
  sub.push(klSub(x));
  const n=klDagen(x), verloop=n!==null&&n<=KL_BINNENKORT?'<small class="kl-verloop"'+(n<0?' data-oud="1"':'')+'>'+esc(klVerloopTekst(x))+'</small>':'';
  const labels=Array.isArray(p.labels)&&p.labels.length?'<small class="kl-labels">'+p.labels.map(l=>'<span>'+esc(l)+'</span>').join('')+'</small>':'';
  return '<div class="kl-rij" translate="no"><button class="trip" data-id="'+esc(x.id)+'" onclick="klKaart(this.dataset.id)"><span class="stic">'+uiIcon(soort)+'</span><span class="kl-tekst"><b>'+esc(x.name)+'</b><small>'+esc(sub.filter(Boolean).join(' · '))+'</small>'+verloop+labels+(x.note?'<small class="kl-doc-note">'+esc(x.note)+'</small>':'')+(stuk?'<small class="kl-doc-note kl-stuk">'+esc(stuk)+'</small>':'')+'</span></button>'
    +'<button class="kl-ster" data-id="'+esc(x.id)+'" aria-pressed="'+(x.favorite?'true':'false')+'" aria-label="'+esc(t('Favoriet: {naam}',{naam:x.name}))+'" onclick="klFavorite(this.dataset.id)">'+uiIcon('star')+'</button></div>';
}
/** De inhoud van deze plek: documenten (en facturen); zoeken kijkt ook in de mappen eronder. */
function klTeken(docs){
  const fac=klTeken.fac=klFacturen(), mappen=klTekenMappen(docs,fac);
  const q=$('#kl-search').value.trim().toLowerCase(), sort=$('#kl-sort').value;
  if(q) $('#kl-mappen').hidden=true; // bij zoeken alleen de treffers (ook uit de mappen eronder)
  if(!klRender.zonderVraag) klTekstVraag(q); // ook in de tekst van documenten (34-uitbreidingen.js)
  const inTekst=d=>klT.q===q&&q&&typeof klT.hits[d.id]==='string'?klT.hits[d.id]:'';
  const bereik=docs.filter(d=>klInMap(d,klMap,!!q)), shown=bereik.filter(d=>!q||inTekst(d)||klZoekTekst(d).some(v=>(v||'').toLowerCase().includes(q)));
  const naam=(a,b)=>(a.name||'').localeCompare(b.name||'','nl',{sensitivity:'base'});
  const op=k=>(a,b)=>{const w=d=>klProps(d)[k]||(k==='datum'?rgDayKey(d.t||0):''),x=w(a),y=w(b);return x&&y?(k==='datum'?y.localeCompare(x):x.localeCompare(y))||naam(a,b):x?-1:y?1:naam(a,b);}; // zonder datum onderaan
  shown.sort(sort==='name'?naam:sort==='datum'||sort==='verloopt'?op(sort):(a,b)=>(b.t||0)-(a.t||0));
  // Facturen: in de map Facturen (per leverancier), en bij zoeken vanaf het bovenste niveau
  const facZoek=s=>[s.naam,s.leverancierNaam,klFacMeta(s).nummer].some(v=>String(v||'').toLowerCase().includes(q));
  const facHier=(klMap.startsWith('fac:')?fac.filter(s=>s.leverancier===klMap.slice(4)):q&&(klMap==='fac'||klMap==='all')?fac:[]).filter(s=>!q||facZoek(s))
    .sort((a,b)=>String(klFacMeta(b).datum||'').localeCompare(String(klFacMeta(a).datum||''))||(b.opgehaald||0)-(a.opgehaald||0));
  const inFac=klMap==='fac'||klMap.startsWith('fac:'), niets=!shown.length&&!facHier.length&&!(mappen&&!q);
  $('#kl-empty').style.display=niets?'block':'none';
  $('#kl-empty').textContent=docs.length||fac.length?(q?t('Geen documenten bij deze zoekopdracht.'):t('Deze map is leeg.')):t('Nog leeg. Voeg een document, foto of PDF toe, zoals een kopie van je paspoort of een polis.');
  const aantal=inFac?facHier.length:shown.length;
  $('#kl-count').textContent=q&&!inFac?tn(bereik.length,'{k} van {n} document','{k} van {n} documenten',{k:shown.length}):aantal||!mappen?tn(aantal,'Eén document','{n} documenten'):'';
  $('.kl-balk').hidden=!docs.length&&!fac.length; $('#kl-sort').hidden=inFac;
  $('#kl-fachint').hidden=!inFac;
  const kop=tekst=>'<h3 class="kl-kop">'+esc(tekst)+'</h3>';
  $('#kl-list').innerHTML=shown.map(x=>klRij(x,!!q||klMap==='fav'||klMap==='exp',inTekst(x))).join('')+(facHier.length&&!inFac?kop(t('Facturen')):'')+facHier.map(s=>klFacRij(s,!klMap.startsWith('fac:'))).join('');
  klKaartKop();
}
/* Een nieuw document komt in de map waar je staat (toevoegen vanuit een map) */
let klNieuwIn=null;
function klNieuwInMap(){
  const w=klNieuwIn; klNieuwIn=null; if(!w||Date.now()-w.t>10*60000) return;
  let d; try{ d=JSON.parse(Android.kluisList()); }catch(e){ return; }
  let fout=''; (d.docs||[]).filter(x=>!w.ids.includes(x.id)&&!x.category&&(x.t||0)>=w.t-2000).forEach(x=>{ try{ fout=Android.kluisOrganize(x.id,JSON.stringify({category:w.map}))||fout; }catch(e){ fout='x'; } }); // alleen wat ná de tik is toegevoegd
  if(fout) toast(t('Toegevoegd, maar niet in de map {map}',{map:w.map}));
}
/** Eén document naar een andere map: bestaande mappen, een nieuwe of geen. */
function klZetMap(id,map){
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({category:map}));}catch(e){err='Document ordenen lukt niet';}
  if(!err&&klEditId===id) $('#kl-edit-category').value=map; // de kaart van dit document staat open
  klRender(); toast(err?t(err):map?t('Verplaatst naar {map}',{map}):t('Uit de map gehaald'));
}
function klVerplaats(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  const acts=klMapNamen(klRender.docs).filter(n=>n!==klPad(d.category)).map(n=>[n,()=>klZetMap(id,n),'folder']);
  acts.push([t('Nieuwe map…'),()=>askInput(t('Nieuwe map'),t('Naam van de map, bijvoorbeeld Verzekeringen. Een map in een map schrijf je met een schuine streep: Wonen/Hypotheek.'),klMap.startsWith('map:')?klMap.slice(4)+'/':'',t('Verplaatsen'),v=>{v=klPad(v);if(!v||v.length>60){toast(t('Kies een naam van 1 tot 60 tekens'));return;}klZetMap(id,v);}),'plus']);
  if(d.category) acts.push([t('Geen map'),()=>klZetMap(id,'')]);
  const mappen=acts.length-(d.category?2:1); // de eerste knoppen zijn mapnamen: gegevens, niet vertalen
  openSheet(t('Verplaatsen naar map'),d.name,acts);
  $('#sheet-sub').setAttribute('translate','no');
  [...$('#sheet-acts').children].slice(0,mappen).forEach(b=>b.setAttribute('translate','no'));
}
/** De map waar je staat een andere naam (of plek) geven: alles in die map en de mappen eronder gaat mee. */
function klMapHernoem(){
  if(!klMap.startsWith('map:'))return;
  const oud=klMap.slice(4);
  askInput(t('Map hernoemen'),t('Bestaat de nieuwe naam al, dan komen de documenten bij elkaar in die map. Met een schuine streep zet je de map in een andere map.'),oud,t('Opslaan'),v=>{
    v=klPad(v);if(!v||v.length>60){toast(t('Kies een naam van 1 tot 60 tekens'));return;}
    if(v===oud)return;
    if(klOnder(v,oud)){toast(t('Een map kan niet in zichzelf'));return;}
    klRender(); // de kluis kan intussen op slot zijn gegaan: dan is de lijst leeg en staat het slotscherm er
    const lijst=(klRender.docs||[]).filter(d=>klOnder(klPad(d.category),oud)); if(!lijst.length) return;
    if(lijst.some(d=>(v+klPad(d.category).slice(oud.length)).length>60)){toast(t('Die naam is te lang voor de mappen die erin zitten'));return;}
    if(typeof Android.kluisMapHernoem==='function'){ // in één keer (een oudere app: per document, hieronder)
      let r; try{r=JSON.parse(Android.kluisMapHernoem(oud,v));}catch(e){r={error:'Document ordenen lukt niet'};}
      if(!r.error) klMap='map:'+v;
      klRender(); toast(r.error?t(r.error):t('Map hernoemd')); return;
    }
    let err='',n=0;
    for(const d of lijst){
      try{err=Android.kluisOrganize(d.id,JSON.stringify({category:v+klPad(d.category).slice(oud.length)}));}catch(e){err='Document ordenen lukt niet';}
      if(err)break; n++;
    }
    if(n) klMap='map:'+v;
    klRender(); toast(!err?t('Map hernoemd'):n?t('{n} van {m} documenten verplaatst: {fout}',{n:fmtNum(n),m:fmtNum(lijst.length),fout:t(err)}):t(err));
  });
}
/* ---------- Documentkaart: één document met zijn eigenschappen en acties, in plaats van de lijst ---------- */
function klKaartDoc(){ return klEditId?(klRender.docs||[]).find(d=>d.id===klEditId):null; }
/** Kop van de kaart (naam, regel eronder, knoppen) uit de index; de ingevulde velden blijven zoals ze zijn. */
function klKaartKop(){
  if(!klEditId) return;
  const d=klKaartDoc(); if(!d){ klOrganizeClose(); return; }
  $('#kl-edit-title').textContent=d.name;
  $('#kl-doc-ic').innerHTML=uiIcon(/pdf/.test(d.mime||'')?'document':/^image/.test(d.mime||'')?'image':'folder');
  $('#kl-doc-sub').textContent=[klSub(d),t('toegevoegd op {dag}',{dag:fmtDate(d.t,{day:'numeric',month:'short',year:'numeric'})}),klVerloopTekst(d)].filter(Boolean).join(' · ');
}
function klKaart(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  const p=klProps(d);
  klEditId=id;
  $('#kl-edit-soort').value=p.soort||'';$('#kl-edit-category').value=d.category||'';$('#kl-edit-partij').value=p.partij||'';
  $('#kl-edit-datum').value=p.datum||'';$('#kl-edit-verloopt').value=p.verloopt||'';$('#kl-edit-bedrag').value=p.bedrag?fmtNumIn(+p.bedrag,2):'';
  $('#kl-edit-labels').value=(Array.isArray(p.labels)?p.labels:[]).join(', ');
  $('#kl-edit-note').value=d.note||'';$('#kl-edit-favorite').checked=!!d.favorite;
  const mappen=$('#kl-category-suggestions'), vast=['Identiteit','Verzekeringen','Wonen','Gezondheid','Financiën','Werk','PDF'];
  mappen.replaceChildren();[...new Set(klMapNamen(klRender.docs||[]).concat(vast))].forEach(n=>{const o=document.createElement('option');o.value=n;mappen.appendChild(o);});
  $('#kl-edit-status').textContent='';$('#kl-lijstkaart').hidden=true;$('#kl-edit').hidden=false;klKaartKop();
  $('#kl-edit').scrollIntoView({block:'start'});$('#kl-edit-title').focus({preventScroll:true});
}
function klOrganizeClose(){
  klEditId=null;$('#kl-edit').hidden=true;const l=$('#kl-lijstkaart');if(l)l.hidden=typeof ksOpen==='function'&&ksOpen(); // tijdens Brief scannen blijft de lijst weg
  $('#kl-edit-title').textContent='';$('#kl-doc-sub').textContent='';
  ['soort','category','partij','datum','verloopt','bedrag','labels','note'].forEach(k=>{$('#kl-edit-'+k).value='';});
  $('#kl-edit-favorite').checked=false;$('#kl-edit-status').textContent='';
}
function klKaartBekijk(){ const d=klKaartDoc(); if(d) klOpen(d.id); } // Java kiest aan de inhoud: de eigen viewer (PDF, afbeelding) of een andere app
function klKaartDeel(){ const d=klKaartDoc(); if(d) Android.kluisShare(d.id,0); }
function klKaartMeer(){ if(klEditId) klDocSheet(klEditId); }
function klOrganizeSave(){
  if(!klEditId)return;
  const fout=tekst=>{$('#kl-edit-status').textContent=tekst;toast(tekst);};
  const ruw=$('#kl-edit-bedrag').value.trim(); let bedrag='';
  if(ruw){const n=parseNum(ruw); if(n===null||!isFinite(n)||Math.abs(n)>=1e9){fout(t('Vul een geldig bedrag in, of laat het leeg'));return;} bedrag=(Math.round(n*100)/100).toFixed(2);} // machinale notatie voor de opslag
  const gezien=new Set(), labels=$('#kl-edit-labels').value.split(',').map(l=>l.trim()).filter(l=>{const k=l.toLowerCase();if(!l||gezien.has(k))return false;gezien.add(k);return true;}); // zoals KluisIndex.props: zonder dubbele, hoofdletters tellen niet
  if(labels.length>12||labels.some(l=>l.length>30)){fout(t('Hooguit 12 labels van elk hooguit 30 tekens'));return;}
  const props={soort:$('#kl-edit-soort').value.trim(),partij:$('#kl-edit-partij').value.trim(),datum:$('#kl-edit-datum').value,verloopt:$('#kl-edit-verloopt').value,bedrag,labels};
  const patch={category:klPad($('#kl-edit-category').value),note:$('#kl-edit-note').value.trim(),favorite:$('#kl-edit-favorite').checked,props};
  let err;try{err=Android.kluisOrganize(klEditId,JSON.stringify(patch));}catch(e){err='Document ordenen lukt niet';}
  if(err){klRender();if(klEditId)fout(t(err));return;}
  klOrganizeClose();klRender();toast(t('Documentgegevens opgeslagen'));
}
/** Terug (pijl of de terugknop van de telefoon) op de documentkaart: eerst terug naar de lijst; goBack roept dit aan. */
function klKaartTerug(){ if(typeof ksTerug==='function'&&ksTerug()) return true; if(klEditId){ klOrganizeClose(); return true; } return klOmhoog(); } // en anders een map omhoog
function klFavorite(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({favorite:!d.favorite}));}catch(e){err='Favoriet wijzigen lukt niet';}
  if(err)toast(err);else if(klEditId===id)$('#kl-edit-favorite').checked=!d.favorite; // de kaart van dit document staat open
  klRender();
}
