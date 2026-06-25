namespace keepITCore.Settings.Dtos
{
    /// <summary>The caller's UI settings. Also used as the request body for updates (Id is ignored).</summary>
    public class UserSettingsDto
    {
        /// <summary>Settings row id. Server-assigned; ignored on update.</summary>
        public Guid? Id { get; set; }

        /// <summary>Global UI accent color key (e.g. "yellow").</summary>
        public string GlobalAccentColor { get; set; } = "yellow";

        /// <summary>UI theme preference: "light", "dim", "dark", or "system".</summary>
        public string Theme { get; set; } = "dark";
    }
}
