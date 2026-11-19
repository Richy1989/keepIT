using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// An update that names its fields sets only those. The case it exists for: an edit made offline
/// replays long after it was made, and sending the whole note then reverted whatever had changed
/// meanwhile in the parts the edit never touched.
/// </summary>
public sealed class NoteUpdateFieldsTests
{
    [Fact]
    public async Task An_update_naming_its_fields_leaves_the_others_as_they_are_now()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = await NewNoteAsync(client, new { type = "Text", title = "Trip", body = "tent", color = "teal" });

        // Someone renames it...
        await PutAsync(client, id, new { type = "Text", title = "Trip to the lake", body = "tent", color = "teal" });
        // ...and then an edit made earlier, offline, to the text alone arrives, still carrying the old title.
        var note = await PutAsync(client, id, new { type = "Text", title = "Trip", body = "tent and stove", color = "teal", fields = new[] { "Body" } });

        Assert.Equal("Trip to the lake", note.GetProperty("title").GetString());
        Assert.Equal("tent and stove", note.GetProperty("body").GetString());
        Assert.Equal("teal", note.GetProperty("color").GetString());
    }

    [Fact]
    public async Task A_checklist_only_update_keeps_title_and_colour()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = await NewNoteAsync(client, new { type = "Checklist", title = "Shop", color = "rose", checklistItems = new[] { new { text = "milk", isChecked = false, order = 0 } } });

        var note = await PutAsync(client, id, new
        {
            type = "Checklist",
            title = (string?)null,
            checklistItems = new[] { new { text = "milk", isChecked = true, order = 0 }, new { text = "eggs", isChecked = false, order = 1 } },
            fields = new[] { "ChecklistItems" },
        });

        Assert.Equal("Shop", note.GetProperty("title").GetString());
        Assert.Equal("rose", note.GetProperty("color").GetString());
        Assert.Equal(2, note.GetProperty("checklistItems").GetArrayLength());
    }

    [Fact]
    public async Task Without_fields_an_update_replaces_everything_as_before()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = await NewNoteAsync(client, new { type = "Text", title = "Old", body = "old", color = "teal" });

        var note = await PutAsync(client, id, new { type = "Text", title = "New" });

        Assert.Equal("New", note.GetProperty("title").GetString());
        Assert.Equal(JsonValueKind.Null, note.GetProperty("body").ValueKind);
        Assert.Equal(JsonValueKind.Null, note.GetProperty("color").ValueKind);
    }

    [Fact]
    public async Task Naming_no_fields_changes_nothing()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = await NewNoteAsync(client, new { type = "Text", title = "Same", body = "same" });
        var before = await client.GetFromJsonAsync<JsonElement>($"/api/notes/{id}");

        var note = await PutAsync(client, id, new { type = "Text", title = "Other", fields = Array.Empty<string>() });

        Assert.Equal("Same", note.GetProperty("title").GetString());
        Assert.Equal(before.GetProperty("updatedAtUtc").GetString(), note.GetProperty("updatedAtUtc").GetString());
    }

    [Fact]
    public async Task A_background_is_set_when_named_and_kept_by_an_update_without_fields()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = await NewNoteAsync(client, new { type = "Text", title = "Budget", color = "sky", background = "lined" });

        // An app older than backgrounds sends the whole note, without fields and without one: the
        // background a newer client chose stays.
        var note = await PutAsync(client, id, new { type = "Text", title = "October budget", color = "sky" });
        Assert.Equal("October budget", note.GetProperty("title").GetString());
        Assert.Equal("lined", note.GetProperty("background").GetString());

        note = await PutAsync(client, id, new { type = "Text", background = "grid-dots", fields = new[] { "Background" } });
        Assert.Equal("grid-dots", note.GetProperty("background").GetString());
        Assert.Equal("October budget", note.GetProperty("title").GetString());

        note = await PutAsync(client, id, new { type = "Text", background = (string?)null, fields = new[] { "Background" } });
        Assert.Equal(JsonValueKind.Null, note.GetProperty("background").ValueKind);
    }

    [Theory]
    [InlineData("../secret")]
    [InlineData("Lined")]
    [InlineData("lined.png")]
    [InlineData("-lined")]
    [InlineData("lined--paper")]
    public async Task A_background_that_is_not_a_plain_name_is_refused(string background)
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = await NewNoteAsync(client, new { type = "Text", title = "x" });

        var update = await client.PutAsJsonAsync($"/api/notes/{id}", new { type = "Text", background, fields = new[] { "Background" } });
        Assert.Equal(HttpStatusCode.BadRequest, update.StatusCode);
        var create = await client.PostAsJsonAsync("/api/notes", new { type = "Text", background });
        Assert.Equal(HttpStatusCode.BadRequest, create.StatusCode);
    }

    private static async Task<string> NewNoteAsync(HttpClient client, object body)
    {
        var response = await client.PostAsJsonAsync("/api/notes", body);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static async Task<JsonElement> PutAsync(HttpClient client, string id, object body)
    {
        var response = await client.PutAsJsonAsync($"/api/notes/{id}", body);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }
}
