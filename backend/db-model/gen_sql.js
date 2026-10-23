// Generates the Flyway baseline (V1–V4) from data.js plus the hand-written SQL in sql_src/.
// Usage: node backend/db-model/gen_sql.js   (writes backend/src/main/resources/db/migration)
const fs = require('fs');
const path = require('path');
const { SCHEMAS, TABLES, ROLES, PRIVILEGED, MFA, INTEGRATIONS } = require('./data');

const OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'db', 'migration');
const APPEND_ALLOWED = {
  'integ.outbox_event': ['published_at'],
  'mtg.recording': ['retention_until', 'deleted_at'],
  'msg.message_attachment': ['scan_status'],
};
const EXTRA_GUARDED = { 'fin.payment_allocation': ['approved_by', 'published_at'] };
const q = s => `'${String(s).replace(/'/g, "''")}'`;

function allColumns(t) {
  const pre = [['id', 'bigint', 'GENERATED ALWAYS AS IDENTITY PRIMARY KEY', 'Internal surrogate key.']];
  if (t.pub) pre.push(['public_id', 'uuid', 'NOT NULL DEFAULT gen_random_uuid() UNIQUE', 'Public identifier used in APIs and URLs.']);
  const post = [];
  const by = "NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)";
  if (t.std) {
    post.push(['created_at', 'timestamptz', 'NOT NULL DEFAULT now()', 'Creation time.'], ['created_by', 'text', by, 'Creating subject.'],
      ['updated_at', 'timestamptz', '', 'Last update time (trigger).'], ['updated_by', 'text', '', 'Last updating subject (trigger).'],
      ['row_version', 'integer', 'NOT NULL DEFAULT 0', 'Optimistic-lock version (trigger).']);
  } else if (t.append) {
    post.push(['created_at', 'timestamptz', 'NOT NULL DEFAULT now()', 'Creation time.'], ['created_by', 'text', by, 'Creating subject.']);
  }
  const own = t.cols.filter(c => !post.some(p => p[0] === c[0]));
  return [...pre, ...own, ...post];
}

function tableSql(t) {
  const cols = allColumns(t);
  const lines = cols.map(c => `  ${c[0].padEnd(28)} ${c[1]}${c[2] ? ' ' + c[2] : ''}`);
  t.uniques.forEach(u => lines.push(`  ${u}`));
  t.checks.forEach(ch => lines.push(`  ${ch}`));
  const fq = `${t.schema}.${t.name}`;
  let sql = `CREATE TABLE ${fq} (\n${lines.join(',\n')}\n);\nCOMMENT ON TABLE ${fq} IS ${q(t.desc)};\n`;
  cols.forEach(c => {
    if (/REFERENCES/.test(c[2]) && !/UNIQUE/.test(c[2]) && !t.uniques.some(u => u.replace(/\s/g, '').startsWith(`UNIQUE(${c[0]}`))) {
      sql += `CREATE INDEX ix_${t.name}_${c[0]} ON ${fq} (${c[0]});\n`;
    }
  });
  t.indexes.forEach(i => { sql += i + ';\n'; });
  if (t.std) sql += `CREATE TRIGGER trg_z_touch BEFORE UPDATE ON ${fq} FOR EACH ROW EXECUTE FUNCTION core.touch_row();\n`;
  if (t.append || EXTRA_GUARDED[fq]) {
    const allowed = APPEND_ALLOWED[fq] || EXTRA_GUARDED[fq] || [];
    sql += `CREATE TRIGGER trg_a_append_only BEFORE UPDATE OR DELETE ON ${fq} FOR EACH ROW EXECUTE FUNCTION core.append_only(${allowed.map(q).join(', ')});\n`;
  }
  return sql;
}

const header = t => `-- ${t}\n-- TIRDO ICCP — generated from backend/db-model (Doc 06, Draft 4.0). Do not edit by hand.\n-- Target: PostgreSQL 16. Applied by Flyway as role iccp_owner.\n\n`;

let v1 = header('V1__baseline_schemas_and_tables.sql') + 'SET client_min_messages = warning;\n\n';
SCHEMAS.forEach(([s, d]) => { v1 += `CREATE SCHEMA IF NOT EXISTS ${s};\nCOMMENT ON SCHEMA ${s} IS ${q(d)};\n`; });
v1 += `
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

`;
TABLES.forEach(t => { v1 += `-- ${t.schema}.${t.name}\n` + tableSql(t) + '\n'; });

const src = f => fs.readFileSync(path.join(__dirname, 'sql_src', f), 'utf8');
const v2 = header('V2__integrity_rules.sql') + src('V2_integrity.sql');
const v3 = header('V3__security_roles_and_rls.sql') + src('V3_security.sql');

let v4 = header('V4__reference_data.sql') + "SELECT set_config('iccp.actor', 'flyway', true);\n\n";
v4 += 'INSERT INTO sec.role (code, name, privileged, requires_mfa) VALUES\n' +
  ROLES.map(r => `  (${q(r[0])}, ${q(r[1])}, ${PRIVILEGED.includes(r[0])}, ${MFA.includes(r[0])})`).join(',\n') + ';\n\n';
v4 += src('V4_reference.sql');
v4 += '\nINSERT INTO integ.integration_endpoint (code, system_name, direction, mechanism) VALUES\n' +
  INTEGRATIONS.map(i => `  (${q(i[0])}, ${q(i[1])}, '${i[2]}', ${q(i[3])})`).join(',\n') + ';\n';

fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(path.join(OUT, 'V1__baseline_schemas_and_tables.sql'), v1);
fs.writeFileSync(path.join(OUT, 'V2__integrity_rules.sql'), v2);
fs.writeFileSync(path.join(OUT, 'V3__security_roles_and_rls.sql'), v3);
fs.writeFileSync(path.join(OUT, 'V4__reference_data.sql'), v4);
console.log(`tables ${TABLES.length}, columns ${TABLES.reduce((a, t) => a + allColumns(t).length, 0)}`);
module.exports = { allColumns };
