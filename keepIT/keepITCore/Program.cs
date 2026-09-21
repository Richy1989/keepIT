using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Infrastructure.Email;
using keepITCore.Infrastructure.Security;
using keepITCore.Service;
using keepITCore.SignalR;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Scalar.AspNetCore;
using Serilog;
using System.Text.Json.Serialization;

var builder = WebApplication.CreateBuilder(args);

// ---- Logging (Serilog: clean colored console, levels from the "Serilog" config section) ----
builder.AddSerilogLogging();

// ---- Options ----
builder.Services.Configure<JwtOptions>(builder.Configuration.GetSection(JwtOptions.SectionName));
builder.Services.Configure<RefreshCookieOptions>(builder.Configuration.GetSection(RefreshCookieOptions.SectionName));
builder.Services.Configure<MediaOptions>(builder.Configuration.GetSection(MediaOptions.SectionName));

var jwtOptions = builder.Configuration.GetSection(JwtOptions.SectionName).Get<JwtOptions>() ?? 
    throw new InvalidOperationException("JWT: jwt options could not cteated. Config mighe be missing.");

if (string.IsNullOrWhiteSpace(jwtOptions.Key) || jwtOptions.Key.Length < 32)
{
    throw new InvalidOperationException(
        "Jwt:Key must be a random secret of at least 32 characters. Set Jwt__Key via the environment.");
}

// Emailed links (password reset) are built only from App:PublicBaseUrl, never from the request. A
// malformed value is refused here, like a bad Jwt:Key, rather than on the first reset request.
var publicBaseUrl = PublicBaseUrl.Read(builder.Configuration);
var emailDelivered = builder.Configuration.GetSection(EmailOptions.SectionName).Get<EmailOptions>()?.IsConfigured == true;

// ---- Common data folder + database provider selection ----
var dataRoot = FolderManagement.EnsureDataRoot(builder.Configuration, builder.Environment);
var postgresConnection = DatabaseSetup.ResolvePostgresConnectionString(builder.Configuration);

builder.Services.AddDbContext<AppDbContext>(options =>
{
    if (postgresConnection is not null)
    {
        options.UseNpgsql(postgresConnection);
    }
    else
    {
        // We use Microsoft.EntityFrameworkCore.Sqlite.Core (no native bundle) plus the patched
        // SourceGear.sqlite3 native binary, to avoid the vulnerable SQLitePCLRaw.lib.e_sqlite3.
        // Without an auto-initializing bundle we must register the provider ourselves.
        SQLitePCL.raw.SetProvider(new SQLitePCL.SQLite3Provider_e_sqlite3());
        options.UseSqlite(DatabaseSetup.SqliteConnectionString(dataRoot));
    }
});

// Keep Data Protection keys inside the common data folder too (cookie/token protection).
builder.Services.AddDataProtection()
    .PersistKeysToFileSystem(new DirectoryInfo(Path.Combine(dataRoot, "keys")));

// ---- Authentication (who the caller is): Identity for users/passwords, JWT bearer for requests ----
builder.Services.AddAppIdentity();
builder.Services.AddJwtBearerAuthentication(jwtOptions);

// ---- Edge protection: trust the proxy's forwarded headers, then throttle abusive callers ----
builder.Services.AddProxyForwardedHeaders(builder.Configuration);
builder.Services.AddKeepItRateLimiting();

// ---- CORS (frontend is a separate origin; cookies require AllowCredentials + explicit origins) ----
var corsOrigins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>() ?? [];
builder.Services.AddCors(options =>
    options.AddPolicy("frontend", policy => policy
        .WithOrigins(corsOrigins)
        .AllowAnyHeader()
        .AllowAnyMethod()
        .AllowCredentials()));

// ---- Outbound email (password-reset links): SMTP when configured, else logged ----
builder.Services.AddAppEmail(builder.Configuration);

// ---- App services ----
builder.Services.AddScoped<ITokenService, TokenService>();
// Central "own OR shared" note authorization + realtime recipient resolution (used by note endpoints).
builder.Services.AddScoped<keepITCore.Notes.NoteAccessService>();
builder.Services
    .AddControllers()
     .ConfigureApiBehaviorOptions(options =>
     {
         options.InvalidModelStateResponseFactory = context =>
         {
             var firstError = context.ModelState
                 .Where(kv => kv.Value?.Errors.Count > 0)
                 .SelectMany(kv => kv.Value!.Errors.Select(e => e.ErrorMessage))
                 .FirstOrDefault();

             var problem = new ValidationProblemDetails(context.ModelState)
             {
                 Title = "Validation failed",
                 Detail = firstError ?? "Please check your input and try again.",
             };
             return new BadRequestObjectResult(problem) { ContentTypes = { "application/problem+json" } };
         };
     })
    // Serialize enums (e.g. NoteType) as strings so the generated TS client gets a union of names.
    .AddJsonOptions(o => o.JsonSerializerOptions.Converters.Add(new JsonStringEnumConverter()));
