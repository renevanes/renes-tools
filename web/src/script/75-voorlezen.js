/* Gedeelde voorleesclient. Alleen native stemmen, één actieve aanvraag en geen opslag van tekst/audio.
   Gebruik: spText(tekst, bron, markdown) of spBook(offset, hoofdstuk) (standaard de leesplek; door de hoofdstukken heen);
   spPause/spStop; spSettings. De e-reader (74c-ereader-lezen.js: erSpeakStart, erSpeakEvent) kiest het begin van de zin,
   markeert, bladert mee, bewaart de leesplek en heeft in het boek een eigen balk; onSpeech geeft boekevents aan erSpeakEvent.
   onSpeech(e): {request, source, state, book, chapter, offset, end}. 'playing' = [offset, end) van de tekst of het
   hoofdstuk is nu hoorbaar (één zin: om te markeren en de pagina te volgen); 'progress' = tot offset uitgesproken
   (boek: leesplek bewaard); 'chapter' = volgend hoofdstuk; 'paused' (met error als verder niet lukte), 'done',
   'stopped', 'error'. Snelheid: speechConfigure (geldt vanaf de volgende zin of de zin daarna). Zie docs/voorlezen.md. */
const sp={active:null,seq:0,settings:false,models:{voices:[]},focus:null,origin:'',timer:null};
function spReadModels(){try{return JSON.parse(Android.speechModels());}catch(_){return {voices:[],supported:false};}}
function spAvailable(){if(typeof Android.speechStart!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return false;}sp.models=spReadModels();if(!sp.models.voice){spSettings();return false;}return true;}
function spText(text,source='text',markdown=false){if(!spAvailable())return;if(typeof text!=='string'||!text.trim()){toast(t('Er is geen tekst om voor te lezen'));return;}if(text.length>120000){toast(t('Deze tekst is te lang om in één keer voor te lezen'));return;}const request='lees-'+Date.now()+'-'+(++sp.seq);sp.active={request,source,state:'loading'};spSpeechRender();Android.speechStart(text,request,source,markdown);}
/* Boek voorlezen vanaf een tekstplek (standaard: de leesplek); de e-reader kiest het begin van de zin (erSpeakStart). */
function spBook(offset,chapter){if(!erBook||!spAvailable())return;const ch=Number.isInteger(chapter)?chapter:erIndex,at=Number.isInteger(offset)&&offset>=0?offset:Math.max(0,erAnchor()),request='boek-'+Date.now()+'-'+(++sp.seq);sp.active={request,source:'reader',state:'loading',book:erBook.id,chapter:ch,offset:at,end:at};spSpeechRender();Android.speechBook(erBook.id,ch,at,request);}
function spSelection(){const selection=erSelection;if(!selection||!erText)return;spText(erText.slice(selection.start,selection.end),'selection');}
function spNote(){const n=noteById(noteCur);if(n)spText([n.title,n.text,...(n.items||[]).map(i=>i.text)].filter(Boolean).join('\n'),'note',true);}
// taal-vast: het voorbeeld is in de taal van de gekozen stem, onafhankelijk van de interface
function spPreview(){sp.models=spReadModels();const voice=(sp.models.voices||[]).find(v=>v.id===sp.models.voice);spText(voice&&voice.language==='en'?'This voice reads your books and notes on your phone.':'Deze stem leest je boeken en notities voor op je telefoon.','preview');}
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
  // Alle boekevents, ook buiten het boek (slaaptimer, plek om naar terug te keren): 74c-ereader-lezen.js
  if(e.source==='reader'&&typeof erSpeakEvent==='function')erSpeakEvent(e);
  if(['done','stopped','error'].includes(e.state)){sp.active=null;spSpeechRender();if(e.state==='error'&&e.source!=='ai'){if(!spReadModels().voice)spSettings();toast(e.error||t('Lokaal voorlezen lukt niet; probeer een korter stuk tekst'));}return;}
  if(e.state==='paused'&&e.error)toast(e.error); // verder lukte niet: een andere app (bijv. een gesprek) heeft het geluid
  spSpeechRender();
};
function spSync(){if(typeof Android.speechState!=='function')return;try{const e=JSON.parse(Android.speechState());if(e.active&&!['done','stopped','error'].includes(e.state)){sp.active=e;onSpeech(e);}else{sp.active=null;spSpeechRender();}}catch(_){ }}
function spSettings(){
  if(typeof Android.speechModels!=='function'){toast(t('Installeer de nieuwste APK voor lokaal voorlezen'));return;}
  if(!sp.settings){sp.origin=current;sp.focus=document.activeElement;dialogFocus.base($('#s-'+current),true);sp.settings=true;$('#speech-settings').hidden=false;$('#speech-heading').focus();}
  spModelsRender();clearInterval(sp.timer);sp.timer=setInterval(spModelsRender,1000);
}
function spSettingsClose(){if(!sp.settings)return;sp.settings=false;clearInterval(sp.timer);$('#speech-settings').hidden=true;const origin=$('#s-'+sp.origin);if(origin)dialogFocus.base(origin,false);if(sp.focus&&sp.focus.isConnected)sp.focus.focus();}
function spModelsRender(){
  if(!sp.settings)return;sp.models=spReadModels();const model=sp.models,box=$('#speech-voices'),f=document.activeElement,line=box.contains(f)&&f.closest('[data-voice]'),focus=line?[line.dataset.voice,f.getAttribute('onclick')]:null;
  // Dezelfde stemregels als bij Modellen en Spraak (53-modellen.js)
  box.innerHTML=mdVoicesHtml(model);
  if(focus){const next=[...box.querySelectorAll('[data-voice] button')].find(b=>b.closest('[data-voice]').dataset.voice===focus[0]&&b.getAttribute('onclick')===focus[1])||box.querySelector('[data-voice="'+CSS.escape(focus[0])+'"] button');(next||$('#speech-heading')).focus();}
  spControlsRender(model,'speech');
  $('#speech-model-status').textContent=model.supported===false?t('Lokale voorleesstemmen werken op een 64-bits toestel'):model.voice?t('Dezelfde stem wordt overal in Tools gebruikt.'):t('Download een stem en kies daarna opnieuw Voorlezen.');$('#speech-engine').textContent=model.engine||'';
}
/** Snelheid, achtergrond en proberen: hetzelfde in het stemmenvenster (prefix speech) en op Spraak (prefix sv). */
function spControlsRender(model,pre){
  const speed=$('#'+pre+'-speed');if(!speed)return;if(document.activeElement!==speed)speed.value=model.speed||1;$('#'+pre+'-speed-value').textContent=t('{snelheid}×',{snelheid:fmtNum(model.speed||1,1)});
  const bg=$('#'+pre+'-background');bg.setAttribute('aria-checked',String(model.background!==false));bg.classList.toggle('on',model.background!==false);$('#'+pre+'-preview').disabled=!model.voice;
}
/** Na een wijziging in de stemmen: elke plek die ze toont opnieuw tekenen. */
function spRefreshAll(){spModelsRender();if(typeof mdRefresh==='function'&&(current==='models'||current==='spraak'))mdRefresh();if(typeof erSpeakRender==='function')erSpeakRender();}
function spDownload(id){const error=Android.speechDownload(id);if(error)toast(error);spRefreshAll();}
function spDownloadStop(id){Android.speechDownloadStop(id);spRefreshAll();}
function spSave(voice,speed,background){const error=Android.speechConfigure(voice,speed,background);if(error)toast(error);spRefreshAll();}
function spChoose(voice){sp.models=spReadModels();spStop();spSave(voice,sp.models.speed||1,sp.models.background!==false);}
function spSpeed(value){sp.models=spReadModels();const voice=sp.models.voice||'';spSave(voice,Number(value),sp.models.background!==false);}
function spBackground(){sp.models=spReadModels();const voice=sp.models.voice||'',on=sp.models.background===false;spSave(voice,sp.models.speed||1,on);if(on&&typeof Android.speechNotifAsk==='function')Android.speechNotifAsk();} // Android 13+: zonder meldingen geen pauzeknop in de melding
function spDelete(id){const open=sp.settings;if(open)spSettingsClose();askConfirm(t('Voorleesstem verwijderen?'),t('Het voorlezen stopt en de stem wordt van je telefoon verwijderd. Je kunt hem later opnieuw downloaden.'),t('Verwijderen'),()=>{Android.speechDelete(id);if(open)spSettings();spRefreshAll();});}

