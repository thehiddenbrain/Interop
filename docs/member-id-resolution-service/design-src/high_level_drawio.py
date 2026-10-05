#!/usr/bin/env python3
"""Writes ../member-id-resolution-service-high-level.drawio, the one-page high-level diagram of the service.

The output is plain, uncompressed draw.io XML, which Lucidchart imports (File > Import > draw.io) as well as
draw.io / diagrams.net itself. To keep that import clean, every cell sits on the default layer with absolute
coordinates (no groups, no nested containers), and only basic shapes are used: rounded rectangles, text and
straight orthogonal arrows. Member ids appear only as masked patterns (X = letter, # = digit).

Run:  python3 high_level_drawio.py
The script refuses to write the file if two boxes overlap by mistake or if an id-shaped value slipped in.
"""
import os
import re
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', 'member-id-resolution-service-high-level.drawio'))

INK, MUTED, NAVY = '#1A2430', '#5A6673', '#123F55'
FONT = 'fontFamily=Helvetica;'

cells = []      # in drawing order: earlier cells sit behind later ones
geo = {}        # vertex id -> (x, y, w, h)
CONTAINERS = {'boundary', 'svc', 'vendors', 'outcomes'}   # boxes that are meant to hold other boxes


def box(fill, stroke, extra=''):
    return ('rounded=1;whiteSpace=wrap;html=1;arcSize=8;' + FONT + 'fontSize=12;'
            f'fontColor={INK};fillColor={fill};strokeColor={stroke};' + extra)


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


def edge(cid, src, tgt, exit_pt, entry_pt, value='', color=INK, dashed=False, label_v='middle', pos=None):
    ex, ey = rel(src, *exit_pt)
    nx, ny = rel(tgt, *entry_pt)
    style = ('edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=block;'
             f'endFill=1;strokeColor={color};strokeWidth=1.5;' + FONT + f'fontSize=11;fontColor={INK};'
             f'labelBackgroundColor=#FFFFFF;verticalAlign={label_v};'
             f'exitX={ex};exitY={ey};exitDx=0;exitDy=0;entryX={nx};entryY={ny};entryDx=0;entryDy=0;')
    if dashed:
        style += 'dashed=1;dashPattern=8 5;'
    cells.append(dict(kind='e', id=cid, value=value, style=style, src=src, tgt=tgt, pos=pos))


# ---------------------------------------------------------------- title and legend
vertex('title', 'Member ID Resolution Service · high-level architecture', 40, 20, 1300, 34,
       text('fontSize=22;fontStyle=1;'))
vertex('subtitle', 'Point32Health interop · Onyx → Member ID Resolution Service → MMI · '
       'design v0.2, owner feedback 1 to 16 · 2026-10-05', 40, 56, 1300, 22,
       text(f'fontSize=13;fontColor={MUTED};'))
vertex('legend', 'Numbers 1 to 6 follow one request.<br>Dashed arrow: Onyx\'s fallback when this service is down.',
       1450, 24, 440, 50, text(f'fontSize=12;fontColor={MUTED};align=right;'))

# ---------------------------------------------------------------- boundaries (drawn first, behind)
vertex('boundary', 'Point32Health internal network · MMI is internal: no response ever names it',
       975, 100, 925, 650,
       'rounded=1;whiteSpace=wrap;html=1;arcSize=2;fillColor=none;strokeColor=#5A6673;dashed=1;dashPattern=8 6;'
       + FONT + f'fontSize=12;fontStyle=2;fontColor={MUTED};align=left;verticalAlign=top;spacingLeft=14;spacingTop=6;')
vertex('svc', '<b>Member ID Resolution Service</b><br>Spring Boot 4.0.7 · Java 17+ · port 9090 · stateless, no database',
       1000, 140, 520, 590,
       'rounded=1;whiteSpace=wrap;html=1;arcSize=3;fillColor=#EAF2F6;strokeColor=#123F55;strokeWidth=2;'
       + FONT + f'fontSize=13;fontColor={NAVY};align=center;verticalAlign=top;spacingTop=8;')
vertex('vendors', '<b>UM vendors</b> · receive only the vendor-formatted ID', 40, 430, 440, 250,
       'rounded=1;whiteSpace=wrap;html=1;arcSize=4;fillColor=#FAFAFA;strokeColor=#666666;'
       + FONT + f'fontSize=12;fontColor={INK};align=left;verticalAlign=top;spacingLeft=10;spacingTop=6;')

# ---------------------------------------------------------------- the request path
vertex('emr', '<b>Provider EMR</b><br>member ID as typed,<br>any length or shape', 40, 205, 150, 90,
       box('#F5F5F5', '#666666'))
vertex('onyx', '<b>Onyx</b><br>prior-authorization intake<br>routes the auth to the UM vendor<br><i>the only caller</i>',
       290, 185, 190, 130, box('#DAE8FC', '#6C8EBF'))
vertex('apim', '<b>Azure API Management</b><br>tokens and certificates<br><i>security lives here,<br>not in the service</i>',
       760, 200, 160, 100, box('#E1D5E7', '#9673A6', 'fontSize=11;'))

