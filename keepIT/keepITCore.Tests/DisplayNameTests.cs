using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.SignalR;
using keepITCore.Tests.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// One API host and two users for the display-name tests, signed up once: the sign-in endpoints
/// allow a handful of requests a minute. <see cref="Bystander"/> never renames, so any push that
/// reaches it would be one too many.
/// </summary>
public sealed class DisplayNameHost : IAsyncLifetime
{
    public CapturingRealtimeNotifier Realtime { get; } = new();
    public KeepItApiFactory Api { get; }
    public HttpClient Renamer { get; private set; } = null!;
    public HttpClient Bystander { get; private set; } = null!;

    public DisplayNameHost()
    {
        Api = new KeepItApiFactory { ServiceOverrides = s => s.AddSingleton<IRealtimeNotifier>(Realtime) };
    }

    public async Task InitializeAsync()
    {
        Renamer = await Api.CreateSignedInClientAsync();
        Bystander = await Api.CreateSignedInClientAsync();
    }

    public Task DisposeAsync()
    {
        Renamer.Dispose();
        Bystander.Dispose();
        Api.Dispose();
        return Task.CompletedTask;
    }
}

/// <summary>
/// <c>PUT /api/auth/me</c>: a display name can be set, changed and removed after sign-up. Removing
/// it stores null, the same as an account registered without one, so the clients' fallback to the
/// email applies; and the change reaches the owner's other devices, nobody else's.
/// </summary>
public sealed class DisplayNameTests(DisplayNameHost host) : IClassFixture<DisplayNameHost>
{
    [Fact]
    public async Task A_new_name_is_saved_trimmed_and_read_back()
    {
        var response = await host.Renamer.PutAsJsonAsync("/api/auth/me", new { displayName = "  Ada Lovelace  " });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var returned = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("Ada Lovelace", returned.GetProperty("displayName").GetString());

        var me = await host.Renamer.GetFromJsonAsync<JsonElement>("/api/auth/me");
        Assert.Equal("Ada Lovelace", me.GetProperty("displayName").GetString());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public async Task A_blank_name_removes_it(string? blank)
    {
        (await host.Renamer.PutAsJsonAsync("/api/auth/me", new { displayName = "Temporary" })).EnsureSuccessStatusCode();

        var response = await host.Renamer.PutAsJsonAsync("/api/auth/me", new { displayName = blank });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var me = await host.Renamer.GetFromJsonAsync<JsonElement>("/api/auth/me");
        Assert.Equal(JsonValueKind.Null, me.GetProperty("displayName").ValueKind);
    }

    [Fact]
    public async Task A_name_over_100_characters_is_refused()
    {
        var response = await host.Renamer.PutAsJsonAsync("/api/auth/me", new { displayName = new string('x', 101) });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task A_rename_tells_only_the_owners_devices()
    {
        var renamerId = (await host.Renamer.GetFromJsonAsync<JsonElement>("/api/auth/me")).GetProperty("id").GetGuid();
        var bystanderId = (await host.Bystander.GetFromJsonAsync<JsonElement>("/api/auth/me")).GetProperty("id").GetGuid();
        host.Realtime.Pushes.Clear();

        (await host.Renamer.PutAsJsonAsync("/api/auth/me", new { displayName = $"Renamed {Guid.NewGuid():N}" }))
            .EnsureSuccessStatusCode();

        var push = Assert.Single(host.Realtime.Pushes);
        Assert.Equal(renamerId, push.UserId);
        Assert.Equal([RealtimeResources.Account], push.Resources);
        Assert.DoesNotContain(host.Realtime.Pushes, p => p.UserId == bystanderId);
    }

    [Fact]
    public async Task Renaming_needs_a_signed_in_user()
    {
        using var anonymous = host.Api.CreateClient();

        var response = await anonymous.PutAsJsonAsync("/api/auth/me", new { displayName = "Nobody" });

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }
}
