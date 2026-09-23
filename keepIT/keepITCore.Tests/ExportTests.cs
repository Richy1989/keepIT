using System.IO.Compression;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// The export archive through the real endpoint: that it is a readable zip, that its manifest is
/// the same DTO shape the API serves, that image originals come with it, and that it stops at what
/// the caller owns.
/// <para>
/// These are the tests that keep the archive format honest. The manifest is defined as "the DTOs
/// the API already sends", so a DTO change that nobody mirrors into the archive shows up here.
/// </para>
/// </summary>
public sealed class ExportTests
{
    // Every test stands up its own API host. The export limiter partitions by client IP and all
    // in-process hosts answer on loopback, so a shared host would let one test spend another's
    // budget — which is exactly how the limit was first noticed here. Hosts run one at a time
    // anyway (see AssemblyInfo), and the suite is small enough that this costs seconds.

    /// <summary>Downloads the export and opens it as a zip.</summary>
    private static async Task<ZipArchive> ExportAsync(HttpClient client)
    {
        var response = await client.GetAsync("/api/export");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/zip", response.Content.Headers.ContentType?.MediaType);

        // Buffered into memory deliberately: ZipArchive needs to seek to the central directory,
        // and a test account's archive is tiny.
        var bytes = await response.Content.ReadAsByteArrayAsync();
        return new ZipArchive(new MemoryStream(bytes), ZipArchiveMode.Read);
    }

    /// <summary>Reads and parses the archive's manifest.</summary>
    private static async Task<JsonElement> ManifestAsync(ZipArchive zip)
    {
        var entry = zip.GetEntry("keepit-export.json");
        Assert.NotNull(entry);
        await using var stream = entry!.Open();
        return (await JsonSerializer.DeserializeAsync<JsonElement>(stream))!;
    }

    private static async Task<string> NewNoteAsync(HttpClient client, object body)
    {
        var response = await client.PostAsJsonAsync("/api/notes", body);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static JsonElement[] NotesIn(JsonElement manifest) =>
        manifest.GetProperty("notes").EnumerateArray().ToArray();

    private static JsonElement NoteWithTitle(JsonElement manifest, string title) =>
        Assert.Single(NotesIn(manifest), n => n.GetProperty("title").GetString() == title);

    /// <summary>The manifest carries the envelope, the caller's lists, and their notes in DTO shape.</summary>
    [Fact]
    public async Task Manifest_carries_the_callers_notes_and_lists()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var listResponse = await client.PostAsJsonAsync("/api/lists", new { name = "Groceries" });
        listResponse.EnsureSuccessStatusCode();
        var listId = (await listResponse.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;

        await NewNoteAsync(client, new { type = "Text", title = "plain", body = "hello" });
        await NewNoteAsync(client, new
        {
            type = "Checklist",
            title = "shopping",
            listIds = new[] { listId },
            checklistItems = new[]
            {
                new { text = "milk", isChecked = false },
                new { text = "eggs", isChecked = true },
            },
        });

        using var zip = await ExportAsync(client);
        var manifest = await ManifestAsync(zip);

        Assert.Equal(1, manifest.GetProperty("schemaVersion").GetInt32());
        Assert.False(string.IsNullOrWhiteSpace(manifest.GetProperty("appVersion").GetString()));
        Assert.NotEqual(default, manifest.GetProperty("exportedAtUtc").GetDateTime());

        var list = Assert.Single(manifest.GetProperty("lists").EnumerateArray());
        Assert.Equal("Groceries", list.GetProperty("name").GetString());
        Assert.Equal(1, list.GetProperty("noteCount").GetInt32());

        Assert.Equal(2, NotesIn(manifest).Length);

        var plain = NoteWithTitle(manifest, "plain");
        Assert.Equal("hello", plain.GetProperty("body").GetString());
        // The enum must be its string name, not a number — the same contract the clients read.
        Assert.Equal("Text", plain.GetProperty("type").GetString());
        Assert.True(plain.GetProperty("isOwner").GetBoolean());

        var shopping = NoteWithTitle(manifest, "shopping");
        Assert.Equal("Checklist", shopping.GetProperty("type").GetString());
        var items = shopping.GetProperty("checklistItems").EnumerateArray().ToArray();
        Assert.Equal(new[] { "milk", "eggs" }, items.Select(i => i.GetProperty("text").GetString()));
        Assert.Equal(new[] { false, true }, items.Select(i => i.GetProperty("isChecked").GetBoolean()));
        Assert.Equal(listId, Assert.Single(shopping.GetProperty("listIds").EnumerateArray()).GetString());
    }

    /// <summary>A backup that dropped the trash would not be a backup: both views come along, flagged.</summary>
    [Fact]
    public async Task Archived_and_trashed_notes_are_exported_with_their_flags()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var archivedId = await NewNoteAsync(client, new { type = "Text", title = "archived" });
        var trashedId = await NewNoteAsync(client, new { type = "Text", title = "trashed" });
        var pinnedId = await NewNoteAsync(client, new { type = "Text", title = "pinned" });

        (await client.PatchAsJsonAsync($"/api/notes/{archivedId}/state", new { isArchived = true }))
            .EnsureSuccessStatusCode();
        (await client.PatchAsJsonAsync($"/api/notes/{trashedId}/state", new { isTrashed = true }))
            .EnsureSuccessStatusCode();
        (await client.PatchAsJsonAsync($"/api/notes/{pinnedId}/state", new { isPinned = true }))
            .EnsureSuccessStatusCode();

        using var zip = await ExportAsync(client);
        var manifest = await ManifestAsync(zip);

        Assert.Equal(3, NotesIn(manifest).Length);
        Assert.True(NoteWithTitle(manifest, "archived").GetProperty("isArchived").GetBoolean());
        Assert.True(NoteWithTitle(manifest, "trashed").GetProperty("isTrashed").GetBoolean());
        Assert.True(NoteWithTitle(manifest, "pinned").GetProperty("isPinned").GetBoolean());
    }

