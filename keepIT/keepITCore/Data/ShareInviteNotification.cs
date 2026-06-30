namespace keepITCore.Data
{
    /// <summary>
    /// An invitation asking the recipient to accept or decline a note another user wants to share with
    /// them. The display fields (<see cref="SharedByUserEmail"/>, <see cref="SharedNoteTitle"/>) are
    /// denormalized point-in-time snapshots so the notification renders without extra joins and stays
    /// meaningful even if the source note is later renamed.
    /// <para>The recipient responds via the notifications endpoint; on accept the share is granted, on
    /// decline it's dropped, and either way this notification is removed. The actual share grant/revoke
    /// (the <c>NoteShare</c> entity) lands with the sharing feature — see ARCHITECTURE.md.</para>
    /// </summary>
    public sealed class ShareInviteNotification : UserNotification
    {
        /// <summary>The note being offered. Plain id (no FK navigation yet) until sharing is built out.</summary>
        public Guid SharedNoteId { get; set; }

        /// <summary>The note's title at the time of sharing, shown in the invite.</summary>
        public string? SharedNoteTitle { get; set; }

        /// <summary>The user offering the note (the note's owner). Plain id until sharing is built out.</summary>
        public Guid SharedByUserId { get; set; }

        /// <summary>The sharer's email at the time of sharing, shown in the invite ("X wants to share…").</summary>
        public string? SharedByUserEmail { get; set; }
    }
}
