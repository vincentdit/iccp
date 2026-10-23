-- Checks that need roles other than iccp_app. Run after verify_integrity.sql.
-- Part 1 as iccp_auditor:   psql -U iccp_auditor   -d <db> -v part=auditor   -f verify_roles.sql
-- Part 2 as iccp_retention: psql -U iccp_retention -d <db> -v part=retention -f verify_roles.sql
-- Part 3 as iccp_owner (tamper simulation): psql -U iccp_owner -d <db> -v part=owner -f verify_roles.sql
\set ON_ERROR_STOP on
CREATE FUNCTION pg_temp.expect(p_id text, p_ok boolean, p_msg text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN RAISE NOTICE '% % — %', CASE WHEN p_ok THEN 'PASS' ELSE 'FAIL' END, p_id, p_msg; END $$;

\if :{?part}
\else
  \set part none
\endif

SELECT :'part' = 'auditor' AS is_auditor, :'part' = 'retention' AS is_retention, :'part' = 'owner' AS is_owner \gset

\if :is_auditor
SELECT pg_temp.expect('AUD-F', (SELECT count(*) = 1 FROM sec.audit_event WHERE stream = 'RESTRICTED'), 'auditor reads the restricted stream');
\endif

\if :is_retention
DELETE FROM com.announcement_version;
SELECT pg_temp.expect('RET-A', NOT EXISTS (SELECT 1 FROM com.announcement_version), 'retention role may dispose of append-only records');
DO $$ BEGIN
  DELETE FROM sec.audit_event;
  RAISE NOTICE 'FAIL RET-B — retention role deleted audit events';
EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'PASS RET-B — audit deletion rejected: %', SQLERRM; END $$;
\endif

\if :is_owner
-- Simulate a privileged tamper: bypass the guard, change one event, then verify.
ALTER TABLE sec.audit_event DISABLE TRIGGER trg_a_append_only;
UPDATE sec.audit_event SET outcome = 'SUCCESS' WHERE action = 'CLAIM_VIEW' AND outcome = 'DENIED';
ALTER TABLE sec.audit_event ENABLE TRIGGER trg_a_append_only;
SELECT pg_temp.expect('AUD-G', (SELECT sec.verify_audit_chain() = 2), 'tampering with event 2 is detected by chain verification');
\endif
