using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Data;
using keepITCore.SignalR;
using keepITCore.Tests.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// Public links (ARCHITECTURE.md "Public links"): an owner publishes a note, read-only or
/// editable, with an optional password and expiry, and anyone holding the link opens it without an
/// account. These pin down who may publish, what a visitor can reach and change, and that every
/// way a link stops working ends the views already open on it too.
/// </summary>
public sealed class PublicLinkTests : IDisposable
{
    private readonly CapturingRealtimeNotifier _realtime = new();
    private readonly KeepItApiFactory _api;

    public PublicLinkTests()
    {
        _api = new KeepItApiFactory { ServiceOverrides = s => s.AddSingleton<IRealtimeNotifier>(_realtime) };
    }

    public void Dispose() => _api.Dispose();

    // ---- publishing ----

    [Fact]
    public async Task Only_the_owner_may_publish_a_note()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var ben = await _api.CreateSignedInClientAsync();
        var stranger = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        await ShareAsync(anna, note, ben, "Editor");

        Assert.Equal(HttpStatusCode.Forbidden, (await PublishAsync(ben, note, new { })).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await PublishAsync(stranger, note, new { })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await ben.DeleteAsync($"/api/notes/{note}/public-link")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await PublishAsync(anna, note, new { })).StatusCode);
    }

    [Fact]
    public async Task A_published_note_shows_as_public_to_its_owner_only()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var ben = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        await ShareAsync(anna, note, ben, "Viewer");

        Assert.False(await IsPublicAsync(anna, note));
        await PublishOkAsync(anna, note, new { });

        Assert.True(await IsPublicAsync(anna, note));
        Assert.False(await IsPublicAsync(ben, note));
    }

    [Fact]
    public async Task Publishing_twice_keeps_one_link_and_the_same_token()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");

        var first = await PublishOkAsync(anna, note, new { });
        var second = await PublishOkAsync(anna, note, new { allowEdit = true });

        Assert.Equal(first.GetProperty("token").GetString(), second.GetProperty("token").GetString());
        Assert.True(second.GetProperty("allowEdit").GetBoolean());
        var fetched = await anna.GetFromJsonAsync<JsonElement>($"/api/notes/{note}/public-link");
        Assert.Equal(first.GetProperty("token").GetString(), fetched.GetProperty("token").GetString());
    }

    [Fact]
    public async Task An_unpublished_note_has_no_link()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");

        Assert.Equal(HttpStatusCode.NoContent, (await anna.GetAsync($"/api/notes/{note}/public-link")).StatusCode);
    }

    [Fact]
    public async Task An_expiry_in_the_past_is_refused()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");

        var response = await PublishAsync(anna, note, new { expiresAtUtc = DateTime.UtcNow.AddMinutes(-1) });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task The_link_is_built_only_on_the_configured_public_address()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var bare = await PublishOkAsync(anna, note, new { });
        Assert.Equal(JsonValueKind.Null, bare.GetProperty("url").ValueKind);

        using var configured = new KeepItApiFactory();
        configured.Settings["App:PublicBaseUrl"] = "https://notes.example.com";
        var owner = await configured.CreateSignedInClientAsync();
        var other = await CreateNoteAsync(owner, "Plans");
        var link = await PublishOkAsync(owner, other, new { });

        Assert.Equal($"https://notes.example.com/p#{link.GetProperty("token").GetString()}", link.GetProperty("url").GetString());
    }

    // ---- opening ----

    [Fact]
    public async Task Anyone_with_the_link_reads_the_note_and_nothing_about_anyone()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans", body: "Bring snacks");
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;

        using var visitor = _api.CreateClient();
        var response = await OpenAsync(visitor, token);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.CacheControl is { NoStore: true, Private: true });
        var json = await response.Content.ReadAsStringAsync();
        var opened = JsonDocument.Parse(json).RootElement;
        Assert.Equal("Plans", opened.GetProperty("title").GetString());
        Assert.Equal("Bring snacks", opened.GetProperty("body").GetString());
        Assert.False(opened.GetProperty("canEdit").GetBoolean());
        Assert.DoesNotContain("@example.com", json);
        Assert.DoesNotContain("owner", json, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task An_unknown_token_is_not_found()
    {
        using var visitor = _api.CreateClient();

        Assert.Equal(HttpStatusCode.NotFound, (await OpenAsync(visitor, "no-such-token")).StatusCode);
    }

    [Fact]
    public async Task A_password_is_needed_and_must_match()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var link = await PublishOkAsync(anna, note, new { password = "open sesame" });
        var token = link.GetProperty("token").GetString()!;
        Assert.True(link.GetProperty("hasPassword").GetBoolean());

        using var visitor = _api.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await OpenAsync(visitor, token)).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await OpenAsync(visitor, token, "wrong")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await OpenAsync(visitor, token, "open sesame")).StatusCode);
    }

    [Fact]
    public async Task The_password_is_stored_hashed_and_the_token_never_in_clear()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { password = "open sesame" })).GetProperty("token").GetString()!;

        using var scope = _api.Services.CreateScope();
        var row = await scope.ServiceProvider.GetRequiredService<AppDbContext>().NotePublicLinks.SingleAsync();

        Assert.DoesNotContain("open sesame", row.PasswordHash);
        Assert.NotEqual(token, row.TokenHash);
        Assert.DoesNotContain(token, row.TokenProtected);
    }

    [Fact]
    public async Task The_password_can_be_kept_changed_and_removed()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { password = "first" })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();

        // A change that names no password keeps it.
        await PublishOkAsync(anna, note, new { allowEdit = true });
        Assert.Equal(HttpStatusCode.OK, (await OpenAsync(visitor, token, "first")).StatusCode);

        await PublishOkAsync(anna, note, new { password = "second" });
        Assert.Equal(HttpStatusCode.Unauthorized, (await OpenAsync(visitor, token, "first")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await OpenAsync(visitor, token, "second")).StatusCode);

        await PublishOkAsync(anna, note, new { clearPassword = true });
        Assert.Equal(HttpStatusCode.OK, (await OpenAsync(visitor, token)).StatusCode);
    }

    [Fact]
    public async Task An_expired_link_opens_nothing()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { expiresAtUtc = DateTime.UtcNow.AddHours(1) })).GetProperty("token").GetString()!;

        using (var scope = _api.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var row = await db.NotePublicLinks.SingleAsync();
            row.ExpiresAtUtc = DateTime.UtcNow.AddMinutes(-1);
            await db.SaveChangesAsync();
        }

        using var visitor = _api.CreateClient();
        Assert.Equal(HttpStatusCode.NotFound, (await OpenAsync(visitor, token)).StatusCode);
        Assert.False(await IsPublicAsync(anna, note));
    }

    [Fact]
    public async Task A_revoked_link_and_its_open_views_stop_working()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var media = await AttachImageAsync(anna, note);
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        (await anna.DeleteAsync($"/api/notes/{note}/public-link")).EnsureSuccessStatusCode();

        Assert.Equal(HttpStatusCode.NotFound, (await OpenAsync(visitor, token)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await GetMediaAsync(visitor, media, ticket)).StatusCode);
        Assert.False(await IsPublicAsync(anna, note));
    }

    [Fact]
    public async Task A_new_token_ends_the_old_link_and_its_views()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var media = await AttachImageAsync(anna, note);
        var oldToken = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var oldTicket = await TicketAsync(visitor, oldToken);

        var newToken = (await PublishOkAsync(anna, note, new { regenerate = true })).GetProperty("token").GetString()!;

        Assert.NotEqual(oldToken, newToken);
        Assert.Equal(HttpStatusCode.NotFound, (await OpenAsync(visitor, oldToken)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await GetMediaAsync(visitor, media, oldTicket)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await OpenAsync(visitor, newToken)).StatusCode);
    }

    [Fact]
    public async Task A_note_in_its_owners_trash_is_offline_until_restored()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();

        (await anna.PatchAsJsonAsync($"/api/notes/{note}/state", new { isTrashed = true })).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.NotFound, (await OpenAsync(visitor, token)).StatusCode);

        (await anna.PatchAsJsonAsync($"/api/notes/{note}/state", new { isTrashed = false })).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.OK, (await OpenAsync(visitor, token)).StatusCode);
    }

    [Fact]
    public async Task Deleting_the_note_deletes_its_link()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;

        (await anna.DeleteAsync($"/api/notes/{note}")).EnsureSuccessStatusCode();

        using var visitor = _api.CreateClient();
        Assert.Equal(HttpStatusCode.NotFound, (await OpenAsync(visitor, token)).StatusCode);
        using var scope = _api.Services.CreateScope();
        Assert.False(await scope.ServiceProvider.GetRequiredService<AppDbContext>().NotePublicLinks.AnyAsync());
    }

    // ---- attachments ----

    [Fact]
    public async Task Attachments_are_read_with_the_ticket_and_only_the_notes_own()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var other = await CreateNoteAsync(anna, "Private");
        var media = await AttachImageAsync(anna, note);
        var otherMedia = await AttachImageAsync(anna, other);
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        var thumb = await GetMediaAsync(visitor, media, ticket, "thumb");
        Assert.Equal(HttpStatusCode.OK, thumb.StatusCode);
        Assert.Equal("image/jpeg", thumb.Content.Headers.ContentType?.MediaType);

        Assert.Equal(HttpStatusCode.NotFound, (await GetMediaAsync(visitor, media, null)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await GetMediaAsync(visitor, media, "forged")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await GetMediaAsync(visitor, otherMedia, ticket)).StatusCode);
    }

    // ---- editing ----

    [Fact]
    public async Task A_read_only_link_refuses_edits()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        var response = await EditAsync(visitor, ticket, new { title = "Changed", fields = new[] { "Title" } });

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal("Plans", await TitleAsync(anna, note));
    }

    [Fact]
    public async Task An_editable_link_changes_the_text_and_the_checklist_and_tells_everyone_on_the_note()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var ben = await _api.CreateSignedInClientAsync();
        var annaId = await IdAsync(anna);
        var benId = await IdAsync(ben);
        var note = await CreateNoteAsync(anna, "Shopping", type: "Checklist",
            items: new object[] { new { text = "Milk", isChecked = false } });
        await ShareAsync(anna, note, ben, "Viewer");
        var token = (await PublishOkAsync(anna, note, new { allowEdit = true })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var opened = await (await OpenAsync(visitor, token)).Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(opened.GetProperty("canEdit").GetBoolean());
        var ticket = opened.GetProperty("viewTicket").GetString()!;
        var milk = opened.GetProperty("checklistItems")[0];
        _realtime.Pushes.Clear();

        var response = await EditAsync(visitor, ticket, new
        {
            type = "Checklist",
            title = "Shopping today",
            checklistItems = new object[]
            {
                new { id = milk.GetProperty("id").GetString(), text = "Milk", isChecked = true },
                new { text = "Bread", isChecked = false },
            },
            checklistBase = new object[]
            {
                new { id = milk.GetProperty("id").GetString(), text = "Milk", isChecked = false },
            },
            fields = new[] { "Title", "ChecklistItems" },
        });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var saved = await anna.GetFromJsonAsync<JsonElement>($"/api/notes/{note}");
        Assert.Equal("Shopping today", saved.GetProperty("title").GetString());
        var items = saved.GetProperty("checklistItems").EnumerateArray().ToList();
        Assert.Equal(["Milk", "Bread"], items.Select(i => i.GetProperty("text").GetString()));
        Assert.True(items[0].GetProperty("isChecked").GetBoolean());
        Assert.Contains(_realtime.Pushes, p => p.UserId == annaId && p.Resources.Contains(RealtimeResources.Notes));
        Assert.Contains(_realtime.Pushes, p => p.UserId == benId && p.Resources.Contains(RealtimeResources.Notes));
    }

    [Theory]
    [InlineData("Color")]
    [InlineData("Background")]
    [InlineData("Type")]
    public async Task A_visitor_may_not_change_the_notes_look_or_type(string field)
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { allowEdit = true })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        var response = await EditAsync(visitor, ticket, new { type = "Checklist", color = "red", background = "lined", fields = new[] { field } });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task An_edit_that_names_no_fields_is_refused()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { allowEdit = true })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        // Without fields an update sets everything, colour and type included.
        var response = await EditAsync(visitor, ticket, new { title = "Changed", color = "red" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("Plans", await TitleAsync(anna, note));
    }

    [Fact]
    public async Task A_visitors_edit_is_held_to_the_note_limits()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { allowEdit = true })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        var response = await EditAsync(visitor, ticket, new { body = new string('x', NoteLimits.Body + 1), fields = new[] { "Body" } });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Turning_editing_off_ends_the_open_editors_at_once()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { allowEdit = true })).GetProperty("token").GetString()!;
        using var visitor = _api.CreateClient();
        var ticket = await TicketAsync(visitor, token);

        await PublishOkAsync(anna, note, new { allowEdit = false });

        Assert.Equal(HttpStatusCode.NotFound,
            (await EditAsync(visitor, ticket, new { title = "Changed", fields = new[] { "Title" } })).StatusCode);
        Assert.Equal("Plans", await TitleAsync(anna, note));
    }

    // ---- what a link is not ----

    [Fact]
    public async Task An_export_carries_no_link()
    {
        var anna = await _api.CreateSignedInClientAsync();
        var note = await CreateNoteAsync(anna, "Plans");
        var token = (await PublishOkAsync(anna, note, new { })).GetProperty("token").GetString()!;

        var archive = await anna.GetByteArrayAsync("/api/export");
        using var zip = new System.IO.Compression.ZipArchive(new MemoryStream(archive));
        using var reader = new StreamReader(zip.GetEntry("keepit-export.json")!.Open());
        var json = await reader.ReadToEndAsync();

        Assert.DoesNotContain(token, json);
        Assert.Contains("\"isPublic\":false", json.Replace(" ", ""));
    }

    // ---- helpers ----

    private static async Task<string> CreateNoteAsync(
        HttpClient client, string title, string? body = null, string type = "Text", object[]? items = null)
    {
        var response = await client.PostAsJsonAsync("/api/notes", new { type, title, body, checklistItems = items });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static Task<HttpResponseMessage> PublishAsync(HttpClient client, string note, object settings) =>
        client.PutAsJsonAsync($"/api/notes/{note}/public-link", settings);

    private static async Task<JsonElement> PublishOkAsync(HttpClient client, string note, object settings)
    {
        var response = await PublishAsync(client, note, settings);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static Task<HttpResponseMessage> OpenAsync(HttpClient visitor, string token, string? password = null) =>
        visitor.PostAsJsonAsync("/api/public/open", new { token, password });

    private static async Task<string> TicketAsync(HttpClient visitor, string token)
    {
        var response = await OpenAsync(visitor, token);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("viewTicket").GetString()!;
    }

    private static Task<HttpResponseMessage> GetMediaAsync(HttpClient visitor, string mediaId, string? ticket, string size = "full")
    {
        var request = new HttpRequestMessage(HttpMethod.Get, $"/api/public/media/{mediaId}?size={size}");
        if (ticket is not null) request.Headers.Add("X-View-Ticket", ticket);
        return visitor.SendAsync(request);
    }

    private static Task<HttpResponseMessage> EditAsync(HttpClient visitor, string ticket, object edit)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, "/api/public/note") { Content = JsonContent.Create(edit) };
        request.Headers.Add("X-View-Ticket", ticket);
        return visitor.SendAsync(request);
    }

    private static async Task<string> AttachImageAsync(HttpClient client, string note)
    {
        var response = await NoteMediaTests.UploadAsync(client, note, TestImages.Png(64, 48), "a.png", "image/png");
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static async Task<bool> IsPublicAsync(HttpClient client, string note) =>
        (await client.GetFromJsonAsync<JsonElement>($"/api/notes/{note}")).GetProperty("isPublic").GetBoolean();

    private static async Task<string?> TitleAsync(HttpClient client, string note) =>
        (await client.GetFromJsonAsync<JsonElement>($"/api/notes/{note}")).GetProperty("title").GetString();

    private static async Task<Guid> IdAsync(HttpClient client) =>
        Guid.Parse((await client.GetFromJsonAsync<JsonElement>("/api/auth/me")).GetProperty("id").GetString()!);

    private static async Task ShareAsync(HttpClient owner, string note, HttpClient invitee, string role)
    {
        var email = (await invitee.GetFromJsonAsync<JsonElement>("/api/auth/me")).GetProperty("email").GetString();
        (await owner.PostAsJsonAsync($"/api/notes/{note}/shares", new { email, role })).EnsureSuccessStatusCode();
        var invite = (await invitee.GetFromJsonAsync<JsonElement>("/api/notifications"))
            .EnumerateArray()
            .Single(n => n.TryGetProperty("sharedNoteId", out var id) && id.GetString() == note);
        (await invitee.PostAsJsonAsync($"/api/notifications/{invite.GetProperty("id").GetString()}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }
}
