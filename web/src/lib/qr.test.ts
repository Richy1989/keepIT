import { describe, expect, it } from 'vitest';
import { qrPath } from './qr';

describe('qrPath', () => {
  it('draws each run of dark modules as one rectangle, row by row', () => {
    expect(qrPath(['0110', '1001'])).toBe('M1 0h2v1h-2zM0 1h1v1h-1zM3 1h1v1h-1z');
  });

  it('draws nothing for a row with no dark modules', () => {
    expect(qrPath(['000', '000'])).toBe('');
  });

  it('runs to the end of a row', () => {
    expect(qrPath(['0111'])).toBe('M1 0h3v1h-3z');
  });
});
