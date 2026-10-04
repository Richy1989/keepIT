using keepITCore.Infrastructure;
using Serilog.Events;
using Serilog.Parsing;

namespace keepITCore.Tests;

/// <summary>
/// The console log line. It goes to a terminal more often than not — Unraid's log view is one —
/// so what matters most is that nothing from outside reaches it raw: a request path or a logged
/// value carrying escape sequences would otherwise recolour, move or overwrite the log. After that,
/// that a request reads as one short line, and that NO_COLOR leaves plain text.
/// </summary>
public class ConsoleLogFormatterTests
{
    private const char Esc = '\u001b';

    // Ordinal throughout: a culture-aware comparison ignores control characters, ESC among them,
    // so it would find "[2J" in the harmless "\x1b[2J" and call it the escape sequence.
    private const StringComparison Exact = StringComparison.Ordinal;

    private static string Format(LogEvent logEvent, bool color = false)
    {
        var output = new StringWriter();
        new ConsoleLogFormatter(color).Format(logEvent, output);
        return output.ToString();
    }

    private static LogEvent Event(
        string template,
        LogEventLevel level = LogEventLevel.Information,
        Exception? exception = null,
        params (string Name, object? Value)[] properties) =>
        new(
            new DateTimeOffset(2026, 10, 4, 18, 26, 3, TimeSpan.Zero),
            level,
            exception,
            new MessageTemplateParser().Parse(template),
            properties.Select(p => new LogEventProperty(p.Name, new ScalarValue(p.Value))));

    /// <summary>The request-logging middleware's event; it logs a 5xx as an error.</summary>
    private static LogEvent Request(string method, string path, int status, double elapsedMs) => Event(
        "HTTP {RequestMethod} {RequestPath} responded {StatusCode} in {Elapsed:0.0000} ms",
        status >= 500 ? LogEventLevel.Error : LogEventLevel.Information,
        properties: [("RequestMethod", method), ("RequestPath", path), ("StatusCode", status), ("Elapsed", elapsedMs)]);

    [Fact]
    public void A_request_is_one_short_line_in_columns()
    {
        var line = Format(Request("POST", "/api/notes", 201, 262.4));

        Assert.Equal("18:26:03 INF  POST   /api/notes" + new string(' ', 30) + " 201  262 ms" + Environment.NewLine, line);
    }

    [Fact]
    public void A_guid_in_a_path_is_cut_to_its_first_eight_characters()
    {
        var line = Format(Request(
            "GET", "/api/notes/5e531dac-1c2d-4a5b-9f00-0123456789ab/media/2f24b901-aaaa-bbbb-cccc-0123456789ab", 200, 26));

        Assert.Contains("/api/notes/5e531dac/media/2f24b901 ", line, Exact);
    }

    [Theory]
    [InlineData(4.2, "4 ms")]
    [InlineData(1450, "1.5 s")]
    [InlineData(720_000, "12 min")]
    public void Durations_read_in_the_unit_that_fits(double elapsedMs, string shown)
    {
        Assert.EndsWith(shown + Environment.NewLine, Format(Request("GET", "/api/realtime", 101, elapsedMs)), Exact);
    }

    [Fact]
    public void An_escape_sequence_in_a_request_path_is_shown_not_obeyed()
    {
        var line = Format(Request("GET", "/api/ci\u001b[2J\u009b31m", 404, 1), color: true);

        Assert.Contains(@"/api/ci\x1b[2J\x9b31m", line, Exact);
        // The formatter's own colour codes are the only escapes left: each one is a 38;5 or 1;… SGR
        // or a reset, never what the path asked for.
        Assert.DoesNotContain($"{Esc}[2J", line, Exact);
        Assert.DoesNotContain('\u009b', line);
    }

    [Fact]
    public void An_escape_sequence_in_a_logged_value_is_shown_not_obeyed()
    {
        var line = Format(Event("Sent email {Subject} to {To}", properties:
            [("Subject", "Reset\u001b]0;pwned\u0007"), ("To", "a@example.com")]), color: true);

        Assert.Contains(@"Reset\x1b]0;pwned\x07", line, Exact);
        Assert.DoesNotContain($"{Esc}]0;", line, Exact);
    }

    [Fact]
    public void Without_colour_there_is_no_escape_at_all()
    {
        var line = Format(Request("DELETE", "/api/lists/1", 500, 3000));

        Assert.DoesNotContain(Esc, line);
        Assert.StartsWith("18:26:03 ERR  DELETE /api/lists/1", line, Exact);
    }

    [Fact]
    public void A_string_value_is_written_bare_and_others_as_rendered()
    {
        var line = Format(Event("Media orphan sweep removed {Count} folder(s) in {Folder}.", properties:
            [("Count", 3), ("Folder", "/data/users")]));

        Assert.Equal("18:26:03 INF  Media orphan sweep removed 3 folder(s) in /data/users." + Environment.NewLine, line);
    }

    [Fact]
    public void A_message_over_several_lines_continues_under_its_first()
    {
        var line = Format(Event("Email delivery is not configured.\nTo: {To}", LogEventLevel.Warning,
            properties: [("To", "a@example.com")]));

        Assert.Equal(
            "18:26:03 WRN  Email delivery is not configured." + Environment.NewLine +
            "              To: a@example.com" + Environment.NewLine,
            line);
    }

    [Fact]
    public void An_exception_follows_its_line_indented()
    {
        Exception thrown;
        try { throw new InvalidOperationException("dispatch failed"); }
        catch (InvalidOperationException e) { thrown = e; }

        var lines = Format(Event("Reminder dispatch tick failed", LogEventLevel.Error, thrown))
            .Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries);

        Assert.Equal("18:26:03 ERR  Reminder dispatch tick failed", lines[0]);
        Assert.StartsWith("              System.InvalidOperationException: dispatch failed", lines[1], Exact);
        Assert.All(lines.Skip(1), l => Assert.StartsWith(new string(' ', 14), l, Exact));
    }
}
