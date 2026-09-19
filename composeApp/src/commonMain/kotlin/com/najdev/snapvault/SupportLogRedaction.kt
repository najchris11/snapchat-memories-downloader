package com.najdev.snapvault

/**
 * A log line as it may be shared: pasted into an issue, a forum post, an email (D18).
 *
 * The log itself keeps the raw text — it is on the user's own screen — but what leaves the app
 * through "Copy logs" has two things taken out:
 *
 * - **Link credentials.** A Snapchat download link's query string is what authorizes fetching
 *   that user's memories. The host and path stay, because "which endpoint failed" is the
 *   useful part. Known credential parameters are also removed where they appear without a
 *   whole URL, since legacy diagnostics truncate the page text they quote.
 * - **Account names in paths.** A home-folder path is replaced with `~`, keeping everything
 *   under it.
 */
internal fun redactForSupport(line: String): String =
    line
        .replace(URL_QUERY) { "${it.groupValues[1]}?[redacted]" }
        .replace(CREDENTIAL_PARAMETER) { "${it.groupValues[1]}=[redacted]" }
        // Windows first: "C:/Users/name" also looks like a Unix home after its drive letter.
        .replace(WINDOWS_HOME) { "~" }
        .replace(UNIX_HOME) { "~" }

// Scheme, host and path, then a query/fragment that runs until whitespace or a quote.
private val URL_QUERY = Regex("""(https?://[^\s?#'"<>]+)[?#][^\s'"<>]*""")

// Only after the URL pass, so a query already reduced to "[redacted]" has nothing left to match.
private val CREDENTIAL_PARAMETER = Regex(
    """\b(uid|sid|mid|sig|signature|token|access_token|key|X-Amz-[A-Za-z-]+)=[^\s&'"<>]+""",
    RegexOption.IGNORE_CASE,
)

private val UNIX_HOME = Regex("""(?<![\w.])/(?:Users|home)/[^/\s]+""")

// A Windows account name can contain spaces, so it runs to the next separator.
private val WINDOWS_HOME = Regex("""\b[A-Za-z]:[\\/]Users[\\/][^\\/]+""", RegexOption.IGNORE_CASE)
