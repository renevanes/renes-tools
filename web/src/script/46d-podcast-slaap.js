/* ---------- Podcasts: afgespeeld met de slaaptimer (PodcastSlaap.java) ----------
 * Liep een aflevering af terwijl de slaaptimer liep, dan heb je hem misschien niet gehoord. De speler houdt dat bij; hier
 * vraag je wat je echt hoorde (kaart op Luisteren), of zie je wat er vanzelf terugging. De lijst en de instelling staan
 * op Instellingen van Podcasts. Titels en namen zijn gegevens: esc() en translate="no". */
function slpData(){ return typeof Android.podSlaap === 'function' ? rdJson(Android.podSlaap(), {}) : {}; }

/** "Podcast · gisteren 23:40" Zuiver op de gegevens (de tijd via de regio-helpers). */
function slpWanneer(r){
  const pod = r.pod && r.pod.title ? r.pod.title : '';
  const wanneer = r.t ? pcDate(r.t) + ' ' + fmtTime(r.t, { hour: '2-digit', minute: '2-digit' }) : '';
  return [pod, wanneer].filter(Boolean).join(' · ');
}
/** De plek waar hij weer klaarstaat, als tekst ("vanaf 23:10" of "vanaf het begin"). */
function slpVanaf(r, plek){
  let van = plek === 'begin' ? 0 : Math.max(0, +r.van || 0); // zoals PodcastSlaap.terugPlek: nooit vlak bij het einde
  const d = +r.d || 0;
  if (d > 0 && van > d - 60000) van = Math.max(0, d - 60000);
  return van >= 60000 ? t('vanaf {plek}', { plek: fmtMin(Math.floor(van / 60000)) }) : t('vanaf het begin');
}
function slpRij(r, plek, vink){
  const titel = '<span translate="no">' + esc((r.ep && r.ep.title) || '') + '</span>';
  const sub = '<small><span translate="no">' + esc(slpWanneer(r)) + '</span> · ' + esc(slpVanaf(r, plek)) + '</small>';
  if (!vink) return '<div class="pcslp-r"><b>' + titel + '</b>' + sub + '</div>';
  return '<label class="chk pcslp-r"><input type="checkbox" checked data-key="' + esc(r.key) + '"><span><b>' + titel + '</b>' + sub + '</span></label>';
}

