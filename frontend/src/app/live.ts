import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { LiveStream } from './live-stream';
import { LiveItem, LiveRun, LiveState } from './model';

const stepText: Record<string, string> = {
  waiting: 'wachtend',
  running: 'bezig',
  succeeded: 'gelukt',
  failed: 'mislukt',
  skipped: 'overgeslagen',
  'not-run': 'niet uitgevoerd',
};

const gateStatusText: Record<string, string> = {
  pending: 'nog niet beslist',
  deciding: 'wordt beslist',
  waiting: 'wacht',
  open: 'open',
  closed: 'dicht',
  'not-run': 'niet bereikt',
};

const marker: Record<string, string> = {
  waiting: '○',
  running: '◐',
  succeeded: '✔',
  failed: '✖',
  skipped: '⤼',
  'not-run': '–',
  pending: '◇',
  deciding: '◈',
  open: '◆',
  closed: '✖',
};

/** Live: per sub-product the current run, step by step and gate by gate, pushed by the Dashboard (no refresh). */
@Component({
  selector: 'app-live',
  templateUrl: './live.html',
  styleUrl: './live.css',
})
export class Live implements OnInit {
  private readonly stream = inject(LiveStream);
  private readonly destroyRef = inject(DestroyRef);

  readonly experiment = input<string | null>(null);
  readonly presentation = input(false);

  protected readonly state = signal<LiveState | null>(null);
  protected readonly connected = signal(false);
  /** The time of the Dashboard, ticking every second (for the running time). */
  protected readonly now = signal(Date.now());
  private offset = 0;

  protected readonly runs = computed<LiveRun[]>(() => {
    const s = this.state();
    if (!s) return [];
    if (!this.presentation() || !s.settings.presentation.focusRunning) return s.runs;
    // The running sub-products first; the others keep their order.
    return [...s.runs].sort(
      (a, b) => Number(b.outcome === 'running') - Number(a.outcome === 'running'),
    );
  });

  protected readonly title = computed(() => {
    const s = this.state();
    return (
      (this.presentation() ? s?.settings.presentation.title : undefined) ??
      `Live – showcase ${s?.showcase ?? '…'}`
    );
  });

  ngOnInit(): void {
    const close = this.stream.connect(
      this.experiment(),
      (s) => {
        this.offset = Date.parse(s.serverTime) - Date.now();
        this.now.set(Date.now() + this.offset);
        this.state.set(s);
      },
      (c) => this.connected.set(c),
    );
    const timer = setInterval(() => this.now.set(Date.now() + this.offset), 1000);
    this.destroyRef.onDestroy(() => {
      close();
      clearInterval(timer);
    });
  }

  protected marker(item: LiveItem): string {
    return marker[item.status] ?? '○';
  }

  protected statusText(item: LiveItem): string {
    if (item.kind === 'gate' && item.status === 'open' && item.explains.length)
      return 'open met afwijking';
    return (item.kind === 'gate' ? gateStatusText : stepText)[item.status] ?? item.status;
  }

  /** "mm:ss" since the start of a running step or run. */
  protected elapsed(from: string | undefined): string {
    if (!from) return '';
    return this.clock(Math.max(0, (this.now() - Date.parse(from)) / 1000));
  }

  protected clock(seconds: number | undefined): string {
    if (seconds === undefined || seconds === null) return '';
    const s = Math.round(seconds);
    const m = Math.floor(s / 60);
    return `${m}:${String(s % 60).padStart(2, '0')}`;
  }

  protected runTime(run: LiveRun): string {
    return run.outcome === 'running' ? this.elapsed(run.started) : this.clock(run.seconds);
  }

  /** A finished run stays large for keepFinishedSeconds (configuration), then it becomes a short line. */
  protected recent(run: LiveRun): boolean {
    if (run.outcome === 'running' || run.seconds === undefined) return true;
    const keep = (this.state()?.settings.keepFinishedSeconds ?? 600) * 1000;
    return this.now() - (Date.parse(run.started) + run.seconds * 1000) < keep;
  }

  protected gateClosed(run: LiveRun): LiveItem | undefined {
    return run.items.find((i) => i.kind === 'gate' && i.status === 'closed');
  }

  /** To the sub-product in the overview, where the run is. */
  protected runLink(run: LiveRun): string {
    const p = new URLSearchParams();
    const e = this.state()?.experiment;
    if (e) p.set('experiment', e);
    p.set('subproduct', `${run.product}/${run.subProduct}`);
    p.set('run', run.run);
    return `/?${p.toString()}`;
  }

  protected trigger(kind: string): string {
    return (
      { human: 'mens', 'ai-agent': 'AI-agent', 'automated-update': 'automatische update' }[kind] ??
      kind
    );
  }
}
