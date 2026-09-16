#!/usr/bin/env python3
"""Generates src/main/resources/catalog/ig-catalog.json from the HL7 FHIR IG npm packages.

Usage: tools/generate-catalog.py <packages-dir>

<packages-dir> holds one unpacked npm package per subfolder (hl7.fhir.us.carin-bb/package/...),
plus an optional us-core-server-master.json (the US Core server CapabilityStatement). Download with:

  for p in hl7.fhir.us.carin-bb@2.1.0 hl7.fhir.us.davinci-pdex@2.1.0 hl7.fhir.us.davinci-pas@2.2.0-ballot \
           hl7.fhir.us.davinci-drug-formulary@2.1.0 hl7.fhir.uv.smart-app-launch@2.2.0; do
    name=${p%@*}; ver=${p#*@}; mkdir -p pkgs/$name
    curl -sSL "https://registry.npmjs.org/$name/-/$name-$ver.tgz" | tar -xz -C pkgs/$name
  done
  curl -sSL -o pkgs/us-core-server-6.1.0.json \
    https://raw.githubusercontent.com/HL7/US-Core/6.1.0/input/resources/capabilitystatement-us-core-server.json

The output drives the workbench's search-option catalog, profile-lite checks and conformance suite.
"""
import json
import os
import sys
from collections import OrderedDict

EXPECTATION_URL = 'http://hl7.org/fhir/StructureDefinition/capabilitystatement-expectation'
COMBO_URL = 'http://hl7.org/fhir/StructureDefinition/capabilitystatement-search-parameter-combination'

IGS = OrderedDict([
    ('c4bb', dict(package='hl7.fhir.us.carin-bb', name='CARIN IG for Blue Button (C4BB)',
                  canonical='http://hl7.org/fhir/us/carin-bb', capability='CapabilityStatement-c4bb.json')),
    ('pdex', dict(package='hl7.fhir.us.davinci-pdex', name='Da Vinci Payer Data Exchange (PDex)',
                  canonical='http://hl7.org/fhir/us/davinci-pdex', capability='CapabilityStatement-pdex-server.json')),
    ('uscore', dict(package=None, name='US Core', canonical='http://hl7.org/fhir/us/core',
                    capability='us-core-server-6.1.0.json')),
    ('usdf', dict(package='hl7.fhir.us.davinci-drug-formulary', name='Da Vinci US Drug Formulary',
                  canonical='http://hl7.org/fhir/us/davinci-drug-formulary', capability='CapabilityStatement-usdf-server.json')),
    ('pas', dict(package='hl7.fhir.us.davinci-pas', name='Da Vinci Prior Authorization Support (PAS)',
                 canonical='http://hl7.org/fhir/us/davinci-pas', capability=None)),
    ('smart', dict(package='hl7.fhir.uv.smart-app-launch', name='SMART App Launch',
                   canonical='http://hl7.org/fhir/smart-app-launch', capability=None)),
])

# Profiles whose element rules (required + must-support) are exported for the profile-lite checker.
PROFILE_FILES = {
    'c4bb': ['C4BB-Patient', 'C4BB-Coverage', 'C4BB-Organization', 'C4BB-Practitioner', 'C4BB-RelatedPerson',
             'C4BB-ExplanationOfBenefit', 'C4BB-ExplanationOfBenefit-Inpatient-Institutional',
             'C4BB-ExplanationOfBenefit-Outpatient-Institutional', 'C4BB-ExplanationOfBenefit-Oral',
             'C4BB-ExplanationOfBenefit-Pharmacy', 'C4BB-ExplanationOfBenefit-Professional-NonClinician'],
    'pdex': ['pdex-priorauthorization', 'pdex-medicationdispense', 'pdex-provenance', 'pdex-device'],
}

