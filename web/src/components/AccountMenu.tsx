import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Avatar } from './Avatar';
import { LogoutIcon, SettingsIcon } from './icons';

/** Profile avatar dropdown: the signed-in user plus Settings and Sign out. */
export function AccountMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);

  const name = user?.displayName || user?.email || '';

  const itemClass =
    'focus-ring flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-sm text-text-muted transition hover:bg-surface-hover hover:text-text';

  return (
    <div className="relative">
      <button
        type="button"
        title={name}
        aria-label="Account"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="focus-ring rounded-full transition hover:opacity-90"
      >
        <Avatar className="size-8 text-sm" />
      </button>

      {open && (
        <>
          {/* Click-away backdrop. */}
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-40 mt-2 w-56 rounded-xl border border-border-subtle bg-elevated p-1.5 shadow-lg shadow-black/30">
            <div className="px-2.5 py-2">
              <p className="truncate text-sm font-medium text-text">{user?.displayName || 'Account'}</p>
              {user?.email && <p className="truncate text-xs text-text-muted">{user.email}</p>}
            </div>
            <div className="my-1 h-px bg-border-subtle" />
            <Link to="/settings" onClick={() => setOpen(false)} className={itemClass}>
              <SettingsIcon className="text-base" />
              Settings
            </Link>
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                void logout();
              }}
              className={itemClass}
            >
              <LogoutIcon className="text-base" />
              Sign out
            </button>
          </div>
        </>
      )}
    </div>
  );
}
