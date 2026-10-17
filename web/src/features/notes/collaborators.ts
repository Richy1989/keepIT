import type { NoteCollaboratorDto, NoteDto } from '../../api/types';

/**
 * The faces on a shared note's card: who is shown, how many are left over, and what the row says
 * to a screen reader and on hover. Pure, so it is unit-tested; the Android app's
 * `ui/notes/Collaborators.kt` holds the same rules.
 */

/** At most this many faces; anyone past them is counted in a "+N" circle instead. */
export const MAX_FACES = 3;

/** The faces to draw, in order, and how many people the "+N" circle stands for (0: no circle). */
export function facesOf(people: NoteCollaboratorDto[]): { shown: NoteCollaboratorDto[]; more: number } {
  const shown = people.slice(0, MAX_FACES);
  return { shown, more: people.length - shown.length };
}

/** The letter a face without a picture shows: the first of the email, upper-cased. */
export function initialOf(email: string): string {
  return (email.trim().charAt(0) || '?').toUpperCase();
}

/**
 * The row in words. The owner reads who has the note ("Shared with ben@x and cleo@y"); a
 * collaborator reads whose it is, who else is on it, and what they may do — which the card no
 * longer says any other way.
 */
export function sharedLabel(note: Pick<NoteDto, 'isOwner' | 'canEdit' | 'collaborators'>): string {
  const people = note.collaborators;
  if (note.isOwner) return `Shared with ${namesOf(people.map((p) => p.email))}`;

  const owner = people.find((p) => p.isOwner);
  const others = people.filter((p) => !p.isOwner).map((p) => p.email);
  const access = note.canEdit ? 'you can edit' : 'view only';
  const by = owner ? `Shared by ${owner.email}` : 'Shared with you';
  return others.length > 0 ? `${by}, also with ${namesOf(others)} — ${access}` : `${by} — ${access}`;
}

/** "a", "a and b", "a, b and c", "a, b, c and 2 others": three names at most. */
function namesOf(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  if (names.length <= MAX_FACES) return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
  const rest = names.length - MAX_FACES;
  return `${names.slice(0, MAX_FACES).join(', ')} and ${rest} ${rest === 1 ? 'other' : 'others'}`;
}
