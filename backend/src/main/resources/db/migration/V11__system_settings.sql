CREATE TABLE sec.system_setting (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code text NOT NULL UNIQUE,
  category text NOT NULL,
  name text NOT NULL,
  description text NOT NULL,
  value jsonb NOT NULL,
  default_value jsonb NOT NULL,
  value_type text NOT NULL CHECK (value_type IN ('BOOLEAN','INTEGER','TEXT')),
  public boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by text NOT NULL DEFAULT coalesce(current_setting('iccp.actor', true), current_user)
);

INSERT INTO sec.feature_flag(code,enabled) VALUES
 ('MODULE_ANNOUNCEMENTS',true),('MODULE_FINANCE',true),('MODULE_DIRECTORY',true),
 ('MODULE_NOTIFICATIONS',true),('MODULE_WORK_HUB',true),('MODULE_MEETINGS',true),('MODULE_HR_ADMIN',true)
ON CONFLICT(code) DO NOTHING;

INSERT INTO sec.system_setting(code,category,name,description,value,default_value,value_type,public) VALUES
 ('APP_NAME','Branding','Application name','Name shown in the browser and system settings.','"ICCP"','"ICCP"','TEXT',true),
 ('SUPPORT_EMAIL','Branding','Support email','Address shown to users who need assistance.','"support@tirdo.example"','"support@tirdo.example"','TEXT',true),
 ('MAINTENANCE_MODE','Operations','Maintenance mode','Blocks non-administrator API access while maintenance is in progress.','false','false','BOOLEAN',true),
 ('DEFAULT_PAGE_SIZE','Operations','Default page size','Default number of records returned in list screens.','25','25','INTEGER',false),
 ('MAX_PAGE_SIZE','Operations','Maximum page size','Largest page size accepted by list endpoints.','200','200','INTEGER',false),
 ('ALLOW_FILE_UPLOADS','Security','Allow file uploads','Master switch for new file uploads.','true','true','BOOLEAN',false),
 ('AUDIT_EXPORT_ENABLED','Security','Allow audit export','Allows authorised auditors to export search results.','true','true','BOOLEAN',false),
 ('SESSION_NOTICE','User experience','Sign-in notice','Optional notice displayed to signed-in users.','""','""','TEXT',true)
ON CONFLICT(code) DO NOTHING;
