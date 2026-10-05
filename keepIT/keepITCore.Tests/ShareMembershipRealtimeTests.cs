using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.SignalR;
using keepITCore.Tests.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// Who hears that a note's membership changed. "People with access" and the shared badge show on
/// the owner's and every collaborator's devices, so each change has to reach all of them — the web
/// owner used to see an accepted invite as "Pending", and a collaborator who had left as still
/// there, because only the person the change was about was told.
/// </summary>
public sealed class ShareMembershipRealtimeTests
{
    private readonly CapturingRealtimeNotifier _realtime = new();

    [Fact]
    public async Task Every_membership_change_reaches_everyone_on_the_note()
    {
        using var api = new KeepItApiFactory { Services = s => s.AddSingleton<IRealtimeNotifier>(_realtime) };
        var (anna, annaId) = await SignInAsync(api);
        var (ben, benId) = await SignInAsync(api);
        var (cleo, cleoId) = await SignInAsync(api);
        var note = (await (await anna.PostAsJsonAsync("/api/notes", new { type = "Text", title = "Trip" }))
            .Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
        await InviteAsync(anna, note, cleo, await EmailAsync(cleo), accept: true);

        // Ben accepts: Anna's pending row settles, and Cleo gains a co-collaborator.
        await InviteAsync(anna, note, ben, await EmailAsync(ben), accept: false);
        _realtime.Pushes.Clear();
        await RespondAsync(ben, note, accept: true);
        AssertToldAboutNotes(annaId, cleoId, benId);

        // Anna makes Ben a viewer: Cleo sees the new role too.
        _realtime.Pushes.Clear();
        (await anna.PatchAsJsonAsync($"/api/notes/{note}/shares/{benId}", new { role = "Viewer" })).EnsureSuccessStatusCode();
        AssertToldAboutNotes(annaId, cleoId, benId);

        // Ben leaves: the owner is told, and so is Cleo.
        _realtime.Pushes.Clear();
        (await ben.DeleteAsync($"/api/notes/{note}/shares/{benId}")).EnsureSuccessStatusCode();
        AssertToldAboutNotes(annaId, cleoId, benId);
    }

    private void AssertToldAboutNotes(params Guid[] users)
    {
        foreach (var user in users)
            Assert.Contains(_realtime.Pushes, p => p.UserId == user && p.Resources.Contains(RealtimeResources.Notes));
    }

    private static async Task<(HttpClient Client, Guid Id)> SignInAsync(KeepItApiFactory api)
    {
        var client = await api.CreateSignedInClientAsync();
        var me = await client.GetFromJsonAsync<JsonElement>("/api/auth/me");
        return (client, Guid.Parse(me.GetProperty("id").GetString()!));
    }

    private static async Task<string> EmailAsync(HttpClient client) =>
        (await client.GetFromJsonAsync<JsonElement>("/api/auth/me")).GetProperty("email").GetString()!;

    private static async Task InviteAsync(HttpClient owner, string note, HttpClient invitee, string email, bool accept)
    {
        (await owner.PostAsJsonAsync($"/api/notes/{note}/shares", new { email, role = "Editor" })).EnsureSuccessStatusCode();
        if (accept) await RespondAsync(invitee, note, accept: true);
    }

    private static async Task RespondAsync(HttpClient invitee, string note, bool accept)
    {
        var invite = (await invitee.GetFromJsonAsync<JsonElement>("/api/notifications"))
            .EnumerateArray()
            .Single(n => n.TryGetProperty("sharedNoteId", out var id) && id.GetString() == note);
        (await invitee.PostAsJsonAsync($"/api/notifications/{invite.GetProperty("id").GetString()}/respond", new { accept }))
            .EnsureSuccessStatusCode();
    }
}
