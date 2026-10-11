using Npgsql;

namespace keepITCore.Infrastructure;

/// <summary>
/// Database/config glue: resolves the one common data folder and decides the EF Core provider
/// from configuration (PostgreSQL if configured, else a SQLite dev fallback). See ARCHITECTURE.md.
/// </summary>
public static class DatabaseSetup
{
    /// <summary>
    /// Returns a Postgres connection string if one is configured — either ConnectionStrings:Postgres
    /// or a complete set of POSTGRES_* env vars — otherwise null (caller falls back to SQLite).
    /// </summary>
    public static string? ResolvePostgresConnectionString(IConfiguration config) =>
        ConfiguredPostgresConnectionString(config) is { } configured ? WithoutGssEncryption(configured) : null;

    /// <summary>
    /// Turns off Npgsql's attempt at GSS (Kerberos) encryption unless the connection string requires
    /// it. Npgsql's default, Prefer, tries it and falls back to none, and trying loads
    /// <c>libgssapi_krb5</c>. The images don't have it, so the fallback was all that ever happened,
    /// and .NET wrote "Error: libgssapi_krb5.so.2: cannot open shared object file" to the log at
    /// every start.
    /// </summary>
    private static string WithoutGssEncryption(string connectionString)
    {
        var builder = new NpgsqlConnectionStringBuilder(connectionString);
        if (builder.GssEncryptionMode == GssEncryptionMode.Prefer)
            builder.GssEncryptionMode = GssEncryptionMode.Disable;
        return builder.ConnectionString;
    }

    private static string? ConfiguredPostgresConnectionString(IConfiguration config)
    {
        var direct = config.GetConnectionString("Postgres");
        if (!string.IsNullOrWhiteSpace(direct))
            return direct;

        var host = config["POSTGRES_HOST"];
        if (string.IsNullOrWhiteSpace(host))
            return null;

        var port = config["POSTGRES_PORT"] ?? "5432";
        var dbName = config["POSTGRES_DB"] ?? "keepit";
        var user = config["POSTGRES_USER"] ?? "keepit";
        var password = config["POSTGRES_PASSWORD"] ?? "";

        return $"Host={host};Port={port};Database={dbName};Username={user};Password={password}";
    }

    /// <summary>The SQLite dev database file path inside the data folder.</summary>
    public static string SqliteConnectionString(string dataRoot) =>
        $"Data Source={Path.Combine(dataRoot, "keepit.db")}";
}
