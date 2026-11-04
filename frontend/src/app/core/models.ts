// API shapes (Doc 07). Dates are ISO strings; money is a JSON number with two decimals.

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  fieldErrors: { field: string; message: string }[];
  requestId?: string;
}

export interface PublicConfig {
  authMode: 'dev' | 'oidc';
  issuer: string;
  clientId: string;
  version: string;
  languages: string[];
  timeZone: string;
  mmsUrl: string;
  mmsEnabled: boolean;
  internalMeetingsEnabled: boolean;
}

export interface Me {
  staffPublicId: string;
  employeeNo: string;
  displayName: string;
  email: string;
  positionTitle?: string;
  unitCode: string;
  unitName: string;
  language: 'en' | 'sw';
  roles: string[];
  can: {
    publishAnnouncements: boolean;
    approveAnnouncements: boolean;
    emergencyAlerts: boolean;
    viewReach: boolean;
    financeWorkspace: boolean;
    financeImport: boolean;
    financeReview: boolean;
    hrAdmin: boolean;
    securityAdmin: boolean;
    auditSearch: boolean;
    manageMeetings: boolean;
  };
}

// ---------------------------------------------------------------- meetings
export interface MeetingType { code: string; nameEn: string; nameSw: string; confidentiality: string; quorumPercent: number; }
export interface MeetingSummary { publicId: string; title: string; typeCode: string; typeNameEn: string; typeNameSw: string; startsAt: string; endsAt: string; mode: string; venue?: string; onlineLink?: string; status: string; confidentiality: string; chairName: string; secretaryName: string; participantCount: number; myRole: string; myRsvp?: string; }
export interface MeetingParticipant { publicId: string; displayName: string; positionTitle?: string; unitName: string; role: string; rsvp?: string; attendance?: string; }
export interface MeetingDetail { meeting: MeetingSummary; participants: MeetingParticipant[]; agenda: { itemNo: number; title: string; minutesAllocated?: number }[]; }
export interface MeetingMinutes { versionNo?: number; status?: string; summary: string; resolutions: { publicId?: string; resolutionNo?: string; text: string; ownerStaffPublicId?: string; ownerName?: string; dueOn?: string; status?: string }[]; canEdit: boolean; canSubmit: boolean; canApprove: boolean; }

export interface DevUser {
  employeeNo: string;
  publicId: string;
  displayName: string;
  positionTitle?: string;
  unitName: string;
  roles: string[];
}

// ---------------------------------------------------------------- directory
export interface StaffSummary {
  publicId: string;
  employeeNo: string;
  displayName: string;
  positionTitle?: string;
  unitCode: string;
  unitName: string;
  email: string;
  officePhone?: string;
  employmentStatus: string;
  expertise: string[];
}

export interface PersonRef {
  publicId: string;
  displayName: string;
  positionTitle?: string;
}

export interface StaffProfile extends StaffSummary {
  firstName: string;
  middleName?: string;
  lastName: string;
  title?: string;
  unitNameSw?: string;
  mobilePhone?: string;
  mobileVisible: boolean;
  dutyStation?: string;
  staffCategory?: string;
  bio?: string;
  lineManager?: PersonRef;
  directReports: PersonRef[];
  actingRoles: { positionTitle: string; unitName: string; startsOn: string; endsOn?: string }[];
  self: boolean;
  preferredLanguage?: string;
}

export interface UnitNode {
  id: number;
  code: string;
  nameEn: string;
  nameSw?: string;
  unitType: string;
  head?: PersonRef;
  headActing: boolean;
  staffCount: number;
  children: UnitNode[];
}

export interface HrBatch {
  id: number;
  fileName: string;
  status: string;
  recordsTotal: number;
  createdCount: number;
  updatedCount: number;
  deactivatedCount: number;
  exceptionCount: number;
  createdBy: string;
  createdAt: string;
}

export interface HrException {
  id: number;
  employeeNo?: string;
  reasonCode: string;
  payload?: string;
  status: string;
  resolvedBy?: string;
  resolvedAt?: string;
}

