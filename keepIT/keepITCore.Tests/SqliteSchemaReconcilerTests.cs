using System.Net;
using System.Net.Http.Json;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Tests.TestHost;
using Microsoft.Extensions.Logging.Abstractions;

namespace keepITCore.Tests;

/// <summary>
/// An existing SQLite database must come up to the current schema at startup without losing data.
/// <c>EnsureCreated</c> does nothing to a file that already exists, so every schema change since a
/// file was created depends on <see cref="SqliteSchemaReconciler"/> — and when it fell short, an
/// instance upgraded to a new release failed on its first query while all its notes sat intact.
/// Each test builds today's schema, then removes something to recreate an older release's file.
/// </summary>
public sealed class SqliteSchemaReconcilerTests : IDisposable
{
    private readonly SqliteTestDb _db = new();

    public SqliteSchemaReconcilerTests()
    {
        using var context = _db.NewContext();
        context.Database.EnsureCreated();
    }

    public void Dispose() => _db.Dispose();

    /// <summary>What the API does at startup for SQLite.</summary>
    private int Reconcile()
    {
        using var context = _db.NewContext();
        context.Database.EnsureCreated();
        return SqliteSchemaReconciler.Reconcile(context, NullLogger.Instance);
    }

    /// <summary>A user and one note with its per-user state — the data an upgrade must not lose.</summary>
    private void SeedNote(Guid noteId)
    {
        using var context = _db.NewContext();
        var user = new ApplicationUser { Id = Guid.NewGuid(), UserName = "old@example.com", Email = "old@example.com" };
        context.Users.Add(user);
        context.Notes.Add(new Note { Id = noteId, OwnerId = user.Id, Title = "kept", Color = "red" });
        context.NoteUserStates.Add(new NoteUserState { NoteId = noteId, UserId = user.Id, IsPinned = true });
        context.SaveChanges();
    }

    [Fact]
    public void A_current_schema_is_left_untouched()
    {
        Assert.Equal(0, Reconcile());
    }

    [Fact]
    public void A_missing_table_and_its_index_are_created_and_existing_notes_survive()
    {
        var noteId = Guid.NewGuid();
        SeedNote(noteId);
        _db.Execute("DROP TABLE NoteMedia"); // a file from before image attachments

        Assert.True(Reconcile() > 0);

        Assert.True(_db.Has("NoteMedia"));
        Assert.True(_db.Has("IX_NoteMedia_NoteId"));
        Assert.Equal("kept", _db.Scalar("SELECT Title FROM Notes"));
    }

    [Fact]
    public void A_missing_index_is_recreated()
    {
        _db.Execute("DROP INDEX IX_Notes_OwnerId");

        Reconcile();

        Assert.True(_db.Has("IX_Notes_OwnerId"));
    }

    [Fact]
    public void A_missing_nullable_column_is_added_and_existing_rows_read_null()
    {
        SeedNote(Guid.NewGuid());
        _db.Execute("ALTER TABLE Notes DROP COLUMN Color");

        Reconcile();

        Assert.True(_db.Columns("Notes").TryGetValue("Color", out var notNull));
        Assert.False(notNull);
        Assert.Equal(DBNull.Value, _db.Scalar("SELECT Color FROM Notes"));
    }

    [Fact]
    public void A_missing_not_null_column_is_added_with_a_zero_default_for_existing_rows()
    {
        // SQLite refuses to add a NOT NULL column without a default, so the reconciler supplies the
        // type's zero value — what EF would have written for a row created before the property.
        SeedNote(Guid.NewGuid());
        _db.Execute("ALTER TABLE NoteUserStates DROP COLUMN IsPinned");

        Reconcile();

        Assert.True(_db.Columns("NoteUserStates").TryGetValue("IsPinned", out var notNull));
        Assert.True(notNull);
        Assert.Equal(0L, _db.Scalar("SELECT IsPinned FROM NoteUserStates"));
    }

    [Fact]
    public void A_second_run_after_an_upgrade_changes_nothing()
    {
        _db.Execute("DROP TABLE NoteMedia");
        Reconcile();

        Assert.Equal(0, Reconcile());
    }

    [Fact]
    public async Task The_API_starts_on_a_database_from_before_note_media_and_serves_its_notes()
    {
        // The upgrade exactly as a user hits it: an older release's file, then the new API on top.
        _db.Execute("DROP TABLE NoteMedia");
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();

        using var api = new KeepItApiFactory(_db.Folder);
        var client = await api.CreateSignedInClientAsync();
        (await client.PostAsJsonAsync("/api/notes", new { type = "Text", title = "after upgrade" }))
            .EnsureSuccessStatusCode();

        // Listing notes loads their media, so without the reconciler this is "no such table".
        var notes = await client.GetAsync("/api/notes");

        Assert.Equal(HttpStatusCode.OK, notes.StatusCode);
    }
}
