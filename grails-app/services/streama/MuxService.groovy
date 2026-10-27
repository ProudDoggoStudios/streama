package streama

import grails.transaction.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Client for the Mux video API (https://docs.mux.com/api-reference).
 *
 * Videos are uploaded straight to Mux and played back from Mux, so the application no
 * longer has to keep the video files on disk. Mux ingests an upload asynchronously:
 * a File row is created right after the upload and its playback id is filled in later
 * by resolveAsset() (called from MuxController.fileStatus() while the UI polls).
 *
 * Credentials are read from the Settings screen ("Mux Token ID" / "Mux Token Secret")
 * and fall back to the MUX_TOKEN_ID / MUX_TOKEN_SECRET environment variables.
 */
@Transactional
class MuxService {

  static final String API_BASE = 'https://api.mux.com'
  static final String STREAM_BASE = 'https://stream.mux.com'

  /** Public playback URL (HLS) of a Mux asset. */
  static String hlsUrl(String playbackId) {
    return playbackId ? "${STREAM_BASE}/${playbackId}.m3u8" : null
  }

  String getTokenId() {
    return Settings.findBySettingsKey('Mux Token ID')?.value ?: System.getenv('MUX_TOKEN_ID')
  }

  String getTokenSecret() {
    return Settings.findBySettingsKey('Mux Token Secret')?.value ?: System.getenv('MUX_TOKEN_SECRET')
  }

  boolean isConfigured() {
    return tokenId && tokenSecret
  }

  Map checkCredentials(String tokenId = this.tokenId, String tokenSecret = this.tokenSecret) {
    if (!tokenId || !tokenSecret) {
      return [valid: false, message: 'Both the Mux Token ID and the Mux Token Secret are required.']
    }

    def response = apiRequest('GET', '/video/v1/videos?limit=1', null, tokenId, tokenSecret)

    if (response.status == 200) {
      return [valid: true, message: 'The Mux credentials are valid and can be used!']
    }
    if (response.status == 401 || response.status == 403) {
      return [valid: false, message: 'Invalid credentials for Mux: The Token ID or the Token Secret is incorrect.']
    }
    return [valid: false, message: "Mux could not be reached (HTTP ${response.status}). Please try again later."]
  }

  /**
   * Uploads an uploaded video file to Mux and returns the File row that links to the asset.
   * The asset is not playable until Mux has finished ingesting it (see resolveAsset).
   */
  Map uploadVideo(file, Map params = [:]) {
    def uploadResponse = apiRequest('POST', '/video/v1/uploads', [
        new_asset_settings: [playback_policy: ['public']],
        cors_origin       : '*'
    ])

    def upload = uploadResponse.data?.data
    if (uploadResponse.status != 201 || !upload?.url) {
      log.error("Mux upload could not be created (HTTP ${uploadResponse.status}): ${uploadResponse.data}")
      return [error: "The video could not be uploaded to Mux (HTTP ${uploadResponse.status})."]
    }

    def uploadResult = sendFileToMux(upload.url, file)
    if (uploadResult.error) {
      return uploadResult
    }

    def originalFilename = file.originalFilename
    def extensionIndex = originalFilename.lastIndexOf('.')
    def extension = extensionIndex > 0 ? originalFilename[extensionIndex..-1].toLowerCase() : ''
    def nameWithoutExtension = extensionIndex > 0 ? originalFilename[0..(extensionIndex - 1)] : originalFilename

    File fileInstance = new File(muxUploadId: upload.id, muxStatus: 'processing')
    fileInstance.originalFilename = originalFilename
    fileInstance.contentType = file.contentType
    fileInstance.extension = extension
    fileInstance.size = file.size
    fileInstance.label = nameWithoutExtension
    fileInstance.save(failOnError: true)

    resolveAsset(fileInstance)

    return [file: fileInstance]
  }

  /**
   * Asks Mux how an upload/asset is doing and stores the playback id as soon as it is ready.
   * Does nothing for File rows that were not uploaded to Mux.
   */
  Map resolveAsset(File fileInstance) {
    if (!fileInstance?.muxUploadId && !fileInstance?.muxAssetId) {
      return [:]
    }

    if (!fileInstance.muxAssetId) {
      def uploadResponse = apiRequest('GET', "/video/v1/uploads/${fileInstance.muxUploadId}")
      def assetId = uploadResponse.data?.data?.asset_id
      if (!assetId) {
        return [status: fileInstance.muxStatus]
      }
      fileInstance.muxAssetId = assetId
    }

    def assetResponse = apiRequest('GET', "/video/v1/assets/${fileInstance.muxAssetId}")
    def asset = assetResponse.data?.data
    if (!asset) {
      log.error("Mux asset ${fileInstance.muxAssetId} could not be loaded (HTTP ${assetResponse.status})")
      fileInstance.save(flush: true)
      return [status: fileInstance.muxStatus]
    }

    fileInstance.muxStatus = asset.status
    if (asset.status == 'ready') {
      def playbackId = asset.playback_ids?.find { it.policy == 'public' }?.id ?: asset.playback_ids?.getAt(0)?.id
      if (playbackId) {
        fileInstance.muxPlaybackId = playbackId
      }
    }
    fileInstance.save(flush: true)

    return [status: fileInstance.muxStatus, playbackId: fileInstance.muxPlaybackId]
  }

  /**
   * Removes the asset from Mux, so deleting a video in Streama does not leave it behind
   * (and does not keep costing money) on Mux.
   */
  Map deleteAsset(String assetId) {
    if (!assetId || !isConfigured()) {
      return [:]
    }
    def response = apiRequest('DELETE', "/video/v1/assets/${assetId}")
    if (response.status != 204 && response.status != 200 && response.status != 404) {
      log.error("Mux asset ${assetId} could not be deleted (HTTP ${response.status})")
    }
    return [status: response.status]
  }

  private Map sendFileToMux(String uploadUrl, file) {
    HttpURLConnection connection = null
    try {
      connection = (HttpURLConnection) new URL(uploadUrl).openConnection()
      connection.setRequestMethod('PUT')
      connection.setDoOutput(true)
      connection.setFixedLengthStreamingMode(file.size)
      connection.setRequestProperty('Content-Type', file.contentType ?: 'application/octet-stream')
      connection.setConnectTimeout(30000)
      connection.setReadTimeout(60 * 60 * 1000)

      file.inputStream.withStream { input ->
        connection.outputStream.withStream { output ->
          byte[] buffer = new byte[64 * 1024]
          int read
          while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read)
          }
        }
      }

      int status = connection.responseCode
      if (status == 200 || status == 201 || status == 204) {
        return [:]
      }

      log.error("Mux rejected the upload (HTTP ${status}): ${connection.errorStream?.getText('UTF-8')}")
      return [error: "The video could not be uploaded to Mux (HTTP ${status})."]
    }
    catch (Exception e) {
      log.error("Upload to Mux failed: ${e.message}", e)
      return [error: 'The video could not be uploaded to Mux. Please try again.']
    }
    finally {
      connection?.disconnect()
    }
  }

  Map apiRequest(String method, String path, Map body = null, String tokenId = this.tokenId, String tokenSecret = this.tokenSecret) {
    if (!tokenId || !tokenSecret) {
      return [status: 0, data: null, error: 'Mux is not configured.']
    }

    HttpURLConnection connection = null
    try {
      connection = (HttpURLConnection) new URL(API_BASE + path).openConnection()
      connection.setRequestMethod(method)
      connection.setRequestProperty('Authorization', 'Basic ' + "${tokenId}:${tokenSecret}".bytes.encodeBase64().toString())
      connection.setRequestProperty('Content-Type', 'application/json')
      connection.setConnectTimeout(15000)
      connection.setReadTimeout(30000)

      if (body) {
        connection.setDoOutput(true)
        connection.outputStream.withWriter('UTF-8') { it.write(JsonOutput.toJson(body)) }
      }

      int status = connection.responseCode
      def responseText = (status >= 400 ? connection.errorStream : connection.inputStream)?.getText('UTF-8')

      return [status: status, data: responseText ? new JsonSlurper().parseText(responseText) : null]
    }
    catch (Exception e) {
      log.error("Mux API request ${method} ${path} failed: ${e.message}", e)
      return [status: 0, data: null, error: e.message]
    }
    finally {
      connection?.disconnect()
    }
  }
}
