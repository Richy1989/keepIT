using System.Collections.Concurrent;
using keepITCore.SignalR;

namespace keepITCore.Tests.TestHost;

/// <summary>
/// Stands in for the API's realtime notifier and keeps every push instead of sending it, so a test
/// can see which devices a change would reach and with which resource names.
/// </summary>
public sealed class CapturingRealtimeNotifier : IRealtimeNotifier
{
    /// <summary>One <c>Changed</c> push: the user whose devices it reaches, and what changed.</summary>
    public sealed record Push(Guid UserId, string[] Resources);

    public ConcurrentQueue<Push> Pushes { get; } = new();

    public Task NotifyAsync(Guid userId, params string[] resources)
    {
        Pushes.Enqueue(new Push(userId, resources));
        return Task.CompletedTask;
    }
}
