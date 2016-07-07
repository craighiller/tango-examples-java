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

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.projecttango.tangoutils.TangoPoseUtilities;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.rajawali3d.surface.IRajawaliSurface;
import org.rajawali3d.surface.RajawaliSurfaceView;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_motion_tracking);
        mRenderer = setupGLViewAndRenderer();
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
    private void logPose(TangoPoseData pose) {
        StringBuilder stringBuilder = new StringBuilder();

        float translation[] = pose.getTranslationAsFloats();
        stringBuilder.append("Position: " +
                translation[0] + ", " + translation[1] + ", " + translation[2]);

        float orientation[] = pose.getRotationAsFloats();
        stringBuilder.append(". Orientation: " +
                orientation[0] + ", " + orientation[1] + ", " +
                orientation[2] + ", " + orientation[3]);

        Log.i(TAG, stringBuilder.toString());
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
    }



    @Override
    public void onClick(View v) {
        switch (v.getId()) {

            case R.id.resetmotion:
                //motionReset();
                break;
            case R.id.collect:
                //collect();
                break;

            case R.id.conCam:
                //makeSound();
                //connectCamera();
                break;
            case R.id.conSpec:
                //connectSpectrometer();
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
                mConfig = mTango.getConfig(mConfig.CONFIG_TYPE_CURRENT);
                mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_MOTIONTRACKING, true);
                mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);
                setupTextViewsAndButtons(mConfig);
                try {
                    setTangoListeners();
                } catch (TangoErrorException e) {
                    Log.e(TAG, getString(R.string.TangoError), e);
                } catch (SecurityException e) {
                    Log.e(TAG, getString(R.string.motiontrackingpermission), e);
                }
                try {
                    mTango.connect(mConfig);
                } catch (TangoOutOfDateException e) {
                    Log.e(TAG, getString(R.string.TangoOutOfDateException), e);
                } catch (TangoErrorException e) {
                    Log.e(TAG, getString(R.string.TangoError), e);
                }
            }
        });
    }
}
