/* Bankzaken: rapportages over je inkomsten en uitgaven, volledig op de telefoon.
 * De gegevens komen ontsleuteld van Bank.java (na vingerafdruk of pincode) en staan alleen in het geheugen zolang het
 * scherm open is: weg uit Bankzaken, de app uit beeld of op slot = alles uit het geheugen en van het scherm.
 * Alle tekst uit afschriften (namen, omschrijvingen) is van buiten: altijd met esc() tonen, nooit als HTML. */

// ---------- categorieën ----------
// soort: uit (uitgave), in (inkomsten), sparen (apart, telt niet als uitgave), neutraal (eigen rekeningen),
// tussen (privé-opname of inbreng op een zakelijke rekening: geen omzet of kosten). Privé is geld van/naar je eigen bedrijf
// inkomsten (naar je bedrijf = negatief), zodat heen en terug tegen elkaar wegvalt. Groep: '' beide, 'p' alleen privé, 'z' alleen zakelijk.
const BK_CATS = [
  ['Boodschappen', 'uit', 'p'], ['Uit eten & drinken', 'uit', 'p'], ['Wonen', 'uit', 'p'], ['Energie & water', 'uit', ''], ['Telefoon & internet', 'uit', ''],
  ['Verzekeringen', 'uit', ''], ['Zorg', 'uit', 'p'], ['Vervoer', 'uit', 'p'], ['Abonnementen', 'uit', 'p'], ['Winkelen', 'uit', 'p'], ['Kleding', 'uit', 'p'],
  ['Persoonlijke verzorging', 'uit', 'p'], ['Vrije tijd & sport', 'uit', 'p'], ['Reizen & vakantie', 'uit', 'p'], ['Belastingen & overheid', 'uit', ''],
  ['Kinderen & onderwijs', 'uit', 'p'], ['Huisdieren', 'uit', 'p'], ['Giften & goede doelen', 'uit', ''], ['Contant geld', 'uit', ''], ['Bankkosten', 'uit', ''],
  ['Overig', 'uit', 'p'],
  ['Inkoop & materiaal', 'uit', 'z'], ['Software & abonnementen', 'uit', 'z'], ['Kantoor & huisvesting', 'uit', 'z'], ['Personeel & lonen', 'uit', 'z'],
  ['Marketing & advertenties', 'uit', 'z'], ['Reis- en autokosten', 'uit', 'z'], ['Representatie', 'uit', 'z'], ['Advies & administratie', 'uit', 'z'],
  ['Btw & loonheffing', 'uit', 'z'], ['Overige zakelijke kosten', 'uit', 'z'],
  ['Salaris', 'in', 'p'], ['Toeslagen & uitkeringen', 'in', 'p'], ['Terugbetalingen', 'in', ''], ['Rente & dividend', 'in', ''], ['Overige inkomsten', 'in', 'p'],
  ['Omzet', 'in', 'z'], ['Overige zakelijke inkomsten', 'in', 'z'],
  ['Uit eigen bedrijf', 'in', 'p'], ['Naar eigen bedrijf', 'in', 'p'], ['Privé-opnamen', 'tussen', 'z'], ['Inbreng privé', 'tussen', 'z'],
  ['Sparen & beleggen', 'sparen', ''], ['Eigen rekeningen', 'neutraal', '']];
/** Geld tussen een privé- en een zakelijke rekening van jezelf (telt niet mee als beide in beeld zijn). */
const BK_TUSSEN = new Set(['Uit eigen bedrijf', 'Naar eigen bedrijf', 'Privé-opnamen', 'Inbreng privé']);
const BK_OVERIG = new Set(['Overig', 'Overige inkomsten', 'Overige zakelijke kosten', 'Overige zakelijke inkomsten']);

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
].map(([p, c, tt]) => [new RegExp(p, 'i'), c, tt]);

// Zakelijke rekeningen: eerst deze regels, dan de algemene (met BK_ZMAP omgezet naar een zakelijke categorie).
const BK_ZREGELS = [
  ['belastingdienst.*(omzetbelasting|\\bbtw\\b|loonheffing)|omzetbelasting|loonheffing|loonbelasting|btw[- ]?aangifte|aangifte btw', 'Btw & loonheffing', ''],
  ['google ads|adwords|meta ads|facebook ads|facebk|linkedin ads|tiktok ads|advertent|drukwerk|vistaprint|helloprint|drukland|reclame', 'Marketing & advertenties', '-'],
  ['\\baws\\b|amazon web services|google (workspace|cloud|\\*gsuite)|microsoft|azure|github|gitlab|atlassian|\\bjira\\b|slack|notion|figma|adobe|openai|anthropic|anysphere|cursor\\.(sh|com)|vercel|netlify|hetzner|transip|versio|strato|mijndomein|one\\.com|digitalocean|cloudflare|moneybird|e-boekhouden|exact online|snelstart|twinfield|jortt|zoho|hubspot|mailchimp|canva|dropbox|zoom\\.us|zoom video|1password|bitwarden|jetbrains|\\bsaas\\b|softwarelicentie|hosting|domeinnaam', 'Software & abonnementen', '-'],
  ['\\bkvk\\b|kamer van koophandel|accountant|administratiekantoor|boekhoud|belastingadvi|advocaat|jurist|notaris|adviesbureau|\\badvies\\b', 'Advies & administratie', '-'],
  ['nettoloon|salaris|\\bloon\\b|nmbrs|loket\\.nl|payroll|pensioenfonds|arbodienst', 'Personeel & lonen', '-'],
  ['kantoorruimte|bedrijfsruimte|huur kantoor|\\bregus\\b|\\bspaces\\b (bv|b\\.v|nederland)|flexplek|werkplek|coworking|kantoorartikelen|staples|viking|office centre|bedrijfspand', 'Kantoor & huisvesting', '-'],
  ['bouwmaat|\\bhubo\\b|technische unie|rexel|conrad electronic|reichelt|makro|sligro|hanos|bidfood|inkoop|groothandel|leverancier', 'Inkoop & materiaal', '-']
].map(([p, c, tt]) => [new RegExp(p, 'i'), c, tt]);
const BK_ZMAP = { 'Uit eten & drinken': 'Representatie', 'Vervoer': 'Reis- en autokosten', 'Reizen & vakantie': 'Reis- en autokosten', 'Abonnementen': 'Software & abonnementen',
  'Winkelen': 'Inkoop & materiaal', 'Wonen': 'Kantoor & huisvesting', 'Salaris': 'Omzet', 'Toeslagen & uitkeringen': 'Overige zakelijke inkomsten', 'Overige inkomsten': 'Overige zakelijke inkomsten',
  'Boodschappen': 'Overige zakelijke kosten', 'Zorg': 'Overige zakelijke kosten', 'Kleding': 'Overige zakelijke kosten', 'Persoonlijke verzorging': 'Overige zakelijke kosten',
  'Vrije tijd & sport': 'Overige zakelijke kosten', 'Kinderen & onderwijs': 'Overige zakelijke kosten', 'Huisdieren': 'Overige zakelijke kosten', 'Overig': 'Overige zakelijke kosten' };

