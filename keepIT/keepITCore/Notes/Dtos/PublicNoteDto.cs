using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>
/// A note as a visitor of its public link sees it: its content and nothing else. No owner, no
/// collaborators, no reminders, no lists — nothing about anyone.
/// </summary>
public class PublicNoteDto
{
    public NoteType Type { get; set; }
    public string? Title { get; set; }
    public string? Body { get; set; }

    /// <summary>The palette colour's name, as on <see cref="NoteDto.Color"/>.</summary>
    public string? Color { get; set; }

    /// <summary>The background image's name, as on <see cref="NoteDto.Background"/>.</summary>
    public string? Background { get; set; }

    public DateTime UpdatedAtUtc { get; set; }

    /// <summary>Checklist rows, ordered. Empty for non-checklist notes.</summary>
    public List<ChecklistItemDto> ChecklistItems { get; set; } = new();

    /// <summary>Attachments, ordered; read each through <c>GET /api/public/media/{id}</c> with the ticket.</summary>
    public List<NoteMediaDto> Media { get; set; } = new();

    /// <summary>True when the link lets the visitor change the title, text and checklist.</summary>
    public bool CanEdit { get; set; }

    /// <summary>
    /// What the visitor sends as the <c>X-View-Ticket</c> header to read attachments and save edits,
    /// so neither the token nor the password has to travel again. Valid until
    /// <see cref="TicketExpiresAtUtc"/>, and only while the link stays as it was.
    /// </summary>
    public string ViewTicket { get; set; } = "";

    public DateTime TicketExpiresAtUtc { get; set; }
}
