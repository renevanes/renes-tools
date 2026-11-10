/* Visitekaartje: één stamgegeven om te lezen, in blokken met elk een potlood (alleen dat blok bewerken). Bij een persoon
 * bovenaan wat die voor jou is (tik om te wijzigen) en of de assistent hem kent; met een gekoppeld contact Bellen, Bericht en
 * Contacten. Nummers (BSN, documentnummer) staan afgeschermd; kaartfoto's staan er meteen (dat staat in Activiteiten als
 * Kaartfoto bekeken) en zijn groot te bekijken met zoomen. */
const cmc={rec:null,epoch:0,reveal:false,photoEpoch:0,fotos:{},laden:new Set(),kijker:''};
const cmct={request:'',done:null};
// taal-vast: korte veldnamen op het kaartje worden met t() vertaald
const CMC_KORT={roepnaam:'Roepnaam',voornamen:'Voornamen',naam:'Achternaam',initialen:'Initialen',geslacht:'Geslacht',geboortedatum:'Geboren',geboorteplaats:'Geboorteplaats',nationaliteit:'Nationaliteit',bsn:'BSN',
  telefoon:'Telefoon',email:'E-mail',adres:'Adres',postcode:'Postcode',plaats:'Plaats',land:'Land',persoon:'Van',documentsoort:'Soort',document:'Nummer',geldig:'Geldig tot',categorieen:'Categorieën',
  afgifte:'Afgiftedatum',afgifteplaats:'Plaats van afgifte',uitgifteland:'Land van afgifte',afgegevenDoor:'Afgegeven door',registratienummer:'Registratienummer',context:'Notitie',accommodatie:'Familie of accommodatie'};
const CMC_DATUM=new Set(['geboortedatum','geldig','afgifte','van','tot']);
const CMC_JANEE=new Set(['alleen','tweedeBegeleider','tweedeGever','bsnInvullen','delen']);

