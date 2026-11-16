/* ---------- Uitbreidingen (versie 1.53+) ---------- */

/* Gesprek → notitie: zinnen uit een transcriptie als notitie of lijstje bewaren. */
let txSel = false;
function txSegTap(el){
  if (!txSel) { txSeek(+el.dataset.ms); return; }
  el.classList.toggle('sel'); el.setAttribute('aria-pressed', el.classList.contains('sel') ? 'true' : 'false');
  const n = document.querySelectorAll('#txd-segs .seg.sel').length;
  $('#txd-selinfo').textContent = n ? tn(n, '{n} zin gekozen', '{n} zinnen gekozen') : t('Tik op de zinnen die je wilt bewaren (of kies niets voor het hele gesprek).');
}
function txSelStart(){
  txSel = true; $('#txd-tonote').style.display = 'none'; $('#txd-selbar').style.display = 'block'; if ($('#txd-sum')) $('#txd-sum').style.display = 'none';
  $('#txd-segs').classList.add('selecting');
  $('#txd-selinfo').textContent = 'Tik op de zinnen die je wilt bewaren (of kies niets voor het hele gesprek).';
}
function txSelEnd(){
  txSel = false; const b = $('#txd-tonote'); if (b) b.style.display = ''; if ($('#txd-sum')) $('#txd-sum').style.display = '';
  const bar = $('#txd-selbar'); if (bar) bar.style.display = 'none';
  const segs = $('#txd-segs'); if (segs) { segs.classList.remove('selecting'); segs.querySelectorAll('.seg.sel').forEach(x => { x.classList.remove('sel'); x.removeAttribute('aria-pressed'); }); }
}
function txToNote(asList){
  if (!once('txnote')) return;
  const tr = txT || {}; const segs = tr.segments || [];
  let picked = Array.from(document.querySelectorAll('#txd-segs .seg.sel')).map(el => segs[+el.id.slice(3)]).filter(Boolean);
  if (!picked.length) picked = segs;
  if (!picked.length) { toast('Er is geen tekst om te bewaren'); return; }
  const when = fmtDate(tr.callDate || tr.mtime || Date.now(), { day: 'numeric', month: 'short' });
  const n = { id: uid(), kind: asList ? 'list' : 'note', title: ((tr.name ? t('Gesprek met {naam}', { naam: tr.name }) : t('Gesprek')) + ' · ' + when).slice(0, 80), text: '', items: [], updated: Date.now(), tx: txCur };
  if (asList) picked.forEach(s => n.items.push({ id: uid(), text: s.text.trim(), done: false }));
  else n.text = picked.map(s => s.text.trim()).join('\n');
  notesGet().push(n); notesSaveNow();
  txSelEnd();
  undoToast(t('Notitie "{titel}" gemaakt', { titel: n.title }), () => { notes = notesGet().filter(x => x.id !== n.id); notesSaveNow(); });
}

