/* ---------- Toegankelijkheid ---------- */
function stZoom(v){ Android.textZoomSet(+v); toast('Tekstgrootte: ' + $('#st-zoom').selectedOptions[0].textContent); setTimeout(() => { if (typeof tabsFit === 'function') tabsFit(); }, 60); }
/* Schakelaars en keuzeknoppen: TalkBack hoort aan/uit (de klasse "on" bepaalt de stand). */
function a11yMark(el){
  if (el.classList.contains('tg')) {
    el.setAttribute('role', 'switch');
    el.setAttribute('aria-checked', el.classList.contains('on') ? 'true' : 'false');
    if (!el.getAttribute('aria-label') || el.dataset.a11yAuto) { const sp = el.parentElement && el.parentElement.querySelector('span'); if (sp && sp.firstChild) { const l = sp.firstChild.textContent.trim(); if (el.getAttribute('aria-label') !== l) el.setAttribute('aria-label', l); el.dataset.a11yAuto = '1'; } }
  } else if (el.parentElement && el.parentElement.classList.contains('chips') && el.tagName === 'BUTTON') {
    const role = el.getAttribute('role');
    if (!role || role === 'button') el.setAttribute('aria-pressed', el.classList.contains('on') ? 'true' : 'false');
    else el.removeAttribute('aria-pressed');
  }
}
function a11yScan(root){ (root || document).querySelectorAll('.tg, .chips > button').forEach(a11yMark); }
new MutationObserver(ms => { for (const m of ms) {
  if (m.type === 'attributes') a11yMark(m.target);
  else m.addedNodes.forEach(n => { if (n.nodeType === 1) { a11yMark(n); a11yScan(n); } });
} }).observe(document.body, { attributes: true, attributeFilter: ['class'], childList: true, subtree: true });
a11yScan();
/* Andere taal: de namen van schakelaars opnieuw uit de (nu vertaalde) tekst ernaast halen */
addEventListener('taalchange', () => a11yScan());

