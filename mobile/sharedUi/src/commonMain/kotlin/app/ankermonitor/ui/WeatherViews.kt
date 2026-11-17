package app.ankermonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ankermonitor.shared.HourCard
import app.ankermonitor.shared.WeatherBadge
import app.ankermonitor.shared.cards
import app.ankermonitor.shared.nowIndex

/** 右上の天気 (いまの天気・気温と、今日の最高/最低・日照)。タップで 1 時間ごとの天気を開閉する */
@Composable
fun WeatherBadgeButton(badge: WeatherBadge, open: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(badge.emoji, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.padding(start = 4.dp)) {
                badge.temp?.let { Text("$it℃", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                badge.todayLine?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 1 時間ごとの天気のカードを横に並べる。「いま」のカードを左端にして開く */
@Composable
fun HourlyStrip(state: HourlyState) {
    Card(Modifier.fillMaxWidth()) {
        when (state) {
            HourlyState.Loading -> Message("読み込んでいます…")
            is HourlyState.Failed -> Message("読み込めませんでした。${state.message}")
            is HourlyState.Loaded -> {
                val cards = state.weather.cards()
                if (cards.isEmpty()) {
                    Message("1 時間ごとの天気はありません")
                } else {
                    // 読み直すたびに「いま」の位置から始める (スクロール位置を前の結果から引き継がない)
                    key(state) {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.weather.nowIndex)
                        LazyRow(
                            state = listState,
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(cards) { _, c -> HourCardView(c) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
}

@Composable
private fun HourCardView(card: HourCard) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (card.isPast) 0.45f else 1f)) {
        // 日付が変わったところにだけ日付を出す (カードの高さはそろえる)
        Text(
            card.dateMark ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (card.isNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (card.isNow) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Column(
                Modifier.width(52.dp).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    card.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (card.isNow) FontWeight.Bold else FontWeight.Normal,
                )
                Text(card.emoji, style = MaterialTheme.typography.titleLarge)
                Text(card.temp, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(card.pop, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
