/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

/**
 * Main Activity for the Cloud Anchor Example
 *
 *
 * This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
class CloudAnchorActivity() : AppCompatActivity(), GLSurfaceView.Renderer,
    NoticeDialogListener {
    private enum class HostResolveMode {
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
    private val singleTapLock = Any()
    private val anchorLock = Any()

    // Tap handling and UI.
    private var gestureDetector: GestureDetector? = null
    private val snackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)
    private var hostButton: Button? = null
    private var resolveButton: Button? = null
    private var roomCodeText: TextView? = null
    private var sharedPreferences: SharedPreferences? = null

    //Array to store anchor
    private var anchors: ArrayList<HashMap<String, Any>> = ArrayList()

//    private var displayAnchorFnList: ArrayList<DisplayAnchor> = ArrayList();

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null
    private var session: Session? = null

    @GuardedBy("anchorLock")
    private var anchor: Anchor? = null

    // Cloud Anchor Components.
    private var firebaseManager: FirebaseManager? = null
    private val cloudManager: CloudAnchorManager = CloudAnchorManager()
    private var currentMode: HostResolveMode? = null
    private var hostListener: RoomCodeAndCloudAnchorIdListener? = null
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_anchor)
        surfaceView = findViewById<GLSurfaceView>(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper(this)

        // Set up touch listener.
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    synchronized(singleTapLock) {
                        if (currentMode == HostResolveMode.HOSTING) {
                            queuedSingleTap = e
                        }
                    }
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }
            })

        surfaceView?.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent? ->
            gestureDetector!!.onTouchEvent(
                (event)!!
            )
        })

        // Set up renderer.
        surfaceView?.setPreserveEGLContextOnPause(true)
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView?.setRenderer(this)
        surfaceView?.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        surfaceView?.setWillNotDraw(false)
        installRequested = false

        // Initialize UI components.
        hostButton = findViewById<Button>(R.id.host_button)
        hostButton?.setOnClickListener(View.OnClickListener { view: View? -> onHostButtonPress() })
        resolveButton = findViewById<Button>(R.id.resolve_button)
        resolveButton?.setOnClickListener(View.OnClickListener { view: View? -> onResolveButtonPress() })
        roomCodeText = findViewById<TextView>(R.id.room_code_text)

        // Initialize Cloud Anchor variables.
        firebaseManager = FirebaseManager(this)
        currentMode = HostResolveMode.NONE
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, MODE_PRIVATE)

        initSpinToSchool()
    }

    /*
     *  1. Show all schools in smu
     */
    private fun initSpinToSchool()
    {
        Log.d("MainActivity", "initSpinToSchool()");

        val list: Array<String>;

        val schoolList = resources.getStringArray(R.array.schoolList);

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, schoolList);
        val spinToSchool = findViewById<Spinner>(R.id.spinToSchool)
        spinToSchool.adapter = spinAdapter

        spinToSchool.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d("CloudAnchorActivity", "initSpinToSchool() - onItemSelected()");
                val selectedString = parent!!.getItemAtPosition(index).toString();

//                spinToSchool = selectedString;
                Log.d("STRING1: ", selectedString);

                initSpinToRoom(selectedString);
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("CloudAnchorActivity", "initSpinToSchool() - onNothingSelected()");
            }
        }
    }

    /*
     *  1. Show all gsr that belongs to the particular school
     */
    private fun initSpinToRoom(school: String)
    {
        Log.d("MainActivity", "initSpinToRoom()");

        when(school)
        {
            "SCIS 1" ->
            {
                Log.d("MainActivity", "initSpinToRoom() - User Selected SCIS 1");
                showRoom(resources.getStringArray(R.array.scis1GsrList));
            }
            "SCIS 2/SOE" ->
            {
                Log.d("MainActivity", "initSpinToRoom() - User Selected SCIS 2/SOE");
                showRoom(resources.getStringArray(R.array.scis2soeGsrList));
            }
        }
    }

    private fun showRoom(gsrList: Array<String>)
    {
        Log.d("MainActivity", "showRoom()");

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, gsrList);
        val spinToRoom = findViewById<Spinner>(R.id.spinToRoom)
        spinToRoom.adapter = spinAdapter

        spinToRoom.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d("CloudAnchorActivity", "showRoom() - onItemSelected()");
                val selectedString = parent!!.getItemAtPosition(index).toString();
                Log.d("STRING2: ", selectedString);

