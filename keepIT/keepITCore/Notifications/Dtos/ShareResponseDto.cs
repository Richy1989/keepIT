namespace keepITCore.Notifications.Dtos
{
    /// <summary>The recipient's answer to a <see cref="Data.ShareInviteNotification"/>.</summary>
    public class ShareResponseDto
    {
        /// <summary>True to accept the share (grant access), false to decline it. Either way the invite is removed.</summary>
        public bool Accept { get; set; }
    }
}
