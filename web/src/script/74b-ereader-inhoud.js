/* Een EPUB wordt als een gesloten boom met tekst, typografie en lokale mediaverwijzingen opgebouwd. */
let erDoc=null,erImages=new Map(),erImageRequests=new Map(),erImageSerial=0,erImageEpoch=0,erLinks=[],erUpgrading=false,erCurrentOffset=0,erPendingLocation=null,erPendingFragment='';
const ER_TAGS=new Set('div span p h1 h2 h3 h4 h5 h6 strong b em i u s del ins small sup sub blockquote q pre code kbd samp var ul ol li dl dt dd table thead tbody tfoot tr th td caption figure figcaption hr br a ruby rt rp abbr cite time aside section article header footer main nav address details summary'.split(' '));
const ER_MATH=new Set('math mrow mi mn mo mtext mspace ms msqrt mroot mfrac msup msub msubsup munder mover munderover mfenced menclose mtable mtr mtd mstyle mphantom mpadded semantics annotation'.split(' '));
function erLinear(b,i){return !b.linear||b.linear[i]!==false;}
function erOrder(b){return b.chapters.map((_,i)=>i).filter(i=>erLinear(b,i));}
function erNeighbor(direction){for(let i=erIndex+direction;i>=0&&i<erBook.chapters.length;i+=direction)if(erLinear(erBook,i))return i;return -1;}
function erLocation(){return erPendingLocation||{chapter:erIndex,anchor:erText.length?erAnchor():-1,offset:erOffset()};}
function erRestoreLocation(p){if(!erText.length||p.anchor<0)erPendingLocation={chapter:erIndex,anchor:-1,offset:p.offset||0};if(p.anchor>0&&erText.length)erRestoreAnchor(p.anchor);else $('#er-text').scrollTop=Math.max(0,Math.min(1,p.offset||0))*Math.max(0,$('#er-text').scrollHeight-$('#er-text').clientHeight);erCurrentAnchor=erPendingLocation?erPendingLocation.anchor:erAnchor();erCurrentOffset=erPendingLocation?erPendingLocation.offset:erOffset();}
function erCancelImages(){erImageEpoch++;erImageRequests.clear();erImages.clear();if(typeof Android.ereaderImagesCancel==='function')Android.ereaderImagesCancel();}
function erRichPaint(ranges,points){
  erCancelImages();erNodes=[];const box=$('#er-content');box.replaceChildren();box.classList.add('er-rich');
  const text=(leaf,parent)=>{
    if(!Number.isInteger(leaf.s)||!Number.isInteger(leaf.e)||leaf.s<0||leaf.e>erText.length||leaf.e<leaf.s)return;
    const cuts=[leaf.s,...points.filter(p=>p>leaf.s&&p<leaf.e),leaf.e];
    for(let i=0;i<cuts.length-1;i++){
      const start=cuts[i],end=cuts[i+1];if(start===end)continue;
      const annotation=ranges.find(x=>x.start<=start&&x.end>=end),hit=erHit&&erHit.chapter===erIndex&&erHit.start<=start&&erHit.end>=end;
      let into=parent;if(annotation||hit){into=document.createElement('mark');if(annotation)into.dataset.erAnn=annotation.id;if(hit)into.classList.add('er-match');parent.append(into);}
      const node=document.createTextNode(erText.slice(start,end));into.append(node);erNodes.push({node,start,end});
    }
  };
  let count=0;
  const visit=(n,parent,depth)=>{
    if(!n||++count>150000||depth>120)return;
    if(typeof n.t==='string'){text(n,parent);return;}
    if(n.tag==='img'){erMediaNode(n,parent);return;}
    const math=n.math&&ER_MATH.has(n.tag),tag=math||ER_TAGS.has(n.tag)?n.tag:'span';
    const el=math?document.createElementNS('http://www.w3.org/1998/Math/MathML',tag):document.createElement(tag);
    if(n.id)el.dataset.erId=String(n.id).slice(0,300);
    if(Number.isInteger(n.at))el.dataset.erAt=String(n.at);
    for(const [k,v] of Object.entries(n.a||{})){
      if(k==='lang'&&/^[A-Za-z0-9-]{2,64}$/.test(v)||k==='dir'&&/^(ltr|rtl|auto)$/.test(v)||k==='role'&&/^doc-(noteref|footnote)$/.test(v)||['start','value','colspan','rowspan'].includes(k)&&/^-?\d{1,6}$/.test(v)||k==='scope'&&/^(row|col|rowgroup|colgroup)$/.test(v)||math&&['display','displaystyle','stretchy','accent','accentunder','mathvariant','open','close','separators'].includes(k)&&String(v).length<80)el.setAttribute(k,v);
    }
    const allowed={textAlign:/^(start|end|left|right|center|justify)$/,fontWeight:/^(normal|bold|[1-9]00)$/,fontStyle:/^(normal|italic|oblique)$/,textDecoration:/^(none|underline|line-through)$/,whiteSpace:/^(normal|pre|pre-wrap|pre-line)$/,textTransform:/^(none|uppercase|lowercase|capitalize)$/,verticalAlign:/^(baseline|sub|super|middle|top|bottom)$/,fontVariant:/^(normal|small-caps)$/,fontSize:/^(?:[0-9.]+)em$/,marginTop:/^[0-9.]+em$/,marginBottom:/^[0-9.]+em$/,marginLeft:/^[0-9.]+em$/,marginRight:/^[0-9.]+em$/,textIndent:/^-?[0-9.]+em$/};
    for(const [k,v] of Object.entries(n.style||{}))if(allowed[k]&&allowed[k].test(v))el.style[k]=v;
    if(tag==='details')el.open=true;
    if(tag==='a'&&n.target){el.href='#';el.addEventListener('click',e=>{e.preventDefault();e.stopPropagation();erFollow(n.target);});}
    parent.append(el);for(const child of n.c||[])visit(child,el,depth+1);
    if(n.mediaOnly&&el.querySelector('.er-media'))erMediaStyle(el,n.style);
    if(tag==='table'){const wrap=document.createElement('div');wrap.className='er-table-scroll';wrap.tabIndex=0;el.replaceWith(wrap);wrap.append(el);}
  };
  visit(erDoc.content,box,0);erNodes.sort((a,b)=>a.start-b.start);
  if(/^[A-Za-z0-9-]{2,64}$/.test(erBook.language||''))box.lang=erBook.language;else box.removeAttribute('lang');
  erQueueImages();
}
function erMediaNode(n,parent){
  const button=document.createElement('button');button.type='button';button.className='er-media';button.dataset.erAt=String(n.at||0);if(n.id)button.dataset.erId=n.id;
  erMediaStyle(button,n.style);
  const alt=String(n.alt||'').slice(0,500);button.setAttribute('aria-label',alt?t('Afbeelding vergroten: {naam}',{naam:alt}):t('Afbeelding vergroten'));
  const img=document.createElement('img');img.alt=alt;img.hidden=true;img.draggable=false;
  const status=document.createElement('span');status.className='er-media-status';status.textContent=t('Afbeelding laden…');
  button.append(img,status);parent.append(button);
  const asset=Number(n.asset),entry={button,img,status,asset,loaded:false,pending:false,epoch:erImageEpoch};erImages.set(button,entry);
  button.addEventListener('click',()=>{if(entry.loaded)erImageOpen(img.src,alt);else if(!entry.pending){entry.failed=false;erQueueImages();}});
  if(!Number.isInteger(asset)||asset<0){entry.failed=true;status.textContent=t('Deze afbeelding ontbreekt in het boek');}
}
function erQueueImages(){
  if(!erBook||current!=='erbook'||typeof Android.ereaderImage!=='function')return;
  const root=$('#er-text').getBoundingClientRect();
  for(const entry of erImages.values()){
    if(erImageRequests.size>=2)break;
    if(entry.loaded||entry.pending||entry.failed)continue;
    const rect=entry.button.getBoundingClientRect();if(rect.bottom<root.top-600||rect.top>root.bottom+600)continue;
    const request=erImageEpoch+'-'+(++erImageSerial);entry.pending=true;erImageRequests.set(request,entry);Android.ereaderImage(erBook.id,entry.asset,request);
  }
}
window.onEreaderImage=function(r){
  const entry=erImageRequests.get(r.request);if(!entry||!erBook||r.id!==erBook.id||r.asset!==entry.asset||entry.epoch!==erImageEpoch)return;
  erImageRequests.delete(r.request);entry.pending=false;
  if(r.error||!/^data:image\/(png|jpeg|gif|webp|svg\+xml);base64,[A-Za-z0-9+/=]+$/.test(r.src||'')){entry.failed=true;entry.status.textContent=r.error||t('Deze afbeelding kan niet worden weergegeven');erQueueImages();return;}
  const position=current==='erbook'?erLocation():null;
  if(r.width>0&&r.width<100000)entry.button.style.maxWidth=Math.min(720,r.width)+'px';
  if(r.width>0&&r.height>0&&r.width<100000&&r.height<100000)entry.button.style.aspectRatio=r.width+'/'+r.height;
  entry.img.onload=()=>{if(entry.epoch!==erImageEpoch)return;entry.loaded=true;entry.button.classList.add('er-media-ready');entry.img.hidden=false;entry.status.hidden=true;if(current==='erbook')erProgress();erQueueImages();};
  entry.img.onerror=()=>{entry.failed=true;entry.status.textContent=t('Deze afbeelding kan niet worden weergegeven');};
  entry.img.src=r.src;if(current==='erbook'&&erPendingFragment)erPlaceFragment();else if(position)erRestoreLocation(position);erQueueImages();
};
function erMediaStyle(el,style){
  if(!style)return;
  if(/^(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)(px|em|%)$/.test(style.width||''))el.style.width=style.width;
  if(/^(left|right|none)$/.test(style.cssFloat||''))el.style.cssFloat=style.cssFloat;
  if(/^(left|right|both|none)$/.test(style.clear||''))el.style.clear=style.clear;
  el.classList.add('er-media-container');
}
function erUpgrade(){
  if(erBusy||!erBook)return;erSave();erUpgrading=true;erPick();
}
function erUpgradeBanner(){const old=erBook&&erBook.format==='EPUB'&&!(erBook.edition>=3);$('#er-upgrade').hidden=!old;}
function erLinkButton(){$('#er-link-back').hidden=!erLinks.length;}
function erFollow(target){
  if(!erBook)return;
  if(Number.isInteger(target.chapter)){
    const previous=erLocation();if(erChapter(target.chapter,target.start||0)){erLinks.push(previous);if(erLinks.length>50)erLinks.shift();erLinkButton();erFragment(target.fragment,target.start);}
  }else if(Number.isInteger(target.asset)){
    const image=[...erImages.values()].find(e=>e.asset===target.asset&&e.loaded);if(image)erImageOpen(image.img.src,image.img.alt);
    else toast(t('Open de afbeelding bij de tekst om deze te vergroten'));
  }else if(target.url){
    try{const url=new URL(target.url);if(!['http:','https:'].includes(url.protocol)||!url.hostname)return;askConfirm(t('Website openen?'),url.href,t('Openen'),()=>Android.openUrl(url.href));}catch(e){toast(t('Deze verwijzing kan niet worden geopend'));}
  }else toast(t('Deze verwijzing staat niet in het boek'));
}
function erFragment(fragment,start){
  erPendingLocation=null;erPendingFragment=fragment||'';
  if(fragment&&erPlaceFragment()){erScroll();return;}
  if(start!=null)erRestoreAnchor(start);erProgress();
}
function erPlaceFragment(){
  const el=[...$('#er-content').querySelectorAll('[data-er-id]')].find(e=>e.dataset.erId===erPendingFragment);if(!el)return false;
  const box=$('#er-text');box.scrollTop+=el.getBoundingClientRect().top-box.getBoundingClientRect().top-20;
  erPendingLocation=null;if(!erText.length||erAnchor()<0)erPendingLocation={chapter:erIndex,anchor:-1,offset:erOffset()};
  erCurrentAnchor=erPendingLocation?erPendingLocation.anchor:erAnchor();erCurrentOffset=erOffset();return true;
}
function erLinkBack(){const p=erLinks.pop();if(!p)return false;if(!erChapter(p.chapter,p.anchor>=0?p.anchor:undefined)){erLinks.push(p);return false;}erRestoreLocation(p);erSave();erProgress();erLinkButton();return true;}
function erBoundary(node,offset,end){
  const known=erNodes.find(n=>n.node===node);if(known)return Math.max(known.start,Math.min(known.end,known.start+offset));
  const point=document.createRange();point.setStart(node,offset);point.collapse(true);
  for(const n of erNodes){const range=document.createRange();range.selectNodeContents(n.node);if(point.compareBoundaryPoints(Range.START_TO_START,range)<=0)return n.start;}
  return erText.length;
}
let erZoom={scale:1,x:0,y:0},erPointers=new Map(),erGesture=null;
function erImageOpen(src,alt){const el=$('#er-image');$('#er-image-picture').src=src;$('#er-image-picture').alt=alt||t('Afbeelding');$('#er-image-label').textContent=alt||t('Afbeelding');el.classList.add('on');erZoom={scale:1,x:0,y:0};erPointers.clear();erImageTransform();dialogFocus.sync($('#er-image-close'));}
function erImageClose(){$('#er-image').classList.remove('on');$('#er-image-picture').removeAttribute('src');erPointers.clear();erGesture=null;dialogFocus.sync();}
function erImageTransform(){const el=$('#er-image-picture');el.style.transform='translate('+erZoom.x+'px,'+erZoom.y+'px) scale('+erZoom.scale+')';$('#er-image-scale').textContent=fmtNum(Math.round(erZoom.scale*100))+'%';$('#er-image-less').disabled=erZoom.scale<=1;$('#er-image-more').disabled=erZoom.scale>=5;}
function erImageZoom(delta){erZoom.scale=Math.max(1,Math.min(5,erZoom.scale+delta));if(erZoom.scale===1){erZoom.x=0;erZoom.y=0;}erImageTransform();}
const erImageView=$('#er-image-view');
function erImageGesture(){const p=[...erPointers.values()];erGesture={x:erZoom.x,y:erZoom.y,scale:erZoom.scale,px:p.reduce((s,n)=>s+n.x,0)/p.length,py:p.reduce((s,n)=>s+n.y,0)/p.length,d:p.length>1?Math.hypot(p[1].x-p[0].x,p[1].y-p[0].y):0};}
erImageView.addEventListener('pointerdown',e=>{erImageView.setPointerCapture(e.pointerId);erPointers.set(e.pointerId,{x:e.clientX,y:e.clientY});erImageGesture();});
erImageView.addEventListener('pointermove',e=>{if(!erPointers.has(e.pointerId)||!erGesture)return;erPointers.set(e.pointerId,{x:e.clientX,y:e.clientY});const p=[...erPointers.values()];if(p.length>1&&erGesture.d)erZoom.scale=Math.max(1,Math.min(5,erGesture.scale*Math.hypot(p[1].x-p[0].x,p[1].y-p[0].y)/erGesture.d));if(erZoom.scale>1){erZoom.x=erGesture.x+p.reduce((s,n)=>s+n.x,0)/p.length-erGesture.px;erZoom.y=erGesture.y+p.reduce((s,n)=>s+n.y,0)/p.length-erGesture.py;}else{erZoom.x=0;erZoom.y=0;}erImageTransform();});
for(const name of ['pointerup','pointercancel'])erImageView.addEventListener(name,e=>{erPointers.delete(e.pointerId);if(erPointers.size)erImageGesture();else erGesture=null;});
erImageView.addEventListener('dblclick',()=>erImageZoom(erZoom.scale>1?-4:1));
erImageView.addEventListener('wheel',e=>{e.preventDefault();erImageZoom(e.deltaY<0?.25:-.25);},{passive:false});

