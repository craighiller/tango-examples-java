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
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
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
 * Class to handle low level USB communication with a MCP2200 device.
 */
public class MCP2200 {
    /** Microchip Product ID. */
    private static final int MCP2200_PID = 0xDF;
    /** Microchip Vendor ID. */
    private static final int MCP2200_VID = 0x4D8;
    /** USB HID packet size for the MCP2200. */
    private static final int PACKET_SIZE = 16;
    /** USB connection for the MCP2200. */
    private UsbDeviceConnection mMcp2200Connection;
    /** MCP2200 USB device. */
    private UsbDevice mMcp2200Device;
    /** USB OUT endpoint. Used for sending commands to the MCP2200 via the HID interface. */
    private UsbEndpoint mMcp2200EpOut;
    /** USB IN endpoint. Used for getting data from the MCP2200 via the HID interface. */
    private UsbEndpoint mMcp2200EpIn;
    /** MCP2200 HID interface reference. */
    private UsbInterface mMcp2200Interface;
    /** USB request used for queuing data to the OUT USB endpoint. */
    private final UsbRequest mMcp2200UsbOutRequest = new UsbRequest();
    /** USB request used for getting data from the IN USB endpoint queue. */
    private final UsbRequest mMcp2200UsbInRequest = new UsbRequest();
    /** UsbManager used to scan for connected MCP2200 devices and grant USB permission. */
    private final UsbManager mUsbManager;

    /**
     * Create a new MCP2200.
     * 
     * @param receivedActivity
     *            (Activity) - A reference to the activity from which this constructor is called.
     */
    public MCP2200(final Activity receivedActivity) {
        super();
        // this.activity = receivedActivity;
        mUsbManager = (UsbManager) receivedActivity.getSystemService(Context.USB_SERVICE);
    }

    /**
     * Close the communication with the MCP2200, release the USB interface <br>
     * and all resources related to the object.
     */
    public final void close() {
        if (mMcp2200Connection != null) {
            mMcp2200Connection.releaseInterface(mMcp2200Interface);
            mMcp2200Connection.close();
            mMcp2200Connection = null;
        }
    }

    /**
     * Open a connection to the MCP2200.
     * 
     * @return (Constants) Constants.SUCCESS if the connection was established
     *         Constants.ERROR_MESSAGE if the connection failed
     */
    public final Constants open() {

        HashMap<String, UsbDevice> deviceList;
        deviceList = mUsbManager.getDeviceList();

        for (String key : deviceList.keySet()) {
            mMcp2200Device = deviceList.get(key);
            if (mMcp2200Device.getVendorId() == MCP2200_VID
                    && mMcp2200Device.getProductId() == MCP2200_PID) {
                // MCP2200 found
                // go through the interfaces until we find the HID one
                for (int i = 0; i < mMcp2200Device.getInterfaceCount(); i++) {
                    mMcp2200Interface = mMcp2200Device.getInterface(i);

                    if (mMcp2200Interface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {

                        for (int j = 0; j < mMcp2200Interface.getEndpointCount(); j++) {

                            if (mMcp2200Interface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
                                // OUT usb endpoint found
                                mMcp2200EpOut = mMcp2200Interface.getEndpoint(j);
                            } else {
                                // IN usb endpoint found
                                mMcp2200EpIn = mMcp2200Interface.getEndpoint(j);
                            }
                        }
                        break;
                    }
                }
                // if we have USB permission try to open a connection
                if (mUsbManager.hasPermission(mMcp2200Device)) {
                    mMcp2200Connection = mUsbManager.openDevice(mMcp2200Device);
                } else {
                    return Constants.NO_USB_PERMISSION;
                }
                break;
            }
        }

        if (mMcp2200Connection == null) {
            // MCP2200 connection failed
            return Constants.CONNECTION_FAILED;
        } else {
            // MCP2200 connected
            return Constants.SUCCESS;
        }
    }

    /**
     * Request temporary USB permission for the connected MCP2200. <br>
     * Success or failure is returned via the PendingIntent permissionIntent.
     * 
     * @param permissionIntent
     *            (PendingIntent)<br>
     * 
     */
    public final void requestUsbPermission(final PendingIntent permissionIntent) {
        mUsbManager.requestPermission(mMcp2200Device, permissionIntent);
    }

    /**
     * Sends a command to the MCP2200 and retrieves the reply.
     * 
     * @param data
     *            (ByteBuffer) 16 bytes of data to be sent
     * @return (ByteBuffer) 16 bytes of data received as a response from the MCP2200 <br>
     *         null - if the transaction wasn't successful
     * 
     */

    public final ByteBuffer sendData(final ByteBuffer data) {

        if (data.capacity() > PACKET_SIZE) {
            // USB packet size is 16 bytes
            return null;
        }
        ByteBuffer usbCommand = ByteBuffer.allocate(PACKET_SIZE);
        usbCommand = data;

        // initialize usb OUT and IN requests
        mMcp2200UsbOutRequest.initialize(mMcp2200Connection, mMcp2200EpOut);
        mMcp2200UsbInRequest.initialize(mMcp2200Connection, mMcp2200EpIn);

        mMcp2200Connection.claimInterface(mMcp2200Interface, true);

        mMcp2200UsbOutRequest.queue(usbCommand, PACKET_SIZE);
        if (mMcp2200Connection.requestWait() == null) {
            // an error has occurred
            return null;
        }
        ByteBuffer usbResponse = ByteBuffer.allocate(PACKET_SIZE);
        mMcp2200UsbInRequest.queue(usbResponse, PACKET_SIZE);
        if (mMcp2200Connection.requestWait() == null) {
            // an error has occurred
            return null;
        }
        return usbResponse;

    }
}
