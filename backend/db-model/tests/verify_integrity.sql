-- ICCP database verification suite (Doc 06 §9). Run as iccp_app against a freshly migrated, EMPTY database:
--   psql -U iccp_app -d <db> -f verify_integrity.sql
-- Each check prints PASS or FAIL. Tests that need other roles are in verify_roles.sql.
\set ON_ERROR_STOP on
SET client_min_messages = notice;

CREATE FUNCTION pg_temp.expect_err(p_id text, p_sql text, p_pattern text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE p_sql;
    RAISE NOTICE 'FAIL % — statement succeeded but should have been rejected', p_id;
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM ~ p_pattern THEN RAISE NOTICE 'PASS % — rejected: %', p_id, SQLERRM;
    ELSE RAISE NOTICE 'FAIL % — wrong error: %', p_id, SQLERRM; END IF;
  END;
END $$;
CREATE FUNCTION pg_temp.expect(p_id text, p_ok boolean, p_msg text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN RAISE NOTICE '% % — %', CASE WHEN p_ok THEN 'PASS' ELSE 'FAIL' END, p_id, p_msg; END $$;

-- ---------- fixtures (Finance scope) ----------
SELECT set_config('iccp.actor', 'finance.officer', false), set_config('iccp.fin_scope', 'ALL', false);
INSERT INTO dir.org_unit (code, name_en, unit_type, effective_from) VALUES ('ICTT', 'ICT and Technology Development Department', 'DEPARTMENT', '2026-07-01');
INSERT INTO dir.staff_member (employee_no, first_name, last_name, org_unit_id, employment_status, official_email)
VALUES ('EMP001', 'Asha', 'Mussa', 1, 'ACTIVE', 'asha.mussa@tirdo.go.tz'),
       ('EMP002', 'Baraka', 'John', 1, 'ACTIVE', 'baraka.john@tirdo.go.tz');
INSERT INTO fin.import_batch (feed_type, checksum, source_record_count, source_amount_total, imported_by)
VALUES ('MIXED', repeat('a', 64), 4, 4100000, 'finance.officer');
INSERT INTO fin.claim (staff_id, batch_id, source_ref, claim_type_id, description, approved_amount, approval_date)
VALUES (1, 1, 'CLM-1', 1, 'Travel allowance — Arusha', 1500000, '2026-10-01'),
       (2, 1, 'CLM-2', 1, 'Travel allowance — Mwanza',  800000, '2026-10-01');
INSERT INTO fin.payment (staff_id, batch_id, source_ref, amount, paid_on)
VALUES (1, 1, 'PAY-1', 1000000, '2026-10-10'),
       (1, 1, 'PAY-2',  600000, '2026-10-15'),
       (2, 1, 'PAY-3',  200000, '2026-10-15');
INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, source_ref, allocated_by) VALUES (1, 1, 1000000, 'ALC-1', 'FINANCE_FEED');
SELECT pg_temp.expect('FIN-A', true, 'partial allocation of 1,000,000 against a 1,500,000 claim accepted');

-- ---------- Finance invariants ----------
SELECT pg_temp.expect_err('FIN-B', $q$INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, source_ref, allocated_by) VALUES (2, 1, 600000, 'ALC-2', 'FINANCE_FEED')$q$, 'ICCP-FIN-008');
SELECT pg_temp.expect_err('FIN-C', $q$INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, source_ref, allocated_by) VALUES (1, 1, 1, 'ALC-3', 'FINANCE_FEED')$q$, 'ICCP-FIN-007');
SELECT pg_temp.expect_err('FIN-D', $q$INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, source_ref, allocated_by) VALUES (3, 1, 1, 'ALC-4', 'FINANCE_FEED')$q$, 'ICCP-FIN-003');
UPDATE fin.claim SET status = 'CANCELLED' WHERE id = 2;
SELECT pg_temp.expect_err('FIN-E', $q$INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, source_ref, allocated_by) VALUES (3, 2, 100000, 'ALC-5', 'FINANCE_FEED')$q$, 'ICCP-FIN-002');
UPDATE fin.claim SET status = 'APPROVED' WHERE id = 2;
INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, reverses_allocation_id, source_ref, allocated_by) VALUES (1, 1, -400000, 1, 'REV-1', 'FINANCE_FEED');
SELECT pg_temp.expect('FIN-F', true, 'reversal of 400,000 accepted as a negative allocation');
SELECT pg_temp.expect_err('FIN-G', $q$INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, reverses_allocation_id, source_ref, allocated_by) VALUES (1, 1, -700000, 1, 'REV-2', 'FINANCE_FEED')$q$, 'ICCP-FIN-006');
SELECT pg_temp.expect_err('FIN-H', $q$INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, source_ref, allocated_by) VALUES (1, 1, -5, 'REV-3', 'FINANCE_FEED')$q$, 'check constraint');
SELECT pg_temp.expect_err('FIN-I', $q$UPDATE fin.payment_allocation SET amount = 1 WHERE id = 1$q$, 'ICCP-CORE-003');
SELECT pg_temp.expect_err('FIN-J', $q$DELETE FROM fin.payment_allocation WHERE id = 1$q$, 'ICCP-CORE-002');
SELECT pg_temp.expect_err('FIN-K', $q$UPDATE fin.import_batch SET reviewed_by = 'finance.officer' WHERE id = 1$q$, 'import_batch_check');
SELECT pg_temp.expect_err('FIN-L', $q$UPDATE fin.import_batch SET status = 'PUBLISHED', published_at = now() WHERE id = 1$q$, 'import_batch_check');
UPDATE fin.import_batch SET status = 'RECONCILED', loaded_count = 4, reviewed_by = 'finance.reviewer', reviewed_at = now() WHERE id = 1;
UPDATE fin.import_batch SET status = 'PUBLISHED', published_at = now() WHERE id = 1;
UPDATE fin.claim SET published_at = now();
UPDATE fin.payment SET published_at = now();
UPDATE fin.payment_allocation SET published_at = now();
SELECT pg_temp.expect('FIN-M', (SELECT outstanding_amount = 900000 AND payment_status = 'PARTLY_PAID' FROM fin.v_claim_balance WHERE claim_id = 1),
  'balance view: 1,500,000 − (1,000,000 − 400,000) = 900,000, status PARTLY_PAID');
