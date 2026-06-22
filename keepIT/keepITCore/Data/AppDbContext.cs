using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Data;

/// <summary>
/// EF Core context. Identity tables come from <see cref="IdentityDbContext{TUser,TRole,TKey}"/>;
/// app tables (currently just refresh tokens) are added here. Notes/lists/media land here later.
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
    }
}
