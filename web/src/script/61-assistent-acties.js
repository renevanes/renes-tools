/* ---------- Assistent: vragen over je eigen gegevens en opdrachten met bevestiging ----------
 * Gegevens (AiData in Java): "hoeveel schermtijd vandaag", "wat zei ik vannacht", "hoe gaat het volgens Check-in",
 * "waar was mijn oortje het laatst". Een duidelijke vraag beantwoordt de app zelf (meteen, precies, ook zonder taalmodel);
 * een andere vraag gaat naar het taalmodel, dat dan kant-en-klare feiten meekrijgt (LocalAi). Alleen bronnen die je zelf
 * aanzette (Lokale AI → Gegevens), nooit in een losse app, en er gaat niets van de telefoon af.
 *
 * Opdrachten (AiCommands in Java, alleen uit je eigen vraag): timer, wekker, radio, podcast, Slaappraat, een tool
 * openen, een sms of WhatsApp-bericht klaarzetten. Altijd eerst een kaart; pas na een tik op de knop gebeurt het.
 * Een bericht wordt alleen klaargezet in Berichten of WhatsApp: versturen doe je daar zelf. Waar het kan: Ongedaan maken. */

/* ---------- je eigen gegevens ---------- */
const AI_DATA_TOOL = { gebruik: 'gebruik', apparaten: 'apparaten', sleep: 'sleep', checkin: 'checkin' };
function aiDataFor(q){
  if (typeof Android.aiData !== 'function') return null;
  const r = rdJson(Android.aiData(q), null);
  if (!r || !r.topic) return null;
  const label = r.label || r.topic;
  if (r.solo) return { text: 'In een losse app kijk ik niet in je ' + label + ': die opent zonder app-slot. Vraag het in Tools zelf.' };
  if (r.off) return { text: 'Daarvoor mag ik nog niet in **' + label + '** kijken. Dat zet je zelf aan bij Lokale AI → Gegevens; daarna kan ik het je vertellen. Alles blijft op je telefoon.', set: { open: ['ai.bron.' + r.topic] } };
  if (r.text) return { text: r.text, src: [{ kind: r.topic, id: '', title: r.title || label }] };
  return null; // het taalmodel antwoordt, met de feiten erbij (LocalAi)
}

