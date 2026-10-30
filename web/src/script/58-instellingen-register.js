/* ---------- Instellingenregister: alle instellingen van de app op één plek ----------
 * Voor de assistent (zoeken, uitleggen, aanpassen met bevestiging) en voor de dekkingstest (tests/ui/ai-register.test.js):
 * elke schakelaar of keuze op een scherm staat hier, of in SET_EXCLUDE met de reden waarom niet.
 * Per instelling: id, scherm (scr), het element op dat scherm (ctl, om het aan te wijzen), naam, uitleg, zoekwoorden (syn),
 * soort (aan = aan/uit, keuze, tijd, tekst, openen = alleen openen), waarden (vals: [waarde, naam]), get() en set(v).
 * set gebruikt dezelfde functies als het scherm zelf (zelfde bijwerkingen: inplannen, wekkers, toasts) en geeft "" of een melding.
 * Gevoelig: safe = 'geheim' (nooit voorlezen of aanpassen), 'beveiliging' of 'privacy' (alleen uitleggen en Openen);
 * weak(v) = alleen díe kant op is gevoelig (bijv. privé-onderdelen aanzetten). warn(v) = waarschuwing op de kaart. */
const SET_AU = [['aan', 'Aan'], ['uit', 'Uit']];
const setB = b => b ? 'aan' : 'uit';
const setOnV = v => v === 'aan';
/* Het scherm opnieuw tekenen als het open is */
function setIf(scr, fn){ if (current === scr || (Array.isArray(scr) && scr.includes(current))) { try { fn(); } catch (e) { } } }
const SET_ZOOM = [['0', 'Zoals de telefoon'], ['90', 'Kleiner'], ['100', 'Normaal'], ['115', 'Groot'], ['130', 'Groter'], ['150', 'Extra groot'], ['175', 'Maximaal']];
const SET_LOCKT = [['0', 'Meteen'], ['30000', '30 seconden'], ['60000', '1 minuut'], ['300000', '5 minuten'], ['900000', '15 minuten'], ['3600000', '1 uur']];
const SET_RANGE = () => RANGE.map(v => [String(v), v < 60 ? v + ' seconden' : v === 60 ? '1 minuut' : String(v / 60).replace('.', ',') + ' minuten']);
/* Korte, vaste id uit een willekeurige tekst (een id van een automatisering kan van alles bevatten) */
function setHash(t){ let h = 5381; const s = String(t); for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) >>> 0; return h.toString(36) + '-' + s.length.toString(36); }
/* Herinnering op bedtijd: net als op het scherm eerst toestemming voor meldingen */
function setSlNotif(on){ slLoad(); if (on && slSt.notifOk === false) { Android.permRequest('notif'); return 'Sta eerst meldingen toe voor de app en probeer het dan opnieuw'; } return ''; }
function setSl(){ return (rdJson(Android.sleepState(), {}) || {}).set || {}; }
function setBk(){ return rdJson(Android.backupState(false), {}) || {}; }
function setCar(){ carLoad(); return carSt || {}; }
function setCarSave(o){ const c = setCar(); Android.carSet(c.addr || '', c.name || '', o.trips != null ? o.trips : !!c.trips, o.def || c.defType || 'prive'); setIf('car', enterCar); return ''; }
function setVp(){ return rdJson(Android.vpnState(), {}) || {}; }
function setVpAuto(k, on){ const s = setVp(), a = Object.assign({}, s.auto || {}, { [k]: on }); Android.vpnAuto(JSON.stringify(a)); setIf('vpn', vpRender); return ''; }
function setAi(){ aiLoad(); return aiSt; }
function setAiCfg(k){ return String((setAi().cfg || {})[k] || ''); }
/* De zender die Opslaan bij de radiowekker gebruikt (de ingestelde, of de eerste uit de lijst) */
function setAlarmStation(){ try { enterAlarm(); const st = alStations[+$('#al-station').value]; return st && st.name ? st.name : ''; } catch (e) { return ''; } }
function setAlarm(fn){ enterAlarm(); fn(); return alSave() ? '' : 'De wekker opslaan lukte niet'; }
function setRedial(){ setIf('redial', () => { chips($('#c-att'), ATT, 'attempts', 10); chips($('#c-int'), INT, 'interval', 10); renderRange(); $('#t-stop').classList.toggle('on', store.get('t-stop', true)); $('#t-spk').classList.toggle('on', store.get('t-spk', false)); }); }

