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
  if (d.state === 'failed') return '⚠ ' + (d.reason || 'Downloaden mislukt');
  if (d.state === 'paused') return '↓ ' + (d.reason || 'Gepauzeerd');
  if (d.state === 'queued') return '↓ In de wachtrij';
  return '↓ ' + (d.total > 0 ? Math.min(99, Math.floor(d.bytes / d.total * 100)) + '%' : 'Bezig');
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
  $('#pc-tab-dl').setAttribute('aria-label', 'Downloads' + (pcDl.length ? ', ' + pcDl.length : ''));
  const done = pcDl.filter(d => d.state === 'done'), bytes = done.reduce((a, d) => a + (+d.bytes || 0), 0);
  $('#pc-dl-sum').textContent = done.length ? done.length + (done.length === 1 ? ' aflevering' : ' afleveringen') + ' · ' + fmtB(bytes) : pcDl.some(pcDlBusy) ? 'Bezig met downloaden' : 'Downloaden mislukt';
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
      '<button class="pcmore" onclick="pcDlMenu(' + i + ')" aria-label="Meer over ' + esc(e.title || 'aflevering') + '">⋯</button></div>';
  }).join('');
}
function pcDlPlay(i){ const d = pcDl[i]; if (d) pcPlayEp(d.ep, d.pod, false); }
function pcDlMenu(i){
  const d = pcDl[i]; if (!d) return;
  const acts = [['Afspelen', () => pcPlayEp(d.ep, d.pod, false)]];
  if (d.pod && d.pod.feed) acts.push(['Naar de podcast', () => pdOpen(d.pod)]);
  openSheet(d.ep.title || 'Aflevering', [d.pod && d.pod.title, pcDlTxt(d)].filter(Boolean).join(' · '), acts.concat(pcDlActs(d.ep, d.pod)));
}
function pcDlSettings(){
  const pr = rdJson(Android.podDlPrefs(), {});
  openSheet('Downloads', 'Voor offline luisteren', [
    [(pr.wifiOnly ? '✓ ' : '') + 'Alleen via wifi downloaden', () => { Android.podDlPref('wifiOnly', !pr.wifiOnly); toast(!pr.wifiOnly ? 'Downloaden alleen via wifi' : 'Downloaden ook via mobiel internet'); }],
    [(pr.autoDelete ? '✓ ' : '') + 'Beluisterde afleveringen vanzelf verwijderen', () => { Android.podDlPref('autoDelete', !pr.autoDelete); toast(!pr.autoDelete ? 'Beluisterd: download gaat vanzelf weg' : 'Downloads blijven staan'); }],
    ['Alle downloads verwijderen…', () => askConfirm('Alle downloads verwijderen?', 'De afleveringen blijven gewoon te beluisteren via internet. Podcasts met "nieuwe afleveringen downloaden" halen de nieuwste later opnieuw op.', 'Verwijderen', () => { Android.podDlClear(); pcDlRefresh(); toast('Downloads verwijderd'); })]
  ]);
}

/* Eén podcast: nieuwe afleveringen vanzelf downloaden */
function pdDlRow(subscribed){
  const row = $('#pd-dl-row'); if (!row) return;
  row.style.display = subscribed ? '' : 'none';
  const s = rdJson(Android.podSubs(), []).find(x => pdPod && x.id === pdPod.id);
  const on = !!(s && s.dl);
  $('#pd-dl').classList.toggle('on', on); $('#pd-dl').setAttribute('aria-checked', on ? 'true' : 'false');
}
function pdDlToggle(){
  if (!pdPod) return;
  const on = !$('#pd-dl').classList.contains('on');
  const e = Android.podAutoDl(pdPod.id, on);
  if (e) { toast(e); return; }
  pdDlRow(true);
  const pr = rdJson(Android.podDlPrefs(), {});
  toast(on ? '↓ De nieuwste afleveringen worden gedownload' + (pr.wifiOnly ? ' (via wifi)' : '') : 'Niet meer vanzelf downloaden');
}
