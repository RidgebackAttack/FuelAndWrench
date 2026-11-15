"""Erzeugt das App-Icon (Android-Vektoren + SVG-Vorschau).
Aufruf: python3 tools/icon_gen.py [Tropfen-Skalierung, Standard 0.74]"""
import sys

S = float(sys.argv[1]) if len(sys.argv) > 1 else 0.64   # Größe Tropfen/Tacho
Z = float(sys.argv[2]) if len(sys.argv) > 2 else 1.25   # Größe Zylinder (Proportionen bleiben)
A = float(sys.argv[3]) if len(sys.argv) > 3 else 0.55   # Deckkraft des Glasrands (Kern bleibt undurchsichtig)
CX, CY = 54, 62                                          # Mittelpunkt Tropfen
RES = "app/src/main/res/drawable/"

def rr(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (f"M{x+r},{y}H{x+w-r}A{r},{r} 0 0,1 {x+w},{y+r}V{y+h-r}A{r},{r} 0 0,1 {x+w-r},{y+h}"
            f"H{x+r}A{r},{r} 0 0,1 {x},{y+h-r}V{y+r}A{r},{r} 0 0,1 {x+r},{y}Z")

def circ(cx, cy, r):
    return f"M{cx-r},{cy}A{r},{r} 0 1,0 {cx+r},{cy}A{r},{r} 0 1,0 {cx-r},{cy}Z"

# Zylinder (Boxer) in Originalgröße; per Gruppe vergrößert, Innenkante bleibt unter dem Tropfen
inner = CX - 21 * S + 2.5
rects_left = [
    (23, 58, 13, 8, 1.5),        # Laufbuchse
    (24.5, 52, 2.2, 20, 1.1),    # Kühlrippen
    (27.6, 53, 2.2, 18, 1.1),
    (30.7, 54, 2.2, 16, 1.1),
    (17, 54.5, 7, 15, 3),        # Zylinderkopf
]
rects_right = [(108 - x - w, y, w, h, r) for (x, y, w, h, r) in rects_left]
cylL = "".join(rr(*r) for r in rects_left)
cylR = "".join(rr(*r) for r in rects_right)
TX = (CX - 21 * S - 0.5) - (36 - (36 - 32.9) * Z)   # innerste Kühlrippe liegt direkt am Tropfen an
def cyl_groups(fill):
    return (f'<group android:pivotX="36" android:pivotY="62" android:scaleX="{Z}" android:scaleY="{Z}" android:translateX="{TX}">\n'
            f'<path android:pathData="{cylL}">{fill}</path>\n</group>\n'
            f'<group android:pivotX="72" android:pivotY="62" android:scaleX="{Z}" android:scaleY="{Z}" android:translateX="{-TX}">\n'
            f'<path android:pathData="{cylR}">{fill}</path>\n</group>\n')

# Tropfen + Tacho in Originalkoordinaten (Mittelpunkt 54,62), per Gruppe skaliert/verschoben
DROP = "M54,19 C54,19 33,45 33,62 A21,21 0 0,0 75,62 C75,45 54,19 54,19 Z"
DIAL = circ(54, 63, 15)
ARC = "M44.8,72.2 A13,13 0 1,1 63.2,72.2"
RED = "M58.6,58.7 A8,8 0 0,1 62,66"
NEEDLE = "M52.9,63.9 L60.6,53.6 L55.1,65.4 Z"
HUB = circ(54, 64, 2.3)
SHINE = "M50,27 C45,33 40,41 38.5,49"
SPARK = circ(39.6, 55, 1.6)

chrome = [(0, "#FFFFFFFF"), (0.5, "#FFC3CCD7"), (1, "#FF6E7C8C")]
gold = [(0, "#FFFFE29A"), (0.55, "#FFF2B544"), (1, "#FFB8741A")]

def lin(attr, x1, y1, x2, y2, stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<aapt:attr name="{attr}"><gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}">{items}</gradient></aapt:attr>')

HDR = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" '
       'android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')
GROUP = (f'<group android:pivotX="54" android:pivotY="62" android:scaleX="{S}" android:scaleY="{S}" '
         f'android:translateX="{CX-54}" android:translateY="{CY-62}">\n')

