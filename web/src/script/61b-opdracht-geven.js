/* ---------- Opdracht geven ----------
   Eén plek om Tools iets te laten doen: zeg of typ het bij de assistent. Wat de app zelf herkent, werkt zonder taalmodel:
   - opdrachten (timer, wekker, radio, podcast, bellen, berichten, een tool openen): AiCommands, 61-assistent-acties.js
   - een afspraak, herinnering of notitie: AiActions.fromQuestion (Java, Android.aiActQ), hier een kaart onder het antwoord
     (aiActHtml in 51-assistent.js). Een afspraak gaat rechtstreeks in de Agenda van Tools (agendaBewaar), in de agenda die
     je op de kaart kiest (een Google-agenda via de agenda van de telefoon). Er gebeurt niets zonder jouw tik.
   Snel bereikbaar (Opdracht.java): snelkoppeling, tegel, widget en de digitale assistent van Android openen de assistent
   met de microfoon aan (openTool('ai-spreek')). Scherm Opdracht geven (s-aizeggen): wat je kunt zeggen, en die plekken. */

/* De afspraak, herinnering of notitie in je eigen vraag (of null): het antwoord met de kaart, zoals aiLocalAnswer het wil */
function aiActFor(q){
  if (typeof Android.aiActQ !== 'function') return null;
  const a = rdJson(Android.aiActQ(q), null);
  if (!a || !AI_ACT[a.type]) return null;
  a.st = 'voorgesteld'; a.src = 'vraag';
  if (a.type === 'agenda' && aiAgKan()) { aiAgSt = null; a.kal = aiAgStandaard(); }
  return { text: t(AI_ACT_TEKST[a.type]), act: a };
}
const AI_ACT_TEKST = {
  agenda: 'Kijk de afspraak na en tik op Bewaren.',
  herinnering: 'Kijk de herinnering na en tik op Zetten.',
  notitie: 'Kijk de notitie na en tik op Maken.'
};

/* ---------- Afspraak in de Agenda van Tools ---------- */
let aiAgSt = null; // agendaStaat, één keer per voorstel (de agenda's van de telefoon lezen kost even)
function aiAgKan(){ return typeof Android.agendaBewaar === 'function' && typeof Android.agendaStaat === 'function' && !!document.getElementById('s-agenda') && !(typeof playWeg === 'function' && playWeg('agenda')); }
function aiAgStaat(){ if (!aiAgSt) aiAgSt = rdJson(Android.agendaStaat(), {}) || {}; return aiAgSt; }
/* Agenda's waarin je kunt schrijven (die je in Agenda niet hebt uitgezet) */
function aiAgKals(){ return (aiAgStaat().kalenders || []).filter(k => k && k.schrijf && k.aan !== false); }
function aiAgStandaard(){ const s = String(aiAgStaat().standaard || ''); return aiAgKals().some(k => String(k.id) === s) ? s : ''; }
function aiAgNaam(id){ const k = aiAgKals().find(x => String(x.id) === String(id || '')); return k ? (k.naam || String(k.id)) : ''; }
/* Keuzelijst op de kaart: alleen in Tools, of een agenda op de telefoon */
function aiAgKalHtml(a, i){
  if (a.kal === undefined) a.kal = aiAgStandaard(); // een voorstel van het taalmodel: de standaardagenda
  const kals = aiAgKals();
  if (!kals.length) return '';
  const cur = String(a.kal || '');
  const opts = '<option value="">' + esc(t('Alleen in Tools')) + '</option>' + kals.map(k => '<option value="' + esc(k.id) + '" translate="no"' + (String(k.id) === cur ? ' selected' : '') + '>' + esc(k.naam || k.id) + '</option>').join('');
  return '<label class="aiactw"><span>' + esc(t('Agenda')) + '</span><select class="sel" id="aiact-kal-' + i + '" onchange="aiActKal(' + i + ', this.value)">' + opts + '</select></label>';
}
function aiActKal(i, v){ const m = aiActMsg(i); if (m) m.act.kal = String(v || ''); }
function aiHmPlus(hm, min){ const v = (+hm.slice(0, 2)) * 60 + (+hm.slice(3)) + min; return String(Math.floor(v / 60) % 24).padStart(2, '0') + ':' + String(v % 60).padStart(2, '0'); }
/* Bewaar de afspraak; "" als het lukte, anders de melding */
function aiAgBewaar(a, at, allDay){
  const dag = rgDayKey(allDay ? a.day : at);
  if (!dag) return t('Opslaan lukte niet.');
  const van = allDay ? '' : rgHm(at);
  const r = rdJson(Android.agendaBewaar(JSON.stringify({ id: '', titel: String(a.title || '').slice(0, 200), plek: '', notitie: a.text || '', dag, eind: dag,
    van: van || '09:00', tot: van ? aiHmPlus(van, 60) : '10:00', heleDag: !!allDay, herhaal: '', totDag: '', kalender: a.kal || '', ext: 0 })), {});
  if (!r || !r.ok) return (r && r.fout) || t('Opslaan lukte niet.');
  a.agId = r.id || ''; a.kalNaam = aiAgNaam(a.kal); a.via = 'tools';
  return '';
}
/* Na het bewaren: de afspraak in Agenda bekijken (niet vanuit een losse app: die gaat niet naar een andere tool) */
function aiActAgenda(i){
  const m = aiActMsg(i); if (!m || !m.act.agId) return;
  if (typeof SOLO !== 'undefined' && SOLO) return;
  aiNaarAgenda();
}
function aiNaarAgenda(){ show('agenda'); }