/* ---------- Spraak (Instellingen → Spraak): één plek voor voorlezen en spraak naar tekst ----------
   Stem, snelheid en achtergrond; daaronder dezelfde kaarten als bij Modellen (stemmen en spraakmodellen). */
function enterSpraak(){mdLoad();svRender();mdRefresh();}
function svRender(){
  if(current!=='spraak')return;
  if(typeof Android.speechModels!=='function'){$('#sv-voice-card').hidden=true;}
  const model=spReadModels(),ready=mdVoiceSort(model.voices).filter(v=>v.state==='ready'),sel=$('#sv-voice');
  sel.replaceChildren(...(ready.length?ready.map(v=>{const o=cmEl('option',v.label+' · '+mdVoiceQ(v));o.value=v.id;o.selected=v.id===model.voice;return o;}):[(()=>{const o=cmEl('option',t('Nog geen stem gedownload'));o.value='';return o;})()]));
  sel.disabled=!ready.length;
  $('#sv-stand').textContent=model.supported===false?t('Lokale voorleesstemmen werken op een 64-bits toestel'):ready.length?t('Dezelfde stem wordt overal in Tools gebruikt. Een boek in een andere taal krijgt vanzelf je stem in die taal.'):t('Download hieronder een stem; daarna werkt voorlezen zonder internet.');
  spControlsRender(model,'sv');
  if(mdSt&&mdSt.models)uiHtml($('#sv-cats'),['tts','spraak'].map(k=>mdCatCard(MD_CATS.find(c=>c.kind===k))).join(''));
}
const spOldPause=window.onPauseApp;window.onPauseApp=function(){if(spOldPause)spOldPause();if(sp.active&&sp.active.source!=='reader'){sp.active=null;spSpeechRender();}};
const spOldResume=window.onResumeApp;window.onResumeApp=function(){if(spOldResume)spOldResume();spSync();if(sp.settings)spModelsRender();};
$('#speech-settings').addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();spSettingsClose();}else if(e.key==='Tab'){const nodes=[...e.currentTarget.querySelectorAll('button,input,summary')].filter(n=>!n.disabled&&n.getClientRects().length),first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&(document.activeElement===first||document.activeElement===$('#speech-heading'))){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});

const spOldBack=goBack;goBack=function(){if(sp.settings){spSettingsClose();return true;}return spOldBack();};

setTimeout(spSync,0);

