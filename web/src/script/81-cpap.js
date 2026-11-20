const CP_VELDEN = {
  seal: { naam: () => t('Maskerafdichting'), max: 20, heel: true },
  leak: { naam: () => t('Lekkage'), max: 999 },
  ahi: { naam: () => t('AHI'), max: 999 },
  usage: { naam: () => t('Gebruiksduur'), max: 1440, heel: true },
  off: { naam: () => t('Masker op/af'), max: 999, heel: true },
  score: { naam: () => t('myAir-score'), max: 100, heel: true },
  leak95: { naam: () => t('Lekkage (95e percentiel)'), max: 999 },
  sessions: { naam: () => t('Maskersessies'), max: 999, heel: true }
};
const CP_ONBEKEND = 'Onbekend masker'; // taal-vast: opslagwaarde in het logboek
const cp = { nachten: [], periode: [], bewerkt: null, importeert: false, limiet: { maskers: 12, nachten: 30 } };

function cpEnter(scherm, vorige) {
  if (vorige === 'cpapnacht' && scherm !== vorige) {
    $('#cp-form').reset();
    cp.bewerkt = null;
    cpBericht('cp-form-fout', '');
  }
  if (scherm === 'cpap') cpLaden();
  else if (scherm !== 'cpapnacht') {
    cp.nachten = [];
    cp.periode = [];
    cpBericht('cp-import-status', '');
    ['cp-samenvatting', 'cp-verloop', 'cp-maskers', 'cp-nachten'].forEach(id => $('#' + id).replaceChildren());
  }
}

function cpBericht(id, bericht) {
  const el = $('#' + id);
  el.textContent = bericht;
  el.hidden = !bericht;
}

function cpLaden() {
  const beschikbaar = typeof Android.cpapList === 'function';
  const antwoord = beschikbaar ? rdJson(Android.cpapList(), {}) : {};
  const gelukt = Array.isArray(antwoord.nights);
  $('#cp-nieuw').disabled = !gelukt;
  $('#cp-import').hidden = typeof Android.cpapImport !== 'function';
  $('#cp-import').disabled = !gelukt || cp.importeert;
  cpBericht('cp-fout', gelukt ? '' : beschikbaar ? t('CPAP-metingen laden lukt niet.') : t('Installeer de nieuwste versie van Tools om CPAP-analyse te gebruiken.'));
  cp.nachten = gelukt ? antwoord.nights.sort((a, b) => b.day.localeCompare(a.day)) : [];
  $('#cp-leeg').innerHTML = gelukt && !cp.nachten.length ? uiEmpty('moon', t('Nog geen CPAP-metingen.')) : '';
  $('#cp-overzicht').hidden = !cp.nachten.length;
  cpPeriode();
}

function cpImport() {
  if (cp.importeert) return;
  cp.importeert = true;
  $('#cp-import').disabled = true;
  cpBericht('cp-import-status', t('Kies je myAir-ZIP-bestand.'));
  Android.cpapImport();
}

function onCpapImport(resultaat) {
  cp.importeert = false;
  if (current !== 'cpap') return;
  $('#cp-import').disabled = false;
  if (resultaat.cancelled) return cpBericht('cp-import-status', '');
  if (resultaat.error) return cpBericht('cp-import-status', t('Importeren lukt niet. Kies een geldige myAir-ZIP met nachtmetingen.'));
  $('#cp-periode').value = 'all';
  cpLaden();
  if (resultaat.added) $('#cp-meting').value = 'ahi';
  cpVerloop();
  cpBericht('cp-import-status', t('Toegevoegd: {nieuw}. Al aanwezig: {bestaand}.', {
    nieuw: fmtNum(resultaat.added), bestaand: fmtNum(resultaat.existing)
  }));
}

function cpDatumbereik() {
  const aantal = $('#cp-periode').value;
  if (aantal === 'all') return () => true;
  const vandaag = rgToday();
  const begin = rgDayAdd(vandaag, 1 - Number(aantal));
  return dag => dag >= begin && dag <= vandaag;
}

function cpPeriode() {
  cp.limiet = { maskers: 12, nachten: 30 };
  const inBereik = cpDatumbereik();
  cp.periode = cp.nachten.filter(nacht => inBereik(nacht.day));
  $('#cp-geen-periode').hidden = !!cp.periode.length;
  $('#cp-metingen').hidden = !cp.periode.length;
  cpSamenvatting();
  cpVerloop();
  cpMaskers();
  cpNachten();
}

