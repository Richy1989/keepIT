using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace keepITCore.Infrastructure;

/// <summary>
/// Marks non-nullable DTO properties as <c>required</c> in the OpenAPI document.
///
/// By default .NET emits every property as optional unless it carries <c>required</c> or
/// <c>[Required]</c>, so a C# <c>List&lt;ChecklistItemDto&gt; ChecklistItems { get; set; } = new()</c>
/// — which the API always populates — generates as <c>checklistItems?: …</c>. That defeats the
/// project's core contract: "change a DTO → regenerate → the TypeScript errors are the complete list
/// of call sites" (CLAUDE.md). With everything optional, <c>undefined</c> is assignable everywhere
/// and making a field nullable server-side produces no client errors at all.
///
/// Nullability is already carried faithfully in the schema (<c>string?</c> → a type union including
/// <c>null</c>), so "not nullable" is exactly the signal we want: the server guarantees the field is
/// present. See ARCHITECTURE.md (the DTO→OpenAPI→TS contract), and
/// <see cref="NumericSchemaTransformer"/> for the sibling normalization.
/// </summary>
public sealed class RequiredPropertiesSchemaTransformer : IOpenApiSchemaTransformer
{
    /// <summary>Adds every non-nullable property of an object schema to its <c>required</c> set.</summary>
    /// <param name="schema">The schema being emitted.</param>
    /// <param name="context">Transformer context (unused).</param>
    /// <param name="cancellationToken">Cancellation token (unused).</param>
    public Task TransformAsync(
        OpenApiSchema schema,
        OpenApiSchemaTransformerContext context,
        CancellationToken cancellationToken)
    {
        if (schema.Properties is null || schema.Properties.Count == 0) return Task.CompletedTask;

        schema.Required ??= new HashSet<string>();
        foreach (var (name, property) in schema.Properties)
        {
            // A nullable property is genuinely optional to the client; leave it out.
            if (property.Type is { } type && type.HasFlag(JsonSchemaType.Null)) continue;
            schema.Required.Add(name);
        }

        return Task.CompletedTask;
    }
}
