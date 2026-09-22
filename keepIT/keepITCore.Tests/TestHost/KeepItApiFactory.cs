using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests.TestHost;

/// <summary>
/// The real API, in-process, on a throwaway data root of its own — SQLite file, media and keys —
/// deleted again on dispose. Runs as Development, which supplies a JWT key and a refresh cookie
/// that works over the test server's plain HTTP.
/// </summary>
public sealed class KeepItApiFactory : WebApplicationFactory<Program>
{
    /// <summary>The data root this host reads and writes.</summary>
    public string DataRoot { get; }

    /// <summary>
    /// Extra configuration for this host (e.g. <c>App:PublicBaseUrl</c>), applied before it starts —
    /// so values the API reads during startup, not only per request, see them too.
    /// </summary>
    public Dictionary<string, string?> Settings { get; } = new();

    /// <summary>Service replacements for this host, applied after the API's own registrations.</summary>
    public Action<IServiceCollection>? Services { get; init; }

    /// <param name="dataRoot">An existing data root to start on (e.g. one holding an old database), or null for a fresh one.</param>
    public KeepItApiFactory(string? dataRoot = null)
    {
        DataRoot = dataRoot ?? Directory.CreateTempSubdirectory("keepit-tests-").FullName;
    }

    /// <inheritdoc />
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Development");
        builder.UseSetting("App:DataRoot", DataRoot);
        // Never pick up a developer's Postgres: these tests are about the SQLite path.
        builder.UseSetting("ConnectionStrings:Postgres", "");
        builder.UseSetting("POSTGRES_HOST", "");

        foreach (var (key, value) in Settings)
            builder.UseSetting(key, value);
        if (Services is not null)
            builder.ConfigureTestServices(Services);
    }

    /// <summary>Registers a fresh account and returns its email (the client stays signed out).</summary>
    public async Task<string> RegisterUserAsync()
    {
        using var client = CreateClient();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        (await client.PostAsJsonAsync("/api/auth/register", new { email, password = "Test-password-1" }))
            .EnsureSuccessStatusCode();
        return email;
    }

    /// <summary>Registers a fresh user and returns a client that sends their access token.</summary>
    /// <param name="email">The account's email, for a test that needs to name the user (sharing); a random one otherwise.</param>
    public async Task<HttpClient> CreateSignedInClientAsync(string? email = null)
    {
        var client = CreateClient();
        email ??= $"user-{Guid.NewGuid():N}@example.com";
        const string password = "Test-password-1";

        (await client.PostAsJsonAsync("/api/auth/register", new { email, password })).EnsureSuccessStatusCode();

        var login = await client.PostAsJsonAsync("/api/auth/login", new { email, password });
        login.EnsureSuccessStatusCode();
        var body = await login.Content.ReadFromJsonAsync<JsonElement>();
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", body.GetProperty("accessToken").GetString());

        return client;
    }

    /// <inheritdoc />
    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (!disposing) return;

        // Pooled connections keep the SQLite file open, and Windows won't delete an open file.
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(DataRoot, recursive: true); }
        catch (IOException) { /* best-effort: it's a temp folder */ }
        catch (UnauthorizedAccessException) { }
    }
}
