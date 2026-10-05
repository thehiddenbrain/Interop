#!/usr/bin/env python3
"""Writes ../member-id-resolution-service-high-level.drawio: the one-page diagram to present to UM vendors.

Audience: a UM vendor. It shows the business flow only: the provider, Onyx, the Member ID Resolution Service,
Point32Health's member records, the vendor, and how the member ID changes along the way. No technical detail
(ports, versions, endpoints, codes, infrastructure), no internal system names, and no other vendor's name, so the
same file serves every vendor. Member IDs appear only masked (X = letter, # = digit).

The output is plain, uncompressed draw.io XML, which Lucidchart imports (File > Import > draw.io) as well as
draw.io / diagrams.net. To keep that import clean, every cell sits on the default layer with absolute coordinates
(no groups, no nested containers) and only basic shapes are used.

Run:  python3 high_level_drawio.py
It refuses to write the file if two boxes overlap by mistake, if an id-shaped value slipped in, or if a label
carries a word that must not reach a vendor (an internal system name, a technical detail, another vendor's name).
"""
import os
import re
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', 'member-id-resolution-service-high-level.drawio'))

INK, MUTED, NAVY = '#1A2430', '#5A6673', '#123F55'
FONT = 'fontFamily=Helvetica;'
NOT_FOR_VENDORS = re.compile(r'\b(MMI|Master Member|Azure|API Management|APIM|Spring|Java|port|9090|yaml|REST|HTTP|'
                             r'POST|JSON|traceId|stub|timeouts?|eviCore|Evolent|Carelon|MHK|MedHOK|Optum|fqa|pqa|prod)\b',
                             re.IGNORECASE)

cells = []      # in drawing order: earlier cells sit behind later ones
geo = {}        # vertex id -> (x, y, w, h)
CONTAINERS = {'p32'}


def box(fill, stroke, extra=''):
    return ('rounded=1;whiteSpace=wrap;html=1;arcSize=10;' + FONT + 'fontSize=15;'
            f'fontColor={INK};fillColor={fill};strokeColor={stroke};strokeWidth=1.5;' + extra)


def text(extra=''):
    return ('text;html=1;strokeColor=none;fillColor=none;whiteSpace=wrap;rounded=0;' + FONT
            + f'fontColor={INK};align=left;verticalAlign=middle;' + extra)


def vertex(cid, value, x, y, w, h, style):
    geo[cid] = (x, y, w, h)
    cells.append(dict(kind='v', id=cid, value=value, style=style))


def rel(cid, px, py):
    """A point on a box, absolute -> the box's relative 0..1 coordinates (draw.io exit/entry constraints)."""
    x, y, w, h = geo[cid]
    return round((px - x) / w, 4), round((py - y) / h, 4)


def edge(cid, src, tgt, exit_pt, entry_pt, value='', color=INK, dashed=False, label_v='middle', both=False, width=2):
    ex, ey = rel(src, *exit_pt)
    nx, ny = rel(tgt, *entry_pt)
    style = ('edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=block;'
             f'endFill=1;strokeColor={color};strokeWidth={width};' + FONT + f'fontSize=13;fontColor={INK};'
             f'labelBackgroundColor=#FFFFFF;verticalAlign={label_v};'
             f'exitX={ex};exitY={ey};exitDx=0;exitDy=0;entryX={nx};entryY={ny};entryDx=0;entryDy=0;')
    if both:
        style += 'startArrow=block;startFill=1;'
    if dashed:
        style += 'dashed=1;dashPattern=8 5;'
    cells.append(dict(kind='e', id=cid, value=value, style=style, src=src, tgt=tgt))


# ---------------------------------------------------------------- title
vertex('title', 'Member ID resolution for prior-authorization requests', 60, 36, 1300, 40,
       text('fontSize=30;fontStyle=1;'))
vertex('subtitle', 'How the member ID reaches your system', 60, 84, 1300, 28, text(f'fontSize=17;fontColor={MUTED};'))