function cmKaartOpen(id,push=true,note=''){if(!cmOpen()||!id)return;cmGo({scr:'cmkaart',id},push,note);}
function cmKaartLoad(id,note=''){
  if(!cmOpen())return;const epoch=++cmc.epoch;
  cmRequest('masterRead',[id,'masterdata',''],r=>{
    if(epoch!==cmc.epoch||!cmOpen())return;
    const zelfde=cmc.rec&&r.record&&cmc.rec.id===r.record.id&&cmc.rec.revision===r.record.revision;
    if(!zelfde){cmcPhotoViewerClose();cmc.photoEpoch++;cmc.fotos={};cmc.laden.clear();}
    cmc.rec=r.record;cmc.reveal=false;if(r.record&&(r.record.type==='formulier'||r.record.id===cmkMe()))sigLoad();
    if(current!=='cmkaart')show('cmkaart');
    cmKaartRender();$('#cm-kaart-status').textContent='';cmNavDone();if(note)cmNote(note);
  },false,()=>{if(epoch===cmc.epoch)cmNavDone();});
}
// Een ander scherm dan het kaartje: een kaartje dat nog laadt, hoeft niet meer te openen
const cmcEnter=enterMasterdata;enterMasterdata=function(name,prev){if(name!=='cmkaart')cmc.epoch++;cmcEnter(name,prev);};
function cmKaartRender(){
  const r=cmc.rec,box=$('#cm-kaart');box.replaceChildren();if(!r||!cmOpen())return;
  $('#cm-kaart-title').textContent=r.label;
  if(r.type==='persoon'){cmcPerson(box,r);return;}
  if(r.type==='relatie'){const zin=cmiRelationText(r.fields,'zin');if(zin){const c=cmEl('div',undefined,'card cmc-top');c.append(cmEl('p',zin,'cmi-zin'));box.append(c);}}
  if(r.type==='identiteit'){const listed=cm.records.find(x=>x.id===r.id);if(listed&&cmiDocLate(listed)){const c=cmEl('div',undefined,'card cmc-top');c.append(cmEl('p',cmiDocStand(listed,null),'cmi-warn'));box.append(c);}}
  if(r.type==='formulier'){const listed=cm.records.find(x=>x.id===r.id),miss=listed?cmkFormMissing(listed):[];if(miss.length){const c=cmEl('div',undefined,'card cmc-top');c.append(cmEl('p',cmkSmall(listed,cmkMe()),'cmi-warn'));box.append(c);}}
  for(const blok of CM_BLOKKEN[r.type]||[]){
    if(blok[0]==='fotos'){cmcPhotos(box,r,blok);continue;}
    const sec=cmcBlok(box,r,blok,cmcRows(r,blok[2]),{mask:blok[2].some(k=>CM_MASK.has(k))});
    if(r.type==='formulier'&&blok[0]==='ondertekenen'&&r.fields?.handtekening)sec.append(cmSigStand('cmc-sig-stand',true));
  }
}
/* Een blok: kop met potlood en de ingevulde velden. */
function cmcBlok(box,r,blok,rows,opts={}){
  const sec=cmEl('section',undefined,'card cmc-blok'),head=cmEl('div',undefined,'cmc-kop'),title=cmBlokTitle(blok,r);sec.dataset.blok=blok[0];
  head.append(cmEl('h2',title));
  if(opts.edit!==false){const pen=cmEl('button',undefined,'icon-btn cmc-pen');pen.type='button';pen.innerHTML=uiIcon('edit');pen.setAttribute('aria-label',t('{blok} bewerken',{blok:title}));pen.onclick=()=>cmcEdit(blok[0]);head.append(pen);}
  sec.append(head);
  if(rows.length){
    const dl=cmEl('dl',undefined,'cmc-rijen');
    for(const [label,value,key,extra] of rows){const dd=cmEl('dd');dd.append(cmEl(key==='context'?'p':'span',value));if(extra)dd.append(cmEl('small',extra));dl.append(cmEl('dt',label),dd);}
    sec.append(dl);
    if(opts.mask&&rows.some(x=>CM_MASK.has(x[2]))){const b=cmcKnop(cmc.reveal?t('Nummers verbergen'):t('Nummers tonen'),()=>{if(!cmOpen())return;cmc.reveal=!cmc.reveal;cmKaartRender();cmTouch();},'eye');b.setAttribute('aria-pressed',String(cmc.reveal));sec.append(b);}
  }else if(opts.empty!==false)sec.append(cmEl('p',opts.empty||t('Nog niets ingevuld.'),'hint'));
  box.append(sec);return sec;
}
function cmcKnop(text,fn,icon,id){const b=cmEl('button',undefined,'mini cmc-knop');b.type='button';if(id)b.id=id;if(icon)b.innerHTML=uiIcon(icon);b.append(document.createTextNode(text));b.onclick=fn;return b;}
function cmcEdit(blok){const r=cmc.rec;if(!cmOpen()||!r||cm.mutating)return;cmEdit(r.id,blok,{id:r.id});}
/* Ingevulde velden als [naam, waarde, veld, extra]. */
function cmcRows(r,keys){
  const f=r.fields||{},out=[];
  for(const key of keys){
    const v=f[key];if(!v||r.type==='formulier'&&key==='formulier'||key==='categorieen'&&f.documentsoort!=='rijbewijs')continue;
    const [value,extra]=cmcValue(r,key,v);out.push([t(CMC_KORT[key]||CM_FIELDS[key]||key),value,key,extra]);
  }
  return out;
}
function cmcValue(r,key,v){
  const ref=cmRefType(r.type,key);
  if(ref){const x=cm.records.find(p=>p.id===v);return [x?x.label:t('Niet meer aanwezig')];}
  if(CM_MASK.has(key))return [cmc.reveal?v:'••••'+v.slice(-3)];
  if(key==='geboortedatum'){const n=cmiAge(v);return [fmtDate(v),n>=0?t('{n} jaar',{n}):''];}
  if(CMC_DATUM.has(key))return [fmtDate(v)];
  if(key==='relatie'||/^begeleider[12]Relatie$/.test(key))return [cmiRelationName(v)];
  if(key==='status'){const c=(cmiStatusChoices(r.fields.relatie)||[]).find(x=>x[0]===v);return [c?c[1]:v];}
  if(CMC_JANEE.has(key))return [v==='ja'?t('Ja'):t('Nee')];
  if(key==='geslacht'||key==='documentsoort'){const c=(cmiChoices(key)||[]).find(x=>x[0]===v);return [c?c[1]:v];}
  if(CMI_LAND.has(key))return [lnToon(key==='nationaliteit'?key:'land',v)];
  if(key==='handtekening')return [v==='begeleider1'?t('Begeleider 1'):v==='begeleider2'?t('Begeleider 2'):t('Nergens')];
  return [v];
}

