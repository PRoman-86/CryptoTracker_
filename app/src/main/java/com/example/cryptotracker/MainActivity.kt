package com.example.cryptotracker

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// --- 1. МОДЕЛИ ДАННЫХ ---
@Suppress("SpellCheckingInspection")
enum class CryptoToken(
    val title: String,
    val symbol: String,
    val cgId: String,
    val iconStr: String,
    val color: Color
) {
    SOL("Solana", "SOLUSDT", "solana", "◎", Color(0xFFAB47BC)),
    BTC("Bitcoin", "BTCUSDT", "bitcoin", "₿", Color(0xFFFFA726)),
    ETH("Ethereum", "ETHUSDT", "ethereum", "◆", Color(0xFF42A5F5))
}

data class DailyPrice(
    val date: String,
    val rawOpen: Double,
    val rawHigh: Double,
    val rawLow: Double,
    val rawClose: Double,
    val highPriceString: String,
    val isToday: Boolean = false
) {
    val isBullish: Boolean get() = rawClose >= rawOpen
}

data class MarketCapInfo(
    val marketCap: Double,
    val change24h: Double
)

data class FearAndGreedInfo(
    val value: String,
    val classification: String
)

data class TokenData(
    val currentPrice: Double? = null,
    val priceText: String = "—",
    val history: List<DailyPrice> = emptyList(),
    val marketCap: Double? = null,
    val marketCapChange: Double = 0.0,
    val lastUpdatedText: String? = null
) {
    // Синхронизируем текущую (сегодняшнюю) свечу с живой ценой тикера
    val effectiveHistory: List<DailyPrice>
        get() {
            val price = currentPrice ?: return history
            return history.map { day ->
                if (day.isToday) {
                    val syncedHigh = maxOf(day.rawHigh, price)
                    val syncedLow = minOf(day.rawLow, price)
                    day.copy(
                        rawHigh = syncedHigh,
                        rawLow = syncedLow,
                        rawClose = price,
                        highPriceString = formatPrice(syncedHigh)
                    )
                } else {
                    day
                }
            }
        }

    val periodMaxPrice: Double?
        get() = listOfNotNull(
            effectiveHistory.maxOfOrNull { it.rawHigh },
            currentPrice
        ).maxOrNull()

    val periodMinPrice: Double?
        get() = listOfNotNull(
            effectiveHistory.minOfOrNull { it.rawLow },
            currentPrice
        ).minOrNull()
}

data class CryptoUiState(
    val selectedCrypto: CryptoToken = CryptoToken.SOL,
    val isLoading: Boolean = false,
    val tokenDataMap: Map<CryptoToken, TokenData> = emptyMap(),
    val fearAndGreedValue: String = "—",
    val fearAndGreedClass: String = ""
) {
    val currentTokenData: TokenData
        get() = tokenDataMap[selectedCrypto] ?: TokenData()
}

