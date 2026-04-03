package cn.org.bjlx.usb_terminal;

import android.content.Context;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.appcompat.widget.AppCompatEditText;

final class CharacterModeEditText extends AppCompatEditText {

    interface Listener {
        void onTextCommitted(String text);
        void onBackspace();
    }

    private Listener listener;

    public CharacterModeEditText(Context context) {
        super(context);
    }

    public CharacterModeEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CharacterModeEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection target = super.onCreateInputConnection(outAttrs);
        return new InputConnectionWrapper(target, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && text.length() > 0) {
                    dispatchText(text.toString());
                    clearEditorText();
                    return true;
                }
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0) {
                    dispatchBackspace();
                    clearEditorText();
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return super.sendKeyEvent(event);
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                    dispatchBackspace();
                    clearEditorText();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    dispatchText("\n");
                    clearEditorText();
                    return true;
                }
                int unicodeChar = event.getUnicodeChar();
                if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
                    dispatchText(String.valueOf((char) unicodeChar));
                    clearEditorText();
                    return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            dispatchBackspace();
            clearEditorText();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            dispatchText("\n");
            clearEditorText();
            return true;
        }
        int unicodeChar = event.getUnicodeChar();
        if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
            dispatchText(String.valueOf((char) unicodeChar));
            clearEditorText();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void dispatchText(String text) {
        if (listener != null) {
            listener.onTextCommitted(text);
        }
    }

    private void dispatchBackspace() {
        if (listener != null) {
            listener.onBackspace();
        }
    }

    private void clearEditorText() {
        Editable editable = getText();
        if (editable != null) {
            editable.clear();
        }
    }
}
