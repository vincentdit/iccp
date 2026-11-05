-- Shared attachment malware-scan ledger (XFR-03).
CREATE TABLE integ.file_scan (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  object_key text NOT NULL UNIQUE,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CLEAN','INFECTED','ERROR')),
  scanner text NOT NULL,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  result text,
  created_at timestamptz NOT NULL DEFAULT now(),
  scanned_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_file_scan_due ON integ.file_scan (status, attempts, updated_at);
COMMENT ON TABLE integ.file_scan IS 'Quarantine and malware-scan status for private attachment objects.';

