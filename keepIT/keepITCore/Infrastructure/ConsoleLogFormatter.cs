using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Serilog.Events;
using Serilog.Formatting;
using Serilog.Parsing;

namespace keepITCore.Infrastructure;

/// <summary>
/// The server's log line: short, aligned and coloured, so the container log reads as an overview of
/// what is happening — in a terminal, in <c>docker logs</c>, and in Unraid's log view, which is a
/// web terminal (ttyd / xterm.js) and shows ANSI colour like any other.
/// <code>
/// 16:17:12 INF  keepIT 0.9.0 starting · SQLite · data in /data
/// 16:17:12 INF  POST   /api/notes                                201   260 ms
/// 16:17:13 INF  GET    /api/notes/7bfc61d0/media/05d628a6        200    29 ms
/// 16:17:13 INF  POST   /api/auth/login                           401    95 ms
/// </code>
/// A request line is the request-logging middleware's event, laid out in columns: the method
/// coloured by verb, the path with every GUID cut to its first 8 characters, the status coloured by
/// class (2xx green, 3xx cyan, 4xx amber, 5xx red) and the time it took. Every other event is its
/// message, with the values in it highlighted.
/// <para>
/// The colour is written here rather than by a Serilog console theme: the sink applies a theme only
/// when its output is a terminal, and a container's never is, so the theme this replaced never
/// reached a container log at all. <c>NO_COLOR</c> (https://no-color.org) turns it off and keeps
/// the layout.
/// </para>
/// <para>
/// Nothing that came from outside is written raw. A request path or a logged value can carry
/// escape sequences, and in a terminal those would recolour, move or overwrite the log; every
/// control character is shown as <c>\xNN</c> instead, so the only escapes in the output are these.
/// </para>
/// </summary>
public sealed partial class ConsoleLogFormatter : ITextFormatter
{
    /// <summary>Width of "HH:mm:ss LVL  ", where a message's continuation lines start.</summary>
    private const int PrefixWidth = 14;

    /// <summary>Path column width in a request line; a longer path pushes the status right.</summary>
    private const int PathWidth = 40;

    private const string Esc = "\u001b[";
    private const string Reset = Esc + "0m";

    private static string Fg(int color) => $"{Esc}38;5;{color}m";

    // xterm 256-colour numbers.
    private static readonly string Dim = Fg(244);
    private static readonly string Text = Fg(253);
    private static readonly string Value = Fg(81);
    private static readonly string WarningText = Fg(222);
    private static readonly string ErrorText = Fg(210);
    private static readonly string ExceptionText = Fg(203);
    private static readonly string Green = Fg(42);
    private static readonly string Cyan = Fg(81);
    private static readonly string Amber = Fg(220);
    private static readonly string RedBold = $"{Esc}1;38;5;196m";

    private readonly bool _color;

    /// <param name="color">Write ANSI colour; see <see cref="ColorFromEnvironment"/>.</param>
    public ConsoleLogFormatter(bool color)
    {
        _color = color;
    }

    /// <summary>Colour unless <c>NO_COLOR</c> is set to anything but an empty string.</summary>
    public static bool ColorFromEnvironment() =>
        string.IsNullOrEmpty(Environment.GetEnvironmentVariable("NO_COLOR"));

    /// <inheritdoc />
    public void Format(LogEvent logEvent, TextWriter output)
    {
        var line = new StringBuilder(160);
        Span(line, logEvent.Timestamp.ToString("HH:mm:ss", CultureInfo.InvariantCulture), Dim);
        line.Append(' ');
        var (label, labelStyle, textStyle) = LevelStyle(logEvent.Level);
        Span(line, label, labelStyle);
        line.Append("  ");

        if (TryReadRequest(logEvent, out var method, out var path, out var status, out var elapsedMs))
            AppendRequest(line, method, path, status, elapsedMs);
        else
            AppendMessage(line, logEvent, textStyle);
        line.Append(Environment.NewLine);

        // One span per line: a viewer that resets colour at each line break (docker compose's
        // prefixer does) would otherwise show only the first line of a stack trace in red.
        if (logEvent.Exception is { } exception)
        {
            foreach (var exceptionLine in exception.ToString().Split('\n'))
            {
                line.Append(' ', PrefixWidth);
                Span(line, exceptionLine.TrimEnd('\r'), ExceptionText);
                line.Append(Environment.NewLine);
            }
        }

        output.Write(line.ToString());
    }

    private static (string Label, string LabelStyle, string TextStyle) LevelStyle(LogEventLevel level) => level switch
    {
        LogEventLevel.Verbose => ("VRB", Dim, Dim),
        LogEventLevel.Debug => ("DBG", Fg(39), Text),
        LogEventLevel.Information => ("INF", Green, Text),
        LogEventLevel.Warning => ("WRN", $"{Esc}1;38;5;220m", WarningText),
        LogEventLevel.Error => ("ERR", $"{Esc}1;38;5;231;48;5;160m", ErrorText),
        _ => ("FTL", $"{Esc}1;38;5;231;48;5;196m", ErrorText),
    };

