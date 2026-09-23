using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Import, and the round trip that is the whole point of the format: what export writes, import
/// reads back. These tests are what stop a DTO change quietly breaking someone's backup — a field
/// that stops surviving the trip fails here rather than the next time a user restores.
/// <para>
/// Each test stands up its own API host, because the export and import limiters partition by client
/// IP and every in-process host answers on loopback.
/// </para>
/// </summary>
public sealed class ImportTests
{
    /// <summary>Downloads the caller's export as raw bytes.</summary>
    private static async Task<byte[]> ExportBytesAsync(HttpClient client)
    {
        var response = await client.GetAsync("/api/export");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadAsByteArrayAsync();
    }

    /// <summary>Uploads an archive to the import endpoint.</summary>
    private static async Task<HttpResponseMessage> ImportAsync(HttpClient client, byte[] archive)
    {
        using var form = new MultipartFormDataContent();
        var file = new ByteArrayContent(archive);
        file.Headers.ContentType = new MediaTypeHeaderValue("application/zip");
        form.Add(file, "file", "keepit-export.zip");
        return await client.PostAsync("/api/import", form);
    }

    /// <summary>Uploads an archive that must succeed, returning the import result.</summary>
    private static async Task<JsonElement> ImportOkAsync(HttpClient client, byte[] archive)
    {
        var response = await ImportAsync(client, archive);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    /// <summary>Builds a zip in memory from a manifest and any number of extra entries.</summary>
    private static byte[] BuildArchive(string manifestJson, params (string Path, byte[] Bytes)[] files)
    {
        using var buffer = new MemoryStream();
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            if (manifestJson.Length > 0)
            {
                var entry = zip.CreateEntry("keepit-export.json");
                using var stream = entry.Open();
                stream.Write(Encoding.UTF8.GetBytes(manifestJson));
            }

            foreach (var (path, bytes) in files)
            {
                var entry = zip.CreateEntry(path);
                using var stream = entry.Open();
                stream.Write(bytes);
            }
        }

        return buffer.ToArray();
    }

