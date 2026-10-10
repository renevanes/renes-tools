/* ---------- Spotify-backup ---------- */
let spSt = {}, spPl = [], spGone = [], spPollT = null;
function spDate(t){ return t ? new Date(t).toLocaleString('nl-NL', { day: 'numeric', month: 'short', year: new Date(t).getFullYear() === new Date().getFullYear() ? undefined : 'numeric', hour: '2-digit', minute: '2-digit' }) : ''; }
function enterSpotify(){ spRender(); if (!spPollT) spPollT = setInterval(() => { if (current !== 'spotify') { clearInterval(spPollT); spPollT = null; return; } spState(); }, 1000); }
function spState(){
  const prev = spSt.busy;
  spSt = rdJson(Android.spotifyState(), {});
  $('#sp-progress').textContent = spSt.busy ? (spSt.progress || 'Bezig…') : '';
  $('#sp-now').disabled = !!spSt.busy;
  if (prev && !spSt.busy) spRender();
}
function spRender(){
  spState();
  const linked = !!spSt.linked;
  $('#sp-setup').style.display = linked ? 'none' : 'block';
  $('#sp-main').style.display = linked ? 'block' : 'none';
  if (!linked && spSt.clientId && !$('#sp-client').value) $('#sp-client').value = spSt.clientId;
  $('#sp-user').textContent = spSt.user ? 'Gekoppeld: ' + spSt.user : 'Gekoppeld met Spotify';
  $('#sp-last').textContent = spSt.lastFailAt > (spSt.lastAt || 0) ? '⚠ Laatste poging (' + spDate(spSt.lastFailAt) + '): ' + spSt.lastMsg
    : spSt.lastAt ? 'Laatste backup ' + spDate(spSt.lastAt) + ' · ' + spSt.own + ' playlists, ' + spSt.tracks + ' nummers' : 'Nog geen backup';
  $('#sp-last').classList.toggle('bad', spSt.lastFailAt > (spSt.lastAt || 0));
  $('#sp-auto').value = spSt.auto || 'day';
  $('#sp-folder').textContent = spSt.folder && spSt.encrypted ? 'Versleutelen staat aan: de backup blijft in de app. Een leesbare kopie in je backup-map kan via ⋯.' : spSt.folder ? 'Ook in je backup-map, map Spotify: per playlist een CSV-bestand, spotify-backup.json en wijzigingen.txt.' : 'Tip: kies een backup-map (Instellingen), dan komt er ook een kopie buiten de app.';
  spPl = rdJson(Android.spotifyPlaylists(), []);
  spGone = rdJson(Android.spotifyGone(), []);
  const own = spPl.filter(p => p.own), fol = spPl.filter(p => !p.own);
  $('#sp-lists').style.display = spSt.hasBackup ? 'block' : 'none';
  $('#sp-empty').style.display = spSt.hasBackup ? 'none' : 'block';
  $('#sp-import').style.display = !spSt.hasBackup && spSt.folder ? 'block' : 'none';
  const row = (p, kind, i) => '<div class="srow"><button class="sbtn" onclick="spOpen(\'' + kind + '\',' + i + ')">' + pcArt(p.img) +
    '<span class="stxt"><b>' + esc(p.name) + '</b><small>' + esc((p.n != null ? p.n + (p.n === 1 ? ' nummer' : ' nummers') : '') + (kind === 'f' && p.owner ? ' · van ' + p.owner : '') + (kind === 'g' ? ' · laatst gezien ' + spDate(p.t) : '') + (p.coll ? ' · samen' : '')) + '</small></span></button></div>';
  $('#sp-own').innerHTML = own.map((p, i) => row(p, 'o', i)).join('') || '<p class="note" style="padding:14px">Geen eigen playlists.</p>';
  $('#sp-gone-wrap').style.display = spGone.length ? 'block' : 'none';
  $('#sp-gone').innerHTML = spGone.map((p, i) => row(p, 'g', i)).join('');
  $('#sp-fol-wrap').style.display = fol.length ? 'block' : 'none';
  $('#sp-fol').innerHTML = fol.map((p, i) => row(p, 'f', i)).join('');
  $('#sp-log').textContent = Android.spotifyLog() || 'Nog niets veranderd.';
}
function spLink(){
  const id = $('#sp-client').value.trim();
  $('#sp-link-msg').textContent = '';
  if (!/^[0-9a-fA-F]{32}$/.test(id)) { $('#sp-link-msg').textContent = 'Een Client ID is 32 tekens (cijfers en a–f). Kopieer hem uit je developer app.'; return; }
  const e = Android.spotifyLink(id);
  if (e) { $('#sp-link-msg').textContent = e; return; }
  toast('Log in bij Spotify en kom daarna terug');
}
window.onSpotifyLinked = function(r){
  if (r.error) { $('#sp-link-msg').textContent = r.error; toast(r.error); return; }
  toast('✓ Gekoppeld met ' + (r.name || 'Spotify'));
  if (current === 'spotify') { spRender(); spBackup(); }
};
function spBackup(plain){ if (spSt.busy) return; Android.spotifyBackup(!!plain); spSt.busy = true; $('#sp-now').disabled = true; $('#sp-progress').textContent = 'Bezig…'; }
/* Versleutelen staat aan: alleen na bevestigen leesbaar in de backup-map */
function spPlainCopy(){ if (!plainOk('De Spotify-backup', spPlainCopy)) { return; } spBackup(true); }
window.onSpotifyDone = function(r){
  spSt.busy = false;
  if (r.error) toast(r.error);
  else toast(r.changed ? '✓ Backup gemaakt' + ((r.lines || []).length ? ': ' + r.lines.length + (r.lines.length === 1 ? ' wijziging' : ' wijzigingen') : '') : '✓ Backup bijgewerkt; niets veranderd');
  if (r.folder === 'encrypted') setTimeout(() => toast('Niet in de backup-map gezet: versleutelen staat aan (zie ⋯)'), 2800);
  else if (r.folder && r.folder !== 'ok') toast(r.folder);
  if (current === 'spotify') spRender();
};
function spImport(){ Android.spotifyImport(); $('#sp-import').disabled = true; }
window.onSpotifyImported = function(r){ $('#sp-import').disabled = false; toast(r.error || '✓ ' + r.count + ' playlists overgenomen'); if (current === 'spotify') spRender(); };
function spMenu(){
  openSheet('Spotify', spSt.user || '', [
    ['Wat er veranderde', () => { $('#sp-log-wrap').open = true; $('#sp-log-wrap').scrollIntoView({ behavior: 'smooth' }); }]].concat(spSt.encrypted && spSt.folder ? [
    ['Kopie in de backup-map (onversleuteld)', () => spPlainCopy()]] : []).concat([
    ['Ontkoppelen', () => askConfirm('Ontkoppelen?', 'De backups blijven op je telefoon en in je backup-map. Vanzelf backuppen stopt.', 'Ontkoppelen', () => { Android.spotifyUnlink(); spRender(); })]]));
}
/* Een playlist: link, versies en terugzetten */
function spOpen(kind, i){
  const p = kind === 'o' ? spPl.filter(x => x.own)[i] : kind === 'f' ? spPl.filter(x => !x.own)[i] : spGone[i];
  if (!p) return;
  if (kind === 'f') { openSheet(p.name, p.owner ? 'Van ' + p.owner : '', [['Openen in Spotify', () => Android.openUrl(p.url)]]); return; }
  const vers = rdJson(Android.spotifyVersions(p.id), []);
  const acts = [];
  if (p.url && kind === 'o') acts.push(['Openen in Spotify', () => Android.openUrl(p.url)]);
  vers.forEach((v, k) => acts.push([(k === 0 ? (kind === 'g' ? 'Laatste versie' : 'Nu') : 'Versie') + ' van ' + spDate(v.t) + ' · ' + v.n + ' nummers' + (v.plus || v.min ? ' (+' + (v.plus || 0) + ' / −' + (v.min || 0) + ' t.o.v. daarvoor)' : ''), () => spVersion(p, v, kind)]));
  openSheet(p.name, vers.length > 1 ? vers.length + ' versies in de backup' : 'Eén versie in de backup', acts);
}
function spVersion(p, v, kind){
  const tr = rdJson(Android.spotifyTracks(p.id, v.snap), { total: 0, items: [] });
  const acts = [['Terugzetten als nieuwe playlist', () => spRestore(p, v, 'new')]];
  if (kind === 'o') acts.push(['De huidige playlist precies zo terugzetten…', () => askConfirm('Playlist overschrijven?', '"' + p.name + '" in Spotify krijgt weer precies de ' + tr.total + ' nummers van ' + spDate(v.t) + '. Wat er daarna bij kwam, gaat eruit.', 'Overschrijven', () => spRestore(p, v, 'replace'))]);
  acts.push(['Nummers bekijken', () => askConfirm(p.name + ' · ' + spDate(v.t), tr.items.map((t, n) => (n + 1) + '. ' + t.n + (t.a ? ' – ' + t.a : '') + (t.l ? ' (lokaal bestand)' : '')).join('\n') + (tr.total > tr.items.length ? '\n…' : '') || 'Leeg', 'Sluiten', () => {})]);
  openSheet(p.name, spDate(v.t) + ' · ' + tr.total + ' nummers', acts);
}
function spRestore(p, v, mode){
  if (spSt.busy) { toast('Even wachten: er loopt nog iets'); return; }
  Android.spotifyRestore(p.id, v.snap, mode); spSt.busy = true; $('#sp-progress').textContent = 'Terugzetten…';
}
window.onSpotifyRestored = function(r){
  spSt.busy = false;
  if (r.error) { toast(r.error); return; }
  toast('✓ ' + r.added + (r.added === 1 ? ' nummer' : ' nummers') + ' teruggezet' + (r.skipped ? ' (' + r.skipped + (r.skipped === 1 ? ' lokaal bestand kan' : ' lokale bestanden kunnen') + ' niet terug)' : ''));
  if (r.url) setTimeout(() => openSheet('Teruggezet', r.name, [['Openen in Spotify', () => Android.openUrl(r.url)]]), 400);
};
