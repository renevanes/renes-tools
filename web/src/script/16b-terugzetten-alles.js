/* ---------- Alles uit de backup-map terugzetten (gewone onderdelen, in één keer) ---------- */
let bkLosRes = null, bkLosBusy = false, bkLosPerm = false;
const BK_LOS_INFO = { sms: 'Sms', calls: 'Oproepen', notifications: 'Meldingen', wa: 'WhatsApp' };
/* Knoppen tonen of verbergen (.big heeft een eigen display: hidden alleen werkt daar niet) */
function bkLosShow(sel, on){ const e = $(sel); if (e) e.style.display = on ? '' : 'none'; }
function bkLosLabel(k){ const r = BK_RESTORE.find(x => x[0] === k); return r ? t(r[1] === 'Route (GPX)' ? 'Routes' : r[1]) : k; }
function bkLosRow(label, cls, msg, sub){
  return '<div class=bkres><b>' + esc(label) + '</b><span class="' + cls + '">' + esc(msg) + '</span></div>' + (sub ? '<p class="note" style="margin:0 0 6px">' + esc(sub) + '</p>' : '');
}
function bkLosScan(){
  if (bkLosBusy) return;
  bkLosBusy = true;
  const b = $('#bk-los-scan'); b.disabled = true; b.textContent = t('Bezig met zoeken…');
  bkLosShow('#bk-los-go', false); bkLosShow('#bk-los-perm', false);
  $('#bk-los-res').innerHTML = '<p class="note">' + esc(t('Je backup-map wordt doorzocht. Er verandert nog niets.')) + '</p>';
  Android.herstelLosScan();
}
window.onHerstelLosScan = function(r){
  bkLosBusy = false;
  const b = $('#bk-los-scan'); if (!b) return;
  b.disabled = false; b.textContent = t('Opnieuw doorzoeken');
  bkLosRes = r = r || {};
  if (r.error) { $('#bk-los-res').innerHTML = '<p class="note warn">' + esc(t(r.error)) + '</p>'; return; }
  const parts = r.parts || [], info = r.info || [];
  let h = '<p class="note" style="margin-top:0">' + esc(t('Gevonden in {map}:', { map: r.destName || t('je backup-map') })) + '</p>';
  if (!parts.length) h += '<p class="note warn" id="bk-los-none">' + esc(t('Geen onderdelen gevonden die terug kunnen.')) + '</p>';
  h += parts.map(p => {
    const label = bkLosLabel(p.kind);
    if (p.error) return bkLosRow(label, 'bad', '✗ ' + t(p.error));
    if (p.needPerm) return bkLosRow(label, 'bad', t('Toestemming voor contacten nodig'));
    return bkLosRow(label, p.fresh ? '' : 'good', p.fresh ? t('{n} nieuw', { n: fmtNum(p.fresh) }) : t('✓ Staat er al'));
  }).join('');
  h += info.map(i => bkLosRow(t(BK_LOS_INFO[i.kind] || i.kind), '', t('Niet terug te zetten'), t(i.msg))).join('');
  $('#bk-los-res').innerHTML = h;
  const fresh = r.fresh || 0, go = $('#bk-los-go');
  bkLosShow('#bk-los-go', !!fresh); go.disabled = false;
  go.textContent = t('Alles terugzetten ({n} nieuw)', { n: fmtNum(fresh) });
  bkLosShow('#bk-los-perm', parts.some(p => p.needPerm));
};
function bkLosAskPerm(){ bkLosPerm = true; Android.herstelFix('contacts'); }
function bkLosApply(){
  if (bkLosBusy) return;
  askConfirm('Alles terugzetten?', t('Alles wat in je backup-map staat en hier nog ontbreekt, wordt toegevoegd. Wat er al staat, blijft zoals het is; er wordt niets verwijderd.'), 'Terugzetten', () => {
    bkLosBusy = true;
    const go = $('#bk-los-go'); go.disabled = true; go.textContent = t('Bezig met terugzetten…');
    $('#bk-los-scan').disabled = true;
    Android.herstelLosApply();
  });
}
window.onHerstelLosStep = function(s){
  const [k, d, n] = String(s).split(','), go = $('#bk-los-go');
  if (go) go.textContent = k ? t('Bezig: {wat} ({a} van {b})', { wat: bkLosLabel(k), a: +d + 1, b: +n }) : t('Bijna klaar…');
};
window.onHerstelLosDone = function(r){
  bkLosBusy = false;
  r = r || {};
  const go = $('#bk-los-go'), sc = $('#bk-los-scan');
  if (sc) sc.disabled = false;
  if (r.error) { if (go) { go.disabled = false; go.textContent = t('Alles terugzetten'); } toast(r.error); return; }
  bkLosShow('#bk-los-go', false);
  const parts = r.parts || [];
  let n = 0;
  $('#bk-los-res').innerHTML = '<p class="note" style="margin-top:0">' + esc(t('Teruggezet:')) + '</p>' + parts.map(p => {
    n += p.count || 0;
    return bkLosRow(bkLosLabel(p.kind), p.ok ? 'good' : 'bad', p.ok ? (p.count ? t('✓ {n} toegevoegd', { n: fmtNum(p.count) }) : t('✓ Stond er al')) : '✗ ' + t(p.msg || 'Mislukt'));
  }).join('');
  bkLosShow('#bk-los-perm', parts.some(p => !p.ok && p.kind === 'contacts'));
  toast(r.ok ? t('✓ Alles teruggezet ({n} toegevoegd)', { n: fmtNum(n) }) : t('Teruggezet, maar niet alles lukte'));
  // Wat in het geheugen van de interface stond, opnieuw inlezen
  try { webStoreRestore(); } catch(e){}
  if (typeof rgReload === 'function') try { rgReload(); } catch(e){}
  try { notes = null; notesBroken = false; refreshNotesTile(); } catch(e){}
  if (typeof renderHome === 'function') try { renderHome(); } catch(e){}
};
(function(){
  const prev = window.onResumeApp;
  window.onResumeApp = function(){ if (typeof prev === 'function') prev(); if (bkLosPerm && current === 'bkrestore') { bkLosPerm = false; bkLosScan(); } };
})();
