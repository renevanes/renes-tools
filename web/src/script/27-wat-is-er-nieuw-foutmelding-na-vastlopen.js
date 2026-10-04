/* ---------- Wat is er nieuw / foutmelding na vastlopen ---------- */
function whatsNew(){
  const seen = store.get('seenVersion', null);
  store.set('seenVersion', VERSION_CODE);
  if (seen == null || seen >= VERSION_CODE) return false;
  // Alle versies sinds de vorige keer (changelog staat nieuwste eerst; per versie code = positie)
  const n = Math.min(VERSION_CODE - seen, CHANGELOG.length, 5);
  const items = CHANGELOG.slice(0, n);
  if (!items.length) return false;
  openModal('Wat is er nieuw', '', null, 'Fijn!', () => {});
  const t = $('#modal-text');
  t.style.display = 'block';
  t.innerHTML = items.map(e => '<b>Versie ' + esc(e.version) + '</b><span class=wn>' + e.changes.map(c => '• ' + esc(c)).join('<br>') + '</span>').join('');
  $('#modal-cancel').style.display = 'none';
  return true;
}
window.addEventListener('error', e => { try { Android.logJs((e.message || 'fout') + ' @' + (e.filename || '').split('/').pop() + ':' + (e.lineno || 0)); } catch(x){} });