export interface ActingAppointment {
  id: number;
  positionCode: string;
  positionTitle: string;
  unitName: string;
  staffPublicId: string;
  staffName: string;
  startsOn: string;
  endsOn?: string;
  reason?: string;
  approvedBy?: string;
}

// ---------------------------------------------------------------- notifications
export interface InboxItem {
  publicId: string;
  eventType: string;
  category: string;
  mandatory: boolean;
  title: string;
  deepLink?: string;
  sourceModule?: string;
  createdAt: string;
  readAt?: string;
}

export interface Preferences {
  categories: { category: string; mandatory: boolean; email: boolean; sms: boolean }[];
  quietStart?: string;
  quietEnd?: string;
  emailDigest: boolean;
}

// ---------------------------------------------------------------- announcements
export type Category = 'NOTICE' | 'CIRCULAR' | 'DIRECTIVE_NOTICE' | 'EMERGENCY_ALERT' | 'EVENT';

export interface AudienceRule {
  type: 'ALL' | 'ORG_UNIT' | 'ORG_UNIT_TREE' | 'ROLE' | 'STAFF_CATEGORY' | 'DUTY_STATION' | 'STAFF';
  value?: string | null;
}

export interface FeedItem {
  publicId: string;
  category: Category;
  priority: number;
  title: string;
  excerpt: string;
  publishedAt: string;
  effectiveAt?: string;
  expiresAt?: string;
  requiresAck: boolean;
  readAt?: string;
  acknowledgedAt?: string;
  attachmentCount: number;
  currentVersion: number;
}

export interface Attachment {
  id: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
}

export interface FeedDetail {
  publicId: string;
  category: Category;
  classification: string;
  priority: number;
  titleEn?: string;
  bodyEn?: string;
  titleSw?: string;
  bodySw?: string;
  publishedAt: string;
  effectiveAt?: string;
  expiresAt?: string;
  requiresAck: boolean;
  readAt?: string;
  acknowledgedAt?: string;
  currentVersion: number;
  authorUnit?: string;
  attachments: Attachment[];
  changeNote?: string;
}

export interface ManageItem {
  publicId: string;
  category: Category;
  status: string;
  priority: number;
  titleEn?: string;
  titleSw?: string;
  authorName: string;
  effectiveAt?: string;
  publishedAt?: string;
  updatedAt: string;
  requiresAck: boolean;
  recipients: number;
  acknowledged: number;
}

export interface ManageDetail {
  publicId: string;
  category: Category;
  classification: string;
  status: string;
  priority: number;
  requiresAck: boolean;
  effectiveAt?: string;
  expiresAt?: string;
  publishedAt?: string;
  withdrawnReason?: string;
  approvedAt?: string;
  currentVersion: number;
  titleEn?: string;
  bodyEn?: string;
  titleSw?: string;
  bodySw?: string;
  authorPublicId: string;
  authorName: string;
  approverName?: string;
  audiences: AudienceRule[];
  versions: { versionNo: number; titleEn?: string; titleSw?: string; changeNote?: string; createdBy: string; createdAt: string }[];
  decisions: { stepNo: number; approverName: string; decision: string; comment?: string; decidedAt: string }[];
  attachments: Attachment[];
  recipients: number;
  read: number;
  acknowledged: number;
  canEdit: boolean;
  canSubmit: boolean;
  canDecide: boolean;
  canWithdraw: boolean;
  canAmend: boolean;
}

export interface AnnouncementInput {
  category: Category;
  classification?: string;
  requiresAck: boolean;
  priority?: number;
  effectiveAt?: string | null;
  expiresAt?: string | null;
  titleEn?: string;
  bodyEn?: string;
  titleSw?: string;
  bodySw?: string;
  audiences: AudienceRule[];
  changeNote?: string;
}

export interface Reach {
  publicId: string;
  title: string;
  requiresAck: boolean;
  targeted: number;
  delivered: number;
  read: number;
  acknowledged: number;
  byUnit: { unitCode: string; unitName: string; targeted: number; read: number; acknowledged: number }[];
  notAcknowledged: { publicId: string; displayName: string; unitName: string }[];
}

// ---------------------------------------------------------------- finance
export interface FinSummary {
  currency: string;
  totalApproved: number;
  totalPaid: number;
  outstanding: number;
  progressPct: number;
  claimCount: number;
  openQueries: number;
  asAt?: string;
}

