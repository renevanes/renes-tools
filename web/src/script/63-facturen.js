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
  const was = faSt.bezig;
  faLaad();
  faBezig();
  if (was !== faSt.bezig) faRender(true);
}
function faBezig(){
  const l = faSt.leveranciers.find(x => x.id === faSt.bezig);
  $('#fa-bezig').style.display = faSt.bezig ? 'block' : 'none';
  $('#fa-bezig-txt').textContent = faSt.bezig ? (l ? l.naam + ': ' : '') + t(faSt.voortgang || 'Bezig…') : '';
}
const FA_WD = ['', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
function faMoment(tt){ return tt ? fmtDT(tt, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function faMaandSleutel(tt){ const d = new Date(tt); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); }
function faMaandNaam(k){ const [y, m] = k.split('-').map(Number); return fmtDate(new Date(y, m - 1, 15, 12), 'maand'); }
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
function faRender(geenLaad){
  if (!geenLaad) faLaad();
  faBezig();
  $('#fa-lijst').innerHTML = faSt.leveranciers.map(l => {
    const p = faPeriode(l), fout = p.soort === 'mis' || p.soort === 'pw';
    const volgende = l.schema !== 'uit' && l.volgende ? t('Volgende keer: {wanneer}', { wanneer: faMoment(l.volgende) }) : t('Vanzelf: {wanneer}', { wanneer: faPlanTekst(l) });
    return '<div class="srow"><button class="sbtn" data-id="' + esc(l.id) + '" onclick="faOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon(l.soort === 'mail' ? 'mail' : 'document') + '</span>' +
      '<span class="stxt"><b translate="no">' + esc(l.naam) + '</b><small' + (fout ? ' class="bad"' : '') + '>' + esc(p.tekst) + '</small><small>' + esc(volgende + ' · ' + faPlanTekst(l)) + '</small></span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">Nog geen leveranciers. Tik op <b>Leverancier toevoegen</b>.</p>';
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
  const l = faSt.leveranciers, nu = Date.now(), dezeMaand = faMaandSleutel(nu);
  $('#fa-maand').style.display = l.length ? 'block' : 'none';
  if (!l.length) { $('#fa-overzicht-lijst').innerHTML = '<p class="note">Nog geen leveranciers.</p>'; return; }
  $('#fa-maand-h').textContent = t('Deze maand') + ' · ' + faMaandNaam(dezeMaand);
  $('#fa-maand-lijst').innerHTML = l.map(x => {
    const n = (x.maanden || {})[dezeMaand] || 0, p = faPeriode(x);
    const binnen = n ? '✓ ' + tn(n, '{n} factuur binnen', '{n} facturen binnen') : p.soort === 'mis' ? t('✗ Nog niet: mislukt') : x.schema !== 'uit' && x.volgende && faMaandSleutel(x.volgende) === dezeMaand && !(x.vorige && faMaandSleutel(x.vorige) === dezeMaand) ? t('Gepland {wanneer}', { wanneer: faMoment(x.volgende) }) : t(x.schema === 'uit' ? 'Alleen met de hand' : 'Nog niets binnen');
    return '<div class="bkres"><b>' + esc(x.naam) + '</b><span class="' + (n ? 'good' : p.soort === 'mis' ? 'bad' : '') + '">' + esc(binnen) + '</span></div>';
  }).join('');
  // Laatste 12 maanden; ✓ = facturen binnen, ✗ = alleen mislukte pogingen, – = niets
  const maanden = []; const d = new Date(nu); d.setDate(1);
  for (let i = 0; i < 12; i++) { maanden.push(faMaandSleutel(d.getTime())); d.setMonth(d.getMonth() - 1); }
  const cel = (x, m) => {
    const n = (x.maanden || {})[m] || 0;
    if (n) return '<td class="good">✓ ' + n + '</td>';
    const mis = ((x.toestand || {}).log || []).some(r => !r.ok && faMaandSleutel(r.t) === m);
    return mis ? '<td class="bad">✗</td>' : '<td>–</td>';
  };
  $('#fa-overzicht-lijst').innerHTML = '<table class="fa-tab"><thead><tr><th scope="col">Maand</th>' + l.map(x => '<th scope="col">' + esc(x.naam) + '</th>').join('') + '</tr></thead><tbody>' +
    maanden.map(m => '<tr><th scope="row">' + esc(faMaandNaam(m)) + '</th>' + l.map(x => cel(x, m)).join('') + '</tr>').join('') + '</tbody></table>' +
    '<p class="note">✓ = aantal facturen dat binnenkwam, ✗ = ophalen mislukt, – = niets.</p>';
}
function faGeschiedenis(l){
  const log = (l.toestand || {}).log || [];
  if (!log.length) { toast('Nog geen geschiedenis'); return; }
  askConfirm(t('Geschiedenis') + ' · ' + l.naam, log.map(r => (r.ok ? '✓ ' : '✗ ') + faMoment(r.t) + ' ' + t(r.auto ? '(vanzelf)' : '(met de hand)') + '\n   ' + t(r.msg || '') + ((r.namen || []).length ? '\n   ' + r.namen.join(', ') : '')).join('\n'), 'Sluiten', () => {});
}
function faOpen(id){
  const l = faSt.leveranciers.find(x => x.id === id); if (!l) return;
  const busy = !!faSt.bezig;
  const acts = [
    ['Nu ophalen', () => faNu(id, false)],
    ...(l.soort === 'mail' ? [] : [['Ophalen en meekijken', () => faNu(id, true)]]),
    [t('Facturen bekijken') + (l.aantal ? ' (' + l.aantal + ')' : ''), () => faBekijk(l)],
    ['Geschiedenis', () => faGeschiedenis(l)],
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
  if (!r.ok && r.inlogNodig) setTimeout(() => askConfirm('Inloggen nodig', t(r.msg || '') + '\n\n' + t('Kijk één keer mee: dan log je zelf in en gaat de app daarna verder.'), 'Meekijken', () => faNu(r.id, true)), 600);
  if (FA_SCHERMEN.includes(current)) faRender();
};
function faBekijk(l){
  const lijst = rdJson(Android.facturenLijst(l.id), []);
  if (!lijst.length) { toast('Nog geen facturen in de app'); return; }
  openSheet(l.naam, tn(lijst.length, '{n} factuur', '{n} facturen'), lijst.slice(0, 40).map(f => [f.naam + ' · ' + faDatum(f.t), () => Android.facturenOpen(l.id, f.naam)]));
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
  $('#fl-m-max').value = m.max || 50; $('#fl-m-per').checked = m.perAfzender !== false;
  $('#fl-msg').textContent = '';
  flBron(); flPlan(); flZoek(); flOpslag(); flPeriode();
  flTab(faEdit.soort === 'mail' ? 'zoek' : 'inlog');
  faNaar('faclev');
  $('#fl-m-woorden').oninput = $('#fl-m-afz').oninput = $('#fl-m-neg').oninput = $('#fl-m-extra').oninput = flZoek;
  ['fl-lok', 'fl-map', 'fl-gd', 'fl-ec'].forEach(i => $('#' + i).onchange = flOpslag);
}
/** Tabbladen: website = Inloggen, Planning, Opslaan, Meer; mail = Zoeken, Planning, Opslaan. */
function flTab(tt){
  const mail = faEdit && faEdit.soort === 'mail', mag = mail ? ['zoek', 'plan', 'opslag'] : ['inlog', 'plan', 'opslag', 'adv'];
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
function flOpslag(){
  const mail = faEdit && faEdit.soort === 'mail', per = mail && $('#fl-m-per').checked;
  document.querySelector('label[for="fl-sub"]').textContent = per ? 'Mapnaam (de afzendermappen komen hierin)' : 'Mapnaam';
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
  const [h, mi] = (o.tijd || '08:00').split(':').map(Number), c = new Date(nu); c.setSeconds(0, 0); c.setHours(h, mi);
  const zet = () => { const max = new Date(c.getFullYear(), c.getMonth() + 1, 0).getDate(); c.setDate(Math.min(o.dag, max)); };
  if (o.schema === 'maand') { c.setDate(1); zet(); while (c.getTime() <= nu) { c.setDate(1); c.setMonth(c.getMonth() + 1); zet(); } }
  else if (o.schema === 'week') { const wd = (c.getDay() + 6) % 7 + 1; c.setDate(c.getDate() + (o.weekdag - wd)); while (c.getTime() <= nu) c.setDate(c.getDate() + 7); }
  else while (c.getTime() <= nu) c.setDate(c.getDate() + 1);
  return c.getTime();
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
  const vanaf = p.soort === 'jaar' ? fmtDate(new Date(new Date().getFullYear(), 0, 1)) : p.soort === 'maanden' ? fmtDate(new Date(new Date().getFullYear(), new Date().getMonth() - p.maanden, new Date().getDate())) : p.soort === 'vanaf' && p.vanaf ? fmtDate(new Date(p.vanaf + 'T12:00')) : '';
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
  const o = {
    id: faEdit && !faEdit._nieuw ? faEdit.id : '', sjabloon: faEdit ? faEdit.sjabloon || 'anders' : 'anders', soort: faEdit && faEdit.soort === 'mail' ? 'mail' : 'website',
    mail: { bron: $('#fl-m-bron').value, woorden: $('#fl-m-woorden').value, afzenders: $('#fl-m-afz').value, negeer: $('#fl-m-neg').value, extra: $('#fl-m-extra').value.trim(),
      max: Number($('#fl-m-max').value) || 50, perAfzender: $('#fl-m-per').checked },
    naam: $('#fl-naam').value.trim(), startUrl: $('#fl-start').value.trim(), gebruiker: $('#fl-user').value.trim(), wachtwoord: $('#fl-pw').value,
    periode: flPeriodeWaarde(), meer: $('#fl-meer').value.trim(),
    facturenUrl: $('#fl-facturl').value.trim(), domeinen: $('#fl-domeinen').value.trim(), menu: $('#fl-menu').value.trim(), knoppen: $('#fl-knoppen').value.trim(), max: Number($('#fl-max').value) || 50,
    code: { bron: $('#fl-bron').value, afzender: $('#fl-afz').value.trim(), patroon: $('#fl-pat').value.trim() },
    schema: $('#fl-schema').value, dag: Number($('#fl-dag').value) || 5, weekdag: Number($('#fl-wd').value) || 1, tijd: $('#fl-tijd').value || '08:00',
    opnieuw: Number($('#fl-opnieuw').value) || 3, wifi: $('#fl-wifi').checked, meldNieuw: $('#fl-meldnieuw').checked, meldFout: $('#fl-meldfout').checked, map: $('#fl-map').checked, lokaal: $('#fl-lok').checked, drive: $('#fl-gd').checked, eigenCloud: $('#fl-ec').checked, submap: $('#fl-sub').value.trim(),
    velden: { gebruiker: $('#fl-v-gebruiker').value.trim(), wachtwoord: $('#fl-v-wachtwoord').value.trim(), code: $('#fl-v-code').value.trim(), knop: $('#fl-v-knop').value.trim() }
  };
  for (const [p, wat] of [[o.menu, 'De tekst van de link naar de facturen'], [o.knoppen, 'Het herkennen van downloadknoppen']]) {
    if (o.soort === 'mail') break;
    try { new RegExp(p, 'i'); } catch (e) { $('#fl-msg').textContent = t('{wat} is geen geldig zoekpatroon.', { wat: t(wat) }); flTab('adv'); return; }
  }
  const fout = (tt, tab) => { $('#fl-msg').textContent = tt; flTab(tab); };
  if (o.lokaal && !faSt.lokaal) return fout('Kies eerst een map op je telefoon (Facturen → Opslaan), of zet "In een map op je telefoon" uit.', 'opslag');
  if (o.drive && !(faSt.drive || {}).rechtDrive) return fout('Koppel eerst Google met Drive (Facturen → Koppelingen), of zet "In Google Drive" uit.', 'opslag');
  const ec = faSt.ec || {};
  if (o.eigenCloud && !(ec.url && ec.heeftWachtwoord)) return fout('Koppel eerst Eigen Cloud (Facturen → Koppelingen), of zet "Naar je mail in Eigen Cloud" uit.', 'opslag');
  if (o.soort !== 'mail' && o.code.bron === 'eigencloud' && !(ec.url && ec.heeftWachtwoord)) return fout('Koppel eerst Eigen Cloud om de code uit je mail te lezen.', 'inlog');
  if (o.soort === 'mail' && !faDelen(o.mail.woorden).length && !faDelen(o.mail.afzenders).length && !o.mail.extra) return fout('Vul zoekwoorden of afzenders in, anders zoekt de app naar elke PDF in je mail.', 'zoek');
  const r = rdJson(Android.facturenBewaar(JSON.stringify(o)), { error: 'Bewaren lukt niet' });
  $('#fl-pw').value = '';
  if (r.error) {
    $('#fl-msg').textContent = r.error;
    const e = r.error;
    if (/facturenpagina|veld|patroon|menu|knoppen/i.test(e)) flTab('adv');
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
function faEcTest(){ $('#fa-ec-msg').textContent = ''; $('#fa-ec-test').disabled = true; Android.facturenEcTest(); }
window.onFacturenEcTest = function(r){
  $('#fa-ec-test').disabled = false;
  $('#fa-ec-msg').textContent = r.error ? r.error : '';
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
const fdVandaag = () => new Date().toLocaleDateString('sv-SE');
/** De datum van een factuur: de factuurdatum, of (onbekend) de dag waarop hij is opgehaald. JJJJ-MM-DD. */
const fdDatum = s => (s.meta || {}).datum || (s.opgehaald ? new Date(s.opgehaald).toLocaleDateString('sv-SE') : '');
/** Van en tot (JJJJ-MM-DD, tot en met) bij een periode; leeg = geen grens. */
function fdVanTot(p, van, tot, nu){
  const d = nu ? new Date(nu) : new Date(), y = d.getFullYear(), m = d.getMonth(), q = Math.floor(m / 3);
  const dag = (yy, mm, dd) => new Date(yy, mm, dd).toLocaleDateString('sv-SE');
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
  if (faDos.bezigMetLezen) { e.style.display = 'block'; e.textContent = 'Gegevens uit de facturen lezen…'; return; }
  e.style.display = 'none'; e.textContent = '';
  const mist = faDos.teLezen || 0; // de app telt zelf wat er nog gelezen moet worden
  if (!faDos.pdfTekst) {
    if ((faDos.items || []).some(s => !(s.meta || {}).bedrag)) { $('#fd-lees').style.display = 'block'; $('#fd-lees').textContent = 'Op deze telefoon (Android 14 of ouder) kan de app de factuurgegevens niet uit de PDF lezen. Tik op een factuur om datum en bedrag zelf in te vullen.'; }
    return;
  }
  if (!mist) return;
  $('#fd-lees').style.display = 'block'; $('#fd-lees').textContent = tn(mist, 'Gegevens uit {n} factuur lezen…', 'Gegevens uit {n} facturen lezen…');
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
  { id: 'lev', naam: 'Leverancier', w: s => (s.leverancierNaam || '').toLowerCase(), t: s => (s.leverancierNaam || '') + (s._docs && s._docs.length > 1 ? ' (' + s._docs.length + ' documenten)' : '') },
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
 * Dezelfde factuur in meer documenten (bijv. de factuur en de specificatie, of twee keer gedownload): één regel per
 * leverancier + factuurnummer, met de gegevens van het document waarin het bedrag staat, aangevuld uit de andere.
 */
function fdSamen(items){
  const m = new Map(), uit = [];
  for (const s of items) {
    // Zelfde leverancier (bij mail: zelfde afzender) en factuurnummer, en datum en bedrag spreken elkaar niet tegen
    const me = s.meta || {}, n = me.nummer, k = n ? s.leverancier + '|' + ((s.bron || {}).afzender || '') + '|' + n : '';
    const p = k && m.get(k), pm = p ? p._docs[0].meta || {} : {};
    if (p && (!me.datum || !pm.datum || me.datum === pm.datum) && (!me.bedrag || !p._docs.some(d => (d.meta || {}).bedrag) || p._docs.some(d => (d.meta || {}).bedrag === me.bedrag))) { p._docs.push(s); continue; }
    const c = { ...s, _docs: [s] };
    if (k && !p) m.set(k, c);
    uit.push(c);
  }
  for (const c of uit) if (c._docs.length > 1) {
    const best = c._docs.find(d => (d.meta || {}).bedrag) || c._docs[0], meta = { ...(best.meta || {}) };
    for (const d of c._docs) for (const [k, v] of Object.entries(d.meta || {})) if (!meta[k] && v) meta[k] = v;
    Object.assign(c, best, { meta, _docs: c._docs });
  }
  return uit;
}
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
  const sel = $('#fd-lev'), was = sel.value, levs = [...new Map(items.map(s => [s.leverancier, s.leverancierNaam || s.leverancier])).entries()];
  sel.innerHTML = '<option value="">Alle leveranciers</option>' + levs.map(([id, n]) => '<option value="' + esc(id) + '">' + esc(n) + '</option>').join('');
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
  $('#fd-tel').textContent = items.length ? tn(items.length, '{k} van {n} factuur', '{k} van {n} facturen', { k: lijst.length }) + (docs.length > items.length ? ' ' + t('({n} documenten)', { n: docs.length }) : '') + (totaal ? ' · ' + t('samen {bedrag}', { bedrag: FD_EUR(totaal) }) : '') : '';
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
function fdCsv(){ $('#fd-csv-msg').textContent = 'Exporteren…'; $('#fd-csv').disabled = true; Android.facturenDossierCsv(); }
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
  // Andere documenten van dezelfde factuur (zelfde leverancier en factuurnummer)
  const nr = (s.meta || {}).nummer, ook = nr ? (faDos.items || []).filter(x => x.sha !== sha && x.leverancier === s.leverancier && (x.meta || {}).nummer === nr) : [];
  if (ook.length) $('#fs-info').insertAdjacentHTML('beforeend', '<div class="bkres"><b>Hoort bij dezelfde factuur</b><span>' + ook.map(x => '<button class="linkish" data-sha="' + esc(x.sha) + '" onclick="fsToon(this.dataset.sha)">' + esc(x.naam || '') + '</button>').join('<br>') + '</span></div>');
  $('#fs-tekst-d').open = false; $('#fs-tekst').textContent = '';
  const app = (s.plekken || {}).app;
  $('#fs-open').disabled = !(app && !app.weg);
  $('#fs-bron').textContent = m.bron === 'zelf' ? 'Door jou ingevuld.' : m.bron === 'herkend' ? 'Herkend uit de factuur; controleer en pas aan waar nodig.' : 'Niet herkend: vul zelf in.';
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
function fsTekst(){
  const r = rdJson(Android.facturenDossierTekst(fsSha), { error: 'Lezen lukt niet' });
  $('#fs-tekst').textContent = r.error ? r.error : (r.tekst || '').trim() || '(geen tekst gevonden: misschien is de PDF een afbeelding)';
}
function fsOpen(){ const s = (faDos.items || []).find(x => x.sha === fsSha); if (s) Android.facturenOpen(s.leverancier, s.naam); }
function fsBewaar(){
  const e = Android.facturenDossierMeta(fsSha, JSON.stringify({ nummer: $('#fs-nummer').value.trim(), datum: $('#fs-datum').value, bedrag: $('#fs-bedrag').value.trim(), btw: $('#fs-btw').value.trim(), vervaldatum: $('#fs-verval').value, betaald: $('#fs-betaald').value, categorie: $('#fs-cat').value.trim(), notitie: $('#fs-notitie').value.trim() }));
  $('#fs-msg').textContent = e || '';
  if (e) return;
  fdLaad(); toast('✓ Bewaard'); fsToon(fsSha);
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
  $('#fo-levs').innerHTML = faSt.leveranciers.map(l => '<label class="chk"><input type="checkbox" class="fo-lev" value="' + esc(l.id) + '"' + ((o.leveranciers || []).includes(l.id) ? ' checked' : '') + '> ' + esc(l.naam) + '</label>').join('') || '<p class="note">Nog geen leveranciers.</p>';
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
function fmTest(){ $('#fm-msg').textContent = ''; $('#fm-test').disabled = true; Android.facturenSmtpTest(); }
window.onFacturenSmtpTest = function(r){
  $('#fm-test').disabled = false;
  $('#fm-msg').textContent = r.error ? r.error : '';
  if (!r.error) toast('✓ Verbinding met de mailserver werkt');
};
