import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ChangePasswordForm } from '../features/account/ChangePasswordForm';
import { UserIconSetting } from '../features/account/UserIconSetting';
import {
  ChevronLeftIcon,
  LogoutIcon,
  ShieldIcon,
  TypewriterIcon,
  UserIcon,
} from '../components/icons';
import { cn } from '../lib/cn';

type SectionKey = 'general' | 'security';

const SECTIONS: { key: SectionKey; label: string; icon: typeof UserIcon }[] = [
  { key: 'general', label: 'General', icon: UserIcon },
  { key: 'security', label: 'Security', icon: ShieldIcon },
];

/** Account settings: section nav on the left, the active section's controls on the right. */
export function SettingsPage() {
  const { user, logout } = useAuth();
  const [active, setActive] = useState<SectionKey>('general');

  return (
    <div className="h-full overflow-y-auto bg-canvas">
      <div className="mx-auto max-w-4xl px-4 py-8 sm:px-6">
        {/* Header */}
        <div className="mb-8 flex items-center gap-3">
          <Link
            to="/"
            title="Back to notes"
            aria-label="Back to notes"
            className="focus-ring grid size-9 place-items-center rounded-full text-text-muted transition hover:bg-surface-hover hover:text-text"
          >
            <ChevronLeftIcon className="text-lg" />
          </Link>
          <div className="flex items-center gap-2">
            <span className="grid size-7 place-items-center rounded-lg bg-accent/15 text-accent">
              <TypewriterIcon className="text-base" />
            </span>
            <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
          </div>
        </div>

        <div className="flex flex-col gap-6 sm:flex-row sm:gap-8">
          {/* Left nav */}
          <nav className="shrink-0 sm:w-52">
            <ul className="flex gap-1 overflow-x-auto sm:flex-col sm:overflow-visible">
              {SECTIONS.map((s) => {
                const Icon = s.icon;
                const on = active === s.key;
                return (
                  <li key={s.key} className="shrink-0">
                    <button
                      type="button"
                      onClick={() => setActive(s.key)}
                      aria-current={on}
                      className={cn(
                        'focus-ring flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition',
                        on
                          ? 'bg-accent/15 font-medium text-accent'
                          : 'text-text-muted hover:bg-surface-hover hover:text-text',
                      )}
                    >
                      <Icon className="text-base" />
                      {s.label}
                    </button>
                  </li>
                );
              })}
            </ul>

            <div className="mt-2 border-border-subtle pt-2 sm:mt-4 sm:border-t">
              <button
                type="button"
                onClick={() => void logout()}
                className="focus-ring flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-text-muted transition hover:bg-surface-hover hover:text-text"
              >
                <LogoutIcon className="text-base" />
                Sign out
              </button>
            </div>
          </nav>

          {/* Content */}
          <div className="min-w-0 flex-1">
            {active === 'general' && (
              <SettingCard
                title="Profile picture"
                description="Add a user icon shown next to your account."
              >
                <UserIconSetting />
                <div className="mt-6 border-t border-border-subtle pt-4">
                  <dl className="grid gap-3 text-sm sm:grid-cols-[8rem_1fr]">
                    <dt className="text-text-muted">Display name</dt>
                    <dd className="text-text">{user?.displayName || '—'}</dd>
                    <dt className="text-text-muted">Email</dt>
                    <dd className="text-text">{user?.email}</dd>
                  </dl>
                </div>
              </SettingCard>
            )}

            {active === 'security' && (
              <SettingCard
                title="Change password"
                description="Update your password. This signs you out of your other devices."
              >
                <ChangePasswordForm />
              </SettingCard>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/** A titled settings card wrapping a group of controls. */
function SettingCard({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-border-subtle bg-surface p-6">
      <h2 className="text-lg font-medium text-text">{title}</h2>
      {description && <p className="mt-1 text-sm text-text-muted">{description}</p>}
      <div className="mt-5">{children}</div>
    </section>
  );
}
