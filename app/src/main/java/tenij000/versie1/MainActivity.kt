package tenij000.versie1

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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
    private lateinit var scannerOverlay: ScannerOverlayView
    private lateinit var btnLoadFile: ImageButton
    private lateinit var btnEditFile: ImageButton
    private lateinit var btnAddProduct: ImageButton
    private var toneGenerator: ToneGenerator? = null
    
    // De Excel/CSV Database
    private val productDatabase = mutableMapOf<String, Triple<String, String, String>>()
    private var currentFileUri: android.net.Uri? = null
    private var lastScannedGtin: String? = null

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
        scannerOverlay = findViewById(R.id.scannerOverlay)
        btnLoadFile = findViewById(R.id.btnLoadFile)
        btnEditFile = findViewById(R.id.btnEditFile)
        btnAddProduct = findViewById(R.id.btnAddProduct)

        btnLoadFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "text/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePickerLauncher.launch(intent)
        }

        btnEditFile.setOnClickListener {
            showEditFileDialog()
        }

        btnAddProduct.setOnClickListener {
            lastScannedGtin?.let { gtin ->
                showAddProductDialog(gtin)
            }
        }

        // De oude bewerk-knop referentie is niet meer nodig
        // findViewById<android.view.View>(R.id.btnEditProduct).visibility = android.view.View.GONE

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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
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
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("CheckApp", "Camera error", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleBarcodeResult(barcode: Barcode) {
        val raw = barcode.rawValue ?: return
        val boundingBox = barcode.boundingBox ?: return

        // GS1 Parsing
        var serial = "-"
        "21([A-Z0-9]+)".toRegex().find(raw)?.let { serial = it.groupValues[1] }

        runOnUiThread {
            val rect = android.graphics.RectF(boundingBox)
            
            // Pas de UI aan
            parseGS1(raw)
            
            // Toon groen vierkantje in de overlay
            val scaleX = scannerOverlay.width / 480f 
            val scaleY = scannerOverlay.height / 640f
            val mappedRect = android.graphics.RectF(
                rect.left * scaleX, 
                rect.top * scaleY, 
                rect.right * scaleX, 
                rect.bottom * scaleY
            )
            
            scannerOverlay.updateResult(mappedRect, serial != "-")
        }
    }

    private fun loadDatabaseFromUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                productDatabase.clear()
                processReader(reader)
            }
            Toast.makeText(this, "Bestand geladen: ${productDatabase.size} producten", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("Excel", "Fout bij laden", e)
            Toast.makeText(this, "Fout bij laden van bestand", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processReader(reader: java.io.BufferedReader) {
        reader.useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val separator = if (line.contains(";")) ";" else ","
                val parts = line.split(separator)
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
            val downloadFile = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "producten.txt"
            )
            
            if (downloadFile.exists()) {
                Log.i("Excel", "Laden van Downloads: ${downloadFile.absolutePath}")
                downloadFile.bufferedReader().use { processReader(it) }
            } else {
                Log.i("Excel", "Downloads bestand niet gevonden, laden van assets")
                // Probeer eerst .txt in assets, dan .csv als fallback
                try {
                    assets.open("producten.txt").bufferedReader().use { processReader(it) }
                } catch (e: Exception) {
                    assets.open("producten.csv").bufferedReader().use { processReader(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("Excel", "Fout bij laden lijst", e)
        }
    }

    private fun parseGS1(raw: String) {
        val cleanRaw = raw.replace("\u001d", " ").replace("\r", " ").replace("\n", " ")
        
        var gtin = "-"
        var serial = "-"
        var price = "-"
        var weight = "-"
        var productName = "ONBEKEND PRODUCT"
        var isKnown = false

        // 1. Haal de GTIN (01) op
        "01(\\d{14})".toRegex().find(cleanRaw)?.let { 
            gtin = it.groupValues[1] 
            lastScannedGtin = gtin
            productDatabase[gtin]?.let { (name, p, w) ->
                productName = name
                price = p
                weight = w
                isKnown = true
            }
        }

        // 2. Haal Serial (21) op
        "21([^ ]+)".toRegex().find(cleanRaw)?.let { 
            serial = it.groupValues[1].split(" ")[0] 
        }

        // 3. Extra info in code (overschrijft database indien aanwezig)
        "3922([^ ]+)".toRegex().find(cleanRaw)?.let { 
            val p = it.groupValues[1].split(" ")[0].filter { it.isDigit() }
            if (p.isNotEmpty()) {
                price = "EUR " + if (p.length > 2) p.substring(0, p.length - 2) + "," + p.substring(p.length - 2) else "0,$p"
            }
        }
        
        "30([^ ]+)".toRegex().find(cleanRaw)?.let { 
            val w = it.groupValues[1].split(" ")[0].filter { it.isDigit() }
            // Beperk gewicht tot max 6 cijfers om foutieve parsing van andere velden te voorkomen
            if (w.isNotEmpty() && w.length <= 6) {
                weight = "$w gram"
            }
        }

        runOnUiThread {
            if (serial != "-") {
                if (tvSerial.text != serial) {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP)
                }
                tvGtin.text = getString(R.string.gtin_format, gtin)
                tvSerial.text = serial
                tvProductName.text = productName
                tvPriceInfo.text = "PRIJS: $price"
                tvWeightInfo.text = "GEWICHT: $weight"
                tvTitle.text = "Gescand: $productName"
                
                // Laat de "+" knop zien als het product onbekend is
                btnAddProduct.visibility = if (isKnown) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun showAddProductDialog(gtin: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val etName = android.widget.EditText(this).apply { hint = "Product Naam" }
        val etPrice = android.widget.EditText(this).apply { hint = "Prijs (bijv. EUR 9,00)" }
        val etWeight = android.widget.EditText(this).apply { hint = "Gewicht (bijv. 50 gram)" }

        layout.addView(etName)
        layout.addView(etPrice)
        layout.addView(etWeight)

        builder.setView(layout)
        builder.setTitle("Nieuw Product Toevoegen")
        builder.setMessage("GTIN: $gtin")
        builder.setPositiveButton("Toevoegen") { _, _ ->
            val name = etName.text.toString().ifBlank { "Nieuw Product" }
            val price = etPrice.text.toString().ifBlank { "-" }
            val weight = etWeight.text.toString().ifBlank { "-" }
            
            val newLine = "$gtin;$name;$price;$weight"
            appendToDatabase(newLine)
        }
        builder.setNegativeButton("Annuleren", null)
        builder.show()
    }

    private fun appendToDatabase(line: String) {
        try {
            // We halen eerst de huidige tekst op
            var content = ""
            val uri = currentFileUri
            if (uri != null) {
                content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                val downloadFile = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "producten.txt"
                )
                if (downloadFile.exists()) content = downloadFile.readText()
            }

            // Voeg nieuwe regel toe
            val newContent = if (content.endsWith("\n") || content.isEmpty()) content + line else content + "\n" + line
            saveContent(newContent)
            
            // Direct herladen zodat de scanner het meteen herkent
            loadProductDatabase()
            Toast.makeText(this, "Product toegevoegd aan de lijst!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("Append", "Fout bij toevoegen", e)
            Toast.makeText(this, "Fout bij toevoegen aan bestand", Toast.LENGTH_LONG).show()
        }
    }

    private fun showEditFileDialog() {
        // We proberen de inhoud te laden van de huidige URI of het Downloads bestand
        val content = try {
            val uri = currentFileUri
            if (uri != null) {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                val downloadFile = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "producten.txt"
                )
                if (downloadFile.exists()) downloadFile.readText() else ""
            }
        } catch (e: Exception) {
            ""
        }

        val et = android.widget.EditText(this).apply {
            setText(content)
            gravity = android.view.Gravity.TOP
            setBackgroundColor(android.graphics.Color.WHITE)
            setTextColor(android.graphics.Color.BLACK)
            setPadding(40, 40, 40, 40)
            // Zorg dat het toetsenbord de weergave niet verpest
            inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(et)
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setView(scroll)
        builder.setTitle("Producten Lijst Bewerken")
        builder.setPositiveButton("Opslaan") { _, _ ->
            val newContent = et.text.toString()
            saveContent(newContent)
        }
        builder.setNegativeButton("Annuleren", null)
        
        val dialog = builder.create()
        dialog.show()
        
        // Forceer het dialoogvenster naar de maximale schermgrootte
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun saveContent(text: String) {
        try {
            val uri = currentFileUri
            if (uri != null) {
                contentResolver.openOutputStream(uri, "wt")?.use { 
                    it.write(text.toByteArray()) 
                }
            } else {
                val downloadFile = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "producten.txt"
                )
                downloadFile.writeText(text)
            }
            Toast.makeText(this, "Opgeslagen! De lijst is bijgewerkt.", Toast.LENGTH_SHORT).show()
            loadProductDatabase() // Direct herladen
        } catch (e: Exception) {
            Log.e("Save", "Fout", e)
            Toast.makeText(this, "Fout bij opslaan. Geen schrijfrechten?", Toast.LENGTH_LONG).show()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera toestemming is nodig!", Toast.LENGTH_LONG).show()
                finish()
            }
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Let op: Geen toegang tot Downloads map. Ik gebruik de interne lijst.", Toast.LENGTH_LONG).show()
            } else {
                loadProductDatabase() // Probeer opnieuw te laden als toestemming is gegeven
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        toneGenerator?.release()
    }

    private class BarcodeAnalyzer(private val onResult: (Barcode) -> Unit) : ImageAnalysis.Analyzer {
        private val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
            .build()
        private val scanner = BarcodeScanning.getClient(options)

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val centerX = imageProxy.width / 2
                        val centerY = imageProxy.height / 2
                        val tolerance = imageProxy.width * 0.20

                        val centerBarcode = barcodes.find { barcode ->
                            val box = barcode.boundingBox
                            box != null && 
                            box.centerX() > centerX - tolerance && box.centerX() < centerX + tolerance &&
                            box.centerY() > centerY - tolerance && box.centerY() < centerY + tolerance
                        }

                        if (centerBarcode != null) {
                            onResult(centerBarcode)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else imageProxy.close()
        }
    }
}