const SET_REG = [
  /* ---------- Instellingen → Meldingen en Weergave ---------- */
  // Op het scherm heet de schakelaar "Alle meldingen uit"; hier andersom (aan = meldingen aan), zodat "zet meldingen uit" klopt
  { id: 'meldingen.aan', scr: 'setnotif', ctl: '#st-notif-mute', label: 'Meldingen van de app', desc: 'Uit: geen geluid, trillen of pop-ups meer van Tools, ook geen herinneringen, backup- of automatiseringsmeldingen (de schakelaar "Alle meldingen uit").',
    syn: 'meldingen notificaties stil dempen geluid trillen popups niet storen melding alle', get: () => setB(!stJson(Android.notifState()).muted),
    set: v => { stNotifMute(v === 'uit'); setIf('settings', enterSettings); return ''; }, warn: v => v === 'uit' ? 'Je krijgt dan ook geen herinneringen en geen melding als een backup misging.' : '' },
  { id: 'meldingen.soorten', scr: 'setnotif', type: 'openen', label: 'Meldingen per soort (geluid en trillen)', desc: 'Per soort melding (herinneringen, radio, backups …) kies je in Android of hij aan staat en met geluid of trillen.', syn: 'meldingen soort geluid trillen kanaal android notificaties' },
  { id: 'weergave.kleuren', scr: 'setlook', ctl: '#st-dyn', label: 'Kleuren uit je achtergrond', desc: 'De app neemt de kleuren van je achtergrond over, zoals Android (vanaf Android 12).', syn: 'kleur kleuren thema achtergrond material you dynamisch',
    avail: () => typeof Android.themeColors === 'function' && !!Android.themeColors() ? '' : 'Dat kan pas vanaf Android 12',
    get: () => setB(store.get('dynColor', false) && typeof Android.themeColors === 'function' && !!Android.themeColors()), set: v => { stDynColor(setOnV(v)); setIf(['setlook', 'settings'], enterSettings); return ''; } },
  { id: 'weergave.tekstgrootte', scr: 'setlook', ctl: '#st-zoom', type: 'keuze', vals: SET_ZOOM, label: 'Tekstgrootte', desc: 'Hoe groot de tekst in de app is.', syn: 'tekst letters lettergrootte groot groter kleiner zoom lezen slechtziend',
    get: () => String(Android.textZoomGet()), set: v => { const z = $('#st-zoom'); z.value = v; stZoom(v); return ''; } },
  { id: 'weergave.donker', scr: 'setlook', type: 'openen', label: 'Donker of licht', desc: 'De app volgt de donkere of lichte modus van je telefoon; dat stel je in Android in (Instellingen → Display).', syn: 'donker licht dark mode nachtmodus thema zwart wit' },
  { id: 'skin.startscherm', scr: 'setskin', type: 'openen', vals: SET_AU, label: 'Tools als startscherm (Telefoon-skin)', desc: 'Gebruik Tools als startscherm van je telefoon. Android vraagt dat zelf te bevestigen.', syn: 'startscherm launcher thuisscherm skin home',
    get: () => setB(Android.homeIsDefault()) },
  /* ---------- Beveiliging en privacy: alleen uitleggen en openen ---------- */
  { id: 'slot.aan', scr: 'setlock', type: 'aan', safe: 'beveiliging', label: 'App-slot', desc: 'Tools vergrendelen met de schermvergrendeling van je telefoon (vingerafdruk, gezicht of pincode) of een eigen app-code.', syn: 'slot vergrendelen vingerafdruk pincode beveiliging app-slot appslot',
    why: 'Het app-slot zet je zelf aan of uit, met je vingerafdruk of code.', get: () => setB(stJson(Android.lockState()).on) },
  { id: 'slot.na', scr: 'setlock', ctl: '#st-lock-timeout', type: 'keuze', vals: SET_LOCKT, safe: 'beveiliging', label: 'Vergrendelen na', desc: 'Hoe lang de app open blijft voordat het app-slot weer vraagt om te ontgrendelen.', syn: 'slot vergrendelen tijd na minuten',
    why: 'Hoe snel de app op slot gaat, kies je zelf bij App-slot.', get: () => String(stJson(Android.lockState()).timeout || 0) },
  { id: 'slot.code', scr: 'setlock', type: 'openen', safe: 'geheim', label: 'Eigen app-code', desc: 'Een eigen code (4 tot 12 cijfers) om de app te ontgrendelen.', syn: 'code pincode app-code wachtwoord', why: 'Codes en wachtwoorden lees ik niet voor en pas ik niet aan.' },
  { id: 'slot.neutraal', scr: 'setlock', ctl: '#st-decoy', type: 'aan', safe: 'beveiliging', label: 'Bij een verkeerde code de neutrale versie tonen', desc: 'Een verkeerde app-code opent een neutrale versie zonder je gegevens.', syn: 'neutraal verkeerde code afleiding',
    why: 'Dit hoort bij het app-slot; dat stel je zelf in.', get: () => setB(stJson(Android.lockState()).decoy) },
  { id: 'slot.vingerafdruk', scr: 'setlock', ctl: '#st-codebio', type: 'aan', safe: 'beveiliging', label: 'Ook ontgrendelen met je vingerafdruk', desc: 'Naast de app-code ook met je vingerafdruk ontgrendelen.', syn: 'vingerafdruk biometrie ontgrendelen',
    why: 'Dit hoort bij het app-slot; dat stel je zelf in.', get: () => setB(stJson(Android.lockState()).codeBio) },
  { id: 'losse.apps', scr: 'setsolo', ctl: '#st-solo-list .tg', type: 'openen', safe: 'beveiliging', label: 'Losse apps', desc: 'Een tool als eigen app op je startscherm. Zo\'n losse app opent zonder vingerafdruk of app-code.', syn: 'losse app eigen app snelkoppeling startscherm los',
    why: 'Een losse app opent zonder app-slot; dat kies je zelf.' },
  { id: 'toestemmingen', scr: 'setperm', type: 'openen', safe: 'beveiliging', label: 'Toestemmingen', desc: 'Wat de app van Android mag (sms, oproepen, contacten, microfoon, locatie …) en waarvoor.', syn: 'toestemming rechten permissie toegang android microfoon locatie sms',
    why: 'Toestemmingen geef je zelf, in Android.' },
  { id: 'backup.map', scr: 'setbackup', type: 'openen', label: 'Backup-map', desc: 'De map (bijv. USB-stick, SD-kaart of OneDrive) waar alle tools hun backups en exports neerzetten.', syn: 'backup map folder usb onedrive sd kaart opslag export',
    get: () => { const i = stJson(Android.settingsInfo()); return i.dest ? (i.destName || 'gekozen') : 'nog niet gekozen'; } },
  { id: 'muziek.sleutel', scr: 'setmusic', type: 'openen', safe: 'geheim', label: 'AudD-sleutel (muziek herkennen)', desc: 'De sleutel van AudD waarmee muziek herkennen werkt.', syn: 'audd sleutel token muziek herkennen', why: 'Sleutels en wachtwoorden lees ik niet voor en pas ik niet aan.' },
  /* ---------- Startscherm ---------- */
  { id: 'start.groepen', scr: 'homeedit', ctl: '#he-grouped', label: 'Startscherm in groepen', desc: 'De tools onder een kopje per groep.', syn: 'startscherm groepen kopjes indeling tegels', get: () => setB(!!homeCfg().grouped), set: v => { heSet('grouped', setOnV(v)); return ''; } },
  { id: 'start.compact', scr: 'homeedit', ctl: '#he-compact', label: 'Compact startscherm', desc: 'Kleinere tegels, drie naast elkaar.', syn: 'startscherm compact klein tegels drie', get: () => setB(!!homeCfg().compact), set: v => { heSet('compact', setOnV(v)); return ''; } },
  { id: 'start.overzicht', scr: 'homeedit', ctl: '#he-dash', label: 'Overzicht bovenaan het startscherm', desc: 'Backup, wekker en herinneringen bovenaan het startscherm.', syn: 'startscherm overzicht dashboard bovenaan', get: () => setB(homeCfg().dash !== false), set: v => { heSet('dash', setOnV(v)); return ''; } },
  /* ---------- Auto redial ---------- */
  { id: 'redial.pogingen', scr: 'redial', ctl: '#c-att', type: 'keuze', vals: [['5', '5'], ['10', '10'], ['25', '25'], ['50', '50'], ['0', 'Onbeperkt']], label: 'Auto redial: aantal pogingen', desc: 'Hoe vaak Auto redial opnieuw belt.', syn: 'redial bellen pogingen keer opnieuw',
    get: () => String(store.get('attempts', 10)), set: v => { store.set('attempts', +v); setRedial(); return ''; } },
  { id: 'redial.wachttijd', scr: 'redial', ctl: '#c-int', type: 'keuze', vals: [['5', '5 seconden'], ['10', '10 seconden'], ['20', '20 seconden'], ['30', '30 seconden'], ['60', '1 minuut'], ['-1', 'Willekeurig']], label: 'Auto redial: wachttijd tussen pogingen', desc: 'Hoe lang Auto redial wacht voor de volgende poging.', syn: 'redial wachttijd wachten interval seconden',
    get: () => String(store.get('interval', 10)), set: v => { store.set('interval', +v); setRedial(); return ''; } },
  { id: 'redial.min', scr: 'redial', ctl: '#r-min', type: 'keuze', vals: SET_RANGE, label: 'Auto redial: willekeurig wachten, minimaal', desc: 'Bij Willekeurig: de kortste wachttijd.', syn: 'redial willekeurig minimaal wachttijd',
    get: () => String(store.get('rmin', 10)), set: v => { renderRange(); $('#r-min').value = v; rangeChanged('min'); return ''; } },
  { id: 'redial.max', scr: 'redial', ctl: '#r-max', type: 'keuze', vals: SET_RANGE, label: 'Auto redial: willekeurig wachten, maximaal', desc: 'Bij Willekeurig: de langste wachttijd.', syn: 'redial willekeurig maximaal wachttijd',
    get: () => String(store.get('rmax', 60)), set: v => { renderRange(); $('#r-max').value = v; rangeChanged('max'); return ''; } },
  { id: 'redial.stoppen', scr: 'redial', ctl: '#t-stop', label: 'Auto redial: stoppen als er wordt opgenomen', desc: 'Uit: hij belt alle pogingen af.', syn: 'redial stoppen opgenomen opnemen',
    get: () => setB(store.get('t-stop', true)), set: v => { store.set('t-stop', setOnV(v)); setRedial(); return ''; } },
  { id: 'redial.luidspreker', scr: 'redial', ctl: '#t-spk', label: 'Auto redial: luidspreker aan', desc: 'Handig als de telefoon op tafel ligt.', syn: 'redial luidspreker speaker handsfree',
    get: () => setB(store.get('t-spk', false)), set: v => { store.set('t-spk', setOnV(v)); setRedial(); return ''; } },
  { id: 'redial.planning', scr: 'redial', type: 'openen', label: 'Auto redial op een tijdstip', desc: 'Auto redial vanzelf laten beginnen, bijvoorbeeld om 08:00 de huisarts. Het nummer, de tijd en de dagen kies je op het scherm.', syn: 'redial tijdstip plannen inplannen huisarts ochtend' },
  /* ---------- WhatsApp backup ---------- */
  { id: 'wa.nacht', scr: 'wa', ctl: '#wa-auto', warn: v => v === 'uit' ? 'Je WhatsApp wordt dan niet meer vanzelf back-upt.' : '', label: 'WhatsApp: elke nacht automatisch back-uppen', desc: 'Na 03:00, als WhatsApp zijn eigen backup heeft gemaakt.', syn: 'whatsapp backup nacht automatisch nachtelijk',
    get: () => setB(waInfo().auto), set: v => { renderWa(false); $('#wa-auto').classList.toggle('on', setOnV(v)); waSave(); return ''; } },
  { id: 'wa.opladen', scr: 'wa', ctl: '#wa-charging', label: 'WhatsApp-backup alleen tijdens opladen', desc: 'De eerste backup kan lang duren.', syn: 'whatsapp backup opladen lader',
    get: () => setB(waInfo().autoCharging !== false), set: v => { renderWa(false); $('#wa-charging').classList.toggle('on', setOnV(v)); waSave(); return ''; } },
  { id: 'wa.wat', scr: 'wa', ctl: '#wa-automode', type: 'keuze', vals: [['all', 'Alles'], ['selection', 'Mijn selectie']], label: 'WhatsApp: wat er automatisch mee gaat', desc: 'Alles, of alleen wat je bij de selectie aanvinkte.', syn: 'whatsapp backup selectie alles automatisch',
    get: () => waInfo().autoMode || 'all', set: v => { renderWa(false); waInfoC.autoMode = v; waSave(); return ''; } },
  { id: 'wa.leesbaar', scr: 'wa', ctl: '#rd-auto', label: 'Leesbare chats automatisch bijwerken', desc: 'Na elke backup de chats opnieuw leesbaar maken.', syn: 'whatsapp leesbaar chats bijwerken automatisch',
    avail: () => readInfo().hasKey ? '' : 'Maak eerst je chats leesbaar (met de sleutel van 64 cijfers)', get: () => setB(readInfo().auto !== false), set: v => { Android.waSetReadableAuto(setOnV(v)); setIf('wa', renderReadable); return ''; } },
  { id: 'wa.sleutel', scr: 'wa', type: 'openen', safe: 'geheim', label: 'WhatsApp-sleutel (64 cijfers)', desc: 'De sleutel van de versleutelde WhatsApp-backup, om je chats leesbaar te maken.', syn: 'whatsapp sleutel 64 cijfers versleuteld', why: 'Sleutels lees ik niet voor en pas ik niet aan.' },
  /* ---------- Alles back-uppen ---------- */
  { id: 'backup.nacht', scr: 'backup', ctl: '#bk-auto', warn: v => v === 'uit' ? 'Er wordt dan niet meer vanzelf een backup gemaakt; alleen nog met de hand.' : '', label: 'Elke nacht alles back-uppen', desc: 'Na 03:30 vanzelf een backup van alles wat aangevinkt is.', syn: 'backup nachtelijke nachtelijk nacht automatisch elke alles reservekopie', phr: ['nachtelijke backup', 'backup elke nacht', 'automatische backup'],
    get: () => setB(setBk().auto), set: v => { Android.backupSetAuto(setOnV(v), setBk().charging !== false); setIf('backup', enterBackup); return ''; } },
  { id: 'backup.opladen', scr: 'backup', ctl: '#bk-charging', label: 'Backup alleen tijdens opladen', desc: 'De nachtelijke backup wacht tot de telefoon aan de lader ligt.', syn: 'backup opladen lader', need: /opla|lader|laad/,
    get: () => setB(setBk().charging !== false), set: v => { Android.backupSetAuto(!!setBk().auto, setOnV(v)); setIf('backup', enterBackup); return ''; } },
  { id: 'backup.opruimen', scr: 'backup', ctl: '#bk-rotate', label: 'Oude backups vanzelf opruimen', desc: 'Na elke gelukte backup: alles van de laatste 14 dagen blijft, en daarvoor de nieuwste per maand.', syn: 'backup opruimen oude verwijderen ruimte rotatie',
    get: () => setB(rdJson(Android.backupState(true), {}).rotate), set: v => { Android.backupSetRotate(setOnV(v)); setIf('backup', enterBackup); return ''; },
    warn: v => v === 'aan' ? 'Oudere backups worden dan na elke backup weggehaald.' : '' },
  { id: 'backup.versleutelen', scr: 'backup', type: 'openen', vals: SET_AU, safe: 'beveiliging', label: 'Backups versleutelen', desc: 'Backups in één versleuteld bestand (AES-256) met een wachtwoord.', syn: 'versleutelen encryptie wachtwoord backup beveiligen',
    why: 'Versleutelen en het wachtwoord stel je zelf in.', get: () => setB(rdJson(Android.backupState(true), {}).encrypted) },
  { id: 'backup.prive.slaapgeluid', scr: 'backup', ctl: '#bk-private input[type=radio]', type: 'openen', safe: 'privacy', label: 'Slaappraat in de backup: met geluid', desc: 'Alleen de tekst, of ook het geluid van Slaappraat.', syn: 'slaappraat geluid backup audio', why: 'Wat er van je privé-onderdelen meegaat, kies je zelf.' },
  /* ---------- Radio, podcasts en wekker ---------- */
  { id: 'radio.terugspoelen', scr: 'radio', ctl: '#rd-ts', label: 'Radio pauzeren en terugspoelen', desc: 'Een buffer van ongeveer 45 minuten, zodat je kunt pauzeren en terugspoelen. Geldt vanaf de volgende zender.', syn: 'radio pauzeren terugspoelen buffer timeshift',
    get: () => setB(Android.radioTimeshift()), set: v => { const on = setOnV(v); Android.radioSetTimeshift(on); const c = $('#rd-ts'); if (c) c.checked = on; toast(on ? 'Geldt vanaf de volgende zender' : 'Uit; geldt vanaf de volgende zender'); return ''; } },
  { id: 'podcast.snelheid', scr: 'podcasts', type: 'keuze', vals: [['0.8', '0,8×'], ['1', '1× (normaal)'], ['1.1', '1,1×'], ['1.25', '1,25×'], ['1.5', '1,5×'], ['1.75', '1,75×'], ['2', '2×']], label: 'Afspeelsnelheid van podcasts', desc: 'Hoe snel podcasts afspelen.', syn: 'podcast snelheid sneller langzamer afspelen tempo',
    get: () => String(+(rdJson(Android.podState(), {}).speed || 1)), set: v => { Android.podSpeed(String(v)); if (typeof pcSt === 'object' && pcSt) pcSt.speed = +v; if (typeof plIsOpen !== 'undefined' && plIsOpen) plRender(); return ''; } },
  { id: 'wekker.aan', scr: 'ralarm', ctl: '#al-on', label: 'Radiowekker', desc: 'De radio als wekker.', syn: 'wekker alarm radiowekker wakker ochtend', phr: ['wekker aan', 'wekker uit', 'radiowekker aan', 'radiowekker uit'],
    note: v => { if (v !== 'aan') return ''; const z = setAlarmStation(); return z ? 'Zender: ' + z : 'Er is nog geen zender: kies eerst een zender bij Radio (anders gaat de wekker niet aan).'; },
    get: () => setB(rdJson(Android.alarmState(), {}).on), set: v => setAlarm(() => { $('#al-on').checked = setOnV(v); }) },
  { id: 'wekker.tijd', scr: 'ralarm', ctl: '#al-time', type: 'tijd', label: 'Tijd van de radiowekker', desc: 'Hoe laat de radiowekker afgaat.', syn: 'wekker tijd alarm hoe laat radiowekker wakker', phr: ['wekker op', 'wekker om', 'wekker in op', 'radiowekker op', 'radiowekker om', 'wekker zetten op'],
    note: () => { const a = rdJson(Android.alarmState(), {}); if (!a.on) return 'De wekker staat nu uit; zet hem daarna aan bij Radiowekker.'; const z = setAlarmStation(); return z ? 'Zender: ' + z : ''; },
    get: () => { const a = rdJson(Android.alarmState(), {}); return String(a.hour != null ? a.hour : 7).padStart(2, '0') + ':' + String(a.minute || 0).padStart(2, '0'); }, set: v => setAlarm(() => { $('#al-time').value = v; }) },
  { id: 'wekker.dagen', scr: 'ralarm', ctl: '#al-days', type: 'openen', label: 'Dagen van de radiowekker', desc: 'Op welke dagen de wekker afgaat (geen dag = eenmalig).', syn: 'wekker dagen weekend werkdagen',
    get: () => { const d = rdJson(Android.alarmState(), {}).days; const n = ['ma', 'di', 'wo', 'do', 'vr', 'za', 'zo'].filter((x, i) => (d != null ? d : 31) & (1 << i)); return n.length ? n.join(', ') : 'eenmalig'; } },
  { id: 'wekker.zender', scr: 'ralarm', ctl: '#al-station', type: 'openen', label: 'Zender van de radiowekker', desc: 'Met welke zender je wakker wordt.', syn: 'wekker zender station radio',
    get: () => { const s = rdJson(Android.alarmState(), {}).station; return s && s.name ? s.name : 'nog geen'; } },
  /* ---------- Gesprekken uitschrijven, opnemen, meldingsgeschiedenis ---------- */
  { id: 'tx.taal', scr: 'transcripts', ctl: '#tx-lang', type: 'keuze', vals: [['nl', 'Nederlands'], ['en', 'Engels'], ['auto', 'Automatisch']], label: 'Taal bij uitschrijven', desc: 'De taal van gesprekken bij uitschrijven; geldt ook voor inspreken bij de assistent.', syn: 'taal uitschrijven transcriberen whisper spraak inspreken',
    get: () => txInfo().lang || 'nl', set: v => { Android.txSetLang(v); setIf('transcripts', () => { $('#tx-lang').value = v; }); return ''; } },
  { id: 'tx.model', scr: 'transcripts', ctl: '#tx-models input[name=trm]', type: 'keuze', vals: () => (txInfo().models || []).filter(m => m.installed).map(m => [m.id, m.label]), label: 'Spraakmodel', desc: 'Welk spraakmodel uitschrijft (ook bij inspreken): Snel of Nauwkeurig.', syn: 'spraakmodel whisper snel nauwkeurig uitschrijven inspreken model',
    get: () => txInfo().model || '', set: v => { Android.txSetModel(v); setIf('transcripts', enterTranscripts); return ''; } },
  { id: 'tx.nacht', scr: 'transcripts', ctl: '#tx-auto', label: '\'s Nachts automatisch uitschrijven', desc: 'Nieuwe opnames (van de laatste 14 dagen) worden vanaf 01:00 uitgeschreven terwijl de telefoon oplaadt.', syn: 'uitschrijven nacht automatisch transcriberen opnames',
    get: () => setB(rdJson(Android.txAutoState(), {}).on), set: v => { txAutoSet(setOnV(v)); return ''; } },
  { id: 'opnemen.auto', scr: 'recorder', ctl: '#recorder-auto', weak: v => v === 'aan', safe: 'privacy', label: 'Gesprekken automatisch opnemen', desc: 'Automatisch starten bij mobiele telefoongesprekken.', syn: 'opnemen gesprekken bellen opname automatisch',
    why: 'Gesprekken met anderen automatisch opnemen zet je zelf aan.', get: () => setB(rdJson(Android.recorderState(), {}).autoEnabled),
    set: v => { const e = Android.recorderSetAuto(setOnV(v)); $('#recorder-result').textContent = e || ''; loadRecorder(); return e || ''; } },
  { id: 'meldingen.geschiedenis', scr: 'history', ctl: '#history-enabled', weak: v => v === 'aan', safe: 'privacy', label: 'Meldingsgeschiedenis: nieuwe meldingen bewaren', desc: 'Meldingen van andere apps bewaren om later terug te lezen.', syn: 'meldingsgeschiedenis meldingen bewaren geschiedenis notificaties',
    why: 'Meldingen van andere apps bewaren zet je zelf aan.', get: () => setB(rdJson(Android.historyListBy('', '', 1), {}).enabled), set: v => { historyEnable(setOnV(v)); return ''; } },
  /* ---------- Mijn auto, Spotify, VPN, Locatie ---------- */
  { id: 'auto.ritten', scr: 'car', ctl: '#car-trips-on', weak: v => v === 'aan', safe: 'privacy', why: 'Elke rit met je locatie vanzelf opnemen zet je zelf aan.', label: 'Ritten vanzelf opnemen', desc: 'Bluetooth van de auto aan = rit begint, uit = rit wordt opgeslagen.', syn: 'auto ritten rit kilometers registratie bluetooth',
    get: () => setB(!!setCar().trips), set: v => setCarSave({ trips: setOnV(v) }) },
  { id: 'auto.rittype', scr: 'car', ctl: '#car-def', type: 'keuze', vals: [['prive', 'Privé'], ['zakelijk', 'Zakelijk']], label: 'Nieuwe ritten zijn privé of zakelijk', desc: 'Wat een nieuwe rit standaard is.', syn: 'auto ritten privé zakelijk standaard',
    get: () => setCar().defType || 'prive', set: v => setCarSave({ def: v }) },
  { id: 'auto.bluetooth', scr: 'car', ctl: '#car-dev', type: 'openen', label: 'Bluetooth van de auto', desc: 'Aan welke Bluetooth de app je auto herkent (voor parkeren en ritten).', syn: 'auto bluetooth parkeren carkit', get: () => setCar().name || 'nog niet gekozen' },
  { id: 'spotify.auto', scr: 'spotify', ctl: '#sp-auto', type: 'keuze', vals: [['day', 'Elke dag'], ['week', 'Elke week'], ['off', 'Uit']], label: 'Spotify vanzelf back-uppen', desc: 'Hoe vaak je playlists en bibliotheek vanzelf bewaard worden.', syn: 'spotify backup playlists automatisch',
    avail: () => rdJson(Android.spotifyState(), {}).linked ? '' : 'Koppel eerst Spotify', get: () => rdJson(Android.spotifyState(), {}).auto || 'day',
    set: v => { const s = $('#sp-auto'); if (s) s.value = v; Android.spotifyAuto(v); toast(v === 'off' ? 'Alleen nog met de hand' : 'Ingepland'); return ''; } },
  { id: 'vpn.onbekend', scr: 'vpn', ctl: '#vp-a-wifi', weak: v => v === 'uit', safe: 'beveiliging', label: 'VPN vanzelf aan op wifi die je niet vertrouwt', desc: 'Hotel, trein, café: de VPN gaat vanzelf aan.', syn: 'vpn wifi onbekend automatisch openbaar',
    why: 'De VPN minder vaak laten aangaan kies je zelf.', get: () => setB((setVp().auto || {}).wifi), set: v => setVpAuto('wifi', setOnV(v)) },
  { id: 'vpn.thuis', scr: 'vpn', ctl: '#vp-a-home', weak: v => v === 'aan', safe: 'beveiliging', label: 'VPN weer uit op vertrouwde wifi', desc: 'Als de VPN vanzelf aanging, gaat hij op vertrouwde wifi weer uit.', syn: 'vpn thuis vertrouwd wifi uit',
    why: 'De VPN minder vaak laten aangaan kies je zelf.', get: () => setB((setVp().auto || {}).home), set: v => setVpAuto('home', setOnV(v)) },
  { id: 'vpn.mobiel', scr: 'vpn', ctl: '#vp-a-mobile', weak: v => v === 'uit', safe: 'beveiliging', label: 'VPN ook aan op mobiel internet', desc: 'De VPN gaat ook vanzelf aan op 4G/5G.', syn: 'vpn mobiel 4g 5g data',
    why: 'De VPN minder vaak laten aangaan kies je zelf.', get: () => setB((setVp().auto || {}).mobile), set: v => setVpAuto('mobile', setOnV(v)) },
  { id: 'vpn.tunnels', scr: 'vpn', type: 'openen', safe: 'geheim', label: 'VPN-tunnels (sleutels en instellingen)', desc: 'Je WireGuard- en OpenVPN-tunnels.', syn: 'vpn tunnel wireguard openvpn sleutel configuratie', why: 'Sleutels en configuraties van de VPN lees ik niet voor en pas ik niet aan.' },
  { id: 'locatie.bewegen', scr: 'loc', ctl: '#lc-jitter', label: 'Locatie kiezen: een paar meter bewegen', desc: 'De gekozen plek beweegt een paar meter, dat is natuurlijker.', syn: 'locatie kiezen bewegen gps plek',
    get: () => setB(rdJson(Android.locState(), {}).jitter !== false), set: v => { Android.locJitter(setOnV(v)); setIf('loc', lcRender); return ''; } },
  /* ---------- Slaappraat ---------- */
  { id: 'slaap.herinnering', scr: 'sleep', ctl: '#sl-rem-on', label: 'Slaappraat: herinnering op bedtijd', desc: 'Elke avond een melding om Slaappraat aan te zetten.', syn: 'slaappraat herinnering bedtijd avond melding slapen',
    get: () => setB(!!setSl().remindAt), set: v => { const e = setSlNotif(setOnV(v)); if (e) return e; if (!!(slSt.set || {}).remindAt !== setOnV(v)) slRemToggle(); return ''; } },
  { id: 'slaap.herinnering.tijd', scr: 'sleep', ctl: '#sl-rem', type: 'tijd', label: 'Slaappraat: tijd van de herinnering', desc: 'Hoe laat de herinnering op bedtijd komt (zet de herinnering ook aan).', syn: 'slaappraat herinnering bedtijd tijd',
    get: () => setSl().remindAt || '', set: v => { const e = setSlNotif(true); if (e) return e; slSet('remindAt', v); return ''; } },
  { id: 'slaap.stoppen', scr: 'sleep', ctl: '#sl-stop-on', label: 'Slaappraat: vanzelf stoppen', desc: '\'s Ochtends om een vaste tijd stoppen met luisteren.', syn: 'slaappraat stoppen ochtend vanzelf',
    get: () => setB(!!setSl().stopAt), set: v => { slLoad(); if (!!(slSt.set || {}).stopAt !== setOnV(v)) slStopToggle(); return ''; } },
  { id: 'slaap.stoptijd', scr: 'sleep', ctl: '#sl-stop', type: 'tijd', label: 'Slaappraat: stoppen om', desc: 'Hoe laat Slaappraat \'s ochtends stopt.', syn: 'slaappraat stoppen tijd ochtend',
    get: () => setSl().stopAt || '', set: v => { slSet('stopAt', v); return ''; } },
  // Uit = een melding "Slaappraat luistert" op het vergrendelscherm; Rene wil hem standaard aan: uitzetten doet hij zelf
  { id: 'slaap.onopvallend', scr: 'sleep', ctl: '#sl-quiet', weak: v => v === 'uit', safe: 'privacy', why: 'Uit zet een melding "Slaappraat luistert" op je vergrendelscherm; dat zet je zelf uit bij Slaappraat.', label: 'Slaappraat: onopvallend luisteren', desc: 'Aan: de melding is stil, niet op het vergrendelscherm en zonder tijdteller, en \'s ochtends geen melding.', syn: 'slaappraat onopvallend stil discreet melding vergrendelscherm verbergen luisteren', need: /slaap|slapen|onopvallend|luister/,
    get: () => setB(setSl().quiet !== false), set: v => { slLoad(); if (((slSt.set || {}).quiet !== false) !== setOnV(v)) slSwitch('quiet'); return ''; } },
  { id: 'slaap.gevoeligheid', scr: 'sleep', ctl: '#sl-sens', type: 'keuze', vals: [['1', 'Ook gefluister'], ['2', 'Normaal'], ['3', 'Alleen duidelijk']], label: 'Slaappraat: gevoeligheid', desc: 'Hoe zacht iets mag zijn om bewaard te worden.', syn: 'slaappraat gevoelig gevoeligheid fluisteren zacht',
    get: () => String(setSl().sens || 2), set: v => { slSet('sens', v); return ''; } },
  { id: 'slaap.uitschrijven', scr: 'sleep', ctl: '#sl-tx', label: 'Slaappraat: daarna uitschrijven', desc: 'Wat je zei wordt op je telefoon uitgeschreven.', syn: 'slaappraat uitschrijven tekst',
    get: () => setB(setSl().transcribe !== false), set: v => { slLoad(); if (((slSt.set || {}).transcribe !== false) !== setOnV(v)) slSwitch('transcribe'); return ''; } },
  { id: 'slaap.meteen', scr: 'sleep', ctl: '#sl-direct', label: 'Slaappraat: meteen uitschrijven', desc: 'Elk stukje wordt tijdens de nacht al uitgeschreven, zodat je meteen ziet wat je zei. Kost wat accu; wanneer het mag, kies je bij Slaappraat-instellingen.', syn: 'slaappraat meteen direct uitschrijven nacht live maximum vol accu',
    get: () => setB(setSl().direct !== false), set: v => { slLoad(); if (((slSt.set || {}).direct !== false) !== setOnV(v)) slSwitch('direct'); return ''; } },
  // Slaappraat → Meer instellingen (Slaappraat-instellingen)
  { id: 'slaap.meteen.wanneer', scr: 'sleepset', ctl: '#sls-when', type: 'keuze', vals: [['accu', 'Lader of genoeg accu'], ['lader', 'Alleen aan de lader'], ['altijd', 'Altijd']], label: 'Slaappraat: meteen uitschrijven wanneer', desc: 'Aan de lader of met genoeg accu (standaard), alleen aan de lader, of altijd. Nooit tijdens een gesprek of als de telefoon warm is.', syn: 'slaappraat meteen uitschrijven lader accu wanneer',
    get: () => setSl().directWhen || 'accu', set: v => { slSet('directWhen', v); return ''; } },
  { id: 'slaap.meteen.accu', scr: 'sleepset', ctl: '#sls-dbat', type: 'keuze', vals: [['20', '20%'], ['30', '30%'], ['40', '40%'], ['50', '50%'], ['60', '60%'], ['80', '80%']], label: 'Slaappraat: meteen uitschrijven zonder lader vanaf', desc: 'Zonder lader alleen meteen uitschrijven vanaf dit accupercentage; daaronder na het luisteren.', syn: 'slaappraat meteen uitschrijven accu drempel procent batterij',
    get: () => String(setSl().directBat || 40), set: v => { slSet('directBat', v); return ''; } },
  { id: 'slaap.model', scr: 'sleepset', ctl: '#sls-model', type: 'keuze', vals: [['volg', 'Zoals Gesprekken uitschrijven'], ['base', 'Snel'], ['small', 'Nauwkeurig']], label: 'Slaappraat: spraakmodel', desc: 'Welk spraakmodel Slaappraat gebruikt om uit te schrijven (een model dat er niet is: dat van Gesprekken uitschrijven).', syn: 'slaappraat spraakmodel model snel nauwkeurig uitschrijven whisper',
    get: () => setSl().txModel || 'volg', set: v => { slSet('txModel', v === 'volg' ? '' : v); return ''; } },
  { id: 'slaap.maximum', scr: 'sleepset', ctl: '#sls-max', type: 'keuze', vals: [['1', '1 uur'], ['2', '2 uur'], ['4', '4 uur'], ['8', '8 uur'], ['0', 'Onbeperkt']], label: 'Slaappraat: hooguit geluid per nacht', desc: 'Hoeveel geluid er per nacht bewaard blijft (8 uur AAC is ±115 MB).', syn: 'slaappraat maximum geluid nacht uur opslag vol',
    get: () => String(setSl().maxAudio == null ? 8 : setSl().maxAudio), set: v => { slSet('maxAudio', v); return ''; } },
  { id: 'slaap.vol', scr: 'sleepset', ctl: '#sls-full', type: 'keuze', vals: [['geluid', 'Oudste geluid weg voor gepraat'], ['oudste', 'Oudste weg'], ['stop', 'Stoppen met bewaren']], label: 'Slaappraat: als het vol is', desc: 'Wat er gebeurt als het maximum aan geluid voor de nacht bereikt is.', syn: 'slaappraat vol maximum oudste weg bewaren',
    get: () => setSl().whenFull || 'geluid', set: v => { slSet('whenFull', v); return ''; }, warn: v => v === 'oudste' ? 'Dan kan ook het geluid van gepraat weggaan (de uitgeschreven tekst blijft).' : '' },
  { id: 'slaap.vrij', scr: 'sleepset', ctl: '#sls-free', type: 'keuze', vals: [['250', '250 MB'], ['500', '500 MB'], ['1000', '1 GB'], ['2000', '2 GB']], label: 'Slaappraat: altijd vrij laten', desc: 'Zoveel ruimte blijft er altijd vrij op de telefoon; anders bewaart hij alleen het tijdstip.', syn: 'slaappraat vrije ruimte opslag vrij laten',
    get: () => String(setSl().minFree || 500), set: v => { slSet('minFree', v); return ''; } },
  { id: 'slaap.formaat', scr: 'sleepset', ctl: '#sls-fmt', type: 'keuze', vals: [['aac', 'AAC (klein)'], ['wav', 'WAV (onbewerkt)']], label: 'Slaappraat: bewaren als', desc: 'AAC (±14 MB per uur) of WAV (±115 MB per uur).', syn: 'slaappraat formaat aac wav bestand geluid',
    get: () => setSl().format || 'aac', set: v => { slSet('format', v); return ''; } },
  { id: 'slaap.stuklengte', scr: 'sleepset', ctl: '#sls-clip', type: 'keuze', vals: [['30', '30 s'], ['60', '1 min'], ['120', '2 min'], ['300', '5 min']], label: 'Slaappraat: langste stukje', desc: 'Wat langer duurt, wordt in stukken geknipt (vanaf de volgende nacht).', syn: 'slaappraat stukje lengte duur knippen',
    get: () => String(setSl().clipMax || 60), set: v => { slSet('clipMax', v); return ''; } },
  { id: 'slaap.stilte', scr: 'sleepset', ctl: '#sls-hang', type: 'keuze', vals: [['1000', '1 s'], ['1500', '1,5 s'], ['2000', '2 s'], ['3000', '3 s']], label: 'Slaappraat: stilte voor het einde van een stukje', desc: 'Hoe lang het stil moet zijn voordat een stukje eindigt (vanaf de volgende nacht).', syn: 'slaappraat stilte einde stukje pauze',
    get: () => String(setSl().hang || 1500), set: v => { slSet('hang', v); return ''; } },
  { id: 'slaap.voorloop', scr: 'sleepset', ctl: '#sls-pre', type: 'keuze', vals: [['1', '1 s'], ['2', '2 s'], ['3', '3 s'], ['5', '5 s']], label: 'Slaappraat: voorloop', desc: 'Hoeveel van vóór het geluid meegaat in een stukje (vanaf de volgende nacht).', syn: 'slaappraat voorloop begin stukje',
    get: () => String(setSl().pre || 2), set: v => { slSet('pre', v); return ''; } },
  { id: 'slaap.stopaccu', scr: 'sleepset', ctl: '#sls-stop', type: 'keuze', vals: [['5', '5%'], ['10', '10%'], ['15', '15%'], ['20', '20%'], ['30', '30%']], label: 'Slaappraat: stoppen zonder lader bij', desc: 'Bij dit accupercentage stopt het luisteren als de telefoon niet aan de lader ligt.', syn: 'slaappraat stoppen accu batterij leeg procent',
    get: () => String(setSl().stopBat || 15), set: v => { slSet('stopBat', v); return ''; } },
  { id: 'slaap.geluiden', scr: 'sleep', ctl: '#sl-noise', label: 'Slaappraat: ook snurken en andere geluiden bewaren', desc: 'Uit: van wat geen praten blijkt, blijft alleen het tijdstip.', syn: 'slaappraat snurken geluiden bewaren',
    get: () => setB(setSl().keepNoise !== false), set: v => { slLoad(); if (((slSt.set || {}).keepNoise !== false) !== setOnV(v)) slSwitch('keepNoise'); return ''; } },
  { id: 'slaap.bewaren', scr: 'sleep', ctl: '#sl-keep', type: 'keuze', vals: [['7', '1 week'], ['14', '2 weken'], ['30', '30 dagen']], label: 'Slaappraat: nachten bewaren', desc: 'Hoe lang nachten bewaard blijven; daarna worden ze vanzelf gewist.', syn: 'slaappraat bewaren nachten wissen dagen weken',
    get: () => String(setSl().keep || 14), set: v => { slSet('keep', v); return ''; }, warn: v => +v < +(setSl().keep || 14) ? 'Nachten ouder dan ' + (v === '7' ? 'een week' : '2 weken') + ' worden dan gewist.' : '' },
  /* ---------- Lokale AI (de assistent) ---------- */
  { id: 'ai.model', scr: 'aisetmodel', type: 'keuze', vals: () => (setAi().models || []).filter(m => !m.embed && m.state === 'ready').map(m => [m.id, m.label]), vname: v => (aiModel(v) || {}).label || v, label: 'Taalmodel van de assistent', desc: 'Welk gedownload taalmodel de assistent gebruikt (groter = slimmer maar trager).', syn: 'taalmodel model qwen klein groot assistent',
    get: () => setAi().chat || '', set: v => { aiUse(v); return ''; } },
  { id: 'ai.style', scr: 'aisetanswer', ctl: '#aic-style', type: 'keuze', vals: [['kort', 'Kort'], ['normaal', 'Normaal'], ['uitgebreid', 'Uitgebreid']], label: 'Lengte van de antwoorden', desc: 'Hoe lang de antwoorden van de assistent zijn.', syn: 'antwoorden lengte kort uitgebreid assistent',
    get: () => setAiCfg('style'), set: v => { aicSet('style', v); return ''; } },
  { id: 'ai.creative', scr: 'aisetanswer', ctl: '#aic-creative', type: 'keuze', vals: [['precies', 'Precies'], ['normaal', 'Gebalanceerd'], ['creatief', 'Creatief']], label: 'Creativiteit van de antwoorden', desc: 'Precies voor samenvatten en vertalen, creatief voor ideeën en verhalen.', syn: 'creativiteit creatief precies antwoorden assistent',
    get: () => setAiCfg('creative'), set: v => { aicSet('creative', v); return ''; } },
  { id: 'ai.lang', scr: 'aisetanswer', ctl: '#aic-lang', type: 'keuze', vals: [['nl', 'Nederlands'], ['en', 'Engels'], ['auto', 'Zelfde taal als de vraag']], label: 'Taal van de antwoorden', desc: 'In welke taal de assistent antwoordt.', syn: 'taal antwoorden engels nederlands assistent',
    get: () => setAiCfg('lang'), set: v => { aicSet('lang', v); return ''; } },
  { id: 'ai.history', scr: 'aisetanswer', ctl: '#aic-history', type: 'keuze', vals: [['8', 'Kort (8 berichten)'], ['24', 'Normaal (24 berichten)'], ['48', 'Lang (48 berichten)']], label: 'Gesprek onthouden', desc: 'Hoeveel berichten van het gesprek de assistent meeneemt.', syn: 'gesprek onthouden berichten geschiedenis context assistent',
    get: () => setAiCfg('history'), set: v => { aicSet('history', v); return ''; } },
  { id: 'ai.about', scr: 'aisetpers', type: 'tekst', label: 'Over mij (voor de assistent)', desc: 'Wat de assistent bij elke vraag over je meekrijgt.', syn: 'over mij persoonlijk assistent' },
  { id: 'ai.instr', scr: 'aisetpers', type: 'tekst', label: 'Hoe moet de assistent antwoorden?', desc: 'Eigen instructies voor de assistent.', syn: 'instructies persoonlijk aanspreken assistent' },
  { id: 'ai.mem', scr: 'aisetmem', ctl: '#aim-use', label: 'Geheugen van de assistent gebruiken', desc: 'Uit: de assistent gebruikt en onthoudt niets (wat er staat, blijft bewaard).', syn: 'geheugen onthouden assistent herinneringen',
    get: () => setAiCfg('mem') === 'uit' ? 'uit' : 'aan', set: v => { aicSet('mem', v); return ''; } },
  { id: 'ai.learn', scr: 'aisetmem', ctl: '#aic-learn', weak: v => v === 'vanzelf', safe: 'privacy', why: 'Of de assistent zonder te vragen dingen over je onthoudt, kies je zelf bij Geheugen.', type: 'keuze', vals: [['vragen', 'Eerst vragen'], ['vanzelf', 'Vanzelf'], ['uit', 'Uit']], label: 'Leren uit gesprekken', desc: 'Of de assistent uit gesprekken iets over je onthoudt, en of hij het eerst vraagt.', syn: 'leren geheugen onthouden assistent',
    get: () => setAiCfg('learn'), set: v => { aicSet('learn', v); return ''; } },
  { id: 'ai.voicesend', scr: 'aisetvoice', ctl: '#ais-vsend', label: 'Meteen versturen na inspreken', desc: 'Uit: de ingesproken tekst komt eerst in het invoerveld, zodat je hem kunt nakijken.', syn: 'inspreken spraak versturen microfoon meteen',
    get: () => setB(setAi().voiceSend === true), set: v => { if ((setAi().voiceSend === true) !== setOnV(v)) aisVoiceSend(); return ''; } },
  { id: 'ai.speakvoice', scr: 'aisetvoice', ctl: '#ais-vspeak', label: 'Antwoord voorlezen als je de vraag insprak', desc: 'De assistent leest het antwoord voor met de Nederlandse stem van je telefoon.', syn: 'voorlezen spraak stem antwoord inspreken praten',
    get: () => setB(!!setAi().speakVoice), set: v => { if (!!setAi().speakVoice !== setOnV(v)) aisVoiceSpeak(); return ''; } },
  { id: 'ai.notes', scr: 'ai', ctl: '#ai-notes', label: 'Met mijn gegevens (de assistent zoekt eerst in je gegevens)', desc: 'De assistent zoekt bij een vraag eerst in wat je bij Gegevens aanzette.', syn: 'gegevens notities zoeken assistent met mijn',
    avail: () => aiReady('zoek') ? '' : 'Download eerst het model voor slim zoeken', get: () => setB(!!setAi().notes), set: v => { if (!!setAi().notes !== setOnV(v)) aiToggleNotes(); return ''; } },
  { id: 'ai.power', scr: 'aisetperf', ctl: '#aic-power', type: 'keuze', vals: [['zuinig', 'Zuinig'], ['normaal', 'Normaal'], ['max', 'Maximaal']], label: 'Rekenkracht van de assistent', desc: 'Zuinig is koeler en zuiniger met de accu, maximaal is het snelst.', syn: 'rekenkracht snelheid accu batterij zuinig processor',
    get: () => setAiCfg('power'), set: v => { aicSet('power', v); return ''; } },
  { id: 'ai.idle', scr: 'aisetperf', ctl: '#aic-idle', type: 'keuze', vals: [['1', '1 minuut'], ['4', '4 minuten'], ['15', '15 minuten']], label: 'Geheugen vrijgeven na', desc: 'Na hoeveel minuten niets doen het taalmodel uit het geheugen gaat.', syn: 'geheugen vrijgeven ram minuten model',
    get: () => setAiCfg('idle'), set: v => { aicSet('idle', v); return ''; } },
  { id: 'ai.wifi', scr: 'aisetperf', ctl: '#ais-wifi, #md-wifi', label: 'Modellen alleen via wifi downloaden', desc: 'De modellen zijn groot (0,6 tot 3 GB).', syn: 'wifi downloaden modellen mobiel internet data',
    get: () => setB(!!setAi().wifiOnly), set: v => { if (!!setAi().wifiOnly !== setOnV(v)) aisWifi(); setIf('models', () => { mdSt.wifiOnly = setOnV(v); mdRender(); }); return ''; } },
  /* ---------- Gebruik ---------- */
  { id: 'gebruik.aan', scr: 'gebruikset', ctl: '#gb-collect', label: 'Gebruik bijhouden', desc: 'Welke functies en apps gebruikt worden en wanneer, bijhouden op je telefoon.', syn: 'gebruik schermtijd bijhouden usage statistieken',
    get: () => setB(gbSet().collect !== false), set: v => { gbSaveOne('collect', setOnV(v)); setIf('gebruikset', enterGebruikSet); return ''; } },
  { id: 'gebruik.bewaren', scr: 'gebruikset', ctl: '#gb-keep', type: 'keuze', vals: [['180', '6 maanden'], ['365', '1 jaar'], ['730', '2 jaar']], label: 'Gebruik: samenvatting per dag bewaren', desc: 'Hoe lang de samenvatting per dag (schermtijd, ontgrendelingen) bewaard blijft.', syn: 'gebruik bewaren bewaartermijn dagen jaar schermtijd',
    get: () => String(gbSet().keep || 365), set: v => { gbSaveOne('keep', +v); setIf('gebruikset', enterGebruikSet); return ''; } },
  { id: 'gebruik.detail', scr: 'gebruikset', ctl: '#gb-detail', type: 'keuze', vals: [['30', '30 dagen'], ['60', '60 dagen'], ['90', '90 dagen']], label: 'Gebruik: gedetailleerde tijdlijn bewaren', desc: 'Hoe lang de tijdlijn met alle stapjes (per ontgrendeling, per app) bewaard blijft.', syn: 'gebruik tijdlijn detail bewaren dagen',
    get: () => String(gbSet().detail || 60), set: v => { gbSaveOne('detail', +v); setIf('gebruikset', enterGebruikSet); return ''; } },
  { id: 'gebruik.apparaatbeheer', scr: 'gebruikset', ctl: '#gb-admin', type: 'aan', safe: 'beveiliging', label: 'Gebruik: apparaatbeheer', desc: 'Apparaatbeheer om ontgrendelingen met pincode en mislukte pogingen te zien.', syn: 'gebruik apparaatbeheer device admin pincode ontgrendelen vergrendelscherm',
    why: 'Apparaatbeheer zet je zelf aan en uit, met de bevestiging van Android.', get: () => setB(gbState().admin) },
  { id: 'gebruik.foto.mislukt', scr: 'gebruikset', ctl: '#gb-pfail', type: 'aan', weak: v => v === 'aan', safe: 'privacy', label: 'Gebruik: foto bij een mislukte poging', desc: 'Een stille foto met de voorcamera bij genoeg mislukte ontgrendelpogingen (blijft alleen in de app).', syn: 'gebruik foto voorcamera mislukte poging camera',
    why: 'Een foto bij het vergrendelscherm zet je zelf aan.', get: () => setB(gbSet().photoFail) },
  { id: 'gebruik.foto.ontgrendeld', scr: 'gebruikset', ctl: '#gb-punlock', type: 'aan', weak: v => v === 'aan', safe: 'privacy', label: 'Gebruik: foto bij ontgrendelen', desc: 'Een stille foto met de voorcamera als er met pincode ontgrendeld wordt (blijft alleen in de app).', syn: 'gebruik foto voorcamera ontgrendelen camera',
    why: 'Een foto bij het ontgrendelen zet je zelf aan.', get: () => setB(gbSet().photoUnlock) },
  { id: 'gebruik.foto.na', scr: 'gebruikset', ctl: '#gb-pafter', type: 'keuze', vals: [['1', '1'], ['2', '2'], ['3', '3'], ['5', '5']], label: 'Gebruik: foto na dit aantal mislukte pogingen', desc: 'Na hoeveel mislukte pogingen achter elkaar er een foto gemaakt wordt.', syn: 'gebruik foto pogingen aantal mislukt',
    get: () => String(gbSet().photoAfter || 3), set: v => { gbSaveOne('photoAfter', +v); setIf('gebruikset', enterGebruikSet); return ''; } },
  { id: 'gebruik.foto.bewaren', scr: 'gebruikset', ctl: '#gb-pkeep', type: 'keuze', vals: [['7', '1 week'], ['30', '30 dagen'], ['90', '90 dagen']], label: 'Gebruik: foto\'s bewaren', desc: 'Hoe lang de foto\'s bij ontgrendelen bewaard blijven.', syn: 'gebruik foto bewaren dagen week',
    get: () => String(gbSet().photoKeep || 30), set: v => { gbSaveOne('photoKeep', +v); setIf('gebruikset', enterGebruikSet); return ''; } }
];

