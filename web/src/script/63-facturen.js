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
function faStatus(l){
  const t = l.toestand || {};
  if (faSt.bezig === l.id) return faSt.voortgang || 'Bezig…';
  if (!l.heeftWachtwoord && l.gebruiker) return 'Geen wachtwoord: vul het in of kijk mee om zelf in te loggen';
  if (t.lastTry && !t.ok) return 'Laatste poging (' + faDatum(t.lastTry) + '): ' + (t.msg || 'mislukt');
  if (t.lastOk) return 'Opgehaald ' + faDatum(t.lastOk) + ' · ' + (t.msg || '') + (l.aantal ? ' · ' + l.aantal + ' in de app' : '');
  return 'Nog niet opgehaald';
}
function faRender(geenLaad){
  if (!geenLaad) faLaad();
  faBezig();
  const schema = { dag: 'elke dag', week: 'elke week', maand: 'elke maand', uit: 'alleen met de hand' };
  $('#fa-lijst').innerHTML = faSt.leveranciers.map(l => {
    const t = l.toestand || {}, fout = (t.lastTry && !t.ok) || (!l.heeftWachtwoord && l.gebruiker);
    return '<div class="srow"><button class="sbtn" data-id="' + esc(l.id) + '" onclick="faOpen(this.dataset.id)"><span class="vpic" aria-hidden="true">' + uiIcon('document') + '</span>' +
      '<span class="stxt"><b>' + esc(l.naam) + '</b><small' + (fout ? ' class="bad"' : '') + '>' + esc(faStatus(l)) + '</small><small>' + esc('Vanzelf: ' + (schema[l.schema] || l.schema)) + '</small></span></button></div>';
  }).join('') || '<p class="note" style="padding:14px">Nog geen leveranciers. Tik op <b>Leverancier toevoegen</b>.</p>';
  $('#fa-nieuw').disabled = !!faSt.onleesbaar;
  const ec = faSt.ec || {};
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#s-facturen .card input')) return; // niet overschrijven tijdens typen
  $('#fa-ec-url').value = ec.url || ''; $('#fa-ec-user').value = ec.gebruiker || ''; $('#fa-ec-adres').value = ec.adres || '';
  $('#fa-ec-pw').value = ''; $('#fa-ec-pw').placeholder = ec.heeftWachtwoord ? 'Ongewijzigd' : '';
  $('#fa-ec-stand').textContent = ec.url && ec.heeftWachtwoord ? 'Gekoppeld met ' + ec.url.replace(/^https:\/\//, '') + '. Facturen gaan als mail met de PDF naar ' + (ec.adres || ec.gebruiker) + '.'
    : 'Niet gekoppeld. Koppel Eigen Cloud om facturen in je eigen mail te krijgen, of om de inlogcode uit je mail te lezen.';
  $('#fa-map').textContent = faSt.map ? 'Kopieën komen in je backup-map, in de map Facturen.' : 'Tip: kies een backup-map (Instellingen), dan komen de facturen ook in de map Facturen.';
}
function faOpen(id){
  const l = faSt.leveranciers.find(x => x.id === id); if (!l) return;
  const busy = !!faSt.bezig;
  const acts = [
    ['Nu ophalen', () => faNu(id, false)],
    ['Ophalen en meekijken', () => faNu(id, true)],
    ['Facturen bekijken' + (l.aantal ? ' (' + l.aantal + ')' : ''), () => faBekijk(l)],
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
  $('#fl-schema').value = l.schema || 'week'; $('#fl-map').checked = l.map !== false; $('#fl-ec').checked = !!l.eigenCloud;
  $('#fl-facturl').value = l.facturenUrl || ''; $('#fl-menu').value = l.menu || ''; $('#fl-knoppen').value = l.knoppen || '';
  $('#fl-max').value = l.max || 12; $('#fl-sub').value = l.submap || '';
  ['gebruiker', 'wachtwoord', 'code', 'knop'].forEach(k => $('#fl-v-' + k).value = v[k] || '');
  $('#fl-geavanceerd').open = false; $('#fl-msg').textContent = '';
  flBron();
  show('faclev');
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
    schema: $('#fl-schema').value, map: $('#fl-map').checked, eigenCloud: $('#fl-ec').checked, submap: $('#fl-sub').value.trim(),
    velden: { gebruiker: $('#fl-v-gebruiker').value.trim(), wachtwoord: $('#fl-v-wachtwoord').value.trim(), code: $('#fl-v-code').value.trim(), knop: $('#fl-v-knop').value.trim() }
  };
  for (const [p, wat] of [[o.menu, 'De tekst van de link naar de facturen'], [o.knoppen, 'Het herkennen van downloadknoppen']]) {
    try { new RegExp(p, 'i'); } catch (e) { $('#fl-msg').textContent = wat + ' is geen geldig zoekpatroon.'; $('#fl-geavanceerd').open = true; return; }
  }
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
