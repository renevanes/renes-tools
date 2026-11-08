#!/usr/bin/env node
/* Het tooloverzicht is ook het register voor losse apps. Nieuwe schermtools doen vanzelf mee. */
'use strict';
const fs = require('fs'), path = require('path'), vm = require('vm');
const ROOT = path.join(__dirname, '..');
function readTools(source = fs.readFileSync(path.join(ROOT, 'web/src/script/00-tools.js'), 'utf8')) {
  const tools = vm.runInNewContext(source + '\nTOOLS;', Object.create(null), { timeout: 1000 });
  for (const [id, value] of Object.entries(tools)) {
    if (!/^[a-z][a-z0-9]*$/.test(id) || !Array.isArray(value) || value.length < 3 || value.slice(0, 3).some(s => typeof s !== 'string' || !s)) throw new Error('Ongeldige tool: ' + id);
  }
  return tools;
}
function soloTools(tools) { return Object.keys(tools).filter(id => id !== 'skin'); } // de telefoon-skin heeft al HomeActivity
function javaSource(tools) {
  return 'package nl.rene.tools;\n\n/** Gegenereerd uit het tooloverzicht; niet met de hand wijzigen. */\n' +
    'final class ToolRegister {\n    private ToolRegister() { }\n    static final java.util.Set<String> SOLO = ' +
    'java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(java.util.Arrays.asList(\n        ' +
    soloTools(tools).map(id => JSON.stringify(id)).join(', ') + '\n    )));\n}\n';
}
if (require.main === module) {
  const tools = readTools();
  if (process.argv[2] === '--json') process.stdout.write(JSON.stringify(soloTools(tools)) + '\n');
  else if (process.argv.length === 3) fs.writeFileSync(process.argv[2], javaSource(tools));
  else throw new Error('Gebruik: node tools/tool-register.js <Java-bestand> of --json');
}
module.exports = { readTools, soloTools, javaSource };
