import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { DashboardApi } from './dashboard-api';
import { Live } from './live';
import { LiveStream } from './live-stream';
import { Command, Controls, KeyResultView, LiveRun, LiveState } from './model';

class FakeApi {
  sent: { kind: string; extra: unknown }[] = [];
  refuse: string | null = null;
  kr: KeyResultView = {
    experiment: 'sim',
    keyResult: 'k',
    orders: 1,
    complete: 0,
    percent: 0,
    incomplete: [],
    version: '1.0.0',
    receivers: [
      { name: 'kitchen', title: 'keuken', stub: true, lastArrived: true, arrived: 1 },
      { name: 'bar', title: 'bar', stub: true, lastArrived: false, arrived: 0 },
    ],
  };
  keyResult() {
    return of(this.kr);
  }
  command(kind: string, extra: unknown = {}) {
    this.sent.push({ kind, extra });
    return this.refuse ? throwError(() => ({ error: { detail: this.refuse } })) : of({} as Command);
  }
}

class FakeStream {
  experiment: string | null = null;
  onState: (s: LiveState) => void = () => undefined;
  onConnected: (c: boolean) => void = () => undefined;
  closed = false;

  connect(
    experiment: string | null,
    onState: (s: LiveState) => void,
    onConnected: (c: boolean) => void,
  ) {
    this.experiment = experiment;
    this.onState = onState;
    this.onConnected = onConnected;
    return () => (this.closed = true);
  }
}

const now = new Date('2026-11-26T09:15:00Z');

const running: LiveRun = {
  product: 'shop',
  subProduct: 'orders',
  title: 'Bestellingen',
  run: 'orders-r1',
  version: '0.5.0',
  environment: 'lokaal',
  triggerKind: 'human',
  triggerName: 'Pat',
  releaseType: 'planned',
  commit: 'a1b2c3d',
  started: '2026-11-26T09:13:30Z',
  outcome: 'running',
  deployed: false,
  items: [
    { kind: 'step', name: 'lint', title: 'Lint', status: 'succeeded', seconds: 4, explains: [] },
    {
      kind: 'step',
      name: 'unit-tests',
      title: 'Unittests',
      status: 'running',
      startedAt: '2026-11-26T09:14:47Z',
      explains: [],
      tests: {
        testType: 'unit',
        tests: 18,
        passed: 18,
        failed: 0,
        skipped: 0,
        expected: 31,
        finished: false,
      },
    },
    { kind: 'gate', name: 'code', title: 'Codegate', status: 'pending', explains: [] },
    { kind: 'step', name: 'build', title: 'Bouwen', status: 'waiting', explains: [] },
  ],
};

const failed: LiveRun = {
  ...running,
  subProduct: 'payments',
  title: 'Betalen',
  run: 'payments-r1',
  outcome: 'failure',
  seconds: 40,
  started: '2026-11-26T09:14:00Z',
  finished: '2026-11-26T09:14:00Z',
  stuckAt: 'code-gate',
  failure: {
    title: 'Unittests',
    reason: '1 van 31 tests mislukt',
    excerpt: 'OrderServiceTest.rejectsEmptyOrder: expected status 400 but was 500',
    failures: '1 van 31',
  },
  items: [
    { kind: 'step', name: 'lint', title: 'Lint', status: 'succeeded', explains: [] },
    {
      kind: 'step',
      name: 'unit-tests',
      title: 'Unittests',
      status: 'failed',
      explains: [],
      tests: { testType: 'unit', tests: 31, passed: 30, failed: 1, skipped: 0, finished: true },
    },
    {
      kind: 'gate',
      name: 'code',
      title: 'Codegate',
      status: 'closed',
      reason: 'unittests niet groen',
      explains: [],
    },
    { kind: 'step', name: 'build', title: 'Bouwen', status: 'not-run', explains: [] },
  ],
};

const waiting: LiveRun = {
  ...running,
  subProduct: 'menu',
  title: 'Menu',
  run: 'menu-r1',
  environment: 'werkplek',
  outcome: 'success',
  seconds: 60,
  started: '2026-11-26T09:13:00Z',
  finished: '2026-11-26T09:13:00Z',
  tag: undefined,
  waitingFor: { kind: 'human', name: 'Pat', action: 'merge op main met een hogere versie' },
  items: [
    {
      kind: 'gate',
      name: 'human',
      title: 'Mensgate',
      status: 'waiting',
      explains: [],
      waitingFor: { kind: 'human', name: 'Pat' },
    },
    { kind: 'step', name: 'release', title: 'Releasen', status: 'not-run', explains: [] },
  ],
};

