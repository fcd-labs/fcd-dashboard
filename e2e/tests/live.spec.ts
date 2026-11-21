import { expect, test, APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

// The live view: the Dashboard pushes every new log line to the browser (Server-Sent Events), without a refresh.
// Own test experiment (test-...), so the real log of the showcase stays as it is.
const experiment = `test-e2e-live-${Date.now()}`;
const run = `e2e-live-${randomUUID().slice(0, 8)}`;
const subProduct = 'e2e-live';
let showcase = '';

function line(type: string, subjectId: string, content: object, extra: object = {}) {
  return {
    context: { version: '0.4.1', id: randomUUID(), source: 'e2e/0.1.0', type, timestamp: new Date().toISOString() },
    subject: { id: subjectId, source: 'e2e', content },
    customData: {
      durationSeconds: 1,
      run,
      commit: { sha: 'e2e0002', time: new Date().toISOString() },
      trigger: { kind: 'human', name: 'E2E' },
      product: 'e2e-product',
      subProduct: { name: subProduct, version: '2.0.0' },
      showcase,
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
  showcase = (await (await request.get('/api/v1/overview')).json()).showcase;
});

test('the live view follows a run step by step, without a refresh', async ({ page, request }) => {
  const state = await (await request.get(`/api/v1/live/state?experiment=${experiment}`)).json();
  // A showcase can limit the live view to its own sub-products and environments (configuration).
  test.skip(
    state.settings.subProducts.length > 0 || (state.settings.environments.length > 0 && !state.settings.environments.includes('werkplek')),
    'the configuration of this showcase limits the live view',
  );
  const task = (name: string, step: number, title: string) => ({ taskName: name, step, title, pipelineRun: { id: run } });

  await send(request, line('dev.cdevents.pipelinerun.started.0.2.0', run, { pipelineName: 'e2e', outcome: 'running', steps: ['lint', 'unit-tests', 'release'] }));
  await send(request, line('dev.cdevents.taskrun.started.0.2.0', 'lint', { ...task('lint', 1, 'Lint'), outcome: 'running' }));

  await page.goto(`/?tab=live&experiment=${experiment}`);
  const lane = page.locator(`.lane[data-subproduct="${subProduct}"]`);
  await expect(page.locator('.conn')).toContainText('live');
  await expect(lane.locator('[data-name="lint"]')).toHaveAttribute('data-status', 'running');
  await expect(lane.locator('[data-name="unit-tests"]')).toHaveAttribute('data-status', 'waiting');

  // New log lines arrive: the page updates by itself.
  await send(request, line('dev.cdevents.taskrun.finished.0.2.0', 'lint', { ...task('lint', 1, 'Lint'), outcome: 'succeeded', reason: '' }));
  await send(request, line('dev.cdevents.taskrun.started.0.2.0', 'unit-tests', { ...task('unit-tests', 2, 'Unittests'), outcome: 'running' }));
  await send(request, line('dev.cdeventsx.fcd-testsuiterun.progressed.0.1.0', 'unit', { testType: 'unit', tests: 5, passed: 4, failed: 1, skipped: 0 }));
  await expect(lane.locator('[data-name="lint"]')).toHaveAttribute('data-status', 'succeeded');
  await expect(lane.locator('[data-name="unit-tests"] .tests')).toContainText('4');

  await send(
    request,
    line('dev.cdevents.taskrun.finished.0.2.0', 'unit-tests', {
      ...task('unit-tests', 2, 'Unittests'),
      outcome: 'failed',
      reason: '1 van 5 tests mislukt',
      excerpt: 'adds a dish: expected 3 but was 2',
    }),
  );
  await send(request, line('dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0', 'code-gate', { gate: 'code', afterSteps: 'lint,unit-tests', outcome: 'closed', reason: 'unittests niet groen', explain: '' }));
  await send(request, line('dev.cdevents.pipelinerun.finished.0.2.0', run, { pipelineName: 'e2e', outcome: 'failure', errors: 'code-gate dicht' }));

  await expect(lane).toHaveAttribute('data-outcome', 'failure');
  await expect(lane.locator('.excerpt')).toContainText('expected 3 but was 2');
  await expect(lane.locator('[data-name="code"]')).toHaveAttribute('data-status', 'closed');
  await expect(lane.locator('[data-name="release"]')).toHaveAttribute('data-status', 'not-run');
  await expect(lane.locator('.outcome a')).toHaveAttribute('href', new RegExp(`run=${run}`));
});

test('presentation mode for a large screen', async ({ page }) => {
  await page.goto(`/?presentatie&experiment=${experiment}`);
  await expect(page.locator('.live.presentation')).toBeVisible();
  await expect(page.locator('.tabs')).toHaveCount(0);
  await page.keyboard.press('Escape');
  await expect(page.locator('.tabs')).toBeVisible();
});

test('the live stream only answers to Server-Sent Events', async ({ request }) => {
  const response = await request.get('/api/v1/live');
  expect(response.status()).toBe(406);
});
