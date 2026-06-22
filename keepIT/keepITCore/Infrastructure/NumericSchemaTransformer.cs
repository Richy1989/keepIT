using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace keepITCore.Infrastructure;

/// <summary>
/// Normalizes numeric schemas in the OpenAPI document. By default .NET advertises integers/numbers
/// as <c>type: ["integer","string"]</c> (with a string pattern) because System.Text.Json can parse
/// numbers leniently from strings. Our API only ever returns real JSON numbers, and that union makes
/// the generated TypeScript client type every number as <c>number | string</c>. This drops the
/// spurious <c>"string"</c> so the typed client gets clean <c>number</c>s. See ARCHITECTURE.md
/// (the DTO→OpenAPI→TS contract).
/// </summary>
public sealed class NumericSchemaTransformer : IOpenApiSchemaTransformer
{
    /// <summary>Strips the string alternative (and its numeric pattern) from integer/number schemas.</summary>
    /// <param name="schema">The schema being emitted.</param>
    /// <param name="context">Transformer context (unused).</param>
    /// <param name="cancellationToken">Cancellation token (unused).</param>
    public Task TransformAsync(
        OpenApiSchema schema,
        OpenApiSchemaTransformerContext context,
        CancellationToken cancellationToken)
    {
        if (schema.Type is { } type
            && (type.HasFlag(JsonSchemaType.Integer) || type.HasFlag(JsonSchemaType.Number))
            && type.HasFlag(JsonSchemaType.String))
        {
            schema.Type = type & ~JsonSchemaType.String;
            schema.Pattern = null;
        }

        return Task.CompletedTask;
    }
}
