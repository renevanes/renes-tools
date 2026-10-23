/* ---------- Assistent: lokale AI (llama.cpp op de telefoon) en slim zoeken ---------- */
let aiSt = {}, aiChat = null, aiBusy = false, aiPollT = null, aiCur = null, aiGen = null; // aiGen: het gesprek waarin nu geantwoord wordt
const AI_SUGG = [
  ['Vat samen', 'Vat deze tekst kort samen in een paar punten:\n\n'],
  ['Schrijf een e-mail', 'Schrijf een korte, vriendelijke e-mail over: '],
  ['Vertaal', 'Vertaal naar het Engels:\n\n'],
  ['Maak een takenlijst', 'Maak een overzichtelijke takenlijst van: '],
  ['Leg uit', 'Leg eenvoudig uit: '],
  ['Wat staat er in mijn notities over…', '', true]
];
function aiLoad(){ aiSt = rdJson(Android.aiState(), {}); return aiSt; }
function aiModel(id){ return (aiSt.models || []).find(m => m.id === id) || {}; }
function aiReady(id){ return aiModel(id).state === 'ready'; }
function aiChatReady(){ return aiReady('klein') || aiReady('groot'); }
function aiBusyDl(){ return (aiSt.models || []).some(m => ['running', 'queued', 'paused', 'verifying'].includes(m.state)); }

/* Status van een model in één regel */
function aiStateTxt(m){
  const gb = b => (b / 1e9).toFixed(1).replace('.', ',') + ' GB';
  switch (m.state) {
    case 'ready': return '✓ Klaar · ' + gb(m.size);
    case 'running': return '↓ ' + (m.total > 0 ? Math.floor(m.bytes / m.total * 100) + '% van ' + gb(m.total) : 'Bezig');
    case 'queued': return '↓ In de wachtrij';
    case 'paused': return '↓ ' + (m.reason || 'Gepauzeerd') + (m.total > 0 ? ' · ' + Math.floor(m.bytes / m.total * 100) + '%' : '');
    case 'verifying': return 'Controleren… ' + (m.pct || 0) + '%';
    case 'failed': return '⚠ ' + (m.reason || 'Mislukt');
  }
  return gb(m.size);
}
function aiModelCard(m, opts){
  const busy = ['running', 'queued', 'paused', 'verifying'].includes(m.state), chosen = opts.chat && aiSt.chat === m.id && m.state === 'ready';
  const pct = m.state === 'running' || m.state === 'paused' ? (m.total > 0 ? m.bytes / m.total * 100 : 0) : m.state === 'verifying' ? (m.pct || 0) : 0;
  let btn = '';
  if (m.state === 'ready') btn = opts.chat ? (chosen ? '<span class="aisel">In gebruik</span>' : '<button class="mini" onclick="aiUse(\'' + m.id + '\')">Gebruiken</button>') : '';
  else if (busy) btn = m.state === 'verifying' ? '' : '<button class="mini" onclick="aiDel(\'' + m.id + '\', true)">Stoppen</button>';
  else btn = '<button class="mini on" onclick="aiDl(\'' + m.id + '\')">Downloaden</button>';
  const big = m.id === 'groot' && aiSt.ram > 0 && aiSt.ram < 7.5e9;
  return '<div class="aimodel' + (chosen ? ' on' : '') + '" data-id="' + m.id + '"><div class="aimt"><b>' + esc(m.label) + '</b><small>' + esc(m.desc) + '</small>' +
    '<small class="aims">' + esc(aiStateTxt(m)) + (big && m.state !== 'ready' ? ' · let op: je telefoon heeft ' + (aiSt.ram / 1e9).toFixed(0) + ' GB geheugen' : '') + '</small>' +
    (busy ? '<span class="pcprog"><i style="width:' + Math.min(100, pct).toFixed(1) + '%"></i></span>' : '') + '</div>' +
    '<div class="aimb">' + btn + (m.state === 'ready' && opts.del ? '<button class="mini" onclick="aiDel(\'' + m.id + '\')">Verwijderen</button>' : '') + '</div></div>';
}
function aiDl(id){
  const e = Android.aiDownload(id);
  if (e) { toast(e); return; }
  toast(aiSt.wifiOnly ? '↓ Downloaden (via wifi)' : '↓ Downloaden');
  aiRefresh();
}
function aiDel(id, stop){
  const m = aiModel(id);
  const go = () => { Android.aiDelete(id); aiRefresh(); };
  if (stop) { go(); return; }
  askConfirm(m.label + ' verwijderen?', 'Het model (' + aiStateTxt(m).replace('✓ Klaar · ', '') + ') gaat van je telefoon. Je kunt het later opnieuw downloaden.', 'Verwijderen', go);
}
function aiUse(id){ Android.aiUse(id); aiRefresh(); toast('De assistent gebruikt nu: ' + aiModel(id).label); }
/* Alles bijwerken wat modellen toont; blijven kijken zolang er gedownload of gecontroleerd wordt */
function aiRefresh(){
  aiLoad();
  if (current === 'ai') aiRenderMain();
  if (current === 'aiset') aiRenderSet();
  clearTimeout(aiPollT);
  if (aiBusyDl() && (current === 'ai' || current === 'aiset')) aiPollT = setTimeout(aiRefresh, 1500);
}
window.onAiModels = function(){ aiRefresh(); };

