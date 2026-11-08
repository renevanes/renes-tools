/* Gedeelde voorleesclient. Alleen native stemmen, één actieve aanvraag en geen opslag van tekst/audio.
   Gebruik: spText(tekst, bron, markdown) of spBook() (vanaf de leesplek, door de hoofdstukken heen); spPause/spStop; spSettings.
   onSpeech(e): {request, source, state, book, chapter, offset, end}. 'playing' = [offset, end) van de tekst of het
   hoofdstuk is nu hoorbaar (één zin: om te markeren en de pagina te volgen); 'progress' = tot offset uitgesproken
   (boek: leesplek bewaard); 'chapter' = volgend hoofdstuk; 'paused' (met error als verder niet lukte), 'done',
   'stopped', 'error'. Snelheid: speechConfigure (geldt vanaf de volgende zin of de zin daarna). Zie docs/voorlezen.md. */
const sp={active:null,seq:0,settings:false,models:{voices:[]},focus:null,origin:'',timer:null,following:false,readerLocation:null};
function spReadModels(){try{return JSON.parse(Android.speechModels());}catch(_){return {voices:[],supported:false};}}
function spAvailable(){if(typeof Android.speechStart!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return false;}sp.models=spReadModels();if(!sp.models.voice){spSettings();return false;}return true;}
function spText(text,source='text',markdown=false){if(!spAvailable())return;if(typeof text!=='string'||!text.trim()){toast(t('Er is geen tekst om voor te lezen'));return;}if(text.length>120000){toast(t('Deze tekst is te lang om in één keer voor te lezen'));return;}const request='lees-'+Date.now()+'-'+(++sp.seq);sp.active={request,source,state:'loading'};spSpeechRender();Android.speechStart(text,request,source,markdown);}
/* Boek voorlezen vanaf een tekstplek (standaard: de leesplek); de e-reader kiest het begin van de zin (erSpeakStart). */
function spBook(offset,chapter){if(!erBook||!spAvailable())return;const ch=Number.isInteger(chapter)?chapter:erIndex,at=Number.isInteger(offset)&&offset>=0?offset:Math.max(0,erAnchor()),request='boek-'+Date.now()+'-'+(++sp.seq);sp.active={request,source:'reader',state:'loading',book:erBook.id,chapter:ch,offset:at,end:at};spSpeechRender();Android.speechBook(erBook.id,ch,at,request);}
function spSelection(){const selection=erSelection;if(!selection||!erText)return;spText(erText.slice(selection.start,selection.end),'selection');}
function spNote(){const n=noteById(noteCur);if(n)spText([n.title,n.text,...(n.items||[]).map(i=>i.text)].filter(Boolean).join('\n'),'note',true);}
// taal-vast: het voorbeeld is in de taal van de gekozen stem, onafhankelijk van de interface
function spPreview(){const voice=(sp.models.voices||[]).find(v=>v.id===sp.models.voice);spText(voice&&voice.language==='en'?'This voice reads your books and notes on your phone.':'Deze stem leest je boeken en notities voor op je telefoon.','preview');}
function spStop(abandon=false){const active=sp.active;if(!active)return;
  // Heeft de app geen sessie (meer) voor deze aanvraag, dan komt er geen 'stopped': meteen loslaten.
  let native=true;if(typeof Android.speechState==='function'){try{const st=JSON.parse(Android.speechState());native=!!st.active&&st.request===active.request;}catch(_){native=false;}}
  if(active.source==='reader'&&!abandon&&native){active.stopping=true;setTimeout(()=>{if(sp.active===active&&active.stopping){sp.active=null;spSpeechRender();}},3000);}else sp.active=null;
  Android.speechStop(active.request);spSpeechRender();}
