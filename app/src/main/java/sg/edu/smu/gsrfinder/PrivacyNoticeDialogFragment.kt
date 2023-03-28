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

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.DialogFragment

/** A DialogFragment for the Privacy Notice Dialog Box.  */
class PrivacyNoticeDialogFragment : DialogFragment() {
    /** Listener for weather to start a host or resolve operation.  */
    interface HostResolveListener {
        /** Invoked when the user accepts sharing experience.  */
        fun onPrivacyNoticeReceived()
    }

    /** Listener for a privacy notice response.  */
    interface NoticeDialogListener {
        /** Invoked when the user accepts sharing experience.  */
        fun onDialogPositiveClick(dialog: DialogFragment?)
    }

    var noticeDialogListener: NoticeDialogListener? = null
    var hostResolveListener: HostResolveListener? = null
    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        noticeDialogListener = try {
            context as NoticeDialogListener
        } catch (e: ClassCastException) {
            throw AssertionError("Must implement NoticeDialogListener", e)
        }
    }

    override fun onDetach() {
        super.onDetach()
        noticeDialogListener = null
        hostResolveListener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
        builder
            .setTitle(R.string.share_experience_title)
            .setMessage(R.string.share_experience_message)
            .setPositiveButton(
                R.string.agree_to_share
            ) { dialog, id -> // Send the positive button event back to the host activity
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

    companion object {
        fun createDialog(hostResolveListener: HostResolveListener?): PrivacyNoticeDialogFragment {
            val dialogFragment = PrivacyNoticeDialogFragment()
            dialogFragment.hostResolveListener = hostResolveListener
            return dialogFragment
        }
    }
}