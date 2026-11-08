/* E-reader lezen: volle scherm met knoppen die je met een tik in het midden toont, snelle instellingen (helderheid,
 * thema, prettig voor je ogen, tekstgrootte, leesmodus) en voorlezen met de stem van de telefoon (EreaderSpeakService). */
const ER_LEZEN={full:true,awake:true,keys:false,bright:-1,brightLast:.5,warm:false,warmLevel:30,warmAuto:false};
const ER_RATES=[.75,1,1.25,1.5,1.75,2];
let erEntering=false,erWin={talkback:false},erPanelOn='',erSpk={state:'stopped'},erVoices=null,erUserAt=0,erSleepChoice='0';

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
  erWindowApply();erWarmApply();erQuickRender();erSetRender();return'';
}
function erBrightInput(value){erLezenSet('bright',Math.max(.02,Math.min(1,value/100)));}
function erSizeStep(direction){const size=erStyle().size,i=ER_VALUES.size.indexOf(size),next=ER_VALUES.size[Math.max(0,Math.min(ER_VALUES.size.length-1,(i<0?3:i)+direction))];if(next!==size)erPreference('size',next);erQuickRender();}
function erPct(v){return fmtNum(v/100,{style:'percent'});}
function erSizePct(size){return erPct(Math.round(size/22*100));}

