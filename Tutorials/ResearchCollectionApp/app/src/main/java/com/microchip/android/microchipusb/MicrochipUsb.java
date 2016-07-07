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
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.HashMap;

/**
 * General class used to detect what Microchip device was connected.
 */
public final class MicrochipUsb {
    /** UsbManager used to scan for connected MCP22xx devices. */
    private static UsbManager sUsbManager;
    /** Microchip Vendor ID. */
    private static final int MICROCHIP_VID = 0x4D8;
    /** Microchip MCP2200 Product ID. */
    private static final int MCP2200_PID = 0xDF;
    /** Microchip MCP2221 Product ID. */
    private static final int MCP2221_PID = 0xDD;
    /** Microchip MCP2210 Product ID. */
    private static final int MCP2210_PID = 0xDE;

    /** Private constructor. Does not initialize anything. */
    private MicrochipUsb() {

    }

    /**
     * Returns the name of the connected Microchip USB device.
     * 
     * @param receivedActivity
     *            (Activity) A reference to the activity from which this function is called.
     * @return (Constants) A constant representing the connected device. <br>
     *         Possible values: MCP2200, MCP2210, MCP2221, UNKNOWN_DEVICE, NO_DEVICE_CONNECTED
     */
    public static Constants getConnectedDevice(final Activity receivedActivity) {

        HashMap<String, UsbDevice> deviceList;

        sUsbManager = (UsbManager) receivedActivity.getSystemService(Context.USB_SERVICE);

        deviceList = sUsbManager.getDeviceList();

        for (String key : deviceList.keySet()) {
            if (deviceList.get(key).getVendorId() == MICROCHIP_VID) {

                switch (deviceList.get(key).getProductId()) {
                    case MCP2200_PID:
                        return Constants.MCP2200;
                    case MCP2210_PID:
                        return Constants.MCP2210;
                    case MCP2221_PID:
                        return Constants.MCP2221;
                    default:
                        return Constants.UNKNOWN_DEVICE;
                }
            }
        }
        return Constants.NO_DEVICE_CONNECTED;
    }
}
