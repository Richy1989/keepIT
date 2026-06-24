using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Data;

/// <summary>
/// EF Core context. Identity tables come from <see cref="IdentityDbContext{TUser,TRole,TKey}"/>;
/// app tables (refresh tokens, notes, lists) are added here. Media/sharing land here later.
/// </summary>
public class AppDbContext : IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>
{
    /// <summary>Creates the context. Options (provider, connection string) are supplied by DI.</summary>
    /// <param name="options">The configured EF Core options for this context.</param>
    public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
    {
    }

    /// <summary>Refresh tokens issued across all users' devices.</summary>
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();

    /// <summary>All users' notes.</summary>
    public DbSet<Note> Notes => Set<Note>();

    /// <summary>Checklist rows belonging to checklist notes.</summary>
    public DbSet<ChecklistItem> ChecklistItems => Set<ChecklistItem>();

    /// <summary>User-created lists (the "List" concept; see <see cref="KeepList"/>).</summary>
    public DbSet<KeepList> Lists => Set<KeepList>();

    /// <summary>Per-user note↔list memberships.</summary>
    public DbSet<NoteList> NoteLists => Set<NoteList>();

    /// <summary>Per-user user settings.</summary>
    public DbSet<UserSettings> UserSettings => Set<UserSettings>();

    /// <summary>
    /// Configures the EF Core model: Identity tables from the base context plus the app's own
    /// entities (refresh tokens, the user's extra columns).
    /// </summary>
    /// <param name="builder">The model builder used to configure entity mappings.</param>
    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);

        builder.Entity<RefreshToken>(e =>
        {
            e.HasKey(rt => rt.Id);
            e.Property(rt => rt.TokenHash).HasMaxLength(128).IsRequired();
            // Looked up by hash on every refresh/logout.
            e.HasIndex(rt => rt.TokenHash).IsUnique();
            e.Property(rt => rt.ReplacedByTokenHash).HasMaxLength(128);

            e.HasOne(rt => rt.User)
                .WithMany(u => u.RefreshTokens)
                .HasForeignKey(rt => rt.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<ApplicationUser>(e =>
        {
            e.Property(u => u.DisplayName).HasMaxLength(100);
        });

        builder.Entity<Note>(e =>
        {
            e.HasKey(n => n.Id);
            e.Property(n => n.Title).HasMaxLength(1000);
            e.Property(n => n.Color).HasMaxLength(32);
            // The grid always filters by owner (+ archived/trashed flags), so index the owner.
            e.HasIndex(n => n.OwnerId);

            e.HasOne(n => n.Owner)
                .WithMany()
                .HasForeignKey(n => n.OwnerId)
                .OnDelete(DeleteBehavior.Cascade);

            e.HasMany(n => n.ChecklistItems)
                .WithOne(c => c.Note)
                .HasForeignKey(c => c.NoteId)
                .OnDelete(DeleteBehavior.Cascade);


        });

        builder.Entity<ChecklistItem>(e =>
        {
            e.HasKey(c => c.Id);
            e.Property(c => c.Text).HasMaxLength(2000);
        });

        builder.Entity<KeepList>(e =>
        {
            e.HasKey(l => l.Id);
            e.Property(l => l.Name).HasMaxLength(100).IsRequired();
            e.Property(l => l.Color).HasMaxLength(32);
            e.HasIndex(l => l.OwnerId);

            e.HasOne(l => l.Owner)
                .WithMany()
                .HasForeignKey(l => l.OwnerId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<NoteList>(e =>
        {
            // One membership per (note, list, user); list membership is private per user.
            e.HasKey(nl => new { nl.NoteId, nl.ListId, nl.UserId });
            e.HasIndex(nl => nl.UserId);

            e.HasOne(nl => nl.Note)
                .WithMany(n => n.NoteLists)
                .HasForeignKey(nl => nl.NoteId)
                .OnDelete(DeleteBehavior.Cascade);

            e.HasOne(nl => nl.List)
                .WithMany(l => l.NoteLists)
                .HasForeignKey(nl => nl.ListId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<UserSettings>(e =>
            {
                e.HasKey(n => n.Id);
               // e.Property(n => n.Title).HasMaxLength(1000);
               // e.Property(n => n.Color).HasMaxLength(32);
                // The grid always filters by owner (+ archived/trashed flags), so index the owner.
                e.HasIndex(n => n.OwnerId);
            });
    }
}
