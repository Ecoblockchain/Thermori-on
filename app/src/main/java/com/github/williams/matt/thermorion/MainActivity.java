package com.github.williams.matt.thermorion;

import com.github.williams.matt.thermorion.util.SystemUiHider;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.media.AudioManager;
import android.util.Log;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.flir.flironesdk.Device;
import com.flir.flironesdk.Frame;
import com.flir.flironesdk.FrameProcessor;
import com.flir.flironesdk.RenderedImage;

import java.util.EnumSet;

/**
 * An example activity and delegate for FLIR One image streaming and device interaction.
 * Based on an example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 *
 * @see SystemUiHider
 * @see com.flir.flironesdk.Device.Delegate
 * @see com.flir.flironesdk.FrameProcessor.Delegate
 * @see com.flir.flironesdk.Device.StreamDelegate
 * @see com.flir.flironesdk.Device.PowerUpdateDelegate
 */
public class MainActivity extends Activity implements Device.Delegate, Device.StreamDelegate, FrameProcessor.Delegate, Device.PowerUpdateDelegate {
    private ImageView thermalImageView;
    private OverlayDrawable overlayDrawable;

    private int deviceRotation = 0;
    private OrientationEventListener orientationEventListener;

    private volatile Device flirOneDevice;

    // Device Delegate methods

    // Called during device discovery, when a device is connected
    // During this callback, you should save a reference to device
    // You should also set the power update delegate for the device if you have one
    // Go ahead and start frame stream as soon as connected, in this use case
    // Finally we create a frame processor for rendering frames

    public synchronized void onDeviceConnected(Device device) {
        mRetryHandler.removeCallbacks(mRetryRunnable);
        mPromptHandler.removeCallbacks(mPromptRunnable);
        if (mPromptToast != null) {
            mPromptToast.cancel();
            mPromptToast = null;
        }

        flirOneDevice = device;
        flirOneDevice.setPowerUpdateDelegate(this);
        flirOneDevice.startFrameStream(this);

        orientationEventListener.enable();
    }

