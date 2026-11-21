using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// A create whose answer never reached the client. The phone's outbox sends a queued note, image or
/// list, the server saves it, and the connection drops before the 201 arrives: the op is still
/// queued, so the phone sends it again. With the id the client picked for it, the second request is
/// recognised as the first one again and answered with what it made, instead of making a copy. An id
/// that is taken by something else is ignored and a new one made, as for checklist rows, so a client
/// can never reach another note through the id it picks. Without an id every request makes something
/// new, as it always did, which is what an older app still gets.
/// </summary>
public sealed class RetriedCreateTests
{
    [Fact]
    public async Task A_note_sent_twice_with_its_id_is_created_once()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = Guid.NewGuid();

        var first = await client.PostAsJsonAsync("/api/notes", new { id, type = "Text", title = "Groceries" });
        var again = await client.PostAsJsonAsync("/api/notes", new { id, type = "Text", title = "Groceries" });

        Assert.Equal(HttpStatusCode.Created, first.StatusCode);
        Assert.Equal(HttpStatusCode.Created, again.StatusCode);
        Assert.Equal(id.ToString(), await IdOf(first));
        Assert.Equal(id.ToString(), await IdOf(again));
        var titles = (await client.GetFromJsonAsync<JsonElement>("/api/notes"))
            .EnumerateArray().Select(n => n.GetProperty("title").GetString()).ToArray();
        Assert.Equal(new[] { "Groceries" }, titles);
    }

    [Fact]
    public async Task A_note_id_taken_by_someone_else_gets_a_new_one()
    {
        using var api = new KeepItApiFactory();
        using var anna = await api.CreateSignedInClientAsync();
        using var ben = await api.CreateSignedInClientAsync();
        var annas = await IdOf(await anna.PostAsJsonAsync("/api/notes", new { type = "Text", title = "Anna's" }));

        var response = await ben.PostAsJsonAsync("/api/notes", new { id = annas, type = "Text", title = "Ben's" });

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.NotEqual(annas, await IdOf(response));
        var note = await anna.GetFromJsonAsync<JsonElement>($"/api/notes/{annas}");
        Assert.Equal("Anna's", note.GetProperty("title").GetString());
        Assert.Equal(HttpStatusCode.NotFound, (await ben.GetAsync($"/api/notes/{annas}")).StatusCode);
    }

    [Fact]
    public async Task Notes_sent_without_an_id_are_each_created_as_before()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        await client.PostAsJsonAsync("/api/notes", new { type = "Text", title = "Same" });
        await client.PostAsJsonAsync("/api/notes", new { type = "Text", title = "Same" });

        Assert.Equal(2, (await client.GetFromJsonAsync<JsonElement>("/api/notes")).GetArrayLength());
    }

    [Fact]
    public async Task An_image_sent_twice_with_its_id_is_attached_once()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NoteMediaTests.NewNoteAsync(client);
        var id = Guid.NewGuid();
        var png = TestImages.Png(64, 64);

        var first = await UploadAsync(client, noteId, png, id);
        var again = await UploadAsync(client, noteId, png, id);

        Assert.Equal(HttpStatusCode.Created, first.StatusCode);
        Assert.Equal(HttpStatusCode.Created, again.StatusCode);
        Assert.Equal(id.ToString(), await IdOf(first));
        Assert.Equal(id.ToString(), await IdOf(again));
        var media = (await client.GetFromJsonAsync<JsonElement>($"/api/notes/{noteId}")).GetProperty("media");
        Assert.Equal(1, media.GetArrayLength());
    }

    [Fact]
    public async Task A_resent_image_is_answered_even_when_the_note_has_filled_up_since()
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:Media:MaxImagesPerNote"] = "1";
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NoteMediaTests.NewNoteAsync(client);
        var id = Guid.NewGuid();

        await UploadAsync(client, noteId, TestImages.Png(64, 64), id);
        var again = await UploadAsync(client, noteId, TestImages.Png(64, 64), id);

        Assert.Equal(HttpStatusCode.Created, again.StatusCode);
        Assert.Equal(id.ToString(), await IdOf(again));
    }

    [Fact]
    public async Task An_image_id_used_on_another_note_gets_a_new_one()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var first = await NoteMediaTests.NewNoteAsync(client);
        var second = await NoteMediaTests.NewNoteAsync(client);
        var id = Guid.NewGuid();
        await UploadAsync(client, first, TestImages.Png(64, 64), id);

        var response = await UploadAsync(client, second, TestImages.Png(64, 64), id);

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.NotEqual(id.ToString(), await IdOf(response));
        var media = (await client.GetFromJsonAsync<JsonElement>($"/api/notes/{first}")).GetProperty("media");
        Assert.Equal(id.ToString(), media.EnumerateArray().Single().GetProperty("id").GetString());
    }

    [Fact]
    public async Task A_voice_note_sent_twice_with_its_id_is_attached_once()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NoteMediaTests.NewNoteAsync(client);
        var id = Guid.NewGuid();

        var first = await UploadAsync(client, noteId, TestAudio.M4a(1500), id, "voice.m4a", "audio/mp4");
        var again = await UploadAsync(client, noteId, TestAudio.M4a(1500), id, "voice.m4a", "audio/mp4");

        Assert.Equal(HttpStatusCode.Created, first.StatusCode);
        Assert.Equal(HttpStatusCode.Created, again.StatusCode);
        Assert.Equal(id.ToString(), await IdOf(again));
        var media = (await client.GetFromJsonAsync<JsonElement>($"/api/notes/{noteId}")).GetProperty("media");
        Assert.Equal(1, media.GetArrayLength());
    }

    [Fact]
    public async Task A_list_sent_twice_with_its_id_is_created_once()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = Guid.NewGuid();

        var first = await client.PostAsJsonAsync("/api/lists", new { id, name = "Shopping" });
        var again = await client.PostAsJsonAsync("/api/lists", new { id, name = "Shopping" });

        Assert.Equal(HttpStatusCode.Created, first.StatusCode);
        Assert.Equal(HttpStatusCode.Created, again.StatusCode);
        Assert.Equal(id.ToString(), await IdOf(again));
        Assert.Equal(1, (await client.GetFromJsonAsync<JsonElement>("/api/lists")).GetArrayLength());
    }

    [Fact]
    public async Task A_list_id_taken_by_someone_else_gets_a_new_one()
    {
        using var api = new KeepItApiFactory();
        using var anna = await api.CreateSignedInClientAsync();
        using var ben = await api.CreateSignedInClientAsync();
        var annas = await IdOf(await anna.PostAsJsonAsync("/api/lists", new { name = "Anna's" }));

        var response = await ben.PostAsJsonAsync("/api/lists", new { id = annas, name = "Ben's" });

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.NotEqual(annas, await IdOf(response));
        var lists = await anna.GetFromJsonAsync<JsonElement>("/api/lists");
        Assert.Equal("Anna's", lists.EnumerateArray().Single().GetProperty("name").GetString());
    }

    private static async Task<HttpResponseMessage> UploadAsync(
        HttpClient client, string noteId, byte[] bytes, Guid id,
        string fileName = "photo.png", string contentType = "image/png")
    {
        using var form = new MultipartFormDataContent();
        var file = new ByteArrayContent(bytes);
        file.Headers.ContentType = new MediaTypeHeaderValue(contentType);
        form.Add(file, "file", fileName);
        form.Add(new StringContent(id.ToString()), "id");
        return await client.PostAsync($"/api/notes/{noteId}/media", form);
    }

    private static async Task<string> IdOf(HttpResponseMessage response) =>
        (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
}