/* ---------- venster: volle scherm, scherm aan, helderheid, volumeknoppen ---------- */
function erReading(){return current==='erbook'&&!!erBook;}
function erWindowApply(){
  const s=erLezen(),on=erReading();
  if(typeof Android.ereaderWindow==='function'){
    let r={};try{r=JSON.parse(Android.ereaderWindow(JSON.stringify(on?{full:s.full,awake:s.awake,bright:s.bright,keys:s.keys}:{}))||'{}');}catch(e){}
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
function erLezenEnter(){erEntering=true;try{erWindowApply();erControls(false);erWarmApply();erSpeakSync();erSpeakRender();}finally{erEntering=false;}}
function erLezenLeave(){
  erPanel('');document.body.classList.remove('er-controls','er-immersive','er-speaking');erSpeakHighlight(null);$('#er-warm').hidden=true;
  if(typeof Android.ereaderWindow==='function'){try{Android.ereaderWindow('{}');sessionStorage.removeItem('rt.erWin');}catch(e){}}
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
window.onEreaderKey=function(direction){if(!erReading()||erPanelOn||document.querySelector('#modal.on,#sheet.on,#er-image.on'))return;erUserAt=Date.now();erTurn(+direction>0?1:-1);};

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
  if(which==='quick')erQuickRender();if(which==='tts'){erSpeakPanelRender();if(!erVoices)erSpeakVoices();}
  dialogFocus.sync();
}
function erPanelBack(){if(!erPanelOn)return false;erPanel('');return true;}
function erTg(el,on){el.classList.toggle('on',!!on);el.setAttribute('aria-checked',String(!!on));}
function erRadios(box,value){box.querySelectorAll('[role=radio]').forEach(b=>{const on=b.dataset.v===String(value);b.setAttribute('aria-checked',String(on));b.tabIndex=on?0:-1;});}
function erQuickRender(){
  if(erPanelOn!=='quick')return;const s=erLezen(),st=erStyle(),bright=$('#er-bright'),level=Math.round((s.bright<0?s.brightLast:s.bright)*100);
  bright.value=String(level);bright.disabled=s.bright<0;bright.setAttribute('aria-valuetext',s.bright<0?t('Zoals de telefoon'):erPct(level));
  $('#er-bright-val').textContent=s.bright<0?t('Zoals de telefoon'):erPct(level);erTg($('#er-bright-auto'),s.bright<0);
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

/* ---------- voorlezen ---------- */
function erSpeakPrefs(){
  const raw=store.get('erSpeak',{}),p=Object.assign({rate:1,lang:'',voices:{}},raw&&typeof raw==='object'?raw:{});
  p.rate=ER_RATES.includes(+p.rate)?+p.rate:1;if(typeof p.lang!=='string'||!/^[A-Za-z0-9-]{0,35}$/.test(p.lang))p.lang='';
  if(!p.voices||typeof p.voices!=='object'||Array.isArray(p.voices))p.voices={};return p;
}
function erBookLang(){const l=erBook&&erBook.language||'';return /^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$/.test(l)?l:'';}
function erSpeakLang(){return erSpeakPrefs().lang||erBookLang()||rgCfg().lang||'nl';}
function erLangBase(tag){return String(tag||'').split('-')[0].toLowerCase();}
function erLangName(tag){try{const n=new Intl.DisplayNames([regioLocale()],{type:'language'}).of(erLangBase(tag));if(n&&n!==erLangBase(tag))return n;}catch(e){}return tag;}
function erSpeakActive(r){r=r||erSpk;return['playing','starting','paused'].includes(r.state);}
function erSpeakOwns(){return !!erBook&&erSpk.id===erBook.id&&erSpeakActive();}
function erSpeakSync(){if(typeof Android.ereaderSpeakState!=='function')return;try{const r=JSON.parse(Android.ereaderSpeakState());if(r&&r.state)erSpk=r;}catch(e){}}
function erSpeakOptions(id,chapter,anchor){const p=erSpeakPrefs(),lang=erSpeakLang();return JSON.stringify({id,chapter,anchor,rate:p.rate,lang,voice:p.voices[erLangBase(lang)]||''});}
function erSpeakStart(){
  if(!erBook)return;
  if(typeof Android.ereaderSpeak!=='function'){toast(t('Installeer de nieuwste APK om te laten voorlezen'));return;}
  if(erSpk.id===erBook.id&&erSpeakActive()){if(erSpk.state==='paused')erSpeakCmd('resume');erSpeakRender();return;}
  const at=erText.length?erAnchor():-1,anchor=at>=0?at:Math.max(0,erCurrentAnchor||0);
  const error=Android.ereaderSpeak(erSpeakOptions(erBook.id,erIndex,erText.length?anchor:0));
  if(error){toast(error);return;}
  erSpk=Object.assign({},erSpk,{state:'starting',id:erBook.id,title:erBook.title,chapter:erIndex,start:anchor,end:anchor,code:'',error:''});
  erUserAt=0;erSpeakRender();
}
/* Andere taal of stem tijdens het voorlezen: opnieuw beginnen bij dezelfde zin. */
function erSpeakRestart(){if(!erSpeakActive()||typeof Android.ereaderSpeak!=='function')return;const error=Android.ereaderSpeak(erSpeakOptions(erSpk.id,erSpk.chapter,erSpk.start));if(error)toast(error);}
function erSpeakCmd(cmd,arg){if(typeof Android.ereaderSpeakCmd!=='function')return;Android.ereaderSpeakCmd(cmd,arg==null?'':String(arg));if(cmd==='stop'){erSpk=Object.assign({},erSpk,{state:'stopped'});erSpeakHighlight(null);erSpeakRender();}}
function erSpeakToggle(){erSpeakCmd(erSpk.state==='paused'?'resume':'pause');}
function erSpeakRateNext(){const p=erSpeakPrefs();erSpeakPref('rate',ER_RATES[(ER_RATES.indexOf(p.rate)+1)%ER_RATES.length]);}
function erSpeakPref(key,value){
  const p=erSpeakPrefs();
  if(key==='rate'){if(!ER_RATES.includes(+value))return;p.rate=+value;}
  else if(key==='lang'){if(!/^[A-Za-z0-9-]{0,35}$/.test(value))return;p.lang=value;erVoices=null;}
  else if(key==='voice'){p.voices[erLangBase(erSpeakLang())]=String(value||'').slice(0,200);}else return;
  store.set('erSpeak',p);
  if(key==='rate'){erSpk=Object.assign({},erSpk,{rate:p.rate});if(erSpeakActive())erSpeakCmd('rate',p.rate);}
  else if(erSpeakActive())erSpeakRestart();
  if(key==='lang'&&erPanelOn==='tts')erSpeakVoices();
  erSpeakRender();
}
function erSpeakSleep(choice){erSleepChoice=choice;if(erSpeakActive())erSpeakCmd('sleep',choice);else toast(t('Start eerst het voorlezen'));erSpeakPanelRender();}
function erSpeakVoices(){if(typeof Android.ereaderVoices!=='function')return;erVoices=null;Android.ereaderVoices(erSpeakLang());erSpeakPanelRender();}
window.onEreaderVoices=function(r){erVoices=r&&typeof r==='object'?r:{state:'noengine'};erSpeakPanelRender();};
function erSpeakVoiceAdd(){if(typeof Android.ereaderTtsSettings==='function')Android.ereaderTtsSettings(true);}
function erSpeakNoVoice(lang){
  const taal=erLangName(lang||erSpeakLang());
  askConfirm(t('Geen stem voor {taal}',{taal}),t('Je telefoon heeft geen stem voor {taal} om mee voor te lezen. Voeg er een toe bij tekst-naar-spraak in de instellingen van Android.',{taal}),t('Stem toevoegen'),erSpeakVoiceAdd);
}
window.onEreaderSpeak=function(r){
  if(!r||typeof r!=='object')return;const before=erSpk;erSpk=r;
  if(r.state==='error'){erSpeakHighlight(null);if(r.code==='nolang')erSpeakNoVoice(r.lang);else if(r.error)toast(r.error);}
  if(r.state==='paused'&&r.code==='end'&&before.code!=='end')toast(t('Het boek is uit'));
  if(!erSpeakActive(r))erSpeakHighlight(null);
  if(erReading()&&r.id===erBook.id&&(r.state==='playing'||r.state==='paused')&&Number.isInteger(r.chapter)){
    if(r.chapter!==erIndex){erUserAt=0;if(!erChapter(r.chapter,r.start)){erSpeakRender();return;}}
    erSpeakHighlight(r);
    if(r.start!==before.start||r.chapter!==before.chapter||before.state!==r.state)erSpeakFollow(r);
  }
  erSpeakRender();
};
/* De zin die nu klinkt oplichten (CSS Custom Highlight: de tekst zelf blijft onaangeroerd). */
function erSpeakHighlight(r){
  if(!window.CSS||!CSS.highlights||typeof Highlight!=='function')return;CSS.highlights.delete('er-speak');
  if(!r||!erBook||r.id!==erBook.id||r.chapter!==erIndex||!erText.length||!(r.end>r.start))return;
  const a=erPoint(r.start),b=erPoint(r.end);if(!a||!b)return;const range=document.createRange();
  try{range.setStart(a.node,a.offset);range.setEnd(b.node,b.offset);}catch(e){return;}
  CSS.highlights.set('er-speak',new Highlight(range));
}
/* Meebladeren: staat de zin (deels) buiten beeld, dan de pagina met die zin; niet vlak nadat je zelf bladerde. */
function erSpeakFollow(r){
  if(!erReading()||!erText.length||Date.now()-erUserAt<4000)return;
  const box=$('#er-text'),view=box.getBoundingClientRect(),pad=parseFloat(getComputedStyle(box).paddingTop)||0,first=erRect(r.start),last=erRect(Math.max(r.start,(r.end||r.start)-1));if(!first)return;
  if(first.top>=view.top+pad-2&&(last||first).bottom<=view.bottom-4)return;
  if(erStyle().mode==='pages')erRestoreAnchor(r.start);else box.scrollTop+=first.top-view.top-box.clientHeight*.3;
  erPendingLocation=null;erPendingFragment='';erCurrentAnchor=erAnchor();erProgress();erScroll();
}
function erSpeakStatus(r){
  if(r.state==='starting')return t('Voorlezen begint…');
  if(r.code==='end')return t('Het boek is uit');
  if(r.sleepAt>0)return t('Stopt om {tijd}',{tijd:fmtTime(r.sleepAt)});
  if(r.sleepChapter)return t('Stopt na dit hoofdstuk');
  return r.state==='paused'?t('Gepauzeerd'):t('Leest voor');
}
function erSpeakRender(){
  const r=erSpk,active=erSpeakActive(r),here=active&&erReading()&&r.id===erBook.id,bar=$('#er-tts'),was=!bar.hidden;
  if(here!==was&&erReading()&&!erEntering){const pos=erLocation();bar.hidden=!here;erRestoreLocation(pos);erProgress();}else bar.hidden=!here;
  document.body.classList.toggle('er-speaking',here);
  $('#er-speak').setAttribute('aria-pressed',String(here));
  if(here){
    const playing=r.state!=='paused',play=$('#er-tts-play');play.dataset.icon=playing?'pause':'play';play.setAttribute('aria-label',t(playing?'Pauzeren':'Verder voorlezen'));
    $('#er-tts-status').textContent=erSpeakStatus(r);const rate=+r.rate||erSpeakPrefs().rate;$('#er-tts-rate').textContent=fmtNum(rate)+'×';$('#er-tts-rate').setAttribute('aria-label',t('Voorleessnelheid {n} keer, tik voor sneller',{n:fmtNum(rate)}));
  }
  const lib=$('#er-speaking'),book=active&&erBooks.find(b=>b.id===r.id);lib.hidden=!(book&&current==='ereader');
  if(!lib.hidden){
    lib.replaceChildren();const icon=document.createElement('span');icon.className='ui-icon';icon.dataset.icon='volume-high';icon.setAttribute('aria-hidden','true');
    const text=document.createElement('span');text.className='er-speaking-text';const title=document.createElement('b');title.translate=false;title.textContent=book.title;const sub=document.createElement('small');sub.textContent=erSpeakStatus(r);text.append(title,sub);
    const toggle=document.createElement('button');toggle.className='icon-btn';toggle.dataset.icon=r.state==='paused'?'play':'pause';toggle.setAttribute('aria-label',t(r.state==='paused'?'Verder voorlezen':'Pauzeren'));toggle.addEventListener('click',erSpeakToggle);
    const open=document.createElement('button');open.className='big tonal';open.textContent=t('Naar het boek');open.addEventListener('click',erSpeakOpen);
    lib.append(icon,text,toggle,open);
  }
  erSpeakPanelRender();
}
function erSpeakPanelRender(){
  if(erPanelOn!=='tts')return;const p=erSpeakPrefs(),lang=erSpeakLang(),r=erSpk;
  const rates=$('#er-tts-rates');if(!rates.children.length)rates.innerHTML=ER_RATES.map(v=>'<button role="radio" data-v="'+v+'" onclick="erSpeakPref(\'rate\','+v+')" translate="no">'+esc(fmtNum(v))+'×</button>').join('');
  else rates.querySelectorAll('button').forEach(b=>{b.textContent=fmtNum(+b.dataset.v)+'×';});
  erRadios(rates,p.rate);erRadios($('#er-tts-sleep'),r.sleepChapter?'chapter':r.sleepAt>0?erSleepChoice:'0');
  const langs=$('#er-tts-lang'),book=erBookLang()||rgCfg().lang||'nl',list=(erVoices&&erVoices.langs)||[];
  langs.replaceChildren(new Option(t('Taal van het boek: {taal}',{taal:erLangName(book)}),''));
  const seen=new Set();for(const l of list.concat(p.lang&&!list.some(x=>x.tag===p.lang)?[{tag:p.lang,label:erLangName(p.lang)}]:[])){if(seen.has(l.tag))continue;seen.add(l.tag);const o=new Option(l.label||erLangName(l.tag),l.tag);o.translate=false;langs.append(o);}
  langs.value=p.lang;
  const voices=$('#er-tts-voice'),chosen=p.voices[erLangBase(lang)]||'',vl=erVoices&&erLangBase(erVoices.lang)===erLangBase(lang)?erVoices.voices||[]:[];
  voices.replaceChildren(new Option(t('Standaardstem'),''));for(const v of vl){const o=new Option(v.label,v.name);o.translate=false;voices.append(o);}
  voices.value=vl.some(v=>v.name===chosen)?chosen:'';voices.disabled=!vl.length;
  const hint=$('#er-tts-voices-hint'),add=$('#er-tts-add');
  if(!erVoices){hint.textContent=t('Stemmen op je telefoon zoeken…');add.hidden=true;}
  else if(erVoices.state==='noengine'){hint.textContent=t('Je telefoon heeft geen voorleesfunctie (tekst-naar-spraak).');add.hidden=false;}
  else if(!vl.length){hint.textContent=t('Geen stem voor {taal} op je telefoon.',{taal:erLangName(lang)});add.hidden=false;}
  else{hint.textContent=tn(vl.length,'Eén stem op je telefoon','{n} stemmen op je telefoon',{n:fmtNum(vl.length)});add.hidden=true;}
}
/* Vanuit de melding: naar het boek dat wordt voorgelezen (blijft staan als je het al leest). */
function erSpeakOpen(){
  erSpeakSync();const id=erSpk.id,r=Object.assign({},erSpk);if(!id||!erSpeakActive(r)){if(!erReading())show('ereader');return;}
  if(!erReading()||erBook.id!==id){if(current!=='ereader')show('ereader');if(!erBooks.some(b=>b.id===id))return;erOpen(id);}
  erSpk={state:'stopped'};erUserAt=0;window.onEreaderSpeak(r);
}

(function(){
  const resumed=window.onResumeApp;window.onResumeApp=function(){if(resumed)resumed();if(erReading()){erWindowApply();erWarmApply();}erSpeakSync();erSpeakRender();};
  // Na herladen van de pagina tijdens het lezen: het venster weer gewoon maken
  try{if(sessionStorage.getItem('rt.erWin')&&current!=='erbook'&&typeof Android.ereaderWindow==='function'){Android.ereaderWindow('{}');sessionStorage.removeItem('rt.erWin');}}catch(e){}
})();
