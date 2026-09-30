#!/usr/bin/env bash
# Pakt de ondertekeningssleutel uit (vraagt om Rene's wachtwoordzin).
# De sleutel moet altijd dezelfde blijven, anders kan de telefoon updates niet installeren.
set -euo pipefail
cd "$(dirname "$0")"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in signing-key.enc ${PASSPHRASE:+-pass env:PASSPHRASE} | tar xz
echo "Sleutel uitgepakt."
