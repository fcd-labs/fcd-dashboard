import { NgTemplateOutlet } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { LiveStore } from './live-store';
import { LiveStream } from './live-stream';
import { Command, CommandKind, LiveItem, LiveRun, LiveState, RunningVersion } from './model';

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
  private readonly store = inject(LiveStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly experiment = input<string | null>(null);
  readonly presentation = input(false);

  protected readonly state = signal<LiveState | null>(null);
  protected readonly connected = signal(false);
  /** Why the Dashboard refused the last button (shared with the buttons in the header). */
  protected readonly refused = this.store.refused;
  /** The messages can scroll further up or down (shown with the arrows). */
  protected readonly canUp = signal(false);
  protected readonly canDown = signal(false);
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');
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
        this.store.state.set(s);
        // After the new messages are drawn: is there more to scroll to?
        setTimeout(() => {
          const l = this.list()?.nativeElement;
          if (l) this.updateScroll(l);
        });
      },
      (c) => this.connected.set(c),
    );
    const timer = setInterval(() => this.now.set(Date.now() + this.offset), 1000);
    this.destroyRef.onDestroy(() => {
      close();
      clearInterval(timer);
    });
  }

  /** The row of the sub-product begins when every container is built (the last step of the container rows). */
  protected joinStatus(r: LiveRun): string {
    const built = (r.containerRows ?? []).map((c) =>
      [...c.items].reverse().find((i) => i.kind === 'step' && i.status !== 'backlog'),
    );
    const st = built.map((i) => i?.status ?? 'waiting');
    if (st.some((x) => x === 'failed')) return 'failed';
    if (st.length && st.every((x) => x === 'succeeded' || x === 'explained')) return 'succeeded';
    if (st.some((x) => x === 'running' || x === 'succeeded')) return 'running';
    return r.outcome === 'running' ? 'waiting' : 'not-run';
  }

  protected joinLabel(r: LiveRun): string {
    return (r.containerRows ?? []).map((c) => c.title).join(' + ') + ' gebouwd';
  }

  protected joinText(r: LiveRun): string {
    return (
      {
        succeeded: 'klaar',
        failed: 'mislukt',
        running: 'bezig',
        waiting: 'wacht',
        'not-run': 'niet bereikt',
      }[this.joinStatus(r)] ?? ''
    );
  }

  /** The messages and errors in their one fixed place. */
  protected messageCount(): number {
    let n =
      (this.refused() ? 1 : 0) +
      (this.busy() ? 1 : 0) +
      (!this.busy() && this.lastCommand() ? 1 : 0);
    for (const r of this.runs()) {
      n +=
        (r.waitingFor ? 1 : 0) + (r.failure ? 1 : 0) + (!r.failure && this.gateClosed(r) ? 1 : 0);
      n += r.outcome !== 'running' ? 1 : 0;
    }
    return n;
  }

  /** "demo-20261121-131229" -> "demo 21-11 13:12". */
  protected demoLabel(e: string | undefined): string {
    const m = /^([a-z]+)-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})/.exec(e ?? '');
    return m ? `${m[1]} ${m[4]}-${m[3]} ${m[5]}:${m[6]}` : (e ?? '');
  }

  /** The bottom line is about the product of the showcase: "ShowKitchen · Bestellen 1.0.1". */
  protected productVersion(s: LiveState): string {
    if (!s.running.length) return '';
    const product = s.running[0].productTitle || s.running[0].product || '';
    return product + ' · ' + s.running.map((v) => `${v.title} ${v.version}`).join(' · ');
  }

  /** The stubs, short: "stubs 1.0.0: keuken · bar · kassa" (grouped by version). */
  protected stubText(v: RunningVersion): string {
    const byVersion = new Map<string, string[]>();
    for (const st of v.stubs ?? []) {
      const key = st.version ?? '?';
      byVersion.set(key, [...(byVersion.get(key) ?? []), st.title]);
    }
    return [...byVersion.entries()]
      .map(([ver, names]) => `stubs ${ver}: ${names.join(' · ')}`)
      .join(' – ');
  }

  /** The containers of the version that runs, short: "frontend 1.0.0 · backend 1.0.1". */
  protected parts(v: RunningVersion): string {
    return Object.entries(v.containers ?? {})
      .map(([name, version]) => `${name.replace(v.subProduct + '-', '')} ${version}`)
      .join(' · ');
  }

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
    this.store.send(kind, extra);
  }

  /** The arrows of the messages: is there more above or below? */
  protected updateScroll(list: HTMLElement): void {
    this.canUp.set(list.scrollTop > 0);
    this.canDown.set(list.scrollTop + list.clientHeight < list.scrollHeight - 1);
  }

  protected scrollMessages(list: HTMLElement, direction: 1 | -1): void {
    list.scrollBy({ top: direction * list.clientHeight * 0.8, behavior: 'smooth' });
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
    // One sub-product: its run stays as it is, so nothing on the screen moves.
    if (this.runs().length <= 1) return true;
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
