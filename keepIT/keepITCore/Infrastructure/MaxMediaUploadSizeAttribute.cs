using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.Options;

namespace keepITCore.Infrastructure;

/// <summary>
/// Rejects an over-sized upload with <b>413</b> before model binding reads the body.
/// <para>Without this the request still gets refused — <c>[RequestSizeLimit]</c> sees to that — but
/// the refusal surfaces as MVC's generic <em>400 "Failed to read the request form"</em>, because the
/// framework's size guard throws inside form binding and the <c>[ApiController]</c> filter turns any
/// model-state error into a validation 400. Clients map 413 to "image too large" and 400 to "not a
/// supported image", so the wrong code tells the user the wrong thing about their photo.</para>
/// <para>A resource filter runs ahead of model binding, so checking <c>Content-Length</c> here
/// answers before a single body byte is read. <c>[RequestSizeLimit]</c> stays on the action as the
/// backstop for a chunked request that arrives without a Content-Length header.</para>
/// </summary>
public sealed class MaxMediaUploadSizeAttribute : Attribute, IResourceFilter
{
    /// <summary>Headroom over the payload cap for multipart framing (boundaries, headers).</summary>
    private const long MultipartOverheadBytes = 2 * 1024 * 1024;

    /// <inheritdoc />
    public void OnResourceExecuting(ResourceExecutingContext context)
    {
        var options = context.HttpContext.RequestServices
            .GetRequiredService<IOptions<MediaOptions>>().Value;

        var length = context.HttpContext.Request.ContentLength;
        if (length is null || length <= options.MaxImageBytes + MultipartOverheadBytes) return;

        context.Result = new ObjectResult(
            $"Image too large (max {options.MaxImageBytes / (1024 * 1024)} MB).")
        {
            StatusCode = StatusCodes.Status413PayloadTooLarge,
        };
    }

    /// <inheritdoc />
    public void OnResourceExecuted(ResourceExecutedContext context)
    {
    }
}
