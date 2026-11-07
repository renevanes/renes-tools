/* Boeken worden door Java als tekst aangeboden, nooit als HTML of externe inhoud. */
let erBooks = [], erBook = null, erIndex = 0, erTimer = null, erBusy = false, erRestoring = false;
function erData(raw){ try { return JSON.parse(raw); } catch(e){ return { error: t('Boek openen lukt niet') }; } }
function enterEreader(){
  const ready = typeof Android.ereaderList === 'function';
  $('#er-add').disabled = erBusy || !ready;
  if (!ready) { erStatus(t('Installeer de nieuwste APK om de e-reader te gebruiken')); return; }
  const r = erData(Android.ereaderList());
  if (r.error) { erStatus(r.error); return; }
  erBooks = r.books || []; erRender();
}
function erStatus(text){ const e = $('#er-status'); e.textContent = text || ''; e.hidden = !text; }
function erRender(){
  const q = $('#er-search').value.trim().toLowerCase();
  const list = erBooks.filter(b => [b.title, b.author].join(' ').toLowerCase().includes(q)).sort((a,b) => (b.last || 0) - (a.last || 0));
  if (!list.length) {
    $('#er-list').innerHTML = uiEmpty('document', t(erBooks.length ? 'Geen boeken bij deze zoekopdracht.' : 'Nog geen boeken. Voeg je eerste boek toe.'));
    return;
  }
  $('#er-list').innerHTML = '<div class="card stlist">' + list.map(b => '<button class="strow" onclick="erOpen(' + jsq(b.id) + ')"><span class="stic">' + uiIcon('document') + '</span><span class="sttxt"><b translate="no">' + esc(b.title) + '</b><small translate="no">' + esc(b.author) + '</small><small>' + esc(t('Hoofdstuk {n} van {totaal}', { n: fmtNum((b.chapter || 0) + 1), totaal: fmtNum(b.chapters.length) })) + '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>').join('') + '</div>';
}
function erPick(){
  if (erBusy || typeof Android.ereaderPick !== 'function') return;
  erBusy = true; $('#er-add').disabled = true; erStatus(t('Boek toevoegen…')); Android.ereaderPick();
}
window.onEreaderImport = function(r){
  erBusy = false; $('#er-add').disabled = false; erStatus(r.error || '');
  if (r.error || r.cancelled) return;
  if (current === 'ereader') { enterEreader(); if (r.book) erOpen(r.book.id); }
  else toast(t('Boek toegevoegd aan de e-reader'));
};
function erOpen(id){
  erSave();
  const b = erBooks.find(x => x.id === id); if (!b) return;
  erBook = b; erIndex = Math.max(0, Math.min(b.chapters.length - 1, b.chapter || 0));
  const r = erData(Android.ereaderChapter(id, erIndex));
  if (r.error) { erBook = null; toast(r.error); return; }
  $('#er-title').textContent = b.title; $('#er-author').textContent = b.author || '';
  $('#er-chapters').innerHTML = b.chapters.map((name,i) => '<option value="' + i + '">' + esc(name || t('Hoofdstuk {n}', { n: fmtNum(i+1) })) + '</option>').join('');
  show('erbook'); erDisplay(r.text, b.offset || 0);
}
function erDisplay(text, offset){
  erRestoring = true;
  $('#er-text').textContent = text;
  $('#er-text').setAttribute('aria-label', t('Boektekst'));
  const size = store.get('erSize', 22), font = store.get('erFont', 'serif');
  $('#er-size').value = String([18,22,26,32].includes(size) ? size : 22);
  $('#er-font').value = font === 'sans-serif' ? font : 'serif';
  $('#er-text').style.fontSize = $('#er-size').value + 'px'; $('#er-text').style.fontFamily = $('#er-font').value;
  $('#er-chapters').value = String(erIndex);
  $('#er-prev').disabled = erIndex === 0; $('#er-next').disabled = erIndex === erBook.chapters.length - 1;
  // De hoogte is nu bekend; meteen herstellen voorkomt dat een oude scrollgebeurtenis de positie overschrijft.
  const e = $('#er-text'); e.scrollTop = Math.max(0, Math.min(1, offset)) * Math.max(0, e.scrollHeight - e.clientHeight);
  erRestoring = false; erProgress(); erSave();
}
function erChapter(index){
  if (!erBook || index < 0 || index >= erBook.chapters.length) return;
  const r = erData(Android.ereaderChapter(erBook.id, index));
  if (r.error) { $('#er-chapters').value = String(erIndex); toast(r.error); return; }
  erSave(); erIndex = index; erDisplay(r.text, 0);
}
function erOffset(){ const e = $('#er-text'); return e.scrollHeight > e.clientHeight ? e.scrollTop / (e.scrollHeight - e.clientHeight) : 0; }
function erProgress(){
  if (erBook) $('#er-progress').textContent = t('Hoofdstuk {n} van {totaal} · {procent}% gelezen in dit hoofdstuk', { n: fmtNum(erIndex+1), totaal: fmtNum(erBook.chapters.length), procent: fmtNum(Math.round(erOffset()*100)) });
}
function erScroll(){
  if (!erBook || current !== 'erbook' || erRestoring) return;
  erProgress(); clearTimeout(erTimer); erTimer = setTimeout(erSave, 400);
}
function erSave(){
  clearTimeout(erTimer);
  if (!erBook || current !== 'erbook') return;
  const offset = erOffset(), error = Android.ereaderPosition(erBook.id, erIndex, offset);
  if (error) { toast(error); return; }
  erBook.chapter = erIndex; erBook.offset = offset; erBook.last = Date.now();
}
function erLeave(){ erSave(); erBook = null; $('#er-text').textContent = ''; }
function erSize(value){
  const p = erOffset(), size = Number(value); if (![18,22,26,32].includes(size)) return;
  store.set('erSize', size); $('#er-text').style.fontSize = size + 'px'; erRestore(p);
}
function erFont(value){
  const p = erOffset(); value = value === 'sans-serif' ? value : 'serif';
  store.set('erFont', value); $('#er-text').style.fontFamily = value; erRestore(p);
}
function erRestore(p){ const e = $('#er-text'); e.scrollTop = p * Math.max(0, e.scrollHeight - e.clientHeight); erProgress(); erSave(); }
function erMenu(){
  if (!erBook) return;
  const id = erBook.id;
  openSheet(erBook.title, '', [[t('Uit de boekenlijst verwijderen'), () => askConfirm(t('Boek verwijderen?'), t('De kopie in Tools en de leespositie worden verwijderd. Je oorspronkelijke bestand blijft staan.'), t('Verwijderen'), () => {
    const error = Android.ereaderDelete(id); if (error) { toast(error); return; }
    erBook = null; show('ereader'); toast(t('Boek verwijderd'));
  }), 'trash']]);
}
window.addEventListener('pagehide', erSave);
document.addEventListener('visibilitychange', () => { if (document.hidden) erSave(); });

(function(){ const previous = window.onPauseApp; window.onPauseApp = function(){ erSave(); if (previous) previous(); }; })();
