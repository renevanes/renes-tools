/* ---------- Betalen: een rekening (foto, PDF, afbeelding of QR-code) wordt een betaling die je zelf bij je bank ondertekent ---------- */
// Tools betaalt nooit zelf. Lezen en herkennen gebeurt in Java, op de telefoon. Alles wat van een rekening of QR-code komt
// (naam, IBAN, kenmerk, omschrijving, het adres van een betaallink) is gegevens: altijd esc() of textContent, nooit als
// HTML; een betaallink gaat alleen via Android.betalenOpenLink (alleen https, na een vraag met het adres erbij).
const BT_SCHERMEN = ['betalen', 'betaling', 'betalenset'];
let bt = { staat: null, filter: 'open', id: '', ibans: {}, anders: {}, stream: null, lezen: false, qrKlaar: null };
function btOud(){ return typeof Android.betalenStaat !== 'function'; }
function btLaad(){ bt.staat = btOud() ? {} : (rdJson(Android.betalenStaat(), {}) || {}); return bt.staat; }
function btLijst(){ return (bt.staat && bt.staat.betalingen) || []; }
function btInst(){ return (bt.staat && bt.staat.inst) || {}; }
function btVind(id){ return btLijst().find(x => x.id === id); }
function btVindNu(id){ btLaad(); return btVind(id); }
function btBank(){ const i = btInst(); return ((bt.staat && bt.staat.banken) || []).find(b => b.id === i.bank) || null; }
function btGetal(nl){ return nl ? Number(String(nl).replace(',', '.')) : NaN; }
function btIbanMooi(i){ return String(i || '').replace(/(.{4})/g, '$1 ').trim(); }
function btLeeg(b){ return !b || !(b.naam || b.iban || b.bedrag || b.kenmerk || b.omschrijving || b.link); }

function btEnter(name, prev){
  if (!BT_SCHERMEN.includes(name)) { if (btCamOpen()) btCamDicht(); if (prev === 'betaling') btRuimLeeg(); return; }
  if (name !== 'betalen' && btCamOpen()) btCamDicht();
  if (prev === 'betaling' && name !== 'betaling') btRuimLeeg();
  btLaad();
  if (name === 'betalen') { btRender(); btUitkomst(); }
  else if (name === 'betaling') btdRender();
  else btsRender();
}
/* Een betaling waar niets in staat (Zelf invullen en weer terug) blijft niet staan */
function btRuimLeeg(){
  const b = bt.id && rdJson(Android.betalenStaat(), {}).betalingen ? (rdJson(Android.betalenStaat(), {}).betalingen || []).find(x => x.id === bt.id) : null;
  if (b && btLeeg(b)) Android.betalenWis(b.id);
}
function btResume(){ btLaad(); if (current === 'betalen') btRender(); else if (current === 'betaling') btdKnoppen(); btUitkomst(); }
function btFilter(f){ bt.filter = f; btRender(); }
function btSlotUit(){ return typeof ts !== 'undefined' && ts && ts.secure && !ts.aan.includes('betalen'); }
function btNaarInstellingen(){ show('setslot'); }
function btNaarSlot(){
  if (typeof SOLO !== 'undefined' && SOLO) { toast(t('Zet dit aan in de gewone Tools-app: Instellingen → Extra ontgrendelen')); return; }
  btNaarInstellingen();
}

