/* ---------- Gesprekken uitschrijven ---------- */
let txPoll = null, txTmr = null, txCur = null, txRecs = [], txT = null, txLastRunning = false, txPlayPoll = null;
function txJson(s){ try { return JSON.parse(s); } catch(e){ return {error: 'Onverwachte fout'}; } }
function txInfo(){ return txJson(Android.txInfo()); }
function fmtMs(ms){ const s = Math.floor((ms||0)/1000); const h = Math.floor(s/3600), m = Math.floor(s%3600/60), x = s%60;
  return (h ? h + ':' + String(m).padStart(2,'0') : m) + ':' + String(x).padStart(2,'0'); }
function enterTranscripts(){
  const i = txInfo();
  $('#tx-unsup').style.display = i.supported ? 'none' : 'block';
  $('#tx-files').style.display = i.supported && !i.files ? 'block' : 'none';
  $('#tx-calls').style.display = i.files && !i.calls ? 'block' : 'none';
  $('#tx-lang').value = i.lang || 'nl';
  $('#tx-folder-note').innerHTML = i.folder ? 'Extra map: <b>' + esc(i.folder) + '</b> · <a href="#" onclick="Android.txClearFolder();enterTranscripts();return false">weghalen</a>' : '';
  $('#tx-models').innerHTML = (i.models || []).map(m =>
    '<div class="row mrow"><label style="flex:1"><input type=radio name=trm value="' + esc(m.id) + '"' + (i.model === m.id ? ' checked' : '') +
    (m.installed ? '' : ' disabled') + ' onchange="Android.txSetModel(this.value)"> ' + esc(m.label) + '</label>' +
    (m.installed ? '<button class="mini" onclick="txDelModel(' + jsq(m.id) + ')">Verwijderen</button>'
                 : '<button class="mini" onclick="txDownload(' + jsq(m.id) + ')">Downloaden</button>') + '</div>').join('');
  $('#tx-home').style.display = i.supported && i.files ? 'block' : 'none';
  $('#tx-search').value = ''; $('#tx-results').innerHTML = '';
  if (i.supported && i.files) { if (!txRecs.length) $('#tx-list').innerHTML = '<p class=note style="padding:14px">Opnames zoeken…</p>'; setTimeout(txLoad, 30); }
  txAutoRender();
  txPollOnce(); stopTrPoll(); txPoll = setInterval(txPollOnce, 700);
}
function stopTrPoll(){ if (txPoll) { clearInterval(txPoll); txPoll = null; } }
window.onTrChanged = function(){ if (current === 'transcripts') enterTranscripts(); };
function txDownload(id){ const e = Android.txDownload(id); if (e) toast(e); else { $('#tx-result').className = 'result'; txPollOnce(); } }
function txDelModel(id){ askConfirm('Spraakmodel verwijderen?', 'Je kunt het later opnieuw downloaden. Bestaande transcripten blijven bewaard.', 'Verwijderen', () => { Android.txDeleteModel(id); enterTranscripts(); }); }
const TXKIND = {in:'Inkomend', out:'Uitgaand', missed:'Gemist', rejected:'Geweigerd'};
function txLoad(){
  const d = txJson(Android.txList());
  const el = $('#tx-list');
  if (d.error) { el.innerHTML = '<p class=note style="padding:14px">' + esc(d.error) + '</p>'; return; }
  txRecs = d.recordings || [];
  const todo = txRecs.filter(r => !r.done && !r.gone);
  $('#tx-all').textContent = todo.length ? 'Alles uitschrijven (' + todo.length + ')' : 'Alles uitgeschreven';
  $('#tx-all').disabled = !todo.length;
  el.innerHTML = txRecs.map((r, i) =>
    '<button onclick="txOpenIdx(' + i + ')"><span class=r1><b>' + esc(r.name || r.file) + '</b><time>' + esc(fmtD(r.mtime)) + '</time></span>' +
    '<span class=r2>' + (r.kind ? esc(TXKIND[r.kind] || '') + ' · ' : '') + (r.duration ? fmtMs(r.duration) + ' · ' : '') +
    (r.done ? (r.gone ? 'opname verwijderd · ' : '') + '<b class=ok>uitgeschreven</b>' + (r.preview ? ' — ' + esc(r.preview) : '') : 'nog niet uitgeschreven') +
    '</span></button>').join('') ||
    '<p class=note style="padding:14px">Geen gespreksopnames gevonden. Zie hieronder hoe je opnemen aanzet.</p>';
}
function txOpenIdx(i){
  const r = txRecs[i]; if (!r) return;
  if (r.done) { txOpen(r.id); return; }
  askConfirm('Gesprek uitschrijven?', (r.name ? r.name + ' · ' : '') + fmtMs(r.duration) + '. Dit duurt even en gaat door als je de app verlaat.', 'Uitschrijven', () => txStart([r.path]));
}
function txStart(paths){
  const e = Android.txStart(JSON.stringify(paths));
  if (e) { toast(e); return; }
  $('#tx-result').className = 'result'; txPollOnce();
}
function txStartAll(){
  const todo = txRecs.filter(r => !r.done && !r.gone);
  if (!todo.length) return;
  const total = todo.reduce((a, r) => a + (r.duration || 0), 0);
  askConfirm(todo.length + ' gesprekken uitschrijven?', 'Samen ' + fmtMs(total) + ' aan opnames. Dit kan een tijd duren; het gaat door als je de app verlaat. Laat de telefoon liefst aan de lader.', 'Starten', () => txStart(todo.map(r => r.path)));
}
function txPollOnce(){
  const s = txJson(Android.txStatus());
  $('#tx-run').style.display = s.running ? 'block' : 'none';
  $('#tx-setup').style.display = s.running && s.phase === 'download' ? 'none' : 'block';
  if (s.running) {
    $('#tx-run-title').textContent = s.phase === 'download' ? 'Spraakmodel downloaden' : s.phase === 'decode' ? 'Opname voorbereiden' : 'Uitschrijven';
    $('#tx-run-file').textContent = s.file || '';
    $('#tx-bar').style.width = (s.pct || 0) + '%';
    $('#tx-run-sub').textContent = (s.pct || 0) + '%' + (s.left ? ' · nog ' + s.left + ' in de wachtrij' : '');
  } else if (txLastRunning) {
    txLastRunning = false;
    const r = $('#tx-result');
    if (s.error || s.failed) { r.textContent = (s.done ? s.done + ' gelukt, ' : '') + (s.failed || 1) + ' mislukt: ' + (s.error || ''); r.className = 'result on error'; }
    else if (s.stopped) { r.textContent = 'Gestopt'; r.className = 'result on other'; }
    else { r.textContent = s.done ? '✓ ' + s.done + (s.done === 1 ? ' gesprek' : ' gesprekken') + ' uitgeschreven' : '✓ Spraakmodel klaar'; r.className = 'result on answered'; }
    enterTranscripts();
  }
  txLastRunning = !!s.running;
  const t = $('#tile-tx-sub');
  if (t) t.textContent = s.running ? 'Bezig met uitschrijven…' : 'Opnames omzetten naar tekst';
  $('#tile-tr').classList.toggle('live', !!s.running);
}
function txSearchDo(){
  clearTimeout(txTmr);
  txTmr = setTimeout(() => {
    const q = $('#tx-search').value.trim();
    const box = $('#tx-results');
    $('#tx-list').style.display = q.length >= 2 ? 'none' : 'block';
    $('#tx-actions').style.display = q.length >= 2 ? 'none' : 'flex';
    if (q.length < 2) { box.innerHTML = ''; return; }
    const d = txJson(Android.txSearch(q));
    const ql = q.toLowerCase();
    box.innerHTML = '<div class="clist card">' + ((d.results || []).map(x =>
      '<button data-id="' + esc(x.id) + '" data-ms="' + (+x.from || 0) + '" onclick="txOpen(this.dataset.id, +this.dataset.ms)"><span class=r1><b>' + esc(x.name) + '</b><time>' + esc(fmtD(x.mtime)) + ' · ' + fmtMs(x.from) + '</time></span>' +
      '<span class=r2 style="white-space:normal">' + hl(x.text, ql) + '</span></button>').join('') ||
      '<p class=note style="padding:14px">Niets gevonden.</p>') + '</div>';
  }, 300);
}
function txOpen(id, ms){
  const t = txJson(Android.txGet(id));
  if (t.error) { toast(t.error); return; }
  txCur = id; txT = t;
  $('#txd-title').textContent = t.name || t.file;
  const when = t.callDate || t.mtime;
  $('#txd-sub').textContent = new Date(when).toLocaleString('nl-NL', {weekday:'long', day:'numeric', month:'long', hour:'2-digit', minute:'2-digit'}) +
    ' · ' + fmtMs(t.duration) + (t.kind ? ' · ' + (TXKIND[t.kind] || '') : '') + (t.number && t.number !== t.name ? ' · ' + t.number : '');
  $('#txd-segs').innerHTML = (t.segments || []).map((s, i) =>
    '<p class=seg id="seg' + i + '" data-ms="' + (+s.from || 0) + '" onclick="txSegTap(this)"><time>' + fmtMs(s.from) + '</time>' + esc(s.text) + '</p>').join('') ||
    '<p class=note>Er is geen spraak herkend in deze opname.</p>';
  txSelEnd();
  show('txd');
  if (ms != null) { const i = (t.segments || []).findIndex(s => s.from >= ms); const el = $('#seg' + Math.max(0, i)); if (el) { el.classList.add('now'); el.scrollIntoView({block:'center'}); } }
}
function txSeek(ms){ const e = Android.txPlay(txCur, String(ms)); if (e) toast(e); else txWatch(); }
function txToggle(){
  const st = txJson(Android.txPlayState());
  if (st.playing) { Android.txPause(); txWatch(); return; }
  txSeek(st.dur && st.pos >= st.dur - 500 ? 0 : (st.pos || 0));
}
function txWatch(){
  if (txPlayPoll) clearInterval(txPlayPoll);
  const tick = () => {
    const st = txJson(Android.txPlayState());
    $('#txd-play').textContent = st.playing ? '❚❚ Pauze' : '▶ Afspelen';
    const segs = (txT && txT.segments) || [];
    let cur = -1; for (let i = 0; i < segs.length; i++) if (segs[i].from <= st.pos) cur = i;
    document.querySelectorAll('#txd-segs .seg.now').forEach(e => { if (e.id !== 'seg' + cur) e.classList.remove('now'); });
    const el = $('#seg' + cur);
    if (el && !el.classList.contains('now') && st.playing) { el.classList.add('now'); el.scrollIntoView({block:'center', behavior:'smooth'}); }
    if (!st.playing) { clearInterval(txPlayPoll); txPlayPoll = null; }
  };
  tick(); txPlayPoll = setInterval(tick, 400);
}
function leaveTrd(){ if (txPlayPoll) { clearInterval(txPlayPoll); txPlayPoll = null; } Android.txStop(); $('#txd-play').textContent = '▶ Afspelen'; }
function txExport(id){
  if (!plainOk(id ? 'Het transcript' : 'Elk transcript', () => txExport(id))) return;
  const r = Android.txExport(id || '');
  toast(r.startsWith('ok:') ? '✓ ' + r.slice(3) + (r.slice(3) === '1' ? ' transcript' : ' transcripten') + ' opgeslagen in de map Gesprekken' : r);
}
function txDel(){
  askConfirm('Transcript verwijderen?', 'De opname zelf blijft staan; je kunt hem later opnieuw uitschrijven.', 'Verwijderen', () => { Android.txDelete(txCur); show('transcripts'); });
}
function txRedo(){
  if (!txT || !txT.path) return;
  askConfirm('Opnieuw uitschrijven?', 'Handig na het kiezen van een ander spraakmodel of andere taal. Het huidige transcript wordt vervangen.', 'Uitschrijven', () => { show('transcripts'); txStart([txT.path]); });
}

