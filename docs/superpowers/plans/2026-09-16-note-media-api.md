# Note Media — API Implementation Plan (1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the API the ability to store, serve and delete image attachments on any note, so the web and Android clients have a contract to build against.

**Architecture:** A new `NoteMedia` entity hangs off `Note` (cascade delete), with bytes on disk behind an `IMediaStorage` abstraction rather than in the database. A dedicated `NoteMediaController` handles multipart upload, authenticated streaming and delete, resolving every request through the existing `NoteAccessService` so a collaborator reaches a shared note's images *through the note*. ImageSharp re-encodes originals (stripping EXIF) and generates one thumbnail on upload.

**Tech Stack:** ASP.NET Core 10, EF Core (Npgsql authoritative, SQLite dev fallback), SixLabors.ImageSharp, SignalR realtime notifier.

**Spec:** `docs/superpowers/specs/2026-09-16-note-media-design.md` — read it before starting. This plan argues from that spec; where they disagree, the spec wins.

**Branch:** `feat/note-media` (already created; do not branch again).

## Global Constraints

- **There is no backend test project in this repo, and this plan does not create one.** That is a deliberate scope decision recorded in the spec (§11). Each task therefore ends with a build plus a *specific* manual verification, not a unit test. Do not scaffold xUnit.
- **Access is "own OR shared", never a bare `OwnerId == me`.** Every endpoint resolves through `NoteAccessService.ResolveAsync`. Read needs any access; content writes need `CanEdit` (owner or Editor); hard-delete stays owner-only.
- **Every mutating endpoint must push realtime.** After `SaveChangesAsync`, notify the full recipient set (`NoteAccessService.RecipientIdsAsync`) with `RealtimeResources.Notes` — media is shared content, not per-user state.
- **Migrations are Postgres-authoritative.** `AppDbContextFactory` always targets Npgsql. The SQLite dev DB uses `EnsureCreated` and will not alter an existing file — delete `App_Data/keepit.db` to pick up the new table locally.
- **String columns get explicit `HasMaxLength`**, or Postgres gives them unbounded `text`.
- **`DateTime` values written to Postgres must have `DateTimeKind.Utc`** — Npgsql throws where SQLite silently accepts. Always `DateTime.UtcNow`, never a client-supplied timestamp.
- **DTO naming:** request/response DTOs are suffixed `Dto` and live in `keepIT/keepITCore/Notes/Dtos/`.
- **Doc comments:** match the heavy XML-doc style of the surrounding files. Every public type and member gets a `<summary>`.
- **Limits:** 10 MB per uploaded image, 10 images per note. Both configurable.
- **Commits:** imperative and resource-scoped, prefixed `api:` (or `docs:` for Task 8).

## File Structure

**Create:**
- `keepIT/keepITCore/Data/NoteMedia.cs` — the entity.
- `keepIT/keepITCore/Service/IMediaStorage.cs` — storage port (save / open / delete / delete-note / enumerate).
- `keepIT/keepITCore/Service/DiskMediaStorage.cs` — the on-disk adapter under `App__DataRoot`.
- `keepIT/keepITCore/Service/NoteMediaProcessor.cs` — validation, EXIF strip, re-encode, thumbnail, dimensions.
- `keepIT/keepITCore/Service/MediaContentTypes.cs` — extension → content type, used by the streaming endpoint.
- `keepIT/keepITCore/Infrastructure/MediaOptions.cs` — bound limits.
- `keepIT/keepITCore/Notes/Dtos/NoteMediaDto.cs` — the wire shape.
- `keepIT/keepITCore/Notes/NoteMediaController.cs` — the three endpoints.
- `keepIT/keepITCore/Notes/MediaOrphanSweepService.cs` — daily background cleanup.

**Modify:**
- `keepIT/keepITCore/Data/Note.cs` — add the `Media` navigation.
- `keepIT/keepITCore/Data/AppDbContext.cs` — `DbSet`, entity config, cascade.
- `keepIT/keepITCore/Infrastructure/FolderManagement.cs` — `GetNoteMediaFolder`.
- `keepIT/keepITCore/Notes/Dtos/NoteDto.cs` — add `Media`.
- `keepIT/keepITCore/Notes/NotesController.cs` — include media in projections; purge files on hard delete.
- `keepIT/keepITCore/Service/ImageService.cs` — expose the magic-byte check for reuse.
- `keepIT/keepITCore/Program.cs` — DI registrations and the hosted service.
- `keepIT/keepITCore/keepITCore.csproj` — ImageSharp package reference.

---

### Task 1: The `NoteMedia` entity and its migration

**Files:**
- Create: `keepIT/keepITCore/Data/NoteMedia.cs`
- Modify: `keepIT/keepITCore/Data/Note.cs`, `keepIT/keepITCore/Data/AppDbContext.cs`
- Create (generated): `keepIT/keepITCore/Data/Migrations/<timestamp>_NoteMedia.cs`

**Interfaces:**
- Consumes: nothing.
- Produces: `NoteMedia` entity with `Id`, `NoteId`, `FileName`, `ThumbFileName`, `Width`, `Height`, `ByteSize`, `Order`, `CreatedAtUtc`; `Note.Media`; `AppDbContext.NoteMedia`.

- [ ] **Step 1: Create the entity**

