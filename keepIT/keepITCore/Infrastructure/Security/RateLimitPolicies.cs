namespace keepITCore.Infrastructure.Security;

/// <summary>
/// Names of the registered rate-limit policies. Shared between registration
/// (<see cref="SecurityServiceExtensions.AddKeepItRateLimiting"/>) and the controllers that opt in
/// via <c>[EnableRateLimiting(...)]</c>, so the policy name is defined in exactly one place.
/// </summary>
public static class RateLimitPolicies
{
    /// <summary>Per-client-IP throttle on the auth endpoints (password guessing / signup abuse).</summary>
    public const string Auth = "auth";
}