const SET_SRC_PRIV = ['sms', 'wa', 'calls', 'contacts', 'tx', 'sleep', 'checkin'];
/* Lijsten die per item een instelling hebben (automatiseringen, bronnen van de AI, onderdelen van de backup) */
const SET_GEN = [
  { cover: '#au-list .tg', gen: () => { auLoad(); return (auState.rules || []).map(r => ({ id: 'auto.regel.' + setHash(r.id), scr: 'auto', label: 'Automatisering "' + (r.name || 'Zonder naam') + '"',
    desc: 'Deze automatisering aan of uit.', syn: 'automatisering automatiseren regel ' + (r.name || ''), get: () => { auLoad(); const x = auState.rules.find(y => y.id === r.id); return setB(!x || x.on !== false); },
    set: v => { Android.autoSetOn(r.id, setOnV(v)); auLoad(); setIf('auto', auRender); return ''; } })); } },
  { cover: '#aid-list .tg', gen: () => AI_SRC.map(s => ({ id: 'ai.bron.' + s[0], scr: 'aisetdata', label: 'Assistent mag kijken in: ' + s[1], desc: s[3], syn: 'gegevens bron assistent zoekindex slim zoeken ' + s[1],
    // Berichten en gegevens van anderen (zoals AI_OTHERS) en wat elders privé is (Slaappraat, Check-in): aanzetten doe je zelf
    weak: SET_SRC_PRIV.includes(s[0]) ? (v => v === 'aan') : null, safe: SET_SRC_PRIV.includes(s[0]) ? 'privacy' : '', why: 'Of de assistent in berichten van anderen of in privé-gegevens mag kijken, zet je zelf aan bij Gegevens.',
    get: () => { setAi(); return setB(aiSrcOn(s[0])); }, set: v => { setAi(); if (aiSrcOn(s[0]) !== setOnV(v)) aiSrcToggle(s[0]); return ''; } })) },
  { cover: '#bk-parts input[type=checkbox]', gen: () => Object.keys(BK_PARTS).map(k => ({ id: 'backup.deel.' + k, scr: 'backup', ctl: '#bk-parts input[data-p="' + k + '"]', label: 'Mee in Alles back-uppen: ' + BK_PARTS[k], desc: 'Of dit meegaat met Alles back-uppen.',
    warn: v => v === 'uit' ? BK_PARTS[k] + ' gaat dan niet meer mee in de backup.' : '',
    // Meldingsgeschiedenis staat standaard uit: berichten van anderen (ook inlogcodes), soms onversleuteld in de backup-map
    weak: k === 'notifications' ? (v => v === 'aan') : null, safe: k === 'notifications' ? 'privacy' : '', why: 'De meldingsgeschiedenis (met berichten van anderen) zet je zelf aan in de backup.', syn: 'backup mee onderdeel ' + BK_PARTS[k],
    get: () => { const p = setBk().parts || {}; return setB(p[k] === true || (k !== 'notifications' && p[k] !== false)); }, set: v => { Android.backupSetPart(k, setOnV(v)); setIf('backup', enterBackup); return ''; } })) },
  { cover: '#bk-private input[type=checkbox]', gen: () => Object.keys(BK_PRIV).map(k => ({ id: 'backup.prive.' + k, scr: 'backup', ctl: '#bk-private input[data-p="' + k + '"]', label: 'Privé mee in Alles back-uppen: ' + BK_PRIV[k], desc: 'Privé-onderdelen staan standaard uit; zonder versleutelen komen ze leesbaar in je backup-map.', syn: 'backup privé ' + BK_PRIV[k],
    weak: v => v === 'aan', safe: 'privacy', why: 'Privé-onderdelen in de backup zet je zelf aan, zodat je bewust kiest (bij voorkeur versleuteld).',
    get: () => setB((setBk().parts || {})[k] === true), set: v => { Android.backupSetPart(k, setOnV(v)); setIf('backup', enterBackup); return ''; } })) }
];

