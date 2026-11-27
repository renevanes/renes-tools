/* ---------- Agenda: synchroniseren met Google ----------
 * Scherm s-agsync (Agenda → Instellingen → Synchroniseren met Google). Java (AgendaSync) doet de ronde; hier alleen de
 * instellingen, de stand, het logboek en de conflicten. Titels en plekken zijn gegevens: esc() en translate="no". */
let agsData = null, agsLoopt = false;

function agsRichtingTekst(r){
  if (r === 'naar') return t('Wat in Tools staat, komt ook in de gekozen agenda. Google volgt Tools.');
  if (r === 'van') return t('Afspraken uit de gekozen agenda komen ook in Tools. Tools volgt Google.');
  if (r === 'beide') return t('Een wijziging aan de ene kant gaat naar de andere kant.');
  return t('Synchroniseren staat uit. Afspraken in een agenda van je telefoon gaan nog steeds via die agenda naar Google.');
}

function agsKan(){ return typeof Android.agendaSyncStaat === 'function'; }
function agsLaad(){ agsData = agsKan() ? (rdJson(Android.agendaSyncStaat(), {}) || {}) : {}; return agsData; }
function agsKort(){ return (agStaat && agStaat.sync) || (agsData && agsData.kort) || {}; }
function agsPushAan(){ const s = agsKort(); return !!(s.aan && s.naar); }

/** Korte stand voor de rij op Instellingen van Agenda en de regel op het scherm Agenda. */
function agsSamen(s){
  if (!s || !s.aan) return t('Uit');
  const kant = { naar: t('Tools → Google'), van: t('Google → Tools'), beide: t('Beide kanten') }[s.richting] || '';
  const elke = agsElkeTekst(+s.elke || 0);
  let r = t('{kant} met {agenda}, {elke}', { kant, agenda: s.kalNaam || t('je agenda'), elke });
  if (s.fout) r += ' · ' + s.fout;
  else if (+s.conflicten > 0) r += ' · ' + tn(+s.conflicten, '1 conflict', '{n} conflicten');
  else if (+s.rem > 0) r += ' · ' + t('verwijderen wacht op je ok');
  return r;
}
function agsElkeTekst(m){
  return ({ 0: t('alleen met de hand'), 15: t('elk kwartier'), 30: t('elk half uur'), 60: t('elk uur'), 180: t('elke 3 uur'), 360: t('elke 6 uur'), 720: t('elke 12 uur'), 1440: t('elke dag') })[m] || t('elk uur');
}

