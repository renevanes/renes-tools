/* Gedeelde voorleesclient. Alleen native stemmen, één actieve aanvraag en geen opslag van tekst/audio. */
const sp={active:null,seq:0,settings:false,models:{voices:[]},focus:null,origin:'',timer:null,following:false};
function spReadModels(){try{return JSON.parse(Android.speechModels());}catch(_){return {voices:[],supported:false};}}
function spAvailable(){if(typeof Android.speechStart!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return false;}sp.models=spReadModels();if(!sp.models.voice){spSettings();return false;}return true;}
function spText(text,source='text',markdown=false){if(!spAvailable())return;if(typeof text!=='string'||!text.trim()){toast(t('Er is geen tekst om voor te lezen'));return;}if(text.length>120000){toast(t('Deze tekst is te lang om in één keer voor te lezen'));return;}const request='lees-'+Date.now()+'-'+(++sp.seq);sp.active={request,source,state:'loading'};spRender();Android.speechStart(text,request,source,markdown);}
function spBook(){if(!erBook||!spAvailable())return;const request='boek-'+Date.now()+'-'+(++sp.seq);sp.active={request,source:'reader',state:'loading',book:erBook.id,chapter:erIndex};spRender();Android.speechBook(erBook.id,erIndex,erAnchor(),request);}
function spSelection(){const selection=erSelection;if(!selection||!erText)return;spText(erText.slice(selection.start,selection.end),'selection');}
function spNote(){const n=noteById(noteCur);if(n)spText([n.title,n.text,...(n.items||[]).map(i=>i.text)].filter(Boolean).join('\n'),'note',true);}
function spPreview(){const voice=(sp.models.voices||[]).find(v=>v.id===sp.models.voice);spText(voice&&voice.language==='en'?'This voice reads your books and notes on your phone.':t('Deze stem leest je boeken en notities voor op je telefoon.'),'preview');}
function spStop(){const active=sp.active;if(!active)return;sp.active=null;Android.speechStop(active.request);spRender();}
function spPause(){const active=sp.active;if(!active)return;Android.speechPause(active.request,active.state!=='paused');}
function spAiPause(){if(aiSpk)Android.speechPause(aiSpk.id,!(sp.active&&sp.active.request===aiSpk.id&&sp.active.state==='paused'));}
function spRender(){
  const active=sp.active,show=active&&active.source!=='ai';$('#speech-player').hidden=!show;document.body.classList.toggle('speech-visible',!!show);
  if(show){$('#speech-title').textContent=active.source==='reader'?t('Boek voorlezen'):active.source==='note'?t('Notitie voorlezen'):t('Voorlezen');$('#speech-state').textContent=active.state==='paused'?t('Gepauzeerd'):active.state==='loading'?t('Stem starten…'):t('Lokale stem');const pause=$('#speech-pause');pause.setAttribute('aria-label',active.state==='paused'?t('Verder voorlezen'):t('Pauzeren'));pause.textContent='';pause.innerHTML=uiIcon(active.state==='paused'?'play':'pause');}
  if(typeof aiSpk!=='undefined'&&aiSpk){const pause=$('#ai-speech-pause');if(pause)pause.textContent=active&&active.request===aiSpk.id&&active.state==='paused'?t('Verder voorlezen'):t('Pauzeren');}
}
window.onSpeech=function(e){
  if(!e||!e.request)return;
  // De assistent gebruikt zijn bestaande knoppen; de onderliggende motor is gedeeld.
  if(e.source==='ai'&&e.state==='loading'&&(!sp.active||sp.active.source==='ai'))sp.active={...e};
  if(!sp.active||e.request!==sp.active.request)return;
  if(['done','stopped','error'].includes(e.state)){sp.active=null;spRender();if(e.state==='error'){if(!spReadModels().voice)spSettings();toast(e.error||t('Lokaal voorlezen lukt niet; probeer een korter stuk tekst'));}return;}
  sp.active={...sp.active,...e};spRender();
  if(e.source==='reader'&&erBook&&e.book===erBook.id&&current==='erbook'&&!document.hidden&&(e.state==='progress'||e.state==='chapter')){
    sp.following=true;try{if(erIndex!==e.chapter)erChapter(e.chapter,e.offset);else{erRestoreLocation({anchor:e.offset,offset:erText.length?e.offset/erText.length:0});erProgress();}}finally{sp.following=false;}
  }
};
function spSync(){if(typeof Android.speechState!=='function')return;try{const e=JSON.parse(Android.speechState());if(e.active&&!['done','stopped','error'].includes(e.state)){sp.active=e;onSpeech(e);}else{sp.active=null;spRender();}}catch(_){ }}
function spSettings(){
  if(typeof Android.speechModels!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return;}
  if(!sp.settings){sp.origin=current;sp.focus=document.activeElement;$('#s-'+current).inert=true;sp.settings=true;$('#speech-settings').hidden=false;$('#speech-heading').focus();}
  spModelsRender();clearInterval(sp.timer);sp.timer=setInterval(spModelsRender,1000);
}
function spSettingsClose(){if(!sp.settings)return;sp.settings=false;clearInterval(sp.timer);$('#speech-settings').hidden=true;const origin=$('#s-'+sp.origin);if(origin)origin.inert=false;if(sp.focus&&sp.focus.isConnected)sp.focus.focus();}
function spModelsRender(){
  if(!sp.settings)return;sp.models=spReadModels();const model=sp.models,box=$('#speech-voices');box.replaceChildren();
  for(const voice of model.voices||[]){
    const card=cmEl('div',undefined,'speech-voice'),title=cmEl('h3',voice.label),status=cmEl('p',undefined,'hint'),actions=cmEl('div',undefined,'row');
    const ready=voice.state==='ready',busy=['running','queued'].includes(voice.state);status.textContent=ready?(voice.selected?t('In gebruik'):t('Stem gedownload')):busy?t('Downloaden: {stand}',{stand:voice.size?fmtNum(100*voice.bytes/voice.size,0)+'%':t('bezig')}):voice.error||t('Nog te downloaden: {grootte}',{grootte:fmtSize(voice.size)});
    const button=(label,act)=>{const b=cmEl('button',t(label),'mini');b.onclick=act;actions.append(b);return b;};
    if(ready){if(!voice.selected)button('Gebruiken',()=>spChoose(voice.id));button('Verwijderen',()=>spDelete(voice));}
    else if(busy)button('Downloaden stoppen',()=>{Android.speechDownloadStop(voice.id);spModelsRender();});
    else{const b=button(voice.bytes?'Verder downloaden':'Stem downloaden',()=>{const error=Android.speechDownload(voice.id);if(error)toast(error);spModelsRender();});b.disabled=model.supported===false;}
    card.append(title,status,actions);box.append(card);
  }
  if(document.activeElement!==$('#speech-speed'))$('#speech-speed').value=model.speed||1;$('#speech-speed-value').textContent=t('{snelheid}×',{snelheid:fmtNum(model.speed||1,1)});
  $('#speech-background').setAttribute('aria-checked',String(model.background!==false));$('#speech-background').classList.toggle('on',model.background!==false);$('#speech-preview').disabled=!model.voice;
  $('#speech-model-status').textContent=model.supported===false?t('Lokale voorleesstemmen werken op een 64-bits toestel'):model.voice?t('Dezelfde stem wordt gebruikt in de e-reader, notities en assistent.'):t('Download een stem en kies daarna opnieuw Voorlezen.');$('#speech-engine').textContent=model.engine||'';
}
function spSave(voice,speed,background){const error=Android.speechConfigure(voice,speed,background);if(error)toast(error);spModelsRender();}
function spChoose(voice){spStop();spSave(voice,sp.models.speed||1,sp.models.background!==false);}
function spSpeed(value){const voice=sp.models.voice||'';spSave(voice,Number(value),sp.models.background!==false);}
function spBackground(){const voice=sp.models.voice||'';spSave(voice,sp.models.speed||1,sp.models.background===false);}
function spDelete(voice){spSettingsClose();askConfirm(t('Voorleesstem verwijderen?'),t('De stem wordt van je telefoon verwijderd. Je kunt hem later opnieuw downloaden.'),t('Verwijderen'),()=>{Android.speechDelete(voice.id);spSettings();});}
const spOldPause=window.onPauseApp;window.onPauseApp=function(){if(spOldPause)spOldPause();if(sp.active&&sp.active.source!=='reader'){sp.active=null;spRender();}};
const spOldResume=window.onResumeApp;window.onResumeApp=function(){if(spOldResume)spOldResume();spSync();if(sp.settings)spModelsRender();};
const spOldErSave=erSave;erSave=function(){if(!sp.following)spOldErSave();};
const spOldErOpen=erOpen;erOpen=function(id,skip){if(sp.active&&sp.active.source==='reader'&&sp.active.book!==id)spStop();spOldErOpen(id,skip);};
$('#speech-settings').addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();spSettingsClose();}else if(e.key==='Tab'){const nodes=[...e.currentTarget.querySelectorAll('button,input,summary')].filter(n=>!n.disabled&&n.getClientRects().length),first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});

const spOldBack=goBack;goBack=function(){if(sp.settings){spSettingsClose();return;}spOldBack();};
