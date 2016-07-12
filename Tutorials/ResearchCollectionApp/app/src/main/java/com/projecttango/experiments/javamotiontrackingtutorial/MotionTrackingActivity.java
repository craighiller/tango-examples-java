/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.experiments.javamotiontrackingtutorial;

import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYPlot;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.microchip.android.microchipusb.MicrochipUsb;
import com.projecttango.tangoutils.TangoPoseUtilities;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.rajawali3d.surface.IRajawaliSurface;
import org.rajawali3d.surface.RajawaliSurfaceView;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;


import com.microchip.android.mcp2221comm.Mcp2221Config;
import com.microchip.android.mcp2221comm.Mcp2221Comm;
import com.microchip.android.microchipusb.Constants;
import com.microchip.android.microchipusb.MCP2221;

import com.spectrometer.Spectrometer;


/**
 * Main Activity class for the Motion Tracking Rajawali Sample. Handles the connection to the Tango
 * service and propagation of Tango pose data to OpenGL and Layout views. OpenGL rendering logic is
 * delegated to the {@link MotionTrackingRajawaliRenderer} class.
 */
public class MotionTrackingActivity extends Activity  implements View.OnClickListener {
    private static final String TAG = MotionTrackingActivity.class.getSimpleName();
    private static final DecimalFormat FORMAT_THREE_DECIMAL = new DecimalFormat("0.000");
    private static final int SECS_TO_MILLISECS = 1000;
    private static final double UPDATE_INTERVAL_MS = 100.0f;

    private double mPreviousTimeStamp = 0.0;
    private int mPreviousPoseStatus = TangoPoseData.POSE_INVALID;
    private int mCount = 0;
    private double mTimeToNextUpdate = UPDATE_INTERVAL_MS;

    private Tango mTango;
    private TangoConfig mConfig;
    private MotionTrackingRajawaliRenderer mRenderer;

    private TextView mDeltaTextView;
    private TextView mPoseCountTextView;
    private TextView mPoseTextView;
    private TextView mQuatTextView;
    private TextView mPoseStatusTextView;
    private TextView mTangoServiceVersionTextView;
    private TextView mApplicationVersionTextView;
    private TextView mTangoEventTextView;
    private Button mMotionResetButton;
    private Button mConnectCamButton;
    private Button mConnectSpecButton;
    private Button mCollectButton;

    private TextView mLocalizationTextView;
    /** Microchip Product ID. */
    protected static final int MCP2221_PID = 0xDD;
    /** Microchip Vendor ID. */
    protected static final int MCP2221_VID = 0x4D8;
    /** Spectrometer Product ID. */
    protected static final int SPEC_PID = 0x4200;
    /** Spectrometer Vendor ID. */
    protected static final int SPEC_VID = 0x2457;
    /** Custom toast - displayed in the center of the screen. */
    private static Toast sToast;
    /** USB permission action for the USB broadcast receiver. */
    private static final String ACTION_USB_PERMISSION = "com.microchip.android.USB_PERMISSION";
    /** public member to be used in the test project. */
    public MCP2221 mcp2221;
    /** public member to be used in the test project. */
    public Mcp2221Comm mcp2221Comm;

    public Spectrometer spectrometer;
    PendingIntent mPermissionIntent;

    SimpleXYSeries series1 = null;
    private XYPlot plot;

    boolean shouldCollect=false;
    final Random randomGen= new Random();

    private DataOutputStream tangoPoseOutput;
    private DataOutputStream tangoDepthOutput;
    private DataOutputStream tangoCamOutput;
    private DataOutputStream tangoSpectroOutput;