function btRender(){
  const st = bt.staat || {}, oud = btOud();
  $('#bt-oud').hidden = !oud;
  $('#bt-nieuw').hidden = oud;
  $('#bt-slot').hidden = oud || !btSlotUit();
  document.querySelectorAll('#bt-filter button').forEach(b => { const on = b.dataset.f === bt.filter; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  const stand = $('#bt-stand');
  if (bt.lezen || st.bezig) uiWachtTekst(stand, t('De rekening lezen…'));
  else stand.textContent = st.onleesbaar ? t('De gegevens van Betalen zijn niet leesbaar. Er is niets overschreven.') : '';
  const lijst = btLijst().filter(b => b.status === bt.filter).sort((a, b) => bt.filter === 'open'
    ? (a.vervaldatum || '9999').localeCompare(b.vervaldatum || '9999') || b.gemaakt - a.gemaakt : (b.gewijzigd || 0) - (a.gewijzigd || 0));
  const box = $('#bt-lijst');
  if (!lijst.length) {
    box.innerHTML = oud ? '' : uiEmpty('wallet', bt.filter === 'open' ? t('Niets te betalen.') : bt.filter === 'klaargezet' ? t('Niets klaargezet.') : t('Nog niets betaald.'));
    return;
  }
  const vandaag = rgToday();
  box.innerHTML = lijst.map(b => {
    const bedrag = b.bedrag ? fmtMoney(btGetal(b.bedrag)) : t('bedrag ontbreekt');
    const wanneer = b.status === 'betaald' ? (b.betaaldOp ? t('betaald op {dag}', { dag: fmtDate(b.betaaldOp) }) : '')
      : b.vervaldatum ? (b.vervaldatum < vandaag ? t('had betaald moeten zijn op {dag}', { dag: fmtDate(b.vervaldatum) }) : t('uiterlijk {dag}', { dag: fmtDate(b.vervaldatum) })) : '';
    const mist = b.status === 'betaald' ? '' : b.link ? t('met betaallink') : !b.compleet ? t('nog niet compleet') : '';
    return '<button type="button" class="' + (b.status === 'open' && b.vervaldatum && b.vervaldatum < vandaag ? 'sn-item nieuw' : 'sn-item') + '" data-id="' + esc(b.id) + '"><b translate="no">' + esc(b.naam || btIbanMooi(b.iban) || t('Betaling')) + '</b>'
      + '<small translate="no">' + esc([bedrag, wanneer, mist].filter(Boolean).join(' · ')) + '</small></button>';
  }).join('');
  box.querySelectorAll('.sn-item').forEach(el => el.addEventListener('click', () => btOpen(el.dataset.id)));
}
function btOpen(id){ bt.id = id; show('betaling'); }

/* ---------- toevoegen: foto, bestand (ook uit de kluis of het facturendossier) of zelf invullen ---------- */
function btNieuw(){
  if (bt.lezen) { toast(t('Tools leest al een rekening')); return; }
  bronKies({ titel: t('Rekening toevoegen'), soort: 'pdfbeeld', kiezer: 'betalen', bestand: () => btKies(),
    voor: [[t('Foto maken'), () => btCamera(), 'camera'], [t('Zelf invullen'), () => btHand(), 'edit']] });
}
function btKies(){ bt.lezen = true; btRender(); Android.betalenKies(); }
function btHand(){
  const r = rdJson(Android.betalenBewaar(JSON.stringify({ bron: 'hand' })), {});
  if (!r.ok) { toast(r.fout || t('Opslaan lukte niet.')); return; }
  btOpen(r.id);
}

/* De camera in de app zelf (zoals de QR-scanner van VPN): het beeld gaat alleen naar de tekstherkenning op de telefoon */
function btCamOpen(){ const c = $('#bt-cam'); return !!c && c.classList.contains('on'); }
function btCamera(){ Android.betalenCamera(); }
window.onBetalenCamera = function(ok){
  if (current !== 'betalen') { Android.betalenCameraDone(); return; }
  if (!ok) { toast(t('Zonder camera kan het ook: kies een foto of PDF van de rekening')); return; }
  const box = $('#bt-cam'), v = $('#bt-video');
  box.classList.add('on'); bt.camNr = (bt.camNr || 0) + 1; $('#bt-camfoto').disabled = true; $('#bt-cammsg').textContent = t('Camera starten…');
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { $('#bt-cammsg').textContent = t('De camera is hier niet beschikbaar. Kies een foto of PDF van de rekening.'); return; }
  navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 3840 }, height: { ideal: 2160 } }, audio: false }).then(stream => {
    if (!btCamOpen()) { stream.getTracks().forEach(tt => tt.stop()); return; }
    bt.stream = stream; v.srcObject = stream; v.play().catch(() => {});
    $('#bt-cammsg').textContent = t('Houd de hele rekening scherp in beeld, met de QR-code als die erop staat');
    $('#bt-camfoto').disabled = false;
  }).catch(() => { $('#bt-cammsg').textContent = t('De camera start niet. Kies een foto of PDF van de rekening.'); });
};
function btCamDicht(){
  if (bt.stream) { bt.stream.getTracks().forEach(tt => tt.stop()); bt.stream = null; }
  const v = $('#bt-video'); if (v) v.srcObject = null;
  $('#bt-cam').classList.remove('on');
  Android.betalenCameraDone();
}
/** Het beeld voor de foto: liefst een echte foto op volle resolutie (scherper dan het videobeeld), anders het videobeeld. */
async function btCamBeeld(){
  const v = $('#bt-video'), spoor = bt.stream && bt.stream.getVideoTracks ? bt.stream.getVideoTracks()[0] : null;
  const opCanvas = (bron, w, h) => { const s = Math.min(1, 3200 / Math.max(w, h)), c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w * s)); c.height = Math.max(1, Math.round(h * s)); c.getContext('2d').drawImage(bron, 0, 0, c.width, c.height); return c; };
  if (spoor && typeof ImageCapture === 'function' && typeof createImageBitmap === 'function') {
    try {
      const blob = await Promise.race([new ImageCapture(spoor).takePhoto(), new Promise((_, nee) => setTimeout(() => nee(new Error('te laat')), 5000))]);
      const beeld = await createImageBitmap(blob);
      const goed = beeld.width >= v.videoWidth && beeld.height > 0, c = goed ? opCanvas(beeld, beeld.width, beeld.height) : null;
      if (beeld.close) beeld.close();
      if (c) return c;
    } catch (e) { /* dan het videobeeld */ }
  }
  return v.videoWidth ? opCanvas(v, v.videoWidth, v.videoHeight) : null;
}
async function btCamFoto(){
  const knop = $('#bt-camfoto');
  if (knop.disabled || !$('#bt-video').videoWidth) return;
  knop.disabled = true; $('#bt-cammsg').textContent = t('Foto maken… houd de telefoon stil');
  const nr = bt.camNr, c = await btCamBeeld();
  if (!btCamOpen() || bt.camNr !== nr) return; // intussen weggegaan, of de camera is opnieuw geopend
  btCamDicht();
  if (!c) { toast(t('De foto is niet gelukt. Probeer het opnieuw.')); return; }
  bt.lezen = true; btRender();
  bt.qrKlaar = btQrUitCanvas(c); // meteen zoeken, terwijl Java de tekst leest
  Android.betalenFoto(c.toDataURL('image/jpeg', 0.9));
}

