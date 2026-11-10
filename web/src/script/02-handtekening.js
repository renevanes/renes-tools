/* ---------- Je handtekening: één voor de hele app ----------
 * Versleuteld op de telefoon (PdfSignature, gaat mee in Alle appgegevens). Alleen dit bestand leest en schrijft hem
 * (Android.pdfSignature*); tools gebruiken sigLoad/sigSave/sigVraagWeg, tekenen gaat overal via hetzelfde venster
 * (sigTeken) en tonen of in een PDF zetten met sigPaint/sigPreview. Wie wil weten dat hij veranderde: sigWatch.
 * Tekenen kan ook vanuit een foto of scan (sigFoto, sigLijnen). Gebruikt door Stamgegevens (je eigen kaartje, Ondertekenen),
 * PDF → Bewerken en ondertekenen en Formulieren. */
const sig={value:null,error:'',loaded:false,watch:[],dlg:null,pad:null,terug:'',kiezen:null,bezig:false};
const SIG_MAX_POINTS=10000,SIG_MAX_PATHS=200;
function sigValid(s){
  if(!s||!Number.isFinite(s.ratio)||s.ratio<.01||s.ratio>10||!/^#[0-9a-f]{6}$/i.test(s.color||'')||!Array.isArray(s.paths)||!s.paths.length||s.paths.length>SIG_MAX_PATHS)return false;
  let count=0,moved=false;
  for(const path of s.paths){
    if(!Array.isArray(path)||path.length<2)return false;
    count+=path.length;if(count>SIG_MAX_POINTS)return false;
    for(const p of path){if(!Array.isArray(p)||p.length!==2||p.some(v=>!Number.isFinite(v)||v<0||v>1))return false;if(p[0]!==path[0][0]||p[1]!==path[0][1])moved=true;}
  }
  return moved;
}
/* Meldingen uit Java (PdfSignature, Kluis) komen Nederlands binnen; alleen deze bekende tonen we (vertaald), voor de
 * rest (technische fouten) de algemene melding anders. */
function sigFout(m,anders){
  switch(m){
    case 'Ongeldige handtekening':return t('Ongeldige handtekening');
    case 'Ongeldige kleur':return t('Ongeldige kleur');
    case 'Teken eerst je handtekening':return t('Teken eerst je handtekening');
    case 'Handtekening is te groot':return t('Handtekening is te groot');
    case 'Handtekening heeft te veel lijnen':return t('Handtekening heeft te veel lijnen');
    case 'Bewaarde handtekening is te groot':return t('Bewaarde handtekening is te groot');
    case 'Handtekening verwijderen lukt niet':return t('Handtekening verwijderen lukt niet');
    case 'Geen kluisbestand':return t('Geen kluisbestand');
    case 'Kluisbestand is beschadigd':return t('Kluisbestand is beschadigd');
    case 'Opslaan kon niet worden gecontroleerd':return t('Opslaan kon niet worden gecontroleerd');
    case 'Opslaan lukt niet; het eerdere bestand blijft behouden':return t('Opslaan lukt niet; het eerdere bestand blijft behouden');
    case 'Deze afbeelding kan niet worden geopend':return t('Deze afbeelding kan niet worden geopend');
    case 'De afbeelding is te groot':return t('De afbeelding is te groot');
    case 'Bestandskiezer niet beschikbaar':return t('Bestandskiezer niet beschikbaar');
    default:return anders;
  }
}
function sigWatch(fn){sig.watch.push(fn);}
function sigChanged(){for(const fn of sig.watch)try{fn(sig.value,sig.error);}catch(e){console.error(e);}}
/* Opnieuw uit de opslag lezen; geeft de handtekening of null (de reden staat dan in sig.error). Watchers alleen bij een verandering. */
function sigLoad(){
  const was=sig.loaded?JSON.stringify([sig.value,sig.error]):'';
  try{const r=JSON.parse(Android.pdfSignatureLoad());if(r.error)throw Error(sigFout(r.error,t('Handtekening laden lukt niet')));if(r.signature&&!sigValid(r.signature))throw Error(t('Bewaarde handtekening is niet te lezen'));sig.value=r.signature||null;sig.error='';}
  catch(e){sig.value=null;sig.error=e.message||t('Handtekening laden lukt niet');}
  sig.loaded=true;if(JSON.stringify([sig.value,sig.error])!==was)sigChanged();return sig.value;
}
function sigHuidig(){return sig.loaded?sig.value:sigLoad();}
/* Bewaren; '' als het lukte, anders de reden (de vorige blijft dan staan). */
function sigSave(s){
  if(!sigValid(s))return t('Teken een handtekening met maximaal 10.000 punten en 200 lijnen');
  let err;try{err=Android.pdfSignatureSave(JSON.stringify(s));}catch(e){err='?';}
  if(err)return sigFout(err,t('Handtekening bewaren lukt niet'));
  sig.value=JSON.parse(JSON.stringify(s));sig.error='';sig.loaded=true;sigChanged();return '';
}
/* Vragen en verwijderen; wat al in een PDF staat, blijft staan. focusId: waar de focus daarna heen gaat (de knop Verwijderen verdwijnt). */
function sigVraagWeg(focusId){
  askConfirm(t('Bewaarde handtekening verwijderen?'),t('Je kunt haar daarna niet meer opnieuw gebruiken. Handtekeningen die al in een PDF staan blijven staan.'),t('Verwijderen'),()=>{
    let err;try{err=Android.pdfSignatureDelete();}catch(e){err='?';}
    if(err){toast(sigFout(err,t('Handtekening verwijderen lukt niet')));return;}
    sig.value=null;sig.error='';sig.loaded=true;sigChanged();toast(t('Bewaarde handtekening verwijderd'));
    const el=focusId&&$('#'+focusId);if(el)el.focus();
  });
}
/* In een vak, niet vervormd: in het midden (voorbeeld) of links onderaan op de lijn (onder: true, zoals in een formulier). */
function sigPaint(ctx,s,box,opts={}){
  const w=Math.min(box.w,box.h/s.ratio),h=w*s.ratio,x=opts.onder?box.x:box.x+(box.w-w)/2,y=opts.onder?box.y+box.h-h:box.y+(box.h-h)/2;
  ctx.save();ctx.strokeStyle=s.color;ctx.lineWidth=Math.max(opts.min||.6,w*.008);ctx.lineCap=ctx.lineJoin='round';
  for(const path of s.paths){ctx.beginPath();path.forEach((p,i)=>{if(i)ctx.lineTo(x+p[0]*w,y+p[1]*h);else ctx.moveTo(x+p[0]*w,y+p[1]*h);});ctx.stroke();}
  ctx.restore();
}
/* Een voorbeeld op papier (canvas met class sig-papier); false als er geen is. */
function sigPreview(canvas,s=sig.value){
  const ctx=canvas.getContext('2d');ctx.clearRect(0,0,canvas.width,canvas.height);if(!s)return false;
  sigPaint(ctx,s,{x:12,y:12,w:canvas.width-24,h:canvas.height-24},{min:2});return true;
}
function sigCanvas(id,label){const c=document.createElement('canvas');c.width=600;c.height=180;c.className='sig-papier';if(id)c.id=id;c.setAttribute('role','img');c.setAttribute('aria-label',label||t('Je bewaarde handtekening'));sigPreview(c);return c;}
/* Getekende lijnen (punten 0–1 van het tekenvlak) als handtekening: bijgesneden tot wat er getekend is. */
function sigFromStrokes(strokes,color,aspect){
  const paths=strokes.filter(p=>p.length>1);if(!paths.length)throw Error(t('Teken eerst je handtekening'));
  const points=paths.flat(),xs=points.map(p=>p[0]),ys=points.map(p=>p[1]);
  const x=Math.max(0,Math.min(...xs)-.02),y=Math.max(0,Math.min(...ys)-.06),w=Math.min(1,Math.max(...xs)+.02)-x,h=Math.min(1,Math.max(...ys)+.06)-y;
  const s={version:1,ratio:h/(w*aspect),color,paths:paths.map(p=>p.map(q=>[(q[0]-x)/w,(q[1]-y)/h]))};
  if(!sigValid(s))throw Error(t('Teken een handtekening met maximaal 10.000 punten en 200 lijnen'));
  return s;
}
/* ---------- Handtekening uit een foto of scan ----------
 * Donkere inkt op licht papier wordt lijnen, zodat hij overal net zo werkt als een getekende (PDF, Formulieren): inkt
 * scheiden van papier met een drempel per buurt (schaduw, ongelijk licht), stipjes en grote vlekken aan de rand weg,
 * bijsnijden, uitdunnen tot middenlijnen (Zhang-Suen) en die volgen en vereenvoudigen. gray: 0 = zwart … 255 = wit.
 * Tussen de stappen even ruimte voor het scherm; meld(deel 0–1) laat zien hoe ver het is, geeft meld false, dan stopt het (null). */
async function sigLijnen(gray,w,h,meld=()=>true){
  const adem=async deel=>{if(meld(deel)===false)throw sigGestopt;await new Promise(r=>setTimeout(r,0));if(meld(deel)===false)throw sigGestopt;};
  try{return await sigLijnenStappen(gray,w,h,adem);}catch(e){if(e===sigGestopt)return null;throw e;}
}
const sigGestopt={};
async function sigLijnenStappen(gray,w,h,adem){
  await adem(0);
  const n=w*h,W=w+1,I=new Float64Array(W*(h+1)),r=Math.max(8,Math.round(Math.max(w,h)/40));
  for(let y=0;y<h;y++){let rij=0;for(let x=0;x<w;x++){rij+=gray[y*w+x];I[(y+1)*W+x+1]=I[y*W+x+1]+rij;}}
  const gem=(x,y,r)=>{const x0=Math.max(0,x-r),x1=Math.min(w,x+r+1),y0=Math.max(0,y-r),y1=Math.min(h,y+r+1);return (I[y1*W+x1]-I[y0*W+x1]-I[y1*W+x0]+I[y0*W+x0])/((x1-x0)*(y1-y0));};
  const donker=(v,g)=>v<g*.8&&g-v>24;
  // Ook een buurt van drie keer zo groot: anders wordt het midden van een dikke lijn (viltstift, dichtbij) papier en de lijn dubbel
  let inkt=new Uint8Array(n),aantal=0;
  for(let y=0;y<h;y++)for(let x=0;x<w;x++){const v=gray[y*w+x];if(donker(v,gem(x,y,r))||donker(v,gem(x,y,3*r))){inkt[y*w+x]=1;aantal++;}}
  if(aantal>n*.25)throw Error(t('Geen duidelijke handtekening gevonden. Kies een foto van alleen je handtekening, op wit papier.'));
  await adem(.2);
  // Samenhangende stukken: stipjes (ruis) en grote vlekken aan de rand (schaduw, rand van het papier) weg
  const deel=new Int32Array(n).fill(-1),stukken=[],stapel=[];
  for(let i=0;i<n;i++){if(!inkt[i]||deel[i]>=0)continue;
    const s={id:stukken.length,px:0,rand:false,x0:w,y0:h,x1:0,y1:0};stukken.push(s);deel[i]=s.id;stapel.push(i);
    while(stapel.length){const p=stapel.pop(),x=p%w,y=(p-x)/w;s.px++;if(x===0||y===0||x===w-1||y===h-1)s.rand=true;
      if(x<s.x0)s.x0=x;if(x>s.x1)s.x1=x;if(y<s.y0)s.y0=y;if(y>s.y1)s.y1=y;
      for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++){const nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;const q=ny*w+nx;if(inkt[q]&&deel[q]<0){deel[q]=s.id;stapel.push(q);}}}
  }
  // Aan de rand: een vlek (tafel, schaduw: groot en gevuld) of een streep langs de rand (rand van het papier) weg, en
  // kleine stukjes alleen als er binnen de foto iets flinks staat. Een strak bijgesneden handtekening raakt de rand ook.
  const vlek=s=>s.rand&&s.px>n*.08&&s.px>(s.x1-s.x0+1)*(s.y1-s.y0+1)*.35;
  const langs=s=>s.rand&&((s.y1<h*.04||s.y0>h*.96)&&s.x1-s.x0>w*.5||(s.x1<w*.04||s.x0>w*.96)&&s.y1-s.y0>h*.5);
  const grootste=stukken.reduce((m,s)=>Math.max(m,vlek(s)||langs(s)?0:s.px),0),min=Math.max(6,n*.00002,grootste*.004);
  const binnen=stukken.reduce((m,s)=>Math.max(m,s.rand?0:s.px),0);
  let houd=stukken.map(s=>s.px>=min&&!vlek(s)&&!langs(s)&&!(s.rand&&binnen>=min&&s.px<binnen*.5));
  if(!houd.some(Boolean))houd=stukken.map(s=>s.px>=min&&!vlek(s));
  let bx0=w,by0=h,bx1=-1,by1=-1;
  for(const s of stukken)if(houd[s.id]){bx0=Math.min(bx0,s.x0);by0=Math.min(by0,s.y0);bx1=Math.max(bx1,s.x1);by1=Math.max(by1,s.y1);}
  if(bx1<0)throw Error(t('Geen handtekening gevonden. Kies een foto van je handtekening in donkere inkt op licht papier.'));
  // Bijsnijden en zo nodig verkleinen (elk werkpixel is inkt als één van de pixels eronder inkt is)
  const f=Math.max(1,Math.ceil(Math.max(bx1-bx0+1,by1-by0+1)/700)),ww=Math.ceil((bx1-bx0+1)/f)+2,wh=Math.ceil((by1-by0+1)/f)+2;
  let b=new Uint8Array(ww*wh);
  for(let y=by0;y<=by1;y++)for(let x=bx0;x<=bx1;x++){const i=y*w+x;if(inkt[i]&&houd[deel[i]])b[(1+Math.floor((y-by0)/f))*ww+1+Math.floor((x-bx0)/f)]=1;}
  inkt=null;await adem(.3);
  // Gaatjes in dikke lijnen dicht (sluiten: uitzetten en weer krimpen), anders worden het lusjes
  const buren=(a,i,waarde)=>{for(const d of [-ww-1,-ww,-ww+1,-1,1,ww-1,ww,ww+1])if(a[i+d]===waarde)return true;return false;};
  const dik=b.slice();for(let y=1;y<wh-1;y++)for(let x=1;x<ww-1;x++){const i=y*ww+x;if(!b[i]&&buren(b,i,1))dik[i]=1;}
  const open=[];for(let y=1;y<wh-1;y++)for(let x=1;x<ww-1;x++){const i=y*ww+x;if(dik[i]&&!b[i]&&buren(dik,i,0))open.push(i);}
  for(const i of open)dik[i]=0;
  b=dik;const voor=b.slice();await adem(.35);
  // Uitdunnen (Zhang-Suen) tot lijnen van één pixel; de eerste ronde haalt het meeste weg, daarop de voortgang
  let eerste=0,dun=.35;
  for(let veranderd=true,ronde=0;veranderd&&ronde<200;ronde++){veranderd=false;let rond=0;
    for(const stap of [0,1]){const weg=[];
      for(let y=1;y<wh-1;y++)for(let x=1;x<ww-1;x++){const i=y*ww+x;if(!b[i])continue;
        const p=[b[i-ww],b[i-ww+1],b[i+1],b[i+ww+1],b[i+ww],b[i+ww-1],b[i-1],b[i-ww-1]],som=p.reduce((s,v)=>s+v,0);if(som<2||som>6)continue;
        let overgangen=0;for(let k=0;k<8;k++)if(!p[k]&&p[(k+1)%8])overgangen++;if(overgangen!==1)continue;
        if(stap===0?(p[0]*p[2]*p[4]||p[2]*p[4]*p[6]):(p[0]*p[2]*p[6]||p[0]*p[4]*p[6]))continue;weg.push(i);}
      for(const i of weg)b[i]=0;if(weg.length)veranderd=true;rond+=weg.length;}
    if(!ronde)eerste=Math.max(1,rond);dun=Math.max(dun,.35+.5*Math.min(1,1-rond/eerste));await adem(dun);
  }
  // Zhang-Suen laat een klein rond stuk (de punt op de i) soms helemaal verdwijnen: dan blijft het middelste pixel staan
  const lbl=new Uint8Array(ww*wh);
  for(let i=0;i<voor.length;i++){if(!voor[i]||lbl[i])continue;
    const stuk=[i];lbl[i]=1;let over=false,sx=0,sy=0;
    for(let k=0;k<stuk.length;k++){const q=stuk[k];if(b[q])over=true;sx+=q%ww;sy+=Math.floor(q/ww);for(const d of [-ww-1,-ww,-ww+1,-1,1,ww-1,ww,ww+1])if(voor[q+d]&&!lbl[q+d]){lbl[q+d]=1;stuk.push(q+d);}}
    if(!over){const mx=sx/stuk.length,my=sy/stuk.length;b[stuk.reduce((m,q)=>Math.hypot(q%ww-mx,Math.floor(q/ww)-my)<Math.hypot(m%ww-mx,Math.floor(m/ww)-my)?q:m)]=1;}
  }
  // Lijnen volgen: eerst vanaf de uiteinden, daarna wat overblijft (rondjes)
  const RECHT=[-ww,1,ww,-1],SCHUIN=[-ww+1,ww+1,ww-1,-ww-1],gezien=new Uint8Array(ww*wh),lijnen=[];
  const aantalBuren=i=>{let c=0;for(const d of RECHT.concat(SCHUIN))if(b[i+d])c++;return c;};
  const volgende=i=>{for(const d of RECHT)if(b[i+d]&&!gezien[i+d])return i+d;for(const d of SCHUIN)if(b[i+d]&&!gezien[i+d])return i+d;return -1;};
  const aanhaken=(i,niet)=>{for(const d of RECHT.concat(SCHUIN))if(b[i+d]&&gezien[i+d]&&!niet.includes(i+d))return i+d;return -1;};
  const volg=start=>{const pad=[start];gezien[start]=1;const voor=aanhaken(start,[]);
    for(let i=volgende(start);i>=0;i=volgende(i)){gezien[i]=1;pad.push(i);}
    const na=aanhaken(pad[pad.length-1],pad.slice(-3));if(na>=0&&pad.length>1)pad.push(na);if(voor>=0&&voor!==pad[pad.length-1])pad.unshift(voor);
    lijnen.push(pad.map(i=>[i%ww-1+.5,Math.floor(i/ww)-1+.5]));};
  for(let i=0;i<b.length;i++)if(b[i]&&!gezien[i]&&aantalBuren(i)===1)volg(i);
  for(let i=0;i<b.length;i++)if(b[i]&&!gezien[i])volg(i);
  if(!lijnen.length)throw Error(t('Geen handtekening gevonden. Kies een foto van je handtekening in donkere inkt op licht papier.'));
  await adem(.9);
  // Vereenvoudigen (Ramer-Douglas-Peucker) tot het binnen de grenzen past; de kortste stukjes vallen als eerste af
  const lengte=l=>l.reduce((s,p,i)=>i?s+Math.hypot(p[0]-l[i-1][0],p[1]-l[i-1][1]):0,0);
  const rdp=(l,eps)=>{if(l.length<3)return l;const [a,z]=[l[0],l[l.length-1]],dx=z[0]-a[0],dy=z[1]-a[1],len=Math.hypot(dx,dy);let ver=-1,k=0;
    for(let i=1;i<l.length-1;i++){const d=len?Math.abs(dy*(l[i][0]-a[0])-dx*(l[i][1]-a[1]))/len:Math.hypot(l[i][0]-a[0],l[i][1]-a[1]);if(d>ver){ver=d;k=i;}}
    return ver>eps?rdp(l.slice(0,k+1),eps).slice(0,-1).concat(rdp(l.slice(k),eps)):[a,z];};
  let paden=lijnen.map(l=>l.length<2?[l[0],[l[0][0]+1,l[0][1]]]:l).sort((a,c)=>lengte(c)-lengte(a)).slice(0,SIG_MAX_PATHS);
  for(let eps=.8;;eps*=1.6){const uit=paden.map(l=>rdp(l,eps));if(uit.reduce((s,l)=>s+l.length,0)<=SIG_MAX_POINTS||eps>50){paden=uit;break;}}
  // Naar 0–1 binnen de handtekening, met een kleine rand
  const pts=paden.flat(),x0=Math.min(...pts.map(p=>p[0])),y0=Math.min(...pts.map(p=>p[1])),x1=Math.max(...pts.map(p=>p[0])),y1=Math.max(...pts.map(p=>p[1]));
  const marge=Math.max(x1-x0,y1-y0,1)*.03,bw=x1-x0+2*marge,bh=y1-y0+2*marge;
  const rond=v=>Math.round(Math.min(1,Math.max(0,v))*10000)/10000;
  return {ratio:bh/bw,paths:paden.map(l=>l.map(p=>[rond((p[0]-x0+marge)/bw),rond((p[1]-y0+marge)/bh)]))};
}
/* Een geladen afbeelding (img) als lijnen; doorzichtig telt als wit papier. */
function sigUitAfbeelding(img,meld){
  const max=1400,s=Math.min(1,max/Math.max(img.naturalWidth,img.naturalHeight)),w=Math.max(1,Math.round(img.naturalWidth*s)),h=Math.max(1,Math.round(img.naturalHeight*s));
  const c=document.createElement('canvas');c.width=w;c.height=h;const ctx=c.getContext('2d',{willReadFrequently:true});
  ctx.fillStyle='#fff';ctx.fillRect(0,0,w,h);ctx.drawImage(img,0,0,w,h);
  const d=ctx.getImageData(0,0,w,h).data,gray=new Uint8Array(w*h);
  for(let i=0;i<gray.length;i++)gray[i]=(d[i*4]*30+d[i*4+1]*59+d[i*4+2]*11)/100;
  return sigLijnen(gray,w,h,meld);
}
function sigFotoKan(){return typeof Android!=='undefined'&&typeof Android.pdfSignaturePick==='function';}
/* Kiezen gaat via Android (een kiezer over de app heen); is Stamgegevens open, dan blijft die zo lang open. */
function sigFoto(){
  if(!sig.dlg||sig.kiezen||sig.bezig||!sigFotoKan())return;
  sig.kiezen=sig.dlg;$('#sig-fout').textContent='';$('#sig-status').textContent=t('Kies een foto of scan van je handtekening…');sigBezig(false);
  const token=typeof cm!=='undefined'&&typeof cmOpen==='function'&&cmOpen()?cm.token:'';
  try{Android.pdfSignaturePick(token||'');}catch(e){sig.kiezen=null;$('#sig-status').textContent='';$('#sig-fout').textContent=t('Bestandskiezer niet beschikbaar');}
}
/* Bezig met de foto (inlezen in Android, daarna lijnen zoeken): de balk in het venster, en zolang niet bewaren of nog een foto
 * kiezen. tekst false = klaar. */
