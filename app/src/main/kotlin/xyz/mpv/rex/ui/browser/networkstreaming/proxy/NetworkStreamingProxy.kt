package xyz.mpv.rex.ui.browser.networkstreaming.proxy

import android.util.Log
import java.net.URI
import xyz.mpv.rex.domain.network.NetworkConnection
import xyz.mpv.rex.ui.browser.networkstreaming.clients.NetworkClient
import xyz.mpv.rex.ui.browser.networkstreaming.clients.NetworkClientFactory
import xyz.mpv.rex.ui.browser.networkstreaming.clients.SmbClient
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP proxy server that enables seeking for network streaming protocols
 * that don't support it natively
 */
class NetworkStreamingProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {

  companion object {
    private const val TAG = "NetworkStreamingProxy"

    @Volatile
    private var instance: NetworkStreamingProxy? = null

    fun getInstance(): NetworkStreamingProxy {
      return instance ?: synchronized(this) {
        instance ?: NetworkStreamingProxy().also {
          it.start()
          instance = it
        }
      }
    }

    fun stopInstance() {
      synchronized(this) {
        instance?.let { proxy ->
          proxy.stop()
          proxy.cleanup()
          instance = null
        }
      }
    }
  }

  // Store active connections and their clients
  private val activeStreams = ConcurrentHashMap<String, StreamInfo>()

  data class StreamInfo(
    val connection: NetworkConnection,
    val filePath: String,
    val client: NetworkClient,
    var fileSize: Long = -1L,
    var mimeType: String = "video/mp4",
  )

  /**
   * Register a stream for proxying
   * @return The local URL to use for playback
   */
  fun registerStream(
    streamId: String,
    connection: NetworkConnection,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "video/mp4",
  ): String {
    val client = NetworkClientFactory.createClient(connection)

    val streamInfo = StreamInfo(
      connection = connection,
      filePath = filePath,
      client = client,
      fileSize = fileSize,
      mimeType = mimeType,
    )

    activeStreams[streamId] = streamInfo

    return "http://127.0.0.1:$listeningPort/$streamId"
  }

  /**
   * Unregister a stream
   */
  fun unregisterStream(streamId: String) {
    activeStreams.remove(streamId)?.let { streamInfo ->
      runBlocking {
        try {
          streamInfo.client.disconnect()
        } catch (e: Exception) {
          // Ignore disconnect errors
        }
      }
    }
  }

