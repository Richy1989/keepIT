using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace keepITCore.Data;

/// <summary>
/// Design-time factory used only by <c>dotnet ef</c> (migrations/scaffolding). It always targets
/// Npgsql so the generated migrations are PostgreSQL-flavoured — Postgres is the authoritative
/// provider (see ARCHITECTURE.md). No database connection is opened to generate a migration.
/// </summary>
public class AppDbContextFactory : IDesignTimeDbContextFactory<AppDbContext>
{
    /// <summary>
    /// Builds an <see cref="AppDbContext"/> for design-time tooling, always using the Npgsql
    /// provider. The connection string comes from the ConnectionStrings__Postgres environment
    /// variable, falling back to a local placeholder (no connection is opened to scaffold).
    /// </summary>
    /// <param name="args">Arguments passed by the EF Core tools (unused).</param>
    /// <returns>A context configured for PostgreSQL migration generation.</returns>
    public AppDbContext CreateDbContext(string[] args)
    {
        var connectionString =
            Environment.GetEnvironmentVariable("ConnectionStrings__Postgres")
            ?? "Host=localhost;Port=5432;Database=keepit;Username=keepit;Password=keepit";

        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(connectionString)
            .Options;

        return new AppDbContext(options);
    }
}
