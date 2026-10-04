/* ---------- Auto redial ---------- */
const ATT = [[5,'5'],[10,'10'],[25,'25'],[50,'50'],[0,'∞']];
const INT = [[5,'5 s'],[10,'10 s'],[20,'20 s'],[30,'30 s'],[60,'1 min'],[-1,'Willekeurig']];
const RANGE = [5,10,15,20,30,45,60,90,120,180,300];
const fmtS = v => v < 60 ? v + ' s' : (v % 60 ? (v/60).toFixed(1).replace('.',',') : v/60) + ' min';
function renderRange(){
  const on = store.get('interval',10) === -1;
  $('#rand-range').classList.toggle('on', on);
  const mn = store.get('rmin',10), mx = store.get('rmax',60);
  $('#r-min').innerHTML = RANGE.map(v=>'<option value="'+v+'"'+(v===mn?' selected':'')+'>'+fmtS(v)+'</option>').join('');
  $('#r-max').innerHTML = RANGE.map(v=>'<option value="'+v+'"'+(v===mx?' selected':'')+'>'+fmtS(v)+'</option>').join('');
}
function rangeChanged(which){
  let mn = +$('#r-min').value, mx = +$('#r-max').value;
  if (mn > mx) { if (which==='min') mx = mn; else mn = mx; }
  store.set('rmin', mn); store.set('rmax', mx); renderRange();
}
function chips(el, opts, key, def){
  const cur = store.get(key, def); el.innerHTML='';
  opts.forEach(([v,l])=>{ const b=document.createElement('button'); b.textContent=l; b.classList.toggle('on', v===cur);
    b.onclick=()=>{ store.set(key,v); chips(el,opts,key,def); Android.vibrate(10); if (key==='interval') renderRange(); }; el.appendChild(b); });
}
function tog(b){ b.classList.toggle('on'); store.set(b.id, b.classList.contains('on')); Android.vibrate(10); }

