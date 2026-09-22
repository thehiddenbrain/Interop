-- Family permission rules.
--
-- PARTIAL SEED. The full rule set (about 430 rows) must be exported from
-- Family_Permission_Table_Design.xlsx, sheet "Family Permission Rules", with the same columns.
-- This file carries the rows that were legible in the design review (profile family, REL_SOGI
-- rows 9-19) plus benefits and claims rows that reproduce the API Response Example, so the
-- service is runnable end to end. Replace it with the workbook export before go-live.
--
-- Conventions the evaluator relies on:
--   * "Derived from child permissions" parent rows are NOT needed; parents are computed.
--     They are included where the workbook has them, but the evaluator ignores parent keys.
--   * NOT_APPLICABLE rows are inactive (is_active = FALSE) and never load.
--   * Empty action_codes with consent_required = TRUE means: actions come from the child rule
--     once consent is on file; until then the key is reported under consentRequired.

INSERT INTO family_permission.family_permission_rule
  (permission_family, permission_key, actor_relationship, viewing_relationship, age_description, minimum_age, maximum_age,
   action_codes, access_status, consent_required, administrative_consent, revocable, masked_data, is_active,
   source_sheet, source_row, source_access_text, notes)
VALUES
  -- ---------------- benefits: Subscriber (reproduces the API Response Example) ----------------
  ('benefits', 'benefits',                 'Subscriber', 'Self', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 2, 'Derived from child permissions', 'Parent permission grants View when at least one child permission is available.'),
  ('benefits', 'benefits.coverage',        'Subscriber', 'Self', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 2, 'Full Access', NULL),
  ('benefits', 'benefits.idCard',          'Subscriber', 'Self', 'Any', NULL, NULL, '{1,3}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 2, 'Full Access', NULL),
  ('benefits', 'benefits.accumulator',     'Subscriber', 'Self', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 2, 'Full Access', NULL),
  ('benefits', 'benefits.spendingAccount', 'Subscriber', 'Self', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 2, 'Full Access', NULL),
  ('benefits', 'benefits.activePolicy',    'Subscriber', 'Self', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 2, 'Full Access', NULL),

  ('benefits', 'benefits.coverage',        'Subscriber', 'Spouse', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 3, 'Full Access', NULL),
  ('benefits', 'benefits.idCard',          'Subscriber', 'Spouse', 'Any', NULL, NULL, '{1,3}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 3, 'Full Access', NULL),
  ('benefits', 'benefits.accumulator',     'Subscriber', 'Spouse', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 3, 'Full Access', NULL),
  ('benefits', 'benefits.spendingAccount', 'Subscriber', 'Spouse', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 3, 'Full Access', NULL),
  ('benefits', 'benefits.activePolicy',    'Subscriber', 'Spouse', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 3, 'Full Access', NULL),

  ('benefits', 'benefits.coverage',        'Subscriber', 'Child', '0 - 12', 0, 12, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 4, 'Full Access', NULL),
  ('benefits', 'benefits.idCard',          'Subscriber', 'Child', '0 - 12', 0, 12, '{1,3}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 4, 'Full Access', NULL),
  ('benefits', 'benefits.accumulator',     'Subscriber', 'Child', '0 - 12', 0, 12, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 4, 'Full Access', NULL),
  ('benefits', 'benefits.spendingAccount', 'Subscriber', 'Child', '0 - 12', 0, 12, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 4, 'Full Access', NULL),
  ('benefits', 'benefits.activePolicy',    'Subscriber', 'Child', '0 - 12', 0, 12, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 4, 'Full Access', NULL),

  ('benefits', 'benefits.coverage',        'Subscriber', 'Child', '13 - 17', 13, 17, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 5, 'Full Access', NULL),
  ('benefits', 'benefits.idCard',          'Subscriber', 'Child', '13 - 17', 13, 17, '{1,3}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 5, 'Full Access', NULL),
  ('benefits', 'benefits.accumulator',     'Subscriber', 'Child', '13 - 17', 13, 17, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 5, 'Full Access', NULL),
  ('benefits', 'benefits.activePolicy',    'Subscriber', 'Child', '13 - 17', 13, 17, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 5, 'Full Access', NULL),

  ('benefits', 'benefits.coverage',        'Subscriber', 'Adult dependent (any relationship)', '18 or older', 18, NULL, '{1}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 6, 'Full Access', NULL),
  ('benefits', 'benefits.idCard',          'Subscriber', 'Adult dependent (any relationship)', '18 or older', 18, NULL, '{1}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Benefits', 6, 'Full Access', NULL),

  -- ---------------- claims: Subscriber (consent and masking examples) ----------------
  ('claims', 'claims.claim',         'Subscriber', 'Self',  'Any',     NULL, NULL, '{1,3}', 'FULL_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 2, 'Full Access', NULL),
  ('claims', 'claims.authorization', 'Subscriber', 'Self',  'Any',     NULL, NULL, '{1}',   'FULL_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 2, 'Full Access', NULL),
  ('claims', 'claims.referral',      'Subscriber', 'Self',  'Any',     NULL, NULL, '{1}',   'FULL_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 2, 'Full Access', NULL),
  ('claims', 'claims.claim',         'Subscriber', 'Child', '0 - 12',  0,    12,   '{1,3}', 'FULL_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 4, 'Full Access', NULL),
  ('claims', 'claims.authorization', 'Subscriber', 'Child', '0 - 12',  0,    12,   '{1}',   'FULL_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 4, 'Full Access', NULL),
  ('claims', 'claims.referral',      'Subscriber', 'Child', '0 - 12',  0,    12,   '{1}',   'FULL_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 4, 'Full Access', NULL),
  ('claims', 'claims.claim',         'Subscriber', 'Child', '13 - 17', 13,   17,   '{1}',   'MASKED_ACCESS',    TRUE,  FALSE, TRUE,  TRUE,  TRUE, 'Claims_RefAuth', 5, 'No default access, consent required', 'PDC masking and consent between subscriber/spouse and teen dependent.'),
  ('claims', 'claims.authorization', 'Subscriber', 'Child', '13 - 17', 13,   17,   '{1}',   'CONSENT_REQUIRED', TRUE,  FALSE, TRUE,  FALSE, TRUE, 'Claims_RefAuth', 5, 'No default access, consent required', NULL),
  ('claims', 'claims.claim',         'Subscriber', 'All other family members', 'Any', NULL, NULL, '{}', 'NO_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'Claims_RefAuth', 6, 'No Access', NULL),

  -- ---------------- profile: REL_SOGI rows 9-19 (legible in the review) ----------------
  ('profile', 'profile.raceEthnicityLanguage',           'Spouse', 'Child', '0 - 11', 0, 11, '{1,2}', 'FULL_ACCESS',    FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 9,  'Full Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Spouse', 'Child', '0 - 11', 0, 11, '{}',    'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 9,  'N/A**', 'Not collected for members under age 18.'),
  ('profile', 'profile',                                 'Spouse', 'Child', '13 - 17', 13, 17, '{}',  'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 10, 'Derived from child permissions', 'Parent permission grants View when at least one child permission is available.'),
  ('profile', 'profile.raceEthnicityLanguage',           'Spouse', 'Child', '13 - 17', 13, 17, '{}',  'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 10, 'No Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Spouse', 'Child', '13 - 17', 13, 17, '{}',  'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 10, 'N/A**', 'Not collected for members under age 18.'),

  ('profile', 'profile',                                 'Ex-Spouse', 'Self', 'Any', NULL, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 11, 'Derived from child permissions', 'Parent permission grants View when at least one child permission is available.'),
  ('profile', 'profile.raceEthnicityLanguage',           'Ex-Spouse', 'Self', 'Any', NULL, NULL, '{1,2}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 11, 'Full Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Ex-Spouse', 'Self', 'Any', NULL, NULL, '{1,2}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 11, 'Full Access', NULL),
  ('profile', 'profile',                                 'Ex-Spouse', 'Subscriber', 'Any', NULL, NULL, '{}', 'NO_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 12, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Ex-Spouse', 'Subscriber', 'Any', NULL, NULL, '{}', 'NO_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 12, 'No Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Ex-Spouse', 'Subscriber', 'Any', NULL, NULL, '{}', 'NO_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 12, 'No Access', NULL),
  ('profile', 'profile',                                 'Ex-Spouse', 'Child', '13 - 17', 13, 17, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 13, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Ex-Spouse', 'Child', '13 - 17', 13, 17, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 13, 'No Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Ex-Spouse', 'Child', '13 - 17', 13, 17, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 13, 'N/A**', 'Not collected for members under age 18.'),
  ('profile', 'profile',                                 'Ex-Spouse', 'Child', '0 - 12', 0, 12, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 14, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Ex-Spouse', 'Child', '0 - 12', 0, 12, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 14, 'No Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Ex-Spouse', 'Child', '0 - 12', 0, 12, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 14, 'N/A**', 'Not collected for members under age 18.'),

  ('profile', 'profile',                                 'Adult Child', 'Self', '18 or older', 18, NULL, '{1}',   'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 15, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Adult Child', 'Self', '18 or older', 18, NULL, '{1,2}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 15, 'Full Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Adult Child', 'Self', '18 or older', 18, NULL, '{1,2}', 'FULL_ACCESS', FALSE, FALSE, FALSE, FALSE, TRUE, 'REL_SOGI', 15, 'Full Access', NULL),
  ('profile', 'profile',                                 'Adult Child', 'All other family members', 'Any', NULL, NULL, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 16, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Adult Child', 'All other family members', 'Any', NULL, NULL, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 16, 'No Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Adult Child', 'All other family members', 'Any', NULL, NULL, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 16, 'N/A**', 'Not collected for members under age 18.'),

  ('profile', 'profile',                                 'Child (teenager)', 'Self', '13 - 17', 13, 17, '{1}',   'FULL_ACCESS',    FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 17, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Child (teenager)', 'Self', '13 - 17', 13, 17, '{1,2}', 'FULL_ACCESS',    FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 17, 'Full Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Child (teenager)', 'Self', '13 - 17', 13, 17, '{}',    'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 17, 'N/A**', 'Not collected for members under age 18.'),
  ('profile', 'profile',                                 'Child (teenager)', 'All other family members', 'Any', NULL, NULL, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 18, 'Derived from child permissions', NULL),
  ('profile', 'profile.raceEthnicityLanguage',           'Child (teenager)', 'All other family members', 'Any', NULL, NULL, '{}', 'NO_ACCESS',      FALSE, FALSE, FALSE, FALSE, TRUE,  'REL_SOGI', 18, 'No Access', NULL),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Child (teenager)', 'All other family members', 'Any', NULL, NULL, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 18, 'N/A**', 'Not collected for members under age 18.'),

  ('profile', 'profile',                                 'Child (minor)', 'Self', '0 - 12', 0, 12, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 19, 'Derived from child permissions', 'Online account or feature is not applicable.'),
  ('profile', 'profile.raceEthnicityLanguage',           'Child (minor)', 'Self', '0 - 12', 0, 12, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 19, 'N/A can not create online accounts', 'Online account or feature is not applicable.'),
  ('profile', 'profile.sexualOrientationGenderIdentity', 'Child (minor)', 'Self', '0 - 12', 0, 12, '{}', 'NOT_APPLICABLE', FALSE, FALSE, FALSE, FALSE, FALSE, 'REL_SOGI', 19, 'N/A can not create online accounts', 'Not collected for members under age 18.');
