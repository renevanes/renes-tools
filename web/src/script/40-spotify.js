/* ---------- Spotify-backup ---------- */
let spSt = {}, spPl = [], spGone = [], spPollT = null;
function spDate(ms){ return ms ? fmtDT(ms, { day: 'numeric', month: 'short', year: rgParts(ms).y === rgParts().y ? undefined : 'numeric', hour: '2-digit', minute: '2-digit' }) : ''; }
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
  $('#sp-user').textContent = spSt.user ? t('Gekoppeld: {naam}', { naam: spSt.user }) : t('Gekoppeld met Spotify');
  $('#sp-last').textContent = spSt.lastFailAt > (spSt.lastAt || 0) ? t('Laatste poging ({wanneer}): {fout}', { wanneer: spDate(spSt.lastFailAt), fout: String(spSt.lastMsg || '').length > 200 ? String(spSt.lastMsg).slice(0, 200) + '…' : t(spSt.lastMsg) })
    : spSt.lastAt ? t('Laatste backup {wanneer} · {n} playlists, {nummers} nummers', { wanneer: spDate(spSt.lastAt), n: spSt.own, nummers: spSt.tracks }) : t('Nog geen backup');
  $('#sp-last').classList.toggle('bad', spSt.lastFailAt > (spSt.lastAt || 0));
  $('#sp-auto').value = spSt.auto || 'day';
  $('#sp-folder').textContent = spSt.folder && spSt.encrypted ? 'Versleutelen staat aan: de backup blijft in de app. Een leesbare kopie in je backup-map kan via ⋯.' : spSt.folder ? 'Ook in je backup-map, map Spotify: per playlist een CSV-bestand, je bibliotheek in de map Bibliotheek, spotify-backup.json en wijzigingen.txt.' : 'Tip: kies een backup-map (Instellingen), dan komt er ook een kopie buiten de app.';
  const fe = spSt.folder && !spSt.encrypted ? String(spSt.folderErr || '') : '';
  if (fe) $('#sp-folder').textContent = t('Niet in je backup-map gezet: {fout}', { fout: fe.length > 160 ? fe.slice(0, 160) + '…' : t(fe) });
  $('#sp-folder').classList.toggle('bad', !!fe);
  $('#sp-repick').style.display = fe ? 'block' : 'none';
  spPl = rdJson(Android.spotifyPlaylists(), []);
  spGone = rdJson(Android.spotifyGone(), []);
  const own = spPl.filter(p => p.own), fol = spPl.filter(p => !p.own);
  $('#sp-lists').style.display = spSt.hasBackup ? 'block' : 'none';
  $('#sp-empty').style.display = spSt.hasBackup ? 'none' : 'block';
  $('#sp-import').style.display = !spSt.hasBackup && spSt.folder ? 'block' : 'none';
  const row = (p, kind, i) => '<div class="srow"><button class="sbtn" onclick="spOpen(\'' + kind + '\',' + i + ')">' + pcArt(p.img) +
    '<span class="stxt"><b>' + esc(p.name) + '</b><small>' + esc((p.n != null ? tn(p.n, '{n} nummer', '{n} nummers') : '') + (kind === 'f' && p.owner ? ' · ' + t('van {naam}', { naam: p.owner }) : '') + (kind === 'g' ? ' · ' + t('laatst gezien {wanneer}', { wanneer: spDate(p.t) }) : '') + (p.coll ? ' · ' + t('samen') : '')) + '</small></span></button></div>';
  $('#sp-own').innerHTML = own.map((p, i) => row(p, 'o', i)).join('') || '<p class="note" style="padding:14px">Geen eigen playlists.</p>';
  $('#sp-gone-wrap').style.display = spGone.length ? 'block' : 'none';
  $('#sp-gone').innerHTML = spGone.map((p, i) => row(p, 'g', i)).join('');
  $('#sp-fol-wrap').style.display = fol.length ? 'block' : 'none';
  $('#sp-fol').innerHTML = fol.map((p, i) => row(p, 'f', i)).join('');
  $('#sp-log').textContent = Android.spotifyLog() || 'Nog niets veranderd.';
  spRenderLib();
}
/* Bibliotheek: leuk gevonden nummers, albums, artiesten, podcasts, top, laatst geluisterd */
let spLibD = {};
const SP_LIB_IC = { tracks:'music',albums:'music',artists:'mic',shows:'mic',episodes:'headphones',audiobooks:'document',recent:'clock' };
function spRenderLib(){
  spLibD = rdJson(Android.spotifyLib(), {});
  const linked = !!spSt.linked;
  $('#sp-relink').style.display = linked && spSt.libRelink ? 'block' : 'none';
  const ks = (spLibD.kinds || []).filter(k => k.n >= 0);
  $('#sp-libwrap').style.display = ks.length ? 'block' : 'none';
  const noun = k => k.kind === 'artists' ? (k.n === 1 ? ' artiest' : ' artiesten') : k.kind === 'albums' ? ' albums' : k.kind === 'shows' ? ' podcasts' : k.kind === 'episodes' ? ' afleveringen' : k.kind === 'audiobooks' ? ' boeken' : k.kind.startsWith('top-artists') ? ' artiesten' : (k.n === 1 ? ' nummer' : ' nummers');
  $('#sp-lib').innerHTML = ks.map(k => '<div class="srow"><button class="sbtn" data-k="' + esc(k.kind) + '" onclick="spLibOpen(this.dataset.k)"><span class="vpic" aria-hidden="true">' + uiIcon(SP_LIB_IC[k.kind] || 'music') + '</span><span class="stxt"><b>' + esc(k.name) + '</b><small>' +
    esc(k.n + ' ' + t(noun(k).trim()) + (k.gone ? ' · ' + t('{n} uit Spotify verdwenen (bewaard)', { n: k.gone }) : '')) + '</small></span></button></div>').join('');
}
function spRelink(){ const e = Android.spotifyLink(spSt.clientId || ''); if (e) toast(e); else toast('Log in bij Spotify en geef toestemming; kom daarna terug'); }
function spLibOpen(kind){
  const k = (spLibD.kinds || []).find(x => x.kind === kind); if (!k) return;
  const acts = [['Bekijken', () => spLibShow(k, false)]];
  if (kind === 'shows' && k.n > 0) acts.push(['Overnemen in Podcasts', () => pspOpen('backup')]);
  if (k.gone) acts.push([t('Verdwenen bekijken ({n})', { n: k.gone }), () => spLibShow(k, true)],
    [t('↩ Verdwenen terugzetten in Spotify ({n})', { n: k.gone }), () => spLibRestore(k, 'gone')]);
  if (k.restore && k.n > 0) acts.push(['Alles terugzetten in Spotify…', () => askConfirm('Alles terugzetten?', t(k.gone ? 'Alle {n} uit de backup (ook de {weg} verdwenen) komen (weer) in "{naam}" in Spotify. Wat er al in staat, blijft gewoon staan. Handig voor een nieuw account.' : 'Alle {n} uit de backup komen (weer) in "{naam}" in Spotify. Wat er al in staat, blijft gewoon staan. Handig voor een nieuw account.', { n: k.n + (k.gone || 0), weg: k.gone, naam: t(k.name) }), 'Terugzetten', () => spLibRestore(k, 'all'))]);
  openSheet(t(k.name), t('{n} in de backup', { n: k.n }) + (k.gone ? ' · ' + t('{n} verdwenen', { n: k.gone }) : ''), acts);
}
function spLibShow(k, gone){
  const r = rdJson(Android.spotifyLibList(k.kind, !!gone), { total: 0, items: [] });
  const when = tt => tt ? ' · ' + String(tt).slice(0, 10) : '';
  askConfirm(t(k.name) + (gone ? ' · ' + t('verdwenen') : ''), r.items.map((tt, n) => (n + 1) + '. ' + tt.n + (tt.a ? ' – ' + tt.a : '') + (k.kind === 'recent' ? when(tt.p || tt.t) : '')).join('\n') + (r.total > r.items.length ? '\n…' : '') || t('Leeg'), 'Sluiten', () => {});
}
function spLibRestore(k, mode){
  if (spSt.busy) { toast('Even wachten: er loopt nog iets'); return; }
  Android.spotifyLibRestore(k.kind, mode); spSt.busy = true; $('#sp-progress').textContent = 'Terugzetten…';
}
window.onSpotifyLibRestored = function(r){
  spSt.busy = false;
  if (r.error) { toast(r.error); return; }
  toast(t('✓ {n} teruggezet in "{naam}"', { n: r.done, naam: t(r.name) }) + (r.skipped ? ' ' + t('({n} kunnen niet terug)', { n: r.skipped }) : ''));
  if (current === 'spotify') spRender();
};
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
  toast(t('✓ Gekoppeld met {naam}', { naam: r.name || 'Spotify' }));
  if (current === 'spotify') { spRender(); spBackup(); }
};
function spBackup(plain){ if (spSt.busy) return; Android.spotifyBackup(!!plain); spSt.busy = true; $('#sp-now').disabled = true; $('#sp-progress').textContent = 'Bezig…'; }
/* Versleutelen staat aan: alleen na bevestigen leesbaar in de backup-map */
function spPlainCopy(){ if (!plainOk('De Spotify-backup', spPlainCopy)) { return; } spBackup(true); }
window.onSpotifyDone = function(r){
  spSt.busy = false;
  if (r.error) toast(r.error);
  else toast(r.changed ? t('✓ Backup gemaakt') + ((r.lines || []).length ? ': ' + tn(r.lines.length, '{n} wijziging', '{n} wijzigingen') : '') : t('✓ Backup bijgewerkt; niets veranderd'));
  if (!r.error && r.lib && (r.lib.errors || []).length) setTimeout(() => toast(t('Niet alles lukte: {fout}', { fout: t(r.lib.errors[0]) })), 2800);
  if (r.folder === 'encrypted') setTimeout(() => toast('Niet in de backup-map gezet: versleutelen staat aan (zie ⋯)'), 2800);
  else if (r.folder && r.folder !== 'ok') { if (r.folderAccess) askConfirm('Backup-map', t('Je backup is gemaakt in de app, maar niet in je backup-map.') + '\n\n' + t(r.folder), 'Map opnieuw kiezen', () => Android.waPickFolder()); else toast(r.folder.length > 160 ? r.folder.slice(0, 160) + '…' : r.folder); }
  if (current === 'spotify') spRender();
};
function spImport(){ Android.spotifyImport(); $('#sp-import').disabled = true; }
window.onSpotifyImported = function(r){ $('#sp-import').disabled = false; toast(r.error || t('✓ {n} playlists overgenomen', { n: r.count })); if (current === 'spotify') spRender(); };
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
  if (kind === 'f') { openSheet(p.name, p.owner ? t('Van {naam}', { naam: p.owner }) : '', [['Openen in Spotify', () => Android.openUrl(p.url)]]); return; }
  const vers = rdJson(Android.spotifyVersions(p.id), []);
  const acts = [];
  if (p.url && kind === 'o') acts.push(['Openen in Spotify', () => Android.openUrl(p.url)]);
  vers.forEach((v, k) => acts.push([t(k === 0 ? (kind === 'g' ? 'Laatste versie van {wanneer}' : 'Nu van {wanneer}') : 'Versie van {wanneer}', { wanneer: spDate(v.t) }) + ' · ' + t('{n} nummers', { n: v.n }) + (v.plus || v.min ? ' ' + t('(+{plus} / −{min} t.o.v. daarvoor)', { plus: v.plus || 0, min: v.min || 0 }) : ''), () => spVersion(p, v, kind)]));
  openSheet(p.name, vers.length > 1 ? t('{n} versies in de backup', { n: vers.length }) : t('Eén versie in de backup'), acts);
}
function spVersion(p, v, kind){
  const tr = rdJson(Android.spotifyTracks(p.id, v.snap), { total: 0, items: [] });
  const acts = [['Terugzetten als nieuwe playlist', () => spRestore(p, v, 'new')]];
  if (kind === 'o') acts.push(['De huidige playlist precies zo terugzetten…', () => askConfirm('Playlist overschrijven?', t('"{naam}" in Spotify krijgt weer precies de {n} nummers van {wanneer}. Wat er daarna bij kwam, gaat eruit.', { naam: p.name, n: tr.total, wanneer: spDate(v.t) }), 'Overschrijven', () => spRestore(p, v, 'replace'))]);
  acts.push(['Nummers bekijken', () => askConfirm(p.name + ' · ' + spDate(v.t), tr.items.map((tt, n) => (n + 1) + '. ' + tt.n + (tt.a ? ' – ' + tt.a : '') + (tt.l ? ' ' + t('(lokaal bestand)') : '')).join('\n') + (tr.total > tr.items.length ? '\n…' : '') || t('Leeg'), 'Sluiten', () => {})]);
  openSheet(p.name, spDate(v.t) + ' · ' + t('{n} nummers', { n: tr.total }), acts);
}
function spRestore(p, v, mode){
  if (spSt.busy) { toast('Even wachten: er loopt nog iets'); return; }
  Android.spotifyRestore(p.id, v.snap, mode); spSt.busy = true; $('#sp-progress').textContent = 'Terugzetten…';
}
window.onSpotifyRestored = function(r){
  spSt.busy = false;
  if (r.error) { toast(r.error); return; }
  toast(tn(r.added, '✓ {n} nummer teruggezet', '✓ {n} nummers teruggezet') + (r.skipped ? ' ' + tn(r.skipped, '({n} lokaal bestand kan niet terug)', '({n} lokale bestanden kunnen niet terug)') : ''));
  if (r.url) setTimeout(() => openSheet('Teruggezet', r.name, [['Openen in Spotify', () => Android.openUrl(r.url)]]), 400);
};
