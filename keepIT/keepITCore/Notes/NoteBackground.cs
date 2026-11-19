namespace keepITCore.Notes;

/// <summary>
/// What a note's <see cref="Data.Note.Background"/> may hold: the name of one of the background
/// images in the repository's <c>backgrounds/</c> folder, without its extension (<c>lined</c> for
/// <c>backgrounds/lined.png</c>). The images ship with the clients — the web bundle and the
/// Android app — not with the API, so the server checks only the shape of the name, never that the
/// image exists: a client that doesn't have the image (an older build, a name from a newer one)
/// draws the note without it. See ARCHITECTURE.md → Note backgrounds.
/// <para>
/// Lowercase letters, digits and single hyphens, which is also what the clients accept as a file
/// name in <c>backgrounds/</c>: a name can never be a path, and every platform's file system and
/// URL treat it alike.
/// </para>
/// </summary>
public static class NoteBackground
{
    /// <summary>The shape of a background name, for <c>[RegularExpression]</c> on the DTOs.</summary>
    public const string Pattern = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private static readonly System.Text.RegularExpressions.Regex Shape = new(Pattern);

    /// <summary>
    /// <paramref name="name"/> when it is a well-formed background name within
    /// <see cref="Data.NoteLimits.Background"/>; otherwise null (no background). For import, which
    /// drops what it can't use rather than refusing the archive.
    /// </summary>
    public static string? ValidOrNull(string? name) =>
        name is { Length: > 0 and <= Data.NoteLimits.Background } && Shape.IsMatch(name) ? name : null;
}
