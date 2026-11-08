/* Boeken openen, bewaren, doorzoeken en annoteren; EPUB-inhoud krijgt uitsluitend toegestane elementen. */
let erBooks=[],erBook=null,erIndex=0,erText='',erTimer=null,erBusy=false,erRestoring=false,erNodes=[],erSelection=null,erHit=null,erCurrentAnchor=0,erTab='chapters',erFindTimer=null,erRequest='',erCoverCache={},erCoverGeneration=0;
const ER_SCREENS=['erbook','ercontents','erset'];
const ER_STYLE={size:22,font:'serif',line:'1.65',margin:'28',align:'start',theme:'auto',mode:'pages'};
const ER_VALUES={size:[18,22,26,32,38],font:['serif','sans-serif','monospace'],line:['1.4','1.65','2'],margin:['16','28','44'],align:['start','justify'],theme:['auto','paper','sepia','night'],mode:['pages','scroll']};
function erData(raw){try{return JSON.parse(raw);}catch(e){return{error:t('Boek openen lukt niet')};}}
function erStyle(){const s=Object.assign({},ER_STYLE,store.get('erStyle',{}));if(!store.get('erStyle',null)){s.size=store.get('erSize',22);s.font=store.get('erFont','serif');}Object.keys(ER_VALUES).forEach(k=>{if(!ER_VALUES[k].includes(s[k]))s[k]=ER_STYLE[k];});return s;}
function erStatus(text){const e=$('#er-status');e.textContent=text||'';e.hidden=!text;}
function enterEreader(){
  const ready=typeof Android.ereaderList==='function';$('#er-add').disabled=erBusy||!ready;
  if(!ready){erStatus(t('Installeer de nieuwste APK om de e-reader te gebruiken'));return;}
  const r=erData(Android.ereaderList());if(r.error){erStatus(r.error);return;}
  erBooks=r.books||[];$('#er-sort').value=store.get('erSort','recent');erRender();
}
function erLength(b,i){return Math.max(1,Number((b.lengths||[])[i])||1);}
function erFraction(b,offset){
  if(b.finished)return 1;const at=b.reading&&b.linear&&b.linear[b.chapter]===false?b.reading:b;const lengths=b.chapters.map((_,i)=>erLinear(b,i)?erLength(b,i):0),total=lengths.reduce((a,n)=>a+n,0)||1;
  const inside=offset==null||at!==b?(b.lengths&&at.anchor>=0?at.anchor/erLength(b,at.chapter||0):(at.offset||0)):offset;
  return Math.max(0,Math.min(1,(lengths.slice(0,at.chapter||0).reduce((a,n)=>a+n,0)+lengths[at.chapter||0]*inside)/total));
}
function erPercent(b){return Math.round(erFraction(b)*100);}
function erChapterName(b,i){return(b.chapters||[])[i]||t('Hoofdstuk {n}',{n:fmtNum(i+1)});}
function erMeter(percent){return'<span class="er-meter" aria-hidden="true"><i style="width:'+Math.max(0,Math.min(100,percent))+'%"></i></span>';}
function erCover(b){
  let h=0;for(const c of b.id)h=(h+c.charCodeAt(0))%3;
  return'<span class="er-cover" aria-hidden="true" data-spine="'+h+'"><span class="er-cover-symbol" aria-hidden="true">'+esc((b.title||'B').charAt(0).toUpperCase())+'</span><span><span class="er-cover-title" translate="no">'+esc(b.title)+'</span><span class="er-cover-author" translate="no">'+esc(b.author||'')+'</span></span>'+(b.cover?'<img data-er-cover="'+esc(b.id)+'" alt="" loading="lazy" hidden>':'')+'</span>';
}
function erLoadCovers(){
  const generation=++erCoverGeneration;
  const images=[...document.querySelectorAll('#s-ereader img[data-er-cover]')].filter(img=>{const r=img.parentElement.getBoundingClientRect();return !img.src && r.bottom>-300 && r.top<innerHeight+300;});
  const next=()=>{
    if(generation!==erCoverGeneration)return;const img=images.shift();if(!img)return;const id=img.dataset.erCover;
    if(!(id in erCoverCache)&&typeof Android.ereaderCover==='function'){
      const src=Android.ereaderCover(id);erCoverCache[id]=/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(src)?src:'';
      if(Object.keys(erCoverCache).length>40)delete erCoverCache[Object.keys(erCoverCache)[0]];
    }
    if(erCoverCache[id]){img.src=erCoverCache[id];img.hidden=false;img.onerror=()=>{img.hidden=true;};}setTimeout(next,15);
  };next();
}
function erRender(){
  const q=$('#er-search').value.trim().toLowerCase(),filter=store.get('erFilter','all'),sort=store.get('erSort','recent'),listMode=store.get('erLayout','grid')==='list';
  const recent=erBooks.filter(b=>!b.finished&&(b.anchor>0||b.offset>0||b.chapter>0)).sort((a,b)=>(b.last||0)-(a.last||0))[0];
  $('#er-continue').hidden=!recent;
  if(recent)$('#er-continue').innerHTML='<button class="er-continue" onclick="erOpen('+jsq(recent.id)+')">'+erCover(recent)+'<span class="er-continue-info"><span class="er-eyebrow">'+esc(t('Verder waar je was'))+'</span><b translate="no">'+esc(recent.title)+'</b><small translate="no">'+esc(recent.author||'')+'</small>'+erMeter(erPercent(recent))+'<span class="er-continue-label">'+esc(t('Verder lezen'))+' '+uiIcon('right')+'</span></span></button>';
  $('#er-count').textContent=tn(erBooks.length,'{n} boek','{n} boeken',{n:fmtNum(erBooks.length)});
  $('#er-layout').setAttribute('aria-pressed',String(listMode));
  document.querySelectorAll('#er-filters button').forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.filter===filter)));
  const list=erBooks.filter(b=>[b.title,b.author].join(' ').toLowerCase().includes(q)&&(filter==='all'||filter==='favorite'&&b.favorite||filter==='finished'&&b.finished||filter==='reading'&&!b.finished&&(b.anchor>0||b.offset>0||b.chapter>0)));
  const coll=(a,b)=>String(a||'').localeCompare(String(b||''),regioLocale(),{sensitivity:'base'});
  list.sort(sort==='title'?(a,b)=>coll(a.title,b.title):sort==='author'?(a,b)=>coll(a.author,b.author)||coll(a.title,b.title):(a,b)=>(b.last||0)-(a.last||0));
  if(!list.length){$('#er-list').innerHTML='<div class="er-empty">'+uiIcon('document')+'<h3>'+esc(t(erBooks.length?'Geen boeken bij deze keuze':'Je volgende verhaal begint hier'))+'</h3><p>'+esc(t(erBooks.length?'Probeer een andere zoekopdracht of een ander filter.':'Nog geen boeken. Voeg je eerste boek toe.'))+'</p></div>';erLoadCovers();return;}
  $('#er-list').innerHTML='<div class="er-shelf">'+list.map(b=>'<article class="er-card"><button class="strow er-book-open" onclick="erOpen('+jsq(b.id)+')">'+erCover(b)+'<span class="er-book-info"><b translate="no">'+esc(b.title)+'</b><small translate="no">'+esc(b.author||t('Onbekende auteur'))+'</small>'+erMeter(erPercent(b))+'<span class="er-book-state">'+esc(b.finished?t('Uitgelezen'):erPercent(b)>0?t('{n}% gelezen',{n:fmtNum(erPercent(b))}):t('Nog te lezen'))+(b.format?' · '+esc(b.format):'')+'</span></span></button><button class="er-favorite" data-icon="star" aria-label="'+esc(t(b.favorite?'Uit favorieten halen':'Als favoriet markeren'))+'" aria-pressed="'+!!b.favorite+'" onclick="erFavorite('+jsq(b.id)+')"></button></article>').join('')+'</div>';$('#er-list .er-shelf').classList.toggle('er-list',listMode);erLoadCovers();
}
function erFilter(value){store.set('erFilter',value);erRender();}
function erSort(value){store.set('erSort',value);erRender();}
function erLayout(){store.set('erLayout',store.get('erLayout','grid')==='list'?'grid':'list');erRender();}
function erPick(){if(erBusy||typeof Android.ereaderPick!=='function')return;erBusy=true;$('#er-add').disabled=true;erStatus(t('Boek toevoegen…'));Android.ereaderPick();}
window.onEreaderImport=function(r){const upgrading=erUpgrading,opened=erBook&&erBook.id;erUpgrading=false;erBusy=false;$('#er-add').disabled=false;erStatus(r.error||'');if(r.error||r.cancelled){if(r.error&&current!=='ereader')toast(r.error);return;}if(r.book)erAdopt(r.book);if(upgrading&&r.book&&r.book.id===opened){erOpen(opened,true);toast(t('Afbeeldingen en opmaak hersteld'));}else if(current==='ereader'){enterEreader();if(r.book)erOpen(r.book.id);}else toast(t('Boek toegevoegd aan de e-reader'));};
function erAdopt(b){if(b.error){toast(b.error);return false;}const i=erBooks.findIndex(x=>x.id===b.id);if(i>=0)erBooks[i]=b;else erBooks.push(b);if(erBook&&erBook.id===b.id)erBook=b;return true;}
function erUpdate(id,patch){if(typeof Android.ereaderUpdate!=='function'){toast(t('Installeer de nieuwste APK om alle leesfuncties te gebruiken'));return false;}return erAdopt(erData(Android.ereaderUpdate(id,JSON.stringify(patch))));}
function erFavorite(id){const b=erBooks.find(x=>x.id===id);if(b&&erUpdate(id,{favorite:!b.favorite})){if(current==='ereader')erRender();}}
function erOpen(id,skipSave){
  if(!skipSave)erSave();let b=erBooks.find(x=>x.id===id);if(!b)return;
  if(typeof Android.ereaderDetails==='function'){b=erData(Android.ereaderDetails(id));if(b.error){toast(b.error);return;}erAdopt(b);}
  const index=Math.max(0,Math.min(b.chapters.length-1,b.chapter||0)),r=erData(Android.ereaderChapter(id,index));if(r.error){toast(r.error);return;}
  erBook=b;erIndex=index;erHit=null;erText=r.text||'';erDoc=r;erLinks=[];erLinkButton();erUpgradeBanner();$('#er-title').textContent=b.title;$('#er-author').textContent=b.author||'';
  show('erbook');erDisplay(b.offset||0,b.anchor);
}
function erDisplay(offset,anchor){
  erRestoring=true;erPendingLocation=null;erSelection=null;$('#er-selection').hidden=true;erApplyStyle();erPaint();$('#er-chapter-name').textContent=erChapterName(erBook,erIndex);
  $('#er-text').setAttribute('aria-label',t('Boektekst'));
  if(anchor>=0&&erText.length)erRestoreAnchor(anchor);else erRestoreLocation({anchor:-1,offset:offset||0});
  erRestoring=false;erCurrentAnchor=erAnchor();erCurrentOffset=erPendingLocation?erPendingLocation.offset:erOffset();erProgress();erSave();erQueueImages();
}
function erPaint(){
  const ranges=(erBook.annotations||[]).filter(x=>x.kind==='highlight'&&x.chapter===erIndex&&x.start>=0&&x.end>x.start&&x.end<=erText.length);
  const points=new Set([0,erText.length]);ranges.forEach(x=>{points.add(x.start);points.add(x.end);});
  if(erHit&&erHit.chapter===erIndex){points.add(Math.max(0,Math.min(erText.length,erHit.start)));points.add(Math.max(0,Math.min(erText.length,erHit.end)));}
  const sorted=[...points].sort((a,b)=>a-b);if(erDoc&&erDoc.content){erRichPaint(ranges,sorted);return;}erCancelImages();$('#er-content').classList.remove('er-rich');$('#er-content').removeAttribute('lang');let html='';
  for(let i=0;i<sorted.length-1;i++){const start=sorted[i],end=sorted[i+1],mark=ranges.find(x=>x.start<=start&&x.end>=end),match=erHit&&erHit.chapter===erIndex&&erHit.start<=start&&erHit.end>=end;
    const text=esc(erText.slice(start,end));html+=mark||match?'<mark'+(mark?' data-er-ann="'+esc(mark.id)+'"':'')+(match?' class="er-match"':'')+'>'+text+'</mark>':text;
  }
  $('#er-content').innerHTML=html;erNodes=[];const walk=document.createTreeWalker($('#er-content'),NodeFilter.SHOW_TEXT);let n,pos=0;
  while((n=walk.nextNode())){erNodes.push({node:n,start:pos,end:pos+n.length});pos+=n.length;}
}
function erPoint(pos){pos=Math.max(0,Math.min(erText.length,pos));const n=erNodes.find(x=>pos<=x.end);return n?{node:n.node,offset:Math.max(0,pos-n.start)}:null;}
function erRect(pos){const p=erPoint(pos);if(!p)return null;const r=document.createRange();r.setStart(p.node,p.offset);r.collapse(true);const a=r.getClientRects();if(a.length&&a[0].height)return a[0];if(p.offset<p.node.length){r.setEnd(p.node,p.offset+1);return r.getBoundingClientRect();}return null;}
function erAnchor(){
  const e=$('#er-text');if(!erText||!e.clientHeight||e.scrollTop<2)return 0;
  const top=e.getBoundingClientRect().top+(parseFloat(getComputedStyle(e).paddingTop)||0)+1;let lo=0,hi=Math.max(0,erText.length-1);
  while(lo<hi){const mid=Math.floor((lo+hi)/2),rect=erRect(mid);if(!rect||rect.bottom<top)lo=mid+1;else hi=mid;}
  return lo;
}
function erRestoreAnchor(pos){
  const e=$('#er-text');pos=Math.max(0,Math.min(Math.max(0,erText.length-1),Number(pos)||0));
  e.scrollTop=0;const rect=erRect(pos);if(rect)e.scrollTop=Math.max(0,rect.top-e.getBoundingClientRect().top-parseFloat(getComputedStyle(e).paddingTop));
  erCurrentAnchor=pos;
}
function erChapter(index,anchor){
  if(!erBook||index<0||index>=erBook.chapters.length)return false;
  const r=erData(Android.ereaderChapter(erBook.id,index));if(r.error){toast(r.error);return false;}
  erSave();erIndex=index;erText=r.text||'';erDoc=r;erSelection=null;if(!erHit||erHit.chapter!==index)erHit=null;
  if(current!=='erbook')show('erbook');erDisplay(0,anchor==null?0:anchor);return true;
}
function erOffset(){const e=$('#er-text');return e.scrollHeight>e.clientHeight?e.scrollTop/(e.scrollHeight-e.clientHeight):0;}
function erStep(){const e=$('#er-text'),line=parseFloat(getComputedStyle($('#er-content')).lineHeight)||36;return Math.max(line,Math.floor((e.clientHeight-32)/line)*line);}
function erTurn(direction){
  if(!erBook)return;erPendingLocation=null;const e=$('#er-text'),max=Math.max(0,e.scrollHeight-e.clientHeight);
  if(direction>0&&e.scrollTop>=max-2){const next=erNeighbor(1);if(next>=0)erChapter(next);return;}
  if(direction<0&&e.scrollTop<2){const prev=erNeighbor(-1);if(prev>=0&&erChapter(prev)){$('#er-text').scrollTop=$('#er-text').scrollHeight;erScroll();}return;}
  e.scrollTop=Math.max(0,Math.min(max,e.scrollTop+direction*erStep()));erProgress();erScroll();
}
function erProgress(){
  if(!erBook)return;const e=$('#er-text'),at=erAnchor(),fraction=erFraction(Object.assign({},erBook,{chapter:erIndex,finished:false}),erText.length?at/erText.length:erOffset()),percent=Math.round(fraction*100);
  const order=erOrder(erBook);$('#er-progress').textContent=erLinear(erBook,erIndex)?t('Hoofdstuk {n} van {totaal} · {procent}%',{n:fmtNum(order.indexOf(erIndex)+1),totaal:fmtNum(order.length),procent:fmtNum(percent)}):t('Bijlage · {n}% gelezen',{n:fmtNum(percent)});
  const minutes=Math.max(1,Math.ceil((erBook.words||0)*(1-fraction)/220));$('#er-time').textContent=erBook.words?t('Nog circa {n} min',{n:fmtNum(minutes)}):'';
  $('#er-seek').value=String(Math.round(fraction*1000));$('#er-seek').setAttribute('aria-valuetext',t('{n}% gelezen',{n:fmtNum(percent)}));
  const max=Math.max(0,e.scrollHeight-e.clientHeight),pages=Math.max(1,Math.ceil(max/erStep())+1),page=e.scrollTop>=max-2?pages:Math.min(pages,Math.round(e.scrollTop/erStep())+1);
  $('#er-pages').textContent=t('Pagina {n} van {totaal}',{n:fmtNum(page),totaal:fmtNum(pages)});
  $('#er-prev').disabled=erNeighbor(-1)<0&&e.scrollTop<2;$('#er-next').disabled=erNeighbor(1)<0&&e.scrollTop>=max-2;
  $('#er-finished').hidden=erIndex!==order[order.length-1]||e.scrollTop<max-2||!!erBook.finished;
  const saved=(erBook.annotations||[]).some(x=>x.kind==='bookmark'&&x.chapter===erIndex&&(erText.length?Math.abs(x.start-at)<100:Math.abs((x.offset||0)-erOffset())<.05));
  $('#er-bookmark').setAttribute('aria-pressed',String(saved));$('#er-bookmark').setAttribute('aria-label',t(saved?'Bladwijzer verwijderen':'Bladwijzer bewaren'));
}
function erScroll(){erQueueImages();if(!erBook||current!=='erbook'||erRestoring)return;clearTimeout(erTimer);erTimer=setTimeout(()=>{erCurrentAnchor=erAnchor();erProgress();erSave();},160);}
function erSave(){
  clearTimeout(erTimer);if(!erBook||current!=='erbook'||erRestoring||erUpgrading)return;
  const offset=erPendingLocation?erPendingLocation.offset:erOffset(),anchor=erText.length?erAnchor():-1;let error;
  if(typeof Android.ereaderReadPosition==='function')error=Android.ereaderReadPosition(erBook.id,erIndex,offset,anchor);else error=Android.ereaderPosition(erBook.id,erIndex,offset);
  if(error){toast(error);return;}Object.assign(erBook,{chapter:erIndex,offset,anchor,last:Date.now()});erCurrentAnchor=anchor;erCurrentOffset=offset;if(erLinear(erBook,erIndex))erBook.reading={chapter:erIndex,offset,anchor};
}
function erLeave(next){
  erSave();erImageClose();erFocus(false);erSelection=null;$('#er-selection').hidden=true;
  if(!ER_SCREENS.includes(next)){erPendingLocation=null;erBook=null;erText='';erDoc=null;erLinks=[];erLinkButton();erCancelImages();erNodes=[];$('#er-content').textContent='';erCancelSearch();}
}
function erResume(){if(!erBook)return;erApplyStyle();erRestoreLocation({anchor:erCurrentAnchor,offset:erCurrentOffset});erProgress();erQueueImages();}
function erBrowse(tab){erTab=tab;show('ercontents');}
function erFocus(on){const position=erBook&&current==='erbook'?erLocation():{anchor:erCurrentAnchor,offset:erCurrentOffset};document.body.classList.toggle('er-focus',!!on);$('#er-focus-exit').hidden=!on;if(erBook&&current==='erbook'){erRestoreLocation(position);erProgress();}}
function erApplyStyle(){
  const s=erStyle(),font=s.font==='serif'?'Georgia, "Noto Serif", serif':s.font==='sans-serif'?'system-ui, sans-serif':'monospace';
  for(const el of [$('#s-erbook'),$('#s-erset')]){el.dataset.erTheme=s.theme;el.dataset.erMode=s.mode;el.style.setProperty('--er-size',s.size+'px');el.style.setProperty('--er-font',font);el.style.setProperty('--er-line',s.line);el.style.setProperty('--er-margin',s.margin+'px');el.style.setProperty('--er-align',s.align);}
  if(current==='erbook')bars();
}
function erSettings(){erApplyStyle();const s=erStyle();for(const k of Object.keys(ER_STYLE))$('#er-'+k).value=String(s[k]);$('#er-sample-text').textContent=(erText||t('Open een boek om hier je eigen tekst te bekijken.')).slice(0,350);}
function erPreference(key,value){
  if(key==='size')value=Number(value);if(!ER_VALUES[key]||!ER_VALUES[key].includes(value))return;
  const anchor=current==='erbook'?erAnchor():erCurrentAnchor,s=erStyle();s[key]=value;store.set('erStyle',s);
  if(key==='size')store.set('erSize',value);if(key==='font')store.set('erFont',value);
  erApplyStyle();erCurrentAnchor=anchor;if(current==='erbook'){erRestoreAnchor(anchor);erProgress();erSave();}if(current==='erset')erSettings();
}
function erSize(value){erPreference('size',value);}
function erFont(value){erPreference('font',value);}
function erResetStyle(){store.set('erStyle',Object.assign({},ER_STYLE));erSettings();}
function erSeek(value){
  if(!erBook)return;const order=erOrder(erBook);if(!order.length)return;
  let position=order.reduce((a,i)=>a+erLength(erBook,i),0)*Math.max(0,Math.min(1000,value))/1000,n=0;
  while(n<order.length-1&&position>=erLength(erBook,order[n])){position-=erLength(erBook,order[n]);n++;}
  const index=order[n],ratio=position/erLength(erBook,index);if(erChapter(index)){erRestoreLocation({anchor:erText.length?Math.floor(erText.length*ratio):-1,offset:ratio});erSave();erProgress();erQueueImages();}
}
function erContents(tab){
  if(tab)erTab=tab;if(!erBook){$('#er-browse-list').textContent=t('Open eerst een boek.');return;}
  document.querySelectorAll('#er-tabs button').forEach(b=>b.setAttribute('aria-selected',String(b.dataset.tab===erTab)));
  $('#er-find-wrap').hidden=erTab!=='search';$('#er-book-summary').hidden=erTab==='search';
  $('#er-book-summary').innerHTML='<div class="er-book-summary"><b translate="no">'+esc(erBook.title)+'</b><small>'+esc(t('{n} hoofdstukken · {woorden} woorden',{n:fmtNum(erBook.chapters.length),woorden:fmtNum(erBook.words||0)}))+'</small></div>';
  const box=$('#er-browse-list');
  if(erTab==='chapters'){const toc=erBook.toc&&erBook.toc.length?erBook.toc:erBook.chapters.map((label,chapter)=>({label,chapter}));box.replaceChildren();toc.forEach(entry=>{const button=document.createElement('button');button.className='er-chapter-row';button.style.paddingInlineStart=(12+Math.min(8,entry.depth||0)*16)+'px';button.setAttribute('aria-current',String(entry.chapter===erIndex));const label=document.createElement('b');label.translate=false;label.textContent=entry.label||erChapterName(erBook,entry.chapter);button.append(label);button.addEventListener('click',()=>{if(erChapter(entry.chapter,entry.start||0))erFragment(entry.fragment,entry.start);});box.append(button);});}
  else if(erTab==='marks')erMarks();
  else{box.replaceChildren();$('#er-find-status').textContent=t('Typ minimaal twee tekens.');if($('#er-find').value.trim().length>=2)erFind();}
}
function erAnnotation(p){if(!erBook||typeof Android.ereaderAnnotation!=='function'){toast(t('Installeer de nieuwste APK om alle leesfuncties te gebruiken'));return false;}return erAdopt(erData(Android.ereaderAnnotation(erBook.id,JSON.stringify(p))));}
function erBookmark(){
  if(!erBook)return;const start=erAnchor(),old=(erBook.annotations||[]).find(x=>x.kind==='bookmark'&&x.chapter===erIndex&&(erText.length?Math.abs(x.start-start)<100:Math.abs((x.offset||0)-erOffset())<.05));
  if(erAnnotation(old?{id:old.id,remove:true}:{kind:'bookmark',chapter:erIndex,start,end:start,offset:erOffset()})){erProgress();toast(t(old?'Bladwijzer verwijderd':'Bladwijzer bewaard'));}
}
function erReadSelection(){
  if(current!=='erbook'||!erBook)return;const selection=window.getSelection(),root=$('#er-content');
  if(!selection||selection.isCollapsed||!selection.rangeCount){$('#er-selection').hidden=true;return;}
  const range=selection.getRangeAt(0);if(!root.contains(range.startContainer)||!root.contains(range.endContainer)){$('#er-selection').hidden=true;return;}
  const start=erBoundary(range.startContainer,range.startOffset,false),end=erBoundary(range.endContainer,range.endOffset,true);
  if(end>start&&end-start<=5000){erSelection={start,end,chapter:erIndex};$('#er-selection').hidden=false;}else $('#er-selection').hidden=true;
}
function erHighlight(withNote){
  const selection=erSelection;if(!erBook||!selection)return;
  const save=note=>{const anchor=erAnchor();if(erAnnotation(Object.assign({kind:'highlight',note},selection))){window.getSelection().removeAllRanges();$('#er-selection').hidden=true;erSelection=null;erPaint();erRestoreAnchor(anchor);erProgress();toast(t('Markering bewaard'));}};
  if(withNote)askInput(t('Notitie bij je markering'),t('Je notitie blijft bij deze tekst in het boek.'),'',t('Bewaren'),save);else save('');
}
function erMarks(){
  const all=(erBook.annotations||[]).slice().sort((a,b)=>a.chapter-b.chapter||a.start-b.start),box=$('#er-browse-list');
  if(!all.length){box.innerHTML=uiEmpty('bookmark',t('Nog geen leeswijzers. Bewaar een bladwijzer of markeer tekst tijdens het lezen.'));return;}
  box.innerHTML=all.map(a=>'<article class="er-note-row"><button class="er-mark-open" onclick="erJumpMark('+jsq(a.id)+')"><b>'+uiIcon(a.kind==='bookmark'?'bookmark':'edit')+' '+esc(t(a.kind==='bookmark'?'Bladwijzer':'Markering'))+'</b><small translate="no">'+esc(erChapterName(erBook,a.chapter))+'</small><p translate="no">'+esc(a.text||'')+'</p></button>'+(a.note?'<p translate="no">'+esc(a.note)+'</p>':'')+'<div class="er-note-actions"><button onclick="erEditMark('+jsq(a.id)+')">'+esc(t('Notitie bewerken'))+'</button><button onclick="erRemoveMark('+jsq(a.id)+')">'+esc(t('Verwijderen'))+'</button></div></article>').join('');
}
function erJumpMark(id){const a=(erBook.annotations||[]).find(x=>x.id===id);if(a){erHit=a.kind==='highlight'?a:null;if(erChapter(a.chapter,a.start)&&!erText.length){erRestoreLocation({anchor:-1,offset:a.offset||0});erSave();erProgress();}}}
function erEditMark(id){const a=(erBook.annotations||[]).find(x=>x.id===id);if(!a)return;askInput(t('Notitie bij je leeswijzer'),'',a.note||'',t('Bewaren'),note=>{if(erAnnotation(Object.assign({},a,{note}))){if(current==='ercontents')erMarks();else{const pos=erAnchor();erPaint();erRestoreAnchor(pos);}}});}
function erRemoveMark(id){askConfirm(t('Leeswijzer verwijderen?'),t('De bladwijzer of markering en de bijbehorende notitie worden verwijderd.'),t('Verwijderen'),()=>{if(erAnnotation({id,remove:true})){if(current==='ercontents')erMarks();else{const pos=erAnchor();erPaint();erRestoreAnchor(pos);erProgress();}}});}
function erCancelSearch(){clearTimeout(erFindTimer);erRequest='';if(typeof Android.ereaderSearchCancel==='function')Android.ereaderSearchCancel();}
function erFindInput(){erCancelSearch();$('#er-browse-list').replaceChildren();$('#er-find-status').textContent=t('Typ minimaal twee tekens.');if($('#er-find').value.trim().length>=2)erFindTimer=setTimeout(erFind,350);}
function erFind(){
  if(!erBook)return;const query=$('#er-find').value.trim();erCancelSearch();if(query.length<2||query.length>80){$('#er-find-status').textContent=t('Typ minimaal twee tekens.');return;}
  if(typeof Android.ereaderSearch!=='function'){$('#er-find-status').textContent=t('Installeer de nieuwste APK om alle leesfuncties te gebruiken');return;}
  erRequest=Date.now()+'-'+Math.random().toString(36).slice(2);$('#er-find-status').textContent=t('Zoeken in het hele boek…');Android.ereaderSearch(erBook.id,query,erRequest);
}
window.onEreaderSearch=function(r){
  if(!erBook||r.id!==erBook.id||r.request!==erRequest||current!=='ercontents'||erTab!=='search')return;
  if(r.error){$('#er-find-status').textContent=r.error;return;}
  const hits=(r.hits||[]).slice(0,100);$('#er-find-status').textContent=(r.hits||[]).length>100?t('De eerste 100 resultaten. Verfijn je zoekopdracht voor meer.'):tn(hits.length,'{n} resultaat','{n} resultaten',{n:fmtNum(hits.length)});
  erFind.hits=hits;$('#er-browse-list').innerHTML=hits.map((h,i)=>'<button class="er-note-row" onclick="erOpenHit('+i+')"><small translate="no">'+esc(erChapterName(erBook,h.chapter))+'</small><p translate="no">'+esc(h.excerpt)+'</p></button>').join('');
};
function erOpenHit(index){const hit=(erFind.hits||[])[index];if(hit){erHit=hit;erChapter(hit.chapter,hit.start);}}
function erFinish(){if(erBook&&erUpdate(erBook.id,{finished:!erBook.finished})){erProgress();toast(t(erBook.finished?'Boek gemarkeerd als uitgelezen':'Boek teruggezet bij nog te lezen'));}}
function erMenu(){
  if(!erBook)return;const id=erBook.id;
  openSheet(erBook.title,t('{n}% gelezen',{n:fmtNum(erPercent(erBook))}),[
    [t('Inhoud en leeswijzers'),()=>erBrowse('marks'),'bookmark'],[t('Leesweergave'),()=>show('erset'),'settings'],
    [t(erBook.favorite?'Uit favorieten halen':'Als favoriet markeren'),()=>erFavorite(id),'star'],
    [t(erBook.finished?'Terug naar nog te lezen':'Als uitgelezen markeren'),erFinish,'check'],
    [t('Titel wijzigen'),()=>askInput(t('Titel van het boek'),'',erBook.title,t('Bewaren'),title=>{if(erUpdate(id,{title}))$('#er-title').textContent=erBook.title;}),'edit'],
    [t('Uit de boekenlijst verwijderen'),()=>askConfirm(t('Boek verwijderen?'),t('De kopie in Tools, de leespositie en alle leeswijzers worden verwijderd. Je oorspronkelijke bestand blijft staan.'),t('Verwijderen'),()=>{const error=Android.ereaderDelete(id);if(error){toast(error);return;}erBook=null;erText='';$('#er-content').replaceChildren();show('ereader');toast(t('Boek verwijderd'));}),'trash']
  ]);
}
$('#er-content').addEventListener('click',event=>{const mark=event.target.closest('[data-er-ann]');if(mark){const id=mark.dataset.erAnn;openSheet(t('Markering'),'',[[t('Notitie bewerken'),()=>erEditMark(id),'edit'],[t('Verwijderen'),()=>erRemoveMark(id),'trash']]);}});
document.addEventListener('selectionchange',()=>{clearTimeout(erReadSelection.timer);erReadSelection.timer=setTimeout(erReadSelection,80);});
let erTouch=null;
$('#er-text').addEventListener('touchstart',e=>{if(e.touches.length===1)erTouch={x:e.touches[0].clientX,y:e.touches[0].clientY};},{passive:true});
$('#er-text').addEventListener('touchend',e=>{if(!erTouch||!e.changedTouches.length)return;const d=e.changedTouches[0],dx=d.clientX-erTouch.x,dy=d.clientY-erTouch.y;erTouch=null;if(erStyle().mode==='pages'&&Math.abs(dx)>60&&Math.abs(dx)>Math.abs(dy)*1.5&&window.getSelection().isCollapsed)erTurn(dx<0?1:-1);},{passive:true});
document.addEventListener('keydown',e=>{if(current!=='erbook'||/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)||document.querySelector('#modal.on,#sheet.on,#er-image.on'))return;if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='f'){e.preventDefault();erBrowse('search');return;}if(['ArrowRight','PageDown',' '].includes(e.key)){e.preventDefault();erTurn(1);}else if(['ArrowLeft','PageUp'].includes(e.key)){e.preventDefault();erTurn(-1);}else if(e.key==='Escape')erFocus(false);});
window.addEventListener('resize',()=>{if(current==='erbook'&&erBook){erRestoreLocation({anchor:erCurrentAnchor,offset:erCurrentOffset});erProgress();erQueueImages();}});
window.addEventListener('pagehide',erSave);document.addEventListener('visibilitychange',()=>{if(document.hidden)erSave();});
(function(){const previous=window.onPauseApp;window.onPauseApp=function(){erSave();if(previous)previous();};})();

window.addEventListener('scroll',()=>{if(current==='ereader'){clearTimeout(erLoadCovers.timer);erLoadCovers.timer=setTimeout(erLoadCovers,100);}},{passive:true});
