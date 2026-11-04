#!/usr/bin/env python3
"""Overzicht: welke toestemmingen uit app/AndroidManifest.xml mogen in een Google Play-versie?

Alleen ter voorbereiding (zie docs/play-store.md). Verandert niets en draait niet in de tests.
Gebruik: python3 .github/scripts/play-toestemmingen.py
Afsluitcode 1 als er toestemmingen in het manifest staan die hieronder nog niet zijn ingedeeld.
"""
import re
import sys
from pathlib import Path

# Eerste inschatting van het Play-beleid (november 2026); de Play Console heeft het laatste woord.
# ok = mag; verklaring = formulier in de Play Console; streng = alleen voor een smalle groep apps; niet = mag niet.
INDELING = {
    'READ_SMS': 'streng',
    'READ_CALL_LOG': 'streng',
    'REQUEST_INSTALL_PACKAGES': 'niet',
    'USE_EXACT_ALARM': 'niet',
    'BIND_DEVICE_ADMIN': 'niet',
    'BIND_ACCESSIBILITY_SERVICE': 'verklaring',
    'ACCESS_BACKGROUND_LOCATION': 'verklaring',
    'MANAGE_EXTERNAL_STORAGE': 'verklaring',
    'SCHEDULE_EXACT_ALARM': 'verklaring',
    'FOREGROUND_SERVICE_SPECIAL_USE': 'verklaring',
    'FOREGROUND_SERVICE_DATA_SYNC': 'verklaring',
    'FOREGROUND_SERVICE_LOCATION': 'verklaring',
    'FOREGROUND_SERVICE_MEDIA_PLAYBACK': 'verklaring',
    'FOREGROUND_SERVICE_MICROPHONE': 'verklaring',
    'BIND_VPN_SERVICE': 'verklaring',
    'PACKAGE_USAGE_STATS': 'verklaring',
    'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS': 'verklaring',
    'BIND_NOTIFICATION_LISTENER_SERVICE': 'ok',
    'SYSTEM_ALERT_WINDOW': 'ok',
    'ACCESS_MOCK_LOCATION': 'ok',
    'ACCESS_COARSE_LOCATION': 'ok',
    'ACCESS_FINE_LOCATION': 'ok',
    'ACCESS_NETWORK_STATE': 'ok',
    'ACCESS_WIFI_STATE': 'ok',
    'CHANGE_WIFI_STATE': 'ok',
    'NEARBY_WIFI_DEVICES': 'ok',
    'BLUETOOTH': 'ok',
    'BLUETOOTH_ADMIN': 'ok',
    'BLUETOOTH_CONNECT': 'ok',
    'BLUETOOTH_SCAN': 'ok',
    'BIND_JOB_SERVICE': 'ok',
    'BIND_QUICK_SETTINGS_TILE': 'ok',
    'BIND_REMOTEVIEWS': 'ok',
    'CALL_PHONE': 'ok',
    'CAMERA': 'ok',
    'EXPAND_STATUS_BAR': 'ok',
    'FOREGROUND_SERVICE': 'ok',
    'INTERNET': 'ok',
    'POST_NOTIFICATIONS': 'ok',
    'READ_CALENDAR': 'ok',
    'READ_CONTACTS': 'ok',
    'WRITE_CONTACTS': 'ok',
    'READ_EXTERNAL_STORAGE': 'ok',
    'WRITE_EXTERNAL_STORAGE': 'ok',
    'READ_PHONE_STATE': 'ok',
    'RECEIVE_BOOT_COMPLETED': 'ok',
    'RECORD_AUDIO': 'ok',
    'USE_BIOMETRIC': 'ok',
    'VIBRATE': 'ok',
    'WAKE_LOCK': 'ok',
}
VOLGORDE = ['niet', 'streng', 'verklaring', 'ok']


def main():
    root = Path(__file__).resolve().parents[2]
    manifest = (root / 'app' / 'AndroidManifest.xml').read_text(encoding='utf-8')
    gevonden = sorted(set(re.findall(r'android\.permission\.([A-Z_]+)', manifest)))
    groepen = {k: [] for k in VOLGORDE}
    onbekend = []
    for p in gevonden:
        (groepen[INDELING[p]] if p in INDELING else onbekend).append(p)
    for k in VOLGORDE:
        print(f'{k} ({len(groepen[k])}): ' + (', '.join(groepen[k]) or '-'))
    if onbekend:
        print(f'\nNog niet ingedeeld ({len(onbekend)}): ' + ', '.join(onbekend))
        print('Deel ze in, hier en in docs/play-store.md.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
