/* Gevolgde Spotify-podcasts eenmalig of opnieuw overnemen als openbare abonnementen. */
let pspRows=[],pspSource='',pspAt=0,pspLoaded=false,pspReading=0,pspSerial=0,pspRunning=false,pspActive=null,pspTimer=null,pspLastLookup=0,pspAdding=false,pspRelink=false;
const PSP_LOOKUP_INTERVAL=3200;
(function(){const previous=window.onPauseApp;window.onPauseApp=function(){if(previous)previous();if(current==='podspotify')pspPause();};})();
document.addEventListener('visibilitychange',()=>{if(document.hidden&&current==='podspotify')pspPause();});
function pspNorm(s){return String(s||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9\u00c0-\uffff]/g,'');}
function pspFeed(feed){try{const u=new URL(feed);if(!['http:','https:'].includes(u.protocol)||u.username||u.password||/(^|\.)spotify\.com$/i.test(u.hostname))return '';u.hash='';return u.href;}catch(e){return '';}}
function pspMessage(text,bad){const e=$('#psp-message');e.textContent=text;e.style.display=text?'block':'none';e.classList.toggle('error',!!bad);e.classList.toggle('answered',!!text&&!bad);}
function pspOpen(source){show('podspotify');if(source||!pspLoaded)pspLoad(source||(rdJson(Android.spotifyState(),{}).linked?'live':'backup'));}
function enterPodSpotify(){pspRender();}
function pspBusy(){return !!pspReading||pspAdding||!!pspActive||pspRunning;}
function pspLoad(source){
 if(pspBusy())return;
 pspReading=++pspSerial;pspRelink=false;pspMessage('Podcastlijst ophalen…');pspRender();
 Android.spotifyPodcastShows(source==='backup',pspReading);
}
window.onSpotifyPodcasts=function(r){
 if(!pspReading||r.request!==pspReading)return;pspReading=0;
 if(r.error){pspRelink=!!r.relink;pspMessage(r.error,true);pspRender();return;}
 const remembered=store.get('spotifyPodcastFeeds',{}),seen=new Set();
 pspRows=(r.shows||[]).filter(s=>/^[a-zA-Z0-9]{22}$/.test(s.id||'')&&!seen.has(s.id)&&seen.add(s.id)).map(s=>{
  const old=remembered&&remembered[s.id],choice=old&&pspFeed(old.feed)?Object.assign({},old,{feed:pspFeed(old.feed)}):null;
  return {id:s.id,title:String(s.title||'Podcast'),query:String(s.title||'Podcast'),author:String(s.author||''),choice,candidates:choice?[choice]:[],selected:!!choice,searched:!!choice,error:'',revision:0};
 });
 pspSource=r.source;pspAt=r.at;pspLoaded=true;pspMessage(pspRows.length?'Lijst opgehaald. Zoek de openbare versies en controleer je selectie.':'Je Spotify-lijst bevat geen gevolgde podcasts.');pspRender();
};
function pspExisting(){return new Set(rdJson(Android.podSubs(),[]).map(p=>pspFeed(p.feed)).filter(Boolean));}
function pspOptions(row){return '<option value="">Kies een openbare versie</option>'+row.candidates.map((p,i)=>'<option value="'+i+'"'+(row.choice&&row.choice.feed===p.feed?' selected':'')+'>'+esc(p.title+(p.author?' — '+p.author:''))+'</option>').join('');}
function pspRender(){
 const linked=!!rdJson(Android.spotifyState(),{}).linked,busy=pspBusy(),existing=pspExisting();
 $('#psp-live').disabled=!linked||busy;$('#psp-backup').disabled=busy;$('#psp-link').hidden=linked&&!pspRelink;
 $('#psp-source').textContent=pspLoaded?(pspSource==='backup'?'Lijst uit je Spotify-backup':'Huidige Spotify-lijst')+(pspAt?' · '+new Date(pspAt).toLocaleString('nl-NL'):''):'Je kunt ook een eerder bewaarde Spotify-backup gebruiken.';
 $('#psp-work').hidden=!pspLoaded||!pspRows.length;
 const pending=pspRows.filter(r=>!r.searched&&!r.choice).length;
 $('#psp-find').disabled=busy||!pending;$('#psp-find').textContent=pending?'Openbare versies zoeken ('+pending+')':'Openbare versies gezocht';
 $('#psp-pause').hidden=!pspRunning;$('#psp-pause').disabled=pspAdding;
 $('#psp-progress').textContent=pspActive?'Zoeken: '+pspRows[pspActive.index].title:pspRunning?'Volgende podcast zoeken…':pspLoaded?pspRows.filter(r=>r.searched).length+' van '+pspRows.length+' podcasts gecontroleerd':'';
 const opened=new Set([...document.querySelectorAll('#psp-list details[open]')].map(e=>e.dataset.id));
 const active=document.activeElement,focus=active&&active.closest('#psp-list')?{id:active.id,start:active.selectionStart,end:active.selectionEnd}:null;
 $('#psp-list').innerHTML=pspRows.map((r,i)=>{
  const has=r.choice&&existing.has(r.choice.feed),matched=!!r.choice;
  const state=has?'Al in Mijn podcasts':matched?r.choice.title+(r.choice.author?' · '+r.choice.author:''):r.error||(!r.searched?'Nog geen openbare versie gezocht':r.candidates.length?'Kies de juiste openbare versie':'Geen openbare versie gevonden');
  return '<article class="psp-row" data-index="'+i+'"><label class="psp-title"><input type="checkbox" id="psp-pick-'+i+'" data-pick="'+i+'" onchange="pspPick('+i+',this.checked)"'+(matched&&r.selected&&!has?' checked':'')+(!matched||has||pspAdding||pspReading?' disabled':'')+' aria-label="'+esc(r.title)+' overnemen"><span><b>'+esc(r.title)+'</b><small>'+esc(r.author)+'</small></span></label><p class="psp-state'+(matched?' ready':'')+'">'+esc(state)+'</p>'+
  '<details data-id="'+r.id+'"'+(opened.has(r.id)?' open':'')+'><summary id="psp-details-'+i+'">'+(matched?'Koppeling controleren':'Versie kiezen of zoeken')+'</summary><label>Openbare versie<select class="sel" id="psp-version-'+i+'" aria-label="Openbare versie voor '+esc(r.title)+'" onchange="pspChoose('+i+',this.value)"'+(pspAdding||pspReading?' disabled':'')+'>'+pspOptions(r)+'</select></label><label for="psp-q-'+i+'">Zoek op titel of maker</label><div class="psp-lookup"><input class="search" type="search" id="psp-q-'+i+'" value="'+esc(r.query)+'" oninput="pspRows['+i+'].query=this.value" maxlength="300"><button class="mini" onclick="pspSearchOne('+i+')"'+(busy?' disabled':'')+'>Zoeken</button></div><button class="linkish" onclick="pspSpotify('+i+')">Bekijken op Spotify</button></details></article>';
 }).join('');
 pspFilter();pspCount();
 if(focus&&focus.id){const e=document.getElementById(focus.id);if(e&&!e.disabled){e.focus({preventScroll:true});if(focus.start!=null&&e.setSelectionRange)e.setSelectionRange(focus.start,focus.end);}}
}
function pspFilter(){const q=pspNorm($('#psp-filter').value);document.querySelectorAll('#psp-list .psp-row').forEach(e=>{const r=pspRows[+e.dataset.index];e.hidden=!!q&&!pspNorm(r.title+' '+r.author).includes(q);});}
function pspCount(){
 const existing=pspExisting(),n=new Set(pspRows.filter(r=>r.choice&&r.selected&&!existing.has(r.choice.feed)).map(r=>r.choice.feed)).size;
 $('#psp-count').textContent=n+' geselecteerd';$('#psp-add').textContent=pspAdding?'Overnemen…':'Overnemen'+(n?' ('+n+')':'');$('#psp-add').disabled=!n||pspBusy();
}
function pspPick(i,on){if(pspAdding||pspReading)return;const r=pspRows[i];if(r&&r.choice){r.selected=!!on;r.revision++;}pspCount();}
function pspSelect(on){if(pspAdding||pspReading)return;pspRows.forEach(r=>{r.selected=!!r.choice&&on;r.revision++;});pspRender();}
function pspChoose(i,value){if(pspAdding||pspReading)return;const r=pspRows[i];if(!r)return;r.choice=value===''?null:r.candidates[+value]||null;r.selected=!!r.choice;r.revision++;pspRender();}
function pspSpotify(i){const r=pspRows[i];if(r)Android.openUrl('https://open.spotify.com/show/'+r.id);}
function pspFind(){if(pspBusy())return;pspRunning=true;pspMessage('De app zoekt openbare versies. Controleer daarna titel en maker voordat je ze overneemt.');pspPump();}
function pspPause(){pspRunning=false;if(pspTimer){clearTimeout(pspTimer);pspTimer=null;pspActive=null;}pspRender();}
function pspPump(){
 if(!pspRunning||current!=='podspotify'){pspPause();return;}
 const i=pspRows.findIndex(r=>!r.searched&&!r.choice);
 if(i<0){pspRunning=false;pspMessage('Zoeken afgerond. Controleer de koppelingen; bij twijfel kun je zelf een versie kiezen.');pspRender();return;}
 pspSchedule(i,pspRows[i].query);
}
function pspSearchOne(i){if(pspBusy())return;const q=$('#psp-q-'+i).value.trim();if(!q){pspMessage('Vul een zoekterm in.',true);return;}pspSchedule(i,q);}
function pspSchedule(i,q){
 clearTimeout(pspTimer);const request=++pspSerial;pspActive={index:i,request,revision:pspRows[i].revision};pspRender();
 pspTimer=setTimeout(()=>{pspTimer=null;pspLastLookup=Date.now();Android.spotifyPodcastSearch(pspRows[i].id,String(q).slice(0,300),request);},Math.max(0,PSP_LOOKUP_INTERVAL-(Date.now()-pspLastLookup)));
}
window.onSpotifyPodcastSearch=function(r){
 const a=pspActive;if(!a||r.request!==a.request||r.id!==pspRows[a.index].id)return;
 const row=pspRows[a.index];pspActive=null;row.searched=true;row.error=r.error||'';
 if(!r.error){
  const feeds=new Set();row.candidates=(r.results||[]).filter(p=>{const f=pspFeed(p.feed);if(!f||feeds.has(f))return false;feeds.add(f);p.feed=f;return true;}).map(p=>pcSlimPod(p));
  const exact=row.candidates.filter(p=>pspNorm(p.title)===pspNorm(row.title)&&pspNorm(row.author)&&pspNorm(p.author)===pspNorm(row.author));
  if(exact.length===1&&row.revision===a.revision){row.choice=exact[0];row.selected=true;}
  if(row.choice&&!row.candidates.some(p=>p.feed===row.choice.feed)){row.candidates.unshift(row.choice);}
 }
 pspRender();if(pspRunning)pspPump();
};
function pspImport(){
 if(pspBusy())return;const existing=pspExisting(),seen=new Set();
 const picked=pspRows.filter(r=>r.selected&&r.choice&&!existing.has(r.choice.feed)&&!seen.has(r.choice.feed)&&seen.add(r.choice.feed));if(!picked.length)return;
 pspAdding=true;pspRender();const confirmed=new Set(),errors=[];let n=0;
 const next=()=>{
  const r=picked[n++];if(r){let error='';try{error=Android.podSubscribe(JSON.stringify(pcSlimPod(r.choice)))||'';}catch(e){error='Opslaan lukt niet';}
   if(error)errors.push(r.title+': '+error);else confirmed.add(r.choice.feed);setTimeout(next,0);return;}
  const saved=store.get('spotifyPodcastFeeds',{})||{};pspRows.forEach(r=>{if(r.choice&&confirmed.has(r.choice.feed))saved[r.id]=pcSlimPod(r.choice);});store.set('spotifyPodcastFeeds',saved);
  pspAdding=false;pspRender();pcRenderSubs();refreshPodTile();
  pspMessage(confirmed.size+' '+(confirmed.size===1?'podcast toegevoegd':'podcasts toegevoegd')+' aan Mijn podcasts.'+(errors.length?' '+errors.length+' niet opgeslagen. '+errors[0]:' Je kunt ze daar openen en afspelen.'),!!errors.length);
 };setTimeout(next,0);
}
