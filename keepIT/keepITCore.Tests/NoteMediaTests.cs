using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>One API host and one signed-in user, shared by the media tests.</summary>
public sealed class MediaHost : IAsyncLifetime
{
    public KeepItApiFactory Api { get; } = new();
    public HttpClient Client { get; private set; } = null!;

    public async Task InitializeAsync() => Client = await Api.CreateSignedInClientAsync();

    public Task DisposeAsync()
    {
        Client.Dispose();
        Api.Dispose();
        return Task.CompletedTask;
    }
}

/// <summary>
/// Note images through the real endpoints: the three renditions, the lazily built preview, and the
/// limits that refuse an upload.
/// </summary>
public sealed class NoteMediaTests(MediaHost host) : IClassFixture<MediaHost>
{
    private HttpClient Client => host.Client;

    private async Task<string> NewNoteAsync()
    {
        var response = await Client.PostAsJsonAsync("/api/notes", new { type = "Text", title = "images" });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private async Task<HttpResponseMessage> UploadAsync(string noteId, byte[] bytes, string fileName, string contentType)
    {
        using var form = new MultipartFormDataContent();
        var file = new ByteArrayContent(bytes);
        file.Headers.ContentType = new MediaTypeHeaderValue(contentType);
        form.Add(file, "file", fileName);
        return await Client.PostAsync($"/api/notes/{noteId}/media", form);
    }

    /// <summary>Uploads an image that must be accepted, returning its media id.</summary>
    private async Task<string> AttachAsync(string noteId, byte[] bytes, string fileName, string contentType)
    {
        var response = await UploadAsync(noteId, bytes, fileName, contentType);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private async Task<HttpResponseMessage> GetAsync(string noteId, string mediaId, string size)
    {
        var response = await Client.GetAsync($"/api/notes/{noteId}/media/{mediaId}?size={size}");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return response;
    }

    /// <summary>The stored files belonging to one image, by the suffix after its id (".jpg", "_thumb.jpg" …).</summary>
    private string[] StoredFiles(string noteId, string mediaId)
    {
        var folder = Directory.GetDirectories(host.Api.DataRoot, noteId, SearchOption.AllDirectories).Single();
        var stem = Guid.Parse(mediaId).ToString("N");
        return Directory.GetFiles(folder)
            .Select(Path.GetFileName)
            .Where(name => name!.StartsWith(stem, StringComparison.Ordinal))
            .Select(name => name![stem.Length..])
            .Order(StringComparer.Ordinal)
            .ToArray();
    }

    private string PreviewPath(string noteId, string mediaId) =>
        Directory.GetFiles(
                Directory.GetDirectories(host.Api.DataRoot, noteId, SearchOption.AllDirectories).Single())
            .Single(path => Path.GetFileName(path) == $"{Guid.Parse(mediaId):N}_preview.jpg");

    [Fact]
    public async Task A_large_image_is_served_at_three_sizes()
    {
        var noteId = await NewNoteAsync();
        var mediaId = await AttachAsync(noteId, TestImages.Png(3000, 2000), "big.png", "image/png");

        var thumb = await (await GetAsync(noteId, mediaId, "thumb")).Content.ReadAsByteArrayAsync();
        var preview = await (await GetAsync(noteId, mediaId, "preview")).Content.ReadAsByteArrayAsync();
        var full = await (await GetAsync(noteId, mediaId, "full")).Content.ReadAsByteArrayAsync();

        Assert.Equal((400, 267), TestImages.SizeOf(thumb));
        Assert.Equal((1280, 854), TestImages.SizeOf(preview));
        Assert.Equal((2560, 1707), TestImages.SizeOf(full));
    }

    [Fact]
    public async Task The_preview_is_built_once_then_served_from_disk()
    {
        var noteId = await NewNoteAsync();
        var mediaId = await AttachAsync(noteId, TestImages.Png(3000, 2000), "big.png", "image/png");
        Assert.DoesNotContain("_preview.jpg", StoredFiles(noteId, mediaId)); // lazily, on first request

        var first = await (await GetAsync(noteId, mediaId, "preview")).Content.ReadAsByteArrayAsync();
        var written = File.GetLastWriteTimeUtc(PreviewPath(noteId, mediaId));
        var second = await (await GetAsync(noteId, mediaId, "preview")).Content.ReadAsByteArrayAsync();

        Assert.Equal(first, second);
        Assert.Equal(written, File.GetLastWriteTimeUtc(PreviewPath(noteId, mediaId)));
        // Exactly these three: a leftover "…_preview.jpg.<guid>.tmp" would mean a non-atomic write.
        Assert.Equal([".jpg", "_preview.jpg", "_thumb.jpg"], StoredFiles(noteId, mediaId));
    }

    [Fact]
    public async Task A_small_image_is_its_own_preview()
    {
        var noteId = await NewNoteAsync();
        var mediaId = await AttachAsync(noteId, TestImages.Png(800, 600), "small.png", "image/png");

        var preview = await (await GetAsync(noteId, mediaId, "preview")).Content.ReadAsByteArrayAsync();
        var full = await (await GetAsync(noteId, mediaId, "full")).Content.ReadAsByteArrayAsync();

        Assert.Equal(full, preview);
        Assert.Equal([".jpg", "_thumb.jpg"], StoredFiles(noteId, mediaId));
    }

    [Fact]
    public async Task A_gif_is_served_as_stored_for_its_preview_so_animation_survives()
    {
        var noteId = await NewNoteAsync();
        var mediaId = await AttachAsync(noteId, TestImages.Gif(1600, 1200), "anim.gif", "image/gif");

        var preview = await GetAsync(noteId, mediaId, "preview");

        Assert.Equal("image/gif", preview.Content.Headers.ContentType?.MediaType);
        Assert.Equal([".gif", "_thumb.jpg"], StoredFiles(noteId, mediaId));
    }

    [Fact]
    public async Task Deleting_an_image_removes_its_original_thumbnail_and_preview()
    {
        var noteId = await NewNoteAsync();
        var mediaId = await AttachAsync(noteId, TestImages.Png(3000, 2000), "big.png", "image/png");
        await GetAsync(noteId, mediaId, "preview");
        Assert.Equal([".jpg", "_preview.jpg", "_thumb.jpg"], StoredFiles(noteId, mediaId));

        var deleted = await Client.DeleteAsync($"/api/notes/{noteId}/media/{mediaId}");

        Assert.Equal(HttpStatusCode.NoContent, deleted.StatusCode);
        Assert.Empty(StoredFiles(noteId, mediaId));
    }

    [Fact]
    public async Task An_image_over_10_MB_is_refused_with_413()
    {
        var noteId = await NewNoteAsync();

        var response = await UploadAsync(noteId, new byte[11 * 1024 * 1024], "huge.jpg", "image/jpeg");

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
    }

    [Fact]
    public async Task A_heic_photo_is_refused_by_name()
    {
        var noteId = await NewNoteAsync();

        var response = await UploadAsync(noteId, TestImages.HeicHeader(), "photo.heic", "image/heic");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains("HEIC", await response.Content.ReadAsStringAsync());
    }
}
