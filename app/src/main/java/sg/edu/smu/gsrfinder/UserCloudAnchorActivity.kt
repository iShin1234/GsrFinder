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
        NONE, HOSTING, RESOLVING
    }

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val virtualObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()
    private var installRequested = false

    // Temporary matrices allocated here to reduce number of allocations for each frame.
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
        surfaceView = findViewById<GLSurfaceView>(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper(this)

        // Set up renderer.
        surfaceView?.setPreserveEGLContextOnPause(true)
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView?.setRenderer(this)
        surfaceView?.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        surfaceView?.setWillNotDraw(false)
        installRequested = false

        // Initialize Cloud Anchor variables.
        firebaseManager = FirebaseManager(this)
        currentMode = HostResolveMode.NONE
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, MODE_PRIVATE)

        showLocation()
    }

    private fun showLocation ()
    {
        var location = intent.getStringExtra("location").toString();
        findViewById<TextView>(R.id.location).text = location

        this.location = location

        onResolve();
    }

    override fun onDestroy()
    {
        Log.d("UserCloudAnchorActivity", "onDestroy()");

        resetMode();
        if (session != null)
        {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume()
    {
        super.onResume()
        Log.d("UserCloudAnchorActivity", "onResume()");
        if (sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false))
        {
            Log.d("UserCloudAnchorActivity", "Allowing image sharing.");
            createSession()
        }
        surfaceView!!.onResume()
        displayRotationHelper?.onResume()
    }

    private fun createSession()
    {
        Log.d("UserCloudAnchorActivity", "createSession()");
        if (session == null)
        {
            Log.d("UserCloudAnchorActivity", "Creating session");
            var exception: Exception? = null
            var messageId = -1
            try
            {
                Log.d("UserCloudAnchorActivity", "Check if ARCore is installed");
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested))
                {
                    InstallStatus.INSTALL_REQUESTED ->
                    {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {}
                }
                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
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

            // Create default config and check if supported.
            val config = Config(session)
            config.setCloudAnchorMode(CloudAnchorMode.ENABLED)
            session!!.configure(config)

            // Setting the session in the HostManager.
            cloudManager.setSession(session)
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
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
        Log.d("UserCloudAnchorActivity", "onPause()");

        if (session != null)
        {
            Log.d("UserCloudAnchorActivity", "Pausing session.");
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.

            displayRotationHelper?.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    )
    {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean)
    {
        super.onWindowFocusChanged(hasFocus)
        Log.d("ARCore", "onWindowFocusChanged");
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig)
    {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try
        {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(this)
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread(
                this, "models/andy_shadow.obj", "models/andy_shadow.png"
            )
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
        displayRotationHelper?.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10)
    {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper?.updateSessionIfNeeded(session!!)
        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera
            val cameraTrackingState = camera.trackingState

            // Notify the cloudManager of all the updates.
            cloudManager.onUpdate()

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) {
                return
            }

            // Get camera and projection matrices.
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            frame.acquirePointCloud().use { pointCloud ->
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewMatrix, projectionMatrix)
            }

            planeRenderer.drawPlanes(
                session!!.getAllTrackables(Plane::class.java),
                camera.displayOrientedPose,
                projectionMatrix
            )

            synchronized(anchorLock)
            {
                if(anchors.isNotEmpty())
                {
                    Log.d(TAG, "anchors is not null");
                    Log.d(TAG, anchors.toString());

                    for(i in 0 until anchors.size)
                    {
                        val anchorMap = anchors[i];
                        val anchor = anchorMap["anchor"] as Anchor;

                        Log.d(TAG, "anchors[$i] is not null");

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
                    val anchorMap = anchors[i];
                    val shouldDrawAnchor = anchorMap["shouldDrawAnchor"] as Boolean;
                    val newAnchorMatrix = anchorMap["matrix"] as FloatArray;

                    if(shouldDrawAnchor)
                    {
                        Log.d(TAG, "shouldDrawAnchor true for $i");
                        val colorCorrectionRgba = FloatArray(4)
                        frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

                        // Update and draw the model and its shadow.
                        val scaleFactor = 1.0f
                        virtualObject.updateModelMatrix(newAnchorMatrix, scaleFactor)
                        virtualObjectShadow.updateModelMatrix(newAnchorMatrix, scaleFactor)
                        virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR)
                        virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR)
                    }
                }
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.  */
    private fun setNewAnchor(newAnchor: Anchor?) {

        synchronized(anchorLock)
        {
            if (newAnchor != null)
            {
                val anchorMap = HashMap<String, Any>()
                anchorMap["matrix"] = FloatArray(16);
                anchorMap["anchor"] = newAnchor;
                anchorMap["shouldDrawAnchor"] = false;
                anchors.add(anchorMap);
            }
        }
    }

    /** Callback function invoked when the Resolve Button is pressed.  */
    private fun onResolve()
    {
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
        clearAnchor();
        val location = this.location!!;

        currentMode = HostResolveMode.RESOLVING
        snackbarHelper.showMessageWithDismiss(this, "Getting anchor from cloud for $location");

        firebaseManager?.registerNewListenerForList(
            location
        ) { postSnapshot ->
            Log.d(TAG, "Resolving cloud anchor with ID");
            Log.d(TAG, postSnapshot.toString());
            val snapShotObj = postSnapshot as DataSnapshot;

            val roomCode = snapShotObj.key.toString().toLong();
            val valObj = snapShotObj.child("hosted_anchor_id").value
            if (valObj != null)
            {
                val anchorId = valObj.toString()
                if (!anchorId.isEmpty())
                {
                    Log.d(TAG, roomCode.toString());
                    Log.d(TAG, anchorId);

                    val resolveListener: CloudAnchorResolveStateListener = CloudAnchorResolveStateListener(roomCode)

                    cloudManager.resolveCloudAnchor(anchorId, resolveListener, SystemClock.uptimeMillis())
                }
            }
        }
    }

    private fun resetMode()
    {
        firebaseManager?.clearRoomListener()
        hostListener = null
        snackbarHelper.hide(this)
        cloudManager.clearListeners()
        clearAnchor();
    }

    private fun clearAnchor()
    {
        if (anchors.isNotEmpty())
        {
            for(i in 0 until anchors!!.size)
            {
                var anchorMap = anchors[i];
                if(anchorMap.containsKey("anchor"))
                {
                    var anchor = anchorMap["anchor"] as Anchor;
                    anchor.detach();
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
            Preconditions.checkState(roomCode == null, "The room code cannot have been set before.")
            roomCode = newRoomCode
            snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity, getString(R.string.snackbar_room_code_available))

            Log.d("CloudAnchorActivty() - onNewRoomCode()", "roomCode: $roomCode")
        }

        override fun onError(error: DatabaseError?)
        {
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
            if(session != null)
            {
                // When the anchor has been resolved, or had a final error state.
                val cloudState = anchor?.cloudAnchorState
                if (cloudState != null)
                {
                    if (cloudState.isError)
                    {
                        Log.w(
                            TAG,
                            "The anchor in room "
                                    + roomCode
                                    + " could not be resolved. The error state was "
                                    + cloudState
                        )
                        snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity, getString(R.string.snackbar_resolve_error, cloudState))
                        return
                    }
                }
                snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity, getString(R.string.snackbar_resolve_success))
                Log.d(TAG, "CloudAnchorResolveStateListener - onCloudTaskComplete() - Setting new Anchor")


                setNewAnchor(anchor)
            }
        }

        override fun onShowResolveMessage()
        {
            if(session != null)
            {
                snackbarHelper.setMaxLines(4)
                snackbarHelper.showMessageWithDismiss(this@UserCloudAnchorActivity,getString(R.string.snackbar_resolve_no_result_yet))
            }
        }
    }

    private fun showNoticeDialog(listener: HostResolveListener?)
    {
        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog(listener)
        dialog.show(supportFragmentManager, PrivacyNoticeDialogFragment::class.java.getName())
    }

    override fun onDialogPositiveClick(dialog: DialogFragment?)
    {
        if (!sharedPreferences!!.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit())
        {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
    }

    companion object
    {
        private val TAG = CloudAnchorActivity::class.java.simpleName
        private val OBJECT_COLOR = floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
        private const val PREFERENCE_FILE_KEY = "allow_sharing_images"
        private const val ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES"
    }
}