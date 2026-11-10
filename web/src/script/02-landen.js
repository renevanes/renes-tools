/* ---------- Landen en nationaliteiten (keuzelijsten, bijv. in Stamgegevens) ----------
 * Eén rij per land: [ISO 3166 alpha-2, alpha-3 (zoals in de strook van paspoort en identiteitskaart), land, nationaliteit].
 * Land en nationaliteit staan in het Nederlands, net als wat Rene zelf invult en wat Mrz.java herkent (Mrz.COUNTRIES moet
 * hiermee kloppen; tests/ui/landen.test.js controleert dat). Opgeslagen wordt altijd die Nederlandse tekst; in het Engels
 * toont de app de naam van het land via Intl en de nationaliteit via t() (web/src/taal/en.json).
 * Een lege nationaliteit: een gebied met een eigen landnaam maar zonder eigen nationaliteit (Aruba: Nederlandse). */
const LANDEN = [
  ['AF', 'AFG', 'Afghanistan', 'Afghaanse'], ['AL', 'ALB', 'Albanië', 'Albanese'], ['DZ', 'DZA', 'Algerije', 'Algerijnse'],
  ['AD', 'AND', 'Andorra', 'Andorrese'], ['AO', 'AGO', 'Angola', 'Angolese'], ['AG', 'ATG', 'Antigua en Barbuda', 'Antiguaanse'],
  ['AR', 'ARG', 'Argentinië', 'Argentijnse'], ['AM', 'ARM', 'Armenië', 'Armeense'], ['AW', 'ABW', 'Aruba', ''],
  ['AU', 'AUS', 'Australië', 'Australische'], ['AZ', 'AZE', 'Azerbeidzjan', 'Azerbeidzjaanse'], ['BS', 'BHS', 'Bahama’s', 'Bahamaanse'], ['BM', 'BMU', 'Bermuda', ''],
  ['BH', 'BHR', 'Bahrein', 'Bahreinse'], ['BD', 'BGD', 'Bangladesh', 'Bengalese'], ['BB', 'BRB', 'Barbados', 'Barbadaanse'],
  ['BY', 'BLR', 'Belarus', 'Belarussische'], ['BE', 'BEL', 'België', 'Belgische'], ['BZ', 'BLZ', 'Belize', 'Belizaanse'],
  ['BJ', 'BEN', 'Benin', 'Beninse'], ['BT', 'BTN', 'Bhutan', 'Bhutaanse'], ['BO', 'BOL', 'Bolivia', 'Boliviaanse'],
  ['BA', 'BIH', 'Bosnië en Herzegovina', 'Bosnische'], ['BW', 'BWA', 'Botswana', 'Botswaanse'], ['BR', 'BRA', 'Brazilië', 'Braziliaanse'],
  ['BN', 'BRN', 'Brunei', 'Bruneise'], ['BG', 'BGR', 'Bulgarije', 'Bulgaarse'], ['BF', 'BFA', 'Burkina Faso', 'Burkinese'],
  ['BI', 'BDI', 'Burundi', 'Burundese'], ['KH', 'KHM', 'Cambodja', 'Cambodjaanse'], ['CA', 'CAN', 'Canada', 'Canadese'],
  ['BQ', 'BES', 'Caribisch Nederland', ''], ['CF', 'CAF', 'Centraal-Afrikaanse Republiek', 'Centraal-Afrikaanse'],
  ['CL', 'CHL', 'Chili', 'Chileense'], ['CN', 'CHN', 'China', 'Chinese'], ['CO', 'COL', 'Colombia', 'Colombiaanse'],
  ['KM', 'COM', 'Comoren', 'Comorese'], ['CG', 'COG', 'Congo-Brazzaville', 'Congolese (Brazzaville)'],
  ['CD', 'COD', 'Congo-Kinshasa', 'Congolese (Kinshasa)'], ['CR', 'CRI', 'Costa Rica', 'Costa Ricaanse'], ['CU', 'CUB', 'Cuba', 'Cubaanse'],
  ['CW', 'CUW', 'Curaçao', ''], ['CY', 'CYP', 'Cyprus', 'Cyprische'], ['DK', 'DNK', 'Denemarken', 'Deense'],
  ['DJ', 'DJI', 'Djibouti', 'Djiboutiaanse'], ['DM', 'DMA', 'Dominica', 'Dominicaanse (Dominica)'],
  ['DO', 'DOM', 'Dominicaanse Republiek', 'Dominicaanse (Dominicaanse Republiek)'], ['DE', 'DEU', 'Duitsland', 'Duitse'],
  ['EC', 'ECU', 'Ecuador', 'Ecuadoraanse'], ['EG', 'EGY', 'Egypte', 'Egyptische'], ['SV', 'SLV', 'El Salvador', 'Salvadoraanse'],
  ['GQ', 'GNQ', 'Equatoriaal-Guinea', 'Equatoriaal-Guinese'], ['ER', 'ERI', 'Eritrea', 'Eritrese'], ['EE', 'EST', 'Estland', 'Estische'],
  ['SZ', 'SWZ', 'Eswatini', 'Swazische'], ['ET', 'ETH', 'Ethiopië', 'Ethiopische'], ['FJ', 'FJI', 'Fiji', 'Fijische'],
  ['PH', 'PHL', 'Filipijnen', 'Filipijnse'], ['FO', 'FRO', 'Faeröer', ''], ['FI', 'FIN', 'Finland', 'Finse'], ['PF', 'PYF', 'Frans-Polynesië', ''], ['FR', 'FRA', 'Frankrijk', 'Franse'],
  ['GA', 'GAB', 'Gabon', 'Gabonese'], ['GM', 'GMB', 'Gambia', 'Gambiaanse'], ['GE', 'GEO', 'Georgië', 'Georgische'],
  ['GH', 'GHA', 'Ghana', 'Ghanese'], ['GD', 'GRD', 'Grenada', 'Grenadaanse'], ['GR', 'GRC', 'Griekenland', 'Griekse'],
  ['GT', 'GTM', 'Guatemala', 'Guatemalteekse'], ['GN', 'GIN', 'Guinee', 'Guinese'], ['GW', 'GNB', 'Guinee-Bissau', 'Guinee-Bissause'],
  ['GI', 'GIB', 'Gibraltar', ''], ['GL', 'GRL', 'Groenland', ''], ['GY', 'GUY', 'Guyana', 'Guyaanse'], ['HT', 'HTI', 'Haïti', 'Haïtiaanse'], ['HN', 'HND', 'Honduras', 'Hondurese'],
  ['HK', 'HKG', 'Hongkong', ''], ['HU', 'HUN', 'Hongarije', 'Hongaarse'], ['IE', 'IRL', 'Ierland', 'Ierse'],
  ['IS', 'ISL', 'IJsland', 'IJslandse'], ['IN', 'IND', 'India', 'Indiase'], ['ID', 'IDN', 'Indonesië', 'Indonesische'],
  ['IQ', 'IRQ', 'Irak', 'Iraakse'], ['IR', 'IRN', 'Iran', 'Iraanse'], ['IL', 'ISR', 'Israël', 'Israëlische'],
  ['IT', 'ITA', 'Italië', 'Italiaanse'], ['CI', 'CIV', 'Ivoorkust', 'Ivoriaanse'], ['JM', 'JAM', 'Jamaica', 'Jamaicaanse'],
  ['JP', 'JPN', 'Japan', 'Japanse'], ['YE', 'YEM', 'Jemen', 'Jemenitische'], ['JO', 'JOR', 'Jordanië', 'Jordaanse'],
  ['CV', 'CPV', 'Kaapverdië', 'Kaapverdische'], ['CM', 'CMR', 'Kameroen', 'Kameroense'], ['KZ', 'KAZ', 'Kazachstan', 'Kazachse'],
  ['KE', 'KEN', 'Kenia', 'Keniaanse'], ['KG', 'KGZ', 'Kirgizië', 'Kirgizische'], ['KI', 'KIR', 'Kiribati', 'Kiribatische'],
  ['KW', 'KWT', 'Koeweit', 'Koeweitse'], ['XK', 'RKS', 'Kosovo', 'Kosovaarse'], ['HR', 'HRV', 'Kroatië', 'Kroatische'],
  ['LA', 'LAO', 'Laos', 'Laotiaanse'], ['LS', 'LSO', 'Lesotho', 'Lesothaanse'], ['LV', 'LVA', 'Letland', 'Letse'],
  ['LB', 'LBN', 'Libanon', 'Libanese'], ['LR', 'LBR', 'Liberia', 'Liberiaanse'], ['LY', 'LBY', 'Libië', 'Libische'],
  ['LI', 'LIE', 'Liechtenstein', 'Liechtensteinse'], ['LT', 'LTU', 'Litouwen', 'Litouwse'], ['LU', 'LUX', 'Luxemburg', 'Luxemburgse'],
  ['MO', 'MAC', 'Macau', ''], ['MG', 'MDG', 'Madagaskar', 'Malagassische'], ['MW', 'MWI', 'Malawi', 'Malawische'],
  ['MV', 'MDV', 'Maldiven', 'Maldivische'], ['MY', 'MYS', 'Maleisië', 'Maleisische'], ['ML', 'MLI', 'Mali', 'Malinese'],
  ['MT', 'MLT', 'Malta', 'Maltese'], ['MA', 'MAR', 'Marokko', 'Marokkaanse'], ['MH', 'MHL', 'Marshalleilanden', 'Marshalleilandse'],
  ['MR', 'MRT', 'Mauritanië', 'Mauritaanse'], ['MU', 'MUS', 'Mauritius', 'Mauritiaanse'], ['MX', 'MEX', 'Mexico', 'Mexicaanse'],
  ['FM', 'FSM', 'Micronesia', 'Micronesische'], ['MD', 'MDA', 'Moldavië', 'Moldavische'], ['MC', 'MCO', 'Monaco', 'Monegaskische'],
  ['MN', 'MNG', 'Mongolië', 'Mongoolse'], ['ME', 'MNE', 'Montenegro', 'Montenegrijnse'], ['MZ', 'MOZ', 'Mozambique', 'Mozambikaanse'],
  ['MM', 'MMR', 'Myanmar', 'Myanmarese'], ['NA', 'NAM', 'Namibië', 'Namibische'], ['NR', 'NRU', 'Nauru', 'Nauruaanse'],
  ['NL', 'NLD', 'Nederland', 'Nederlandse'], ['NP', 'NPL', 'Nepal', 'Nepalese'], ['NI', 'NIC', 'Nicaragua', 'Nicaraguaanse'],
  ['NC', 'NCL', 'Nieuw-Caledonië', ''], ['NZ', 'NZL', 'Nieuw-Zeeland', 'Nieuw-Zeelandse'], ['NE', 'NER', 'Niger', 'Nigerese'], ['NG', 'NGA', 'Nigeria', 'Nigeriaanse'],
  ['KP', 'PRK', 'Noord-Korea', 'Noord-Koreaanse'], ['MK', 'MKD', 'Noord-Macedonië', 'Noord-Macedonische'], ['NO', 'NOR', 'Noorwegen', 'Noorse'],
  ['UG', 'UGA', 'Oeganda', 'Oegandese'], ['UA', 'UKR', 'Oekraïne', 'Oekraïense'], ['UZ', 'UZB', 'Oezbekistan', 'Oezbeekse'],
  ['OM', 'OMN', 'Oman', 'Omaanse'], ['AT', 'AUT', 'Oostenrijk', 'Oostenrijkse'], ['TL', 'TLS', 'Oost-Timor', 'Oost-Timorese'],
  ['PK', 'PAK', 'Pakistan', 'Pakistaanse'], ['PW', 'PLW', 'Palau', 'Palause'], ['PS', 'PSE', 'Palestina', 'Palestijnse'],
  ['PA', 'PAN', 'Panama', 'Panamese'], ['PG', 'PNG', 'Papoea-Nieuw-Guinea', 'Papoea-Nieuw-Guinese'], ['PY', 'PRY', 'Paraguay', 'Paraguayaanse'],
  ['PE', 'PER', 'Peru', 'Peruaanse'], ['PL', 'POL', 'Polen', 'Poolse'], ['PT', 'PRT', 'Portugal', 'Portugese'],
  ['PR', 'PRI', 'Puerto Rico', ''], ['QA', 'QAT', 'Qatar', 'Qatarese'], ['RO', 'ROU', 'Roemenië', 'Roemeense'],
  ['RU', 'RUS', 'Rusland', 'Russische'], ['RW', 'RWA', 'Rwanda', 'Rwandese'], ['KN', 'KNA', 'Saint Kitts en Nevis', 'Kittitiaanse'],
  ['LC', 'LCA', 'Saint Lucia', 'Saint Luciaanse'], ['VC', 'VCT', 'Saint Vincent en de Grenadines', 'Vincentiaanse'],
  ['SB', 'SLB', 'Salomonseilanden', 'Salomonseilandse'], ['WS', 'WSM', 'Samoa', 'Samoaanse'], ['SM', 'SMR', 'San Marino', 'San Marinese'],
  ['ST', 'STP', 'Sao Tomé en Principe', 'Santomese'], ['SA', 'SAU', 'Saoedi-Arabië', 'Saoedi-Arabische'], ['SN', 'SEN', 'Senegal', 'Senegalese'],
  ['RS', 'SRB', 'Servië', 'Servische'], ['SC', 'SYC', 'Seychellen', 'Seychelse'], ['SL', 'SLE', 'Sierra Leone', 'Sierra Leoonse'],
  ['SG', 'SGP', 'Singapore', 'Singaporese'], ['SX', 'SXM', 'Sint Maarten', ''], ['SI', 'SVN', 'Slovenië', 'Sloveense'],
  ['SK', 'SVK', 'Slowakije', 'Slowaakse'], ['SD', 'SDN', 'Soedan', 'Soedanese'], ['SO', 'SOM', 'Somalië', 'Somalische'],
  ['ES', 'ESP', 'Spanje', 'Spaanse'], ['LK', 'LKA', 'Sri Lanka', 'Sri Lankaanse'], ['SR', 'SUR', 'Suriname', 'Surinaamse'],
  ['SY', 'SYR', 'Syrië', 'Syrische'], ['TJ', 'TJK', 'Tadzjikistan', 'Tadzjiekse'], ['TW', 'TWN', 'Taiwan', 'Taiwanese'],
  ['TZ', 'TZA', 'Tanzania', 'Tanzaniaanse'], ['TH', 'THA', 'Thailand', 'Thaise'], ['TG', 'TGO', 'Togo', 'Togolese'],
  ['TO', 'TON', 'Tonga', 'Tongaanse'], ['TT', 'TTO', 'Trinidad en Tobago', 'Trinidadiaanse'], ['TD', 'TCD', 'Tsjaad', 'Tsjadische'],
  ['CZ', 'CZE', 'Tsjechië', 'Tsjechische'], ['TN', 'TUN', 'Tunesië', 'Tunesische'], ['TR', 'TUR', 'Turkije', 'Turkse'],
  ['TM', 'TKM', 'Turkmenistan', 'Turkmeense'], ['TV', 'TUV', 'Tuvalu', 'Tuvaluaanse'], ['UY', 'URY', 'Uruguay', 'Uruguayaanse'],
  ['VU', 'VUT', 'Vanuatu', 'Vanuatuaanse'], ['VA', 'VAT', 'Vaticaanstad', 'Vaticaanse'], ['VE', 'VEN', 'Venezuela', 'Venezolaanse'],
  ['AE', 'ARE', 'Verenigde Arabische Emiraten', 'Emiratische'], ['GB', 'GBR', 'Verenigd Koninkrijk', 'Britse'],
  ['US', 'USA', 'Verenigde Staten', 'Amerikaanse'], ['VN', 'VNM', 'Vietnam', 'Vietnamese'], ['ZM', 'ZMB', 'Zambia', 'Zambiaanse'],
  ['ZW', 'ZWE', 'Zimbabwe', 'Zimbabwaanse'], ['ZA', 'ZAF', 'Zuid-Afrika', 'Zuid-Afrikaanse'], ['KR', 'KOR', 'Zuid-Korea', 'Zuid-Koreaanse'],
  ['SS', 'SSD', 'Zuid-Soedan', 'Zuid-Soedanese'], ['SE', 'SWE', 'Zweden', 'Zweedse'], ['CH', 'CHE', 'Zwitserland', 'Zwitserse']
];
/* Geen land, wel een nationaliteit (strook: XXA) */
const LN_STAATLOOS = 'Staatloos';
/* Tijdzone van de telefoon (of de gekozen app-zone) → land, voor de standaard in keuzelijsten. Alleen zones die bij één land horen. */
const LN_ZONE = {
  'Europe/Amsterdam': 'NL', 'Europe/Brussels': 'BE', 'Europe/Berlin': 'DE', 'Europe/Busingen': 'DE', 'Europe/Paris': 'FR', 'Europe/London': 'GB',
  'Europe/Luxembourg': 'LU', 'Europe/Madrid': 'ES', 'Atlantic/Canary': 'ES', 'Europe/Rome': 'IT', 'Europe/Lisbon': 'PT', 'Europe/Warsaw': 'PL',
  'Europe/Istanbul': 'TR', 'Africa/Casablanca': 'MA', 'America/Paramaribo': 'SR', 'Europe/Vienna': 'AT', 'Europe/Zurich': 'CH',
  'Europe/Dublin': 'IE', 'Europe/Athens': 'GR', 'Europe/Copenhagen': 'DK', 'Europe/Stockholm': 'SE', 'Europe/Oslo': 'NO', 'Europe/Helsinki': 'FI',
  'Europe/Prague': 'CZ', 'Europe/Budapest': 'HU', 'Europe/Bratislava': 'SK', 'Europe/Ljubljana': 'SI', 'Europe/Zagreb': 'HR', 'Europe/Bucharest': 'RO',
  'Europe/Sofia': 'BG', 'Europe/Tallinn': 'EE', 'Europe/Riga': 'LV', 'Europe/Vilnius': 'LT', 'Europe/Malta': 'MT', 'Asia/Nicosia': 'CY',
  'America/Aruba': 'AW', 'America/Curacao': 'CW', 'America/Lower_Princes': 'SX', 'America/Kralendijk': 'BQ'
};

