namespace keepITCore.Data
{
    /// <summary>
    /// An invitation asking the recipient to accept or decline a note another user wants to share with
    /// them at a given <see cref="Role"/>. The display fields (<see cref="SharedByUserEmail"/>,
    /// <see cref="SharedNoteTitle"/>) are denormalized point-in-time snapshots so the notification
    /// renders without extra joins and stays meaningful even if the source note is later renamed.
    /// <para>The recipient responds via the notifications endpoint; on accept a <see cref="NoteShare"/>
    /// is created (granting access at <see cref="Role"/>) and on decline nothing is granted — either
    /// way this invite is removed. A pending invite is not itself access; only the resulting
    /// <see cref="NoteShare"/> is.</para>
    /// </summary>
    public sealed class ShareInviteNotification : UserNotification
    {
        /// <summary>The note being offered. Plain id (resolved to a <see cref="NoteShare"/> on accept).</summary>
        public Guid SharedNoteId { get; set; }

        /// <summary>The note's title at the time of sharing, shown in the invite.</summary>
        public string? SharedNoteTitle { get; set; }

        /// <summary>The user offering the note (the note's owner).</summary>
        public Guid SharedByUserId { get; set; }

        /// <summary>The sharer's email at the time of sharing, shown in the invite ("X wants to share…").</summary>
        public string? SharedByUserEmail { get; set; }

        /// <summary>The role the recipient will get if they accept (viewer/editor).</summary>
        public NoteRole Role { get; set; } = NoteRole.Viewer;
    }
}
