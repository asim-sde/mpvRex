package xyz.mpv.rex.ui.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import xyz.mpv.rex.preferences.AdvancedPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI

/**
 * Per-file demuxer cache caps for localhost proxy streams.
 *
 * Only applied to URLs served by the in-process streaming proxy (see [isProxyUrl]). Skipped on
 * low-RAM devices and devices with small total memory, and downgraded to a smaller tier (or
 * skipped) based on currently available memory. A non-blank user mpv.conf takes precedence and
 * disables the tuning entirely.
 */
object NetworkStreamTuning : KoinComponent {
  private val advancedPreferences: AdvancedPreferences by inject()

  private const val MIB = 1024L * 1024L

  private const val MIN_RAM = (2.5 * 1024 * 1024 * 1024).toLong()
  private const val FULL_TIER_RAM = (5.2 * 1024 * 1024 * 1024).toLong()

  // Usable memory (availMem above the system's low-memory kill threshold) must exceed a
  // tier's combined forward+back caps by this factor for the tier to be held comfortably;
  // otherwise loadFileOptions downgrades to the mid tier.
  private const val AVAIL_MEM_HEADROOM = 3

  private data class CacheTier(val maxBytes: Long, val maxBackBytes: Long) {
    val combinedBytes: Long get() = maxBytes + maxBackBytes
  }

  private val FULL_TIER = CacheTier(maxBytes = 400 * MIB, maxBackBytes = 200 * MIB)
  private val MID_TIER = CacheTier(maxBytes = 200 * MIB, maxBackBytes = 100 * MIB)

  fun isProxyUrl(url: String?): Boolean {
    if (url == null) return false
    // Mirrors NetworkStreamingProxy.extractStreamId's host check so the two predicates
    // cannot drift apart over what counts as a proxy URL.
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.equals("http", ignoreCase = true) == true &&
      uri.host?.lowercase() in setOf("127.0.0.1", "localhost")
  }

  fun loadFileOptions(context: Context): String? {
    // A non-blank user mpv.conf wins: per-file loadfile options would silently override the
    // user's own cache settings there, so the whole tuning is skipped instead.
    if (advancedPreferences.mpvConf.get().isNotBlank()) return null
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    if (activityManager.isLowRamDevice) return null
    // The universal APK still ships 32-bit ABIs; on a 32-bit process the cache's native
    // allocations contend for ~3 GiB of address space, which the RAM gates cannot see.
    if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) return null
    val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    if (memoryInfo.totalMem < MIN_RAM) return null
    // Only the memory above the low-memory kill threshold is actually usable; if even the
    // smallest tier's caps cannot fit there the device is too memory-pressed to cache into.
    val usableMemory = memoryInfo.availMem - memoryInfo.threshold
    if (usableMemory < MID_TIER.combinedBytes) return null
    val selectedTier = if (memoryInfo.totalMem < FULL_TIER_RAM) MID_TIER else FULL_TIER
    val tier =
      if (usableMemory < AVAIL_MEM_HEADROOM * selectedTier.combinedBytes) MID_TIER else selectedTier
    return "demuxer-max-bytes=${tier.maxBytes},demuxer-max-back-bytes=${tier.maxBackBytes}"
  }
}
