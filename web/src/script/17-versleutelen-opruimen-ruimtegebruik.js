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
  bkKeepRender(st);
  bkBlokRender(st);
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
/* Oude backups opruimen: hoeveel volledige backups er blijven (Java: Rotate.KEEP_CHOICES, 0 = 14 dagen + per maand) */
function bkKeep(st){ const k = Number((st || {}).keep); return [0, 3, 7, 14, 30].includes(k) ? k : 7; }
function bkKeepTekst(k){
  return k ? t('Van elke soort blijven de laatste {n} volledige backups staan', { n: k }) : t('De laatste 14 dagen blijven, en daarvoor de nieuwste per maand');
}
function bkKeepRender(st){
  const k = bkKeep(st), sel = $('#bk-keep'), txt = $('#bk-keep-txt');
  if (sel) sel.value = String(k);
  if (txt) txt.textContent = st.rotate ? bkKeepTekst(k) : t('Uit: oude backups blijven staan tot je ze zelf weghaalt');
}
function bkKeepSet(v){
  Android.backupSetKeep(Number(v) || 0);
  bkKeepRender(bkState(true));
}
function bkRotate(){ uiBezig(t('Bezig met tellen…'), 'onRotate'); Android.backupRotate(true); }
window.onRotate = function(r){
  if (r.error) { toast(r.error); return; }
  if (r.dry) {
    if (!r.count) { toast('Er is niets op te ruimen'); return; }
    askConfirm(t('{n} oude bestanden verwijderen?', { n: r.count }), t('Samen {grootte}. {regel}.', { grootte: fmtB(r.bytes), regel: bkKeepTekst(bkKeep(bkState(true))) }), 'Verwijderen', () => { uiBezig(t('Oude bestanden verwijderen…'), 'onRotate'); Android.backupRotate(false); });
  } else {
    toast(t('✓ {n} oude bestanden verwijderd ({grootte})', { n: r.count, grootte: fmtB(r.bytes) }) + (r.later ? ' · ' + bkLater(r.later) : ''));
  }
};
/* Waarom ongebruikte grote bestanden (nog) blijven staan */
function bkLater(l){
  return l === 'dubbel' ? t('Wacht: er staan twee mappen met dezelfde naam; die worden eerst samengevoegd')
    : l === 'onleesbaar' ? t('Wacht: een backup in de map was niet te lezen; er gaat niets weg')
    : l === 'bezig' ? t('Wacht: er wordt nog een backup gemaakt')
    : t('Volgende nacht verder');
}
/* Grote bestanden één keer bewaren (Versleuteld/Grote bestanden), en de oude backups waarin nog alles volledig zit */
function bkBlokRender(st){
  const on = st.blokken !== false, c = $('#bk-blok'), s = $('#bk-blok-txt');
  if (c) c.checked = on;
  if (s) s.textContent = on ? t('Opnames, documenten en foto’s staan één keer in Versleuteld/Grote bestanden; elke backup verwijst ernaar') : t('Uit: elke backup bevat alles opnieuw');
  const let_op = $('#bk-blok-let'); if (let_op) let_op.hidden = !on;
  const o = st.oud || {}, box = $('#bk-oud');
  if (!box) return;
  box.hidden = !(o.count > 0);
  if (o.count > 0) $('#bk-oud-txt').textContent = tn(o.count, 'Er staat nog één oude backup waarin alles volledig zit ({grootte}). De nieuwe backups hebben die niet meer nodig.',
    'Er staan nog {n} oude backups waarin alles volledig zit (samen {grootte}). De nieuwe backups hebben die niet meer nodig.', { grootte: fmtB(o.bytes || 0) });
}
function bkBlokSet(on){
  if (!on) {
    askConfirm(t('Grote bestanden weer in elke backup?'), t('Dan zit alles weer in elke backup en wordt je backup-map elke nacht veel groter. De grote bestanden die al bewaard zijn blijven staan zolang een backup ze nodig heeft.'), t('Uitzetten'),
      () => { Android.backupSetBlokken(false); bkBlokRender(bkState(true)); }, () => bkBlokRender(bkState(true)));
    return;
  }
  Android.backupSetBlokken(true);
  bkBlokRender(bkState(true));
}
function bkOudOpruimen(){ uiBezig(t('Bezig met tellen…'), 'onOudeBackups'); Android.backupOudeOpruimen(true); }
window.onOudeBackups = function(r){
  if (r.error) { toast(r.error); return; }
  if (r.dry) {
    if (!r.count) {
      toast(r.onvolledig ? t('Nog niet: de nieuwste backup mist grote bestanden. Maak eerst een nieuwe backup.')
        : r.geenNieuw || r.onbekend ? t('Nog niet: er is nog geen nieuwe backup met de grote bestanden apart.')
        : t('Er zijn geen oude backups om weg te halen'));
      if (bkOn()) bkBlokRender(bkState(true));
      return;
    }
    const dag = n => { const m = /(\d{4}-\d{2}-\d{2})/.exec(n || ''); return m ? fmtDate(m[1]) : '?'; };
    askConfirm(tn(r.count, 'Eén oude backup weghalen?', '{n} oude backups weghalen?'),
      (r.van ? (r.van === r.tot ? t('Van {datum}.', { datum: dag(r.van) }) : t('Van {van} tot en met {tot}.', { van: dag(r.van), tot: dag(r.tot) })) + ' ' : '') +
      t('Samen {grootte}. De nieuwste oude backup blijft als vangnet staan; je nieuwe backups zijn compleet zonder deze.', { grootte: fmtB(r.bytes || 0) }) + '\n\n' +
      t('Ze gaan naar de prullenbak van je opslag. Bij Google Drive tellen ze daar nog mee tot je de prullenbak leegt (of na 30 dagen).'),
      t('Weghalen'), () => { uiBezig(t('Oude backups weghalen…'), 'onOudeBackups'); Android.backupOudeOpruimen(false); });
  } else {
    toast(tn(r.count, '✓ Eén oude backup weggehaald ({grootte})', '✓ {n} oude backups weggehaald ({grootte})', { grootte: fmtB(r.bytes || 0) }));
    if (bkOn()) bkBlokRender(bkState(true));
  }
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

