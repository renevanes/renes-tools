/* ---------- Brief scannen (Documentenkluis) ----------
 * Pagina's fotograferen met de camera in de app (zoals bij Betalen), de hoeken van het papier zoeken (met de hand bij te
 * stellen), rechttrekken en er een scan van maken (achtergrond wit, tekst scherp). Daarna één PDF die Java meteen
 * versleuteld in de kluis zet, in de map waar je staat (KluisScan.java). Alles blijft in het geheugen: geen foto in de
 * galerij, geen bestand onder Gemaakt, niets op schijf. Weg van het kluisscherm, op slot (ook vanzelf) of annuleren = alles weg.
 * Het rekenwerk (hoeken zoeken, rechttrekken, scanfilter) staat los van het scherm, zodat de test het met een
 * nagemaakt beeld kan nalopen (tests/ui/brief-scannen.test.js). */
const KS_LANG = 2000; // langste zijde van een gescande pagina (±170 dpi op A4: scherp genoeg om te lezen en te herkennen)
const KS_KLEIN = 360; // zo groot zoekt hij de hoeken (sneller, en kleine vlekjes vallen weg)
const ks = { paginas: [], foto: null, hoeken: null, stream: null, camNr: 0, bezig: false, sleep: -1 };

/* ---------- rekenwerk, zonder scherm ---------- */
/** Licht deel (het papier) in een klein beeld (RGBA), en daarvan de vier hoeken [[x,y]×4] (linksboven, rechtsboven,
 *  rechtsonder, linksonder), of null als er geen duidelijk papier is. */