//                this@CloudAnchorActivity.spinToRoom = selectedString;
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("CloudAnchorActivity", "showRoom() - onNothingSelected()");
            }
        }
    }


    override fun onDestroy() {
        // Clear all registered listeners.
        resetMode()
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            createSession()
        }
        snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message))
        surfaceView!!.onResume()
        displayRotationHelper?.onResume()
    }

    private fun createSession() {
        if (session == null) {
            var exception: Exception? = null
            var messageId = -1
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
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
            } catch (e: UnavailableArcoreNotInstalledException) {
                messageId = R.string.snackbar_arcore_unavailable
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                messageId = R.string.snackbar_arcore_too_old
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                messageId = R.string.snackbar_arcore_sdk_too_old
                exception = e
            } catch (e: Exception) {
                messageId = R.string.snackbar_arcore_exception
                exception = e
            }
            if (exception != null) {
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
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable))
            session = null
            return
        }
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
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
    ) {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    /**
     * Handles the most recent user tap.
     *
     *
     * We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */
    private fun handleTap(frame: Frame, cameraTrackingState: TrackingState) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        synchronized(singleTapLock) {
            synchronized(anchorLock) {
                // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
                // camera is currently tracking.
                if ((anchor == null
                            ) && (queuedSingleTap != null
                            ) && (cameraTrackingState == TrackingState.TRACKING)
                ) {
                    Preconditions.checkState(
                        currentMode == HostResolveMode.HOSTING,
                        "We should only be creating an anchor in hosting mode."
                    )
                    for (hit: HitResult in frame.hitTest(queuedSingleTap)) {
                        if (shouldCreateAnchorWithHit(hit)) {
                            val newAnchor: Anchor = hit.createAnchor()
                            Preconditions.checkNotNull(
                                hostListener,
                                "The host listener cannot be null."
                            )
                            hostListener?.let { cloudManager.hostCloudAnchor(newAnchor, it) }
                            setNewAnchor(newAnchor)
                            snackbarHelper.showMessage(
                                this,
                                getString(R.string.snackbar_anchor_placed)
                            )
                            break // Only handle the first valid hit.
                        }
                    }
                }
            }
            queuedSingleTap = null
        }
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
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
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to read an asset file", ex)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
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

            // Handle user input.
            handleTap(frame, cameraTrackingState)

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

            // Visualize planes.
            planeRenderer.drawPlanes(
                session!!.getAllTrackables(Plane::class.java),
                camera.displayOrientedPose,
                projectionMatrix
            )
            Log.d(TAG, "onDrawFrame()");

            // Check if the anchor can be visualized or not, and get its pose if it can be.
            var shouldDrawAnchor = false
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
                            // Get the current pose of an Anchor in world space. The Anchor pose is updated
                            // during calls to session.update() as ARCore refines its estimate of the world.
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

//    private inner class DisplayAnchor(frame: Frame, cloudAnchorId: String) {
//        private var anchorMatrix = FloatArray(16)
//        private val viewMatrix = FloatArray(16)
//        private val projectionMatrix = FloatArray(16)
//        private val cloudAnchorId = cloudAnchorId;
//        private val frame = frame;
//
//        fun getAnchorId(): String
//        {
//            return cloudAnchorId;
//        }
//
//        fun displayAnchor()
//        {
//            Log.d(TAG, "Drawing anchor");
//
//            val colorCorrectionRgba = FloatArray(4)
//            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
//
//            // Update and draw the model and its shadow.
//            val scaleFactor = 1.0f
//            virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
//            virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
//            virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR)
//            virtualObjectShadow.draw(
//                viewMatrix,
//                projectionMatrix,
//                colorCorrectionRgba,
//                OBJECT_COLOR
//            )
//        }
//    }

//    private fun displayAnchor(frame: Frame)
//    {
//        // Visualize anchor.
//        if (shouldDrawAnchor) {
//            Log.d(TAG, "Drawing anchor");
//            val colorCorrectionRgba = FloatArray(4)
//            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
//
//            // Update and draw the model and its shadow.
//            val scaleFactor = 1.0f
//            virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
//            virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
//            virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR)
//            virtualObjectShadow.draw(
//                viewMatrix,
//                projectionMatrix,
//                colorCorrectionRgba,
//                OBJECT_COLOR
//            )
//        }
//
//    }

    /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.  */
    private fun setNewAnchor(newAnchor: Anchor?) {

        synchronized(anchorLock)
        {
            if (newAnchor != null)
            {
//                anchors.add(newAnchor);
                val anchorMap = HashMap<String, Any>()
                anchorMap["matrix"] = FloatArray(16);
                anchorMap["anchor"] = newAnchor;
                anchorMap["shouldDrawAnchor"] = false;
                anchors.add(anchorMap);
            }
        }
    }

    /** Callback function invoked when the Host Button is pressed.  */
    private fun onHostButtonPress() {
        if (currentMode == HostResolveMode.HOSTING) {
            resetMode()
            return
        }
        if (!sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            showNoticeDialog(object : HostResolveListener {
                override fun onPrivacyNoticeReceived() {
                    onPrivacyAcceptedForHost()
                }
            })
        } else {
            onPrivacyAcceptedForHost()
        }
    }

    private fun onPrivacyAcceptedForHost() {
        if (hostListener != null) {
            return
        }
        resolveButton!!.isEnabled = false
        hostButton?.setText(R.string.cancel)
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_host))
        hostListener = RoomCodeAndCloudAnchorIdListener()
        firebaseManager?.getNewRoomCode(hostListener!!)
    }

    /** Callback function invoked when the Resolve Button is pressed.  */
    private fun onResolveButtonPress() {
        if (currentMode == HostResolveMode.RESOLVING) {
            resetMode()
            return
        }
        if (!sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            showNoticeDialog(object : HostResolveListener {
                override fun onPrivacyNoticeReceived() {
                    onPrivacyAcceptedForResolve()
                }
            })
        } else {
            onPrivacyAcceptedForResolve()
        }
    }

    private fun onPrivacyAcceptedForResolve() {
        val dialogFragment = ResolveDialogFragment()
//        dialogFragment.setOkListener { roomCode: Long -> onRoomCodeEntered(roomCode) }
        dialogFragment.setOkListener { roomCode: String -> onRoomCodeEntered(roomCode) }
        dialogFragment.show(supportFragmentManager, "ResolveDialog")
    }

    /** Resets the mode of the app to its initial state and removes the anchors.  */
    private fun resetMode() {
        hostButton?.setText(R.string.host_button_text)
        hostButton!!.isEnabled = true
        resolveButton?.setText(R.string.resolve_button_text)
        resolveButton!!.isEnabled = true
        roomCodeText?.setText(R.string.initial_room_code)
        currentMode = HostResolveMode.NONE
        firebaseManager?.clearRoomListener()
        hostListener = null
        setNewAnchor(null)
        snackbarHelper.hide(this)
        cloudManager.clearListeners()
    }

    /** Callback function invoked when the user presses the OK button in the Resolve Dialog.  */
    private fun onRoomCodeEntered(location: String)
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

        currentMode = HostResolveMode.RESOLVING
        hostButton!!.isEnabled = false
        resolveButton?.setText(R.string.cancel)
