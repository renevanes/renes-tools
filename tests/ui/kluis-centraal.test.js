// Documentenkluis als centrale opslag: wat de PDF-tools maken gaat vanzelf de kluis in (na ontgrendelen), een kluis-PDF
// opent in de eigen viewer, bewerken wordt een nieuwe versie, versies bekijken/vergelijken/terugzetten, en de schakelaar op Gemaakt.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);
  // De kluis vraagt het centrale slotscherm; daarna gaat het bewaren verder
  const rust=async()=>{
    for(let i=0;i<4;i++){
      const s=await p.waitForFunction(()=>tsZichtbaar()&&ts.tool==='kluis'&&!$('#ts-open').disabled?'slot':!pdfBusy&&!kc.bezig&&!kc.wacht&&!kc.rij.length?'klaar':'').then(h=>h.jsonValue());
      if(s==='klaar')return;
      await p.click('#ts-open');
    }
    throw new Error('kluis niet klaar');
  };
  const doorSlot=async()=>{
    const s=await p.waitForFunction(()=>tsZichtbaar()&&ts.tool==='kluis'&&!$('#ts-open').disabled?'slot':!klDan&&!kc.wacht?'klaar':'').then(h=>h.jsonValue());
    if(s==='slot'){ await p.click('#ts-open'); await p.waitForFunction(()=>!tsZichtbaar()&&!klDan); }
  };
  const openKluis=async()=>{
    await p.evaluate(()=>show('kluis'));
    if(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'))await p.click('#ts-open');
    await p.waitForFunction(()=>current==='kluis'&&$('#kl-open').style.display==='block');
  };
  // 1. Plaatjes naar PDF: de kluis is op slot, ontgrendelen gaat vanzelf; daarna staat de PDF in de kluis en niet meer onder Gemaakt
  await p.evaluate(()=>{Android._kl.open=false;Android._kl.docs=[];openTool('pdf');});await p.waitForTimeout(150);
  await p.click('#pdf-t-img');await p.click('text=＋ Plaatjes kiezen');await (async()=>{if(await p.isVisible('#sheet.on'))await p.click('#sheet-acts >> text=Bestand op de telefoon');})();await p.waitForTimeout(150);
  await p.fill('#pdf-img-name','Bonnetjes');await p.click('#pdf-img-make');await rust();await p.waitForTimeout(60);
  let kl=await p.evaluate(()=>JSON.parse(JSON.stringify(Android._kl)));
  assert.equal(kl.docs.length,1,'in de kluis');assert.equal(kl.docs[0].name,'Bonnetjes.pdf');assert.equal(kl.docs[0].category,'PDF');assert.equal(kl.docs[0].source,'pdf');
  assert.ok(!await p.evaluate(()=>Android._pdf.outs.some(o=>o.name==='Bonnetjes.pdf')),'niet meer leesbaar onder Gemaakt');
  assert.match(await p.textContent('.pdfres[data-scr="pdfimg"]'),/Bonnetjes\.pdf/,'net gemaakt blijft zichtbaar');
  assert.match(await p.textContent('.pdfres[data-scr="pdfimg"]'),/In de documentenkluis/);
  assert.match(await p.textContent('#s-pdfimg .pdfkeep'),/Bewaard in de documentenkluis/);
  assert.match(await p.textContent('#toast'),/In de documentenkluis/);
  // Tikken op de rij: bekijken gaat via de kluis (eigen viewer), delen en opslaan ook
  await p.click('.pdfres[data-scr="pdfimg"] .trip');await p.getByRole('button',{name:'Bekijken',exact:true}).click();
  await p.waitForFunction(()=>tsZichtbaar()&&ts.tool==='kluis'&&!!klDan&&!$('#ts-open').disabled);
  await p.evaluate(()=>goBack());await p.waitForFunction(()=>!tsZichtbaar()&&!klDan);
  assert.match(await p.textContent('#toast'),/Kluis niet ontgrendeld/);assert.doesNotMatch(await p.textContent('#toast'),/schermvergrendeling/,'terug is annuleren, niet een ontbrekende schermvergrendeling');
  await p.click('.pdfres[data-scr="pdfimg"] .trip');await p.getByRole('button',{name:'Bekijken',exact:true}).click();await doorSlot();
  await p.waitForFunction(v=>JSON.stringify(Android._kl.opened)===JSON.stringify(v),{id:kl.docs[0].id,version:0});
  await p.click('.pdfres[data-scr="pdfimg"] .trip');await p.getByRole('button',{name:'Delen (bijv. mailen of WhatsApp)',exact:true}).click();await doorSlot();
  await p.waitForFunction(v=>JSON.stringify(Android._kl.shared)===JSON.stringify(v),{id:kl.docs[0].id,version:0});
  // 2. Schakelaar uit: dan blijft het onder Gemaakt, en daar staat de knop om het alsnog te bewaren
  await p.evaluate(()=>show('pdfmade'));await p.waitForTimeout(80);
  assert.equal(await p.getAttribute('#kc-aan','aria-checked'),'true');
  await p.click('#kc-aan');assert.equal(await p.getAttribute('#kc-aan','aria-checked'),'false');assert.equal(await p.evaluate(()=>store.get('pdfKluisVanzelf',true)),false);
  await p.evaluate(()=>show('pdftext'));await p.fill('#pdf-text','Hallo');await p.fill('#pdf-text-title','Brief');
  await p.evaluate(()=>pdfFromText());await rust();await p.waitForTimeout(80);
  assert.equal(await p.evaluate(()=>Android._kl.docs.length),1,'uit: niet in de kluis');
  await p.evaluate(()=>show('pdfmade'));await p.waitForTimeout(80);
  assert.equal(await p.isVisible('#kc-alles'),true);
  await p.click('#kc-alles');await rust();await p.waitForTimeout(80);
  assert.deepEqual(await p.evaluate(()=>Android._kl.docs.map(d=>d.name)),['Bonnetjes.pdf','Brief.pdf'],'alles van Gemaakt in de kluis');
  assert.equal(await p.evaluate(()=>Android._pdf.outs.length),0,'Gemaakt is leeg');assert.equal(await p.isVisible('#kc-alles'),false);
  await p.click('#kc-aan');assert.equal(await p.getAttribute('#kc-aan','aria-checked'),'true');
  // 3. In de kluis: menu met bekijken (eigen viewer), bewerken, delen, opslaan, vergelijken
  // Na het bewaren is de kluis weer op slot (alleen het kluisscherm houdt hem open)
  assert.equal(await p.evaluate(()=>Android._kl.open),false,'kluis na bewaren weer op slot');
  await openKluis();
  const id=await p.evaluate(()=>Android._kl.docs[0].id),rij=p.locator('#kl-list .trip',{hasText:'Bonnetjes.pdf'});
  await rij.click();
  for(const n of ['Bekijken','Bewerken en ondertekenen','Delen (bijv. mailen of WhatsApp)','Opslaan als…','Vergelijken met…'])assert.equal(await p.locator('#sheet-acts').getByRole('button',{name:n,exact:true}).count(),1,n);
  await p.locator('#sheet-acts').getByRole('button',{name:'Opslaan als…',exact:true}).click();
  await p.waitForFunction(v=>JSON.stringify(Android._kl.savedAs)===JSON.stringify(v),{id,version:0});
  // Bewerken: opent de bewerker met de kluis-PDF; bewaren wordt een nieuwe versie van hetzelfde document
  await rij.click();await p.locator('#sheet-acts').getByRole('button',{name:'Bewerken en ondertekenen',exact:true}).click();
  await p.waitForFunction(id=>current==='pdfedit'&&pdfIn.kluis===id&&pdfEd.revision&&!pdfEd.loading&&pdfEd.meta[0],id);
  await p.evaluate(()=>pdfEditDate());
  await p.click('#pdf-edit-save');await rust();await p.waitForTimeout(80);
  kl=await p.evaluate(()=>JSON.parse(JSON.stringify(Android._kl)));
  const d=kl.docs.find(x=>x.id===id);
  assert.equal(kl.docs.length,2,'geen nieuw document');assert.equal((d.versions||[]).length,1,'oude versie bewaard');assert.equal(d.vnote,'Bewerkt');
  assert.match(await p.textContent('#s-pdfedit .pdfkeep'),/nieuwe versie/);
  // 4. Versies: lijst, vergelijken met de huidige, terugzetten
  await openKluis();
  assert.match(await rij.innerText(),/versie 2/);
  await rij.click();await p.locator('#sheet-acts').getByRole('button',{name:'Eerdere versie',exact:true}).click();
  await p.locator('#sheet-acts button').first().click();
  for(const n of ['Bekijken','Vergelijken met de huidige versie','Deze versie terugzetten'])assert.equal(await p.locator('#sheet-acts').getByRole('button',{name:n,exact:true}).count(),1,n);
  await p.locator('#sheet-acts').getByRole('button',{name:'Bekijken',exact:true}).click();
  await p.waitForFunction(v=>JSON.stringify(Android._kl.opened)===JSON.stringify(v),{id,version:1});
  await rij.click();await p.locator('#sheet-acts').getByRole('button',{name:'Eerdere versie',exact:true}).click();await p.locator('#sheet-acts button').first().click();
  await p.locator('#sheet-acts').getByRole('button',{name:'Vergelijken met de huidige versie',exact:true}).click();
  await p.waitForFunction(()=>current==='pdfcmp'&&cmp.a&&cmp.b);
  assert.deepEqual(await p.evaluate(()=>[cmp.a.version,cmp.b.version,cmp.a.kluis===cmp.b.kluis]),[1,0,true],'A = versie 1, B = de huidige');
  // Weg van het kluisscherm = de kluis op slot (zoals altijd): terug is opnieuw ontgrendelen
  await p.evaluate(()=>show('kluis'));assert.equal(await p.evaluate(()=>tsZichtbaar()&&ts.tool==='kluis'&&!Android._kl.open),true,'kluis weer op slot');
  await p.click('#ts-open');await p.waitForFunction(()=>current==='kluis'&&!tsZichtbaar()&&$('#kl-open').style.display==='block');
  assert.equal(await p.evaluate(()=>Android._cmp.cleared>0),true,'vergelijken wist zijn bestanden bij weggaan');
  await rij.click();await p.locator('#sheet-acts').getByRole('button',{name:'Eerdere versie',exact:true}).click();await p.locator('#sheet-acts button').first().click();
  await p.locator('#sheet-acts').getByRole('button',{name:'Deze versie terugzetten',exact:true}).click();await p.click('#modal-ok');
  await p.waitForFunction(()=>/teruggezet/.test($('#toast').textContent));
  assert.equal(await p.evaluate(id=>Android._kl.docs.find(x=>x.id===id).versions.length,id),2,'wat er stond blijft als versie');
  // Verwijderen noemt ook de eerdere versies
  await rij.click();await p.locator('#sheet-acts').getByRole('button',{name:'Verwijderen',exact:true}).click();
  assert.match(await p.textContent('#modal-text'),/eerdere versies/);await p.click('#modal-cancel');
  // 5. Ontgrendelen geweigerd: dan blijft de PDF onder Gemaakt, met een melding
  await p.evaluate(()=>{Android.kluisClose();window._u=Android.kluisUnlock;Android.kluisUnlock=()=>setTimeout(()=>onKluis('Niet ontgrendeld'),20);show('pdftext');});
  await p.fill('#pdf-text','Tweede');await p.fill('#pdf-text-title','Memo');await p.evaluate(()=>pdfFromText());await rust();await p.waitForTimeout(60);
  assert.ok(await p.evaluate(()=>Android._pdf.outs.some(o=>o.name==='Memo.pdf')),'blijft onder Gemaakt');
  assert.match(await p.textContent('#s-pdftext .pdfkeep'),/Niet in de documentenkluis gezet \(Niet ontgrendeld\)/);
  await p.evaluate(()=>{Android.kluisUnlock=window._u;});
  await p.screenshot({path:__dirname+'/shots/kluis-centraal-'+colorScheme+'.png'});
  await p.close();
}
await b.close();assert.deepEqual(errors,[]);console.log('kluis als centrale opslag: vanzelf bewaren, schakelaar, bewerken als versie, versies, vergelijken, weigeren: errors []');
})().catch(e=>{console.error(e);console.log('errors ['+e.message+']');process.exit(1);});
