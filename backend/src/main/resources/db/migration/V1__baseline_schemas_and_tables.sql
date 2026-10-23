-- V1__baseline_schemas_and_tables.sql
-- TIRDO ICCP — generated from backend/db-model (Doc 06, Draft 4.0). Do not edit by hand.
-- Target: PostgreSQL 16. Applied by Flyway as role iccp_owner.

SET client_min_messages = warning;

CREATE SCHEMA IF NOT EXISTS core;
COMMENT ON SCHEMA core IS 'Shared database functions only (no tables).';
CREATE SCHEMA IF NOT EXISTS dir;
COMMENT ON SCHEMA dir IS 'Staff directory and organisation structure (shared kernel referenced by all modules).';
CREATE SCHEMA IF NOT EXISTS sec;
COMMENT ON SCHEMA sec IS 'Accounts, roles, audit, retention, legal holds, feature flags.';
CREATE SCHEMA IF NOT EXISTS wf;
COMMENT ON SCHEMA wf IS 'Shared workflow engine: definitions, instances, approval steps, delegation, calendar.';
CREATE SCHEMA IF NOT EXISTS integ;
COMMENT ON SCHEMA integ IS 'Inbound idempotency, transactional outbox and integration endpoints.';
CREATE SCHEMA IF NOT EXISTS ntf;
COMMENT ON SCHEMA ntf IS 'Notification templates, notifications, delivery attempts and preferences.';
CREATE SCHEMA IF NOT EXISTS com;
COMMENT ON SCHEMA com IS 'Announcements, versions, audiences, recipients and attachments.';
CREATE SCHEMA IF NOT EXISTS fin;
COMMENT ON SCHEMA fin IS 'Finance import batches, claims, payments, allocations, adjustments, quarantine and queries.';
CREATE SCHEMA IF NOT EXISTS mtg;
COMMENT ON SCHEMA mtg IS 'Meeting types, meetings, participants, agenda, recordings, transcripts, minutes and resolutions.';
CREATE SCHEMA IF NOT EXISTS tsk;
COMMENT ON SCHEMA tsk IS 'Tasks, task events, extension requests and attachments.';
CREATE SCHEMA IF NOT EXISTS msg;
COMMENT ON SCHEMA msg IS 'Conversations, members, messages, revisions, attachments and reports.';
CREATE SCHEMA IF NOT EXISTS doc;
COMMENT ON SCHEMA doc IS 'Document types, documents, versions, access, links and disposal certificates.';
CREATE SCHEMA IF NOT EXISTS prj;
COMMENT ON SCHEMA prj IS 'Workspaces, members, milestones, deliverables and progress reports.';
CREATE SCHEMA IF NOT EXISTS req;
COMMENT ON SCHEMA req IS 'Request categories, types, requests and attachments.';
CREATE SCHEMA IF NOT EXISTS trn;
COMMENT ON SCHEMA trn IS 'Training offerings, applications, records and needs.';
CREATE SCHEMA IF NOT EXISTS ana;
COMMENT ON SCHEMA ana IS 'Metric snapshots and report export log.';
CREATE SCHEMA IF NOT EXISTS fdb;
COMMENT ON SCHEMA fdb IS 'Suggestions, surveys, anonymous responses and protected cases.';

CREATE FUNCTION core.touch_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- JPA @Version sends OLD+1; plain SQL updates leave it unchanged and get it incremented here.
  IF NEW.row_version = OLD.row_version THEN
    NEW.row_version := OLD.row_version + 1;
  ELSIF NEW.row_version <> OLD.row_version + 1 THEN
    RAISE EXCEPTION 'ICCP-CORE-001: invalid row_version on %.% (current %)', TG_TABLE_SCHEMA, TG_TABLE_NAME, OLD.row_version
      USING ERRCODE = '40001';
  END IF;
  NEW.updated_at  := now();
  NEW.updated_by  := coalesce(current_setting('iccp.actor', true), current_user);
  RETURN NEW;
END $$;
COMMENT ON FUNCTION core.touch_row() IS 'Sets updated_at/updated_by and maintains row_version for optimistic locking.';

