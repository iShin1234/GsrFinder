package sg.edu.smu.gsrfinder

import android.content.Context
import android.util.Log
import com.google.common.base.Preconditions
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*

internal class FirebaseManager(context: Context?)
{
    internal interface RoomCodeListener
    {
        fun onNewRoomCode(newRoomCode: Long?)
        fun onError(error: DatabaseError?)
    }

    private val app: FirebaseApp?
    private var hotspotListRef: DatabaseReference? = null
    private var roomCodeRef: DatabaseReference? = null
    private var currentRoomRef: DatabaseReference? = null
    private var currentRoomListener: ValueEventListener? = null
    private var listRef: DatabaseReference? = null
    private var allRoomListener: ValueEventListener? = null
    private var schGsrList = ArrayList<String>()

    init
    {
        Log.d(TAG, "init")

        app = FirebaseApp.initializeApp(context!!)
        if (app != null)
        {
            val rootRef = FirebaseDatabase.getInstance(app).reference
            val schoolList = context.resources.getStringArray(R.array.schoolList)
            val scis1GsrList = context.resources.getStringArray(R.array.scis1GsrList)
            val scis2soeGsrList = context.resources.getStringArray(R.array.scis2soeGsrList)

            for (sch in schoolList)
            {
                if (sch == "SCIS 1")
                {
                    for (gsr in scis1GsrList)
                    {
                        var newGsrString = "$sch $gsr"
                        Log.d("HERE", newGsrString)
                        schGsrList.add(newGsrString)
                    }
                }
                else
                {
                    for (gsr in scis2soeGsrList)
                    {
                        var newGsrString = "$sch $gsr"
                        schGsrList.add(newGsrString)
                    }
                }
            }
            roomCodeRef = rootRef.child(ROOT_LAST_ROOM_CODE)
            DatabaseReference.goOnline()
        }
        else
        {
            Log.d(TAG, "Could not connect to Firebase Database!")
            hotspotListRef = null
            roomCodeRef = null
        }
    }

    fun getNewRoomCode(listener: RoomCodeListener)
    {
        Log.d(TAG, "getNewRoomCode()")

        Preconditions.checkNotNull(app, "Firebase App was null")
        roomCodeRef!!.runTransaction(
            object : Transaction.Handler
            {
                override fun doTransaction(currentData: MutableData): Transaction.Result
                {
                    var nextCode = java.lang.Long.valueOf(1)
                    val currVal = currentData.value
                    if (currVal != null)
                    {
                        val lastCode = java.lang.Long.valueOf(currVal.toString())
                        nextCode = lastCode + 1
                    }
                    currentData.value = nextCode
                    return Transaction.success(currentData)
                }

                override fun onComplete(error: DatabaseError?,committed: Boolean,currentData: DataSnapshot?)
                {
                    if (!committed)
                    {
                        listener.onError(error)
                        return
                    }
                    val roomCode = currentData!!.getValue(Long::class.java)
                    listener.onNewRoomCode(roomCode)
                }
            })
    }

    fun storeAnchorIdInRoom(hotspot: String, roomCode: Long, cloudAnchorId: String?)
    {
        Log.d(TAG, "storeAnchorIdInRoom()")

        Preconditions.checkNotNull(app, "Firebase App was null")
        if (hotspot in schGsrList)
        {
            val rootRef = FirebaseDatabase.getInstance(app!!).reference
            hotspotListRef = rootRef.child(hotspot)
        }
        val roomRef = hotspotListRef!!.child(roomCode.toString())
        roomRef.child(KEY_DISPLAY_NAME).setValue(DISPLAY_NAME_VALUE)
        roomRef.child(KEY_ANCHOR_ID).setValue(cloudAnchorId)
        roomRef.child(KEY_TIMESTAMP).setValue(System.currentTimeMillis())
    }

    fun registerNewListenerForList(collect_list: String, listener: (Any) -> Unit)
    {
        Log.d(TAG, "registerNewListenerForList()")

        Preconditions.checkNotNull(app, "Firebase App was null")
        clearRoomListener()
        if(app != null)
        {
            val rootRef = FirebaseDatabase.getInstance(app).reference
            listRef = rootRef.child(collect_list)

            allRoomListener = object : ValueEventListener
            {
                override fun onDataChange(snapshot: DataSnapshot)
                {
                    if(snapshot.exists())
                    {
                        for (postSnapshot in snapshot.children) {
                            if(postSnapshot != null)
                            {
                                listener.invoke(postSnapshot)
                            }
                        }
                    }
                }
                override fun onCancelled(databaseError: DatabaseError)
                {
                    Log.w(TAG, "The Firebase operation was cancelled.", databaseError.toException())
                }
            }
            listRef!!.addValueEventListener(allRoomListener as ValueEventListener)
        }
    }

    fun clearRoomListener()
    {
        Log.d(TAG, "clearRoomListener()")

        if (currentRoomListener != null && currentRoomRef != null)
        {
            currentRoomRef!!.removeEventListener(currentRoomListener!!)
            currentRoomListener = null
            currentRoomRef = null
        }
    }

    companion object
    {
        private val TAG = FirebaseManager::class.java.simpleName
        private const val ROOT_LAST_ROOM_CODE = "last_room_code"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ANCHOR_ID = "hosted_anchor_id"
        private const val KEY_TIMESTAMP = "updated_at_timestamp"
        private const val DISPLAY_NAME_VALUE = "SMU_GSR"
    }
}