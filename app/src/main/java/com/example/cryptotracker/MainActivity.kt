package com.example.cryptotracker

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// --- 1. МОДЕЛИ ДАННЫХ ---
enum class CryptoToken(
    val title: String, val symbol: String, val cgId: String,
    val iconStr: String, val color: Color
) {
    SOL("Solana", "SOLUSDT", "solana", "◎", Color(0xFF9C27B0)),
    BTC("Bitcoin", "BTCUSDT", "bitcoin", "₿", Color(0xFFFF9800)),
    ETH("Ethereum", "ETHUSDT", "ethereum", "◆", Color(0xFF2196F3))
}

data class DailyPrice(
    val date: String,
    val rawOpen: Double, val rawHigh: Double,
    val rawLow: Double, val rawClose: Double,
    val highPriceString: String
) {
    val isBullish: Boolean get() = rawClose >= rawOpen
}

// --- 2. VIEWMODEL (Логика и Сеть) ---
class CryptoViewModel : ViewModel() {
    private val _selectedCrypto = MutableStateFlow(CryptoToken.SOL)
    val selectedCrypto: StateFlow<CryptoToken> = _selectedCrypto.asStateFlow()

    private val _priceText = MutableStateFlow("—")
    val priceText = _priceText.asStateFlow()

    private val _history = MutableStateFlow<List<DailyPrice>>(emptyList())
    val history = _history.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _fearAndGreedValue = MutableStateFlow("—")
    val fearAndGreedValue = _fearAndGreedValue.asStateFlow()

    private val _fearAndGreedClass = MutableStateFlow("")
    val fearAndGreedClass = _fearAndGreedClass.asStateFlow()

    private val _marketCap = MutableStateFlow<Double?>(null)
    val marketCap = _marketCap.asStateFlow()

    private val _marketCapChange = MutableStateFlow(0.0)
    val marketCapChange = _marketCapChange.asStateFlow()

    init { loadAllData() }

    fun selectCrypto(token: CryptoToken) {
        if (_selectedCrypto.value != token) {
            _selectedCrypto.value = token
            loadAllData()
        }
    }

    fun loadAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    fetchCurrentPrice()
                    fetchHistory()
                    fetchFearAndGreed()
                    fetchMarketCap()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun fetchCurrentPrice() {
        try {
            val response = URL("https://api.binance.com/api/v3/ticker/price?symbol=${_selectedCrypto.value.symbol}").readText()
            val price = JSONObject(response).getString("price").toDouble()
            _priceText.value = formatPrice(price)
        } catch (_: Exception) {
            if (_priceText.value == "—") _priceText.value = "Ошибка"
        }
    }

    private fun fetchHistory() {
        try {
            val response = URL("https://api.binance.com/api/v3/klines?symbol=${_selectedCrypto.value.symbol}&interval=1d&limit=7").readText()
            val jsonArray = JSONArray(response)
            val newHistory = mutableListOf<DailyPrice>()
            val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())

            for (i in 0 until jsonArray.length() - 1) {
                val dayData = jsonArray.getJSONArray(i)
                val timestamp = dayData.getLong(0)
                val open = dayData.getString(1).toDouble()
                val high = dayData.getString(2).toDouble()
                val low = dayData.getString(3).toDouble()
                val close = dayData.getString(4).toDouble()

                newHistory.add(
                    DailyPrice(
                        date = sdf.format(Date(timestamp)),
                        rawOpen = open, rawHigh = high,
                        rawLow = low, rawClose = close,
                        highPriceString = formatPrice(high)
                    )
                )
            }
            _history.value = newHistory
        } catch (_: Exception) {}
    }

    private fun fetchFearAndGreed() {
        try {
            val response = URL("https://api.alternative.me/fng/").readText()
            val firstItem = JSONObject(response).getJSONArray("data").getJSONObject(0)
            _fearAndGreedValue.value = firstItem.getString("value")
            _fearAndGreedClass.value = firstItem.getString("value_classification")
        } catch (_: Exception) {}
    }

    private fun fetchMarketCap() {
        try {
            val tokenInfo = _selectedCrypto.value.cgId
            val response = URL("https://api.coingecko.com/api/v3/simple/price?ids=$tokenInfo&vs_currencies=usd&include_market_cap=true&include_24hr_change=true").readText()
            val coinData = JSONObject(response).getJSONObject(tokenInfo)
            _marketCap.value = coinData.getDouble("usd_market_cap")
            _marketCapChange.value = coinData.getDouble("usd_24h_change")
        } catch (_: Exception) {}
    }
}

