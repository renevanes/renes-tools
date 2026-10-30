/* ---------- Gesprek met de assistent (handsfree) ----------
 * Tik op Gesprek: hij luistert (live, whisper op de telefoon), merkt zelf dat je uitgesproken bent (stilte, instelbaar
 * bij Lokale AI → Spraak), schrijft het uit, vraagt het aan de assistent en leest het antwoord zin voor zin voor terwijl
 * het geschreven wordt. Daarna luistert hij weer. Stoppen: zeg "stop" of "klaar", tik op Stop, of ga weg uit de assistent.
 * Onderbreken: praat door hem heen (als de telefoon dat kan horen boven zijn eigen stem uit) of tik op Onderbreken.
 * Een voorstel (timer, bericht, instelling …) gebeurt ook hier pas na een tik: hij zegt "Bevestig op het scherm" en wacht.
 * Een telefoongesprek, Slaappraat, een gespreksopname of muziek herkennen gaat altijd voor. Het scherm blijft aan zolang
 * het gesprek loopt. Er gaat niets van de telefoon af. */
let aiTk = null;
const AI_TALK_STOP = /^(?:stop|stoppen|klaar|ik ben klaar|dat was het|dat was het wel|dat is alles|einde|hou op|houd op|ophouden|gesprek stoppen|stop het gesprek)$/i;
const AI_TALK_ST = {
  luistert: ['Ik luister…', 'Klaar met praten'],
  denkt: ['Even denken…', ''],
  spreekt: ['Ik praat…', 'Onderbreken'],
  pauze: ['Gepauzeerd', 'Praten'],
  bevestig: ['Bevestig op het scherm', 'Verder praten']
};
const AI_TALK_ICON = { luistert: 'mic', denkt: 'sparkle', spreekt: 'volume-high', pauze: 'pause', bevestig: 'check' };
function aiTalkOn(){ return !!aiTk; }
function aiTalkCan(){ return typeof Android.aiTalkListen === 'function'; }
function aiTalkCfg(){ const c = aiSt.cfg || {}; return { silence: +c.talkSilence || 1300, speak: aiSt.talkSpeak !== false, auto: aiSt.talkAuto !== false, barge: aiSt.talkBarge !== false }; }
/* Is het een "stop"? (de laatste leestekens tellen niet) */
function aiTalkIsStop(t){ return AI_TALK_STOP.test(String(t || '').toLowerCase().replace(/[.,!?…"']+/g, ' ').replace(/\s+/g, ' ').trim()); }
function aiTalkPlain(s){ return aiNoActLine(String(s || '')).replace(/\s*\[\d+\]/g, '').replace(/\*\*([^*]+)\*\*/g, '$1').replace(/[*_`#>]/g, '').replace(/^\s*[-•]\s+/gm, '').replace(/\n{2,}/g, '\n').trim(); }

function aiTalkStart(){
  if (aiTk) return;
  if (current !== 'ai') show('ai');
  if (aiRec || aiVoiceBusy) { toast('Eerst stoppen met inspreken'); return; }
  const p = rdJson(Android.aiTalkPoll(), {});
  if (p.conflict) { toast(p.conflict); return; }
  aiLoad(); aiSpeakStop();
  aiTk = { st: '', seq: 0, q: [], spk: null, gen: false, card: false, said: 0, wait: '', retry: 0, pend: '' };
  document.body.classList.add('talk-on');
  const o = $('#ai-talk'); o.hidden = false; o.classList.remove('mini');
  $('#ai-talk-me').textContent = ''; $('#ai-talk-ai').textContent = '';
  Android.aiTalkScreen(true); // het scherm blijft aan
  aiTk.pollT = setInterval(aiTalkWatch, 500);
  aiTalkListen();
  setTimeout(() => { const h = $('#ai-talk-t'); if (h && aiTk) h.focus({ preventScroll: true }); }, 0);
}
/* Wordt er gebeld, of pakt iets anders de microfoon (Slaappraat, opname, muziek herkennen)? Dan stoppen. */
function aiTalkWatch(){
  if (!aiTk) return;
  const p = rdJson(Android.aiTalkPoll(), {});
  if (p.call) aiTalkStop('Gesprek gestopt: er is een telefoongesprek');
  else if (p.conflict) aiTalkStop('Gesprek gestopt: ' + p.conflict.charAt(0).toLowerCase() + p.conflict.slice(1));
}
function aiTalkSt(st, hint){
  if (!aiTk) return;
  aiTk.st = st;
  const o = $('#ai-talk'); o.dataset.st = st; o.classList.toggle('mini', st === 'bevestig');
  o.setAttribute('aria-modal', st === 'bevestig' ? 'false' : 'true'); // bevestigen: de kaart eronder moet bereikbaar zijn
  $('#ai-talk-st').textContent = AI_TALK_ST[st][0];
  const ic = $('#ai-talk-orb .ui-icon'); if (ic) ic.dataset.icon = AI_TALK_ICON[st];
  const b = $('#ai-talk-go'), lab = AI_TALK_ST[st][1];
  b.textContent = lab; b.style.display = lab ? '' : 'none'; b.dataset.act = st;
  aiTalkHint(hint != null ? hint : st === 'bevestig' ? 'Tik op de knop onder het antwoord om het te doen of niet te doen, of op Verder praten.'
    : st === 'spreekt' ? (aiTalkCfg().barge && !aiTk.noBarge ? 'Praat gewoon door me heen om te onderbreken, of tik op Onderbreken.' : 'Tik op Onderbreken om me te onderbreken.')
    : st === 'luistert' ? 'Zeg "stop" of "klaar" om te stoppen.' : '');
  if (st === 'bevestig') requestAnimationFrame(() => scrollTo(0, document.documentElement.scrollHeight));
}
function aiTalkHint(t){ const h = $('#ai-talk-hint'); if (h) h.textContent = t || ''; }
function aiTalkLevel(lv){ const o = $('#ai-talk-orb'); if (o) o.style.setProperty('--lv', Math.max(0, Math.min(1, +lv || 0)).toFixed(2)); }
/* De grote knop: wat hij doet hangt af van de stand */
function aiTalkMain(){
  if (!aiTk) return;
  const st = aiTk.st;
  if (st === 'luistert') aiTalkHeard();
  else if (st === 'spreekt') aiTalkInterrupt(false);
  else if (st === 'pauze' || st === 'bevestig') { aiTk.retry = 0; aiTalkListen(); }
}

/* ---------- luisteren ---------- */
function aiTalkListen(){
  if (!aiTk) return;
  aiTalkBargeOff();
  aiTk.card = false; aiTk.gen = false; aiTk.q = []; aiTk.said = 0;
  const e = Android.aiTalkListen(aiTalkCfg().silence);
  if (e === 'mic') { Android.musicRequestMic(); aiTalkStop('Geef toegang tot de microfoon en start het gesprek opnieuw'); return; }
  if (e === 'model') { aiTalkStop(); aiNeedVoiceModel(); return; }
  if (e) { aiTalkStop(e); return; }
  aiTk.wait = 'luister';
  aiTalkSt('luistert');
  $('#ai-talk-me').textContent = '';
  clearInterval(aiTk.lvT); aiTk.lvT = setInterval(aiTalkTick, 100);
}
function aiTalkTick(){
  if (!aiTk || aiTk.st !== 'luistert') return;
  const o = rdJson(Android.aiVoiceLive(), {});
  if (Array.isArray(o.lv) && o.lv.length) aiTalkLevel(Math.max.apply(null, o.lv.map(Number)));
  if (typeof o.partial === 'string' && o.partial) $('#ai-talk-me').textContent = o.partial;
  if (o.live === false) aiTalkHint('Tik op Klaar met praten als je uitgesproken bent.'); // gewone opname: geen niveau om de stilte te horen
  if (o.state === 'idle') { aiTalkStop('Het luisteren stopte'); return; }
  if (o.state === 'working') { aiTalkThink(); return; } // vanzelf gestopt (na 5 minuten of de microfoon viel weg): wordt uitgeschreven
  if (o.end === 1 || o.end === 3) aiTalkHeard();
  else if (o.end === 2) { clearInterval(aiTk.lvT); Android.aiVoiceCancel(); aiTk.wait = ''; aiTalkSt('pauze', 'Ik hoorde niets. Tik op Praten als je iets wilt vragen.'); }
}
function aiTalkThink(){ clearInterval(aiTk.lvT); aiTk.wait = 'uitschrijven'; aiTalkLevel(0); aiTalkSt('denkt'); }
/* Uitgesproken (stilte, of de knop): de opname helemaal uitschrijven */
function aiTalkHeard(){ if (!aiTk) return; aiTalkThink(); Android.aiVoiceStop(); }
(function(){
  const prev = window.onAiVoice;
  window.onAiVoice = function(r){
    if (aiTk && (aiTk.wait === 'uitschrijven' || aiTk.wait === 'luister')) { aiTalkVoice(r || {}); return; }
    if (prev) prev(r);
  };
})();
function aiTalkVoice(r){
  aiTk.wait = '';
  if (r.cancelled) return;
  if (r.error) {
    if (/geen spraak|te kort/i.test(r.error) && aiTk.retry++ < 2) { aiTalkListen(); return; }
    aiTalkSt('pauze', r.error); return;
  }
  const text = String(r.text || '').trim();
  if (!text) { aiTalkListen(); return; }
  aiTk.retry = 0;
  $('#ai-talk-me').textContent = text;
  if (aiTalkIsStop(text)) { aiTalkStop('Gesprek gestopt'); return; }
  if (aiBusy) { aiTk.pend = text; aiTalkSt('denkt'); return; } // het vorige antwoord stopt nog
  aiTalkAsk(text);
}
function aiTalkAsk(text){
  if (!aiTk) return;
  $('#ai-talk-ai').textContent = '';
  aiTk.wait = 'antwoord'; aiTk.gen = false; aiTk.q = []; aiTk.said = 0; aiTk.card = false; aiTk.pend = '';
  aiTalkSt('denkt');
  const t = $('#ai-in'); t.value = text; aiGrow(); aiFromVoice = true; aiSentAt = 0;
  aiSendOrStop();
  // Geweigerd (er liep nog een antwoord): straks opnieuw
  if (aiTk && aiTk.wait === 'antwoord' && $('#ai-in').value.trim() === text) { aiTk.pend = text; t.value = ''; aiGrow(); }
}

/* ---------- antwoorden en voorlezen ---------- */
/* Een antwoord van de app zelf (opdracht, gegevens, instelling): meteen voorlezen */
function aiTalkLocal(m){
  if (!aiTk) return;
  aiTk.wait = '';
  $('#ai-talk-ai').textContent = aiTalkPlain(m.c);
  aiTk.q = aiTalkCfg().speak ? [aiTalkPlain(m.c)] : [];
  aiTk.card = aiCardOpen(m);
  if (aiTk.card) aiTk.q.push('Bevestig op het scherm.');
  aiTk.gen = true;
  aiTalkPump();
}
/* Tijdens het schrijven: elke hele zin meteen voorlezen */
function aiTalkToken(m){
  if (!aiTk || aiTk.wait !== 'antwoord') return;
  const plain = m.actOk ? aiNoActLine(m.c) : m.c;
  $('#ai-talk-ai').textContent = aiTalkPlain(plain);
  if (!aiTalkCfg().speak) return;
  const rest = plain.slice(aiTk.said);
  const mm = /^[\s\S]*[.!?…:](?=\s)|^[\s\S]*\n/.exec(rest); // tot en met de laatste hele zin
  if (mm && aiTalkPlain(mm[0]).length >= 2) { aiTk.said += mm[0].length; aiTk.q.push(aiTalkPlain(mm[0])); aiTalkPump(); }
}
/* Klaar met schrijven (of gestopt, of een fout) */
function aiTalkDone(m, cancelled){
  if (!aiTk) return;
  if (aiTk.wait !== 'antwoord') { // bijv. onderbroken: dit antwoord telt niet meer; een vraag die wachtte, nu stellen
    if (aiTk.pend) { const p = aiTk.pend; setTimeout(() => aiTalkAsk(p), 0); }
    return;
  }
  aiTk.wait = '';
  const c = String(m && m.c || '');
  if (aiTalkCfg().speak && !cancelled) { const rest = aiTalkPlain(c.slice(aiTk.said)); if (rest) aiTk.q.push(rest); }
  aiTk.said = c.length;
  aiTk.card = !cancelled && aiCardOpen(m);
  if (aiTk.card) aiTk.q.push('Bevestig op het scherm.');
  aiTk.gen = true;
  $('#ai-talk-ai').textContent = aiTalkPlain(c);
  aiTalkPump();
}
function aiTalkPump(){
  if (!aiTk || aiTk.spk) return;
  if (aiTk.q.length && aiTalkCfg().speak && !aiTk.noTts) {
    const text = aiTk.q.join(' ').replace(/\s+/g, ' ').trim(); aiTk.q = [];
    if (text) {
      const id = 'k' + (++aiTk.seq); aiTk.spk = id;
      aiTalkSt('spreekt');
      Android.aiSpeak(text, id);
      aiTalkBargeOn();
      return;
    }
  }
  aiTk.q = [];
  if (aiTk.gen) aiTalkAfter();
}
function aiTalkAfter(){
  aiTalkBargeOff();
  if (aiTk.card) { aiTalkSt('bevestig'); return; }
  if (aiTalkCfg().auto) aiTalkListen(); else aiTalkSt('pauze');
}
(function(){
  const prev = window.onAiSpeak;
  window.onAiSpeak = function(o){
    o = o || {};
    if (String(o.id || '').charAt(0) === 'k') {
      if (!aiTk || o.id !== aiTk.spk || o.state === 'start') return;
      aiTk.spk = null;
      if (o.state === 'error') { aiTk.noTts = true; toast(o.error || 'Voorlezen lukte niet'); }
      aiTalkPump();
      return;
    }
    if (prev) prev(o);
  };
})();
/* Een voorstel werd bevestigd of afgewezen: weer verder praten */
function aiTalkCardDone(){
  if (!aiTk || aiTk.st !== 'bevestig' || !aiChat || aiChat.msgs.some(aiCardOpen)) return;
  if (aiTalkCfg().auto) aiTalkListen(); else aiTalkSt('pauze');
}

/* ---------- onderbreken ---------- */
function aiTalkBargeOn(){
  if (!aiTk || !aiTalkCfg().barge || aiTk.bargeT || aiTk.noBarge) return;
  const e = Android.aiBargeStart();
  if (e) { aiTk.noBarge = true; aiTalkSt('spreekt'); return; } // dan alleen de knop Onderbreken
  aiTk.bargeT = setInterval(() => { if (!aiTk) return; const b = rdJson(Android.aiBarge(), {}); if (b.hit) aiTalkInterrupt(true); }, 100);
}
function aiTalkBargeOff(){ if (aiTk && aiTk.bargeT) { clearInterval(aiTk.bargeT); aiTk.bargeT = null; } Android.aiBargeStop(); }
function aiTalkInterrupt(byVoice){
  if (!aiTk) return;
  aiTalkBargeOff();
  aiTk.q = [];
  if (aiTk.spk) { aiTk.spk = null; Android.aiSpeakStop(); }
  if (aiBusy && aiTk.wait === 'antwoord') Android.aiCancel(); // het antwoord stopt (wat er al stond, blijft)
  aiTk.card = false;
  aiVSay(byVoice ? 'Onderbroken. Ik luister.' : 'Onderbroken');
  aiTalkListen();
}

/* ---------- stoppen ---------- */
function aiTalkStop(msg){
  if (!aiTk) return;
  const t = aiTk; aiTk = null;
  clearInterval(t.lvT); clearInterval(t.pollT); if (t.bargeT) clearInterval(t.bargeT);
  Android.aiBargeStop();
  if (Android.aiVoiceState() !== 'idle') Android.aiVoiceCancel();
  if (t.spk) Android.aiSpeakStop();
  if (t.wait === 'antwoord' && aiBusy) Android.aiCancel();
  Android.aiTalkScreen(false);
  document.body.classList.remove('talk-on');
  const o = $('#ai-talk'); if (o) { o.hidden = true; o.classList.remove('mini'); }
  if (msg) toast(msg);
  aiVSay(msg || 'Gesprek gestopt');
  aiSendBtn();
  const b = $('#ai-talk-btn'); if (b && current === 'ai' && b.offsetParent) b.focus({ preventScroll: true });
}
/* Terug (knop of gebaar): eerst het gesprek stoppen */
function aiTalkBack(){ if (!aiTk) return false; aiTalkStop(); return true; }
(function(){ const prev = window.onPauseApp; window.onPauseApp = function(){ if (prev) prev(); if (aiTk) aiTalkStop(); }; })();
