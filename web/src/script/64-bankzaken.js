/* Bankzaken: rapportages over je inkomsten en uitgaven, volledig op de telefoon.
 * De gegevens komen ontsleuteld van Bank.java (na vingerafdruk of pincode) en staan alleen in het geheugen zolang het
 * scherm open is: weg uit Bankzaken, de app uit beeld of op slot = alles uit het geheugen en van het scherm.
 * Alle tekst uit afschriften (namen, omschrijvingen) is van buiten: altijd met esc() tonen, nooit als HTML. */

// ---------- categorieën ----------
// soort: uit (uitgave), in (inkomsten), sparen (apart, telt niet als uitgave), neutraal (eigen rekeningen)
const BK_CATS = [
  ['Boodschappen', 'uit'], ['Uit eten & drinken', 'uit'], ['Wonen', 'uit'], ['Energie & water', 'uit'], ['Telefoon & internet', 'uit'],
  ['Verzekeringen', 'uit'], ['Zorg', 'uit'], ['Vervoer', 'uit'], ['Abonnementen', 'uit'], ['Winkelen', 'uit'], ['Kleding', 'uit'],
  ['Persoonlijke verzorging', 'uit'], ['Vrije tijd & sport', 'uit'], ['Reizen & vakantie', 'uit'], ['Belastingen & overheid', 'uit'],
  ['Kinderen & onderwijs', 'uit'], ['Huisdieren', 'uit'], ['Giften & goede doelen', 'uit'], ['Contant geld', 'uit'], ['Bankkosten', 'uit'],
  ['Overig', 'uit'],
  ['Salaris', 'in'], ['Toeslagen & uitkeringen', 'in'], ['Terugbetalingen', 'in'], ['Rente & dividend', 'in'], ['Overige inkomsten', 'in'],
  ['Sparen & beleggen', 'sparen'], ['Eigen rekeningen', 'neutraal']];

// [patroon, categorie, teken] — teken '+' alleen bijschrijvingen, '-' alleen afschrijvingen, '' allebei. Volgorde telt.
const BK_REGELS = [
  ['salaris|\\bloon\\b|vakantiegeld|vakantietoeslag|13e maand|eindejaarsuitkering|payroll|loonstrook', 'Salaris', '+'],
  ['toeslag|kinderbijslag|\\bsvb\\b|\\buwv\\b|uitkering|\\baow\\b|pensioen|\\babp\\b|pfzw|\\bpmt\\b|\\bpme\\b|bijstand|studiefinanciering|\\bduo\\b|belastingdienst|teruggaaf', 'Toeslagen & uitkeringen', '+'],
  ['rente|dividend|interest', 'Rente & dividend', '+'],
  ['terugbetaling|retour|refund|restitutie|terugstorting|creditering|terugboeking|bol\\.com|coolblue|zalando|amazon|tikkie|betaalverzoek', 'Terugbetalingen', '+'],
  ['spaarrekening|\\bsparen\\b|spaardoel|spaarpot|degiro|flatex|meesman|brand new day|\\bbux\\b|trade republic|\\bpeaks\\b|bitvavo|coinbase|binance|saxo|\\blynx\\b|beleggen|vermogensbeheer', 'Sparen & beleggen', ''],
  ['albert heijn|\\bah to go\\b|\\bah \\d|jumbo|\\blidl\\b|\\baldi\\b|plus supermarkt|\\bplus [a-z]|\\bdirk\\b|vomar|hoogvliet|\\bcoop\\b|\\bspar\\b|dekamarkt|poiesz|picnic|crisp|ekoplaza|marqt|nettorama|boni\\b|jan linders|\\bodin\\b|\\bflink\\b|supermarkt|\\bbakker|\\bslager|\\btoko\\b|groente|kaashandel|versmarkt', 'Boodschappen', '-'],
  ['restaurant|\\bcaf[eé]\\b|eetcaf|mcdonald|burger king|\\bkfc\\b|subway|domino|new york pizza|thuisbezorgd|uber ?eats|deliveroo|starbucks|bagels|brasserie|lunchroom|\\bbistro|\\bgrill|sushi|pizzeria|\\bfebo\\b|smullers|la place|\\bbar\\b|koffie|coffee|bakkerij.*caf|snackbar|friet|shoarma|poke|wok', 'Uit eten & drinken', '-'],
  ['\\bhuur\\b|hypotheek|woonstichting|woonbron|havensteder|vestia|ymere|eigen haard|stadgenoot|de alliantie|mitros|portaal|wooncorporatie|\\bvve\\b|servicekosten', 'Wonen', '-'],
  ['vattenfall|eneco|essent|greenchoice|energiedirect|budget energie|vandebron|oxxio|engie|pure energie|frank energie|tibber|zonneplan|evides|vitens|brabant water|\\bpwn\\b|waternet|dunea|oasen|\\bwml\\b|waterbedrijf|stedin|liander|enexis', 'Energie & water', '-'],
  ['\\bkpn\\b|t-mobile|odido|vodafone|ziggo|tele2|simpel|\\bben\\b|lebara|lycamobile|youfone|hollandsnieuwe|delta fiber|caiway|budget mobiel|solcon', 'Telefoon & internet', '-'],
  ['zilveren kruis|\\bcz\\b|\\bvgz\\b|menzis|\\bdsw\\b|zorg en zekerheid|\\bonvz\\b|zorgverzeker|apotheek|huisarts|tandarts|fysio|ziekenhuis|optiek|specsavers|hans anders|pearle|eigen risico', 'Zorg', '-'],
  ['verzekering|centraal beheer|interpolis|nationale nederlanden|a\\.s\\.r|\\basr\\b|\\bohra\\b|univ[eé]|\\bfbto\\b|inshared|allianz|aegon|ditzo|reaal|achmea|\\bnn\\b', 'Verzekeringen', '-'],
  ['\\bshell\\b|\\bbp\\b|\\besso\\b|\\btango\\b|\\btinq\\b|totalenergies|\\btotal\\b|texaco|\\bgulf\\b|\\bargos\\b|\\bq8\\b|\\bavia\\b|tamoil|tankstation|parkeren|parking|easypark|yellowbrick|parkmobile|q-park|\\banwb\\b|\\bapk\\b|garage|kwik-fit|\\bns\\b|ns groep|ns reizigers|\\bret\\b|\\bgvb\\b|\\bhtm\\b|arriva|connexxion|qbuzz|keolis|ov-chip|translink|\\buber\\b|\\bbolt\\b|taxi|greenwheels|swapfiets|felyx|go sharing|donkey republic|ov-fiets|laadpaal|shell recharge|fastned|allego|vandebron laad', 'Vervoer', '-'],
  ['netflix|spotify|disney|videoland|\\bhbo\\b|\\bmax\\b|prime video|amazon prime|apple\\.com|itunes|google play|youtube|patreon|\\bnrc\\b|volkskrant|telegraaf|\\bparool\\b|\\btrouw\\b|blendle|storytel|audible|kindle|microsoft|office 365|dropbox|icloud|chatgpt|openai|anthropic|github|adobe|canva|nintendo|playstation|xbox|steam|abonnement', 'Abonnementen', '-'],
  ['h&m|h & m|\\bzara\\b|c&a|c & a|we fashion|uniqlo|zalando|about you|jack & jones|\\bnike\\b|adidas|vanharen|schoenen|scapino|the sting|costes|sissy-boy|mango|esprit|g-star|wibra|kleding|primark', 'Kleding', '-'],
  ['kruidvat|\\betos\\b|trekpleister|\\bdA\\b drogist|drogist|kapper|barber|kapsalon|schoonheid|nagelstudio|douglas|ici paris', 'Persoonlijke verzorging', '-'],
  ['basic-fit|sportcity|anytime fitness|fit for free|trainmore|sportschool|zwembad|bioscoop|path[eé]|\\bvue\\b|kinepolis|museum|efteling|walibi|artis|diergaarde|blijdorp|ticketmaster|eventim|ticketswap|paylogic|concert|theater|ahoy|bowling|escape room|\\bgolf|tennis|sportvereniging|vereniging|contributie', 'Vrije tijd & sport', '-'],
  ['booking\\.com|airbnb|\\bklm\\b|transavia|easyjet|ryanair|vueling|corendon|\\btui\\b|sunweb|expedia|hotels\\.com|\\bhotel|camping|center parcs|landal|roompot|eurostar|flixbus|schiphol|airport|vliegveld', 'Reizen & vakantie', '-'],
  ['belastingdienst|gemeente|waterschap|\\bcjib\\b|\\brdw\\b|\\bcak\\b|belastingsamenwerking|\\bbsgr\\b|\\bgbt\\b|\\bsvhw\\b|\\bbghu\\b|\\brbg\\b|leges|rijksoverheid|kadaster|notaris|motorrijtuigenbelasting', 'Belastingen & overheid', '-'],
  ['kinderopvang|\\bbso\\b|school|\\bduo\\b|studielink|universiteit|hogeschool|opleiding|cursus|ouderbijdrage|partou|kinderdagverblijf|smallsteps|kids first|kinderen', 'Kinderen & onderwijs', '-'],
  ['dierenarts|dierenkliniek|pets place|petsplace|jumper|zooplus|discus|dierenspeciaal|tom ?& ?co|dierenwinkel', 'Huisdieren', '-'],
  ['unicef|\\bkwf\\b|rode kruis|artsen zonder grenzen|greenpeace|\\bwnf\\b|natuurmonumenten|amnesty|hartstichting|\\bkika\\b|alzheimer|giro555|goede doel|donatie|gift', 'Giften & goede doelen', '-'],
  ['geldautomaat|geldmaat|\\bgea\\b|opname|\\bcash\\b|\\batm\\b', 'Contant geld', '-'],
  ['betaalpakket|basispakket|kosten (betaal)?rekening|bankkosten|transactiekosten|creditcard|pakketkosten|rente debet|debetrente|kosten gebruik|ing oranje pakket|rabo .*pakket', 'Bankkosten', '-'],
  ['bol\\.com|coolblue|amazon|mediamarkt|\\baction\\b|\\bhema\\b|blokker|zeeman|ikea|gamma|praxis|karwei|hornbach|intratuin|leen bakker|kwantum|xenos|s[øo]strene|bijenkorf|wehkamp|aliexpress|temu|shein|marktplaats|vinted|decathlon|alternate|megekko|lucardi|\\bbruna\\b|\\bako\\b|readshop|boekhandel|\\bapple store|webshop|winkel', 'Winkelen', '-']
].map(([p, c, t]) => [new RegExp(p, 'i'), c, t]);

