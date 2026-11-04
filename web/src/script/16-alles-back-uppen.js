/* ---------- Alles back-uppen ---------- */
const BK_PARTS = { sms: 'Sms-berichten', calls: 'Oproepen', notifications: 'Meldingsgeschiedenis', contacts: 'Contacten', notes: 'Notities', launcher: 'Startscherm (skin)', transcripts: 'Uitgeschreven gesprekken', music: 'Herkende muziek', settings: 'Instellingen, automatiseringen en zenders', routes: 'Routes (GPX)', app: 'Alle appgegevens (voor herinstallatie)' };
/* Privé: staan standaard uit; zonder versleutelen een duidelijke waarschuwing */
const BK_PRIV = { ai: 'Geheugen en gesprekken van de assistent', sleep: 'Slaappraat', checkin: 'Check-in' };
const BK_LABEL = Object.assign({}, BK_PARTS, BK_PRIV);
const BK_PRIV_ZIN = { ai: 'Het geheugen en de gesprekken van de assistent zijn privé', sleep: 'Slaappraat is privé', checkin: 'Check-in is privé (je antwoorden, notities en labels)' };
/* "… gaan/gaat dan niet meer mee": het geheugen en de gesprekken zijn meervoud */
function bkPrivNoLonger(priv){
  if (!priv.length) return '';
  if (TAAL.on) { // Engels: één zin, met "and" tussen de laatste twee
    const ns = priv.map(k => t({ ai: 'het geheugen en de gesprekken van de assistent', sleep: 'Slaappraat', checkin: 'Check-in' }[k]));
    const wie = ns.length === 1 ? ns[0] : ns.slice(0, -1).join(', ') + (priv.includes('ai') ? ', and ' : ' and ') + ns[ns.length - 1];
    return t('{wie} gaan dan niet meer mee, want die zijn privé. Wil je ze toch onversleuteld meenemen, vink ze dan opnieuw aan.', { wie: wie.replace(/^./, c => c.toUpperCase()) });
  }
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
  box.innerHTML = '<p class="note" style="margin-top:0">' + t(r.ok ? '✓ In orde · gecontroleerd {wanneer}' : '✗ Er is iets mis · gecontroleerd {wanneer}', { wanneer: esc(fmtD(r.t)) }) + '</p>' +
    (r.items || []).map(i => '<div class=bkres><b>' + esc(t(i.what)) + '</b><span class="' + (i.ok ? 'good' : 'bad') + '">' + (i.ok ? '✓ ' : '✗ ') + esc(t(i.msg)) + '</span></div>').join('');
}
function bkCheck(){ $('#bk-check-btn').disabled = true; $('#bk-check-btn').textContent = 'Bezig met controleren…'; Android.backupCheckRun(); }
window.onBackupCheck = function(r){ const b = $('#bk-check-btn'); if (b) { b.disabled = false; b.textContent = 'Nu controleren'; } bkCheckRender(r); toast(r.ok ? '✓ Backup is in orde' : 'Backup-controle: er is iets mis'); };
function enterBackup(){
  try { bkCheckRender(JSON.parse(Android.backupCheckLast())); } catch(e){}
  const st = bkState(true);
  $('#bk-nodest').style.display = st.dest ? 'none' : 'block';
  $('#bk-dest').textContent = st.dest ? 'Naar: ' + (st.destName || 'je backup-map') : '';
  $('#bk-parts').innerHTML = Object.keys(BK_PARTS).map(k => '<label class=chk><input type=checkbox data-p="' + k + '"' + (((st.parts || {})[k] === true || (k !== 'notifications' && (st.parts || {})[k] !== false)) ? ' checked' : '') +
    ' onchange="Android.backupSetPart(this.dataset.p, this.checked)"> ' + BK_PARTS[k] + '</label>' +
    (k === 'app' ? '<div class=bk-sub><p class="note' + (st.encrypted ? '' : ' warn') + '" id=bk-app-note>' + bkAppNote(st.encrypted) + '</p></div>' : '')).join('');
  $('#bk-auto').checked = !!st.auto; $('#bk-charging').checked = st.charging !== false;
  $('#bk-next').textContent = st.auto && st.next ? t('Volgende backup: {wanneer}', { wanneer: fmtDT(st.next, {weekday:'short', day:'numeric', month:'short', hour:'2-digit', minute:'2-digit'}) }) : '';
  bkRenderLast(st); bkRenderSecure(st); bkRenderHerstel(st);
  stopBkPoll(); bkPoll = setInterval(bkPollOnce, 1000);
}
function bkAppNote(on){ return on ? uiIcon('lock') + ' ' + t('Alles van de app, ook wachtwoorden, VPN, kluis, opnames en privé-onderdelen, zodat je na opnieuw installeren alles terug hebt. Modellen gaan niet mee (die worden opnieuw gedownload).') : t('Gaat alleen mee in een versleutelde backup: zet hieronder Versleutelen aan.'); }
/* Kun je na opnieuw installeren alles terugzetten? (versleuteld, backup-map, elke nacht, Alle appgegevens, gelukt) */
function bkRenderHerstel(st){
  const h = st.herstel; if (!h || !$('#bk-herstel')) return;
  $('#bk-herstel-st').innerHTML = h.ok ? '<span class=permok>' + t('✓ Compleet voor herinstallatie') + '</span>' + (h.last ? ' · ' + t('laatste complete backup {wanneer}', { wanneer: esc(fmtD(h.last)) }) : '')
    : '<span class="bad">' + t('Nog niet compleet') + '</span>' + (h.last ? ' · ' + t('laatste complete backup {wanneer}', { wanneer: esc(fmtD(h.last)) }) : '');
  $('#bk-herstel-st').className = 'note';
  const rows = [['Backup-map', h.dest], ['Versleutelen', h.enc], ['Alle appgegevens', h.part], ['Elke nacht vanzelf', h.auto], ['Complete backup gemaakt', !!h.last]];
  $('#bk-herstel-list').innerHTML =
    (h.blocked ? '<p class="note warn" id=bk-blocked>' + t('Backups staan stil. {reden}.', { reden: esc(t(h.blocked)) }) + '</p>' +
      '<button class="big" id=bk-blocked-btn onclick="' + (/zet eerst je gegevens terug/.test(h.blocked) ? 'herstelStart()' : 'bkEncSet()') + '">' + (/zet eerst je gegevens terug/.test(h.blocked) ? 'Gegevens terugzetten' : 'Wachtwoord instellen') + '</button>' +
      (/wachtwoord van je backup/.test(h.blocked) ? '<button class="big ghost" style="margin-top:8px" id=bk-plain-btn onclick="bkPlainOk()">Zonder versleutelen verder</button>' : '') : '') +
    rows.map(r => '<div class=bkres><b>' + t(r[0]) + '</b>' + (r[1] ? '<span class=good>✓</span>' : '<span class=bad>' + t('✗ ontbreekt') + '</span>') + '</div>').join('') +
    ((h.missing || []).length ? '<p class=note id=bk-herstel-miss>' + t('Nog te doen: {wat}.', { wat: esc(h.missing.map(x => t(x)).join('; ')) }) + '</p>' : '') +
    (h.stopped ? '<p class="note warn" id=bk-herstel-stopped>De laatste nachtelijke backup werd door Android gestopt voordat alle appgegevens erin zaten (Android geeft een nachtelijke taak beperkte tijd). Maak hem één keer met <b>Nu alles back-uppen</b> en laat de app open tot hij klaar is.</p>' : '') +
    // Grootte: elke nacht een volledig archief erbij. Opruimen kiest Rene zelf.
    (h.part && h.enc && !h.rotate ? '<p class=note id=bk-herstel-size>' + (h.appBytes ? t('Elke nacht komt er een volledige backup bij (de appgegevens waren de laatste keer ongeveer {grootte}). Oude backups worden niet vanzelf weggehaald, dus je backup-map groeit elke nacht.', { grootte: fmtB(h.appBytes) }) : t('Elke nacht komt er een volledige backup bij. Oude backups worden niet vanzelf weggehaald, dus je backup-map groeit elke nacht.')) +
      '</p><button class="big ghost" id=bk-herstel-rotate onclick="bkRotateOn()">Oude backups vanzelf opruimen</button>' : '');
}
/* Na terugzetten zonder backup-sleutel: bewust kiezen voor gewone, leesbare backups */
function bkPlainOk(){
  askConfirm('Zonder versleutelen verder?', 'Backups worden dan weer gewone, leesbare bestanden in je backup-map (sms, oproepen, contacten, notities …), te lezen door iedereen die bij die map kan. Alle appgegevens (met wachtwoorden en sleutels) en de privé-onderdelen gaan dan niet mee. Je kunt later altijd weer een wachtwoord instellen.', 'Zonder versleutelen',
    () => { Android.herstelPlainOk(); toast('Backups gaan verder zonder versleutelen'); enterBackup(); });
}
function bkRotateOn(){ Android.backupSetRotate(true); toast('Na elke gelukte backup worden oude backups opgeruimd (de laatste 14 dagen en de nieuwste per maand blijven)'); enterBackup(); }
function stopBkPoll(){ if (bkPoll) { clearInterval(bkPoll); bkPoll = null; } }
function bkRenderLast(st){
  const l = st.last;
  bkBusy = !!st.busy;
  $('#bk-run').disabled = bkBusy || !st.dest;
  $('#bk-run').textContent = bkBusy ? 'Bezig: ' + (BK_LABEL[st.current] || '…') : 'Nu alles back-uppen';
  if (!l || !l.t) { $('#bk-last').textContent = 'Nog niet gemaakt'; $('#bk-parts-res').innerHTML = ''; return; }
  $('#bk-last').textContent = (l.auto ? 'Automatisch, ' : '') + fmtDT(l.t, {weekday:'long', day:'numeric', month:'long', hour:'2-digit', minute:'2-digit'});
  $('#bk-parts-res').innerHTML = Object.keys(BK_LABEL).filter(k => l[k]).map(k => { const r = l[k];
    return '<div class=bkres><b>' + BK_LABEL[k] + '</b>' + (r.ok ? '<span class=good>✓ ' + (r.msg ? esc(r.msg) : fmtNum(r.count)) + '</span>' : '<span class=bad>✗ ' + esc(r.msg || 'mislukt') + '</span>') + '</div>'; }).join('') +
    (l.all ? '<div class=bkres><span class=bad>' + esc(l.all.msg || '') + '</span></div>' : '') +
    (l.archive ? '<div class=bkres><b>'+uiIcon('lock')+' Versleuteld</b><span class=good>' + esc(l.archive) + '</span></div>' : '') +
    (l.rotated ? '<div class=bkres><b>' + t('Opgeruimd') + '</b><span>' + t('{n} oude bestanden ({grootte})', { n: l.rotated.count, grootte: fmtB(l.rotated.bytes) }) + '</span></div>' : '');
}
function bkPollOnce(){ const st = bkState(); if (st.busy !== bkBusy || st.busy) bkRenderLast(st); const t = $('#tile-backup-sub'); if (t) t.textContent = st.busy ? 'Bezig met back-uppen…' : (st.last && st.last.t ? 'Laatst: ' + fmtD(st.last.t) : 'Alles in één keer bewaren'); }
function bkRun(){ const e = Android.backupStart($('#bk-wa').checked); if (e) toast(e); setTimeout(bkPollOnce, 200); }
window.onBackupDone = function(r){
  const failed = Object.keys(r || {}).filter(k => r[k] && r[k].ok === false).length;
  toast(failed ? tn(failed, 'Backup klaar, {n} onderdeel mislukt', 'Backup klaar, {n} onderdelen mislukt') : t('✓ Alles is back-upt'));
  if (current === 'backup') bkRenderLast(bkState()); bkPollOnce();
};
function bkCount(v, one, more){ v = v || 0; return fmtNum(v) + ' ' + t(v === 1 ? one : more); }
function bkPrivRender(st){
  const box = $('#bk-private'); if (!box || !st) return;
  const p = st.parts || {}, x = st.extra || {}, on = k => p[k] === true;
  const aiInfo = (x.aiMem || x.aiChats) ? bkCount(x.aiMem, 'herinnering', 'herinneringen') + ', ' + bkCount(x.aiChats, 'gesprek', 'gesprekken') + ' · ' + fmtB(x.aiBytes || 0) : 'Nog niets bewaard';
  const slInfo = x.nights ? bkCount(x.nights, 'nacht', 'nachten') + ' · ' + t('tekst {grootte}', { grootte: fmtB(x.sleepText || 0) }) + (x.sleepAudio ? ' · ' + t('geluid {grootte}', { grootte: fmtB(x.sleepAudio) }) : '') : t('Nog geen nachten');
  const ciInfo = x.ciQ || x.ciA ? bkCount(x.ciQ, 'vraag', 'vragen') + ', ' + bkCount(x.ciA, 'antwoord', 'antwoorden') + (x.ciR ? ', ' + bkCount(x.ciR, 'opmerking', 'opmerkingen') : '') + ' · ' + fmtB(x.ciBytes || 0) : 'Nog niets ingevuld';
  const ciWarn = x.ciRBroken ? '<p class="note warn" id="bk-ci-rbroken">De opmerkingen van Check-in waren niet te lezen en staan apart op je telefoon. Vragen en antwoorden gaan gewoon mee; opmerkingen pas weer als je een nieuwe schrijft.</p>' : '';
  box.innerHTML =
    '<label class=chk><input type=checkbox data-p="ai"' + (on('ai') ? ' checked' : '') + ' onchange="bkPrivSet(this)"> ' + BK_PRIV.ai + '</label>' +
    '<div class=bk-sub><p class=note>' + esc(aiInfo) + '</p></div>' +
    '<label class=chk><input type=checkbox data-p="sleep"' + (on('sleep') ? ' checked' : '') + ' onchange="bkPrivSet(this)"> Slaappraat (tekst; geluid optioneel)</label>' +
    '<div class=bk-sub><p class=note>' + esc(slInfo) + '</p>' + (on('sleep') ?
      '<label class=chk><input type=radio name=bk-slaud id=bk-slaud-0' + (!st.sleepAudio ? ' checked' : '') + ' onchange="bkSleepAudio(false)"> Alleen de tekst</label>' +
      '<label class=chk><input type=radio name=bk-slaud id=bk-slaud-1' + (st.sleepAudio ? ' checked' : '') + ' onchange="bkSleepAudio(true)"> ' + t('Met geluid') + (x.sleepAudio ? ' (+' + fmtB(x.sleepAudio) + ')' : '') + '</label>' +
      (st.sleepAudio && x.sleepAudio ? '<p class=note>' + t('Elke backup wordt zo {grootte} groter.', { grootte: fmtB(x.sleepAudio) }) + '</p>' : '') : '') + '</div>' +
    '<label class=chk><input type=checkbox data-p="checkin"' + (on('checkin') ? ' checked' : '') + ' onchange="bkPrivSet(this)"> Check-in (vragen, antwoorden, notities, labels en opmerkingen)</label>' +
    '<div class=bk-sub><p class=note>' + esc(ciInfo) + '</p>' + ciWarn + '</div>' +
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
    askConfirm('Startscherm terugzetten?', t('De indeling van de skin ({wat}) vervangt de huidige. Widgets kunnen niet mee terug; die plaats je opnieuw. De huidige indeling wordt bewaard.', { wat: tn(d.pages, '{n} pagina', '{n} pagina\'s') + ', ' + t('{n} apps en mappen', { n: d.total }) + (d.hasLook ? ', ' + t('look') : '') }), 'Terugzetten', () => {
      restoreWhat = 'onderdelen van het startscherm'; restoreKind = 'launcher';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met terugzetten…');
    });
    return;
  }
  if (d.kind === 'route') {
    const st = d.stats || {};
    askConfirm('Route toevoegen?', t('"{titel}": {km} km, {n} punten.', { titel: d.title, km: fmtKm(st.distance || 0), n: d.points }), 'Toevoegen', () => {
      restoreWhat = 'route'; restoreKind = 'route';
      const e = Android.restoreApply(); if (e) toast(e);
    });
    return;
  }
  if (d.kind === 'ai') {
    if (!d.fresh) { Android.restoreCancel(); toast(t(d.memFull || d.chatFull ? 'Alles uit deze backup staat er al of past niet meer' : 'Alles uit deze backup staat er al')); return; }
    const mem = t('Geheugen: {n} nieuw', { n: d.memFresh }) + (d.memDup ? ', ' + t('{n} staan er al', { n: d.memDup }) : '') + (d.memFull ? ', ' + t('{n} passen niet meer (het geheugen is vol)', { n: d.memFull }) : '') + '.';
    const chats = t('Gesprekken: {n} nieuw', { n: d.chatFresh }) + (d.chatDup ? ', ' + t('{n} staan er al', { n: d.chatDup }) : '') + (d.chatFull ? ', ' + t('{n} passen niet (hooguit 100)', { n: d.chatFull }) : '') + '.';
    askConfirm('Assistent terugzetten?', mem + '\n' + chats + '\n\n' + t('Er wordt niets overschreven of verwijderd.') + ((d.sample || []).length ? '\n\n' + d.sample.join('\n') : ''), 'Toevoegen', () => {
      restoreWhat = 'herinneringen en gesprekken'; restoreKind = 'ai';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met toevoegen…');
    }, () => Android.restoreCancel());
    return;
  }
  if (d.kind === 'checkin') {
    if (!d.fresh) { Android.restoreCancel(); toast('Alles uit deze backup staat er al'); return; }
    askConfirm('Check-in terugzetten?', t('Vragen: {n} nieuw', { n: d.qFresh }) + (d.qDup ? ', ' + t('{n} staan er al', { n: d.qDup }) : '') + '.\n' + t('Antwoorden: {n} nieuw', { n: d.aFresh }) + (d.aDup ? ', ' + t('{n} staan er al', { n: d.aDup }) : '') + '.' +
      (d.rTotal ? '\n' + t('Opmerkingen: {n} nieuw', { n: d.rFresh }) + (d.rDup ? ', ' + t('{n} staan er al', { n: d.rDup }) : '') + (d.rFull ? ', ' + t('{n} passen niet meer (hooguit 5000)', { n: d.rFull }) : '') + '.' : '') +
      (d.bad ? '\n' + t('{n} niet leesbaar (overgeslagen).', { n: d.bad }) : '') + '\n\n' + t('Er wordt niets overschreven of verwijderd. Teruggezette vragen staan eerst uit: zet ze aan als je weer meldingen wilt.') +
      ((d.sample || []).length ? '\n\n' + d.sample.join('\n') : ''), 'Toevoegen', () => {
      restoreWhat = d.rTotal ? 'vragen, antwoorden en opmerkingen' : 'vragen en antwoorden'; restoreKind = 'checkin';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met toevoegen…');
    }, () => Android.restoreCancel());
    return;
  }
  if (d.kind === 'sleep') {
    const skip = [d.dup ? t('{n} al op deze telefoon', { n: d.dup }) : '', d.running ? t('de nacht die nu loopt blijft zoals hij is') : '',
      d.old ? t('{n} ouder dan je bewaartermijn van {dagen} dagen (kies bij Slaappraat 30 dagen om die ook terug te zetten)', { n: d.old, dagen: d.keep }) : ''].filter(Boolean);
    if (!d.fresh) { Android.restoreCancel(); toast(t('Geen nachten toe te voegen') + (skip.length ? ': ' + skip.join('; ') : '')); return; }
    const snd = d.audio ? tn(d.audio, 'Met geluid: {n} stukje ({grootte}).', 'Met geluid: {n} stukjes ({grootte}).', { grootte: fmtB(d.audioBytes || 0) })
      : d.noSpace ? t('Te weinig ruimte op de telefoon voor het geluid: alleen de tekst.')
      : d.withAudio ? (d.fromArchive ? t('Het geluid kon niet uit de versleutelde backup gehaald worden: alleen de tekst.') : t('Het geluid is niet gevonden (het hoort in de map Slaappraat van je backup-map): alleen de tekst.'))
      : t('Alleen de tekst (deze backup is zonder geluid gemaakt).');
    askConfirm(tn(d.fresh, '{n} nacht toevoegen?', '{n} nachten toevoegen?'), (d.sample || []).map(x => fmtD(x)).join(', ') + (d.fresh > (d.sample || []).length ? ', …' : '') + '\n\n' + snd +
      (skip.length ? '\n' + t('Niet toegevoegd: {wat}.', { wat: skip.join('; ') }) : '') + '\n\n' + t('Bestaande nachten worden nooit overschreven.'), 'Toevoegen', () => {
      restoreWhat = 'nachten'; restoreKind = 'sleep';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met toevoegen…');
    }, () => Android.restoreCancel()); // niet doen: ontsleuteld geluid meteen uit de cache
    return;
  }
  const what = { contacts: 'contacten', notes: 'notities', settings: 'instellingen', transcripts: 'uitgeschreven gesprekken', music: 'herkende nummers' }[d.kind] || 'onderdelen';
  if (d.kind === 'settings' && d.fresh) {
    const parts = [d.rules ? tn(d.rules, '{n} automatisering', '{n} automatiseringen') : '', d.favs ? tn(d.favs, '{n} zender', '{n} zenders') : '', d.alarm ? t('radiowekker') : '', d.plan ? t('geplande Auto redial') : '', d.reminders ? tn(d.reminders, '{n} herinnering', '{n} herinneringen') : ''].filter(Boolean);
    askConfirm('Instellingen terugzetten?', (parts.length ? t('Toegevoegd wordt wat hier nog ontbreekt: {wat}.', { wat: parts.join(', ') }) : t('Toegevoegd wordt wat hier nog ontbreekt.')) + ' ' + t('Bestaande instellingen blijven zoals ze zijn.'), 'Terugzetten', () => {
      restoreWhat = 'instellingen'; restoreKind = 'settings';
      const e = Android.restoreApply(); if (e) toast(e); else toast('Bezig met terugzetten…');
    });
    return;
  }
  if (!d.fresh) { toast(t('Alle {n} {wat} staan er al; er is niets toe te voegen', { n: d.total, wat: t(what) })); return; }
  askConfirm(t('{n} {wat} toevoegen?', { n: d.fresh, wat: t(what) }), t('In het bestand: {n}. Al aanwezig (worden overgeslagen): {dubbel}.', { n: d.total, dubbel: d.dup }) + '\n\n' +
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
    toast(restoreKind === 'route' && r === 'ok:0' ? t('Deze route staat er al') : t('✓ {n} {wat} toegevoegd', { n, wat: t(restoreKind === 'sleep' && n === 1 ? 'nacht' : restoreWhat) }));
    if (restoreKind === 'ai' && typeof aiLoad === 'function') try { aiLoad(); } catch(e){}
    if (restoreKind === 'sleep' && current === 'sleep' && typeof enterSleep === 'function') enterSleep();
    if ((restoreKind === 'ai' || restoreKind === 'sleep' || restoreKind === 'checkin') && current === 'backup') bkPrivRender(bkState(true));
    if (restoreKind === 'checkin' && typeof ciAfter === 'function') ciAfter();
    if (restoreKind === 'notes') { notes = null; notesBroken = false; refreshNotesTile(); }
    if (restoreKind === 'route' && current === 'tracks') enterTracks();
    if (restoreKind === 'settings') { webStoreRestore(); if (typeof rgReload === 'function') rgReload(); if (typeof renderHome === 'function') try { renderHome(); } catch(e){} }
  } else toast(r);
};

