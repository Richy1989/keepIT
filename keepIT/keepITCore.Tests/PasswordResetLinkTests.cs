using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Infrastructure.Email;
using keepITCore.Tests.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// Where a password-reset link points, and what the Settings page is told about it. Forgot-password
/// is anonymous and the request's <c>Origin</c> and <c>Host</c> are the requester's to choose, so an
/// emailed link built from them would let anyone send a victim a genuine reset email aimed at their
/// own site, and collect the token when it is clicked (password-reset poisoning).
/// </summary>
public sealed class PasswordResetLinkTests
{
    private const string Attacker = "https://attacker.example";

    /// <summary>A host whose outgoing mail is captured. <paramref name="viaSmtp"/> makes it behave
    /// like real delivery to the user's inbox; false like the log-only fallback.</summary>
    private static (KeepItApiFactory Api, CapturingEmailSender Mail) Host(bool viaSmtp, string? publicBaseUrl = null)
    {
        var mail = new CapturingEmailSender(viaSmtp);
        var api = new KeepItApiFactory { Services = services => services.AddSingleton<IEmailSender>(mail) };
        if (publicBaseUrl is not null) api.Settings["App:PublicBaseUrl"] = publicBaseUrl;
        return (api, mail);
    }

    /// <summary>Asks for a reset link the way an attacker would: with forged Origin and Host headers.</summary>
    private static async Task<HttpResponseMessage> ForgotPasswordAsync(KeepItApiFactory api, string email, string origin)
    {
        using var client = api.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/auth/forgot-password")
        {
            Content = JsonContent.Create(new { email }),
        };
        request.Headers.Add("Origin", origin);
        request.Headers.Host = new Uri(origin).Authority;
        return await client.SendAsync(request);
    }

    private static string LinkIn(CapturingEmailSender.Message message) =>
        message.Body.Split('\n').Single(line => line.Contains("/reset-password?", StringComparison.Ordinal)).Trim();

    [Fact]
    public async Task An_emailed_link_points_at_the_configured_address_whatever_the_request_claims()
    {
        var (api, mail) = Host(viaSmtp: true, publicBaseUrl: "https://notes.example.com/");
        using var _ = api;
        var email = await api.RegisterUserAsync();

        var response = await ForgotPasswordAsync(api, email, Attacker);

        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        var link = LinkIn(Assert.Single(mail.Sent));
        Assert.StartsWith("https://notes.example.com/reset-password?", link);
        Assert.DoesNotContain("attacker", link);
    }

    [Fact]
    public async Task Without_a_configured_address_no_reset_email_is_sent()
    {
        var (api, mail) = Host(viaSmtp: true);
        using var _ = api;
        var email = await api.RegisterUserAsync();

        var response = await ForgotPasswordAsync(api, email, Attacker);

        // The usual answer, so the missing setting can't be used to tell registered emails apart.
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Empty(mail.Sent);
    }

    [Fact]
    public async Task A_link_only_written_to_the_log_may_use_the_requests_address()
    {
        // No SMTP: the operator reads the link in the server log, and local dev relies on it.
        var (api, mail) = Host(viaSmtp: false);
        using var _ = api;
        var email = await api.RegisterUserAsync();

        await ForgotPasswordAsync(api, email, "http://localhost:5173");

        Assert.StartsWith("http://localhost:5173/reset-password?", LinkIn(Assert.Single(mail.Sent)));
    }

    /// <summary>What <c>GET api/settings/email-status</c> reports to a signed-in user.</summary>
    private static async Task<JsonElement> EmailStatusAsync(KeepItApiFactory api)
    {
        using var client = await api.CreateSignedInClientAsync();
        var response = await client.GetAsync("/api/settings/email-status");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    [Fact]
    public async Task Settings_are_told_when_reset_emails_are_switched_off()
    {
        var (api, _) = Host(viaSmtp: true);
        using var _ = api;

        var status = await EmailStatusAsync(api);

        Assert.True(status.GetProperty("smtpConfigured").GetBoolean());
        Assert.True(status.GetProperty("resetEmailsDisabled").GetBoolean());
        Assert.Equal(JsonValueKind.Null, status.GetProperty("publicBaseUrl").ValueKind);
    }

    [Fact]
    public async Task Settings_are_told_where_reset_links_point_once_configured()
    {
        var (api, _) = Host(viaSmtp: true, publicBaseUrl: "https://notes.example.com/");
        using var _ = api;

        var status = await EmailStatusAsync(api);

        Assert.False(status.GetProperty("resetEmailsDisabled").GetBoolean());
        Assert.Equal("https://notes.example.com", status.GetProperty("publicBaseUrl").GetString());
    }

    [Fact]
    public async Task Log_only_delivery_is_not_reported_as_switched_off()
    {
        var (api, _) = Host(viaSmtp: false);
        using var _ = api;

        var status = await EmailStatusAsync(api);

        Assert.False(status.GetProperty("smtpConfigured").GetBoolean());
        Assert.False(status.GetProperty("resetEmailsDisabled").GetBoolean());
    }

    [Fact]
    public async Task Settings_are_told_when_SMTP_may_send_unencrypted()
    {
        var (api, _) = Host(viaSmtp: true, publicBaseUrl: "https://notes.example.com");
        api.Settings["Email:AllowUnencrypted"] = "true";
        using var _ = api;

        var status = await EmailStatusAsync(api);

        Assert.True(status.GetProperty("unencryptedAllowed").GetBoolean());
    }

    [Fact]
    public async Task The_email_status_needs_a_signed_in_user()
    {
        using var api = new KeepItApiFactory();
        using var client = api.CreateClient();

        var response = await client.GetAsync("/api/settings/email-status");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData("notes.example.com")]
    [InlineData("ftp://notes.example.com")]
    [InlineData("https://notes.example.com/?next=x")]
    public void A_malformed_public_address_stops_the_api_from_starting(string value)
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:PublicBaseUrl"] = value;

        var error = Assert.ThrowsAny<Exception>(() => api.CreateClient());
        Assert.Contains("App:PublicBaseUrl", error.ToString());
    }
}
