/* ---------- Foto bewerken: één bewerker voor de hele app ----------
 * Bijsnijden (vier hoeken, ook schuin: dan wordt het vlak rechtgetrokken), een kwartslag draaien, rechtzetten, licht,
 * contrast, zwart-wit en zwarte vlakken. De bewerker geeft alleen de bewerking terug (ops; zelfde vorm als FotoOps.java):
 * Java past die toe op de foto zelf (tool Foto bewerken, de kaartfoto's in Stamgegevens, een pagina van ID-kopie), zodat
 * de volle resolutie blijft. fbVoorstel zoekt zelf een verbetering (het document in de foto, rechtop, beter belicht);
 * fbRender rekent het voorbeeld uit, op dezelfde manier als Java. Alles in het geheugen.
 * Gebruik: fotoBewerk({ src, titel, knop, soort: 'idkaart'|'paspoort'|'rijbewijs'|'doc'|'', zijde, ops, voorstel, afdekken })
 *   → Promise met { ops, gewijzigd } of null (geannuleerd). Ook in de testbrug te gebruiken (tests/ui/foto-bewerken.test.js). */
const FB_MAX_TOON = 1000; // zo groot tekent de bewerker de foto op het scherm
/** Verhoudingen (breedte/hoogte) van documenten: ID-1 (pas, rijbewijs) en ID-3 (fotopagina van een paspoort). */
const FB_DOC = { idkaart: 85.6 / 54, rijbewijs: 85.6 / 54, paspoort: 125 / 88 };
const FB_VERH = () => [['0', t('Vrij')], [String(85.6 / 54), t('ID-kaart of rijbewijs')], [String(125 / 88), t('Paspoort (fotopagina)')],
  [String(297 / 210), t('A4 liggend')], [String(210 / 297), t('A4 staand')], ['1', t('Vierkant')]];
const fb = { open: false, foto: null, ops: null, terug: [], klaar: null, opts: {}, modus: 'bewerk', sleep: -1, vlak: null, tab: 'snij', hoek: 0, hoekBasis: null, cache: {}, teken: 0, toon: null, uit: null, zoek: 0, render: 0, lichtStap: false, zoom: 1, vingers: new Map(), knijp: null, schuif: null };