SELECT pg_temp.expect_err('FIN-N', $q$UPDATE fin.claim SET description = 'x', row_version = row_version + 5 WHERE id = 1$q$, 'ICCP-CORE-001');

-- ---------- Row-level security: staff see only their own records ----------
SELECT set_config('iccp.fin_scope', '', false), set_config('iccp.staff_id', '1', false), set_config('iccp.actor', 'asha', false);
SELECT pg_temp.expect('RLS-A', (SELECT count(*) = 1 FROM fin.claim), 'staff 1 sees exactly 1 claim (own)');
SELECT pg_temp.expect('RLS-B', (SELECT count(*) = 0 FROM fin.claim WHERE source_ref = 'CLM-2'), 'staff 1 cannot read staff 2''s claim by reference (IDOR)');
SELECT pg_temp.expect('RLS-C', (SELECT count(*) = 1 FROM fin.v_claim_balance), 'balance view honours RLS of the caller');
SELECT pg_temp.expect('RLS-D', (SELECT count(*) = 0 FROM fin.payment WHERE staff_id = 2), 'staff 1 cannot read staff 2''s payments');
SELECT pg_temp.expect_err('RLS-E', $q$INSERT INTO fin.claim (staff_id, batch_id, source_ref, claim_type_id, description, approved_amount, approval_date) VALUES (1, 1, 'CLM-X', 1, 'self-made', 1, current_date)$q$, 'row-level security');
INSERT INTO fin.claim_query (staff_id, claim_id, category, description) VALUES (1, 1, 'AMOUNT', 'Second instalment not received');
SELECT pg_temp.expect('RLS-F', (SELECT payment_status = 'UNDER_QUERY' FROM fin.v_claim_balance WHERE claim_id = 1), 'own query accepted; claim shows UNDER_QUERY');
SELECT pg_temp.expect_err('RLS-G', $q$INSERT INTO fin.claim_query (staff_id, claim_id, category, description) VALUES (2, 1, 'OTHER', 'spoof')$q$, 'row-level security');
SELECT set_config('iccp.staff_id', '', false), set_config('iccp.actor', 'system', false);