# ---------------------------------------------------------------- Point32Health (drawn first, behind)
vertex('p32', 'Point32Health', 440, 400, 720, 210,
       'rounded=1;whiteSpace=wrap;html=1;arcSize=4;fillColor=#EEF4F7;strokeColor=#123F55;strokeWidth=1.5;'
       + FONT + f'fontSize=14;fontStyle=1;fontColor={NAVY};align=left;verticalAlign=top;spacingLeft=14;spacingTop=8;')

# ---------------------------------------------------------------- who takes part
vertex('provider', '<b>Provider</b><br>enters the member ID<br>from the member\'s card', 60, 170, 280, 120,
       box('#F5F5F5', '#666666'))
vertex('onyx', '<b>Onyx</b><br>prior-authorization intake<br>for Point32Health', 660, 170, 280, 120,
       box('#DAE8FC', '#6C8EBF'))
vertex('vendor', '<b>UM vendor</b> · your system<br>receives a verified member ID,<br>in the format you store',
       1200, 170, 340, 120, box('#FFF2CC', '#D6B656', 'strokeWidth=2;'))
vertex('service', '<b>Member ID Resolution Service</b><br>verifies the member ID<br>confirms coverage on the date of service<br>'
       'formats the ID for each UM vendor', 480, 440, 380, 140, box('#FFFFFF', NAVY, 'strokeWidth=2;'))
vertex('records', '<b>Member records</b><br>members and<br>coverage', 960, 460, 170, 100, box('#D5E8D4', '#82B366', 'fontSize=14;'))

# ---------------------------------------------------------------- the two callouts beside Point32Health
callout = box('#FFFFFF', '#A0AAB4', 'fontSize=15;align=left;verticalAlign=middle;spacingLeft=16;spacingRight=12;')
vertex('why', '<b>Why</b><br>Providers enter the member ID in many forms: 9, 11 or 14 characters, with or without '
       'spaces or a hyphen. Your system stores one form. The service finds the member from what was entered and sends '
       'you the ID in the form you store.',
       60, 425, 330, 160, callout)
vertex('changes', '<b>What changes for you</b><br>• The member ID is verified against Point32Health\'s member records '
       'before the request is sent<br>• It arrives in the format your system stores<br>• Requests still reach you through Onyx',
       1200, 425, 340, 160, callout.replace('#A0AAB4', '#D6B656'))

# ---------------------------------------------------------------- the member ID along the way
vertex('journey', '<b>The member ID along the way</b> · THP Medicare (TMP) and SCO members · X is a letter, # a digit',
       60, 666, 1480, 26, text(f'fontSize=14;fontColor={NAVY};'))
vertex('j_card', '<b>On the member\'s card</b><br>X########<br>9 characters; providers may add<br>spaces, a hyphen or the suffix',
       60, 705, 280, 140, box('#FFFFFF', '#A0AAB4', 'fontSize=14;'))
vertex('j_stored', '<b>Stored by Point32Health</b><br>X######## + 3 spaces + 01<br>14 characters',
       610, 705, 380, 140, box('#FFFFFF', NAVY, 'fontSize=14;'))
vertex('j_sent', '<b>Sent to you, in your agreed format</b><br>X########01 · 11 characters<br>'
       'X######## + 3 spaces + 01 · 14 characters<br>X######## and 01 · two separate fields',
       1200, 705, 340, 140, box('#FFFFFF', '#D6B656', 'fontSize=14;strokeWidth=2;'))
vertex('footnote', 'THP Public Plans and Harvard Pilgrim (HPHC) member IDs are sent exactly as stored.',
       60, 858, 1480, 26, text(f'fontSize=14;fontColor={MUTED};'))

