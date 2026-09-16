namespace keepITCore.Service;

/// <summary>Maps a stored file's extension to the content type its response should carry.</summary>
public static class MediaContentTypes
{
    /// <summary>The content type for a stored media file name, defaulting to a safe binary type.</summary>
    /// <param name="fileName">The stored file name (server-generated, so the extension is trusted).</param>
    /// <returns>An image content type, or <c>application/octet-stream</c> when unrecognised.</returns>
    public static string For(string fileName) => Path.GetExtension(fileName).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        _ => "application/octet-stream",
    };
}
