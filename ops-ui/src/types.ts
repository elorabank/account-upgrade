// Shapes of the JSON the services return (see the *View records in each service).

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Me {
  name: string;
  email: string | null;
  roles: string[];
}

export interface FileView {
  id: number;
  fileName: string;
  businessDate: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  totalRows: number | null;
  loadedRows: number | null;
  rejectedRows: number | null;
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
}

export interface FileError {
  rowNo: number;
  errorMessage: string;
  rawRow: string | null;
}

export interface UpgradeView {
  id: number;
  accountNo: string;
  customerName: string;
  currentType: string;
  targetType: string | null;
  status: string;
  attemptCount: number;
  reason: string | null;
  businessDate: string;
  sourceFileId: number | null;
  updatedAt: string;
}

export interface UpgradeSummary {
  businessDate: string | null;
  byStatus: Record<string, number>;
  total: number;
}

export interface LetterView {
  id: number;
  upgradeRequestId: number;
  accountNo: string;
  recipient: string;
  subject: string;
  status: 'PENDING' | 'SENT' | 'FAILED';
  attempts: number;
  errorMessage: string | null;
  generatedAt: string;
  sentAt: string | null;
  nextAttemptAt: string | null;
}

export interface LetterSummary {
  since: string;
  byStatus: Record<string, number>;
  total: number;
}

export interface AuditEventView {
  eventId: string;
  topic: string;
  eventType: string;
  sourceService: string | null;
  accountNo: string | null;
  actor: string;
  correlationId: string | null;
  summary: string;
  occurredAt: string;
  recordedAt: string;
}

export interface JobStart {
  started: boolean;
  correlationId: string | null;
  message: string;
}
