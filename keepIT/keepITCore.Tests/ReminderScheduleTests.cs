using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using keepITCore.Data;
using keepITCore.Notes;
using keepITCore.Tests.TestHost;

namespace keepITCore.Tests;

/// <summary>
/// When a recurring reminder goes off next. A reminder is set for a time on someone's clock, so a
/// weekly 08:00 reminder must still go off at 08:00 after the clocks change, and a monthly one on
/// the 31st must come back to the 31st after February. The cases live in
/// <c>ReminderOccurrences.json</c>, which the Android app's tests read too: the app moves reminders
/// on by itself while offline, and if the two disagree, a reminder goes off twice.
/// </summary>
public sealed class ReminderScheduleTests
{
    private static DateTime Utc(string iso) =>
        DateTimeOffset.Parse(iso, CultureInfo.InvariantCulture).UtcDateTime;

    /// <summary>The shared cases, one theory row each, named for what they show.</summary>
    public static IEnumerable<object[]> Occurrences()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "ReminderOccurrences.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        foreach (var c in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            yield return new object[]
            {
                c.GetProperty("name").GetString()!,
                c.GetProperty("zone").GetString()!,
                c.GetProperty("recurrence").GetString()!,
                c.GetProperty("firstAtUtc").GetString()!,
                c.GetProperty("afterUtc").GetString()!,
                c.GetProperty("nextUtc").GetString()!,
            };
        }
    }

    [Theory]
    [MemberData(nameof(Occurrences))]
    public void The_next_occurrence_keeps_the_reminders_clock(
        string name, string zone, string recurrence, string firstAt, string after, string next)
    {
        var actual = ReminderSchedule.NextAfter(
            Utc(firstAt),
            TimeZoneInfo.FindSystemTimeZoneById(zone),
            Enum.Parse<ReminderRecurrence>(recurrence),
            Utc(after));

        Assert.True(Utc(next) == actual, $"{name}: expected {next}, got {actual:O}");
        Assert.Equal(DateTimeKind.Utc, actual.Kind);
    }

    [Fact]
    public void A_one_time_reminder_is_marked_fired()
    {
        var now = Utc("2026-10-10T12:00:00Z");
        var reminder = new NoteReminder { RemindAtUtc = now.AddMinutes(-1), Recurrence = ReminderRecurrence.None };

        ReminderDispatcherService.MoveOn(reminder, now);

        Assert.Equal(now, reminder.FiredAtUtc);
        Assert.Equal(now.AddMinutes(-1), reminder.RemindAtUtc);
    }

    [Fact]
    public void A_recurring_reminder_moves_on_by_its_own_clock()
    {
        var first = Utc("2026-10-19T06:00:00Z"); // Monday 08:00 in Vienna, summer time
        var reminder = new NoteReminder
        {
            RemindAtUtc = first,
            FirstAtUtc = first,
            TimeZone = "Europe/Vienna",
            Recurrence = ReminderRecurrence.Weekly,
        };

        ReminderDispatcherService.MoveOn(reminder, first.AddSeconds(20));

        Assert.Null(reminder.FiredAtUtc);
        Assert.Equal(Utc("2026-10-26T07:00:00Z"), reminder.RemindAtUtc); // 08:00, winter time
        Assert.Equal(first, reminder.FirstAtUtc);
    }

    /// <summary>
    /// A reminder from before reminders kept a zone and a first occurrence: it starts counting from
    /// the occurrence that just went off, on the server's clock.
    /// </summary>
    [Fact]
    public void A_reminder_set_before_zones_were_kept_counts_from_its_last_occurrence()
    {
        // Unspecified, as SQLite reads it back.
        var last = new DateTime(2026, 1, 31, 7, 0, 0, DateTimeKind.Unspecified);
        var reminder = new NoteReminder { RemindAtUtc = last, Recurrence = ReminderRecurrence.Monthly };
        var now = new DateTime(2026, 1, 31, 7, 0, 20, DateTimeKind.Utc);

        ReminderDispatcherService.MoveOn(reminder, now);

        Assert.Equal(DateTime.SpecifyKind(last, DateTimeKind.Utc), reminder.FirstAtUtc);
        Assert.Equal(DateTimeKind.Utc, reminder.FirstAtUtc!.Value.Kind);
        Assert.Equal(
            ReminderSchedule.NextAfter(reminder.FirstAtUtc.Value, ReminderSchedule.Default.Zone, ReminderRecurrence.Monthly, now),
            reminder.RemindAtUtc);
        Assert.Null(reminder.TimeZone);
    }

    [Fact]
    public void A_zone_this_server_does_not_know_falls_back_to_its_own()
    {
        Assert.Null(ReminderSchedule.KnownZoneId("Mars/Olympus_Mons"));
        Assert.Null(ReminderSchedule.KnownZoneId(""));
        Assert.Equal("Europe/Vienna", ReminderSchedule.KnownZoneId("Europe/Vienna"));

        Assert.Equal(ReminderSchedule.Default.Id, ReminderSchedule.ZoneIdOf(null));
        Assert.Equal(ReminderSchedule.Default.Id, ReminderSchedule.ZoneIdOf("Mars/Olympus_Mons"));
        Assert.Same(ReminderSchedule.Default.Zone, ReminderSchedule.ZoneOf(null));
    }

    /// <summary>
    /// The clients are told the default zone by id and look it up themselves (java.time, Intl), so it
    /// must be an id they know, never a Windows name such as "W. Europe Standard Time".
    /// </summary>
    [Fact]
    public void The_servers_own_zone_is_named_by_an_IANA_id()
    {
        var id = ReminderSchedule.Default.Id;
        Assert.True(id == "UTC" || id.Contains('/'), $"not an IANA id: {id}");
        Assert.True(TimeZoneInfo.TryFindSystemTimeZoneById(id, out _), $"can't look up {id}");
    }

    // ---- through the API ----

    private static async Task<string> NewNoteAsync(HttpClient client)
    {
        var response = await client.PostAsJsonAsync("/api/notes", new { type = "Text", title = "standup" });
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
    }

    private static async Task<JsonElement> SetReminderAsync(HttpClient client, string noteId, object body)
    {
        var response = await client.PutAsJsonAsync($"/api/notes/{noteId}/reminder", body);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    [Fact]
    public async Task A_reminder_keeps_the_zone_and_the_time_it_was_set_for()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        var remindAt = DateTime.UtcNow.AddDays(30);
        var note = await SetReminderAsync(client, noteId,
            new { remindAtUtc = remindAt, recurrence = "Weekly", timeZone = "Europe/Vienna" });

        Assert.Equal("Europe/Vienna", note.GetProperty("reminderTimeZone").GetString());
        Assert.Equal(remindAt, note.GetProperty("reminderFirstAtUtc").GetDateTime(), TimeSpan.FromSeconds(1));

        // And a read sees what the write returned.
        var read = await client.GetFromJsonAsync<JsonElement>($"/api/notes/{noteId}");
        Assert.Equal("Europe/Vienna", read.GetProperty("reminderTimeZone").GetString());
    }

    /// <summary>
    /// An older client sends no zone, and a newer one may send one this server's time zone data
    /// doesn't have. Neither is refused (an outbox can only drop what the server turns down): the
    /// reminder runs on the server's clock, and says so.
    /// </summary>
    [Fact]
    public async Task A_reminder_without_a_zone_the_server_knows_reports_the_servers_own()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);
        var remindAt = DateTime.UtcNow.AddDays(30);

        var none = await SetReminderAsync(client, noteId, new { remindAtUtc = remindAt, recurrence = "Daily" });
        Assert.Equal(ReminderSchedule.Default.Id, none.GetProperty("reminderTimeZone").GetString());

        var unknown = await SetReminderAsync(client, noteId,
            new { remindAtUtc = remindAt, recurrence = "Daily", timeZone = "Mars/Olympus_Mons" });
        Assert.Equal(ReminderSchedule.Default.Id, unknown.GetProperty("reminderTimeZone").GetString());
    }

    /// <summary>
    /// A device replaying a reminder it has already moved on sends where the series started, so a
    /// monthly reminder on the 31st carries on from the 31st. A start after the occurrence being set
    /// makes no series, and is ignored.
    /// </summary>
    [Fact]
    public async Task A_series_can_start_before_the_occurrence_being_set_but_not_after_it()
    {
        using var api = new KeepItApiFactory();
        using var client = await api.CreateSignedInClientAsync();
        var noteId = await NewNoteAsync(client);

        var remindAt = DateTime.UtcNow.AddDays(30);
        var earlier = remindAt.AddDays(-61);
        var kept = await SetReminderAsync(client, noteId,
            new { remindAtUtc = remindAt, recurrence = "Monthly", timeZone = "UTC", firstAtUtc = earlier });
        Assert.Equal(earlier, kept.GetProperty("reminderFirstAtUtc").GetDateTime(), TimeSpan.FromSeconds(1));

        var ignored = await SetReminderAsync(client, noteId,
            new { remindAtUtc = remindAt, recurrence = "Monthly", timeZone = "UTC", firstAtUtc = remindAt.AddDays(1) });
        Assert.Equal(remindAt, ignored.GetProperty("reminderFirstAtUtc").GetDateTime(), TimeSpan.FromSeconds(1));
    }
}
