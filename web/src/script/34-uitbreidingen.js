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
  if (!once('txnote')) return;
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

/* Spraaknotitie: inspreken → op de telefoon uitschrijven → notitie (of items/tekst erbij in de open notitie). */
let voiceFor = null, voiceTmr = null;
function voiceOpen(noteId){
  voiceFor = noteId;
  $('#voice-t').textContent = noteId ? 'Inspreken in deze notitie' : 'Spraaknotitie';
  $('#voice-state').textContent = 'Tik op de microfoon en praat. Tik nog eens als je klaar bent.';
  $('#voice-btn').classList.remove('rec'); $('#voice-btn').disabled = false;
  $('#voice').classList.add('on');
}
function voiceClose(){ clearInterval(voiceTmr); if (typeof Android.voiceCancel === 'function') Android.voiceCancel(); $('#voice').classList.remove('on'); }
function voiceToggle(){
  const b = $('#voice-btn');
  if (b.classList.contains('rec')) {
    clearInterval(voiceTmr); b.classList.remove('rec'); b.disabled = true;
    $('#voice-state').textContent = 'Uitschrijven…';
    Android.voiceStop(); return;
  }
  const e = Android.voiceStart();
  if (e === 'mic') { Android.musicRequestMic(); $('#voice-state').textContent = 'Geef toegang tot de microfoon en tik opnieuw.'; return; }
  if (e === 'model') { $('#voice-state').innerHTML = 'Download eerst een spraakmodel bij <a href="#" onclick="voiceClose();jumpTo(\'transcripts\');return false">Gesprekken uitschrijven</a>.'; return; }
  if (e) { $('#voice-state').textContent = e; return; }
  b.classList.add('rec'); Android.vibrate(15);
  const tick = () => { const ms = Android.voiceElapsed(); $('#voice-state').textContent = '● Luisteren… ' + Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0') + ' · tik om te stoppen'; };
  tick(); voiceTmr = setInterval(tick, 500);
}
window.onVoiceNote = function(r){
  if (!$('#voice').classList.contains('on')) return;
  if (r.error) { $('#voice-state').textContent = r.error; $('#voice-btn').disabled = false; return; }
  const text = String(r.text || '').trim();
  // "Melk, brood en kaas" in een lijstje → losse items
  const parts = text.replace(/[.!?]+$/, '').split(/\s*(?:,|;|\ben\b)\s*/i).map(x => x.trim()).filter(Boolean);
  const n = voiceFor ? noteById(voiceFor) : null;
  if (n) {
    if ((n.items || []).length || parts.length > 1 && parts.every(p => p.split(' ').length <= 5)) parts.forEach(p => n.items.push({ id: uid(), text: p.charAt(0).toUpperCase() + p.slice(1), done: false }));
    else n.text = (n.text ? n.text + '\n' : '') + text;
    touch(n); const ok = notesSaveNow(); voiceClose(); openNote(n.id); if (ok) toast('🎤 Toegevoegd');
    return;
  }
  const words = text.split(/\s+/);
  const nn = { id: uid(), title: words.slice(0, 6).join(' ').replace(/[.,;:!?]+$/, '') + (words.length > 6 ? '…' : ''), text: text, items: [], updated: Date.now() };
  notesGet().push(nn); const ok = notesSaveNow(); voiceClose(); openNote(nn.id); if (ok) toast('🎤 Spraaknotitie gemaakt');
};

/* Radiofragment bewaren: de laatste minuten uit de terugspoelbuffer als bestand in Radio/ (voor eigen gebruik). */
function rdClipMenu(){
  const max = ((rdState.shift || {}).clip) || 0;
  if (max < 10) { toast('Er is nog te weinig opgenomen'); return; }
  const opts = [5, 15, 30, 60].filter(m => m * 60 <= max + 59).map(m => ['Laatste ' + m + ' minuten', () => rdClip(m * 60)]);
  if (!opts.length || max < 5 * 60) opts.unshift(['Alles wat er is (' + Math.max(1, Math.round(max / 60)) + ' min)', () => rdClip(max)]);
  openSheet('Fragment bewaren', 'Komt in de map Radio in je backup-map. Alleen voor eigen gebruik.', opts);
}
function rdClip(sec){ toast('Bezig met bewaren…'); Android.radioSaveClip(Math.round(sec)); }
window.onRadioClip = function(r){ toast(r ? r : '💾 Fragment bewaard in Radio/'); };

/* Kluis: documenten versleuteld op de telefoon, open met vingerafdruk. */
function enterKluis(){ klRender(); }
function klRender(){
  let d; try { d = JSON.parse(Android.kluisList()); } catch(e){ d = { locked: true }; }
  const locked = !!d.locked || !!d.error;
  $('#kl-locked').style.display = locked ? 'block' : 'none';
  $('#kl-open').style.display = locked ? 'none' : 'block';
  if (d.error) $('#kl-msg').textContent = d.error;
  clearTimeout(klRender.t);
  if (locked) return;
  if (d.left) klRender.t = setTimeout(() => { if (current === 'kluis') klRender(); }, d.left + 500); // na 5 minuten vanzelf op slot, ook op het scherm
  const docs = (d.docs || []).slice().sort((a, b) => b.t - a.t);
  $('#kl-empty').style.display = docs.length ? 'none' : 'block';
  const ic = m => /pdf/.test(m) ? '📄' : /^image/.test(m) ? '🖼️' : '📎';
  $('#kl-list').innerHTML = docs.map(x => '<button class="trip" data-id="' + esc(x.id) + '" onclick="klMenu(this.dataset.id)"><span><b>' + ic(x.mime || '') + ' ' + esc(x.name) + '</b><small>' +
    esc(fmtB(x.size || 0)) + ' · ' + esc(new Date(x.t).toLocaleDateString('nl-NL', { day: 'numeric', month: 'short', year: 'numeric' })) + '</small></span><span aria-hidden="true">›</span></button>').join('');
  klRender.docs = docs;
}
function klUnlock(){ $('#kl-msg').textContent = ''; Android.kluisUnlock(); }
window.onKluis = function(r){ if (r !== true) $('#kl-msg').textContent = r; if (current === 'kluis') klRender(); };
window.onKluisChanged = function(r){ toast(r === 'ok' ? '🔒 Toegevoegd aan de kluis' : r); if (current === 'kluis') klRender(); };
function klLock(){ Android.kluisClose(); klRender(); }
function klMenu(id){
  const x = (klRender.docs || []).find(d => d.id === id); if (!x) return;
  openSheet(x.name, fmtB(x.size || 0), [
    ['Bekijken', () => Android.kluisView(id)],
    ['Naam wijzigen', () => askInput('Naam', '', x.name, 'Opslaan', v => { const e = Android.kluisRename(id, v); if (e) toast(e); klRender(); })],
    ['Verwijderen', () => askConfirm('Uit de kluis verwijderen?', '"' + x.name + '" wordt definitief gewist.', 'Verwijderen', () => { const e = Android.kluisDelete(id); toast(e || 'Verwijderd'); klRender(); })]]);
}
