// VPN: OpenVPN-tunnel toevoegen (met inloggen), reserve bij blokkade voorstellen en instellen, status via reserve
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
const OVPN = 'client\ndev tun\nproto udp\nremote nl.vpn.example 1194\nremote nl.vpn.example 443 tcp\nauth-user-pass\nremote-cert-tls server\n<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n';
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const vis = sel => pg.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
    // eerst een WireGuard-tunnel (uit een bestand)
    await pg.click('#tile-vpn'); await pg.waitForTimeout(200);
    await pg.click('#vp-intro button:has-text("Bestand")'); await pg.waitForTimeout(300);
    await pg.click('#ve-save'); await pg.waitForTimeout(300);
    // OpenVPN plakken
    await pg.click('button:has-text("Tunnel toevoegen")'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Tekst plakken")'); await pg.waitForTimeout(400);
    await pg.fill('#ve-text', OVPN); await pg.waitForTimeout(450);
    assert((await pg.textContent('#ve-check')).includes('OpenVPN') && await vis('#ve-authbox') && !(await vis('#ve-pubbox')), 'OpenVPN herkend, inlogvelden zichtbaar, geen WireGuard-sleutel');
    await pg.fill('#ve-name', 'Werk OpenVPN');
    await pg.click('#ve-save'); await pg.waitForTimeout(200);
    assert((await pg.textContent('#toast')).includes('gebruikersnaam'), 'zonder inloggegevens: melding');
    await pg.fill('#ve-user', 'rene'); await pg.fill('#ve-pass', 'geheim');
    await pg.screenshot({ path: __dirname + '/shots/vpn-ovpn-edit-' + scheme + '.png' });
    await pg.click('#ve-save'); await pg.waitForTimeout(700);
    assert(await pg.evaluate(() => document.querySelector('#modal').classList.contains('on')) && (await pg.textContent('#modal-title')).includes('Reserve'), 'stelt reserve voor');
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    const rows = await pg.$$eval('#vp-list .srow small', x => x.map(e => e.textContent));
    assert(rows[0].startsWith('WireGuard') && rows[0].includes('reserve: Werk OpenVPN') && rows[1].startsWith('OpenVPN'), 'lijst toont soort en reserve: ' + rows.join(' | '));
    // reservemenu
    await pg.click('#vp-list .srow >> nth=0'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Reserve bij blokkade")'); await pg.waitForTimeout(150);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    assert(acts.some(a => a.includes('1. Werk OpenVPN') && a.includes('weghalen')), 'reservemenu: ' + acts.join(' | '));
    await pg.screenshot({ path: __dirname + '/shots/vpn-fallback-' + scheme + '.png' });
    await pg.evaluate(() => closeSheet());
    // bewerken: gebruikersnaam staat er, wachtwoord niet
    await pg.click('#vp-list .srow >> nth=1'); await pg.waitForTimeout(150);
    assert(!(await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).some(a => a.includes('publieke sleutel')), 'OpenVPN: geen publieke sleutel in het menu');
    await pg.click('#sheet-acts button:has-text("Bewerken")'); await pg.waitForTimeout(400);
    assert((await pg.inputValue('#ve-user')) === 'rene' && (await pg.inputValue('#ve-pass')) === '' && (await pg.getAttribute('#ve-pass', 'placeholder')).includes('bewaard'), 'bewerken: gebruikersnaam ingevuld, wachtwoord bewaard');
    await pg.click('#ve-save'); await pg.waitForTimeout(300);
    assert((await pg.textContent('#toast')).includes('Opgeslagen'), 'opslaan zonder wachtwoord opnieuw in te tikken');
    // geblokkeerd: verbonden via de reserve
    await pg.evaluate(() => { Android._vpBlocked = true; Android._vp.consent = true; });
    await pg.click('#vp-toggle'); await pg.waitForTimeout(900);
    assert((await pg.textContent('#vp-sub')).includes('via reserve: Werk OpenVPN') && (await pg.textContent('#vp-sub')).includes('OpenVPN'), 'status: via reserve (' + await pg.textContent('#vp-sub') + ')');
    await pg.screenshot({ path: __dirname + '/shots/vpn-reserve-' + scheme + '.png' });
    console.log('errors', errs);
    await pg.close();
  }
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
