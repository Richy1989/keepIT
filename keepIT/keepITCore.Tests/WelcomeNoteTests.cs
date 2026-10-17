using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Data;
using keepITCore.Notes;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// A new account starts with the <see cref="WelcomeNote"/>: one blue note of its own, with the
/// GitHub link for questions, that the user owns like any other. Test hosts switch it off (see
/// <see cref="KeepItApiFactory"/>), so these tests switch it back on — and check that off means a
/// new account starts empty, since an operator can choose that too.
/// </summary>
public sealed class WelcomeNoteTests
{
    [Fact]
    public async Task A_new_account_starts_with_one_blue_welcome_note_of_its_own()
    {
        using var api = new KeepItApiFactory();
        api.Settings[WelcomeNote.SettingKey] = "true";
        using var first = await api.CreateSignedInClientAsync();
        using var second = await api.CreateSignedInClientAsync();

        var note = Assert.Single(await GridAsync(first));
        Assert.Equal("Text", note.GetProperty("type").GetString());
        Assert.Equal(WelcomeNote.Title, note.GetProperty("title").GetString());
        Assert.Equal("sky", note.GetProperty("color").GetString());
        Assert.Contains(" https://github.com/Richy1989/keepIT/issues\n", note.GetProperty("body").GetString());
        Assert.True(note.GetProperty("isOwner").GetBoolean());
        Assert.False(note.GetProperty("isShared").GetBoolean());
        Assert.False(note.GetProperty("isPinned").GetBoolean());

        // Each account gets a welcome of its own, not a share of somebody else's.
        var other = Assert.Single(await GridAsync(second));
        Assert.Equal(WelcomeNote.Title, other.GetProperty("title").GetString());
        Assert.NotEqual(note.GetProperty("id").GetString(), other.GetProperty("id").GetString());
    }

    [Fact]
    public async Task Switched_off_a_new_account_starts_empty()
    {
        using var api = new KeepItApiFactory();
        api.Settings[WelcomeNote.SettingKey] = "false";
        using var client = await api.CreateSignedInClientAsync();

        Assert.Empty(await GridAsync(client));
    }

    [Fact]
    public void The_welcome_note_fits_what_a_note_may_hold()
    {
        // It is never validated on its way in, so hold it to the limits here: the clients refuse
        // to save a note past them, and the user could then never edit their welcome note.
        Assert.InRange(WelcomeNote.Title.Length, 1, NoteLimits.Title);
        Assert.InRange(WelcomeNote.Body.Length, 1, NoteLimits.Body);
        Assert.InRange(WelcomeNote.Color.Length, 1, NoteLimits.Color);
        Assert.DoesNotContain('\r', WelcomeNote.Body);
    }

    private static async Task<JsonElement[]> GridAsync(HttpClient client) =>
        (await client.GetFromJsonAsync<JsonElement>("/api/notes")).EnumerateArray().ToArray();
}
