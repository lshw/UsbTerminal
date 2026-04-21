package cn.org.bjlx.usb_terminal;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialProber;

/**
 * add devices here, that are not known to DefaultProber
 *
 * if the App should auto start for these devices, also
 * add IDs to app/src/main/res/xml/usb_device_filter.xml
 */
class CustomProber {

    private static final int VENDOR_QINHENG = 0x1a86;
    private static final int QINHENG_CH342 = 0x55d2;

    static UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x1234, 0xabcd, FtdiSerialDriver.class); // e.g. device with custom VID+PID
        // CH342/CH342F expose dual CDC ACM ports. Add an explicit VID/PID mapping as a
        // fallback so these devices can still be opened if interface-based probing misses them.
        customTable.addProduct(VENDOR_QINHENG, QINHENG_CH342, CdcAcmSerialDriver.class);
        return new UsbSerialProber(customTable);
    }

}
