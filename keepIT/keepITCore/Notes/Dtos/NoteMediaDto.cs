namespace keepITCore.Notes.Dtos;

/// <summary>
/// One image attached to a note. Carries no URL: clients build the path from the note and media ids
/// and fetch the bytes as an authenticated request. <see cref="Width"/>/<see cref="Height"/> are here
/// so a client can reserve the right box before the image arrives.
/// </summary>
public class NoteMediaDto
{
    public Guid Id { get; set; }

    /// <summary>Pixel width of the stored original.</summary>
    public int Width { get; set; }

    /// <summary>Pixel height of the stored original.</summary>
    public int Height { get; set; }

    /// <summary>Stored size in bytes, after server-side re-encoding.</summary>
    public long ByteSize { get; set; }

    /// <summary>Position within the note, ascending.</summary>
    public int Order { get; set; }

    public DateTime CreatedAtUtc { get; set; }
}
