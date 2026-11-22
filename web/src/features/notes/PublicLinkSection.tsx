import { useState } from 'react';
import { useDeletePublicLink, usePublicLink, useUpsertPublicLink } from './publicLinkQueries';
import { EXPIRY_PRESETS, expiryFromPreset, publicLinkUrl, type ExpiryPreset } from './publicLink';
import { AlertIcon, CheckIcon, CopyIcon, GlobeIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import type { PublicLinkDto } from '../../api/types';

/** The expiry choice that keeps a link's current expiry date as it is. */
type ExpiryChoice = ExpiryPreset | 'keep';

/**
 * The owner's "Publish to web" controls in the share dialog (ARCHITECTURE.md "Public links"):
 * make a link anyone can open without an account, read-only or editable, with an optional
 * password and expiry date; copy it; replace it; take it down.
 */
export function PublicLinkSection({ noteId }: { noteId: string }) {
  const { data: link, isPending } = usePublicLink(noteId);

  return (
    <div className="mt-5 border-t border-border-subtle pt-4">
      <p className="mb-2 flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-text-faint">
        <GlobeIcon className="text-sm" />
        Publish to web
      </p>
      {isPending ? (
        <p className="px-2 py-1.5 text-xs text-text-faint">Loading…</p>
      ) : (
        // Keyed on the link's state so the form starts again from what the server has after a save.
        <PublicLinkForm key={formKey(link)} noteId={noteId} link={link ?? null} />
      )}
    </div>
  );
}

function formKey(link: PublicLinkDto | null | undefined): string {
  if (!link) return 'none';
  return [link.token, link.allowEdit, link.hasPassword, link.expiresAtUtc ?? ''].join('|');
}

function PublicLinkForm({ noteId, link }: { noteId: string; link: PublicLinkDto | null }) {
  const upsert = useUpsertPublicLink(noteId);
  const remove = useDeletePublicLink(noteId);

  const [allowEdit, setAllowEdit] = useState(link?.allowEdit ?? false);
  const [password, setPassword] = useState('');
  const [clearPassword, setClearPassword] = useState(false);
  const [expiry, setExpiry] = useState<ExpiryChoice>(link?.expiresAtUtc ? 'keep' : link ? 'never' : '7d');
  const [customDate, setCustomDate] = useState('');
  const [copied, setCopied] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const url = link && link.token ? publicLinkUrl(window.location.origin, link.token, link.url) : null;
  const willHavePassword = password.length > 0 || (!!link?.hasPassword && !clearPassword);
  const busy = upsert.isPending || remove.isPending;

  function save(regenerate = false) {
    setFormError(null);
    const expiresAtUtc =
      expiry === 'keep' ? (link?.expiresAtUtc ?? null) : expiryFromPreset(expiry, new Date(), customDate);
    if (expiresAtUtc === undefined) {
      setFormError('Pick a date in the future.');
      return;
    }
    if (password.length > 0 && password.length < 4) {
      setFormError('The password needs at least 4 characters.');
      return;
    }
    upsert.mutate({
      allowEdit,
      expiresAtUtc,
      password: password.length > 0 ? password : null,
      clearPassword: password.length === 0 && clearPassword,
      regenerate,
    });
  }

  async function copy() {
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Over plain http the clipboard API is missing: the field is selectable instead.
      setFormError('Copy the link from the field above.');
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {!link && (
        <p className="text-xs text-text-muted">
          Anyone with the link can open this note without an account.
        </p>
      )}

      {link && (
        <div className="flex flex-col gap-1.5">
          {url ? (
            <div className="flex gap-2">
              <input
                readOnly
                value={url}
                aria-label="Public link"
                onFocus={(e) => e.currentTarget.select()}
                className="focus-ring min-w-0 flex-1 rounded-lg border border-border-subtle bg-surface px-3 py-2 font-mono text-xs text-text"
              />
              <button
                type="button"
                onClick={copy}
                className="focus-ring flex items-center gap-1 rounded-lg border border-border-subtle px-3 py-2 text-xs text-text transition hover:bg-surface-hover"
              >
                {copied ? <CheckIcon className="text-sm" /> : <CopyIcon className="text-sm" />}
                {copied ? 'Copied' : 'Copy link'}
              </button>
            </div>
          ) : (
            // The server's keys changed since the link was made: it can't show it, so make a new one.
            <p className="text-xs text-text-muted">This link can't be shown again. Make a new link to share it.</p>
          )}
          {link.isExpired && <p className="text-xs text-danger">This link has expired. Choose a new expiry date.</p>}
        </div>
      )}

      <label className="flex items-center gap-2 text-sm text-text">
        <input
          type="checkbox"
          checked={allowEdit}
          onChange={(e) => setAllowEdit(e.target.checked)}
          className="size-4 accent-[var(--color-accent)]"
        />
        Anyone with the link can edit
      </label>
      {allowEdit && (
        <p className="-mt-2 pl-6 text-xs text-text-muted">
          They can change the title, the text and the checklist, not the colour or the attachments.
        </p>
      )}

      <div className="flex flex-col gap-1">
        <label htmlFor="public-link-password" className="text-xs text-text-muted">
          Password {link?.hasPassword && !clearPassword ? '(set — type a new one to change it)' : '(optional)'}
        </label>
        <div className="flex gap-2">
          <input
            id="public-link-password"
            type="password"
            autoComplete="new-password"
            value={password}
            maxLength={128}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={link?.hasPassword && !clearPassword ? '••••••••' : 'No password'}
            className="focus-ring min-w-0 flex-1 rounded-lg border border-border-subtle bg-surface px-3 py-2 text-sm text-text placeholder:text-text-faint"
          />
          {link?.hasPassword && (
            <button
              type="button"
              onClick={() => {
                setClearPassword(!clearPassword);
                setPassword('');
              }}
              className="focus-ring rounded-lg px-2 text-xs text-text-muted transition hover:bg-surface-hover hover:text-text"
            >
              {clearPassword ? 'Keep password' : 'Remove password'}
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-col gap-1">
        <label htmlFor="public-link-expiry" className="text-xs text-text-muted">
          Link expires
        </label>
        <div className="flex gap-2">
          <select
            id="public-link-expiry"
            value={expiry}
            onChange={(e) => setExpiry(e.target.value as ExpiryChoice)}
            className="focus-ring min-w-0 flex-1 rounded-lg border border-border-subtle bg-surface px-3 py-2 text-sm text-text"
          >
            {link?.expiresAtUtc && (
              <option value="keep">On {new Date(link.expiresAtUtc).toLocaleString()}</option>
            )}
            {EXPIRY_PRESETS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
          {expiry === 'custom' && (
            <input
              type="date"
              aria-label="Expiry date"
              value={customDate}
              onChange={(e) => setCustomDate(e.target.value)}
              className="focus-ring rounded-lg border border-border-subtle bg-surface px-3 py-2 text-sm text-text"
            />
          )}
        </div>
      </div>

      {allowEdit && !willHavePassword && (
        <p className="flex items-start gap-1.5 text-xs text-danger">
          <AlertIcon className="mt-px shrink-0 text-sm" />
          Anyone with the link can change this note. Consider a password.
        </p>
      )}

      {(formError || upsert.isError || remove.isError) && (
        <p className="text-xs text-danger">
          {formError ?? ((upsert.error ?? remove.error) as Error).message}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy}
          onClick={() => save()}
          className="focus-ring rounded-lg bg-accent px-4 py-2 text-sm font-medium text-black transition hover:opacity-90 disabled:opacity-50"
        >
          {link ? 'Save changes' : 'Create public link'}
        </button>
        {link && (
          <>
            <button
              type="button"
              disabled={busy}
              onClick={() => save(true)}
              title="Replace the link: the old one stops working"
              className={cn(
                'focus-ring rounded-lg border border-border-subtle px-3 py-2 text-sm text-text transition hover:bg-surface-hover disabled:opacity-50',
              )}
            >
              New link
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => remove.mutate()}
              className="focus-ring rounded-lg px-3 py-2 text-sm text-danger transition hover:bg-surface-hover disabled:opacity-50"
            >
              Stop sharing
            </button>
          </>
        )}
      </div>
    </div>
  );
}