-- ---------- Audit trail ----------
INSERT INTO sec.audit_event (actor_subject, module, action, resource_type, resource_id, outcome) VALUES
  ('asha', 'FIN', 'CLAIM_VIEW', 'claim', 'CLM-1', 'SUCCESS'),
  ('asha', 'FIN', 'CLAIM_VIEW', 'claim', 'CLM-2', 'DENIED'),
  ('fin.reviewer', 'FIN', 'BATCH_APPROVE', 'import_batch', '1', 'SUCCESS');
INSERT INTO sec.audit_event (stream, actor_subject, module, action, resource_type, resource_id, outcome) VALUES ('RESTRICTED', 'integrity.officer', 'FDB', 'CASE_VIEW', 'protected_case', 'x', 'SUCCESS');
SELECT pg_temp.expect_err('AUD-A', $q$UPDATE sec.audit_event SET action = 'NOTHING'$q$, 'permission denied');
SELECT pg_temp.expect_err('AUD-B', $q$DELETE FROM sec.audit_event$q$, 'permission denied');
SELECT pg_temp.expect_err('AUD-C', $q$TRUNCATE sec.audit_event$q$, 'permission denied');
SELECT pg_temp.expect('AUD-D', (SELECT sec.verify_audit_chain() IS NULL), 'hash chain verifies intact');
SELECT pg_temp.expect('AUD-E', (SELECT count(*) = 0 FROM sec.audit_event WHERE stream = 'RESTRICTED'), 'application cannot read the restricted stream');

-- ---------- Communication ----------
SELECT pg_temp.expect_err('COM-A', $q$INSERT INTO com.announcement (category, status, author_staff_id, effective_at) VALUES ('NOTICE', 'PUBLISHED', 1, now())$q$, 'announcement_check');
SELECT pg_temp.expect_err('COM-B', $q$INSERT INTO com.announcement (category, status, author_staff_id, approver_staff_id, effective_at) VALUES ('CIRCULAR', 'APPROVED', 1, 1, now())$q$, 'announcement_check');
SELECT pg_temp.expect_err('COM-C', $q$INSERT INTO com.announcement (category, status, author_staff_id, effective_at, published_at) VALUES ('CIRCULAR', 'PUBLISHED', 1, now(), now())$q$, 'announcement_check');
INSERT INTO com.announcement (category, author_staff_id, effective_at) VALUES ('NOTICE', 1, now());
INSERT INTO com.announcement_version (announcement_id, version_no, title_en) SELECT max(id), 1, 'Office closure' FROM com.announcement;
SELECT pg_temp.expect_err('COM-D', $q$UPDATE com.announcement_version SET title_en = 'changed'$q$, 'ICCP-CORE-003');
SELECT pg_temp.expect_err('COM-E', $q$DELETE FROM com.announcement_version$q$, 'ICCP-CORE-002');

-- ---------- Meetings ----------
INSERT INTO mtg.meeting (meeting_type_id, title, starts_at, ends_at, mode, venue, chair_staff_id, secretary_staff_id, confidentiality)
VALUES (2, 'Management Meeting No. 5', '2027-05-03 09:00+03', '2027-05-03 12:00+03', 'PHYSICAL', 'Board Room', 2, 1, 'RESTRICTED');
SELECT pg_temp.expect_err('MTG-A', $q$INSERT INTO mtg.recording (meeting_id, object_key, file_name, content_type, size_bytes, sha256, source) VALUES (1, 'k', 'a.m4a', 'audio/mp4', 10, repeat('0',64), 'UPLOAD')$q$, 'ICCP-MTG-001');
INSERT INTO mtg.minutes_version (meeting_id, version_no, content, ai_sections_unreviewed) VALUES (1, 1, '{}', 2);
SELECT pg_temp.expect_err('MTG-B', $q$UPDATE mtg.minutes_version SET status = 'APPROVED', approved_by_staff_id = 2, approved_at = now() WHERE id = 1$q$, 'minutes_version_check');
UPDATE mtg.minutes_version SET ai_sections_unreviewed = 0 WHERE id = 1;
UPDATE mtg.minutes_version SET status = 'APPROVED', approved_by_staff_id = 2, approved_at = now() WHERE id = 1;
SELECT pg_temp.expect_err('MTG-C', $q$UPDATE mtg.minutes_version SET content = '{"changed":true}' WHERE id = 1$q$, 'ICCP-MTG-003');
UPDATE mtg.minutes_version SET pdf_object_key = 'minutes/1.pdf' WHERE id = 1;
SELECT pg_temp.expect('MTG-D', true, 'stamped PDF can be attached once after approval');
SELECT pg_temp.expect_err('MTG-E', $q$DELETE FROM mtg.minutes_version WHERE id = 1$q$, 'ICCP-MTG-002');