/* Het kaartje van een persoon */
function cmcPerson(box,r){
  const me=cmkMe(),self=r.id===me,f=r.fields||{},listed=cm.records.find(x=>x.id===r.id)||{},live=r.live||{},naam=f.roepnaam||(f.voornamen||'').split(/\s+/)[0]||r.label;
  const top=cmEl('div',undefined,'card cmc-top');
  if(self)top.append(cmEl('p',t('Dit ben jij'),'cmc-ik'));
  else if(me){
    const x=cmkToMe(r.id,me)[0],chip=cmEl('button',undefined,'cmc-chip');chip.type='button';chip.id='cmc-wie';
    chip.append(cmEl('span',x?cmkWieText(x,r.label):t('Wie is {naam} voor jou?',{naam})));chip.insertAdjacentHTML('beforeend',uiIcon('edit'));chip.onclick=()=>cmkWie(r.id);top.append(chip);
    if(x&&cmkStatusKeuzes(x.code)&&!x.status)top.append(cmEl('p',t('Tik erop om de status in te vullen, zoals ouderlijk gezag.'),'hint'));
  }else top.append(cmEl('p',t('Voeg eerst jezelf toe (op het beginscherm); dan leg je vast wat {naam} voor jou is.',{naam}),'hint'));
  if(!self){
    const on=!!listed.bekend,row=cmEl('div',undefined,'cm-sw'),txt=cmEl('span',undefined,'cm-sw-t'),b=cmEl('b',t('Bekend bij de assistent')),tg=cmEl('button',undefined,'tg');b.id='cmc-ai-t';
    txt.append(b,cmEl('small',cmcAiSub(on,naam)));tg.type='button';tg.id='cmc-ai';tg.classList.toggle('on',on);tg.setAttribute('role','switch');tg.setAttribute('aria-checked',String(on));tg.setAttribute('aria-labelledby','cmc-ai-t');tg.onclick=()=>cmcAi(!on);
    row.append(txt,tg);top.append(row);
  }
  const tel=live.gevonden&&live.telefoon||f.telefoon||'';
  if(tel||f.contact){
    const acts=cmEl('div',undefined,'row cmc-acts');
    if(tel&&!self)acts.append(cmcKnop(tc('actie','Bellen'),()=>cmcContact('bel'),'phone','cmc-bel'),cmcKnop(t('Bericht'),()=>cmcContact('bericht'),'message','cmc-bericht'));
    if(f.contact)acts.append(cmcKnop(t('Contacten'),()=>cmcContact('contact'),'external','cmc-contact'));
    if(acts.childElementCount)top.append(acts);
  }
  if(f.contact&&live.toegang===false)top.append(cmEl('p',t('Geen toegang tot Contacten: telefoon en e-mail komen nu uit Stamgegevens.'),'hint'));
  else if(f.contact&&live.gevonden===false)top.append(cmEl('p',t('Het gekoppelde contact is niet meer gevonden. Koppel opnieuw via ⋯.'),'hint'));
  box.append(top);
  const blok=k=>CM_BLOKKEN.persoon.find(b=>b[0]===k);
  cmcBlok(box,r,blok('persoon'),cmcRows(r,blok('persoon')[2]));
  cmcBlok(box,r,blok('geboorte'),cmcRows(r,blok('geboorte')[2]),{mask:true});
  // Telefoon en e-mail uit Contacten als het contact gevonden is
  const rows=cmcRows(r,blok('contact')[2]);
  if(live.gevonden)for(const k of ['email','telefoon']){if(!live[k])continue;const i=rows.findIndex(x=>x[2]===k),row=[t(CMC_KORT[k]),live[k],k,t('uit Contacten')];if(i>=0)rows[i]=row;else rows.unshift(row);}
  cmcBlok(box,r,blok('contact'),rows);
  // Documenten
  const docs=cm.records.filter(x=>x.type==='identiteit'&&x.person===r.id).sort((a,b)=>(a.geldig||'9999').localeCompare(b.geldig||'9999'));
  const ds=cmcBlok(box,r,['documenten','Documenten',[]],[],{edit:false,empty:false});
  if(docs.length){const l=cmEl('div',undefined,'stlist');for(const d of docs)l.append(cmRow(d,cmiDocStand(d,null),cmiDocLate(d),()=>cmKaartOpen(d.id)));ds.append(l);}
  else ds.append(cmEl('p',t('Nog geen documenten.'),'hint'));
  ds.append(cmcKnop(t('Document toevoegen'),()=>cmcDocAdd(r.id),'plus','cmc-doc-add'));
  if(self)box.append(cmcSigBlok());
  // Relaties als zinnen; die met jou staat bij een ander al bovenaan
  const rels=cm.records.filter(x=>x.type==='relatie'&&(x.van===r.id||x.naar===r.id)&&(self||!me||![x.van,x.naar].includes(me)));
  const rs=cmcBlok(box,r,['relaties','Relaties',[]],[],{edit:false,empty:false});
  if(rels.length){const l=cmEl('div',undefined,'stlist');for(const x of rels)l.append(cmRow(x,t('Tik om te wijzigen'),false,()=>cmEdit(x.id,'relatie',{id:r.id}),'share',cmiRelationText({vanPersoon:x.van,naarPersoon:x.naar,relatie:x.relatie,status:x.status},'zin')||x.label));rs.append(l);}
  else rs.append(cmEl('p',self?t('Nog niemand. Voeg je mensen toe met Persoon toevoegen.'):t('Geen relaties met anderen.'),'hint'));
  rs.append(self?cmcKnop(t('Persoon toevoegen'),cmkPersonNew,'plus','cmc-rel-add'):cmcKnop(me?t('Relatie tussen anderen…'):t('Relatie toevoegen…'),()=>cmkOthers(r.id),'plus','cmc-rel-add'));
  // Over: bij jezelf Over mij van de assistent (één plek), bij een ander een notitie voor de assistent
  if(self){
    const prof=cm.records.find(x=>x.type==='profiel');
    if(f.context||prof?.tekst)cmcAboutOld(box,f.context);
    const l=cmEl('div',undefined,'card stlist'),over=cmRow(null,t('Staat bij de assistent'),false,null,'user',t('Over mij'));
    over.id='cmc-over-mij';over.setAttribute('onclick','cmaiOverMij()');if(isPlay())over.classList.add('play-weg');
    l.append(over,cmRow(null,cmiAiStand(),false,cmaiOpen,'smile',t('Wat weet de assistent?')));box.append(l);
  }else cmcBlok(box,r,blok('over'),cmcRows(r,['context']),{empty:t('Iets wat de assistent over {naam} mag weten, zoals school of werk.',{naam})});
}
function cmcAiSub(on,naam){
  const prof=cm.records.find(x=>x.type==='profiel');
  if(prof?.delen!=='ja')return t('De assistent kent je mensen nu niet: zet dat aan bij Wat weet de assistent?');
  return on?t('De assistent weet wie {naam} is en wat {naam} voor jou is.',{naam}):t('De assistent weet niets van {naam}.',{naam});
}
function cmcAi(on){
  const r=cmc.rec;if(!cmOpen()||!r||r.type!=='persoon'||cm.mutating)return;
  const rec={...r,fields:{...r.fields,delen:on?'ja':'nee'}};
  cmBusy(true);cmRequest('masterSave',[JSON.stringify(rec)],()=>{cmBusy(false);appIdentityRefresh();cmListLoad(()=>cmKaartLoad(r.id,on?t('{naam} is nu bekend bij de assistent.',{naam:r.label}):t('De assistent kent {naam} nu niet meer.',{naam:r.label})));},false,()=>cmBusy(false));
}
function cmcContact(what){const r=cmc.rec;if(!cmOpen()||!r||r.type!=='persoon'||cm.mutating)return;cmRequest('masterContactAction',[r.id,what],()=>{});}
/* Tekst over jou uit de oude indeling (in je persoon of het app-profiel): naar Over mij van de assistent, of weg. */
function cmcAboutOld(box,text){
  const sec=cmEl('section',undefined,'card cmc-blok');sec.id='cmc-over-oud';
  const play=typeof isPlay==='function'&&isPlay();
  sec.append(cmEl('h2',t('Tekst over jou')),cmEl('p',play?t('Er staat nog een tekst over jou in Stamgegevens. Deze versie heeft geen assistent die hem gebruikt; je kunt hem weghalen.'):t('Er staat nog een tekst over jou in Stamgegevens. Die hoort voortaan bij Over mij van de assistent, zodat je hem op één plek bijhoudt.'),'hint'));
  if(text)sec.append(cmEl('p',text,'cmc-tekst'));
  const acts=cmEl('div',undefined,'row cmc-acts');if(!play)acts.append(cmcKnop(t('Naar Over mij'),()=>cmcAboutMove('verplaats'),'check','cmc-over-naar'));acts.append(cmcKnop(t('Weghalen'),()=>cmcAboutMove('wis'),'trash','cmc-over-weg'));
  sec.append(acts);box.append(sec);
}
function cmcAboutMove(mode){
  if(!cmOpen()||cm.mutating)return;
  const go=()=>{if(!cmOpen()||cm.mutating)return;cmBusy(true);cmRequest('masterAboutMove',[mode],out=>{
    cmBusy(false);appIdentityRefresh();
    const note=out.moved?(mode==='wis'?t('De tekst over jou is weggehaald.'):t('De tekst staat nu bij Over mij van de assistent.')):out.teLang?t('Over mij is te lang om de tekst erbij te zetten. Maak Over mij korter, of haal de tekst hier weg.'):'';
    cmListLoad(()=>{const id=cmc.rec?.id||cmkMe();if(current==='cmkaart'&&id)cmKaartLoad(id,note);else cmNote(note);});
  },false,()=>cmBusy(false));};
  if(mode==='wis')askConfirm(t('Tekst over jou weghalen?'),typeof isPlay==='function'&&isPlay()?t('De tekst wordt uit Stamgegevens verwijderd.'):t('De tekst wordt uit Stamgegevens verwijderd. Over mij bij de assistent blijft zoals het is.'),t('Weghalen'),go);else go();
}