```csharp
namespace keepITCore.Data;

/// <summary>
/// One image attached to a <see cref="Note"/>. Bytes live on disk under the data root (never in the
/// database — ARCHITECTURE.md "Profile images &amp; media"); this row is the metadata plus the
/// storage key. Media is owned <em>through</em> the note, so there is no separate owner column: the
/// note's <see cref="Note.OwnerId"/> decides both the on-disk folder and who may purge it.
/// <para>The collection is append-only, which is why media needs no conflict resolution: two
/// devices attaching at once both succeed and order by <see cref="CreatedAtUtc"/>.</para>
/// </summary>
public class NoteMedia
{
    /// <summary>The public id and the storage key — a client filename never reaches the disk.</summary>
    public Guid Id { get; set; }

    /// <summary>The note this image is attached to.</summary>
    public Guid NoteId { get; set; }

    /// <summary>Navigation to the owning note.</summary>
    public Note Note { get; set; } = null!;

    /// <summary>Stored file name, always <c>{Id}.{ext}</c>. Stored rather than derived so the
    /// response content type is one lookup.</summary>
    public string FileName { get; set; } = null!;

    /// <summary>Thumbnail file name, always <c>{Id}_thumb.{ext}</c>. Held separately because the
    /// thumbnail's encoding need not match the source (an animated GIF thumbnails to a still).</summary>
    public string ThumbFileName { get; set; } = null!;

    /// <summary>Pixel width of the stored original — lets a client reserve the card's box before
    /// the thumbnail arrives, so the notes grid doesn't reflow as images land.</summary>
    public int Width { get; set; }

    /// <summary>Pixel height of the stored original.</summary>
    public int Height { get; set; }

    /// <summary>Size on disk of the stored original, after re-encoding. Makes a future per-user
    /// quota a SUM rather than a migration.</summary>
    public long ByteSize { get; set; }

    /// <summary>Position within the note, ascending. Append-only: a new row takes
    /// <c>max(Order) + 1</c>, or 0 when it is the first.</summary>
    public int Order { get; set; }

    /// <summary>Server-set attach time; the tie-break for concurrent attaches.</summary>
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}
```

- [ ] **Step 2: Add the navigation to `Note`**

In `keepIT/keepITCore/Data/Note.cs`, after the `Reminders` collection:

```csharp
    /// <summary>Image attachments, ordered (see <see cref="NoteMedia"/>).</summary>
    public ICollection<NoteMedia> Media { get; set; } = new List<NoteMedia>();
```

- [ ] **Step 3: Add the `DbSet` and entity configuration**

In `AppDbContext.cs`, next to the other `DbSet` properties (around line 44):

```csharp
    /// <summary>Image attachments on notes (metadata only — bytes live on disk).</summary>
    public DbSet<NoteMedia> NoteMedia => Set<NoteMedia>();
```

Inside the `builder.Entity<Note>(e => { … })` block, alongside the `ChecklistItems` mapping:

```csharp
            e.HasMany(n => n.Media)
                .WithOne(m => m.Note)
                .HasForeignKey(m => m.NoteId)
                .OnDelete(DeleteBehavior.Cascade);
```

And a sibling configuration block, after the `ChecklistItem` one:

```csharp
        builder.Entity<NoteMedia>(e =>
        {
            e.HasKey(m => m.Id);
            e.HasIndex(m => m.NoteId);
            // Explicit lengths: without them Postgres gives these unbounded `text`.
            e.Property(m => m.FileName).HasMaxLength(128).IsRequired();
            e.Property(m => m.ThumbFileName).HasMaxLength(128).IsRequired();
        });
```

- [ ] **Step 4: Generate the migration**

Run:
```bash
dotnet ef migrations add NoteMedia --project keepIT/keepITCore
```
Expected: a new `Migrations/<timestamp>_NoteMedia.cs`.

- [ ] **Step 5: Verify the migration is Postgres-shaped**

Open the generated file and confirm, by eye:
- `Id`, `NoteId` are `type: "uuid"`.
- `FileName`, `ThumbFileName` are `type: "character varying(128)"`, `nullable: false`.
- `Width`, `Height` are `integer`; `ByteSize` is `bigint`; `Order` is `integer`.
- `CreatedAtUtc` is `timestamp with time zone`.
- The FK to `Notes` is `onDelete: ReferentialAction.Cascade`.

If any of these are wrong, fix the entity configuration and regenerate — do not hand-edit the migration.

- [ ] **Step 6: Apply against real Postgres**

This is the step that catches what SQLite hides. Run:
```bash
docker compose up -d db
dotnet ef database update --project keepIT/keepITCore
```
Expected: applies with no error. (`ConnectionStrings__Postgres` must point at the compose database; the design-time factory falls back to `Host=localhost;Port=5432;Database=keepit;Username=keepit;Password=keepit`.)

- [ ] **Step 7: Rebuild the local SQLite schema**

The dev DB is `EnsureCreated` and will not alter an existing file:
```bash
rm -f keepIT/keepITCore/App_Data/keepit.db
dotnet run --project keepIT/keepITCore
```
Expected: starts cleanly. Stop it again.

- [ ] **Step 8: Commit**

```bash
git add keepIT/keepITCore/Data keepIT/keepITCore/Data/Migrations
git commit -m "api: add NoteMedia entity and migration"
```

---

### Task 2: Storage port and disk adapter

**Files:**
- Create: `keepIT/keepITCore/Service/IMediaStorage.cs`, `keepIT/keepITCore/Service/DiskMediaStorage.cs`
- Modify: `keepIT/keepITCore/Infrastructure/FolderManagement.cs`, `keepIT/keepITCore/Program.cs`

**Interfaces:**
- Consumes: `FolderManagement.RootPath`.
- Produces: `IMediaStorage` with `Task<long> SaveAsync(Guid ownerId, Guid noteId, string fileName, Stream content, CancellationToken ct)`, `Stream? OpenRead(Guid ownerId, Guid noteId, string fileName)`, `void Delete(Guid ownerId, Guid noteId, string fileName)`, `void DeleteNote(Guid ownerId, Guid noteId)`, `IReadOnlyList<(Guid OwnerId, Guid NoteId)> EnumerateNoteFolders()`.

- [ ] **Step 1: Add the folder helper**

In `FolderManagement.cs`, after `GetUserProfileImageFolder`:

```csharp
        /// <summary>
        /// The folder holding one note's image attachments, creating it if needed. Keyed by owner so
        /// a user's data stays in one subtree under the data root.
        /// </summary>
        /// <param name="userID">The note owner's id.</param>
        /// <param name="noteID">The note's id.</param>
        /// <returns>The absolute folder path.</returns>
        public static string GetNoteMediaFolder(string userID, string noteID)
        {
            var path = Path.Combine(GetUserFolder(userID), "notes", noteID);
            Directory.CreateDirectory(path);
            return path;
        }
```

