-- V7__r1_workflow_definitions.sql
-- Workflow definitions used by Release 1 (shared workflow engine, XFR-02).
-- Announcement approval: one step by any COMMS_APPROVER other than the author (BRL-07).
SELECT set_config('iccp.actor', 'flyway', true);

INSERT INTO wf.workflow_definition (code, def_version, module, name, definition) VALUES
  ('COM_APPROVAL', 1, 'COM', 'Announcement approval',
   '{"steps":[{"no":1,"role":"COMMS_APPROVER","excludeInitiator":true,"slaWorkingDays":1}],"categories":["CIRCULAR","DIRECTIVE_NOTICE"]}');