    /// <summary>Image originals travel with the archive, at a path the importer can resolve from the ids.</summary>
    [Fact]
    public async Task Image_originals_are_in_the_archive_under_their_note()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var noteId = await NewNoteAsync(client, new { type = "Text", title = "with a photo" });
        var upload = await NoteMediaTests.UploadAsync(client, noteId, TestImages.Png(64, 48), "photo.png", "image/png");
        Assert.Equal(HttpStatusCode.Created, upload.StatusCode);
        var mediaId = (await upload.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;

        using var zip = await ExportAsync(client);
        var manifest = await ManifestAsync(zip);

        var media = Assert.Single(NoteWithTitle(manifest, "with a photo").GetProperty("media").EnumerateArray());
        Assert.Equal(mediaId, media.GetProperty("id").GetString());
        Assert.Equal(64, media.GetProperty("width").GetInt32());
        Assert.Equal(48, media.GetProperty("height").GetInt32());

        // The importer locates a file by note id + media id; the extension comes off the entry name.
        var prefix = $"media/{noteId}/{Guid.Parse(mediaId):N}";
        var entry = Assert.Single(zip.Entries, e => e.FullName.StartsWith(prefix, StringComparison.Ordinal));
        Assert.Equal(media.GetProperty("byteSize").GetInt64(), entry.Length);

        await using var stream = entry.Open();
        using var buffer = new MemoryStream();
        await stream.CopyToAsync(buffer);
        Assert.Equal((64, 48), TestImages.SizeOf(buffer.ToArray()));

        // Thumbnails are derived and regenerated on import — shipping them would double the archive.
        Assert.DoesNotContain(zip.Entries, e => e.FullName.Contains("_thumb", StringComparison.Ordinal));
    }

    /// <summary>
    /// The export is owner-scoped, unlike every other read. A note shared with the caller shows in
    /// their grid but must not land in a file they keep after the owner revokes the share.
    /// </summary>
    [Fact]
    public async Task Notes_shared_with_the_caller_are_not_exported()
    {
        var granteeEmail = $"grantee-{Guid.NewGuid():N}@example.com";

        using var api = new KeepItApiFactory();
        using var owner = await api.CreateSignedInClientAsync();
        using var grantee = await api.CreateSignedInClientAsync(granteeEmail);

        var sharedId = await NewNoteAsync(owner, new { type = "Text", title = "owners note" });
        (await owner.PostAsJsonAsync($"/api/notes/{sharedId}/shares", new { email = granteeEmail, role = "Editor" }))
            .EnsureSuccessStatusCode();

        var invites = await grantee.GetFromJsonAsync<JsonElement>("/api/notifications");
        var inviteId = invites.EnumerateArray().First().GetProperty("id").GetString()!;
        (await grantee.PostAsJsonAsync($"/api/notifications/{inviteId}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        // It really is in their grid...
        var grid = await grantee.GetFromJsonAsync<JsonElement>("/api/notes");
        Assert.Contains(grid.EnumerateArray(), n => n.GetProperty("id").GetString() == sharedId);

        // ...and really is not in their export.
        using var granteeZip = await ExportAsync(grantee);
        var granteeManifest = await ManifestAsync(granteeZip);
        Assert.Empty(NotesIn(granteeManifest));

        // The owner still exports it, and it is marked as shared.
        using var ownerZip = await ExportAsync(owner);
        var ownerManifest = await ManifestAsync(ownerZip);
        var exported = Assert.Single(NotesIn(ownerManifest));
        Assert.Equal(sharedId, exported.GetProperty("id").GetString());
        Assert.True(exported.GetProperty("isShared").GetBoolean());
    }

    /// <summary>
    /// The export budget is real and is enforced. Export is the most expensive request the app
    /// serves, so losing the limiter would turn one endpoint into a way to pin the server; this
    /// test fails if the policy is ever dropped from the controller.
    /// </summary>
    [Fact]
    public async Task Export_is_rate_limited()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        // The policy allows five in a five-minute window.
        for (var i = 0; i < 5; i++)
            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/export")).StatusCode);

        var refused = await client.GetAsync("/api/export");
        Assert.Equal(HttpStatusCode.TooManyRequests, refused.StatusCode);
        Assert.NotNull(refused.Headers.RetryAfter);
    }

    /// <summary>Export is authenticated like every other note read.</summary>
    [Fact]
    public async Task Export_requires_a_session()
    {
        using var api = new KeepItApiFactory();
        using var anonymous = api.CreateClient();

        var response = await anonymous.GetAsync("/api/export");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }
}
