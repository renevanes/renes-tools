/* ---------- Overzicht op het startscherm ---------- */
function renderDash(){
  const box = $('#home-dash'); if (!box) return;
  if (gsQ) { box.style.display = 'none'; return; }
  if (homeCfg().dash === false) { box.style.display = 'none'; return; }
  const rows = [];
  // Telefoon-skin nog niet in gebruik: bovenaan de weg ernaartoe.
  try { if (!Android.homeIsDefault()) rows.push(['📱', 'Telefoon-skin: Rene\'s Tools als startscherm gebruiken', 'settings', '']); } catch(e){}
  try {
    const bk = JSON.parse(Android.backupState(false));
    const l = bk.last;
    if (bk.busy) rows.push(['💾', 'Backup is bezig…', 'backup', '']);
    else if (l && l.t) {
      const failed = Object.keys(l).filter(k => l[k] && l[k].ok === false).length;
      const old = Date.now() - l.t > 3 * 864e5;
      rows.push(['💾', 'Laatste backup: ' + fmtD(l.t) + (failed ? ' · ' + failed + ' mislukt' : old ? ' · al even geleden' : ' ✓'), 'backup', failed ? 'badc' : old ? 'warnc' : '']);
    } else rows.push(['💾', 'Nog geen backup gemaakt', 'backup', 'warnc']);
  } catch(e){}
  try {
    const a = JSON.parse(Android.alarmState());
    if (a.on && a.nextAt) rows.push(['⏰', 'Wekker: ' + new Date(a.nextAt).toLocaleString('nl-NL', {weekday:'short', hour:'2-digit', minute:'2-digit'}) + (a.station ? ' · ' + a.station.name : ''), 'ralarm', '']);
  } catch(e){}
  try {
    const r = JSON.parse(Android.noteReminders()), end = new Date(); end.setHours(23, 59, 59, 999);
    const today = Object.values(r).filter(t => t >= Date.now() && t <= end.getTime()).sort();
    if (today.length) rows.push(['🔔', today.length + (today.length === 1 ? ' herinnering' : ' herinneringen') + ' vandaag, eerst om ' + new Date(today[0]).toLocaleTimeString('nl-NL', {hour:'2-digit', minute:'2-digit'}), 'notes', '']);
  } catch(e){}
  try {
    const rp = JSON.parse(Android.redialPlanState());
    if (rp.plan && rp.next > Date.now() && rp.next - Date.now() < 7 * 864e5) rows.push(['📞', 'Auto redial: ' + (rp.plan.name || rp.plan.number) + ' · ' + new Date(rp.next).toLocaleString('nl-NL', {weekday:'short', hour:'2-digit', minute:'2-digit'}), 'redial', '']);
  } catch(e){}
  if (rdState && rdState.status === 'playing' && rdState.station) rows.push(['📻', rdState.station.name + (rdState.title ? ' · ' + rdState.title : ''), 'radio', '']);
  try {
    const st = JSON.parse(Android.selfTestLast());
    const bad = (st.items || []).filter(i => i.status === 'fail').length;
    if (bad) rows.push(['🩺', 'Zelftest: ' + bad + ' probleem' + (bad > 1 ? 'en' : ''), 'selftest', 'badc']);
  } catch(e){}
  box.style.display = rows.length ? 'block' : 'none';
  box.innerHTML = rows.map(r => '<button data-t="' + r[2] + '" onclick="jumpTo(this.dataset.t)"><span class=di>' + r[0] + '</span><span class="dt ' + r[3] + '">' + esc(r[1]) + '</span><span aria-hidden=true>›</span></button>').join('');
}

