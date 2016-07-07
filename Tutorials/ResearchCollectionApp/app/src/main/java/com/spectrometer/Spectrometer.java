/**
 * Created by craig on 3/14/16.
 * (very) heavily based off of Microchip's MCP2221 code
 */
package com.spectrometer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Handler;
import android.util.Log;

import com.androidplot.xy.SimpleXYSeries;
import com.microchip.android.microchipusb.Constants; // should really find a way to replace this

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;


public class Spectrometer {
    /** Spectrometer Product ID. */
    private static final int SPEC_PID = 0x4200;
    /** Spectrometer Vendor ID. */
    public static final int SPEC_VID = 0x2457;
    /** USB HID packet size for the MCP2221. */
    private static final int HID_PACKET_SIZE = 64;

    /** USB connection for the spectrometer. */
    private UsbDeviceConnection mSpecConnection;
    /** Spectrometer USB device. */
    private UsbDevice mSpecDevice;
    /** USB HID OUT endpoint. Used for sending commands to the Spectrometer via the HID interface. */
    private UsbEndpoint mSpecEpOut;
    /** USB HID IN endpoint. Used for getting data from the Spectrometer via the HID interface. */
    private UsbEndpoint mSpecEpIn;
    /** Spectrometer HID interface reference. */
    private UsbInterface mSpecInterface;
    /** USB request used for queuing data to the OUT USB endpoint. */
    private final UsbRequest mSpecUsbOutRequest = new UsbRequest();
    /** USB request used for getting data from the IN USB endpoint queue. */
    private final UsbRequest mSpecUsbInRequest = new UsbRequest();
    /** UsbManager used to scan for connected Spectrometer devices and grant USB permission. */
    private final UsbManager mUsbManager;


    public final byte[] specSetIntegrationTimeCommand = {
            // Set integration time (10,000 uSec = 10ms)
            (byte) 0xC1, (byte) 0xC0, //start bytes
            0x00, 0x10, // protocol version
            0x00, 0x00, // flags
            0x00, 0x00, // error number
            0x10, 0x00, 0x11, 0x00, // message type
            0x00, 0x00, 0x00, 0x00, // regarding (user-specified)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Reserved
            0x00, // checksum type
            0x04, // immediate length
            0x10, 0x27, 0x00, 0x00, // integration time (immediate data)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // unused (remainder of immediate data)
            0x14, 0x00, 0x00, 0x00 ,// bytes remaining
            // optional payload
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // checksum
            (byte) 0xC5, (byte) 0xC4,(byte)  0xC3,(byte)  0xC2 // footer
    };
    public final byte[] specSetBinFactorCommand = {
            (byte) 0xC1, (byte) 0xC0, //start bytes
            0x00, 0x10, // protocol version
            0x00, 0x00, // flags
            0x00, 0x00, // error number
            (byte) 0x90, 0x02, 0x11, 0x00, // message type
            0x00, 0x00, 0x00, 0x00, // regarding (user-specified)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Reserved
            0x00, // checksum type
            0x01, // immediate length
            0x00, 0x00, 0x00, 0x00, // binning mode (0) was too lazy to move the next three bytes to next line (immediate data)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // unused (remainder of immediate data)
            0x14, 0x00, 0x00, 0x00 ,// bytes remaining
            // optional payload
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // checksum
            (byte) 0xC5, (byte) 0xC4,(byte)  0xC3,(byte)  0xC2 // footer
    };
    final byte[] specGetSpectrum = {
            (byte) 0xC1, (byte) 0xC0, //start bytes
            0x00, 0x10, // protocol version
            0x00, 0x00, // flags
            0x00, 0x00, // error number
            0x00, 0x10, 0x10, 0x00, // message type
            0x00, 0x00, 0x00, 0x00, // regarding (user-specified)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Reserved
            0x00, // checksum type
            0x00, // immediate length
            0x00, 0x00, 0x00, 0x00, // unused (immediate data)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // unused (remainder of immediate data)
            0x14, 0x00, 0x00, 0x00,// bytes remaining
            // optional payload
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // checksum
            (byte) 0xC5, (byte) 0xC4, (byte) 0xC3, (byte) 0xC2 // footer
    };
    final ByteBuffer getSpec = ByteBuffer.wrap(specGetSpectrum);
    protected static final String TAG = "Spectrometer";

    /**
     * Create a new MCP2221.
     *
     * @param receivedActivity
     *            (Activity)<br>
     *            A reference to the activity from which this constructor is called.
     */
    public Spectrometer(final Activity receivedActivity) {
        super();
        mUsbManager = (UsbManager) receivedActivity.getSystemService(Context.USB_SERVICE);

    }


    /**
     * Close the communication with the MCP2221, release the USB interface <br>
     * and all resources related to the object.
     */
    public final void close() {
        if (mSpecConnection != null) {
            mSpecConnection.releaseInterface(mSpecInterface);
            mSpecConnection.close();
            mSpecConnection = null;
        }
    }