/* Zoekt een land bij een code (NL, NLD, D), een landnaam of een nationaliteit (Nederlands of de Engelse weergave), zonder op
 * hoofdletters of accenten te letten. */
function lnVind(waarde){
  const s = lnNorm(waarde);
  if (!s) return null;
  if (s === 'd') return lnVind('DE'); // Duitsland in de strook
  if (/^gb[dnops]$/.test(s)) return lnVind('GB'); // Britse varianten in de strook (overzeese gebieden, British National e.d.)
  for (const r of LANDEN) if (lnNorm(r[0]) === s || lnNorm(r[1]) === s || lnNorm(r[2]) === s || r[3] && lnNorm(r[3]) === s) return r;
  for (const r of LANDEN) if (lnNorm(lnLandNaam(r)) === s || r[3] && lnNorm(lnNatNaam(r)) === s) return r;
  // De Engelse naam (bijv. het land van een contact op een Engelstalige telefoon: "Netherlands")
  try { if (!lnEn) lnEn = new Intl.DisplayNames(['en'], { type: 'region' }); for (const r of LANDEN) if (lnNorm(lnEn.of(r[0])) === s) return r; } catch (e) {}
  return null;
}
let lnEn = null;
function lnNorm(s){ return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[’']/g, '\'').trim().toLowerCase(); }
/* Naam van het land in de taal van de app (Nederlands: uit de lijst; anders via Intl, met de Nederlandse naam als het niet lukt) */
function lnLandNaam(r){
  if (rgLang().id === 'nl') return r[2];
  try {
    const loc = regioLocale();
    if (!lnDn || lnDn.loc !== loc) lnDn = { loc, dn: new Intl.DisplayNames([loc], { type: 'region' }) };
    const n = lnDn.dn.of(r[0]); if (n && n !== r[0]) return n;
  } catch (e) {}
  return r[2];
}
let lnDn = null;
function lnNatNaam(r){ return r[3] ? t(r[3]) : ''; }
/* Het land van Rene volgens Regio en taal: de gekozen standaardplaats; anders, bij een Nederlandstalige telefoon (nl-NL, nl-BE),
 * die regio, zodat een andere tijdzone op reis het land niet verandert; anders de tijdzone (Europe/Amsterdam = Nederland);
 * anders de regio van de telefoon, maar alleen een land uit LN_ZONE ("en-US" is vaak gewoon de standaard van het toestel;
 * een Nederlandse telefoon in het Engels of Turks telt dus via de tijdzone als Nederland). null als niets iets zegt. */
