import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { DashboardApi } from './dashboard-api';
import { DemoControls } from './demo-controls';
import { Live } from './live';
import {
  Changelog,
  KeyResultView,
  KpiView,
  LoglineRow,
  Overview,
  ProductView,
  SubProductView,
  TestView,
} from './model';

export type Tab =
  | 'home'
  | 'live'
  | 'status'
  | 'kpis'
  | 'keyresult'
  | 'changelog'
  | 'tests'
  | 'loglines'
  | 'report';

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
  waiting: 'wacht',
};

export const gateText: Record<string, string> = {
  code: 'Codegate',
  build: 'Bouwgate',
  security: 'Securitygate',
  deploy: 'Uitrolgate',
  human: 'Goedkeuring',
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
  imports: [DatePipe, DecimalPipe, Live, DemoControls],
  host: { '(document:keydown)': 'onKey($event)' },
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly api = inject(DashboardApi);

  private readonly params = new URLSearchParams(globalThis.location?.search ?? '');
  /** From the address: ?tab=live, or ?subproduct=... (a link from the live view) opens that sub-product. */
  /** Home is the live view; ?tab=<name> opens another tab, a link to a run (?subproduct=...) opens the overview. */
  protected readonly tab = signal<Tab>(this.startTab());

  private startTab(): Tab {
    const tabs: Tab[] = [
      'home',
      'live',
      'status',
      'kpis',
      'keyresult',
      'changelog',
      'tests',
      'loglines',
      'report',
    ];
    const asked = this.params.get('tab') as Tab | null;
    if (asked && tabs.includes(asked)) return asked;
    if (this.params.has('subproduct')) return 'status';
    if (this.params.has('presentatie')) return 'live';
    return 'home';
  }
  /** From the address (?experiment=...), else the Dashboard chooses the current experiment. */
  protected readonly experiment = signal<string | null>(this.params.get('experiment'));
  /** Presentation mode for a large screen (?presentatie, or the key P on the live view). */
  protected readonly presentation = signal(this.params.has('presentatie'));
  protected readonly overview = signal<Overview | null>(null);
  protected readonly tests = signal<TestView[]>([]);
  protected readonly loglines = signal<LoglineRow[]>([]);
  protected readonly kpis = signal<KpiView | null>(null);
  protected readonly keyResult = signal<KeyResultView | null>(null);
  protected readonly report = signal<string>('');
  protected readonly changelog = signal<Changelog[]>([]);
  protected readonly error = signal(false);
  /** Selected sub-product: "product/name". */
  protected readonly selected = signal<string | null>(this.params.get('subproduct'));

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

  /** P: presentation mode on or off (on the live view); Escape: off. */
  protected onKey(e: KeyboardEvent): void {
    const target = e.target as HTMLElement | null;
    if (target?.closest?.('input, select, textarea') || e.ctrlKey || e.metaKey || e.altKey) return;
    if ((e.key === 'p' || e.key === 'P') && this.tab() === 'live')
      this.presentation.update((v) => !v);
    if (e.key === 'Escape') this.presentation.set(false);
  }

  refresh(): void {
    // The live view gets its data pushed; the other tabs are not visible in presentation mode.
    if (this.presentation()) return;
    const e = this.experiment();
    forkJoin({
      overview: this.api.overview(e),
      tests: this.api.tests(e),
      loglines: this.api.loglines(e),
      kpis: this.api.kpis(e),
      keyResult: this.api.keyResult(e),
      report: this.api.report(e),
      changelog: this.api.changelog(e),
    }).subscribe({
      next: (r) => {
        this.overview.set(r.overview);
        this.tests.set(r.tests);
        this.loglines.set(r.loglines);
        this.kpis.set(r.kpis);
        this.keyResult.set(r.keyResult);
        this.report.set(r.report);
        this.changelog.set(r.changelog);
        this.error.set(false);
      },
      error: () => this.error.set(true),
    });
  }

  /** The start page: every part of the Dashboard, what it shows. */
  protected readonly homeItems: { tab: Tab; title: string; text: string }[] = [
    {
      tab: 'live',
      title: 'Live',
      text: 'De pipeline live, stap voor stap: per container en voor het deelproduct, met de quality gates en de meldingen. Hier start je de pipeline en de demo (Bediening).',
    },
    {
      tab: 'status',
      title: 'Pipelines en gates',
      text: 'Per product en deelproduct de laatste run, de quality gates met hun reden en de goedgekeurde afwijkingen.',
    },
    {
      tab: 'kpis',
      title: "KPI's",
      text: 'De DORA-metrics: hoe vaak er wordt uitgerold, hoe lang een wijziging erover doet en hoe vaak een gate dichtvalt.',
    },
    {
      tab: 'keyresult',
      title: 'Key result',
      text: 'Haalt het product zijn doel? Het key result van de intent, per versie en per ontvanger.',
    },
    {
      tab: 'changelog',
      title: 'Changelog',
      text: 'Wat er per versie veranderde, tot en met de versie die nu draait.',
    },
    {
      tab: 'tests',
      title: 'Testresultaten',
      text: 'Unit-, integratie- en end-to-end tests per deelproduct, met de mislukte tests.',
    },
    {
      tab: 'loglines',
      title: 'Logregels',
      text: 'Elke stap en gate als logregel: het materiaal waar dit Dashboard uit bestaat.',
    },
    {
      tab: 'report',
      title: 'Statusrapport',
      text: 'Alles samen als rapport om te lezen, te bewaren of te delen.',
    },
  ];

  /** The last order did not arrive at every receiver. */
  protected lastIncomplete(kr: KeyResultView): boolean {
    return (kr.receivers ?? []).some((r) => !r.lastArrived);
  }

  /** "demo-20261121-131229" -> "demo 21-11 13:12"; other names as they are. */
  protected experimentLabel(e: string): string {
    const m = /^([a-z]+)-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})/.exec(e);
    return m ? `Demoronde ${m[4]}-${m[3]} ${m[5]}:${m[6]}` : e;
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

  protected containersOf(
    map: Record<string, string> | undefined,
  ): { name: string; version: string }[] {
    return Object.entries(map ?? {}).map(([name, version]) => ({ name, version }));
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

  protected reportUrl(format: 'markdown' | 'html' = 'markdown'): string {
    return this.api.reportDownloadUrl(this.experiment(), format);
  }

  protected print(): void {
    globalThis.print?.();
  }
}
