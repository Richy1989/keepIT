import { useRef, useState } from 'react';
import { AlertIcon, DownloadIcon } from '../../components/icons';
import { MAX_ARCHIVE_BYTES, useExportArchive, useImportArchive } from './queries';
import type { ImportResultDto } from '../../api/types';

/**
 * Export and import for the Settings page — the two halves of "these notes are yours".
 *
 * The copy is deliberately explicit about the two things a user can't see from the button: an
 * export holds the notes they *own* (not ones shared with them), and an import *adds* rather than
 * replaces. Both are surprising after the fact and cheap to say up front.
 */
export function DataSettings() {
  const exportArchive = useExportArchive();
  const importArchive = useImportArchive();
  const filePicker = useRef<HTMLInputElement>(null);
  const [fileName, setFileName] = useState<string | null>(null);

  function onFileChosen(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // Reset first: picking the same file twice in a row fires no change event otherwise, which
    // looks like a dead button after a failed import.
    event.target.value = '';
    if (!file) return;

    setFileName(file.name);
    importArchive.mutate(file);
  }

  return (
    <div className="space-y-8">
      <section className="max-w-lg space-y-3">
        <div>
          <h3 className="text-sm font-medium text-text">Download a copy</h3>
          <p className="mt-1 text-sm text-text-muted">
            A single <code className="font-mono text-xs">.zip</code> holding every note you own —
            text, checklists, colours, reminders, your lists, and the images attached to them.
            Archived and deleted notes come too. Notes other people shared with you stay theirs and
            aren't included.
          </p>
        </div>

        {exportArchive.isError && (
          <p role="alert" className="rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
            {exportArchive.error.message}
          </p>
        )}

        {exportArchive.isSuccess && (
          <p role="status" className="rounded-lg bg-accent/10 px-3 py-2 text-sm text-accent-ink">
            Your export has been downloaded ({formatBytes(exportArchive.data)}). Keep it somewhere
            safe — anyone who opens it can read your notes.
          </p>
        )}

        <button
          type="button"
          onClick={() => exportArchive.mutate()}
          disabled={exportArchive.isPending}
          className="focus-ring flex items-center gap-2 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
        >
          <DownloadIcon className="text-base" />
          {exportArchive.isPending ? 'Preparing…' : 'Download my notes'}
        </button>
      </section>

      <section className="max-w-lg space-y-3 border-t border-border-subtle pt-6">
        <div>
          <h3 className="text-sm font-medium text-text">Restore from a copy</h3>
          <p className="mt-1 text-sm text-text-muted">
            Upload an export to add its notes to this account. Nothing already here is changed or
            replaced — everything arrives as new notes, so importing the same file twice gives you
            two of each. Lists you already have are reused rather than duplicated.
          </p>
        </div>

        {importArchive.isError && (
          <p role="alert" className="rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
            {importArchive.error.message}
          </p>
        )}

        {importArchive.isSuccess && <ImportSummary result={importArchive.data} />}

        <input
          ref={filePicker}
          type="file"
          accept=".zip,application/zip"
          onChange={onFileChosen}
          className="hidden"
        />

        <button
          type="button"
          onClick={() => filePicker.current?.click()}
          disabled={importArchive.isPending}
          className="focus-ring rounded-lg border border-border px-4 py-2 text-sm font-semibold text-text transition hover:bg-surface-hover disabled:opacity-60"
        >
          {importArchive.isPending ? `Importing ${fileName ?? 'your archive'}…` : 'Choose an export file…'}
        </button>

        <p className="text-xs text-text-faint">
          keepIT exports only, up to {MAX_ARCHIVE_BYTES / (1024 * 1024)} MB. Exports from other
          notes apps aren't supported yet.
        </p>
      </section>
    </div>
  );
}

/** What the import actually did, plus anything it had to skip. */
function ImportSummary({ result }: { result: ImportResultDto }) {
  const parts = [
    count(result.notesImported, 'note'),
    result.listsCreated > 0 ? count(result.listsCreated, 'new list') : null,
    result.imagesImported > 0 ? count(result.imagesImported, 'image') : null,
  ].filter(Boolean);

  return (
    <div role="status" className="space-y-2">
      <p className="rounded-lg bg-accent/10 px-3 py-2 text-sm text-accent-ink">
        Imported {parts.join(', ')}.
        {result.listsReused > 0 && ` Filed into ${count(result.listsReused, 'list')} you already had.`}
      </p>

      {result.warnings.length > 0 && (
        <div className="flex gap-2.5 rounded-lg bg-warning-bg px-3 py-2.5 text-sm text-warning">
          <AlertIcon className="mt-0.5 shrink-0 text-base" />
          <div className="min-w-0 space-y-1">
            <p className="font-medium">
              {count(result.imagesSkipped, 'image')} couldn't be imported
            </p>
            <ul className="list-inside list-disc space-y-0.5">
              {result.warnings.slice(0, 5).map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
            {result.warnings.length > 5 && <p>…and {result.warnings.length - 5} more.</p>}
          </div>
        </div>
      )}
    </div>
  );
}

/** "1 note" / "3 notes". */
function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/** Human-readable size for the downloaded archive. */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