- [ ] **Step 2: Define the port**

```csharp
namespace keepITCore.Service;

/// <summary>
/// Where note media bytes live. Controllers depend on this port rather than <c>System.IO</c> so the
/// disk implementation can later be swapped for S3/MinIO without touching a caller
/// (ARCHITECTURE.md "Profile images &amp; media").
/// </summary>
public interface IMediaStorage
{
    /// <summary>Writes one file for a note and returns the bytes written.</summary>
    Task<long> SaveAsync(Guid ownerId, Guid noteId, string fileName, Stream content, CancellationToken ct);

    /// <summary>Opens a stored file for reading, or null when it is missing.</summary>
    Stream? OpenRead(Guid ownerId, Guid noteId, string fileName);

    /// <summary>Best-effort delete of one stored file. Never throws for a missing file.</summary>
    void Delete(Guid ownerId, Guid noteId, string fileName);

    /// <summary>Best-effort delete of a note's whole media folder.</summary>
    void DeleteNote(Guid ownerId, Guid noteId);

    /// <summary>Every (owner, note) folder pair currently on disk — the orphan sweep's input.</summary>
    IReadOnlyList<(Guid OwnerId, Guid NoteId)> EnumerateNoteFolders();
}
```

- [ ] **Step 3: Implement the disk adapter**

```csharp
using keepITCore.Infrastructure;

namespace keepITCore.Service;

/// <summary>
/// <see cref="IMediaStorage"/> over the local data root. File names are always server-generated
/// (<c>{mediaId}.{ext}</c>), so nothing here interpolates client input into a path.
/// </summary>
public class DiskMediaStorage : IMediaStorage
{
    /// <inheritdoc />
    public async Task<long> SaveAsync(Guid ownerId, Guid noteId, string fileName, Stream content, CancellationToken ct)
    {
        var dir = FolderManagement.GetNoteMediaFolder(ownerId.ToString(), noteId.ToString());
        var path = Path.Combine(dir, fileName);

        await using var file = File.Create(path);
        await content.CopyToAsync(file, ct);
        return file.Length;
    }

    /// <inheritdoc />
    public Stream? OpenRead(Guid ownerId, Guid noteId, string fileName)
    {
        var path = Path.Combine(
            FolderManagement.GetNoteMediaFolder(ownerId.ToString(), noteId.ToString()), fileName);
        return File.Exists(path) ? File.OpenRead(path) : null;
    }

    /// <inheritdoc />
    public void Delete(Guid ownerId, Guid noteId, string fileName)
    {
        var path = Path.Combine(
            FolderManagement.GetNoteMediaFolder(ownerId.ToString(), noteId.ToString()), fileName);
        try { File.Delete(path); }
        catch (IOException) { /* best-effort; an orphan file is harmless and the sweep collects it */ }
    }

    /// <inheritdoc />
    public void DeleteNote(Guid ownerId, Guid noteId)
    {
        var dir = Path.Combine(FolderManagement.GetUserFolder(ownerId.ToString()), "notes", noteId.ToString());
        try { if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); }
        catch (IOException) { /* best-effort */ }
    }

    /// <inheritdoc />
    public IReadOnlyList<(Guid OwnerId, Guid NoteId)> EnumerateNoteFolders()
    {
        var results = new List<(Guid, Guid)>();
        var usersRoot = Path.Combine(FolderManagement.RootPath, "users");
        if (!Directory.Exists(usersRoot)) return results;

        foreach (var userDir in Directory.EnumerateDirectories(usersRoot))
        {
            if (!Guid.TryParse(Path.GetFileName(userDir), out var ownerId)) continue;
            var notesDir = Path.Combine(userDir, "notes");
            if (!Directory.Exists(notesDir)) continue;

            foreach (var noteDir in Directory.EnumerateDirectories(notesDir))
                if (Guid.TryParse(Path.GetFileName(noteDir), out var noteId))
                    results.Add((ownerId, noteId));
        }

        return results;
    }
}
```

- [ ] **Step 4: Register it**

In `Program.cs`, beside `builder.Services.AddScoped<ImageService>();` (line ~114):

```csharp
builder.Services.AddSingleton<IMediaStorage, DiskMediaStorage>();
```

- [ ] **Step 5: Build**

Run: `dotnet build keepIT/keepITCore`
Expected: succeeds with no warnings introduced.

- [ ] **Step 6: Commit**

```bash
git add keepIT/keepITCore/Service keepIT/keepITCore/Infrastructure/FolderManagement.cs keepIT/keepITCore/Program.cs
git commit -m "api: add IMediaStorage and disk adapter for note media"
```

---

### Task 3: Image processing — validate, strip EXIF, thumbnail

**Files:**
- Create: `keepIT/keepITCore/Service/NoteMediaProcessor.cs`, `keepIT/keepITCore/Service/MediaContentTypes.cs`
- Modify: `keepIT/keepITCore/Service/ImageService.cs`, `keepIT/keepITCore/keepITCore.csproj`, `keepIT/keepITCore/Program.cs`

**Interfaces:**
- Consumes: `ImageService.LooksLikeImageAsync` (made `internal static`).
- Produces: `NoteMediaProcessor.ProcessAsync(Stream upload, CancellationToken ct)` returning `MediaProcessResult(ProcessedImage? Image, MediaRejection Reason)`; `ProcessedImage(Stream Original, string Extension, int Width, int Height, Stream Thumbnail, string ThumbnailExtension)`; `MediaRejection { None, NotAnImage, Heic, Corrupt }`; `MediaContentTypes.For(string fileName)`.

**Before starting:** confirm the ImageSharp licence (Six Labors Split License — free for open-source/personal use, paid above a revenue threshold) is acceptable for this project. If it is not, stop and raise it; the fallback is client-side downscaling, which changes this task and both client plans.

- [ ] **Step 1: Add the package**

