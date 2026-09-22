-- Reference data for family permissions (Family_Permission_Table_Design.xlsx: Action Codes, Permission Catalog).

INSERT INTO family_permission.action_code (action_code, description) VALUES
  (1, 'View'), (2, 'Edit'), (3, 'Download'), (4, 'Delete');

INSERT INTO family_permission.permission_catalog (permission_key, permission_family, description) VALUES
  ('benefits',                               'benefits',    'Benefits'),
  ('benefits.accumulator',                   'benefits',    'Benefits > Accumulator'),
  ('benefits.activePolicy',                  'benefits',    'Benefits > Active Policy'),
  ('benefits.coverage',                      'benefits',    'Benefits > Coverage'),
  ('benefits.idCard',                        'benefits',    'Benefits > Id Card'),
  ('benefits.spendingAccount',               'benefits',    'Benefits > Spending Account'),
  ('claims',                                 'claims',      'Claims'),
  ('claims.authorization',                   'claims',      'Claims > Authorization'),
  ('claims.claim',                           'claims',      'Claims > Claim'),
  ('claims.referral',                        'claims',      'Claims > Referral'),
  ('demographic',                            'demographic', 'Demographic'),
  ('demographic.contract',                   'demographic', 'Demographic > Contract'),
  ('demographic.memberOther',                'demographic', 'Demographic > Member Other'),
  ('demographic.memberSelf',                 'demographic', 'Demographic > Member Self'),
  ('documents',                              'documents',   'Documents'),
  ('documents.letter',                       'documents',   'Documents > Letter'),
  ('documents.planDocument',                 'documents',   'Documents > Plan Document'),
  ('documents.taxDocument',                  'documents',   'Documents > Tax Document'),
  ('forms',                                  'forms',       'Forms'),
  ('forms.capeCodHealthcare',                'forms',       'Forms > Cape Cod Healthcare'),
  ('forms.coordinationOfBenefits',           'forms',       'Forms > Coordination Of Benefits'),
  ('forms.designationOfRepresentative',      'forms',       'Forms > Designation Of Representative'),
  ('forms.medicalReimbursement',             'forms',       'Forms > Medical Reimbursement'),
  ('profile',                                'profile',     'Profile'),
  ('profile.raceEthnicityLanguage',          'profile',     'Profile > Race Ethnicity Language'),
  ('profile.sexualOrientationGenderIdentity','profile',     'Profile > Sexual Orientation Gender Identity');

-- Member domain relationship codes. Confirm against the MemberDomain service; 01 and 03 come from
-- the sample response in the design notes, 02 and 04 are placeholders to be verified.
INSERT INTO family_permission.relationship_code (relationship_code, relationship, description) VALUES
  ('01', 'Subscriber', 'Subscriber / policy holder'),
  ('02', 'Spouse',     'Spouse or domestic partner'),
  ('03', 'Child',      'Child or dependent'),
  ('04', 'Ex-Spouse',  'Former spouse still on the policy');