-- ---------- Documents ----------
INSERT INTO doc.document_type (code, name_en, name_sw, workflow_code, retention_period) VALUES ('POLICY', 'Policy', 'Sera', 'DOC_POLICY', '7 years');
INSERT INTO doc.document (document_type_id, title, owner_unit_id, classification, language) VALUES (1, 'ICT Policy', 1, 'INTERNAL', 'en');
INSERT INTO doc.document_version (document_id, major, minor, object_key, file_name, content_type, size_bytes, sha256, status, approved_by_staff_id, published_at)
VALUES (1, 1, 0, 'doc/1', 'ict-policy.pdf', 'application/pdf', 1000, repeat('1',64), 'PUBLISHED', 2, now());
SELECT pg_temp.expect_err('DOC-A', $q$UPDATE doc.document_version SET object_key = 'doc/evil' WHERE id = 1$q$, 'ICCP-DOC-002');
UPDATE doc.document_version SET status = 'SUPERSEDED' WHERE id = 1;
SELECT pg_temp.expect('DOC-B', true, 'PUBLISHED → SUPERSEDED allowed');
SELECT pg_temp.expect_err('DOC-C', $q$UPDATE doc.document_version SET status = 'PUBLISHED' WHERE id = 1$q$, 'ICCP-DOC-002');

-- ---------- Workflow, security, feedback ----------
INSERT INTO wf.workflow_definition (code, def_version, module, name, definition) VALUES ('REQ_STD', 1, 'REQ', 'Standard request', '{}');
INSERT INTO wf.workflow_instance (definition_id, module, resource_type, resource_public_id, status, started_by_staff_id) VALUES (1, 'REQ', 'service_request', gen_random_uuid(), 'RUNNING', 1);
SELECT pg_temp.expect_err('WF-A', $q$INSERT INTO wf.approval_step (instance_id, step_no, approver_staff_id, decision) VALUES (1, 1, 1, 'APPROVED')$q$, 'ICCP-WF-001');
SELECT pg_temp.expect_err('WF-B', $q$INSERT INTO wf.approval_step (instance_id, step_no, approver_staff_id, decision) VALUES (1, 1, 2, 'REJECTED')$q$, 'approval_step_check');
INSERT INTO sec.user_account (staff_id, keycloak_subject, account_type) VALUES (1, 'kc-asha', 'STAFF');
SELECT pg_temp.expect_err('SEC-A', $q$INSERT INTO sec.role_assignment (user_account_id, role_id) SELECT 1, id FROM sec.role WHERE code = 'FIN_REVIEWER'$q$, 'ICCP-SEC-001');
INSERT INTO fdb.survey (title_en, mode, audience, opens_at, closes_at, owner_staff_id) VALUES ('Staff satisfaction 2027', 'ANONYMOUS', '{}', now(), now() + interval '14 days', 1);
SELECT pg_temp.expect_err('FDB-A', $q$INSERT INTO fdb.survey_response (survey_id, respondent_staff_id, answers) VALUES (1, 1, '{}')$q$, 'ICCP-FDB-001');
INSERT INTO fdb.survey_response (survey_id, answers) VALUES (1, '{"1":4}');
SELECT pg_temp.expect('FDB-B', true, 'anonymous response stored without respondent');
SELECT pg_temp.expect('FDB-C', NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'fdb' AND table_name IN ('protected_case','protected_case_message')
  AND column_name IN ('created_by','updated_by','client_ip','ip_address','device_id','user_agent','staff_id','reporter_staff_id','session_id')), 'protected case tables have no identity, IP or device columns');
SELECT pg_temp.expect_err('NTF-A', $q$INSERT INTO ntf.template (event_type, channel, language, tpl_version, body, status) VALUES ('PaymentPublished', 'SMS', 'en', 9, 'You were paid {{ amount }}', 'PUBLISHED')$q$, 'template_check');
