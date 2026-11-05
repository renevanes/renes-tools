// Tools-overzicht (startscherm van de app), indeling 2: "Handig" verdeeld over Documenten & geld, Dag & gezondheid,
// Onderweg en Slim, geen losse groep Telefoon; de overstap houdt Rene's eigen indeling precies zoals hij is (homeMigrate,
// zuiver getest en van begin tot eind), nieuwe tools gaan naar hun eigen standaardgroep; rij "Recent gebruikt" (volgorde,
// hooguit vier, uit te zetten, blijft na herladen); de eerste tegel hoog genoeg; Play-versie en losse app; Engels; licht en donker.
const { chromium } = require('playwright');
const path = require('path');
const URL = 'file://' + path.join(__dirname, 'ui', 'index.html');
const NIEUW = ['Bellen & contacten', 'Berichten & backup', 'Muziek & radio', 'Documenten & geld', 'Dag & gezondheid', 'Onderweg', 'Slim'];
// Zoals de app (versie 1.149) de standaard bewaarde zodra Rene iets aan de weergave veranderde (bijv. compact of een favoriet)
const OUD = { grouped: true, compact: true, hidden: [], fav: ['notes'], dash: false, groups: [
  { name: 'Telefoon', tools: ['skin'] },
  { name: 'Bellen & contacten', tools: ['redial', 'calls', 'contacts', 'recorder', 'transcripts'] },
  { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'] },
  { name: 'Muziek & radio', tools: ['radio', 'podcasts', 'spotify', 'music'] },
  { name: 'Handig', tools: ['ai', 'notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto', 'sleep', 'checkin', 'apparaten', 'facturen', 'bank', 'masterdata', 'gebruik'] }] };
// Een eigen indeling: groepen omgedraaid, een tool verplaatst, een eigen groep, iets verborgen; Bankzaken en Gebruik ontbreken nog
const EIGEN = { grouped: true, hidden: ['music'], fav: [], groups: [
  { name: 'Handig', tools: ['notes', 'ai', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto', 'sleep', 'checkin', 'apparaten', 'facturen', 'masterdata'] },
  { name: 'Mijn muziek', tools: ['radio', 'podcasts', 'spotify', 'music', 'skin'] },
  { name: 'Bellen & contacten', tools: ['calls', 'redial', 'contacts', 'recorder', 'transcripts'] },
  { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'] }] };

(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    let pg = await ctx.newPage();
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m + ' (' + scheme + ')'); else console.log('✓ ' + m); };
    await pg.goto(URL); await pg.waitForTimeout(300);
    const home = p => p.$$eval('#home-groups .hgroup', gs => gs.map(g => ((g.querySelector('h3') || {}).textContent || '') + ':' + [...g.querySelectorAll('.tiles > .tile')].map(t => t.id.replace('tile-', '')).join(',')));

    // ---------- de nieuwe standaardgroepen ----------
    let g = await home(pg);
    ok(g.map(x => x.split(':')[0]).join('|') === NIEUW.join('|'), 'nieuwe groepen: ' + g.map(x => x.split(':')[0]).join(', '));
    ok(g.includes('Documenten & geld:pdf,kluis,facturen,bank,masterdata') && g.includes('Dag & gezondheid:checkin,sleep,gebruik') &&
      g.includes('Onderweg:tracks,car,loc,vpn') && g.includes('Slim:ai,notes,auto,apparaten,skin'), 'Handig verdeeld, skin in Slim');
    ok(await pg.evaluate(() => { const all = [].concat(...HOME_DEFAULT.groups.map(x => x.tools)); return Object.values(TILE_TOOL).every(tk => all.filter(x => x === tk).length === 1) && all.length === Object.keys(TILE_TOOL).length; }), 'elke tool staat precies één keer in de standaardindeling');

    // ---------- homeMigrate: zuiver ----------
    const u = await pg.evaluate(([OUD, EIGEN]) => {
      const K = Object.keys(TOOL_TILE), names = c => c.groups.map(x => x.name).join('|'), cp = o => JSON.parse(JSON.stringify(o)), r = {};
      const nieuw = HOME_DEFAULT.groups.map(x => x.name + ':' + x.tools).join('|'), lay = c => c.groups.map(x => x.name + ':' + x.tools).join('|');
      r.leeg = lay(homeMigrate(null, K)) === nieuw && homeMigrate(null, K).v === 2;
      const m = homeMigrate(cp(OUD), K);
      r.oud = lay(m) === nieuw && m.compact === true && m.fav.join() === 'notes' && m.dash === false && m.v === 2 && !m.hidden.length;
      r.oudZuiver = JSON.stringify(OUD) === JSON.stringify(OUD) && (() => { const o = cp(OUD); homeMigrate(o, K); return JSON.stringify(o) === JSON.stringify(OUD); })();
      // Ook oude standaarden van eerdere versies (zonder Telefoon, met wat de app er toen vanzelf achteraan zette)
      const vroeg = { grouped: true, hidden: [], groups: [{ name: 'Bellen & contacten', tools: ['redial', 'calls', 'contacts', 'transcripts', 'recorder'] }, { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'] },
        { name: 'Muziek & radio', tools: ['radio', 'music', 'podcasts', 'spotify'] }, { name: 'Handig', tools: ['notes', 'tracks', 'auto', 'car', 'pdf', 'kluis'] }] };
      r.vroeg = lay(homeMigrate(vroeg, K)) === nieuw;
      const half = cp(OUD); half.groups[4].tools = half.groups[4].tools.slice(0, 12); half.groups.shift();
      r.half = lay(homeMigrate(half, K)) === nieuw;
      // Zelf aangepast: blijft precies zo
      const keep = c => { const x = homeMigrate(cp(c), K); return lay(x) === lay(c) && names(x) === names(c); };
      const swap = cp(OUD); swap.groups[1].tools = ['calls', 'redial', 'contacts', 'recorder', 'transcripts'];
      const hid = cp(OUD); hid.hidden = ['sms'];
      const ren = cp(OUD); ren.groups[3].name = 'Muziek';
      const mov = cp(OUD); mov.groups[4].tools = mov.groups[4].tools.filter(x => x !== 'pdf'); mov.groups[2].tools.push('pdf');
      const ord = cp(OUD); ord.groups.splice(1, 2, ord.groups[2], ord.groups[1]);
      const tail = cp(OUD); tail.groups[4].tools = ['ai', 'notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto', 'checkin', 'sleep', 'apparaten', 'facturen', 'bank', 'masterdata', 'gebruik'];
      const v2 = cp(OUD); v2.v = 2;
      r.eigen = { swap: keep(swap), verborgen: keep(hid) && homeMigrate(cp(hid), K).hidden.join() === 'sms', hernoemd: keep(ren), verplaatst: keep(mov), groepvolgorde: keep(ord), staart: keep(tail), al2: keep(v2) };
      // Nieuwe tools in een eigen indeling: naar hun standaardgroep (zelfde naam, anders waar de meeste uit die groep staan, anders een nieuwe groep)
      const e = homeMigrate(cp(EIGEN), K), grp = n => (e.groups.find(x => x.name === n) || { tools: [] }).tools;
      r.nieuweTools = grp('Handig').slice(-2).join() === 'bank,gebruik' && e.groups.length === 4 && e.hidden.join() === 'music' &&
        grp('Bellen & contacten').join() === 'calls,redial,contacts,recorder,transcripts' && grp('Mijn muziek').join() === 'radio,podcasts,spotify,music,skin';
      const metNaam = homeMigrate({ groups: [{ name: 'Alles', tools: K.filter(x => x !== 'bank' && x !== 'kluis') }, { name: 'Documenten & geld', tools: ['kluis'] }, { name: 'Laatste', tools: [] }] }, K);
      r.zelfdeNaam = metNaam.groups[1].tools.join() === 'kluis,bank' && !metNaam.groups[2].tools.length;
      const geen = homeMigrate({ groups: [{ name: 'A', tools: K.filter(x => !['checkin', 'sleep', 'gebruik'].includes(x)) }, { name: 'Laatste', tools: [] }] }, K);
      r.nieuweGroep = geen.groups.length === 3 && geen.groups[2].name === 'Dag & gezondheid' && geen.groups[2].tools.join() === 'checkin,sleep,gebruik' && !geen.groups[1].tools.length;
      const vreemd = homeMigrate({ groups: [{ name: 'A', tools: ['bestaatniet', 'notes', 'notes'] }], hidden: ['ookniet'], fav: ['notes', 'x', 'notes'] }, K);
      r.opgeruimd = vreemd.groups[0].tools[0] === 'notes' && vreemd.groups[0].tools.filter(x => x === 'notes').length === 1 && !vreemd.hidden.length && vreemd.fav.join() === 'notes' && [].concat(...vreemd.groups.map(x => x.tools)).length === K.length;
      r.recent = homeRecentPush(['a', 'b', 'c', 'd'], 'c', 4).join() === 'c,a,b,d' && homeRecentPush(['a', 'b', 'c', 'd'], 'e', 4).join() === 'e,a,b,c';
      return r;
    }, [OUD, EIGEN]);
    ok(u.leeg, 'niets bewaard → nieuwe standaard (indeling 2)');
    ok(u.oud, 'oude standaard → nieuwe groepen; compact, favorieten en Overzicht blijven');
    ok(u.oudZuiver, 'homeMigrate verandert de invoer niet');
    ok(u.vroeg && u.half, 'ook oude standaarden van eerdere versies (zonder Telefoon, minder tools) gaan over');
    Object.entries(u.eigen).forEach(([k, v]) => ok(v, 'eigen indeling blijft precies zo: ' + k));
    ok(u.nieuweTools, 'nieuwe tools in een eigen indeling: bij de groep met hun groepsgenoten, de rest onveranderd');
    ok(u.zelfdeNaam, 'nieuwe tool naar de groep met de naam van zijn standaardgroep, niet naar de laatste');
    ok(u.nieuweGroep, 'geen groepsgenoten: een nieuwe groep met de standaardnaam, niet de laatste groep');
    ok(u.opgeruimd, 'onbekende en dubbele tools weg, alle tools één keer');
    ok(u.recent, 'homeRecentPush: vooraan, zonder dubbele, hooguit vier');

    // ---------- van begin tot eind: bewaarde oude standaard en bewaarde eigen indeling ----------
    await pg.evaluate(o => { localStorage.setItem('rt.home', JSON.stringify(o)); }, OUD); await pg.reload(); await pg.waitForTimeout(300);
    g = await home(pg);
    ok(g[0] === '★ Favorieten:notes' && g.slice(1).map(x => x.split(':')[0]).join('|') === NIEUW.join('|'), 'oude standaard bewaard → na starten de nieuwe groepen, favoriet blijft');
    ok(await pg.evaluate(() => $('#home-groups').classList.contains('compact') && !homeCfg().hidden.length), 'compact blijft aan');
    await pg.evaluate(() => show('homeedit')); await pg.waitForTimeout(150);
    ok((await pg.$$eval('#he-groups .hegname', x => x.map(e => e.firstChild.textContent))).join('|') === NIEUW.join('|'), 'Startscherm aanpassen toont de nieuwe groepen');
    await pg.click('#he-groups .hegroup:has(.hegname:has-text("Onderweg")) .headd'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#sheet-acts')).includes('Slaappraat (uit Dag & gezondheid)'), 'Tool toevoegen noemt de nieuwe groepen');
    await pg.click('#sheet-acts button:has-text("Klaar")'); await pg.waitForTimeout(100);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    await pg.evaluate(o => { localStorage.setItem('rt.home', JSON.stringify(o)); }, EIGEN); await pg.reload(); await pg.waitForTimeout(300);
    g = await home(pg);
    ok(g.map(x => x.split(':')[0]).join('|') === 'Handig|Mijn muziek|Bellen & contacten|Berichten & backup', 'eigen indeling bewaard → na starten precies zo (' + g.map(x => x.split(':')[0]) + ')');
    ok(g[0].endsWith('facturen,masterdata,bank,gebruik') && g[1] === 'Mijn muziek:radio,podcasts,spotify,skin' && g[2].startsWith('Bellen & contacten:calls,redial'), 'nieuwe tools erbij in hun groep, verborgen blijft verborgen');
    // Opslaan na een wijziging bewaart indeling 2: later niet opnieuw omgezet
    await pg.evaluate(() => heSet('compact', true)); await pg.reload(); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => store.get('home').v === 2 && homeCfg().groups[0].name === 'Handig'), 'bewaard als indeling 2, eigen groepen blijven');
    // Standaard herstellen: de nieuwe standaard, favorieten en de keuze voor Recent blijven
    await pg.evaluate(() => { const c = homeCfg(); c.fav = ['pdf']; c.recent = false; homeSave(c); show('homeedit'); heReset(); }); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => homeCfg().groups.map(x => x.name).join('|') === HOME_DEFAULT.groups.map(x => x.name).join('|') && homeCfg().fav.join() === 'pdf' && homeCfg().recent === false), 'Standaard herstellen: nieuwe groepen, favoriet en Recent-keuze blijven');
    await pg.evaluate(() => { localStorage.removeItem('rt.home'); localStorage.removeItem('rt.homeRecent'); }); await pg.reload(); await pg.waitForTimeout(300);

    // ---------- de eerste tegel hoog op het scherm (390×844), ook met een profiel en "Sinds gisteravond" ----------
    await pg.evaluate(() => { window._idOud = Android.appIdentity; Android.appIdentity = () => JSON.stringify({ self: { name: 'Rene van Es', callname: 'Rene' } }); appIdentityRefresh(); show('home'); }); await pg.waitForTimeout(150);
    const top = await pg.evaluate(() => ({ tile: Math.round(document.querySelector('#home-groups .tile').getBoundingClientRect().top), night: getComputedStyle($('#home-night')).display !== 'none', hello: $('#cmi-home').getBoundingClientRect().top, recent: !$('#home-recent').hidden }));
    ok(top.tile <= 350 && top.night && !top.recent, 'eerste tegel op y=' + top.tile + ' (≤ 350), met Sinds gisteravond');
    ok(top.hello < 60 && (await pg.textContent('#cmi-home')).includes('Hallo Rene'), '"Hallo Rene" in de kopbalk');
    await pg.click('#cmi-home'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'masterdata'), 'Hallo Rene opent Stamgegevens');
    await pg.evaluate(() => { Android.appIdentity = window._idOud; appIdentityRefresh(); show('home'); }); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#cmi-home')), 'zonder profiel geen lege rij of knop');
    await pg.evaluate(() => { store.set('homeRecent', []); show('home'); }); // Stamgegevens hierboven telde als gebruikt
    // Overzicht in één kaart; met alles uit (Gezien, Overzicht uit) geen lege kaart
    ok(await pg.evaluate(() => $('#home-ov').contains($('#home-night')) && $('#home-ov').contains($('#home-dash')) && !$('#home-ov').hidden), 'Sinds gisteravond en het overzicht in één kaart');
    await pg.click('#home-night button:has-text("Gezien")'); await pg.evaluate(() => heSet('dash', false)); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#home-ov')), 'niets te melden: geen kaart');
    await pg.evaluate(() => heSet('dash', true));

    // ---------- Recent gebruikt ----------
    ok(!(await pg.isVisible('#home-recent')), 'nog niets geopend: geen rij Recent gebruikt');
    for (const n of ['pdf', 'notes', 'radio', 'car', 'pdf', 'ai']) { await pg.evaluate(n => show(n), n); await pg.waitForTimeout(40); }
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(150);
    const rec = () => pg.$$eval('#home-recent .hrtool', x => x.map(e => e.dataset.t).join());
    ok(await pg.isVisible('#home-recent') && (await rec()) === 'ai,pdf,car,radio', 'laatst geopend eerst, zonder dubbele, hooguit vier: ' + await rec());
    ok(await pg.evaluate(() => { const r = $('#home-recent').getBoundingClientRect(), o = $('#home-ov').getBoundingClientRect(), g = $('#home-groups').getBoundingClientRect(); return r.top >= o.bottom && r.bottom <= g.top; }), 'rij onder het overzicht, boven de groepen');
    const first = await pg.evaluate(() => Math.round(document.querySelector('#home-recent .hrtool').getBoundingClientRect().top));
    ok(first <= 350, 'eerste tool (Recent) op y=' + first);
    ok(await pg.evaluate(() => [...document.querySelectorAll('#home-recent .hrtool')].every(e => e.getBoundingClientRect().height >= 44 && e.getBoundingClientRect().width >= 44)), 'tikvlakken minstens 44×44');
    await pg.evaluate(() => { show('carset'); show('notes'); show('home'); }); await pg.waitForTimeout(100);
    ok((await rec()) === 'notes,ai,pdf,car', 'een subscherm (instellingen van Mijn auto) telt niet, de tool zelf wel: ' + await rec());
    await pg.reload(); await pg.waitForTimeout(300);
    ok((await rec()) === 'notes,ai,pdf,car', 'blijft na herladen');
    await pg.click('#home-recent .hrtool[data-t="pdf"]'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'pdf'), 'tik opent de tool');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await rec()) === 'pdf,notes,ai,car', 'en zet hem vooraan');
    // verborgen tool niet in de rij
    await pg.evaluate(() => heToggle('ai', false)); await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    ok((await rec()) === 'pdf,notes,car', 'verborgen tool niet in Recent');
    await pg.evaluate(() => heToggle('ai', true));
    await pg.screenshot({ path: __dirname + '/shots/home-groepen-' + scheme + '.png' });
    // Uitzetten in Startscherm aanpassen: rij weg, lijst gewist, niets meer bijhouden
    await pg.evaluate(() => show('homeedit')); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => $('#he-recent').getAttribute('aria-checked') === 'true'), 'schakelaar Recent gebruikt staat aan');
    await pg.click('#he-recent'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => $('#he-recent').getAttribute('aria-checked') === 'false' && homeCfg().recent === false && !store.get('homeRecent', []).length), 'uit: keuze bewaard en lijst gewist');
    await pg.evaluate(() => { show('radio'); show('pdf'); show('home'); }); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#home-recent')) && !(await pg.evaluate(() => store.get('homeRecent', []).length)), 'uit: niets bijgehouden, geen rij');
    await pg.evaluate(() => { heRecent(true); show('vpn'); show('home'); }); await pg.waitForTimeout(100);
    ok((await rec()) === 'vpn', 'weer aan: houdt opnieuw bij');
    // Instellingen zoeken kent de schakelaar
    ok(await pg.evaluate(() => SET_REG.some(r => r.id === 'start.recent' && r.ctl === '#he-recent')), 'in het register van instellingen');

    // ---------- Engels ----------
    await pg.evaluate(() => rgSave({ lang: 'en' })); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(200);
    const en = await pg.$$eval('#home-groups .hgroup h3', x => x.map(e => e.textContent));
    ok(en.join('|') === 'Calls & contacts|Messages & backup|Music & radio|Documents & money|Day & health|On the go|Smart', 'groepen in het Engels: ' + en.join(', '));
    ok((await pg.textContent('#home-recent h3')) === 'Recently used' && (await pg.textContent('#home-recent .hrtool')) === 'VPN', 'Recent gebruikt in het Engels');
    await pg.evaluate(() => show('homeedit')); await pg.waitForTimeout(150);
    ok((await pg.evaluate(() => $('#he-recent').closest('.sw').textContent)).startsWith('Recently used'), 'schakelaar in het Engels');
    await pg.screenshot({ path: __dirname + '/shots/home-groepen-edit-en-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => rgSave({ lang: 'nl' })); await pg.waitForTimeout(200);
    await pg.evaluate(() => show('home'));

    // ---------- 360 px breed: geen tekst buiten beeld ----------
    await pg.setViewportSize({ width: 360, height: 780 }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => document.documentElement.scrollWidth <= 360 && [...document.querySelectorAll('#s-home .tile, #s-home .hrtool')].filter(e => e.getClientRects().length).every(e => e.getBoundingClientRect().right <= 360 && e.scrollWidth <= e.clientWidth + 1)), '360 px: alles past');
    console.log('errors', errs);
    await ctx.close();
  }

  // ---------- Play-versie en losse app ----------
  const errs = [];
  const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); else console.log('✓ ' + m); };
  let pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.addInitScript(() => { window.__testPlay = true; localStorage.setItem('rt.homeRecent', JSON.stringify(['redial', 'pdf', 'sms', 'notes'])); });
  await pg.goto(URL); await pg.waitForTimeout(400);
  const pl = await pg.$$eval('#home-groups .hgroup', gs => gs.filter(g => g.getClientRects().length).map(g => g.querySelector('h3').textContent + ':' + [...g.querySelectorAll('.tile')].filter(t => t.getClientRects().length).map(t => t.id.replace('tile-', '')).join(',')));
  ok(pl.join('|') === 'Muziek & radio:radio,podcasts|Documenten & geld:pdf,masterdata|Slim:notes', 'Play: alleen groepen met toegestane tools (' + pl.join(' | ') + ')');
  ok((await pg.$$eval('#home-recent .hrtool', x => x.map(e => e.dataset.t).join())) === 'pdf,notes', 'Play: Recent zonder tools die er niet in zitten');
  await pg.close();
  pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.addInitScript(() => { window.__testSolo = 'notes'; localStorage.setItem('rt.homeRecent', JSON.stringify(['pdf'])); });
  await pg.goto(URL); await pg.waitForTimeout(400);
  ok(await pg.evaluate(() => current === 'notes' && SOLO === 'notes'), 'losse app: opent meteen Notities');
  await pg.evaluate(() => show('pdf')); await pg.waitForTimeout(100);
  ok(await pg.evaluate(() => current === 'notes' && store.get('homeRecent').join() === 'notes,pdf'), 'losse app: andere tool gaat niet open en telt niet mee');
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
