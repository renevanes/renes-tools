/* ---------- Versleutelen, opruimen, ruimtegebruik ---------- */
function askPassword(title, text, okLabel, onOk){
  askInput(title, text, '', okLabel, onOk);
  const i = $('#modal-input'); i.type = 'password'; i.autocomplete = 'off'; setTimeout(() => i.focus(), 50);
}
/* Versleutelen staat aan, maar een losse export is een gewoon, leesbaar bestand: eerst even vragen.
   Geeft true als het meteen mag; anders vraagt het en roept daarna retry() aan. */
let plainAllowed = false;
function plainOk(what, retry){
  if (plainAllowed || !bkState(false).encrypted) return true;
  askConfirm('Onversleuteld opslaan?', t('{wat} komt als gewoon, leesbaar bestand in je backup-map, ook al staat versleutelen aan. Versleuteld bewaren gaat via Alles back-uppen.', { wat: t(what) }), 'Toch opslaan',
    () => { plainAllowed = true; try { retry(); } finally { plainAllowed = false; } });
  return false;
}
function bkRenderSecure(st){
  const on = !!st.encrypted;
  $('#bk-enc-state').textContent = st.encBroken ? 'Het wachtwoord moet opnieuw worden ingesteld (bijv. na overzetten naar een andere telefoon). Tot dan mislukken de backups.'
    : on ? t('Aan sinds {wanneer}. Nieuwe backups worden versleuteld.', { wanneer: st.encSince ? fmtDayT(st.encSince) : t('onbekend') }) : t('Uit: backups zijn gewone, leesbare bestanden.');
  $('#bk-enc-state').className = 'note' + (st.encBroken ? ' bad' : '');
  $('#bk-enc-btn').textContent = on ? 'Wachtwoord wijzigen' : 'Wachtwoord instellen';
  $('#bk-enc-off').style.display = on ? 'block' : 'none';
  $('#bk-rotate').checked = !!st.rotate;
  if (st.parts) bkPrivRender(st); // de waarschuwing bij Privé hangt af van versleutelen
  if (st.parts && bkOn() && $('#bk-app-note')) { const n = $('#bk-app-note'); n.className = 'note' + (on ? '' : ' warn'); n.innerHTML = bkAppNote(on); }
  if (st.herstel) bkRenderHerstel(st);
  if (st.space) bkRenderSpace(st.space);
  bkBusyUi();
}
let bkEncBusy = false;
function bkEncSet(){
  if (bkEncBusy) return;
  const wasOn = !!bkState(true).encrypted;
  askPassword(wasOn ? 'Nieuw wachtwoord' : 'Wachtwoord kiezen', t('Minstens 8 tekens. Schrijf het ergens veilig op: zonder wachtwoord is de backup niet meer te openen.') + (wasOn ? ' ' + t('Oudere backups blijven met het oude wachtwoord.') : ''), 'Verder', pw => {
    if ((pw || '').length < 8) { toast('Kies een wachtwoord van minstens 8 tekens'); return; }
    setTimeout(() => askPassword('Nog een keer', 'Typ het wachtwoord nog een keer ter controle.', 'Instellen', pw2 => {
      if (pw2 !== pw) { toast('De wachtwoorden zijn niet gelijk'); return; }
      bkEncBusy = true; $('#bk-enc-btn').disabled = true; $('#bk-enc-btn').textContent = 'Bezig…';
      uiBezig(t('Wachtwoord instellen…'), 'onSecure'); Android.backupSetPassword(pw);
    }), 80);
  });
}
window.onSecure = function(err){
  bkEncBusy = false; $('#bk-enc-btn').disabled = false;
  toast(err ? err : 'Versleutelen staat aan');
  if (bkOn()) { const st = bkState(true); bkRenderSecure(st); bkSums(st); }
};
function bkEncOff(){
  const p = bkState(true).parts || {}, priv = ['ai', 'sleep', 'checkin'].filter(k => p[k] === true);
  askConfirm('Versleutelen uitzetten?', t('Nieuwe backups worden weer gewone, leesbare bestanden. Bestaande versleutelde backups blijven staan en houden hun wachtwoord.') +
    (priv.length ? '\n\n' + bkPrivNoLonger(priv) : ''), 'Uitzetten', () => {
    Android.backupEncryptOff(); // zet ook de privé-onderdelen uit
    const st = bkState(true); bkRenderSecure(st); bkSums(st); toast(t(priv.length ? 'Versleutelen staat uit; privé-onderdelen gaan niet meer mee' : 'Versleutelen staat uit'));
  });
}
function bkRotate(){ uiBezig(t('Bezig met tellen…'), 'onRotate'); Android.backupRotate(true); }
window.onRotate = function(r){
  if (r.error) { toast(r.error); return; }
  if (r.dry) {
    if (!r.count) { toast('Er is niets op te ruimen'); return; }
    askConfirm(t('{n} oude bestanden verwijderen?', { n: r.count }), t('Samen {grootte}. Alles van de laatste 14 dagen en de nieuwste van elke maand blijven staan.', { grootte: fmtB(r.bytes) }), 'Verwijderen', () => { uiBezig(t('Oude bestanden verwijderen…'), 'onRotate'); Android.backupRotate(false); });
  } else { toast(t('✓ {n} oude bestanden verwijderd ({grootte})', { n: r.count, grootte: fmtB(r.bytes) })); }
};
function bkSpace(){ $('#bk-space-btn').disabled = true; $('#bk-space-btn').textContent = 'Bezig met tellen…'; Android.backupSpace(); }
window.onSpace = function(r){
  $('#bk-space-btn').disabled = false; $('#bk-space-btn').textContent = 'Opnieuw berekenen';
  if (r.error) { toast(r.error); return; }
  bkRenderSpace(r);
};
function bkRenderSpace(r){
  const d = (r.dirs || []).slice().sort((a, b) => b.bytes - a.bytes);
  $('#bk-space').innerHTML = d.map(x => '<div class=spc><span>' + esc(x.name) + ' <small>' + t('{n} bestanden', { n: fmtNum(x.files) }) + '</small></span><span>' + fmtB(x.bytes) + '</span></div>').join('') +
    '<div class="spc tot"><span>' + t('Totaal') + '</span><span>' + fmtB(r.total || 0) + '</span></div><p class=note>' + t('Berekend {wanneer}', { wanneer: fmtD(r.t) }) + '</p>';
  $('#bk-space-btn').textContent = 'Opnieuw berekenen';
}
/* versleutelde backup openen */
window.onArchivePicked = function(){ uiBezig(t('Bezig met openen…'), 'onArchive'); Android.archiveOpen(''); };
window.onArchive = function(r){
  if (r.needPw) { askPassword('Wachtwoord', 'Het wachtwoord van deze backup.', 'Openen', pw => { uiBezig(t('Bezig met ontsleutelen…'), 'onArchive'); Android.archiveOpen(pw); }); return; }
  if (r.error) { toast(r.error); if (/Verkeerd/.test(r.error)) setTimeout(() => onArchive({needPw: true}), 400); return; }
  const acts = [];
  if (r.contacts) acts.push(['Contacten terugzetten', () => arcRestore('contacts', r.contacts)]);
  if (r.notes) acts.push(['Notities terugzetten', () => arcRestore('notes', r.notes)]);
  if (r.launcher) acts.push(['Startscherm (skin) terugzetten', () => arcRestore('launcher', r.launcher)]);
  if (r.settings) acts.push(['Instellingen, automatiseringen en zenders terugzetten', () => arcRestore('settings', r.settings)]);
  if (r.transcripts) acts.push(['Uitgeschreven gesprekken terugzetten', () => arcRestore('transcripts', r.transcripts)]);
  if (r.music) acts.push(['Herkende muziek terugzetten', () => arcRestore('music', r.music)]);
  if (r.ai) acts.push(['Geheugen en gesprekken van de assistent terugzetten', () => arcRestore('ai', r.ai)]);
  if (r.sleep) acts.push(['Slaappraat terugzetten', () => arcRestore('sleep', r.sleep)]);
  if (r.checkin) acts.push(['Check-in terugzetten', () => arcRestore('checkin', r.checkin)]);
  acts.push(['Uitpakken naar de backup-map', () => askConfirm('Uitpakken?', 'De bestanden komen onversleuteld in de map Uitgepakt in je backup-map. Haal ze weg als je ze niet meer nodig hebt.', 'Uitpakken', () => { uiBezig(t('Bezig met uitpakken…'), 'onArchiveUnpacked'); Android.archiveUnpack(); }, () => Android.archiveClose())]);
  acts.push(['Sluiten', () => Android.archiveClose()]);
  // Dicht zonder keuze (terug of ernaast tikken): de sleutel meteen vergeten
  openSheet('🔒 ' + (r.name || t('Backup')), t('{n} bestanden · {grootte}', { n: (r.files || []).length, grootte: fmtB(r.bytes || 0) }), acts, () => Android.archiveClose());
};
function arcRestore(soort, e){ uiBezig(t('Bezig met openen…'), 'onRestorePreview'); Android.archiveRestore(soort, e); }
window.onArchiveUnpacked = function(r){ toast(r.startsWith('ok:') ? t('✓ {n} bestanden uitgepakt naar Uitgepakt/', { n: r.slice(3) }) : r); };