/* ---------- QR-code op de rekening (jsQR, in de app) ---------- */
function btQrLaad(){
  if (typeof jsQR === 'function') return Promise.resolve(true);
  return new Promise(res => { const s = document.createElement('script'); s.src = 'vendor/jsQR.js'; s.onload = () => res(typeof jsQR === 'function'); s.onerror = () => res(false); document.head.appendChild(s); });
}
const btPauze = () => new Promise(r => setTimeout(r, 0));
/**
 * Zoekt alle QR-codes op het beeld: het hele beeld (verkleind) en daarna overlappende stukken, ook vergroot. Op een brief
 * staan er vaak meer (een adrescode bovenaan, de betaalcode onderaan), dus niet stoppen bij de eerste.
 */
async function btQrAlle(bron){
  const uit = [];
  if (!(await btQrLaad())) return uit;
  const lees = (sx, sy, sw, sh, max) => {
    const s = Math.min(2.5, max / Math.max(sw, sh)), w = Math.max(1, Math.round(sw * s)), h = Math.max(1, Math.round(sh * s));
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    const x = c.getContext('2d', { willReadFrequently: true }); x.drawImage(bron, sx, sy, sw, sh, 0, 0, w, h);
    try { const r = jsQR(x.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: 'dontInvert' }); if (r && r.data && !uit.includes(r.data)) uit.push(r.data); } catch (e) { }
  };
  const W = bron.width, H = bron.height;
  lees(0, 0, W, H, 1400);
  for (const n of [3, 4]) { // stukken van de helft en van een derde van het beeld, overlappend
    const sw = Math.ceil(W * 2 / (n + 1)), sh = Math.ceil(H * 2 / (n + 1));
    for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
      await btPauze();
      lees(Math.round((W - sw) * i / (n - 1)), Math.round((H - sh) * j / (n - 1)), sw, sh, 1400);
    }
    if (uit.some(q => btQrSoort(q) > 0)) break; // een betaalcode gevonden: klaar
  }
  return uit;
}
/** Hoe bruikbaar is deze QR-code om mee te betalen? 3 = betaalgegevens, 2 = betaallink van een bekende dienst, 1 = andere https-link, 0 = iets anders. */
function btQrSoort(tekst){
  const q = rdJson(Android.betalenQr(tekst), null);
  return !q ? 0 : q.soort === 'epc' ? (q.eigen ? 0 : 3) : q.soort === 'link' ? (q.bekend ? 2 : 1) : 0;
}
/** De QR-code op het beeld waarmee je kunt betalen (de beste), of '' als die er niet is. */
async function btQrUitCanvas(bron){
  const alle = await btQrAlle(bron);
  let best = '', score = 0;
  for (const q of alle) { const s = btQrSoort(q); if (s > score) { best = q; score = s; } }
  /* taal-vast-begin: tekst voor Diagnose (om een probleem na te zoeken), geen tekst van de interface */
  try { // voor Diagnose: hoeveel codes en van welke soort, nooit de inhoud (een betaallink is een sleutel)
    if (typeof Android.diagnoseLeg === 'function') Android.diagnoseLeg('betalen', 'QR-codes gezocht', 'Beeld: ' + (bron.width || bron.videoWidth || 0) + ' x ' + (bron.height || bron.videoHeight || 0) + '\nGevonden: ' + alle.length + '\nSoorten (3 = SEPA-QR, 2 = bekende betaallink, 1 = link, 0 = iets anders): ' + alle.map(q => btQrSoort(q) + ' (' + q.length + ' tekens)').join(', ') + '\nGekozen: ' + (best ? 'soort ' + score : alle[0] ? 'geen betaalcode' : 'niets'));
  } catch (e) { /* diagnose mag nooit iets tegenhouden */ }
  /* taal-vast-eind */
  return best || alle[0] || '';
}
function btQrUitBeeld(dataUrl){
  return new Promise(res => {
    if (!/^data:image\/jpeg;base64,/.test(dataUrl || '')) { res(''); return; }
    const im = new Image();
    im.onload = () => { const c = document.createElement('canvas'); c.width = im.naturalWidth; c.height = im.naturalHeight; c.getContext('2d').drawImage(im, 0, 0); btQrUitCanvas(c).then(res, () => res('')); };
    im.onerror = () => res('');
    im.src = dataUrl;
  });
}