// --- 2. СЕТЕВОЙ СЛОЙ (REPOSITORY) ---
class CryptoRepository {

    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CryptoTrackerAndroid/1.0")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP error: $code for $urlString")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchCurrentPrice(token: CryptoToken): Result<Double> = runCatching {
        val response = httpGet("https://api.binance.com/api/v3/ticker/price?symbol=${token.symbol}")
        JSONObject(response).getString("price").toDouble()
    }

    fun fetchHistory(token: CryptoToken): Result<List<DailyPrice>> = runCatching {
        val response = httpGet("https://api.binance.com/api/v3/klines?symbol=${token.symbol}&interval=1d&limit=7")
        val jsonArray = JSONArray(response)
        if (jsonArray.length() == 0) return@runCatching emptyList()

        val newHistory = mutableListOf<DailyPrice>()
        val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
        val lastIndex = jsonArray.length() - 1

        for (i in 0..lastIndex) {
            val dayData = jsonArray.getJSONArray(i)
            val timestamp = dayData.getLong(0)
            val open = dayData.getString(1).toDouble()
            val high = dayData.getString(2).toDouble()
            val low = dayData.getString(3).toDouble()
            val close = dayData.getString(4).toDouble()
            val isToday = (i == lastIndex)

            newHistory.add(
                DailyPrice(
                    date = if (isToday) "сегодня" else sdf.format(Date(timestamp)),
                    rawOpen = open,
                    rawHigh = high,
                    rawLow = low,
                    rawClose = close,
                    highPriceString = formatPrice(high),
                    isToday = isToday
                )
            )
        }
        newHistory
    }

    fun fetchFearAndGreed(): Result<FearAndGreedInfo> = runCatching {
        val response = httpGet("https://api.alternative.me/fng/")
        val firstItem = JSONObject(response).getJSONArray("data").getJSONObject(0)
        FearAndGreedInfo(
            value = firstItem.getString("value"),
            classification = firstItem.getString("value_classification")
        )
    }

    fun fetchMarketCap(token: CryptoToken): Result<MarketCapInfo> = runCatching {
        val id = token.cgId
        val response = httpGet(
            "https://api.coingecko.com/api/v3/simple/price?ids=$id&vs_currencies=usd&include_market_cap=true&include_24hr_change=true"
        )
        val coinData = JSONObject(response).getJSONObject(id)
        MarketCapInfo(
            marketCap = coinData.getDouble("usd_market_cap"),
            change24h = coinData.getDouble("usd_24h_change")
        )
    }
}

// --- 3. VIEWMODEL ---
class CryptoViewModel(
    private val repository: CryptoRepository = CryptoRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(CryptoUiState())
    val uiState: StateFlow<CryptoUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadAllData()
    }

    fun selectCrypto(token: CryptoToken) {
        if (_uiState.value.selectedCrypto != token) {
            _uiState.update { it.copy(selectedCrypto = token) }
            loadAllData()
        }
    }

    fun loadAllData() {
        val targetToken = _uiState.value.selectedCrypto
        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val (priceRes, historyRes, fngRes, capRes) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val priceDeferred = async { repository.fetchCurrentPrice(targetToken) }
                        val historyDeferred = async { repository.fetchHistory(targetToken) }
                        val fngDeferred = async { repository.fetchFearAndGreed() }
                        val capDeferred = async { repository.fetchMarketCap(targetToken) }

                        FetchBundle(
                            price = priceDeferred.await(),
                            history = historyDeferred.await(),
                            fng = fngDeferred.await(),
                            marketCap = capDeferred.await()
                        )
                    }
                }

                if (_uiState.value.selectedCrypto != targetToken) return@launch

                val allSucceeded = priceRes.isSuccess &&
                        historyRes.isSuccess &&
                        fngRes.isSuccess &&
                        capRes.isSuccess

                val newTimeStamp = if (allSucceeded) {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    "обновлено в $timeStr"
                } else {
                    null
                }

                _uiState.update { state ->
                    val prevTokenData = state.tokenDataMap[targetToken] ?: TokenData()

                    val updatedCurrentPrice = priceRes.getOrNull() ?: prevTokenData.currentPrice
                    val updatedPriceText = priceRes.fold(
                        onSuccess = { formatPrice(it) },
                        onFailure = { if (prevTokenData.priceText == "—") "Ошибка" else prevTokenData.priceText }
                    )

                    val updatedHistory = historyRes.getOrElse { prevTokenData.history }

                    val updatedCap = capRes.getOrNull()?.marketCap ?: prevTokenData.marketCap
                    val updatedCapChange = capRes.getOrNull()?.change24h ?: prevTokenData.marketCapChange

                    val finalTimestamp = newTimeStamp ?: prevTokenData.lastUpdatedText

                    val updatedTokenData = prevTokenData.copy(
                        currentPrice = updatedCurrentPrice,
                        priceText = updatedPriceText,
                        history = updatedHistory,
                        marketCap = updatedCap,
                        marketCapChange = updatedCapChange,
                        lastUpdatedText = finalTimestamp
                    )

                    val fngInfo = fngRes.getOrNull()

                    state.copy(
                        tokenDataMap = state.tokenDataMap + (targetToken to updatedTokenData),
                        fearAndGreedValue = fngInfo?.value ?: state.fearAndGreedValue,
                        fearAndGreedClass = fngInfo?.classification ?: state.fearAndGreedClass
                    )
                }
            } finally {
                if (_uiState.value.selectedCrypto == targetToken) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private data class FetchBundle(
        val price: Result<Double>,
        val history: Result<List<DailyPrice>>,
        val fng: Result<FearAndGreedInfo>,
        val marketCap: Result<MarketCapInfo>
    )
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
        in 0..24 -> Color(0xFFEF5350)
        in 25..44 -> Color(0xFFFFA726)
        in 45..54 -> Color(0xFFFFEE58)
        in 55..74 -> Color(0xFF66BB6A)
        in 75..100 -> Color(0xFF26A69A)
        else -> Color.Gray
    }
}

