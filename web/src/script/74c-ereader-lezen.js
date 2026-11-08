/* E-reader lezen: volle scherm met knoppen die je met een tik in het midden toont, snelle instellingen (helderheid,
 * thema, prettig voor je ogen, tekstgrootte, leesmodus) en voorlezen met de gedeelde lokale stem (75-voorlezen.js). */
const ER_LEZEN={full:true,awake:true,keys:false,bright:-1,brightLast:.5,warm:false,warmLevel:30,warmAuto:false};
let erEntering=false,erWin={talkback:false},erPanelOn='',erUserAt=0;

/* ---------- instellingen voor het lezen ---------- */
function erLezen(){
  const raw=store.get('erLezen',{}),s=Object.assign({},ER_LEZEN,raw&&typeof raw==='object'?raw:{});
  for(const k of ['full','awake','keys','warm','warmAuto'])s[k]=typeof s[k]==='boolean'?s[k]:ER_LEZEN[k];
  s.bright=typeof s.bright==='number'&&s.bright>=.02&&s.bright<=1?s.bright:-1;
  s.brightLast=typeof s.brightLast==='number'&&s.brightLast>=.02&&s.brightLast<=1?s.brightLast:ER_LEZEN.brightLast;
  s.warmLevel=Number.isInteger(s.warmLevel)&&s.warmLevel>=10&&s.warmLevel<=60?s.warmLevel:ER_LEZEN.warmLevel;
  return s;
}
function erLezenSet(key,value){
  if(!(key in ER_LEZEN))return'';const s=erLezen();s[key]=value;if(key==='bright'&&value>=.02)s.brightLast=value;
  store.set('erLezen',Object.assign({},ER_LEZEN,s));
  if(key==='full'&&current==='erbook')erControls(false);
  if(erReading())erWindowApply(); // buiten een boek nooit het venster aanraken (scherm aan van de assistent, balken, helderheid)
  erWarmApply();erQuickRender();erSetRender();return'';
}
function erBrightInput(value){erLezenSet('bright',Math.max(.02,Math.min(1,value/100)));}
function erSizeStep(direction){const size=erStyle().size,i=ER_VALUES.size.indexOf(size),next=ER_VALUES.size[Math.max(0,Math.min(ER_VALUES.size.length-1,(i<0?3:i)+direction))];if(next!==size)erPreference('size',next);erQuickRender();}
function erPct(v){return fmtNum(v/100,{style:'percent'});}
function erSizePct(size){return erPct(Math.round(size/22*100));}

