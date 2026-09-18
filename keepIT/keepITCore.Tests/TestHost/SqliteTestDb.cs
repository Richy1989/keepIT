using keepITCore.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Tests.TestHost;

/// <summary>A SQLite database file for schema tests, plus ways to look inside it.</summary>
public sealed class SqliteTestDb : IDisposable
{
    static SqliteTestDb()
    {
        // The API registers the native SQLite provider itself at startup (it ships no auto-init
        // bundle); a context built directly, outside the host, needs the same.
        SQLitePCL.raw.SetProvider(new SQLitePCL.SQLite3Provider_e_sqlite3());
    }

    private readonly string _folder = Directory.CreateTempSubdirectory("keepit-schema-").FullName;

    /// <summary>The folder holding the database — usable as an API data root.</summary>
    public string Folder => _folder;

    private string ConnectionString => $"Data Source={Path.Combine(_folder, "keepit.db")}";

    /// <summary>A context on this database, the way the API builds one.</summary>
    public AppDbContext NewContext() =>
        new(new DbContextOptionsBuilder<AppDbContext>().UseSqlite(ConnectionString).Options);

    /// <summary>Runs raw SQL — how a test turns the current schema back into an older one.</summary>
    public void Execute(string sql)
    {
        using var connection = new SqliteConnection(ConnectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    /// <summary>The first column of the first row of a query.</summary>
    public object? Scalar(string sql)
    {
        using var connection = new SqliteConnection(ConnectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        return command.ExecuteScalar();
    }

    /// <summary>Whether a table or index of that name exists.</summary>
    public bool Has(string name) =>
        Convert.ToInt64(Scalar($"SELECT COUNT(*) FROM sqlite_master WHERE name = '{name}'")) > 0;

    /// <summary>A table's columns and whether each is NOT NULL, or empty when the table is absent.</summary>
    public Dictionary<string, bool> Columns(string table)
    {
        using var connection = new SqliteConnection(ConnectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = $"PRAGMA table_info(\"{table}\")";
        using var reader = command.ExecuteReader();

        var columns = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
        while (reader.Read())
            columns[reader.GetString(1)] = reader.GetBoolean(3); // 1 = name, 3 = notnull
        return columns;
    }

    /// <inheritdoc />
    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(_folder, recursive: true); }
        catch (IOException) { /* best-effort: it's a temp folder */ }
        catch (UnauthorizedAccessException) { }
    }
}
