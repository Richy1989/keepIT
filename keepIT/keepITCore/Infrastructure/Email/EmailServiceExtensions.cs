namespace keepITCore.Infrastructure.Email;

/// <summary>
/// Registers outbound email, picking the implementation from config at startup — the same
/// pattern as the database provider selection: configured → real (SMTP), absent → local fallback
/// (log). Kept out of Program.cs so the startup file stays a readable outline.
/// </summary>
public static class EmailServiceExtensions
{
    /// <summary>
    /// Binds <see cref="EmailOptions"/> and registers <see cref="IEmailSender"/>:
    /// <see cref="SmtpEmailSender"/> when <c>Email:SmtpHost</c> is set, otherwise
    /// <see cref="LogOnlyEmailSender"/>.
    /// </summary>
    public static IServiceCollection AddAppEmail(this IServiceCollection services, IConfiguration configuration)
    {
        services.Configure<EmailOptions>(configuration.GetSection(EmailOptions.SectionName));

        var options = configuration.GetSection(EmailOptions.SectionName).Get<EmailOptions>() ?? new EmailOptions();
        if (options.IsConfigured)
        {
            if (string.IsNullOrWhiteSpace(options.From))
                throw new InvalidOperationException(
                    "Email:From is required when Email:SmtpHost is configured.");
            services.AddScoped<IEmailSender, SmtpEmailSender>();
        }
        else
        {
            services.AddScoped<IEmailSender, LogOnlyEmailSender>();
        }

        return services;
    }
}
