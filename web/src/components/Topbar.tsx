import { TypewriterIcon, SearchIcon } from './icons';
import { ThemeMenu } from './ThemeMenu';
import { AccountMenu } from './AccountMenu';

/** Top bar: brand, the search field, the appearance menu, and the profile/account menu. */
export function Topbar({
  search,
  onSearchChange,
}: {
  search: string;
  onSearchChange: (v: string) => void;
}) {
  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border-subtle bg-canvas/85 px-3 backdrop-blur sm:gap-4 sm:px-4">
      <div className="flex items-center gap-2">
        <span className="grid size-8 place-items-center rounded-lg bg-accent/15 text-accent">
          <TypewriterIcon className="text-lg" />
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
        <ThemeMenu />
        <AccountMenu />
      </div>
    </header>
  );
}