/* Je handtekening (02-handtekening.js, dezelfde als in PDF en Formulieren) op je eigen kaartje. Hij staat niet in de kluis
 * van Stamgegevens maar versleuteld bij de app, zodat PDF hem ook zonder ontgrendelen kan plaatsen. */
function cmcSigBlok(){
  const s=sigHuidig(),sec=cmEl('section',undefined,'card cmc-blok'),head=cmEl('div',undefined,'cmc-kop');sec.dataset.blok='handtekening';sec.id='cmc-sig';
  head.append(cmEl('h2',t('Handtekening')));sec.append(head);
  if(s)sec.append(sigCanvas('cmc-sig-voorbeeld'));
  sec.append(cmEl('p',sig.error||(s?t('Voor Formulieren en PDF → Bewerken en ondertekenen. Versleuteld op deze telefoon.'):t('Nog geen handtekening. Teken hem één keer; Formulieren en PDF gebruiken hem daarna.')),sig.error?'hint cmi-warn':'hint'));
  const acts=cmEl('div',undefined,'row cmc-acts');acts.append(cmcSigKnop(s,'cmc-sig-teken'));
  if(s||sig.error)acts.append(cmcKnop(t('Verwijderen'),()=>{if(cmOpen())sigVraagWeg('cmc-sig-teken');},'trash','cmc-sig-weg'));
  sec.append(acts);return sec;
}
function cmcSigKnop(s,id){return cmcKnop(s?t('Opnieuw tekenen'):t('Handtekening tekenen'),()=>{if(cmOpen()&&!cm.mutating)sigTeken();},'sign',id);}
/* Bij Ondertekenen van vaste formuliergegevens: of je handtekening er is, en tekenen. Op het kaartje (op: true) met
 * voorbeeld en alleen als hij bij een begeleider komt; bij het bewerken altijd, kort. */
