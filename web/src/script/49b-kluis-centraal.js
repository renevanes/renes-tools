/* ---------- Documentenkluis als centrale opslag ----------
 * Wat de PDF-tools maken (plaatjes naar PDF, splitsen, tekst naar PDF, bewerken, formulieren, ID-kopie) gaat vanzelf
 * versleuteld de kluis in; het leesbare bestand onder PDF → Gemaakt gaat daarna weg (Java: kluisStore). Een PDF uit de
 * kluis bewerken wordt een nieuwe versie van dat document; oudere versies blijven (Versies). Is de kluis op slot, dan eerst
 * ontgrendelen; lukt dat niet, dan blijft het bestand onder Gemaakt staan met "Naar de documentenkluis".
 * Uit te zetten op PDF → Gemaakt ("Gemaakte PDF's vanzelf in de documentenkluis"). De Play-versie heeft geen kluis. */
const kc = { rij: [], lopend: null, bezig: false, wacht: false, naar: {}, status: '' };
function kcAan(){ return !isPlay() && store.get('pdfKluisVanzelf', true) !== false; }
function kcZet(on){ store.set('pdfKluisVanzelf', !!on); kcRender(); }
/** Bestanden uit Gemaakt naar de kluis. items: [{name, title, category, versionOf, note, source, link, klaar(doc, fout)}] */
function kcBewaar(items){
  if (isPlay() || !items || !items.length) return false;
  kc.rij.push(...items); kcVolgende(); return true;
}
/** Na het maken: vanzelf naar de kluis als dat aan staat. Geeft true als het onderweg is. */
function kcNaMaken(items){ return kcAan() && kcBewaar(items); }
function kcStatus(tekst, wacht){
  kc.status = tekst || '';
  document.querySelectorAll('.pdfkeep,.kcstatus').forEach(e => { if (wacht) uiWachtTekst(e, kc.status); else e.textContent = kc.status; });
}
function kcVolgende(){
  if (kc.bezig || !kc.rij.length) return;
  let d; try { d = JSON.parse(Android.kluisList()); } catch(e){ d = { locked: true }; }
  if (d.error) { kcMislukt(d.error); return; }
  if (d.locked) { if (kc.wacht) return; kc.wacht = true; kcStatus(t('Documentenkluis openen…'), true); Android.kluisUnlock(); return; }
  kc.bezig = true; kc.lopend = kc.rij.splice(0);
  kcStatus(t('Bewaren in de documentenkluis…'), true);
  try { Android.kluisStore(JSON.stringify({ items: kc.lopend.map(({ klaar, ...i }) => i) })); }
  catch(e){ onKluisStored({ error: 'Bewaren in de kluis lukt niet' }); }
}
function kcMislukt(reden){
  const items = kc.rij.splice(0);
  kcStatus(t('Niet in de documentenkluis gezet ({reden}). Het bestand staat onder PDF → Gemaakt.', { reden: t(reden) }));
  items.forEach(i => { if (i.klaar) i.klaar(null, reden); });
  kcVernieuw();
}
/* Antwoord op het ontgrendelen (34-uitbreidingen.js zet onKluis; hier erbij) */
(function(){ const prev = window.onKluis; window.onKluis = function(r){ if (prev) prev.apply(this, arguments); kcOnKluis(r); klDanOnKluis(r); }; })();
function kcOnKluis(r){
  if (!kc.wacht) return; kc.wacht = false;
  if (r === true) kcVolgende(); else kcMislukt(typeof r === 'string' ? r : 'Kluis niet ontgrendeld');
}
window.onKluisStored = function(r){
  const items = kc.lopend || []; kc.lopend = null; kc.bezig = false;
  if (!r || r.error) {
    const why = r && r.error || 'Bewaren lukt niet';
    items.forEach(i => { if (i.klaar) i.klaar(null, why); });
    kcStatus(t('Niet in de documentenkluis gezet ({reden}). Het bestand staat onder PDF → Gemaakt.', { reden: t(why) }));
  } else {
    const fout = {};
    (r.failed || []).forEach(f => { fout[f.name] = f.error; });
    (r.stored || []).forEach(s => { kc.naar[s.name] = { id: s.id, title: s.title, version: !!s.version }; });
    items.forEach(i => { if (i.klaar) i.klaar(kc.naar[i.name] || null, fout[i.name] || ''); });
    const n = (r.stored || []).length, m = (r.failed || []).length;
    if (n) {
      const versie = (r.stored || []).every(s => s.version);
      kcStatus(versie ? t('Opgeslagen als nieuwe versie in de documentenkluis') : tn(n, 'Bewaard in de documentenkluis', '{n} documenten bewaard in de documentenkluis'));
      toast(versie ? t('✓ Nieuwe versie in de documentenkluis') : t('✓ In de documentenkluis'));
    }
    if (m) kcStatus(t('{n} niet in de documentenkluis gezet ({reden}). Die staan onder PDF → Gemaakt.', { n: fmtNum(m), reden: t(r.failed[0].error || '') }));
  }
  kcVernieuw();
  kcVolgende();
};
function kcVernieuw(){ if (typeof pdfScr === 'function' && pdfScr(current)) pdfRender(); if (current === 'kluis') klRender(); kcRender(); }
/* Op Gemaakt: de schakelaar en wat er nog niet in de kluis staat */
function kcRender(){
  const sw = $('#kc-aan'); if (!sw) return;
  const play = isPlay();
  $('#kc-card').hidden = play;
  if (play) return;
  sw.classList.toggle('on', kcAan()); sw.setAttribute('aria-checked', kcAan() ? 'true' : 'false');
  const outs = rdJson(Android.pdfOutputs(), []);
  $('#kc-alles').hidden = !outs.length;
  $('#kc-alles').textContent = tn(outs.length, 'Naar de documentenkluis', 'Alle {n} naar de documentenkluis');
}
function kcSchakel(){ kcZet(!kcAan()); }
/** Alles wat nog onder Gemaakt staat naar de kluis. */
function kcAllesNaarKluis(){
  const outs = rdJson(Android.pdfOutputs(), []);
  kcBewaar(outs.map(o => ({ name: o.name, category: 'PDF', source: 'pdf' })));
}
function kcEenNaarKluis(name){ kcBewaar([{ name, category: 'PDF', source: 'pdf' }]); }