fg = HDR + f'''<path android:pathData="M12,38 L38,38 M8,44 L32,44 M70,38 L96,38 M76,44 L100,44" android:strokeColor="#1FFFFFFF" android:strokeWidth="1.6" android:strokeLineCap="round"/>
{cyl_groups(lin("android:fillColor", 0, 52, 0, 72, chrome))}
{GROUP}<path android:pathData="{DROP}" android:fillAlpha="{A}">{lin("android:fillColor", 0, 19, 0, 83, gold)}</path>
<group android:pivotX="54" android:pivotY="60" android:scaleX="0.86" android:scaleY="0.86">
<path android:pathData="{DROP}">{lin("android:fillColor", 0, 19, 0, 83, gold)}</path>
</group>
<path android:pathData="{DROP}" android:strokeColor="#FFFFF4D0" android:strokeAlpha="0.85" android:strokeWidth="1.4"/>
<path android:pathData="{DIAL}"><aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="54" android:centerY="57" android:gradientRadius="18"><item android:offset="0" android:color="#FF1B2B45"/><item android:offset="1" android:color="#FF060D1A"/></gradient></aapt:attr></path>
<path android:pathData="{ARC}" android:strokeWidth="2.2" android:strokeLineCap="round">{lin("android:strokeColor", 0, 50, 0, 73, chrome)}</path>
<path android:pathData="{RED}" android:strokeColor="#FFE63946" android:strokeWidth="2.2" android:strokeLineCap="round"/>
<path android:pathData="{NEEDLE}" android:fillColor="#FFFF4D5A"/>
<path android:pathData="{HUB}">{lin("android:fillColor", 0, 61.7, 0, 66.3, chrome)}</path>
<path android:pathData="{SHINE}" android:strokeColor="#BFFFFFFF" android:strokeWidth="3.2" android:strokeLineCap="round"/>
<path android:pathData="{SPARK}" android:fillColor="#B3FFFFFF"/>
</group>
</vector>
'''
mono = HDR + f'''{cyl_groups('<aapt:attr name="android:fillColor"><color android:color="#FFFFFFFF"/></aapt:attr>').replace('<path android:pathData','<path android:fillColor="#FFFFFFFF" android:pathData').replace('<aapt:attr name="android:fillColor"><color android:color="#FFFFFFFF"/></aapt:attr>','')}
{GROUP}<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{DROP} {DIAL}"/>
<path android:fillColor="#FFFFFFFF" android:pathData="{NEEDLE}"/>
</group>
</vector>
'''
open(RES + "ic_launcher_foreground.xml", "w").write(fg)
open(RES + "ic_launcher_monochrome.xml", "w").write(mono)

# SVG-Vorschau (mit Hintergrund und Maske wie auf dem Homescreen)
T = f'translate({CX} {CY}) scale({S}) translate(-54 -62)'
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
<defs>
<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0A1A33"/><stop offset="1" stop-color="#1E4F8C"/></linearGradient>
<linearGradient id="gold" x1="0" y1="19" x2="0" y2="83" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFE29A"/><stop offset="0.55" stop-color="#F2B544"/><stop offset="1" stop-color="#B8741A"/></linearGradient>
<linearGradient id="chrome" x1="0" y1="52" x2="0" y2="72" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.5" stop-color="#C3CCD7"/><stop offset="1" stop-color="#6E7C8C"/></linearGradient>
<linearGradient id="chrome2" x1="0" y1="50" x2="0" y2="73" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.5" stop-color="#C3CCD7"/><stop offset="1" stop-color="#6E7C8C"/></linearGradient>
<radialGradient id="dial" cx="54" cy="57" r="18" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1B2B45"/><stop offset="1" stop-color="#060D1A"/></radialGradient>
<clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath>
</defs>
<g clip-path="url(#m)">
<rect width="108" height="108" fill="url(#bg)"/>
<path d="M12,38 L38,38 M8,44 L32,44 M70,38 L96,38 M76,44 L100,44" stroke="#FFFFFF" stroke-opacity="0.12" stroke-width="1.6" stroke-linecap="round"/>
<g transform="translate({36+TX} 62) scale({Z}) translate(-36 -62)"><path d="{cylL}" fill="url(#chrome)"/></g>
<g transform="translate({72-TX} 62) scale({Z}) translate(-72 -62)"><path d="{cylR}" fill="url(#chrome)"/></g>
<g transform="{T}">
<path d="{DROP}" fill="url(#gold)" fill-opacity="{A}"/>
<g transform="translate(54 60) scale(0.86) translate(-54 -60)"><path d="{DROP}" fill="url(#gold)"/></g>
<path d="{DROP}" fill="none" stroke="#FFF4D0" stroke-opacity="0.85" stroke-width="1.4"/>
<path d="{DIAL}" fill="url(#dial)"/>
<path d="{ARC}" fill="none" stroke="url(#chrome2)" stroke-width="2.2" stroke-linecap="round"/>
<path d="{RED}" fill="none" stroke="#E63946" stroke-width="2.2" stroke-linecap="round"/>
<path d="{NEEDLE}" fill="#FF4D5A"/>
<path d="{HUB}" fill="#DDE3EA"/>
<path d="{SHINE}" stroke="#FFFFFF" stroke-opacity="0.75" stroke-width="3.2" fill="none" stroke-linecap="round"/>
<path d="{SPARK}" fill="#FFFFFF" fill-opacity="0.7"/>
</g></g></svg>'''
open("tools/icon_preview.svg", "w").write(svg)
print("ok", S)
