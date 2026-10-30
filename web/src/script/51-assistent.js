/* ---------- Assistent: lokale AI (llama.cpp op de telefoon) en slim zoeken ---------- */
let aiSt = {}, aiChat = null, aiBusy = false, aiPollT = null, aiCur = null, aiGen = null; // aiGen: het gesprek waarin nu geantwoord wordt
/* [knop, tekst in het invoerveld, alleen met slim zoeken, soort]: soort 'gesprek' start een gesprek, 'vraag' = een hele vraag */
const AI_SUGG = [
  ['Start een gesprek', '', false, 'gesprek'],
  ['Hoeveel schermtijd vandaag?', 'Hoeveel schermtijd vandaag?', false, 'vraag'],
  ['Wat zei ik vannacht?', 'Wat zei ik vannacht?', false, 'vraag'],
  ['Zet een timer van 10 minuten', 'Zet een timer van 10 minuten', false, 'vraag'],
  ['Waar was mijn oortje het laatst?', 'Waar was mijn oortje het laatst?', false, 'vraag'],
  ['Vat samen', 'Vat deze tekst kort samen in een paar punten:\n\n'],
  ['Schrijf een e-mail', 'Schrijf een korte, vriendelijke e-mail over: '],
  ['Vertaal', 'Vertaal naar het Engels:\n\n'],
  ['Maak een takenlijst', 'Maak een overzichtelijke takenlijst van: '],
  ['Leg uit', 'Leg eenvoudig uit: '],
  ['Wat weet je over mij?', 'Wat weet je allemaal over mij?'],
  ['Wat staat er in mijn notities over…', '', true],
  ['Iets in de app instellen', 'Wat kan ik instellen bij ']
];
function aiLoad(){ aiSt = rdJson(Android.aiState(), {}); return aiSt; }
function aiModel(id){ return (aiSt.models || []).find(m => m.id === id) || {}; }
function aiReady(id){ return aiModel(id).state === 'ready'; }
function aiChatReady(){ return (aiSt.models || []).some(m => !m.embed && m.state === 'ready'); }
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
  else if (busy) btn = m.state === 'verifying' ? '' : (m.state === 'paused' && !m.waiting && !aiReplaced(m.id) ? '<button class="mini on" onclick="mdDl(\'ai:' + m.id + '\')">Verder</button>' : '') +
    '<button class="mini" onclick="mdStop(\'ai:' + m.id + '\')">Stoppen</button>';
  else if (m.state === 'failed' && !aiReplaced(m.id)) btn = '<button class="mini on" onclick="mdDl(\'ai:' + m.id + '\')">Opnieuw</button>' +
    '<button class="mini" onclick="mdStop(\'ai:' + m.id + '\')">Weghalen</button>';
  // Een vervangen model (bijv. mislukt downloaden) niet opnieuw aanbieden, alleen weghalen
  else if (aiReplaced(m.id)) btn = m.state === 'none' ? '' : '<button class="mini" onclick="aiDel(\'' + m.id + '\', true)">Verwijderen</button>';
  else btn = '<button class="mini on" onclick="aiDl(\'' + m.id + '\')">Downloaden</button>';
  const big = m.ram > 0 && aiSt.ram > 0 && aiSt.ram < m.ram; // minder geheugen dan aangeraden
  // Nieuw (vervangt een ouder model), of vervangen door een nieuwer dat al klaar is
  const prev = m.replaces ? aiModel(m.replaces) : {};
  const newer = prev.id && aiListed(prev) ? '<small class="ainew">' + uiIcon('star') + 'Nieuw' + (aiModel(m.replaces).label ? ', vervangt ' + esc(aiModel(m.replaces).label) : '') + (m.news ? ': ' + esc(m.news) : '') + '</small>' : '';
  const by = (aiSt.models || []).find(x => x.replaces === m.id && x.state === 'ready');
  const old = by && m.state === 'ready' ? '<small class="ainew">Vervangen door ' + esc(by.label) + (aiSt.chat === by.id ? '; dit model kun je verwijderen' : '') + '</small>' : '';
  return '<div class="aimodel' + (chosen ? ' on' : '') + '" data-id="' + m.id + '"><div class="aimt"><b>' + esc(m.label) + '</b><small>' + esc(m.desc) + '</small>' +
    '<small class="aims">' + esc(aiStateTxt(m)) + (big && m.state !== 'ready' ? ' · let op: je telefoon heeft ' + (aiSt.ram / 1e9).toFixed(0) + ' GB geheugen' : '') + '</small>' + newer + old +
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
function aiUse(id){
  Android.aiUse(id); aiRefresh(); toast('De assistent gebruikt nu: ' + aiModel(id).label);
  // Een nieuwer model in gebruik genomen: het oude mag weg (vragen, niet vanzelf)
  const m = aiModel(id), old = m.replaces && aiModel(m.replaces);
  if (old && old.state === 'ready') setTimeout(() => askConfirm(old.label + ' verwijderen?', 'Je gebruikt nu ' + m.label + '. Het oude model (' + aiStateTxt(old).replace('✓ Klaar · ', '') + ') kan weg om ruimte te maken.', 'Verwijderen', () => { Android.aiDelete(old.id); aiRefresh(); toast(old.label + ' verwijderd'); }), 400);
}
/* Taalmodellen om te tonen: een model dat door een nieuwer vervangen is alleen als je het al hebt (of downloadt) */
function aiReplaced(id){ return (aiSt.models || []).some(x => x.replaces === id); }
function aiListed(m){
  return !m.embed && (m.state !== 'none' || !aiReplaced(m.id));
}
/* Nieuwere modellen die een model vervangen dat je hebt (meegekomen met een app-update) en die je nog niet hebt */
function aiNewer(){
  let seen = []; try { seen = store.get('aiNewSeen') || []; } catch (e) { }
  return (aiSt.models || []).filter(m => m.replaces && aiReady(m.replaces) && m.state !== 'ready' && !seen.includes(m.id));
}
function aiNewHtml(){
  const n = aiNewer();
  if (!n.length) return '';
  const m = n[0];
  const busy = ['running', 'queued', 'paused', 'verifying'].includes(m.state);
  return '<div class="card ainewcard" role="status"><b>' + uiIcon('star') + 'Nieuw model: ' + esc(m.label) + '</b><p class="note">' + esc((m.news ? m.news + '. ' : '') + 'Vervangt ' + aiModel(m.replaces).label + '. ' + m.desc) + '</p>' +
    '<div class="ainewb"><button class="mini on" onclick="jumpTo(\'aisetmodel\')">' + (busy ? 'Bekijken' : 'Bekijken en downloaden') + '</button><button class="mini" onclick="aiNewLater(\'' + esc(m.id) + '\')">Niet nu</button></div></div>';
}
function aiNewLater(id){ let seen = []; try { seen = store.get('aiNewSeen') || []; } catch (e) { } if (!seen.includes(id)) seen.push(id); store.set('aiNewSeen', seen); aiRenderMain(); toast('Je vindt het nieuwe model bij Instellingen → Taalmodel'); }
function aiOldHtml(){
  const b = aiSt.orphans || 0;
  if (b < 1e6) return '';
  return '<div class="sw"><span>Oude modelbestanden<small>' + esc((b / 1e9).toFixed(1).replace('.', ',') + ' GB van modellen die de app niet meer gebruikt') + '</small></span><button class="mini" onclick="aiCleanOld()">Opruimen</button></div>';
}
function aiCleanOld(){ const n = +Android.aiCleanOld() || 0; aiRefresh(); toast(n ? (n / 1e9).toFixed(1).replace('.', ',') + ' GB vrijgemaakt' : 'Niets op te ruimen'); }
/* Alles bijwerken wat modellen toont; blijven kijken zolang er gedownload of gecontroleerd wordt */
function aiRefresh(){
  aiLoad();
  if (current === 'ai') aiRenderMain();
  if (aiSetOn()) aiRenderSet();
  clearTimeout(aiPollT);
  if (aiBusyDl() && (current === 'ai' || aiSetOn())) aiPollT = setTimeout(aiRefresh, 1500);
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
  // Zonder taalmodel kan het gesprek wel: vragen over de instellingen van de app (zie 59-assistent-instellingen.js)
  $('#ai-chat').style.display = '';
  $('#ai-new').style.display = ok ? '' : 'none';
  const tb = $('#ai-talk-btn'); if (tb) tb.style.display = aiTalkCan() ? '' : 'none';
  $('#ai-in').placeholder = ok ? 'Vraag iets…' : 'Vraag iets over de instellingen van de app…';
  if (!ok) {
    $('#ai-setup-models').innerHTML = (aiSt.models || []).filter(aiListed).map(m => aiModelCard(m, { chat: true })).join('');
    $('#ai-setup-note').textContent = !aiSt.supported ? 'Let op: de lokale AI werkt alleen op telefoons met een 64-bits ARM-processor.'
      : 'Downloaden gaat door op de achtergrond' + (aiSt.wifiOnly ? ', alleen via wifi' : '') + '. Je kunt het kleine model later altijd nog wisselen voor het grote, of andersom (bij Meer → Instellingen).';
    $('#ai-newmodel').innerHTML = '';
    $('#ai-notes').style.display = 'none';
    $('#ai-sugg').innerHTML = '<p class="note" style="margin:0 4px 8px">Ook zonder taalmodel kun je opdrachten geven, vragen stellen over je eigen gegevens en over de instellingen van de app, bijvoorbeeld:</p>' +
      AI_SET_SUGG.map((s, i) => '<button class="chip" onclick="aiSetSugg(' + i + ')">' + esc(s) + '</button>').join('') +
      (aiTalkCan() ? '<button class="chip" onclick="aiTalkStart()">' + uiIcon('mic') + 'Start een gesprek</button>' : '');
    aiSendBtn();
    return;
  }
  $('#ai-newmodel').innerHTML = aiNewHtml();
  const n = $('#ai-notes');
  n.style.display = aiReady('zoek') ? '' : 'none';
  n.classList.toggle('on', !!aiSt.notes); n.setAttribute('aria-checked', aiSt.notes ? 'true' : 'false');
  $('#ai-sugg').innerHTML = '<p class="note aisugh">Wat ik kan: vragen beantwoorden, je eigen gegevens bekijken (alleen wat je aanzet), en dingen voor je klaarzetten, altijd pas na jouw tik. Alles blijft op je telefoon.</p>' +
    AI_SUGG.filter(s => (!s[2] || aiReady('zoek')) && (s[3] !== 'gesprek' || aiTalkCan())).map(s => '<button class="chip' + (s[3] ? ' new' : '') + '" onclick="aiSugg(' + AI_SUGG.indexOf(s) + ')">' + (s[3] === 'gesprek' ? uiIcon('mic') : '') + esc(s[0]) + '</button>').join('');
  aiSendBtn();
}
const AI_SET_SUGG = ['Hoeveel schermtijd vandaag?', 'Zet een timer van 10 minuten', 'Wat kan ik instellen bij de assistent?', 'Waar vind ik de tekstgrootte?', 'Zet de nachtelijke backup aan'];
function aiSetSugg(i){ const t = $('#ai-in'); t.value = AI_SET_SUGG[i] || ''; aiGrow(); t.focus(); }
function aiSugg(i){
  const s = AI_SUGG[i]; if (!s) return;
  if (s[3] === 'gesprek') { aiTalkStart(); return; }
  if (s[2] && !aiSt.notes) aiToggleNotes();
  const t = $('#ai-in'); t.value = s[1]; aiGrow(); t.focus(); t.setSelectionRange(t.value.length, t.value.length);
}
function aiToggleNotes(){ aiSt.notes = !aiSt.notes; Android.aiPref('notes', aiSt.notes); aiRenderMain(); toast(aiSt.notes ? 'De assistent zoekt eerst in je gegevens (' + aiSrcNames() + ')' : 'Zonder je gegevens'); }
/* Tabbalk weg tijdens het typen (zie stijl.css: ai-typing); pas 0,4 s na het typen terug */
(function(){
  const t = document.getElementById('ai-in'); if (!t) return; let tm = null;
  t.addEventListener('focus', () => { clearTimeout(tm); document.body.classList.add('ai-typing'); });
  t.addEventListener('blur', () => { clearTimeout(tm); tm = setTimeout(() => { if (document.activeElement !== t) document.body.classList.remove('ai-typing'); }, 400); });
  // Toetsenbord dicht met Terug (het vak houdt de focus): tabbalk weer terug; tik je er weer in, dan weer weg
  const vv = window.visualViewport; if (!vv) return;
  let full = vv.height, w = vv.width;
  vv.addEventListener('resize', () => {
    if (Math.abs(vv.width - w) > 1) { w = vv.width; full = vv.height; } // gedraaid: opnieuw meten
    full = Math.max(full, vv.height);
    if (document.activeElement !== t) return;
    const kb = vv.height < full - 120;
    clearTimeout(tm);
    if (kb) document.body.classList.add('ai-typing');
    else tm = setTimeout(() => document.body.classList.remove('ai-typing'), 150);
  });
  t.addEventListener('pointerdown', () => { if (document.activeElement === t) document.body.classList.add('ai-typing'); });
})();
function aiGrow(){ const t = $('#ai-in'); if (!t.value.trim()) aiFromVoice = false; t.style.height = 'auto'; t.style.height = Math.min(160, t.scrollHeight) + 'px'; aiSendBtn(); }
function aiKey(e){ if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); aiSendOrStop(); } }
function aiSendBtn(){
  const b = $('#ai-send'); if (!b) return;
  if (typeof aiMicUi === 'function') { const m = $('#ai-mic'); if (m) m.classList.toggle('dim', aiBusy && !aiRec); } // tijdens een antwoord: zichtbaar maar gedimd (focus blijft)
  b.classList.toggle('stop', aiBusy);
  b.innerHTML = uiIcon(aiBusy ? 'stop' : 'up');
  b.setAttribute('aria-label', aiBusy ? 'Stoppen' : 'Versturen');
  // Vlak na versturen even uit: een dubbele tik stopt (of verstuurt) dan niet meteen nog eens
  b.disabled = aiSentAt > 0 || (!aiBusy && (!$('#ai-in').value.trim() || aiRec || aiVoiceBusy));
}
let aiSentAt = 0, aiSentT = null;
const AI_TAP_MS = 350; // zo snel als een dubbele tik (Android: 300 ms)
function aiSendOrStop(){
  if (aiSentAt && Date.now() - aiSentAt < AI_TAP_MS) return;
  if (aiBusy) { Android.aiCancel(); return; }
  const q = $('#ai-in').value.trim(); if (!q) return;
  const v = aiFromVoice; aiFromVoice = false;
  aiChat.msgs.push(Object.assign({ r: 'user', c: q, t: Date.now() }, v ? { v: true } : {}));
  if (!aiChat.title) aiChat.title = q.replace(/\s+/g, ' ').slice(0, 60);
  $('#ai-in').value = ''; aiGrow();
  // Een duidelijke opdracht (timer, wekker, radio, bericht …) of een feitenvraag over je eigen gegevens: de app zelf,
  // met een kaart om te bevestigen (61-assistent-acties.js). Alleen je eigen vraag, nooit tekst van anderen of van het model.
  const c = typeof aiCmdFor === 'function' ? aiCmdFor(q) : null;
  if (c) { aiLocalAnswer(c, v); return; }
  const d = typeof aiDataFor === 'function' ? aiDataFor(q) : null;
  if (d) { aiLocalAnswer(d, v); return; }
  // Een vraag over de instellingen van de app: soms zonder taalmodel (opdracht, opzoeken), soms met de instellingen erbij
  const h = typeof setHandle === 'function' ? setHandle(q) : null;
  if (h && h.local) { aiLocalAnswer(h, v); return; }
  if (!aiChatReady()) { aiLocalAnswer({ text: 'Daarvoor is een taalmodel nodig: download er hierboven een (eenmalig). Opdrachten zoals "zet een timer van 10 minuten", vragen als "hoeveel schermtijd vandaag" en vragen over de instellingen van de app, zoals "zet de nachtelijke backup aan", kan ik ook zonder.' }, v); return; }
  if (aiAsk(h) === 'bezig') { aiChat.msgs.pop(); $('#ai-in').value = q; aiFromVoice = v; aiGrow(); aiRenderMsgs(); }
}
/* Een antwoord van de app zelf (zonder taalmodel), bijv. over een instelling */
function aiLocalAnswer(h, v){
  aiSpeakStop();
  const m = Object.assign({ r: 'ai', c: h.text || '', t: Date.now(), local: true }, h.set ? { set: h.set } : {}, h.src ? { src: h.src } : {}, h.cmd ? { cmd: h.cmd } : {});
  aiChat.msgs.push(m);
  aiSaveChat(aiChat); aiRenderMsgs(); aiSendBtn();
  if (typeof aiTalkOn === 'function' && aiTalkOn()) { aiTalkLocal(m); return; } // gesprek: voorlezen (dan niet ook nog de schermlezer) en weer luisteren
  aiVSay(String(m.c).replace(/\*\*/g, '') + (aiCardOpen(m) ? ' Bevestig met de knop onder het antwoord.' : ''));
  if (v && aiSt.speakVoice && m.c) setTimeout(() => { if (current === 'ai' && !document.hidden) aiSpeakStart(m, aiChat); }, 0);
}
const AI_OTHERS = ['sms', 'wa', 'calls', 'contacts', 'tx'];
/* Staat er onder dit antwoord iets om te bevestigen (actie, instelling of opdracht)? */
function aiCardOpen(m){ return !!(m && ((m.act && m.act.st === 'voorgesteld') || (m.set && (m.set.items || []).some(x => x.st === 'voorgesteld')) || (m.cmd && m.cmd.st === 'voorgesteld'))); }
/* De vraag (met het gesprek tot nu toe) naar de assistent */
function aiAsk(opt){
  if (aiBusy) { toast('De assistent is nog bezig met een antwoord'); return 'bezig'; }
  aiSpeakStop(); // een nieuwe vraag: het voorlezen stopt
  // Een gesprek om samen te vatten (sms, WhatsApp, uitgeschreven) gaat als bijlage mee met de vraag (att.q)
  // Een antwoord dat op berichten van anderen steunde (sms, WhatsApp, oproepen, contacten, uitgeschreven gesprekken): oth,
  // dan leert de assistent niets zolang dat antwoord in het meegestuurde gesprek zit (LocalAi controleert dat)
  const aiOth = m => m.r !== 'user' && (m.src || []).some(h => AI_OTHERS.includes(h && h.kind));
  const msgs = aiChat.msgs.filter(m => !m.err && (m.r === 'user' || m.c)).map(m => Object.assign({ role: m.r === 'user' ? 'user' : 'assistant', content: m.r === 'user' && m.att && m.att.q ? m.att.q : m.c }, m.r === 'user' && m.att ? { att: true } : {}, aiOth(m) ? { oth: true } : {}));
  const lastQ = aiChat.msgs.filter(m => m.r === 'user').slice(-1)[0] || {};
  if (!opt && typeof setOfferFor === 'function' && !lastQ.att) opt = setOfferFor(lastQ.c || ''); // opnieuw antwoorden: weer met de instellingen
  const noDo = !!(opt && opt.noDo); // een opdracht zonder instelling: het model zegt nooit dat het iets deed
  if (opt && !opt.settings) opt = null;
  const others = aiChat.msgs.some(m => m.att); // een gesprek van anderen als bijlage: niets over jou leren en geen acties
  if (msgs.some(m => m.oth)) opt = null; // een eerder antwoord steunde op berichten van anderen: geen instellingen (hun tekst mag niets voorstellen)
  // Het gesprek eerst bewaren: dan heeft het een id (wat de assistent onthoudt, weet uit welk gesprek het komt)
  aiSaveChat(aiChat);
  // Eerst het (lege) antwoord klaarzetten: stukjes kunnen meteen binnenkomen
  aiCur = Object.assign({ r: 'ai', c: '', t: Date.now(), src: null }, opt && opt.set ? { set: JSON.parse(JSON.stringify(opt.set)) } : {});
  aiChat.msgs.push(aiCur); aiGen = aiChat;
  aiBusy = true; aiStatus('Denken…');
  aiSentAt = Date.now(); clearTimeout(aiSentT); aiSentT = setTimeout(() => { aiSentAt = 0; aiSendBtn(); }, AI_TAP_MS);
  aiRenderMsgs(); aiSendBtn();
  aiLearnFor = null;
  const e = Android.aiChat(JSON.stringify(Object.assign({ messages: msgs, notes: !!aiSt.notes && aiReady('zoek') && !lastQ.att, chat: aiChat.id || '', title: aiChat.title || '' },
    others ? { noLearn: true, noAct: true } : {}, lastQ.att ? { sum: true } : {}, opt && !others ? { settings: opt.settings } : {}, noDo && !others ? { noDo: true } : {})));
  if (e === AI_BUSY) {
    // Er loopt nog een antwoord (bijv. van een dubbele tik): deze vraag niet nog eens beantwoorden
    aiChat.msgs.splice(aiChat.msgs.indexOf(aiCur), 1); aiCur = null; aiGen = null; aiBusy = false; aiStatus('');
    aiRenderMsgs(); aiSendBtn(); toast('De assistent is nog bezig met een antwoord');
    setTimeout(() => aiResync(aiChat), 0); // de vraag gaat terug naar het invoerveld: ook uit het bewaarde gesprek
    return 'bezig';
  }
  if (e) { aiCur.c = e; aiCur.err = true; aiCur = null; aiGen = null; aiBusy = false; aiStatus(''); aiRenderMsgs(); aiSendBtn(); }
}
const AI_BUSY = 'De assistent is nog bezig'; // zelfde melding als AiBridge.BUSY
function aiStatus(t){ const s = $('#ai-status'); if (!s) return; s.textContent = t || ''; s.style.display = t && aiGen === aiChat ? '' : 'none'; }
const aiShown = () => aiGen && aiGen === aiChat && current === 'ai';
window.onAiStatus = function(t){ aiStatus(t); };
/* Er werd om een actie gevraagd: de actieregel ([[herinnering: …]]) tijdens het schrijven niet laten zien */
window.onAiActWanted = function(){ if (aiCur) aiCur.actOk = true; };
window.onAiSources = function(items){ if (aiCur) { aiCur.src = items; if (aiShown()) aiRenderLast(); } };
window.onAiToken = function(t){
  if (!aiCur) return;
  if (!aiCur.c) aiStatus('');
  aiCur.c += t; if (aiShown()) aiRenderLast();
  if (typeof aiTalkOn === 'function' && aiTalkOn()) aiTalkToken(aiCur);
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
    // Gestopt vóór het eerste woord: het (lege) antwoord weg, behalve als er iets met het geheugen gebeurde (dat blijft zichtbaar)
    if (info.cancelled && !m.c && !m.mem) chat.msgs.splice(chat.msgs.indexOf(m), 1);
    else if (info.cancelled && !m.c) { m.stopped = true; if (info.turn) { m.turn = info.turn; aiTurns[info.turn] = { chat, m }; } }
    else {
      if (typeof info.clean === 'string') m.c = info.clean; // zonder de actieregel ([[herinnering: …]])
      else if (m.actOk) m.c = aiNoActLine(m.c);
      delete m.actOk;
      m.c = m.c.trim(); if (info.cancelled) m.stopped = true; if (info.cut) m.cut = true; if (info.trim) m.trim = true;
      // Een voorgestelde actie: pas na een tik op de knop (zie aiActHtml)
      if (m.set && typeof setFromModel === 'function' && !info.cancelled) setFromModel(m, info.settings);
      if (info.action && info.action.type && !info.cancelled) { m.act = Object.assign({}, info.action, { st: 'voorgesteld' }); if (typeof aiVSay === 'function') aiVSay('Voorstel: ' + aiActLabel(m.act) + ', ' + m.act.title + '. Bevestig met de knop onder het antwoord.'); }
      // Gesprek met je stem: het antwoord voorlezen (Lokale AI → Spraak); in een Gesprek doet aiTalkDone dat
      if (!info.cancelled && m.c && aiSt.speakVoice && !(typeof aiTalkOn === 'function' && aiTalkOn())) { const q = chat.msgs.slice(0, chat.msgs.indexOf(m)).filter(x => x.r === 'user').slice(-1)[0]; if (q && q.v) setTimeout(() => { if (aiChat === chat && current === 'ai' && !document.hidden) aiSpeakStart(m, chat); }, 0); }
      if (info.mem) m.memUsed = +info.mem || 0;
      // De assistent kijkt nog of er iets te onthouden is: dat komt bij dit antwoord (onAiMemory, zelfde turn)
      if (info.learning) { m.mem = Object.assign({}, m.mem, { thinking: true }); }
      if (info.turn) { m.turn = info.turn; aiTurns[info.turn] = { chat, m }; }
    }
    if (typeof aiTalkOn === 'function' && aiTalkOn()) aiTalkDone(m, !!info.cancelled);
  });
};
/* Geheugen: wat de assistent onthield (of wil onthouden), altijd zichtbaar bij het antwoord */
let aiLearnFor = null; // (niet meer gebruikt voor het koppelen; zie aiTurns)
const aiTurns = {};    // turn → { chat, m }: bij welk antwoord een late geheugenmelding hoort
window.onAiMemory = function(o){
  o = o || {};
  // Vóór het antwoord (onthoud/vergeet): bij het antwoord dat nu geschreven wordt; daarna: bij het antwoord van die vraag
  let t = null;
  if (o.pre && aiCur) { aiCur.turn = o.turn; t = { chat: aiGen, m: aiCur }; }
  else if (o.turn && aiTurns[o.turn]) t = aiTurns[o.turn];
  else if (aiCur && aiCur.turn && aiCur.turn === o.turn) t = { chat: aiGen, m: aiCur };
  if (!t || !t.m || !t.chat || t.chat.discard) return;
  const mm = t.m.mem = Object.assign({}, t.m.mem);
  if (o.thinking) mm.thinking = true;
  else {
    if (o.saved || o.ask || o.none || o.error || o.stopped) delete mm.thinking;
    if (o.saved) mm.saved = (mm.saved || []).concat(o.saved.map(x => ({ id: x.id, text: x.text, dup: !!x.dup, explicit: !!o.explicit })));
    if (o.ask) mm.ask = o.ask.map(x => ({ text: x, st: 'ask' }));
    if (o.updated) (mm.saved || []).forEach(x => { if (x.id === o.updated.id) x.text = o.updated.text; });
    if (o.forgot != null) { mm.forgot = o.forgot || ''; if (o.forgotItem) mm.forgotItem = o.forgotItem; }
    if (o.error) mm.err = o.error;
    if (o.saved && !o.explicit) toast('Onthouden: ' + o.saved.map(x => x.text).join(' '));
    // Voor TalkBack: wat er met het geheugen gebeurde
    const say = o.saved ? 'Onthouden: ' + o.saved.map(x => x.text).join(' ') : o.ask ? 'De assistent stelt voor iets te onthouden: ' + o.ask.join(' ') : o.forgot ? 'Vergeten: ' + o.forgot : o.error || '';
    if (say && typeof aiVSay === 'function') aiVSay(say);
  }
  if (t.m !== aiCur) aiSaveChat(t.chat);
  if (aiChat === t.chat && current === 'ai') { const i = aiChat.msgs.indexOf(t.m), el = $('#ai-msgs .aimsg[data-i="' + i + '"]'); if (el) el.outerHTML = aiMsgHtml(t.m, i); }
  if (current === 'aisetmem') aimRender();
};
function aiMemHtml(m, i){
  const mm = m.mem || {}, out = [];
  (mm.saved || []).forEach((x, k) => out.push('<div class="aimemrow">' + uiIcon('memory') + '<span>' + (x.undone ? '<s>' + esc(x.text) + '</s> <small>(niet onthouden)</small>' : (x.dup ? 'Wist ik al: ' : 'Onthouden: ') + '<b>' + esc(x.text) + '</b>') + '</span>' +
    (x.undone || x.dup ? '' : '<button class="linkish" onclick="aiMemUndo(' + i + ',' + k + ')">Ongedaan maken</button>') + '</div>'));
  if (mm.forgot != null) out.push('<div class="aimemrow">' + uiIcon('memory') + '<span>' + (mm.forgot ? (mm.forgotBack ? 'Teruggezet: <b>' + esc(mm.forgot) + '</b>' : 'Vergeten: <s>' + esc(mm.forgot) + '</s>') : 'Daar stond niets over in het geheugen') + '</span>' +
    (mm.forgot && mm.forgotItem && !mm.forgotBack ? '<button class="linkish" onclick="aiMemForgetUndo(' + i + ')">Ongedaan maken</button>' : '') + '</div>');
  const ask = (mm.ask || []).filter(x => x.st !== 'no');
  if (ask.length) out.push('<div class="aimemask"><b>' + uiIcon('memory') + 'Onthouden?</b>' + (mm.ask || []).map((x, k) => x.st === 'no' ? '' :
    '<div class="aimemrow"><span>' + (x.st === 'ok' ? '✓ ' : '') + esc(x.text) + (x.dup ? ' <small>(wist ik al)</small>' : '') + '</span>' + (x.st === 'ok' ? (x.dup ? '' : '<button class="linkish" onclick="aiMemAskUndo(' + i + ',' + k + ')">Ongedaan maken</button>')
      : '<button class="mini on" onclick="aiMemAccept(' + i + ',' + k + ')" aria-label="Onthouden: ' + esc(x.text) + '">Onthouden</button><button class="mini" onclick="aiMemReject(' + i + ',' + k + ')" aria-label="Niet onthouden: ' + esc(x.text) + '">Nee</button>') + '</div>').join('') + '</div>');
  if (mm.thinking) out.push('<p class="note aimemthink">Kijken of er iets te onthouden is…</p>');
  if (mm.err) out.push('<p class="note">' + esc(mm.err) + '</p>');
  return out.length ? '<div class="aimem">' + out.join('') + '</div>' : '';
}
function aiMemMsg(i){ return aiChat && aiChat.msgs[i]; }
function aiMemAfter(i){ aiSaveChat(aiChat); const el = $('#ai-msgs .aimsg[data-i="' + i + '"]'); if (el) aiSwap(el, aiMsgHtml(aiChat.msgs[i], i)); if (typeof aiTalkCardDone === 'function') aiTalkCardDone(); }
/* Een stuk opnieuw tekenen zonder dat de focus (TalkBack, toetsenbord) kwijtraakt: dezelfde knop, of anders iets in het nieuwe stuk */
function aiSwap(el, html){
  const act = document.activeElement, had = act && el.contains(act);
  const key = had ? (act.getAttribute('onclick') || '') + '|' + (act.id || '') : '';
  const tmp = document.createElement('div'); tmp.innerHTML = html;
  const nu = tmp.firstElementChild; if (!nu) { el.remove(); return; }
  el.replaceWith(nu);
  if (!had) return;
  const same = [...nu.querySelectorAll('button, input, [tabindex]')].find(x => (x.getAttribute('onclick') || '') + '|' + (x.id || '') === key);
  const t = same || nu.querySelector('.aiact button, .aiact input, button, [tabindex]') || nu;
  if (t === nu && !nu.hasAttribute('tabindex')) nu.setAttribute('tabindex', '-1');
  t.focus({ preventScroll: true });
}
function aiMemAccept(i, k){
  const m = aiMemMsg(i), x = m && m.mem && m.mem.ask && m.mem.ask[k]; if (!x) return;
  const r = rdJson(Android.aiMemAdd(x.text, 'geleerd', aiChat.id || '', aiChat.title || ''), {});
  if (r.error) { toast(r.error); return; }
  x.st = 'ok'; if (r.dup) x.dup = true; else x.id = r.id; // een herinnering die er al was, niet weghalen bij ongedaan maken
  aiMemAfter(i); toast(r.dup ? 'Wist ik al (bijgewerkt)' : 'Onthouden');
}
function aiMemReject(i, k){ const m = aiMemMsg(i), x = m && m.mem && m.mem.ask && m.mem.ask[k]; if (!x) return; x.st = 'no'; aiMemAfter(i); }
function aiMemAskUndo(i, k){ const m = aiMemMsg(i), x = m && m.mem && m.mem.ask && m.mem.ask[k]; if (!x) return; if (x.id) Android.aiMemDelete(x.id); x.st = 'no'; delete x.id; aiMemAfter(i); toast('Niet onthouden'); }
function aiMemForgetUndo(i){
  const m = aiMemMsg(i), f = m && m.mem && m.mem.forgotItem; if (!f) return;
  const r = rdJson(Android.aiMemAdd(f.text, f.src || 'geleerd', f.chat || '', f.title || ''), {});
  if (r.error) { toast(r.error); return; }
  m.mem.forgotBack = true; aiMemAfter(i); toast('Teruggezet');
}
function aiMemUndo(i, k){ const m = aiMemMsg(i), x = m && m.mem && m.mem.saved && m.mem.saved[k]; if (!x) return; Android.aiMemDelete(x.id); x.undone = true; aiMemAfter(i); toast('Niet onthouden'); }
window.onAiError = function(msg){
  aiFinish((chat, m) => { if (!m.c) { m.c = msg; m.err = true; } else m.c += '\n\n⚠ ' + msg; if (typeof aiTalkOn === 'function' && aiTalkOn()) aiTalkDone(m, false); });
};
/* Het bewaarde gesprek gelijk trekken met wat er nu staat (na een geweigerde vraag); leeg = weg */
function aiResync(chat){
  if (!chat) return;
  if (chat.msgs.some(m => !m.err)) aiSaveChat(chat);
  else if (chat.id) { Android.aiChatDelete(chat.id); chat.id = ''; }
}
function aiSaveChat(chat){
  if (!chat || chat.discard || !chat.msgs.some(m => !m.err)) return;
  const memOf = m => { if (!m.mem) return undefined; const x = Object.assign({}, m.mem); delete x.thinking; return Object.keys(x).length ? x : undefined; };
  const id = Android.aiChatSave(JSON.stringify({ id: chat.id, title: chat.title, msgs: chat.msgs.filter(m => m !== aiCur || m.c).map(m => ({ r: m.r, c: m.c, t: m.t, src: m.src || undefined, err: m.err || undefined, stopped: m.stopped || undefined, cut: m.cut || undefined, trim: m.trim || undefined, mem: memOf(m), memUsed: m.memUsed || undefined, act: aiActOf(m), cmd: typeof aiCmdSaved === 'function' ? aiCmdSaved(m.cmd) : (m.cmd || undefined), att: m.att || undefined, v: m.v || undefined, set: m.set || undefined, local: m.local || undefined })) }));
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
/* Soorten gegevens waar slim zoeken en de assistent in mogen kijken (zie Lokale AI → Gegevens) */
const AI_SRC = [
  ['notes', 'Notities', 'notes', 'Al je notities en lijstjes.'],
  ['tx', 'Uitgeschreven gesprekken', 'mic', 'Wat je bij Gesprekken uitschrijven liet uitschrijven.'],
  ['sms', "Sms'jes", 'message', "De laatste 300 berichten van je 150 recentste sms-gesprekken."],
  ['wa', 'WhatsApp', 'message', 'De laatste 300 berichten van je 100 recentste chats (alleen als je de chats leesbaar maakte).'],
  ['calls', 'Oproepen', 'calls', 'Wie belde of gebeld werd, wanneer en hoe lang (de laatste 50 per persoon).'],
  ['contacts', 'Contacten', 'user', 'Namen, nummers, e-mailadressen, adressen, verjaardagen en notities.'],
  ['sleep', 'Slaappraat', 'moon', 'Wat je in je slaap zei (wat als praten is uitgeschreven), ook van de nacht die nog loopt als hij meteen uitschrijft (bijv. "Wat zei ik vannacht?").'],
  ['checkin', 'Check-in', 'smile', 'Je antwoorden bij Check-in, met notities en labels: per maand samengevat, en hoe het deze week gaat vergeleken met de week ervoor (bijv. "Hoe gaat het met mijn stemming?").'],
  ['gebruik', 'Gebruik', 'clock', 'Je schermtijd, ontgrendelingen en mislukte pogingen, vandaag en deze week, per app (bijv. "Hoeveel schermtijd vandaag?"). Nooit de foto\'s bij ontgrendelen, alleen hoeveel er zijn.'],
  ['apparaten', 'Apparaten', 'nearby', 'Waar je eigen apparaten het laatst gezien zijn en wat er bij de laatste zoekronde in de buurt was (bijv. "Waar was mijn oortje het laatst?"). Van apparaten van anderen alleen hoeveel.']
];
/* Bronnen die niet in de zoekindex gaan: de app rekent zelf de feiten uit (AiData) */
const AI_SRC_FACTS = ['gebruik', 'apparaten'];
function aiSrcOn(k){ const s = aiSt.src || {}; return s[k] != null ? !!s[k] : (k === 'notes' || k === 'tx'); }
function aiSrcNames(){ const n = AI_SRC.filter(s => aiSrcOn(s[0])).map(s => s[1].toLowerCase()); return n.length ? n.join(', ') : 'niets aangezet'; }
function aiKindIcon(k){ const s = AI_SRC.find(x => x[0] === k); return s ? s[2] : 'notes'; }
/* Een treffer openen zoals bij Zoeken (overal): [soort, gegevens voor gsOpen] */
function aiHitGs(h){
  const id = String(h.id || '');
  if (h.kind === 'tx') return ['tx', { id, from: +h.from || 0 }];
  if (h.kind === 'sleep') return ['sleep', { night: id, t: +h.from || 0 }];
  if (h.kind === 'checkin') { const p = id.split('|'); return ['checkin', { q: p[0], month: /^\d{4}-\d{2}$/.test(p[1] || '') ? p[1] : '' }]; }
  if (h.kind === 'sms') return ['sms', { thread: /^\d+$/.test(id) ? +id : id, name: h.title || '' }];
  if (h.kind === 'wa') return ['wa', { chat: /^\d+$/.test(id) ? +id : id, name: h.title || '' }];
  if (h.kind === 'calls') return ['calls', { number: id }];
  if (h.kind === 'contacts') { const i = id.lastIndexOf('|'); return ['contacts', { k: i < 0 ? id : id.slice(0, i), id: i < 0 ? '' : id.slice(i + 1) }]; }
  return ['notes', { id }];
}
function aiSrcHtml(src){
  if (!src || !src.length) return '';
  return '<div class="aisrc">' + src.map((h, i) => {
    // Feiten uit een tool (Gebruik, Slaappraat …): de knop opent die tool
    if (h && !h.id && AI_DATA_TOOL[h.kind]) return '<button class="aichip sm" onclick="jumpTo(\'' + AI_DATA_TOOL[h.kind] + '\')" aria-label="' + esc('Bron: ' + h.title + ', openen') + '">' + uiIcon(aiKindIcon(h.kind)) + esc(h.title) + '</button>';
    const g = aiHitGs(h); return '<button class="aichip sm" onclick="gsOpen(this)" data-k="' + esc(g[0]) + '" data-x="' + esc(JSON.stringify(g[1])) + '">[' + (i + 1) + '] ' + uiIcon(aiKindIcon(h.kind)) + esc(h.title) + '</button>'; }).join('') + '</div>';
}
function aiMsgHtml(m, i){
  if (!m) return '';
  if (m.r === 'user') return '<div class="aimsg me" data-i="' + i + '"><div class="aib">' + esc(m.c).replace(/\n/g, '<br>') + (m.att ? '<small class="aiatt">' + uiIcon(m.att.kind === 'tx' ? 'mic' : 'message') + esc(aiAttInfo(m.att)) + '</small>' : '') + '</div></div>';
  const live = m === aiCur;
  const shown = live && m.actOk ? aiNoActLine(m.c) : m.c; // tijdens het schrijven: de actieregel niet laten zien
  return '<div class="aimsg' + (m.err ? ' err' : '') + '" data-i="' + i + '"><div class="aib">' + (shown ? aiMd(shown) : (live ? '<span class="aidots" aria-label="Bezig"><i></i><i></i><i></i></span>' : '')) +
    (m.stopped ? '<p class="note">Gestopt.</p>' : '') + (m.cut ? '<p class="note">Het gesprek werd te lang: het begin is overgeslagen.</p>' : '') + (m.trim ? '<p class="note">Te lang voor het model: een deel van je bericht is weggelaten.</p>' : '') + '</div>' + aiSrcHtml(m.src) + aiMemHtml(m, i) + aiActHtml(m, i) + (typeof aiCmdHtml === 'function' ? aiCmdHtml(m, i) : '') + (typeof setMsgHtml === 'function' ? setMsgHtml(m, i) : '') +
    (!live && !m.err && m.c ? '<div class="aiacts"><button class="linkish" onclick="aiMsgMenu(' + i + ')" aria-label="Meer over dit antwoord">⋯ Meer</button>' + aiSpkBtn(m, i) +
      (+m.memUsed ? '<button class="linkish" onclick="jumpTo(\'aisetmem\')" aria-label="Met je geheugen: de assistent gebruikte ' + (+m.memUsed) + (+m.memUsed === 1 ? ' ding' : ' dingen') + ' die hij over je weet. Bekijken">' + uiIcon('memory') + ' Met je geheugen (' + (+m.memUsed) + ')</button>' : '') + '</div>' : '') + '</div>';
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
    ['Als notitie bewaren', () => aiToNote(m.c)],
    [aiSpk && aiSpk.m === m ? 'Stoppen met voorlezen' : 'Voorlezen', () => aiSpeakStart(m, aiChat)]
  ];
  if (i === aiChat.msgs.length - 1 && !aiBusy && !m.local && aiChatReady()) acts.push(['Opnieuw antwoorden', () => {
    // Het oude antwoord eerst weg; weigert de assistent (nog bezig), dan komt het terug
    const old = aiChat.msgs.pop();
    if (aiAsk() === 'bezig') { aiChat.msgs.push(old); aiRenderMsgs(); aiResync(aiChat); } // de melding geeft aiAsk zelf
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
  aiSpeakStop();
  if (aiBusy) Android.aiCancel(); // het lopende antwoord stopt en wordt (tot dan) bewaard in het oude gesprek
  aiChat = { id: '', title: '', msgs: [] };
  aiRenderMain(); aiRenderMsgs(); aiStatus('');
  setTimeout(() => $('#ai-in') && $('#ai-in').focus(), 50);
}
function aiMenu(){
  const acts = [];
  if (aiChatReady() || (aiChat && aiChat.msgs.length)) acts.push(['＋ Nieuw gesprek', () => aiNew()]);
  acts.push(['Eerdere gesprekken', () => show('aichats')]);
  if (aiChat && aiChat.msgs.length) acts.push(['Dit gesprek delen', () => Android.aiShare(aiChat.msgs.map(m => (m.r === 'user' ? 'Ik: ' : 'Assistent: ') + m.c).join('\n\n'))]);
  const cf = aiSt.cfg || {};
  acts.push(['Antwoorden: ' + [(AIC_LABEL.style[cf.style] || 'Normaal').toLowerCase(), (AIC_LABEL.creative[cf.creative] || 'Gebalanceerd').toLowerCase()].join(', ') + '…', () => jumpTo('aisetanswer')]);
  acts.push(['Geheugen (' + (aiSt.memCount || 0) + ')', () => jumpTo('aisetmem')]);
  acts.push(['Instellingen', () => show('aiset')]);
  openSheet('Assistent', aiSt.chat ? 'Model: ' + aiModel(aiSt.chat).label : '', acts);
}

/* Een bewaard gesprek openen: is het nog in het geheugen (er kan nog een geheugenmelding voor komen), dan dat object */
function aiChatObj(full){
  const t = Object.values(aiTurns).find(x => x.chat && x.chat.id === full.id && !x.chat.discard);
  return t ? t.chat : { id: full.id, title: full.title, msgs: full.msgs || [] };
}
/* Verwijderd gesprek: late geheugenmeldingen mogen het niet opnieuw bewaren */
function aiDiscardTurns(id){ Object.keys(aiTurns).forEach(k => { const t = aiTurns[k]; if (id === '*' || (t.chat && t.chat.id === id)) { if (t.chat) t.chat.discard = true; delete aiTurns[k]; } }); if (aiChat && (id === '*' || aiChat.id === id)) aiChat.discard = true; }
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
  else { if (aiBusy) Android.aiCancel(); aiChat = aiChatObj(full); }
  aiSpeakStop();
  show('ai');
}
function aicMenu(i){
  const c = aicList[i]; if (!c) return;
  openSheet(c.title || 'Gesprek', fmtD(c.t), [
    ['Openen', () => aicOpen(i)],
    ['Verwijderen', () => { if (aiGen && aiGen.id === c.id) { aiGen.discard = true; Android.aiCancel(); } aiDiscardTurns(c.id); Android.aiChatDelete(c.id); if (aiChat && aiChat.id === c.id) aiChat = null; enterAiChats(); toast('Gesprek verwijderd'); }]
  ]);
}

/* ---------- instellingen ---------- */
function enterAiSet(){ aiLoad(); aiRenderSet(); if (current === 'aisetmem') aimRender(); clearTimeout(aiPollT); if (aiBusyDl()) aiPollT = setTimeout(aiRefresh, 1500); }
function aiRenderSet(){
  const ms = aiSt.models || [];
  $('#ais-chat').innerHTML = ms.filter(aiListed).map(m => aiModelCard(m, { chat: true, del: true })).join('');
  $('#ais-old').innerHTML = aiOldHtml();
  $('#ais-embed').innerHTML = ms.filter(m => m.embed).map(m => aiModelCard(m, { del: true })).join('');
  const w = $('#ais-wifi'); w.classList.toggle('on', !!aiSt.wifiOnly); w.setAttribute('aria-checked', aiSt.wifiOnly ? 'true' : 'false');
  aisRenderVoice(); aicRender(); aiRenderData();
  const ix = aiSt.index, box = $('#ais-index');
  if (!ix || !aiReady('zoek')) { box.innerHTML = ''; return; }
  const busy = ix.busy || aiIdx.busy;
  box.innerHTML = aiIndexHtml('ais-ix-sub');
}
/* Stand van de zoekindex (bij Slim zoeken en bij Gegevens) */
function aiIndexHtml(subId){
  const ix = aiSt.index || {}, busy = ix.busy || aiIdx.busy;
  return '<div class="sw" style="margin-top:6px"><span>Zoekindex<small id="' + subId + '">' + esc(busy ? 'Bijwerken… ' + (aiIdx.done || ix.done || 0) + ' van ' + (aiIdx.total || ix.total || 0) + ' nieuwe stukken (gaat door op de achtergrond)'
      : (ix.items ? ix.chunks + ' stukken uit ' + ix.items + ' bronnen' + (ix.at ? ' · ' + fmtD(ix.at) : '') : 'Nog niet opgebouwd') + (ix.stale && ix.items ? (ix.stopped ? ' · gestopt: tik op Bijwerken om verder te gaan' : ' · wordt bij de volgende zoekvraag bijgewerkt') : '')) + '</small></span>' +
    (busy ? '<button class="mini" onclick="aisIndexStop()">Stoppen</button>' : '<button class="mini" onclick="aisIndex(false)">' + (ix.items ? 'Bijwerken' : 'Opbouwen') + '</button>') + '</div>' +
    (ix.items && !busy ? '<button class="linkish" onclick="aisIndex(true)">Helemaal opnieuw opbouwen</button>' : '');
}
let aiIdx = {};
function aisIndex(force){ aiIdx = { busy: true, done: 0, total: 0 }; Android.aiIndex(!!force); aiRenderSet(); }
function aisIndexStop(){ Android.aiIndexStop(); toast('Stoppen… wat al klaar is, blijft bewaard'); }
window.onAiIndex = function(o){
  // Voortgang: alleen de regel bijwerken (de rest blijft staan, ook de focus van TalkBack)
  if (o.busy && aiIdx.busy && aiSetOn() && ($('#ais-ix-sub') || $('#aid-ix-sub'))) {
    aiIdx = { busy: true, done: o.done, total: o.total };
    const t = 'Bijwerken… ' + o.done + ' van ' + o.total + ' nieuwe stukken (gaat door op de achtergrond)';
    ['#ais-ix-sub', '#aid-ix-sub'].forEach(id => { const e = $(id); if (e) e.textContent = t; });
    return;
  }
  if (o.busy) aiIdx = { busy: true, done: o.done, total: o.total };
  else { aiIdx = {}; if (o.error) toast(o.error); else if (!o.quiet) toast('Zoekindex bijgewerkt'); aiLoad(); }
  if (aiSetOn()) aiRenderSet();
};

/* ---------- Gegevens: waar de AI in mag kijken ---------- */
const AI_PERM = { sms: 'sms', calls: 'calllog', contacts: 'contactsread' };
function aiRenderData(){
  const list = $('#aid-list'); if (!list) return;
  const perm = aiSt.perm || {}, zoek = aiReady('zoek');
  $('#aid-need').innerHTML = zoek ? '' : '<p class="note">Voor zoeken in notities, gesprekken en berichten is het model voor slim zoeken nodig (' + esc(((aiSt.models || []).find(m => m.id === 'zoek') || {}).state === 'running' ? 'wordt gedownload' : 'nog niet gedownload') + '). <button class="linkish" style="display:inline;padding:0" onclick="jumpTo(\'aisetsearch\')">Naar Slim zoeken</button> Vragen over Gebruik, Apparaten, wat je vannacht zei en je Check-in werken ook zonder.</p>';
  list.innerHTML = AI_SRC.map(s => {
    const k = s[0], on = aiSrcOn(k), ok = perm[k] !== false;
    const need = !ok ? (k === 'wa' ? '<button class="linkish" style="display:inline;padding:0" onclick="jumpTo(\'wa\')">Maak eerst je chats leesbaar</button>'
      : k === 'gebruik' ? '<button class="linkish" style="display:inline;padding:0" onclick="jumpTo(\'gebruik\')">Zet eerst Gebruik bijhouden aan</button>'
      : '<button class="linkish" style="display:inline;padding:0" onclick="Android.permRequest(\'' + AI_PERM[k] + '\')">Toestemming geven</button>') : '';
    return '<div class="sw"><span>' + uiIcon(s[2]) + ' ' + esc(s[1]) + '<small id="aid-d-' + k + '">' + esc(s[3]) + (on && !ok ? ' Staat aan, maar de app mag er nog niet bij.' : '') + '</small>' + (on && !ok ? '<small>' + need + '</small>' : '') + '</span>' +
      '<button class="tg' + (on ? ' on' : '') + '" role="switch" aria-checked="' + on + '" aria-label="' + esc(s[1]) + '" aria-describedby="aid-d-' + k + '" onclick="aiSrcToggle(\'' + k + '\')"></button></div>';
  }).join('');
  $('#aid-index').innerHTML = zoek && aiSt.index ? aiIndexHtml('aid-ix-sub') : '';
}
function aiSrcToggle(k){
  const on = !aiSrcOn(k), s = AI_SRC.find(x => x[0] === k);
  aiSt.src = Object.assign({}, aiSt.src || {}, { [k]: on });
  Android.aiPref('src_' + k, on);
  if (on && (aiSt.perm || {})[k] === false && AI_PERM[k]) Android.permRequest(AI_PERM[k]);
  const facts = AI_SRC_FACTS.includes(k); // niet in de zoekindex: de app rekent de feiten zelf uit
  if (on && aiReady('zoek') && !facts) aiIdx = { busy: true, done: 0, total: 0 };
  aiRenderData(); aicRender();
  const one = k === 'wa' || k === 'sleep' || k === 'checkin' || k === 'gebruik';
  if (facts) { toast(on ? s[1] + (one ? ' doet' : ' doen') + ' mee: de assistent kan nu vragen daarover beantwoorden' : s[1] + (one ? ' doet' : ' doen') + ' niet meer mee'); return; }
  toast(on ? s[1] + (one ? ' doet' : ' doen') + ' mee' + (aiReady('zoek') ? ': wordt op de achtergrond aan de zoekindex toegevoegd' : '')
    : s[1] + (one ? ' doet niet meer mee en gaat' : ' doen niet meer mee en gaan') + ' uit de zoekindex');
}
/* Na "Toestemming geven": de lijst bijwerken (en de index haalt het er vanzelf bij) */
(function(){
  const prev = window.onSettingsChanged;
  window.onSettingsChanged = function(msg){
    if (typeof prev === 'function') prev(msg);
    if (current === 'aisetdata') { aiLoad(); aiRenderData(); if (aiReady('zoek') && aiSt.index && aiSt.index.stale && !aiSt.index.stopped) Android.aiIndexAuto(); }
  };
})();
function aisWifi(){ aiSt.wifiOnly = !aiSt.wifiOnly; Android.aiPref('wifiOnly', aiSt.wifiOnly); aiRenderSet(); toast(aiSt.wifiOnly ? 'Downloaden alleen via wifi (voor een nieuwe download)' : 'Downloaden ook via mobiel internet (voor een nieuwe download)'); }
function aisClearChats(){ askConfirm('Alle gesprekken verwijderen?', 'Alle gesprekken met de assistent gaan weg. De modellen, je notities en wat de assistent onthield (Geheugen) blijven.', 'Verwijderen', () => { if (aiGen) { aiGen.discard = true; Android.aiCancel(); } aiDiscardTurns('*'); Android.aiChatDelete('*'); aiChat = null; toast('Gesprekken verwijderd'); }); }

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
  aiSearchRes = r.items ? { q: r.q, items: r.items, updating: !!r.updating } : null;
  if (typeof gsRender === 'function') gsRender();
};
function aiSearchHtml(ql){
  if (!aiSearchRes || aiSearchRes.q !== gsQ) return '';
  if (aiSearchRes.pending) return '<div class="card gsg aigs"><h3>' + uiIcon('sparkle') + 'Op betekenis <span class="n">…</span></h3><p class="note" style="margin:4px 0">Zoeken op betekenis…</p></div>';
  const it = aiSearchRes.items || [];
  if (!it.length) return '';
  return '<div class="card gsg aigs"><h3>' + uiIcon('sparkle') + 'Op betekenis <span class="n">' + it.length + '</span></h3>' + it.map(h => { const g = aiHitGs(h);
    return '<button class=gi data-k="' + esc(g[0]) + '" data-x="' + esc(JSON.stringify(g[1])) + '" onclick="gsOpen(this)"><b>' + uiIcon(aiKindIcon(h.kind)) + ' ' + esc(h.title) + '</b><small>' + esc(String(h.snip || '').slice(0, 160)) + (String(h.snip || '').length > 160 ? '…' : '') + '</small></button>'; }).join('') +
    (aiSearchRes.updating ? '<p class="note" style="margin:4px 0">De zoekindex wordt nog bijgewerkt: misschien ontbreekt er nog iets.</p>' : '') + '</div>';
}

/* ---------- Spraak: een vraag inspreken (zoals in de app van Claude) ----------
   Tik op de microfoon: je ziet dat hij luistert (golf van je stem en de tijd), de tekst verschijnt al terwijl je praat
   (tussentekst: whisper op de telefoon, op het stuk tot nu toe), ✓ = klaar: de hele opname wordt nog één keer goed
   uitgeschreven en komt in het invoerveld om na te kijken of te versturen; ✕ = annuleren. Er gaat niets van de telefoon af. */
let aiRec = false, aiRecT = null, aiVoiceBusy = false, aiVoiceDlT = null, aiVoiceChat = null;
let aiVPre = '', aiVIgnore = false, aiVLv = [], aiVPart = '', aiVLive = false;
const AI_WAVE = 28; // aantal staafjes in de golf
function aiVState(t){ const e = $('#ai-vstate'); if (!e) return; e.textContent = t || ''; e.style.display = t ? '' : 'none'; }
/* Voor TalkBack alleen de overgangen (niet elke halve seconde de tijd) */
function aiVSay(t){ const e = $('#ai-vsay'); if (e) e.textContent = t; }
function aiMicUi(){
  const b = $('#ai-mic'); if (!b) return;
  b.classList.toggle('rec', aiRec); b.classList.toggle('busy', aiVoiceBusy);
  b.setAttribute('aria-disabled', aiVoiceBusy ? 'true' : 'false'); // niet disabled: dan raakt de focus kwijt
  b.innerHTML = uiIcon(aiRec ? 'check' : 'mic');
  b.setAttribute('aria-label', aiRec ? 'Stoppen met inspreken' : aiVoiceBusy ? 'Bezig met uitschrijven' : 'Inspreken');
  const t = $('#ai-in'); t.disabled = aiRec || aiVoiceBusy; t.classList.toggle('ailive', aiRec || aiVoiceBusy);
  const bar = $('#ai-vbar'); if (bar) bar.style.display = aiRec || aiVoiceBusy ? '' : 'none';
  const c = $('#ai-comp'); if (c) { c.classList.toggle('listening', aiRec || aiVoiceBusy); c.classList.toggle('rec', aiRec); }
  const w = $('#ai-wave'); if (w) w.classList.toggle('busy', aiVoiceBusy);
  aiSendBtn();
}
function aiWaveDraw(){
  const w = $('#ai-wave'); if (!w) return;
  if (w.children.length !== AI_WAVE) w.innerHTML = '<i></i>'.repeat(AI_WAVE);
  const lv = aiVLv.slice(-AI_WAVE);
  while (lv.length < AI_WAVE) lv.unshift(0);
  [...w.children].forEach((b, i) => { b.style.transform = 'scaleY(' + Math.max(0.08, Math.min(1, lv[i])).toFixed(2) + ')'; });
}
/* De tussentekst in het invoerveld (achter wat er al stond) */
function aiVText(part){ const t = $('#ai-in'); t.value = (aiVPre ? aiVPre + ' ' : '') + (part || ''); t.style.height = 'auto'; t.style.height = Math.min(160, t.scrollHeight) + 'px'; t.scrollTop = t.scrollHeight; }
function aiMic(){
  if (aiVoiceBusy) { toast('Even wachten: de vraag wordt uitgeschreven'); return; }
  if (aiRec) { aiRecStop(); return; }
  if (aiBusy) { toast('Wacht even tot het antwoord klaar is'); return; }
  aiSpeakStop();
  const e = Android.aiVoiceStart();
  if (e === 'mic') { Android.musicRequestMic(); toast('Geef toegang tot de microfoon en tik dan opnieuw'); return; }
  if (e === 'model') { aiNeedVoiceModel(); return; }
  if (e) { toast(e); return; }
  aiRec = true; aiVoiceChat = aiChat; aiVIgnore = false; aiVLv = []; aiVPart = ''; aiVLive = false;
  aiVPre = $('#ai-in').value.trim();
  Android.vibrate(15); aiMicUi(); aiWaveDraw(); aiVSay('Luisteren. Tik op het vinkje als je klaar bent, of op het kruisje om te annuleren.');
  let n = 0;
  const tick = () => {
    const o = typeof Android.aiVoiceLive === 'function' ? rdJson(Android.aiVoiceLive(), {}) : { state: Android.aiVoiceState(), ms: Android.voiceElapsed() };
    const st = o.state || Android.aiVoiceState();
    // Stopte de opname vanzelf (na 5 minuten: dan wordt er uitgeschreven; of de app ging even weg: dan is hij weg)?
    if (aiRec && st === 'working') { clearInterval(aiRecT); aiRec = false; aiVoiceBusy = true; aiVState('Uitschrijven…'); aiVSay('Uitschrijven'); aiMicUi(); return; }
    if (aiRec && st === 'idle') { clearInterval(aiRecT); aiRec = false; aiVState(''); aiVText(aiVPart = ''); aiMicUi(); toast('Het inspreken is gestopt'); return; }
    if (!aiRec) return;
    aiVLive = !!o.live;
    if (Array.isArray(o.lv) && o.lv.length) { aiVLv.push(...o.lv.map(Number)); if (aiVLv.length > 200) aiVLv = aiVLv.slice(-AI_WAVE); }
    else if (!aiVLive) aiVLv.push(0.25 + 0.2 * Math.abs(Math.sin(n / 3))); // gewone opname: geen niveau, wel beweging
    n++;
    aiWaveDraw();
    if (typeof o.partial === 'string' && o.partial !== aiVPart) { aiVPart = o.partial; aiVText(aiVPart); }
    const ms = +o.ms || 0, tm = Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0');
    const te = $('#ai-vtime'); if (te) te.textContent = tm;
    if (n % 5 === 1) aiVState('● Luisteren… ' + tm + (aiVPart ? '' : ' · praat maar'));
  };
  tick(); clearInterval(aiRecT); aiRecT = setInterval(tick, 100);
}
function aiRecStop(){
  clearInterval(aiRecT); aiRec = false; aiVoiceBusy = true;
  aiVState('Uitschrijven…'); aiVSay('Uitschrijven'); aiMicUi();
  Android.aiVoiceStop();
}
/* ✕: annuleren (ook tijdens het uitschrijven): wat je zei gaat weg, het invoerveld is weer zoals het was */
function aiVoiceCancelBtn(){
  if (!aiRec && !aiVoiceBusy) return;
  const busy = aiVoiceBusy;
  Android.aiVoiceCancel();
  clearInterval(aiRecT); aiRec = false; aiVoiceBusy = false; aiVIgnore = busy; aiVState('');
  aiVText(''); aiVPart = ''; aiMicUi();
  const t = $('#ai-in'); t.value = aiVPre; aiGrow(); t.focus();
  aiVSay('Inspreken geannuleerd'); toast('Geannuleerd');
}
window.onAiVoice = function(r){
  r = r || {};
  if (aiVIgnore || r.cancelled) { aiVIgnore = false; if (aiVoiceBusy) { aiVoiceBusy = false; aiVState(''); aiMicUi(); } return; } // geannuleerd
  clearInterval(aiRecT); aiRec = false; aiVoiceBusy = false; aiVState(''); aiMicUi();
  const t = $('#ai-in');
  if (r.error) { t.value = aiVPre; aiGrow(); toast(r.error); aiVSay(r.error); return; }
  const text = String(r.text || '').trim(); if (!text) { t.value = aiVPre; aiGrow(); return; }
  t.value = (aiVPre ? aiVPre + ' ' : '') + text; aiGrow();
  aiFromVoice = true; // ook als je hem eerst nakijkt: het antwoord mag dan voorgelezen worden
  if (r.note) toast(r.note); // vanzelf gestopt (bijv. de microfoon viel weg): dit is wat er was
  aiVSay('Herkend: ' + text);
  // Alleen meteen versturen als dat aan staat, in hetzelfde gesprek, op dit scherm, als er niets loopt; anders staat het in het invoerveld
  const same = aiVoiceChat === aiChat && current === 'ai';
  if (aiSt.voiceSend === true && same && !aiBusy) aiSendOrStop();
  else if (current === 'ai') { t.focus(); try { t.setSelectionRange(t.value.length, t.value.length); } catch (e) { } }
};
/* Weg van het scherm (of ander gesprek) tijdens het inspreken: de opname stopt en wordt weggegooid */
function aiVoiceAbort(){
  if (!aiRec) return;
  Android.aiVoiceCancel(); clearInterval(aiRecT); aiRec = false; aiVState(''); aiVText(''); aiVPart = ''; aiMicUi();
  const t = $('#ai-in'); if (t) { t.value = aiVPre; aiGrow(); }
}
// De app ging naar de achtergrond: Android stopt de opname daar zelf (MainActivity.onPause)
(function(){ const previous = window.onPauseApp; window.onPauseApp = function(){ if (previous) previous(); if (typeof aiRec !== 'undefined' && aiRec) { clearInterval(aiRecT); aiRec = false; aiVState(''); aiVText(''); aiVPart = ''; aiMicUi(); const t = $('#ai-in'); if (t) { t.value = aiVPre; aiGrow(); } }
  if (aiSpk) { aiSpk = null; aiSpkUi(); } }; })(); // het voorlezen stopt Android zelf (MainActivity.onPause)
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
    if (aiSetOn()) aisRenderVoice(s);
    if (s.running) { if (current === 'ai') aiVState('↓ Spraakmodel downloaden… ' + (s.pct || 0) + '%'); aiVoiceDlT = setTimeout(poll, 800); return; }
    aiVState('');
    const v = rdJson(Android.aiVoiceInfo(), {});
    toast(v.model ? '✓ Spraakmodel klaar: tik op de microfoon en praat' : 'Het spraakmodel downloaden lukte niet');
    if (aiSetOn()) aisRenderVoice();
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
  const s = $('#ais-vsend'); s.classList.toggle('on', v.send === true); s.setAttribute('aria-checked', v.send === true ? 'true' : 'false');
  const sp = $('#ais-vspeak'); if (sp) { sp.classList.toggle('on', !!aiSt.speakVoice); sp.setAttribute('aria-checked', aiSt.speakVoice ? 'true' : 'false'); }
  [['#ais-tspeak', 'talkSpeak'], ['#ais-tauto', 'talkAuto'], ['#ais-tbarge', 'talkBarge']].forEach(([id, k]) => { const e = $(id); if (e) { const on = aiSt[k] !== false; e.classList.toggle('on', on); e.setAttribute('aria-checked', on ? 'true' : 'false'); } });
}
/* Gesprek: voorlezen, vanzelf weer luisteren, onderbreken door te praten */
const AIS_TALK = { talkSpeak: ['Antwoorden worden voorgelezen in een gesprek', 'Antwoorden staan alleen op het scherm'], talkAuto: ['Na een antwoord luistert hij vanzelf weer', 'Na een antwoord tik je op Praten'], talkBarge: ['Onderbreken door te praten staat aan', 'Onderbreken alleen met de knop'] };
function aisTalk(k){ if (!AIS_TALK[k]) return; const on = aiSt[k] === false; aiSt[k] = on; Android.aiPref(k, on); aisRenderVoice(); aicRender(); toast(AIS_TALK[k][on ? 0 : 1]); }
function aisVoiceSpeak(){ aiSt.speakVoice = !aiSt.speakVoice; Android.aiPref('speakVoice', aiSt.speakVoice); aisRenderVoice(); aicRender(); toast(aiSt.speakVoice ? 'Antwoorden op een ingesproken vraag worden voorgelezen' : 'Antwoorden worden niet vanzelf voorgelezen'); }
function aisVoiceSend(){ aiSt.voiceSend = !(aiSt.voiceSend === true); Android.aiPref('voiceSend', aiSt.voiceSend); aisRenderVoice(); toast(aiSt.voiceSend ? 'Ingesproken vraag wordt meteen verstuurd' : 'Ingesproken tekst komt eerst in het invoerveld'); }

