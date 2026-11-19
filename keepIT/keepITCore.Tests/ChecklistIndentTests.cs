using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Checklist indentation is normalised by the server: one level only, never on the first row, and
/// kept as it is by an update that doesn't say (an app from before sub-items). Ticked boxes are
/// stored as sent. These go through the real API so they cover both the write and the projection
/// the clients read back.
/// </summary>
public sealed class ChecklistIndentTests
{
    [Fact]
    public async Task The_first_row_is_never_indented_and_deeper_nesting_collapses_to_one_level()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var note = await CreateAsync(client, new[]
        {
            new { text = "first", indent = 1, isChecked = false },
            new { text = "child", indent = 2, isChecked = false },
            new { text = "top", indent = 0, isChecked = false },
        });

        var items = Items(note);
        Assert.Equal(new[] { 0, 1, 0 }, items.Select(IndentOf));
    }

    [Fact]
    public async Task Every_box_is_stored_as_sent_whatever_its_children_say()
    {
        // Ticking a group is the apps' rule. An app from before sub-items can't see them, so a
        // parent it ticks must stay ticked rather than be derived back from its children.
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var note = await CreateAsync(client, new[]
        {
            new { text = "ticked by an old app", indent = 0, isChecked = true },
            new { text = "a", indent = 1, isChecked = false },
            new { text = "not ticked", indent = 0, isChecked = false },
            new { text = "b", indent = 1, isChecked = true },
        });

        var items = Items(note);
        Assert.True(CheckedOf(items, "ticked by an old app"));
        Assert.False(CheckedOf(items, "a"));
        Assert.False(CheckedOf(items, "not ticked"));
        Assert.True(CheckedOf(items, "b"));
    }

    [Fact]
    public async Task An_update_persists_the_new_indent()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var created = await CreateAsync(client, new[]
        {
            new { text = "parent", indent = 0, isChecked = false },
            new { text = "child", indent = 0, isChecked = false },
        });
        var id = created.GetProperty("id").GetString()!;
        var ids = Items(created).Select(i => i.GetProperty("id").GetString()).ToArray();

        var indented = await UpdateAsync(client, id, new[]
        {
            new { id = ids[0], text = "parent", indent = 0, isChecked = false },
            new { id = ids[1], text = "child", indent = 1, isChecked = false },
        });
        Assert.Equal(new[] { 0, 1 }, Items(indented).Select(IndentOf));

        var outdented = await UpdateAsync(client, id, new[]
        {
            new { id = ids[0], text = "parent", indent = 0, isChecked = false },
            new { id = ids[1], text = "child", indent = 0, isChecked = false },
        });
        Assert.Equal(new[] { 0, 0 }, Items(outdented).Select(IndentOf));
    }

    /// <summary>
    /// An app from before sub-items sends its rows without <c>indent</c>. Editing the note there
    /// (or replaying an edit it queued offline) must not flatten what a newer app nested: a row it
    /// names keeps its indent, and a row it adds starts top-level.
    /// </summary>
    [Fact]
    public async Task An_update_without_indent_keeps_the_nesting()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var created = await CreateAsync(client, new[]
        {
            new { text = "pack", indent = 0, isChecked = false },
            new { text = "socks", indent = 1, isChecked = false },
            new { text = "boots", indent = 1, isChecked = false },
        });
        var id = created.GetProperty("id").GetString()!;
        var ids = Items(created).Select(i => i.GetProperty("id").GetString()).ToArray();

        // What an older app sends: every row it knows, a new one at the end, and no indent at all.
        var updated = await UpdateAsync(client, id, new object[]
        {
            new { id = ids[0], text = "pack", isChecked = false },
            new { id = ids[1], text = "socks", isChecked = true },
            new { id = ids[2], text = "boots", isChecked = false },
            new { text = "passport", isChecked = false },
        });

        var items = Items(updated);
        Assert.Equal(new[] { 0, 1, 1, 0 }, items.Select(IndentOf));
        Assert.True(CheckedOf(items, "socks"));
    }

    [Fact]
    public async Task A_checklist_without_indentation_is_unchanged()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var note = await CreateAsync(client, new[]
        {
            new { text = "one", isChecked = false },
            new { text = "two", isChecked = true },
        });

        var items = Items(note);
        Assert.Equal(new[] { 0, 0 }, items.Select(IndentOf));
        Assert.False(CheckedOf(items, "one"));
        Assert.True(CheckedOf(items, "two"));
    }

    private static async Task<JsonElement> CreateAsync<T>(HttpClient client, T items)
    {
        var response = await client.PostAsJsonAsync(
            "/api/notes", new { type = "Checklist", title = "list", checklistItems = items });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static async Task<JsonElement> UpdateAsync<T>(HttpClient client, string id, T items)
    {
        var response = await client.PutAsJsonAsync(
            $"/api/notes/{id}", new { type = "Checklist", title = "list", checklistItems = items });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static JsonElement[] Items(JsonElement note) =>
        note.GetProperty("checklistItems").EnumerateArray().ToArray();

    private static int IndentOf(JsonElement item) => item.GetProperty("indent").GetInt32();

    private static bool CheckedOf(IEnumerable<JsonElement> items, string text) =>
        items.Single(i => i.GetProperty("text").GetString() == text).GetProperty("isChecked").GetBoolean();
}
