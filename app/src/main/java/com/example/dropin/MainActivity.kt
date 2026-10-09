package com.example.dropin

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dropin.ui.MainViewModel
import com.example.dropin.ui.theme.DropinTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DropinTheme(darkTheme = true) {
                val viewModel: MainViewModel = viewModel()
                HomeScreen(viewModel)
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val isRunning by viewModel.isRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val sessionToken by viewModel.sessionToken.collectAsState()
    val sharedFiles by viewModel.sharedFiles.collectAsState()
    val context = LocalContext.current

    var showIntro by remember { mutableStateOf(true) }
    var logoVisible by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(300)
        logoVisible = true
        delay(1800)
        logoVisible = false
        delay(400)
        showIntro = false
    }

    val bgColorCenter by animateColorAsState(
        targetValue = if (showIntro) Color.Black else if (isRunning) Color(0xFF003F34) else Color(0xFF9E1B43),
        animationSpec = tween(1000), label = "bgTransition"
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> 
            if (uris.isNotEmpty()) {
                viewModel.startServer(context, uris)
            }
        }
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    )

    fun launchPickerWithPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(bgColorCenter.copy(alpha = 0.7f), Color.Black),
                    center = Offset(0.5f, 0.5f),
                    radius = 1800f
                )
            )
        )

        NoiseEffect(intensity = 0.08f)

        if (showIntro) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedVisibility(
                    visible = logoVisible,
                    enter = fadeIn(tween(800)) + scaleIn(tween(800), initialScale = 0.85f),
                    exit = fadeOut(tween(400))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.fiver_logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(160.dp).clip(CircleShape)
                    )
                }
            }
        } else {
            Column(modifier = Modifier.statusBarsPadding().fillMaxSize()) {
                HeaderSection()
                Spacer(modifier = Modifier.height(24.dp))

                if (!isRunning) {
                    EmptyState(onSelectFiles = { launchPickerWithPermissions() })
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ServerStatusCard(serverUrl, sessionToken, onQrClick = { showQrDialog = true })
                        Spacer(modifier = Modifier.height(32.dp))
                        AssetsHeader(sharedFiles.size)
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            items(sharedFiles, key = { it.id }) { file ->
                                FileListItem(file.name, file.size, file.mimeType)
                            }
                        }
                    }
                }
            }
            
            Text(
                "crafted by ashutosh jena",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.3f),
                letterSpacing = 1.sp
            )
        }

        if (isRunning && !showIntro) {
            StopButton(onClick = { viewModel.stopServer() }, modifier = Modifier.align(Alignment.BottomCenter))
        }

        if (showQrDialog && serverUrl != null) {
            QrCodeDialog(url = serverUrl!!, onDismiss = { showQrDialog = false })
        }
    }
}

@Composable
fun HeaderSection() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 36.dp, start = 24.dp, end = 24.dp)) {
        Text(
            "DROPIN", 
            fontWeight = FontWeight.Black, 
            color = Color.White,
            fontSize = 32.sp,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 4.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "your airdrop buddy !!",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MADE IN INDIA ",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text("🇮🇳", fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun AssetsHeader(size: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Shared Assets", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Surface(
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
        ) {
            Text("${size} Files", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun StopButton(onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.padding(bottom = 44.dp).size(72.dp),
        shape = CircleShape,
        color = Color(0xFFE53935).copy(alpha = 0.2f),
        border = BorderStroke(2.dp, Color(0xFFFF5252).copy(alpha = 0.7f)),
        shadowElevation = 12.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Stop, "Stop Server", modifier = Modifier.size(36.dp), tint = Color.White)
        }
    }
}

@Composable
fun NoiseEffect(intensity: Float) {
    // Generate static noise points once to avoid heavy CPU/GPU overhead during animations
    val noisePoints = remember {
        List(400) {
            Offset(Random.nextFloat(), Random.nextFloat()) to (Random.nextFloat() * 1.2f + 0.4f)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize().alpha(intensity)) {
        for ((relativeOffset, radius) in noisePoints) {
            drawCircle(
                color = Color.White,
                radius = radius,
                center = Offset(relativeOffset.x * size.width, relativeOffset.y * size.height)
            )
        }
    }
}

@Composable
fun EmptyState(onSelectFiles: () -> Unit) {
    val stroke = remember { Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(40.dp))
                .clickable { onSelectFiles() }
                .background(Color.White.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(color = Color.White.copy(alpha = 0.2f), style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(40.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(110.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.padding(28.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text("Drop files here", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = Color.White)
                Text("tap to start sharing", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun ServerStatusCard(url: String?, token: String?, onQrClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = Color(0xFF00E676).copy(alpha = alpha)) {}
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("LIVE STREAMING", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                IconButton(onClick = onQrClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.QrCode, null, tint = Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                color = Color.Black.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = url ?: "Starting stream...", modifier = Modifier.weight(1f), color = Color.White, maxLines = 1, fontSize = 14.sp)
                    val context = LocalContext.current
                    IconButton(
                        onClick = {
                            if (!url.isNullOrEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                                Toast.makeText(context, "Link Copied!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !url.isNullOrEmpty()
                    ) { 
                        Icon(Icons.Rounded.ContentCopy, null, tint = Color.White, modifier = Modifier.size(20.dp)) 
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Session PIN", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    Text(token ?: "---", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                }
                Icon(Icons.Rounded.CloudDone, null, tint = Color.White.copy(alpha = 0.1f), modifier = Modifier.size(80.dp))
            }
        }
    }
}

@Composable
fun QrCodeDialog(url: String, onDismiss: () -> Unit) {
    val bitmap = remember(url) { generateQrCode(url) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF050B10),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Scan to Connect", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                Spacer(modifier = Modifier.height(24.dp))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onDismiss, shape = RoundedCornerShape(16.dp)) {
                    Text("Close")
                }
            }
        }
    }
}

fun generateQrCode(text: String): Bitmap? {
    return try {
        val size = 512
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) { null }
}

@Composable
fun FileListItem(name: String, size: Long, mimeType: String?) {
    val icon = when {
        mimeType?.startsWith("image") == true -> Icons.Default.Image
        mimeType?.startsWith("video") == true -> Icons.Default.VideoFile
        mimeType?.startsWith("audio") == true -> Icons.Default.AudioFile
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        ListItem(
            headlineContent = { Text(name, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) },
            supportingContent = { 
                val mbSize = size / (1024.0 * 1024.0)
                val sizeText = if (mbSize >= 0.01) "${"%.2f".format(mbSize)} MB" else "${(size / 1024.0).toInt()} KB"
                Text(sizeText, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) 
            },
            leadingContent = {
                Surface(color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(46.dp)) {
                    Icon(icon, null, modifier = Modifier.padding(12.dp), tint = Color.White)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    fontFamily: FontFamily? = null
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        maxLines = maxLines,
        fontFamily = fontFamily,
        textAlign = textAlign
    )
}

