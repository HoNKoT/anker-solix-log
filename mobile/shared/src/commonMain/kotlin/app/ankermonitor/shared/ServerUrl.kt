package app.ankermonitor.shared

const val DEFAULT_PORT = 19877

/**
 * 入力された接続先を URL にする。`amon remote` (cmd_remote) と同じ補完:
 * スキームが無ければ http:// を付け、末尾の / を外し、ポートが無ければ :19877 を付ける。
 * 例: `100.64.1.2` → `http://100.64.1.2:19877`、`my-mac` → `http://my-mac:19877`
 */
fun normalizeServerUrl(input: String): String {
    var url = input.trim()
    if (url.isEmpty()) return ""
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
    url = url.removeSuffix("/")
    // amon と同じく「:// の後に : があるか」だけで判断する (IPv6 の [..] は amon も扱わない)
    if (!url.substringAfter("://").contains(':')) url = "$url:$DEFAULT_PORT"
    return url
}

/**
 * 1 つだけ保存していた古い形の接続先を、ローカル / グローバルのどちらの欄に入れるか決める。
 * メニューバーアプリの移し替えと同じく、Tailscale (…ts.net / 100.x) ならグローバル、それ以外はローカル。
 */
fun legacyConnection(url: String, token: String): Connection {
    val host = hostOf(url)
    val global = host.endsWith(".ts.net") || host.startsWith("100.")
    return if (global) Connection("", url, token) else Connection(url, "", token)
}

/** URL のホスト部分 (403 のメッセージ用) */
fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBeforeLast(':')