/* ---------- rekenwerk, zonder scherm ---------- */
function fbLeeg(){ return { draai: 0, q: [[0, 0], [1, 0], [1, 1], [0, 1]], verhouding: 0, licht: 0, contrast: 0, niveau: [0, 255], grijs: false, vlakken: [] }; }
function fbKopie(o){ return JSON.parse(JSON.stringify(o)); }
/** Doet de bewerking iets? */
function fbIets(o){ const l = fbLeeg(); return JSON.stringify(fbSchoon(o)) !== JSON.stringify(fbSchoon(l)); }
/** Wat naar Java gaat (FotoOps.lees): afgeronde getallen, alleen bekende velden. */
function fbSchoon(o){
  const r = n => Math.round(n * 10000) / 10000;
  return { v: 1, draai: o.draai, q: o.q.map(p => [r(Math.min(1, Math.max(0, p[0]))), r(Math.min(1, Math.max(0, p[1])))]), verhouding: o.verhouding ? r(o.verhouding) : 0,
    licht: Math.round(o.licht), contrast: Math.round(o.contrast), niveau: [Math.round(o.niveau[0]), Math.round(o.niveau[1])], grijs: !!o.grijs,
    vlakken: o.vlakken.map(v => v.map(r)) };
}
/** Een kwartslag (stap = 1 met de klok mee, -1 tegen de klok in): de foto draait, het vlak, de vlakken en de verhouding draaien mee. */
function fbDraaiOps(o, stap){
  const cw = stap > 0;
  o.draai = ((o.draai + (cw ? 90 : 270)) % 360);
  const p = cw ? ([x, y]) => [1 - y, x] : ([x, y]) => [y, 1 - x], q = o.q.map(p);
  o.q = cw ? [q[3], q[0], q[1], q[2]] : [q[1], q[2], q[3], q[0]];
  o.vlakken = o.vlakken.map(([x, y, w, h]) => cw ? [1 - y - h, x, h, w] : [y, 1 - x - w, h, w]);
  if (o.verhouding) o.verhouding = 1 / o.verhouding;
  return o;
}
/** Maat van de uitkomst (zelfde als FotoOps.maat): w×h = de (gedraaide) foto. */
function fbMaat(q, w, h, verh, max){
  const P = q.map(([x, y]) => [x * w, y * h]), d = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  let bw = Math.max(d(P[0], P[1]), d(P[3], P[2])), bh = Math.max(d(P[0], P[3]), d(P[1], P[2]));
  if (verh > 0) { const b = Math.max(bw, bh * verh); bw = b; bh = b / verh; }
  const s = Math.min(1, max / Math.max(1, bw, bh));
  return [Math.max(16, Math.round(bw * s)), Math.max(16, Math.round(bh * s))];
}
/** Kleur: uit = a·in + b (zelfde als FotoOps.kleur). */
function fbKleurAB(o){
  const c = (100 + o.contrast) / 100, span = Math.max(1, o.niveau[1] - o.niveau[0]), a = 255 / span * c;
  return [a, (-o.niveau[0] * 255 / span - 128) * c + 128 + o.licht * 1.28];
}
function fbKleurLeeg(o){ return !o.licht && !o.contrast && o.niveau[0] === 0 && o.niveau[1] === 255 && !o.grijs; }
function fbKleur(img, o){
  if (fbKleurLeeg(o)) return img;
  const [a, b] = fbKleurAB(o), d = img.data;
  for (let i = 0; i < d.length; i += 4) {
    if (o.grijs) { const v = (d[i] * 0.299 + d[i + 1] * 0.587 + d[i + 2] * 0.114) * a + b; d[i] = d[i + 1] = d[i + 2] = v; }
    else { d[i] = d[i] * a + b; d[i + 1] = d[i + 1] * a + b; d[i + 2] = d[i + 2] * a + b; }
  }
  return img;
}
/** De foto na de kwartslag (één keer per stand). */
function fbGedraaid(foto, draai, cache){
  if (!draai) return foto;
  if (cache && cache[draai]) return cache[draai];
  const c = document.createElement('canvas'), z = draai === 90 || draai === 270; c.width = z ? foto.height : foto.width; c.height = z ? foto.width : foto.height;
  const x = c.getContext('2d'); x.translate(c.width / 2, c.height / 2); x.rotate(draai * Math.PI / 180); x.drawImage(foto, -foto.width / 2, -foto.height / 2);
  if (cache) cache[draai] = c;
  return c;
}
/** Het voorbeeld van de bewerking (langste kant hooguit max); in stukjes, zodat het scherm niet vastloopt. */
async function fbRender(foto, o, max, cache){
  const g = fbGedraaid(foto, o.draai, cache), sw = g.width, sh = g.height;
  const [W, H] = fbMaat(o.q, sw, sh, o.verhouding, max), M = ksHomografie([[0, 0], [W, 0], [W, H], [0, H]], o.q.map(([x, y]) => [x * sw, y * sh]));
  if (!M) throw new Error('vlak');
  const src = g.getContext('2d').getImageData(0, 0, sw, sh).data, uit = document.createElement('canvas'); uit.width = W; uit.height = H;
  const cx = uit.getContext('2d'), img = cx.createImageData(W, H), d = img.data;
  for (let y = 0; y < H; y++) {
    const yy = y + 0.5; let nx = M[1] * yy + M[2] + M[0] * 0.5, ny = M[4] * yy + M[5] + M[3] * 0.5, nd = M[7] * yy + 1 + M[6] * 0.5, k = y * W * 4;
    for (let x = 0; x < W; x++, nx += M[0], ny += M[3], nd += M[6], k += 4) {
      let fx = nx / nd - 0.5, fy = ny / nd - 0.5;
      if (fx < -1 || fy < -1 || fx > sw || fy > sh) { d[k] = d[k + 1] = d[k + 2] = 255; d[k + 3] = 255; continue; }
      fx = fx < 0 ? 0 : fx > sw - 1.001 ? sw - 1.001 : fx; fy = fy < 0 ? 0 : fy > sh - 1.001 ? sh - 1.001 : fy;
      const x0 = fx | 0, y0 = fy | 0, ax = fx - x0, ay = fy - y0, i = (y0 * sw + x0) * 4, j = i + sw * 4;
      for (let c = 0; c < 3; c++) { const top = src[i + c] + (src[i + 4 + c] - src[i + c]) * ax, bot = src[j + c] + (src[j + 4 + c] - src[j + c]) * ax; d[k + c] = top + (bot - top) * ay; }
      d[k + 3] = 255;
    }
    if ((y & 63) === 63) await new Promise(r => setTimeout(r, 0));
  }
  cx.putImageData(fbKleur(img, o), 0, 0);
  cx.fillStyle = '#000000';
  for (const r of o.vlakken) { const [x0, y0, x1, y1] = fbVlakPix(r, W, H); cx.fillRect(x0, y0, x1 - x0, y1 - y0); }
  return uit;
}
/** Een zwart vlak in pixels, met een kleine marge (zelfde als FotoBewerk.vlak): liever iets te veel zwart. */
const FB_MARGE = 0.002;
function fbVlakPix([x, y, w, h], W, H){
  return [Math.max(0, Math.floor((x - FB_MARGE) * W)), Math.max(0, Math.floor((y - FB_MARGE) * H)), Math.min(W, Math.ceil((x + w + FB_MARGE) * W)), Math.min(H, Math.ceil((y + h + FB_MARGE) * H))];
}
/** Bruikbaar vlak: bol, met de klok mee en niet te klein (zelfde als FotoOps.bol en de grens op het oppervlak). */
function fbGeldig(q, pix){ return ksBol(pix) && ksOppervlak(q) >= 0.0004; }
/** Vier hoeken uit de omhullende: de verste twee punten (een diagonaal), en aan elke kant daarvan het verste punt. */
function fbVierHoeken(hull){
  if (hull.length < 4) return null;
  let i1 = 0, i2 = 0, dmax = -1;
  for (let i = 0; i < hull.length; i++) for (let j = i + 1; j < hull.length; j++) { const d = (hull[i][0] - hull[j][0]) ** 2 + (hull[i][1] - hull[j][1]) ** 2; if (d > dmax) { dmax = d; i1 = i; i2 = j; } }
  const A = hull[i1], B = hull[i2], kant = p => (B[0] - A[0]) * (p[1] - A[1]) - (B[1] - A[1]) * (p[0] - A[0]);
  let l = null, r = null, lm = 0, rm = 0;
  for (const p of hull) { const k = kant(p); if (k > lm) { lm = k; l = p; } if (-k > rm) { rm = -k; r = p; } }
  return l && r ? ksOrden([A, l, B, r]) : null;
}
/** Het document in een klein beeld (RGBA): alles wat duidelijk anders is dan de rand (de ondergrond), het grootste vlak
 *  daarvan, en de vier hoeken. Werkt ook voor een gekleurde pas op een tafel; null = niet gevonden. */
function fbDocHoeken(px, w, h){
  const n = w * h, rand = [[], [], []];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) { const i = (y * w + x) * 4; rand[0].push(px[i]); rand[1].push(px[i + 1]); rand[2].push(px[i + 2]); }
  const med = rand.map(a => { a.sort((p, q) => p - q); return a[a.length >> 1]; });
  const dd = new Uint8Array(n), hist = new Float64Array(256);
  for (let i = 0; i < n; i++) { const v = Math.min(255, ((Math.abs(px[i * 4] - med[0]) + Math.abs(px[i * 4 + 1] - med[1]) + Math.abs(px[i * 4 + 2] - med[2])) / 2) | 0); dd[i] = v; hist[v]++; }
  let som = 0; for (let v = 0; v < 256; v++) som += v * hist[v];
  let sB = 0, wB = 0, best = -1, T = 0;
  for (let v = 0; v < 256; v++) { wB += hist[v]; if (!wB) continue; const wF = n - wB; if (!wF) break; sB += v * hist[v]; const d = sB / wB - (som - sB) / wF, s = wB * wF * d * d; if (s > best) { best = s; T = v; } }
  if (T < 10) return null; // document en ondergrond lijken te veel op elkaar
  const lab = new Int32Array(n), stapel = new Int32Array(n); let grootste = 0, gl = 0, nr = 0, bx = [0, 0, 0, 0];
  for (let i = 0; i < n; i++) {
    if (dd[i] <= T || lab[i]) continue;
    nr++; let top = 0, k = 0, x0 = w, x1 = 0, y0 = h, y1 = 0; stapel[top++] = i; lab[i] = nr;
    while (top) {
      const p = stapel[--top]; k++; const x = p % w, y = (p - x) / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
      if (x > 0 && !lab[p - 1] && dd[p - 1] > T) { lab[p - 1] = nr; stapel[top++] = p - 1; }
      if (x < w - 1 && !lab[p + 1] && dd[p + 1] > T) { lab[p + 1] = nr; stapel[top++] = p + 1; }
      if (y > 0 && !lab[p - w] && dd[p - w] > T) { lab[p - w] = nr; stapel[top++] = p - w; }
      if (y < h - 1 && !lab[p + w] && dd[p + w] > T) { lab[p + w] = nr; stapel[top++] = p + w; }
    }
    if (k > grootste) { grootste = k; gl = nr; bx = [x0, y0, x1, y1]; }
  }
  if (grootste < n * 0.1 || (bx[2] - bx[0] >= w - 3 && bx[3] - bx[1] >= h - 3)) return null;
  const pt = [];
  for (let y = bx[1]; y <= bx[3]; y++) { let a = -1, b = -1; for (let x = bx[0]; x <= bx[2]; x++) if (lab[y * w + x] === gl) { if (a < 0) a = x; b = x; } if (a >= 0) { pt.push([a, y]); if (b !== a) pt.push([b, y]); } }
  const q = fbVierHoeken(ksOmhullende(pt));
  return q && ksBol(q) && ksOppervlak(q) >= n * 0.12 ? q : null;
}
/** Waar staan de twee regels tekens (de machineleesbare strook van een paspoort of ID-kaart): 'onder', 'boven' of ''.
 *  Zo'n strook is druk én loopt over bijna de hele breedte; gewone tekst (een kop, een naam) niet. */
