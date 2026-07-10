package com.example.usbimageflasher

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.usbimageflasher.databinding.ActivityMainBinding
import com.example.usbimageflasher.usb.MassStorageDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var recentStore: RecentImagesStore

    private var selectedImageUri: Uri? = null
    private var selectedImageSize: Long = -1
    private var pendingDevice: UsbDevice? = null
    private var massStorageDevice: MassStorageDevice? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    private val ACTION_USB_PERMISSION = "com.example.usbimageflasher.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val device: UsbDevice? = intent.getParcelableExtraCompat()
                        if (granted && device != null) {
                            openMassStorageDevice(device)
                        } else {
                            setDriveInfo("Permission denied for USB device")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    massStorageDevice?.close()
                    massStorageDevice = null
                    binding.flashButton.isEnabled = false
                    binding.formatButton.isEnabled = false
                    binding.ejectButton.isEnabled = false
                    setDriveInfo("Drive disconnected")
                }
            }
        }
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(): T? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions; the recent-list
                // entry may fail to reopen after an app/device restart in that case.
            }
            selectedImageUri = uri
            selectedImageSize = querySize(uri)
            val name = queryName(uri)
            binding.imageNameText.text = "Selected: $name (${selectedImageSize / (1024 * 1024)} MB)"
            recentStore.addOrUpdate(uri, name, selectedImageSize)
            updateFlashButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        recentStore = RecentImagesStore(this)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        binding.selectImageButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("*/*"))
        }

        binding.recentImagesButton.setOnClickListener { showRecentImagesDialog() }

        binding.detectDriveButton.setOnClickListener { detectDrive() }

        binding.flashButton.setOnClickListener { confirmAndFlash() }

        binding.formatButton.setOnClickListener { confirmAndFormat() }

        binding.ejectButton.setOnClickListener { confirmAndEject() }

        (intent.getParcelableExtraCompat<UsbDevice>())?.let { requestPermissionFor(it) }
    }

    private fun showRecentImagesDialog() {
        val items = recentStore.getAll()
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Recent Images")
                .setMessage("No recent images yet. Select an image first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val listView = ListView(this)
        lateinit var adapter: RecentImagesAdapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("Recent / Favorite Images")
            .setView(listView)
            .setNegativeButton("Close", null)
            .create()

        adapter = RecentImagesAdapter(
            this,
            items,
            onSelect = { item ->
                selectImageFromRecent(item)
                dialog.dismiss()
            },
            onToggleFavorite = { item ->
                recentStore.toggleFavorite(item.uri)
                adapter.updateItems(recentStore.getAll())
            },
            onRemove = { item ->
                recentStore.remove(item.uri)
                adapter.updateItems(recentStore.getAll())
            }
        )
        listView.adapter = adapter
        dialog.show()
    }

    private fun selectImageFromRecent(item: RecentImage) {
        val uri = Uri.parse(item.uri)
        selectedImageUri = uri
        selectedImageSize = item.sizeBytes
        binding.imageNameText.text = "Selected: ${item.name} (${item.sizeBytes / (1024 * 1024)} MB)"
        recentStore.addOrUpdate(uri, item.name, item.sizeBytes)
        updateFlashButtonState()
    }

    private fun detectDrive() {
        val candidates = usbManager.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { i ->
                val intf = dev.getInterface(i)
                intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }
        when {
            candidates.isEmpty() -> setDriveInfo("No USB mass storage device found. Plug it in via OTG.")
            candidates.size == 1 -> requestPermissionFor(candidates.first())
            else -> setDriveInfo("Multiple USB devices found; disconnect all but one target drive.")
        }
    }

    private fun requestPermissionFor(device: UsbDevice) {
        pendingDevice = device
        if (usbManager.hasPermission(device)) {
            openMassStorageDevice(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun openMassStorageDevice(device: UsbDevice) {
        scope.launch {
            setDriveInfo("Opening device...")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = usbManager.openDevice(device)
                        ?: throw IllegalStateException("Could not open USB connection")
                    MassStorageDevice.open(connection, device)
                        ?: throw IllegalStateException("Device is not a supported mass storage drive")
                }
            }
            result.onSuccess { msd ->
                massStorageDevice = msd
                val sizeMb = (msd.blockCount * msd.blockSize) / (1024 * 1024)
                setDriveInfo("Drive ready: ${sizeMb} MB, block size ${msd.blockSize}B")
                binding.formatButton.isEnabled = true
                binding.ejectButton.isEnabled = true
                updateFlashButtonState()
            }.onFailure { e ->
                setDriveInfo("Failed to open drive: ${e.message}")
            }
        }
    }

    private fun updateFlashButtonState() {
        binding.flashButton.isEnabled = selectedImageUri != null && massStorageDevice != null
    }

    private fun confirmAndFlash() {
        val msd = massStorageDevice ?: return
        val uri = selectedImageUri ?: return
        val sizeMb = (msd.blockCount * msd.blockSize) / (1024 * 1024)
        AlertDialog.Builder(this)
            .setTitle("Erase and flash drive?")
            .setMessage(
                "This will PERMANENTLY ERASE ALL DATA on the connected $sizeMb MB drive " +
                    "and write the selected image to it. This cannot be undone."
            )
            .setPositiveButton("Flash") { _, _ -> startFlashing(uri, msd) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmAndFormat() {
        val msd = massStorageDevice ?: return
        val sizeMb = (msd.blockCount * msd.blockSize) / (1024 * 1024)
        AlertDialog.Builder(this)
            .setTitle("Format drive as FAT32?")
            .setMessage(
                "This will PERMANENTLY ERASE ALL DATA on the connected $sizeMb MB drive " +
                    "and create a new, empty FAT32 filesystem. This cannot be undone."
            )
            .setPositiveButton("Format") { _, _ -> startFormatting(msd) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmAndEject() {
        val msd = massStorageDevice ?: return
        AlertDialog.Builder(this)
            .setTitle("Eject drive?")
            .setMessage("Flags the drive as safe to remove and releases the USB connection.")
            .setPositiveButton("Eject") { _, _ -> startEject(msd) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startFlashing(uri: Uri, msd: MassStorageDevice) {
        setButtonsEnabled(false)
        binding.progressBar.progress = 0
        val verifyAfter = binding.verifyCheckBox.isChecked

        val flasher = ImageFlasher(contentResolver, msd)
        scope.launch {
            withContext(Dispatchers.IO) {
                flasher.flash(uri, selectedImageSize, verifyAfter, object : ImageFlasher.ProgressListener {
                    override fun onScanProgress(bytesScanned: Long) {
                        runOnUiThread {
                            binding.progressText.text = "Scanning image: ${bytesScanned / (1024 * 1024)} MB"
                        }
                    }

                    override fun onProgress(bytesWritten: Long, totalBytes: Long) {
                        runOnUiThread {
                            if (totalBytes > 0) {
                                val pct = ((bytesWritten * 100) / totalBytes).toInt()
                                binding.progressBar.progress = pct
                                binding.progressText.text =
                                    "Writing: ${bytesWritten / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB ($pct%)"
                            } else {
                                binding.progressText.text = "Writing: ${bytesWritten / (1024 * 1024)} MB"
                            }
                        }
                    }

                    override fun onVerifyProgress(bytesVerified: Long, totalBytes: Long) {
                        runOnUiThread {
                            val pct = if (totalBytes > 0) ((bytesVerified * 100) / totalBytes).toInt() else 0
                            binding.progressBar.progress = pct
                            binding.progressText.text =
                                "Verifying: ${bytesVerified / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB ($pct%)"
                        }
                    }

                    override fun onVerifyResult(success: Boolean) {
                        runOnUiThread {
                            binding.progressText.text = if (success) {
                                "Flash verified successfully! Safe to eject."
                            } else {
                                "VERIFY FAILED: data on drive does not match the image. Try flashing again."
                            }
                            resetButtons()
                        }
                    }

                    override fun onComplete() {
                        runOnUiThread {
                            if (!verifyAfter) {
                                binding.progressText.text = "Done! Safe to eject."
                                resetButtons()
                            } else {
                                binding.progressText.text = "Write complete. Verifying..."
                            }
                        }
                    }

                    override fun onError(e: Exception) {
                        runOnUiThread {
                            binding.progressText.text = "Error: ${e.message}"
                            resetButtons()
                        }
                    }
                })
            }
        }
    }

    private fun startFormatting(msd: MassStorageDevice) {
        setButtonsEnabled(false)
        binding.progressBar.progress = 0

        val formatter = Fat32Formatter(msd)
        scope.launch {
            withContext(Dispatchers.IO) {
                formatter.format(listener = object : Fat32Formatter.ProgressListener {
                    override fun onProgress(message: String) {
                        runOnUiThread { binding.progressText.text = message }
                    }

                    override fun onComplete() {
                        runOnUiThread {
                            binding.progressText.text = "Format complete! Safe to unplug the drive."
                            resetButtons()
                        }
                    }

                    override fun onError(e: Exception) {
                        runOnUiThread {
                            binding.progressText.text = "Format error: ${e.message}"
                            resetButtons()
                        }
                    }
                })
            }
        }
    }

    private fun startEject(msd: MassStorageDevice) {
        setButtonsEnabled(false)
        scope.launch {
            withContext(Dispatchers.IO) {
                msd.eject()
                msd.close()
            }
            massStorageDevice = null
            binding.progressText.text = "Drive safely ejected. You may unplug it now."
            setDriveInfo("No drive detected")
            resetButtons()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.selectImageButton.isEnabled = enabled
        binding.recentImagesButton.isEnabled = enabled
        binding.detectDriveButton.isEnabled = enabled
        binding.formatButton.isEnabled = enabled && massStorageDevice != null
        binding.ejectButton.isEnabled = enabled && massStorageDevice != null
        if (!enabled) binding.flashButton.isEnabled = false
    }

    private fun resetButtons() {
        binding.selectImageButton.isEnabled = true
        binding.recentImagesButton.isEnabled = true
        binding.detectDriveButton.isEnabled = true
        binding.formatButton.isEnabled = massStorageDevice != null
        binding.ejectButton.isEnabled = massStorageDevice != null
        updateFlashButtonState()
    }

    private fun setDriveInfo(text: String) {
        binding.driveInfoText.text = text
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment ?: "image"
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getLong(idx)
        }
        return -1
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        massStorageDevice?.close()
    }
}
