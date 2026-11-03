# Plugin catalog

`catalog.json` lists verified plugins. The Filum app reads it from this repository every time the plugin manager
opens:
- it shows these plugins under **Verified plugins**;
- it offers updates when a listed version is newer than the installed one.

## Entry

| Field | Meaning |
|---|---|
| `id` | the plugin's `@plugin` id; the app refuses a file whose header has another id |
| `name`, `description`, `author`, `homepage` | shown in the app |
| `version` | the plugin's `@version`; compared with the installed version to offer updates |
| `url` | `https://` link to the plugin file **at a fixed commit** (`raw.githubusercontent.com/<owner>/<repo>/<commit>/<file>.js`), not a branch |
| `sha256` | SHA-256 of that file; the app refuses a download with another checksum |
| `grants` | the plugin's `@grant` permissions, shown before install |

Because the URL points to a commit and the checksum is pinned, a plugin's author can't change what users install
without a new review of this file.

## Adding or updating a plugin

1. Review the plugin code at the commit you are listing.
2. Get the checksum:
   ```sh
   curl -sL <url> | sha256sum
   ```
3. Add or update the entry: `version`, `url` and `sha256` change together.
4. Open a pull request.
