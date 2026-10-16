let klEditId=null;
function klOrganizeOpen(id){
  klRender();const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  klEditId=id;$('#kl-edit-title').textContent='Ordenen: '+d.name;
  $('#kl-edit-category').value=d.category||'';$('#kl-edit-note').value=d.note||'';$('#kl-edit-favorite').checked=!!d.favorite;
  $('#kl-edit-status').textContent='';$('#kl-edit').hidden=false;$('#kl-edit').scrollIntoView({block:'center'});$('#kl-edit-category').focus();
}
function klOrganizeClose(){
  klEditId=null;$('#kl-edit').hidden=true;$('#kl-edit-title').textContent='Document ordenen';$('#kl-edit-category').value='';$('#kl-edit-note').value='';$('#kl-edit-favorite').checked=false;$('#kl-edit-status').textContent='';
}
function klOrganizeSave(){
  if(!klEditId)return;
  const patch={category:$('#kl-edit-category').value.trim(),note:$('#kl-edit-note').value.trim(),favorite:$('#kl-edit-favorite').checked};
  let err;try{err=Android.kluisOrganize(klEditId,JSON.stringify(patch));}catch(e){err='Document ordenen lukt niet';}
  if(err){klRender();$('#kl-edit-status').textContent=err;toast(err);return;}
  klOrganizeClose();klRender();toast('Documentgegevens opgeslagen');
}
function klFavorite(id){
  const d=(klRender.docs||[]).find(d=>d.id===id);if(!d)return;
  let err;try{err=Android.kluisOrganize(id,JSON.stringify({favorite:!d.favorite}));}catch(e){err='Favoriet wijzigen lukt niet';}
  if(err)toast(err);klRender();
}
