import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { KeyResultView, KpiView, LoglineRow, Overview, TestView } from './model';

const tests: TestView[] = [
  {
    subProduct: 'bestellen',
    testType: 'unit',
    tests: 45,
    passed: 44,
    failed: 1,
    skipped: 0,
    seconds: 5.9,
    failures: 'rejects a null line',
    run: 'r2',
    at: '2026-11-20T17:00:00Z',
  },
];

const overview: Overview = {
  showcase: 'demo',
  experiment: 'mvp0',
  experiments: ['mvp0'],
  generatedAt: '2026-11-20T17:00:00Z',
  products: [
    {
      name: 'showkitchen',
      status: 'red',
      tests: [{ testType: 'unit', tests: 45, passed: 44, failed: 1, skipped: 0, seconds: 5.9 }],
      subProducts: [
        {
          product: 'showkitchen',
          name: 'bestellen',
          status: 'red',
          deployed: {
            version: '0.1.0',
            containers: { 'bestellen-frontend': '0.1.0' },
            environment: 'lokaal',
            at: '2026-11-20T16:00:00Z',
          },
          releases: [{ tag: 'v0.1.0', releaseType: 'planned', at: '2026-11-20T16:00:00Z' }],
          explains: [
            {
              id: 'EXP-001',
              gate: 'security',
              until: '2026-12-31',
              approver: 'sim007',
              lastUsed: '2026-11-20T16:00:00Z',
            },
          ],
          runs: [],
          lastRun: {
            run: 'r2',
            product: 'showkitchen',
            subProduct: 'bestellen',
            version: '0.2.0',
            environment: 'lokaal',
            triggerKind: 'human',
            triggerName: 'sim007',
            releaseType: 'planned',
            commit: 'abc1234',
            started: '2026-11-20T17:00:00Z',
            finished: '2026-11-20T17:02:00Z',
            outcome: 'failure',
            seconds: 120,
            stuckAt: 'security-gate',
            steps: [
              {
                step: 6,
                name: 'security-freshness',
                title: 'Security en versheid',
                outcome: 'failed',
                reason: 'kritieke kwetsbaarheid',
                seconds: 30,
              },
            ],
            gates: [
              { gate: 'code', afterSteps: '1-3', outcome: 'open', explains: [] },
              {
                gate: 'security',
                afterSteps: '6-7',
                outcome: 'closed',
                reason: 'kritieke kwetsbaarheid',
                explains: [],
              },
            ],
            tests,
          },
        },
      ],
    },
  ],
};

const loglines: LoglineRow[] = [
  {
    id: '1',
    time: '2026-11-20T17:02:00Z',
    seconds: 0,
    triggerKind: 'human',
    triggerName: 'sim007',
    product: 'showkitchen',
    subProduct: 'bestellen',
    version: '0.2.0',
    environment: 'lokaal',
    type: 'qualitygate.evaluated',
    action: 'security-gate',
    outcome: 'closed',
    reason: 'kritieke kwetsbaarheid',
    run: 'r2',
  },
];

const kpis: KpiView = {
  experiment: 'mvp0',
  activeExplains: 1,
  keyResultPercent: 66.7,
  products: [
    {
      name: 'showkitchen',
      dora: {
        product: 'showkitchen',
        subProduct: '',
        deployments: 3,
        deploymentsPerDay: 3,
        leadTimeMedianMinutes: 10,
        failedChanges: 1,
        changeFailureRate: 1 / 3,
        restoreMedianMinutes: 30,
        restores: [],
        pipelineSecondsAverage: 106,
      },
      subProducts: [
        {
          product: 'showkitchen',
          subProduct: 'bestellen',
          deployments: 3,
          deploymentsPerDay: 3,
          leadTimeMedianMinutes: 10,
          failedChanges: 1,
          changeFailureRate: 1 / 3,
          restoreMedianMinutes: 30,
          restores: [],
          pipelineSecondsAverage: 106,
        },
      ],
    },
  ],
  gates: [
    {
      gate: 'security',
      evaluated: 2,
      closed: 1,
      reasons: [{ reason: 'bestellen: image x: kritieke of hoge kwetsbaarheid', count: 1 }],
    },
  ],
  trend: [
    {
      day: '2026-11-26',
      deployments: 3,
      leadTimeMedianMinutes: 10,
      runs: 4,
      runsFailed: 1,
      gatesClosed: 1,
      pipelineSecondsAverage: 100,
      keyResultPercent: 66.7,
    },
  ],
};