function agsRender(){
  const d = agsLaad();
  if (!agsKan()) { uiHtml($('#ags-stand-t'), esc(t('Installeer de nieuwste versie van Tools om te synchroniseren.'))); return; }
  const cfg = d.cfg || {}, kort = d.kort || {};
  $('#ags-toestem').hidden = !!(d.perm && (d.schrijf || cfg.richting === 'van'));
  // welke kant op
  document.querySelectorAll('#ags-richting button').forEach(b => {
    const aan = b.dataset.v === (cfg.richting || 'uit');
    b.classList.toggle('on', aan); b.setAttribute('aria-checked', aan ? 'true' : 'false');
  });
  $('#ags-richting-t').textContent = agsRichtingTekst(cfg.richting);
  // agenda: schrijfbare eerst, Google voorop; bij Google → Tools mag een alleen-lezen agenda ook
  const kals = (d.kalenders || []).slice().sort((a, b) => (b.google - a.google) || (b.schrijf - a.schrijf));
  const sel = $('#ags-kal');
  sel.innerHTML = ['<option value="">' + esc(t('Kies een agenda')) + '</option>'].concat(kals.map(k =>
    '<option value="' + esc(k.id) + '">' + esc((k.naam || k.id) + (k.account && k.account !== k.naam ? ' · ' + k.account : '')) +
    (k.schrijf ? '' : ' (' + esc(t('Alleen lezen')) + ')') + '</option>')).join('');
  sel.value = [...sel.options].some(o => o.value === String(cfg.kal || '')) ? String(cfg.kal || '') : '';
  const gekozen = kals.find(k => String(k.id) === String(cfg.kal || ''));
  const kt = $('#ags-kal-t');
  let kalTekst = '';
  if (!kals.length) kalTekst = t('Geen agenda\'s op je telefoon, of nog geen toestemming.');
  else if (cfg.richting !== 'uit' && !cfg.kal) kalTekst = t('Kies de agenda om mee te synchroniseren.');
  else if (cfg.kal && !d.kalOk) kalTekst = t('De gekozen agenda staat niet meer op je telefoon. Kies hem opnieuw.');
  else if (gekozen && !gekozen.schrijf && (cfg.richting === 'naar' || cfg.richting === 'beide')) kalTekst = t('In deze agenda mag Tools niet schrijven. Kies een andere, of alleen Google → Tools.');
  else if (gekozen && !gekozen.google) kalTekst = t('Dit is geen Google-agenda. Synchroniseren werkt wel: dan gaat het naar het account van die agenda.');
  kt.textContent = kalTekst; kt.hidden = !kalTekst;
  // wanneer
  $('#ags-elke').value = String(cfg.elke != null ? cfg.elke : 60);
  agsTg('#ags-open', cfg.open !== false);
  agsTg('#ags-laden', !!cfg.laden);
  agsTg('#ags-googlenu', cfg.googleNu !== false);
  $('#ags-terug').value = String(cfg.terug != null ? cfg.terug : 30);
  $('#ags-vooruit').value = String(cfg.vooruit != null ? cfg.vooruit : -1);
  $('#ags-wissen').value = cfg.wissen || 'naar';
  $('#ags-wissen-t').textContent = agsWisTekst(cfg);
  $('#ags-conflict').value = cfg.conflict || 'vragen';
  $('#ags-rem-k').value = String(cfg.rem != null ? cfg.rem : 10);
  $('#ags-meld').value = cfg.meld || 'fouten';
  // de stand
  agsStand(d);
  agsConflicten(d);
  agsLog(d);
}
function agsTg(sel, aan){ const b = $(sel); if (!b) return; b.classList.toggle('on', aan); b.setAttribute('aria-checked', aan ? 'true' : 'false'); }
function agsWisTekst(cfg){
  const r = cfg.richting || 'uit', w = cfg.wissen || 'naar';
  if (r === 'uit') return '';
  if (r === 'naar') return w === 'naar' || w === 'beide' ? t('Verwijder je een afspraak in Tools, dan gaat hij ook uit Google.') : t('Verwijder je een afspraak in Tools, dan blijft hij in Google staan.');
  if (r === 'van') return w === 'van' || w === 'beide' ? t('Verdwijnt een afspraak uit Google, dan gaat hij ook uit Tools.') : t('Verdwijnt een afspraak uit Google, dan blijft je kopie in Tools staan.');
  return ({ naar: t('Weg in Tools: ook weg in Google. Weg in Google: je kopie in Tools blijft.'), van: t('Weg in Google: ook weg in Tools. Weg in Tools: blijft in Google staan.'),
    beide: t('Weg aan de ene kant is ook weg aan de andere kant.'), nooit: t('Verwijderen gebeurt alleen aan de kant waar je het doet.') })[w] || '';
}
function agsStand(d){
  const kort = d.kort || {}, el = $('#ags-stand-t');
  if (agsLoopt) return; // de lopende balk staat er
  let tekst;
  if (!kort.aan) tekst = t('Synchroniseren staat uit.');
  else if (kort.laatst) tekst = t('Laatst: {wanneer}', { wanneer: fmtDT(kort.laatst, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) }) + ' · ' + agsElkeTekst(+kort.elke || 0);
  else tekst = t('Nog niet gesynchroniseerd.') + ' · ' + agsElkeTekst(+kort.elke || 0);
  const log = (d.log || [])[0];
  if (kort.aan && log && !log.fout) tekst += '\n' + agsLogSamen(log);
  uiWachtTekst(el, '');
  el.textContent = tekst; el.style.whiteSpace = 'pre-line';
  const f = $('#ags-fout'); f.textContent = kort.fout || ''; f.hidden = !kort.aan || !kort.fout;
  const rem = $('#ags-rem'), actie = kort.remActie || 'wis', n = +kort.rem || 0;
  rem.hidden = !(kort.aan && n > 0);
  $('#ags-rem-t').textContent = !n ? '' : actie === 'wis' ? tn(n, 'Eén afspraak is uit Google verdwenen. Ook uit Tools verwijderen?', '{n} afspraken zijn uit Google verdwenen. Ook uit Tools verwijderen?')
    : actie === 'opnieuw' ? tn(n, 'Eén afspraak is uit Google verdwenen. Opnieuw in Google zetten?', '{n} afspraken zijn uit Google verdwenen. Opnieuw in Google zetten?')
    : tn(n, 'Eén afspraak is uit Google verdwenen. De kopie in Tools losmaken van Google?', '{n} afspraken zijn uit Google verdwenen. De kopieën in Tools losmaken van Google?');
  $('#ags-rem-door').textContent = actie === 'wis' ? t('Toch verwijderen') : actie === 'opnieuw' ? t('Opnieuw in Google zetten') : t('Losmaken');
  $('#ags-rem-houd').hidden = actie === 'los';
  const nu = $('#ags-nu'); nu.disabled = !kort.aan || !!kort.bezig; nu.hidden = !kort.aan;
}
function agsLogSamen(r){
  const d = [];
  const naar = (+r.naarNieuw || 0) + (+r.naarBij || 0), van = (+r.vanNieuw || 0) + (+r.vanBij || 0);
  if (naar) d.push(tn(naar, '1 naar Google', '{n} naar Google'));
  if (van) d.push(tn(van, '1 uit Google', '{n} uit Google'));
  if (+r.wegTools) d.push(tn(+r.wegTools, '1 verwijderd', '{n} verwijderd'));
  if (+r.los) d.push(tn(+r.los, '1 losgemaakt', '{n} losgemaakt'));
  if (+r.conflict) d.push(tn(+r.conflict, '1 conflict', '{n} conflicten'));
  if (+r.mislukt) d.push(tn(+r.mislukt, '1 mislukt', '{n} mislukt'));
  if (+r.rem) d.push(tn(+r.rem, '1 wacht op je ok om te verwijderen', '{n} wachten op je ok om te verwijderen'));
  return d.length ? d.join(' · ') : t('Niets veranderd');
}
function agsLog(d){
  const log = d.log || [];
  if (!log.length) { uiHtml($('#ags-log'), '<p class="note">' + esc(t('Nog geen rondes.')) + '</p>'); return; }
  const waarom = { hand: t('met de hand'), plan: t('vanzelf'), open: t('bij openen') };
  uiHtml($('#ags-log'), '<div class="card">' + log.map(r => '<p class="note"><b>' + esc(fmtDT(r.tijd, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })) + '</b> · ' +
    esc(waarom[r.waarom] || '') + '<br>' + esc(r.fout ? r.fout : agsLogSamen(r)) + '</p>').join('') + '</div>');
}
function agsWanneer(it){
  if (!it) return '';
  const dag = fmtDate(it.dag, 'wdm');
  return it.heleDag || !it.van ? dag + ' · ' + t('Hele dag') : dag + ' · ' + fmtHm(it.van) + (it.tot ? '–' + fmtHm(it.tot) : '');
}
function agsVersie(kop, it){
  if (!it) return '<p class="note"><b>' + esc(kop) + '</b><br>' + esc(t('Niet meer te vinden')) + '</p>';
  return '<p class="note"><b>' + esc(kop) + '</b><br><span translate="no">' + esc(it.titel || '') + '</span><br>' + esc(agsWanneer(it)) +
    (it.plek ? '<br><span translate="no">' + esc(it.plek) + '</span>' : '') + '</p>';
}
function agsConflicten(d){
  const l = d.conflicten || [], box = $('#ags-con');
  box.hidden = !l.length;
  if (!l.length) { uiHtml($('#ags-con-lijst'), ''); return; }
  uiHtml($('#ags-con-lijst'), l.map(c => '<div class="card ags-con" data-id="' + esc(c.id) + '">' + agsVersie(t('In Tools'), c.tools) + agsVersie(t('In Google'), c.google) +
    '<div class="row"><button class="mini tonal" type="button" data-k="tools">' + esc(t('Houd Tools')) + '</button><button class="mini tonal" type="button" data-k="google">' + esc(t('Houd Google')) +
    '</button><button class="mini ghost" type="button" data-k="beide">' + esc(t('Houd beide')) + '</button></div></div>').join(''));
  $('#ags-con-lijst').querySelectorAll('.ags-con button').forEach(b => b.addEventListener('click', () => agsKies(b.closest('.ags-con').dataset.id, b.dataset.k)));
}
function agsKies(id, keuze){
  if (typeof Android.agendaSyncKies !== 'function') return;
  const r = rdJson(Android.agendaSyncKies(id, keuze), {});
  if (!r || !r.ok) { toast((r && r.fout) || t('Opslaan lukte niet.')); return; }
  toast(keuze === 'tools' ? t('De versie van Tools blijft') : keuze === 'google' ? t('De versie van Google blijft') : t('Beide versies blijven'));
  agsVernieuw();
}
function agsVernieuw(){
  if (typeof Android.agendaStaat === 'function') agStaat = rdJson(Android.agendaStaat(), agStaat) || agStaat;
  if (current === 'agsync') agsRender();
  else if (current === 'agaset') agSetRender();
  else if (current === 'agenda') { agSyncRegel(); const z = $('#ag-zoek'); if (z && !z.value.trim()) agLaad(); }
}

