package ly.anoma.hex;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Fields for checking the input method's indicator and swipes on a connected device. */
public class PrivacyEditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setFocusableInTouchMode(true);
        fields.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        addField(fields, "Ordinary input", InputType.TYPE_CLASS_TEXT, 0);
        addField(fields, "Incognito browser input", 0x280011, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        addField(fields, "Instagram composer input", 0x2ac001, 0x02000001);
        setContentView(fields);
        fields.requestFocus();
    }

    private void addField(LinearLayout fields, String label, int type, int options) {
        EditText field = new EditText(this);
        field.setHint(label);
        field.setInputType(type);
        field.setImeOptions(options);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        fields.addView(field);
    }
}
