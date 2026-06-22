namespace keepITCore.Infrastructure;

/// <summary>
/// Database/config glue: resolves the one common data folder and decides the EF Core provider
/// from configuration (PostgreSQL if configured, else a SQLite dev fallback). See ARCHITECTURE.md.
/// </summary>
public static class DatabaseSetup
{
    /// <summary>Resolves App:DataRoot (default ./data) to an absolute path and ensures it exists.</summary>
    public static string EnsureDataRoot(IConfiguration config, IHostEnvironment env)
    {
        var configured = config["App:DataRoot"];
        // Default "App_Data" (not "data") so it never collides with the C# Data/ source folder
        // on case-insensitive filesystems (Windows/macOS).
        var root = string.IsNullOrWhiteSpace(configured) ? "./App_Data" : configured;
        var full = Path.GetFullPath(root, env.ContentRootPath);
        Directory.CreateDirectory(full);
        return full;
    }

    /// <summary>
    /// Returns a Postgres connection string if one is configured — either ConnectionStrings:Postgres
    /// or a complete set of POSTGRES_* env vars — otherwise null (caller falls back to SQLite).
    /// </summary>
    public static string? ResolvePostgresConnectionString(IConfiguration config)
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
