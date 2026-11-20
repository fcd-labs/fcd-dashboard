import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { LoglineRow, Overview, TestView } from './model';

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
              approver: 'Johannes',
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
            triggerName: 'Johannes',
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
    triggerName: 'Johannes',
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

/** Text without whitespace: Angular drops whitespace between elements. */
const flat = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, '');

describe('App (dashboard)', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;
  let el: HTMLElement;

  function flushAll(o: Overview = overview): void {
    http.expectOne((r) => r.url === '/api/v1/overview').flush(o);
    http.expectOne((r) => r.url === '/api/v1/tests').flush(tests);
    http.expectOne((r) => r.url === '/api/v1/loglines').flush(loglines);
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

  it('shows per product which sub-products are red and where they are stuck', () => {
    flushAll();
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
    (Array.from(el.querySelectorAll('.tabs button'))[1] as HTMLElement).click();
    fixture.detectChanges();
    const rows = Array.from(el.querySelectorAll('.tests tbody tr')).map((r) =>
      Array.from(r.querySelectorAll('td'))
        .map((td) => td.textContent?.trim())
        .join(' '),
    );
    expect(rows[0]).toContain('bestellen Unit 45 44 1 0');
    expect(rows[1]).toContain('opgeteld Unit 45 44 1');
    expect(el.querySelector('.failures')?.textContent).toContain('rejects a null line');
  });

  it('shows the log lines: time, who, sub-product, action and outcome', () => {
    flushAll();
    (Array.from(el.querySelectorAll('.tabs button'))[2] as HTMLElement).click();
    fixture.detectChanges();
    const row = el.querySelector('.loglines tbody tr')?.textContent?.replace(/\s+/g, ' ');
    expect(row).toContain('Johannes (mens)');
    expect(row).toContain('bestellen 0.2.0');
    expect(row).toContain('security-gate');
    expect(row).toContain('dicht');
  });

  it('says when there are no log lines yet', () => {
    flushAll({ ...overview, products: [] });
    expect(el.querySelector('.empty')?.textContent).toContain('Nog geen logregels');
  });

  it('says when the data cannot be fetched', () => {
    http
      .expectOne((r) => r.url === '/api/v1/overview')
      .flush('x', { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('.error')?.textContent).toContain('kan de gegevens nu niet ophalen');
  });
});
