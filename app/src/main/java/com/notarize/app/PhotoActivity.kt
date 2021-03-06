package com.notarize.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.notarize.app.ImagePopupView.Companion.ALPHA_TRANSPARENT
import com.notarize.app.ImagePopupView.Companion.FADING_ANIMATION_DURATION
import com.notarize.app.ext.toHexString
import com.notarize.app.ext.toSha256
import kotlinx.android.synthetic.main.activity_photo.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class PhotoActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CODE_UPLOAD = 11
        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private var imageUri: Uri? = null

    private val fileUtils: FileUtils by lazy { FileUtilsImpl() }
    private val executor: Executor by lazy { Executors.newSingleThreadExecutor() }

    private var imageCapture: ImageCapture? = null
    private var imagePopupView: ImagePopupView? = null
    private var lensFacing = CameraX.LensFacing.BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        setClickListeners()
        requestPermissions()

        bt_upload.setOnClickListener {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            val byteArray = bitmap.toByteArray()
            bitmap.recycle()

            startActivityForResult(
                Intent(
                    this@PhotoActivity,
                    MainActivity::class.java
                )
                    .apply {
                        val hash = byteArray
                            .toSha256()
                            .toHexString()
                        Log.d("PhotoActivity", "Second hash $hash")
                        putExtra(EXTRA_FILE_HASH, hash)
                    },
                REQUEST_CODE_UPLOAD
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_UPLOAD) {
            if (resultCode == Activity.RESULT_OK) {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            putExtra(Intent.EXTRA_STREAM, imageUri)
                            type = "image/*"
                        },
                        getString(R.string.send_file)
                    )
                )
            } else {
                Toast.makeText(
                    this,
                    R.string.something_went_wrong,
                    Toast.LENGTH_LONG
                ).show()
                takenImage.setImageBitmap(null)
                bt_upload.visibility = View.GONE
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun setClickListeners() {
        toggleCameraLens.setOnClickListener { toggleFrontBackCamera() }
        previewView.setOnClickListener { takePicture() }
        takenImage.setOnLongClickListener {
            showImagePopup()
            return@setOnLongClickListener true
        }

//        extensionFeatures.onItemSelectedListener =
//            object : AdapterView.OnItemSelectedListener {
//                override fun onItemSelected(
//                    parentView: AdapterView<*>,
//                    selectedItemView: View,
//                    position: Int,
//                    id: Long
//                ) {
//                    if (ExtensionFeature.fromPosition(position) != ExtensionFeature.NONE) {
//                        previewView.post { startCamera() }
//                    }
//                }
//
//                override fun onNothingSelected(parentView: AdapterView<*>) {}
//            }
    }

    private fun requestPermissions() {
        if (allPermissionsGranted()) {
            previewView.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun takePicture() {
        disableActions()
        savePictureToMemory()
    }

    private fun savePictureToFile() {
        fileUtils.createDirectoryIfNotExist()
        val file = fileUtils.createFile()

        imageCapture?.takePicture(file, getMetadata(), executor,
            object : ImageCapture.OnImageSavedListener {
                override fun onImageSaved(file: File) {
                    runOnUiThread {
                        imageUri = FileProvider.getUriForFile(
                            this@PhotoActivity,
                            packageName,
                            file
                        )
                        takenImage.setImageURI(
                            imageUri
                        )
                        bt_upload.visibility = View.VISIBLE
                        enableActions()
                    }
                }

                override fun onError(
                    imageCaptureError: ImageCapture.ImageCaptureError,
                    message: String,
                    cause: Throwable?
                ) {
                    Log.e("PhotoActivity", message, cause)
                    runOnUiThread {
                        Toast.makeText(
                            this@PhotoActivity,
                            getString(R.string.image_capture_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                        bt_upload.visibility = View.GONE
                    }
                }
            })
    }

    private fun getMetadata() = ImageCapture.Metadata().apply {
        isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
    }

    private fun savePictureToMemory() {
        imageCapture?.takePicture(executor,
            object : ImageCapture.OnImageCapturedListener() {
                override fun onError(
                    error: ImageCapture.ImageCaptureError,
                    message: String, exc: Throwable?
                ) {
                    Toast.makeText(
                        this@PhotoActivity, getString(R.string.image_save_failed),
                        Toast.LENGTH_SHORT
                    ).show()

                    bt_upload.visibility = View.GONE
                }

                override fun onCaptureSuccess(
                    imageProxy: ImageProxy?,
                    rotationDegrees: Int
                ) {
                    imageProxy?.image?.let {
                        val bitmap = rotateImage(
                            imageToBitmap(it),
                            rotationDegrees.toFloat()
                        )
                        runOnUiThread {
                            Log.d(
                                "Photo",
                                "First hash ${bitmap.toByteArray().toSha256().toHexString()}"
                            )
                            takenImage.setImageBitmap(bitmap)

                            val path = MediaStore.Images.Media.insertImage(
                                contentResolver,
                                bitmap, "doc", null
                            )

                            imageUri = Uri.parse(path)

                            bt_upload.visibility = View.VISIBLE
                            enableActions()
                        }
                    }
                    super.onCaptureSuccess(imageProxy, rotationDegrees)
                }
            })
    }

    private fun createImagePopup(
        imageDrawable: Drawable,
        backgroundClickAction: () -> Unit
    ) =
        ImagePopupView.builder(this)
            .imageDrawable(imageDrawable)
            .onBackgroundClickAction(backgroundClickAction)
            .build()

    private fun removeImagePopup() {
        imagePopupView?.let {
            it.animate()
                .alpha(ALPHA_TRANSPARENT)
                .setDuration(FADING_ANIMATION_DURATION)
                .withEndAction {
                    rootView.removeView(it)
                }
                .start()
        }
    }

    private fun showImagePopup() {
        if (takenImage.drawable == null) {
            return
        }
        createImagePopup(takenImage.drawable) { removeImagePopup() }
            .let {
                imagePopupView = it
                addImagePopupViewToRoot(it)
            }
    }

    private fun addImagePopupViewToRoot(imagePopupView: ImagePopupView) {
        rootView.addView(
            imagePopupView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun rotateImage(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
    }

    private fun toggleFrontBackCamera() {
        lensFacing = if (lensFacing == CameraX.LensFacing.BACK) {
            CameraX.LensFacing.FRONT
        } else {
            CameraX.LensFacing.BACK
        }
        previewView.post { startCamera() }
    }

    private fun startCamera() {
        CameraX.unbindAll()

        val preview = createPreviewUseCase()

        preview.setOnPreviewOutputUpdateListener {

            val parent = previewView.parent as ViewGroup
            parent.removeView(previewView)
            parent.addView(previewView, 0)

            previewView.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        imageCapture = createCaptureUseCase()
        CameraX.bindToLifecycle(this, preview, imageCapture)
    }

    private fun createPreviewUseCase(): Preview {
        val previewConfig = PreviewConfig.Builder().apply {
            setLensFacing(lensFacing)
            setTargetRotation(previewView.display.rotation)

        }.build()

        return Preview(previewConfig)
    }

    private fun createCaptureUseCase(): ImageCapture {
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                setLensFacing(lensFacing)
                setTargetRotation(previewView.display.rotation)
                setCaptureMode(ImageCapture.CaptureMode.MAX_QUALITY)
            }

//        applyExtensions(imageCaptureConfig)
        return ImageCapture(imageCaptureConfig.build())
    }

//    private fun applyExtensions(builder: ImageCaptureConfig.Builder) {
//        when (ExtensionFeature.fromPosition(extensionFeatures.selectedItemPosition)) {
//            ExtensionFeature.BOKEH ->
//                enableExtensionFeature(BokehImageCaptureExtender.create(builder))
//            ExtensionFeature.HDR ->
//                enableExtensionFeature(HdrImageCaptureExtender.create(builder))
//            ExtensionFeature.NIGHT_MODE ->
//                enableExtensionFeature(NightImageCaptureExtender.create(builder))
//            else -> {
//            }
//        }
//    }

//    private fun enableExtensionFeature(imageCaptureExtender: ImageCaptureExtender) {
//        if (imageCaptureExtender.isExtensionAvailable) {
//            imageCaptureExtender.enableExtension()
//        } else {
//            Toast.makeText(
//                this, getString(R.string.extension_unavailable),
//                Toast.LENGTH_SHORT
//            ).show()
//            extensionFeatures.setSelection(0)
//        }
//    }

    private fun updateTransform() {
        val matrix = Matrix()

        val centerX = previewView.width / 2f
        val centerY = previewView.height / 2f

        val rotationDegrees = when (previewView.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        previewView.setTransform(matrix)
    }

    private fun disableActions() {
        previewView.isClickable = false
        takenImage.isClickable = false
        toggleCameraLens.isClickable = false
    }

    private fun enableActions() {
        previewView.isClickable = true
        takenImage.isClickable = true
        toggleCameraLens.isClickable = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                previewView.post { startCamera() }
            } else {
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}