function sigBezig(tekst,deel){
  sig.bezig=!!tekst;uiWacht($('#sig-wacht'),tekst,deel);
  for(const id of ['#sig-foto','#sig-ok','#sig-wis'])$(id).disabled=!!tekst;$('#sig-pad').style.pointerEvents=tekst?'none':'';
  if(tekst)$('#sig-status').textContent='';
}
// Het inlezen van de gekozen foto (Voortgang "handtekening" in Java) staat in het venster, niet in de balk onderin.
uiVgEigen.handtekening=r=>{
  if(!sig.kiezen||sig.dlg!==sig.kiezen)return false;
  if(!r.klaar)sigBezig(r.stap==='lezen'?t('Foto lezen…'):t('Foto verwerken…'),r.stap==='lezen'&&r.totaal>0?.3*r.gedaan/r.totaal:undefined);
  return true;
};
window.onSignatureImage=function(r){
  const dlg=sig.kiezen;sig.kiezen=null;if(!dlg||sig.dlg!==dlg)return;
  const stand=$('#sig-status'),fout=m=>{sigBezig(false);stand.textContent='';$('#sig-fout').textContent=m;};
  if(!r||r.cancelled){sigBezig(false);stand.textContent='';return;}
  if(r.error){fout(sigFout(r.error,t('Deze afbeelding kan niet worden geopend')));return;}
  if(typeof r.image!=='string'||r.image.length>1200000||!/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(r.image)){fout(t('Deze afbeelding kan niet worden geopend'));return;}
  const zoek=t('Handtekening zoeken op de foto…');sigBezig(zoek,.3);
  const img=new Image();
  img.onload=async()=>{
    if(sig.dlg!==dlg)return;
    try{
      const s=await sigUitAfbeelding(img,deel=>{if(sig.dlg!==dlg)return false;sigBezig(zoek,.3+.7*deel);return true;});
      if(!s||sig.dlg!==dlg)return;
      sigBezig(false);sig.pad.load(s);stand.textContent=t('Zo ziet je handtekening eruit. Klopt hij niet? Kies Wissen en probeer een andere foto.');
    }catch(e){if(sig.dlg===dlg)fout(e.message||t('Deze afbeelding kan niet worden geopend'));}
  };
  img.onerror=()=>{if(sig.dlg===dlg)fout(t('Deze afbeelding kan niet worden geopend'));};
  img.src=r.image;
};