/* ---------- venster: volle scherm, scherm aan, helderheid, volumeknoppen ---------- */
function erReading(){return current==='erbook'&&!!erBook;}
/* Ligt er iets over het boek (paneel, dialoog, menu, afbeelding, app-slot), dan zijn de volumeknoppen weer gewoon voor het volume. */
function erOnTop(){return !!erPanelOn||!!document.querySelector('#modal.on,#sheet.on,#er-image.on,#lockscr.on');}
let erWinLast='{}';
function erWindowApply(force){
  const s=erLezen(),on=erReading();
  if(typeof Android.ereaderWindow==='function'){
    const cfg=JSON.stringify(on?{full:s.full,awake:s.awake,bright:s.bright,keys:s.keys&&!erOnTop()&&!erSpeakBusy()}:{});
    if(cfg===erWinLast&&!force&&on)return;erWinLast=cfg;
    let r={};try{r=JSON.parse(Android.ereaderWindow(cfg)||'{}');}catch(e){}
    erWin.talkback=!!r.talkback;
    try{if(on)sessionStorage.setItem('rt.erWin','1');else sessionStorage.removeItem('rt.erWin');}catch(e){}
  }
  const immersive=on&&s.full&&!erWin.talkback,was=document.body.classList.contains('er-immersive');
  if(immersive!==was&&on&&!erEntering){const pos=erLocation();document.body.classList.toggle('er-immersive',immersive);erRestoreLocation(pos);erProgress();}
  else document.body.classList.toggle('er-immersive',immersive);
  if(!immersive)document.body.classList.remove('er-controls');
}
/* Knoppen over de tekst tonen of verbergen (alleen op het volle scherm; met TalkBack blijven ze altijd staan). */
function erControls(show){
  const on=!!show&&document.body.classList.contains('er-immersive');document.body.classList.toggle('er-controls',on);
  $('#er-text').setAttribute('aria-description',document.body.classList.contains('er-immersive')&&!on?t('Tik in het midden voor de knoppen'):'');
}
/* Bij binnenkomen: erResume zet daarna de leesplek terug, dus hier nog niets meten of herstellen. */
function erLezenEnter(){erEntering=true;try{erSpeakResumeLoc();erWindowApply(true);erControls(false);erWarmApply();erSpeakRender();if(typeof spSpeechRender==='function')spSpeechRender();}finally{erEntering=false;}}
/* Terug in het boek terwijl de motor verder las: naar de plek van de laatste zin */
function erSpeakResumeLoc(){const l=erSpeakLoc;erSpeakLoc=null;if(!l||!erBook||l.book!==erBook.id)return;if(l.chapter!==erIndex){erFollowing=true;try{erChapter(l.chapter,l.offset);}finally{erFollowing=false;}}erCurrentAnchor=l.offset;erCurrentOffset=erText.length?l.offset/erText.length:0;erPendingLocation={chapter:l.chapter,anchor:l.offset,offset:erCurrentOffset};}
function erLezenLeave(){
  erPanel('');document.body.classList.remove('er-controls','er-immersive','er-speaking');erSpeakHighlight(null);$('#er-warm').hidden=true;$('#er-tts').hidden=true;
  setTimeout(()=>{if(typeof spSpeechRender==='function')spSpeechRender();},0); // buiten het boek: de gedeelde voorleesbalk
  if(typeof Android.ereaderWindow==='function'&&erWinLast!=='{}'){erWinLast='{}';try{Android.ereaderWindow('{}');sessionStorage.removeItem('rt.erWin');}catch(e){}}
}

/* Tik in de tekst: links/rechts bladeren (pagina's), midden de knoppen. Met zichtbare knoppen verbergt een tik ze. */
$('#er-text').addEventListener('click',e=>{
  if(!erReading()||e.target.closest('a,button,input,select,[data-er-ann]'))return;
  const sel=window.getSelection();if(sel&&!sel.isCollapsed)return;
  const immersive=document.body.classList.contains('er-immersive');
  if(immersive&&document.body.classList.contains('er-controls')){erControls(false);return;}
  const box=$('#er-text').getBoundingClientRect(),x=(e.clientX-box.left)/Math.max(1,box.width);
  if(erStyle().mode==='pages'&&!erWin.talkback&&(x<.3||x>.7)){erUserAt=Date.now();erTurn(x<.3?-1:1);return;}
  if(immersive)erControls(true);
});
for(const name of ['touchstart','wheel'])$('#er-text').addEventListener(name,()=>{erUserAt=Date.now();},{passive:true});
$('.er-nav').addEventListener('click',()=>{erUserAt=Date.now();});
document.addEventListener('keydown',e=>{
  if(!erReading()||erPanelOn||document.querySelector('#modal.on,#sheet.on,#er-image.on'))return;
  if(e.key==='Escape'||e.key==='Tab'&&!document.body.classList.contains('er-controls')){
    if(!document.body.classList.contains('er-immersive'))return;
    if(e.key==='Escape'){e.preventDefault();erControls(!document.body.classList.contains('er-controls'));}else erControls(true);
  }
});
window.onEreaderKey=function(direction){if(!erReading()||erOnTop())return;erUserAt=Date.now();erTurn(+direction>0?1:-1);};