    /**
     * Indicate to the user that the device has disconnected
     */
    public synchronized void onDeviceDisconnected(Device device) {
        final TextView levelTextView = (TextView) findViewById(R.id.batteryLevelTextView);
        final ImageView chargingIndicator = (ImageView) findViewById(R.id.batteryChargeIndicator);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                thermalImageView.setImageBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8));
                // TODO: Reset overlay
                levelTextView.setText("--");
                chargingIndicator.setVisibility(View.GONE);
                thermalImageView.clearColorFilter();
                findViewById(R.id.tuningProgressBar).setVisibility(View.GONE);
                findViewById(R.id.tuningTextView).setVisibility(View.GONE);
            }
        });
        flirOneDevice = null;
        orientationEventListener.disable();
    }

    private Device.TuningState currentTuningState = Device.TuningState.Unknown;

    /**
     * If using RenderedImage.ImageType.ThermalRadiometricKelvinImage, you should not rely on
     * the accuracy if tuningState is not Device.TuningState.Tuned
     *
     * @param tuningState
     */
    public void onTuningStateChanged(Device.TuningState tuningState) {
        currentTuningState = tuningState;
        if (tuningState == Device.TuningState.InProgress) {
            runOnUiThread(new Thread() {
                @Override
                public void run() {
                    super.run();
                    thermalImageView.setColorFilter(Color.DKGRAY, PorterDuff.Mode.DARKEN);
                    findViewById(R.id.tuningProgressBar).setVisibility(View.VISIBLE);
                    findViewById(R.id.tuningTextView).setVisibility(View.VISIBLE);
                }
            });
        } else {
            runOnUiThread(new Thread() {
                @Override
                public void run() {
                    super.run();
                    thermalImageView.clearColorFilter();
                    findViewById(R.id.tuningProgressBar).setVisibility(View.GONE);
                    findViewById(R.id.tuningTextView).setVisibility(View.GONE);
                }
            });
        }
    }

    @Override
    public void onAutomaticTuningChanged(boolean deviceWillTuneAutomatically) {
    }

    private ColorFilter originalChargingIndicatorColor = null;

    @Override
    public void onBatteryChargingStateReceived(final Device.BatteryChargingState batteryChargingState) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ImageView chargingIndicator = (ImageView) findViewById(R.id.batteryChargeIndicator);
                if (originalChargingIndicatorColor == null) {
                    originalChargingIndicatorColor = chargingIndicator.getColorFilter();
                }
                switch (batteryChargingState) {
                    case FAULT:
                    case FAULT_HEAT:
                        chargingIndicator.setColorFilter(Color.RED);
                        chargingIndicator.setVisibility(View.VISIBLE);
                        break;
                    case FAULT_BAD_CHARGER:
                        chargingIndicator.setColorFilter(Color.DKGRAY);
                        chargingIndicator.setVisibility(View.VISIBLE);
                    case MANAGED_CHARGING:
                        chargingIndicator.setColorFilter(originalChargingIndicatorColor);
                        chargingIndicator.setVisibility(View.VISIBLE);
                        break;
                    case NO_CHARGING:
                    default:
                        chargingIndicator.setVisibility(View.GONE);
                        break;
                }
            }
        });
    }

    @Override
    public void onBatteryPercentageReceived(final byte percentage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView levelTextView = (TextView) findViewById(R.id.batteryLevelTextView);
                levelTextView.setText(String.valueOf((int) percentage) + "%");
            }
        });
    }

    private FrameProcessorThread frameProcessorThread = null;
    private class FrameProcessorThread extends Thread {
        private FrameProcessor frameProcessor;
        private Frame frameToProcess = null;
        private boolean terminating = false;

        public FrameProcessorThread(Context context, FrameProcessor.Delegate delegate) {
            frameProcessor = new FrameProcessor(context, delegate, EnumSet.of(RenderedImage.ImageType.ThermalRGBA8888Image,
                                                                              RenderedImage.ImageType.ThermalRadiometricKelvinImage));
            start();
        }

        public void processFrame(Frame frame) {
            synchronized (this) {
                frameToProcess = frame;
                notify();
            }
        }

        public void terminate() {
            synchronized (this) {
                terminating = true;
                notify();
            }
        }

        @Override
        public void run() {
            while (!terminating) {
                try {
                    Frame frame = null;
                    synchronized(this) {
                        while ((frameToProcess == null) && terminating) {
                            wait();
                        }
                        frame = frameToProcess;
                        frameToProcess = null;
                    }
                    if (frame != null) {
                        frameProcessor.processFrame(frame);
                    }
                } catch (InterruptedException e) {
                    Log.e("FrameProcessorThread", "Caught InterruptedException", e);
                }
            }
        }
    };

    @Override
    public void onFrameProcessed(final RenderedImage renderedImage) {
        if (renderedImage.imageType() == RenderedImage.ImageType.ThermalRadiometricKelvinImage) {
            overlayDrawable.updateThermalImage(renderedImage);
        } else if (renderedImage.imageType() == RenderedImage.ImageType.ThermalRGBA8888Image) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    thermalImageView.setImageBitmap(renderedImage.getBitmap());
                    Rect oldBounds = overlayDrawable.getBounds();
                    int imageViewWidth = thermalImageView.getWidth();
                    int imageViewHeight = thermalImageView.getHeight();
                    int imageWidth = renderedImage.width();
                    int imageHeight = renderedImage.height();
                    if ((imageViewWidth > 0) && (imageViewHeight > 0)) {
                        double scaleFactor = Math.min(imageViewWidth * 1.0 / imageWidth, imageViewHeight * 1.0 / imageHeight);
                        int scaledWidth = (int)(imageWidth * scaleFactor);
                        int scaledHeight = (int)(imageHeight * scaleFactor);
                        int padLeft = (imageViewWidth - scaledWidth) / 2;
                        int padTop = (imageViewHeight - scaledHeight) / 2;
                        Rect newBounds = new Rect(padLeft, padTop, padLeft + scaledWidth, padTop + scaledHeight);
                        overlayDrawable.setBounds(newBounds);
                    }
                }
            });
        }
    }

    // StreamDelegate method
    public void onFrameReceived(Frame frame){
        if ((currentTuningState != Device.TuningState.InProgress) && (frameProcessorThread != null)) {
            frameProcessorThread.processFrame(frame);
        }
    }

    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

    /**
     * The flags to pass to {@link SystemUiHider#getInstance}.
     */
    private static final int HIDER_FLAGS = SystemUiHider.FLAG_HIDE_NAVIGATION;

    /**
     * The instance of the {@link SystemUiHider} for this activity.
     */
    private SystemUiHider mSystemUiHider;
    public void onTuneClicked(View v){
        if (flirOneDevice != null){
            flirOneDevice.performTuning();
        }

    }

    public void onRotateClicked(View v){
        ToggleButton theSwitch = (ToggleButton)v;
        if (theSwitch.isChecked()){
            thermalImageView.setRotation(180);
        }else{
            thermalImageView.setRotation(0);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_preview);

        thermalImageView = (ImageView) findViewById(R.id.imageView);
        overlayDrawable = new OverlayDrawable(this);
        thermalImageView.getOverlay().add(overlayDrawable);

        final View controlsView = findViewById(R.id.fullscreen_content_controls);
        final View contentView = findViewById(R.id.fullscreen_content);

        // Set up an instance of SystemUiHider to control the system UI for
        // this activity.

        mSystemUiHider = SystemUiHider.getInstance(this, contentView, HIDER_FLAGS);
        mSystemUiHider.setup();

        mSystemUiHider
                .setOnVisibilityChangeListener(new SystemUiHider.OnVisibilityChangeListener() {
                    // Cached values.
                    int mControlsHeight;
                    int mShortAnimTime;

                    @Override
                    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
                    public void onVisibilityChange(boolean visible) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
                            // If the ViewPropertyAnimator API is available
                            // (Honeycomb MR2 and later), use it to animate the
                            // in-layout UI controls at the bottom of the
                            // screen.
                            if (mControlsHeight == 0) {
                                mControlsHeight = controlsView.getHeight();
                            }
                            if (mShortAnimTime == 0) {
                                mShortAnimTime = getResources().getInteger(
                                        android.R.integer.config_shortAnimTime);
                            }
                            controlsView.animate()
                                    .translationY(visible ? 0 : -mControlsHeight)
                                    .setDuration(mShortAnimTime);
                        } else {
                            // If the ViewPropertyAnimator APIs aren't
                            // available, simply show or hide the in-layout UI
                            // controls.
                            controlsView.setVisibility(visible ? View.VISIBLE : View.GONE);
                        }

                        delayedHide(AUTO_HIDE_DELAY_MILLIS);
                   }
                });

        // Set up the user interaction to manually show or hide the system UI.
        contentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mSystemUiHider.toggle();
            }
        });

        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                deviceRotation = orientation;
            }
        };

        Dialog dialog = new AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(R.string.about_dialog).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                // Trigger the initial hide() shortly after the activity has been
                // created, to briefly hint to the user that UI controls
                // are available.
                delayedHide(100);
            }
        }).create();
        dialog.show();

    }

    @Override
    protected synchronized void onStart(){
        Log.e("Thermori-on", "onStart");
        super.onStart();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
//        overlayDrawable.reset();
//        thermalImageView.setImageResource(R.drawable.insert_flir);
        try {
            Device.startDiscovery(this, this);
        } catch(IllegalStateException e) {
            // it's okay if we've already started discovery
        }
        if (flirOneDevice == null) {
            mRetryHandler.postDelayed(mRetryRunnable, 2000);
            mPromptHandler.postDelayed(mPromptRunnable, 5000);
        }
    }

    private Handler animationHandler = new Handler();
    private Runnable animationRunnable = new Runnable() {
        @Override
        public void run() {
            thermalImageView.invalidate();
            animationHandler.removeCallbacks(this);
            animationHandler.postDelayed(this, 20);
        }
    };

    @Override
    protected synchronized void onResume() {
        Log.e("Thermori-on", "onResume");
        super.onResume();
        frameProcessorThread = new FrameProcessorThread(this, this);
        overlayDrawable.start();
        animationHandler.postDelayed(animationRunnable, 20);
//        if (flirOneDevice != null) {
//            flirOneDevice.setPowerUpdateDelegate(this);
//            flirOneDevice.startFrameStream(this);
//        }
    }

    @Override
    protected synchronized void onPause() {
        Log.e("Thermori-on", "onPause");
//        if (flirOneDevice != null) {
//            flirOneDevice.stopFrameStream();
//            flirOneDevice.setPowerUpdateDelegate(null);
//        }
        animationHandler.removeCallbacks(animationRunnable);
        overlayDrawable.stop();
        frameProcessorThread.terminate();
        frameProcessorThread = null;
        super.onPause();
    }


    @Override
    public synchronized void onStop() {
        Log.e("Thermori-on", "onStop");
        // We must unregister our usb receiver, otherwise we will steal events from other apps
        Device.stopDiscovery();
        flirOneDevice = null;
        super.onStop();
    }

    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            delayedHide(AUTO_HIDE_DELAY_MILLIS);
            return false;
        }
    };

    Handler mHideHandler = new Handler();
    Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            mSystemUiHider.hide();
        }
    };

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    Handler mRetryHandler = new Handler();
    Runnable mRetryRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (MainActivity.this) {
                if (flirOneDevice == null) {
                    // Try restarting discovery - sometimes that helps
                    Device.stopDiscovery();
                    try {
                        Device.startDiscovery(MainActivity.this, MainActivity.this);
                    } catch (IllegalStateException e) {
                        // it's okay if we've already started discovery
                    }
                }
            }
        }
    };

    Handler mPromptHandler = new Handler();
    Toast mPromptToast = null;
    Runnable mPromptRunnable = new Runnable() {
        @Override
        public void run() {
            if (flirOneDevice == null) {
                mPromptToast = Toast.makeText(MainActivity.this, R.string.no_camera_prompt, Toast.LENGTH_LONG);
                mPromptToast.show();
            }
        }
    };
}
