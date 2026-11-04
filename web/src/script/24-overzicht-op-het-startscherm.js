/* ---------- Overzicht op het startscherm ---------- */
/* Vastgezette notities (bijv. de boodschappenlijst) bovenaan het startscherm van de app. */
function renderNotePins(){
  const wrap = $('#home-notes'); if (!wrap) return;
  let ids = []; try { ids = JSON.parse(Android.notePins('app')) || []; } catch(e){}
  const list = ids.map(id => noteById(id)).filter(Boolean);
  wrap.style.display = list.length ? 'block' : 'none';
  $('#home-notes-tiles').innerHTML = list.map(n => {
    const open = (n.items || []).filter(i => !i.done);
    const sub = (n.items || []).length ? (open.length ? t('{n} open', { n: open.length }) + ' · ' + open.slice(0, 3).map(i => i.text).join(', ') : t('Alles afgestreept ✓')) : (n.text || '').slice(0, 60);
    return '<button class="tile" data-id="' + esc(n.id) + '" onclick="jumpTo(\'note\', () => openNote(this.dataset.id))"><span class="ic">'+uiIcon('notes')+'</span><b>' +
      '<span translate="no">' + esc(n.title || t('Notitie')) + '</span></b><small translate="no">' + esc(sub) + '</small></button>';
  }).join('');
}
/* "Sinds gisteravond": wat er gebeurde terwijl je sliep (gemiste oproepen, backup, automatiseringen, batterij). */
function renderNight(){
  const box = $('#home-night'); if (!box) return;
  if (typeof Android.overnight !== 'function') { box.style.display = 'none'; return; }
  let o = {}; try { o = JSON.parse(Android.overnight()); } catch(e){}
  const rows = [];
  const hm = ms => fmtTime(ms, {hour: '2-digit', minute: '2-digit'});
  if (o.backup) rows.push(['backup', o.backup.failed ? tn(o.backup.failed, 'Backup om {tijd}: {n} onderdeel mislukt', 'Backup om {tijd}: {n} onderdelen mislukt', { tijd: hm(o.backup.t) }) : t('Backup om {tijd} ✓', { tijd: hm(o.backup.t) }), 'backup', o.backup.failed ? 'badc' : '']);
  for (const m of (o.missed || [])) rows.push(['phone', t('Gemist: {wie} om {tijd}', { wie: m.who, tijd: hm(m.t) }), 'calls', 'warnc']);
  for (const a of (o.auto || [])) rows.push(['automation', (a.name ? a.name + ': ' : '') + t(a.what) + ' (' + hm(a.t) + ')', 'auto', '']);
  if (o.battery) rows.push(['battery', t('Android beperkt de app: {wat}', { wat: t(o.battery) }), 'selftest', 'badc']);
  if (o.checkBad) rows.push(['shield', t('Backup-controle: {wat}', { wat: t(o.checkBad) }), 'backup', 'badc']);
  if (!rows.length) { box.style.display = 'none'; return; }
  box.style.display = 'block';
  box.innerHTML = '<div style="display:flex;align-items:center;padding:8px 0 2px"><b style="flex:1">Sinds gisteravond</b><button style="width:auto;border:0;padding:4px 8px;color:var(--chip-on);font-weight:600" onclick="Android.overnightSeen();renderNight()">Gezien</button></div>' +
    rows.map(r => '<button data-t="' + r[2] + '" onclick="jumpTo(this.dataset.t)"><span class=di>' + uiIcon(r[0]) + '</span><span class="dt ' + r[3] + '">' + esc(r[1]) + '</span><span aria-hidden=true>›</span></button>').join('');
}
function renderDash(){
  renderNotePins();
  renderNight();
  const box = $('#home-dash'); if (!box) return;
  if (homeCfg().dash === false) { box.style.display = 'none'; return; }
  const rows = [];
  try { const tile = $('#tile-skin-sub'); if (tile) tile.textContent = Android.homeIsDefault() ? 'Actief als startscherm' : 'Je telefoon in een nieuw jasje'; } catch(e){}
  try {
    const bk = JSON.parse(Android.backupState(false));
    const l = bk.last;
    if (bk.busy) rows.push(['backup', 'Backup is bezig…', 'backup', '']);
    else if (l && l.t) {
      const failed = Object.keys(l).filter(k => l[k] && l[k].ok === false).length;
      const old = Date.now() - l.t > 3 * 864e5;
      rows.push(['backup', t('Laatste backup: {wanneer}', { wanneer: fmtD(l.t) }) + (failed ? ' · ' + t('{n} mislukt', { n: failed }) : old ? ' · ' + t('al even geleden') : ' ✓'), 'backup', failed ? 'badc' : old ? 'warnc' : '']);
    } else rows.push(['backup', 'Nog geen backup gemaakt', 'backup', 'warnc']);
  } catch(e){}
  try {
    const a = JSON.parse(Android.alarmState());
    if (a.on && a.nextAt) rows.push(['clock', t('Wekker:') + ' ' + fmtDT(a.nextAt, {weekday:'short', hour:'2-digit', minute:'2-digit'}) + (a.station ? ' · ' + a.station.name : ''), 'ralarm', '']);
  } catch(e){}
  try {
    const r = JSON.parse(Android.noteReminders()), end = new Date(); end.setHours(23, 59, 59, 999);
    const today = Object.values(r).filter(x => x >= Date.now() && x <= end.getTime()).sort();
    if (today.length) rows.push(['bell', tn(today.length, '{n} herinnering vandaag, eerst om {tijd}', '{n} herinneringen vandaag, eerst om {tijd}', { tijd: fmtTime(today[0], {hour:'2-digit', minute:'2-digit'}) }), 'notes', '']);
  } catch(e){}
  try {
    const rp = JSON.parse(Android.redialPlanState());
    if (rp.plan && rp.next > Date.now() && rp.next - Date.now() < 7 * 864e5) rows.push(['phone', t('Auto redial:') + ' ' + (rp.plan.name || rp.plan.number) + ' · ' + fmtDT(rp.next, {weekday:'short', hour:'2-digit', minute:'2-digit'}), 'redial', '']);
  } catch(e){}
  if (rdState && rdState.status === 'playing' && rdState.station) rows.push(['radio', rdState.station.name + (rdState.title ? ' · ' + rdState.title : ''), 'radio', '']);
  try {
    const st = JSON.parse(Android.selfTestLast());
    const bad = (st.items || []).filter(i => i.status === 'fail').length;
    if (bad) rows.push(['shield', 'Zelftest: ' + bad + ' probleem' + (bad > 1 ? 'en' : ''), 'selftest', 'badc']);
  } catch(e){}
  box.style.display = rows.length ? 'block' : 'none';
  box.innerHTML = rows.map(r => '<button data-t="' + r[2] + '" onclick="jumpTo(this.dataset.t)"><span class=di>' + uiIcon(r[0]) + '</span><span class="dt ' + r[3] + '">' + esc(r[1]) + '</span><span aria-hidden=true>›</span></button>').join('');
}

