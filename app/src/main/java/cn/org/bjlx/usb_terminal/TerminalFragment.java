package cn.org.bjlx.usb_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.espressif.iot.esptouch.EsptouchTask;
import com.espressif.iot.esptouch.IEsptouchResult;
import com.espressif.iot.esptouch.IEsptouchTask;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }
    private static final String PREFS_NAME = "terminal";
    private static final String PREF_COMM_LOG_ENABLED = "comm_log_enabled";
    private static final String PREF_COMM_LOG_HEX = "comm_log_hex";
    private static final long HEX_TOGGLE_MIN_INTERVAL_MILLIS = 1000;
    private static final int MAX_RECEIVE_TEXT_LENGTH = 12_000;
    private static final int TRIM_RECEIVE_TEXT_TO = 8_000;
    private static final int MAX_RECEIVE_TEXT_LINES = 160;
    private static final int TRIM_RECEIVE_TEXT_TO_LINES = 100;
    private static final int RECEIVE_RENDER_INTERVAL_MS = 33;
    private static final int RECEIVE_RENDER_MAX_BYTES = 16 * 1024;
    private static final int RECEIVE_IMMEDIATE_DRAIN_THRESHOLD = 64 * 1024;
    private static final String SMART_CONFIG_BSSID = "00:00:00:00:00:00";
    private static final String CONNECTION_TYPE_TELNET = "telnet";
    private final Handler mainLooper;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, vendorId, productId, portNum, baudRate, dataBits, parity, stopBits;
    private String connectionType;
    private String telnetHost;
    private int telnetPort;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private CharacterModeEditText characterInput;
    private ImageButton sendBtn;
    private View sendPanel;
    private View terminalRoot;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean characterMode = true;
    private boolean hexEnabled = false;
    private boolean reconnectPending = false;
    private enum SendButtonState {Idle, Busy, Disabled};
    private boolean commLogEnabled;
    private boolean commLogHex;
    private File commLogFile;
    private Uri commLogUri;
    private String commLogLocation;
    private OutputStream commLogStream;
    private BufferedWriter commLogWriter;
    private long lastHexToggleAtMillis;
    private boolean replaceableFlashStatusLine;
    private boolean smartConfigInProgress;
    private volatile IEsptouchTask smartConfigTask;
    private final AtomicBoolean smartConfigCompletionHandled = new AtomicBoolean();
    private boolean smartConfigCancelledByUser;
    @Nullable
    private AlertDialog smartConfigProgressDialog;

    private ControlLines controlLines = new ControlLines();

    private boolean pendingNewline = false;
    private static final String NEWLINE = TextUtil.newline_crlf;
    private TextUtil.AnsiRenderer ansiRenderer;
    private int pendingCursorColumn = -1;
    private boolean characterKeyboardVisible;
    private final ArrayDeque<byte[]> pendingReceiveQueue = new ArrayDeque<>();
    private int pendingReceiveBytes;
    private boolean receiveRenderScheduled;
    private final Runnable receiveRenderRunnable = this::drainReceiveQueue;

    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if(Constants.INTENT_ACTION_GRANT_USB.equals(action)) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (matchesDevice(device) && connected != Connected.False) {
                        status(getString(R.string.status_usb_device_detached));
                        disconnect(true);
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (matchesDevice(device) && reconnectPending && connected == Connected.False) {
                        status(getString(R.string.status_usb_device_attached_reconnecting));
                        mainLooper.post(() -> connect(null));
                    }
                }
            }
        };
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        connectionType = getArguments().getString("connectionType", "usb");
        telnetHost = getArguments().getString("host", "");
        telnetPort = getArguments().getInt("networkPort", 23);
        deviceId = getArguments().getInt("device");
        vendorId = getArguments().getInt("vendor", -1);
        productId = getArguments().getInt("product", -1);
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        dataBits = getArguments().getInt("dataBits", UsbSerialPort.DATABITS_8);
        parity = getArguments().getInt("parity", UsbSerialPort.PARITY_NONE);
        stopBits = getArguments().getInt("stopBits", UsbSerialPort.STOPBITS_1);
        SharedPreferences preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        commLogEnabled = preferences.getBoolean(PREF_COMM_LOG_ENABLED, false);
        commLogHex = preferences.getBoolean(PREF_COMM_LOG_HEX, false);
    }

    @Override
    public void onDestroy() {
        cancelSmartConfigTask();
        dismissSmartConfigProgressDialog();
        closeCommunicationLog();
        if (connected != Connected.False)
            disconnect(false);
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        if (isUsbConnection()) {
            ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
            ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED);
            ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    @Override
    public void onStop() {
        if (isUsbConnection()) {
            getActivity().unregisterReceiver(broadcastReceiver);
        }
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setText("", TextView.BufferType.EDITABLE);
        int receiveColor = getResources().getColor(R.color.colorRecieveText);
        receiveText.setTextColor(receiveColor); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        receiveText.setHorizontallyScrolling(false);
        terminalRoot = view.findViewById(R.id.terminal_root);
        ansiRenderer = new TextUtil.AnsiRenderer(receiveColor, new TextUtil.AnsiRenderer.ControlHandler() {
            @Override
            public void onEraseInLine(int mode) {
                pendingCursorColumn = mode == 0 ? pendingCursorColumn : 0;
            }

            @Override
            public void onCursorHorizontalAbsolute(int column) {
                pendingCursorColumn = Math.max(0, column - 1);
            }
        });

        sendPanel = view.findViewById(R.id.send_panel);
        sendText = view.findViewById(R.id.send_text);
        characterInput = view.findViewById(R.id.character_input);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        characterInput.setListener(new CharacterModeEditText.Listener() {
            @Override
            public void onTextCommitted(String text) {
                if (!characterMode || hexEnabled || connected != Connected.True) {
                    return;
                }
                send(text, false);
            }

            @Override
            public void onBackspace() {
                if (!characterMode || hexEnabled || connected != Connected.True) {
                    return;
                }
                send("\b", false);
            }
        });
        View.OnClickListener keyboardToggleListener = v -> {
            if (characterMode && !hexEnabled) {
                toggleCharacterKeyboard();
            }
        };
        terminalRoot.setOnClickListener(keyboardToggleListener);
        receiveText.setOnClickListener(keyboardToggleListener);
        updateInputModeUi();

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString(), !characterMode));
        controlLines.onCreateView(view);
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.characterMode).setChecked(characterMode);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        menu.findItem(R.id.communicationLog).setChecked(commLogEnabled);
        menu.findItem(R.id.communicationLogHex).setChecked(commLogHex);
        controlLines.onPrepareOptionsMenu(menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
        menu.findItem(R.id.smartConfigEsp32).setEnabled(!smartConfigInProgress);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("", TextView.BufferType.EDITABLE);
            pendingNewline = false;
            pendingCursorColumn = -1;
            if (ansiRenderer != null) {
                ansiRenderer.reset();
            }
            return true;
        } else if (id == R.id.characterMode) {
            characterMode = !characterMode;
            if (characterMode && hexEnabled) {
                applyHexMode(false, false);
                sendText.setText("");
                hexWatcher.enable(false);
            }
            item.setChecked(characterMode);
            updateInputModeUi();
            return true;
        } else if (id == R.id.hex) {
            if (!applyHexMode(!hexEnabled, true)) {
                item.setChecked(hexEnabled);
                return true;
            }
            if (hexEnabled) {
                characterMode = false;
            }
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            updateInputModeUi();
            requireActivity().invalidateOptionsMenu();
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.communicationLog) {
            setCommunicationLogEnabled(!commLogEnabled);
            item.setChecked(commLogEnabled);
            return true;
        } else if (id == R.id.communicationLogHex) {
            setCommunicationLogHex(!commLogHex);
            item.setChecked(commLogHex);
            return true;
        } else if (id == R.id.shareLatestLog) {
            ((MainActivity) requireActivity()).openLatestLog();
            return true;
        } else if (id == R.id.language) {
            ((MainActivity) requireActivity()).showLanguageDialog();
            return true;
        } else if (id == R.id.controlLines) {
            item.setChecked(controlLines.showControlLines(!item.isChecked()));
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (service == null) {
                    status(getString(R.string.status_notification_settings_unavailable));
                } else if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                status(getString(R.string.status_send_break));
                mainLooper.postDelayed(() -> {
                    try {
                        if (usbSerialPort != null) {
                            usbSerialPort.setBreak(false);
                        }
                    } catch (Exception e) {
                        status(getString(R.string.status_clear_break_failed, e.getMessage()));
                    }
                }, 100);
            } catch (Exception e) {
                status(getString(R.string.status_send_break_failed, e.getMessage()));
            }
            return true;
        } else if (id == R.id.smartConfigEsp32) {
            if (smartConfigInProgress) {
                status(getString(R.string.status_smart_config_busy));
                return true;
            }
            showSmartConfigDialog();
            return true;
        } else if (id == R.id.about) {
            ((MainActivity) requireActivity()).showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean applyHexMode(boolean enabled, boolean enforceMinInterval) {
        if (hexEnabled == enabled) {
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (enforceMinInterval && now - lastHexToggleAtMillis < HEX_TOGGLE_MIN_INTERVAL_MILLIS) {
            Toast.makeText(getActivity(), R.string.hex_toggle_too_fast, Toast.LENGTH_SHORT).show();
            return false;
        }
        hexEnabled = enabled;
        lastHexToggleAtMillis = now;
        if (commLogEnabled) {
            reopenCommunicationLog();
        }
        return true;
    }

    private void applyNoFlowControl() {
        if (usbSerialPort == null) {
            return;
        }
        try {
            usbSerialPort.setFlowControl(UsbSerialPort.FlowControl.NONE);
            controlLines.start();
        } catch (Exception e) {
            status(getString(R.string.status_set_flow_control_failed, e.getClass().getName(), e.getMessage()));
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        if (!isUsbConnection()) {
            connectTelnet();
            return;
        }
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbDevice device = findDevice(usbManager);
        if(device == null) {
            status(getString(R.string.status_connection_failed_device_not_found));
            return;
        }
        deviceId = device.getDeviceId();
        vendorId = device.getVendorId();
        productId = device.getProductId();
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status(getString(R.string.status_connection_failed_no_driver));
            return;
        }
        if(driver.getPorts().size() <= portNum) {
            status(getString(R.string.status_connection_failed_not_enough_ports));
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status(getString(R.string.status_connection_failed_permission_denied));
            else
                status(getString(R.string.status_connection_failed_open_failed));
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, dataBits, stopBits, parity);
            } catch (UnsupportedOperationException e) {
                status(getString(R.string.status_setting_serial_parameters_failed, e.getMessage()));
            }
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            reconnectPending = false;
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect(boolean reconnectExpected) {
        connected = Connected.False;
        reconnectPending = reconnectExpected;
        controlLines.stop();
        service.disconnect();
        updateSendBtn(SendButtonState.Idle);
        usbSerialPort = null;
        updateKeepScreenOn(false);
        if (isAdded()) {
            requireActivity().invalidateOptionsMenu();
        }
    }

    private void connectTelnet() {
        connected = Connected.Pending;
        new Thread(() -> {
            try {
                TelnetSocket socket = new TelnetSocket(getActivity().getApplicationContext(), telnetHost, telnetPort);
                service.connect(socket);
                reconnectPending = false;
                mainLooper.post(this::onSerialConnect);
            } catch (Exception e) {
                mainLooper.post(() -> onSerialConnectError(e));
            }
        }, "telnet-connect").start();
    }

    private UsbDevice findDevice(UsbManager usbManager) {
        UsbDevice fallback = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getDeviceId() == deviceId) {
                return device;
            }
            if (fallback == null && matchesDevice(device)) {
                fallback = device;
            }
        }
        return fallback;
    }

    private boolean matchesDevice(UsbDevice device) {
        if (!isUsbConnection()) {
            return false;
        }
        if (device == null) {
            return false;
        }
        if (device.getDeviceId() == deviceId) {
            return true;
        }
        return vendorId != -1 && productId != -1
                && device.getVendorId() == vendorId
                && device.getProductId() == productId;
    }

    private void showSmartConfigDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_smart_config, null, false);
        EditText ssidView = dialogView.findViewById(R.id.smart_config_ssid);
        EditText passwordView = dialogView.findViewById(R.id.smart_config_password);
        String lastSsid = SmartConfigSessionState.getLastSsid();
        String lastPassword = SmartConfigSessionState.getLastPassword();
        if (lastSsid != null) {
            ssidView.setText(lastSsid);
            ssidView.setSelection(lastSsid.length());
        }
        if (lastPassword != null) {
            passwordView.setText(lastPassword);
            passwordView.setSelection(lastPassword.length());
        }

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
                status(getString(R.string.status_smart_config_ssid_required));
                ssidView.requestFocus();
                return;
            }
            SmartConfigSessionState.setLastSsid(inputSsid);
            SmartConfigSessionState.setLastPassword(password);
            dialog.dismiss();
            startSmartConfig(inputSsid, password);
        }));
        dialog.show();
    }

    private void startSmartConfig(String ssid, String password) {
        if (smartConfigInProgress) {
            status(getString(R.string.status_smart_config_busy));
            return;
        }
        smartConfigInProgress = true;
        smartConfigCancelledByUser = false;
        smartConfigCompletionHandled.set(false);
        requireActivity().invalidateOptionsMenu();
        status(getString(R.string.status_smart_config_starting, ssid));
        status(getString(R.string.status_smart_config_running));
        showSmartConfigProgressDialog();
        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> runSmartConfig(appContext, ssid, SMART_CONFIG_BSSID, password), "esp-smart-config").start();
    }

    private void runSmartConfig(Context context, String ssid, String bssid, String password) {
        try {
            IEsptouchTask task = new EsptouchTask(ssid, bssid, password, context);
            task.setPackageBroadcast(true);
            task.setEsptouchListener(result -> {
                if (hasSmartConfigAck(result)) {
                    mainLooper.post(() -> finishSmartConfigIfPending(List.of(result), null));
                    task.interrupt();
                }
            });
            smartConfigTask = task;
            List<IEsptouchResult> results = task.executeForResults(1);
            mainLooper.post(() -> finishSmartConfigIfPending(results, null));
        } catch (Exception e) {
            mainLooper.post(() -> finishSmartConfigIfPending(null, e));
        } finally {
            smartConfigTask = null;
        }
    }

    private void finishSmartConfigIfPending(@Nullable List<IEsptouchResult> results, @Nullable Exception error) {
        if (!smartConfigCompletionHandled.compareAndSet(false, true)) {
            return;
        }
        finishSmartConfig(results, error);
    }

    private void finishSmartConfig(@Nullable List<IEsptouchResult> results, @Nullable Exception error) {
        dismissSmartConfigProgressDialog();
        smartConfigInProgress = false;
        if (!isAdded()) {
            return;
        }
        requireActivity().invalidateOptionsMenu();
        if (smartConfigCancelledByUser) {
            smartConfigCancelledByUser = false;
            return;
        }
        if (error != null) {
            String message = TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
            status(getString(R.string.status_smart_config_failed, message));
            return;
        }
        if (results == null || results.isEmpty()) {
            status(getString(R.string.status_smart_config_timeout));
            return;
        }
        IEsptouchResult result = results.get(0);
        if (result.isCancelled()) {
            status(getString(R.string.status_smart_config_cancelled));
            return;
        }
        if (!hasSmartConfigAck(result)) {
            status(getString(R.string.status_smart_config_timeout));
            return;
        }
        String ip = result.getInetAddress() == null ? "-" : result.getInetAddress().getHostAddress();
        String mac = TextUtils.isEmpty(result.getBssid()) ? "-" : result.getBssid();
        if (!TextUtils.isEmpty(ip) && !"-".equals(ip)) {
            SmartConfigSessionState.setLastTelnetHost(ip);
        }
        status(getString(R.string.status_smart_config_ack_success, mac, ip));
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
        smartConfigCancelledByUser = true;
        smartConfigCompletionHandled.set(true);
        smartConfigInProgress = false;
        dismissSmartConfigProgressDialog();
        if (isAdded()) {
            requireActivity().invalidateOptionsMenu();
        }
        IEsptouchTask task = smartConfigTask;
        if (task != null) {
            task.interrupt();
        }
    }

    private boolean isUsbConnection() {
        return !CONNECTION_TYPE_TELNET.equals(connectionType);
    }

    private void postStatus(String message) {
        mainLooper.post(() -> {
            if (isAdded()) {
                status(message);
            }
        });
    }

    private void send(String str) {
        send(str, true);
    }

    private void send(String str, boolean appendNewline) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (hexEnabled) {
            appendNewline = false;
        }
        String msg;
        byte[] data;
        if(hexEnabled) {
            byte[] payload = maybeAppendModbusCrc(TextUtil.fromHexString(str));
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, payload);
            if (appendNewline) {
                TextUtil.toHexString(sb, NEWLINE.getBytes());
            }
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
        } else {
            msg = str;
            data = appendNewline ? (str + NEWLINE).getBytes(StandardCharsets.UTF_8) : str.getBytes(StandardCharsets.UTF_8);
        }
        try {
            if (!characterMode || hexEnabled) {
                appendLocalEcho(hexEnabled ? msg + '\n' : new String(data, StandardCharsets.UTF_8));
            }
            appendCommunicationLog("TX", data);
            service.write(data);
        } catch (SerialTimeoutException e) { // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void sendAgain(byte[] data0, int offset) {
        updateSendBtn(SendButtonState.Busy);
        if (connected != Connected.True) {
            return;
        }
        byte[] data;
        if (offset == 0) {
            data = data0;
        } else {
            data = new byte[data0.length - offset];
            System.arraycopy(data0, offset, data, 0, data.length);
        }
        try {
            service.write(data);
        } catch (SerialTimeoutException e) {
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
            return;
        } catch (Exception e) {
            onSerialIoError(e);
        }
        updateSendBtn(SendButtonState.Idle);
    }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            appendCommunicationLog("RX", data);
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data, StandardCharsets.UTF_8);
                if (NEWLINE.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                if (ansiRenderer != null) {
                    appendAnsiText(spn, msg);
                } else {
                    spn.append(TextUtil.toCaretString(msg, true));
                }
            }
        }
        if (spn.length() > 0) {
            receiveText.append(spn);
            trimReceiveText();
        }
    }

    private void appendLocalEcho(String text) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        if (hexEnabled) {
            spn.append(text);
        } else if (ansiRenderer != null) {
            appendAnsiText(spn, text);
        } else {
            spn.append(TextUtil.toCaretString(text, true));
        }
        if (spn.length() > 0) {
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                    0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            trimReceiveText();
        }
    }

    private void appendAnsiText(SpannableStringBuilder target, String msg) {
        Editable existing = receiveText.getEditableText();
        for (int i = 0; i < msg.length(); i++) {
            applyPendingCursorCommand(target, existing);
            char c = msg.charAt(i);
            if (c == '\r') {
                deleteToLineStart(target, existing);
                continue;
            }
            if (c == '\b') {
                if (target.length() > 0) {
                    target.delete(target.length() - 1, target.length());
                } else if (existing != null && existing.length() > 0) {
                    existing.delete(existing.length() - 1, existing.length());
                }
                continue;
            }
            ansiRenderer.appendTo(target, String.valueOf(c), true);
        }
        applyPendingCursorCommand(target, existing);
    }

    private void applyPendingCursorCommand(SpannableStringBuilder target, Editable existing) {
        if (pendingCursorColumn < 0) {
            return;
        }
        deleteToLineStart(target, existing);
        for (int i = 0; i < pendingCursorColumn; i++) {
            ansiRenderer.appendTo(target, " ", true);
        }
        pendingCursorColumn = -1;
    }

    private void deleteToLineStart(SpannableStringBuilder target, Editable existing) {
        int targetLineStart = findLineStart(target);
        if (targetLineStart < target.length()) {
            target.delete(targetLineStart, target.length());
            return;
        }
        if (existing == null || existing.length() == 0) {
            return;
        }
        int existingLineStart = findLineStart(existing);
        if (existingLineStart < existing.length()) {
            existing.delete(existingLineStart, existing.length());
        }
    }

    private byte[] maybeAppendModbusCrc(byte[] payload) {
        if (payload.length != 6) {
            return payload;
        }
        int function = payload[1] & 0xff;
        if (function != 0x03 && function != 0x06) {
            return payload;
        }
        int crc = calculateModbusCrc(payload);
        byte[] withCrc = Arrays.copyOf(payload, payload.length + 2);
        withCrc[payload.length] = (byte) (crc & 0xff);
        withCrc[payload.length + 1] = (byte) ((crc >> 8) & 0xff);
        return withCrc;
    }

    private int calculateModbusCrc(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= b & 0xff;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private int findLineStart(CharSequence text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
        trimReceiveText();
    }

    private void trimReceiveText() {
        Editable editable = getReceiveEditable();
        if (editable == null) {
            return;
        }
        int charCut = 0;
        if (editable.length() > MAX_RECEIVE_TEXT_LENGTH) {
            int trimTo = editable.length() - TRIM_RECEIVE_TEXT_TO;
            charCut = Math.min(trimTo, editable.length());
            while (charCut < editable.length() && editable.charAt(charCut) != '\n') {
                charCut++;
            }
            if (charCut < editable.length() && editable.charAt(charCut) == '\n') {
                charCut++;
            }
            if (charCut <= 0 || charCut > editable.length()) {
                charCut = trimTo;
            }
        }
        int lineCut = findTrimStartForRecentLines(editable, TRIM_RECEIVE_TEXT_TO_LINES);
        if (editable.length() <= MAX_RECEIVE_TEXT_LENGTH && lineCut == 0) {
            return;
        }
        int cut = Math.max(charCut, lineCut);
        cut = Math.min(cut, editable.length());
        if (cut <= 0) {
            return;
        }
        CharSequence tail = editable.subSequence(cut, editable.length());
        receiveText.setText(tail, TextView.BufferType.EDITABLE);
    }

    @Nullable
    private Editable getReceiveEditable() {
        Editable editable = receiveText.getEditableText();
        if (editable != null) {
            return editable;
        }
        CharSequence text = receiveText.getText();
        return text instanceof Editable ? (Editable) text : null;
    }

    private int findTrimStartForRecentLines(Editable editable, int keepLines) {
        int newlineCount = 0;
        int cut = 0;
        for (int i = editable.length() - 1; i >= 0; i--) {
            if (editable.charAt(i) != '\n') {
                continue;
            }
            newlineCount++;
            if (newlineCount >= keepLines) {
                cut = i + 1;
                break;
            }
        }
        if (newlineCount < MAX_RECEIVE_TEXT_LINES) {
            return 0;
        }
        return cut;
    }

    private void setCommunicationLogEnabled(boolean enabled) {
        commLogEnabled = enabled;
        SharedPreferences preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putBoolean(PREF_COMM_LOG_ENABLED, enabled).apply();
        if (enabled) {
            if (openCommunicationLog()) {
                status(getString(R.string.communication_log_enabled, commLogLocation));
            } else {
                commLogEnabled = false;
                preferences.edit().putBoolean(PREF_COMM_LOG_ENABLED, false).apply();
                status(getString(R.string.communication_log_enable_failed));
            }
        } else {
            closeCommunicationLog();
            status(getString(R.string.communication_log_disabled));
        }
    }

    private void setCommunicationLogHex(boolean enabled) {
        commLogHex = enabled;
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_COMM_LOG_HEX, enabled)
                .apply();
        if (commLogEnabled) {
            reopenCommunicationLog();
            status(getString(enabled ? R.string.communication_log_format_hex : R.string.communication_log_format_text));
        }
    }

    private boolean openCommunicationLog() {
        if (commLogWriter != null || commLogStream != null) {
            return true;
        }
        String fileName = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        commLogLocation = LogFiles.getLogLocation(requireContext(), fileName);
        try {
            if (LogFiles.usesPublicLogs()) {
                commLogUri = LogFiles.createLogUri(requireContext(), fileName);
                if (commLogUri == null) {
                    return false;
                }
                commLogStream = LogFiles.openLogStream(requireContext(), commLogUri, false);
            } else {
                File directory = LogFiles.getLogsDir(requireContext());
                if (directory == null) {
                    return false;
                }
                commLogFile = new File(directory, fileName);
                commLogLocation = commLogFile.getAbsolutePath();
                commLogStream = new FileOutputStream(commLogFile, true);
            }
            if (commLogHex) {
                commLogWriter = new BufferedWriter(new OutputStreamWriter(commLogStream, StandardCharsets.UTF_8));
                commLogWriter.write("# SimpleUsbTerminal communication log");
                commLogWriter.newLine();
                commLogWriter.write("# created=" + formatTimestamp(System.currentTimeMillis()));
                commLogWriter.newLine();
                commLogWriter.write("# baudRate=" + baudRate);
                commLogWriter.newLine();
            }
            return true;
        } catch (IOException e) {
            LogFiles.deleteLogUri(requireContext(), commLogUri);
            commLogFile = null;
            commLogUri = null;
            commLogLocation = null;
            commLogStream = null;
            commLogWriter = null;
            return false;
        }
    }

    private void reopenCommunicationLog() {
        if (!commLogEnabled) {
            return;
        }
        closeCommunicationLog();
        if (openCommunicationLog()) {
            status(getString(R.string.communication_log_enabled, commLogLocation));
        } else {
            commLogEnabled = false;
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_COMM_LOG_ENABLED, false)
                    .apply();
            status(getString(R.string.communication_log_enable_failed));
            requireActivity().invalidateOptionsMenu();
        }
    }

    private void closeCommunicationLog() {
        if (commLogWriter == null && commLogStream == null) {
            commLogFile = null;
            commLogUri = null;
            commLogLocation = null;
            return;
        }
        try {
            if (commLogWriter != null) {
                commLogWriter.flush();
                commLogWriter.close();
            } else if (commLogStream != null) {
                commLogStream.flush();
                commLogStream.close();
            }
        } catch (IOException ignored) {
        } finally {
            commLogStream = null;
            commLogWriter = null;
            commLogFile = null;
            commLogUri = null;
            commLogLocation = null;
        }
    }

    private void appendCommunicationLog(String direction, byte[] data) {
        if (!commLogEnabled) {
            return;
        }
        if (!openCommunicationLog()) {
            status(getString(R.string.communication_log_enable_failed));
            commLogEnabled = false;
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_COMM_LOG_ENABLED, false)
                    .apply();
            requireActivity().invalidateOptionsMenu();
            return;
        }
        try {
            if (!commLogHex) {
                if (!"RX".equals(direction) || commLogStream == null) {
                    return;
                }
                commLogStream.write(data);
                return;
            }
            StringBuilder line = new StringBuilder();
            line.append(formatTimestamp(System.currentTimeMillis()))
                    .append(' ')
                    .append(direction)
                    .append(" len=")
                    .append(data.length)
                    .append(' ');
            if (commLogHex) {
                line.append("hex=").append(TextUtil.toHexString(data));
            } else {
                String text = new String(data, StandardCharsets.UTF_8)
                        .replace("\r", "\\r")
                        .replace("\n", "\\n");
                line.append("text=").append(text);
            }
            commLogWriter.write(line.toString());
            commLogWriter.newLine();
        } catch (IOException e) {
            closeCommunicationLog();
            commLogEnabled = false;
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_COMM_LOG_ENABLED, false)
                    .apply();
            status(getString(R.string.communication_log_write_failed, e.getMessage()));
            requireActivity().invalidateOptionsMenu();
        }
    }

    private String formatTimestamp(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(timestamp));
    }

    void updateSendBtn(SendButtonState state) {
        sendBtn.setEnabled(state == SendButtonState.Idle);
        sendBtn.setImageAlpha(state == SendButtonState.Idle ? 255 : 64);
        sendBtn.setImageResource(state == SendButtonState.Disabled ? R.drawable.ic_block_white_24dp : R.drawable.ic_send_white_24dp);
    }

    private void updateInputModeUi() {
        if (sendText == null || sendBtn == null || sendPanel == null || characterInput == null) {
            return;
        }
            if (hexEnabled) {
            sendText.setHint(R.string.hint_hex_mode);
        } else if (characterMode) {
            characterInput.setHint(R.string.hint_character_mode);
            characterInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        } else {
            sendText.setHint("");
        }
        sendPanel.setVisibility(characterMode && !hexEnabled ? View.GONE : View.VISIBLE);
        characterInput.setVisibility(characterMode && !hexEnabled ? View.VISIBLE : View.GONE);
        if (characterMode && !hexEnabled) {
            if (characterInput.getText() != null && characterInput.getText().length() > 0) {
                Selection.setSelection(characterInput.getText(), characterInput.getText().length());
            }
            characterInput.requestFocus();
            showSoftKeyboard(characterInput);
        } else {
            characterInput.setText("");
            characterInput.clearFocus();
            hideSoftKeyboard(characterInput);
        }
    }

    private void toggleCharacterKeyboard() {
        if (characterKeyboardVisible) {
            characterInput.clearFocus();
            hideSoftKeyboard(characterInput);
        } else {
            characterInput.requestFocus();
            showSoftKeyboard(characterInput);
        }
    }

    private void showSoftKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            view.post(() -> {
                view.requestFocus();
                characterKeyboardVisible = true;
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private void hideSoftKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            characterKeyboardVisible = false;
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private String formatErrorMessage(@Nullable Exception e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        if (!TextUtils.isEmpty(message)) {
            return message;
        }
        return e.getClass().getSimpleName();
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status(getString(R.string.status_connected));
        connected = Connected.True;
        applyNoFlowControl();
        controlLines.start();
        updateKeepScreenOn(true);
        if (isAdded()) {
            requireActivity().invalidateOptionsMenu();
        }
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status(getString(R.string.status_connection_failed, formatErrorMessage(e)));
        disconnect(true);
    }

    @Override
    public void onSerialRead(byte[] data) {
        enqueueReceivedData(data);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        enqueueReceivedDataBatch(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status(getString(R.string.status_connection_lost, formatErrorMessage(e)));
        disconnect(true);
    }

    private void updateKeepScreenOn(boolean keepScreenOn) {
        if (terminalRoot != null) {
            terminalRoot.setKeepScreenOn(keepScreenOn);
        }
    }

    private void enqueueReceivedData(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        pendingReceiveQueue.add(data);
        pendingReceiveBytes += data.length;
        if (!receiveRenderScheduled) {
            receiveRenderScheduled = true;
            mainLooper.post(receiveRenderRunnable);
        }
    }

    private void enqueueReceivedDataBatch(ArrayDeque<byte[]> datas) {
        if (datas == null || datas.isEmpty()) {
            return;
        }
        int batchBytes = 0;
        int chunkCount = 0;
        for (byte[] data : datas) {
            if (data == null || data.length == 0) {
                continue;
            }
            batchBytes += data.length;
            chunkCount++;
        }
        if (chunkCount == 0) {
            return;
        }
        for (byte[] data : datas) {
            if (data == null || data.length == 0) {
                continue;
            }
            pendingReceiveQueue.add(data);
            pendingReceiveBytes += data.length;
        }
        if (!receiveRenderScheduled) {
            receiveRenderScheduled = true;
            mainLooper.post(receiveRenderRunnable);
        }
    }

    private void drainReceiveQueue() {
        receiveRenderScheduled = false;
        if (pendingReceiveQueue.isEmpty()) {
            return;
        }
        ArrayDeque<byte[]> batch = new ArrayDeque<>();
        int batchBytes = 0;
        while (!pendingReceiveQueue.isEmpty() && batchBytes < RECEIVE_RENDER_MAX_BYTES) {
            byte[] data = pendingReceiveQueue.removeFirst();
            batch.add(data);
            batchBytes += data.length;
            pendingReceiveBytes -= data.length;
        }
        receive(batch);
        if (!pendingReceiveQueue.isEmpty()) {
            receiveRenderScheduled = true;
            if (pendingReceiveBytes >= RECEIVE_IMMEDIATE_DRAIN_THRESHOLD) {
                mainLooper.post(receiveRenderRunnable);
            } else {
                mainLooper.postDelayed(receiveRenderRunnable, RECEIVE_RENDER_INTERVAL_MS);
            }
        }
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;

        private View frame;
        private ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        private boolean showControlLines;

        ControlLines() {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        void onCreateView(View view) {
            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        void onPrepareOptionsMenu(Menu menu) {
            try {
                EnumSet<UsbSerialPort.ControlLine> scl = usbSerialPort.getSupportedControlLines();
                menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty());
                menu.findItem(R.id.controlLines).setChecked(showControlLines);
            } catch (Exception ignored) {
            }
        }

        public boolean showControlLines(boolean show) {
            showControlLines = show;
            start();
            return showControlLines;
        }

        void start() {
            if (showControlLines) {
                try {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getSupportedControlLines();
                    rtsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RTS) ? View.VISIBLE : View.INVISIBLE);
                    ctsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CTS) ? View.VISIBLE : View.INVISIBLE);
                    dtrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DTR) ? View.VISIBLE : View.INVISIBLE);
                    dsrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DSR) ? View.VISIBLE : View.INVISIBLE);
                    cdBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CD)   ? View.VISIBLE : View.INVISIBLE);
                    riBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RI)   ? View.VISIBLE : View.INVISIBLE);
                } catch (IOException e) {
                    showControlLines = false;
                    status(getString(R.string.status_get_supported_control_lines_failed, e.getMessage()));
                }
            }
            frame.setVisibility(showControlLines ? View.VISIBLE : View.GONE);
            updateSendBtn(SendButtonState.Idle);

            mainLooper.removeCallbacks(runnable);
            if (showControlLines) {
                run();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            updateSendBtn(SendButtonState.Idle);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                if (showControlLines) {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                    if(rtsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setChecked(!rtsBtn.isChecked());
                    if(ctsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setChecked(!ctsBtn.isChecked());
                    if(dtrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setChecked(!dtrBtn.isChecked());
                    if(dsrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setChecked(!dsrBtn.isChecked());
                    if(cdBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.CD))  cdBtn.setChecked(!cdBtn.isChecked());
                    if(riBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.RI))  riBtn.setChecked(!riBtn.isChecked());
                }
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status(getString(R.string.status_get_control_lines_failed, e.getMessage()));
            }
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status(getString(R.string.status_set_control_line_failed, ctrl, e.getMessage()));
            }
        }

    }

}
