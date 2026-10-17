import { describe, expect, it } from 'vitest';
import { facesOf, initialOf, MAX_FACES, sharedLabel } from './collaborators';
import type { NoteCollaboratorDto } from '../../api/types';

/**
 * The faces on a shared card. The rules that matter: a long list never grows the row past
 * MAX_FACES faces and one "+N", and the words say who is on the note and, for a collaborator,
 * what they may do — the card's only remaining sign of view-only access.
 */

function person(email: string, isOwner = false): NoteCollaboratorDto {
  return { userId: `id-${email}`, email, isOwner, profileImageVersion: null };
}

describe('facesOf', () => {
  it('shows everyone while they fit', () => {
    const people = [person('a@x'), person('b@x'), person('c@x')];
    expect(facesOf(people)).toEqual({ shown: people, more: 0 });
  });

  it(`stops at ${MAX_FACES} faces and counts the rest`, () => {
    const people = ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((n) => person(`${n}@x`));
    const { shown, more } = facesOf(people);
    expect(shown.map((p) => p.email)).toEqual(['a@x', 'b@x', 'c@x']);
    expect(more).toBe(4);
  });
});

describe('initialOf', () => {
  it('is the first letter of the email, upper-cased', () => {
    expect(initialOf('anna@example.com')).toBe('A');
    expect(initialOf('  ')).toBe('?');
  });
});

describe('sharedLabel', () => {
  it('tells the owner who has the note', () => {
    const one = { isOwner: true, canEdit: true, collaborators: [person('ben@x')] };
    expect(sharedLabel(one)).toBe('Shared with ben@x');

    const many = {
      isOwner: true,
      canEdit: true,
      collaborators: ['ben', 'cleo', 'dan', 'eve', 'finn'].map((n) => person(`${n}@x`)),
    };
    expect(sharedLabel(many)).toBe('Shared with ben@x, cleo@x, dan@x and 2 others');
  });

  it('tells a collaborator whose it is, who else is on it, and what they may do', () => {
    expect(sharedLabel({ isOwner: false, canEdit: false, collaborators: [person('anna@x', true)] })).toBe(
      'Shared by anna@x — view only',
    );
    expect(
      sharedLabel({
        isOwner: false,
        canEdit: true,
        collaborators: [person('anna@x', true), person('cleo@x'), person('dan@x')],
      }),
    ).toBe('Shared by anna@x, also with cleo@x and dan@x — you can edit');
  });
});
