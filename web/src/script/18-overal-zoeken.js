/* ---------- Overal zoeken ---------- */
const GS = {
  app:      ['⚙️', 'In de app: functies en instellingen', '', '', () => {}],
  notes:    ['📝', 'Notities', 'notes', 'notes-search', () => renderNotes()],
  contacts: ['👤', 'Contacten', 'contacts', 'ct-search', () => ctSearchDo()],
  sms:      ['💬', "Sms'jes", 'sms', 'sms-search', v => smsSearchDo(v)],
  wa:       ['🟢', 'WhatsApp', 'chats', 'chat-search', v => chatSearch(v)],
  calls:    ['📞', 'Oproepen', 'calls', 'calls-search', () => callsSearchDo()],
  tx:       ['🎙️', 'Gesprekken', 'transcripts', 'tx-search', () => txSearchDo()],
  music:    ['🎵', 'Muziek', 'music', 'mu-search', () => muRenderHistory()],
  history:  ['🔔', 'Meldingsgeschiedenis', 'history', 'history-search', () => { historyLimit = 200; loadHistory(); }]
};
/* ---------- zoeken in de app zelf: schermen, kaarten en instellingen ---------- */
const GS_SYN = {meldingen: 'notificaties melding geluid trillen stil dempen uitzetten', 'app-slot': 'vingerafdruk pincode slot vergrendelen beveiliging wachtwoord',
  backup: 'reservekopie back-up opslaan bewaren', 'telefoon-skin': 'startscherm launcher thuisscherm skin', weergave: 'tekstgrootte letters groot thema donker dark mode licht nachtmodus',
  foutrapport: 'fout crash probleem bug', versleutel: 'wachtwoord encryptie geheim', batterij: 'accu energie stroom'};