function agsZet(wat){
  if (typeof Android.agendaSyncZet !== 'function') return;
  agsData = rdJson(Android.agendaSyncZet(JSON.stringify(wat)), agsData) || agsData;
  if (typeof Android.agendaStaat === 'function') agStaat = rdJson(Android.agendaStaat(), agStaat) || agStaat;
  agsRender();
}
function agsSchakel(sleutel, b){ const aan = b.getAttribute('aria-checked') !== 'true'; agsZet({ [sleutel]: aan }); }

/** Werkt het nu (richting aan en een agenda gekozen die er is)? */
function agsActief(d){ const c = (d && d.cfg) || {}; return c.richting && c.richting !== 'uit' && !!c.kal && !!(d && d.kalOk); }
function agsPush(d){ const c = (d && d.cfg) || {}; return agsActief(d) && (c.richting === 'naar' || c.richting === 'beide'); }

/**
 * Na een andere richting of agenda: wordt het nu voor het eerst Tools → Google, vraag dan of de afspraken die alleen in Tools
 * staan ook mee gaan, en begin. Zonder gekozen agenda gebeurt er nog niets: die kiest Rene zelf.
 */
function agsWijzig(wat){
  const voor = agsData || agsLaad(), wasPush = agsPush(voor);
  agsZet(wat);
  const na = agsData || {};
  if (!agsActief(na)) {
    if ((na.cfg || {}).richting !== 'uit' && !(na.cfg || {}).kal) { toast(t('Kies de agenda om mee te synchroniseren.')); const k = $('#ags-kal'); if (k) k.focus(); }
    return;
  }
  const n = agsPush(na) && !wasPush ? +(na.teVersturen || 0) : 0;
  if (n > 0) {
    const naam = ((na.kalenders || []).find(x => String(x.id) === String((na.cfg || {}).kal)) || {}).naam || t('je agenda');
    askChoice(t('Bestaande afspraken meenemen?'),
      tn(n, 'Er staat één afspraak alleen in Tools. Zet die ook in {agenda}, of alleen nieuwe afspraken?', 'Er staan {n} afspraken alleen in Tools. Zet die ook in {agenda}, of alleen nieuwe afspraken?', { agenda: naam }),
      t('Ook bestaande'), t('Alleen nieuwe'), () => agsNu(), () => { agsZet({ bestaandLokaal: true }); agsNu(); });
    return;
  }
  agsNu();
}
function agsRichting(v){
  const d = agsData || agsLaad();
  if (v === ((d.cfg || {}).richting || 'uit')) return;
  agsWijzig({ richting: v });
}
function agsKalKies(v){ agsWijzig({ kal: v }); }

