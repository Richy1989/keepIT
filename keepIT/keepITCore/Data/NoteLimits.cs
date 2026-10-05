namespace keepITCore.Data;

/// <summary>
/// How much a note and a list may hold: the request DTOs validate against these, the database
/// columns are sized by them, and import holds an archive to them. One place, so the API, the
/// schema and import can never disagree — an import that let through more than the API accepts
/// stored notes that could never be saved again, or failed on the column outright.
/// <para>
/// The Android app mirrors them in <c>data/NoteLimits.kt</c>, to hold a note to them before it is
/// queued; change one here, change it there.
/// </para>
/// </summary>
public static class NoteLimits
{
    /// <summary>A note's title, in UTF-16 code units (<see cref="string.Length"/>).</summary>
    public const int Title = 1000;

    /// <summary>A note's body. Capped so a public instance can't be used as a blob store.</summary>
    public const int Body = 100_000;

    /// <summary>A note's or a list's colour key.</summary>
    public const int Color = 32;

    /// <summary>Rows in one checklist.</summary>
    public const int ChecklistItems = 500;

    /// <summary>One checklist row's text.</summary>
    public const int ChecklistItemText = 2000;

    /// <summary>A list's name.</summary>
    public const int ListName = 100;

    /// <summary>
    /// <paramref name="text"/> cut to at most <paramref name="max"/> code units, never ending on half
    /// a surrogate pair.
    /// </summary>
    public static string Cut(string text, int max)
    {
        if (text.Length <= max) return text;
        var end = max > 0 && char.IsHighSurrogate(text[max - 1]) ? max - 1 : max;
        return text[..end];
    }
}
