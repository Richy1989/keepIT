using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Infrastructure.Security;
using keepITCore.Service;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc;
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

var jwtOptions = builder.Configuration.GetSection(JwtOptions.SectionName).Get<JwtOptions>() ?? 
    throw new InvalidOperationException("JWT: jwt options could not cteated. Config mighe be missing.");

if (string.IsNullOrWhiteSpace(jwtOptions.Key) || jwtOptions.Key.Length < 32)
{
    throw new InvalidOperationException(
        "Jwt:Key must be a random secret of at least 32 characters. Set Jwt__Key via the environment.");
}

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
builder.Services.AddProxyForwardedHeaders();
builder.Services.AddKeepItRateLimiting();

// ---- CORS (frontend is a separate origin; cookies require AllowCredentials + explicit origins) ----
var corsOrigins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>() ?? [];
builder.Services.AddCors(options =>
    options.AddPolicy("frontend", policy => policy
        .WithOrigins(corsOrigins)
        .AllowAnyHeader()
        .AllowAnyMethod()
        .AllowCredentials()));

// ---- App services ----
builder.Services.AddScoped<ITokenService, TokenService>();
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
});

//Adding a service which helps in creating images
builder.Services.AddScoped<ImageService>();

var app = builder.Build();

// ---- Database init: Postgres uses migrations; SQLite dev DB is created from the model ----
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    if (db.Database.IsNpgsql())
        db.Database.Migrate();
    else
        db.Database.EnsureCreated();
}

app.Logger.LogInformation(
    postgresConnection is not null
        ? "Database provider: PostgreSQL"
        : "Database provider: SQLite (dev) — data folder: {DataRoot}",
    dataRoot);

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();                  // OpenAPI document at /openapi/v1.json
    app.MapScalarApiReference();       // interactive API UI at /scalar/v1
}
else
{
    app.UseHttpsRedirection();
}

app.UseForwardedHeaders();
app.UseSerilogRequestLogging(); // one clean line per request (after forwarded headers, so the client IP is real)
app.UseCors("frontend");
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();

app.Run();
