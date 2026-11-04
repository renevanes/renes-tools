/* ---------- Automatisch uitschrijven ---------- */
function txAutoRender(){
  const a = rdJson(Android.txAutoState(), {});
  $('#tx-auto').checked = !!a.on;
  let note = "Nieuwe opnames (van de laatste 14 dagen) worden vanaf 01:00 uitgeschreven terwijl de telefoon oplaadt. Je krijgt een melding als het klaar is.";
  if (a.on && !a.model) note = 'Download eerst een spraakmodel; zonder model wordt er niets uitgeschreven.';
  else if (a.on) note = (a.next ? t('Volgende keer: vanaf {wanneer}, tijdens opladen.', { wanneer: fmtDT(a.next, {weekday:'long', hour:'2-digit', minute:'2-digit'}) }) : '') +
    (a.last ? ' ' + t('Laatst gekeken {wanneer}: {n} nieuw.', { wanneer: fmtD(a.last), n: a.found }) : '');
  $('#tx-auto-note').innerHTML = esc(t(note)) +
    (a.on && !a.battery ? '<br>' + t('⚠️ Zonder batterij-uitzondering laat Android het niet vanzelf starten; je krijgt dan \'s ochtends een melding om het met één tik te doen. <a href="#" onclick="Android.batterySettings();return false">Uitzondering geven</a>') : '') + (a.failed ? ' <a href="#" onclick="Android.txAutoRetryFailed();txAutoRender();toast(\'Wordt vannacht opnieuw geprobeerd\');return false">' + t('{n} mislukte opnieuw proberen', { n: a.failed }) + '</a>' : '');
}
function txAutoSet(on){ Android.txAutoSet(on); txAutoRender(); if (on) toast("Vanaf vannacht automatisch"); }

