using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>One API host and one user for the list icon tests: sign-up is rate limited.</summary>
public sealed class ListIconHost : IAsyncLifetime
{
    public KeepItApiFactory Api { get; } = new();
    public HttpClient Client { get; private set; } = null!;

    public async Task InitializeAsync() => Client = await Api.CreateSignedInClientAsync();

    public Task DisposeAsync()
    {
        Client.Dispose();
        Api.Dispose();
        return Task.CompletedTask;
    }
}

/// <summary>
/// A list's optional icon: one emoji or other single symbol, set when the list is created or
/// later. On <c>PATCH</c> a null icon leaves it alone, so an empty one is how it is removed.
/// </summary>
public sealed class ListIconTests(ListIconHost host) : IClassFixture<ListIconHost>
{
    private async Task<JsonElement> CreateAsync(object body)
    {
        var response = await host.Client.PostAsJsonAsync("/api/lists", body);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private async Task<JsonElement> PatchAsync(string id, object body)
    {
        var response = await host.Client.PatchAsJsonAsync($"/api/lists/{id}", body);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    /// <summary>The list as <c>GET /api/lists</c> serves it, which is what the clients read.</summary>
    private async Task<JsonElement> ReadBackAsync(string id) =>
        Assert.Single((await host.Client.GetFromJsonAsync<JsonElement>("/api/lists")).EnumerateArray(),
            l => l.GetProperty("id").GetString() == id);

    private static string Id(JsonElement list) => list.GetProperty("id").GetString()!;

    [Fact]
    public async Task A_list_is_created_with_an_icon_and_serves_it()
    {
        var created = await CreateAsync(new { name = "Groceries", icon = "🛒" });

        Assert.Equal("🛒", created.GetProperty("icon").GetString());
        Assert.Equal("🛒", (await ReadBackAsync(Id(created))).GetProperty("icon").GetString());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("  ")]
    public async Task A_list_created_without_an_icon_has_none(string? none)
    {
        var created = await CreateAsync(new { name = "Plain", icon = none });

        Assert.Equal(JsonValueKind.Null, (await ReadBackAsync(Id(created))).GetProperty("icon").ValueKind);
    }

    [Fact]
    public async Task An_icon_is_set_kept_through_a_rename_and_removed_with_an_empty_one()
    {
        var id = Id(await CreateAsync(new { name = "Work" }));

        await PatchAsync(id, new { icon = "💼" });
        Assert.Equal("💼", (await ReadBackAsync(id)).GetProperty("icon").GetString());

        // A rename sends no icon, and must not take it away.
        await PatchAsync(id, new { name = "Office" });
        var renamed = await ReadBackAsync(id);
        Assert.Equal("Office", renamed.GetProperty("name").GetString());
        Assert.Equal("💼", renamed.GetProperty("icon").GetString());

        await PatchAsync(id, new { icon = "" });
        Assert.Equal(JsonValueKind.Null, (await ReadBackAsync(id)).GetProperty("icon").ValueKind);
    }

    /// <summary>
    /// One symbol is one user-perceived character, however many code points it takes: a flag, a
    /// skin tone, a ZWJ family and a subdivision flag are each one icon. A plain letter is too.
    /// </summary>
    [Theory]
    [InlineData("🇦🇹")]
    [InlineData("👍🏽")]
    [InlineData("👨‍👩‍👧‍👦")]
    [InlineData("🏴󠁧󠁢󠁳󠁣󠁴󠁿")]
    [InlineData("❤️")]
    [InlineData("K")]
    public async Task Any_single_symbol_is_an_icon(string icon)
    {
        var created = await CreateAsync(new { name = $"Symbol {Guid.NewGuid():N}", icon });

        Assert.Equal(icon, (await ReadBackAsync(Id(created))).GetProperty("icon").GetString());
    }

    [Theory]
    [InlineData("ab")]
    [InlineData("🛒🛒")]
    [InlineData("‍")]
    [InlineData("\u0007")]
    [InlineData("🛒🛒🛒🛒🛒🛒🛒🛒🛒")]
    public async Task Anything_but_one_symbol_is_refused(string icon)
    {
        var create = await host.Client.PostAsJsonAsync("/api/lists", new { name = "Refused", icon });
        Assert.Equal(HttpStatusCode.BadRequest, create.StatusCode);

        var id = Id(await CreateAsync(new { name = $"Target {Guid.NewGuid():N}", icon = "📌" }));
        var patch = await host.Client.PatchAsJsonAsync($"/api/lists/{id}", new { name = "Renamed", icon });

        // Refused whole: the name in the same request isn't applied either.
        Assert.Equal(HttpStatusCode.BadRequest, patch.StatusCode);
        var kept = await ReadBackAsync(id);
        Assert.Equal("📌", kept.GetProperty("icon").GetString());
        Assert.NotEqual("Renamed", kept.GetProperty("name").GetString());
    }
}
