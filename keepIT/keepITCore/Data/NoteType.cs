using System.Text.Json.Serialization;

namespace keepITCore.Data;

/// <summary>
/// The kind of content a note primarily holds (ARCHITECTURE.md "Note functions"). A single
/// <see cref="Note"/> table carries this discriminator; type-specific data hangs off it.
/// The <see cref="JsonStringEnumConverter{TEnum}"/> attribute makes this serialize as a name and,
/// crucially, makes the OpenAPI document describe it as a string enum — so the generated TS client
/// gets a <c>"Text" | "Checklist" | "Image"</c> union instead of a meaningless number.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<NoteType>))]
public enum NoteType
{
    /// <summary>Free-form text/markdown in the note body. The default type.</summary>
    Text = 0,

    /// <summary>An ordered list of checkbox items.</summary>
    Checklist = 1,

    /// <summary>Image-first note. Media upload/serving lands in a later pass.</summary>
    Image = 2,
}
