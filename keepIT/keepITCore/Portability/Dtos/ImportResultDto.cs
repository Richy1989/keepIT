namespace keepITCore.Portability.Dtos;

/// <summary>
/// What an import actually did. Import never overwrites — every note in the archive arrives as a
/// new note — so these are counts of things added, and the caller can show them without having to
/// diff anything. Anything the archive asked for but the server would not do lands in
/// <see cref="Warnings"/> rather than failing the whole import: a single unreadable image should
/// not cost someone the other 400 notes in the file.
/// </summary>
public class ImportResultDto
{
    /// <summary>Notes created.</summary>
    public int NotesImported { get; set; }

    /// <summary>Lists created because the caller had none by that name.</summary>
    public int ListsCreated { get; set; }

    /// <summary>Lists in the archive that matched one the caller already had, and were filed into.</summary>
    public int ListsReused { get; set; }

    /// <summary>Images re-attached to their notes.</summary>
    public int ImagesImported { get; set; }

    /// <summary>Images that were listed but not re-attached; each one adds a <see cref="Warnings"/> line.</summary>
    public int ImagesSkipped { get; set; }

    /// <summary>Human-readable notes about what was skipped and why. Empty on a clean import.</summary>
    public List<string> Warnings { get; set; } = new();
}
