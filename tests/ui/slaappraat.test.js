// Slaappraat: starten (met toestemming), luisteren, stoppen, de nacht bekijken, afspelen, wissen, instellingen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    ok(await pg.isVisible('#tile-sleep'), 'tegel op het startscherm');
    await pg.click('#tile-sleep'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'sleep') && (await pg.textContent('#sl-btn')) === 'Begin met luisteren', 'scherm: begin met luisteren');
    ok((await pg.textContent('#sl-nights')).includes('Nog geen nachten'), 'nog geen nachten');
    // Zonder microfoon: eerst toestemming
    await pg.evaluate(() => { Android._sl.mic = false; });
    await pg.click('#sl-btn'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sl.phase === '' && Android._sl.mic === true) && (await pg.textContent('#toast')).includes('microfoon'), 'zonder microfoon: vraagt toestemming, start niet');
    // Starten
    await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => Android._sl.phase === 'luistert') && (await pg.textContent('#sl-status')).includes('Luistert') && (await pg.textContent('#sl-btn')) === 'Stoppen', 'luistert, met stopknop');
    ok(await pg.isVisible('#sl-meter') && (await pg.textContent('#sl-status')).includes('stopt om'), 'meter en stoptijd te zien');
    await pg.evaluate(() => { Android._slHear('Ik moet de trein nog halen', 3); Android._slHear('', 6); Android._slHear('Nee, niet de rode', 2); }); await pg.waitForTimeout(1200);
    ok((await pg.textContent('#sl-status')).includes('3 keer iets gehoord'), 'telt mee terwijl hij luistert');
    await pg.screenshot({ path: __dirname + '/shots/slaappraat-' + scheme + '.png', fullPage: true });
    // Stoppen: daarna uitgeschreven
    await pg.click('#sl-btn'); await pg.waitForTimeout(800);
    ok(await pg.evaluate(() => Android._sl.phase === '') && (await pg.textContent('#sl-btn')) === 'Begin met luisteren', 'gestopt');
    ok((await pg.textContent('#sl-nights')).includes('2 keer gepraat') && (await pg.textContent('#sl-nights')).includes('1 geluid') && (await pg.textContent('#sl-nights')).includes('Ik moet de trein'), 'nacht: gepraat, geluid en het eerste wat je zei');
    // De nacht
    await pg.click('#sl-nights .strow'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'sleepnight') && (await pg.$$('#sln-list .sl-clip')).length === 2, 'nacht: alleen gepraat (2)');
    await pg.click('#sln-filter button[data-v=alles]'); await pg.waitForTimeout(80);
    ok((await pg.$$('#sln-list .sl-clip')).length === 3 && (await pg.textContent('#sln-list')).includes('Geluid (geen woorden)'), 'alles: ook het geluid');
    ok((await pg.$$('#sln-list .sl-clip:has-text("Geluid") button[aria-label^="Afspelen"]')).length === 0, 'geluid niet bewaard: niet af te spelen');
    await pg.click('#sln-list .sl-clip:has-text("trein") button[aria-label^="Afspelen"]'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._sl.played !== '') && await pg.isVisible('#sln-list .sl-clip:has-text("trein") button[aria-label^="Stoppen"]'), 'afspelen, en de knop wordt stoppen');
    await pg.screenshot({ path: __dirname + '/shots/slaappraat-nacht-' + scheme + '.png', fullPage: true });
    await pg.click('#sln-list .sl-clip:has-text("rode") button[aria-label^="Wissen"]'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await pg.$$('#sln-list .sl-clip')).length === 2, 'stukje gewist');
    // Als notitie
    await pg.click('#sln-menu'); await pg.click('#sheet-acts button:has-text("Als notitie bewaren")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => notesGet().some(n => n.title.startsWith('Slaappraat') && n.text.includes('trein'))), 'als notitie bewaard');
    // Terug: afspelen stopt
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'sleep' && Android._sl.played === ''), 'terug: afspelen gestopt');
    // Nog niet uitgeschreven (uitschrijven stond uit): later alsnog
    await pg.evaluate(() => { Android._sl.set.transcribe = false; });
    await pg.click('#sl-btn'); await pg.waitForTimeout(600);
    await pg.evaluate(() => { Android._slHear('Waar is de kat', 3); });
    await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    await pg.click('#sl-nights .strow:first-child'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#sln-tx')).includes('nog niet uitgeschreven'), 'nog niet uitgeschreven: knop om het nu te doen');
    await pg.click('#sln-tx button:has-text("Nu uitschrijven")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#sln-tx')).includes('Wordt nu uitgeschreven'), 'uitschrijven: voortgang te zien');
    ok((await pg.$('#sln-menu')) && await pg.evaluate(() => Android.sleepTranscribe(slNightId) !== ''), 'tijdens het uitschrijven: niet nog eens');
    await pg.waitForTimeout(1500);
    ok((await pg.textContent('#sln-list')).includes('Waar is de kat') && !(await pg.textContent('#sln-tx')).includes('Wordt nu'), 'nu uitgeschreven');
    // Tijdens het uitschrijven opnieuw beginnen met luisteren
    await pg.evaluate(() => { goBack(); Android._sl.phase = 'uitschrijven'; Android._sl.txDone = 1; Android._sl.txTotal = 4; slLoad(); slRender(); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#sl-status')).includes('Uitschrijven… 1 van 4') && (await pg.textContent('#sl-btn')).includes('Opnieuw beginnen'), 'uitschrijven: voortgang en opnieuw beginnen');
    await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => Android._sl.phase === 'luistert'), 'opnieuw beginnen: luistert');
    await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    await pg.click('#sl-nights .strow:nth-child(2)'); await pg.waitForTimeout(200);
    // Nacht wissen
    await pg.click('#sln-menu'); await pg.click('#sheet-acts button:has-text("Deze nacht wissen")'); await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'sleep' && Android._sl.nights.length === 2 && !Android._sl.nights.some(n => n.clips.some(k => k.text === 'Waar is de kat'))), 'nacht gewist');
    // Instellingen
    await pg.click('#sl-sens button[data-v="3"]'); await pg.click('#sl-keep button[data-v="30"]'); await pg.click('#sl-noise');
    ok(await pg.evaluate(() => Android._sl.set.sens === 3 && Android._sl.set.keep === 30 && Android._sl.set.keepNoise === true) && (await pg.textContent('#sl-sens-n')).includes('duidelijk'), 'gevoeligheid, bewaren en geluiden');
    ok((await pg.getAttribute('#sl-quiet', 'aria-checked')) === 'true' && (await pg.textContent('#sl-quiet-n')).includes('vergrendelscherm'), 'onopvallend luisteren staat standaard aan');
    await pg.click('#sl-quietrow button'); ok(await pg.evaluate(() => Android._notifOpen === 'sleeptalk-stil'), 'melding verder wegstoppen: Android-instelling van de soort');
    await pg.click('#sl-quiet'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.quiet === false) && (await pg.getAttribute('#sl-quiet', 'aria-checked')) === 'false' && (await pg.textContent('#sl-quiet-n')).includes('stopknop') && !(await pg.isVisible('#sl-quietrow')), 'onopvallend luisteren uit');
    await pg.click('#sl-quiet'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.quiet === true) && (await pg.textContent('#sl-quiet-n')).includes('vergrendelscherm'), 'onopvallend luisteren weer aan');
    await pg.click('#sl-stop-on'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.stopAt === '') && !(await pg.isVisible('#sl-stop')) && (await pg.textContent('#sl-stop-n')).includes('tot je stopt'), 'vanzelf stoppen uit');
    await pg.click('#sl-stop-on'); await pg.fill('#sl-stop', '06:45'); await pg.dispatchEvent('#sl-stop', 'change'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.stopAt === '06:45'), 'stoptijd 06:45');
    // Zonder spraakmodel: uitleg
    await pg.evaluate(() => { Android._sl.model = ''; slLoad(); slRender(); });
    ok((await pg.textContent('#sl-tx-n')).includes('Gesprekken uitschrijven'), 'zonder spraakmodel: waar je het downloadt');
    // Proefronde: live zien wat hij oppikt, niets bewaren
    await pg.evaluate(() => { Android._sl.model = 'Snel (57 MB)'; Android._sl.mic = true; slLoad(); slRender(); });
    const nightsBefore = await pg.evaluate(() => Android._sl.nights.length);
    await pg.click('#sl-probe'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sl.phase === 'proef') && (await pg.textContent('#sl-status')).includes('Proef') && !(await pg.isVisible('#sl-probe')), 'proef: bezig, knop weg');
    await pg.evaluate(() => { Android._sl.hearing = true; Android._sl.probeHits = 2; Android._sl.probeLeft = 12000; }); await pg.waitForTimeout(1200);
    ok((await pg.textContent('#sl-status')).includes('Hoort nu iets') && (await pg.textContent('#sl-status')).includes('2 keer opgepikt') && (await pg.textContent('#sl-status')).includes('nog 12 s'), 'proef: live wat hij hoort');
    await pg.evaluate(() => { Android._sl.hearing = false; Android._slProbeEnd(); }); await pg.waitForTimeout(1200);
    ok((await pg.textContent('#sl-status')).includes('Proef klaar: 2 keer') && await pg.evaluate(n => Android._sl.nights.length === n, nightsBefore) && await pg.isVisible('#sl-probe'), 'proef klaar: uitslag, niets bewaard');
    // Accu: per nacht en gemiddeld zonder lader
    await pg.evaluate(() => { Android._sl.bat = 90; Android._sl.batEnd = 82; Android._sl.nightMs = 4 * 3600e3; Android._sl.plugged = false; });
    await pg.click('#sl-btn'); await pg.waitForTimeout(600); await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    ok((await pg.textContent('#sl-bat')).includes('2,0% accu per uur'), 'accu: gemiddeld per uur zonder lader (' + (await pg.textContent('#sl-bat')) + ')');
    await pg.click('#sl-nights .strow:first-child'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#sln-sum')).includes('accu 90% → 82%'), 'accu bij de nacht');
    await pg.evaluate(() => { goBack(); Android._sl.nightMs = 0; });  await pg.waitForTimeout(100);
    // Herinnering op bedtijd
    ok(!(await pg.isVisible('#sl-rem')), 'herinnering: standaard uit');
    await pg.evaluate(() => { Android._sl.notifOk = false; Android._permAsked = []; slLoad(); });
    await pg.click('#sl-rem-on'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => !Android._sl.set.remindAt && Android._permAsked.includes('notif')), 'herinnering zonder meldingen: eerst toestemming vragen');
    await pg.evaluate(() => { Android._sl.notifOk = true; slLoad(); });
    await pg.click('#sl-rem-on'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.remindAt === '22:30') && await pg.isVisible('#sl-rem') && (await pg.textContent('#sl-rem-n')).includes('22:30'), 'herinnering aan om 22:30');
    await pg.fill('#sl-rem', '23:15'); await pg.dispatchEvent('#sl-rem', 'change'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.remindAt === '23:15'), 'herinnering om 23:15');
    // Zoeken vindt wat je zei, en opent die nacht bij dat stukje
    await pg.evaluate(() => { const S = Android._sl; S.nights.push({ id: '1700000000000', start: 1700000000000, end: 1700020000000, clips: [{ id: 'c1700000100000', t: 1700000100000, dur: 3, audio: true, done: true, kind: 'praten', text: 'De sleutels liggen onder de mat' }] }); });
    await pg.evaluate(() => { show('search'); }); await pg.fill('#gs-q', 'sleutels'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(700);
    ok(await pg.isVisible('#gs-res .gi[data-k=sleep]') && (await pg.textContent('#gs-res')).includes('onder de mat'), 'Zoeken: Slaappraat-groep');
    await pg.click('#gs-res .gi[data-k=sleep]'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'sleepnight' && slNightId === '1700000000000') && await pg.isVisible('#sln-list [data-clip="c1700000100000"]'), 'treffer opent de nacht bij dat stukje');
    // Vanuit de assistent: naar het stukje op tijd; een onleesbaar stukje krijgt een eigen regel
    await pg.evaluate(() => { const n = Android._sl.nights.find(x => x.id === '1700000000000'); n.clips.push({ id: 'c1700000200000', t: 1700000200000, dur: 2, audio: true, done: true, kind: 'onleesbaar', text: '' }); goBack(); });
    await pg.waitForTimeout(100);
    await pg.evaluate(() => { slFilterV = 'alles'; slNightId = '1700000000000'; slFocusClip = 't:1700000200000'; jumpTo('sleepnight'); slFilterV = 'alles'; slnRender(); });
    await pg.waitForTimeout(200);
    ok((await pg.textContent('#sln-list')).includes('Kon dit stukje niet lezen'), 'onleesbaar stukje');
    // Zoeken vindt het scherm
    await pg.evaluate(() => { show('search'); }); await pg.fill('#gs-q', 'slaap'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(400);
    ok((await pg.textContent('#gs-res')).includes('Slaappraat'), 'te vinden bij Zoeken');
    // Meteen uitschrijven: standaard aan, met eerlijke uitleg (accu, warmte)
    await pg.evaluate(() => { show('sleep'); Android._sl.set.keepNoise = false; Android._sl.set.transcribe = true; Android._sl.directWait = ''; slLoad(); slRender(); }); await pg.waitForTimeout(150);
    ok((await pg.getAttribute('#sl-direct', 'aria-checked')) === 'true' && await pg.isVisible('#sl-direct') && (await pg.textContent('#sl-direct-n')).includes('40%') && (await pg.textContent('#sl-direct-n')).includes('warm'), 'meteen uitschrijven: standaard aan, met uitleg over accu en warmte');
    // Terwijl hij luistert: wat je zei staat er meteen, geluid zonder woorden is meteen weg
    await pg.click('#sl-btn'); await pg.waitForTimeout(600);
    await pg.evaluate(() => { Android._slHear('Waar zijn de sleutels', 3); Android._slHear('', 40); Android._slHear('', 50); }); await pg.waitForTimeout(1200);
    const st1 = await pg.textContent('#sl-status');
    ok(st1.includes('1 keer gepraat · 2 geluiden') && st1.includes('Waar zijn de sleutels'), 'luistert: live wat er al uitgeschreven is en het laatste wat je zei');
    const row1 = await pg.textContent('#sl-nights .strow:first-child');
    ok(row1.includes('Bezig… · 1 keer gepraat · 2 geluiden') && row1.includes('Waar zijn de sleutels'), 'de nacht onder Bezig…: tellers en het laatste wat je zei');
    ok(await pg.evaluate(() => { const n = Android._sl.nights.find(x => x.id === Android._sl.night); return n.clips.filter(k => k.kind === 'geluid').every(k => !k.audio && k.done); }), 'geluid zonder woorden: het geluid is meteen weg (telt niet meer mee)');
    await pg.screenshot({ path: __dirname + '/shots/slaappraat-meteen-' + scheme + '.png', fullPage: true });
    await pg.click('#sl-nights .strow:first-child'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#sln-tx')).includes('verschijnt hier') && (await pg.textContent('#sln-list')).includes('Waar zijn de sleutels'), 'de nacht terwijl hij luistert: de tekst staat er al');
    await pg.click('#sln-filter button[data-v=alles]'); await pg.waitForTimeout(80);
    ok((await pg.$$('#sln-list .sl-clip:has-text("Geluid (geen woorden)")')).length === 2 && (await pg.$$('#sln-list .sl-clip:has-text("Geluid") button[aria-label^="Afspelen"]')).length === 0, 'geluiden: zonder geluid, niet af te spelen');
    await pg.evaluate(() => show('sleep')); await pg.waitForTimeout(100);
    // Zonder lader en weinig accu: wacht, en zegt waarom
    await pg.evaluate(() => { Android._sl.directWait = 'accu onder 40% en niet aan de lader'; Android._slHear('Niet vergeten', 3); Android._slHear('', 20);
      Android._slHear('', 30, { audio: false, full: true, why: 'max' }); Android._slHear('', 30, { audio: false, full: true, why: 'plaats' }); }); await pg.waitForTimeout(1200);
    const st2 = await pg.textContent('#sl-status');
    ok(st2.includes('wacht: accu onder 40%') && st2.includes('2 nog uit te schrijven') && st2.includes('na het luisteren'), 'wacht (accu): zegt waarom, en dat de rest na het luisteren komt');
    // Stoppen: alleen wat er nog over is wordt uitgeschreven
    await pg.evaluate(() => { Android._sl.txMs = 1500; });
    await pg.click('#sl-btn'); await pg.waitForTimeout(800);
    ok(await pg.evaluate(() => Android._sl.phase === 'uitschrijven') && (await pg.textContent('#sl-status')).includes('Uitschrijven… 0 van 2'), 'na het stoppen: alleen de rest (2) uitschrijven');
    await pg.waitForTimeout(1500);
    ok(await pg.evaluate(() => Android._sl.phase === '' && Android._sl.txLast === 2) && (await pg.textContent('#sl-nights .strow:first-child')).includes('2 keer gepraat'), 'klaar: de rest is uitgeschreven');
    await pg.evaluate(() => { Android._sl.txMs = 0; Android._sl.directWait = ''; });
    await pg.click('#sl-nights .strow:first-child'); await pg.waitForTimeout(200);
    await pg.click('#sln-filter button[data-v=alles]'); await pg.waitForTimeout(80);
    const lst = await pg.textContent('#sln-list');
    ok(lst.includes('maximum aan geluid voor deze nacht') && lst.includes('plaats te maken'), 'maximum bereikt: uitleg met de nieuwe regels');
    await pg.evaluate(() => show('sleep')); await pg.waitForTimeout(100);
    // Uitzetten, en verborgen als er niet uitgeschreven wordt
    await pg.click('#sl-direct'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.direct === false) && (await pg.getAttribute('#sl-direct', 'aria-checked')) === 'false' && (await pg.textContent('#sl-direct-n')).startsWith('Uit'), 'meteen uitschrijven uit');
    await pg.click('#sl-btn'); await pg.waitForTimeout(600);
    await pg.evaluate(() => { Android._slHear('Pas morgen', 3); }); await pg.waitForTimeout(1200);
    ok(await pg.evaluate(() => { const n = Android._sl.nights.find(x => x.id === Android._sl.night); return n.clips.every(k => !k.done); }) && !(await pg.textContent('#sl-status')).includes('gepraat'), 'uit: tijdens het luisteren niets uitgeschreven');
    await pg.click('#sl-btn'); await pg.waitForTimeout(800);
    await pg.click('#sl-direct'); await pg.waitForTimeout(60);
    await pg.evaluate(() => { Android._sl.set.transcribe = false; slLoad(); slRender(); });
    ok(!(await pg.isVisible('#sl-directrow')), 'daarna uitschrijven uit: meteen uitschrijven niet te zien');
    await pg.evaluate(() => { Android._sl.set.transcribe = true; slLoad(); slRender(); });
    ok(await pg.evaluate(() => Android._sl.set.direct === true) && await pg.isVisible('#sl-directrow'), 'weer aan');
    // Geluiden bewaren aan: ook na meteen uitschrijven blijft het geluid (af te spelen), en de ruimte is te zien
    await pg.evaluate(() => { Android._sl.set.keepNoise = true; slLoad(); slRender(); });
    await pg.click('#sl-btn'); await pg.waitForTimeout(600);
    await pg.evaluate(() => { Android._slHear('', 50); Android._slHear('Hallo', 3); }); await pg.waitForTimeout(1200);
    ok(await pg.evaluate(() => { const n = Android._sl.nights.find(x => x.id === Android._sl.night); const g = n.clips.find(k => k.kind === 'geluid'); return g && g.done && g.audio; }), 'geluiden bewaren: uitgeschreven geluid houdt zijn geluid');
    ok(/\d+ kB|\d+ MB/.test(await pg.textContent('#sl-nights .strow:first-child')), 'grootte bij de nacht');
    await pg.click('#sl-btn'); await pg.waitForTimeout(800);
    ok((await pg.textContent('#sl-size')).includes('Slaappraat gebruikt') && (await pg.textContent('#sl-size')).includes('nachten'), 'totale grootte op het scherm');
    await pg.evaluate(() => { Android._sl.set.keepNoise = false; });
    // Meer instellingen
    await pg.click('#sl-more'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'sleepset') && (await pg.textContent('#s-sleepset h1')) === 'Slaappraat-instellingen', 'meer instellingen: eigen scherm');
    const chk = id => pg.evaluate(i => (document.querySelector('#' + i + ' button[aria-checked=true]') || {}).dataset ? document.querySelector('#' + i + ' button[aria-checked=true]').dataset.v : null, id);
    ok((await chk('sls-when')) === 'accu' && (await chk('sls-dbat')) === '40' && (await chk('sls-max')) === '8' && (await chk('sls-full')) === 'geluid' && (await chk('sls-free')) === '500' && (await chk('sls-fmt')) === 'aac'
      && (await chk('sls-clip')) === '60' && (await chk('sls-hang')) === '1500' && (await chk('sls-pre')) === '2' && (await chk('sls-stop')) === '15' && (await chk('sls-model')) === '', 'standaard: zoals voorheen (60 s, 1,5 s, 2 s, 15%) en 8 uur geluid');
    ok((await pg.textContent('#sls-size')).includes('Nu:') && (await pg.textContent('#sls-max-n')).includes('115 MB') && (await pg.textContent('#sls-full-n')).includes('Gepraat gaat nooit weg'), 'opslag: grootte en eerlijke uitleg');
    await pg.click('#sls-when button[data-v=lader]'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.directWhen === 'lader') && !(await pg.isVisible('#sls-dbatrow')) && (await pg.textContent('#sls-when-n')).includes('aan de lader'), 'alleen aan de lader: accudrempel niet nodig');
    await pg.click('#sls-when button[data-v=accu]'); await pg.click('#sls-dbat button[data-v="60"]'); await pg.waitForTimeout(60);
    await pg.click('#sls-max button[data-v="0"]'); await pg.click('#sls-full button[data-v=oudste]'); await pg.click('#sls-free button[data-v="1000"]'); await pg.click('#sls-fmt button[data-v=wav]');
    await pg.click('#sls-clip button[data-v="120"]'); await pg.click('#sls-hang button[data-v="3000"]'); await pg.click('#sls-pre button[data-v="5"]'); await pg.click('#sls-stop button[data-v="20"]'); await pg.click('#sls-model button[data-v=small]'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => { const S = Android._sl.set; return S.directWhen === 'accu' && S.directBat === 60 && S.maxAudio === 0 && S.whenFull === 'oudste' && S.minFree === 1000 && S.format === 'wav' && S.clipMax === 120 && S.hang === 3000 && S.pre === 5 && S.stopBat === 20 && S.txModel === 'small'; }), 'alles instelbaar');
    ok((await pg.textContent('#sls-max-n')).includes('Onbeperkt') && (await pg.textContent('#sls-full-n')).includes('ook van gepraat') && (await pg.textContent('#sls-fmt-n')).includes('115 MB') && (await pg.textContent('#sls-model-n')).includes('nog niet gedownload'), 'uitleg past zich aan');
    ok(await pg.evaluate(() => Android.sleepSet('maxAudio', '3') !== '' && Android.sleepSet('whenFull', 'alles') !== '' && Android.sleepSet('hang', 'x') !== '' && Android.sleepSet('txModel', 'groot') !== '' && Android._sl.set.maxAudio === 0), 'alleen vaste keuzes');
    await pg.evaluate(() => show('sleep')); await pg.waitForTimeout(100);
    ok((await pg.textContent('#sl-direct-n')).includes('vanaf 60% accu'), 'hoofdscherm: meteen uitschrijven noemt de gekozen drempel');
    await pg.click('#sl-more'); await pg.waitForTimeout(150);
    await pg.click('#sls-reset'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => { const S = Android._sl.set; return S.directBat === 40 && S.maxAudio === 8 && S.whenFull === 'geluid' && S.format === 'aac' && S.clipMax === 60 && S.txModel === '' && S.keep === 30; }) && (await chk('sls-max')) === '8', 'terug naar standaard (nachten bewaren blijft)');
    await pg.screenshot({ path: __dirname + '/shots/slaappraat-instellingen-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'sleep'), 'terug naar Slaappraat');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
