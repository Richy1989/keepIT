namespace keepITCore.Infrastructure;

/// <summary>
/// The address users open keepIT at (<c>App:PublicBaseUrl</c>, e.g. <c>https://notes.example.com</c>),
/// the only trustworthy base for a link the server sends out of its own hands — today the
/// password-reset link in an email.
/// <para>A request cannot stand in for it. <c>Origin</c> and <c>Host</c> are whatever the sender
/// chooses, and the endpoints that send links are anonymous, so a link built from them lets anyone
/// have a victim's genuine email point at a site of their choosing (password-reset poisoning).</para>
/// </summary>
public static class PublicBaseUrl
{
    /// <summary>The configuration key (<c>App__PublicBaseUrl</c> in the environment).</summary>
    public const string ConfigKey = "App:PublicBaseUrl";

    /// <summary>
    /// Reads and validates the configured public base URL.
    /// </summary>
    /// <param name="configuration">App configuration.</param>
    /// <returns>The URL without a trailing slash, or null when none is configured.</returns>
    /// <exception cref="InvalidOperationException">The value is set but is not an absolute http(s)
    /// address — a misconfiguration worth refusing to start over, like a malformed <c>Jwt:Key</c>.</exception>
    public static string? Read(IConfiguration configuration)
    {
        var raw = configuration[ConfigKey]?.Trim();
        if (string.IsNullOrEmpty(raw)) return null;

        // A path is allowed (keepIT served under a sub-path); a query or fragment would corrupt
        // every link built by appending to it.
        if (!Uri.TryCreate(raw, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp)
            || string.IsNullOrEmpty(uri.Host)
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment)
            || !string.IsNullOrEmpty(uri.UserInfo))
        {
            throw new InvalidOperationException(
                $"{ConfigKey} must be the absolute http(s) address users open keepIT at, " +
                $"e.g. https://notes.example.com, but got \"{raw}\". Set App__PublicBaseUrl accordingly.");
        }

        return raw.TrimEnd('/');
    }
}