// --- 4. UI (JETPACK COMPOSE, PERMANENT DARK THEME) ---
private val DarkAppColorScheme = darkColorScheme(
    primary = Color(0xFFAB47BC),
    background = Color(0xFF121214),
    surface = Color(0xFF1C1C20),
    surfaceVariant = Color(0xFF2A2A30),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF9E9EA9)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF121214.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0xFF121214.toInt()

        val viewModel = ViewModelProvider(this)[CryptoViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = DarkAppColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CryptoScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun PullToRefreshContainer(
    isLoading: Boolean,
    accentColor: Color,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val triggerThresholdPx = with(density) { 80.dp.toPx() }
    val maxPullPx = with(density) { 120.dp.toPx() }
    val badgeSizePx = with(density) { 40.dp.toPx() }

    var pullOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            isSwipeRefreshing = false
        }
    }

    val nestedScrollConnection = remember(isLoading, isSwipeRefreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Если пользователь уже потянул вниз, а затем повел палец вверх — сначала сворачиваем индикатор
                if (available.y < 0f && pullOffset > 0f) {
                    val consumedY = maxOf(available.y, -pullOffset)
                    pullOffset += consumedY
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Перехватываем свайп вниз, когда внутренний скролл уже уперся в верхнюю границу
                if (available.y > 0f && !isLoading && !isSwipeRefreshing) {
                    val dampedDelta = available.y * 0.5f
                    pullOffset = (pullOffset + dampedDelta).coerceIn(0f, maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffset >= triggerThresholdPx && !isLoading && !isSwipeRefreshing) {
                    isSwipeRefreshing = true
                    onRefresh()
                }
                pullOffset = 0f
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pullOffset = 0f
                return Velocity.Zero
            }
        }
    }

    val targetOffset = if (isSwipeRefreshing) triggerThresholdPx * 0.75f else pullOffset
    val animatedOffset by animateFloatAsState(
        targetValue = targetOffset,
        label = "pullRefreshOffset"
    )

    Box(modifier = modifier.nestedScroll(nestedScrollConnection)) {
        content()

        if (animatedOffset > 1f || isSwipeRefreshing) {
            val progress = (pullOffset / triggerThresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = animatedOffset - badgeSizePx
                    }
                    .size(40.dp)
                    .shadow(4.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .border(1.dp, accentColor.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSwipeRefreshing) {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { progress },
                        color = accentColor,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CryptoScreen(viewModel: CryptoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedCrypto = uiState.selectedCrypto
    val tokenData = uiState.currentTokenData
    val history = tokenData.effectiveHistory

    val maxPeriodPrice = tokenData.periodMaxPrice
    val minPeriodPrice = tokenData.periodMinPrice

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    PullToRefreshContainer(
        isLoading = uiState.isLoading,
        accentColor = selectedCrypto.color,
        onRefresh = { viewModel.loadAllData() },
        modifier = Modifier.fillMaxSize()
    ) {
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
                    PriceHeader(selectedCrypto, tokenData.priceText)
                    Spacer(modifier = Modifier.height(24.dp))
                    UpdateButton(selectedCrypto, uiState.isLoading) { viewModel.loadAllData() }
                    Spacer(modifier = Modifier.height(24.dp))
                    HistoryList(history, uiState.isLoading)
                }

                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .verticalScroll(rememberScrollState())
                ) {
                    StatsPanel(maxPeriodPrice, minPeriodPrice)
                    Spacer(modifier = Modifier.height(24.dp))
                    ChartAndExtras(
                        selectedCrypto = selectedCrypto,
                        history = history,
                        currentPrice = tokenData.currentPrice,
                        maxPeriodPrice = maxPeriodPrice,
                        minPeriodPrice = minPeriodPrice,
                        fearAndGreedValue = uiState.fearAndGreedValue,
                        fearAndGreedClass = uiState.fearAndGreedClass,
                        marketCap = tokenData.marketCap,
                        marketCapChange = tokenData.marketCapChange,
                        lastUpdatedText = tokenData.lastUpdatedText
                    )
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
                PriceHeader(selectedCrypto, tokenData.priceText)
                Spacer(modifier = Modifier.height(24.dp))
                UpdateButton(selectedCrypto, uiState.isLoading) { viewModel.loadAllData() }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(24.dp))

                HistoryList(history, uiState.isLoading)

                if (history.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    StatsPanel(maxPeriodPrice, minPeriodPrice)
                    Spacer(modifier = Modifier.height(24.dp))
                    ChartAndExtras(
                        selectedCrypto = selectedCrypto,
                        history = history,
                        currentPrice = tokenData.currentPrice,
                        maxPeriodPrice = maxPeriodPrice,
                        minPeriodPrice = minPeriodPrice,
                        fearAndGreedValue = uiState.fearAndGreedValue,
                        fearAndGreedClass = uiState.fearAndGreedClass,
                        marketCap = tokenData.marketCap,
                        marketCapChange = tokenData.marketCapChange,
                        lastUpdatedText = tokenData.lastUpdatedText
                    )
                }
            }
        }
    }
}

