// Types of the Dashboard API (contracts/dashboard-api.openapi.yaml).

export type Status = 'green' | 'red' | 'running' | 'unknown';

export interface StepView {
  step: number;
  name: string;
  title: string;
  outcome: 'running' | 'succeeded' | 'failed' | 'skipped';
  reason?: string;
  seconds?: number;
}

export interface GateView {
  gate: string;
  afterSteps: string;
  outcome: 'open' | 'closed';
  reason?: string;
  explains: string[];
}

export interface TestView {
  subProduct: string;
  testType: string;
  tests: number;
  passed: number;
  failed: number;
  skipped: number;
  seconds: number;
  failures?: string;
  run: string;
  at: string;
}

export interface TestTotals {
  testType: string;
  tests: number;
  passed: number;
  failed: number;
  skipped: number;
  seconds: number;
}

export interface RunView {
  run: string;
  product: string;
  subProduct: string;
  version: string;
  environment: string;
  triggerKind: string;
  triggerName: string;
  releaseType: string;
  commit: string;
  started: string;
  finished?: string;
  outcome: 'running' | 'success' | 'failure';
  seconds?: number;
  stuckAt?: string;
  warnings?: string;
  steps: StepView[];
  gates: GateView[];
  tests: TestView[];
}

export interface RunSummary {
  run: string;
  version: string;
  environment: string;
  started: string;
  outcome: string;
  stuckAt?: string;
  triggerName: string;
}

export interface SubProductView {
  product: string;
  name: string;
  status: Status;
  lastRun?: RunView;
  deployed?: {
    version: string;
    containers: Record<string, string>;
    environment: string;
    at: string;
  };
  releases: { tag: string; releaseType: string; at: string }[];
  explains: {
    id: string;
    gate: string;
    rule?: string;
    reason?: string;
    approver?: string;
    until?: string;
    lastUsed: string;
  }[];
  runs: RunSummary[];
}

export interface ProductView {
  name: string;
  status: Status;
  subProducts: SubProductView[];
  tests: TestTotals[];
}

export interface Overview {
  showcase: string;
  experiment: string;
  experiments: string[];
  generatedAt: string;
  products: ProductView[];
}

export interface LoglineRow {
  id: string;
  time: string;
  seconds: number;
  triggerKind: string;
  triggerName: string;
  product: string;
  subProduct: string;
  version: string;
  environment: string;
  type: string;
  action: string;
  outcome: string;
  reason?: string;
  run: string;
}

export interface DoraView {
  product: string;
  subProduct: string;
  deployments: number;
  deploymentsPerDay: number;
  leadTimeMedianMinutes?: number;
  failedChanges: number;
  changeFailureRate?: number;
  restoreMedianMinutes?: number;
  restores: { failedVersion: string; patchVersion: string; minutes: number; at: string }[];
  pipelineSecondsAverage?: number;
}

export interface KpiView {
  experiment: string;
  products: { name: string; dora: DoraView; subProducts: DoraView[] }[];
  gates: {
    gate: string;
    evaluated: number;
    closed: number;
    reasons: { reason: string; count: number }[];
  }[];
  trend: {
    day: string;
    deployments: number;
    leadTimeMedianMinutes?: number;
    runs: number;
    runsFailed: number;
    gatesClosed: number;
    pipelineSecondsAverage?: number;
    keyResultPercent?: number;
  }[];
  activeExplains: number;
  keyResultPercent?: number;
}

export interface KeyResultView {
  experiment: string;
  keyResult: string;
  orders: number;
  complete: number;
  percent?: number;
  incomplete: { orderId: string; tableNumber: number; at: string; missing: string[] }[];
  version?: string;
  receivers?: {
    name: string;
    title: string;
    stub: boolean;
    lastArrived: boolean;
    arrived: number;
  }[];
  versions?: { version: string; orders: number; complete: number; percent?: number }[];
}

// The live view (GET /api/v1/live/state, and as Server-Sent Events on GET /api/v1/live).
export interface WaitingFor {
  kind: string;
  name: string;
  action?: string;
}

export interface LiveTests {
  testType: string;
  tests: number;
  passed: number;
  failed: number;
  skipped: number;
  expected?: number;
  finished: boolean;
}

export type LiveStatus =
  | 'waiting'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'explained'
  | 'skipped'
  | 'not-run'
  | 'backlog'
  | 'pending'
  | 'deciding'
  | 'open'
  | 'closed';

export interface LiveItem {
  kind: 'step' | 'gate';
  name: string;
  title: string;
  status: LiveStatus;
  step?: number;
  startedAt?: string;
  seconds?: number;
  reason?: string;
  excerpt?: string;
  explains: string[];
  container?: string;
  shortened?: { explain: string; reason?: string; until?: string };
  waitingFor?: WaitingFor;
  tests?: LiveTests;
}

export interface LiveContainerRow {
  container: string;
  title: string;
  version?: string;
  items: LiveItem[];
}

export interface LiveRun {
  product: string;
  subProduct: string;
  title: string;
  run: string;
  version: string;
  environment: string;
  triggerKind: string;
  triggerName: string;
  releaseType: string;
  commit: string;
  started: string;
  finished?: string;
  outcome: 'running' | 'success' | 'failure';
  seconds?: number;
  stuckAt?: string;
  waitingFor?: WaitingFor;
  failure?: { title: string; reason?: string; excerpt?: string; failures?: string };
  tag?: string;
  deployed: boolean;
  items: LiveItem[];
  containers?: Record<string, string>;
  containerRows?: LiveContainerRow[];
}

export interface LiveSettings {
  configured: boolean;
  subProducts: string[];
  environments: string[];
  layout: 'stacked' | 'side-by-side';
  keepFinishedSeconds: number;
  presentation: {
    title?: string;
    theme: 'dark' | 'light';
    fontScale: number;
    focusRunning: boolean;
  };
}

export interface LiveState {
  showcase: string;
  experiment: string;
  serverTime: string;
  settings: LiveSettings;
  runs: LiveRun[];
  controls: Controls;
  commands: Command[];
  demo?: { phase: 'start' | 'merged'; unmerged: string[] };
  running: RunningVersion[];
}

export interface RunningVersion {
  product?: string;
  productTitle?: string;
  subProduct: string;
  title: string;
  version: string;
  containers?: Record<string, string>;
  deployedAt?: string;
  stubs?: { name: string; title: string; receiver: string; version?: string }[];
}

export interface Controls {
  startButton: boolean;
  demoReset: boolean;
  approver?: string;
}

export type CommandKind = 'start-run' | 'approve-release' | 'demo-reset' | 'demo-merge';

export interface Command {
  id: string;
  kind: CommandKind;
  run?: string;
  save?: boolean;
  requestedBy: string;
  requestedAt: string;
  status: 'pending' | 'claimed' | 'done' | 'failed' | 'rejected';
  message?: string;
}

export interface Changelog {
  product: string;
  subProduct: string;
  title?: string;
  version: string;
  containers?: Record<string, string>;
  deployedAt?: string;
  text: string;
}
