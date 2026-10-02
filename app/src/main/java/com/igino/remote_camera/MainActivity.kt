package com.igino.remote_camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var localIpTextView: TextView
    private lateinit var remoteIpTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var roleSwitch: SwitchCompat
    private lateinit var scanButton: ImageButton
    private lateinit var editPhotoButton: ImageButton
    private lateinit var loadPhotoButton: ImageButton
    private lateinit var captureButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var remoteImageView: ImageView
    private lateinit var localPreviewView: PreviewView
    
    private lateinit var focusControlLayout: View
    private lateinit var autofocusSwitch: SwitchCompat
    private lateinit var focusSeekBar: SeekBar

    private lateinit var fullScreenDrawingContainer: View
    private lateinit var drawingView: DrawingView
    private lateinit var drawingColorIndicator: View
    private lateinit var clearDrawingButton: Button
    private lateinit var saveDrawingButton: Button
    private lateinit var closeDrawingButton: ImageButton

    private lateinit var slavePaletteContainer: View
    private lateinit var colorPaletteLayout: LinearLayout
    private lateinit var closePaletteButton: Button

    private var nsdManager: NsdManager? = null
    private var serviceName: String = "RemoteCamera-${Build.MODEL.replace(" ", "_")}"
    private val serviceType = "_remotecamera._tcp"
    private val FIXED_PORT = 9000
    private var localPort: Int = -1
    private var serverSocket: ServerSocket? = null
    
    private var activeSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var isUpdatingFromRemote = false
    private var isReconnecting = true

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private lateinit var prefs: SharedPreferences
    private val PREF_LAST_IP = "last_remote_ip"
    private val PREF_ROLE = "last_role"

    private var lastReceivedBitmap: Bitmap? = null
    private var lastCapturedPhoto: Bitmap? = null
    private var isDrawingMode = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraIfSlave()
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
                Toast.makeText(this, "Foto caricata con successo", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Impossibile caricare l'immagine", Toast.LENGTH_SHORT).show()
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
            roleSwitch = findViewById(R.id.roleSwitch)
            scanButton = findViewById(R.id.scanButton)
            editPhotoButton = findViewById(R.id.editPhotoButton)
            loadPhotoButton = findViewById(R.id.loadPhotoButton)
            captureButton = findViewById(R.id.captureButton)
            switchCameraButton = findViewById(R.id.switchCameraButton)
            closeButton = findViewById(R.id.closeButton)
            remoteImageView = findViewById(R.id.remoteImageView)
            localPreviewView = findViewById(R.id.localPreviewView)
            
            focusControlLayout = findViewById(R.id.focusControlLayout)
            autofocusSwitch = findViewById(R.id.autofocusSwitch)
            focusSeekBar = findViewById(R.id.focusSeekBar)

            fullScreenDrawingContainer = findViewById(R.id.fullScreenDrawingContainer)
            drawingView = findViewById(R.id.drawingView)
            drawingColorIndicator = findViewById(R.id.drawingColorIndicator)
            clearDrawingButton = findViewById(R.id.clearDrawingButton)
            saveDrawingButton = findViewById(R.id.saveDrawingButton)
            closeDrawingButton = findViewById(R.id.closeDrawingButton)

            slavePaletteContainer = findViewById(R.id.slavePaletteContainer)
            colorPaletteLayout = findViewById(R.id.colorPaletteLayout)
            closePaletteButton = findViewById(R.id.closePaletteButton)

            setupColorPalette()

            cameraExecutor = Executors.newSingleThreadExecutor()

            val localIp = getLocalIpAddress()
            localIpTextView.text = "Indirizzo Locale: $localIp"

            nsdManager = getSystemService(NSD_SERVICE) as? NsdManager

            val lastRoleIsSlave = prefs.getBoolean(PREF_ROLE, Build.MODEL.hashCode() % 2 == 0)
            roleSwitch.isChecked = lastRoleIsSlave
            updateRoleUI(lastRoleIsSlave)

            roleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingFromRemote) {
                    prefs.edit().putBoolean(PREF_ROLE, isChecked).apply()
                    sendControlMessage(if (isChecked) "CMD_SET_ROLE:MASTER" else "CMD_SET_ROLE:SLAVE")
                }
                updateRoleUI(isChecked)
            }
            
            autofocusSwitch.setOnCheckedChangeListener { _, isChecked ->
                focusSeekBar.visibility = if (isChecked) View.GONE else View.VISIBLE
                if (isChecked) {
                    sendControlMessage("CMD_AF_ON")
                } else {
                    sendControlMessage("CMD_AF_OFF")
                }
            }
            
            focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        sendControlMessage("CMD_FOCUS_SET:$progress")
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            scanButton.setOnClickListener {
                startManualScan()
            }

            editPhotoButton.setOnClickListener {
                if (lastCapturedPhoto == null) {
                    Toast.makeText(this, "Nessuna foto disponibile da modificare", Toast.LENGTH_SHORT).show()
                } else {
                    startDrawingModeOnMaster()
                    sendControlMessage("CMD_START_DRAWING")
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
                } else {
                    Toast.makeText(this, "Errore salvataggio foto modificata", Toast.LENGTH_SHORT).show()
                }
            }

            closeDrawingButton.setOnClickListener {
                stopDrawingModeOnMaster()
                sendControlMessage("CMD_STOP_DRAWING")
            }

            closePaletteButton.setOnClickListener {
                stopDrawingModeOnSlave()
                sendControlMessage("CMD_STOP_DRAWING")
            }

            captureButton.setOnClickListener {
                captureRemoteImage()
            }

            switchCameraButton.setOnClickListener {
                if (roleSwitch.isChecked) {
                    showCameraSelectionMenu()
                } else {
                    sendControlMessage("CMD_GET_CAMERA_LIST")
                }
            }

            closeButton.setOnClickListener {
                cleanupAndExit()
            }

            startServer()
            startAutoReconnectionLoop()

        } catch (e: Exception) {
            Log.e("RemoteCamera", "Crash in onCreate", e)
        }
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
                    sendControlMessage("CMD_SET_DRAW_COLOR:$color")
                    Toast.makeText(this@MainActivity, "Colore $name inviato al Master", Toast.LENGTH_SHORT).show()
                }
            }

            currentRow?.addView(itemLayout)
        }
    }

    private fun startDrawingModeOnMaster() {
        isDrawingMode = true
        val photo = lastCapturedPhoto ?: return
        runOnUiThread {
            fullScreenDrawingContainer.visibility = View.VISIBLE
            drawingView.setPhoto(photo)
            drawingView.setStrokeColor(Color.RED)
            drawingColorIndicator.setBackgroundColor(Color.RED)
        }
    }

    private fun stopDrawingModeOnMaster() {
        isDrawingMode = false
        runOnUiThread {
            fullScreenDrawingContainer.visibility = View.GONE
        }
    }

    private fun startDrawingModeOnSlave() {
        isDrawingMode = true
        stopCamera()
        runOnUiThread {
            slavePaletteContainer.visibility = View.VISIBLE
        }
    }

    private fun stopDrawingModeOnSlave() {
        isDrawingMode = false
        runOnUiThread {
            slavePaletteContainer.visibility = View.GONE
            if (roleSwitch.isChecked) {
                startCameraIfSlave()
            }
        }
    }

    private fun captureRemoteImage() {
        if (activeSocket == null || dataOutputStream == null) {
            Toast.makeText(this, "Non connesso", Toast.LENGTH_SHORT).show()
            return
        }
        statusTextView.text = "Richiesta scatto alta risoluzione..."
        sendControlMessage("CMD_TAKE_PHOTO")
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
                    Toast.makeText(this, "Foto salvata ($filename)", Toast.LENGTH_SHORT).show()
                    statusTextView.text = "Foto salvata!"
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteCamera", "Errore salvataggio: ${e.message}")
            runOnUiThread { Toast.makeText(this, "Errore durante il salvataggio", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun startAutoReconnectionLoop() {
        thread {
            while (isReconnecting) {
                if (activeSocket == null || !activeSocket!!.isConnected) {
                    val lastIp = prefs.getString(PREF_LAST_IP, null)
                    if (lastIp != null) {
                        runOnUiThread { statusTextView.text = "Tentativo riconnessione: $lastIp..." }
                        try {
                            val address = InetAddress.getByName(lastIp)
                            val socket = Socket()
                            socket.connect(InetSocketAddress(address, FIXED_PORT), 2000)
                            handleConnection(socket, false)
                        } catch (e: Exception) {
                            Log.d("RemoteCamera", "Riconnessione fallita, riprovo tra 3s")
                        }
                    }
                }
                Thread.sleep(3000)
            }
        }
    }

    private fun startManualScan() {
        val isSlave = roleSwitch.isChecked
        statusTextView.text = if (isSlave) "In cerca di Master..." else "Configurazione Master..."
        unregisterService()
        stopDiscovery()
        thread {
            Thread.sleep(300)
            runOnUiThread {
                if (!isSlave) {
                    registerService(localPort)
                    statusTextView.text = "Modalità: MASTER (Visibile)"
                } else {
                    discoverServices()
                }
            }
        }
    }

    private fun updateRoleUI(isSlave: Boolean) {
        runOnUiThread {
            stopDrawingModeOnMaster()
            stopDrawingModeOnSlave()
            if (isSlave) {
                statusTextView.text = "Modalità: SLAVE"
                remoteImageView.visibility = View.GONE
                remoteImageView.setImageBitmap(null)
                lastReceivedBitmap = null
                captureButton.visibility = View.GONE
                focusControlLayout.visibility = View.GONE
                editPhotoButton.visibility = View.GONE
                loadPhotoButton.visibility = View.GONE
                localPreviewView.visibility = View.VISIBLE
                checkCameraPermission()
            } else {
                statusTextView.text = "Modalità: MASTER"
                remoteImageView.visibility = View.VISIBLE
                captureButton.visibility = View.VISIBLE
                focusControlLayout.visibility = View.VISIBLE
                editPhotoButton.visibility = View.VISIBLE
                loadPhotoButton.visibility = View.VISIBLE
                localPreviewView.visibility = View.GONE
                stopCamera()
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraIfSlave()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraIfSlave() {
        if (!roleSwitch.isChecked) return
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
                
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (roleSwitch.isChecked && activeSocket?.isConnected == true && !isDrawingMode) {
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
                            sendData(2, stream.toByteArray())
                            if (outBitmap !== bitmap) outBitmap.recycle()
                            bitmap.recycle()
                        } catch (e: Exception) {
                            Log.e("RemoteCamera", "Analisi fallita: ${e.message}")
                        }
                    }
                    imageProxy.close()
                }
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, currentCameraSelector, preview, imageAnalysis)
                // Default AF ON
                enableAutofocus(true)
            } catch (e: Exception) {
                Log.e("RemoteCamera", "Errore avvio camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeHighResPhoto() {
        if (!roleSwitch.isChecked) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(this, currentCameraSelector, imageCapture)
                imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        val matrix = Matrix()
                        matrix.postRotate(rotation.toFloat())
                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        val stream = ByteArrayOutputStream()
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                        sendData(3, stream.toByteArray())
                        image.close()
                        bitmap.recycle()
                        if (rotatedBitmap !== bitmap) rotatedBitmap.recycle()
                        runOnUiThread { provider.unbind(imageCapture) }
                    }
                    override fun onError(exception: ImageCaptureException) { Log.e("RemoteCamera", "Scatto fallito", exception) }
                })
            } catch (e: Exception) { Log.e("RemoteCamera", "Errore Photo Capture", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runOnUiThread {
            try {
                cameraProvider?.unbindAll()
                camera = null
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
                runOnUiThread { registerService(localPort) }
                while (true) {
                    val client = ss.accept()
                    handleConnection(client, true)
                }
            } catch (e: Exception) { Log.e("RemoteCamera", "Server error", e) }
        }
    }

    private fun handleConnection(socket: Socket, isIncoming: Boolean) {
        if (activeSocket?.isConnected == true && activeSocket?.inetAddress?.hostAddress == socket.inetAddress.hostAddress) {
            socket.close()
            return
        }
        activeSocket?.close()
        activeSocket = socket
        val remoteIp = socket.inetAddress.hostAddress
        if (remoteIp != null) { prefs.edit().putString(PREF_LAST_IP, remoteIp).apply() }
        runOnUiThread {
            remoteIpTextView.text = "Indirizzo Remoto: $remoteIp"
            statusTextView.text = "Connesso a $remoteIp"
        }
        thread {
            try {
                dataOutputStream = DataOutputStream(socket.getOutputStream())
                val myRole = if (roleSwitch.isChecked) "SLAVE" else "MASTER"
                val connectionType = if (isIncoming) "SERVER" else "CLIENT"
                sendControlMessage("CMD_SYNC_ROLE:$myRole:$connectionType")
                val dataInputStream = DataInputStream(socket.getInputStream())
                while (true) {
                    val type = dataInputStream.readByte().toInt()
                    val length = dataInputStream.readInt()
                    val payload = ByteArray(length)
                    dataInputStream.readFully(payload)
                    if (type == 1) {
                        processControlMessage(String(payload), isIncoming)
                    } else if (type == 2) {
                        if (!roleSwitch.isChecked) {
                            val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                            runOnUiThread { 
                                val oldBitmap = lastReceivedBitmap
                                lastReceivedBitmap = bitmap
                                remoteImageView.setImageBitmap(bitmap)
                                oldBitmap?.recycle()
                            }
                        }
                    } else if (type == 3) {
                        if (!roleSwitch.isChecked) {
                            val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                            lastCapturedPhoto = bitmap
                            saveImage(bitmap)
                        }
                    }
                }
            } catch (e: Exception) { Log.e("RemoteCamera", "Connection closed") } finally {
                activeSocket = null
                runOnUiThread {
                    statusTextView.text = "Disconnesso. Riprovo..."
                    remoteIpTextView.text = "Indirizzo Remoto: -"
                    remoteImageView.setImageBitmap(null)
                    lastReceivedBitmap = null
                    stopDrawingModeOnMaster()
                    stopDrawingModeOnSlave()
                    stopCamera()
                }
            }
        }
    }

    private fun processControlMessage(message: String, isIncoming: Boolean) {
        if (message.startsWith("CMD_SYNC_ROLE:")) {
            val parts = message.split(":")
            if (parts.size >= 3) {
                val remoteRoleIsSlave = parts[1] == "SLAVE"
                runOnUiThread {
                    val myRoleIsSlave = roleSwitch.isChecked
                    if (myRoleIsSlave == remoteRoleIsSlave) {
                        if (!isIncoming) {
                            isUpdatingFromRemote = true
                            roleSwitch.isChecked = !myRoleIsSlave
                            updateRoleUI(roleSwitch.isChecked)
                            isUpdatingFromRemote = false
                            prefs.edit().putBoolean(PREF_ROLE, roleSwitch.isChecked).apply()
                        }
                    }
                }
            }
        } else if (message.startsWith("CMD_SET_ROLE:")) {
            val targetRole = message.substringAfter("CMD_SET_ROLE:")
            runOnUiThread {
                val newRoleIsSlave = targetRole == "SLAVE"
                if (roleSwitch.isChecked != newRoleIsSlave) {
                    isUpdatingFromRemote = true
                    roleSwitch.isChecked = newRoleIsSlave
                    updateRoleUI(newRoleIsSlave)
                    prefs.edit().putBoolean(PREF_ROLE, newRoleIsSlave).apply()
                    isUpdatingFromRemote = false
                }
            }
        } else if (message == "CMD_TAKE_PHOTO") {
            takeHighResPhoto()
        } else if (message == "CMD_AF_ON") {
            enableAutofocus(true)
        } else if (message == "CMD_AF_OFF") {
            enableAutofocus(false)
        } else if (message.startsWith("CMD_FOCUS_SET:")) {
            val value = message.substringAfter("CMD_FOCUS_SET:").toFloatOrNull() ?: 0f
            setManualFocus(value / 100f)
        } else if (message == "CMD_GET_CAMERA_LIST") {
            sendCameraListToRemote()
        } else if (message.startsWith("CMD_CAMERA_LIST:")) {
            val listString = message.substringAfter("CMD_CAMERA_LIST:")
            runOnUiThread { showRemoteCameraSelectionMenu(listString) }
        } else if (message.startsWith("CMD_SET_CAMERA_ID:")) {
            val cameraId = message.substringAfter("CMD_SET_CAMERA_ID:")
            runOnUiThread { switchCameraById(cameraId) }
        } else if (message.startsWith("CMD_CAMERA_CHANGED:")) {
            val info = message.substringAfter("CMD_CAMERA_CHANGED:")
            runOnUiThread { 
                Toast.makeText(this, "Camera cambiata (ID: $info)", Toast.LENGTH_SHORT).show()
            }
        } else if (message == "CMD_START_DRAWING") {
            if (roleSwitch.isChecked) {
                startDrawingModeOnSlave()
            } else {
                if (lastCapturedPhoto != null) {
                    startDrawingModeOnMaster()
                } else {
                    sendControlMessage("CMD_NO_PHOTO")
                    runOnUiThread {
                        Toast.makeText(this, "Nessuna foto scattata disponibile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (message == "CMD_STOP_DRAWING") {
            if (roleSwitch.isChecked) {
                stopDrawingModeOnSlave()
            } else {
                stopDrawingModeOnMaster()
            }
        } else if (message.startsWith("CMD_SET_DRAW_COLOR:")) {
            val colorStr = message.substringAfter("CMD_SET_DRAW_COLOR:")
            val colorInt = colorStr.toIntOrNull() ?: Color.RED
            runOnUiThread {
                drawingView.setStrokeColor(colorInt)
                drawingColorIndicator.setBackgroundColor(colorInt)
            }
        } else if (message == "CMD_NO_PHOTO") {
            runOnUiThread {
                Toast.makeText(this, "Nessuna foto disponibile sul Master", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(ExperimentalLensFacing::class, ExperimentalCamera2Interop::class)
    private fun sendCameraListToRemote() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val cameraInfos = provider.availableCameraInfos
                val manager = getSystemService(CAMERA_SERVICE) as CameraManager
                val allIds = manager.cameraIdList
                
                val listString = cameraInfos.mapIndexed { index, info ->
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
                
                sendControlMessage("CMD_CAMERA_LIST:${if (fullList.isEmpty()) "Nessuna camera" else fullList}")
                Log.d("RemoteCamera", "Full list sent: $fullList")
            } catch (e: Exception) {
                sendControlMessage("CMD_CAMERA_LIST:Errore")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showRemoteCameraSelectionMenu(listString: String) {
        if (listString == "Nessuna camera" || listString == "Errore") {
            Toast.makeText(this, "Nessuna camera trovata", Toast.LENGTH_SHORT).show()
            return
        }
        val itemsData = listString.split(";")
        val displayItems = itemsData.map { it.substringBefore("|") }.toTypedArray()
        val ids = itemsData.map { it.substringAfter("|") }

        AlertDialog.Builder(this)
            .setTitle("Scegli Telecamera Remota")
            .setItems(displayItems) { _, which ->
                sendControlMessage("CMD_SET_CAMERA_ID:${ids[which]}")
            }
            .setNeutralButton("Aggiorna") { _, _ -> sendControlMessage("CMD_GET_CAMERA_LIST") }
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
                startCameraIfSlave()
                sendControlMessage("CMD_CAMERA_CHANGED:$cameraId")
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
                    .setTitle("Scegli Telecamera")
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

    private fun sendControlMessage(message: String) { sendData(1, message.toByteArray()) }

    private fun sendData(type: Int, data: ByteArray) {
        val out = dataOutputStream ?: return
        thread {
            try {
                synchronized(out) {
                    out.writeByte(type)
                    out.writeInt(data.size)
                    out.write(data)
                    out.flush()
                }
            } catch (e: Exception) {}
        }
    }

    private fun cleanupAndExit() {
        isReconnecting = false
        thread {
            try {
                cameraExecutor.shutdownNow()
                runOnUiThread { cameraProvider?.unbindAll() }
                unregisterService()
                stopDiscovery()
                activeSocket?.close()
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
            serviceName = this@MainActivity.serviceName
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
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            connectToServer(s.host, s.port)
                        }
                    })
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
        thread {
            try {
                handleConnection(Socket(address, port), false)
            } catch (e: Exception) { Log.e("RemoteCamera", "Connection error") }
        }
    }

    override fun onDestroy() {
        isReconnecting = false
        try {
            cameraExecutor.shutdownNow()
            cameraProvider?.unbindAll()
            unregisterService()
            stopDiscovery()
            activeSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
