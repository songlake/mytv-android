package top.yogiczy.mytv.core.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.network.await
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 节目单获取
 */
class EpgRepository(
    source: EpgSource,
) : FileCacheRepository("epg-${source.url.hashCode().toUInt().toString(16)}.json") {
    private val log = Logger.create(javaClass.simpleName)
    private val epgXmlRepository = EpgXmlRepository(source.url)

    /**
     * 解析节目单xml
     */
    private suspend fun parseFromXml(
        xmlString: String,
        filteredChannels: List<String> = emptyList(),
    ) = withContext(Dispatchers.Default) {
        fun parseTime(time: String): Long {
            if (time.length < 14) return 0

            return SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
                .parse(time)?.time ?: 0
        }

        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlString))

        val epgMap = mutableMapOf<String, Epg>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "channel") {
                        val channelId = parser.getAttributeValue(null, "id")
                        parser.nextTag()
                        val channelName = parser.nextText()

                        if (filteredChannels.isEmpty() || filteredChannels.contains(channelName.lowercase())) {
                            epgMap[channelId] = Epg(channelName, EpgProgrammeList())
                        }
                    } else if (parser.name == "programme") {
                        val channelId = parser.getAttributeValue(null, "channel")
                        val startTime = parser.getAttributeValue(null, "start")
                        val stopTime = parser.getAttributeValue(null, "stop")
                        parser.nextTag()
                        val title = parser.nextText()

                        epgMap[channelId]?.let { epg ->
                            epgMap[channelId] = epg.copy(
                                programmeList = EpgProgrammeList(
                                    epg.programmeList + listOf(
                                        EpgProgramme(
                                            startAt = parseTime(startTime),
                                            endAt = parseTime(stopTime),
                                            title = title,
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        log.i("解析节目单完成，共${epgMap.size}个频道，${epgMap.values.sumOf { it.programmeList.size }}个节目")
        return@withContext EpgList(epgMap.values.toList())
    }

    /**
     * 获取节目单列表
     */
    suspend fun getEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): EpgList = withContext(Dispatchers.Default) {
        try {
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < refreshTimeThreshold) {
                log.i("未到时间点，不刷新节目单")
                return@withContext EpgList()
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val xmlJson = getOrRefresh({ lastModified, _ ->
                dateFormat.format(System.currentTimeMillis()) != dateFormat.format(lastModified)
            }) {
                val xmlString = epgXmlRepository.getEpgXml()
                Json.encodeToString(
                    parseFromXml(
                        xmlString,
                        filteredChannels.map { it.lowercase() },
                    )
                )
            }

            return@withContext Json.decodeFromString(xmlJson)
        } catch (ex: Exception) {
            log.e("获取节目单失败", ex)
            throw Exception(ex)
        }
    }
}

/**
 * 节目单xml获取
 */
private class EpgXmlRepository(
    private val url: String
) : FileCacheRepository("epg-${url.hashCode().toUInt().toString(16)}.xml") {
    private val log = Logger.create(javaClass.simpleName)

    /**
     * 获取远程xml
     */
    private suspend fun fetchXml(): String {
        log.i("获取节目单xml: $url")

        // 1. 绕过 HTTPS 证书校验（改为更兼容老电视的 TLS）
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS") 
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        // 2. 构建终极霸体版 OkHttpClient (修复 GZIP 冲突)
        val client = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true } 
            .followRedirects(true)             
            .followSslRedirects(true)          
            // 改回普通的 Interceptor，只伪装 UA，不干涉 GZIP 压缩
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(req)
            }
            .build()

        // 3. 构建初始请求（Header 已经在拦截器里加了，这里不需要写了）
        val request = okhttp3.Request.Builder().url(url).build()

        try {
            // 4. 放弃作者自定义的 await()，直接在 IO 线程同步执行，规避协程超时 BUG
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val response = client.newCall(request).execute() // 原生的同步执行
                
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")

                val fetcher = top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher.instances.first { it.isSupport(url) }
                fetcher.fetch(response)
            }
        } catch (ex: Exception) {
            // 顺手把异常的真实 message 抛到 UI 上，方便如果再报错能看出究竟是什么问题
            log.e("获取节目单xml失败: ${ex.message}", ex)
            throw Exception("获取节目单xml失败: ${ex.message}", ex)
        }
    }

    /**
     * 获取xml
     */
    suspend fun getEpgXml(): String {
        return getOrRefresh(0) {
            fetchXml()
        }
    }
}
