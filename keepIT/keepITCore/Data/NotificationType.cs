using System.Text.Json.Serialization;

namespace keepITCore.Data;

/// <summary>
/// The kind of notification, used both as the EF Core TPH discriminator (one <c>Notifications</c>
/// table, type-specific columns hang off it) and as the wire discriminator the client switches on.
/// The <see cref="JsonStringEnumConverter{TEnum}"/> makes it serialize as a name and describes it as
/// a string enum in OpenAPI, so the generated TS client gets a <c>"System" | "ShareInvite" | "Reminder"</c>
/// union it can narrow on — each member of that union exposing exactly its type's fields.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<NotificationType>))]
public enum NotificationType
{
    /// <summary>A plain informational message (text + severity). No interactive actions.</summary>
    System = 0,

    /// <summary>An invitation to accept or decline a note another user wants to share. Actionable.</summary>
    ShareInvite = 1,

    /// <summary>A note reminder that came due (see <see cref="NoteReminder"/>). Dismiss-only.</summary>
    Reminder = 2,
}
