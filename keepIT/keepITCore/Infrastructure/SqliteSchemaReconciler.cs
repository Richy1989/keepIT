using System.Text.RegularExpressions;
using keepITCore.Data;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Infrastructure;

/// <summary>
/// Brings an <b>existing</b> SQLite database up to the current model.
/// <para>Postgres upgrades cleanly because it runs migrations. SQLite does not: the file is created
/// with <c>EnsureCreated()</c>, which builds the whole schema for a database that doesn't exist yet
/// and is a <em>no-op for one that does</em>. So every schema change since a file was created is
/// simply absent from it, and the first query touching a new table fails with
/// <c>SQLite Error 1: 'no such table: …'</c> — on an instance whose notes are all still there.</para>
/// <para>Migrations can't be retrofitted here: the ones in this project are generated against Npgsql
/// (Postgres column types) and an <c>EnsureCreated</c> database has no <c>__EFMigrationsHistory</c>,
/// so <c>Migrate()</c> would try to replay every migration over populated tables. Instead this asks
/// EF for the create script <em>for the current model in SQLite's own dialect</em> and runs only the
/// statements whose table or index is missing. Existing tables and all data are left untouched.</para>
/// <para>Adding a <b>column</b> to an existing table is deliberately not attempted — SQLite cannot
/// add a NOT NULL column without a default, and doing it properly means a table rebuild. Those are
/// detected and logged loudly instead, so the operator gets a clear instruction rather than a 500.</para>
/// </summary>
public static class SqliteSchemaReconciler
{
    /// <summary>Matches <c>CREATE TABLE "Name"</c>, capturing the table name.</summary>
    private static readonly Regex CreateTable = new(
        @"^\s*CREATE\s+TABLE\s+""(?<name>[^""]+)""",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>Matches <c>CREATE [UNIQUE] INDEX "Name"</c>, capturing the index name.</summary>
    private static readonly Regex CreateIndex = new(
        @"^\s*CREATE\s+(UNIQUE\s+)?INDEX\s+""(?<name>[^""]+)""",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// Creates any tables and indexes the model defines that the database is missing.
    /// </summary>
    /// <param name="db">The context, which must be on the SQLite provider.</param>
    /// <param name="logger">Logger for what was created, and for columns needing manual attention.</param>
    /// <returns>The number of statements applied (0 when the schema was already current).</returns>
    public static int Reconcile(AppDbContext db, ILogger logger)
    {
        var existing = ExistingObjects(db);
        var applied = 0;

        foreach (var statement in Statements(db.Database.GenerateCreateScript()))
        {
            var name = ObjectName(statement);
            // Anything we can't classify (a pragma, say) is left to EnsureCreated's own run.
            if (name is null || existing.Contains(name)) continue;

            db.Database.ExecuteSqlRaw(statement);
            existing.Add(name);
            applied++;
            logger.LogInformation("SQLite schema: created missing {Object}.", name);
        }

        WarnAboutMissingColumns(db, logger);
        return applied;
    }

    /// <summary>Every table and index name currently in the database.</summary>
    private static HashSet<string> ExistingObjects(AppDbContext db)
    {
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        using var command = db.Database.GetDbConnection().CreateCommand();
        command.CommandText = "SELECT name FROM sqlite_master WHERE type IN ('table', 'index')";
        db.Database.OpenConnection();
        using var reader = command.ExecuteReader();
        while (reader.Read())
            names.Add(reader.GetString(0));

        return names;
    }

    /// <summary>Splits a generated create script into individual statements.</summary>
    private static IEnumerable<string> Statements(string script) =>
        script.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(s => s.Length > 0);

    /// <summary>The table or index a CREATE statement targets, or null if it is neither.</summary>
    private static string? ObjectName(string statement)
    {
        var table = CreateTable.Match(statement);
        if (table.Success) return table.Groups["name"].Value;

        var index = CreateIndex.Match(statement);
        return index.Success ? index.Groups["name"].Value : null;
    }

    /// <summary>
    /// Logs any column the model expects that an existing table doesn't have. Not repaired
    /// automatically (see the type's remarks) — but an explicit warning beats a runtime 500.
    /// </summary>
    private static void WarnAboutMissingColumns(AppDbContext db, ILogger logger)
    {
        var model = db.Model.GetRelationalModel();

        foreach (var table in model.Tables)
        {
            var actual = ColumnsOf(db, table.Name);
            if (actual.Count == 0) continue; // table absent or empty metadata; nothing to compare

            var missing = table.Columns
                .Select(c => c.Name)
                .Where(name => !actual.Contains(name))
                .ToList();

            if (missing.Count > 0)
            {
                logger.LogWarning(
                    "SQLite schema: table {Table} is missing column(s) {Columns}. SQLite cannot add "
                    + "these in place; back up {Db} and recreate it, or move this instance to "
                    + "PostgreSQL (which upgrades via migrations).",
                    table.Name, string.Join(", ", missing), "App_Data/keepit.db");
            }
        }
    }

    /// <summary>The column names of one table, or empty when the table doesn't exist.</summary>
    private static HashSet<string> ColumnsOf(AppDbContext db, string table)
    {
        var columns = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        using var command = db.Database.GetDbConnection().CreateCommand();
        // PRAGMA doesn't take parameters, so the identifier is quoted with internal quotes doubled.
        command.CommandText = $"""PRAGMA table_info("{table.Replace("\"", "\"\"")}")""";
        db.Database.OpenConnection();
        using var reader = command.ExecuteReader();
        while (reader.Read())
            columns.Add(reader.GetString(1)); // 1 = name

        return columns;
    }
}
