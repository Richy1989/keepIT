package org.hyperstarit.keepitapp.ui.settings

/** A link out of the About page (source, issues, release notes, licence, support); [id] picks its icon. */
data class AboutLink(val id: String, val label: String, val detail: String, val url: String)

/** One open-source project keepIT is built on, and what it does here. */
data class Credit(val name: String, val role: String, val license: String, val url: String)

/**
 * What the About page says. The words are the web app's: `web/src/features/about/about.json` is the
 * reference, and `AboutContentParityTest` fails when the parts both clients show — the tagline,
 * description, links, thanks, copyright and the server's credits — drift apart. [androidCredits] is
 * this app's own, as the web lists its own.
 *
 * Plain Kotlin with no Android types, so that JVM test can read it.
 */
object AboutContent {
    const val NAME = "keepIT"
    const val TAGLINE = "A modern, real-time notes app you can run yourself."
    const val COPYRIGHT = "© 2026 Richard Leopold"
    const val THANKS = "keepIT is built on the work of many open-source projects. Our sincere thanks " +
        "to everyone who creates and maintains them."

    val description = listOf(
        "keepIT is a notes app for the web and Android. Write notes and checklists, add photos and " +
            "voice notes, set reminders, and share notes with the people you choose. Every change " +
            "appears on all your devices as you make it.",
        "Your notes live on a server you run yourself, or only on your phone: the Android app also " +
            "works entirely on its own. There are no ads, no analytics and no tracking, and no one " +
            "else ever holds your data.",
        "keepIT is free and open-source software, released under the MIT License.",
    )

    val links = listOf(
        AboutLink("source", "Source code", "github.com/Richy1989/keepIT", "https://github.com/Richy1989/keepIT"),
        AboutLink("issues", "Report a problem", "Bugs and ideas, on GitHub", "https://github.com/Richy1989/keepIT/issues"),
        AboutLink("releases", "What's new", "Release notes on GitHub", "https://github.com/Richy1989/keepIT/releases"),
        AboutLink("license", "License", "MIT License", "https://github.com/Richy1989/keepIT/blob/main/LICENSE"),
        AboutLink("privacy", "Privacy policy", "How your data is handled", "https://github.com/Richy1989/keepIT/blob/main/PRIVACY.md"),
        AboutLink("support", "Support keepIT", "Buy the developer a coffee", "https://buymeacoffee.com/hyperstarit"),
    )

    val androidCredits = listOf(
        Credit("Kotlin", "Programming language, with kotlinx.serialization and kotlinx.coroutines", "Apache 2.0", "https://kotlinlang.org"),
        Credit("Jetpack Compose and Material 3", "User interface", "Apache 2.0", "https://developer.android.com/compose"),
        Credit("AndroidX", "Navigation, the home-screen widget (Glance) and background work", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
        Credit("Retrofit and OkHttp", "Networking", "Apache 2.0", "https://square.github.io/retrofit/"),
        Credit("Coil", "Image loading", "Apache 2.0", "https://coil-kt.github.io/coil/"),
        Credit("commonmark-java", "Markdown", "BSD 2-Clause", "https://github.com/commonmark/commonmark-java"),
        Credit("SignalR Java client", "Live sync", "MIT", "https://github.com/dotnet/aspnetcore"),
        Credit("Material Icons", "Icons", "Apache 2.0", "https://fonts.google.com/icons"),
    )

    val serverCredits = listOf(
        Credit("ASP.NET Core", "Web framework and live sync (SignalR)", "MIT", "https://github.com/dotnet/aspnetcore"),
        Credit("Entity Framework Core", "Database access", "MIT", "https://github.com/dotnet/efcore"),
        Credit("Npgsql", "PostgreSQL driver", "PostgreSQL License", "https://www.npgsql.org"),
        Credit("SQLite", "Built-in database", "Public domain", "https://sqlite.org"),
        Credit("SQLitePCLRaw", "SQLite for .NET", "Apache 2.0", "https://github.com/ericsink/SQLitePCL.raw"),
        Credit("ImageSharp", "Image processing", "Six Labors Split License", "https://sixlabors.com/products/imagesharp/"),
        Credit("MailKit", "Email delivery", "MIT", "https://github.com/jstedfast/MailKit"),
        Credit("QR Code Generator", "QR codes for two-factor setup", "MIT", "https://github.com/manuelbl/QrCodeGenerator"),
        Credit("Serilog", "Logging", "Apache 2.0", "https://serilog.net"),
        Credit("nginx", "Web server in the Docker image", "BSD 2-Clause", "https://nginx.org"),
    )
}