// --- ХЕЛПЕРЫ ФОРМАТИРОВАНИЯ ---
fun formatPrice(price: Double): String = String.format(Locale.US, "%,.2f", price)

fun formatMarketCap(value: Double): String {
    return when {
        value >= 1_000_000_000_000 -> String.format(Locale.US, "$%.2f трлн", value / 1_000_000_000_000)
        value >= 1_000_000_000 -> String.format(Locale.US, "$%.2f млрд", value / 1_000_000_000)
        value >= 1_000_000 -> String.format(Locale.US, "$%.2f млн", value / 1_000_000)
        else -> String.format(Locale.US, "$%.0f", value)
    }
}

fun translatedFnGClass(fngClass: String): String = when (fngClass) {
    "Extreme Fear" -> "Экстремальный страх"
    "Fear" -> "Страх"
    "Neutral" -> "Нейтрально"
    "Greed" -> "Жадность"
    "Extreme Greed" -> "Экстремальная жадность"
    else -> fngClass
}

fun fngColor(valueStr: String): Color {
    val valInt = valueStr.toIntOrNull() ?: return Color.Gray
    return when (valInt) {
        in 0..24 -> Color(0xFFF44336)
        in 25..44 -> Color(0xFFFF9800)
        in 45..54 -> Color(0xFFFFEB3B)
        in 55..74 -> Color(0xFF4CAF50)
        in 75..100 -> Color(0xFF009688)
        else -> Color.Gray
    }
}

// --- 3. UI (JETPACK COMPOSE) ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Создаем ViewModel напрямую, чтобы не требовать дополнительных библиотек
        val viewModel = ViewModelProvider(this)[CryptoViewModel::class.java]

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CryptoScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun CryptoScreen(viewModel: CryptoViewModel) {
    val selectedCrypto by viewModel.selectedCrypto.collectAsState()
    val priceText by viewModel.priceText.collectAsState()
    val history by viewModel.history.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val fearAndGreedValue by viewModel.fearAndGreedValue.collectAsState()
    val fearAndGreedClass by viewModel.fearAndGreedClass.collectAsState()
    val marketCap by viewModel.marketCap.collectAsState()
    val marketCapChange by viewModel.marketCapChange.collectAsState()

    val maxHistoryPrice = history.maxOfOrNull { it.rawHigh }
    val minHistoryPrice = history.minOfOrNull { it.rawLow }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CryptoPicker(selectedCrypto) { viewModel.selectCrypto(it) }
                Spacer(modifier = Modifier.height(24.dp))
                PriceHeader(selectedCrypto, priceText)
                Spacer(modifier = Modifier.height(24.dp))
                UpdateButton(selectedCrypto, isLoading) { viewModel.loadAllData() }
                Spacer(modifier = Modifier.height(24.dp))
                HistoryList(history, isLoading)
            }

            Column(
                modifier = Modifier
                    .weight(1.5f)
                    .verticalScroll(rememberScrollState())
            ) {
                StatsPanel(maxHistoryPrice, minHistoryPrice)
                Spacer(modifier = Modifier.height(24.dp))
                ChartAndExtras(history, fearAndGreedValue, fearAndGreedClass, marketCap, marketCapChange)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CryptoPicker(selectedCrypto) { viewModel.selectCrypto(it) }
            Spacer(modifier = Modifier.height(24.dp))
            PriceHeader(selectedCrypto, priceText)
            Spacer(modifier = Modifier.height(24.dp))
            UpdateButton(selectedCrypto, isLoading) { viewModel.loadAllData() }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(24.dp))

            HistoryList(history, isLoading)

            if (history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                StatsPanel(maxHistoryPrice, minHistoryPrice)
                Spacer(modifier = Modifier.height(24.dp))
                ChartAndExtras(history, fearAndGreedValue, fearAndGreedClass, marketCap, marketCapChange)
            }
        }
    }
}

@Composable
fun CryptoPicker(selectedCrypto: CryptoToken, onSelect: (CryptoToken) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Заменили .values() на .entries для совместимости с Kotlin 1.9
        CryptoToken.entries.forEach { token ->
            val isSelected = selectedCrypto == token
            val bgColor by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bgColor, RoundedCornerShape(6.dp))
                    .clickable { onSelect(token) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = token.title,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
            }
        }
    }
}

