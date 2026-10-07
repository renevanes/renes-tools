/* In een gewone browser (voorbeeld/test) bestaat de Android-brug niet: dan een nep-brug. */
if (!window.Android) window.Android = (function(){
  let st = {running:false, phase:'idle'}, t0 = 0, timer = null;
  const tick = () => {
    const el = Date.now() - t0, per = st.interval*1000 + 6000, a = Math.floor(el/per)+1, inCall = (el%per) < 6000;
    if (st.maxAttempts && a > st.maxAttempts) { st = {...st, running:false, phase:'done', result:'maxed', message:'Niet opgenomen na '+st.maxAttempts+' pogingen'}; clearInterval(timer); return; }
    st.attempt = a; st.phase = inCall ? 'incall' : 'waiting'; st.message = inCall ? 'Gaat over…' : 'Niet opgenomen';
    st.nextAt = t0 + (a-1)*per + per; st.now = Date.now();
  };
  return {
    version:()=>JSON.stringify({appName:VERSION_NAME,appCode:VERSION_CODE,nativeLevel:1,webName:VERSION_NAME,webCode:VERSION_CODE,android:'test',sdk:0}),
    lastManifest:()=>'null', checkUpdates:()=>setTimeout(()=>onUpdateStatus({state:'uptodate',manual:true,versionName:VERSION_NAME}),400),
    installUpdate(){}, permissions:()=>JSON.stringify({call:true,phoneState:true,callLog:true,notifications:true}),
    requestPermissions(){}, openAppSettings(){}, pickContact(){ onContactPicked({number:'010 123 4567', name:'Huisarts'}); },
    setBars(){}, vibrate(){}, toast(m){ toast(m); }, pendingOpen:()=>'',
    redialStart(n,name,a,i){ st={running:true,number:n,name,attempt:1,maxAttempts:a,interval:i,phase:'dialing',message:'Bellen…'}; t0=Date.now(); timer=setInterval(tick,300); return ''; },
    redialStart2(n,name,a,i,rmin,rmax){ const r=this.redialStart(n,name,a,rmin?rmin:i); st.randomMin=rmin; st.randomMax=rmax; return r; },
    redialStop(){ clearInterval(timer); st={...st,running:false,phase:'done',result:'stopped',message:'Gestopt'}; },
    redialNow(){}, redialStatus:()=>JSON.stringify({...st, now:Date.now()}),
    _recorder:{busy:false,recording:false,autoEnabled:false,automatic:false,mic:false,notifications:false,phone:false,rows:[],message:'',elapsed:0},
    recorderState(){return JSON.stringify({...this._recorder,autoReady:this._recorder.mic&&this._recorder.phone&&this._recorder.notifications,dest:this._wa.info.dest,total:this._recorder.rows.length});},
    recorderRequestPermissions(){this._recorder.mic=true;this._recorder.notifications=true;this._recorder.phone=true;},
    recorderSetAuto(on){const r=this._recorder;if(on&&(!r.mic||!r.phone||!r.notifications))return 'Geef eerst microfoon- en telefoontoegang en schakel meldingen in';r.autoEnabled=on;if(!on&&r.automatic&&r.busy){this.recorderStop();r.automatic=false;}return '';},
    recorderStart(){if(!this._recorder.mic||!this._recorder.notifications){window.onRecorderMessage('Geef eerst toestemming');return;}this._recorder.busy=true;this._recorder.recording=true;this._recorder.automatic=false;this._recorder.message='';},
    recorderStop(){const r=this._recorder;r.busy=false;r.recording=false;r.automatic=false;r.message='Opname opgeslagen.';r.rows.unshift({id:'opname-1791244800000-0123456789abcdef0123456789abcdef.m4a',date:Date.now(),size:12000,duration:3000});},
    recorderPlay(id){this._recorder.playing=id;return '';},recorderStopPlayback(){this._recorder.playing=null;},
    recorderShare(id){this._recorder.shared=id;},
    recorderDelete(id){this._recorder.rows=this._recorder.rows.filter(r=>r.id!==id);return '';},
    recorderExport(id){if(!this._wa.info.dest)return 'Kies eerst een backup-map';this._recorder.exported=id;setTimeout(()=>window.onRecorderMessage('✓ Opname geëxporteerd naar Gespreksopnames'),50);return '';},
    _historyEnabled:false, _historyAllowed:false,
    _historyRows:[{time:Date.now(), package:'com.whatsapp', app:'WhatsApp', title:'Familie', text:'Tot vanavond!'}, {time:Date.now()-60000, package:'com.example.mail', app:'Mail', title:'Afspraak', text:'Je afspraak is bevestigd.'}],
    historyList(q){ const rows=this._historyRows.filter(r => (r.app+' '+r.package+' '+r.title+' '+r.text).toLowerCase().includes((q||'').toLowerCase())); return JSON.stringify({rows, count:rows.length, total:this._historyRows.length, enabled:this._historyEnabled, allowed:this._historyAllowed, connected:this._historyAllowed, dest:this._wa.info.dest}); },
    historySetEnabled(on){this._historyEnabled=on;},
    historyApps(){ const m = {}; for (const r of this._historyRows) { m[r.package] = m[r.package] || {package: r.package, app: r.app, count: 0}; m[r.package].count++; } return JSON.stringify(Object.values(m).sort((a, b) => b.count - a.count)); },
    historyListBy(q, pkg, limit){ const all = this._historyRows.filter(r => (!pkg || r.package === pkg) && (r.app+' '+r.package+' '+r.title+' '+r.text).toLowerCase().includes((q||'').toLowerCase())); const rows = all.slice(0, limit); return JSON.stringify({rows, count: all.length, total: this._historyRows.length, enabled: this._historyEnabled, allowed: this._historyAllowed, connected: this._historyAllowed, dest: this._wa.info.dest}); },
    historyRequestPermission(){this._historyAllowed=true;},
    historyClear(){this._historyRows=[];return '';},
    historyExport(){if(!this._wa.info.dest)return 'Kies eerst een backup-map';setTimeout(()=>window.onHistoryExport(this._historyRows.length ? '✓ '+this._historyRows.length+' meldingen geëxporteerd naar Meldingen backup' : 'Nog geen meldingen om te exporteren'),50);return '';},
    _wa:{info:{filesAccess:false,sources:[{name:'WhatsApp',path:'/storage/emulated/0/Android/media/com.whatsapp/WhatsApp'}],dest:false,destName:null,auto:false,autoCharging:true,autoMode:'all',sel:'chats,images,voice,documents',nextAuto:0,lastOk:0,history:[]}, st:{running:false,phase:'idle'}, t:null},
    waInfo(){ return JSON.stringify(this._wa.info); },
    waRequestFilesAccess(){ this._wa.info.filesAccess=true; setTimeout(()=>onWaChanged(),100); },
    waPickFolder(){ Object.assign(this._wa.info,{dest:true,destName:'USB-stick Rene'}); setTimeout(()=>onWaChanged(),100); },
    waScanSizes(){ setTimeout(()=>onWaSizes({chats:{bytes:48e6,files:9},images:{bytes:3.1e9,files:8200},video:{bytes:5.6e9,files:610},voice:{bytes:420e6,files:3100},audio:{bytes:90e6,files:80},documents:{bytes:800e6,files:450},stickers:{bytes:60e6,files:900},other:{bytes:20e6,files:120},statuses:{bytes:150e6,files:40}}),300); },
    waSaveSettings(a,c,m,sel){ Object.assign(this._wa.info,{auto:a,autoCharging:c,autoMode:m,sel,nextAuto:a?Date.now()+3600e3*14:0}); },
    waStart(mode,cats){ const w=this._wa; w.st={running:true,mode,phase:'copy',filesTotal:2400,filesDone:0,bytesTotal:2.4e9,bytesDone:0,current:'IMG-20260930-WA0001.jpg'};
      w.t=setInterval(()=>{ w.st.filesDone+=60; w.st.bytesDone+=6e7; if(w.st.filesDone>=2400){ clearInterval(w.t); w.st={running:false,phase:'done',mode,result:'ok',message:'2400 bestanden gekopieerd',bytesDone:2.4e9,finishedAt:Date.now()}; w.info.history.unshift(w.st); } },200); return ''; },
    waRestore(){ return this.waStart('restore'); },
    waCancel(){ const w=this._wa; clearInterval(w.t); w.st={running:false,phase:'done',mode:w.st.mode,result:'cancelled',message:'Gestopt',finishedAt:Date.now()}; },
    waStatus(){ return JSON.stringify(this._wa.st); },
    _rd:{hasKey:false,auto:true,contacts:false,chats:[],msgs:{}},
    _mkDemo(){ const r=this._rd; const names=['Huisarts','Familie Van Es','Jos','Pietje Puk','Sportclub']; r.chats=[]; r.msgs={};
      for(let i=0;i<5;i++){ const id=i+1,n=40+i*20,grp=i===1||i===4; const ms=[]; let t=Date.now()-n*36e5;
        for(let k=0;k<n;k++){ const me=k%2===0; t+=Math.random()*72e5;
          let o={id:k+1,me,t:Math.round(t),type:0,text:'Voorbeeldbericht '+(k+1)+' in '+names[i],label:'',sender:me?'Ik':(grp?['Jos','Anne','Kees'][k%3]:names[i]),media:'',mime:'',fileName:''};
          if(k%13===4){o.type=1;o.label='📷 Foto';o.text='';o.media='Media/WhatsApp Images/x.jpg';o.mime='image/jpeg';}
          if(k%17===3){o.type=2;o.label='🎤 Spraakbericht';o.text='';o.media='Media/WhatsApp Voice Notes/x.opus';}
          ms.push(o); }
        r.msgs[id]=ms; const last=ms[ms.length-1];
        r.chats.push({id,name:names[i],group:grp,n,last:last.t,lastText:last.text||last.label}); }
      r.chats.sort((a,b)=>b.last-a.last); },
    waReadInfo(){ const r=this._rd; return JSON.stringify({hasKey:r.hasKey,auto:r.auto,hasDb:r.hasKey,dbFrom:r.hasKey?Date.now()-72e5:0,dest:this._wa.info.dest,contacts:r.contacts}); },
    waSetKey(hex){ setTimeout(()=>{ const h=(hex||'').replace(/[^0-9a-f]/gi,''); if(h.length!==64){ onWaKeyResult({ok:false,error:'De sleutel moet 64 tekens hebben; je hebt er '+h.length}); return; } this._rd.hasKey=true; this._mkDemo(); onWaKeyResult({ok:true,chats:this._rd.chats.length}); },500); },
    waForgetKey(){ this._rd.hasKey=false; this._rd.chats=[]; },
    waSetReadableAuto(on){ this._rd.auto=on; },
    waMakeReadable(){ const w=this._wa; w.st={running:true,mode:'readable',phase:'export',filesTotal:5,filesDone:0}; let i=0;
      w.t=setInterval(()=>{ w.st.filesDone=++i; if(i>=5){ clearInterval(w.t); w.st={running:false,phase:'done',mode:'readable',result:'ok',message:'5 chats leesbaar gemaakt',finishedAt:Date.now()}; } },300); return ''; },
    waChats(){ return JSON.stringify({chats:this._rd.chats,from:Date.now()-72e5}); },
    waMessages(id,before,limit){ const all=this._rd.msgs[id]||[]; const b=+before; const f=all.filter(m=>b<=0||m.t<b); return JSON.stringify(f.slice(Math.max(0,f.length-limit))); },
    waSearch(q){ const out=[]; const ql=q.toLowerCase(); for(const c of this._rd.chats){ for(const m of this._rd.msgs[c.id]){ if(m.text&&m.text.toLowerCase().includes(ql)){ out.push({...m,chat:c.id,chatName:c.name}); } } } return JSON.stringify(out.slice(0,200)); },
    waRequestContacts(){ this._rd.contacts=true; setTimeout(()=>onWaChanged(),100); },
    _tr:{perm:false,gps:true,running:false,paused:false,list:[],t:null,start:0,dist:0,pts:0,just:null},
    trackHasPermission(){ return this._tr.perm; },
    trackRequestPermission(){ this._tr.perm=true; setTimeout(()=>onTrackPerm(),200); },
    trackGpsOn(){ return this._tr.gps; },
    trackOpenLocationSettings(){ this._tr.gps=true; },
    trackStart(){ const t=this._tr; if(!t.perm) return 'Geef eerst toestemming voor je locatie'; t.running=true;t.paused=false;t.start=Date.now();t.dist=0;t.pts=0;t.path='';
      let lon=4.47917; const lat=51.9225;
      t.t=setInterval(()=>{ if(t.paused)return; lon+=0.00008; t.dist+=6*2; t.pts++; t.lat=lat; t.lon=lon; t.acc=5+Math.random()*4; t.ele=5+Math.sin(t.pts/15)*3;
        t.pathpts=(t.pathpts||[]); t.pathpts.push([lon,lat+Math.sin(t.pts/20)*0.0006]); },2000); return ''; },
    trackPause(){ this._tr.paused=true; }, trackResume(){ this._tr.paused=false; },
    trackStop(){ const t=this._tr; clearInterval(t.t); t.running=false; t.just={points:t.pts,stats:{distance:t.dist,movingMs:t.pts*2000,totalMs:Date.now()-t.start,avgSpeed:6,maxSpeed:7.2,eleGain:42,eleLoss:40}}; },
    trackStatus(){ const t=this._tr; let path=''; if(t.pathpts&&t.pathpts.length>1){ const xs=t.pathpts.map(p=>p[0]),ys=t.pathpts.map(p=>p[1]);const mnx=Math.min(...xs),mxx=Math.max(...xs),mny=Math.min(...ys),mxy=Math.max(...ys);const sx=280/((mxx-mnx)||1e-9),sy=140/((mxy-mny)||1e-9),s=Math.min(sx,sy); path=t.pathpts.map((p,i)=>(i?'L':'M')+(10+(p[0]-mnx)*s).toFixed(1)+' '+(150-(p[1]-mny)*s).toFixed(1)).join(' '); }
      return JSON.stringify({running:t.running,paused:t.paused,startT:t.start,distance:t.dist,maxSpeed:7.2,movingMs:t.pts*2000,points:t.pts,now:Date.now(),lat:t.lat,lon:t.lon,acc:t.acc,ele:t.ele,error:'',path}); },
    trackJustStopped(){ return this._tr.just?JSON.stringify(this._tr.just):''; },
    trackLivePoints(){ const t=this._tr; return JSON.stringify((t.pathpts||[]).map(p=>[Math.round(p[1]*1e6)/1e6, Math.round(p[0]*1e6)/1e6])); },
    openUrl(u){ toast('Zou openen: '+u.slice(0,40)); },
    _sms:{perm:false,contacts:false,convs:null,st:{running:false},done:false},
    _smsDemo(){ const names=['Bank','Moeder','Pakketdienst','+31 6 12345678','Tandarts']; const convs=[]; const msgs={};
      for(let i=0;i<5;i++){ const th=i+1,n=8+i*5; const l=[]; let t=Date.now()-n*36e5;
        for(let k=0;k<n;k++){ t+=Math.random()*54e5; l.push({id:k+1,me:k%3===0,t:Math.round(t),text:'Voorbeeld-sms '+(k+1)+' met '+names[i],type:k%3===0?2:1}); }
        msgs[th]=l; const last=l[l.length-1]; convs.push({thread:th,name:names[i],count:n,last:last.t,lastText:last.text}); }
      convs.sort((a,b)=>b.last-a.last); this._sms.convs=convs; this._sms.msgs=msgs; },
    smsHasPermission(){ return this._sms.perm; },
    smsRequestPermission(){ this._sms.perm=true; this._smsDemo(); setTimeout(()=>onSmsChanged(),150); },
    smsRequestContacts(){ this._sms.contacts=true; setTimeout(()=>onSmsChanged(),100); },
    smsInfo(){ const s=this._sms; return JSON.stringify({perm:s.perm,dest:this._wa.info.dest,contacts:s.contacts,lastExport:s.done?Date.now()-3600e3:0,lastCount:s.done?31:0,total:s.perm?31:0}); },
    smsConversations(){ const s=this._sms; return JSON.stringify({conversations:s.convs||[],total:31}); },
    smsMessages(th){ return JSON.stringify((this._sms.msgs[th]||[])); },
    smsSearch(q){ const out=[]; const ql=q.toLowerCase(); const s=this._sms; for(const c of s.convs){ for(const m of s.msgs[c.thread]){ if(m.text.toLowerCase().includes(ql)) out.push({...m,name:c.name,thread:c.thread}); } } return JSON.stringify(out.slice(0,200)); },
    smsExport(){ const s=this._sms; if(!s.perm) return 'Geef eerst toegang tot sms'; if(!this._wa.info.dest) return 'Kies eerst een backup-map (bij WhatsApp backup)';
      s.st={running:true,done:0,total:31}; let i=0; s.t=setInterval(()=>{ i+=8; s.st={running:true,done:Math.min(i,31),total:31}; if(i>=31){ clearInterval(s.t); s.done=true; s.st={running:false,ok:true,count:31}; } },250); return ''; },
    smsStatus(){ return JSON.stringify(this._sms.st); },
    _calls:{perm:false,contacts:false,st:{running:false},done:false,list:null},
    _callsDemo(){ const ppl=[['Moeder','06 11122233','k1',1,true],['Huisarts','010 123 4567','k3',3,false],['','+31 20 765 4321'],['Jos de Vries','010 999 8888','k2',2,false]]; const kinds=[['in','Inkomend'],['out','Uitgaand'],['missed','Gemist']]; const l=[]; let t=Date.now();
      for(let i=0;i<42;i++){ t-=Math.random()*9e6; const p=ppl[i%4], k=kinds[(i*7)%3]; l.push({name:p[0]||p[1],number:p[1],date:Math.round(t),duration:k[0]==='missed'?0:Math.round(Math.random()*900),kind:k[0],label:k[1],ck:p[2],cid:p[3],fav:p[4]}); } this._calls.list=l; },
    callsHasPermission(){ return this._calls.perm; },
    callsRequestPermission(){ this._calls.perm=true; this._calls.contacts=true; this._callsDemo(); setTimeout(()=>onCallsChanged(),150); },
    callsInfo(){ const s=this._calls; return JSON.stringify({perm:s.perm,dest:this._wa.info.dest,contacts:s.contacts,lastExport:s.done?Date.now()-3600e3:0,lastCount:s.done?42:0,total:s.perm?42:0}); },
    callsList(j){ const f=JSON.parse(j||'{}'); const all=this._calls.list||[]; const ql=(f.q||'').toLowerCase(); const nk=n=>(n||'').replace(/\D/g,'').slice(-9);
      const nums=(f.numbers||[]).map(nk);
      const c=all.filter(x=>(!f.kind||f.kind==='all'||x.kind===f.kind)&&(!ql||x.name.toLowerCase().includes(ql)||x.number.includes(ql))&&(!f.from||x.date>=f.from)&&(!f.to||x.date<f.to)
        &&(f.minDur==null||x.duration>=f.minDur)&&(f.maxDur==null||x.duration<=f.maxDur)&&(!f.who||f.who==='all'||(f.who==='contacts'?!!x.ck:f.who==='unknown'?!x.ck:!!x.fav))&&(!nums.length||nums.includes(nk(x.number))));
      const sum=k=>c.filter(x=>x.kind===k); const active=Object.keys(f).some(k=>k==='kind'?f.kind!=='all':k==='who'?f.who!=='all':k==='q'?!!f.q:true);
      return JSON.stringify({calls:c,total:all.length,count:c.length,people:new Set(c.map(x=>nk(x.number))).size,filtered:active,in:sum('in').length,out:sum('out').length,missed:sum('missed').length,
        secIn:sum('in').reduce((a,x)=>a+x.duration,0),secOut:sum('out').reduce((a,x)=>a+x.duration,0),first:c.length?c[c.length-1].date:0,last:c.length?c[0].date:0,contacts:true}); },
    contactsAddNumber(n){ toast('(contacten-app: nieuw contact met '+n+')'); },
    callsExport(j){ const s=this._calls; if(!s.perm) return 'Geef eerst toegang tot de oproepgeschiedenis'; if(!this._wa.info.dest) return 'Kies eerst een backup-map (bij WhatsApp backup)';
      s.st={running:true,done:0,total:126}; let i=0; s.t=setInterval(()=>{ i+=30; s.st={running:true,done:Math.min(i,126),total:126}; if(i>=126){ clearInterval(s.t); s.done=true; s.st={running:false,ok:true,count:42}; } },250); return ''; },
    callsStatus(){ return JSON.stringify(this._calls.st); },
    _notes:'[{"id":"a1","title":"Boodschappen","text":"","updated":1759480000000,"items":[{"id":"i1","text":"Melk","done":false},{"id":"i2","text":"Brood","done":true},{"id":"i3","text":"Kaas","done":false}]}]',
    _ct:{perm:false,write:false,v:0,list:[
      {k:'k1',n:'Moeder',id:1,u:Date.now()-864e5,f:{Naam:'Moeder',Telefoon:'06 11122233 (mobiel)',Favoriet:'ja'}},
      {k:'k2',n:'Jos de Vries',id:2,u:Date.now()-5*864e5,f:{Naam:'Jos de Vries',Telefoon:'010 999 8888 (werk)','E-mail':'jos@esape.nl',Bedrijf:'ESAPE',Functie:'Ontwikkelaar'}},
      {k:'k3',n:'Huisarts',id:3,u:Date.now()-40*864e5,f:{Naam:'Huisarts',Telefoon:'010 123 4567',Adres:'Coolsingel 1, Rotterdam'}}],
      versions:[{v:2,t:Date.now()-864e5,count:3,added:0,removed:1,changed:1,first:false,full:true,changes:[
        {kind:'~',k:'k1',n:'Moeder',d:[['Telefoon','06 11122200 (mobiel)','06 11122233 (mobiel)']]},
        {kind:'-',k:'k9',n:'Oude buurman',d:[['Naam','Oude buurman',''],['Telefoon','0612000000 (mobiel)','']]}]},
        {v:1,t:Date.now()-30*864e5,count:3,added:3,removed:0,changed:0,first:true,full:true,changes:[]}]},
    contactsInfo(){ const c=this._ct; return JSON.stringify({perm:c.perm,write:c.write,dest:this._wa.info.dest,lastCheck:Date.now()}); },
    contactsRequestPermission(){ this._ct.perm=true; this._ct.write=true; setTimeout(()=>onContactsChanged(),120); },
    contactsSnapshot(){ return JSON.stringify({v:0}); },
    contactsList(q){ const ql=(q||'').toLowerCase(); const c=this._ct.list.filter(x=>!ql||x.n.toLowerCase().includes(ql)||(x.f.Telefoon||'').replace(/\D/g,'').includes(ql.replace(/\D/g,'')||'#'));
      return JSON.stringify({contacts:c.map(x=>({k:x.k,id:x.id,n:x.n,u:x.u,sub:(x.f.Telefoon||x.f['E-mail']||'').split('\n')[0],fav:!!x.f.Favoriet})),total:this._ct.list.length,version:2,versionT:Date.now()-864e5}); },
    contactsDetail(k,id){ const x=this._ct.list.find(y=>y.k===k); if(!x) return '{"error":"Contact niet gevonden"}'; const h=[]; for(const v of this._ct.versions) for(const c of v.changes) if(c.k===k) h.push({v:v.v,t:v.t,kind:c.kind,d:c.d}); return JSON.stringify({k:x.k,n:x.n,u:x.u,id:x.id,f:x.f,history:h}); },
    contactsVersions(){ return JSON.stringify({versions:this._ct.versions.map(v=>({v:v.v,t:v.t,count:v.count,added:v.added,removed:v.removed,changed:v.changed,first:v.first,full:v.full}))}); },
    contactsVersion(v){ const x=this._ct.versions.find(y=>y.v==v); return x?JSON.stringify(x):'{"error":"Versie niet gevonden"}'; },
    contactsRestore(v,k){ return 'ok:Oude buurman'; },
    contactsExport(v){ if(!this._wa.info.dest) return 'Kies eerst een backup-map (bij WhatsApp backup)'; return 'ok:'+this._ct.list.length; },
    contactsEdit(k,id){ toast(k?'(contacten-app: bewerken '+k+')':'(contacten-app: nieuw contact)'); },
    _tx:{files:false,calls:false,model:'',models:{base:false,small:false},st:{running:false},done:{},lang:'nl',playing:false,pos:0},
    txInfo(){ const t=this._tx; return JSON.stringify({supported:true,files:t.files,calls:t.calls,dest:this._wa.info.dest,folder:'',lang:t.lang,model:t.model,busy:t.st.running,
      models:[{id:'base',label:'Snel (57 MB)',installed:t.models.base},{id:'small',label:'Nauwkeurig (181 MB)',installed:t.models.small}]}); },
    txRequestFiles(){ this._tx.files=true; setTimeout(()=>onTrChanged(),100); },
    txRequestCalls(){ this._tx.calls=true; setTimeout(()=>onTrChanged(),100); },
    txPickFolder(){}, txClearFolder(){}, txSetModel(id){ this._tx.model=id; }, txSetLang(l){ this._tx.lang=l; },
    txDownload(id){ const t=this._tx; let p=0; t.st={running:true,phase:'download',pct:0}; const h=setInterval(()=>{ p+=25; t.st={running:true,phase:'download',pct:Math.min(p,100)}; if(p>=100){ clearInterval(h); t.models[id]=true; t.model=id; t.st={running:false,done:0,failed:0,stopped:false}; } },200); return ''; },
    txDeleteModel(id){ this._tx.models[id]=false; if(this._tx.model===id) this._tx.model=''; },
    _txRecs:[{id:'a1',file:'Moeder_0611122233_20261003101500.m4a',path:'/rec/a.m4a',mtime:Date.now()-3*36e5,duration:312000,name:'Moeder',number:'06 11122233',kind:'in'},
      {id:'b2',file:'Huisarts_0101234567_20261002091000.m4a',path:'/rec/b.m4a',mtime:Date.now()-27*36e5,duration:95000,name:'Huisarts',number:'010 123 4567',kind:'out'}],
    txList(){ const t=this._tx; return JSON.stringify({recordings:this._txRecs.map(r=>Object.assign({},r,t.done[r.id]?{done:true,preview:'Hoi mam, ik bel even over zaterdag…'}:{done:false}))}); },
    txStart(paths){ const t=this._tx; if(!t.model) return 'Download eerst een spraakmodel'; const ids=this._txRecs.filter(r=>JSON.parse(paths).includes(r.path)).map(r=>r.id); let p=0;
      t.st={running:true,phase:'transcribe',pct:0,file:'opname.m4a',left:ids.length-1}; const h=setInterval(()=>{ p+=20; t.st={running:true,phase:'transcribe',pct:Math.min(p,100),file:'opname.m4a',left:0}; if(p>=100){ clearInterval(h); ids.forEach(i=>t.done[i]=true); t.st={running:false,done:ids.length,failed:0,stopped:false}; } },200); return ''; },
    txCancel(){ this._tx.st={running:false,done:0,failed:0,stopped:true}; },
    txStatus(){ return JSON.stringify(this._tx.st); },
    txGet(id){ const r=this._txRecs.find(x=>x.id===id); if(!r||!this._tx.done[id]) return '{"error":"Transcript niet gevonden"}';
      return JSON.stringify(Object.assign({},r,{callDate:r.mtime-r.duration,model:'base',segments:[{from:0,to:4000,text:'Hoi mam, ik bel even over zaterdag.'},{from:4000,to:9000,text:'Kom je om twee uur of liever later?'},{from:9000,to:15000,text:'Twee uur is prima, dan neem ik taart mee.'}]})); },
    txSearch(q){ const out=[]; for(const r of this._txRecs) if(this._tx.done[r.id] && 'kom je om twee uur of liever later?'.includes(q.toLowerCase())) out.push({id:r.id,name:r.name,mtime:r.mtime,from:4000,text:'Kom je om twee uur of liever later?'}); return JSON.stringify({results:out}); },
    txExport(id){ if(!this._wa.info.dest) return 'Kies eerst een backup-map (bij WhatsApp backup)'; return 'ok:'+(id?1:Object.keys(this._tx.done).length); },
    txDelete(id){ delete this._tx.done[id]; },
    txPlay(id,ms){ this._tx.playing=true; this._tx.pos=+ms; return ''; }, txPause(){ this._tx.playing=false; },
    txPlayState(){ const t=this._tx; if(t.playing) t.pos+=400; return JSON.stringify({playing:t.playing,pos:t.pos}); }, txStop(){ this._tx.playing=false; this._tx.pos=0; },
    _rd:{favs:[],st:{status:'stopped',title:''},list:[
      {id:'a1',name:'NPO Radio 1',url:'https://icecast.omroep.nl/radio1-bb-mp3',logo:'',tags:'news,talk',codec:'MP3',bitrate:192},
      {id:'a2',name:'NPO Radio 2',url:'https://icecast.omroep.nl/radio2-bb-mp3',logo:'',tags:'pop,hits',codec:'MP3',bitrate:192},
      {id:'a3',name:'Qmusic',url:'https://example.org/qmusic',logo:'',tags:'pop,top 40',codec:'AAC',bitrate:96},
      {id:'a4',name:'Radio 538',url:'https://example.org/538',logo:'',tags:'dance,hits',codec:'AAC',bitrate:64},
      {id:'a5',name:'Sky Radio',url:'https://example.org/sky',logo:'',tags:'easy listening',codec:'MP3',bitrate:128}]},
    radioLoad(q){ const l=this._rd.list.filter(s=>!q||s.name.toLowerCase().includes(q.toLowerCase())); setTimeout(()=>onRadioStations({q:q||'',stations:l}),150); },
    radioCache(){ return '[]'; },
    radioFavorites(){ return JSON.stringify(this._rd.favs); },
    radioToggleFav(j){ const s=JSON.parse(j); const i=this._rd.favs.findIndex(f=>f.id===s.id); if(i>=0){ this._rd.favs.splice(i,1); return false; } this._rd.favs.push(s); return true; },
    _car: {addr: '', trips: false, defType: 'prive', log: [{t: Date.now() - 7200e3, lat: 51.92, lon: 4.48, acc: 12}], tripList: [{id: 't1', start: Date.now() - 864e5, end: Date.now() - 864e5 + 1800e3, m: 23400, type: 'zakelijk', note: 'Klant', route: '1__Rit.trk'}, {id: 't2', start: Date.now() - 2 * 864e5, end: Date.now() - 2 * 864e5 + 900e3, m: 8100, type: 'prive', note: '', route: ''}], locOk: true, bgLoc: false, btOk: true, battery: false, dest: true},
    carState(){ return JSON.stringify(this._car); }, carSet(a, n, t, d){ Object.assign(this._car, {addr: a, name: n, trips: t, defType: d}); },
    carParkNow(){ this._car.park = {t: Date.now(), how: 'hand', lat: 51.92, lon: 4.48, acc: 8}; setTimeout(() => onCarChanged('ok'), 50); }, carLeft(){ delete this._car.park; },
    carTripSet(id, type, note){ const t = this._car.tripList.find(x => x.id === id); if (t) { if (type != null) t.type = type; if (note != null) t.note = note; } return ''; },
    carTripDelete(id){ this._car.tripList = this._car.tripList.filter(x => x.id !== id); return ''; }, carExport(m){ setTimeout(() => onCarExport('ok:2'), 50); },
    carTripStart(){ this._car.tripActive = true; }, carTripStop(){ this._car.tripActive = false; },
    _kl: {open: false, docs: [{id: 'k1abcdef', name: 'Paspoort.pdf', mime: 'application/pdf', size: 820000, t: Date.now() - 864e5}]},
    kluisUnlock(){ this._kl.open = true; setTimeout(() => onKluis(true), 30); }, kluisList(){ return this._kl.open ? JSON.stringify({docs: this._kl.docs, left: 300000}) : '{"locked":true}'; },
    kluisAdd(){ this._kl.docs.push({id: 'k2abcdef', name: 'Polis <b>.pdf', mime: 'application/pdf', size: 1000, t: Date.now()}); setTimeout(() => onKluisChanged('ok'), 30); },
    kluisView(id){ this._klView = id; }, kluisDelete(id){ this._kl.docs = this._kl.docs.filter(d => d.id !== id); return ''; }, kluisRename(id, n){ const d = this._kl.docs.find(x => x.id === id); if (d) d.name = n; return ''; }, kluisClose(){ this._kl.open = false; },
    voiceStart(){ if (this._voiceErr) return this._voiceErr; this._vt = Date.now(); return ''; }, voiceStop(){ setTimeout(() => onVoiceNote(this._voiceResult || {text: 'Melk, brood en kaas'}), 50); }, voiceCancel(){}, voiceElapsed(){ return Date.now() - (this._vt || Date.now()); },
    radioSaveClip(s){ this._clip = s; setTimeout(() => onRadioClip(''), 50); },
    musicSaveFromRadio(a, t, s){ this._saved = [a, t, s]; return ''; },
    radioMoveFav(){}, radioMoveFavTo(a, b){ const f = this._rd.favs || []; if (a >= 0 && a < f.length && b >= 0 && b < f.length) f.splice(b, 0, f.splice(a, 1)[0]); },
    radioPlay(j){ const r=this._rd; r.st={status:'connecting',station:JSON.parse(j),title:'',info:{},recent:[]}; setTimeout(()=>{ r.st.status='playing'; if(this._ts) r.st.shift={behind:6,back:600,clip:1500}; r.st.title='Doe Maar - De Bom'; r.st.info={name:'NPO Radio 2',desc:'Gouden Uur met Gijs Staverman',genre:'Pop',site:'https://www.nporadio2.nl',br:'192',title:'Doe Maar - De Bom',artist:'Doe Maar',song:'De Bom'}; r.st.recent=[{t:Date.now(),title:'Doe Maar - De Bom'},{t:Date.now()-24e4,title:'Golden Earring - Radar Love'}]; },600); },
    radioPause(){ this._rd.st.status='paused'; }, radioResume(){ if(this._rd.st.station) this._rd.st.status='playing'; },
    radioStop(){ this._rd.st={status:'stopped',title:'',last:this._rd.st.station}; }, radioSleep(m){ this._rd.st.sleepAt=m?Date.now()+m*6e4:0; },
    radioState(){ return JSON.stringify(this._rd.st); },
    _mu:{token:'',st:{state:'idle'},hist:[],used:0,mic:false},
    musicHasMic(){ return this._mu.mic; }, musicRequestMic(){ this._mu.mic=true; setTimeout(()=>onMusicChanged(),100); },
    musicSetToken(t){ this._mu.token=t; }, musicTokenHint(){ return this._mu.token?'abc…xyz':''; },
    _muRun(src){ const m=this._mu; m.st={state:src?'sending':'recording',at:Date.now()}; setTimeout(()=>{ if(m.st.state==='idle') return; m.st={state:'sending',at:Date.now()}; setTimeout(()=>{ if(m.st.state==='idle') return; m.used++;
      const r={t:Date.now(),source:src||'mic',title:'Bohemian Rhapsody',artist:'Queen',album:'A Night at the Opera',date:'1975-10-31',art:'',spotify:'https://open.spotify.com/track/x',apple:'',link:'https://lis.tn/x'}; m.hist.unshift(r); m.st={state:'done',at:Date.now(),result:r}; },500); },700); return ''; },
    musicStart(){ if(!this._mu.token) return 'Vul eerst je AudD-sleutel in'; return this._muRun(null); },
    musicStartRadio(){ if(this._rd.st.status!=='playing') return 'Er speelt geen radio'; if(!this._mu.token) return 'Vul eerst je AudD-sleutel in'; return this._muRun('radio:'+this._rd.st.station.name); },
    musicCancel(){ this._mu.st={state:'idle',at:Date.now()}; },
    musicState(){ const m=this._mu; return JSON.stringify(Object.assign({used:m.used,hasToken:!!m.token},m.st)); },
    musicHistory(){ return JSON.stringify(this._mu.hist); }, musicDelete(t){ this._mu.hist=this._mu.hist.filter(r=>String(r.t)!==t); },
    shortcutPin(t,l){ return ''; }, shortcutPinStation(j){ return ''; }, shortcutsDynamic(j){ this._dyn=j; },
    _st:{lock:false,timeout:60000,crashes:0},
    settingsInfo(){ const t=this._st; return JSON.stringify({dest:this._wa.info.dest,destName:'USB-stick',audd:this._mu.token?'abc…xyz':'',crashes:t.crashes,lock:{on:t.lock,locked:false,timeout:t.timeout,secure:true},
      perms:[{id:'phone',name:'Bellen',used:'Auto redial',ok:true},{id:'mic',name:'Microfoon',used:'Muziek herkennen',ok:this._mu.mic},{id:'files',name:'Alle bestanden',used:'WhatsApp backup, Gesprekken',ok:false}]}); },
    permRequest(id){ if(id==='mic') this._mu.mic=true; setTimeout(()=>onSettingsChanged(''),100); },
    crashShare(){ toast('(delen foutrapport)'); }, notesVersion(){ return this._nv || 1; }, historyStamp(){ return [this._historyRows.length, this._historyEnabled, this._historyAllowed].join(':'); }, overnight(){ return this._seen ? '{}' : JSON.stringify({since: Date.now() - 8 * 36e5, backup: {t: Date.now() - 4 * 36e5, failed: 0}, missed: [{who: 'Tandarts', t: Date.now() - 2 * 36e5}], auto: [{t: Date.now() - 36e5, name: 'Parkeren', what: 'Gestart vanuit de melding'}]}); }, overnightSeen(){ this._seen = true; },
    backupCheckLast(){ return this._chk || '{}'; }, backupCheckRun(){ this._chk = JSON.stringify({t: Date.now(), ok: true, items: [{what: 'Backup-map', ok: true, msg: 'Backup'}, {what: 'Proef-terugzetten', ok: true, msg: '✓ Notities leesbaar (12).'}], summary: 'ok'}); setTimeout(() => onBackupCheck(JSON.parse(this._chk)), 50); },
    webStoreSave(j){ this._webStore = j; }, webStorePending(){ return ''; },
    notifMute(on){ this._notifMuted = !!on; },
    notifState(){ return JSON.stringify({muted: !!this._notifMuted, allowed: !this._notifOff, perm: !this._notifOff, kinds: [{id: 'reminders', name: 'Herinneringen', desc: 'Herinneringen die je bij een notitie zet', on: true, level: 4}, {id: 'radio', name: 'Radio', desc: 'Bediening van de radio', on: false, level: 0}]}); },
    notifOpen(id){ this._notifOpen = id; }, notifAsk(){ this._notifOff = false; this._notifAsk = true; },
    uiReady(){ this._uiReady = true; }, crashCopy(){ this._copied = true; toast('Foutrapport gekopieerd'); }, freshInstall(){ return true; }, crashClear(){ this._st.crashes=0; }, crashNew(){ return false; }, logJs(m){ this._jsErr=m; },
    lockState(){ const t=this._st; return JSON.stringify({on:t.lock,locked:!!t.locked,timeout:t.timeout,secure:true}); },
    lockUnlock(){ setTimeout(()=>{ this._st.locked=false; onUnlocked(); },200); },
    lockSet(on){ this._st.lock=on; setTimeout(()=>onSettingsChanged(''),100); }, lockTimeout(v){ this._st.timeout=+v; },
    _sec:{on:false,since:0,rotate:false,space:null},
    backupSetPassword(pw){ setTimeout(()=>{ this._sec.on=true; this._sec.since=Date.now(); this._sec.pw=pw; onSecure(''); },200); },
    backupEncryptOff(){ this._sec.on=false; }, backupSetRotate(on){ this._sec.rotate=on; },
    backupRotate(dry){ setTimeout(()=>{ if(!dry) this._sec.rotated=true; onRotate({count:6,bytes:48e6,dry}); },100); },
    backupSpace(){ setTimeout(()=>{ this._sec.space={t:Date.now(),total:5.4e9,dirs:[{name:'WhatsApp backup',bytes:4.9e9,files:8123},{name:'SMS backup',bytes:3.1e8,files:41},{name:'Versleuteld',bytes:1.9e8,files:9}]}; onSpace(this._sec.space); },200); },
    archivePick(){ setTimeout(()=>onArchivePicked(),100); },
    archiveOpen(pw){ setTimeout(()=>onArchive(!pw ? {needPw:true} : pw!=='goedwachtwoord' ? {error:'Verkeerd wachtwoord'} : {name:'backup-2026-10-04_0330.rtb',bytes:1.2e7,files:[{n:'SMS backup/sms-2026-10-04.xml',s:1e6}],contacts:'Contacten backup/contacten-2026-10-04.vcf',notes:'Notities/notities.json'}),150); },
    archiveRestore(k,e){ this._arcRestore=k+'|'+e; setTimeout(()=>onRestorePreview({kind:k,total:320,fresh:2,dup:318,sample:['Oom Kees','Tandarts']}),100); },
    archiveUnpack(){ setTimeout(()=>onArchiveUnpacked('ok:57'),150); }, archiveClose(){ this._arcClosed=true; },
    _bk:{busy:false,current:'',auto:false,charging:true,parts:{},last:null},
    backupState(f){ const b=this._bk; return JSON.stringify({busy:b.busy,current:b.current,auto:b.auto,charging:b.charging,dest:this._wa.info.dest,destName:'USB-stick',next:b.auto?Date.now()+5*36e5:0,parts:b.parts,last:b.last,encrypted:this._sec.on,encSince:this._sec.since,rotate:this._sec.rotate,space:this._sec.space}); },
    backupSetPart(p,on){ this._bk.parts[p]=on; }, backupSetAuto(a,c){ this._bk.auto=a; this._bk.charging=c; },
    backupStart(wa){ const b=this._bk; if(!this._wa.info.dest) return 'Kies eerst een backup-map'; b.busy=true; const ks=['sms','calls','contacts','notes','transcripts','music']; let i=0;
      const t=setInterval(()=>{ b.current=ks[i++]; if(i>ks.length){ clearInterval(t); b.busy=false; b.current='';
        b.last={t:Date.now(),auto:false,sms:{ok:true,count:1234},calls:{ok:true,count:842},contacts:{ok:true,count:311},notes:{ok:true,count:4},transcripts:{ok:true,count:0,msg:'Niets te bewaren'},music:{ok:false,count:0,msg:'Geen toestemming'}}; if(this._sec.on) b.last.archive='backup-2026-10-04_1342.rtb'; if(this._sec.rotate) b.last.rotated={count:2,bytes:31e6};
        onBackupDone(b.last); } },150); return ''; },
    restorePick(k){ this._restoreKind = k; const P = {contacts:{kind:k,total:320,fresh:9,dup:311,sample:['Oom Kees','Tandarts']}, settings:{kind:k,total:12,fresh:5,dup:7,rules:2,favs:3,alarm:true,plan:false,reminders:0,sample:['Parkeren starten']}, route:{kind:k,total:1,fresh:1,dup:0,title:'Rondje Plas',points:420,stats:{distance:5230},sample:['Rondje Plas']}};
      setTimeout(()=>onRestorePreview(P[k] || {kind:k,total:5,fresh:1,dup:4,sample:['Vakantie']}),100); },
    restoreApply(){ setTimeout(()=>onRestoreDone('ok:9'),100); return ''; },
    _al:{on:false,hour:7,minute:0,days:31,station:null,nextAt:0},
    alarmState(){ return JSON.stringify(Object.assign({exact:true},this._al)); },
    alarmSet(on,h,m,d,st){ const a=this._al; Object.assign(a,{on,hour:h,minute:m,days:d}); if(st) a.station=JSON.parse(st); if(on&&!a.station) return 'Kies een zender'; a.nextAt=on?Date.now()+8*36e5:0; return ''; },
    alarmTest(){ return this._al.station?'':'Kies eerst een zender'; }, alarmExactSettings(){},
    _rem:{}, _rems:[], noteRemind(id,t,time){ if(+time>0) this._rem[id]=+time; else delete this._rem[id]; }, noteReminders(){ const o = {...this._rem}; this._rems.forEach(r => { if (!o[r.note] || o[r.note] > r.t) o[r.note] = r.t; }); return JSON.stringify(o); },
    noteRemindAdd(note,title,time,rep){ const id = note + '~' + (this._rems.length + 2); this._rems.push({id, note, t: +time, rep}); return id; },
    noteRemindList(note){ return JSON.stringify(this._rems.filter(r => r.note === note).map(r => ({id: r.id, t: r.t, rep: r.rep})).sort((a, b) => a.t - b.t)); },
    noteRemindDel(id){ this._rems = this._rems.filter(r => r.id !== id); }, noteRemindClear(note){ this._rems = this._rems.filter(r => r.note !== note); delete this._rem[note]; }, noteRemindRetitle(){},
    noteShare(t,x){ this._shared=x; }, pendingShare(){ const s=this._ps||''; this._ps=''; return s; },
    _sf:{last:'{}'},
    selfTestRun(){ setTimeout(()=>{ const items=[{id:'whisper',name:'Gesprekken uitschrijven (Whisper)',status:'ok',detail:'Het programma draait (snelle versie). Model: Snel (57 MB).'},
      {id:'dest',name:'Backup-map',status:'fail',detail:'Schrijven lukt niet. Kies de map opnieuw.',fix:'dest'},{id:'battery',name:'Batterijbeperking',status:'warn',detail:'Android kan de nachtelijke backup stoppen.',fix:'battery'},
      {id:'audd',name:'Muziek herkennen (AudD)',status:'skip',detail:'Geen sleutel ingesteld.',fix:'audd'}]; this._sf.last=JSON.stringify({t:Date.now(),items}); onSelfTest(items); },300); return ''; },
    selfTestLast(){ return this._sf.last; }, batterySettings(){ this._bat=true; if(this._au) this._au.perms.battery=true; }, notificationSettings(){},
    searchAll(q){ const id=++this._gsId; const ql=q.toLowerCase(); setTimeout(()=>{ const r={id,q};
      if('jansen piet'.includes(ql)||ql==='pie'){ r.contacts={total:2,items:[{k:'k1',id:'1',n:'Piet Jansen',sub:'06 12345678'},{k:'k2',id:'2',n:'Pieter de Vries',sub:'010 1234567'}]};
        r.calls={total:12,items:[{name:'Piet Jansen',number:'0612345678',date:Date.now()-36e5,kind:'in',label:'Inkomend',ck:'k1',cid:1}]}; }
      if(ql.length>=2){ r.sms={total:7,items:[{thread:3,name:'Moeder',t:Date.now()-864e5,text:'Neem jij morgen de taart mee voor '+q+'? Dan regel ik de rest.'}]};
        r.wa={total:1,items:[{chat:5,chatName:'Familie',sender:'Jos',t:Date.now()-2*864e5,text:'Wie neemt '+q+' mee naar het feest?'}]};
        r.tx={total:2,items:[{id:this._txRecs[0].id,name:'Tandarts',mtime:Date.now()-5*864e5,from:42000,text:'Ja, dan zie ik u dinsdag om tien uur, '+q+'.'}]}; }
      r.done=true; onSearchAll(r); },150); return id; }, _gsId:0,
    _rp:null, _txa:{on:false},
    redialPlanSet(j){ const p=JSON.parse(j); if(p.number.replace(/\D/g,'').length<3) return 'Vul een geldig telefoonnummer in'; this._rp=p; return ''; },
    redialPlanClear(){ this._rp=null; },
    redialPlanState(){ const p=this._rp; return JSON.stringify({on:!!p,plan:p,exact:true,next:p?Date.now()+18*36e5:0,last:''}); },
    txAutoSet(on){ this._txa.on=on; }, txAutoState(){ return JSON.stringify({battery:false,blocked:false,on:this._txa.on,next:this._txa.on?Date.now()+10*36e5:0,last:0,found:0,failed:this._txa.on?2:0,model:true}); }, txAutoRetryFailed(){ this._txa.retried=true; },
    _zoom:0, textZoomGet(){ return this._zoom; }, textZoomActual(){ return this._zoom || 100; }, textZoomSet(z){ this._zoom=z; document.body.style.zoom = (z || 100) / 100; },
    _ts:true, radioTimeshift(){ return this._ts; }, radioSetTimeshift(on){ this._ts=on; },
    radioShift(w){ const st=this._rd.st; if(!st.shift) return; st.shift.behind = w==='live' ? 6 : Math.max(6, st.shift.behind + (w==='rew' ? 30 : -30)); },
    _home:false, homeIsDefault(){ return this._home; }, homeMakeDefault(){ this._home=true; setTimeout(()=>onHomeRole(),50); }, homeSettings(){ this._hs=true; }, homeOpen(){ this._ho=true; },
    _pins:{app:[],skin:[]}, notePins(w){ return JSON.stringify(this._pins[w] || []); },
    notePinSet(w,id,on){ const l=(this._pins[w]||[]).filter(x=>x!==id); if(on) l.push(id); this._pins[w]=l; },
    shortcutPinNote(id,t){ this._pinned=id+'|'+t; return ''; },
    notesLoad(){ return this._notes; },
    notesSave(j){ this._notes=j; return ''; }, notesSaveIf(j, v){ if ((this._nv || 1) !== v) return JSON.stringify({conflict: true}); this._notes=j; this._nv=(this._nv||1)+1; return JSON.stringify({ok: true, ver: this._nv}); },
    notesExport(){ if(!this._wa.info.dest) return 'Kies eerst een backup-map (bij WhatsApp backup)'; return 'ok:'+JSON.parse(this._notes).length; },
    trackSave(title){ const t=this._tr; const id=Date.now()+'__'+(title||'Route').replace(/ /g,'_')+'.trk'; t.list.unshift({id,title:title||'Route',t:t.start,stats:t.just.stats,path:'M10 120 L80 40 L160 90 L240 30 L290 70'}); t.just=null; return ''; },
    trackDiscard(){ this._tr.just=null; },
    trackList(){ return JSON.stringify(this._tr.list); },
    trackDetail(id,w,h){ const r=this._tr.list.find(x=>x.id===id); if(!r) return JSON.stringify({error:'niet gevonden'});
      const line=[]; let la=51.9225, lo=4.47917; for(let i=0;i<40;i++){ la+=0.0006*Math.sin(i/6); lo+=0.0008; line.push([Math.round(la*1e6)/1e6, Math.round(lo*1e6)/1e6]); }
      return JSON.stringify({...r,path:'M12 180 L90 50 L180 110 L260 40 L308 90',line,ele:[[0,5],[500,12],[1000,8],[1500,18],[2000,6]]}); },
    trackRename(id,title){ const r=this._tr.list.find(x=>x.id===id); if(r)r.title=title; return ''; },
    trackDelete(id){ this._tr.list=this._tr.list.filter(x=>x.id!==id); return ''; },
    trackExportBackup(id){ return this._wa.info.dest?'':'Kies eerst een backup-map (bij WhatsApp backup)'; },
    trackShare(id){ return ''; },
    // Automatiseringen
    _au:{rules:[],log:[],last:{},perms:{a11y:false,a11yRunning:false,loc:false,fine:false,bgloc:false,bt:false,notif:true,battery:false,sdk:34},rec:null,tested:null},
    autoState(){ return JSON.stringify(this._au); },
    autoSave(j){ const r=JSON.parse(j); if(!r.name) return 'Geef de automatisering een naam'; const a=this._au.rules, i=a.findIndex(x=>x.id===r.id); if(i>=0)a[i]=r; else a.push(r); return ''; },
    autoDelete(id){ this._au.rules=this._au.rules.filter(r=>r.id!==id); },
    autoSetOn(id,on){ const r=this._au.rules.find(x=>x.id===id); if(r) r.on=on; },
    autoBt(){ return this._au.perms.bt ? JSON.stringify({devices:[{a:'00:11:22:33:44:55',n:'VW Golf',car:true},{a:'AA:BB:CC:DD:EE:FF',n:'Galaxy Buds',car:false}]}) : '{"error":"perm"}'; },
    autoApps(){ return JSON.stringify([{p:'net.easypark.android',n:'EasyPark',park:true},{p:'com.google.android.apps.maps',n:'Maps'},{p:'com.whatsapp',n:'WhatsApp'}]); },
    autoTest(id){ this._au.tested=id; const r=this._au.rules.find(x=>x.id===id); this._au.log.unshift({t:Date.now(),id,name:r?r.name:'',what:'Test: gevraagd met een melding'}); this._au.last[id]=Date.now(); return ''; },
    autoHere(){ setTimeout(()=>onAutoHere(this._au.perms.loc?{lat:51.92250,lng:4.47917,acc:12}:{error:'perm'}),150); },
    autoRecord(p){ if(!this._au.perms.a11yRunning) return 'Zet eerst de toegankelijkheid voor Rene\'s Tools aan'; this._au.rec={pkg:p,steps:[{t:'tap',text:'Start parkeren',id:'net.easypark.android:id/start'},{t:'tap',text:'Bevestigen'}]}; setTimeout(()=>openTool('auto-rec'),200); return ''; },
    autoRecTake(){ const r=this._au.rec; this._au.rec=null; return JSON.stringify(r); },
    autoA11ySettings(){ Object.assign(this._au.perms,{a11y:true,a11yRunning:true}); },
    autoPerm(k){ const P=this._au.perms; if(k==='loc'){P.loc=true;P.fine=true;} if(k==='bgloc'){ if(!P.loc){P.loc=true;P.fine=true;} else P.bgloc=true; } if(k==='bt')P.bt=true; if(k==='notif')P.notif=true; setTimeout(()=>onAutoChanged(),50); }
  };
})();

