using System.Security.Cryptography;
using System.Text;
using keepITCore.Auth.Dtos;
using keepITCore.Data;
using Microsoft.AspNetCore.Identity;
using Net.Codecrete.QrCodeGenerator;

namespace keepITCore.Auth;

/// <summary>
/// Two-factor sign-in with an authenticator app (TOTP, RFC 6238): the six-digit code that changes
/// every 30 seconds, as Google Authenticator, Aegis, 2FAS and the like produce it. Built on what
/// Identity already has — the authenticator key and recovery codes live in its user-token table,
/// <c>TwoFactorEnabled</c> on the user — so there is no schema of our own.
/// <para>Two things differ from Identity's defaults. Recovery codes are stored as SHA-256 hashes,
/// not as the text: Identity keeps them readable, and a copy of the database would then be a copy
/// of every way past the second factor. And a code is accepted with spaces or dashes in it, the way
/// authenticator apps display them.</para>
/// <para>Shared by <see cref="TwoFactorController"/> (the user's own settings),
/// <see cref="AuthController.Login"/> and <see cref="DisableTwoFactorCommand"/> (the operator's way
/// to let someone back in who lost both their phone and their recovery codes).</para>
/// </summary>
public class TwoFactorService
{
    /// <summary>The name authenticator apps list the account under.</summary>
    public const string Issuer = "keepIT";

    /// <summary>How many recovery codes a set holds; each signs in once.</summary>
    public const int RecoveryCodeCount = 10;

    // No 0/o, 1/l/i: a recovery code is read off paper and typed in by hand.
    private const string RecoveryAlphabet = "abcdefghjkmnpqrstuvwxyz23456789";

    private readonly UserManager<ApplicationUser> _userManager;
    private readonly IUserTwoFactorRecoveryCodeStore<ApplicationUser> _recoveryCodes;

    /// <summary>Injects Identity's user manager and its store, whose recovery-code half it uses directly.</summary>
    /// <param name="userManager">Identity user manager (authenticator key, TOTP check, the enabled flag).</param>
    /// <param name="store">Identity's user store; the EF store also keeps recovery codes.</param>
    public TwoFactorService(UserManager<ApplicationUser> userManager, IUserStore<ApplicationUser> store)
    {
        _userManager = userManager;
        _recoveryCodes = store as IUserTwoFactorRecoveryCodeStore<ApplicationUser>
            ?? throw new InvalidOperationException("The user store can't hold recovery codes.");
    }

    /// <summary>
    /// Starts setting up an authenticator: a new key, replacing any earlier one, which only takes
    /// effect once <see cref="EnableAsync"/> has seen a code made from it. Never called while 2FA is
    /// on, since the new key would end the authenticator already in use.
    /// </summary>
    /// <param name="user">The user setting it up.</param>
    /// <returns>The key, as text and as a QR code to scan.</returns>
    public async Task<TwoFactorSetupDto> BeginSetupAsync(ApplicationUser user)
    {
        await _userManager.ResetAuthenticatorKeyAsync(user);
        var key = await _userManager.GetAuthenticatorKeyAsync(user)
            ?? throw new InvalidOperationException("Identity did not store an authenticator key.");

        var uri = AuthenticatorUri(user.Email!, key);
        return new TwoFactorSetupDto
        {
            SharedKey = FormatKey(key),
            AuthenticatorUri = uri,
            QrCode = QrRows(uri),
        };
    }

    /// <summary>
    /// Turns 2FA on, once the user has shown their authenticator works by sending a code from it.
    /// </summary>
    /// <param name="user">The user, after <see cref="BeginSetupAsync"/>.</param>
    /// <param name="code">A code from the authenticator just set up.</param>
    /// <returns>The first set of recovery codes, or null when the code is wrong.</returns>
    public async Task<List<string>?> EnableAsync(ApplicationUser user, string code)
    {
        if (!await VerifyAuthenticatorCodeAsync(user, code))
            return null;

        await _userManager.SetTwoFactorEnabledAsync(user, true);
        return await NewRecoveryCodesAsync(user);
    }

    /// <summary>
    /// Turns 2FA off. The key is replaced as well, so the authenticator entry the user still has
    /// can't turn it back on, and the recovery codes are dropped.
    /// </summary>
    /// <param name="user">The user.</param>
    public async Task DisableAsync(ApplicationUser user)
    {
        await _userManager.SetTwoFactorEnabledAsync(user, false);
        await _userManager.ResetAuthenticatorKeyAsync(user);
        await _recoveryCodes.ReplaceCodesAsync(user, [], CancellationToken.None);
        await _userManager.UpdateAsync(user);
    }

    /// <summary>
    /// Whether <paramref name="code"/> is the authenticator's current code, or one of the user's
    /// unused recovery codes — which this then uses up.
    /// </summary>
    /// <param name="user">The user signing in or confirming a change.</param>
    /// <param name="code">What they typed.</param>
    public async Task<bool> VerifySecondFactorAsync(ApplicationUser user, string? code) =>
        await CheckSecondFactorAsync(user, code) != SecondFactor.None;

