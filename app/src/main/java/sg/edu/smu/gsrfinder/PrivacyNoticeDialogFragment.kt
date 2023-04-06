package sg.edu.smu.gsrfinder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.DialogFragment

class PrivacyNoticeDialogFragment : DialogFragment()
{
    interface HostResolveListener
    {
        fun onPrivacyNoticeReceived()
    }
    interface NoticeDialogListener
    {
        fun onDialogPositiveClick(dialog: DialogFragment?)
    }

    var noticeDialogListener: NoticeDialogListener? = null
    var hostResolveListener: HostResolveListener? = null
    override fun onAttach(context: Context)
    {
        Log.d(TAG, "onAttach()")

        super.onAttach(context)

        noticeDialogListener =
        try
        {
            context as NoticeDialogListener
        }
        catch (e: ClassCastException)
        {
            throw AssertionError("Must implement NoticeDialogListener", e)
        }
    }

    override fun onDetach()
    {
        Log.d(TAG, "onDetach()")

        super.onDetach()
        noticeDialogListener = null
        hostResolveListener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog
    {
        Log.d(TAG, "onCreateDialog()")

        val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
        builder
            .setTitle(R.string.share_experience_title)
            .setMessage(R.string.share_experience_message)
            .setPositiveButton(
                R.string.agree_to_share
            ) { dialog, id ->
                noticeDialogListener!!.onDialogPositiveClick(this@PrivacyNoticeDialogFragment)
                hostResolveListener!!.onPrivacyNoticeReceived()
            }
            .setNegativeButton(
                R.string.learn_more
            ) { dialog, id ->
                val browserIntent = Intent(
                    Intent.ACTION_VIEW, Uri.parse(getString(R.string.learn_more_url))
                )
                requireActivity().startActivity(browserIntent)
            }
        return builder.create()
    }

    companion object
    {
        private val TAG = PrivacyNoticeDialogFragment::class.java.simpleName

        fun createDialog(hostResolveListener: HostResolveListener?): PrivacyNoticeDialogFragment
        {
            Log.d(TAG, "createDialog()")

            val dialogFragment = PrivacyNoticeDialogFragment()
            dialogFragment.hostResolveListener = hostResolveListener
            return dialogFragment
        }
    }
}