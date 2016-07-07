/*
 * Copyright (C) 2014 Microchip Technology Inc. and its subsidiaries. You may use this software and
 * any derivatives exclusively with Microchip products.
 * 
 * THIS SOFTWARE IS SUPPLIED BY MICROCHIP "AS IS". NO WARRANTIES, WHETHER EXPRESS, IMPLIED OR
 * STATUTORY, APPLY TO THIS SOFTWARE, INCLUDING ANY IMPLIED WARRANTIES OF NON-INFRINGEMENT,
 * MERCHANTABILITY, AND FITNESS FOR A PARTICULAR PURPOSE, OR ITS INTERACTION WITH MICROCHIP
 * PRODUCTS, COMBINATION WITH ANY OTHER PRODUCTS, OR USE IN ANY APPLICATION.
 * 
 * IN NO EVENT WILL MICROCHIP BE LIABLE FOR ANY INDIRECT, SPECIAL, PUNITIVE, INCIDENTAL OR
 * CONSEQUENTIAL LOSS, DAMAGE, COST OR EXPENSE OF ANY KIND WHATSOEVER RELATED TO THE SOFTWARE,
 * HOWEVER CAUSED, EVEN IF MICROCHIP HAS BEEN ADVISED OF THE POSSIBILITY OR THE DAMAGES ARE
 * FORESEEABLE. TO THE FULLEST EXTENT ALLOWED BY LAW, MICROCHIP'S TOTAL LIABILITY ON ALL CLAIMS IN
 * ANY WAY RELATED TO THIS SOFTWARE WILL NOT EXCEED THE AMOUNT OF FEES, IF ANY, THAT YOU HAVE PAID
 * DIRECTLY TO MICROCHIP FOR THIS SOFTWARE.
 * 
 * MICROCHIP PROVIDES THIS SOFTWARE CONDITIONALLY UPON YOUR ACCEPTANCE OF THESE TERMS.
 * 
 * Licensed under the Apache License, Version 2.0 (the "Licenseimport android.app.Activity; import
 * android.app.PendingIntent; import android.content.Context; import
 * android.hardware.usb.UsbConstants; import android.hardware.usb.UsbDevice; import
 * android.hardware.usb.UsbDeviceConnection; import android.hardware.usb.UsbEndpoint; import
 * android.hardware.usb.UsbInterface; import android.hardware.usb.UsbManager; import
 * android.hardware.usb.UsbRequest;
 * 
 * import java.nio.ByteBuffer; import java.util.HashMap; tations under the License.
 */

package com.microchip.android.microchipusb;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Class to handle low level USB communication with a MCP2210 device.
 */
public class MCP2210 {
    /** Microchip Product ID. */
    private static final int MCP2210_PID = 0xDE;
    /** Microchip Vendor ID. */
    private static final int MCP2210_VID = 0x4D8;
    /** USB HID packet size for the MCP2210. */
    private static final int PACKET_SIZE = 64;
    /** USB connection for the MCP2210. */
    private UsbDeviceConnection mMcp2210Connection;
    /** MCP2210 USB device. */
    private UsbDevice mMcp2210Device;
    /** USB OUT endpoint. Used for sending commands to the MCP2210 via the HID interface. */
    private UsbEndpoint mMcp2210EpOut;
    /** USB IN endpoint. Used for getting data from the MCP2210 via the HID interface. */
    private UsbEndpoint mMcp2210EpIn;
    /** MCP2210 HID interface reference. */
    private UsbInterface mMcp2210Interface;
    /** USB request used for queuing data to the OUT USB endpoint. */
    private final UsbRequest mMcp2210UsbOutRequest = new UsbRequest();
    /** USB request used for getting data from the IN USB endpoint queue. */
    private final UsbRequest mMcp2210UsbInRequest = new UsbRequest();
    /** UsbManager used to scan for connected MCP2221 devices and grant USB permission. */
    private final UsbManager mUsbManager;

    /**
     * Creates a new MCP2210.
     * 
     * @param receivedActivity
     *            (Activity)<br>
     *            A reference to the activity from which this constructor is called.
     */
    public MCP2210(final Activity receivedActivity) {
        super();
        mUsbManager = (UsbManager) receivedActivity.getSystemService(Context.USB_SERVICE);
    }

