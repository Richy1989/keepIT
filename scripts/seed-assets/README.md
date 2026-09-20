# Seed images

Sample photos the seed scripts (`../seed-dev-data.ps1` / `../seed-dev-data.sh`) attach to notes, so a
freshly seeded dev instance exercises image attachments — and so screenshots show them.

Each file is downscaled to a 2560 px long edge and stripped of metadata, which is exactly what
`NoteMediaProcessor` does to an upload anyway; keeping the originals would only make the repo heavier
for bytes the server throws away.

| File | Source |
|------|--------|
| `aperitivo-terrace.jpg` | Project photo |
| `coastal-town.jpg` | Nenad Radojčić on [Unsplash](https://unsplash.com/photos/kIQoxt8-srU) |
| `hummingbird.jpg` | Kyle Doerksen on [Unsplash](https://unsplash.com/photos/GPvRvTwXUcI) |
| `museum-sculpture.jpg` | Tamara Harhai on [Unsplash](https://unsplash.com/photos/9Oe0w7BI7Is) |

The Unsplash photos are used under the [Unsplash License](https://unsplash.com/license).
