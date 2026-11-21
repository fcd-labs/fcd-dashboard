import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { DashboardApi } from './dashboard-api';
import { LiveStream } from './live-stream';
import { Command, CommandKind, KeyResultView, LiveItem, LiveRun, LiveState } from './model';

const stepText: Record<string, string> = {
  waiting: 'wachtend',
  running: 'bezig',
  succeeded: 'gelukt',
  failed: 'mislukt',
  explained: 'met afwijking',
  skipped: 'overgeslagen',
  'not-run': 'niet uitgevoerd',
  backlog: 'backlog',
};

const commandText: Record<CommandKind, string> = {
  'start-run': 'Pipeline starten',
  'approve-release': 'Release goedkeuren',
  'demo-reset': 'Demo opnieuw',
  'demo-merge': 'Patch mergen',
};

const commandStatusText: Record<Command['status'], string> = {
  pending: 'wacht op de Pipeline',
  claimed: 'de Pipeline is bezig',
  done: 'gedaan',
  failed: 'mislukt',
  rejected: 'geweigerd',
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
  explained: '!',
  skipped: '⤼',
  'not-run': '–',
  backlog: '·',
  pending: '◇',
  deciding: '◈',
  open: '◆',
  closed: '✖',
};

const gateMarker: Record<string, string> = {
  pending: '',
  deciding: '…',
  waiting: '⏸',
  open: '✔',
  closed: '✖',
  'not-run': '–',
};

/** Live: per sub-product the current run, step by step and gate by gate, pushed by the Dashboard (no refresh). */
@Component({
  selector: 'app-live',
  templateUrl: './live.html',
  styleUrl: './live.css',
  imports: [NgTemplateOutlet],
})
export class Live implements OnInit {
  private readonly stream = inject(LiveStream);
  private readonly api = inject(DashboardApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly experiment = input<string | null>(null);
  readonly presentation = input(false);

  protected readonly state = signal<LiveState | null>(null);
  protected readonly connected = signal(false);
  protected readonly keyResult = signal<KeyResultView | null>(null);
  /** The confirmation of "Demo opnieuw" is open. */
  protected readonly confirmReset = signal(false);
  protected readonly saveFirst = signal(true);
  protected readonly refused = signal<string | null>(null);
  private keyResultBusy = false;
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
        this.loadKeyResult();
      },
      (c) => this.connected.set(c),
    );
    const timer = setInterval(() => this.now.set(Date.now() + this.offset), 1000);
    this.destroyRef.onDestroy(() => {
      close();
      clearInterval(timer);
    });
  }

  private loadKeyResult(): void {
    if (this.keyResultBusy) return;
    this.keyResultBusy = true;
    this.api.keyResult(this.experiment()).subscribe({
      next: (k) => {
        this.keyResult.set(k);
        this.keyResultBusy = false;
      },
      error: () => (this.keyResultBusy = false),
    });
  }

  protected containerList(
    map: Record<string, string> | undefined,
  ): { name: string; version: string }[] {
    return Object.entries(map ?? {}).map(([name, version]) => ({ name, version }));
  }

  /** Red when the last order did not arrive everywhere (the key result now, not the history). */
  protected lastIncomplete(kr: KeyResultView): boolean {
    return (kr.receivers ?? []).some((r) => !r.lastArrived);
  }

  /** A command that is still with the Pipeline (pending or claimed). */
  /** Approving a release is possible while the Pipeline runs (the start command is still open then). */
  protected approvable(run: LiveRun): boolean {
    return !this.state()?.commands.some(
      (c) =>
        c.kind === 'approve-release' &&
        c.run === run.run &&
        (c.status === 'pending' || c.status === 'claimed'),
    );
  }

  protected readonly busy = computed<Command | undefined>(() =>
    this.state()?.commands.find((c) => c.status === 'pending' || c.status === 'claimed'),
  );

  protected readonly lastCommand = computed<Command | undefined>(() => this.state()?.commands[0]);

  protected readonly running = computed(() =>
    (this.state()?.runs ?? []).some((r) => r.outcome === 'running' && r.environment === 'lokaal'),
  );

  protected commandText(c: Command): string {
    return `${commandText[c.kind]} (${c.requestedBy}): ${commandStatusText[c.status]}${c.message ? ' – ' + c.message : ''}`;
  }

  protected send(kind: CommandKind, extra: { run?: string; save?: boolean } = {}): void {
    this.refused.set(null);
    this.confirmReset.set(false);
    this.api.command(kind, extra).subscribe({
      error: (e) => this.refused.set(e?.error?.detail ?? 'De knop werkt nu niet.'),
    });
  }

  protected marker(item: LiveItem): string {
    return (item.kind === 'gate' ? gateMarker : marker)[item.status] ?? '○';
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
