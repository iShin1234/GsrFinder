package sg.edu.smu.gsrfinder

import android.content.SharedPreferences
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.widget.*
import androidx.annotation.GuardedBy
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config.CloudAnchorMode
import sg.edu.smu.gsrfinder.PrivacyNoticeDialogFragment.HostResolveListener
import sg.edu.smu.gsrfinder.PrivacyNoticeDialogFragment.NoticeDialogListener
import sg.edu.smu.gsrfinder.common.helpers.CameraPermissionHelper
import sg.edu.smu.gsrfinder.common.helpers.DisplayRotationHelper
import sg.edu.smu.gsrfinder.common.helpers.FullScreenHelper
import sg.edu.smu.gsrfinder.common.helpers.SnackbarHelper
import sg.edu.smu.gsrfinder.common.helpers.TrackingStateHelper
import sg.edu.smu.gsrfinder.common.rendering.BackgroundRenderer
import sg.edu.smu.gsrfinder.common.rendering.ObjectRenderer
import sg.edu.smu.gsrfinder.common.rendering.ObjectRenderer.BlendMode
import sg.edu.smu.gsrfinder.common.rendering.PlaneRenderer
import sg.edu.smu.gsrfinder.common.rendering.PointCloudRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.common.base.Preconditions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class UserCloudAnchorActivity() : AppCompatActivity(), GLSurfaceView.Renderer, NoticeDialogListener
{
    private enum class HostResolveMode
    {
        NONE, RESOLVING
    }

    //Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val virtualObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()
    private var installRequested = false

    //Temporary matrices allocated here to reduce number of allocations for each frame.
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Locks needed for synchronization
    private val anchorLock = Any()

    // Tap handling and UI.
    private val snackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)
    private var sharedPreferences: SharedPreferences? = null

    @GuardedBy("anchorLock")
    private var anchors: ArrayList<HashMap<String, Any>> = ArrayList()

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null
    private var session: Session? = null

    // Cloud Anchor Components.
    private var firebaseManager: FirebaseManager? = null
    private val cloudManager: CloudAnchorManager = CloudAnchorManager()
    private var currentMode: HostResolveMode? = null
    private var hostListener: RoomCodeAndCloudAnchorIdListener? = null

    // Firebase
    private var location: String? = null

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_cloud_anchor)

        setUpSurfaceView()
        initFirebaseManager()
        showLocation()
    }

    private fun setUpSurfaceView()
    {
        Log.d(TAG, "setUpSurfaceView()")

        displayRotationHelper = DisplayRotationHelper(this)

        surfaceView = findViewById(R.id.surfaceview)
        // Set up renderer.
        surfaceView?.setPreserveEGLContextOnPause(true)
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView?.setRenderer(this)
        surfaceView?.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        surfaceView?.setWillNotDraw(false)
        installRequested = false
    }

    private fun initFirebaseManager()
    {
        Log.d(TAG, "initFirebaseManager()")

        firebaseManager = FirebaseManager(this)
        currentMode = HostResolveMode.NONE
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, MODE_PRIVATE)
    }

    private fun showLocation ()
    {
        Log.d(TAG, "showLocation()")

        var location = intent.getStringExtra("location").toString()
        findViewById<TextView>(R.id.location).text = location
        this.location = location

        onResolve()
    }

    override fun onDestroy()
    {
        Log.d(TAG, "onDestroy()")

        resetMode()
        if (session != null)
        {
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume()
    {
        Log.d(TAG, "onResume()")

        super.onResume()
        Log.d("UserCloudAnchorActivity", "onResume()")
        if (sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false))
        {
            Log.d("UserCloudAnchorActivity", "Allowing image sharing.")
            createSession()
        }
        surfaceView!!.onResume()
        displayRotationHelper?.onResume()
    }

    private fun createSession()
    {
        Log.d(TAG, "createSession()")
        if (session == null)
        {
            var exception: Exception? = null
            var messageId = -1

            try
            {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested))
                {
                    InstallStatus.INSTALL_REQUESTED ->
                    {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {}
                }

                if (!CameraPermissionHelper.hasCameraPermission(this))
                {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                session = Session(this)
            }
            catch (e: UnavailableArcoreNotInstalledException)
            {
                messageId = R.string.snackbar_arcore_unavailable
                exception = e
            }
            catch (e: UnavailableApkTooOldException)
            {
                messageId = R.string.snackbar_arcore_too_old
                exception = e
            }
            catch (e: UnavailableSdkTooOldException)
            {
                messageId = R.string.snackbar_arcore_sdk_too_old
                exception = e
            }
            catch (e: Exception)
            {
                messageId = R.string.snackbar_arcore_exception
                exception = e
            }
            if (exception != null)
            {
                snackbarHelper.showError(this, getString(messageId))
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            val config = Config(session)
            config.cloudAnchorMode = CloudAnchorMode.ENABLED
            session!!.configure(config)

            cloudManager.setSession(session)
        }

        try
        {
            session!!.resume()
        }
        catch (e: CameraNotAvailableException)
        {
            snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable))
            session = null
            return
        }
    }

    public override fun onPause()
    {
        super.onPause()
        Log.d("UserCloudAnchorActivity", "onPause()")

        if (session != null)
        {
            Log.d("UserCloudAnchorActivity", "Pausing session.")

            displayRotationHelper?.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<String>,results: IntArray)
    {
        Log.d(TAG, "onRequestPermissionsResult()")

        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this))
        {
            Toast.makeText(this,"Camera permission is needed to run this application",Toast.LENGTH_LONG).show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this))
            {
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean)
    {
        Log.d(TAG, "onWindowFocusChanged()")

        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig)
    {
        Log.d(TAG, "onSurfaceCreated()")

        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try
        {
            backgroundRenderer.createOnGlThread(this)
            pointCloudRenderer.createOnGlThread(this)
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread(this, "models/andy_shadow.obj", "models/andy_shadow.png")
            virtualObjectShadow.setBlendMode(BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        }
        catch (ex: IOException)
        {
            Log.e(TAG, "Failed to read an asset file", ex)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int)
    {
        Log.d(TAG, "onSurfaceChanged()")

        displayRotationHelper?.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10)
    {
        Log.d(TAG, "onDrawFrame()")

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (session == null)
        {
            return
        }

        displayRotationHelper?.updateSessionIfNeeded(session!!)

        try
        {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            val frame = session!!.update()
            val camera = frame.camera
            val cameraTrackingState = camera.trackingState

            cloudManager.onUpdate()
            backgroundRenderer.draw(frame)
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            if (cameraTrackingState == TrackingState.PAUSED)
            {
                return
            }

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            frame.acquirePointCloud().use { pointCloud ->
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewMatrix, projectionMatrix)
            }

            synchronized(anchorLock)
            {
                if(anchors.isNotEmpty())
                {
                    for(i in 0 until anchors.size)
                    {
                        val anchorMap = anchors[i]
                        val anchor = anchorMap["anchor"] as Anchor

                        if (anchor.trackingState == TrackingState.TRACKING)
                        {
                            anchor.pose.toMatrix(anchorMap["matrix"] as FloatArray, 0)
                            anchorMap["shouldDrawAnchor"] = true
                        }
                    }
                }
            }

            if(anchors.isNotEmpty())
            {
                for(i in 0 until anchors.size)
                {
                    val anchorMap = anchors[i]
                    val shouldDrawAnchor = anchorMap["shouldDrawAnchor"] as Boolean
                    val newAnchorMatrix = anchorMap["matrix"] as FloatArray

                    if(shouldDrawAnchor)
                    {
                        Log.d(TAG, "shouldDrawAnchor true for $i")
                        val colorCorrectionRgba = FloatArray(4)
                        frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

                        val scaleFactor = 1.0f
                        virtualObject.updateModelMatrix(newAnchorMatrix, scaleFactor)
                        virtualObjectShadow.updateModelMatrix(newAnchorMatrix, scaleFactor)
                        virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR)
                        virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR)
                    }
                }
            }
        }
        catch (t: Throwable)
        {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun setNewAnchor(newAnchor: Anchor?)
    {
        Log.d(TAG, "setNewAnchor()")

        synchronized(anchorLock)
        {
            if (newAnchor != null)
            {
                val anchorMap = HashMap<String, Any>()
                anchorMap["matrix"] = FloatArray(16)
                anchorMap["anchor"] = newAnchor
                anchorMap["shouldDrawAnchor"] = false
                anchors.add(anchorMap)
            }
        }
    }

    private fun onResolve()
    {
        Log.d(TAG, "onResolve()")

        if (!sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false))
        {
            showNoticeDialog(object : HostResolveListener
            {
                override fun onPrivacyNoticeReceived()
                {
                    onPrivacyAcceptedForResolve()
                }
            })
        }
        else
        {
            onPrivacyAcceptedForResolve()
        }
    }

    private fun onPrivacyAcceptedForResolve()
    {
        Log.d(TAG, "onPrivacyAcceptedForResolve()")

        clearAnchor()
        val location = this.location!!

        currentMode = HostResolveMode.RESOLVING
        snackbarHelper.showMessageWithDismiss(this, "Getting anchor from cloud for $location")

        firebaseManager?.registerNewListenerForList(location)
        { postSnapshot ->
            val snapShotObj = postSnapshot as DataSnapshot

            val roomCode = snapShotObj.key.toString().toLong()
            val valObj = snapShotObj.child("hosted_anchor_id").value
            if (valObj != null)
            {
                val anchorId = valObj.toString()
                if (!anchorId.isEmpty())
                {
                    Log.d(TAG, roomCode.toString())
                    Log.d(TAG, anchorId)

                    val resolveListener: CloudAnchorResolveStateListener = CloudAnchorResolveStateListener(roomCode)

                    cloudManager.resolveCloudAnchor(anchorId, resolveListener, SystemClock.uptimeMillis())
                }
            }
        }
    }

    private fun resetMode()
    {
        Log.d(TAG, "resetMode()")

        firebaseManager?.clearRoomListener()
        hostListener = null
        snackbarHelper.hide(this)
        cloudManager.clearListeners()
        clearAnchor()
    }

    private fun clearAnchor()
    {
        Log.d(TAG, "clearAnchor()")

        if (anchors.isNotEmpty())
        {
            for(i in 0 until anchors!!.size)
            {
                var anchorMap = anchors[i]
                if(anchorMap.containsKey("anchor"))
                {
                    var anchor = anchorMap["anchor"] as Anchor
                    anchor.detach()
                }
            }
            anchors.clear()
        }
    }

    private inner class RoomCodeAndCloudAnchorIdListener() : FirebaseManager.RoomCodeListener
    {
        private var roomCode: Long? = null
        override fun onNewRoomCode(newRoomCode: Long?)
        {
            Log.d("RoomCodeAndCloudAnchorIdListener", "onNewRoomCode()")

            Preconditions.checkState(roomCode == null, "The room code cannot have been set before.")
            roomCode = newRoomCode
            snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity, getString(R.string.snackbar_room_code_available))
        }

        override fun onError(error: DatabaseError?)
        {
            Log.d("RoomCodeAndCloudAnchorIdListener", "onError()")

            if (error != null)
            {
                Log.w(TAG, "A Firebase database error happened.", error.toException())
            }
            snackbarHelper.showError(this@UserCloudAnchorActivity, getString(R.string.snackbar_firebase_error))
        }
    }

    private inner class CloudAnchorResolveStateListener internal constructor(private val roomCode: Long) :CloudAnchorManager.CloudAnchorResolveListener
    {
        override fun onCloudTaskComplete(anchor: Anchor?)
        {
            Log.d("CloudAnchorResolveStateListener", "onCloudTaskComplete()")

            if(session != null)
            {
                val cloudState = anchor?.cloudAnchorState
                if (cloudState != null)
                {
                    if (cloudState.isError)
                    {
                        Log.w("CloudAnchorResolveStateListener","The anchor in room $roomCode could not be resolved. The error state was $cloudState")

                        snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity, getString(R.string.snackbar_resolve_error, cloudState))
                        return
                    }
                }
                snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity, getString(R.string.snackbar_resolve_success))

                setNewAnchor(anchor)
            }
        }

        override fun onShowResolveMessage()
        {
            Log.d("CloudAnchorResolveStateListener", "onShowResolveMessage()")

            if(session != null)
            {
                snackbarHelper.setMaxLines(4)
                snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity,getString(R.string.snackbar_resolve_no_result_yet))
            }
        }
    }

    private fun showNoticeDialog(listener: HostResolveListener?)
    {
        Log.d(TAG, "showNoticeDialog()")

        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog(listener)
        dialog.show(supportFragmentManager, PrivacyNoticeDialogFragment::class.java.getName())
    }

    override fun onDialogPositiveClick(dialog: DialogFragment?)
    {
        Log.d(TAG, "onDialogPositiveClick()")

        if (!sharedPreferences!!.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit())
        {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
    }

    companion object
    {
        private val TAG = UserCloudAnchorActivity::class.java.simpleName
        private val OBJECT_COLOR = floatArrayOf(53.0f, 24.0f, 255.0f, 255.0f)
        private const val PREFERENCE_FILE_KEY = "allow_sharing_images"
        private const val ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES"
    }
}