# inside the service
vertex('rest', '<b>REST API</b> · POST, JSON<br>/api/v1/member-ids/resolve · one vendor, strict<br>'
       '/api/v1/member-ids/vendor-map · every vendor, lenient', 1020, 205, 480, 90, box('#FFFFFF', NAVY))
steps = [
    ('validate', '<b>Validate the request</b><br>member ID: presence only, never reshaped'),
    ('ask', '<b>Ask MMI once</b><br>the ID exactly as received'),
    ('select', '<b>Select the member</b><br>DOB picks one; several people → AMBIGUOUS'),
    ('cover', '<b>Coverage on the date of service</b><br>active flag + covering period'),
    ('fmt', '<b>Format the stored ID</b><br>for one vendor, or for every vendor'),
]
for i, (cid, label) in enumerate(steps):
    vertex(cid, label, 1020, 320 + 72 * i, 280, 52, box('#FFFFFF', NAVY, 'fontSize=11;'))
vertex('client', '<b>MMI client</b><br>timeouts 2 s / 5 s, no retry<br>dev profile: in-process stub',
       1318, 380, 182, 76, box('#FFFFFF', NAVY, 'fontSize=11;'))
vertex('table', '<b>Vendor format table</b><br>application.yaml<br>one block per vendor',
       1318, 596, 182, 76, box('#FFFFFF', NAVY, 'fontSize=11;'))
vertex('svc_footer', 'Masked logs (last 4 characters) · correlation id in header and logs · '
       'health and info endpoints only', 1020, 672, 480, 46, text(f'fontSize=11;fontColor={MUTED};align=center;'))

# MMI
vertex('mmi', '<b>MMI · Master Member Index</b><br>POST /master/member/v1<br>returns members and coverage spans<br>'
       '404 = no member for this ID', 1640, 352, 240, 130, box('#D5E8D4', '#82B366', 'fontSize=11;'))
vertex('profiles', '<b>Which MMI</b> (Spring profile)<br>dev: in-process stub<br>fqa · pqa (default) · pqa-lite · prod',
       1640, 500, 240, 100, box('#F7FBF6', '#82B366', 'fontSize=11;'))

# UM vendors
vendor_boxes = [
    ('v_evicore', '<b>eviCore</b><br>11 characters', 55, 475),
    ('v_evolent', '<b>Evolent</b><br>11 characters', 195, 475),
    ('v_carelon', '<b>Carelon</b><br>11 characters', 335, 475),
    ('v_mhk', '<b>MHK</b><br>14 characters', 55, 540),
    ('v_optum', '<b>Optum</b><br>core + suffix, two fields', 195, 540),
]
for cid, label, x, y in vendor_boxes:
    vertex(cid, label, x, y, 130, 52, box('#F5F5F5', '#666666', 'fontSize=11;'))
vertex('v_next', '<i>next vendor</i><br>one YAML block', 335, 540, 130, 52,
       box('#FFFFFF', '#999999', f'fontSize=11;fontColor={MUTED};dashed=1;'))
vertex('vendors_footer', 'Public Plans and HPHC IDs reach every vendor as stored.<br>'
       'Dashed arrow: if this service is down, Onyx sends the EMR\'s ID as received.',
       55, 602, 410, 68, text('fontSize=11;'))

# what goes in, what comes back
vertex('req_note', '<b>2. Request</b> · POST, JSON<br>memberId: required, as typed<br>dateOfService: optional, today if none<br>'
       'vendor: /resolve only<br>patient.dateOfBirth: optional', 495, 330, 250, 92,
       box('#FFFFFF', '#6C8EBF', 'fontSize=11;align=left;verticalAlign=top;spacingLeft=10;spacingTop=6;'))
vertex('resp_note', '<b>5. Response</b> · HTTP 200<br>outcome + one-sentence message<br>memberId: received, stored, forVendor<br>'
       '(vendor-map: vendorMemberIds[])<br>lineOfBusiness · dateOfService<br>coverage: active + covering period<br>'
       'candidates[] when AMBIGUOUS<br>traceId; never names MMI', 495, 438, 250, 130,
       box('#FFFFFF', '#6C8EBF', 'fontSize=11;align=left;verticalAlign=top;spacingLeft=10;spacingTop=6;'))

# ---------------------------------------------------------------- bottom band
panel = box('#FFFFFF', '#A0AAB4', 'align=left;verticalAlign=top;spacingLeft=14;spacingTop=10;')
panel13 = panel + 'fontSize=13;'
vertex('ids', '<b>One TMP / SCO member ID, masked</b> (X = letter, # = digit)<br>'
       'Card: X######## · 9 characters<br>'
       'Stored in MMI: X######## + three spaces + 01 · 14 characters<br>'
       'COMPACT_11: X########01 · eviCore, Evolent, Carelon, Onyx<br>'
       'SPACED_14: X######## + three spaces + 01 · MHK<br>'
       'SPLIT: X######## and 01 as two fields · Optum<br>'
       'Public Plans (11 continuous) and HPHC (HP + 9 digits): as stored, every vendor',
       40, 790, 610, 185, panel13)
