using keepITCore.Data;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Serilog.Events;

namespace keepITCore.Infrastructure;

/// <summary>
/// <c>GET /api/health</c>: 200 "Healthy" while keepIT can reach its database and its data folder,
/// 503 "Unhealthy" when it can't. The image's Docker <c>HEALTHCHECK</c> asks it, and so can an
/// uptime monitor, so a container that runs but can't serve notes no longer looks like one that
/// works. Anonymous, and it answers with that one word: which check failed goes to the log.
/// </summary>
public static class HealthEndpoint
{
    /// <summary>Under /api, so nginx and the dev proxy route it like the rest of the API.</summary>
    public const string Path = "/api/health";

    /// <summary>Registers the two checks <see cref="Path"/> runs.</summary>
    public static IServiceCollection AddKeepItHealthChecks(this IServiceCollection services, string dataRoot)
    {
        services.AddHealthChecks()
            .AddCheck<DatabaseHealthCheck>("database")
            .AddCheck("data folder", () => Directory.Exists(dataRoot)
                ? HealthCheckResult.Healthy()
                : HealthCheckResult.Unhealthy($"The data folder {dataRoot} is gone."));
        return services;
    }

    /// <summary>
    /// The request log's level for a request. An exception or a 5xx is an Error: the server failed,
    /// and the operator should see it. Every other request is Debug, which the default Information
    /// log leaves out: a line per sync, save and thumbnail buried what matters (start-up, warnings,
    /// errors, an account deleted) under hundreds that say nothing. Set
    /// <c>Serilog__MinimumLevel__Default=Debug</c> to see them while looking into something. A
    /// healthy answer to <see cref="Path"/> is Verbose, below even that: Docker asks every 30 seconds,
    /// and an unhealthy answer is a 503, logged like any other.
    /// </summary>
    public static LogEventLevel RequestLogLevel(HttpContext http, double elapsedMs, Exception? exception) =>
        exception is not null || http.Response.StatusCode >= 500 ? LogEventLevel.Error
        : http.Request.Path == Path ? LogEventLevel.Verbose
        : LogEventLevel.Debug;

    /// <summary>
    /// Healthy when the database answers: PostgreSQL accepts a connection, or the SQLite file is
    /// where it belongs.
    /// </summary>
    private sealed class DatabaseHealthCheck(AppDbContext db) : IHealthCheck
    {
        public async Task<HealthCheckResult> CheckHealthAsync(
            HealthCheckContext context, CancellationToken cancellationToken = default) =>
            await db.Database.CanConnectAsync(cancellationToken)
                ? HealthCheckResult.Healthy()
                : HealthCheckResult.Unhealthy("The database can't be reached.");
    }
}
