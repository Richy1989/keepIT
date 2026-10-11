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
    /// The request log's level for a request: Serilog's own rule (an error for an exception or a
    /// 5xx, information otherwise), except that a healthy answer to <see cref="Path"/> is Verbose,
    /// which the log leaves out. Docker asks every 30 seconds, so it would otherwise fill the log
    /// with a line that says nothing; an unhealthy answer is a 503 and logged like any other.
    /// </summary>
    public static LogEventLevel RequestLogLevel(HttpContext http, double elapsedMs, Exception? exception) =>
        exception is not null || http.Response.StatusCode >= 500 ? LogEventLevel.Error
        : http.Request.Path == Path ? LogEventLevel.Verbose
        : LogEventLevel.Information;

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