Run:
```bash
dotnet add keepIT/keepITCore package SixLabors.ImageSharp
```
Expected: a `<PackageReference Include="SixLabors.ImageSharp" …>` appears in `keepITCore.csproj`. It is fully managed — no native libraries enter the Docker image.

- [ ] **Step 2: Expose the existing magic-byte check**

In `ImageService.cs`, change the signature of the private helper so the new processor can reuse it rather than duplicating the signature table:

```csharp
    internal static async Task<bool> LooksLikeImageAsync(Stream stream, CancellationToken ct)
```

Leave the body and all existing callers unchanged.

- [ ] **Step 3: Add the content-type map**

```csharp
namespace keepITCore.Service;

/// <summary>Maps a stored file's extension to the content type its response should carry.</summary>
public static class MediaContentTypes
{
    /// <summary>The content type for a stored media file name, defaulting to a safe binary type.</summary>
    /// <param name="fileName">The stored file name (server-generated, so the extension is trusted).</param>
    /// <returns>An image content type, or <c>application/octet-stream</c> when unrecognised.</returns>
    public static string For(string fileName) => Path.GetExtension(fileName).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        _ => "application/octet-stream",
    };
}
```

- [ ] **Step 4: Write the processor**

```csharp
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats.Gif;
using SixLabors.ImageSharp.Formats.Jpeg;
using SixLabors.ImageSharp.Processing;

namespace keepITCore.Service;

/// <summary>Why an upload was refused; maps to the controller's status code and message.</summary>
public enum MediaRejection
{
    /// <summary>Accepted.</summary>
    None,
    /// <summary>Content carries no recognised image signature.</summary>
    NotAnImage,
    /// <summary>A real image, but HEIC — recognised purely so it can be refused by name.</summary>
    Heic,
    /// <summary>A recognised signature that the decoder could not actually read.</summary>
    Corrupt,
}

/// <summary>A processed upload, ready to be written to storage. Both streams are positioned at 0.</summary>
/// <param name="Original">The re-encoded original.</param>
/// <param name="Extension">Extension for the original, including the dot.</param>
/// <param name="Width">Pixel width of the original.</param>
/// <param name="Height">Pixel height of the original.</param>
/// <param name="Thumbnail">The grid thumbnail.</param>
/// <param name="ThumbnailExtension">Extension for the thumbnail, including the dot.</param>
public sealed record ProcessedImage(
    Stream Original, string Extension, int Width, int Height, Stream Thumbnail, string ThumbnailExtension);

/// <summary>The outcome of processing: an image, or the reason it was refused.</summary>
/// <param name="Image">The processed image when <paramref name="Reason"/> is <see cref="MediaRejection.None"/>.</param>
/// <param name="Reason">Why it was refused, if it was.</param>
public sealed record MediaProcessResult(ProcessedImage? Image, MediaRejection Reason);

/// <summary>
/// Turns an uploaded file into the two blobs we store. Originals are re-encoded rather than kept
/// verbatim, because that is the only way to strip metadata: phone photos carry GPS, and a shared
/// note would otherwise hand a collaborator the coordinates of the photographer's home. The
/// accepted trade-off is that pixel-exact originals are not preserved.
/// </summary>
public class NoteMediaProcessor
{
    private const int MaxOriginalEdge = 2560;
    private const int ThumbnailEdge = 400;
    private const int JpegQuality = 88;

    /// <summary>Validates and processes an upload.</summary>
    /// <param name="upload">The uploaded content; must be seekable or already buffered.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The processed image, or the rejection reason.</returns>
    public async Task<MediaProcessResult> ProcessAsync(Stream upload, CancellationToken ct)
    {
        if (await IsHeicAsync(upload, ct))
            return new MediaProcessResult(null, MediaRejection.Heic);

        upload.Position = 0;
        if (!await ImageService.LooksLikeImageAsync(upload, ct))
            return new MediaProcessResult(null, MediaRejection.NotAnImage);

        upload.Position = 0;

        try
        {
            using var image = await Image.LoadAsync(upload, ct);

            // Orientation is applied here; Mutate's AutoOrient plus stripping metadata below means a
            // sideways phone photo displays upright on every client with no client-side EXIF logic.
            image.Mutate(x => x.AutoOrient());
            image.Metadata.ExifProfile = null;
            image.Metadata.XmpProfile = null;
            image.Metadata.IptcProfile = null;

            // Animated GIFs would lose their frames through a resize/JPEG encode, so they are stored
            // as-is and thumbnailed from the first frame only.
            var isGif = image.Metadata.DecodedImageFormat is GifFormat;

            var original = new MemoryStream();
            string extension;

            if (isGif)
            {
                upload.Position = 0;
                await upload.CopyToAsync(original, ct);
                extension = ".gif";
            }
            else
            {
                if (image.Width > MaxOriginalEdge || image.Height > MaxOriginalEdge)
                    image.Mutate(x => x.Resize(new ResizeOptions
                    {
                        Mode = ResizeMode.Max,
                        Size = new Size(MaxOriginalEdge, MaxOriginalEdge),
                    }));

                await image.SaveAsJpegAsync(original, new JpegEncoder { Quality = JpegQuality }, ct);
                extension = ".jpg";
            }

            original.Position = 0;

            using var thumb = image.Clone(x => x.Resize(new ResizeOptions
            {
                Mode = ResizeMode.Max,
                Size = new Size(ThumbnailEdge, ThumbnailEdge),
            }));

            var thumbnail = new MemoryStream();
            await thumb.SaveAsJpegAsync(thumbnail, new JpegEncoder { Quality = JpegQuality }, ct);
            thumbnail.Position = 0;

            return new MediaProcessResult(
                new ProcessedImage(original, extension, image.Width, image.Height, thumbnail, ".jpg"),
                MediaRejection.None);
        }
        catch (Exception ex) when (ex is ImageFormatException or NotSupportedException or InvalidImageContentException)
        {
            return new MediaProcessResult(null, MediaRejection.Corrupt);
        }
    }

    /// <summary>
    /// True when the stream is an ISO-BMFF file with a HEIC brand. Detected only so the endpoint can
    /// refuse it <em>by name</em> — iPhone-on-Safari users hit this constantly, and a generic
    /// "unsupported file type" would baffle them.
    /// </summary>
    private static async Task<bool> IsHeicAsync(Stream stream, CancellationToken ct)
    {
        stream.Position = 0;
        var header = new byte[12];
        var read = await stream.ReadAtLeastAsync(header, header.Length, throwOnEndOfStream: false, ct);
        if (read < 12) return false;

        // Bytes 4..8 are "ftyp"; 8..12 carry the brand.
        if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') return false;

        var brand = System.Text.Encoding.ASCII.GetString(header, 8, 4);
        return brand is "heic" or "heix" or "hevc" or "heim" or "heis" or "mif1" or "msf1";
    }
}
```

