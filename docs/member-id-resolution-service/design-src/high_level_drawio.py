#!/usr/bin/env python3
"""Writes ../member-id-resolution-service-high-level.drawio: the one-slide diagram to present to UM vendors.

One message, in plain words (owner feedback 18 and 19): before a prior-authorization request reaches the vendor,
Point32Health checks the member ID, so the vendor receives it in the format its system stores. Four boxes, three
arrows, one example sentence. No technical detail, no internal system names, no other vendor's name, no codes or
ID patterns.

The output is plain, uncompressed draw.io XML, which Lucidchart imports (File > Import > draw.io) as well as
draw.io / diagrams.net. Every cell sits on the default layer with absolute coordinates and only basic shapes are
used, so the import stays clean.

Run:  python3 high_level_drawio.py
It refuses to write the file if two boxes overlap, if an id-shaped value or an ID pattern slipped in, or if a label
carries a word not meant for a vendor (an internal system name, a technical term, another vendor's name).
"""
import os
import re
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', 'member-id-resolution-service-high-level.drawio'))

INK, MUTED, NAVY = '#1A2430', '#5A6673', '#123F55'
FONT = 'fontFamily=Helvetica;'
NOT_FOR_VENDORS = re.compile(r'\b(MMI|Master Member|Azure|API Management|APIM|Spring|Java|port|9090|yaml|REST|HTTP|'
                             r'POST|JSON|traceId|stub|timeouts?|eviCore|Evolent|Carelon|MHK|MedHOK|Optum|fqa|pqa|prod)\b'
                             r'|#', re.IGNORECASE)

cells = []
geo = {}


def box(fill, stroke, extra=''):
    return ('rounded=1;whiteSpace=wrap;html=1;arcSize=10;' + FONT + 'fontSize=19;'
            f'fontColor={INK};fillColor={fill};strokeColor={stroke};strokeWidth=2;' + extra)


def text(extra=''):
    return ('text;html=1;strokeColor=none;fillColor=none;whiteSpace=wrap;rounded=0;' + FONT
            + f'fontColor={INK};align=left;verticalAlign=middle;' + extra)


def vertex(cid, value, x, y, w, h, style):
    geo[cid] = (x, y, w, h)
    cells.append(dict(kind='v', id=cid, value=value, style=style))


def rel(cid, px, py):
    x, y, w, h = geo[cid]
    return round((px - x) / w, 4), round((py - y) / h, 4)


def edge(cid, src, tgt, exit_pt, entry_pt, value, both=False, width=2.5, label_v='middle'):
    ex, ey = rel(src, *exit_pt)
    nx, ny = rel(tgt, *entry_pt)
    style = ('edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=block;'
             f'endFill=1;strokeColor={INK};strokeWidth={width};' + FONT + f'fontSize=17;fontColor={INK};'
             f'labelBackgroundColor=#FFFFFF;verticalAlign={label_v};'
             f'exitX={ex};exitY={ey};exitDx=0;exitDy=0;entryX={nx};entryY={ny};entryDx=0;entryDy=0;')
    if both:
        style += 'startArrow=block;startFill=1;'
    cells.append(dict(kind='e', id=cid, value=value, style=style, src=src, tgt=tgt))


vertex('title', 'Member ID resolution for prior authorization', 80, 48, 1440, 44, text('fontSize=34;fontStyle=1;'))
vertex('message', 'Before a prior-authorization request reaches you, Point32Health checks the member ID '
       'and sends it in the format your system stores.', 80, 100, 1440, 64, text(f'fontSize=20;fontColor={MUTED};'))

vertex('provider', '<b>Provider</b><br>types the member ID<br>from the member\'s card', 80, 240, 300, 160,
       box('#F5F5F5', '#666666'))
vertex('onyx', '<b>Onyx</b><br>routes prior-authorization<br>requests for Point32Health', 650, 240, 300, 160,
       box('#DAE8FC', '#6C8EBF'))
vertex('vendor', '<b>UM vendor</b><br>your system', 1220, 240, 300, 160, box('#FFF2CC', '#D6B656', 'strokeWidth=3;'))
vertex('service', '<b>Member ID Resolution Service</b><br>Point32Health<br>finds the member and returns<br>'
       'the member ID in your format', 580, 530, 440, 170, box('#FFFFFF', NAVY, 'strokeWidth=3;'))

vertex('example', '<b>Example.</b> The provider types the 9-character number printed on the card. You receive the '
       'member\'s full ID, with the suffix, exactly as your system stores it.', 80, 760, 1440, 64,
       text('fontSize=19;'))

edge('typed', 'provider', 'onyx', (380, 320), (650, 320), 'Request with the<br>member ID <b>as typed</b>', label_v='bottom')
edge('check', 'onyx', 'service', (800, 400), (800, 530), 'checks the<br>member ID', both=True)
edge('formatted', 'onyx', 'vendor', (950, 320), (1220, 320), 'Request with the<br>member ID <b>in your format</b>', width=3.5,
     label_v='bottom')


def overlap(a, b):
    ax, ay, aw, ah = geo[a]
    bx, by, bw, bh = geo[b]
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


ids = list(geo)
for i, a in enumerate(ids):
    for b in ids[i + 1:]:
        if overlap(a, b):
            raise SystemExit(f'boxes overlap: {a} and {b}')
for c in cells:
    hit = NOT_FOR_VENDORS.search(c['value'])
    if hit:
        raise SystemExit(f'"{hit.group(0)}" in {c["id"]}: this diagram is for vendors')

mxfile = ET.Element('mxfile', host='app.diagrams.net', modified='2026-10-05T00:00:00.000Z',
                    agent='high_level_drawio.py', version='24.7.17', type='device')
diagram = ET.SubElement(mxfile, 'diagram', id='mirs-vendor-overview', name='Member ID resolution')
model = ET.SubElement(diagram, 'mxGraphModel', dx='1600', dy='860', grid='1', gridSize='10', guides='1',
                      tooltips='1', connect='1', arrows='1', fold='1', page='1', pageScale='1',
                      pageWidth='1600', pageHeight='860', math='0', shadow='0')
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

if re.search(r'[A-Za-z]\d{8}(?!\d)|(?<!\d)\d{9}(?!\d)|(?<!\d)\d{11}(?!\d)', xml):
    raise SystemExit('an id-shaped value is in the diagram')

with open(OUT, 'w', encoding='utf-8') as f:
    f.write(xml)
print(f'wrote {OUT}: {sum(1 for c in cells if c["kind"] == "v")} shapes, {sum(1 for c in cells if c["kind"] == "e")} arrows')