function cmSigStand(id,op){
  const s=sigHuidig(),box=cmEl('div',undefined,'sig-stand');box.id=id;
  if(s&&op)box.append(sigCanvas());
  const tekst=sig.error||(op?(s?t('Je bewaarde handtekening komt bij die begeleider.'):t('Nog geen handtekening bewaard. Teken hem één keer; dan zet Formulieren hem bij die begeleider.')):(s?t('Je hebt een handtekening bewaard.'):t('Nog geen handtekening bewaard.')));
  box.append(cmEl('p',tekst,sig.error||(op&&!s)?'hint cmi-warn':'hint'),cmcSigKnop(s,`${id}-teken`));return box;
}
sigWatch(()=>{
  if(!cmOpen())return;
  const blok=$('#cmc-sig');if(blok)blok.replaceWith(cmcSigBlok());
  const st=$('#cmc-sig-stand');if(st)st.replaceWith(cmSigStand('cmc-sig-stand',true));
  const ed=$('#cmi-form-sig');if(ed)ed.replaceWith(cmSigStand('cmi-form-sig',false));
});

/* Kaartfoto's op het documentkaartje: meteen getoond (elke keer lezen staat in Activiteiten als Kaartfoto bekeken); tikken
 * opent de foto groot, met in- en uitzoomen (knijpen, dubbeltikken of + en −). Eén keer laden per geopend kaartje. */
