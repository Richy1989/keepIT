namespace keepITCore.Data
{
    public class UserSettings
    {
        /// <summary>ID in the database.</summary>
        public Guid Id { get; set; }

        /// <summary>The user who owns these settings. Every query is scoped to the caller's id.</summary>
        public Guid OwnerId { get; set; }

        /// <summary>Navigation to the owning user.</summary>
        public ApplicationUser Owner { get; set; } = null!;

        /// <summary>Setting to set the global accent color in the UI.</summary>
        public string GlobalAccentColor { get; set; } = "yellow";
    }
}
