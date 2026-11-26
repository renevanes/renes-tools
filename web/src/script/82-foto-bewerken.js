/* ---------- Foto bewerken (tool) en de koppelingen met Stamgegevens en ID-kopie ----------
 * De tool: een foto kiezen (bronKies: ook uit de kluis of Gemaakt), bewerken in de gedeelde bewerker (02-fotobewerken.js)
 * en bewaren. Java past de bewerking toe op de foto zelf (FotoBridge.fotoBewaar) en zet hem als JPG onder Gemaakt; daarna
 * gaat hij vanzelf de documentenkluis in (kcNaMaken), zoals elk resultaat van de PDF-tools.
 * Verbetering voorstellen (store fotoVoorstel, standaard aan): bij een nieuwe foto zoekt de app zelf het document, zet het
 * rechtop en verbetert de belichting. Ook bij een nieuwe kaartfoto in Stamgegevens (een voorstel op het documentkaartje,
 * met Toepassen) en bij ID-kopie (meteen toegepast; Bijsnijden en rechtzetten om het aan te passen of terug te draaien). */
const fob = { sessie: '', naam: '', src: '', ops: null, uit: '', bezig: false, nr: 0 };
function fobVoorstelAan(){ return store.get('fotoVoorstel', true) !== false; }
function fobSoort(){ const v = store.get('fotoSoort', 'doc'); return ['doc', 'idkaart', 'paspoort', 'foto'].includes(v) ? v : 'doc'; }
function fobEnter(name, prev){
  if (prev === 'foto' && name !== 'foto') { if (fbAan() && fb.opts.bron === 'foto') fbSluit(null); fobWis(); }
  if (name === 'foto') fobRender();
}
function fobWis(){
  fob.nr++; fob.sessie = ''; fob.naam = ''; fob.src = ''; fob.ops = null; fob.bezig = false;
  if (typeof Android.fotoWis === 'function') Android.fotoWis();
}
function fobRender(){
  const kan = typeof Android.fotoKies === 'function';
  $('#fob-oud').hidden = kan;
  const leeg = $('#fob-leeg'); leeg.hidden = !kan || !!fob.sessie;
  if (!leeg.hidden && !leeg.childElementCount) leeg.innerHTML = uiEmpty('image', t('Kies een foto om bij te snijden, te draaien of te verbeteren.'), t('Foto kiezen'), 'fobKies()');
  $('#fob-kaart').hidden = !fob.sessie;
  $('#fob-soort').value = fobSoort();
  const v = $('#fob-voorstel'), aan = fobVoorstelAan(); v.classList.toggle('on', aan); v.setAttribute('aria-checked', aan ? 'true' : 'false');
  if (fob.sessie) {
    $('#fob-naam').textContent = fob.naam;
    $('#fob-stand').textContent = fob.ops && fbIets(fob.ops) ? t('Bewerkt. Bewaren maakt een nieuwe foto; het origineel blijft waar het stond.') : t('Nog niet bewerkt.');
    $('#fob-bewaar').disabled = fob.bezig || !fob.ops || !fbIets(fob.ops); $('#fob-bewerk').disabled = fob.bezig;
  }
}
function fobKies(){
  if (fob.bezig || typeof Android.fotoKies !== 'function') return;
  bronKies({ titel: t('Foto kiezen'), soort: 'beeld', kiezer: 'foto', bestand: () => { fob.bezig = true; Android.fotoKies(); } });
}
window.onFotoGekozen = function(r){
  fob.bezig = false;
  if (!r || r.cancelled) { fobRender(); return; }
  if (r.error) { fobRender(); toast(t(r.error)); return; }
  fob.sessie = r.sessie; fob.naam = r.naam || t('Foto'); fob.src = r.image; fob.ops = null; fob.uit = '';
  $('#fob-naamin').value = t('{naam} bewerkt', { naam: fob.naam }).slice(0, 60);
  $('#fob-klaar').hidden = true; $('#fob-beeld').src = r.image;
  fobRender(); fobBewerk(true);
};
async function fobBewerk(nieuw){
  if (!fob.sessie || fob.bezig) return;
  const nr = ++fob.nr, soort = fobSoort() === 'foto' ? '' : fobSoort();
  const res = await fotoBewerk({ src: fob.src, bron: 'foto', scherm: 'foto', titel: t('Foto bewerken'), knop: t('Klaar'), soort, ops: fob.ops || undefined,
    voorstel: nieuw === true && fobVoorstelAan(), uitleg: nieuw === true && fobVoorstelAan() ? t('Tools zoekt zelf een verbetering. Pas aan wat niet klopt.') : '' });
  if (!res || nr !== fob.nr || !fob.sessie) { fobRender(); return; }
  fob.ops = res.ops; fobRender(); fobVoorbeeld();
}
/** Het voorbeeld van de bewerkte foto op de tool. */
async function fobVoorbeeld(){
  const nr = fob.nr, img = new Image();
  img.onload = async () => {
    const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight; c.getContext('2d').drawImage(img, 0, 0);
    try { const u = await fbRender(c, fob.ops, 900); if (nr === fob.nr) $('#fob-beeld').src = u.toDataURL('image/jpeg', 0.85); } catch (e) { }
  };
  img.src = fob.src;
}
function fobBewaar(){
  if (!fob.sessie || fob.bezig || !fob.ops) return;
  fob.bezig = true; fobRender(); uiWachtTekst($('#fob-stand'), t('Bewaren…'));
  Android.fotoBewaar(fob.sessie, JSON.stringify(fob.ops), $('#fob-naamin').value.trim() || t('Foto bewerkt'));
}
window.onFotoBewaard = function(r){
  fob.bezig = false;
  if (!r || r.error) { fobRender(); $('#fob-stand').textContent = t(r && r.error ? r.error : 'Opslaan lukte niet.'); return; }
  fob.uit = r.name; $('#fob-klaar-naam').textContent = r.name; $('#fob-klaar').hidden = false;
  $('#fob-klaar-t').textContent = kcAan() ? t('Bewaard. De foto gaat versleuteld de documentenkluis in (map Foto’s).') : t('Bewaard onder PDF → Gemaakt.');
  kcNaMaken([{ name: r.name, title: r.name, category: t('Foto’s'), source: 'foto', klaar: doc => { if (doc && fob.uit === r.name) $('#fob-klaar-naam').textContent = doc.title; } }]);
  fobRender();
};
function fobOpen(){ if (fob.uit) kcOpen(fob.uit); }
function fobDeel(){ if (fob.uit) kcShare(fob.uit); }
function fobOpslaan(){ if (fob.uit) kcSave(fob.uit); }
function fobSoortZet(v){ store.set('fotoSoort', ['doc', 'idkaart', 'paspoort', 'foto'].includes(v) ? v : 'doc'); }
function fobVoorstelZet(){ store.set('fotoVoorstel', !fobVoorstelAan()); fobRender(); }

