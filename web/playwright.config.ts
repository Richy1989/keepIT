import { defineConfig, devices } from '@playwright/test';

// The browser smoke test (e2e/): one sign-up-to-photo-card run against a keepIT that is already
// running. CI points it at the single-container image it just built; locally start any keepIT and
// `npm run test:e2e`. It starts no server itself, because the point is the server as deployed —
// nginx, its headers and the built bundle — not a dev server Playwright would launch.
//
// KEEPIT_URL defaults to where `docker run -p 8080:80 …` puts the image, the same default as
// deploy/smoke-test.sh. The run signs up a throwaway account: point it at a test instance.
export default defineConfig({
  testDir: 'e2e',
  // No retries: a run that passes on the second try has still shown a user the failure. A retry
  // would also be refused: the API allows one address 120 requests a minute, a run makes about 50,
  // and in CI deploy/smoke-test.sh has just spent some of them. That is the budget to mind before
  // adding a second test here, and why a third run within a minute fails with 429s.
  retries: 0,
  forbidOnly: !!process.env.CI,
  // Generous, because the first upload on a cold CI runner builds its renditions slowly, and a
  // slow pass is worth more here than a fast flake.
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // `list` only: the default HTML report opens a browser on failure, which a terminal run and CI
  // have no use for. `github` turns a failure into an annotation on the commit.
  reporter: process.env.CI ? [['list'], ['github']] : 'list',
  use: {
    baseURL: process.env.KEEPIT_URL ?? 'http://localhost:8080',
    // What a red run leaves behind (test-results/, uploaded by CI): every step, with the DOM,
    // the console and the network at that moment.
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
