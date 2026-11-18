/* ---------- Overzicht op het startscherm ---------- */
/* Vastgezette notities (bijv. de boodschappenlijst) bovenaan het startscherm van de app. */
function renderNotePins(){
  const wrap = $('#home-notes'); if (!wrap) return;
  if (typeof tsVerborgen === 'function' && tsVerborgen('notes') || homeCfg().notes === false) { wrap.style.display = 'none'; $('#home-notes-tiles').innerHTML = ''; return; } // extra ontgrendelen
  let ids = []; try { ids = JSON.parse(Android.notePins('app')) || []; } catch(e){}
  const list = ids.map(id => noteById(id)).filter(Boolean);
  wrap.style.display = list.length ? 'block' : 'none';
  $('#home-notes-tiles').innerHTML = list.map(n => {
    const open = (n.items || []).filter(i => !i.done);
    const sub = (n.items || []).length ? (open.length ? t('{n} open', { n: open.length }) + ' · ' + open.slice(0, 3).map(i => i.text).join(', ') : t('Alles afgestreept ✓')) : notePreview(n).slice(0, 60);
    return '<button class="tile" data-id="' + esc(n.id) + '" onclick="jumpTo(\'note\', () => openNote(this.dataset.id))"><span class="ic">'+uiIcon(noteKind(n)==='list'?'notes':'document')+'</span><b>' +
      '<span translate="no">' + esc(noteLabel(n) || t('Notitie')) + '</span></b><small translate="no">' + esc(sub) + '</small></button>';
  }).join('');
}
/* "Sinds gisteravond": wat er gebeurde terwijl je sliep (gemiste oproepen, backup, automatiseringen, batterij). */
function renderNight(){
  const box = $('#home-night'); if (!box) return;
  if (typeof Android.overnight !== 'function') { box.style.display = 'none'; homeOvSync(); return; }
  const ov = homeOv(); // Startscherm aanpassen → Overzicht: Sinds gisteravond aan of uit, en welke regels
  if (!ov.nacht) { box.style.display = 'none'; homeOvSync(); return; }
  const aan = id => !ov.nachtUit.includes(id);
  let o = {}; try { o = JSON.parse(Android.overnight()); } catch(e){}
  const rows = [];
  const hm = ms => fmtTime(ms, {hour: '2-digit', minute: '2-digit'});
  if (o.backup && aan('backup')) rows.push(['backup', o.backup.failed ? tn(o.backup.failed, 'Backup om {tijd}: {n} onderdeel mislukt', 'Backup om {tijd}: {n} onderdelen mislukt', { tijd: hm(o.backup.t) }) : t('Backup om {tijd} ✓', { tijd: hm(o.backup.t) }), 'backup', o.backup.failed ? 'badc' : '']);
  if (aan('gemist')) for (const m of (o.missed || [])) rows.push(['phone', t('Gemist: {wie} om {tijd}', { wie: m.who, tijd: hm(m.t) }), 'calls', 'warnc']);
  if (aan('auto')) for (const a of (o.auto || [])) rows.push(['automation', (a.name ? a.name + ': ' : '') + t(a.what) + ' (' + hm(a.t) + ')', 'auto', '']);
  if (o.battery && aan('accu')) rows.push(['battery', t('Android beperkt de app: {wat}', { wat: t(o.battery) }), 'selftest', 'badc']);
  if (o.checkBad && aan('controle')) rows.push(['shield', t('Backup-controle: {wat}', { wat: t(o.checkBad) }), 'backup', 'badc']);
  homeDicht(rows);
  if (!rows.length) { box.style.display = 'none'; homeOvSync(); return; }
  box.style.display = 'block';
  box.innerHTML = '<div class="ovhead"><b>Sinds gisteravond</b><button onclick="Android.overnightSeen();renderNight()">Gezien</button></div>' +
    rows.map(r => '<button data-t="' + r[2] + '" onclick="jumpTo(this.dataset.t)"><span class=di>' + uiIcon(r[0]) + '</span><span class="dt ' + r[3] + '">' + esc(r[1]) + '</span><span aria-hidden=true>›</span></button>').join('');
  homeOvSync();
}
/* Regels over een tool met extra ontgrendelen (op slot) weglaten */
function homeDicht(rows){
  if (typeof tsVerborgen !== 'function') return;
  for (let i = rows.length - 1; i >= 0; i--) if (tsVerborgen(tsTool(rows[i][2]))) rows.splice(i, 1);
}
/* Sinds gisteravond en het overzicht staan samen in één kaart; die kaart alleen als er iets in staat. */
function homeOvSync(){
  const ov = $('#home-ov'); if (!ov) return;
  ov.hidden = ![...ov.children].some(x => x.style.display !== 'none' && !x.hidden);
  ov.classList.toggle('both', [...ov.children].every(x => x.style.display !== 'none' && !x.hidden));
}
function renderDash(){
  renderNotePins();
  if (typeof renderHomeRecent === 'function') renderHomeRecent();
  if (isPlay()) { $('#home-night').style.display = 'none'; $('#home-dash').style.display = 'none'; homeOvSync(); return; }
  renderNight();
  const box = $('#home-dash'); if (!box) return;
  if (homeCfg().dash === false) { box.style.display = 'none'; homeOvSync(); return; }
  const per = {}, ov = homeOv(), oud = ov.bkDagen * 864e5; // regels per soort; volgorde en keuze uit Startscherm aanpassen → Overzicht
  try { const tile = $('#tile-skin-sub'); if (tile) tile.textContent = Android.homeIsDefault() ? 'Actief als startscherm' : 'Je telefoon in een nieuw jasje'; } catch(e){}
  try {
    const bk = JSON.parse(Android.backupState(false));
    const l = bk.last;
    if (bk.busy) { if (ov.bk === 'altijd') per.backup = ['backup', 'Backup is bezig…', 'backup', '']; }
    else if (l && l.t) {
      const failed = Object.keys(l).filter(k => l[k] && l[k].ok === false).length;
      const old = Date.now() - l.t > oud;
      if (ov.bk === 'altijd' || failed || old) per.backup = ['backup', t('Laatste backup: {wanneer}', { wanneer: fmtD(l.t) }) + (failed ? ' · ' + t('{n} mislukt', { n: failed }) : old ? ' · ' + t('al even geleden') : ' ✓'), 'backup', failed ? 'badc' : old ? 'warnc' : ''];
    } else per.backup = ['backup', 'Nog geen backup gemaakt', 'backup', 'warnc'];
  } catch(e){}
  try {
    const a = JSON.parse(Android.alarmState());
    if (a.on && a.nextAt) per.wekker = ['clock', t('Wekker:') + ' ' + fmtDT(a.nextAt, {weekday:'short', hour:'2-digit', minute:'2-digit'}) + (a.station ? ' · ' + a.station.name : ''), 'ralarm', ''];
  } catch(e){}
  try {
    const r = JSON.parse(Android.noteReminders()), end = rgEndOfDay(Date.now()); // einde van vandaag in de app-zone
    const today = Object.values(r).filter(x => x >= Date.now() && x < end).sort();
    if (today.length) per.herinner = ['bell', tn(today.length, '{n} herinnering vandaag, eerst om {tijd}', '{n} herinneringen vandaag, eerst om {tijd}', { tijd: fmtTime(today[0], {hour:'2-digit', minute:'2-digit'}) }), 'notes', ''];
  } catch(e){}
  try {
    const rp = JSON.parse(Android.redialPlanState());
    if (rp.plan && rp.next > Date.now() && rp.next - Date.now() < 7 * 864e5) per.redial = ['phone', t('Auto redial:') + ' ' + (rp.plan.name || rp.plan.number) + ' · ' + fmtDT(rp.next, {weekday:'short', hour:'2-digit', minute:'2-digit'}), 'redial', ''];
  } catch(e){}
  if (rdState && rdState.status === 'playing' && rdState.station) per.radio = ['radio', rdState.station.name + (rdState.title ? ' · ' + rdState.title : ''), 'radio', ''];
  try {
    const st = JSON.parse(Android.selfTestLast());
    const bad = (st.items || []).filter(i => i.status === 'fail').length;
    if (bad) per.zelftest = ['shield', tn(bad, 'Zelftest: {n} probleem', 'Zelftest: {n} problemen'), 'selftest', 'badc'];
  } catch(e){}
  const rows = ov.volg.filter(id => per[id] && !ov.uit.includes(id)).map(id => per[id]);
  homeDicht(rows);
  box.style.display = rows.length ? 'block' : 'none';
  homeOvSync();
  box.innerHTML = rows.map(r => '<button data-t="' + r[2] + '" onclick="jumpTo(this.dataset.t)"><span class=di>' + uiIcon(r[0]) + '</span><span class="dt ' + r[3] + '">' + esc(r[1]) + '</span><span aria-hidden=true>›</span></button>').join('');
}