- [ ] **Step 5: Register it**

In `Program.cs`, beside the `IMediaStorage` registration:

```csharp
builder.Services.AddScoped<NoteMediaProcessor>();
```

- [ ] **Step 6: Build**

Run: `dotnet build keepIT/keepITCore`
Expected: succeeds.

- [ ] **Step 7: Commit**

```bash
git add keepIT/keepITCore/Service keepIT/keepITCore/keepITCore.csproj keepIT/keepITCore/Program.cs
git commit -m "api: add note media processing (EXIF strip, resize, thumbnail)"
```

---

### Task 4: Put media on the wire

**Files:**
- Create: `keepIT/keepITCore/Notes/Dtos/NoteMediaDto.cs`
- Modify: `keepIT/keepITCore/Notes/Dtos/NoteDto.cs`, `keepIT/keepITCore/Notes/NotesController.cs:54-96` (`GetNotes`), `:434-465` (`ToDto`), `LoadDtoAsync`

**Interfaces:**
- Consumes: `NoteMedia` from Task 1.
- Produces: `NoteMediaDto { Guid Id; int Width; int Height; long ByteSize; int Order; DateTime CreatedAtUtc; }` and `NoteDto.Media` — the shape both client plans generate from.

- [ ] **Step 1: Create the DTO**

```csharp
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
```

- [ ] **Step 2: Add it to `NoteDto`**

In `NoteDto.cs`, after `ChecklistItems`:

```csharp
    /// <summary>Image attachments, ordered. Empty when the note has none.</summary>
    public List<NoteMediaDto> Media { get; set; } = new();
```

- [ ] **Step 3: Project it in `ToDto`**

In `NotesController.ToDto`, after the `ChecklistItems` projection:

```csharp
            Media = n.Media
                .OrderBy(m => m.Order)
                .Select(m => new NoteMediaDto
                {
                    Id = m.Id,
                    Width = m.Width,
                    Height = m.Height,
                    ByteSize = m.ByteSize,
                    Order = m.Order,
                    CreatedAtUtc = m.CreatedAtUtc,
                })
                .ToList(),
```

- [ ] **Step 4: Load media in both read paths**

`ToDto` projects only what was `Include`d, so both queries need the new navigation or every note reports zero images.

In `GetNotes`, add to the `Include` chain:

```csharp
            .Include(us => us.Note).ThenInclude(n => n.Media)
```

In `LoadDtoAsync`, add to its `Include` chain:

```csharp
            .Include(n => n.Media)
```

- [ ] **Step 5: Build and verify the contract**

Run:
```bash
dotnet build keepIT/keepITCore
dotnet run --project keepIT/keepITCore
```
Open `http://localhost:5025/scalar/v1`, find `GET /api/notes`, and confirm the `NoteDto` schema now lists `media` as an array of `NoteMediaDto`. Stop the server.

- [ ] **Step 6: Commit**

```bash
git add keepIT/keepITCore/Notes
git commit -m "api: expose note media on NoteDto"
```

---

### Task 5: The media endpoints

**Files:**
- Create: `keepIT/keepITCore/Notes/NoteMediaController.cs`, `keepIT/keepITCore/Infrastructure/MediaOptions.cs`
- Modify: `keepIT/keepITCore/Program.cs`, `keepIT/keepITCore/appsettings.json`

**Interfaces:**
- Consumes: `IMediaStorage`, `NoteMediaProcessor`, `MediaContentTypes`, `NoteAccessService`, `IRealtimeNotifier`, `NoteMediaDto`.
- Produces: `POST /api/notes/{noteId}/media`, `DELETE /api/notes/{noteId}/media/{mediaId}`, `GET /api/notes/{noteId}/media/{mediaId}?size=thumb|full` — the endpoints both client plans call.

- [ ] **Step 1: Add the options type**

```csharp
namespace keepITCore.Infrastructure;

/// <summary>Configurable limits for note image attachments, bound from <c>App:Media</c>.</summary>
public class MediaOptions
{
    /// <summary>Largest accepted upload, in bytes. Checked before any processing.</summary>
    public long MaxImageBytes { get; set; } = 10 * 1024 * 1024;

    /// <summary>How many images one note may hold.</summary>
    public int MaxImagesPerNote { get; set; } = 10;
}
```

- [ ] **Step 2: Bind and default it**

In `Program.cs`, near the other configuration binding:

```csharp
builder.Services.Configure<MediaOptions>(builder.Configuration.GetSection("App:Media"));
```

In `appsettings.json`, inside the existing `"App"` object:

```json
    "Media": {
      "MaxImageBytes": 10485760,
      "MaxImagesPerNote": 10
    }
```

- [ ] **Step 3: Write the controller**

