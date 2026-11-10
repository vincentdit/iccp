CREATE TABLE fin.report_export (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  batch_id bigint NOT NULL REFERENCES fin.import_batch(id),
  report_type text NOT NULL CHECK (report_type IN ('RECONCILIATION_CSV')),
  signature text NOT NULL UNIQUE,
  content_sha256 text NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  exported_by text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user),
  exported_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_fin_report_export_batch ON fin.report_export(batch_id, exported_at DESC);
COMMENT ON TABLE fin.report_export IS 'Append-only register of signed Finance report downloads.';
CREATE TRIGGER trg_fin_report_export_append_only BEFORE UPDATE OR DELETE ON fin.report_export
FOR EACH ROW EXECUTE FUNCTION core.append_only();
