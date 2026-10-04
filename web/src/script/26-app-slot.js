/* ---------- App-slot ---------- */
function setInert(on){ document.querySelectorAll('.screen, #modal, #sheet').forEach(e => { e.inert = on; }); if (on && document.activeElement) document.activeElement.blur(); }
function showLock(){ setInert(true); $('#lockscr').classList.add('on'); $('#lock-msg').textContent = ''; setTimeout(() => Android.lockUnlock(), 250); }
window.onLock = function(){ if (!$('#lockscr').classList.contains('on')) showLock(); };
window.onUnlocked = function(){ $('#lockscr').classList.remove('on'); setInert(false); };
window.onLockFail = function(msg){ $('#lock-msg').textContent = msg || 'Niet ontgrendeld'; };

