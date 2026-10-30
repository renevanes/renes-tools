/* In een gewone browser (voorbeeld/test) bestaat de Android-brug niet: dan een test-brug. */
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
    lastManifest:()=>'null', checkUpdates:()=>setTimeout(()=>onUpdateStatus({state:'uptodate',kind:'web',manual:true,versionName:VERSION_NAME}),400),
    installUpdate(){
      // Downloaden met voortgang (5 MB in 10 stappen), controleren, klaarzetten en dan vraagt Android om te bevestigen
      if (this._upd) { onUpdateStatus({ state: 'busy', kind: 'apk' }); return; }
      this._upd = true; const T = 5242880, steps = [];
      steps.push({ state: 'progress', kind: 'apk', phase: 'start', done: 0, total: 0 });
      for (let i = 0; i <= 10; i++) steps.push({ state: 'progress', kind: 'apk', phase: 'download', done: Math.round(T * i / 10), total: T });
      steps.push({ state: 'progress', kind: 'apk', phase: 'check', done: 0, total: 0 }, { state: 'progress', kind: 'apk', phase: 'install', done: 0, total: 0 },
        { state: 'progress', kind: 'apk', phase: 'committed', done: 0, total: 0 }, this._updFail ? { state: 'error', kind: 'apk', error: 'Installatie afgebroken' } : this._updSilent ? null : { state: 'confirm', kind: 'apk' });
      (this._updLog = []);
      steps.forEach((s, i) => setTimeout(() => { if (s) { this._updLog.push(s); onUpdateStatus(s); } if (i === steps.length - 1) this._upd = false; }, (this._updMs || 60) * (i + 1)));
    }, permissions:()=>JSON.stringify({call:true,phoneState:true,callLog:true,notifications:true}),
    requestPermissions(){}, openAppSettings(){}, pickContact(){ onContactPicked({number:'010 123 4567', name:'Huisarts'}); },
    setBars(t, b, l){ this._bars = [t, b, l]; }, themeColors(){ return this._theme ? JSON.stringify(this._theme) : ''; }, vibrate(){}, toast(m){ toast(m); }, pendingOpen:()=>'',
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
    _mkDemo(){ const r=this._rd; const names=['Huisarts','Familie De Boer','Jos','Pietje Puk','Sportclub']; r.chats=[]; r.msgs={};
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
    smsInfo(){ const s=this._sms; return JSON.stringify({perm:s.perm,dest:this._wa.info.dest,destName:this._wa.info.destName,contacts:s.contacts,lastExport:s.done?Date.now()-3600e3:0,lastCount:s.done?31:0,total:s.perm?31:0}); },
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
      {k:'k2',n:'Jos de Vries',id:2,u:Date.now()-5*864e5,f:{Naam:'Jos de Vries',Telefoon:'010 999 8888 (werk)','E-mail':'jos@example.nl',Bedrijf:'Voorbeeld BV',Functie:'Ontwikkelaar'}},
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
    txDownload(id){ const t=this._tx; t.dl=id; let p=0; t.st={running:true,phase:'download',pct:0}; const h=setInterval(()=>{ p+=25; t.st={running:true,phase:'download',pct:Math.min(p,100)}; if(p>=100){ clearInterval(h); t.models[id]=true; t.model=id; t.st={running:false,done:0,failed:0,stopped:false}; } },200); return ''; },
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
    kluisOrganize(id,json){if(!this._kl.open)return 'De kluis is op slot';if(this._kl.error)return this._kl.error;const d=this._kl.docs.find(d=>d.id===id);if(!d)return 'Document niet gevonden';const p=JSON.parse(json);if((p.category||'').length>60||(p.note||'').length>1000)return 'Documentgegevens zijn te lang';Object.assign(d,p);return '';},
    kluisSavePdf(name,category){setTimeout(()=>{if(!this._kl.open||this._kl.error){onPdfStored({error:this._kl.error||'De kluis is op slot'});return;}const f=this._pdf.outs.find(d=>d.name===name);if(!f){onPdfStored({error:'Bestand niet gevonden'});return;}const d={id:'doc'+Date.now().toString(36),name,mime:'application/pdf',size:f.size,t:Date.now(),category};this._kl.docs.push(d);onPdfStored({ok:true,id:d.id,name});},40);},
    pdfSignatureLoad(){return JSON.stringify(this._signatureReadError?{error:this._signatureReadError}:{signature:this._signature||null});},
    pdfSignatureSave(json){if(this._signatureError)return this._signatureError;const s=JSON.parse(json);if(!pdfSignatureValid(s))return 'Ongeldige handtekening';this._signature=JSON.parse(JSON.stringify(s));return '';},
    pdfSignatureDelete(){if(this._signatureError)return this._signatureError;this._signature=null;return '';},
    _pdf: {imgs: [], outs: [], info: {}, pending: ''},
    pdfPickImages(){ const P = this._pdf; P.imgs.push({i: 0, w: 300, h: 400, thumb: 'data:image/jpeg;base64,AAAA'}, {i: 1, w: 400, h: 300, thumb: 'data:image/jpeg;base64,BBBB'}); setTimeout(() => onPdf({kind: 'images', count: 2, warn: ''}), 30); },
    pdfPickPdf(){ this._pdf.info = {name: 'Contract', pages: 8, lossless: true, revision:'pdf-' + Date.now()}; setTimeout(() => onPdf({kind: 'pdf', ...this._pdf.info}), 30); },
    pdfPickDoc(){ this._pdf.outs.unshift({name: 'Afspraak.pdf', size: 12000}); setTimeout(() => onPdf({kind: 'done', made: ['Afspraak.pdf']}), 30); },
    pdfImages(){ return JSON.stringify(this._pdf.imgs.map((m, i) => ({...m, i}))); }, pdfImageMove(i, t){ const a = this._pdf.imgs; if (t >= 0 && t < a.length) a.splice(t, 0, a.splice(i, 1)[0]); }, pdfImageRemove(i){ this._pdf.imgs.splice(i, 1); }, pdfImagesClear(){ this._pdf.imgs = []; },
    pdfMakeFromImages(a4, n){ this._pdf.lastA4 = a4; const name = (n || 'Plaatjes') + '.pdf'; this._pdf.imgs = []; this._pdf.outs.unshift({name, size: 50000}); setTimeout(() => onPdf({kind: 'done', made: [name]}), 30); },
    pdfInfo(){ return JSON.stringify(this._pdf.info); }, pdfThumb(i){ return 'data:image/jpeg;base64,CCCC'; },
    pdfEditPreview(revision,page){const c=document.createElement('canvas');c.width=300;c.height=400;const ctx=c.getContext('2d');ctx.fillStyle='white';ctx.fillRect(0,0,300,400);ctx.fillStyle='black';ctx.font='16px sans-serif';ctx.fillText('Contract · pagina '+(page+1),20,35);setTimeout(()=>onPdfEditPreview(this._pdf.previewError?{revision,page,error:this._pdf.previewError}:{revision,page,width:600,height:800,preview:c.toDataURL('image/jpeg')}),30);},
    pdfEditSave(revision,layers,name){this._pdf.edits={revision,layers:JSON.parse(layers),name};setTimeout(()=>{if(this._pdf.editError){onPdf({error:this._pdf.editError});return;}const n=(name||'Contract bewerkt')+'.pdf';this._pdf.outs.unshift({name:n,size:14000});onPdf({kind:'edited',revision,made:[n]});},60);},
    pdfSplit(mode, r){ this._pdf.split = [mode, r]; const made = mode === 'each' ? ['Contract p1.pdf', 'Contract p2.pdf'] : ['Contract p' + r + '.pdf']; made.forEach(n => this._pdf.outs.unshift({name: n, size: 9000})); setTimeout(() => onPdf({kind: 'done', made, lossless: true}), 30); },
    pdfFromText(t, x){ this._pdf.text = [t, x]; this._pdf.outs.unshift({name: (t || 'Tekst') + '.pdf', size: 3000}); setTimeout(() => onPdf({kind: 'done', made: [(t || 'Tekst') + '.pdf']}), 30); },
    pdfOutputs(){ return JSON.stringify(this._pdf.outs); }, pdfPending(){ const p = this._pdf.pending; this._pdf.pending = ''; return p; },
    pdfOpen(n){ this._pdf.opened = n; }, pdfViewPick(){ this._pdf.viewPick = (this._pdf.viewPick || 0) + 1; }, pdfDefaultState(){ return JSON.stringify(this._pdf.def || {state: 'none', app: '', count: 2}); }, pdfMakeDefault(){ this._pdf.madeDefault = (this._pdf.madeDefault || 0) + 1; }, pdfShare(j){ this._pdf.shared = JSON.parse(j); }, pdfSave(n){ this._pdf.saved = n; }, pdfSaveAll(j){ setTimeout(() => onPdf({kind: 'savedAll', count: JSON.parse(j).length}), 30); }, pdfDelete(n){ this._pdf.outs = this._pdf.outs.filter(o => o.name !== n); },
    voiceStart(){ if (this._voiceErr) return this._voiceErr; this._vt = Date.now(); return ''; }, voiceStop(){ setTimeout(() => onVoiceNote(this._voiceResult || {text: 'Melk, brood en kaas'}), 50); }, voiceCancel(){}, voiceElapsed(){ return Date.now() - (this._vt || Date.now()); },
    radioSaveClip(s){ this._clip = s; setTimeout(() => onRadioClip(''), 50); },
    musicSaveFromRadio(a, t, s){ this._saved = [a, t, s]; return ''; },
    radioMoveFav(){}, radioMoveFavTo(a, b){ const f = this._rd.favs || []; if (a >= 0 && a < f.length && b >= 0 && b < f.length) f.splice(b, 0, f.splice(a, 1)[0]); },
    radioPlay(j){ if (this._cast && this._cast.active) { const st = JSON.parse(j); Object.assign(this._cast, { src: 'radio', title: st.name, key: st.url, station: st, ep: undefined, pos: 0, dur: 0, playing: true, status: 'playing' }); return; } this._take('radio'); const r=this._rd; r.st={status:'connecting',station:JSON.parse(j),title:'',info:{},recent:[]}; setTimeout(()=>{ r.st.status='playing'; if(this._ts) r.st.shift={behind:6,back:600,clip:1500}; r.st.title='Doe Maar - De Bom'; r.st.info={name:'NPO Radio 2',desc:'Gouden Uur met Gijs Staverman',genre:'Pop',site:'https://www.nporadio2.nl',br:'192',title:'Doe Maar - De Bom',artist:'Doe Maar',song:'De Bom'}; r.st.recent=[{t:Date.now(),title:'Doe Maar - De Bom'},{t:Date.now()-24e4,title:'Golden Earring - Radar Love'}]; },600); },
    radioPause(){ this._rd.st.status='paused'; }, radioResume(){ if(this._rd.st.station) { this._take('radio'); this._rd.st.status='playing'; } },
    radioStop(){ this._rd.st={status:'stopped',title:'',last:this._rd.st.station}; }, radioSleep(m){ this._rd.st.sleepAt=m?Date.now()+m*6e4:0; },
    radioState(){ return JSON.stringify(this._rd.st); },
    // ----- podcasts (test) -----
    _pc: { subs: [], prog: {}, recent: [], st: { status: 'stopped' }, speed: 1, refreshed: 0, calls: [],
      top: [{ id: 'p1', feed: 'https://feeds.example.org/vandaag', title: 'Nieuws van Vandaag', author: 'Omroep X', image: 'https://img.example.org/1.jpg', genre: 'Nieuws' },
            { id: 'p2', feed: 'https://feeds.example.org/tech', title: 'Tech in 20 minuten', author: 'Rene & Co', image: '', genre: 'Technologie' }] },
    _pcFeed(feed){ const n = feed.endsWith('tech') ? 'Tech in 20 minuten' : 'Nieuws van Vandaag', id = feed.endsWith('tech') ? 'p2' : 'p1';
      const items = Array.from({ length: 64 }, (_, i) => ({ key: 'e' + (id + 'x' + i).padEnd(16, '0').slice(0, 16), guid: 'g' + i, title: 'Aflevering ' + (64 - i) + (i === 0 ? ' <b>nieuw</b>' : ''), url: 'https://cdn.example.org/' + id + '/' + i + '.mp3',
        type: 'audio/mpeg', date: Date.now() - i * 864e5 - 36e5, dur: 1200 + i * 60, image: '', desc: 'Over aflevering ' + (64 - i) + '.\nMet een tweede regel.' }));
      items.forEach(e => { const p = this._pc.prog[e.key]; if (p) { e.pos = p.p; e.done = p.done; e.pd = p.d; } });
      return { feedUrl: feed, pod: { id, feed, title: n, author: 'Omroep X', image: '', desc: 'Elke dag het belangrijkste nieuws.', link: 'https://example.org' }, items, subscribed: this._pc.subs.some(x => x.feed === feed) }; },
    podSearch(q){ this._pc.calls.push(['search', q]); setTimeout(() => onPodSearch(q === 'fout' ? { q, error: 'Geen internetverbinding' } : { q, results: this._pc.top.filter(p => p.title.toLowerCase().includes(q.toLowerCase())) }), 120); },
    podTop(){ setTimeout(() => onPodTop({ results: this._pc.top }), 80); },
    podOpen(feed, force){ this._pc.calls.push(['open', feed, force]); setTimeout(() => onPodFeed(this._pcFeed(feed)), 120); },
    podSubscribe(j){ const p = JSON.parse(j); if (!/^https?:/.test(p.feed || '')) return 'Ongeldig feed-adres'; if (!this._pc.subs.some(x => x.feed === p.feed)) this._pc.subs.push(Object.assign({ fresh: 2 }, p)); return ''; },
    podUnsubscribe(id){ this._pc.subs = this._pc.subs.filter(x => x.id !== id); },
    podSubs(){ return JSON.stringify(this._pc.subs); },
    // Lokale AI (assistent en slim zoeken): nagebootst, zonder model
    // Slaappraat zoals SleepBridge: nachten met stukjes, luisteren en uitschrijven worden nagebootst
    _sl: { phase: '', night: '', since: 0, stopAt: 0, heard: 0, level: -60, floor: -64, txDone: 0, txTotal: 0, message: '', mic: true, notif: true, model: 'Snel (57 MB)',
      set: { sens: 2, stopAt: '07:30', transcribe: true, keepNoise: false, keep: 14, remindAt: '' }, nights: [], played: '', hearing: false, probeHits: 0, probeLeft: 0, notifOk: true },
    _slSum(n){ const c = n.clips; return { id: n.id, start: n.start, end: n.end, reason: n.reason || '', talk: c.filter(k => k.done && k.kind === 'praten').length, noise: c.filter(k => k.done && k.kind === 'geluid').length, open: c.filter(k => !k.done).length, first: (c.find(k => k.done && k.kind === 'praten') || {}).text || '', batStart: n.batStart == null ? -1 : n.batStart, batEnd: n.batEnd == null ? -1 : n.batEnd, charged: !!n.charged }; },
    sleepState(){ const S = this._sl; return JSON.stringify({ phase: S.phase, night: S.night, since: S.since, stopAt: S.stopAt, heard: S.heard, level: S.level, floor: S.floor, txDone: S.txDone, txTotal: S.txTotal, message: S.message, hearing: S.hearing, probeHits: S.probeHits, probeLeft: S.probeLeft, notifOk: S.notifOk,
      mic: S.mic, notif: S.notif, model: S.model, set: S.set, nights: S.nights.slice().sort((a, b) => b.start - a.start).map(n => this._slSum(n)) }); },
    sleepStart(){ const S = this._sl; if (!S.mic) return 'mic'; if (!S.notif) return 'notif'; if (S.phase === 'luistert') return '';
      S.phase = 'luistert'; S.since = Date.now(); S.heard = 0; S.night = String(S.since); S.stopAt = S.set.stopAt ? S.since + 8 * 3600e3 : 0;
      S.nights.push({ id: S.night, start: S.since, end: 0, clips: [], batStart: S.bat == null ? -1 : S.bat, charged: !!S.plugged }); return ''; },
    sleepProbe(){ const S = this._sl; if (!S.mic) return 'mic'; if (!S.notif) return 'notif'; if (S.phase) return 'Slaappraat is al bezig'; S.phase = 'proef'; S.probeHits = 0; S.probeLeft = 30000; S.message = ''; return ''; },
    _slProbeEnd(){ const S = this._sl; S.phase = ''; S.message = S.probeHits ? 'Proef klaar: ' + S.probeHits + ' keer iets opgepikt. Zo pakt hij het vannacht ook op.' : 'Proef klaar: niets opgepikt.'; },
    sleepStop(){ const S = this._sl; if (S.phase === 'proef') { this._slProbeEnd(); return; } if (S.phase !== 'luistert') return; const n = S.nights.find(x => x.id === S.night); if (n) { n.end = Date.now() + (S.nightMs || 0); n.reason = 'Gestopt'; n.batEnd = S.batEnd == null ? -1 : S.batEnd; } S.phase = '';
      if (S.set.transcribe && S.model && n) n.clips.forEach(k => { if (!k.done && k.audio) { k.done = true; k.text = k._text || ''; k.kind = k.text ? 'praten' : 'geluid'; if (k.kind === 'geluid' && !S.set.keepNoise) k.audio = false; } }); },
    _slHear(text, dur){ const S = this._sl, n = S.nights.find(x => x.id === S.night); if (!n) return; const t = Date.now() + n.clips.length * 60e3; n.clips.push({ id: 'c' + t, t, dur: dur || 4, audio: true, done: false, _text: text || '' }); S.heard++; },
    sleepNight(id){ const n = this._sl.nights.find(x => x.id === id); return n ? JSON.stringify(n) : ''; },
    sleepDeleteNight(id){ const S = this._sl; if (id === S.night && S.phase) return; S.nights = S.nights.filter(n => n.id !== id); },
    sleepDeleteClip(id, clip){ const n = this._sl.nights.find(x => x.id === id); if (n) n.clips = n.clips.filter(k => k.id !== clip); },
    sleepSet(name, v){ const S = this._sl.set; if (name === 'sens') { if (!/^[123]$/.test(v)) return 'Ongeldig'; S.sens = +v; } else if (name === 'stopAt') { if (!/^(([01]\d|2[0-3]):[0-5]\d)?$/.test(v)) return 'Ongeldige tijd'; S.stopAt = v; }
      else if (name === 'transcribe' || name === 'keepNoise') S[name] = v === 'true'; else if (name === 'keep') { if (!/^(7|14|30)$/.test(v)) return 'Ongeldig'; S.keep = +v; }
      else if (name === 'remindAt') { if (!/^(([01]\d|2[0-3]):[0-5]\d)?$/.test(v)) return 'Ongeldige tijd'; S.remindAt = v; } else return 'Ongeldig'; return ''; },
    sleepTranscribe(id){ const S = this._sl, n = S.nights.find(x => x.id === id); if (!n) return 'Onbekende nacht'; if (S.phase) return 'Dat kan pas als Slaappraat klaar is met luisteren en uitschrijven'; if (!S.model) return 'model';
      S.phase = 'uitschrijven'; S.night = id; S.txDone = 0; S.txTotal = n.clips.filter(k => !k.done && k.audio).length;
      setTimeout(() => { n.clips.forEach(k => { if (!k.done && k.audio) { k.done = true; k.text = k._text || ''; k.kind = k.text ? 'praten' : 'geluid'; if (k.kind === 'geluid' && !S.set.keepNoise) k.audio = false; } }); S.phase = ''; }, S.txMs || 300); return ''; },
    sleepPlay(id, clip){ const n = this._sl.nights.find(x => x.id === id), k = n && n.clips.find(x => x.id === clip); if (!k || !k.audio) return 'Het geluid van dit stukje is er niet meer'; this._sl.played = clip; return ''; },
    sleepStopPlay(){ this._sl.played = ''; },
    sleepPlaying(){ return this._sl.played; },
    // Check-in zoals CheckinBridge/CheckinStore/Checkin (periodes in de tijd van de browser); _ciNotify/_ciNotifAnswer bootsen de melding na
    _ci: { qs: [], ans: [], seeded: false, next: 1, notifOk: true, muted: false, calOk: false, posted: {}, done: {}, previews: [], days: {}, model: 'Snel (57 MB)' },
    _ciKey(d){ return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); },
    _ciDow(d){ return (d.getDay() + 6) % 7 + 1; },
    _ciMin(t){ const m = /^(\d\d):(\d\d)$/.exec(t || ''); return m ? +m[1] * 60 + +m[2] : -1; },
    _ciWeek(d){ const t = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 4 - this._ciDow(d), 12), y = t.getFullYear(), j = new Date(y, 0, 1, 12); return y + '-W' + String(Math.floor(Math.round((t - j) / 864e5) / 7) + 1).padStart(2, '0'); },
    _ciApplies(q, d){ const dow = this._ciDow(d);
      switch (q.rhythm) { case 'daily': return true; case 'workdays': return dow <= 5; case 'weekdays': case 'multi': return (q.days || []).includes(dow); case 'weekly': return dow === (q.wday || 5);
        case 'monthly': { const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate(); return d.getDate() === Math.min(q.dom || 1, last); } } return false; },
    _ciQuiet(q, d){ return (q.quietWeekend && this._ciDow(d) >= 6) || (!!q.pauseUntil && this._ciKey(d) <= q.pauseUntil); },
    _ciSlots(q, from, to){
      const out = [], ts = (q.rhythm === 'multi' ? q.times.slice().sort() : q.times.slice(0, 1)).map(t => this._ciMin(t)), u0 = this._ciMin(q.until || '23:59'), u = u0 < 0 ? 1439 : u0;
      const d = new Date(from); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - 1);
      for (let g = 0; g < 4000 && d.getTime() < to; g++, d.setDate(d.getDate() + 1)) {
        if (!this._ciApplies(q, d)) continue;
        ts.forEach((m, i) => { const at = new Date(d.getFullYear(), d.getMonth(), d.getDate(), Math.floor(m / 60), m % 60).getTime(); if (at < from || at >= to) return;
          let end = u > m ? new Date(d.getFullYear(), d.getMonth(), d.getDate(), Math.floor(u / 60), u % 60).getTime() : new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1, Math.floor(u / 60), u % 60).getTime();
          if (q.rhythm === 'multi' && i + 1 < ts.length) end = Math.min(end, new Date(d.getFullYear(), d.getMonth(), d.getDate(), Math.floor(ts[i + 1] / 60), ts[i + 1] % 60).getTime());
          const key = q.rhythm === 'weekly' ? this._ciWeek(d) : q.rhythm === 'monthly' ? this._ciKey(d).slice(0, 7) : q.rhythm === 'multi' ? this._ciKey(d) + 'T' + String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0') : this._ciKey(d);
          out.push({ at, end, key, quiet: this._ciQuiet(q, d) }); });
      }
      return out; },
    _ciCurrent(q, now){ let open = null, today = null; const tk = this._ciKey(new Date(now));
      this._ciSlots(q, now - 40 * 864e5, now + 2 * 864e5).forEach(s => { if (s.at <= now && now < s.end) open = s; if (!today && s.at > now && this._ciKey(new Date(s.at)) === tk) today = s; }); return open || today; },
    _ciNext(q, after){ const s = this._ciSlots(q, after + 1, after + 400 * 864e5).find(x => !x.quiet && x.at > after); return s ? s.at : 0; },
    _ciFind(q, key){ const day = /^\d{4}-W\d{2}$/.test(key) ? ciWeekStart(key) : ciDate(/^\d{4}-\d{2}$/.test(key) ? key + '-01' : key); if (!day) return null;
      return this._ciSlots(q, day.getTime() - 2 * 864e5, day.getTime() + 33 * 864e5).find(s => s.key === key) || null; },
    _ciStatus(q, s, answered, now){ if (answered) return 'answered'; if (s.quiet) return 'quiet'; if (q.created && s.end <= q.created) return 'before'; if (now < s.at) return 'upcoming'; if (now < s.end) return 'open'; return 'missed'; },
    _ciBy(qid){ const m = {}; this._ci.ans.filter(a => a.q === qid).forEach(a => (m[a.p] = m[a.p] || []).push(a)); return m; },
    _ciSlotJson(q, s, by, now){ const a = by[s.key] || []; return { key: s.key, at: s.at, end: s.end, quiet: s.quiet, status: this._ciStatus(q, s, a.length > 0, now), answers: a.slice() }; },
    _ciStreak(q, by, now){ const st = this._ciSlots(q, now - 400 * 864e5, now + 864e5).reverse().map(s => this._ciStatus(q, s, (by[s.key] || []).length > 0, now)); let n = 0, started = false;
      for (const s of st) { if (s === 'answered') { n++; started = true; continue; } if (s === 'quiet') continue; if (!started && (s === 'upcoming' || s === 'open')) continue; break; } return n; },
    _ciSeed(){ const C = this._ci; if (C.seeded) return false; C.seeded = true; if (C.qs.length) return true; const now = Date.now();
      const t = (id, text, emoji, color, scale, rhythm, time, labels, voice) => ({ id, n: C.next++, text, emoji, color, scale, options: cedDefaults(scale), rhythm, days: [1, 2, 3, 4, 5, 6, 7], wday: 5, dom: 1, times: [time], until: '23:59', quietWeekend: false, pauseUntil: '',
        on: false, archived: false, tpl: true, perPeriod: 'one', editable: true, backfill: true, note: true, voice, labels: true, labelList: labels, created: now, updated: now });
      C.qs.push(t('voel', 'Hoe voel je je?', '🙂', 'blue', 'smileys5', 'daily', '21:00', ['slecht geslapen', 'moe', 'druk', 'ontspannen', 'gesport'], true), t('werk', 'Hoe was je werk?', '💼', 'green', 'buttons3', 'workdays', '17:30', ['druk', 'rustig', 'veel overleg', 'thuisgewerkt'], false)); return true; },
    checkinState(){ const C = this._ci, now = Date.now(), first = this._ciSeed();
      const qs = C.qs.map(q0 => { const q = JSON.parse(JSON.stringify(q0)), by = this._ciBy(q.id); q.total = C.ans.filter(a => a.q === q.id).length;
        if (!q.archived) { const s = this._ciCurrent(q, now); if (s) q.cur = this._ciSlotJson(q, s, by, now); q.next = this._ciNext(q, now); q.streak = this._ciStreak(q, by, now);
          q.missed7 = this._ciSlots(q, now - 7 * 864e5, now).filter(x => this._ciStatus(q, x, (by[x.key] || []).length > 0, now) === 'missed').length; }
        return q; });
      return JSON.stringify({ questions: qs, first, now, notifOk: C.notifOk, muted: C.muted, calOk: C.calOk, model: C.model }); },
    checkinSave(j){ const C = this._ci, x = JSON.parse(j), now = Date.now();
      if (!String(x.text || '').trim()) return JSON.stringify({ error: 'Vul de vraag in' });
      if (!['buttons2', 'buttons3', 'smileys5', 'score10', 'yesno', 'custom'].includes(x.scale)) return JSON.stringify({ error: 'Kies een soort antwoord' });
      const o = x.options || []; if (o.length < 2 || (x.scale === 'custom' && o.length > 7)) return JSON.stringify({ error: 'Kies 2 tot 7 antwoorden' });
      if (new Set(o.map(v => +v.value)).size !== o.length || o.some(v => isNaN(+v.value))) return JSON.stringify({ error: 'Geef elk antwoord een eigen waarde' });
      if ((x.rhythm === 'weekdays' || x.rhythm === 'multi') && !(x.days || []).length) return JSON.stringify({ error: 'Kies minstens één dag' });
      if (!(x.times || []).length) return JSON.stringify({ error: 'Kies een tijd' });
      const old = x.id ? C.qs.find(q => q.id === x.id) : null; if (x.id && !old) return JSON.stringify({ error: 'Deze vraag bestaat niet meer' });
      const q = Object.assign({}, x, { text: x.text.trim(), times: x.rhythm === 'multi' ? x.times.slice().sort() : x.times.slice(0, 1), labelList: (x.labelList || []).slice(0, 16), tpl: !!(old && old.tpl && !x.on), updated: now });
      ['cur', 'next', 'streak', 'total', 'missed7'].forEach(k => delete q[k]);
      if (old) { q.n = old.n; q.created = old.created; C.qs[C.qs.indexOf(old)] = q; } else { q.id = 'q' + now.toString(36) + C.next; q.n = C.next++; q.created = now; C.qs.push(q); }
      // Zoals CheckinNotify.changed: de periode die nu loopt telt als gemeld; een melding die er staat opnieuw tonen of weg
      const cur = this._ciCurrent(q, now); if (cur && cur.at <= now) C.done[q.id] = cur.key;
      if (C.posted[q.id] && !(q.on && !q.archived)) delete C.posted[q.id]; else if (C.posted[q.id]) C.posted[q.id].reposted = (C.posted[q.id].reposted || 0) + 1;
      C.saved = (C.saved || 0) + 1; return JSON.stringify({ id: q.id }); },
    checkinDelete(id){ const C = this._ci; C.qs = C.qs.filter(q => q.id !== id); C.ans = C.ans.filter(a => a.q !== id); delete C.posted[id]; return ''; },
    checkinMove(id, dir){ const qs = this._ci.qs, i = qs.findIndex(q => q.id === id), j = i + (dir < 0 ? -1 : 1); if (i < 0 || j < 0 || j >= qs.length) return; [qs[i], qs[j]] = [qs[j], qs[i]]; },
    checkinDuplicate(id){ const q = this._ci.qs.find(x => x.id === id); if (!q) return JSON.stringify({ error: 'Deze vraag bestaat niet meer' }); const c = JSON.parse(JSON.stringify(q)); delete c.id; c.text += ' (kopie)'; c.on = false; c.tpl = false; return this.checkinSave(JSON.stringify(c)); },
    checkinAnswer(j){ const C = this._ci, r = JSON.parse(j), now = Date.now(), q = C.qs.find(x => x.id === r.q), err = e => JSON.stringify({ error: e });
      if (!q) return err('Deze vraag bestaat niet meer'); if (q.archived) return err('Deze vraag is gearchiveerd');
      const note = q.note || q.voice ? String(r.note || '').slice(0, 2000) : '', labels = q.labels ? (r.labels || []).filter(l => (q.labelList || []).includes(l)) : [];
      if (r.id) { const a = C.ans.find(x => x.id === r.id); if (!a || a.q !== q.id) return err('Dit antwoord bestaat niet meer');
        if (q.editable === false && now - a.t > 15 * 60000) return err('Wijzigen staat uit bij deze vraag');
        if ('o' in r) { const o = q.options[r.o]; if (!o) return err('Onbekend antwoord'); Object.assign(a, { o: r.o, v: +o.value, l: o.label, e: o.emoji }); }
        if ('note' in r) a.note = note; if ('labels' in r) a.labels = labels; if (r.voice) a.voice = true; a.edited = now;
        return JSON.stringify({ ok: true, id: a.id, key: a.p, late: !!a.late }); }
      const o = q.options[r.o]; if (!o) return err('Onbekend antwoord');
      const s = r.p ? this._ciFind(q, r.p) : this._ciCurrent(q, now); if (!s) return err(r.p ? 'Deze periode hoort niet bij de vraag' : 'Er staat nu geen vraag open');
      if (s.at > now && this._ciKey(new Date(s.at)) !== this._ciKey(new Date(now))) return err('Deze periode is nog niet begonnen');
      const late = now >= s.end; if (late && q.backfill === false) return err('Achteraf invullen staat uit bij deze vraag');
      const have = C.ans.filter(a => a.q === q.id && a.p === s.key);
      if (q.perPeriod !== 'many' && have.length) { if (q.editable === false) return err('Al ingevuld (wijzigen staat uit bij deze vraag)');
        if (!r.replace) { const h = have[have.length - 1]; return JSON.stringify({ need: 'replace', old: ((h.e || '') + ' ' + (h.l || '')).trim() }); }
        C.ans = C.ans.filter(a => !(a.q === q.id && a.p === s.key)); }
      const a = { id: 'a' + now.toString(36) + C.ans.length + Math.floor(Math.random() * 1e4), q: q.id, p: s.key, t: now, o: r.o, v: +o.value, l: o.label, e: o.emoji, note, labels, late, src: r.src === 'notif' ? 'notif' : 'app' };
      if (r.voice && note) a.voice = true; C.ans.push(a); if (C.posted[q.id] && C.posted[q.id].key === s.key) delete C.posted[q.id];
      return JSON.stringify({ ok: true, id: a.id, key: s.key, late }); },
    checkinDeleteAnswer(id){ const C = this._ci, a = C.ans.find(x => x.id === id); if (!a) return 'Dit antwoord bestaat niet meer'; const q = C.qs.find(x => x.id === a.q);
      if (q && q.editable === false && Date.now() - a.t > 15 * 60000) return 'Wijzigen staat uit bij deze vraag'; C.ans = C.ans.filter(x => x.id !== id); return ''; },
    checkinHistory(id, from, to){ const C = this._ci, q = C.qs.find(x => x.id === id), now = Date.now(); if (!q) return JSON.stringify({ error: 'Deze vraag bestaat niet meer' });
      const a = ciDate(from).setHours(0, 0, 0, 0), b = ciDate(to).setHours(0, 0, 0, 0), by = this._ciBy(id), slots = this._ciSlots(q, a, b).map(s => this._ciSlotJson(q, s, by, now)), keys = new Set(slots.map(s => s.key));
      const extra = C.ans.filter(x => x.q === id && !keys.has(x.p) && ciKeyDay(x.p) >= from && ciKeyDay(x.p) < to);
      const vals = slots.flatMap(s => s.answers.map(x => x.v)).concat(extra.map(x => x.v)), counts = q.options.map(o => vals.filter(v => v === +o.value).length);
      const sum = { counts, answered: slots.filter(s => s.status === 'answered').length, missed: slots.filter(s => s.status === 'missed').length, n: vals.length, streak: this._ciStreak(q, by, now) };
      if (vals.length) sum.avg = Math.round(vals.reduce((x, y) => x + y, 0) / vals.length * 100) / 100;
      return JSON.stringify({ q, slots, extra, summary: sum }); },
    checkinDays(from, to){ const C = this._ci, days = {}; Object.keys(C.days).forEach(d => { if (d >= from && d < to) { const x = Object.assign({}, C.days[d]); if (!C.calOk) delete x.cal; days[d] = x; } }); return JSON.stringify({ cal: C.calOk, days }); },
    checkinPreview(id){ const C = this._ci, q = C.qs.find(x => x.id === id); if (!q) return 'Deze vraag bestaat niet meer'; if (!C.notifOk) return 'notif'; const s = this._ciCurrent(q, Date.now()); if (!s) return 'Er is nu geen periode om te beantwoorden'; C.previews.push(id); C.posted[id] = { key: s.key }; return ''; },
    /* De wekker: een melding voor de periode die nu open staat (één per moment). Geeft de knoppen (alleen bij 2 of 3 antwoorden). */
    _ciNotify(id){ const C = this._ci, q = C.qs.find(x => x.id === id); if (!q || !q.on || q.archived) return null; const s = this._ciCurrent(q, Date.now()); if (!s || s.at > Date.now()) return null;
      if (C.done[id] === s.key) return null; C.done[id] = s.key; // één melding per moment
      if (C.ans.some(a => a.q === id && a.p === s.key)) return null; C.posted[id] = { key: s.key }; return q.options.length <= 3 ? q.options.map(o => ((o.emoji || '') + ' ' + o.label).trim()) : []; },
    /* Een knop in de melding (AlarmReceiver): bewaren zonder de app te openen */
    _ciNotifAnswer(id, i){ const C = this._ci, p = C.posted[id]; if (!p) return 'geen melding'; const r = JSON.parse(this.checkinAnswer(JSON.stringify({ q: id, p: p.key, o: i, src: 'notif' }))); delete C.posted[id]; C.confirm = r.ok ? '✓ Bewaard' : (r.error || 'Was al ingevuld'); setTimeout(() => onCheckin({}), 20); return C.confirm; },
    _ai: { st: { klein: { state: 'none' }, groot: { state: 'none' }, klein35: { state: 'none' }, groot35: { state: 'none' }, zoek: { state: 'none' } }, chat: 'klein', wifiOnly: true, notes: true, chats: [], ram: 8e9, calls: [], seq: 0,
      mem: [], learnFacts: ['Woont in Rotterdam.'],
      src: { notes: true, tx: true, sms: false, wa: false, calls: false, contacts: false, sleep: false, checkin: false }, perm: { notes: true, tx: true, sms: true, wa: false, calls: true, contacts: false, sleep: true, checkin: true },
      // [id, naam, beschrijving, grootte, vervangt, waarom, aangeraden geheugen] zoals LocalAi.MODELS
      models: [['klein', 'Klein en snel', 'Qwen3 1.7B · werkt op de meeste telefoons', 1282439584], ['groot', 'Groter en slimmer', 'Qwen3 4B · beter Nederlands, trager', 2497280256, '', '', 7.5e9],
        ['klein35', 'Klein en snel (Qwen3.5)', 'Qwen3.5 2B · werkt op de meeste telefoons', 1396198496, 'klein', 'Nieuwer en beter in Nederlands, iets groter (1,4 GB)'],
        ['groot35', 'Groter en slimmer (Qwen3.5)', 'Qwen3.5 4B · beter Nederlands, trager, 8 GB geheugen aangeraden', 3013027808, 'groot', 'Nieuwer en slimmer, groter (3,0 GB)', 7.5e9],
        ['zoek', 'Slim zoeken', 'Qwen3-Embedding 0.6B · zoeken op betekenis', 639150592]],
      reply: 'Hallo! Ik ben je **assistent**.\n- Eén\n- Twee' },
    _aiReady(id){ return this._ai.st[id].state === 'ready'; },
    // Zoals LocalAi.chatModel: het gekozen model, of anders het eerste taalmodel dat klaar is
    _aiChat(){ const A = this._ai; if (this._aiReady(A.chat)) return A.chat; const m = A.models.find(x => x[0] !== 'zoek' && this._aiReady(x[0])); return m ? m[0] : A.chat; },
    aiState(){ const A = this._ai; return JSON.stringify({ supported: true, engine: true, ram: A.ram, chat: this._aiChat(), wifiOnly: A.wifiOnly, notes: A.notes, voiceSend: A.voiceSend === true, speakVoice: !!A.speakVoice, cfg: Object.assign({}, this._aiCfgDef, A.cfg), memCount: A.mem.length, loaded: '', busy: !!A.busy, src: Object.assign({}, A.src), perm: Object.assign({}, A.perm),
      models: A.models.map(m => Object.assign({ id: m[0], label: m[1], desc: m[2], size: m[3], embed: m[0] === 'zoek', ctx: m[0] === 'groot' ? 3072 : m[0] === 'zoek' ? 1024 : 4096 }, m[4] ? { replaces: m[4], news: m[5] || '' } : {}, m[6] ? { ram: m[6] } : {}, A.st[m[0]] || { state: 'none' })), orphans: A.orphans || 0,
      index: this._aiReady('zoek') ? { items: 3, chunks: 7, at: Date.now() - 6e4, stale: false } : undefined }); },
    aiDownload(id){ const A = this._ai, m = A.st[id]; if (!m) return 'Onbekend model'; A.calls.push(['dl', id]);
      Object.assign(m, { state: 'running', bytes: 0, total: 1e9 }); setTimeout(() => { m.bytes = 5e8; }, 200);
      setTimeout(() => { Object.assign(m, { state: 'verifying', pct: 40 }); }, 450); setTimeout(() => { A.st[id] = { state: 'ready' }; onAiModels(''); }, 700); return ''; },
    aiDelete(id){ this._ai.st[id] = { state: 'none' }; }, aiUse(id){ this._ai.chat = id; }, aiPref(n, on){ const A = this._ai; if (/^src_/.test(n)) { const k = n.slice(4); if (!(k in A.src)) return; A.src[k] = on; A.calls.push(['src', k, on]); if (this._aiReady('zoek')) { setTimeout(() => onAiIndex({ busy: true, done: 1, total: 3 }), 40); setTimeout(() => onAiIndex({ items: 4, chunks: 9, at: Date.now(), quiet: true }), A.idxMs || 150); } return; } if (['wifiOnly', 'notes', 'voiceSend', 'speakVoice'].includes(n)) A[n] = on; },
    // Modellen beheren: zoals Models.json (taalmodellen, zoekmodel en de spraakmodellen)
    _md: { free: 20e9, online: true, metered: false, last: {}, leftovers: 0, txBusy: false, appMs: 500, tts: { state: '', engine: '' }, ttsNext: { state: 'ok', engine: 'Spraakservices van Google' }, calls: [] },
    ttsCheck(){ const D = this._md; D.calls.push('ttsCheck'); D.tts = { state: 'checking', engine: '' }; setTimeout(() => { D.tts = Object.assign({}, D.ttsNext); onAiModels(''); }, 120); },
    ttsSettings(){ this._md.calls.push('ttsSettings'); }, ttsInstall(){ this._md.calls.push('ttsInstall'); }, aiResume(){ this._md.calls.push('aiResume'); },
    modelsState(){ const A = this._ai, D = this._md, T = this._tx, chat = this._aiChat();
      const ai0 = JSON.parse(this.aiState()).models;
      const ai = ai0.map(m => Object.assign(m, { key: 'ai:' + m.id, kind: m.embed ? 'zoek' : 'ai', last: D.last['ai:' + m.id] || 0, inUse: m.state === 'ready' && (m.embed || m.id === chat), version: m.desc.split(' · ')[0] },
        (ai0.find(x => x.replaces === m.id) || {}).id ? { newer: ai0.find(x => x.replaces === m.id).id } : {}));
      const sp = [['base', 'Spraakmodel snel', 59707625], ['small', 'Spraakmodel nauwkeurig', 190085487]].map(([id, label, size]) => {
        const ready = !!T.models[id], run = !ready && T.st.running && T.st.phase === 'download' && T.dl === id;
        return { id, key: 'spraak:' + id, kind: 'spraak', label, desc: 'Spraak naar tekst · whisper ' + id, version: 'Whisper ' + id, size, last: D.last['spraak:' + id] || 0, state: ready ? 'ready' : run ? 'running' : 'none', inUse: ready && T.model === id, bytes: run ? size * (T.st.pct || 0) / 100 : 0, total: size }; });
      const all = ai.concat(sp), used = all.filter(m => m.state === 'ready').reduce((a, m) => a + m.size, 0);
      return JSON.stringify({ free: D.free, online: D.online, metered: D.metered, wifiOnly: A.wifiOnly, supported: true, txBusy: D.txBusy, models: all, used, leftovers: (A.orphans || 0) + (D.leftovers || 0), ram: A.ram, tts: D.tts }); },
    modelsClean(){ const n = (this._ai.orphans || 0) + (this._md.leftovers || 0); this._ai.orphans = 0; this._md.leftovers = 0; return String(n); },
    aiDownloadApp(id){ const A = this._ai, D = this._md, m = A.st[id]; if (!m) return 'Onbekend model'; A.calls.push(['dlApp', id]);
      Object.assign(m, { state: 'running', via: 'app', bytes: m.bytes || 0, total: m.total || 1e9, reason: undefined });
      setTimeout(() => { if (A.st[id] === m) m.bytes = Math.max(m.bytes, 8e8); }, 200);
      setTimeout(() => { if (A.st[id] === m) A.st[id] = { state: 'verifying', pct: 50 }; }, D.appMs);
      setTimeout(() => { if (A.st[id].state === 'verifying') { A.st[id] = { state: 'ready' }; onAiModels(''); } }, D.appMs + 250); return ''; },
    aiIndexStop(){ this._ai.calls.push(['indexStop']); },
    aiIndexAuto(){ this._ai.calls.push(['indexAuto']); },
    // Zoals AiBridge: bezig tot het antwoord (of het stoppen) helemaal klaar is; een tweede vraag intussen wordt geweigerd
    aiChat(j){ const A = this._ai, r = JSON.parse(j); A.asked = (A.asked || 0) + 1; if (A.busy) { A.refused = (A.refused || 0) + 1; return 'De assistent is nog bezig'; } A.last = r; A.busy = true;
      const words = A.reply.split(/(?<= )/); let i = 0;
      // Geheugen zoals LocalAi: "onthoud …" meteen, anders na het antwoord kijken wat er te leren valt
      const cfg = Object.assign({}, this._aiCfgDef, A.cfg), q = r.messages[r.messages.length - 1].content, memOn = cfg.mem !== 'uit';
      const turn = ++A.seq;
      if (A.learningNow) A.learnStop = true; // een nieuwe vraag stopt het leren (zoals LocalAi.chat)
      const rem = /^\s*onthoud\b[\s,]*(?::|dat\b)\s*(.+)$/i.exec(q), fg = rem ? null : /^\s*vergeet\b[\s,]*(?::|dat\b)\s*(.+)$/i.exec(q);
      if (rem && memOn) { const res = JSON.parse(this.aiMemAdd(rem[1], 'gevraagd', r.chat, r.title)); onAiMemory({ saved: [res], explicit: true, pre: true, turn }); }
      if (fg && memOn) { const w = fg[1].toLowerCase(), m = A.mem.find(x => w.split(/\s+/).filter(v => v.length > 3).some(v => x.text.toLowerCase().includes(v)));
        if (m) A.mem = A.mem.filter(x => x !== m); onAiMemory(Object.assign({ forgot: m ? m.text : '', pre: true, turn }, m ? { forgotItem: m } : {})); }
      const used = memOn ? A.mem.length : 0, learn = memOn && !rem && !fg && cfg.learn !== 'uit' && /\b(ik|mijn)\b/i.test(q) && !(r.notes && this._aiReady('zoek')) && !r.messages.some(m => m.oth);
      // Acties zoals LocalAi/AiActions: de regel [[herinnering: JJJJ-MM-DD UU:MM | tekst]] eruit, en als voorstel mee
      const act = this._aiAct(A.reply, r.noAct || r.messages.some(m => m.att)), wantAct = act.wanted(q), lrn = learn && !wantAct && !r.noLearn;
      if (wantAct || r.settings) onAiActWanted();
      const set = this._aiSet(A.reply, r.settings); // instellingen zoals LocalAi/AiSettings
      const tick = () => { if (i < words.length) { onAiToken(words[i++]); A.timer = setTimeout(tick, 60); } else { A.timer = null; A.busy = false; onAiDone(Object.assign({ done: true, n: words.length, turn }, used ? { mem: used } : {}, lrn ? { learning: true } : {}, wantAct && act.clean != null ? { clean: act.clean } : {}, act.action && wantAct ? { action: act.action } : {}, set.clean != null ? { clean: set.clean } : {}, set.list.length ? { settings: set.list } : {}));
        if (lrn) { A.learningNow = true; setTimeout(() => onAiMemory({ thinking: true, turn }), 20); setTimeout(() => {
          A.learningNow = false;
          if (A.learnStop) { A.learnStop = false; onAiMemory({ none: true, stopped: true, turn }); return; }
          if (cfg.learn === 'vanzelf') onAiMemory({ saved: A.learnFacts.map(f => JSON.parse(this.aiMemAdd(f, 'geleerd', r.chat, r.title))), turn }); else onAiMemory({ ask: A.learnFacts.slice(), turn }); }, A.learnMs || 150); } } };
      if (r.notes && this._aiReady('zoek')) { onAiStatus('Zoeken in je gegevens…'); onAiSources([{ kind: 'notes', id: (notesGet()[0] || {}).id || 'x', title: 'Garage', snip: 'APK in maart', score: 0.61 }].concat(this._ai.src.sms ? [{ kind: 'sms', id: '7', title: 'Jos', snip: '[1 okt] Jos: de garage belt morgen', score: 0.5 }] : [])); }
      A.timer = setTimeout(() => { onAiStatus(''); tick(); }, 120); return ''; },
    // Zoals AiSettings: de regels [[instelling: id = waarde]] eruit; alleen meegestuurde instellingen met een bekende waarde
    _aiSet(reply, offer){
      const out = { list: [] }; if (!Array.isArray(offer)) return out;
      const re = /`*\[\[\s*instelling\s*:\s*([^\]\n]*)\]\]`*/gi; let m;
      while ((m = re.exec(reply))) { const p = m[1].split('='), id = (p[0] || '').trim().toLowerCase(), v = (p[1] || '').trim().toLowerCase(), e = offer.find(x => x.id === id);
        const val = e && !e.safe && (e.type === 'tijd' ? (/^\d{1,2}[:.]\d{2}$/.test(v) ? v.replace('.', ':').padStart(5, '0') : null) : ((e.vals || []).find(x => x[0].toLowerCase() === v || String(x[1]).toLowerCase() === v) || [])[0]);
        if (val != null && val !== e.cur) out.list.push({ id, value: val }); }
      if (re.test(reply) || out.list.length || /\[\[\s*instelling/i.test(reply)) out.clean = reply.replace(/`*\[\[\s*instelling\s*:[^\]\n]*\]\]`*/gi, '').replace(/\n{3,}/g, '\n\n').trim();
      return out;
    },
    _aiAct(reply, noAct){
      const out = { wanted: q => !noAct && /herinner|notitie|noteer|agenda|remind/i.test(q) && !/^\s*(onthoud|vergeet)\b/i.test(q) };
      const mt = /`*\[\[\s*(herinnering|notitie|agenda)\s*:\s*([^\]\n]*)\]\]`*/i.exec(reply);
      if (!mt) return out;
      out.clean = reply.replace(mt[0], '').replace(/\n{3,}/g, '\n\n').trim();
      const type = mt[1].toLowerCase(), f = mt[2].split('|').map(x => x.trim());
      if (type === 'notitie') { out.action = { type, title: f.length > 1 ? f[0] : f[0].slice(0, 60), text: f.length > 1 ? f.slice(1).join(' | ') : '', need: '', src: 'model' }; return out; }
      const d = /^(\d{4})-(\d\d)-(\d\d)(?:\s+(\d\d):(\d\d))?$/.exec(f[0] || ''), day = d ? new Date(+d[1], d[2] - 1, +d[3]).getTime() : 0;
      const at = d && d[4] ? new Date(+d[1], d[2] - 1, +d[3], +d[4], +d[5]).getTime() : 0, ok = at > Date.now() + 30000;
      out.action = { type, title: f[1] || '', text: '', src: 'model', need: ok ? '' : day && !at && type === 'agenda' ? '' : day && !at ? 'tijd' : 'datum' };
      if (ok) { out.action.at = at; out.action.day = day; } else if (day && !at) { out.action.day = day; if (type === 'agenda') out.action.allDay = true; }
      return out;
    },
    // Voorlezen zoals AiSpeech: start, klaar (na _tts.ms), stoppen; of een fout (_tts.err)
    _tts: { said: [], stops: 0, ms: 400, cur: '' },
    aiSpeak(text, id){ const T = this._tts; T.said.push(text); clearTimeout(T.t1); clearTimeout(T.t2); T.cur = id;
      if (T.err) { T.t1 = setTimeout(() => { T.cur = ''; onAiSpeak({ state: 'error', id, error: T.err }); }, 20); return; }
      T.t1 = setTimeout(() => onAiSpeak({ state: 'start', id }), 20); T.t2 = setTimeout(() => { T.cur = ''; onAiSpeak({ state: 'done', id }); }, T.ms); },
    aiSpeakStop(){ const T = this._tts; T.stops++; clearTimeout(T.t1); clearTimeout(T.t2); if (T.cur) { const id = T.cur; T.cur = ''; setTimeout(() => onAiSpeak({ state: 'stop', id }), 10); } },
    aiCalendarAdd(j){ if (this._calErr) return this._calErr; this._cal = JSON.parse(j); return ''; },
    aiCancel(){ const A = this._ai; if (A.timer) { clearTimeout(A.timer); A.timer = null; setTimeout(() => { A.busy = false; onAiDone({ done: true, cancelled: true }); }, 20); } },
    aiCopy(t){ this._copied = t; }, aiShare(t){ this._shared = t; },
    _aiCfgDef: { style: 'normaal', creative: 'normaal', lang: 'nl', history: '24', power: 'normaal', idle: '4', mem: 'aan', learn: 'vragen', about: '', instr: '' },
    aiCleanOld(){ const n = this._ai.orphans || 0; this._ai.orphans = 0; return String(n); },
    aiMemList(){ return JSON.stringify(this._ai.mem.slice().sort((a, b) => b.u - a.u)); },
    aiMemAdd(text, src, chat, title){ const A = this._ai, t = String(text || '').trim(); if (!t) return JSON.stringify({ error: 'Er staat niets om te onthouden' });
      const x = t.charAt(0).toUpperCase() + t.slice(1), d = A.mem.find(m => m.text.toLowerCase().replace(/[.!]$/, '') === x.toLowerCase().replace(/[.!]$/, ''));
      if (d) { d.u = Date.now() + (++A.seq); return JSON.stringify({ id: d.id, text: d.text, dup: true }); }
      const m = { id: 'm' + (++A.seq), text: x, src: src || '', chat: chat || '', title: title || '', t: Date.now(), u: Date.now() + A.seq }; A.mem.push(m); return JSON.stringify({ id: m.id, text: m.text }); },
    aiMemEdit(id, text){ const m = this._ai.mem.find(x => x.id === id); if (!m) return 'Niet gevonden'; if (!String(text).trim()) return 'Er staat niets'; m.text = String(text).trim(); m.u = Date.now() + (++this._ai.seq); return ''; },
    aiMemDelete(id){ this._ai.mem = id === '*' ? [] : this._ai.mem.filter(m => m.id !== id); },
    aiConfigSet(n, v){ const A = this._ai, ok = { style: ['normaal', 'kort', 'uitgebreid'], creative: ['normaal', 'precies', 'creatief'], lang: ['nl', 'en', 'auto'], history: ['24', '8', '48'], power: ['normaal', 'zuinig', 'max'], idle: ['4', '1', '15'], mem: ['aan', 'uit'], learn: ['vragen', 'vanzelf', 'uit'] };
      if (n === 'about' || n === 'instr') { A.cfg = Object.assign({}, this._aiCfgDef, A.cfg, { [n]: String(v).slice(0, 1000).trim() }); return ''; }
      if (!ok[n] || !ok[n].includes(v)) return 'Ongeldige instelling'; A.cfg = Object.assign({}, this._aiCfgDef, A.cfg, { [n]: v }); return ''; },
    aiConfigReset(){ this._ai.cfg = null; },
    aiVoiceStart(){ if (this._voiceErr) return this._voiceErr; if (this._tx && !this._tx.models.base && !this._tx.models.small) return 'model'; this._vt = Date.now(); this._aiRec = true; return ''; },
    aiVoiceStop(){ this._aiRec = false; setTimeout(() => onAiVoice(this._voiceResult || { text: 'Wat is de hoofdstad van Frankrijk?' }), 80); },
    aiVoiceCancel(){ this._aiRec = false; this._aiVoiceCancelled = true; },
    aiVoiceState(){ return this._aiVoiceState || (this._aiRec ? 'recording' : 'idle'); },
    // Live zoals VoiceNote.liveJson: niveau en (na even praten) de tussentekst _voicePartial
    aiVoiceLive(){ const r = !!this._aiRec, ms = r ? Date.now() - (this._vt || Date.now()) : 0; return JSON.stringify({ state: this.aiVoiceState(), ms, live: r, lv: r ? [Math.round((0.2 + 0.6 * Math.abs(Math.sin(ms / 300))) * 100) / 100] : [], partial: r && this._voicePartial && ms > 250 ? this._voicePartial : '' }); },
    aiVoiceInfo(){ const t = this._tx; const m = t.models.small ? 'small' : t.models.base ? 'base' : ''; return JSON.stringify({ supported: true, model: m, label: m ? 'Snel (57 MB)' : '', busy: !!t.st.running, send: this._ai.voiceSend === true }); },
    aiChats(){ return JSON.stringify(this._ai.chats.map(c => ({ id: c.id, title: c.title, t: c.t, n: c.msgs.length }))); },
    aiChatGet(id){ const c = this._ai.chats.find(x => x.id === id); return c ? JSON.stringify(c) : ''; },
    aiChatSave(j){ const c = JSON.parse(j); c.id = c.id || 'c' + (++this._ai.seq); c.t = Date.now(); this._ai.chats = [c].concat(this._ai.chats.filter(x => x.id !== c.id)); return c.id; },
    aiChatDelete(id){ this._ai.chats = id === '*' ? [] : this._ai.chats.filter(x => x.id !== id); },
    aiSearch(q){ const id = ++this._ai.seq; this._ai.calls.push(['search', q]); setTimeout(() => onAiSearch({ id, q, items: [{ kind: 'notes', id: (notesGet()[0] || {}).id || 'x', title: 'Auto naar de garage', snip: 'APK-keuring in maart, banden wisselen', t: Date.now(), score: 0.58 }, { kind: 'tx', id: 't1', title: 'Jos', snip: 'we spraken over de garage', from: 12000, score: 0.44 }].concat(this._ai.src.sms ? [{ kind: 'sms', id: '7', title: 'Jos', snip: '[1 okt] Jos: de garage belt morgen', score: 0.4 }] : [], this._ai.src.contacts ? [{ kind: 'contacts', id: 'k1|5', title: 'Garage Jansen', snip: 'Contact: Garage Jansen', score: 0.35 }] : []), updating: !!this._ai.updating }), 80); return id; },
    aiIndex(force){ this._ai.calls.push(['index', force]); setTimeout(() => onAiIndex({ busy: true, done: 2, total: 4 }), 50); setTimeout(() => onAiIndex({ items: 3, chunks: 7, at: Date.now() }), this._ai.idxMs || 200); },
    _dl: { list: [], prefs: { wifiOnly: true, autoDelete: true } },
    podDlStart(j){ const o = JSON.parse(j); if (this._dl.list.some(d => d.key === o.ep.key)) return ''; this._dl.list.unshift({ key: o.ep.key, ep: o.ep, pod: o.pod, t: Date.now(), state: 'running', bytes: 0, total: 40e6 });
      const d = this._dl.list[0]; setTimeout(() => { d.bytes = 2e7; }, 300); setTimeout(() => { d.state = 'done'; d.bytes = d.total; }, 900); return ''; },
    podDlRemove(k){ this._dl.list = this._dl.list.filter(d => d.key !== k); }, podDlClear(){ this._dl.list = []; },
    podDlList(){ return JSON.stringify(this._dl.list); }, podDlPrefs(){ return JSON.stringify(this._dl.prefs); }, podDlPref(n, on){ this._dl.prefs[n] = on; },
    podAutoDl(id, on){ const s = this._pc.subs.find(x => x.id === id); if (!s) return 'Volg de podcast eerst'; s.dl = on; return ''; }, podSeen(id){ this._pc.subs.forEach(x => { if (x.id === id) x.fresh = 0; }); },
    podRefresh(){ this._pc.refreshed = Date.now(); setTimeout(() => onPodRefreshed({ fresh: 0 }), 100); }, podRefreshedAt(){ return this._pc.refreshed; },
    podContinue(){ return JSON.stringify(this._pc.recent.filter(r => !(this._pc.prog[r.ep.key] || {}).done).map(r => Object.assign({}, r, { ep: Object.assign({}, r.ep, { pos: (this._pc.prog[r.ep.key] || {}).p || 0, pd: (this._pc.prog[r.ep.key] || {}).d || 0 }) }))); },
    podForget(k){ this._pc.recent = this._pc.recent.filter(r => r.ep.key !== k); },
    podMarkPlayed(k, d){ this._pc.prog[k] = { p: 0, d: 0, done: d }; if (d && this._dl.prefs.autoDelete) this._dl.list = this._dl.list.filter(x => x.key !== k); },
    _pcSave(){ const st = this._pc.st; if (st.ep) this._pc.prog[st.ep.key] = { p: this._pcPos(), d: st.dur, done: false }; },
    _pcPos(){ const st = this._pc.st; return st.status === 'playing' ? Math.min(st.dur, st.pos + (Date.now() - st.at) * this._pc.speed) : st.pos; },
    podPlay(j){ const o = JSON.parse(j); if (!/^https?:/.test(o.ep.url || '')) return 'Deze aflevering heeft geen geldig adres';
      if (this._cast && this._cast.active) { Object.assign(this._cast, { src: 'podcast', title: o.ep.title, key: o.ep.key, ep: o.ep, pod: o.pod, pos: 0, dur: (o.ep.dur || 0) * 1000, playing: true, status: 'playing' }); return ''; } this._take('podcast'); this._pcSave(); this._pc.q = (this._pc.q || []).filter(r => r.ep.key !== o.ep.key);
      const saved = this._pc.prog[o.ep.key], from = o.fromStart || !saved || saved.done ? 0 : saved.p;
      this._pc.st = { status: 'connecting', ep: o.ep, pod: o.pod, pos: from, dur: (o.ep.dur || 0) * 1000, at: Date.now(), sleepAt: this._pc.st.sleepAt || 0, sleepEnd: !!this._pc.st.sleepEnd };
      this._pc.recent = [{ ep: o.ep, pod: o.pod, t: Date.now() }].concat(this._pc.recent.filter(r => r.ep.key !== o.ep.key));
      setTimeout(() => { if (this._pc.st.status === 'connecting') { this._pc.st.status = 'playing'; this._pc.st.at = Date.now(); } }, 150); return ''; },
    podPause(){ const st = this._pc.st; st.pos = this._pcPos(); st.status = 'paused'; this._pcSave(); },
    podResume(){ const st = this._pc.st; if (st.ep) { this._take('podcast'); st.status = 'playing'; st.at = Date.now(); } },
    podStop(){ this._pcSave(); this._pc.st = { status: 'stopped' }; },
    podSeek(ms){ const st = this._pc.st; st.pos = ms; st.at = Date.now(); this._pc.lastSeek = ms; },
    podSkip(s){ const st = this._pc.st; st.pos = Math.max(0, this._pcPos() + s * 1000); st.at = Date.now(); this._pc.lastSkip = s; },
    podSpeed(v){ this._pc.st.pos = this._pcPos(); this._pc.st.at = Date.now(); this._pc.speed = +v; },
    podSleep(m){ const st = this._pc.st; if (!st.ep) return m ? 'Start eerst een aflevering' : ''; st.sleepEnd = m === -1; st.sleepAt = m > 0 ? Date.now() + m * 6e4 : 0; this._pc.lastSleep = m; return ''; },
    podSleepAdd(m){ const st = this._pc.st; if (!st.ep) return 'Start eerst een aflevering'; st.sleepAt = Math.max(st.sleepAt || 0, Date.now()) + m * 6e4; st.sleepEnd = false; return ''; },
    podState(){ const st = this._pc.st; return JSON.stringify(Object.assign({}, st, { pos: this._pcPos(), speed: this._pc.speed })); },
    podShare(t, l){ this._pc.shared = [t, l]; },
    /* De ene speler: wie begint, neemt het over */
    _src: '', playerSrc(){ return this._src; },
    _take(src){ this._src = src;
      if (src === 'podcast' && this._rd.st.status && this._rd.st.status !== 'stopped') this._rd.st = { status: 'stopped', title: '', last: this._rd.st.station };
      if (src === 'radio' && this._pc.st.ep) { if (this._pc.st.status === 'playing') { this._pc.st.pos = this._pcPos(); this._pc.st.status = 'paused'; this._pcSave(); } this._pc.st.sleepAt = 0; this._pc.st.sleepEnd = false; } },
    playerArt(u){ this._artAsked = (this._artAsked || []).concat([u]); setTimeout(() => onPlayerArt({ url: u, color: '#7a2a1e' }), 30); },
    playerNext(src){ return this._step(1, src); }, playerPrev(src){ return this._step(-1, src); },
    _step(d, src){
      if ((src || this._src) === 'radio') { const f = this._rd.favs; if (!f.length) return 'Zet zenders bij je favorieten (☆) om te wisselen';
        const cur = this._rd.st.station || this._rd.st.last || {}; let i = f.findIndex(s => s.url === cur.url); if (i < 0 && d < 0) i = 0; i = ((i + d) % f.length + f.length) % f.length; this.radioPlay(JSON.stringify(f[i])); this._lastStep = d; return ''; }
      const st = this._pc.st; this._lastStep = d;
      if (d < 0) { if (st.ep) this.podSeek(0); return st.ep ? '' : 'Er speelde nog niets'; }
      const q = this._pc.q || []; if (!q.length) return 'Geen volgende aflevering. Zet er een in Hierna via ⋯ bij een aflevering.';
      const n = q.shift(); this.podPlay(JSON.stringify(n)); return ''; },
    podQueue(){ return JSON.stringify(this._pc.q || []); },
    podQueueAdd(j, next){ const o = JSON.parse(j); if (this._pc.st.ep && this._pc.st.ep.key === o.ep.key && this._pc.st.status !== 'ended') return 'Deze aflevering speelt al';
      const q = (this._pc.q || []).filter(r => r.ep.key !== o.ep.key); if (next) q.unshift(o); else q.push(o); this._pc.q = q; return ''; },
    podQueueRemove(k){ this._pc.q = (this._pc.q || []).filter(r => r.ep.key !== k); },
    podQueueMove(k, b){ const q = this._pc.q || []; const a = q.findIndex(r => r.ep.key === k); if (a >= 0) q.splice(Math.max(0, Math.min(q.length - 1, b)), 0, q.splice(a, 1)[0]); },
    podQueueClear(){ this._pc.q = []; },
    podQueuePlay(k){ const r = (this._pc.q || []).find(x => x.ep.key === k); if (!r) return 'Staat niet meer in Hierna'; return this.podPlay(JSON.stringify(r)); },
    _float: { on: false, allowed: false }, floatState(){ return JSON.stringify(this._float); },
    floatSet(on){ this._float.on = on; if (on && !this._float.allowed) { this._float.allowed = true; return 'perm'; } return ''; },
    /* Chromecast (nagebootst) */
    _cast: { active: false }, _castDevs: [{ id: 'cc1', name: 'Woonkamer', model: 'Chromecast' }],
    castScan(){ setTimeout(() => onCastDevices({ devices: this._castDevs }), 120); },
    castStart(id){ const d = this._castDevs.find(x => x.id === id); if (!d) return 'Dit apparaat is niet (meer) gevonden; zoek opnieuw';
      const pod = this._src !== 'radio' && this._pc.st.ep, st = this._rd.st.station || this._rd.st.last;
      if (pod) { this._pc.st.pos = this._pcPos(); this._pcSave(); }
      this._cast = { active: true, id: Date.now(), device: d.name, status: 'playing', playing: true, src: pod ? 'podcast' : 'radio', title: pod ? this._pc.st.ep.title : (st || {}).name,
        sub: 'Op ' + d.name, art: '', key: pod ? this._pc.st.ep.key : (st || {}).url, pos: pod ? this._pc.st.pos : 0, dur: pod ? this._pc.st.dur : 0, volume: 0.5, rate: 1,
        ep: pod ? this._pc.st.ep : undefined, pod: pod ? this._pc.st.pod : undefined, station: pod ? undefined : st };
      if (pod) this._pc.st = { status: 'stopped' }; else this._rd.st = { status: 'stopped', title: '', last: st };
      return ''; },
    castState(){ return JSON.stringify(this._cast); },
    castStop(local){ const c = this._cast; this._cast = { active: false }; this._castStopped = local ? 'local' : 'stop';
      if (local && c.ep) this.podPlay(JSON.stringify({ ep: c.ep, pod: c.pod })); else if (local && c.station) this.radioPlay(JSON.stringify(c.station)); },
    castForget(){ if (!this._cast.active) this._cast = { active: false }; }, castVolume(v){ this._cast.volume = +v; },
    playerPlayPause(){ const c = this._cast; if (c.active) { c.playing = !c.playing; c.status = c.playing ? 'playing' : 'paused'; } },
    outputSwitcher(){ this._switcher = (this._switcher || 0) + 1; return ''; },
    /* Spotify-backup (nagebootst) */
    _sp: { linked: false, clientId: '', user: '', auto: 'day', lastAt: 0, versions: [], log: '' },
    spotifyState(){ const s = this._sp; const own = (this._spNow || []).filter(p => p.own); return JSON.stringify({ libRelink: !!this._spRelink, linked: s.linked, clientId: s.clientId, user: s.user, auto: s.auto, busy: !!s.busy, progress: s.busy ? 'Playlist 1 van 3: Roadtrip' : '',
      lastAt: s.lastAt, lastFailAt: s.lastFailAt || 0, lastMsg: s.lastMsg || '', own: own.length, followed: (this._spNow || []).length - own.length, tracks: own.reduce((a, p) => a + p.n, 0), versions: s.versions.length, hasBackup: !!this._spNow, folder: true, folderErr: s.folderErr || '' }); },
    spotifyLink(id){ this._sp.clientId = id; setTimeout(() => { this._sp.linked = true; this._sp.user = 'Rene'; onSpotifyLinked({ name: 'Rene' }); }, 200); return ''; },
    spotifyUnlink(){ this._sp.linked = false; }, spotifyAuto(m){ this._sp.auto = m; },
    _spPodcastShows: [{id:'AAAAAAAAAAAAAAAAAAAAAA',title:'Nieuws van Vandaag',author:'Omroep X'},{id:'BBBBBBBBBBBBBBBBBBBBBB',title:'Tech in 20 minuten',author:'Rene & Co'}],
    spotifyPodcastShows(backup,request){this._spPodcastSource=backup?'backup':'live';setTimeout(()=>onSpotifyPodcasts(!backup&&!this._sp.linked?{request,error:'Koppel Spotify om je gevolgde podcasts op te halen',relink:true}:this._spPodcastError?{request,error:this._spPodcastError}:{request,shows:this._spPodcastShows,source:backup?'backup':'live',at:Date.now()- (backup?864e5:0)}),80);},
    spotifyPodcastSearch(id,q,request){this._pc.calls.push(['spotify-search',id,q,Date.now()]);setTimeout(()=>onSpotifyPodcastSearch(this._spPodcastSearchError?{id,request,error:this._spPodcastSearchError}:{id,request,results:this._spPodcastResults&&Object.prototype.hasOwnProperty.call(this._spPodcastResults,id)?this._spPodcastResults[id]:this._pc.top.filter(p=>p.title.toLowerCase().includes(q.toLowerCase()))}),this._spPodcastSearchDelay||100);},
    spotifyBackup(plain){ this._spPlain = plain; this._sp.busy = true; setTimeout(() => { this._sp.busy = false; const first = !this._spNow;
      this._spNow = [{ id: 'p1', name: 'Roadtrip', snap: 's2', own: true, n: 3, url: 'https://open.spotify.com/playlist/p1' }, { id: 'p2', name: 'Avondeten', snap: 't1', own: true, n: 2, coll: true }, { id: 'f1', name: 'Top 50 NL', own: false, n: 50, owner: 'Spotify', url: 'https://open.spotify.com/playlist/f1' }];
      this._sp.lastAt = Date.now(); this._sp.log = 'vandaag\n  Roadtrip: +1 (Radar Love – Golden Earring)\n'; onSpotifyDone({ own: 2, followed: 1, tracks: 5, changed: true, lines: first ? ['Nieuw: Roadtrip (3)'] : [], folder: 'ok' }); }, 300); },
    spotifyPlaylists(){ return JSON.stringify(this._spNow || []); },
    spotifyGone(){ return JSON.stringify(this._spNow ? [{ id: 'g1', name: 'Oude zomer', snap: 'g', own: true, n: 4, t: Date.now() - 864e6 }] : []); },
    spotifyVersions(id){ return JSON.stringify(id === 'p1' ? [{ t: Date.now(), snap: 's2', n: 3, name: 'Roadtrip', plus: 1, min: 0, ok: true }, { t: Date.now() - 864e5, snap: 's1', n: 2, name: 'Roadtrip', ok: true }] : [{ t: Date.now(), snap: 'x', n: 2, name: 'x', ok: true }]); },
    spotifyTracks(id, snap){ return JSON.stringify({ total: 2, items: [{ u: 'spotify:track:a', n: 'Radar Love', a: 'Golden Earring' }, { u: 'spotify:local:x', n: 'Eigen opname', a: '', l: true }] }); },
    spotifyRestore(id, snap, mode){ this._spRestored = [id, snap, mode]; setTimeout(() => onSpotifyRestored({ id: 'new1', added: 1, skipped: 1, name: 'Roadtrip', url: 'https://open.spotify.com/playlist/new1' }), 100); },
    // Locatie kiezen
    _lc: { on: false, allowed: false, dev: false, perm: true, jitter: true, places: [], recent: [], name: '', lat: null, lng: null },
    locState(){ const l = this._lc; const o = Object.assign({}, l); if (l.lat == null) { delete o.lat; delete o.lng; } return JSON.stringify(o); },
    locStart(lat, lng, name){ const l = this._lc; if (!l.perm) return 'perm'; if (!l.allowed) return 'mock'; Object.assign(l, { on: true, lat, lng, name: name || '' }); l.recent = [{ lat, lng, name: name || '' }].concat(l.recent.filter(p => p.lat !== lat || p.lng !== lng)).slice(0, 8); setTimeout(() => onLocChanged(), 50); return ''; },
    locStop(){ this._lc.on = false; setTimeout(() => onLocChanged(), 50); }, locJitter(on){ this._lc.jitter = on; },
    locSave(n, lat, lng){ if (!String(n || '').trim()) return 'Geef de plek een naam'; this._lc.places.push({ name: n.trim(), lat, lng }); return ''; }, locDelete(i){ this._lc.places.splice(i, 1); },
    locDevSettings(){ this._lc.dev = true; this._lc.allowed = true; }, locAboutPhone(){ this._lc.dev = true; },
    locSearch(q){ setTimeout(() => onLocSearch(/nergens/.test(q) ? { q, results: [] } : { q, results: [{ name: 'Coolsingel 40, 3011 AD Rotterdam', lat: 51.9225, lng: 4.4792 }, { name: 'Coolsingel, Rotterdam', lat: 51.9205, lng: 4.4782 }] }), 80); },
    // VPN
    _vp: { tun: [], on: false, status: 'off', id: '', active: '', auto: { wifi: false, home: true, mobile: false, trusted: [] }, ssid: 'Thuisnetwerk', consent: false, rx: 0, tx: 0, err: '' },
    vpnState(){ const v = this._vp; return JSON.stringify({ on: v.on, status: v.status, id: v.on ? v.id : '', name: v.on ? (v.tun.find(t => t.id === v.id) || {}).name : '', error: v.err, since: Date.now(), reason: v.on ? 'app' : '', active: v.active || (v.tun[0] || {}).id || '', consent: v.consent, rx: v.rx, tx: v.tx, handshake: v.on && v.status === 'connected' ? Date.now() - 4000 : 0, endpoint: v.on ? '203.0.113.5:51820' : '', activeId: v.on ? (v.via || v.id) : undefined, activeName: v.on ? (v.tun.find(t => t.id === (v.via || v.id)) || {}).name : undefined, kind: v.on ? ((v.tun.find(t => t.id === (v.via || v.id)) || {}).type === 'ovpn' ? 'OpenVPN' : 'WireGuard') : undefined, alwaysOn: v.on ? false : undefined, lockdown: false, auto: v.auto, ssid: v.ssid, wifi: true, locOk: true, bgLocOk: !!this._vpBg }); },
    vpnTunnels(){ return JSON.stringify(this._vp.tun.map(t => { const c = Object.assign({}, t); delete c.conf; delete c.pass; return c; })); },
    vpnConfig(id){ const t = this._vp.tun.find(x => x.id === id); return JSON.stringify(t ? { text: t.conf, user: t.user || '', hasPass: !!t.pass } : { error: 'Deze tunnel bestaat niet meer' }); },
    vpnSetFallback(id, j){ const t = this._vp.tun.find(x => x.id === id); if (t) t.fallback = JSON.parse(j).filter(x => x !== id); },
    vpnCheck(text){
      if (/^\s*client\b/m.test(text)) {
        const rm = /^\s*remote\s+(\S+)\s*(\d*)\s*(\S*)/m.exec(text); if (!rm) return JSON.stringify({ error: "Er staat geen 'remote' (server) in" });
        if (!/<ca>/.test(text)) return JSON.stringify({ error: 'Het CA-certificaat (<ca>) ontbreekt; zonder kan de server niet gecontroleerd worden' });
        const tcp = /tcp/.test(rm[3] || '') || /^proto tcp/m.test(text), port = rm[2] || '1194';
        return JSON.stringify({ ok: true, summary: { type: 'ovpn', endpoint: rm[1] + ':' + port, summary: rm[1] + ':' + port + (tcp ? ' TCP' : ' UDP'), auth: /auth-user-pass/.test(text), tcp, warnings: [], addr: [], dns: [], allowed: [] } });
      }
      if (!/\[Interface\]/i.test(text)) return JSON.stringify({ error: 'PrivateKey ontbreekt bij [Interface]' });
      if (!/PrivateKey\s*=\s*\S{43}=/.test(text)) return JSON.stringify({ error: 'PrivateKey is geen geldige sleutel' });
      const ep = /Endpoint\s*=\s*(\S+:\d+)/.exec(text); if (!ep) return JSON.stringify({ error: 'Endpoint (adres van de server) ontbreekt' });
      if (!/PublicKey\s*=\s*\S{43}=/.test(text)) return JSON.stringify({ error: 'PublicKey ontbreekt' });
      const al = (/AllowedIPs\s*=\s*(.+)/.exec(text) || [, ''])[1].split(',').map(x => x.trim()).filter(Boolean), dns = (/DNS\s*=\s*(.+)/.exec(text) || [, ''])[1].split(',').map(x => x.trim()).filter(Boolean);
      return JSON.stringify({ ok: true, summary: { endpoint: ep[1], full: al.some(x => /\/0$/.test(x)), allowed: al, dns: dns, addr: ['10.0.0.2/32'], pub: 'cHViLWtleS1tb2NrLWFhYWFhYWFhYWFhYWFhYWFhYWE=' } });
    },
    vpnSave(id, name, text, user, pass){
      const c = JSON.parse(this.vpnCheck(text)); if (c.error) return JSON.stringify(c);
      const v = this._vp, nid = id || 't' + (v.tun.length + 1), prev = v.tun.find(x => x.id === nid) || {};
      if (c.summary.auth && !(user || prev.user) ) return JSON.stringify({ error: 'Vul je gebruikersnaam en wachtwoord van de VPN in' });
      if (c.summary.auth && !(pass || prev.pass)) return JSON.stringify({ error: 'Vul je gebruikersnaam en wachtwoord van de VPN in' });
      const t = Object.assign({ id: nid, name: name || c.summary.endpoint.split(':')[0], conf: text, type: c.summary.type || 'wg', fallback: prev.fallback || [] }, c.summary);
      if (c.summary.auth) { t.user = user || prev.user; t.pass = pass || prev.pass; }
      const i = v.tun.findIndex(x => x.id === nid); if (i >= 0) v.tun[i] = t; else { if (id) return JSON.stringify({ error: 'Deze tunnel bestaat niet meer' }); v.tun.push(t); }
      if (!v.active) v.active = nid; this._vpSaved = text; return JSON.stringify({ id: nid, restart: false });
    },
    vpnDelete(id){ const v = this._vp; v.tun = v.tun.filter(t => t.id !== id); if (v.id === id) { v.on = false; v.status = 'off'; } if (v.active === id) v.active = (v.tun[0] || {}).id || ''; },
    vpnSetActive(id){ this._vp.active = id; },
    vpnNewKey(){ return JSON.stringify({ priv: 'cHJpdi1rZXktbW9jay1hYWFhYWFhYWFhYWFhYWFhYWE=', pub: 'cHViLWtleS1tb2NrLWFhYWFhYWFhYWFhYWFhYWFhYWE=' }); },
    vpnPubOf(p){ return /^\S{43}=$/.test(p) ? 'cHViLWtleS1tb2NrLWFhYWFhYWFhYWFhYWFhYWFhYWE=' : ''; },
    vpnStart(id){ const v = this._vp; if (!v.tun.length) return 'Stel eerst een tunnel in'; v.via = this._vpBlocked && (v.tun.find(x => x.id === (id || v.active)) || {}).fallback ? (v.tun.find(x => x.id === (id || v.active)).fallback[0] || '') : '';
      if (!v.consent) { v.consent = true; setTimeout(() => { this.vpnStart(id); onVpnChanged(''); }, 300); return 'consent'; }
      v.on = true; v.id = id || v.active; v.status = 'connecting'; setTimeout(() => { v.status = 'connected'; v.rx = 123456; v.tx = 45678; }, 300); return ''; },
    vpnStop(){ const v = this._vp; v.on = false; v.status = 'off'; },
    vpnAuto(j){ this._vp.auto = JSON.parse(j); },
    vpnTrustCurrent(){ const a = this._vp.auto; if (!a.trusted.includes(this._vp.ssid)) a.trusted.push(this._vp.ssid); return this._vp.ssid; },
    vpnSystemSettings(){ this._vpSys = true; }, vpnAddTile(){ setTimeout(() => onVpnTile('2'), 50); return ''; }, vpnPinToggle(){ this._pinned = 'vpn-toggle'; return ''; },
    vpnPickFile(){ setTimeout(() => onVpnImported({ name: 'mullvad-nl', text: '[Interface]\nPrivateKey = cHJpdi1rZXktbW9jay1hYWFhYWFhYWFhYWFhYWFhYWE=\nAddress = 10.64.1.2/32\nDNS = 10.64.0.1\n\n[Peer]\nPublicKey = c2VydmVyLWtleS1tb2NrLWFhYWFhYWFhYWFhYWFhYWE=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = nl-ams-wg-001.relays.mullvad.net:51820\n' }), 50); },
    vpnPickImage(){ setTimeout(() => onVpnImage({ error: 'Afbeelding niet te lezen' }), 50); },
    vpnCamera(){ setTimeout(() => onVpnCamera(false), 50); }, vpnCameraDone(){}, vpnCopy(t){ this._copied = t; },
    spotifyLib(){ if (!this._spNow) return JSON.stringify({ kinds: [], relink: !!this._spRelink });
      return JSON.stringify({ relink: !!this._spRelink, kinds: [{ kind: 'tracks', name: 'Nummers die je leuk vindt', n: 812, gone: 2, restore: true }, { kind: 'artists', name: 'Artiesten die je volgt', n: 64, gone: 0, restore: true },
        { kind: 'albums', name: 'Bewaarde albums', n: 31, gone: 0, restore: true }, { kind: 'shows', name: 'Podcasts die je volgt', n: 9, gone: 0, restore: true }, { kind: 'episodes', name: 'Bewaarde afleveringen', n: 0, gone: 0, restore: true },
        { kind: 'audiobooks', name: 'Luisterboeken', n: -1, gone: 0, restore: true }, { kind: 'top-artists-short', name: 'Top-artiesten (4 weken)', n: 50, gone: 0, restore: false }, { kind: 'recent', name: 'Laatst geluisterd', n: 150, gone: 0, restore: false }] }); },
    spotifyLibList(kind, gone){ return JSON.stringify(gone ? { total: 2, items: [{ u: 'spotify:track:x1', n: 'Radar Love', a: 'Golden Earring' }, { u: 'spotify:track:x2', n: 'Ik wil je', a: 'De Kast' }] } : { total: 2, items: [{ u: 'spotify:track:y', n: 'Zoutelande', a: 'BLØF', p: '2026-10-13T08:00:00Z' }, { u: 'spotify:artist:z', n: 'Racoon', a: '' }] }); },
    spotifyLibRestore(kind, mode){ this._spLibRestored = [kind, mode]; setTimeout(() => onSpotifyLibRestored({ kind, name: 'Nummers die je leuk vindt', done: mode === 'gone' ? 2 : 812, skipped: 0 }), 100); },
    spotifyLog(){ return this._sp.log; }, spotifyImport(){ setTimeout(() => onSpotifyImported({ count: 3 }), 100); }, spotifyCopyRedirect(){ this._spCopied = true; },
    radioShiftTo(b){ const st = this._rd.st; if (st.shift) st.shift.behind = Math.max(6, b); this._shiftTo = b; },
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
    shortcutPin(t,l){ return ''; },
    soloTool(){ return window.__testSolo || ''; }, soloList(){ return JSON.stringify(this._solo || []); },
    soloSet(t, on){ const s = new Set(this._solo || []); if (on) s.add(t); else s.delete(t); this._solo = [...s]; },
    soloPin(t, l, c, g){ this._soloPinned = [t, l, c, g]; return (this._solo || []).includes(t) ? '' : 'Zet deze losse app eerst aan bij Instellingen'; }, shortcutPinStation(j){ return ''; }, shortcutsDynamic(j){ this._dyn=j; },
    shortcutPinPodcast(j){ this._pinned='pod|'+JSON.parse(j).feed; return ''; }, shortcutPinPlayer(){ this._pinned='player'; return ''; },
    _st:{lock:false,timeout:60000,crashes:0},
    settingsInfo(){ const t=this._st; return JSON.stringify({dest:this._wa.info.dest,destName:'USB-stick',audd:this._mu.token?'abc…xyz':'',crashes:t.crashes,lock:JSON.parse(this.lockState()),
      perms:[{id:'phone',name:'Bellen',used:'Auto redial',ok:true},{id:'mic',name:'Microfoon',used:'Muziek herkennen',ok:this._mu.mic},{id:'files',name:'Alle bestanden',used:'WhatsApp backup, Gesprekken',ok:false}]}); },
    permRequest(id){ if(id==='mic') { this._mu.mic=true; if (this._sl) this._sl.mic = true; } if (id==='notif' && this._sl) this._sl.notif = true; if (id==='notif' && this._ci) this._ci.notifOk = true; if (id==='calendar' && this._ci) this._ci.calOk = true; const ap = { contacts: 'contacts', contactsread: 'contacts', calllog: 'calls', sms: 'sms' }; if (ap[id] && this._ai) this._ai.perm[ap[id]] = true; (this._permAsked = this._permAsked || []).push(id); setTimeout(()=>onSettingsChanged(''),100); },
    crashShare(){ toast('(delen foutrapport)'); }, notesVersion(){ return this._nv || 1; }, historyStamp(){ return [this._historyRows.length, this._historyEnabled, this._historyAllowed].join(':'); }, overnight(){ return this._seen ? '{}' : JSON.stringify({since: Date.now() - 8 * 36e5, backup: {t: Date.now() - 4 * 36e5, failed: 0}, missed: [{who: 'Tandarts', t: Date.now() - 2 * 36e5}], auto: [{t: Date.now() - 36e5, name: 'Parkeren', what: 'Gestart vanuit de melding'}]}); }, overnightSeen(){ this._seen = true; },
    backupCheckLast(){ return this._chk || '{}'; }, backupCheckRun(){ this._chk = JSON.stringify({t: Date.now(), ok: true, items: [{what: 'Backup-map', ok: true, msg: 'Backup'}, {what: 'Proef-terugzetten', ok: true, msg: '✓ Notities leesbaar (12).'}], summary: 'ok'}); setTimeout(() => onBackupCheck(JSON.parse(this._chk)), 50); },
    webStoreSave(j){ this._webStore = j; }, webStorePending(){ return ''; },
    notifMute(on){ this._notifMuted = !!on; },
    notifState(){ return JSON.stringify({muted: !!this._notifMuted, allowed: !this._notifOff, perm: !this._notifOff, kinds: [{id: 'reminders', name: 'Herinneringen', desc: 'Herinneringen die je bij een notitie zet', on: true, level: 4}, {id: 'radio', name: 'Radio', desc: 'Bediening van de radio', on: false, level: 0}]}); },
    notifOpen(id){ this._notifOpen = id; }, notifAsk(){ this._notifOff = false; this._notifAsk = true; },
    uiReady(){ this._uiReady = true; }, crashCopy(){ this._copied = true; toast('Foutrapport gekopieerd'); }, freshInstall(){ return true; }, crashClear(){ this._st.crashes=0; }, crashNew(){ return false; }, logJs(m){ this._jsErr=m; },
    lockState(){ const t=this._st; return JSON.stringify({on:t.lock,locked:!!t.locked,timeout:t.timeout,secure:t.secure!==false,code:!!t.code,decoy:!!t.decoy&&!!t.code,bio:!!t.code&&!!t.codeBio&&t.secure!==false,codeBio:!!t.codeBio,wait:0}); },
    lockTryCode(c){ const t=this._st; t.tries=(t.tries||[]).concat([c]); let r; if (c===t.code) { t.locked=false; setTimeout(()=>onUnlocked(),20); r=''; } else if (t.decoy) { t.neutral=true; r='neutral'; } else r='Verkeerde code'; setTimeout(()=>onLockResult(r),30); },
    lockBio(){ this._st.bioAsked=true; },
    lockCodeSet(c, old){ this._st.code=c; setTimeout(()=>onSettingsChanged(''),50); }, lockCodeClear(old){ this._st.code=null; this._st.decoy=false; setTimeout(()=>onSettingsChanged(''),50); },
    lockDecoySet(on){ this._st.decoy=on; }, lockCodeBio(on){ this._st.codeBio=on; setTimeout(()=>onSettingsChanged(''),30); },
    lockSetCode(on, c){ if (c!==this._st.code) { setTimeout(()=>onSettingsChanged('De app-code klopt niet'),50); return; } this._st.lock=on; setTimeout(()=>onSettingsChanged(''),50); },
    lockUnlock(){ setTimeout(()=>{ this._st.locked=false; onUnlocked(); },200); },
    lockSet(on){ this._st.lock=on; setTimeout(()=>onSettingsChanged(''),100); }, lockTimeout(v){ this._st.timeout=+v; },
    _sec:{on:false,since:0,rotate:false,space:null},
    backupSetPassword(pw){ setTimeout(()=>{ this._sec.on=true; this._sec.since=Date.now(); this._sec.pw=pw; onSecure(''); },200); },
    backupEncryptOff(){ this._sec.on=false; this._bk.parts.ai=false; this._bk.parts.sleep=false; this._bk.parts.checkin=false; this._bk.plain={}; }, backupSetRotate(on){ this._sec.rotate=on; },
    backupRotate(dry){ setTimeout(()=>{ if(!dry) this._sec.rotated=true; onRotate({count:6,bytes:48e6,dry}); },100); },
    backupSpace(){ setTimeout(()=>{ this._sec.space={t:Date.now(),total:5.4e9,dirs:[{name:'WhatsApp backup',bytes:4.9e9,files:8123},{name:'SMS backup',bytes:3.1e8,files:41},{name:'Versleuteld',bytes:1.9e8,files:9}]}; onSpace(this._sec.space); },200); },
    archivePick(){ setTimeout(()=>onArchivePicked(),100); },
    archiveOpen(pw){ setTimeout(()=>onArchive(!pw ? {needPw:true} : pw!=='goedwachtwoord' ? {error:'Verkeerd wachtwoord'} : {name:'backup-2026-10-04_0330.rtb',bytes:1.2e7,files:[{n:'SMS backup/sms-2026-10-04.xml',s:1e6}],contacts:'Contacten backup/contacten-2026-10-04.vcf',notes:'Notities/notities.json',ai:'Assistent/assistent.json',sleep:'Slaappraat/slaappraat.json',checkin:'Check-in/checkin.json'}),150); },
    archiveRestore(k,e){ this._arcRestore=k+'|'+e; this._restoreKind=k; setTimeout(()=>onRestorePreview(k==='sleep'?{kind:k,total:4,fresh:2,dup:0,old:0,running:1,bad:0,keep:14,clips:9,audio:7,audioBytes:12e6,withAudio:true,fromArchive:true,sample:[Date.now()-864e5,Date.now()-2*864e5]}
      :k==='ai'?{kind:k,memTotal:5,memFresh:0,memDup:2,memFull:3,chatTotal:4,chatFresh:4,chatDup:0,chatFull:0,total:9,fresh:4,sample:[]}:k==='checkin'?{kind:k,qTotal:2,qFresh:1,qDup:1,aTotal:40,aFresh:12,aDup:28,bad:0,total:42,fresh:13,sample:['🏃 Heb je bewogen?']}:{kind:k,total:320,fresh:2,dup:318,sample:['Oom Kees','Tandarts']}),100); },
    archiveUnpack(){ setTimeout(()=>onArchiveUnpacked('ok:57'),150); }, archiveClose(){ this._arcClosed=true; },
    _bk:{busy:false,current:'',auto:null,charging:true,parts:{},last:null,sleepAudio:false,extra:{aiMem:12,aiChats:8,aiBytes:46000,nights:5,sleepText:21000,sleepAudio:84e6,ciQ:2,ciA:57,ciBytes:9000}},
    /* Elke nacht: zelf gekozen blijft; nooit gekozen = alleen met versleutelen en een backup-map (zoals AllBackup.auto) */
    _bkAuto(){ const b=this._bk; return b.auto==null ? (!!this._sec.on && !!this._wa.info.dest) : !!b.auto; },
    backupState(f){ const b=this._bk, auto=this._bkAuto(); return JSON.stringify({busy:b.busy,current:b.current,auto,charging:b.charging,dest:this._wa.info.dest,destName:'USB-stick',next:auto?Date.now()+5*36e5:0,parts:Object.assign({ai:false,sleep:false,checkin:false},b.parts),sleepAudio:b.sleepAudio,extra:b.extra,last:b.last,encrypted:this._sec.on,encSince:this._sec.since,rotate:this._sec.rotate,space:this._sec.space,herstel:this._herstelReady()}); },
    /* Klaar voor herinstallatie (zoals Herstel.ready) */
    _herstelReady(){ const b=this._bk, h=this._hs, dest=!!this._wa.info.dest, enc=!!this._sec.on, auto=this._bkAuto(), part=b.parts.app!==false, last=b.appLast||0, miss=[];
      const blocked=h.state==='nieuw'?'Nog niet: zet eerst je gegevens terug (of kies Overslaan)':h.needPw&&!enc?'Stel eerst het wachtwoord van je backup opnieuw in (Versleutelen): na het terugzetten gaan backups alleen versleuteld verder':'';
      if(blocked) miss.push(blocked);
      if(!dest) miss.push('Kies een backup-map'); if(!enc) miss.push('Zet Versleutelen aan (wachtwoord)'); if(!part) miss.push('Vink Alle appgegevens aan'); if(!auto) miss.push('Zet Elke nacht alles back-uppen aan'); if(!last) miss.push('Nog geen complete backup gemaakt');
      return {ok:!miss.length,missing:miss,last,lastName:last?'backup-2026-10-28_0331.rtb':'',enc,dest,auto,part,blocked,rotate:!!this._sec.rotate,appBytes:b.appBytes||0,stopped:!!b.appStopped}; },
    /* Gegevens terugzetten na herinstallatie (nagebootst) */
    _hs:{state:window.__testHerstel||'bestaand',after:false,hasData:false,pwd:'goedwachtwoord',old:false,plain:{},
      archives:[{name:'backup-2026-10-28_0331_deels.rtb',size:9e8,t:Date.now()-36e5,deels:true},{name:'backup-2026-10-27_0330.rtb',size:2.4e9,t:Date.now()-864e5,deels:false,app:true},{name:'backup-2026-10-26_0330.rtb',size:2.3e9,t:Date.now()-2*864e5,deels:false}],
      items:[{id:'sms',name:'Sms',used:'SMS backup',ok:false},{id:'contacts',name:'Contacten',used:'Namen bij nummers, de Contacten-tool',ok:true},{id:'history',name:'Meldingentoegang',used:'Meldingsgeschiedenis',ok:false},{id:'a11y',name:'Toegankelijkheid',used:'Automatiseringen die tikken in andere apps',ok:false},{id:'bio',name:'Vingerafdruk bij de app-code',used:'Zet hem weer aan bij Instellingen → App-slot',ok:false},{id:'widgets',name:'Widgets',used:'Widgets op het startscherm van de telefoon moet je opnieuw plaatsen',ok:true,info:true}],
      models:{names:['Klein en snel','Zoekmodel','Spraakmodel Snel (57 MB)'],bytes:1.7e9,wait:'Wacht op wifi (of zet Alleen via wifi uit)',err:''}},
    herstelState(){ const h=this._hs; return JSON.stringify({state:h.state,pending:h.state==='nieuw',after:!!h.after,dest:!!this._wa.info.dest,hasData:!!h.hasData,blocked:this._herstelReady().blocked}); },
    herstelSkip(){ if(this._hs.state==='nieuw') this._hs.state='overgeslagen'; this._hs.skipped=true; },
    herstelPickFolder(){ Object.assign(this._wa.info,{dest:true,destName:'USB-stick Rene'}); setTimeout(()=>onHerstelFolder(''),80); },
    herstelScan(){ const h=this._hs; setTimeout(()=>onHerstelScan(h.scanError?{error:h.scanError}:{dest:true,destName:'USB-stick Rene',archives:h.archives,recommended:(h.archives.find(a=>!a.deels)||h.archives[0]||{}).name,plain:h.plain}),80); },
    herstelOpen(n,pw){ const h=this._hs; h.opened=n; h.tries=(h.tries||[]).concat([pw]); setTimeout(()=>{
      if(!pw) return onHerstelOpened({needPw:true}); if(pw!==h.pwd) return onHerstelOpened({error:'Verkeerd wachtwoord',wrongPw:true}); if(h.old) return onHerstelOpened({old:true,name:n});
      onHerstelProgress('1200000000,2400000000,1'); onHerstelProgress('600000000,2400000000,2'); setTimeout(()=>onHerstelOpened({ok:true,name:n,app:'1.105',t:Date.now()-864e5,files:1532,kluis:4,bytes:2.3e9,history:true,warn:h.state!=='nieuw'||!!h.hasData,missing:h.missing||[]}),80); },120); },
    herstelOldArchive(n,pw){ this._hs.oldOpened=n; this.archiveOpen(pw); },
    herstelApply(){ const h=this._hs; if(h.busy){ setTimeout(()=>onHerstelDone(h.busy),50); return; } h.applied=h.opened; setTimeout(()=>{ onHerstelDone(h.applyError||''); if(!h.applyError){ h.state='klaar'; h.after=true; } },120); },
    herstelCancel(){ this._hs.cancelled=(this._hs.cancelled||0)+1; },
    herstelPlainOk(){ this._hs.needPw=false; this._hs.plainOk=true; this._bk.parts.ai=false; this._bk.parts.sleep=false; this._bk.parts.checkin=false; },
    herstelChecklist(){ const h=this._hs; return JSON.stringify(h.after?{items:h.items,models:h.models,t:Date.now()}:{items:[]}); },
    herstelChecklistDone(){ this._hs.after=false; this._hs.done=true; },
    herstelFix(id){ const h=this._hs; (h.fixed=h.fixed||[]).push(id); const it=h.items.find(i=>i.id===id); if(it) it.ok=true; setTimeout(()=>onSettingsChanged(''),50); },
    backupSetPart(p,on){ const b=this._bk; b.plain=b.plain||{}; if(p==='sleepAudio') b.sleepAudio=on; else if(p.startsWith('plain:')) b.plain[p.slice(6)]=on; else { b.parts[p]=on; if(!on) b.plain[p]=false; } }, backupSetAuto(a,c){ this._bk.auto=a; this._bk.charging=c; },
    backupStart(wa){ const b=this._bk; if(!this._wa.info.dest) return 'Kies eerst een backup-map'; b.busy=true; const ks=['sms','calls','contacts','notes','transcripts','music']; let i=0;
      const t=setInterval(()=>{ b.current=ks[i++]; if(i>ks.length){ clearInterval(t); b.busy=false; b.current='';
        b.last={t:Date.now(),auto:false,sms:{ok:true,count:1234},calls:{ok:true,count:842},contacts:{ok:true,count:311},notes:{ok:true,count:4},transcripts:{ok:true,count:0,msg:'Niets te bewaren'},music:{ok:false,count:0,msg:'Geen toestemming'}}; if(this._sec.on) b.last.archive='backup-2026-10-04_1342.rtb'; if(this._sec.rotate) b.last.rotated={count:2,bytes:31e6};
        onBackupDone(b.last); } },150); return ''; },
    restorePick(k){ this._restoreKind = k; const P = {contacts:{kind:k,total:320,fresh:9,dup:311,sample:['Oom Kees','Tandarts']}, settings:{kind:k,total:12,fresh:5,dup:7,rules:2,favs:3,alarm:true,plan:false,reminders:0,sample:['Parkeren starten']}, route:{kind:k,total:1,fresh:1,dup:0,title:'Rondje Plas',points:420,stats:{distance:5230},sample:['Rondje Plas']},
        ai:{kind:'ai',memTotal:14,memFresh:3,memDup:11,memFull:0,chatTotal:9,chatFresh:2,chatDup:7,chatFull:0,total:23,fresh:5,sample:['Woont in <b>Rotterdam</b>.','Sport op dinsdag.']},
        sleep:{kind:'sleep',total:6,fresh:3,dup:1,old:2,running:0,bad:0,keep:14,clips:17,audio:0,audioBytes:0,withAudio:true,sample:[Date.now()-864e5,Date.now()-2*864e5,Date.now()-3*864e5]},
        checkin:{kind:'checkin',qTotal:3,qFresh:1,qDup:2,aTotal:120,aFresh:30,aDup:90,bad:1,total:123,fresh:31,sample:['🏃 Heb je bewogen?']}};
      setTimeout(()=>onRestorePreview(P[k] || {kind:k,total:5,fresh:1,dup:4,sample:['Vakantie']}),100); },
    restoreCancel(){ this._restoreCancelled=(this._restoreCancelled||0)+1; this._restoreKind=null; },
    restoreApply(){ const k=this._restoreKind; this._restoreApplied=k; setTimeout(()=>onRestoreDone(k==='sleep'?'ok:3':k==='ai'?'ok:5':k==='checkin'?'ok:31':'ok:9'),100); return ''; },
    _al:{on:false,hour:7,minute:0,days:31,station:null,nextAt:0},
    alarmState(){ return JSON.stringify(Object.assign({exact:true},this._al)); },
    alarmSet(on,h,m,d,st){ const a=this._al; Object.assign(a,{on,hour:h,minute:m,days:d}); if(st) a.station=JSON.parse(st); if(on&&!a.station) return 'Kies een zender'; a.nextAt=on?Date.now()+8*36e5:0; return ''; },
    alarmTest(){ return this._al.station?'':'Kies eerst een zender'; }, alarmExactSettings(){},
    _rem:{}, _rems:[], noteRemind(id,t,time){ if(+time>0) this._rem[id]=+time; else delete this._rem[id]; }, noteReminders(){ const o = {...this._rem}; this._rems.forEach(r => { if (!o[r.note] || o[r.note] > r.t) o[r.note] = r.t; }); return JSON.stringify(o); },
    noteRemindAdd(note,title,time,rep){ if (this._remFail) return ''; const id = note + '~' + (this._rems.length + 2); this._rems.push({id, note, t: +time, rep}); return id; },
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
      // Slaappraat: wat je zei (zoals SleepTalk.search)
      const sl=[]; (this._sl ? this._sl.nights : []).slice().sort((a,b)=>b.start-a.start).forEach(n=>n.clips.slice().reverse().forEach(k=>{ if(k.done&&k.kind==='praten'&&(k.text||'').toLowerCase().includes(ql)) sl.push({night:n.id,clip:k.id,t:k.t,text:k.text}); }));
      if(sl.length) r.sleep={total:sl.length,items:sl.slice(0,5)};
      // Check-in: notities en labels (zoals CheckinStore.search)
      const ci=(this._ci?this._ci.ans:[]).filter(a=>((a.note||'')+' '+(a.labels||[]).join(', ')).toLowerCase().includes(ql)).sort((a,b)=>b.t-a.t).map(a=>{ const q=this._ci.qs.find(x=>x.id===a.q)||{}; return {q:a.q,p:a.p,t:a.t,title:((q.emoji||'')+' '+(q.text||'')).trim(),text:((a.e||'')+' '+(a.l||'')).trim()+((a.labels||[]).length?' · '+a.labels.join(', '):'')+(a.note?' · '+a.note:'')}; });
      if(ci.length) r.checkin={total:ci.length,items:ci.slice(0,5)};
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
    autoRecord(p){ if(!this._au.perms.a11yRunning) return 'Zet eerst de toegankelijkheid voor Tools aan'; this._au.rec={pkg:p,steps:[{t:'tap',text:'Start parkeren',id:'net.easypark.android:id/start'},{t:'tap',text:'Bevestigen'}]}; setTimeout(()=>openTool('auto-rec'),200); return ''; },
    autoRecTake(){ const r=this._au.rec; this._au.rec=null; return JSON.stringify(r); },
    autoA11ySettings(){ Object.assign(this._au.perms,{a11y:true,a11yRunning:true}); },
    autoPerm(k){ const P=this._au.perms; if(k==='loc'){P.loc=true;P.fine=true;} if(k==='bgloc'){ if(!P.loc){P.loc=true;P.fine=true;} else P.bgloc=true; } if(k==='bt')P.bt=true; if(k==='notif')P.notif=true; setTimeout(()=>onAutoChanged(),50); }
  };
})();

