namespace keepITCore.Data
{
    public class ShareNote
    {
        public Guid Id { get; set; }

        /// <summary>The user who owns this share request.</summary>
        public Guid OwnerId { get; set; }

        /// <summary>Navigation to the owning user.</summary>
        public ApplicationUser Owner { get; set; } = null!;

        /// <summary>The note being offered. Set only when <see cref="Type"/> is ShareInvite.</summary>
        public Guid? SharedNoteId { get; set; }

        /// <summary>The offered note's title, for display. Set only for ShareInvite.</summary>
        public string? SharedNoteTitle { get; set; }

        /// <summary>The email of the user offering the note, for display. Set only for ShareInvite.</summary>
        public string? SharedByUserEmail { get; set; }
    }
}