/* Woorden die in de vraag moeten staan om een instelling van die tool te bedoelen (anders telt hij minder zwaar mee):
   "zet de nachtelijke backup aan" is Alles back-uppen, niet WhatsApp */
const SET_NEED = [['wa.leesbaar', /whatsapp|whats app|leesba|chats/], ['wa.', /whatsapp|whats app|\bwa\b/], ['redial.', /redial|bellen|beller|pogingen/], ['slaap.', /slaap|slapen|bedtijd|snurk|nachten/],
  ['spotify.', /spotify/], ['vpn.', /vpn|tunnel/], ['auto.ritten', /auto|rit/], ['auto.rittype', /auto|rit/], ['auto.bluetooth', /auto|bluetooth|carkit/], ['tx.', /uitschrijv|transcri|spraak|whisper|taal|gesprek/],
  ['meldingen.geschiedenis', /geschiedenis|bewaren|bewaar/], ['wekker.', /wekker|alarm/], ['opnemen.', /opnem|opname/], ['locatie.', /locatie|gps|plek/], ['start.', /startscherm|tegel|home|groep|compact|overzicht/],
  ['ai.wifi', /model|download/], ['radio.', /radio|terugspoel|pauzeer|buffer/], ['podcast.', /podcast|afspeel/], ['backup.deel.', /backup|back-up|back up|mee/], ['backup.prive.', /backup|back-up|back up|privé|prive/]];