function fbStrook(c){
  const w = c.width, h = c.height, d = c.getContext('2d').getImageData(0, 0, w, h).data, l = i => d[i] * 0.299 + d[i + 1] * 0.587 + d[i + 2] * 0.114, V = 20;
  const strook = (y0, y1) => {
    let n = 0; const vak = new Uint8Array(V);
    for (let y = y0; y < y1; y++) for (let x = 1; x < w; x++) { const i = (y * w + x) * 4; if (Math.abs(l(i) - l(i - 4)) > 45) { n++; vak[Math.min(V - 1, x * V / w | 0)] = 1; } }
    return { dicht: n / Math.max(1, (y1 - y0) * w), breed: vak.reduce((a, b) => a + b, 0) / V };
  };
  const s = Math.max(2, Math.round(h * 0.22)), onder = strook(h - s, h), boven = strook(0, s), mrz = x => x.breed >= 0.8 && x.dicht >= 0.04;
  return mrz(onder) && !mrz(boven) ? 'onder' : mrz(boven) && !mrz(onder) ? 'boven' : '';
}
/** Helderheid: 0,5% en 99,5% (voor Automatisch: zwart echt zwart, papier echt wit). */
function fbNiveau(c){
  const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data, hist = new Float64Array(256); let n = 0;
  for (let i = 0; i < d.length; i += 16) { hist[(d[i] * 0.299 + d[i + 1] * 0.587 + d[i + 2] * 0.114) | 0]++; n++; }
  let s = 0, lo = 0, hi = 255; for (let v = 0; v < 256; v++) { s += hist[v]; if (s >= n * 0.005) { lo = v; break; } }
  s = 0; for (let v = 255; v >= 0; v--) { s += hist[v]; if (s >= n * 0.005) { hi = v; break; } }
  return [Math.min(lo, 80), Math.max(hi, 170)];
}
/**
 * Zelf een verbetering zoeken: het document in de foto (bijsnijden en rechttrekken), rechtop (een document is liggend;
 * bij een paspoort of de achterkant van een ID-kaart staan de tekenregels onderaan) en de belichting. soort: idkaart,
 * paspoort, rijbewijs, doc (brief) of '' (gewone foto: alleen de belichting). Antwoord {ops, redenen} of null.
 */
async function fbVoorstel(foto, soort, zijde){
  soort = soort || ''; const o = fbLeeg(), redenen = [];
  const k = ksVerklein(foto, 360), px = k.getContext('2d').getImageData(0, 0, k.width, k.height).data;
  if (soort) {
    const q = fbDocHoeken(px, k.width, k.height) || (soort === 'doc' && typeof ksHoekenUit === 'function' ? ksHoekenUit(px, k.width, k.height) : null);
    if (q) {
      const qn = q.map(([x, y]) => [Math.min(1, Math.max(0, (x + 0.5) / k.width)), Math.min(1, Math.max(0, (y + 0.5) / k.height))]);
      const heel = [[0, 0], [1, 0], [1, 1], [0, 1]].every((p, i) => Math.abs(p[0] - qn[i][0]) < 0.03 && Math.abs(p[1] - qn[i][1]) < 0.03);
      if (!heel) { o.q = qn; redenen.push('bijsnijden'); }
    }
  }
  const doc = FB_DOC[soort];
  if (doc) {
    const [W, H] = fbMaat(o.q, foto.width, foto.height, 0, 1e9), staand = H > W * 1.12, strook = soort === 'paspoort' || (soort === 'idkaart' && zijde === 'back');
    let stappen = staand ? 1 : 0;
    if (strook) { // de strook hoort onderaan: draai zo dat hij daar staat (alleen als hij duidelijk te zien is)
      const p = fbKopie(o); for (let i = 0; i < stappen; i++) fbDraaiOps(p, 1);
      if (fbStrook(await fbRender(k, p, 300)) === 'boven') stappen += 2;
    }
    for (let i = 0; i < stappen; i++) fbDraaiOps(o, 1);
    if (stappen) redenen.push('draaien');
    const [W2, H2] = fbMaat(o.q, stappen % 2 ? foto.height : foto.width, stappen % 2 ? foto.width : foto.height, 0, 1e9);
    if (redenen.includes('bijsnijden') && Math.abs(W2 / H2 / doc - 1) < 0.15) o.verhouding = doc;
  }
  const n = fbNiveau(await fbRender(k, o, 300));
  if (n[1] - n[0] >= 60 && (n[0] > 12 || n[1] < 240)) { o.niveau = n; redenen.push('licht'); }
  return redenen.length ? { ops: o, redenen } : null;
}
function fbRedenTekst(r){
  const w = { bijsnijden: t('bijgesneden tot het document'), draaien: t('rechtop gedraaid'), licht: t('beter belicht') };
  return r.map(x => w[x]).filter(Boolean).join(', ');
}