    /// <summary>The request-logging middleware's completion event, recognised by its properties.</summary>
    private static bool TryReadRequest(
        LogEvent logEvent, out string method, out string path, out int status, out double elapsedMs)
    {
        method = path = "";
        status = 0;
        elapsedMs = 0;
        var p = logEvent.Properties;
        if (!p.TryGetValue("RequestMethod", out var m) || m is not ScalarValue { Value: string methodValue } ||
            !p.TryGetValue("RequestPath", out var r) || r is not ScalarValue { Value: string pathValue } ||
            !p.TryGetValue("StatusCode", out var s) || s is not ScalarValue { Value: IConvertible statusValue } ||
            !p.TryGetValue("Elapsed", out var e) || e is not ScalarValue { Value: IConvertible elapsedValue })
        {
            return false;
        }

        method = methodValue;
        path = pathValue;
        status = statusValue.ToInt32(CultureInfo.InvariantCulture);
        elapsedMs = elapsedValue.ToDouble(CultureInfo.InvariantCulture);
        return true;
    }

    private void AppendRequest(StringBuilder line, string method, string path, int status, double elapsedMs)
    {
        Span(line, Safe(method).PadRight(6), method switch
        {
            "GET" or "HEAD" => Fg(75),
            "POST" => Fg(114),
            "PUT" or "PATCH" => Fg(179),
            "DELETE" => Fg(203),
            _ => Text,
        });
        line.Append(' ');
        Span(line, Safe(GuidPattern().Replace(path, match => match.Value[..8])).PadRight(PathWidth), Text);
        line.Append(' ');
        Span(line, status.ToString(CultureInfo.InvariantCulture), status switch
        {
            >= 500 => RedBold,
            >= 400 => Amber,
            >= 300 => Cyan,
            _ => Green,
        });
        line.Append("  ");
        Span(line, Duration(elapsedMs).PadLeft(6), elapsedMs >= 1000 ? Amber : Dim);
    }

    /// <summary>"29 ms", "1.4 s", "12 min" — a SignalR connection is logged when it closes.</summary>
    private static string Duration(double ms) => ms switch
    {
        < 1000 => string.Create(CultureInfo.InvariantCulture, $"{ms:0} ms"),
        < 60_000 => string.Create(CultureInfo.InvariantCulture, $"{ms / 1000:0.0} s"),
        _ => string.Create(CultureInfo.InvariantCulture, $"{ms / 60_000:0} min"),
    };

    private void AppendMessage(StringBuilder line, LogEvent logEvent, string textStyle)
    {
        foreach (var token in logEvent.MessageTemplate.Tokens)
        {
            switch (token)
            {
                case TextToken text:
                    AppendText(line, text.Text, textStyle);
                    break;
                case PropertyToken property when logEvent.Properties.TryGetValue(property.PropertyName, out var value):
                    AppendText(line, Render(value, property.Format), Value);
                    break;
                default:
                    AppendText(line, token.ToString() ?? "", textStyle);
                    break;
            }
        }
    }

    /// <summary>A value as text: strings bare rather than quoted, everything else as Serilog renders it.</summary>
    private static string Render(LogEventPropertyValue value, string? format)
    {
        if (value is ScalarValue { Value: string text }) return text;
        var writer = new StringWriter(CultureInfo.InvariantCulture);
        value.Render(writer, format, CultureInfo.InvariantCulture);
        return writer.ToString();
    }

    /// <summary>Text that may span lines: each continuation starts under the message column.</summary>
    private void AppendText(StringBuilder line, string text, string style)
    {
        var parts = text.Split('\n');
        for (var i = 0; i < parts.Length; i++)
        {
            if (i > 0)
            {
                line.Append(Environment.NewLine);
                line.Append(' ', PrefixWidth);
            }
            var part = parts[i].TrimEnd('\r');
            if (part.Length > 0) Span(line, Safe(part), style);
        }
    }

    /// <summary>Writes already-safe text, coloured when colour is on.</summary>
    private void Span(StringBuilder line, string safeText, string style)
    {
        if (!_color)
        {
            line.Append(safeText);
            return;
        }
        line.Append(style).Append(safeText).Append(Reset);
    }

    /// <summary>
    /// <paramref name="text"/> with every C0 and C1 control character, and DEL, shown as <c>\xNN</c>:
    /// ESC alone is enough for a terminal to take instructions from a log line, and C1's CSI
    /// (U+009B) is ESC [ in a single character.
    /// </summary>
    internal static string Safe(string text)
    {
        if (!text.Any(IsControl)) return text;
        var safe = new StringBuilder(text.Length + 16);
        foreach (var c in text)
        {
            if (IsControl(c)) safe.Append("\\x").Append(((int)c).ToString("x2", CultureInfo.InvariantCulture));
            else safe.Append(c);
        }
        return safe.ToString();
    }

    private static bool IsControl(char c) => c < 0x20 || c is >= '\u007f' and <= '\u009f';

    [GeneratedRegex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")]
    private static partial Regex GuidPattern();
}
