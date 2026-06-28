using keepITCore.Auth;
using Microsoft.AspNetCore.SignalR;

namespace keepITCore.SignalR
{
    /// <summary>
    /// Tells SignalR which user a connection belongs to. The default provider reads
    /// <c>ClaimTypes.NameIdentifier</c>, but our JWTs carry the id in the un-remapped <c>sub</c>
    /// claim — so without this, <c>Clients.User(...)</c> would never match a connection.
    /// </summary>
    public sealed class SubUserIdProvider : IUserIdProvider
    {
        public string? GetUserId(HubConnectionContext connection) =>
            connection.User?.GetUserId()?.ToString();
    }
}
