using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Tests.TestHost;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace keepITCore.Tests;

/// <summary>
/// Two-factor sign-in with an authenticator app: setting it up, signing in with a code or a
/// recovery code, and the ways it is turned off — by the user with both factors, or by the operator
/// on the server. <see cref="Totp"/> plays the authenticator app. Each test has a host of its own:
/// the sign-in endpoints allow ten requests a minute, and a two-factor sign-in takes two.
/// </summary>
public sealed class TwoFactorTests
{
    private const string Password = "Test-password-1";

    [Fact]
    public async Task Once_set_up_signing_in_takes_the_password_and_a_code()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);

        var setup = await SetUpAsync(client);
        var key = setup.GetProperty("sharedKey").GetString()!.Replace(" ", "");
        var uri = setup.GetProperty("authenticatorUri").GetString()!;
        Assert.StartsWith("otpauth://totp/keepIT:", uri);
        Assert.Contains($"secret={key}", uri);
        // The QR code is square, framed by its light border, and has dark modules in it.
        var rows = setup.GetProperty("qrCode").EnumerateArray().Select(r => r.GetString()!).ToList();
        Assert.All(rows, r => Assert.Equal(rows.Count, r.Length));
        Assert.All(rows, r => Assert.Matches("^[01]+$", r));
        Assert.Equal(new string('0', rows.Count), rows[0]);
        Assert.Contains('1', string.Concat(rows));
        Assert.Equal(TwoFactorService.QrRows(uri), rows);

        // A wrong code doesn't turn it on.
        var wrong = await client.PostAsJsonAsync("/api/auth/two-factor/enable", new { code = OtherCode(Totp.Code(key)) });
        Assert.Equal(HttpStatusCode.BadRequest, wrong.StatusCode);
        Assert.False((await StatusAsync(client)).GetProperty("enabled").GetBoolean());

        // The right one does, with spaces the way apps show it, and hands out the recovery codes.
        var code = Totp.Code(key);
        var enabled = await client.PostAsJsonAsync("/api/auth/two-factor/enable", new { code = $"{code[..3]} {code[3..]}" });
        Assert.Equal(HttpStatusCode.OK, enabled.StatusCode);
        var codes = (await enabled.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("codes").EnumerateArray().ToList();
        Assert.Equal(TwoFactorService.RecoveryCodeCount, codes.Count);
        var status = await StatusAsync(client);
        Assert.True(status.GetProperty("enabled").GetBoolean());
        Assert.Equal(TwoFactorService.RecoveryCodeCount, status.GetProperty("recoveryCodesLeft").GetInt32());

        // The password alone is now the first of two steps …
        using var device = api.CreateClient();
        var first = await LoginAsync(device, email, Password);
        Assert.Equal(HttpStatusCode.Unauthorized, first.StatusCode);
        Assert.True((await first.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("twoFactorRequired").GetBoolean());

        // … a wrong code is refused …
        var refused = await LoginAsync(device, email, Password, OtherCode(Totp.Code(key)));
        Assert.Equal(HttpStatusCode.Unauthorized, refused.StatusCode);
        Assert.True((await refused.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("twoFactorRequired").GetBoolean());

        // … and the right one signs in.
        var signedIn = await LoginAsync(device, email, Password, Totp.Code(key));
        Assert.Equal(HttpStatusCode.OK, signedIn.StatusCode);
        Assert.False(string.IsNullOrEmpty((await signedIn.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("accessToken").GetString()));
    }

    [Fact]
    public async Task A_wrong_password_never_says_the_account_has_two_factor()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        await TurnOnAsync(client);

        using var stranger = api.CreateClient();
        var response = await LoginAsync(stranger, email, "Wrong-password-1", "123456");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.False(body.GetProperty("twoFactorRequired").GetBoolean());
        Assert.Equal("Invalid email or password.", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task A_recovery_code_signs_in_once_and_is_stored_only_as_a_hash()
    {
        var realtime = new CapturingRealtimeNotifier();
        using var api = new KeepItApiFactory { ServiceOverrides = s => s.AddSingleton<keepITCore.SignalR.IRealtimeNotifier>(realtime) };
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        var (_, codes) = await TurnOnAsync(client);

        // The database holds none of the codes as text.
        using (var scope = api.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var stored = string.Concat(await db.UserTokens.Select(t => t.Value).ToListAsync());
            Assert.All(codes, c => Assert.DoesNotContain(TwoFactorService.NormalizeRecoveryCode(c), stored));
            Assert.All(codes, c => Assert.DoesNotContain(c, stored));
        }

        // Typed in capitals and without its dash, it still counts …
        using var device = api.CreateClient();
        var recovery = codes[3].Replace("-", "").ToUpperInvariant();
        var pushesBefore = realtime.Pushes.Count(p => p.Resources.Contains("account"));
        Assert.Equal(HttpStatusCode.OK, (await LoginAsync(device, email, Password, recovery)).StatusCode);
        Assert.Equal(TwoFactorService.RecoveryCodeCount - 1, (await StatusAsync(client)).GetProperty("recoveryCodesLeft").GetInt32());
        // The user's other devices hear that the count went down.
        Assert.Equal(pushesBefore + 1, realtime.Pushes.Count(p => p.Resources.Contains("account")));

        // … once.
        Assert.Equal(HttpStatusCode.Unauthorized, (await LoginAsync(device, email, Password, recovery)).StatusCode);
    }

    [Fact]
    public async Task Turning_it_off_takes_both_factors()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        var (key, _) = await TurnOnAsync(client);

        Assert.Equal(HttpStatusCode.BadRequest,
            (await client.PostAsJsonAsync("/api/auth/two-factor/disable", new { password = "Wrong-password-1", code = Totp.Code(key) })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,
            (await client.PostAsJsonAsync("/api/auth/two-factor/disable", new { password = Password, code = OtherCode(Totp.Code(key)) })).StatusCode);
        Assert.True((await StatusAsync(client)).GetProperty("enabled").GetBoolean());

        Assert.Equal(HttpStatusCode.NoContent,
            (await client.PostAsJsonAsync("/api/auth/two-factor/disable", new { password = Password, code = Totp.Code(key) })).StatusCode);

        var status = await StatusAsync(client);
        Assert.False(status.GetProperty("enabled").GetBoolean());
        Assert.Equal(0, status.GetProperty("recoveryCodesLeft").GetInt32());
        using var device = api.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await LoginAsync(device, email, Password)).StatusCode);
    }

    [Fact]
    public async Task Setting_up_takes_the_password_and_never_replaces_an_authenticator_in_use()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();

        Assert.Equal(HttpStatusCode.BadRequest,
            (await client.PostAsJsonAsync("/api/auth/two-factor/setup", new { password = "Wrong-password-1" })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,
            (await client.PostAsJsonAsync("/api/auth/two-factor/enable", new { code = "123456" })).StatusCode);

        var (key, _) = await TurnOnAsync(client);

        Assert.Equal(HttpStatusCode.Conflict,
            (await client.PostAsJsonAsync("/api/auth/two-factor/setup", new { password = Password })).StatusCode);
        // New recovery codes replace the old set.
        var renewed = await client.PostAsJsonAsync("/api/auth/two-factor/recovery-codes", new { password = Password, code = Totp.Code(key) });
        Assert.Equal(HttpStatusCode.OK, renewed.StatusCode);
        Assert.Equal(TwoFactorService.RecoveryCodeCount,
            (await renewed.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("codes").GetArrayLength());
    }

    [Fact]
    public async Task Wrong_codes_lock_the_account_like_wrong_passwords()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        var (key, _) = await TurnOnAsync(client);

        using var attacker = api.CreateClient();
        for (var i = 0; i < 5; i++)
            await LoginAsync(attacker, email, Password, OtherCode(Totp.Code(key)));

        // Locked: even the right code is refused now, without saying why.
        var locked = await LoginAsync(attacker, email, Password, Totp.Code(key));
        Assert.Equal(HttpStatusCode.Unauthorized, locked.StatusCode);
        Assert.False((await locked.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("twoFactorRequired").GetBoolean());
    }

    [Fact]
    public async Task A_password_reset_leaves_two_factor_on()
    {
        var mail = new CapturingEmailSender(deliversToRecipient: true);
        using var api = new KeepItApiFactory
        {
            ServiceOverrides = s => s.AddSingleton<keepITCore.Infrastructure.Email.IEmailSender>(mail),
        };
        api.Settings["App:PublicBaseUrl"] = "https://notes.example.com";
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        await TurnOnAsync(client);

        using var anonymous = api.CreateClient();
        (await anonymous.PostAsJsonAsync("/api/auth/forgot-password", new { email })).EnsureSuccessStatusCode();
        var link = new Uri(mail.Sent.Single().Body.Split('\n').Single(l => l.StartsWith("https://")));
        var query = System.Web.HttpUtility.ParseQueryString(link.Query);
        (await anonymous.PostAsJsonAsync("/api/auth/reset-password",
            new { email, token = query["token"], newPassword = "New-password-2" })).EnsureSuccessStatusCode();

        // The mailbox was enough for a new password, not for the account.
        var response = await LoginAsync(anonymous, email, "New-password-2");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.True((await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("twoFactorRequired").GetBoolean());
    }

    [Fact]
    public async Task The_operator_can_turn_it_off_from_the_server()
    {
        using var api = new KeepItApiFactory();
        var email = $"user-{Guid.NewGuid():N}@example.com";
        using var client = await api.CreateSignedInClientAsync(email);
        await TurnOnAsync(client);
        var host = api.Services;

        // Locked out after losing the phone, too.
        using (var scope = host.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
            await users.SetLockoutEndDateAsync((await users.FindByEmailAsync(email))!, DateTimeOffset.UtcNow.AddMinutes(15));
        }

        var output = new StringWriter();
        Assert.Equal(1, await DisableTwoFactorCommand.RunAsync(host, "nobody@example.com", output));
        Assert.Equal(0, await DisableTwoFactorCommand.RunAsync(host, email, output));
        Assert.Contains($"Two-factor authentication is off for {email}", output.ToString());

        using var device = api.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await LoginAsync(device, email, Password)).StatusCode);
    }

    // ---- helpers ----

    private static async Task<JsonElement> SetUpAsync(HttpClient client)
    {
        var response = await client.PostAsJsonAsync("/api/auth/two-factor/setup", new { password = Password });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    /// <summary>Sets up an authenticator and turns two-factor on: its key, and the recovery codes.</summary>
    private static async Task<(string Key, List<string> Codes)> TurnOnAsync(HttpClient client)
    {
        var key = (await SetUpAsync(client)).GetProperty("sharedKey").GetString()!.Replace(" ", "");
        var response = await client.PostAsJsonAsync("/api/auth/two-factor/enable", new { code = Totp.Code(key) });
        response.EnsureSuccessStatusCode();
        var codes = (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("codes")
            .EnumerateArray().Select(c => c.GetString()!).ToList();
        return (key, codes);
    }

    private static async Task<JsonElement> StatusAsync(HttpClient client) =>
        await client.GetFromJsonAsync<JsonElement>("/api/auth/two-factor");

    private static Task<HttpResponseMessage> LoginAsync(HttpClient client, string email, string password, string? code = null) =>
        client.PostAsJsonAsync("/api/auth/login", new { email, password, twoFactorCode = code });

    /// <summary>A six-digit code that isn't <paramref name="code"/>.</summary>
    private static string OtherCode(string code) => ((int.Parse(code) + 500_000) % 1_000_000).ToString("D6");

    /// <summary>The authenticator app: RFC 6238 codes, 30-second steps, six digits, HMAC-SHA1.</summary>
    private static class Totp
    {
        public static string Code(string base32Key)
        {
            var step = DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 30;
            var counter = BitConverter.GetBytes(step);
            if (BitConverter.IsLittleEndian) Array.Reverse(counter);
            var hash = HMACSHA1.HashData(Base32(base32Key), counter);
            var offset = hash[^1] & 0x0f;
            var value = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
            return (value % 1_000_000).ToString("D6");
        }

        private static byte[] Base32(string text)
        {
            const string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            var bytes = new List<byte>();
            int buffer = 0, bits = 0;
            foreach (var c in text.TrimEnd('=').ToUpperInvariant())
            {
                buffer = (buffer << 5) | alphabet.IndexOf(c);
                bits += 5;
                if (bits >= 8)
                {
                    bytes.Add((byte)(buffer >> (bits - 8)));
                    bits -= 8;
                }
            }
            return bytes.ToArray();
        }
    }
}
