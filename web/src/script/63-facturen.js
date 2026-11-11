/* ---------- Facturen ophalen ----------
 * Algemeen raamwerk: per leverancier (eerste: KPN Zakelijk) inloggen, facturen zoeken en PDF's bewaren.
 * Het eigenlijke werk doet de app (FacturenRun, web/facturen-robot.js); hier de lijst en de instellingen. */
let faSt = { leveranciers: [], sjablonen: [], ec: {} }, faPollT = null, faEdit = null;
/** De schermen van Facturen (voor enterFacturen en het verversen). */
var FA_SCHERMEN = ['facturen', 'facoverz', 'facopslag', 'faclok', 'fackopp', 'facgoogle', 'facec', 'faclev', 'facsmtp', 'facdos', 'facstuk', 'facaanl', 'facontv'];
/** Schermen met een formulier dat je zelf invult: niet verversen bij binnenkomen (dat doet het openen). */
var FA_FORMS = ['faclev', 'facstuk', 'facontv'];
/** Naar een onderdeel; terug gaat naar waar je vandaan kwam (Google en Eigen Cloud zijn via Opslaan én Koppelingen te bereiken). */
function faNaar(scr){
  if (scr === current) return;
  // Ligt het scherm al op de weg terug (bijv. Dossier → factuur → Aanleveren → Dossier)? Dan geen rondje maken.
  for (let x = BACK[current], n = 0; x && n < 20; x = BACK[x], n++) if (x === scr) { show(scr); return; }
  BACK[scr] = current; show(scr);
}
function faBackupMap(){
  askConfirm('Backup-map', faSt.map ? 'Facturen gaan in je backup-map in de map Facturen (per leverancier een eigen map). De backup-map zelf kies je in Instellingen.' : 'Er is nog geen backup-map gekozen. Kies er nu een; dezelfde map gebruikt de app ook voor de andere backups.', faSt.map ? 'Andere map kiezen' : 'Map kiezen', () => Android.waPickFolder());
}
function faDatum(tt){ return tt ? fmtDT(tt, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function enterFacturen(){
  faRender();
  if (!faPollT) faPollT = setInterval(() => { if (current !== 'facturen') { clearInterval(faPollT); faPollT = null; return; } faPoll(); }, 1000);
  if (current === 'facgoogle') { $('#fa-gd-msg').textContent = ''; }
  if (current === 'facdos') { $('#fd-kol').style.display = 'none'; $('#fd-kol-knop').setAttribute('aria-expanded', 'false'); fdGelezen = false; fdRender(true); fdLees(); }
  if (current === 'facaanl') foRender(true);
}
function faLaad(){ faSt = rdJson(Android.facturenState(), { leveranciers: [], sjablonen: [], ec: {} }); return faSt; }
function faPoll(){
  const was = faSt.bezig, wasIndelen = !!faSt.indelen, b = rdJson(Android.facturenBezig(), null);
  if (!b || !('bezig' in b)) { faLaad(); faBezig(); if (was !== faSt.bezig) faRender(true); return; }
  faSt.bezig = b.bezig; faSt.voortgang = b.voortgang; faSt.zichtbaar = b.zichtbaar;
  faBezig();
  if (was !== b.bezig || wasIndelen !== !!b.indelen) faRender(); // alles opnieuw laden als er iets begon of klaar is
}
function faBezig(){
  const l = faSt.leveranciers.find(x => x.id === faSt.bezig);
  $('#fa-bezig').style.display = faSt.bezig ? 'block' : 'none';
  $('#fa-bezig-txt').textContent = faSt.bezig ? (l ? l.naam + ': ' : '') + t(faSt.voortgang || 'Bezig…') : '';
}
const FA_WD = ['', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
function faMoment(ms){ return ms ? fmtDT(ms, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function faMaandSleutel(ms){ return rgMonthKey(ms); } // maand in de app-zone
function faMaandNaam(k){ return fmtDate(k + '-15', 'maand'); }
/** Wanneer vanzelf, in gewone woorden: "elke maand op de 5e om 08:00". */
function faPlanTekst(l){
  if (l.schema === 'uit') return t('alleen met de hand');
  const tijd = fmtHm(l.tijd || '08:00'), dag = l.dag || 5;
  if (l.schema === 'maand') return dag >= 31 ? t('elke maand op de laatste dag om {tijd}', { tijd }) : dag > 28 ? t('elke maand op de {n}e (of de laatste dag) om {tijd}', { n: dag, tijd }) : t('elke maand op de {n}e om {tijd}', { n: dag, tijd });
  if (l.schema === 'week') return t('elke {dag} om {tijd}', { dag: t(FA_WD[l.weekdag || 1]), tijd });
  return t('elke dag om {tijd}', { tijd });
}
/** Stand van deze periode: {soort: ok|mis|wacht|bezig|uit|nieuw|pw, tekst}. */
function faPeriode(l){
  const tt = l.toestand || {};
  if (faSt.bezig === l.id) return { soort: 'bezig', tekst: t(faSt.voortgang || 'Bezig…') };
  if (!l.heeftWachtwoord && l.gebruiker) return { soort: 'pw', tekst: t('Geen wachtwoord: vul het in of kijk mee om zelf in te loggen') };
  if (l.schema === 'uit') return tt.lastOk ? { soort: 'ok', tekst: t('✓ Laatst opgehaald {wanneer}', { wanneer: faMoment(tt.lastOk) }) } : { soort: 'uit', tekst: t('Alleen met de hand') };
  // De laatste poging mislukte (ook als het eerder deze periode wel lukte): dat eerst laten zien
  if (tt.lastTry && !tt.ok && tt.lastTry > (tt.lastOk || 0)) return { soort: 'mis', tekst: t('✗ Mislukt {wanneer}: {fout}', { wanneer: faMoment(tt.lastTry), fout: t(tt.msg || '') }) + ' ' + (l.vorige && tt.lastOk >= l.vorige ? t('(deze periode was al opgehaald)') : t('(na {n} uur opnieuw)', { n: l.opnieuw || 3 })) };
  if (l.heelPeriode && tt.lastOk) return { soort: 'wacht', tekst: t('⏳ Haalt nog de hele periode op ({periode}); dat gebeurt vanzelf, of tik op Nu ophalen', { periode: faPeriodeTekst(l) }) };
  if (l.vorige && tt.lastOk >= l.vorige) return { soort: 'ok', tekst: t('✓ Opgehaald {wanneer}', { wanneer: faMoment(tt.lastOk) }) + ' · ' + (tt.nieuw ? tn(tt.nieuw, '{n} nieuwe factuur', '{n} nieuwe facturen') : t('niets nieuws')) };
  if (l.vorige && tt.lastTry >= l.vorige && !tt.ok) return { soort: 'mis', tekst: t('✗ Mislukt {wanneer}: {fout}', { wanneer: faMoment(tt.lastTry), fout: t(tt.msg || '') }) + ' ' + t('(na {n} uur opnieuw)', { n: l.opnieuw || 3 }) };
  if (l.vorige && (tt.lastOk || tt.lastTry || l.aantal)) return { soort: 'wacht', tekst: t('⏳ Gepland {wanneer}: nog niet gelukt, de app probeert het zodra het kan', { wanneer: faMoment(l.vorige) }) };
  return { soort: 'nieuw', tekst: t('Nog niet opgehaald') };
}
function faPeriodeTekst(l){
  const p = l.periode || { soort: 'jaar' };
  return p.soort === 'alles' ? t('alles') : p.soort === 'maanden' ? t('de laatste {n} maanden', { n: p.maanden }) : p.soort === 'vanaf' && p.vanaf ? t('vanaf {datum}', { datum: fmtDate(new Date(p.vanaf + 'T12:00')) }) : t('dit jaar');
}
function faStatus(l){ return faPeriode(l).tekst; }
/* Leveranciers uit de mail (soort uitmail): elke afzender die Tools in de mail vindt, een eigen rij */
const FA_NIET_HERKEND = 'niet-herkend';
const faUit = l => !!l && l.soort === 'uitmail';
function faNaam(l){ return l.id === FA_NIET_HERKEND && (!l.naam || l.naam === 'Niet herkend') ? t('Niet herkend') : l.naam; }
let faUitOpen = false;
function faUitAlles(){ faUitOpen = !faUitOpen; faRender(true); }
/** Een website-leverancier (of een andere uit de mail) waar deze leverancier volgens de app bij hoort. */
function faVoorstel(l){ const v = l.voorstel && faSt.leveranciers.find(x => x.id === l.voorstel); return v && v.soort !== 'mail' && !v.overslaan ? v : null; }
function faUitTekst(l){
  const n = l.facturen || l.aantal || 0;
  return (n ? tn(n, '{n} factuur', '{n} facturen') : t('Nog geen facturen')) + (l.laatste ? ' · ' + t('laatste {wanneer}', { wanneer: faDatum(l.laatste) }) : '');
}
function faUitRij(l){
  const v = faVoorstel(l);
  return '<div class="srow"><button class="sbtn" data-id="' + esc(l.id) + '" onclick="faOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon('mail') + '</span>' +
    '<span class="stxt"><b translate="no">' + esc(faNaam(l)) + (l.nieuw ? '<span class="fa-nieuw">' + esc(t('Nieuw')) + '</span>' : '') + '</b><small>' + esc(faUitTekst(l)) + '</small>' +
    (v ? '<small>' + esc(t('Hoort dit bij {naam}?', { naam: v.naam })) + '</small>' : '') + '</span></button></div>';
}
function faUitRender(){
  const uit = faSt.leveranciers.filter(x => faUit(x) && !x.overslaan)
    .sort((a, b) => (b.nieuw ? 1 : 0) - (a.nieuw ? 1 : 0) || (b.laatste || 0) - (a.laatste || 0) || faNaam(a).localeCompare(faNaam(b)));
  const nNieuw = uit.filter(x => x.nieuw).length, max = Math.max(8, nNieuw);
  $('#fa-uit-blok').hidden = !uit.length;
  $('#fa-uit-h').textContent = nNieuw ? t('Gevonden in je mail · {n} nieuw', { n: nNieuw }) : t('Gevonden in je mail');
  $('#fa-uit').innerHTML = (faUitOpen ? uit : uit.slice(0, max)).map(faUitRij).join('');
  $('#fa-uit-meer').hidden = uit.length <= max;
  $('#fa-uit-meer').textContent = faUitOpen ? t('Minder tonen') : t('Alle {n} tonen', { n: uit.length });
}
function faRender(geenLaad){
  if (!geenLaad) faLaad();
  faBezig();
  $('#fa-indelen').hidden = !faSt.indelen;
  $('#fa-lijst').innerHTML = faSt.leveranciers.filter(l => !faUit(l)).map(l => {
    const p = faPeriode(l), fout = p.soort === 'mis' || p.soort === 'pw';
    const volgende = l.schema !== 'uit' && l.volgende ? t('Volgende keer: {wanneer}', { wanneer: faMoment(l.volgende) }) : t('Vanzelf: {wanneer}', { wanneer: faPlanTekst(l) });
    const gevonden = l.soort === 'mail' ? faSt.leveranciers.filter(x => faUit(x) && !x.overslaan && (x.bronnen || []).includes(l.id)).length : 0;
    return '<div class="srow"><button class="sbtn" data-id="' + esc(l.id) + '" onclick="faOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon(l.soort === 'mail' ? 'mail' : 'document') + '</span>' +
      '<span class="stxt"><b translate="no">' + esc(l.naam) + '</b><small' + (fout ? ' class="bad"' : '') + '>' + esc(p.tekst) + '</small><small>' + esc(volgende + ' · ' + faPlanTekst(l)) + '</small>' +
      (gevonden ? '<small>' + esc(tn(gevonden, '{n} leverancier gevonden', '{n} leveranciers gevonden')) + '</small>' : '') + '</span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">Nog geen leveranciers. Tik op <b>Leverancier toevoegen</b>.</p>';
  faUitRender();
  $('#fa-nieuw').disabled = !!faSt.onleesbaar;
  faMaandRender();
  const ec = faSt.ec || {};
  faSamenvatting();
  if (current === 'facdos' && faDos) fdRender();
  if (current === 'facaanl') foRender();
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#s-facsmtp .card input')) return;
  const sm = faSt.smtp || {};
  $('#fm-host').value = sm.host || ''; $('#fm-poort').value = sm.poort || 587; $('#fm-bev').value = sm.beveiliging === 'ssl' ? 'ssl' : 'starttls';
  $('#fm-user').value = sm.gebruiker || ''; $('#fm-van').value = sm.van || ''; $('#fm-naam').value = sm.naam || '';
  $('#fm-pw').value = ''; $('#fm-pw').placeholder = sm.heeftWachtwoord ? 'Ongewijzigd' : '';
  $('#fm-stand').textContent = sm.host ? t('Ingesteld: {server}, afzender {van}.', { server: sm.host + ':' + (sm.poort || 587), van: sm.van || '?' }) : t('Niet ingesteld. Met je eigen mailserver (SMTP) kan Tools facturen versturen aan je boekhouder vanaf je eigen adres.');
  $('#fm-los').style.display = sm.host ? 'block' : 'none';
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#s-facec .card input, #s-facgoogle .card input')) return; // niet overschrijven tijdens typen
  $('#fa-ec-url').value = ec.url || ''; $('#fa-ec-user').value = ec.gebruiker || ''; $('#fa-ec-adres').value = ec.adres || '';
  $('#fa-ec-pw').value = ''; $('#fa-ec-pw').placeholder = ec.heeftWachtwoord ? 'Ongewijzigd' : '';
  $('#fa-ec-stand').textContent = ec.url && ec.heeftWachtwoord ? t('Gekoppeld met {server}. Facturen gaan als mail met de PDF naar {adres}.', { server: ec.url.replace(/^https:\/\//, ''), adres: ec.adres || ec.gebruiker })
    : 'Niet gekoppeld. Koppel Eigen Cloud om facturen in je eigen mail te krijgen, of om de inlogcode uit je mail te lezen.';
  const lok = faSt.lokaal || '';
  $('#fa-lok-stand').textContent = lok === '!' ? t('Tools kan niet meer bij de gekozen map. Kies hem opnieuw.') : lok ? t('Facturen komen in {map}, per leverancier een eigen map.', { map: lok }) : t('Nog geen map gekozen. Kies bijvoorbeeld Documenten; per leverancier komt er een eigen map in.');
  $('#fa-lok-stand').classList.toggle('bad', lok === '!');
  $('#fa-lok-kies').textContent = lok ? 'Andere map kiezen' : 'Map kiezen';
  $('#fa-lok-los').style.display = lok ? 'block' : 'none';
  const gd = faSt.drive || {};
  $('#fa-gd-setup').style.display = gd.gekoppeld ? 'none' : 'block';
  $('#fa-gd-aan').style.display = gd.gekoppeld ? 'block' : 'none';
  $('#fa-gd-stand').textContent = gd.gekoppeld ? (gd.gebruiker ? t('Gekoppeld met {naam}', { naam: gd.gebruiker }) : t('Gekoppeld')) + ' ' + t(gd.viaToestel ? '(het Google-account op deze telefoon).' : '(via de browser).') + (gd.accountWeg ? ' ' + t('Dit account staat niet (meer) op deze telefoon voor Tools: tik op Rechten aanpassen en kies je account opnieuw.') : '') : '';
  $('#fa-gd-stand').classList.toggle('bad', !!gd.accountWeg);
  if (!$('#fa-gd-sha1').textContent) { const k = rdJson(Android.facturenAppSleutel(), {}); $('#fa-gd-pakket').textContent = k.pakket || 'nl.rene.tools'; $('#fa-gd-sha1').textContent = k.sha1 || t('(niet te lezen)'); }
  $('#fa-gd-rechten').textContent = gd.gekoppeld ? 'Gmail: ' + // taal-vast: "Gmail:" en "Drive:" zijn namen van diensten; de rest staat in t()
     t(gd.rechtGmail ? '✓ facturen zoeken (alleen lezen)' : '– geen toegang') + ' · Drive: ' + t(gd.rechtDrive ? '✓ bewaren in de map Facturen' : '– geen toegang') + ' · ' + t('Versturen:') + ' ' + t(gd.rechtGmailSend ? '✓ aan je boekhouder' : '– nee') + '. ' + t('Tools ziet in Drive alleen zijn eigen mappen.') : '';
  if (gd.gekoppeld) { $('#fa-gd-r-gmail').checked = !!gd.rechtGmail || $('#fa-gd-r-gmail').checked; $('#fa-gd-r-drive').checked = !!gd.rechtDrive; $('#fa-gd-r-send').checked = !!gd.rechtGmailSend || $('#fa-gd-r-send').checked; }
  $('#fa-gd-knop').textContent = gd.gekoppeld ? 'Opnieuw koppelen met deze rechten' : 'Koppelen met Google';
  $('#fa-gd-toestel').textContent = gd.gekoppeld && gd.viaToestel ? 'Opnieuw toestemming geven met deze rechten' : 'Google-account kiezen';
  if (!gd.gekoppeld && gd.clientId && !$('#fa-gd-id').value) $('#fa-gd-id').value = gd.clientId;
  $('#fa-gd-geheim').placeholder = gd.heeftGeheim ? 'Client secret (ongewijzigd)' : 'Client secret';
}
/** De korte stand onder elke rij van Meer, Opslaan en Koppelingen. */
function faSamenvatting(){
  const gd = faSt.drive || {}, ec = faSt.ec || {}, lok = faSt.lokaal || '';
  const ecAan = !!(ec.url && ec.heeftWachtwoord);
  const plekken = [lok && lok !== '!' ? 'telefoon' : '', faSt.map ? 'backup-map' : '', gd.rechtDrive ? 'Drive' : '', ecAan ? 'Eigen Cloud' : ''].filter(Boolean);
  const zet = (id, tt) => { const e = $('#' + id); if (e) e.textContent = tt; };
  zet('fa-sum-overz', t(faSt.leveranciers.length ? 'De laatste 12 maanden per leverancier' : 'Nog geen leveranciers'));
  zet('fa-sum-opslag', plekken.length ? t('Ingesteld: {wat}', { wat: plekken.map(x => t(x)).join(', ') }) : t('Nog niets ingesteld (alleen in de app)'));
  zet('fa-sum-kopp', [gd.gekoppeld ? 'Google' : '', ecAan ? 'Eigen Cloud' : '', (faSt.smtp || {}).host ? t('mailserver') : ''].filter(Boolean).join(', ') || t('Nog niets gekoppeld'));
  zet('fa-sum-lok', lok === '!' ? t('Geen toegang meer: kies hem opnieuw') : lok || t('Nog geen map gekozen'));
  zet('fa-sum-backup', t(faSt.map ? 'Map Facturen in je backup-map' : 'Nog geen backup-map gekozen'));
  zet('fa-sum-drive', gd.rechtDrive ? t('Gekoppeld') + (gd.gebruiker ? ' · ' + gd.gebruiker : '') : t(gd.gekoppeld ? 'Google gekoppeld, maar zonder Drive' : 'Niet gekoppeld'));
  zet('fa-sum-ec', ecAan ? t('Gekoppeld') + ' · ' + ec.url.replace(/^https:\/\//, '') : t('Niet gekoppeld'));
  zet('fa-sum-ec2', ecAan ? t('Gekoppeld') + ' · ' + ec.url.replace(/^https:\/\//, '') : t('Niet gekoppeld'));
  const sm = faSt.smtp || {};
  zet('fa-sum-smtp', sm.host ? t('Ingesteld') + ' · ' + sm.host : t('Niet ingesteld'));
  zet('fa-sum-dos', faSt.dossier ? tn(faSt.dossier, '{n} factuur met alle gegevens', '{n} facturen met alle gegevens') : t('Nog leeg'));
  zet('fa-sum-aanl', faSt.ontvangers ? tn(faSt.ontvangers, '{n} ontvanger', '{n} ontvangers') : t('Nog geen ontvanger (bijv. je boekhouder)'));
  zet('fa-sum-google', gd.gekoppeld ? [gd.rechtGmail ? 'Gmail' : '', gd.rechtDrive ? 'Drive' : '', gd.rechtGmailSend ? 'versturen' : ''].filter(Boolean).join(', ') + (gd.gebruiker ? ' · ' + gd.gebruiker : '') : 'Niet gekoppeld');
}
/* Overzicht: deze maand per leverancier, en per maand wat er binnenkwam */
function faMaandRender(){
  const l = faSt.leveranciers.filter(x => !(faUit(x) && x.overslaan)), nu = Date.now(), dezeMaand = faMaandSleutel(nu);
  $('#fa-maand').style.display = l.length ? 'block' : 'none';
  if (!l.length) { $('#fa-overzicht-lijst').innerHTML = '<p class="note">Nog geen leveranciers.</p>'; return; }
  $('#fa-maand-h').textContent = t('Deze maand') + ' · ' + faMaandNaam(dezeMaand);
  // Alleen wie deze maand iets heeft (of waar het misging); de rest ingeklapt
  const aantal = x => (x.maanden || {})[dezeMaand] || 0, fout = x => !faUit(x) && faPeriode(x).soort === 'mis';
  const rij = x => {
    const n = aantal(x), p = faUit(x) ? { soort: '' } : faPeriode(x);
    const binnen = n ? '✓ ' + tn(n, '{n} factuur van deze maand', '{n} facturen van deze maand') : faUit(x) ? t('Nog niets binnen') : p.soort === 'ok' ? t('Opgehaald; nog geen factuur met een datum in deze maand') : p.soort === 'mis' ? t('✗ Nog niet: mislukt') : x.schema !== 'uit' && x.volgende && faMaandSleutel(x.volgende) === dezeMaand && !(x.vorige && faMaandSleutel(x.vorige) === dezeMaand) ? t('Gepland {wanneer}', { wanneer: faMoment(x.volgende) }) : t(x.schema === 'uit' ? 'Alleen met de hand' : 'Nog niets binnen');
    return '<div class="bkres"><b translate="no">' + esc(faNaam(x)) + '</b><span class="' + (n ? 'good' : p.soort === 'mis' ? 'bad' : '') + '">' + esc(binnen) + '</span></div>';
  };
  const boven = l.filter(x => aantal(x) || fout(x)), rest = l.filter(x => !faUit(x) && x.soort !== 'mail' && !boven.includes(x)), uitRest = l.filter(x => faUit(x) && !boven.includes(x)).length;
  uiHtml($('#fa-maand-lijst'), (boven.map(rij).join('') || '<p class="note">' + esc(t('Nog geen facturen van deze maand.')) + '</p>') +
    (rest.length || uitRest ? '<details class="fa-rest" data-k="rest"><summary>' + esc(tn(rest.length + uitRest, 'Nog niets van 1 andere leverancier', 'Nog niets van {n} andere leveranciers')) + '</summary>' + rest.map(rij).join('') +
      (uitRest ? '<p class="note">' + esc(tn(uitRest, 'En 1 leverancier uit je mail.', 'En {n} leveranciers uit je mail.')) + '</p>' : '') + '</details>' : ''));
  // Laatste 12 maanden, een rij per leverancier (de mailbron zelf niet: die verdeelt alleen);
  // ✓ = facturen binnen, ✗ = alleen mislukte pogingen, – = niets
  const maanden = []; let mk = faMaandSleutel(nu) + '-01';
  for (let i = 0; i < 12; i++) { maanden.push(mk.slice(0, 7)); mk = rgDayAdd(mk, -1).slice(0, 7) + '-01'; }
  const cel = (x, m) => {
    const n = (x.maanden || {})[m] || 0;
    if (n) return '<td class="good">✓ ' + n + '</td>';
    const mis = ((x.toestand || {}).log || []).some(r => !r.ok && faMaandSleutel(r.t) === m);
    return mis ? '<td class="bad">✗</td>' : '<td>–</td>';
  };
  const rijen = l.filter(x => x.soort !== 'mail' && (!faUit(x) || x.nieuw || maanden.some(m => (x.maanden || {})[m])))
    .sort((a, b) => (faUit(a) ? 1 : 0) - (faUit(b) ? 1 : 0) || faNaam(a).localeCompare(faNaam(b)));
  $('#fa-overzicht-lijst').innerHTML = (rijen.length ? '<table class="fa-tab fa-ovz"><thead><tr><th scope="col">' + esc(t('Leverancier')) + '</th>' + maanden.map(m => '<th scope="col">' + esc(fmtDate(m + '-15', { month: 'short', year: '2-digit' })) + '</th>').join('') + '</tr></thead><tbody>' +
    rijen.map(x => '<tr><th scope="row" translate="no">' + esc(faNaam(x)) + '</th>' + maanden.map(m => cel(x, m)).join('') + '</tr>').join('') + '</tbody></table>' : '<p class="note">' + esc(t('Nog geen facturen.')) + '</p>') +
    '<p class="note">✓ = aantal facturen met een factuurdatum in die maand (bijlagen tellen niet apart; zonder herkende datum telt de dag van de mail of van ophalen), ✗ = ophalen mislukt in die maand, – = niets. Nieuwste maand links.</p>';
}
function faGeschiedenis(l){
  const log = (l.toestand || {}).log || [];
  if (!log.length) { toast('Nog geen geschiedenis'); return; }
  askConfirm(t('Geschiedenis') + ' · ' + l.naam, log.map(r => (r.ok ? '✓ ' : '✗ ') + faMoment(r.t) + ' ' + t(r.auto ? '(vanzelf)' : '(met de hand)') + '\n   ' + t(r.msg || '') + ((r.namen || []).length ? '\n   ' + r.namen.join(', ') : '')).join('\n'), 'Sluiten', () => {});
}
/** Alleen vaste diagnosestappen en tellingen; geen namen, adressen, websitefouten of factuurinhoud in het deelbare rapport. */
function faLogTekst(l){
  const namen = {
    START: t('Ophalen gestart'), INLOG: t('Inloggen'), FACTUREN: t('Facturenpagina zoeken'), LIJST: t('Factuurlijst geladen'),
    MEER: t('Toon meer aangeklikt'), VOLGENDE: t('Volgende pagina aangeklikt'), WACHT: t('Wachten op facturen'), DOWNLOAD: t('Download gestart'),
    HTTP: t('Antwoordcode van de website'), DOWNLOADFOUT: t('Download mislukt'), PDF: t('PDF ontvangen'), HERSTEL: t('Terug naar de factuurlijst'), VOLLEDIG: t('Alle factuurlijsten doorlopen'), LIMIET: t('Ingesteld maximum bereikt'),
    LAADFOUT: t('Factuurlijst niet op tijd geladen'), HERHALING: t('De website herhaalt een factuurlijst'), GEWIJZIGD: t('Factuurlijst onverwacht gewijzigd'),
    HERSTELFOUT: t('Teruggaan naar de factuurlijst mislukt'), OPSLAAN: t('Facturen bewaren'), KLAAR: t('Ophalen afgerond'), FOUT: t('Ophalen mislukt'), STOP: t('Gestopt')
  };
  const tt = l.toestand || {}, logs = [tt.diagnose, ...(tt.log || []).map(r => r.diagnose)].filter(Boolean);
  const gehad = new Set(), blokken = [];
  for (const d of logs) {
    if (gehad.has(d.t)) continue; gehad.add(d.t);
    const stappen = (d.stappen || []).filter(r => Object.prototype.hasOwnProperty.call(namen, r.stap));
    if (!stappen.length) continue;
    blokken.push(t('Ophaalpoging {wanneer} · app {versie} · APK {native}', { wanneer: faDatum(d.t), versie: d.versie, native: fmtNum(d.native) })
      + '\n' + stappen.map(r => t('+{s} s · lijst {pagina} · {stap} · aantal {n}', { s: fmtNum(r.ms / 1000, 1), pagina: fmtNum(r.pagina), stap: namen[r.stap], n: fmtNum(r.aantal) })).join('\n')
      + (d.weggelaten ? '\n' + t('{n} eerdere stappen weggelaten', { n: fmtNum(d.weggelaten) }) : ''));
  }
  return blokken.join('\n\n');
}
function faLog(id){
  faLaad();
  const l = faSt.leveranciers.find(x => x.id === id); if (!l) return;
  const tekst = faLogTekst(l);
  if (!tekst) { toast('Nog geen ophaallogboek; haal eerst facturen op'); return; }
  openSheet('Logboek ophalen', l.naam, [
    ['Bekijken', () => askConfirm('Logboek ophalen', tekst, 'Sluiten', () => {})],
    ['Kopiëren', () => { Android.aiCopy(tekst); toast('Gekopieerd'); }],
    ['Delen', () => Android.aiShare(tekst)]
  ]);
}
/** Menu van een leverancier uit de mail: klopt het, hoort hij bij een andere, overslaan. */
function faUitMenu(l){
  const id = l.id, v = faVoorstel(l), bron = faSt.leveranciers.find(x => (l.bronnen || []).includes(x.id) && x.soort === 'mail');
  const acts = [
    ...(l.nieuw ? [[t('Klopt'), () => { const e = Android.facturenLevKlopt(id); if (e) toast(e); else toast(t('✓ {naam} staat erbij', { naam: faNaam(l) })); faRender(); }]] : []),
    ...(v ? [[t('Hoort bij {naam}', { naam: v.naam }), () => faSamen(l, v)]] : []),
    [l.facturen || l.aantal ? t('Facturen bekijken ({n})', { n: l.facturen || l.aantal }) : t('Facturen bekijken'), () => faDossierVan(id)],
    [t('Naam en opslag aanpassen'), () => flOpen(l)],
    [t('Samenvoegen met…'), () => faSamenKies(l)],
    [t('Overslaan…'), () => faOverslaan(l)]];
  openSheet(faNaam(l), (bron ? t('Uit {bron}', { bron: bron.naam }) + ' · ' : '') + faUitTekst(l), acts);
}
/** Dossier met alleen de facturen van deze leverancier. */
let fdLevKeuze = '';
function faDossierVan(id){ fdLevKeuze = id; faNaar('facdos'); }
function faSamenKies(l){
  const doel = faSt.leveranciers.filter(x => x.id !== l.id && x.soort !== 'mail' && !x.overslaan)
    .sort((a, b) => (faUit(a) ? 1 : 0) - (faUit(b) ? 1 : 0) || faNaam(a).localeCompare(faNaam(b)));
  if (!doel.length) { toast(t('Er is nog geen andere leverancier')); return; }
  openSheet(t('{naam} samenvoegen met…', { naam: faNaam(l) }), t('Alle facturen en de herkenning gaan naar de leverancier die je kiest.'), doel.map(x => [faNaam(x) + (faUit(x) ? '' : ' · ' + t('website')), () => faSamen(l, x)]));
}
function faSamen(van, naar){
  askConfirm(t('Samenvoegen met {naam}?', { naam: faNaam(naar) }), t('De facturen van {van} gaan naar {naar}, ook in de app, en voortaan herkent Tools ze als {naar}. {van} verdwijnt uit de lijst. Kopieën in je backup-map, op Drive en in je mail blijven waar ze zijn.', { van: faNaam(van), naar: faNaam(naar) }), t('Samenvoegen'), () => {
    const r = rdJson(Android.facturenLevSamen(van.id, naar.id), { error: t('Samenvoegen lukt niet') });
    if (r.error) { toast(r.error); return; }
    toast(t('✓ Samengevoegd met {naam}', { naam: faNaam(naar) })); faRender(); fdLaad();
  });
}
function faOverslaan(l){
  const doe = ook => { const r = rdJson(Android.facturenLevOverslaan(l.id, true, !!ook), { error: t('Lukt niet') }); if (r.error) { toast(r.error); return; } toast(t('✓ {naam} wordt overgeslagen', { naam: faNaam(l) })); faRender(); fdLaad(); };
  openSheet(t('{naam} overslaan?', { naam: faNaam(l) }), t('Nieuwe facturen van deze afzender bewaart Tools niet meer. Terugzetten kan bij de mailbron (Zoeken).'), [
    [t('Overslaan; wat er al is, blijft'), () => doe(false)],
    [t('Overslaan en de facturen weghalen…'), () => askConfirm(t('Facturen weghalen?'), tn(l.facturen || l.aantal || 0, 'Eén factuur gaat uit het dossier en uit de app. Kopieën in je backup-map, op Drive en in je mail blijven staan. Dit kan niet ongedaan worden.', '{n} facturen gaan uit het dossier en uit de app. Kopieën in je backup-map, op Drive en in je mail blijven staan. Dit kan niet ongedaan worden.'), t('Weghalen'), () => doe(true))]]);
}
function faTerugzetten(id){
  const r = rdJson(Android.facturenLevOverslaan(id, false, false), { error: t('Lukt niet') });
  if (r.error) { toast(r.error); return; }
  const l = faSt.leveranciers.find(x => x.id === id), naam = l ? faNaam(l) : '';
  toast(r.terug ? tn(r.terug, '✓ {naam} staat weer in de lijst; de overgeslagen factuur komt bij de volgende keer zoeken', '✓ {naam} staat weer in de lijst; {n} overgeslagen facturen komen bij de volgende keer zoeken', { naam })
    : t('✓ {naam} staat weer in de lijst', { naam })); faLaad(); flOver();
}
function faOpen(id){
  const l = faSt.leveranciers.find(x => x.id === id); if (!l) return;
  if (faUit(l)) { faUitMenu(l); return; }
  const busy = !!faSt.bezig;
  const acts = [
    ['Nu ophalen', () => faNu(id, false)],
    ...(l.soort === 'mail' ? [] : [['Ophalen en meekijken', () => faNu(id, true)]]),
    [l.facturen ? (l.aantal > l.facturen ? t('Facturen bekijken ({n}, met bijlagen {docs} documenten)', { n: l.facturen, docs: l.aantal }) : t('Facturen bekijken ({n})', { n: l.facturen })) : l.aantal ? t('Facturen bekijken ({n})', { n: l.aantal }) : t('Facturen bekijken'), () => faBekijk(l)],
    ['Geschiedenis', () => faGeschiedenis(l)],
    ['Logboek ophalen', () => faLog(id)],
    ['Alles van de periode opnieuw ophalen', () => askConfirm(t('Alles opnieuw ophalen?'), t('De app haalt alle facturen van de gekozen periode ({periode}) nog een keer op. Wat al binnen is, komt er niet dubbel in.', { periode: faPeriodeTekst(l) }), t('Ophalen'), () => { const e = Android.facturenOpnieuw(id); if (e) { toast(e); return; } faNu(id, false); })],
    ['Instellingen', () => flOpen(l)],
    ['Verwijderen…', () => askConfirm(t('{naam} verwijderen?', { naam: l.naam }), 'De instellingen en de facturen in de app gaan weg. Kopieën in je backup-map en in Eigen Cloud blijven staan.', 'Verwijderen', () => { const e = Android.facturenVerwijder(id); if (e) toast(e); faRender(); })]];
  openSheet(l.naam, busy ? 'Er wordt nu opgehaald: even wachten' : faStatus(l), busy ? acts.filter(a => !/^(Nu ophalen|Ophalen en meekijken|Alles van de periode)/.test(a[0])) : acts);
}
function faNu(id, meekijken){
  const e = Android.facturenNu(id, !!meekijken);
  if (e) { toast(e); return; }
  if (!meekijken) { faSt.bezig = id; faSt.voortgang = 'Starten…'; faBezig(); toast('Bezig met ophalen; dat kan een paar minuten duren'); }
}
window.onFacturenKlaar = function(r){
  if (!r.ok && r.koppelNodig) { toast(r.msg || 'Koppeling nodig'); setTimeout(() => askConfirm('Koppeling nodig', r.msg || '', 'Naar Google', () => faNaar('facgoogle')), 600); if (FA_SCHERMEN.includes(current)) faRender(); return; }
  toast((r.ok ? '✓ ' : '') + (r.naam ? r.naam + ': ' : '') + (r.msg || ''));
  if (r.ok && (r.fouten || []).length) setTimeout(() => toast(t('Niet alles afgeleverd: {fout}', { fout: t(r.fouten[0]) })), 2800);
  else if (r.ok && (r.nieuweLeveranciers || []).length) { const nl = r.nieuweLeveranciers; setTimeout(() => toast(tn(nl.length, 'Nieuwe leverancier in je mail: {namen}', '{n} nieuwe leveranciers in je mail: {namen}', { namen: nl.slice(0, 5).join(', ') + (nl.length > 5 ? ', …' : '') })), 2800); }
  if (!r.ok && r.inlogNodig) setTimeout(() => askConfirm('Inloggen nodig', t(r.msg || '') + '\n\n' + t('Kijk één keer mee: dan log je zelf in en gaat de app daarna verder.'), 'Meekijken', () => faNu(r.id, true)), 600);
  if (FA_SCHERMEN.includes(current)) faRender();
};
window.onFacturenIngedeeld = function(r){
  toast(tn(r.aantal || 0, 'Eén factuur uit je mail staat nu bij zijn eigen leverancier', '{n} facturen uit je mail staan nu bij hun eigen leverancier'));
  if (FA_SCHERMEN.includes(current)) { faRender(); if (current === 'facdos') fdRender(true); }
};
function faBekijk(l){
  const lijst = rdJson(Android.facturenLijst(l.id), []);
  if (!lijst.length) { toast('Nog geen facturen in de app'); return; }
  openSheet(l.naam, tn(lijst.length, '{n} document', '{n} documenten') + (l.facturen && l.facturen < lijst.length ? ' ' + tn(l.facturen, '({n} factuur met bijlagen)', '({n} facturen met bijlagen)') : ''), lijst.slice(0, 40).map(f => [f.naam + ' · ' + faDatum(f.t), () => Android.facturenOpen(l.id, f.naam)]));
}
function faNieuw(){
  const s = faSt.sjablonen || [];
  openSheet('Leverancier toevoegen', 'Kies een sjabloon; alles is daarna aan te passen', s.map(x => [(x.naam || t('Andere website')) + (x.omschrijving ? ' · ' + t(x.omschrijving) : ''), () => flOpen(Object.assign({ soort: 'website' }, x, { id: '' }), true)]));
}
/* Instellingen van één leverancier */
function flOpen(l, nieuw){
  faEdit = JSON.parse(JSON.stringify(l)); faEdit._nieuw = !!nieuw;
  const c = l.code || {}, v = l.velden || {};
  $('#fl-h').textContent = nieuw ? (l.naam ? t('{naam} toevoegen', { naam: l.naam }) : t('Leverancier toevoegen')) : l.naam;
  $('#fl-tip').textContent = l.tip || ((faSt.sjablonen || []).find(s => s.sjabloon === l.sjabloon) || {}).tip || '';
  $('#fl-tip').style.display = $('#fl-tip').textContent ? 'block' : 'none';
  $('#fl-naam').value = l.naam || ''; $('#fl-start').value = l.startUrl || ''; $('#fl-user').value = l.gebruiker || '';
  $('#fl-pw').value = ''; $('#fl-pw').placeholder = l.heeftWachtwoord ? 'Ongewijzigd' : '';
  $('#fl-bron').value = c.bron || 'geen'; $('#fl-afz').value = c.afzender || ''; $('#fl-pat').value = c.patroon || '';
  $('#fl-schema').value = l.schema || 'maand';
  if (!$('#fl-dag').options.length) for (let i = 1; i <= 31; i++) $('#fl-dag').add(new Option(i === 31 ? 'laatste dag' : i + 'e', String(i)));
  $('#fl-dag').value = String(l.dag || 5); $('#fl-wd').value = String(l.weekdag || 1); $('#fl-tijd').value = l.tijd || '08:00';
  $('#fl-opnieuw').value = String(l.opnieuw || 3); $('#fl-wifi').checked = !!l.wifi;
  $('#fl-meldnieuw').checked = l.meldNieuw !== false; $('#fl-meldfout').checked = l.meldFout !== false; $('#fl-map').checked = l.map !== false; $('#fl-ec').checked = !!l.eigenCloud;
  $('#fl-lok').checked = !!l.lokaal; $('#fl-gd').checked = !!l.drive;
  $('#fl-domeinen').value = l.domeinen != null ? l.domeinen : (((faSt.sjablonen || []).find(s => s.sjabloon === l.sjabloon) || {}).domeinen || '');
  $('#fl-facturl').value = l.facturenUrl || ''; $('#fl-menu').value = l.menu || ''; $('#fl-knoppen').value = l.knoppen || '';
  $('#fl-max').value = l.max || 50; $('#fl-sub').value = l.submap || '';
  $('#fl-meer').value = l.meer != null ? l.meer : 'toon meer|meer tonen|meer facturen|meer laden|laad meer|oudere facturen|alle facturen|bekijk meer|show more|load more';
  const pe = l.periode || { soort: 'jaar' };
  $('#fl-periode').value = pe.soort === 'maanden' ? 'm' + ([3, 6, 12, 24].includes(pe.maanden) ? pe.maanden : 12) : ['jaar', 'vanaf', 'alles'].includes(pe.soort) ? pe.soort : 'jaar';
  $('#fl-vanaf').value = pe.vanaf || '';
  $('#fl-start').placeholder = 'https://…';
  ['gebruiker', 'wachtwoord', 'code', 'knop'].forEach(k => $('#fl-v-' + k).value = v[k] || '');
  const m = l.mail || {};
  $('#fl-m-bron').value = m.bron || 'gmail'; $('#fl-m-woorden').value = m.woorden != null ? m.woorden : 'factuur, invoice, rekening, nota';
  $('#fl-m-afz').value = m.afzenders || ''; $('#fl-m-neg').value = m.negeer || ''; $('#fl-m-extra').value = m.extra || '';
  $('#fl-m-max').value = m.max || 50;
  const h = l.herken || {}, lijst = k => (h[k] || []).join(', ');
  $('#fl-h-afz').value = lijst('afzenders'); $('#fl-h-dom').value = lijst('domeinen'); $('#fl-h-namen').value = lijst('namen');
  $('#fl-h-btw').value = lijst('btw'); $('#fl-h-kvk').value = lijst('kvk'); $('#fl-h-iban').value = lijst('iban');
  const bron = faUit(l) && faSt.leveranciers.find(x => (l.bronnen || []).includes(x.id) && x.soort === 'mail');
  $('#fl-h-bron').textContent = faUit(l) ? (bron ? t('Gevonden in {bron}.', { bron: bron.naam }) + ' ' : '') + t('Facturen in je mail met een van deze gegevens komen bij deze leverancier.') : t('Facturen in je mail met een van deze gegevens komen bij deze leverancier.');
  $('#fl-h-uitleg').textContent = t('Meer dan één? Scheid ze met een komma.') + (faSt.pdfTekst === false ? ' ' + t('Btw-, KvK- en IBAN-nummers leest Tools pas vanaf Android 15 uit de PDF; op deze telefoon tellen de afzender, het domein en de naam.') : '');
  if (faUit(l)) { $('#fl-tip').textContent = l.nieuw ? t('Nieuw gevonden in je mail. Kijk of de naam klopt en waar de facturen heen moeten.') : ''; $('#fl-tip').style.display = l.nieuw ? 'block' : 'none'; }
  const eg = faSt.herkennen || {};
  $('#fl-e-nr').value = (eg.nummers || []).join(', '); $('#fl-e-adr').value = (eg.adressen || []).join(', ');
  $('#fl-msg').textContent = '';
  flBron(); flPlan(); flZoek(); flOpslag(); flPeriode(); flOver();
  flTab(faEdit.soort === 'mail' ? 'zoek' : faUit(faEdit) ? 'herk' : 'inlog');
  faNaar('faclev');
  $('#fl-m-woorden').oninput = $('#fl-m-afz').oninput = $('#fl-m-neg').oninput = $('#fl-m-extra').oninput = flZoek;
  ['fl-lok', 'fl-map', 'fl-gd', 'fl-ec'].forEach(i => $('#' + i).onchange = flOpslag);
}
/** Heeft deze website-leverancier herkenning in de mail (na "Hoort bij" of een onthouden afzender)? */
function flHeeftHerken(l){ return !!(l && l.herken && Object.values(l.herken).some(a => a && a.length)); }
/**
 * Tabbladen: website = Inloggen, Planning, Opslaan, Meer (en Herkennen als hij ook uit de mail komt);
 * mail = Zoeken, Planning, Opslaan; leverancier uit de mail = Herkennen, Opslaan.
 */
function flTab(tt){
  const mail = faEdit && faEdit.soort === 'mail', uit = faUit(faEdit);
  const mag = mail ? ['zoek', 'plan', 'opslag'] : uit ? ['herk', 'opslag'] : ['inlog', 'plan', 'opslag', 'adv'].concat(flHeeftHerken(faEdit) ? ['herk'] : []);
  if (!mag.includes(tt)) tt = mag[0];
  document.querySelectorAll('.fl-tabs button').forEach(b => { const ok = mag.includes(b.dataset.tab); b.style.display = ok ? '' : 'none'; b.classList.toggle('on', b.dataset.tab === tt); b.setAttribute('aria-selected', b.dataset.tab === tt ? 'true' : 'false'); });
  document.querySelectorAll('.fl-paneel').forEach(p => p.classList.toggle('on', p.dataset.tab === tt));
}
/** Zoeken in de mail: de Gmail-zoekopdracht laten zien (zelfde regels als FacturenGmail.zoekvraag) en de koppeling controleren. */
function faDelen(s){ return String(s || '').split(/[,;\n]/).map(x => x.replace(/["(){}]/g, ' ').replace(/\s+/g, ' ').trim()).filter(Boolean); }
function faZoekvraag(m){
  let q = 'has:attachment filename:pdf';
  const w = faDelen(m.woorden), q1 = tt => tt.includes(' ') ? '"' + tt + '"' : tt;
  if (w.length) q += ' (' + w.map(tt => q1(tt) + (tt.includes(' ') ? '' : ' OR filename:' + tt)).join(' OR ') + ')';
  const a = faDelen(m.afzenders); if (a.length) q += ' from:(' + a.map(q1).join(' OR ') + ')';
  faDelen(m.negeer).forEach(n => q += ' -from:' + q1(n));
  if ((m.extra || '').trim()) q += ' ' + m.extra.trim();
  return q;
}
function flZoek(){
  const gd = faSt.drive || {};
  $('#fl-m-kopp').style.display = gd.rechtGmail ? 'none' : 'block';
  $('#fl-m-vraag').textContent = t('Zoekt in Gmail op: {vraag} (en alleen nieuwe mails sinds de vorige keer).', { vraag: faZoekvraag({ woorden: $('#fl-m-woorden').value, afzenders: $('#fl-m-afz').value, negeer: $('#fl-m-neg').value, extra: $('#fl-m-extra').value }) });
}
/** De overgeslagen leveranciers uit deze mail, met Terugzetten. */
function flOver(){
  const k = $('#fl-m-overkaart');
  if (!faEdit || faEdit.soort !== 'mail' || faEdit._nieuw) { k.hidden = true; return; }
  const bron = id => faSt.leveranciers.some(y => y.id === id && y.soort === 'mail');
  const l = faSt.leveranciers.filter(x => faUit(x) && x.overslaan && ((x.bronnen || []).includes(faEdit.id) || !(x.bronnen || []).some(bron)));
  k.hidden = !l.length;
  $('#fl-m-over').innerHTML = l.map(x => '<div class="bkres"><b translate="no">' + esc(faNaam(x)) + '</b><span><button class="mini" style="float:none" data-id="' + esc(x.id) + '" onclick="faTerugzetten(this.dataset.id)" aria-label="' + esc(t('{naam} terugzetten', { naam: faNaam(x) })) + '">' + esc(t('Terugzetten')) + '</button></span></div>').join('');
}
function flOpslag(){
  const mail = faEdit && faEdit.soort === 'mail';
  $('#fl-subrij').hidden = mail; $('#fl-opslag-mail').hidden = !mail; // uit de mail: elke leverancier zijn eigen map
  const gd = faSt.drive || {}, ec = faSt.ec || {}, mis = [];
  if ($('#fl-lok').checked && !(faSt.lokaal && faSt.lokaal !== '!')) mis.push('kies een map op je telefoon');
  if ($('#fl-map').checked && !faSt.map) mis.push('kies een backup-map');
  if ($('#fl-gd').checked && !gd.rechtDrive) mis.push('koppel Google met Drive');
  if ($('#fl-ec').checked && !(ec.url && ec.heeftWachtwoord)) mis.push('koppel Eigen Cloud');
  $('#fl-opslag-msg').textContent = mis.length ? t('Nog te doen bij Facturen → Opslaan: {wat}.', { wat: mis.map(x => t(x)).join(', ') }) : '';
  $('#fl-opslag-msg').classList.toggle('bad', mis.length > 0);
}
/** Volgende keer volgens de keuzes op het scherm (zelfde regels als Facturen.gepland in de app). */
function faVolgende(o, nu){
  if (o.schema === 'uit') return 0;
  // De tijd geldt in de app-zone (zoals Facturen.gepland met Regio.zone)
  const m0 = /^(\d{1,2}):(\d{2})$/.exec(o.tijd || ''), hm = m0 ? m0[1].padStart(2, '0') + ':' + m0[2] : '08:00', k0 = rgDayKey(nu), at = k => rgFromLocal(k, hm);
  if (o.schema === 'maand') {
    let y = +k0.slice(0, 4), m = +k0.slice(5, 7);
    for (let i = 0; i < 14; i++) { const last = new Date(Date.UTC(y, m, 0)).getUTCDate(), t = at(rgKey(y, m, Math.min(+o.dag || 1, last))); if (t > nu) return t; if (++m > 12) { m = 1; y++; } }
    return 0;
  }
  for (let i = 0; i < 15; i++) { const k = rgDayAdd(k0, i); if ((o.schema !== 'week' || (rgWeekday(k) + 6) % 7 + 1 === +o.weekdag) && at(k) > nu) return at(k); }
  return 0;
}
/** Welke facturen: de keuze op het scherm als {soort, maanden, vanaf} (zelfde als Facturen.periode in de app). */
function flPeriodeWaarde(){
  const v = $('#fl-periode').value;
  if (/^m\d+$/.test(v)) return { soort: 'maanden', maanden: Number(v.slice(1)) };
  if (v === 'vanaf') return { soort: 'vanaf', vanaf: $('#fl-vanaf').value };
  return { soort: v === 'alles' ? 'alles' : 'jaar' };
}
function flPeriode(){
  const p = flPeriodeWaarde(), mail = faEdit && faEdit.soort === 'mail';
  $('#fl-vanafrij').style.display = p.soort === 'vanaf' ? 'flex' : 'none';
  // Kalenderdatums vanaf vandaag in de app-zone (zoals Facturen.grens met Regio.zone); als datum getoond, verschuift niet
  const nu = rgParts(), mk = (y, m, d) => { const x = new Date(Date.UTC(y, m, d)); return rgKey(x.getUTCFullYear(), x.getUTCMonth() + 1, Math.min(d, new Date(Date.UTC(y, m + 1, 0)).getUTCDate())); };
  const vanaf = p.soort === 'jaar' ? fmtDate(nu.y + '-01-01') : p.soort === 'maanden' ? fmtDate(mk(nu.y, nu.m - 1 - p.maanden, nu.d)) : p.soort === 'vanaf' && p.vanaf ? fmtDate(p.vanaf.slice(0, 10)) : '';
  $('#fl-periode-uitleg').textContent = (p.soort === 'alles' ? t(mail ? 'Alle facturen die in je mail staan' : 'Alle facturen die de website laat zien') : vanaf ? t('Facturen vanaf {datum}', { datum: vanaf }) : t('Kies een datum')) + '. '
    + t(mail ? 'De eerste keer zoekt de app vanaf dat moment; daarna alleen nieuwe mails.' : 'De app klikt op "Toon meer" om oudere facturen te laden en bewaart alleen facturen vanaf dat moment (op de factuurdatum).')
    + ' ' + t('Wil je na een wijziging ook de oudere facturen alsnog? Kies dan bij de leverancier "Alles van de periode opnieuw ophalen".');
}
function flPlan(){
  const s = $('#fl-schema').value;
  $('#fl-dagrij').style.display = s === 'maand' ? 'flex' : 'none';
  $('#fl-wdrij').style.display = s === 'week' ? 'flex' : 'none';
  $('#fl-tijdrij').style.display = s === 'uit' ? 'none' : 'flex';
  $('#fl-opnrij').style.display = s === 'uit' ? 'none' : 'flex';
  $('#fl-wifirij').style.display = s === 'uit' ? 'none' : 'flex';
  const o = { schema: s, dag: Number($('#fl-dag').value) || 5, weekdag: Number($('#fl-wd').value) || 1, tijd: $('#fl-tijd').value || '08:00' };
  const opn = Number($('#fl-opnieuw').value) || 3;
  $('#fl-plan').textContent = s === 'uit' ? t('Alleen als je zelf op Nu ophalen tikt.') : t('Vanzelf {wanneer}. Eerstvolgende keer: {volgende}.', { wanneer: faPlanTekst(o), volgende: faMoment(faVolgende(o, Date.now())) }) + ' ' +
    t($('#fl-wifi').checked ? 'Staat de telefoon dan uit of zonder wifi, dan haalt de app het zo snel mogelijk daarna in; lukt het niet, dan na {n} uur opnieuw.' : 'Staat de telefoon dan uit of zonder internet, dan haalt de app het zo snel mogelijk daarna in; lukt het niet, dan na {n} uur opnieuw.', { n: opn });
}
function flBron(){
  const b = $('#fl-bron').value;
  $('#fl-bronvelden').style.display = b === 'geen' ? 'none' : 'block';
  $('#fl-sms').style.display = b === 'sms' && !faSt.sms ? 'block' : 'none';
}
function flBewaar(){
  const uit = faUit(faEdit), herk = uit || flHeeftHerken(faEdit);
  const o = {
    id: faEdit && !faEdit._nieuw ? faEdit.id : '', sjabloon: uit ? 'uitmail' : faEdit ? faEdit.sjabloon || 'anders' : 'anders', soort: faEdit && faEdit.soort === 'mail' ? 'mail' : uit ? 'uitmail' : 'website',
    mail: { bron: $('#fl-m-bron').value, woorden: $('#fl-m-woorden').value, afzenders: $('#fl-m-afz').value, negeer: $('#fl-m-neg').value, extra: $('#fl-m-extra').value.trim(),
      max: Number($('#fl-m-max').value) || 50 },
    ...(herk ? { herken: { afzenders: $('#fl-h-afz').value, domeinen: $('#fl-h-dom').value, namen: $('#fl-h-namen').value, btw: $('#fl-h-btw').value, kvk: $('#fl-h-kvk').value, iban: $('#fl-h-iban').value } } : {}),
    ...(uit ? { nieuw: false, overslaan: !!faEdit.overslaan, voorstel: '', bronnen: faEdit.bronnen || [] } : {}),
    naam: $('#fl-naam').value.trim(), startUrl: $('#fl-start').value.trim(), gebruiker: $('#fl-user').value.trim(), wachtwoord: $('#fl-pw').value,
    periode: flPeriodeWaarde(), meer: $('#fl-meer').value.trim(),
    facturenUrl: $('#fl-facturl').value.trim(), domeinen: $('#fl-domeinen').value.trim(), menu: $('#fl-menu').value.trim(), knoppen: $('#fl-knoppen').value.trim(), max: Number($('#fl-max').value) || 50,
    code: { bron: $('#fl-bron').value, afzender: $('#fl-afz').value.trim(), patroon: $('#fl-pat').value.trim() },
    schema: $('#fl-schema').value, dag: Number($('#fl-dag').value) || 5, weekdag: Number($('#fl-wd').value) || 1, tijd: $('#fl-tijd').value || '08:00',
    opnieuw: Number($('#fl-opnieuw').value) || 3, wifi: $('#fl-wifi').checked, meldNieuw: $('#fl-meldnieuw').checked, meldFout: $('#fl-meldfout').checked, map: $('#fl-map').checked, lokaal: $('#fl-lok').checked, drive: $('#fl-gd').checked, eigenCloud: $('#fl-ec').checked, submap: $('#fl-sub').value.trim(),
    velden: { gebruiker: $('#fl-v-gebruiker').value.trim(), wachtwoord: $('#fl-v-wachtwoord').value.trim(), code: $('#fl-v-code').value.trim(), knop: $('#fl-v-knop').value.trim() }
  };
  for (const [p, wat] of [[o.menu, 'De tekst van de link naar de facturen'], [o.knoppen, 'Het herkennen van downloadknoppen']]) {
    if (o.soort !== 'website') break;
    try { new RegExp(p, 'i'); } catch (e) { $('#fl-msg').textContent = t('{wat} is geen geldig zoekpatroon.', { wat: t(wat) }); flTab('adv'); return; }
  }
  const fout = (tt, tab) => { $('#fl-msg').textContent = tt; flTab(tab); };
  if (o.lokaal && !faSt.lokaal) return fout('Kies eerst een map op je telefoon (Facturen → Opslaan), of zet "In een map op je telefoon" uit.', 'opslag');
  if (o.drive && !(faSt.drive || {}).rechtDrive) return fout('Koppel eerst Google met Drive (Facturen → Koppelingen), of zet "In Google Drive" uit.', 'opslag');
  const ec = faSt.ec || {};
  if (o.eigenCloud && !(ec.url && ec.heeftWachtwoord)) return fout('Koppel eerst Eigen Cloud (Facturen → Koppelingen), of zet "Naar je mail in Eigen Cloud" uit.', 'opslag');
  if (o.soort !== 'mail' && o.code.bron === 'eigencloud' && !(ec.url && ec.heeftWachtwoord)) return fout('Koppel eerst Eigen Cloud om de code uit je mail te lezen.', 'inlog');
  if (o.soort === 'mail' && !faDelen(o.mail.woorden).length && !faDelen(o.mail.afzenders).length && !o.mail.extra) return fout('Vul zoekwoorden of afzenders in, anders zoekt de app naar elke PDF in je mail.', 'zoek');
  if (o.soort === 'mail') { // je eigen nummers en adressen (voor alle mail)
    const eg = faSt.herkennen || {}, nr = $('#fl-e-nr').value.trim(), adr = $('#fl-e-adr').value.trim();
    if (nr !== (eg.nummers || []).join(', ') || adr !== (eg.adressen || []).join(', ')) {
      const e = Android.facturenHerkennen(JSON.stringify({ nummers: nr, adressen: adr }));
      if (e) return fout(e, 'zoek');
    }
  }
  const r = rdJson(Android.facturenBewaar(JSON.stringify(o)), { error: 'Bewaren lukt niet' });
  $('#fl-pw').value = '';
  if (r.error) {
    $('#fl-msg').textContent = r.error;
    const e = r.error;
    if (uit) flTab(/map/i.test(e) ? 'opslag' : 'herk');
    else if (herk && /KvK|btw|IBAN|mailadres|domein van de afzender/i.test(e)) flTab('herk');
    else if (/facturenpagina|veld|patroon|menu|knoppen/i.test(e)) flTab('adv');
    else if (/zoekwoord|afzender|zoekopdracht|mailbron/i.test(e)) flTab('zoek');
    else if (/domein/i.test(e)) flTab('adv');
    else if (/inlogpagina|gebruiker|bron voor de inlogcode/i.test(e)) flTab('inlog');
    else if (/tijd|hoe vaak/i.test(e)) flTab('plan');
    else if (/submap|mapnaam/i.test(e)) flTab('opslag');
    return;
  }
  const nieuw = faEdit && faEdit._nieuw;
  goBack(); faRender();
  if (o.soort !== 'mail' && o.code.bron === 'sms' && !faSt.sms) Android.smsRequestPermission();
  if (nieuw && o.soort === 'mail') setTimeout(() => (faSt.drive || {}).rechtGmail ? askConfirm('Nu zoeken?', 'De app zoekt nu in je Gmail naar facturen van de afgelopen tijd.', 'Nu zoeken', () => faNu(r.id, false)) : askConfirm('Google koppelen', 'Om in Gmail te zoeken moet Tools eerst toegang krijgen tot Gmail (alleen lezen).', 'Nu koppelen', () => faNaar('facgoogle')), 300);
  else if (nieuw) setTimeout(() => askConfirm('Nu ophalen?', 'De eerste keer is meekijken handig: je ziet of de app de inlogvelden en downloadknoppen vindt, en je kunt zelf bijspringen.', 'Meekijken', () => faNu(r.id, true)), 300);
  else toast('✓ Bewaard');
}
/* Map op de telefoon en Google Drive */
window.onFacturenMap = function(fout){
  if (fout && fout !== 'geen') toast(fout); else if (!fout) toast('✓ Map gekozen');
  if (FA_SCHERMEN.includes(current)) faRender();
};
/** De aangevinkte rechten ("gmail,drive,gmailsend"), of '' met een melding. */
function faGdRechten(){
  const rechten = [$('#fa-gd-r-gmail').checked ? 'gmail' : '', $('#fa-gd-r-drive').checked ? 'drive' : '', $('#fa-gd-r-send').checked ? 'gmailsend' : ''].filter(Boolean).join(',');
  if (!rechten) $('#fa-gd-msg').textContent = 'Vink aan waarvoor Tools Google mag gebruiken (Gmail en/of Drive).';
  return rechten;
}
function faGdToestel(){
  $('#fa-gd-msg').textContent = '';
  const rechten = faGdRechten(); if (!rechten) return;
  const e = Android.facturenGoogleToestel(rechten);
  if (e) { $('#fa-gd-msg').textContent = e; return; }
  toast('Kies je Google-account');
}
function faGdKoppel(){
  $('#fa-gd-msg').textContent = '';
  const id = $('#fa-gd-id').value.trim(), geheim = $('#fa-gd-geheim').value.trim();
  if (!/^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/.test(id)) { $('#fa-gd-msg').textContent = 'Een Client ID van Google eindigt op .apps.googleusercontent.com. Kopieer hem uit je Google-project.'; return; }
  const gd = faSt.drive || {};
  if (!geheim && (!gd.heeftGeheim || id !== gd.clientId)) { $('#fa-gd-msg').textContent = t(gd.heeftGeheim ? 'Vul ook het Client secret in (nieuwe Client ID = ook een nieuw secret).' : 'Vul ook het Client secret in.'); return; }
  const rechten = [$('#fa-gd-r-gmail').checked ? 'gmail' : '', $('#fa-gd-r-drive').checked ? 'drive' : '', $('#fa-gd-r-send').checked ? 'gmailsend' : ''].filter(Boolean).join(',');
  if (!rechten) { $('#fa-gd-msg').textContent = 'Vink aan waarvoor Tools Google mag gebruiken (Gmail en/of Drive).'; return; }
  const zonder = [!rechten.includes('drive') && faSt.leveranciers.some(l => l.drive) ? 'Drive (leveranciers bewaren daar)' : '', !/\bgmail\b/.test(rechten) && faSt.leveranciers.some(l => l.soort === 'mail') ? 'Gmail (leveranciers zoeken daar)' : '', !rechten.includes('gmailsend') && (faSt.drive || {}).rechtGmailSend ? 'Versturen met Gmail (aan je boekhouder)' : ''].filter(Boolean);
  if (zonder.length && !faGdKoppel._ok) { askConfirm(t('Minder rechten?'), t('Zonder {wat} werken die leveranciers niet meer tot je het weer aanzet.', { wat: zonder.map(x => t(x)).join(t(' en ')) }), t('Toch koppelen'), () => { faGdKoppel._ok = true; try { faGdKoppel(); } finally { faGdKoppel._ok = false; } }); return; }
  const e = Android.facturenDriveKoppel(id, geheim, rechten);
  $('#fa-gd-geheim').value = '';
  if (e) { $('#fa-gd-msg').textContent = e; return; }
  toast('Log in bij Google en geef toestemming; kom daarna terug');
}
window.onFacturenDrive = function(r){
  if (r.error) { $('#fa-gd-msg').textContent = r.error; toast(r.error); }
  else toast(t('✓ Google gekoppeld') + (r.naam ? ' (' + r.naam + ')' : ''));
  if (FA_SCHERMEN.includes(current)) faRender();
  if (current === 'faclev') { flZoek(); flOpslag(); }
};
/* Eigen Cloud */
function faEcBewaar(){
  const e = Android.facturenEc(JSON.stringify({ url: $('#fa-ec-url').value.trim(), gebruiker: $('#fa-ec-user').value.trim(), adres: $('#fa-ec-adres').value.trim(), wachtwoord: $('#fa-ec-pw').value }));
  $('#fa-ec-pw').value = '';
  $('#fa-ec-msg').textContent = e || '';
  if (!e) { if (document.activeElement) document.activeElement.blur(); faRender(); toast('✓ Bewaard'); }
}
function faEcTest(){ $('#fa-ec-msg').classList.remove('bad'); uiWachtTekst($('#fa-ec-msg'), t('Verbinding testen…')); $('#fa-ec-test').disabled = true; Android.facturenEcTest(); }
window.onFacturenEcTest = function(r){
  $('#fa-ec-test').disabled = false;
  $('#fa-ec-msg').textContent = r.error ? r.error : ''; $('#fa-ec-msg').classList.add('bad');
  if (!r.error) toast('✓ Verbinding met Eigen Cloud werkt');
};

/* ---------- Dossier: alle opgehaalde facturen met hun gegevens ---------- */
let faDos = null, fsSha = '';
const FA_PLEK = { app: 'In de app', lok: 'Map op je telefoon', map: 'Backup-map', gd: 'Google Drive', ec: 'Eigen Cloud (mail)' };
function fdLaad(){ faDos = rdJson(Android.facturenDossier(), { items: [], ontvangers: [] }); return faDos; }
/** Stand van het aanleveren van één factuur: {soort: ok|mis|open|geen, tekst}. */
function fdAanl(s){
  if (s._docs && s._docs.length > 1) { // samengevoegd: het slechtste van alle documenten
    const l = s._docs.map(d => fdAanl1(d));
    return l.find(a => a.soort === 'mis') || l.find(a => a.soort === 'open') || l[0];
  }
  return fdAanl1(s);
}
function fdAanl1(s){
  const v = Object.entries(s.verzonden || {}), ok = v.filter(([, x]) => x.ok), mis = v.filter(([, x]) => !x.ok && !x.opnieuw);
  if (mis.length) return { soort: 'mis', tekst: t('✗ Niet aangeleverd aan {wie}', { wie: mis.map(([id, x]) => x.naam || id).join(', ') }) };
  if (ok.length) return { soort: 'ok', tekst: t('✓ Aangeleverd aan {wie}', { wie: ok.map(([id, x]) => (x.naam || id) + ' (' + faDatum(x.t) + ')').join(', ') }) };
  return { soort: (faDos.ontvangers || []).length ? 'open' : 'geen', tekst: (faDos.ontvangers || []).length ? t('Nog niet aangeleverd') : '' };
}
/* Het overzicht: een tabel met kolommen naar keuze, sorteren op elke kolom en groeperen met subtotalen */
const FD_DAG = d => d ? fmtDate(d + 'T12:00', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
const FD_EUR = v => v === '' || v == null || isNaN(v) ? '' : fmtMoney(Number(v), 'EUR', { minusIn: true }); // facturen zijn in euro; negatief zoals altijd: "€ -12,50"
/** "1.234,56", "1234,56", "12.50", "1,234.56" → getal (zelfde regels als FacturenDossier.bedrag). */
const fdGetal = x => { let s = String(x || '').replace(/[€\s]|eur/gi, ''); if (/^-?\d{1,3}(\.\d{3})+(,\d{1,2})?$/.test(s)) s = s.replace(/\./g, ''); else if (/^-?\d{1,3}(,\d{3})+(\.\d{1,2})?$/.test(s)) s = s.replace(/,/g, ''); const v = parseFloat(s.replace(',', '.')); return isNaN(v) ? '' : v; };
const fdVandaag = () => rgToday();
/** De datum van een factuur: de factuurdatum, of (onbekend) de dag waarop hij is opgehaald. JJJJ-MM-DD. */
const fdDatum = s => (s.meta || {}).datum || (s.opgehaald ? rgDayKey(s.opgehaald) : '');
/** Van en tot (JJJJ-MM-DD, tot en met) bij een periode; leeg = geen grens. */
function fdVanTot(p, van, tot, nu){
  const d = rgParts(nu || Date.now()), y = d.y, m = d.m - 1, q = Math.floor(m / 3); // vandaag in de app-zone
  const dag = (yy, mm, dd) => { const x = new Date(Date.UTC(yy, mm, dd)); return rgKey(x.getUTCFullYear(), x.getUTCMonth() + 1, x.getUTCDate()); };
  if (p === 'maand') return [dag(y, m, 1), dag(y, m + 1, 0)];
  if (p === 'vmaand') return [dag(y, m - 1, 1), dag(y, m, 0)];
  if (p === 'kwartaal') return [dag(y, q * 3, 1), dag(y, q * 3 + 3, 0)];
  if (p === 'vkwartaal') return [dag(y, q * 3 - 3, 1), dag(y, q * 3, 0)];
  if (p === 'jaar') return [dag(y, 0, 1), dag(y, 11, 31)];
  if (p === 'vjaar') return [dag(y - 1, 0, 1), dag(y - 1, 11, 31)];
  if (p === 'mnd' && /^\d{4}-\d{2}$/.test(van || '')) { const yy = +van.slice(0, 4), mm = +van.slice(5, 7) - 1; return [dag(yy, mm, 1), dag(yy, mm + 1, 0)]; }
  if (p === 'kjaar' && /^\d{4}$/.test(van || '')) return [van + '-01-01', van + '-12-31'];
  if (p === 'mnd' || p === 'kjaar') return ['', ''];
  if (p === 'zelf') return van && tot && van > tot ? [tot, van] : [van || '', tot || '']; // omgedraaid ingevuld: gewoon omdraaien
  return ['', ''];
}
function fdPeriode(){
  const i = fdInst(); i.periode = $('#fd-periode').value; i.van = $('#fd-van').value; i.tot = $('#fd-tot').value;
  i.maand = $('#fd-mnd').value; i.jaar = $('#fd-jaar').value;
  $('#fd-vantot').style.display = i.periode === 'zelf' ? 'flex' : 'none';
  fdBewaarInst(i); fdRender();
}
/** Ontbrekende gegevens van eerder opgehaalde facturen uit de PDF's lezen (één keer per keer openen). */
let fdGelezen = false;
function fdLees(){
  if (fdGelezen || !faDos) return;
  fdGelezen = true;
  const e = $('#fd-lees');
  if (faDos.bezigMetLezen) { e.style.display = 'block'; uiWachtTekst(e, t('Gegevens uit de facturen lezen…')); return; }
  e.style.display = 'none'; e.textContent = '';
  const mist = faDos.teLezen || 0; // de app telt zelf wat er nog gelezen moet worden
  if (!faDos.pdfTekst) {
    if ((faDos.items || []).some(s => !(s.meta || {}).bedrag)) { $('#fd-lees').style.display = 'block'; $('#fd-lees').textContent = 'Op deze telefoon (Android 14 of ouder) kan de app de factuurgegevens niet uit de PDF lezen. Tik op een factuur om datum en bedrag zelf in te vullen.'; }
    return;
  }
  if (!mist) return;
  $('#fd-lees').style.display = 'block'; uiWachtTekst($('#fd-lees'), tn(mist, 'Gegevens uit {n} factuur lezen…', 'Gegevens uit {n} facturen lezen…'));
  Android.facturenDossierHerken();
}
window.onFacturenHerkend = function(r){
  const e = $('#fd-lees');
  if (r.error) { e.textContent = t('Lezen lukt niet: {fout}', { fout: t(r.error) }); return; }
  e.textContent = r.aantal ? tn(r.aantal, '✓ Bij {n} factuur gegevens gevonden. Controleer ze even.', '✓ Bij {n} facturen gegevens gevonden. Controleer ze even.') : t('Geen extra gegevens gevonden in de facturen. Tik op een factuur om ze zelf in te vullen.');
  setTimeout(() => { if (e.textContent.startsWith('✓')) e.style.display = 'none'; }, 6000);
  if (current === 'facdos') fdRender(true);
  else if (current === 'facstuk') { fdLaad(); if (!document.activeElement || !document.activeElement.closest('#s-facstuk')) fsToon(fsSha); }
};
/** Alle velden die als kolom kunnen: id, naam, waarde (om te sorteren), tekst (in de tabel), num = bedrag (rechts, met totaal). */
const FD_VELDEN = [
  { id: 'datum', naam: 'Factuurdatum', w: s => fdDatum(s), t: s => (s.meta || {}).datum ? FD_DAG(s.meta.datum) : s.opgehaald ? FD_DAG(fdDatum(s)) + ' *' : '' },
  { id: 'lev', naam: 'Leverancier', w: s => (s.leverancierNaam || '').toLowerCase(), t: s => (s.leverancierNaam || '') + (s._docs ? fdDocsTekst(s._docs.length) : '') },
  { id: 'bedrag', naam: 'Bedrag', num: true, w: s => fdGetal((s.meta || {}).bedrag), t: s => FD_EUR(fdGetal((s.meta || {}).bedrag)) },
  { id: 'btw', naam: 'Btw', num: true, w: s => fdGetal((s.meta || {}).btw), t: s => FD_EUR(fdGetal((s.meta || {}).btw)) },
  { id: 'nummer', naam: 'Factuurnummer', w: s => (s.meta || {}).nummer || '', t: s => (s.meta || {}).nummer || '' },
  { id: 'verval', naam: 'Uiterste betaaldatum', w: s => (s.meta || {}).vervaldatum || '', t: s => { const m = s.meta || {}; return FD_DAG(m.vervaldatum) + (m.vervaldatum && !m.betaald && m.vervaldatum < fdVandaag() ? ' ⚠' : ''); } },
  { id: 'betaald', naam: 'Betaald op', w: s => (s.meta || {}).betaald || '', t: s => FD_DAG((s.meta || {}).betaald) },
  { id: 'cat', naam: 'Categorie', w: s => ((s.meta || {}).categorie || '').toLowerCase(), t: s => (s.meta || {}).categorie || '' },
  { id: 'aan', naam: 'Aangeleverd', w: s => { const a = fdAanl(s); return a.soort === 'ok' ? 2 : a.soort === 'mis' ? 0 : 1; }, t: s => { const a = fdAanl(s); return a.soort === 'ok' ? '✓' : a.soort === 'mis' ? '✗' : a.soort === 'open' ? '–' : ''; } },
  { id: 'opgehaald', naam: 'Opgehaald', w: s => s.opgehaald || 0, t: s => faDatum(s.opgehaald) },
  { id: 'bestand', naam: 'Bestand', lang: true, w: s => (s.naam || '').toLowerCase(), t: s => s.naam || '' },
  { id: 'afzender', naam: 'Afzender (mail)', lang: true, w: s => ((s.bron || {}).afzender || '').toLowerCase(), t: s => (s.bron || {}).afzender || '' },
  { id: 'notitie', naam: 'Notitie', lang: true, w: s => ((s.meta || {}).notitie || '').toLowerCase(), t: s => (s.meta || {}).notitie || '' }];
const FD_STANDAARD = { kolommen: ['datum', 'lev', 'bedrag', 'verval', 'aan'], sort: 'datum', af: true, groep: '', periode: '', van: '', tot: '' };
function fdInst(){
  const o = store.get('faDosWeergave', null) || {}, k = Array.isArray(o.kolommen) ? o.kolommen.filter(x => FD_VELDEN.some(v => v.id === x)) : [];
  return { ...FD_STANDAARD, ...o, kolommen: k.length ? k : FD_STANDAARD.kolommen, groep: ['', 'maand', 'kwartaal', 'lev', 'cat'].includes(o.groep) ? o.groep : '', periode: ['', 'maand', 'vmaand', 'kwartaal', 'vkwartaal', 'jaar', 'vjaar', 'mnd', 'kjaar', 'zelf'].includes(o.periode) ? o.periode : '', sort: FD_VELDEN.some(v => v.id === o.sort) ? o.sort : FD_STANDAARD.sort };
}
function fdBewaarInst(i){ try { store.set('faDosWeergave', i); } catch (e) { } }
function fdKolommen(){
  const open = $('#fd-kol').style.display === 'none';
  $('#fd-kol').style.display = open ? 'block' : 'none'; $('#fd-kol-knop').setAttribute('aria-expanded', String(open));
  const k = fdInst().kolommen;
  $('#fd-kol-lijst').innerHTML = FD_VELDEN.map(v => '<label class="chk"><input type="checkbox" value="' + v.id + '"' + (k.includes(v.id) ? ' checked' : '') + ' onchange="fdKolom(this)"> ' + esc(v.naam) + '</label>').join('');
}
function fdKolom(el){
  const i = fdInst();
  i.kolommen = FD_VELDEN.map(v => v.id).filter(id => id === el.value ? el.checked : i.kolommen.includes(id));
  if (!i.kolommen.length) { el.checked = true; toast('Kies minstens één kolom'); return; }
  fdBewaarInst(i); fdRender();
}
function fdSorteer(id){ const i = fdInst(); if (i.sort === id) i.af = !i.af; else { i.sort = id; i.af = ['datum', 'verval', 'betaald', 'opgehaald', 'bedrag', 'btw'].includes(id); } fdBewaarInst(i); fdRender(); }
function fdKeuze(){ const i = fdInst(); i.groep = $('#fd-groep').value; fdBewaarInst(i); fdRender(); }
/** Groep van een factuur: maand/kwartaal van de factuurdatum (anders van ophalen), leverancier of categorie. */
function fdGroep(s, g){
  const d = fdDatum(s);
  if (g === 'maand') return d ? [d.slice(0, 7), faMaandNaam(d.slice(0, 7))] : ['', 'Zonder datum'];
  if (g === 'kwartaal') return d ? [d.slice(0, 4) + '-' + Math.ceil(+d.slice(5, 7) / 3), 'Q' + Math.ceil(+d.slice(5, 7) / 3) + ' ' + d.slice(0, 4)] : ['', 'Zonder datum'];
  if (g === 'lev') return s.leverancierNaam ? ['0' + s.leverancierNaam.toLowerCase(), s.leverancierNaam] : ['1', 'Onbekend'];
  if (g === 'cat') { const c = (s.meta || {}).categorie || ''; return [c ? '0' + c.toLowerCase() : '1', c || 'Zonder categorie']; }
  return ['', ''];
}
/**
 * Welke documenten bij één factuur horen (zelfde regels als FacturenDossier.groepen in de app): zelfde leverancier
 * (bij mail: afzender) en factuurnummer; een document zonder nummer uit dezelfde mail als een factuur met nummer hoort
 * daarbij (bijlage, specificatie, voorwaarden); zonder nummer in één mail samen ook. Geeft sha → sleutel.
 */
function fdGroepen(items){
  const uit = new Map(), perMail = new Map(), nr = s => String((s.meta || {}).nummer || '').trim().toLowerCase(), mid = s => (s.bron || {}).mailId || '';
  const l = items.slice().sort((a, b) => a.sha < b.sha ? -1 : a.sha > b.sha ? 1 : 0);
  // Zelfde nummer in een andere maand: aparte facturen; een document met nummer zonder datum hoort bij de eerste met dat nummer
  const mnd = s => /^\d{4}-\d{2}-\d{2}$/.test((s.meta || {}).datum || '') ? s.meta.datum.slice(0, 7) : '', eerste = new Map();
  for (const ronde of [0, 1]) for (const s of l) {
    if (!nr(s) || (ronde === 0) === !mnd(s)) continue;
    const basis = 'n|' + (s.leverancier || '') + '|' + ((s.bron || {}).afzender || '') + '|' + nr(s);
    const g = mnd(s) ? basis + '|' + mnd(s) : eerste.has(basis) ? eerste.get(basis) : basis;
    if (!eerste.has(basis)) eerste.set(basis, g);
    uit.set(s.sha, g);
    if (mid(s) && !perMail.has((s.leverancier || '') + '|' + mid(s))) perMail.set((s.leverancier || '') + '|' + mid(s), g);
  }
  for (const s of l) { if (uit.has(s.sha)) continue; const lv = s.leverancier || ''; uit.set(s.sha, mid(s) ? (perMail.get(lv + '|' + mid(s)) || 'm|' + lv + '|' + mid(s)) : 's|' + s.sha); }
  return uit;
}
/** Het hoofddocument van een factuur: met bedrag en datum, anders met bedrag, anders het eerste. */
function fdHoofd(docs){ return docs.find(d => (d.meta || {}).bedrag && (d.meta || {}).datum) || docs.find(d => (d.meta || {}).bedrag) || docs[0]; }
/**
 * Dezelfde factuur in meer documenten (bijv. de factuur en de specificatie, of twee keer gedownload): één regel per
 * factuur, met de gegevens van het hoofddocument (waarin het bedrag staat), aangevuld uit de andere.
 */
function fdSamen(items){
  const g = fdGroepen(items), m = new Map(), uit = [];
  for (const s of items) { const k = g.get(s.sha); const p = m.get(k); if (p) { p._docs.push(s); continue; } const c = { ...s, _docs: [s] }; m.set(k, c); uit.push(c); }
  for (const c of uit) if (c._docs.length > 1) {
    const best = fdHoofd(c._docs), meta = { ...(best.meta || {}) };
    for (const d of c._docs) for (const [k, v] of Object.entries(d.meta || {})) if (!meta[k] && v) meta[k] = v;
    c._docs = [best].concat(c._docs.filter(d => d !== best)); // hoofddocument eerst
    Object.assign(c, best, { meta, _docs: c._docs });
  }
  return uit;
}
/** "1 factuur + 2 bijlagen" voor een samengevoegde regel. */
function fdDocsTekst(n){ return n > 1 ? ' ' + tn(n - 1, '(+ {n} bijlage)', '(+ {n} bijlagen)') : ''; }
function fdRender(laad){
  if (laad || !faDos) fdLaad();
  const docs = faDos.items || [], items = fdSamen(docs), inst = fdInst();
  $('#fd-groep').value = inst.groep; $('#fd-periode').value = inst.periode;
  if (document.activeElement !== $('#fd-van')) $('#fd-van').value = inst.van || '';
  if (document.activeElement !== $('#fd-tot')) $('#fd-tot').value = inst.tot || '';
  $('#fd-vantot').style.display = inst.periode === 'zelf' ? 'flex' : 'none';
  // Maand of jaar kiezen: standaard deze maand / dit jaar; de jaren waarin facturen staan
  const jaren = [...new Set(docs.map(s => fdDatum(s).slice(0, 4)).filter(Boolean).concat([String(new Date().getFullYear())]))].sort().reverse();
  if ([...$('#fd-jaar').options].map(o => o.value).join() !== jaren.join()) $('#fd-jaar').innerHTML = jaren.map(y => '<option value="' + y + '">' + y + '</option>').join('');
  $('#fd-mndrij').style.display = inst.periode === 'mnd' || inst.periode === 'kjaar' ? 'flex' : 'none';
  $('#fd-mnd').style.display = inst.periode === 'mnd' ? '' : 'none'; $('#fd-jaar').style.display = inst.periode === 'kjaar' ? '' : 'none';
  $('#fd-mnd-l').textContent = inst.periode === 'kjaar' ? 'Jaar' : 'Maand';
  if (document.activeElement !== $('#fd-mnd')) $('#fd-mnd').value = inst.maand || fdVandaag().slice(0, 7);
  $('#fd-jaar').value = jaren.includes(inst.jaar) ? inst.jaar : jaren[0];
  const [pvan, ptot] = fdVanTot(inst.periode, inst.periode === 'mnd' ? $('#fd-mnd').value : inst.periode === 'kjaar' ? $('#fd-jaar').value : inst.van, inst.tot);
  const sel = $('#fd-lev'), was = fdLevKeuze || sel.value, levs = [...new Map(items.map(s => [s.leverancier, s.leverancier === FA_NIET_HERKEND && (!s.leverancierNaam || s.leverancierNaam === 'Niet herkend') ? t('Niet herkend') : s.leverancierNaam || s.leverancier])).entries()];
  fdLevKeuze = '';
  sel.innerHTML = '<option value="">Alle leveranciers</option>' + levs.map(([id, n]) => '<option value="' + esc(id) + '" translate="no">' + esc(n) + '</option>').join('');
  sel.value = levs.some(([id]) => id === was) ? was : '';
  const q = $('#fd-zoek').value.trim().toLowerCase(), f = $('#fd-filter').value, vandaag = fdVandaag();
  let lijst = items.filter(s => {
    const m = s.meta || {};
    if (sel.value && s.leverancier !== sel.value) return false;
    if (pvan || ptot) { const d = fdDatum(s); if (!d || (pvan && d < pvan) || (ptot && d > ptot)) return false; }
    const a = fdAanl(s);
    if (f === 'open' && a.soort !== 'open' && a.soort !== 'mis') return false;
    if (f === 'mis' && a.soort !== 'mis') return false;
    if (f === 'tebetalen' && (m.betaald || !m.vervaldatum || m.vervaldatum < vandaag)) return false;
    if (f === 'telaat' && (m.betaald || !m.vervaldatum || m.vervaldatum >= vandaag)) return false;
    if (!q) return true;
    return [s.leverancierNaam, s.naam, m.nummer, m.bedrag, m.datum, m.categorie, m.notitie, (s.bron || {}).afzender, (s.bron || {}).onderwerp].some(x => String(x || '').toLowerCase().includes(q));
  });
  const kol = inst.kolommen.map(id => FD_VELDEN.find(v => v.id === id)).filter(Boolean), sv = FD_VELDEN.find(v => v.id === inst.sort) || FD_VELDEN[0];
  const vgl = (x, y) => { const a = sv.w(x), b = sv.w(y); if (a === '' && b !== '') return 1; if (b === '' && a !== '') return -1; const r = a < b ? -1 : a > b ? 1 : 0; return inst.af ? -r : r; };
  lijst = lijst.slice().sort((x, y) => { if (inst.groep) { const gx = fdGroep(x, inst.groep)[0], gy = fdGroep(y, inst.groep)[0]; if (gx !== gy) return (inst.groep === 'maand' || inst.groep === 'kwartaal') ? (gx < gy ? 1 : -1) : (gx < gy ? -1 : 1); } return vgl(x, y) || (y.opgehaald || 0) - (x.opgehaald || 0); });
  const som = l => l.reduce((tt, s) => tt + (fdGetal((s.meta || {}).bedrag) || 0), 0);
  const totaal = som(lijst);
  $('#fd-tel').textContent = items.length ? tn(items.length, '{k} van {n} factuur', '{k} van {n} facturen', { k: lijst.length }) + (docs.length > items.length ? ' ' + t('({n} documenten: facturen met hun bijlagen)', { n: docs.length }) : '') + (totaal ? ' · ' + t('samen {bedrag}', { bedrag: FD_EUR(totaal) }) : '') : '';
  if (!lijst.length) { $('#fd-lijst').innerHTML = '<p class="note" style="padding:14px">' + (items.length ? 'Niets gevonden.' : 'Nog geen facturen in het dossier. Ze komen er vanzelf in zodra er facturen zijn opgehaald.') + '</p>'; $('#fd-csv').disabled = !items.length; return; }
  const kop = '<thead><tr>' + kol.map(v => '<th scope="col" class="' + (v.num ? 'num' : '') + '"' + (v.id === sv.id ? ' aria-sort="' + (inst.af ? 'descending' : 'ascending') + '"' : '') + '><button data-k="' + v.id + '" onclick="fdSorteer(this.dataset.k)">' + esc(v.naam) + (v.id === sv.id ? '<span aria-hidden="true">' + (inst.af ? ' ↓' : ' ↑') + '</span>' : '') + '</button></th>').join('') + '</tr></thead>';
  const cel = (v, s) => '<td class="' + [v.num ? 'num' : '', v.lang ? 'lang' : '', v.id === 'aan' && fdAanl(s).soort === 'ok' ? 'good' : '', v.id === 'aan' && fdAanl(s).soort === 'mis' ? 'bad' : '', v.id === 'verval' && /⚠/.test(v.t(s)) ? 'bad' : ''].filter(Boolean).join(' ') + '">' + esc(v.t(s)) + '</td>';
  const rij = s => '<tr class="fd-rij" tabindex="0" data-sha="' + esc(s.sha) + '">' + kol.map(v => cel(v, s)).join('') + '</tr>';
  const somRij = l => '<tr>' + kol.map((v, i) => '<td class="' + (v.num ? 'num' : '') + '">' + (v.id === 'bedrag' ? esc(FD_EUR(som(l))) : v.id === 'btw' ? esc(FD_EUR(l.reduce((tt, s) => tt + (fdGetal((s.meta || {}).btw) || 0), 0))) : i === 0 ? 'Totaal' : '') + '</td>').join('') + '</tr>';
  let body = '', groep = null;
  const delen = [];
  for (const s of lijst) { const g = inst.groep ? fdGroep(s, inst.groep) : ['', '']; if (!groep || g[0] !== groep.k) { groep = { k: g[0], naam: g[1], l: [] }; delen.push(groep); } groep.l.push(s); }
  for (const g of delen) {
    if (inst.groep) body += '<tr class="fd-groep"><td colspan="' + kol.length + '">' + esc(g.naam + ' · ' + tn(g.l.length, '{n} factuur', '{n} facturen') + (som(g.l) ? ' · ' + FD_EUR(som(g.l)) : '')) + '</td></tr>';
    const nog = 500 - (body.match(/class="fd-rij"/g) || []).length;
    if (nog <= 0) break;
    body += g.l.slice(0, nog).map(rij).join('');
  }
  $('#fd-lijst').innerHTML = '<table class="fa-tab fd-tab">' + kop + '<tbody>' + body + '</tbody>' + (kol.some(v => v.id === 'bedrag' || v.id === 'btw') && lijst.some(s => fdGetal((s.meta || {}).bedrag) !== '') ? '<tfoot>' + somRij(lijst) + '</tfoot>' : '') + '</table>' + (lijst.length > 500 ? '<p class="note" style="padding:8px 14px">De eerste 500; zoek of filter om de rest te zien.</p>' : '');
  if (kol.some(v => v.id === 'datum') && lijst.some(s => !(s.meta || {}).datum)) $('#fd-lijst').insertAdjacentHTML('beforeend', '<p class="note" style="padding:8px 14px">* factuurdatum onbekend: dit is de dag van ophalen. Tik op de factuur om hem in te vullen.</p>');
  $('#fd-lijst').querySelectorAll('tr.fd-rij').forEach(tr => { tr.onclick = () => fsToon(tr.dataset.sha); tr.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fsToon(tr.dataset.sha); } }; });
  $('#fd-csv').disabled = !items.length;
}
function fdCsv(){ $('#fd-csv-msg').classList.remove('bad'); uiWachtTekst($('#fd-csv-msg'), t('Exporteren…')); $('#fd-csv').disabled = true; Android.facturenDossierCsv(); }
window.onFacturenCsv = function(r){
  $('#fd-csv').disabled = false;
  $('#fd-csv-msg').textContent = r.error ? t(r.error) : t('✓ Opgeslagen in {waar}', { waar: r.waar });
  $('#fd-csv-msg').classList.toggle('bad', !!r.error);
};
function fsToon(sha){
  if (!faDos) fdLaad();
  const s = (faDos.items || []).find(x => x.sha === sha); if (!s) return;
  fsSha = sha;
  const m = s.meta || {}, b = s.bron || {};
  $('#fs-h').textContent = s.leverancierNaam || 'Factuur';
  const rij = (k, v) => v ? '<div class="bkres"><b>' + esc(k) + '</b><span style="overflow-wrap:anywhere">' + esc(v) + '</span></div>' : '';
  $('#fs-info').innerHTML = rij('Bestand', s.naam) + (s.origineel && s.origineel !== s.naam ? rij('Oorspronkelijke naam', s.origineel) : '') + rij('Leverancier', s.leverancierNaam) + rij('Opgehaald', faDatum(s.opgehaald)) +
    rij('Grootte', s.grootte ? fmtSize(s.grootte) : '') +
    (b.soort === 'mail' ? rij('Uit de mail van', b.afzender) + rij('Onderwerp', b.onderwerp) + rij('Mail ontvangen', b.maildatum ? faDatum(b.maildatum) : '') : rij('Website', b.site) + rij('Pagina', b.pagina)) +
    rij('Controlegetal (SHA-256)', (s.sha || '').slice(0, 16) + '…');
  // Alle documenten van deze factuur (factuur en bijlagen), elk met een eigen knop om te openen
  const alle = faDos.items || [], gr = fdGroepen(alle), sleutel = gr.get(sha);
  const docs = alle.filter(x => gr.get(x.sha) === sleutel), hoofd = fdHoofd(docs);
  const geord = [hoofd].concat(docs.filter(x => x !== hoofd));
  fsDocs = geord;
  $('#fs-docs').hidden = geord.length < 2;
  $('#fs-docs-h').textContent = t('Documenten van deze factuur ({n})', { n: geord.length });
  $('#fs-docs-l').innerHTML = geord.map((x, i) => {
    const kan = !!((x.plekken || {}).app && !(x.plekken || {}).app.weg), dit = x.sha === sha, cls = dit ? 'fs-doc fs-dit' : 'fs-doc';
    const wat = i === 0 ? t('Factuur') : t('Bijlage {n}', { n: i }), naam = x.naam || '';
    return '<div class="' + cls + '"><span class="fs-doc-n"><b>' + esc(dit ? t('{wat} · getoond', { wat }) : wat) + '</b><small translate="no">' + esc(naam) + '</small></span>'
      + (kan ? '' : '<small class="note" style="margin:0">' + esc(t('niet meer in de app')) + '</small>')
      + '<button class="mini" style="float:none" data-i="' + i + '" onclick="fsOpenDoc(+this.dataset.i)"' + (kan ? '' : ' disabled') + ' aria-label="' + esc(t('{wat} openen: {naam}', { wat, naam })) + '">' + esc(t('Openen')) + '</button>'
      + (dit ? '' : '<button class="mini" style="float:none" data-sha="' + esc(x.sha) + '" onclick="fsToon(this.dataset.sha)" aria-label="' + esc(t('Gegevens van {naam}', { naam })) + '">' + esc(tc('detail', 'Gegevens')) + '</button>') + '</div>';
  }).join('');
  const rol = geord.length < 2 ? '' : hoofd.sha === sha ? 'factuur' : 'bijlage';
  $('#fs-open').textContent = t(geord.length < 2 ? 'Openen' : rol === 'factuur' ? 'Factuur openen' : 'Deze bijlage openen');
  $('#fs-tekst-d').open = false; $('#fs-tekst').textContent = '';
  const app = (s.plekken || {}).app;
  $('#fs-open').disabled = !(app && !app.weg);
  $('#fs-bron').textContent = m.bron === 'zelf' ? 'Door jou ingevuld.' : m.bron === 'herkend' ? 'Herkend uit de factuur; controleer en pas aan waar nodig.' : 'Niet herkend: vul zelf in.';
  $('#fs-lev').value = s.leverancierNaam || '';
  // Uit de mail: de leverancier kiezen (en eventueel onthouden voor deze afzender) in plaats van de naam typen
  const uitMail = !!s.via;
  $('#fs-levnaam').hidden = uitMail; $('#fs-levrij').hidden = !uitMail;
  if (uitMail) {
    const levs = faSt.leveranciers.filter(x => x.soort !== 'mail' && (!x.overslaan || x.id === s.leverancier))
      .sort((a, b) => (faUit(a) ? 1 : 0) - (faUit(b) ? 1 : 0) || faNaam(a).localeCompare(faNaam(b)));
    if (!levs.some(x => x.id === s.leverancier)) levs.unshift({ id: s.leverancier, naam: s.leverancierNaam || s.leverancier });
    $('#fs-levsel').innerHTML = levs.map(x => '<option value="' + esc(x.id) + '" translate="no">' + esc(faNaam(x)) + '</option>').join('');
    $('#fs-levsel').value = s.leverancier; $('#fs-onthoud').checked = false;
    fsLevKeuze();
  }
  $('#fs-nummer').value = m.nummer || ''; $('#fs-datum').value = m.datum || ''; $('#fs-bedrag').value = m.bedrag || ''; $('#fs-btw').value = m.btw || ''; $('#fs-verval').value = m.vervaldatum || ''; $('#fs-betaald').value = m.betaald || '';
  $('#fs-cat').value = m.categorie || ''; $('#fs-notitie').value = m.notitie || ''; $('#fs-msg').textContent = '';
  $('#fs-cats').innerHTML = [...new Set((faDos.items || []).map(x => (x.meta || {}).categorie).filter(Boolean))].map(c => '<option value="' + esc(c) + '">').join('');
  $('#fs-plekken').innerHTML = Object.entries(s.plekken || {}).map(([k, x]) => {
    const waar = x.pad ? x.pad.replace(/^\/data\/(user\/0|data)\/[^/]+\/files\//, '') : x.aan ? t('mail aan {adres}', { adres: x.aan }) : x.id ? t('bestand {id}…', { id: x.id.slice(0, 10) }) : '';
    return '<div class="bkres"><b>' + esc(FA_PLEK[k] || k) + '</b><span style="overflow-wrap:anywhere">' + (x.weg ? t('weg (leverancier verwijderd)') : esc([waar, faDatum(x.t)].filter(Boolean).join(' · '))) +
      (x.link ? ' <button class="linkish" data-u="' + esc(x.link) + '" onclick="Android.openUrl(this.dataset.u)">Openen</button>' : '') + '</span></div>';
  }).join('') || '<p class="note">Onbekend</p>';
  const ontv = faDos.ontvangers || [];
  $('#fs-aan').innerHTML = ontv.map(o => {
    const x = (s.verzonden || {})[o.id];
    const st = !x ? t('Nog niet') : x.ok ? '✓ ' + faDatum(x.t) + (x.aan ? ' ' + t('aan {adres}', { adres: x.aan }) : '') : x.opnieuw ? t('Gaat opnieuw') : '✗ ' + t(x.fout || 'mislukt') + ' (' + faDatum(x.t) + ')' + ((x.pogingen || 0) >= 5 ? '; ' + t('gestopt na {n} pogingen', { n: x.pogingen }) : '');
    return '<div class="bkres"><b>' + esc(o.naam) + '</b><span' + (x && !x.ok && !x.opnieuw ? ' class="bad"' : x && x.ok ? ' class="good"' : '') + '>' + esc(st) +
      (x && (x.ok || (x.pogingen || 0) >= 5) ? ' <button class="linkish" data-o="' + esc(o.id) + '" onclick="fsOpnieuw(this.dataset.o)">Opnieuw</button>' : '') + '</span></div>';
  }).join('') || '<p class="note">Nog geen ontvanger. <button class="linkish" onclick="faNaar(\'facaanl\')">Ontvanger toevoegen</button></p>';
  if (current !== 'facstuk') faNaar('facstuk');
}
/* Zelf toevoegen, verwijderen en (ontwikkelen) alles leegmaken */
function fdToevoegen(){
  const levs = (faSt.leveranciers || []).filter(l => l.soort !== 'mail' && !l.overslaan); // de mailbron zelf verdeelt alleen
  const kies = (id, naam) => bronKies({ titel: t('Factuur toevoegen'), soort: 'pdf', zonder: ['factuur'], bestand: () => { toast(t('Kies de PDF')); Android.facturenDossierToevoegen(id || '', naam || ''); } });
  openSheet(t('Factuur toevoegen'), t('Van welke leverancier is de factuur?'), levs.map(l => [faNaam(l), () => kies(l.id, '')]).concat([[t('Andere leverancier…'), () =>
    askInput(t('Andere leverancier'), t('De naam van de leverancier'), '', t('PDF kiezen'), v => { if (String(v || '').trim()) kies('', String(v).trim()); else toast(t('Vul een naam in')); })]]));
}
window.onFacturenToegevoegd = function(r){
  if (r.error === '') return; // geen bestand gekozen
  if (r.error) { toast(r.error); return; }
  toast(t('✓ Toegevoegd aan het dossier'));
  fdLaad(); faLaad(); faSamenvatting();
  if (FA_SCHERMEN.includes(current)) { fdRender(); fsToon(r.sha); }
};
function fsWeg(){
  const s = (faDos.items || []).find(x => x.sha === fsSha); if (!s) return;
  const doe = opnieuw => { const e = Android.facturenDossierWeg(fsSha, !!opnieuw); if (e) { toast(e); return; } toast(t('✓ Verwijderd')); fdLaad(); faLaad(); faSamenvatting(); goBack(); fdRender(); };
  const acts = [[t('Verwijderen'), () => doe(false)]];
  // Opnieuw ophalen werkt alleen bij een website (de mail zoekt alleen vanaf de vorige keer)
  if (s.leverancier && s.leverancier !== 'handmatig' && (s.bron || {}).soort === 'website') acts.push([t('Verwijderen en de volgende keer opnieuw ophalen'), () => doe(true)]);
  openSheet(t('{naam} verwijderen?', { naam: s.naam || '' }), t('De factuur gaat uit het dossier en uit de app. Kopieën in je backup-map, op Drive en in je mail blijven staan.'), acts);
}
function fdReset(){
  const n = ((faDos || {}).items || []).length;
  askConfirm(t('Dossier leegmaken?'), tn(n, 'Eén factuur gaat uit het dossier en uit de app, en elke leverancier begint de volgende keer opnieuw. Dit kan niet ongedaan worden.', '{n} facturen gaan uit het dossier en uit de app, en elke leverancier begint de volgende keer opnieuw. Dit kan niet ongedaan worden.'), t('Leegmaken'), () =>
    askConfirm(t('Echt alles leegmaken?'), t('Weet je het zeker? Kopieën in je backup-map, op Drive en in je mail blijven staan.'), t('Ja, leegmaken'), () => {
      const r = rdJson(Android.facturenDossierReset(), { error: t('Leegmaken lukt niet') });
      if (r.error) { toast(r.error); return; }
      toast(t('✓ Dossier leeg'));
      fdLaad(); faLaad(); faSamenvatting(); fdRender();
    }));
}
function fdAllesWeg(){
  const n = ((faDos || {}).items || []).length;
  askConfirm(t('Alles weghalen?'), tn(n, 'Eén factuur gaat uit de app, en ook uit je backup-map, de map op je telefoon en Google Drive. Dit kan niet ongedaan worden.', '{n} facturen gaan uit de app, en ook uit je backup-map, de map op je telefoon en Google Drive. Dit kan niet ongedaan worden.'), t('Weghalen'), () =>
    askConfirm(t('Echt alles weghalen?'), t('Weet je het zeker? De PDF\'s zijn daarna nergens meer, behalve in mail die al verstuurd is.'), t('Ja, alles weghalen'), () => {
      const k = $('#fd-alles'); k.disabled = true; uiWachtTekst($('#fd-alles-msg'), t('Bezig met weghalen…'));
      Android.facturenDossierAllesWeg();
    }));
}
window.onFacturenAllesWeg = function(r){
  const k = $('#fd-alles'), m = $('#fd-alles-msg');
  if (k) k.disabled = false;
  if (r.error) { if (m) m.textContent = r.error; toast(r.error); return; }
  let tekst;
  if (!r.leeg) tekst = tn(r.mislukt, 'Eén kopie kon niet weg (geen verbinding, Google niet gekoppeld of geen toegang meer tot de map). Het dossier is nog niet leeggemaakt: probeer het opnieuw, of kies Dossier leegmaken.', '{n} kopieën konden niet weg (geen verbinding, Google niet gekoppeld of geen toegang meer tot de map). Het dossier is nog niet leeggemaakt: probeer het opnieuw, of kies Dossier leegmaken.');
  else if (r.nietGevonden) tekst = tn(r.nietGevonden, '✓ Alles weggehaald. Eén kopie was er al niet meer (of de app kan er niet meer bij).', '✓ Alles weggehaald. {n} kopieën waren er al niet meer (of de app kan er niet meer bij).');
  else tekst = t('✓ Alles weggehaald');
  if (m) m.textContent = tekst;
  toast(r.leeg ? t('✓ Alles weggehaald') : t('Niet alles kon weg'));
  fdLaad(); faLaad(); faSamenvatting(); fdRender();
};
function fsTekst(){
  const r = rdJson(Android.facturenDossierTekst(fsSha), { error: 'Lezen lukt niet' });
  $('#fs-tekst').textContent = r.error ? r.error : (r.tekst || '').trim() || '(geen tekst gevonden: misschien is de PDF een afbeelding)';
}
function fsOpen(){ const s = (faDos.items || []).find(x => x.sha === fsSha); if (s) Android.facturenOpen(s.leverancier, s.naam); }
let fsDocs = [];
function fsOpenDoc(i){ const s = fsDocs[i]; if (s) Android.facturenOpen(s.leverancier, s.naam); }
/** Wat "Onthoud dit" vastlegt (zoals FacturenIndeling.regel): de naam bij een platform, het eigen adres, of alleen de nummers; "" = niets. */
function fsOnthoudTekst(km){
  const nrs = ['btw', 'kvk', 'iban'].some(s => (km[s] || []).length), alleenNrs = nrs ? t('Onthoud de nummers op deze factuur (KvK, btw, IBAN)') : '';
  if (km.soort === 'platform') return km.naam ? t('Onthoud dit voor {naam}', { naam: km.naam }) : alleenNrs;
  if (km.soort === 'eigen') return alleenNrs;
  if (km.platform) return km.soort === 'domein' && km.domein ? t('Onthoud dit voor {adres}', { adres: km.domein }) : alleenNrs;
  return km.adres ? t('Onthoud dit voor {adres}', { adres: km.adres }) : alleenNrs;
}
function fsLevKeuze(){
  const s = ((faDos || {}).items || []).find(x => x.sha === fsSha); if (!s) return;
  const ander = $('#fs-levsel').value !== s.leverancier, wat = fsOnthoudTekst(s.kenmerken || {});
  $('#fs-onthoudrij').hidden = !ander || !wat || $('#fs-levsel').value === FA_NIET_HERKEND;
  $('#fs-onthoud-t').textContent = wat;
  $('#fs-lev-uitleg').textContent = ander ? t('Bij Bewaren gaat deze factuur (met de bijlagen uit dezelfde mail) naar de leverancier die je kiest.') : t('Uit je mail. Hoort hij bij een andere leverancier? Kies die hier.');
}
function fsBewaar(){
  const s = ((faDos || {}).items || []).find(x => x.sha === fsSha), uitMail = !!(s && s.via);
  let verplaatst = '';
  // Eerst de gegevens (die worden gecontroleerd), dan pas naar een andere leverancier: bij een fout blijft alles staan
  const e = Android.facturenDossierMeta(fsSha, JSON.stringify({ leverancierNaam: uitMail ? '' : $('#fs-lev').value.trim(), nummer: $('#fs-nummer').value.trim(), datum: $('#fs-datum').value, bedrag: $('#fs-bedrag').value.trim(), btw: $('#fs-btw').value.trim(), vervaldatum: $('#fs-verval').value, betaald: $('#fs-betaald').value, categorie: $('#fs-cat').value.trim(), notitie: $('#fs-notitie').value.trim() }));
  $('#fs-msg').textContent = e || '';
  if (e) return;
  if (uitMail) {
    const naar = $('#fs-levsel').value;
    if (naar && naar !== s.leverancier) {
      const r = rdJson(Android.facturenDossierLeverancier(fsSha, naar, $('#fs-onthoud').checked && !$('#fs-onthoudrij').hidden), { error: t('Verplaatsen lukt niet') });
      if (r.error) { $('#fs-msg').textContent = r.error; fdLaad(); return; }
      const l = faSt.leveranciers.find(x => x.id === naar); verplaatst = l ? faNaam(l) : naar;
      faLaad();
    }
  }
  fdLaad(); toast(verplaatst ? t('✓ Verplaatst naar {naam}', { naam: verplaatst }) : t('✓ Bewaard')); fsToon(fsSha);
}
function fsOpnieuw(ontv){
  const o = (faDos.ontvangers || []).find(x => x.id === ontv);
  askConfirm(t('Opnieuw aanleveren?'), t('Deze factuur gaat nog een keer naar {naam}: de volgende keer vanzelf, of nu met Aanleveren → Nu versturen.', { naam: o ? o.naam : ontv }), t('Opnieuw'), () => {
    const e = Android.facturenDossierOpnieuw(fsSha, ontv); if (e) { toast(e); return; } fdLaad(); fsToon(fsSha); toast('✓ Gaat opnieuw');
  });
}

/* ---------- Aanleveren aan de accountant of boekhouder ---------- */
let foSt = { lijst: [], kanalen: [], via: [], log: [] }, foEdit = null;
function foLaad(){ foSt = rdJson(Android.facturenOntvangers(), { lijst: [], kanalen: [], via: [], log: [] }); return foSt; }
function foPlanTekst(o){ return o.schema === 'direct' ? t('direct na het ophalen') : faPlanTekst(o); }
function foRender(laad){
  if (laad !== false) foLaad();
  $('#fo-bezig').style.display = foSt.bezig ? 'block' : 'none';
  $('#fo-bezig-txt').textContent = t(foSt.voortgang || 'Versturen…');
  $('#fo-lijst').innerHTML = foSt.lijst.map(o => {
    const tt = o.toestand || {}, via = (foSt.via.find(v => v.id === o.via) || {}).naam || o.via;
    const st = o.mist ? '✗ ' + t(o.mist) : tt.lastTry && !tt.ok ? '✗ ' + faMoment(tt.lastTry) + ': ' + t(tt.msg || 'mislukt') : tt.lastOk ? t('✓ Laatst {wanneer}: {wat}', { wanneer: faMoment(tt.lastOk), wat: t(tt.msg || '') }) : t('Nog niets verstuurd');
    const wanneer = !o.actief ? t('Uit') : (o.open ? tn(o.open, '{n} factuur klaar', '{n} facturen klaar') : t('Niets open')) + ' · ' + foPlanTekst(o) + (o.volgende ? ' ' + t('(volgende keer {wanneer})', { wanneer: faMoment(o.volgende) }) : '');
    return '<div class="srow"><button class="sbtn" data-id="' + esc(o.id) + '" onclick="foOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon('mail') + '</span>' +
      '<span class="stxt"><b>' + esc(o.naam) + '</b><small>' + esc((o.aan || '') + ' · via ' + via) + '</small><small' + (st.startsWith('✗') ? ' class="bad"' : '') + '>' + esc(st) + '</small><small>' + esc(wanneer) + '</small></span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">Nog geen ontvanger. Tik op <b>Ontvanger toevoegen</b> en vul het mailadres van je accountant of boekhouder in.</p>';
}
function foOpen(id){
  const o = foSt.lijst.find(x => x.id === id); if (!o) return;
  openSheet(o.naam, (o.open ? tn(o.open, '{n} factuur staat klaar', '{n} facturen staan klaar') : t('Alles is al aangeleverd')) + ' · ' + foPlanTekst(o), [
    [t('Nu versturen') + (o.open ? ' (' + o.open + ')' : ''), () => foNu(id, false)],
    ['Proef naar mezelf', () => foNu(id, true)],
    ['Instellingen', () => foForm(o)],
    ['Verwijderen…', () => askConfirm(t('{naam} verwijderen?', { naam: o.naam }), 'Wat al verstuurd is, blijft in het dossier staan.', 'Verwijderen', () => { const e = Android.facturenOntvangerWeg(id); if (e) toast(e); foRender(); faLaad(); faSamenvatting(); })]]);
}
function foNu(id, proef){
  const o = foSt.lijst.find(x => x.id === id);
  if (!proef && o && !o.open) { toast('Er staat niets klaar: alles is al aangeleverd'); return; }
  const e = Android.facturenAanleveren(id, !!proef);
  if (e) { toast(e); return; }
  foSt.bezig = true; foSt.voortgang = proef ? 'Proef versturen…' : 'Versturen…'; foRender(false);
  toast(proef ? 'Proef wordt verstuurd' : 'Bezig met versturen');
}
window.onFacturenAanlever = function(r){
  toast((r.ok ? '✓ ' : '✗ ') + (r.msg || ''));
  if (!r.ok && r.koppelNodig) setTimeout(() => askConfirm('Koppeling nodig', r.msg || '', 'Naar Koppelingen', () => faNaar('fackopp')), 600);
  if (current === 'facaanl') foRender(); faLaad(); faSamenvatting();
  if (current === 'facdos') fdRender(true);
};
function foLog(){
  foLaad();
  const log = foSt.log || [];
  if (!log.length) { toast('Nog niets verstuurd'); return; }
  askConfirm('Logboek aanleveren', log.slice(0, 60).map(r => (r.ok ? '✓ ' : '✗ ') + faMoment(r.t) + ' · ' + (r.naam || '') + ' ' + t(r.proef ? '(proef)' : r.auto ? '(vanzelf)' : '(met de hand)') + '\n   ' + t(r.msg || '') + ((r.namen || []).length ? '\n   ' + r.namen.join(', ') : '')).join('\n'), 'Sluiten', () => {});
}
function foNieuw(){ foForm({ naam: '', kanaal: 'email', via: (foSt.via.find(v => !v.mist) || foSt.via[0] || {}).id || 'eigencloud', actief: true, schema: 'maand', dag: 1, weekdag: 1, tijd: '09:00', opnieuw: 3, bundel: 'samen', maxMb: 15, meldFout: true, leveranciers: [] }, true); }
function foForm(o, nieuw){
  if (!foSt.kanalen.length) foLaad();
  foEdit = JSON.parse(JSON.stringify(o)); foEdit._nieuw = !!nieuw;
  $('#fo-h').textContent = nieuw ? 'Ontvanger toevoegen' : o.naam;
  $('#fo-naam').value = o.naam || '';
  $('#fo-kanaal').innerHTML = foSt.kanalen.map(k => '<option value="' + esc(k.id) + '">' + esc(k.naam) + '</option>').join('');
  $('#fo-kanaal').value = o.kanaal || 'email';
  $('#fo-actief').checked = o.actief !== false;
  $('#fo-aan').value = o.aan || ''; $('#fo-cc').value = o.cc || '';
  $('#fo-via').innerHTML = foSt.via.map(v => '<option value="' + esc(v.id) + '">' + esc(v.naam) + (v.mist ? ' (nog instellen)' : '') + '</option>').join('');
  $('#fo-via').value = o.via || 'eigencloud';
  $('#fo-mij').checked = !!o.kopieMij; $('#fo-bundel').value = o.bundel || 'samen'; $('#fo-mb').value = String([5, 10, 15, 20].includes(o.maxMb) ? o.maxMb : 15);
  $('#fo-ond').value = o.onderwerp || 'Facturen {periode}';
  $('#fo-tekst').value = o.tekst || 'Hallo,\n\nHierbij {aantal} ({leveranciers}):\n\n{lijst}\n\nMet vriendelijke groet,\nRene van Es';
  foLevs(o.leveranciers || []);
  $('#fo-oud').checked = false; $('#fo-oudrij').querySelector('input').nextSibling.textContent = nieuw ? ' Ook de facturen die al in het dossier staan' : ' Ook de facturen van vóór deze ontvanger (nog een keer kijken)';
  $('#fo-schema').value = o.schema || 'maand';
  if (!$('#fo-dag').options.length) for (let i = 1; i <= 31; i++) $('#fo-dag').add(new Option(i === 31 ? 'laatste dag' : i + 'e', String(i)));
  $('#fo-dag').value = String(o.dag || 1); $('#fo-wd').value = String(o.weekdag || 1); $('#fo-tijd').value = o.tijd || '09:00';
  $('#fo-opnieuw').value = String([1, 3, 6, 12, 24].includes(o.opnieuw) ? o.opnieuw : 3);
  $('#fo-meldfout').checked = o.meldFout !== false; $('#fo-meldok').checked = !!o.meldOk;
  $('#fo-msg').textContent = '';
  foKanaal(); foVia(); foPlan();
  faNaar('facontv');
}
/**
 * Welke leveranciers: websites, per mailbron "Alles uit …" (ook leveranciers die er later bij komen) en,
 * ingeklapt, de leveranciers uit die mail los.
 */
function foLevs(gekozen){
  const alle = faSt.leveranciers, uit = alle.filter(faUit), bronnen = alle.filter(l => l.soort === 'mail');
  const vink = (id, tekst) => '<label class="chk"><input type="checkbox" class="fo-lev" value="' + esc(id) + '"' + (gekozen.includes(id) ? ' checked' : '') + '> <span translate="no">' + esc(tekst) + '</span></label>';
  let html = alle.filter(l => !faUit(l) && l.soort !== 'mail').map(l => vink(l.id, l.naam)).join('');
  for (const b of bronnen) {
    const van = uit.filter(x => (x.bronnen || []).includes(b.id) && (!x.overslaan || gekozen.includes(x.id)));
    html += vink(b.id, t('Alles uit {naam}', { naam: b.naam })) + '<p class="note" style="margin:0 0 4px">' + esc(t('Ook leveranciers die er later bij komen.')) + '</p>';
    if (van.length) html += '<details class="rinfo"' + (van.some(x => gekozen.includes(x.id)) ? ' open' : '') + '><summary>' + esc(tn(van.length, 'Of alleen één leverancier uit deze mail', 'Of alleen bepaalde leveranciers uit deze mail ({n})')) + '</summary>' + van.map(x => vink(x.id, faNaam(x))).join('') + '</details>';
  }
  html += uit.filter(x => !bronnen.some(b => (x.bronnen || []).includes(b.id)) && (!x.overslaan || gekozen.includes(x.id))).map(x => vink(x.id, faNaam(x))).join('');
  $('#fo-levs').innerHTML = html || '<p class="note">Nog geen leveranciers.</p>';
}
function foKanaal(){ const k = $('#fo-kanaal').value; document.querySelectorAll('.fo-k').forEach(c => c.style.display = c.dataset.kanaal === k ? 'block' : 'none'); }
function foVia(){
  const v = foSt.via.find(x => x.id === $('#fo-via').value) || {};
  const doel = v.id === 'smtp' ? 'facsmtp' : v.id === 'gmail' ? 'facgoogle' : 'facec';
  $('#fo-via-msg').innerHTML = v.mist ? esc(v.mist) + ' <button class="linkish" onclick="faNaar(\'' + doel + '\')">Nu instellen</button>' : v.van ? esc(t('Wordt verstuurd vanaf {adres}.', { adres: v.van })) : '';
  $('#fo-via-msg').classList.toggle('bad', !!v.mist);
}
function foPlan(){
  const s = $('#fo-schema').value;
  $('#fo-dagrij').style.display = s === 'maand' ? 'flex' : 'none';
  $('#fo-wdrij').style.display = s === 'week' ? 'flex' : 'none';
  $('#fo-tijdrij').style.display = s === 'uit' || s === 'direct' ? 'none' : 'flex';
  $('#fo-opnrij').style.display = s === 'uit' ? 'none' : 'flex';
  const o = { schema: s, dag: Number($('#fo-dag').value) || 1, weekdag: Number($('#fo-wd').value) || 1, tijd: $('#fo-tijd').value || '09:00' };
  $('#fo-plan').textContent = s === 'uit' ? t('Alleen als je zelf op Nu versturen tikt.') : s === 'direct' ? t('Zodra er nieuwe facturen zijn opgehaald, gaan ze binnen een paar minuten weg.')
    : t('Vanzelf {wanneer}, met alles wat sinds de vorige keer is binnengekomen. Eerstvolgende keer: {volgende}.', { wanneer: faPlanTekst(o), volgende: faMoment(faVolgende(o, Date.now())) });
}
function foBewaar(){
  const o = {
    id: foEdit && !foEdit._nieuw ? foEdit.id : '', naam: $('#fo-naam').value.trim(), kanaal: $('#fo-kanaal').value, actief: $('#fo-actief').checked,
    aan: $('#fo-aan').value.trim(), cc: $('#fo-cc').value.trim(), via: $('#fo-via').value, kopieMij: $('#fo-mij').checked, bundel: $('#fo-bundel').value, maxMb: Number($('#fo-mb').value) || 15,
    onderwerp: $('#fo-ond').value.trim(), tekst: $('#fo-tekst').value,
    leveranciers: [...document.querySelectorAll('.fo-lev:checked')].map(i => i.value), ookOud: $('#fo-oud').checked,
    schema: $('#fo-schema').value, dag: Number($('#fo-dag').value) || 1, weekdag: Number($('#fo-wd').value) || 1, tijd: $('#fo-tijd').value || '09:00',
    opnieuw: Number($('#fo-opnieuw').value) || 3, meldFout: $('#fo-meldfout').checked, meldOk: $('#fo-meldok').checked };
  const r = rdJson(Android.facturenOntvangerBewaar(JSON.stringify(o)), { error: 'Bewaren lukt niet' });
  if (r.error) { $('#fo-msg').textContent = r.error; return; }
  const nieuw = foEdit && foEdit._nieuw;
  goBack(); foRender(); faLaad(); faSamenvatting();
  const v = foSt.via.find(x => x.id === o.via) || {};
  if (v.mist) setTimeout(() => askConfirm('Nog instellen', v.mist, 'Nu instellen', () => faNaar(o.via === 'smtp' ? 'facsmtp' : o.via === 'gmail' ? 'facgoogle' : 'facec')), 300);
  else if (nieuw) setTimeout(() => askConfirm('Proef versturen?', 'Stuur eerst een proef naar jezelf, dan zie je hoe de mail eruitziet.', 'Proef naar mezelf', () => foNu(r.id, true)), 300);
  else toast('✓ Bewaard');
}

/* ---------- Eigen mailserver (SMTP) ---------- */
function fmBev(){ const p = $('#fm-poort'); if (p.value === '587' || p.value === '465' || !p.value) p.value = $('#fm-bev').value === 'ssl' ? '465' : '587'; }
function fmBewaar(){
  const e = Android.facturenSmtp(JSON.stringify({ host: $('#fm-host').value.trim(), poort: Number($('#fm-poort').value) || 587, beveiliging: $('#fm-bev').value,
    gebruiker: $('#fm-user').value.trim(), wachtwoord: $('#fm-pw').value, van: $('#fm-van').value.trim(), naam: $('#fm-naam').value.trim() }));
  $('#fm-pw').value = '';
  $('#fm-msg').textContent = e || '';
  if (!e) { if (document.activeElement) document.activeElement.blur(); faRender(); toast('✓ Bewaard'); }
}
function fmTest(){ $('#fm-msg').classList.remove('bad'); uiWachtTekst($('#fm-msg'), t('Verbinding testen…')); $('#fm-test').disabled = true; Android.facturenSmtpTest(); }
window.onFacturenSmtpTest = function(r){
  $('#fm-test').disabled = false;
  $('#fm-msg').textContent = r.error ? r.error : ''; $('#fm-msg').classList.add('bad');
  if (!r.error) toast('✓ Verbinding met de mailserver werkt');
};
