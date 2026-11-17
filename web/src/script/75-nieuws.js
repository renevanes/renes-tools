/* Nieuws: artikelen uit de nieuwsfeeds die Java ophaalt (Nieuws.java). Alles uit een feed is gegevens: altijd als tekst
 * (esc of textContent), nooit als HTML van de site. */
const nw = { data: null, filter: 'alles', open: null, toon: 60, scroll: 0, bezig: false, nogEens: false, toevoegen: false, gelezen: new Set() };
function nwData(raw){ try { return JSON.parse(raw); } catch(e){ return { error: t('Nieuws lezen lukt niet') }; } }
function nwKlaar(){ return typeof Android.nieuwsLijst === 'function'; }
function nwBeeldenAan(){ return store.get('nwBeelden', true) !== false; }
function nwLaad(){
  const r = nwData(Android.nieuwsLijst());
  if (r.error) { nwStand(r.error); return false; }
  nw.data = r; nw.gelezen = new Set(r.gelezen || []);
  if (r.bezig && !nw.bezig) { nw.bezig = true; setTimeout(nwWachtOpAnder, 2000); }
  return true;
}
/* Een bijwerkronde die al liep (bijv. vanuit een ander venster): kijken tot hij klaar is. */
function nwWachtOpAnder(){
  if (!nwKlaar()) return;
  const r = nwData(Android.nieuwsLijst());
  if (r.bezig) { setTimeout(nwWachtOpAnder, 2000); return; }
  window.onNieuws({});
}
function nwBronnen(){ return (nw.data && nw.data.bronnen) || []; }
function nwNamen(){ const m = {}; nwBronnen().forEach(b => { m[b.id] = b.naam; }); return m; }
function nwOud(){
  const nu = Date.now();
  return nwBronnen().some(b => b.aan && (!b.t || b.t > nu || nu - b.t > 15 * 60000) && !(b.fout && b.poging <= nu && nu - b.poging < 2 * 60000));
}
function nwWanneer(ms){
  if (!ms) return '';
  const s = Math.max(0, (Date.now() - ms) / 1000), hm = fmtTime(ms);
  if (s < 90) return t('net');
  if (s < 3600) return t('{n} min geleden', { n: Math.round(s / 60) });
  if (s < 6 * 3600) return tn(Math.floor(s / 3600), '{n} uur geleden', '{n} uur geleden');
  if (rgIsToday(ms)) return t('vandaag {tijd}', { tijd: hm });
  if (rgIsYesterday(ms)) return t('gisteren {tijd}', { tijd: hm });
  const jaar = rgParts(ms).y === rgParts(Date.now()).y;
  return fmtDate(ms, jaar ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' }) + ' ' + hm;
}

/* ---------- overzicht ---------- */
function enterNieuws(prev){
  if (!nwKlaar()) { nwStand(t('Installeer de nieuwste APK om Nieuws te gebruiken')); $('#nw-lijst').innerHTML = ''; return; }
  if (prev !== 'nieuwsart') nw.toon = 60;
  if (!nwLaad()) return;
  nwRender();
  if (prev === 'nieuwsart') window.scrollTo(0, nw.scroll || 0);
  if (nwOud()) nwVerversen(false);
}
function nwResume(){ if (current === 'nieuws' && nwKlaar() && nwLaad()) { nwRender(); if (nwOud()) nwVerversen(false); } }
function nwStand(tekst){ $('#nw-stand').textContent = tekst || ''; }
function nwStandBij(){
  if (nw.bezig) { uiWachtTekst($('#nw-stand'), t('Bijwerken…')); return; }
  const aan = nwBronnen().filter(b => b.aan);
  if (!aan.length) { nwStand(''); return; }
  const laatst = Math.max(0, ...aan.map(b => b.t || 0)), mis = aan.filter(b => b.fout && (b.poging || 0) >= (b.t || 0)).length;
  nwStand([laatst ? t('Bijgewerkt {wanneer}', { wanneer: nwWanneer(laatst) }) : '', mis ? tn(mis, 'Eén bron lukte niet', '{n} bronnen lukten niet') : ''].filter(Boolean).join(' · '));
}
function nwFilter(v){
  nw.filter = v; nw.toon = 60;
  document.querySelectorAll('#nw-filter button').forEach(b => { const on = b.dataset.v === v; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  nwRender();
}
function nwBronKeuze(lijst){
  const sel = $('#nw-bron'), was = sel.value, namen = nwNamen(), ids = [];
  lijst.forEach(a => { if (!ids.includes(a.bron)) ids.push(a.bron); });
  const opties = ids.map(id => [id, namen[id] || (lijst.find(a => a.bron === id) || {}).bronnaam || id]).sort((x, y) => x[1].localeCompare(y[1]));
  sel.hidden = opties.length < 2;
  const html = '<option value="">' + esc(t('Alle bronnen')) + '</option>' + opties.map(o => '<option value="' + esc(o[0]) + '" translate="no">' + esc(o[1]) + '</option>').join('');
  if (sel._h !== html) { sel.innerHTML = html; sel._h = html; }
  sel.value = opties.some(o => o[0] === was) ? was : '';
}
function nwZichtbaar(){
  const d = nw.data || {};
  let lijst = nw.filter === 'bewaard' ? (d.bewaard || []) : (d.artikelen || []);
  nwBronKeuze(lijst);
  const bron = $('#nw-bron').value, q = $('#nw-zoek').value.trim().toLowerCase();
  if (bron) lijst = lijst.filter(a => a.bron === bron);
  if (nw.filter === 'ongelezen') lijst = lijst.filter(a => !nw.gelezen.has(a.id));
  if (q) lijst = lijst.filter(a => (a.titel + ' ' + (a.sam || '')).toLowerCase().includes(q));
  return lijst;
}
function nwRij(a, namen, beelden){
  const nieuw = !nw.gelezen.has(a.id);
  const meta = [namen[a.bron] || a.bronnaam || '', nwWanneer(a.datum)].filter(Boolean).join(' · ');
  const img = beelden && /^https:\/\//.test(a.beeld || '') ? '<img class="nw-img" src="' + esc(a.beeld) + '" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">' : '';
  return '<button class="nw-rij"' + (nieuw ? ' data-new="1"' : '') + ' onclick="nwOpen(' + jsq(a.id) + ')"><span class="nw-txt">' +
    '<small class="nw-meta" translate="no">' + esc(meta) + (nieuw ? '<span class="sr-only">, ' + esc(t('ongelezen')) + '</span>' : '') + '</small>' +
    '<b translate="no">' + esc(a.titel) + '</b>' + (a.sam ? '<span class="nw-sam" translate="no">' + esc(a.sam) + '</span>' : '') + '</span>' + img + '</button>';
}
function nwRender(){
  if (!nw.data) return;
  nwStandBij();
  nwBeeldBalk();
  const ververs = $('#nw-ververs'); ververs.disabled = nw.bezig; ververs.classList.toggle('nw-draai', nw.bezig);
  const lijst = nwZichtbaar(), el = $('#nw-lijst'), alle = nw.data.artikelen || [];
  const zoek = $('#nw-zoek').value.trim() || $('#nw-bron').value;
  $('#nw-meer').disabled = !alle.length;
  if (!lijst.length) {
    let html;
    if (nw.filter === 'bewaard') html = uiEmpty('bookmark', zoek ? t('Niets gevonden bij deze zoekopdracht.') : t('Nog niets bewaard. Tik bij een artikel op Bewaren.'));
    else if (!nwBronnen().some(b => b.aan)) html = uiEmpty('news', t('Er staat geen bron aan.'), t('Bronnen kiezen'), "show('nieuwsset')");
    else if (!alle.length) html = nw.bezig ? uiEmpty('news', t('Nieuws ophalen…')) : uiEmpty('news', t('Nog geen nieuws.'), t('Bijwerken'), 'nwVerversen(true)');
    else if (zoek) html = uiEmpty('search', t('Niets gevonden bij deze zoekopdracht.'));
    else html = uiEmpty('check', t('Je hebt alles gelezen.'));
    uiHtml(el, html);
    return;
  }
  const namen = nwNamen(), beelden = nwBeeldenAan();
  const meer = lijst.length > nw.toon ? '<button class="big ghost nw-verder" onclick="nwVerder()">' + esc(t('Meer tonen')) + '</button>' : '';
  uiHtml(el, '<div class="card nw-lijst">' + lijst.slice(0, nw.toon).map(a => nwRij(a, namen, beelden)).join('') + '</div>' + meer);
}
function nwVerder(){ nw.toon += 60; nwRender(); }
function nwVerversen(alles){
  if (!nwKlaar()) return;
  if (nw.bezig) { nw.nogEens = alles || nw.nogEens === 'alles' ? 'alles' : true; return; } // bijv. een bron aangezet tijdens het bijwerken: daarna nog een ronde
  if (alles && !nwBronnen().some(b => b.aan)) { toast(t('Zet eerst een bron aan')); show('nieuwsset'); return; }
  nw.bezig = true;
  if (current === 'nieuws') nwRender();
  if (current === 'nieuwsset' && nw.data) nwCookieRender();
  Android.nieuwsVerversen(!!alles, nwBeeldenAan());
}
window.onNieuws = function(r){
  r = r || {};
  nw.bezig = false;
  if (r.bezig) { nw.bezig = true; setTimeout(nwWachtOpAnder, 2000); }
  if (r.error) toast(r.error);
  if (!nwLaad()) return;
  if (current === 'nieuws') { nwRender(); if (r.nieuw > 0) toast(tn(r.nieuw, 'Eén nieuw artikel', '{n} nieuwe artikelen')); }
  if (current === 'nieuwsset') nwSetRender();
  if (nw.nogEens && !nw.bezig) { const alles = nw.nogEens === 'alles'; nw.nogEens = false; nwVerversen(alles); }
};
function nwMenu(){
  const lijst = nwZichtbaar().filter(a => !nw.gelezen.has(a.id));
  openSheet(t('Nieuws'), '', [[t('Alles als gelezen markeren'), () => nwAllesGelezen(lijst), 'check'],
    [t('Bronnen importeren (OPML)'), () => opmlImport('nieuws')], [t('Bronnen exporteren (OPML)'), () => opmlExport('nieuws')]]);
}
function nwAllesGelezen(lijst){
  const ids = lijst.map(a => a.id);
  if (!ids.length) { toast(t('Je hebt alles gelezen.')); return; }
  const fout = Android.nieuwsGelezen(JSON.stringify(ids), true);
  if (fout) { toast(fout); return; }
  ids.forEach(id => nw.gelezen.add(id)); nwRender();
  undoToast(tn(ids.length, 'Eén artikel als gelezen gemarkeerd', '{n} artikelen als gelezen gemarkeerd'), () => {
    const f = Android.nieuwsGelezen(JSON.stringify(ids), false);
    if (f) { toast(f); return; }
    ids.forEach(id => nw.gelezen.delete(id)); if (current === 'nieuws') nwRender();
  });
}

/* ---------- artikel ---------- */
function nwOpen(id){
  const a = nwData(Android.nieuwsArtikel(id));
  if (a.error) { toast(a.error); return; }
  nw.open = a; nw.scroll = window.scrollY;
  if (!a.gelezen) {
    const fout = Android.nieuwsGelezen(JSON.stringify([id]), true);
    if (!fout) { nw.gelezen.add(id); a.gelezen = true; }
  }
  show('nieuwsart');
}
function nwArtRender(){
  const a = nw.open;
  if (!a) { show('nieuws'); return; }
  $('#nwa-bron').textContent = nwNamen()[a.bron] || a.bronnaam || t('Nieuws');
  $('#nwa-titel').textContent = a.titel;
  const jaar = a.datum && rgParts(a.datum).y !== rgParts(Date.now()).y ? { year: 'numeric' } : {};
  $('#nwa-meta').textContent = [a.datum ? fmtDate(a.datum, Object.assign({ weekday: 'long', day: 'numeric', month: 'long' }, jaar)) + ' ' + fmtTime(a.datum) : '', a.auteur ? t('door {naam}', { naam: a.auteur }) : ''].filter(Boolean).join(' · ');
  const img = $('#nwa-beeld');
  img.hidden = true; img.onload = () => { img.hidden = false; }; img.onerror = () => { img.hidden = true; };
  if (nwBeeldenAan() && /^https:\/\//.test(a.beeld || '')) img.src = a.beeld; else img.removeAttribute('src');
  const tekst = $('#nwa-tekst'), blokken = a.blokken || [];
  tekst.textContent = '';
  blokken.forEach(b => { const el = document.createElement(b.k ? 'h3' : 'p'); el.textContent = b.t; tekst.appendChild(el); });
  const lang = blokken.reduce((n, b) => n + (b.t || '').length, 0);
  $('#nwa-kort').hidden = !a.link || !(a.ingekort || lang < 700);
  $('#nwa-web').hidden = !a.link;
  nwBewaarKnop();
}
function nwBewaarKnop(){
  const b = $('#nwa-bewaar'), aan = !!(nw.open && nw.open.isBewaard);
  b.textContent = aan ? t('Niet meer bewaren') : t('Bewaren');
  b.classList.toggle('tonal', !aan); b.classList.toggle('ghost', aan);
}
function nwBewaarArt(){
  const a = nw.open; if (!a) return;
  const aan = !a.isBewaard, fout = Android.nieuwsBewaar(a.id, aan);
  if (fout) { toast(fout); return; }
  a.isBewaard = aan; nwLaad(); nwBewaarKnop();
  toast(aan ? t('Bewaard') : t('Niet meer bewaard'));
}
/* Lees op de website: bij een bron met inloggen (en Lezen in Tools) in het venster van de bron, anders in de browser. */
function nwWeb(){
  const a = nw.open; if (!a || !a.link) return;
  const b = nwBronnen().find(x => x.id === a.bron);
  if (b && b.inlog && b.lezen === 'app' && typeof Android.nieuwsLees === 'function' && Android.nieuwsLees(b.id, a.link) !== 'extern') return;
  Android.openUrl(a.link);
}
function nwDeel(a){ Android.nieuwsDeel(a.titel, a.link); }
function nwArtMenu(){
  const a = nw.open; if (!a) return;
  const acts = [];
  if (a.link) acts.push([t('Delen'), () => nwDeel(a), 'share']);
  acts.push([t('Markeren als ongelezen'), () => {
    const fout = Android.nieuwsGelezen(JSON.stringify([a.id]), false);
    if (fout) { toast(fout); return; }
    nw.gelezen.delete(a.id); a.gelezen = false; goBack();
  }, 'eye']);
  openSheet(a.titel, '', acts);
}

/* ---------- instellingen ---------- */
function enterNieuwsSet(){
  const b = $('#nws-img'), on = nwBeeldenAan();
  b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false');
  if (!nwKlaar()) { nwSetStatus(t('Installeer de nieuwste APK om Nieuws te gebruiken')); $('#nws-add').disabled = true; return; }
  $('#nws-add').disabled = nw.toevoegen;
  if (nwLaad()) nwSetRender();
}
function nwSetStatus(tekst){ const e = $('#nws-status'); e.textContent = tekst || ''; e.hidden = !tekst; }
function nwBronStand(b){
  if (b.fout && (b.poging || 0) >= (b.t || 0) && b.aan) return b.fout;
  if (!b.aan) return nwHost(b.url);
  if (!b.t) return t('Nog niet opgehaald');
  return [tn(b.aantal || 0, 'Eén artikel', '{n} artikelen'), t('bijgewerkt {wanneer}', { wanneer: nwWanneer(b.t) })].join(' · ');
}
function nwHost(url){ try { return new URL(url).hostname.replace(/^www\./, ''); } catch(e){ return ''; } }
function nwSetRender(){
  const html = nwBronnen().map(b => {
    const k = 'nws-b-' + b.id, fout = b.aan && b.fout && (b.poging || 0) >= (b.t || 0);
    return '<div class="srow"><button class="nws-open" onclick="nwBronOpen(' + jsq(b.id) + ')"><span class="stxt"><b id="' + esc(k) + '" translate="no">' + esc(b.naam) + '</b><small translate="no"' + (fout ? ' class="nws-fout"' : '') + '>' + esc(nwBronStand(b)) + '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>' +
      (b.eigen ? '<button class="icon-btn nws-weg" data-icon="trash" onclick="nwVerwijder(' + jsq(b.id) + ')" aria-label="' + esc(t('{naam} verwijderen', { naam: b.naam })) + '"></button>' : '') +
      '<button class="tg' + (b.aan ? ' on' : '') + '" role="switch" aria-checked="' + (b.aan ? 'true' : 'false') + '" aria-labelledby="' + esc(k) + '" translate="no" onclick="togUi(this); nwBronAan(' + jsq(b.id) + ', this.classList.contains(\'on\'))"></button></div>';
  }).join('');
  uiHtml($('#nws-bronnen'), html);
  nwCookieRender();
}
/* Eenmalig instellen: de cookiekeuze voor een bron die de afbeeldingen van de artikelpagina haalt (Tweakers). */
function nwCookieBron(){ return nwBeeldenAan() ? nwBronnen().find(b => b.aan && b.cookiekeuze && b.pagina !== false) : null; }
function nwCookieRender(){
  const el = $('#nws-cookie'), b = nwCookieBron();
  el.hidden = !b;
  if (!b) return;
  const stand = b.beeldStand || '';
  // alleen een toestemmingspagina (of nog niets geprobeerd) vraagt om de keuze; een gewone fout klapt de kaart niet open
  uiSetup(el, stand === 'ok' || stand === 'fout');
  el.classList.toggle('nws-mis', stand === 'fout');
  const st = nw.bezig ? t('Nakijken…') : stand === 'ok' ? tc('status', 'Klaar') : stand === 'cookiekeuze' ? t('Nog niet klaar')
    : stand === 'fout' ? t('Ophalen lukte niet') : t('Nog niet nagekeken');
  const uitleg = t('{naam} laat de artikelpagina met de afbeelding pas zien na een cookiekeuze. Die maak je één keer zelf, op de pagina van de site.', { naam: b.naam });
  const privacy = t('Tools stuurt daarna de cookies van deze site mee als het de afbeeldingen ophaalt, net als een browser: de site herkent je en ziet welk soort telefoon je hebt.');
  const reden = stand === 'fout' && !nw.bezig ? nwBeeldReden(b) : '';
  [['#nws-cookie-st', st], ['#nws-cookie-uitleg', uitleg], ['#nws-cookie-privacy', privacy], ['#nws-cookie-reden', reden]].forEach(([s, v]) => { if ($(s).textContent !== v) $(s).textContent = v; });
  $('#nws-cookie-reden').hidden = !reden;
}
/* Waarom de afbeeldingen van de artikelpagina's niet lukten (Nieuws.reden in Java), als zin; '' als dat niets toevoegt. */
function nwBeeldReden(b){
  const r = (b && b.beeldReden) || '';
  if (/^\d{3}$/.test(r)) return t('De site antwoordde met code {code}.', { code: r });
  return { geenkop: t('De site gaf iets anders terug dan de artikelpagina, bijvoorbeeld een cookiemuur.'), traag: t('De site reageerde te traag.'),
    verbinding: t('Er was geen verbinding.'), beveiliging: t('Er was geen veilige verbinding met de site.') }[r] || '';
}
/*
 * Op het nieuwsscherm zelf: de afbeeldingen van een bron wachten op de cookiekeuze, of het ophalen lukte niet. Niet bij
 * iets wat vanzelf overgaat (te traag, geen verbinding); tijdens het bijwerken blijft de balk staan, zodat de lijst niet verspringt.
 */
function nwBeeldBalk(){
  const el = $('#nw-beeldbalk'), b = nwCookieBron(), stand = b ? b.beeldStand || '' : '';
  const toon = !!b && (stand === 'cookiekeuze' || (stand === 'fout' && !['traag', 'verbinding'].includes(b.beeldReden || '')));
  el.style.display = toon ? '' : 'none';
  if (!toon) return;
  const titel = stand === 'cookiekeuze' ? t('Afbeeldingen van {naam}: eerst een cookiekeuze', { naam: b.naam }) : t('Afbeeldingen van {naam} ophalen lukte niet', { naam: b.naam });
  const sub = nw.bezig ? t('Nakijken…') : stand === 'cookiekeuze' ? t('Die maak je één keer zelf. Tik om te regelen.') : (nwBeeldReden(b) || t('Tik voor meer.'));
  [['#nw-beeldbalk-t', titel], ['#nw-beeldbalk-s', sub]].forEach(([s, v]) => { if ($(s).textContent !== v) $(s).textContent = v; });
}
/* De balk: naar de instellingen, met de kaart van de cookiekeuze open en in beeld (ook bij een fout, waar hij anders dicht blijft). */
function nwBeeldBalkOpen(){
  show('nieuwsset');
  const el = $('#nws-cookie');
  if (el.hidden) return;
  uiOpenTo(el);
  el.scrollIntoView({ block: 'start' });
  // na de focus op de kop die show() later zet: de schermlezer begint bij de kaart, niet bij de bronnenlijst
  setTimeout(() => el.querySelector('summary').focus({ preventScroll: true }), 0);
}
function nwCookies(){
  const b = nwCookieBron(); if (!b) return;
  const fout = Android.nieuwsCookies(b.id);
  if (fout) { toast(fout); return; }
  nw.cookie = true;
}
(function(){
  const prev = window.onResumeApp;
  window.onResumeApp = function(){
    if (typeof prev === 'function') prev();
    if (nw.cookie) { nw.cookie = false; nwVerversen(true); }
    if (nw.inlog) { nw.inlog = false; if (current === 'nieuwsbron') nwbVul(false); nwVerversen(true); }
  };
})();
function nwBronAan(id, aan){
  const fout = Android.nieuwsBron(id, aan);
  if (fout) { toast(fout); }
  if (nwLaad()) nwSetRender();
  if (!fout && aan) nwVerversen(false);
}
function nwVerwijder(id, daarna){
  const b = nwBronnen().find(x => x.id === id); if (!b) return;
  askConfirm(t('Bron verwijderen?'), t('{naam} en de opgehaalde artikelen verdwijnen uit Nieuws. Wat je bewaard hebt, blijft staan.', { naam: b.naam }), t('Verwijderen'), () => {
    const fout = Android.nieuwsVerwijder(id);
    if (fout) { toast(fout); return; }
    if (nwLaad()) nwSetRender();
    toast(t('Bron verwijderd'));
    if (daarna) daarna();
  });
}
function nwToevoegen(){
  if (nw.toevoegen || !nwKlaar()) return;
  const v = $('#nws-url').value.trim();
  if (!v) { nwSetStatus(t('Vul het adres van een nieuwssite of feed in')); $('#nws-url').focus(); return; }
  nw.toevoegen = true; $('#nws-add').disabled = true; $('#nws-status').hidden = false; uiWachtTekst($('#nws-status'), t('Feed zoeken…'));
  Android.nieuwsToevoegen(v);
}
window.onNieuwsBron = function(r){
  r = r || {};
  nw.toevoegen = false; $('#nws-add').disabled = false;
  if (r.error) { nwSetStatus(r.error); return; }
  const b = r.bron || {};
  $('#nws-url').value = '';
  nwSetStatus(b.al ? t('{naam} stond er al en staat nu aan.', { naam: b.naam }) : t('{naam} toegevoegd.', { naam: b.naam }));
  if (nwLaad() && current === 'nieuwsset') nwSetRender();
  if (b.al) nwVerversen(false);
};
function nwBeelden(on){ store.set('nwBeelden', !!on); if (nw.data) nwCookieRender(); }

/* ---------- één bron: alle instellingen, voor elke bron op dezelfde manier (ook inloggen) ---------- */
function nwBronOpen(id){ nw.bron = id; show('nieuwsbron'); }
function nwbBron(){ return nwBronnen().find(b => b.id === nw.bron); }
function nwbKlaar(){ return nwKlaar() && typeof Android.nieuwsInstellen === 'function'; }
function nwbStatus(tekst){ const e = $('#nwb-status'); e.textContent = tekst || ''; e.hidden = !tekst; }
function enterNieuwsBron(){
  nwbStatus('');
  if (!nwbKlaar()) { nwbStatus(t('Installeer de nieuwste APK om de bron in te stellen')); $('#nwb-opslaan').disabled = true; }
  else $('#nwb-opslaan').disabled = !!nw.instellen;
  if (!nwKlaar() || !nwLaad()) return;
  nwbVul(true);
}
function nwbSchakel(sel, aan){ const b = $(sel); b.classList.toggle('on', !!aan); b.setAttribute('aria-checked', aan ? 'true' : 'false'); }
/* velden = ook de invoervelden opnieuw vullen (bij binnenkomen en na Opslaan; niet na een schakelaar, dan blijft wat je aan het typen bent). */
function nwbVul(velden){
  const b = nwbBron();
  if (!b) { if (current === 'nieuwsbron') show('nieuwsset'); return; }
  $('#nwb-titel').textContent = b.naam;
  const st = $('#nwb-stand'), fout = b.aan && b.fout && (b.poging || 0) >= (b.t || 0);
  st.textContent = nwBronStand(b); st.classList.toggle('nws-fout', !!fout);
  nwbSchakel('#nwb-aan', b.aan); nwbSchakel('#nwb-inlog', b.inlog); nwbSchakel('#nwb-pagina', b.pagina); nwbSchakel('#nwb-derden', !!b.derden);
  if (velden) {
    $('#nwb-naam').value = b.naam; $('#nwb-url').value = b.url; $('#nwb-alleen').value = b.alleen || '';
    $('#nwb-start').value = b.inlogStart || ''; $('#nwb-sites').value = b.inlogSites || '';
  }
  const std = $('#nwb-url-std'), anders = !b.eigen && b.standaardUrl && b.url !== b.standaardUrl;
  std.hidden = !anders; std.textContent = anders ? t('Standaard: {adres}', { adres: b.standaardUrl }) : '';
  $('#nwb-inlog-deel').hidden = !b.inlog;
  $('#nwb-lezen').value = b.lezen === 'app' ? 'app' : 'browser';
  if (b.inlog) {
    let n = 0;
    try { n = typeof Android.nieuwsInlogStand === 'function' ? (JSON.parse(Android.nieuwsInlogStand(b.id)).koekjes || 0) : 0; } catch(e){ n = 0; }
    $('#nwb-inlog-st').textContent = n ? t('De site heeft je inlog bewaard (cookies op je telefoon).') : t('Nog niet ingelogd.');
    $('#nwb-uitloggen').disabled = !n;
  }
  const ps = $('#nwb-pagina-st'), stand = b.beeldStand || '';
  const ptekst = !b.pagina || !nwBeeldenAan() ? (b.pagina ? t('Afbeeldingen tonen staat uit bij de instellingen van Nieuws.') : '')
    : stand === 'cookiekeuze' ? (b.cookiekeuze ? t('Eerst een cookiekeuze maken.') : t('De site stuurt eerst naar een andere site, bijvoorbeeld een cookiemuur.'))
    : stand === 'fout' ? (nwBeeldReden(b) || t('Ophalen lukte niet')) : '';
  ps.textContent = ptekst; ps.hidden = !ptekst;
  $('#nwb-cookie').hidden = !(b.cookiekeuze && b.pagina);
  $('#nwb-standaard').hidden = !b.aangepast;
  $('#nwb-weg').hidden = !b.eigen;
}
/* Een schakelaar of keuzelijst: meteen opslaan. Aan/uit gaat zoals in de lijst met bronnen. */
function nwbZet(wijz){
  const b = nwbBron(); if (!b) return;
  if ('aan' in wijz) { nwBronAan(b.id, wijz.aan); nwbVul(false); return; }
  nwbStuur(wijz);
}
function nwbOpslaan(){
  const b = nwbBron(); if (!b) return;
  const w = { naam: $('#nwb-naam').value, url: $('#nwb-url').value.trim(), alleen: $('#nwb-alleen').value };
  if (b.inlog) { w.inlogStart = $('#nwb-start').value.trim(); w.inlogSites = $('#nwb-sites').value; }
  if (!w.url) { nwbStatus(t('Vul het adres van een nieuwssite of feed in')); $('#nwb-url').focus(); return; }
  nwbStuur(w, true);
}
function nwbStuur(wijz, velden){
  if (nw.instellen || !nwbKlaar()) { nwbVul(false); return; } // een schakelaar die al omging, terug zoals hij is
  nw.instellen = { wijz, velden: !!velden };
  $('#nwb-opslaan').disabled = true; $('#nwb-status').hidden = false;
  uiWachtTekst($('#nwb-status'), 'url' in wijz ? t('Feed nakijken…') : t('Opslaan…'));
  Android.nieuwsInstellen(nw.bron, JSON.stringify(wijz));
}
window.onNieuwsInstellen = function(r){
  r = r || {};
  const was = nw.instellen || { wijz: {} };
  nw.instellen = null; $('#nwb-opslaan').disabled = false;
  nwLaad();
  if (r.error) { nwbStatus(r.error); if (current === 'nieuwsbron') nwbVul(false); return; }
  nwbStatus(was.wijz.standaard ? t('Standaard teruggezet.') : t('Opgeslagen'));
  if (current === 'nieuwsbron') {
    nwbVul(!!was.velden || !!was.wijz.standaard);
    // inloggen aan of uit: alleen de velden van het inloggen opnieuw (wat je verder aan het typen bent, blijft)
    if ('inlog' in was.wijz && r.bron) { $('#nwb-start').value = r.bron.inlogStart || ''; $('#nwb-sites').value = r.bron.inlogSites || ''; }
  }
  if (current === 'nieuwsset') nwSetRender();
  // een andere feed, filter of inlog: meteen opnieuw ophalen (de oude artikelen van die bron zijn weg)
  if (r.bron && r.bron.aan && ['url', 'alleen', 'inlog', 'inlogSites', 'pagina', 'standaard'].some(k => k in was.wijz)) nwVerversen(true);
};
function nwbInloggen(){
  const b = nwbBron(); if (!b) return;
  const fout = Android.nieuwsInloggen(b.id);
  if (fout) { toast(fout); return; }
  nw.inlog = true;
}
function nwbUitloggen(){
  const b = nwbBron(); if (!b) return;
  askConfirm(t('Uitloggen bij {naam}?', { naam: b.naam }), t('De cookies en websitegegevens van de toegestane sites gaan weg van je telefoon. Daarna haalt Tools alleen nog op wat iedereen mag zien.'), t('Uitloggen'), () => {
    const fout = Android.nieuwsUitloggen(b.id);
    if (fout) { toast(fout); return; }
    toast(t('Uitgelogd')); nwbVul(false);
  });
}
function nwbCookies(){
  const b = nwbBron(); if (!b) return;
  const fout = Android.nieuwsCookies(b.id);
  if (fout) { toast(fout); return; }
  nw.cookie = true;
}
function nwbStandaard(){
  const b = nwbBron(); if (!b) return;
  askConfirm(t('Standaard terugzetten?'), b.eigen ? t('Je instellingen van {naam} gaan terug naar het begin: inloggen, afbeeldingen en welke artikelen. Naam en adres blijven. Je inlog bij de site blijft bewaard.', { naam: b.naam })
    : t('Je wijzigingen aan {naam} gaan weg: naam, adres, inloggen en de rest. Je inlog bij de site blijft bewaard.', { naam: b.naam }), t('Terugzetten'), () => nwbStuur({ standaard: true }, true));
}
function nwbVerwijder(){ const b = nwbBron(); if (b && b.eigen) nwVerwijder(b.id, () => { if (current === 'nieuwsbron') goBack(); }); }
