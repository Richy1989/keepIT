using System.Collections.Generic;
using Serilog;
using Serilog.Sinks.SystemConsole.Themes;

namespace keepITCore.Infrastructure;

/// <summary>
/// Wires up Serilog as the app's logging provider with a clean, colored console sink. Levels and
/// per-source overrides are read from the "Serilog" config section, so verbosity is tunable without
/// a recompile; the sink and output format live here in code.
/// </summary>
public static class LoggingServiceExtensions
{
    /// <summary>The ANSI escape character (ESC, U+001B / decimal 27) that introduces an SGR sequence.</summary>
    private static readonly string Esc = ((char)27).ToString();

    /// <summary>
    /// Replaces the default logging with Serilog: a themed, color-coded console sink plus
    /// <c>LogContext</c> enrichment. The host flushes Serilog on shutdown, so no manual teardown is
    /// needed.
    /// </summary>
    public static WebApplicationBuilder AddSerilogLogging(this WebApplicationBuilder builder)
    {
        builder.Host.UseSerilog((context, services, config) => config
            .ReadFrom.Configuration(context.Configuration)
            .ReadFrom.Services(services)
            .Enrich.FromLogContext()
            .WriteTo.Console(
                theme: ColorfulTheme,
                outputTemplate: "[{Timestamp:HH:mm:ss} {Level:u3}] {Message:lj}{NewLine}{Exception}"));

        return builder;
    }

    /// <summary>
    /// A high-contrast console theme. Each <see cref="ConsoleThemeStyle"/> maps to an ANSI SGR escape
    /// (256-colour palette); the sink appends the reset after each styled span. Tweak the number in
    /// <c>38;5;&lt;n&gt;</c> (foreground) or <c>48;5;&lt;n&gt;</c> (background) to recolour a token —
    /// <c>n</c> is a 0–255 xterm colour. Levels stand out most: warnings bold-amber, errors/fatals as
    /// a white-on-red badge. Prefer a ready-made look? Swap this for a built-in such as
    /// <c>AnsiConsoleTheme.Literate</c> or <c>AnsiConsoleTheme.Sixteen</c> in the sink above.
    /// </summary>
    private static readonly AnsiConsoleTheme ColorfulTheme = new(
        new Dictionary<ConsoleThemeStyle, string>
        {
            [ConsoleThemeStyle.Text]             = $"{Esc}[38;5;253m", // message text — near-white
            [ConsoleThemeStyle.SecondaryText]    = $"{Esc}[38;5;244m", // timestamp, brackets — grey
            [ConsoleThemeStyle.TertiaryText]     = $"{Esc}[38;5;240m", // punctuation — dim grey
            [ConsoleThemeStyle.Invalid]          = $"{Esc}[38;5;232;48;5;208m",
            [ConsoleThemeStyle.Null]             = $"{Esc}[38;5;141m", // null — violet
            [ConsoleThemeStyle.Name]             = $"{Esc}[38;5;81m",  // property names — cyan
            [ConsoleThemeStyle.String]           = $"{Esc}[38;5;114m", // strings — green
            [ConsoleThemeStyle.Number]           = $"{Esc}[38;5;208m", // numbers — orange
            [ConsoleThemeStyle.Boolean]          = $"{Esc}[38;5;75m",  // booleans — blue
            [ConsoleThemeStyle.Scalar]           = $"{Esc}[38;5;79m",  // other scalars — teal
            [ConsoleThemeStyle.LevelVerbose]     = $"{Esc}[38;5;244m", // VRB — grey
            [ConsoleThemeStyle.LevelDebug]       = $"{Esc}[38;5;39m",  // DBG — sky blue
            [ConsoleThemeStyle.LevelInformation] = $"{Esc}[38;5;48m",  // INF — bright green
            [ConsoleThemeStyle.LevelWarning]     = $"{Esc}[1m{Esc}[38;5;220m",          // WRN — bold amber
            [ConsoleThemeStyle.LevelError]       = $"{Esc}[1m{Esc}[38;5;231;48;5;160m", // ERR — white on red
            [ConsoleThemeStyle.LevelFatal]       = $"{Esc}[1m{Esc}[38;5;231;48;5;196m", // FTL — white on bright red
        });
}
