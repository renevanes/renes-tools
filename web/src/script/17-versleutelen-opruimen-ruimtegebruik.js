/* ---------- Versleutelen, opruimen, ruimtegebruik ---------- */
function askPassword(title, text, okLabel, onOk){
  askInput(title, text, '', okLabel, onOk);
  const i = $('#modal-input'); i.type = 'password'; i.autocomplete = 'off'; setTimeout(() => i.focus(), 50);
}
function bkRenderSecure(st){
  const on = !!st.encrypted;
  $('#bk-enc-state').textContent = st.encBroken ? '⚠️ Het wachtwoord moet opnieuw worden ingesteld (bijv. na overzetten naar een andere telefoon). Tot dan mislukken de backups.'
    : on ? '🔒 Aan sinds ' + (st.encSince ? fmtDayT(st.encSince) : 'onbekend') + '. Nieuwe backups worden versleuteld.' : 'Uit: backups zijn gewone, leesbare bestanden.';
  $('#bk-enc-state').className = 'note' + (st.encBroken ? ' bad' : '');
  $('#bk-enc-btn').textContent = on ? 'Wachtwoord wijzigen' : 'Wachtwoord instellen';
  $('#bk-enc-off').style.display = on ? 'block' : 'none';
  $('#bk-rotate').checked = !!st.rotate;
  if (st.space) bkRenderSpace(st.space);
}
let bkEncBusy = false;
function bkEncSet(){
  if (bkEncBusy) return;
  const wasOn = !!bkState(true).encrypted;
  askPassword(wasOn ? 'Nieuw wachtwoord' : 'Wachtwoord kiezen', 'Minstens 8 tekens. Schrijf het ergens veilig op: zonder wachtwoord is de backup niet meer te openen.' + (wasOn ? ' Oudere backups blijven met het oude wachtwoord.' : ''), 'Verder', pw => {
    if ((pw || '').length < 8) { toast('Kies een wachtwoord van minstens 8 tekens'); return; }
    setTimeout(() => askPassword('Nog een keer', 'Typ het wachtwoord nog een keer ter controle.', 'Instellen', pw2 => {
      if (pw2 !== pw) { toast('De wachtwoorden zijn niet gelijk'); return; }
      bkEncBusy = true; $('#bk-enc-btn').disabled = true; $('#bk-enc-btn').textContent = 'Bezig…';
      Android.backupSetPassword(pw);
    }), 80);
  });
}
window.onSecure = function(err){
  bkEncBusy = false; $('#bk-enc-btn').disabled = false;
  toast(err ? err : '🔒 Versleutelen staat aan');
  if (current === 'backup') bkRenderSecure(bkState(true));
};
function bkEncOff(){
  askConfirm('Versleutelen uitzetten?', 'Nieuwe backups worden weer gewone, leesbare bestanden. Bestaande versleutelde backups blijven staan en houden hun wachtwoord.', 'Uitzetten', () => {
    Android.backupEncryptOff(); bkRenderSecure(bkState(true)); toast('Versleutelen staat uit');
  });
}
function bkRotate(){ toast('Bezig met tellen…'); Android.backupRotate(true); }
window.onRotate = function(r){
  if (r.error) { toast(r.error); return; }
  if (r.dry) {
    if (!r.count) { toast('Er is niets op te ruimen'); return; }
    askConfirm(r.count + ' oude bestanden verwijderen?', 'Samen ' + fmtB(r.bytes) + '. Alles van de laatste 14 dagen en de nieuwste van elke maand blijven staan.', 'Verwijderen', () => Android.backupRotate(false));
  } else { toast('✓ ' + r.count + ' oude bestanden verwijderd (' + fmtB(r.bytes) + ')'); }
};
function bkSpace(){ $('#bk-space-btn').disabled = true; $('#bk-space-btn').textContent = 'Bezig met tellen…'; Android.backupSpace(); }
window.onSpace = function(r){
  $('#bk-space-btn').disabled = false; $('#bk-space-btn').textContent = 'Opnieuw berekenen';
  if (r.error) { toast(r.error); return; }
  bkRenderSpace(r);
};
function bkRenderSpace(r){
  const d = (r.dirs || []).slice().sort((a, b) => b.bytes - a.bytes);
  $('#bk-space').innerHTML = d.map(x => '<div class=spc><span>' + esc(x.name) + ' <small>' + x.files.toLocaleString('nl-NL') + ' bestanden</small></span><span>' + fmtB(x.bytes) + '</span></div>').join('') +
    '<div class="spc tot"><span>Totaal</span><span>' + fmtB(r.total || 0) + '</span></div><p class=note>Berekend ' + fmtD(r.t) + '</p>';
  $('#bk-space-btn').textContent = 'Opnieuw berekenen';
}
/* versleutelde backup openen */
let arcInfo = null;
window.onArchivePicked = function(){ toast('Bezig met openen…'); Android.archiveOpen(''); };
window.onArchive = function(r){
  if (r.needPw) { askPassword('Wachtwoord', 'Het wachtwoord van deze backup.', 'Openen', pw => { toast('Bezig met ontsleutelen…'); Android.archiveOpen(pw); }); return; }
  if (r.error) { toast(r.error); if (/Verkeerd/.test(r.error)) setTimeout(() => onArchive({needPw: true}), 400); return; }
  arcInfo = r;
  const acts = [];
  if (r.contacts) acts.push(['Contacten terugzetten', () => Android.archiveRestore('contacts', r.contacts)]);
  if (r.notes) acts.push(['Notities terugzetten', () => Android.archiveRestore('notes', r.notes)]);
  if (r.launcher) acts.push(['Startscherm (skin) terugzetten', () => Android.archiveRestore('launcher', r.launcher)]);
  if (r.settings) acts.push(['Instellingen, automatiseringen en zenders terugzetten', () => Android.archiveRestore('settings', r.settings)]);
  if (r.transcripts) acts.push(['Uitgeschreven gesprekken terugzetten', () => Android.archiveRestore('transcripts', r.transcripts)]);
  if (r.music) acts.push(['Herkende muziek terugzetten', () => Android.archiveRestore('music', r.music)]);
  acts.push(['Uitpakken naar de backup-map', () => askConfirm('Uitpakken?', 'De bestanden komen onversleuteld in de map Uitgepakt in je backup-map. Haal ze weg als je ze niet meer nodig hebt.', 'Uitpakken', () => { toast('Bezig met uitpakken…'); Android.archiveUnpack(); })]);
  acts.push(['Sluiten', () => Android.archiveClose()]);
  openSheet('🔒 ' + (r.name || 'Backup'), (r.files || []).length + ' bestanden · ' + fmtB(r.bytes || 0), acts);
};
window.onArchiveUnpacked = function(r){ toast(r.startsWith('ok:') ? '✓ ' + r.slice(3) + ' bestanden uitgepakt naar Uitgepakt/' : r); };

