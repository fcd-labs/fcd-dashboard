import { defineConfig, devices } from '@playwright/test';

// Runs inside the Docker network of the deployed Dashboard (fcd, step 9).
export default defineConfig({
  testDir: './tests',
  outputDir: '/tmp/test-results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://dashboard-frontend:8080',
    trace: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
