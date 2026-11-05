/* ---------- WhatsApp backup ---------- */
const WA_CATS = [['chats','Chats','backup van al je berichten'],['images',"Foto's"],['video',"Video's"],['voice','Spraakberichten'],
  ['audio','Audio'],['documents','Documenten'],['stickers',"Stickers en GIF's"],['other','Overig','profielfoto\'s, achtergronden'],['statuses','Statussen','van anderen, verdwijnen na 24 uur']];
const WA_ALL = ['chats','images','video','voice','audio','documents','stickers','other'];
/* Het overzicht en zijn eigen schermen (Leesbare chats is het scherm chats) */
const WA_SCR = ['wa', 'wasel', 'waauto', 'warestore'];
let waPoll = null, waInfoC = {}, waSizesC = null, waSel = new Set(), waLastKey = '', waWasRunning = false;
function fmtB(b){ return fmtSize(b); } // opslag: Regio en taal (1024 of 1000 per stap)
function fmtD(ms){ const d = new Date(ms);
  const hm = fmtTime(d, {hour:'2-digit', minute:'2-digit'});
  if (rgIsToday(d)) return t('vandaag {tijd}', { tijd: hm });
  if (rgIsYesterday(d)) return t('gisteren {tijd}', { tijd: hm });
  return fmtDate(d, {day:'numeric', month:'short'}) + ' ' + hm; }