/** Nu synchroniseren (met de hand, of stil bij openen). rem: '' | 'wis' | 'los'. */
function agsNu(rem, waarom){
  if (typeof Android.agendaSyncNu !== 'function' || agsLoopt) return;
  const stil = waarom === 'open';
  if (!Android.agendaSyncNu(stil ? 'open' : 'hand', rem || '')) { if (!stil) toast(t('Er loopt al een ronde.')); return; }
  agsLoopt = true;
  if (!stil && current === 'agsync') { uiWachtTekst($('#ags-stand-t'), t('Synchroniseren…')); $('#ags-nu').disabled = true; }
  else if (!stil) uiBezig(t('Agenda synchroniseren…'), 'onAgendaSync');
}
function agsRem(keuze){
  const actie = agsKort().remActie || 'wis';
  if (keuze === 'wis' && actie === 'wis') askConfirm(t('Toch verwijderen?'), t('Deze afspraken zijn uit Google verdwenen en gaan nu ook uit Tools.'), t('Verwijderen'), () => agsNu('wis'));
  else agsNu(keuze === 'wis' ? 'wis' : 'los');
}
window.onAgendaSync = function(r){
  const stil = r && r.waarom === 'open';
  agsLoopt = false;
  if (!r) r = {};
  if (!stil) {
    if (r.bezig) toast(t('Er loopt al een ronde.'));
    else if (r.uit) toast(t('Synchroniseren staat uit.'));
    else if (r.ok === false) toast(r.fout || t('Synchroniseren lukte niet.'));
    else toast(r.samenvatting || t('Agenda gesynchroniseerd'));
  } else if (+r.conflicten > 0 || +r.rem > 0) toast(r.samenvatting || '');
  agsVernieuw();
};

/** De regel boven aan Agenda en het automatisch synchroniseren bij openen. */
function agSyncRegel(){
  const el = $('#ag-syncregel'); if (!el) return;
  const s = agsKort();
  el.hidden = !s.aan;
  el.textContent = s.aan ? t('Synchroniseren: {stand}', { stand: agsSamen(s) }) : '';
}
function agSyncBijOpenen(){
  const s = agsKort();
  if (!s.aan || s.open === false || s.bezig || typeof Android.agendaSyncNu !== 'function') return;
  if (s.laatst && Date.now() - s.laatst < 5 * 60_000) return;
  agsNu('', 'open');
}
