package com.example.detectmovement

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.scale
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class DetectMovementActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private val firebaseStorage = FirebaseStorage.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.detect_movoment_screen)

        // ask for permission of camera upon first launch of application
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED
            ) {
                val permission = arrayOf(
                    Manifest.permission.CAMERA
                )
                requestPermissions(permission, 1122)
            } else {
                // show live camera footage
                setFragment()
            }
        } else {
            // show live camera footage
            setFragment()
        }

        findViewById<Button>(R.id.stopRecordingButton).setOnClickListener { finish() }

    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // show live camera footage
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // show live camera footage
            setFragment()
        } else {
            finish()
        }
    }

    var previewHeight = 0;
    var previewWidth = 0
    var sensorOrientation = 0;

    //TODO fragment which show llive footage from camera
    protected fun setFragment() {
        val manager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        try {
            cameraId = manager.cameraIdList[0]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        val fragment: Fragment
        val camera2Fragment = CameraConnectionFragment.newInstance(
            object :
                CameraConnectionFragment.ConnectionCallback {
                override fun onPreviewSizeChosen(size: Size?, rotation: Int) {
                    previewHeight = size!!.height
                    previewWidth = size.width
                    sensorOrientation = rotation - getScreenOrientation()
                }
            },
            this,
            R.layout.camera_fragment,
            Size(640, 480)
        )
        camera2Fragment.setCamera(cameraId)
        fragment = camera2Fragment
        fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    protected fun getScreenOrientation(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    // getting frames of live camera footage and passing them to model
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private var rgbFrameBitmap: Bitmap? = null
    private var prev_rgbFrameBitmap: Bitmap? = null
    override fun onImageAvailable(reader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0]!!,
                    yuvBytes[1]!!,
                    yuvBytes[2]!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes!!
                )
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            processImage()
        } catch (e: Exception) {
            image?.close()
            e.printStackTrace()
            return
        }
    }


    private fun processImage() {
        imageConverter!!.run()
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        rgbFrameBitmap?.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        try {
            detectMotion(prev_rgbFrameBitmap, rgbFrameBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        prev_rgbFrameBitmap = rgbFrameBitmap
        postInferenceCallback!!.run()
    }

    private fun detectMotion(bitmap1: Bitmap?, bitmap2: Bitmap?) {
        if (bitmap1 != null && bitmap2 != null) {
            val difference =
                getDifferencePercent(bitmap1.apply { scale(16, 12) }, bitmap2.apply { scale(16, 12) })
            if (difference > 10) { // customize accuracy
                println("Movement Detected!")
                EventBus.getDefault().post(Event(true))
                uploadPicture(rotateBitmap(bitmap2))
            } else {
                println("Everything is still!")
                EventBus.getDefault().post(Event(false))
            }
        } else {
            println("Image not ready yet!")
            EventBus.getDefault().post(Event(false))
        }
    }

    private fun getDifferencePercent(img1: Bitmap, img2: Bitmap): Double {
        if (img1.width != img2.width || img1.height != img2.height) {
            val f = "(%d,%d) vs. (%d,%d)".format(img1.width, img1.height, img2.width, img2.height)
            throw IllegalArgumentException("Images must have the same dimensions: $f")
        }
        var diff = 0L
        for (y in 0 until img1.height) {
            for (x in 0 until img1.width) {
                diff += pixelDiff(img1.getPixel(x, y), img2.getPixel(x, y))
            }
        }
        val maxDiff = 3L * 255 * img1.width * img1.height
        return 100.0 * diff / maxDiff
    }

    private fun pixelDiff(rgb1: Int, rgb2: Int): Int {
        val r1 = (rgb1 shr 16) and 0xff
        val g1 = (rgb1 shr 8) and 0xff
        val b1 = rgb1 and 0xff
        val r2 = (rgb2 shr 16) and 0xff
        val g2 = (rgb2 shr 8) and 0xff
        val b2 = rgb2 and 0xff
        return kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
    }

    protected fun fillBytes(
        planes: Array<Image.Plane>,
        yuvBytes: Array<ByteArray?>
    ) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }


    // rotate image if image captured on some devices
    //Most phone cameras are landscape, meaning if you take the photo in portrait, the resulting photos will be rotated 90 degrees.
    fun rotateBitmap(input: Bitmap): Bitmap {
        Log.d("trySensor", sensorOrientation.toString() + "     " + getScreenOrientation())
        val rotationMatrix = Matrix()
        rotationMatrix.setRotate(sensorOrientation.toFloat())
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, rotationMatrix, true)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun showWarning(event: Event) {
        if (event.warning)
            findViewById<ImageView>(R.id.warningIcon).visibility = View.VISIBLE
        else
            findViewById<ImageView>(R.id.warningIcon).visibility = View.INVISIBLE
    }

    private fun uploadPicture(bitmap: Bitmap) {

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val data = outputStream.toByteArray()
        val path = "photos/" + UUID.randomUUID() + ".png"
        val photosRef = firebaseStorage.getReference(path)
        val calendar = Calendar.getInstance()
        val simpleDateFormat = SimpleDateFormat("MM/dd/yyyy")
        val date = simpleDateFormat.format(calendar.time)
        val metadata = StorageMetadata.Builder().setCustomMetadata(
            "date", date
        ).build()
        val uploadTask = photosRef.putBytes(data, metadata)
        uploadTask.addOnCompleteListener(
            this
        ) { println("Upload succeded") }
        val getDownloadUriTask = uploadTask.continueWithTask { task ->
            if (!task.isSuccessful) {
                throw task.exception!!
            }
            photosRef.downloadUrl
        }
        getDownloadUriTask.addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val downloadUri = task.result
                println(downloadUri.toString())
                Toast.makeText(applicationContext, "Upload Succesfull", Toast.LENGTH_SHORT).show()
            }

        }
    }

}
