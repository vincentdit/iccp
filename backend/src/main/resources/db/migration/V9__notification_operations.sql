-- Operational records for daily email digests and provider delivery receipts.
CREATE TABLE ntf.email_digest_delivery (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id bigint NOT NULL REFERENCES dir.staff_member(id),
  digest_date date NOT NULL,
  notification_count integer NOT NULL CHECK (notification_count > 0),
  status text NOT NULL CHECK (status IN ('SENT','FAILED')),
  provider_ref text,
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  UNIQUE (staff_id, digest_date)
);
COMMENT ON TABLE ntf.email_digest_delivery IS 'One daily notification email digest per opted-in staff member.';

CREATE TABLE ntf.delivery_receipt (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider_ref text NOT NULL,
  status text NOT NULL CHECK (status IN ('DELIVERED','FAILED')),
  error text,
  received_at timestamptz NOT NULL DEFAULT now(),
  created_by text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  UNIQUE (provider_ref, status)
);
CREATE INDEX ix_delivery_receipt_provider_ref ON ntf.delivery_receipt (provider_ref, received_at DESC);
COMMENT ON TABLE ntf.delivery_receipt IS 'Append-only SMS provider delivery reports.';
CREATE TRIGGER trg_a_delivery_receipt_append_only BEFORE UPDATE OR DELETE ON ntf.delivery_receipt
FOR EACH ROW EXECUTE FUNCTION core.append_only();
