using System.Globalization;
using System.Net;
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
    ///
    /// <para><b><see cref="ForwardedHeadersOptions.ForwardLimit"/> must equal the number of trusted
    /// proxy hops in front of the API</b> — each hop appends one <c>X-Forwarded-For</c> entry, and
    /// the middleware unwinds exactly this many from the right. Set it too low and a proxy's own IP
    /// is read as the client (so every request shares one rate-limit bucket); set it too high and a
    /// client can spoof its IP via a forged <c>X-Forwarded-For</c> (dodging the per-IP limit). The
    /// count is read from <c>App:ForwardedProxyHops</c> so it can match each deployment without a
    /// rebuild: the bare local stack (nginx only) is <c>1</c>; behind another proxy such as Traefik
    /// (Traefik → nginx → api) it's <c>2</c>. Defaults to <c>1</c>.</b></para>
    /// </summary>
    /// <param name="services">The service collection.</param>
    /// <param name="configuration">App configuration, read for <c>App:ForwardedProxyHops</c>.</param>
    public static IServiceCollection AddProxyForwardedHeaders(this IServiceCollection services, IConfiguration configuration)
    {
        var proxyHops = configuration.GetValue<int?>("App:ForwardedProxyHops") ?? 1;
        if (proxyHops < 1) proxyHops = 1;

        services.Configure<ForwardedHeadersOptions>(options =>
        {
            options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
            options.ForwardLimit = proxyHops;
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

            // Global default: sliding window of 120 req/min per IP. Covers all endpoints that
            // don't opt into a tighter named policy. Generous enough for real-time note editing
            // (bursts of saves) but stops abusive crawling or scripted enumeration.
            options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(httpContext =>
            {
                var ip = (httpContext.Connection.RemoteIpAddress ?? IPAddress.Loopback).ToString();
                return RateLimitPartition.GetSlidingWindowLimiter(ip, _ => new SlidingWindowRateLimiterOptions
                {
                    PermitLimit = 120,
                    Window = TimeSpan.FromMinutes(1),
                    SegmentsPerWindow = 6,
                });
            });

            // Tighter per-IP fixed window on auth endpoints (password guessing / signup abuse).
            options.AddPolicy(RateLimitPolicies.Auth, httpContext =>
                RateLimitPartition.GetFixedWindowLimiter(
                    partitionKey: (httpContext.Connection.RemoteIpAddress ?? IPAddress.Loopback).ToString(),
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