/* Spraaknotitie: inspreken → op de telefoon uitschrijven → notitie (of items/tekst erbij in de open notitie). */
let voiceFor = null, voiceTmr = null, voiceOpt = null;
/* opt (bijv. Check-in): {title, done(tekst), models: zonder spraakmodel naar Modellen verwijzen} */
function voiceOpen(noteId, opt){
  if (isPlay()) { toast(t('Deze tool zit niet in de Play-versie')); return; }
  voiceFor = noteId; voiceOpt = opt || null;
  $('#voice-t').textContent = voiceOpt && voiceOpt.title ? voiceOpt.title : noteId ? 'Inspreken in deze notitie' : 'Spraaknotitie';
  $('#voice-state').textContent = 'Tik op de microfoon en praat. Tik nog eens als je klaar bent.';
  $('#voice-btn').classList.remove('rec'); $('#voice-btn').disabled = false;
  $('#voice').classList.add('on');
}
function voiceClose(){ clearInterval(voiceTmr); if (typeof Android.voiceCancel === 'function') Android.voiceCancel(); $('#voice').classList.remove('on'); voiceOpt = null; }
function voiceToggle(){
  const b = $('#voice-btn');
  if (b.classList.contains('rec')) {
    clearInterval(voiceTmr); b.classList.remove('rec'); b.disabled = true;
    uiWachtTekst($('#voice-state'), t('Uitschrijven…'));
    Android.voiceStop(); return;
  }
  const e = Android.voiceStart();
  if (e === 'mic') { Android.musicRequestMic(); $('#voice-state').textContent = 'Geef toegang tot de microfoon en tik opnieuw.'; return; }
  if (e === 'model' && voiceOpt && voiceOpt.models) { $('#voice-state').innerHTML = 'Er is nog geen spraakmodel. Download er een bij <a href="#" onclick="voiceClose();if(typeof ciAnsClose===\'function\')ciAnsClose();if(typeof ciRemPark===\'function\')ciRemPark();jumpTo(\'models\');return false">Modellen</a> (Spraak naar tekst).'; return; }
  if (e === 'model') { $('#voice-state').innerHTML = 'Download eerst een spraakmodel bij <a href="#" onclick="voiceClose();jumpTo(\'transcripts\');return false">Gesprekken uitschrijven</a>.'; return; }
  if (e) { $('#voice-state').textContent = e; return; }
  b.classList.add('rec'); Android.vibrate(15);
  const tick = () => { const ms = Android.voiceElapsed(); $('#voice-state').textContent = t('● Luisteren… {tijd} · tik om te stoppen', { tijd: Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0') }); };
  tick(); voiceTmr = setInterval(tick, 500);
}
window.onVoiceNote = function(r){
  if (!$('#voice').classList.contains('on')) return;
  if (r.error) { $('#voice-state').textContent = r.error; $('#voice-btn').disabled = false; return; }
  const text = String(r.text || '').trim();
  if (voiceOpt && voiceOpt.done) { const d = voiceOpt.done; voiceClose(); d(text); return; }
  // "Melk, brood en kaas" in een lijstje → losse items
  const parts = text.replace(/[.!?]+$/, '').split(/\s*(?:,|;|\ben\b)\s*/i).map(x => x.trim()).filter(Boolean);
  const n = voiceFor ? noteById(voiceFor) : null;
  if (n) {
    if ((n.items || []).length || parts.length > 1 && parts.every(p => p.split(' ').length <= 5)) parts.forEach(p => n.items.push({ id: uid(), text: p.charAt(0).toUpperCase() + p.slice(1), done: false }));
    else n.text = (n.text ? n.text + '\n' : '') + text;
    touch(n); const ok = notesSaveNow(); voiceClose(); openNote(n.id); if (ok) toast('Toegevoegd');
    return;
  }
  const words = text.split(/\s+/);
  const nn = { id: uid(), kind: 'note', title: words.slice(0, 6).join(' ').replace(/[.,;:!?]+$/, '') + (words.length > 6 ? '…' : ''), text: text, items: [], updated: Date.now() };
  notesGet().push(nn); const ok = notesSaveNow(); voiceClose(); openNote(nn.id); if (ok) toast('Spraaknotitie gemaakt');
};

/* Radiofragment bewaren: de laatste minuten uit de terugspoelbuffer als bestand in Radio/ (voor eigen gebruik). */
function rdClipMenu(){
  const max = ((rdState.shift || {}).clip) || 0;
  if (max < 10) { toast('Er is nog te weinig opgenomen'); return; }
  const opts = [5, 15, 30, 60].filter(m => m * 60 <= max + 59).map(m => [t('Laatste {n} minuten', { n: m }), () => rdClip(m * 60)]);
  if (!opts.length || max < 5 * 60) opts.unshift([t('Alles wat er is ({n} min)', { n: Math.max(1, Math.round(max / 60)) }), () => rdClip(max)]);
  openSheet('Fragment bewaren', 'Komt in de map Radio in je backup-map. Alleen voor eigen gebruik.', opts);
}
function rdClip(sec){ uiBezig(t('Bezig met bewaren…'), 'onRadioClip'); Android.radioSaveClip(Math.round(sec)); }
window.onRadioClip = function(r){ toast(r ? r : 'Fragment bewaard in Radio/'); };

/* Kluis: documenten versleuteld op de telefoon. Om ontgrendelen vraagt alleen het slotscherm van Extra ontgrendelen (26b);
 * de kluis zelf heeft geen slotscherm. */
function enterKluis(){ klRender(); }
/** Open zonder vraag als dat mag: Java opent vóór het antwoord (net ontgrendeld op het slotscherm, of extra
 *  ontgrendelen uit); anders onKluis('vraag'). Niet onder het app-slot of een slotscherm. */
function klVanzelf(){ if (current !== 'kluis' || !tsVanzelfMag()) return false; Android.kluisUnlock(); return true; }
function klLijst(){ try { return JSON.parse(Android.kluisList()); } catch(e){ return { locked: true }; } }
function klMsg(text){ const e = $('#kl-msg'); e.textContent = text || ''; e.hidden = !text; }
// Na het app-slot of het slotscherm (dat ook over de kluis kan komen als je de app even verliet, of dat van een andere tool
// waar je toch niet heen ging): open als hij intussen dicht ging
(function(){
  for (const naam of ['onUnlocked', 'onToolSlotVrij']) { const prev = window[naam]; window[naam] = function(){ if (prev) prev.apply(this, arguments); if (current === 'kluis' && tsVanzelfMag()) klRender(); }; }
  const slot = window.onToolSlot; window.onToolSlot = function(r){ if (slot) slot.apply(this, arguments); if (r && r.soort === 'open' && r.ok && current === 'kluis' && tsVanzelfMag()) klRender(); };
})();
/* Zoeken in de tekst van documenten: Java leest de tekst op de achtergrond (alleen met de kluis open) en zoekt erin.
   klT.q/hits: het laatste antwoord; een nieuwe vraag gaat pas weg als je even stopt met typen. */
const klT = { q: '', hits: {}, zonder: 0, vraag: '', timer: 0, gestart: false, bezig: null };
function klTekstWis(){ clearTimeout(klT.timer); klT.q = klT.vraag = ''; klT.hits = {}; klT.zonder = 0; klT.gestart = false; klT.bezig = null; const e = $('#kl-tekst'); if (e) e.textContent = ''; }
/* Weg van het kluisscherm (of de app uit beeld): namen en stukjes tekst blijven niet in de pagina staan */
function klVergeet(){ klTekstWis(); if (typeof klOrganizeClose === 'function') klOrganizeClose(); const l = $('#kl-list'); if (l) l.replaceChildren(); klRender.docs = []; const m = $('#kl-mappen'); if (m) m.replaceChildren(); const pd = $('#kl-pad'); if (pd) pd.replaceChildren(); const z = $('#kl-search'); if (z) z.value = ''; }
(function(){ const vorige = window.onPauseApp; window.onPauseApp = function(){ if (vorige) vorige(); if (klT.q || Object.keys(klT.hits).length) { const g = klT.gestart; klTekstWis(); klT.gestart = g; if (current === 'kluis') klRender.zonderVraag = true, klRender(), klRender.zonderVraag = false; } }; })(); // de stukjes weg, de zoekvraag blijft: bij terugkomen zoekt de kluis opnieuw
function klTekstVraag(q){
  if (typeof Android.kluisZoek !== 'function') return;
  if (q.replace(/\s/g, '').length < 3) { clearTimeout(klT.timer); klT.vraag = ''; if (klT.q) { klT.q = ''; klT.hits = {}; } return; }
  if (q === klT.vraag) return;
  klT.vraag = q; clearTimeout(klT.timer);
  klT.timer = setTimeout(() => { if (current === 'kluis' && klT.vraag === q) Android.kluisZoek(q); }, 350);
}
function klTekstStand(){
  const e = $('#kl-tekst'); if (!e) return;
  const b = klT.bezig;
  if (b && b.bezig && b.totaal) uiWachtTekst(e, t('Tekst lezen om in te zoeken: {k} van {n}…', { k: Math.min(b.klaar + 1, b.totaal), n: b.totaal }));
  else e.textContent = klT.q && klT.zonder ? tn(klT.zonder, 'Van één document is de tekst nog niet gelezen', 'Van {n} documenten is de tekst nog niet gelezen') : '';
}
window.onKluisZoek = function(r){
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { r = {}; } }
  if (!r || r.locked || r.error || current !== 'kluis') return;
  if ((r.q || '').trim().toLowerCase() !== $('#kl-search').value.trim().toLowerCase()) return; // intussen iets anders getypt
  klT.q = (r.q || '').trim().toLowerCase(); klT.hits = r.hits || {}; klT.zonder = r.zonder || 0;
  klRender();
};
window.onKluisTekst = function(r){
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { r = {}; } }
  klT.bezig = r || null;
  if (current !== 'kluis') return;
  klTekstStand();
  if (r && !r.bezig && r.klaar && klT.vraag) { const q = klT.vraag; klT.vraag = ''; klTekstVraag(q); } // nieuwe tekst: de vraag nog eens stellen
};
function klRender(){
  let d = klLijst();
  if (d.locked && !d.error && klVanzelf()) d = klLijst();
  const locked = !!d.locked || !!d.error;
  $('#kl-open').style.display = locked ? 'none' : 'block';
  if (d.error) klMsg(d.error); else if (!locked) klMsg('');
  clearTimeout(klRender.t);
  if (locked) { if (klEditId) toast(t('De kluis is op slot gegaan; wat je nog niet bewaard had, is niet opgeslagen')); klTekstWis(); $('#kl-list').replaceChildren();klRender.docs=[];klOrganizeClose();$('#kl-search').value='';klMap='all';$('#kl-mappen').replaceChildren();$('#kl-pad').replaceChildren();$('#kl-maphernoem').hidden=true;return; }
  $('#kl-lock').hidden = tsEigenUit('kluis'); // uit: hij opent toch meteen weer
  if (d.left) klRender.t = setTimeout(() => { if (current === 'kluis') klRender(); }, d.left + 500); // na 5 minuten vanzelf op slot, ook op het scherm
  const docs=(d.docs||[]).slice();
  if (!klT.gestart && typeof Android.kluisTekstBijwerken === 'function') { klT.gestart = true; Android.kluisTekstBijwerken(); }
  klRender.docs=docs;
  klTeken(docs); // mappen, lijst en telling: 49-documenten-ordenen.js
  klTekstStand();
}
window.onKluis = function(r){
  if (r === 'vraag') { klVraag(); return; }
  if (r !== true) klMsg(r);
  if (current === 'kluis') klRender();
};
/** De kluis kan nu niet open zonder ontgrendeling: het slotscherm, over de kluis of over wat de PDF-tools willen bewaren. */
function klVraag(){
  const geen = t('Stel eerst een schermvergrendeling in op je telefoon (pincode, patroon of vingerafdruk)');
  const afbreken = (reden) => {
    if (typeof kc !== 'undefined' && kc.wacht) { kc.wacht = false; clearTimeout(kc.wachtT); kcMislukt(reden); }
    if (typeof klDan !== 'undefined' && klDan) { klDan = null; toast(t(reden)); }
  };
  // Op het kluisscherm zelf: terug verlaat de kluis. Elders (PDF bewaren of bekijken): terug maakt dat ongedaan.
  // Alleen als het slotscherm niet kan (geen schermvergrendeling) is dát de reden; terug op het slotscherm is annuleren.
  const terug = () => afbreken('Kluis niet ontgrendeld');
  if (!tsVraag('kluis', () => Android.kluisUnlock(), current === 'kluis' ? null : terug)) { if (current === 'kluis') klMsg(geen); else afbreken('Stel eerst een schermvergrendeling in op je telefoon (pincode, patroon of vingerafdruk)'); }
}
window.onKluisChanged = function(r){ if (r === 'ok' && typeof klNieuwInMap === 'function') klNieuwInMap(); toast(r === 'ok' ? t('Toegevoegd aan de kluis') : t(r)); if (current === 'kluis') klRender(); };
function klLock(){ Android.kluisClose(); klRender(); }
// Ontsleutelen kan bij een groot document even duren; de balk gaat weg zodra de keuze van de app (of een fout) verschijnt.
function klView(id){
  const klaar = uiBezig(t('Document ontsleutelen…'), ['onKluisChanged', 'onPauseApp']);
  setTimeout(klaar, 60000);
  Android.kluisView(id);
}