let gsIndex = null;
function gsBuildIndex(){
  gsIndex = [];
  const fold = t => norm2(t);
  // Detailschermen (één notitie, chat, route …) hebben iets nodig om te tonen: die niet los openen
  const DETAIL = ['note', 'chat', 'smschat', 'track', 'txd', 'contact', 'cversion', 'autoed', 'podcast'];
  document.querySelectorAll('section.screen[id^="s-"]').forEach(sec => {
    const scr = sec.id.slice(2);
    if (scr === 'home' || DETAIL.includes(scr)) return;
    const h1 = sec.querySelector('.hdr h1'), screen = h1 ? h1.textContent.trim() : scr;
    gsIndex.push({scr, el: null, title: screen, sub: 'Openen', key: fold(screen + ' ' + scr)});
    sec.querySelectorAll('.card').forEach(card => {
      const h = card.querySelector('h2, h3'); if (!h) return;
      const head = h.textContent.trim(); if (!head) return;
      const labels = [...card.querySelectorAll('label, .setrow > span, button.big, button.ghost, .chk')].map(e => e.textContent.trim()).filter(Boolean).slice(0, 12);
      const syn = Object.keys(GS_SYN).filter(k => fold(head).includes(k)).map(k => GS_SYN[k]).join(' ');
      gsIndex.push({scr, el: card, title: head, sub: screen, key: fold(head + ' ' + screen + ' ' + labels.join(' ') + ' ' + syn)});
    });
  });
}
function norm2(s){ return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(); }
function gsApp(q){
  if (!gsIndex) gsBuildIndex();
  const words = norm2(q).split(/\s+/).filter(Boolean);
  const hits = gsIndex.map((x, i) => ({x, i, s: words.every(w => x.key.includes(w)) ? (norm2(x.title).includes(words[0]) ? 2 : 1) : 0})).filter(h => h.s).sort((a, b) => b.s - a.s);
  return {items: hits.slice(0, 6).map(h => ({i: h.i, title: h.x.title, sub: h.x.sub})), total: Math.min(hits.length, 6)};
}
function gsHistory(q){
  if (typeof Android.historyListBy !== 'function') return null;
  let d; try { d = JSON.parse(Android.historyListBy(q, '', 4)); } catch(e){ return null; }
  if (!d || !d.rows || !d.count) return null;
  return {items: d.rows.map(r => ({app: r.app || r.package, title: r.title, text: r.text, t: r.time})), total: d.count};
}
let gsTmr = null, gsId = 0, gsQ = '', gsData = {};
function gsInput(){
  const q = $('#gs-q').value.trim();
  clearTimeout(gsTmr);
  const on = q.length >= 2;
  $('#gs-res').style.display = on ? 'block' : 'none';
  $('#home-groups').style.display = on ? 'none' : '';
  if (on) $('#home-notes').style.display = 'none';
  $('#home-tip').style.display = on ? 'none' : '';
  if (!on) { if (gsQ) Android.searchAll(''); gsQ = ''; gsData = {}; renderDash(); return; }
  $('#home-dash').style.display = 'none';
  // Vorige treffers blijven (gedimd) staan tot de nieuwe binnen zijn: geen verspringende pagina.
  const prev = gsData; gsQ = q; gsData = { app: gsApp(q), notes: gsNotes(q), history: gsHistory(q), pending: true, stale: {} };
  for (const k of Object.keys(GS)) if (!['notes', 'app', 'history'].includes(k) && prev[k]) gsData.stale[k] = prev[k];
  gsRender();
  gsTmr = setTimeout(() => { gsId = Android.searchAll(q); }, 300);
}
function gsNotes(q){
  const ql = q.toLowerCase(), out = [];
  for (const n of notesGet()) {
    const title = n.title || 'Zonder titel', text = (n.text || '').replace(/\s+/g, ' ');
    const item = (n.items || []).find(i => (i.text || '').toLowerCase().includes(ql));
    if (title.toLowerCase().includes(ql) || text.toLowerCase().includes(ql) || item) out.push({ id: n.id, title, snip: item ? item.text : text, u: n.updated || 0 });
  }
  out.sort((a, b) => b.u - a.u);
  return { items: out.slice(0, 5), total: out.length };
}
window.onSearchAll = function(r){
  if (!r || r.id !== gsId || r.q !== gsQ) return;
  gsData.stale = gsData.stale || {};
  for (const k of Object.keys(GS)) if (!['notes', 'app', 'history'].includes(k)) { if (r[k]) gsData[k] = r[k]; if (r.done || r[k]) delete gsData.stale[k]; }
  if (r.done) { gsData.pending = false; gsData.stale = {}; }
  gsRender();
};
function gsSnip(text, ql){
  text = String(text || ''); const i = text.toLowerCase().indexOf(ql);
  if (i > 40) text = '…' + text.slice(i - 30);
  return hl(text.length > 160 ? text.slice(0, 160) + '…' : text, ql);
}
function gsItem(kind, x, ql){
  const t = (title, sub, act) => '<button class=gi data-k="' + kind + '" data-x="' + esc(JSON.stringify(act)) + '" onclick="gsOpen(this)"><b>' + title + '</b>' + (sub ? '<small>' + sub + '</small>' : '') + '</button>';
  switch (kind) {
    case 'notes': return t(hl(x.title, ql), x.snip && x.snip !== x.title ? gsSnip(x.snip, ql) : '', {id: x.id});
    case 'contacts': return t(hl(x.n || '(zonder naam)', ql), esc(x.sub || ''), {k: x.k, id: x.id});
    case 'sms': return t(esc(x.name || x.address || '') + ' <small style="display:inline">· ' + esc(fmtD(x.t)) + '</small>', gsSnip(x.text, ql), {thread: x.thread, name: x.name || ''});
    case 'wa': return t(esc(x.chatName || '') + ' <small style="display:inline">· ' + esc(fmtD(x.t)) + '</small>', (x.sender && !x.me ? esc(x.sender) + ': ' : '') + gsSnip(x.text || x.label, ql), {chat: x.chat, name: x.chatName || ''});
    case 'calls': return t(hl(x.name || x.number || 'Onbekend', ql), esc((x.label || '') + ' · ' + fmtD(x.date) + (x.number && x.number !== x.name ? ' · ' + x.number : '')), {ck: x.ck || '', cid: x.cid || '', number: x.number || ''});
    case 'tx': return t(esc(x.name) + ' <small style="display:inline">· ' + esc(fmtD(x.mtime)) + '</small>', gsSnip(x.text, ql), {id: x.id, from: x.from});
    case 'music': return t(hl(x.title || '', ql), hl(x.artist || '', ql) + ' · ' + esc(fmtD(x.t)), {t: x.t});
    case 'app': return t(hl(x.title, ql), esc(x.sub), {i: x.i});
    case 'history': return t(esc(x.app) + ' <small style="display:inline">· ' + esc(fmtD(x.t)) + '</small>', (x.title ? esc(x.title) + ': ' : '') + gsSnip(x.text, ql), {});
  }
  return '';
}
function gsRender(){
  const ql = gsQ.toLowerCase();
  const st = gsData.stale || {};
  const html = Object.keys(GS).filter(k => (gsData[k] || st[k]) && (gsData[k] || st[k]).total).map(k => {
    const g = gsData[k] || st[k], d = GS[k], tot = g.total + (g.capped ? '+' : '');
    return '<div class="card gsg' + (gsData[k] ? '' : ' stale') + '"><h3><span aria-hidden=true>' + d[0] + '</span>' + d[1] + ' <span class=n>' + tot + '</span>' +
      (g.total > g.items.length ? '<button class=more data-k="' + k + '" onclick="gsMore(this.dataset.k)">Alle ' + tot + ' ›</button>' : '') + '</h3>' +
      g.items.map(x => gsItem(k, x, ql)).join('') + '</div>';
  }).join('');
  $('#gs-res').innerHTML = html || '<p class=gs-empty>' + (gsData.pending ? 'Zoeken…' : 'Niets gevonden voor "' + esc(gsQ) + '"') + '</p>';
  if (html && gsData.pending) $('#gs-res').innerHTML += '<p class=gs-empty style="padding:8px 0">Verder zoeken…</p>';
}
function gsMore(k){
  const d = GS[k];
  jumpTo(d[2]);
  if (k === 'calls') Object.assign(callsF, { kind: 'all', period: 'all', from: '', to: '', who: 'all', dur: 'all', person: null });
  const inp = $('#' + d[3]); if (inp) { inp.value = gsQ; if (k === 'calls') callsSyncUi(); d[4](gsQ); }
  gsUsed[d[3]] = gsQ;
}
function gsOpen(el){
  const k = el.dataset.k; let x; try { x = JSON.parse(el.dataset.x); } catch(e){ return; }
  if (k === 'notes') jumpTo('note', () => openNote(x.id));
  else if (k === 'contacts') jumpTo('contact', () => ctOpen(x.k, x.id));
  else if (k === 'sms') jumpTo('smschat', () => openSms(x.thread, x.name));
  else if (k === 'wa') jumpTo('chat', () => openChat(x.chat, x.name));
  else if (k === 'calls') { if (x.ck) jumpTo('contact', () => ctOpen(x.ck, x.cid)); else gsMoreCalls(x.number); }
  else if (k === 'tx') jumpTo('txd', () => txOpen(x.id, x.from));
  else if (k === 'music') { jumpTo('music'); setTimeout(() => muOpen(x.t), 50); }
  else if (k === 'history') gsMore('history');
  else if (k === 'app') {
    const it = gsIndex && gsIndex[x.i]; if (!it) return;
    jumpTo(it.scr);
    if (it.el) setTimeout(() => { it.el.scrollIntoView({block: 'start', behavior: 'smooth'}); it.el.classList.add('gsflash'); setTimeout(() => it.el.classList.remove('gsflash'), 1600); }, 120);
  }
}
function gsMoreCalls(num){
  jumpTo('calls'); Object.assign(callsF, { kind: 'all', period: 'all', from: '', to: '', who: 'all', dur: 'all', person: null });
  const i = $('#calls-search'); if (i) { i.value = num || gsQ; gsUsed['calls-search'] = i.value; callsSyncUi(); callsSearchDo(); }
}
/* Zoekterm die overal-zoeken in een tool heeft gezet: weghalen als je die tool later gewoon via de tegel opent. */
const gsUsed = {};
function gsResetTool(name){
  const d = Object.values(GS).find(x => x[2] === name); if (!d) return;
  const inp = $('#' + d[3]);
  if (inp && gsUsed[d[3]] != null && inp.value === gsUsed[d[3]]) { inp.value = ''; delete gsUsed[d[3]]; d[4](''); }
}

