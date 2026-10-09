package com.example.dropin.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dropin.model.SharedFile
import com.example.dropin.service.FileTransferService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var transferService: FileTransferService? = null
    private val _isServiceBound = MutableStateFlow(false)
    private var pendingFilesToStart: List<SharedFile>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? FileTransferService.LocalBinder
            transferService = binder?.getService()
            _isServiceBound.value = true
            
            pendingFilesToStart?.let { files ->
                transferService?.startTransferSession(files)
                pendingFilesToStart = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            _isServiceBound.value = false
        }
    }

    val isRunning: StateFlow<Boolean> = _isServiceBound.flatMapLatest { bound ->
        if (bound) transferService?.isRunning ?: flowOf(false) else flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val serverUrl: StateFlow<String?> = _isServiceBound.flatMapLatest { bound ->
        if (bound) transferService?.serverUrl ?: flowOf(null) else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sessionToken: StateFlow<String?> = _isServiceBound.flatMapLatest { bound ->
        if (bound) transferService?.sessionToken ?: flowOf(null) else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sharedFiles: StateFlow<List<SharedFile>> = _isServiceBound.flatMapLatest { bound ->
        if (bound) transferService?.sharedFiles ?: flowOf(emptyList()) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        bindService()
    }

    fun bindService() {
        val app = getApplication<Application>()
        Intent(app, FileTransferService::class.java).also { intent ->
            app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun startServer(context: Context, uris: List<Uri>) {
        val files = uris.mapNotNull { uri ->
            try {
                // Try taking persistable read permission if available
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not a persistable document uri
            }
            getFileMetadata(context, uri)
        }
        
        if (files.isNotEmpty()) {
            val app = getApplication<Application>()
            val intent = Intent(app, FileTransferService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }

            if (transferService != null) {
                transferService?.startTransferSession(files)
            } else {
                pendingFilesToStart = files
                bindService()
            }
        }
    }

    fun stopServer() {
        transferService?.stopTransferSession()
    }

    private fun getFileMetadata(context: Context, uri: Uri): SharedFile? {
        val contentResolver = context.contentResolver
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex != -1) it.getString(nameIndex) else "File"
                    val size = if (sizeIndex != -1) it.getLong(sizeIndex) else 0L
                    val mimeType = contentResolver.getType(uri)
                    SharedFile(UUID.randomUUID().toString(), uri, name ?: "File", size, mimeType)
                } else null
            }
        } catch (e: Exception) {
            val name = uri.lastPathSegment ?: "File"
            val mimeType = contentResolver.getType(uri)
            SharedFile(UUID.randomUUID().toString(), uri, name, 0L, mimeType)
        }
    }

    override fun onCleared() {
        val app = getApplication<Application>()
        if (_isServiceBound.value) {
            try {
                app.unbindService(serviceConnection)
            } catch (e: Exception) { }
            _isServiceBound.value = false
        }
        super.onCleared()
    }
}
