using Microsoft.AspNetCore.SignalR;

namespace keepITCore.SignalR
{
    /// <summary>The resource names carried by <see cref="IRealTimeHub.Changed"/>. Must match the
    /// strings the web client maps to its TanStack Query keys.</summary>
    public static class RealtimeResources
    {
        public const string Notes = "notes";
        public const string Lists = "lists";
        public const string Notification = "notification";
        public const string Settings = "settings";
    }

    /// <summary>
    /// Pushes "your data changed" signals to a user's connected devices. Controllers depend on this
    /// thin abstraction instead of <see cref="IHubContext{THub, T}"/> directly, keeping SignalR types
    /// out of the controllers and the fan-out logic in one place.
    /// </summary>
    public interface IRealtimeNotifier
    {
        /// <summary>Notifies all of <paramref name="userId"/>'s devices that the given resources changed.</summary>
        /// <param name="userId">The owning user whose devices should refetch.</param>
        /// <param name="resources">The affected resource names (see <see cref="RealtimeResources"/>).</param>
        Task NotifyAsync(Guid userId, params string[] resources);
    }

    /// <inheritdoc cref="IRealtimeNotifier"/>
    public sealed class RealtimeNotifier(IHubContext<RealTimeHub, IRealTimeHub> hub) : IRealtimeNotifier
    {
        public Task NotifyAsync(Guid userId, params string[] resources) =>
            // Clients.User routes by our SubUserIdProvider (the JWT "sub"). This reaches every device
            // the user has open — including the one that made the change; that device already updated
            // optimistically, and a redundant refetch is harmless (TanStack dedupes in-flight loads).
            hub.Clients.User(userId.ToString()).Changed(resources);
    }
}