function spPause(){const active=sp.active;if(!active)return;Android.speechPause(active.request,active.state!=='paused');}
function spAiPause(){if(aiSpk)Android.speechPause(aiSpk.id,!(sp.active&&sp.active.request===aiSpk.id&&sp.active.state==='paused'));}
function spSpeechRender(){
  const active=sp.active,show=active&&!active.stopping&&active.source!=='ai'&&!(active.source==='reader'&&typeof erReading==='function'&&erReading()); /* in het boek: de voorleesbalk van de e-reader */$('#speech-player').hidden=!show;document.body.classList.toggle('speech-visible',!!show);
  if(show){$('#speech-title').textContent=active.source==='reader'?t('Boek voorlezen'):active.source==='note'?t('Notitie voorlezen'):t('Voorlezen');$('#speech-state').textContent=active.state==='paused'?t('Gepauzeerd'):active.state==='loading'?t('Stem starten…'):t('Lokale stem');const pause=$('#speech-pause');pause.setAttribute('aria-label',active.state==='paused'?t('Verder voorlezen'):t('Pauzeren'));pause.textContent='';pause.dataset.icon=active.state==='paused'?'play':'pause';}
  if(typeof aiSpk!=='undefined'&&aiSpk){const pause=$('#ai-speech-pause');if(pause)pause.textContent=active&&active.request===aiSpk.id&&active.state==='paused'?t('Verder voorlezen'):t('Pauzeren');}
  if(typeof erSpeakRender==='function')erSpeakRender(); // de voorleesbalk en het paneel van de e-reader
}
window.onSpeech=function(e){
  if(!e||!e.request)return;
  // De assistent gebruikt zijn bestaande knoppen; de onderliggende motor is gedeeld.
  if(e.source==='ai'&&e.state==='loading'&&(!sp.active||sp.active.source==='ai'))sp.active={...e};
  if(!sp.active||e.request!==sp.active.request)return;
  sp.active={...sp.active,...e};
  if(e.source==='reader'&&erBook&&e.book===erBook.id&&Number.isInteger(e.chapter)&&Number.isInteger(e.offset)&&['playing','progress','chapter','paused','done','stopped'].includes(e.state)){
    if(typeof erSpeakEvent==='function')erSpeakEvent(e); // markeren, meebladeren en leesplek: 74c-ereader-lezen.js
  }
  if(['done','stopped','error'].includes(e.state)){sp.active=null;spSpeechRender();if(e.state==='error'&&e.source!=='ai'){if(!spReadModels().voice)spSettings();toast(e.error||t('Lokaal voorlezen lukt niet; probeer een korter stuk tekst'));}return;}
  if(e.state==='paused'&&e.error)toast(e.error); // verder lukte niet: een andere app (bijv. een gesprek) heeft het geluid
  spSpeechRender();
};
function spSync(){if(typeof Android.speechState!=='function')return;try{const e=JSON.parse(Android.speechState());if(e.active&&!['done','stopped','error'].includes(e.state)){sp.active=e;onSpeech(e);}else{sp.active=null;spSpeechRender();}}catch(_){ }}
function spSettings(){
  if(typeof Android.speechModels!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return;}
  if(!sp.settings){sp.origin=current;sp.focus=document.activeElement;$('#s-'+current).inert=true;sp.settings=true;$('#speech-settings').hidden=false;$('#speech-heading').focus();}
  spModelsRender();clearInterval(sp.timer);sp.timer=setInterval(spModelsRender,1000);
}
function spSettingsClose(){if(!sp.settings)return;sp.settings=false;clearInterval(sp.timer);$('#speech-settings').hidden=true;const origin=$('#s-'+sp.origin);if(origin)origin.inert=false;if(sp.focus&&sp.focus.isConnected)sp.focus.focus();}
function spModelsRender(){
  if(!sp.settings)return;sp.models=spReadModels();const model=sp.models,box=$('#speech-voices'),focus=box.contains(document.activeElement)?document.activeElement.dataset.speechAction:null;box.replaceChildren();
  for(const voice of model.voices||[]){
    const card=cmEl('div',undefined,'speech-voice'),title=cmEl('h3',voice.label),status=cmEl('p',undefined,'hint'),actions=cmEl('div',undefined,'row');
    const ready=voice.state==='ready',busy=['running','queued'].includes(voice.state);status.textContent=ready?(voice.selected?t('In gebruik'):t('Stem gedownload')):busy?t('Downloaden: {stand}',{stand:voice.size?fmtNum(100*voice.bytes/voice.size,0)+'%':t('bezig')}):voice.error||t('Nog te downloaden: {grootte}',{grootte:fmtSize(voice.size)});
    const button=(label,act)=>{const b=cmEl('button',t(label),'mini');b.dataset.speechAction=voice.id+':'+label;b.onclick=act;actions.append(b);return b;};
    if(ready){if(!voice.selected)button('Gebruiken',()=>spChoose(voice.id));button('Verwijderen',()=>spDelete(voice));}
    else if(busy)button('Downloaden stoppen',()=>{Android.speechDownloadStop(voice.id);spModelsRender();});
    else{const b=button(voice.bytes?'Verder downloaden':'Stem downloaden',()=>{const error=Android.speechDownload(voice.id);if(error)toast(error);spModelsRender();});b.disabled=model.supported===false;}
    card.append(title,status,actions);box.append(card);
  }
  if(focus){const next=[...box.querySelectorAll('button')].find(b=>b.dataset.speechAction===focus);(next||$('#speech-heading')).focus();}
  if(document.activeElement!==$('#speech-speed'))$('#speech-speed').value=model.speed||1;$('#speech-speed-value').textContent=t('{snelheid}×',{snelheid:fmtNum(model.speed||1,1)});
  $('#speech-background').setAttribute('aria-checked',String(model.background!==false));$('#speech-background').classList.toggle('on',model.background!==false);$('#speech-preview').disabled=!model.voice;
  $('#speech-model-status').textContent=model.supported===false?t('Lokale voorleesstemmen werken op een 64-bits toestel'):model.voice?t('Dezelfde stem wordt gebruikt in de e-reader, notities en assistent.'):t('Download een stem en kies daarna opnieuw Voorlezen.');$('#speech-engine').textContent=model.engine||'';
}
function spSave(voice,speed,background){const error=Android.speechConfigure(voice,speed,background);if(error)toast(error);spModelsRender();}
function spChoose(voice){spStop();spSave(voice,sp.models.speed||1,sp.models.background!==false);}
function spSpeed(value){const voice=sp.models.voice||'';spSave(voice,Number(value),sp.models.background!==false);}
function spBackground(){const voice=sp.models.voice||'',on=sp.models.background===false;spSave(voice,sp.models.speed||1,on);if(on&&typeof Android.speechNotifAsk==='function')Android.speechNotifAsk();} // Android 13+: zonder meldingen geen pauzeknop in de melding
function spDelete(voice){spSettingsClose();askConfirm(t('Voorleesstem verwijderen?'),t('Het voorlezen stopt en de stem wordt van je telefoon verwijderd. Je kunt hem later opnieuw downloaden.'),t('Verwijderen'),()=>{Android.speechDelete(voice.id);spSettings();});}
const spOldPause=window.onPauseApp;window.onPauseApp=function(){if(spOldPause)spOldPause();if(sp.active&&sp.active.source!=='reader'){sp.active=null;spSpeechRender();}};
const spOldResume=window.onResumeApp;window.onResumeApp=function(){if(spOldResume)spOldResume();spSync();if(sp.settings)spModelsRender();};
$('#speech-settings').addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();spSettingsClose();}else if(e.key==='Tab'){const nodes=[...e.currentTarget.querySelectorAll('button,input,summary')].filter(n=>!n.disabled&&n.getClientRects().length),first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&(document.activeElement===first||document.activeElement===$('#speech-heading'))){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});

const spOldBack=goBack;goBack=function(){if(sp.settings){spSettingsClose();return true;}return spOldBack();};

setTimeout(spSync,0);

