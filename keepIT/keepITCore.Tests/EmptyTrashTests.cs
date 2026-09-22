using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// One API host and two users for the empty-trash tests, signed up once: the sign-in endpoints
/// allow a handful of requests a minute, so a pair per test would soon be refused.
/// </summary>
public sealed class TrashHost : IAsyncLifetime
{
    public KeepItApiFactory Api { get; } = new();
    public string MyEmail { get; } = $"user-{Guid.NewGuid():N}@example.com";
    public string OtherEmail { get; } = $"user-{Guid.NewGuid():N}@example.com";
    public HttpClient Me { get; private set; } = null!;
    public HttpClient Other { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        Me = await Api.CreateSignedInClientAsync(MyEmail);
        Other = await Api.CreateSignedInClientAsync(OtherEmail);
    }

    public Task DisposeAsync()
    {
        Me.Dispose();
        Other.Dispose();
        Api.Dispose();
        return Task.CompletedTask;
    }
}

/// <summary>
/// "Delete all" in the trash: which notes go for good, which are only left, and which are never
/// touched because the client didn't list them or they are no longer in the trash. The users are
/// shared between the tests, so each asserts only on the notes it made.
/// </summary>
public sealed class EmptyTrashTests(TrashHost host) : IClassFixture<TrashHost>
{
    private HttpClient Me => host.Me;
    private HttpClient Other => host.Other;

    [Fact]
    public async Task Owned_notes_listed_from_the_trash_are_deleted_with_their_images()
    {
        var withImage = await NewNoteAsync(Me, "with image");
        var upload = await NoteMediaTests.UploadAsync(Me, withImage, TestImages.Png(40, 30), "a.png", "image/png");
        Assert.Equal(HttpStatusCode.Created, upload.StatusCode);
        var plain = await NewNoteAsync(Me, "plain");
        await TrashAsync(Me, withImage);
        await TrashAsync(Me, plain);

        await EmptyAsync(Me, withImage, plain);

        Assert.Equal(HttpStatusCode.NotFound, await StatusOfAsync(Me, withImage));
        Assert.Equal(HttpStatusCode.NotFound, await StatusOfAsync(Me, plain));
        var trash = await TrashedIdsAsync(Me);
        Assert.DoesNotContain(withImage, trash);
        Assert.DoesNotContain(plain, trash);
        Assert.Empty(Directory.GetDirectories(host.Api.DataRoot, withImage, SearchOption.AllDirectories));
    }

    [Fact]
    public async Task Only_listed_notes_that_are_still_in_the_trash_are_removed()
    {
        var listed = await NewNoteAsync(Me, "listed");
        var trashedSince = await NewNoteAsync(Me, "trashed on another device after the list was shown");
        var restored = await NewNoteAsync(Me, "restored on another device");
        var theirs = await NewNoteAsync(Other, "someone else's, not shared");
        await TrashAsync(Me, listed);
        await TrashAsync(Me, trashedSince);
        await TrashAsync(Other, theirs);

        await EmptyAsync(Me, listed, restored, theirs, Guid.NewGuid().ToString());

        Assert.Equal(HttpStatusCode.NotFound, await StatusOfAsync(Me, listed));
        Assert.Contains(trashedSince, await TrashedIdsAsync(Me));
        Assert.Equal(HttpStatusCode.OK, await StatusOfAsync(Me, restored));
        Assert.Contains(theirs, await TrashedIdsAsync(Other));
    }

    [Fact]
    public async Task A_note_shared_with_the_caller_is_left_and_its_owner_keeps_it()
    {
        var shared = await NewNoteAsync(Other, "shared with me");
        await ShareAsync(Other, shared, Me, host.MyEmail);
        await TrashAsync(Me, shared);

        await EmptyAsync(Me, shared);

        Assert.DoesNotContain(shared, await TrashedIdsAsync(Me));
        Assert.Equal(HttpStatusCode.NotFound, await StatusOfAsync(Me, shared));
        var kept = await Other.GetFromJsonAsync<JsonElement>($"/api/notes/{shared}");
        Assert.False(kept.GetProperty("isTrashed").GetBoolean());
        Assert.False(kept.GetProperty("isShared").GetBoolean()); // I'm no longer on it
    }

    [Fact]
    public async Task A_purged_note_disappears_for_its_collaborators_too()
    {
        var shared = await NewNoteAsync(Me, "mine, shared");
        await ShareAsync(Me, shared, Other, host.OtherEmail);
        await TrashAsync(Me, shared);

        await EmptyAsync(Me, shared);

        Assert.Equal(HttpStatusCode.NotFound, await StatusOfAsync(Other, shared));
    }

    [Fact]
    public async Task Sending_the_same_request_again_changes_nothing()
    {
        var note = await NewNoteAsync(Me, "gone");
        await TrashAsync(Me, note);

        await EmptyAsync(Me, note);
        await EmptyAsync(Me, note); // an offline queue replaying it

        Assert.Equal(HttpStatusCode.NotFound, await StatusOfAsync(Me, note));
    }

    private static async Task<string> NewNoteAsync(HttpClient client, string title)
    {
        var response = await client.PostAsJsonAsync("/api/notes", new { type = "Text", title });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static async Task TrashAsync(HttpClient client, string noteId) =>
        (await client.PatchAsJsonAsync($"/api/notes/{noteId}/state", new { isTrashed = true }))
            .EnsureSuccessStatusCode();

    private static async Task EmptyAsync(HttpClient client, params string[] noteIds)
    {
        var response = await client.PostAsJsonAsync("/api/notes/trash/empty", new { noteIds });
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
    }

    private static async Task<HttpStatusCode> StatusOfAsync(HttpClient client, string noteId) =>
        (await client.GetAsync($"/api/notes/{noteId}")).StatusCode;

    private static async Task<string[]> TrashedIdsAsync(HttpClient client) =>
        (await client.GetFromJsonAsync<JsonElement>("/api/notes?trashed=true"))
            .EnumerateArray()
            .Select(n => n.GetProperty("id").GetString()!)
            .ToArray();

    /// <summary>The owner invites the collaborator as an editor, and the collaborator accepts.</summary>
    private static async Task ShareAsync(HttpClient owner, string noteId, HttpClient collaborator, string collaboratorEmail)
    {
        (await owner.PostAsJsonAsync($"/api/notes/{noteId}/shares", new { email = collaboratorEmail, role = "Editor" }))
            .EnsureSuccessStatusCode();
        var invite = (await collaborator.GetFromJsonAsync<JsonElement>("/api/notifications"))
            .EnumerateArray()
            .Single(n => n.TryGetProperty("sharedNoteId", out var id) && id.GetString() == noteId);
        (await collaborator.PostAsJsonAsync($"/api/notifications/{invite.GetProperty("id").GetString()}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }
}
