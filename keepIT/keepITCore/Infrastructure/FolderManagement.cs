namespace keepITCore.Infrastructure
{
    public static class FolderManagement
    {
        public static string RootPath { get; private set; } = "./App_Data";

        /// <summary>Resolves App:DataRoot (default ./data) to an absolute path and ensures it exists.</summary>
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
    }
}