/* Java heeft de rekening gelezen: het voorstel (en de QR-code) wordt een betaling die je daarna naloopt */
window.onBetalenHerkend = function(r){
  const qr = bt.qrKlaar; bt.qrKlaar = null;
  if (!r || r.error !== undefined) { bt.lezen = false; if (current === 'betalen') btRender(); if (r && r.error) toast(r.error); return; }
  (qr || btQrUitBeeld(r.beeld)).then(tekst => btVoorstel(r, tekst), () => btVoorstel(r, ''));
};
function btVoorstel(r, qrTekst){
  bt.lezen = false;
  const q = qrTekst ? rdJson(Android.betalenQr(qrTekst), null) : null;
  const b = { doc: r.doc || '', bron: r.bron || 'bestand', genoemd: r.genoemd || '', naamVan: r.naamVan || '', naamGelezen: r.naamGelezen || '', naam: r.naam || '', iban: r.iban || '', bedrag: r.bedrag || '', kenmerk: r.kenmerk || '', omschrijving: r.omschrijving || '', vervaldatum: r.vervaldatum || '' };
  const eigenQr = !!(q && q.soort === 'epc' && q.eigen); // je eigen rekeningnummer in de QR-code: die code is niet om mee te betalen
  const anders = !!(q && q.soort === 'epc' && !q.eigen && (r.ibans || []).length && !(r.ibans || []).includes(q.iban));
  if (q && q.soort === 'epc' && !eigenQr) { // een QR-code met betaalgegevens is betrouwbaarder dan gelezen tekst
    // de naam van de lijst of van een eerdere betaling hoort bij het gelezen IBAN: een ander IBAN uit de QR-code = de gelezen naam
    if (q.iban !== r.iban && r.naamVan) { b.naam = r.naamGelezen || ''; b.naamVan = ''; b.naamGelezen = ''; }
    b.iban = q.iban; if (q.naam) b.naam = q.naam; if (q.bedrag) b.bedrag = q.bedrag;
    if (q.kenmerk) { b.kenmerk = q.kenmerk; b.omschrijving = ''; } else if (q.omschrijving) { b.omschrijving = q.omschrijving; b.kenmerk = ''; }
  }
  if (q && q.soort === 'link') b.link = q.link;
  if (b.kenmerk) b.omschrijving = '';
  let res = rdJson(Android.betalenBewaar(JSON.stringify(b)), {});
  if (!res.ok) res = rdJson(Android.betalenBewaar(JSON.stringify({ bron: b.bron, naam: b.naam, link: b.link || '' })), {}); // iets klopte niet: dan alleen wat zeker kan
  if (!res.ok) { toast(res.fout || t('Opslaan lukte niet.')); if (current === 'betalen') { btLaad(); btRender(); } return; }
  bt.ibans[res.id] = (r.ibans || []).filter(x => typeof x === 'string').slice(0, 5);
  bt.anders[res.id] = anders;
  // Klaar terwijl Betalen op slot ging (het lezen loopt door met het scherm uit): tonen en de kluis vragen pas na ontgrendelen
  tsDaarna('betalen', () => btVoorstelToon(r, q, b, res, eigenQr));
}
function btVoorstelToon(r, q, b, res, eigenQr){
  if (r.stuk) btStukBewaren(r.stuk, res.id);
  const gevonden = !!(b.iban || b.bedrag || b.link), uitQr = !!(q && q.soort === 'epc' && !eigenQr && (btVindNu(res.id) || {}).iban === q.iban);
  toast(q && q.soort === 'link' ? t('Betaallink gevonden in de QR-code. Kijk de gegevens na.')
    : eigenQr ? t('In de QR-code staat je eigen rekeningnummer. Tools heeft die code niet gebruikt.')
    : uitQr ? t('Betaalgegevens gelezen uit de QR-code. Kijk ze na.')
    : gevonden ? t('Gelezen. Kijk de gegevens goed na.') : t('Tools kon de rekening niet lezen. Vul de betaling zelf in.'));
  if (BT_SCHERMEN.includes(current)) btOpen(res.id); else { btLaad(); }
}