    /*********************************************************
     * USB actions broadcast receiver.
     *********************************************************/
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    final UsbDevice device =
                            (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    // is usb permission has been granted, try to open a connection
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            // call method to set up device communication
                            final Constants result = mcp2221.open();

                            if (result != Constants.SUCCESS) {
                                sToast.setText("Could not open MCP2221 connection");
                                sToast.show();
                            } else {
                                mcp2221Comm = new Mcp2221Comm(mcp2221);
                                sToast.setText("MCP2221 connection opened");
                                sToast.show();


                            }
                        }
                    } else {
                        sToast.setText("USB Permission Denied");
                        sToast.show();
                    }
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                sToast.setText("Device Detached");
                sToast.show();
                // close the connection and
                // release all resources
                mcp2221.close();
                // leave a bit of time for the COM thread to close
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    // e.printStackTrace();
                }
                mcp2221Comm = null;
                // if the nav drawer isn't open change the action bar icon to show the device is
                // detached


            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                sToast.setText("Device Attached");
                sToast.show();
                final UsbDevice device =
                        (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {

                    // only try to connect if an MCP2221 is attached
                    if (device.getVendorId() == MCP2221_VID && device.getProductId() == MCP2221_PID) {
                        final Constants result = mcp2221.open();

                        switch (result) {
                            case SUCCESS:
                                sToast.setText("MCP2221 Connection Opened");
                                sToast.show();
                                mcp2221Comm = new Mcp2221Comm(mcp2221);


                                break;
                            case CONNECTION_FAILED:
                                sToast.setText("Connection Failed");
                                sToast.show();
                                break;
                            case NO_USB_PERMISSION:
                                mcp2221.requestUsbPermission(mPermissionIntent);
                                break;
                            default:
                                break;
                        }
                    }
                }
            }

        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_motion_tracking);
        mRenderer = setupGLViewAndRenderer();
        mPermissionIntent =
                PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        final IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

        registerReceiver(mUsbReceiver, filter);

        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(mUsbReceiver, filter);

        sToast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
        sToast.setGravity(Gravity.CENTER, 0, 0);

        Constants result = null;

        mcp2221 = new MCP2221(this);
        result = MicrochipUsb.getConnectedDevice(this);

        startActivityForResult(mTango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_ADF_LOAD_SAVE),mTango.TANGO_INTENT_ACTIVITYCODE);


        if (result == Constants.MCP2221) {
            // try to open a connection
            result = mcp2221.open();

            switch (result) {
                case SUCCESS:
                    sToast.setText("MCP2221 connected");
                    sToast.show();
                    mcp2221Comm = new Mcp2221Comm(mcp2221);
                    break;
                case CONNECTION_FAILED:
                    sToast.setText("Connection failed");
                    sToast.show();
                    break;
                case NO_USB_PERMISSION:
                    mcp2221.requestUsbPermission(mPermissionIntent);
                    break;
                default:
                    break;
            }
        }

        File root = new File(Environment.getExternalStorageDirectory()+File.separator+"AData/CraigData"+System.currentTimeMillis());
        try{
            if(root.mkdir()) {
                System.out.println("Directory created");
            } else {
                System.out.println("Directory is not created");
            }
        }catch(Exception e){
            e.printStackTrace();
        }
        File poutput = new File(root, "pose.dat");
        File doutput = new File(root, "depth.dat");
        File coutput = new File(root, "cam.dat");
        File soutput = new File(root, "spec.dat");
        Log.d("onCreate", "files setup");

        FileOutputStream pfos;
        FileOutputStream dfos;
        FileOutputStream cfos;
        FileOutputStream sfos;
        try {
            pfos = new FileOutputStream(poutput);
            tangoPoseOutput = new DataOutputStream(pfos);

            cfos = new FileOutputStream(coutput);
            tangoCamOutput = new DataOutputStream(cfos);

            dfos = new FileOutputStream(doutput);
            tangoDepthOutput = new DataOutputStream(dfos);

            sfos = new FileOutputStream(soutput);
            tangoSpectroOutput = new DataOutputStream(sfos);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MotionTrackingRajawaliRenderer setupGLViewAndRenderer() {
        MotionTrackingRajawaliRenderer renderer = new MotionTrackingRajawaliRenderer(this);
        RajawaliSurfaceView glView = (RajawaliSurfaceView) findViewById(R.id.gl_surface_view);
        glView.setEGLContextClientVersion(2);
        glView.setSurfaceRenderer(renderer);
        return renderer;
    }

    private void setTangoListeners() {
        final ArrayList<TangoCoordinateFramePair> framePairs =
            new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE));
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {

            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                mRenderer.updateDevicePose(pose);
                //logPose(pose);
                final double deltaTime = (pose.timestamp - mPreviousTimeStamp)
                        * SECS_TO_MILLISECS;

                mPreviousTimeStamp = pose.timestamp;
                // Log whenever Motion Tracking enters an invalid state
                if (pose.statusCode == TangoPoseData.POSE_INVALID) {
                    Log.w(TAG, "Invalid State");
                }
                if (mPreviousPoseStatus != pose.statusCode) {
                    mCount = 0;
                }
                mCount++;
                final int count = mCount;
                mPreviousPoseStatus = pose.statusCode;


                // Throttle updates to the UI based on UPDATE_INTERVAL_MS.
                mTimeToNextUpdate -= deltaTime;
                boolean updateUI = false;
                if (mTimeToNextUpdate < 0.0) {
                    mTimeToNextUpdate = UPDATE_INTERVAL_MS;
                    updateUI = true;
                }

                // If the pose is not valid, we may not get another callback so make sure to update
                // the UI during this call
                if (pose.statusCode != TangoPoseData.POSE_VALID) {
                    updateUI = true;
                }

                // save values
                if (pose != null) {
                    if (pose.baseFrame == TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION
                            && pose.targetFrame == TangoPoseData.COORDINATE_FRAME_DEVICE) {

                        // LOCALIZED!!!!!
                        /*ColorDrawable cd = (ColorDrawable) mLocalizationTextView.getBackground();
                        if (cd.getColor() == getResources().getColor(android.R.color.holo_green_dark)) {
                            mLocalizationTextView.setBackgroundColor(getResources().getColor(android.R.color.holo_purple));
                        } else {
                            mLocalizationTextView.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
                        }*/
                        Log.d("LOCALIZED", "yay");
                        if (true) {
                            try {
                                tangoPoseOutput.writeLong(System.currentTimeMillis());

                                tangoPoseOutput.writeDouble(pose.timestamp);
                                tangoPoseOutput.writeDouble(pose.translation[0]);
                                tangoPoseOutput.writeDouble(pose.translation[1]);
                                tangoPoseOutput.writeDouble(pose.translation[2]);

                                tangoPoseOutput.writeDouble(pose.rotation[0]);
                                tangoPoseOutput.writeDouble(pose.rotation[1]);
                                tangoPoseOutput.writeDouble(pose.rotation[2]);
                                tangoPoseOutput.writeDouble(pose.rotation[3]);
                                //Log.d("collect", "wrote stuff");


                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        if (updateUI) {

                            final String translationString =
                                    TangoPoseUtilities.getTranslationString(pose, FORMAT_THREE_DECIMAL);
                            final String quaternionString =
                                    TangoPoseUtilities.getQuaternionString(pose, FORMAT_THREE_DECIMAL);
                            final String status = TangoPoseUtilities.getStatusString(pose);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // Display pose data on screen in TextViews.
                                    mPoseTextView.setText(translationString);
                                    mQuatTextView.setText(quaternionString);
                                    mPoseCountTextView.setText(Integer.toString(count));
                                    mDeltaTextView.setText(FORMAT_THREE_DECIMAL.format(deltaTime));
                                    mPoseStatusTextView.setText(status);
                                }
                            });
                        }
                    }


                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData arg0) {
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
            }

            @Override
            public void onFrameAvailable(int cameraId) {
            }
        });
    }

    private void setupTextViewsAndButtons(TangoConfig config){
        // Text views for displaying translation and rotation data
        mPoseTextView = (TextView) findViewById(R.id.pose);
        mQuatTextView = (TextView) findViewById(R.id.quat);
        mPoseCountTextView = (TextView) findViewById(R.id.posecount);
        mDeltaTextView = (TextView) findViewById(R.id.deltatime);
        mTangoEventTextView = (TextView) findViewById(R.id.tangoevent);

        // Text views for the status of the pose data and Tango library versions
        mPoseStatusTextView = (TextView) findViewById(R.id.status);
        mTangoServiceVersionTextView = (TextView) findViewById(R.id.version);
        mApplicationVersionTextView = (TextView) findViewById(R.id.appversion);

        mLocalizationTextView = (TextView) findViewById(R.id.localized);

        // Button to reset motion tracking
        mMotionResetButton = (Button) findViewById(R.id.resetmotion);
        // Set up button click listeners
        mMotionResetButton.setOnClickListener(this);

        mConnectCamButton = (Button) findViewById(R.id.conCam);
        mConnectCamButton.setOnClickListener(this);

        mConnectSpecButton = (Button) findViewById(R.id.conSpec);
        mConnectSpecButton.setOnClickListener(this);

        mCollectButton = (Button) findViewById(R.id.collect);
        mCollectButton.setOnClickListener(this);

        // Display the library version for debug purposes
        mTangoServiceVersionTextView.setText(config.getString("tango_service_library_version"));
        PackageInfo packageInfo;
        try {
            packageInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
            mApplicationVersionTextView.setText(packageInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        plot = (XYPlot) findViewById(R.id.plot);
        series1 = new SimpleXYSeries("series1");
        series1.useImplicitXVals();
        plot.addSeries(series1, new LineAndPointFormatter(Color.BLUE, Color.RED, Color.BLACK, null));
    }

    private void connectCamera() {
        Constants result;

        mcp2221 = new MCP2221(this);


        result = mcp2221.open();

        switch (result) {
            case SUCCESS:
                Log.d("MCP2221","success");
                sToast.setText("MCP2221 connected");
                sToast.show();
                mcp2221Comm = new Mcp2221Comm(mcp2221);
                mConnectCamButton.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
                setupMCP2221();
                break;
            case CONNECTION_FAILED:
                Log.d("MCP2221","fail");

                sToast.setText("MCP2221 connection failed");
                sToast.show();
                break;
            case NO_USB_PERMISSION:
                Log.d("MCP2221","no usb");

                mcp2221.requestUsbPermission(mPermissionIntent);
                break;
            default:
                Log.d("MCP2221","default");

                break;
        }

        // Let's try to open the spectrometer



    }
    private void connectSpectrometer() {
        Constants result;
        spectrometer = new Spectrometer(this);

        result = spectrometer.open();

        switch (result) {
            case SUCCESS:
                Log.d("specto", "success");
                sToast.setText("Spectrometer connected");
                sToast.show();
                mConnectSpecButton.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
                final Handler specHandler = new Handler();
                final int specDelay = 15; //milliseconds

                specHandler.postDelayed(new Runnable() {
                    public void run() {
                        readSpectrometer();
                        specHandler.postDelayed(this, specDelay);
                    }
                }, specDelay);
                break;
            case CONNECTION_FAILED:
                Log.d("specto", "failed");
                sToast.setText("Spectrometer connection failed");
                sToast.show();
                break;
            case NO_USB_PERMISSION:
                Log.d("specto", "no permission");
                spectrometer.requestUsbPermission(mPermissionIntent);
                break;
            default:
                Log.d("specto", "default");
                break;
        }
    }

    void setupMCP2221(){
        Mcp2221Config conf = new Mcp2221Config();
        byte[] pinDesDir = {0,0,0,0};
        conf.setGpPinDesignations(pinDesDir);
        conf.setGpPinDirections(pinDesDir);
        byte[] pinVals = {1,0,0,0};
        conf.setGpPinValues(pinVals);
        mcp2221Comm.setSRamSettings(conf, false, false, false, false, false, false, true);

        Log.d("MCP2221", "values set");

    }



    /**
     * Reset motion tracking to last known valid pose. Once this function is called,
     * Motion Tracking restarts as such we may get initializing poses again. Developer should make
     * sure that user gets enough feed back in that case.
     */
    private void motionReset() {
        mTango.resetMotionTracking();
    }
    public void readSpectrometer(){
        if (shouldCollect){
            try {
                if (spectrometer != null) {
                    //Log.d("specto", "spectro?");
                    tangoSpectroOutput.writeLong(System.currentTimeMillis());
                    byte[] spec = spectrometer.captureSpectrum();
                    tangoSpectroOutput.write(spec);


                    Number[] intensities = new Number[1024];
                    int numCount = 0;
                    for (int i = 44; i <= 2091; i = i + 2) {
                        intensities[numCount] = ByteBuffer.wrap(new byte[]{spec[i + 1], spec[i]}).getShort();
                        numCount += 1;
                    }
                    if (randomGen.nextInt(5) == 0) {

                        series1.setModel(Arrays.asList(intensities), SimpleXYSeries.ArrayFormat.Y_VALS_ONLY);

                        plot.redraw();
                    }

                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public void startCollecting(){
        shouldCollect = true;
        final byte b0 = 0;
        final byte b1 = 1;
        if (mcp2221Comm != null) {
            mcp2221Comm.setGpPinValue(b1, b0); // make sure focus is connected to ground
            mcp2221Comm.setGpPinValue(b0, b0); // trigger the shutter
            //startCapture();
            try {
                Log.d("collecting", ""+System.currentTimeMillis());
                tangoCamOutput.writeLong(System.currentTimeMillis());
                Log.d("collecting", "photo start");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public void stopCollecting(){
        shouldCollect = false;
        final byte b0 = 0;
        final byte b1 = 1;
        if (mcp2221Comm != null) {
            mcp2221Comm.setGpPinValue(b0, b1); // trigger the shutter
            //startCapture();
            try {
                tangoCamOutput.writeLong(System.currentTimeMillis());
                Log.d("collecting", "photo end");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public void collect() {
        Log.d("button", "collecting");
        if (mCollectButton.getText().equals("Start Collecting")){
            startCollecting();
            Log.d("collect", "start collect");

            mCollectButton.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
            mCollectButton.setText("Stop Collecting");

        } else {
            stopCollecting();
            Log.d("collect", "stop collect");

            mCollectButton.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
            mCollectButton.setText("Start Collecting");
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {

            case R.id.resetmotion:
                motionReset();
                break;
            case R.id.collect:
                collect();
                break;

            case R.id.conCam:
                //makeSound();
                connectCamera();
                break;
            case R.id.conSpec:
                connectSpectrometer();
                break;
            default:
                Log.w(TAG, "Unknown button click");
                return;
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        try {
            mTango.disconnect();
        } catch (TangoErrorException e) {
            Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("resume","RESUME");
        // Initialize Tango Service as a normal Android Service, since we call 
        // mTango.disconnect() in onPause, this will unbind Tango Service, so
        // everytime when onResume get called, we should create a new Tango object.
        mTango = new Tango(MotionTrackingActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready,
            // this Runnable will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only
            // when there is no UI thread changes involved.
            @Override
            public void run() {
                //startActivityForResult(mTango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_DATASET),mTango.TANGO_INTENT_ACTIVITYCODE);
                //startActivityForResult(mTango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),mTango.TANGO_INTENT_ACTIVITYCODE);

                mConfig = mTango.getConfig(mConfig.CONFIG_TYPE_CURRENT);
                mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_MOTIONTRACKING, true);
                //mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);
                //mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
                //mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_COLORMODEAUTO, true);
                //mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_HIGH_RATE_POSE, true);
               // mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_LEARNINGMODE, true);

                ArrayList<String> fullUuidList;
                // Returns a list of ADFs with their UUIDs
                fullUuidList = mTango.listAreaDescriptions();
                // Load the latest ADF if ADFs are found.
                if (fullUuidList.size() > 0) {
                    String uuid = fullUuidList.get(fullUuidList.size() - 1);
                    mConfig.putString(TangoConfig.KEY_STRING_AREADESCRIPTION, uuid);


                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setupTextViewsAndButtons(mConfig);

                    }
                });

                try {
                    setTangoListeners();
                } catch (TangoErrorException e) {
                    Log.e(TAG, getString(R.string.TangoError), e);
                } catch (SecurityException e) {
                    Log.e(TAG, getString(R.string.motiontrackingpermission), e);
                }
                try {

                    mTango.connect(mConfig);

                    //mTango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_ADF_LOAD_SAVE);
                    //mTango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING);
                } catch (TangoOutOfDateException e) {
                    Log.e(TAG, getString(R.string.TangoOutOfDateException), e);
                } catch (TangoErrorException e) {
                    Log.e(TAG, getString(R.string.TangoError), e);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);

    }
}
