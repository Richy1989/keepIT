using System.Buffers;
using System.Globalization;
using System.Text;
using keepITCore.Data;

namespace keepITCore.Lists;

/// <summary>
/// What a list's icon may be: one emoji, or any other single symbol. "Single" is one user-perceived
/// character — a grapheme cluster — so a flag, a skin-toned emoji or a ZWJ sequence such as a family
/// is one icon, while "ab" is two and is refused. Whitespace, control and format characters are
/// refused too: they would draw nothing.
/// <para>
/// The clients offer a curated emoji grid, but the server holds icons only to this shape, not to the
/// grid, so the grid can grow (or differ between clients) without an API change.
/// </para>
/// </summary>
public static class ListIcon
{
    /// <summary>The rule, as a validation message.</summary>
    public const string Rule = "An icon is a single emoji or symbol.";

    /// <summary>True when <paramref name="icon"/> is exactly one drawable symbol.</summary>
    /// <param name="icon">The icon, already trimmed.</param>
    public static bool IsValid(string icon)
    {
        if (icon.Length is 0 or > NoteLimits.ListIcon) return false;
        if (new StringInfo(icon).LengthInTextElements != 1) return false;

        // A lone surrogate is a cluster of its own, and no symbol.
        if (Rune.DecodeFromUtf16(icon, out var first, out _) != OperationStatus.Done) return false;
        return !Rune.IsWhiteSpace(first)
            && !Rune.IsControl(first)
            && Rune.GetUnicodeCategory(first) != UnicodeCategory.Format;
    }
}
