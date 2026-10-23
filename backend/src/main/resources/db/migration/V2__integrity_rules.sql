-- V2__integrity_rules.sql
-- TIRDO ICCP — generated from backend/db-model (Doc 06, Draft 4.0). Do not edit by hand.
-- Target: PostgreSQL 16. Applied by Flyway as role iccp_owner.

-- Business invariants that must hold even if application code is wrong.
-- Error codes ICCP-<MODULE>-nnn are mapped to user messages in the API error model (Doc 05 §12).

-- ===================================================================
-- SEC: tamper-evident audit chain
-- ===================================================================
CREATE SEQUENCE sec.audit_chain_seq;

CREATE FUNCTION sec.audit_event_hash(e sec.audit_event, prev bytea) RETURNS bytea
LANGUAGE sql IMMUTABLE AS $$
  SELECT sha256(coalesce(prev, '\x'::bytea) || convert_to(concat_ws('|',
           e.chain_seq, extract(epoch FROM e.occurred_at)::text, e.stream, e.actor_subject,
           e.actor_staff_id, e.module, e.action, e.resource_type, e.resource_id, e.outcome,
           host(e.client_ip), e.request_id, e.details::text), 'UTF8'))
$$;

CREATE FUNCTION sec.audit_chain() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, sec AS $$
DECLARE v_prev bytea;
BEGIN
  -- Serialise writers so chain order equals chain_seq order.
  PERFORM pg_advisory_xact_lock(hashtext('sec.audit_event'));
  SELECT row_hash INTO v_prev FROM sec.audit_event ORDER BY chain_seq DESC LIMIT 1;
  NEW.chain_seq := nextval('sec.audit_chain_seq');
  NEW.prev_hash := v_prev;
  NEW.row_hash  := sec.audit_event_hash(NEW, v_prev);
  RETURN NEW;
END $$;
CREATE TRIGGER trg_b_audit_chain BEFORE INSERT ON sec.audit_event
  FOR EACH ROW EXECUTE FUNCTION sec.audit_chain();
CREATE TRIGGER trg_c_audit_no_truncate BEFORE TRUNCATE ON sec.audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION core.append_only();

-- Returns the chain_seq of the first broken link, or NULL if the chain is intact.
CREATE FUNCTION sec.verify_audit_chain() RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, sec AS $$
DECLARE r sec.audit_event; v_prev bytea := NULL;
BEGIN
  FOR r IN SELECT * FROM sec.audit_event ORDER BY chain_seq LOOP
    IF r.prev_hash IS DISTINCT FROM v_prev OR r.row_hash <> sec.audit_event_hash(r, v_prev) THEN
      RETURN r.chain_seq;
    END IF;
    v_prev := r.row_hash;
  END LOOP;
  RETURN NULL;
END $$;

-- Privileged roles need an end date and approval.
CREATE FUNCTION sec.check_role_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT privileged FROM sec.role WHERE id = NEW.role_id) AND (NEW.ends_on IS NULL OR NEW.approved_by IS NULL) THEN
    RAISE EXCEPTION 'ICCP-SEC-001: privileged role assignments need ends_on and approved_by' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_b_role_assignment BEFORE INSERT OR UPDATE ON sec.role_assignment
  FOR EACH ROW EXECUTE FUNCTION sec.check_role_assignment();

-- ===================================================================
-- WF: no self-approval
-- ===================================================================
CREATE FUNCTION wf.check_approval_step() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.decision IN ('APPROVED','REJECTED','RETURNED')
     AND NEW.approver_staff_id = (SELECT started_by_staff_id FROM wf.workflow_instance WHERE id = NEW.instance_id) THEN
    RAISE EXCEPTION 'ICCP-WF-001: an initiator cannot decide their own approval step' USING ERRCODE = '23514';
  END IF;
  IF NEW.decision <> 'PENDING' AND NEW.decided_at IS NULL THEN
    NEW.decided_at := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_b_approval_step BEFORE INSERT OR UPDATE ON wf.approval_step
  FOR EACH ROW EXECUTE FUNCTION wf.check_approval_step();

-- ===================================================================
-- FIN: allocation invariant (BRL-05) and reversal rules
-- ===================================================================
CREATE FUNCTION fin.check_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  p fin.payment; c fin.claim; o fin.payment_allocation;
  v_pay numeric; v_claim numeric; v_adj numeric; v_rev numeric;
