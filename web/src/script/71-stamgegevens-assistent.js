/* Wat weet de assistent?: kiezen wat de assistent (de lokale AI, op de telefoon) uit Stamgegevens weet. Het hoofdschakelaartje
 * is "delen" in het app-profiel; daaronder per soort gegevens (vinkjes ai… in het app-profiel) en per persoon ("delen" bij die
 * persoon). Op slot zie je alleen wat de assistent nu weet (de gedeelde momentopname); kiezen kan weer zodra Stamgegevens
 * open is (vanzelf, of na het slotscherm van Extra ontgrendelen).
 * Over mij staat bij de assistent zelf (Persoonlijk). */
const CMAI_SOORTEN=['aiStatus','aiGeboorte','aiDocumenten','aiContact','aiWoonplaats'];
// Zelfde standaard als MasterIdentity.ai: leeg = aan, behalve de woonplaats
function cmaiOn(prof,key){const v=prof?.[key]||'';return v?v==='ja':key!=='aiWoonplaats';}
function cmaiOpen(){if(current!=='cmai')show('cmai');cmaiRender();}
function cmaiSw(id,on,disabled){const b=$('#'+id);b.classList.toggle('on',!!on);b.setAttribute('aria-checked',String(!!on));b.disabled=!!disabled||cm.mutating;}
function cmaiRender(){
  const open=cmOpen()&&cm.recordsReady,prof=open?cm.records.find(r=>r.type==='profiel'):null,me=open?cmkMe():'';
  $('#cm-ai-set').hidden=!open;cmiSharedRender();
  const box=$('#cm-ai-mensen');box.replaceChildren();$('#cm-ai-mensen-card').hidden=true;
  if(!open)return;
  const on=!!me&&prof?.delen==='ja';
  cmaiSw('cm-ai-delen',on,!me);
  $('#cm-ai-delen-s').textContent=!me?t('Voeg eerst jezelf toe in Stamgegevens.'):on?t('De assistent weet wie jij bent en wie je mensen zijn.'):t('De assistent weet nu niets uit Stamgegevens.');
  $('#cm-ai-soorten').hidden=!on;for(const k of CMAI_SOORTEN)cmaiSw('cm-ai-'+k,cmaiOn(prof,k),!on);
  const people=cm.records.filter(r=>r.type==='persoon'&&r.id!==me).sort((a,b)=>a.label.localeCompare(b.label));
  $('#cm-ai-mensen-card').hidden=!on||!people.length;if(!on)return;
  people.forEach((p,i)=>{
    const row=cmEl('div',undefined,'cm-sw'),txt=cmEl('span',undefined,'cm-sw-t'),b=cmEl('b',p.label),tg=cmEl('button',undefined,'tg');b.id='cm-ai-p'+i+'-t';
    const wie=cmkToMe(p.id,me).map(x=>cmkWieText(x,p.label)).join(' · ');
    txt.append(b,cmEl('small',wie||t('Geen relatie met jou')));tg.type='button';tg.classList.toggle('on',!!p.bekend);tg.setAttribute('role','switch');tg.setAttribute('aria-checked',String(!!p.bekend));tg.setAttribute('aria-labelledby',b.id);tg.disabled=cm.mutating;
    tg.onclick=()=>cmaiPerson(p.id,!p.bekend);row.append(txt,tg);box.append(row);
  });
}
function cmaiSave(rec,note){
  cmRequest('masterSave',[JSON.stringify(rec)],out=>{cmBusy(false);appIdentityRefresh();cmListLoad(()=>{cmaiRender();cmNote(out.contextError?t(out.contextError):note);});},false,()=>{cmBusy(false);cmaiRender();});
}
function cmaiSet(key){
  if(!cmOpen()||cm.mutating||!cm.recordsReady)return;
  const prof=cm.records.find(r=>r.type==='profiel'),me=cmkMe();if(!me||!prof){cmNote(t('Voeg eerst jezelf toe in Stamgegevens.'));return;}
  const now=key==='delen'?prof.delen==='ja':cmaiOn(prof,key),val=now?'nee':'ja';
  const note=key==='delen'?(val==='ja'?t('De assistent kent nu jou en je mensen.'):t('De assistent weet nu niets meer uit Stamgegevens.')):t('Bijgewerkt.');
  cmBusy(true);cmRequest('masterRead',[prof.id,'masterdata',''],r=>{const rec=r.record;rec.fields[key]=val;cmaiSave(rec,note);},false,()=>cmBusy(false));
}
function cmaiPerson(id,on){
  if(!cmOpen()||cm.mutating)return;
  cmBusy(true);cmRequest('masterRead',[id,'masterdata',''],r=>{const rec=r.record;rec.fields.delen=on?'ja':'nee';cmaiSave(rec,on?t('{naam} is nu bekend bij de assistent.',{naam:rec.label}):t('De assistent kent {naam} nu niet meer.',{naam:rec.label}));},false,()=>cmBusy(false));
}
/* Over mij staat bij de assistent (Persoonlijk); een eigen hulpfunctie, zodat een losse app Stamgegevens hem weglaat. */
function cmaiOverMij(){jumpTo('aisetpers');if(current==='aisetpers')setTimeout(()=>{const a=$('#aic-about');if(a)a.focus();},0);}
