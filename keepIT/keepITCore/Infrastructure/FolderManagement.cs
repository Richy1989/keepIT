namespace keepITCore.Infrastructure
{
    public static class FolderManagement
    {
        public static string RootPath { get; private set; } = "./App_Data";

        /// <summary>Resolves App:DataRoot (default ./App_Data) to an absolute path and ensures it exists.</summary>
        public static string EnsureDataRoot(IConfiguration config, IHostEnvironment env)
        {
            var configured = config["App:DataRoot"];
            // Default "App_Data" (not "data") so it never collides with the C# Data/ source folder
            // on case-insensitive filesystems (Windows/macOS).
            var root = string.IsNullOrWhiteSpace(configured) ? RootPath : configured;
            var full = Path.GetFullPath(root, env.ContentRootPath);
            Directory.CreateDirectory(full);
            RootPath = full;
            return full;
        }

        public static string GetUserFolder(string userID)
        {
            var path = Path.Combine(RootPath, "users", userID);
            Directory.CreateDirectory(path);
            return path;
        }

        public static string GetUserProfileImageFolder(string userID)
        {
            var path = Path.Combine(GetUserFolder(userID), "profile_image");
            Directory.CreateDirectory(path);
            return path;
        }

        /// <summary>
        /// The folder holding one note's image attachments, creating it if needed. Keyed by owner so
        /// a user's data stays in one subtree under the data root.
        /// </summary>
        /// <param name="userID">The note owner's id.</param>
        /// <param name="noteID">The note's id.</param>
        /// <returns>The absolute folder path.</returns>
        public static string GetNoteMediaFolder(string userID, string noteID)
        {
            var path = Path.Combine(GetUserFolder(userID), "notes", noteID);
            Directory.CreateDirectory(path);
            return path;
        }
    }
}
