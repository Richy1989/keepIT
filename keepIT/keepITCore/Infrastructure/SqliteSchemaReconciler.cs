using System.Text;
using System.Text.RegularExpressions;
using keepITCore.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;

namespace keepITCore.Infrastructure;

/// <summary>
/// Brings an <b>existing</b> SQLite database up to the current model.
/// <para>Postgres upgrades cleanly because it runs migrations. SQLite does not: the file is created
/// with <c>EnsureCreated()</c>, which builds the whole schema for a database that doesn't exist yet
/// and is a <em>no-op for one that does</em>. So every schema change since a file was created is
/// simply absent from it, and the first query touching a new table or column fails with
/// <c>SQLite Error 1: 'no such table: …'</c> — on an instance whose notes are all still there.</para>
/// <para>Migrations can't be retrofitted here: the ones in this project are generated against Npgsql
/// (Postgres column types) and an <c>EnsureCreated</c> database has no <c>__EFMigrationsHistory</c>,
/// so <c>Migrate()</c> would try to replay every migration over populated tables. Instead this asks
/// EF for the create script <em>for the current model in SQLite's own dialect</em> and runs only the
/// statements whose table or index is missing, then appends missing columns with <c>ALTER TABLE</c>.
/// Existing tables and all data are left untouched.</para>
/// <para>The passes are ordered — tables, then columns, then indexes — because an index the model
/// added may cover a column this run is about to add.</para>
/// </summary>
public static class SqliteSchemaReconciler
{
    /// <summary>Matches <c>CREATE TABLE "Name"</c>, capturing the table name.</summary>
    private static readonly Regex CreateTable = new(
        @"^\s*CREATE\s+TABLE\s+""(?<name>[^""]+)""",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>Matches <c>CREATE [UNIQUE] INDEX "Name"</c>, capturing the index name.</summary>
    private static readonly Regex CreateIndex = new(
        @"^\s*CREATE\s+(?:UNIQUE\s+)?INDEX\s+""(?<name>[^""]+)""",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// Creates any tables, columns and indexes the model defines that the database is missing.
    /// </summary>
    /// <param name="db">The context, which must be on the SQLite provider.</param>
    /// <param name="logger">Logger for what was changed, and for anything needing manual attention.</param>
    /// <returns>The number of statements applied (0 when the schema was already current).</returns>
    public static int Reconcile(AppDbContext db, ILogger logger)
    {
        var existing = ExistingObjects(db);
        var statements = Statements(db.Database.GenerateCreateScript()).ToList();

        // Tables first: a column add or an index needs its table to be there.
        var applied = Create(db, logger, statements, CreateTable, existing);
        // Then columns, so an index created below can reference one added here.
        applied += AddMissingColumns(db, logger);
        applied += Create(db, logger, statements, CreateIndex, existing);

        return applied;
    }

    /// <summary>Runs the create statements of one kind whose object the database doesn't have yet.</summary>
    private static int Create(
        AppDbContext db,
        ILogger logger,
        IEnumerable<string> statements,
        Regex kind,
        HashSet<string> existing)
    {
        var applied = 0;

        foreach (var statement in statements)
        {
            // Anything that isn't this kind (the other kind, or a pragma) is skipped here.
            var match = kind.Match(statement);
            if (!match.Success) continue;

            // Add returns false for a name already in the database — and stops a duplicate run.
            if (!existing.Add(match.Groups["name"].Value)) continue;

            db.Database.ExecuteSqlRaw(statement);
            applied++;
            logger.LogInformation("SQLite schema: created missing {Object}.", match.Groups["name"].Value);
        }

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

    /// <summary>
    /// Adds every column the model expects that an existing table doesn't have. SQLite's
    /// <c>ALTER TABLE ADD COLUMN</c> rewrites nothing — it appends the column and reads existing rows
    /// back with its default — so this stays cheap and safe on a populated table. The few cases
    /// SQLite refuses outright are logged instead (see <see cref="ColumnDefinition"/>).
    /// </summary>
    private static int AddMissingColumns(AppDbContext db, ILogger logger)
    {
        var applied = 0;

        // The design-time model, not db.Model: the runtime one is trimmed of the metadata a column
        // definition needs (reading Collation off it throws), and it is what the create script above
        // was generated from, so both passes see the same schema.
        var model = db.GetService<IDesignTimeModel>().Model.GetRelationalModel();

        foreach (var table in model.Tables)
        {
            var actual = ColumnsOf(db, table.Name);
            if (actual.Count == 0) continue; // table absent or empty metadata; nothing to compare

            foreach (var column in table.Columns.Where(c => !actual.Contains(c.Name)))
            {
                var definition = ColumnDefinition(column);
                if (definition is null)
                {
                    logger.LogWarning(
                        "SQLite schema: table {Table} is missing column {Column}, which SQLite cannot "
                        + "add in place. Back up the database and recreate it, or move this instance "
                        + "to PostgreSQL (which upgrades via migrations).",
                        table.Name, column.Name);
                    continue;
                }

                // DDL takes no parameters, and every part of this comes from EF's own model
                // metadata — no caller input reaches it.
                var sql = $"ALTER TABLE {Quote(table.Name)} ADD COLUMN {definition}";
                db.Database.ExecuteSqlRaw(sql);
                applied++;
                logger.LogInformation(
                    "SQLite schema: added missing column {Table}.{Column}.", table.Name, column.Name);
            }
        }

        return applied;
    }

    /// <summary>
    /// The <c>ADD COLUMN</c> clause for one column, or null when SQLite cannot append it — a computed
    /// column, or a NOT NULL one whose type offers no value to give the rows already in the table.
    /// </summary>
    private static string? ColumnDefinition(IColumn column)
    {
        if (column.ComputedColumnSql is not null) return null;

        var literal = DefaultLiteral(column);
        if (literal is null && !column.IsNullable) return null;

        var definition = new StringBuilder($"{Quote(column.Name)} {column.StoreType}");
        if (!column.IsNullable) definition.Append(" NOT NULL");
        if (literal is not null) definition.Append($" DEFAULT {literal}");
        if (column.Collation is not null) definition.Append($" COLLATE {column.Collation}");

        return definition.ToString();
    }

    /// <summary>
    /// The SQL literal the rows already in the table get for a new column: the model's own default
    /// when it declares one, otherwise — for NOT NULL, which SQLite refuses without a default — the
    /// store type's zero value (0, an empty string, an empty GUID, 0001-01-01). That is what EF
    /// would have written for a row created before the property existed.
    /// </summary>
    private static string? DefaultLiteral(IColumn column)
    {
        if (column.DefaultValueSql is not null) return column.DefaultValueSql;

        if (column.TryGetDefaultValue(out var declared) && declared is not null)
            return column.StoreTypeMapping.GenerateSqlLiteral(declared);

        if (column.IsNullable) return null;

        var zero = ZeroValue(column.StoreTypeMapping.ClrType);
        return zero is null ? null : column.StoreTypeMapping.GenerateSqlLiteral(zero);
    }

    /// <summary>The "empty" value of a store type, or null for a reference type that has none.</summary>
    private static object? ZeroValue(Type type)
    {
        if (type == typeof(string)) return string.Empty;
        if (type == typeof(byte[])) return Array.Empty<byte>();
        return type.IsValueType ? Activator.CreateInstance(type) : null;
    }

    /// <summary>The column names of one table, or empty when the table doesn't exist.</summary>
    private static HashSet<string> ColumnsOf(AppDbContext db, string table)
    {
        var columns = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        using var command = db.Database.GetDbConnection().CreateCommand();
        // PRAGMA doesn't take parameters, so the identifier goes in quoted instead.
        command.CommandText = $"PRAGMA table_info({Quote(table)})";
        db.Database.OpenConnection();
        using var reader = command.ExecuteReader();
        while (reader.Read())
            columns.Add(reader.GetString(1)); // 1 = name

        return columns;
    }

    /// <summary>Quotes an identifier for SQLite, doubling any embedded quote.</summary>
    private static string Quote(string identifier) => $"\"{identifier.Replace("\"", "\"\"")}\"";
}