function cpWaarde(veld, waarde) {
  if (veld === 'usage') {
    const min = Math.round(waarde);
    return t('{uur} u {min} min', { uur: fmtNum(Math.floor(min / 60)), min: fmtNum(min % 60) });
  }
  const n = fmtNum(waarde, { maximumFractionDigits: 2 });
  if (veld === 'seal') return t('{n}/20 punten', { n });
  if (veld === 'leak' || veld === 'leak95') return t('{n} L/min', { n });
  if (veld === 'ahi') return t('{n}/uur', { n });
  if (veld === 'score') return t('{n}/100', { n });
  return n;
}

function cpSamenvatting() {
  const gebruik = cp.periode.filter(nacht => nacht.usage != null);
  const metGebruik = gebruik.filter(nacht => nacht.usage > 0);
  const ahi = cp.periode.filter(nacht => nacht.ahi != null && nacht.usage !== 0);
  const rij = (label, waarde) => '<div><dt>' + esc(label) + '</dt><dd>' + esc(waarde) + '</dd></div>';
  const gemiddelde = (nachten, veld) => nachten.reduce((som, nacht) => som + nacht[veld], 0) / nachten.length;
  $('#cp-samenvatting').innerHTML = [
    gebruik.length ? rij(t('Nachten met CPAP-gebruik'), t('{n} van {totaal}', {
      n: fmtNum(metGebruik.length), totaal: fmtNum(gebruik.length)
    })) : '',
    metGebruik.length ? rij(t('Gemiddelde duur bij gebruik'), cpWaarde('usage', gemiddelde(metGebruik, 'usage'))) : '',
    ahi.length ? rij(t('Gemiddelde AHI'), t('{waarde} · {nachten}', {
      waarde: cpWaarde('ahi', gemiddelde(ahi, 'ahi')), nachten: cpAantal(ahi.length)
    })) : ''
  ].join('') || '<div><dd>' + esc(t('Geen gebruiksduur of AHI beschikbaar.')) + '</dd></div>';
}

function cpMaskernaam(masker) {
  return masker === CP_ONBEKEND ? t('Onbekend masker') : masker;
}

function cpVerloop() {
  const veld = $('#cp-meting').value;
  const metingen = cp.periode.filter(nacht => nacht[veld] != null).slice(0, 7).reverse();
  const max = veld === 'seal' ? 20 : Math.max(1, ...metingen.map(nacht => nacht[veld]));
  $('#cp-geen-verloop').hidden = !!metingen.length;
  $('#cp-verloop').hidden = !metingen.length;
  $('#cp-verloop').innerHTML = metingen.map(nacht =>
    '<li translate="no"><time datetime="' + esc(nacht.day) + '">' + esc(fmtDate(nacht.day, 'dm')) + '</time>' +
    '<span class="cp-balk" aria-hidden="true"><i style="width:' + (100 * nacht[veld] / max) + '%"></i></span>' +
    '<strong>' + esc(cpWaarde(veld, nacht[veld])) + '</strong></li>'
  ).join('');
}

function cpAantal(n) {
  return tn(n, 'Eén nacht', '{n} nachten', { n: fmtNum(n) });
}

function cpMaskers() {
  const groepen = new Map();
  for (const nacht of cp.periode) {
    const sleutel = nacht.mask.toLowerCase();
    if (!groepen.has(sleutel)) groepen.set(sleutel, []);
    groepen.get(sleutel).push(nacht);
  }
  const maskers = [...groepen.values()];
  $('#cp-maskers').innerHTML = maskers.slice(0, cp.limiet.maskers).map(nachten => {
    const waarden = Object.entries(CP_VELDEN).map(([veld, soort]) => {
      const metingen = nachten.map(nacht => nacht[veld]).filter(n => n != null);
      if (!metingen.length) return '';
      const gemiddelde = metingen.reduce((som, n) => som + n, 0) / metingen.length;
      return '<div><dt>' + esc(soort.naam()) + '</dt><dd>' + esc(cpWaarde(veld, gemiddelde)) +
        ' <small>· ' + esc(cpAantal(metingen.length)) + '</small></dd></div>';
    }).join('');
    return '<article class="cp-masker"><h3 translate="no">' + esc(cpMaskernaam(nachten[0].mask)) + '</h3><p class="hint">' +
      esc(cpAantal(nachten.length)) + '</p><dl class="cp-waarden">' + waarden + '</dl></article>';
  }).join('');
  $('#cp-meer-maskers').hidden = maskers.length <= cp.limiet.maskers;
}

