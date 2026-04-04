package cn.org.bjlx.usb_terminal;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status(getString(R.string.status_usb_device_detected));
        }
        super.onNewIntent(intent);
    }

    void showAboutDialog() {
        String appName = getString(R.string.app_name);
        String version = getString(R.string.version_label, BuildConfig.VERSION_NAME);
        String deviceDesc = getString(R.string.about_device_desc);
        String message = getString(R.string.about_message, appName, version, deviceDesc);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setMessage(message)
                .setPositiveButton(R.string.about_web, (dialog, which) -> openUrl(R.string.about_website_url))
                .setNeutralButton(R.string.about_product, (dialog, which) -> openUrl(R.string.about_product_url))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    void showLanguageDialog() {
        String[] labels = getResources().getStringArray(R.array.app_language_labels);
        String[] codes = getResources().getStringArray(R.array.app_language_codes);
        String currentCode = getCurrentLanguageCode();
        int checkedItem = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(currentCode)) {
                checkedItem = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_language)
                .setSingleChoiceItems(labels, checkedItem, (dialog, which) -> {
                    applyLanguageCode(codes[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private String getCurrentLanguageCode() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) {
            return "";
        }
        String tag = locales.get(0) != null ? locales.get(0).toLanguageTag() : "";
        if (tag.startsWith("zh-CN")) return "zh-CN";
        if (tag.startsWith("zh-TW")) return "zh-TW";
        if (tag.startsWith("ja")) return "ja";
        if (tag.startsWith("en")) return "en";
        return "";
    }

    private void applyLanguageCode(String code) {
        LocaleListCompat locales = code.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(code);
        AppCompatDelegate.setApplicationLocales(locales);
    }

    private void openUrl(int urlResId) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlResId)));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.about_open_web_failed, Toast.LENGTH_SHORT).show();
        }
    }

    void openLatestLog() {
        Uri uri = LogFiles.getLatestLogUri(this);
        if (uri == null) {
            openLogsDirectory();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.logs_open_latest)));
        } catch (Exception e) {
            openLogsDirectory();
        }
    }

    void shareLatestLog() {
        openLatestLog();
    }

    private void openLogsDirectory() {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(LogFiles.getLogsDirectoryUri(), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Intent fallbackIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(fallbackIntent);
                Toast.makeText(this, getString(R.string.logs_open_fallback, LogFiles.getPublicLogsDisplayPath()), Toast.LENGTH_LONG).show();
            } catch (Exception fallbackError) {
                Toast.makeText(this, R.string.logs_open_unsupported, Toast.LENGTH_SHORT).show();
            }
        }
    }

}
