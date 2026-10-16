/* Alleen de weergave verandert; tabbladen en uitgeklapte groepen schrijven niets naar de notitie. */
let notesScope='all';
const notesExpanded=new Set();
function noteUiShow(panel,scroll=true){
  if(!['list','children','extra'].includes(panel))return;
  const focusedPanel=document.activeElement.closest('[role=tabpanel]');
  for(const b of document.querySelectorAll('.note-section-tabs [role=tab]')){
    const selected=b.dataset.panel===panel;b.setAttribute('aria-selected',String(selected));b.tabIndex=selected?0:-1;
    $('#note-panel-'+b.dataset.panel).hidden=!selected;
  }
  if(focusedPanel&&focusedPanel.hidden)$('#note-tab-'+panel).focus({preventScroll:true});
  if(scroll&&current==='note')$('.note-section-tabs').scrollIntoView({block:'start'});
}
function noteUiExtra(group){
  noteUiShow('extra');const el=$('#note-extra-'+group);if(el){el.open=true;el.querySelector('summary').focus({preventScroll:true});}
}
function noteUiReset(){
  document.querySelectorAll('.note-option-group').forEach(el=>el.open=false);noteUiShow('list',false);
}
function noteUiSummaries(){
  const n=noteById(noteCur);if(!n)return;
  const r=n.repeat,option=$('#note-repeat').selectedOptions[0];
  $('#note-repeat-summary').textContent=r&&r.every?(option?option.textContent:'Herhalen staat aan'):'Niet herhalen';
  const count=$('#note-rem-list').children.length;
  $('#note-rem-summary').textContent=count?count+(count===1?' herinnering ingesteld':' herinneringen ingesteld'):'Nog geen herinneringen';
  $('#note-pin-summary').textContent=$('#note-pin-app').checked?'Vastgezet op het app-startscherm':'Snel openen vanaf je startscherm';
}
function notesFilter(scope){
  if(!['all','main','children'].includes(scope))return;notesScope=scope;renderNotes();
}
function notesExpand(id){
  if(notesExpanded.has(id))notesExpanded.delete(id);else notesExpanded.add(id);renderNotes();
  const b=[...document.querySelectorAll('.note-tree-toggle')].find(b=>b.dataset.expandId===id);if(b)b.focus({preventScroll:true});
}
document.querySelector('.note-section-tabs').addEventListener('keydown',e=>{
  if(!['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;
  const tabs=[...e.currentTarget.querySelectorAll('[role=tab]')],at=tabs.indexOf(document.activeElement);if(at<0)return;
  e.preventDefault();const i=e.key==='Home'?0:e.key==='End'?tabs.length-1:(at+(e.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;
  noteUiShow(tabs[i].dataset.panel);tabs[i].focus({preventScroll:true});
});