//        roomCodeText!!.text = roomCode.toString()
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve))

        // Register a new listener for the given room.
        firebaseManager?.registerNewListenerForList(
            location
        ) { postSnapshot ->
            // When the cloud anchor ID is available from Firebase.
//            Log.d(TAG, cloudAnchorList as String)

//            Preconditions.checkNotNull(cloudAnchorList, "The resolve listener cannot be null.")

//            Log.d("CloudAnchorActivity", "Resolving cloud anchor with ID $cloudAnchorList");

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

                    val resolveListener: CloudAnchorResolveStateListener =
                        CloudAnchorResolveStateListener(
                            roomCode
                        )

                    cloudManager.resolveCloudAnchor(
                        anchorId, resolveListener, SystemClock.uptimeMillis())


                }
            }
        }

    }

    /** Callback function invoked when the user presses the OK button in the Resolve Dialog.  */
    private fun onRoomCodeEntered(roomCode: Long) {
        currentMode = HostResolveMode.RESOLVING
        hostButton!!.isEnabled = false
        resolveButton?.setText(R.string.cancel)
        roomCodeText!!.text = roomCode.toString()
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve))

        // Register a new listener for the given room.
        firebaseManager?.registerNewListenerForRoom(
            roomCode
        ) { cloudAnchorId ->
            // When the cloud anchor ID is available from Firebase.
            val resolveListener: CloudAnchorResolveStateListener =
                CloudAnchorResolveStateListener(
                    roomCode
                )
            Preconditions.checkNotNull(resolveListener, "The resolve listener cannot be null.")

            Log.d("CloudAnchorActivity", "Resolving cloud anchor with ID $cloudAnchorId");

            cloudManager.resolveCloudAnchor(
                cloudAnchorId, resolveListener, SystemClock.uptimeMillis()
            )
        }
    }

    /**
     * Listens for both a new room code and an anchor ID, and shares the anchor ID in Firebase with
     * the room code when both are available.
     */
    private inner class RoomCodeAndCloudAnchorIdListener() :
        CloudAnchorManager.CloudAnchorHostListener,
        FirebaseManager.RoomCodeListener {
        private var roomCode: Long? = null
        private var cloudAnchorId: String? = null
        override fun onNewRoomCode(newRoomCode: Long?) {
            Preconditions.checkState(roomCode == null, "The room code cannot have been set before.")
            roomCode = newRoomCode
            roomCodeText!!.text = roomCode.toString()
            snackbarHelper.showMessageWithDismiss(
                this@CloudAnchorActivity, getString(R.string.snackbar_room_code_available)
            )
            checkAndMaybeShare()
            synchronized(singleTapLock) {
                // Change currentMode to HOSTING after receiving the room code (not when the 'Host' button
                // is tapped), to prevent an anchor being placed before we know the room code and able to
                // share the anchor ID.
                currentMode =
                    HostResolveMode.HOSTING
            }
        }

        override fun onError(error: DatabaseError?) {
            if (error != null) {
                Log.w(TAG, "A Firebase database error happened.", error.toException())
            }
            snackbarHelper.showError(
                this@CloudAnchorActivity, getString(R.string.snackbar_firebase_error)
            )
        }

        private fun checkAndMaybeShare() {
            if (roomCode == null || cloudAnchorId == null) {
                return
            }
            firebaseManager?.storeAnchorIdInRoom(roomCode!!, cloudAnchorId)
            snackbarHelper.showMessageWithDismiss(
                this@CloudAnchorActivity, getString(R.string.snackbar_cloud_id_shared)
            )
        }

        override fun onCloudTaskComplete(anchor: Anchor?) {
            val cloudState = anchor?.cloudAnchorState
            if (cloudState != null) {
                if (cloudState.isError) {
                    Log.e(
                        TAG,
                        "Error hosting a cloud anchor, state $cloudState"
                    )
                    snackbarHelper.showMessageWithDismiss(
                        this@CloudAnchorActivity, getString(R.string.snackbar_host_error, cloudState)
                    )
                    return
                }
            }
            Preconditions.checkState(
                cloudAnchorId == null, "The cloud anchor ID cannot have been set before."
            )
            if (anchor != null) {
                cloudAnchorId = anchor.cloudAnchorId
            }
            Log.d(TAG, "RoomCodeAndCloudAnchorIdListener - onCloudTaskComplete() - Setting new Anchor")
            setNewAnchor(anchor)
            checkAndMaybeShare()
        }
    }

    private inner class CloudAnchorResolveStateListener internal constructor(private val roomCode: Long) :
        CloudAnchorManager.CloudAnchorResolveListener {

        override fun onCloudTaskComplete(anchor: Anchor?) {
            // When the anchor has been resolved, or had a final error state.
            val cloudState = anchor?.cloudAnchorState
            if (cloudState != null) {
                if (cloudState.isError) {
                    Log.w(
                        TAG,
                        "The anchor in room "
                                + roomCode
                                + " could not be resolved. The error state was "
                                + cloudState
                    )
                    snackbarHelper.showMessageWithDismiss(
                        this@CloudAnchorActivity, getString(R.string.snackbar_resolve_error, cloudState)
                    )
                    return
                }
            }
            snackbarHelper.showMessageWithDismiss(
                this@CloudAnchorActivity, getString(R.string.snackbar_resolve_success)
            )
            Log.d(TAG, "CloudAnchorResolveStateListener - onCloudTaskComplete() - Setting new Anchor")

            setNewAnchor(anchor)
        }

        override fun onShowResolveMessage() {
            snackbarHelper.setMaxLines(4)
            snackbarHelper.showMessageWithDismiss(
                this@CloudAnchorActivity, getString(R.string.snackbar_resolve_no_result_yet)
            )
        }
    }

    fun showNoticeDialog(listener: HostResolveListener?) {
        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog(listener)
        dialog.show(supportFragmentManager, PrivacyNoticeDialogFragment::class.java.getName())
    }

    override fun onDialogPositiveClick(dialog: DialogFragment?) {
        if (!sharedPreferences!!.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
    }

    companion object {
        private val TAG = CloudAnchorActivity::class.java.simpleName
        private val OBJECT_COLOR = floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
        private val PREFERENCE_FILE_KEY = "allow_sharing_images"
        private val ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES"

        /** Returns `true` if and only if the hit can be used to create an Anchor reliably.  */
        private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
            val trackable = hit.trackable
            if (trackable is Plane) {
                // Check if the hit was within the plane's polygon.
                return trackable.isPoseInPolygon(hit.hitPose)
            } else if (trackable is Point) {
                // Check if the hit was against an oriented point.
                return trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            return false
        }
    }
}