using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Deleting an account: everything the user owns goes — notes, their files, the profile picture,
/// invites they sent, the account itself — while what other people own stays, minus the user. Each
/// test has a host of its own, because the sign-in endpoints allow ten requests a minute and every
/// test here signs up two people and calls a rate-limited endpoint again.
/// </summary>
public sealed class AccountDeletionTests
{
    private const string Password = "Test-password-1";

    [Fact]
    public async Task Deleting_an_account_removes_everything_it_owns_and_leaves_what_others_own()
    {
        using var api = new KeepItApiFactory();
        var annaEmail = $"anna-{Guid.NewGuid():N}@example.com";
        var tomEmail = $"tom-{Guid.NewGuid():N}@example.com";
        using var anna = await api.CreateSignedInClientAsync(annaEmail);
        using var tom = await api.CreateSignedInClientAsync(tomEmail);
        var annaId = (await anna.GetFromJsonAsync<JsonElement>("/api/auth/me")).GetProperty("id").GetString()!;

        // Anna's: a note with a photo, shared with Tom; a note Tom is invited to but hasn't
        // answered; and a profile picture.
        var annasShared = await NewNoteAsync(anna, "Anna's, shared with Tom");
        Assert.Equal(HttpStatusCode.Created,
            (await NoteMediaTests.UploadAsync(anna, annasShared, TestImages.Png(40, 30), "a.png", "image/png")).StatusCode);
        await ShareAsync(anna, annasShared, tom, tomEmail);
        var annasInvited = await NewNoteAsync(anna, "Anna's, invite pending");
        (await anna.PostAsJsonAsync($"/api/notes/{annasInvited}/shares", new { email = tomEmail, role = "Viewer" }))
            .EnsureSuccessStatusCode();
        await UploadProfileImageAsync(anna);
        Assert.True(Directory.Exists(Path.Combine(api.DataRoot, "users", annaId)));

        // Tom's, shared with Anna.
        var tomsShared = await NewNoteAsync(tom, "Tom's, shared with Anna");
        await ShareAsync(tom, tomsShared, anna, annaEmail);

        var response = await anna.PostAsJsonAsync("/api/auth/delete-account", new { password = Password });

        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Contains(response.Headers.GetValues("Set-Cookie"), c => c.Contains("expires=Thu, 01 Jan 1970", StringComparison.OrdinalIgnoreCase));

        // Gone: the account, its notes and their files, and the invite it sent.
        Assert.Equal(HttpStatusCode.Unauthorized, (await anna.GetAsync("/api/auth/me")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,
            (await api.CreateClient().PostAsJsonAsync("/api/auth/login", new { email = annaEmail, password = Password })).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await tom.GetAsync($"/api/notes/{annasShared}")).StatusCode);
        Assert.False(Directory.Exists(Path.Combine(api.DataRoot, "users", annaId)));
        var inbox = await tom.GetFromJsonAsync<JsonElement>("/api/notifications");
        Assert.DoesNotContain(inbox.EnumerateArray(), n =>
            n.TryGetProperty("sharedNoteId", out var id) && id.GetString() == annasInvited);

        // Kept: Tom's note, without Anna as a collaborator.
        Assert.Equal(HttpStatusCode.OK, (await tom.GetAsync($"/api/notes/{tomsShared}")).StatusCode);
        var shares = await tom.GetFromJsonAsync<JsonElement>($"/api/notes/{tomsShared}/shares");
        Assert.Empty(shares.EnumerateArray());

        // And the address is free again.
        using var fresh = api.CreateClient();
        Assert.Equal(HttpStatusCode.OK,
            (await fresh.PostAsJsonAsync("/api/auth/register", new { email = annaEmail, password = Password })).StatusCode);
    }

    [Fact]
    public async Task Another_device_is_refused_once_the_account_is_gone()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var phone = await api.CreateSignedInClientAsync(email);
        // A second device, signed in with its own access token.
        using var laptop = api.CreateClient();
        var login = await laptop.PostAsJsonAsync("/api/auth/login", new { email, password = Password });
        laptop.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
            "Bearer", (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("accessToken").GetString());

        Assert.Equal(HttpStatusCode.NoContent,
            (await phone.PostAsJsonAsync("/api/auth/delete-account", new { password = Password })).StatusCode);

        // Its token hasn't expired, but it names an account that is gone: unauthenticated, so the
        // device refreshes, is refused, and signs out — not an empty account that 500s on writes.
        Assert.Equal(HttpStatusCode.Unauthorized, (await laptop.GetAsync("/api/notes")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,
            (await laptop.PostAsJsonAsync("/api/notes", new { type = "Text", title = "after" })).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await laptop.GetAsync("/api/settings")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await laptop.PostAsync("/api/auth/refresh", null)).StatusCode);
    }

    [Fact]
    public async Task A_wrong_password_deletes_nothing()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        var note = await NewNoteAsync(client, "still here");

        var response = await client.PostAsJsonAsync("/api/auth/delete-account", new { password = "Not-the-password-1" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains("password", await response.Content.ReadAsStringAsync(), StringComparison.OrdinalIgnoreCase);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync($"/api/notes/{note}")).StatusCode);
        Assert.Equal(HttpStatusCode.OK,
            (await api.CreateClient().PostAsJsonAsync("/api/auth/login", new { email, password = Password })).StatusCode);
    }

    [Fact]
    public async Task Deleting_needs_a_signed_in_user()
    {
        using var api = new KeepItApiFactory();
        using var anonymous = api.CreateClient();

        var response = await anonymous.PostAsJsonAsync("/api/auth/delete-account", new { password = Password });

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    private static async Task<string> NewNoteAsync(HttpClient client, string title)
    {
        var response = await client.PostAsJsonAsync("/api/notes", new { type = "Text", title });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static async Task UploadProfileImageAsync(HttpClient client)
    {
        using var form = new MultipartFormDataContent();
        var file = new ByteArrayContent(TestImages.Png(64, 64));
        file.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        form.Add(file, "file", "avatar.png");
        (await client.PostAsync("/api/settings/uploadProfileImage", form)).EnsureSuccessStatusCode();
    }

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
