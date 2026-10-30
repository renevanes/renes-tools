// Apparaten: tegel, Bluetooth-lijst (groepen, gekoppeld, tracker), detail met zoeken (warmer/kouder, toon, trillen),
// bijnaam (mijn apparaat), wifi-netwerken met drukte, apparaten op mijn wifi met "nieuw", trackers met de waarschuwing
// (vanuit de melding, "van mij", negeren), bekend/vaak in de buurt, instellingen en toestemmingen. Licht en donker.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const tag = ' (' + scheme + ')';
    ok(await pg.isVisible('#tile-apparaten') && (await pg.textContent('#tile-apparaten')).includes('Apparaten'), 'tegel op het startscherm' + tag);
    ok(await pg.evaluate(() => homeCfg().groups.find(g => g.name === 'Handig').tools.includes('apparaten') && TOOLS.apparaten[0] === 'Apparaten'), 'in de groep Handig en als snelkoppeling');

    // ---------- toestemming in context ----------
    await pg.evaluate(() => { Android._ap.perms.bt = false; Android._ap.perms.wifi = false; store.set('apTab', 'bt'); apTabNow = 'bt'; });
    await pg.click('#tile-apparaten'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'apparaten' && !!document.querySelector('#s-apparaten .hdr h1')), 'scherm Apparaten met kop');
    ok(await pg.isVisible('#ap-perm') && (await pg.textContent('#ap-perm')).includes('Apparaten in de buurt') && (await pg.textContent('#ap-perm')).includes('niets naar internet'), 'uitleg toestemming (Nederlands, met waarom)');
    await pg.click('#ap-perm button'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._ap.asked.includes('nearby') && Android._ap.perms.bt) && !(await pg.isVisible('#ap-perm')), 'toestaan: gevraagd en de uitleg is weg');
    await pg.evaluate(() => { Android._ap.perms.btOn = false; onApChanged(); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ap-perm')).includes('Bluetooth staat uit'), 'Bluetooth uit: uitleg met knop');
    await pg.click('#ap-perm button'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._ap.opened.includes('bt')) && !(await pg.isVisible('#ap-perm')), 'Bluetooth aanzetten via Android');

    // ---------- Bluetooth ----------
    await pg.click('#ap-bt-go'); await pg.waitForTimeout(1300);
    const bt = await pg.textContent('#ap-bt-list');
    ok(bt.includes('Galaxy Buds2 Pro') && bt.includes('JBL Flip 5') && bt.includes('6× Apple') && bt.includes('AirTag'), 'lijst: oortjes, speaker, groep van 6 Apple-apparaten, AirTag');
    ok((await pg.textContent('#ap-bt-list .ap-row >> nth=0')).includes('AirTag'), 'tracker bovenaan');
    ok(bt.includes('ongeveer') && bt.includes('gekoppeld') && bt.includes('wisselend adres'), 'afstand "ongeveer", gekoppeld en wisselend adres');
    ok((await pg.textContent('#ap-bt-paired')).includes('Momentum 4') && (await pg.textContent('#ap-bt-paired')).includes('niet in de buurt'), 'gekoppeld maar niet in de buurt');
    ok((await pg.textContent('#ap-p-bt .ap-foot')).includes('wisselen') && (await pg.textContent('#ap-p-bt .ap-foot')).includes('ongeveer'), 'eerlijk over wisselende adressen en de afstand');
    ok(await pg.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'niet breder dan het scherm');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#ap-bt-list .ap-row')].every(b => b.tagName === 'BUTTON' && !b.getAttribute('role') && b.parentElement.getAttribute('role') === 'listitem')), 'rijen: knop binnen een lijstitem (toegankelijk)');

    // Groep: geen bijnaam mogelijk
    await pg.click('#ap-bt-list .ap-row[data-k="g:76|apple"]'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'apdev') && (await pg.textContent('#apd-acts')).includes('niet terug te herkennen') && !(await pg.isVisible('#apd-find')), 'groep: niet terug te herkennen, niet te zoeken');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);

    // ---------- detail en zoeken ----------
    await pg.click('#ap-bt-list .ap-row[data-k="a:F4:12:9A:00:11:22"]'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#apd-h')) === 'Galaxy Buds2 Pro' && (await pg.textContent('#apd-info')).includes('Samsung') && (await pg.textContent('#apd-info')).includes('Oortjes'), 'detail: naam, soort, maker');
    ok(await pg.evaluate(() => document.querySelector('#s-apdev .hdr').classList.contains('sm')), 'detailscherm met kleine kop');
    await pg.click('#apd-go'); await pg.waitForTimeout(1500);
    const tr = await pg.textContent('#apd-trend');
    ok(tr.includes('Warmer') || tr.includes('dichtbij'), 'zoeken: warmer');
    ok(+(await pg.getAttribute('#apd-meter', 'aria-valuenow')) > 20 && (await pg.textContent('#apd-unit')).includes('ongeveer'), 'meter loopt op, afstand ongeveer');
    ok((await pg.getAttribute('#apd-spark-p', 'd')).startsWith('M'), 'verloop getekend');
    await pg.click('#apd-tone'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => store.get('apTone') === true && $('#apd-tone').getAttribute('aria-checked') === 'true'), 'toon aan');
    await pg.click('#apd-vib'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => store.get('apVib') === false), 'trillen uit');
    await pg.click('#apd-go'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !apFinding && Android._ap.find === null && (apBeepT === null)), 'stoppen: zoeken en toon gestopt');
    await pg.evaluate(() => { Android._ap.findGone = true; }); await pg.click('#apd-go'); await pg.waitForTimeout(1000);
    ok((await pg.textContent('#apd-unit')).includes('Even niet gezien') && (await pg.textContent('#apd-trend')).includes('Kouder'), 'uit beeld: kouder');
    await pg.evaluate(() => { Android._ap.findGone = false; });
    // Bijnaam
    await pg.click('#apd-acts button'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.inputValue('#modal-input')) === 'Galaxy Buds2 Pro', 'bijnaam vragen');
    await pg.fill('#modal-input', 'Mijn oortjes'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ap.known['a:F4:12:9A:00:11:22'].nick === 'Mijn oortjes'), 'bijnaam bewaard');
    ok((await pg.textContent('#apd-h')) === 'Mijn oortjes' && (await pg.textContent('#apd-acts')).includes('Bijnaam wijzigen'), 'detail toont de bijnaam');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'apparaten' && Android._ap.find === null && !apFinding), 'terug: zoeken gestopt');
    ok((await pg.textContent('#ap-bt-list')).includes('Mijn oortjes') && (await pg.textContent('#ap-bt-list')).includes('Mijn apparaat'), 'lijst: bijnaam en "Mijn apparaat"');

    // ---------- wifi-netwerken ----------
    await pg.click('#ap-t-wifi'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ap-perm')).includes('wifi-netwerken') || !(await pg.isVisible('#ap-perm')), 'wifi: uitleg toestemming als die mist');
    await pg.evaluate(() => { Android._ap.perms.wifi = true; onApChanged(); }); await pg.waitForTimeout(100);
    await pg.click('#ap-wifi-go'); await pg.waitForTimeout(1300);
    const wl = await pg.textContent('#ap-wifi-list');
    ok(wl.includes('Thuis') && wl.includes('Verbonden') && wl.includes('2 toegangspunten') && wl.includes('2,4 GHz en 5 GHz'), 'eigen netwerk: verbonden, twee banden');
    ok(wl.includes('onveilig: open') && wl.includes('onveilig (WEP)') && wl.includes('Verborgen netwerk') && wl.includes('AVM (FRITZ!Box)'), 'open en WEP onveilig, verborgen netwerk, maker');
    ok(await pg.isVisible('#ap-crowd-card') && (await pg.$$('#ap-crowd .ap-bars')).length === 2 && (await pg.textContent('#ap-crowd-tip')).includes('kanaal 11'), 'drukte per kanaal, rustigste kanaal');
    await pg.click('#ap-wifi-go'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ap-wifi-st')).includes('4 keer per 2 minuten'), 'Android-beperking uitgelegd, niet opnieuw gevraagd');

    // ---------- op mijn wifi ----------
    await pg.evaluate(() => { Android._ap.noWifi = true; }); await pg.click('#ap-t-lan'); await pg.waitForTimeout(100);
    await pg.click('#ap-lan-go'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ap-lan-st')).includes('nooit via mobiele data'), 'zonder wifi: niet zoeken (nooit via mobiel)');
    await pg.evaluate(() => { Android._ap.noWifi = false; Android._ap.ssid = 'Hotel Gast'; }); await pg.click('#ap-lan-go'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal-text')).includes('Hotel Gast') && (await pg.textContent('#modal-text')).includes('toestemming'), 'onbekend netwerk: eerst vragen');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !Android._ap.lanBusy), 'niet gevraagd = niet gezocht');
    await pg.evaluate(() => { Android._ap.ssid = 'Thuis'; Android._ap.set.homeSsid = 'Thuis'; }); await pg.click('#ap-lan-go'); await pg.waitForTimeout(250);
    ok(!(await pg.isVisible('#modal.on')), 'je wifi thuis: meteen zoeken');
    await pg.evaluate(() => { Android._ap.set.homeSsid = ''; });
    ok(await pg.isVisible('#ap-lan-prog') && (await pg.textContent('#ap-lan-go')).includes('Stoppen'), 'voortgang en stopknop');
    await pg.waitForTimeout(1200);
    const ll = await pg.textContent('#ap-lan-list');
    ok(ll.includes('FRITZ!Box 7590') && ll.includes('Woonkamer') && ll.includes('HP ENVY 6000') && ll.includes('NAS-ZOLDER') && ll.includes('192.168.1.77'), 'router, Chromecast, printer, NAS en een onbekend adres');
    ok((await pg.textContent('#ap-lan-list .ap-row[data-k="l:b:nas-zolder"]')).includes('nieuw') && (await pg.textContent('#ap-lan-st')).includes('1 nieuw'), 'nieuw apparaat valt op');
    ok((await pg.textContent('#ap-lan-foot')).includes('Android vanaf versie 10 verborgen') && (await pg.textContent('#ap-lan-foot')).includes('192.168.1.37/24'), 'eerlijk over MAC-adressen, eigen subnet genoemd');
    await pg.click('#ap-lan-list .ap-row[data-k="l:b:nas-zolder"]'); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#apd-find')) && (await pg.textContent('#apd-info')).includes('Gedeelde map') && (await pg.textContent('#apd-info')).includes('192.168.1.40'), 'detail op het netwerk: diensten, IP, geen Bluetooth-zoeken');
    await pg.click('#apd-acts button'); await pg.waitForTimeout(100); await pg.fill('#modal-input', 'NAS zolder'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ap.known['l:b:nas-zolder'].nick === 'NAS zolder'), 'mijn apparaat op het netwerk');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150); await pg.evaluate(() => apLanPoll()); await pg.waitForTimeout(100);
    ok(!(await pg.textContent('#ap-lan-list .ap-row[data-k="l:b:nas-zolder"]')).includes('nieuw') && (await pg.textContent('#ap-lan-list')).includes('NAS zolder'), 'eigen apparaat: niet meer nieuw');
    await pg.click('#ap-lan-list .ap-row[data-k="l:i:192.168.1.77"]'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#apd-info')).includes('kan veranderen'), 'apparaat zonder naam: herkend aan het IP-adres (eerlijk)');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);

    // ---------- trackers ----------
    await pg.click('#ap-t-tr'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ap-tr-state')).includes('achtergrond staat uit'), 'achtergrond uit: uitleg met knop Instellen');
    await pg.evaluate(() => { Android._apTrackerDemo(); Android._ap.set.bg = true; apLoad(); apTab('tr'); }); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ap-tr-state')).includes('Aan'), 'trackerwaarschuwing aan');
    ok((await pg.textContent('#ap-tr-list')).includes('reist mee: 3 plekken, 42 minuten') && (await pg.textContent('#ap-tr-list')).includes('let op'), 'meereizende tracker in de lijst');
    // Vanuit de melding: openTool("apparaten:zoek:…") opent het detail en begint te zoeken
    await pg.evaluate(() => { show('home'); openTool('apparaten:zoek:C2:7E:01:02:03:04'); }); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => current === 'apdev' && apFinding && Android._ap.find && Android._ap.find.key === 'a:C2:7E:01:02:03:04'), 'melding → zoeken naar de tracker');
    const tc = await pg.textContent('#apd-tracker');
    ok(tc.includes('Onbekende AirTag') && tc.includes('42 minuten') && tc.includes('NFC') && tc.includes('batterij') && tc.includes('112') && tc.includes('Meldingen over onbekende trackers'), 'wat je kunt doen (Apple- en Google-advies, politie)');
    await pg.click('#apd-tracker button:has-text("Negeren")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ap.trackers['C2:7E:01:02:03:04'].status === 'ignored') && (await pg.textContent('#apd-tracker')).includes('genegeerd'), 'negeren');
    await pg.click('#apd-tracker button:has-text("Weer waarschuwen")'); await pg.waitForTimeout(150);
    await pg.click('#apd-tracker button:has-text("Dit is van mij")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ap.trackers['C2:7E:01:02:03:04'].status === 'mine' && !!Android._ap.known['a:C2:7E:01:02:03:04']), 'dit is van mij: bewaard als eigen tracker');
    ok((await pg.textContent('#toast')).includes('nieuw adres'), 'eerlijk: na een nieuw adres kan er weer een waarschuwing komen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !apFinding), 'terug: zoeken gestopt');
    ok((await pg.textContent('#ap-tr-list')).includes('van jou'), 'lijst: van jou');

    // ---------- bekend en vaak in de buurt ----------
    await pg.click('#ap-t-known'); await pg.waitForTimeout(150);
    const kn = await pg.textContent('#ap-p-known');
    ok(kn.includes('Mijn oortjes') && kn.includes('laatst gezien') && kn.includes('±1 km') && kn.includes('NAS zolder'), 'mijn apparaten met waar ze het laatst waren (grof)');
    ok(kn.includes('Sonos Move') && kn.includes('op 18 dagen') && kn.includes('Fietscomputer'), 'vaak in de buurt, thuis en elders');
    ok(kn.includes('vaste naam') && kn.includes('op je telefoon'), 'eerlijk over wat herkend kan worden');

    // ---------- instellingen ----------
    await pg.click('#ap-set-btn'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'apset') && (await pg.textContent('#aps-bg-how')).includes('geen dienst met een vaste melding'), 'instellingen: uitleg over de achtergrond');
    await pg.evaluate(() => { Android._ap.set.bg = false; Android._ap.perms.bg = false; Android._ap.asked = []; apsRender(); });
    await pg.click('#aps-bg'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._ap.set.bg === true && Android._ap.asked.includes('bgloc')), 'achtergrond aan: vraagt pas dan om "locatie altijd"');
    await pg.click('#aps-every [data-v="60"]'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ap.set.every === 60 && $('#aps-every [data-v="60"]').getAttribute('aria-checked') === 'true'), 'elk uur');
    await pg.click('#aps-keep [data-v="7"]'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal-text')).includes('gewist'), 'korter bewaren: eerst bevestigen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ap.set.keep === 7), 'bewaren: 1 week');
    await pg.click('#aps-away'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ap.set.away === true), 'alleen buiten huis');
    await pg.click('#aps-home'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#aps-home-st')).includes('Thuis is ingesteld') && (await pg.textContent('#aps-home-st')).includes('Thuis'), 'thuis ingesteld (met de wifi)');
    const perms = await pg.textContent('#aps-perms');
    ok(perms.includes('Apparaten in de buurt') && perms.includes('Locatie altijd') && perms.includes('Meldingen'), 'toestemmingen met waarvoor');
    await pg.click('#aps-tr'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ap.set.trackers === false), 'trackerwaarschuwing uit');
    await pg.click('#aps-tr'); await pg.waitForTimeout(100);
    // Alles wissen
    await pg.click('#s-apset button:has-text("Alles wissen")'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#modal.on'), 'wissen: eerst bevestigen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ap.wiped === true && Object.keys(Android._ap.known).length === 0 && !Android._ap.set.home), 'alles gewist, ook bijnamen en thuis');
    // Het register
    ok(await pg.evaluate(() => ['apparaten.achtergrond', 'apparaten.vaak', 'apparaten.trackers', 'apparaten.geschiedenis', 'apparaten.buitenhuis', 'apparaten.bewaren', 'apparaten.toon', 'apparaten.trillen'].every(id => !!setEntry(id))), 'alle instellingen in het register');
    ok(await pg.evaluate(() => setSafe(setEntry('apparaten.trackers'), 'uit') && !setSafe(setEntry('apparaten.trackers'), 'aan')), 'trackerwaarschuwing uitzetten: alleen zelf');
    ok(await pg.evaluate(() => setSafe(setEntry('apparaten.achtergrond'), 'uit') && !setSafe(setEntry('apparaten.achtergrond'), 'aan')), 'achtergrond uitzetten (dan ook geen trackerwaarschuwing): alleen zelf');
    // Alleen geschatte locatie: uitleg dat het Precies moet zijn
    await pg.evaluate(() => { Android._ap.perms.bt = false; Android._ap.perms.fine = false; show('apparaten'); apTab('bt'); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ap-perm')).includes('Precies') && (await pg.textContent('#ap-perm')).includes('geschatte locatie'), 'alleen geschatte locatie: uitleg Precies');
    await pg.evaluate(() => { Android._ap.perms.bt = true; Android._ap.perms.fine = true; onApChanged(); });
    // Weg van Apparaten: alles stopt
    await pg.evaluate(() => { show('apparaten'); apTab('bt'); apBtGo(); }); await pg.waitForTimeout(200);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !apPollT && !Android._ap.scanning), 'naar het startscherm: zoeken gestopt');
    // Contrast van de knoppen en lijsten (kleurrollen, geen vaste kleuren)
    await pg.evaluate(() => { show('apparaten'); apTab('bt'); });
    const contrast = await pg.evaluate(() => {
      const lum = c => { const m = c.match(/\d+(\.\d+)?/g).map(Number); const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; return .2126 * f(m[0]) + .7152 * f(m[1]) + .0722 * f(m[2]); };
      const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
      const t = document.querySelector('#ap-tabs [aria-selected=true]'), cs = getComputedStyle(t);
      return ratio(cs.color, cs.backgroundColor);
    });
    ok(contrast >= 4.5, 'gekozen tab leesbaar (contrast ' + contrast.toFixed(1) + ')');
    await pg.screenshot({ path: require('path').join(__dirname, 'shots', 'apparaten-' + scheme + '.png') });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
