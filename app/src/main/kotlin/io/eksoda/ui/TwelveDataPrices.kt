package io.eksoda.ui

import io.eksoda.engine.finance.DailyMarketPrice
import io.eksoda.engine.finance.MarketPriceHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection

/** Only the chosen public ticker, exchange and API credential leave the phone. */
internal object TwelveDataPrices {
    suspend fun fetch(symbol: String, mic: String, apiKey: String): MarketPriceHistory =
        withContext(Dispatchers.IO) {
            require(symbol.matches(Regex("[A-Z0-9._-]{1,32}")))
            require(mic.matches(Regex("[A-Z0-9]{4}")))
            require(apiKey.matches(Regex("[A-Za-z0-9_-]{8,128}")))
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            val uri = URI("https://api.twelvedata.com/time_series?symbol=$encoded&mic_code=$mic" +
                "&interval=1day&outputsize=400&adjust=splits")
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "apikey $apiKey")
                connection.setRequestProperty("Accept", "application/json")
                check(connection.responseCode == 200) { "Price request failed" }
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    val out = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        check(out.length + count <= 1_000_000) { "Response too large" }
                        out.append(buffer, 0, count)
                    }
                    out.toString()
                }
                parse(body, symbol, mic, LocalDate.now())
            } finally {
                connection.disconnect()
            }
        }

    internal fun parse(body: String, symbol: String, mic: String, today: LocalDate): MarketPriceHistory {
        val root = JSONObject(body)
        check(root.optString("status") == "ok") { "Data not available" }
        val meta = root.getJSONObject("meta")
        check(meta.getString("symbol").equals(symbol, ignoreCase = true))
        check(meta.getString("mic_code").equals(mic, ignoreCase = true))
        val currency = meta.getString("currency")
        // Do not uppercase GBp/GBX into GBP: pence are not pounds.
        check(currency.matches(Regex("[A-Z]{3}")) && currency != "GBX")
        val values = root.getJSONArray("values")
        check(values.length() in 1..400)
        val prices = (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            val day = LocalDate.parse(value.getString("datetime"))
            check(!day.isAfter(today))
            DailyMarketPrice(day, BigDecimal(value.getString("close")))
        }
        return MarketPriceHistory(currency, prices)
    }
}
