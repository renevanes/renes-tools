#!/usr/bin/env python3
"""Eén functielijst voor de gewone Java-bouw, de Play-bouw en het Play-manifest."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def profile():
    p = json.loads((ROOT / 'play/basis.json').read_text(encoding='utf-8'))
    if not p['tools'] or any(not re.fullmatch('[a-z]+', t) for t in p['tools']):
        raise ValueError('Ongeldige toolnamen in het Play-profiel')
    return p


if __name__ == '__main__':
    out = Path(sys.argv[1])
    tools = ', '.join(json.dumps(t) for t in profile()['tools'])
    out.write_text('package nl.rene.tools;\nfinal class PlayFeatures {\n'
                   '    static final String[] TOOLS = {' + tools + '};\n}\n', encoding='utf-8')