vertex('plain', '<b>Deliberately plain</b><br>'
       'One MMI call per request; MMI is the only integration<br>'
       'No database, cache or queue; stateless<br>'
       'No retry or circuit breaker: timeouts, then a clear 503<br>'
       'No security code: Azure API Management owns it<br>'
       'A vendor change: one YAML block and a deploy',
       670, 790, 410, 185, panel13)
vertex('outcomes', '<b>What Onyx does with each answer</b>', 1100, 790, 790, 185, panel)
pills = [
    ('ACTIVE', '#D5E8D4', '#82B366', '#2D7A4F', 'Send forVendor (or the vendorMemberIds entry) to the vendor'),
    ('INACTIVE', '#FFF2CC', '#D6B656', '#8A6100', 'Hold for intake; the message says why (ended, not yet effective, gap)'),
    ('NOT_FOUND', '#F5F5F5', '#666666', '#444444', 'Member-not-found worklist; one resend with a fuller ID if Onyx has one'),
    ('AMBIGUOUS', '#DAE8FC', '#6C8EBF', '#2F5D8F', 'Resend with the date of birth or the full ID; else intake picks a candidate'),
    ('400 · 422', '#F8CECC', '#B85450', '#8F2F33', 'Never retry; route by error code (422: the date of birth matches no record)'),
    ('502 · 503', '#FFE6CC', '#D79B00', '#8A5A00', 'Retry later (Retry-After 10). No answer at all: pass the EMR\'s ID through'),
]
for i, (label, fill, stroke, ink, action) in enumerate(pills):
    y = 820 + 26 * i
    vertex(f'pill_{i}', label, 1115, y, 110, 22,
           f'rounded=1;arcSize=50;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};'
           + FONT + f'fontSize=10;fontStyle=1;fontColor={ink};')
    vertex(f'pill_text_{i}', action, 1235, y - 1, 640, 24, text('fontSize=11;'))

# ---------------------------------------------------------------- arrows (drawn last, on top)
edge('e1', 'emr', 'onyx', (190, 250), (290, 250), '1. prior-auth<br>request')
edge('e2', 'onyx', 'apim', (480, 237), (760, 237), '2. request', label_v='bottom')
edge('e3', 'apim', 'rest', (920, 237), (1020, 237))
edge('e4', 'rest', 'apim', (1020, 263), (920, 263))
edge('e5', 'apim', 'onyx', (760, 263), (480, 263), '5. response', label_v='top')
edge('e6', 'rest', 'validate', (1160, 295), (1160, 320), color=NAVY)
for a, b in zip(['validate', 'ask', 'select', 'cover'], ['ask', 'select', 'cover', 'fmt']):
    ya = geo[a][1] + geo[a][3]
    edge(f'e_{a}_{b}', a, b, (1160, ya), (1160, geo[b][1]), color=NAVY)
edge('e_ask_client', 'ask', 'client', (1300, 418), (1318, 418), color=NAVY)
edge('e3_mmi', 'client', 'mmi', (1500, 405), (1640, 405), '3. search', label_v='bottom')
edge('e4_mmi', 'mmi', 'client', (1640, 432), (1500, 432), '4. records', label_v='top')
edge('e_table_fmt', 'table', 'fmt', (1318, 634), (1300, 634), color=NAVY, dashed=True)
edge('e6_vendor', 'onyx', 'vendors', (445, 315), (445, 430), '6. formatted ID', pos=0.5)
edge('e_fallback', 'onyx', 'vendors', (395, 315), (395, 430), 'fallback', color='#9A6A00', dashed=True, pos=-0.5)


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

mxfile = ET.Element('mxfile', host='app.diagrams.net', modified='2026-10-05T00:00:00.000Z',
                    agent='high_level_drawio.py', version='24.7.17', type='device')
diagram = ET.SubElement(mxfile, 'diagram', id='mirs-high-level', name='High-level architecture')
model = ET.SubElement(diagram, 'mxGraphModel', dx='1920', dy='1000', grid='1', gridSize='10', guides='1',
                      tooltips='1', connect='1', arrows='1', fold='1', page='1', pageScale='1',
                      pageWidth='1920', pageHeight='1000', math='0', shadow='0')
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
        g = ET.SubElement(cell, 'mxGeometry', relative='1', **{'as': 'geometry'})
        if c['pos'] is not None:
            g.set('x', str(c['pos']))
ET.indent(mxfile, space='  ')
xml = ET.tostring(mxfile, encoding='unicode') + '\n'

# a member id is never written: no letter + 8 digits, no run of 9 or 11 digits
if re.search(r'[A-Za-z]\d{8}(?!\d)|(?<!\d)\d{9}(?!\d)|(?<!\d)\d{11}(?!\d)', xml):
    raise SystemExit('an id-shaped value is in the diagram; mask it')

with open(OUT, 'w', encoding='utf-8') as f:
    f.write(xml)
print(f'wrote {OUT}: {sum(1 for c in cells if c["kind"] == "v")} shapes, {sum(1 for c in cells if c["kind"] == "e")} arrows')
