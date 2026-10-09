package com.example.dropin.webrtc

import android.content.Context
import android.util.Log
import com.example.dropin.model.SharedFile
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections

class WebRtcManager(private val context: Context, private val pin: String) {

    private val TAG = "WebRTC_Dropin"
    private val VERCEL_API = "https://dropin-web-kappa.vercel.app/api/webrtc"
    
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var isRemoteDescriptionSet = false
    private val pendingWebCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())
    
    interface WebRtcListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    var listener: WebRtcListener? = null
    var sharedFiles: List<SharedFile> = emptyList()

    fun start() {
        Log.d(TAG, "Starting WebRtcManager for PIN: $pin")
        initializeWebRtc()
        setupPeerConnection()
        startSignaling()
    }

    private fun initializeWebRtc() {
        if (factory != null) return
        
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    private fun setupPeerConnection() {
        try {
            dataChannel?.close()
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) { }

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        listener?.onConnected()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        listener?.onDisconnected()
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE Gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    Log.d(TAG, "Local ICE Candidate discovered: ${candidate.sdpMid}")
                    sendIceCandidateToVercel(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {
                Log.d(TAG, "DataChannel received from remote peer: ${dc?.label()}")
                dataChannel = dc
                if (dc != null) {
                    setupDataChannel(dc)
                }
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }

    private fun setupDataChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d(TAG, "DataChannel state changed: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null || buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val message = String(bytes, Charsets.UTF_8)
                Log.d(TAG, "Received message via DataChannel: $message")

                try {
                    val json = JSONObject(message)
                    if (json.optString("type") == "download") {
                        val fileId = json.getString("fileId")
                        sendFileOverDataChannel(fileId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling DataChannel message", e)
                }
            }
        })
    }

    private fun sendFileOverDataChannel(fileId: String) {
        val file = sharedFiles.find { it.id == fileId }
        if (file == null) {
            Log.e(TAG, "File not found for ID: $fileId")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Streaming file: ${file.name} (${file.size} bytes)")
                
                // 1. Send File Start Header
                val header = JSONObject().apply {
                    put("type", "file_start")
                    put("fileId", fileId)
                    put("name", file.name)
                    put("size", file.size)
                }.toString()

                val headerBuffer = ByteBuffer.wrap(header.toByteArray(Charsets.UTF_8))
                dataChannel?.send(DataChannel.Buffer(headerBuffer, false))

                delay(50)

                // 2. Send Binary Chunks (32 KB chunks)
                val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                if (inputStream != null) {
                    val chunk = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(chunk).also { bytesRead = it } != -1) {
                        // Backpressure control: wait if buffer exceeds 512 KB
                        while ((dataChannel?.bufferedAmount() ?: 0) > 512 * 1024) {
                            delay(10)
                        }
                        val chunkBuffer = ByteBuffer.wrap(chunk, 0, bytesRead)
                        dataChannel?.send(DataChannel.Buffer(chunkBuffer, true))
                    }
                    inputStream.close()
                }

                delay(50)

                // 3. Send File End Footer
                val footer = JSONObject().apply {
                    put("type", "file_end")
                    put("fileId", fileId)
                }.toString()

                val footerBuffer = ByteBuffer.wrap(footer.toByteArray(Charsets.UTF_8))
                dataChannel?.send(DataChannel.Buffer(footerBuffer, false))
                Log.d(TAG, "Finished streaming file: ${file.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed streaming file", e)
            }
        }
    }

    private fun startSignaling() {
        scope.launch {
            // Step 1: Upload Metadata so Web client sees files immediately
            val metadataArray = JSONArray()
            sharedFiles.forEach { f ->
                metadataArray.put(JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("size", f.size)
                })
            }
            postToVercel("action=setMetadata", JSONObject().put("metadata", metadataArray))
            Log.d(TAG, "Metadata uploaded for PIN: $pin")

            // Step 2: Signaling Polling Loop
            var lastOfferSdp: String? = null
            var lastOfferId: String? = null
            var processedCandidatesCount = 0

            while (isActive) {
                try {
                    val responseText = httpGet("$VERCEL_API?pin=$pin")
                    if (responseText != null) {
                        val data = JSONObject(responseText)

                        val hasAnswer = data.has("answer") && !data.isNull("answer")

                        // Check for Offer
                        if (data.has("offer") && !data.isNull("offer")) {
                            val offerJson = data.getJSONObject("offer")
                            val sdpString = offerJson.optString("sdp", "")
                            val offerId = offerJson.optString("id", "")

                            val isNewOffer = sdpString.isNotBlank() && (
                                !hasAnswer || 
                                (offerId.isNotBlank() && offerId != lastOfferId) || 
                                sdpString != lastOfferSdp
                            )

                            if (isNewOffer) {
                                lastOfferSdp = sdpString
                                lastOfferId = offerId
                                Log.d(TAG, "Processing SDP Offer from Web (hasAnswer=$hasAnswer, offerId=$offerId)")

                                isRemoteDescriptionSet = false
                                synchronized(pendingWebCandidates) {
                                    pendingWebCandidates.clear()
                                }
                                processedCandidatesCount = 0

                                // Re-initialize peer connection cleanly for this offer
                                setupPeerConnection()

                                val sessionDescription = SessionDescription(
                                    SessionDescription.Type.OFFER,
                                    sdpString
                                )

                                peerConnection?.setRemoteDescription(object : CustomSdpObserver() {
                                    override fun onSetSuccess() {
                                        Log.d(TAG, "Remote Description set successfully on Android")
                                        isRemoteDescriptionSet = true

                                        // Drain queued ICE candidates
                                        synchronized(pendingWebCandidates) {
                                            for (cand in pendingWebCandidates) {
                                                Log.d(TAG, "Applying queued ICE Candidate: ${cand.sdpMid}")
                                                peerConnection?.addIceCandidate(cand)
                                            }
                                            pendingWebCandidates.clear()
                                        }

                                        // Create Answer
                                        val constraints = MediaConstraints()
                                        peerConnection?.createAnswer(object : CustomSdpObserver() {
                                            override fun onCreateSuccess(answerDesc: SessionDescription?) {
                                                if (answerDesc != null) {
                                                    peerConnection?.setLocalDescription(object : CustomSdpObserver() {
                                                        override fun onSetSuccess() {
                                                            Log.d(TAG, "Local Description set successfully on Android")
                                                        }
                                                    }, answerDesc)
                                                    
                                                    val answerJson = JSONObject().apply {
                                                        put("type", "answer")
                                                        put("sdp", answerDesc.description)
                                                    }
                                                    scope.launch {
                                                        postToVercel("action=setAnswer", JSONObject().put("answer", answerJson))
                                                        Log.d(TAG, "SDP Answer posted to Vercel")
                                                    }
                                                }
                                            }

                                            override fun onCreateFailure(err: String?) {
                                                Log.e(TAG, "Failed to create SDP Answer: $err")
                                            }
                                        }, constraints)
                                    }

                                    override fun onSetFailure(err: String?) {
                                        Log.e(TAG, "Failed to set Remote Description: $err")
                                    }
                                }, sessionDescription)
                            }
                        }

                        // Check for Web ICE Candidates
                        if (data.has("candidates_web")) {
                            val candidatesArray = data.getJSONArray("candidates_web")
                            while (processedCandidatesCount < candidatesArray.length()) {
                                val cItem = candidatesArray.get(processedCandidatesCount)
                                val cJson = if (cItem is JSONObject) cItem else JSONObject(cItem.toString())
                                val candStr = cJson.optString("candidate", "")
                                if (candStr.isNotBlank()) {
                                    val sdpMid = cJson.optString("sdpMid", "0")
                                    val sdpMLineIndex = cJson.optInt("sdpMLineIndex", 0)
                                    try {
                                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candStr)
                                        if (isRemoteDescriptionSet) {
                                            Log.d(TAG, "Applying incoming Web ICE Candidate directly: $sdpMid")
                                            peerConnection?.addIceCandidate(candidate)
                                        } else {
                                            Log.d(TAG, "Queueing incoming Web ICE Candidate: $sdpMid")
                                            pendingWebCandidates.add(candidate)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error adding ice candidate", e)
                                    }
                                }
                                processedCandidatesCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Signaling loop error", e)
                }
                delay(1000)
            }
        }
    }

    private fun sendIceCandidateToVercel(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("sdpMid", candidate.sdpMid ?: "0")
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        scope.launch(Dispatchers.IO) {
            postToVercel("action=addCandidate&role=android", JSONObject().put("candidate", json))
        }
    }

    private suspend fun httpGet(urlString: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun postToVercel(query: String, body: JSONObject) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$VERCEL_API?pin=$pin&$query")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream?.use { it.readBytes() }
            } else {
                conn.errorStream?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed posting to Vercel query: $query", e)
        } finally {
            conn?.disconnect()
        }
    }

    open class CustomSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    fun stop() {
        Log.d(TAG, "Stopping WebRtcManager")
        scope.cancel()
        try {
            dataChannel?.close()
            peerConnection?.close()
            peerConnection?.dispose()
            factory?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC resources", e)
        }
    }
}
