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
