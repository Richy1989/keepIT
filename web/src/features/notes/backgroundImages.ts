import { backgroundsFromFiles } from './backgrounds';

/**
 * The backgrounds this build ships: every image in the repository's `backgrounds/` folder, bundled
 * by Vite (hashed, so a changed image is never served stale). Empty when the folder holds none, and
 * the pickers then don't offer backgrounds at all. See `backgrounds.ts`.
 */
export const NOTE_BACKGROUNDS = backgroundsFromFiles(
  import.meta.glob<string>('../../../../backgrounds/*.{png,webp}', {
    eager: true,
    query: '?url',
    import: 'default',
  }),
);
