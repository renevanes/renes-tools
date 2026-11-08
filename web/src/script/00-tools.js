/* Toolregister: naam, kleur en symbool. Nieuwe schermtools kunnen standaard als losse app draaien. */
const TOOLS = {
  ereader: ['E-reader', '#2E6B3F', '📖'],
  masterdata: ['Stamgegevens', '#2E6B3F', '🔐', { backup: 'hoog', adapter: 'masterdata' }],
  car: ['Mijn auto', '#1F6F5C', '🚗'], kluis: ['Kluis', '#4B5563', '🔒'], pdf: ['PDF', '#B03A2E', '📄'],
  recorder: ['Gesprekken opnemen', '#A93226', '🎙'], sleep: ['Slaappraat', '#283593', '🌙'], checkin: ['Check-in', '#1E5AA8', '🙂'], apparaten: ['Apparaten', '#00796B', '📡'],
  gebruik: ['Gebruik', '#5E35B1', '📊'], facturen: ['Facturen', '#0B6E99', '🧾'], bank: ['Bankzaken', '#2E6B3F', '💶', { backup: 'hoog', adapter: 'bank' }],
  history: ['Meldingsgeschiedenis', '#B9770E', '🔔'],
  radio: ['Radio', '#D35400', '📻'], podcasts: ['Podcasts', '#6C3483', '🎧'], spotify: ['Spotify-backup', '#1DB954', '🟢'], vpn: ['VPN', '#1E8449', '🔑'], loc: ['Locatie kiezen', '#B03A2E', '📍'], wc: ['Toiletten en douches', '#00838F', '🚻'], music: ['Muziek herkennen', '#2471A3', '🎵'], ai: ['Assistent', '#5B4BB7', '✨'], notes: ['Notities', '#E67E22', '📝'],
  redial: ['Auto redial', '#1E5AA8', '🔁'], calls: ['Oproepen', '#16A085', '📞'], contacts: ['Contacten', '#2C3E50', '👤'],
  sms: ['SMS-backup', '#8E44AD', '💬'], wa: ['WhatsApp backup', '#25A35A', '💾'], tracks: ['Mijn routes', '#C0392B', '📍'],
  transcripts: ['Gesprekken uitschrijven', '#7D3C98', '🗒'], backup: ['Alles back-uppen', '#34495E', '🗄'],
  skin: ['Telefoon-skin', '#6D28D9', '📱'], auto: ['Automatiseringen', '#0E7C86', '⚡'],
  player: ['Verder luisteren', '#6C3483', '▶'] // geen tegel: de speler (radio of podcast) gaat verder waar hij was
};
const SOLO_TOOLS = Object.keys(TOOLS).filter(k => k !== 'skin'); // de telefoon-skin heeft HomeActivity
