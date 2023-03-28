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

import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Session
import com.google.common.base.Preconditions

/**
 * A helper class to handle all the Cloud Anchors logic, and add a callback-like mechanism on top of
 * the existing ARCore API.
 */
internal class CloudAnchorManager {
    private var deadlineForMessageMillis: Long = 0

    /** Listener for the results of a host operation.  */
    internal interface CloudAnchorHostListener {
        /** This method is invoked when the results of a Cloud Anchor operation are available.  */
        fun onCloudTaskComplete(anchor: Anchor?)
    }

    /** Listener for the results of a resolve operation.  */
    internal interface CloudAnchorResolveListener {
        /** This method is invoked when the results of a Cloud Anchor operation are available.  */
        fun onCloudTaskComplete(anchor: Anchor?)

        /** This method show the toast message.  */
        fun onShowResolveMessage()
    }

    private var session: Session? = null
    private val pendingHostAnchors = HashMap<Anchor, CloudAnchorHostListener>()
    private val pendingResolveAnchors = HashMap<Anchor, CloudAnchorResolveListener>()

    /**
     * This method is used to set the session, since it might not be available when this object is
     * created.
     */
    @Synchronized
    fun setSession(session: Session?) {
        this.session = session
    }

    /**
     * This method hosts an anchor. The `listener` will be invoked when the results are
     * available.
     */
    @Synchronized
    fun hostCloudAnchor(anchor: Anchor?, listener: CloudAnchorHostListener) {
        Preconditions.checkNotNull(session, "The session cannot be null.")
        val newAnchor = session!!.hostCloudAnchor(anchor)
        pendingHostAnchors[newAnchor] = listener
    }

    /**
     * This method resolves an anchor. The `listener` will be invoked when the results are
     * available.
     */
    @Synchronized
    fun resolveCloudAnchor(
        anchorId: Any, listener: CloudAnchorResolveListener, startTimeMillis: Long
    ) {
        Preconditions.checkNotNull(session, "The session cannot be null.")
        val newAnchor = session!!.resolveCloudAnchor(anchorId as String?)
        deadlineForMessageMillis = startTimeMillis + DURATION_FOR_NO_RESOLVE_RESULT_MS
        pendingResolveAnchors[newAnchor] = listener
    }

    /** Should be called after a [Session.update] call.  */
    @Synchronized
    fun onUpdate() {
        Preconditions.checkNotNull(session, "The session cannot be null.")
        val hostIter: MutableIterator<Map.Entry<Anchor, CloudAnchorHostListener>> =
            pendingHostAnchors.entries.iterator()
        while (hostIter.hasNext()) {
            val (anchor, listener) = hostIter.next()
            if (isReturnableState(anchor.cloudAnchorState)) {
                listener.onCloudTaskComplete(anchor)
                hostIter.remove()
            }
        }
        val resolveIter: MutableIterator<Map.Entry<Anchor, CloudAnchorResolveListener>> =
            pendingResolveAnchors.entries.iterator()
        while (resolveIter.hasNext()) {
            val (anchor, listener) = resolveIter.next()
            if (isReturnableState(anchor.cloudAnchorState)) {
                listener.onCloudTaskComplete(anchor)
                resolveIter.remove()
            }
            if (deadlineForMessageMillis > 0 && SystemClock.uptimeMillis() > deadlineForMessageMillis) {
                listener.onShowResolveMessage()
                deadlineForMessageMillis = 0
            }
        }
    }

    /** Used to clear any currently registered listeners, so they won't be called again.  */
    @Synchronized
    fun clearListeners() {
        pendingHostAnchors.clear()
        deadlineForMessageMillis = 0
    }

    companion object {
        private val TAG =
            CloudAnchorActivity::class.java.simpleName + "." + CloudAnchorManager::class.java.simpleName
        private const val DURATION_FOR_NO_RESOLVE_RESULT_MS: Long = 10000
        private fun isReturnableState(cloudState: CloudAnchorState): Boolean {
            return when (cloudState) {
                CloudAnchorState.NONE, CloudAnchorState.TASK_IN_PROGRESS -> false
                else -> true
            }
        }
    }
}