# Erzeugt res/values*/strings.xml aus strings.tsv (Spalten: key, en, de, da). en ist Standard.
import os, re
ROOT = os.path.dirname(os.path.abspath(__file__)) + '/../..'
rows = []
for line in open(os.path.dirname(os.path.abspath(__file__)) + '/strings.tsv', encoding='utf-8'):
    if line.strip() and not line.startswith('#'):
        rows.append(line.rstrip('\n').split('\t'))

def esc(v):
    v = v.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    v = v.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
    v = v.replace('\\\\n', '\\n')  # \n aus der Tabelle bleibt Zeilenumbruch
    if v.startswith('@') or v.startswith('?'):
        v = '\\' + v
    return v

for col, folder in ((1, 'values'), (2, 'values-de'), (3, 'values-da')):
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Erzeugt von tools/i18n/gen.py aus strings.tsv – nicht von Hand bearbeiten -->', '<resources>']
    for r in rows:
        if len(r) != 4:
            raise SystemExit('bad row ' + repr(r))
        val = r[col] or r[1]
        out.append(f'    <string name="{r[0]}">{esc(val)}</string>')
    out.append('</resources>')
    d = f'{ROOT}/app/src/main/res/{folder}'
    os.makedirs(d, exist_ok=True)
    open(f'{d}/strings.xml', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(len(rows), 'strings')