/* ---------- Instellingen van de assistent: overzicht en eigen schermen ---------- */
const AI_SUBS = ['aisetmodel', 'aisetanswer', 'aisetpers', 'aisetmem', 'aisetvoice', 'aisetdata', 'aisetsearch', 'aisetperf', 'aisetpriv'];
function aiSetOn(){ return current === 'aiset' || AI_SUBS.includes(current); }
const AIC_NOTE = {
  style: { kort: 'Een paar zinnen, alleen het belangrijkste. Ook het snelst.', normaal: 'Duidelijk en beknopt, met opsommingen waar dat helpt.', uitgebreid: 'Grondig, met uitleg en voorbeelden. Duurt langer.' },
  creative: { precies: 'Feitelijk en voorspelbaar: goed voor samenvatten en vertalen.', normaal: 'Een goede balans voor de meeste vragen.', creatief: 'Meer variatie: goed voor ideeën, verhalen en teksten.' },
  lang: { nl: 'Altijd Nederlands, tenzij je om een andere taal vraagt.', en: 'Altijd Engels.', auto: 'In de taal waarin je de vraag stelt.' },
  history: { '8': 'De laatste 8 berichten: sneller, maar vergeet eerder.', '24': 'De laatste 24 berichten.', '48': 'De laatste 48 berichten (past niet altijd: dan valt het begin weg).' },
  power: { zuinig: '2 processorkernen: trager, maar koeler en zuiniger met de accu.', normaal: 'Tot 4 kernen: een goede balans.', max: 'Tot 6 kernen: het snelst, maar warmer en meer accugebruik.' },
  learn: { vragen: 'Na een antwoord stelt de assistent voor wat hij zou willen onthouden; jij kiest Onthouden of Nee.', vanzelf: 'Hij onthoudt het meteen, en laat bij het antwoord zien wat (met Ongedaan maken).', uit: 'Hij leert niets uit je gesprekken. "Onthoud dat …" werkt wel.' },
  idle: { '1': 'Het model gaat na 1 minuut uit het geheugen: zuinig, maar de volgende vraag laadt opnieuw.', '4': 'Na 4 minuten niets doen.', '15': 'Na 15 minuten: vervolgvragen gaan sneller, maar het geheugen blijft langer bezet.' },
  talkSilence: { '1000': 'Snel: hij antwoordt na 1 seconde stilte (soms valt hij je in de rede).', '1300': 'Na 1,3 seconde stilte: een goede balans.', '1600': 'Na 1,6 seconde stilte: tijd om even na te denken.', '2200': 'Na 2,2 seconden stilte: rustig, voor lange zinnen met pauzes.' }
};
const AIC_LABEL = { style: { kort: 'Kort', normaal: 'Normaal', uitgebreid: 'Uitgebreid' }, creative: { precies: 'Precies', normaal: 'Gebalanceerd', creatief: 'Creatief' },
  lang: { nl: 'Nederlands', en: 'Engels', auto: 'Zelfde taal als de vraag' }, power: { zuinig: 'Zuinig', normaal: 'Normaal', max: 'Maximaal' } };