const keyResult: KeyResultView = {
  experiment: 'mvp0',
  keyResult: 'Elke bestelling van de gast komt aan bij keuken, bar en kassa',
  orders: 3,
  complete: 2,
  percent: 66.7,
  incomplete: [{ orderId: 'abc', tableNumber: 12, at: '2026-11-26T10:10:00Z', missing: ['bar'] }],
  version: '1.0.0',
  receivers: [
    { name: 'kitchen', title: 'keuken', stub: true, lastArrived: true, arrived: 3 },
    { name: 'bar', title: 'bar', stub: true, lastArrived: false, arrived: 2 },
  ],
};

/** Text without whitespace: Angular drops whitespace between elements. */
const flat = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, '');

describe('App (dashboard)', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;
  let el: HTMLElement;

  const changelog = [
    {
      product: 'showkitchen',
      subProduct: 'bestellen',
      title: 'Bestellen',
      version: '1.0.1',
      containers: { 'bestellen-backend': '1.0.1' },
      text: '## [1.0.1] - 2026-11-25\n\n### Opgelost\n- <b>bar</b>',
    },
  ];

  function flushAll(o: Overview = overview): void {
    http.expectOne((r) => r.url === '/api/v1/overview').flush(o);
    http.expectOne((r) => r.url === '/api/v1/tests').flush(tests);
    http.expectOne((r) => r.url === '/api/v1/loglines').flush(loglines);
    http.expectOne((r) => r.url === '/api/v1/kpis').flush(kpis);
    http.expectOne((r) => r.url === '/api/v1/keyresult').flush(keyResult);
    http
      .expectOne((r) => r.url === '/api/v1/report')
      .flush(
        '<h1>Statusrapport showcase Demo</h1><table><tbody><tr><td>Experiment</td><td>mvp0</td></tr></tbody></table>',
      );
    http.expectOne((r) => r.url === '/api/v1/changelog').flush(changelog);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(App);
    http = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('opens on the start page: what the Dashboard shows, the menu as cards, what runs', () => {
    flushAll();
    const selected = () =>
      el.querySelector('.tabs button[aria-selected="true"]')?.textContent?.trim();
    expect(selected()).toBe('Home');
    expect(el.querySelector('.home h2')?.textContent).toContain('Showcase demo');
    const cards = Array.from(el.querySelectorAll('.home .card')).map((c) =>
      c.getAttribute('data-tab'),
    );
    expect(cards).toEqual([
      'live',
      'status',
      'kpis',
      'keyresult',
      'changelog',
      'tests',
      'loglines',
      'report',
    ]);
    expect(el.querySelector('.home .now')?.textContent).toContain('showkitchen');
    (el.querySelector('.home .card[data-tab="live"]') as HTMLElement).click();
    fixture.detectChanges();
    expect(selected()).toBe('Live');
    expect(el.querySelector('app-live')).not.toBeNull();
  });

  it('shows per product which sub-products are red and where they are stuck', () => {
    flushAll();
    openTab('Pipelines en gates');
    expect(el.querySelector('.sub')?.textContent).toContain('Showcase demo');
    expect(el.querySelector('.product h2')?.textContent).toContain('showkitchen');
    expect(el.querySelector('.product .badge')?.textContent).toContain('rood');
    expect(el.querySelector('.stuck')?.textContent).toContain('bestellen: Securitygate dicht');
    const gates = Array.from(el.querySelectorAll('.subproducts .gate')).map((g) =>
      g.textContent?.trim(),
    );
    expect(gates).toEqual(['Codegate', 'Securitygate']);
  });

  it('drills down from product to sub-product: steps, gates with reason, deployed containers and explains', () => {
    flushAll();
    openTab('Pipelines en gates');
    (el.querySelector('.subproducts tbody tr') as HTMLElement).click();
    fixture.detectChanges();
    const detail = el.querySelector('.detail')!;
    expect(detail.textContent).toContain('Security en versheid');
    expect(flat(detail.textContent)).toContain(
      flat('Securitygate (na stap 6-7): dicht – kritieke kwetsbaarheid'),
    );
    expect(detail.textContent).toContain('bestellen-frontend 0.1.0');
    expect(detail.textContent).toContain('EXP-001');
    expect(detail.textContent).toContain('tot 2026-12-31');
  });

  it('shows test results per sub-product and type, summed per product, with the failed tests', () => {
    flushAll();
    openTab('Testresultaten');
    const rows = Array.from(el.querySelectorAll('.tests tbody tr')).map((r) =>
      Array.from(r.querySelectorAll('td'))
        .map((td) => td.textContent?.trim())
        .join(' '),
    );
    expect(rows[0]).toContain('bestellen Unit 45 44 1 0');
    expect(rows[1]).toContain('opgeteld Unit 45 44 1');
    expect(el.querySelector('.failures')?.textContent).toContain('rejects a null line');
  });

  function openTab(name: string): void {
    (
      Array.from(el.querySelectorAll('.tabs button')).find(
        (b) => b.textContent?.trim() === name,
      ) as HTMLElement
    ).click();
    fixture.detectChanges();
  }

  it('shows the DORA metrics per product and sub-product, how often each gate closes, and the trend', () => {
    flushAll();
    openTab("KPI's");
    const dora = Array.from(el.querySelectorAll('.dora tbody tr')).map((r) => flat(r.textContent));
    expect(dora[0]).toContain(flat('showkitchen 3 3 10 min 33.3% (1) 30 min 106 s'));
    expect(dora[1]).toContain('bestellen');
    expect(el.querySelector('.kpis')?.textContent).toContain(
      '1× bestellen: image x: kritieke of hoge kwetsbaarheid',
    );
    expect(flat(el.querySelector('.trend tbody tr')?.textContent)).toContain(
      flat('2026-11-26 3 10 min 4 (1) 1 100 s 66.7%'),
    );
  });

  it('shows the key result: orders, complete, and which orders did not arrive where', () => {
    flushAll();
    openTab('Key result');
    const tiles = Array.from(el.querySelectorAll('.keyresult .tile .value')).map((t) =>
      t.textContent?.trim(),
    );
    expect(tiles).toEqual(['3', '2', '66.7%']);
    expect(flat(el.querySelector('.keyresult tbody tr')?.textContent)).toContain(
      flat('abc 12 bar'),
    );
  });

  it('shows per stub whether the last order arrived, in the key result tab', () => {
    flushAll();
    openTab('Key result');
    const line = el.querySelector('.kr-stubs')!;
    expect(line.classList).toContain('bad');
    expect(flat(line.querySelector('[data-receiver="bar"]')?.textContent)).toBe('✖barstub');
    expect(flat(line.textContent)).toContain('LaatstebestellingmetBestellen1.0.0');
  });

  it('shows the status report as a readable page and offers it as a page and as Markdown', () => {
    flushAll();
    openTab('Statusrapport');
    expect(el.querySelector('.report-html h1')?.textContent).toBe('Statusrapport showcase Demo');
    expect(el.querySelector('.report-html td')?.textContent).toBe('Experiment');
    expect(el.querySelector('.report a[data-format="html"]')?.getAttribute('href')).toBe(
      '/api/v1/report?download=true&format=html',
    );
    expect(el.querySelector('.report a[data-format="markdown"]')?.getAttribute('href')).toBe(
      '/api/v1/report?download=true',
    );
  });

  it('shows the log lines: time, who, sub-product, action and outcome', () => {
    flushAll();
    openTab('Logregels');
    const row = el.querySelector('.loglines tbody tr')?.textContent?.replace(/\s+/g, ' ');
    expect(row).toContain('sim007 (mens)');
    expect(row).toContain('bestellen 0.2.0');
    expect(row).toContain('security-gate');
    expect(row).toContain('dicht');
  });

  it('has a live tab; the key P switches to presentation mode, Escape back', () => {
    flushAll();
    openTab('Live');
    expect(el.querySelector('app-live')).not.toBeNull();
    expect(el.querySelector('.live.presentation')).toBeNull();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'p' }));
    fixture.detectChanges();
    expect(el.querySelector('.live.presentation')).not.toBeNull();
    expect(el.querySelector('.tabs')).toBeNull();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el.querySelector('.live.presentation')).toBeNull();
    expect(el.querySelector('.tabs')).not.toBeNull();
  });

  it('says when there are no log lines yet', () => {
    flushAll({ ...overview, products: [] });
    openTab('Pipelines en gates');
    expect(el.querySelector('.empty')?.textContent).toContain('Nog geen logregels');
  });

  it('shows the changelog of the deployed version as text, never as HTML', () => {
    flushAll();
    (Array.from(el.querySelectorAll('.tabs button')) as HTMLButtonElement[])
      .find((b) => b.textContent?.trim() === 'Changelog')!
      .click();
    fixture.detectChanges();
    expect(el.querySelector('.changelog h2')?.textContent?.trim()).toBe('Bestellen 1.0.1');
    expect(el.querySelector('.changelog .small')?.textContent).toContain('bestellen-backend 1.0.1');
    const text = el.querySelector('.changelog-text')!;
    expect(text.textContent).toContain('<b>bar</b>');
    expect(text.querySelector('b')).toBeNull();
  });

  it('says when the data cannot be fetched', () => {
    http
      .expectOne((r) => r.url === '/api/v1/overview')
      .flush('x', { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('.error')?.textContent).toContain('kan de gegevens nu niet ophalen');
  });
});
