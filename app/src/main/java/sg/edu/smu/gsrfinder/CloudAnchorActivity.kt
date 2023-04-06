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

class CloudAnchorActivity() : AppCompatActivity(), GLSurfaceView.Renderer, NoticeDialogListener
{
    private enum class HostResolveMode
    {
        NONE, HOSTING, RESOLVING
    }

    //Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val virtualObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()
    private var installRequested = false

    //Temporary matrices allocated here to reduce number of allocations for each frame.
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    //Locks needed for synchronization
    private val singleTapLock = Any()
    private val anchorLock = Any()

    //Tap handling and UI.
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

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null
    private var session: Session? = null

    @GuardedBy("anchorLock")
    private var anchor: Anchor? = null

    //Cloud Anchor Components.
    private var firebaseManager: FirebaseManager? = null
    private val cloudManager: CloudAnchorManager = CloudAnchorManager()
    private var currentMode: HostResolveMode? = null
    private var hostListener: RoomCodeAndCloudAnchorIdListener? = null

    //Firebase
    private var schGsr: String? = null
    private var school: String? = null

    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.d(TAG, "onCreate()")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_anchor)

        displayRotationHelper = DisplayRotationHelper(this)

        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener()
            {
                override fun onSingleTapUp(e: MotionEvent): Boolean
                {
                    synchronized(singleTapLock)
                    {
                        if (currentMode == HostResolveMode.HOSTING)
                        {
                            queuedSingleTap = e
                        }
                    }
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean
                {
                    return true
                }
            })

        setUpSurfaceView()
        initUIComponent()
        initFirebaseManager()
        initSpinner()
    }

    private fun setUpSurfaceView()
    {
        Log.d(TAG, "setUpSurfaceView()")

        surfaceView = findViewById(R.id.surfaceview)

        surfaceView?.setOnTouchListener(OnTouchListener
        { v: View?, event: MotionEvent? ->
            gestureDetector!!.onTouchEvent((event)!!)
        })

        surfaceView?.preserveEGLContextOnPause = true
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView?.setRenderer(this)
        surfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView?.setWillNotDraw(false)
        installRequested = false
    }

    private fun initUIComponent()
    {
        Log.d(TAG, "initUIComponent()")

        hostButton = findViewById(R.id.host_button)
        hostButton = findViewById(R.id.host_button)
        hostButton?.setOnClickListener { onHostButtonPress() }
        resolveButton = findViewById(R.id.resolve_button)
        resolveButton?.setOnClickListener(View.OnClickListener { onResolveButtonPress() })
        roomCodeText = findViewById(R.id.room_code_text)
    }

    private fun initFirebaseManager()
    {
        Log.d(TAG, "initFirebaseManager()")

        firebaseManager = FirebaseManager(this)
        currentMode = HostResolveMode.NONE
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, MODE_PRIVATE)
    }

    private fun initSpinner()
    {
        Log.d(TAG, "initSpinner()")

        val schoolList = resources.getStringArray(R.array.schoolList)

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, schoolList)
        var spinToSchool = findViewById<Spinner>(R.id.spinToSchool)
        spinToSchool.adapter = spinAdapter

        spinToSchool.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d(TAG, "initSpinner() - onItemSelected()")
                val selectedString = parent!!.getItemAtPosition(index).toString()

                school = selectedString
                schGsr = selectedString

                when(school)
                {
                    "SCIS 1" ->
                    {
                        Log.d(TAG, "initSpinner() - User Selected SCIS 1")
                        showRoom(resources.getStringArray(R.array.scis1GsrList))
                    }
                    "SCIS 2/SOE" ->
                    {
                        Log.d(TAG, "initSpinner() - User Selected SCIS 2/SOE")
                        showRoom(resources.getStringArray(R.array.scis2soeGsrList))
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d(TAG, "initSpinner() - onNothingSelected()")
            }
        }        
    }

    private fun showRoom(gsrList: Array<String>)
    {
        Log.d(TAG, "showRoom()")

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, gsrList)
        val spinToRoom = findViewById<Spinner>(R.id.spinToRoom)
        spinToRoom.adapter = spinAdapter

        spinToRoom.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d(TAG, "showRoom() - onItemSelected()")
                val selectedString = parent!!.getItemAtPosition(index).toString()
                schGsr = "$school $selectedString"
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d(TAG, "showRoom() - onNothingSelected()")
            }
        }
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
        if (sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false))
        {
            createSession()
        }
        snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message))
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
        Log.d(TAG, "onPause()")

        super.onPause()
        if (session != null)
        {
            displayRotationHelper?.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,results: IntArray)
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

    private fun handleTap(frame: Frame, cameraTrackingState: TrackingState)
    {
        Log.d(TAG, "handleTap()")

        synchronized(singleTapLock)
        {
            synchronized(anchorLock)
            {
                if ((anchor == null) && (queuedSingleTap != null) && (cameraTrackingState == TrackingState.TRACKING))
                {
                    Preconditions.checkState(currentMode == HostResolveMode.HOSTING,"We should only be creating an anchor in hosting mode.")
                    for (hit: HitResult in frame.hitTest(queuedSingleTap))
                    {
                        if (shouldCreateAnchorWithHit(hit))
                        {
                            val newAnchor: Anchor = hit.createAnchor()
                            Preconditions.checkNotNull(hostListener,"The host listener cannot be null.")
                            hostListener?.let { cloudManager.hostCloudAnchor(newAnchor, it) }
                            setNewAnchor(newAnchor)
                            snackbarHelper.showMessage(this,getString(R.string.snackbar_anchor_placed))
                            break
                        }
                    }
                }
            }
            queuedSingleTap = null
        }
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig)
    {
        Log.d(TAG, "onSurfaceCreated()")

        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try
        {
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
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
            handleTap(frame, cameraTrackingState)
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

            planeRenderer.drawPlanes(session!!.getAllTrackables(Plane::class.java),camera.displayOrientedPose,projectionMatrix)

            synchronized(anchorLock)
            {
                if(anchors.isNotEmpty())
                {
                    Log.d(TAG, "anchors is not null")
                    Log.d(TAG, anchors.toString())

                    for(i in 0 until anchors.size)
                    {
                        val anchorMap = anchors[i]
                        val anchor = anchorMap["anchor"] as Anchor

                        Log.d(TAG, "anchors[$i] is not null")

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

    private fun onHostButtonPress()
    {
        Log.d(TAG, "onHostButtonPress()")

        if (currentMode == HostResolveMode.HOSTING)
        {
            resetMode()
            return
        }
        if (!sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false))
        {
            showNoticeDialog(object : HostResolveListener
            {
                override fun onPrivacyNoticeReceived()
                {
                    onPrivacyAcceptedForHost()
                }
            })
        }
        else
        {
            onPrivacyAcceptedForHost()
        }
    }

    private fun onPrivacyAcceptedForHost()
    {
        Log.d(TAG, "onPrivacyAcceptedForHost()")

        if (hostListener != null)
        {
            return
        }
        resolveButton!!.isEnabled = false
        hostButton?.setText(R.string.cancel)
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_host))
        hostListener = RoomCodeAndCloudAnchorIdListener()
        firebaseManager?.getNewRoomCode(hostListener!!)
    }

    private fun onResolveButtonPress()
    {
        Log.d(TAG, "onResolveButtonPress()")

        if (currentMode == HostResolveMode.RESOLVING)
        {
            resetMode()
            return
        }

        if (!sharedPreferences!!.getBoolean(ALLOW_SHARE_IMAGES_KEY, false))
        {
            showNoticeDialog(object : HostResolveListener
            {
                override fun onPrivacyNoticeReceived()
                {
                    onRoomCodeEntered()
                }
            })
        }
        else
        {
            onRoomCodeEntered()
        }
    }

    private fun resetMode()
    {
        Log.d(TAG, "resetMode()")

        clearAnchor()
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

    private fun onRoomCodeEntered()
    {
        Log.d(TAG, "onRoomCodeEntered()")

        clearAnchor()

        val location = this.schGsr!!
        currentMode = HostResolveMode.RESOLVING
        hostButton!!.isEnabled = false
        resolveButton?.setText(R.string.cancel)
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve))

        firebaseManager?.registerNewListenerForList(location) { postSnapshot ->
            val snapShotObj = postSnapshot as DataSnapshot
            val roomCode = snapShotObj.key.toString().toLong()
            val valObj = snapShotObj.child("hosted_anchor_id").value

            if (valObj != null)
            {
                val anchorId = valObj.toString()
                if (anchorId.isNotEmpty())
                {
                    val resolveListener = CloudAnchorResolveStateListener(roomCode)
                    cloudManager.resolveCloudAnchor(anchorId, resolveListener, SystemClock.uptimeMillis())
                }
            }
        }
    }
    
    private fun showNoticeDialog(listener: HostResolveListener?)
    {
        Log.d(TAG, "onShowResolveMessage()")

        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog(listener)
        dialog.show(supportFragmentManager, PrivacyNoticeDialogFragment::class.java.name)
    }

    override fun onDialogPositiveClick(dialog: DialogFragment?)
    {
        Log.d(TAG, "onShowResolveMessage()")

        if (!sharedPreferences!!.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit())
        {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
    }    

    private inner class RoomCodeAndCloudAnchorIdListener() : CloudAnchorManager.CloudAnchorHostListener, FirebaseManager.RoomCodeListener
    {
        private var roomCode: Long? = null
        private var cloudAnchorId: String? = null
        override fun onNewRoomCode(newRoomCode: Long?)
        {
            Log.d("RoomCodeAndCloudAnchorIdListener", "onNewRoomCode()")

            Preconditions.checkState(roomCode == null, "The room code cannot have been set before.")
            roomCode = newRoomCode
            roomCodeText!!.text = roomCode.toString()
            snackbarHelper.showMessageWithDismiss(
                this@CloudAnchorActivity, getString(R.string.snackbar_room_code_available)
            )
            Log.d("CloudAnchorActivty() - onNewRoomCode()", "roomCode: $roomCode")
            checkAndMaybeShare()
            synchronized(singleTapLock)
            {
                currentMode = HostResolveMode.HOSTING
            }
        }

        override fun onError(error: DatabaseError?)
        {
            Log.d("RoomCodeAndCloudAnchorIdListener", "onError()")

            if (error != null)
            {
                Log.e("RoomCodeAndCloudAnchorIdListener", "A Firebase database error happened.", error.toException())
            }
            snackbarHelper.showError(this@CloudAnchorActivity, getString(R.string.snackbar_firebase_error))
        }

        private fun checkAndMaybeShare()
        {
            Log.d("RoomCodeAndCloudAnchorIdListener", "checkAndMaybeShare()")

            if (roomCode == null || cloudAnchorId == null)
            {
                return
            }
            firebaseManager?.storeAnchorIdInRoom(schGsr!!, roomCode!!, cloudAnchorId)
            snackbarHelper.showMessageWithDismiss(this@CloudAnchorActivity, getString(R.string.snackbar_cloud_id_shared))
        }

        override fun onCloudTaskComplete(anchor: Anchor?)
        {
            Log.d("RoomCodeAndCloudAnchorIdListener", "onCloudTaskComplete()")

            val cloudState = anchor?.cloudAnchorState
            if (cloudState != null)
            {
                if (cloudState.isError)
                {
                    Log.e("RoomCodeAndCloudAnchorIdListener","Error hosting a cloud anchor, state $cloudState")
                    snackbarHelper.showMessageWithDismiss(this@CloudAnchorActivity, getString(R.string.snackbar_host_error, cloudState))
                    return
                }
            }

            Preconditions.checkState(cloudAnchorId == null, "The cloud anchor ID cannot have been set before.")

            if (anchor != null)
            {
                cloudAnchorId = anchor.cloudAnchorId
            }
            setNewAnchor(anchor)
            checkAndMaybeShare()
        }
    }

    private inner class CloudAnchorResolveStateListener(private val roomCode: Long) :CloudAnchorManager.CloudAnchorResolveListener
    {
        override fun onCloudTaskComplete(anchor: Anchor?)
        {
            Log.d("CloudAnchorResolveStateListener", "onCloudTaskComplete()")

            val cloudState = anchor?.cloudAnchorState
            if (cloudState != null)
            {
                if (cloudState.isError)
                {
                    Log.e("CloudAnchorResolveStateListener","The anchor in room $roomCode could not be resolved. The error state was $cloudState")
                    snackbarHelper.showMessageWithDismiss(this@CloudAnchorActivity, getString(R.string.snackbar_resolve_error, cloudState))
                    return
                }
            }
            snackbarHelper.showMessageWithDismiss(this@CloudAnchorActivity, getString(R.string.snackbar_resolve_success))

            setNewAnchor(anchor)
        }

        override fun onShowResolveMessage()
        {
            Log.d("CloudAnchorResolveStateListener", "onShowResolveMessage()")

            snackbarHelper.setMaxLines(4)
            snackbarHelper.showMessageWithDismiss(this@CloudAnchorActivity, getString(R.string.snackbar_resolve_no_result_yet))
        }
    }

    companion object 
    {
        private val TAG = CloudAnchorActivity::class.java.simpleName
        private val OBJECT_COLOR = floatArrayOf(53.0f, 24.0f, 255.0f, 255.0f)
        private const val PREFERENCE_FILE_KEY = "allow_sharing_images"
        private const val ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES"

        private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean
        {
            Log.d(TAG, "shouldCreateAnchorWithHit()")

            val trackable = hit.trackable

            if (trackable is Plane)
            {
                return trackable.isPoseInPolygon(hit.hitPose)
            }
            else if (trackable is Point)
            {
                return trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            return false
        }
    }
}