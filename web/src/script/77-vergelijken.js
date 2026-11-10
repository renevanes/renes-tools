/* ---------- Documenten vergelijken (scherm pdfcmp) ----------
 * Twee documenten A en B (bestand, documentenkluis of Gemaakt) en dan de tekst (per woord) of de pagina's (in beeld).
 * Java (PdfCompare) houdt de bestanden in de cache en wist ze bij het verlaten van dit scherm (cmpLeave in 02-kern.js). */
const cmp = { a: null, b: null, tab: 'tekst', tekst: null, paginas: null, pagina: -1, modus: 'verschil', bezig: false };
function enterPdfCmp(){ cmpInfo(); }
function cmpLeave(){ Android.cmpClear(); cmp.a = cmp.b = null; cmpReset(); }
function cmpReset(){
  cmp.tekst = null; cmp.paginas = null; cmp.pagina = -1; cmp.bezig = false;
  $('#cmp-tekst').replaceChildren(); $('#cmp-tekst-stand').textContent = ''; $('#cmp-tekst-legenda').hidden = true; $('#cmp-alles-wrap').hidden = true;
  $('#cmp-paginas').replaceChildren(); $('#cmp-beeld-stand').textContent = ''; $('#cmp-pagina-kaart').hidden = true; $('#cmp-img').hidden = true; $('#cmp-img').removeAttribute('src');
  cmpTeken();
}
function cmpInfo(){ const r = rdJson(Android.cmpInfo(), {}); cmp.a = r.a || null; cmp.b = r.b || null; cmpTeken(); }
function cmpTeken(){
  [['a', 0], ['b', 1]].forEach(([k, i]) => {
    const d = cmp[k];
    $('#cmp-' + k + '-naam').textContent = d ? d.name : (i ? t('Document B kiezen') : t('Document A kiezen'));
    $('#cmp-' + k + '-naam').setAttribute('translate', d ? 'no' : 'yes');
    $('#cmp-' + k + '-info').textContent = d ? [d.pdf ? tn(d.pages, '{n} pagina', '{n} pagina\'s') : t('Foto'), d.kluis ? t('uit de documentenkluis') : ''].filter(Boolean).join(' · ') : (i ? t('Bijvoorbeeld de nieuwe versie') : t('Bijvoorbeeld de oude versie'));
  });
  const beide = !!(cmp.a && cmp.b);
  $('#cmp-werk').hidden = !beide;
  $('#cmp-wissel').disabled = !beide || cmp.bezig;
  $('#cmp-tekst-start').hidden = !!cmp.tekst; $('#cmp-tekst-start').disabled = cmp.bezig;
  $('#cmp-beeld-start').hidden = !!cmp.paginas; $('#cmp-beeld-start').disabled = cmp.bezig;
  document.querySelectorAll('.cmp-slot').forEach(b => b.disabled = cmp.bezig);
}
/* Een document kiezen voor A (0) of B (1) */
function cmpKies(slot){
  if (cmp.bezig) return;
  const outs = rdJson(Android.pdfOutputs(), []);
  openSheet(slot ? t('Document B') : t('Document A'), '', [
    [t('Bestand op de telefoon'), () => cmpBestand(slot), 'folder'],
    ...(!isPlay() ? [[t('Uit de documentenkluis'), () => cmpUitKluis(slot), 'lock']] : []),
    ...(outs.length ? [[t('Een gemaakte PDF'), () => cmpUitGemaakt(slot, outs), 'document']] : [])]);
}
function cmpBestand(slot){ cmpWacht(t('Document kiezen…')); Android.cmpPick(slot); }
function cmpUitKluis(slot){
  klMetKluis(docs => {
    const bruikbaar = docs.filter(d => /pdf|^image\//.test(d.mime || '') || /\.(pdf|jpe?g|png)$/i.test(d.name || ''));
    if (!bruikbaar.length) { toast(t('Geen PDF of foto in de documentenkluis')); return; }
    bruikbaar.sort((x, y) => (y.t || 0) - (x.t || 0));
    openSheet(t('Uit de documentenkluis'), '', bruikbaar.slice(0, 40).map(d => [d.name, () => cmpVanKluis(slot, d.id, 0)]));
  });
}
function cmpUitGemaakt(slot, outs){ openSheet(t('Een gemaakte PDF'), '', outs.slice(0, 40).map(o => [o.name, () => cmpVanGemaakt(slot, o.name)])); }
function cmpVanKluis(slot, id, version){ cmpWacht(t('Document uit de kluis openen…')); klMetKluis(() => Android.cmpKluis(slot, id, version)); }
function cmpVanGemaakt(slot, name){ cmpWacht(t('Document openen…')); Android.cmpMade(slot, name); }
/** Een oudere versie (A) met de huidige (B) van hetzelfde kluisdocument */
function cmpVersies(id, n){ cmpWacht(t('Document uit de kluis openen…')); klMetKluis(() => { Android.cmpKluis(0, id, n); Android.cmpKluis(1, id, 0); }); }
function cmpWacht(tekst){ document.querySelectorAll('#s-pdfcmp .pdfbusy').forEach(e => uiWachtTekst(e, tekst)); }
window.onCmp = function(r){
  document.querySelectorAll('#s-pdfcmp .pdfbusy').forEach(e => { e.textContent = ''; });
  if (!r || r.error) { toast(t(r && r.error || 'Openen lukt niet')); return; }
  cmp.a = r.a || null; cmp.b = r.b || null; cmpReset();
  if (cmp.a && cmp.b && current === 'pdfcmp') cmpTab(cmp.tab);
};
function cmpWissel(){ if (cmp.bezig) return; Android.cmpSwap(); cmpInfo(); cmpReset(); cmpTab(cmp.tab); }
function cmpTab(tab){
  cmp.tab = tab === 'beeld' ? 'beeld' : 'tekst';
  document.querySelectorAll('#s-pdfcmp .pctabs [role=tab]').forEach(b => { const on = b.dataset.tab === cmp.tab; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
  $('#cmp-p-tekst').hidden = cmp.tab !== 'tekst'; $('#cmp-p-beeld').hidden = cmp.tab !== 'beeld';
}

/* ---------- Tekst ---------- */
function cmpTekst(){
  if (cmp.bezig || !cmp.a || !cmp.b) return;
  cmp.bezig = true; cmpTeken();
  uiWachtTekst($('#cmp-tekst-stand'), t('Tekst vergelijken…'));
  Android.cmpText();
}
window.onCmpText = function(r){
  cmp.bezig = false;
  if (!r || r.error) { $('#cmp-tekst-stand').textContent = t(r && r.error || 'Vergelijken lukt niet'); cmpTeken(); return; }
  cmp.tekst = r; cmpTeken(); cmpTekstTeken();
};
const CMP_KORT = 3; // gelijke tekst: zoveel regels voor en na een verschil blijven zichtbaar
function cmpTekstTeken(){
  const r = cmp.tekst, box = $('#cmp-tekst'); if (!r) return;
  const ops = r.ops || [];
  const stand = !r.weg && !r.erbij ? t('Geen verschillen in de tekst gevonden.') : t('Alleen in A: {weg}. Alleen in B: {erbij}.', { weg: tn(r.weg, 'één woord', '{n} woorden'), erbij: tn(r.erbij, 'één woord', '{n} woorden') });
  $('#cmp-tekst-stand').textContent = stand + (r.ocr ? ' ' + t('Een deel is gelezen met tekstherkenning: controleer belangrijke verschillen op de pagina.') : '') + (r.limited ? ' ' + t('Alleen de eerste 60 pagina\'s zijn vergeleken.') : '');
  $('#cmp-tekst-legenda').hidden = !(r.weg || r.erbij);
  const alles = $('#cmp-alles').checked;
  box.replaceChildren();
  let lang = false;
  ops.forEach(([type, tekst], k) => {
    if (type !== 0) { const el = document.createElement(type < 0 ? 'del' : 'ins'); el.className = type < 0 ? 'cmp-weg' : 'cmp-erbij'; el.textContent = tekst; box.append(el); return; }
    const regels = tekst.split('\n'); if (regels[regels.length - 1] === '') regels.pop();
    const eerste = k === 0, laatste = k === ops.length - 1, nodig = (eerste ? 0 : CMP_KORT) + (laatste ? 0 : CMP_KORT);
    if (alles || regels.length <= nodig + 1) { box.append(document.createTextNode(tekst)); return; }
    lang = true;
    const kop = eerste ? [] : regels.slice(0, CMP_KORT), staart = laatste ? [] : regels.slice(regels.length - CMP_KORT);
    const eindNl = /\n$/.test(tekst);
    if (kop.length) box.append(document.createTextNode(kop.join('\n') + '\n'));
    const weg = document.createElement('span'); weg.className = 'cmp-gelijk'; weg.setAttribute('translate', 'yes');
    weg.textContent = tn(regels.length - kop.length - staart.length, '… één regel gelijk …', '… {n} regels gelijk …') + '\n'; box.append(weg);
    if (staart.length) box.append(document.createTextNode(staart.join('\n') + (eindNl ? '\n' : '')));
  });
  $('#cmp-alles-wrap').hidden = !lang && !alles;
}

/* ---------- Pagina's in beeld ---------- */
function cmpBeeld(){
  if (cmp.bezig || !cmp.a || !cmp.b) return;
  cmp.bezig = true; cmpTeken();
  uiWachtTekst($('#cmp-beeld-stand'), t('Pagina\'s vergelijken…'));
  Android.cmpScan();
}
window.onCmpScan = function(r){
  cmp.bezig = false;
  if (!r || r.error) { $('#cmp-beeld-stand').textContent = t(r && r.error || 'Vergelijken lukt niet'); cmpTeken(); return; }
  cmp.paginas = r; cmpTeken();
  const anders = r.pages.filter(p => p.score > 0).length, extra = Math.abs(r.a - r.b);
  $('#cmp-beeld-stand').textContent = [anders ? tn(anders, 'Eén pagina heeft verschillen.', '{n} pagina\'s hebben verschillen.') : t('Geen verschillen gezien op de pagina\'s die in beide staan.'),
    extra ? (r.a > r.b ? tn(extra, 'A heeft één pagina meer.', 'A heeft {n} pagina\'s meer.') : tn(extra, 'B heeft één pagina meer.', 'B heeft {n} pagina\'s meer.')) : ''].filter(Boolean).join(' ');
  const box = $('#cmp-paginas'); box.replaceChildren();
  const max = Math.max(r.a, r.b);
  for (let i = 0; i < max; i++) {
    const p = r.pages.find(x => x.i === i), b = document.createElement('button');
    b.className = 'cmp-pag' + (p && p.score > 0 ? ' anders' : '') + (!p ? ' extra' : '');
    b.type = 'button'; b.onclick = () => cmpPagina(i);
    const sterk = document.createElement('b'); sterk.textContent = t('Pagina {n}', { n: fmtNum(i + 1) });
    const klein = document.createElement('small');
    klein.textContent = !p ? (i >= r.a ? t('alleen in B') : t('alleen in A')) : p.score > 0 ? t('{p}% anders', { p: fmtNum(Math.max(0.1, p.score / 10), { maximumFractionDigits: 1 }) }) : t('gelijk');
    b.append(sterk, klein); box.append(b);
  }
  const eerste = r.pages.find(p => p.score > 0);
  if (eerste) cmpPagina(eerste.i);
};
function cmpPagina(i){
  const r = cmp.paginas; if (!r) return;
  cmp.pagina = Math.max(0, Math.min(Math.max(r.a, r.b) - 1, i));
  const beide = cmp.pagina < Math.min(r.a, r.b);
  if (!beide) cmp.modus = cmp.pagina >= r.a ? 'b' : 'a';
  else if (!cmp.modus) cmp.modus = 'verschil';
  $('#cmp-pagina-kaart').hidden = false;
  $('#cmp-pagina-kop').textContent = t('Pagina {n}', { n: fmtNum(cmp.pagina + 1) });
  document.querySelectorAll('#cmp-modi button').forEach(b => {
    const v = b.dataset.v, kan = beide || v === cmp.modus;
    b.disabled = !kan; b.classList.toggle('on', v === cmp.modus); b.setAttribute('aria-checked', v === cmp.modus ? 'true' : 'false');
  });
  document.querySelectorAll('#cmp-paginas .cmp-pag').forEach((b, k) => b.setAttribute('aria-current', k === cmp.pagina ? 'true' : 'false'));
  $('#cmp-vorige').disabled = cmp.pagina <= 0; $('#cmp-volgende').disabled = cmp.pagina >= Math.max(r.a, r.b) - 1;
  $('#cmp-beeld-legenda').hidden = cmp.modus !== 'verschil';
  uiWachtTekst($('#cmp-pagina-stand'), t('Pagina laden…'));
  Android.cmpPage(cmp.pagina, cmp.modus);
}
function cmpModus(m){ if (!cmp.paginas) return; cmp.modus = m; cmpPagina(cmp.pagina); }
function cmpStap(d){ cmpPagina(cmp.pagina + d); }
window.onCmpPage = function(r){
  if (!r || r.i !== cmp.pagina || r.mode !== cmp.modus) return;
  if (r.error) { $('#cmp-pagina-stand').textContent = t(r.error); $('#cmp-img').hidden = true; return; }
  const img = $('#cmp-img');
  img.src = r.image; img.width = r.w; img.height = r.h; img.hidden = false;
  img.alt = r.mode === 'verschil' ? t('Pagina {n}: verschillen tussen A en B', { n: fmtNum(r.i + 1) }) : r.mode === 'a' ? t('Pagina {n} van A', { n: fmtNum(r.i + 1) }) : t('Pagina {n} van B', { n: fmtNum(r.i + 1) });
  $('#cmp-pagina-stand').textContent = r.mode === 'verschil' ? (r.score > 0 ? t('Rood staat alleen in A, groen alleen in B.') : t('Geen verschillen gezien op deze pagina.')) : '';
};
