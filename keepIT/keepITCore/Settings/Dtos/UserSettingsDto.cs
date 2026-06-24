namespace keepITCore.Settings.Dtos
{
    public class UserSettingsDto
    {
        /// <summary>Existing item id. Leave null when adding a new item.</summary>
        public Guid? Id { get; set; }

        public string GlobalAccentColor { get; set; } = "yellow";
    }
}
