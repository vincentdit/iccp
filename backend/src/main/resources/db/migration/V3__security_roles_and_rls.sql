-- V3__security_roles_and_rls.sql
-- TIRDO ICCP — generated from backend/db-model (Doc 06, Draft 4.0). Do not edit by hand.
-- Target: PostgreSQL 16. Applied by Flyway as role iccp_owner.

-- Database privileges and row-level security (defence in depth; the application
-- still enforces object authorisation on every request — Doc 05 §7).
-- The application sets, per transaction:
--   SET LOCAL iccp.actor     = '<keycloak subject>';
--   SET LOCAL iccp.staff_id  = '<dir.staff_member.id of the caller>';
--   SET LOCAL iccp.fin_scope = 'ALL'   -- only when the caller holds a FIN_* role (or a system job)

DO $$
DECLARE s text;
BEGIN
  FOREACH s IN ARRAY ARRAY['core','dir','sec','wf','integ','ntf','com','fin','mtg','tsk','msg','doc','prj','req','trn','ana','fdb'] LOOP
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO iccp_app, iccp_retention, iccp_report, iccp_auditor', s);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO iccp_app', s);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO iccp_app', s);
    EXECUTE format('GRANT SELECT, DELETE ON ALL TABLES IN SCHEMA %I TO iccp_retention', s);
  END LOOP;
END $$;

-- Audit: the application may only insert. Auditors read; nobody updates or deletes.
REVOKE UPDATE, DELETE, TRUNCATE ON sec.audit_event FROM iccp_app, iccp_retention;
GRANT SELECT ON sec.audit_event TO iccp_auditor;
GRANT EXECUTE ON FUNCTION sec.verify_audit_chain() TO iccp_auditor, iccp_app;

-- Reporting role sees only aggregate snapshots and the export log.
GRANT SELECT ON ana.metric_snapshot, ana.report_export TO iccp_report;

-- Protected reports are never readable by the reporting or retention roles.
REVOKE ALL ON fdb.protected_case, fdb.protected_case_message FROM iccp_report, iccp_retention;

-- -------------------------------------------------------------------
-- Row-level security: staff financial data
-- -------------------------------------------------------------------
CREATE FUNCTION fin.can_see(p_staff_id bigint) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('iccp.fin_scope', true), '') = 'ALL'
      OR p_staff_id = nullif(current_setting('iccp.staff_id', true), '')::bigint
$$;

ALTER TABLE fin.claim              ENABLE ROW LEVEL SECURITY;
ALTER TABLE fin.payment            ENABLE ROW LEVEL SECURITY;
ALTER TABLE fin.payment_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE fin.adjustment         ENABLE ROW LEVEL SECURITY;
ALTER TABLE fin.claim_query        ENABLE ROW LEVEL SECURITY;
ALTER TABLE fin.query_message      ENABLE ROW LEVEL SECURITY;

-- Staff see only their own published rows; Finance scope sees everything.
CREATE POLICY p_claim ON fin.claim TO iccp_app
  USING (fin.can_see(staff_id) AND (published_at IS NOT NULL OR current_setting('iccp.fin_scope', true) = 'ALL'))
  WITH CHECK (current_setting('iccp.fin_scope', true) = 'ALL');
CREATE POLICY p_payment ON fin.payment TO iccp_app
  USING (fin.can_see(staff_id) AND (published_at IS NOT NULL OR current_setting('iccp.fin_scope', true) = 'ALL'))
  WITH CHECK (current_setting('iccp.fin_scope', true) = 'ALL');
CREATE POLICY p_allocation ON fin.payment_allocation TO iccp_app
  USING (EXISTS (SELECT 1 FROM fin.claim c WHERE c.id = claim_id AND fin.can_see(c.staff_id))
         AND (published_at IS NOT NULL OR current_setting('iccp.fin_scope', true) = 'ALL'))
  WITH CHECK (current_setting('iccp.fin_scope', true) = 'ALL');
CREATE POLICY p_adjustment ON fin.adjustment TO iccp_app
  USING (EXISTS (SELECT 1 FROM fin.claim c WHERE c.id = claim_id AND fin.can_see(c.staff_id))
         AND (published_at IS NOT NULL OR current_setting('iccp.fin_scope', true) = 'ALL'))
  WITH CHECK (current_setting('iccp.fin_scope', true) = 'ALL');
-- Staff may create and read their own queries; Finance may read and update all.
CREATE POLICY p_query ON fin.claim_query TO iccp_app
  USING (fin.can_see(staff_id)) WITH CHECK (fin.can_see(staff_id));
-- Query messages follow the visibility of their query.
CREATE POLICY p_query_message ON fin.query_message TO iccp_app
  USING (EXISTS (SELECT 1 FROM fin.claim_query q WHERE q.id = query_id))
  WITH CHECK (EXISTS (SELECT 1 FROM fin.claim_query q WHERE q.id = query_id));

-- Retention role can see everything it must dispose of.
CREATE POLICY p_claim_ret      ON fin.claim              TO iccp_retention USING (true);
CREATE POLICY p_payment_ret    ON fin.payment            TO iccp_retention USING (true);
CREATE POLICY p_allocation_ret ON fin.payment_allocation TO iccp_retention USING (true);
CREATE POLICY p_adjustment_ret ON fin.adjustment         TO iccp_retention USING (true);
CREATE POLICY p_query_ret      ON fin.claim_query        TO iccp_retention USING (true);
CREATE POLICY p_qmsg_ret       ON fin.query_message      TO iccp_retention USING (true);

-- -------------------------------------------------------------------
-- Row-level security: restricted audit stream
-- -------------------------------------------------------------------
ALTER TABLE sec.audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_audit_app_insert ON sec.audit_event FOR INSERT TO iccp_app WITH CHECK (true);
CREATE POLICY p_audit_app_read   ON sec.audit_event FOR SELECT TO iccp_app USING (stream = 'MAIN');
CREATE POLICY p_audit_auditor    ON sec.audit_event FOR SELECT TO iccp_auditor USING (true);

-- Default privileges for objects created by later migrations.
ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO iccp_app;
ALTER DEFAULT PRIVILEGES GRANT USAGE, SELECT ON SEQUENCES TO iccp_app;