# ---------------------------------------------------------------- arrows (drawn last, on top)
edge('e1', 'provider', 'onyx', (340, 230), (660, 230), '1. Prior-authorization request<br>with the member ID as entered')
edge('e2', 'onyx', 'service', (690, 290), (690, 440), '2. Member ID and<br>date of service')
edge('e3', 'service', 'records', (860, 510), (960, 510), '3. Look up', label_v='bottom', both=True)
edge('e4', 'service', 'onyx', (830, 440), (830, 290), '4. Verified member ID<br>in your format,<br>coverage status')
edge('e5', 'onyx', 'vendor', (940, 215), (1200, 215), '5. Request with the member ID<br>in your format', label_v='bottom')
edge('e_fallback', 'onyx', 'vendor', (940, 265), (1200, 265), 'If verification is unavailable:<br>the member ID as entered, as today',
     color='#7A8691', dashed=True, label_v='top', width=1.5)
edge('j1', 'j_card', 'j_stored', (340, 775), (610, 775), 'verified', color=MUTED, width=1.5)
edge('j2', 'j_stored', 'j_sent', (990, 775), (1200, 775), 'formatted for you', color=MUTED, width=1.5)


# ---------------------------------------------------------------- checks, then write
def inside(inner, outer):
    ix, iy, iw, ih = geo[inner]
    ox, oy, ow, oh = geo[outer]
    return ix >= ox and iy >= oy and ix + iw <= ox + ow and iy + ih <= oy + oh


def overlap(a, b):
    ax, ay, aw, ah = geo[a]
    bx, by, bw, bh = geo[b]
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


ids = list(geo)
for i, a in enumerate(ids):
    for b in ids[i + 1:]:
        if overlap(a, b) and not ((a in CONTAINERS and inside(b, a)) or (b in CONTAINERS and inside(a, b))):
            raise SystemExit(f'boxes overlap: {a} and {b}')

for c in cells:
    hit = NOT_FOR_VENDORS.search(c['value'])
    if hit:
        raise SystemExit(f'"{hit.group(0)}" in {c["id"]}: this diagram is for vendors')

mxfile = ET.Element('mxfile', host='app.diagrams.net', modified='2026-10-05T00:00:00.000Z',
                    agent='high_level_drawio.py', version='24.7.17', type='device')
diagram = ET.SubElement(mxfile, 'diagram', id='mirs-vendor-overview', name='Member ID resolution')
model = ET.SubElement(diagram, 'mxGraphModel', dx='1600', dy='900', grid='1', gridSize='10', guides='1',
                      tooltips='1', connect='1', arrows='1', fold='1', page='1', pageScale='1',
                      pageWidth='1600', pageHeight='900', math='0', shadow='0')
root = ET.SubElement(model, 'root')
ET.SubElement(root, 'mxCell', id='0')
ET.SubElement(root, 'mxCell', id='1', parent='0')
for c in cells:
    if c['kind'] == 'v':
        cell = ET.SubElement(root, 'mxCell', id=c['id'], value=c['value'], style=c['style'], vertex='1', parent='1')
        x, y, w, h = geo[c['id']]
        ET.SubElement(cell, 'mxGeometry', x=str(x), y=str(y), width=str(w), height=str(h), **{'as': 'geometry'})
    else:
        cell = ET.SubElement(root, 'mxCell', id=c['id'], value=c['value'], style=c['style'], edge='1', parent='1',
                             source=c['src'], target=c['tgt'])
        ET.SubElement(cell, 'mxGeometry', relative='1', **{'as': 'geometry'})
ET.indent(mxfile, space='  ')
xml = ET.tostring(mxfile, encoding='unicode') + '\n'

# a member id is never written: no letter + 8 digits, no run of 9 or 11 digits
if re.search(r'[A-Za-z]\d{8}(?!\d)|(?<!\d)\d{9}(?!\d)|(?<!\d)\d{11}(?!\d)', xml):
    raise SystemExit('an id-shaped value is in the diagram; mask it')

with open(OUT, 'w', encoding='utf-8') as f:
    f.write(xml)
print(f'wrote {OUT}: {sum(1 for c in cells if c["kind"] == "v")} shapes, {sum(1 for c in cells if c["kind"] == "e")} arrows')