/* ---------- opdrachten ---------- */
const AI_CMD_SOLO = ['timer', 'wekker']; // in een losse app: alleen wat niets van je gegevens of andere tools raakt
const AI_CMD = {
  timer: { icon: 'clock', title: 'Timer zetten?', btn: 'Timer zetten' },
  wekker: { icon: 'bell', title: 'Wekker zetten?', btn: 'Wekker zetten' },
  radio: { icon: 'radio', title: 'Radio afspelen?', btn: 'Afspelen' },
  podcast: { icon: 'headphones', title: 'Podcast afspelen?', btn: 'Afspelen' },
  slaap: { icon: 'moon', title: 'Slaappraat starten?', btn: 'Starten' },
  open: { icon: 'external', title: 'Openen?', btn: 'Openen' },
  sms: { icon: 'message', title: 'Sms klaarzetten?', btn: 'Klaarzetten in Berichten' },
  wa: { icon: 'message', title: 'WhatsApp-bericht klaarzetten?', btn: 'Klaarzetten in WhatsApp' }
};
function aiDur(s){
  s = Math.round(+s || 0); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
  return [h ? h + ' uur' : '', m ? m + ' min' : '', x ? x + ' s' : ''].filter(Boolean).join(' ') || '0 min';
}
const aiHm = min => String(Math.floor(min / 60)).padStart(2, '0') + ':' + String(min % 60).padStart(2, '0');
function aiNormName(s){ return norm2(String(s || '')).replace(/[^a-z0-9]+/g, ''); }
/* Beste treffer op naam: precies, begint met, bevat (in beide richtingen) */
function aiBestName(list, q, name){
  const n = aiNormName(q); if (!n) return null;
  let best = null, score = 0;
  list.forEach(x => { const v = aiNormName(name(x)); if (!v) return;
    const s = v === n ? 4 : v.startsWith(n) || n.startsWith(v) && v.length >= 3 ? 3 : v.includes(n) || n.includes(v) && v.length >= 4 ? 2 : 0;
    if (s > score) { score = s; best = x; } });
  return best;
}
/* Zenders die je zelf hebt: favorieten, wat speelt of speelde, de zender van de radiowekker */
function aiStations(){
  const out = [], seen = new Set(), add = s => { if (s && s.url && s.name && !seen.has(s.url)) { seen.add(s.url); out.push(s); } };
  rdJson(Android.radioFavorites(), []).forEach(add);
  const st = rdJson(Android.radioState(), {}); add(st.station); add(st.last); add(rdState.station); add(rdState.last);
  add(rdJson(Android.alarmState(), {}).station);
  return out;
}
/* Afleveringen uit je eigen lijsten: verder luisteren en nieuw van wat je volgt (zonder internet) */
function aiEpisodes(){
  const c = rdJson(Android.podContinue(), []).filter(r => r && r.ep && r.ep.key).map(r => Object.assign({ cont: true }, r));
  const l = rdJson(Android.podLatest(40), []).filter(r => r && r.ep && r.ep.key && r.pod);
  return { cont: c, latest: l };
}
function aiFindStation(q){ const all = aiStations(); return q ? aiBestName(all, q, s => s.name) : null; }
function aiFindEp(q, newest){
  const e = aiEpisodes();
  if (!q) return e.cont[0] || null;
  const byPod = list => aiBestName(list, q, r => (r.pod && r.pod.title) || '') || aiBestName(list, q, r => r.ep.title);
  if (newest) return byPod(e.latest) || byPod(e.cont);
  return byPod(e.cont) || byPod(e.latest);
}
/* De opdracht in je vraag (of null): het antwoord met de kaart, zoals aiLocalAnswer het wil */
function aiCmdFor(q){
  if (typeof Android.aiCmd !== 'function') return null;
  const c = rdJson(Android.aiCmd(q), null);
  if (!c || !AI_CMD[c.type] && c.type !== 'speel') return null;
  if (typeof SOLO !== 'undefined' && SOLO && !AI_CMD_SOLO.includes(c.type) && c.type !== 'speel') return { text: 'In een losse app kan ik dat niet: die opent zonder app-slot. Open daarvoor Tools zelf. (Een timer of wekker kan wel.)' };
  if (typeof SOLO !== 'undefined' && SOLO && c.type === 'speel') return null;
  const a = Object.assign({ st: 'voorgesteld' }, c);
  // "speel …": eerst een zender die je hebt, dan een podcast; anders gewoon de assistent
  if (c.type === 'speel') {
    if (aiFindStation(a.q)) a.type = 'radio';
    else if (aiFindEp(a.q, false)) a.type = 'podcast';
    else return null;
  }
  switch (a.type) {
    case 'timer':
      return { text: a.secs ? 'Ik kan een timer van **' + aiDur(a.secs) + '** zetten in je Klok-app. Je ziet hem daar meteen.' : 'Hoe lang moet de timer lopen? Kies het hieronder.', cmd: a };
    case 'wekker': {
      if (a.radio && !(typeof SOLO !== 'undefined' && SOLO)) return aiCmdRadioAlarm(a.min, a.need === 'tijd');
      a.radioOk = !!aiStations().length && !(typeof SOLO !== 'undefined' && SOLO);
      let t = a.min >= 0 ? 'Ik kan een wekker om **' + aiHm(a.min) + '** zetten in je Klok-app; die gaat zeker af, ook als Tools dicht is.' : 'Hoe laat moet de wekker gaan? Kies het hieronder.';
      if (a.radioOk) t += ' Liever wakker worden met de radio? Kies dan **Met de radio** (de Radiowekker van Tools).';
      return { text: t, cmd: a };
    }
    case 'radio': {
      const st = rdJson(Android.radioState(), {}), playing = st.status === 'playing' || st.status === 'connecting';
      if (a.stop) { if (!playing) return { text: 'De radio speelt nu niet.' }; a.station = st.station || null; return { text: 'Zal ik de radio stoppen' + (st.station ? ' (' + st.station.name + ')' : '') + '?', cmd: Object.assign(a, { stopNow: true }) }; }
      const s = a.q ? aiFindStation(a.q) : (st.station || st.last || aiStations()[0]);
      if (!s) return { text: a.q ? 'Ik vind geen zender "' + a.q + '" bij je zenders. Zoek hem bij Radio en zet hem bij je favorieten; daarna kan ik hem voor je aanzetten.' : 'Je hebt nog geen zenders. Kies er een bij Radio.', cmd: { type: 'open', tool: 'radio', name: 'Radio', st: 'voorgesteld' } };
      a.station = { name: s.name, url: s.url, id: s.id || '', logo: s.logo || s.favicon || '' };
      return { text: 'Zal ik **' + s.name + '** aanzetten?', cmd: a };
    }
    case 'podcast': {
      const r = aiFindEp(a.q, a.newest);
      if (!r) return { text: a.q ? 'Ik vind geen podcast "' + a.q + '" bij wat je volgt of aan het luisteren bent. Volg hem eerst bij Podcasts.' : 'Je bent nog niets aan het luisteren. Kies een aflevering bij Podcasts.', cmd: { type: 'open', tool: 'podcasts', name: 'Podcasts', st: 'voorgesteld' } };
      a.ep = r.ep; a.pod = r.pod || {};
      return { text: 'Zal ik **' + r.ep.title + '**' + (a.pod.title ? ' van ' + a.pod.title : '') + (r.cont && +r.ep.pos > 0 ? ' verder afspelen?' : ' afspelen?'), cmd: a };
    }
    case 'slaap': {
      const st = rdJson(Android.sleepState(), {}), on = st.phase === 'luistert' || st.phase === 'proef';
      if (!a.stop && on) return { text: 'Slaappraat luistert al. Welterusten!' };
      if (a.stop && !on) return { text: 'Slaappraat luistert nu niet.' };
      return { text: a.stop ? 'Zal ik Slaappraat stoppen?' : 'Zal ik Slaappraat starten? Hij luistert dan tot je hem stopt' + ((st.set || {}).stopAt ? ' (of tot ' + st.set.stopAt + ')' : '') + '; alles blijft op je telefoon.', cmd: a };
    }
    case 'open': {
      const t = (TOOLS[a.tool] || [])[0] || ({ settings: 'Instellingen', ralarm: 'Radiowekker', aiset: 'Lokale AI', home: 'Startscherm' })[a.tool];
      if (!t || !document.getElementById('s-' + a.tool)) return null;
      a.name = t;
      return { text: 'Zal ik **' + t + '** openen?', cmd: a };
    }
    case 'sms': case 'wa': return aiCmdMsg(a);
  }
  return null;
}
/* Met de radio wakker worden: dat is de Radiowekker (een instelling van de app, met Ongedaan maken) */
function aiCmdRadioAlarm(min, noTime){
  if (setSolo()) return { text: 'De Radiowekker pas je aan in Tools zelf, niet in een losse app.' };
  const items = [], tijd = setEntry('wekker.tijd'), aan = setEntry('wekker.aan');
  if (!noTime && min >= 0 && setGet(tijd) !== aiHm(min)) items.push({ id: 'wekker.tijd', from: setGet(tijd), to: aiHm(min), st: 'voorgesteld' });
  if (setGet(aan) !== 'aan') items.push({ id: 'wekker.aan', from: setGet(aan), to: 'aan', st: 'voorgesteld' });
  const z = setAlarmStation();
  if (!items.length) return { text: 'De **Radiowekker** staat al aan om ' + setGet(tijd) + (z ? ', met ' + z : '') + '.', set: { open: ['wekker.tijd'] } };
  return { text: 'Met de radio wakker worden doet de **Radiowekker** van Tools' + (z ? ' (zender: ' + z + ')' : '') + '. ' + (noTime ? 'Welke tijd? Zeg bijvoorbeeld "zet de wekker op 07:30".' : 'Bevestig hieronder met Toepassen.'), set: noTime ? { open: ['wekker.tijd'] } : { items } };
}
/* Een bericht: aan wie (alleen uit je eigen contacten, met het nummer erbij) en welke tekst */
function aiCmdMsg(a){
  if (!a.number && a.to) {
    const r = rdJson(Android.aiContact(a.to), []);
    if (r && r.perm === false) a.noPerm = true;
    else if (Array.isArray(r) && r.length) { a.choices = r.slice(0, 6); a.number = r[0].number; a.toName = r[0].name; a.pick = 0; }
  }
  if (!a.toName) a.toName = a.to || a.number || '';
  const app = a.type === 'wa' ? 'WhatsApp' : 'Berichten';
  let t = 'Ik kan ' + (a.type === 'wa' ? 'een WhatsApp-bericht' : 'een sms') + (a.toName ? ' aan **' + a.toName + '**' : '') + ' klaarzetten in ' + app + '. Versturen doe je daar zelf.';
  if (a.noPerm) t += ' Om ' + a.to + ' in je contacten op te zoeken, heb ik toegang tot je contacten nodig; of vul hieronder een nummer in.';
  else if (!a.number && a.to) t += ' Ik vind ' + a.to + ' niet in je contacten' + (a.type === 'wa' ? ': vul een nummer in, of kies de chat straks zelf in WhatsApp.' : ': vul hieronder een nummer in.');
  return { text: t, cmd: a };
}

