/* ---------- Meldingsgeschiedenis ---------- */
let historyPoll = null, historySearchTimer = null, historyExportBusy = false, historyStamp = '';
/* Elke 3 s alleen een klein controlegetal ophalen; de lijst pas opnieuw laden als er iets veranderd is. */
function historyTick(){
  let s = ''; try { s = typeof Android.historyStamp === 'function' ? String(Android.historyStamp()) : ''; } catch(e){}
  if (!s || s !== historyStamp) loadHistory();
}
function enterHistory(){ loadHistory(); stopHistoryPoll(); historyPoll = setInterval(historyTick, 3000); }
function stopHistoryPoll(){ if (historyPoll) clearInterval(historyPoll); historyPoll = null; clearTimeout(historySearchTimer); }
function loadHistory(){
  try { historyStamp = typeof Android.historyStamp === 'function' ? String(Android.historyStamp()) : ''; } catch(e){ historyStamp = ''; }
  let data;
  try { data = JSON.parse(Android.historyList($('#history-search').value)); } catch(e){ data = {error:'Geschiedenis laden lukt niet'}; }
  $('#history-enabled').checked = !!data.enabled;
  $('#history-state').textContent = data.error || (!data.enabled ? 'Bewaren staat uit.' : !data.allowed ? 'Geef Rene’s Tools meldingentoegang in Android.' : !data.connected ? 'Wachten op de verbinding met Android. Controleer meldingentoegang als dit blijft staan.' : 'Nieuwe meldingen worden bewaard.');
  $('#history-permission').textContent = data.allowed ? 'Meldingentoegang beheren' : 'Meldingentoegang instellen';
  $('#history-folder').style.display = data.dest ? 'none' : 'block';
  $('#history-export').disabled = historyExportBusy || !data.dest || !data.total;
  $('#history-clear').disabled = !data.total;
  const rows = data.rows || [];
  $('#history-count').textContent = (data.count || 0) + ' van ' + (data.total || 0) + ' meldingen' + (data.count > rows.length ? ' · nieuwste ' + rows.length + ' getoond' : '');
  $('#history-list').innerHTML = rows.map(r => '<article class="card history-entry"><small>' + esc(r.app || r.package) + ' · ' + esc(new Date(r.time).toLocaleString('nl-NL')) + '</small><h2>' + esc(r.title || 'Melding') + '</h2><p style="white-space:pre-wrap;overflow-wrap:anywhere">' + esc(r.text || '') + '</p></article>').join('') || '<p class="note">' + ($('#history-search').value ? 'Geen meldingen gevonden.' : 'Nog geen meldingen bewaard. Schakel bewaren in en geef meldingentoegang om te beginnen.') + '</p>';
}
function historySearch(){ clearTimeout(historySearchTimer); historySearchTimer = setTimeout(loadHistory, 200); }
function historyEnable(on){ Android.historySetEnabled(on); loadHistory(); }
function historyExport(){
  if (historyExportBusy) return;
  const error = Android.historyExport();
  if (error) { $('#history-result').textContent = error; return; }
  historyExportBusy = true; $('#history-result').textContent = 'Bezig met exporteren…'; loadHistory();
}
window.onHistoryExport = function(result){ historyExportBusy = false; $('#history-result').textContent = result; if (current === 'history') loadHistory(); };
function historyClear(){ askConfirm('Meldingsgeschiedenis wissen?', 'Verwijdert alle bewaarde meldingen op deze telefoon. Je backups blijven staan. Nieuwe meldingen worden nog steeds bewaard als opnemen aanstaat.', 'Wissen', () => { const error = Android.historyClear(); if (error) toast(error); loadHistory(); }); }