function renderRecent(){
  const r = store.get('recent', []), el = $('#recent'); el.innerHTML='';
  r.forEach(x=>{ const b=document.createElement('button'); b.textContent = x.name ? x.name : x.number;
    b.onclick=()=>{ $('#num').value=x.number; nameFor=x.name||''; $('#pname').textContent=nameFor; }; el.appendChild(b); });
}
function remember(number, name){
  let r = store.get('recent', []).filter(x=>norm(x.number)!==norm(number));
  r.unshift({number, name}); store.set('recent', r.slice(0,5)); renderRecent();
}
const norm = n => (n||'').replace(/[^0-9+*#]/g,'');

window.onContactPicked = function(c){ $('#num').value = c.number; nameFor = c.name||''; $('#pname').textContent = nameFor; };

function perms(){ try { return JSON.parse(Android.permissions()); } catch(e){ return {}; } }
function renderPerms(){
  const p = perms(), need = !p.call || !p.phoneState || !p.callLog || !p.notifications;
  $('#perm-card').style.display = need ? 'block' : 'none';
  const asked = store.get('askedPerms', false);
  let txt = 'Auto redial heeft toestemming nodig om te bellen en om te zien wanneer een gesprek voorbij is.';
  if (p.call && p.phoneState && !p.callLog) txt = 'Geef ook toegang tot je oproepgeschiedenis. Dan kan de app zien of er is opgenomen. Zonder die toegang telt een gesprek van langer dan 1 minuut als opgenomen.';
  else if (p.call && p.phoneState && p.callLog && !p.notifications) txt = 'Sta meldingen toe, dan zie je de voortgang en kun je stoppen vanuit de meldingenbalk.';
  $('#perm-text').textContent = txt;
  $('#perm-btn').textContent = asked ? 'Instellingen openen' : 'Toestemming geven';
  $('#how-note').textContent = p.callLog
    ? 'De app belt het nummer, wacht tot het gesprek voorbij is en kijkt in je oproepgeschiedenis of er is opgenomen. Een voicemail telt ook als opgenomen.'
    : 'Zonder toegang tot je oproepgeschiedenis telt een gesprek van langer dan 1 minuut als opgenomen.';
  return p;
}
function askPerms(){
  if (store.get('askedPerms', false)) { Android.openAppSettings(); return; }
  store.set('askedPerms', true); Android.requestPermissions();
}
window.onPermissions = function(p){ const all = p.call && p.phoneState && p.callLog && p.notifications; if (all) store.set('askedPerms', false); renderPerms(); };

function enterRedial(){
  chips($('#c-att'), ATT, 'attempts', 10);
  chips($('#c-int'), INT, 'interval', 10); renderRange();
  $('#t-stop').classList.toggle('on', store.get('t-stop', true));
  $('#t-spk').classList.toggle('on', store.get('t-spk', false));
  if (!$('#num').value) { const r = store.get('recent', []); if (r[0]) { $('#num').value = r[0].number; nameFor = r[0].name||''; $('#pname').textContent = nameFor; } }
  renderRecent(); renderPerms(); pollOnce(); rpRender();
  stopPoll(); poll = setInterval(pollOnce, 500);
}
function stopPoll(){ if (poll) { clearInterval(poll); poll = null; } }

function startRedial(){
  const n = $('#num').value.trim();
  const p = renderPerms();
  if (!p.call || !p.phoneState) { toast('Geef eerst toestemming'); askPerms(); return; }
  const iv = store.get('interval',10), rnd = iv === -1;
  const rmin = store.get('rmin',10), rmax = store.get('rmax',60);
  const err = Android.redialStart2(n, nameFor, store.get('attempts',10), rnd ? rmin : iv, rnd ? rmin : 0, rnd ? rmax : 0, store.get('t-stop',true), store.get('t-spk',false));
  if (err) { toast(err); return; }
  remember(n, nameFor); lastResultShown = ''; $('#result').className='result';
  Android.vibrate(25); setTimeout(pollOnce, 150);
}
function stopRedial(){ Android.redialStop(); Android.vibrate(25); setTimeout(pollOnce, 200); }

const PHASE = {dialing:'Bellen…', incall:'Gaat over of in gesprek', checking:'Controleren…', waiting:'Wachten'};
function pollOnce(){
  let s; try { s = JSON.parse(Android.redialStatus()); } catch(e){ return; }
  const run = !!s.running;
  $('#run-card').style.display = run ? 'block' : 'none';
  $('#setup').style.display = run ? 'none' : 'block';
  if (run) {
    $('#run-att').textContent = s.attempt || 1;
    $('#run-of').textContent = s.maxAttempts ? 'van ' + s.maxAttempts : 'pogingen';
    let ph = s.message || PHASE[s.phase] || '';
    let frac = 0;
    if (s.phase === 'waiting') {
      const left = Math.max(0, Math.ceil((s.nextAt - s.now)/1000));
      ph = (s.message ? s.message + ' · ' : '') + 'opnieuw over ' + left + ' s';
      frac = 1 - Math.min(1, (s.nextAt - s.now) / ((s.wait || s.interval)*1000));
    } else if (s.phase === 'incall' || s.phase === 'dialing') frac = 1;
    $('#run-phase').textContent = ph;
    $('#run-who').textContent = (s.name ? s.name + ' · ' + s.number : s.number) + (s.randomMin ? ' · wachttijd ' + s.randomMin + '–' + s.randomMax + ' s' : '');
    $('#ring-arc').style.strokeDashoffset = 414.7 * (1 - frac);
    $('#ring-arc').style.stroke = s.phase === 'waiting' ? 'var(--brand-2)' : 'var(--ok)';
    $('#now-btn').style.visibility = s.phase === 'waiting' ? 'visible' : 'hidden';
  } else if (s.phase === 'done' && s.result && s.result !== 'stopped') {
    const key = s.result + s.message + s.attempt;
    const r = $('#result');
    r.textContent = (s.result === 'answered' ? '✓ ' : '') + s.message + (s.number ? ' — ' + (s.name || s.number) : '');
    r.className = 'result on ' + (s.result === 'answered' ? 'answered' : s.result === 'error' ? 'error' : 'other');
    if (lastResultShown && lastResultShown !== key) Android.vibrate(60);
    lastResultShown = key;
  }
  refreshTile(s);
}
function refreshTile(s){
  if (!s) { try { s = JSON.parse(Android.redialStatus()); } catch(e){ return; } }
  $('#tile-redial').classList.toggle('live', !!s.running);
  $('#tile-redial-sub').textContent = s.running ? 'Bezig: poging ' + (s.attempt||1) + (s.maxAttempts ? ' van ' + s.maxAttempts : '') : 'Blijft bellen tot er wordt opgenomen';
}

