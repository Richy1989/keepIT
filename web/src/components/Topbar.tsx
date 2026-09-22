import { TypewriterIcon, SearchIcon, MenuIcon } from './icons';
import { ThemeMenu } from './ThemeMenu';
import { AccountMenu } from './AccountMenu';
import { NotificationsBell } from '../features/notifications/NotificationsBell';
import { useMediaQuery } from '../lib/useMediaQuery';

/** Top bar: brand, the search field, the appearance menu, and the profile/account menu. */
export function Topbar({
  search,
  onSearchChange,
  onMenuClick,
}: {
  search: string;
  onSearchChange: (v: string) => void;
  onMenuClick: () => void;
}) {
  // Below this the bar can't seat "Search notes" next to the menu button and the three account
  // controls, and an input clips its placeholder mid-word rather than ellipsing it. 360px is where
  // the measured text (87px) stops fitting the field, not a breakpoint — hence the literal query.
  const roomForFullPlaceholder = useMediaQuery('(min-width: 360px)');

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b border-border-subtle bg-canvas/85 px-2 backdrop-blur sm:gap-4 sm:px-4">
      <button
        type="button"
        onClick={onMenuClick}
        aria-label="Open navigation"
        className="focus-ring grid size-9 shrink-0 place-items-center rounded-lg text-text-muted transition hover:bg-surface-hover hover:text-text md:hidden"
      >
        <MenuIcon className="text-xl" />
      </button>
      {/*
        Hidden below `sm`, brand mark included — the wordmark already was. On a 390px phone the bar
        has to fit the menu button, three account controls and the search field, and the mark's
        32px plus its gap squeezed the field until the placeholder was cut mid-word ("Search
        note:"). The hamburger identifies the app well enough there, which is what Keep and Gmail
        do at this width too.
      */}
      <div className="hidden items-center gap-2 sm:flex">
        <span className="grid size-8 place-items-center rounded-lg bg-accent/15 text-accent-ink">
          <TypewriterIcon className="text-lg" />
        </span>
        <span className="text-lg font-semibold tracking-tight">keepIT</span>
      </div>

      <div role="search" className="relative mx-auto w-full max-w-xl">
        <SearchIcon className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-base text-text-faint" />
        <input
          type="search"
          aria-label="Search notes"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder={roomForFullPlaceholder ? 'Search notes' : 'Search'}
          className="focus-ring w-full rounded-lg border border-transparent bg-surface py-2 pl-9 pr-3 text-sm text-text placeholder:text-text-faint hover:border-border-subtle"
        />
      </div>

      <div className="flex items-center gap-2">
        <NotificationsBell />
        <ThemeMenu />
        <AccountMenu />
      </div>
    </header>
  );
}