/* ---------- de bewerker (venster #fbdlg) ---------- */
function fbAan(){ return fb.open; }
function fotoBewerk(opts = {}){
  if (fb.open) fbSluit();
  return new Promise(klaar => {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight; c.getContext('2d').drawImage(img, 0, 0);
      fbStart(c, opts, klaar);
    };
    img.onerror = () => { toast(t('Deze foto kan niet worden geopend')); klaar(null); };
    if (opts.src instanceof HTMLCanvasElement) { fbStart(opts.src, opts, klaar); return; }
    img.src = opts.src;
  });
}
function fbStart(foto, opts, klaar){
  fbVergToon(false);
  Object.assign(fb, { modus: 'bewerk', open: true, foto, opts, klaar, ops: opts.ops ? fbKopie(opts.ops) : fbLeeg(), terug: [], tab: 'snij', hoek: 0, hoekBasis: null, cache: {}, sleep: -1, vlak: null, uit: null, lichtStap: false, zoom: 1, vingers: new Map(), knijp: null, schuif: null });
  $('#fb-titel').textContent = opts.titel || t('Foto bewerken');
  $('#fb-ok').textContent = opts.knop || t('Klaar');
  $('#fb-tab-vlak').hidden = opts.afdekken === false;
  $('#fb-uitleg').textContent = opts.uitleg || '';
  $('#fb-verh').innerHTML = FB_VERH().map(([v, n]) => '<option value="' + v + '">' + esc(n) + '</option>').join('');
  fbStatus(''); fbTab('snij');
  $('#fbdlg').classList.add('on'); dialogFocus.sync($('#fb-ok'));
  fbTeken(); if (opts.voorstel && !opts.ops) fbAuto(true);
}
function fbStatus(s, wacht){ const el = $('#fb-status'); if (wacht) uiWachtTekst(el, s); else el.textContent = s; }
function fbSluit(uitkomst = null){
  if (!fb.open) return;
  fb.open = false; fb.zoek++; fb.render++; $('#fbdlg').classList.remove('on'); dialogFocus.sync();
  const k = fb.klaar; fb.klaar = null; fb.foto = null; fb.cache = {}; fb.uit = null; fb.toon = null; fb.modus = 'bewerk';
  $('#fb-verg-voor').removeAttribute('src'); $('#fb-verg-na').removeAttribute('src'); fbVergToon(false);
  const c = $('#fb-doek'); c.width = 1; c.height = 1; $('#fb-vlak').innerHTML = '';
  fb.zoom = 1; fb.knijp = null; fb.schuif = null; if (fb.vingers) fb.vingers.clear(); fbZoomPas();
  clearTimeout(fb.straksT); fb.straksNr = (fb.straksNr || 0) + 1; const sd = $('#fb-straks-doek'); sd.width = 1; sd.height = 1;
  if (k) k(uitkomst);
}
function fbAnnuleer(){
  if (!fb.open) return;
  if (fb.modus === 'controle') { fbVergTerug(); return; } // eerst terug naar het bewerken
  if (fb.modus === 'los') { fbSluit(null); return; }
  if (fb.terug.length && fbIets(fb.ops)) { askConfirm(t('Stoppen met bewerken?'), t('Wat je hebt aangepast, wordt niet bewaard.'), t('Stoppen'), () => fbSluit(null)); return; }
  fbSluit(null);
}
function fbOk(){
  if (!fb.open) return;
  if (fb.modus === 'los') { fbSluit(true); return; }
  if (!fbGeldig(fb.ops.q, fbPix(fb.ops.q))) { fbStatus(t('Dit vlak lukt niet. Zet de hoeken op de hoeken van wat je wilt houden.')); return; }
  // Eerst laten zien wat er verandert (opts.controle): voor en na naast elkaar, dan pas bewaren
  if (fb.opts.controle && fb.modus === 'bewerk' && fbIets(fb.ops)) { fbControle(); return; }
  fbSluit({ ops: fbSchoon(fb.ops), gewijzigd: fbIets(fb.ops) });
}
/* ---------- voor en na naast elkaar ---------- */
function fbVergToon(aan){
  $('#fb-verg').hidden = !aan; $('#fb-werk').hidden = aan;
}
async function fbControle(){
  const nr = ++fb.render, foto = fb.foto; fb.zoek++; fb.modus = 'controle';
  $('#fb-verg-uitleg').textContent = fb.opts.controleUitleg || t('Controleer wat er verandert.');
  $('#fb-verg-voor-t').textContent = t('Zo is hij nu'); $('#fb-verg-na-t').textContent = t('Zo wordt hij');
  $('#fb-verg-voor').src = ksVerklein(foto, 900).toDataURL('image/jpeg', 0.85); $('#fb-verg-na').removeAttribute('src');
  $('#fb-verg-terug').hidden = false; uiWachtTekst($('#fb-verg-status'), t('Voorbeeld maken…')); $('#fb-ok').disabled = true; fbVergToon(true);
  let c = null; try { c = await fbRender(foto, fb.ops, 900, fb.cache); } catch (e) { c = null; }
  if (!fb.open || nr !== fb.render || fb.modus !== 'controle') return;
  if (!c) { fbVergTerug(); fbStatus(t('Dit vlak lukt niet. Zet de hoeken op de hoeken van wat je wilt houden.')); return; }
  $('#fb-verg-na').src = c.toDataURL('image/jpeg', 0.85); $('#fb-verg-status').textContent = ''; $('#fb-ok').disabled = false; $('#fb-ok').focus({ preventScroll: true });
}
function fbVergTerug(){ if (!fb.open || fb.modus !== 'controle') return; fb.modus = 'bewerk'; fb.render++; fbVergToon(false); fbTeken(); }
/** Twee foto's naast elkaar met één vraag (bijv. een eerdere versie terugzetten): Promise met true of null. */
function fbVergelijk(opts = {}){
  if (fb.open) fbSluit(null);
  return new Promise(klaar => {
    Object.assign(fb, { modus: 'los', open: true, foto: null, opts, klaar, ops: fbLeeg(), terug: [] });
    $('#fb-titel').textContent = opts.titel || t('Vergelijken'); $('#fb-ok').textContent = opts.knop || t('Klaar'); $('#fb-ok').disabled = false;
    $('#fb-verg-uitleg').textContent = opts.uitleg || '';
    $('#fb-verg-voor-t').textContent = opts.voorTekst || t('Zo is hij nu'); $('#fb-verg-na-t').textContent = opts.naTekst || t('Zo wordt hij');
    $('#fb-verg-voor').src = opts.voor; $('#fb-verg-na').src = opts.na; $('#fb-verg-status').textContent = ''; $('#fb-verg-terug').hidden = true;
    fbVergToon(true); $('#fbdlg').classList.add('on'); dialogFocus.sync($('#fb-ok'));
  });
}
/** De uitsnede verandert: zwarte vlakken liggen dan op iets anders. Weg, en zeggen (liever opnieuw dan op de verkeerde plek). */
function fbVlakkenWeg(){
  if (!fb.ops.vlakken.length) return;
  fb.ops.vlakken = []; fbStatus(t('De zwarte vlakken zijn weg, want de uitsnede veranderde. Zet ze opnieuw bij Afdekken.'));
}
function fbBewaarStap(){ fb.terug.push(fbKopie(fb.ops)); if (fb.terug.length > 40) fb.terug.shift(); fbKnoppen(); }
function fbOngedaan(){ if (!fb.terug.length) return; fb.ops = fb.terug.pop(); fb.hoek = 0; fbTeken(); fbStatus(t('Ongedaan gemaakt.')); }
function fbOrigineel(){ if (!fbIets(fb.ops)) return; fbBewaarStap(); fb.ops = fbLeeg(); fb.hoek = 0; fbTeken(); fbStatus(t('Weer de foto zoals hij was. Ongedaan maken zet je bewerking terug.')); }
async function fbAuto(start){
  if (!fb.open) return;
  const nr = ++fb.zoek, foto = fb.foto, voor = JSON.stringify(fb.ops); fbStatus(t('Verbetering zoeken…'), true); fbKnoppen(true);
  let v = null; try { v = await fbVoorstel(foto, fb.opts.soort, fb.opts.zijde); } catch (e) { v = null; }
  if (!fb.open || nr !== fb.zoek || fb.foto !== foto) return;
  if (JSON.stringify(fb.ops) !== voor) { fbKnoppen(); fbStatus(t('Voorstel niet toegepast: je paste intussen zelf iets aan.')); return; }
  let weg = false;
  if (v) {
    fbBewaarStap();
    if (v.redenen.includes('bijsnijden') || v.redenen.includes('draaien')) { weg = fb.ops.vlakken.length > 0; fb.ops = v.ops; fb.hoek = 0; }
    else { fb.ops.niveau = v.ops.niveau; fb.ops.licht = 0; fb.ops.contrast = 0; } // alleen de belichting: je uitsnede en vlakken blijven
  }
  fbTeken();
  fbStatus(v ? (weg ? t('Voorstel: {wat}. De zwarte vlakken zijn weg; zet ze opnieuw bij Afdekken.', { wat: fbRedenTekst(v.redenen) }) : t('Voorstel: {wat}. Controleer het en pas aan waar nodig.', { wat: fbRedenTekst(v.redenen) }))
    : start ? t('Geen verbetering gevonden. Pas zelf aan waar nodig.') : t('Geen verbetering gevonden.'));
}
function fbTab(tab){
  if (tab === 'vlak' && fb.opts.afdekken === false) tab = 'snij';
  if (fb.tab !== tab) fb.zoom = 1;
  fb.tab = tab;
  document.querySelectorAll('#fbdlg .pctabs [role=tab]').forEach(b => { const on = b.dataset.tab === tab; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
  document.querySelectorAll('#fbdlg .fb-paneel').forEach(p => p.hidden = p.id !== 'fb-p-' + tab);
  if (fb.open) { fbTeken(); fbStraks(); }
}
/** Het vlak in pixels van de gedraaide foto. */
function fbPix(q){ const g = fbGedraaid(fb.foto, fb.ops.draai, fb.cache); return q.map(([x, y]) => [x * g.width, y * g.height]); }
function fbKnoppen(bezig){
  if (!fb.open || fb.modus !== 'bewerk') return;
  $('#fb-ongedaan').disabled = !fb.terug.length; $('#fb-orig').disabled = !fbIets(fb.ops); $('#fb-auto').disabled = !!bezig;
  $('#fb-verh').value = String(fb.ops.verhouding ? FB_VERH().map(x => +x[0]).find(v => v && Math.abs(v - fb.ops.verhouding) < 0.001) || '0' : '0');
  $('#fb-hoek').value = fb.hoek; $('#fb-hoek-n').textContent = t('{n}°', { n: fmtNum(fb.hoek) });
  $('#fb-licht').value = fb.ops.licht; $('#fb-contrast').value = fb.ops.contrast;
  const g = $('#fb-grijs'); g.classList.toggle('on', !!fb.ops.grijs); g.setAttribute('aria-checked', fb.ops.grijs ? 'true' : 'false');
  $('#fb-vlak-weg').disabled = !fb.ops.vlakken.length; $('#fb-vlak-alles').disabled = !fb.ops.vlakken.length;
  $('#fb-ok').disabled = !fbGeldig(fb.ops.q, fbPix(fb.ops.q));
}
/** Tekenen (hooguit één keer per beeld). */
function fbTeken(){
  if (!fb.open || fb.modus !== 'bewerk') return;
  fbKnoppen(); if (fb.teken) return;
  fb.teken = requestAnimationFrame(async () => { fb.teken = 0; if (!fb.open || fb.modus !== 'bewerk') return; fbKnoppen(); if (fb.tab === 'vlak') await fbTekenUitkomst(); else fbTekenFoto(); fbStraks(); });
}
/** Onder de foto: zo wordt hij (bijgesneden, gedraaid, belicht). Op het tabblad Afdekken toont de foto zelf de uitkomst al. */
function fbStraks(){
  const el = $('#fb-straks'); clearTimeout(fb.straksT);
  if (fb.tab === 'vlak') { el.hidden = true; return; }
  el.hidden = false; const nr = fb.straksNr = (fb.straksNr || 0) + 1;
  fb.straksT = setTimeout(async () => {
    if (!fb.open || fb.modus !== 'bewerk' || fb.tab === 'vlak' || nr !== fb.straksNr) return;
    const foto = fb.foto, o = fbKopie(fb.ops);
    let c = null; try { if (fbGeldig(o.q, fbPix(o.q))) c = await fbRender(foto, o, 600, fb.cache); } catch (e) { c = null; }
    if (!fb.open || nr !== fb.straksNr || fb.foto !== foto) return;
    const d = $('#fb-straks-doek'); el.classList.toggle('leeg', !c);
    if (!c) { d.width = 1; d.height = 1; return; }
    d.width = c.width; d.height = c.height; d.getContext('2d').drawImage(c, 0, 0);
  }, 120);
}
function fbTekenFoto(){
  const g = fbGedraaid(fb.foto, fb.ops.draai, fb.cache), s = Math.min(1, fbToonMax() / Math.max(g.width, g.height));
  const c = $('#fb-doek'), w = Math.max(1, Math.round(g.width * s)), h = Math.max(1, Math.round(g.height * s));
  if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
  const x = c.getContext('2d'); x.drawImage(g, 0, 0, w, h);
  if (!fbKleurLeeg(fb.ops)) x.putImageData(fbKleur(x.getImageData(0, 0, w, h), fb.ops), 0, 0);
  fbZoomPas(); fbTekenVlak();
}
const FB_NAMEN = () => [t('Hoek linksboven'), t('Hoek rechtsboven'), t('Hoek rechtsonder'), t('Hoek linksonder'), t('Bovenkant'), t('Rechterkant'), t('Onderkant'), t('Linkerkant')];
const FB_KANT = [[0, 1], [1, 2], [2, 3], [3, 0]];
function fbGrepen(){ const q = fb.ops.q; return q.concat(FB_KANT.map(([a, b]) => [(q[a][0] + q[b][0]) / 2, (q[a][1] + q[b][1]) / 2])); }
function fbTekenVlak(){
  const c = $('#fb-doek'), svg = $('#fb-vlak'), W = c.width, H = c.height;
  svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H); svg.setAttribute('aria-label', t('Wat je houdt'));
  const breed = svg.getBoundingClientRect().width || 340, s = W / breed, P = fb.ops.q.map(([x, y]) => [x * W, y * H]), pts = P.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ');
  let html = '<path class="fb-buiten" fill-rule="evenodd" d="M0 0H' + W + 'V' + H + 'H0Z M' + P.map(p => p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' L') + 'Z"/>';
  html += '<polygon points="' + pts + '" class="ks-poly" stroke-width="' + (2 * s).toFixed(2) + '"/>';
  fbGrepen().forEach((p, i) => {
    const x = p[0] * W, y = p[1] * H, kant = i >= 4;
    html += '<g class="' + ['ks-hoek', 'fb-greep', kant ? 'kant' : ''].join(' ').trim() + '" data-i="' + i + '" tabindex="0" role="slider" aria-label="' + esc(FB_NAMEN()[i]) + '" aria-valuetext="' + esc(Math.round(p[0] * 100) + '%, ' + Math.round(p[1] * 100) + '%') + '">'
      + '<circle cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="' + (24 * s).toFixed(1) + '" class="ks-raak"/>'
      + '<circle cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="' + ((kant ? 7 : 11) * s).toFixed(1) + '" class="ks-stip" stroke-width="' + (2.5 * s).toFixed(2) + '"/></g>';
  });
  svg.innerHTML = html;
}
async function fbTekenUitkomst(){
  const nr = ++fb.render, o = fbKopie(fb.ops), vl = o.vlakken; o.vlakken = [];
  let c; try { c = await fbRender(fb.foto, o, fbToonMax(), fb.cache); } catch (e) { fbStatus(t('Dit vlak lukt niet. Zet de hoeken op de hoeken van wat je wilt houden.')); return; }
  if (!fb.open || nr !== fb.render) return;
  fb.uit = c; const d = $('#fb-doek'); d.width = c.width; d.height = c.height; d.getContext('2d').drawImage(c, 0, 0);
  fbZoomPas(); fbTekenVlakken(vl);
}
function fbTekenVlakken(vl, extra){
  const d = $('#fb-doek'), svg = $('#fb-vlak'), W = d.width, H = d.height;
  svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H); svg.setAttribute('aria-label', t('Zwarte vlakken'));
  svg.innerHTML = vl.concat(extra ? [extra] : []).map(([x, y, w, h]) => '<rect x="' + (x * W).toFixed(1) + '" y="' + (y * H).toFixed(1) + '" width="' + (w * W).toFixed(1) + '" height="' + (h * H).toFixed(1) + '" class="fb-zwart"/>').join('');
}
/** Het vlak binnen de foto houden: naar het midden toe kleiner tot alle hoeken erin vallen. */
function fbBinnen(q){
  const c = [q.reduce((s, p) => s + p[0], 0) / 4, q.reduce((s, p) => s + p[1], 0) / 4]; let s = 1;
  for (const p of q) for (const k of [0, 1]) { const d = p[k] - c[k]; if (d > 0 && c[k] + d > 1) s = Math.min(s, (1 - c[k]) / d); if (d < 0 && c[k] + d < 0) s = Math.min(s, c[k] / -d); }
  return q.map(p => [c[0] + (p[0] - c[0]) * s, c[1] + (p[1] - c[1]) * s]);
}
/** Rechtzetten: het vlak draait om zijn midden (in pixels), de foto draait dus de andere kant op. */
function fbHoek(v){
  v = Math.max(-45, Math.min(45, +v || 0));
  if (!fb.hoekBasis) { fbBewaarStap(); fb.hoekBasis = { q: fbKopie(fb.ops.q), v: fb.hoek }; }
  const g = fbGedraaid(fb.foto, fb.ops.draai, fb.cache), W = g.width, H = g.height, a = -(v - fb.hoekBasis.v) * Math.PI / 180, cos = Math.cos(a), sin = Math.sin(a);
  const P = fb.hoekBasis.q.map(([x, y]) => [x * W, y * H]), cx = P.reduce((s, p) => s + p[0], 0) / 4, cy = P.reduce((s, p) => s + p[1], 0) / 4;
  fb.ops.q = fbBinnen(P.map(([x, y]) => [(cx + (x - cx) * cos - (y - cy) * sin) / W, (cy + (x - cx) * sin + (y - cy) * cos) / H]));
  fbVlakkenWeg(); fb.hoek = v; fbTeken();
}
function fbHoekKlaar(){ fb.hoekBasis = null; }
function fbDraai(stap){ fbBewaarStap(); fbDraaiOps(fb.ops, stap); fb.hoek = 0; fbTeken(); }
function fbVerhouding(v){
  fbBewaarStap(); v = +v || 0; fb.ops.verhouding = v; fbVlakkenWeg();
  // Nog de hele foto: dan meteen het grootste vlak met die verhouding, in het midden
  const heel = fb.ops.q.every((p, i) => Math.abs(p[0] - [0, 1, 1, 0][i]) < 0.005 && Math.abs(p[1] - [0, 0, 1, 1][i]) < 0.005);
  if (v && heel) {
    const g = fbGedraaid(fb.foto, fb.ops.draai, fb.cache), W = g.width, H = g.height; let w = W, h = W / v; if (h > H) { h = H; w = H * v; }
    const x0 = (W - w) / 2 / W, y0 = (H - h) / 2 / H, x1 = 1 - x0, y1 = 1 - y0; fb.ops.q = [[x0, y0], [x1, y0], [x1, y1], [x0, y1]];
  }
  fbTeken();
}
function fbHeel(){ fbBewaarStap(); fbVlakkenWeg(); fb.ops.q = fbLeeg().q; fb.hoek = 0; fbTeken(); }
async function fbZoekDocument(){
  const nr = ++fb.zoek, foto = fb.foto; fbStatus(t('Document zoeken…'), true);
  const g = fbGedraaid(foto, fb.ops.draai, fb.cache), k = ksVerklein(g, 360), px = k.getContext('2d').getImageData(0, 0, k.width, k.height).data;
  await new Promise(r => setTimeout(r, 0));
  const q = fbDocHoeken(px, k.width, k.height) || (typeof ksHoekenUit === 'function' ? ksHoekenUit(px, k.width, k.height) : null);
  if (!fb.open || nr !== fb.zoek || fb.foto !== foto) return;
  if (!q) { fbStatus(t('Geen document gevonden. Sleep de hoeken zelf.')); return; }
  fbBewaarStap(); const weg = fb.ops.vlakken.length; fb.ops.vlakken = []; fb.ops.q = q.map(([x, y]) => [Math.min(1, (x + 0.5) / k.width), Math.min(1, (y + 0.5) / k.height)]); fb.hoek = 0; fbTeken();
  fbStatus(weg ? t('Document gevonden. De zwarte vlakken zijn weg; zet ze opnieuw bij Afdekken.') : t('Document gevonden. Klopt het? Sleep anders de hoeken.'));
}
function fbLicht(veld, v){ if (!fb.lichtStap) { fbBewaarStap(); fb.lichtStap = true; } fb.ops[veld] = Math.max(-100, Math.min(100, Math.round(+v || 0))); fbTeken(); }
function fbLichtKlaar(){ fb.lichtStap = false; }
function fbGrijs(){ fbBewaarStap(); fb.ops.grijs = !fb.ops.grijs; fbTeken(); }
async function fbAutoLicht(){
  const nr = ++fb.zoek, o = fbKopie(fb.ops); o.niveau = [0, 255]; o.licht = 0; o.contrast = 0; o.vlakken = [];
  const n = fbNiveau(await fbRender(ksVerklein(fb.foto, 360), o, 300));
  if (!fb.open || nr !== fb.zoek) return;
  fbBewaarStap(); fb.ops.niveau = n[1] - n[0] >= 30 ? n : [0, 255]; fb.ops.licht = 0; fb.ops.contrast = 0; fbTeken();
  fbStatus(t('Belichting aangepast: het donkerste is zwart, het lichtste wit.'));
}
function fbVlakWeg(alles){ if (!fb.ops.vlakken.length) return; fbBewaarStap(); if (alles) fb.ops.vlakken = []; else fb.ops.vlakken.pop(); fbTeken(); }