/* Een tekenvlak: met vinger, pen of muis. */
function sigPad(canvas,kleur){
  const pad={strokes:[],stroke:null};
  const punt=e=>{const r=canvas.getBoundingClientRect();return [Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))];};
  canvas.addEventListener('pointerdown',e=>{pad.stroke=[punt(e)];pad.strokes.push(pad.stroke);canvas.setPointerCapture(e.pointerId);e.preventDefault();});
  canvas.addEventListener('pointermove',e=>{
    if(!pad.stroke||pad.strokes.reduce((n,a)=>n+a.length,0)>=SIG_MAX_POINTS)return;
    const p=punt(e),q=pad.stroke[pad.stroke.length-1],c=canvas.getContext('2d');pad.stroke.push(p);
    c.strokeStyle=kleur();c.lineWidth=Math.max(3,canvas.width/150);c.lineCap='round';c.beginPath();c.moveTo(q[0]*canvas.width,q[1]*canvas.height);c.lineTo(p[0]*canvas.width,p[1]*canvas.height);c.stroke();
  });
  for(const ev of ['pointerup','pointercancel'])canvas.addEventListener(ev,()=>{pad.stroke=null;});
  pad.clear=()=>{pad.strokes=[];pad.stroke=null;canvas.getContext('2d').clearRect(0,0,canvas.width,canvas.height);};
  pad.draw=()=>{
    const c=canvas.getContext('2d');c.clearRect(0,0,canvas.width,canvas.height);c.strokeStyle=kleur();c.lineWidth=Math.max(3,canvas.width/150);c.lineCap=c.lineJoin='round';
    for(const st of pad.strokes){c.beginPath();st.forEach((p,i)=>{const x=p[0]*canvas.width,y=p[1]*canvas.height;if(i)c.lineTo(x,y);else c.moveTo(x,y);});c.stroke();}
  };
  /* Een handtekening (uit een foto) op het tekenvlak, in het midden; daarna kun je er nog bij tekenen. */
  pad.load=s=>{
    const cw=canvas.width,ch=canvas.height,bw=Math.min(cw*.9,ch*.8/s.ratio),bh=bw*s.ratio,x0=(cw-bw)/2,y0=(ch-bh)/2;
    pad.strokes=s.paths.map(l=>l.map(p=>[(x0+p[0]*bw)/cw,(y0+p[1]*bh)/ch]));pad.stroke=null;pad.draw();
  };
  pad.signature=()=>sigFromStrokes(pad.strokes,kleur(),canvas.width/canvas.height);
  return pad;
}