# US Core profiles are not in the local packages (the npm mirror has no hl7.fhir.us.core); the
# canonical URLs are listed so search results can be labelled. Must-support rules for them are
# curated separately in uscore-rules.json.
US_CORE_PROFILES = {
    'Patient': 'us-core-patient', 'AllergyIntolerance': 'us-core-allergyintolerance', 'CarePlan': 'us-core-careplan',
    'CareTeam': 'us-core-careteam', 'Condition': 'us-core-condition', 'Device': 'us-core-implantable-device',
    'DiagnosticReport': 'us-core-diagnosticreport-lab', 'DocumentReference': 'us-core-documentreference',
    'Encounter': 'us-core-encounter', 'Goal': 'us-core-goal', 'Immunization': 'us-core-immunization',
    'Location': 'us-core-location', 'Medication': 'us-core-medication', 'MedicationRequest': 'us-core-medicationrequest',
    'Observation': 'us-core-observation-lab', 'Organization': 'us-core-organization', 'Practitioner': 'us-core-practitioner',
    'PractitionerRole': 'us-core-practitionerrole', 'Procedure': 'us-core-procedure', 'Provenance': 'us-core-provenance',
    'RelatedPerson': 'us-core-relatedperson', 'ServiceRequest': 'us-core-servicerequest', 'Specimen': 'us-core-specimen',
    'MedicationDispense': 'us-core-medicationdispense', 'QuestionnaireResponse': 'us-core-questionnaireresponse',
    'Coverage': 'us-core-coverage', 'FamilyMemberHistory': 'us-core-familymemberhistory',
}

# Resource types the Patient Access API is expected to expose (used to order the catalog and the UI).
PATIENT_ACCESS_RESOURCES = [
    'Patient', 'Coverage', 'ExplanationOfBenefit', 'Organization', 'Practitioner', 'PractitionerRole', 'RelatedPerson',
    'Location', 'AllergyIntolerance', 'CarePlan', 'CareTeam', 'Condition', 'Device', 'DiagnosticReport', 'DocumentReference',
    'Encounter', 'Goal', 'Immunization', 'Medication', 'MedicationDispense', 'MedicationRequest', 'Observation', 'Procedure',
    'Provenance', 'ServiceRequest', 'Specimen', 'QuestionnaireResponse', 'FamilyMemberHistory', 'Consent',
    'InsurancePlan', 'Basic', 'MedicationKnowledge', 'Group',
]

def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)

def expectation(elem):
    for e in elem.get('extension', []):
        if e.get('url') == EXPECTATION_URL:
            return e.get('valueCode', '').upper() or None
    return None

def read_capability(path, ig, catalog, sp_descriptions):
    cs = load(path)
    for rest in cs.get('rest', []):
        if rest.get('mode') != 'server':
            continue
        for res in rest.get('resource', []):
            rtype = res['type']
            entry = catalog.setdefault(rtype, OrderedDict(profiles=[], interactions=[], searchParams=[], combos=[],
                                                          includes=[], revIncludes=[], operations=[]))
            for p in res.get('supportedProfile', []):
                url = p.split('|')[0]
                if not any(x['url'] == url for x in entry['profiles']):
                    entry['profiles'].append(OrderedDict(url=url, name=url.rsplit('/', 1)[-1], ig=ig))
            for i in res.get('interaction', []):
                if i['code'] not in entry['interactions']:
                    entry['interactions'].append(i['code'])
            for sp in res.get('searchParam', []):
                existing = next((x for x in entry['searchParams'] if x['name'] == sp['name']), None)
                exp = expectation(sp) or 'MAY'
                if existing:
                    if RANK[exp] > RANK[existing['expectation']]:
                        existing['expectation'] = exp
                    if ig not in existing['igs']:
                        existing['igs'].append(ig)
                else:
                    doc = sp.get('documentation') or sp_descriptions.get((rtype, sp['name'])) or sp_descriptions.get(('*', sp['name'])) or ''
                    doc = doc.replace('**', '').replace('*', '')
                    entry['searchParams'].append(OrderedDict(name=sp['name'], type=sp.get('type', 'string'), expectation=exp,
                                                             igs=[ig], definition=sp.get('definition'), description=doc.strip()))
            for ext in res.get('extension', []):
                if ext.get('url') == COMBO_URL:
                    params = sorted(x['valueString'] for x in ext.get('extension', []) if x.get('url') == 'required')
                    exp = expectation(ext) or 'MAY'
                    existing = next((c for c in entry['combos'] if c['params'] == params), None)
                    if existing:
                        if RANK[exp] > RANK[existing['expectation']]:
                            existing['expectation'] = exp
                        if ig not in existing['igs']:
                            existing['igs'].append(ig)
                    else:
                        entry['combos'].append(OrderedDict(params=params, expectation=exp, igs=[ig]))
            for inc in res.get('searchInclude', []):
                if inc not in entry['includes']:
                    entry['includes'].append(inc)
            for inc in res.get('searchRevInclude', []):
                if inc not in entry['revIncludes']:
                    entry['revIncludes'].append(inc)
            for op in res.get('operation', []):
                if not any(o['name'] == op['name'] for o in entry['operations']):
                    entry['operations'].append(OrderedDict(name=op['name'], definition=op.get('definition'), ig=ig))
    return cs