function state(
  runs: LiveRun[],
  controls: Controls = { startButton: false, demoReset: false },
  commands: Command[] = [],
): LiveState {
  return {
    controls,
    commands,
    showcase: 'demo',
    experiment: 'sim',
    serverTime: now.toISOString(),
    settings: {
      configured: true,
      subProducts: [],
      environments: [],
      layout: 'stacked',
      keepFinishedSeconds: 600,
      presentation: { title: 'Demo live', theme: 'dark', fontScale: 2, focusRunning: true },
    },
    runs,
  };
}

describe('Live', () => {
  let fixture: ComponentFixture<Live>;
  let el: HTMLElement;
  let stream: FakeStream;
  let api: FakeApi;

  const flat = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

  function create(presentation = false) {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(now);
    stream = new FakeStream();
    api = new FakeApi();
    TestBed.configureTestingModule({
      imports: [Live],
      providers: [
        { provide: LiveStream, useValue: stream },
        { provide: DashboardApi, useValue: api },
      ],
    });
    fixture = TestBed.createComponent(Live);
    fixture.componentRef.setInput('experiment', 'sim');
    fixture.componentRef.setInput('presentation', presentation);
    fixture.detectChanges();
    el = fixture.nativeElement;
  }

  function push(runs: LiveRun[]) {
    stream.onState(state(runs));
    stream.onConnected(true);
    fixture.detectChanges();
  }

  afterEach(() => vi.useRealTimers());

  const demo: Controls = { startButton: true, demoReset: true, approver: 'Johannes' };

  it('shows the key result per stub: red for the bar', () => {
    create();
    push([]);
    const bar = el.querySelector('[data-receiver="bar"]');
    expect(flat(bar?.textContent)).toBe('✖ bar stub');
    expect(bar?.getAttribute('data-arrived')).toBe('false');
    expect(el.querySelector('.keyresult-bar')?.classList).toContain('bad');
    expect(flat(el.querySelector('.kr-label')?.textContent)).toBe('Key result (Bestellen 1.0.0)');
  });

  it('the start button sends start-run; not while a pipeline runs', () => {
    create();
    stream.onState(state([], demo));
    fixture.detectChanges();
    const start = el.querySelector<HTMLButtonElement>('[data-command="start-run"]')!;
    expect(flat(start.textContent)).toBe('▶ Pipeline starten (Johannes)');
    start.click();
    expect(api.sent).toEqual([{ kind: 'start-run', extra: {} }]);

    stream.onState(state([running], demo));
    fixture.detectChanges();
    expect(el.querySelector<HTMLButtonElement>('[data-command="start-run"]')!.disabled).toBe(true);
    expect(el.querySelector<HTMLButtonElement>('[data-command="demo-reset"]')!.disabled).toBe(true);
  });

  it('a waiting human gate has a button to approve the release of that run', () => {
    create();
    const waiting = { kind: 'human', name: 'Johannes', action: 'release goedkeuren' };
    stream.onState(state([{ ...running, waitingFor: waiting }], demo));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('[data-command="approve-release"]')!.click();
    expect(api.sent).toEqual([{ kind: 'approve-release', extra: { run: 'orders-r1' } }]);
  });

  it('Demo opnieuw asks first, and can keep the run and the report', () => {
    create();
    stream.onState(state([], demo));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('[data-command="demo-reset"]')!.click();
    fixture.detectChanges();
    expect(api.sent).toEqual([]);
    el.querySelector<HTMLButtonElement>('[data-command="demo-reset-confirm"]')!.click();
    expect(api.sent).toEqual([{ kind: 'demo-reset', extra: { save: true } }]);
  });

  it('shows what the Pipeline does with a command, and a refusal', () => {
    create();
    const c: Command = {
      id: '1',
      kind: 'start-run',
      requestedBy: 'Johannes',
      requestedAt: now.toISOString(),
      status: 'claimed',
    };
    stream.onState(state([], demo, [c]));
    fixture.detectChanges();
    expect(flat(el.querySelector('.command')?.textContent)).toBe(
      '⏳ Pipeline starten (Johannes): de Pipeline is bezig',
    );
    api.refuse = 'A pipeline is running.';
    stream.onState(state([], demo, [{ ...c, status: 'done', message: 'bestellen: gelukt' }]));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('[data-command="start-run"]')!.click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('A pipeline is running.');
  });

  it('a row per container, the backlog grey and a shortened step marked', () => {
    create();
    push([
      {
        ...running,
        containerRows: [
          {
            container: 'orders-frontend',
            title: 'frontend',
            version: '1.0.0',
            items: [
              {
                kind: 'step',
                name: 'lint',
                title: 'Statisch',
                status: 'succeeded',
                explains: [],
                container: 'orders-frontend',
              },
              { kind: 'step', name: 'coverage', title: 'Dekking', status: 'backlog', explains: [] },
            ],
          },
        ],
        items: [
          {
            kind: 'step',
            name: 'e2e',
            title: 'E2E',
            status: 'waiting',
            explains: [],
            shortened: { explain: 'EXP-9', reason: 'kernpad' },
          },
        ],
      },
    ]);
    const rows = el.querySelectorAll('.row');
    expect(rows.length).toBe(2);
    expect(flat(rows[0].querySelector('.row-title')?.textContent)).toBe('frontend 1.0.0');
    expect(rows[0].querySelector('[data-name="coverage"]')?.getAttribute('data-status')).toBe(
      'backlog',
    );
    expect(flat(rows[1].querySelector('.explain.shortened')?.textContent)).toBe('✂ EXP-9');
  });

  it('connects for the chosen experiment and says when it is live', () => {
    create();
    expect(stream.experiment).toBe('sim');
    expect(el.textContent).toContain('wordt geladen');
    push([]);
    expect(el.querySelector('.conn')?.textContent).toContain('live');
    expect(el.textContent).toContain('Nog geen runs in experiment sim');
    fixture.destroy();
    expect(stream.closed).toBe(true);
  });

  it('shows the steps and gates on a line, with the running time and the test counters', () => {
    create();
    push([running]);
    const stops = Array.from(el.querySelectorAll('.lane .stop'));
    expect(stops.map((s) => s.getAttribute('data-name'))).toEqual([
      'lint',
      'unit-tests',
      'code',
      'build',
    ]);
    expect(flat(stops[0].textContent)).toContain('gelukt 0:04');
    expect(flat(stops[1].textContent)).toContain('bezig 0:13');
    expect(stops[1].querySelector('.tests')?.textContent?.replace(/\s/g, '')).toBe('✔18✖0/31');
    expect(flat(stops[2].textContent)).toContain('nog niet beslist');
    expect(flat(stops[3].textContent)).toContain('wachtend');
    expect(flat(el.querySelector('.lane h3')?.textContent)).toContain('Bestellingen 0.5.0');
    expect(el.querySelector('.lane .time')?.textContent?.trim()).toBe('1:30');

    vi.advanceTimersByTime(2000);
    fixture.detectChanges();
    expect(flat(el.querySelectorAll('.lane .stop')[1].textContent)).toContain('bezig 0:15');
  });

  it('shows a failed step with a line of output, the closed gate, and the outcome with a link to the run', () => {
    create();
    push([failed]);
    const lane = el.querySelector('.lane')!;
    expect(lane.getAttribute('data-outcome')).toBe('failure');
    expect(flat(lane.querySelector('.failure')?.textContent)).toContain(
      'Unittests – 1 van 31 tests mislukt',
    );
    expect(lane.querySelector('.excerpt')?.textContent).toContain('expected status 400');
    expect(flat(lane.querySelector('.closed')?.textContent)).toBe(
      'Codegate dicht: unittests niet groen',
    );
    expect(flat(lane.querySelector('.outcome')?.textContent)).toContain('✖ Mislukt in 0:40');
    expect(lane.querySelector('.outcome a')?.getAttribute('href')).toBe(
      '/?experiment=sim&subproduct=shop%2Fpayments&run=payments-r1',
    );
    expect(lane.querySelector('[data-name="build"]')?.textContent).toContain('niet uitgevoerd');
  });

  it('shows that the pipeline waits for a human', () => {
    create();
    push([waiting]);
    expect(flat(el.querySelector('.waiting')?.textContent)).toBe(
      '⏸ Wacht op Pat – merge op main met een hogere versie',
    );
    expect(el.querySelector('[data-name="human"]')?.textContent).toContain('wacht');
  });

  it('makes a finished run small after the time from the configuration', () => {
    create();
    push([{ ...failed, started: '2026-11-26T08:00:00Z' }]);
    expect(el.querySelector('.lane')?.classList).toContain('small');
    expect(el.querySelector('.lane .line')).toBeNull();
    expect(el.querySelector('.lane .outcome')).not.toBeNull();
  });

  it('in presentation mode: title, theme and size from the configuration, running sub-products first, no links', () => {
    create(true);
    push([failed, waiting, running]);
    const live = el.querySelector('.live') as HTMLElement;
    expect(live.classList).toContain('presentation');
    expect(live.getAttribute('data-theme')).toBe('dark');
    expect(live.style.getPropertyValue('--scale')).toBe('2');
    expect(el.querySelector('.head h2')?.textContent).toContain('Demo live');
    expect(
      Array.from(el.querySelectorAll('.lane')).map((l) => l.getAttribute('data-subproduct')),
    ).toEqual(['orders', 'payments', 'menu']);
    expect(el.querySelector('.outcome a')).toBeNull();
  });
});