function ksHoekenUit(px, w, h){
  const n = w * h, lum = new Uint8Array(n), hist = new Float64Array(256);
  for (let i = 0; i < n; i++) { const v = (px[i * 4] * 77 + px[i * 4 + 1] * 150 + px[i * 4 + 2] * 29) >> 8; lum[i] = v; hist[v]++; }
  // Drempel tussen papier en ondergrond (Otsu)
  let som = 0; for (let v = 0; v < 256; v++) som += v * hist[v];
  let sB = 0, wB = 0, best = -1, T = 128;
  for (let v = 0; v < 256; v++) {
    wB += hist[v]; if (!wB) continue; const wF = n - wB; if (!wF) break;
    sB += v * hist[v]; const d = sB / wB - (som - sB) / wF, s = wB * wF * d * d;
    if (s > best) { best = s; T = v; }
  }
  // Het grootste lichte vlak (letters erin maken niet uit: we kijken alleen naar de buitenkant)
  const lab = new Int32Array(n), stapel = new Int32Array(n); let grootste = 0, grootsteLab = 0, nr = 0;
  for (let i = 0; i < n; i++) {
    if (lum[i] <= T || lab[i]) continue;
    nr++; let top = 0, k = 0; stapel[top++] = i; lab[i] = nr;
    while (top) {
      const p = stapel[--top]; k++; const x = p % w, y = (p - x) / w;
      if (x > 0 && !lab[p - 1] && lum[p - 1] > T) { lab[p - 1] = nr; stapel[top++] = p - 1; }
      if (x < w - 1 && !lab[p + 1] && lum[p + 1] > T) { lab[p + 1] = nr; stapel[top++] = p + 1; }
      if (y > 0 && !lab[p - w] && lum[p - w] > T) { lab[p - w] = nr; stapel[top++] = p - w; }
      if (y < h - 1 && !lab[p + w] && lum[p + w] > T) { lab[p + w] = nr; stapel[top++] = p + w; }
    }
    if (k > grootste) { grootste = k; grootsteLab = nr; }
  }
  if (grootste < n * 0.1) return null;
  // Buitenkant: per rij het eerste en laatste punt, daarvan de bolle omhullende
  const pt = [];
  for (let y = 0; y < h; y++) {
    let a = -1, b = -1; for (let x = 0; x < w; x++) if (lab[y * w + x] === grootsteLab) { if (a < 0) a = x; b = x; }
    if (a >= 0) { pt.push([a, y]); if (b !== a) pt.push([b, y]); }
  }
  const hull = ksOmhullende(pt); if (hull.length < 4) return null;
  // Vier hoeken: de verste twee punten (een diagonaal), en aan elke kant daarvan het punt het verst van die lijn
  let i1 = 0, i2 = 0, dmax = -1;
  for (let i = 0; i < hull.length; i++) for (let j = i + 1; j < hull.length; j++) { const d = (hull[i][0] - hull[j][0]) ** 2 + (hull[i][1] - hull[j][1]) ** 2; if (d > dmax) { dmax = d; i1 = i; i2 = j; } }
  const A = hull[i1], B = hull[i2], kant = p => (B[0] - A[0]) * (p[1] - A[1]) - (B[1] - A[1]) * (p[0] - A[0]);
  let links = null, rechts = null, lmax = 0, rmax = 0;
  for (const p of hull) { const k = kant(p); if (k > lmax) { lmax = k; links = p; } if (-k > rmax) { rmax = -k; rechts = p; } }
  if (!links || !rechts) return null;
  const q = ksOrden([A, links, B, rechts]);
  return ksOppervlak(q) >= n * 0.1 ? q : null;
}
/** Bolle omhullende (monotone ketting). */
function ksOmhullende(p){
  p = p.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (p.length < 3) return p;
  const x = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]), lo = [], hi = [];
  for (const q of p) { while (lo.length >= 2 && x(lo[lo.length - 2], lo[lo.length - 1], q) <= 0) lo.pop(); lo.push(q); }
  for (let i = p.length - 1; i >= 0; i--) { const q = p[i]; while (hi.length >= 2 && x(hi[hi.length - 2], hi[hi.length - 1], q) <= 0) hi.pop(); hi.push(q); }
  lo.pop(); hi.pop(); return lo.concat(hi);
}
/** Vier punten op volgorde: linksboven, rechtsboven, rechtsonder, linksonder (met de klok mee, y naar beneden). */
function ksOrden(q){
  const cx = q.reduce((s, p) => s + p[0], 0) / 4, cy = q.reduce((s, p) => s + p[1], 0) / 4;
  const r = q.slice().sort((a, b) => Math.atan2(a[1] - cy, a[0] - cx) - Math.atan2(b[1] - cy, b[0] - cx));
  let k = 0; r.forEach((p, i) => { if (p[0] + p[1] < r[k][0] + r[k][1]) k = i; });
  return r.slice(k).concat(r.slice(0, k));
}
function ksOppervlak(q){ let s = 0; for (let i = 0; i < q.length; i++) { const a = q[i], b = q[(i + 1) % q.length]; s += a[0] * b[1] - b[0] * a[1]; } return Math.abs(s) / 2; }
/** Bol en met de klok mee? Anders is het geen bruikbaar vlak (hoeken over elkaar gesleept). */
function ksBol(q){
  for (let i = 0; i < 4; i++) { const a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4]; if ((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]) <= 1e-9) return false; } // gespiegeld (tegen de klok in) telt ook niet
  return true;
}
/** Projectie die de punten van (4) op naar (4) legt: [a..h] met x' = (ax+by+c)/(gx+hy+1), y' = (dx+ey+f)/(gx+hy+1). */
function ksHomografie(van, naar){
  const M = [];
  for (let i = 0; i < 4; i++) {
    const [x, y] = van[i], [X, Y] = naar[i];
    M.push([x, y, 1, 0, 0, 0, -x * X, -y * X, X]); M.push([0, 0, 0, x, y, 1, -x * Y, -y * Y, Y]);
  }
  for (let c = 0; c < 8; c++) { // Gauss met spilkeuze
    let p = c; for (let r = c + 1; r < 8; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
    if (Math.abs(M[p][c]) < 1e-12) return null;
    [M[c], M[p]] = [M[p], M[c]];
    for (let r = 0; r < 8; r++) if (r !== c) { const f = M[r][c] / M[c][c]; for (let k = c; k < 9; k++) M[r][k] -= f * M[c][k]; }
  }
  return M.map((r, i) => r[8] / r[i]);
}
function ksPunt(H, x, y){ const d = H[6] * x + H[7] * y + 1; return [(H[0] * x + H[1] * y + H[2]) / d, (H[3] * x + H[4] * y + H[5]) / d]; }
/** Maat van de rechtgezette pagina: de langste zijden van het vlak, met de langste kant hooguit KS_LANG (en nooit groter dan de foto). */
function ksMaat(q){
  const d = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  const w = Math.max(d(q[0], q[1]), d(q[3], q[2])), h = Math.max(d(q[0], q[3]), d(q[1], q[2])), s = Math.min(1, KS_LANG / Math.max(w, h, 1));
  return [Math.max(16, Math.round(w * s)), Math.max(16, Math.round(h * s))];
}
/** Rechttrekken: van het vlak q (in de bron) een rechthoekig beeld; bilineair, in stukjes zodat het scherm niet vastloopt. */
async function ksRecht(bron, q){
  const [W, H] = ksMaat(q), M = ksHomografie([[0, 0], [W, 0], [W, H], [0, H]], q);
  if (!M) throw new Error('vlak');
  const sw = bron.width, sh = bron.height, src = bron.getContext('2d').getImageData(0, 0, sw, sh).data;
  const uit = document.createElement('canvas'); uit.width = W; uit.height = H;
  const cx = uit.getContext('2d'), img = cx.createImageData(W, H), d = img.data;
  for (let y = 0; y < H; y++) {
    const yy = y + 0.5;
    let nx = M[1] * yy + M[2] + M[0] * 0.5, ny = M[4] * yy + M[5] + M[3] * 0.5, nd = M[7] * yy + 1 + M[6] * 0.5, o = y * W * 4;
    for (let x = 0; x < W; x++, nx += M[0], ny += M[3], nd += M[6], o += 4) {
      let fx = nx / nd - 0.5, fy = ny / nd - 0.5;
      fx = fx < 0 ? 0 : fx > sw - 1.001 ? sw - 1.001 : fx; fy = fy < 0 ? 0 : fy > sh - 1.001 ? sh - 1.001 : fy;
      const x0 = fx | 0, y0 = fy | 0, ax = fx - x0, ay = fy - y0, i = (y0 * sw + x0) * 4, j = i + sw * 4;
      for (let c = 0; c < 3; c++) {
        const top = src[i + c] + (src[i + 4 + c] - src[i + c]) * ax, bot = src[j + c] + (src[j + 4 + c] - src[j + c]) * ax;
        d[o + c] = top + (bot - top) * ay;
      }
      d[o + 3] = 255;
    }
    if ((y & 63) === 63) await new Promise(r => setTimeout(r, 0));
  }
  cx.putImageData(img, 0, 0); return uit;
}
/** Scan maken: de achtergrond (papier, schaduw, licht) gelijk wit maken en de tekst donker. modus 'scan' = grijs, 'kleur' = kleur. */
function ksFilter(img, modus){
  const { width: W, height: H, data: d } = img, C = 8, cw = Math.ceil(W / C), ch = Math.ceil(H / C);
  // 1. gemiddelde helderheid per vakje van 8×8, 2. per vakje het lichtste in de buurt (letters vallen weg), 3. glad maken
  const cel = new Float32Array(cw * ch), tel = new Float32Array(cw * ch);
  for (let y = 0; y < H; y++) { const r = (y / C | 0) * cw; for (let x = 0, o = y * W * 4; x < W; x++, o += 4) { const k = r + (x / C | 0); cel[k] += d[o] * 0.299 + d[o + 1] * 0.587 + d[o + 2] * 0.114; tel[k]++; } }
  for (let k = 0; k < cel.length; k++) cel[k] /= tel[k] || 1;
  const R = 3, bg = new Float32Array(cw * ch);
  for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) { let m = 0; for (let v = Math.max(0, y - R); v <= Math.min(ch - 1, y + R); v++) for (let u = Math.max(0, x - R); u <= Math.min(cw - 1, x + R); u++) if (cel[v * cw + u] > m) m = cel[v * cw + u]; bg[y * cw + x] = m; }
  for (let ronde = 0; ronde < 2; ronde++) { const b = bg.slice(); for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) { let s = 0, t = 0; for (let v = Math.max(0, y - 2); v <= Math.min(ch - 1, y + 2); v++) for (let u = Math.max(0, x - 2); u <= Math.min(cw - 1, x + 2); u++) { s += b[v * cw + u]; t++; } bg[y * cw + x] = s / t; } }
  // Helderheid ten opzichte van het papier eromheen (L, papier = 255), dan een curve: papier wit, tekst donker
  const kl = modus === 'kleur', lo = kl ? 30 : 55, span = kl ? 200 : 170, gam = kl ? 1.2 : 1.6, curve = new Uint8ClampedArray(512);
  for (let i = 0; i < 512; i++) { const tt = Math.min(1, Math.max(0, (i / 2 - lo) / span)); curve[i] = Math.round(255 * Math.pow(tt, gam)); }
  const X0 = new Int32Array(W), X1 = new Int32Array(W), AX = new Float32Array(W);
  for (let x = 0; x < W; x++) { const fx = Math.min(cw - 1, Math.max(0, (x + 0.5) / C - 0.5)), x0 = Math.floor(fx); X0[x] = x0; X1[x] = Math.min(cw - 1, x0 + 1); AX[x] = fx - x0; }
  for (let y = 0; y < H; y++) {
    const fy = Math.min(ch - 1, Math.max(0, (y + 0.5) / C - 0.5)), y0 = Math.floor(fy), y1 = Math.min(ch - 1, y0 + 1), ay = fy - y0, r0 = y0 * cw, r1 = y1 * cw;
    for (let x = 0, o = y * W * 4; x < W; x++, o += 4) {
      const ax = AX[x], top = bg[r0 + X0[x]] + (bg[r0 + X1[x]] - bg[r0 + X0[x]]) * ax, bot = bg[r1 + X0[x]] + (bg[r1 + X1[x]] - bg[r1 + X0[x]]) * ax;
      const f = 510 / Math.max(24, top + (bot - top) * ay); // ×2: de curve heeft halve stappen
      if (kl) { d[o] = curve[Math.min(511, d[o] * f | 0)]; d[o + 1] = curve[Math.min(511, d[o + 1] * f | 0)]; d[o + 2] = curve[Math.min(511, d[o + 2] * f | 0)]; }
      else { const v = curve[Math.min(511, (d[o] * 0.299 + d[o + 1] * 0.587 + d[o + 2] * 0.114) * f | 0)]; d[o] = d[o + 1] = d[o + 2] = v; }
    }
  }
  return img;
}
/** Een canvas een kwartslag met de klok mee. */
function ksKwart(c){ const u = document.createElement('canvas'); u.width = c.height; u.height = c.width; const x = u.getContext('2d'); x.translate(u.width, 0); x.rotate(Math.PI / 2); x.drawImage(c, 0, 0); return u; }
function ksVerklein(c, max){ const s = Math.min(1, max / Math.max(c.width, c.height)), u = document.createElement('canvas'); u.width = Math.max(1, Math.round(c.width * s)); u.height = Math.max(1, Math.round(c.height * s)); u.getContext('2d').drawImage(c, 0, 0, u.width, u.height); return u; }
/** Hoeken zoeken in een foto (canvas); null = niet gevonden. In de coördinaten van de foto. */
function ksZoekHoeken(foto){
  const k = ksVerklein(foto, KS_KLEIN), s = foto.width / k.width;
  const q = ksHoekenUit(k.getContext('2d').getImageData(0, 0, k.width, k.height).data, k.width, k.height);
  return q ? q.map(([x, y]) => [Math.min(foto.width, x * s + s / 2), Math.min(foto.height, y * s + s / 2)]) : null;
}
function ksHeleFoto(f){ const m = Math.round(Math.min(f.width, f.height) * 0.03); return [[m, m], [f.width - m, m], [f.width - m, f.height - m], [m, f.height - m]]; }

