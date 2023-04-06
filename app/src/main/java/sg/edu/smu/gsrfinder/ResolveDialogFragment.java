package sg.edu.smu.gsrfinder;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.EditText;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.google.common.base.Preconditions;

public class ResolveDialogFragment extends DialogFragment
{
    interface OkListener
    {
        void onOkPressed();
    }
    private EditText roomCodeField;
    private OkListener okListener;

    public void setOkListener(OkListener okListener)
    {
        this.okListener = okListener;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState)
    {
        FragmentActivity activity = Preconditions.checkNotNull(getActivity(), "The activity cannot be null.");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        View dialogView = activity.getLayoutInflater().inflate(R.layout.resolve_dialog, null);
        roomCodeField = dialogView.findViewById(R.id.room_code_input);

        builder
        .setView(dialogView)
        .setTitle(R.string.resolve_dialog_title)
        .setPositiveButton(
            R.string.resolve_dialog_ok,
            (dialog, which) ->
            {
                okListener.onOkPressed();
            })
        .setNegativeButton(R.string.cancel, (dialog, which) -> {});

        return builder.create();
    }
}
