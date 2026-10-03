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

        /// <summary>
        /// Global UI accent color key (e.g. "forest"); maps to a swatch on the frontend. New rows
        /// start on "forest", the brand green the Android app also uses.
        /// </summary>
        public string GlobalAccentColor { get; set; } = "forest";

        /// <summary>UI theme preference: "light", "dim", "dark", or "system" (follow the OS).</summary>
        public string Theme { get; set; } = "dark";
    }
}
