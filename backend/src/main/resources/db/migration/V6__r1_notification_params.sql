-- V6__r1_notification_params.sql
-- R1 addition to the Doc 06 baseline (record in the Doc 06 data dictionary at the next revision):
-- template parameters are kept with each notification so that email and SMS deliveries can be
-- rendered again on retry. Parameters never include amounts or confidential content (NTF-08).
SELECT set_config('iccp.actor', 'flyway', true);

ALTER TABLE ntf.notification ADD COLUMN params jsonb NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN ntf.notification.params IS 'Template parameters (title, link, reference) for re-rendering external deliveries. No amounts.';

CREATE INDEX ix_ntf_source ON ntf.notification (source_public_id, event_type, recipient_staff_id);
CREATE INDEX ix_delivery_recent ON ntf.delivery_attempt (notification_id, channel, id DESC);