```csharp
using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Notes.Dtos;
using keepITCore.Service;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace keepITCore.Notes;

/// <summary>
/// Image attachments on a note. Media is a sub-resource of the note, so authorization is one
/// <see cref="NoteAccessService"/> call on the parent: reading bytes needs any access, attaching and
/// removing need Editor access. A collaborator reaches a shared note's images through the note, never
/// by owning the media.
/// </summary>
[ApiController]
[Authorize]
[Route("api/notes/{noteId:guid}/media")]
public class NoteMediaController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IMediaStorage _storage;
    private readonly NoteMediaProcessor _processor;
    private readonly NoteAccessService _access;
    private readonly IRealtimeNotifier _notifier;
    private readonly MediaOptions _options;

    /// <summary>Injects the context, storage, processor, access resolver, notifier and limits.</summary>
    public NoteMediaController(
        AppDbContext db,
        IMediaStorage storage,
        NoteMediaProcessor processor,
        NoteAccessService access,
        IRealtimeNotifier notifier,
        IOptions<MediaOptions> options)
    {
        _db = db;
        _storage = storage;
        _processor = processor;
        _access = access;
        _notifier = notifier;
        _options = options.Value;
    }

    /// <summary>
    /// Attaches one image to a note. One file per request: a multi-select loops client-side so each
    /// image carries its own progress and its own failure.
    /// </summary>
    /// <param name="noteId">The note to attach to.</param>
    /// <param name="file">The image (jpg/png/webp/gif).</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>201 with the created media, 400/409/413 on a refused upload, 403 for a viewer, 404 without access.</returns>
    [HttpPost]
    [RequestSizeLimit(12 * 1024 * 1024)] // 10 MB payload + multipart overhead headroom
    [RequestFormLimits(MultipartBodyLengthLimit = 12 * 1024 * 1024)]
    public async Task<ActionResult<NoteMediaDto>> Upload(Guid noteId, IFormFile file, CancellationToken ct)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.CanEdit) return Forbid();

        if (file is null || file.Length == 0) return BadRequest("No file provided.");
        if (file.Length > _options.MaxImageBytes)
            return StatusCode(StatusCodes.Status413PayloadTooLarge,
                $"Image too large (max {_options.MaxImageBytes / (1024 * 1024)} MB).");

        var existing = await _db.NoteMedia.CountAsync(m => m.NoteId == noteId, ct);
        if (existing >= _options.MaxImagesPerNote)
            return Conflict($"This note already has {_options.MaxImagesPerNote} images.");

        // Buffer so the processor can seek: it reads the header, then decodes from the start.
        await using var buffer = new MemoryStream();
        await file.CopyToAsync(buffer, ct);
        buffer.Position = 0;

        var result = await _processor.ProcessAsync(buffer, ct);
        if (result.Image is null)
            return BadRequest(result.Reason switch
            {
                MediaRejection.Heic =>
                    "HEIC images aren't supported yet. On iPhone, set Camera → Formats to "
                    + "\"Most Compatible\", or share the photo as JPEG.",
                MediaRejection.Corrupt => "That image couldn't be read.",
                _ => "The file is not a valid image.",
            });

        var image = result.Image;
        var ownerId = await _db.Notes.Where(n => n.Id == noteId).Select(n => n.OwnerId).FirstAsync(ct);

        var mediaId = Guid.NewGuid();
        var fileName = $"{mediaId:N}{image.Extension}";
        var thumbName = $"{mediaId:N}_thumb{image.ThumbnailExtension}";

        // Bytes first, row second: a row pointing at missing bytes breaks rendering on every client,
        // whereas a file with no row is invisible and the orphan sweep collects it.
        var byteSize = await _storage.SaveAsync(ownerId, noteId, fileName, image.Original, ct);
        await _storage.SaveAsync(ownerId, noteId, thumbName, image.Thumbnail, ct);

        var maxOrder = await _db.NoteMedia.Where(m => m.NoteId == noteId)
            .Select(m => (int?)m.Order).MaxAsync(ct);

        var media = new NoteMedia
        {
            Id = mediaId,
            NoteId = noteId,
            FileName = fileName,
            ThumbFileName = thumbName,
            Width = image.Width,
            Height = image.Height,
            ByteSize = byteSize,
            Order = (maxOrder ?? -1) + 1,
            CreatedAtUtc = DateTime.UtcNow,
        };

        _db.NoteMedia.Add(media);

        try
        {
            await _db.SaveChangesAsync(ct);
        }
        catch
        {
            // Compensate so we never leave bytes the database doesn't know about.
            _storage.Delete(ownerId, noteId, fileName);
            _storage.Delete(ownerId, noteId, thumbName);
            throw;
        }

        await NotifyRecipientsAsync(noteId);

        return CreatedAtAction(nameof(Get), new { noteId, mediaId }, ToDto(media));
    }

    /// <summary>Streams one image. Any read access to the note is enough.</summary>
    /// <param name="noteId">The note.</param>
    /// <param name="mediaId">The media item.</param>
    /// <param name="size">"thumb" for the grid thumbnail; anything else serves the original.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>200 with the bytes, or 404 when it is missing or the caller has no access.</returns>
    [HttpGet("{mediaId:guid}")]
    public async Task<IActionResult> Get(Guid noteId, Guid mediaId, [FromQuery] string? size, CancellationToken ct)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        // Same 404 for "no access" and "doesn't exist" — the non-enumeration stance.
        if (await _access.ResolveAsync(noteId, callerId.Value) is null) return NotFound();

        var media = await _db.NoteMedia.AsNoTracking()
            .FirstOrDefaultAsync(m => m.Id == mediaId && m.NoteId == noteId, ct);
        if (media is null) return NotFound();

        var ownerId = await _db.Notes.Where(n => n.Id == noteId).Select(n => n.OwnerId).FirstAsync(ct);
        var wantThumb = string.Equals(size, "thumb", StringComparison.OrdinalIgnoreCase);
        var fileName = wantThumb ? media.ThumbFileName : media.FileName;

        var stream = _storage.OpenRead(ownerId, noteId, fileName);
        if (stream is null) return NotFound();

        // A media id's bytes never change, so this cache directive is honest.
        Response.Headers.CacheControl = "private, max-age=31536000, immutable";
        return File(stream, MediaContentTypes.For(fileName), enableRangeProcessing: true);
    }

    /// <summary>Removes one image from a note. Editor access required.</summary>
    /// <param name="noteId">The note.</param>
    /// <param name="mediaId">The media item.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>204 on success, 403 for a viewer, 404 without access or when it doesn't exist.</returns>
    [HttpDelete("{mediaId:guid}")]
    public async Task<IActionResult> Delete(Guid noteId, Guid mediaId, CancellationToken ct)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.CanEdit) return Forbid();

        var media = await _db.NoteMedia.FirstOrDefaultAsync(m => m.Id == mediaId && m.NoteId == noteId, ct);
        if (media is null) return NotFound();

        var ownerId = await _db.Notes.Where(n => n.Id == noteId).Select(n => n.OwnerId).FirstAsync(ct);

        // Capture the names before the row goes — after SaveChanges they're unrecoverable.
        var fileName = media.FileName;
        var thumbName = media.ThumbFileName;

        _db.NoteMedia.Remove(media);
        await _db.SaveChangesAsync(ct);

        _storage.Delete(ownerId, noteId, fileName);
        _storage.Delete(ownerId, noteId, thumbName);

        await NotifyRecipientsAsync(noteId);
        return NoContent();
    }

    // ---- helpers ----

    /// <summary>Tells the owner and every collaborator that this note's content changed.</summary>
    private async Task NotifyRecipientsAsync(Guid noteId)
    {
        var recipients = await _access.RecipientIdsAsync(noteId);
        await Task.WhenAll(recipients.Select(uid => _notifier.NotifyAsync(uid, RealtimeResources.Notes)));
    }

    /// <summary>Projects a stored row onto the wire shape.</summary>
    private static NoteMediaDto ToDto(NoteMedia m) => new()
    {
        Id = m.Id,
        Width = m.Width,
        Height = m.Height,
        ByteSize = m.ByteSize,
        Order = m.Order,
        CreatedAtUtc = m.CreatedAtUtc,
    };
}
```

