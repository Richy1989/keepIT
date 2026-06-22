import { useAuth } from '../auth/AuthContext';
import { LightbulbIcon, LogoutIcon, SearchIcon } from './icons';

/** Top bar: brand, the search field, and the current user with a sign-out button. */
export function Topbar({
  search,
  onSearchChange,
}: {
  search: string;
  onSearchChange: (v: string) => void;
}) {
  const { user, logout } = useAuth();
  const name = user?.displayName || user?.email || '';
  const initial = name.charAt(0).toUpperCase();

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border-subtle bg-canvas/85 px-3 backdrop-blur sm:gap-4 sm:px-4">
      <div className="flex items-center gap-2">
        <span className="grid size-8 place-items-center rounded-lg bg-accent/15 text-accent">
          <LightbulbIcon className="text-lg" />
        </span>
        <span className="hidden text-lg font-semibold tracking-tight sm:block">keepIT</span>
      </div>

      <div className="relative mx-auto w-full max-w-xl">
        <SearchIcon className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-base text-text-faint" />
        <input
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search notes"
          className="focus-ring w-full rounded-lg border border-transparent bg-surface py-2 pl-9 pr-3 text-sm text-text placeholder:text-text-faint hover:border-border-subtle"
        />
      </div>

      <div className="flex items-center gap-2">
        <span
          title={name}
          className="grid size-8 place-items-center rounded-full bg-elevated text-sm font-semibold text-text-muted"
        >
          {initial}
        </span>
        <button
          type="button"
          onClick={() => void logout()}
          title="Sign out"
          className="focus-ring grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-surface-hover hover:text-text"
        >
          <LogoutIcon className="text-lg" />
        </button>
      </div>
    </header>
  );
}
