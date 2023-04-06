package sg.edu.smu.gsrfinder

import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Session
import com.google.common.base.Preconditions

internal class CloudAnchorManager
{
    private var deadlineForMessageMillis: Long = 0

    internal interface CloudAnchorHostListener
    {
        fun onCloudTaskComplete(anchor: Anchor?)
    }

    internal interface CloudAnchorResolveListener
    {
        fun onCloudTaskComplete(anchor: Anchor?)
        fun onShowResolveMessage()
    }

    private var session: Session? = null
    private val pendingHostAnchors = HashMap<Anchor, CloudAnchorHostListener>()
    private val pendingResolveAnchors = HashMap<Anchor, CloudAnchorResolveListener>()

    @Synchronized
    fun setSession(session: Session?)
    {
        Log.d(TAG, "setSession()")

        this.session = session
    }

    @Synchronized
    fun hostCloudAnchor(anchor: Anchor?, listener: CloudAnchorHostListener)
    {
        Log.d(TAG, "hostCloudAnchor()")

        Preconditions.checkNotNull(session, "The session cannot be null.")
        val newAnchor = session!!.hostCloudAnchor(anchor)
        pendingHostAnchors[newAnchor] = listener
    }

    @Synchronized
    fun resolveCloudAnchor(anchorId: Any, listener: CloudAnchorResolveListener, startTimeMillis: Long)
    {
        Log.d(TAG, "resolveCloudAnchor()")

        Preconditions.checkNotNull(session, "The session cannot be null.")
        val newAnchor = session!!.resolveCloudAnchor(anchorId as String?)
        deadlineForMessageMillis = startTimeMillis + DURATION_FOR_NO_RESOLVE_RESULT_MS
        pendingResolveAnchors[newAnchor] = listener
    }

    @Synchronized
    fun onUpdate()
    {
        Log.d(TAG, "onUpdate()")

        Preconditions.checkNotNull(session, "The session cannot be null.")
        val hostIter: MutableIterator<Map.Entry<Anchor, CloudAnchorHostListener>> = pendingHostAnchors.entries.iterator()

        while (hostIter.hasNext())
        {
            val (anchor, listener) = hostIter.next()

            if (isReturnableState(anchor.cloudAnchorState))
            {
                listener.onCloudTaskComplete(anchor)
                hostIter.remove()
            }
        }

        val resolveIter: MutableIterator<Map.Entry<Anchor, CloudAnchorResolveListener>> = pendingResolveAnchors.entries.iterator()

        while (resolveIter.hasNext())
        {
            val (anchor, listener) = resolveIter.next()

            if (isReturnableState(anchor.cloudAnchorState))
            {
                listener.onCloudTaskComplete(anchor)
                resolveIter.remove()
            }
            if (deadlineForMessageMillis > 0 && SystemClock.uptimeMillis() > deadlineForMessageMillis) {
                listener.onShowResolveMessage()
                deadlineForMessageMillis = 0
            }
        }
    }

    @Synchronized
    fun clearListeners()
    {
        Log.d(TAG, "clearListeners()")

        pendingHostAnchors.clear()
        deadlineForMessageMillis = 0
    }

    companion object
    {
        private val TAG = CloudAnchorManager::class.java.simpleName
        private const val DURATION_FOR_NO_RESOLVE_RESULT_MS: Long = 10000
        private fun isReturnableState(cloudState: CloudAnchorState): Boolean
        {
            Log.d(TAG, "isReturnableState()")

            return when (cloudState)
            {
                CloudAnchorState.NONE, CloudAnchorState.TASK_IN_PROGRESS -> false
                else -> true
            }
        }
    }
}