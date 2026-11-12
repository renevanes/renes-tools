/* ---------- Podcasts downloaden voor offline luisteren ----------
 * Android downloadt (gaat door op de achtergrond); afspelen kiest vanzelf het bestand als het er is. */
let pcDl = [], pcDlMap = {}, pcDlT = null;
function pcDlLoad(){
  pcDl = rdJson(Android.podDlList(), []);
  pcDlMap = {}; pcDl.forEach(d => { pcDlMap[d.key] = d; });
  return pcDl;
}
function pcDlBusy(d){ return d && (d.state === 'running' || d.state === 'queued' || d.state === 'paused'); }
/* Korte tekst voor in een rij: "Offline", "↓ 45%", "Wacht op wifi" */
function pcDlTxt(d){
  if (!d) return '';
  if (d.state === 'done') return '✓ Offline';
  if (d.state === 'failed') return '⚠ ' + t(d.reason || 'Downloaden mislukt');
  if (d.state === 'paused') return '↓ ' + t(d.reason || 'Gepauzeerd');
  if (d.state === 'queued') return '↓ In de wachtrij';
  return '↓ ' + (d.total > 0 ? Math.min(99, Math.floor(d.bytes / d.total * 100)) + '%' : t('Bezig'));
}
/* Menu-keuzes voor een aflevering (in elk afleveringmenu) */
function pcDlActs(ep, pod){
  const d = pcDlMap[ep.key];
  if (!d) return [['↓ Downloaden voor offline', () => pcDlStart(ep, pod)]];
  if (d.state === 'done') return [['Download verwijderen', () => pcDlRemove(ep.key)]];
  if (d.state === 'failed') return [['↓ Opnieuw downloaden', () => { Android.podDlRemove(ep.key); pcDlStart(ep, pod); }], ['Download weghalen', () => pcDlRemove(ep.key)]];
  return [['Downloaden stoppen', () => pcDlRemove(ep.key)]];
}
function pcDlStart(ep, pod){
  const e = Android.podDlStart(JSON.stringify({ ep: pcSlimEp(ep), pod: pcSlimPod(pod) }));
  if (e) { toast(e); return; }
  const pr = rdJson(Android.podDlPrefs(), {});
  toast(pr.wifiOnly ? '↓ Downloaden (via wifi)' : '↓ Downloaden');
  pcDlRefresh();
}
function pcDlRemove(key){ Android.podDlRemove(key); pcDlRefresh(); }
/* Alles bijwerken wat downloads toont, en blijven kijken zolang er iets bezig is */
let pcDlSig = {};
function pcDlRefresh(){
  pcDlLoad();
  if (current === 'podcasts') pcRenderDl();
  if (current === 'podcast' && pdFeed) {
    // Alleen de tekst van rijen waarvan de download veranderde (de rij zelf blijft staan: tik en focus gaan niet verloren)
    pdRows.forEach((e, i) => {
      const s = pcDlTxt(pcDlMap[e.key]);
      if ((pcDlSig[e.key] || '') === s) return;
      pcDlSig[e.key] = s;
      const sm = document.querySelector('#pd-eps .pcep[data-i="' + i + '"] small'); if (sm) sm.textContent = pdRowInfo(e);
    });
  }
  clearTimeout(pcDlT);
  if (pcDl.some(pcDlBusy) && (current === 'podcasts' || current === 'podcast')) pcDlT = setTimeout(pcDlRefresh, 2000);
}
window.onPodDl = function(){ pcDlRefresh(); };

/* Hoofdscherm: lijst "Gedownload" en de instellingen */
let pcDlListSig = '';
function pcDlLine(d){ return [(d.pod || {}).title, pcDlTxt(d), d.state === 'done' && d.bytes ? fmtB(d.bytes) : ''].filter(Boolean).join(' · '); }
function pcDlBar(d){ return pcDlBusy(d) && d.total > 0; }
function pcRenderDl(){
  const wrap = $('#pc-dl-wrap'); if (!wrap) return;
  wrap.style.display = pcDl.length ? 'block' : 'none';
  $('#pc-dl-empty').style.display = pcDl.length ? 'none' : '';
  $('#pc-tab-dl-n').textContent = pcDl.length ? String(pcDl.length) : '';
  $('#pc-tab-dl').setAttribute('aria-label', t('Downloads') + (pcDl.length ? ', ' + pcDl.length : ''));
  const done = pcDl.filter(d => d.state === 'done'), bytes = done.reduce((a, d) => a + (+d.bytes || 0), 0);
  $('#pc-dl-sum').textContent = done.length ? tn(done.length, '{n} aflevering', '{n} afleveringen') + ' · ' + fmtB(bytes) : t(pcDl.some(pcDlBusy) ? 'Bezig met downloaden' : 'Downloaden mislukt');
  // Zelfde rijen als net (alleen voortgang anders): tekst en balk bijwerken, de lijst niet opnieuw opbouwen
  const sig = pcDl.map(d => d.key + ':' + pcDlBar(d)).join('|');
  const rows = $('#pc-dl').children;
  if (sig === pcDlListSig && rows.length === pcDl.length) {
    pcDl.forEach((d, i) => {
      const sm = rows[i].querySelector('small'); if (sm) sm.textContent = pcDlLine(d);
      const bar = rows[i].querySelector('.pcprog i'); if (bar) bar.style.width = Math.min(100, d.bytes / d.total * 100).toFixed(1) + '%';
    });
    return;
  }
  pcDlListSig = sig;
  $('#pc-dl').innerHTML = pcDl.map((d, i) => {
    const e = d.ep || {}, p = d.pod || {};
    return '<div class="pcep"><button class="pcepb" onclick="pcDlPlay(' + i + ')">' + pcArt(e.image || p.image) +
      '<span class="pctxt"><b>' + esc(e.title || 'Aflevering') + '</b><small>' + esc(pcDlLine(d)) + '</small>' +
      (pcDlBar(d) ? '<span class="pcprog"><i style="width:' + Math.min(100, d.bytes / d.total * 100).toFixed(1) + '%"></i></span>' : '') + '</span></button>' +
      '<button class="pcmore" onclick="pcDlMenu(' + i + ')" aria-label="' + t('Meer over {titel}', { titel: esc(e.title || t('aflevering')) }) + '">⋯</button></div>';
  }).join('');
}
function pcDlPlay(i){ const d = pcDl[i]; if (d) pcPlayEp(d.ep, d.pod, false); }
function pcDlMenu(i){
  const d = pcDl[i]; if (!d) return;
  const acts = [['Afspelen', () => pcPlayEp(d.ep, d.pod, false)]];
  if (d.pod && d.pod.feed) acts.push(['Naar de podcast', () => pdOpen(d.pod)]);
  openSheet(d.ep.title || 'Aflevering', [d.pod && d.pod.title, pcDlTxt(d)].filter(Boolean).join(' · '), acts.concat(pcDlActs(d.ep, d.pod)));
}
function pcDlSettings(){ jumpTo('podset'); } // de instellingen voor downloaden staan op Instellingen van Podcasts
function pcDlClearAsk(){ askConfirm('Alle downloads verwijderen?', 'De afleveringen blijven gewoon te beluisteren via internet. Podcasts met "nieuwe afleveringen downloaden" halen de nieuwste later opnieuw op.', 'Verwijderen', () => { Android.podDlClear(); pcDlRefresh(); toast('Downloads verwijderd'); }); }

