using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Two people working the same checklist at once — both in the supermarket, both ticking, one
/// adding the thing they forgot. An update that sends <c>checklistBase</c> (the rows it started
/// from) is merged row by row into what the note holds now: only what this edit changed is applied,
/// so a tick, a new row or a deletion made meanwhile by someone else survives. Without a base the
/// list is replaced whole, as it always was, which is what an older app still gets.
/// </summary>
public sealed class ChecklistMergeTests
{
    [Fact]
    public async Task Two_people_ticking_different_rows_both_keep_their_tick()
    {
        await using var shop = await SharedListAsync("milk", "bread", "eggs");

        // Both opened the list as it was; Anna ticks milk, then Ben ticks bread.
        await shop.Anna.SaveAsync(shop.Base, shop.Base.Tick("milk"));
        var note = await shop.Ben.SaveAsync(shop.Base, shop.Base.Tick("bread"));

        Assert.True(CheckedOf(note, "milk"));
        Assert.True(CheckedOf(note, "bread"));
        Assert.False(CheckedOf(note, "eggs"));
    }

    [Fact]
    public async Task A_row_added_by_one_survives_a_tick_from_the_others_older_list()
    {
        await using var shop = await SharedListAsync("milk", "bread");

        await shop.Anna.SaveAsync(shop.Base, shop.Base.Add("butter", after: "milk"));
        var note = await shop.Ben.SaveAsync(shop.Base, shop.Base.Tick("bread"));

        Assert.Equal(new[] { "milk", "butter", "bread" }, Texts(note));
        Assert.True(CheckedOf(note, "bread"));
    }

    [Fact]
    public async Task A_row_deleted_by_one_stays_deleted_when_the_other_ticks_from_an_older_list()
    {
        await using var shop = await SharedListAsync("milk", "bread", "eggs");

        await shop.Anna.SaveAsync(shop.Base, shop.Base.Remove("eggs"));
        var note = await shop.Ben.SaveAsync(shop.Base, shop.Base.Tick("milk"));

        Assert.Equal(new[] { "milk", "bread" }, Texts(note));
        Assert.True(CheckedOf(note, "milk"));
    }

    [Fact]
    public async Task Both_adding_a_row_keeps_both_where_each_put_it()
    {
        await using var shop = await SharedListAsync("milk", "bread");

        await shop.Anna.SaveAsync(shop.Base, shop.Base.Add("butter", after: "milk"));
        var note = await shop.Ben.SaveAsync(shop.Base, shop.Base.Add("jam", after: "bread"));

        Assert.Equal(new[] { "milk", "butter", "bread", "jam" }, Texts(note));
    }

    [Fact]
    public async Task One_ticks_a_row_while_the_other_renames_it_and_both_changes_stay()
    {
        await using var shop = await SharedListAsync("milk", "bread");

        await shop.Anna.SaveAsync(shop.Base, shop.Base.Tick("milk"));
        var note = await shop.Ben.SaveAsync(shop.Base, shop.Base.Rename("milk", "oat milk"));

        Assert.Equal(new[] { "oat milk", "bread" }, Texts(note));
        Assert.True(CheckedOf(note, "oat milk"));
    }

    [Fact]
    public async Task A_move_reorders_the_list_without_losing_the_others_new_row()
    {
        await using var shop = await SharedListAsync("milk", "bread", "eggs");

        await shop.Anna.SaveAsync(shop.Base, shop.Base.Add("butter", after: "milk"));
        var note = await shop.Ben.SaveAsync(shop.Base, shop.Base.Move("eggs", toFront: true));

        Assert.Equal(new[] { "eggs", "milk", "butter", "bread" }, Texts(note));
    }

    [Fact]
    public async Task A_new_row_keeps_the_id_the_app_gave_it_and_a_resent_save_adds_it_once()
    {
        await using var shop = await SharedListAsync("milk");
        var withButter = shop.Base.Add("butter", after: "milk");
        var butterId = withButter.IdOf("butter");

        await shop.Anna.SaveAsync(shop.Base, withButter);
        // The response was lost on a bad connection, so the outbox sends the very same save again.
        var note = await shop.Anna.SaveAsync(shop.Base, withButter);

        Assert.Equal(new[] { "milk", "butter" }, Texts(note));
        Assert.Equal(butterId, Rows(note).Single(r => r.Text == "butter").Id);
    }

