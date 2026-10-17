import { PersonAvatar } from '../../components/Avatar';
import { facesOf, initialOf, sharedLabel } from './collaborators';
import type { NoteDto } from '../../api/types';

/**
 * The people on a shared note, in its card's footer: a face each, a third of it under the next, owner
 * first — up to MAX_FACES of them and then a "+N" circle, so a long list never widens the row. Each
 * face has a ring in the colour behind it, so where two overlap the one in front reads as cut out
 * of the one behind. On a colour card that is the gradient's bottom, where the footer sits; on a
 * photo card, the photo text colour, the way Keep rings its faces in white.
 *
 * Says nothing while the note has no one else on it. The whole row is one image to a screen reader,
 * named by sharedLabel, which is also its tooltip.
 */
export function CollaboratorFaces({ note, onPhoto }: { note: NoteDto; onPhoto: boolean }) {
  const { shown, more } = facesOf(note.collaborators);
  if (shown.length === 0) return null;

  const label = sharedLabel(note);
  const ring = {
    boxShadow: `0 0 0 2px ${onPhoto ? 'var(--photo-text)' : 'color-mix(in srgb, var(--card-bg), black var(--note-sink))'}`,
  };
  const face = 'size-6 text-[11px] [&:not(:first-child)]:-ml-1.5';

  return (
    <span role="img" aria-label={label} title={label} className="flex items-center pl-0.5">
      {shown.map((p) => (
        <PersonAvatar
          key={p.userId}
          userId={p.userId}
          version={p.profileImageVersion}
          initial={initialOf(p.email)}
          className={face}
          style={ring}
        />
      ))}
      {more > 0 && (
        <span
          className={`${face} grid shrink-0 place-items-center rounded-full bg-elevated font-semibold text-text-muted`}
          style={ring}
        >
          +{more}
        </span>
      )}
    </span>
  );
}
