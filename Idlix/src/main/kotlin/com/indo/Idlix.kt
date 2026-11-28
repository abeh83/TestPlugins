package com.indo

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Idlix : MainAPI() {
    override var mainUrl = "https://z3.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cfKiller = CloudflareKiller()

	// ===== Login / Session =====
	private var accessToken: String? = null
	private var refreshToken: String? = null
	private var isLoggedIn = false
	private var authenticatedOrigin: String? = null
	private var sessionExpiresAt: Long = 0L
	
	// ===== Session persistence =====
	private var sessionPrefs: SharedPreferences? = null
	
	fun initSession(context: Context) {
		sessionPrefs = context.applicationContext
			.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		restoreSession()
	}
	
	private fun getSessionKey(): SecretKey {
		check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
	
		val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
			load(null)
		}
	
		val existingKey = keyStore.getKey(
			SESSION_KEY_ALIAS,
			null
		) as? SecretKey
	
		if (existingKey != null) {
			return existingKey
		}
	
		val keyGenerator = KeyGenerator.getInstance(
			KeyProperties.KEY_ALGORITHM_AES,
			"AndroidKeyStore"
		)
	
		val keySpec = KeyGenParameterSpec.Builder(
			SESSION_KEY_ALIAS,
			KeyProperties.PURPOSE_ENCRYPT or
				KeyProperties.PURPOSE_DECRYPT
		)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(
				KeyProperties.ENCRYPTION_PADDING_NONE
			)
			.setKeySize(256)
			.build()
	
		keyGenerator.init(keySpec)
	
		return keyGenerator.generateKey()
	}
	
	private fun encryptSession(value: String): String {
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
	
		cipher.init(
			Cipher.ENCRYPT_MODE,
			getSessionKey()
		)
	
		val encrypted = cipher.doFinal(
			value.toByteArray(Charsets.UTF_8)
		)
	
		val iv = Base64.encodeToString(
			cipher.iv,
			Base64.NO_WRAP
		)
	
		val data = Base64.encodeToString(
			encrypted,
			Base64.NO_WRAP
		)
	
		return "$iv:$data"
	}
	
	private fun decryptSession(value: String): String {
		val parts = value.split(":", limit = 2)
	
		require(parts.size == 2)
	
		val iv = Base64.decode(parts[0], Base64.NO_WRAP)
		val data = Base64.decode(parts[1], Base64.NO_WRAP)
	
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
	
		cipher.init(
			Cipher.DECRYPT_MODE,
			getSessionKey(),
			GCMParameterSpec(128, iv)
		)
	
		return String(
			cipher.doFinal(data),
			Charsets.UTF_8
		)
	}
	
	private fun clearSavedSession() {
		sessionPrefs?.edit()
			?.remove(KEY_ACCESS)
			?.remove(KEY_REFRESH)
			?.remove(KEY_ORIGIN)
			?.remove(KEY_LOGGED_IN)
			?.remove(KEY_ENCRYPTED_SESSION)
			?.commit()
	}
	
	private fun persistSession() {
		val prefs = sessionPrefs ?: return
	
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
			!hasAccountSession()
		) {
			clearSavedSession()
			return
		}
	
		try {
			val session = JSONObject()
				.put("accessToken", accessToken)
				.put("origin", authenticatedOrigin)
				.put("expiresAt", sessionExpiresAt)
	
			val encrypted = encryptSession(
				session.toString()
			)
	
			val saved = prefs.edit()
				.remove(KEY_ACCESS)
				.remove(KEY_REFRESH)
				.remove(KEY_ORIGIN)
				.remove(KEY_LOGGED_IN)
				.putString(KEY_ENCRYPTED_SESSION, encrypted)
				.commit()
	
			if (saved) {
				Log.d(TAG, "Encrypted account session saved")
			} else {
				clearSavedSession()
				Log.w(TAG, "Unable to save account session")
			}
	
		} catch (e: Exception) {
			clearSavedSession()
	
			Log.w(
				TAG,
				"Session storage failed: ${e.javaClass.simpleName}"
			)
		}
	}
	
	private fun restoreSession() {
		val prefs = sessionPrefs ?: return
	
		// Remove legacy plaintext credentials.
		prefs.edit()
			.remove(KEY_ACCESS)
			.remove(KEY_REFRESH)
			.remove(KEY_ORIGIN)
			.remove(KEY_LOGGED_IN)
			.apply()
	
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			clearSavedSession()
			return
		}
	
		try {
			val encrypted = prefs.getString(
				KEY_ENCRYPTED_SESSION,
				null
			) ?: return
	
			val session = JSONObject(
				decryptSession(encrypted)
			)
	
			val token = session.optString("accessToken", "")
			val origin = session.optString("origin", "")
			val expiresAt = session.optLong("expiresAt", 0L)
	
			if (token.isBlank() ||
				origin != mainUrl ||
				expiresAt <= System.currentTimeMillis()
			) {
				logout()
				return
			}
	
			accessToken = token
			refreshToken = null
			authenticatedOrigin = origin
			sessionExpiresAt = expiresAt
			isLoggedIn = true
	
			Log.d(TAG, "Encrypted account session restored")
	
		} catch (e: Exception) {
			logout()
	
			Log.w(
				TAG,
				"Session restoration failed: ${e.javaClass.simpleName}"
			)
		}
	}
	
	private fun parseSessionExpiration(value: String): Long {
		if (value.isBlank()) return 0L
	
		return try {
			val format = SimpleDateFormat(
				"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
				Locale.US
			).apply {
				timeZone = TimeZone.getTimeZone("UTC")
				isLenient = false
			}
	
			format.parse(value)?.time ?: 0L
	
		} catch (_: Exception) {
			0L
		}
	}

	enum class LoginResult {
		SUCCESS,
		INVALID_CREDENTIALS,
		NETWORK_ERROR,
		SESSION_ERROR,
		UNTRUSTED_ENDPOINT,
		SERVER_ERROR
	}

	suspend fun testLogin(
		identifier: String,
		password: String
	): LoginResult {
	
		delay(1000L)
	
		return if (
			identifier == "testuser" &&
			password == "test123456"
		) {
			LoginResult.SUCCESS
		} else {
			LoginResult.INVALID_CREDENTIALS
		}
	}

	fun hasAccountSession(): Boolean {
		if (!isLoggedIn || accessToken.isNullOrBlank()) {
			return false
		}
	
		if (authenticatedOrigin.isNullOrBlank() ||
			authenticatedOrigin != mainUrl ||
			authenticatedOrigin != "https://z3.idlixku.com"
		) {
			Log.w(TAG, "Account session origin mismatch")
			return false
		}
	
		if (sessionExpiresAt <= System.currentTimeMillis()) {
			logout()
			return false
		}
	
		return true
	}

	private val authHeaders: Map<String, String>
		get() {
			val headers = apiHeaders.toMutableMap()
	
			if (hasAccountSession()) {
				accessToken?.let {
					headers["Cookie"] = "access_token=$it"
					headers["Authorization"] = "Bearer $it"
				}
			}
	
			return headers
		}

    // A getter, so the Referer follows mainUrl when the domain moves.
    private val apiHeaders
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Accept" to "application/json",
            "Referer" to "$mainUrl/"
        )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    private var mainUrlCheckedAt = 0L
    private val mainUrlLock = Mutex()

    /**
     * The site keeps moving between mirror domains (z2.idlixku.com, z3.idlixku.com, ...).
     * idlixian.com stays put and answers with a 301 to the current domain, so read its Location
     * (without following it) and switch mainUrl to that host. Checked at most once per
     * MAIN_URL_CHECK_MS; when the backup cannot be reached, the last known domain stays.
     * Stored links from an older domain keep working: load and loadLinks only use their slug.
     */
    private suspend fun ensureMainUrl() {
        if (System.currentTimeMillis() - mainUrlCheckedAt < MAIN_URL_CHECK_MS) return
        mainUrlLock.withLock {
            if (System.currentTimeMillis() - mainUrlCheckedAt < MAIN_URL_CHECK_MS) return
            mainUrlCheckedAt = System.currentTimeMillis()
            val location = runCatching {
                app.get(BACKUP_URL, allowRedirects = false, timeout = 5L).okhttpResponse.header("Location")
            }.onFailure { Log.d(TAG, "backup domain check failed: $it") }.getOrNull()
			val target = location?.toHttpUrlOrNull()
			
			if (target == null ||
				target.scheme != "https" ||
				target.host != "z3.idlixku.com" ||
				target.port != 443
			) {
				Log.w(TAG, "Untrusted redirect destination rejected")
				return
			}
            val newMainUrl = "${target.scheme}://${target.host}"
            if (newMainUrl != mainUrl) {
                Log.d(TAG, "main domain moved: $mainUrl -> $newMainUrl")
                mainUrl = newMainUrl
            } else {
                Log.d(TAG, "main domain still $mainUrl")
            }
        }
    }

    	/**
	* Simple login for Idlix.
	* Call this with username/email + password.
	* Returns true if login succeeded.
	*/
	suspend fun login(identifier: String, password: String): Boolean {
		ensureMainUrl()
	
		val body = mapOf(
			"identifier" to identifier,
			"password" to password
		)
	
		return try {
			val response = app.post(
				"$mainUrl/api/auth/login",
				headers = apiHeaders + mapOf(
					"Content-Type" to "application/json",
					"Origin" to mainUrl,
					"Referer" to "$mainUrl/"
				),
				json = body,
				allowRedirects = false,
				interceptor = cfKiller
			)
	
			if (!response.isSuccessful) {
				Log.e("Idlix", "Login failed: HTTP ${response.code}")
				return false
			}
	
			// Extract tokens from Set-Cookie headers
			val cookies = response.headers.values("set-cookie")
			var access: String? = null
			var refresh: String? = null
	
			cookies.forEach { cookie ->
				when {
					cookie.startsWith("access_token=") -> {
						access = cookie.substringAfter("access_token=").substringBefore(";")
					}
					cookie.startsWith("refresh_token=") -> {
						refresh = cookie.substringAfter("refresh_token=").substringBefore(";")
					}
				}
			}
	
			// Fallback: sometimes token is also in the JSON body
			val json = try { JSONObject(response.text) } catch (_: Exception) { null }
			if (access == null) {
				access = json?.optString("access_token")?.takeIf { it.isNotBlank() }
			}
	
			if (!access.isNullOrBlank()) {
				accessToken = access
				refreshToken = refresh
				isLoggedIn = true
				authenticatedOrigin = mainUrl
				
				sessionExpiresAt = parseSessionExpiration(
					json?.optString("sessionExpiresAt", "") ?: ""
				)
				
				if (sessionExpiresAt <= System.currentTimeMillis()) {
					logout()
					Log.w(TAG, "Missing or expired session expiration")
					return false
				}
				
				persistSession()
				Log.d("Idlix", "Login successful")
				true
			} else {
				Log.e("Idlix", "Login succeeded but no access_token found")
				false
			}
		} catch (e: Exception) {
			Log.e("Idlix", "Login error: ${e.javaClass.simpleName}")
			false
		}
	}
	
	fun logout() {
		accessToken = null
		refreshToken = null
		authenticatedOrigin = null
		sessionExpiresAt = 0L
		isLoggedIn = false
	
		clearSavedSession()
	}

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureMainUrl()
        
        val sections = listOfNotNull(
            getApiSection("Movies", page, "api/movies"),
            getApiSection("TV Series", page, "api/series"),
        )
        return newHomePageResponse(sections)
    }

    private suspend fun getApiSection(name: String, page: Int, apiPath: String): HomePageList? {
        val resp = app.get("$mainUrl/$apiPath?page=$page&limit=24", headers = apiHeaders, interceptor = cfKiller)
        val text = resp.text
        val data = try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { return null }
        if (data == null) return null
        val items = (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug", "")
            val title = item.optString("title", "")
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val voteAvg = item.opt("voteAverage").let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val isSeries = apiPath.contains("series")
            val href = if (isSeries) "/series/$slug" else "/movie/$slug"
            if (isSeries)
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
        return if (items.isEmpty()) null else HomePageList(name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureMainUrl()
        val q = URLEncoder.encode(query, "UTF-8")
        val resp = app.get("$mainUrl/api/search?q=$q", headers = authHeaders, interceptor = cfKiller)
        val text = resp.text
        val data = try { JSONObject(text).optJSONArray("results") } catch (e: Exception) { return emptyList() }
        if (data == null) return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug", "")
            val title = item.optString("title", "")
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val contentType = item.optString("contentType", "movie")
            val voteAvg = item.opt("voteAverage").let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val href = if (contentType == "tv_series") "/series/$slug" else "/movie/$slug"
            if (contentType == "tv_series")
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureMainUrl()
        val isSeries = url.contains("/series/")
        val slug = if (isSeries) {
            url.substringAfter("/series/").substringBefore("/").substringBefore("?")
        } else {
            url.substringAfter("/movie/").substringBefore("/").substringBefore("?")
        }

        if (isSeries) {
            try {
                val seriesResp = app.get("$mainUrl/api/series/$slug", headers = apiHeaders, interceptor = cfKiller)
                val seriesJson = JSONObject(seriesResp.text)
                val title = seriesJson.optString("title", "").ifBlank { null }
                if (title != null) {
                    val posterPath = seriesJson.optString("posterPath", "")
                    val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
                    val backdropPath = seriesJson.optString("backdropPath", "")
                    val backdrop = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/original$backdropPath" else null
                    val plot = seriesJson.optString("overview", "").ifBlank { null }
                    val year = seriesJson.optString("firstAirDate", "").take(4).toIntOrNull()
                    val score = Score.from10(seriesJson.opt("voteAverage").let { v ->
                        when (v) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull()
                            else -> null
                        }
                    })
                    val genresArray = seriesJson.optJSONArray("genres")
                    val tags = if (genresArray != null) {
                        (0 until genresArray.length()).mapNotNull { i ->
                            genresArray.optJSONObject(i)?.optString("name", "")?.ifBlank { null }
                        }
                    } else emptyList()

                    val episodeUrls = mutableListOf<Episode>()
                    val seasonsArray = seriesJson.optJSONArray("seasons")
                    val defaultSeason = seriesJson.optJSONObject("defaultSeason")
                    val defaultSeasonNum = defaultSeason?.optInt("seasonNumber", 1) ?: 1

                    if (seasonsArray != null && seasonsArray.length() > 0) {
                        for (i in 0 until seasonsArray.length()) {
                            val sObj = seasonsArray.optJSONObject(i) ?: continue
                            val sNum = sObj.optInt("seasonNumber", i + 1)
                            val episodesArray = if (sNum == defaultSeasonNum && defaultSeason != null) {
                                defaultSeason.optJSONArray("episodes")
                            } else {
                                try {
                                    val sResp = app.get("$mainUrl/api/series/$slug/season/$sNum", headers = apiHeaders, interceptor = cfKiller)
                                    val sJson = JSONObject(sResp.text)
                                    sJson.optJSONObject("season")?.optJSONArray("episodes")
                                } catch (_: Exception) { null }
                            }
                            if (episodesArray != null) {
                                for (j in 0 until episodesArray.length()) {
                                    val ep = episodesArray.optJSONObject(j) ?: continue
                                    val epNum = ep.optInt("episodeNumber", 0)
                                    val epId = ep.optString("id", "")
                                    if (epNum > 0) {
                                        val epName = ep.optString("name").ifBlank { ep.optString("title") }.ifBlank { "S${sNum}E$epNum" }
                                        val stillPath = ep.optString("stillPath", "")
                                        val still = if (stillPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$stillPath" else null
                                        val epUrl = fixUrl("/series/$slug/season/$sNum/episode/$epNum") + (if (epId.isNotBlank()) "#episodeId=$epId" else "")
                                        episodeUrls.add(newEpisode(epUrl) {
                                            this.name = epName
                                            this.episode = epNum
                                            this.season = sNum
                                            this.posterUrl = still
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (episodeUrls.isNotEmpty()) {
                        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
                            posterUrl = poster
                            backgroundPosterUrl = backdrop
                            this.plot = plot
                            this.tags = tags
                            this.year = year
                            this.score = score
                        }
                    }
                }
            } catch (_: Exception) {}
        } else {
            try {
                val movieResp = app.get("$mainUrl/api/movies/$slug", headers = apiHeaders, interceptor = cfKiller)
                val movieJson = JSONObject(movieResp.text)
                val title = movieJson.optString("title", "").ifBlank { null }
                if (title != null) {
                    val posterPath = movieJson.optString("posterPath", "")
                    val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
                    val backdropPath = movieJson.optString("backdropPath", "")
                    val backdrop = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/original$backdropPath" else null
                    val plot = movieJson.optString("overview", "").ifBlank { null }
                    val year = movieJson.optString("releaseDate", "").take(4).toIntOrNull()
                    val score = Score.from10(movieJson.opt("voteAverage").let { v ->
                        when (v) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull()
                            else -> null
                        }
                    })
                    val genresArray = movieJson.optJSONArray("genres")
                    val tags = if (genresArray != null) {
                        (0 until genresArray.length()).mapNotNull { i ->
                            genresArray.optJSONObject(i)?.optString("name", "")?.ifBlank { null }
                        }
                    } else emptyList()

                    val movieId = movieJson.optString("id", "")
                    val movieData = url + (if (movieId.isNotBlank()) "#movieId=$movieId" else "")
                    return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                        posterUrl = poster
                        backgroundPosterUrl = backdrop
                        this.plot = plot
                        this.tags = tags
                        this.year = year
                        this.score = score
                    }
                }
            } catch (_: Exception) {}
        }

        // HTML fallback
        val resp = app.get(onMainDomain(url), interceptor = cfKiller)
        val raw = resp.text
        val html = raw.replace("\\\"", "\"")

        val title = Regex("\"og:title\",\"content\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("Title not found")

        val poster = Regex("\"og:image\",\"content\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?.ifBlank { null }

        val description = Regex("\"og:description\",\"content\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?.ifBlank { null }

        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\"releaseDate\":\"(\\d{4})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\"firstAirDate\":\"(\\d{4})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = Regex("\"genres\":\\[([^\\]]+)\\]").find(html)?.let { m ->
            Regex("\"name\":\"([^\"]+)\"").findAll(m.value).map { it.groupValues[1] }.toList()
        } ?: emptyList()

        val voteAvg = Regex("\"voteAverage\":\"?([0-9.]+)\"?").find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        return if (isSeries) {
            val episodeUrls = mutableListOf<Episode>()
            Regex("""/series/[^/]+/season/(\d+)/episode/(\d+)""").findAll(html).forEach { m ->
                val seasonNum = m.groupValues[1].toIntOrNull() ?: 1
                val epNum = m.groupValues[2].toIntOrNull()
                if (epNum != null) {
                    episodeUrls.add(newEpisode(fixUrl(m.value)) {
                        this.name = "S${seasonNum}E$epNum"
                        this.episode = epNum
                        this.season = seasonNum
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureMainUrl()
        if (tryLoadFromApi(data, subtitleCallback, callback)) return true
        if (tryLoadFromWebView(data, subtitleCallback, callback)) return true

        var found = false
        val resp = app.get(onMainDomain(data.substringBefore("#")), interceptor = cfKiller)
        val raw = resp.text
        val html = raw.replace("\\\"", "\"")

        val sources = listOf(
            Regex("""sources":\[([^\]]+)\]"""),
            Regex("""playlist":\[([^\]]+)\]"""),
            Regex("""playerSources":\[([^\]]+)\]"""),
        )

        for (pattern in sources) {
            val match = pattern.find(html) ?: continue
            val content = match.groupValues[1]
            Regex("""https?://[^\s"',]+\.(?:m3u8|mp4)[^\s"',]*""").findAll(content).forEach { m ->
                callback(newExtractorLink("Idlix", "Idlix", m.value) {
                    this.referer = "$mainUrl/"
                })
                found = true
            }
            Regex("""playbackUrl":"([^"]+)""").findAll(content).forEach { m ->
                val quality = Regex("""quality":"([^"]+)""").find(content)?.groupValues?.getOrNull(1) ?: "Unknown"
                callback(newExtractorLink("Idlix", "Idlix - $quality", m.groupValues[1]) {
                    this.quality = parseQuality(quality)
                    this.referer = "$mainUrl/"
                })
                found = true
            }
        }

        return found
    }

    private suspend fun tryLoadFromApi(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directEpId = Regex("""#episodeId=([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.getOrNull(1)
        val directMovieId = Regex("""#movieId=([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.getOrNull(1)
        val cleanData = data.substringBefore("#")

        val slug: String
        val contentType: String
        val extra: Map<String, String>

        val episodeMatch = Regex("/series/([^/]+)/season/(\\d+)/episode/(\\d+)").find(cleanData)
        if (episodeMatch != null) {
            slug = episodeMatch.groupValues[1]
            contentType = "tv_series"
            extra = mapOf("season" to episodeMatch.groupValues[2], "episode" to episodeMatch.groupValues[3])
        } else if (cleanData.contains("/series/")) {
            slug = cleanData.substringAfter("/series/").substringBefore("/").substringBefore("?")
            contentType = "tv_series"
            extra = emptyMap()
        } else {
            slug = cleanData.substringAfter("/movie/").substringBefore("/").substringBefore("?")
            contentType = "movie"
            extra = emptyMap()
        }
        if (slug.isBlank()) return false

        val pageUrl = if (contentType == "movie") "$mainUrl/movie/$slug"
        else if (extra.containsKey("season")) "$mainUrl/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
        else "$mainUrl/series/$slug"

        try {
            app.get(pageUrl, interceptor = cfKiller)
        } catch (_: Exception) {}

        val contentId: String
        try {
            contentId = when {
                !directEpId.isNullOrBlank() -> directEpId
                !directMovieId.isNullOrBlank() -> directMovieId
                contentType == "movie" -> {
                    val movieResp = app.get("$mainUrl/api/movies/$slug", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                    JSONObject(movieResp.text).optString("id", "")
                }
                extra.containsKey("season") -> {
                    val sNum = extra["season"] ?: "1"
                    val epNum = extra["episode"]?.toIntOrNull() ?: 1
                    var matchedId = ""
                    try {
                        val seasonResp = app.get("$mainUrl/api/series/$slug/season/$sNum", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                        val seasonJson = JSONObject(seasonResp.text)
                        val episodes = seasonJson.optJSONObject("season")?.optJSONArray("episodes")
                            ?: seasonJson.optJSONArray("episodes")
                        if (episodes != null) {
                            for (i in 0 until episodes.length()) {
                                val ep = episodes.optJSONObject(i) ?: continue
                                if (ep.optInt("episodeNumber", 0) == epNum) {
                                    matchedId = ep.optString("id", "")
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (matchedId.isBlank()) {
                        try {
                            val seriesResp = app.get("$mainUrl/api/series/$slug", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                            val seriesJson = JSONObject(seriesResp.text)
                            val defEps = seriesJson.optJSONObject("defaultSeason")?.optJSONArray("episodes")
                            if (defEps != null) {
                                for (i in 0 until defEps.length()) {
                                    val ep = defEps.optJSONObject(i) ?: continue
                                    if (ep.optInt("episodeNumber", 0) == epNum) {
                                        matchedId = ep.optString("id", "")
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    matchedId
                }
                else -> {
                    val seriesResp = app.get("$mainUrl/api/series/$slug", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                    val seriesJson = JSONObject(seriesResp.text)
                    seriesJson.optJSONObject("defaultSeason")?.optJSONArray("episodes")?.optJSONObject(0)?.optString("id", "")
                        ?: seriesJson.optString("id", "")
                }
            }
            if (contentId.isBlank()) return false
        } catch (_: Exception) {
            return false
        }

        try {
            val playInfoType = if (contentType == "tv_series" || extra.containsKey("season") || !directEpId.isNullOrBlank()) "episode" else "movie"
            val playInfoResp = app.get(
                "$mainUrl/api/watch/play-info/$playInfoType/$contentId",
                headers = apiHeaders + mapOf("Referer" to "$pageUrl/"),
                interceptor = cfKiller
            )
            val playInfo = JSONObject(playInfoResp.text)
            val gateToken = playInfo.optString("gateToken", "")
            if (gateToken.isBlank()) return false
            // A Turnstile-gated claim always fails here; let the WebView path handle it right away.
            if (playInfo.has("verification")) return false

            val kind = playInfo.optString("kind", "")
            if (kind == "gate") {
                val unlockAt = playInfo.optLong("unlockAt", 0L)
                val serverNow = playInfo.optLong("serverNow", 0L)
                val waitMs = if (unlockAt > 0 && serverNow > 0) maxOf(0L, unlockAt - serverNow) else 0L
                if (waitMs in 1L..15500L) {
                    delay(waitMs + 300L)
                }
            }

            var claimJson = postClaim(gateToken, pageUrl)

            var retries = 0
            while (claimJson.optString("kind") == "pending" && retries < 3) {
                val remainingMs = claimJson.optLong("remainingMs", 1000L)
                delay(minOf(maxOf(500L, remainingMs + 200L), 3000L))
                claimJson = postClaim(gateToken, pageUrl)
                retries++
            }

            val rawRedeemUrl = claimJson.optString("redeemUrl", "")
            val claim = claimJson.optString("claim", "")
            val initialMaster = claimJson.optString("initialMasterUrl", "")

            var streamUrl: String? = null
            var subtitles: List<Pair<String, String>> = emptyList()

            if (claim.isNotBlank()) {
                val fullRedeemUrl = when {
                    rawRedeemUrl.startsWith("http") -> rawRedeemUrl
                    rawRedeemUrl.isNotBlank() -> "$mainUrl" + (if (rawRedeemUrl.startsWith("/api")) "" else "/api") + rawRedeemUrl
                    else -> "$mainUrl/api/watch/session/redeem"
                }

                val redeemPayload = JSONObject().put("claim", claim).put("mode", "browser").toString()

                try {
                    val redeemResp = app.post(
                        fullRedeemUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "Content-Type" to "application/json"
                        ),
                        json = JsonAsString(redeemPayload),
                        interceptor = cfKiller
                    )
                    val redeemJson = JSONObject(redeemResp.text)
                    streamUrl = redeemJson.optString("url", "").ifBlank { null }
                    val subsArray = redeemJson.optJSONArray("subtitles")
                    if (subsArray != null) {
                        subtitles = (0 until subsArray.length()).mapNotNull { i ->
                            val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                            val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                            val path = sub.optString("path", "")
                            if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                        }
                    }
                } catch (_: Exception) {}

                if (streamUrl == null && !fullRedeemUrl.contains("majorplay.net")) {
                    try {
                        val pentosResp = app.post(
                            "https://e2e.majorplay.net/api/play",
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/",
                                "Content-Type" to "application/json"
                            ),
                            json = JsonAsString(redeemPayload)
                        )
                        val pentosJson = JSONObject(pentosResp.text)
                        streamUrl = pentosJson.optString("url", "").ifBlank { null }
                        val subsArray = pentosJson.optJSONArray("subtitles")
                        if (subsArray != null && subtitles.isEmpty()) {
                            subtitles = (0 until subsArray.length()).mapNotNull { i ->
                                val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                                val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                                val path = sub.optString("path", "")
                                if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (streamUrl == null && initialMaster.isNotBlank()) {
                    streamUrl = initialMaster
                }

                if (streamUrl != null) {
                    subtitles.forEach { (lang, path) ->
                        val subUrl = if (path.startsWith("http")) path else fixUrl(path)
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                    val maxHeight = claimJson.optInt("maxHeight", 0)
                    registerSession(streamUrl, claimJson, apiHeaders["User-Agent"], null)
                    emitStream(streamUrl, maxHeight, apiHeaders["User-Agent"], callback)
                    return true
                }
            }

            if (initialMaster.isNotBlank()) {
                val maxHeight = claimJson.optInt("maxHeight", 0)
                registerSession(initialMaster, claimJson, apiHeaders["User-Agent"], null)
                emitStream(initialMaster, maxHeight, apiHeaders["User-Agent"], callback)
                return true
            }
        } catch (_: Exception) {
            return false
        }

        return false
    }

    /**
     * Guest playback now needs a Cloudflare Turnstile token on the session claim, which only the
     * site's own player can obtain. Load the real page in a WebView, start the player, wait out the
     * preroll, and capture the master playlist it requests. The master is served as
     * `majorplay.net/v/<zone>/<videoId>/config-<n>.json?t=...&pm=browser` (HLS despite the .json name);
     * its token must be carried to every variant, init map and segment, which getVideoInterceptor does.
     *
     * The WebView must run as a plain browser: with useOkhttp the resolver replays the page's requests
     * through OkHttp, which drops POST bodies (claim, redeem) and Set-Cookie (the `did` cookie set by
     * play-info), and a spoofed desktop user agent makes Turnstile fall back to an interactive check
     * that nobody can click in a hidden WebView.
     *
     * The injected script also copies the claim and redeem responses (claim, renewal token, master
     * URL, subtitles) into a request the resolver answers locally; that gives the subtitles and what
     * getVideoInterceptor needs to get a new stream token once the first one (1 hour) runs out.
     * The master request itself stays as a fallback.
     */
    private suspend fun tryLoadFromWebView(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = onMainDomain(data.substringBefore("#"))
        var lastStatus = ""
        val sessionData = AtomicReference<JSONObject?>(null)
        return try {
            if (DEBUG_WEBVIEW) withContext(Dispatchers.Main) { WebView.setWebContentsDebuggingEnabled(true) }
            val resolver = WebViewResolver(
                interceptUrl = MASTER_URL_REGEX,
                additionalUrls = listOf(TRACE_URL_REGEX, SESSION_URL_REGEX),
                userAgent = null,
                useOkhttp = false,
                script = PLAYER_AUTOSTART_JS,
                scriptCallback = { status ->
                    if (status != lastStatus) {
                        lastStatus = status
                        Log.d(TAG, "player state: $status")
                    }
                },
                timeout = 90_000L
            )
            Log.d(TAG, "webview start: $pageUrl")
            val (request, _) = resolver.resolveUsingWebView(
                url = pageUrl,
                referer = "$mainUrl/",
                headers = emptyMap(),
                method = "GET"
            ) { req ->
                if (req.url.encodedPath == SESSION_PATH) {
                    val json = req.url.queryParameter("d")?.let { runCatching { JSONObject(it) }.getOrNull() }
                    Log.d(TAG, "webview session: ${json?.keys()?.asSequence()?.toList()}")
                    if (json != null) sessionData.set(json)
                    json?.optString("url").isNullOrBlank().not()
                } else {
                    Log.d(TAG, "webview request: ${req.method} ${req.url.host}${req.url.encodedPath}")
                    false
                }
            }
            val session = sessionData.get()
            val streamUrl = session?.optString("url")?.ifBlank { null } ?: request?.url?.toString()
            if (streamUrl == null) {
                Log.d(TAG, "webview gave no stream, last player state: $lastStatus")
                return false
            }
            val userAgent = request?.header("User-Agent") ?: WebViewResolver.webViewUserAgent
            val cookie = runCatching { CookieManager.getInstance().getCookie(mainUrl) }.getOrNull()
            registerSession(streamUrl, session, userAgent, cookie)
            session?.optJSONArray("subtitles")?.let { emitSubtitles(it, userAgent, subtitleCallback) }
            emitStream(streamUrl, 0, userAgent, callback)
            true
        } catch (e: Exception) {
            Log.d(TAG, "webview failed: $e")
            false
        }
    }

    private suspend fun emitSubtitles(
        subs: JSONArray,
        userAgent: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subHeaders = mutableMapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        if (!userAgent.isNullOrBlank()) subHeaders["User-Agent"] = userAgent
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val label = sub.optString("label").ifBlank { sub.optString("lang") }
            val path = sub.optString("path")
            if (label.isBlank() || path.isBlank()) continue
            subtitleCallback(newSubtitleFile(label, if (path.startsWith("http")) path else fixUrl(path)) {
                this.headers = subHeaders
            })
        }
    }

    private suspend fun postClaim(gateToken: String, pageUrl: String): JSONObject {
        val claimResp = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = apiHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$pageUrl/",
                "Content-Type" to "application/json"
            ),
            json = JsonAsString("""{"gateToken":"$gateToken"}"""),
            interceptor = cfKiller
        )
        return JSONObject(claimResp.text)
    }

    private suspend fun emitStream(
        streamUrl: String,
        maxHeight: Int,
        userAgent: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val linkHeaders = mutableMapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        if (!userAgent.isNullOrBlank()) linkHeaders["User-Agent"] = userAgent
        val linkName = if (maxHeight > 0) "Idlix ${maxHeight}p" else "Idlix"
        val linkQuality = when {
            maxHeight >= 1080 -> Qualities.P1080.value
            maxHeight >= 720 -> Qualities.P720.value
            maxHeight >= 480 -> Qualities.P480.value
            maxHeight >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        callback(newExtractorLink("Idlix", linkName, streamUrl, type = ExtractorLinkType.M3U8) {
            this.quality = linkQuality
            this.referer = "$mainUrl/"
            this.headers = linkHeaders
        })
    }

    /**
     * One playback: the master's token and what is needed to get a new one. The stream token runs
     * out an hour after redeem, the claim it came from lasts 12 hours, and the site gets a new
     * master URL by redeeming the same claim again (or, once the claim is gone, renewing it with
     * the renewal token). Neither needs another Turnstile check.
     */
    private class StreamSession(
        masterUrl: HttpUrl,
        var claim: String?,
        var redeemUrl: String?,
        var renewalToken: String?,
        var progressToken: String?,
        val userAgent: String?,
        val cookie: String?
    ) {
        val pathPrefix: String = masterUrl.encodedPath.substringBeforeLast("/") + "/"
        @Volatile var query: String = masterUrl.encodedQuery.orEmpty()
        var lastRefreshAt = 0L

        // Only for testing the refresh: makes the first token look like it runs out early.
        @Volatile var forcedExpiry: Long =
            if (FORCE_REFRESH_AFTER_SEC > 0) System.currentTimeMillis() / 1000 + FORCE_REFRESH_AFTER_SEC
            else Long.MAX_VALUE

        val canRefresh get() = claim != null || renewalToken != null

        /** Expiry in epoch seconds, read from `t=c2_3/v/<zone>/<videoId>/.<expiry>-<sig>`. */
        val expiresAt: Long
            get() {
                val t = runCatching { URLDecoder.decode(query, "UTF-8") }.getOrDefault(query)
                val fromToken = Regex("""/\.(\d{9,11})-""").find(t)?.groupValues?.get(1)?.toLongOrNull()
                    ?: Long.MAX_VALUE
                return minOf(fromToken, forcedExpiry)
            }

        fun covers(url: HttpUrl) = url.encodedPath.startsWith(pathPrefix) && url.queryParameter("t") == null

        fun sign(request: Request, withQuery: String): Request {
            val url = request.url
            val builder = url.newBuilder()
            withQuery.split("&").forEach { pair ->
                val key = pair.substringBefore("=")
                if (key.isNotBlank() && url.queryParameter(key) == null) {
                    builder.addEncodedQueryParameter(key, pair.substringAfter("=", ""))
                }
            }
            return request.newBuilder().url(builder.build()).build()
        }
    }

    private val sessions = ConcurrentHashMap<String, StreamSession>()

    private fun registerSession(streamUrl: String, data: JSONObject?, userAgent: String?, cookie: String?) {
        val master = streamUrl.toHttpUrlOrNull() ?: return
        sessions[streamUrl] = StreamSession(
            masterUrl = master,
            claim = data?.optString("claim")?.ifBlank { null },
            redeemUrl = data?.optString("redeemUrl")?.ifBlank { null },
            renewalToken = data?.optString("renewalToken")?.ifBlank { null },
            progressToken = data?.optString("progressToken")?.ifBlank { null },
            userAgent = userAgent,
            cookie = cookie
        )
    }

    /**
     * The stream token is only on the master playlist URL, and the CDN refuses every other request
     * without it (401). Variant playlists are listed as paths without a query, and init maps and
     * segments sit on many unrelated hosts (`g3.<random>.website/v/z3/<videoId>/p/.../cover.jpg`).
     * The token is scoped to the path (`t=c2_3/v/z3/<videoId>/.<expiry>-<sig>`), so add the
     * master's query parameters to every request under the master's directory, on any host, and
     * get a new token when it is about to run out or the CDN refuses it.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val session = sessions[extractorLink.url] ?: run {
            val master = extractorLink.url.toHttpUrlOrNull() ?: return null
            StreamSession(master, null, null, null, null, null, null)
        }
        if (session.query.isBlank()) return null
        return Interceptor { chain -> interceptStream(chain, session) }
    }

    private fun interceptStream(chain: Interceptor.Chain, session: StreamSession): Response {
        val request = chain.request()
        if (!session.covers(request.url)) return chain.proceed(request)
        if (session.canRefresh && session.expiresAt - nowSec() < TOKEN_REFRESH_MARGIN_SEC) {
            refreshStreamToken(session, usedQuery = null)
        }
        val used = session.query
        val response = chain.proceed(session.sign(request, used))
        if (response.code != 401 && response.code != 403) return response
        Log.d(TAG, "stream request refused (${response.code}): ${request.url.encodedPath}")
        if (!session.canRefresh || !refreshStreamToken(session, usedQuery = used)) return response
        response.close()
        return chain.proceed(session.sign(request, session.query))
    }

    /**
     * Gets a new master URL for [session] and takes its token. Requests run in parallel, so only one
     * of them refreshes: [usedQuery] is the token a refused request carried (null for a refresh ahead
     * of expiry); if the session already moved past it, there is nothing to do.
     */
    private fun refreshStreamToken(session: StreamSession, usedQuery: String?): Boolean = synchronized(session) {
        if (usedQuery != null && session.query != usedQuery) return@synchronized true
        if (usedQuery == null && session.expiresAt - nowSec() >= TOKEN_REFRESH_MARGIN_SEC) return@synchronized true
        if (nowSec() - session.lastRefreshAt < 15) return@synchronized false
        session.lastRefreshAt = nowSec()
        val newUrl = runCatching { runBlocking { redeemStream(session) } }
            .onFailure { Log.d(TAG, "token refresh failed: $it") }
            .getOrNull()
            ?.toHttpUrlOrNull()
        if (newUrl == null) {
            Log.d(TAG, "token refresh gave no stream URL")
            return@synchronized false
        }
        val newPrefix = newUrl.encodedPath.substringBeforeLast("/") + "/"
        if (newPrefix != session.pathPrefix) Log.d(TAG, "token refresh moved the stream to $newPrefix")
        session.query = newUrl.encodedQuery.orEmpty()
        session.forcedExpiry = Long.MAX_VALUE
        Log.d(TAG, "stream token refreshed, expires at ${session.expiresAt}")
        true
    }

    private suspend fun redeemStream(session: StreamSession): String? {
        session.claim?.let { claim -> postRedeem(session, claim)?.let { return it } }
        // The claim ran out (redeem answers 410): renew it, then redeem the new one.
        val renewal = session.renewalToken ?: return null
        val payload = JSONObject().put("renewalToken", renewal)
        session.progressToken?.let { payload.put("progressToken", it) }
        val resp = app.post(
            "$mainUrl/api/watch/session/refresh-claim",
            headers = sessionHeaders(session, withCookie = true),
            json = JsonAsString(payload.toString())
        )
        val json = runCatching { JSONObject(resp.text) }.getOrNull()
            ?.takeIf { it.optString("kind") == "pentos" && it.optString("claim").isNotBlank() }
        if (json == null) {
            Log.d(TAG, "claim renewal refused (${resp.code})")
            return null
        }
        val claim = json.optString("claim")
        session.claim = claim
        json.optString("renewalToken").ifBlank { null }?.let { session.renewalToken = it }
        json.optString("progressToken").ifBlank { null }?.let { session.progressToken = it }
        json.optString("redeemUrl").ifBlank { null }?.let { session.redeemUrl = it }
        return postRedeem(session, claim)
    }

    private suspend fun postRedeem(session: StreamSession, claim: String): String? {
        val raw = session.redeemUrl.orEmpty()
        val external = raw.startsWith("http")
        val url = when {
            external -> raw
            raw.isNotBlank() -> mainUrl + (if (raw.startsWith("/api")) "" else "/api") + raw
            else -> "$mainUrl/api/watch/session/redeem"
        }
        val resp = app.post(
            url,
            headers = sessionHeaders(session, withCookie = !external),
            json = JsonAsString(JSONObject().put("claim", claim).put("mode", "browser").toString())
        )
        if (!resp.isSuccessful) {
            Log.d(TAG, "redeem refused (${resp.code})")
            return null
        }
        return runCatching { JSONObject(resp.text).optString("url") }.getOrNull()?.ifBlank { null }
    }

    private fun sessionHeaders(session: StreamSession, withCookie: Boolean): Map<String, String> {
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )
        session.userAgent?.let { headers["User-Agent"] = it }
        if (withCookie) session.cookie?.let { headers["Cookie"] = it }
        return headers
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

	private fun getAvailableResolutions(
		manifest: String
	): List<Int> {
	
		val resolutionPattern = Regex(
			"""(?:^|,)\s*RESOLUTION=(\d+)x(\d+)(?:,|$)""",
			RegexOption.IGNORE_CASE
		)
	
		return manifest.lineSequence()
			.map { it.trim() }
			.filter { it.startsWith("#EXT-X-STREAM-INF:") }
			.mapNotNull { line ->
				resolutionPattern
					.find(line.substringAfter(":"))
					?.groupValues
					?.getOrNull(2)
					?.toIntOrNull()
			}
			.filter { it > 0 }
			.distinct()
			.sortedDescending()
			.toList()
	}

	private fun parseQuality(q: String): Int {
		return when {
			q.contains("4K", true) ||
			q.contains("2160", true) -> Qualities.P2160.value
			q.contains("1080", true) -> Qualities.P1080.value
			q.contains("720", true) -> Qualities.P720.value
			q.contains("480", true) -> Qualities.P480.value
			q.contains("360", true) -> Qualities.P360.value
			else -> Qualities.Unknown.value
		}
	}

    // Stored links may still point at an older mirror domain.
    private fun onMainDomain(url: String) = url.replace(Regex("""^https?://[^/]+"""), mainUrl)

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }

    companion object {
        private const val TAG = "Idlix"

        // Stable address that redirects (301) to the current mirror domain.
        private const val BACKUP_HOST = "idlixian.com"
        private const val BACKUP_URL = "https://$BACKUP_HOST/"
        private const val MAIN_URL_CHECK_MS = 30 * 60 * 1000L
		
		private const val PREFS_NAME = "idlix_session"
		private const val KEY_ACCESS = "access_token"
		private const val KEY_REFRESH = "refresh_token"
		private const val KEY_ORIGIN = "authenticated_origin"
		private const val KEY_LOGGED_IN = "is_logged_in"

		private const val SESSION_KEY_ALIAS =
			"idlix_account_session_key_v18"
		
		private const val KEY_ENCRYPTED_SESSION =
			"encrypted_session_v18"

        // Lets the hidden WebView be inspected over adb (chrome://inspect) while testing.
        private const val DEBUG_WEBVIEW = false

        // Get a new stream token this long before the current one runs out.
        private const val TOKEN_REFRESH_MARGIN_SEC = 120L

        // For testing the token refresh: the first token is treated as running out this many
        // seconds after the stream starts (0 = off).
        private const val FORCE_REFRESH_AFTER_SEC = 0L

        // Request the injected script makes to hand the claim/redeem data to the extension.
        private const val SESSION_PATH = "/__cs_session.png"
        private val SESSION_URL_REGEX = Regex("""/__cs_session\.png\?""")

        private val MASTER_URL_REGEX = Regex("""majorplay\.net/v/[^?#]+/config-[^/?#]+\.json|\.m3u8""")

        // Requests logged while the WebView runs, to see how far the player got.
        private val TRACE_URL_REGEX = Regex("""/api/watch/|majorplay\.net/|challenges\.cloudflare\.com/""")

        // Runs on every request the WebView makes.
        // - The site's Turnstile widget only renders once its container is wider than 0 (later size
        //   changes go through ResizeObserver and requestAnimationFrame, which never fire in a page
        //   that is not shown). Two things keep it at 0 in the resolver's WebView: the WebView has
        //   no size, and the resolver answers .css requests with an empty body, so the site's asset
        //   check marks the page `nb-asset-failed`, whose inline rule hides every direct child of
        //   body with `display: none !important` - including the Turnstile probe, which is portaled
        //   into body. Give the container a fixed width and override that rule for the probe with a
        //   more specific selector (1,1,3 against 1,1,2).
        // - The player module also loads a CSS chunk; its failed load rejects the chunk
        //   (ChunkLoadError) and the page shows an error instead of the player. Stop the error
        //   from reaching the chunk loader and fire `load` on the link instead.
        // - Wraps fetch to copy the claim response (claim, renewal token) and the redeem response
        //   (master URL, subtitles), and hands them over in a request to SESSION_PATH. The site only
        //   gets the redeem response after that request has gone out, so it arrives before the
        //   master request.
        // - Presses the hero play button, then the preroll skip button once its countdown ends
        //   ("Lewati dalam 5 dtk" turns into "Lewati Iklan").
        // - Returns a short state string for the log. When the state changes, it requests a .png
        //   that the resolver answers locally (no network), only so this script runs again and the
        //   new state reaches the log.
        private const val PLAYER_AUTOSTART_JS = """
            (function () {
                if (!window.__csIdlixCss) {
                    window.__csIdlixCss = true;
                    window.addEventListener('error', function (e) {
                        var t = e.target;
                        if (t && t.tagName === 'LINK' && /\/_next\/static\/.+\.css/.test(t.href || '')) {
                            e.stopImmediatePropagation();
                            setTimeout(function () { t.dispatchEvent(new Event('load')); }, 0);
                        }
                    }, true);
                }
                if (!window.__csIdlixFetch && window.fetch) {
                    window.__csIdlixFetch = true;
                    var origFetch = window.fetch, session = {};
                    window.fetch = function (input) {
                        var url = typeof input === 'string' ? input : (input && input.url) || String(input);
                        var p = origFetch.apply(this, arguments);
                        if (!/\/watch\/session\/|majorplay\.net\/api\//.test(url)) return p;
                        return p.then(function (res) {
                            return res.clone().json().then(function (j) {
                                if (j && j.kind === 'pentos' && j.claim) {
                                    session.claim = j.claim;
                                    session.redeemUrl = j.redeemUrl;
                                    session.renewalToken = j.renewalToken;
                                    session.progressToken = j.progressToken;
                                } else if (j && j.url && j.mode === 'browser') {
                                    session.url = j.url;
                                    session.subtitles = j.subtitles || [];
                                    new Image().src = location.origin + '/__cs_session.png?d='
                                        + encodeURIComponent(JSON.stringify(session));
                                }
                                return res;
                            }, function () { return res; });
                        });
                    };
                }
                var root = document.head || document.documentElement;
                if (root && !document.getElementById('__csIdlixStyle')) {
                    var st = document.createElement('style');
                    st.id = '__csIdlixStyle';
                    st.textContent = '.turnstile-probe,.turnstile-widget,.turnstile-widget__host'
                        + '{display:block!important;width:320px!important;min-height:80px!important}'
                        + 'html body>div.turnstile-probe:not(#__cs_none){display:block!important}';
                    root.appendChild(st);
                }
                function state() {
                    var s = [];
                    if (/\bnb-asset-failed\b/.test(document.documentElement.className)) s.push('asset-failed');
                    if (document.querySelector('button.group.cursor-pointer.z-10')) s.push('play-button');
                    var w = document.querySelector('.turnstile-widget');
                    if (w) s.push('turnstile w=' + w.clientWidth + ' rendered=' + (w.dataset.rendered || '-')
                        + ' size=' + (w.dataset.size || '-'));
                    if (document.querySelector('.turnstile-probe')) s.push('probe');
                    if (document.querySelector('.player-status__check')) s.push('turnstile-interactive');
                    var pill = document.querySelector('.player-pill--status');
                    if (pill) s.push('status: ' + (pill.innerText || '').trim().slice(0, 80));
                    if (document.querySelector('video')) s.push('video');
                    return s.join(', ') || 'waiting';
                }
                if (!window.__csIdlixAuto) {
                    window.__csIdlixAuto = true;
                    var last = '';
                    setInterval(function () {
                        var play = document.querySelector('button.group.cursor-pointer.z-10');
                        if (play) play.click();
                        document.querySelectorAll('button').forEach(function (b) {
                            var t = (b.innerText || '').trim();
                            if (!b.disabled && /^(skip ad|lewati iklan)/i.test(t)) b.click();
                        });
                        var now = state();
                        if (now !== last) {
                            last = now;
                            new Image().src = location.origin + '/__cs_state.png?' + Date.now();
                        }
                    }, 1000);
                }
                return state();
            })();
        """
    }
}