BEGIN
  -- Row locks serialise concurrent allocations against the same payment or claim.
  SELECT * INTO p FROM fin.payment WHERE id = NEW.payment_id FOR UPDATE;
  SELECT * INTO c FROM fin.claim   WHERE id = NEW.claim_id   FOR UPDATE;

  IF p.status <> 'CONFIRMED' THEN
    RAISE EXCEPTION 'ICCP-FIN-001: payment % is not confirmed', p.source_ref USING ERRCODE = '23514'; END IF;
  IF c.status <> 'APPROVED' THEN
    RAISE EXCEPTION 'ICCP-FIN-002: claim % is not approved', c.source_ref USING ERRCODE = '23514'; END IF;
  IF p.staff_id <> c.staff_id THEN
    RAISE EXCEPTION 'ICCP-FIN-003: payment and claim belong to different staff' USING ERRCODE = '23514'; END IF;
  IF p.currency <> c.currency THEN
    RAISE EXCEPTION 'ICCP-FIN-004: currency mismatch (% vs %)', p.currency, c.currency USING ERRCODE = '23514'; END IF;

  IF NEW.reverses_allocation_id IS NOT NULL THEN
    SELECT * INTO o FROM fin.payment_allocation WHERE id = NEW.reverses_allocation_id FOR UPDATE;
    IF o.amount < 0 OR o.payment_id <> NEW.payment_id OR o.claim_id <> NEW.claim_id THEN
      RAISE EXCEPTION 'ICCP-FIN-005: a reversal must reference a positive allocation of the same payment and claim' USING ERRCODE = '23514'; END IF;
    SELECT coalesce(sum(amount), 0) INTO v_rev FROM fin.payment_allocation
      WHERE reverses_allocation_id = o.id AND id <> NEW.id;
    IF o.amount + v_rev + NEW.amount < 0 THEN
      RAISE EXCEPTION 'ICCP-FIN-006: reversal exceeds the original allocation' USING ERRCODE = '23514'; END IF;
  END IF;

  SELECT coalesce(sum(amount), 0) INTO v_pay   FROM fin.payment_allocation WHERE payment_id = NEW.payment_id AND id <> NEW.id;
  SELECT coalesce(sum(amount), 0) INTO v_claim FROM fin.payment_allocation WHERE claim_id   = NEW.claim_id   AND id <> NEW.id;
  SELECT coalesce(sum(amount), 0) INTO v_adj   FROM fin.adjustment         WHERE claim_id   = NEW.claim_id;

  IF v_pay + NEW.amount > p.amount THEN
    RAISE EXCEPTION 'ICCP-FIN-007: allocations (%) would exceed payment amount (%)', v_pay + NEW.amount, p.amount USING ERRCODE = '23514'; END IF;
  IF v_claim + NEW.amount > c.approved_amount + v_adj THEN
    RAISE EXCEPTION 'ICCP-FIN-008: allocations (%) would exceed amount owed on claim (%)', v_claim + NEW.amount, c.approved_amount + v_adj USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_b_check_allocation BEFORE INSERT ON fin.payment_allocation
  FOR EACH ROW EXECUTE FUNCTION fin.check_allocation();

-- Staff-facing balance (BRL-02). security_invoker so row-level security of the caller applies.
CREATE VIEW fin.v_claim_balance WITH (security_invoker = true, security_barrier = true) AS
SELECT c.id AS claim_id, c.public_id, c.staff_id, c.claim_type_id, c.description, c.currency,
       c.approval_date, c.approved_amount,
       coalesce(a.paid, 0)                                    AS paid_amount,
       coalesce(j.adj, 0)                                     AS adjustment_amount,
       c.approved_amount + coalesce(j.adj, 0) - coalesce(a.paid, 0) AS outstanding_amount,
       CASE WHEN c.status = 'CANCELLED' THEN 'CANCELLED'
            WHEN EXISTS (SELECT 1 FROM fin.claim_query qq WHERE qq.claim_id = c.id AND qq.status IN ('OPEN','RESPONDED','REOPENED','ESCALATED')) THEN 'UNDER_QUERY'
            WHEN coalesce(a.paid, 0) = 0 THEN 'APPROVED'
            WHEN c.approved_amount + coalesce(j.adj, 0) - coalesce(a.paid, 0) <= 0 THEN 'FULLY_PAID'
            ELSE 'PARTLY_PAID' END                            AS payment_status