function cmcPhotoAlt(side){return side==='front'?t('Voorzijde van het identiteitsdocument'):t('Achterzijde van het identiteitsdocument');}
function cmcPhotos(box,r,blok){
  const sec=cmcBlok(box,r,blok,[],{empty:false}),grid=cmEl('div',undefined,'cmc-fotos');
  for(const side of ['front','back']){
    const has=!!r.images?.[side],tile=cmEl('button',undefined,'cmc-foto');tile.classList.toggle('leeg',!has);tile.type='button';tile.id='cmc-foto-'+side;
    if(has){const img=cmEl('img');img.alt=cmcPhotoAlt(side);img.hidden=true;tile.classList.add('vol');const laad=cmEl('span');tile.append(img,laad);uiWachtTekst(laad,t('Foto laden…'));tile.onclick=()=>cmcPhotoZoom(side);}
    else{tile.innerHTML=uiIcon('camera');tile.append(cmEl('span',side==='front'?t('Voorkant toevoegen'):t('Achterkant toevoegen')));tile.onclick=()=>cmcPhotoPick(side);}
    grid.append(tile);
  }
  sec.append(grid);
  if(r.images?.front||r.images?.back)sec.append(cmEl('p',t('Tik op een foto om hem groot te bekijken en in te zoomen.'),'hint'));
  for(const side of ['front','back'])if(r.images?.[side])cmcPhotoLoad(side);
}
function cmcPhotoPut(side){
  const tile=$('#cmc-foto-'+side),src=cmc.fotos[side];if(!tile||!src)return;
  const img=tile.querySelector('img');img.src=src;img.hidden=false;tile.querySelector('span').textContent=side==='front'?t('Voorkant'):t('Achterkant');tile.classList.add('open');
  tile.setAttribute('aria-label',side==='front'?t('Voorkant vergroten'):t('Achterkant vergroten'));
}
function cmcPhotoLoad(side){
  const r=cmc.rec;if(!cmOpen()||!r?.images?.[side])return;
  if(cmc.fotos[side]){cmcPhotoPut(side);return;}
  if(cmc.laden.has(side))return;
  const epoch=cmc.photoEpoch;cmc.laden.add(side);
  const mislukt=()=>{if(epoch!==cmc.photoEpoch)return;cmc.laden.delete(side);const span=$('#cmc-foto-'+side+' span');if(span)span.textContent=t('Foto niet geladen. Tik om het opnieuw te proberen.');};
  cmRequest('masterPhotoRead',[r.id,r.revision,side],out=>{
    if(epoch!==cmc.photoEpoch||!cmOpen())return;
    if(typeof out.image==='string'&&out.image.length<=350000&&/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(out.image)){cmc.laden.delete(side);cmc.fotos[side]=out.image;cmcPhotoPut(side);}
    else mislukt();
  },false,mislukt);
}
function cmcPhotoZoom(side){
  if(!cmOpen())return;const src=cmc.fotos[side];
  if(!src){cmcPhotoLoad(side);return;}
  cmc.kijker=src;erImageOpen(src,cmcPhotoAlt(side));cmTouch();
}
function cmcPhotoViewerClose(){if(cmc.kijker&&$('#er-image').classList.contains('on')&&$('#er-image-picture').getAttribute('src')===cmc.kijker)erImageClose();cmc.kijker='';}
function cmcPhotoPick(side){
  const r=cmc.rec;if(!cmOpen()||cm.mutating||!r||r.type!=='identiteit'||cmi.photoRequest)return;
  cmi.photoRequest=cmRequest('masterPhotoPick',[r.id,r.revision,side],()=>{},false,()=>cmiPhotoCancel());
}

