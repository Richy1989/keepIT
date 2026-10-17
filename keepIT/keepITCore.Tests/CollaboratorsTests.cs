using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.SignalR;
using keepITCore.Tests.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// <c>NoteDto.Collaborators</c>: the faces on a shared note's card. Each person on a note sees
/// everyone else on it, owner first, and never themself; an unanswered invite isn't on it yet. A
/// new profile picture shows up as a new version, and reaches the people who share notes with
/// its owner without them asking.
/// </summary>
public sealed class CollaboratorsTests
{
    private readonly CapturingRealtimeNotifier _realtime = new();

    [Fact]
    public async Task Everyone_on_a_note_sees_everyone_else_owner_first()
    {
        using var api = new KeepItApiFactory();
        var anna = await SignInAsync(api);
        var ben = await SignInAsync(api);
        var cleo = await SignInAsync(api);
        var note = await CreateNoteAsync(anna.Client, "Trip");
        var alone = await CreateNoteAsync(anna.Client, "Diary");

        await InviteAsync(anna.Client, note, ben.Email);
        await AcceptAsync(ben.Client, note);
        await InviteAsync(anna.Client, note, cleo.Email);

        // Cleo hasn't answered, so she isn't on it yet.
        Assert.Equal([ben.Id], Ids(await CollaboratorsAsync(anna.Client, note)));
        Assert.Empty(await CollaboratorsAsync(anna.Client, alone));

        await AcceptAsync(cleo.Client, note);

        var annasView = await CollaboratorsAsync(anna.Client, note);
        Assert.Equal([ben.Id, cleo.Id], Ids(annasView));
        Assert.All(annasView, c => Assert.False(c.GetProperty("isOwner").GetBoolean()));
        Assert.Equal(ben.Email, annasView[0].GetProperty("email").GetString());
        Assert.Equal(JsonValueKind.Null, annasView[0].GetProperty("profileImageVersion").ValueKind);

        var bensView = await CollaboratorsAsync(ben.Client, note);
        Assert.Equal([anna.Id, cleo.Id], Ids(bensView));
        Assert.True(bensView[0].GetProperty("isOwner").GetBoolean());
        Assert.Equal([anna.Id, ben.Id], Ids(await CollaboratorsAsync(cleo.Client, note)));

        // A single note, read on its own, says the same as the grid.
        var single = await ben.Client.GetFromJsonAsync<JsonElement>($"/api/notes/{note}");
        Assert.Equal([anna.Id, cleo.Id], Ids(single.GetProperty("collaborators").EnumerateArray().ToArray()));
    }

    [Fact]
    public async Task A_new_picture_is_a_new_version_and_reaches_the_people_sharing_notes()
    {
        using var api = new KeepItApiFactory { ServiceOverrides = s => s.AddSingleton<IRealtimeNotifier>(_realtime) };
        var anna = await SignInAsync(api);
        var ben = await SignInAsync(api);
        var stranger = await SignInAsync(api);
        var note = await CreateNoteAsync(anna.Client, "Trip");
        await InviteAsync(anna.Client, note, ben.Email);
        await AcceptAsync(ben.Client, note);

        _realtime.Pushes.Clear();
        await UploadProfileImageAsync(ben.Client);
        var first = (await CollaboratorsAsync(anna.Client, note))[0].GetProperty("profileImageVersion").GetString();
        Assert.False(string.IsNullOrEmpty(first));
        Assert.Contains(_realtime.Pushes, p => p.UserId == anna.Id && p.Resources.Contains(RealtimeResources.Notes));
        Assert.DoesNotContain(_realtime.Pushes, p => p.UserId == stranger.Id);

        // The other way round too: the owner's new picture reaches the collaborator.
        _realtime.Pushes.Clear();
        await UploadProfileImageAsync(anna.Client);
        Assert.Contains(_realtime.Pushes, p => p.UserId == ben.Id && p.Resources.Contains(RealtimeResources.Notes));

        await UploadProfileImageAsync(ben.Client);
        var second = (await CollaboratorsAsync(anna.Client, note))[0].GetProperty("profileImageVersion").GetString();
        Assert.NotEqual(first, second);
    }

    private sealed record User(HttpClient Client, Guid Id, string Email);

    private static async Task<User> SignInAsync(KeepItApiFactory api)
    {
        var client = await api.CreateSignedInClientAsync();
        var me = await client.GetFromJsonAsync<JsonElement>("/api/auth/me");
        return new User(client, Guid.Parse(me.GetProperty("id").GetString()!), me.GetProperty("email").GetString()!);
    }

    private static async Task<string> CreateNoteAsync(HttpClient owner, string title) =>
        (await (await owner.PostAsJsonAsync("/api/notes", new { type = "Text", title }))
            .Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;

    private static async Task InviteAsync(HttpClient owner, string note, string email) =>
        (await owner.PostAsJsonAsync($"/api/notes/{note}/shares", new { email, role = "Editor" })).EnsureSuccessStatusCode();

    private static async Task AcceptAsync(HttpClient invitee, string note)
    {
        var invite = (await invitee.GetFromJsonAsync<JsonElement>("/api/notifications"))
            .EnumerateArray()
            .Single(n => n.TryGetProperty("sharedNoteId", out var id) && id.GetString() == note);
        (await invitee.PostAsJsonAsync($"/api/notifications/{invite.GetProperty("id").GetString()}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }

    private static async Task<JsonElement[]> CollaboratorsAsync(HttpClient client, string note)
    {
        var grid = await client.GetFromJsonAsync<JsonElement>("/api/notes");
        return grid.EnumerateArray()
            .Single(n => n.GetProperty("id").GetString() == note)
            .GetProperty("collaborators").EnumerateArray().ToArray();
    }

    private static Guid[] Ids(JsonElement[] collaborators) =>
        collaborators.Select(c => Guid.Parse(c.GetProperty("userId").GetString()!)).ToArray();

    private static async Task UploadProfileImageAsync(HttpClient client)
    {
        using var form = new MultipartFormDataContent();
        var file = new ByteArrayContent(TestImages.Png(64, 64));
        file.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        form.Add(file, "file", "avatar.png");
        (await client.PostAsync("/api/settings/uploadProfileImage", form)).EnsureSuccessStatusCode();
    }
}