/* Slepen: grepen van het vlak, of zwarte vlakken trekken */
/* ---------- vergroten: knoppen, knijpen met twee vingers, schuiven ---------- */
const FB_ZOOM_MAX = 6;
/** Vergroot tekent de bewerker de foto scherper. */
function fbToonMax(){ return (fb.zoom || 1) > 1.2 ? FB_MAX_TOON * 2 : FB_MAX_TOON; }
/** De maat van de foto op het scherm bij de zoom van nu; zonder zoom regelt de opmaak het zelf. */
function fbZoomPas(){
  const k = $('#fb-kijk'), c = $('#fb-doek'), z = fb.open ? fb.zoom || 1 : 1, aan = z > 1.001;
  $('#fb-zoom-n').textContent = t('{n}×', { n: fmtNum(Math.round(z * 10) / 10) });
  $('#fb-zoom-min').disabled = !aan; $('#fb-zoom-n').disabled = !aan; $('#fb-zoom-plus').disabled = z >= FB_ZOOM_MAX - 0.001;
  if (!aan) { if (k.classList.contains('zoom')) { k.classList.remove('zoom'); k.style.height = ''; c.style.width = ''; k.scrollLeft = 0; k.scrollTop = 0; } return; }
  // de maat zonder zoom: zo breed als past, hooguit 40% van de hoogte van het scherm
  const breed = (k.parentElement.clientWidth || 340) - 32, hoog = innerHeight * 0.4, n = Math.min(1, FB_MAX_TOON / Math.max(c.width, c.height));
  const f = Math.min(1, breed / (c.width * n), hoog / (c.height * n)), bw = c.width * n * f, bh = c.height * n * f;
  k.classList.add('zoom'); k.style.height = Math.round(bh + 28) + 'px'; c.style.width = Math.round(bw * z) + 'px';
}
/** Zoom zetten; het punt (x, y) op het scherm blijft op zijn plek (anders het midden). */
function fbZoomZet(z, x, y){
  if (!fb.open || fb.modus !== 'bewerk') return;
  z = Math.max(1, Math.min(FB_ZOOM_MAX, +z || 1)); if (Math.abs(z - fb.zoom) < 0.001) return;
  const k = $('#fb-kijk'), svg = $('#fb-vlak'), kr = k.getBoundingClientRect(), r = svg.getBoundingClientRect();
  if (x == null) { x = kr.left + kr.width / 2; y = kr.top + kr.height / 2; }
  const fx = (x - r.left) / (r.width || 1), fy = (y - r.top) / (r.height || 1), was = fb.zoom;
  fb.zoom = z; fbZoomPas();
  const r2 = svg.getBoundingClientRect(); k.scrollLeft += r2.left + fx * r2.width - x; k.scrollTop += r2.top + fy * r2.height - y;
  if (was <= 1.001 && z > 1) fbStatus(t('Vergroot. Schuif met twee vingers, of met één vinger naast de hoeken.'));
  fbTeken();
}
function fbZoomStap(stap){ fbZoomZet(stap > 0 ? Math.min(FB_ZOOM_MAX, fb.zoom * 1.5) : fb.zoom / 1.5 < 1.1 ? 1 : fb.zoom / 1.5); }
function fbVang(svg, e){ try { svg.setPointerCapture(e.pointerId); } catch (x) {} }
function fbKnijpStand(){ const [a, b] = [...fb.vingers.values()]; return { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }; }
function fbPunt(e){ const r = $('#fb-vlak').getBoundingClientRect(); return [Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), Math.max(0, Math.min(1, (e.clientY - r.top) / r.height))]; }
function fbZet(i, p){
  const q = fbKopie(fb.ops.q);
  if (i < 4) q[i] = p;
  else { const [a, b] = FB_KANT[i - 4], m = [(q[a][0] + q[b][0]) / 2, (q[a][1] + q[b][1]) / 2], dx = p[0] - m[0], dy = p[1] - m[1]; for (const k of [a, b]) q[k] = [Math.max(0, Math.min(1, q[k][0] + dx)), Math.max(0, Math.min(1, q[k][1] + dy))]; }
  if (!fbGeldig(q, fbPix(q))) return false; // hoeken over elkaar of te klein: niet toegestaan
  fbVlakkenWeg();
  fb.ops.q = q; fbTeken(); return true;
}
function fbEvents(){
  const svg = $('#fb-vlak'); if (svg.dataset.fb) return; svg.dataset.fb = '1';
  svg.addEventListener('pointerdown', e => {
    if (!fb.open) return; if (fb.opts.actief) fb.opts.actief();
    fb.vingers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (fb.vingers.size >= 2) { // tweede vinger: knijpen en schuiven, wat de eerste vinger deed stopt
      if (fb.sleep >= 0 && fb.terug.length) fb.ops = fb.terug.pop();
      fb.sleep = -1; fb.vlak = null; fb.schuif = null; fbVang(svg, e); e.preventDefault();
      if (fb.vingers.size === 2) { const st = fbKnijpStand(); fb.knijp = { d: st.d, x: st.x, y: st.y, zoom: fb.zoom }; fbTeken(); }
      return;
    }
    if (fb.knijp) return;
    if (fb.tab === 'vlak') { fb.vlak = { van: fbPunt(e) }; fbVang(svg, e); e.preventDefault(); return; }
    const g = e.target.closest('.fb-greep'); let i = g ? +g.dataset.i : -1;
    if (i < 0) { const p = fbPunt(e); let best = Infinity; fbGrepen().forEach((q, k) => { const d = Math.hypot(q[0] - p[0], q[1] - p[1]); if (d < best) { best = d; i = k; } }); if (best > 0.18 / fb.zoom) i = -1; }
    if (i < 0) { // naast de hoeken: vergroot schuif je de foto
      if (fb.zoom > 1.001) { const k = $('#fb-kijk'); fb.schuif = { x: e.clientX, y: e.clientY, l: k.scrollLeft, t: k.scrollTop }; fbVang(svg, e); e.preventDefault(); }
      return;
    }
    fbBewaarStap(); fb.sleep = i; fbVang(svg, e); e.preventDefault();
  });
  svg.addEventListener('pointermove', e => {
    if (fb.vingers.has(e.pointerId)) fb.vingers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (fb.knijp) {
      if (fb.vingers.size !== 2) return;
      const st = fbKnijpStand(), k = $('#fb-kijk');
      k.scrollLeft -= st.x - fb.knijp.x; k.scrollTop -= st.y - fb.knijp.y; fb.knijp.x = st.x; fb.knijp.y = st.y;
      fbZoomZet(fb.knijp.zoom * st.d / fb.knijp.d, st.x, st.y); return;
    }
    if (fb.schuif) { const k = $('#fb-kijk'); k.scrollLeft = fb.schuif.l - (e.clientX - fb.schuif.x); k.scrollTop = fb.schuif.t - (e.clientY - fb.schuif.y); return; }
    if (fb.vlak) { const a = fb.vlak.van, b = fbPunt(e); fb.vlak.r = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])]; fbTekenVlakken(fb.ops.vlakken, fb.vlak.r); return; }
    if (fb.sleep >= 0) fbZet(fb.sleep, fbPunt(e));
  });
  const los = e => {
    fb.vingers.delete(e.pointerId);
    if (fb.knijp) { if (!fb.vingers.size) fb.knijp = null; return; }
    if (fb.schuif) { fb.schuif = null; return; }
    if (fb.vlak) { const r = fb.vlak.r; fb.vlak = null; if (r && r[2] > 0.005 && r[3] > 0.005 && fb.ops.vlakken.length < 60) { fbBewaarStap(); fb.ops.vlakken.push(r); } fbTeken(); return; }
    if (fb.sleep >= 0) { const i = fb.sleep; fb.sleep = -1; fbTeken(); requestAnimationFrame(() => { const g = svg.querySelector('.fb-greep[data-i="' + i + '"]'); if (g) g.focus({ preventScroll: true }); }); }
  };
  svg.addEventListener('pointerup', los); svg.addEventListener('pointercancel', los);
  svg.addEventListener('keydown', e => {
    const g = e.target.closest('.fb-greep'); if (!g || !fb.open) return;
    const st = (e.shiftKey ? 5 : 1) / 100, d = { ArrowLeft: [-st, 0], ArrowRight: [st, 0], ArrowUp: [0, -st], ArrowDown: [0, st] }[e.key];
    if (!d) return; e.preventDefault(); const i = +g.dataset.i, p = fbGrepen()[i];
    fbBewaarStap(); fbZet(i, [Math.max(0, Math.min(1, p[0] + d[0])), Math.max(0, Math.min(1, p[1] + d[1]))]);
    requestAnimationFrame(() => { const n = svg.querySelector('.fb-greep[data-i="' + i + '"]'); if (n) n.focus({ preventScroll: true }); });
  });
  document.querySelector('#fbdlg .pctabs').addEventListener('keydown', e => {
    const tabs = [...document.querySelectorAll('#fbdlg .pctabs [role=tab]')].filter(b => !b.hidden), i = tabs.indexOf(document.activeElement);
    if (i < 0 || !['ArrowLeft', 'ArrowRight'].includes(e.key)) return; e.preventDefault();
    const n = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length]; fbTab(n.dataset.tab); n.focus();
  });
}
fbEvents();