/* ---------- camera (zoals Betalen: in de interface, alleen zolang hij open is) ---------- */
/** Het beeld voor de foto: liefst een echte foto op volle resolutie (scherper dan het videobeeld), anders het videobeeld. */
async function camFoto(v, stream, max){
  const spoor = stream && stream.getVideoTracks ? stream.getVideoTracks()[0] : null;
  const opCanvas = (bron, w, h) => { const s = Math.min(1, max / Math.max(w, h)), c = document.createElement('canvas');
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
function ksCamOpen(){ const c = $('#kl-cam'); return !!c && c.classList.contains('on'); }
function ksCamera(){ if (ks.bezig) return; Android.kluisScanCamera(); }
window.onKluisScanCamera = function(ok){
  if (current !== 'kluis') { Android.kluisScanCameraDone(); return; }
  if (!ok) { toast(t('Zonder toestemming voor de camera kan de app geen brief scannen')); return; }
  const box = $('#kl-cam'), v = $('#ks-video');
  box.classList.add('on'); ks.camNr++; $('#ks-camfoto').disabled = true; $('#ks-cammsg').textContent = t('Camera starten…');
  dialogFocus.base($('#s-kluis'), true);
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { $('#ks-cammsg').textContent = t('De camera is hier niet beschikbaar.'); return; }
  navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 3840 }, height: { ideal: 2160 } }, audio: false }).then(stream => {
    if (!ksCamOpen()) { stream.getTracks().forEach(tt => tt.stop()); return; }
    ks.stream = stream; v.srcObject = stream; v.play().catch(() => {});
    $('#ks-cammsg').textContent = t('Leg de brief op een donkere ondergrond en houd de hele pagina in beeld');
    $('#ks-camfoto').disabled = false;
  }).catch(() => { $('#ks-cammsg').textContent = t('De camera start niet.'); });
};
function ksCamDicht(){
  const box = $('#kl-cam'), was = ksCamOpen();
  if (ks.stream) { ks.stream.getTracks().forEach(tt => tt.stop()); ks.stream = null; }
  const v = $('#ks-video'); if (v) v.srcObject = null;
  if (box) box.classList.remove('on');
  if (was) dialogFocus.base($('#s-kluis'), false);
  Android.kluisScanCameraDone();
}
async function ksCamKlik(){
  const knop = $('#ks-camfoto');
  if (knop.disabled || !$('#ks-video').videoWidth) return;
  knop.disabled = true; $('#ks-cammsg').textContent = t('Foto maken… houd de telefoon stil');
  const nr = ks.camNr, c = await camFoto($('#ks-video'), ks.stream, 3200);
  if (!ksCamOpen() || ks.camNr !== nr) return; // intussen weggegaan, of de camera is opnieuw geopend
  ksCamDicht();
  if (!c) { toast(t('De foto is niet gelukt. Probeer het opnieuw.')); ksTeken(); return; }
  ksNaFoto(c);
}

