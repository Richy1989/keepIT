import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ABOUT } from './content';

/**
 * The About page's content (`about.json`), which both clients show: the Android app's
 * AboutContentParityTest holds it to the same file. These are the checks that don't need the
 * other client — every link and credit complete, on https, and listed once.
 */
describe('about.json', () => {
  const credits = [...ABOUT.credits.web, ...ABOUT.credits.server];

  it('links only to https addresses', () => {
    for (const url of [...ABOUT.links.map((l) => l.url), ...credits.map((c) => c.url)]) {
      expect(new URL(url).protocol, url).toBe('https:');
    }
  });

  it('gives every link a label and a line of detail, under a unique id', () => {
    for (const link of ABOUT.links) {
      expect(link.label.trim(), link.id).not.toBe('');
      expect(link.detail.trim(), link.id).not.toBe('');
    }
    expect(new Set(ABOUT.links.map((l) => l.id)).size).toBe(ABOUT.links.length);
  });

  it('names what each credited project does and its licence, once per group', () => {
    for (const credit of credits) {
      expect(credit.role.trim(), credit.name).not.toBe('');
      expect(credit.license.trim(), credit.name).not.toBe('');
    }
    for (const group of [ABOUT.credits.web, ABOUT.credits.server]) {
      expect(new Set(group.map((c) => c.name)).size).toBe(group.length);
    }
  });
});

/** Why a dependency has no credit: it is tooling that never reaches anyone using keepIT. */
type NotCredited = { notCredited: string };

/**
 * Every package the web app depends on, and the credit that covers it, or why none does. A package
 * added or removed without updating this, and the credits with it, fails below: the About page
 * thanks the projects keepIT is built on, and a list nobody checks is wrong within a month.
 */
const WEB_PACKAGES: Record<string, string | NotCredited> = {
  react: 'React',
  'react-dom': 'React',
  'react-router-dom': 'React Router',
  '@tanstack/react-query': 'TanStack Query',
  'react-markdown': 'react-markdown and remark',
  'remark-gfm': 'react-markdown and remark',
  'remark-breaks': 'react-markdown and remark',
  '@microsoft/signalr': 'SignalR JavaScript client',
  'openapi-fetch': 'openapi-fetch',
  // The generator of the types openapi-fetch reads, from the same project.
  'openapi-typescript': 'openapi-fetch',
  tailwindcss: 'Tailwind CSS',
  '@tailwindcss/vite': 'Tailwind CSS',
  vite: 'Vite',
  '@vitejs/plugin-react': 'Vite',
  typescript: 'TypeScript',
  '@fontsource-variable/inter': 'Inter',
  vitest: { notCredited: 'runs the tests' },
  eslint: { notCredited: 'lints the source' },
  '@eslint/js': { notCredited: 'lints the source' },
  'typescript-eslint': { notCredited: 'lints the source' },
  'eslint-plugin-react-hooks': { notCredited: 'lints the source' },
  'eslint-plugin-react-refresh': { notCredited: 'lints the source' },
  globals: { notCredited: 'lints the source' },
  '@types/node': { notCredited: 'type definitions for the build' },
  '@types/react': { notCredited: 'type definitions for the build' },
  '@types/react-dom': { notCredited: 'type definitions for the build' },
};

/**
 * The same for the server: the API's NuGet packages, and what the single-container image installs
 * besides the .NET runtime (nginx, which every request passes through).
 */
const SERVER_PACKAGES: Record<string, string | NotCredited> = {
  'Microsoft.AspNetCore.Authentication.JwtBearer': 'ASP.NET Core',
  'Microsoft.AspNetCore.Identity.EntityFrameworkCore': 'ASP.NET Core',
  'Microsoft.AspNetCore.OpenApi': 'ASP.NET Core',
  'Microsoft.EntityFrameworkCore.Design': 'Entity Framework Core',
  'Microsoft.EntityFrameworkCore.Sqlite.Core': 'Entity Framework Core',
  'Npgsql.EntityFrameworkCore.PostgreSQL': 'Npgsql',
  'SourceGear.sqlite3': 'SQLite',
  'SQLitePCLRaw.provider.e_sqlite3': 'SQLitePCLRaw',
  'SixLabors.ImageSharp': 'ImageSharp',
  MailKit: 'MailKit',
  'Net.Codecrete.QrCodeGenerator': 'QR Code Generator',
  'Serilog.AspNetCore': 'Serilog',
  nginx: 'nginx',
  'Microsoft.OpenApi': { notCredited: 'builds the API description, served only in development' },
  'Scalar.AspNetCore': { notCredited: 'the API reference page, served only in development' },
  'Microsoft.VisualStudio.Azure.Containers.Tools.Targets': { notCredited: "Visual Studio's container tooling" },
};

const repoFile = (path: string) => readFileSync(fileURLToPath(new URL(`../../../../${path}`, import.meta.url)), 'utf8');

function webDependencies(): string[] {
  const manifest = JSON.parse(repoFile('web/package.json')) as {
    dependencies?: Record<string, string>;
    devDependencies?: Record<string, string>;
  };
  return [...Object.keys(manifest.dependencies ?? {}), ...Object.keys(manifest.devDependencies ?? {})];
}

function serverDependencies(): string[] {
  const packages = [...repoFile('keepIT/keepITCore/keepITCore.csproj').matchAll(/<PackageReference\s+Include="([^"]+)"/g)].map(
    (m) => m[1],
  );
  // `apt-get install -y --no-install-recommends nginx \`: the words after `install`, options aside.
  const installed = repoFile('deploy/Dockerfile')
    .split('\n')
    .filter((line) => line.includes('apt-get install'))
    .flatMap((line) => line.split('apt-get install')[1].split(/\s+/))
    .filter((word) => /^[a-z0-9][a-z0-9.+-]*$/.test(word));
  return [...packages, ...installed];
}

describe.each([
  { side: 'web', table: WEB_PACKAGES, dependencies: webDependencies, credits: ABOUT.credits.web },
  { side: 'server', table: SERVER_PACKAGES, dependencies: serverDependencies, credits: ABOUT.credits.server },
])('the $side credits and the libraries in use', ({ table, dependencies, credits }) => {
  const used = dependencies();
  const creditNames = credits.map((c) => c.name);
  const covering = Object.values(table).filter((v): v is string => typeof v === 'string');

  it('account for every dependency: credited, or said why not', () => {
    for (const name of used) {
      expect(table, `${name} is new: credit it in about.json, or list it as not credited and why`).toHaveProperty([name]);
    }
  });

  it('list no dependency that is gone', () => {
    for (const name of Object.keys(table)) {
      expect(used, `${name} is no longer used: drop it here, and its credit if nothing else needs it`).toContain(name);
    }
  });

  it('name only credits about.json has', () => {
    for (const credit of covering) expect(creditNames, credit).toContain(credit);
  });

  it('thank no project nothing uses', () => {
    for (const name of creditNames) {
      expect(covering, `${name} is credited, but no dependency is that project`).toContain(name);
    }
  });
});