@Composable
fun PriceHeader(selectedCrypto: CryptoToken, priceText: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Заменили картинки на символы, чтобы убрать зависимость от библиотеки Icons
        Text(
            text = selectedCrypto.iconStr,
            fontSize = 60.sp,
            color = selectedCrypto.color,
            fontWeight = FontWeight.Bold
        )
        Text("Текущий курс ${selectedCrypto.title}", color = Color.Gray, fontSize = 14.sp)
        Text(
            text = "$$priceText",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun UpdateButton(selectedCrypto: CryptoToken, isLoading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = !isLoading,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = selectedCrypto.color)
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
        } else {
            Text("Обновить", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HistoryList(history: List<DailyPrice>, isLoading: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Макс. цена за 6 дней", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))

        if (history.isEmpty() && !isLoading) {
            Text("Загрузка истории...", color = Color.Gray)
        } else {
            history.reversed().forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item.date, color = Color.Gray)
                    Text("$${item.highPriceString}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun StatsPanel(maxHistoryPrice: Double?, minHistoryPrice: Double?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Статистика за период", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▲", color = Color(0xFF4CAF50), fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("Максимум", fontSize = 12.sp, color = Color.Gray)
                }
                if (maxHistoryPrice != null) {
                    Text("$${formatPrice(maxHistoryPrice)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▼", color = Color(0xFFF44336), fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("Минимум", fontSize = 12.sp, color = Color.Gray)
                }
                if (minHistoryPrice != null) {
                    Text("$${formatPrice(minHistoryPrice)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun ChartAndExtras(
    history: List<DailyPrice>,
    fearAndGreedValue: String,
    fearAndGreedClass: String,
    marketCap: Double?,
    marketCapChange: Double
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Дополнительно", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(200.dp)
                    .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                CandlestickChart(history)
            }
            Column(
                modifier = Modifier.width(110.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column {
                    Text("Настроение", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        if (fearAndGreedValue == "—") "—" else "$fearAndGreedValue/100",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                    if (fearAndGreedClass.isNotEmpty()) {
                        Text(
                            text = translatedFnGClass(fearAndGreedClass),
                            fontSize = 11.sp, color = fngColor(fearAndGreedValue)
                        )
                    }
                }
                Column {
                    Text("Капитализация", fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (marketCap != null) {
                        val isGrowing = marketCapChange >= 0
                        val capColor = if (isGrowing) Color(0xFF4CAF50) else Color(0xFFF44336)
                        Text(
                            text = formatMarketCap(marketCap),
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = capColor, maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val arrowStr = if (isGrowing) "▲" else "▼"
                            Text(arrowStr, color = capColor, fontSize = 12.sp)
                            Spacer(Modifier.width(2.dp))
                            Text(String.format(Locale.US, "%.2f%%", abs(marketCapChange)), fontSize = 11.sp, color = capColor)
                        }
                    } else {
                        Text("—", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CandlestickChart(history: List<DailyPrice>) {
    if (history.isEmpty()) return

    val minPrice = history.minOf { it.rawLow }
    val maxPrice = history.maxOf { it.rawHigh }
    val priceRange = if (maxPrice == minPrice) 1.0 else maxPrice - minPrice

    Canvas(modifier = Modifier.fillMaxSize()) {
        val candleWidth = size.width / history.size
        val widthPadding = candleWidth * 0.2f
        val actualCandleWidth = candleWidth - (widthPadding * 2)

        history.forEachIndexed { index, dailyPrice ->
            val color = if (dailyPrice.isBullish) Color(0xFF4CAF50) else Color(0xFFF44336)
            val xCenter = (index * candleWidth) + (candleWidth / 2)

            val yHigh = size.height - ((dailyPrice.rawHigh - minPrice) / priceRange * size.height).toFloat()
            val yLow = size.height - ((dailyPrice.rawLow - minPrice) / priceRange * size.height).toFloat()
            val yOpen = size.height - ((dailyPrice.rawOpen - minPrice) / priceRange * size.height).toFloat()
            val yClose = size.height - ((dailyPrice.rawClose - minPrice) / priceRange * size.height).toFloat()

            drawLine(
                color = color,
                start = Offset(xCenter, yHigh),
                end = Offset(xCenter, yLow),
                strokeWidth = 4f
            )

            val topY = minOf(yOpen, yClose)
            val bottomY = maxOf(yOpen, yClose)

            drawRect(
                color = color,
                topLeft = Offset(xCenter - (actualCandleWidth / 2), topY),
                size = Size(actualCandleWidth, maxOf(bottomY - topY, 4f))
            )
        }
    }
}