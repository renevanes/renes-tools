/* ---------- Alles back-uppen ---------- */
const BK_PARTS = { sms: 'Sms-berichten', calls: 'Oproepen', notifications: 'Meldingsgeschiedenis', contacts: 'Contacten', notes: 'Notities', launcher: 'Startscherm (skin)', transcripts: 'Uitgeschreven gesprekken', music: 'Herkende muziek', settings: 'Instellingen, automatiseringen en zenders', routes: 'Routes (GPX)' };
/* Privé: staan standaard uit; zonder versleutelen een duidelijke waarschuwing */
const BK_PRIV = { ai: 'Geheugen en gesprekken van de assistent', sleep: 'Slaappraat', checkin: 'Check-in' };
const BK_LABEL = Object.assign({}, BK_PARTS, BK_PRIV);
const BK_PRIV_ZIN = { ai: 'Het geheugen en de gesprekken van de assistent zijn privé', sleep: 'Slaappraat is privé', checkin: 'Check-in is privé (je antwoorden, notities en labels)' };
/* "… gaan/gaat dan niet meer mee": het geheugen en de gesprekken zijn meervoud */
function bkPrivNoLonger(priv){
  if (!priv.length) return '';
  const N = { ai: 'het geheugen en de gesprekken van de assistent', sleep: 'Slaappraat', checkin: 'Check-in' }, ns = priv.map(k => N[k]), ai = priv.includes('ai');
  // Met de assistent erbij staat er al "en" in de naam: dan met komma's ("…, en Slaappraat,")
  const wie = (ns.length === 1 ? ns[0] : ai ? ns.slice(0, -1).join(', ') + ', en ' + ns[ns.length - 1] + ',' : ns.slice(0, -1).join(', ') + ' en ' + ns[ns.length - 1]).replace(/^./, c => c.toUpperCase());
  const mv = priv.length > 1 || ai;
  return wie + (mv ? ' gaan' : ' gaat') + ' dan niet meer mee, want ' + (mv ? 'die zijn' : 'dat is') + ' privé. Wil je ' + (mv ? 'ze' : 'het') + ' toch onversleuteld meenemen, vink ' + (mv ? 'ze' : 'het') + ' dan opnieuw aan.';
}
let bkPoll = null, bkBusy = false;
function bkState(full){ try { return JSON.parse(Android.backupState(!!full)); } catch(e){ return {}; } }
/* ---------- backup-controle (proef-terugzetten) ---------- */
function bkCheckRender(r){
  const box = $('#bk-check'); if (!box || !r || !r.t) return;
  box.innerHTML = '<p class="note" style="margin-top:0">' + (r.ok ? '✓ In orde' : '✗ Er is iets mis') + ' · gecontroleerd ' + esc(fmtD(r.t)) + '</p>' +
    (r.items || []).map(i => '<div class=bkres><b>' + esc(i.what) + '</b><span class="' + (i.ok ? 'good' : 'bad') + '">' + (i.ok ? '✓ ' : '✗ ') + esc(i.msg) + '</span></div>').join('');
}
function bkCheck(){ $('#bk-check-btn').disabled = true; $('#bk-check-btn').textContent = 'Bezig met controleren…'; Android.backupCheckRun(); }
window.onBackupCheck = function(r){ const b = $('#bk-check-btn'); if (b) { b.disabled = false; b.textContent = 'Nu controleren'; } bkCheckRender(r); toast(r.ok ? '✓ Backup is in orde' : 'Backup-controle: er is iets mis'); };
function enterBackup(){
  try { bkCheckRender(JSON.parse(Android.backupCheckLast())); } catch(e){}
  const st = bkState(true);
  $('#bk-nodest').style.display = st.dest ? 'none' : 'block';
  $('#bk-dest').textContent = st.dest ? 'Naar: ' + (st.destName || 'je backup-map') : '';
  $('#bk-parts').innerHTML = Object.keys(BK_PARTS).map(k => '<label class=chk><input type=checkbox data-p="' + k + '"' + (((st.parts || {})[k] === true || (k !== 'notifications' && (st.parts || {})[k] !== false)) ? ' checked' : '') +
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
  $('#bk-run').textContent = bkBusy ? 'Bezig: ' + (BK_LABEL[st.current] || '…') : 'Nu alles back-uppen';
  if (!l || !l.t) { $('#bk-last').textContent = 'Nog niet gemaakt'; $('#bk-parts-res').innerHTML = ''; return; }
  $('#bk-last').textContent = (l.auto ? 'Automatisch, ' : '') + new Date(l.t).toLocaleString('nl-NL', {weekday:'long', day:'numeric', month:'long', hour:'2-digit', minute:'2-digit'});
  $('#bk-parts-res').innerHTML = Object.keys(BK_LABEL).filter(k => l[k]).map(k => { const r = l[k];
    return '<div class=bkres><b>' + BK_LABEL[k] + '</b>' + (r.ok ? '<span class=good>✓ ' + (r.msg ? esc(r.msg) : r.count.toLocaleString('nl-NL')) + '</span>' : '<span class=bad>✗ ' + esc(r.msg || 'mislukt') + '</span>') + '</div>'; }).join('') +
    (l.all ? '<div class=bkres><span class=bad>' + esc(l.all.msg || '') + '</span></div>' : '') +
    (l.archive ? '<div class=bkres><b>'+uiIcon('lock')+' Versleuteld</b><span class=good>' + esc(l.archive) + '</span></div>' : '') +
    (l.rotated ? '<div class=bkres><b>Opgeruimd</b><span>' + l.rotated.count + ' oude bestanden (' + fmtB(l.rotated.bytes) + ')</span></div>' : '');
}
function bkPollOnce(){ const st = bkState(); if (st.busy !== bkBusy || st.busy) bkRenderLast(st); const t = $('#tile-backup-sub'); if (t) t.textContent = st.busy ? 'Bezig met back-uppen…' : (st.last && st.last.t ? 'Laatst: ' + fmtD(st.last.t) : 'Alles in één keer bewaren'); }
function bkRun(){ const e = Android.backupStart($('#bk-wa').checked); if (e) toast(e); setTimeout(bkPollOnce, 200); }
window.onBackupDone = function(r){
  const failed = Object.keys(r || {}).filter(k => r[k] && r[k].ok === false).length;
  toast(failed ? 'Backup klaar, ' + failed + ' onderdeel mislukt' : '✓ Alles is back-upt');
  if (current === 'backup') bkRenderLast(bkState()); bkPollOnce();
};
function bkCount(v, one, more){ v = v || 0; return v.toLocaleString('nl-NL') + ' ' + (v === 1 ? one : more); }
function bkPrivRender(st){
  const box = $('#bk-private'); if (!box || !st) return;
  const p = st.parts || {}, x = st.extra || {}, on = k => p[k] === true;
  const aiInfo = (x.aiMem || x.aiChats) ? bkCount(x.aiMem, 'herinnering', 'herinneringen') + ', ' + bkCount(x.aiChats, 'gesprek', 'gesprekken') + ' · ' + fmtB(x.aiBytes || 0) : 'Nog niets bewaard';
  const slInfo = x.nights ? bkCount(x.nights, 'nacht', 'nachten') + ' · tekst ' + fmtB(x.sleepText || 0) + (x.sleepAudio ? ' · geluid ' + fmtB(x.sleepAudio) : '') : 'Nog geen nachten';
  const ciInfo = x.ciQ || x.ciA ? bkCount(x.ciQ, 'vraag', 'vragen') + ', ' + bkCount(x.ciA, 'antwoord', 'antwoorden') + ' · ' + fmtB(x.ciBytes || 0) : 'Nog niets ingevuld';
  box.innerHTML =
    '<label class=chk><input type=checkbox data-p="ai"' + (on('ai') ? ' checked' : '') + ' onchange="bkPrivSet(this)"> ' + BK_PRIV.ai + '</label>' +
    '<div class=bk-sub><p class=note>' + esc(aiInfo) + '</p></div>' +
    '<label class=chk><input type=checkbox data-p="sleep"' + (on('sleep') ? ' checked' : '') + ' onchange="bkPrivSet(this)"> Slaappraat (tekst; geluid optioneel)</label>' +
    '<div class=bk-sub><p class=note>' + esc(slInfo) + '</p>' + (on('sleep') ?
      '<label class=chk><input type=radio name=bk-slaud id=bk-slaud-0' + (!st.sleepAudio ? ' checked' : '') + ' onchange="bkSleepAudio(false)"> Alleen de tekst</label>' +
      '<label class=chk><input type=radio name=bk-slaud id=bk-slaud-1' + (st.sleepAudio ? ' checked' : '') + ' onchange="bkSleepAudio(true)"> Met geluid' + (x.sleepAudio ? ' (+' + fmtB(x.sleepAudio) + ')' : '') + '</label>' +
      (st.sleepAudio && x.sleepAudio ? '<p class=note>Elke backup wordt zo ' + fmtB(x.sleepAudio) + ' groter.</p>' : '') : '') + '</div>' +
    '<label class=chk><input type=checkbox data-p="checkin"' + (on('checkin') ? ' checked' : '') + ' onchange="bkPrivSet(this)"> Check-in (vragen, antwoorden, notities en labels)</label>' +
    '<div class=bk-sub><p class=note>' + esc(ciInfo) + '</p></div>' +
    (on('ai') || on('sleep') || on('checkin') ? (st.encrypted ? '<p class=note id=bk-priv-enc>' + uiIcon('lock') + ' Gaat versleuteld mee, in het archief van Alles back-uppen.</p>'
      : '<p class="note warn" id=bk-priv-warn>Let op: versleutelen staat uit. Dit komt als leesbaar bestand in je backup-map, te lezen door iedereen die bij die map kan (bijv. via OneDrive). Zet hieronder Versleutelen aan om het te beschermen.</p>') : '');
}
function bkPrivSet(el){
  const k = el.dataset.p;
  if (el.checked && !bkState(false).encrypted) {
    el.checked = false; // pas aan na bevestigen
    askConfirm('Onversleuteld meenemen?', BK_PRIV_ZIN[k] + '. Versleutelen staat uit, dus dit komt als leesbaar bestand in je backup-map. Beter: zet eerst Versleutelen aan (onderaan dit scherm).', 'Toch meenemen',
      () => { Android.backupSetPart('plain:' + k, true); Android.backupSetPart(k, true); bkPrivRender(bkState(true)); }); // toestemming alleen hier
    return;
  }
  Android.backupSetPart(k, el.checked); bkPrivRender(bkState(true));
}
function bkSleepAudio(on){ Android.backupSetPart('sleepAudio', on); bkPrivRender(bkState(true)); }
function bkSaveAuto(){ Android.backupSetAuto($('#bk-auto').checked, $('#bk-charging').checked); enterBackup(); }
window.onRestorePreview = function(d){
  if (d.error) { toast(d.error); return; }
  if (d.kind === 'launcher') {
    askConfirm('Startscherm terugzetten?', 'De indeling van de skin (' + d.pages + (d.pages === 1 ? ' pagina' : ' pagina\'s') + ', ' + d.total + ' apps en mappen' + (d.hasLook ? ', look' : '') + ') vervangt de huidige. Widgets kunnen niet mee terug; die plaats je opnieuw. De huidige indeling wordt bewaard.', 'Terugzetten', () => {
      restoreWhat = 'onderdelen van het startscherm'; restoreKind = 'launcher';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met terugzetten…');
    });
    return;
  }
  if (d.kind === 'route') {
    const st = d.stats || {};
    askConfirm('Route toevoegen?', '"' + d.title + '": ' + fmtKm(st.distance || 0) + ' km, ' + d.points + ' punten.', 'Toevoegen', () => {
      restoreWhat = 'route'; restoreKind = 'route';
      const e = Android.restoreApply(); if (e) toast(e);
    });
    return;
  }
  if (d.kind === 'ai') {
    if (!d.fresh) { Android.restoreCancel(); toast('Alles uit deze backup staat er al' + (d.memFull || d.chatFull ? ' of past niet meer' : '')); return; }
    const mem = 'Geheugen: ' + d.memFresh + ' nieuw' + (d.memDup ? ', ' + d.memDup + ' staan er al' : '') + (d.memFull ? ', ' + d.memFull + ' passen niet meer (het geheugen is vol)' : '') + '.';
    const chats = 'Gesprekken: ' + d.chatFresh + ' nieuw' + (d.chatDup ? ', ' + d.chatDup + ' staan er al' : '') + (d.chatFull ? ', ' + d.chatFull + ' passen niet (hooguit 100)' : '') + '.';
    askConfirm('Assistent terugzetten?', mem + '\n' + chats + '\n\nEr wordt niets overschreven of verwijderd.' + ((d.sample || []).length ? '\n\n' + d.sample.join('\n') : ''), 'Toevoegen', () => {
      restoreWhat = 'herinneringen en gesprekken'; restoreKind = 'ai';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met toevoegen…');
    }, () => Android.restoreCancel());
    return;
  }
  if (d.kind === 'checkin') {
    if (!d.fresh) { Android.restoreCancel(); toast('Alles uit deze backup staat er al'); return; }
    askConfirm('Check-in terugzetten?', 'Vragen: ' + d.qFresh + ' nieuw' + (d.qDup ? ', ' + d.qDup + ' staan er al' : '') + '.\nAntwoorden: ' + d.aFresh + ' nieuw' + (d.aDup ? ', ' + d.aDup + ' staan er al' : '') + '.' +
      (d.bad ? '\n' + d.bad + ' niet leesbaar (overgeslagen).' : '') + '\n\nEr wordt niets overschreven of verwijderd. Teruggezette vragen staan eerst uit: zet ze aan als je weer meldingen wilt.' +
      ((d.sample || []).length ? '\n\n' + d.sample.join('\n') : ''), 'Toevoegen', () => {
      restoreWhat = 'vragen en antwoorden'; restoreKind = 'checkin';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met toevoegen…');
    }, () => Android.restoreCancel());
    return;
  }
  if (d.kind === 'sleep') {
    const skip = [d.dup ? d.dup + ' al op deze telefoon' : '', d.running ? 'de nacht die nu loopt blijft zoals hij is' : '',
      d.old ? d.old + ' ouder dan je bewaartermijn van ' + d.keep + ' dagen (kies bij Slaappraat 30 dagen om die ook terug te zetten)' : ''].filter(Boolean);
    if (!d.fresh) { Android.restoreCancel(); toast('Geen nachten toe te voegen' + (skip.length ? ': ' + skip.join('; ') : '')); return; }
    const snd = d.audio ? 'Met geluid: ' + d.audio + (d.audio === 1 ? ' stukje' : ' stukjes') + ' (' + fmtB(d.audioBytes || 0) + ').'
      : d.noSpace ? 'Te weinig ruimte op de telefoon voor het geluid: alleen de tekst.'
      : d.withAudio ? (d.fromArchive ? 'Het geluid kon niet uit de versleutelde backup gehaald worden: alleen de tekst.' : 'Het geluid is niet gevonden (het hoort in de map Slaappraat van je backup-map): alleen de tekst.')
      : 'Alleen de tekst (deze backup is zonder geluid gemaakt).';
    askConfirm(d.fresh + (d.fresh === 1 ? ' nacht' : ' nachten') + ' toevoegen?', (d.sample || []).map(t => fmtD(t)).join(', ') + (d.fresh > (d.sample || []).length ? ', …' : '') + '\n\n' + snd +
      (skip.length ? '\nNiet toegevoegd: ' + skip.join('; ') + '.' : '') + '\n\nBestaande nachten worden nooit overschreven.', 'Toevoegen', () => {
      restoreWhat = 'nachten'; restoreKind = 'sleep';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met toevoegen…');
    }, () => Android.restoreCancel()); // niet doen: ontsleuteld geluid meteen uit de cache
    return;
  }
  const what = { contacts: 'contacten', notes: 'notities', settings: 'instellingen', transcripts: 'uitgeschreven gesprekken', music: 'herkende nummers' }[d.kind] || 'onderdelen';
  if (d.kind === 'settings' && d.fresh) {
    const parts = [d.rules ? d.rules + ' automatisering' + (d.rules === 1 ? '' : 'en') : '', d.favs ? d.favs + ' zender' + (d.favs === 1 ? '' : 's') : '', d.alarm ? 'radiowekker' : '', d.plan ? 'geplande Auto redial' : '', d.reminders ? d.reminders + ' herinnering' + (d.reminders === 1 ? '' : 'en') : ''].filter(Boolean);
    askConfirm('Instellingen terugzetten?', 'Toegevoegd wordt wat hier nog ontbreekt' + (parts.length ? ': ' + parts.join(', ') : '') + '. Bestaande instellingen blijven zoals ze zijn.', 'Terugzetten', () => {
      restoreWhat = 'instellingen'; restoreKind = 'settings';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met terugzetten…');
    });
    return;
  }
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
    const n = +r.slice(3);
    toast(restoreKind === 'route' && r === 'ok:0' ? 'Deze route staat er al' : '✓ ' + n + ' ' + (restoreKind === 'sleep' && n === 1 ? 'nacht' : restoreWhat) + ' toegevoegd');
    if (restoreKind === 'ai' && typeof aiLoad === 'function') try { aiLoad(); } catch(e){}
    if (restoreKind === 'sleep' && current === 'sleep' && typeof enterSleep === 'function') enterSleep();
    if ((restoreKind === 'ai' || restoreKind === 'sleep' || restoreKind === 'checkin') && current === 'backup') bkPrivRender(bkState(true));
    if (restoreKind === 'checkin' && typeof ciAfter === 'function') ciAfter();
    if (restoreKind === 'notes') { notes = null; notesBroken = false; refreshNotesTile(); }
    if (restoreKind === 'route' && current === 'tracks') enterTracks();
    if (restoreKind === 'settings') { webStoreRestore(); if (typeof renderHome === 'function') try { renderHome(); } catch(e){} }
  } else toast(r);
};