/* ---------- de rekening zelf: versleuteld in de documentenkluis (map Betalen), te bekijken in de viewer van de app ---------- */
function btKluisKan(){ return typeof Android.kluisList === 'function' && typeof klMetKluis === 'function'; }
function btStukBewaren(token, id){
  if (!btKluisKan()) { Android.betalenStukWeg(token); return; }
  bt.stuk = { token, id };
  klMetKluis(() => { if (bt.stuk && bt.stuk.token === token) Android.betalenStukNaarKluis(token, id); });
}
window.onBetalenStuk = function(r){
  const s = bt.stuk; bt.stuk = null;
  if (!r || !r.ok) { if (s) Android.betalenStukWeg(s.token); toast((r && r.fout) || t('Bewaren in de kluis lukte niet.')); }
  btLaad(); if (current === 'betaling') btdKnoppen();
};
function btdStuk(){
  const b = btVind(bt.id);
  if (!b || !b.doc || !btKluisKan()) return;
  klMetKluis(() => { const klaar = uiBezig(t('Rekening openen…'), ['onKluisChanged', 'onPauseApp']); setTimeout(klaar, 60000); Android.kluisOpen(b.doc, 0); });
}

/* ---------- één betaling ---------- */
const BTD_VELDEN = ['naam', 'iban', 'bedrag', 'kenmerk', 'omschrijving', 'verval'];
function btdVelden(b){
  $('#btd-naam').value = b.naam || '';
  $('#btd-iban').value = btIbanMooi(b.iban);
  $('#btd-bedrag').value = b.bedrag ? fmtNumIn(btGetal(b.bedrag), 2) : '';
  $('#btd-kenmerk').value = b.kenmerk || '';
  $('#btd-omschrijving').value = b.omschrijving || '';
  $('#btd-verval').value = b.vervaldatum || '';
  $('#btd-fout').hidden = true;
}
function btdRender(){
  const b = btVind(bt.id);
  if (!b) { show('betalen'); return; }
  btdVelden(b);
  const bron = $('#btd-bron');
  bron.hidden = b.bron === 'hand';
  bron.textContent = bt.anders[b.id] ? t('Let op: het IBAN uit de QR-code staat niet in de tekst van de rekening. Vergelijk het extra goed.')
    : b.bron === 'foto' ? t('Gelezen van een foto. Kijk elk veld na: tekstherkenning kan zich vergissen.') : t('Gelezen uit een bestand. Kijk elk veld na.');
  const andere = (bt.ibans[b.id] || []).filter(x => x !== b.iban), box = $('#btd-ibans');
  box.hidden = !andere.length;
  box.innerHTML = andere.map(x => '<button type="button" data-iban="' + esc(x) + '" translate="no">' + esc(btIbanMooi(x)) + '</button>').join('');
  box.querySelectorAll('button').forEach(el => el.addEventListener('click', () => { $('#btd-iban').value = btIbanMooi(el.dataset.iban); btdBewaar(); }));
  btdKnoppen();
  btdControle(b);
}
/* Klopt de ontvanger? Wat Java op de telefoon nakeek (BetalenControle): officiële lijst, bank, eerdere betalingen. */
function btdControle(b){
  const c = b.controle || {}, kaart = $('#btd-controle'), box = $('#btd-checks');
  kaart.hidden = !c.land;
  box.textContent = '';
  if (!c.land) return;
  const rij = (soort, tekst, bron) => {
    const p = document.createElement('p'); p.className = 'bt-check ' + soort;
    const ic = document.createElement('span'); ic.className = 'bt-checkic'; ic.setAttribute('aria-hidden', 'true'); ic.textContent = soort === 'ok' ? '✓' : soort === 'let' ? '!' : '•';
    const tx = document.createElement('span'); tx.textContent = tekst;
    p.append(ic, tx);
    if (bron && /^https:\/\/[a-z0-9.-]+\//i.test(bron)) {
      const a = document.createElement('button'); a.type = 'button'; a.className = 'linkish'; a.textContent = t('Bron bekijken');
      a.addEventListener('click', () => Android.openUrl(bron)); p.appendChild(a);
    }
    box.appendChild(p);
  };
  if (c.bekend) rij('ok', c.oud ? t('Dit is een oud rekeningnummer van {org}. Dat mag je nog gebruiken als het zo op de brief staat.', { org: c.bekend }) : t('Dit is een officieel rekeningnummer van {org}.', { org: c.bekend }), c.bron);
  if (b.naamVan && b.naamGelezen && b.naamGelezen !== b.naam) rij('info', b.naamVan === 'lijst' ? t('De naam komt uit de officiële lijst. Op de rekening las Tools: {naam}.', { naam: b.naamGelezen })
    : t('De naam komt van je vorige betaling naar dit rekeningnummer. Op de rekening las Tools: {naam}.', { naam: b.naamGelezen }));
  if (c.verdacht) rij('let', t('Let op: de rekening noemt {org}, maar dit rekeningnummer staat niet in de officiële lijst van {org}. Maak niets over voordat je het bij {org} zelf hebt nagekeken.', { org: c.verdacht }), c.bron);
  rij(c.buitenland ? 'let' : 'info', c.buitenland ? t('Buitenlandse rekening (landcode {land}). Klopt dat bij deze ontvanger?', { land: c.land })
    : c.bank ? t('Rekening bij {bank}.', { bank: c.bank }) : t('Nederlandse rekening.'));
  if (c.eerder) rij('ok', tn(c.eerder, 'Je betaalde al eens vanuit Tools naar dit rekeningnummer (aan {naam}).', 'Je betaalde al {n} keer vanuit Tools naar dit rekeningnummer (laatst aan {naam}).', { naam: c.eerderNaam || '?' }));
}
function btdZoek(){
  const b = btVind(bt.id);
  if (!b || !b.iban) return;
  askConfirm(t('Zoeken op internet?'), t('Je browser zoekt dan bij DuckDuckGo naar dit rekeningnummer. De zoekmachine ziet het rekeningnummer, verder niets van de rekening.'), t('Zoeken'), () => {
    Android.openUrl('https://duckduckgo.com/?q=' + encodeURIComponent('"' + btIbanMooi(b.iban) + '"'));
  });
}
function btdKnoppen(){
  const b = btVind(bt.id);
  if (!b) return;
  const bank = btBank(), kan = !!(bank && bank.kan), betaald = b.status === 'betaald';
  $('#btd-nieuw').hidden = !(b.nieuw && b.iban && !betaald);
  const klaar = $('#btd-klaar'), kop = $('#btd-kopieer');
  klaar.hidden = !kan || betaald;
  klaar.disabled = !b.compleet;
  klaar.textContent = bank ? t('Klaarzetten bij {bank}', { bank: bank.naam }) : t('Klaarzetten bij je bank');
  kop.className = kan || betaald ? 'big ghost' : 'big'; // kan je bank het niet automatisch, dan is kopiëren de hoofdknop
  kop.disabled = !(b.iban || b.bedrag || b.naam || b.kenmerk || b.omschrijving);
  $('#btd-stuk').hidden = !(b.doc && btKluisKan());
  const geen = $('#btd-geenstuk'); // gelezen voordat Tools de rekening bewaarde, of bewaren staat uit
  geen.hidden = !!b.doc || b.bron === 'hand' || !btKluisKan();
  geen.textContent = btInst().stuk === false ? t('Rekening bewaren staat uit (Betalen → Instellingen). Zet het aan en lees de rekening opnieuw in, dan kun je hem hier bekijken.')
    : t('De rekening zelf is bij deze betaling niet bewaard. Lees hem opnieuw in, dan bewaart Tools hem in je documentenkluis en kun je hem hier bekijken.');
  const lk = $('#btd-linkkaart'), lb = $('#btd-link');
  lk.hidden = !b.link; lb.hidden = !b.link || betaald;
  if (b.link) {
    $('#btd-linkhost').textContent = b.linkHost || '';
    $('#btd-linkuitleg').textContent = b.linkBekend ? t('Dit is het adres van een bekende betaaldienst. Controleer op de betaalpagina zelf het bedrag en de ontvanger.')
      : t('Tools kent dit adres niet als betaaldienst. Open de link alleen als je de afzender van de rekening vertrouwt, en controleer het bedrag en de ontvanger.');
  }
  $('#btd-betaald').textContent = betaald ? t('Toch nog niet betaald') : t('Betaald');
  $('#btd-stand').textContent = betaald ? (b.betaaldOp ? t('Betaald op {dag}.', { dag: fmtDate(b.betaaldOp) }) : t('Betaald.'))
    : b.status === 'klaargezet' ? t('Klaargezet bij je bank. Zet hem op Betaald als de betaling gelukt is.')
    : !b.compleet ? t('Vul de naam, het IBAN en het bedrag in om de betaling klaar te zetten.')
    : !kan && bank ? t('Bij {bank} kan Tools de overboeking niet zelf invullen. Kopieer de gegevens naar de app van je bank.', { bank: bank.naam }) : '';
}
/** Bewaart wat er in de velden staat; een fout (bijv. een IBAN dat niet klopt) staat onder de velden. true = bewaard. */
function btdBewaar(){
  const b = btVind(bt.id);
  if (!b) return false;
  const ruw = $('#btd-bedrag').value.trim(), n = ruw ? parseNum(ruw) : NaN;
  const nieuw = Object.assign({}, b, { naam: $('#btd-naam').value, iban: $('#btd-iban').value, kenmerk: $('#btd-kenmerk').value,
    omschrijving: $('#btd-omschrijving').value, vervaldatum: $('#btd-verval').value,
    bedrag: !ruw ? '' : Number.isFinite(n) && Math.abs(n * 100 - Math.round(n * 100)) < 1e-6 ? String(Math.round(n * 100) / 100) : ruw }); // meer dan twee cijfers achter de komma: Java weigert het
  const r = rdJson(Android.betalenBewaar(JSON.stringify(nieuw)), {});
  const fout = $('#btd-fout');
  fout.hidden = !!r.ok; fout.textContent = r.ok ? '' : (r.fout || t('Opslaan lukte niet.'));
  if (!r.ok) return false;
  btLaad(); const nu = btVind(bt.id); if (nu) btdVelden(nu); btdKnoppen(); // de velden tonen wat er echt bewaard is (de keuzeknoppen blijven staan: een tik erop mag niet verloren gaan)
  return true;
}
function btdKlaarzetten(){
  if (!btdBewaar()) return;
  const b = btVind(bt.id);
  if (!b || !b.compleet) return;
  // Altijd eerst laten zien wat er naar de bank gaat, zoals het bewaard is
  const wat = { iban: btIbanMooi(b.iban), naam: b.naam, bedrag: fmtMoney(btGetal(b.bedrag)) };
  askConfirm(b.nieuw ? t('Nieuwe ontvanger') : t('Klaarzetten bij je bank'), b.nieuw ? t('Klopt dit met de rekening? {bedrag} naar {iban} ten name van {naam}. Naar dit rekeningnummer heb je nog niet eerder betaald.', wat)
    : t('Klopt dit met de rekening? {bedrag} naar {iban} ten name van {naam}.', wat), t('Klopt, ga door'), () => { const m = Android.betalenKlaarzetten(b.id); if (m) toast(m); });
}
function btdLink(){
  const b = btVind(bt.id);
  if (!b || !b.link) return;
  askConfirm(t('Betaallink openen'), t('Je gaat naar {adres}. Controleer daar het bedrag en de ontvanger voor je betaalt.', { adres: b.linkHost || '' }), t('Openen'), () => {
    const m = Android.betalenOpenLink(b.id);
    if (m) { toast(m); return; }
    if (b.status === 'open') { Android.betalenStatus(b.id, 'klaargezet'); btLaad(); btdKnoppen(); }
  });
}
function btdKopieer(){
  btdBewaar();
  const b = btVind(bt.id);
  if (!b) return;
  const neem = tekst => () => { Android.betalenKopieer(tekst); toast(t('Gekopieerd')); };
  const acts = [];
  if (b.iban) acts.push([t('IBAN kopiëren'), neem(b.iban), 'copy']);
  if (b.naam) acts.push([t('Naam kopiëren'), neem(b.naam), 'copy']);
  if (b.bedrag) acts.push([t('Bedrag kopiëren'), neem(b.bedrag), 'copy']);
  if (b.kenmerk) acts.push([t('Betalingskenmerk kopiëren'), neem(b.kenmerk), 'copy']);
  if (b.omschrijving) acts.push([t('Omschrijving kopiëren'), neem(b.omschrijving), 'copy']);
  if (!acts.length) return;
  openSheet(t('Gegevens kopiëren'), t('Plak ze in de app van je bank.'), acts);
}
function btdBetaald(){
  const b = btVind(bt.id);
  if (!b) return;
  btdBewaar();
  Android.betalenStatus(b.id, b.status === 'betaald' ? 'open' : 'betaald');
  btLaad(); btdKnoppen();
}
function btdMenu(){ openSheet(t('Betaling'), '', [[t('Verwijderen'), () => btdWis(), 'trash']]); }
function btdWis(){
  askConfirm(t('Betaling verwijderen?'), t('Alleen in Tools: bij je bank verandert er niets.'), t('Verwijderen'), () => { Android.betalenWis(bt.id); bt.id = ''; show('betalen'); });
}