/* ---------- De assistent openen met de microfoon aan ---------- */
function aiOpdracht(){
  show('ai');
  const go = () => setTimeout(() => {
    if (current !== 'ai' || $('#lockscr').classList.contains('on') || document.hidden) return;
    if (aiRec || aiVoiceBusy || aiBusy) return;
    if (typeof aiTalkOn === 'function' && aiTalkOn()) return; // er loopt een gesprek
    if (aiChat && aiChat.msgs.length) aiNew(); // een nieuwe opdracht: een eigen gesprek
    aiMic();
  }, 400);
  if (typeof tsDaarna === 'function') tsDaarna('ai', go); else go();
}

/* ---------- Scherm Opdracht geven ---------- */
/* Voorbeelden: zinnen die de app zelf herkent (zonder taalmodel). Ze staan vast in het Nederlands: zo ontleedt de app ze. */
/* taal-vast-begin: voorbeeldzinnen die de assistent ontleedt */
const AI_ZEGGEN = [
  ['Agenda', 'calendar', ['Maak een afspraak morgen om 10 uur met Jos', 'Zet de tandarts vrijdag om 9:30 in mijn agenda', 'Maak een afspraak op 3 december om 14:00 met omschrijving overleg']],
  ['Herinneringen en notities', 'bell', ['Herinner me morgen om 9 uur aan de vuilnis', 'Herinner me over 2 uur aan de oven', 'Noteer: melk, brood en kaas']],
  ['Timer en wekker', 'clock', ['Zet een timer van 10 minuten', 'Wek me morgen om 7 uur']],
  ['Radio en podcasts', 'radio', ['Zet de radio aan', 'Luister verder naar de podcast', 'Stop de radio']],
  ['Bellen en berichten', 'phone', ['Bel Jos', 'Stuur een WhatsApp naar Jos: ik ben er over 10 minuten']],
  ['Tools openen', 'grid', ['Open Notities', 'Open mijn agenda']],
  ['Instellingen', 'settings', ['Zet de nachtelijke backup aan', 'Waar vind ik de tekstgrootte?']]
];
/* taal-vast-eind */
function enterAiZeggen(){
  aizRender();
}
function aizRender(){
  const snel = $('#aiz-snel');
  if (snel) snel.hidden = (typeof SOLO !== 'undefined' && !!SOLO) || typeof Android.aiOpdrachtStaat !== 'function';
  if (snel && !snel.hidden) {
    const st = rdJson(Android.aiOpdrachtStaat(), {}) || {};
    $('#aiz-assist-st').textContent = st.assistent === true ? t('Tools is je digitale assistent') : st.assistent === false ? t('Niet gekozen; tik om te kiezen') : t('Kiezen in de instellingen van Android');
    $('#aiz-widget').hidden = st.widget === false;
  }
  const box = $('#aiz-lijst'); if (!box) return;
  box.innerHTML = AI_ZEGGEN.map((g, i) => '<div class="card aiz"><b class="aizh">' + uiIcon(g[1]) + esc(t(g[0])) + '</b><div class="aisugg">' +
    g[2].map((z, j) => '<button class="chip" translate="no" onclick="aizVul(' + i + ',' + j + ')">' + esc(z) + '</button>').join('') + '</div></div>').join('');
}
/* Een voorbeeld in het invoerveld van de assistent */
function aizVul(i, j){
  const z = AI_ZEGGEN[i] && AI_ZEGGEN[i][2][j]; if (!z) return;
  goBack();
  setTimeout(() => { const tt = $('#ai-in'); if (!tt) return; tt.value = z; aiGrow(); tt.focus(); try { tt.setSelectionRange(tt.value.length, tt.value.length); } catch (e) { } }, 60);
}
function aizPin(){ const e = Android.aiOpdrachtPin(); toast(e || t('Kijk op je startscherm: daar staat Opdracht geven')); }
function aizTegel(){
  const r = Android.aiOpdrachtTegel();
  if (r === 'manual') toast(t('Veeg omlaag, tik op het potlood in de snelle instellingen en sleep de tegel Opdracht erbij'));
}
window.onAiTegel = function(r){ r = +r; toast(r === 2 ? t('Tegel toegevoegd') : r === 1 ? t('De tegel staat er al') : r === 0 ? t('Niet toegevoegd') : t('Toevoegen lukte niet; voeg hem toe via het potlood in de snelle instellingen')); };
function aizWidget(){ const e = Android.aiOpdrachtWidget(); toast(e || t('Kijk op je startscherm: zet de widget neer')); }
function aizAssistent(){
  askConfirm(t('Digitale assistent kiezen'), t('Android opent de instellingen. Kies daar Tools bij Digitale assistent. Lang drukken op de aan/uit- of thuisknop opent dan Tools met de microfoon aan. Wat je zegt blijft op je telefoon.'), t('Instellingen openen'), () => {
    const e = Android.aiAssistentKiezen(); if (e) toast(e);
  });
}
