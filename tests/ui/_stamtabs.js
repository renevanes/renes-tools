// Stamgegevens bewerken gaat per blok (het potlood op het visitekaartje): velden van een ander blok zijn verborgen
// (Playwright kan ze dan niet invullen). Deze hulp opent eerst het blok waar het element in staat, en maakt stamgegevens
// aan zoals de app ze bewaart (masterSave), voor tests die over iets anders gaan dan het invullen zelf.
module.exports = p => {
  const tab = async s => {
    const id = await p.evaluate(s => { const b = document.querySelector(s)?.closest('[data-cm-tab]'); return b && b.classList.contains('cm-off') ? b.dataset.cmTab : ''; }, s);
    if (id) await p.evaluate(id => cmTab(id), id);
    await p.evaluate(s=>{const field=document.querySelector(s);for(const d of document.querySelectorAll('#cm-editor details'))if(d.contains(field))d.open=true;},s);
  };
  // Een stamgegeven bewaren en de lijst bijwerken; geeft het id.
  const maak = (type, label, fields = {}) => p.evaluate(([type, label, fields]) => new Promise((ok, fout) =>
    cmRequest('masterSave', [JSON.stringify({ type, label, fields, revision: 0 })], r => cmListLoad(() => ok(r.record.id)), false, r => fout(Error(r.error)))), [type, label, fields]);
  // Eén blok van een bewaard stamgegeven bewerken (zoals het potlood op het kaartje)
  const blok = async (id, b) => {
    await p.evaluate(([id, b]) => cmEdit(id, b, { id }), [id, b]);
    await p.waitForFunction(([id, b]) => current === 'cmedit' && cm.edit?.id === id && (!b || cm.tab === b) && !cm.pending.size, [id, b]);
  };
  // Het visitekaartje openen en wachten tot het getekend is
  const kaart = async id => {
    await p.evaluate(id => cmKaartOpen(id), id);
    await p.waitForFunction(id => current === 'cmkaart' && cmc.rec?.id === id && !cm.pending.size && !cm.mutating, id);
  };
  // Opslaan; wacht tot de bewerking dicht is en de lijst opnieuw geladen
  const opslaan = async () => {
    await p.click('#cm-save');
    await p.waitForFunction(() => !cm.mutating && !cm.edit && cm.recordsReady && !cm.pending.size && current !== 'cmedit');
  };
  return {
    tab, maak, blok, kaart, opslaan,
    fill: async (s, v) => { await tab(s); await p.fill(s, v); },
    select: async (s, v) => { await tab(s); return p.selectOption(s, v); },
    click: async s => { await tab(s); await p.click(s); },
  };
};
