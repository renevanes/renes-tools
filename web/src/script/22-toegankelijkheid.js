/* ---------- Toegankelijkheid ---------- */
function stZoom(v){ Android.textZoomSet(+v); toast('Tekstgrootte: ' + $('#st-zoom').selectedOptions[0].textContent); }
/* Schakelaars en keuzeknoppen: TalkBack hoort aan/uit (de klasse "on" bepaalt de stand). */
function a11yMark(el){
  if (el.classList.contains('tg')) {
    el.setAttribute('role', 'switch');
    el.setAttribute('aria-checked', el.classList.contains('on') ? 'true' : 'false');
    if (!el.getAttribute('aria-label')) { const sp = el.parentElement && el.parentElement.querySelector('span'); if (sp && sp.firstChild) el.setAttribute('aria-label', sp.firstChild.textContent.trim()); }
  } else if (el.parentElement && el.parentElement.classList.contains('chips') && el.tagName === 'BUTTON') {
    el.setAttribute('aria-pressed', el.classList.contains('on') ? 'true' : 'false');
  }
}
function a11yScan(root){ (root || document).querySelectorAll('.tg, .chips > button').forEach(a11yMark); }
new MutationObserver(ms => { for (const m of ms) {
  if (m.type === 'attributes') a11yMark(m.target);
  else m.addedNodes.forEach(n => { if (n.nodeType === 1) { a11yMark(n); a11yScan(n); } });
} }).observe(document.body, { attributes: true, attributeFilter: ['class'], childList: true, subtree: true });
a11yScan();

