package com.igino.remote_camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ScrollView
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var localIpTextView: TextView
    private lateinit var remoteIpTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var roleLabel: TextView
    private lateinit var becomeMasterButton: Button
    private lateinit var scanButton: ImageButton
    private lateinit var editPhotoButton: ImageButton
    private lateinit var recognizeLegoButton: ImageButton
    private lateinit var loadPhotoButton: ImageButton
    private lateinit var captureButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var cameraContainer: FrameLayout
    private lateinit var remoteImageView: ImageView
    private lateinit var localPreviewView: PreviewView
    private lateinit var bottomActionsBar: LinearLayout

    private lateinit var focusControlLayout: View
    private lateinit var autofocusSwitch: SwitchCompat
    private lateinit var focusSeekBar: SeekBar
    private lateinit var resolutionSeekBar: SeekBar
    private lateinit var resolutionLabelTextView: TextView
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var zoomLabelTextView: TextView
    private var currentResolutionLevel = 1

    private lateinit var fullScreenDrawingContainer: View
    private lateinit var drawingView: DrawingView
    private lateinit var drawingColorIndicator: View
    private lateinit var clearDrawingButton: Button
    private lateinit var saveDrawingButton: Button
    private lateinit var closeDrawingButton: ImageButton

    private lateinit var slavePaletteContainer: View
    private lateinit var colorPaletteLayout: LinearLayout
    private lateinit var closePaletteButton: Button
    private lateinit var backToDrawingButton: Button
    private lateinit var brushSizeSeekBar: SeekBar
    private lateinit var brushSizeValueTextView: TextView
    private lateinit var brushSizePreviewView: View

    private lateinit var customShadePreview: View
    private lateinit var customShadeNameTextView: TextView
    private lateinit var customShadeHexTextView: TextView
    private lateinit var applyCustomShadeButton: Button
    private lateinit var hueSliderView: ColorSliderView
    private lateinit var shadeSliderView: ColorSliderView
    private lateinit var saturationSliderView: ColorSliderView
    private lateinit var generatedShadesContainer: LinearLayout
    private lateinit var colorHarmoniesContainer: LinearLayout
    private lateinit var imageEditingShadesContainer: LinearLayout

    private lateinit var paletteBadgeTextView: TextView
    private lateinit var paletteBadgeDescTextView: TextView
    private lateinit var paletteTabsLayout: LinearLayout
    private lateinit var tabPalette1Button: Button
    private lateinit var tabPalette2Button: Button
    private lateinit var tabPalette3Button: Button

    private lateinit var paletteToolsContainer: LinearLayout
    private lateinit var paletteColorsContainer: LinearLayout
    private lateinit var paletteShadesContainer: LinearLayout

    private var isEditingLocally = false
    private var isPendingLegoRecognition = false
    private var assignedPaletteIds: Set<Int> = setOf(1, 2, 3)

    private var currentHue: Float = 0f
    private var currentLightness: Float = 0.5f
    private var currentSaturation: Float = 1.0f
    private var currentCustomColor: Int = Color.RED

    private var isMaster = false
    private var isCameraBoxVisible = true
    @Volatile
    private var isStreamingRequested = true

    // Gestione multi-dispositivo (un solo master, molteplici slave)
    data class PeerConnection(
        val socket: Socket,
        val outputStream: DataOutputStream,
        val remoteIp: String,
        var role: String = "SLAVE",
        var deviceName: String = ""
    ) {
        @Volatile
        var isAlive: Boolean = true
    }

    private val connectedPeers = Collections.synchronizedMap(mutableMapOf<String, PeerConnection>())

    private val networkExecutor = Executors.newCachedThreadPool()
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var activeImageCapture: ImageCapture? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private var nsdManager: NsdManager? = null
    private var serviceName: String = "RemoteCamera-${Build.MODEL.replace(" ", "_")}"
    private val serviceType = "_remotecamera._tcp"
    private val FIXED_PORT = 9000
    private var localPort: Int = -1
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isReconnecting = true

    private lateinit var prefs: SharedPreferences
    private val PREF_IS_MASTER = "is_master"
    private val PREF_KNOWN_IPS = "known_peer_ips"
    private val PREF_REBRICKABLE_API_KEY = "rebrickable_api_key"

    private var lastReceivedBitmap: Bitmap? = null
    private var lastCapturedPhoto: Bitmap? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted && isMaster) {
            startCameraOnMaster()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val bitmap = decodeBitmapFromUri(uri)
            if (bitmap != null) {
                lastCapturedPhoto = bitmap
                remoteImageView.setImageBitmap(bitmap)
                remoteImageView.visibility = View.VISIBLE
                editPhotoButton.visibility = View.VISIBLE
                recognizeLegoButton.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return null

            val exifStream = contentResolver.openInputStream(uri)
            val exif = exifStream?.use { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("RemoteCamera", "Errore decodeBitmapFromUri: ${e.message}")
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
            setContentView(R.layout.activity_main)

            prefs = getSharedPreferences("remote_camera_prefs", MODE_PRIVATE)

            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }

            localIpTextView = findViewById(R.id.localIpTextView)
            remoteIpTextView = findViewById(R.id.remoteIpTextView)
            statusTextView = findViewById(R.id.statusTextView)
            roleLabel = findViewById(R.id.roleLabel)
            becomeMasterButton = findViewById(R.id.becomeMasterButton)
            scanButton = findViewById(R.id.scanButton)
            editPhotoButton = findViewById(R.id.editPhotoButton)
            recognizeLegoButton = findViewById(R.id.recognizeLegoButton)
            loadPhotoButton = findViewById(R.id.loadPhotoButton)
            captureButton = findViewById(R.id.captureButton)
            switchCameraButton = findViewById(R.id.switchCameraButton)
            settingsButton = findViewById(R.id.settingsButton)
            closeButton = findViewById(R.id.closeButton)
            cameraContainer = findViewById(R.id.cameraContainer)
            remoteImageView = findViewById(R.id.remoteImageView)
            localPreviewView = findViewById(R.id.localPreviewView)
            bottomActionsBar = findViewById(R.id.bottomActionsBar)

            focusControlLayout = findViewById(R.id.focusControlLayout)
            autofocusSwitch = findViewById(R.id.autofocusSwitch)
            focusSeekBar = findViewById(R.id.focusSeekBar)
            resolutionSeekBar = findViewById(R.id.resolutionSeekBar)
            resolutionLabelTextView = findViewById(R.id.resolutionLabelTextView)
            zoomSeekBar = findViewById(R.id.zoomSeekBar)
            zoomLabelTextView = findViewById(R.id.zoomLabelTextView)

            resolutionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentResolutionLevel = progress
                    val resText = when (progress) {
                        0 -> "Risoluzione: Bassa (HD)"
                        1 -> "Risoluzione: Media (Full HD)"
                        else -> "Risoluzione: Alta (Massima)"
                    }
                    resolutionLabelTextView.text = resText
                    if (fromUser) {
                        broadcastControlMessage("CMD_SET_RESOLUTION:$progress")
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val cam = camera
                    if (cam != null) {
                        val zoomState = cam.cameraInfo.zoomState.value
                        if (zoomState != null) {
                            val min = zoomState.minZoomRatio
                            val max = zoomState.maxZoomRatio
                            val ratio = min + (progress / 100f) * (max - min)
                            cam.cameraControl.setZoomRatio(ratio)
                            zoomLabelTextView.text = String.format(Locale.getDefault(), "Zoom: %.1fx", ratio)
                        }
                    }
                    if (fromUser) {
                        broadcastControlMessage("CMD_SET_ZOOM:$progress")
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            fullScreenDrawingContainer = findViewById(R.id.fullScreenDrawingContainer)
            drawingView = findViewById(R.id.drawingView)
            drawingColorIndicator = findViewById(R.id.drawingColorIndicator)
            clearDrawingButton = findViewById(R.id.clearDrawingButton)
            saveDrawingButton = findViewById(R.id.saveDrawingButton)
            closeDrawingButton = findViewById(R.id.closeDrawingButton)

            slavePaletteContainer = findViewById(R.id.slavePaletteContainer)
            colorPaletteLayout = findViewById(R.id.colorPaletteLayout)
            closePaletteButton = findViewById(R.id.closePaletteButton)
            backToDrawingButton = findViewById(R.id.backToDrawingButton)
            brushSizeSeekBar = findViewById(R.id.brushSizeSeekBar)
            brushSizeValueTextView = findViewById(R.id.brushSizeValueTextView)
            brushSizePreviewView = findViewById(R.id.brushSizePreviewView)

            customShadePreview = findViewById(R.id.customShadePreview)
            customShadeNameTextView = findViewById(R.id.customShadeNameTextView)
            customShadeHexTextView = findViewById(R.id.customShadeHexTextView)
            applyCustomShadeButton = findViewById(R.id.applyCustomShadeButton)
            hueSliderView = findViewById(R.id.hueSliderView)
            shadeSliderView = findViewById(R.id.shadeSliderView)
            saturationSliderView = findViewById(R.id.saturationSliderView)
            generatedShadesContainer = findViewById(R.id.generatedShadesContainer)
            colorHarmoniesContainer = findViewById(R.id.colorHarmoniesContainer)
            imageEditingShadesContainer = findViewById(R.id.imageEditingShadesContainer)

            brushSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = progress.coerceAtLeast(2)
                    brushSizeValueTextView.text = "$size px"
                    updateBrushPreview(size)
                    drawingView.setStrokeWidth(size.toFloat())
                    if (fromUser) {
                        broadcastControlMessage("CMD_SET_DRAW_SIZE:$size")
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            paletteBadgeTextView = findViewById(R.id.paletteBadgeTextView)
            paletteBadgeDescTextView = findViewById(R.id.paletteBadgeDescTextView)
            paletteTabsLayout = findViewById(R.id.paletteTabsLayout)
            tabPalette1Button = findViewById(R.id.tabPalette1Button)
            tabPalette2Button = findViewById(R.id.tabPalette2Button)
            tabPalette3Button = findViewById(R.id.tabPalette3Button)

            paletteToolsContainer = findViewById(R.id.paletteToolsContainer)
            paletteColorsContainer = findViewById(R.id.paletteColorsContainer)
            paletteShadesContainer = findViewById(R.id.paletteShadesContainer)

            tabPalette1Button.setOnClickListener { onUserSelectedPaletteTab(1) }
            tabPalette2Button.setOnClickListener { onUserSelectedPaletteTab(2) }
            tabPalette3Button.setOnClickListener { onUserSelectedPaletteTab(3) }

            setupColorPalette()
            setupCustomShadePalette()

            applyPaletteVisibility(setOf(1, 2, 3))

            cameraExecutor = Executors.newSingleThreadExecutor()

            val localIp = getLocalIpAddress()
            localIpTextView.text = "Indirizzo Locale: $localIp"

            nsdManager = getSystemService(NSD_SERVICE) as? NsdManager

            // All'avvio tutti i dispositivi partono come Slave (non master)
            isMaster = false
            updateRoleUI()
            updateConnectionStatusUI()

            // Premendo "Diventa Master", attivo la mia fotocamera e la mostro agli slave
            becomeMasterButton.setOnClickListener {
                setMasterRole(true, broadcast = true)
            }

            switchCameraButton.setOnClickListener {
                if (isMaster) {
                    showCameraSelectionMenu()
                } else {
                    isCameraBoxVisible = !isCameraBoxVisible
                    updateCameraBoxUI()
                }
            }

            switchCameraButton.setOnLongClickListener {
                if (isMaster) {
                    showCameraSelectionMenu()
                } else {
                    sendControlMessageToMaster("CMD_GET_CAMERA_LIST")
                }
                true
            }

            autofocusSwitch.setOnCheckedChangeListener { _, isChecked ->
                focusSeekBar.visibility = if (isChecked) View.GONE else View.VISIBLE
                if (isChecked) {
                    sendControlMessageToMaster("CMD_AF_ON")
                } else {
                    sendControlMessageToMaster("CMD_AF_OFF")
                }
            }

            focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        sendControlMessageToMaster("CMD_FOCUS_SET:$progress")
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            scanButton.setOnClickListener {
                startManualScan()
            }

            settingsButton.setOnClickListener {
                showSettingsDialog()
            }

            recognizeLegoButton.setOnClickListener {
                if (isMaster) {
                    isPendingLegoRecognition = true
                    takeHighResPhoto(null)
                } else {
                    isPendingLegoRecognition = true
                    sendControlMessageToMaster("CMD_RECOGNISE_LEGO")
                }
            }

            editPhotoButton.setOnClickListener {
                if (lastCapturedPhoto != null) {
                    isEditingLocally = true
                    startDrawingMode()
                    distributePalettesToPeers()
                }
            }

            loadPhotoButton.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }

            clearDrawingButton.setOnClickListener {
                drawingView.clearDrawing()
            }

            saveDrawingButton.setOnClickListener {
                val editedBitmap = drawingView.getCombinedBitmap()
                if (editedBitmap != null) {
                    saveImage(editedBitmap)
                }
            }

            closeDrawingButton.setOnClickListener {
                isEditingLocally = false
                stopDrawingMode()
                broadcastControlMessage("CMD_STOP_DRAWING")
            }

            closePaletteButton.setOnClickListener {
                if (fullScreenDrawingContainer.visibility == View.VISIBLE) {
                    slavePaletteContainer.visibility = View.GONE
                } else {
                    isEditingLocally = false
                    stopDrawingMode()
                    broadcastControlMessage("CMD_STOP_DRAWING")
                }
            }

            captureButton.setOnClickListener {
                captureRemoteImage()
            }

            closeButton.setOnClickListener {
                cleanupAndExit(broadcast = true)
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (slavePaletteContainer.visibility == View.VISIBLE && fullScreenDrawingContainer.visibility == View.VISIBLE) {
                        slavePaletteContainer.visibility = View.GONE
                    } else if (fullScreenDrawingContainer.visibility == View.VISIBLE || slavePaletteContainer.visibility == View.VISIBLE) {
                        isEditingLocally = false
                        stopDrawingMode()
                        broadcastControlMessage("CMD_STOP_DRAWING")
                    } else {
                        cleanupAndExit(broadcast = true)
                    }
                }
            })

            startServer()
            startAutoReconnectionLoop()

        } catch (e: Exception) {
            Log.e("RemoteCamera", "Crash in onCreate", e)
        }
    }

    private fun setMasterRole(newMaster: Boolean, broadcast: Boolean = true) {
        if (isMaster == newMaster) return
        isMaster = newMaster
        prefs.edit().putBoolean(PREF_IS_MASTER, isMaster).apply()

        runOnUiThread {
            updateRoleUI()
            updateConnectionStatusUI()
        }

        if (isMaster) {
            checkCameraPermission()
            if (broadcast) {
                broadcastControlMessage("CMD_CLAIM_MASTER:${Build.MODEL.replace(":", "_")}")
            }
        } else {
            stopCamera()
            if (broadcast) {
                broadcastControlMessage("CMD_SET_ROLE:SLAVE")
            }
        }
    }

    private fun updateRoleUI() {
        stopDrawingMode()

        if (isMaster) {
            roleLabel.text = "Ruolo: MASTER (Fotocamera)"
            becomeMasterButton.visibility = View.GONE

            cameraContainer.visibility = View.VISIBLE
            remoteImageView.visibility = View.GONE
            localPreviewView.visibility = View.VISIBLE
            bottomActionsBar.visibility = View.VISIBLE
            captureButton.visibility = View.VISIBLE
            editPhotoButton.visibility = if (lastCapturedPhoto != null) View.VISIBLE else View.GONE
            recognizeLegoButton.visibility = View.VISIBLE
            loadPhotoButton.visibility = View.VISIBLE
            focusControlLayout.visibility = View.VISIBLE

            switchCameraButton.setColorFilter(Color.parseColor("#4CAF50"))
        } else {
            roleLabel.text = "Ruolo: SLAVE (Controllo)"
            becomeMasterButton.visibility = View.VISIBLE

            bottomActionsBar.visibility = View.VISIBLE
            scanButton.visibility = View.VISIBLE
            loadPhotoButton.visibility = View.VISIBLE
            editPhotoButton.visibility = if (lastCapturedPhoto != null) View.VISIBLE else View.GONE
            recognizeLegoButton.visibility = View.VISIBLE

            updateCameraBoxUI()
        }
    }

    private fun updateCameraBoxUI() {
        if (isMaster) {
            cameraContainer.visibility = View.VISIBLE
            localPreviewView.visibility = View.VISIBLE
            remoteImageView.visibility = View.GONE
            bottomActionsBar.visibility = View.VISIBLE
            captureButton.visibility = View.VISIBLE
            recognizeLegoButton.visibility = View.VISIBLE
            loadPhotoButton.visibility = View.VISIBLE
            focusControlLayout.visibility = View.VISIBLE
            return
        }

        bottomActionsBar.visibility = View.VISIBLE

        if (isCameraBoxVisible) {
            switchCameraButton.setColorFilter(Color.parseColor("#F44336"))
            cameraContainer.visibility = View.VISIBLE
            remoteImageView.visibility = View.VISIBLE
            localPreviewView.visibility = View.GONE
            captureButton.visibility = View.VISIBLE
            focusControlLayout.visibility = View.VISIBLE

            sendControlMessageToMaster("CMD_START_STREAM")
        } else {
            switchCameraButton.setColorFilter(Color.parseColor("#4CAF50"))
            cameraContainer.visibility = View.GONE
            remoteImageView.visibility = View.GONE
            captureButton.visibility = View.GONE
            focusControlLayout.visibility = View.GONE

            sendControlMessageToMaster("CMD_STOP_STREAM")
        }
    }

    private fun updateConnectionStatusUI() {
        val count = connectedPeers.size
        if (count == 0) {
            remoteIpTextView.text = "Dispositivi connessi: 0"
            statusTextView.text = if (isMaster) {
                "Modalità: MASTER (In attesa di slave a cui trasmettere)"
            } else {
                "Modalità: SLAVE (In attesa del Master)"
            }
        } else {
            val peerList = synchronized(connectedPeers) {
                connectedPeers.values.joinToString(", ") { peer ->
                    "${peer.deviceName.ifEmpty { peer.remoteIp }} (${peer.role})"
                }
            }
            remoteIpTextView.text = "Connessi ($count): $peerList"
            statusTextView.text = if (isMaster) {
                val slavesCount = synchronized(connectedPeers) { connectedPeers.values.count { it.role == "SLAVE" } }
                "MASTER: In streaming a $slavesCount Slave"
            } else {
                val masterPeer = synchronized(connectedPeers) { connectedPeers.values.find { it.role == "MASTER" } }
                val masterName = masterPeer?.deviceName ?: masterPeer?.remoteIp ?: "In ricerca Master..."
                "SLAVE: Connesso a Master ($masterName)"
            }
        }
    }

    private fun updateBrushPreview(sizePx: Int) {
        val density = resources.displayMetrics.density
        val displaySize = (sizePx * density).toInt().coerceIn((6 * density).toInt(), (48 * density).toInt())
        val params = brushSizePreviewView.layoutParams
        params.width = displaySize
        params.height = displaySize
        brushSizePreviewView.layoutParams = params
    }

    private fun setupColorPalette() {
        colorPaletteLayout.removeAllViews()

        val colorList = listOf(
            Pair(Color.RED, "Rosso"),
            Pair(Color.GREEN, "Verde"),
            Pair(Color.BLUE, "Blu"),
            Pair(Color.YELLOW, "Giallo"),
            Pair(Color.MAGENTA, "Magenta"),
            Pair(Color.CYAN, "Ciano"),
            Pair(Color.parseColor("#FFA500"), "Arancione"),
            Pair(Color.parseColor("#800080"), "Viola"),
            Pair(Color.WHITE, "Bianco"),
            Pair(Color.BLACK, "Nero"),
            Pair(Color.parseColor("#FF1493"), "Rosa"),
            Pair(Color.parseColor("#8B4513"), "Marrone")
        )

        val density = resources.displayMetrics.density
        val columns = 3
        var currentRow: LinearLayout? = null

        colorList.forEachIndexed { index, (color, name) ->
            if (index % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (12 * density).toInt(), 0, (12 * density).toInt())
                    }
                }
                colorPaletteLayout.addView(currentRow)
            }

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )

                val circleView = View(this@MainActivity).apply {
                    val size = (60 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke((3 * density).toInt(), Color.WHITE)
                    }
                }

                val textView = TextView(this@MainActivity).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * density).toInt(), 0, 0)
                }

                addView(circleView)
                addView(textView)

                setOnClickListener {
                    selectColorFromExternal(color)
                }
            }

            currentRow?.addView(itemLayout)
        }
    }

    private fun computeCurrentCustomColor(): Int {
        return ColorUtils.HSLToColor(floatArrayOf(currentHue, currentSaturation, currentLightness))
    }

    private fun applyPaletteVisibility(activeIds: Set<Int>) {
        assignedPaletteIds = activeIds
        runOnUiThread {
            paletteToolsContainer.visibility = if (activeIds.contains(1)) View.VISIBLE else View.GONE
            paletteColorsContainer.visibility = if (activeIds.contains(2)) View.VISIBLE else View.GONE
            paletteShadesContainer.visibility = if (activeIds.contains(3)) View.VISIBLE else View.GONE

            updatePaletteBadgeAndTabsUI()
        }
    }

    private fun updatePaletteBadgeAndTabsUI() {
        val density = resources.displayMetrics.density

        fun updateTabButton(button: Button, isSelected: Boolean) {
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(if (isSelected) Color.parseColor("#2196F3") else Color.parseColor("#333333"))
                if (isSelected) {
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
            }
            button.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#AAAAAA"))
        }

        updateTabButton(tabPalette1Button, assignedPaletteIds.contains(1))
        updateTabButton(tabPalette2Button, assignedPaletteIds.contains(2))
        updateTabButton(tabPalette3Button, assignedPaletteIds.contains(3))

        when {
            assignedPaletteIds == setOf(1) -> {
                paletteBadgeTextView.text = "PALETTE 1: STRUMENTI (PENNELLO)"
                paletteBadgeDescTextView.text = "Dispositivo dedicato alla dimensione del pennello"
            }
            assignedPaletteIds == setOf(2) -> {
                paletteBadgeTextView.text = "PALETTE 2: COLORI BASE"
                paletteBadgeDescTextView.text = "Dispositivo dedicato ai 12 colori base"
            }
            assignedPaletteIds == setOf(3) -> {
                paletteBadgeTextView.text = "PALETTE 3: COLOR SCHEME & SFUMATURE"
                paletteBadgeDescTextView.text = "Dispositivo dedicato alle sfumature e armonie cromatiche"
            }
            assignedPaletteIds == setOf(2, 3) -> {
                paletteBadgeTextView.text = "PALETTE 2 & 3: COLORI E SFUMATURE"
                paletteBadgeDescTextView.text = "Dispositivo dedicato a colori, sfumature e armonie"
            }
            else -> {
                paletteBadgeTextView.text = "TUTTE LE PALETTE DISPONIBILI"
                paletteBadgeDescTextView.text = "Strumenti, colori e sfumature disponibili su questo dispositivo"
            }
        }
    }

    private fun parseAndApplyPaletteAssignment(config: String) {
        val ids = config.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        val finalIds = if (ids.isEmpty()) setOf(1, 2, 3) else ids
        applyPaletteVisibility(finalIds)
    }

    private fun distributePalettesToPeers() {
        if (!isEditingLocally) return
        val peers = synchronized(connectedPeers) {
            connectedPeers.values.toList().sortedBy { it.remoteIp }
        }
        if (peers.isEmpty()) {
            applyPaletteVisibility(setOf(1, 2, 3))
            return
        }

        when (peers.size) {
            1 -> {
                sendDataToPeer(peers[0], 1, "CMD_START_DRAWING:1,2,3".toByteArray())
            }
            2 -> {
                sendDataToPeer(peers[0], 1, "CMD_START_DRAWING:1".toByteArray())
                sendDataToPeer(peers[1], 1, "CMD_START_DRAWING:2,3".toByteArray())
            }
            else -> {
                for (i in peers.indices) {
                    val assignedId = (i % 3) + 1
                    sendDataToPeer(peers[i], 1, "CMD_START_DRAWING:$assignedId".toByteArray())
                }
            }
        }
    }

    private fun onUserSelectedPaletteTab(paletteId: Int) {
        if (assignedPaletteIds == setOf(paletteId)) return
        val previousPaletteId = assignedPaletteIds.firstOrNull() ?: 1

        applyPaletteVisibility(setOf(paletteId))

        broadcastControlMessage("CMD_SWAP_PALETTE:$previousPaletteId:$paletteId")
    }

    private fun setupCustomShadePalette() {
        hueSliderView.setGradientColors(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        hueSliderView.progress = currentHue / 360f
        shadeSliderView.progress = currentLightness
        saturationSliderView.progress = currentSaturation

        updateShadeSlidersGradients()
        updateCustomShadePreviewUI()
        generateShadeSwatches()
        generateHarmonySwatches()

        hueSliderView.onProgressChanged = { progress, fromUser ->
            currentHue = (progress * 360f).coerceIn(0f, 360f)
            onCustomColorComponentsChanged(fromUser, isFinal = false)
        }
        hueSliderView.onTrackingStopped = {
            onCustomColorComponentsChanged(fromUser = true, isFinal = true)
        }

        shadeSliderView.onProgressChanged = { progress, fromUser ->
            currentLightness = progress.coerceIn(0f, 1f)
            onCustomColorComponentsChanged(fromUser, isFinal = false)
        }
        shadeSliderView.onTrackingStopped = {
            onCustomColorComponentsChanged(fromUser = true, isFinal = true)
        }

        saturationSliderView.onProgressChanged = { progress, fromUser ->
            currentSaturation = progress.coerceIn(0f, 1f)
            onCustomColorComponentsChanged(fromUser, isFinal = false)
        }
        saturationSliderView.onTrackingStopped = {
            onCustomColorComponentsChanged(fromUser = true, isFinal = true)
        }

        applyCustomShadeButton.setOnClickListener {
            applyColorToDrawing(currentCustomColor, broadcast = true)
        }

        customShadePreview.setOnClickListener {
            applyColorToDrawing(currentCustomColor, broadcast = true)
        }
    }

    private fun onCustomColorComponentsChanged(fromUser: Boolean, isFinal: Boolean) {
        currentCustomColor = computeCurrentCustomColor()
        updateShadeSlidersGradients()
        updateCustomShadePreviewUI()

        if (isFinal) {
            generateShadeSwatches()
            generateHarmonySwatches()
            populateImageEditingShades()
        }

        if (fromUser) {
            applyColorToDrawing(currentCustomColor, broadcast = isFinal)
        }
    }

    private fun updateShadeSlidersGradients() {
        val black = ColorUtils.HSLToColor(floatArrayOf(currentHue, currentSaturation, 0f))
        val mid = ColorUtils.HSLToColor(floatArrayOf(currentHue, currentSaturation, 0.5f))
        val white = ColorUtils.HSLToColor(floatArrayOf(currentHue, currentSaturation, 1f))
        shadeSliderView.setGradientColors(black, mid, white)

        val desat = ColorUtils.HSLToColor(floatArrayOf(currentHue, 0f, currentLightness))
        val sat = ColorUtils.HSLToColor(floatArrayOf(currentHue, 1f, currentLightness))
        saturationSliderView.setGradientColors(desat, sat)
    }

    private fun updateCustomShadePreviewUI() {
        val density = resources.displayMetrics.density
        customShadePreview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(currentCustomColor)
            setStroke((2.5f * density).toInt(), Color.WHITE)
        }
        customShadeHexTextView.text = formatHexColor(currentCustomColor)
    }

    private fun formatHexColor(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun applyColorToDrawing(color: Int, broadcast: Boolean = true) {
        currentCustomColor = color
        drawingView.setStrokeColor(color)
        drawingColorIndicator.setBackgroundColor(color)
        updateCustomShadePreviewUI()

        if (broadcast) {
            broadcastControlMessage("CMD_SET_DRAW_COLOR:$color")
        }
    }

    private fun selectColorFromExternal(color: Int) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        currentHue = hsl[0]
        currentSaturation = hsl[1]
        currentLightness = hsl[2]
        currentCustomColor = color

        hueSliderView.progress = currentHue / 360f
        shadeSliderView.progress = currentLightness
        saturationSliderView.progress = currentSaturation

        updateShadeSlidersGradients()
        updateCustomShadePreviewUI()
        generateShadeSwatches()
        generateHarmonySwatches()

        applyColorToDrawing(color, broadcast = true)
    }

    private fun syncColorFromRemote(color: Int) {
        currentCustomColor = color
        drawingView.setStrokeColor(color)
        drawingColorIndicator.setBackgroundColor(color)

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        currentHue = hsl[0]
        currentSaturation = hsl[1]
        currentLightness = hsl[2]

        hueSliderView.progress = currentHue / 360f
        shadeSliderView.progress = currentLightness
        saturationSliderView.progress = currentSaturation

        updateShadeSlidersGradients()
        updateCustomShadePreviewUI()
        generateShadeSwatches()
        generateHarmonySwatches()
    }

    private fun generateShadeSwatches() {
        generatedShadesContainer.removeAllViews()
        val density = resources.displayMetrics.density

        val lightnessLevels = listOf(
            0.12f to "Ombra",
            0.25f to "Scuro",
            0.38f to "Medio",
            0.50f to "Puro",
            0.65f to "Chiaro",
            0.78f to "Pastello",
            0.88f to "Chiarissimo",
            0.96f to "Luce"
        )

        for ((l, label) in lightnessLevels) {
            val shadeColor = ColorUtils.HSLToColor(floatArrayOf(currentHue, currentSaturation, l))

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())

                val circleView = View(this@MainActivity).apply {
                    val size = (42 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(shadeColor)
                        setStroke((2 * density).toInt(), Color.parseColor("#888888"))
                    }
                }

                val textView = TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(Color.parseColor("#CCCCCC"))
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(0, (3 * density).toInt(), 0, 0)
                }

                addView(circleView)
                addView(textView)

                setOnClickListener {
                    currentLightness = l
                    shadeSliderView.progress = l
                    onCustomColorComponentsChanged(fromUser = true, isFinal = true)
                }
            }

            generatedShadesContainer.addView(itemLayout)
        }
    }

    private fun generateHarmonySwatches() {
        colorHarmoniesContainer.removeAllViews()
        val density = resources.displayMetrics.density

        val harmonies = listOf(
            "Base" to currentHue,
            "Complementare" to ((currentHue + 180f) % 360f),
            "Analogo +" to ((currentHue + 30f) % 360f),
            "Analogo -" to ((currentHue + 330f) % 360f),
            "Triadico 1" to ((currentHue + 120f) % 360f),
            "Triadico 2" to ((currentHue + 240f) % 360f)
        )

        for ((name, hue) in harmonies) {
            val harmonyColor = ColorUtils.HSLToColor(floatArrayOf(hue, currentSaturation, currentLightness))

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())

                val circleView = View(this@MainActivity).apply {
                    val size = (42 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(harmonyColor)
                        setStroke((2 * density).toInt(), Color.parseColor("#888888"))
                    }
                }

                val textView = TextView(this@MainActivity).apply {
                    text = name
                    setTextColor(Color.parseColor("#CCCCCC"))
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(0, (3 * density).toInt(), 0, 0)
                }

                addView(circleView)
                addView(textView)

                setOnClickListener {
                    currentHue = hue
                    hueSliderView.progress = currentHue / 360f
                    onCustomColorComponentsChanged(fromUser = true, isFinal = true)
                }
            }

            colorHarmoniesContainer.addView(itemLayout)
        }
    }

    private fun startDrawingMode() {
        val photo = lastCapturedPhoto ?: return
        runOnUiThread {
            fullScreenDrawingContainer.visibility = View.VISIBLE
            drawingView.setPhoto(photo)
            drawingView.setStrokeColor(currentCustomColor)
            drawingColorIndicator.setBackgroundColor(currentCustomColor)
            populateImageEditingShades()
        }
    }

    private fun populateImageEditingShades() {
        if (!::imageEditingShadesContainer.isInitialized) return
        imageEditingShadesContainer.removeAllViews()
        val density = resources.displayMetrics.density

        val colorItems = mutableListOf<Pair<String, Int>>()

        val lightnessLevels = listOf(
            0.12f to "Ombra",
            0.25f to "Scuro",
            0.38f to "Medio",
            0.50f to "Puro",
            0.65f to "Chiaro",
            0.78f to "Pastello",
            0.88f to "Chiarissimo",
            0.96f to "Luce"
        )
        for ((l, label) in lightnessLevels) {
            val shadeColor = ColorUtils.HSLToColor(floatArrayOf(currentHue, currentSaturation, l))
            colorItems.add(label to shadeColor)
        }

        val harmonies = listOf(
            "Base" to currentHue,
            "Comp" to ((currentHue + 180f) % 360f),
            "Ana+" to ((currentHue + 30f) % 360f),
            "Ana-" to ((currentHue + 330f) % 360f),
            "Tri1" to ((currentHue + 120f) % 360f),
            "Tri2" to ((currentHue + 240f) % 360f)
        )
        for ((name, hue) in harmonies) {
            val harmonyColor = ColorUtils.HSLToColor(floatArrayOf(hue, currentSaturation, currentLightness))
            colorItems.add(name to harmonyColor)
        }

        for ((label, color) in colorItems) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())

                val circleView = View(this@MainActivity).apply {
                    val size = (38 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke((2 * density).toInt(), Color.parseColor("#FFFFFF"))
                    }
                }

                val textView = TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(Color.parseColor("#CCCCCC"))
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setPadding(0, (2 * density).toInt(), 0, 0)
                }

                addView(circleView)
                addView(textView)

                setOnClickListener {
                    applyColorToDrawing(color, broadcast = true)
                }
            }

            imageEditingShadesContainer.addView(itemLayout)
        }
    }

    private fun showSettingsDialog() {
        val input = EditText(this).apply {
            hint = "Inserisci Rebrickable API Key"
            setText(prefs.getString(PREF_REBRICKABLE_API_KEY, "") ?: "")
            setPadding(48, 32, 48, 32)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Impostazioni - Rebrickable API Key")
            .setMessage("Inserisci la tua chiave API di Rebrickable per abilitare il riconoscimento dei mattoncini Lego:")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val apiKey = input.text.toString().trim()
                prefs.edit().putString(PREF_REBRICKABLE_API_KEY, apiKey).apply()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun stopDrawingMode() {
        runOnUiThread {
            fullScreenDrawingContainer.visibility = View.GONE
            slavePaletteContainer.visibility = View.GONE
        }
    }

    private fun takePhotoAndRecognize(requesterPeer: PeerConnection?) {
        val capture = activeImageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rawBmp = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotatedBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                    val scaledBmp = when (currentResolutionLevel) {
                        0 -> scaleBitmapMaxDimension(rotatedBmp, 1280)
                        1 -> scaleBitmapMaxDimension(rotatedBmp, 1920)
                        else -> rotatedBmp
                    }
                    performLegoRecognitionForPeer(requesterPeer, scaledBmp)
                    image.close()
                    rawBmp.recycle()
                    if (rotatedBmp !== rawBmp) rotatedBmp.recycle()
                } catch (e: Exception) {
                    Log.e("RemoteCamera", "Errore scatto per riconoscimento: ${e.message}")
                    if (requesterPeer != null) {
                        sendDataToPeer(requesterPeer, 1, "CMD_LEGO_ERROR::Errore scatto fotocamera Master".toByteArray())
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("RemoteCamera", "Scatto fallito: ${exception.message}")
                if (requesterPeer != null) {
                    sendDataToPeer(requesterPeer, 1, "CMD_LEGO_ERROR::Scatto fotocamera fallito".toByteArray())
                }
            }
        })
    }

    private fun recognizeLegoPart() {
        val bitmap = lastCapturedPhoto
        if (bitmap == null) {
            if (isMaster) {
                val capture = activeImageCapture
                if (capture == null) {
                    return
                }
                capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val rawBmp = image.toBitmap()
                            val rotation = image.imageInfo.rotationDegrees
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotatedBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                            val scaledBmp = when (currentResolutionLevel) {
                                0 -> scaleBitmapMaxDimension(rotatedBmp, 1280)
                                1 -> scaleBitmapMaxDimension(rotatedBmp, 1920)
                                else -> rotatedBmp
                            }
                            performLegoRecognitionForPeer(null, scaledBmp)
                            image.close()
                            rawBmp.recycle()
                            if (rotatedBmp !== rawBmp) rotatedBmp.recycle()
                        } catch (e: Exception) {
                            Log.e("RemoteCamera", "Errore scatto per riconoscimento: ${e.message}")
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {}
                })
            } else {
                sendControlMessageToMaster("CMD_RECOGNISE_LEGO")
            }
            return
        }

        val scaledBmp = when (currentResolutionLevel) {
            0 -> scaleBitmapMaxDimension(bitmap, 1280)
            1 -> scaleBitmapMaxDimension(bitmap, 1920)
            else -> bitmap
        }
        performLegoRecognitionForPeer(null, scaledBmp)
    }

    private fun performLegoRecognitionForPeer(peer: PeerConnection?, bitmap: Bitmap) {
        thread {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val imageBytes = stream.toByteArray()

                val endpoints = listOf(
                    "https://api.brickognize.com/predict/",
                    "https://api.brickognize.com/v2/predict/",
                    "https://api.brickognize.com/v3/predict/"
                )

                var responseJsonStr: String? = null
                var successResponseCode = -1

                for (endpointUrl in endpoints) {
                    try {
                        val boundary = "Boundary_" + System.currentTimeMillis()
                        val url = URL(endpointUrl)
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                            setRequestProperty("Accept", "application/json")
                            connectTimeout = 10000
                            readTimeout = 10000
                        }

                        connection.outputStream.use { outputStream ->
                            val writer = PrintWriter(OutputStreamWriter(outputStream, "UTF-8"), true)
                            writer.append("--$boundary\r\n")
                            writer.append("Content-Disposition: form-data; name=\"query_image\"; filename=\"lego.jpg\"\r\n")
                            writer.append("Content-Type: image/jpeg\r\n\r\n")
                            writer.flush()

                            outputStream.write(imageBytes)
                            outputStream.flush()

                            writer.append("\r\n--$boundary--\r\n")
                            writer.flush()
                        }

                        successResponseCode = connection.responseCode
                        if (successResponseCode == HttpURLConnection.HTTP_OK) {
                            responseJsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                            connection.disconnect()
                            break
                        } else {
                            connection.disconnect()
                        }
                    } catch (e: Exception) {
                        Log.d("RemoteCamera", "Tentativo endpoint $endpointUrl fallito: ${e.message}")
                    }
                }

                if (responseJsonStr == null || successResponseCode != HttpURLConnection.HTTP_OK) {
                    if (peer != null) {
                        sendDataToPeer(peer, 1, "CMD_LEGO_ERROR::Brickognize: Errore endpoint".toByteArray())
                    }
                    return@thread
                }

                val jsonRoot = JSONObject(responseJsonStr)
                val itemsArray = jsonRoot.optJSONArray("items")
                if (itemsArray == null || itemsArray.length() == 0) {
                    if (peer != null) {
                        sendDataToPeer(peer, 1, "CMD_LEGO_ERROR::Nessun mattoncino Lego riconosciuto".toByteArray())
                    }
                    return@thread
                }

                val bestItem = itemsArray.getJSONObject(0)
                val brickId = bestItem.optString("id", "")
                val brickName = bestItem.optString("name", "Sconosciuto")
                val score = bestItem.optDouble("score", 0.0)

                val externalIds = bestItem.optJSONObject("external_ids")
                val rebrickableIds = externalIds?.optJSONArray("rebrickable")
                val partNum = if (rebrickableIds != null && rebrickableIds.length() > 0) {
                    rebrickableIds.getString(0)
                } else {
                    brickId
                }

                var rebrickablePartName = brickName
                var rebrickableYear = ""
                var rebrickableCategory = ""
                var rebrickableCatId = ""
                var rebrickableImgUrl = ""

                if (partNum.isNotEmpty()) {
                    val apiKey = prefs.getString(PREF_REBRICKABLE_API_KEY, "") ?: ""
                    try {
                        val rebUrl = URL("https://rebrickable.com/api/v3/lego/parts/$partNum/")
                        val rebConn = (rebUrl.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            if (apiKey.isNotEmpty()) {
                                setRequestProperty("Authorization", "key $apiKey")
                            }
                            setRequestProperty("Accept", "application/json")
                            connectTimeout = 10000
                            readTimeout = 10000
                        }

                        if (rebConn.responseCode == HttpURLConnection.HTTP_OK) {
                            val rebJsonStr = rebConn.inputStream.bufferedReader().use { it.readText() }
                            val rebObj = JSONObject(rebJsonStr)
                            rebrickablePartName = rebObj.optString("name", brickName)
                            rebrickableYear = rebObj.optString("year_from", "")
                            rebrickableCatId = rebObj.optString("part_cat_id", "")
                            rebrickableImgUrl = rebObj.optString("part_img_url", "")

                            if (rebrickableCatId.isNotEmpty()) {
                                try {
                                    val catUrl = URL("https://rebrickable.com/api/v3/lego/part_categories/$rebrickableCatId/")
                                    val catConn = (catUrl.openConnection() as HttpURLConnection).apply {
                                        requestMethod = "GET"
                                        if (apiKey.isNotEmpty()) {
                                            setRequestProperty("Authorization", "key $apiKey")
                                        }
                                        setRequestProperty("Accept", "application/json")
                                        connectTimeout = 5000
                                        readTimeout = 5000
                                    }
                                    if (catConn.responseCode == HttpURLConnection.HTTP_OK) {
                                        val catJsonStr = catConn.inputStream.bufferedReader().use { it.readText() }
                                        val catObj = JSONObject(catJsonStr)
                                        rebrickableCategory = catObj.optString("name", "")
                                    }
                                    catConn.disconnect()
                                } catch (e: Exception) {
                                    Log.e("RemoteCamera", "Errore Rebrickable Category API: ${e.message}")
                                }
                            }
                        }
                        rebConn.disconnect()
                    } catch (e: Exception) {
                        Log.e("RemoteCamera", "Errore Rebrickable API: ${e.message}")
                    }
                }

                val confidencePercent = (score * 100).toInt()
                val safeName = rebrickablePartName.replace("::", "_")
                val safeCat = rebrickableCategory.replace("::", "_")
                val safeCatId = rebrickableCatId.replace("::", "_")
                val safeYear = rebrickableYear.replace("::", "_")

                if (peer != null) {
                    val resultMsg = "CMD_LEGO_RESULT::$partNum::$safeName::$safeCat::$safeCatId::$safeYear::$confidencePercent::$rebrickableImgUrl"
                    sendLegoResultToPeer(peer, imageBytes, resultMsg)
                } else {
                    showLegoResultDialog(bitmap, partNum, rebrickablePartName, rebrickableCategory, rebrickableCatId, rebrickableYear, confidencePercent.toString(), rebrickableImgUrl)
                }

            } catch (e: Exception) {
                Log.e("RemoteCamera", "Errore riconoscimento Lego: ${e.message}", e)
                if (peer != null) {
                    sendDataToPeer(peer, 1, "CMD_LEGO_ERROR::Errore di elaborazione".toByteArray())
                }
            }
        }
    }

    private fun showLegoResultDialog(capturedBitmap: Bitmap?, partNum: String, partName: String, category: String, catId: String, year: String, confidence: String, imgUrl: String) {
        thread {
            var dbBitmap: Bitmap? = null
            if (imgUrl.isNotEmpty()) {
                try {
                    val imgConn = URL(imgUrl).openConnection() as HttpURLConnection
                    imgConn.connectTimeout = 5000
                    imgConn.readTimeout = 5000
                    if (imgConn.responseCode == HttpURLConnection.HTTP_OK) {
                        dbBitmap = BitmapFactory.decodeStream(imgConn.inputStream)
                    }
                    imgConn.disconnect()
                } catch (e: Exception) {
                    Log.e("RemoteCamera", "Errore download immagine Rebrickable: ${e.message}")
                }
            }

            val resultMessage = buildString {
                append("Nome: $partName\n")
                append("Codice Parte: $partNum\n")
                if (category.isNotEmpty()) append("Categoria: $category\n")
                if (catId.isNotEmpty()) append("ID Categoria (part_cat_id): $catId\n")
                if (year.isNotEmpty()) append("Anno: $year\n")
                append("Affidabilità: $confidence%")
            }

            runOnUiThread {
                val density = resources.displayMetrics.density
                val dialogLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                    gravity = Gravity.CENTER_HORIZONTAL
                    setBackgroundColor(Color.parseColor("#1E1E1E"))
                }

                val imagesLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (150 * density).toInt()
                    ).apply {
                        setMargins(0, 0, 0, (12 * density).toInt())
                    }
                }

                if (capturedBitmap != null) {
                    val capturedImageView = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(4, 4, 4, 4)
                        }
                        setImageBitmap(capturedBitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    imagesLayout.addView(capturedImageView)
                }

                val dbImageView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, if (capturedBitmap != null) 1f else 2f).apply {
                        setMargins(4, 4, 4, 4)
                    }
                    if (dbBitmap != null) {
                        setImageBitmap(dbBitmap)
                    } else {
                        setBackgroundColor(Color.parseColor("#333333"))
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                imagesLayout.addView(dbImageView)
                dialogLayout.addView(imagesLayout)

                val labelsLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (12 * density).toInt())
                    }
                }

                if (capturedBitmap != null) {
                    val labelCaptured = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "Foto Scattata"
                        gravity = Gravity.CENTER
                        textSize = 12f
                        setTextColor(Color.parseColor("#AAAAAA"))
                    }
                    labelsLayout.addView(labelCaptured)
                }

                val labelDb = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (capturedBitmap != null) 1f else 2f)
                    text = "Database Rebrickable"
                    gravity = Gravity.CENTER
                    textSize = 12f
                    setTextColor(Color.parseColor("#AAAAAA"))
                }
                labelsLayout.addView(labelDb)
                dialogLayout.addView(labelsLayout)

                val detailsTextView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (8 * density).toInt(), 0, 0)
                    }
                    text = resultMessage
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.START
                    setLineSpacing(4f, 1.2f)
                }
                dialogLayout.addView(detailsTextView)

                val scrollView = ScrollView(this).apply {
                    addView(dialogLayout)
                }

                AlertDialog.Builder(this)
                    .setTitle("Riconoscimento Lego")
                    .setView(scrollView)
                    .setPositiveButton("OK", null)
                    .setOnDismissListener {
                        runOnUiThread {
                            if (isMaster) {
                                cameraContainer.visibility = View.VISIBLE
                                localPreviewView.visibility = View.VISIBLE
                                remoteImageView.visibility = View.GONE
                            } else {
                                if (isCameraBoxVisible) {
                                    cameraContainer.visibility = View.VISIBLE
                                    localPreviewView.visibility = View.GONE
                                    remoteImageView.visibility = View.VISIBLE
                                } else {
                                    cameraContainer.visibility = View.GONE
                                    remoteImageView.visibility = View.GONE
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun captureRemoteImage() {
        val masterPeer = synchronized(connectedPeers) { connectedPeers.values.find { it.role == "MASTER" } }
        if (masterPeer == null) {
            return
        }
        statusTextView.text = "Richiesta scatto foto remota al Master..."
        sendDataToPeer(masterPeer, 1, "CMD_TAKE_PHOTO".toByteArray())
    }

    private fun saveImage(bitmap: Bitmap) {
        val filename = "Remote_${System.currentTimeMillis()}.jpg"
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/remote")
            }
            val imageUri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            val fos = imageUri?.let { contentResolver.openOutputStream(it) }
            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                runOnUiThread {
                    statusTextView.text = "Foto salvata!"
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteCamera", "Errore salvataggio: ${e.message}")
        }
    }

    private fun rememberPeerIp(ip: String) {
        val currentSet = prefs.getStringSet(PREF_KNOWN_IPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (currentSet.add(ip)) {
            prefs.edit().putStringSet(PREF_KNOWN_IPS, currentSet).apply()
        }
    }

    private fun startAutoReconnectionLoop() {
        thread {
            while (isReconnecting) {
                try {
                    val knownIps = prefs.getStringSet(PREF_KNOWN_IPS, emptySet()) ?: emptySet()
                    val myIp = getLocalIpAddress()
                    for (ip in knownIps) {
                        if (ip != myIp && !connectedPeers.containsKey(ip)) {
                            try {
                                val address = InetAddress.getByName(ip)
                                val socket = Socket()
                                socket.connect(InetSocketAddress(address, FIXED_PORT), 1500)
                                handleConnection(socket, false)
                            } catch (e: Exception) {
                                // Nessuna connessione possibile verso questo IP al momento
                            }
                        }
                    }
                } catch (e: Exception) {}

                if (discoveryListener == null && nsdManager != null) {
                    runOnUiThread { discoverServices() }
                }

                Thread.sleep(4000)
            }
        }
    }

    private fun startManualScan() {
        statusTextView.text = "Scansione dispositivi in corso..."
        unregisterService()
        stopDiscovery()
        thread {
            Thread.sleep(300)
            runOnUiThread {
                registerService(localPort)
                discoverServices()
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (isMaster) {
                startCameraOnMaster()
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraOnMaster() {
        if (!isMaster) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(localPreviewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                activeImageCapture = imageCapture

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (isMaster && connectedPeers.isNotEmpty() && isStreamingRequested) {
                        try {
                            val bitmap = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val outBitmap = if (rotation != 0) {
                                val matrix = Matrix()
                                matrix.postRotate(rotation.toFloat())
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            } else {
                                bitmap
                            }
                            val stream = ByteArrayOutputStream()
                            outBitmap.compress(Bitmap.CompressFormat.JPEG, 35, stream)
                            val bytes = stream.toByteArray()

                            val slavePeers = synchronized(connectedPeers) {
                                connectedPeers.values.filter { it.role == "SLAVE" }
                            }
                            for (slave in slavePeers) {
                                try {
                                    synchronized(slave.outputStream) {
                                        slave.outputStream.writeByte(2)
                                        slave.outputStream.writeInt(bytes.size)
                                        slave.outputStream.write(bytes)
                                        slave.outputStream.flush()
                                    }
                                } catch (e: Exception) {
                                    disconnectPeer(slave.remoteIp)
                                }
                            }

                            if (outBitmap !== bitmap) outBitmap.recycle()
                            bitmap.recycle()
                        } catch (e: Exception) {
                            Log.e("RemoteCamera", "Analisi frame Master fallita: ${e.message}")
                        }
                    }
                    imageProxy.close()
                }

                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, currentCameraSelector, preview, imageAnalysis, imageCapture)
                enableAutofocus(true)

                runOnUiThread {
                    statusTextView.text = "Fotocamera attiva (Master in streaming agli Slave)"
                    localPreviewView.visibility = View.VISIBLE
                    remoteImageView.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("RemoteCamera", "Errore avvio fotocamera Master", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scaleBitmapMaxDimension(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val scale = maxDim.toFloat() / Math.max(w, h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun takeHighResPhoto(requesterPeer: PeerConnection?) {
        val capture = activeImageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    val matrix = Matrix()
                    matrix.postRotate(rotation.toFloat())
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    val scaledBitmap = when (currentResolutionLevel) {
                        0 -> scaleBitmapMaxDimension(rotatedBitmap, 1280)
                        1 -> scaleBitmapMaxDimension(rotatedBitmap, 1920)
                        else -> rotatedBitmap
                    }
                    val stream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    val photoBytes = stream.toByteArray()

                    // Salva la foto locale sul Master e mostrala (senza riciclarla perché usata da lastCapturedPhoto / remoteImageView)
                    saveImage(scaledBitmap)
                    lastCapturedPhoto = scaledBitmap

                    runOnUiThread {
                        remoteImageView.setImageBitmap(scaledBitmap)
                        remoteImageView.visibility = View.VISIBLE
                        cameraContainer.visibility = View.VISIBLE
                        localPreviewView.visibility = View.GONE
                        bottomActionsBar.visibility = View.VISIBLE
                        scanButton.visibility = View.VISIBLE
                        editPhotoButton.visibility = View.VISIBLE
                        recognizeLegoButton.visibility = View.VISIBLE
                        loadPhotoButton.visibility = View.VISIBLE

                        if (isPendingLegoRecognition) {
                            isPendingLegoRecognition = false
                            recognizeLegoPart()
                        }
                    }

                    // Invia la foto ad alta risoluzione allo slave richiedente (o a tutti gli slave)
                    if (requesterPeer != null) {
                        sendDataToPeer(requesterPeer, 3, photoBytes)
                        if (isPendingLegoRecognition) {
                            isPendingLegoRecognition = false
                            performLegoRecognitionForPeer(requesterPeer, scaledBitmap)
                        }
                    } else {
                        val slaves = synchronized(connectedPeers) { connectedPeers.values.filter { it.role == "SLAVE" } }
                        for (slave in slaves) {
                            sendDataToPeer(slave, 3, photoBytes)
                        }
                        if (isPendingLegoRecognition) {
                            isPendingLegoRecognition = false
                            performLegoRecognitionForPeer(null, scaledBitmap)
                        }
                    }

                    image.close()
                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e("RemoteCamera", "Errore elaborazione foto scattata: ${e.message}")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("RemoteCamera", "Scatto foto fallito: ${exception.message}")
            }
        })
    }

    private fun stopCamera() {
        runOnUiThread {
            try {
                cameraProvider?.unbindAll()
                camera = null
                activeImageCapture = null
            } catch (e: Exception) {}
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Sconosciuto"
                    }
                }
            }
        } catch (ex: Exception) { Log.e("RemoteCamera", "Error getting IP", ex) }
        return "Sconosciuto"
    }

    private fun startServer() {
        thread {
            try {
                val ss = try { ServerSocket(FIXED_PORT) } catch (e: Exception) { ServerSocket(0) }
                serverSocket = ss
                localPort = ss.localPort
                runOnUiThread {
                    registerService(localPort)
                    discoverServices()
                }
                while (isReconnecting) {
                    val client = ss.accept()
                    handleConnection(client, true)
                }
            } catch (e: Exception) { Log.e("RemoteCamera", "Server error", e) }
        }
    }

    private fun handleConnection(socket: Socket, isIncoming: Boolean) {
        val remoteIp = socket.inetAddress?.hostAddress ?: return

        synchronized(connectedPeers) {
            val existing = connectedPeers[remoteIp]
            if (existing != null && existing.socket.isConnected && !existing.socket.isClosed) {
                socket.close()
                return
            }
        }

        try {
            val out = DataOutputStream(socket.getOutputStream())
            val peer = PeerConnection(socket, out, remoteIp)
            connectedPeers[remoteIp] = peer

            rememberPeerIp(remoteIp)

            runOnUiThread {
                updateConnectionStatusUI()
                if (isEditingLocally) {
                    distributePalettesToPeers()
                }
            }

            // Invia handshake iniziale con ruolo e nome dispositivo
            val myRole = if (isMaster) "MASTER" else "SLAVE"
            val handshakeMsg = "CMD_HELLO:$myRole:${Build.MODEL.replace(":", "_")}"
            sendDataToPeer(peer, 1, handshakeMsg.toByteArray())

            thread {
                readLoop(peer)
            }
        } catch (e: Exception) {
            Log.e("RemoteCamera", "Errore handshake con $remoteIp: ${e.message}")
            try { socket.close() } catch (ex: Exception) {}
        }
    }

    private fun readLoop(peer: PeerConnection) {
        try {
            val inputStream = DataInputStream(peer.socket.getInputStream())
            while (peer.isAlive && !peer.socket.isClosed && isReconnecting) {
                val type = inputStream.readByte().toInt()
                val length = inputStream.readInt()
                if (length < 0 || length > 30 * 1024 * 1024) {
                    throw IllegalStateException("Dimensione payload non valida: $length")
                }
                val payload = ByteArray(length)
                inputStream.readFully(payload)

                when (type) {
                    1 -> {
                        val message = String(payload)
                        processControlMessage(peer, message)
                    }
                    2 -> {
                        // Frame video ricevuto dal Master sullo Slave
                        if (!isMaster && isCameraBoxVisible) {
                            val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                            if (bitmap != null) {
                                runOnUiThread {
                                    val oldBitmap = lastReceivedBitmap
                                    lastReceivedBitmap = bitmap
                                    remoteImageView.setImageBitmap(bitmap)
                                    oldBitmap?.recycle()
                                }
                            }
                        }
                    }
                    3 -> {
                        // Foto ad alta risoluzione ricevuta dall'altro smartphone
                        val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                        if (bitmap != null) {
                            lastCapturedPhoto = bitmap
                            saveImage(bitmap)
                            runOnUiThread {
                                remoteImageView.setImageBitmap(bitmap)
                                remoteImageView.visibility = View.VISIBLE
                                cameraContainer.visibility = View.VISIBLE
                                editPhotoButton.visibility = View.VISIBLE
                                recognizeLegoButton.visibility = View.VISIBLE

                                if (isPendingLegoRecognition) {
                                    isPendingLegoRecognition = false
                                    recognizeLegoPart()
                                }
                            }
                        }
                    }
                    4 -> {
                        // Risultato riconoscimento Lego con foto temporanea in memoria (senza salvataggio su disco)
                        val buffer = ByteBuffer.wrap(payload)
                        val photoLen = buffer.int
                        val photoBytes = ByteArray(photoLen)
                        buffer.get(photoBytes)
                        val tempBitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)

                        val strBytes = ByteArray(buffer.remaining())
                        buffer.get(strBytes)
                        val message = String(strBytes, Charsets.UTF_8)

                        val parts = message.split("::")
                        val partNum = parts.getOrNull(1) ?: ""
                        val partName = parts.getOrNull(2) ?: ""
                        val category = parts.getOrNull(3) ?: ""
                        val catId = parts.getOrNull(4) ?: ""
                        val year = parts.getOrNull(5) ?: ""
                        val confidence = parts.getOrNull(6) ?: "0"
                        val imgUrl = parts.getOrNull(7) ?: ""

                        showLegoResultDialog(tempBitmap, partNum, partName, category, catId, year, confidence, imgUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("RemoteCamera", "Connessione chiusa con ${peer.remoteIp}")
        } finally {
            disconnectPeer(peer.remoteIp)
        }
    }

    private fun disconnectPeer(ip: String) {
        val peer = connectedPeers.remove(ip)
        peer?.isAlive = false
        try { peer?.socket?.close() } catch (e: Exception) {}

        runOnUiThread {
            updateConnectionStatusUI()
            if (isEditingLocally) {
                distributePalettesToPeers()
            }
            if (connectedPeers.isEmpty() && !isMaster) {
                remoteImageView.setImageBitmap(null)
                lastReceivedBitmap = null
            }
        }
    }

    private fun processControlMessage(peer: PeerConnection, message: String) {
        if (message.startsWith("CMD_HELLO:")) {
            val parts = message.split(":")
            val remoteRole = parts.getOrNull(1) ?: "SLAVE"
            val remoteName = parts.getOrNull(2) ?: peer.remoteIp
            peer.role = remoteRole
            peer.deviceName = remoteName

            if (remoteRole == "MASTER" && isMaster) {
                // Uno solo diventa master: risoluzione conflitto deterministica
                val localIp = getLocalIpAddress()
                if (localIp < peer.remoteIp) {
                    runOnUiThread {
                        setMasterRole(false, broadcast = false)
                    }
                } else {
                    sendDataToPeer(peer, 1, "CMD_CLAIM_MASTER:${Build.MODEL.replace(":", "_")}".toByteArray())
                }
            } else if (remoteRole == "MASTER") {
                runOnUiThread {
                    updateRoleUI()
                    updateConnectionStatusUI()
                    if (isCameraBoxVisible) {
                        sendControlMessageToMaster("CMD_START_STREAM")
                    }
                }
            }

            runOnUiThread { updateConnectionStatusUI() }

        } else if (message.startsWith("CMD_CLAIM_MASTER") || message.startsWith("CMD_SET_ROLE:MASTER")) {
            peer.role = "MASTER"

            // Uno solo diventa master: se ero master, divento slave
            runOnUiThread {
                if (isMaster) {
                    setMasterRole(false, broadcast = false)
                } else {
                    updateRoleUI()
                }
                updateConnectionStatusUI()
                if (isCameraBoxVisible) {
                    sendControlMessageToMaster("CMD_START_STREAM")
                }
            }

        } else if (message == "CMD_SET_ROLE:SLAVE") {
            peer.role = "SLAVE"
            runOnUiThread { updateConnectionStatusUI() }

        } else if (message.startsWith("CMD_SET_RESOLUTION:")) {
            if (isMaster) {
                val level = message.substringAfter("CMD_SET_RESOLUTION:").toIntOrNull() ?: 1
                currentResolutionLevel = level
            }

        } else if (message.startsWith("CMD_SET_ZOOM:")) {
            if (isMaster) {
                val progress = message.substringAfter("CMD_SET_ZOOM:").toIntOrNull() ?: 0
                runOnUiThread {
                    zoomSeekBar.progress = progress
                    val cam = camera
                    if (cam != null) {
                        val zoomState = cam.cameraInfo.zoomState.value
                        if (zoomState != null) {
                            val min = zoomState.minZoomRatio
                            val max = zoomState.maxZoomRatio
                            val ratio = min + (progress / 100f) * (max - min)
                            cam.cameraControl.setZoomRatio(ratio)
                            zoomLabelTextView.text = String.format(Locale.getDefault(), "Zoom: %.1fx", ratio)
                        }
                    }
                }
            }

        } else if (message == "CMD_RECOGNISE_LEGO") {
            if (isMaster) {
                val capture = activeImageCapture
                if (capture != null) {
                    capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val rawBmp = image.toBitmap()
                                val rotation = image.imageInfo.rotationDegrees
                                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotatedBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                                val scaledBmp = when (currentResolutionLevel) {
                                    0 -> scaleBitmapMaxDimension(rotatedBmp, 1280)
                                    1 -> scaleBitmapMaxDimension(rotatedBmp, 1920)
                                    else -> rotatedBmp
                                }
                                performLegoRecognitionForPeer(peer, scaledBmp)
                                image.close()
                                rawBmp.recycle()
                                if (rotatedBmp !== rawBmp) rotatedBmp.recycle()
                            } catch (e: Exception) {
                                Log.e("RemoteCamera", "Errore scatto per peer: ${e.message}")
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {}
                    })
                }
            }

        } else if (message.startsWith("CMD_LEGO_RESULT::")) {
            val parts = message.split("::")
            val partNum = parts.getOrNull(1) ?: ""
            val partName = parts.getOrNull(2) ?: ""
            val category = parts.getOrNull(3) ?: ""
            val catId = parts.getOrNull(4) ?: ""
            val year = parts.getOrNull(5) ?: ""
            val confidence = parts.getOrNull(6) ?: "0"
            val imgUrl = parts.getOrNull(7) ?: ""

            showLegoResultDialog(lastCapturedPhoto, partNum, partName, category, catId, year, confidence, imgUrl)

        } else if (message.startsWith("CMD_LEGO_ERROR::")) {
            val errorMsg = message.substringAfter("CMD_LEGO_ERROR::")
        } else if (message == "CMD_START_STREAM") {
            if (isMaster) {
                isStreamingRequested = true
            }

        } else if (message == "CMD_STOP_STREAM") {
            if (isMaster) {
                isStreamingRequested = false
            }

        } else if (message == "CMD_TAKE_PHOTO") {
            if (isMaster) {
                takeHighResPhoto(peer)
            }

        } else if (message == "CMD_AF_ON") {
            if (isMaster) enableAutofocus(true)

        } else if (message == "CMD_AF_OFF") {
            if (isMaster) enableAutofocus(false)

        } else if (message.startsWith("CMD_FOCUS_SET:")) {
            if (isMaster) {
                val value = message.substringAfter("CMD_FOCUS_SET:").toFloatOrNull() ?: 0f
                setManualFocus(value / 100f)
            }

        } else if (message == "CMD_GET_CAMERA_LIST") {
            if (isMaster) {
                sendCameraListToPeer(peer)
            }

        } else if (message.startsWith("CMD_CAMERA_LIST:")) {
            val listString = message.substringAfter("CMD_CAMERA_LIST:")
            runOnUiThread { showRemoteCameraSelectionMenu(listString) }

        } else if (message.startsWith("CMD_SET_CAMERA_ID:")) {
            if (isMaster) {
                val cameraId = message.substringAfter("CMD_SET_CAMERA_ID:")
                runOnUiThread { switchCameraById(cameraId) }
            }

        } else if (message.startsWith("CMD_CAMERA_CHANGED:")) {
            val info = message.substringAfter("CMD_CAMERA_CHANGED:")

        } else if (message.startsWith("CMD_START_DRAWING")) {
            runOnUiThread {
                isEditingLocally = false
                fullScreenDrawingContainer.visibility = View.GONE
                backToDrawingButton.visibility = View.GONE
                slavePaletteContainer.bringToFront()
                slavePaletteContainer.visibility = View.VISIBLE

                val config = if (message.contains(":")) message.substringAfter("CMD_START_DRAWING:") else "1,2,3"
                parseAndApplyPaletteAssignment(config)
            }

        } else if (message.startsWith("CMD_SET_PALETTE_ASSIGNMENT:")) {
            val config = message.substringAfter("CMD_SET_PALETTE_ASSIGNMENT:")
            runOnUiThread {
                isEditingLocally = false
                fullScreenDrawingContainer.visibility = View.GONE
                backToDrawingButton.visibility = View.GONE
                slavePaletteContainer.bringToFront()
                slavePaletteContainer.visibility = View.VISIBLE

                parseAndApplyPaletteAssignment(config)
            }

        } else if (message.startsWith("CMD_SWAP_PALETTE:")) {
            val parts = message.substringAfter("CMD_SWAP_PALETTE:").split(":")
            val givenPalette = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val takenPalette = parts.getOrNull(1)?.toIntOrNull() ?: 2
            runOnUiThread {
                if (assignedPaletteIds.contains(takenPalette)) {
                    applyPaletteVisibility(setOf(givenPalette))
                }
            }

        } else if (message == "CMD_STOP_DRAWING") {
            runOnUiThread {
                isEditingLocally = false
                stopDrawingMode()
            }

        } else if (message == "CMD_CLOSE_APP") {
            runOnUiThread {
                cleanupAndExit(broadcast = false)
            }

        } else if (message.startsWith("CMD_SET_DRAW_COLOR:")) {
            val colorStr = message.substringAfter("CMD_SET_DRAW_COLOR:")
            val colorInt = colorStr.toIntOrNull() ?: Color.RED
            runOnUiThread {
                syncColorFromRemote(colorInt)
            }

        } else if (message.startsWith("CMD_SET_DRAW_SIZE:")) {
            val sizeStr = message.substringAfter("CMD_SET_DRAW_SIZE:")
            val sizeFloat = sizeStr.toFloatOrNull() ?: 12f
            runOnUiThread {
                drawingView.setStrokeWidth(sizeFloat)
                brushSizeValueTextView.text = "${sizeFloat.toInt()} px"
                updateBrushPreview(sizeFloat.toInt())
            }
        }
    }

    private fun sendDataToPeer(peer: PeerConnection, type: Int, data: ByteArray) {
        networkExecutor.execute {
            try {
                synchronized(peer.outputStream) {
                    peer.outputStream.writeByte(type)
                    peer.outputStream.writeInt(data.size)
                    peer.outputStream.write(data)
                    peer.outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e("RemoteCamera", "Errore invio a ${peer.remoteIp}: ${e.message}")
                disconnectPeer(peer.remoteIp)
            }
        }
    }

    private fun sendLegoResultToPeer(peer: PeerConnection, photoBytes: ByteArray, resultString: String) {
        networkExecutor.execute {
            try {
                val resultBytes = resultString.toByteArray(Charsets.UTF_8)
                val totalSize = 4 + photoBytes.size + resultBytes.size
                val buffer = ByteBuffer.allocate(totalSize)
                buffer.putInt(photoBytes.size)
                buffer.put(photoBytes)
                buffer.put(resultBytes)
                val payload = buffer.array()

                synchronized(peer.outputStream) {
                    peer.outputStream.writeByte(4)
                    peer.outputStream.writeInt(payload.size)
                    peer.outputStream.write(payload)
                    peer.outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e("RemoteCamera", "Errore invio risultato Lego a ${peer.remoteIp}: ${e.message}")
                disconnectPeer(peer.remoteIp)
            }
        }
    }

    private fun broadcastControlMessage(message: String) {
        val peers = synchronized(connectedPeers) { connectedPeers.values.toList() }
        val data = message.toByteArray()
        for (peer in peers) {
            sendDataToPeer(peer, 1, data)
        }
    }

    private fun sendControlMessageToMaster(message: String) {
        val masterPeer = synchronized(connectedPeers) { connectedPeers.values.find { it.role == "MASTER" } }
        if (masterPeer != null) {
            sendDataToPeer(masterPeer, 1, message.toByteArray())
        } else {
            broadcastControlMessage(message)
        }
    }

    @OptIn(ExperimentalLensFacing::class, ExperimentalCamera2Interop::class)
    private fun sendCameraListToPeer(peer: PeerConnection) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val cameraInfos = provider.availableCameraInfos
                val manager = getSystemService(CAMERA_SERVICE) as CameraManager
                val allIds = manager.cameraIdList

                val listString = cameraInfos.mapIndexed { _, info ->
                    val cam2Info = Camera2CameraInfo.from(info)
                    val id = cam2Info.cameraId
                    val facingInt = info.lensFacing
                    val facing = when (facingInt) {
                        CameraSelector.LENS_FACING_BACK -> "Posteriore"
                        CameraSelector.LENS_FACING_FRONT -> "Anteriore"
                        CameraSelector.LENS_FACING_EXTERNAL -> "Esterna (USB)"
                        else -> "Camera $id (Tipo $facingInt)"
                    }
                    "$facing|$id"
                }.joinToString(";")

                val existingIds = cameraInfos.map { Camera2CameraInfo.from(it).cameraId }
                val missingIds = allIds.filter { it !in existingIds }

                val missingString = missingIds.map { id ->
                    "Sconosciuta (Forzata)|$id"
                }.joinToString(";")

                val fullList = if (missingString.isEmpty()) listString else "$listString;$missingString"

                sendDataToPeer(peer, 1, "CMD_CAMERA_LIST:${if (fullList.isEmpty()) "Nessuna camera" else fullList}".toByteArray())
            } catch (e: Exception) {
                sendDataToPeer(peer, 1, "CMD_CAMERA_LIST:Errore".toByteArray())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showRemoteCameraSelectionMenu(listString: String) {
        if (listString == "Nessuna camera" || listString == "Errore") {
            return
        }
        val itemsData = listString.split(";")
        val displayItems = itemsData.map { it.substringBefore("|") }.toTypedArray()
        val ids = itemsData.map { it.substringAfter("|") }

        AlertDialog.Builder(this)
            .setTitle("Scegli Telecamera del Master")
            .setItems(displayItems) { _, which ->
                sendControlMessageToMaster("CMD_SET_CAMERA_ID:${ids[which]}")
            }
            .setNeutralButton("Aggiorna") { _, _ -> sendControlMessageToMaster("CMD_GET_CAMERA_LIST") }
            .show()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun switchCameraById(cameraId: String) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                currentCameraSelector = CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                    }.build()
                if (isMaster) {
                    startCameraOnMaster()
                }
                broadcastControlMessage("CMD_CAMERA_CHANGED:$cameraId")
            } catch (e: Exception) {
                Log.e("RemoteCamera", "Errore switch ID", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalLensFacing::class, ExperimentalCamera2Interop::class)
    private fun showCameraSelectionMenu() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val cameraInfos = provider.availableCameraInfos
                val manager = getSystemService(CAMERA_SERVICE) as CameraManager
                val allIds = manager.cameraIdList

                val itemsData = mutableListOf<Pair<String, String>>()

                cameraInfos.forEach { info ->
                    val cam2Info = Camera2CameraInfo.from(info)
                    val id = cam2Info.cameraId
                    val facingInt = info.lensFacing
                    val name = when (facingInt) {
                        CameraSelector.LENS_FACING_BACK -> "Posteriore"
                        CameraSelector.LENS_FACING_FRONT -> "Anteriore"
                        CameraSelector.LENS_FACING_EXTERNAL -> "Esterna (USB)"
                        else -> "Camera $id (Tipo $facingInt)"
                    }
                    itemsData.add(name to id)
                }

                val existingIds = itemsData.map { it.second }
                allIds.filter { it !in existingIds }.forEach { id ->
                    itemsData.add("Sconosciuta (Forzata) $id" to id)
                }

                val displayItems = itemsData.map { it.first }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("Scegli Telecamera Locale (Master)")
                    .setItems(displayItems) { _, which ->
                        switchCameraById(itemsData[which].second)
                    }
                    .setNeutralButton("Aggiorna") { _, _ -> showCameraSelectionMenu() }
                    .show()
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enableAutofocus(enable: Boolean) {
        val cam = camera ?: return
        val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
        if (enable) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        camera2CameraControl.captureRequestOptions = options.build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setManualFocus(distance: Float) {
        val cam = camera ?: return
        val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance * 10f)
        camera2CameraControl.captureRequestOptions = options.build()
    }

    @Volatile
    private var isClosingApp = false

    private fun broadcastControlMessageSync(message: String) {
        val peers = synchronized(connectedPeers) { connectedPeers.values.toList() }
        val data = message.toByteArray()
        for (peer in peers) {
            try {
                synchronized(peer.outputStream) {
                    peer.outputStream.writeByte(1)
                    peer.outputStream.writeInt(data.size)
                    peer.outputStream.write(data)
                    peer.outputStream.flush()
                }
            } catch (e: Exception) {}
        }
    }

    private fun cleanupAndExit(broadcast: Boolean = true) {
        if (isClosingApp) return
        isClosingApp = true
        isReconnecting = false

        thread {
            try {
                if (broadcast) {
                    try {
                        broadcastControlMessageSync("CMD_CLOSE_APP")
                        Thread.sleep(150)
                    } catch (e: Exception) {}
                }

                cameraExecutor.shutdownNow()
                networkExecutor.shutdownNow()
                runOnUiThread { cameraProvider?.unbindAll() }
                unregisterService()
                stopDiscovery()
                synchronized(connectedPeers) {
                    for (peer in connectedPeers.values) {
                        try { peer.socket.close() } catch (e: Exception) {}
                    }
                    connectedPeers.clear()
                }
                serverSocket?.close()
            } catch (e: Exception) {
            } finally {
                runOnUiThread { finishAndRemoveTask() }
            }
        }
    }

    private fun registerService(port: Int) {
        if (registrationListener != null || port <= 0) return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${this@MainActivity.serviceName}-$port"
            serviceType = this@MainActivity.serviceType
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                this@MainActivity.serviceName = registeredInfo.serviceName
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) { registrationListener = null }
            override fun onServiceUnregistered(s: NsdServiceInfo) { registrationListener = null }
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) { registrationListener = null }
        }
        try { nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener) } catch (e: Exception) { registrationListener = null }
    }

    private fun unregisterService() {
        registrationListener?.let { try { nsdManager?.unregisterService(it) } catch (e: Exception) {} }
        registrationListener = null
    }

    private fun discoverServices() {
        if (discoveryListener != null) return
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if ((service.serviceType.contains("remotecamera") || service.serviceType.contains(serviceType)) && service.serviceName != serviceName) {
                    try {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                            override fun onServiceResolved(s: NsdServiceInfo) {
                                connectToServer(s.host, s.port)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e("RemoteCamera", "Errore resolveService: ${e.message}")
                    }
                }
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStopped(s: String) { discoveryListener = null }
            override fun onStartDiscoveryFailed(s: String, e: Int) { discoveryListener = null }
            override fun onStopDiscoveryFailed(s: String, e: Int) { discoveryListener = null }
        }
        try { nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener) } catch (e: Exception) { discoveryListener = null }
    }

    private fun stopDiscovery() {
        discoveryListener?.let { try { nsdManager?.stopServiceDiscovery(it) } catch (e: Exception) {} }
        discoveryListener = null
    }

    private fun connectToServer(address: InetAddress, port: Int) {
        val host = address.hostAddress ?: return
        if (host == getLocalIpAddress()) return
        if (connectedPeers.containsKey(host)) return

        thread {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(address, port), 2500)
                handleConnection(socket, false)
            } catch (e: Exception) {
                Log.d("RemoteCamera", "Connessione fallita a $host:$port")
            }
        }
    }

    override fun onDestroy() {
        if (!isClosingApp) {
            cleanupAndExit(broadcast = true)
        }
        super.onDestroy()
    }
}