function lnThuis(){
  const c = rgCfg(), city = RG_CITIES.find(x => x[0] === c.place);
  if (city && city[4]) return lnVind(city[4]);
  const bekend = new Set(Object.values(LN_ZONE));
  let taal = '', regio = '';
  try { const m = /^([a-z]{2,3})[-_]([A-Z]{2})\b/.exec(String((navigator.languages && navigator.languages[0]) || navigator.language || '')); if (m) { taal = m[1]; regio = bekend.has(m[2]) ? m[2] : ''; } } catch (e) {}
  if (taal === 'nl' && regio) return lnVind(regio);
  const code = LN_ZONE[rgZone()] || regio;
  return code ? lnVind(code) : null;
}
/* Opgeslagen tekst (Nederlands) zoals hij in de app getoond wordt; onbekende tekst blijft zoals hij is. */
function lnToon(soort, waarde){
  if (soort === 'nationaliteit' && String(waarde || '').includes(',')) return String(waarde).split(/\s*,\s*/).filter(Boolean).map(x => lnToon(soort, x)).join(', ');
  if (soort === 'nationaliteit' && (lnNorm(waarde) === lnNorm(LN_STAATLOOS) || lnNorm(waarde) === 'xxa')) return t(LN_STAATLOOS);
  const r = lnVind(waarde);
  if (!r) return waarde;
  // Een code uit de strook die eerder zo bewaard is (SYR): toon de naam
  if (/^[A-Z]{1,3}$/.test(String(waarde).trim())) return soort === 'nationaliteit' ? lnNatNaam(r) || lnLandNaam(r) : lnLandNaam(r);
  return soort === 'nationaliteit' ? (r[3] && lnNorm(r[3]) === lnNorm(waarde) ? lnNatNaam(r) : waarde) : lnNorm(r[2]) === lnNorm(waarde) ? lnLandNaam(r) : waarde;
}
/* Keuzes [waarde, tekst] voor een landen- of nationaliteitenlijst, gesorteerd op de getoonde tekst. */
function lnKeuzes(soort){
  const out = [];
  for (const r of LANDEN) { if (soort === 'nationaliteit') { if (r[3]) out.push([r[3], lnNatNaam(r)]); } else out.push([r[2], lnLandNaam(r)]); }
  if (soort === 'nationaliteit') out.push([LN_STAATLOOS, t(LN_STAATLOOS)]);
  const coll = new Intl.Collator(regioLocale(), { sensitivity: 'base' });
  return out.sort((a, b) => coll.compare(a[1], b[1]));
}
/* Opgeslagen waarde (Nederlands) bij een land: de landnaam of de nationaliteit. */
function lnWaarde(r, soort){ return r ? (soort === 'nationaliteit' ? r[3] : r[2]) : ''; }
/* Een <select> vullen met alle landen (bijv. het land van bestemming bij Formulieren); waarde = de Nederlandse landnaam. */
function lnSelect(sel, leeg, anders){
  if (!sel || sel.dataset.ln === regioLocale()) return;
  sel.replaceChildren();
  const o = document.createElement('option'); o.value = ''; o.textContent = leeg; sel.append(o);
  for (const [w, txt] of lnKeuzes('land')) { const x = document.createElement('option'); x.value = w; x.textContent = txt; sel.append(x); }
  if (anders) { const x = document.createElement('option'); x.value = anders[0]; x.textContent = anders[1]; sel.append(x); }
  sel.dataset.ln = regioLocale();
}