    /**
     * Close the communication with the MCP2210, release the USB interface <br>
     * and all resources related to the object.
     */
    public final void close() {
        if (mMcp2210Connection != null) {
            mMcp2210Connection.releaseInterface(mMcp2210Interface);
            mMcp2210Connection.close();
            mMcp2210Connection = null;
        }

    }

    /**
     * Open a connection to the MCP2210.
     * 
     * @return (Constants) Constants.SUCCESS if the connection was established
     *         Constants.ERROR_MESSAGE if the connection failed
     */
    public final Constants open() {

        HashMap<String, UsbDevice> deviceList;
        deviceList = mUsbManager.getDeviceList();

        for (String key : deviceList.keySet()) {
            mMcp2210Device = deviceList.get(key);
            if (mMcp2210Device.getVendorId() == MCP2210_VID
                    && mMcp2210Device.getProductId() == MCP2210_PID) {
                // found the MCP2210
                // Now go through the interfaces until we find the HID one
                for (int i = 0; i < mMcp2210Device.getInterfaceCount(); i++) {

                    mMcp2210Interface = mMcp2210Device.getInterface(i);

                    if (mMcp2210Interface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {

                        for (int j = 0; j < mMcp2210Interface.getEndpointCount(); j++) {
                            if (mMcp2210Interface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
                                // OUT usb endpoint found
                                mMcp2210EpOut = mMcp2210Interface.getEndpoint(j);
                            } else {
                                // IN usb endpoint found
                                mMcp2210EpIn = mMcp2210Interface.getEndpoint(j);
                            }
                        }
                        break;
                    }
                }

                // if the user granted USB permission try to open a connection
                if (mUsbManager.hasPermission(mMcp2210Device)) {
                    mMcp2210Connection = mUsbManager.openDevice(mMcp2210Device);
                } else {
                    return Constants.NO_USB_PERMISSION;
                }
                break;
            }
        }

        if (mMcp2210Connection == null) {
            // MCP2210 connection failed
            return Constants.CONNECTION_FAILED;
        } else {
            // MCP2210 connected
            return Constants.SUCCESS;
        }
    }

    /**
     * Request temporary USB permission for the connected MCP2210. <br>
     * Success or failure is returned via the PendingIntent permissionIntent.
     * 
     * @param permissionIntent
     *            (PendingIntent)<br>
     * 
     */
    public final void requestUsbPermission(final PendingIntent permissionIntent) {
        mUsbManager.requestPermission(mMcp2210Device, permissionIntent);
    }

    /**
     * Sends a command to the MCP2210 and retrieves the reply.
     * 
     * @param data
     *            (ByteBuffer) 64 bytes of data to be sent
     * @return (ByteBuffer) 64 bytes of data received as a response from the MCP2210 <br>
     *         null - if the transaction wasn't successful
     * 
     */

    public final ByteBuffer sendData(final ByteBuffer data) {

        if (data.capacity() > PACKET_SIZE) {
            // USB packet size is 64 bytes
            return null;
        }

        ByteBuffer usbCommand = ByteBuffer.allocate(PACKET_SIZE);
        usbCommand = data;

        // initialize USB out and in requests
        mMcp2210UsbOutRequest.initialize(mMcp2210Connection, mMcp2210EpOut);

        mMcp2210UsbInRequest.initialize(mMcp2210Connection, mMcp2210EpIn);

        mMcp2210Connection.claimInterface(mMcp2210Interface, true);

        mMcp2210UsbOutRequest.queue(usbCommand, PACKET_SIZE);
        if (mMcp2210Connection.requestWait() == null) {
            // an error has occurred
            return null;
        }

        ByteBuffer usbResponse = ByteBuffer.allocate(PACKET_SIZE);
        mMcp2210UsbInRequest.queue(usbResponse, PACKET_SIZE);
        if (mMcp2210Connection.requestWait() == null) {
            // an error has occurred
            return null;
        }

        return usbResponse;
    }
}