function aicCfg(){ return aiSt.cfg || {}; }
function aicRender(){
  const c = aicCfg();
  ['style', 'creative', 'lang', 'history', 'power', 'idle', 'learn', 'talkSilence'].forEach(k => {
    const box = $('#aic-' + k); if (!box) return;
    box.querySelectorAll('button').forEach(b => { const on = b.dataset.v === String(c[k]); b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
    const n = $('#aic-' + k + '-n'); if (n) n.textContent = (AIC_NOTE[k] || {})[c[k]] || '';
  });
  // Tekstvelden niet overschrijven terwijl je typt
  const a = $('#aic-about'), i = $('#aic-instr');
  if (a && !aicDirtyOn) { a.value = c.about || ''; i.value = c.instr || ''; }
  aicCount();
  // Regels in het overzicht
  const sum = (id, t) => { const e = $('#ai-sum-' + id); if (e) e.textContent = t; };
  const ready = (aiSt.models || []).filter(m => !m.embed && m.state === 'ready');
  const chat = (aiSt.models || []).find(m => m.id === aiSt.chat);
  const nieuw = (aiSt.models || []).some(m => m.replaces && aiReady(m.replaces) && m.state !== 'ready');
  sum('model', (ready.length ? (chat && chat.state === 'ready' ? chat.label : ready[0].label) : 'Nog geen model gedownload') + (nieuw ? ' · nieuw model beschikbaar' : ''));
  sum('answer', [AIC_LABEL.style[c.style], AIC_LABEL.creative[c.creative], AIC_LABEL.lang[c.lang]].filter(Boolean).join(' · '));
  sum('pers', c.about || c.instr ? [c.about ? 'Over mij' : '', c.instr ? 'eigen instructies' : ''].filter(Boolean).join(' en ') + ' ingevuld' : 'Nog niets ingevuld');
  const v = typeof Android.aiVoiceInfo === 'function' ? rdJson(Android.aiVoiceInfo(), {}) : {};
  sum('voice', (v.model ? 'Spraakmodel klaar' : 'Nog geen spraakmodel') + (v.send === true ? ' · meteen versturen' : '') + (aiSt.speakVoice ? ' · antwoord voorlezen' : '') + ' · gesprek: ' + String((+c.talkSilence || 1300) / 1000).replace('.', ',') + ' s stilte');
  const zoek = (aiSt.models || []).find(m => m.id === 'zoek') || {};
  sum('search', zoek.state === 'ready' ? (aiSt.index && aiSt.index.items ? aiSt.index.items + ' bronnen in de zoekindex' : 'Klaar') : 'Nog niet gedownload');
  const n = AI_SRC.filter(s => aiSrcOn(s[0])).length;
  sum('data', n ? AI_SRC.filter(s => aiSrcOn(s[0])).map(s => s[1]).join(', ') : 'Niets aangezet');
  sum('perf', (AIC_LABEL.power[c.power] || 'Normaal') + ' · vrijgeven na ' + (c.idle || 4) + ' min' + (aiSt.wifiOnly ? ' · alleen via wifi' : ''));
  sum('priv', 'Alles blijft op je telefoon');
  const mn = aiSt.memCount || 0;
  sum('mem', c.mem === 'uit' ? 'Uit' : (mn ? mn + (mn === 1 ? ' ding' : ' dingen') : 'Nog niets') + ' · ' + ({ vragen: 'vraagt eerst', vanzelf: 'leert vanzelf', uit: 'leert niet' }[c.learn] || 'vraagt eerst'));
  const mu = $('#aim-use');
  if (mu) {
    const on = c.mem !== 'uit';
    mu.classList.toggle('on', on); mu.setAttribute('aria-checked', on ? 'true' : 'false');
    $('#aim-use-n').textContent = on ? 'De assistent gebruikt wat hier staat in zijn antwoorden.' : 'Uit: de assistent gebruikt en onthoudt niets (wat er staat, blijft bewaard).';
    document.querySelectorAll('#aic-learn button').forEach(b => b.disabled = !on);
  }
}
function aicSet(name, v){
  const e = Android.aiConfigSet(name, v);
  if (e) { toast(e); return; }
  aiSt.cfg = Object.assign({}, aicCfg(), { [name]: v });
  aicRender();
  if (name === 'power' && aiBusy) toast('Geldt vanaf de volgende keer dat het model laadt');
}
let aicDirtyOn = false;
function aicCount(){ const a = $('#aic-about'), i = $('#aic-instr'), n = $('#aic-count'); if (n && a) n.textContent = (a.value.length + i.value.length) + ' tekens'; }
function aicDirty(){ aicDirtyOn = true; $('#aic-save').disabled = false; aicCount(); }
function aicSaveText(msg){
  const a = $('#aic-about').value, i = $('#aic-instr').value;
  const e = Android.aiConfigSet('about', a) || Android.aiConfigSet('instr', i);
  if (e) { toast(e); return; }
  aicDirtyOn = false; $('#aic-save').disabled = true;
  aiLoad(); aicRender(); toast(msg || 'Opgeslagen: geldt vanaf je volgende vraag');
}
/* Weg uit Persoonlijk met iets dat nog niet bewaard is: gewoon bewaren (niets kwijt) */
function aicLeavePers(){ if (aicDirtyOn) aicSaveText('Persoonlijk bewaard'); }
function aicReset(){
  askConfirm('Standaardinstellingen herstellen?', 'Lengte, creativiteit, taal, gespreksgeheugen, rekenkracht en wat je bij Persoonlijk invulde gaan terug naar de standaard. Je modellen, gesprekken, het geheugen en de keuzes daarvoor blijven.', 'Herstellen', () => {
    Android.aiConfigReset(); aicDirtyOn = false; aiLoad(); aicRender(); if ($('#aic-save')) $('#aic-save').disabled = true; toast('Standaardinstellingen hersteld');
  });
}

/* ---------- Geheugen: wat de assistent over je weet (alleen op de telefoon, alles te zien en aan te passen) ---------- */
let aimList = [];
const AIM_SRC = { gevraagd: 'jij vroeg het te onthouden', geleerd: 'geleerd uit een gesprek', zelf: 'zelf toegevoegd' };
function aimRender(){
  const box = $('#aim-list'); if (!box) return;
  aimList = rdJson(Android.aiMemList(), []);
  const qi = $('#aim-q'); qi.style.display = aimList.length > 8 ? '' : 'none';
  const q = aimList.length > 8 ? norm2(qi.value.trim()) : '';
  const list = q ? aimList.filter(m => norm2(m.text).includes(q)) : aimList;
  $('#aim-n').textContent = aimList.length ? aimList.length + (aimList.length === 1 ? ' ding' : ' dingen') + (q ? ' · ' + list.length + ' gevonden' : '') : '';
  box.innerHTML = list.map(m => {
    const i = aimList.indexOf(m);
    const from = AIM_SRC[m.src] || 'onthouden';
    const chat = m.chat ? '<button class="linkish" style="display:inline;padding:0" onclick="aimOpenChat(' + i + ')">' + esc(m.title ? '"' + m.title + '"' : 'het gesprek') + '</button>' : (m.title ? esc('"' + m.title + '"') : '');
    return '<div class="aimemitem" role="listitem"><div class="aimt"><span>' + esc(m.text) + '</span><small>' + esc(fmtD(m.u || m.t) + ' · ' + from) + (chat ? ' · ' + chat : '') + '</small></div>' +
      '<button class="iconbtn" onclick="aimEdit(' + i + ')" aria-label="Aanpassen: ' + esc(m.text) + '">' + uiIcon('edit') + '</button>' +
      '<button class="iconbtn" onclick="aimDel(' + i + ')" aria-label="Vergeten: ' + esc(m.text) + '">' + uiIcon('trash') + '</button></div>';
  }).join('') || '<p class="note">' + (q ? 'Niets gevonden.' : 'De assistent weet nog niets over je. Vertel iets over jezelf in een gesprek, zeg "onthoud dat …", of voeg hieronder zelf iets toe.') + '</p>';
  $('#aim-clear').style.display = aimList.length ? '' : 'none';
}
function aimUse(){ aicSet('mem', aicCfg().mem === 'uit' ? 'aan' : 'uit'); toast(aicCfg().mem === 'uit' ? 'Geheugen uit: de assistent gebruikt en onthoudt niets' : 'Geheugen aan'); }
function aimAdd(){
  askInput('Zelf iets toevoegen', 'Een korte zin over jezelf, bijvoorbeeld "Woont in Rotterdam." of "Wil antwoorden met een voorbeeld."', '', 'Onthouden', v => {
    const r = rdJson(Android.aiMemAdd(v, 'zelf', '', ''), {});
    if (r.error) { toast(r.error); return; }
    aiLoad(); aimRender(); aicRender(); toast(r.dup ? 'Stond er al (bijgewerkt)' : 'Onthouden');
  });
}
function aimEdit(i){
  const m = aimList[i]; if (!m) return;
  askInput('Aanpassen', 'Wat de assistent hierover onthoudt.', m.text, 'Opslaan', v => {
    const e = Android.aiMemEdit(m.id, v);
    if (e) { toast(e); return; }
    aimRender(); toast('Aangepast');
  });
}
function aimDel(i){
  const m = aimList[i]; if (!m) return;
  Android.aiMemDelete(m.id); aiLoad(); aimRender(); aicRender();
  undoToast('Vergeten: ' + m.text, () => { Android.aiMemAdd(m.text, m.src === 'zelf' ? 'zelf' : 'geleerd', m.chat || '', m.title || ''); aiLoad(); aimRender(); aicRender(); });
}
function aimClear(){
  askConfirm('Alles vergeten?', 'De assistent vergeet alles wat hier staat (' + aimList.length + (aimList.length === 1 ? ' ding' : ' dingen') + '). Je gesprekken blijven.', 'Alles vergeten', () => {
    Android.aiMemDelete('*'); aiLoad(); aimRender(); aicRender(); toast('Alles vergeten');
  });
}
function aimOpenChat(i){
  const m = aimList[i]; if (!m || !m.chat) return;
  const full = rdJson(Android.aiChatGet(m.chat), null);
  if (!full) { toast('Dat gesprek is er niet meer'); return; }
  if (aiGen && aiGen.id === full.id) aiChat = aiGen;
  else { if (aiBusy) Android.aiCancel(); aiChat = aiChatObj(full); }
  jumpTo('ai');
}

/* ---------- Acties: een notitie, herinnering of afspraak, alleen na een tik op de knop ----------
   De assistent stelt het voor (LocalAi/AiActions lezen de regel [[herinnering: …]] uit het antwoord); hier staat
   het voorstel onder het antwoord, met Zetten/Maken en Nee. "Onthoud dat …" is iets anders: dat is het Geheugen. */
let aiFromVoice = false; // de vraag in het invoerveld kwam van de microfoon
function aiNoActLine(s){ return String(s || '').replace(/`*\[\[[^\]\n]*(?:\]\]`*|$)/g, '').replace(/\n{3,}/g, '\n\n'); }
function aiActOf(m){ if (!m.act) return undefined; const a = Object.assign({}, m.act); delete a.pick; delete a.edit; return a; }
const AI_ACT = {
  herinnering: { icon: 'bell', label: 'Herinnering zetten', btn: 'Zetten', done: 'Herinnering gezet' },
  notitie: { icon: 'notes', label: 'Notitie maken', btn: 'Maken', done: 'Notitie gemaakt' },
  agenda: { icon: 'calendar', label: 'In je agenda zetten', btn: 'Agenda openen', done: 'Naar je agenda gestuurd' }
};
function aiActLabel(a){ return (AI_ACT[a.type] || AI_ACT.notitie).label; }
function aiActFmt(t, allDay){
  const d = new Date(t);
  return allDay ? d.toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short' }) + ' (hele dag)'
    : d.toLocaleString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}
/* Wat er in de tijdkiezer staat: wat je koos, het voorstel, of de dag met een tijd van het dagdeel (anders 9:00) */
function aiActPickVal(a){
  if (a.pick) return a.pick;
  if (a.at) return localIso(a.at);
  if (a.day) { const d = new Date(a.day), sug = a.sug >= 0 ? a.sug : 540; d.setHours(Math.floor(sug / 60), sug % 60, 0, 0); if (d.getTime() > Date.now()) return localIso(d.getTime()); }
  return localIso(Math.ceil((Date.now() + 36e5) / 3e5) * 3e5);
}
function aiActHtml(m, i){
  const a = m.act; if (!a || !a.type) return '';
  const k = AI_ACT[a.type] || AI_ACT.notitie;
  const when = a.type === 'notitie' ? '' : a.at ? aiActFmt(a.at) : a.allDay && a.day ? aiActFmt(a.day, true) : '';
  if (a.st === 'gedaan') return '<div class="aiact done" role="status">' + uiIcon('check') + '<span>' + esc(k.done) + (when ? ' voor ' + esc(when) : '') + ': <b>' + esc(a.title) + '</b>' +
    (a.type === 'agenda' ? '<small>Bewaar de afspraak in je agenda-app.</small>' : '') + '</span>' + (a.note ? '<button class="linkish" onclick="aiActOpen(' + i + ')">Openen</button>' : '') + '</div>';
  if (a.st === 'afgewezen') return '<div class="aiact no">' + uiIcon(k.icon) + '<span><s>' + esc(a.title) + '</s> <small>(niet gedaan)</small></span><button class="linkish" onclick="aiActUndoNo(' + i + ')">Toch voorstellen</button></div>';
  // Een voorstel van eerder (bijv. een heropend gesprek) waarvan het moment al voorbij is: opnieuw kiezen
  if (a.st === 'voorgesteld' && a.type !== 'notitie' && !a.need) {
    const end = a.at ? a.at : a.allDay && a.day ? new Date(a.day).setDate(new Date(a.day).getDate() + 1) : 0;
    if (end && end <= Date.now()) { a.need = 'datum'; a.at = 0; a.day = 0; a.allDay = false; }
  }
  const pick = a.type !== 'notitie' && (a.need || a.edit);
  const why = a.need === 'tijd' ? 'Kies nog een tijd' : a.need === 'datum' ? 'Kies wanneer' : 'Wanneer';
  return '<div class="aiact" role="group" aria-label="Voorstel: ' + esc(k.label) + '"><b>' + uiIcon(k.icon) + esc(k.label) + '?</b>' +
    (a.remFail ? '<p class="note" role="status" style="margin:0">De notitie is gemaakt, maar de herinnering zetten lukte niet. Probeer het nog eens.</p>' : '') +
    '<div class="aiactt">' + esc(a.title) + (a.text ? '<small>' + esc(a.text.length > 240 ? a.text.slice(0, 240) + '…' : a.text) + '</small>' : '') + '</div>' +
    (pick ? '<label class="aiactw"><span>' + why + '</span><input type="datetime-local" id="aiact-t-' + i + '" value="' + esc(aiActPickVal(a)) + '" oninput="aiActPick(' + i + ', this.value)"></label>'
      : when ? '<div class="aiactw">' + uiIcon('clock') + '<span>' + esc(when) + '</span><button class="linkish" onclick="aiActEditWhen(' + i + ')">Ander moment</button></div>' : '') +
    '<div class="aiactb"><button class="mini on" onclick="aiActDo(' + i + ')" aria-label="' + esc(k.btn + ': ' + a.title) + '">' + esc(k.btn) + '</button><button class="mini" onclick="aiActNo(' + i + ')" aria-label="' + esc('Niet doen: ' + a.title) + '">Nee</button>' +
    '<button class="linkish" onclick="aiActEditText(' + i + ')">Tekst aanpassen</button></div></div>';
}
function aiActMsg(i){ const m = aiChat && aiChat.msgs[i]; return m && m.act ? m : null; }
function aiActPick(i, v){ const m = aiActMsg(i); if (m) m.act.pick = v; }
function aiActEditWhen(i){ const m = aiActMsg(i); if (!m) return; m.act.edit = true; aiMemAfter(i); setTimeout(() => { const e = $('#aiact-t-' + i); if (e) e.focus(); }, 30); }
function aiActEditText(i){
  const m = aiActMsg(i); if (!m) return;
  askInput('Tekst aanpassen', m.act.type === 'notitie' ? 'De titel van de notitie.' : 'Waar het om gaat.', m.act.title, 'Opslaan', v => { v = String(v || '').trim(); if (!v) return; m.act.title = v.slice(0, 200); aiMemAfter(i); });
}
function aiActNo(i){ const m = aiActMsg(i); if (!m) return; m.act.st = 'afgewezen'; aiMemAfter(i); }
function aiActUndoNo(i){ const m = aiActMsg(i); if (!m) return; m.act.st = 'voorgesteld'; aiMemAfter(i); }
function aiActDo(i){
  const m = aiActMsg(i), a = m && m.act; if (!a || a.st !== 'voorgesteld') return;
  let at = a.at || 0, allDay = !!a.allDay && !a.edit && !a.need;
  const inp = $('#aiact-t-' + i);
  if (inp) { if (!inp.value) { toast('Kies een datum en tijd'); inp.focus(); return; } at = new Date(inp.value).getTime(); allDay = false; }
  if (a.type !== 'notitie' && !allDay && !(at > Date.now())) { toast('Kies een moment in de toekomst'); if (inp) inp.focus(); return; }
  if (!once('aiact' + (aiChat.id || '') + i)) return; // dubbel tikken: één keer
  if (a.type === 'agenda') {
    const begin = allDay ? a.day : at, end = allDay ? new Date(new Date(a.day).setDate(new Date(a.day).getDate() + 1)).getTime() : at + 36e5;
    const e = Android.aiCalendarAdd(JSON.stringify({ title: a.title, text: a.text || '', begin, end, allDay }));
    if (e) { toast(e); return; }
    a.at = allDay ? 0 : at; a.allDay = allDay;
  } else {
    let n = a.note && a.remFail ? noteById(a.note) : null; // al gemaakt bij een eerdere poging
    if (!n) {
      n = { id: uid(), title: a.title.slice(0, 80), text: a.text || (a.title.length > 80 ? a.title : ''), items: [], updated: Date.now() };
      const list = notesGet(); list.push(n);
      if (!notesSaveNow()) { const k = list.indexOf(n); if (k >= 0) list.splice(k, 1); toast('De notitie bewaren lukte niet'); return; }
    }
    a.note = n.id;
    if (a.type === 'herinnering') {
      const rid = Android.noteRemindAdd(n.id, n.title || 'Herinnering', String(at), '');
      if (!rid) {
        // De notitie is er, de herinnering niet: het voorstel blijft staan om het nog eens te proberen (zonder tweede notitie)
        a.remFail = true; aiMemAfter(i);
        toast('Notitie gemaakt; herinnering niet gezet'); aiVSay('Notitie gemaakt; herinnering niet gezet');
        return;
      }
      delete a.remFail; a.at = at;
    }
  }
  a.st = 'gedaan'; a.need = ''; delete a.pick; delete a.edit; delete a.remFail;
  aiMemAfter(i);
  const k = AI_ACT[a.type] || AI_ACT.notitie, msg = k.done + (a.type === 'notitie' ? '' : ' voor ' + aiActFmt(a.at || a.day, !a.at));
  toast(a.type === 'agenda' ? 'Je agenda-app is geopend: bewaar het daar' : msg); aiVSay(msg + ': ' + a.title);
}
function aiActOpen(i){
  const m = aiActMsg(i); if (!m || !m.act.note) return;
  if (!noteById(m.act.note)) { toast('Die notitie is er niet meer'); return; }
  jumpTo('note', () => openNote(m.act.note));
}

/* ---------- Voorlezen (tekst-naar-spraak van de telefoon, Nederlandse stem) ---------- */
let aiSpk = null, aiSpkSeq = 0; // { id, m, chat, on }: welk antwoord nu voorgelezen wordt
function aiSpkBtn(m, i){
  const on = aiSpk && aiSpk.m === m;
  return '<button class="linkish aispk" data-i="' + i + '" onclick="aiSpeakMsg(' + i + ')" aria-label="' + (on ? 'Stoppen met voorlezen' : 'Dit antwoord voorlezen') + '">' + uiIcon(on ? 'stop' : 'volume-high') + (on ? ' Stoppen' : ' Voorlezen') + '</button>';
}
function aiSpeakMsg(i){ const m = aiChat && aiChat.msgs[i]; if (m && m.c) aiSpeakStart(m, aiChat); }
function aiSpeakStart(m, chat){
  if (aiSpk && aiSpk.m === m) { aiSpeakStop(); return; } // nog eens tikken = stoppen
  if (aiRec) { toast('Eerst stoppen met inspreken'); return; }
  const id = 's' + (++aiSpkSeq);
  aiSpk = { id, m, chat, on: false };
  Android.aiSpeak(m.c, id);
  aiSpkUi();
}
function aiSpeakStop(){ if (!aiSpk) return; aiSpk = null; Android.aiSpeakStop(); aiSpkUi(); }
window.onAiSpeak = function(o){
  o = o || {};
  if (!aiSpk || o.id !== aiSpk.id) return;
  if (o.state === 'start') { aiSpk.on = true; aiSpkUi(); return; }
  aiSpk = null; aiSpkUi();
  if (o.state === 'error') { if (/stem|voorleesfunctie/.test(o.error || '')) askConfirm('Voorlezen lukt niet', o.error, 'OK', () => {}); else toast(o.error || 'Voorlezen lukte niet'); }
};
function aiSpkUi(){
  document.querySelectorAll('#ai-msgs .aispk').forEach(b => { const m = aiChat && aiChat.msgs[+b.dataset.i]; if (m) aiSwap(b, aiSpkBtn(m, +b.dataset.i)); });
  const bar = $('#ai-spkbar'); if (!bar) return;
  const show = !!aiSpk && aiSpk.chat === aiChat;
  bar.style.display = show ? '' : 'none';
  if (show) $('#ai-spktxt').textContent = aiSpk.on ? 'Voorlezen…' : 'Voorlezen starten…';
}

/* ---------- Samenvatten met één tik (sms, WhatsApp, uitgeschreven gesprek) ----------
   Een nieuw gesprek met de assistent; het gesprek gaat als bijlage mee (de nieuwste berichten die in het model passen).
   Alles blijft op de telefoon. */
function aiAttInfo(a){
  const u = a.kind === 'tx' ? ['zin', 'zinnen'] : ['bericht', 'berichten'];
  if (a.more) return a.total > a.n ? 'De laatste ' + a.n + ' van meer dan ' + a.total + ' ' + u[1] : 'De laatste ' + a.n + ' ' + u[1];
  return a.total > a.n ? 'De laatste ' + a.n + ' van ' + a.total + ' ' + u[1] : a.n + ' ' + (a.n === 1 ? u[0] : u[1]);
}
/* Hoeveel tekens van het gesprek in het model passen (±2,6 tekens per woordje; ruimte voor de instructie en het antwoord) */
function aiSumFit(){ const m = aiModel(aiSt.chat); const ctx = +m.ctx || 4096; return Math.max(1500, Math.min(11000, Math.floor((ctx - 700 - 600 - 150) * 2.6))); }
/* De nieuwste regels die passen (oud → nieuw); een heel lang laatste bericht wordt ingekort */
function aiSumTake(lines, max){
  let used = 0, k = lines.length;
  while (k > 0 && used + lines[k - 1].length + 1 <= max) { used += lines[k - 1].length + 1; k--; }
  if (k === lines.length && lines.length) return ['…' + lines[lines.length - 1].slice(-(max - 1))];
  const part = lines.slice(k);
  // Begint het midden op een dag: de dagregel erbij
  if (part.length && !/^— /.test(part[0])) { for (let j = k - 1; j >= 0; j--) if (/^— /.test(lines[j])) { part.unshift(lines[j]); break; } }
  return part;
}
function aiSummarize(kind, name, lines, more){
  aiLoad();
  if (!aiChatReady()) {
    askConfirm('Eerst een taalmodel nodig', 'Samenvatten doet de assistent op je telefoon. Download daarvoor eenmalig een taalmodel (vanaf 1,3 GB) bij Assistent. Er gaat niets van dit gesprek de telefoon uit.', 'Naar de assistent', () => jumpTo('ai'));
    return;
  }
  const real = lines.filter(l => l && !/^— /.test(l));
  if (!real.length) { toast('Er staat nog niets in dit gesprek'); return; }
  if (aiBusy) { toast('De assistent is nog bezig met een antwoord'); return; }
  const part = aiSumTake(lines, aiSumFit()), n = part.filter(l => !/^— /.test(l)).length, total = real.length;
  const what = kind === 'sms' ? "sms-gesprek met " + name : kind === 'wa' ? 'WhatsApp-chat met ' + name : 'uitgeschreven gesprek "' + name + '"';
  const q = 'Vat dit ' + what + ' samen in een paar korte punten: waar ging het over, wat is er afgesproken en wat moet er nog gebeuren (en door wie). Gebruik alleen wat in het gesprek staat en verzin niets.' +
    (kind === 'tx' ? '' : ' "Ik" ben ik zelf.') + '\n\n' + (n < total || more ? 'Het gesprek (alleen de laatste ' + n + (more ? '' : ' van ' + total) + (kind === 'tx' ? ' zinnen' : ' berichten') + '), van oud naar nieuw:' : 'Het gesprek, van oud naar nieuw:') + '\n' + part.join('\n');
  if (typeof aiVoiceAbort === 'function') aiVoiceAbort();
  aiSpeakStop();
  aiChat = { id: '', title: ('Samenvatting: ' + name).slice(0, 80), msgs: [{ r: 'user', c: 'Vat samen: ' + what, t: Date.now(), att: Object.assign({ kind, name, n, total, q }, more ? { more: true } : {}) }] };
  jumpTo('ai');
  if (aiAsk() === 'bezig') { aiChat.msgs = []; aiChat.title = ''; aiRenderMsgs(); } // nog bezig (melding komt van aiAsk): geen hangende vraag laten staan
}
/* Regels: een dagregel als de dag wisselt, dan "14:05 Jos: tekst" */
function aiSumLines(msgs, who){
  const out = []; let day = '';
  for (const m of msgs) {
    const text = String(m.text || m.label || '').replace(/\s+/g, ' ').trim(); if (!text) continue;
    const d = new Date(m.t), dd = d.toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
    if (dd !== day) { out.push('— ' + dd + ' —'); day = dd; }
    out.push(d.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }) + ' ' + (m.me ? 'Ik' : who(m)) + ': ' + text);
  }
  return out;
}
function smsSummarize(){
  let msgs; try { msgs = JSON.parse(Android.smsMessages(String(smsChatCur))); } catch (e) { msgs = []; }
  if (!Array.isArray(msgs)) { toast(msgs && msgs.error || 'Het gesprek lezen lukte niet'); return; }
  const name = $('#sms-title').textContent;
  aiSummarize('sms', name, aiSumLines(msgs, () => name));
}
function waSummarize(){
  let msgs; try { msgs = JSON.parse(Android.waMessages(String(chatCur), '0', 300)); } catch (e) { msgs = []; }
  if (!Array.isArray(msgs)) { toast('Het gesprek lezen lukte niet'); return; }
  const name = $('#chat-title').textContent;
  aiSummarize('wa', name, aiSumLines(msgs, m => m.sender || name), msgs.length >= 300); // 300 gelezen: er kunnen er meer zijn
}
function txSummarize(){
  if (!txT) return;
  aiSummarize('tx', txT.name || txT.file || 'Gesprek', (txT.segments || []).map(s => String(s.text || '').replace(/\s+/g, ' ').trim()).filter(Boolean));
}
