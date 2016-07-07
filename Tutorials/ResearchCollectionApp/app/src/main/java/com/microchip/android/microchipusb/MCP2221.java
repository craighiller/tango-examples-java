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
import android.os.Handler;

import java.lang.Thread.State;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Class to handle low level USB communication with a MCP2221 device.
 */
public class MCP2221 implements Runnable {
    /** Microchip Product ID. */
    private static final int MCP2221_PID = 0xDD;
    /** Microchip Vendor ID. */
    public static final int MCP2221_VID = 0x4D8;
    /** USB HID packet size for the MCP2221. */
    private static final int HID_PACKET_SIZE = 64;
    /** USB CDC packet size for the MCP2221. */
    private static final int CDC_PACKET_SIZE = 16;
    /** flag used to stop the COM thread. */
    private static boolean comThreadRunning;
    /** USB connection for the MCP2221. */
    private UsbDeviceConnection mMcp2221Connection;
    /** MCP2221 USB device. */
    private UsbDevice mMcp2221Device;
    /** USB HID OUT endpoint. Used for sending commands to the MCP2221 via the HID interface. */
    private UsbEndpoint mMcp2221HidEpOut;
    /** USB HID IN endpoint. Used for getting data from the MCP2221 via the HID interface. */
    private UsbEndpoint mMcp2221HidEpIn;
    /** MCP2221 HID interface reference. */
    private UsbInterface mMcp2221HidInterface;
    /** USB request used for queuing data to the OUT USB endpoint. */
    private final UsbRequest mMcp2221UsbOutRequest = new UsbRequest();
    /** USB request used for getting data from the IN USB endpoint queue. */
    private final UsbRequest mMcp2221UsbInRequest = new UsbRequest();
    /** UsbManager used to scan for connected MCP2221 devices and grant USB permission. */
    private final UsbManager mUsbManager;
    /** USB CDC OUT endpoint. Used for sending commands to the MCP2221 via the CDC interface. */
    private UsbEndpoint mMcp2221CdcEpOut;
    /** USB CDC IN endpoint. Used for getting data from the MCP2221 via the CDC interface. */
    private UsbEndpoint mMcp2221CdcEpIn;
    /** MCP2221 CDC interface reference. */
    private UsbInterface mMcp2221CdcInterface;
    /** Handler to pass CDC messages to the calling activity. */
    private Handler mHandler;
    /** Used in the CDC thread to send the TX message. */
    private String toSend = new String();
    /** Thread used for CDC communication (COM port). */
    private Thread comThread;

    protected static final String TAG = "MCP2221";

    /**
     * Create a new MCP2221.
     * 
     * @param receivedActivity
     *            (Activity)<br>
     *            A reference to the activity from which this constructor is called.
     */
    public MCP2221(final Activity receivedActivity) {
        super();
        mUsbManager = (UsbManager) receivedActivity.getSystemService(Context.USB_SERVICE);

    }

    /**
     * Set the handler which will send/receive the CDC messages.
     * 
     * @param handler
     *            (Handler) - Handler to pass CDC messages from the COM thread to the calling
     *            activity
     */
    public final void setHandler(final Handler handler) {
        this.mHandler = handler;
    }

    /**
     * Close the communication with the MCP2221, release the USB interface <br>
     * and all resources related to the object.
     */
    public final void close() {
        if (mMcp2221Connection != null) {
            mMcp2221Connection.releaseInterface(mMcp2221HidInterface);

            if (isComOpen()) {
                closeCOM();
            }
            mMcp2221Connection.releaseInterface(mMcp2221CdcInterface);

            mMcp2221Connection.close();
            mMcp2221Connection = null;
        }
    }

