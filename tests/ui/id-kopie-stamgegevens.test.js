// ID-kopie vanuit Stamgegevens: op het kaartje van een identiteitsdocument een afgeschermde kopie maken (kaartfoto's als bron,
// in Activiteiten als Kaartfoto bekeken door ID-kopie afschermen), voorgestelde vlakken, controle per pagina, de kopie in de
// documentenkluis gekoppeld aan dat document, en de lijst met gemaakte kopieën op het kaartje.
const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('path');
(async()=>{const b=await chromium.launch(),errors=[];
for(const colorScheme of ['light','dark']){
  const p=await b.newPage({viewport:{width:390,height:844},colorScheme});p.on('pageerror',e=>errors.push(e.message));
  await p.goto('file://'+path.join(__dirname,'ui/index.html'));await p.waitForTimeout(300);const T=require('./_stamtabs')(p);
  const foto=await p.evaluate(()=>{const c=document.createElement('canvas');c.width=80;c.height=50;c.getContext('2d').fillRect(0,0,10,10);return c.toDataURL('image/jpeg');});
  await p.evaluate(foto=>{
    const id=n=>'00000000-0000-0000-0000-00000000000'+n;
    Android._cm.records=[{id:id(1),type:'persoon',label:'Rene',revision:1,fields:{naam:'Van Es',voornamen:'Rene'}},
      {id:id(2),type:'identiteit',label:'Paspoort Rene',revision:1,fields:{persoon:id(1),documentsoort:'paspoort',document:'NX99',geldig:'2034-01-01'},images:{front:foto}},
      {id:id(3),type:'identiteit',label:'ID-kaart zonder foto',revision:1,fields:{persoon:id(1),documentsoort:'identiteitskaart',document:'IX1',geldig:'2034-01-01'}}];
    Android._kl.open=false;Android._kl.docs=[];
  },foto);
  await p.evaluate(()=>show('masterdata'));await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  // Zonder foto: de knop staat uit en zegt waarom
  await T.kaart('00000000-0000-0000-0000-000000000003');
  assert.equal(await p.isDisabled('#cmc-idkopie-maak'),true);assert.match(await p.textContent('#cmc-idkopie'),/Voeg eerst een foto/);
  // Kluis op slot: een knop om de gemaakte kopieën te tonen
  assert.equal(await p.isVisible('#cmc-idlijst-open'),true);
  // Met foto: kopie maken opent ID-kopie afschermen met de foto en een voorstel
  await T.kaart('00000000-0000-0000-0000-000000000002');
  await p.click('#cmc-idkopie-maak');
  await p.waitForFunction(()=>current==='idcopy'&&idc.pages.length===1&&!idc.loading&&!idc.busy);
  assert.equal(await p.isVisible('#id-bron'),true);assert.equal(await p.textContent('#id-bron-naam'),'Paspoort Rene');
  assert.equal(await p.evaluate(()=>Android._idc.master),true,'foto kwam uit Stamgegevens (niet via de interface)');
  assert.equal(await p.evaluate(()=>idc.pages[0].rects.length),5,'vijf vlakken voorgesteld voor een paspoort');
  assert.equal(await p.evaluate(()=>idc.pages[0].checked),false,'voorstel is niet gecontroleerd');
  assert.match(await p.textContent('#id-status'),/Voorstel: zwarte vlakken/);
  assert.equal(await p.evaluate(()=>cmOpen()),false,'stamgegevens weer op slot na het verlaten');
  const log=await p.evaluate(()=>Android._cm.events.filter(e=>e.action==='photo_view').map(e=>e.source));assert.equal(log.filter(x=>x==='idcopy').length,1,'in Activiteiten: één keer door ID-kopie (het kaartje toont de foto zelf ook)');
  // Ook bij een gekozen bestand: vlakken voorstellen
  await p.click('#id-voorstel');await p.locator('#sheet-acts').getByRole('button',{name:'Rijbewijs, voorkant',exact:true}).click();
  assert.equal(await p.evaluate(()=>idc.pages[0].rects.length),4);
  await p.click('#id-voorstel');await p.locator('#sheet-acts').getByRole('button',{name:'Paspoort (fotopagina)',exact:true}).click();
  // Maken en bewaren: in de kluis, gekoppeld aan het paspoort, met ontvanger, doel en datum
  await p.fill('#id-recipient','Verhuurder <b>');await p.fill('#id-purpose','Huurcontract');await p.check('#id-checked');
  await p.click('#id-save');await p.waitForFunction(()=>!idc.busy&&!kc.bezig&&!kc.wacht&&!kc.rij.length&&Android._kl.docs.length===1);
  const doc=await p.evaluate(()=>Android._kl.docs[0]);
  assert.equal(doc.category,'Identiteit');assert.equal(doc.source,'idkopie');
  assert.deepEqual({kind:doc.link.kind,ref:doc.link.ref,soort:doc.link.soort,ontvanger:doc.link.ontvanger,doel:doc.link.doel},{kind:'id',ref:'00000000-0000-0000-0000-000000000002',soort:'paspoort',ontvanger:'Verhuurder <b>',doel:'Huurcontract'});
  assert.match(doc.name,/^ID-kopie voor Verhuurder <b> \d{4}-\d\d-\d\d\.pdf$/);
  assert.equal(await p.textContent('#id-result-name'),doc.name,'resultaat toont de naam in de kluis');assert.match(await p.textContent('#id-result .kcstatus'),/documentenkluis/);
  assert.equal(await p.isVisible('#id-bron'),false);
  await p.click('#id-result >> text=Kopie bekijken');assert.deepEqual(await p.evaluate(()=>Android._kl.opened),{id:doc.id,version:0});
  // Terug op het kaartje: de gemaakte kopie staat erbij (de kluis is nog open)
  await p.evaluate(()=>{show('masterdata');});await p.click('#cm-unlock');await p.waitForFunction(()=>cmOpen()&&cm.recordsReady);
  await p.evaluate(()=>Android.kluisUnlock());await p.waitForTimeout(60);
  await T.kaart('00000000-0000-0000-0000-000000000002');
  assert.match(await p.textContent('#cmc-idlijst'),/Eén kopie gemaakt/);assert.equal(await p.textContent('#cmc-idlijst li b'),'Verhuurder <b>');assert.equal(await p.locator('#cmc-idlijst li b b').count(),0);
  assert.match(await p.textContent('#cmc-idlijst li small'),/Huurcontract/);
  await p.screenshot({path:__dirname+'/shots/id-kopie-stamgegevens-'+colorScheme+'.png',fullPage:true});
  await p.close();
}
await b.close();assert.deepEqual(errors,[]);console.log('ID-kopie vanuit Stamgegevens: bron, voorstel, kluis met koppeling, lijst op het kaartje: errors []');
})().catch(e=>{console.error(e);console.log('errors ['+e.message+']');process.exit(1);});
