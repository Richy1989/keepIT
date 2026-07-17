namespace keepITCore.Meta.Dtos;

/// <summary>Public facts about this server instance (no auth required, nothing sensitive).</summary>
public class MetaDto
{
    /// <summary>
    /// The server's version, e.g. <c>0.2.0+ab12cd3</c> — the release tag plus the build's commit
    /// sha, or <c>0.0.0-dev+…</c> for a local development build.
    /// </summary>
    public string Version { get; set; } = "";
}