    /**
     * Open a connection to the MCP2221.
     * 
     * @return (Constants) Constants.SUCCESS if the connection was established
     *         Constants.ERROR_MESSAGE if the connection failed
     */
    public final Constants open() {

        HashMap<String, UsbDevice> deviceList;
        deviceList = mUsbManager.getDeviceList();

        UsbInterface tempInterface;

        for (String key : deviceList.keySet()) {
            mMcp2221Device = deviceList.get(key);

            if (mMcp2221Device.getVendorId() == MCP2221_VID
                    && mMcp2221Device.getProductId() == MCP2221_PID) {
                // we found the MCP2221
                // Now go through the interfaces until we find the HID one
                for (int i = 0; i < mMcp2221Device.getInterfaceCount(); i++) {

                    tempInterface = mMcp2221Device.getInterface(i);

                    if (tempInterface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                        // we found the HID interface
                        mMcp2221HidInterface = tempInterface;
                        for (int j = 0; j < mMcp2221HidInterface.getEndpointCount(); j++) {
                            if (mMcp2221HidInterface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
                                // found the OUT USB endpoint
                                mMcp2221HidEpOut = mMcp2221HidInterface.getEndpoint(j);
                            } else {
                                // found the IN USB endpoint
                                mMcp2221HidEpIn = mMcp2221HidInterface.getEndpoint(j);
                            }
                        }
                        // break;
                    } else if (tempInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                        // we found the CDC interface
                        mMcp2221CdcInterface = tempInterface;
                        for (int j = 0; j < mMcp2221CdcInterface.getEndpointCount(); j++) {
                            if (mMcp2221CdcInterface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
                                // found the OUT USB endpoint
                                mMcp2221CdcEpOut = mMcp2221CdcInterface.getEndpoint(j);
                            } else {
                                // found the IN USB endpoint
                                mMcp2221CdcEpIn = mMcp2221CdcInterface.getEndpoint(j);
                            }
                        }
                    }
                }
                // if the user granted USB permission
                // try to open a connection
                if (mUsbManager.hasPermission(mMcp2221Device)) {
                    mMcp2221Connection = mUsbManager.openDevice(mMcp2221Device);
                } else {
                    return Constants.NO_USB_PERMISSION;
                }
                break;
            }
        }
        if (mMcp2221Connection == null) {
            return Constants.CONNECTION_FAILED;
        } else {
            // create the COM thread
            comThread = new Thread(this);

            // but don't start it yet (this will open the COM port)
            // comThread.start();
            return Constants.SUCCESS;
        }
    }

    /**
     * Request temporary USB permission for the connected MCP2221. <br>
     * Success or failure is returned via the PendingIntent permissionIntent.
     * 
     * @param permissionIntent
     *            (PendingIntent) <br>
     * 
     */
    public final void requestUsbPermission(final PendingIntent permissionIntent) {
        mUsbManager.requestPermission(mMcp2221Device, permissionIntent);
    }

    /**
     * Sends an HID command to the MCP2221 and retrieves the reply.
     * 
     * @param data
     *            (ByteBuffer) 64 bytes of data to be sent
     * @return (ByteBuffer) 64 bytes of data received as a response from the MCP2221 <br>
     *         null - if the transaction wasn't successful
     * 
     */
    public final ByteBuffer sendData(final ByteBuffer data) {

        if (data.capacity() > HID_PACKET_SIZE) {
            // USB packet size is 64 bytes
            return null;
        }

        ByteBuffer usbCommand = ByteBuffer.allocate(HID_PACKET_SIZE);
        usbCommand = data;

        mMcp2221UsbOutRequest.initialize(mMcp2221Connection, mMcp2221HidEpOut);
        mMcp2221UsbInRequest.initialize(mMcp2221Connection, mMcp2221HidEpIn);
        mMcp2221Connection.claimInterface(mMcp2221HidInterface, true);

        // queue the USB command
        mMcp2221UsbOutRequest.queue(usbCommand, HID_PACKET_SIZE);
        if (mMcp2221Connection.requestWait() == null) {
            // an error has occurred
            return null;
        }

        ByteBuffer usbResponse = ByteBuffer.allocate(HID_PACKET_SIZE);
        mMcp2221UsbInRequest.queue(usbResponse, HID_PACKET_SIZE);
        if (mMcp2221Connection.requestWait() == null) {
            // an error has occurred
            return null;
        }
        return usbResponse;

    }

    // CDC send method
    /**
     * Handles COM port RX and TX.
     */
    @Override
    public final void run() {
        byte[] rxData = new byte[CDC_PACKET_SIZE];
        // byte[] rxData = new byte[64];
        int result = 0;
        byte[] txData = null;

        while (true) {

            if (mMcp2221Connection == null) {
                return;
            }

            // if there was a request to close the thread
            if (comThreadRunning == false) {
                // clean up and exit
                mMcp2221Connection.releaseInterface(mMcp2221CdcInterface);
                comThread = null;
                return;
            }

            /* Sleep the thread for a while */
            try {
                Thread.sleep(0, 8500);
            } catch (InterruptedException e) {
            }

            mMcp2221Connection.claimInterface(mMcp2221CdcInterface, true);

            /* Send the data */
            synchronized (toSend) {
                if (toSend.length() > 0 && txData == null) {
                    txData = toSend.getBytes();
                    toSend = new String();
                }
            }

            if (txData != null) {
                result =
                        mMcp2221Connection.bulkTransfer(mMcp2221CdcEpOut, txData, txData.length,
                                100);

                if (result != 0) {
                    txData = null;
                }
            }

            /* Read any available data */
            // synchronized (rxData) {
            result = mMcp2221Connection.bulkTransfer(mMcp2221CdcEpIn, rxData, rxData.length, 100);
            /* If there was data successfully read,... */
            if (result > 0) {
                // return the data to the calling activity
                mHandler.obtainMessage(0, new String(rxData).substring(0, result)).sendToTarget();

            }
        }

    }

