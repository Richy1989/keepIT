using System.Globalization;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;

namespace keepITCore.Infrastructure.Security;

/// <summary>
/// Registers the app's edge-protection services: proxy-aware client identification (forwarded
/// headers) and per-IP request throttling (rate limiting). These two go together — the forwarded
/// headers exist precisely so the rate limiter can see the real client IP behind the reverse proxy.
/// Grouped here so Program.cs stays a high-level outline and abuse-protection tuning has one home.
/// </summary>
public static class SecurityServiceExtensions
{
    /// <summary>
    /// Trusts the reverse proxy's <c>X-Forwarded-For</c>/<c>-Proto</c> so the app sees the real
    /// client IP and scheme. The proxy isn't on a loopback network in the Docker stack, so the
    /// defaults would ignore its forwarded headers and every request would look like it came from
    /// the proxy's single IP — collapsing the per-IP rate limit into one shared bucket. Trusting the
    /// headers is safe here because the API is only reachable through the reverse proxy (it's not
    /// published to the host). If you ever expose the API directly, restrict trust to the proxy
    /// network instead of clearing it.
    /// </summary>
    public static IServiceCollection AddProxyForwardedHeaders(this IServiceCollection services)
    {
        services.Configure<ForwardedHeadersOptions>(options =>
        {
            options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
            options.KnownIPNetworks.Clear();
            options.KnownProxies.Clear();
        });

        return services;
    }

    /// <summary>
    /// Adds per-client-IP fixed-window rate limiting. The <see cref="RateLimitPolicies.Auth"/> policy
    /// throttles the auth endpoints against password guessing / signup abuse; rejected callers get a
    /// 429 with a <c>Retry-After</c> header telling them when to try again.
    /// </summary>
    public static IServiceCollection AddKeepItRateLimiting(this IServiceCollection services)
    {
        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

            // Per-client-IP fixed window, applied to /api/auth/* via [EnableRateLimiting(RateLimitPolicies.Auth)].
            options.AddPolicy(RateLimitPolicies.Auth, httpContext =>
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

        return services;
    }
}