let bk = { geimporteerd: new Set(), st: null, store: null, tab: 'overzicht', lijstN: 100, catCache: new Map(), eigen: new Set(), aiBezig: false, aiDoel: '', aiTekst: '', voorstellen: null, aanraak: 0, tmr: null };

const bkVandaag = () => window.BK_VANDAAG || rgToday(); // vandaag in de app-zone (Regio en taal)
function bkEuro(c, teken){
  return fmtMoney(c / 100, 'EUR', { sign: !!teken }); // de rekeningen zijn in euro; tekens volgens Regio en taal
}
const bkEuroKort = c => fmtMoney(c / 100, 'EUR', { round: true, minus: '' });
const bkDatum = (d, opt) => fmtDate(d + 'T12:00:00', opt || { day: 'numeric', month: 'short', year: 'numeric' });
const BK_MAANDEN = ['jan', 'feb', 'mrt', 'apr', 'mei', 'jun', 'jul', 'aug', 'sep', 'okt', 'nov', 'dec'];
const bkMaandNaam = ym => t(BK_MAANDEN[Number(ym.slice(5, 7)) - 1]) + ' ' + ym.slice(2, 4);

function bkCatSoort(c){ const x = BK_CATS.find(k => k[0] === c); if (x) return x[1]; const e = ((bk.store && bk.store.cats) || []).find(k => k.naam === c); return e ? e.soort : 'uit'; }
function bkCatGroep(c){ const x = BK_CATS.find(k => k[0] === c); return x ? x[2] : ''; }
function bkAlleCats(){ return BK_CATS.map(c => c[0]).concat(((bk.store && bk.store.cats) || []).map(c => c.naam)); }
/** Categorieën die bij een privé- ('prive') of zakelijke ('zakelijk') rekening horen; leeg = alle. */
function bkCatsVoor(soort){ const g = soort === 'zakelijk' ? 'z' : soort === 'prive' ? 'p' : ''; return bkAlleCats().filter(c => !g || !bkCatGroep(c) || bkCatGroep(c) === g); }

// ---------- rekeningen: privé en zakelijk ----------
/** Gegevens van een eigen rekening: { naam, soort: 'prive' | 'zakelijk' }; zelf toegevoegde rekeningen staan in store.eigen. */
function bkRekInfo(a){
  const s = bk.store; if (!s) return {};
  const e = (s.eigen || []).find(x => x.iban === a), r = s.rek && s.rek[a];
  return Object.assign({}, e || {}, r || {});
}
function bkRekSoort(a){ return bkRekInfo(a).soort === 'zakelijk' ? 'zakelijk' : 'prive'; }
function bkRekZet(a, veld, waarde){
  const e = (bk.store.eigen || []).find(x => x.iban === a);
  if (e) e[veld] = waarde;
  if (!e || bk.store.tx.some(tt => tt.acc === a)) (bk.store.rek[a] = bk.store.rek[a] || {})[veld] = waarde; // geïmporteerd: altijd in rek
}
/** De rekeningen van je afschriften en de zelf toegevoegde, met soort. */
function bkAlleRek(){
  const acc = [...new Set(bk.store.tx.map(tt => tt.acc))].sort();
  return acc.map(a => ({ iban: a, eigen: false })).concat((bk.store.eigen || []).filter(e => !acc.includes(e.iban)).map(e => ({ iban: e.iban, eigen: true })));
}
/** Hoort deze rekening bij wat je nu bekijkt (Alle, Privé, Zakelijk of één rekening)? */
function bkInZicht(a){ const r = bkRekening(); return r === 'alle' || (r === 'prive' || r === 'zakelijk' ? bkRekSoort(a) === r : a === r); }
/** Wat je bekijkt: 'prive', 'zakelijk' of '' (gemengd). */
function bkZichtSoort(){
  const r = bkRekening();
  if (r === 'prive' || r === 'zakelijk') return r;
  if (r !== 'alle') return bkRekSoort(r);
  const soorten = new Set(bk.store.tx.map(tt => bkRekSoort(tt.acc)));
  return soorten.size === 1 ? [...soorten][0] : '';
}
/** De soort van een boeking voor de totalen: geld tussen privé en zakelijk telt niet als beide kanten in beeld zijn. */
function bkSoort(tt){
  const c = bkCat(tt), s = bkCatSoort(c);
  if (BK_TUSSEN.has(c) && tt.ci && bk.geimporteerd.has(tt.ci) && bkInZicht(tt.ci)) return 'neutraal'; // de andere kant staat ook in beeld
  return s;
}
/** Naam zonder winkelnummers e.d., om dezelfde winkel samen te nemen. */
function bkNorm(s){ return String(s || '').toLowerCase().replace(/\b(nr|pas)\s*\d+\b/g, '').replace(/[0-9]{2,}/g, '').replace(/[^a-zÀ-ɏ&+ ]+/g, ' ').replace(/\s+/g, ' ').trim(); }
function bkSleutel(tt){ return bkNorm(tt.cp || tt.ci) || '?'; }

/** De categorie van een boeking: zelf gekozen > eigen rekening > eigen regel > ingebouwde regel > overig. */
function bkCat(tt){
  const c = bk.catCache.get(tt.id); if (c) return c;
  let r = '';
  const soort = bkRekSoort(tt.acc), zak = soort === 'zakelijk', past = (teken) => !teken || (teken === '+') === (tt.a > 0);
  if (tt.c) r = tt.c;
  else if (tt.ci && tt.ci !== tt.acc && bk.eigen.has(tt.ci)) {
    // Naar of van een eigen rekening: binnen privé of binnen zakelijk = eigen rekeningen; ertussen = privé-opname/inbreng
    const ander = bkRekSoort(tt.ci);
    r = ander === soort ? 'Eigen rekeningen' : zak ? (tt.a < 0 ? 'Privé-opnamen' : 'Inbreng privé') : (tt.a > 0 ? 'Uit eigen bedrijf' : 'Naar eigen bedrijf');
  } else {
    const tekst = ((tt.cp || '') + ' ' + (tt.ds || '')).toLowerCase(), norm = bkNorm(tekst);
    for (const g of (bk.store.rules || [])) if (g.p && (!g.s || g.s === soort) && (tekst.includes(g.p) || norm.includes(g.p)) && past(g.t)) { r = zak && !g.s ? (BK_ZMAP[g.cat] || g.cat) : g.cat; break; }
    if (!r && zak) for (const [re, cat, teken] of BK_ZREGELS) if (past(teken) && re.test(tekst)) { r = cat; break; }
    if (!r) for (const [re, cat, teken] of BK_REGELS) if (past(teken) && re.test(tekst)) { r = zak ? (BK_ZMAP[cat] || cat) : cat; break; }
    if (!r) r = tt.a > 0 ? (zak ? 'Omzet' : 'Overige inkomsten') : (zak ? 'Overige zakelijke kosten' : 'Overig');
  }
  bk.catCache.set(tt.id, r);
  return r;
}
function bkHerbereken(){
  bk.catCache.clear();
  bk.eigen = new Set(); bk.geimporteerd = new Set();
  if (!bk.store) return;
  for (const tt of bk.store.tx) if (tt.acc && tt.acc !== 'onbekend') { bk.eigen.add(tt.acc); bk.geimporteerd.add(tt.acc); }
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
  let lo = v, hi = '1990-01-01'; for (const tt of tx) { if (tt.d < lo) lo = tt.d; if (tt.d > hi) hi = tt.d; }
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
  return bk.store.tx.filter(tt => tt.d >= van && tt.d <= tot && bkInZicht(tt.acc));
}

