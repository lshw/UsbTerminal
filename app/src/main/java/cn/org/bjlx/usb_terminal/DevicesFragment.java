package cn.org.bjlx.usb_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.Locale;

public class DevicesFragment extends ListFragment {

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
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if(id == R.id.refresh) {
            refresh();
            return true;
        } else if (id ==R.id.baud_rate) {
            showCommunicationSettingsDialog();
            return true;
        } else if (id == R.id.openLatestLog) {
            ((MainActivity) requireActivity()).openLatestLog();
            return true;
        } else if (id == R.id.shareLatestLog) {
            ((MainActivity) requireActivity()).shareLatestLog();
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

}
