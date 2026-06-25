using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Service;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.IdentityModel.Tokens;
using Scalar.AspNetCore;
using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Text;
using System.Text.Json.Serialization;
using System.Threading.RateLimiting;

var builder = WebApplication.CreateBuilder(args);

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

// ---- Identity (user + password management; no cookie sign-in, JWT only) ----
builder.Services
    .AddIdentityCore<ApplicationUser>(options =>
    {
        options.User.RequireUniqueEmail = true;
        options.Password.RequiredLength = 8;
    })
    .AddRoles<IdentityRole<Guid>>()
    .AddEntityFrameworkStores<AppDbContext>()
    .AddDefaultTokenProviders();

// ---- Authentication / Authorization (JWT bearer) ----
JwtSecurityTokenHandler.DefaultMapInboundClaims = false; // keep "sub" instead of remapping it

builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.MapInboundClaims = false;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = jwtOptions.Issuer,
            ValidateAudience = true,
            ValidAudience = jwtOptions.Audience,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtOptions.Key)),
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30),
            NameClaimType = JwtRegisteredClaimNames.Sub,
        };
    });

builder.Services.AddAuthorization();

// ---- Forwarded headers (recover the real client IP/scheme behind the reverse proxy) ----
builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    // The proxy isn't on a loopback network in the Docker stack, so the defaults would ignore its
    // forwarded headers and every request would look like it came from the proxy's single IP —
    // collapsing the per-IP rate limit into one shared bucket. Trusting the headers is safe here
    // because the API is only reachable through the reverse proxy (it's not published to the host).
    // If you ever expose the API directly, restrict trust to the proxy network instead of clearing.
    options.KnownIPNetworks.Clear();
    options.KnownProxies.Clear();
});

// ---- Rate limiting (throttle auth endpoints against password guessing / signup abuse) ----
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

    // Per-client-IP fixed window, applied to /api/auth/* via [EnableRateLimiting("auth")].
    options.AddPolicy("auth", httpContext =>
        RateLimitPartition.GetFixedWindowLimiter(
            partitionKey: httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            factory: _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 10,
                Window = TimeSpan.FromMinutes(1),
            }));

    // Tell rejected clients when they can retry.
    options.OnRejected = (context, _) =>
    {
        if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retryAfter))
        {
            context.HttpContext.Response.Headers.RetryAfter =
                ((int)retryAfter.TotalSeconds).ToString(CultureInfo.InvariantCulture);
        }
        return ValueTask.CompletedTask;
    };
});

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
app.UseCors("frontend");
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();

app.Run();
