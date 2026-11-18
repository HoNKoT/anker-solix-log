package app.ankermonitor.shared

/** 読めなかったときに利用者へ出す理由。分け方は AnkerMonitor.swift の fetch() と amon remote に合わせる */
sealed interface Problem {
    val message: String

    data object WrongToken : Problem {
        override val message =
            "トークンが違います。記録している Mac で amon token を実行し、表示された値を入れ直してください"
    }

    data class Refused(val host: String) : Problem {
        override val message =
            "$host に断られました (403)。記録している Mac で amon lan on になっているか確認してください"
    }

    data class Unreachable(val code: Int?) : Problem {
        override val message = buildString {
            append("記録している Mac に接続できません")
            if (code != null) append(" (HTTP $code)")
            append("。同じネットワークにいるか、スマホと Mac の Tailscale がオンか、Mac がスリープしていないか確認してください")
        }
    }
}

fun FetchResult.problem(connection: Connection): Problem? = when (this) {
    is FetchResult.Success -> null
    FetchResult.Unauthorized -> Problem.WrongToken
    FetchResult.Forbidden -> Problem.Refused(connection.candidates.joinToString(" / ") { hostOf(it) })
    is FetchResult.Unreachable -> Problem.Unreachable(code)
}
