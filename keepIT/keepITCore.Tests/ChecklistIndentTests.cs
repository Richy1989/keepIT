using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Checklist indentation is normalised by the server: one level only, never on the first row, and a
/// parent's box mirrors its children. These go through the real API so they cover both the write and
/// the projection the clients read back.
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
    public async Task A_parent_is_ticked_on_create_only_when_every_child_is()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var note = await CreateAsync(client, new[]
        {
            new { text = "all done", indent = 0, isChecked = false },
            new { text = "a", indent = 1, isChecked = true },
            new { text = "b", indent = 1, isChecked = true },
            new { text = "not done", indent = 0, isChecked = true },
            new { text = "c", indent = 1, isChecked = true },
            new { text = "d", indent = 1, isChecked = false },
            new { text = "alone", indent = 0, isChecked = true },
        });

        var items = Items(note);
        Assert.True(CheckedOf(items, "all done"));
        Assert.False(CheckedOf(items, "not done"));
        // A top-level row without children keeps its own state.
        Assert.True(CheckedOf(items, "alone"));
    }

    [Fact]
    public async Task An_update_recomputes_the_parent_and_persists_the_new_indent()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        var created = await CreateAsync(client, new[]
        {
            new { text = "parent", indent = 0, isChecked = false },
            new { text = "child", indent = 0, isChecked = false },
        });

        var ids = Items(created).Select(i => i.GetProperty("id").GetString()).ToArray();

        // The second row becomes a checked child: the unchecked parent is derived ticked.
        var indented = await UpdateAsync(client, created.GetProperty("id").GetString()!, new[]
        {
            new { id = ids[0], text = "parent", indent = 0, isChecked = false },
            new { id = ids[1], text = "child", indent = 1, isChecked = true },
        });

        Assert.Equal(1, IndentOf(Items(indented).Single(i => i.GetProperty("text").GetString() == "child")));
        Assert.True(CheckedOf(Items(indented), "parent"));

        // Unchecking the child unchecks the parent.
        var uncheckedChild = await UpdateAsync(client, created.GetProperty("id").GetString()!, new[]
        {
            new { id = ids[0], text = "parent", indent = 0, isChecked = true },
            new { id = ids[1], text = "child", indent = 1, isChecked = false },
        });

        Assert.False(CheckedOf(Items(uncheckedChild), "parent"));

        // Outdenting the child leaves the parent without one: it keeps the state that was sent.
        var outdented = await UpdateAsync(client, created.GetProperty("id").GetString()!, new[]
        {
            new { id = ids[0], text = "parent", indent = 0, isChecked = true },
            new { id = ids[1], text = "child", indent = 0, isChecked = false },
        });

        Assert.Equal(new[] { 0, 0 }, Items(outdented).Select(IndentOf));
        Assert.True(CheckedOf(Items(outdented), "parent"));
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