    private static async Task<string> NewNoteAsync(HttpClient client, object body)
    {
        var response = await client.PostAsJsonAsync("/api/notes", body);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static async Task<JsonElement[]> GridAsync(HttpClient client, string? view = null)
    {
        var url = view is null ? "/api/notes" : $"/api/notes?{view}=true";
        var grid = await client.GetFromJsonAsync<JsonElement>(url);
        return grid.EnumerateArray().ToArray();
    }

    private static JsonElement NoteWithTitle(IEnumerable<JsonElement> notes, string title) =>
        Assert.Single(notes, n => n.GetProperty("title").GetString() == title);

    /// <summary>
    /// The full trip: one account's notes, lists, per-user flags, reminder and image, exported and
    /// then imported into a different account, arrive intact.
    /// </summary>
    [Fact]
    public async Task An_export_imports_back_into_another_account()
    {
        using var api = new KeepItApiFactory();
        using var source = await api.CreateSignedInClientAsync();
        using var target = await api.CreateSignedInClientAsync();

        var listResponse = await source.PostAsJsonAsync("/api/lists", new { name = "Trip", color = "teal" });
        listResponse.EnsureSuccessStatusCode();
        var listId = (await listResponse.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;

        var textId = await NewNoteAsync(source, new
        {
            type = "Text",
            title = "packing",
            body = "passport, charger",
            color = "amber",
            listIds = new[] { listId },
        });
        await NewNoteAsync(source, new
        {
            type = "Checklist",
            title = "todo",
            checklistItems = new[]
            {
                new { text = "book hotel", isChecked = true },
                new { text = "print tickets", isChecked = false },
            },
        });
        var archivedId = await NewNoteAsync(source, new { type = "Text", title = "old" });

        (await source.PatchAsJsonAsync($"/api/notes/{textId}/state", new { isPinned = true }))
            .EnsureSuccessStatusCode();
        (await source.PatchAsJsonAsync($"/api/notes/{archivedId}/state", new { isArchived = true }))
            .EnsureSuccessStatusCode();

        // Far enough out that the reminder dispatcher cannot fire it mid-test.
        var remindAt = DateTime.UtcNow.AddDays(30);
        (await source.PutAsJsonAsync($"/api/notes/{textId}/reminder",
                new { remindAtUtc = remindAt, recurrence = "Weekly" }))
            .EnsureSuccessStatusCode();

        var upload = await NoteMediaTests.UploadAsync(source, textId, TestImages.Png(40, 30), "p.png", "image/png");
        Assert.Equal(HttpStatusCode.Created, upload.StatusCode);

        var result = await ImportOkAsync(target, await ExportBytesAsync(source));

        Assert.Equal(3, result.GetProperty("notesImported").GetInt32());
        Assert.Equal(1, result.GetProperty("listsCreated").GetInt32());
        Assert.Equal(1, result.GetProperty("imagesImported").GetInt32());
        Assert.Equal(0, result.GetProperty("imagesSkipped").GetInt32());
        Assert.Empty(result.GetProperty("warnings").EnumerateArray());

        // The active grid: the pinned text note and the checklist (the archived one is elsewhere).
        var grid = await GridAsync(target);
        Assert.Equal(2, grid.Length);

        var packing = NoteWithTitle(grid, "packing");
        Assert.Equal("passport, charger", packing.GetProperty("body").GetString());
        Assert.Equal("amber", packing.GetProperty("color").GetString());
        Assert.True(packing.GetProperty("isPinned").GetBoolean());
        Assert.True(packing.GetProperty("isOwner").GetBoolean());
        Assert.Equal("Weekly", packing.GetProperty("reminderRecurrence").GetString());
        Assert.Equal(remindAt, packing.GetProperty("remindAtUtc").GetDateTime(), TimeSpan.FromSeconds(1));

        var todo = NoteWithTitle(grid, "todo");
        var items = todo.GetProperty("checklistItems").EnumerateArray().ToArray();
        Assert.Equal(new[] { "book hotel", "print tickets" }, items.Select(i => i.GetProperty("text").GetString()));
        Assert.Equal(new[] { true, false }, items.Select(i => i.GetProperty("isChecked").GetBoolean()));

        Assert.Equal("old", Assert.Single(await GridAsync(target, "archived")).GetProperty("title").GetString());

        // The list came across, and the note is still filed in it.
        var lists = (await target.GetFromJsonAsync<JsonElement>("/api/lists")).EnumerateArray().ToArray();
        var trip = Assert.Single(lists);
        Assert.Equal("Trip", trip.GetProperty("name").GetString());
        Assert.Equal(trip.GetProperty("id").GetString(), Assert.Single(packing.GetProperty("listIds").EnumerateArray()).GetString());

        // The image was re-attached under the new note, and really serves bytes.
        var media = Assert.Single(packing.GetProperty("media").EnumerateArray());
        Assert.Equal(40, media.GetProperty("width").GetInt32());
        Assert.Equal(30, media.GetProperty("height").GetInt32());

        var newNoteId = packing.GetProperty("id").GetString();
        var newMediaId = media.GetProperty("id").GetString();
        var image = await target.GetAsync($"/api/notes/{newNoteId}/media/{newMediaId}");
        Assert.Equal(HttpStatusCode.OK, image.StatusCode);
        Assert.Equal((40, 30), TestImages.SizeOf(await image.Content.ReadAsByteArrayAsync()));
    }

    /// <summary>
    /// Import adds and never replaces: the same archive twice leaves the first copy untouched and
    /// the account holding both. Duplicates are the accepted cost of an operation that cannot lose
    /// anything.
    /// </summary>
    [Fact]
    public async Task Importing_the_same_archive_twice_adds_it_twice()
    {
        using var api = new KeepItApiFactory();
        using var source = await api.CreateSignedInClientAsync();
        using var target = await api.CreateSignedInClientAsync();

        (await source.PostAsJsonAsync("/api/lists", new { name = "Work" })).EnsureSuccessStatusCode();
        await NewNoteAsync(source, new { type = "Text", title = "once", body = "first" });

        var archive = await ExportBytesAsync(source);

        await ImportOkAsync(target, archive);
        var second = await ImportOkAsync(target, archive);

        // The note is duplicated...
        Assert.Equal(1, second.GetProperty("notesImported").GetInt32());
        var grid = await GridAsync(target);
        Assert.Equal(2, grid.Length);
        Assert.All(grid, n => Assert.Equal("once", n.GetProperty("title").GetString()));
        Assert.Equal(2, grid.Select(n => n.GetProperty("id").GetString()).Distinct().Count());

        // ...but the list is not: an existing name is filed into, not cloned.
        Assert.Equal(0, second.GetProperty("listsCreated").GetInt32());
        Assert.Equal(1, second.GetProperty("listsReused").GetInt32());
        Assert.Single((await target.GetFromJsonAsync<JsonElement>("/api/lists")).EnumerateArray());
    }

    /// <summary>
    /// A one-time reminder whose moment has passed imports as already fired. Without this, restoring
    /// an old backup would hand the dispatcher every overdue reminder in it at once.
    /// </summary>
    [Fact]
    public async Task A_past_one_time_reminder_imports_as_already_fired()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var past = DateTime.UtcNow.AddDays(-90);
        var manifest = JsonSerializer.Serialize(new
        {
            schemaVersion = 1,
            exportedAtUtc = DateTime.UtcNow,
            appVersion = "test",
            lists = Array.Empty<object>(),
            notes = new[]
            {
                new
                {
                    id = Guid.NewGuid(),
                    type = "Text",
                    title = "overdue",
                    remindAtUtc = past,
                    reminderRecurrence = "None",
                    reminderFired = false,
                },
            },
        });

        await ImportOkAsync(client, BuildArchive(manifest));

        var note = Assert.Single(await GridAsync(client));
        Assert.Equal("overdue", note.GetProperty("title").GetString());
        Assert.True(note.GetProperty("reminderFired").GetBoolean());

        // Nothing was raised into the inbox for it.
        Assert.Empty((await client.GetFromJsonAsync<JsonElement>("/api/notifications")).EnumerateArray());
    }

    /// <summary>An image the manifest promises but the archive doesn't contain is a warning, not a failure.</summary>
    [Fact]
    public async Task A_missing_image_is_reported_and_the_note_still_imports()
    {
        using var api = new KeepItApiFactory();
        using var source = await api.CreateSignedInClientAsync();
        using var target = await api.CreateSignedInClientAsync();

        var noteId = await NewNoteAsync(source, new { type = "Text", title = "photo note" });
        (await NoteMediaTests.UploadAsync(source, noteId, TestImages.Png(20, 20), "p.png", "image/png"))
            .EnsureSuccessStatusCode();

        // Rebuild the archive with the manifest kept but the image entry dropped.
        var original = await ExportBytesAsync(source);
        string manifest;
        using (var zip = new ZipArchive(new MemoryStream(original), ZipArchiveMode.Read))
        {
            using var reader = new StreamReader(zip.GetEntry("keepit-export.json")!.Open());
            manifest = await reader.ReadToEndAsync();
        }

        var result = await ImportOkAsync(target, BuildArchive(manifest));

        Assert.Equal(1, result.GetProperty("notesImported").GetInt32());
        Assert.Equal(0, result.GetProperty("imagesImported").GetInt32());
        Assert.Equal(1, result.GetProperty("imagesSkipped").GetInt32());
        Assert.Contains("missing", Assert.Single(result.GetProperty("warnings").EnumerateArray()).GetString());

        var note = Assert.Single(await GridAsync(target));
        Assert.Equal("photo note", note.GetProperty("title").GetString());
        Assert.Empty(note.GetProperty("media").EnumerateArray());
    }

    /// <summary>An archive from a newer keepIT is refused rather than half-read.</summary>
    [Fact]
    public async Task An_archive_from_a_newer_version_is_refused()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var manifest = JsonSerializer.Serialize(new
        {
            schemaVersion = 999,
            exportedAtUtc = DateTime.UtcNow,
            appVersion = "from-the-future",
            lists = Array.Empty<object>(),
            notes = new[] { new { id = Guid.NewGuid(), type = "Text", title = "unreadable" } },
        });

        var response = await ImportAsync(client, BuildArchive(manifest));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains("newer version", await response.Content.ReadAsStringAsync());
        Assert.Empty(await GridAsync(client));
    }

    /// <summary>Something that isn't a keepIT archive is refused with an answer the user can act on.</summary>
    [Fact]
    public async Task A_zip_that_is_not_a_keepIT_archive_is_refused()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var notOurs = BuildArchive("", ("notes/something.txt", Encoding.UTF8.GetBytes("hello")));
        var response = await ImportAsync(client, notOurs);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains("keepit-export.json", await response.Content.ReadAsStringAsync());
        Assert.Empty(await GridAsync(client));
    }

    /// <summary>A file that isn't a zip at all fails as a bad request, not a 500.</summary>
    [Fact]
    public async Task A_file_that_is_not_a_zip_is_refused()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var response = await ImportAsync(client, Encoding.UTF8.GetBytes("this is not a zip file"));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Empty(await GridAsync(client));
    }

    /// <summary>Import is authenticated.</summary>
    [Fact]
    public async Task Import_requires_a_session()
    {
        using var api = new KeepItApiFactory();
        using var anonymous = api.CreateClient();

        var response = await ImportAsync(anonymous, BuildArchive("{}"));
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }
}