    /**
     * Sends data to the MCP2221 Serial Port (COM).
     * 
     * @param data
     *            (String) - the data that will be sent.
     */
    public void sendCdcData(String data) {
        synchronized (toSend) {
            toSend = toSend + data;
        }
    }

    /**
     * Starts the thread that handles the COM communication.<br>
     * <p>
     * <b>Preconditions:</b> <i>SetHandler</i> must be called before so the handler for the RX data
     * is initialized.
     * 
     * @return (boolean) - true if the COM thread was successfully started.
     */
    public final boolean openCOM() {
        if (mMcp2221Connection == null) {
            return false;
        } else {

            if (comThread == null) {
                comThread = new Thread(this);
                comThread.start();
                comThreadRunning = true;
                return true;
            }

            if (comThread.getState() == State.NEW) {
                comThread.start();
                comThreadRunning = true;
            }
            return true;
        }
    }

    /**
     * Close the COM port.
     */
    public final void closeCOM() {
        if (comThread.isAlive()) {
            comThread.interrupt();
            comThreadRunning = false;
        }

    }

    /**
     * Set the COM baud rate.
     * 
     * @param baudRate
     *            (int) - the desired baud rate. Supported values are between 300 and 115200
     * @return (boolean) - true if the baud rate was correctly set.
     */
    public final boolean setBaudRate(int baudRate) {
        byte[] lineCoding = new byte[0];
        // set the baud rate by sending the set_line_coding command
        // baud rate, 1 stop bit, parity none, 8 data bits
        lineCoding =
                new byte[] { (byte) (baudRate & 0xFF), (byte) ((baudRate >> 8) & 0xFF),
                        (byte) ((baudRate >> 16) & 0xFF), (byte) ((baudRate >> 24) & 0xFF),
                        (byte) 0x00, (byte) 0x00, (byte) 0x08 };
        if (mMcp2221Connection == null) {
            return false;
        }

        // claim the interface before sending the new baud rate
        mMcp2221Connection.claimInterface(mMcp2221CdcInterface, true);

        if (mMcp2221Connection.controlTransfer(0x21, 0x20, 0x0000, 0x0000, lineCoding,
                lineCoding.length, 20) >= 0) {
            // the baud rate was changed
            return true;
        }
        // there was an error
        return false;
    }

    /**
     * Get the COM baud rate.
     * 
     * @param baudRate
     *            (int) - the desired baud rate. Supported values are between 300 and 115200
     */
    /**
     * 
     * @return (int) - the baud rate currently configured on the mcp2221, negative value indicates
     *         an error.
     */
    public final int getBaudRate() {
        byte[] lineCoding = new byte[0];
        // get the baud rate by sending the get_line_coding command
        lineCoding = new byte[7];
        if (mMcp2221Connection == null) {
            return -10;
        }

        // claim the interface before sending the new baud rate
        mMcp2221Connection.claimInterface(mMcp2221CdcInterface, true);
        // get line coding
        if (mMcp2221Connection.controlTransfer(0xA1, 0x21, 0x0000, 0x0000, lineCoding,
                lineCoding.length, 20) >= 0) {

            // reassemble the received value for the baud rate
            int temp;
            int baudRate = lineCoding[3];
            baudRate = (baudRate << 24) & 0xffffffff;
            temp = lineCoding[2];
            temp = (temp << 16) & 0xffffff;
            baudRate = baudRate + temp;
            temp = lineCoding[1];
            temp = (temp << 8) & 0xffff;
            baudRate = baudRate + temp;
            baudRate = baudRate + (lineCoding[0] & 0xff);

            return baudRate;
        } else {
            // there was an error
            return -10;
        }
    }

    /**
     * Returns true if the receiver has already been started and still runs code (hasn't died yet).
     * Returns false either if the receiver hasn't been started yet or if it has already started and
     * run to completion and died.
     * 
     * @return (boolean) -a boolean indicating the liveness of the Thread
     */
    public final boolean isComOpen() {
        if (comThread == null) {
            return false;
        } else {
            return comThread.isAlive();
        }
    }

}