/* ---------- Stamgegevens: een kaartfoto bewerken, en een voorstel bij een nieuwe kaartfoto ---------- */
function cmfKan(){ return typeof Android.masterPhotoBewerk === 'function'; }
function cmfSoort(r){ return (typeof idCopySoort === 'function' && idCopySoort(r.soort || (r.fields || {}).documentsoort)) || 'idkaart'; }
const cmfVoor = cmcPhotos;
cmcPhotos = function(box, r, blok){
  cmfVoor(box, r, blok);
  if (!cmfKan() || !(r.images?.front || r.images?.back)) return;
  const grid = document.querySelector('#cmc-foto-front')?.parentNode, sec = grid?.parentNode; if (!sec) return;
  const plek = cmEl('div', undefined, 'cmf-voorstellen'); plek.id = 'cmf-voorstellen'; grid.after(plek);
  let acts = sec.querySelector('.cmi-actions'); if (!acts) { acts = cmEl('div', undefined, 'row cmi-actions'); sec.append(acts); }
  const b = cmEl('button', t('Foto bewerken'), 'mini'); b.type = 'button'; b.id = 'cmc-bewerk'; b.dataset.icon = 'crop'; b.onclick = () => cmfKies(); acts.prepend(b);
};
function cmfKies(){
  const r = cmc.rec; if (!cmOpen() || cm.mutating || !r) return;
  const zijden = ['front', 'back'].filter(s => r.images?.[s]);
  if (zijden.length === 1) { cmfBewerk(zijden[0]); return; }
  openSheet(t('Welke foto?'), '', [[t('Voorkant'), () => cmfBewerk('front'), 'image'], [t('Achterkant'), () => cmfBewerk('back'), 'image']]);
}
async function cmfFoto(side){
  for (let i = 0; i < 50 && cmOpen() && !cmc.fotos[side]; i++) { if (i === 0) cmcPhotoLoad(side); await new Promise(r => setTimeout(r, 100)); }
  return cmc.fotos[side] || '';
}
async function cmfBewerk(side, ops){
  const r = cmc.rec; if (!cmOpen() || cm.mutating || !r || !cmfKan()) return;
  const src = await cmfFoto(side); if (!src || cmc.rec !== r) return;
  cmTouch();
  const res = await fotoBewerk({ src, bron: 'stam', titel: side === 'front' ? t('Voorkant bewerken') : t('Achterkant bewerken'), knop: t('Bewaren'),
    soort: cmfSoort(r), zijde: side, ops, afdekken: false, actief: cmTouch,
    uitleg: ops ? t('Dit is het voorstel. Pas aan wat niet klopt en tik op Bewaren.') : '' });
  if (!res || !res.gewijzigd || !cmOpen() || cmc.rec?.id !== r.id) return;
  cmfToepassen(r, side, res.ops);
}
function cmfToepassen(r, side, ops){
  if (!cmOpen() || cm.mutating) return;
  cmBusy(true); cmfWeg(side);
  cmRequest('masterPhotoBewerk', [r.id, r.revision, side, JSON.stringify(fbSchoon(ops))], () => { cmBusy(false); cmListLoad(() => cmKaartLoad(r.id, t('Kaartfoto bewerkt.'))); });
}
/* Na een nieuwe kaartfoto: welke kant(en) een voorstel mogen krijgen (alleen dit document, kort daarna) */
const cmfNa0 = cmiPhotoAfterUnlock;
cmiPhotoAfterUnlock = function(photo){
  if (photo && !photo.error && photo.saved && photo.id && cmfKan() && fobVoorstelAan())
    cmc.fbNa = { id: photo.id, zijden: photo.side === 'front' || photo.side === 'back' ? [photo.side] : ['front', 'back'], tot: Date.now() + 5 * 60000 };
  return cmfNa0(photo);
};
const cmfPut0 = cmcPhotoPut;
cmcPhotoPut = function(side){
  cmfPut0(side);
  const r = cmc.rec, na = cmc.fbNa;
  if (!r || !na || na.id !== r.id || Date.now() > na.tot || !na.zijden.includes(side)) return;
  na.zijden = na.zijden.filter(s => s !== side); cmfVoorstel(r, side);
};
async function cmfVoorstel(r, side){
  const src = cmc.fotos[side]; if (!src) return;
  const c = await fbCanvas(src); if (!c) return;
  let v = null; try { v = await fbVoorstel(c, cmfSoort(r), side); } catch (e) { v = null; }
  if (!fbVormVoorstel(v) || !cmOpen() || cmc.rec?.id !== r.id || cmc.rec.revision !== r.revision) return;
  const klein = await fbRender(c, v.ops, 240), plek = $('#cmf-voorstellen');
  if (!plek || !cmOpen() || cmc.rec?.id !== r.id || cmc.rec.revision !== r.revision) return;
  cmfWeg(side);
  const kaart = cmEl('div', undefined, 'card fob-voorstel'); kaart.id = 'cmf-' + side;
  const img = cmEl('img'); img.src = klein.toDataURL('image/jpeg', 0.8); img.alt = t('Voorbeeld van het voorstel');
  const tekst = cmEl('div'), p = cmEl('p', side === 'front' ? t('Voorstel voor de voorkant: {wat}.', { wat: fbRedenTekst(v.redenen) }) : t('Voorstel voor de achterkant: {wat}.', { wat: fbRedenTekst(v.redenen) }), 'note');
  const rij = cmEl('div', undefined, 'row');
  const ja = cmEl('button', t('Toepassen'), 'mini'); ja.type = 'button'; ja.onclick = () => cmfToepassen(r, side, v.ops);
  const pas = cmEl('button', t('Aanpassen'), 'mini'); pas.type = 'button'; pas.onclick = () => { cmfWeg(side); cmfBewerk(side, fbSchoon(v.ops)); };
  const nee = cmEl('button', t('Laten zo'), 'mini'); nee.type = 'button'; nee.onclick = () => cmfWeg(side);
  rij.append(ja, pas, nee); tekst.append(p, rij); kaart.append(img, tekst); plek.append(kaart);
}
/** Vanzelf alleen bij bijsnijden of draaien (alleen de belichting is bij een kaartfoto of ID-kopie geen reden om te storen). */
function fbVormVoorstel(v){ return !!v && v.redenen.some(x => x === 'bijsnijden' || x === 'draaien'); }
function cmfWeg(side){ const k = $('#cmf-' + side); if (k) k.remove(); }
/* Ook op het bewerkscherm (blok Foto's): per kant een knop zodra de foto getoond is */
async function cmiPhotoBewerk(side){
  if (!cmfKan() || !cmiPhotoCanChange()) return;
  const r = cm.edit, src = $('#cmi-' + side + '-image').getAttribute('src'); if (!src || !r.images?.[side]) return;
  cmTouch();
  const res = await fotoBewerk({ src, bron: 'stam', titel: side === 'front' ? t('Voorzijde bewerken') : t('Achterzijde bewerken'), knop: t('Bewaren'),
    soort: cmfSoort(r), zijde: side, afdekken: false, actief: cmTouch });
  if (!res || !res.gewijzigd || !cmOpen() || cm.edit !== r || cm.mutating) return;
  cmBusy(true);
  cmRequest('masterPhotoBewerk', [r.id, r.revision, side, JSON.stringify(res.ops)], out => { cmBusy(false); r.revision = out.revision; cmiEditor(); if (!cmi.show) cmiPhotoShow(); cmNote(t('Kaartfoto bewerkt.')); });
}
function cmfKnoppen(){ for (const side of ['front', 'back']) { const b = document.getElementById(['cmi', side, 'bewerk'].join('-')), z = document.getElementById(['cmi', side, 'zoom'].join('-')); const weg = z ? z.hidden || !cmfKan() : true; if (b && b.hidden !== weg) b.hidden = weg; } } // alleen bij verschil: anders roept de waarnemer zichzelf eindeloos aan
if ($('#cmi-photos')) new MutationObserver(cmfKnoppen).observe($('#cmi-photos'), { subtree: true, attributes: true, attributeFilter: ['hidden'] });
const cmfClear0 = cmClearCentral;
cmClearCentral = function(){ if (fbAan() && fb.opts.bron === 'stam') fbSluit(null); cmc.fbNa = null; return cmfClear0.apply(this, arguments); };
/** Een plaatje (data-adres) als canvas. */
function fbCanvas(src){
  return new Promise(ok => { const img = new Image(); img.onload = () => { const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight; c.getContext('2d').drawImage(img, 0, 0); ok(c); }; img.onerror = () => ok(null); img.src = src; });
}

