using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Service;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// Voice notes: what the server accepts as audio, and what it does with it.
///
/// <para>Audio is the one attachment kind stored <b>exactly as uploaded</b> — there is no encoder
/// in the container, so the re-encode that strips an image's GPS metadata has no equivalent. That
/// makes identifying the bytes the whole of the validation, which is why most of these tests are
/// about refusing things rather than storing them.</para>
/// </summary>
public sealed class AudioProbeTests
{
    private static AudioInfo Identify(byte[] bytes) => AudioProbe.Identify(new MemoryStream(bytes));

    [Fact]
    public void An_m4a_is_recognised_with_its_duration()
    {
        var info = Identify(TestAudio.M4a(durationMs: 12_500));

        Assert.Equal(AudioFormat.M4a, info.Format);
        Assert.Equal(".m4a", info.Extension);
        Assert.Equal(12_500, info.DurationMs);
    }

    /// <summary>
    /// The reason the probe walks the box tree instead of trusting the `ftyp` brand: an MP4 with
    /// video has the same signature as one with a voice note, and a notes app is not video hosting.
    /// </summary>
    [Fact]
    public void An_mp4_with_a_video_track_is_refused()
    {
        var info = Identify(TestAudio.Mp4Video(durationMs: 5_000));

        Assert.False(info.IsAudio);
    }

    [Fact]
    public void An_mp4_with_no_sound_track_is_refused()
    {
        Assert.False(Identify(TestAudio.Mp4WithoutTracks()).IsAudio);
    }

    [Theory]
    [InlineData(AudioFormat.Ogg, ".ogg")]
    [InlineData(AudioFormat.Mp3, ".mp3")]
    [InlineData(AudioFormat.Wav, ".wav")]
    public void The_other_containers_are_recognised_by_signature(AudioFormat format, string extension)
    {
        var bytes = format switch
        {
            AudioFormat.Ogg => TestAudio.Ogg(),
            AudioFormat.Mp3 => TestAudio.Mp3(),
            _ => TestAudio.Wav(),
        };

        var info = Identify(bytes);

        Assert.Equal(format, info.Format);
        Assert.Equal(extension, info.Extension);
        // Only MPEG-4 gives up a duration cheaply; the rest show none rather than a guess.
        Assert.Null(info.DurationMs);
    }

    [Fact]
    public void An_image_is_not_audio()
    {
        Assert.False(Identify(TestImages.Png(8, 8)).IsAudio);
    }

    [Fact]
    public void Nonsense_is_not_audio()
    {
        Assert.False(Identify("this is just some text, not a recording"u8.ToArray()).IsAudio);
        Assert.False(Identify(new byte[4]).IsAudio);
        Assert.False(Identify([]).IsAudio);
    }

    /// <summary>A truncated or malformed box tree must stop the walk, not hang it.</summary>
    [Fact]
    public void A_truncated_mp4_is_refused_without_hanging()
    {
        var full = TestAudio.M4a(1_000);

        Assert.False(Identify(full[..(full.Length / 2)]).IsAudio);
        // A box claiming a size of zero used to be the classic way to make a parser spin.
        var zeroSized = new byte[] { 0, 0, 0, 0, (byte)'f', (byte)'t', (byte)'y', (byte)'p', 0, 0, 0, 0, 0, 0, 0, 0 };
        Assert.False(Identify(zeroSized).IsAudio);
    }
}

/// <summary>A voice note through the real endpoints: attach, list, play back, and the limits.</summary>
public sealed class NoteAudioTests
{
    private static async Task<string> NewNoteAsync(HttpClient client)
    {
        var response = await client.PostAsJsonAsync("/api/notes", new { type = "Text", title = "voice" });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static Task<HttpResponseMessage> UploadAsync(HttpClient client, string noteId, byte[] bytes) =>
        NoteMediaTests.UploadAsync(client, noteId, bytes, "recording.m4a", "audio/mp4");

    [Fact]
    public async Task A_recording_attaches_and_reports_its_kind_and_duration()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        var upload = await UploadAsync(client, noteId, TestAudio.M4a(durationMs: 8_250));
        Assert.Equal(HttpStatusCode.Created, upload.StatusCode);

        var created = await upload.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("Audio", created.GetProperty("kind").GetString());
        Assert.Equal(8_250, created.GetProperty("durationMs").GetInt32());
        // No pixels, because there are none — not a zero standing in for an unknown.
        Assert.Equal(0, created.GetProperty("width").GetInt32());
        Assert.Equal(0, created.GetProperty("height").GetInt32());

        var note = await client.GetFromJsonAsync<JsonElement>($"/api/notes/{noteId}");
        var media = Assert.Single(note.GetProperty("media").EnumerateArray());
        Assert.Equal("Audio", media.GetProperty("kind").GetString());
    }

