// Samenvatten met één tik: sms-gesprek, WhatsApp-chat en uitgeschreven gesprek naar de assistent (testbrug).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._sms.perm = true; Android._smsDemo(); Android._rd.hasKey = true; Android._mkDemo(); Android._tx.done.a1 = true; });

    // Zonder taalmodel: uitleg en naar de assistent
    await pg.evaluate(() => { show('sms'); openSms(2, 'Moeder'); }); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#sms-sum') && (await pg.getAttribute('#sms-sum', 'aria-label')).includes('Samenvatten'), 'knop Samenvatten bij een sms-gesprek');
    await pg.click('#sms-sum'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal-title')).includes('taalmodel'), 'zonder taalmodel: uitleg');
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'ai') && await pg.isVisible('#ai-setup'), 'naar de assistent om een model te kiezen');
    ok(await pg.evaluate(() => Android._ai.asked === undefined), 'niets gevraagd');

    // Met taalmodel: sms
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._ai.reply = '- Moeder vraagt of je zaterdag komt.\n- Afgesproken: om twee uur.'; show('sms'); openSms(2, 'Moeder'); }); await pg.waitForTimeout(150);
    await pg.click('#sms-sum'); await pg.waitForTimeout(250);
    const last = await pg.evaluate(() => Android._ai.last);
    ok(await pg.evaluate(() => current === 'ai' && aiChat.title === 'Samenvatting: Moeder'), 'nieuw gesprek "Samenvatting: Moeder"');
    const q = last.messages[0].content;
    ok(last.messages.length === 1 && q.startsWith('Vat dit sms-gesprek met Moeder samen') && q.includes('Voorbeeld-sms 13 met Moeder') && q.includes('Moeder: ') && q.includes('Ik: ') && /— .* —/.test(q), 'de vraag: instructie met het hele gesprek (dagregels, Ik/naam)');
    ok(last.noLearn === true && last.noAct === true && last.sum === true && last.notes === false, 'niets leren, geen acties, geen slim zoeken');
    ok((await pg.textContent('#ai-msgs .aimsg.me')).includes('Vat samen: sms-gesprek met Moeder') && (await pg.textContent('#ai-msgs .aimsg.me .aiatt')).includes('13 berichten'), 'in beeld: korte vraag met "13 berichten" (niet de hele tekst)');
    await pg.waitForTimeout(900);
    ok((await pg.textContent('#ai-msgs')).includes('Afgesproken'), 'de samenvatting staat er');
    await pg.screenshot({ path: __dirname + '/shots/ai-samenvatten-' + scheme + '.png' });
    // Doorvragen: de bijlage gaat weer mee, en er wordt niets over jou geleerd
    await pg.fill('#ai-in', 'Wat moet ik meenemen?'); await pg.click('#ai-send'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => { const l = Android._ai.last; return l.messages[0].content.startsWith('Vat dit sms-gesprek') && l.messages[0].att === true && !l.messages[2].att && l.noLearn === true && l.noAct === true; }), 'doorvragen: het gesprek gaat mee (als bijlage), niets leren, geen acties (tekst van anderen)');
    await pg.waitForTimeout(900);
    ok(await pg.evaluate(() => { const c = Android._ai.chats.find(x => x.title === 'Samenvatting: Moeder'); return c && c.msgs[0].att && c.msgs[0].att.q.includes('Voorbeeld-sms'); }), 'bewaard met de bijlage');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'smschat'), 'terug: weer bij het sms-gesprek');

    // Lang gesprek: alleen de nieuwste berichten die passen (het groot model heeft minder ruimte)
    await pg.evaluate(() => { const l = []; for (let k = 0; k < 400; k++) l.push({ id: k, me: k % 2 === 0, t: Date.now() - (400 - k) * 6e5, text: 'Bericht nummer ' + k + ' met wat extra tekst om het gesprek lang te maken' }); Android._sms.msgs[2] = l; Android._ai.st.groot = { state: 'ready' }; Android._ai.chat = 'groot'; openSms(2, 'Moeder'); });
    await pg.waitForTimeout(150); await pg.click('#sms-sum'); await pg.waitForTimeout(250);
    const q2 = await pg.evaluate(() => Android._ai.last.messages[0].content);
    ok(q2.includes('Bericht nummer 399 ') && !q2.includes('Bericht nummer 0 ') && /alleen de laatste \d+ van 400 berichten/.test(q2) && q2.length < 5200, 'lang gesprek: de laatste berichten die passen (' + q2.length + ' tekens)');
    ok((await pg.textContent('#ai-msgs .aimsg.me .aiatt')).includes('van 400 berichten'), 'in beeld: de laatste … van 400');
    await pg.waitForTimeout(900);
    await pg.evaluate(() => { Android._ai.chat = 'klein'; });

    // WhatsApp
    await pg.evaluate(() => { show('chats'); openChat(1, 'Huisarts', 0); }); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#chat-sum'), 'knop Samenvatten bij een WhatsApp-chat');
    await pg.click('#chat-sum'); await pg.waitForTimeout(250);
    const qw = await pg.evaluate(() => Android._ai.last.messages[0].content);
    ok((await pg.textContent('#ai-msgs .aimsg.me .aiatt')).includes('40 berichten'), 'WhatsApp: aantal berichten');
    ok(qw.startsWith('Vat dit WhatsApp-chat met Huisarts') && qw.includes('Huisarts: Voorbeeldbericht') && qw.includes('📷 Foto') && await pg.evaluate(() => aiChat.title === 'Samenvatting: Huisarts'), 'WhatsApp: met afzenders en media-labels');
    await pg.waitForTimeout(900);

    // WhatsApp: 300 gelezen (het maximum): er kunnen er meer zijn
    await pg.evaluate(() => { const l = []; for (let k = 0; k < 320; k++) l.push({ id: k, me: false, t: Date.now() - (320 - k) * 6e4, text: 'Bericht ' + k, sender: 'Jos', label: '' }); Android._rd.msgs[3] = l; show('chats'); openChat(3, 'Jos', 0); });
    await pg.waitForTimeout(150); await pg.click('#chat-sum'); await pg.waitForTimeout(250);
    ok((await pg.textContent('#ai-msgs .aimsg.me .aiatt')).includes('De laatste 300 berichten') && await pg.evaluate(() => /alleen de laatste 300 berichten/.test(Android._ai.last.messages[0].content)), 'WhatsApp met 300 gelezen (het maximum): "de laatste 300", niet "300 berichten"');
    await pg.waitForTimeout(900);
    // Bezig met een antwoord: niet nog een samenvatting tegelijk
    await pg.evaluate(() => { show('chats'); openChat(3, 'Jos', 0); }); await pg.waitForTimeout(150);
    await pg.evaluate(() => { aiBusy = true; }); await pg.click('#chat-sum'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('nog bezig') && await pg.evaluate(() => current === 'chat'), 'bezig: melding, niets gestart');
    await pg.evaluate(() => { aiBusy = false; });

    // Uitgeschreven gesprek
    await pg.evaluate(() => { show('transcripts'); txOpen('a1'); }); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#txd-sum'), 'knop bij een uitgeschreven gesprek');
    await pg.click('#txd-sum'); await pg.waitForTimeout(250);
    const qt = await pg.evaluate(() => Android._ai.last.messages[0].content);
    ok(qt.startsWith('Vat dit uitgeschreven gesprek "Moeder"') && qt.includes('Kom je om twee uur of liever later?') && !qt.includes('"Ik" ben ik zelf') && await pg.evaluate(() => aiChat.title === 'Samenvatting: Moeder'), 'uitgeschreven gesprek samengevat');
    ok((await pg.textContent('#ai-msgs .aimsg.me .aiatt')).includes('3 zinnen'), 'in beeld: 3 zinnen');
    await pg.waitForTimeout(900);
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
