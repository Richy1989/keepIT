using System.Net;
using System.Net.Http.Json;
using keepITCore.Tests.TestHost;
using Microsoft.AspNetCore.Mvc.Testing;

namespace keepITCore.Tests;

/// <summary>
/// When the refresh cookie is marked Secure. Over HTTPS it always is, whatever
/// <c>Auth:RefreshCookie:Secure</c> says: the single container defaults that to false so plain http
/// on a LAN works, and an instance later put behind a TLS proxy must not keep sending its sign-in
/// cookie without the flag because nobody changed the setting.
/// </summary>
public sealed class RefreshCookieTests
{
    private const string Password = "Cookie#Pass1234";

    private static KeepItApiFactory Host(bool secureSetting)
    {
        var api = new KeepItApiFactory();
        api.Settings["Auth:RefreshCookie:Secure"] = secureSetting ? "true" : "false";
        return api;
    }

    private static HttpClient Client(KeepItApiFactory api, string baseAddress) =>
        api.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri(baseAddress),
            HandleCookies = false, // read Set-Cookie as sent, not as a cookie jar digests it
        });

    /// <summary>
    /// Registers a fresh user and returns the refresh cookie's Set-Cookie header.
    /// <paramref name="forwardedProto"/> is what a proxy in front would report as the browser's scheme.
    /// </summary>
    private static async Task<string> RegisterAsync(
        KeepItApiFactory api, string baseAddress = "http://localhost", string? forwardedProto = null)
    {
        using var client = Client(api, baseAddress);
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/auth/register")
        {
            Content = JsonContent.Create(new { email = $"cookie-{Guid.NewGuid():N}@example.com", password = Password }),
        };
        if (forwardedProto is not null) request.Headers.Add("X-Forwarded-Proto", forwardedProto);

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return RefreshCookieIn(response);
    }

    private static string RefreshCookieIn(HttpResponseMessage response) =>
        Assert.Single(response.Headers.GetValues("Set-Cookie"), c => c.StartsWith("keepit_refresh=", StringComparison.Ordinal));

    private static bool IsSecure(string setCookie) =>
        setCookie.Split(';').Any(a => a.Trim().Equals("secure", StringComparison.OrdinalIgnoreCase));

    [Fact]
    public async Task Plain_http_leaves_it_unmarked_so_a_LAN_instance_keeps_working()
    {
        using var api = Host(secureSetting: false);

        Assert.False(IsSecure(await RegisterAsync(api)));
    }

    [Fact]
    public async Task Behind_a_TLS_proxy_it_is_Secure_with_the_setting_off()
    {
        using var api = Host(secureSetting: false);

        Assert.True(IsSecure(await RegisterAsync(api, forwardedProto: "https")));
    }

    [Fact]
    public async Task Over_direct_HTTPS_it_is_Secure_with_the_setting_off()
    {
        using var api = Host(secureSetting: false);

        Assert.True(IsSecure(await RegisterAsync(api, baseAddress: "https://localhost")));
    }

    [Fact]
    public async Task The_setting_makes_it_Secure_on_plain_http_too()
    {
        using var api = Host(secureSetting: true);

        Assert.True(IsSecure(await RegisterAsync(api)));
    }

    [Fact]
    public async Task Signing_out_over_HTTPS_clears_it_with_the_same_flag()
    {
        // A browser won't let a non-Secure Set-Cookie replace a Secure cookie, so a deletion without
        // the flag would leave the session cookie in place.
        using var api = Host(secureSetting: false);
        var cookie = await RegisterAsync(api, forwardedProto: "https");
        using var client = Client(api, "http://localhost");
        using var logout = new HttpRequestMessage(HttpMethod.Post, "/api/auth/logout");
        logout.Headers.Add("Cookie", cookie.Split(';')[0]);
        logout.Headers.Add("X-Forwarded-Proto", "https");

        var response = await client.SendAsync(logout);

        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        var cleared = RefreshCookieIn(response);
        Assert.Contains("expires=Thu, 01 Jan 1970", cleared, StringComparison.OrdinalIgnoreCase);
        Assert.True(IsSecure(cleared));
    }
}
