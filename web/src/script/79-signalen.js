/* ---------- Signalen: bronnen volgen (eerste soort: een webpagina met trefwoorden), één inbox, melden per bron ---------- */
// Ophalen gebeurt alleen in Java. Alles wat van een website komt (titel, tekst, adres) is gegevens: altijd esc() of
// textContent, nooit als HTML, en een adres gaat alleen via Android.openUrl (dat alleen http(s) opent).
const SN_SCHERMEN = ['signalen', 'snitem', 'snset', 'snbron'];
let sn = { staat: null, filter: 'open', item: '', bron: null, ai: false };
function snOud(){ return typeof Android.signalenStaat !== 'function'; }
function snLaad(){ sn.staat = snOud() ? {} : (rdJson(Android.signalenStaat(), {}) || {}); return sn.staat; }
function snItems(){ return (sn.staat && sn.staat.items) || []; }
function snBronnen(){ return (sn.staat && sn.staat.bronnen) || []; }
function snItem(id){ return snItems().find(x => x.id === id); }
function snEnter(name, prev){
  if (prev === 'snitem' && name !== 'snitem') sniAiStop();
  snLaad();
  if (name === 'signalen') snRender();
  else if (name === 'snitem') sniRender();
  else if (name === 'snset') snSetRender();
  else if (name === 'snbron') snbRender();
}
function snWanneer(it){
  if (!it || !it.dag) return '';
  return it.tijd ? t('{dag}, {tijd}', { dag: fmtDate(it.dag, 'lang'), tijd: fmtHm(it.tijd) }) : fmtDate(it.dag, 'lang');
}
function snInFilter(it, f, nu){
  const later = it.later > nu, klaar = it.status === 'klaar';
  return f === 'klaar' ? klaar : f === 'later' ? later && !klaar : !klaar && !later;
}
function snFilter(f){ sn.filter = f; snRender(); }
function snRender(){
  const st = sn.staat || {}, oud = snOud(), nu = Date.now();
  $('#sn-oud').hidden = !oud;
  $('#sn-meld').hidden = oud || st.perm !== false;
  document.querySelectorAll('#sn-filter button').forEach(b => { const on = b.dataset.f === sn.filter; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  const bronnen = snBronnen(), aan = bronnen.filter(b => b.aan !== false), stuk = aan.filter(b => b.fout);
  const laatst = Math.max(0, ...aan.map(b => b.lastOk || 0));
  const stand = $('#sn-stand');
  if (st.bezig) uiWachtTekst(stand, t('Bezig met nakijken…'));
  else stand.textContent = oud ? '' : st.onleesbaar ? t('De gegevens van Signalen zijn niet leesbaar. Er is niets overschreven.')
    : !bronnen.length ? t('Je volgt nog niets.')
    : stuk.length ? tn(stuk.length, 'Eén bron kon niet nagekeken worden. Kijk bij de instellingen.', '{n} bronnen konden niet nagekeken worden. Kijk bij de instellingen.')
    : (tn(aan.length, 'Eén bron aan', '{n} bronnen aan') + (laatst ? ' · ' + t('laatst nagekeken {wanneer}', { wanneer: fmtDT(laatst, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) }) : '')
      + (st.gedempt ? ' · ' + t('alle meldingen van Tools staan uit') : ''));
  const lijst = snItems().filter(it => snInFilter(it, sn.filter, nu));
  const box = $('#sn-lijst');
  if (!lijst.length) {
    box.innerHTML = !bronnen.length && !oud ? uiEmpty('bell', t('Volg een website en hoor het als er iets nieuws staat.'), t('Website toevoegen'), 'snBronNieuw()')
      : uiEmpty('bell', sn.filter === 'open' ? t('Niets nieuws.') : sn.filter === 'later' ? t('Niets voor later.') : t('Nog niets afgehandeld.'));
    return;
  }
  box.innerHTML = lijst.map(it => {
    const w = it.later > nu ? t('Herinnering: {wanneer}', { wanneer: fmtDT(it.later, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) }) : snWanneer(it);
    return '<button type="button" class="' + (it.status === 'nieuw' ? 'sn-item nieuw' : 'sn-item') + '" data-id="' + esc(it.id) + '"><b translate="no">' + esc(it.titel || it.link) + '</b>'
      + '<small translate="no">' + esc([w, it.bronNaam].filter(Boolean).join(' · ')) + '</small></button>';
  }).join('');
  box.querySelectorAll('.sn-item').forEach(b => b.addEventListener('click', () => snOpen(b.dataset.id)));
}
function snOpen(id){ if (!snItem(id)) { toast(t('Dat bericht staat er niet meer.')); return; } sn.item = id; show('snitem'); }
function snNu(){
  if (snOud()) return;
  if (!snBronnen().some(b => b.aan !== false)) { toast(t('Voeg eerst een website toe.')); return; }
  if (!Android.signalenScan('')) { toast(t('Signalen is al bezig met nakijken.')); return; }
  sn.staat.bezig = true; snRender();
}
window.onSignalen = function(r){
  snLaad();
  if (current === 'signalen') snRender(); else if (current === 'snset') snSetRender();
  if (!r || typeof r !== 'object') return;
  if (r.fouten && !r.ok) toast(t('Nakijken lukte niet.'));
  else if (r.nieuw) toast(tn(r.nieuw, 'Eén nieuw bericht', '{n} nieuwe berichten'));
  else if (current === 'signalen') toast(t('Niets nieuws.'));
};
function snMeldingen(){ if (typeof Android.permRequest === 'function') Android.permRequest('notif'); }

/* ----- één bericht ----- */
function sniRender(){
  const it = snItem(sn.item);
  if (!it) { goBack(); return; }
  if (it.status === 'nieuw') { Android.signalenItem(it.id, 'gelezen', ''); it.status = 'gelezen'; }
  $('#sni-titel').textContent = it.titel || it.link;
  $('#sni-meta').textContent = [it.bronNaam, t('gevonden {wanneer}', { wanneer: fmtDT(it.gevonden, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) })].filter(Boolean).join(' · ');
  const w = $('#sni-wanneer');
  w.hidden = !it.dag; w.textContent = it.dag ? t('Datum in het bericht: {wanneer}', { wanneer: snWanneer(it) }) : '';
  $('#sni-tekst').textContent = it.tekst || '';
  $('#sni-agenda').hidden = !it.dag;
  $('#sni-agenda').textContent = it.inAgenda ? t('Staat in je agenda') : t('In agenda zetten');
  $('#sni-agenda').disabled = !!it.inAgenda;
  $('#sni-klaar').textContent = it.status === 'klaar' ? t('Terug naar Open') : t('Afgehandeld');
  $('#sni-ai').hidden = typeof Android.signalenAi !== 'function' || !it.tekst;
  $('#sni-aikaart').hidden = true; $('#sni-aitekst').textContent = '';
}
function sniAgenda(){
  const it = snItem(sn.item);
  if (!it || !it.dag || typeof Android.agendaBewaar !== 'function') return;
  const st = rdJson(Android.agendaStaat(), {}) || {};
  let tot = '';
  if (it.tijd) { const m = Math.min(23 * 60 + 59, (+it.tijd.slice(0, 2)) * 60 + (+it.tijd.slice(3)) + 60); tot = String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'); }
  const r = rdJson(Android.agendaBewaar(JSON.stringify({ id: '', titel: (it.titel || '').slice(0, 200), plek: '', notitie: it.link || '', dag: it.dag, eind: it.dag,
    van: it.tijd || '09:00', tot: tot || '10:00', heleDag: !it.tijd, herhaal: '', totDag: '', kalender: st.standaard || '', ext: 0 })), {});
  if (!r || !r.ok) { toast((r && r.fout) || t('Opslaan lukte niet.')); return; }
  Android.signalenItem(it.id, 'dag', ''); it.inAgenda = true;
  toast(t('In je agenda gezet op {dag}', { dag: fmtDate(it.dag, 'kort') }));
  sniRender();
}
function sniWeb(){ const it = snItem(sn.item); if (it && it.link) Android.openUrl(it.link); }
function sniNotitie(){
  const it = snItem(sn.item);
  if (!it || typeof notesGet !== 'function') return;
  const w = snWanneer(it);
  notesGet().push({ id: uid(), kind: 'note', title: (it.titel || '').slice(0, 80), text: [w, it.tekst, it.link].filter(Boolean).join('\n\n'), items: [], updated: Date.now() });
  notesSaveNow();
  toast(t('Notitie gemaakt'));
}
function sniLaterOp(ms){
  const it = snItem(sn.item);
  if (!it) return;
  if (!Android.signalenItem(it.id, 'later', String(ms))) { toast(t('Dat lukte niet.')); return; }
  toast(t('Je hoort het weer op {wanneer}', { wanneer: fmtDT(ms, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) }));
  snLaad(); goBack();
}
function sniLater(){
  const nu = Date.now(), vandaag = rgToday(), acts = [[t('Over een uur'), () => sniLaterOp(Date.now() + 3600000), 'clock']];
  const avond = rgFromLocal(vandaag, '19:00');
  if (avond > nu + 3600000) acts.push([t('Vanavond om {tijd}', { tijd: fmtHm('19:00') }), () => sniLaterOp(avond), 'clock']);
  acts.push([t('Morgen om {tijd}', { tijd: fmtHm('09:00') }), () => sniLaterOp(rgFromLocal(rgDayAdd(vandaag, 1), '09:00')), 'clock']);
  acts.push([t('Over een week'), () => sniLaterOp(rgFromLocal(rgDayAdd(vandaag, 7), '09:00')), 'clock']);
  const it = snItem(sn.item);
  if (it && it.dag && rgDayAdd(it.dag, -1) > vandaag) acts.push([t('De dag ervoor ({dag})', { dag: fmtDate(rgDayAdd(it.dag, -1), 'kort') }), () => sniLaterOp(rgFromLocal(rgDayAdd(it.dag, -1), '09:00')), 'clock']);
  openSheet(t('Later herinneren'), t('Je krijgt dan een melding van dit bericht.'), acts);
}
function sniLees(){ const it = snItem(sn.item); if (it && typeof spText === 'function') spText([it.titel, snWanneer(it), it.tekst].filter(Boolean).join('.\n'), 'text'); }
function sniKlaar(){
  const it = snItem(sn.item);
  if (!it) return;
  Android.signalenItem(it.id, it.status === 'klaar' ? 'open' : 'klaar', '');
  snLaad(); goBack();
}
function sniWis(){ Android.signalenItem(sn.item, 'wis', ''); snLaad(); goBack(); }
function sniMenu(){ openSheet(t('Bericht'), '', [[t('Verwijderen'), () => sniWis(), 'trash']]); }
function sniAi(){
  const it = snItem(sn.item);
  if (!it) return;
  const fout = Android.signalenAi(it.id);
  if (fout) { toast(fout); return; }
  sn.ai = true; sn.aiTekst = '';
  $('#sni-aikaart').hidden = false;
  uiWachtTekst($('#sni-aitekst'), t('De lokale AI leest het bericht…'));
}
function sniAiStop(){ if (sn.ai && typeof Android.signalenAiStop === 'function') Android.signalenAiStop(); sn.ai = false; }
window.onSignalenAiToken = function(s){ if (!sn.ai || current !== 'snitem') return; sn.aiTekst += String(s || ''); $('#sni-aitekst').textContent = sn.aiTekst; };
window.onSignalenAiDone = function(){ sn.ai = false; };
window.onSignalenAiError = function(m){ sn.ai = false; if (current === 'snitem') $('#sni-aitekst').textContent = String(m || t('De lokale AI kon niet starten')); };

/* ----- instellingen: bronnen, stille uren, dagoverzicht ----- */
function snElkeTekst(u){ return u === 1 ? t('elk uur') : u === 24 ? t('elke dag') : u === 168 ? t('elke week') : u === 12 ? t('twee keer per dag') : t('elke {n} uur', { n: fmtNum(u) }); }
function snMeldTekst(m){ return m === 'overzicht' ? t('in het dagoverzicht') : m === 'inbox' ? t('geen melding') : t('meteen een melding'); }
function snBronStand(b){
  if (b.aan === false) return t('Uit');
  if (b.fout) return t('Lukt niet: {fout}', { fout: b.fout });
  return snElkeTekst(b.elke) + ' · ' + snMeldTekst(b.melden) + (b.lastOk ? '' : ' · ' + t('nog niet nagekeken'));
}
function snSetRender(){
  const st = sn.staat || {}, i = st.inst || {};
  const box = $('#sns-bronnen'), bs = snBronnen();
  uiHtml(box, bs.length ? bs.map(b => '<button type="button" class="strow" data-id="' + esc(b.id) + '"><span class="stic">' + uiIcon(b.aan === false ? 'pause' : 'bell') + '</span><span class="sttxt"><b translate="no">' + esc(b.naam) + '</b><small translate="no">' + esc(snBronStand(b)) + '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>').join('')
    : '<p class="note" style="margin:0">' + esc(t('Nog geen bronnen.')) + '</p>');
  box.querySelectorAll('.strow').forEach(b => b.addEventListener('click', () => snBronOpen(b.dataset.id)));
  const tg = $('#sns-stil'); tg.classList.toggle('on', i.stilAan !== false); tg.setAttribute('aria-checked', i.stilAan !== false ? 'true' : 'false');
  $('#sns-van').value = i.stilVan || '22:00'; $('#sns-tot').value = i.stilTot || '07:30'; $('#sns-overzicht').value = i.overzicht || '18:00';
}
function snInstBewaar(){
  if (snOud()) return;
  const r = rdJson(Android.signalenInst(JSON.stringify({ stilAan: $('#sns-stil').getAttribute('aria-checked') === 'true', stilVan: $('#sns-van').value, stilTot: $('#sns-tot').value, overzicht: $('#sns-overzicht').value })), {});
  if (!r || !r.ok) toast((r && r.fout) || t('Opslaan lukte niet.'));
  snLaad();
}
function snBronOpen(id){ const b = snBronnen().find(x => x.id === id); if (!b) return; sn.bron = JSON.parse(JSON.stringify(b)); show('snbron'); }
function snBronNieuw(){
  if (snOud()) { toast(t('Installeer de nieuwste versie van Tools om Signalen te gebruiken.')); return; }
  sn.bron = { id: '', naam: '', url: '', woorden: [], alleen: '', elke: 6, wifi: true, melden: 'direct', belangrijk: false, aan: true };
  show('snbron');
}

/* ----- één bron ----- */
function snbZet(id, on){ const b = $(id); b.classList.toggle('on', !!on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }
function snbVul(b){
  $('#snb-naam').value = b.naam || ''; $('#snb-url').value = b.url || ''; $('#snb-woorden').value = (b.woorden || []).join(', '); $('#snb-alleen').value = b.alleen || '';
  $('#snb-elke').value = String(b.elke || 6); $('#snb-melden').value = b.melden || 'direct';
  snbZet('#snb-aan', b.aan !== false); snbZet('#snb-wifi', b.wifi !== false); snbZet('#snb-belangrijk', !!b.belangrijk);
}
function snbRender(){
  const b = sn.bron || {}, nieuw = !b.id;
  $('#snb-h1').textContent = nieuw ? t('Website toevoegen') : t('Bron');
  $('#snb-menu').hidden = nieuw;
  const sj = (sn.staat && sn.staat.sjablonen) || [], sel = $('#snb-sjabloon');
  $('#snb-sjabloonkaart').hidden = !nieuw || !sj.length;
  sel.innerHTML = '<option value="">' + esc(t('Zelf invullen')) + '</option>' + sj.map(s => '<option value="' + esc(s.sjabloon) + '" translate="no">' + esc(s.naam) + '</option>').join('');
  const stand = $('#snb-stand');
  stand.hidden = nieuw || !(b.fout || b.lastOk);
  stand.textContent = b.fout ? t('Lukt niet: {fout}', { fout: b.fout }) : b.lastOk ? t('Laatst nagekeken {wanneer}: {n} links op de pagina', { wanneer: fmtDT(b.lastOk, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }), n: fmtNum(b.links || 0) }) : '';
  $('#snb-proefkaart').hidden = true;
  snbVul(b);
}
function snbSjabloon(){
  const s = ((sn.staat && sn.staat.sjablonen) || []).find(x => x.sjabloon === $('#snb-sjabloon').value);
  if (s) snbVul(Object.assign({}, snbLees(), s));
}
function snbLees(){
  const aan = id => $(id).getAttribute('aria-checked') === 'true';
  return { id: (sn.bron && sn.bron.id) || '', naam: $('#snb-naam').value.trim(), url: $('#snb-url').value.trim(), alleen: $('#snb-alleen').value.trim(),
    woorden: $('#snb-woorden').value.split(',').map(x => x.trim()).filter(Boolean), elke: +$('#snb-elke').value || 6, melden: $('#snb-melden').value,
    aan: aan('#snb-aan'), wifi: aan('#snb-wifi'), belangrijk: aan('#snb-belangrijk') };
}
function snbBewaar(){
  const r = rdJson(Android.signalenBron(JSON.stringify(snbLees())), {});
  if (!r || !r.ok) { toast((r && r.fout) || t('Opslaan lukte niet.')); return; }
  toast(t('Bewaard'));
  snLaad();
  const b = snBronnen().find(x => x.id === r.id);
  if (b && b.aan !== false && !b.klaar) Android.signalenScan(r.id); // meteen de eerste keer: wat er nu staat komt zonder melding in Signalen
  goBack();
}
function snbProef(){
  if (!$('#snb-url').value.trim()) { toast(t('Vul eerst het adres van de pagina in.')); return; }
  $('#snb-proefkaart').hidden = false; $('#snb-proeflijst').innerHTML = '';
  uiWachtTekst($('#snb-proefstand'), t('De pagina wordt opgehaald…'));
  Android.signalenProef(JSON.stringify(snbLees()));
}
window.onSignalenProef = function(r){
  if (current !== 'snbron') return;
  const stand = $('#snb-proefstand'), box = $('#snb-proeflijst');
  box.innerHTML = '';
  if (!r || !r.ok) { stand.textContent = (r && r.fout) || t('De pagina kon niet gelezen worden'); return; }
  const g = r.gevonden || [];
  stand.textContent = g.length ? tn(g.length, 'Eén bericht past nu bij je trefwoorden ({links} links op de pagina).', '{n} berichten passen nu bij je trefwoorden ({links} links op de pagina).', { links: fmtNum(r.links || 0) })
    : t('Niets op de pagina past nu bij je trefwoorden ({links} links bekeken).', { links: fmtNum(r.links || 0) });
  g.forEach(x => {
    const p = document.createElement('p'); p.className = 'sn-proef'; p.setAttribute('translate', 'no');
    const b = document.createElement('b'); b.textContent = x.titel || x.link; p.appendChild(b);
    if (x.dag) { const s = document.createElement('small'); s.textContent = fmtDate(x.dag, 'lang'); p.appendChild(s); }
    box.appendChild(p);
  });
};
function snbWis(){
  const b = sn.bron; if (!b || !b.id) return;
  askConfirm(t('Bron verwijderen?'), t('Tools kijkt dan niet meer op deze website. De berichten die er al staan blijven staan.'), t('Verwijderen'), () => {
    const r = rdJson(Android.signalenBronWis(b.id), {});
    if (!r || !r.ok) { toast((r && r.fout) || t('Dat lukte niet.')); return; }
    snLaad(); goBack();
  });
}
function snbMenu(){ openSheet(t('Bron'), '', [[t('Bron verwijderen'), () => snbWis(), 'trash']]); }