/* ---------- prettig voor je ogen: een warme laag over het leesvlak ---------- */
function erEvening(now){try{const c=Object.assign({},thCfg(),{mode:'sun',off:0});return thDecide(c,now||Date.now(),false,thLoc(c)).dark;}catch(e){return false;}}
function erWarmOn(){const s=erLezen();return s.warm||s.warmAuto&&erEvening();}
function erWarmApply(){
  const on=erReading()&&erWarmOn(),el=$('#er-warm');el.hidden=!on;$('#s-erbook').style.setProperty('--er-warm',on?String(erLezen().warmLevel/100):'0');
  if(current==='erbook')bars();
}
/* Dezelfde warme laag over de kleur van de systeembalken (vermenigvuldigen met amber). */
function erWarmTint(color){
  if(!erReading()||!erWarmOn())return color;
  let m=/^#([0-9a-f]{6})$/i.exec(color||''),rgb;
  if(m)rgb=[0,2,4].map(i=>parseInt(m[1].slice(i,i+2),16));else if((m=/^#([0-9a-f]{3})$/i.exec(color||'')))rgb=[0,1,2].map(i=>parseInt(m[1][i]+m[1][i],16));
  else if((m=/^rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(color||'')))rgb=[+m[1],+m[2],+m[3]];else return color;
  const a=erLezen().warmLevel/100,amber=[255,165,82];
  return'#'+rgb.map((c,i)=>Math.round(c*(1-a)+c*amber[i]/255*a).toString(16).padStart(2,'0')).join('');
}
setInterval(()=>{if(erReading()&&erLezen().warmAuto)erWarmApply();},60000);

/* ---------- panelen ---------- */
function erPanel(which){
  if(which&&!erReading())which='';
  $('#er-quick').classList.toggle('on',which==='quick');$('#er-tts-panel').classList.toggle('on',which==='tts');erPanelOn=which||'';
  if(which==='quick')erQuickRender();if(which==='tts')erSpeakPanelRender();
  dialogFocus.sync();
}
function erPanelBack(){if(!erPanelOn)return false;erPanel('');return true;}
function erTg(el,on){el.classList.toggle('on',!!on);el.setAttribute('aria-checked',String(!!on));}
function erRadios(box,value){box.querySelectorAll('[role=radio]').forEach(b=>{const on=b.dataset.v===String(value);b.setAttribute('aria-checked',String(on));b.tabIndex=on?0:-1;});}
function erQuickRender(){
  if(erPanelOn!=='quick')return;const s=erLezen(),st=erStyle(),bright=$('#er-bright'),level=Math.round((s.bright<0?s.brightLast:s.bright)*100);
  bright.value=String(level);bright.disabled=s.bright<0;bright.setAttribute('aria-valuetext',s.bright<0?t('Zoals de telefoon'):erPct(level));
  $('#er-bright-val').textContent=s.bright<0?'':erPct(level);erTg($('#er-bright-auto'),s.bright<0);
  erRadios($('#er-q-theme'),st.theme);erRadios($('#er-q-mode'),st.mode);
  erTg($('#er-warm-on'),s.warm);$('#er-warm-level').value=String(s.warmLevel);$('#er-warm-level').setAttribute('aria-valuetext',erPct(s.warmLevel));$('#er-warm-val').textContent=erPct(s.warmLevel);
  erTg($('#er-warm-auto'),s.warmAuto);
  $('#er-warm-auto-s').textContent=s.warmAuto?(erEvening()?t('Nu aan: het is na zonsondergang.'):t('Gaat vanzelf aan bij zonsondergang en uit bij zonsopkomst.')):t('Tussen zonsondergang en zonsopkomst, zoals bij het thema.');
  $('#er-q-size').textContent=erSizePct(st.size);$('#er-q-smaller').disabled=st.size<=ER_VALUES.size[0];$('#er-q-bigger').disabled=st.size>=ER_VALUES.size[ER_VALUES.size.length-1];
}
/* Pijltjestoetsen in een groep keuzeknoppen (zoals een radiogroep hoort te werken). */
for(const id of ['#er-q-theme','#er-q-mode','#er-tts-rates','#er-tts-sleep'])$(id).addEventListener('keydown',e=>{
  if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key))return;const list=[...e.currentTarget.querySelectorAll('[role=radio]')],i=list.indexOf(document.activeElement);if(i<0)return;
  e.preventDefault();const next=list[(i+(e.key==='ArrowLeft'||e.key==='ArrowUp'?-1:1)+list.length)%list.length];next.click();next.focus();
});
function erSetRender(){
  if(current!=='erset')return;const s=erLezen();erTg($('#er-full'),s.full);erTg($('#er-awake'),s.awake);erTg($('#er-keys'),s.keys);
}