    /// <summary>An image attached to the same note keeps behaving exactly as before.</summary>
    [Fact]
    public async Task Images_still_report_as_images()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        var upload = await NoteMediaTests.UploadAsync(client, noteId, TestImages.Png(20, 10), "p.png", "image/png");
        Assert.Equal(HttpStatusCode.Created, upload.StatusCode);

        var created = await upload.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("Image", created.GetProperty("kind").GetString());
        Assert.Equal(20, created.GetProperty("width").GetInt32());
        Assert.Equal(JsonValueKind.Null, created.GetProperty("durationMs").ValueKind);
    }

    /// <summary>
    /// A voice note has one rendition. A client asking for a thumbnail must get the recording, not
    /// a 404 from an empty thumbnail name.
    /// </summary>
    [Theory]
    [InlineData(null)]
    [InlineData("thumb")]
    [InlineData("preview")]
    [InlineData("full")]
    public async Task Every_size_serves_the_recording(string? size)
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);
        var bytes = TestAudio.M4a(3_000);

        var upload = await UploadAsync(client, noteId, bytes);
        var mediaId = (await upload.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;

        var url = size is null
            ? $"/api/notes/{noteId}/media/{mediaId}"
            : $"/api/notes/{noteId}/media/{mediaId}?size={size}";
        var response = await client.GetAsync(url);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("audio/mp4", response.Content.Headers.ContentType?.MediaType);
        // Byte for byte what was uploaded: audio is never re-encoded.
        Assert.Equal(bytes, await response.Content.ReadAsByteArrayAsync());
    }

    /// <summary>Seeking in a player needs range requests, which the response must advertise.</summary>
    [Fact]
    public async Task A_recording_can_be_range_requested()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        var upload = await UploadAsync(client, noteId, TestAudio.M4a(3_000));
        var mediaId = (await upload.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;

        using var request = new HttpRequestMessage(HttpMethod.Get, $"/api/notes/{noteId}/media/{mediaId}");
        request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 15);
        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal(16, (await response.Content.ReadAsByteArrayAsync()).Length);
    }

    [Fact]
    public async Task An_mp4_holding_video_is_refused_by_the_endpoint()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        // It is not audio, so it falls through to the image path and is refused as not an image.
        var response = await UploadAsync(client, noteId, TestAudio.Mp4Video(5_000));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    /// <summary>Recordings and images are capped separately, so one cannot crowd out the other.</summary>
    [Fact]
    public async Task Recordings_and_images_have_their_own_per_note_limits()
    {
        using var api = new KeepItApiFactory();
        api.Settings["App:Media:MaxAudioPerNote"] = "2";
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        for (var i = 0; i < 2; i++)
            Assert.Equal(HttpStatusCode.Created, (await UploadAsync(client, noteId, TestAudio.M4a(1_000))).StatusCode);

        var third = await UploadAsync(client, noteId, TestAudio.M4a(1_000));
        Assert.Equal(HttpStatusCode.Conflict, third.StatusCode);
        Assert.Contains("recordings", await third.Content.ReadAsStringAsync());

        // The image budget is untouched by the recordings.
        var image = await NoteMediaTests.UploadAsync(client, noteId, TestImages.Png(8, 8), "p.png", "image/png");
        Assert.Equal(HttpStatusCode.Created, image.StatusCode);
    }

    /// <summary>Deleting a note takes its recordings off disk with it.</summary>
    [Fact]
    public async Task Deleting_a_note_removes_its_recording()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        var upload = await UploadAsync(client, noteId, TestAudio.M4a(2_000));
        Assert.Equal(HttpStatusCode.Created, upload.StatusCode);

        var folder = Directory.GetDirectories(api.DataRoot, noteId, SearchOption.AllDirectories).Single();
        Assert.Single(Directory.GetFiles(folder));

        (await client.DeleteAsync($"/api/notes/{noteId}")).EnsureSuccessStatusCode();

        Assert.False(Directory.Exists(folder) && Directory.GetFiles(folder).Length > 0);
    }
}
