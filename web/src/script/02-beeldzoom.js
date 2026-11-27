/* Beeld vergroten: één vaste set knoppen en gebaren voor elke plek die een foto toont (wens van Rene, 27-11-2026).
 *
 *   const bz = beeldZoom(kijk, { … })   // kijk = het kader; het eerste kind is wat groter wordt (een img, canvas of een div eromheen)
 *
 * Wat je krijgt: de balk − / 1× / + (met eigen knoppen erbij, bijvoorbeeld Bewerken), knijpen met twee vingers en,
 * vergroot, schuiven met één vinger. Zonder zoom regelt de opmaak de maat; vergroot houdt het kader zijn hoogte en schuift
 * de inhoud erin. Keuzes (alle optioneel):
 *   inhoud     het element dat groter wordt (anders het eerste kind)
 *   balk       een eigen element voor de knoppen; false = geen balk (bijvoorbeeld een tweede foto die meeloopt)
 *   id         begin van de id's van de knoppen (<id>-min, <id>-n, <id>-plus)
 *   knoppen    extra knoppen in de balk: [{ id, tekst, icoon, actie }]
 *   eenVinger  false (of een functie die false geeft) = één vinger is van de gastheer (hoeken slepen, vlakken tekenen); die roept zelf bz.schuif(e) aan
 *   basis      () => ({ w, h }): de maat zonder zoom, als die niet te meten is (de bewerker: het doek wisselt van maat)
 *   rand       ruimte om de inhoud als hij vergroot is (px), gelijk aan de padding van .zoom in de opmaak
 *   aan        () => boolean: nu gebaren aannemen?
 *   gebaar     () => …: een tweede vinger begint te knijpen; de gastheer breekt af wat de eerste vinger deed
 *   naZoom     (zoom, was) => …: na elke verandering
 * Terug: { zoom, zet(z, x, y), stap(±1), leeg(), pas(), herzien(), bezig(), schuif(e), balk }. */
