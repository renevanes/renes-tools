/* ---------- Zichtbare, handmatige microfoonopname ---------- */
let recorderPoll = null, recorderRows = [], recorderListHtml = '', recorderStarting = false, recorderLastMessage = '';
function enterRecorder(){ loadRecorder(); stopRecorderPoll(); recorderPoll = setInterval(loadRecorder, 1000); }
function stopRecorderPoll(){ if (recorderPoll) clearInterval(recorderPoll); recorderPoll = null; Android.recorderStopPlayback(); }
function loadRecorder(){
  let state; try { state = JSON.parse(Android.recorderState()); } catch(e){ state = {error:'Opnames laden lukt niet'}; }
  if (state.error) { $('#recorder-result').textContent = state.error; return; }
  if (state.busy) recorderStarting = false;
  $('#recorder-auto').checked = !!state.autoEnabled;
  $('#recorder-auto-state').textContent = state.autoEnabled ? (!state.autoReady ? 'Automatisch starten staat aan, maar de benodigde toestemmingen of meldingen ontbreken.' : 'Automatisch starten staat aan voor volgende mobiele oproepen; Android kan een handmatige start vereisen.') : 'Automatisch starten staat uit.';
  $('#recorder-permissions').style.display = state.mic && state.notifications && state.phone ? 'none' : 'block';
  $('#recorder-permissions').disabled = !!state.busy;
  $('#recorder-phone').textContent = state.busy && !state.autoStop ? 'Automatisch stoppen is niet beschikbaar; stop deze opname zelf.' : state.phone ? 'Bij het einde van een mobiel telefoongesprek wordt de opname automatisch gestopt. Andere gesprekken stop je zelf.' : 'Zonder telefoontoegang moet je de opname zelf stoppen. Geef telefoontoegang als je automatisch stoppen wilt gebruiken.';
  const rBase = state.recording ? (state.automatic ? '● Automatische opname actief' : '● Opname actief') : state.busy || recorderStarting ? 'Opname starten…' : 'Er wordt niet opgenomen.';
  $('#recorder-state').textContent = state.recording ? rBase + ' · ' + fmtMs(state.elapsed) : rBase;
  // Alleen veranderingen voorlezen (niet elke seconde de tijd)
  if ($('#recorder-live').textContent !== rBase) $('#recorder-live').textContent = rBase;
  $('#recorder-warning').style.display = state.recording && state.silent ? 'block' : 'none';
  $('#recorder-start').style.display = state.busy ? 'none' : 'block';
  $('#recorder-start').disabled = recorderStarting || !state.mic || !state.notifications;
  $('#recorder-stop').style.display = state.busy ? 'block' : 'none';
  if (state.message && state.message !== recorderLastMessage) $('#recorder-result').textContent = state.message;
  recorderLastMessage = state.message || '';
  $('#recorder-folder').style.display = state.dest ? 'none' : 'block';
  recorderRows = state.rows || [];
  $('#recorder-count').textContent = (state.total || 0) + ' opnames' + (state.total > recorderRows.length ? ' · nieuwste ' + recorderRows.length + ' getoond' : '');
  const html = recorderRows.map((r, i) => '<article style="border-top:1px solid var(--line);padding:14px 0"><h3>' + esc(new Date(r.date).toLocaleString('nl-NL')) + '</h3><p class="note">' + esc(fmtMs(r.duration)) + ' · ' + esc(fmtB(r.size)) + '</p><div class="row" style="flex-wrap:wrap">' +
    '<button class="mini" data-action="play" data-i="' + i + '"' + (state.busy ? ' disabled' : '') + '>Afspelen</button>' +
    '<button class="mini" data-action="share" data-i="' + i + '">Delen</button>' +
    '<button class="mini" data-action="export" data-i="' + i + '"' + (!state.dest ? ' disabled' : '') + '>Exporteren</button>' +
    '<button class="mini" data-action="delete" data-i="' + i + '">Verwijderen</button></div></article>').join('') || '<p class="note">Nog geen opnames. Start en stop een opname om te beginnen.</p>';
  if (html !== recorderListHtml) { $('#recorder-list').innerHTML = html; recorderListHtml = html; }
}
function recorderSetAuto(on){ const error = Android.recorderSetAuto(on); $('#recorder-result').textContent = error || ''; loadRecorder(); }
function recorderStart(){
  if (recorderStarting) return;
  recorderStarting = true; $('#recorder-result').textContent = ''; Android.recorderStart(); loadRecorder();
  setTimeout(() => { recorderStarting = false; if (current === 'recorder') loadRecorder(); }, 2000);
}
window.onRecorderMessage = function(message){ recorderStarting = false; $('#recorder-result').textContent = message; if (current === 'recorder') loadRecorder(); };
$('#recorder-list').addEventListener('click', event => {
  const button = event.target.closest('button[data-action]'); if (!button) return;
  const row = recorderRows[Number(button.dataset.i)]; if (!row) return;
  const id = row.id;
  if (button.dataset.action === 'play') { const error = Android.recorderPlay(id); if (error) toast(error); }
  if (button.dataset.action === 'share') Android.recorderShare(id);
  if (button.dataset.action === 'export') { const error = Android.recorderExport(id); $('#recorder-result').textContent = error || 'Bezig met exporteren…'; }
  if (button.dataset.action === 'delete') askConfirm('Opname verwijderen?', 'Verwijdert dit audiobestand van deze telefoon. Eerdere exports en transcripten blijven staan.', 'Verwijderen', () => { const error = Android.recorderDelete(id); if (error) toast(error); loadRecorder(); });
});
(function(){ const previous = window.onPauseApp; window.onPauseApp = function(){ if (previous) previous(); Android.recorderStopPlayback(); }; })();
