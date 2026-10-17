using keepITCore.Data;

namespace keepITCore.Notes;

/// <summary>
/// The note a new account starts with: a hello, a few things to try, and where to ask when
/// something is unclear — so the first thing a new user sees is not an empty grid. It is an
/// ordinary note the user owns, to edit or delete like any other. Registration adds it unless
/// <c>App:WelcomeNote</c> is false.
/// <para>
/// The body is Markdown, but kept to plain paragraphs and dashes: the clients render it on the
/// card, yet open a note the user can edit as its source, which is where the list of things to
/// try is read. The help link comes second and bare, so the card shows it as a link to tap. Each
/// paragraph and list item is one line: both renderers turn a single newline into a line break,
/// so a wrapped sentence would show wrapped.
/// </para>
/// </summary>
public static class WelcomeNote
{
    /// <summary>The configuration key that switches the welcome note off (default: on).</summary>
    public const string SettingKey = "App:WelcomeNote";

    /// <summary>The note's colour: <c>sky</c>, the palette's blue.</summary>
    public const string Color = "sky";

    public const string Title = "Welcome to keepIT 👋";

    public static readonly string Body = """
        Hi, and welcome! We're really glad you're here. keepIT holds your notes, lists and ideas, and keeps them in sync on every device you sign in on.

        Questions, ideas, or something not working? We're on GitHub, and we'd love to hear from you: https://github.com/Richy1989/keepIT/issues

        A few things to try:

        - Write a note, and format it with the toolbar: bold, headings, lists and links.
        - Make a checklist for shopping or packing, and tick things off as you go.
        - Add photos to a note, and give it a colour so it's easy to spot.
        - Pin the notes you need most, and archive the ones you're done with.
        - Sort notes into lists from the menu: home, work, holidays, whatever suits you.
        - Set a reminder on a note, once or repeating, and keepIT tells you when it's time.
        - Share a note with someone who has an account here, to read it or to edit it together.
        - On Android, record voice notes, put the keepIT widget on your home screen, and share text from other apps straight into a new note. It works offline, too.
        - Make it yours with a light or dark theme, and export all your notes from Settings whenever you like.

        This note is yours to keep, change or delete. Happy note-taking! 💙
        """.ReplaceLineEndings("\n");

    /// <summary>A new welcome note owned by <paramref name="ownerId"/>, ready to add to the context.</summary>
    public static Note For(Guid ownerId, DateTime nowUtc)
    {
        var note = new Note
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId,
            Type = NoteType.Text,
            Title = Title,
            Body = Body,
            Color = Color,
            CreatedAtUtc = nowUtc,
            UpdatedAtUtc = nowUtc,
        };
        // The owner's private view row — its existence puts the note in their grid.
        note.UserStates.Add(new NoteUserState { NoteId = note.Id, UserId = ownerId });
        return note;
    }
}
