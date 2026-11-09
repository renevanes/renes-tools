/* ---------- Je handtekening: één voor de hele app ----------
 * Versleuteld op de telefoon (PdfSignature, gaat mee in Alle appgegevens). Alleen dit bestand leest en schrijft hem
 * (Android.pdfSignature*); tools gebruiken sigLoad/sigSave/sigVraagWeg, tekenen gaat overal via hetzelfde venster
 * (sigTeken) en tonen of in een PDF zetten met sigPaint/sigPreview. Wie wil weten dat hij veranderde: sigWatch.
 * Gebruikt door Stamgegevens (je eigen kaartje, Ondertekenen), PDF → Bewerken en ondertekenen en Formulieren. */
const sig={value:null,error:'',loaded:false,watch:[],dlg:null,pad:null};
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
function sigWatch(fn){sig.watch.push(fn);}
function sigChanged(){for(const fn of sig.watch)fn(sig.value,sig.error);}
/* Opnieuw uit de opslag lezen; geeft de handtekening of null (de reden staat dan in sig.error). Watchers alleen bij een verandering. */
function sigLoad(){
  const was=sig.loaded?JSON.stringify([sig.value,sig.error]):'';
  try{const r=JSON.parse(Android.pdfSignatureLoad());if(r.error)throw Error(t(String(r.error)));if(r.signature&&!sigValid(r.signature))throw Error(t('Bewaarde handtekening is niet te lezen'));sig.value=r.signature||null;sig.error='';}
  catch(e){sig.value=null;sig.error=e.message||t('Handtekening laden lukt niet');}
  sig.loaded=true;if(JSON.stringify([sig.value,sig.error])!==was)sigChanged();return sig.value;
}
function sigHuidig(){return sig.loaded?sig.value:sigLoad();}
/* Bewaren; '' als het lukte, anders de reden (de vorige blijft dan staan). */
function sigSave(s){
  if(!sigValid(s))return t('Teken een handtekening met maximaal 10.000 punten en 200 lijnen');
  let err;try{err=Android.pdfSignatureSave(JSON.stringify(s));}catch(e){err='Handtekening bewaren lukt niet';}
  if(err)return t(err);
  sig.value=JSON.parse(JSON.stringify(s));sig.error='';sig.loaded=true;sigChanged();return '';
}
/* Vragen en verwijderen; wat al in een PDF staat, blijft staan. */
function sigVraagWeg(){
  askConfirm(t('Bewaarde handtekening verwijderen?'),t('Je kunt haar daarna niet meer opnieuw gebruiken. Handtekeningen die al in een PDF staan blijven staan.'),t('Verwijderen'),()=>{
    let err;try{err=Android.pdfSignatureDelete();}catch(e){err='Handtekening verwijderen lukt niet';}
    if(err){toast(t(err));return;}
    sig.value=null;sig.error='';sig.loaded=true;sigChanged();toast(t('Bewaarde handtekening verwijderd'));
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
  pad.signature=()=>sigFromStrokes(pad.strokes,kleur(),canvas.width/canvas.height);
  return pad;
}

/* Het venster Handtekening tekenen, voor elke tool.
 * opts.plaatsen(s, bewaard): ook (eenmalig) plaatsen, zoals in een PDF; bewaren is dan een keuze. Zonder: tekenen = bewaren.
 * opts.bewaard(s): na bewaren. */
function sigTeken(opts={}){
  const heeft=!!(sigHuidig()||sig.error),plaats=typeof opts.plaatsen==='function';
  if(!sig.pad)sig.pad=sigPad($('#sig-pad'),()=>$('#sig-kleur').value);
  sig.pad.clear();sig.dlg=opts;
  $('#sig-title').textContent=heeft&&!plaats?t('Handtekening vervangen'):t('Handtekening tekenen');
  $('#sig-uitleg').textContent=plaats?t('Teken met je vinger. Bewaar je hem, dan gebruik je hem ook in Formulieren en Stamgegevens.')
    :heeft?t('Teken met je vinger. De nieuwe vervangt je bewaarde handtekening, overal in de app.'):t('Teken met je vinger. Hij wordt versleuteld bewaard op deze telefoon en is daarna te gebruiken in PDF, Formulieren en Stamgegevens.');
  $('#sig-bewaar-rij').hidden=!plaats;$('#sig-bewaar').checked=!heeft;
  $('#sig-bewaar-t').textContent=heeft?t('Ook bewaren (vervangt je bewaarde handtekening)'):t('Ook bewaren voor later');
  $('#sig-ok').textContent=plaats?t('Plaatsen'):t('Bewaren');$('#sig-fout').textContent='';
  $('#sigdlg').classList.add('on');dialogFocus.sync($('#sig-pad'));
}
function sigOpen(){return $('#sigdlg').classList.contains('on');}
function sigClose(){if(sig.pad)sig.pad.clear();sig.dlg=null;$('#sig-fout').textContent='';$('#sigdlg').classList.remove('on');dialogFocus.sync();}
function sigWis(){if(sig.pad)sig.pad.clear();$('#sig-fout').textContent='';}
function sigOk(){
  const o=sig.dlg;if(!o)return;
  let s;try{s=sig.pad.signature();}catch(e){$('#sig-fout').textContent=e.message;return;}
  const plaats=typeof o.plaatsen==='function',bewaar=!plaats||$('#sig-bewaar').checked;
  if(bewaar){const err=sigSave(s);if(err){$('#sig-fout').textContent=err;return;}}
  sigClose();
  if(bewaar&&o.bewaard)o.bewaard(s);
  if(plaats)o.plaatsen(s,bewaar);else toast(t('Handtekening bewaard'));
}
