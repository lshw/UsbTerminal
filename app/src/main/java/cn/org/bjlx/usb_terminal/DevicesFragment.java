package cn.org.bjlx.usb_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;
import android.Manifest;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.TextView;
import android.text.TextUtils;

import com.espressif.iot.esptouch.EsptouchTask;
import com.espressif.iot.esptouch.IEsptouchResult;
import com.espressif.iot.esptouch.IEsptouchTask;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.List;

public class DevicesFragment extends ListFragment {
    private static final int SMART_CONFIG_PERMISSION_REQUEST_CODE = 3001;

    static class ListItem {
        UsbDevice device;
        int port;
        UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    private final ArrayList<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 38400;
    private int dataBits = 8;
    private int parity = 0;
    private int stopBits = 1;
    private boolean smartConfigInProgress;
    private boolean pendingSmartConfigDialogAfterPermission;
    private volatile IEsptouchTask smartConfigTask;
    @Nullable
    private AlertDialog smartConfigProgressDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        listAdapter = new ArrayAdapter<ListItem>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                ListItem item = listItems.get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                if(item.driver == null)
                    text1.setText(R.string.label_no_driver);
                else if(item.driver.getPorts().size() == 1)
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver",""));
                else
                    text1.setText(getString(R.string.label_port_with_index, item.driver.getClass().getSimpleName().replace("SerialDriver",""), item.port));
                text2.setText(getString(R.string.label_vendor_product, item.device.getVendorId(), item.device.getProductId()));
                return view;
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setEmptyText(getString(R.string.label_no_usb_devices_found));
        ((TextView) getListView().getEmptyView()).setTextSize(18);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroy() {
        cancelSmartConfigTask();
        dismissSmartConfigProgressDialog();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if(id == R.id.refresh) {
            refresh();
            return true;
        } else if (id ==R.id.baud_rate) {
            showCommunicationSettingsDialog();
            return true;
        } else if (id == R.id.smartConfigEsp32) {
            if (smartConfigInProgress) {
                showSmartConfigToast(R.string.status_smart_config_busy);
                return true;
            }
            if (!ensureSmartConfigPermission()) {
                pendingSmartConfigDialogAfterPermission = true;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        SMART_CONFIG_PERMISSION_REQUEST_CODE);
                showSmartConfigToast(R.string.status_smart_config_permission_required);
                return true;
            }
            showSmartConfigDialog();
            return true;
        } else if (id == R.id.shareLatestLog) {
            ((MainActivity) requireActivity()).openLatestLog();
            return true;
        } else if (id == R.id.language) {
            ((MainActivity) requireActivity()).showLanguageDialog();
            return true;
        } else if (id == R.id.about) {
            ((MainActivity) requireActivity()).showAboutDialog();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void showCommunicationSettingsDialog() {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_comm_params, null, false);
        Spinner baudRateSpinner = dialogView.findViewById(R.id.baudRateSpinner);
        Spinner dataBitsSpinner = dialogView.findViewById(R.id.dataBitsSpinner);
        Spinner paritySpinner = dialogView.findViewById(R.id.paritySpinner);
        Spinner stopBitsSpinner = dialogView.findViewById(R.id.stopBitsSpinner);

        String[] baudRates = getResources().getStringArray(R.array.baud_rates);
        String[] dataBitsLabels = getResources().getStringArray(R.array.data_bits_labels);
        String[] dataBitsValues = getResources().getStringArray(R.array.data_bits_values);
        String[] parityLabels = getResources().getStringArray(R.array.parity_labels);
        String[] parityValues = getResources().getStringArray(R.array.parity_values);
        String[] stopBitsLabels = getResources().getStringArray(R.array.stop_bits_labels);
        String[] stopBitsValues = getResources().getStringArray(R.array.stop_bits_values);

        baudRateSpinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, baudRates));
        dataBitsSpinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, dataBitsLabels));
        paritySpinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, parityLabels));
        stopBitsSpinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, stopBitsLabels));

        baudRateSpinner.setSelection(java.util.Arrays.asList(baudRates).indexOf(String.valueOf(baudRate)));
        dataBitsSpinner.setSelection(java.util.Arrays.asList(dataBitsValues).indexOf(String.valueOf(dataBits)));
        paritySpinner.setSelection(java.util.Arrays.asList(parityValues).indexOf(String.valueOf(parity)));
        stopBitsSpinner.setSelection(java.util.Arrays.asList(stopBitsValues).indexOf(String.valueOf(stopBits)));

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.dialog_baud_rate)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    baudRate = Integer.parseInt(baudRates[baudRateSpinner.getSelectedItemPosition()]);
                    dataBits = Integer.parseInt(dataBitsValues[dataBitsSpinner.getSelectedItemPosition()]);
                    parity = Integer.parseInt(parityValues[paritySpinner.getSelectedItemPosition()]);
                    stopBits = Integer.parseInt(stopBitsValues[stopBitsSpinner.getSelectedItemPosition()]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSmartConfigDialog() {
        String ssid = getConnectedWifiSsid();
        if (TextUtils.isEmpty(ssid)) {
            showSmartConfigToast(R.string.status_smart_config_wifi_unavailable);
            return;
        }
        if (isConnectedWifi5G()) {
            showSmartConfigToast(R.string.status_smart_config_wifi_5g);
            return;
        }

        View dialogView = requireActivity().getLayoutInflater().inflate(R.layout.dialog_smart_config, null, false);
        EditText ssidView = dialogView.findViewById(R.id.smart_config_ssid);
        EditText passwordView = dialogView.findViewById(R.id.smart_config_password);
        ssidView.setText(ssid);
        ssidView.setSelection(ssid.length());

        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.dialog_smart_config_title)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dialog_smart_config_start, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String inputSsid = ssidView.getText() == null ? "" : ssidView.getText().toString().trim();
            String password = passwordView.getText() == null ? "" : passwordView.getText().toString();
            if (TextUtils.isEmpty(inputSsid)) {
                showSmartConfigToast(R.string.status_smart_config_ssid_required);
                ssidView.requestFocus();
                return;
            }
            dialog.dismiss();
            startSmartConfig(inputSsid, password);
        }));
        dialog.show();
    }

    private void startSmartConfig(String ssid, String password) {
        if (smartConfigInProgress) {
            showSmartConfigToast(R.string.status_smart_config_busy);
            return;
        }
        String bssid = getConnectedWifiBssid();
        if (TextUtils.isEmpty(bssid)) {
            showSmartConfigToast(R.string.status_smart_config_bssid_unavailable);
            return;
        }
        smartConfigInProgress = true;
        showSmartConfigToast(getString(R.string.status_smart_config_starting, ssid));
        showSmartConfigProgressDialog();
        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> runSmartConfig(appContext, ssid, bssid, password), "esp32-smart-config-devices").start();
    }

    private void runSmartConfig(Context context, String ssid, String bssid, String password) {
        try {
            IEsptouchTask task = new EsptouchTask(ssid, bssid, password, context);
            task.setPackageBroadcast(true);
            smartConfigTask = task;
            List<IEsptouchResult> results = task.executeForResults(1);
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> finishSmartConfig(results, null));
            }
        } catch (Exception e) {
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> finishSmartConfig(null, e));
            }
        } finally {
            smartConfigTask = null;
        }
    }

    private void finishSmartConfig(@Nullable List<IEsptouchResult> results, @Nullable Exception error) {
        dismissSmartConfigProgressDialog();
        smartConfigInProgress = false;
        if (!isAdded()) {
            return;
        }
        if (error != null) {
            String message = TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
            showSmartConfigToast(getString(R.string.status_smart_config_failed, message));
            return;
        }
        if (results == null || results.isEmpty()) {
            showSmartConfigToast(R.string.status_smart_config_timeout);
            return;
        }
        IEsptouchResult result = results.get(0);
        if (result.isCancelled()) {
            showSmartConfigToast(R.string.status_smart_config_cancelled);
            return;
        }
        if (!hasSmartConfigAck(result)) {
            showSmartConfigToast(R.string.status_smart_config_timeout);
            return;
        }
        String ip = result.getInetAddress() == null ? "-" : result.getInetAddress().getHostAddress();
        String mac = TextUtils.isEmpty(result.getBssid()) ? "-" : result.getBssid();
        showSmartConfigToast(getString(R.string.status_smart_config_ack_success, mac, ip));
    }

    private boolean hasSmartConfigAck(@Nullable IEsptouchResult result) {
        return result != null && !result.isCancelled() && result.isSuc();
    }

    private void showSmartConfigProgressDialog() {
        dismissSmartConfigProgressDialog();
        smartConfigProgressDialog = new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.dialog_smart_config_title)
                .setMessage(R.string.status_smart_config_running)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> cancelSmartConfigTask())
                .create();
        smartConfigProgressDialog.show();
    }

    private void dismissSmartConfigProgressDialog() {
        if (smartConfigProgressDialog != null) {
            smartConfigProgressDialog.dismiss();
            smartConfigProgressDialog = null;
        }
    }

    private void cancelSmartConfigTask() {
        IEsptouchTask task = smartConfigTask;
        if (task != null) {
            task.interrupt();
        }
    }

    private boolean ensureSmartConfigPermission() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    private WifiInfo getCurrentWifiInfo() {
        WifiManager wifiManager = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return null;
        }
        try {
            return wifiManager.getConnectionInfo();
        } catch (SecurityException e) {
            return null;
        }
    }

    @Nullable
    private String getConnectedWifiSsid() {
        WifiInfo wifiInfo = getCurrentWifiInfo();
        if (wifiInfo == null) {
            return null;
        }
        String ssid = wifiInfo.getSSID();
        if (TextUtils.isEmpty(ssid) || "<unknown ssid>".equalsIgnoreCase(ssid)) {
            return null;
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return TextUtils.isEmpty(ssid) ? null : ssid;
    }

    @Nullable
    private String getConnectedWifiBssid() {
        WifiInfo wifiInfo = getCurrentWifiInfo();
        if (wifiInfo == null) {
            return null;
        }
        String bssid = wifiInfo.getBSSID();
        if (TextUtils.isEmpty(bssid) || "02:00:00:00:00:00".equals(bssid)) {
            return null;
        }
        return bssid;
    }

    private boolean isConnectedWifi5G() {
        WifiInfo wifiInfo = getCurrentWifiInfo();
        if (wifiInfo == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        int frequency = wifiInfo.getFrequency();
        return frequency >= 4900 && frequency <= 5900;
    }

    private void showSmartConfigToast(int messageResId) {
        showSmartConfigToast(getString(messageResId));
    }

    private void showSmartConfigToast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    void refresh() {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();
        for(UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if(driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if(driver != null) {
                for(int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new ListItem(device, port, driver));
            } else {
                listItems.add(new ListItem(device, 0, null));
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        ListItem item = listItems.get(position-1);
        if(item.driver == null) {
            Toast.makeText(getActivity(), R.string.toast_no_driver, Toast.LENGTH_SHORT).show();
        } else {
            Bundle args = new Bundle();
            args.putInt("device", item.device.getDeviceId());
            args.putInt("vendor", item.device.getVendorId());
            args.putInt("product", item.device.getProductId());
            args.putInt("port", item.port);
            args.putInt("baud", baudRate);
            args.putInt("dataBits", dataBits);
            args.putInt("parity", parity);
            args.putInt("stopBits", stopBits);
            Fragment fragment = new TerminalFragment();
            fragment.setArguments(args);
            getParentFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == SMART_CONFIG_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean reopenDialog = pendingSmartConfigDialogAfterPermission && granted;
            pendingSmartConfigDialogAfterPermission = false;
            if (reopenDialog) {
                showSmartConfigDialog();
            } else if (!granted) {
                showSmartConfigToast(R.string.status_smart_config_permission_denied);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

}