/* Eén podcast: nieuwe afleveringen vanzelf downloaden (knop naast Volgend, en in het menu ⋯) */
function pdDlOn(){ const s = rdJson(Android.podSubs(), []).find(x => pdPod && x.id === pdPod.id); return !!(s && s.dl); }
function pdDlRow(subscribed){
  const b = $('#pd-dl'); if (!b) return;
  b.hidden = !subscribed;
  const on = !!subscribed && pdDlOn();
  b.setAttribute('aria-checked', on ? 'true' : 'false');
  b.innerHTML = uiIcon(on ? 'check' : 'download') + '<span class="lang">' + t('Vanzelf downloaden') + '</span><span class="kort">' + t('Downloaden') + '</span>';
}
function pdDlToggle(){
  if (!pdPod) return;
  const on = $('#pd-dl').getAttribute('aria-checked') !== 'true';
  const e = Android.podAutoDl(pdPod.id, on);
  if (e) { toast(e); return; }
  pdDlRow(true);
  const pr = rdJson(Android.podDlPrefs(), {});
  toast(on ? t(pr.wifiOnly ? '↓ De nieuwste 3 die je nog niet beluisterde, worden gedownload (via wifi)' : '↓ De nieuwste 3 die je nog niet beluisterde, worden gedownload') : t('Niet meer vanzelf downloaden'));
}

/* ---------- Instellingen van Podcasts (scherm podset): na een aflevering, en downloaden ---------- */
/** Uitleg bij de keuze "na een aflevering". */
function psUitleg(na){
  return na === 'stop' ? t('De speler stopt na elke aflevering.')
    : na === 'hierna' ? t('De speler gaat verder met Hierna; is die leeg, dan stopt hij.')
    : na === 'nieuw' ? t('Eerst Hierna, dan de volgende van dezelfde podcast, en daarna de nieuwste aflevering die je nog niet hoorde van wat je volgt.')
    : t('Eerst Hierna; is die leeg, dan de volgende aflevering van dezelfde podcast.');
}
function psNa(){ return typeof Android.podNa === 'function' ? rdJson(Android.podNa(), {}) : {}; }
function enterPodSet(){ psRender(); }
function psRender(){
  const n = psNa(), pr = rdJson(Android.podDlPrefs(), {}), na = n.na || 'podcast';
  $('#ps-na').value = na;
  $('#ps-na-uitleg').textContent = psUitleg(na);
  const tg = (id, on) => { const b = $(id); b.classList.toggle('on', !!on); b.setAttribute('aria-checked', on ? 'true' : 'false'); };
  tg('#ps-naslaap', n.naSlaap !== false); tg('#ps-wifi', !!pr.wifiOnly); tg('#ps-autodel', !!pr.autoDelete);
  $('#ps-next').textContent = psNextTxt(n);
}
/** "Daarna: <titel> (<podcast>)" of waarom er niets komt. Zuiver. */
function psNextTxt(n){
  if (!n || !n.nu) return '';
  const e = n.next && n.next.ep, p = n.next && n.next.pod;
  if (e) return t('Na deze aflevering: {titel}', { titel: e.title + (p && p.title ? ' · ' + p.title : '') });
  return n.nu === 'stop' ? t('Na deze aflevering stopt de speler.') : '';
}
function psSave(){ const n = psNa(); Android.podNaSet($('#ps-na').value, n.naSlaap !== false); psRender(); if (typeof plQueueSig !== 'undefined') { plQueueSig = ''; if (typeof plRender === 'function') plRender(); } }
function psSlaap(){ const n = psNa(); Android.podNaSet(n.na || 'podcast', n.naSlaap === false); psRender(); }
function psDl(k){ const pr = rdJson(Android.podDlPrefs(), {}); Android.podDlPref(k, !pr[k]); psRender(); }