FROM fin.claim c
LEFT JOIN (SELECT claim_id, sum(amount) AS paid FROM fin.payment_allocation WHERE published_at IS NOT NULL GROUP BY claim_id) a ON a.claim_id = c.id
LEFT JOIN (SELECT claim_id, sum(amount) AS adj  FROM fin.adjustment         WHERE published_at IS NOT NULL GROUP BY claim_id) j ON j.claim_id = c.id
WHERE c.published_at IS NOT NULL;
COMMENT ON VIEW fin.v_claim_balance IS 'Outstanding = approved + published adjustments − published allocations (reversals negative).';

-- ===================================================================
-- MTG: recording notice, locked minutes
-- ===================================================================
CREATE FUNCTION mtg.check_recording() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.source <> 'MMS_MIGRATION'
     AND (SELECT recording_notice_at FROM mtg.meeting WHERE id = NEW.meeting_id) IS NULL THEN
    RAISE EXCEPTION 'ICCP-MTG-001: confirm that participants were notified before adding a recording' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_b_check_recording BEFORE INSERT ON mtg.recording
  FOR EACH ROW EXECUTE FUNCTION mtg.check_recording();

CREATE FUNCTION mtg.lock_approved_minutes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'APPROVED' THEN
    IF TG_OP = 'DELETE' THEN
      RAISE EXCEPTION 'ICCP-MTG-002: approved minutes cannot be deleted' USING ERRCODE = '42501';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['pdf_object_key','updated_at','updated_by','row_version'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['pdf_object_key','updated_at','updated_by','row_version'])
       OR (OLD.pdf_object_key IS NOT NULL AND NEW.pdf_object_key IS DISTINCT FROM OLD.pdf_object_key) THEN
      RAISE EXCEPTION 'ICCP-MTG-003: approved minutes are locked' USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER trg_b_lock_minutes BEFORE UPDATE OR DELETE ON mtg.minutes_version
  FOR EACH ROW EXECUTE FUNCTION mtg.lock_approved_minutes();

-- ===================================================================
-- DOC: published versions are immutable
-- ===================================================================
CREATE FUNCTION doc.lock_published_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('PUBLISHED','SUPERSEDED') THEN
    IF TG_OP = 'DELETE' AND current_user <> 'iccp_retention' THEN
      RAISE EXCEPTION 'ICCP-DOC-001: published versions cannot be deleted outside authorised disposal' USING ERRCODE = '42501';
    ELSIF TG_OP = 'UPDATE' AND (
          (to_jsonb(NEW) - ARRAY['status','updated_at','updated_by','row_version'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','updated_at','updated_by','row_version'])
          OR NOT (OLD.status = NEW.status OR (OLD.status = 'PUBLISHED' AND NEW.status = 'SUPERSEDED'))) THEN
      RAISE EXCEPTION 'ICCP-DOC-002: published versions are immutable (only PUBLISHED → SUPERSEDED is allowed)' USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER trg_b_lock_docver BEFORE UPDATE OR DELETE ON doc.document_version
  FOR EACH ROW EXECUTE FUNCTION doc.lock_published_version();

-- ===================================================================
-- FDB: anonymous surveys stay anonymous
-- ===================================================================
CREATE FUNCTION fdb.check_survey_response() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_mode text;
BEGIN
  SELECT mode INTO v_mode FROM fdb.survey WHERE id = NEW.survey_id;
  IF v_mode = 'ANONYMOUS' AND NEW.respondent_staff_id IS NOT NULL THEN
    RAISE EXCEPTION 'ICCP-FDB-001: anonymous survey responses cannot carry a respondent' USING ERRCODE = '23514';
  ELSIF v_mode = 'IDENTIFIED' AND NEW.respondent_staff_id IS NULL THEN
    RAISE EXCEPTION 'ICCP-FDB-002: identified survey responses need a respondent' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_b_check_survey_response BEFORE INSERT OR UPDATE ON fdb.survey_response
  FOR EACH ROW EXECUTE FUNCTION fdb.check_survey_response();
