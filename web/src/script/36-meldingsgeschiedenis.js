/* ---------- Meldingsgeschiedenis ---------- */
let historyData = {}, historyPoll = null, historySearchTimer = null, historyExportBusy = false, historyStamp = '', historyPkg = '', historyLimit = 200;
/* Elke 3 s alleen een klein controlegetal ophalen; de lijst pas opnieuw laden als er iets veranderd is. */
function historyTick(){
  let s = ''; try { s = typeof Android.historyStamp === 'function' ? String(Android.historyStamp()) : ''; } catch(e){}
  if (!s || s !== historyStamp) loadHistory();
}
function enterHistory(){ historyLimit = 200; loadHistory(); stopHistoryPoll(); historyPoll = setInterval(historyTick, 3000); }
function stopHistoryPoll(){ if (historyPoll) clearInterval(historyPoll); historyPoll = null; clearTimeout(historySearchTimer); }
function loadHistory(){
  try { historyStamp = typeof Android.historyStamp === 'function' ? String(Android.historyStamp()) : ''; } catch(e){ historyStamp = ''; }
  let data;
  try { data = JSON.parse(typeof Android.historyListBy === 'function' ? Android.historyListBy($('#history-search').value, historyPkg, historyLimit) : Android.historyList($('#history-search').value)); } catch(e){ data = {error:'Geschiedenis laden lukt niet'}; }
  renderHistoryApps();
  $('#history-enabled').checked = !!data.enabled;
  $('#history-state').textContent = data.error || (!data.enabled ? 'Bewaren staat uit.' : !data.allowed ? 'Geef Tools meldingentoegang in Android.' : !data.connected ? 'Wachten op de verbinding met Android. Controleer meldingentoegang als dit blijft staan.' : 'Nieuwe meldingen worden bewaard.');
  historyData = data;
  // Bovenaan een compacte balk met de schakelaar; opvallend zolang er niets bewaard wordt
  $('#history-bar').classList.toggle('warn', !!data.error || !data.enabled || !data.allowed);
  $('#history-permission').style.display = data.enabled && !data.allowed ? 'block' : 'none';
  const rows = data.rows || [];
  $('#history-count').textContent = t('{n} van {totaal} meldingen', { n: data.count || 0, totaal: data.total || 0 }) + (data.count > rows.length ? ' · ' + t('nieuwste {n} getoond', { n: rows.length }) : '');
  $('#history-more').style.display = data.count > rows.length ? 'block' : 'none';
  $('#history-list').innerHTML = rows.map(r => '<article class="card history-entry" translate="no"><small>' + esc(r.app || r.package) + ' · ' + esc(fmtDT(r.time)) + '</small><h2>' + esc(r.title || t('Melding')) + '</h2><p style="white-space:pre-wrap;overflow-wrap:anywhere">' + esc(r.text || '') + '</p></article>').join('') || ($('#history-search').value ? uiEmpty('search', t('Geen meldingen gevonden.')) : uiEmpty('bell', t('Nog geen meldingen bewaard. Schakel bewaren in en geef meldingentoegang om te beginnen.')));
}
/* Filter per app (bijv. WhatsApp of Drive): knoppen met het aantal meldingen, meeste eerst. */
function renderHistoryApps(){
  const box = $('#history-apps'); if (!box || typeof Android.historyApps !== 'function') return;
  let apps = []; try { apps = JSON.parse(Android.historyApps()) || []; } catch(e){}
  if (historyPkg && !apps.some(a => a.package === historyPkg)) historyPkg = '';
  box.style.display = apps.length > 1 ? 'flex' : 'none';
  box.innerHTML = '<button class="' + (historyPkg ? '' : 'on') + '" data-p="" aria-pressed="' + !historyPkg + '" onclick="historyFilter(this.dataset.p)">Alle</button>' +
    apps.map(a => '<button class="' + (a.package === historyPkg ? 'on' : '') + '" data-p="' + esc(a.package) + '" aria-pressed="' + (a.package === historyPkg) + '" onclick="historyFilter(this.dataset.p)">' + esc(a.app || a.package) + ' <small>' + a.count + '</small></button>').join('');
}
function historyFilter(p){ historyPkg = p || ''; historyLimit = 200; loadHistory(); $('#history-list').scrollIntoView({block: 'start'}); }
function historyMore(){ historyLimit += 300; loadHistory(); }
function historySearch(){ clearTimeout(historySearchTimer); historySearchTimer = setTimeout(loadHistory, 200); }
function historyEnable(on){ Android.historySetEnabled(on); loadHistory(); }
/* Menu ⋯ in de kop: exporteren, backup-map, meldingentoegang en wissen */
function historyMenu(){
  const d = historyData || {}, acts = [['Geschiedenis exporteren', historyExport, 'upload']];
  if (!d.dest) acts.push(['Backup-map kiezen', () => { Android.waPickFolder(); setTimeout(loadHistory, 500); }, 'folder']);
  if (d.allowed) acts.push(['Meldingentoegang beheren', () => { Android.historyRequestPermission(); setTimeout(loadHistory, 500); }, 'settings']);
  acts.push(['Geschiedenis op deze telefoon wissen', historyClear, 'trash']);
  openSheet(t('Meldingsgeschiedenis'), d.total ? tn(d.total, 'Eén bewaarde melding', '{n} bewaarde meldingen') : t('Nog niets bewaard'), acts);
}
/* Backup-map kiezen hoort bij Instellingen: in een losse app niet (en dan mag die aanroep ook niet in historyExport
   staan, anders haalt de losse app Exporteren uit het menu: soloSrcOk leest de broncode van de keuze). */
function historyPickFolder(){ if (!SOLO) { Android.waPickFolder(); setTimeout(loadHistory, 500); } }
function historyExport(){
  if (historyExportBusy) return;
  const d = historyData || {};
  if (!d.total) { toast('Er zijn nog geen meldingen om te exporteren'); return; }
  if (!d.dest) { toast('Kies eerst een backup-map'); historyPickFolder(); return; }
  if (!plainOk('De meldingsgeschiedenis (met bijv. inlogcodes)', historyExport)) return;
  const error = Android.historyExport();
  if (error) { $('#history-result').textContent = error; return; }
  historyExportBusy = true; uiWachtTekst($('#history-result'), t('Bezig met exporteren…')); loadHistory();
}
window.onHistoryExport = function(result){ historyExportBusy = false; $('#history-result').textContent = result; if (current === 'history') loadHistory(); };
function historyClear(){ if (!(historyData || {}).total) { toast('Er is niets om te wissen'); return; } askConfirm('Meldingsgeschiedenis wissen?', 'Verwijdert alle bewaarde meldingen op deze telefoon. Je backups blijven staan. Nieuwe meldingen worden nog steeds bewaard als opnemen aanstaat.', 'Wissen', () => { const error = Android.historyClear(); if (error) toast(error); loadHistory(); }); }