RANK = {'MAY': 0, 'SHOULD': 1, 'SHALL': 2}

def path_to_fhirpath(element_id):
    """Turns an ElementDefinition id like ExplanationOfBenefit.item.adjudication:allowedunits.value into a
    FHIRPath expression relative to the resource; slices are rendered as a where() on their discriminator
    when it is a fixed pattern, else the slice is dropped (the checker then evaluates the parent path)."""
    parts = element_id.split('.')[1:]
    out = []
    for part in parts:
        name = part.split(':')[0]
        name = name.replace('[x]', '')
        out.append(name)
    return '.'.join(out)

def element_rules(sd):
    rules = []
    for e in sd.get('snapshot', {}).get('element', []):
        eid = e['id']
        if '.' not in eid:
            continue
        must = bool(e.get('mustSupport'))
        mn = e.get('min', 0)
        fixed = {k: e[k] for k in e if k.startswith('fixed') or k.startswith('pattern')}
        slice_name = e.get('sliceName')
        if not (must or mn > 0 or fixed):
            continue
        # skip the noise of required children of optional elements deeper than 3 levels
        rule = OrderedDict(id=eid, path=path_to_fhirpath(eid), min=mn, max=e.get('max', '*'), mustSupport=must)
        if slice_name:
            rule['slice'] = slice_name
        if fixed:
            rule['fixed'] = fixed
        types = [t.get('code') for t in e.get('type', [])]
        if types:
            rule['types'] = types
        type_profiles = [p for t in e.get('type', []) for p in t.get('profile', [])]
        if type_profiles:
            rule['typeProfiles'] = type_profiles
        b = e.get('binding')
        if b and b.get('strength') in ('required', 'extensible') and b.get('valueSet'):
            rule['binding'] = OrderedDict(strength=b['strength'], valueSet=b['valueSet'])
        if e.get('short'):
            rule['short'] = e['short']
        rules.append(rule)
    return rules

def read_profiles(pkgdir, ig, names, out):
    for name in names:
        path = os.path.join(pkgdir, 'StructureDefinition-%s.json' % name)
        if not os.path.exists(path):
            print('  missing profile', path, file=sys.stderr)
            continue
        sd = load(path)
        out[sd['url']] = OrderedDict(name=sd.get('name'), title=sd.get('title'), ig=ig, version=sd.get('version'),
                                     type=sd['type'], base=sd.get('baseDefinition'),
                                     description=(sd.get('description') or '').strip()[:400],
                                     elements=element_rules(sd))

def codesystem(path):
    cs = load(path)
    out = OrderedDict()
    def walk(concepts):
        for c in concepts:
            out[c['code']] = c.get('display') or c.get('definition') or c['code']
            if c.get('concept'):
                walk(c['concept'])
    walk(cs.get('concept', []))
    return cs.get('url'), out

