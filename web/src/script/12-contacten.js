/* ---------- Contacten ---------- */
let ctTmr = null, ctCur = null, ctCurId = '', cvCur = 0;
function ctInfo(){ try { return JSON.parse(Android.contactsInfo()); } catch(e){ return {}; } }
function ctJson(s){ try { return JSON.parse(s); } catch(e){ return {error: 'Onverwachte fout'}; } }
function enterContacts(){
  const i = ctInfo();
  $('#ct-perm').style.display = i.perm ? 'none' : 'block';
  $('#ct-perm-btn').textContent = store.get('ctAsked', false) && !i.perm ? 'Instellingen openen' : 'Toegang geven';
  $('#ct-home').style.display = i.perm ? 'block' : 'none';
  $('#ct-fab').style.display = i.perm ? 'block' : 'none';
  if (!i.perm) return;
  const sn = ctJson(Android.contactsSnapshot());
  if (sn.error) toast(sn.error); else if (sn.v > 1) toast('Wijzigingen vastgelegd als versie ' + sn.v);
  ctLoad();
}
window.onContactsChanged = function(){ if (current === 'contacts') enterContacts(); };
function ctAskPerm(){
  if (store.get('ctAsked', false) && !ctInfo().perm) { Android.openAppSettings(); return; }
  store.set('ctAsked', true); Android.contactsRequestPermission();
}
function ctSearchDo(){ clearTimeout(ctTmr); ctTmr = setTimeout(ctLoad, 250); }
function ctLoad(){
  const d = ctJson(Android.contactsList($('#ct-search').value.trim()));
  const el = $('#ct-list');
  if (d.error) { el.innerHTML = '<p class=note style="padding:14px">' + esc(d.error) + '</p>'; return; }
  $('#ct-ver').textContent = (d.total || 0).toLocaleString('nl-NL') + ' contacten' +
    (d.version ? ' · versie ' + d.version + ' van ' + fmtD(d.versionT) : '');
  const list = d.contacts || [];
  el.innerHTML = list.slice(0, 600).map(c =>
    '<button data-k="' + esc(c.k) + '" data-id="' + esc(c.id) + '" onclick="ctOpen(this.dataset.k, this.dataset.id)"><span class=r1><b>' + (c.fav ? '★ ' : '') + esc(c.n || '(zonder naam)') + '</b>' +
    (c.u ? '<time>' + esc(fmtD(c.u)) + '</time>' : '') + '</span><span class=r2>' + esc(c.sub || '') + '</span></button>').join('')
    || '<p class=note style="padding:14px">Geen contacten gevonden.</p>';
  if (list.length > 600) el.innerHTML += '<p class=note style="padding:10px 2px 4px">Nog ' + (list.length - 600) + ' contacten; zoek om ze te vinden.</p>';
}
let ctDetail = null;
function ctOpen(k, id){ ctCur = k; ctCurId = id || ''; show('contact'); ctRenderDetail(); }
function ctRenderDetail(){
  const d = ctJson(Android.contactsDetail(ctCur, String(ctCurId)));
  if (d.error) { toast(d.error); show('contacts'); return; }
  ctDetail = d; ctCur = d.k; ctCurId = String(d.id);
  $('#ctd-title').textContent = d.n || 'Contact';
  const f = d.f || {};
  $('#ctd-fields').innerHTML = Object.keys(f).filter(k => k !== 'Naam').map(k =>
    '<dt>' + esc(k) + '</dt><dd>' + esc(f[k]).replace(/\n/g, '<br>') + '</dd>').join('') +
    (d.u ? '<dt>Laatst gewijzigd</dt><dd>' + esc(fmtFull(d.u)) + '</dd>' : '');
  ctRenderCalls(d);
  const h = d.history || [];
  $('#ctd-hist').innerHTML = h.length ? h.map(x =>
    '<div class=hist><b>' + ({'+': 'Toegevoegd', '-': 'Verwijderd', '~': 'Gewijzigd'}[x.kind] || '') + '</b> <small>versie ' + x.v + ' · ' + esc(fmtFull(x.t)) + '</small>' +
    (x.kind === '~' ? ctChanges(x.d) : '') + '</div>').join('')
    : '<p class=note style="margin:0">Nog geen wijzigingen sinds de eerste versie.</p>';
}
/** Oproepen met dit contact (alle nummers van het contact). */
let ctCallNums = [];
function ctRenderCalls(d){
  const box = $('#ctd-calls');
  ctCallNums = ((d.f || {}).Telefoon || '').split('\n').map(x => x.replace(/ \([^)]*\)$/, '').trim()).filter(Boolean);
  if (!ctCallNums.length || !Android.callsHasPermission()) { box.style.display = 'none'; return; }
  let r; try { r = JSON.parse(Android.callsList(JSON.stringify({ numbers: ctCallNums }))); } catch(e){ box.style.display = 'none'; return; }
  box.style.display = 'block';
  if (!r.count) { $('#ctd-calls-body').innerHTML = '<p class=note style="margin:0">Geen oproepen met dit contact in de oproepgeschiedenis.</p>'; return; }
  $('#ctd-calls-body').innerHTML =
    '<p class=note style="margin-top:0">' + r.count + ' oproepen · ' + (r.in||0) + ' inkomend, ' + (r.out||0) + ' uitgaand, ' + (r.missed||0) + ' gemist · samen ' + fmtHours((r.secIn||0) + (r.secOut||0)) + '</p>' +
    '<div class="clist calls">' + (r.calls || []).slice(0, 5).map(c =>
      '<div class="crow ' + esc(c.kind) + '"><span class=r1><b><span class=k>' + esc(c.label) + '</span></b><time>' + esc(fmtD(c.date)) + '</time></span>' +
      '<span class=r2>' + (c.number ? esc(c.number) : '') + (c.duration ? ' · ' + fmtCallDur(c.duration) : '') + '</span></div>').join('') + '</div>' +
    '<button class="big ghost" style="margin-top:10px" onclick="callsForPerson(ctDetail.n || \'Contact\', ctCallNums)">Alle ' + r.count + ' oproepen bekijken</button>';
}
function fmtFull(t){ return new Date(t).toLocaleString('nl-NL', {day:'numeric', month:'short', year:'numeric', hour:'2-digit', minute:'2-digit'}); }
function ctChanges(d){
  return '<ul class=chg>' + (d || []).map(c => '<li><span>' + esc(c[0]) + '</span>' +
    (c[1] ? '<del>' + esc(c[1]).replace(/\n/g, '<br>') + '</del>' : '') +
    (c[2] ? '<ins>' + esc(c[2]).replace(/\n/g, '<br>') + '</ins>' : '') + '</li>').join('') + '</ul>';
}
function ctEdit(){ if (ctDetail) Android.contactsEdit(ctCur, String(ctDetail.id)); }
function enterVersions(){
  const d = ctJson(Android.contactsVersions());
  const el = $('#cv-list');
  if (d.error) { el.innerHTML = '<p class=note style="padding:14px">' + esc(d.error) + '</p>'; return; }
  el.innerHTML = (d.versions || []).map(v =>
    '<button onclick="cvOpen(' + v.v + ')"><span class=r1><b>Versie ' + v.v + '</b><time>' + esc(fmtFull(v.t)) + '</time></span>' +
    '<span class=r2>' + v.count + ' contacten · ' + (v.first ? 'eerste versie' :
      [v.added ? '+' + v.added + ' nieuw' : '', v.changed ? '~' + v.changed + ' gewijzigd' : '', v.removed ? '−' + v.removed + ' verwijderd' : ''].filter(Boolean).join(' · ')) +
    '</span></button>').join('') || '<p class=note style="padding:14px">Nog geen versies.</p>';
}
function cvOpen(v){ cvCur = v; show('cversion'); cvRender(); }
function cvRender(){
  const d = ctJson(Android.contactsVersion(String(cvCur)));
  if (d.error) { toast(d.error); return; }
  $('#cvd-title').textContent = 'Versie ' + d.v;
  $('#cvd-sum').textContent = fmtFull(d.t) + ' · ' + d.count + ' contacten' + (d.first ? ' · eerste versie' : '');
  $('#cvd-export').style.display = d.full ? 'block' : 'none';
  const groups = [['-', 'Verwijderd'], ['~', 'Gewijzigd'], ['+', 'Nieuw']];
  $('#cvd-list').innerHTML = groups.map(([k, label]) => {
    const l = (d.changes || []).filter(x => x.kind === k); if (!l.length) return '';
    return '<div class=card><h2>' + label + ' (' + l.length + ')</h2>' + l.map(x =>
      '<div class=hist><b>' + esc(x.n || '(zonder naam)') + '</b>' +
      (k === '-' ? (x.restored ? ' <button class="mini" disabled>Teruggezet</button>'
        : ' <button class="mini" data-k="' + esc(x.k) + '" onclick="cvRestore(this.dataset.k, this)">Terugzetten</button>') : '') +
      ctChanges(k === '~' ? x.d : (x.d || []).filter(c => c[0] !== 'Naam')) + '</div>').join('') + '</div>';
  }).join('') || (d.first ? '' : '<p class=note>Geen wijzigingen.</p>');
}
function cvRestore(k, btn){
  const i = ctInfo();
  if (!i.write) { toast('Geef eerst toestemming om contacten te wijzigen'); Android.contactsRequestPermission(); return; }
  askConfirm('Contact terugzetten?', 'Het contact wordt opnieuw aangemaakt op deze telefoon, met de gegevens van vóór het verwijderen.', 'Terugzetten', () => {
    const r = Android.contactsRestore(String(cvCur), k);
    if (r.startsWith('ok:')) { toast('✓ ' + r.slice(3) + ' is teruggezet'); btn.disabled = true; btn.textContent = 'Teruggezet'; }
    else toast(r);
  });
}
function ctExport(v){
  if (!plainOk('De contactenlijst', () => ctExport(v))) return;
  const r = Android.contactsExport(String(v || 0));
  toast(r.startsWith('ok:') ? '✓ ' + r.slice(3) + ' contacten opgeslagen in de map Contacten backup' : r);
}