CREATE FUNCTION core.append_only() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  allowed text[] := TG_ARGV::text[] || ARRAY['updated_at','updated_by','row_version'];
BEGIN
  IF TG_OP = 'TRUNCATE' THEN
    RAISE EXCEPTION 'ICCP-CORE-004: %.% cannot be truncated', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'DELETE' THEN
    IF current_user = 'iccp_retention' AND TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME <> 'sec.audit_event' THEN
      RETURN OLD;  -- authorised disposal under the retention schedule
    END IF;
    RAISE EXCEPTION 'ICCP-CORE-002: %.% is append-only; DELETE is not permitted', TG_TABLE_SCHEMA, TG_TABLE_NAME
      USING ERRCODE = '42501';
  END IF;
  IF (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
    RAISE EXCEPTION 'ICCP-CORE-003: %.% is append-only; %', TG_TABLE_SCHEMA, TG_TABLE_NAME,
      coalesce('only ' || nullif(array_to_string(TG_ARGV::text[], ', '), '') || ' may change', 'rows cannot be changed')
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION core.append_only() IS 'Blocks DELETE/TRUNCATE and blocks UPDATE of any column not passed as a trigger argument.';

-- dir.org_unit
CREATE TABLE dir.org_unit (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text,
  unit_type                    text NOT NULL CHECK (unit_type IN ('DIRECTORATE','DEPARTMENT','SECTION','UNIT','LABORATORY','OFFICE')),
  parent_id                    bigint REFERENCES dir.org_unit(id),
  effective_from               date NOT NULL,
  effective_to                 date,
  hr_ref                       text UNIQUE,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
COMMENT ON TABLE dir.org_unit IS 'Organisation unit (directorate, department, section, unit, laboratory, office), effective-dated.';
CREATE INDEX ix_org_unit_parent_id ON dir.org_unit (parent_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.org_unit FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- dir.position
CREATE TABLE dir.position (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  title_en                     text NOT NULL,
  title_sw                     text,
  org_unit_id                  bigint NOT NULL REFERENCES dir.org_unit(id),
  is_head                      boolean NOT NULL DEFAULT false,
  hr_ref                       text UNIQUE,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE dir.position IS 'Established position within a unit.';
CREATE INDEX ix_position_org_unit_id ON dir.position (org_unit_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.position FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- dir.staff_member
CREATE TABLE dir.staff_member (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  employee_no                  text NOT NULL UNIQUE,
  first_name                   text NOT NULL,
  middle_name                  text,
  last_name                    text NOT NULL,
  title                        text,
  position_id                  bigint REFERENCES dir.position(id),
  org_unit_id                  bigint NOT NULL REFERENCES dir.org_unit(id),
  line_manager_id              bigint REFERENCES dir.staff_member(id),
  employment_status            text NOT NULL CHECK (employment_status IN ('ACTIVE','ON_LEAVE','SUSPENDED','LEFT')),
  staff_category               text,
  duty_station                 text,
  official_email               text NOT NULL,
  office_phone                 text,
  mobile_phone                 text,
  mobile_visible               boolean NOT NULL DEFAULT false,
  preferred_language           char(2) NOT NULL DEFAULT 'en' CHECK (preferred_language IN ('en','sw')),
  photo_object_key             text,
  bio                          text CHECK (length(bio) <= 1000),
  hr_synced_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (line_manager_id IS NULL OR line_manager_id <> id)
);
COMMENT ON TABLE dir.staff_member IS 'Employee record synchronised from the HR master; referenced by all modules.';
CREATE INDEX ix_staff_member_position_id ON dir.staff_member (position_id);
CREATE INDEX ix_staff_member_org_unit_id ON dir.staff_member (org_unit_id);
CREATE INDEX ix_staff_member_line_manager_id ON dir.staff_member (line_manager_id);
CREATE UNIQUE INDEX ux_staff_email ON dir.staff_member (lower(official_email));
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.staff_member FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- dir.staff_expertise
CREATE TABLE dir.staff_expertise (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  tag                          text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (staff_id, tag)
);
COMMENT ON TABLE dir.staff_expertise IS 'Expertise tags published by staff.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.staff_expertise FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- dir.acting_appointment
CREATE TABLE dir.acting_appointment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  position_id                  bigint NOT NULL REFERENCES dir.position(id),
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  starts_on                    date NOT NULL,
  ends_on                      date NOT NULL,
  reason                       text,
  approved_by                  text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (ends_on >= starts_on)
);
COMMENT ON TABLE dir.acting_appointment IS 'Acting assignment of a staff member to a position; approval authority follows it.';
CREATE INDEX ix_acting_appointment_position_id ON dir.acting_appointment (position_id);
CREATE INDEX ix_acting_appointment_staff_id ON dir.acting_appointment (staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.acting_appointment FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- dir.hr_sync_batch
CREATE TABLE dir.hr_sync_batch (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  file_name                    text,
  checksum                     char(64) NOT NULL UNIQUE,
  status                       text NOT NULL CHECK (status IN ('RECEIVED','APPLIED','PARTIAL','REJECTED')),
  records_total                integer NOT NULL CHECK (records_total >= 0),
  created_count                integer NOT NULL DEFAULT 0,
  updated_count                integer NOT NULL DEFAULT 0,
  deactivated_count            integer NOT NULL DEFAULT 0,
  exception_count              integer NOT NULL DEFAULT 0,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE dir.hr_sync_batch IS 'One HR synchronisation run.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.hr_sync_batch FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- dir.hr_sync_exception
CREATE TABLE dir.hr_sync_exception (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  batch_id                     bigint NOT NULL REFERENCES dir.hr_sync_batch(id),
  employee_no                  text,
  reason_code                  text NOT NULL CHECK (reason_code IN ('UNKNOWN_UNIT','UNKNOWN_MANAGER','DUPLICATE_EMAIL','MISSING_FIELD','INVALID_STATUS','OTHER')),
  payload                      jsonb NOT NULL,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','IGNORED')),
  resolved_by                  text,
  resolved_at                  timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE dir.hr_sync_exception IS 'HR record that could not be applied.';
CREATE INDEX ix_hr_sync_exception_batch_id ON dir.hr_sync_exception (batch_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON dir.hr_sync_exception FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- sec.user_account
CREATE TABLE sec.user_account (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  staff_id                     bigint UNIQUE REFERENCES dir.staff_member(id),
  keycloak_subject             text NOT NULL UNIQUE,
  account_type                 text NOT NULL CHECK (account_type IN ('STAFF','BREAK_GLASS','SERVICE')),
  status                       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  last_login_at                timestamptz,
  deprovisioned_at             timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (account_type <> 'STAFF' OR staff_id IS NOT NULL)
);
COMMENT ON TABLE sec.user_account IS 'Login account linked to a Keycloak subject.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON sec.user_account FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- sec.role
CREATE TABLE sec.role (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name                         text NOT NULL,
  privileged                   boolean NOT NULL DEFAULT false,
  requires_mfa                 boolean NOT NULL DEFAULT false,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE sec.role IS 'Role catalogue (Doc 03 §2.2).';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON sec.role FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- sec.role_assignment
CREATE TABLE sec.role_assignment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_account_id              bigint NOT NULL REFERENCES sec.user_account(id),
  role_id                      bigint NOT NULL REFERENCES sec.role(id),
  scope_org_unit_id            bigint REFERENCES dir.org_unit(id),
  starts_on                    date NOT NULL DEFAULT current_date,
  ends_on                      date,
  approved_by                  text,
  approval_ref                 text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
COMMENT ON TABLE sec.role_assignment IS 'Role granted to an account, optionally scoped to a unit, time-bound.';
CREATE INDEX ix_role_assignment_user_account_id ON sec.role_assignment (user_account_id);
CREATE INDEX ix_role_assignment_role_id ON sec.role_assignment (role_id);
CREATE INDEX ix_role_assignment_scope_org_unit_id ON sec.role_assignment (scope_org_unit_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON sec.role_assignment FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- sec.audit_event
CREATE TABLE sec.audit_event (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_seq                    bigint NOT NULL UNIQUE,
  occurred_at                  timestamptz NOT NULL DEFAULT clock_timestamp(),
  stream                       text NOT NULL DEFAULT 'MAIN' CHECK (stream IN ('MAIN','RESTRICTED')),
  actor_subject                text NOT NULL,
  actor_staff_id               bigint,
  module                       char(3) NOT NULL,
  action                       text NOT NULL,
  resource_type                text NOT NULL,
  resource_id                  text,
  outcome                      text NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','ERROR')),
  client_ip                    inet,
  request_id                   uuid,
  details                      jsonb NOT NULL DEFAULT '{}'::jsonb,
  prev_hash                    bytea,
  row_hash                     bytea NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE sec.audit_event IS 'Append-only, hash-chained audit trail. Stream RESTRICTED holds protected-report access and is readable only by AUDITOR.';
CREATE INDEX ix_audit_resource ON sec.audit_event (resource_type, resource_id, occurred_at);
CREATE INDEX ix_audit_actor ON sec.audit_event (actor_subject, occurred_at);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON sec.audit_event FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- sec.retention_policy
CREATE TABLE sec.retention_policy (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  record_type                  text NOT NULL UNIQUE,
  retention_period             interval NOT NULL,
  disposal_action              text NOT NULL CHECK (disposal_action IN ('DELETE','ARCHIVE','REVIEW')),
  legal_basis                  text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE sec.retention_policy IS 'Retention rule per record type.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON sec.retention_policy FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- sec.legal_hold
CREATE TABLE sec.legal_hold (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  resource_type                text NOT NULL,
  resource_id                  text NOT NULL,
  reason                       text NOT NULL,
  released_at                  timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE sec.legal_hold IS 'Suspends retention for a resource.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON sec.legal_hold FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- sec.feature_flag
CREATE TABLE sec.feature_flag (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  enabled                      boolean NOT NULL DEFAULT false,
  approvals                    jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE sec.feature_flag IS 'Feature switch; protected flags need two approvals.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON sec.feature_flag FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- wf.workflow_definition
CREATE TABLE wf.workflow_definition (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL,
  def_version                  integer NOT NULL,
  module                       char(3) NOT NULL,
  name                         text NOT NULL,
  definition                   jsonb NOT NULL,
  active                       boolean NOT NULL DEFAULT true,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (code, def_version)
);
COMMENT ON TABLE wf.workflow_definition IS 'Versioned approval workflow definition.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON wf.workflow_definition FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- wf.workflow_instance
CREATE TABLE wf.workflow_instance (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  definition_id                bigint NOT NULL REFERENCES wf.workflow_definition(id),
  module                       char(3) NOT NULL,
  resource_type                text NOT NULL,
  resource_public_id           uuid NOT NULL,
  status                       text NOT NULL CHECK (status IN ('RUNNING','APPROVED','REJECTED','RETURNED','CANCELLED')),
  started_by_staff_id          bigint NOT NULL REFERENCES dir.staff_member(id),
  completed_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE wf.workflow_instance IS 'Running or completed workflow for one business record.';
CREATE INDEX ix_workflow_instance_definition_id ON wf.workflow_instance (definition_id);
CREATE INDEX ix_workflow_instance_started_by_staff_id ON wf.workflow_instance (started_by_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON wf.workflow_instance FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- wf.approval_step
CREATE TABLE wf.approval_step (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  instance_id                  bigint NOT NULL REFERENCES wf.workflow_instance(id),
  step_no                      smallint NOT NULL,
  approver_staff_id            bigint NOT NULL REFERENCES dir.staff_member(id),
  acting_for_staff_id          bigint REFERENCES dir.staff_member(id),
  delegated_from_staff_id      bigint REFERENCES dir.staff_member(id),
  decision                     text NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','APPROVED','REJECTED','RETURNED','DELEGATED','SKIPPED')),
  comment                      text,
  due_at                       timestamptz,
  decided_at                   timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (instance_id, step_no),
  CHECK (decision <> 'REJECTED' OR comment IS NOT NULL)
);
COMMENT ON TABLE wf.approval_step IS 'One approver decision within a workflow instance.';
CREATE INDEX ix_approval_step_approver_staff_id ON wf.approval_step (approver_staff_id);
CREATE INDEX ix_approval_step_acting_for_staff_id ON wf.approval_step (acting_for_staff_id);
CREATE INDEX ix_approval_step_delegated_from_staff_id ON wf.approval_step (delegated_from_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON wf.approval_step FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- wf.delegation
CREATE TABLE wf.delegation (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  delegator_staff_id           bigint NOT NULL REFERENCES dir.staff_member(id),
  delegate_staff_id            bigint NOT NULL REFERENCES dir.staff_member(id),
  starts_at                    timestamptz NOT NULL,
  ends_at                      timestamptz NOT NULL,
  scope_module                 char(3),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (delegator_staff_id <> delegate_staff_id),
  CHECK (ends_at > starts_at)
);
COMMENT ON TABLE wf.delegation IS 'Out-of-office delegation of approvals.';
CREATE INDEX ix_delegation_delegator_staff_id ON wf.delegation (delegator_staff_id);
CREATE INDEX ix_delegation_delegate_staff_id ON wf.delegation (delegate_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON wf.delegation FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- wf.holiday
CREATE TABLE wf.holiday (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  holiday_date                 date NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE wf.holiday IS 'Public holidays for the working-day calendar.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON wf.holiday FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- integ.inbound_message
CREATE TABLE integ.inbound_message (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_system                text NOT NULL CHECK (source_system IN ('HR','FINANCE','LIMS','CIAP','TELTP','RESEARCH','MMS','SMS_GATEWAY')),
  message_key                  text NOT NULL,
  received_at                  timestamptz NOT NULL DEFAULT now(),
  outcome                      text NOT NULL CHECK (outcome IN ('ACCEPTED','DUPLICATE','REJECTED')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  UNIQUE (source_system, message_key)
);
COMMENT ON TABLE integ.inbound_message IS 'Idempotency register for inbound files, API calls and events.';
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON integ.inbound_message FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- integ.outbox_event
CREATE TABLE integ.outbox_event (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  aggregate_type               text NOT NULL,
  aggregate_id                 uuid NOT NULL,
  event_type                   text NOT NULL,
  payload                      jsonb NOT NULL,
  published_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE integ.outbox_event IS 'Transactional outbox for domain events.';
CREATE INDEX ix_outbox_pending ON integ.outbox_event (id) WHERE published_at IS NULL;
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON integ.outbox_event FOR EACH ROW EXECUTE FUNCTION core.append_only('published_at');

-- integ.integration_endpoint
CREATE TABLE integ.integration_endpoint (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  system_name                  text NOT NULL,
  direction                    text NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND','BIDIRECTIONAL')),
  mechanism                    text NOT NULL,
  active                       boolean NOT NULL DEFAULT true,
  last_success_at              timestamptz,
  last_error                   text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE integ.integration_endpoint IS 'Registered integration and its health.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON integ.integration_endpoint FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- ntf.template
CREATE TABLE ntf.template (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_type                   text NOT NULL,
  channel                      text NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH')),
  language                     char(2) NOT NULL CHECK (language IN ('en','sw')),
  tpl_version                  integer NOT NULL,
  subject                      text,
  body                         text NOT NULL,
  status                       text NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (event_type, channel, language, tpl_version),
  CHECK (channel IN ('IN_APP') OR body !~* '\{\{\s*(amount|balance|approved_amount)')
);
COMMENT ON TABLE ntf.template IS 'Versioned notification template per event, channel and language.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON ntf.template FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- ntf.notification
CREATE TABLE ntf.notification (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  recipient_staff_id           bigint NOT NULL REFERENCES dir.staff_member(id),
  event_type                   text NOT NULL,
  category                     text NOT NULL,
  mandatory                    boolean NOT NULL DEFAULT false,
  title                        text NOT NULL,
  deep_link                    text NOT NULL,
  source_module                char(3) NOT NULL,
  source_public_id             uuid,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  read_at                      timestamptz
);
COMMENT ON TABLE ntf.notification IS 'In-app notification for one recipient.';
CREATE INDEX ix_notification_recipient_staff_id ON ntf.notification (recipient_staff_id);
CREATE INDEX ix_ntf_unread ON ntf.notification (recipient_staff_id, created_at DESC) WHERE read_at IS NULL;

-- ntf.delivery_attempt
CREATE TABLE ntf.delivery_attempt (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  notification_id              bigint NOT NULL REFERENCES ntf.notification(id),
  channel                      text NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH')),
  attempt_no                   smallint NOT NULL CHECK (attempt_no BETWEEN 1 AND 5),
  status                       text NOT NULL CHECK (status IN ('QUEUED','SENT','DELIVERED','FAILED','DEAD_LETTER')),
  provider_ref                 text,
  error                        text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE ntf.delivery_attempt IS 'Each delivery attempt per channel.';
CREATE INDEX ix_delivery_attempt_notification_id ON ntf.delivery_attempt (notification_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON ntf.delivery_attempt FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- ntf.user_preference
CREATE TABLE ntf.user_preference (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  category                     text NOT NULL,
  channel                      text NOT NULL CHECK (channel IN ('EMAIL','SMS','PUSH')),
  enabled                      boolean NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (staff_id, category, channel)
);
COMMENT ON TABLE ntf.user_preference IS 'Channel preference per category.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON ntf.user_preference FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- ntf.preference_setting
CREATE TABLE ntf.preference_setting (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id                     bigint NOT NULL UNIQUE REFERENCES dir.staff_member(id),
  quiet_start                  time NOT NULL DEFAULT '20:00',
  quiet_end                    time NOT NULL DEFAULT '07:00',
  email_digest                 boolean NOT NULL DEFAULT false,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE ntf.preference_setting IS 'Quiet hours and digest settings.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON ntf.preference_setting FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- ntf.push_subscription
CREATE TABLE ntf.push_subscription (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  endpoint                     text NOT NULL UNIQUE,
  p256dh                       text NOT NULL,
  auth_secret                  text NOT NULL,
  revoked_at                   timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE ntf.push_subscription IS 'PWA Web Push subscription.';
CREATE INDEX ix_push_subscription_staff_id ON ntf.push_subscription (staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON ntf.push_subscription FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- com.announcement
CREATE TABLE com.announcement (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  category                     text NOT NULL CHECK (category IN ('NOTICE','CIRCULAR','DIRECTIVE_NOTICE','EMERGENCY_ALERT','EVENT')),
  classification               text NOT NULL DEFAULT 'INTERNAL' CHECK (classification IN ('PUBLIC','INTERNAL','RESTRICTED','CONFIDENTIAL')),
  status                       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_REVIEW','RETURNED','REJECTED','APPROVED','SCHEDULED','PUBLISHED','WITHDRAWN','ARCHIVED')),
  author_staff_id              bigint NOT NULL REFERENCES dir.staff_member(id),
  approver_staff_id            bigint REFERENCES dir.staff_member(id),
  approved_at                  timestamptz,
  requires_ack                 boolean NOT NULL DEFAULT false,
  priority                     smallint NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
  effective_at                 timestamptz NOT NULL,
  expires_at                   timestamptz,
  published_at                 timestamptz,
  withdrawn_reason             text,
  current_version              integer NOT NULL DEFAULT 1,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (status NOT IN ('PUBLISHED','WITHDRAWN','ARCHIVED') OR published_at IS NOT NULL),
  CHECK (approver_staff_id IS NULL OR approver_staff_id <> author_staff_id),
  CHECK (status NOT IN ('APPROVED','SCHEDULED','PUBLISHED') OR category IN ('NOTICE','EVENT') OR approver_staff_id IS NOT NULL OR category = 'EMERGENCY_ALERT'),
  CHECK (status <> 'WITHDRAWN' OR withdrawn_reason IS NOT NULL),
  CHECK (expires_at IS NULL OR expires_at > effective_at)
);
COMMENT ON TABLE com.announcement IS 'Official notice, circular, directive notice, emergency alert or event.';
CREATE INDEX ix_announcement_author_staff_id ON com.announcement (author_staff_id);
CREATE INDEX ix_announcement_approver_staff_id ON com.announcement (approver_staff_id);
CREATE INDEX ix_ann_active ON com.announcement (status, effective_at DESC) WHERE status = 'PUBLISHED';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON com.announcement FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- com.announcement_version
CREATE TABLE com.announcement_version (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  announcement_id              bigint NOT NULL REFERENCES com.announcement(id),
  version_no                   integer NOT NULL CHECK (version_no >= 1),
  title_en                     text,
  body_en                      text,
  title_sw                     text,
  body_sw                      text,
  change_note                  text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  UNIQUE (announcement_id, version_no),
  CHECK (title_en IS NOT NULL OR title_sw IS NOT NULL)
);
COMMENT ON TABLE com.announcement_version IS 'Content version of an announcement (English and/or Kiswahili).';
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON com.announcement_version FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- com.announcement_audience
CREATE TABLE com.announcement_audience (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  announcement_id              bigint NOT NULL REFERENCES com.announcement(id),
  rule_type                    text NOT NULL CHECK (rule_type IN ('ALL','ORG_UNIT','ORG_UNIT_TREE','ROLE','STAFF_CATEGORY','DUTY_STATION','STAFF')),
  rule_value                   text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (rule_type = 'ALL' OR rule_value IS NOT NULL)
);
COMMENT ON TABLE com.announcement_audience IS 'Targeting rule; rules are combined with OR.';
CREATE INDEX ix_announcement_audience_announcement_id ON com.announcement_audience (announcement_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON com.announcement_audience FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- com.announcement_recipient
CREATE TABLE com.announcement_recipient (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  announcement_id              bigint NOT NULL REFERENCES com.announcement(id),
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  delivered_at                 timestamptz,
  first_read_at                timestamptz,
  acknowledged_at              timestamptz,
  ack_channel                  text CHECK (ack_channel IN ('WEB','MOBILE_WEB')),
  UNIQUE (announcement_id, staff_id),
  CHECK (acknowledged_at IS NULL OR first_read_at IS NOT NULL)
);
COMMENT ON TABLE com.announcement_recipient IS 'Resolved recipient with read and acknowledgement state.';
CREATE INDEX ix_announcement_recipient_staff_id ON com.announcement_recipient (staff_id);

-- com.announcement_attachment
CREATE TABLE com.announcement_attachment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  announcement_id              bigint NOT NULL REFERENCES com.announcement(id),
  object_key                   text NOT NULL,
  file_name                    text NOT NULL,
  content_type                 text NOT NULL,
  size_bytes                   bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
  sha256                       char(64) NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE com.announcement_attachment IS 'File attached to an announcement.';
CREATE INDEX ix_announcement_attachment_announcement_id ON com.announcement_attachment (announcement_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON com.announcement_attachment FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- fin.import_batch
CREATE TABLE fin.import_batch (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  feed_type                    text NOT NULL CHECK (feed_type IN ('CLAIMS','PAYMENTS','MIXED')),
  file_name                    text,
  checksum                     char(64) NOT NULL UNIQUE,
  source_record_count          integer NOT NULL CHECK (source_record_count >= 0),
  source_amount_total          numeric(18,2) NOT NULL,
  loaded_count                 integer NOT NULL DEFAULT 0,
  quarantined_count            integer NOT NULL DEFAULT 0,
  status                       text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','VALIDATED','RECONCILED','REJECTED','PUBLISHED')),
  imported_by                  text NOT NULL,
  reviewed_by                  text,
  reviewed_at                  timestamptz,
  published_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (reviewed_by IS NULL OR reviewed_by <> imported_by),
  CHECK (status <> 'PUBLISHED' OR (reviewed_by IS NOT NULL AND published_at IS NOT NULL)),
  CHECK (status NOT IN ('RECONCILED','PUBLISHED') OR loaded_count + quarantined_count = source_record_count)
);
COMMENT ON TABLE fin.import_batch IS 'One Finance import (file or API batch) with control totals and maker-checker publication.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.import_batch FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.claim_type
CREATE TABLE fin.claim_type (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE fin.claim_type IS 'Claim type reference list (e.g., travel allowance, consultancy allowance).';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.claim_type FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.claim
CREATE TABLE fin.claim (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  batch_id                     bigint NOT NULL REFERENCES fin.import_batch(id),
  source_ref                   text NOT NULL UNIQUE,
  claim_type_id                bigint NOT NULL REFERENCES fin.claim_type(id),
  description                  text NOT NULL,
  approved_amount              numeric(18,2) NOT NULL CHECK (approved_amount > 0),
  currency                     char(3) NOT NULL DEFAULT 'TZS' CHECK (currency ~ '^[A-Z]{3}$'),
  approval_date                date NOT NULL,
  voucher_ref                  text,
  status                       text NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('APPROVED','CANCELLED')),
  published_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE fin.claim IS 'Finance-approved claim owed by TIRDO to a staff member (read-only mirror).';
CREATE INDEX ix_claim_staff_id ON fin.claim (staff_id);
CREATE INDEX ix_claim_batch_id ON fin.claim (batch_id);
CREATE INDEX ix_claim_claim_type_id ON fin.claim (claim_type_id);
CREATE INDEX ix_claim_staff ON fin.claim (staff_id, approval_date DESC);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.claim FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.payment
CREATE TABLE fin.payment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  batch_id                     bigint NOT NULL REFERENCES fin.import_batch(id),
  source_ref                   text NOT NULL UNIQUE,
  amount                       numeric(18,2) NOT NULL CHECK (amount > 0),
  currency                     char(3) NOT NULL DEFAULT 'TZS' CHECK (currency ~ '^[A-Z]{3}$'),
  paid_on                      date NOT NULL,
  payment_ref                  text,
  status                       text NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED','CANCELLED')),
  published_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE fin.payment IS 'Confirmed payment from Finance to a staff member.';
CREATE INDEX ix_payment_staff_id ON fin.payment (staff_id);
CREATE INDEX ix_payment_batch_id ON fin.payment (batch_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.payment FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.payment_allocation
CREATE TABLE fin.payment_allocation (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  payment_id                   bigint NOT NULL REFERENCES fin.payment(id),
  claim_id                     bigint NOT NULL REFERENCES fin.claim(id),
  amount                       numeric(18,2) NOT NULL CHECK (amount <> 0),
  reverses_allocation_id       bigint REFERENCES fin.payment_allocation(id),
  source_ref                   text UNIQUE,
  allocated_by                 text NOT NULL,
  approved_by                  text,
  published_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK ((amount < 0) = (reverses_allocation_id IS NOT NULL)),
  CHECK (approved_by IS NULL OR approved_by <> allocated_by),
  CHECK (source_ref IS NOT NULL OR approved_by IS NOT NULL OR published_at IS NULL)
);
COMMENT ON TABLE fin.payment_allocation IS 'Allocation of a payment to a claim; reversals are negative and reference the original.';
CREATE INDEX ix_payment_allocation_payment_id ON fin.payment_allocation (payment_id);
CREATE INDEX ix_payment_allocation_claim_id ON fin.payment_allocation (claim_id);
CREATE INDEX ix_payment_allocation_reverses_allocation_id ON fin.payment_allocation (reverses_allocation_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.payment_allocation FOR EACH ROW EXECUTE FUNCTION core.touch_row();
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON fin.payment_allocation FOR EACH ROW EXECUTE FUNCTION core.append_only('approved_by', 'published_at');

-- fin.adjustment
CREATE TABLE fin.adjustment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  claim_id                     bigint NOT NULL REFERENCES fin.claim(id),
  batch_id                     bigint NOT NULL REFERENCES fin.import_batch(id),
  source_ref                   text NOT NULL UNIQUE,
  amount                       numeric(18,2) NOT NULL CHECK (amount <> 0),
  reason                       text NOT NULL,
  published_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE fin.adjustment IS 'Finance adjustment to a claim (positive or negative) with source reference.';
CREATE INDEX ix_adjustment_claim_id ON fin.adjustment (claim_id);
CREATE INDEX ix_adjustment_batch_id ON fin.adjustment (batch_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.adjustment FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.quarantine_item
CREATE TABLE fin.quarantine_item (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  batch_id                     bigint NOT NULL REFERENCES fin.import_batch(id),
  row_no                       integer NOT NULL,
  reason_code                  text NOT NULL CHECK (reason_code IN ('UNKNOWN_EMPLOYEE','UNKNOWN_CLAIM','CURRENCY_MISMATCH','OVER_ALLOCATION','INVALID_AMOUNT','SCHEMA_ERROR','DUPLICATE_REF')),
  payload                      jsonb NOT NULL,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','REJECTED')),
  resolution_note              text,
  resolved_by                  text,
  resolved_at                  timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (batch_id, row_no),
  CHECK (status = 'OPEN' OR (resolution_note IS NOT NULL AND resolved_by IS NOT NULL))
);
COMMENT ON TABLE fin.quarantine_item IS 'Import row held for Finance resolution.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.quarantine_item FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.claim_query
CREATE TABLE fin.claim_query (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  claim_id                     bigint REFERENCES fin.claim(id),
  payment_id                   bigint REFERENCES fin.payment(id),
  category                     text NOT NULL CHECK (category IN ('AMOUNT','MISSING_PAYMENT','WRONG_CLAIM','OTHER')),
  description                  text NOT NULL,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESPONDED','RESOLVED','REOPENED','ESCALATED','CLOSED')),
  reopen_count                 smallint NOT NULL DEFAULT 0 CHECK (reopen_count BETWEEN 0 AND 2),
  assigned_to_staff_id         bigint REFERENCES dir.staff_member(id),
  due_at                       timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (claim_id IS NOT NULL OR payment_id IS NOT NULL)
);
COMMENT ON TABLE fin.claim_query IS 'Staff query on a claim or payment.';
CREATE INDEX ix_claim_query_staff_id ON fin.claim_query (staff_id);
CREATE INDEX ix_claim_query_claim_id ON fin.claim_query (claim_id);
CREATE INDEX ix_claim_query_payment_id ON fin.claim_query (payment_id);
CREATE INDEX ix_claim_query_assigned_to_staff_id ON fin.claim_query (assigned_to_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fin.claim_query FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fin.query_message
CREATE TABLE fin.query_message (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  query_id                     bigint NOT NULL REFERENCES fin.claim_query(id),
  author_staff_id              bigint NOT NULL REFERENCES dir.staff_member(id),
  body                         text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE fin.query_message IS 'Message in a query thread.';
CREATE INDEX ix_query_message_query_id ON fin.query_message (query_id);
CREATE INDEX ix_query_message_author_staff_id ON fin.query_message (author_staff_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON fin.query_message FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- mtg.meeting_type
CREATE TABLE mtg.meeting_type (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text NOT NULL,
  confidentiality              text NOT NULL CHECK (confidentiality IN ('INTERNAL','RESTRICTED','CONFIDENTIAL')),
  quorum_percent               smallint NOT NULL DEFAULT 50 CHECK (quorum_percent BETWEEN 1 AND 100),
  chair_position_id            bigint REFERENCES dir.position(id),
  secretary_position_id        bigint REFERENCES dir.position(id),
  minutes_template             jsonb NOT NULL,
  recording_retention          interval NOT NULL DEFAULT '12 months',
  resolution_prefix            text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE mtg.meeting_type IS 'Configured meeting type with defaults.';
CREATE INDEX ix_meeting_type_chair_position_id ON mtg.meeting_type (chair_position_id);
CREATE INDEX ix_meeting_type_secretary_position_id ON mtg.meeting_type (secretary_position_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON mtg.meeting_type FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- mtg.meeting
CREATE TABLE mtg.meeting (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  meeting_type_id              bigint NOT NULL REFERENCES mtg.meeting_type(id),
  title                        text NOT NULL,
  starts_at                    timestamptz NOT NULL,
  ends_at                      timestamptz NOT NULL,
  mode                         text NOT NULL CHECK (mode IN ('PHYSICAL','ONLINE','HYBRID')),
  venue                        text,
  online_link                  text,
  chair_staff_id               bigint NOT NULL REFERENCES dir.staff_member(id),
  secretary_staff_id           bigint NOT NULL REFERENCES dir.staff_member(id),
  confidentiality              text NOT NULL CHECK (confidentiality IN ('INTERNAL','RESTRICTED','CONFIDENTIAL')),
  status                       text NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','INVITED','HELD','MINUTES_DRAFT','MINUTES_APPROVED','CANCELLED')),
  recording_notice_at          timestamptz,
  recording_notice_by          bigint REFERENCES dir.staff_member(id),
  ics_uid                      uuid NOT NULL DEFAULT gen_random_uuid(),
  legacy_mms_ref               text UNIQUE,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at),
  CHECK (mode = 'PHYSICAL' OR online_link IS NOT NULL),
  CHECK ((recording_notice_at IS NULL) = (recording_notice_by IS NULL))
);
COMMENT ON TABLE mtg.meeting IS 'A scheduled meeting.';
CREATE INDEX ix_meeting_meeting_type_id ON mtg.meeting (meeting_type_id);
CREATE INDEX ix_meeting_chair_staff_id ON mtg.meeting (chair_staff_id);
CREATE INDEX ix_meeting_secretary_staff_id ON mtg.meeting (secretary_staff_id);
CREATE INDEX ix_meeting_recording_notice_by ON mtg.meeting (recording_notice_by);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON mtg.meeting FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- mtg.meeting_participant
CREATE TABLE mtg.meeting_participant (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  meeting_id                   bigint NOT NULL REFERENCES mtg.meeting(id),
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  participant_role             text NOT NULL CHECK (participant_role IN ('CHAIR','SECRETARY','MEMBER','GUEST','IN_ATTENDANCE')),
  rsvp                         text CHECK (rsvp IN ('ACCEPTED','DECLINED','DELEGATED')),
  attendance                   text CHECK (attendance IN ('PRESENT','APOLOGY','ABSENT','IN_ATTENDANCE')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (meeting_id, staff_id)
);
COMMENT ON TABLE mtg.meeting_participant IS 'Invitee with RSVP and attendance.';
CREATE INDEX ix_meeting_participant_staff_id ON mtg.meeting_participant (staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON mtg.meeting_participant FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- mtg.recording
CREATE TABLE mtg.recording (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  meeting_id                   bigint NOT NULL REFERENCES mtg.meeting(id),
  object_key                   text NOT NULL,
  file_name                    text NOT NULL,
  content_type                 text NOT NULL,
  size_bytes                   bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 2147483648),
  sha256                       char(64) NOT NULL,
  source                       text NOT NULL CHECK (source IN ('UPLOAD','ONLINE_IMPORT','MMS_MIGRATION')),
  retention_until              date,
  deleted_at                   timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE mtg.recording IS 'Meeting recording in object storage.';
CREATE INDEX ix_recording_meeting_id ON mtg.recording (meeting_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON mtg.recording FOR EACH ROW EXECUTE FUNCTION core.append_only('retention_until', 'deleted_at');

-- mtg.transcript
CREATE TABLE mtg.transcript (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  recording_id                 bigint NOT NULL REFERENCES mtg.recording(id),
  language                     char(2) NOT NULL CHECK (language IN ('en','sw')),
  engine                       text NOT NULL,
  segments                     jsonb NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE mtg.transcript IS 'On-premises AI transcript of a recording (R4).';
CREATE INDEX ix_transcript_recording_id ON mtg.transcript (recording_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON mtg.transcript FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- mtg.minutes_version
CREATE TABLE mtg.minutes_version (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  meeting_id                   bigint NOT NULL REFERENCES mtg.meeting(id),
  version_no                   integer NOT NULL,
  content                      jsonb NOT NULL,
  status                       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SECRETARY_REVIEW','CIRCULATED','CHAIR_APPROVAL','APPROVED','SUPERSEDED')),
  ai_sections_unreviewed       integer NOT NULL DEFAULT 0 CHECK (ai_sections_unreviewed >= 0),
  approved_by_staff_id         bigint REFERENCES dir.staff_member(id),
  approved_at                  timestamptz,
  pdf_object_key               text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (meeting_id, version_no),
  CHECK (status <> 'APPROVED' OR (approved_by_staff_id IS NOT NULL AND approved_at IS NOT NULL AND ai_sections_unreviewed = 0)),
  CHECK (status NOT IN ('CHAIR_APPROVAL','APPROVED') OR ai_sections_unreviewed = 0)
);
COMMENT ON TABLE mtg.minutes_version IS 'Version of a meeting''s minutes; locked when approved.';
CREATE INDEX ix_minutes_version_approved_by_staff_id ON mtg.minutes_version (approved_by_staff_id);
CREATE UNIQUE INDEX ux_minutes_one_approved ON mtg.minutes_version (meeting_id) WHERE status = 'APPROVED';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON mtg.minutes_version FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- mtg.resolution
CREATE TABLE mtg.resolution (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  meeting_id                   bigint NOT NULL REFERENCES mtg.meeting(id),
  minutes_version_id           bigint NOT NULL REFERENCES mtg.minutes_version(id),
  resolution_no                text NOT NULL UNIQUE,
  text                         text NOT NULL,
  owner_staff_id               bigint REFERENCES dir.staff_member(id),
  due_on                       date,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','DONE','DROPPED')),
  task_public_id               uuid,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE mtg.resolution IS 'Numbered resolution from approved minutes.';
CREATE INDEX ix_resolution_meeting_id ON mtg.resolution (meeting_id);
CREATE INDEX ix_resolution_minutes_version_id ON mtg.resolution (minutes_version_id);
CREATE INDEX ix_resolution_owner_staff_id ON mtg.resolution (owner_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON mtg.resolution FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- mtg.agenda_item
CREATE TABLE mtg.agenda_item (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  meeting_id                   bigint NOT NULL REFERENCES mtg.meeting(id),
  item_no                      smallint NOT NULL,
  title                        text NOT NULL,
  presenter_staff_id           bigint REFERENCES dir.staff_member(id),
  minutes_allocated            smallint CHECK (minutes_allocated > 0),
  confidential                 boolean NOT NULL DEFAULT false,
  source_resolution_id         bigint REFERENCES mtg.resolution(id),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (meeting_id, item_no)
);
COMMENT ON TABLE mtg.agenda_item IS 'Agenda item, optionally carrying a matter arising.';
CREATE INDEX ix_agenda_item_presenter_staff_id ON mtg.agenda_item (presenter_staff_id);
CREATE INDEX ix_agenda_item_source_resolution_id ON mtg.agenda_item (source_resolution_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON mtg.agenda_item FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- tsk.task
CREATE TABLE tsk.task (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  reference_no                 text NOT NULL UNIQUE,
  title                        text NOT NULL,
  description                  text,
  source                       text NOT NULL CHECK (source IN ('DIRECTIVE','MEETING_RESOLUTION','AD_HOC','PROJECT','EXTERNAL_SYSTEM','REQUEST')),
  source_ref                   text,
  priority                     text NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','CRITICAL')),
  issuer_staff_id              bigint NOT NULL REFERENCES dir.staff_member(id),
  assignee_staff_id            bigint REFERENCES dir.staff_member(id),
  assignee_unit_id             bigint REFERENCES dir.org_unit(id),
  verifier_staff_id            bigint REFERENCES dir.staff_member(id),
  due_on                       date NOT NULL,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACCEPTED','IN_PROGRESS','SUBMITTED','RETURNED','CLOSED','CANCELLED')),
  evidence_required            boolean NOT NULL DEFAULT false,
  progress_pct                 smallint NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
  acknowledged_at              timestamptz,
  closed_at                    timestamptz,
  workspace_public_id          uuid,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (assignee_staff_id IS NOT NULL OR assignee_unit_id IS NOT NULL),
  CHECK (verifier_staff_id IS NULL OR assignee_staff_id IS NULL OR verifier_staff_id <> assignee_staff_id),
  CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
);
COMMENT ON TABLE tsk.task IS 'Directive or task.';
CREATE INDEX ix_task_issuer_staff_id ON tsk.task (issuer_staff_id);
CREATE INDEX ix_task_assignee_staff_id ON tsk.task (assignee_staff_id);
CREATE INDEX ix_task_assignee_unit_id ON tsk.task (assignee_unit_id);
CREATE INDEX ix_task_verifier_staff_id ON tsk.task (verifier_staff_id);
CREATE INDEX ix_task_assignee_open ON tsk.task (assignee_staff_id, due_on) WHERE status NOT IN ('CLOSED','CANCELLED');
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON tsk.task FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- tsk.task_event
CREATE TABLE tsk.task_event (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id                      bigint NOT NULL REFERENCES tsk.task(id),
  event_type                   text NOT NULL CHECK (event_type IN ('CREATED','ACKNOWLEDGED','STATUS_CHANGED','PROGRESS','REASSIGNED','ESCALATED','COMMENT')),
  from_status                  text,
  to_status                    text,
  comment                      text,
  actor_staff_id               bigint REFERENCES dir.staff_member(id),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE tsk.task_event IS 'Status change or update on a task.';
CREATE INDEX ix_task_event_task_id ON tsk.task_event (task_id);
CREATE INDEX ix_task_event_actor_staff_id ON tsk.task_event (actor_staff_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON tsk.task_event FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- tsk.extension_request
CREATE TABLE tsk.extension_request (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id                      bigint NOT NULL REFERENCES tsk.task(id),
  requested_due_on             date NOT NULL,
  justification                text NOT NULL,
  decision                     text NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','APPROVED','REJECTED')),
  decided_by_staff_id          bigint REFERENCES dir.staff_member(id),
  decided_at                   timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE tsk.extension_request IS 'Deadline extension request.';
CREATE INDEX ix_extension_request_task_id ON tsk.extension_request (task_id);
CREATE INDEX ix_extension_request_decided_by_staff_id ON tsk.extension_request (decided_by_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON tsk.extension_request FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- tsk.task_attachment
CREATE TABLE tsk.task_attachment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id                      bigint NOT NULL REFERENCES tsk.task(id),
  object_key                   text NOT NULL,
  file_name                    text NOT NULL,
  content_type                 text NOT NULL,
  size_bytes                   bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 52428800),
  sha256                       char(64) NOT NULL,
  is_evidence                  boolean NOT NULL DEFAULT false,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE tsk.task_attachment IS 'File or evidence attached to a task.';
CREATE INDEX ix_task_attachment_task_id ON tsk.task_attachment (task_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON tsk.task_attachment FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- msg.conversation
CREATE TABLE msg.conversation (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  conv_type                    text NOT NULL CHECK (conv_type IN ('DIRECT','GROUP','UNIT','PROJECT','ANNOUNCEMENT')),
  title                        text,
  org_unit_id                  bigint REFERENCES dir.org_unit(id),
  workspace_public_id          uuid,
  owner_staff_id               bigint REFERENCES dir.staff_member(id),
  retention_class              text NOT NULL DEFAULT 'MSG_STANDARD',
  legal_hold                   boolean NOT NULL DEFAULT false,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (conv_type <> 'UNIT' OR org_unit_id IS NOT NULL),
  CHECK (conv_type <> 'PROJECT' OR workspace_public_id IS NOT NULL)
);
COMMENT ON TABLE msg.conversation IS 'Direct, group, unit, project or announcement-only conversation.';
CREATE INDEX ix_conversation_org_unit_id ON msg.conversation (org_unit_id);
CREATE INDEX ix_conversation_owner_staff_id ON msg.conversation (owner_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON msg.conversation FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- msg.conversation_member
CREATE TABLE msg.conversation_member (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id              bigint NOT NULL REFERENCES msg.conversation(id),
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  member_role                  text NOT NULL DEFAULT 'MEMBER' CHECK (member_role IN ('OWNER','ADMIN','MEMBER')),
  muted                        boolean NOT NULL DEFAULT false,
  left_at                      timestamptz,
  last_read_message_id         bigint,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (conversation_id, staff_id)
);
COMMENT ON TABLE msg.conversation_member IS 'Membership and read position.';
CREATE INDEX ix_conversation_member_staff_id ON msg.conversation_member (staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON msg.conversation_member FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- msg.message
CREATE TABLE msg.message (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id              bigint NOT NULL REFERENCES msg.conversation(id),
  sender_staff_id              bigint NOT NULL REFERENCES dir.staff_member(id),
  parent_message_id            bigint REFERENCES msg.message(id),
  body                         text NOT NULL CHECK (length(body) <= 10000),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  edited_at                    timestamptz,
  deleted_at                   timestamptz,
  hidden_at                    timestamptz,
  moderation_reason            text
);
COMMENT ON TABLE msg.message IS 'Chat message (partition by month when volume requires).';
CREATE INDEX ix_message_conversation_id ON msg.message (conversation_id);
CREATE INDEX ix_message_sender_staff_id ON msg.message (sender_staff_id);
CREATE INDEX ix_message_parent_message_id ON msg.message (parent_message_id);
CREATE INDEX ix_message_conv_time ON msg.message (conversation_id, created_at DESC);

-- msg.message_revision
CREATE TABLE msg.message_revision (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id                   bigint NOT NULL REFERENCES msg.message(id),
  body                         text NOT NULL,
  revision_type                text NOT NULL CHECK (revision_type IN ('EDIT','DELETE','HIDE')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE msg.message_revision IS 'Original text retained on edit or delete (records retention).';
CREATE INDEX ix_message_revision_message_id ON msg.message_revision (message_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON msg.message_revision FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- msg.message_attachment
CREATE TABLE msg.message_attachment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id                   bigint NOT NULL REFERENCES msg.message(id),
  object_key                   text NOT NULL,
  file_name                    text NOT NULL,
  content_type                 text NOT NULL,
  size_bytes                   bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 52428800),
  sha256                       char(64) NOT NULL,
  scan_status                  text NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING','CLEAN','INFECTED')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE msg.message_attachment IS 'File in a message.';
CREATE INDEX ix_message_attachment_message_id ON msg.message_attachment (message_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON msg.message_attachment FOR EACH ROW EXECUTE FUNCTION core.append_only('scan_status');

-- msg.message_report
CREATE TABLE msg.message_report (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id                   bigint NOT NULL REFERENCES msg.message(id),
  reported_by_staff_id         bigint NOT NULL REFERENCES dir.staff_member(id),
  reason                       text NOT NULL,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','HIDDEN','DISMISSED')),
  handled_by_staff_id          bigint REFERENCES dir.staff_member(id),
  handled_at                   timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE msg.message_report IS 'Report of inappropriate content.';
CREATE INDEX ix_message_report_message_id ON msg.message_report (message_id);
CREATE INDEX ix_message_report_reported_by_staff_id ON msg.message_report (reported_by_staff_id);
CREATE INDEX ix_message_report_handled_by_staff_id ON msg.message_report (handled_by_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON msg.message_report FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- doc.document_type
CREATE TABLE doc.document_type (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text NOT NULL,
  workflow_code                text NOT NULL,
  review_cycle                 interval,
  retention_period             interval NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE doc.document_type IS 'Document type with workflow, review cycle and retention.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON doc.document_type FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- doc.document
CREATE TABLE doc.document (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  document_type_id             bigint NOT NULL REFERENCES doc.document_type(id),
  title                        text NOT NULL,
  owner_unit_id                bigint NOT NULL REFERENCES dir.org_unit(id),
  classification               text NOT NULL CHECK (classification IN ('PUBLIC','INTERNAL','RESTRICTED','CONFIDENTIAL')),
  language                     char(2) NOT NULL CHECK (language IN ('en','sw')),
  keywords                     text[] NOT NULL DEFAULT '{}',
  status                       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_REVIEW','PUBLISHED','SUPERSEDED','DISPOSED')),
  review_due_on                date,
  checked_out_by_staff_id      bigint REFERENCES dir.staff_member(id),
  eoffice_ref                  text,
  disposed_at                  timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE doc.document IS 'Controlled document.';
CREATE INDEX ix_document_document_type_id ON doc.document (document_type_id);
CREATE INDEX ix_document_owner_unit_id ON doc.document (owner_unit_id);
CREATE INDEX ix_document_checked_out_by_staff_id ON doc.document (checked_out_by_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON doc.document FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- doc.document_version
CREATE TABLE doc.document_version (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id                  bigint NOT NULL REFERENCES doc.document(id),
  major                        integer NOT NULL CHECK (major >= 0),
  minor                        integer NOT NULL CHECK (minor >= 0),
  object_key                   text NOT NULL,
  file_name                    text NOT NULL,
  content_type                 text NOT NULL,
  size_bytes                   bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 104857600),
  sha256                       char(64) NOT NULL,
  status                       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','PUBLISHED','SUPERSEDED')),
  approved_by_staff_id         bigint REFERENCES dir.staff_member(id),
  approved_at                  timestamptz,
  published_at                 timestamptz,
  search_vector                tsvector,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (document_id, major, minor),
  CHECK (status NOT IN ('APPROVED','PUBLISHED','SUPERSEDED') OR approved_by_staff_id IS NOT NULL)
);
COMMENT ON TABLE doc.document_version IS 'File version; immutable once published.';
CREATE INDEX ix_document_version_approved_by_staff_id ON doc.document_version (approved_by_staff_id);
CREATE INDEX ix_docver_search ON doc.document_version USING gin (search_vector);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON doc.document_version FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- doc.document_access
CREATE TABLE doc.document_access (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id                  bigint NOT NULL REFERENCES doc.document(id),
  principal_type               text NOT NULL CHECK (principal_type IN ('ORG_UNIT','ROLE','WORKSPACE','STAFF')),
  principal_value              text NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (document_id, principal_type, principal_value)
);
COMMENT ON TABLE doc.document_access IS 'Explicit audience on top of classification.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON doc.document_access FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- doc.document_link
CREATE TABLE doc.document_link (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id                  bigint NOT NULL REFERENCES doc.document(id),
  resource_type                text NOT NULL CHECK (resource_type IN ('MEETING','AGENDA_ITEM','TASK','WORKSPACE','REQUEST','ANNOUNCEMENT')),
  resource_public_id           uuid NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (document_id, resource_type, resource_public_id)
);
COMMENT ON TABLE doc.document_link IS 'Link from a document to a record in another module.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON doc.document_link FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- doc.disposal_certificate
CREATE TABLE doc.disposal_certificate (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  certificate_no               text NOT NULL UNIQUE,
  document_id                  bigint NOT NULL REFERENCES doc.document(id),
  version_labels               text[] NOT NULL,
  approved_by_staff_id         bigint NOT NULL REFERENCES dir.staff_member(id),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE doc.disposal_certificate IS 'Evidence of authorised disposal.';
CREATE INDEX ix_disposal_certificate_document_id ON doc.disposal_certificate (document_id);
CREATE INDEX ix_disposal_certificate_approved_by_staff_id ON doc.disposal_certificate (approved_by_staff_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON doc.disposal_certificate FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- prj.workspace
CREATE TABLE prj.workspace (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  code                         text NOT NULL UNIQUE,
  title                        text NOT NULL,
  ws_type                      text NOT NULL CHECK (ws_type IN ('RESEARCH','CONSULTANCY','INTERNAL')),
  lead_staff_id                bigint NOT NULL REFERENCES dir.staff_member(id),
  org_unit_id                  bigint NOT NULL REFERENCES dir.org_unit(id),
  starts_on                    date NOT NULL,
  ends_on                      date,
  funding_source               text,
  ciap_ref                     text,
  research_ref                 text,
  confidential                 boolean NOT NULL DEFAULT false,
  status                       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ON_HOLD','CLOSED')),
  closed_at                    timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (ends_on IS NULL OR ends_on >= starts_on),
  CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
);
COMMENT ON TABLE prj.workspace IS 'Research, consultancy or internal project workspace.';
CREATE INDEX ix_workspace_lead_staff_id ON prj.workspace (lead_staff_id);
CREATE INDEX ix_workspace_org_unit_id ON prj.workspace (org_unit_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON prj.workspace FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- prj.workspace_member
CREATE TABLE prj.workspace_member (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id                 bigint NOT NULL REFERENCES prj.workspace(id),
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  member_role                  text NOT NULL CHECK (member_role IN ('LEAD','MEMBER','VIEWER')),
  removed_at                   timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE prj.workspace_member IS 'Workspace membership history.';
CREATE INDEX ix_workspace_member_workspace_id ON prj.workspace_member (workspace_id);
CREATE INDEX ix_workspace_member_staff_id ON prj.workspace_member (staff_id);
CREATE UNIQUE INDEX ux_ws_member_active ON prj.workspace_member (workspace_id, staff_id) WHERE removed_at IS NULL;
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON prj.workspace_member FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- prj.milestone
CREATE TABLE prj.milestone (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id                 bigint NOT NULL REFERENCES prj.workspace(id),
  title                        text NOT NULL,
  planned_on                   date NOT NULL,
  actual_on                    date,
  status                       text NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','IN_PROGRESS','DONE','DELAYED','CANCELLED')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE prj.milestone IS 'Project milestone.';
CREATE INDEX ix_milestone_workspace_id ON prj.milestone (workspace_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON prj.milestone FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- prj.deliverable
CREATE TABLE prj.deliverable (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id                 bigint NOT NULL REFERENCES prj.workspace(id),
  milestone_id                 bigint REFERENCES prj.milestone(id),
  title                        text NOT NULL,
  document_public_id           uuid,
  status                       text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SUBMITTED','ACCEPTED','REJECTED')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE prj.deliverable IS 'Deliverable linked to a controlled document.';
CREATE INDEX ix_deliverable_workspace_id ON prj.deliverable (workspace_id);
CREATE INDEX ix_deliverable_milestone_id ON prj.deliverable (milestone_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON prj.deliverable FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- prj.progress_report
CREATE TABLE prj.progress_report (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id                 bigint NOT NULL REFERENCES prj.workspace(id),
  period_start                 date NOT NULL,
  period_end                   date NOT NULL,
  rag                          text NOT NULL CHECK (rag IN ('RED','AMBER','GREEN')),
  achievements                 text NOT NULL,
  issues                       text,
  next_steps                   text,
  reviewed_by_staff_id         bigint REFERENCES dir.staff_member(id),
  reviewed_at                  timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (period_end >= period_start)
);
COMMENT ON TABLE prj.progress_report IS 'Periodic progress report.';
CREATE INDEX ix_progress_report_workspace_id ON prj.progress_report (workspace_id);
CREATE INDEX ix_progress_report_reviewed_by_staff_id ON prj.progress_report (reviewed_by_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON prj.progress_report FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- req.request_category
CREATE TABLE req.request_category (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text NOT NULL,
  service_owner_unit_id        bigint REFERENCES dir.org_unit(id),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE req.request_category IS 'Service catalogue category.';
CREATE INDEX ix_request_category_service_owner_unit_id ON req.request_category (service_owner_unit_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON req.request_category FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- req.request_type
CREATE TABLE req.request_type (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  category_id                  bigint NOT NULL REFERENCES req.request_category(id),
  code                         text NOT NULL UNIQUE,
  name_en                      text NOT NULL,
  name_sw                      text NOT NULL,
  form_schema                  jsonb NOT NULL,
  workflow_code                text NOT NULL,
  fulfilment_unit_id           bigint REFERENCES dir.org_unit(id),
  sla_hours                    integer NOT NULL CHECK (sla_hours > 0),
  active                       boolean NOT NULL DEFAULT true,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE req.request_type IS 'Configurable request type.';
CREATE INDEX ix_request_type_category_id ON req.request_type (category_id);
CREATE INDEX ix_request_type_fulfilment_unit_id ON req.request_type (fulfilment_unit_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON req.request_type FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- req.service_request
CREATE TABLE req.service_request (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  reference_no                 text NOT NULL UNIQUE,
  request_type_id              bigint NOT NULL REFERENCES req.request_type(id),
  requester_staff_id           bigint NOT NULL REFERENCES dir.staff_member(id),
  form_data                    jsonb NOT NULL,
  status                       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','IN_APPROVAL','RETURNED','APPROVED','REJECTED','IN_FULFILMENT','RESOLVED','CLOSED','CANCELLED')),
  workflow_instance_id         bigint REFERENCES wf.workflow_instance(id),
  assigned_agent_staff_id      bigint REFERENCES dir.staff_member(id),
  submitted_at                 timestamptz,
  resolved_at                  timestamptz,
  closed_at                    timestamptz,
  auto_closed                  boolean NOT NULL DEFAULT false,
  satisfaction                 smallint CHECK (satisfaction BETWEEN 1 AND 5),
  resolution_note              text,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (status = 'DRAFT' OR submitted_at IS NOT NULL),
  CHECK (assigned_agent_staff_id IS NULL OR assigned_agent_staff_id <> requester_staff_id)
);
COMMENT ON TABLE req.service_request IS 'Submitted request.';
CREATE INDEX ix_service_request_request_type_id ON req.service_request (request_type_id);
CREATE INDEX ix_service_request_requester_staff_id ON req.service_request (requester_staff_id);
CREATE INDEX ix_service_request_workflow_instance_id ON req.service_request (workflow_instance_id);
CREATE INDEX ix_service_request_assigned_agent_staff_id ON req.service_request (assigned_agent_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON req.service_request FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- req.request_attachment
CREATE TABLE req.request_attachment (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id                   bigint NOT NULL REFERENCES req.service_request(id),
  object_key                   text NOT NULL,
  file_name                    text NOT NULL,
  content_type                 text NOT NULL,
  size_bytes                   bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
  sha256                       char(64) NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE req.request_attachment IS 'File attached to a request.';
CREATE INDEX ix_request_attachment_request_id ON req.request_attachment (request_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON req.request_attachment FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- trn.training_offering
CREATE TABLE trn.training_offering (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  title                        text NOT NULL,
  provider                     text NOT NULL,
  mode                         text NOT NULL CHECK (mode IN ('INTERNAL','EXTERNAL','ONLINE_TELTP')),
  starts_on                    date,
  ends_on                      date,
  location                     text,
  eligibility                  jsonb NOT NULL DEFAULT '{}'::jsonb,
  seats                        integer CHECK (seats > 0),
  application_deadline         date,
  indicative_cost              numeric(18,2) CHECK (indicative_cost >= 0),
  teltp_course_ref             text,
  status                       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('DRAFT','OPEN','CLOSED','CANCELLED','COMPLETED')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on),
  CHECK (mode <> 'ONLINE_TELTP' OR teltp_course_ref IS NOT NULL)
);
COMMENT ON TABLE trn.training_offering IS 'Training opportunity.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON trn.training_offering FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- trn.training_application
CREATE TABLE trn.training_application (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  offering_id                  bigint NOT NULL REFERENCES trn.training_offering(id),
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  nominated_by_staff_id        bigint REFERENCES dir.staff_member(id),
  justification                text NOT NULL,
  status                       text NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','MANAGER_APPROVED','APPROVED','REJECTED','WITHDRAWN','ATTENDED','COMPLETED')),
  workflow_instance_id         bigint REFERENCES wf.workflow_instance(id),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (offering_id, staff_id)
);
COMMENT ON TABLE trn.training_application IS 'Application or nomination.';
CREATE INDEX ix_training_application_staff_id ON trn.training_application (staff_id);
CREATE INDEX ix_training_application_nominated_by_staff_id ON trn.training_application (nominated_by_staff_id);
CREATE INDEX ix_training_application_workflow_instance_id ON trn.training_application (workflow_instance_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON trn.training_application FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- trn.training_record
CREATE TABLE trn.training_record (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  offering_id                  bigint REFERENCES trn.training_offering(id),
  title                        text NOT NULL,
  provider                     text,
  completed_on                 date NOT NULL,
  hours                        numeric(6,1) CHECK (hours > 0),
  certificate_object_key       text,
  source                       text NOT NULL CHECK (source IN ('ICCP','TELTP','HR_IMPORT')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE trn.training_record IS 'Completed training on the staff record.';
CREATE INDEX ix_training_record_staff_id ON trn.training_record (staff_id);
CREATE INDEX ix_training_record_offering_id ON trn.training_record (offering_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON trn.training_record FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- trn.training_need
CREATE TABLE trn.training_need (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  plan_year                    smallint NOT NULL CHECK (plan_year BETWEEN 2026 AND 2100),
  need                         text NOT NULL,
  priority                     text NOT NULL CHECK (priority IN ('HIGH','MEDIUM','LOW')),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE trn.training_need IS 'Annual training need.';
CREATE INDEX ix_training_need_staff_id ON trn.training_need (staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON trn.training_need FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- ana.metric_snapshot
CREATE TABLE ana.metric_snapshot (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  snapshot_date                date NOT NULL,
  metric_code                  text NOT NULL,
  org_unit_id                  bigint REFERENCES dir.org_unit(id),
  dimension_key                text NOT NULL DEFAULT '',
  value                        numeric NOT NULL,
  population                   integer NOT NULL CHECK (population >= 0),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE ana.metric_snapshot IS 'Nightly metric value per unit and dimension.';
CREATE INDEX ix_metric_snapshot_org_unit_id ON ana.metric_snapshot (org_unit_id);
CREATE UNIQUE INDEX ux_metric ON ana.metric_snapshot (snapshot_date, metric_code, coalesce(org_unit_id, 0), dimension_key);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON ana.metric_snapshot FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- ana.report_export
CREATE TABLE ana.report_export (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  requested_by_staff_id        bigint NOT NULL REFERENCES dir.staff_member(id),
  report_code                  text NOT NULL,
  parameters                   jsonb NOT NULL,
  format                       text NOT NULL CHECK (format IN ('PDF','XLSX','CSV')),
  row_count                    integer NOT NULL,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);
COMMENT ON TABLE ana.report_export IS 'Audit log of report exports.';
CREATE INDEX ix_report_export_requested_by_staff_id ON ana.report_export (requested_by_staff_id);
CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON ana.report_export FOR EACH ROW EXECUTE FUNCTION core.append_only();

-- fdb.suggestion
CREATE TABLE fdb.suggestion (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  staff_id                     bigint NOT NULL REFERENCES dir.staff_member(id),
  category                     text NOT NULL,
  description                  text NOT NULL,
  status                       text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','TRIAGED','ASSIGNED','RESPONDED','CLOSED')),
  assigned_to_staff_id         bigint REFERENCES dir.staff_member(id),
  response                     text,
  responded_at                 timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE fdb.suggestion IS 'Identified staff suggestion or issue report.';
CREATE INDEX ix_suggestion_staff_id ON fdb.suggestion (staff_id);
CREATE INDEX ix_suggestion_assigned_to_staff_id ON fdb.suggestion (assigned_to_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fdb.suggestion FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fdb.survey
CREATE TABLE fdb.survey (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  title_en                     text NOT NULL,
  title_sw                     text,
  mode                         text NOT NULL CHECK (mode IN ('IDENTIFIED','ANONYMOUS')),
  audience                     jsonb NOT NULL,
  opens_at                     timestamptz NOT NULL,
  closes_at                    timestamptz NOT NULL,
  status                       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','OPEN','CLOSED')),
  owner_staff_id               bigint NOT NULL REFERENCES dir.staff_member(id),
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  CHECK (closes_at > opens_at)
);
COMMENT ON TABLE fdb.survey IS 'Survey definition.';
CREATE INDEX ix_survey_owner_staff_id ON fdb.survey (owner_staff_id);
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fdb.survey FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fdb.survey_question
CREATE TABLE fdb.survey_question (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  survey_id                    bigint NOT NULL REFERENCES fdb.survey(id),
  q_no                         smallint NOT NULL,
  q_type                       text NOT NULL CHECK (q_type IN ('SINGLE','MULTIPLE','LIKERT','TEXT')),
  text_en                      text NOT NULL,
  text_sw                      text,
  options                      jsonb,
  required                     boolean NOT NULL DEFAULT false,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  created_by                   text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  updated_at                   timestamptz,
  updated_by                   text,
  row_version                  integer NOT NULL DEFAULT 0,
  UNIQUE (survey_id, q_no)
);
COMMENT ON TABLE fdb.survey_question IS 'Survey question.';
CREATE TRIGGER trg_z_touch BEFORE UPDATE ON fdb.survey_question FOR EACH ROW EXECUTE FUNCTION core.touch_row();

-- fdb.survey_response
CREATE TABLE fdb.survey_response (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  survey_id                    bigint NOT NULL REFERENCES fdb.survey(id),
  respondent_staff_id          bigint REFERENCES dir.staff_member(id),
  submitted_on                 date NOT NULL DEFAULT current_date,
  answers                      jsonb NOT NULL
);
COMMENT ON TABLE fdb.survey_response IS 'Response; respondent is null for anonymous surveys (trigger-enforced); only the date is kept.';
CREATE INDEX ix_survey_response_survey_id ON fdb.survey_response (survey_id);
CREATE INDEX ix_survey_response_respondent_staff_id ON fdb.survey_response (respondent_staff_id);

-- fdb.survey_participation
CREATE TABLE fdb.survey_participation (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  survey_id                    bigint NOT NULL REFERENCES fdb.survey(id),
  participant_token            bytea NOT NULL,
  UNIQUE (survey_id, participant_token)
);
COMMENT ON TABLE fdb.survey_participation IS 'One-way participation token to prevent double voting; holds no staff identifier.';

-- fdb.protected_case
CREATE TABLE fdb.protected_case (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id                    uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  case_key_hash                bytea NOT NULL UNIQUE,
  category                     text NOT NULL,
  description                  text NOT NULL,
  status                       text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','TRIAGED','ASSIGNED','RESPONDED','CLOSED')),
  assigned_officer_staff_id    bigint REFERENCES dir.staff_member(id),
  created_on                   date NOT NULL DEFAULT current_date,
  closed_on                    date
);
COMMENT ON TABLE fdb.protected_case IS 'Protected report. No identity, IP or device columns by design.';
CREATE INDEX ix_protected_case_assigned_officer_staff_id ON fdb.protected_case (assigned_officer_staff_id);

-- fdb.protected_case_message
CREATE TABLE fdb.protected_case_message (
  id                           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  case_id                      bigint NOT NULL REFERENCES fdb.protected_case(id),
  from_reporter                boolean NOT NULL,
  officer_staff_id             bigint REFERENCES dir.staff_member(id),
  body                         text NOT NULL,
  created_on                   date NOT NULL DEFAULT current_date,
  CHECK (from_reporter = (officer_staff_id IS NULL))
);
COMMENT ON TABLE fdb.protected_case_message IS 'Message on a protected case.';
CREATE INDEX ix_protected_case_message_case_id ON fdb.protected_case_message (case_id);
CREATE INDEX ix_protected_case_message_officer_staff_id ON fdb.protected_case_message (officer_staff_id);

