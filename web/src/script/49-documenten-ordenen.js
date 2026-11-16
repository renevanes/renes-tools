/* Documentenkluis: mappen, de documentenlijst en het ordenen van één document.
   Een map is de categorie van een document in de versleutelde index (Kluis.java); de keuze op het scherm staat
   alleen in het geheugen en is weg zodra de kluis op slot gaat. Namen van mappen en documenten zijn gegevens:
   altijd esc()/textContent en translate="no". */
let klEditId=null, klMap='all'; // 'all' | 'fav' | 'none' | 'map:<naam>'
function klMapNamen(docs){ return [...new Set(docs.map(d=>d.category||'').filter(Boolean))].sort((a,b)=>a.localeCompare(b,'nl',{sensitivity:'base'})); }
function klInMap(d,map){ return map==='all'||(map==='fav'?!!d.favorite:map==='none'?!d.category:map==='map:'+(d.category||'')); }
function klKies(map){ klMap=map; klRender(); }
/** Mappen als chips met aantallen; een map die leeg raakte, valt terug op Alle. */
function klTekenMappen(docs){
  const namen=klMapNamen(docs), zonder=docs.filter(d=>!d.category).length, fav=docs.filter(d=>d.favorite).length;
  const lijst=[['all',t('Alle'),docs.length,'']];
  if(fav||klMap==='fav') lijst.push(['fav',t('Favorieten'),fav,'star']);
  namen.forEach(n=>lijst.push(['map:'+n,n,docs.filter(d=>d.category===n).length,'folder']));
  if(namen.length&&zonder||klMap==='none') lijst.push(['none',t('Zonder map'),zonder,'']);
  if(!lijst.some(x=>x[0]===klMap)||(klMap!=='all'&&!docs.some(d=>klInMap(d,klMap)))) klMap='all';
  const box=$('#kl-mappen'); box.replaceChildren(); box.hidden=!docs.length;
  lijst.forEach(([map,naam,n,icoon])=>{
    if(map==='none'&&!zonder&&klMap!=='none'||map==='fav'&&!fav&&klMap!=='fav') return;
    const b=document.createElement('button'), aan=map===klMap; b.type='button'; b.dataset.map=map; b.className=aan?'on':''; b.setAttribute('aria-pressed',aan?'true':'false'); b.setAttribute('translate','no');
    if(icoon) b.insertAdjacentHTML('beforeend',uiIcon(icoon));
    const s=document.createElement('span'); s.className='kl-naam'; s.textContent=naam; b.appendChild(s);
    const k=document.createElement('span'); k.className='kl-n'; k.textContent=fmtNum(n); b.appendChild(k);
    b.setAttribute('aria-label',tn(n,'{naam}: één document','{naam}: {n} documenten',{naam}));
    b.addEventListener('click',()=>klKies(map)); box.appendChild(b);
  });
  $('#kl-maphernoem').hidden=!klMap.startsWith('map:');
}
function klRij(x,metMap){
  const soort=/pdf/.test(x.mime||'')?'document':/^image/.test(x.mime||'')?'image':'folder';
  const sub=[]; if(metMap&&x.category) sub.push(x.category); sub.push(klSub(x),fmtDate(x.t,{day:'numeric',month:'short',year:'numeric'}));
  return '<div class="kl-rij" translate="no"><button class="trip" data-id="'+esc(x.id)+'" onclick="klMenu(this.dataset.id)"><span class="stic">'+uiIcon(soort)+'</span><span class="kl-tekst"><b>'+esc(x.name)+'</b><small>'+esc(sub.join(' · '))+'</small>'+(x.note?'<small class="kl-doc-note">'+esc(x.note)+'</small>':'')+'</span></button>'
    +'<button class="kl-ster" data-id="'+esc(x.id)+'" aria-pressed="'+(x.favorite?'true':'false')+'" aria-label="'+esc(t('Favoriet: {naam}',{naam:x.name}))+'" onclick="klFavorite(this.dataset.id)">'+uiIcon('star')+'</button></div>';
}
/** De lijst: zoeken en de gekozen map filteren; in Alle staan favorieten bovenaan, of alles per map bij die sortering. */
function klTeken(docs){
  klTekenMappen(docs);
  const q=$('#kl-search').value.trim().toLowerCase(), sort=$('#kl-sort').value;
  const shown=docs.filter(d=>(!q||[d.name,d.category,d.note].some(v=>(v||'').toLowerCase().includes(q)))&&klInMap(d,klMap));
  const naam=(a,b)=>(a.name||'').localeCompare(b.name||'','nl',{sensitivity:'base'});
  shown.sort(sort==='name'||sort==='category'?naam:(a,b)=>(b.t||0)-(a.t||0));
  $('#kl-empty').style.display=shown.length?'none':'block';
  $('#kl-empty').textContent=docs.length?t('Geen documenten bij deze zoekopdracht of filters.'):t('Nog leeg. Voeg een document, foto of PDF toe, zoals een kopie van je paspoort of een polis.');
  $('#kl-count').textContent=docs.length?tn(docs.length,'{k} van {n} document','{k} van {n} documenten',{k:shown.length}):'';
  $('.kl-balk').hidden=!docs.length;
  const kop=tekst=>'<h3 class="kl-kop" translate="no">'+esc(tekst)+'</h3>', rijen=(l,metMap)=>l.map(x=>klRij(x,metMap)).join('');
  let html;
  if(klMap==='all'&&sort==='category'){
    const groepen=klMapNamen(shown).map(n=>[n,shown.filter(d=>d.category===n)]), los=shown.filter(d=>!d.category);
    if(los.length) groepen.push([t('Zonder map'),los]);
    html=groepen.map(([n,l])=>kop(n)+rijen(l,false)).join('');
  } else {
    const fav=klMap==='all'?shown.filter(d=>d.favorite):[], rest=fav.length?shown.filter(d=>!d.favorite):shown;
    html=fav.length&&rest.length?kop(t('Favorieten'))+rijen(fav,true)+kop(t('Overige documenten'))+rijen(rest,true):rijen(shown,!klMap.startsWith('map:'));
  }
  $('#kl-list').innerHTML=html;
}
/** Eén document naar een andere map: bestaande mappen, een nieuwe of geen. */
function klZetMap(id,map){
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({category:map}));}catch(e){err='Document ordenen lukt niet';}
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
function klOrganizeOpen(id){
  klRender();const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  klEditId=id;$('#kl-edit-title').textContent=t('Ordenen: {naam}',{naam:d.name});
  $('#kl-edit-category').value=d.category||'';$('#kl-edit-note').value=d.note||'';$('#kl-edit-favorite').checked=!!d.favorite;
  $('#kl-edit-status').textContent='';$('#kl-edit').hidden=false;$('#kl-edit').scrollIntoView({block:'center'});$('#kl-edit-category').focus();
}
function klOrganizeClose(){
  klEditId=null;$('#kl-edit').hidden=true;$('#kl-edit-title').textContent='Document ordenen';$('#kl-edit-category').value='';$('#kl-edit-note').value='';$('#kl-edit-favorite').checked=false;$('#kl-edit-status').textContent='';
}
function klOrganizeSave(){
  if(!klEditId)return;
  const patch={category:$('#kl-edit-category').value.trim(),note:$('#kl-edit-note').value.trim(),favorite:$('#kl-edit-favorite').checked};
  let err;try{err=Android.kluisOrganize(klEditId,JSON.stringify(patch));}catch(e){err='Document ordenen lukt niet';}
  if(err){klRender();$('#kl-edit-status').textContent=err;toast(err);return;}
  klOrganizeClose();klRender();toast('Documentgegevens opgeslagen');
}
function klFavorite(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({favorite:!d.favorite}));}catch(e){err='Favoriet wijzigen lukt niet';}
  if(err)toast(err);klRender();
}
