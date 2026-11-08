#!/usr/bin/env node
/* Het tooloverzicht is ook het register voor losse apps. Nieuwe schermtools doen vanzelf mee. */
'use strict';
const fs = require('fs'), path = require('path'), vm = require('vm');
const ROOT = path.join(__dirname, '..');
function readTools(source = fs.readFileSync(path.join(ROOT, 'web/src/script/00-tools.js'), 'utf8')) {
  const tools = vm.runInNewContext(source + '\nTOOLS;', Object.create(null), { timeout: 1000 });
  const seen = { roots: new Set(), prefs: new Set() };
  for (const [id, value] of Object.entries(tools)) {
    if (!/^[a-z][a-z0-9]*$/.test(id) || !Array.isArray(value) || value.length < 3 || value.slice(0, 3).some(s => typeof s !== 'string' || !s)) throw new Error('Ongeldige tool: ' + id);
    if (['home','settings','app','models','selftest','herstel'].includes(id)) throw new Error('Gereserveerde tool-id: '+id);
    const policy = value[3] || {};
    if (typeof policy !== 'object' || Array.isArray(policy)) throw new Error('Ongeldig toolbeleid: '+id);
    if (policy.backup && !['normaal', 'hoog'].includes(policy.backup)) throw new Error('Ongeldig backuprisico: ' + id);
    if (policy.adapter && !['bank', 'masterdata', 'bestanden'].includes(policy.adapter)) throw new Error('Onbekende backup-adapter: ' + id);
    if (policy.versie !== undefined && (!Number.isInteger(policy.versie) || policy.versie < 1)) throw new Error('Ongeldige beleidsversie: '+id);
    for (const kind of ['roots', 'prefs']) {
      if (policy[kind] && (!Array.isArray(policy[kind]) || policy[kind].some(s => !(kind === 'roots' ? /^[a-z][a-z0-9_.-]{0,99}$/ : /^[a-z][a-z0-9_-]{0,99}$/).test(s)))) throw new Error('Ongeldige opslag: ' + id);
      if (policy.backup === 'hoog') for (const name of policy[kind] || (kind === 'prefs' ? [id, id + '_geheim'] : [id])) {
        if (seen[kind].has(name)) throw new Error('Gedeelde risico-opslag: ' + name);
        seen[kind].add(name);
      }
    }
    if (policy.adapter && policy.adapter !== 'bestanden' && policy.adapter !== id) throw new Error('Backup-adapter hoort bij een andere tool: ' + id);
    if (['bank', 'masterdata'].includes(id) && (policy.backup !== 'hoog' || policy.adapter !== id)) throw new Error('Beschermde tool moet standaard uitgesloten blijven: ' + id);
  }
  return tools;
}
function soloTools(tools) { return Object.keys(tools).filter(id => id !== 'skin'); } // de telefoon-skin heeft al HomeActivity
function javaSource(tools) {
  const high = Object.entries(tools).filter(([,v]) => v[3]?.backup === 'hoog');
  const map = kind => high.flatMap(([id,v]) => {
    const p = v[3];
    if (kind === 'consent') return [[id, require('crypto').createHash('sha256').update(JSON.stringify([id,p.backup,p.adapter||'bestanden',p.versie||1,p.roots||[id],p.prefs||[id,id+'_geheim']])).digest('hex')]];
    return (kind === 'adapters' ? [id] : (p[kind] || (kind === 'prefs' ? [id,id+'_geheim'] : [id]))).map(name => [name, kind === 'adapters' ? (p.adapter || 'bestanden') : id]);
  });
  const javaMap = kind => '    static final java.util.Map<String,String> ' + kind.toUpperCase() + ' = ' + kind + '();\n' +
    '    private static java.util.Map<String,String> ' + kind + '() {\n        java.util.Map<String,String> m = new java.util.LinkedHashMap<>();\n' +
    map(kind).map(([k,v]) => '        m.put(' + JSON.stringify(k) + ', ' + JSON.stringify(v) + ');').join('\n') + '\n        return java.util.Collections.unmodifiableMap(m);\n    }\n';
  return 'package nl.rene.tools;\n\n/** Gegenereerd uit het tooloverzicht; niet met de hand wijzigen. */\n' +
    'final class ToolRegister {\n    private ToolRegister() { }\n    static final java.util.Set<String> SOLO = ' +
    'java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(java.util.Arrays.asList(\n        ' +
    soloTools(tools).map(id => JSON.stringify(id)).join(', ') + '\n    )));\n' + ['adapters', 'roots', 'prefs', 'consent'].map(javaMap).join('') + '}\n';
}
if (require.main === module) {
  const tools = readTools();
  if (process.argv[2] === '--json') process.stdout.write(JSON.stringify(soloTools(tools)) + '\n');
  else if (process.argv.length === 3) fs.writeFileSync(process.argv[2], javaSource(tools));
  else throw new Error('Gebruik: node tools/tool-register.js <Java-bestand> of --json');
}
module.exports = { readTools, soloTools, javaSource };
