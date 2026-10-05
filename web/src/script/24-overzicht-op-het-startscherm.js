/* ---------- Overzicht op het startscherm ---------- */
/* Vastgezette notities (bijv. de boodschappenlijst) bovenaan het startscherm van de app. */
function renderNotePins(){
  const wrap = $('#home-notes'); if (!wrap) return;
  let ids = []; try { ids = JSON.parse(Android.notePins('app')) || []; } catch(e){}
  const list = ids.map(id => noteById(id)).filter(Boolean);
  wrap.style.display = list.length && !gsQ ? 'block' : 'none';
  $('#home-notes-tiles').innerHTML = list.map(n => {
    const open = (n.items || []).filter(i => !i.done);
    const sub = (n.items || []).length ? (open.length ? open.length + ' open · ' + open.slice(0, 3).map(i => i.text).join(', ') : 'Alles afgestreept ✓') : (n.text || '').slice(0, 60);
    return '<button class="tile" data-id="' + esc(n.id) + '" onclick="jumpTo(\'note\', () => openNote(this.dataset.id))"><span class="ic" style="background:#E67E22;font-size:24px">📝</span><b>' +
      esc(n.title || 'Notitie') + '</b><small>' + esc(sub) + '</small></button>';
  }).join('');
}
/* "Sinds gisteravond": wat er gebeurde terwijl je sliep (gemiste oproepen, backup, automatiseringen, batterij). */
function renderNight(){
  const box = $('#home-night'); if (!box) return;
  if (gsQ || typeof Android.overnight !== 'function') { box.style.display = 'none'; return; }
  let o = {}; try { o = JSON.parse(Android.overnight()); } catch(e){}
  const rows = [];
  const hm = t => new Date(t).toLocaleTimeString('nl-NL', {hour: '2-digit', minute: '2-digit'});
  if (o.backup) rows.push(['💾', 'Backup om ' + hm(o.backup.t) + (o.backup.failed ? ': ' + o.backup.failed + ' onderdeel mislukt' : ' ✓'), 'backup', o.backup.failed ? 'badc' : '']);
  for (const m of (o.missed || [])) rows.push(['📞', 'Gemist: ' + m.who + ' om ' + hm(m.t), 'calls', 'warnc']);
  for (const a of (o.auto || [])) rows.push(['⚙️', (a.name ? a.name + ': ' : '') + a.what + ' (' + hm(a.t) + ')', 'auto', '']);
  if (o.battery) rows.push(['🔋', 'Android beperkt de app: ' + o.battery, 'selftest', 'badc']);
  if (o.checkBad) rows.push(['🩺', 'Backup-controle: ' + o.checkBad, 'backup', 'badc']);
  if (!rows.length) { box.style.display = 'none'; return; }
  box.style.display = 'block';
  box.innerHTML = '<div style="display:flex;align-items:center;padding:8px 0 2px"><b style="flex:1">🌙 Sinds gisteravond</b><button style="width:auto;border:0;padding:4px 8px;color:var(--chip-on);font-weight:600" onclick="Android.overnightSeen();renderNight()">Gezien</button></div>' +
    rows.map(r => '<button data-t="' + r[2] + '" onclick="jumpTo(this.dataset.t)"><span class=di>' + r[0] + '</span><span class="dt ' + r[3] + '">' + esc(r[1]) + '</span><span aria-hidden=true>›</span></button>').join('');
}
function renderDash(){
  renderNotePins();
  renderNight();
  const box = $('#home-dash'); if (!box) return;
  if (gsQ) { box.style.display = 'none'; return; }
  if (homeCfg().dash === false) { box.style.display = 'none'; return; }
  const rows = [];
  try { const t = $('#tile-skin-sub'); if (t) t.textContent = Android.homeIsDefault() ? 'Actief als startscherm' : 'Je telefoon in een nieuw jasje'; } catch(e){}
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

