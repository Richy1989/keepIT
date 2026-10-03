using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// One API host and two users for the accent tests, signed up once: the sign-in endpoints allow a
/// handful of requests a minute. <see cref="Fresh"/> only ever reads, so its settings row is the
/// one the server creates on first access.
/// </summary>
public sealed class AccentHost : IAsyncLifetime
{
    public KeepItApiFactory Api { get; } = new();
    public HttpClient Fresh { get; private set; } = null!;
    public HttpClient Chooser { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        Fresh = await Api.CreateSignedInClientAsync();
        Chooser = await Api.CreateSignedInClientAsync();
    }

    public Task DisposeAsync()
    {
        Fresh.Dispose();
        Chooser.Dispose();
        Api.Dispose();
        return Task.CompletedTask;
    }
}

/// <summary>
/// The accent a new account starts on, and the keys the server accepts. Forest is the brand green
/// of the app icon; the server default has to agree with the web's <c>DEFAULT_ACCENT</c>
/// and the Android app's fixed accent, or a new account would flash from one to the other.
/// </summary>
public sealed class AccentSettingsTests(AccentHost host) : IClassFixture<AccentHost>
{
    [Fact]
    public async Task A_new_account_starts_on_the_forest_accent()
    {
        var settings = await host.Fresh.GetFromJsonAsync<JsonElement>("/api/settings");

        Assert.Equal("forest", settings.GetProperty("globalAccentColor").GetString());
    }

    [Theory]
    [InlineData("forest")]
    [InlineData("yellow")]
    public async Task Forest_and_the_old_default_are_both_accepted(string accent)
    {
        var response = await host.Chooser.PutAsJsonAsync("/api/settings", new { theme = "dark", globalAccentColor = accent });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var saved = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(accent, saved.GetProperty("globalAccentColor").GetString());
    }

    [Fact]
    public async Task An_unknown_accent_is_refused()
    {
        var response = await host.Chooser.PutAsJsonAsync("/api/settings", new { theme = "dark", globalAccentColor = "chartreuse" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}
