package cn.org.bjlx.usb_terminal;

import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.annotation.ColorInt;

import java.io.ByteArrayOutputStream;

final class TextUtil {

    @ColorInt static int caretBackground = 0xff666666;

    final static String newline_crlf = "\r\n";
    final static String newline_lf = "\n";

    static byte[] fromHexString(final CharSequence s) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte b = 0;
        int nibble = 0;
        for(int pos = 0; pos<s.length(); pos++) {
            if(nibble==2) {
                buf.write(b);
                nibble = 0;
                b = 0;
            }
            int c = s.charAt(pos);
            if(c>='0' && c<='9') { nibble++; b *= 16; b += c-'0';    }
            if(c>='A' && c<='F') { nibble++; b *= 16; b += c-'A'+10; }
            if(c>='a' && c<='f') { nibble++; b *= 16; b += c-'a'+10; }
        }
        if(nibble>0)
            buf.write(b);
        return buf.toByteArray();
    }

    static String toHexString(final byte[] buf) {
        return toHexString(buf, 0, buf.length);
    }

    static String toHexString(final byte[] buf, int begin, int end) {
        StringBuilder sb = new StringBuilder(3*(end-begin));
        toHexString(sb, buf, begin, end);
        return sb.toString();
    }

    static void toHexString(StringBuilder sb, final byte[] buf) {
        toHexString(sb, buf, 0, buf.length);
    }

    static void toHexString(StringBuilder sb, final byte[] buf, int begin, int end) {
        for(int pos=begin; pos<end; pos++) {
            if(sb.length()>0)
                sb.append(' ');
            int c;
            c = (buf[pos]&0xff) / 16;
            if(c >= 10) c += 'A'-10;
            else        c += '0';
            sb.append((char)c);
            c = (buf[pos]&0xff) % 16;
            if(c >= 10) c += 'A'-10;
            else        c += '0';
            sb.append((char)c);
        }
    }

    /**
     * use https://en.wikipedia.org/wiki/Caret_notation to avoid invisible control characters
     */
    static CharSequence toCaretString(CharSequence s, boolean keepNewline) {
        return toCaretString(s, keepNewline, s.length());
    }

    static CharSequence toCaretString(CharSequence s, boolean keepNewline, int length) {
        boolean found = false;
        for (int pos = 0; pos < length; pos++) {
            if (s.charAt(pos) < 32 && (!keepNewline ||s.charAt(pos)!='\n')) {
                found = true;
                break;
            }
        }
        if(!found)
            return s;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for(int pos=0; pos<length; pos++)
            if (s.charAt(pos) < 32 && (!keepNewline ||s.charAt(pos)!='\n')) {
                sb.append('^');
                sb.append((char)(s.charAt(pos) + 64));
                sb.setSpan(new BackgroundColorSpan(caretBackground), sb.length()-2, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(s.charAt(pos));
            }
        return sb;
    }

    static class AnsiRenderer {

        interface ControlHandler {
            void onEraseInLine(int mode);
            void onCursorHorizontalAbsolute(int column);
        }

        private static final int ESC = 27;
        private static final int[] ANSI_COLORS = {
                0xff000000, 0xffcd3131, 0xff0dbc79, 0xffe5e510,
                0xff2472c8, 0xffbc3fbc, 0xff11a8cd, 0xffe5e5e5
        };
        private static final int[] ANSI_BRIGHT_COLORS = {
                0xff666666, 0xffff6666, 0xff23d18b, 0xfff5f543,
                0xff3b8eea, 0xffd670d6, 0xff29b8db, 0xffffffff
        };

        private final StringBuilder pendingEscape = new StringBuilder();
        private final int defaultForegroundColor;
        private final ControlHandler controlHandler;
        private Integer foregroundColor;
        private Integer backgroundColor;
        private boolean bold;

        AnsiRenderer(int defaultForegroundColor, ControlHandler controlHandler) {
            this.defaultForegroundColor = defaultForegroundColor;
            this.controlHandler = controlHandler;
        }

        void reset() {
            pendingEscape.setLength(0);
            foregroundColor = null;
            backgroundColor = null;
            bold = false;
        }

        void appendTo(SpannableStringBuilder out, CharSequence input, boolean keepNewline) {
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (pendingEscape.length() > 0 || c == ESC) {
                    consumeEscapeChar(c);
                    continue;
                }
                appendStyledChar(out, c, keepNewline);
            }
        }

        CharSequence format(CharSequence input, boolean keepNewline) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            appendTo(out, input, keepNewline);
            return out;
        }

        private void consumeEscapeChar(char c) {
            pendingEscape.append(c);
            if (pendingEscape.length() == 1) {
                return;
            }
            if (pendingEscape.length() == 2 && pendingEscape.charAt(1) != '[') {
                pendingEscape.setLength(0);
                return;
            }
            if (!isEscapeComplete(c)) {
                if (pendingEscape.length() > 32) {
                    pendingEscape.setLength(0);
                }
                return;
            }
            applyEscapeSequence(pendingEscape);
            pendingEscape.setLength(0);
        }

        private boolean isEscapeComplete(char c) {
            return c >= '@' && c <= '~';
        }

        private void applyEscapeSequence(CharSequence escape) {
            if (escape.length() < 3 || escape.charAt(1) != '[') {
                return;
            }
            char command = escape.charAt(escape.length() - 1);
            String params = escape.subSequence(2, escape.length() - 1).toString();
            if (command == 'K') {
                controlHandler.onEraseInLine(parseFirstParam(params, 0));
                return;
            }
            if (command == 'G') {
                controlHandler.onCursorHorizontalAbsolute(parseFirstParam(params, 1));
                return;
            }
            if (command != 'm') {
                return;
            }
            if (params.isEmpty()) {
                applySgrCode(0);
                return;
            }
            String[] parts = params.split(";");
            for (String part : parts) {
                int code;
                if (part.isEmpty()) {
                    code = 0;
                } else {
                    try {
                        code = Integer.parseInt(part);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
                applySgrCode(code);
            }
        }

        private int parseFirstParam(String params, int defaultValue) {
            if (params.isEmpty()) {
                return defaultValue;
            }
            int separator = params.indexOf(';');
            String first = separator >= 0 ? params.substring(0, separator) : params;
            if (first.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(first);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        private void applySgrCode(int code) {
            switch (code) {
                case 0:
                    foregroundColor = null;
                    backgroundColor = null;
                    bold = false;
                    return;
                case 1:
                    bold = true;
                    return;
                case 22:
                    bold = false;
                    return;
                case 39:
                    foregroundColor = null;
                    return;
                case 49:
                    backgroundColor = null;
                    return;
                default:
                    break;
            }
            if (code >= 30 && code <= 37) {
                foregroundColor = ANSI_COLORS[code - 30];
            } else if (code >= 90 && code <= 97) {
                foregroundColor = ANSI_BRIGHT_COLORS[code - 90];
            } else if (code >= 40 && code <= 47) {
                backgroundColor = ANSI_COLORS[code - 40];
            } else if (code >= 100 && code <= 107) {
                backgroundColor = ANSI_BRIGHT_COLORS[code - 100];
            }
        }

        private void appendStyledChar(SpannableStringBuilder out, char c, boolean keepNewline) {
            if (c < 32 && (!keepNewline || c != '\n')) {
                int start = out.length();
                out.append('^');
                out.append((char) (c + 64));
                applyCurrentStyle(out, start, out.length());
                out.setSpan(new BackgroundColorSpan(caretBackground), out.length() - 2, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
            int start = out.length();
            out.append(c);
            applyCurrentStyle(out, start, out.length());
        }

        private void deleteLastChar(SpannableStringBuilder out) {
            if (out.length() == 0) {
                return;
            }
            out.delete(out.length() - 1, out.length());
        }

        private void applyCurrentStyle(SpannableStringBuilder out, int start, int end) {
            if (start >= end) {
                return;
            }
            int fg = foregroundColor != null ? foregroundColor : defaultForegroundColor;
            out.setSpan(new ForegroundColorSpan(fg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (backgroundColor != null) {
                out.setSpan(new BackgroundColorSpan(backgroundColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bold) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }


    static class HexWatcher implements TextWatcher {

        private final TextView view;
        private final StringBuilder sb = new StringBuilder();
        private boolean self = false;
        private boolean enabled = false;

        HexWatcher(TextView view) {
            this.view = view;
        }

        void enable(boolean enable) {
            if(enable) {
                view.setInputType(InputType.TYPE_CLASS_TEXT + InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                view.setInputType(InputType.TYPE_CLASS_TEXT + InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            enabled = enable;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if(!enabled || self)
                return;

            sb.delete(0,sb.length());
            int i;
            for(i=0; i<s.length(); i++) {
                char c = s.charAt(i);
                if(c >= '0' && c <= '9') sb.append(c);
                if(c >= 'A' && c <= 'F') sb.append(c);
                if(c >= 'a' && c <= 'f') sb.append((char)(c+'A'-'a'));
            }
            for(i=2; i<sb.length(); i+=3)
                sb.insert(i,' ');
            final String s2 = sb.toString();

            if(!s2.equals(s.toString())) {
                self = true;
                s.replace(0, s.length(), s2);
                self = false;
            }
        }
    }

}