function cpNachten() {
  const lijst = $('#cp-nachten');
  lijst.innerHTML = cp.periode.slice(0, cp.limiet.nachten).map(nacht => {
    const metingen = Object.entries(CP_VELDEN).filter(([veld]) => nacht[veld] != null).map(([veld, soort]) =>
      t('{meting} {waarde}', { meting: soort.naam(), waarde: cpWaarde(veld, nacht[veld]) }));
    return '<button class="strow" type="button" data-dag="' + esc(nacht.day) + '"><span class="sttxt"><b translate="no">' +
      esc(fmtDate(nacht.day, 'kort')) + ' · ' + esc(cpMaskernaam(nacht.mask)) + '</b><small>' + esc(metingen.join(' · ')) +
      '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>';
  }).join('');
  lijst.onclick = e => {
    const rij = e.target.closest('[data-dag]');
    if (rij) cpOpen(rij.dataset.dag);
  };
  $('#cp-meer-nachten').hidden = cp.periode.length <= cp.limiet.nachten;
}

function cpMeer(lijst) {
  if (lijst === 'maskers') {
    cp.limiet.maskers += 12;
    cpMaskers();
  } else {
    cp.limiet.nachten += 30;
    cpNachten();
  }
}

function cpOpen(dag) {
  const nacht = cp.nachten.find(n => n.day === dag);
  cp.bewerkt = nacht || null;
  const datum = $('#cp-day');
  datum.readOnly = !!nacht;
  datum.max = rgToday();
  datum.value = nacht ? nacht.day : datum.max;
  $('#cp-mask').value = nacht ? nacht.mask : ((cp.nachten.find(n => n.mask !== CP_ONBEKEND) || {}).mask || '');
  $('#cp-titel').textContent = nacht ? t('Nacht bewerken') : t('Nacht toevoegen');
  $('#cp-menu').hidden = !nacht;
  cpBericht('cp-form-fout', '');
  Object.keys(CP_VELDEN).forEach(veld => {
    const n = nacht && nacht[veld];
    $('#cp-' + veld).value = n == null ? '' : veld === 'usage' ?
      Math.floor(n / 60) + ':' + String(n % 60).padStart(2, '0') : fmtNumIn(n);
  });
  show('cpapnacht');
}

/* De duur is uur:min, de andere metingen zijn getallen in de gekozen getalnotatie. */
function cpGetal(veld, tekst) {
  if (veld === 'usage') {
    const tijd = /^(\d{1,2}):([0-5]\d)$/.exec(tekst);
    return tijd ? Number(tijd[1]) * 60 + Number(tijd[2]) : null;
  }
  return /^\d+(?:[.,]\d{1,2})?$/.test(tekst) ? parseNum(tekst) : null;
}

function cpOpslaan() {
  const fout = bericht => cpBericht('cp-form-fout', bericht);
  const nacht = { day: $('#cp-day').value, mask: $('#cp-mask').value.trim().replace(/\s+/g, ' ') };
  if (!nacht.mask) return fout(t('Vul de naam of het model van je masker in.'));
  for (const [veld, soort] of Object.entries(CP_VELDEN)) {
    const tekst = $('#cp-' + veld).value.trim();
    if (!tekst) continue;
    const waarde = cpGetal(veld, tekst);
    if (waarde == null || waarde > soort.max || (soort.heel && !Number.isInteger(waarde))) {
      const bereik = veld === 'usage' ? t('Gebruik uur:min, van 0:00 tot 24:00.') :
        t('{meting} moet tussen 0 en {max} liggen.', { meting: soort.naam(), max: fmtNum(soort.max) });
      $('#cp-' + veld).focus();
      return fout(bereik);
    }
    nacht[veld] = waarde;
  }
  if (Object.keys(nacht).length === 2) return fout(t('Vul minstens één meting in.'));
  const resultaat = Android.cpapSave(JSON.stringify(nacht), cp.bewerkt ? JSON.stringify(cp.bewerkt) : '');
  if (resultaat === 'date') return fout(t('Er bestaat al een meting voor deze datum. Open die nacht om haar te bewerken.'));
  if (resultaat === 'changed') return fout(cpGewijzigd());
  if (resultaat) return fout(t('CPAP-meting opslaan lukt niet.'));
  if (!cpDatumbereik()(nacht.day)) $('#cp-periode').value = 'all';
  show('cpap');
}

function cpGewijzigd() {
  return t('Deze nacht is in een ander venster gewijzigd of verwijderd. Ga terug en open de nacht opnieuw.');
}

function cpMenu() {
  openSheet(t('Nacht bewerken'), '', [[t('Nacht verwijderen'), () => {
    askConfirm(t('Nacht verwijderen?'), t('De meting van deze nacht wordt uit je CPAP-logboek gewist.'), t('Verwijderen'), () => {
      const nacht = cp.bewerkt;
      if (!nacht) return;
      const resultaat = Android.cpapDelete(nacht.day, JSON.stringify(nacht));
      if (!resultaat) show('cpap');
      else cpBericht('cp-form-fout', resultaat === 'changed' ? cpGewijzigd() : t('CPAP-meting verwijderen lukt niet.'));
    });
  }]]);
}
