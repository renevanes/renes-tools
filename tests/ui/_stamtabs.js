// Stamgegevens bewerken heeft tabbladen: wat op een ander tabblad staat is verborgen (Playwright kan het dan niet invullen).
// Deze hulp opent eerst het tabblad waar het element op staat.
module.exports = p => {
  const tab = async s => {
    const id = await p.evaluate(s => { const b = document.querySelector(s)?.closest('[data-cm-tab]'); return b && b.classList.contains('cm-off') ? b.dataset.cmTab : ''; }, s);
    if (id) await p.click('#cm-tab-' + id);
  };
  return {
    tab,
    fill: async (s, v) => { await tab(s); await p.fill(s, v); },
    select: async (s, v) => { await tab(s); return p.selectOption(s, v); },
    click: async s => { await tab(s); await p.click(s); },
  };
};