/* De kaart op Luisteren: eerst wat nog open staat (vragen), anders wat vanzelf terugging en nog niet gezien is */
function slpRender(){
  const el = $('#pc-slaap'); if (!el) return;
  const d = slpData(), lijst = (d.lijst || []).slice().reverse(), plek = d.plek || 'wakker';
  const open = lijst.filter(r => r.st === 'open'), auto = lijst.filter(r => r.st === 'terug' && r.auto && !r.gezien);
  if (!open.length && !auto.length) { el.hidden = true; el.innerHTML = ''; return; }
  const kop = '<h2>' + uiIcon('moon') + '<span>' + esc(t('Afgespeeld met de slaaptimer')) + '</span></h2>';
  if (open.length) {
    el.innerHTML = kop + '<p class="hint">' + esc(tn(open.length,
      'Deze aflevering liep af terwijl de slaaptimer liep. Heb je hem echt gehoord? Aangevinkt = weer niet beluisterd.',
      'Deze {n} afleveringen liepen af terwijl de slaaptimer liep. Heb je ze echt gehoord? Aangevinkt = weer niet beluisterd.')) + '</p>' +
      '<div class="pcslp-l">' + open.map(r => slpRij(r, plek, true)).join('') + '</div>' +
      '<button class="big tonal" id="pc-slaap-terug">' + esc(t('Als niet beluisterd markeren')) + '</button>' +
      '<button class="big ghost" id="pc-slaap-gehoord">' + esc(tn(open.length, 'Ik heb hem gehoord', 'Ik heb ze allemaal gehoord')) + '</button>';
    $('#pc-slaap-terug').addEventListener('click', slpTerugGekozen);
    $('#pc-slaap-gehoord').addEventListener('click', () => slpOordeel(open.map(r => r.key), 'gehoord'));
    el.querySelectorAll('input[data-key]').forEach(i => i.addEventListener('change', slpKnop));
  } else {
    el.innerHTML = kop + '<p class="hint">' + esc(tn(auto.length,
      'Vanzelf als niet beluisterd gemarkeerd: deze aflevering liep af terwijl de slaaptimer liep.',
      'Vanzelf als niet beluisterd gemarkeerd: deze {n} afleveringen liepen af terwijl de slaaptimer liep.')) + '</p>' +
      '<div class="pcslp-l">' + auto.map(r => slpRij(r, plek, false)).join('') + '</div>' +
      '<button class="big tonal" id="pc-slaap-ok">' + esc(t('Oké')) + '</button>' +
      '<button class="big ghost" id="pc-slaap-toch">' + esc(tn(auto.length, 'Toch gehoord', 'Toch allemaal gehoord')) + '</button>';
    $('#pc-slaap-ok').addEventListener('click', () => slpOordeel(auto.map(r => r.key), 'gezien'));
    $('#pc-slaap-toch').addEventListener('click', () => slpOordeel(auto.map(r => r.key), 'gehoord'));
  }
  el.hidden = false;
}
/* Niets aangevinkt: de hoofdknop kan niets doen */
function slpKnop(){ const b = $('#pc-slaap-terug'); if (b) b.disabled = !$('#pc-slaap').querySelector('input[data-key]:checked'); }
/* Aangevinkt = niet beluisterd; de rest heb je dus wel gehoord */
function slpTerugGekozen(){
  const alle = [...$('#pc-slaap').querySelectorAll('input[data-key]')];
  const terug = alle.filter(i => i.checked).map(i => i.dataset.key), gehoord = alle.filter(i => !i.checked).map(i => i.dataset.key);
  if (!terug.length) return;
  if (gehoord.length) Android.podSlaapOordeel(JSON.stringify(gehoord), 'gehoord');
  slpOordeel(terug, 'terug');
}
function slpOordeel(keys, st){
  const n = +Android.podSlaapOordeel(JSON.stringify(keys), st) || 0;
  if (n && st === 'terug') toast(tn(n, 'Weer niet beluisterd', '{n} afleveringen weer niet beluisterd'));
  slpNa();
}
/* Daarna alles bijwerken wat beluisterd toont */
function slpNa(){
  slpRender();
  if (current === 'podcasts') { pcRenderCont(); pcRenderNew(); }
  if (typeof pcDlRefresh === 'function') pcDlRefresh();
  if ($('#ps-slp-lijst')) psSlpRender();
}