/* Document toevoegen bij een persoon: eerst de soort, dan een foto (herkennen op de telefoon) of zelf invullen. */
// taal-vast: soorten worden met t() vertaald
const CMC_SOORTEN=[['identiteitskaart','Identiteitskaart'],['paspoort','Paspoort'],['rijbewijs','Rijbewijs'],['verblijfsdocument','Verblijfsdocument'],['anders','Ander document']];
function cmcDocAdd(person){
  if(!cmOpen()||cm.mutating||!cm.recordsReady)return;
  openSheet(t('Welk document van {naam}?',{naam:cmiPersonName(person)}),'',CMC_SOORTEN.map(([s,l])=>[t(l),()=>cmcDocHow(person,s),'document']));
}
function cmcDocHow(person,soort){
  if(typeof Android.masterScanPick!=='function'||soort==='anders'){cmiDocNew(person,soort);return;}
  const tip=soort==='paspoort'?t('Kies een foto van de pagina met je foto, met de twee regels tekens onderaan. Herkennen gebeurt op je telefoon.')
    :soort==='rijbewijs'?t('Kies een foto van de voorkant, met naam, datums en nummer. Herkennen gebeurt op je telefoon.')
    :t('Kies eerst een foto van de achterkant, met de strook tekens (<<<). Herkennen gebeurt op je telefoon.');
  openSheet(t(CMC_SOORTEN.find(x=>x[0]===soort)[1]),tip,[[t('Foto kiezen'),()=>cmhScanPick(person,soort),'camera'],[t('Zelf invullen'),()=>cmiDocNew(person,soort),'edit']]);
}
function cmiDocNew(person,soort){
  if(!cmOpen()||cm.mutating)return;
  // Na opslaan het documentkaartje (met de kaartfoto's); terug gaat naar waar je vandaan kwam
  cmiNew('identiteit');if(!cm.edit||cm.edit.id)return;cm.edit.fields={...cm.edit.fields,persoon:person,documentsoort:soort||'identiteitskaart'};cmEditor();
}