    /**
     * Open a connection to the MCP2221.
     *
     * @return (Constants) Constants.SUCCESS if the connection was established
     *         Constants.ERROR_MESSAGE if the connection failed
     */
    public final Constants open() {
        Log.d("Spectrometer", "spectrometer open function");
        HashMap<String, UsbDevice> deviceList;
        deviceList = mUsbManager.getDeviceList();

        UsbInterface tempInterface;
        //PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent("com.android.example.USB_PERMISSION"), 0);

        for (String key : deviceList.keySet()) {
            mSpecDevice = deviceList.get(key);

            if (mSpecDevice.getVendorId() == SPEC_VID
                    && mSpecDevice.getProductId() == SPEC_PID) {
                // we found the spectrometer
                // Now go through the interfaces until we find the vendor specified one, should be the 0th
                for (int i = 0; i < mSpecDevice.getInterfaceCount(); i++) {
                    tempInterface = mSpecDevice.getInterface(i);

                    UsbEndpoint ep0 = tempInterface.getEndpoint(0);
                    UsbEndpoint ep1 = tempInterface.getEndpoint(1);
                    if (ep0.getDirection() == UsbConstants.USB_DIR_IN) {
                        mSpecEpIn = ep0;
                        mSpecEpOut = ep1;

                    } else {
                        mSpecEpIn = ep1;
                        mSpecEpOut = ep0;

                    }
                    break;
                    // why doesn't this work?  ¯\_(ツ)_/¯

//                    if (tempInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
//                        // we found the interface
//                        mSpecInterface = tempInterface;
//                        for (int j = 0; j < mSpecInterface.getEndpointCount(); j++) {
//                            if (mSpecInterface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
//                                // found the OUT USB endpoint
//                                mSpecEpOut = mSpecInterface.getEndpoint(j);
//                            } else {
//                                // found the IN USB endpoint
//                                mSpecEpIn = mSpecInterface.getEndpoint(j);
//                            }
//                        }
//                        break;
//                    }
                }
                // if the user granted USB permission
                // try to open a connection
                //mUsbManager.requestPermission(mSpecDevice, mPermissionIntent);
                if (mUsbManager.hasPermission(mSpecDevice)) {
                    mSpecConnection = mUsbManager.openDevice(mSpecDevice);
                } else {
                    Log.d("Spectrometer","no permission" );
                    return Constants.NO_USB_PERMISSION;
                }
                break;
            }
        }
        if (mSpecConnection == null) {
            return Constants.CONNECTION_FAILED;
        } else {
            if (setupSpectrometer()){
                return Constants.SUCCESS;
            }
            return Constants.CONNECTION_FAILED;
        }
    }

    /**
     * Request temporary USB permission for the connected Spec. <br>
     * Success or failure is returned via the PendingIntent permissionIntent.
     *
     * @param permissionIntent
     *            (PendingIntent) <br>
     *
     */
    public final void requestUsbPermission(final PendingIntent permissionIntent) {
        mUsbManager.requestPermission(mSpecDevice, permissionIntent);
    }

    /**
     * Sends an HID command to the Spec and retrieves the reply.
     *
     * @param usbCommand
     *            (ByteBuffer) 64 bytes of data to be sent
     * @return (ByteBuffer) 64 bytes of data received as a response from the Spec <br>
     *         null - if the transaction wasn't successful
     *
     */
    public final ByteBuffer sendData(final ByteBuffer usbCommand, int outSize, int inSize) {



        mSpecUsbOutRequest.initialize(mSpecConnection, mSpecEpOut);
        mSpecUsbInRequest.initialize(mSpecConnection, mSpecEpIn);
        mSpecConnection.claimInterface(mSpecInterface, true);

        // queue the USB command
        mSpecUsbOutRequest.queue(usbCommand, outSize);
        if (mSpecConnection.requestWait() == null) {
            // an error has occurred
            return null;
        }

        ByteBuffer usbResponse = ByteBuffer.allocate(inSize);
        mSpecUsbInRequest.queue(usbResponse, inSize);
        if (mSpecConnection.requestWait() == null) {
            // an error has occurred
            return null;
        }
        return usbResponse;

    }

    public byte[] captureSpectrum(){
        byte[] spec = new byte[2112];
        if (mSpecConnection != null) {
            int out = mSpecConnection.bulkTransfer(mSpecEpOut, specGetSpectrum, 64, 1000);
            int in = mSpecConnection.bulkTransfer(mSpecEpIn, spec, 2112, 1000);

            Log.d("spectro", "Out: " + out + "In: " + in);
        }
        return spec;
    }

    private boolean setupSpectrometer() {
        if (mSpecConnection != null) {
            mSpecConnection.bulkTransfer(mSpecEpOut, specSetIntegrationTimeCommand, 64, 1000);
            mSpecConnection.bulkTransfer(mSpecEpOut, specSetBinFactorCommand, 64, 1000);
            return true;
        }
        return false;
    }
}
