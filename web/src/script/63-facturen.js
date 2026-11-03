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
function faDatum(t){ return t ? new Date(t).toLocaleString('nl-NL', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function enterFacturen(){
  faRender();
  if (!faPollT) faPollT = setInterval(() => { if (current !== 'facturen') { clearInterval(faPollT); faPollT = null; return; } faPoll(); }, 1000);
  if (current === 'facgoogle') { $('#fa-gd-msg').textContent = ''; }
  if (current === 'facdos') fdRender(true);
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
  $('#fa-bezig-txt').textContent = faSt.bezig ? (l ? l.naam + ': ' : '') + (faSt.voortgang || 'Bezig…') : '';
}
const FA_WD = ['', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
function faMoment(t){ return t ? new Date(t).toLocaleString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function faMaandSleutel(t){ const d = new Date(t); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); }
function faMaandNaam(k){ const [y, m] = k.split('-').map(Number); return new Date(y, m - 1, 1).toLocaleString('nl-NL', { month: 'long', year: 'numeric' }); }
/** Wanneer vanzelf, in gewone woorden: "elke maand op de 5e om 08:00". */
function faPlanTekst(l){
  if (l.schema === 'uit') return 'alleen met de hand';
  const tijd = l.tijd || '08:00';
  if (l.schema === 'maand') return 'elke maand op ' + ((l.dag || 5) >= 31 ? 'de laatste dag' : 'de ' + (l.dag || 5) + 'e' + ((l.dag || 5) > 28 ? ' (of de laatste dag)' : '')) + ' om ' + tijd;
  if (l.schema === 'week') return 'elke ' + FA_WD[l.weekdag || 1] + ' om ' + tijd;
  return 'elke dag om ' + tijd;
}
/** Stand van deze periode: {soort: ok|mis|wacht|bezig|uit|nieuw|pw, tekst}. */
function faPeriode(l){
  const t = l.toestand || {};
  if (faSt.bezig === l.id) return { soort: 'bezig', tekst: faSt.voortgang || 'Bezig…' };
  if (!l.heeftWachtwoord && l.gebruiker) return { soort: 'pw', tekst: 'Geen wachtwoord: vul het in of kijk mee om zelf in te loggen' };
  if (l.schema === 'uit') return t.lastOk ? { soort: 'ok', tekst: '✓ Laatst opgehaald ' + faMoment(t.lastOk) } : { soort: 'uit', tekst: 'Alleen met de hand' };
  // De laatste poging mislukte (ook als het eerder deze periode wel lukte): dat eerst laten zien
  if (t.lastTry && !t.ok && t.lastTry > (t.lastOk || 0)) return { soort: 'mis', tekst: '✗ Mislukt ' + faMoment(t.lastTry) + ': ' + (t.msg || '') + (l.vorige && t.lastOk >= l.vorige ? ' (deze periode was al opgehaald)' : ' (na ' + (l.opnieuw || 3) + ' uur opnieuw)') };
  if (l.vorige && t.lastOk >= l.vorige) return { soort: 'ok', tekst: '✓ Opgehaald ' + faMoment(t.lastOk) + ' · ' + (t.nieuw ? t.nieuw + (t.nieuw === 1 ? ' nieuwe factuur' : ' nieuwe facturen') : 'niets nieuws') };
  if (l.vorige && t.lastTry >= l.vorige && !t.ok) return { soort: 'mis', tekst: '✗ Mislukt ' + faMoment(t.lastTry) + ': ' + (t.msg || '') + ' (na ' + (l.opnieuw || 3) + ' uur opnieuw)' };
  if (l.vorige && (t.lastOk || t.lastTry || l.aantal)) return { soort: 'wacht', tekst: '⏳ Gepland ' + faMoment(l.vorige) + ': nog niet gelukt, de app probeert het zodra het kan' };
  return { soort: 'nieuw', tekst: 'Nog niet opgehaald' };
}
function faStatus(l){ return faPeriode(l).tekst; }
function faRender(geenLaad){
  if (!geenLaad) faLaad();
  faBezig();
  $('#fa-lijst').innerHTML = faSt.leveranciers.map(l => {
    const p = faPeriode(l), fout = p.soort === 'mis' || p.soort === 'pw';
    const volgende = l.schema !== 'uit' && l.volgende ? 'Volgende keer: ' + faMoment(l.volgende) : 'Vanzelf: ' + faPlanTekst(l);
    return '<div class="srow"><button class="sbtn" data-id="' + esc(l.id) + '" onclick="faOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon(l.soort === 'mail' ? 'mail' : 'document') + '</span>' +
      '<span class="stxt"><b>' + esc(l.naam) + '</b><small' + (fout ? ' class="bad"' : '') + '>' + esc(p.tekst) + '</small><small>' + esc(volgende + ' · ' + faPlanTekst(l)) + '</small></span></button></div>';
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
  $('#fm-stand').textContent = sm.host ? 'Ingesteld: ' + sm.host + ':' + (sm.poort || 587) + ', afzender ' + (sm.van || '?') + '.' : 'Niet ingesteld. Met je eigen mailserver (SMTP) kan Tools facturen versturen aan je boekhouder vanaf je eigen adres.';
  $('#fm-los').style.display = sm.host ? 'block' : 'none';
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#s-facec .card input, #s-facgoogle .card input')) return; // niet overschrijven tijdens typen
  $('#fa-ec-url').value = ec.url || ''; $('#fa-ec-user').value = ec.gebruiker || ''; $('#fa-ec-adres').value = ec.adres || '';
  $('#fa-ec-pw').value = ''; $('#fa-ec-pw').placeholder = ec.heeftWachtwoord ? 'Ongewijzigd' : '';
  $('#fa-ec-stand').textContent = ec.url && ec.heeftWachtwoord ? 'Gekoppeld met ' + ec.url.replace(/^https:\/\//, '') + '. Facturen gaan als mail met de PDF naar ' + (ec.adres || ec.gebruiker) + '.'
    : 'Niet gekoppeld. Koppel Eigen Cloud om facturen in je eigen mail te krijgen, of om de inlogcode uit je mail te lezen.';
  const lok = faSt.lokaal || '';
  $('#fa-lok-stand').textContent = lok === '!' ? 'Tools kan niet meer bij de gekozen map. Kies hem opnieuw.' : lok ? 'Facturen komen in ' + lok + ', per leverancier een eigen map.' : 'Nog geen map gekozen. Kies bijvoorbeeld Documenten; per leverancier komt er een eigen map in.';
  $('#fa-lok-stand').classList.toggle('bad', lok === '!');
  $('#fa-lok-kies').textContent = lok ? 'Andere map kiezen' : 'Map kiezen';
  $('#fa-lok-los').style.display = lok ? 'block' : 'none';
  const gd = faSt.drive || {};
  $('#fa-gd-setup').style.display = gd.gekoppeld ? 'none' : 'block';
  $('#fa-gd-aan').style.display = gd.gekoppeld ? 'block' : 'none';
  $('#fa-gd-stand').textContent = gd.gekoppeld ? 'Gekoppeld' + (gd.gebruiker ? ' met ' + gd.gebruiker : '') + '.' : '';
  $('#fa-gd-rechten').textContent = gd.gekoppeld ? 'Gmail: ' + (gd.rechtGmail ? '✓ facturen zoeken (alleen lezen)' : '– geen toegang') + ' · Drive: ' + (gd.rechtDrive ? '✓ bewaren in de map Facturen' : '– geen toegang') + ' · Versturen: ' + (gd.rechtGmailSend ? '✓ aan je boekhouder' : '– nee') + '. Tools ziet in Drive alleen zijn eigen mappen.' : '';
  if (gd.gekoppeld) { $('#fa-gd-r-gmail').checked = !!gd.rechtGmail || $('#fa-gd-r-gmail').checked; $('#fa-gd-r-drive').checked = !!gd.rechtDrive; $('#fa-gd-r-send').checked = !!gd.rechtGmailSend || $('#fa-gd-r-send').checked; }
  $('#fa-gd-knop').textContent = gd.gekoppeld ? 'Opnieuw koppelen met deze rechten' : 'Koppelen met Google';
  if (!gd.gekoppeld && gd.clientId && !$('#fa-gd-id').value) $('#fa-gd-id').value = gd.clientId;
  $('#fa-gd-geheim').placeholder = gd.heeftGeheim ? 'Client secret (ongewijzigd)' : 'Client secret';
}
/** De korte stand onder elke rij van Meer, Opslaan en Koppelingen. */
function faSamenvatting(){
  const gd = faSt.drive || {}, ec = faSt.ec || {}, lok = faSt.lokaal || '';
  const ecAan = !!(ec.url && ec.heeftWachtwoord);
  const plekken = [lok && lok !== '!' ? 'telefoon' : '', faSt.map ? 'backup-map' : '', gd.rechtDrive ? 'Drive' : '', ecAan ? 'Eigen Cloud' : ''].filter(Boolean);
  const zet = (id, t) => { const e = $('#' + id); if (e) e.textContent = t; };
  zet('fa-sum-overz', faSt.leveranciers.length ? 'De laatste 12 maanden per leverancier' : 'Nog geen leveranciers');
  zet('fa-sum-opslag', plekken.length ? 'Ingesteld: ' + plekken.join(', ') : 'Nog niets ingesteld (alleen in de app)');
  zet('fa-sum-kopp', [gd.gekoppeld ? 'Google' : '', ecAan ? 'Eigen Cloud' : '', (faSt.smtp || {}).host ? 'mailserver' : ''].filter(Boolean).join(', ') || 'Nog niets gekoppeld');
  zet('fa-sum-lok', lok === '!' ? 'Geen toegang meer: kies hem opnieuw' : lok || 'Nog geen map gekozen');
  zet('fa-sum-backup', faSt.map ? 'Map Facturen in je backup-map' : 'Nog geen backup-map gekozen');
  zet('fa-sum-drive', gd.rechtDrive ? 'Gekoppeld' + (gd.gebruiker ? ' · ' + gd.gebruiker : '') : gd.gekoppeld ? 'Google gekoppeld, maar zonder Drive' : 'Niet gekoppeld');
  zet('fa-sum-ec', ecAan ? 'Gekoppeld · ' + ec.url.replace(/^https:\/\//, '') : 'Niet gekoppeld');
  zet('fa-sum-ec2', ecAan ? 'Gekoppeld · ' + ec.url.replace(/^https:\/\//, '') : 'Niet gekoppeld');
  const sm = faSt.smtp || {};
  zet('fa-sum-smtp', sm.host ? 'Ingesteld · ' + sm.host : 'Niet ingesteld');
  zet('fa-sum-dos', faSt.dossier ? faSt.dossier + (faSt.dossier === 1 ? ' factuur' : ' facturen') + ' met alle gegevens' : 'Nog leeg');
  zet('fa-sum-aanl', faSt.ontvangers ? faSt.ontvangers + (faSt.ontvangers === 1 ? ' ontvanger' : ' ontvangers') : 'Nog geen ontvanger (bijv. je boekhouder)');
  zet('fa-sum-google', gd.gekoppeld ? [gd.rechtGmail ? 'Gmail' : '', gd.rechtDrive ? 'Drive' : '', gd.rechtGmailSend ? 'versturen' : ''].filter(Boolean).join(', ') + (gd.gebruiker ? ' · ' + gd.gebruiker : '') : 'Niet gekoppeld');
}
/* Overzicht: deze maand per leverancier, en per maand wat er binnenkwam */
function faMaandRender(){
  const l = faSt.leveranciers, nu = Date.now(), dezeMaand = faMaandSleutel(nu);
  $('#fa-maand').style.display = l.length ? 'block' : 'none';
  if (!l.length) { $('#fa-overzicht-lijst').innerHTML = '<p class="note">Nog geen leveranciers.</p>'; return; }
  $('#fa-maand-h').textContent = 'Deze maand · ' + faMaandNaam(dezeMaand);
  $('#fa-maand-lijst').innerHTML = l.map(x => {
    const n = (x.maanden || {})[dezeMaand] || 0, p = faPeriode(x);
    const binnen = n ? '✓ ' + n + (n === 1 ? ' factuur binnen' : ' facturen binnen') : p.soort === 'mis' ? '✗ Nog niet: mislukt' : x.schema !== 'uit' && x.volgende && faMaandSleutel(x.volgende) === dezeMaand && !(x.vorige && faMaandSleutel(x.vorige) === dezeMaand) ? 'Gepland ' + faMoment(x.volgende) : x.schema === 'uit' ? 'Alleen met de hand' : 'Nog niets binnen';
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
  askConfirm('Geschiedenis · ' + l.naam, log.map(r => (r.ok ? '✓ ' : '✗ ') + faMoment(r.t) + (r.auto ? ' (vanzelf)' : ' (met de hand)') + '\n   ' + (r.msg || '') + ((r.namen || []).length ? '\n   ' + r.namen.join(', ') : '')).join('\n'), 'Sluiten', () => {});
}
function faOpen(id){
  const l = faSt.leveranciers.find(x => x.id === id); if (!l) return;
  const busy = !!faSt.bezig;
  const acts = [
    ['Nu ophalen', () => faNu(id, false)],
    ...(l.soort === 'mail' ? [] : [['Ophalen en meekijken', () => faNu(id, true)]]),
    ['Facturen bekijken' + (l.aantal ? ' (' + l.aantal + ')' : ''), () => faBekijk(l)],
    ['Geschiedenis', () => faGeschiedenis(l)],
    ['Instellingen', () => flOpen(l)],
    ['Verwijderen…', () => askConfirm(l.naam + ' verwijderen?', 'De instellingen en de facturen in de app gaan weg. Kopieën in je backup-map en in Eigen Cloud blijven staan.', 'Verwijderen', () => { const e = Android.facturenVerwijder(id); if (e) toast(e); faRender(); })]];
  openSheet(l.naam, busy ? 'Er wordt nu opgehaald: even wachten' : faStatus(l), busy ? acts.filter(a => !/^(Nu ophalen|Ophalen en meekijken)/.test(a[0])) : acts);
}
function faNu(id, meekijken){
  const e = Android.facturenNu(id, !!meekijken);
  if (e) { toast(e); return; }
  if (!meekijken) { faSt.bezig = id; faSt.voortgang = 'Starten…'; faBezig(); toast('Bezig met ophalen; dat kan een paar minuten duren'); }
}
window.onFacturenKlaar = function(r){
  if (!r.ok && r.koppelNodig) { toast(r.msg || 'Koppeling nodig'); setTimeout(() => askConfirm('Koppeling nodig', r.msg || '', 'Naar Google', () => faNaar('facgoogle')), 600); if (FA_SCHERMEN.includes(current)) faRender(); return; }
  toast((r.ok ? '✓ ' : '') + (r.naam ? r.naam + ': ' : '') + (r.msg || ''));
  if (r.ok && (r.fouten || []).length) setTimeout(() => toast('Niet alles afgeleverd: ' + r.fouten[0]), 2800);
  if (!r.ok && r.inlogNodig) setTimeout(() => askConfirm('Inloggen nodig', (r.msg || '') + '\n\nKijk één keer mee: dan log je zelf in en gaat de app daarna verder.', 'Meekijken', () => faNu(r.id, true)), 600);
  if (FA_SCHERMEN.includes(current)) faRender();
};
function faBekijk(l){
  const lijst = rdJson(Android.facturenLijst(l.id), []);
  if (!lijst.length) { toast('Nog geen facturen in de app'); return; }
  openSheet(l.naam, lijst.length + (lijst.length === 1 ? ' factuur' : ' facturen'), lijst.slice(0, 40).map(f => [f.naam + ' · ' + faDatum(f.t), () => Android.facturenOpen(l.id, f.naam)]));
}
function faNieuw(){
  const s = faSt.sjablonen || [];
  openSheet('Leverancier toevoegen', 'Kies een sjabloon; alles is daarna aan te passen', s.map(x => [(x.naam || 'Andere website') + (x.omschrijving ? ' · ' + x.omschrijving : ''), () => flOpen(Object.assign({ soort: 'website' }, x, { id: '' }), true)]));
}
/* Instellingen van één leverancier */
function flOpen(l, nieuw){
  faEdit = JSON.parse(JSON.stringify(l)); faEdit._nieuw = !!nieuw;
  const c = l.code || {}, v = l.velden || {};
  $('#fl-h').textContent = nieuw ? (l.naam ? l.naam + ' toevoegen' : 'Leverancier toevoegen') : l.naam;
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
  $('#fl-facturl').value = l.facturenUrl || ''; $('#fl-menu').value = l.menu || ''; $('#fl-knoppen').value = l.knoppen || '';
  $('#fl-max').value = l.max || 12; $('#fl-sub').value = l.submap || '';
  $('#fl-start').placeholder = 'https://…';
  ['gebruiker', 'wachtwoord', 'code', 'knop'].forEach(k => $('#fl-v-' + k).value = v[k] || '');
  const m = l.mail || {};
  $('#fl-m-bron').value = m.bron || 'gmail'; $('#fl-m-woorden').value = m.woorden != null ? m.woorden : 'factuur, invoice, rekening, nota';
  $('#fl-m-afz').value = m.afzenders || ''; $('#fl-m-neg').value = m.negeer || ''; $('#fl-m-extra').value = m.extra || '';
  $('#fl-m-dagen').value = String([30, 90, 180, 365, 730].includes(m.dagen) ? m.dagen : 90); $('#fl-m-max').value = m.max || 50; $('#fl-m-per').checked = m.perAfzender !== false;
  $('#fl-msg').textContent = '';
  flBron(); flPlan(); flZoek(); flOpslag();
  flTab(faEdit.soort === 'mail' ? 'zoek' : 'inlog');
  faNaar('faclev');
  $('#fl-m-woorden').oninput = $('#fl-m-afz').oninput = $('#fl-m-neg').oninput = $('#fl-m-extra').oninput = flZoek;
  ['fl-lok', 'fl-map', 'fl-gd', 'fl-ec'].forEach(i => $('#' + i).onchange = flOpslag);
}
/** Tabbladen: website = Inloggen, Planning, Opslaan, Meer; mail = Zoeken, Planning, Opslaan. */
function flTab(t){
  const mail = faEdit && faEdit.soort === 'mail', mag = mail ? ['zoek', 'plan', 'opslag'] : ['inlog', 'plan', 'opslag', 'adv'];
  if (!mag.includes(t)) t = mag[0];
  document.querySelectorAll('.fl-tabs button').forEach(b => { const ok = mag.includes(b.dataset.tab); b.style.display = ok ? '' : 'none'; b.classList.toggle('on', b.dataset.tab === t); b.setAttribute('aria-selected', b.dataset.tab === t ? 'true' : 'false'); });
  document.querySelectorAll('.fl-paneel').forEach(p => p.classList.toggle('on', p.dataset.tab === t));
}
/** Zoeken in de mail: de Gmail-zoekopdracht laten zien (zelfde regels als FacturenGmail.zoekvraag) en de koppeling controleren. */
function faDelen(s){ return String(s || '').split(/[,;\n]/).map(x => x.replace(/["(){}]/g, ' ').replace(/\s+/g, ' ').trim()).filter(Boolean); }
function faZoekvraag(m){
  let q = 'has:attachment filename:pdf';
  const w = faDelen(m.woorden), q1 = t => t.includes(' ') ? '"' + t + '"' : t;
  if (w.length) q += ' (' + w.map(t => q1(t) + (t.includes(' ') ? '' : ' OR filename:' + t)).join(' OR ') + ')';
  const a = faDelen(m.afzenders); if (a.length) q += ' from:(' + a.map(q1).join(' OR ') + ')';
  faDelen(m.negeer).forEach(n => q += ' -from:' + q1(n));
  if ((m.extra || '').trim()) q += ' ' + m.extra.trim();
  return q;
}
function flZoek(){
  const gd = faSt.drive || {};
  $('#fl-m-kopp').style.display = gd.rechtGmail ? 'none' : 'block';
  $('#fl-m-vraag').textContent = 'Zoekt in Gmail op: ' + faZoekvraag({ woorden: $('#fl-m-woorden').value, afzenders: $('#fl-m-afz').value, negeer: $('#fl-m-neg').value, extra: $('#fl-m-extra').value }) + ' (en alleen nieuwe mails sinds de vorige keer).';
}
function flOpslag(){
  const mail = faEdit && faEdit.soort === 'mail', per = mail && $('#fl-m-per').checked;
  document.querySelector('label[for="fl-sub"]').textContent = per ? 'Mapnaam (de afzendermappen komen hierin)' : 'Mapnaam';
  const gd = faSt.drive || {}, ec = faSt.ec || {}, mis = [];
  if ($('#fl-lok').checked && !(faSt.lokaal && faSt.lokaal !== '!')) mis.push('kies een map op je telefoon');
  if ($('#fl-map').checked && !faSt.map) mis.push('kies een backup-map');
  if ($('#fl-gd').checked && !gd.rechtDrive) mis.push('koppel Google met Drive');
  if ($('#fl-ec').checked && !(ec.url && ec.heeftWachtwoord)) mis.push('koppel Eigen Cloud');
  $('#fl-opslag-msg').textContent = mis.length ? 'Nog te doen bij Facturen → Opslaan: ' + mis.join(', ') + '.' : '';
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
function flPlan(){
  const s = $('#fl-schema').value;
  $('#fl-dagrij').style.display = s === 'maand' ? 'flex' : 'none';
  $('#fl-wdrij').style.display = s === 'week' ? 'flex' : 'none';
  $('#fl-tijdrij').style.display = s === 'uit' ? 'none' : 'flex';
  $('#fl-opnrij').style.display = s === 'uit' ? 'none' : 'flex';
  $('#fl-wifirij').style.display = s === 'uit' ? 'none' : 'flex';
  const o = { schema: s, dag: Number($('#fl-dag').value) || 5, weekdag: Number($('#fl-wd').value) || 1, tijd: $('#fl-tijd').value || '08:00' };
  const opn = Number($('#fl-opnieuw').value) || 3;
  $('#fl-plan').textContent = s === 'uit' ? 'Alleen als je zelf op Nu ophalen tikt.' : 'Vanzelf ' + faPlanTekst(o) + '. Eerstvolgende keer: ' + faMoment(faVolgende(o, Date.now())) +
    '. Staat de telefoon dan uit of zonder ' + ($('#fl-wifi').checked ? 'wifi' : 'internet') + ', dan haalt de app het zo snel mogelijk daarna in; lukt het niet, dan na ' + opn + (opn === 1 ? ' uur' : ' uur') + ' opnieuw.';
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
      dagen: Number($('#fl-m-dagen').value) || 90, max: Number($('#fl-m-max').value) || 50, perAfzender: $('#fl-m-per').checked },
    naam: $('#fl-naam').value.trim(), startUrl: $('#fl-start').value.trim(), gebruiker: $('#fl-user').value.trim(), wachtwoord: $('#fl-pw').value,
    facturenUrl: $('#fl-facturl').value.trim(), menu: $('#fl-menu').value.trim(), knoppen: $('#fl-knoppen').value.trim(), max: Number($('#fl-max').value) || 12,
    code: { bron: $('#fl-bron').value, afzender: $('#fl-afz').value.trim(), patroon: $('#fl-pat').value.trim() },
    schema: $('#fl-schema').value, dag: Number($('#fl-dag').value) || 5, weekdag: Number($('#fl-wd').value) || 1, tijd: $('#fl-tijd').value || '08:00',
    opnieuw: Number($('#fl-opnieuw').value) || 3, wifi: $('#fl-wifi').checked, meldNieuw: $('#fl-meldnieuw').checked, meldFout: $('#fl-meldfout').checked, map: $('#fl-map').checked, lokaal: $('#fl-lok').checked, drive: $('#fl-gd').checked, eigenCloud: $('#fl-ec').checked, submap: $('#fl-sub').value.trim(),
    velden: { gebruiker: $('#fl-v-gebruiker').value.trim(), wachtwoord: $('#fl-v-wachtwoord').value.trim(), code: $('#fl-v-code').value.trim(), knop: $('#fl-v-knop').value.trim() }
  };
  for (const [p, wat] of [[o.menu, 'De tekst van de link naar de facturen'], [o.knoppen, 'Het herkennen van downloadknoppen']]) {
    if (o.soort === 'mail') break;
    try { new RegExp(p, 'i'); } catch (e) { $('#fl-msg').textContent = wat + ' is geen geldig zoekpatroon.'; flTab('adv'); return; }
  }
  const fout = (t, tab) => { $('#fl-msg').textContent = t; flTab(tab); };
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
function faGdKoppel(){
  $('#fa-gd-msg').textContent = '';
  const id = $('#fa-gd-id').value.trim(), geheim = $('#fa-gd-geheim').value.trim();
  if (!/^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/.test(id)) { $('#fa-gd-msg').textContent = 'Een Client ID van Google eindigt op .apps.googleusercontent.com. Kopieer hem uit je Google-project.'; return; }
  const gd = faSt.drive || {};
  if (!geheim && (!gd.heeftGeheim || id !== gd.clientId)) { $('#fa-gd-msg').textContent = 'Vul ook het Client secret in' + (gd.heeftGeheim ? ' (nieuwe Client ID = ook een nieuw secret).' : '.'); return; }
  const rechten = [$('#fa-gd-r-gmail').checked ? 'gmail' : '', $('#fa-gd-r-drive').checked ? 'drive' : '', $('#fa-gd-r-send').checked ? 'gmailsend' : ''].filter(Boolean).join(',');
  if (!rechten) { $('#fa-gd-msg').textContent = 'Vink aan waarvoor Tools Google mag gebruiken (Gmail en/of Drive).'; return; }
  const zonder = [!rechten.includes('drive') && faSt.leveranciers.some(l => l.drive) ? 'Drive (leveranciers bewaren daar)' : '', !/\bgmail\b/.test(rechten) && faSt.leveranciers.some(l => l.soort === 'mail') ? 'Gmail (leveranciers zoeken daar)' : '', !rechten.includes('gmailsend') && (faSt.drive || {}).rechtGmailSend ? 'Versturen met Gmail (aan je boekhouder)' : ''].filter(Boolean);
  if (zonder.length && !faGdKoppel._ok) { askConfirm('Minder rechten?', 'Zonder ' + zonder.join(' en ') + ' werken die leveranciers niet meer tot je het weer aanzet.', 'Toch koppelen', () => { faGdKoppel._ok = true; try { faGdKoppel(); } finally { faGdKoppel._ok = false; } }); return; }
  const e = Android.facturenDriveKoppel(id, geheim, rechten);
  $('#fa-gd-geheim').value = '';
  if (e) { $('#fa-gd-msg').textContent = e; return; }
  toast('Log in bij Google en geef toestemming; kom daarna terug');
}
window.onFacturenDrive = function(r){
  if (r.error) { $('#fa-gd-msg').textContent = r.error; toast(r.error); }
  else toast('✓ Google gekoppeld' + (r.naam ? ' (' + r.naam + ')' : ''));
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
  const v = Object.entries(s.verzonden || {}), ok = v.filter(([, x]) => x.ok), mis = v.filter(([, x]) => !x.ok && !x.opnieuw);
  if (mis.length) return { soort: 'mis', tekst: '✗ Niet aangeleverd aan ' + mis.map(([id, x]) => x.naam || id).join(', ') };
  if (ok.length) return { soort: 'ok', tekst: '✓ Aangeleverd aan ' + ok.map(([id, x]) => (x.naam || id) + ' (' + faDatum(x.t) + ')').join(', ') };
  return { soort: (faDos.ontvangers || []).length ? 'open' : 'geen', tekst: (faDos.ontvangers || []).length ? 'Nog niet aangeleverd' : '' };
}
function fdRender(laad){
  if (laad || !faDos) fdLaad();
  const items = faDos.items || [];
  const sel = $('#fd-lev'), was = sel.value, levs = [...new Map(items.map(s => [s.leverancier, s.leverancierNaam || s.leverancier])).entries()];
  sel.innerHTML = '<option value="">Alle leveranciers</option>' + levs.map(([id, n]) => '<option value="' + esc(id) + '">' + esc(n) + '</option>').join('');
  sel.value = levs.some(([id]) => id === was) ? was : '';
  const q = $('#fd-zoek').value.trim().toLowerCase(), f = $('#fd-filter').value;
  const lijst = items.filter(s => {
    if (sel.value && s.leverancier !== sel.value) return false;
    const a = fdAanl(s);
    if (f === 'open' && a.soort !== 'open' && a.soort !== 'mis') return false;
    if (f === 'mis' && a.soort !== 'mis') return false;
    if (!q) return true;
    const m = s.meta || {};
    return [s.leverancierNaam, s.naam, m.nummer, m.bedrag, m.datum, m.categorie, m.notitie, (s.bron || {}).afzender, (s.bron || {}).onderwerp].some(x => String(x || '').toLowerCase().includes(q));
  });
  const som = lijst.reduce((t, s) => t + (parseFloat(String((s.meta || {}).bedrag || '').replace(',', '.')) || 0), 0);
  $('#fd-tel').textContent = items.length ? lijst.length + ' van ' + items.length + (items.length === 1 ? ' factuur' : ' facturen') + (som ? ' · samen € ' + som.toFixed(2).replace('.', ',') : '') : '';
  $('#fd-lijst').innerHTML = lijst.slice(0, 300).map(s => {
    const m = s.meta || {}, a = fdAanl(s);
    const regel = [m.datum ? new Date(m.datum + 'T12:00').toLocaleDateString('nl-NL', { day: 'numeric', month: 'short', year: 'numeric' }) : 'opgehaald ' + faDatum(s.opgehaald), m.nummer ? 'nr. ' + m.nummer : '', m.bedrag ? '€ ' + m.bedrag : ''].filter(Boolean).join(' · ');
    return '<div class="srow"><button class="sbtn" data-sha="' + esc(s.sha) + '" onclick="fsToon(this.dataset.sha)"><span class="vpic" aria-hidden="true">' + uiIcon((s.bron || {}).soort === 'mail' ? 'mail' : 'document') + '</span>' +
      '<span class="stxt"><b>' + esc(s.naam || '') + '</b><small>' + esc((s.leverancierNaam ? s.leverancierNaam + ' · ' : '') + regel) + '</small>' + (a.tekst ? '<small' + (a.soort === 'mis' ? ' class="bad"' : a.soort === 'ok' ? ' class="good"' : '') + '>' + esc(a.tekst) + '</small>' : '') + '</span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">' + (items.length ? 'Niets gevonden.' : 'Nog geen facturen in het dossier. Ze komen er vanzelf in zodra er facturen zijn opgehaald.') + '</p>';
  $('#fd-csv').disabled = !items.length;
}
function fdCsv(){ $('#fd-csv-msg').textContent = 'Exporteren…'; $('#fd-csv').disabled = true; Android.facturenDossierCsv(); }
window.onFacturenCsv = function(r){
  $('#fd-csv').disabled = false;
  $('#fd-csv-msg').textContent = r.error ? r.error : '✓ Opgeslagen in ' + r.waar;
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
    rij('Grootte', s.grootte ? Math.max(1, Math.round(s.grootte / 1024)) + ' kB' : '') +
    (b.soort === 'mail' ? rij('Uit de mail van', b.afzender) + rij('Onderwerp', b.onderwerp) + rij('Mail ontvangen', b.maildatum ? faDatum(b.maildatum) : '') : rij('Website', b.site) + rij('Pagina', b.pagina)) +
    rij('Controlegetal (SHA-256)', (s.sha || '').slice(0, 16) + '…');
  const app = (s.plekken || {}).app;
  $('#fs-open').disabled = !(app && !app.weg);
  $('#fs-bron').textContent = m.bron === 'zelf' ? 'Door jou ingevuld.' : m.bron === 'herkend' ? 'Herkend uit de factuur; controleer en pas aan waar nodig.' : 'Niet herkend: vul zelf in.';
  $('#fs-nummer').value = m.nummer || ''; $('#fs-datum').value = m.datum || ''; $('#fs-bedrag').value = m.bedrag || ''; $('#fs-btw').value = m.btw || '';
  $('#fs-cat').value = m.categorie || ''; $('#fs-notitie').value = m.notitie || ''; $('#fs-msg').textContent = '';
  $('#fs-cats').innerHTML = [...new Set((faDos.items || []).map(x => (x.meta || {}).categorie).filter(Boolean))].map(c => '<option value="' + esc(c) + '">').join('');
  $('#fs-plekken').innerHTML = Object.entries(s.plekken || {}).map(([k, x]) => {
    const waar = x.pad ? x.pad.replace(/^\/data\/(user\/0|data)\/[^/]+\/files\//, '') : x.aan ? 'mail aan ' + x.aan : x.id ? 'bestand ' + x.id.slice(0, 10) + '…' : '';
    return '<div class="bkres"><b>' + esc(FA_PLEK[k] || k) + '</b><span style="overflow-wrap:anywhere">' + (x.weg ? 'weg (leverancier verwijderd)' : esc([waar, faDatum(x.t)].filter(Boolean).join(' · '))) +
      (x.link ? ' <button class="linkish" data-u="' + esc(x.link) + '" onclick="Android.openUrl(this.dataset.u)">Openen</button>' : '') + '</span></div>';
  }).join('') || '<p class="note">Onbekend</p>';
  const ontv = faDos.ontvangers || [];
  $('#fs-aan').innerHTML = ontv.map(o => {
    const x = (s.verzonden || {})[o.id];
    const st = !x ? 'Nog niet' : x.ok ? '✓ ' + faDatum(x.t) + (x.aan ? ' aan ' + x.aan : '') : x.opnieuw ? 'Gaat opnieuw' : '✗ ' + (x.fout || 'mislukt') + ' (' + faDatum(x.t) + ')';
    return '<div class="bkres"><b>' + esc(o.naam) + '</b><span' + (x && !x.ok && !x.opnieuw ? ' class="bad"' : x && x.ok ? ' class="good"' : '') + '>' + esc(st) +
      (x && x.ok ? ' <button class="linkish" data-o="' + esc(o.id) + '" onclick="fsOpnieuw(this.dataset.o)">Opnieuw</button>' : '') + '</span></div>';
  }).join('') || '<p class="note">Nog geen ontvanger. <button class="linkish" onclick="faNaar(\'facaanl\')">Ontvanger toevoegen</button></p>';
  if (current !== 'facstuk') faNaar('facstuk');
}
function fsOpen(){ const s = (faDos.items || []).find(x => x.sha === fsSha); if (s) Android.facturenOpen(s.leverancier, s.naam); }
function fsBewaar(){
  const e = Android.facturenDossierMeta(fsSha, JSON.stringify({ nummer: $('#fs-nummer').value.trim(), datum: $('#fs-datum').value, bedrag: $('#fs-bedrag').value.trim(), btw: $('#fs-btw').value.trim(), categorie: $('#fs-cat').value.trim(), notitie: $('#fs-notitie').value.trim() }));
  $('#fs-msg').textContent = e || '';
  if (e) return;
  fdLaad(); toast('✓ Bewaard'); fsToon(fsSha);
}
function fsOpnieuw(ontv){
  const o = (faDos.ontvangers || []).find(x => x.id === ontv);
  askConfirm('Opnieuw aanleveren?', 'Deze factuur gaat nog een keer naar ' + (o ? o.naam : ontv) + ': de volgende keer vanzelf, of nu met Aanleveren → Nu versturen.', 'Opnieuw', () => {
    const e = Android.facturenDossierOpnieuw(fsSha, ontv); if (e) { toast(e); return; } fdLaad(); fsToon(fsSha); toast('✓ Gaat opnieuw');
  });
}

/* ---------- Aanleveren aan de accountant of boekhouder ---------- */
let foSt = { lijst: [], kanalen: [], via: [], log: [] }, foEdit = null;
function foLaad(){ foSt = rdJson(Android.facturenOntvangers(), { lijst: [], kanalen: [], via: [], log: [] }); return foSt; }
function foPlanTekst(o){ return o.schema === 'direct' ? 'direct na het ophalen' : faPlanTekst(o); }
function foRender(laad){
  if (laad !== false) foLaad();
  $('#fo-bezig').style.display = foSt.bezig ? 'block' : 'none';
  $('#fo-bezig-txt').textContent = foSt.voortgang || 'Versturen…';
  $('#fo-lijst').innerHTML = foSt.lijst.map(o => {
    const t = o.toestand || {}, via = (foSt.via.find(v => v.id === o.via) || {}).naam || o.via;
    const st = o.mist ? '✗ ' + o.mist : t.lastTry && !t.ok ? '✗ ' + faMoment(t.lastTry) + ': ' + (t.msg || 'mislukt') : t.lastOk ? '✓ Laatst ' + faMoment(t.lastOk) + ': ' + (t.msg || '') : 'Nog niets verstuurd';
    const wanneer = !o.actief ? 'Uit' : (o.open ? o.open + (o.open === 1 ? ' factuur klaar' : ' facturen klaar') : 'Niets open') + ' · ' + foPlanTekst(o) + (o.volgende ? ' (volgende keer ' + faMoment(o.volgende) + ')' : '');
    return '<div class="srow"><button class="sbtn" data-id="' + esc(o.id) + '" onclick="foOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon('mail') + '</span>' +
      '<span class="stxt"><b>' + esc(o.naam) + '</b><small>' + esc((o.aan || '') + ' · via ' + via) + '</small><small' + (st.startsWith('✗') ? ' class="bad"' : '') + '>' + esc(st) + '</small><small>' + esc(wanneer) + '</small></span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">Nog geen ontvanger. Tik op <b>Ontvanger toevoegen</b> en vul het mailadres van je accountant of boekhouder in.</p>';
}
function foOpen(id){
  const o = foSt.lijst.find(x => x.id === id); if (!o) return;
  openSheet(o.naam, (o.open ? o.open + (o.open === 1 ? ' factuur staat klaar' : ' facturen staan klaar') : 'Alles is al aangeleverd') + ' · ' + foPlanTekst(o), [
    ['Nu versturen' + (o.open ? ' (' + o.open + ')' : ''), () => foNu(id, false)],
    ['Proef naar mezelf', () => foNu(id, true)],
    ['Instellingen', () => foForm(o)],
    ['Verwijderen…', () => askConfirm(o.naam + ' verwijderen?', 'Wat al verstuurd is, blijft in het dossier staan.', 'Verwijderen', () => { const e = Android.facturenOntvangerWeg(id); if (e) toast(e); foRender(); faLaad(); faSamenvatting(); })]]);
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
  askConfirm('Logboek aanleveren', log.slice(0, 60).map(r => (r.ok ? '✓ ' : '✗ ') + faMoment(r.t) + ' · ' + (r.naam || '') + (r.proef ? ' (proef)' : r.auto ? ' (vanzelf)' : ' (met de hand)') + '\n   ' + (r.msg || '') + ((r.namen || []).length ? '\n   ' + r.namen.join(', ') : '')).join('\n'), 'Sluiten', () => {});
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
  $('#fo-via-msg').innerHTML = v.mist ? esc(v.mist) + ' <button class="linkish" onclick="faNaar(\'' + doel + '\')">Nu instellen</button>' : v.van ? 'Wordt verstuurd vanaf ' + esc(v.van) + '.' : '';
  $('#fo-via-msg').classList.toggle('bad', !!v.mist);
}
function foPlan(){
  const s = $('#fo-schema').value;
  $('#fo-dagrij').style.display = s === 'maand' ? 'flex' : 'none';
  $('#fo-wdrij').style.display = s === 'week' ? 'flex' : 'none';
  $('#fo-tijdrij').style.display = s === 'uit' || s === 'direct' ? 'none' : 'flex';
  $('#fo-opnrij').style.display = s === 'uit' ? 'none' : 'flex';
  const o = { schema: s, dag: Number($('#fo-dag').value) || 1, weekdag: Number($('#fo-wd').value) || 1, tijd: $('#fo-tijd').value || '09:00' };
  $('#fo-plan').textContent = s === 'uit' ? 'Alleen als je zelf op Nu versturen tikt.' : s === 'direct' ? 'Zodra er nieuwe facturen zijn opgehaald, gaan ze binnen een paar minuten weg.'
    : 'Vanzelf ' + faPlanTekst(o) + ', met alles wat sinds de vorige keer is binnengekomen. Eerstvolgende keer: ' + faMoment(faVolgende(o, Date.now())) + '.';
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