export interface ClaimLine {
  publicId: string;
  sourceRef: string;
  claimTypeCode: string;
  claimTypeEn: string;
  claimTypeSw: string;
  description: string;
  currency: string;
  approvalDate: string;
  approvedAmount: number;
  paidAmount: number;
  adjustmentAmount: number;
  outstandingAmount: number;
  paymentStatus: 'APPROVED' | 'PARTLY_PAID' | 'FULLY_PAID' | 'UNDER_QUERY' | 'CANCELLED';
}

export interface ClaimDetail {
  claim: ClaimLine;
  voucherRef?: string;
  allocations: { id: number; paidOn: string; paymentRef?: string; paymentSourceRef: string; amount: number; reversal: boolean; publishedAt: string }[];
  adjustments: { sourceRef: string; amount: number; reason: string; publishedAt: string }[];
  queries: QuerySummary[];
}

export interface QuerySummary {
  publicId: string;
  claimRef: string;
  category: string;
  description: string;
  status: string;
  reopenCount: number;
  dueAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface QueryDetail {
  publicId: string;
  claimPublicId: string;
  claimRef: string;
  claimDescription: string;
  staffPublicId: string;
  staffName: string;
  category: string;
  description: string;
  status: string;
  reopenCount: number;
  dueAt?: string;
  createdAt: string;
  assignedTo?: string;
  messages: { authorName: string; fromFinance: boolean; body: string; createdAt: string }[];
  canRespond: boolean;
  canConfirm: boolean;
  canReopen: boolean;
}

export interface DeskItem {
  publicId: string;
  claimRef: string;
  staffName: string;
  unitName: string;
  category: string;
  status: string;
  reopenCount: number;
  dueAt?: string;
  createdAt: string;
  overdue: boolean;
}

export interface Batch {
  publicId: string;
  feedType: string;
  fileName: string;
  status: string;
  sourceRecordCount: number;
  sourceAmountTotal: number;
  loadedCount: number;
  quarantinedCount: number;
  openQuarantine: number;
  importedBy: string;
  reviewedBy?: string;
  reviewedAt?: string;
  publishedAt?: string;
  createdAt: string;
  canApprove: boolean;
}

export interface ImportResult {
  batchPublicId: string;
  duplicate: boolean;
  status: string;
  records: number;
  loaded: number;
  quarantined: number;
  rejectionReason?: string;
}

export interface QuarantineItem {
  id: number;
  rowNo: number;
  reasonCode: string;
  payload: string;
  status: string;
  resolutionNote?: string;
  resolvedBy?: string;
  resolvedAt?: string;
}

export interface PendingAllocation {
  id: number;
  paymentSourceRef: string;
  claimSourceRef: string;
  staffName: string;
  amount: number;
  allocatedBy: string;
  createdAt: string;
  canApprove: boolean;
}

export interface FinDashboard {
  batches: { status: string; count: number }[];
  openQuarantine: number;
  pendingManualAllocations: number;
  openQueriesByAge: { bucket: string; count: number }[];
  liabilities: { claimTypeCode: string; claimTypeEn: string; currency: string; claims: number; outstanding: number }[];
  totalOutstandingTzs: number;
  staffWithOutstanding: number;
  asAt: string;
}

// ---------------------------------------------------------------- security
export interface RoleInfo {
  code: string;
  name: string;
  privileged: boolean;
  requiresMfa: boolean;
}

export interface RoleAssignment {
  id: number;
  staffPublicId: string;
  staffName: string;
  employeeNo: string;
  roleCode: string;
  roleName: string;
  privileged: boolean;
  scopeUnitCode?: string;
  startsOn: string;
  endsOn?: string;
  approvedBy?: string;
  approvalRef?: string;
  createdBy: string;
  createdAt: string;
}

export interface AuditRow {
  chainSeq: number;
  occurredAt: string;
  actorSubject: string;
  actorStaffId?: number;
  actorName?: string;
  module: string;
  action: string;
  resourceType: string;
  resourceId: string;
  outcome: string;
  requestId?: string;
  details?: string;
}