let bk = { st: null, store: null, tab: 'overzicht', lijstN: 100, catCache: new Map(), eigen: new Set(), aiBezig: false, aiDoel: '', aiTekst: '', voorstellen: null, aanraak: 0, tmr: null };

const bkVandaag = () => window.BK_VANDAAG || (() => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); })();
function bkEuro(c, teken){
  const v = Math.abs(c) / 100;
  const s = '€ ' + v.toLocaleString('nl-NL', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (teken && c > 0 ? '+ ' : '') + (c < 0 ? '− ' : '') + s;
}
const bkEuroKort = c => '€ ' + Math.round(Math.abs(c) / 100).toLocaleString('nl-NL');
const bkDatum = (d, opt) => new Date(d + 'T12:00:00').toLocaleDateString('nl-NL', opt || { day: 'numeric', month: 'short', year: 'numeric' });
const BK_MAANDEN = ['jan', 'feb', 'mrt', 'apr', 'mei', 'jun', 'jul', 'aug', 'sep', 'okt', 'nov', 'dec'];
const bkMaandNaam = ym => BK_MAANDEN[Number(ym.slice(5, 7)) - 1] + ' ' + ym.slice(2, 4);

function bkCatSoort(c){ const x = BK_CATS.find(k => k[0] === c); if (x) return x[1]; const e = ((bk.store && bk.store.cats) || []).find(k => k.naam === c); return e ? e.soort : 'uit'; }
function bkAlleCats(){ return BK_CATS.map(c => c[0]).concat(((bk.store && bk.store.cats) || []).map(c => c.naam)); }
/** Naam zonder winkelnummers e.d., om dezelfde winkel samen te nemen. */
function bkNorm(s){ return String(s || '').toLowerCase().replace(/\b(nr|pas)\s*\d+\b/g, '').replace(/[0-9]{2,}/g, '').replace(/[^a-zÀ-ɏ&+ ]+/g, ' ').replace(/\s+/g, ' ').trim(); }
function bkSleutel(t){ return bkNorm(t.cp || t.ci) || '?'; }

/** De categorie van een boeking: zelf gekozen > eigen rekening > eigen regel > ingebouwde regel > overig. */
function bkCat(t){
  const c = bk.catCache.get(t.id); if (c) return c;
  let r = '';
  if (t.c) r = t.c;
  else if (t.ci && t.ci !== t.acc && bk.eigen.has(t.ci)) r = 'Eigen rekeningen';
  else {
    const tekst = ((t.cp || '') + ' ' + (t.ds || '')).toLowerCase(), norm = bkNorm(tekst);
    for (const g of (bk.store.rules || [])) if (g.p && (tekst.includes(g.p) || norm.includes(g.p)) && (!g.t || (g.t === '+') === (t.a > 0))) { r = g.cat; break; }
    if (!r) for (const [re, cat, teken] of BK_REGELS) if ((!teken || (teken === '+') === (t.a > 0)) && re.test(tekst)) { r = cat; break; }
    if (!r) r = t.a > 0 ? 'Overige inkomsten' : 'Overig';
  }
  bk.catCache.set(t.id, r);
  return r;
}
function bkHerbereken(){
  bk.catCache.clear();
  bk.eigen = new Set();
  if (!bk.store) return;
  for (const t of bk.store.tx) if (t.acc && t.acc !== 'onbekend') bk.eigen.add(t.acc);
  for (const e of (bk.store.eigen || [])) bk.eigen.add(e.iban);
}

// ---------- periode ----------
function bkPeriodeVan(){
  const p = $('#bk-periode').value, v = bkVandaag(), y = Number(v.slice(0, 4)), m = Number(v.slice(5, 7));
  const ym = (yy, mm) => { while (mm < 1) { mm += 12; yy--; } return yy + '-' + String(mm).padStart(2, '0'); };
  const eind = (s) => { const d = new Date(Number(s.slice(0, 4)), Number(s.slice(5, 7)), 0); return s + '-' + String(d.getDate()).padStart(2, '0'); };
  if (p === 'm0') return [ym(y, m) + '-01', v, 'Deze maand'];
  if (p === 'm1') return [ym(y, m - 1) + '-01', eind(ym(y, m - 1)), 'Vorige maand'];
  if (p === 'm3') return [ym(y, m - 2) + '-01', v, 'Laatste 3 maanden'];
  if (p === '12m') return [ym(y, m - 11) + '-01', v, 'Laatste 12 maanden'];
  if (p === 'y0') return [y + '-01-01', v, 'Dit jaar'];
  if (p === 'y1') return [(y - 1) + '-01-01', (y - 1) + '-12-31', 'Vorig jaar'];
  if (p === 'eigen') { const a = $('#bk-van').value || '1990-01-01', b = $('#bk-tot').value || v; return a <= b ? [a, b, 'Gekozen periode'] : [b, a, 'Gekozen periode']; }
  const tx = bk.store ? bk.store.tx : [];
  let lo = v, hi = '1990-01-01'; for (const t of tx) { if (t.d < lo) lo = t.d; if (t.d > hi) hi = t.d; }
  return [lo, hi < lo ? lo : hi, 'Alles'];
}
/** De periode ervoor, even lang (voor de vergelijking). */
function bkVorige(van, tot){
  const f = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const a = new Date(van + 'T12:00:00'), b = new Date(tot + 'T12:00:00');
  if (van.slice(8) === '01') {
    // Begint op de 1e: dezelfde stukken maanden ervoor (1 sep–3 nov ↔ 1 jun–3 aug), dag afgekapt op de maandlengte
    const mnd = (b.getFullYear() - a.getFullYear()) * 12 + b.getMonth() - a.getMonth() + 1;
    const terug = (d, m) => { const x = new Date(d.getFullYear(), d.getMonth() - m, 1, 12); const laatste = new Date(x.getFullYear(), x.getMonth() + 1, 0).getDate(); x.setDate(Math.min(d.getDate(), laatste)); return x; };
    return [f(terug(a, mnd)), f(terug(b, mnd))];
  }
  const dagen = Math.round((b - a) / 864e5) + 1;
  return [f(new Date(a.getTime() - dagen * 864e5)), f(new Date(a.getTime() - 864e5))];
}
function bkRekening(){ const s = $('#bk-rek'); return s && s.value ? s.value : 'alle'; }
function bkSelectie(van, tot){
  const r = bkRekening();
  return bk.store.tx.filter(t => t.d >= van && t.d <= tot && (r === 'alle' || t.acc === r));
}

// ---------- rekenen ----------
function bkTotalen(lijst){
  const o = { in: 0, uit: 0, sparen: 0, cats: new Map(), n: 0 };
  for (const t of lijst) {
    const c = bkCat(t), s = bkCatSoort(c);
    if (s === 'neutraal') continue;
    o.n++;
    const x = o.cats.get(c) || { som: 0, n: 0, soort: s }; x.som += t.a; x.n++; o.cats.set(c, x);
    if (s === 'in') o.in += t.a; else if (s === 'sparen') o.sparen -= t.a; else o.uit -= t.a;
  }
  o.netto = o.in - o.uit - o.sparen;
  return o;
}
function bkPerMaand(lijst){
  const m = new Map();
  for (const t of lijst) {
    const s = bkCatSoort(bkCat(t)); if (s === 'neutraal') continue;
    const k = t.d.slice(0, 7), x = m.get(k) || { in: 0, uit: 0, sparen: 0 };
    if (s === 'in') x.in += t.a; else if (s === 'sparen') x.sparen -= t.a; else x.uit -= t.a;
    m.set(k, x);
  }
  return [...m.entries()].sort((a, b) => a[0] < b[0] ? -1 : 1);
}
function bkTegenpartijen(lijst){
  const m = new Map();
  for (const t of lijst) {
    if (t.a >= 0 || bkCatSoort(bkCat(t)) !== 'uit') continue;
    const k = bkSleutel(t), x = m.get(k) || { naam: t.cp || t.ci, som: 0, n: 0, cat: bkCat(t) };
    x.som -= t.a; x.n++; m.set(k, x);
  }
  return [...m.values()].sort((a, b) => b.som - a.som);
}
/** Vaste lasten en abonnementen: dezelfde tegenpartij, regelmatig (week/maand/kwartaal/jaar), ongeveer hetzelfde bedrag. */
function bkTerugkerend(){
  const groep = new Map(), r = bkRekening();
  for (const t of bk.store.tx) {
    if (t.a >= 0 || (r !== 'alle' && t.acc !== r)) continue;
    const s = bkCatSoort(bkCat(t)); if (s === 'neutraal') continue;
    const k = bkSleutel(t); if (!groep.has(k)) groep.set(k, []); groep.get(k).push(t);
  }
  const uit = [], vandaag = new Date(bkVandaag() + 'T12:00:00');
  const med = a => { const s = a.slice().sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
  for (const [, l] of groep) {
    if (l.length < 3) continue;
    l.sort((a, b) => a.d < b.d ? -1 : 1);
    const d = []; for (let i = 1; i < l.length; i++) d.push((new Date(l[i].d) - new Date(l[i - 1].d)) / 864e5);
    const iv = med(d);
    const soort = iv >= 6 && iv <= 8 ? ['wekelijks', 52 / 12] : iv >= 25 && iv <= 35 ? ['maandelijks', 1] : iv >= 85 && iv <= 95 ? ['per kwartaal', 1 / 3] : iv >= 350 && iv <= 380 ? ['jaarlijks', 1 / 12] : null;
    if (!soort) continue;
    const bedragen = l.map(t => -t.a), m = med(bedragen);
    if (bedragen.filter(b => Math.abs(b - m) <= m * 0.15 + 100).length / bedragen.length < 0.6) continue;
    const laatst = l[l.length - 1], volgende = new Date(new Date(laatst.d + 'T12:00:00').getTime() + iv * 864e5);
    const gestopt = vandaag - new Date(laatst.d + 'T12:00:00') > iv * 1.6 * 864e5;
    uit.push({ naam: laatst.cp || laatst.ci, cat: bkCat(laatst), soort: soort[0], bedrag: m, perMaand: Math.round(m * soort[1]), laatst: laatst.d, volgende, gestopt, n: l.length });
  }
  return uit.sort((a, b) => (a.gestopt - b.gestopt) || b.perMaand - a.perMaand);
}
function bkSaldoReeks(van, tot){
  // Laatste bekende saldo per rekening per dag; samen opgeteld (alleen rekeningen met saldo in het afschrift)
  const r = bkRekening(), per = new Map();
  for (const t of bk.store.tx) { if (t.b == null || (r !== 'alle' && t.acc !== r)) continue; if (!per.has(t.acc)) per.set(t.acc, []); per.get(t.acc).push(t); }
  if (!per.size) return [];
  const dagen = new Set();
  for (const [, l] of per) { l.sort((a, b) => a.d < b.d ? -1 : a.d > b.d ? 1 : 0); for (const t of l) if (t.d >= van && t.d <= tot) dagen.add(t.d); }
  const reeks = [];
  for (const d of [...dagen].sort()) {
    let som = 0, ok = false;
    for (const [, l] of per) { let s = null; for (const t of l) { if (t.d <= d) s = t.b; else break; } if (s != null) { som += s; ok = true; } }
    if (ok) reeks.push([d, som]);
  }
  return reeks;
}

// ---------- openen, op slot, opslaan ----------
function enterBank(){
  try { Android.bankSecure(true); } catch (e) { }
  bkToestand();
  if (!bk.st || !bk.st.open) return;
  bkLaad();
}
function bkToestand(){
  let s; try { s = JSON.parse(Android.bankState()); } catch (e) { s = { open: false }; }
  bk.st = s;
  const open = !!s.open && !s.kwijt; // sleutel kwijt: alleen wissen kan nog (na ontgrendelen)
  $('#bk-open-knop').hidden = !!s.open && !!s.kwijt;
  $('#bk-dicht').hidden = open; $('#bk-los').hidden = !open;
  $('#bk-kwijt').hidden = !s.kwijt;
  if (s.kwijt) $('#bk-kwijt-txt').textContent = 'De sleutel van Bankzaken bestaat niet meer (bijvoorbeeld omdat de schermvergrendeling van je telefoon uit is gezet). Je bankgegevens zijn daardoor niet meer te openen.';
  if (!s.slot && !open) $('#bk-msg').textContent = 'Stel eerst een schermvergrendeling in op je telefoon (pincode, patroon of vingerafdruk). Zonder kan Bankzaken je gegevens niet beveiligen.';
  if (!open) bkVergeet();
  return open;
}
function bkOntgrendel(){ $('#bk-msg').textContent = ''; Android.bankUnlock(); }
window.onBank = function(r){
  if (r !== true) { $('#bk-msg').textContent = String(r || 'Niet ontgrendeld'); return; }
  if (current !== 'bank') { Android.bankLock(); return; }
  if (bkToestand()) bkLaad();
};
function bkLaad(){
  let d; try { d = JSON.parse(Android.bankLoad()); } catch (e) { d = { error: 'Openen lukt niet' }; }
  if (d.locked || d.error) {
    if (d.error) $('#bk-msg').textContent = d.error;
    // Sleutel ongeldig: niet op slot doen, dan kan er nog gewist worden
    if (!(d.error && /niet meer te openen/.test(d.error))) Android.bankLock();
    bkToestand(); return;
  }
  bk.store = d.store || { v: 1, tx: [], imports: [] };
  for (const k of ['tx', 'imports', 'rules', 'cats', 'eigen']) if (!Array.isArray(bk.store[k])) bk.store[k] = [];
  if (!bk.store.budget || typeof bk.store.budget !== 'object') bk.store.budget = {};
  if (!bk.store.namen || typeof bk.store.namen !== 'object') bk.store.namen = {};
  bkHerbereken();
  bkRekeningen();
  bkTijdBewaken();
  bkRender();
  // Automatisch opgehaald terwijl het scherm weg was
  let u = ''; try { u = Android.bankKoppelUitkomst(); } catch (e) { }
  if (u) bkKoppelKlaar(u);
}
/** Alles uit het geheugen en van het scherm. */
function bkVergeet(){
  bk.store = null; bk.catCache.clear(); bk.eigen = new Set(); bk.voorstellen = null; bk.aiTekst = ''; bk.lijstN = 100;
  for (const id of ['#bk-overzicht', '#bk-lijst', '#bk-regels', '#bk-budgetten', '#bk-rekeningen', '#bk-imports', '#bk-ai-cat-uit', '#bk-ai-uit']) { const e = $(id); if (e) e.replaceChildren(); }
  for (const id of ['#bk-rek', '#bk-cat']) { const e = $(id); if (e) e.replaceChildren(); }
  for (const id of ['#bk-lijst-telling', '#bk-periode-txt', '#bk-ai-status', '#bk-zonder', '#bk-tijd', '#bk-uitleg']) { const e = $(id); if (e) e.textContent = ''; }
  // Een open menu of venster met gegevens van een boeking dicht en leeg
  if (bk.venster) { bk.venster = false; try { closeSheet(); } catch (e) { } try { if ($('#modal.on')) closeModal(null); } catch (e) { } const s = $('#sheet-sub'); if (s) s.textContent = ''; const t = $('#sheet-title'); if (t) t.textContent = ''; }
  const zoek = $('#bk-zoek'); if (zoek) zoek.value = '';
  const ai = $('#bk-ai-in'); if (ai) ai.value = '';
  const k = $('#bk-ai-uit-kaart'); if (k) k.hidden = true;
  clearInterval(bk.tmr); bk.tmr = null;
}
function bkSluit(){ Android.bankLock(); bkToestand(); }
function bkSave(){
  if (!bk.store) return false;
  const r = Android.bankSave(JSON.stringify(bk.store));
  if (r) {
    if (/intussen iets ge/.test(r)) { askConfirm('Bijgewerkt', r, 'OK', () => {}); bkLaad(); return false; } // een import was eerder klaar
    toast(r); if (/op slot|Ontgrendel/i.test(r)) { Android.bankLock(); bkToestand(); } return false;
  }
  bk.store.rev = (bk.store.rev || 0) + 1;
  return true;
}
/** Elke 10 seconden kijken of Bankzaken nog open is (na 5 minuten niets doen gaat hij vanzelf op slot); tikken telt als bezig. */
function bkTijdBewaken(){
  clearInterval(bk.tmr);
  bk.tmr = setInterval(() => {
    if (current !== 'bank') return;
    let s; try { s = JSON.parse(Android.bankState()); } catch (e) { s = { open: false }; }
    if (!s.open) { bkToestand(); toast('Bankzaken is op slot gegaan'); return; }
    const min = Math.ceil(s.left / 60000);
    $('#bk-tijd').textContent = 'Gaat op slot over ' + min + (min === 1 ? ' minuut' : ' minuten') + ' zonder gebruik, en zodra je de app verlaat.';
  }, 10000);
}
document.addEventListener('pointerdown', ev => {
  if (current !== 'bank' || !bk.store || !ev.target.closest || !ev.target.closest('#s-bank')) return;
  if (Date.now() - bk.aanraak > 20000) { bk.aanraak = Date.now(); try { Android.bankTouch(); } catch (e) { } }
}, true);
document.addEventListener('input', ev => { if (current === 'bank' && ev.target.closest && ev.target.closest('#s-bank') && Date.now() - bk.aanraak > 20000) { bk.aanraak = Date.now(); try { Android.bankTouch(); } catch (e) { } } }, true);
function bkVerlaat(){ if (bk.aiBezig) { try { Android.bankAiCancel(); } catch (e) { } bk.aiBezig = false; } Android.bankLock(); bkVergeet(); try { Android.bankSecure(false); } catch (e) { } $('#bk-dicht').hidden = false; $('#bk-los').hidden = true; }
(function(){ const prev = window.onPauseApp; window.onPauseApp = function(){ if (prev) prev(); if (bk.store) { bkVergeet(); $('#bk-dicht').hidden = false; $('#bk-los').hidden = true; } }; })();

// ---------- tabs en filters ----------
function bkTab(t){
  bk.tab = t;
  document.querySelectorAll('#s-bank .pctabs [role=tab]').forEach(b => { const on = b.dataset.tab === t; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
  for (const p of ['overzicht', 'boekingen', 'ai', 'beheer']) $('#bk-p-' + p).hidden = p !== t;
  $('#bk-filters').hidden = t === 'beheer';
  bkRender();
}
function bkPeriode(){ $('#bk-eigen').hidden = $('#bk-periode').value !== 'eigen'; bk.lijstN = 100; bkRender(); }
function bkRekeningen(){
  const s = $('#bk-rek'), was = s.value, acc = [...new Set(bk.store.tx.map(t => t.acc))].sort();
  s.replaceChildren();
  const opt = (v, t) => { const o = document.createElement('option'); o.value = v; o.textContent = t; s.appendChild(o); };
  opt('alle', 'Alle rekeningen');
  for (const a of acc) opt(a, bkRekNaam(a));
  s.value = acc.includes(was) ? was : 'alle';
  $('#bk-rek-rij').hidden = acc.length < 2;
}
function bkRekNaam(a){ const n = bk.store && bk.store.namen[a]; return (n ? n + ' · ' : '') + (a === 'onbekend' ? 'Rekening onbekend' : a.length > 8 ? a.slice(0, 4) + ' … ' + a.slice(-4) : a); }

function bkRender(){
  if (!bk.store) return;
  const leeg = !bk.store.tx.length;
  $('#bk-leeg').hidden = !leeg || bk.tab === 'beheer';
  bkAutoLijst();
  if (bk.tab === 'beheer') { bkBeheer(); return; }
  if (leeg) { $('#bk-overzicht').replaceChildren(); $('#bk-lijst').replaceChildren(); $('#bk-periode-txt').textContent = ''; return; }
  const [van, tot, label] = bkPeriodeVan();
  let laatste = ''; for (const t of bk.store.tx) if (t.d > laatste) laatste = t.d;
  $('#bk-periode-txt').textContent = label + ': ' + bkDatum(van) + ' t/m ' + bkDatum(tot) + (laatste ? ' · nieuwste boeking ' + bkDatum(laatste) : '');
  if (bk.tab === 'overzicht') bkOverzicht(van, tot);
  if (bk.tab === 'boekingen') { bkCatSelect(); bkLijstRender(); }
  if (bk.tab === 'ai') bkAiRender();
}

// ---------- overzicht ----------
function bkKaart(titel, inhoud, extra){ return '<div class="card"' + (extra || '') + '><h2>' + esc(titel) + '</h2>' + inhoud + '</div>'; }
function bkVerschil(nu, was, goedAlsLager){
  if (!was) return '<span>—</span>';
  const p = Math.round((nu - was) / Math.abs(was) * 100);
  const goed = goedAlsLager ? p <= 0 : p >= 0;
  return '<span class="' + (p === 0 ? 'bk-neutraal' : goed ? 'bk-plus' : 'bk-min') + '">' + (p > 0 ? '+' : p < 0 ? '−' : '') + Math.abs(p) + '% t.o.v. ervoor</span>';
}
function bkOverzicht(van, tot){
  const lijst = bkSelectie(van, tot), t = bkTotalen(lijst);
  const [pv, pt] = bkVorige(van, tot), vorig = bkTotalen(bkSelectie(pv, pt));
  let h = '<div class="bk-kpis">'
    + '<div class="bk-kpi"><small>Inkomsten</small><b class="bk-plus">' + esc(bkEuro(t.in)) + '</b>' + bkVerschil(t.in, vorig.in, false) + '</div>'
    + '<div class="bk-kpi"><small>Uitgaven</small><b>' + esc(bkEuro(t.uit)) + '</b>' + bkVerschil(t.uit, vorig.uit, true) + '</div>'
    + '<div class="bk-kpi"><small>Over</small><b class="' + (t.netto >= 0 ? 'bk-plus' : 'bk-min') + '">' + esc(bkEuro(t.netto, true)) + '</b><span>' + (t.in > 0 ? 'Spaarquote ' + Math.round((t.in - t.uit) / t.in * 100) + '%' : '') + '</span></div>'
    + '<div class="bk-kpi"><small>Gespaard en belegd</small><b>' + esc(bkEuro(t.sparen)) + '</b><span>' + t.n + ' boekingen</span></div></div>';
  // Budgetten (deze maand)
  const budgets = Object.entries(bk.store.budget || {}).filter(([, v]) => v > 0);
  if (budgets.length) {
    const v = bkVandaag(), mt = bkTotalen(bkSelectie(v.slice(0, 7) + '-01', v));
    h += bkKaart('Budget deze maand', '<div class="bk-bars">' + budgets.map(([c, b]) => {
      const x = mt.cats.get(c), uit = x ? -x.som : 0, p = Math.min(100, Math.round(uit / b * 100));
      return '<button class="bk-bar" onclick="bkNaarCat(' + jsq(c) + ')"><div class="bk-bar-kop"><span>' + esc(c) + '</span><b>' + esc(bkEuroKort(uit)) + ' van ' + esc(bkEuroKort(b)) + '</b></div>'
        + '<div class="bk-spoor" role="presentation"><div class="bk-vul' + (uit > b ? ' over' : '') + '" style="width:' + p + '%"></div></div><small>' + (uit > b ? esc(bkEuroKort(uit - b)) + ' te veel' : esc(bkEuroKort(b - uit)) + ' over') + '</small></button>';
    }).join('') + '</div>');
  }
  // Per maand
  const pm = bkPerMaand(lijst);
  if (pm.length > 1) h += bkKaart('Per maand', bkMaandGrafiek(pm.slice(-12)));
  // Uitgaven per categorie
  const uitCats = [...t.cats.entries()].filter(([, x]) => x.soort === 'uit' && -x.som > 0).sort((a, b) => a[1].som - b[1].som);
  if (uitCats.length) {
    const max = -uitCats[0][1].som;
    h += bkKaart('Uitgaven per categorie', '<div class="bk-bars">' + uitCats.map(([c, x]) => {
      const was = vorig.cats.get(c), w = was ? -was.som : 0, nu = -x.som;
      return '<button class="bk-bar" onclick="bkNaarCat(' + jsq(c) + ')"><div class="bk-bar-kop"><span>' + esc(c) + '</span><b>' + esc(bkEuro(nu)) + '</b></div>'
        + '<div class="bk-spoor" role="presentation"><div class="bk-vul" style="width:' + Math.max(2, Math.round(nu / max * 100)) + '%"></div></div>'
        + '<small>' + Math.round(nu / Math.max(1, t.uit) * 100) + '% van je uitgaven · ' + x.n + '×' + (w ? ' · ervoor ' + esc(bkEuro(w)) : '') + '</small></button>';
    }).join('') + '</div>');
  }
  const inCats = [...t.cats.entries()].filter(([, x]) => x.soort === 'in' && x.som > 0).sort((a, b) => b[1].som - a[1].som);
  if (inCats.length) h += bkKaart('Inkomsten', inCats.map(([c, x]) => '<div class="bk-rij"><span class="bk-naam">' + esc(c) + '<small>' + x.n + ' bijschrijvingen</small></span><b class="bk-plus">' + esc(bkEuro(x.som)) + '</b></div>').join(''));
  // Vaste lasten
  const vast = bkTerugkerend(), actief = vast.filter(v => !v.gestopt);
  if (vast.length) {
    const som = actief.reduce((s, v) => s + v.perMaand, 0);
    h += bkKaart('Vaste lasten en abonnementen', '<p class="note" style="margin-top:0">Samen ongeveer <b>' + esc(bkEuro(som)) + '</b> per maand (' + actief.length + ' terugkerende betalingen).</p>'
      + vast.slice(0, 25).map(v => '<div class="bk-rij"><span class="bk-naam">' + esc(v.naam) + '<small>' + esc(v.cat) + ' · ' + esc(v.soort) + ' · ' + (v.gestopt ? 'mogelijk gestopt (laatst ' + esc(bkDatum(v.laatst)) + ')' : 'volgende rond ' + esc(v.volgende.toLocaleDateString('nl-NL', { day: 'numeric', month: 'short' }))) + '</small></span><b' + (v.gestopt ? ' class="bk-neutraal"' : '') + '>' + esc(bkEuro(v.bedrag)) + '</b></div>').join(''));
  }
  // Grootste ontvangers
  const tp = bkTegenpartijen(lijst).slice(0, 10);
  if (tp.length) h += bkKaart('Waar gaat het meeste heen', tp.map(x => '<div class="bk-rij"><span class="bk-naam">' + esc(x.naam) + '<small>' + esc(x.cat) + ' · ' + x.n + '×</small></span><b>' + esc(bkEuro(x.som)) + '</b></div>').join(''));
  // Grootste uitgaven
  const groot = lijst.filter(x => x.a < 0 && bkCatSoort(bkCat(x)) === 'uit').sort((a, b) => a.a - b.a).slice(0, 8);
  if (groot.length) h += bkKaart('Grootste uitgaven', groot.map(x => '<button class="bk-tx" onclick="bkTx(' + jsq(x.id) + ')"><span class="bk-naam"><b>' + esc(x.cp || x.ci || 'Onbekend') + '</b><small>' + esc(bkDatum(x.d)) + ' · ' + esc(bkCat(x)) + '</small></span><span class="bk-bedrag">' + esc(bkEuro(x.a)) + '</span></button>').join(''));
  // Saldo
  const reeks = bkSaldoReeks(van, tot);
  if (reeks.length > 1) h += bkKaart('Saldo', bkLijnGrafiek(reeks) + '<p class="note" style="margin-bottom:0">Laatste saldo: <b>' + esc(bkEuro(reeks[reeks.length - 1][1])) + '</b> op ' + esc(bkDatum(reeks[reeks.length - 1][0])) + '.</p>');
  // Per dag van de week
  const wd = [0, 0, 0, 0, 0, 0, 0];
  for (const x of lijst) if (x.a < 0 && bkCatSoort(bkCat(x)) === 'uit') wd[(new Date(x.d + 'T12:00:00').getDay() + 6) % 7] -= x.a;
  if (wd.some(v => v > 0)) {
    const mx = Math.max(...wd), dn = ['maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
    h += bkKaart('Uitgaven per dag van de week', '<div class="bk-bars">' + wd.map((v, i) => '<div class="bk-bar" style="cursor:default"><div class="bk-bar-kop"><span>' + dn[i] + '</span><b>' + esc(bkEuroKort(v)) + '</b></div><div class="bk-spoor" role="presentation"><div class="bk-vul" style="width:' + Math.round(v / mx * 100) + '%"></div></div></div>').join('') + '</div>');
  }
  if (!lijst.length) h = '<div class="card"><p class="note" style="margin:0">Geen boekingen in deze periode. Kies een andere periode of importeer een nieuwer afschrift.</p></div>';
  $('#bk-overzicht').innerHTML = h;
}
function bkMaandGrafiek(pm){
  const W = 340, H = 160, L = 4, B = 22, n = pm.length, bw = (W - L) / n;
  const max = Math.max(1, ...pm.map(([, x]) => Math.max(x.in, x.uit)));
  let s = '<svg class="bk-grafiek" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Inkomsten en uitgaven per maand">';
  pm.forEach(([m, x], i) => {
    const x0 = L + i * bw, w = Math.max(3, bw / 2 - 3), hi = (H - B - 6) * x.in / max, hu = (H - B - 6) * x.uit / max;
    s += '<rect x="' + (x0 + 2).toFixed(1) + '" y="' + (H - B - hi).toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + hi.toFixed(1) + '" rx="2" fill="var(--ok)"><title>' + esc(bkMaandNaam(m) + ' inkomsten ' + bkEuro(x.in)) + '</title></rect>';
    s += '<rect x="' + (x0 + 2 + w + 1).toFixed(1) + '" y="' + (H - B - hu).toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + hu.toFixed(1) + '" rx="2" fill="var(--bad)"><title>' + esc(bkMaandNaam(m) + ' uitgaven ' + bkEuro(x.uit)) + '</title></rect>';
    if (n <= 12) s += '<text x="' + (x0 + bw / 2).toFixed(1) + '" y="' + (H - 6) + '" text-anchor="middle">' + esc(BK_MAANDEN[Number(m.slice(5, 7)) - 1]) + '</text>';
  });
  s += '</svg>';
  const tabel = pm.map(([m, x]) => '<div class="bk-rij"><span class="bk-naam">' + esc(bkMaandNaam(m)) + '</span><b class="bk-plus">' + esc(bkEuroKort(x.in)) + '</b><b>' + esc(bkEuroKort(x.uit)) + '</b><b class="' + (x.in - x.uit - x.sparen >= 0 ? 'bk-plus' : 'bk-min') + '">' + esc((x.in - x.uit - x.sparen < 0 ? '−' : '') + bkEuroKort(x.in - x.uit - x.sparen)) + '</b></div>').join('');
  return s + '<div class="bk-legenda"><span><i style="background:var(--ok)"></i>Inkomsten</span><span><i style="background:var(--bad)"></i>Uitgaven</span></div><details class="rinfo"><summary>Als tabel (inkomsten, uitgaven, over)</summary>' + tabel + '</details>';
}
function bkLijnGrafiek(reeks){
  const W = 340, H = 140, P = 6;
  const vals = reeks.map(r => r[1]), lo = Math.min(...vals), hi = Math.max(...vals), span = Math.max(1, hi - lo);
  const t0 = new Date(reeks[0][0]).getTime(), t1 = Math.max(t0 + 1, new Date(reeks[reeks.length - 1][0]).getTime());
  const pt = r => [(P + (new Date(r[0]).getTime() - t0) / (t1 - t0) * (W - 2 * P)).toFixed(1), (P + (1 - (r[1] - lo) / span) * (H - 2 * P - 14)).toFixed(1)];
  const d = reeks.map((r, i) => (i ? 'L' : 'M') + pt(r).join(' ')).join(' ');
  return '<svg class="bk-grafiek" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Saldoverloop van ' + esc(bkEuro(vals[0])) + ' naar ' + esc(bkEuro(vals[vals.length - 1])) + '">'
    + '<path d="' + d + '" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linejoin="round"/>'
    + '<text x="' + P + '" y="' + (H - 2) + '">' + esc(bkEuroKort(lo)) + '</text><text x="' + (W - P) + '" y="' + (H - 2) + '" text-anchor="end">hoogst ' + esc(bkEuroKort(hi)) + '</text></svg>';
}
function bkNaarCat(c){ bkTab('boekingen'); bkCatSelect(); $('#bk-cat').value = c; bkLijstRender(); }

// ---------- boekingen ----------
function bkCatSelect(){
  const s = $('#bk-cat'), was = s.value;
  s.replaceChildren();
  const opt = (v, t) => { const o = document.createElement('option'); o.value = v; o.textContent = t; s.appendChild(o); };
  opt('', 'Alle categorieën'); opt('*zonder', 'Zonder categorie (overig)');
  for (const c of bkAlleCats()) opt(c, c);
  s.value = [...s.options].some(o => o.value === was) ? was : '';
}
function bkGefilterd(){
  const [van, tot] = bkPeriodeVan(), q = $('#bk-zoek').value.trim().toLowerCase(), cat = $('#bk-cat').value, sort = $('#bk-sort').value;
  const qBedrag = q.replace(/[€\s]/g, '').replace(',', '.');
  let l = bkSelectie(van, tot).filter(t => {
    const c = bkCat(t);
    if (cat === '*zonder' ? !(c === 'Overig' || c === 'Overige inkomsten') : cat && c !== cat) return false;
    if (!q) return true;
    if (/^\d+(\.\d{1,2})?$/.test(qBedrag) && Math.abs(Math.abs(t.a) / 100 - Number(qBedrag)) < 0.005) return true;
    return ((t.cp || '') + ' ' + (t.ds || '') + ' ' + (t.ci || '') + ' ' + c + ' ' + (t.n || '')).toLowerCase().includes(q);
  });
  l.sort(sort === 'oud' ? (a, b) => a.d < b.d ? -1 : a.d > b.d ? 1 : 0 : sort === 'hoog' ? (a, b) => a.a - b.a : sort === 'bij' ? (a, b) => b.a - a.a : (a, b) => a.d > b.d ? -1 : a.d < b.d ? 1 : 0);
  return l;
}
function bkLijstRender(){
  if (!bk.store) return;
  const l = bkGefilterd(), toon = l.slice(0, bk.lijstN);
  let som = 0; for (const t of l) som += t.a;
  $('#bk-lijst-telling').textContent = l.length + (l.length === 1 ? ' boeking' : ' boekingen') + ' · samen ' + bkEuro(som, true);
  let h = '', dag = '';
  const opDag = $('#bk-sort').value === 'nieuw' || $('#bk-sort').value === 'oud';
  for (const t of toon) {
    if (opDag && t.d !== dag) { dag = t.d; h += '<div class="bk-dag">' + esc(bkDatum(t.d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })) + '</div>'; }
    h += '<button class="bk-tx" onclick="bkTx(' + jsq(t.id) + ')"><span class="bk-naam"><b>' + esc(t.cp || t.ci || 'Onbekend') + '</b><small>' + esc(bkCat(t)) + (opDag ? '' : ' · ' + esc(bkDatum(t.d))) + (t.n ? ' · ' + esc(t.n) : t.ds ? ' · ' + esc(t.ds) : '') + '</small></span><span class="bk-bedrag ' + (t.a > 0 ? 'bk-plus' : '') + '">' + esc(bkEuro(t.a, true)) + '</span></button>';
  }
  $('#bk-lijst').innerHTML = h || '<p class="note" style="margin:8px 0">Geen boekingen gevonden.</p>';
  $('#bk-meer').hidden = l.length <= bk.lijstN;
}
function bkMeer(){ bk.lijstN += 200; bkLijstRender(); }
function bkVind(id){ return bk.store && bk.store.tx.find(t => t.id === id); }
function bkTx(id){
  const t = bkVind(id); if (!t) return;
  const regels = [bkDatum(t.d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }), bkEuro(t.a, true) + ' · ' + bkCat(t)];
  if (t.ci) regels.push('Tegenrekening ' + t.ci);
  if (t.ds) regels.push(t.ds.slice(0, 300));
  if (t.ty) regels.push(t.ty);
  regels.push('Rekening ' + bkRekNaam(t.acc) + (t.src ? ' · ' + t.src : ''));
  if (t.n) regels.push('Notitie: ' + t.n);
  bk.venster = true;
  openSheet(t.cp || t.ci || 'Boeking', regels.join('\n'), [
    ['Categorie wijzigen', () => bkKiesCat(id, false), 'edit'],
    ['Altijd zo indelen: ' + (bkSleutel(t).slice(0, 30) || 'deze naam'), () => bkKiesCat(id, true), 'repeat'],
    [t.n ? 'Notitie wijzigen' : 'Notitie toevoegen', () => askInput('Notitie', 'Alleen voor jezelf, versleuteld bewaard.', t.n || '', 'Bewaren', v => { t.n = String(v || '').slice(0, 200); if (!t.n) delete t.n; bkSave(); bkRender(); }), 'notes'],
    ['Andere boekingen van deze naam', () => { bkTab('boekingen'); $('#bk-cat').value = ''; $('#bk-zoek').value = (t.cp || t.ci || '').slice(0, 40); bkLijstRender(); }, 'search']
  ]);
}
function bkKiesCat(id, regel){
  const t = bkVind(id); if (!t) return;
  const cats = bkAlleCats().filter(c => bkCatSoort(c) === 'neutraal' || (t.a > 0 ? true : bkCatSoort(c) !== 'in'));
  const acts = cats.map(c => [c, () => {
    if (regel) {
      const p = bkSleutel(t);
      if (!p || p === '?') { toast('Deze boeking heeft geen naam om een regel op te maken'); return; }
      bk.store.rules = bk.store.rules.filter(g => !(g.p === p && (g.t || '') === (t.a > 0 ? '+' : '-')));
      bk.store.rules.unshift({ p, cat: c, t: t.a > 0 ? '+' : '-' });
      delete t.c;
    } else t.c = c;
    bkHerbereken();
    if (bkSave()) toast(regel ? 'Regel bewaard: “' + p0(t) + '” → ' + c : 'Categorie gewijzigd');
    bkRender();
  }]);
  acts.push(['Nieuwe categorie…', () => bkNieuweCat(c => { if (regel) { bk.store.rules.unshift({ p: bkSleutel(t), cat: c, t: t.a > 0 ? '+' : '-' }); } else t.c = c; bkHerbereken(); bkSave(); bkRender(); }), 'plus']);
  if (t.c && !regel) acts.push(['Automatisch laten indelen', () => { delete t.c; bkHerbereken(); bkSave(); bkRender(); }, 'refresh']);
  bk.venster = true;
  openSheet(regel ? 'Altijd indelen als…' : 'Categorie', regel ? 'Voor alles met “' + p0(t) + '” in de naam of omschrijving' : '', acts);
}
const p0 = t => bkSleutel(t).slice(0, 40);
function bkNieuweCat(klaar){
  askInput('Nieuwe categorie', 'Een eigen categorie voor uitgaven.', '', 'Toevoegen', v => {
    const n = String(v || '').trim().slice(0, 40);
    if (!n) return;
    if (!bkAlleCats().includes(n)) bk.store.cats.push({ naam: n, soort: 'uit' });
    klaar(n);
  });
}

// ---------- importeren ----------
function bkImporteren(){ const w = $('#bk-wis-bron'); Android.bankImport(!w || w.checked); }
window.onBankImport = function(r){
  if (!r || r.cancelled) return;
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { return; } }
  if (r.error) { toast(r.error); if (/op slot|Ontgrendel/i.test(r.error) && current === 'bank') bkToestand(); return; }
  if (current !== 'bank' || !bkToestand()) return;
  bkLaad();
  const fs = r.bestanden || [];
  let nieuw = 0, dubbel = 0; const fout = [];
  for (const f of fs) { if (f.ok && f.sum) { nieuw += f.sum.nieuw || 0; dubbel += f.sum.dubbel || 0; } else fout.push(f.naam + ': ' + (f.fout || 'niet gelukt')); }
  const tekst = nieuw + ' nieuwe boekingen' + (dubbel ? ', ' + dubbel + ' stonden er al' : '') + (r.gewist ? '. ' + r.gewist + (r.gewist === 1 ? ' bestand' : ' bestanden') + ' van de telefoon verwijderd' : '') + (fout.length ? '.\n\nNiet gelukt:\n' + fout.join('\n') : '.');
  bk.venster = true;
  askConfirm('Afschrift geïmporteerd', tekst, 'OK', () => {});
};
function bkKoppel(id){
  const tx = bk.store ? bk.store.tx : [];
  const code = id === 'rabobank' ? 'RABO' : '';
  let laatst = '';
  for (const t of tx) if ((code && t.acc.slice(4, 8) === code) || t.src === 'Rabobank') { if (t.d > laatst) laatst = t.d; }
  let van;
  const d = new Date((laatst || bkVandaag()) + 'T12:00:00');
  if (laatst) d.setDate(d.getDate() - 5); else d.setMonth(d.getMonth() - 13);
  van = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const r = Android.bankKoppel(id, van);
  if (r) toast(r);
}
function bkKoppelKlaar(u){
  let r; try { r = JSON.parse(u); } catch (e) { return; }
  if (r.wacht) { toast('Opgehaald bij ' + (r.bank || 'de bank') + '. Ontgrendel Bankzaken om het af te ronden.'); return; }
  const f = (r.bestanden || [])[0];
  if (f && f.ok && f.sum) toast((r.bank || 'Bank') + ': ' + f.sum.nieuw + ' nieuwe boekingen' + (f.sum.dubbel ? ' (' + f.sum.dubbel + ' stonden er al)' : ''));
}
function bkAutoLijst(){
  let banken = []; try { banken = JSON.parse(Android.bankBanken()); } catch (e) { }
  const auto = banken.filter(b => b.robot);
  const h = auto.map(b => '<div class="bk-bank"><span><b>' + esc(b.naam) + '</b><small>Inloggen met de ' + esc(b.appNaam || 'app van je bank') + '</small></span><button class="mini" style="float:none" onclick="bkKoppel(' + jsq(b.id) + ')">Ophalen</button></div>').join('');
  document.querySelectorAll('#s-bank .bk-auto-lijst').forEach(e => { e.innerHTML = h; });
  const u = $('#bk-uitleg');
  if (u) u.innerHTML = banken.map(b => '<div class="bk-res"><b>' + esc(b.naam) + '</b><br>' + esc(b.uitleg) + '</div>').join('');
}

// ---------- beheer ----------
function bkBeheer(){
  const s = bk.store;
  const zonder = s.tx.filter(t => { const c = bkCat(t); return c === 'Overig' || c === 'Overige inkomsten'; });
  $('#bk-zonder').textContent = zonder.length ? zonder.length + ' boekingen staan nog bij Overig. De lokale AI kan voorstellen maken voor de namen die het vaakst voorkomen; jij kiest wat je overneemt.' : 'Alle boekingen zijn ingedeeld.';
  $('#bk-ai-cat').hidden = !zonder.length;
  $('#bk-regels').innerHTML = s.rules.length ? s.rules.map((g, i) => '<div class="bk-rij"><span class="bk-naam">“' + esc(g.p) + '”<small>' + (g.t === '+' ? 'bijschrijvingen' : g.t === '-' ? 'afschrijvingen' : 'alles') + ' → ' + esc(g.cat) + '</small></span><button class="mini" style="float:none" onclick="bkRegelWeg(' + i + ')" aria-label="Regel verwijderen">Weg</button></div>').join('') : '<p class="note" style="margin:0">Nog geen eigen regels.</p>';
  $('#bk-budgetten').innerHTML = Object.entries(s.budget).filter(([, v]) => v > 0).map(([c, v]) => '<div class="bk-rij"><span class="bk-naam">' + esc(c) + '<small>per maand</small></span><b>' + esc(bkEuroKort(v)) + '</b><button class="mini" style="float:none" onclick="bkBudgetWeg(' + jsq(c) + ')">Weg</button></div>').join('');
  const acc = [...new Set(s.tx.map(t => t.acc))].sort();
  $('#bk-rekeningen').innerHTML = acc.map(a => '<div class="bk-rij"><span class="bk-naam">' + esc(bkRekNaam(a)) + '<small>' + esc(a) + ' · geïmporteerd</small></span><button class="mini" style="float:none" onclick="bkRekHernoem(' + jsq(a) + ')">Naam</button></div>').join('')
    + s.eigen.map((e, i) => '<div class="bk-rij"><span class="bk-naam">' + esc(e.naam || e.iban) + '<small>' + esc(e.iban) + ' · zelf toegevoegd</small></span><button class="mini" style="float:none" onclick="bkEigenWeg(' + i + ')">Weg</button></div>').join('');
  $('#bk-imports').innerHTML = s.imports.length ? '<details class="rinfo"><summary>Eerdere imports (' + s.imports.length + ')</summary>' + s.imports.slice().reverse().slice(0, 30).map(i => '<div class="bk-res">' + esc(new Date(i.t).toLocaleDateString('nl-NL', { day: 'numeric', month: 'short', year: 'numeric' })) + ' · ' + esc(i.bank || i.format) + ' · ' + esc(i.van ? bkDatum(i.van) + ' t/m ' + bkDatum(i.tot) : '') + ' · ' + (i.nieuw || 0) + ' nieuw</div>').join('') + '</details>' : '';
  const st = bk.st || {};
  const veilig = [
    'Versleuteld met AES-256-GCM; de sleutel ' + (st.hardware ? 'zit in de beveiligde hardware van deze telefoon' : 'zit in de beveiligde sleutelopslag van Android') + ' en kan er niet uit.',
    st.ontgrendeling ? 'De telefoon zelf geeft de sleutel alleen vrij tot 15 minuten na je vingerafdruk of pincode.' : 'Openen alleen na je vingerafdruk of pincode (deze telefoon koppelt de sleutel niet aan de ontgrendeling zelf).',
    'Gaat op slot na 5 minuten niets doen en zodra je de app verlaat; dan is alles ook uit het geheugen.',
    'Geen schermafdrukken of schermopnames, en niet zichtbaar in "recente apps".',
    'Gaat niet mee in de Android-backup, Alles back-uppen, zoeken, de assistent of de statistieken.',
    'De lokale AI draait op deze telefoon, onthoudt niets en bewaart geen gesprekken.',
    'Automatisch ophalen praat alleen met de website van je bank (https); je logt zelf in met de app van je bank en na afloop wordt de sessie gewist.',
    'Wissen haalt ook de sleutel weg: daarna is niets meer terug te halen.'];
  $('#bk-veilig').innerHTML = veilig.map(v => '<li>' + esc(v) + '</li>').join('');
}
function bkRegelWeg(i){ bk.store.rules.splice(i, 1); bkHerbereken(); bkSave(); bkRender(); }
function bkBudgetWeg(c){ delete bk.store.budget[c]; bkSave(); bkRender(); }
function bkBudgetNieuw(){
  const cats = bkAlleCats().filter(c => bkCatSoort(c) === 'uit');
  openSheet('Budget voor…', '', cats.map(c => [c, () => askInput('Budget ' + c, 'Bedrag per maand in euro.', bk.store.budget[c] ? String(bk.store.budget[c] / 100) : '', 'Bewaren', v => {
    const n = Math.round(Number(String(v || '').replace(/[€\s.]/g, '').replace(',', '.')) * 100);
    if (!(n > 0) || n > 1e10) { toast('Vul een bedrag in'); return; }
    bk.store.budget[c] = n; bkSave(); bkRender();
  })]));
}
function bkRekHernoem(a){ askInput('Naam van de rekening', a, bk.store.namen[a] || '', 'Bewaren', v => { const n = String(v || '').trim().slice(0, 40); if (n) bk.store.namen[a] = n; else delete bk.store.namen[a]; bkSave(); bkRekeningen(); bkRender(); }); }
function bkEigenNieuw(){
  askInput('Eigen rekening', 'IBAN van je eigen rekening (bijv. een spaarrekening).', '', 'Toevoegen', v => {
    const iban = String(v || '').replace(/\s+/g, '').toUpperCase();
    if (!/^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$/.test(iban)) { toast('Dat lijkt geen IBAN'); return; }
    if (!bk.store.eigen.some(e => e.iban === iban)) bk.store.eigen.push({ iban, naam: '' });
    bkHerbereken(); bkSave(); bkRender();
  });
}
function bkEigenWeg(i){ bk.store.eigen.splice(i, 1); bkHerbereken(); bkSave(); bkRender(); }
function bkWis(){
  askConfirm('Alle bankgegevens wissen?', 'Alle afschriften, regels, budgetten en de sleutel worden van deze telefoon verwijderd. Dit kan niet ongedaan worden gemaakt.', 'Wissen', () => {
    if (!bk.st || !bk.st.open) { toast('Ontgrendel eerst; daarna kun je wissen'); return; }
    const r = Android.bankWipe();
    if (r) { toast(r); return; }
    bkVergeet(); bkToestand(); toast('Alle bankgegevens zijn gewist');
  });
}
