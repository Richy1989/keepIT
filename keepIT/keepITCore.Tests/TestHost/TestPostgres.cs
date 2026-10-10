using Npgsql;

namespace keepITCore.Tests.TestHost;

/// <summary>
/// The Postgres server the tests run on instead of SQLite, when <c>KEEPIT_TEST_POSTGRES</c> names
/// one: a connection string to the server and a database to connect to while creating others, such
/// as <c>Host=127.0.0.1;Port=55432;Database=postgres;Username=postgres;Password=…</c>.
/// <c>scripts/test-postgres.sh</c> starts one in Docker, and CI runs the whole suite on one.
/// Each host gets an empty database of its own, so the API's own <c>Migrate()</c> builds it from
/// the first migration up, exactly as a new Compose install does.
/// </summary>
public static class TestPostgres
{
    /// <summary>The server, or null to run on SQLite.</summary>
    public static string? Server { get; } =
        Environment.GetEnvironmentVariable("KEEPIT_TEST_POSTGRES") is { Length: > 0 } server ? server : null;

    /// <summary>Creates an empty database and returns a connection string to it.</summary>
    public static string CreateDatabase()
    {
        var name = $"keepit_test_{Guid.NewGuid():N}";
        Execute($"CREATE DATABASE \"{name}\"");
        return new NpgsqlConnectionStringBuilder(Server) { Database = name }.ConnectionString;
    }

    /// <summary>Drops a database <see cref="CreateDatabase"/> made, closing what still holds it.</summary>
    public static void DropDatabase(string connectionString)
    {
        var name = new NpgsqlConnectionStringBuilder(connectionString).Database;
        NpgsqlConnection.ClearAllPools();
        Execute($"DROP DATABASE IF EXISTS \"{name}\" WITH (FORCE)");
    }

    private static void Execute(string sql)
    {
        using var connection = new NpgsqlConnection(Server);
        connection.Open();
        using var command = new NpgsqlCommand(sql, connection);
        command.ExecuteNonQuery();
    }
}
