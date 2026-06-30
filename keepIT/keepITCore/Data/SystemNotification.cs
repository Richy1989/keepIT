namespace keepITCore.Data
{
    /// <summary>
    /// A plain informational notification — just <see cref="UserNotification.NotificationText"/> and a
    /// severity, with no interactive actions. This is what the create endpoint produces directly.
    /// </summary>
    public sealed class SystemNotification : UserNotification
    {
    }
}
