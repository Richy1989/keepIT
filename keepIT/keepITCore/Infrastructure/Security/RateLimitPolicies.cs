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

    /// <summary>
    /// Per-client-IP throttle on the export endpoint. Export is by far the most expensive request
    /// the app serves — it reads every note the caller owns and streams every attached image — so
    /// it gets a tighter budget than the global limiter, which is sized for bursts of small saves.
    /// </summary>
    public const string Export = "export";

    /// <summary>
    /// Per-client-IP throttle on the import endpoint. Kept separate from
    /// <see cref="Export"/> so a user who has just downloaded a backup can still upload one —
    /// a shared budget would have them wait out a limit they spent on the other half of the job.
    /// </summary>
    public const string Import = "import";
}
