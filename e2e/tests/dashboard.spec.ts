import { expect, test, APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

// Each run uses its own test experiment (test-...): it is never the default view of the Dashboard,
// so the real log lines of the showcase stay as they are.
const experiment = `test-e2e-${Date.now()}`;
const run = `e2e-${randomUUID().slice(0, 8)}`;

function line(type: string, subjectId: string, content: object, extra: object = {}) {
  return {
    context: { version: '0.4.1', id: randomUUID(), source: 'e2e/0.1.0', type, timestamp: new Date().toISOString() },
    subject: { id: subjectId, source: 'e2e', content },
    customData: {
      durationSeconds: 1,
      run,
      commit: { sha: 'e2e0001', time: new Date().toISOString() },
      trigger: { kind: 'human', name: 'E2E' },
      product: 'e2e-product',
      subProduct: { name: 'e2e-sub', version: '1.2.3', containers: { 'e2e-frontend': '1.2.3' } },
      showcase: 'demo',
      experiment,
      environment: 'werkplek',
      releaseType: 'planned',
      ...extra,
    },
  };
}

async function send(request: APIRequestContext, body: object) {
  const response = await request.post('/api/v1/events', { data: body });
  expect(response.status()).toBe(202);
}

test.beforeAll(async ({ request }) => {
  await send(request, line('dev.cdevents.pipelinerun.started.0.2.0', run, { pipelineName: 'e2e', outcome: 'running' }));
  await send(request, line('dev.cdevents.taskrun.finished.0.2.0', 'unit-tests', { taskName: 'unit-tests', step: 2, title: 'Unittests', outcome: 'failed', reason: 'unit: 1 van 3 tests mislukt' }));
  await send(request, line('dev.cdevents.testsuiterun.finished.0.2.0', 'unit', { testType: 'unit', outcome: 'fail', tests: 3, passed: 2, failed: 1, skipped: 0, seconds: 1.5, failures: 'adds a dish' }));
  await send(request, line('dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0', 'code-gate', { gate: 'code', afterSteps: '1-3', outcome: 'closed', reason: 'unit: 1 van 3 tests mislukt', explain: '' }));
  await send(request, line('dev.cdevents.pipelinerun.finished.0.2.0', run, { pipelineName: 'e2e', outcome: 'failure', errors: 'code-gate dicht' }));
});

test('shows a product that is stuck at a closed gate, and drills down to the sub-product', async ({ page }) => {
  await page.goto(`/?experiment=${experiment}`);
  const product = page.locator('[data-product="e2e-product"]');
  await expect(product).toContainText('rood');
  await expect(product.locator('.stuck')).toContainText('e2e-sub: Codegate dicht');

  await product.locator('td.name', { hasText: 'e2e-sub' }).click();
  const detail = page.getByLabel('Deelproduct');
  await expect(detail).toContainText('Unittests');
  await expect(detail).toContainText('Codegate');
  await expect(detail).toContainText('unit: 1 van 3 tests mislukt');
});

test('shows the test results and the failed test', async ({ page }) => {
  await page.goto(`/?experiment=${experiment}`);
  await page.getByRole('tab', { name: 'Testresultaten' }).click();
  await expect(page.locator('.tests')).toContainText('e2e-sub');
  await expect(page.locator('.failures')).toContainText('adds a dish');
});

test('shows the log lines, newest first', async ({ page }) => {
  await page.goto(`/?experiment=${experiment}`);
  await page.getByRole('tab', { name: 'Logregels' }).click();
  const first = page.locator('.loglines tbody tr').first();
  await expect(first).toContainText('E2E');
  await expect(first).toContainText('run');
});

test('rejects a log line that does not conform to the contract', async ({ request }) => {
  const response = await request.post('/api/v1/events', { data: { context: {}, subject: {} } });
  expect(response.status()).toBe(400);
});
