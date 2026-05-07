package tenij000.versie1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var tvGtin: TextView
    private lateinit var tvSerial: TextView
    private lateinit var tvProduct: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvPriceInfo: TextView
    private lateinit var tvWeightInfo: TextView
    private lateinit var tvProductName: TextView
    private lateinit var tvTargetInfo: TextView
    private lateinit var scannerOverlay: ScannerOverlayView
    private lateinit var btnLoadFile: ImageButton
    private lateinit var btnEditFile: ImageButton
    private lateinit var btnAddProduct: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnListCodes: ImageButton
    private var toneGenerator: ToneGenerator? = null

    private var selectedCameraId: String? = null
    private val productDatabase = mutableMapOf<String, Triple<String, String, String>>()
    private var currentFileUri: android.net.Uri? = null
    private var lastScannedGtin: String? = null
    private var targetCode: String? = null
    private var currentVisibleBarcodes = mutableListOf<Barcode>()
    private var currentVisibleText = mutableListOf<String>()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                currentFileUri = uri
                loadDatabaseFromUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadProductDatabase()

        previewView = findViewById(R.id.previewView)
        tvGtin = findViewById(R.id.tvGtin)
        tvSerial = findViewById(R.id.tvSerial)
        tvProduct = findViewById(R.id.tvProduct)
        tvTitle = findViewById(R.id.tvTitle)
        tvPriceInfo = findViewById(R.id.tvPriceInfo)
        tvWeightInfo = findViewById(R.id.tvWeightInfo)
        tvProductName = findViewById(R.id.tvProductName)
        tvTargetInfo = findViewById(R.id.tvTargetInfo)
        scannerOverlay = findViewById(R.id.scannerOverlay)
        btnLoadFile = findViewById(R.id.btnLoadFile)
        btnEditFile = findViewById(R.id.btnEditFile)
        btnAddProduct = findViewById(R.id.btnAddProduct)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnListCodes = findViewById(R.id.btnListCodes)

        btnLoadFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "text/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePickerLauncher.launch(intent)
        }

        btnEditFile.setOnClickListener { showEditFileDialog() }
        btnAddProduct.setOnClickListener { lastScannedGtin?.let { showAddProductDialog(it) } }
        btnSwitchCamera.setOnClickListener { switchCamera() }
        btnListCodes.setOnClickListener { showBarcodeSelectionDialog() }
        tvTargetInfo.setOnClickListener {
            targetCode = null
            tvTargetInfo.visibility = View.GONE
            Toast.makeText(this, "Zoekdoel gewist", Toast.LENGTH_SHORT).show()
        }

        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE),
                10
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode ->
                        handleBarcodeResult(barcode)
                    })
                }

            try {
                cameraProvider.unbindAll()
                
                val selector = if (selectedCameraId != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { info ->
                                Camera2CameraInfo.from(info).cameraId == selectedCameraId
                            }
                        }.build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                
                cameraProvider.bindToLifecycle(this, selector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("Scanner", "Camera binding failed", exc)
                if (selectedCameraId != null) {
                    selectedCameraId = null
                    startCamera()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                Toast.makeText(this, "Geen camera's gevonden", Toast.LENGTH_SHORT).show()
                return
            }

            val options = cameraIds.map { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val type = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Achter"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Voor"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "USB/Extern"
                    else -> "Onbekend"
                }
                "$type Camera ($id)"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Kies een camera")
                .setItems(options) { _, which ->
                    selectedCameraId = cameraIds[which]
                    startCamera()
                }
                .setPositiveButton("OTG Instellingen") { _, _ ->
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                .setNegativeButton("Annuleren", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fout bij laden camera's", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBarcodeSelectionDialog() {
        val itemsList = mutableListOf<String>()
        val gtinMap = mutableMapOf<Int, String>()

        // Voeg Barcodes toe
        currentVisibleBarcodes.forEach { barcode ->
            val raw = barcode.rawValue ?: return@forEach
            var label = "BARCODE: $raw"
            "01(\\d{14})".toRegex().find(raw.replace("\u001d", " "))?.let {
                val gtin = it.groupValues[1]
                productDatabase[gtin]?.let { (name, _, _) -> label = "DOOS: $name ($gtin)" }
            }
            gtinMap[itemsList.size] = raw
            itemsList.add(label)
        }

        // Voeg Herkende Tekst (GTINs) toe
        currentVisibleText.distinct().forEach { text ->
            var label = "TEKST: $text"
            productDatabase[text]?.let { (name, _, _) -> label = "SCHERM: $name ($text)" }
            gtinMap[itemsList.size] = text
            itemsList.add(label)
        }

        if (itemsList.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Geen codes gevonden")
                .setMessage("Richt op monitor of doos en druk nogmaals, of voer de code handmatig in.")
                .setPositiveButton("Handmatig") { _, _ -> showManualInputDialog() }
                .setNegativeButton("Annuleren", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Kies een code als zoekdoel")
            .setItems(itemsList.toTypedArray()) { _, which ->
                val fullRaw = gtinMap[which] ?: return@setItems
                setSearchTarget(fullRaw)
            }
            .setPositiveButton("Handmatig") { _, _ -> showManualInputDialog() }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun showManualInputDialog() {
        val et = EditText(this).apply {
            hint = "Typ of plak de code hier..."
            setPadding(50, 40, 50, 40)
        }
        AlertDialog.Builder(this)
            .setTitle("Handmatig doel invoeren")
            .setView(et)
            .setPositiveButton("Instellen") { _, _ ->
                val input = et.text.toString().trim()
                if (input.isNotEmpty()) {
                    setSearchTarget(input)
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun setSearchTarget(fullRaw: String) {
        targetCode = fullRaw
        // Probeer een naam te vinden voor de display
        var name = "Handmatige code"
        "01(\\d{14})".toRegex().find(fullRaw.replace("\u001d", " "))?.let {
            val gtin = it.groupValues[1]
            name = productDatabase[gtin]?.first ?: "Product ($gtin)"
        }

        tvTargetInfo.text = "ZOEKEN NAAR: $name\n(${fullRaw.take(20)}...)"
        tvTargetInfo.visibility = View.VISIBLE
        Toast.makeText(this, "Zoekdoel ingesteld!", Toast.LENGTH_SHORT).show()
    }

    private fun handleBarcodeResult(barcode: Barcode) {
        val raw = barcode.rawValue ?: return
        val boundingBox = barcode.boundingBox ?: return
        
        // Controleer of dit onze target is
        var isTarget = false
        val target = targetCode
        if (target != null) {
            val cleanScanned = raw.replace("\u001d", "").replace(" ", "")
            val cleanTarget = target.replace("\u001d", "").replace(" ", "").replace("]", "")
            
            // Match op de volledige code of als een deel ervan overeenkomt (ivm OCR/Separator verschillen)
            if (cleanScanned == cleanTarget || cleanScanned.contains(cleanTarget) || cleanTarget.contains(cleanScanned)) {
                isTarget = true
            }
        }

        runOnUiThread {
            val scaleX = previewView.width.toFloat() / 480f 
            val scaleY = previewView.height.toFloat() / 640f

            val mappedRect = RectF(
                boundingBox.left * scaleX,
                boundingBox.top * scaleY,
                boundingBox.right * scaleX,
                boundingBox.bottom * scaleY
            )
            
            parseGS1(raw)
            scannerOverlay.updateResult(mappedRect, isTarget)
            
            if (isTarget) {
                // Extra feedback voor target
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 500)
            }
        }
    }

    private fun loadDatabaseFromUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                productDatabase.clear()
                processReader(r)
            }
            Toast.makeText(this, "Geladen: ${productDatabase.size} producten", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fout bij laden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processReader(reader: java.io.BufferedReader) {
        reader.useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val sep = if (line.contains(";")) ";" else ","
                val parts = line.split(sep)
                if (parts.size >= 4) {
                    val gtin = parts[0].replace("\"", "").trim()
                    val name = parts[1].replace("\"", "").trim()
                    val price = parts[2].replace("\"", "").trim()
                    val weight = parts[3].trim().replace("\"", "")
                    productDatabase[gtin] = Triple(name, price, weight)
                }
            }
        }
    }

    private fun loadProductDatabase() {
        try {
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val downloadFile = java.io.File(downloadDir, "producten.txt")
            if (downloadFile.exists()) {
                downloadFile.bufferedReader().use { processReader(it) }
            } else {
                val fallbackFile = java.io.File("/storage/emulated/0/Download/producten.txt")
                if (fallbackFile.exists()) {
                    fallbackFile.bufferedReader().use { processReader(it) }
                } else {
                    try {
                        assets.open("producten.txt").bufferedReader().use { processReader(it) }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Excel", "Fout bij laden", e)
        }
    }

    private fun parseGS1(raw: String) {
        val cleanRaw = raw.replace("\u001d", " ").replace("\r", " ").replace("\n", " ")
        var gtin = "-"
        var serial = "-"
        var price = "-"
        var weight = "-"
        var productName = "ONBEKEND"
        var isKnown = false

        "01(\\d{14})".toRegex().find(cleanRaw)?.let {
            gtin = it.groupValues[1]
            lastScannedGtin = gtin
            productDatabase[gtin]?.let { (n, p, w) ->
                productName = n
                price = p
                weight = w
                isKnown = true
            }
        }
        "21([^ ]+)".toRegex().find(cleanRaw)?.let { serial = it.groupValues[1].split(" ")[0] }
        "3922([^ ]+)".toRegex().find(cleanRaw)?.let {
            val p = it.groupValues[1].split(" ")[0].filter { char -> char.isDigit() }
            if (p.isNotEmpty()) {
                price = "EUR " + if (p.length > 2) p.substring(0, p.length - 2) + "," + p.substring(p.length - 2) else "0,$p"
            }
        }
        "30([^ ]+)".toRegex().find(cleanRaw)?.let {
            val w = it.groupValues[1].split(" ")[0].filter { char -> char.isDigit() }
            if (w.isNotEmpty() && w.length <= 6) weight = "$w gram"
        }
        runOnUiThread {
            if (serial != "-") {
                if (tvSerial.text != serial) toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP)
                tvGtin.text = getString(R.string.gtin_format, gtin)
                tvSerial.text = serial
                tvProductName.text = productName
                tvPriceInfo.text = getString(R.string.price_format, price)
                tvWeightInfo.text = getString(R.string.weight_format, weight)
                tvTitle.text = getString(R.string.scanned_format, productName)
                btnAddProduct.visibility = if (isKnown) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showAddProductDialog(gtin: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val etName = EditText(this).apply { hint = "Naam" }
        val etPrice = EditText(this).apply { hint = "Prijs" }
        val etWeight = EditText(this).apply { hint = "Gewicht" }
        layout.addView(etName)
        layout.addView(etPrice)
        layout.addView(etWeight)
        AlertDialog.Builder(this)
            .setView(layout)
            .setTitle("Nieuw Product")
            .setMessage("GTIN: $gtin")
            .setPositiveButton("Toevoegen") { _, _ ->
                appendToDatabase("$gtin;${etName.text};${etPrice.text};${etWeight.text}")
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun appendToDatabase(line: String) {
        try {
            var content = ""
            val uri = currentFileUri
            if (uri != null) {
                content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "producten.txt")
                if (f.exists()) content = f.readText()
            }
            saveContent(if (content.endsWith("\n") || content.isEmpty()) content + line else content + "\n" + line)
            loadProductDatabase()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showEditFileDialog() {
        val content = try {
            val uri = currentFileUri
            if (uri != null) {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "producten.txt")
                if (f.exists()) f.readText() else ""
            }
        } catch (e: Exception) {
            ""
        }
        val et = EditText(this).apply {
            setText(content)
            gravity = Gravity.TOP
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setPadding(40, 40, 40, 40)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(et)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setTitle("Bewerken")
            .setPositiveButton("Opslaan") { _, _ -> saveContent(et.text.toString()) }
            .setNegativeButton("Annuleren", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun saveContent(text: String) {
        try {
            val uri = currentFileUri
            if (uri != null) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            } else {
                val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "producten.txt")
                f.writeText(text)
            }
            loadProductDatabase()
            Toast.makeText(this, "Opgeslagen!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fout", Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera nodig!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        toneGenerator?.release()
    }

    private inner class BarcodeAnalyzer(private val onResult: (Barcode) -> Unit) : ImageAnalysis.Analyzer {
        private val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX).build()
        private val scanner = BarcodeScanning.getClient(options)
        private val textScanner = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                // Scan voor barcodes
                scanner.process(image).addOnSuccessListener { barcodes ->
                    currentVisibleBarcodes = barcodes.toMutableList()
                    val centerX = imageProxy.width / 2
                    val centerY = imageProxy.height / 2
                    val tolerance = imageProxy.width * 0.20
                    barcodes.find { b ->
                        val box = b.boundingBox
                        box != null && (box.centerX() > centerX - tolerance && box.centerX() < centerX + tolerance) &&
                                (box.centerY() > centerY - tolerance && box.centerY() < centerY + tolerance)
                    }?.let { onResult(it) }
                }

                // Scan voor tekst (GS1 codes op monitor)
                textScanner.process(image).addOnSuccessListener { visionText ->
                    val textList = mutableListOf<String>()
                    visionText.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val text = line.text.replace(" ", "")
                            // Zoek naar GS1-achtige patronen (01...21...)
                            if (text.startsWith("01") && text.length > 20) {
                                textList.add(text)
                            } else if (text.length == 14 && text.all { it.isDigit() }) {
                                textList.add(text)
                            }
                        }
                    }
                    currentVisibleText = textList
                }.addOnCompleteListener { imageProxy.close() }

            } else imageProxy.close()
        }
    }
}
