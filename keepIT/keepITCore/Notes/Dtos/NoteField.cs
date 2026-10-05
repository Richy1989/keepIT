using System.Text.Json.Serialization;

namespace keepITCore.Notes.Dtos;

/// <summary>
/// One editable part of a note's content, as named by <see cref="UpdateNoteDto.Fields"/>. Serialized
/// as a string name, like every wire enum.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<NoteField>))]
public enum NoteField
{
    Type = 0,
    Title = 1,
    Body = 2,
    Color = 3,
    ChecklistItems = 4,
}
