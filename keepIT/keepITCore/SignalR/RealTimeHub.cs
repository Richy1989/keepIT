using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace keepITCore.SignalR
{
    /// <summary>
    /// The strongly-typed methods the server invokes on connected clients. The browser only ever
    /// <em>receives</em> here — mutations still go through the REST controllers — so there are no
    /// callable server methods on the hub itself.
    /// </summary>
    public interface IRealTimeHub
    {
        /// <summary>
        /// Tells a user's other devices that some of their server data changed, so they refetch.
        /// Carries the affected resource names (see <see cref="RealtimeResources"/>) rather than the
        /// changed rows — TanStack Query owns the data and reloads it.
        /// </summary>
        Task Changed(IReadOnlyList<string> resources);
    }

    /// <summary>
    /// Realtime fan-out hub. Authenticated (JWT lifted from the <c>access_token</c> query string in
    /// <c>AuthenticationServiceExtensions</c> for the WebSocket); the server pushes to a user via
    /// <see cref="IRealtimeNotifier"/>, scoped to that user so devices only ever see their own data.
    /// </summary>
    [Authorize]
    public sealed class RealTimeHub : Hub<IRealTimeHub>
    {
    }
}
