using System.Reflection;
using keepITCore.Meta.Dtos;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace keepITCore.Meta;

/// <summary>
/// Public instance metadata. Anonymous by design: the version is not a secret on a self-hosted
/// notes app, and the sign-in screens (web and Android) want to show it before any session exists.
/// </summary>
[ApiController]
[Route("api/meta")]
public class MetaController : ControllerBase
{
    /// <summary>Resolved once — the assembly can't change while the process runs.</summary>
    private static readonly string Version = ResolveVersion();

    /// <summary>Returns the server's version (see <see cref="MetaDto.Version"/> for the format).</summary>
    /// <returns>200 with the instance metadata.</returns>
    [HttpGet]
    [AllowAnonymous]
    public ActionResult<MetaDto> Get() => Ok(new MetaDto { Version = Version });

    /// <summary>
    /// Reads the assembly's informational version, which carries the release tag (injected by the
    /// Docker build via <c>/p:Version</c>) plus a <c>+sha</c> suffix — appended automatically by
    /// the SDK's SourceLink for local git builds, or passed in by the release workflow. A full
    /// 40-char sha is clipped to 7 for display.
    /// </summary>
    private static string ResolveVersion()
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