/* ---------- voorlezen (gedeelde lokale stem: 75-voorlezen.js, ReadAloud) ----------
 * De motor leest zin voor zin vanaf een tekstplek en gaat zelf door naar het volgende hoofdstuk; elk 'playing'-event
 * geeft [offset, end) van de zin die klinkt. Hier: beginnen bij de eerste zichtbare zin, de zin markeren, meebladeren
 * (niet vlak na zelf bladeren), vorige/volgende zin, slaaptimer en wie de leesplek bewaart. */
const ER_RATES=[.8,1,1.2,1.4,1.6,1.8];
let erSleep={at:0,chapter:false,choice:'0',timer:null},erFollowing=false,erSpeakLoc=null,erSpeakLast=null;
function erBookLang(){const l=erBook&&erBook.language||'';return /^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$/.test(l)?l:'';}
function erLangBase(tag){return String(tag||'').split('-')[0].toLowerCase();}
/* De voorleessessie van de e-reader (of null) */
function erSpeakNow(){const a=typeof sp!=='undefined'&&sp.active;return a&&a.source==='reader'&&!a.stopping?a:null;}
function erSpeakActive(){return !!erSpeakNow();}
function erSpeakBusy(){const a=erSpeakNow();return !!a&&a.state!=='paused';}
function erSpeakHere(){const a=erSpeakNow();return !!a&&!!erBook&&a.book===erBook.id;}
/* Alleen terwijl er echt wordt voorgelezen bewaart de motor de leesplek (na elke zin); gepauzeerd ook zolang de zin nog
   op de pagina staat. Heb je verder gebladerd, dan geldt weer de gewone leesplek (S5). */
