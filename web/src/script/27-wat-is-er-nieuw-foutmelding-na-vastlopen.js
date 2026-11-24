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
  const tx = $('#modal-text');
  tx.style.display = 'block';
  tx.innerHTML = (TAAL.on ? '<span class=wn>' + t('(in het Nederlands)') + '</span>' : '') + items.map(e => '<b>' + t('Versie {n}', { n: esc(e.version) }) + '</b><span class=wn translate="no">' + e.changes.map(c => '• ' + esc(c)).join('<br>') + '</span>').join('');
  $('#modal-cancel').style.display = 'none';
  return true;
}
window.addEventListener('error', e => { try { Android.logJs((e.message || 'fout') /* taal-vast: logregel, niet in beeld */ + ' @' + (e.filename || '').split('/').pop() + ':' + (e.lineno || 0)); } catch(x){} });
// Ook een fout in een belofte die niemand opvangt (await zonder try): anders zie je daar niets van in het logboek
window.addEventListener('unhandledrejection', e => { try { const r = e.reason; Android.logJs('belofte: ' /* taal-vast: logregel, niet in beeld */ + String(r && (r.stack || r.message) || r || 'fout').slice(0, 1500)); } catch(x){} });

