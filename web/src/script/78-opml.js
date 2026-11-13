/* ---------- OPML: abonnementen (Podcasts) en bronnen (Nieuws) overzetten ----------
 * Exporteren bewaart een OPML-bestand op een plek die je kiest; importeren leest zo'n bestand uit een andere app.
 * Java doet het werk (OpmlWerk); de interface krijgt alleen aantallen terug (onOpml). */
function opmlExport(soort){ Android.opmlExport(soort); }
function opmlImport(soort){
  const doe = () => Android.opmlImport(soort);
  // Nieuws leest elke nieuwe feed eerst, net als bij Bron toevoegen: dat zeggen we vooraf
  if (soort === 'nieuws') askConfirm(t('Bronnen importeren'), t('Tools leest elke feed uit het bestand één keer om te zien of hij werkt. De sites van die feeds zien daarbij je IP-adres, net als bij Bron toevoegen.'), t('Bestand kiezen'), doe);
  else doe();
}
function opmlTekst(r){
  if (r.error) return r.error;
  if (r.actie === 'export') return tn(r.nieuw, 'Eén feed bewaard als OPML', '{n} feeds bewaard als OPML');
  const delen = [tn(r.nieuw, 'Eén feed toegevoegd', '{n} feeds toegevoegd')];
  if (r.al) delen.push(tn(r.al, 'één had je al', '{n} had je al'));
  if (r.mislukt) delen.push(tn(r.mislukt, 'één lukte niet', '{n} lukten niet'));
  return delen.join(', ');
}
window.onOpml = function(r){
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { r = {}; } }
  if (!r || r.geannuleerd) return;
  toast(opmlTekst(r));
  if (r.actie !== 'import' || r.error) return;
  if (r.soort === 'podcasts' && typeof pcRenderListen === 'function') { pcRenderListen(); if (r.nieuw && typeof pcRefresh === 'function') pcRefresh(); }
  if (r.soort === 'nieuws' && typeof nwLaad === 'function') { if (nwLaad() !== false && typeof nwRender === 'function') nwRender(); }
};
