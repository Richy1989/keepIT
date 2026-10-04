import type { ComponentType, SVGProps } from 'react';
import { BrandMark } from '../../components/BrandMark';
import {
  CodeIcon,
  ExternalLinkIcon,
  FileTextIcon,
  HeartIcon,
  MessageIcon,
  TagIcon,
} from '../../components/icons';
import { useServerMeta } from '../settings/queries';
import { ABOUT, type Credit } from './content';

/** The icon beside each of the About page's links, by the link's id in `about.json`. */
const LINK_ICONS: Record<string, ComponentType<SVGProps<SVGSVGElement>>> = {
  source: CodeIcon,
  issues: MessageIcon,
  releases: TagIcon,
  license: FileTextIcon,
  support: HeartIcon,
};

/**
 * The Settings page's About section: what keepIT is, the version this server runs (the web app
 * ships in the same image, so it is the web app's too), the project's links, and the open-source
 * projects it is built on, with thanks. The words come from `about.json`, which the Android app's
 * About page is held to.
 */
export function AboutSettings() {
  const meta = useServerMeta();

  return (
    <div className="space-y-6">
      <section className="rounded-2xl border border-border-subtle bg-surface p-6">
        <div className="flex items-center gap-4">
          <BrandMark className="size-14 shrink-0 rounded-2xl" />
          <div className="min-w-0">
            <h2 className="text-xl font-semibold tracking-tight text-text">{ABOUT.name}</h2>
            <p className="text-sm text-text-muted">{ABOUT.tagline}</p>
            {meta.data && (
              <p className="mt-0.5 text-xs text-text-faint">Version {meta.data.version}</p>
            )}
          </div>
        </div>

        <div className="mt-5 space-y-3 text-sm leading-relaxed text-text-muted">
          {ABOUT.description.map((paragraph) => (
            <p key={paragraph}>{paragraph}</p>
          ))}
        </div>

        <ul className="mt-6 grid gap-2 sm:grid-cols-2">
          {ABOUT.links.map((link) => {
            const Icon = LINK_ICONS[link.id] ?? ExternalLinkIcon;
            return (
              <li key={link.id}>
                <a
                  href={link.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="focus-ring flex items-center gap-3 rounded-xl border border-border-subtle px-3.5 py-2.5 transition hover:bg-surface-hover"
                >
                  <span className="grid size-8 shrink-0 place-items-center rounded-full bg-accent/15 text-accent-ink">
                    <Icon className="text-base" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium text-text">{link.label}</span>
                    <span className="block truncate text-xs text-text-faint">{link.detail}</span>
                  </span>
                  <ExternalLinkIcon className="shrink-0 text-sm text-text-faint" />
                </a>
              </li>
            );
          })}
        </ul>
      </section>

      <section className="rounded-2xl border border-border-subtle bg-surface p-6">
        <h2 className="text-lg font-medium text-text">Open-source software</h2>
        <p className="mt-1 text-sm text-text-muted">{ABOUT.thanks}</p>
        <CreditList title="Web app" credits={ABOUT.credits.web} />
        <CreditList title="Server" credits={ABOUT.credits.server} />
      </section>

      <p className="text-center text-xs text-text-faint">
        {ABOUT.copyright} · Released under the MIT License
      </p>
    </div>
  );
}

/** One group of credits: each project's name (a link to it), its licence, and what it does here. */
function CreditList({ title, credits }: { title: string; credits: Credit[] }) {
  return (
    <div className="mt-6">
      <h3 className="mb-2 text-xs font-semibold tracking-wider text-text-faint uppercase">{title}</h3>
      <ul className="divide-y divide-border-subtle rounded-xl border border-border-subtle">
        {credits.map((credit) => (
          <li key={credit.name} className="flex items-baseline gap-3 px-3.5 py-2.5">
            <div className="min-w-0 flex-1">
              <a
                href={credit.url}
                target="_blank"
                rel="noopener noreferrer"
                className="focus-ring rounded text-sm font-medium text-text underline-offset-2 hover:underline"
              >
                {credit.name}
              </a>
              <p className="text-xs text-text-muted">{credit.role}</p>
            </div>
            <span className="shrink-0 rounded-md bg-canvas px-2 py-0.5 text-xs text-text-faint">
              {credit.license}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
