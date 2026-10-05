using keepITCore.Data;
using Microsoft.AspNetCore.Identity;

namespace keepITCore.Auth;

/// <summary>
/// <c>disable-two-factor &lt;email&gt;</c>: the operator's way to let someone back in who lost the
/// phone with their authenticator app <em>and</em> their recovery codes. There is no admin screen to
/// do it from, and no email route either (a reset link is only one factor), so it is run on the
/// server, by whoever can reach its shell — which is what makes it safe to offer:
/// <code>
/// docker exec -u app keepit dotnet /app/keepITCore.dll disable-two-factor anna@example.com   # single container
/// docker compose exec api dotnet keepITCore.dll disable-two-factor anna@example.com          # Compose
/// dotnet run --project keepIT/keepITCore -- disable-two-factor anna@example.com              # from source
/// </code>
/// It starts the API's configuration and database without serving anything, turns two-factor off,
/// lifts a lockout the user may have run into while trying, and exits. Run it as the user the API
/// runs as (<c>-u app</c> above): SQLite may write a journal next to the database, and one owned by
/// root would leave the API unable to open it.
/// </summary>
public static class DisableTwoFactorCommand
{
    /// <summary>The first command-line argument that runs this instead of the server.</summary>
    public const string Name = "disable-two-factor";

    /// <summary>Turns two-factor authentication off for <paramref name="email"/>'s account.</summary>
    /// <param name="services">The API's services; a scope is created for the work.</param>
    /// <param name="email">The account's email.</param>
    /// <param name="output">Where to report what happened.</param>
    /// <returns>The process exit code: 0 when done (or already off), 1 when there is no such account.</returns>
    public static async Task<int> RunAsync(IServiceProvider services, string email, TextWriter output)
    {
        using var scope = services.CreateScope();
        var userManager = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        var twoFactor = scope.ServiceProvider.GetRequiredService<TwoFactorService>();

        var user = await userManager.FindByEmailAsync(email);
        if (user is null)
        {
            await output.WriteLineAsync($"No account with the email {email} on this server.");
            return 1;
        }

        await userManager.ResetAccessFailedCountAsync(user);
        await userManager.SetLockoutEndDateAsync(user, null);

        if (!user.TwoFactorEnabled)
        {
            await output.WriteLineAsync($"Two-factor authentication is already off for {email}.");
            return 0;
        }

        await twoFactor.DisableAsync(user);
        await output.WriteLineAsync(
            $"Two-factor authentication is off for {email}. They can sign in with their password, " +
            "and set up an authenticator app again in Settings.");
        return 0;
    }
}
