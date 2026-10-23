-- V4__reference_data.sql
-- TIRDO ICCP — generated from backend/db-model (Doc 06, Draft 4.0). Do not edit by hand.
-- Target: PostgreSQL 16. Applied by Flyway as role iccp_owner.

SELECT set_config('iccp.actor', 'flyway', true);

INSERT INTO sec.role (code, name, privileged, requires_mfa) VALUES
  ('STAFF', 'All staff', false, false),
  ('LINE_MANAGER', 'Head of section/unit', false, false),
  ('DIRECTOR', 'Director / Head of department', false, true),
  ('EXECUTIVE', 'Director General and designated executives', false, true),
  ('COMMS_PUBLISHER', 'Communication officer', false, false),
  ('COMMS_APPROVER', 'Authorised approver of communications', true, true),
  ('EMERGENCY_APPROVER', 'Designated emergency approver', true, true),
  ('FIN_OFFICER', 'Finance officer', true, true),
  ('FIN_REVIEWER', 'Finance reviewer', true, true),
  ('HR_OFFICER', 'Human resources officer', false, false),
  ('MEETING_SECRETARY', 'Meeting secretary', false, false),
  ('MEETING_CHAIR', 'Meeting chair', false, false),
  ('RECORDS_OFFICER', 'Records officer', false, false),
  ('PROJECT_LEAD', 'Project lead', false, false),
  ('SERVICE_AGENT', 'Fulfilment agent', false, false),
  ('TRAINING_COORDINATOR', 'Training coordinator', false, false),
  ('FEEDBACK_OFFICER', 'Feedback officer', false, false),
  ('INTEGRITY_OFFICER', 'Integrity officer', true, true),
  ('ANALYST', 'Planning / M&E analyst', false, false),
  ('SYS_ADMIN', 'System administrator (ICTT)', true, true),
  ('SECURITY_ADMIN', 'Security administrator (ICTT)', true, true),
  ('AUDITOR', 'Internal auditor', true, true);

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

INSERT INTO integ.integration_endpoint (code, system_name, direction, mechanism) VALUES
  ('I-01', 'HR master system (HCMIS or TIRDO HR records — to confirm in R0)', 'INBOUND', 'R1: daily authenticated batch. R3: API/GovESB.'),
  ('I-02', 'Finance system (MUSE or TIRDO finance system — to confirm in R0)', 'INBOUND', 'R1: daily authenticated batch with control totals. R3: API/GovESB.'),
  ('I-03', 'Email (GMS or TIRDO mail relay)', 'OUTBOUND', 'SMTP with TLS and authenticated relay'),
  ('I-04', 'SMS gateway (approved government or contracted gateway)', 'OUTBOUND', 'HTTPS API; delivery-report callback'),
  ('I-05', 'Keycloak identity provider', 'BIDIRECTIONAL', 'OIDC; optional LDAP federation'),
  ('I-06', 'TIRDO Meeting Management System (legacy)', 'INBOUND', 'Migration scripts with reconciliation report'),
  ('I-07', 'Calendars (staff email clients)', 'OUTBOUND', 'iCalendar (RFC 5545) attachments'),
  ('I-08', 'LIMS', 'INBOUND', 'REST webhook or GovESB'),
  ('I-09', 'CIAP', 'INBOUND', 'REST API'),
  ('I-10', 'TeLTP (e-Learning)', 'BIDIRECTIONAL', 'REST API'),
  ('I-11', 'Research Management system (if in use)', 'INBOUND', 'REST API or batch'),
  ('I-12', 'e-Office (if used by TIRDO)', 'BIDIRECTIONAL', 'Reference capture; deep link'),
  ('I-13', 'GovESB', 'BIDIRECTIONAL', 'GovESB onboarding per e-GA guidance'),
  ('I-14', 'Web Push (browser push services)', 'OUTBOUND', 'Web Push protocol with VAPID keys'),
  ('I-15', 'SIEM / Security Operations Centre', 'OUTBOUND', 'Syslog or JSON over TLS');
