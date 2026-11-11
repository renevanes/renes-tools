/* ---------- De assistent in het Engels ----------
 * Staat de app in het Engels (Regio en taal), dan begrijpt de assistent ook Engelse opdrachten en vragen over de app.
 * Er is geen tweede parser: een Engelse zin wordt eerst omgezet naar de Nederlandse vorm die 59-assistent-instellingen.js,
 * 61-assistent-acties.js en AiCommands/AiData (Java) al kennen ("turn on dark mode" → "zet dark mode aan",
 * "set a timer for 5 minutes" → "zet een timer van 5 minuten"). Namen van instellingen, waarden en tools zoekt de
 * assistent dan ook in het Engels (de vertalingen uit web/src/taal/en.json, zie setIdx en setValFor).
 * Alleen hele zinnen die precies zo'n opdracht zijn, worden omgezet (anders null: gewoon de assistent).
 * Nederlands blijft altijd werken. Getest in tests/ui/ai-engels.test.js (ook zinnen die géén opdracht zijn). */
/* taal-vast-begin: hier worden Engelse zinnen omgezet naar de Nederlandse opdrachten die de assistent kent */
const AI_EN_NUM = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, fifteen: 15, twenty: 20, thirty: 30, forty: 40, 'forty-five': 45, sixty: 60, ninety: 90 };
/* Wat iemand voor je is: "mum" → "mama" (zoals AiCommands.people het kent) */
const AI_EN_REL = { mum: 'mama', mom: 'mama', mummy: 'mama', mommy: 'mama', mother: 'moeder', dad: 'papa', daddy: 'papa', father: 'vader', parents: 'ouders', parent: 'ouder', daughter: 'dochter', son: 'zoon', child: 'kind', kid: 'kind', wife: 'vrouw', husband: 'man', partner: 'partner', girlfriend: 'vriendin', boyfriend: 'vriend', brother: 'broer', sister: 'zus', grandma: 'oma', granny: 'oma', grandmother: 'oma', grandpa: 'opa', grandfather: 'opa', aunt: 'tante', uncle: 'oom', neighbour: 'buurman', neighbor: 'buurman', colleague: 'collega' };
/* Engelse namen van tools die niet letterlijk in TOOLS staan */
const AI_EN_TOOL = { settings: 'instellingen', 'the settings': 'instellingen', assistant: 'assistent', home: 'startscherm', 'home screen': 'startscherm', 'radio alarm': 'radiowekker', 'screen time': 'gebruik', 'sleep talk': 'slaappraat', banking: 'bankzaken', invoices: 'facturen', notes: 'notities', contacts: 'contacten', calls: 'oproepen', devices: 'apparaten', usage: 'gebruik', calendar: 'agenda', 'my calendar': 'agenda' };
function aiEnNum(w){ w = String(w || '').trim(); return /^\d+([.,]\d+)?$/.test(w) ? w.replace(',', '.') : AI_EN_NUM[w] != null ? String(AI_EN_NUM[w]) : null; }
/* "5 minutes", "an hour and a half", "90 seconds" → "5 minuten", "anderhalf uur", "90 seconden" */
function aiEnDur(x){
  let s = ' ' + x.replace(/\band a half\b/, 'en een half') + ' ';
  s = s.replace(/\b(an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|sixty|ninety)\b(?= (?:hours?|hrs?|minutes?|mins?|seconds?|secs?)\b)/g, w => aiEnNum(w));
  s = s.replace(/\bhalf an hour\b/, '30 minuten').replace(/\b(hours?|hrs?)\b/g, 'uur').replace(/\b(minutes?|mins?)\b/g, 'minuten').replace(/\b(seconds?|secs?)\b/g, 'seconden').replace(/\band\b/g, 'en');
  s = s.trim();
  return /\d/.test(s) && /(uur|minuten|seconden)/.test(s) && !/[a-z]{3,}/.test(s.replace(/\b(uur|minuten|seconden|en|een|half)\b/g, '')) ? s : null;
}
/* "7", "7:30", "7 am", "half past 7", "quarter to 8", "7.30 pm", "noon" → "7:30" (24-uurs, met 's avonds) */
function aiEnTime(x){
  let s = x.trim().replace(/\bo'?clock\b/, '').replace(/\s+/g, ' ').trim(), m, h = -1, mi = 0;
  const pm = /\b(pm|p\.m\.|in the (evening|afternoon)|tonight)\b/.test(s), am = /\b(am|a\.m\.|in the morning)\b/.test(s);
  s = s.replace(/\b(am|a\.m\.|pm|p\.m\.|in the (evening|afternoon|morning)|tonight|tomorrow|today)\b/g, '').trim();
  if (s === 'noon' || s === 'midday') { h = 12; }
  else if (s === 'midnight') { h = 0; }
  else if ((m = /^(\d{1,2})(?:[:.](\d{2}))?$/.exec(s))) { h = +m[1]; mi = m[2] ? +m[2] : 0; }
  else if ((m = /^(\w+)$/.exec(s)) && aiEnNum(m[1])) h = +aiEnNum(m[1]);
  else if ((m = /^half past (\w+)$/.exec(s)) && aiEnNum(m[1])) { h = +aiEnNum(m[1]); mi = 30; }
  else if ((m = /^(?:a )?quarter past (\w+)$/.exec(s)) && aiEnNum(m[1])) { h = +aiEnNum(m[1]); mi = 15; }
  else if ((m = /^(?:a )?quarter to (\w+)$/.exec(s)) && aiEnNum(m[1])) { h = +aiEnNum(m[1]) - 1; mi = 45; }
  else if ((m = /^(\w+) past (\w+)$/.exec(s)) && aiEnNum(m[1]) && aiEnNum(m[2])) { h = +aiEnNum(m[2]); mi = +aiEnNum(m[1]); }
  else if ((m = /^(\w+) to (\w+)$/.exec(s)) && aiEnNum(m[1]) && aiEnNum(m[2])) { h = +aiEnNum(m[2]) - 1; mi = 60 - +aiEnNum(m[1]); }
  if (!(h >= 0 && h <= 24 && mi >= 0 && mi < 60) || h !== Math.floor(h)) return null;
  if (pm && h < 12) h += 12;
  if (am && h === 12) h = 0;
  return h + ':' + String(mi).padStart(2, '0');
}
/* Engelse naam van een tool (of instelling) → de Nederlandse naam die de app kent */
function aiEnName(x){
  const s = x.replace(/^(the|my) /, '').trim();
  if (AI_EN_TOOL[s]) return AI_EN_TOOL[s];
  if (typeof TOOLS !== 'undefined') for (const k of Object.keys(TOOLS)) { const nl = TOOLS[k][0]; if (norm2(t(nl)) === s || norm2(nl) === s) return norm2(nl); }
  return s;
}
/* Een Engelse opdracht of vraag over de app → dezelfde zin in het Nederlands, of null */
function aiEn(q){
  let s = norm2(q).replace(/[’`]/g, '\'').replace(/[?!.]+\s*$/, '').replace(/\s+/g, ' ').trim(), m, x;
  if (!s || s.length > 200 || !/^[a-z0-9 '.,:\-]+$/.test(s)) return null;
  s = s.replace(/^(?:(?:hey|ok|okay|hi),? )?(?:(?:please|kindly) |(?:can|could|would|will) you (?:please )?|i(?:'d| would) like (?:you )?to |i want (?:you )?to |let's |lets )+/, '').replace(/,? please$/, '').trim();
  const it = v => v.replace(/^(the|my|your) /, '');
  // Taal: "switch to Dutch", "change the language to English"
  if ((m = /^(?:switch|change|go|set (?:the )?(?:app )?language)(?: back)? (?:to|into) (english|dutch)$/.exec(s))) return 'zet de taal op ' + (m[1] === 'english' ? 'engels' : 'nederlands');
  if ((m = /^(?:use|speak) (english|dutch)$/.exec(s))) return 'zet de taal op ' + (m[1] === 'english' ? 'engels' : 'nederlands');
  // Geheugen
  if ((m = /^remember (?:that )?(.+)$/.exec(s))) return 'onthoud dat ' + m[1];
  if ((m = /^forget (?:that |about )?(.+)$/.exec(s))) return 'vergeet ' + m[1];
  // Timer en wekker
  if ((m = /^(?:set|start|put on|make)? ?(?:a |the |an )?timer (?:for |of )?(.+)$/.exec(s)) && (x = aiEnDur(m[1]))) return 'zet een timer van ' + x;
  if ((m = /^(?:set|start) (?:a |an )?(.+?) timer$/.exec(s)) && (x = aiEnDur(m[1]))) return 'zet een timer van ' + x;
  if ((m = /^(?:wake me(?: up)?|set (?:an |my |the )?alarm(?: clock)?|alarm|set (?:a )?wake-?up call) (?:at|for) (.+?)(?: (?:with|on) the radio)?$/.exec(s)) && (x = aiEnTime(m[1])))
    return 'zet een wekker om ' + x + (/radio$/.test(s) ? ' met de radio' : '') + (/\btomorrow\b/.test(s) ? ' morgen' : '');
  // Radio, podcast, Slaappraat
  if ((m = /^stop (?:the |playing )?(radio|music|podcast|playback|player)$/.exec(s))) return 'stop de ' + (m[1] === 'podcast' ? 'podcast' : 'radio');
  if (/^(?:play|turn on|put on|start) (?:the )?radio$/.test(s)) return 'zet de radio aan';
  if ((m = /^play (?:the )?(?:latest|newest|new) (?:episode (?:of )?)?(.+)$/.exec(s))) return 'speel de nieuwste aflevering van ' + it(m[1]);
  if ((m = /^play (?:the )?(.+)$/.exec(s))) return 'speel ' + it(m[1]).replace(/ podcast$/, '');
  if (/^(?:start|turn on) sleep talk$/.test(s)) return 'start slaappraat';
  if (/^(?:stop|turn off) sleep talk$/.test(s)) return 'stop slaappraat';
  // Bellen: "call mum", "ring my daughter", "give Jos a call", "call 0612345678" (de kiezer opent met het nummer)
  if ((m = /^(?:call|ring|dial) (.+?)(?: up)?$/.exec(s)) || (m = /^give (.+?) a (?:call|ring)$/.exec(s))) {
    const who = m[1].trim(), rel = AI_EN_REL[who.replace(/^(?:my|our) /, '')];
    if (/\b(?:me|it|that|this|him|her|them|us|you|back|off|someone|somebody|anyone|a day)\b/.test(who)) return null;
    return 'bel ' + (rel ? (/^(?:my|our) /.test(who) ? 'mijn ' : '') + rel : who);
  }
  // Openen
  if ((m = /^(?:open|go to|show(?: me)?|take me to|launch) (?:the |my )?(.+?)(?: screen| tool| app)?$/.exec(s)) && !/\b(that|this|it)\b/.test(m[1])) return 'open ' + aiEnName(m[1]);
  // De tijd ergens anders (alleen antwoorden): "what time is it in Tokyo", "time difference with New York"
  if ((m = /^(?:what time is it|what's the time|what is the time)(?: now| right now)? in (.+?)(?: now| right now)?$/.exec(s))) return 'hoe laat is het in ' + it(m[1]);
  if ((m = /^(?:what(?:'s| is) the )?time difference (?:with|to|between here and) (.+)$/.exec(s))) return 'tijdsverschil met ' + it(m[1]);
  // Je eigen gegevens (Gebruik, Slaappraat …): "what's my screen time today", "how long was i on my phone yesterday"
  if ((m = /^(?:what(?:'s| is| was) |how much |show (?:me )?)?(?:my )?screen ?time(?: (today|yesterday|this week|last week))?$/.exec(s))) return 'hoeveel schermtijd ' + ({ today: 'vandaag', yesterday: 'gisteren', 'this week': 'deze week', 'last week': 'vorige week' }[m[1] || 'today']);
  if ((m = /^how (?:long|much time) (?:was i|have i been|did i spend) on my phone(?: (today|yesterday|this week))?$/.exec(s))) return 'hoeveel schermtijd ' + ({ today: 'vandaag', yesterday: 'gisteren', 'this week': 'deze week' }[m[1] || 'today']);
  if (/^how (?:often|many times) did i unlock my phone(?: today)?$/.test(s)) return 'hoe vaak heb ik mijn telefoon ontgrendeld vandaag';
  // Instellingen: aan/uit
  if ((m = /^(?:turn|switch|put) (on|off) (.+)$/.exec(s)) || (m = /^(?:turn|switch|put) (.+?) (on|off)$/.exec(s))) {
    const on = m[1] === 'on' || m[2] === 'on', x2 = m[1] === 'on' || m[1] === 'off' ? m[2] : m[1];
    return 'zet ' + it(x2) + (on ? ' aan' : ' uit');
  }
  if ((m = /^(enable|activate|disable|deactivate) (.+)$/.exec(s))) return 'zet ' + it(m[2]) + (/^(enable|activate)$/.test(m[1]) ? ' aan' : ' uit');
  // Instellingen: een waarde ("set the theme to automatic", "change the text size to large", "make the text larger")
  if ((m = /^(?:set|change|switch|put) (.+?) (?:to|on|at) (.+)$/.exec(s))) return 'zet ' + it(m[1]) + ' op ' + it(m[2]);
  if ((m = /^make (?:the )?(.+?) (.+)$/.exec(s)) && /^(larger|bigger|smaller|large|small|dark|light|automatic|normal)$/.test(m[2])) return 'zet ' + m[1] + ' op ' + ({ larger: 'groter', bigger: 'groter', large: 'groot', smaller: 'kleiner', small: 'kleiner' }[m[2]] || m[2]);
  // Regio: "use miles", "show temperatures in fahrenheit", "use the 12-hour clock", "start the week on sunday"
  if ((m = /^(?:use|show (?:\w+ )?in|display (?:\w+ )?in|measure in|count in) (?:the )?(.+)$/.exec(s))) return 'gebruik ' + m[1].replace(/ clock$/, '').replace(/^12[- ]hour$/, '12-uurs').replace(/^24[- ]hour$/, '24-uurs');
  if ((m = /^(?:start|begin) (?:the |my )?week on (\w+)$/.exec(s))) return 'begin de week op ' + ({ monday: 'maandag', sunday: 'zondag', saturday: 'zaterdag' }[m[1]] || m[1]);
  // Opzoeken
  if ((m = /^where (?:can|do) i (?:find|change|set|turn on|turn off|adjust) (.+)$/.exec(s)) || (m = /^where (?:is|are) (.+?)(?: setting| settings)?$/.exec(s))) return 'waar vind ik ' + it(m[1]);
  if ((m = /^what (?:is|'s) (.+?) set to$/.exec(s)) || (m = /^what'?s (.+?) set to$/.exec(s))) return 'wat staat ' + it(m[1]) + ' op';
  if ((m = /^is (.+?) (?:turned |switched )?(on|off|enabled|disabled)$/.exec(s))) return 'staat ' + it(m[1]) + ' aan';
  if (/^what (?:have you|did you) change(?:d)?(?: for me)?$/.test(s) || /^what changed$/.test(s)) return 'wat heb je veranderd';
  if ((m = /^what (?:can i (?:set|change|adjust)|settings are there) (?:in|for) (.+)$/.exec(s))) return 'wat kan ik instellen bij ' + aiEnName(m[1]);
  // Agenda: "what's on my calendar tomorrow", "search my calendar for dentist" (een afspraak zetten blijft de agenda-app)
  if ((m = /^(?:what(?:'s| is) (?:on|in) my calendar|what appointments do i have)(?: (today|tomorrow|yesterday))?$/.exec(s))) return 'wat staat er ' + ({ today: 'vandaag', tomorrow: 'morgen', yesterday: 'gisteren' }[m[1] || 'today']) + ' in mijn agenda';
  if ((m = /^search my calendar for (.+)$/.exec(s))) return 'zoek ' + m[1] + ' in mijn agenda';
  return null;
}
/* taal-vast-eind */
/* De vraag zoals de opdrachten en instellingen hem lezen: in het Engels eerst omzetten (alleen als de app Engels is) */
function aiCmdQ(q){ if (!TAAL.on) return q; const nl = aiEn(q); return nl || q; }