function erSpeakOwns(){const a=erSpeakNow();if(!a||!erBook||a.book!==erBook.id)return false;return a.state!=='paused'||erSpeakPausedHere();}
function erSpeakPausedHere(){
  const a=erSpeakNow();if(!a||!erBook||a.book!==erBook.id||a.state!=='paused'||a.chapter!==erIndex)return false;if(!erText.length)return true;
  const box=$('#er-text'),view=box.getBoundingClientRect(),r=erRect(Number(a.offset)||0);return !!r&&r.bottom>view.top&&r.top<view.bottom;
}
/* Begin van de zin waarin pos valt (zelfde grenzen als ReadAloud.segments: regeleinde, of .!?… met witruimte erna). */
function erSentenceStart(text,pos){
  pos=Math.max(0,Math.min(text.length,pos|0));
  for(let i=pos-1;i>=0&&i>pos-800;i--){const c=text[i];if(c==='\n'||(/\s/.test(c)&&i>0&&/[.!?…]/.test(text[i-1]))){let s=i+1;while(s<text.length&&/\s/.test(text[s]))s++;if(s<=pos)return s;}}
  return pos<=800?0:pos;
}
function erSkipSpace(text,pos){while(pos<text.length&&/\s/.test(text[pos]))pos++;return pos;}
function erVoiceInfo(){if(typeof spReadModels!=='function')return{voices:[],supported:false};const m=spReadModels();m.ready=(m.voices||[]).filter(v=>v.state==='ready');return m;}
/* Een gedownloade stem in de taal van het boek, als de gekozen stem een andere taal spreekt (de motor kent één stem). */
function erVoiceForBook(m){
  const want=erLangBase(erBookLang());if(!want||!m.voice)return;const cur=(m.voices||[]).find(v=>v.id===m.voice);if(!cur||cur.language===want)return;
  const other=m.ready.find(v=>v.language===want);if(other){const e=Android.speechConfigure(other.id,m.speed||1,m.background!==false);if(!e)toast(t('Stem {naam} voor dit boek',{naam:other.label}));}
}
function erSpeakStart(){
  if(!erBook)return;
  if(typeof Android.speechBook!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return;}
  const m=erVoiceInfo();if(!m.voice){erPanel('tts');return;} // nog geen stem: uitleg en een knop naar de stemkiezer
  const a=erSpeakNow();
  if(a&&erSpeakHere()&&(a.state!=='paused'||erSpeakPausedHere())){if(a.state==='paused')spPause();return;}
  erVoiceForBook(m);
  const at=erText.length?erAnchor():-1,from=erText.length?erSentenceStart(erText,at>=0?at:Math.max(0,erCurrentAnchor||0)):0;
  erSpeakFrom(erIndex,from);
}
function erSpeakFrom(chapter,offset){
  const a=erSpeakNow();if(a){a.stopping=true;Android.speechStop(a.request);}
  erUserAt=0;erSpeakLoc=null;erSpeakLast=null;spBook(offset,chapter);erSleepArm();
}
function erSpeakToggle(){const a=erSpeakNow();if(!a)return erSpeakStart();if(a.state==='paused'&&erReading()&&a.book===erBook.id)erSpeakStart();else spPause();}
function erSpeakStop(){erSleepSet('0',true);spStop();}
/* Vorige/volgende zin: opnieuw beginnen bij die zin (de motor heeft geen sprong binnen een sessie). */
function erSpeakStep(direction){
  const a=erSpeakNow();if(!a||!erBook||a.book!==erBook.id)return;const off=Number(a.offset)||0;
  if(a.chapter!==erIndex){erSpeakFrom(a.chapter,off);return;}
  if(direction<0){erSpeakFrom(erIndex,off>0?erSentenceStart(erText,Math.max(0,off-1)):0);return;}
  const next=erSkipSpace(erText,Number(a.end)>off?Number(a.end):off+1);
  if(next<erText.length){erSpeakFrom(erIndex,next);return;}
  const ch=erNeighbor(1);if(ch>=0)erSpeakFrom(ch,0);
}
function erSpeakRateNext(){const m=erVoiceInfo(),i=ER_RATES.findIndex(r=>r>=(m.speed||1)-.001);erSpeakRate(ER_RATES[(i+1)%ER_RATES.length]);}
function erSpeakRate(value){const m=erVoiceInfo();const e=Android.speechConfigure(m.voice||'',+value,m.background!==false);if(e)toast(e);erSpeakRender();}
function erSpeakVoice(id){
  const m=erVoiceInfo();if(!m.ready.some(v=>v.id===id))return;const e=Android.speechConfigure(id,m.speed||1,m.background!==false);if(e){toast(e);return;}
  const a=erSpeakNow();if(a&&erSpeakHere()&&a.state!=='paused')erSpeakFrom(a.chapter,Number(a.offset)||0); // verder met dezelfde zin, met de nieuwe stem
  erSpeakRender();
}
function erSpeakBackground(){const m=erVoiceInfo();const e=Android.speechConfigure(m.voice||'',m.speed||1,m.background===false);if(e)toast(e);erSpeakRender();}
function erSpeakVoices(){erPanel('');spSettings();}
/* Slaaptimer: een tijd, of het einde van het hoofdstuk; dan gewoon stoppen (de motor heeft de leesplek al bewaard). */
function erSleepSet(choice,quiet){
  clearTimeout(erSleep.timer);erSleep={at:['15','30','60'].includes(choice)?Date.now()+choice*60000:0,chapter:choice==='chapter',choice:choice||'0',timer:null};
  if(!quiet&&!erSpeakActive()&&choice!=='0')toast(t('Start eerst het voorlezen'));erSleepArm();erSpeakRender();
}
function erSleepArm(){clearTimeout(erSleep.timer);if(erSleep.at)erSleep.timer=setTimeout(erSleepCheck,Math.max(0,erSleep.at-Date.now())+50);}
function erSleepCheck(){if(erSleep.at&&Date.now()>=erSleep.at&&erSpeakActive()){erSleepSet('0',true);spStop();toast(t('Slaaptimer: voorlezen gestopt'));}}
/* Events van de motor voor de e-reader (vanuit onSpeech). */
function erSpeakEvent(e){
  if(!erBooks.some(b=>b.id===e.book)&&!(erBook&&erBook.id===e.book))return;
  if(['playing','progress','paused'].includes(e.state))erSleepCheck(); // ook als de timers van de pagina even stilstonden
  if(e.state==='chapter'&&erSleep.chapter){erSleepSet('0',true);spStop();toast(t('Slaaptimer: voorlezen gestopt'));}
  const ended=['done','stopped','error'].includes(e.state);if(ended)erSleepSet('0',true);
  if(!Number.isInteger(e.chapter)||!Number.isInteger(e.offset)){erSpeakHighlight(null);return;}
  const reading=erReading()&&erBook.id===e.book;
  if(!reading){erSpeakLoc={book:e.book,chapter:e.chapter,offset:e.offset};return;} // terug in het boek: daar verder (erLezenEnter)
  if(e.chapter!==erIndex){erFollowing=true;try{erUserAt=0;if(!erChapter(e.chapter,e.offset))return;}finally{erFollowing=false;}}
  // De exacte plek van de motor; wordt pas overschreven als je zelf bladert of scrolt
  if(!ended||e.state==='done'||e.state==='stopped'){erPendingLocation={chapter:e.chapter,anchor:e.offset,offset:erText.length?e.offset/erText.length:0};erCurrentAnchor=e.offset;erCurrentOffset=erPendingLocation.offset;}
  if(e.state==='playing'&&Number.isInteger(e.end)&&e.end>e.offset){const before=erSpeakLast;erSpeakLast={chapter:e.chapter,offset:e.offset,end:e.end};erSpeakHighlight(erSpeakLast);if(!before||before.offset!==e.offset||before.chapter!==e.chapter)erSpeakFollow(erSpeakLast);}
  else if(ended)erSpeakHighlight(null);
  else if(erSpeakLast)erSpeakHighlight(erSpeakLast);
}
/* De zin die nu klinkt oplichten (CSS Custom Highlight: de tekst zelf blijft onaangeroerd). */
function erSpeakHighlight(r){
  if(!window.CSS||!CSS.highlights||typeof Highlight!=='function')return;CSS.highlights.delete('er-speak');
  if(!r||!erBook||r.chapter!==erIndex||!erText.length||!(r.end>r.offset)||!erSpeakHere())return;
  const a=erPoint(r.offset),b=erPoint(Math.min(erText.length,r.end));if(!a||!b)return;const range=document.createRange();
  try{range.setStart(a.node,a.offset);range.setEnd(b.node,b.offset);}catch(e){return;}
  CSS.highlights.set('er-speak',new Highlight(range));
}
/* Meebladeren: staat de zin (deels) buiten beeld, dan de pagina met die zin; niet vlak nadat je zelf bladerde. */
function erSpeakFollow(r){
  if(!erReading()||!erText.length||Date.now()-erUserAt<4000)return;
  const box=$('#er-text'),view=box.getBoundingClientRect(),pad=parseFloat(getComputedStyle(box).paddingTop)||0,first=erRect(r.offset),last=erRect(Math.max(r.offset,(r.end||r.offset)-1));if(!first)return;
  if(first.top>=view.top+pad-2&&(last||first).bottom<=view.bottom-4)return;
  const keep=erPendingLocation;
  if(erStyle().mode==='pages')erRestoreAnchor(r.offset);else box.scrollTop+=first.top-view.top-box.clientHeight*.3;
  erPendingLocation=keep;erPendingFragment='';erProgress();erQueueImages();
}
function erSpeakStatus(a){
  if(a.state==='loading')return t('Stem starten…');
  if(erSleep.at)return t('Stopt om {tijd}',{tijd:fmtTime(erSleep.at)});
  if(erSleep.chapter)return t('Stopt na dit hoofdstuk');
  return a.state==='paused'?t('Gepauzeerd'):t('Leest voor');
}
function erSpeakRender(){
  const a=erSpeakNow(),here=!!a&&erReading()&&a.book===erBook.id,bar=$('#er-tts'),was=!bar.hidden;
  if(here!==was&&erReading()&&!erEntering){const pos=erLocation();bar.hidden=!here;erRestoreLocation(pos);erProgress();}else bar.hidden=!here;
  document.body.classList.toggle('er-speaking',here);
  $('#er-speak').setAttribute('aria-pressed',String(here));
  if(here){
    const playing=a.state!=='paused',play=$('#er-tts-play');play.dataset.icon=playing?'pause':'play';play.setAttribute('aria-label',t(playing?'Pauzeren':'Verder voorlezen'));
    const rate=erVoiceInfo().speed||1;$('#er-tts-status').textContent=erSpeakStatus(a);$('#er-tts-rate').textContent=fmtNum(rate)+'×';$('#er-tts-rate').setAttribute('aria-label',t('Voorleessnelheid {n} keer, tik voor sneller',{n:fmtNum(rate)}));
  } else if(!a)erSpeakHighlight(null);
  if(erReading())erWindowApply(); // tijdens voorlezen zijn de volumeknoppen voor het volume
  erSpeakPanelRender();
}
function erSpeakPanelRender(){
  if(erPanelOn!=='tts')return;const m=erVoiceInfo(),none=!m.voice||!m.ready.length;
  $('#er-tts-novoice').hidden=!none;$('#er-tts-main').hidden=none;
  $('#er-tts-novoice-t').textContent=m.supported===false?t('Lokale voorleesstemmen werken op een 64-bits toestel'):t('Er is nog geen stem gedownload. Download eenmalig een stem (ongeveer 21 MB) bij Lokaal voorlezen; daarna leest de e-reader voor zonder internet.');
  $('#er-tts-novoice button').disabled=m.supported===false;
  if(none)return;
  const rates=$('#er-tts-rates');if(!rates.children.length)rates.innerHTML=ER_RATES.map(v=>'<button role="radio" data-v="'+v+'" onclick="erSpeakRate('+v+')" translate="no">'+esc(fmtNum(v))+'×</button>').join('');
  else rates.querySelectorAll('button').forEach(b=>{b.textContent=fmtNum(+b.dataset.v)+'×';});
  erRadios(rates,ER_RATES.find(r=>Math.abs(r-(m.speed||1))<.001)||'');erRadios($('#er-tts-sleep'),erSleep.chapter?'chapter':erSleep.at?erSleep.choice:'0');
  const voices=$('#er-tts-voice');voices.replaceChildren(...m.ready.map(v=>{const o=new Option(v.label,v.id);o.translate=false;return o;}));voices.value=m.voice;
  const book=erLangBase(erBookLang()),cur=m.ready.find(v=>v.id===m.voice);
  $('#er-tts-voice-hint').textContent=book&&cur&&cur.language!==book&&!m.ready.some(v=>v.language===book)?t('Dit boek is in het {taal}; download een stem in die taal bij Stemmen beheren.',{taal:erLangName(book)}):'';
  erTg($('#er-tts-bg'),m.background!==false);
}
function erLangName(tag){try{const n=new Intl.DisplayNames([regioLocale()],{type:'language'}).of(erLangBase(tag));if(n&&n!==erLangBase(tag))return n;}catch(e){}return tag;}
/* Vanuit de melding of het startscherm: naar het boek dat wordt voorgelezen (blijft staan als je het al leest). */
function erSpeakOpen(){
  if(typeof spSync==='function')spSync();const a=erSpeakNow();if(!a){if(!erReading())show('ereader');return;}
  if(erReading()&&erBook.id===a.book)return;
  if(current!=='ereader')show('ereader');if(erBooks.some(b=>b.id===a.book))erOpen(a.book);
}
/* Zelf naar een ander boek of hoofdstuk springen (inhoud, zoeken, schuif) stopt het voorlezen; bladeren niet. */
function erSpeakJump(id){if(erFollowing)return;erSpeakLoc=null;const a=erSpeakNow();if(a&&(id===undefined||a.book!==id))spStop(true);}

(function(){
  const resumed=window.onResumeApp;window.onResumeApp=function(){if(resumed)resumed();if(erReading()){erWindowApply(true);erWarmApply();}erSpeakRender();};
  // Iets over het boek open of dicht: de volumeknoppen volgen
  new MutationObserver(()=>{if(erReading())erWindowApply();}).observe(document.body,{attributes:true,attributeFilter:['class'],subtree:true,attributeOldValue:false});
  // Na herladen van de pagina tijdens het lezen: het venster weer gewoon maken
  try{if(sessionStorage.getItem('rt.erWin')&&current!=='erbook'&&typeof Android.ereaderWindow==='function'){Android.ereaderWindow('{}');sessionStorage.removeItem('rt.erWin');}}catch(e){}
})();