@Composable
fun CryptoPicker(selectedCrypto: CryptoToken, onSelect: (CryptoToken) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        CryptoToken.entries.forEach { token ->
            val isSelected = selectedCrypto == token
            var isFocused by remember { mutableStateOf(false) }

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                label = "tabBg"
            )
            val borderColor = if (isFocused) token.color else Color.Transparent

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bgColor, RoundedCornerShape(6.dp))
                    .border(1.5.dp, borderColor, RoundedCornerShape(6.dp))
                    .onFocusChanged { isFocused = it.isFocused }
                    .clickable { onSelect(token) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = token.title,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
fun PriceHeader(selectedCrypto: CryptoToken, priceText: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = selectedCrypto.iconStr,
            fontSize = 60.sp,
            color = selectedCrypto.color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Текущий курс ${selectedCrypto.title}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        Text(
            text = "$$priceText",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun UpdateButton(selectedCrypto: CryptoToken, isLoading: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        enabled = !isLoading,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = selectedCrypto.color,
            contentColor = Color.White,
            disabledContainerColor = selectedCrypto.color.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        )
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
        Text(
            text = "Макс. цена за 7 дней",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (history.isEmpty()) {
            Text(
                text = if (isLoading) "Загрузка истории..." else "Нет данных",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            history.reversed().forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.date,
                        color = if (item.isToday) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (item.isToday) FontWeight.Medium else FontWeight.Normal
                    )
                    Text(
                        text = "$${item.highPriceString}",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun StatsPanel(maxPeriodPrice: Double?, minPeriodPrice: Double?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Статистика за период",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▲", color = Color(0xFF66BB6A), fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("Максимум", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = maxPeriodPrice?.let { "$${formatPrice(it)}" } ?: "—",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▼", color = Color(0xFFEF5350), fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("Минимум", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = minPeriodPrice?.let { "$${formatPrice(it)}" } ?: "—",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ChartAndExtras(
    selectedCrypto: CryptoToken,
    history: List<DailyPrice>,
    currentPrice: Double?,
    maxPeriodPrice: Double?,
    minPeriodPrice: Double?,
    fearAndGreedValue: String,
    fearAndGreedClass: String,
    marketCap: Double?,
    marketCapChange: Double,
    lastUpdatedText: String?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Дополнительно",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(200.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                CandlestickChart(
                    history = history,
                    currentPrice = currentPrice,
                    maxPeriodPrice = maxPeriodPrice,
                    minPeriodPrice = minPeriodPrice,
                    accentColor = selectedCrypto.color
                )
            }
            Column(
                modifier = Modifier.width(110.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column {
                    Text("Настроение", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (fearAndGreedValue == "—") "—" else "$fearAndGreedValue/100",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (fearAndGreedClass.isNotEmpty()) {
                        Text(
                            text = translatedFnGClass(fearAndGreedClass),
                            fontSize = 11.sp,
                            color = fngColor(fearAndGreedValue)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Капитализация",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (marketCap != null) {
                        val isGrowing = marketCapChange >= 0
                        val capColor = if (isGrowing) Color(0xFF66BB6A) else Color(0xFFEF5350)
                        Text(
                            text = formatMarketCap(marketCap),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = capColor,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val arrowStr = if (isGrowing) "▲" else "▼"
                            Text(arrowStr, color = capColor, fontSize = 12.sp)
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = String.format(Locale.US, "%.2f%%", abs(marketCapChange)),
                                fontSize = 11.sp,
                                color = capColor
                            )
                        }
                    } else {
                        Text(
                            text = "—",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        if (lastUpdatedText != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = lastUpdatedText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CandlestickChart(
    history: List<DailyPrice>,
    currentPrice: Double?,
    maxPeriodPrice: Double?,
    minPeriodPrice: Double?,
    accentColor: Color
) {
    if (history.isEmpty() || maxPeriodPrice == null || minPeriodPrice == null) return

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$${formatPrice(maxPeriodPrice)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            if (currentPrice != null) {
                Text(
                    text = "┄ $${formatPrice(currentPrice)}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            fun priceToY(price: Double): Float {
                if (maxPeriodPrice == minPeriodPrice) return size.height / 2f
                return size.height - ((price - minPeriodPrice) / (maxPeriodPrice - minPeriodPrice) * size.height).toFloat()
            }

            val candleWidth = size.width / history.size
            val widthPadding = candleWidth * 0.2f
            val actualCandleWidth = candleWidth - (widthPadding * 2)

            history.forEachIndexed { index, dailyPrice ->
                val color = if (dailyPrice.isBullish) Color(0xFF66BB6A) else Color(0xFFEF5350)
                val xCenter = (index * candleWidth) + (candleWidth / 2)

                val yHigh = priceToY(dailyPrice.rawHigh)
                val yLow = priceToY(dailyPrice.rawLow)
                val yOpen = priceToY(dailyPrice.rawOpen)
                val yClose = priceToY(dailyPrice.rawClose)

                drawLine(
                    color = color,
                    start = Offset(xCenter, yHigh),
                    end = Offset(xCenter, yLow),
                    strokeWidth = 3.5f
                )

                val topY = minOf(yOpen, yClose)
                val bottomY = maxOf(yOpen, yClose)

                drawRect(
                    color = color,
                    topLeft = Offset(xCenter - (actualCandleWidth / 2), topY),
                    size = Size(actualCandleWidth, maxOf(bottomY - topY, 4f))
                )
            }

            if (currentPrice != null) {
                val yCurrent = priceToY(currentPrice)
                drawLine(
                    color = accentColor,
                    start = Offset(0f, yCurrent),
                    end = Offset(size.width, yCurrent),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
                drawCircle(
                    color = accentColor,
                    radius = 4.5f,
                    center = Offset(size.width - 4.5f, yCurrent)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$${formatPrice(minPeriodPrice)}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}