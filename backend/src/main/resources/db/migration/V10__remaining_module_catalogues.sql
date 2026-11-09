-- Remaining-module reference catalogues and shared human-readable reference sequence.
CREATE SEQUENCE IF NOT EXISTS core.reference_seq START WITH 1001;

INSERT INTO doc.document_type(code,name_en,name_sw,workflow_code,review_cycle,retention_period) VALUES
 ('POLICY','Policy','Sera','DOC_POLICY','2 years','7 years'),
 ('SOP','Standard operating procedure','Utaratibu wa kazi','DOC_STANDARD','1 year','7 years'),
 ('TEMPLATE','Official template','Kiolezo rasmi','DOC_STANDARD','2 years','7 years'),
 ('RESEARCH','Research document','Nyaraka ya utafiti','DOC_RESEARCH','2 years','10 years')
ON CONFLICT (code) DO NOTHING;

INSERT INTO req.request_type(category_id,code,name_en,name_sw,form_schema,workflow_code,sla_hours)
SELECT c.id,v.code,v.name_en,v.name_sw,v.form_schema::jsonb,v.workflow_code,v.sla_hours
FROM req.request_category c
JOIN (VALUES
 ('ICT','ICT_SUPPORT','ICT support request','Ombi la msaada wa TEHAMA','{"fields":[{"name":"summary","type":"text","required":true},{"name":"description","type":"textarea","required":true}]}','REQ_STANDARD',24),
 ('ADMIN','ADMIN_SERVICE','Administrative service','Huduma ya utawala','{"fields":[{"name":"summary","type":"text","required":true},{"name":"description","type":"textarea","required":true}]}','REQ_STANDARD',48),
 ('TRAVEL','TRAVEL_AUTHORISATION','Travel authorisation','Kibali cha safari','{"fields":[{"name":"purpose","type":"textarea","required":true},{"name":"travelDate","type":"date","required":true},{"name":"destination","type":"text","required":true}]}','REQ_TRAVEL',72),
 ('EQUIPMENT','EQUIPMENT_REQUEST','Equipment request','Ombi la vifaa','{"fields":[{"name":"summary","type":"text","required":true},{"name":"justification","type":"textarea","required":true}]}','REQ_STANDARD',120),
 ('MAINTENANCE','MAINTENANCE_REQUEST','Maintenance request','Ombi la matengenezo','{"fields":[{"name":"summary","type":"text","required":true},{"name":"location","type":"text","required":true},{"name":"description","type":"textarea","required":true}]}','REQ_STANDARD',48)
) AS v(category_code,code,name_en,name_sw,form_schema,workflow_code,sla_hours) ON v.category_code=c.code
ON CONFLICT (code) DO NOTHING;
CREATE OR REPLACE VIEW ana.current_dashboard AS
 SELECT 1 sort_order,'TASK_OPEN' code,'Open tasks' label,count(*)::numeric value,'tasks' unit FROM tsk.task WHERE status NOT IN ('CLOSED','CANCELLED')
 UNION ALL SELECT 2,'TASK_OVERDUE','Overdue tasks',count(*)::numeric,'tasks' FROM tsk.task WHERE due_on<current_date AND status NOT IN ('CLOSED','CANCELLED')
 UNION ALL SELECT 3,'REQUEST_PENDING','Pending requests',count(*)::numeric,'requests' FROM req.service_request WHERE status IN ('SUBMITTED','IN_APPROVAL','APPROVED','IN_FULFILMENT')
 UNION ALL SELECT 4,'MEETING_OPEN','Open meeting resolutions',count(*)::numeric,'resolutions' FROM mtg.resolution WHERE status<>'CLOSED'
 UNION ALL SELECT 5,'PROJECT_ACTIVE','Active projects',count(*)::numeric,'projects' FROM prj.workspace WHERE status='ACTIVE'
 UNION ALL SELECT 6,'TRAINING_OPEN','Open training opportunities',count(*)::numeric,'courses' FROM trn.training_offering WHERE status='OPEN'
 UNION ALL SELECT 7,'DOCUMENT_REVIEW','Documents due for review',count(*)::numeric,'documents' FROM doc.document WHERE review_due_on<=current_date+30 AND status='PUBLISHED'
 UNION ALL SELECT 8,'SUGGESTION_OPEN','Open suggestions',count(*)::numeric,'suggestions' FROM fdb.suggestion WHERE status<>'CLOSED';
