/* ---------- PDF: plaatjes naar PDF, splitsen, tekst/e-mail naar PDF ---------- */
let pdfImgs = [], pdfSel = new Set(), pdfIn = {}, pdfBusy = false, pdfInGen = 0;
function enterPdf(){
  const p = typeof Android.pdfPending === 'function' ? Android.pdfPending() : '';
  if (p) { try { onPdf(JSON.parse(p)); } catch(e){} }
  pdfImgs = rdJson(Android.pdfImages(), []);
  pdfIn = rdJson(Android.pdfInfo(), {});
  pdfRender();
  pdfRenderDef();
}
/* Standaard PDF-app: Android laat dit de gebruiker zelf kiezen ("Altijd"), wij helpen erbij */
function pdfRenderDef(){
  const d = rdJson(typeof Android.pdfDefaultState === 'function' ? Android.pdfDefaultState() : '', {});
  const box = $('#pdf-def');
  if (d.state === 'ours') { box.innerHTML = '<p class="note">✓ PDF\'s openen standaard in Rene\'s Tools, ook uit Gmail, WhatsApp en Bestanden.</p>'; return; }
  box.innerHTML = '<p class="note">' + (d.state === 'other' && d.app ? 'PDF\'s openen nu in <b>' + esc(d.app) + '</b>. ' : '') +
    'Wil je dat PDF\'s altijd hier openen? Tik op de knop, kies <b>Rene\'s Tools</b> en dan <b>Altijd</b>.</p>' +
    '<button class="big ghost" id="pdf-def-btn" onclick="Android.pdfMakeDefault()">Als standaard PDF-app instellen</button>';
}
function pdfSetBusy(t){ pdfBusy = !!t; $('#pdf-busy').textContent = t || ''; document.querySelectorAll('#s-pdf main button').forEach(b => b.disabled = pdfBusy); }
window.onPdf = function(r){
  if (!r) return;
  // Een gekozen bestand (plaatjes of PDF) beëindigt geen lopende klus; al het andere wel
  if (r.error || (r.kind !== 'images' && r.kind !== 'pdf')) pdfSetBusy('');
  if (r.error) { toast(r.error); if (current === 'pdf') pdfRender(); return; }
  if (r.kind === 'images') { pdfImgs = rdJson(Android.pdfImages(), []); toast(r.count + (r.count === 1 ? ' plaatje' : ' plaatjes') + ' toegevoegd' + (r.warn ? ' · ' + r.warn : '')); }
  if (r.kind === 'pdf') { pdfIn = r; pdfInGen++; pdfSel = new Set(); $('#pdf-range').value = ''; }
  if (r.kind === 'done') {
    pdfImgs = rdJson(Android.pdfImages(), []);
    const n = (r.made || []).length;
    toast(n === 1 ? '✓ PDF gemaakt' : '✓ ' + n + ' PDF\'s gemaakt');
    if (r.lossless === false) setTimeout(() => toast('Deze PDF kon niet direct gesplitst worden; de pagina\'s zijn als afbeelding overgenomen'), 2800);
  }
  if (r.kind === 'saved') toast('✓ Opgeslagen');
  if (r.kind === 'savedAll') toast('✓ ' + r.count + ' opgeslagen in de map PDF');
  // Niet wegspringen als je intussen ergens anders bent: het resultaat staat klaar op het PDF-scherm
  if (current === 'pdf') pdfRender();
};
function pdfRender(){
  // gemaakt
  const outs = rdJson(Android.pdfOutputs(), []);
  $('#pdf-out-card').style.display = outs.length ? 'block' : 'none';
  $('#pdf-share-all').style.display = outs.length > 1 ? '' : 'none';
  $('#pdf-out').innerHTML = outs.map(o => '<button class="trip" data-n="' + esc(o.name) + '" onclick="pdfOutMenu(this.dataset.n)"><span><b>📄 ' + esc(o.name) + '</b><small>' + esc(fmtB(o.size)) +
    '</small></span><span aria-hidden="true">›</span></button>').join('');
  // plaatjes
  $('#pdf-img-opts').style.display = pdfImgs.length ? 'block' : 'none';
  $('#pdf-imgs').innerHTML = pdfImgs.map((m, k) => '<div class="pdfimg"><img src="' + esc(m.thumb) + '" alt="Plaatje ' + (k + 1) + '"><span class="pnum">' + (k + 1) + '</span>' +
    '<div class="pbtns">' + (k > 0 ? '<button onclick="pdfImgMove(' + k + ',-1)" aria-label="Plaatje ' + (k + 1) + ' naar voren">◀</button>' : '') +
    '<button onclick="pdfImgDel(' + k + ')" aria-label="Plaatje ' + (k + 1) + ' weghalen">✕</button>' +
    (k < pdfImgs.length - 1 ? '<button onclick="pdfImgMove(' + k + ',1)" aria-label="Plaatje ' + (k + 1) + ' naar achteren">▶</button>' : '') + '</div></div>').join('');
  $('#pdf-img-make').textContent = pdfImgs.length ? 'PDF maken (' + pdfImgs.length + (pdfImgs.length === 1 ? ' pagina)' : ' pagina\'s)') : 'PDF maken';
  // splitsen
  const has = pdfIn && pdfIn.pages > 0;
  $('#pdf-split').style.display = has ? 'block' : 'none';
  if (has) {
    $('#pdf-split-info').textContent = pdfIn.name + ' · ' + pdfIn.pages + (pdfIn.pages === 1 ? ' pagina' : ' pagina\'s') + (pdfIn.lossless === false ? ' · wordt als afbeelding overgenomen' : '');
    const box = $('#pdf-pages');
    if (box.dataset.for !== String(pdfInGen)) {
      box.dataset.for = String(pdfInGen);
      box.innerHTML = Array.from({ length: Math.min(pdfIn.pages, 300) }, (_, i) => '<button class="ppage" data-i="' + i + '" onclick="pdfTogglePage(' + i + ')" aria-pressed="false" aria-label="Pagina ' + (i + 1) + '"><img alt=""><span>' + (i + 1) + '</span></button>').join('');
      pdfLoadThumbs(0);
    }
    box.querySelectorAll('.ppage').forEach(b => { const on = pdfSel.has(+b.dataset.i); b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); });
  }
  if (pdfBusy) document.querySelectorAll('#s-pdf main button').forEach(b => b.disabled = true);
}
/* Voorbeelden in stukjes laden, zodat het scherm niet vastloopt bij grote PDF's */
function pdfLoadThumbs(from){
  const imgs = document.querySelectorAll('#pdf-pages .ppage img');
  const until = Math.min(imgs.length, from + 6);
  const gen = pdfInGen;
  for (let i = from; i < until; i++) {
    const u = Android.pdfThumb(i);
    if (u === 'wait') { // de PDF is even bezig (splitsen/openen): straks verder
      if (current === 'pdf') setTimeout(() => { if (gen === pdfInGen) pdfLoadThumbs(i); }, 800);
      return;
    }
    if (/^data:image\/jpeg;base64,/.test(u)) imgs[i].src = u;
  }
  if (until < imgs.length && current === 'pdf') setTimeout(() => { if (gen === pdfInGen) pdfLoadThumbs(until); }, 30);
}
function pdfImgMove(k, d){ Android.pdfImageMove(k, k + d); pdfImgs = rdJson(Android.pdfImages(), []); pdfRender(); }
function pdfImgDel(k){ Android.pdfImageRemove(k); pdfImgs = rdJson(Android.pdfImages(), []); pdfRender(); }
function pdfClearImages(){ Android.pdfImagesClear(); pdfImgs = []; pdfRender(); }
function pdfMakeImages(){
  if (!pdfImgs.length || pdfBusy) return;
  const a4 = $('#pdf-size .on') ? $('#pdf-size .on').dataset.v === 'a4' : true;
  pdfSetBusy('PDF maken…');
  Android.pdfMakeFromImages(a4, $('#pdf-img-name').value.trim());
  $('#pdf-img-name').value = '';
}
document.querySelectorAll('#pdf-size button').forEach(b => b.addEventListener('click', () => {
  document.querySelectorAll('#pdf-size button').forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-checked', x === b ? 'true' : 'false'); });
}));
/* Pagina's kiezen: tikken op voorbeelden en het tekstvak blijven gelijk */
function pdfRangeText(){
  const l = [...pdfSel].sort((a, b) => a - b), out = [];
  for (let i = 0; i < l.length; i++) { let a = l[i], e = a; while (i + 1 < l.length && l[i + 1] === e + 1) { e++; i++; } out.push(e > a ? (a + 1) + '-' + (e + 1) : String(a + 1)); }
  return out.join(', ');
}
function pdfTogglePage(i){ if (pdfSel.has(i)) pdfSel.delete(i); else pdfSel.add(i); $('#pdf-range').value = pdfRangeText(); pdfRender(); }
function pdfParse(s, n){
  const out = new Set();
  for (const part of String(s || '').trim().replace(/\s*-\s*/g, '-').split(/[,;\s]+/)) {
    if (!part) continue;
    const m = /^(\d*)-(\d*)$/.exec(part), one = /^\d+$/.test(part);
    if (!m && !one) return null;
    const a = one ? +part : (m[1] ? +m[1] : 1), b = one ? +part : (m[2] ? +m[2] : n);
    if (a < 1 || b > n || a > b) return null;
    for (let i = a; i <= b; i++) out.add(i - 1);
  }
  return out;
}
function pdfRangeTyped(){ const s = pdfParse($('#pdf-range').value, pdfIn.pages || 0); if (s) { pdfSel = s; pdfRender(); } }
function pdfDoSplit(mode){
  if (pdfBusy) return;
  const r = $('#pdf-range').value.trim();
  if (mode !== 'each' && !r) { toast('Kies pagina\'s: tik erop of typ bijv. 1-3, 5'); return; }
  if (mode === 'each' && pdfIn.pages > 50) { askConfirm(pdfIn.pages + ' PDF\'s maken?', 'Elke pagina wordt een eigen bestand.', 'Maken', () => { pdfSetBusy('Splitsen…'); Android.pdfSplit(mode, ''); }); return; }
  pdfSetBusy('Splitsen…');
  Android.pdfSplit(mode, r);
}
function pdfFromText(){
  const t = $('#pdf-text').value;
  if (!t.trim()) { toast('Plak eerst tekst'); return; }
  pdfSetBusy('PDF maken…');
  Android.pdfFromText($('#pdf-text-title').value.trim(), t);
  $('#pdf-text').value = ''; $('#pdf-text-title').value = '';
}
function pdfOutMenu(name){
  openSheet(name, '', [
    ['Bekijken', () => Android.pdfOpen(name)],
    ['Delen (bijv. mailen of WhatsApp)', () => Android.pdfShare(JSON.stringify([name]))],
    ['Opslaan als…', () => Android.pdfSave(name)],
    ['Weghalen', () => { Android.pdfDelete(name); pdfRender(); }]]);
}
function pdfNames(){ return rdJson(Android.pdfOutputs(), []).map(o => o.name); }
function pdfShareAll(){ Android.pdfShare(JSON.stringify(pdfNames())); }
function pdfSaveAll(){ pdfSetBusy('Opslaan…'); Android.pdfSaveAll(JSON.stringify(pdfNames())); }