// ---------- rekenen ----------
function bkTotalen(lijst){
  const o = { in: 0, uit: 0, sparen: 0, naarPrive: 0, inbreng: 0, cats: new Map(), n: 0 };
  for (const tt of lijst) {
    const c = bkCat(tt), s = bkSoort(tt);
    if (s === 'neutraal') continue;
    o.n++;
    const x = o.cats.get(c) || { som: 0, n: 0, soort: s }; x.som += tt.a; x.n++; o.cats.set(c, x);
    if (s === 'tussen') { if (tt.a < 0) o.naarPrive -= tt.a; else o.inbreng += tt.a; continue; }
    if (s === 'in') o.in += tt.a; else if (s === 'sparen') o.sparen -= tt.a; else o.uit -= tt.a;
  }
  o.netto = o.in - o.uit - o.sparen;
  return o;
}
function bkPerMaand(lijst){
  const m = new Map();
  for (const tt of lijst) {
    const s = bkSoort(tt); if (s === 'neutraal' || s === 'tussen') continue;
    const k = tt.d.slice(0, 7), x = m.get(k) || { in: 0, uit: 0, sparen: 0 };
    if (s === 'in') x.in += tt.a; else if (s === 'sparen') x.sparen -= tt.a; else x.uit -= tt.a;
    m.set(k, x);
  }
  return [...m.entries()].sort((a, b) => a[0] < b[0] ? -1 : 1);
}
function bkTegenpartijen(lijst){
  const m = new Map();
  for (const tt of lijst) {
    if (tt.a >= 0 || bkSoort(tt) !== 'uit') continue;
    const k = bkSleutel(tt), x = m.get(k) || { naam: tt.cp || tt.ci, som: 0, n: 0, cat: bkCat(tt) };
    x.som -= tt.a; x.n++; m.set(k, x);
  }
  return [...m.values()].sort((a, b) => b.som - a.som);
}
/** Vaste lasten en abonnementen: dezelfde tegenpartij, regelmatig (week/maand/kwartaal/jaar), ongeveer hetzelfde bedrag. */
function bkTerugkerend(){
  const groep = new Map();
  for (const tt of bk.store.tx) {
    if (tt.a >= 0 || !bkInZicht(tt.acc)) continue;
    const s = bkSoort(tt); if (s === 'neutraal' || s === 'tussen') continue;
    const k = bkRekSoort(tt.acc) + '|' + bkSleutel(tt); if (!groep.has(k)) groep.set(k, []); groep.get(k).push(tt);
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
    const bedragen = l.map(tt => -tt.a), m = med(bedragen);
    if (bedragen.filter(b => Math.abs(b - m) <= m * 0.15 + 100).length / bedragen.length < 0.6) continue;
    const laatst = l[l.length - 1], volgende = rgDayAdd(laatst.d, Math.round(iv)); // een kalenderdatum ('JJJJ-MM-DD'): fmtDate verschuift hem niet
    const gestopt = vandaag - new Date(laatst.d + 'T12:00:00') > iv * 1.6 * 864e5;
    uit.push({ naam: laatst.cp || laatst.ci, cat: bkCat(laatst), soort: soort[0], bedrag: m, perMaand: Math.round(m * soort[1]), laatst: laatst.d, volgende, gestopt, n: l.length });
  }
  return uit.sort((a, b) => (a.gestopt - b.gestopt) || b.perMaand - a.perMaand);
}
function bkSaldoReeks(van, tot){
  // Laatste bekende saldo per rekening per dag; samen opgeteld (alleen rekeningen met saldo in het afschrift)
  const per = new Map();
  for (const tt of bk.store.tx) { if (tt.b == null || !bkInZicht(tt.acc)) continue; if (!per.has(tt.acc)) per.set(tt.acc, []); per.get(tt.acc).push(tt); }
  if (!per.size) return [];
  const dagen = new Set();
  for (const [, l] of per) { l.sort((a, b) => a.d < b.d ? -1 : a.d > b.d ? 1 : 0); for (const tt of l) if (tt.d >= van && tt.d <= tot) dagen.add(tt.d); }
  const reeks = [];
  for (const d of [...dagen].sort()) {
    let som = 0, ok = false;
    for (const [, l] of per) { let s = null; for (const tt of l) { if (tt.d <= d) s = tt.b; else break; } if (s != null) { som += s; ok = true; } }
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
  if (!bk.store.rek || typeof bk.store.rek !== 'object') bk.store.rek = {};
  // Oude opslag: alleen namen per rekening
  if (bk.store.namen && typeof bk.store.namen === 'object') { for (const [a, n] of Object.entries(bk.store.namen)) { (bk.store.rek[a] = bk.store.rek[a] || {}).naam = n; } delete bk.store.namen; }
  bkHerbereken();
  bkRekeningen();
  bkTijdBewaken();
  bkRender();
  bkNieuweRekeningen();
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
  if (bk.venster) { bk.venster = false; try { closeSheet(); } catch (e) { } try { if ($('#modal.on')) closeModal(null); } catch (e) { } const s = $('#sheet-sub'); if (s) s.textContent = ''; const tt = $('#sheet-title'); if (tt) tt.textContent = ''; }
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
    $('#bk-tijd').textContent = tn(min, 'Gaat op slot over {n} minuut zonder gebruik, en zodra je de app verlaat.', 'Gaat op slot over {n} minuten zonder gebruik, en zodra je de app verlaat.');
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
function bkTab(tt){
  bk.tab = tt;
  document.querySelectorAll('#s-bank .pctabs [role=tab]').forEach(b => { const on = b.dataset.tab === tt; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
  for (const p of ['overzicht', 'boekingen', 'ai', 'beheer']) $('#bk-p-' + p).hidden = p !== tt;
  $('#bk-filters').hidden = tt === 'beheer';
  bkRender();
}
function bkPeriode(){ $('#bk-eigen').hidden = $('#bk-periode').value !== 'eigen'; bk.lijstN = 100; bkRender(); }
function bkRekeningen(){
  const s = $('#bk-rek'), was = s.value, acc = [...new Set(bk.store.tx.map(tt => tt.acc))].sort();
  s.replaceChildren();
  const opt = (v, tt, ouder) => { const o = document.createElement('option'); o.value = v; o.textContent = tt; (ouder || s).appendChild(o); };
  const prive = acc.filter(a => bkRekSoort(a) === 'prive'), zak = acc.filter(a => bkRekSoort(a) === 'zakelijk');
  opt('alle', 'Alle rekeningen');
  if (prive.length && zak.length) { opt('prive', t('Alles privé ({n})', { n: prive.length })); opt('zakelijk', t('Alles zakelijk ({n})', { n: zak.length })); }
  for (const [label, l] of [['Privé', prive], ['Zakelijk', zak]]) {
    if (!l.length || acc.length < 2) continue;
    const g = document.createElement('optgroup'); g.label = label; s.appendChild(g);
    for (const a of l) opt(a, bkRekNaam(a), g);
  }
  s.value = [...s.options].some(o => o.value === was) ? was : 'alle';
  $('#bk-rek-rij').hidden = acc.length < 2;
}
function bkRekNaam(a){ const n = bk.store && bkRekInfo(a).naam; return (n ? n + ' · ' : '') + (a === 'onbekend' ? t('Rekening onbekend') : a.length > 8 ? a.slice(0, 4) + ' … ' + a.slice(-4) : a); }
/** Naam van wat je bekijkt, voor kopjes en de AI. */
function bkZichtNaam(){ const r = bkRekening(); return r === 'alle' ? t('alle rekeningen') : r === 'prive' ? t('alle privérekeningen') : r === 'zakelijk' ? t('alle zakelijke rekeningen') : t('rekening {naam} ({soort})', { naam: bkRekNaam(r), soort: t(bkRekSoort(r) === 'zakelijk' ? 'zakelijk' : 'privé') }); }

/** Nieuwe rekening in een afschrift: vragen of hij privé of zakelijk is (één voor één). */
function bkNieuweRekeningen(){
  if (!bk.store || bk.stilLaden || $('#modal.on')) return;
  const a = [...new Set(bk.store.tx.map(tt => tt.acc))].sort().find(x => x !== 'onbekend' && !bkRekInfo(x).soort);
  if (!a) return;
  bk.venster = true;
  const kies = soort => {
    bkRekZet(a, 'soort', soort); bkHerbereken();
    if (!bkSave() && bk.store) { bkRekZet(a, 'soort', soort); bkHerbereken(); bkSave(); } // na een tussentijdse import: keuze opnieuw zetten
    bkRekeningen(); bkRender(); setTimeout(bkNieuweRekeningen, 50);
  };
  askChoice(t('Privé of zakelijk?'), t('Rekening {naam} staat nieuw in je afschriften. Is dit een privérekening of een zakelijke rekening? Je kunt dit later wijzigen bij Beheer → Mijn rekeningen.', { naam: bkRekNaam(a) }), t('Privé'), t('Zakelijk'), () => kies('prive'), () => kies('zakelijk'));
}

function bkRender(){
  if (!bk.store) return;
  const leeg = !bk.store.tx.length;
  $('#bk-leeg').hidden = !leeg || bk.tab === 'beheer';
  bkAutoLijst();
  if (bk.tab === 'beheer') { bkBeheer(); return; }
  if (leeg) { $('#bk-overzicht').replaceChildren(); $('#bk-lijst').replaceChildren(); $('#bk-periode-txt').textContent = ''; return; }
  const [van, tot, label] = bkPeriodeVan();
  let laatste = ''; for (const tt of bk.store.tx) if (tt.d > laatste) laatste = tt.d;
  $('#bk-periode-txt').textContent = t(label) + ': ' + t('{van} t/m {tot}', { van: bkDatum(van), tot: bkDatum(tot) }) + (laatste ? ' · ' + t('nieuwste boeking {datum}', { datum: bkDatum(laatste) }) : '');
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
  return '<span class="' + (p === 0 ? 'bk-neutraal' : goed ? 'bk-plus' : 'bk-min') + '">' + t('{pct}% t.o.v. ervoor', { pct: (p > 0 ? '+' : p < 0 ? '−' : '') + Math.abs(p) }) + '</span>';
}
function bkOverzicht(van, tot){
  const lijst = bkSelectie(van, tot), tt = bkTotalen(lijst);
  const [pv, pt] = bkVorige(van, tot), vorig = bkTotalen(bkSelectie(pv, pt));
  const zak = bkZichtSoort() === 'zakelijk', over = zak ? tt.in - tt.uit : tt.netto;
  let h = '<div class="bk-kpis">'
    + '<div class="bk-kpi"><small>' + (zak ? 'Omzet en inkomsten' : 'Inkomsten') + '</small><b class="bk-plus">' + esc(bkEuro(tt.in)) + '</b>' + bkVerschil(tt.in, vorig.in, false) + '</div>'
    + '<div class="bk-kpi"><small>' + (zak ? 'Kosten' : 'Uitgaven') + '</small><b>' + esc(bkEuro(tt.uit)) + '</b>' + bkVerschil(tt.uit, vorig.uit, true) + '</div>'
    + '<div class="bk-kpi"><small>' + (zak ? 'Resultaat' : 'Over') + '</small><b class="' + (over >= 0 ? 'bk-plus' : 'bk-min') + '">' + esc(bkEuro(over, true)) + '</b><span>' + (tt.in > 0 ? t(zak ? 'Marge {pct}%' : 'Spaarquote {pct}%', { pct: Math.round((tt.in - tt.uit) / tt.in * 100) }) : '') + '</span></div>'
    + (zak ? '<div class="bk-kpi"><small>Naar privé</small><b>' + esc(bkEuro(tt.naarPrive)) + '</b><span>' + esc(tt.inbreng ? t('ingebracht {bedrag}', { bedrag: bkEuro(tt.inbreng) }) : t('{n} boekingen', { n: tt.n })) + '</span></div></div>'
      : '<div class="bk-kpi"><small>Gespaard en belegd</small><b>' + esc(bkEuro(tt.sparen)) + '</b><span>' + t('{n} boekingen', { n: tt.n }) + '</span></div></div>');
  // Alle rekeningen met privé én zakelijk: per soort en per rekening apart
  if (bkRekening() === 'alle' && bkZichtSoort() === '') {
    const rij = (naam, x, sub, klik) => '<' + (klik ? 'button class="bk-tx" onclick="' + klik + '"' : 'div class="bk-rij"') + '><span class="bk-naam"><b translate="no">' + esc(naam) + '</b><small>' + esc(sub) + '</small></span><span class="bk-bedrag ' + (x >= 0 ? 'bk-plus' : 'bk-min') + '">' + esc(bkEuro(x, true)) + '</span></' + (klik ? 'button' : 'div') + '>';
    let r = '';
    for (const soort of ['prive', 'zakelijk']) {
      const deel = lijst.filter(x => bkRekSoort(x.acc) === soort);
      // Binnen één soort telt geld naar de andere soort wél mee (privé-opname, uit eigen bedrijf)
      let inn = 0, uit = 0; for (const x of deel) { const so = bkCatSoort(bkCat(x)); if (so === 'neutraal' || so === 'sparen' || so === 'tussen') continue; if (so === 'in') inn += x.a; else uit -= x.a; }
      if (!deel.length) continue;
      r += rij(t(soort === 'prive' ? 'Privé' : 'Zakelijk'), inn - uit, t(soort === 'prive' ? 'inkomsten {in} · uitgaven {uit}' : 'omzet {in} · kosten {uit}', { in: bkEuro(inn), uit: bkEuro(uit) }), 'bkZicht(' + jsq(soort) + ')');
    }
    h += bkKaart('Privé en zakelijk', r + '<p class="note" style="margin-bottom:0">Geld tussen je privé- en zakelijke rekeningen telt in de totalen hierboven niet mee. Per soort: bij Privé telt het als inkomsten uit je eigen bedrijf (geld naar je bedrijf gaat ervan af), bij Zakelijk als privé-opname of inbreng (geen omzet of kosten). Tik voor het overzicht van één soort.</p>');
  }
  if (tt.naarPrive || tt.inbreng) {
    const tc = [...tt.cats.entries()].filter(([, x]) => x.soort === 'tussen');
    if (tc.length && !zak) h += bkKaart('Tussen privé en zakelijk', tc.map(([c, x]) => '<div class="bk-rij"><span class="bk-naam">' + esc(c) + '<small>' + t('{n}× · telt niet als uitgave', { n: x.n }) + '</small></span><b>' + esc(bkEuro(x.som, true)) + '</b></div>').join(''));
  }
  // Budgetten (deze maand)
  const budgets = Object.entries(bk.store.budget || {}).filter(([, v]) => v > 0);
  if (budgets.length) {
    const v = bkVandaag(), mt = bkTotalen(bkSelectie(v.slice(0, 7) + '-01', v));
    h += bkKaart('Budget deze maand', '<div class="bk-bars">' + budgets.map(([c, b]) => {
      const x = mt.cats.get(c), uit = x ? -x.som : 0, p = Math.min(100, Math.round(uit / b * 100));
      return '<button class="bk-bar" onclick="bkNaarCat(' + jsq(c) + ')"><div class="bk-bar-kop"><span>' + esc(c) + '</span><b>' + esc(t('{a} van {b}', { a: bkEuroKort(uit), b: bkEuroKort(b) })) + '</b></div>'
        + '<div class="bk-spoor" role="presentation"><div class="bk-vul' + (uit > b ? ' over' : '') + '" style="width:' + p + '%"></div></div><small>' + esc(uit > b ? t('{bedrag} te veel', { bedrag: bkEuroKort(uit - b) }) : t('{bedrag} over', { bedrag: bkEuroKort(b - uit) })) + '</small></button>';
    }).join('') + '</div>');
  }
  // Per maand
  const pm = bkPerMaand(lijst);
  if (pm.length > 1) h += bkKaart('Per maand', bkMaandGrafiek(pm.slice(-12)));
  // Uitgaven per categorie
  const uitCats = [...tt.cats.entries()].filter(([, x]) => x.soort === 'uit' && -x.som > 0).sort((a, b) => a[1].som - b[1].som);
  if (uitCats.length) {
    const max = -uitCats[0][1].som;
    h += bkKaart(zak ? 'Kosten per categorie' : 'Uitgaven per categorie', '<div class="bk-bars">' + uitCats.map(([c, x]) => {
      const was = vorig.cats.get(c), w = was ? -was.som : 0, nu = -x.som;
      return '<button class="bk-bar" onclick="bkNaarCat(' + jsq(c) + ')"><div class="bk-bar-kop"><span>' + esc(c) + '</span><b>' + esc(bkEuro(nu)) + '</b></div>'
        + '<div class="bk-spoor" role="presentation"><div class="bk-vul" style="width:' + Math.max(2, Math.round(nu / max * 100)) + '%"></div></div>'
        + '<small>' + esc(t(zak ? '{pct}% van de kosten' : '{pct}% van je uitgaven', { pct: Math.round(nu / Math.max(1, tt.uit) * 100) }) + ' · ' + x.n + '×' + (w ? ' · ' + t('ervoor {bedrag}', { bedrag: bkEuro(w) }) : '')) + '</small></button>';
    }).join('') + '</div>');
  }
  const inCats = [...tt.cats.entries()].filter(([, x]) => x.soort === 'in' && x.som !== 0).sort((a, b) => b[1].som - a[1].som);
  if (inCats.length) h += bkKaart(zak ? 'Omzet en inkomsten' : 'Inkomsten', inCats.map(([c, x]) => '<div class="bk-rij"><span class="bk-naam">' + esc(c) + '<small>' + t(x.som < 0 ? '{n} overboekingen' : '{n} bijschrijvingen', { n: x.n }) + '</small></span><b class="' + (x.som < 0 ? 'bk-min' : 'bk-plus') + '">' + esc(bkEuro(x.som, x.som < 0)) + '</b></div>').join(''));
  // Vaste lasten
  const vast = bkTerugkerend(), actief = vast.filter(v => !v.gestopt);
  if (vast.length) {
    const som = actief.reduce((s, v) => s + v.perMaand, 0);
    h += bkKaart('Vaste lasten en abonnementen', '<p class="note" style="margin-top:0">' + t('Samen ongeveer <b>{bedrag}</b> per maand ({n} terugkerende betalingen).', { bedrag: esc(bkEuro(som)), n: actief.length }) + '</p>'
      + vast.slice(0, 25).map(v => '<div class="bk-rij"><span class="bk-naam">' + esc(v.naam) + '<small>' + esc(t(v.cat) + ' · ' + t(v.soort) + ' · ' + (v.gestopt ? t('mogelijk gestopt (laatst {datum})', { datum: bkDatum(v.laatst) }) : t('volgende rond {datum}', { datum: fmtDate(v.volgende, { day: 'numeric', month: 'short' }) }))) + '</small></span><b' + (v.gestopt ? ' class="bk-neutraal"' : '') + '>' + esc(bkEuro(v.bedrag)) + '</b></div>').join(''));
  }
  // Grootste ontvangers
  const tp = bkTegenpartijen(lijst).slice(0, 10);
  if (tp.length) h += bkKaart('Waar gaat het meeste heen', tp.map(x => '<div class="bk-rij"><span class="bk-naam">' + esc(x.naam) + '<small>' + esc(x.cat) + ' · ' + x.n + '×</small></span><b>' + esc(bkEuro(x.som)) + '</b></div>').join(''));
  // Grootste uitgaven
  const groot = lijst.filter(x => x.a < 0 && bkSoort(x) === 'uit').sort((a, b) => a.a - b.a).slice(0, 8);
  if (groot.length) h += bkKaart('Grootste uitgaven', groot.map(x => '<button class="bk-tx" onclick="bkTx(' + jsq(x.id) + ')"><span class="bk-naam"><b>' + esc(x.cp || x.ci || 'Onbekend') + '</b><small>' + esc(bkDatum(x.d)) + ' · ' + esc(bkCat(x)) + '</small></span><span class="bk-bedrag">' + esc(bkEuro(x.a)) + '</span></button>').join(''));
  // Saldo
  const reeks = bkSaldoReeks(van, tot);
  if (reeks.length > 1) h += bkKaart('Saldo', bkLijnGrafiek(reeks) + '<p class="note" style="margin-bottom:0">' + t('Laatste saldo: <b>{bedrag}</b> op {datum}.', { bedrag: esc(bkEuro(reeks[reeks.length - 1][1])), datum: esc(bkDatum(reeks[reeks.length - 1][0])) }) + '</p>');
  // Per dag van de week
  const wd = [0, 0, 0, 0, 0, 0, 0];
  for (const x of lijst) if (x.a < 0 && bkSoort(x) === 'uit') wd[(new Date(x.d + 'T12:00:00').getDay() + 6) % 7] -= x.a;
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
    s += '<rect x="' + (x0 + 2).toFixed(1) + '" y="' + (H - B - hi).toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + hi.toFixed(1) + '" rx="2" fill="var(--ok)"><title>' + esc(t('{maand} inkomsten {bedrag}', { maand: bkMaandNaam(m), bedrag: bkEuro(x.in) })) + '</title></rect>';
    s += '<rect x="' + (x0 + 2 + w + 1).toFixed(1) + '" y="' + (H - B - hu).toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + hu.toFixed(1) + '" rx="2" fill="var(--bad)"><title>' + esc(t('{maand} uitgaven {bedrag}', { maand: bkMaandNaam(m), bedrag: bkEuro(x.uit) })) + '</title></rect>';
    if (n <= 12) s += '<text x="' + (x0 + bw / 2).toFixed(1) + '" y="' + (H - 6) + '" text-anchor="middle">' + esc(t(BK_MAANDEN[Number(m.slice(5, 7)) - 1])) + '</text>';
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
  return '<svg class="bk-grafiek" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + esc(t('Saldoverloop van {van} naar {naar}', { van: bkEuro(vals[0]), naar: bkEuro(vals[vals.length - 1]) })) + '">'
    + '<path d="' + d + '" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linejoin="round"/>'
    + '<text x="' + P + '" y="' + (H - 2) + '">' + esc(bkEuroKort(lo)) + '</text><text x="' + (W - P) + '" y="' + (H - 2) + '" text-anchor="end">' + esc(t('hoogst {bedrag}', { bedrag: bkEuroKort(hi) })) + '</text></svg>';
}
function bkNaarCat(c){ bkTab('boekingen'); bkCatSelect(); $('#bk-cat').value = c; bkLijstRender(); }

// ---------- boekingen ----------
function bkCatSelect(){
  const s = $('#bk-cat'), was = s.value;
  s.replaceChildren();
  const opt = (v, tt) => { const o = document.createElement('option'); o.value = v; o.textContent = tt; s.appendChild(o); };
  opt('', 'Alle categorieën'); opt('*zonder', 'Zonder categorie (overig)');
  const [van, tot] = bkPeriodeVan(), cats = bkCatsVoor(bkZichtSoort());
  for (const tt of bkSelectie(van, tot)) { const c = bkCat(tt); if (!cats.includes(c)) cats.push(c); } // ook wat er echt in staat
  for (const c of cats) opt(c, c);
  s.value = [...s.options].some(o => o.value === was) ? was : '';
}
function bkGefilterd(){
  const [van, tot] = bkPeriodeVan(), q = $('#bk-zoek').value.trim().toLowerCase(), cat = $('#bk-cat').value, sort = $('#bk-sort').value;
  const qBedrag = /^[-−€\s\d.,\u202f]+$/.test(q) ? parseNum(q) : null; // een bedrag zoeken, volgens Regio en taal
  let l = bkSelectie(van, tot).filter(tt => {
    const c = bkCat(tt);
    if (cat === '*zonder' ? !BK_OVERIG.has(c) : cat && c !== cat) return false;
    if (!q) return true;
    if (qBedrag != null && Math.abs(Math.abs(tt.a) / 100 - Math.abs(qBedrag)) < 0.005) return true;
    return ((tt.cp || '') + ' ' + (tt.ds || '') + ' ' + (tt.ci || '') + ' ' + c + ' ' + (tt.n || '')).toLowerCase().includes(q);
  });
  l.sort(sort === 'oud' ? (a, b) => a.d < b.d ? -1 : a.d > b.d ? 1 : 0 : sort === 'hoog' ? (a, b) => a.a - b.a : sort === 'bij' ? (a, b) => b.a - a.a : (a, b) => a.d > b.d ? -1 : a.d < b.d ? 1 : 0);
  return l;
}
function bkLijstRender(){
  if (!bk.store) return;
  const l = bkGefilterd(), toon = l.slice(0, bk.lijstN);
  let som = 0; for (const tt of l) som += tt.a;
  $('#bk-lijst-telling').textContent = tn(l.length, '{n} boeking', '{n} boekingen') + ' · ' + t('samen {bedrag}', { bedrag: bkEuro(som, true) });
  let h = '', dag = '';
  const opDag = $('#bk-sort').value === 'nieuw' || $('#bk-sort').value === 'oud';
  for (const tt of toon) {
    if (opDag && tt.d !== dag) { dag = tt.d; h += '<div class="bk-dag">' + esc(bkDatum(tt.d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })) + '</div>'; }
    h += '<button class="bk-tx" onclick="bkTx(' + jsq(tt.id) + ')"><span class="bk-naam"><b translate="no">' + esc(tt.cp || tt.ci || t('Onbekend')) + '</b><small>' + esc(t(bkCat(tt))) + (opDag ? '' : ' · ' + esc(bkDatum(tt.d))) + (tt.n ? ' · ' + esc(tt.n) : tt.ds ? ' · ' + esc(tt.ds) : '') + '</small></span><span class="bk-bedrag ' + (tt.a > 0 ? 'bk-plus' : '') + '">' + esc(bkEuro(tt.a, true)) + '</span></button>';
  }
  $('#bk-lijst').innerHTML = h || '<p class="note" style="margin:8px 0">Geen boekingen gevonden.</p>';
  $('#bk-meer').hidden = l.length <= bk.lijstN;
}
function bkMeer(){ bk.lijstN += 200; bkLijstRender(); }
function bkVind(id){ return bk.store && bk.store.tx.find(tt => tt.id === id); }
function bkTx(id){
  const tt = bkVind(id); if (!tt) return;
  const regels = [bkDatum(tt.d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }), bkEuro(tt.a, true) + ' · ' + bkCat(tt)];
  if (tt.ci) regels.push(t('Tegenrekening {iban}', { iban: tt.ci }));
  if (tt.ds) regels.push(tt.ds.slice(0, 300));
  if (tt.ty) regels.push(tt.ty);
  regels.push(t('Rekening {naam}', { naam: bkRekNaam(tt.acc) }) + (tt.src ? ' · ' + tt.src : ''));
  if (tt.n) regels.push(t('Notitie: {tekst}', { tekst: tt.n }));
  bk.venster = true;
  openSheet(tt.cp || tt.ci || 'Boeking', regels.join('\n'), [
    ['Categorie wijzigen', () => bkKiesCat(id, false), 'edit'],
    [t('Altijd zo indelen: {naam}', { naam: bkSleutel(tt).slice(0, 30) || t('deze naam') }), () => bkKiesCat(id, true), 'repeat'],
    [tt.n ? 'Notitie wijzigen' : 'Notitie toevoegen', () => askInput('Notitie', 'Alleen voor jezelf, versleuteld bewaard.', tt.n || '', 'Bewaren', v => { tt.n = String(v || '').slice(0, 200); if (!tt.n) delete tt.n; bkSave(); bkRender(); }), 'notes'],
    ['Andere boekingen van deze naam', () => { bkTab('boekingen'); $('#bk-cat').value = ''; $('#bk-zoek').value = (tt.cp || tt.ci || '').slice(0, 40); bkLijstRender(); }, 'search']
  ]);
}
function bkKiesCat(id, regel){
  const tt = bkVind(id); if (!tt) return;
  const soortRek = bkRekSoort(tt.acc);
  const cats = bkCatsVoor(soortRek).filter(c => bkCatSoort(c) === 'neutraal' || (tt.a > 0 ? true : bkCatSoort(c) !== 'in'));
  const acts = cats.map(c => [c, () => {
    if (regel) {
      const p = bkSleutel(tt);
      if (!p || p === '?') { toast('Deze boeking heeft geen naam om een regel op te maken'); return; }
      bk.store.rules = bk.store.rules.filter(g => !(g.p === p && (g.t || '') === (tt.a > 0 ? '+' : '-') && (g.s || '') === soortRek));
      bk.store.rules.unshift({ p, cat: c, t: tt.a > 0 ? '+' : '-', s: soortRek });
      delete tt.c;
    } else tt.c = c;
    bkHerbereken();
    if (bkSave()) toast(regel ? t('Regel bewaard: “{tekst}” → {categorie}', { tekst: p0(tt), categorie: t(c) }) : t('Categorie gewijzigd'));
    bkRender();
  }]);
  acts.push(['Nieuwe categorie…', () => bkNieuweCat(c => { if (regel) { bk.store.rules.unshift({ p: bkSleutel(tt), cat: c, t: tt.a > 0 ? '+' : '-', s: soortRek }); } else tt.c = c; bkHerbereken(); bkSave(); bkRender(); }), 'plus']);
  if (tt.c && !regel) acts.push(['Automatisch laten indelen', () => { delete tt.c; bkHerbereken(); bkSave(); bkRender(); }, 'refresh']);
  bk.venster = true;
  openSheet(regel ? 'Altijd indelen als…' : 'Categorie', regel ? t(soortRek === 'zakelijk' ? 'Voor alles met “{tekst}” in de naam of omschrijving, op je zakelijke rekeningen' : 'Voor alles met “{tekst}” in de naam of omschrijving, op je privérekeningen', { tekst: p0(tt) }) : '', acts);
}
const p0 = tt => bkSleutel(tt).slice(0, 40);
function bkNieuweCat(klaar){
  askInput('Nieuwe categorie', 'Een eigen categorie voor uitgaven.', '', 'Toevoegen', v => {
    const n = String(v || '').trim().slice(0, 40);
    if (!n) return;
    if (!bkAlleCats().includes(n)) bk.store.cats.push({ naam: n, soort: 'uit' }); // eigen categorie: voor privé en zakelijk
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
  bk.stilLaden = true; bkLaad(); bk.stilLaden = false;
  const fs = r.bestanden || [];
  let nieuw = 0, dubbel = 0; const fout = [];
  for (const f of fs) { if (f.ok && f.sum) { nieuw += f.sum.nieuw || 0; dubbel += f.sum.dubbel || 0; } else fout.push(f.naam + ': ' + t(f.fout || 'niet gelukt')); }
  const tekst = t('{n} nieuwe boekingen', { n: nieuw }) + (dubbel ? ', ' + t('{n} stonden er al', { n: dubbel }) : '') + (r.gewist ? '. ' + tn(r.gewist, '{n} bestand van de telefoon verwijderd', '{n} bestanden van de telefoon verwijderd') : '') + (fout.length ? '.\n\n' + t('Niet gelukt:') + '\n' + fout.join('\n') : '.');
  bk.venster = true;
  askConfirm('Afschrift geïmporteerd', tekst, 'OK', () => setTimeout(bkNieuweRekeningen, 50), () => setTimeout(bkNieuweRekeningen, 50));
};
function bkKoppel(id){
  const tx = bk.store ? bk.store.tx : [];
  const code = id === 'rabobank' ? 'RABO' : '';
  let laatst = '';
  for (const tt of tx) if ((code && tt.acc.slice(4, 8) === code) || tt.src === 'Rabobank') { if (tt.d > laatst) laatst = tt.d; }
  let van;
  const d = new Date((laatst || bkVandaag()) + 'T12:00:00');
  if (laatst) d.setDate(d.getDate() - 5); else d.setMonth(d.getMonth() - 13);
  van = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const r = Android.bankKoppel(id, van);
  if (r) toast(r);
}
function bkKoppelKlaar(u){
  let r; try { r = JSON.parse(u); } catch (e) { return; }
  if (r.wacht) { toast(r.bank ? t('Opgehaald bij {bank}. Ontgrendel Bankzaken om het af te ronden.', { bank: r.bank }) : t('Opgehaald bij de bank. Ontgrendel Bankzaken om het af te ronden.')); return; }
  const f = (r.bestanden || [])[0];
  if (f && f.ok && f.sum) toast((r.bank || t('Bank')) + ': ' + t('{n} nieuwe boekingen', { n: f.sum.nieuw }) + (f.sum.dubbel ? ' ' + t('({n} stonden er al)', { n: f.sum.dubbel }) : ''));
}
function bkAutoLijst(){
  let banken = []; try { banken = JSON.parse(Android.bankBanken()); } catch (e) { }
  const auto = banken.filter(b => b.robot);
  const h = auto.map(b => '<div class="bk-bank"><span><b>' + esc(b.naam) + '</b><small>' + esc(b.appNaam ? t('Inloggen met de {app}', { app: b.appNaam }) : t('Inloggen met de app van je bank')) + '</small></span><button class="mini" style="float:none" onclick="bkKoppel(' + jsq(b.id) + ')">Ophalen</button></div>').join('');
  document.querySelectorAll('#s-bank .bk-auto-lijst').forEach(e => { e.innerHTML = h; });
  const u = $('#bk-uitleg');
  if (u) u.innerHTML = banken.map(b => '<div class="bk-res"><b>' + esc(b.naam) + '</b><br>' + esc(b.uitleg) + '</div>').join('');
}

// ---------- beheer ----------
function bkBeheer(){
  const s = bk.store;
  const zonder = s.tx.filter(tt => BK_OVERIG.has(bkCat(tt)));
  $('#bk-zonder').textContent = zonder.length ? t('{n} boekingen staan nog bij Overig. De lokale AI kan voorstellen maken voor de namen die het vaakst voorkomen; jij kiest wat je overneemt.', { n: zonder.length }) : t('Alle boekingen zijn ingedeeld.');
  $('#bk-ai-cat').hidden = !zonder.length;
  $('#bk-regels').innerHTML = s.rules.length ? s.rules.map((g, i) => '<div class="bk-rij"><span class="bk-naam">“' + esc(g.p) + '”<small>' + esc(t(g.t === '+' ? 'bijschrijvingen' : g.t === '-' ? 'afschrijvingen' : 'alles') + ' → ' + t(g.cat) + (g.s ? ' · ' + t(g.s === 'zakelijk' ? 'zakelijk' : 'privé') : '')) + '</small></span><button class="mini" style="float:none" onclick="bkRegelWeg(' + i + ')" aria-label="Regel verwijderen">Weg</button></div>').join('') : '<p class="note" style="margin:0">Nog geen eigen regels.</p>';
  $('#bk-budgetten').innerHTML = Object.entries(s.budget).filter(([, v]) => v > 0).map(([c, v]) => '<div class="bk-rij"><span class="bk-naam">' + esc(c) + '<small>per maand</small></span><b>' + esc(bkEuroKort(v)) + '</b><button class="mini" style="float:none" onclick="bkBudgetWeg(' + jsq(c) + ')">Weg</button></div>').join('');
  $('#bk-rekeningen').innerHTML = bkAlleRek().map(r => '<div class="bk-rij"><span class="bk-naam">' + esc(bkRekNaam(r.iban)) + '<small>' + esc(r.iban + ' · ' + t(bkRekSoort(r.iban) === 'zakelijk' ? 'zakelijk' : 'privé') + (bkRekInfo(r.iban).soort ? '' : ' ' + t('(nog niet gekozen)')) + ' · ' + t(r.eigen ? 'zelf toegevoegd' : 'geïmporteerd')) + '</small></span><button class="mini" style="float:none" onclick="bkRekMenu(' + jsq(r.iban) + ')">Wijzigen</button></div>').join('');
  $('#bk-imports').innerHTML = s.imports.length ? '<details class="rinfo"><summary>' + t('Eerdere imports ({n})', { n: s.imports.length }) + '</summary>' + s.imports.slice().reverse().slice(0, 30).map(i => '<div class="bk-res">' + esc(fmtDate(i.t, { day: 'numeric', month: 'short', year: 'numeric' })) + ' · ' + esc(i.bank || i.format) + ' · ' + esc(i.van ? t('{van} t/m {tot}', { van: bkDatum(i.van), tot: bkDatum(i.tot) }) : '') + ' · ' + t('{n} nieuw', { n: i.nieuw || 0 }) + '</div>').join('') + '</details>' : '';
  const st = bk.st || {};
  const veilig = [
    t(st.hardware ? 'Versleuteld met AES-256-GCM; de sleutel zit in de beveiligde hardware van deze telefoon en kan er niet uit.' : 'Versleuteld met AES-256-GCM; de sleutel zit in de beveiligde sleutelopslag van Android en kan er niet uit.'),
    st.ontgrendeling ? 'De telefoon zelf geeft de sleutel alleen vrij tot 15 minuten na je vingerafdruk of pincode.' : 'Openen alleen na je vingerafdruk of pincode (deze telefoon koppelt de sleutel niet aan de ontgrendeling zelf).',
    'Gaat op slot na 5 minuten niets doen en zodra je de app verlaat; dan is alles ook uit het geheugen.',
    'Geen schermafdrukken of schermopnames, en niet zichtbaar in "recente apps".',
    t('Standaard niet in backups. Alleen na je eigen keuze bij Alles back-uppen → Hoog risico in een versleutelde kopie. Nooit in de Android-backup, zoeken, de assistent of statistieken.'),
    'De lokale AI draait op deze telefoon, onthoudt niets en bewaart geen gesprekken.',
    'Automatisch ophalen praat alleen met de website van je bank (https); je logt zelf in met de app van je bank en na afloop wordt de sessie gewist.',
    'Wissen haalt ook de sleutel weg: daarna is niets meer terug te halen.'];
  $('#bk-veilig').innerHTML = veilig.map(v => '<li>' + esc(v) + '</li>').join('');
}
function bkRegelWeg(i){ bk.store.rules.splice(i, 1); bkHerbereken(); bkSave(); bkRender(); }
function bkBudgetWeg(c){ delete bk.store.budget[c]; bkSave(); bkRender(); }
function bkBudgetNieuw(){
  const cats = bkAlleCats().filter(c => bkCatSoort(c) === 'uit');
  openSheet('Budget voor…', '', cats.map(c => [t(c), () => askInput(t('Budget {categorie}', { categorie: t(c) }), 'Bedrag per maand in euro.', bk.store.budget[c] ? fmtNumIn(bk.store.budget[c] / 100, 2) : '', 'Bewaren', v => {
    const p = parseNum(v), n = p == null ? NaN : Math.round(p * 100); // "12,50", "12.50" en "1.250" (Regio en taal)
    if (!(n > 0) || n > 1e10) { toast('Vul een bedrag in'); return; }
    bk.store.budget[c] = n; bkSave(); bkRender();
  })]));
}
function bkRekHernoem(a){ askInput('Naam van de rekening', a, bkRekInfo(a).naam || '', 'Bewaren', v => { const n = String(v || '').trim().slice(0, 40); bkRekZet(a, 'naam', n); bkSave(); bkRekeningen(); bkRender(); }); }
function bkRekSoortZet(a, soort){ bkRekZet(a, 'soort', soort); bkHerbereken(); bkRekeningen(); if (bkSave()) toast(t(soort === 'zakelijk' ? '{naam} is nu zakelijk' : '{naam} is nu privé', { naam: bkRekNaam(a) })); bkRender(); }
function bkRekMenu(a){
  const soort = bkRekSoort(a), eigen = (bk.store.eigen || []).findIndex(e => e.iban === a);
  const acts = [['Naam wijzigen', () => bkRekHernoem(a), 'edit'],
    [(soort === 'prive' ? '✓ ' : '') + t('Privérekening'), () => bkRekSoortZet(a, 'prive')],
    [(soort === 'zakelijk' ? '✓ ' : '') + t('Zakelijke rekening'), () => bkRekSoortZet(a, 'zakelijk')]];
  if (eigen >= 0) acts.push(['Weghalen', () => bkEigenWeg(eigen), 'trash']);
  bk.venster = true;
  openSheet(bkRekNaam(a), a, acts);
}
function bkEigenNieuw(){
  askInput('Eigen rekening', 'IBAN van je eigen rekening (bijv. een spaarrekening of een zakelijke rekening die je niet importeert).', '', 'Toevoegen', v => {
    const iban = String(v || '').replace(/\s+/g, '').toUpperCase();
    if (!/^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$/.test(iban)) { toast('Dat lijkt geen IBAN'); return; }
    const kies = soort => {
      if (!bk.store.eigen.some(e => e.iban === iban) && !bk.store.tx.some(tt => tt.acc === iban)) bk.store.eigen.push({ iban, naam: '', soort });
      else bkRekZet(iban, 'soort', soort);
      bkHerbereken(); bkRekeningen(); bkSave(); bkRender();
    };
    setTimeout(() => askChoice(t('Privé of zakelijk?'), t('Is {iban} een privérekening of een zakelijke rekening?', { iban: iban }), t('Privé'), t('Zakelijk'), () => kies('prive'), () => kies('zakelijk')), 50);
  });
}
function bkEigenWeg(i){ bk.store.eigen.splice(i, 1); bkHerbereken(); bkRekeningen(); bkSave(); bkRender(); }
/** Vanuit het overzicht naar alleen privé of alleen zakelijk. */
function bkZicht(soort){ const s = $('#bk-rek'); if ([...s.options].some(o => o.value === soort)) { s.value = soort; bk.lijstN = 100; bkRender(); scrollTo(0, 0); } }
function bkWis(){
  askConfirm('Alle bankgegevens wissen?', 'Alle afschriften, regels, budgetten en de sleutel worden van deze telefoon verwijderd. Dit kan niet ongedaan worden gemaakt.', 'Wissen', () => {
    if (!bk.st || !bk.st.open) { toast('Ontgrendel eerst; daarna kun je wissen'); return; }
    const r = Android.bankWipe();
    if (r) { toast(r); return; }
    bkVergeet(); bkToestand(); toast('Alle bankgegevens zijn gewist');
  });
}
