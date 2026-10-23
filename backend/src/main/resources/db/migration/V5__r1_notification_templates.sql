-- V5__r1_notification_templates.sql
-- Release 1 notification templates (English and Kiswahili). Kiswahili wording is a draft for review
-- by the Communication unit (Doc 09 open item). External channels never carry amounts or confidential
-- content: the CHECK constraint on ntf.template rejects amount placeholders (BRL-06, NTF-08).
SELECT set_config('iccp.actor', 'flyway', true);

INSERT INTO ntf.template (event_type, channel, language, tpl_version, subject, body, status) VALUES
-- Announcements
('AnnouncementPublished', 'IN_APP', 'en', 1, NULL, '{{title}}', 'PUBLISHED'),
('AnnouncementPublished', 'IN_APP', 'sw', 1, NULL, '{{title}}', 'PUBLISHED'),
('AnnouncementPublished', 'EMAIL',  'en', 1, 'New notice: {{title}}', 'A new notice has been published in ICCP: "{{title}}". Sign in to read it: {{link}}', 'PUBLISHED'),
('AnnouncementPublished', 'EMAIL',  'sw', 1, 'Tangazo jipya: {{title}}', 'Tangazo jipya limechapishwa ICCP: "{{title}}". Ingia kulisoma: {{link}}', 'PUBLISHED'),
('AnnouncementAckRequired', 'IN_APP', 'en', 1, NULL, 'Please acknowledge: {{title}}', 'PUBLISHED'),
('AnnouncementAckRequired', 'IN_APP', 'sw', 1, NULL, 'Tafadhali thibitisha kupokea: {{title}}', 'PUBLISHED'),
('AnnouncementAckRequired', 'EMAIL',  'en', 1, 'Action required: please acknowledge "{{title}}"', 'A notice that requires your acknowledgement has been published in ICCP: "{{title}}". Sign in to read and acknowledge it: {{link}}', 'PUBLISHED'),
('AnnouncementAckRequired', 'EMAIL',  'sw', 1, 'Hatua inahitajika: tafadhali thibitisha kupokea "{{title}}"', 'Tangazo linalohitaji uthibitisho wako limechapishwa ICCP: "{{title}}". Ingia kulisoma na kuthibitisha: {{link}}', 'PUBLISHED'),
('AnnouncementAckReminder', 'IN_APP', 'en', 1, NULL, 'Reminder: please acknowledge "{{title}}"', 'PUBLISHED'),
('AnnouncementAckReminder', 'IN_APP', 'sw', 1, NULL, 'Ukumbusho: tafadhali thibitisha kupokea "{{title}}"', 'PUBLISHED'),
('AnnouncementAckReminder', 'EMAIL',  'en', 1, 'Reminder: please acknowledge "{{title}}"', 'You have not yet acknowledged the notice "{{title}}". Sign in to acknowledge it: {{link}}', 'PUBLISHED'),
('AnnouncementAckReminder', 'EMAIL',  'sw', 1, 'Ukumbusho: tafadhali thibitisha kupokea "{{title}}"', 'Bado hujathibitisha kupokea tangazo "{{title}}". Ingia kuthibitisha: {{link}}', 'PUBLISHED'),
('AnnouncementAckOverdue', 'IN_APP', 'en', 1, NULL, '{{count}} of your staff have not acknowledged "{{title}}"', 'PUBLISHED'),
('AnnouncementAckOverdue', 'IN_APP', 'sw', 1, NULL, 'Watumishi {{count}} wako hawajathibitisha kupokea "{{title}}"', 'PUBLISHED'),
('EmergencyAlert', 'IN_APP', 'en', 1, NULL, 'Emergency alert: {{title}}', 'PUBLISHED'),
('EmergencyAlert', 'IN_APP', 'sw', 1, NULL, 'Tahadhari ya dharura: {{title}}', 'PUBLISHED'),
('EmergencyAlert', 'SMS',    'en', 1, NULL, 'TIRDO ALERT: {{title}}. Details in ICCP.', 'PUBLISHED'),
('EmergencyAlert', 'SMS',    'sw', 1, NULL, 'TAHADHARI TIRDO: {{title}}. Maelezo zaidi ICCP.', 'PUBLISHED'),
('EmergencyAlert', 'EMAIL',  'en', 1, 'TIRDO ALERT: {{title}}', 'An emergency alert has been issued: "{{title}}". Sign in to ICCP for details: {{link}}', 'PUBLISHED'),
('EmergencyAlert', 'EMAIL',  'sw', 1, 'TAHADHARI TIRDO: {{title}}', 'Tahadhari ya dharura imetolewa: "{{title}}". Ingia ICCP kwa maelezo: {{link}}', 'PUBLISHED'),
('AnnouncementSubmitted', 'IN_APP', 'en', 1, NULL, 'Awaiting your approval: {{title}}', 'PUBLISHED'),
('AnnouncementSubmitted', 'IN_APP', 'sw', 1, NULL, 'Linasubiri idhini yako: {{title}}', 'PUBLISHED'),
('AnnouncementSubmitted', 'EMAIL',  'en', 1, 'Approval needed: {{title}}', 'A notice is awaiting your approval in ICCP: "{{title}}". Review it: {{link}}', 'PUBLISHED'),
('AnnouncementSubmitted', 'EMAIL',  'sw', 1, 'Idhini inahitajika: {{title}}', 'Tangazo linasubiri idhini yako ICCP: "{{title}}". Lipitie: {{link}}', 'PUBLISHED'),
('AnnouncementDecided', 'IN_APP', 'en', 1, NULL, '"{{title}}" was {{decision}}', 'PUBLISHED'),
('AnnouncementDecided', 'IN_APP', 'sw', 1, NULL, '"{{title}}" — {{decision}}', 'PUBLISHED'),
-- Finance
('PaymentPublished', 'IN_APP', 'en', 1, NULL, 'A payment update is available in your Financial Diary', 'PUBLISHED'),
('PaymentPublished', 'IN_APP', 'sw', 1, NULL, 'Kuna taarifa mpya ya malipo katika Shajara yako ya Fedha', 'PUBLISHED'),
('PaymentPublished', 'EMAIL',  'en', 1, 'A payment update is available in ICCP', 'A payment update is available in ICCP. Sign in to view it: {{link}}', 'PUBLISHED'),
('PaymentPublished', 'EMAIL',  'sw', 1, 'Kuna taarifa mpya ya malipo ICCP', 'Kuna taarifa mpya ya malipo ICCP. Ingia kuiona: {{link}}', 'PUBLISHED'),
('PaymentPublished', 'SMS',    'en', 1, NULL, 'TIRDO ICCP: A payment update is available. Sign in to view: {{link}}', 'PUBLISHED'),
('PaymentPublished', 'SMS',    'sw', 1, NULL, 'TIRDO ICCP: Kuna taarifa mpya ya malipo. Ingia kuiona: {{link}}', 'PUBLISHED'),
('FinanceBatchAwaitingReview', 'IN_APP', 'en', 1, NULL, 'Finance batch {{file}} is ready for review', 'PUBLISHED'),
('FinanceBatchAwaitingReview', 'IN_APP', 'sw', 1, NULL, 'Kundi la fedha {{file}} liko tayari kupitiwa', 'PUBLISHED'),
('FinanceQueryRaised', 'IN_APP', 'en', 1, NULL, 'New staff query on claim {{ref}}', 'PUBLISHED'),
('FinanceQueryRaised', 'IN_APP', 'sw', 1, NULL, 'Hoja mpya ya mtumishi kuhusu dai {{ref}}', 'PUBLISHED'),
('FinanceQueryResponded', 'IN_APP', 'en', 1, NULL, 'Finance has responded to your query on claim {{ref}}', 'PUBLISHED'),
('FinanceQueryResponded', 'IN_APP', 'sw', 1, NULL, 'Fedha imejibu hoja yako kuhusu dai {{ref}}', 'PUBLISHED'),
('FinanceQueryResponded', 'EMAIL',  'en', 1, 'Finance has responded to your query', 'Finance has responded to your query on claim {{ref}}. Sign in to read the response: {{link}}', 'PUBLISHED'),
('FinanceQueryResponded', 'EMAIL',  'sw', 1, 'Fedha imejibu hoja yako', 'Fedha imejibu hoja yako kuhusu dai {{ref}}. Ingia kusoma jibu: {{link}}', 'PUBLISHED'),
-- Directory and security
('HrSyncExceptions', 'IN_APP', 'en', 1, NULL, 'HR import {{file}}: {{count}} records need attention', 'PUBLISHED'),
('HrSyncExceptions', 'IN_APP', 'sw', 1, NULL, 'Uingizaji wa HR {{file}}: kumbukumbu {{count}} zinahitaji kushughulikiwa', 'PUBLISHED'),
('RoleGranted', 'IN_APP', 'en', 1, NULL, 'You have been given the role {{role}}', 'PUBLISHED'),
('RoleGranted', 'IN_APP', 'sw', 1, NULL, 'Umepewa jukumu {{role}}', 'PUBLISHED');