builder.Services.AddOpenApi(options =>
{
    options.AddDocumentTransformer<BearerSecuritySchemeTransformer>();
    // Emit clean number schemas (drop .NET's lenient integer-or-string union) for the TS client.
    options.AddSchemaTransformer<NumericSchemaTransformer>();
    // Mark non-nullable properties required, so the generated client can actually enforce the
    // contract instead of typing every field as optional.
    options.AddSchemaTransformer<RequiredPropertiesSchemaTransformer>();
});

//Adding a service which helps in creating images
builder.Services.AddScoped<ImageService>();

// Note image attachments: bytes on disk behind a port, so S3/MinIO can replace it without
// touching a caller. Stateless, so a singleton.
builder.Services.AddSingleton<IMediaStorage, DiskMediaStorage>();
// Validates uploads, strips EXIF (phone photos carry GPS) and builds the grid thumbnail.
builder.Services.AddScoped<NoteMediaProcessor>();

//Add the SignalR Service
builder.Services.AddSignalR();
// Route Clients.User(...) by the JWT "sub" claim (our tokens don't emit NameIdentifier).
builder.Services.AddSingleton<IUserIdProvider, SubUserIdProvider>();
// Lets controllers push change signals to a user's other devices after a mutation.
builder.Services.AddSingleton<IRealtimeNotifier, RealtimeNotifier>();
// Fires due note reminders (creates the notification + realtime push).
builder.Services.AddHostedService<keepITCore.Notes.ReminderDispatcherService>();
// Daily safety net: removes media folders whose note is gone (bytes are written before the row).
builder.Services.AddHostedService<keepITCore.Notes.MediaOrphanSweepService>();

var app = builder.Build();

// ---- Database init: Postgres uses migrations; SQLite is created from the model ----
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    if (db.Database.IsNpgsql())
    {
        db.Database.Migrate();
    }
    else
    {
        // EnsureCreated builds the schema for a *new* file and does nothing at all to an existing
        // one, so an instance created before a schema change never gains the new tables and the
        // first query touching one fails with "no such table". Reconcile picks up that slack.
        db.Database.EnsureCreated();
        SqliteSchemaReconciler.Reconcile(db, app.Logger);
    }
}

app.Logger.LogInformation(
    postgresConnection is not null
        ? "Database provider: PostgreSQL"
        : "Database provider: SQLite (dev) — data folder: {DataRoot}",
    dataRoot);

// Not fatal, so an upgraded instance keeps serving notes; but until it is set, password-reset
// emails are withheld rather than built from request headers (see AuthController.ForgotPassword).
if (emailDelivered && publicBaseUrl is null)
{
    app.Logger.LogWarning(
        "Password-reset emails are switched off: SMTP is configured (Email__SmtpHost) but " +
        "App__PublicBaseUrl is not set. keepIT builds reset links only from that address, never from " +
        "the incoming request, so a forged request can't aim a genuine reset email at another site. " +
        "Set App__PublicBaseUrl to the address you open keepIT at, e.g. https://notes.example.com " +
        "(Unraid: the \"Public Base URL\" field; Docker: -e App__PublicBaseUrl=...), then restart. " +
        "The Settings page shows this too, under Email.");
}

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();                  // OpenAPI document at /openapi/v1.json
    app.MapScalarApiReference();       // interactive API UI at /scalar/v1
}

// No UseHttpsRedirection here on purpose: TLS terminates at the reverse proxy (Traefik/nginx), so
// the API always sees plain HTTP. With no HTTPS port configured the middleware would no-op anyway —
// and if one were ever configured it would redirect-loop behind the proxy (the redirect check runs
// before forwarded headers restore the original scheme). Enforce HTTPS at the proxy instead.

app.UseForwardedHeaders();
app.UseSerilogRequestLogging(); // one clean line per request (after forwarded headers, so the client IP is real)
app.UseCors("frontend");
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();

// Mapped under /api so the existing reverse-proxy + WebSocket-upgrade rules (vite dev proxy and
// nginx) route it without extra config. The browser's SignalR client connects to /api/realtime.
app.MapHub<RealTimeHub>("/api/realtime");

app.Run();
