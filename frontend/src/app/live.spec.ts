import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Live } from './live';
import { LiveStream } from './live-stream';
import { LiveRun, LiveState } from './model';

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

function state(runs: LiveRun[]): LiveState {
  return {
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

  const flat = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

  function create(presentation = false) {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(now);
    stream = new FakeStream();
    TestBed.configureTestingModule({
      imports: [Live],
      providers: [{ provide: LiveStream, useValue: stream }],
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
