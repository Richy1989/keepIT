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
                theme: AnsiConsoleTheme.Code,
                outputTemplate: "[{Timestamp:HH:mm:ss} {Level:u3}] {Message:lj}{NewLine}{Exception}"));

        return builder;
    }
}
