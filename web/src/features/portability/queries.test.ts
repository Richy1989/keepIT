import { describe, expect, it } from 'vitest';
import { fileNameFrom, MAX_ARCHIVE_BYTES } from './queries';

/** A Response carrying just the header under test. */
function withDisposition(value: string | null): Response {
  return new Response(null, value === null ? undefined : { headers: { 'content-disposition': value } });
}

describe('fileNameFrom', () => {
  it('reads the quoted name the export endpoint sends', () => {
    expect(fileNameFrom(withDisposition('attachment; filename="keepit-export-2026-09-23.zip"'))).toBe(
      'keepit-export-2026-09-23.zip',
    );
  });

  it('reads a bare, unquoted name', () => {
    expect(fileNameFrom(withDisposition('attachment; filename=backup.zip'))).toBe('backup.zip');
  });

  it('decodes the RFC 5987 form', () => {
    expect(
      fileNameFrom(withDisposition("attachment; filename*=UTF-8''keepit%20export.zip")),
    ).toBe('keepit export.zip');
  });

  // A proxy is free to drop the header; the download must still land under a sensible name
  // rather than something like "export" with no extension.
  it('falls back when the header is missing', () => {
    expect(fileNameFrom(withDisposition(null))).toBe('keepit-export.zip');
  });

  it('falls back when the header carries no filename', () => {
    expect(fileNameFrom(withDisposition('attachment'))).toBe('keepit-export.zip');
  });
});

describe('MAX_ARCHIVE_BYTES', () => {
  // Mirrors ImportController.MaxArchiveBytes and the nginx `client_max_body_size` on
  // /api/import. If the server's cap moves, this is the reminder that three places track it.
  it('is the 256 MB the API and the proxy both allow', () => {
    expect(MAX_ARCHIVE_BYTES).toBe(256 * 1024 * 1024);
  });
});
