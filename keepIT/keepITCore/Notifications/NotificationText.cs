namespace keepITCore.Notifications;

/// <summary>
/// Builders for a notification's <c>NotificationText</c> fallback string. The value must always fit
/// the 200-char column: Postgres rejects an overflow at save time (SQLite dev doesn't enforce
/// lengths, so it would only break in prod), and clients render invites/reminders from the snapshot
/// fields anyway — this text is just a fallback. The unbounded title is clipped first, then the
/// whole string as a belt-and-braces. Centralized here so the column-fit rule lives in one place.
/// </summary>
public static class NotificationText
{
    /// <summary>The <c>NotificationText</c> column's length limit.</summary>
    public const int MaxLength = 200;

    /// <summary>The most a note title may occupy inside a composed message.</summary>
    private const int TitleMaxLength = 60;

    /// <summary>A display-safe note title (never blank).</summary>
    /// <param name="title">The note's title, possibly null/blank.</param>
    /// <returns>The title, or "Untitled note" when it's null or whitespace.</returns>
    public static string TitleOrUntitled(string? title) =>
        string.IsNullOrWhiteSpace(title) ? "Untitled note" : title;

    /// <summary>The fallback text for a fired reminder.</summary>
    /// <param name="title">The reminded note's title.</param>
    /// <returns>A message guaranteed to fit <see cref="MaxLength"/>.</returns>
    public static string Reminder(string? title) =>
        Clip($"Reminder: \"{Clip(TitleOrUntitled(title), TitleMaxLength)}\"", MaxLength);

    /// <summary>The fallback text for a share invite.</summary>
    /// <param name="sharerEmail">The inviting user's email.</param>
    /// <param name="title">The shared note's title.</param>
    /// <returns>A message guaranteed to fit <see cref="MaxLength"/>.</returns>
    public static string ShareInvite(string? sharerEmail, string? title) =>
        Clip($"{sharerEmail} wants to share \"{Clip(TitleOrUntitled(title), TitleMaxLength)}\" with you.", MaxLength);

    /// <summary>Truncates to <paramref name="max"/> chars, appending an ellipsis when clipped.</summary>
    /// <param name="value">The string to bound.</param>
    /// <param name="max">The maximum length of the result (including the ellipsis).</param>
    /// <returns>The original string, or a clipped copy ending in an ellipsis.</returns>
    public static string Clip(string value, int max) =>
        value.Length <= max ? value : value[..(max - 1)] + "…";
}
