/* ---------- PDF: plaatjes naar PDF, splitsen, tekst/e-mail naar PDF ----------
 * Overzicht 'pdf' (PDF openen, recent gemaakt, tegels, Meer) met per taak een eigen scherm. Terug gaat naar het overzicht. */
const PDF_SUBS = ['pdfedit', 'pdfimg', 'pdfsplit', 'pdftext', 'pdfmade'];
function pdfScr(name){ return name === 'pdf' || PDF_SUBS.includes(name); }
let pdfImgs = [], pdfSel = new Set(), pdfIn = {}, pdfBusy = false, pdfInGen = 0;
let pdfLast = null; // {scr, names}: wat er net gemaakt is, getoond op het scherm waar het gemaakt werd
let pdfJobScr = null; // het scherm waar de lopende klus (maken, splitsen, bewerken) gestart is
function pdfTakePending(){
  const p = typeof Android.pdfPending === 'function' ? Android.pdfPending() : '';
  if (!p) return null;
  try { return JSON.parse(p); } catch(e){ return null; }
}
/* Waar een resultaat van buiten (Delen → PDF maken, de viewer, een gedeeld bestand) thuishoort */
function pdfTarget(r){
  const here = pdfScr(current) ? current : 'pdf';
  if (!r || r.error) return here;
  if (r.kind === 'images') return 'pdfimg';
  // De viewer zegt welke taak (to: edit/split); een gedeelde PDF gaat naar Splitsen, tenzij je al in de bewerker zit
  if (r.kind === 'pdf') return r.to === 'edit' || (r.to !== 'split' && current === 'pdfedit') ? 'pdfedit' : 'pdfsplit';
  if (r.kind === 'done') return current === 'pdfmade' ? 'pdfmade' : 'pdf';
  return here;
}
/* openTool('pdf-share'): naar het juiste scherm en daar het klaarliggende resultaat verwerken */
function pdfShared(){ pdfOutside(pdfTakePending()); }
/* Terug in de app terwijl een PDF-scherm open staat */
function pdfResume(){ const r = pdfTakePending(); if (r) pdfOutside(r); else enterPdf(); }
function pdfOutside(r){
  const to = pdfTarget(r);
  // Java heeft de nieuwe PDF al geopend: niet bewaarde toevoegingen aan de vorige zijn niet meer op te slaan. Dat zeggen.
  const lost = !!(r && r.kind === 'pdf' && pdfEd.revision && pdfEd.revision !== r.revision && pdfEditDirty()), old = pdfIn.name || '';
  show(to);
  if (!r) return;
  if (r.kind === 'pdf' && current === 'pdfedit') pdfEd.want = true; // meteen bewerken
  pdfJobScr = current; // een resultaat van buiten hoort bij het scherm waar het landt
  onPdf(r); // ook als het scherm niet open mocht: dan staat het resultaat klaar
  if (lost) pdfLostNotice(old);
}
function pdfLostNotice(name){
  openModal(t('Andere PDF geopend'), t('De toevoegingen aan {naam} waren nog niet bewaard. Ze zijn vervangen door de PDF die je net hebt gedeeld.', { naam: name }), null, 'OK', () => {});
  $('#modal-cancel').style.display = 'none';
}
/* Een gedeelde PDF landt op Splitsen: van daaruit ook bewerken */
function pdfSplitEdit(){ if (pdfBusy || !pdfIn.revision) return; pdfEd.want = true; show('pdfedit'); pdfEditSource(); }
function pdfPickDoc(){ pdfJobScr = current; Android.pdfPickDoc(); }
function enterPdf(){
  const r = pdfTakePending();
  if (r) onPdf(r);
  pdfImgs = rdJson(Android.pdfImages(), []);
  pdfIn = rdJson(Android.pdfInfo(), {});
  pdfRender();
  pdfRenderDef();
  pdfEditSource();sigLoad();pdfSignatureRender();
}
/* Standaard PDF-app: Android laat dit de gebruiker zelf kiezen ("Altijd"), wij helpen erbij */
function pdfRenderDef(){
  const d = rdJson(typeof Android.pdfDefaultState === 'function' ? Android.pdfDefaultState() : '', {});
  const box = $('#pdf-def');
  if (d.state === 'ours') { box.textContent = t('✓ PDF\'s openen standaard in Tools'); return; }
  box.textContent = d.state === 'other' && d.app ? t('PDF\'s openen nu in {app}. Tik hier, kies Tools en dan Altijd.', { app: d.app }) : t('Tik hier, kies Tools en dan Altijd.');
}
function pdfSetBusy(tt){ if (tt && !pdfBusy) pdfJobScr = current; pdfBusy = !!tt; document.querySelectorAll('.pdfbusy').forEach(e => { e.textContent = tt || ''; }); document.querySelectorAll('.pdfscr main button').forEach(b => b.disabled = pdfBusy); pdfEditControls(); }
window.onPdf = function(r){
  if (!r) return;
  // Een gekozen bestand (plaatjes of PDF) beëindigt geen lopende klus; al het andere wel
  if (r.error || (r.kind !== 'images' && r.kind !== 'pdf')) pdfSetBusy('');
  if (r.error) { toast(r.error); if (pdfScr(current)) pdfRender(); return; }
  if (r.kind === 'images') { pdfImgs = rdJson(Android.pdfImages(), []); toast(tn(r.count, '{n} plaatje toegevoegd', '{n} plaatjes toegevoegd') + (r.warn ? ' · ' + t(r.warn) : '')); }
  if (r.kind === 'pdf') { pdfIn = r; pdfInGen++; pdfSel = new Set(); $('#pdf-range').value = ''; pdfEditSource(); }
  if ((r.kind === 'edited' || r.kind === 'done') && (r.made || []).length) pdfLast = { scr: pdfJobScr || current, names: r.made.slice() };
  if (r.error || r.kind === 'edited' || r.kind === 'done') pdfJobScr = null;
  if (r.kind === 'edited') { pdfEditSaved(r); toast('✓ Bewerkte PDF-kopie gemaakt'); }
  if (r.kind === 'done') {
    pdfImgs = rdJson(Android.pdfImages(), []);
    const n = (r.made || []).length;
    toast(n === 1 ? t('✓ PDF gemaakt') : t('✓ {n} PDF\'s gemaakt', { n }));
    if (r.lossless === false) setTimeout(() => toast('Deze PDF kon niet direct gesplitst worden; de pagina\'s zijn als afbeelding overgenomen'), 2800);
  }
  if (r.kind === 'saved') toast('✓ Opgeslagen');
  if (r.kind === 'savedAll') toast(t('✓ {n} opgeslagen in de map PDF', { n: r.count }));
  // Niet wegspringen als je intussen ergens anders bent: het resultaat staat klaar op het PDF-scherm
  if (pdfScr(current)) pdfRender();
};
/* Eén gemaakt bestand: tikken opent het menu (bekijken, delen, opslaan …) */
function pdfOutRow(o, share){
  const when = o.t ? ' · ' + (rgIsToday(o.t) ? t('vandaag {tijd}', { tijd: fmtTime(o.t) }) : fmtDate(o.t)) : '';
  const row = '<button class="trip" data-n="' + esc(o.name) + '" onclick="pdfOutMenu(this.dataset.n)"><span><b translate="no">' + uiIcon('document') + ' ' + esc(o.name) + '</b><small>' + esc(fmtB(o.size) + when) + '</small></span>' +
    (share ? '' : '<span aria-hidden="true">›</span>') + '</button>';
  if (!share) return row;
  return '<div class="pdfrec">' + row + '<button class="pdfrec-share" data-n="' + esc(o.name) + '" onclick="pdfShareOne(this.dataset.n)" aria-label="' + esc(t('{naam} delen', { naam: o.name })) + '">' + uiIcon('share') + '</button></div>';
}
function pdfShareOne(name){ Android.pdfShare(JSON.stringify([name])); }
/* Tegels: een korte hint, of wat er klaarstaat */
function pdfTileHints(){
  const dirty = typeof pdfEditDirty === 'function' && pdfEditDirty();
  $('#pdf-t-edit-d').textContent = dirty ? t('Niet bewaarde toevoegingen') : t('Tekst en handtekening');
  $('#pdf-t-img-d').textContent = pdfImgs.length ? tn(pdfImgs.length, '{n} plaatje klaar', '{n} plaatjes klaar') : t('Foto\'s of scans');
}
function pdfRender(){
  // gemaakt: de laatste drie op het overzicht, alles op het scherm Gemaakt
  const outs = rdJson(Android.pdfOutputs(), []);
  $('#pdf-recent-card').style.display = outs.length ? '' : 'none';
  $('#pdf-recent').innerHTML = outs.slice(0, 3).map(o => pdfOutRow(o, true)).join('');
  $('#pdf-recent-all').textContent = outs.length > 3 ? t('Alles tonen ({n})', { n: outs.length }) : t('Alles delen of bewaren');
  $('#pdf-out-empty').style.display = outs.length ? 'none' : '';
  $('#pdf-out-acts').style.display = outs.length ? '' : 'none';
  $('#pdf-share-all').style.display = outs.length > 1 ? '' : 'none';
  $('#pdf-out').innerHTML = outs.map(o => pdfOutRow(o, false)).join('');
  // net gemaakt, op het scherm waar het gemaakt is
  document.querySelectorAll('.pdfres').forEach(box => {
    const names = pdfLast && pdfLast.scr === box.dataset.scr ? pdfLast.names : [];
    const made = outs.filter(o => names.includes(o.name));
    box.innerHTML = made.length ? '<h3 class="pdfres-h">' + esc(t('Net gemaakt')) + '</h3>' + made.map(o => pdfOutRow(o, false)).join('') +
      '<button class="linkish" onclick="show(\'pdfmade\')">' + esc(t('Alle gemaakte PDF\'s')) + '</button>' : '';
  });
  pdfTileHints();
  // plaatjes
  $('#pdf-img-opts').style.display = pdfImgs.length ? 'block' : 'none';
  $('#pdf-img-pick').classList.toggle('ghost', pdfImgs.length > 0); // eerst kiezen, daarna is PDF maken de hoofdknop
  $('#pdf-imgs').innerHTML = pdfImgs.map((m, k) => '<div class="pdfimg"><img src="' + esc(m.thumb) + '" alt="' + t('Plaatje {n}', { n: k + 1 }) + '"><span class="pnum">' + (k + 1) + '</span>' +
    '<div class="pbtns">' + (k > 0 ? '<button onclick="pdfImgMove(' + k + ',-1)" aria-label="' + t('Plaatje {n} naar voren', { n: k + 1 }) + '">◀</button>' : '') +
    '<button onclick="pdfImgDel(' + k + ')" aria-label="' + t('Plaatje {n} weghalen', { n: k + 1 }) + '">✕</button>' +
    (k < pdfImgs.length - 1 ? '<button onclick="pdfImgMove(' + k + ',1)" aria-label="' + t('Plaatje {n} naar achteren', { n: k + 1 }) + '">▶</button>' : '') + '</div></div>').join('');
  $('#pdf-img-make').textContent = pdfImgs.length ? tn(pdfImgs.length, 'PDF maken ({n} pagina)', 'PDF maken ({n} pagina\'s)') : t('PDF maken');
  // splitsen
  const has = pdfIn && pdfIn.pages > 0;
  $('#pdf-split').style.display = has ? 'block' : 'none';
  $('#pdf-split-pick').classList.toggle('ghost', !!has);
  $('#pdf-split-pick').textContent = has ? t('Andere PDF kiezen') : t('PDF kiezen');
  if (has) {
    $('#pdf-split-info').textContent = pdfIn.name + ' · ' + tn(pdfIn.pages, '{n} pagina', '{n} pagina\'s') + (pdfIn.lossless === false ? ' · ' + t('wordt als afbeelding overgenomen') : '');
    const box = $('#pdf-pages');
    if (box.dataset.for !== String(pdfInGen)) {
      box.dataset.for = String(pdfInGen);
      box.innerHTML = Array.from({ length: Math.min(pdfIn.pages, 300) }, (_, i) => '<button class="ppage" data-i="' + i + '" onclick="pdfTogglePage(' + i + ')" aria-pressed="false" aria-label="' + t('Pagina {n}', { n: i + 1 }) + '"><img alt=""><span>' + (i + 1) + '</span></button>').join('');
      pdfLoadThumbs(0);
    }
    box.querySelectorAll('.ppage').forEach(b => { const on = pdfSel.has(+b.dataset.i); b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); });
  }
  if (pdfBusy) document.querySelectorAll('.pdfscr main button').forEach(b => b.disabled = true);
  pdfEditControls();
}
/* Voorbeelden in stukjes laden, zodat het scherm niet vastloopt bij grote PDF's */
function pdfLoadThumbs(from){
  const imgs = document.querySelectorAll('#pdf-pages .ppage img');
  const until = Math.min(imgs.length, from + 6);
  const gen = pdfInGen;
  for (let i = from; i < until; i++) {
    const u = Android.pdfThumb(i);
    if (u === 'wait') { // de PDF is even bezig (splitsen/openen): straks verder
      if (pdfScr(current)) setTimeout(() => { if (gen === pdfInGen) pdfLoadThumbs(i); }, 800);
      return;
    }
    if (/^data:image\/jpeg;base64,/.test(u)) imgs[i].src = u;
  }
  if (until < imgs.length && pdfScr(current)) setTimeout(() => { if (gen === pdfInGen) pdfLoadThumbs(until); }, 30);
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
  if (mode === 'each' && pdfIn.pages > 50) { askConfirm(t('{n} PDF\'s maken?', { n: pdfIn.pages }), 'Elke pagina wordt een eigen bestand.', 'Maken', () => { pdfSetBusy('Splitsen…'); Android.pdfSplit(mode, ''); }); return; }
  pdfSetBusy('Splitsen…');
  Android.pdfSplit(mode, r);
}
function pdfFromText(){
  const tt = $('#pdf-text').value;
  if (!tt.trim()) { toast('Plak eerst tekst'); return; }
  pdfSetBusy('PDF maken…');
  Android.pdfFromText($('#pdf-text-title').value.trim(), tt);
  $('#pdf-text').value = ''; $('#pdf-text-title').value = '';
}
function pdfOutMenu(name){
  openSheet(name, '', [
    ['Bekijken', () => Android.pdfOpen(name)],
    ['Delen (bijv. mailen of WhatsApp)', () => Android.pdfShare(JSON.stringify([name]))],
    ['Opslaan als…', () => Android.pdfSave(name)],
    ...(!isPlay() ? [['Bewaren in documentenkluis', () => pdfKeep(name)]] : []),
    ['Weghalen', () => { Android.pdfDelete(name); pdfRender(); }]]);
}
function pdfNames(){ return rdJson(Android.pdfOutputs(), []).map(o => o.name); }
function pdfShareAll(){ Android.pdfShare(JSON.stringify(pdfNames())); }
function pdfSaveAll(){ pdfSetBusy('Opslaan…'); Android.pdfSaveAll(JSON.stringify(pdfNames())); }