def valueset_codes(path):
    vs = load(path)
    codes = []
    for inc in vs.get('compose', {}).get('include', []):
        for c in inc.get('concept', []):
            codes.append(OrderedDict(system=inc.get('system'), code=c['code'], display=c.get('display')))
        if not inc.get('concept'):
            codes.append(OrderedDict(system=inc.get('system'), code='*', display='all codes of the system'))
    return vs.get('url'), codes

def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    root = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    out_path = os.path.join(here, '..', 'src', 'main', 'resources', 'catalog', 'ig-catalog.json')

    sp_descriptions = {}
    # SearchParameter definitions shipped by the IGs give descriptions for their custom parameters.
    for key, ig in IGS.items():
        if not ig['package']:
            continue
        pkgdir = os.path.join(root, ig['package'], 'package')
        if not os.path.isdir(pkgdir):
            continue
        for fn in os.listdir(pkgdir):
            if fn.startswith('SearchParameter-') and fn.endswith('.json'):
                sp = load(os.path.join(pkgdir, fn))
                for base in sp.get('base', ['*']):
                    sp_descriptions[(base, sp['code'])] = sp.get('description', '')

    catalog = OrderedDict()
    igs_out = OrderedDict()
    profiles = OrderedDict()
    for key, ig in IGS.items():
        version = None
        if ig['package']:
            pkgdir = os.path.join(root, ig['package'], 'package')
            if os.path.isdir(pkgdir):
                version = load(os.path.join(pkgdir, 'package.json')).get('version')
                if ig['capability']:
                    read_capability(os.path.join(pkgdir, ig['capability']), key, catalog, sp_descriptions)
                if key in PROFILE_FILES:
                    read_profiles(pkgdir, key, PROFILE_FILES[key], profiles)
            else:
                print('  package not found:', pkgdir, file=sys.stderr)
        elif ig['capability'] and os.path.exists(os.path.join(root, ig['capability'])):
            cs = read_capability(os.path.join(root, ig['capability']), key, catalog, sp_descriptions)
            # the release tag is authoritative (the file inside a tag may still carry the previous build's version)
            version = ig['capability'].replace('us-core-server-', '').replace('.json', '')
            if not version or version == 'master':
                raise SystemExit('US Core CapabilityStatement version could not be determined; pin a released file')
        igs_out[key] = OrderedDict(name=ig['name'], canonical=ig['canonical'], version=version)

    # IG-defined SearchParameters that the CapabilityStatements do not list (e.g. PDex ExplanationOfBenefit.use)
    for key, ig in IGS.items():
        if not ig['package']:
            continue
        pkgdir = os.path.join(root, ig['package'], 'package')
        if not os.path.isdir(pkgdir):
            continue
        for fn in sorted(os.listdir(pkgdir)):
            if not (fn.startswith('SearchParameter-') and fn.endswith('.json')):
                continue
            sp = load(os.path.join(pkgdir, fn))
            for base in sp.get('base', []):
                entry = catalog.get(base)
                if entry is None:
                    continue
                if any(x['name'] == sp['code'] for x in entry['searchParams']):
                    continue
                entry['searchParams'].append(OrderedDict(name=sp['code'], type=sp.get('type', 'string'), expectation='MAY',
                                                         igs=[key], definition=sp.get('url'),
                                                         description=(sp.get('description') or '').strip(),
                                                         expression=sp.get('expression')))

    # label US Core profiles by canonical URL (the CapabilityStatement's supportedProfile carries them)
    for rtype, name in US_CORE_PROFILES.items():
        entry = catalog.setdefault(rtype, OrderedDict(profiles=[], interactions=[], searchParams=[], combos=[],
                                                      includes=[], revIncludes=[], operations=[]))
        url = 'http://hl7.org/fhir/us/core/StructureDefinition/' + name
        if not any(p['url'] == url for p in entry['profiles']):
            entry['profiles'].append(OrderedDict(url=url, name=name, ig='uscore'))

    # sort search params: SHALL first, then SHOULD, MAY; _id, patient first within the same expectation
    for rtype, entry in catalog.items():
        entry['searchParams'].sort(key=lambda s: (-RANK[s['expectation']], s['name'] not in ('_id', 'patient', 'identifier'), s['name']))
        entry['combos'].sort(key=lambda c: (-RANK[c['expectation']], c['params']))

    ordered = OrderedDict()
    for r in PATIENT_ACCESS_RESOURCES:
        if r in catalog:
            ordered[r] = catalog[r]
    for r in sorted(catalog):
        if r not in ordered:
            ordered[r] = catalog[r]

    codes = OrderedDict()
    pdex_dir = os.path.join(root, 'hl7.fhir.us.davinci-pdex', 'package')
    c4bb_dir = os.path.join(root, 'hl7.fhir.us.carin-bb', 'package')
    for label, path in [('pdexAdjudicationDiscriminator', os.path.join(pdex_dir, 'CodeSystem-PDexAdjudicationDiscriminator.json')),
                        ('priorAuthorizationValueCodes', os.path.join(pdex_dir, 'CodeSystem-PriorAuthorizationValueCodes.json')),
                        ('pdexPayerAdjudicationStatus', os.path.join(pdex_dir, 'CodeSystem-PDexPayerAdjudicationStatus.json')),
                        ('c4bbAdjudication', os.path.join(c4bb_dir, 'CodeSystem-C4BBAdjudication.json')),
                        ('c4bbIdentifierType', os.path.join(c4bb_dir, 'CodeSystem-C4BBIdentifierType.json')),
                        ('c4bbPayerAdjudicationStatus', os.path.join(c4bb_dir, 'CodeSystem-C4BBPayerAdjudicationStatus.json')),
                        ('c4bbSupportingInfoType', os.path.join(c4bb_dir, 'CodeSystem-C4BBSupportingInfoType.json'))]:
        if os.path.exists(path):
            url, concepts = codesystem(path)
            codes[label] = OrderedDict(system=url, codes=concepts)
    valuesets = OrderedDict()
    for label, path in [('c4bbPatientIdentifierType', os.path.join(c4bb_dir, 'ValueSet-C4BBPatientIdentifierType.json')),
                        ('priorAuthorizationAmounts', os.path.join(pdex_dir, 'ValueSet-PriorAuthorizationAmounts.json')),
                        ('priorAuthServiceTypeCodes', os.path.join(pdex_dir, 'ValueSet-PriorAuthServiceTypeCodes.json')),
                        ('pdexAdjudication', os.path.join(pdex_dir, 'ValueSet-PDexAdjudication.json'))]:
        if os.path.exists(path):
            url, cs = valueset_codes(path)
            valuesets[label] = OrderedDict(url=url, codes=cs)

    eob_profiles = []
    for name, claim_type, subtype in [('C4BB-ExplanationOfBenefit-Inpatient-Institutional', 'institutional', 'inpatient'),
                                      ('C4BB-ExplanationOfBenefit-Outpatient-Institutional', 'institutional', 'outpatient'),
                                      ('C4BB-ExplanationOfBenefit-Professional-NonClinician', 'professional', None),
                                      ('C4BB-ExplanationOfBenefit-Pharmacy', 'pharmacy', None),
                                      ('C4BB-ExplanationOfBenefit-Oral', 'oral', None)]:
        eob_profiles.append(OrderedDict(url='http://hl7.org/fhir/us/carin-bb/StructureDefinition/' + name, name=name,
                                        claimType=claim_type, subType=subtype, use='claim'))
    eob_profiles.append(OrderedDict(url='http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization',
                                    name='pdex-priorauthorization', claimType=None, subType=None, use='preauthorization'))

    result = OrderedDict(
        generatedBy='tools/generate-catalog.py',
        igs=igs_out,
        resources=ordered,
        eobProfiles=eob_profiles,
        profiles=profiles,
        codeSystems=codes,
        valueSets=valuesets,
    )
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(result, f, indent=1)
        f.write('\n')
    print('wrote', os.path.normpath(out_path), 'resources:', len(ordered), 'profiles:', len(profiles))

if __name__ == '__main__':
    main()
