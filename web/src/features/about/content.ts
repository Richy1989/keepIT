import aboutJson from './about.json';

/** A link out of the About page (source, issues, release notes, licence, support). */
export interface AboutLink {
  id: string;
  label: string;
  detail: string;
  url: string;
}

/** One open-source project keepIT is built on, and what it does here. */
export interface Credit {
  name: string;
  role: string;
  license: string;
  url: string;
}

export interface AboutContent {
  name: string;
  tagline: string;
  description: string[];
  copyright: string;
  links: AboutLink[];
  thanks: string;
  credits: { web: Credit[]; server: Credit[] };
}

/**
 * What the About page says. Kept as JSON rather than TypeScript because the Android app's About
 * page says the same thing: its `AboutContentParityTest` reads this file and fails when the
 * shared parts (the description, links, thanks and the server's credits) drift apart.
 */
export const ABOUT: AboutContent = aboutJson;