  /**
   * Extracts the stream ID from a local proxy URL (e.g. http://127.0.0.1:port/streamId)
   */
  fun extractStreamId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return uri.takeIf { it.host == "127.0.0.1" || it.host == "localhost" }
      ?.path?.trim('/')?.substringBefore('/')?.takeIf { it.isNotEmpty() }
  }

  /**
   * Cleanup all streams
   */
  private fun cleanup() {
    val streamIds = activeStreams.keys.toList()
    streamIds.forEach { unregisterStream(it) }
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri

    // Extract stream ID from URI (format: /streamId)
    val streamId = uri.removePrefix("/").split("/").firstOrNull()
    if (streamId.isNullOrEmpty()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    val streamInfo = activeStreams[streamId]
    if (streamInfo == null) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    // Handle range requests for seeking
    val rangeHeader = session.headers["range"]

    return try {
      if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
        handleRangeRequest(session, streamInfo, rangeHeader)
      } else {
        handleFullRequest(session, streamInfo)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error serving request for stream $streamId: ${streamInfo.filePath}", e)
      Log.e(
        TAG,
        "Connection: ${streamInfo.connection.protocol} ${streamInfo.connection.host}:${streamInfo.connection.port}${streamInfo.connection.path}",
      )
      newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "Error: ${e.message}",
      )
    }
  }

  private fun handleRangeRequest(
    session: IHTTPSession,
    streamInfo: StreamInfo,
    rangeHeader: String,
  ): Response {
    // Parse range header: bytes=start-end
    val rangeValue = rangeHeader.removePrefix("bytes=")
    val parts = rangeValue.split("-")
    val start = parts[0].toLongOrNull() ?: 0L
    val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
      parts[1].toLongOrNull()
    } else {
      null
    }

    // Get file size if not known
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = getFileSize(streamInfo)
    }

    val fileSize = streamInfo.fileSize

    // An unknown size must not fail the request: the player's HTTP client
    // treats any 5xx as fatal with no retry. Only the SMB range stream needs
    // a real length (PrefetchingSmbInputStream self-limits against it; SMB
    // listings always supply one); every other data layer streams to EOF
    // independent of the requested length.
    if (fileSize < 0) {
      if (streamInfo.client is SmbClient) {
        return newFixedLengthResponse(
          Response.Status.INTERNAL_ERROR,
          MIME_PLAINTEXT,
          "Failed to determine file size",
        )
      }

      val unknownSizeStream = getStreamWithOffset(streamInfo, start, -1L)
      if (unknownSizeStream == null) {
        return newFixedLengthResponse(
          Response.Status.INTERNAL_ERROR,
          MIME_PLAINTEXT,
          "Failed to open stream",
        )
      }

      // The last byte position is unknown, so a valid 206 is impossible; the
      // body still starts at the requested offset.
      val response = newChunkedResponse(Response.Status.OK, streamInfo.mimeType, unknownSizeStream)
      response.addHeader("Accept-Ranges", "bytes")
      return response
    }

    if (fileSize == 0L) {
      return newFixedLengthResponse(Response.Status.OK, streamInfo.mimeType, "")
    }

    val rangeEnd = end ?: (fileSize - 1)
    val contentLength = rangeEnd - start + 1

    // Get stream with offset
    val inputStream = getStreamWithOffset(streamInfo, start, contentLength)

    if (inputStream == null) {
      return newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "Failed to open stream",
      )
    }

    // Create response with partial content
    val response = newFixedLengthResponse(
      Response.Status.PARTIAL_CONTENT,
      streamInfo.mimeType,
      inputStream,
      contentLength,
    )

    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Range", "bytes $start-$rangeEnd/$fileSize")
    response.addHeader("Content-Length", contentLength.toString())

    return response
  }

  private fun handleFullRequest(
    session: IHTTPSession,
    streamInfo: StreamInfo,
  ): Response {
    // Get file size if not known
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = getFileSize(streamInfo)
    }

    if (streamInfo.fileSize == 0L) {
      return newFixedLengthResponse(Response.Status.OK, streamInfo.mimeType, "")
    }

    val inputStream = getStream(streamInfo)

    if (inputStream == null) {
      return newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "Failed to open stream",
      )
    }

    // The player's HTTP client treats any 5xx as a fatal open error with no
    // retry, so an unknown size streams chunked to EOF instead of failing.
    if (streamInfo.fileSize < 0) {
      val response = newChunkedResponse(Response.Status.OK, streamInfo.mimeType, inputStream)
      response.addHeader("Accept-Ranges", "bytes")
      return response
    }

    val response = newFixedLengthResponse(
      Response.Status.OK,
      streamInfo.mimeType,
      inputStream,
      streamInfo.fileSize,
    )

    response.addHeader("Accept-Ranges", "bytes")
    if (streamInfo.fileSize > 0) {
      response.addHeader("Content-Length", streamInfo.fileSize.toString())
    }

    return response
  }

  private fun getFileSize(streamInfo: StreamInfo): Long {
    return runBlocking {
      try {
        when (streamInfo.client) {
          is xyz.mpv.rex.ui.browser.networkstreaming.clients.SmbClient -> {
            getFileSizeSMB(streamInfo)
          }

          is xyz.mpv.rex.ui.browser.networkstreaming.clients.FtpClient -> {
            getFileSizeFTP(streamInfo)
          }

          is xyz.mpv.rex.ui.browser.networkstreaming.clients.WebDavClient -> {
            val webDavClient =
              streamInfo.client as xyz.mpv.rex.ui.browser.networkstreaming.clients.WebDavClient
            if (!webDavClient.isConnected()) {
              webDavClient.connect().getOrThrow()
            }
            val sizeResult = webDavClient.getFileSize(streamInfo.filePath)
            sizeResult.getOrNull() ?: -1L
          }

          else -> {
            if (!streamInfo.client.isConnected()) {
              streamInfo.client.connect().getOrThrow()
            }
            val ftpClient =
              streamInfo.client as? xyz.mpv.rex.ui.browser.networkstreaming.clients.FtpClient
            val sizeResult = ftpClient?.getFileSize(streamInfo.filePath)
            sizeResult?.getOrNull() ?: -1L
          }
        }
      } catch (e: Exception) {
        -1L
      }
    }
  }

  private fun decodeSmbPath(path: String): String =
    runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrElse { path }

  private fun parseSmbRelativePath(filePath: String): String? {
    return when {
      filePath.startsWith("smb://", ignoreCase = true) -> {
        val pathAfterProtocol = filePath.substring(6)
        val firstSlash = pathAfterProtocol.indexOf('/')
        if (firstSlash == -1) return null

        val pathAfterHost = pathAfterProtocol.substring(firstSlash + 1)
        val secondSlash = pathAfterHost.indexOf('/')
        if (secondSlash == -1) "" else pathAfterHost.substring(secondSlash + 1)
      }
      else -> filePath.trim('/')
    }
  }

  private suspend fun getFileSizeSMB(streamInfo: StreamInfo): Long {
    val smbClient = streamInfo.client as? SmbClient ?: return -1L

    return try {
      Log.d(TAG, "SMB getFileSize called (shared session)")
      Log.d(TAG, "  File path: ${streamInfo.filePath}")

      smbClient.withSharedSession { _, shareName ->
        val relativePath = parseSmbRelativePath(streamInfo.filePath)
        if (relativePath == null) {
          Log.e(TAG, "Invalid SMB path format: ${streamInfo.filePath}")
          return@withSharedSession -1L
        }

        val decodedRelativePath = decodeSmbPath(relativePath)
        Log.d(TAG, "  Final: share=$shareName, relativePath=$decodedRelativePath")

        smbClient.connectShare().getFileInformation(decodedRelativePath).standardInformation.endOfFile
      }
    } catch (e: Exception) {
      Log.e(TAG, "SMB getFileSize error: ${e.message}", e)
      -1L
    }
  }

  /**
   * Get file size using FTP listFiles command
   */
  private suspend fun getFileSizeFTP(streamInfo: StreamInfo): Long {
    val ftpClient = org.apache.commons.net.ftp.FTPClient()

    // Set UTF-8 encoding for proper handling of non-English characters
    ftpClient.controlEncoding = "UTF-8"
    ftpClient.setConnectTimeout(10000)

    try {
      // Connect
      ftpClient.connect(streamInfo.connection.host, streamInfo.connection.port)

      if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(ftpClient.replyCode)) {
        ftpClient.disconnect()
        return -1L
      }

      // Login
      val loginSuccess = if (streamInfo.connection.isAnonymous) {
        ftpClient.login("anonymous", "")
      } else {
        ftpClient.login(streamInfo.connection.username, streamInfo.connection.password)
      }

      if (!loginSuccess) {
        ftpClient.disconnect()
        return -1L
      }

      // Set binary mode
      ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)

      // Try to enable UTF-8 mode on the server (RFC 2640)
      try {
        ftpClient.sendCommand("OPTS UTF8 ON")
      } catch (_: Exception) {
        // Server may not support UTF-8 mode, continue anyway
      }

      // Change to base directory if needed
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty()) {
        ftpClient.changeWorkingDirectory(streamInfo.connection.path)
      }

      // Determine the file path to use
      val pathsToTry = mutableListOf<String>()
      pathsToTry.add(streamInfo.filePath)
      if (streamInfo.filePath.startsWith("/")) {
        pathsToTry.add(streamInfo.filePath.substring(1))
      }
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty() &&
        streamInfo.filePath.startsWith(streamInfo.connection.path)
      ) {
        val relativePath = streamInfo.filePath.substring(streamInfo.connection.path.length).trimStart('/')
        if (relativePath.isNotEmpty()) {
          pathsToTry.add(relativePath)
        }
      }

      // Try to get file size using listFiles
      for (path in pathsToTry) {
        try {
          val files = ftpClient.listFiles(path)
          if (files.isNotEmpty() && !files[0].isDirectory) {
            val size = files[0].size
            ftpClient.disconnect()
            return size
          }
        } catch (e: Exception) {
          // Try next path
        }
      }

      ftpClient.disconnect()
      return -1L

    } catch (e: Exception) {
      try {
        ftpClient.disconnect()
      } catch (_: Exception) {
      }
      return -1L
    }
  }

  private fun getStream(streamInfo: StreamInfo): InputStream? {
    return runBlocking {
      try {
        // Connect if needed
        if (!streamInfo.client.isConnected()) {
          streamInfo.client.connect().getOrThrow()
        }

        // Get file stream
        val result = streamInfo.client.getFileStream(streamInfo.filePath)
        result.getOrNull()
      } catch (e: Exception) {
        Log.e(TAG, "Error getting stream", e)
        null
      }
    }
  }

  private fun getStreamWithOffset(
    streamInfo: StreamInfo,
    offset: Long,
    contentLength: Long,
  ): InputStream? {
    return runBlocking {
      try {
        when (streamInfo.client) {
          is xyz.mpv.rex.ui.browser.networkstreaming.clients.SmbClient -> {
            getStreamWithOffsetSMB(streamInfo, offset, contentLength)
          }

          is xyz.mpv.rex.ui.browser.networkstreaming.clients.FtpClient -> {
            getStreamWithOffsetFTP(streamInfo, offset)
          }

          is xyz.mpv.rex.ui.browser.networkstreaming.clients.WebDavClient -> {
            getStreamWithOffsetWebDAV(streamInfo, offset)
          }

          else -> {
            getStreamWithOffsetGeneric(streamInfo, offset)
          }
        }
      } catch (e: Exception) {
        null
      }
    }
  }

  /**
   * Get FTP stream with offset using REST command (efficient seeking)
   */
  private suspend fun getStreamWithOffsetFTP(streamInfo: StreamInfo, offset: Long): InputStream? {
    // Create a new FTP client for this specific range request
    val ftpClient = org.apache.commons.net.ftp.FTPClient()

    // Set UTF-8 encoding for proper handling of non-English characters
    ftpClient.controlEncoding = "UTF-8"
    ftpClient.setConnectTimeout(10000)
    ftpClient.setDataTimeout(30000)
    ftpClient.controlKeepAliveTimeout = 300

    try {
      // Connect
      ftpClient.connect(streamInfo.connection.host, streamInfo.connection.port)

      if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(ftpClient.replyCode)) {
        ftpClient.disconnect()
        return null
      }

      // Login
      val loginSuccess = if (streamInfo.connection.isAnonymous) {
        ftpClient.login("anonymous", "")
      } else {
        ftpClient.login(streamInfo.connection.username, streamInfo.connection.password)
      }

      if (!loginSuccess) {
        ftpClient.disconnect()
        return null
      }

      // Set binary mode and passive mode
      ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
      ftpClient.enterLocalPassiveMode()

      // Try to enable UTF-8 mode on the server (RFC 2640)
      try {
        ftpClient.sendCommand("OPTS UTF8 ON")
      } catch (_: Exception) {
        // Server may not support UTF-8 mode, continue anyway
      }

      ftpClient.setBufferSize(1024 * 64)

      // Change to base directory if needed
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty()) {
        ftpClient.changeWorkingDirectory(streamInfo.connection.path)
      }

      // Set restart position (offset) - this is the key for efficient seeking!
      if (offset > 0) {
        ftpClient.setRestartOffset(offset)
      }

      // Determine the file path to use
      val pathsToTry = mutableListOf<String>()
      pathsToTry.add(streamInfo.filePath)
      if (streamInfo.filePath.startsWith("/")) {
        pathsToTry.add(streamInfo.filePath.substring(1))
      }
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty() &&
        streamInfo.filePath.startsWith(streamInfo.connection.path)
      ) {
        val relativePath = streamInfo.filePath.substring(streamInfo.connection.path.length).trimStart('/')
        if (relativePath.isNotEmpty()) {
          pathsToTry.add(relativePath)
        }
      }

      // Try to retrieve file stream
      var rawStream: java.io.InputStream? = null
      for (path in pathsToTry) {
        rawStream = ftpClient.retrieveFileStream(path)
        if (rawStream != null) {
          break
        }
      }

      if (rawStream == null) {
        ftpClient.disconnect()
        return null
      }

      // Wrap stream to handle cleanup
      val wrappedStream = object : java.io.InputStream() {
        override fun read(): Int = rawStream.read()
        override fun read(b: ByteArray): Int = rawStream.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
        override fun available(): Int = rawStream.available()

        override fun close() {
          try {
            rawStream.close()
          } catch (e: Exception) {
            // Ignore
          }
          try {
            if (ftpClient.isConnected) {
              ftpClient.completePendingCommand()
              ftpClient.logout()
              ftpClient.disconnect()
            }
          } catch (e: Exception) {
            // Ignore
          }
        }
      }

      // Buffer the socket stream so NanoHTTPD's 16 KiB response reads are
      // served from memory instead of one syscall per chunk.
      return BufferedInputStream(wrappedStream, 1024 * 1024)

    } catch (e: Exception) {
      try {
        ftpClient.disconnect()
      } catch (_: Exception) {
      }
      return null
    }
  }

  /**
   * Get WebDAV stream with offset using HTTP Range header (efficient seeking)
   */
  private suspend fun getStreamWithOffsetWebDAV(streamInfo: StreamInfo, offset: Long): InputStream? {
    try {
      val protocol = if (streamInfo.connection.useHttps) "https" else "http"
      val cleanBasePath = streamInfo.connection.path.trimEnd('/')
      val cleanFilePath = if (streamInfo.filePath.startsWith("/")) streamInfo.filePath else "/${streamInfo.filePath}"
      val url = "$protocol://${streamInfo.connection.host}:${streamInfo.connection.port}$cleanBasePath$cleanFilePath"

      Log.d(TAG, "WebDAV stream request - Protocol: $protocol, URL: $url")

      // Use OkHttp directly to add Range header support
      val okHttpClient = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
          val request = chain.request().newBuilder()
            .addHeader("Range", "bytes=$offset-")
            .build()
          chain.proceed(request)
        }
        .build()

      // Build the request
      val requestBuilder = okhttp3.Request.Builder()
        .url(url)
        .get()

      // Add auth if needed
      if (!streamInfo.connection.isAnonymous) {
        val credentials = okhttp3.Credentials.basic(streamInfo.connection.username, streamInfo.connection.password)
        requestBuilder.addHeader("Authorization", credentials)
      }

      val request = requestBuilder.build()
      val response = okHttpClient.newCall(request).execute()

      if (!response.isSuccessful && response.code != 206) {
        response.close()
        return null
      }

      val rawStream = response.body?.byteStream()
      if (rawStream == null) {
        response.close()
        return null
      }

      // Wrap stream to handle cleanup
      val wrappedStream = object : java.io.InputStream() {
        override fun read(): Int = rawStream.read()
        override fun read(b: ByteArray): Int = rawStream.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
        override fun available(): Int = rawStream.available()

        override fun close() {
          try {
            rawStream.close()
          } catch (e: Exception) {
            // Ignore
          }
          try {
            response.close()
          } catch (e: Exception) {
            // Ignore
          }
        }
      }

      // Buffer the response stream so NanoHTTPD's 16 KiB response reads are
      // served from memory instead of one network read per chunk.
      return BufferedInputStream(wrappedStream, 1024 * 1024)

    } catch (e: Exception) {
      return null
    }
  }

  private suspend fun getStreamWithOffsetSMB(
    streamInfo: StreamInfo,
    offset: Long,
    contentLength: Long,
  ): InputStream? {
    val smbClient = streamInfo.client as? SmbClient ?: return null

    try {
      Log.d(TAG, "SMB getStreamWithOffset called, offset=$offset")
      Log.d(TAG, "  Connection path: ${streamInfo.connection.path}")
      Log.d(TAG, "  File path: ${streamInfo.filePath}")

      // Isolate the relative file path within the share
      val relativePath = parseSmbRelativePath(streamInfo.filePath)
      if (relativePath == null) {
        Log.e(TAG, "Invalid SMB path format: ${streamInfo.filePath}")
        return null
      }

      // Decode URL-encoded characters so SMBJ can resolve the literal disk path
      val decodedRelativePath = decodeSmbPath(relativePath)
      Log.d(TAG, "  Final: relativePath=$decodedRelativePath")

      return smbClient.withSharedSession { _, _ ->
        val diskShare = smbClient.connectShare()
        val file = diskShare.openFile(
          decodedRelativePath,
          EnumSet.of(AccessMask.GENERIC_READ),
          null,
          EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
          SMB2CreateDisposition.FILE_OPEN,
          null,
        )

        Log.d(TAG, "  Stream created successfully starting at offset $offset, contentLength=$contentLength")

        PrefetchingSmbInputStream(
          fileHandle = file,
          initialOffset = offset,
          contentLength = contentLength,
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "SMB getStreamWithOffset error: ${e.message}", e)
      return null
    }
  }

  /**
   * Generic stream with offset using skip (less efficient, for other protocols)
   */
  private suspend fun getStreamWithOffsetGeneric(streamInfo: StreamInfo, offset: Long): InputStream? {
    val client = NetworkClientFactory.createClient(streamInfo.connection)
    client.connect().getOrThrow()

    val stream = client.getFileStream(streamInfo.filePath).getOrNull()

    if (stream != null && offset > 0) {
      var remaining = offset
      val buffer = ByteArray(8192)

      while (remaining > 0) {
        val toSkip = minOf(remaining, buffer.size.toLong()).toInt()
        val skipped = stream.read(buffer, 0, toSkip)
        if (skipped <= 0) {
          stream.close()
          client.disconnect()
          return null
        }
        remaining -= skipped
      }
    }

    return stream
  }

  /**
   * Serves an SMB file through a background prefetcher.
   *
   * NanoHTTPD 2.3.1 copies response bodies out in hard-coded 16 KiB chunks;
   * without prefetching, every SMB read would inherit that granularity. The
   * prefetch thread instead pulls [BLOCK_SIZE] blocks into [queue],
   * decoupling the consumer's read granularity from the SMB read granularity,
   * and self-limits to [contentLength] so reads never go past the requested
   * range.
   *
   * [close] releases only the SMB file handle: the DiskShare is cached on
   * the session and owned by SmbClient.
   */
  private class PrefetchingSmbInputStream(
    private val fileHandle: com.hierynomus.smbj.share.File,
    initialOffset: Long,
    private val contentLength: Long,
  ) : InputStream() {

    companion object {
      private const val TAG = "NetworkStreamingProxy"
      // Must stay <= SmbClient's read buffer size or each block silently
      // degrades into multiple smaller SMB2 reads.
      private const val BLOCK_SIZE = 4 * 1024 * 1024
      private const val FIRST_BLOCK_SIZE = 1024 * 1024
      private const val PREFETCH_DEPTH = 2

      // Zero-length sentinel matched by reference (===); the filled <= 0
      // guard in prefetchLoop keeps every other queue entry non-empty.
      private val EOF_MARKER = ByteArray(0)
    }

    private val queue = ArrayBlockingQueue<ByteArray>(PREFETCH_DEPTH)

    private var currentBlock: ByteArray? = null
    private var blockPos = 0
    private var eof = false
    private val closed = AtomicBoolean(false)

    private var readPosition = initialOffset
    private var bytesReadFromFile = 0L

    private val prefetchThread =
      Thread({ prefetchLoop() }, "SmbPrefetch-${System.identityHashCode(this)}").apply {
        isDaemon = true
        start()
      }

    private fun prefetchLoop() {
      try {
        while (!closed.get()) {
          val remaining = contentLength - bytesReadFromFile
          if (remaining <= 0) break
          val blockSize = if (bytesReadFromFile == 0L) FIRST_BLOCK_SIZE else BLOCK_SIZE
          val requestLen = minOf(blockSize.toLong(), remaining).toInt()
          val block = ByteArray(requestLen)
          var filled = 0
          var hitEof = false
          while (filled < requestLen && !closed.get()) {
            val n = fileHandle.read(block, readPosition, filled, requestLen - filled)
            if (n <= 0) { hitEof = true; break }
            readPosition += n
            bytesReadFromFile += n
            filled += n
          }
          // A zero-length block must never be enqueued: EOF_MARKER is the
          // only zero-length array the queue may hold, and serving an empty
          // block would make read() return an illegal 0.
          if (filled <= 0) break
          queue.put(if (filled == block.size) block else block.copyOf(filled))
          if (hitEof) break
        }
        if (!closed.get()) {
          runCatching { queue.put(EOF_MARKER) }
        }
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      } catch (e: Exception) {
        Log.e(TAG, "SMB prefetch error: ${e.message}", e)
        if (!closed.get()) {
          runCatching { queue.put(EOF_MARKER) }
        }
      }
    }

    override fun read(): Int {
      val buf = ByteArray(1)
      val n = read(buf, 0, 1)
      return if (n == 1) buf[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    @Synchronized
    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (closed.get()) return -1
      if (len == 0) return 0

      // Ensure we have a current block to serve from.
      while (currentBlock == null) {
        if (eof) return -1
        val block =
          try {
            queue.take()
          } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1
          }
        if (block === EOF_MARKER) {
          eof = true
          return -1
        }
        currentBlock = block
        blockPos = 0
      }

      val block = currentBlock!!
      val n = minOf(len, block.size - blockPos)
      System.arraycopy(block, blockPos, b, off, n)
      blockPos += n
      if (blockPos >= block.size) {
        currentBlock = null
      }
      return n
    }

    override fun available(): Int {
      if (closed.get() || eof) return 0
      val inCurrent = currentBlock?.let { it.size - blockPos } ?: 0
      return inCurrent + queue.sumOf { it.size }
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        // Wake the prefetch thread if it is blocked in queue.put().
        prefetchThread.interrupt()
        // Wake a consumer blocked in queue.take().
        queue.drainTo(ArrayList())
        queue.offer(EOF_MARKER)
        runCatching { fileHandle.close() }
      }
    }
  }
}