function waInfo(){ try { return JSON.parse(Android.waInfo()); } catch(e){ return {}; } }
function enterWa(name){ renderWa(!name || name === 'wa' || name === 'wasel'); stopWaPoll(); waPoll = setInterval(waPollOnce, 700); waPollOnce(); }
function stopWaPoll(){ if (waPoll) { clearInterval(waPoll); waPoll = null; } }
window.onWaChanged = function(){ renderWa(true); };
window.onWaSizes = function(sz){ waSizesC = sz; renderWaCats(); };
function renderWa(rescan){
  uncache('waInfo');
  const i = waInfoC = waInfo();
  waSel = new Set((i.sel || '').split(',').filter(Boolean));
  $('#wa-perm').style.display = i.filesAccess ? 'none' : 'block';
  $('#wa-nosrc').style.display = i.filesAccess && !(i.sources||[]).length ? 'block' : 'none';
  $('#wa-dest').textContent = i.dest ? (i.destName || 'Gekozen map') : 'Nog niet gekozen';
  $('#wa-dest-btn').textContent = i.dest ? 'Andere map' : 'Kies map';
  $('#wa-last').textContent = i.lastOk ? fmtD(i.lastOk) : t('Nog niet gemaakt');
  $('#wa-sum-auto').textContent = i.auto ? t((i.autoMode || 'all') === 'selection' ? 'Elke nacht · mijn selectie' : 'Elke nacht · alles') + (i.autoCharging !== false ? ' · ' + t('tijdens opladen') : '') : t('Uit');
  $('#wa-auto').classList.toggle('on', !!i.auto);
  $('#wa-charging').classList.toggle('on', i.autoCharging !== false);
  const am = $('#wa-automode'); am.innerHTML = '';
  [['all','Alles'],['selection','Mijn selectie']].forEach(([v,l])=>{ const b=document.createElement('button'); b.textContent=l; b.classList.toggle('on', (i.autoMode||'all')===v);
    b.onclick=()=>{ waInfoC.autoMode=v; waSave(); renderWa(false); }; am.appendChild(b); });
  let nx = '';
  if (i.auto) {
    if (!i.filesAccess || !i.dest) nx = 'Automatisch staat aan, maar er ontbreekt nog toegang of een backup-map.';
    else if (i.nextAuto && i.nextAuto > Date.now() + 60000) nx = t(i.autoCharging !== false ? 'Volgende automatische backup: {wanneer}, als de telefoon aan de lader ligt.' : 'Volgende automatische backup: {wanneer}.', { wanneer: fmtD(i.nextAuto).replace(/ 0?3:00$/, ' ' + t('na 03:00')) });
    else nx = t(i.autoCharging !== false ? 'De volgende automatische backup start zo snel mogelijk als de telefoon aan de lader ligt.' : 'De volgende automatische backup start zo snel mogelijk.');
  }
  $('#wa-next').textContent = nx;
  const h = (i.history || []).slice(0, 5);
  $('#wa-hist-card').style.display = h.length ? 'block' : 'none';
  const ML = {all:'Alles', selection:'Selectie', auto:'Automatisch', restore:'Terugzetten', readable:'Leesbaar maken'};
  $('#wa-hist').innerHTML = h.map(x => '<div class="hist"><b>' + esc(t(ML[x.mode] || x.mode)) + '</b> · ' + esc(fmtD(x.finishedAt)) +
    '<small>' + (x.result === 'ok' ? '✓ ' : x.result === 'error' ? '✗ ' : '') + esc(x.message || '') + (x.bytesDone > 0 ? ' · ' + fmtB(x.bytesDone) : '') + '</small></div>').join('');
  renderWaCats();
  renderReadable();
  if (rescan && i.filesAccess && (i.sources||[]).length) Android.waScanSizes();
}
function renderWaCats(){
  const el = $('#wa-cats'); el.innerHTML = '';
  let all = 0, allF = 0;
  WA_CATS.forEach(([k, label, sub]) => {
    const z = waSizesC && waSizesC[k];
    if (z && WA_ALL.includes(k)) { all += z.bytes; allF += z.files; }
    const b = document.createElement('button'); b.className = 'cat' + (waSel.has(k) ? ' on' : '');
    b.innerHTML = '<i class="cb"></i><span>' + esc(t(label)) + (sub ? '<br><small style="white-space:normal">' + esc(t(sub)) + '</small>' : '') + '</span><small>' + (z ? fmtB(z.bytes) : '') + '</small>';
    b.onclick = () => { waSel.has(k) ? waSel.delete(k) : waSel.add(k); Android.vibrate(10); waSave(); renderWaCats(); };
    el.appendChild(b);
  });
  $('#wa-sum-sel').textContent = t('{n} van {totaal}', { n: WA_CATS.filter(c => waSel.has(c[0])).length, totaal: WA_CATS.length });
  $('#wa-all-size').textContent = t('Chats en alle media, zonder statussen') + (waSizesC ? ' · ' + t('{grootte} in {n} bestanden', { grootte: fmtB(all), n: fmtNum(allF) }) : '');
}
function waSave(){
  const sel = WA_CATS.map(c=>c[0]).filter(k=>waSel.has(k)).join(',');
  Android.waSaveSettings($('#wa-auto').classList.contains('on'), $('#wa-charging').classList.contains('on'), waInfoC.autoMode || 'all', sel);
  setTimeout(()=>renderWa(false), 50);
}
function waStart(mode){
  const cats = mode === 'all' ? WA_ALL.join(',') : WA_CATS.map(c=>c[0]).filter(k=>waSel.has(k)).join(',');
  const err = Android.waStart(mode, cats);
  if (err) { toast(err); if (err.indexOf('map') >= 0 && !waInfoC.dest) Android.waPickFolder(); return; }
  waLastKey = ''; $('#wa-result').className = 'result'; Android.vibrate(25); waToHub(); setTimeout(waPollOnce, 200);
}
/* De voortgang staat op het overzicht: vanaf Selectie of Terugzetten daarheen */
function waToHub(){ if (current !== 'wa' && WA_SCR.includes(current)) show('wa'); }
function waRestore(){
  askConfirm('Backup terugzetten?', 'Ontbrekende en afwijkende bestanden worden naar de WhatsApp-map op deze telefoon gezet. Doe dit voordat je WhatsApp installeert of opnieuw instelt.', 'Terugzetten', () => {
    const err = Android.waRestore();
    if (err) { toast(err); return; }
    waLastKey = ''; $('#wa-result').className = 'result'; waToHub(); setTimeout(waPollOnce, 200);
  });
}
function waPollOnce(){
  let s; try { s = JSON.parse(Android.waStatus()); } catch(e){ return; }
  const run = !!s.running;
  if (waWasRunning && !run) renderWa(true);
  waWasRunning = run;
  $('#wa-run').style.display = run ? 'block' : 'none';
  $('#wa-setup').style.display = run ? 'none' : 'block';
  if (run) {
    $('#wa-run-title').textContent = s.mode === 'restore' ? 'Terugzetten bezig' : s.mode === 'auto' ? 'Automatische backup bezig' : 'Backup bezig';
    const ph = {scan:'Bestanden zoeken…', compare:'Vergelijken met de backup…', copy:'Kopiëren…'}[s.phase] || '';
    $('#wa-run-phase').textContent = ph;
    const pct = s.phase === 'copy' && s.bytesTotal > 0 ? 100 * s.bytesDone / s.bytesTotal : 0;
    $('#wa-bar').style.width = pct.toFixed(1) + '%';
    $('#wa-run-count').textContent = s.phase === 'copy' ? t('{n} van {totaal} bestanden · {grootte} van {totaalgrootte}', { n: fmtNum(s.filesDone||0), totaal: fmtNum(s.filesTotal||0), grootte: fmtB(s.bytesDone||0), totaalgrootte: fmtB(s.bytesTotal||0) }) : '';
    $('#wa-run-cur').textContent = s.current || '';
  } else if (s.phase === 'done' && s.finishedAt) {
    const key = s.finishedAt + s.result;
    const r = $('#wa-result');
    const T = {ok:'✓ Klaar: ', partial:'Klaar met fouten: ', cancelled:'', error:'Mislukt: '};
    r.textContent = t(T[s.result] || '') + t(s.message || '') + (s.bytesDone > 0 ? ' (' + fmtB(s.bytesDone) + ')' : '');
    r.className = 'result on ' + (s.result === 'ok' ? 'answered' : s.result === 'error' ? 'error' : 'other');
    if (waLastKey && waLastKey !== key) Android.vibrate(60);
    waLastKey = key;
  }
  refreshWaTile(s);
}
function refreshWaTile(s){
  if (!s) { try { s = JSON.parse(Android.waStatus()); } catch(e){ return; } }
  $('#tile-wa').classList.toggle('live', !!s.running);
  let sub = 'Chats en media veilig bewaren';
  if (s.running) sub = s.mode === 'restore' ? t('Bezig met terugzetten') : t('Bezig met backup') + (s.phase === 'copy' && s.bytesTotal ? ' · ' + Math.round(100*s.bytesDone/s.bytesTotal) + '%' : '');
  else { const i = cached('waInfo', 30000, waInfo); if (i.lastOk) sub = t('Laatste backup: {wanneer}', { wanneer: fmtD(i.lastOk) }); }
  $('#tile-wa-sub').textContent = sub;
}