/* ---------- het assistentscherm ---------- */
function enterAi(){
  aiLoad();
  if (!aiChat) aiChat = { id: '', title: '', msgs: [] };
  aiRenderMain(); aiRenderMsgs();
  clearTimeout(aiPollT); if (aiBusyDl()) aiPollT = setTimeout(aiRefresh, 1500);
}
function aiRenderMain(){
  const ok = aiChatReady();
  $('#ai-setup').style.display = ok ? 'none' : '';
  $('#ai-chat').style.display = ok ? '' : 'none';
  $('#ai-new').style.display = ok ? '' : 'none';
  if (!ok) {
    $('#ai-setup-models').innerHTML = (aiSt.models || []).filter(m => !m.embed).map(m => aiModelCard(m, { chat: true })).join('');
    $('#ai-setup-note').textContent = !aiSt.supported ? 'Let op: de lokale AI werkt alleen op telefoons met een 64-bits ARM-processor.'
      : 'Downloaden gaat door op de achtergrond' + (aiSt.wifiOnly ? ', alleen via wifi' : '') + '. Je kunt het kleine model later altijd nog wisselen voor het grote, of andersom (bij Meer → Instellingen).';
    return;
  }
  const n = $('#ai-notes');
  n.style.display = aiReady('zoek') ? '' : 'none';
  n.classList.toggle('on', !!aiSt.notes); n.setAttribute('aria-checked', aiSt.notes ? 'true' : 'false');
  $('#ai-sugg').innerHTML = AI_SUGG.filter(s => !s[2] || aiReady('zoek')).map((s, i) => '<button class="chip" onclick="aiSugg(' + AI_SUGG.indexOf(s) + ')">' + esc(s[0]) + '</button>').join('');
  aiSendBtn();
}
function aiSugg(i){
  const s = AI_SUGG[i]; if (!s) return;
  if (s[2] && !aiSt.notes) aiToggleNotes();
  const t = $('#ai-in'); t.value = s[1]; aiGrow(); t.focus(); t.setSelectionRange(t.value.length, t.value.length);
}
function aiToggleNotes(){ aiSt.notes = !aiSt.notes; Android.aiPref('notes', aiSt.notes); aiRenderMain(); toast(aiSt.notes ? 'De assistent zoekt eerst in je notities en gesprekken' : 'Zonder je notities'); }
function aiGrow(){ const t = $('#ai-in'); t.style.height = 'auto'; t.style.height = Math.min(160, t.scrollHeight) + 'px'; aiSendBtn(); }
function aiKey(e){ if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); aiSendOrStop(); } }
function aiSendBtn(){
  const b = $('#ai-send'); if (!b) return;
  if (typeof aiMicUi === 'function') { const m = $('#ai-mic'); if (m) m.classList.toggle('dim', aiBusy && !aiRec); } // tijdens een antwoord: zichtbaar maar gedimd (focus blijft)
  b.classList.toggle('stop', aiBusy);
  b.innerHTML = uiIcon(aiBusy ? 'stop' : 'up');
  b.setAttribute('aria-label', aiBusy ? 'Stoppen' : 'Versturen');
  // Vlak na versturen even uit: een dubbele tik stopt (of verstuurt) dan niet meteen nog eens
  b.disabled = aiSentAt > 0 || (!aiBusy && !$('#ai-in').value.trim());
}
let aiSentAt = 0, aiSentT = null;
const AI_TAP_MS = 350; // zo snel als een dubbele tik (Android: 300 ms)
function aiSendOrStop(){
  if (aiSentAt && Date.now() - aiSentAt < AI_TAP_MS) return;
  if (aiBusy) { Android.aiCancel(); return; }
  const q = $('#ai-in').value.trim(); if (!q) return;
  aiChat.msgs.push({ r: 'user', c: q, t: Date.now() });
  if (!aiChat.title) aiChat.title = q.replace(/\s+/g, ' ').slice(0, 60);
  $('#ai-in').value = ''; aiGrow();
  if (aiAsk() === 'bezig') { aiChat.msgs.pop(); $('#ai-in').value = q; aiGrow(); aiRenderMsgs(); }
}
/* De vraag (met het gesprek tot nu toe) naar de assistent */
function aiAsk(){
  if (aiBusy) { toast('De assistent is nog bezig met een antwoord'); return 'bezig'; }
  const msgs = aiChat.msgs.filter(m => !m.err).map(m => ({ role: m.r === 'user' ? 'user' : 'assistant', content: m.c }));
  // Eerst het (lege) antwoord klaarzetten: stukjes kunnen meteen binnenkomen
  aiCur = { r: 'ai', c: '', t: Date.now(), src: null };
  aiChat.msgs.push(aiCur); aiGen = aiChat;
  aiBusy = true; aiStatus('Denken…');
  aiSentAt = Date.now(); clearTimeout(aiSentT); aiSentT = setTimeout(() => { aiSentAt = 0; aiSendBtn(); }, AI_TAP_MS);
  aiRenderMsgs(); aiSendBtn();
  const e = Android.aiChat(JSON.stringify({ messages: msgs, notes: !!aiSt.notes && aiReady('zoek') }));
  if (e === AI_BUSY) {
    // Er loopt nog een antwoord (bijv. van een dubbele tik): deze vraag niet nog eens beantwoorden
    aiChat.msgs.splice(aiChat.msgs.indexOf(aiCur), 1); aiCur = null; aiGen = null; aiBusy = false; aiStatus('');
    aiRenderMsgs(); aiSendBtn(); toast('De assistent is nog bezig met een antwoord'); return 'bezig';
  }
  if (e) { aiCur.c = e; aiCur.err = true; aiCur = null; aiGen = null; aiBusy = false; aiStatus(''); aiRenderMsgs(); aiSendBtn(); }
}
const AI_BUSY = 'De assistent is nog bezig'; // zelfde melding als AiBridge.BUSY
function aiStatus(t){ const s = $('#ai-status'); if (!s) return; s.textContent = t || ''; s.style.display = t && aiGen === aiChat ? '' : 'none'; }
const aiShown = () => aiGen && aiGen === aiChat && current === 'ai';
window.onAiStatus = function(t){ aiStatus(t); };
window.onAiSources = function(items){ if (aiCur) { aiCur.src = items; if (aiShown()) aiRenderLast(); } };
window.onAiToken = function(t){
  if (!aiCur) return;
  if (!aiCur.c) aiStatus('');
  aiCur.c += t; if (aiShown()) aiRenderLast();
};
/* Klaar (of gestopt, of fout): het gesprek waarin geantwoord werd bewaren, ook als je intussen een ander opende */
function aiFinish(fix){
  const chat = aiGen, m = aiCur;
  aiBusy = false; aiCur = null; aiGen = null; aiStatus('');
  if (chat && m) fix(chat, m);
  if (chat && !chat.discard) aiSaveChat(chat);
  if (aiChat) aiRenderMsgs();
  aiSendBtn();
}
window.onAiDone = function(info){
  info = info || {};
  aiFinish((chat, m) => {
    if (info.cancelled && !m.c) chat.msgs.splice(chat.msgs.indexOf(m), 1);
    else { m.c = m.c.trim(); if (info.cancelled) m.stopped = true; if (info.cut) m.cut = true; if (info.trim) m.trim = true; }
  });
};
window.onAiError = function(msg){
  aiFinish((chat, m) => { if (!m.c) { m.c = msg; m.err = true; } else m.c += '\n\n⚠ ' + msg; });
};
function aiSaveChat(chat){
  if (!chat || !chat.msgs.some(m => !m.err)) return;
  const id = Android.aiChatSave(JSON.stringify({ id: chat.id, title: chat.title, msgs: chat.msgs.map(m => ({ r: m.r, c: m.c, t: m.t, src: m.src || undefined, err: m.err || undefined, stopped: m.stopped || undefined, cut: m.cut || undefined, trim: m.trim || undefined })) }));
  if (id) chat.id = id;
}
/* Eenvoudige opmaak van een antwoord: alinea's, opsommingen, vet, cursief, code, kopjes (alles eerst ge-escaped) */
function aiMd(s){
  const lines = esc(String(s || '')).split('\n'), out = [];
  let list = null;
  const inline = t => t.replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/(^|[\s(])\*([^*\s][^*]*)\*/g, '$1<i>$2</i>');
  const close = () => { if (list) { out.push('</' + list + '>'); list = null; } };
  for (const raw of lines) {
    const l = raw.trimEnd();
    let m;
    if ((m = l.match(/^\s*[-*•]\s+(.*)$/))) { if (list !== 'ul') { close(); out.push('<ul>'); list = 'ul'; } out.push('<li>' + inline(m[1]) + '</li>'); continue; }
    if ((m = l.match(/^\s*\d+[.)]\s+(.*)$/))) { if (list !== 'ol') { close(); out.push('<ol>'); list = 'ol'; } out.push('<li>' + inline(m[1]) + '</li>'); continue; }
    close();
    if ((m = l.match(/^#{1,4}\s+(.*)$/))) { out.push('<p><b>' + inline(m[1]) + '</b></p>'); continue; }
    if (!l.trim()) { out.push(''); continue; }
    out.push('<p>' + inline(l) + '</p>');
  }
  close();
  return out.join('');
}
function aiSrcHtml(src){
  if (!src || !src.length) return '';
  return '<div class="aisrc">' + src.map((h, i) => '<button class="aichip sm" onclick="aiOpenSrc(' + i + ', this)" data-k="' + esc(h.kind) + '" data-id="' + esc(h.id) + '" data-from="' + (+h.from || 0) + '">[' + (i + 1) + '] ' + uiIcon(h.kind === 'tx' ? 'mic' : 'notes') + esc(h.title) + '</button>').join('') + '</div>';
}
function aiOpenSrc(i, el){
  const k = el.dataset.k, id = el.dataset.id;
  if (k === 'notes') jumpTo('note', () => openNote(id));
  else if (k === 'tx') jumpTo('txd', () => txOpen(id, +el.dataset.from || 0));
}
function aiMsgHtml(m, i){
  if (!m) return '';
  if (m.r === 'user') return '<div class="aimsg me" data-i="' + i + '"><div class="aib">' + esc(m.c).replace(/\n/g, '<br>') + '</div></div>';
  const live = m === aiCur;
  return '<div class="aimsg' + (m.err ? ' err' : '') + '" data-i="' + i + '"><div class="aib">' + (m.c ? aiMd(m.c) : (live ? '<span class="aidots" aria-label="Bezig"><i></i><i></i><i></i></span>' : '')) +
    (m.stopped ? '<p class="note">Gestopt.</p>' : '') + (m.cut ? '<p class="note">Het gesprek werd te lang: het begin is overgeslagen.</p>' : '') + (m.trim ? '<p class="note">Te lang voor het model: een deel van je bericht is weggelaten.</p>' : '') + '</div>' + aiSrcHtml(m.src) +
    (!live && !m.err && m.c ? '<div class="aiacts"><button class="linkish" onclick="aiMsgMenu(' + i + ')" aria-label="Meer over dit antwoord">⋯ Meer</button></div>' : '') + '</div>';
}
function aiRenderMsgs(){
  const box = $('#ai-msgs'); if (!box || !aiChat) return;
  $('#ai-empty').style.display = aiChat.msgs.length ? 'none' : '';
  box.innerHTML = aiChat.msgs.map(aiMsgHtml).join('');
  aiScroll();
}
/* Alleen het laatste bericht opnieuw (tijdens het schrijven): de rest blijft staan */
function aiRenderLast(){
  const box = $('#ai-msgs'); if (!box || !aiCur || !aiShown()) return;
  const i = aiChat.msgs.indexOf(aiCur), el = box.querySelector('.aimsg[data-i="' + i + '"]');
  if (el) el.outerHTML = aiMsgHtml(aiCur, i); else box.insertAdjacentHTML('beforeend', aiMsgHtml(aiCur, i));
  aiScroll();
}
function aiScroll(){
  // Alleen meescrollen als je al (bijna) onderaan was
  const nearEnd = window.innerHeight + scrollY >= document.documentElement.scrollHeight - 160;
  if (nearEnd || aiBusy) requestAnimationFrame(() => scrollTo(0, document.documentElement.scrollHeight));
}
function aiMsgMenu(i){
  const m = aiChat.msgs[i]; if (!m) return;
  const acts = [
    ['Kopiëren', () => { Android.aiCopy(m.c); toast('Gekopieerd'); }],
    ['Delen', () => Android.aiShare(m.c)],
    ['Als notitie bewaren', () => aiToNote(m.c)]
  ];
  if (i === aiChat.msgs.length - 1 && !aiBusy) acts.push(['Opnieuw antwoorden', () => {
    // Het oude antwoord eerst weg; weigert de assistent (nog bezig), dan komt het terug
    const old = aiChat.msgs.pop();
    if (aiAsk() === 'bezig') { aiChat.msgs.push(old); aiRenderMsgs(); } // de melding geeft aiAsk zelf
  }]);
  openSheet('Antwoord', '', acts);
}
function aiToNote(text){
  const first = text.split('\n').find(l => l.trim()) || 'Van de assistent';
  const n = { id: uid(), title: first.replace(/^[#*\-\s]+/, '').replace(/\*\*/g, '').slice(0, 60), text: text, items: [], updated: Date.now() };
  notesGet().push(n); notesSaveNow();
  toast('Bewaard bij Notities');
}
function aiNew(){
  if (typeof aiVoiceAbort === 'function') aiVoiceAbort();
  if (aiBusy) Android.aiCancel(); // het lopende antwoord stopt en wordt (tot dan) bewaard in het oude gesprek
  aiChat = { id: '', title: '', msgs: [] };
  aiRenderMain(); aiRenderMsgs(); aiStatus('');
  setTimeout(() => $('#ai-in') && $('#ai-in').focus(), 50);
}
function aiMenu(){
  const acts = [];
  if (aiChatReady()) acts.push(['＋ Nieuw gesprek', () => aiNew()]);
  acts.push(['Eerdere gesprekken', () => show('aichats')]);
  if (aiChat && aiChat.msgs.length) acts.push(['Dit gesprek delen', () => Android.aiShare(aiChat.msgs.map(m => (m.r === 'user' ? 'Ik: ' : 'Assistent: ') + m.c).join('\n\n'))]);
  acts.push(['Instellingen', () => show('aiset')]);
  openSheet('Assistent', aiSt.chat ? 'Model: ' + aiModel(aiSt.chat).label : '', acts);
}

/* ---------- eerdere gesprekken ---------- */
let aicList = [];
function enterAiChats(){
  aicList = rdJson(Android.aiChats(), []);
  $('#aic-list').innerHTML = aicList.map((c, i) => '<div class="pcep"><button class="pcepb" onclick="aicOpen(' + i + ')"><span class="pctxt"><b>' + esc(c.title || 'Gesprek') + '</b><small>' + esc(fmtD(c.t) + ' · ' + c.n + (c.n === 1 ? ' bericht' : ' berichten')) + '</small></span></button>' +
    '<button class="pcmore" onclick="aicMenu(' + i + ')" aria-label="Meer over ' + esc(c.title || 'gesprek') + '">⋯</button></div>').join('') || '<p class="note" style="padding:14px">Nog geen gesprekken.</p>';
}
function aicOpen(i){
  const c = aicList[i]; if (!c) return;
  const full = rdJson(Android.aiChatGet(c.id), null); if (!full) return;
  // Hetzelfde gesprek als waarin nu geantwoord wordt: gewoon verder laten lopen
  if (aiGen && aiGen.id === full.id) aiChat = aiGen;
  else { if (aiBusy) Android.aiCancel(); aiChat = { id: full.id, title: full.title, msgs: full.msgs || [] }; }
  show('ai');
}
function aicMenu(i){
  const c = aicList[i]; if (!c) return;
  openSheet(c.title || 'Gesprek', fmtD(c.t), [
    ['Openen', () => aicOpen(i)],
    ['Verwijderen', () => { if (aiGen && aiGen.id === c.id) { aiGen.discard = true; Android.aiCancel(); } Android.aiChatDelete(c.id); if (aiChat && aiChat.id === c.id) aiChat = null; enterAiChats(); toast('Gesprek verwijderd'); }]
  ]);
}

/* ---------- instellingen ---------- */
function enterAiSet(){ aiLoad(); aiRenderSet(); clearTimeout(aiPollT); if (aiBusyDl()) aiPollT = setTimeout(aiRefresh, 1500); }
function aiRenderSet(){
  const ms = aiSt.models || [];
  $('#ais-chat').innerHTML = ms.filter(m => !m.embed).map(m => aiModelCard(m, { chat: true, del: true })).join('');
  $('#ais-embed').innerHTML = ms.filter(m => m.embed).map(m => aiModelCard(m, { del: true })).join('');
  const w = $('#ais-wifi'); w.classList.toggle('on', !!aiSt.wifiOnly); w.setAttribute('aria-checked', aiSt.wifiOnly ? 'true' : 'false');
  aisRenderVoice();
  const ix = aiSt.index, box = $('#ais-index');
  if (!ix || !aiReady('zoek')) { box.innerHTML = ''; return; }
  const busy = ix.busy || aiIdx.busy;
  box.innerHTML = '<div class="sw" style="margin-top:6px"><span>Zoekindex<small id="ais-ix-sub">' + esc(busy ? 'Bijwerken… ' + (aiIdx.done || ix.done || 0) + ' van ' + (aiIdx.total || ix.total || 0) + ' stukken'
      : (ix.items ? ix.items + ' notities en gesprekken · ' + ix.chunks + ' stukken' + (ix.at ? ' · ' + fmtD(ix.at) : '') : 'Nog niet opgebouwd') + (ix.stale && ix.items ? ' · wordt bij de volgende zoekvraag bijgewerkt' : '')) + '</small></span>' +
    (busy ? '' : '<button class="mini" onclick="aisIndex(' + (ix.items ? 'false' : 'false') + ')">' + (ix.items ? 'Bijwerken' : 'Opbouwen') + '</button>') + '</div>' +
    (ix.items && !busy ? '<button class="linkish" onclick="aisIndex(true)">Helemaal opnieuw opbouwen</button>' : '');
}
let aiIdx = {};
function aisIndex(force){ aiIdx = { busy: true, done: 0, total: 0 }; Android.aiIndex(!!force); aiRenderSet(); }
window.onAiIndex = function(o){
  if (o.busy) aiIdx = { busy: true, done: o.done, total: o.total };
  else { aiIdx = {}; if (o.error) toast(o.error); else toast('Zoekindex bijgewerkt'); aiLoad(); }
  if (current === 'aiset') aiRenderSet();
};
function aisWifi(){ aiSt.wifiOnly = !aiSt.wifiOnly; Android.aiPref('wifiOnly', aiSt.wifiOnly); aiRenderSet(); toast(aiSt.wifiOnly ? 'Downloaden alleen via wifi (voor een nieuwe download)' : 'Downloaden ook via mobiel internet (voor een nieuwe download)'); }
function aisClearChats(){ askConfirm('Alle gesprekken verwijderen?', 'Alle gesprekken met de assistent gaan weg. De modellen en je notities blijven.', 'Verwijderen', () => { if (aiGen) { aiGen.discard = true; Android.aiCancel(); } Android.aiChatDelete('*'); aiChat = null; toast('Gesprekken verwijderd'); }); }

/* ---------- slim zoeken in Zoeken (overal) ---------- */
let aiSearchId = 0, aiSearchT = null, aiSearchRes = null;
function aiSearchSoon(q){
  clearTimeout(aiSearchT); aiSearchRes = null;
  if (!q || q.length < 3) return;
  aiSearchT = setTimeout(() => {
    aiLoad(); if (!aiReady('zoek') || q !== gsQ) return;
    aiSearchRes = { pending: true, q }; aiSearchId = Android.aiSearch(q); gsRender();
  }, 700);
}
window.onAiSearch = function(r){
  if (!r || r.id !== aiSearchId || typeof gsQ === 'undefined' || r.q !== gsQ) return;
  aiSearchRes = r.items ? { q: r.q, items: r.items } : null;
  if (typeof gsRender === 'function') gsRender();
};
function aiSearchHtml(ql){
  if (!aiSearchRes || aiSearchRes.q !== gsQ) return '';
  if (aiSearchRes.pending) return '<div class="card gsg aigs"><h3>' + uiIcon('sparkle') + 'Op betekenis <span class="n">…</span></h3><p class="note" style="margin:4px 0">Zoeken op betekenis…</p></div>';
  const it = aiSearchRes.items || [];
  if (!it.length) return '';
  return '<div class="card gsg aigs"><h3>' + uiIcon('sparkle') + 'Op betekenis <span class="n">' + it.length + '</span></h3>' + it.map(h =>
    '<button class=gi data-k="' + (h.kind === 'tx' ? 'tx' : 'notes') + '" data-x="' + esc(JSON.stringify(h.kind === 'tx' ? { id: h.id, from: +h.from || 0 } : { id: h.id })) + '" onclick="gsOpen(this)"><b>' + uiIcon(h.kind === 'tx' ? 'mic' : 'notes') + ' ' + esc(h.title) + '</b><small>' + esc(String(h.snip || '').slice(0, 160)) + (String(h.snip || '').length > 160 ? '…' : '') + '</small></button>').join('') + '</div>';
}

/* ---------- Spraak: een vraag inspreken (Whisper op de telefoon, zoals bij spraaknotities) ---------- */
let aiRec = false, aiRecT = null, aiVoiceBusy = false, aiVoiceDlT = null, aiVoiceChat = null;
function aiVState(t){ const e = $('#ai-vstate'); if (!e) return; e.textContent = t || ''; e.style.display = t ? '' : 'none'; }
/* Voor TalkBack alleen de overgangen (niet elke halve seconde de tijd) */
function aiVSay(t){ const e = $('#ai-vsay'); if (e) e.textContent = t; }
function aiMicUi(){
  const b = $('#ai-mic'); if (!b) return;
  b.classList.toggle('rec', aiRec); b.classList.toggle('busy', aiVoiceBusy);
  b.setAttribute('aria-disabled', aiVoiceBusy ? 'true' : 'false'); // niet disabled: dan raakt de focus kwijt
  b.innerHTML = uiIcon(aiRec ? 'stop' : 'mic');
  b.setAttribute('aria-label', aiRec ? 'Stoppen met inspreken' : aiVoiceBusy ? 'Bezig met uitschrijven' : 'Inspreken');
  $('#ai-in').disabled = aiRec || aiVoiceBusy;
}
function aiMic(){
  if (aiVoiceBusy) { toast('Even wachten: de vraag wordt uitgeschreven'); return; }
  if (aiRec) { aiRecStop(); return; }
  if (aiBusy) { toast('Wacht even tot het antwoord klaar is'); return; }
  const e = Android.aiVoiceStart();
  if (e === 'mic') { Android.musicRequestMic(); toast('Geef toegang tot de microfoon en tik dan opnieuw'); return; }
  if (e === 'model') { aiNeedVoiceModel(); return; }
  if (e) { toast(e); return; }
  aiRec = true; aiVoiceChat = aiChat; Android.vibrate(15); aiMicUi(); aiVSay('Luisteren. Tik op stoppen als je klaar bent.');
  const tick = () => {
    // Stopte de opname vanzelf (na 5 minuten: dan wordt er uitgeschreven; of de app ging even weg: dan is hij weg)?
    const st = Android.aiVoiceState();
    if (aiRec && st === 'working') { clearInterval(aiRecT); aiRec = false; aiVoiceBusy = true; aiVState('Uitschrijven…'); aiVSay('Uitschrijven'); aiMicUi(); return; }
    if (aiRec && st === 'idle') { clearInterval(aiRecT); aiRec = false; aiVState(''); aiMicUi(); toast('Het inspreken is gestopt'); return; }
    const ms = Android.voiceElapsed(); aiVState('● Luisteren… ' + Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0') + ' · tik ■ om te stoppen');
  };
  tick(); clearInterval(aiRecT); aiRecT = setInterval(tick, 500);
}
function aiRecStop(){
  clearInterval(aiRecT); aiRec = false; aiVoiceBusy = true;
  aiVState('Uitschrijven…'); aiVSay('Uitschrijven'); aiMicUi();
  Android.aiVoiceStop();
}
window.onAiVoice = function(r){
  clearInterval(aiRecT); aiRec = false; aiVoiceBusy = false; aiVState(''); aiMicUi();
  r = r || {};
  if (r.error) { toast(r.error); aiVSay(r.error); return; }
  const text = String(r.text || '').trim(); if (!text) return;
  const t = $('#ai-in'); t.value = (t.value.trim() ? t.value.trim() + ' ' : '') + text; aiGrow();
  aiVSay('Herkend: ' + text);
  // Alleen meteen versturen in hetzelfde gesprek, op dit scherm, als er niets loopt; anders staat het in het invoerveld
  const same = aiVoiceChat === aiChat && current === 'ai';
  if (aiSt.voiceSend !== false && same && !aiBusy) aiSendOrStop();
  else if (current === 'ai') t.focus();
};
/* Weg van het scherm (of ander gesprek) tijdens het inspreken: de opname stopt en wordt weggegooid */
function aiVoiceAbort(){
  if (!aiRec) return;
  Android.aiVoiceCancel(); clearInterval(aiRecT); aiRec = false; aiVState(''); aiMicUi();
}
// De app ging naar de achtergrond: Android stopt de opname daar zelf (MainActivity.onPause)
(function(){ const previous = window.onPauseApp; window.onPauseApp = function(){ if (previous) previous(); if (typeof aiRec !== 'undefined' && aiRec) { clearInterval(aiRecT); aiRec = false; aiVState(''); aiMicUi(); } }; })();
function aiNeedVoiceModel(){
  askConfirm('Spraakmodel nodig', 'Om in te spreken is een klein spraakmodel nodig (57 MB, eenmalig downloaden). Het draait op je telefoon; er gaat niets naar internet.', 'Downloaden', aiVoiceDownload);
}
function aiVoiceDownload(){
  const e = Android.txDownload('base');
  if (e) { toast(e); return; }
  toast('↓ Spraakmodel downloaden…');
  clearTimeout(aiVoiceDlT);
  const poll = () => {
    const s = rdJson(Android.txStatus(), {});
    if (current === 'aiset') aisRenderVoice(s);
    if (s.running) { if (current === 'ai') aiVState('↓ Spraakmodel downloaden… ' + (s.pct || 0) + '%'); aiVoiceDlT = setTimeout(poll, 800); return; }
    aiVState('');
    const v = rdJson(Android.aiVoiceInfo(), {});
    toast(v.model ? '✓ Spraakmodel klaar: tik op de microfoon en praat' : 'Het spraakmodel downloaden lukte niet');
    if (current === 'aiset') aisRenderVoice();
  };
  aiVoiceDlT = setTimeout(poll, 600);
}
function aisRenderVoice(st){
  const box = $('#ais-voice'); if (!box) return;
  const v = rdJson(Android.aiVoiceInfo(), {});
  if (!st) st = rdJson(Android.txStatus(), {});
  const running = !!(st.running && st.phase === 'download'); // niet elk bezig zijn van Gesprekken uitschrijven is een download
  box.innerHTML = '<div class="aimodel"><div class="aimt"><b>Spraakmodel</b><small>' + (v.model ? '✓ Klaar · ' + esc(v.label) : running ? '↓ Downloaden… ' + ((st && st.pct) || 0) + '%' : 'Nog niet gedownload (57 MB)') + '</small></div>' +
    '<div class="aimb">' + (v.model ? '<button class="mini" onclick="jumpTo(\'transcripts\')">Kiezen</button>' : running ? '' : '<button class="mini on" onclick="aiVoiceDownload()">Downloaden</button>') + '</div></div>';
  const s = $('#ais-vsend'); s.classList.toggle('on', v.send !== false); s.setAttribute('aria-checked', v.send !== false ? 'true' : 'false');
}
function aisVoiceSend(){ aiSt.voiceSend = !(aiSt.voiceSend !== false); Android.aiPref('voiceSend', aiSt.voiceSend); aisRenderVoice(); toast(aiSt.voiceSend ? 'Ingesproken vraag wordt meteen verstuurd' : 'Ingesproken tekst komt eerst in het invoerveld'); }
