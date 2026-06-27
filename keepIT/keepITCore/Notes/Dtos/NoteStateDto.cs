namespace keepITCore.Notes.Dtos;

/// <summary>
/// Toggles the per-note flags (pin / archive / trash &amp; restore). Each field is optional;
/// a null leaves that flag unchanged. Used for the quick card actions.
/// </summary>
public class NoteStateDto
{
    public bool? IsPinned { get; set; }
    public bool? IsArchived { get; set; }
    public bool? IsTrashed { get; set; }
}
