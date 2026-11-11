/* ---------- Bestand kiezen: uit de app zelf of van de telefoon ----------
 * Overal waar een tool om een bestand vraagt (PDF openen, plaatjes, een PDF bewerken of splitsen, een e-mail naar PDF, een
 * ID-kopie, een boek, een factuur, de documentenkluis) kies je eerst waar het vandaan komt: de documentenkluis, Gemaakt
 * (PDF-tools), het facturendossier of "Bestand op de telefoon" (de bestandskiezer van Android). Is er geen andere bron, dan
 * gaat het meteen naar de bestandskiezer.
 * Een bestand uit de app gaat via Java (Bron.java): Android.bronVolgende zet het klaar, daarna roept bronKies de gewone
 * kiesfunctie van de tool aan en krijgt de tool het klaargezette bestand in plaats van de kiezer van Android. Zo verwerkt elke
 * tool het precies zoals een gekozen bestand. Een tool kan voor een bron ook iets eigens doen (eigen.kluis, eigen.gemaakt),
 * zoals een kluisdocument openen als nieuwe versie (PDF bewerken) of in de viewer van de kluis (PDF openen).
 * Niet hier: Stamgegevens (kaartfoto's), Bankzaken, de handtekening en back-ups terugzetten; die houden hun eigen kiezer.
 *
 * bronKies({ titel, soort: 'pdf'|'beeld'|'pdfbeeld'|'boek'|'tekst'|'alles', kiezer: (MainActivity.bronReq), bestand: () => …,
 *            zonder: ['kluis'|'gemaakt'|'factuur'],
 *            eigen: { kluis: doc => …, gemaakt: naam => … }, kluisOpen: true (de kluis niet meteen weer op slot),
 *            weg: () => … (het menu dicht zonder keuze), voor: [[tekst, fn, pictogram]] (een eigen plek bovenaan) }) */
const BRON_SOORT = {
  pdf: { ext: /\.pdf$/i, mime: /pdf/ },
  beeld: { ext: /\.(jpe?g|png|webp|gif|heic|heif|bmp)$/i, mime: /^image\// },
  pdfbeeld: { ext: /\.(pdf|jpe?g|png|webp|heic|heif)$/i, mime: /pdf|^image\// },
  boek: { ext: /\.(epub|txt)$/i, mime: /epub|^text\/plain/ },
  tekst: { ext: /\.(eml|txt|html?)$/i, mime: /rfc822|^text\/(plain|html)/ },
  alles: { ext: /./, mime: /./ }
};
const BRON_MAX = 40; // zoveel per lijst (nieuwste eerst)
function bronPast(o, naam, mime){ const s = BRON_SOORT[o.soort] || BRON_SOORT.alles; return s.mime.test(mime || '') || s.ext.test(naam || ''); }
function bronMag(o, bron){
  if ((o.zonder || []).includes(bron)) return false;
  const tool = bron === 'kluis' ? 'kluis' : bron === 'factuur' ? 'facturen' : 'pdf';
  if (typeof SOLO !== 'undefined' && SOLO && typeof soloOk === 'function' && !soloOk(tool)) return false;
  return !isPlay() || bron === 'gemaakt';
}
/* Gemaakte PDF's (Gemaakt) die passen */
function bronGemaakt(o){
  if (!bronMag(o, 'gemaakt') || typeof Android.pdfOutputs !== 'function') return [];
  return rdJson(Android.pdfOutputs(), []).filter(x => x && x.name && bronPast(o, x.name, ''));
}
/* Facturen waarvan de app zelf een kopie heeft */
function bronFacturen(o){
  if (!bronMag(o, 'factuur') || typeof Android.facturenDossier !== 'function' || !bronPast(o, 'x.pdf', 'application/pdf')) return [];
  return (rdJson(Android.facturenDossier(), {}).items || []).filter(s => s && s.sha && s.plekken && s.plekken.app && !s.plekken.app.weg)
    .sort((a, b) => (b.opgehaald || 0) - (a.opgehaald || 0));
}
function bronKies(o){
  const keuzes = [...(o.voor || []), [t('Bestand op de telefoon'), () => bronTelefoon(o), 'smartphone']];
  if (bronMag(o, 'kluis') && typeof Android.kluisList === 'function') keuzes.push([t('Uit de documentenkluis'), () => bronUitKluis(o), 'lock']);
  const gemaakt = bronGemaakt(o), facturen = bronFacturen(o);
  if (gemaakt.length) keuzes.push([t('Uit Gemaakt (PDF-tools)'), () => bronLijst(o, t('Uit Gemaakt (PDF-tools)'), gemaakt.slice(0, BRON_MAX).map(x => [x.name, () => bronVanGemaakt(o, x.name)])), 'document']);
  if (facturen.length) keuzes.push([t('Uit het facturendossier'), () => bronLijst(o, t('Uit het facturendossier'), facturen.slice(0, BRON_MAX).map(s => [
    s.leverancierNaam ? t('{leverancier} · {bestand}', { leverancier: s.leverancierNaam, bestand: s.naam || '' }) : (s.naam || ''), () => bronNeem(o, [{ bron: 'factuur', id: s.sha, versie: 0 }])])), 'wallet']);
  if (keuzes.length === 1 && !(o.voor || []).length) { bronTelefoon(o); return; }
  openSheet(o.titel || t('Bestand kiezen'), t('Waar staat het bestand?'), keuzes, o.weg);
}
function bronLijst(o, titel, acts){ openSheet(titel, '', acts, o.weg); }
function bronUitKluis(o){
  klMetKluis(docs => {
    const lijst = docs.filter(d => bronPast(o, d.name, d.mime)).sort((a, b) => (b.t || 0) - (a.t || 0));
    if (!lijst.length) { toast(t('Niets passends in de documentenkluis')); klDichtBuiten(); if (o.weg) o.weg(); return; }
    openSheet(t('Uit de documentenkluis'), '', lijst.slice(0, BRON_MAX).map(d => [d.name, () => bronVanKluis(o, d)]), () => { klDichtBuiten(); if (o.weg) o.weg(); });
  });
}
function bronVanKluis(o, d){
  klMetKluis(() => {
    if (o.eigen && o.eigen.kluis) o.eigen.kluis(d); else bronNeem(o, [{ bron: 'kluis', id: d.id, versie: 0 }]);
    if (!o.kluisOpen) setTimeout(klDichtBuiten, 0); // Java schreef het document al in (ontsleutelen pas bij het openen); weg van het kluisscherm = op slot
  });
}
function bronVanGemaakt(o, naam){ if (o.eigen && o.eigen.gemaakt) o.eigen.gemaakt(naam); else bronNeem(o, [{ bron: 'gemaakt', id: naam, versie: 0 }]); }
/* Klaarzetten in Java en dan de gewone kiesfunctie van de tool: die krijgt dit bestand in plaats van de kiezer */
function bronNeem(o, items){
  let fout = '';
  try { fout = Android.bronVolgende(JSON.stringify(items), o.kiezer || '') || ''; } catch (e) { fout = 'Kiezen lukt niet'; }
  if (fout) { toast(t(fout)); if (o.weg) o.weg(); return; }
  o.bestand();
}
/* De kiezer van Android: niets klaargezet laten staan */
function bronTelefoon(o){ try { Android.bronVolgende('', ''); } catch (e) {} o.bestand(); }
/* Lukte het klaarzetten in Java niet (bijv. de factuur is intussen weg): zeggen; de tool hoort een geannuleerde keuze */
window.onBron = function(r){ if (r && r.error) toast(t(r.error)); };
