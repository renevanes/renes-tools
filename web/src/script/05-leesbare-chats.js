/* ---------- Leesbare chats ---------- */
function readInfo(){ try { return JSON.parse(Android.waReadInfo()); } catch(e){ return {}; } }
function renderReadable(){
  const r = readInfo();
  $('#rd-setup').style.display = r.hasKey ? 'none' : 'block';
  $('#rd-ready').style.display = r.hasKey ? 'block' : 'none';
  $('#rd-auto').classList.toggle('on', r.auto !== false);
  let f = '';
  if (r.hasKey) {
    f = r.dbFrom ? 'Chats bijgewerkt uit de backup van ' + fmtD(r.dbFrom) + '.' : '';
    if (!r.dest) f += (f ? ' ' : '') + 'Kies bovenaan een backup-map om ook HTML-bestanden te krijgen.';
  }
  $('#rd-from').textContent = f;
}
function rdSaveKey(){
  const k = $('#rd-key').value.replace(/[^0-9a-fA-F]/g, '');
  if (k.length !== 64) { toast('De sleutel moet 64 tekens hebben; je hebt er ' + k.length); return; }
  if (!JSON.parse(Android.waInfo()).filesAccess) { toast('Geef eerst toegang tot bestanden'); return; }
  toast('Sleutel controleren…');
  Android.waSetKey(k);
}
window.onWaKeyResult = function(r){
  if (r.ok) {
    $('#rd-key').value = '';
    toast(r.chats + ' chats gevonden ✓');
    renderReadable();
    show('chats');
  } else {
    toast(r.error || 'De sleutel klopt niet');
  }
};
function rdForget(){
  askConfirm('Sleutel wissen?', 'Je sleutel en de ontsleutelde chats worden uit de app verwijderd. De HTML-bestanden in je backup-map blijven staan.', 'Wissen', () => {
    Android.waForgetKey(); renderReadable(); toast('Sleutel gewist');
  });
}
function rdRefresh(){
  const err = Android.waMakeReadable();
  if (err) { toast(err); return; }
  toast('Chats bijwerken…'); setTimeout(waPollOnce, 200);
}

let chatCur = null, chatOldest = 0, chatLoading = false, chatGroup = false;
function enterChats(){
  const r = readInfo();
  $('#chat-contacts').style.display = r.contacts ? 'none' : 'block';
  $('#chat-search').value = ''; $('#search-results').innerHTML = '';
  loadChatList();
}
function loadChatList(){
  let data; try { data = JSON.parse(Android.waChats()); } catch(e){ return; }
  if (data.error) { $('#chat-list').innerHTML = '<p class=note style="padding:14px">' + esc(data.error) + '</p>'; return; }
  const el = $('#chat-list');
  el.innerHTML = (data.chats || []).map(c =>
    '<button onclick="openChat(' + c.id + ',' + jsq(c.name) + ',' + (c.group?1:0) + ')">' +
    '<span class=r1><b>' + esc(c.name) + '</b><time>' + esc(fmtD(c.last)) + '</time></span>' +
    '<span class=r2>' + (c.group ? '👥 ' : '') + esc(c.lastText || '') + ' · ' + c.n + '</span></button>').join('');
  if (!(data.chats || []).length) el.innerHTML = '<p class=note style="padding:14px">Geen chats gevonden.</p>';
}
function openChat(id, name, group){
  chatCur = id; chatOldest = 0; chatGroup = !!group;
  $('#chat-title').textContent = name;
  $('#chat-msgs').innerHTML = '';
  show('chat');
  loadMore(true);
}
function loadMore(initial){
  if (chatLoading || chatCur == null) return;
  chatLoading = true;
  let msgs; try { msgs = JSON.parse(Android.waMessages(String(chatCur), String(chatOldest), 300)); } catch(e){ chatLoading = false; return; }
  const box = $('#chat-msgs');
  const oldH = box.scrollHeight;
  const btn = $('#load-older'); if (btn) btn.remove();
  if (Array.isArray(msgs) && msgs.length) {
    chatOldest = msgs[0].t;
    const html = renderMsgs(msgs);
    box.insertAdjacentHTML('afterbegin', html);
    if (msgs.length >= 300) box.insertAdjacentHTML('afterbegin', '<button class=more id=load-older onclick="loadMore(false)">Oudere berichten</button>');
    if (initial) window.scrollTo(0, document.body.scrollHeight);
    else window.scrollTo(0, document.body.scrollHeight - oldH);
  }
  chatLoading = false;
}
function renderMsgs(msgs){
  let out = '', lastDay = '';
  for (const m of msgs) {
    const d = new Date(m.t), dd = d.toLocaleDateString('nl-NL', {weekday:'long', day:'numeric', month:'long', year:'numeric'});
    if (dd !== lastDay) { out += '<div class=dsep>' + esc(dd) + '</div>'; lastDay = dd; }
    out += '<div class="bub' + (m.me ? ' me' : '') + '">';
    // Afzender tonen in groepen; ook als de groep via zoeken is geopend (dan is de afzender een ander dan de chatnaam)
    if (!m.me && m.sender && (chatGroup || m.sender !== $('#chat-title').textContent)) out += '<span class=snd>' + esc(m.sender) + '</span>';
    if (m.media && m.mime && m.mime.indexOf('image/') === 0 && m.type !== 20)
      out += '<img loading=lazy src="https://app.renes-tools.local/wa-media/' + encMedia(m.media) + '" alt="">';
    else if (m.label && !m.text) out += '<span class=lb>' + esc(m.label) + '</span>';
    else if (m.label && m.media) out += '<span class=lb>' + esc(m.label) + '</span>\n';
    if (m.text) out += esc(m.text);
    out += '<span class=tm>' + d.toLocaleTimeString('nl-NL', {hour:'2-digit', minute:'2-digit'}) + '</span></div>';
  }
  return out;
}
function encMedia(rel){ return rel.split('/').map(s=>encodeURIComponent(s)).join('/'); }

let searchTmr = null;
function chatSearch(q){
  clearTimeout(searchTmr);
  const sr = $('#search-results'), cl = $('#chat-list');
  if (!q || q.trim().length < 2) { sr.innerHTML = ''; cl.style.display = 'block'; return; }
  searchTmr = setTimeout(() => {
    let msgs; try { msgs = JSON.parse(Android.waSearch(q.trim())); } catch(e){ return; }
    cl.style.display = 'none';
    if (msgs.error) { sr.innerHTML = '<p class=note>' + esc(msgs.error) + '</p>'; return; }
    if (!msgs.length) { sr.innerHTML = '<p class=note style="text-align:center">Niets gevonden voor "' + esc(q) + '"</p>'; return; }
    sr.innerHTML = '<p class=note>' + msgs.length + ' resultaten</p><div class="msgs">' + msgs.map(m => {
      return '<div class="bub' + (m.me ? ' me' : '') + '" onclick="openChat(' + m.chat + ',' + jsq(m.chatName||'') + ')">' +
        '<span class=snd>' + esc(m.chatName || '') + (m.sender && !m.me ? ' · ' + esc(m.sender) : '') + '</span>' +
        esc(m.text || m.label || '') + '<span class=tm>' + fmtD(m.t) + '</span></div>';
    }).join('') + '</div>';
  }, 250);
}