/* Een bestand dat (misschien) al in de kluis staat: bekijken, delen en opslaan gaan dan via de kluis. */
function kcIn(name){ return kc.naar[name] || null; }
function kcOpen(name){ const k = kcIn(name); if (k) klMetKluis(() => Android.kluisOpen(k.id, 0)); else Android.pdfOpen(name); }
function kcShare(name){ const k = kcIn(name); if (k) klMetKluis(() => Android.kluisShare(k.id, 0)); else Android.pdfShare(JSON.stringify([name])); }
function kcSave(name){ const k = kcIn(name); if (k) klMetKluis(() => Android.kluisSaveAs(k.id, 0)); else Android.pdfSave(name); }
/** Rij voor een net gemaakt document dat nu in de kluis staat */
function kcRij(name){
  const k = kcIn(name); if (!k) return '';
  return '<button class="trip" data-n="' + esc(name) + '" onclick="kcMenu(this.dataset.n)"><span><b translate="no">' + uiIcon('lock') + ' ' + esc(k.title || name) + '</b><small>' +
    esc(k.version ? t('Nieuwe versie in de documentenkluis') : t('In de documentenkluis')) + '</small></span><span aria-hidden="true">›</span></button>';
}
function kcMenu(name){
  const k = kcIn(name); if (!k) return;
  openSheet(k.title || name, t('In de documentenkluis'), [
    [t('Bekijken'), () => kcOpen(name), 'document'],
    [t('Delen (bijv. mailen of WhatsApp)'), () => kcShare(name), 'share'],
    [t('Opslaan als…'), () => kcSave(name)],
    [t('Documentenkluis openen'), () => show('kluis'), 'lock']]);
}

/* ---------- Kluis openen en dan iets doen (ontgrendelen als het moet) ---------- */
let klDan = null;
function klMetKluis(fn){
  let d; try { d = JSON.parse(Android.kluisList()); } catch(e){ d = { locked: true }; }
  if (d.error) { toast(t(d.error)); return; }
  if (!d.locked) { fn(d.docs || []); return; }
  klDan = fn; Android.kluisUnlock();
}
function klDanOnKluis(r){
  const fn = klDan; klDan = null; if (!fn) return;
  if (r !== true) { toast(typeof r === 'string' ? t(r) : t('Kluis niet ontgrendeld')); return; }
  let d; try { d = JSON.parse(Android.kluisList()); } catch(e){ d = {}; }
  fn(d.docs || []);
}

