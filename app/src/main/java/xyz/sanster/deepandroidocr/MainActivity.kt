package xyz.sanster.deepandroidocr

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import xyz.sanster.deepandroidocr.databinding.ActivityMainBinding
import android.os.Message
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import xyz.sanster.deepandroidocr.camera.CameraManager
import xyz.sanster.deepandroidocr.model.TextResult
import xyz.sanster.deepandroidocr.ocr.CRNNRecoginzer
import xyz.sanster.deepandroidocr.ocr.CTPNDetector
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val PERMISSION_REQUEST_CODE: Int = 1

        init {
            if (OpenCVLoader.initDebug()) {
                Log.d(TAG, "OpenCV successfully loaded")
            } else {
                Log.d(TAG, "OpenCV not loaded")
            }
        }
    }

    private var sharedPref: SharedPreferences? = null

    private var hasSurface: Boolean = false
    lateinit var cameraManager: CameraManager
    var handler: CaptureActivityHandler? = null
    private var isProgressing: Boolean = false
    private var roiPaint: Paint = Paint()
    private var flashOn: Boolean = false
    var ip: String? = null

    var CHN: Int = 0
    var ENG: Int = 1
    var categoryModel: Int = CHN

    lateinit var ctpn: CTPNDetector
    lateinit var chnCrnn: CRNNRecoginzer
    lateinit var engCrnn: CRNNRecoginzer

    // 1. Add the binding property
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2. Inflate the binding and set the content view
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "MainActivity onCreate()")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideStatusBar()

        checkPermissionsGranted()

        // 3. Replace all synthetic view references with binding.*
        binding.continueBtn.setOnClickListener { onContinueBtnClick() }
        binding.takeImgBtn.setOnClickListener { onTakeImgBtnClick() }
        binding.flashBtn.setOnClickListener { toggleFlash() }
        binding.category.setOnClickListener { toggleCategory() }

        roiPaint.style = Paint.Style.STROKE
        roiPaint.strokeWidth = 2.0f
        roiPaint.color = Color.GREEN

        sharedPref = getPreferences(Context.MODE_PRIVATE)

        ctpn = CTPNDetector(this)
        chnCrnn = CRNNRecoginzer(this, "simple_mobile_crnn.pb", "chn.txt")
        engCrnn = CRNNRecoginzer(this, "raw_eng_crnn.pb", "eng.txt")
    }

    private fun init() {
        //        initCamera()
    }

    private fun onContinueBtnClick() {
        binding.resultView.visibility = View.GONE
        binding.failedView.visibility = View.GONE
        binding.flashBtn.visibility = View.VISIBLE
        binding.viewfinderView.visibility = View.VISIBLE
        binding.menuContainer.visibility = View.VISIBLE
    }

    private fun onTakeImgBtnClick() {
        if (!isProgressing) {
            isProgressing = true
            binding.progressingView.visibility = View.VISIBLE
            binding.viewfinderView.visibility = View.GONE
            startDetect()
        }
    }

    private fun toggleFlash() {
        if (flashOn) {
            binding.flashBtn.setBackgroundResource(R.mipmap.icon_flash_off)
        } else {
            binding.flashBtn.setBackgroundResource(R.mipmap.icon_flash_on)
        }
        flashOn = !flashOn
        cameraManager.setTorch(flashOn)
    }

    private fun toggleCategory() {
        if (binding.categoryContainer.visibility == View.VISIBLE) {
            binding.categoryContainer.visibility = View.GONE
        } else {
            binding.categoryContainer.visibility = View.VISIBLE
        }
    }

    fun categorySelected(view: View) {
        binding.item0.setBackgroundColor(Color.TRANSPARENT)
        binding.item1.setBackgroundColor(Color.TRANSPARENT)
        view.setBackgroundColor(0x55ffffff)
        categoryModel = view.tag.toString().toInt()
        when (categoryModel) {
            CHN -> binding.category.setImageResource(R.mipmap.icon_chinese)
            ENG -> binding.category.setImageResource(R.mipmap.icon_english)
        }

        binding.categoryContainer.visibility = View.GONE
    }

    private fun startDetect() {
        Log.d(TAG, "MainActivity.startDetect")
        sendMessage(R.id.start_detect)
    }

    fun handleResult(data: ByteArray?, textResults: ArrayList<TextResult>) {
        isProgressing = false
        binding.viewfinderView.visibility = View.GONE
        binding.progressingView.visibility = View.GONE
        binding.menuContainer.visibility = View.GONE
        binding.flashBtn.visibility = View.GONE
        binding.resultView.visibility = View.VISIBLE

        var detectBitmap: Bitmap? = null
        if (data != null) {
            detectBitmap = BitmapFactory.decodeByteArray(data, 0, data.size, null)
        }

        if (detectBitmap != null) {
            val tempBitmap = Bitmap.createBitmap(
                detectBitmap.width,
                detectBitmap.height,
                detectBitmap.config ?: Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(tempBitmap)
            canvas.drawBitmap(detectBitmap, 0f, 0f, Paint())

            var allResult = ""
            textResults.forEach { w ->
                allResult += "${w.words}\n"
                canvas.drawRect(w.location, roiPaint)
            }

            binding.detectImg.setImageBitmap(tempBitmap)
            binding.ocrResultText.text = allResult
        }
    }

    fun showFailedView() {
        binding.failedView.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        hideStatusBar()

        cameraManager = CameraManager(application)

        binding.viewfinderView.cameraManager = cameraManager

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val surfaceHolder = binding.previewSurface.holder
        if (hasSurface) {
            Log.d(TAG, "initCamera() in onResume")
            initCamera(surfaceHolder)
        } else {
            Log.d(TAG, "surfaceHolder.addCallback() in onResume")
            surfaceHolder.addCallback(this)
        }
    }

    private fun release() {
        if (handler != null) {
            handler!!.quitSynchronously()
            handler = null
        }
        cameraManager.stopPreview()
        cameraManager.closeDriver()
        if (!hasSurface) {
            binding.previewSurface.holder.removeCallback(this)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause()")
        release()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "MainActivity surfaceCreated()")
        if (!hasSurface) {
            hasSurface = true
            Log.d(TAG, "initCamera() in surfaceCreated()")
            initCamera(holder)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "MainActivity surfaceChanged()")
        if (!cameraManager.isOpen) {
            initCamera(holder)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_CODE -> run {
                val permissionsGranted = grantResults.indices
                    .filter { grantResults[it] == PackageManager.PERMISSION_GRANTED }
                    .map { permissions[it] }

                Log.d(TAG, "on permission request code")
                if (permissionsGranted.isEmpty()) {
                    Log.w(TAG, permissionsGranted.toString())

                    AlertDialog.Builder(this)
                        .setTitle("Permission Alert")
                        .setMessage("Please grant camera permission")
                        .setPositiveButton(android.R.string.yes) { _, _ -> System.exit(-1) }.show()
                } else {
                    init()
                }
            }
            else -> {
            }
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissionsGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            init()
            return
        }

        val permissionsNeeded = mutableListOf<String>()

        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }

        if (!isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (!isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (!isPermissionGranted(Manifest.permission.ACCESS_WIFI_STATE)) {
            permissionsNeeded.add(Manifest.permission.ACCESS_WIFI_STATE)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            init()
        }
    }

    private fun initCamera(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) {
            throw IllegalStateException("No SurfaceHolder provided")
        }

        if (cameraManager.isOpen) {
            Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?")
            return
        }

        try {
            cameraManager.openDriver(surfaceHolder)
            if (handler == null) {
                handler = CaptureActivityHandler(this, cameraManager)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.w(TAG, e)
        }
    }

    fun handleDetectDebug(bitmap: Bitmap?) {
        binding.debugImageView.setImageBitmap(bitmap)
    }

    fun showLoadingView() {
        binding.progressingView.visibility = View.VISIBLE
    }

    fun hideLoadingView() {
        binding.progressingView.visibility = View.GONE
    }

    private fun sendMessage(msg: Int) {
        if (handler != null) {
            val message = Message.obtain(handler, msg)
            handler!!.sendMessage(message)
        }
    }

    private fun hideStatusBar() {
        val decorView = window.decorView
        val uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
        decorView.systemUiVisibility = uiOptions
    }

    fun drawViewfinder() {
        binding.viewfinderView.drawViewfinder()
    }
}