/* Schakelaars en keuzes die bewust niet in het register staan, met de reden */
const SET_EXCLUDE = [
  ['#rp-time, #rp-days', 'Auto redial op een tijdstip hoort bij een nummer; dat plan je op het scherm (zie redial.planning)'],
  ['#wa-cats', 'De selectie van WhatsApp-mappen kies je op het scherm'],
  ['#bk-wa', 'Keuze voor één keer, bij Nu alles back-uppen'],
  ['#rd-genres', 'Een filter in de zenderlijst, geen instelling'],
  ['#note-split-source, #note-split-separator, #note-split-keep', 'Keuzes van het venster Lijst splitsen, voor één keer'],
  ['#note-repeat, #note-rem-rep, #note-pin-app', 'Hoort bij één notitie of herinnering'],
  ['#calls-filter, #cf-period, #cf-who, #cf-dur', 'Filters in de oproepenlijst, geen instelling'],
  ['#s-autoed .tg, #s-autoed [role=radiogroup], #s-autoed .chips, #s-autoed select, #s-autoed input', 'Hoort bij één automatisering (de editor)'],
  ['#kl-category, #kl-favorites, #kl-sort, #kl-edit-favorite', 'Filters en gegevens van één document in de kluis'],
  ['#history-apps', 'Filter in de meldingsgeschiedenis'],
  ['#s-pdf select, #s-pdf input, #s-pdf [role=radiogroup], #s-pdf .chips', 'Keuzes bij het maken of bewerken van één pdf'],
  ['#pd-dl, #pd-sort, #pd-hide', 'Hoort bij één podcast, of is een filter'],
  ['#s-podspotify input, #s-podspotify select', 'Keuzes bij het overzetten van podcasts, voor één keer'],
  ['#sln-filter', 'Filter bij één nacht van Slaappraat'],
  ['#s-checkined .tg, #s-checkined [role=radiogroup], #s-checkined [role=group], #s-checkined .chips, #s-checkined select, #s-checkined input', 'Hoort bij één vraag van Check-in (de editor)'],
  ['#ciq-view, #s-checkincmp select, #s-checkincmp [role=radiogroup], #s-checkincmp .chips', 'Weergave van de grafieken van Check-in'],
  ['#car-months', 'Maandkeuze in de rittenlijst'],
  ['#s-calls input[type=date]', 'Filter in de oproepenlijst'],
  ['#history-search, #gs-q', 'Zoekveld']
];
