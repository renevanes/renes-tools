/* ---------- Uitbreidingen (versie 1.53+) ---------- */

/* Gesprek → notitie: zinnen uit een transcriptie als notitie of lijstje bewaren. */
let txSel = false;
function txSegTap(el){
  if (!txSel) { txSeek(+el.dataset.ms); return; }
  el.classList.toggle('sel'); el.setAttribute('aria-pressed', el.classList.contains('sel') ? 'true' : 'false');
  const n = document.querySelectorAll('#txd-segs .seg.sel').length;
  $('#txd-selinfo').textContent = n ? n + (n === 1 ? ' zin gekozen' : ' zinnen gekozen') : 'Tik op de zinnen die je wilt bewaren (of kies niets voor het hele gesprek).';
}
function txSelStart(){
  txSel = true; $('#txd-tonote').style.display = 'none'; $('#txd-selbar').style.display = 'block';
  $('#txd-segs').classList.add('selecting');
  $('#txd-selinfo').textContent = 'Tik op de zinnen die je wilt bewaren (of kies niets voor het hele gesprek).';
}
function txSelEnd(){
  txSel = false; const b = $('#txd-tonote'); if (b) b.style.display = '';
  const bar = $('#txd-selbar'); if (bar) bar.style.display = 'none';
  const segs = $('#txd-segs'); if (segs) { segs.classList.remove('selecting'); segs.querySelectorAll('.seg.sel').forEach(x => { x.classList.remove('sel'); x.removeAttribute('aria-pressed'); }); }
}
function txToNote(asList){
  const t = txT || {}; const segs = t.segments || [];
  let picked = Array.from(document.querySelectorAll('#txd-segs .seg.sel')).map(el => segs[+el.id.slice(3)]).filter(Boolean);
  if (!picked.length) picked = segs;
  if (!picked.length) { toast('Er is geen tekst om te bewaren'); return; }
  const when = new Date(t.callDate || t.mtime || Date.now()).toLocaleDateString('nl-NL', { day: 'numeric', month: 'short' });
  const n = { id: uid(), title: ('Gesprek' + (t.name ? ' met ' + t.name : '') + ' · ' + when).slice(0, 80), text: '', items: [], updated: Date.now(), tx: txCur };
  if (asList) picked.forEach(s => n.items.push({ id: uid(), text: s.text.trim(), done: false }));
  else n.text = picked.map(s => s.text.trim()).join('\n');
  notesGet().push(n); notesSaveNow();
  txSelEnd();
  undoToast('📝 Notitie "' + n.title + '" gemaakt', () => { notes = notesGet().filter(x => x.id !== n.id); notesSaveNow(); });
}
