using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Tests.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;

namespace keepITCore.Tests;

/// <summary>
/// The suite runs on SQLite, and on Postgres when <c>KEEPIT_TEST_POSTGRES</c> names a server; CI
/// does both. Postgres is what Docker Compose runs, and the only database the migrations ever
/// touch: SQLite builds its schema from the model, so a migration can be wrong or missing and
/// every SQLite test still passes.
/// </summary>
public sealed class DatabaseTests
{
    /// <summary>
    /// A run asked for Postgres that quietly got SQLite would pass while proving nothing about
    /// Postgres.
    /// </summary>
    [Fact]
    public void The_tests_run_on_the_database_they_were_asked_for()
    {
        using var api = new KeepItApiFactory();
        using var scope = api.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

        Assert.Equal(TestPostgres.Server is not null, db.Database.IsNpgsql());
        Assert.Equal(api.PostgresDatabase is not null, db.Database.IsNpgsql());
    }

    /// <summary>
    /// An entity changed without a migration ships a Postgres server whose first query on the new
    /// column fails. EF compares the model with the last migration's snapshot, so this needs no
    /// database and runs on both.
    /// </summary>
    [Fact]
    public void Every_change_to_the_model_has_a_migration()
    {
        using var db = new AppDbContextFactory().CreateDbContext([]);

        Assert.False(db.Database.HasPendingModelChanges(),
            "The model has changed since the last migration: dotnet ef migrations add <Name> --project keepIT/keepITCore");
    }

    /// <summary>
    /// Npgsql tries GSS encryption by default, and the images lack the library for it, so .NET
    /// logged an error at every start. keepIT turns it off, either way PostgreSQL is configured,
    /// unless the connection string requires it.
    /// </summary>
    [Theory]
    [InlineData("ConnectionStrings:Postgres", "Host=db;Database=keepit;Username=keepit;Password=p", "Disable")]
    [InlineData("ConnectionStrings:Postgres", "Host=db;Password=p;GSS Encryption Mode=Require", "Require")]
    [InlineData("ConnectionStrings:Postgres", "Host=db;Password=p;GssEncryptionMode=Prefer", "Disable")]
    [InlineData("POSTGRES_HOST", "db", "Disable")]
    public void PostgreSQL_is_asked_for_GSS_encryption_only_when_the_connection_string_requires_it(
        string key, string value, string expected)
    {
        var config = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?> { [key] = value, ["POSTGRES_PASSWORD"] = "p" })
            .Build();

        var connection = new NpgsqlConnectionStringBuilder(DatabaseSetup.ResolvePostgresConnectionString(config));

        Assert.Equal(Enum.Parse<GssEncryptionMode>(expected), connection.GssEncryptionMode);
        Assert.Equal("db", connection.Host);
        Assert.NotNull(connection.Password);
    }

    [Fact]
    public void Without_PostgreSQL_settings_it_is_SQLite() =>
        Assert.Null(DatabaseSetup.ResolvePostgresConnectionString(new ConfigurationBuilder().Build()));
}
