/* Focus en achtergrondbediening voor alle algemene vensters, met het app-slot als hoogste laag. */
const dialogFocus = (() => {
  const selector = '#modal, #sheet, #voice, #ci-ans, #sleepdlg, #lockscr';
  const opened = new Map(), background = new Map();
  let serial = 0, top = null;
  const controls = root => [...root.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]')]
    .filter(e => e.getClientRects().length && !e.closest('[inert]'));
  function sync(preferred){
    for (const root of document.querySelectorAll(selector)) {
      if (root.classList.contains('on')) {
        if (!opened.has(root)) opened.set(root, { order: ++serial, previous: document.activeElement });
      } else opened.delete(root);
    }
    const roots = [...opened.keys()];
    const next = roots.sort((a, b) => {
      const z = el => el.id === 'lockscr' ? 100000 : (+getComputedStyle(el).zIndex || 0);
      return z(a) - z(b) || opened.get(a).order - opened.get(b).order;
    }).pop() || null;
    const old = top;
    const oldPrevious = old && old._dialogPrevious;
    for (const child of document.body.children) {
      if (child.tagName === 'SCRIPT') continue;
      if (next && !background.has(child)) {
        // Deze drie worden door het app-slot zelf beheerd; het slot is geen blijvende oorspronkelijke inert-status.
        const fromLock = $('#lockscr').classList.contains('on') && child.matches('.screen, #modal, #sheet');
        background.set(child, fromLock ? false : child.inert);
      }
      if (next) child.inert = child !== next && !child.contains(next);
      else if (background.has(child)) child.inert = background.get(child);
    }
    if (!next) background.clear();
    top = next;
    if (next) {
      next._dialogPrevious = opened.get(next).previous;
      if (preferred && next.contains(preferred)) preferred.focus({ preventScroll: true });
      else if (next !== old || !next.contains(document.activeElement)) {
        const first = controls(next)[0]; if (first) first.focus({ preventScroll: true });
      }
    } else if (oldPrevious && oldPrevious.isConnected && !oldPrevious.closest('[inert]')) oldPrevious.focus({ preventScroll: true });
  }
  document.addEventListener('keydown', e => {
    if (!top) return;
    if (e.key === 'Escape' && top.id !== 'lockscr') {
      e.preventDefault(); e.stopImmediatePropagation();
      if (top.id === 'modal') closeModal(null); else if (top.id === 'sheet') closeSheet(); else goBack();
    } else if (e.key === 'Tab') {
      const list = controls(top), first = list[0], last = list[list.length - 1];
      if (!first) { e.preventDefault(); return; }
      if (!top.contains(document.activeElement) || (!e.shiftKey && document.activeElement === last)) { e.preventDefault(); first.focus(); }
      else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    }
  }, true);
  new MutationObserver(records => {
    if (records.some(r => r.target.matches && r.target.matches(selector))) sync();
  }).observe(document.body, { attributes: true, attributeFilter: ['class'], subtree: true });
  return { sync };
})();