    [Fact]
    public async Task A_note_created_with_row_ids_keeps_them()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var id = Guid.NewGuid().ToString();

        var response = await client.PostAsJsonAsync("/api/notes", new
        {
            type = "Checklist",
            checklistItems = new[] { new { id, text = "milk", isChecked = false, order = 0 } },
        });
        response.EnsureSuccessStatusCode();
        var note = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(id, Rows(note).Single().Id);
    }

    [Fact]
    public async Task A_row_id_already_used_by_someone_elses_note_is_not_taken_over()
    {
        await using var shop = await SharedListAsync("milk");
        using var stranger = await shop.Api.CreateSignedInClientAsync();
        var milkId = shop.Base.IdOf("milk");

        var response = await stranger.PostAsJsonAsync("/api/notes", new
        {
            type = "Checklist",
            checklistItems = new[] { new { id = milkId, text = "mine now", isChecked = true, order = 0 } },
        });
        response.EnsureSuccessStatusCode();
        var theirs = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.NotEqual(milkId, Rows(theirs).Single().Id);
        var ours = await shop.Anna.Client.GetFromJsonAsync<JsonElement>($"/api/notes/{shop.NoteId}");
        Assert.Equal(new[] { "milk" }, Texts(ours));
        Assert.False(CheckedOf(ours, "milk"));
    }

    [Fact]
    public async Task Without_a_base_the_list_is_replaced_as_it_always_was()
    {
        // What an app from before the merge sends: its whole list, which wins.
        await using var shop = await SharedListAsync("milk", "bread");

        await shop.Anna.SaveAsync(shop.Base, shop.Base.Tick("milk"));
        var note = await shop.Ben.SaveAsync(null, shop.Base.Tick("bread"));

        Assert.False(CheckedOf(note, "milk"));
        Assert.True(CheckedOf(note, "bread"));
    }

    [Fact]
    public async Task Switching_the_note_to_text_empties_the_list_whatever_the_base_says()
    {
        await using var shop = await SharedListAsync("milk");
        await shop.Anna.SaveAsync(shop.Base, shop.Base.Add("butter", after: "milk"));

        var response = await shop.Ben.Client.PutAsJsonAsync($"/api/notes/{shop.NoteId}", new
        {
            type = "Text",
            body = "milk",
            checklistItems = Array.Empty<object>(),
            checklistBase = shop.Base.Payload(),
            fields = new[] { "Type", "Body", "ChecklistItems" },
        });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var note = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal("Text", note.GetProperty("type").GetString());
        Assert.Empty(Rows(note));
    }

    [Fact]
    public async Task A_merge_that_would_go_past_the_row_limit_is_refused()
    {
        await using var shop = await SharedListAsync("milk");
        var many = Enumerable.Range(0, 499).Aggregate(shop.Base, (rows, i) => rows.Add($"item {i}", after: "milk"));

        await shop.Anna.SaveAsync(shop.Base, many);
        var response = await shop.Ben.Client.PutAsJsonAsync($"/api/notes/{shop.NoteId}", new
        {
            type = "Checklist",
            checklistItems = shop.Base.Add("one more", after: "milk").Payload(),
            checklistBase = shop.Base.Payload(),
            fields = new[] { "ChecklistItems" },
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    // ---- helpers ----

    /// <summary>A checklist row as the tests see it.</summary>
    private sealed record Row(string Id, string Text, bool IsChecked = false, int Indent = 0);

    /// <summary>An editor's copy of the list: immutable, so "the rows it started from" stays put.</summary>
    private sealed record Checklist(IReadOnlyList<Row> Items)
    {
        public string IdOf(string text) => Items.Single(r => r.Text == text).Id;

        public Checklist Tick(string text) => new(Items.Select(r => r.Text == text ? r with { IsChecked = true } : r).ToList());

        public Checklist Rename(string text, string to) => new(Items.Select(r => r.Text == text ? r with { Text = to } : r).ToList());

        public Checklist Remove(string text) => new(Items.Where(r => r.Text != text).ToList());

        /// <summary>A new row with an id the app made up, as the Android editor does.</summary>
        public Checklist Add(string text, string after)
        {
            var list = Items.ToList();
            list.Insert(list.FindIndex(r => r.Text == after) + 1, new Row(Guid.NewGuid().ToString(), text));
            return new(list);
        }

        public Checklist Move(string text, bool toFront)
        {
            var row = Items.Single(r => r.Text == text);
            var rest = Items.Where(r => r != row);
            return new(toFront ? rest.Prepend(row).ToList() : rest.Append(row).ToList());
        }

        public object[] Payload() => Items
            .Select((r, i) => (object)new { id = r.Id, text = r.Text, isChecked = r.IsChecked, indent = r.Indent, order = i })
            .ToArray();
    }

    private sealed class Person(HttpClient client, string noteId)
    {
        public HttpClient Client { get; } = client;

        /// <summary>Saves <paramref name="rows"/>, started from <paramref name="from"/> (no base when null).</summary>
        public async Task<JsonElement> SaveAsync(Checklist? from, Checklist rows)
        {
            var response = await Client.PutAsJsonAsync($"/api/notes/{noteId}", new
            {
                type = "Checklist",
                checklistItems = rows.Payload(),
                checklistBase = from?.Payload(),
                fields = new[] { "ChecklistItems" },
            });
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            return await response.Content.ReadFromJsonAsync<JsonElement>();
        }
    }

    private sealed class Shop(KeepItApiFactory api, Person anna, Person ben, string noteId, Checklist @base) : IAsyncDisposable
    {
        public KeepItApiFactory Api { get; } = api;
        public Person Anna { get; } = anna;
        public Person Ben { get; } = ben;
        public string NoteId { get; } = noteId;

        /// <summary>The list as both opened it.</summary>
        public Checklist Base { get; } = @base;

        public ValueTask DisposeAsync()
        {
            Anna.Client.Dispose();
            Ben.Client.Dispose();
            Api.Dispose();
            return ValueTask.CompletedTask;
        }
    }

    /// <summary>Anna's checklist of <paramref name="texts"/>, shared with Ben as an Editor.</summary>
    private static async Task<Shop> SharedListAsync(params string[] texts)
    {
        var api = new KeepItApiFactory();
        var anna = await api.CreateSignedInClientAsync();
        var benEmail = $"ben-{Guid.NewGuid():N}@example.com";
        var ben = await api.CreateSignedInClientAsync(benEmail);

        var created = await anna.PostAsJsonAsync("/api/notes", new
        {
            type = "Checklist",
            title = "Shop",
            checklistItems = texts.Select((t, i) => new { text = t, isChecked = false, order = i }).ToArray(),
        });
        created.EnsureSuccessStatusCode();
        var note = await created.Content.ReadFromJsonAsync<JsonElement>();
        var noteId = note.GetProperty("id").GetString()!;

        (await anna.PostAsJsonAsync($"/api/notes/{noteId}/shares", new { email = benEmail, role = "Editor" }))
            .EnsureSuccessStatusCode();
        var invite = (await ben.GetFromJsonAsync<JsonElement>("/api/notifications"))
            .EnumerateArray()
            .Single(n => n.TryGetProperty("sharedNoteId", out var id) && id.GetString() == noteId);
        (await ben.PostAsJsonAsync($"/api/notifications/{invite.GetProperty("id").GetString()}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        return new Shop(api, new Person(anna, noteId), new Person(ben, noteId), noteId, new Checklist(Rows(note)));
    }

    private static List<Row> Rows(JsonElement note) => note.GetProperty("checklistItems")
        .EnumerateArray()
        .OrderBy(i => i.GetProperty("order").GetInt32())
        .Select(i => new Row(
            i.GetProperty("id").GetString()!,
            i.GetProperty("text").GetString()!,
            i.GetProperty("isChecked").GetBoolean(),
            i.GetProperty("indent").GetInt32()))
        .ToList();

    private static string[] Texts(JsonElement note) => Rows(note).Select(r => r.Text).ToArray();

    private static bool CheckedOf(JsonElement note, string text) => Rows(note).Single(r => r.Text == text).IsChecked;
}
