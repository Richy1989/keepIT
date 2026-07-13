using System.Text.Json.Serialization;

namespace keepITCore.Data;

/// <summary>
/// How often a <see cref="NoteReminder"/> repeats. Serialized as a string name so the generated
/// TS client gets a <c>"None" | "Daily" | …</c> union (same convention as every wire enum).
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<ReminderRecurrence>))]
public enum ReminderRecurrence
{
    /// <summary>Fires once, then stays as a fired reminder until cleared or rescheduled.</summary>
    None = 0,

    Daily = 1,
    Weekly = 2,
    Monthly = 3,
    Yearly = 4,
}
