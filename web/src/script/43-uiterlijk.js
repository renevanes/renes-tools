/* ---------- Uiterlijk: kleuren uit je achtergrond (Material You), meeschuivende kop, tabbalk ---------- */
function hexMix(a, b, t){ // t = aandeel van b
  const x = parseInt(a.slice(1), 16), y = parseInt(b.slice(1), 16);
  return '#' + [16, 8, 0].map(s => { const p = (x >> s) & 255, q = (y >> s) & 255; return Math.round(p + (q - p) * t).toString(16).padStart(2, '0'); }).join('');
}
/* Android geeft per kleur tinten van 0 (wit) tot 1000 (zwart); hier de rollen van Material 3 */
function dynTokens(p){
  const a1 = p.accent1, a2 = p.accent2, n1 = p.neutral1, n2 = p.neutral2, m = hexMix;
  return {
    light: { bg: m(n1[50], n1[100], .25), card: n1[10], hi: m(n1[50], n1[100], .65), ink: n1[900], muted: n2[700], line: m(n2[100], n2[200], .5), outline: n2[500],
      primary: a1[600], 'on-primary': a1[0], tonal: a1[100], 'on-tonal': a1[900], chip: a2[100], 'on-chip': a2[900], inv: n1[800], 'inv-ink': n1[50] },
    dark: { bg: m(n1[900], n1[1000], .4), card: m(n1[900], n1[800], .45), hi: n1[800], ink: n1[100], muted: n2[200], line: m(n2[800], n2[700], .4), outline: n2[400],
      primary: a1[200], 'on-primary': a1[800], tonal: a1[700], 'on-tonal': a1[100], chip: a2[700], 'on-chip': a2[100], inv: n1[100], 'inv-ink': n1[800] }
  };
}
function dynPalette(){
  if (!store.get('dynColor', false) || typeof Android.themeColors !== 'function') return null;
  let p; try { const j = Android.themeColors(); if (!j) return null; p = JSON.parse(j); } catch(e){ return null; }
  const ok = h => typeof h === 'string' && /^#[0-9a-f]{6}$/i.test(h);
  for (const k of ['accent1', 'accent2', 'neutral1', 'neutral2'])
    for (const t of [0, 10, 50, 100, 200, 400, 500, 600, 700, 800, 900, 1000]) if (!p[k] || !ok(p[k][t])) return null;
  return p;
}
function applyTheme(){
  const p = dynPalette();
  const decl = o => Object.entries(o).map(([k, v]) => '--' + k + ':' + v).join(';');
  let css = '';
  if (p) { const t = dynTokens(p); css = ':root{' + decl(t.light) + '}@media (prefers-color-scheme: dark){:root{' + decl(t.dark) + '}}'; }
  let st = document.getElementById('dyn-theme');
  if (!st) { st = document.createElement('style'); st.id = 'dyn-theme'; document.head.appendChild(st); }
  if (st.textContent !== css) { st.textContent = css; bars.last = null; }
  bars();
}
function stDynColor(on){ store.set('dynColor', !!on); applyTheme(); toast(on ? 'Kleuren uit je achtergrond' : 'Standaardkleuren'); }

/* Koppen: grote titel die bij scrollen omhoog schuift; de bovenste rij (terug, titel klein, knoppen) blijft staan */
function uiInitHeaders(){
  document.querySelectorAll('.hdr').forEach(h => {
    if (h.querySelector('.hrow')) return;
    const h1 = h.querySelector('h1'), row = document.createElement('div'); row.className = 'hrow';
    [...h.children].forEach(c => { if (c !== h1) row.appendChild(c); });
    const back = row.querySelector('.back'), at = back ? back.nextSibling : row.firstChild;
    if (h.classList.contains('sm')) row.insertBefore(h1, at);
    else { const hs = document.createElement('span'); hs.className = 'hs'; hs.setAttribute('aria-hidden', 'true'); row.insertBefore(hs, at); }
    h.insertBefore(row, h.firstChild);
  });
}
function uiHdr(){ return document.querySelector('.screen.on > .hdr'); }
function uiFit(){ uiScroll(); }
function uiScroll(){
  const h = uiHdr(); if (!h) return;
  const h1 = h.classList.contains('sm') ? null : h.querySelector(':scope > h1');
  const c = h1 ? scrollY > h1.offsetHeight - 16 : scrollY > 4;
  if (c && h1) { const hs = h.querySelector('.hs'); if (hs && hs.textContent !== h1.textContent) hs.textContent = h1.textContent; }
  if (h.classList.contains('compact') !== c) { h.classList.toggle('compact', c); bars(); }
}
addEventListener('scroll', uiScroll, { passive: true });
addEventListener('resize', () => uiFit());
if (typeof ResizeObserver === 'function') { const ro = new ResizeObserver(() => uiFit()); document.querySelectorAll('.hdr > h1').forEach(h => ro.observe(h)); }

/* Tabbalk onderin op de hoofdschermen */
const TABS = ['home', 'search', 'backup', 'settings'];
function uiShown(name){
  const on = !SOLO && TABS.includes(name);
  // Via een sprong (bijv. zelftest → toestemmingen) blijft de terugknop zichtbaar
  const viaJump = jumpTo.busy || (jumpStack.length && jumpStack[jumpStack.length - 1].to === name);
  document.body.classList.toggle('tabs-on', on);
  document.body.classList.toggle('tab-root', on && !viaJump);
  document.querySelectorAll('#tabs button').forEach(b => { const me = b.dataset.t === name; b.classList.toggle('on', me); if (me) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current'); });
  uiFit(); bars();
}
function tabGo(t){
  if (t === current) { scrollTo({ top: 0, behavior: 'smooth' }); if (t === 'search') $('#gs-q').focus(); return; }
  jumpStack.length = 0;
  show(t);
  if (t === 'search' && !$('#gs-q').value) setTimeout(() => $('#gs-q').focus(), 60);
}

uiInitHeaders();
applyTheme();
uiShown(current);