/* Terug van de website van de bank: is het formulier ingevuld, dan staat de betaling klaar */
function btUitkomst(){
  if (btOud()) return;
  const r = rdJson(Android.betalenUitkomst(), null);
  if (!r || r.stand !== 'ingevuld' || !btVind(r.id)) return;
  const na = btInst().naKlaar || 'vragen', klaar = () => { btLaad(); if (current === 'betalen') btRender(); else if (current === 'betaling') btdKnoppen(); };
  Android.betalenStatus(r.id, na === 'betaald' ? 'betaald_vanzelf' : 'klaargezet'); klaar();
  if (na === 'vragen') askChoice(t('Is de betaling gelukt?'), t('Heb je de overboeking verstuurd en ondertekend bij je bank?'), t('Ja, betaald'), t('Nog niet'),
    () => { Android.betalenStatus(r.id, 'betaald'); klaar(); }, () => {});
}

/* ---------- instellingen ---------- */
function btsRender(){
  const i = btInst(), sel = $('#bts-bank');
  sel.innerHTML = ((bt.staat && bt.staat.banken) || []).map(b => '<option value="' + esc(b.id) + '" translate="no">' + esc(b.naam) + '</option>').join('');
  sel.value = i.bank || 'rabobank';
  $('#bts-na').value = i.naKlaar || 'vragen';
  const st = $('#bts-stuk'), stuk = i.stuk !== false; st.classList.toggle('on', stuk); st.setAttribute('aria-checked', stuk ? 'true' : 'false');
  $('#bts-eigen').value = (i.eigen || []).map(btIbanMooi).join(', ');
  $('#bts-fout').hidden = true;
  btsUitleg();
}
function btsUitleg(){
  const bank = ((bt.staat && bt.staat.banken) || []).find(b => b.id === $('#bts-bank').value);
  $('#bts-bankuitleg').textContent = !bank ? '' : bank.kan ? t('Tools kan de overboeking bij deze bank voor je invullen. Inloggen en ondertekenen doe je zelf.')
    : t('Bij deze bank vult Tools de overboeking niet zelf in. Je kopieert de gegevens naar de app van je bank.');
  const slot = typeof ts !== 'undefined' && ts ? (!ts.secure ? t('Kan niet: je telefoon heeft geen schermvergrendeling') : ts.aan.includes('betalen') ? t('Aan: Betalen vraagt om je vingerafdruk of code') : t('Uit: Betalen opent zonder vraag')) : '';
  $('#bts-slot').textContent = slot;
}
function btsBewaar(){
  const eigen = $('#bts-eigen').value.split(/[,;\n]+/).map(s => s.trim()).filter(Boolean);
  const r = rdJson(Android.betalenInst(JSON.stringify({ bank: $('#bts-bank').value, naKlaar: $('#bts-na').value, eigen, stuk: $('#bts-stuk').classList.contains('on') })), {});
  const fout = $('#bts-fout');
  fout.hidden = !!r.ok; fout.textContent = r.ok ? '' : (r.fout || t('Opslaan lukte niet.'));
  if (r.ok) { btLaad(); btsUitleg(); }
}

(function(){
  BTD_VELDEN.forEach(k => { const el = document.getElementById('btd-' + k); if (el) el.addEventListener('change', () => btdBewaar()); });
  const hervat = window.onResumeApp; window.onResumeApp = function(){ if (hervat) hervat(); if (BT_SCHERMEN.includes(current)) btResume(); };
  const pauze = window.onPauseApp; window.onPauseApp = function(){ if (pauze) pauze(); if (btCamOpen()) btCamDicht(); };
})();
