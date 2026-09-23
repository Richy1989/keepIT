using keepITCore.Infrastructure;
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
    /// <summary>Returns the server's version (see <see cref="MetaDto.Version"/> for the format).</summary>
    /// <returns>200 with the instance metadata.</returns>
    [HttpGet]
    [AllowAnonymous]
    public ActionResult<MetaDto> Get() => Ok(new MetaDto { Version = AppVersion.Current });
}
