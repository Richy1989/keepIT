using System.Net;
using System.Net.Http.Headers;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Who the global rate limit counts. A signed-in caller is counted per account, everyone else per
/// client address. Before, everything was counted per address, and ordinary use hit the limit:
/// every device in one household shared a budget, and behind a reverse proxy with
/// <c>App:ForwardedProxyHops</c> set too low every user of the server did.
/// <para>
/// Every request from the test server comes from the same address, which is the case being tested:
/// several accounts behind one IP. The budgets are set low through <c>App:RateLimit:*</c> so a test
/// reaches them in a handful of requests. Signing in costs two requests without a session
/// (register, login), which count against the address, so the per-IP budgets leave room for them.
/// </para>
/// </summary>
public sealed class RateLimitTests
{
    /// <summary>A signed-in caller isn't held to the address's budget, and doesn't use it up.</summary>
    [Fact]
    public async Task Signed_in_requests_are_not_held_to_the_per_ip_budget()
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:RateLimit:PerIpPerMinute"] = "5";
        using var client = await api.CreateSignedInClientAsync(); // two of the address's five

        for (var i = 0; i < 20; i++)
            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/notes")).StatusCode);

        // The address still has what signing in left of it.
        using var anonymous = api.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await anonymous.GetAsync("/api/meta")).StatusCode);
    }

    /// <summary>Two accounts behind one address each have a budget of their own.</summary>
    [Fact]
    public async Task Each_user_has_their_own_budget()
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:RateLimit:PerUserPerMinute"] = "5";
        using var first = await api.CreateSignedInClientAsync();
        using var second = await api.CreateSignedInClientAsync();

        for (var i = 0; i < 5; i++)
            Assert.Equal(HttpStatusCode.OK, (await first.GetAsync("/api/notes")).StatusCode);

        var refused = await first.GetAsync("/api/notes");
        Assert.Equal(HttpStatusCode.TooManyRequests, refused.StatusCode);
        Assert.NotNull(refused.Headers.RetryAfter);

        Assert.Equal(HttpStatusCode.OK, (await second.GetAsync("/api/notes")).StatusCode);
    }

    /// <summary>Without a session the address's budget still applies.</summary>
    [Fact]
    public async Task Requests_without_a_session_keep_the_per_ip_budget()
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:RateLimit:PerIpPerMinute"] = "5";
        using var anonymous = api.CreateClient();

        for (var i = 0; i < 5; i++)
            Assert.Equal(HttpStatusCode.OK, (await anonymous.GetAsync("/api/meta")).StatusCode);

        var refused = await anonymous.GetAsync("/api/meta");
        Assert.Equal(HttpStatusCode.TooManyRequests, refused.StatusCode);
        Assert.NotNull(refused.Headers.RetryAfter);
    }

    /// <summary>
    /// A token that doesn't verify buys no budget of its own: the request is counted against its
    /// address like any other without a session, so forging tokens can't dodge the limit.
    /// </summary>
    [Fact]
    public async Task A_forged_token_counts_against_the_address()
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:RateLimit:PerIpPerMinute"] = "3";
        using var forger = api.CreateClient();
        forger.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "not-a-token");

        for (var i = 0; i < 3; i++)
            Assert.Equal(HttpStatusCode.Unauthorized, (await forger.GetAsync("/api/notes")).StatusCode);

        Assert.Equal(HttpStatusCode.TooManyRequests, (await forger.GetAsync("/api/notes")).StatusCode);
    }
}
