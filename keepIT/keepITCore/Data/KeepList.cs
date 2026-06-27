namespace keepITCore.Data;

/// <summary>
/// The "List" concept from ARCHITECTURE.md — a named collection a user files notes into. Named
/// <c>KeepList</c> in code to avoid colliding with <see cref="System.Collections.Generic.List{T}"/>.
/// Lists are private per user and are never shared (a collaborator files a shared note into their
/// own lists via the per-user <see cref="NoteList"/> join).
/// </summary>
public class KeepList
{
    /// <summary>The key / id of the list.</summary>
    public Guid Id { get; set; }

    /// <summary>The user who created and owns this list.</summary>
    public Guid OwnerId { get; set; }

    /// <summary>Navigation to the owning user.</summary>
    public ApplicationUser Owner { get; set; } = null!;

    /// <summary>The list's display name.</summary>
    public string Name { get; set; } = "";

    /// <summary>Optional chip color for the sidebar.</summary>
    public string? Color { get; set; }

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;

    /// <summary>Note memberships in this list.</summary>
    public ICollection<NoteList> NoteLists { get; set; } = new List<NoteList>();
}
