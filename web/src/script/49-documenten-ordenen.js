/* Documentenkluis: mappen, de documentenlijst en het ordenen van één document.
   Een map is de categorie van een document in de versleutelde index (Kluis.java); de keuze op het scherm staat
   alleen in het geheugen en is weg zodra de kluis op slot gaat. Namen van mappen en documenten zijn gegevens:
   altijd esc()/textContent en translate="no". */
let klEditId=null, klMap='all'; // 'all' | 'fav' | 'exp' | 'none' | 'map:<naam>'
function klMapNamen(docs){ return [...new Set(docs.map(d=>d.category||'').filter(Boolean))].sort((a,b)=>a.localeCompare(b,'nl',{sensitivity:'base'})); }
function klInMap(d,map){ return map==='all'||(map==='fav'?!!d.favorite:map==='exp'?klVerloopt(d):map==='none'?!d.category:map==='map:'+(d.category||'')); }
function klKies(map){ klMap=map; klRender(); }
/* Eigenschappen van een document (soort, partij, datum, bedrag, verloopt, labels) staan in props, in de versleutelde index. */
const KL_BINNENKORT=90; // dagen: vanaf dan telt een document als "verloopt binnenkort"
function klProps(d){ return d&&d.props&&typeof d.props==='object'?d.props:{}; }
/** Dagen tot de verloopdatum (negatief = verlopen); null zonder verloopdatum. */
function klDagen(d){ const v=klProps(d).verloopt; if(!v) return null; const n=rgDayDiff(rgToday(),v); return isFinite(n)?n:null; }
function klVerloopt(d){ const n=klDagen(d); return n!==null&&n<=KL_BINNENKORT; }
function klVerloopTekst(d){ const n=klDagen(d); if(n===null) return ''; const dag=fmtDate(klProps(d).verloopt,{day:'numeric',month:'short',year:'numeric'}); return n<0?t('Verlopen op {dag}',{dag}):t('Verloopt op {dag}',{dag}); }
function klZoekTekst(d){ const p=klProps(d); return [d.name,d.category,d.note,p.soort,p.partij].concat(Array.isArray(p.labels)?p.labels:[]); }
/** Mappen als chips met aantallen; een map die leeg raakte, valt terug op Alle. */
function klTekenMappen(docs){
  const namen=klMapNamen(docs), zonder=docs.filter(d=>!d.category).length, fav=docs.filter(d=>d.favorite).length;
  const lijst=[['all',t('Alle'),docs.length,'']];
  if(fav||klMap==='fav') lijst.push(['fav',t('Favorieten'),fav,'star']);
  const exp=docs.filter(klVerloopt).length; if(exp||klMap==='exp') lijst.push(['exp',t('Verloopt binnenkort'),exp,'clock']);
  namen.forEach(n=>lijst.push(['map:'+n,n,docs.filter(d=>d.category===n).length,'folder']));
  if(namen.length&&zonder||klMap==='none') lijst.push(['none',t('Zonder map'),zonder,'']);
  if(!lijst.some(x=>x[0]===klMap)||(klMap!=='all'&&!docs.some(d=>klInMap(d,klMap)))) klMap='all';
  const box=$('#kl-mappen'); box.replaceChildren(); box.hidden=!docs.length;
  lijst.forEach(([map,naam,n,icoon])=>{
    if(!n&&map!=='all'&&map!==klMap) return; // een lege keuze die net is losgelaten
    const b=document.createElement('button'), aan=map===klMap; b.type='button'; b.dataset.map=map; b.className=aan?'on':''; b.setAttribute('aria-pressed',aan?'true':'false'); b.setAttribute('translate','no');
    if(icoon) b.insertAdjacentHTML('beforeend',uiIcon(icoon));
    const s=document.createElement('span'); s.className='kl-naam'; s.textContent=naam; b.appendChild(s);
    const k=document.createElement('span'); k.className='kl-n'; k.textContent=fmtNum(n); b.appendChild(k);
    b.setAttribute('aria-label',tn(n,'{naam}: één document','{naam}: {n} documenten',{naam}));
    b.addEventListener('click',()=>klKies(map)); box.appendChild(b);
  });
  $('#kl-maphernoem').hidden=!klMap.startsWith('map:');
}
function klRij(x,metMap,stuk){
  const soort=/pdf/.test(x.mime||'')?'document':/^image/.test(x.mime||'')?'image':'folder', p=klProps(x);
  const sub=[]; if(p.soort) sub.push(p.soort); if(p.partij) sub.push(p.partij); if(metMap&&x.category) sub.push(x.category);
  if(p.bedrag) sub.push(fmtMoney(p.bedrag));
  sub.push(p.datum?fmtDate(p.datum,{day:'numeric',month:'short',year:'numeric'}):fmtDate(x.t,{day:'numeric',month:'short',year:'numeric'}));
  sub.push(klSub(x));
  const n=klDagen(x), verloop=n!==null&&n<=KL_BINNENKORT?'<small class="kl-verloop"'+(n<0?' data-oud="1"':'')+'>'+esc(klVerloopTekst(x))+'</small>':'';
  const labels=Array.isArray(p.labels)&&p.labels.length?'<small class="kl-labels">'+p.labels.map(l=>'<span>'+esc(l)+'</span>').join('')+'</small>':'';
  return '<div class="kl-rij" translate="no"><button class="trip" data-id="'+esc(x.id)+'" onclick="klKaart(this.dataset.id)"><span class="stic">'+uiIcon(soort)+'</span><span class="kl-tekst"><b>'+esc(x.name)+'</b><small>'+esc(sub.filter(Boolean).join(' · '))+'</small>'+verloop+labels+(x.note?'<small class="kl-doc-note">'+esc(x.note)+'</small>':'')+(stuk?'<small class="kl-doc-note kl-stuk">'+esc(stuk)+'</small>':'')+'</span></button>'
    +'<button class="kl-ster" data-id="'+esc(x.id)+'" aria-pressed="'+(x.favorite?'true':'false')+'" aria-label="'+esc(t('Favoriet: {naam}',{naam:x.name}))+'" onclick="klFavorite(this.dataset.id)">'+uiIcon('star')+'</button></div>';
}
/** De lijst: zoeken en de gekozen map filteren; in Alle staan favorieten bovenaan, of alles per map bij die sortering. */
function klTeken(docs){
  klTekenMappen(docs);
  const q=$('#kl-search').value.trim().toLowerCase(), sort=$('#kl-sort').value;
  if(!klRender.zonderVraag) klTekstVraag(q); // ook in de tekst van documenten (34-uitbreidingen.js)
  const inTekst=d=>klT.q===q&&q&&typeof klT.hits[d.id]==='string'?klT.hits[d.id]:'';
  const shown=docs.filter(d=>(!q||inTekst(d)||klZoekTekst(d).some(v=>(v||'').toLowerCase().includes(q)))&&klInMap(d,klMap));
  const naam=(a,b)=>(a.name||'').localeCompare(b.name||'','nl',{sensitivity:'base'});
  const op=k=>(a,b)=>{const w=d=>klProps(d)[k]||(k==='datum'?rgDayKey(d.t||0):''),x=w(a),y=w(b);return x&&y?(k==='datum'?y.localeCompare(x):x.localeCompare(y))||naam(a,b):x?-1:y?1:naam(a,b);}; // zonder datum onderaan
  shown.sort(sort==='name'||sort==='category'?naam:sort==='datum'||sort==='verloopt'?op(sort):(a,b)=>(b.t||0)-(a.t||0));
  $('#kl-empty').style.display=shown.length?'none':'block';
  $('#kl-empty').textContent=docs.length?t('Geen documenten bij deze zoekopdracht of filters.'):t('Nog leeg. Voeg een document, foto of PDF toe, zoals een kopie van je paspoort of een polis.');
  $('#kl-count').textContent=docs.length?tn(docs.length,'{k} van {n} document','{k} van {n} documenten',{k:shown.length}):'';
  $('.kl-balk').hidden=!docs.length;
  const kop=tekst=>'<h3 class="kl-kop" translate="no">'+esc(tekst)+'</h3>', rijen=(l,metMap)=>l.map(x=>klRij(x,metMap,inTekst(x))).join('');
  let html;
  if(klMap==='all'&&sort==='category'){
    const groepen=klMapNamen(shown).map(n=>[n,shown.filter(d=>d.category===n)]), los=shown.filter(d=>!d.category);
    if(los.length) groepen.push([t('Zonder map'),los]);
    html=groepen.map(([n,l])=>kop(n)+rijen(l,false)).join('');
  } else {
    const fav=klMap==='all'&&sort!=='datum'&&sort!=='verloopt'?shown.filter(d=>d.favorite):[], rest=fav.length?shown.filter(d=>!d.favorite):shown;
    html=fav.length&&rest.length?kop(t('Favorieten'))+rijen(fav,true)+kop(t('Overige documenten'))+rijen(rest,true):rijen(shown,!klMap.startsWith('map:'));
  }
  $('#kl-list').innerHTML=html;
  klKaartKop();
}
/** Eén document naar een andere map: bestaande mappen, een nieuwe of geen. */
function klZetMap(id,map){
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({category:map}));}catch(e){err='Document ordenen lukt niet';}
  if(!err&&klEditId===id) $('#kl-edit-category').value=map; // de kaart van dit document staat open
  klRender(); toast(err?t(err):map?t('Verplaatst naar {map}',{map}):t('Uit de map gehaald'));
}
function klVerplaats(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  const acts=klMapNamen(klRender.docs).filter(n=>n!==(d.category||'')).map(n=>[n,()=>klZetMap(id,n),'folder']);
  acts.push([t('Nieuwe map…'),()=>askInput(t('Nieuwe map'),t('Naam van de map, bijvoorbeeld Verzekeringen.'),'',t('Verplaatsen'),v=>{v=(v||'').trim();if(!v||v.length>60){toast(t('Kies een naam van 1 tot 60 tekens'));return;}klZetMap(id,v);}),'plus']);
  if(d.category) acts.push([t('Geen map'),()=>klZetMap(id,'')]);
  const mappen=acts.length-(d.category?2:1); // de eerste knoppen zijn mapnamen: gegevens, niet vertalen
  openSheet(t('Verplaatsen naar map'),d.name,acts);
  $('#sheet-sub').setAttribute('translate','no');
  [...$('#sheet-acts').children].slice(0,mappen).forEach(b=>b.setAttribute('translate','no'));
}
/** De gekozen map een andere naam geven: elk document in die map krijgt de nieuwe naam. */
function klMapHernoem(){
  if(!klMap.startsWith('map:'))return;
  const oud=klMap.slice(4);
  askInput(t('Map hernoemen'),t('Bestaat de nieuwe naam al, dan komen de documenten bij elkaar in die map.'),oud,t('Opslaan'),v=>{
    v=(v||'').trim();if(!v||v.length>60){toast(t('Kies een naam van 1 tot 60 tekens'));return;}
    if(v===oud)return;
    klRender(); // de kluis kan intussen op slot zijn gegaan: dan is de lijst leeg en staat het slotscherm er
    const lijst=(klRender.docs||[]).filter(d=>d.category===oud); if(!lijst.length) return;
    let err='',n=0;
    for(const d of lijst){
      try{err=Android.kluisOrganize(d.id,JSON.stringify({category:v}));}catch(e){err='Document ordenen lukt niet';}
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
  klEditId=null;$('#kl-edit').hidden=true;const l=$('#kl-lijstkaart');if(l)l.hidden=false;
  $('#kl-edit-title').textContent='';$('#kl-doc-sub').textContent='';
  ['soort','category','partij','datum','verloopt','bedrag','labels','note'].forEach(k=>{$('#kl-edit-'+k).value='';});
  $('#kl-edit-favorite').checked=false;$('#kl-edit-status').textContent='';
}
function klKaartBekijk(){ const d=klKaartDoc(); if(d) klIsPdf(d)?klOpen(d.id):klView(d.id); }
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
  const patch={category:$('#kl-edit-category').value.trim(),note:$('#kl-edit-note').value.trim(),favorite:$('#kl-edit-favorite').checked,props};
  let err;try{err=Android.kluisOrganize(klEditId,JSON.stringify(patch));}catch(e){err='Document ordenen lukt niet';}
  if(err){klRender();if(klEditId)fout(t(err));return;}
  klOrganizeClose();klRender();toast(t('Documentgegevens opgeslagen'));
}
/** Terug (pijl of de terugknop van de telefoon) op de documentkaart: eerst terug naar de lijst; goBack roept dit aan. */
function klKaartTerug(){ if(!klEditId) return false; klOrganizeClose(); return true; }
function klFavorite(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({favorite:!d.favorite}));}catch(e){err='Favoriet wijzigen lukt niet';}
  if(err)toast(err);else if(klEditId===id)$('#kl-edit-favorite').checked=!d.favorite; // de kaart van dit document staat open
  klRender();
}