const BZ_MAX = 6;
function beeldZoom(kijk, o = {}){
  const inhoud = o.inhoud || kijk.firstElementChild, max = o.max || BZ_MAX, rand = o.rand || 0;
  const st = { z: 1, basis: null, vingers: new Map(), knijp: null, schuif: null };
  kijk.classList.add('bz-kijk'); inhoud.classList.add('bz-inhoud');
  const balk = o.balk === false ? null : (o.balk || document.createElement('div')), kn = {};
  const knop = (naam, klasse, tekst, label, actie, id) => {
    const b = document.createElement('button'); b.type = 'button'; b.className = klasse; b.textContent = tekst; if (label) b.setAttribute('aria-label', label);
    if (id) b.id = id; b.onclick = actie; balk.append(b); kn[naam] = b; return b;
  };
  if (balk) {
    balk.classList.add('bz-balk'); balk.setAttribute('role', 'group'); balk.setAttribute('aria-label', t('Vergroten'));
    const id = s => o.id ? [o.id, s].join('-') : '';
    knop('min', 'icon-btn', '−', t('Uitzoomen'), () => api.stap(-1), id('min'));
    knop('n', 'mini', '', t('Zoom terugzetten'), () => api.zet(1), id('n')).setAttribute('translate', 'no');
    knop('plus', 'icon-btn', '+', t('Inzoomen'), () => api.stap(1), id('plus'));
    for (const k of o.knoppen || []) { const b = knop(k.id || k.tekst, 'mini', k.tekst, '', k.actie, k.id); if (k.icoon) b.dataset.icon = k.icoon; }
    if (!o.balk) kijk.after(balk);
  }
  const vang = e => { try { kijk.setPointerCapture(e.pointerId); } catch (x) {} };
  const toon = () => {
    if (!balk) return; const aan = st.z > 1.001, vol = st.z >= max - 0.001, act = document.activeElement;
    kn.n.textContent = t('{n}×', { n: fmtNum(Math.round(st.z * 10) / 10) });
    // een knop die uitgaat terwijl hij de focus heeft: de focus naar de andere, anders raakt hij zoek
    if (!aan && (act === kn.min || act === kn.n)) kn.plus.focus({ preventScroll: true }); else if (vol && act === kn.plus) kn.min.focus({ preventScroll: true });
    kn.min.disabled = !aan; kn.n.disabled = !aan; kn.plus.disabled = vol;
  };
  const los = () => { kijk.classList.remove('zoom'); kijk.style.height = ''; inhoud.style.width = ''; };
  const meet = () => { const r = inhoud.getBoundingClientRect(); return { w: r.width, h: r.height }; };
  const api = {
    get zoom(){ return st.z; }, balk,
    bezig: () => !!st.knijp,
    /** De maat bij de zoom van nu toepassen (ook na een ander beeld in hetzelfde kader). */
    pas(){
      if (st.z <= 1.001) { if (kijk.classList.contains('zoom')) { los(); kijk.scrollLeft = 0; kijk.scrollTop = 0; } st.basis = null; toon(); return; }
      const b = o.basis ? o.basis() : st.basis || (st.basis = meet());
      if (!b || !(b.w > 0) || !(b.h > 0)) { st.z = 1; los(); toon(); return; }
      kijk.classList.add('zoom'); kijk.style.height = Math.round(b.h + 2 * rand) + 'px'; inhoud.style.width = Math.round(b.w * st.z) + 'px'; toon();
    },
    /** De inhoud kreeg een andere vorm (gedraaid, ander beeld): de maat zonder zoom opnieuw meten. */
    herzien(){ if (st.z > 1.001 && !o.basis) { los(); st.basis = null; } api.pas(); },
    /** Zoom zetten; het punt (x, y) op het scherm blijft op zijn plek (anders het midden van het kader). */
    zet(z, x, y){
      z = Math.max(1, Math.min(max, +z || 1)); if (Math.abs(z - st.z) < 0.001) return;
      const kr = kijk.getBoundingClientRect(), r = inhoud.getBoundingClientRect();
      if (x == null) { x = kr.left + kr.width / 2; y = kr.top + kr.height / 2; }
      const fx = (x - r.left) / (r.width || 1), fy = (y - r.top) / (r.height || 1), was = st.z;
      st.z = z; api.pas();
      const r2 = inhoud.getBoundingClientRect(); kijk.scrollLeft += r2.left + fx * r2.width - x; kijk.scrollTop += r2.top + fy * r2.height - y;
      if (o.naZoom) o.naZoom(st.z, was);
    },
    stap(d){ api.zet(d > 0 ? Math.min(max, st.z * 1.5) : st.z / 1.5 < 1.1 ? 1 : st.z / 1.5); },
    /** Terug naar 1×, zonder melding (sluiten, een ander beeld). */
    leeg(){ st.z = 1; st.knijp = null; st.schuif = null; st.vingers.clear(); api.pas(); },
    /** Met deze vinger schuiven (de gastheer besliste dat hij niets anders doet). */
    schuif(e){ if (st.z <= 1.001) return false; st.schuif = { x: e.clientX, y: e.clientY, l: kijk.scrollLeft, t: kijk.scrollTop }; vang(e); e.preventDefault(); return true; },
  };
  const stand = () => { const [a, b] = [...st.vingers.values()]; return { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, paar: [...st.vingers.keys()].join() }; };
  // Luisteren in de vangfase: zo ziet dit onderdeel een tweede vinger vóór de gastheer en houdt het die bij zich
  kijk.addEventListener('pointerdown', e => {
    if (o.aan && !o.aan()) return;
    if (e.isPrimary) { st.vingers.clear(); st.knijp = null; st.schuif = null; } // wat bleef hangen (buiten het kader losgelaten) telt niet mee
    st.vingers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (st.vingers.size >= 2) {
      if (st.vingers.size === 2) { st.schuif = null; st.knijp = Object.assign(stand(), { zoom: st.z }); if (o.gebaar) o.gebaar(); }
      vang(e); e.preventDefault(); e.stopPropagation(); return;
    }
    if (st.knijp) { e.stopPropagation(); return; }
    if ((typeof o.eenVinger === 'function' ? o.eenVinger() : o.eenVinger) !== false && api.schuif(e)) e.stopPropagation();
  }, true);
  kijk.addEventListener('pointermove', e => {
    if (st.vingers.has(e.pointerId)) st.vingers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (st.knijp) {
      e.stopPropagation(); if (st.vingers.size !== 2) { st.knijp.paar = ''; return; }
      const nu = stand(); if (st.knijp.paar !== nu.paar) st.knijp = Object.assign(nu, { zoom: st.z }); // een ander paar vingers: opnieuw meten
      kijk.scrollLeft -= nu.x - st.knijp.x; kijk.scrollTop -= nu.y - st.knijp.y; st.knijp.x = nu.x; st.knijp.y = nu.y;
      api.zet(st.knijp.zoom * nu.d / st.knijp.d, nu.x, nu.y); return;
    }
    if (st.schuif) { e.stopPropagation(); kijk.scrollLeft = st.schuif.l - (e.clientX - st.schuif.x); kijk.scrollTop = st.schuif.t - (e.clientY - st.schuif.y); }
  }, true);
  const op = e => {
    st.vingers.delete(e.pointerId);
    if (st.knijp) { if (!st.vingers.size) st.knijp = null; e.stopPropagation(); return; }
    if (st.schuif) { st.schuif = null; e.stopPropagation(); }
  };
  for (const soort of ['pointerup', 'pointercancel', 'lostpointercapture']) kijk.addEventListener(soort, op, true);
  toon();
  return api;
}
