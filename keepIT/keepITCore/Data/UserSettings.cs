namespace keepITCore.Data
{
    /// <summary>
    /// Per-user UI preferences. Exactly one row per user (unique on <see cref="OwnerId"/>), created
    /// lazily the first time the user reads or writes their settings.
    /// </summary>
    public class UserSettings
    {
        /// <summary>ID in the database.</summary>
        public Guid Id { get; set; }

        /// <summary>The user who owns these settings. Every query is scoped to the caller's id.</summary>
        public Guid OwnerId { get; set; }

        /// <summary>Navigation to the owning user.</summary>
        public ApplicationUser Owner { get; set; } = null!;

        /// <summary>Global UI accent color key (e.g. "yellow"); maps to a swatch on the frontend.</summary>
        public string GlobalAccentColor { get; set; } = "yellow";

        /// <summary>UI theme preference: "light", "dim", "dark", or "system" (follow the OS).</summary>
        public string Theme { get; set; } = "dark";
    }
}
