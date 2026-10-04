using Serilog;

namespace keepITCore.Infrastructure;

/// <summary>
/// Wires up Serilog as the app's logging provider, writing to the console in
/// <see cref="ConsoleLogFormatter"/>'s short, coloured lines. Levels and per-source overrides are
/// read from the "Serilog" config section, so verbosity is tunable without a recompile; the
/// format lives in code.
/// </summary>
public static class LoggingServiceExtensions
{
    /// <summary>
    /// Replaces the default logging with Serilog: the console formatter plus <c>LogContext</c>
    /// enrichment. The host flushes Serilog on shutdown, so no manual teardown is needed.
    /// </summary>
    public static WebApplicationBuilder AddSerilogLogging(this WebApplicationBuilder builder)
    {
        var formatter = new ConsoleLogFormatter(ConsoleLogFormatter.ColorFromEnvironment());

        builder.Host.UseSerilog((context, services, config) => config
            .ReadFrom.Configuration(context.Configuration)
            .ReadFrom.Services(services)
            .Enrich.FromLogContext()
            // Logged as a warning whenever Data Protection makes a new key, and expected here: the
            // key ring sits in the data folder beside the database it protects, so encrypting it
            // with a key kept in that same folder would add nothing. A warning nobody should act
            // on only teaches people to skim past the ones they should.
            .Filter.ByExcluding(e =>
                e.MessageTemplate.Text.StartsWith("No XML encryptor configured", StringComparison.Ordinal))
            .WriteTo.Console(formatter));

        return builder;
    }
}