/* ---------- ID-kopie: bijsnijden en rechtzetten per pagina; bij een nieuwe pagina meteen een voorstel ---------- */
function idfKan(){ return typeof Android.idCopyPreviewBewerkt === 'function'; }
const idfPreview0 = window.onIdCopyPreview;
window.onIdCopyPreview = function(r){
  if (r && r.bron) { const w = idc.bronWacht; if (w && r.volg === w.volg) { idc.bronWacht = null; w.klaar(r.error || r.session !== idc.session || r.revision !== idc.revision ? '' : r.image); } return; }
  if (r && r.volg != null && r.volg !== idc.volg) return; // een ouder antwoord
  const p0 = idc.pages[idc.page];
  if (r && r.error && p0 && p0.ops && r.page === idc.page && r.session === idc.session && r.revision === idc.revision) {
    // De bewerking lukt niet (meer): zonder bewerking verder, zodat de pagina niet vastloopt
    p0.ops = null; p0.auto = true; p0.checked = false; idc.loading = false; idc.melding = t('De bewerking lukte niet; de pagina staat weer zoals hij was.'); idCopyLoad(); return;
  }
  idfPreview0(r);
  const p = idc.pages[idc.page];
  if (!r || r.error || !p || !idfKan() || !fobVoorstelAan() || p.ops || p.auto !== undefined || r.page !== idc.page) return;
  p.auto = false; idfVoorstel(p, r.image);
};
async function idfVoorstel(p, src){
  const rev = idc.revision, page = idc.page, turn = p.turn, vlakken = JSON.stringify(p.rects), c = await fbCanvas(src); if (!c) return;
  let v = null; try { v = await fbVoorstel(c, idfSoort(), ''); } catch (e) { v = null; }
  if (!fbVormVoorstel(v) || current !== 'idcopy' || idc.revision !== rev || idc.page !== page || p.turn !== turn || p.ops || idc.busy || idc.loading || JSON.stringify(p.rects) !== vlakken) return;
  p.ops = fbSchoon(v.ops); p.opsTurn = p.turn; p.auto = true; p.checked = false;
  idc.melding = p.rects.length ? t('Voorstel toegepast: {wat}, met zwarte vlakken op de plekken die meestal afgeschermd worden. Controleer elke pagina; niet goed? Tik op Bijsnijden en rechtzetten.', { wat: fbRedenTekst(v.redenen) })
    : t('Voorstel toegepast: {wat}. Niet goed? Tik op Bijsnijden en rechtzetten.', { wat: fbRedenTekst(v.redenen) });
  idCopyLoad();
}
function idfSoort(){ return (typeof idCopySoort === 'function' && idCopySoort(idc.bron && idc.bron.soort)) || 'doc'; }
/** De pagina zonder bewerking (om in de bewerker te openen). */
function idfBron(p){
  if (!p.ops) return Promise.resolve($('#id-image').getAttribute('src') || '');
  return new Promise(klaar => { const volg = ++idc.volg; idc.bronWacht = { volg, klaar }; Android.idCopyPreviewBewerkt(idc.revision, idc.page, p.turn, '', volg, true); setTimeout(() => { if (idc.bronWacht && idc.bronWacht.volg === volg) { idc.bronWacht = null; klaar(''); } }, 20000); });
}
async function idCopyBewerk(){
  const p = idc.pages[idc.page]; if (idc.busy || idc.loading || !idc.meta || !p || !idfKan()) return;
  const rev = idc.revision, page = idc.page;
  idc.busy = true; idCopyControls(); uiWachtTekst($('#id-status'), t('Pagina laden…'));
  const src = await idfBron(p); idc.busy = false; idCopyControls(); $('#id-status').textContent = '';
  if (!src || idc.revision !== rev || idc.page !== page) return;
  const res = await fotoBewerk({ src, bron: 'id', titel: t('Pagina bewerken'), knop: t('Gebruiken'), soort: idfSoort(), ops: p.ops || undefined, afdekken: false,
    voorstel: !p.ops && fobVoorstelAan(), uitleg: t('Zwart maken doe je daarna met de afschermvlakken.') });
  if (!res || idc.revision !== rev || idc.page !== page || current !== 'idcopy') return;
  p.ops = res.gewijzigd ? res.ops : null; p.opsTurn = p.turn; p.auto = true; p.checked = false;
  idc.melding = p.rects.length ? t('Pagina aangepast. Controleer of de afschermvlakken nog goed liggen.') : t('Pagina aangepast.');
  idCopyLoad();
}
const idfControls0 = idCopyControls;
idCopyControls = function(){
  idfControls0();
  const b = $('#id-bewerk'); if (!b) return;
  b.hidden = !idfKan(); b.disabled = idc.busy || idc.loading || !idc.meta;
};
const idfReset0 = idCopyReset;
idCopyReset = function(){ if (fbAan() && fb.opts.bron === 'id') fbSluit(null); idc.bronWacht = null; return idfReset0.apply(this, arguments); };
const idfLeave0 = idCopyLeave;
idCopyLeave = function(){ if (fbAan() && fb.opts.bron === 'id') fbSluit(null); idc.bronWacht = null; return idfLeave0.apply(this, arguments); };
