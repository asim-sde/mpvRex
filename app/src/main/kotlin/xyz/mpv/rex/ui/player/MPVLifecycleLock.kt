package xyz.mpv.rex.ui.player

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Non-blocking synchronization manager for libmpv native teardown and creation.
 *
 * Ensures that if a previous PlayerActivity instance is executing native `MPVLib.destroy()`,
 * a newly launched PlayerActivity instance cleanly awaits native teardown completion
 * using Kotlin StateFlows without blocking the Android UI main thread.
 */
object MPVLifecycleLock {
  private const val TAG = "MPVLifecycleLock"
  private val _isTearingDown = MutableStateFlow(false)
  val isTearingDown = _isTearingDown.asStateFlow()

  @Volatile
  var isNativeInitialized: Boolean = false
    private set

  /**
   * Monitor that must be held from a native-call lifecycle check through the call itself,
   * and across `MPVLib.destroy()` during teardown. A check made without holding it is
   * check-then-act: libmpv treats calls overlapping `mpv_terminate_destroy` as undefined,
   * and the JNI layer exits the process on a call arriving after the context is freed.
   */
  val nativeApiLock = Any()

  /**
   * Called when native MPV initialization is complete in MPVView.postInitOptions().
   */
  fun onNativeInitialized() {
    isNativeInitialized = true
    Log.d(TAG, "Native MPV marked initialized")
  }

  /**
   * Called when MPV teardown begins in PlayerActivity.cleanupMPV() or HeadlessPlaybackController.stop().
   */
  fun onTeardownStart() {
    _isTearingDown.value = true
    isNativeInitialized = false
    Log.d(TAG, "Native MPV teardown started")
  }

  /**
   * Called when MPV teardown completes in PlayerActivity.cleanupMPV().
   */
  fun onTeardownComplete() {
    _isTearingDown.value = false
    isNativeInitialized = false
    Log.d(TAG, "Native MPV teardown completed")
  }

  /**
   * Non-blockingly suspends until any ongoing native MPV teardown finishes.
   */
  suspend fun awaitTeardown() {
    if (!_isTearingDown.value) return

    Log.d(TAG, "Awaiting native MPV teardown completion...")
    _isTearingDown.first { !it }
    Log.d(TAG, "Native MPV teardown wait finished")
  }
}