for(const name of ['wheel','touchstart','pointerdown'])$('#er-text').addEventListener(name,()=>{erPendingLocation=null;erPendingFragment='';},{passive:true});

function erRichAnchor(){
  const box=$('#er-text'),view=box.getBoundingClientRect(),top=view.top+(parseFloat(getComputedStyle(box).paddingTop)||0)+1,range=document.createRange();
  let best=null,distance=Infinity;
  for(const entry of erNodes){
    if(!entry.node.data.trim())continue;range.selectNodeContents(entry.node);const bounds=range.getBoundingClientRect();if(bounds.bottom<top||bounds.top>view.bottom)continue;
    for(const rect of range.getClientRects()){
      if(!rect.height||rect.bottom<=top)continue;const score=Math.max(0,rect.top-top);
      if(score<distance){best=entry;distance=score;}break;
    }
  }
  const onImage=[...erImages.values()].some(({button})=>{const r=button.getBoundingClientRect();return r.top<=top&&r.bottom>top;});
  if(onImage&&distance>4)return -1;
  if(!best)return erCurrentAnchor>=0?erCurrentAnchor:0;
  let lo=0,hi=Math.max(0,best.node.length-1);
  while(lo<hi){const mid=Math.floor((lo+hi)/2);range.setStart(best.node,mid);range.setEnd(best.node,Math.min(best.node.length,mid+1));const rect=range.getBoundingClientRect();if(rect.bottom<=top)lo=mid+1;else hi=mid;}
  return best.start+lo;
}
