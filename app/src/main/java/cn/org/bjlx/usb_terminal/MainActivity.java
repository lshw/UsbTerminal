package cn.org.bjlx.usb_terminal;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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
            Toast.makeText(this, R.string.logs_latest_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.logs_open_latest)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.logs_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    void shareLatestLog() {
        Uri uri = LogFiles.getLatestLogUri(this);
        if (uri == null) {
            Toast.makeText(this, R.string.logs_latest_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.logs_share_latest)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.logs_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

}