/* Instellingen van Podcasts: Na de slaaptimer, met de lijst van de afgelopen twee weken */
function psSlpUitleg(w, plek){
  if (w === 'uit') return t('Niets bijhouden: wat afliep blijft beluisterd.');
  const vanaf = plek === 'begin' ? t('vanaf het begin') : t('vanaf waar je nog wakker was');
  return w === 'vanzelf' ? t('Na de slaaptimer staan ze vanzelf weer klaar, {vanaf}.', { vanaf })
    : t('Op Luisteren vraagt Podcasts wat je echt hoorde; wat je niet hoorde staat weer klaar, {vanaf}.', { vanaf });
}
function psSlpRender(){
  const el = $('#ps-slp-lijst'); if (!el) return;
  if (typeof Android.podSlaap !== 'function') { $('#ps-slp').hidden = true; return; }
  const d = slpData(), w = d.wijze || 'vragen', plek = d.plek || 'wakker', lijst = (d.lijst || []).slice().reverse();
  $('#ps-slp-wijze').value = w; $('#ps-slp-plek').value = plek;
  $('#ps-slp-plek').disabled = w === 'uit';
  $('#ps-slp-uitleg').textContent = psSlpUitleg(w, plek);
  if (!lijst.length) { el.innerHTML = ''; return; }
  const stand = r => r.st === 'terug' ? t('Niet beluisterd') : r.st === 'gehoord' ? t('Gehoord') : t('Nog niet gekozen');
  el.innerHTML = '<div class="flabel" id="ps-slp-h">' + esc(t('Afgespeeld met de slaaptimer')) + '</div><div class="pcslp-l" role="list" aria-labelledby="ps-slp-h">' +
    lijst.map(r => '<div class="pcslp-r pcslp-st" role="listitem"><span><b translate="no">' + esc((r.ep && r.ep.title) || '') + '</b><small><span translate="no">' + esc(slpWanneer(r)) + '</span> · ' + esc(stand(r)) + '</small></span>' +
      '<button class="mini" data-key="' + esc(r.key) + '" data-st="' + (r.st === 'terug' ? 'gehoord' : 'terug') + '">' +
      esc(r.st === 'terug' ? t('Toch gehoord') : t('Niet beluisterd')) + '</button></div>').join('') + '</div>';
  el.querySelectorAll('button[data-key]').forEach(b => b.addEventListener('click', () => slpOordeel([b.dataset.key], b.dataset.st)));
}
function psSlp(){ slpZet($('#ps-slp-wijze').value, $('#ps-slp-plek').value); }
/* Bewaren, en alles bijwerken wat het raakt. Op "Als beluisterd laten" met afleveringen die nog open staan: eerst vragen,
   want die tellen dan meteen als gehoord (hun download kan weg) en dat draait terugzetten van de keuze niet terug. */
function slpZet(w, plek){
  const doe = () => { Android.podSlaapZet(w, plek); slpNa(); if (typeof sleepSlpToon === 'function') sleepSlpToon(); };
  const open = w === 'uit' ? (slpData().lijst || []).filter(r => r.st === 'open').length : 0;
  if (!open) { doe(); return; }
  const terug = () => { if ($('#ps-slp-lijst')) psSlpRender(); if (typeof sleepSlpToon === 'function') sleepSlpToon(); };
  terug(); // de keuzelijst laat de oude stand zien tot Rene het bevestigt
  askConfirm(t('Als beluisterd laten?'), tn(open,
    'Er staat nog één aflevering open die afliep terwijl de slaaptimer liep. Die telt dan als gehoord; een download ervan kan weg.',
    'Er staan nog {n} afleveringen open die afliepen terwijl de slaaptimer liep. Die tellen dan als gehoord; een download ervan kan weg.'),
    t('Als beluisterd laten'), doe, terug);
}
/* Vanuit ⋯ op Podcasts: naar de lijst op Instellingen van Podcasts */
function slpLijstOpen(){ show('podset'); setTimeout(() => { const c = $('#ps-slp'); if (c) c.scrollIntoView({ block: 'start' }); }, 50); }

/* In het venster Slaaptimer (alleen bij podcasts, niet op een Chromecast): wat er gebeurt met afleveringen die aflopen
   terwijl de timer loopt. Dezelfde instelling als op Instellingen van Podcasts (wens van Rene: bij de slaaptimer). */
function sleepSlpToon(){
  const rij = $('#sl-slp-rij'); if (!rij) return;
  const cast = typeof plCast === 'object' && plCast && plCast.active;
  rij.hidden = slTarget !== 'pod' || typeof Android.podSlaap !== 'function' || !!cast;
  if (rij.hidden) return;
  const w = slpData().wijze || 'vragen';
  $('#sl-slp').value = w;
  $('#sl-slp-uitleg').textContent = w === 'vanzelf' ? t('Na de timer vanzelf weer niet beluisterd.')
    : w === 'uit' ? t('Telt gewoon als beluisterd.') : t('Daarna vraagt Podcasts wat je echt hoorde.');
}
function sleepSlpKies(v){ slpZet(v, slpData().plek || 'wakker'); }
