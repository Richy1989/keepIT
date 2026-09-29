using System.Net;
using System.Net.Http.Json;
using keepITCore.Data;
using keepITCore.Tests.TestHost;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// What happens when an already-rotated refresh token comes back.
///
/// <para>Replaying one can mean a stolen cookie, and the answer to that is to end every session the
/// user has. It can also mean a client that never received the rotation response — a dropped
/// connection, a process killed at the wrong moment — or one retrying a queued request after the
/// user signed out. Those are ordinary, and ending every session on every device because of one is
/// a logout nobody can explain.</para>
///
/// <para>The tokens themselves say which happened: a replacement that has never been used is in
/// nobody's hands, and a logout leaves no replacement at all. Only a replacement already in
/// circulation means a second holder exists.</para>
/// </summary>
public sealed class RefreshReplayTests
{
    private const string Password = "Replay#Pass1234";

    private static HttpClient Client(KeepItApiFactory api) =>
        api.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("http://localhost"),
            HandleCookies = false, // cookies are passed by hand: these tests are about which one is sent
        });

    /// <summary>The <c>name=value</c> pair from a response's refresh cookie, ready to send back.</summary>
    private static string RefreshCookie(HttpResponseMessage response)
    {
        var setCookie = Assert.Single(
            response.Headers.GetValues("Set-Cookie"),
            c => c.StartsWith("keepit_refresh=", StringComparison.Ordinal));
        return setCookie.Split(';')[0];
    }

    private static async Task<(string Email, string Cookie)> RegisterAsync(KeepItApiFactory api)
    {
        var email = $"replay-{Guid.NewGuid():N}@example.com";
        using var client = Client(api);
        var response = await client.PostAsJsonAsync("/api/auth/register", new { email, password = Password });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return (email, RefreshCookie(response));
    }

    /// <summary>Signs the same user in again — a second device, with a refresh token of its own.</summary>
    private static async Task<string> SignInAgainAsync(KeepItApiFactory api, string email)
    {
        using var client = Client(api);
        var response = await client.PostAsJsonAsync("/api/auth/login", new { email, password = Password });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return RefreshCookie(response);
    }

    private static async Task<HttpResponseMessage> PostAsync(KeepItApiFactory api, string path, string cookie)
    {
        using var client = Client(api);
        using var request = new HttpRequestMessage(HttpMethod.Post, path);
        request.Headers.Add("Cookie", cookie);
        return await client.SendAsync(request);
    }

    private static Task<HttpResponseMessage> RefreshAsync(KeepItApiFactory api, string cookie) =>
        PostAsync(api, "/api/auth/refresh", cookie);

    /// <summary>
    /// Pushes every rotation that has happened so far out of the controller's 60-second grace
    /// window, which is otherwise only reachable by waiting a minute inside a unit test.
    /// </summary>
    private static async Task AgeRotationsAsync(KeepItApiFactory api)
    {
        using var scope = api.Server.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var longAgo = DateTime.UtcNow.AddMinutes(-5);
        await db.RefreshTokens
            .Where(rt => rt.RevokedAtUtc != null)
            .ExecuteUpdateAsync(s => s.SetProperty(rt => rt.RevokedAtUtc, longAgo));
    }

    /// <summary>
    /// The lost-response case: the server rotated, the client never got the new token, so it comes
    /// back with the old one. The replacement was never used, so nobody else holds anything — the
    /// client gets a working session and the user's other devices are left alone.
    /// </summary>
    [Fact]
    public async Task Replay_when_the_replacement_was_never_used_is_served_not_punished()
    {
        using var api = new KeepItApiFactory();
        var (email, first) = await RegisterAsync(api);
        var otherDevice = await SignInAgainAsync(api, email);

        // Rotate once, then throw the response away as a dropped connection would.
        using (var rotated = await RefreshAsync(api, first))
            Assert.Equal(HttpStatusCode.OK, rotated.StatusCode);
        await AgeRotationsAsync(api);

        using var replay = await RefreshAsync(api, first);
        Assert.Equal(HttpStatusCode.OK, replay.StatusCode);

        using var elsewhere = await RefreshAsync(api, otherDevice);
        Assert.Equal(HttpStatusCode.OK, elsewhere.StatusCode);
    }

    /// <summary>
    /// Serving a lost rotation must not leave the old token usable for good. Its first replacement
    /// is never used — nobody holds it — so judged by that one alone, the old token would pass as a
    /// lost response on every replay, and a copy of it would mint sessions for as long as it lived
    /// without ever being noticed. Once the client is using the token it was served instead, the
    /// old one coming back is a copy like any other.
    /// </summary>
    [Fact]
    public async Task Replay_after_a_lost_rotation_was_served_ends_every_session_once_the_new_token_is_in_use()
    {
        using var api = new KeepItApiFactory();
        var (email, first) = await RegisterAsync(api);
        var otherDevice = await SignInAgainAsync(api, email);

        using (var lost = await RefreshAsync(api, first))
            Assert.Equal(HttpStatusCode.OK, lost.StatusCode);
        await AgeRotationsAsync(api);

        string served;
        using (var recovered = await RefreshAsync(api, first))
        {
            Assert.Equal(HttpStatusCode.OK, recovered.StatusCode);
            served = RefreshCookie(recovered);
        }

        // The client carries on with the token it was served, so that one is now in circulation.
        using (var used = await RefreshAsync(api, served))
            Assert.Equal(HttpStatusCode.OK, used.StatusCode);
        await AgeRotationsAsync(api);

        using var replay = await RefreshAsync(api, first);
        Assert.Equal(HttpStatusCode.Unauthorized, replay.StatusCode);

        using var elsewhere = await RefreshAsync(api, otherDevice);
        Assert.Equal(HttpStatusCode.Unauthorized, elsewhere.StatusCode);
    }

    /// <summary>
    /// The other side of the test above: a client can lose the response to its recovery too, and
    /// that is still no reason to end anyone's session. Nothing it was served is in use, so it is
    /// served again.
    /// </summary>
    [Fact]
    public async Task Replay_after_losing_the_recovery_response_too_is_still_served()
    {
        using var api = new KeepItApiFactory();
        var (email, first) = await RegisterAsync(api);
        var otherDevice = await SignInAgainAsync(api, email);

        using (var lost = await RefreshAsync(api, first))
            Assert.Equal(HttpStatusCode.OK, lost.StatusCode);
        await AgeRotationsAsync(api);
        using (var lostAgain = await RefreshAsync(api, first))
            Assert.Equal(HttpStatusCode.OK, lostAgain.StatusCode);
        await AgeRotationsAsync(api);

        using var replay = await RefreshAsync(api, first);
        Assert.Equal(HttpStatusCode.OK, replay.StatusCode);

        using var elsewhere = await RefreshAsync(api, otherDevice);
        Assert.Equal(HttpStatusCode.OK, elsewhere.StatusCode);
    }

    /// <summary>
    /// The theft case, and the reason any of this exists: the replacement is already in use, so
    /// whoever is presenting the token it replaced is holding a copy. Every session ends, including
    /// the real user's — which is the point, since we cannot tell which caller is which.
    /// </summary>
    [Fact]
    public async Task Replay_when_the_replacement_is_in_use_ends_every_session()
    {
        using var api = new KeepItApiFactory();
        var (email, first) = await RegisterAsync(api);
        var otherDevice = await SignInAgainAsync(api, email);

        string second;
        using (var rotated = await RefreshAsync(api, first))
        {
            Assert.Equal(HttpStatusCode.OK, rotated.StatusCode);
            second = RefreshCookie(rotated);
        }

        // The legitimate client uses its new token, so the replacement is now in circulation.
        using (var used = await RefreshAsync(api, second))
            Assert.Equal(HttpStatusCode.OK, used.StatusCode);
        await AgeRotationsAsync(api);

        using var replay = await RefreshAsync(api, first);
        Assert.Equal(HttpStatusCode.Unauthorized, replay.StatusCode);

        using var elsewhere = await RefreshAsync(api, otherDevice);
        Assert.Equal(HttpStatusCode.Unauthorized, elsewhere.StatusCode);
    }

    /// <summary>
    /// Signing out revokes the token without recording a replacement. A client that retries a
    /// queued request with the dead cookie afterwards is refused — and that is all. Ending the
    /// user's other devices because one of them finished signing out is not a security measure.
    /// </summary>
    [Fact]
    public async Task Replay_after_signing_out_refuses_that_session_only()
    {
        using var api = new KeepItApiFactory();
        var (email, signedOut) = await RegisterAsync(api);
        var otherDevice = await SignInAgainAsync(api, email);

        using (var logout = await PostAsync(api, "/api/auth/logout", signedOut))
            Assert.Equal(HttpStatusCode.NoContent, logout.StatusCode);
        await AgeRotationsAsync(api);

        using var replay = await RefreshAsync(api, signedOut);
        Assert.Equal(HttpStatusCode.Unauthorized, replay.StatusCode);

        using var elsewhere = await RefreshAsync(api, otherDevice);
        Assert.Equal(HttpStatusCode.OK, elsewhere.StatusCode);
    }
}
