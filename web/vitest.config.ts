import { defineConfig } from 'vitest/config';

// Separate from vite.config.ts: that one carries the dev-server proxy and has no need of the test
// runner's types. These tests are pure — token parsing and layout arithmetic — so they run in
// node, with no jsdom and no browser environment to set up.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
