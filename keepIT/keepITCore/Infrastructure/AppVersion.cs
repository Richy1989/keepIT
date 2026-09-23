using System.Reflection;

namespace keepITCore.Infrastructure;

/// <summary>
/// The running build's version string, resolved once — the assembly can't change while the process
/// runs. Shared by <c>MetaController</c> (which serves it to sign-in screens) and the export
/// archive (which stamps it, so a failed import can be traced to the server that wrote the file).
/// </summary>
public static class AppVersion
{
    /// <summary>
    /// The release tag (injected by the Docker build via <c>/p:Version</c>) plus a <c>+sha</c>
    /// suffix — appended automatically by the SDK's SourceLink for local git builds, or passed in
    /// by the release workflow. A full 40-char sha is clipped to 7 for display. E.g.
    /// <c>0.7.6+ab12cd3</c>, or <c>unknown</c> when the attribute is missing.
    /// </summary>
    public static string Current { get; } = Resolve();

    private static string Resolve()
    {
        var info = Assembly.GetEntryAssembly()
            ?.GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion;
        if (string.IsNullOrWhiteSpace(info)) return "unknown";

        var plus = info.IndexOf('+');
        if (plus < 0 || info.Length - plus - 1 <= 7) return info;
        return info[..(plus + 1 + 7)];
    }
}
