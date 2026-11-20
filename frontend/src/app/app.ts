import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { DashboardApi } from './dashboard-api';
import {
  KeyResultView,
  KpiView,
  LoglineRow,
  Overview,
  ProductView,
  SubProductView,
  TestView,
} from './model';

export type Tab = 'status' | 'kpis' | 'keyresult' | 'tests' | 'loglines' | 'report';

const REFRESH_MS = 5000;

export const statusText: Record<string, string> = {
  green: 'groen',
  red: 'rood',
  running: 'bezig',
  unknown: 'onbekend',
  succeeded: 'gelukt',
  failed: 'mislukt',
  skipped: 'overgeslagen',
  open: 'open',
  closed: 'dicht',
  success: 'gelukt',
  failure: 'mislukt',
  pass: 'gelukt',
  fail: 'mislukt',
  complete: 'volledig',
  incomplete: 'niet volledig',
};

export const gateText: Record<string, string> = {
  code: 'Codegate',
  build: 'Bouwgate',
  security: 'Securitygate',
  deploy: 'Uitrolgate',
  human: 'Mensgate',
};

export const testTypeText: Record<string, string> = {
  unit: 'Unit',
  contract: 'Contract',
  integration: 'Integratie',
  'end-to-end': 'End-to-end',
};

export const triggerText: Record<string, string> = {
  human: 'mens',
  'ai-agent': 'AI-agent',
  'automated-update': 'automatische update',
  guest: 'gast',
};

@Component({
  selector: 'app-root',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly api = inject(DashboardApi);

  protected readonly tab = signal<Tab>('status');
  /** From the address (?experiment=...), else the Dashboard chooses the current experiment. */
  protected readonly experiment = signal<string | null>(
    new URLSearchParams(globalThis.location?.search ?? '').get('experiment'),
  );
  protected readonly overview = signal<Overview | null>(null);
  protected readonly tests = signal<TestView[]>([]);
  protected readonly loglines = signal<LoglineRow[]>([]);
  protected readonly kpis = signal<KpiView | null>(null);
  protected readonly keyResult = signal<KeyResultView | null>(null);
  protected readonly report = signal<string>('');
  protected readonly error = signal(false);
  /** Selected sub-product: "product/name". */
  protected readonly selected = signal<string | null>(null);

  protected readonly statusText = statusText;
  protected readonly gateText = gateText;
  protected readonly testTypeText = testTypeText;
  protected readonly triggerText = triggerText;

  protected readonly selectedSubProduct = computed<SubProductView | null>(() => {
    const key = this.selected();
    if (!key) return null;
    for (const p of this.overview()?.products ?? []) {
      for (const s of p.subProducts) if (`${p.name}/${s.name}` === key) return s;
    }
    return null;
  });

  protected readonly failedTests = computed(() => this.tests().filter((t) => t.failed > 0));

  constructor() {
    this.refresh();
    const timer = setInterval(() => this.refresh(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  refresh(): void {
    const e = this.experiment();
    forkJoin({
      overview: this.api.overview(e),
      tests: this.api.tests(e),
      loglines: this.api.loglines(e),
      kpis: this.api.kpis(e),
      keyResult: this.api.keyResult(e),
      report: this.api.report(e),
    }).subscribe({
      next: (r) => {
        this.overview.set(r.overview);
        this.tests.set(r.tests);
        this.loglines.set(r.loglines);
        this.kpis.set(r.kpis);
        this.keyResult.set(r.keyResult);
        this.report.set(r.report);
        this.error.set(false);
      },
      error: () => this.error.set(true),
    });
  }

  protected chooseExperiment(value: string): void {
    this.experiment.set(value);
    this.selected.set(null);
    this.refresh();
  }

  protected select(product: ProductView, sub: SubProductView): void {
    this.selected.set(`${product.name}/${sub.name}`);
  }

  protected stuck(product: ProductView): string[] {
    return product.subProducts
      .filter((s) => s.status === 'red' && s.lastRun?.stuckAt)
      .map((s) => `${s.name}: ${this.stuckText(s.lastRun!.stuckAt!)}`);
  }

  protected stuckText(stuckAt: string): string {
    const gate = stuckAt.replace(/-gate$/, '');
    return gateText[gate] ? `${gateText[gate]} dicht` : stuckAt;
  }

  protected testsOf(subProduct: string): TestView[] {
    return this.tests().filter((t) => t.subProduct === subProduct);
  }

  protected containers(sub: SubProductView): { name: string; version: string }[] {
    return Object.entries(sub.deployed?.containers ?? {}).map(([name, version]) => ({
      name,
      version,
    }));
  }

  protected percent(v: number | undefined | null): string {
    return v === undefined || v === null ? '–' : `${Math.round(v * 10) / 10}%`;
  }

  protected rate(v: number | undefined | null): string {
    return v === undefined || v === null ? '–' : `${Math.round(v * 1000) / 10}%`;
  }

  protected minutes(v: number | undefined | null): string {
    if (v === undefined || v === null) return '–';
    return v >= 120 ? `${Math.round((v / 60) * 10) / 10} u` : `${v} min`;
  }

  /** Width of a bar in the trend (0-100) relative to the largest value in that column. */
  protected bar(value: number | undefined, column: (number | undefined)[]): number {
    const max = Math.max(1, ...column.map((c) => c ?? 0));
    return Math.round(((value ?? 0) / max) * 100);
  }

  protected column<K extends keyof KpiView['trend'][number]>(key: K): (number | undefined)[] {
    return (this.kpis()?.trend ?? []).map((t) => t[key] as number | undefined);
  }

  protected reportUrl(): string {
    return this.api.reportDownloadUrl(this.experiment());
  }

  protected print(): void {
    globalThis.print?.();
  }
}