/* ---------- de kaart ---------- */
function aiCmdSoloBlocked(a){ return typeof SOLO !== 'undefined' && !!SOLO && !AI_CMD_SOLO.includes(a.type); }
function aiCmdWhat(a){
  switch (a.type) {
    case 'timer': return a.secs ? 'Timer van ' + aiDur(a.secs) + (a.label ? ': ' + a.label : '') : 'Timer';
    case 'wekker': return a.min >= 0 ? 'Wekker om ' + aiHm(a.min) + (a.label ? ': ' + a.label : '') : 'Wekker';
    case 'radio': return a.stopNow ? 'Radio stoppen' + (a.station ? ' (' + a.station.name + ')' : '') : (a.station ? a.station.name : 'Radio');
    case 'podcast': return a.ep ? a.ep.title + (a.pod && a.pod.title ? ' · ' + a.pod.title : '') : 'Podcast';
    case 'slaap': return a.stop ? 'Slaappraat stoppen' : 'Slaappraat starten';
    case 'open': return a.name || a.tool;
    case 'sms': case 'wa': return (a.type === 'wa' ? 'WhatsApp' : 'Sms') + (a.toName ? ' aan ' + a.toName : '');
  }
  return '';
}
function aiCmdHtml(m, i){
  const a = m && m.cmd; if (!a || !AI_CMD[a.type]) return '';
  const k = AI_CMD[a.type];
  if (aiCmdSoloBlocked(a)) return '<div class="aiact no" role="status">' + uiIcon('lock') + '<span>' + esc(aiCmdWhat(a)) + '<small>Dat kan niet in een losse app; open daarvoor Tools zelf.</small></span></div>';
  if (a.st === 'gedaan') {
    const undo = a.undo ? '<button class="linkish" onclick="aiCmdUndo(' + i + ')" aria-label="' + esc('Ongedaan maken: ' + aiCmdWhat(a)) + '">Ongedaan maken</button>' : '';
    const clock = a.type === 'timer' || a.type === 'wekker' ? '<button class="linkish" onclick="aiCmdClock(' + i + ')">Klok openen</button>' : '';
    return '<div class="aiact done cmdcard" role="status">' + uiIcon('check') + '<span>' + esc(a.done || 'Gedaan') + '<small>' + esc(a.doneNote || '') + '</small></span>' + undo + clock + '</div>';
  }
  if (a.st === 'terug') return '<div class="aiact done cmdcard" role="status">' + uiIcon('refresh') + '<span>' + esc(a.back || 'Teruggezet') + '</span></div>';
  if (a.st === 'nee') return '<div class="aiact no cmdcard">' + uiIcon(k.icon) + '<span><s>' + esc(aiCmdWhat(a)) + '</s> <small>(niet gedaan)</small></span><button class="linkish" onclick="aiCmdAgain(' + i + ')">Toch voorstellen</button></div>';
  let title = k.title;
  if (a.type === 'slaap' && a.stop) title = 'Slaappraat stoppen?';
  if (a.type === 'radio' && a.stopNow) title = 'Radio stoppen?';
  let body = '<div class="aiactt">' + esc(aiCmdWhat(a)) + '</div>', btn = k.btn, extra = '';
  if (a.type === 'timer' && !a.secs) body += '<label class="aiactw"><span>Minuten</span><input type="number" inputmode="numeric" min="1" max="1440" id="aicmd-min-' + i + '" value="' + esc(a.pickMin || 10) + '" oninput="aiCmdSet(' + i + ', \'pickMin\', this.value)"></label>';
  if (a.type === 'wekker') {
    if (a.min < 0) body += '<label class="aiactw"><span>Tijd</span><input type="time" id="aicmd-time-' + i + '" value="' + esc(a.pickTime || '07:00') + '" oninput="aiCmdSet(' + i + ', \'pickTime\', this.value)"></label>';
    const note = aiCmdAlarmNote(a);
    if (note) body += '<p class="note setwarn setinfo">' + uiIcon('info') + esc(note) + '</p>';
    if (a.radioOk) extra = '<button class="mini" onclick="aiCmdToRadio(' + i + ')">Met de radio</button>';
  }
  if (a.type === 'slaap' && !a.stop) body += '<p class="note setwarn setinfo">' + uiIcon('info') + esc('Een gesprek met de assistent stopt dan: Slaappraat gebruikt de microfoon.') + '</p>';
  if (a.type === 'sms' || a.type === 'wa') {
    if (a.choices && a.choices.length > 1) body += '<div class="aiactb" role="radiogroup" aria-label="Welk nummer?">' + a.choices.map((c, j) => '<button class="mini' + (j === a.pick ? ' on' : '') + '" role="radio" aria-checked="' + (j === a.pick) + '" onclick="aiCmdPick(' + i + ',' + j + ')">' + esc(c.name + ' · ' + c.number + (c.label ? ' (' + c.label + ')' : '')) + '</button>').join('') + '</div>';
    else if (a.number) body += '<div class="aiactw">' + uiIcon('phone') + '<span>' + esc(a.number) + (a.choices && a.choices[0] && a.choices[0].label ? ' (' + esc(a.choices[0].label) + ')' : '') + '</span></div>';
    if (!a.choices || !a.choices.length) body += '<label class="aiactw"><span>Nummer</span><input type="tel" inputmode="tel" id="aicmd-nr-' + i + '" value="' + esc(a.number || '') + '" placeholder="06…" oninput="aiCmdSet(' + i + ', \'number\', this.value)" autocomplete="off"></label>';
    if (a.noPerm) extra += '<button class="mini" onclick="Android.permRequest(\'contactsread\')">Toegang tot contacten</button>';
    body += '<label class="aiactw aimsgtx"><span>Tekst</span><textarea id="aicmd-tx-' + i + '" rows="2" maxlength="1000" oninput="aiCmdSet(' + i + ', \'text\', this.value)">' + esc(a.text || '') + '</textarea></label>';
    extra += '<button class="mini" onclick="aiCmdSwap(' + i + ')">' + (a.type === 'wa' ? 'Via sms' : 'Via WhatsApp') + '</button>';
  }
  if (a.err) body += '<p class="note" role="status" style="margin:0">' + esc(a.err) + '</p>';
  return '<div class="aiact cmdcard" role="group" aria-label="' + esc('Voorstel: ' + title.replace('?', '')) + '"><b>' + uiIcon(k.icon) + esc(title) + '</b>' + body +
    '<div class="aiactb"><button class="mini on cmdgo" onclick="aiCmdDo(' + i + ')" aria-label="' + esc(btn + ': ' + aiCmdWhat(a)) + '">' + esc(btn) + '</button>' + extra +
    '<button class="mini" onclick="aiCmdNo(' + i + ')" aria-label="' + esc('Annuleren: ' + aiCmdWhat(a)) + '">Annuleren</button></div></div>';
}
/* De Klok-app kiest altijd de eerstvolgende keer op die tijd: zeg het als dat niet de dag is die je noemde */
function aiCmdAlarmNote(a){
  if (!a.day || a.min < 0) return '';
  if (a.day === 'morgen') { const d = new Date(), now = d.getHours() * 60 + d.getMinutes(); return a.min > now ? 'Let op: de Klok-app zet hem op de eerstvolgende ' + aiHm(a.min) + ', en dat is vandaag. Voor morgen pas je dat daar aan.' : ''; }
  return 'De dag (' + a.day + ') kies je daarna zelf in de Klok-app; die zet hem op de eerstvolgende ' + aiHm(a.min) + '.';
}
function aiCmdMsgAt(i){ const m = aiChat && aiChat.msgs[i]; return m && m.cmd ? m : null; }
function aiCmdSet(i, k, v){ const m = aiCmdMsgAt(i); if (m) m.cmd[k] = v; }
function aiCmdRedraw(i){ aiMemAfter(i); } // (aiMemAfter laat een gesprek ook weer verder gaan)
function aiCmdNo(i){ const m = aiCmdMsgAt(i); if (!m || m.cmd.st !== 'voorgesteld') return; m.cmd.st = 'nee'; aiCmdRedraw(i); aiVSay('Niet gedaan'); }
function aiCmdAgain(i){ const m = aiCmdMsgAt(i); if (!m || m.cmd.st !== 'nee') return; m.cmd.st = 'voorgesteld'; aiMemAfter(i); }
function aiCmdPick(i, j){ const m = aiCmdMsgAt(i); if (!m || !m.cmd.choices || !m.cmd.choices[j]) return; m.cmd.pick = j; m.cmd.number = m.cmd.choices[j].number; m.cmd.toName = m.cmd.choices[j].name; aiMemAfter(i); }
function aiCmdSwap(i){ const m = aiCmdMsgAt(i); if (!m) return; const a = m.cmd; a.type = a.type === 'wa' ? 'sms' : 'wa'; aiMemAfter(i); }
function aiCmdClock(i){ const m = aiCmdMsgAt(i); if (!m) return; const e = Android.aiDo(JSON.stringify({ type: 'klok', what: m.cmd.type === 'timer' ? 'timers' : 'alarms' })); if (e) toast(e); }
/* Wekker → Radiowekker: dezelfde kaart als bij de instellingen (met Toepassen en Ongedaan maken) */
function aiCmdToRadio(i){
  const m = aiCmdMsgAt(i); if (!m) return;
  const min = m.cmd.min >= 0 ? m.cmd.min : (m.cmd.pickTime ? +m.cmd.pickTime.slice(0, 2) * 60 + +m.cmd.pickTime.slice(3, 5) : -1);
  const r = aiCmdRadioAlarm(min, min < 0);
  delete m.cmd; m.c = r.text; if (r.set) m.set = r.set; aiMemAfter(i);
}
const AI_CMD_TOOLS = ['apparaten', 'gebruik', 'sleep', 'checkin', 'radio', 'ralarm', 'podcasts', 'notes', 'contacts', 'calls', 'sms', 'wa', 'music', 'recorder', 'transcripts', 'car', 'kluis', 'pdf', 'spotify', 'vpn', 'loc', 'history', 'tracks', 'redial', 'backup', 'settings', 'auto', 'aiset', 'home']; // zelfde lijst als AiCommands.TOOLS
/* Na de tik: nog eens alles nakijken, dan doen */
function aiCmdDo(i){
  const m = aiCmdMsgAt(i), a = m && m.cmd; if (!a || a.st !== 'voorgesteld') return;
  if (aiCmdSoloBlocked(a)) { toast('Dat kan niet in een losse app'); return; }
  const go = () => once('aicmd' + (aiChat.id || '') + i); // dubbel tikken: één keer (pas na het nakijken)
  const fail = e => { a.err = e; aiMemAfter(i); toast(e); aiVSay(e); };
  delete a.err;
  switch (a.type) {
    case 'timer': {
      const secs = a.secs || Math.round(+a.pickMin * 60);
      if (!(secs >= 1 && secs <= 86400)) { fail('Kies tussen 1 en 1440 minuten'); return; }
      if (!go()) return;
      const e = Android.aiDo(JSON.stringify({ type: 'timer', secs, label: a.label || '' })); if (e) { fail(e); return; }
      a.secs = secs; a.done = 'Timer van ' + aiDur(secs) + ' gezet'; a.doneNote = 'In je Klok-app; stoppen doe je daar.'; break;
    }
    case 'wekker': {
      let min = a.min;
      if (min < 0) { const t = /^(\d{2}):(\d{2})$/.exec(a.pickTime || '07:00'); min = t ? +t[1] * 60 + +t[2] : -1; }
      if (!(min >= 0 && min < 1440)) { fail('Kies een tijd'); return; }
      if (!go()) return;
      const e = Android.aiDo(JSON.stringify({ type: 'wekker', min, label: a.label || '' })); if (e) { fail(e); return; }
      a.min = min; a.done = 'Wekker om ' + aiHm(min) + ' gezet'; a.doneNote = 'In je Klok-app; uitzetten of een andere dag kiezen doe je daar.'; break;
    }
    case 'radio': {
      if (!go()) return;
      const st = rdJson(Android.radioState(), {}), was = (st.status === 'playing' || st.status === 'connecting') ? st.station : null;
      if (a.stopNow) { Android.radioStop(); a.undo = was ? { play: was } : null; a.done = 'Radio gestopt'; a.doneNote = was ? was.name : ''; }
      else {
        const s = aiStations().find(x => x.url === (a.station || {}).url); // alleen een zender die je (nog) hebt
        if (!s) { fail('Die zender staat niet meer bij je zenders'); return; }
        Android.radioPlay(JSON.stringify(s)); rdState = { status: 'connecting', station: s, title: '' };
        if (typeof plHintSrc === 'function') try { plHintSrc('radio'); } catch (x) { }
        a.undo = was && was.url !== s.url ? { play: was } : { stop: true }; a.done = s.name + ' speelt'; a.doneNote = '';
      }
      break;
    }
    case 'podcast': {
      const e0 = aiEpisodes(), r = e0.cont.concat(e0.latest).find(x => x.ep.key === (a.ep || {}).key);
      if (!r) { fail('Die aflevering staat niet meer in je lijsten'); return; }
      if (!go()) return;
      const before = rdJson(Android.podState(), {}).status;
      pcPlayEp(r.ep, r.pod || a.pod, false);
      a.undo = { podStop: true, was: before }; a.done = 'Speelt: ' + r.ep.title; a.doneNote = (r.pod && r.pod.title) || ''; break;
    }
    case 'slaap': {
      if (!go()) return;
      if (a.stop) { Android.sleepStop(); a.undo = { sleepStart: true }; a.done = 'Slaappraat gestopt'; a.doneNote = 'Wat hij hoorde, wordt nog uitgeschreven.'; }
      else {
        if (typeof aiTalkStop === 'function') aiTalkStop(); // de microfoon is dan voor Slaappraat
        const e = Android.sleepStart();
        if (e === 'mic') { Android.permRequest('mic'); fail('Geef de app toegang tot de microfoon en tik dan opnieuw'); return; }
        if (e === 'notif') { Android.permRequest('notif'); fail('Sta meldingen toe (je ziet dan dat hij luistert) en tik dan opnieuw'); return; }
        if (e) { fail(e); return; }
        a.undo = { sleepStop: true }; a.done = 'Slaappraat luistert'; a.doneNote = 'Welterusten!';
      }
      if (typeof slLoad === 'function') try { slLoad(); } catch (x) { }
      break;
    }
    case 'open': {
      if (!AI_CMD_TOOLS.includes(a.tool) || !document.getElementById('s-' + a.tool)) { fail('Die tool ken ik niet'); return; }
      if (!go()) return;
      a.st = 'gedaan'; a.done = (a.name || a.tool) + ' geopend'; a.doneNote = ''; aiCmdRedraw(i); aiSaveChat(aiChat);
      jumpTo(a.tool); return;
    }
    case 'sms': case 'wa': {
      const text = String(a.text || '').trim();
      if (!text && a.type === 'sms') { fail('Vul eerst de tekst in'); const t = $('#aicmd-tx-' + i); if (t) t.focus(); return; }
      const nr = String(a.number || '').trim();
      if (!nr && a.type === 'sms') { fail('Vul eerst een telefoonnummer in'); const t = $('#aicmd-nr-' + i); if (t) t.focus(); return; }
      if (nr && !/^\+?[0-9 ()./-]{4,30}$/.test(nr)) { fail('Dat is geen geldig telefoonnummer'); return; }
      if (!go()) return;
      const e = Android.aiDo(JSON.stringify({ type: a.type, number: nr, text })); if (e) { fail(e); return; }
      a.done = (a.type === 'wa' ? 'Klaargezet in WhatsApp' : 'Klaargezet in Berichten') + (a.toName ? ' voor ' + a.toName : '');
      a.doneNote = 'Verstuurd is het nog niet: dat doe je daar zelf.'; a.undo = null; break;
    }
  }
  a.st = 'gedaan';
  aiCmdRedraw(i); aiSaveChat(aiChat);
  toast(a.done); aiVSay(a.done);
}
function aiCmdUndo(i){
  const m = aiCmdMsgAt(i), a = m && m.cmd; if (!a || a.st !== 'gedaan' || !a.undo) return;
  const u = a.undo;
  if (u.play) { Android.radioPlay(JSON.stringify(u.play)); a.back = 'Teruggezet: ' + u.play.name + ' speelt weer'; }
  else if (u.stop) { Android.radioStop(); a.back = 'Radio weer uit'; }
  else if (u.podStop) { Android.podPause(); a.back = 'Podcast gepauzeerd'; }
  else if (u.sleepStop) { Android.sleepStop(); a.back = 'Slaappraat weer gestopt'; }
  else if (u.sleepStart) { const e = Android.sleepStart(); if (e) { toast(e === 'mic' || e === 'notif' ? 'Opnieuw starten lukte niet; doe het bij Slaappraat' : e); return; } a.back = 'Slaappraat luistert weer'; }
  a.st = 'terug'; aiMemAfter(i); aiSaveChat(aiChat); toast(a.back); aiVSay(a.back);
}
