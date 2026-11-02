/* ---------- Facturen ophalen ----------
 * Algemeen raamwerk: per leverancier (eerste: KPN Zakelijk) inloggen, facturen zoeken en PDF's bewaren.
 * Het eigenlijke werk doet de app (FacturenRun, web/facturen-robot.js); hier de lijst en de instellingen. */
let faSt = { leveranciers: [], sjablonen: [], ec: {} }, faPollT = null, faEdit = null;
function faDatum(t){ return t ? new Date(t).toLocaleString('nl-NL', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function enterFacturen(){
  faRender();
  if (!faPollT) faPollT = setInterval(() => { if (current !== 'facturen') { clearInterval(faPollT); faPollT = null; return; } faPoll(); }, 1000);
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
    return '<div class="srow"><button class="sbtn" data-id="' + esc(l.id) + '" onclick="faOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon('document') + '</span>' +
      '<span class="stxt"><b>' + esc(l.naam) + '</b><small' + (fout ? ' class="bad"' : '') + '>' + esc(p.tekst) + '</small><small>' + esc(volgende + ' · ' + faPlanTekst(l)) + '</small></span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">Nog geen leveranciers. Tik op <b>Leverancier toevoegen</b>.</p>';
  $('#fa-nieuw').disabled = !!faSt.onleesbaar;
  faMaandRender();
  const ec = faSt.ec || {};
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#s-facturen .card input')) return; // niet overschrijven tijdens typen
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
  $('#fa-gd-stand').textContent = gd.gekoppeld ? 'Gekoppeld' + (gd.gebruiker ? ' met ' + gd.gebruiker : '') + '. Facturen komen in de map Facturen in je Drive.' : '';
  if (!gd.gekoppeld && gd.clientId && !$('#fa-gd-id').value) $('#fa-gd-id').value = gd.clientId;
  $('#fa-gd-geheim').placeholder = gd.heeftGeheim ? 'Client secret (ongewijzigd)' : 'Client secret';
  $('#fa-map').textContent = faSt.map ? 'Kopieën komen in je backup-map, in de map Facturen.' : 'Tip: kies een backup-map (Instellingen), dan komen de facturen ook in de map Facturen.';
}
/* Overzicht: deze maand per leverancier, en per maand wat er binnenkwam */
function faMaandRender(){
  const l = faSt.leveranciers, nu = Date.now(), dezeMaand = faMaandSleutel(nu);
  $('#fa-maand').style.display = l.length ? 'block' : 'none';
  $('#fa-overzicht').style.display = l.length ? 'block' : 'none';
  if (!l.length) return;
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
    ['Ophalen en meekijken', () => faNu(id, true)],
    ['Facturen bekijken' + (l.aantal ? ' (' + l.aantal + ')' : ''), () => faBekijk(l)],
    ['Geschiedenis', () => faGeschiedenis(l)],
    ['Instellingen', () => flOpen(l)],
    ['Verwijderen…', () => askConfirm(l.naam + ' verwijderen?', 'De instellingen en de facturen in de app gaan weg. Kopieën in je backup-map en in Eigen Cloud blijven staan.', 'Verwijderen', () => { const e = Android.facturenVerwijder(id); if (e) toast(e); faRender(); })]];
  openSheet(l.naam, busy ? 'Er wordt nu opgehaald: even wachten' : faStatus(l), busy ? acts.slice(2) : acts);
}
function faNu(id, meekijken){
  const e = Android.facturenNu(id, !!meekijken);
  if (e) { toast(e); return; }
  if (!meekijken) { faSt.bezig = id; faSt.voortgang = 'Starten…'; faBezig(); toast('Bezig met ophalen; dat kan een paar minuten duren'); }
}
window.onFacturenKlaar = function(r){
  toast((r.ok ? '✓ ' : '') + (r.naam ? r.naam + ': ' : '') + (r.msg || ''));
  if (r.ok && (r.fouten || []).length) setTimeout(() => toast('Niet alles afgeleverd: ' + r.fouten[0]), 2800);
  if (!r.ok && r.inlogNodig) setTimeout(() => askConfirm('Inloggen nodig', (r.msg || '') + '\n\nKijk één keer mee: dan log je zelf in en gaat de app daarna verder.', 'Meekijken', () => faNu(r.id, true)), 600);
  if (current === 'facturen') faRender();
};
function faBekijk(l){
  const lijst = rdJson(Android.facturenLijst(l.id), []);
  if (!lijst.length) { toast('Nog geen facturen in de app'); return; }
  openSheet(l.naam, lijst.length + (lijst.length === 1 ? ' factuur' : ' facturen'), lijst.slice(0, 40).map(f => [f.naam + ' · ' + faDatum(f.t), () => Android.facturenOpen(l.id, f.naam)]));
}
function faNieuw(){
  const s = faSt.sjablonen || [];
  openSheet('Leverancier toevoegen', 'Kies een sjabloon; alles is daarna aan te passen', s.map(x => [(x.naam || 'Andere website') + (x.omschrijving ? ' · ' + x.omschrijving : ''), () => flOpen(Object.assign({}, x, { id: '' }), true)]));
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
  ['gebruiker', 'wachtwoord', 'code', 'knop'].forEach(k => $('#fl-v-' + k).value = v[k] || '');
  $('#fl-geavanceerd').open = false; $('#fl-msg').textContent = '';
  flBron(); flPlan();
  show('faclev');
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
    id: faEdit && !faEdit._nieuw ? faEdit.id : '', sjabloon: faEdit ? faEdit.sjabloon || 'anders' : 'anders',
    naam: $('#fl-naam').value.trim(), startUrl: $('#fl-start').value.trim(), gebruiker: $('#fl-user').value.trim(), wachtwoord: $('#fl-pw').value,
    facturenUrl: $('#fl-facturl').value.trim(), menu: $('#fl-menu').value.trim(), knoppen: $('#fl-knoppen').value.trim(), max: Number($('#fl-max').value) || 12,
    code: { bron: $('#fl-bron').value, afzender: $('#fl-afz').value.trim(), patroon: $('#fl-pat').value.trim() },
    schema: $('#fl-schema').value, dag: Number($('#fl-dag').value) || 5, weekdag: Number($('#fl-wd').value) || 1, tijd: $('#fl-tijd').value || '08:00',
    opnieuw: Number($('#fl-opnieuw').value) || 3, wifi: $('#fl-wifi').checked, meldNieuw: $('#fl-meldnieuw').checked, meldFout: $('#fl-meldfout').checked, map: $('#fl-map').checked, lokaal: $('#fl-lok').checked, drive: $('#fl-gd').checked, eigenCloud: $('#fl-ec').checked, submap: $('#fl-sub').value.trim(),
    velden: { gebruiker: $('#fl-v-gebruiker').value.trim(), wachtwoord: $('#fl-v-wachtwoord').value.trim(), code: $('#fl-v-code').value.trim(), knop: $('#fl-v-knop').value.trim() }
  };
  for (const [p, wat] of [[o.menu, 'De tekst van de link naar de facturen'], [o.knoppen, 'Het herkennen van downloadknoppen']]) {
    try { new RegExp(p, 'i'); } catch (e) { $('#fl-msg').textContent = wat + ' is geen geldig zoekpatroon.'; $('#fl-geavanceerd').open = true; return; }
  }
  if (o.lokaal && !faSt.lokaal) { $('#fl-msg').textContent = 'Kies eerst een map op je telefoon (op het scherm Facturen), of zet "In een map op je telefoon" uit.'; return; }
  if (o.drive && !(faSt.drive || {}).gekoppeld) { $('#fl-msg').textContent = 'Koppel eerst Google Drive (op het scherm Facturen), of zet "In Google Drive" uit.'; return; }
  const ec = faSt.ec || {};
  if (o.eigenCloud && !(ec.url && ec.heeftWachtwoord)) { $('#fl-msg').textContent = 'Koppel eerst Eigen Cloud (op het scherm Facturen), of zet "Naar je mail in Eigen Cloud" uit.'; return; }
  if (o.code.bron === 'eigencloud' && !(ec.url && ec.heeftWachtwoord)) { $('#fl-msg').textContent = 'Koppel eerst Eigen Cloud om de code uit je mail te lezen.'; return; }
  const r = rdJson(Android.facturenBewaar(JSON.stringify(o)), { error: 'Bewaren lukt niet' });
  $('#fl-pw').value = '';
  if (r.error) { $('#fl-msg').textContent = r.error; return; }
  const nieuw = faEdit && faEdit._nieuw;
  goBack(); faRender();
  if (o.code.bron === 'sms' && !faSt.sms) Android.smsRequestPermission();
  if (nieuw) setTimeout(() => askConfirm('Nu ophalen?', 'De eerste keer is meekijken handig: je ziet of de app de inlogvelden en downloadknoppen vindt, en je kunt zelf bijspringen.', 'Meekijken', () => faNu(r.id, true)), 300);
  else toast('✓ Bewaard');
}
/* Map op de telefoon en Google Drive */
window.onFacturenMap = function(fout){
  if (fout && fout !== 'geen') toast(fout); else if (!fout) toast('✓ Map gekozen');
  if (current === 'facturen') faRender();
};
function faGdKoppel(){
  $('#fa-gd-msg').textContent = '';
  const id = $('#fa-gd-id').value.trim(), geheim = $('#fa-gd-geheim').value.trim();
  if (!/^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/.test(id)) { $('#fa-gd-msg').textContent = 'Een Client ID van Google eindigt op .apps.googleusercontent.com. Kopieer hem uit je Google-project.'; return; }
  const gd = faSt.drive || {};
  if (!geheim && (!gd.heeftGeheim || id !== gd.clientId)) { $('#fa-gd-msg').textContent = 'Vul ook het Client secret in' + (gd.heeftGeheim ? ' (nieuwe Client ID = ook een nieuw secret).' : '.'); return; }
  const e = Android.facturenDriveKoppel(id, geheim);
  $('#fa-gd-geheim').value = '';
  if (e) { $('#fa-gd-msg').textContent = e; return; }
  toast('Log in bij Google en geef toestemming; kom daarna terug');
}
window.onFacturenDrive = function(r){
  if (r.error) { $('#fa-gd-msg').textContent = r.error; toast(r.error); }
  else toast('✓ Google Drive gekoppeld' + (r.naam ? ' (' + r.naam + ')' : ''));
  if (current === 'facturen') faRender();
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
