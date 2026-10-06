import os
HERE = os.path.dirname(os.path.abspath(__file__))
import re
p1=open(os.path.join(HERE, 'part1.html')).read()
head=p1[:p1.index('</style>')]
head=head.replace('<meta name="description" content="Design for the Spring Boot service that resolves an EMR-supplied member ID through MMI, formats it for the UM vendor, and reports coverage status on the date of service.">',
 '<meta name="description" content="Design v0.2, matching the delivered code: the Spring Boot service that resolves an EMR-supplied member ID through MMI, formats it for the UM vendor and reports coverage on the date of service.">')
head += """
.cardrow { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; margin: 16px 0 10px; }
.idcard { aspect-ratio: 1.586; max-width: 100%; border: 1px solid var(--line); border-radius: 12px; background: linear-gradient(135deg, var(--surface), var(--accent-soft)); padding: 14px 16px; display: flex; flex-direction: column; justify-content: space-between; min-width: 0; }
.idcard .plan { font-family: var(--font-display); font-weight: 700; font-size: 13px; letter-spacing: .04em; text-transform: uppercase; color: var(--accent); }
.idcard .name { font-size: 12px; letter-spacing: .12em; color: var(--muted); }
.idcard .lbl { font-size: 11px; text-transform: uppercase; letter-spacing: .08em; color: var(--muted); }
.idcard .id { font-family: var(--font-mono); font-size: 22px; font-weight: 600; letter-spacing: .06em; }
.idcard .fine { font-size: 10px; color: var(--muted); font-style: italic; }
</style>
"""
fig1 = p1[p1.index('<figure>'):p1.index('</figure>')+len('</figure>')]
fig1 = fig1.replace('the service returns an outcome with canonical and vendor-formatted IDs','the service returns an outcome with the stored and vendor-formatted IDs')
fig1 = fig1.replace('parse · select · coverage · format','pass through · select · coverage · format')
fig1 = fig1.replace('normalized ID, void = N','ID as typed, void = N')
fig1 = fig1.replace('vendorFormatted only, when ACTIVE','forVendor only, when ACTIVE')
fig1 = fig1.replace('Onyx posts member ID, date of service and vendor to the Interop Resolution Service','Onyx posts the member ID and the date of service to the Interop Resolution Service')
fig1 = fig1.replace('memberId, DOS, vendor','memberId, date of service')
fig1 = fig1.replace('forVendor only, when ACTIVE','each vendor its vendorMemberIds entry, when ACTIVE')
fig1 = fig1.replace('>Member ID Resolution<','>Interop Resolution<')
p3=open(os.path.join(HERE, 'part3.html')).read()
grid = p3[p3.index('<div class="idgrid"'):]
grid = grid[:grid.index('</div>\n<p class="small">')+len('</div>\n')]
grid = grid.replace('MMI canonical form; what Optum / MHK want (sample)','as stored in MMI; what MHK receives')
grid = grid.replace('what eviCore / Carelon want (sample)','what eviCore, Evolent, Carelon and Onyx receive')
grid = grid.replace('prefix + digits; digit count to be confirmed (default 9)','prefix + 9 digits; the card prints a hyphen after HP')
grid = grid.replace('policy / subscriber number: identifies a family, not a person', 'what the TMP / SCO card prints and what Optum receives; one person per number (in Public Plans the same 9 digits name a family)')
grid = grid.replace('Three rows of character cells showing the same THP member id: as stored, 14 characters, 9 digits then 3 spaces then the 2-digit suffix; compact, 11 characters, 9 digits then suffix; card, 9 digits only.', 'Four rows of character cells. Three show the same THP member id: as stored, 14 characters, 9 digits then 3 spaces then the 2-digit suffix; compact, 11 characters, 9 digits then suffix; card, 9 digits only. The fourth shows an HPHC id: HP then 9 digits.')
grid += '<p class="small">Digits 1–9 are the <em>core</em> (policy number). Suffix <code>01</code> is the subscriber; dependents, where they exist, have <code>02</code>, <code>03</code>… The three spaces are literal characters in the stored value.</p>\n'
v1=open(os.path.join(HERE, 'v2part1.html')).read().replace('<!--FIGURE1-->', fig1)
v2=open(os.path.join(HERE, 'v2part2.html')).read().replace('<!--IDGRID-->', grid)
v3=open(os.path.join(HERE, 'v2part3.html')).read()
open(os.path.join(HERE, 'artifact_v2.html'),'w').write(head + v1 + v2 + v3)
standalone = '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n' + head + '</head>\n<body>\n' + v1 + v2 + v3 + '</body>\n</html>\n'
open(os.path.join(HERE, '..', 'design.html'),'w').write(standalone)
from html.parser import HTMLParser
class P(HTMLParser):
    def __init__(s): super().__init__(); s.stack=[]; s.errs=0
    def handle_starttag(s,t,a):
        if t not in ('meta','link','br','img','input','hr'): s.stack.append(t)
    def handle_endtag(s,t):
        if t in ('meta','link','br','img','input','hr'): return
        if s.stack and s.stack[-1]==t: s.stack.pop()
        else: s.errs+=1
p=P(); p.feed(standalone); print('tag errors', p.errs, 'unclosed', p.stack)
