using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using keepITCore.Notes;
using keepITCore.Notes.Dtos;

namespace keepITCore.Tests;

/// <summary>
/// The server's checklist merge (<see cref="ChecklistMerge"/>) against the cases in
/// <c>web/src/features/notes/checklistMerge.json</c>, which the web's and the Android app's copies of
/// the merge run too: a save merges the same on the server as in either app's cache. The cases name
/// rows ("milk"); each name stands for one Guid here.
/// </summary>
public sealed class ChecklistMergeCasesTests
{
    public static TheoryData<string> Cases()
    {
        var data = new TheoryData<string>();
        foreach (var c in Load()) data.Add(c.GetProperty("name").GetString()!);
        return data;
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public void A_save_merges_as_it_does_in_the_apps(string name)
    {
        var c = Load().Single(x => x.GetProperty("name").GetString() == name);

        var merged = ChecklistMerge.Merge(Rows(c, "base"), Rows(c, "edit"), Rows(c, "current"));

        Assert.Equal(Shape(Rows(c, "merged")), Shape(merged));
    }

    private static List<ChecklistItemDto> Rows(JsonElement c, string key) => c.GetProperty(key)
        .EnumerateArray()
        .Select((r, order) => new ChecklistItemDto
        {
            Id = r.GetProperty("id").ValueKind == JsonValueKind.Null ? null : IdOf(r.GetProperty("id").GetString()!),
            Text = r.GetProperty("text").GetString()!,
            IsChecked = r.TryGetProperty("isChecked", out var ticked) && ticked.GetBoolean(),
            Indent = r.TryGetProperty("indent", out var indent) ? indent.GetInt32() : 0,
            Order = order,
        })
        .ToList();

    private static List<(Guid?, string, bool, int)> Shape(IEnumerable<ChecklistItemDto> rows) =>
        rows.Select(r => (r.Id, r.Text, r.IsChecked, r.Indent ?? 0)).ToList();

    /** The same Guid for the same name, every run. */
    private static Guid IdOf(string name) => new(MD5.HashData(Encoding.UTF8.GetBytes(name)));

    private static List<JsonElement> Load()
    {
        // The tests run from bin/<config>/<tfm>; the cases live with the web app in the checkout.
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var file = Path.Combine(dir.FullName, "web", "src", "features", "notes", "checklistMerge.json");
            if (File.Exists(file))
                return JsonDocument.Parse(File.ReadAllText(file)).RootElement.EnumerateArray().ToList();
        }
        throw new FileNotFoundException("checklistMerge.json not found above " + AppContext.BaseDirectory + " - run from the repository checkout");
    }
}
