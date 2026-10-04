/* ---------- Alles back-uppen ---------- */
const BK_PARTS = { sms: 'Sms-berichten', calls: 'Oproepen', contacts: 'Contacten', notes: 'Notities', transcripts: 'Uitgeschreven gesprekken', music: 'Herkende muziek' };
let bkPoll = null, bkBusy = false;
function bkState(full){ try { return JSON.parse(Android.backupState(!!full)); } catch(e){ return {}; } }
function enterBackup(){
  const st = bkState(true);
  $('#bk-nodest').style.display = st.dest ? 'none' : 'block';
  $('#bk-dest').textContent = st.dest ? 'Naar: ' + (st.destName || 'je backup-map') : '';
  $('#bk-parts').innerHTML = Object.keys(BK_PARTS).map(k => '<label class=chk><input type=checkbox data-p="' + k + '"' + ((st.parts || {})[k] !== false ? ' checked' : '') +
    ' onchange="Android.backupSetPart(this.dataset.p, this.checked)"> ' + BK_PARTS[k] + '</label>').join('');
  $('#bk-auto').checked = !!st.auto; $('#bk-charging').checked = st.charging !== false;
  $('#bk-next').textContent = st.auto && st.next ? 'Volgende backup: ' + new Date(st.next).toLocaleString('nl-NL', {weekday:'short', day:'numeric', month:'short', hour:'2-digit', minute:'2-digit'}) : '';
  bkRenderLast(st); bkRenderSecure(st);
  stopBkPoll(); bkPoll = setInterval(bkPollOnce, 1000);
}
function stopBkPoll(){ if (bkPoll) { clearInterval(bkPoll); bkPoll = null; } }
function bkRenderLast(st){
  const l = st.last;
  bkBusy = !!st.busy;
  $('#bk-run').disabled = bkBusy || !st.dest;
  $('#bk-run').textContent = bkBusy ? 'Bezig: ' + (BK_PARTS[st.current] || '…') : 'Nu alles back-uppen';
  if (!l || !l.t) { $('#bk-last').textContent = 'Nog niet gemaakt'; $('#bk-parts-res').innerHTML = ''; return; }
  $('#bk-last').textContent = (l.auto ? 'Automatisch, ' : '') + new Date(l.t).toLocaleString('nl-NL', {weekday:'long', day:'numeric', month:'long', hour:'2-digit', minute:'2-digit'});
  $('#bk-parts-res').innerHTML = Object.keys(BK_PARTS).filter(k => l[k]).map(k => { const r = l[k];
    return '<div class=bkres><b>' + BK_PARTS[k] + '</b>' + (r.ok ? '<span class=good>✓ ' + (r.msg ? esc(r.msg) : r.count.toLocaleString('nl-NL')) + '</span>' : '<span class=bad>✗ ' + esc(r.msg || 'mislukt') + '</span>') + '</div>'; }).join('') +
    (l.all ? '<div class=bkres><span class=bad>' + esc(l.all.msg || '') + '</span></div>' : '') +
    (l.archive ? '<div class=bkres><b>🔒 Versleuteld</b><span class=good>' + esc(l.archive) + '</span></div>' : '') +
    (l.rotated ? '<div class=bkres><b>Opgeruimd</b><span>' + l.rotated.count + ' oude bestanden (' + fmtB(l.rotated.bytes) + ')</span></div>' : '');
}
function bkPollOnce(){ const st = bkState(); if (st.busy !== bkBusy || st.busy) bkRenderLast(st); const t = $('#tile-backup-sub'); if (t) t.textContent = st.busy ? 'Bezig met back-uppen…' : (st.last && st.last.t ? 'Laatst: ' + fmtD(st.last.t) : 'Alles in één keer bewaren'); }
function bkRun(){ const e = Android.backupStart($('#bk-wa').checked); if (e) toast(e); setTimeout(bkPollOnce, 200); }
window.onBackupDone = function(r){
  const failed = Object.keys(r || {}).filter(k => r[k] && r[k].ok === false).length;
  toast(failed ? 'Backup klaar, ' + failed + ' onderdeel mislukt' : '✓ Alles is back-upt');
  if (current === 'backup') bkRenderLast(bkState()); bkPollOnce();
};
function bkSaveAuto(){ Android.backupSetAuto($('#bk-auto').checked, $('#bk-charging').checked); enterBackup(); }
window.onRestorePreview = function(d){
  if (d.error) { toast(d.error); return; }
  const what = d.kind === 'contacts' ? 'contacten' : 'notities';
  if (!d.fresh) { toast('Alle ' + d.total + ' ' + what + ' staan er al; er is niets toe te voegen'); return; }
  askConfirm(d.fresh + ' ' + what + ' toevoegen?', 'In het bestand: ' + d.total + '. Al aanwezig (worden overgeslagen): ' + d.dup + '.\n\n' +
    (d.sample || []).join(', ') + (d.fresh > (d.sample || []).length ? ', …' : ''), 'Toevoegen', () => {
    if (d.kind === 'notes' && typeof notesSaveNow === 'function') notesSaveNow(); // eerst eventuele laatste wijziging bewaren
    restoreWhat = what; restoreKind = d.kind;
    const e = Android.restoreApply();
    if (e) toast(e); else toast('Bezig met toevoegen…');
  });
};
let restoreWhat = '', restoreKind = '';
window.onRestoreDone = function(r){
  if (r.startsWith('ok:')) {
    toast('✓ ' + r.slice(3) + ' ' + restoreWhat + ' toegevoegd');
    if (restoreKind === 'notes') { notes = null; notesBroken = false; refreshNotesTile(); }
  } else toast(r);
};