- [ ] **Step 4: Build**

Run: `dotnet build keepIT/keepITCore`
Expected: succeeds.

- [ ] **Step 5: Verify the happy path by hand**

Start the API (`dotnet run --project keepIT/keepITCore`), sign in through Scalar at `http://localhost:5025/scalar/v1` to get a token, create a note, then:

```bash
curl -i -X POST http://localhost:5025/api/notes/<noteId>/media \
  -H "Authorization: Bearer <token>" -F "file=@some-photo.jpg"
```
Expected: `201`, a JSON body with `id`, non-zero `width`/`height`/`byteSize`, `order: 0`.

Then `GET /api/notes/<noteId>` and confirm the note's `media` array contains it, and:
```bash
curl -i "http://localhost:5025/api/notes/<noteId>/media/<mediaId>?size=thumb" -H "Authorization: Bearer <token>" -o thumb.jpg
```
Expected: `200`, `Content-Type: image/jpeg`, `Cache-Control: private, max-age=31536000, immutable`, and `thumb.jpg` opens with its long edge at 400px.

- [ ] **Step 6: Verify the refusals**

With the same token and note:
- Upload a `.txt` renamed to `.jpg` → expect `400 "The file is not a valid image."`
- Upload a HEIC photo → expect `400` with the HEIC-specific message.
- Upload an 11th image → expect `409 "This note already has 10 images."`
- Upload a >10 MB image → expect `413`.

- [ ] **Step 7: Verify the share matrix**

This is the check that matters most — a mistake here leaks one user's photos to another account. With a second account, and a note shared from account A to account B:

| As | Action | Expected |
|---|---|---|
| B with a **Viewer** grant | `GET` the bytes | 200 |
| B with a **Viewer** grant | `POST` a new image | 403 |
| B with a **Viewer** grant | `DELETE` an image | 403 |
| B with an **Editor** grant | `POST` / `DELETE` | 201 / 204 |
| C with **no** grant | `GET`, `POST`, `DELETE` | 404 on all three |

Also confirm that while B has the note open, A attaching an image causes B's client to receive a `Changed` signal naming `notes`.

- [ ] **Step 8: Verify EXIF is gone**

Upload a geotagged photo, then fetch the stored original and inspect it:
```bash
curl -s "http://localhost:5025/api/notes/<noteId>/media/<mediaId>" -H "Authorization: Bearer <token>" -o out.jpg
exiftool out.jpg | grep -i -E "gps|orientation" || echo "no GPS/orientation metadata"
```
Expected: no GPS tags. Also confirm a photo shot sideways displays upright.

- [ ] **Step 9: Commit**

```bash
git add keepIT/keepITCore/Notes keepIT/keepITCore/Infrastructure/MediaOptions.cs keepIT/keepITCore/Program.cs keepIT/keepITCore/appsettings.json
git commit -m "api: add note media upload, download and delete endpoints"
```

---

### Task 6: Purge media when a note is hard-deleted

**Files:**
- Modify: `keepIT/keepITCore/Notes/NotesController.cs:345-370` (`Delete`)

**Interfaces:**
- Consumes: `IMediaStorage.DeleteNote`.
- Produces: no new surface.

- [ ] **Step 1: Inject the storage port**

Add a field and constructor parameter to `NotesController` (which currently takes `AppDbContext`, `IRealtimeNotifier`, `NoteAccessService`):

```csharp
    private readonly IMediaStorage _media;
```

Append `IMediaStorage media` to the constructor parameters, assign `_media = media;`, and extend the constructor's XML docs with:

```csharp
    /// <param name="media">Storage port, used to purge a deleted note's images.</param>
```

Add `using keepITCore.Service;` to the file's usings.

- [ ] **Step 2: Delete the folder after the note goes**

In `Delete`, after `await _db.SaveChangesAsync();` and before the notifier fan-out:

```csharp
        // Cascade removed the rows (and with them the file names), so the whole folder goes.
        _media.DeleteNote(note.OwnerId, note.Id);
```

- [ ] **Step 3: Build**

Run: `dotnet build keepIT/keepITCore`
Expected: succeeds.

- [ ] **Step 4: Verify**

