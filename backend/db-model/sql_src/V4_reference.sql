INSERT INTO fin.claim_type (code, name_en, name_sw) VALUES
  ('TRAVEL',      'Travel allowance',       'Posho ya safari'),
  ('PER_DIEM',    'Per diem / subsistence', 'Posho ya kujikimu'),
  ('CONSULTANCY', 'Consultancy allowance',  'Posho ya ushauri'),
  ('EXTRA_DUTY',  'Extra-duty allowance',   'Posho ya muda wa ziada'),
  ('OTHER',       'Other approved claims',  'Madai mengine yaliyoidhinishwa');

INSERT INTO mtg.meeting_type (code, name_en, name_sw, confidentiality, quorum_percent, minutes_template, resolution_prefix) VALUES
  ('BOARD',       'Board of Directors',  'Bodi ya Wakurugenzi',   'CONFIDENTIAL', 50, '{"sections":["attendance","confirmation_of_minutes","matters_arising","agenda_items","resolutions","aob","closing"]}', 'BD'),
  ('MANAGEMENT',  'Management Meeting',  'Kikao cha Menejimenti', 'RESTRICTED',   50, '{"sections":["attendance","confirmation_of_minutes","matters_arising","agenda_items","resolutions","aob","closing"]}', 'MM'),
  ('DIRECTORATE', 'Directorate Meeting', 'Kikao cha Kurugenzi',   'INTERNAL',     50, '{"sections":["attendance","matters_arising","agenda_items","resolutions","aob"]}', 'DM'),
  ('COMMITTEE',   'Committee Meeting',   'Kikao cha Kamati',      'RESTRICTED',   50, '{"sections":["attendance","agenda_items","resolutions"]}', 'CM'),
  ('PROJECT',     'Project Meeting',     'Kikao cha Mradi',       'INTERNAL',     40, '{"sections":["attendance","progress","issues","actions"]}', 'PM'),
  ('STAFF',       'Staff Meeting',       'Kikao cha Watumishi',   'INTERNAL',     30, '{"sections":["attendance","agenda_items","resolutions"]}', 'SM');

-- Fixed-date public holidays for 2026 and 2027. Moveable holidays (Good Friday, Easter Monday,
-- Eid al-Fitr, Eid al-Adha, Maulid) are entered by administrators when gazetted.
INSERT INTO wf.holiday (holiday_date, name_en, name_sw) VALUES
  ('2026-10-14', 'Nyerere Day',               'Siku ya Mwalimu Nyerere'),
  ('2026-12-09', 'Independence Day',          'Siku ya Uhuru'),
  ('2026-12-25', 'Christmas Day',             'Krismasi'),
  ('2026-12-26', 'Boxing Day',                'Siku ya Kufungua Zawadi'),
  ('2027-01-01', 'New Year''s Day',           'Mwaka Mpya'),
  ('2027-01-12', 'Zanzibar Revolution Day',   'Siku ya Mapinduzi ya Zanzibar'),
  ('2027-04-07', 'Karume Day',                'Siku ya Karume'),
  ('2027-04-26', 'Union Day',                 'Siku ya Muungano'),
  ('2027-05-01', 'Workers'' Day',             'Siku ya Wafanyakazi'),
  ('2027-07-07', 'Saba Saba',                 'Sabasaba'),
  ('2027-08-08', 'Nane Nane (Farmers'' Day)', 'Nanenane'),
  ('2027-10-14', 'Nyerere Day',               'Siku ya Mwalimu Nyerere'),
  ('2027-12-09', 'Independence Day',          'Siku ya Uhuru'),
  ('2027-12-25', 'Christmas Day',             'Krismasi'),
  ('2027-12-26', 'Boxing Day',                'Siku ya Kufungua Zawadi');

-- Proposed retention periods — to be confirmed against TIRDO's approved records retention schedule in R0.
INSERT INTO sec.retention_policy (record_type, retention_period, disposal_action, legal_basis) VALUES
  ('MTG_RECORDING',  '12 months', 'DELETE',  'Proposed; recordings are working material once minutes are approved'),
  ('MTG_MINUTES',    '100 years', 'ARCHIVE', 'Proposed permanent retention; confirm with Records'),
  ('MSG_STANDARD',   '3 years',   'REVIEW',  'Proposed; confirm with Records'),
  ('COM_NOTICE',     '7 years',   'ARCHIVE', 'Proposed; confirm with Records'),
  ('FIN_MIRROR',     '7 years',   'DELETE',  'Mirror of Finance records; source retained by Finance'),
  ('DOC_SUPERSEDED', '7 years',   'REVIEW',  'Proposed; per document type'),
  ('AUDIT_EVENT',    '7 years',   'ARCHIVE', 'Proposed; archive by partition'),
  ('FDB_PROTECTED',  '7 years',   'REVIEW',  'Per whistle-blowing policy; confirm with Legal');

INSERT INTO sec.feature_flag (code, enabled) VALUES
  ('FDB_PROTECTED_CHANNEL', false),
  ('MTG_AI_DRAFTS',         false),
  ('NTF_WEB_PUSH',          false),
  ('FIN_API_FEED',          false),
  ('DIR_API_FEED',          false);

INSERT INTO req.request_category (code, name_en, name_sw) VALUES
  ('ICT',         'ICT support',             'Msaada wa TEHAMA'),
  ('ADMIN',       'Administrative services', 'Huduma za utawala'),
  ('TRAVEL',      'Travel authorisation',    'Kibali cha safari'),
  ('EQUIPMENT',   'Equipment',               'Vifaa'),
  ('MAINTENANCE', 'Maintenance',             'Matengenezo');
