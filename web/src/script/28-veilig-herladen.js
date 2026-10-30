/* Een interface-update mag geen invoer of bewerkingen wissen, ook niet van een eerder geopend scherm. */
document.addEventListener('input', e => {
  if (!e.target.matches('input, textarea')) return;
  const screen = e.target.closest('.screen'), name = screen && screen.id.slice(2);
  // Deze instellingen worden bij iedere wijziging meteen opgeslagen. Persoonlijke AI-tekst heeft een eigen dirty-status.
  if (name && (name === 'settings' || (typeof ST_SUBS !== 'undefined' && ST_SUBS.includes(name))
      || (typeof AI_SUBS !== 'undefined' && AI_SUBS.includes(name)))) return;
  e.target.dataset.uiEdited = '1';
});
function uiCanReload(){
  if (current !== 'home' && current !== 'info') return false;
  if (document.querySelector('#modal.on, #sheet.on, #voice.on, #ci-ans.on, #sleepdlg.on, #lockscr.on, #player.on')) return false;
  if (typeof pdfEditDirty === 'function' && pdfEditDirty()) return false;
  if (typeof pdfEditPendingInput === 'function' && pdfEditPendingInput()) return false;
  if (typeof pdfBusy !== 'undefined' && pdfBusy) return false;
  if (typeof pdfEd !== 'undefined' && (pdfEd.stroke || pdfEd.strokes.length)) return false;
  if (['#note-add', '#ai-in'].some(s => $(s) && $(s).value.trim())) return false;
  if (typeof aeDirty === 'function' && aeDirty()) return false;
  if (typeof cedDirty === 'function' && cedDirty()) return false;
  if (typeof aicDirtyOn !== 'undefined' && aicDirtyOn) return false;
  if (typeof notesUnsaved === 'function' && notesUnsaved() && !notesSaveNow()) return false;
  return ![...document.querySelectorAll('[data-ui-edited="1"]')].some(e => {
    // Notities worden hierboven veilig opgeslagen; een zoekvraag is geen documentbewerking.
    if (e.closest('#s-note') || e.id === 'pdf-edit-text' || e.type === 'search') return false;
    return e.value !== e.defaultValue && String(e.value || '').trim() !== '';
  });
}
window.prepareUiReload = function(){
  if (!uiCanReload()) return false;
  document.body.inert = true; // voorkom een laatste toetsaanslag tussen de controle en het native herladen
  setTimeout(() => { document.body.inert = false; }, 1500); // vangnet als het venster intussen is gesloten
  return true;
};
function updTryReload(manual){
  if (typeof updReady === 'undefined' || !updReady) return;
  if (updAvail || updKind === 'apk') return;
  if (updReloadingVersion === updReady) return;
  if (uiCanReload() && typeof Android.reloadUi === 'function') {
    const version = updReady; updReloadingVersion = version;
    Android.reloadUi();
    setTimeout(() => { if (updReloadingVersion === version) updReloadingVersion = ''; }, 1600);
  }
  else if (manual) toast('Bewaar je bewerkingen en sluit open invoer. De update blijft ook klaarstaan voor de volgende start.');
}