Attach two images to a note, note the folder `App_Data/users/<ownerId>/notes/<noteId>/` exists and holds four files (two originals, two thumbs), then `DELETE /api/notes/<noteId>`.
Expected: 204, and the folder is gone.

- [ ] **Step 5: Commit**

```bash
git add keepIT/keepITCore/Notes/NotesController.cs
git commit -m "api: purge note media folder on hard delete"
```

---

### Task 7: The orphan sweep

**Files:**
- Create: `keepIT/keepITCore/Notes/MediaOrphanSweepService.cs`
- Modify: `keepIT/keepITCore/Program.cs`

**Interfaces:**
- Consumes: `IMediaStorage.EnumerateNoteFolders`, `IMediaStorage.DeleteNote`.
- Produces: a registered `IHostedService`.

- [ ] **Step 1: Write the service**

```csharp
using keepITCore.Data;
using keepITCore.Service;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// Removes note media folders with no matching note. Media writes bytes before the database row
/// (so a client never sees a row pointing at missing bytes), which means a crash between the two
/// leaves a file nothing references. This is the safety net for that, plus any folder left behind by
/// a best-effort delete that failed.
/// </summary>
public class MediaOrphanSweepService : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromHours(24);
    private static readonly TimeSpan StartupDelay = TimeSpan.FromMinutes(5);

    private readonly IServiceScopeFactory _scopes;
    private readonly IMediaStorage _storage;
    private readonly ILogger<MediaOrphanSweepService> _log;

    /// <summary>Injects the scope factory (for a scoped DbContext), storage and logger.</summary>
    public MediaOrphanSweepService(
        IServiceScopeFactory scopes, IMediaStorage storage, ILogger<MediaOrphanSweepService> log)
    {
        _scopes = scopes;
        _storage = storage;
        _log = log;
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Don't compete with startup; nothing here is urgent.
        try { await Task.Delay(StartupDelay, stoppingToken); }
        catch (OperationCanceledException) { return; }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await SweepAsync(stoppingToken);
            }
            catch (Exception ex)
            {
                // Never let a sweep failure take the host down — it retries tomorrow.
                _log.LogError(ex, "Media orphan sweep failed.");
            }

            try { await Task.Delay(Interval, stoppingToken); }
            catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Deletes every note media folder whose note no longer exists.</summary>
    private async Task SweepAsync(CancellationToken ct)
    {
        var folders = _storage.EnumerateNoteFolders();
        if (folders.Count == 0) return;

        using var scope = _scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

        var noteIds = folders.Select(f => f.NoteId).ToList();
        var live = await db.Notes.AsNoTracking()
            .Where(n => noteIds.Contains(n.Id))
            .Select(n => n.Id)
            .ToListAsync(ct);

        var liveSet = live.ToHashSet();
        var removed = 0;

        foreach (var (ownerId, noteId) in folders)
        {
            if (liveSet.Contains(noteId)) continue;
            _storage.DeleteNote(ownerId, noteId);
            removed++;
        }

        if (removed > 0)
            _log.LogInformation("Media orphan sweep removed {Count} folder(s).", removed);
    }
}
```

- [ ] **Step 2: Register it**

In `Program.cs`, beside `builder.Services.AddHostedService<keepITCore.Notes.ReminderDispatcherService>();`:

```csharp
builder.Services.AddHostedService<keepITCore.Notes.MediaOrphanSweepService>();
```

- [ ] **Step 3: Build**

Run: `dotnet build keepIT/keepITCore`
Expected: succeeds.

- [ ] **Step 4: Verify**

Temporarily change `StartupDelay` to `TimeSpan.FromSeconds(5)`. Create `App_Data/users/<a real owner guid>/notes/<a random guid>/junk.jpg` by hand, start the API, and watch the log.
Expected: `Media orphan sweep removed 1 folder(s).` and the folder is gone, while a folder belonging to a real note is untouched. **Restore `StartupDelay` to 5 minutes before committing.**

- [ ] **Step 5: Commit**

```bash
git add keepIT/keepITCore/Notes/MediaOrphanSweepService.cs keepIT/keepITCore/Program.cs
git commit -m "api: sweep orphaned note media folders daily"
```

---

### Task 8: Documentation

**Files:**
- Modify: `ARCHITECTURE.md`, `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: docs that match the code.

- [ ] **Step 1: Update `ARCHITECTURE.md`**

In the *Profile images & media* section, replace the **Planned: note media** block with an implemented description covering: the `NoteMedia` entity, the `IMediaStorage` port and its disk layout `{DataRoot}/users/{ownerId}/notes/{noteId}/`, the three endpoints, server-side re-encode/EXIF-strip/thumbnail, the 10 MB and 10-image limits, and the daily orphan sweep. Keep the four original rules (never bytes in the DB; storage keys not user filenames; access-checked serving through the note; lifecycle purge + sweep) and mark them satisfied.

In *Note functions (product definition)*, update item 3 so image attachments are described as implemented on any note, and note that a distinct `NoteType.Image` was deliberately not added.

In *Status & roadmap*, remove "🖼️ Image notes & note media" from the remaining roadmap.

- [ ] **Step 2: Update `README.md`**

Remove the image-notes entry from "What's next" and add images to the feature list.

- [ ] **Step 3: Update `CLAUDE.md`**

In the Layout tree, the `Notes/` line already covers the new controller; extend its comment to mention `NoteMediaController` and add `Service/` with `IMediaStorage`/`NoteMediaProcessor` if it isn't listed.

- [ ] **Step 4: Commit**

```bash
git add ARCHITECTURE.md README.md CLAUDE.md
git commit -m "docs: record note media as implemented"
```

---

## Done when

- `dotnet build keepIT/keepITCore` is clean.
- The migration applies to a real Postgres database.
- All of Task 5's manual checks pass, including the full share matrix and the EXIF check.
- `GET /api/notes` returns `media` on every note.
- The next plan (`2026-09-16-note-media-web.md`) can run `npm run generate:api` against this API and get `NoteMediaDto`.
