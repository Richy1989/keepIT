using System.Globalization;
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
    public static string? ResolvePostgresConnectionString(IConfiguration config)
    {
        if (ConfiguredPostgres(config) is not { } connection)
            return null;

        // Turns off Npgsql's attempt at GSS (Kerberos) encryption unless the connection string
        // requires it. Npgsql's default, Prefer, tries it and falls back to none, and trying loads
        // libgssapi_krb5. The images don't have it, so the fallback was all that ever happened, and
        // .NET wrote "Error: libgssapi_krb5.so.2: cannot open shared object file" to the log at
        // every start.
        if (connection.GssEncryptionMode == GssEncryptionMode.Prefer)
            connection.GssEncryptionMode = GssEncryptionMode.Disable;
        return connection.ConnectionString;
    }

    /// <summary>
    /// ConnectionStrings:Postgres as written, or one built from the POSTGRES_* settings; null
    /// without either.
    /// </summary>
    private static NpgsqlConnectionStringBuilder? ConfiguredPostgres(IConfiguration config)
    {
        var direct = config.GetConnectionString("Postgres");
        if (!string.IsNullOrWhiteSpace(direct))
            return new NpgsqlConnectionStringBuilder(direct);

        var host = config["POSTGRES_HOST"];
        if (string.IsNullOrWhiteSpace(host))
            return null;

        // Set one by one, never pasted into a string: the builder quotes what needs quoting, so a
        // password with a ";" in it can't end the password and start another setting.
        return new NpgsqlConnectionStringBuilder
        {
            Host = host,
            Port = int.Parse(config["POSTGRES_PORT"] ?? "5432", CultureInfo.InvariantCulture),
            Database = config["POSTGRES_DB"] ?? "keepit",
            Username = config["POSTGRES_USER"] ?? "keepit",
            Password = config["POSTGRES_PASSWORD"] ?? "",
        };
    }

    /// <summary>The SQLite dev database file path inside the data folder.</summary>
    public static string SqliteConnectionString(string dataRoot) =>
        $"Data Source={Path.Combine(dataRoot, "keepit.db")}";
}