/* ---------- Menu van een kluisdocument ---------- */
function klIsPdf(d){ return /pdf/.test(d.mime || '') || /\.pdf$/i.test(d.name || ''); }
function klDocSheet(id){
  const x = (klRender.docs || []).find(d => d.id === id); if (!x) return;
  const pdf = klIsPdf(x), nv = (x.versions || []).length;
  const acts = [[t('Bekijken'), () => pdf ? klOpen(id) : klView(id), 'document']];
  // Weggaan van het kluisscherm doet de kluis op slot: eerst de opdracht (Java controleert de kluis meteen), dan pas het andere scherm
  if (pdf && !isPlay()) acts.push([t('Bewerken en ondertekenen'), () => { klBewerk(id); show('pdfedit'); }, 'sign']);
  acts.push([t('Delen (bijv. mailen of WhatsApp)'), () => Android.kluisShare(id, 0), 'share']);
  acts.push([t('Opslaan als…'), () => Android.kluisSaveAs(id, 0)]);
  if (!isPlay()) acts.push([t('Vergelijken met…'), () => { cmpVanKluis(0, id, 0); show('pdfcmp'); }]);
  if (nv) acts.push([tn(nv, 'Eerdere versie', '{n} eerdere versies'), () => klVersies(id), 'clock']);
  acts.push(['Categorie en toelichting', () => klOrganizeOpen(id)],
    [x.favorite ? 'Uit favorieten halen' : 'Als favoriet markeren', () => klFavorite(id)],
    ['Naam wijzigen', () => askInput('Naam', '', x.name, 'Opslaan', v => { const e = Android.kluisRename(id, v); if (e) toast(e); klRender(); })],
    ['Verwijderen', () => askConfirm('Uit de kluis verwijderen?', nv ? t('"{naam}" en de eerdere versies worden definitief gewist.', { naam: x.name }) : t('"{naam}" wordt definitief gewist.', { naam: x.name }), 'Verwijderen', () => { const e = Android.kluisDelete(id); toast(e || 'Verwijderd'); klRender(); })]);
  openSheet(x.name, klSub(x), acts);
}
/** Korte regel onder de naam: grootte, versie, koppeling (ID-kopie voor wie) */
function klSub(x){
  const delen = [fmtB(x.size || 0)];
  if ((x.versions || []).length) delen.push(t('versie {n}', { n: fmtNum(x.versions.length + 1) }));
  if (x.link && x.link.ontvanger) delen.push(t('ID-kopie voor {ontvanger}', { ontvanger: x.link.ontvanger }));
  return delen.join(' · ');
}
function klOpen(id){
  const klaar = uiBezig(t('Document ontsleutelen…'), ['onKluisChanged', 'onPauseApp']);
  setTimeout(klaar, 60000);
  Android.kluisOpen(id, 0);
}
function klBewerk(id){ pdfSetBusy('Document uit de kluis openen…'); Android.kluisEdit(id); }
window.onKluisEdit = function(r){
  pdfSetBusy('');
  if (!r || r.error) { toast(t(r && r.error || 'Openen lukt niet')); return; }
  if (current !== 'pdfedit') show('pdfedit');
  pdfEd.want = true; pdfJobScr = 'pdfedit';
  onPdf(r);
};
/* Eerdere versies: bekijken, vergelijken met de huidige, terugzetten, delen */
function klVersies(id){
  const x = (klRender.docs || []).find(d => d.id === id); if (!x) return;
  const vs = (x.versions || []).map((v, i) => ({ ...v, n: i + 1 })).reverse();
  openSheet(t('Versies van {naam}', { naam: x.name }), t('De nieuwste staat bovenaan; de huidige versie is wat je bij Bekijken ziet.'),
    vs.map(v => [t('Versie {n} · {wanneer}', { n: fmtNum(v.n), wanneer: fmtDT(v.t) }) + (v.note ? ' · ' + v.note : ''), () => klVersie(id, v.n)]));
}
function klVersie(id, n){
  const x = (klRender.docs || []).find(d => d.id === id); if (!x) return;
  const pdf = klIsPdf(x);
  openSheet(t('Versie {n}', { n: fmtNum(n) }), x.name, [
    [t('Bekijken'), () => Android.kluisOpen(id, n), 'document'],
    ...(!isPlay() && pdf ? [[t('Vergelijken met de huidige versie'), () => { cmpVersies(id, n); show('pdfcmp'); }]] : []),
    [t('Delen (bijv. mailen of WhatsApp)'), () => Android.kluisShare(id, n), 'share'],
    [t('Deze versie terugzetten'), () => askConfirm(t('Versie {n} terugzetten?', { n: fmtNum(n) }), t('Deze versie wordt weer de huidige. Wat er nu staat, blijft bewaard als eerdere versie.'), t('Terugzetten'), () => Android.kluisRestore(id, n))]]);
}
window.onKluisVersie = function(r){
  toast(r && r.ok ? t('✓ Versie teruggezet') : t(r && r.error || 'Terugzetten lukt niet'));
  if (current === 'kluis') klRender();
};