    /// <summary>
    /// Which second factor <paramref name="code"/> is, if any: the authenticator's current code, or
    /// one of the user's unused recovery codes — which this then uses up, so the caller can tell the
    /// user's devices their count went down.
    /// </summary>
    /// <param name="user">The user signing in or confirming a change.</param>
    /// <param name="code">What they typed.</param>
    public async Task<SecondFactor> CheckSecondFactorAsync(ApplicationUser user, string? code)
    {
        if (string.IsNullOrWhiteSpace(code)) return SecondFactor.None;
        if (await VerifyAuthenticatorCodeAsync(user, code)) return SecondFactor.AppCode;

        var normalized = NormalizeRecoveryCode(code);
        if (normalized.Length == 0) return SecondFactor.None;
        var redeemed = await _userManager.RedeemTwoFactorRecoveryCodeAsync(user, HashRecoveryCode(normalized));
        return redeemed.Succeeded ? SecondFactor.RecoveryCode : SecondFactor.None;
    }

    /// <summary>A new set of recovery codes, replacing the old one. Only their hashes are stored.</summary>
    /// <param name="user">The user.</param>
    /// <returns>The codes, as the user should write them down.</returns>
    public async Task<List<string>> NewRecoveryCodesAsync(ApplicationUser user)
    {
        var codes = Enumerable.Range(0, RecoveryCodeCount)
            .Select(_ =>
            {
                var raw = RandomNumberGenerator.GetString(RecoveryAlphabet, 10);
                return $"{raw[..5]}-{raw[5..]}";
            })
            .ToList();

        await _recoveryCodes.ReplaceCodesAsync(
            user, codes.Select(c => HashRecoveryCode(NormalizeRecoveryCode(c))), CancellationToken.None);
        await _userManager.UpdateAsync(user);
        return codes;
    }

    /// <summary>How many recovery codes the user has left.</summary>
    /// <param name="user">The user.</param>
    public Task<int> RecoveryCodesLeftAsync(ApplicationUser user) => _userManager.CountRecoveryCodesAsync(user);

    /// <summary>Checks a six-digit authenticator code, ignoring the spaces apps put in it.</summary>
    private Task<bool> VerifyAuthenticatorCodeAsync(ApplicationUser user, string code)
    {
        var digits = new string(code.Where(c => !char.IsWhiteSpace(c) && c != '-').ToArray());
        if (digits.Length != 6 || !digits.All(char.IsAsciiDigit))
            return Task.FromResult(false);
        return _userManager.VerifyTwoFactorTokenAsync(
            user, _userManager.Options.Tokens.AuthenticatorTokenProvider, digits);
    }

    /// <summary>
    /// The <c>otpauth://</c> address an authenticator app reads from the QR code (or from a tap on
    /// a phone that has one installed): Google's Key Uri Format, the one every app understands.
    /// </summary>
    /// <param name="email">The account, as the app lists it.</param>
    /// <param name="key">The Base32 key.</param>
    public static string AuthenticatorUri(string email, string key) =>
        $"otpauth://totp/{Uri.EscapeDataString(Issuer)}:{Uri.EscapeDataString(email)}" +
        $"?secret={key}&issuer={Uri.EscapeDataString(Issuer)}&digits=6&period=30";

    /// <summary>The key in groups of four, which is easier to type into an app by hand.</summary>
    /// <param name="key">The Base32 key.</param>
    public static string FormatKey(string key)
    {
        var text = new StringBuilder();
        for (var i = 0; i < key.Length; i += 4)
        {
            if (i > 0) text.Append(' ');
            text.Append(key, i, Math.Min(4, key.Length - i));
        }
        return text.ToString();
    }

    /// <summary>
    /// <paramref name="text"/> as a QR code: one string per row, top to bottom, <c>'1'</c> for a
    /// dark module and <c>'0'</c> for a light one, with the four-module light border scanners need.
    /// The clients draw it, so neither carries a QR library of its own.
    /// </summary>
    /// <param name="text">What the code says.</param>
    public static List<string> QrRows(string text)
    {
        const int border = 4;
        var qr = QrCode.EncodeText(text, QrCode.Ecc.Medium);
        var size = qr.Size + 2 * border;
        var rows = new List<string>(size);
        for (var y = 0; y < size; y++)
        {
            var row = new char[size];
            for (var x = 0; x < size; x++)
            {
                var dark = x >= border && y >= border && x < size - border && y < size - border
                    && qr.GetModule(x - border, y - border);
                row[x] = dark ? '1' : '0';
            }
            rows.Add(new string(row));
        }
        return rows;
    }

    /// <summary>A recovery code as compared: lower case, letters and digits only.</summary>
    /// <param name="code">The code as typed, perhaps with the dash or spaces.</param>
    public static string NormalizeRecoveryCode(string code) =>
        new(code.Where(char.IsAsciiLetterOrDigit).Select(char.ToLowerInvariant).ToArray());

    /// <summary>
    /// What is stored for a recovery code. A plain SHA-256 is enough: each code holds about 50 random bits,
    /// and it only ever replaces the second factor, so the password is still needed with it.
    /// </summary>
    /// <param name="normalized">The code, from <see cref="NormalizeRecoveryCode"/>.</param>
    public static string HashRecoveryCode(string normalized) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(normalized)));
}

/// <summary>What a second factor turned out to be.</summary>
public enum SecondFactor
{
    /// <summary>Neither: wrong, already used, or empty.</summary>
    None,

    /// <summary>The authenticator app's current code.</summary>
    AppCode,

    /// <summary>A recovery code, now used up.</summary>
    RecoveryCode,
}