/* Het venster Handtekening tekenen, voor elke tool.
 * opts.plaatsen(s, bewaard): ook (eenmalig) plaatsen, zoals in een PDF; bewaren is dan een keuze. Zonder: tekenen = bewaren.
 * opts.bewaard(s): na bewaren. */
function sigTeken(opts={}){
  const heeft=!!(sigHuidig()||sig.error),plaats=typeof opts.plaatsen==='function';
  if(!sig.pad){sig.pad=sigPad($('#sig-pad'),()=>$('#sig-kleur').value);$('#sig-kleur').addEventListener('change',()=>sig.pad.draw());}
  sig.pad.clear();sig.dlg=opts;sig.kiezen=null;sigBezig(false);$('#sig-status').textContent='';$('#sig-foto').hidden=!sigFotoKan();sig.terug=document.activeElement&&document.activeElement.id||'';
  if(sig.value&&[...$('#sig-kleur').options].some(o=>o.value===sig.value.color))$('#sig-kleur').value=sig.value.color;
  $('#sig-title').textContent=heeft&&!plaats?t('Handtekening vervangen'):t('Handtekening tekenen');
  $('#sig-uitleg').textContent=plaats?t('Teken met je vinger. Bewaar je hem, dan gebruik je hem ook in Formulieren en Stamgegevens.')
    :heeft?t('Teken met je vinger. De nieuwe vervangt je bewaarde handtekening, overal in de app.'):t('Teken met je vinger. Hij wordt versleuteld bewaard op deze telefoon en is daarna te gebruiken in PDF, Formulieren en Stamgegevens.');
  $('#sig-bewaar-rij').hidden=!plaats;$('#sig-bewaar').checked=false; // bewust kiezen: wie in een PDF tekent, is niet altijd jij
  $('#sig-bewaar-t').textContent=heeft?t('Ook bewaren (vervangt je bewaarde handtekening)'):t('Ook bewaren voor later');
  $('#sig-ok').textContent=plaats?t('Plaatsen'):t('Bewaren');$('#sig-fout').textContent='';
  $('#sigdlg').classList.add('on');dialogFocus.sync($('#sig-pad'));
}
function sigOpen(){return $('#sigdlg').classList.contains('on');}
/* De knop die het venster opende, kan intussen opnieuw getekend zijn (sigWatch): dan de nieuwe met dezelfde id. */
function sigClose(){
  if(sig.pad)sig.pad.clear();sig.dlg=null;sig.kiezen=null;sigBezig(false);$('#sig-fout').textContent='';$('#sig-status').textContent='';$('#sigdlg').classList.remove('on');dialogFocus.sync();
  const terug=sig.terug;sig.terug='';
  const weg=!document.activeElement||document.activeElement===document.body||$('#sigdlg').contains(document.activeElement);
  if(terug&&weg){const el=document.getElementById(terug);if(el&&!el.closest('[inert]'))el.focus();}
}
function sigWis(){if(sig.pad)sig.pad.clear();$('#sig-fout').textContent='';$('#sig-status').textContent='';}
function sigOk(){
  const o=sig.dlg;if(!o||sig.bezig)return;
  let s;try{s=sig.pad.signature();}catch(e){$('#sig-fout').textContent=e.message;return;}
  const plaats=typeof o.plaatsen==='function',bewaar=!plaats||$('#sig-bewaar').checked;
  if(bewaar){const err=sigSave(s);if(err){$('#sig-fout').textContent=err;return;}}
  sigClose();
  if(bewaar&&o.bewaard)o.bewaard(s);
  if(plaats)o.plaatsen(s,bewaar);else toast(t('Handtekening bewaard'));
}