/* Menu ⋯ op het kaartje: koppelen aan een contact, een andere naam in het overzicht, verwijderen. */
function cmcMenu(){
  const r=cmc.rec;if(!cmOpen()||!r||cm.mutating)return;const acts=[];
  if(r.type==='persoon'){
    if(r.fields.contact)acts.push([t('Loskoppelen van contact'),cmcUnlink,'close']);else if(cmContactKan())acts.push([t('Koppelen aan contact'),cmcLink,'user']);
    if(!cmkMe())acts.push([t('Dit ben ik'),()=>cmkSelfSet(r.id),'user']);
  }
  acts.push([t('Andere naam in het overzicht'),()=>cmcEdit('naam'),'edit'],[t('Verwijderen'),cmcDelete,'trash']);
  openSheet(t(CM_TYPES[r.type]),'',acts);
}
function cmcDelete(){
  const r=cmc.rec;if(!cmOpen()||!r||cm.mutating)return;
  askConfirm('Stamgegeven verwijderen?','De gegevens worden verwijderd. De weergavenaam en activiteiten blijven in het logboek staan.','Verwijderen',()=>{
    if(!cmOpen()||cm.mutating||cmc.rec!==r)return;cmBusy(true);
    cmRequest('masterDelete',[r.id,r.revision],()=>{
      cmBusy(false);appIdentityRefresh();cm.navigating=true;cm.navNote='';cmNote(t('Stamgegeven verwijderd.'));
      cmListLoad(()=>{cmc.rec=null;const prev=cm.nav.pop();if(prev&&prev.id!==r.id){cmGo(prev,false);return;}show('masterdata');cmNavDone();});
    },false,()=>cmBusy(false));
  });
}
/* Koppelen aan een contact: lege velden aanvullen uit Contacten; telefoon en e-mail komen daarna steeds uit het contact. */
function cmcLink(){
  const r=cmc.rec;if(!r||r.type!=='persoon')return;
  cmContactPick(c=>{
    if(!cmOpen()||cm.mutating||cmc.rec?.id!==r.id)return;
    const rec={...cmc.rec,fields:{...cmc.rec.fields}};cmContactFill(rec.fields,c);
    cmBusy(true);cmRequest('masterSave',[JSON.stringify(rec)],()=>{cmBusy(false);appIdentityRefresh();cmListLoad(()=>cmKaartLoad(r.id,t('Gekoppeld aan {naam}. Telefoon en e-mail komen nu uit Contacten.',{naam:c.fields?.weergave||r.label})));},false,()=>cmBusy(false));
  });
}
function cmcUnlink(){
  const r=cmc.rec;if(!cmOpen()||!r||cm.mutating||!r.fields.contact)return;
  const live=r.live||{},rec={...r,fields:{...r.fields}};delete rec.fields.contact;
  // Wat nu uit Contacten kwam, blijft in Stamgegevens staan (als daar nog niets stond)
  for(const k of ['telefoon','email'])if(live.gevonden&&live[k]&&!rec.fields[k])rec.fields[k]=live[k];
  cmBusy(true);cmRequest('masterSave',[JSON.stringify(rec)],()=>{cmBusy(false);appIdentityRefresh();cmListLoad(()=>cmKaartLoad(r.id,t('Losgekoppeld van het contact. Telefoon en e-mail staan nu in Stamgegevens.')));},false,()=>cmBusy(false));
}

/* Een contact kiezen (Android: de contactkiezer, binnen het keuzevenster zonder opnieuw ontgrendelen). */
// Uit Contacten overnemen: alleen lege velden; BSN, documenten en notities nooit
const CM_UIT_CONTACT=['roepnaam','voornamen','naam','geboortedatum','telefoon','email','adres','postcode','plaats','land'];
// De Play-versie heeft geen toegang tot Contacten (play/basis.json)
function cmContactKan(){return typeof Android.masterContactPick==='function'&&!(typeof isPlay==='function'&&isPlay());}
function cmContactFill(fields,c){
  const f=c.fields||{};for(const k of CM_UIT_CONTACT)if(typeof f[k]==='string'&&f[k]&&!fields[k])fields[k]=f[k].slice(0,240);
  if(!fields.roepnaam&&!fields.voornamen&&!fields.naam&&typeof f.weergave==='string'&&f.weergave)fields.roepnaam=f.weergave.slice(0,120);
  if(c.contact)fields.contact=c.contact;
}
function cmContactPick(done){
  if(!cmOpen()||cm.mutating||cmct.request||!cmContactKan())return;
  cmct.done=done;cmct.request='cm-contact-'+(++cm.seq);cmNote(t('Kies een contact…'));
  Android.masterContactPick(cm.token,cmct.request);
}
window.onMasterContactPicked=function(r){
  if(!r||!cmct.request||r.request!==cmct.request)return;const done=cmct.done;cmct.request='';cmct.done=null;
  if(r.token!==cm.token||!cmOpen())return;
  if(r.error){cmNote(t(r.error));return;}
  cmNote('');if(r.contact&&done)done(r.contact);
};
const cmcClear=cmClearCentral;cmClearCentral=function(){cmcPhotoViewerClose();cmc.rec=null;cmc.epoch++;cmc.photoEpoch++;cmc.fotos={};cmc.laden.clear();cmc.reveal=false;cmct.request='';cmct.done=null;cmcClear();};