/* ---------- het scherm: hoeken bijstellen, pagina's, bewaren ---------- */
function ksOpen(){ return !!$('#kl-scan') && !$('#kl-scan').hidden; }
/** Knop Brief scannen: verder waar je was, of meteen de camera. */
function ksStart(){ if (ks.paginas.length || ks.foto) ksTeken(); else ksCamera(); }
/** Een foto erbij: hoeken zoeken en laten bijstellen. */
function ksNaFoto(c){
  ks.foto = c; const q = ksZoekHoeken(c);
  ks.hoeken = q || ksHeleFoto(c); ks.gevonden = !!q;
  ksTeken();
}
function ksTeken(){
  const kaart = $('#kl-scan'); if (!kaart) return;
  if (!ks.foto && !ks.paginas.length) { kaart.hidden = true; const l = $('#kl-lijstkaart'); if (l && !klEditId) l.hidden = false; return; }
  if (klEditId) klOrganizeClose();
  $('#kl-lijstkaart').hidden = true; kaart.hidden = false;
  try { Android.kluisList(); } catch (e) { } // je bent bezig: de kluis blijft open (zoals bij elke blik op de lijst)
  $('#ks-hoekstap').hidden = !ks.foto; $('#ks-paginastap').hidden = !!ks.foto;
  if (ks.foto) ksTekenFoto(); else ksTekenPaginas();
}
function ksTekenFoto(){
  const f = ks.foto, c = $('#ks-foto'), toon = ksVerklein(f, 1000);
  c.width = toon.width; c.height = toon.height; c.getContext('2d').drawImage(toon, 0, 0);
  $('#ks-uitleg').textContent = ks.gevonden ? t('Klopt het vlak? Sleep de hoeken naar de hoeken van het papier.') : t('Het papier is niet gevonden. Sleep de hoeken naar de hoeken van het papier.');
  $('#ks-modus').value = store.get('klScanModus', 'scan') === 'kleur' ? 'kleur' : 'scan';
  $('#ks-nr').textContent = t('Pagina {n}', { n: fmtNum(ks.paginas.length + 1) });
  ksTekenHoeken();
}
const KS_NAMEN = () => [t('Hoek linksboven'), t('Hoek rechtsboven'), t('Hoek rechtsonder'), t('Hoek linksonder')];
function ksTekenHoeken(){
  const svg = $('#ks-vlak'), f = ks.foto; if (!svg || !f) return;
  svg.setAttribute('viewBox', '0 0 ' + f.width + ' ' + f.height);
  const breed = svg.getBoundingClientRect().width || 340, s = f.width / breed, q = ks.hoeken;
  let html = '<polygon points="' + q.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ') + '" class="ks-poly" stroke-width="' + (2 * s).toFixed(2) + '"/>';
  q.forEach((p, i) => { html += '<g class="ks-hoek" data-i="' + i + '" tabindex="0" role="slider" aria-label="' + esc(KS_NAMEN()[i]) + '" aria-valuetext="' + esc(Math.round(p[0] / f.width * 100) + '%, ' + Math.round(p[1] / f.height * 100) + '%') + '">'
    + '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="' + (24 * s).toFixed(1) + '" class="ks-raak"/>'
    + '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="' + (11 * s).toFixed(1) + '" class="ks-stip" stroke-width="' + (2.5 * s).toFixed(2) + '"/></g>'; });
  svg.innerHTML = html;
  $('#ks-goed').disabled = !ksBol(q);
}
function ksZet(i, x, y){
  const f = ks.foto; ks.hoeken[i] = [Math.max(0, Math.min(f.width, x)), Math.max(0, Math.min(f.height, y))];
  ksTekenHoeken();
}
function ksVlakEvents(){
  const svg = $('#ks-vlak'); if (!svg || svg.dataset.ks) return; svg.dataset.ks = '1';
  const naar = e => { const r = svg.getBoundingClientRect(); return [(e.clientX - r.left) / r.width * ks.foto.width, (e.clientY - r.top) / r.height * ks.foto.height]; };
  svg.addEventListener('pointerdown', e => {
    if (!ks.foto) return;
    const g = e.target.closest('.ks-hoek'); let i = g ? +g.dataset.i : -1;
    if (i < 0) { const [x, y] = naar(e); let best = Infinity; ks.hoeken.forEach((p, k) => { const d = Math.hypot(p[0] - x, p[1] - y); if (d < best) { best = d; i = k; } }); if (best > ks.foto.width / 6) return; } // naast een hoek: de dichtstbijzijnde, als hij in de buurt is
    ks.sleep = i; svg.setPointerCapture(e.pointerId); e.preventDefault();
  });
  svg.addEventListener('pointermove', e => { if (ks.sleep < 0 || !ks.foto) return; const [x, y] = naar(e); ksZet(ks.sleep, x, y); });
  const los = () => { if (ks.sleep >= 0) { const i = ks.sleep; ks.sleep = -1; const g = svg.querySelector('.ks-hoek[data-i="' + i + '"]'); if (g) g.focus({ preventScroll: true }); } };
  svg.addEventListener('pointerup', los); svg.addEventListener('pointercancel', los);
  svg.addEventListener('keydown', e => {
    const g = e.target.closest('.ks-hoek'); if (!g || !ks.foto) return;
    const st = Math.max(1, ks.foto.width / 100) * (e.shiftKey ? 5 : 1), d = { ArrowLeft: [-st, 0], ArrowRight: [st, 0], ArrowUp: [0, -st], ArrowDown: [0, st] }[e.key];
    if (!d) return; e.preventDefault(); const i = +g.dataset.i, p = ks.hoeken[i];
    ksZet(i, p[0] + d[0], p[1] + d[1]); const n = svg.querySelector('.ks-hoek[data-i="' + i + '"]'); if (n) n.focus({ preventScroll: true });
  });
}
function ksHeel(){ if (!ks.foto) return; ks.hoeken = ksHeleFoto(ks.foto); ksTekenHoeken(); }
function ksModus(v){ store.set('klScanModus', v === 'kleur' ? 'kleur' : 'scan'); }
/** Deze foto opnieuw maken (de vorige pagina's blijven). */
function ksOpnieuw(){ ks.foto = null; ks.hoeken = null; ksTeken(); ksCamera(); }
/** Rechtzetten, scan maken en als pagina bewaren (in het geheugen). */
async function ksGebruik(){
  if (ks.bezig || !ks.foto || !ksBol(ks.hoeken)) return;
  ks.bezig = true; const foto = ks.foto, q = ks.hoeken.map(p => p.slice()), modus = $('#ks-modus').value === 'kleur' ? 'kleur' : 'scan';
  const st = $('#ks-status'); uiWachtTekst(st, t('Rechtzetten en scannen…')); $('#ks-goed').disabled = true;
  try {
    const c = await ksRecht(foto, q);
    if (ks.foto !== foto) return; // intussen geannuleerd
    const cx = c.getContext('2d'); cx.putImageData(ksFilter(cx.getImageData(0, 0, c.width, c.height), modus), 0, 0);
    ksPagina(c, modus);
    ks.foto = null; ks.hoeken = null; st.textContent = ''; ks.bezig = false;
    ksTeken();
  } catch (e) { st.textContent = t('Dit vlak lukt niet. Zet de hoeken op de hoeken van het papier.'); }
  finally { ks.bezig = false; if (ks.foto) $('#ks-goed').disabled = !ksBol(ks.hoeken); }
}
function ksPagina(c, modus, plek){
  const p = { jpeg: c.toDataURL('image/jpeg', modus === 'kleur' ? 0.8 : 0.72), w: c.width, h: c.height, modus, duim: ksVerklein(c, 240).toDataURL('image/jpeg', 0.7) };
  if (plek >= 0) ks.paginas[plek] = p; else ks.paginas.push(p);
}
function ksTekenPaginas(){
  const box = $('#ks-paginas'), n = ks.paginas.length;
  box.replaceChildren();
  ks.paginas.forEach((p, i) => {
    const el = document.createElement('div'); el.className = 'ks-pag';
    const img = document.createElement('img'); img.src = p.duim; img.alt = t('Pagina {n}', { n: fmtNum(i + 1) }); el.appendChild(img);
    const nr = document.createElement('span'); nr.className = 'ks-pagnr'; nr.textContent = fmtNum(i + 1); el.appendChild(nr);
    const acts = document.createElement('div'); acts.className = 'ks-pagacts';
    const knop = (icoon, label, fn, uit) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'icon-btn'; b.innerHTML = uiIcon(icoon); b.setAttribute('aria-label', label); b.disabled = !!uit; b.addEventListener('click', fn); acts.appendChild(b); };
    knop('refresh', t('Pagina {n} draaien', { n: fmtNum(i + 1) }), () => ksDraai(i));
    knop('left', t('Pagina {n} eerder', { n: fmtNum(i + 1) }), () => ksSchuif(i), i === 0);
    knop('trash', t('Pagina {n} weghalen', { n: fmtNum(i + 1) }), () => ksWeg(i));
    el.appendChild(acts); box.appendChild(el);
  });
  $('#ks-aantal').textContent = tn(n, 'Eén pagina', '{n} pagina\'s');
  const naam = $('#ks-naam'); if (!naam.value) naam.placeholder = ksStandaardNaam();
  $('#ks-waar').textContent = klMap.startsWith('map:') ? t('Komt in de map {map}', { map: klMap.slice(4).split('/').join(' › ') }) : t('Komt bovenaan in de kluis, zonder map');
  $('#ks-bewaar').disabled = !n || ks.bezig;
}
function ksStandaardNaam(){ return t('Brief van {dag}', { dag: fmtDate(Date.now(), { day: 'numeric', month: 'long', year: 'numeric' }) }); }
async function ksDraai(i){
  const p = ks.paginas[i]; if (!p || ks.bezig) return;
  const img = new Image(); img.src = p.jpeg; try { await img.decode(); } catch (e) { return; }
  if (ks.paginas[i] !== p) return;
  const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight; c.getContext('2d').drawImage(img, 0, 0);
  ksPagina(ksKwart(c), p.modus, i); ksTekenPaginas();
}
function ksSchuif(i){ if (i <= 0 || ks.bezig) return; const p = ks.paginas.splice(i, 1)[0]; ks.paginas.splice(i - 1, 0, p); ksTekenPaginas(); }
function ksWeg(i){ if (ks.bezig) return; ks.paginas.splice(i, 1); ksTeken(); }
/** Alles weg (annuleren, op slot, weg van het kluisscherm, na bewaren). */
function ksWis(){
  if (ksCamOpen()) ksCamDicht();
  ks.paginas = []; ks.foto = null; ks.hoeken = null; ks.camNr++;
  try { Android.kluisScanWis(); } catch (e) { }
  const k = $('#kl-scan'); if (!k) return;
  k.hidden = true; $('#ks-naam').value = ''; $('#ks-paginas').replaceChildren(); $('#ks-vlak').replaceChildren(); $('#ks-status').textContent = '';
  const c = $('#ks-foto'); c.width = c.height = 1;
  if (!klEditId) $('#kl-lijstkaart').hidden = false;
}
function ksAnnuleer(){
  if (ks.bezig) { toast(t('Even wachten: de brief wordt bewaard')); return; }
  if (!ks.paginas.length) { ksWis(); return; }
  askConfirm(t('Scan weggooien?'), tn(ks.paginas.length, 'De gescande pagina is nog niet bewaard.', 'De {n} gescande pagina\'s zijn nog niet bewaard.'), t('Weggooien'), () => ksWis());
}
/** Terug (pijl of de knop van de telefoon) tijdens het scannen: eerst de foto weg, dan vragen of de scan weg mag. */
function ksTerug(){
  if (!ksOpen()) return false;
  if (ks.bezig) { toast(t('Even wachten: de brief wordt bewaard')); return true; }
  if (ks.foto && ks.paginas.length) { ks.foto = null; ks.hoeken = null; ksTeken(); return true; }
  ksAnnuleer(); return true;
}
/** Bewaren: de pagina's één voor één naar Java (geheugen), dan de PDF versleuteld de kluis in. */
async function ksBewaar(){
  if (ks.bezig || !ks.paginas.length) return;
  const naam = $('#ks-naam').value.trim() || ksStandaardNaam(), map = klMap.startsWith('map:') ? klMap.slice(4) : '';
  if (naam.length > 110) { toast(t('Kies een naam van hooguit 110 tekens')); return; }
  ks.bezig = true; $('#ks-bewaar').disabled = true; uiWachtTekst($('#ks-bstatus'), t('Bewaren in de documentenkluis…'));
  let fout = '';
  const paginas = ks.paginas.slice(), nr = ks.camNr;
  try { Android.kluisScanWis(); for (const p of paginas) { await new Promise(r => setTimeout(r, 0)); if (ks.camNr !== nr) return; fout = Android.kluisScanPagina(p.jpeg) || ''; if (fout) break; } } // tussendoor tekent het scherm de wachtbalk
  catch (e) { fout = t('Bewaren lukt niet'); }
  if (ks.camNr !== nr) return; // intussen weggegaan of op slot: ksWis heeft Java al leeggemaakt
  if (fout) { try { Android.kluisScanWis(); } catch (e) { } ks.bezig = false; $('#ks-bstatus').textContent = fout; $('#ks-bewaar').disabled = false; toast(fout); klRender(); return; }
  Android.kluisScanBewaar(naam, map);
}
window.onKluisScan = function(r){
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { r = {}; } }
  ks.bezig = false;
  const st = $('#ks-bstatus'); if (st) st.textContent = '';
  if (!r || r.error || !r.id) { const f = t(r && r.error || 'Bewaren lukt niet'); if (st) st.textContent = f; if ($('#ks-bewaar')) $('#ks-bewaar').disabled = false; toast(f); if (current === 'kluis') klRender(); return; }
  ksWis(); toast(t('Brief bewaard in de kluis'));
  if (current !== 'kluis') return;
  klRender(); klKaart(r.id); // meteen de eigenschappen invullen (soort, van wie, datum)
};
(function(){
  const pauze = window.onPauseApp; window.onPauseApp = function(){ if (pauze) pauze(); if (ksCamOpen()) ksCamDicht(); }; // de camera nooit op de achtergrond
  const klaar = () => ksVlakEvents();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', klaar); else klaar();
  window.addEventListener('resize', () => { if (ks.foto && ksOpen()) ksTekenHoeken(); });
})();
