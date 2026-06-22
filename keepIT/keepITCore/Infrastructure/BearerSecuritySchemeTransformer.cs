using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace keepITCore.Infrastructure;

/// <summary>
/// Declares the JWT "Bearer" security scheme on the generated OpenAPI document so API UIs
/// (Scalar, Swagger UI, …) show a token input and attach an "Authorization: Bearer …" header.
/// The built-in OpenAPI generator does not infer this from the JWT auth setup, so we add it here.
/// </summary>
public sealed class BearerSecuritySchemeTransformer : IOpenApiDocumentTransformer
{
    /// <summary>Adds the Bearer scheme to the document's components.</summary>
    /// <param name="document">The OpenAPI document being generated.</param>
    /// <param name="context">Transformer context (unused).</param>
    /// <param name="cancellationToken">Cancellation token (unused).</param>
    public Task TransformAsync(
        OpenApiDocument document,
        OpenApiDocumentTransformerContext context,
        CancellationToken cancellationToken)
    {
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes ??= new Dictionary<string, IOpenApiSecurityScheme>();
        document.Components.SecuritySchemes["Bearer"] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.Http,
            Scheme = "bearer",
            BearerFormat = "JWT",
            In = ParameterLocation.Header,
            Description = "Paste the access token from POST /api/auth/login or /api/auth/register.",
        };
        return Task.CompletedTask;
    }
}
