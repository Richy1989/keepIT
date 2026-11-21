using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using keepITCore.Auth;

namespace keepITCore.Infrastructure.Security;

/// <summary>
/// Registers the app's edge-protection services: proxy-aware client identification (forwarded
/// headers) and request throttling (rate limiting: per account when signed in, per IP otherwise).
/// These two go together — the forwarded headers exist so the rate limiter can see the real client
/// IP behind the reverse proxy.
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
    /// is read as the client (so every request without a session, sign-in included, shares one
    /// rate-limit bucket); set it too high and a
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
    /// Adds rate limiting. The global limiter counts a signed-in caller per account and everyone
    /// else per client IP; the named policies — <see cref="RateLimitPolicies.Auth"/> against
    /// password guessing / signup abuse, <see cref="RateLimitPolicies.Export"/> and
    /// <see cref="RateLimitPolicies.Import"/> for the two requests that touch a whole account —
    /// stay per IP. Rejected callers get a 429 with a <c>Retry-After</c> header telling them when to
    /// try again, and the operator gets a log line naming who was refused.
    ///
    /// <para><b>Why per account.</b> Counting everything per IP made ordinary use hit the limit: a
    /// household shares one address, so every phone, web tab and widget behind it shared one budget,
    /// and behind a reverse proxy with <c>App:ForwardedProxyHops</c> set too low every user of the
    /// server did. A signed-in caller has proved who they are, so their account is the fair unit.
    /// This needs <c>UseAuthentication</c> to run before <c>UseRateLimiter</c> (see Program.cs).</para>
    ///
    /// <para>Both budgets can be changed without a rebuild: <c>App:RateLimit:PerUserPerMinute</c>
    /// (default 600) and <c>App:RateLimit:PerIpPerMinute</c> (default 120).</para>
    /// </summary>
    /// <param name="services">The service collection.</param>
    /// <param name="configuration">App configuration, read for <c>App:RateLimit:*</c>.</param>
    public static IServiceCollection AddKeepItRateLimiting(this IServiceCollection services, IConfiguration configuration)
    {
        var perUser = Math.Max(1, configuration.GetValue<int?>("App:RateLimit:PerUserPerMinute") ?? 600);
        var perIp = Math.Max(1, configuration.GetValue<int?>("App:RateLimit:PerIpPerMinute") ?? 120);
        var rejections = new RejectionLog();

        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

            // Global default, a sliding window over a minute, for every endpoint whether or not it
            // also opts into a named policy. Per account it is generous enough for several devices
            // syncing and a fresh tab loading every thumbnail; per IP it still stops crawling and
            // scripted enumeration by callers without a session.
            options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(httpContext =>
            {
                var userId = SignedInUserId(httpContext);
                var (key, limit) = userId is { } id
                    ? ("user:" + id.ToString("N"), perUser)
                    : ("ip:" + ClientIp(httpContext), perIp);
                return RateLimitPartition.GetSlidingWindowLimiter(key, _ => new SlidingWindowRateLimiterOptions
                {
                    PermitLimit = limit,
                    Window = GlobalWindow,
                    SegmentsPerWindow = GlobalSegments,
                });
            });

            // Tighter per-IP fixed window on auth endpoints (password guessing / signup abuse).
            options.AddPolicy(RateLimitPolicies.Auth, httpContext =>
                RateLimitPartition.GetFixedWindowLimiter(
                    partitionKey: ClientIp(httpContext),
                    factory: _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 10,
                        Window = TimeSpan.FromMinutes(1),
                    }));

            // Export reads the caller's whole account and streams every image, so it is throttled
            // well below the global budget. Per IP, like the auth policy: a budget this small is
            // meant to stop a loop or a script, and the address is what such a caller cannot change
            // by signing up again.
            options.AddPolicy(RateLimitPolicies.Export, httpContext =>
                RateLimitPartition.GetFixedWindowLimiter(
                    partitionKey: ClientIp(httpContext),
                    factory: _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 5,
                        Window = TimeSpan.FromMinutes(5),
                    }));

            // Import decodes and re-encodes every image in the archive, which is heavier still than
            // export, but it is a one-off action — a handful per window is plenty.
            options.AddPolicy(RateLimitPolicies.Import, httpContext =>
                RateLimitPartition.GetFixedWindowLimiter(
                    partitionKey: ClientIp(httpContext),
                    factory: _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 5,
                        Window = TimeSpan.FromMinutes(5),
                    }));

            // Tell rejected clients when they can retry, and the operator who was refused.
            options.OnRejected = (context, _) =>
            {
                var httpContext = context.HttpContext;
                // The fixed windows say how long is left; the sliding window says nothing, and the
                // earliest anything comes back there is when its oldest segment slides out.
                var retryAfter = context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var after)
                    ? after
                    : GlobalWindow / GlobalSegments;
                httpContext.Response.Headers.RetryAfter =
                    ((int)Math.Ceiling(retryAfter.TotalSeconds)).ToString(CultureInfo.InvariantCulture);
                rejections.Report(httpContext, retryAfter);
                return ValueTask.CompletedTask;
            };
        });

        return services;
    }

    /// <summary>The global limiter's window, and the segments it slides in.</summary>
    private static readonly TimeSpan GlobalWindow = TimeSpan.FromMinutes(1);
    private const int GlobalSegments = 6;

    /// <summary>The signed-in caller's id, or null for a request without a valid session.</summary>
    private static Guid? SignedInUserId(HttpContext httpContext) =>
        httpContext.User.Identity?.IsAuthenticated == true ? httpContext.User.GetUserId() : null;

    /// <summary>The client's address as the forwarded headers left it (loopback when there is none).</summary>
    private static string ClientIp(HttpContext httpContext) =>
        (httpContext.Connection.RemoteIpAddress ?? IPAddress.Loopback).ToString();

    /// <summary>
    /// Writes one warning per refused caller and limit, at most once a minute each, so an operator
    /// can see who is being turned away — and, when it is a private address on every line, that it
    /// is probably their own reverse proxy and <c>App:ForwardedProxyHops</c> is too low. The
    /// throttle's memory is bounded: past <see cref="MaxTracked"/> entries the stale ones go, so a
    /// flood of forged addresses cannot grow it without limit.
    /// </summary>
    private sealed class RejectionLog
    {
        private const int MaxTracked = 1000;
        private static readonly TimeSpan Quiet = TimeSpan.FromMinutes(1);
        private readonly ConcurrentDictionary<string, long> _lastLogged = new();

        public void Report(HttpContext httpContext, TimeSpan retryAfter)
        {
            var policy = httpContext.GetEndpoint()?.Metadata.GetMetadata<EnableRateLimitingAttribute>()?.PolicyName;
            var userId = SignedInUserId(httpContext);
            var who = userId is { } id ? "user " + id.ToString("N")[..8] : ClientIp(httpContext);
            var key = who + "|" + policy;

            var now = Environment.TickCount64;
            var last = _lastLogged.GetOrAdd(key, long.MinValue);
            if (last != long.MinValue && now - last < Quiet.TotalMilliseconds) return;
            if (!_lastLogged.TryUpdate(key, now, last)) return; // another request just logged it
            if (_lastLogged.Count > MaxTracked) Sweep(now);

            var logger = httpContext.RequestServices.GetRequiredService<ILoggerFactory>()
                .CreateLogger("keepITCore.RateLimit");
            var wait = $"{Math.Ceiling(retryAfter.TotalSeconds):0} s";
            var limit = policy is null ? "the request limit" : $"the {policy} limit";
            var hint = userId is null && IsPrivate(httpContext.Connection.RemoteIpAddress)
                ? " If that is your reverse proxy's address, set App__ForwardedProxyHops to the number of proxies in front of keepIT."
                : "";
            logger.LogWarning("Rate limit: {Who} reached {Limit}; refused for {Wait}.{Hint}", who, limit, wait, hint);
        }

        private void Sweep(long now)
        {
            foreach (var (key, at) in _lastLogged)
                if (now - at >= Quiet.TotalMilliseconds) _lastLogged.TryRemove(key, out _);
        }

        /// <summary>Loopback, RFC 1918, link-local, or an IPv6 unique-local address.</summary>
        private static bool IsPrivate(IPAddress? address)
        {
            if (address is null) return false;
            if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
            if (IPAddress.IsLoopback(address)) return true;
            if (address.AddressFamily == AddressFamily.InterNetwork)
            {
                var b = address.GetAddressBytes();
                return b[0] == 10
                    || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                    || (b[0] == 192 && b[1] == 168)
                    || (b[0] == 169 && b[1] == 254);
            }
            return address.IsIPv6LinkLocal || address.IsIPv6UniqueLocal;
        }
    }
}
