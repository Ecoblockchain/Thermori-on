package com.github.williams.matt.thermorion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.AttributeSet;
import android.util.Log;

import com.flir.flironesdk.RenderedImage;

import java.util.Arrays;
import java.util.Date;

/**
 * Created by mirw on 19/06/16.
 */
public class OverlayDrawable extends Drawable implements Drawable.Callback {
    int width;
    int height;
    int panelSize;
    int numPanelsWide;
    int numPanelsHigh;
    int panelXPadding;
    int panelYPadding;
    private long startTime;
    private boolean[] toneArray;
    private boolean[] animating;
    private int timeslot;
    private int tickInTimeslot;
    private AudioManager audioManager;
    private SoundPool soundPool;
    private int soundId;
    private boolean soundLoaded = false;
    private SoundThread soundThread = null;
    private static final double SEMITONE = Math.pow(2.0, 1.0 / 12.0);
    private static final float SCALE[] = {
            (float)Math.pow(SEMITONE, 0),
            (float)Math.pow(SEMITONE, 2),
            (float)Math.pow(SEMITONE, 4),
            (float)Math.pow(SEMITONE, 5),
            (float)Math.pow(SEMITONE, 7),
            (float)Math.pow(SEMITONE, 9),
            (float)Math.pow(SEMITONE, 11),
            (float)Math.pow(SEMITONE, 12)
    }; // Ionian scale
    private int left;
    private int top;

    public OverlayDrawable(Context context) {
        // AudioManager audio settings for adjusting the volume
        audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

        // Load the sounds
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                soundLoaded = true;
            }
        });
        soundId = soundPool.load(context, R.raw.piano, 1);
    }

    public void start() {
        soundThread = new SoundThread();
    }

    public void stop() {
        soundThread.terminate();
        soundThread = null;
    }

    @Override
    public void setAlpha(int i) {}
    @Override
    public void setColorFilter(ColorFilter colorFilter) {}
    @Override
    public int getOpacity() {
        return 100;
    }

    @Override
    protected synchronized void onBoundsChange(Rect bounds) {
        left = bounds.left;
        top = bounds.top;
        width = bounds.width();
        height = bounds.height();
        panelSize = (int)Math.floor(Math.min((width * 0.95), (height * 0.95)) / 8);
        numPanelsWide = (int)(width * 0.95) / panelSize;
        numPanelsHigh = (int)(height * 0.95) / panelSize;
        panelXPadding = (width - (numPanelsWide * panelSize)) / 2;
        panelYPadding = (height - (numPanelsHigh * panelSize)) / 2;
        toneArray = new boolean[numPanelsWide * numPanelsHigh];
        animating = new boolean[Math.min(numPanelsWide, numPanelsHigh)];
        startTime = new Date().getTime();
        timeslot = 0;
        tickInTimeslot = 0;
    }

    public synchronized void reset() {
        toneArray = new boolean[numPanelsWide * numPanelsHigh];
        animating = new boolean[Math.min(numPanelsWide, numPanelsHigh)];
        startTime = new Date().getTime();
        timeslot = 0;
        tickInTimeslot = 0;
    }

    class SoundThread extends Thread {
        private boolean terminating = false;

        public SoundThread() {
            start();
        }

        public void terminate() {
            terminating = true;
        }

        @Override
        public void run() {
            while (!terminating) {
                long time = new Date().getTime() - startTime;
                if ((numPanelsWide > 0) && (numPanelsHigh > 0)) {
                    int newTimeslot = (int)((time / 250) % (Math.max(numPanelsWide, numPanelsHigh)));
                    tickInTimeslot = (int)(time % 250);

                    if (newTimeslot != timeslot) {
                        timeslot = newTimeslot;

                        float actVolume = (float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                        float maxVolume = (float) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        float volume = actVolume / maxVolume;

                        synchronized (OverlayDrawable.this) {
                            if (width > height) {
                                for (int y = 0; y < numPanelsHigh; y++) {
                                    boolean play = toneArray[timeslot + y * numPanelsWide];
                                    animating[y] = play;
                                    if ((play) && (soundLoaded)) {
                                        soundPool.play(soundId, volume, volume, 1, 0, SCALE[y]);
                                    }
                                }
                            } else {
                                for (int x = 0; x < numPanelsWide; x++) {
                                    boolean play = toneArray[x + timeslot * numPanelsWide];
                                    animating[x] = play;
                                    if ((play) && (soundLoaded)) {
                                        soundPool.play(soundId, volume, volume, 1, 0, SCALE[x]);
                                    }
                                }
                            }
                        }
                    }
                }
                try {
                    synchronized (this) {
                        wait(10);
                    }
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    @Override
    public synchronized void draw(Canvas canvas) {
        if ((numPanelsWide > 0) && (numPanelsHigh > 0)) {
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);

            for (int x = 0; x < numPanelsWide; x++) {
                for (int y = 0; y < numPanelsHigh; y++) {
                    if ((width > height) && (timeslot == x) && (animating[y]) ||
                            (width <= height) && (timeslot == y) && (animating[x])) {
                        // We'll do this just below, after we've drawn all the "background" entries...
                    }
                    else if (toneArray[x + y * numPanelsWide]) {
                        paint.setColor(Color.BLACK);
                        paint.setStrokeWidth(8);
                        canvas.drawRoundRect(left + panelXPadding + panelSize * x + 10, top + panelYPadding + panelSize * y + 10, left + panelXPadding + panelSize * (x + 1) - 10, top + panelYPadding + panelSize * (y + 1) - 10, 5, 5, paint);
                        paint.setColor(Color.WHITE);
                        paint.setStrokeWidth(4);
                        canvas.drawRoundRect(left + panelXPadding + panelSize * x + 10, top + panelYPadding + panelSize * y + 10, left + panelXPadding + panelSize * (x + 1) - 10, top + panelYPadding + panelSize * (y + 1) - 10, 5, 5, paint);
                    } else {
                        paint.setColor(Color.BLACK);
                        paint.setStrokeWidth(8);
                        canvas.drawRoundRect(left + panelXPadding + panelSize * x + 10, top + panelYPadding + panelSize * y + 10, left + panelXPadding + panelSize * (x + 1) - 10, top + panelYPadding + panelSize * (y + 1) - 10, 5, 5, paint);
                    }
                }
            }

            for (int x = 0; x < numPanelsWide; x++) {
                for (int y = 0; y < numPanelsHigh; y++) {
                    if ((width > height) && (timeslot == x) && (animating[y]) ||
                            (width <= height) && (timeslot == y) && (animating[x])) {
                        float effect;
                        if (tickInTimeslot < 75) {
                            effect = tickInTimeslot / 75.0f;
                        } else if (tickInTimeslot < 150) {
                            effect = 1.0f - (tickInTimeslot - 75.0f) / 150.0f;
                        } else {
                            effect = 0.5f - (tickInTimeslot - 150.0f) / 200.0f;
                        }
                        paint.setColor(Color.GREEN);
                        paint.setStrokeWidth(panelSize / (5 - effect));
                        canvas.drawRoundRect(left + panelXPadding + panelSize * x + 10 - 10 * effect, top + panelYPadding + panelSize * y + 10 - 10 * effect, left + panelXPadding + panelSize * (x + 1) - 10 + 10 * effect, top + panelYPadding + panelSize * (y + 1) - 10 + 10 * effect, 5, 5, paint);
                    }
                }
            }

            float strokeWidth = Math.min(width, height) / 100;
            paint.setStrokeWidth(strokeWidth);
            paint.setColor(Color.GREEN);

            if (width > height) {
                int x = (int)((timeslot * 250 + tickInTimeslot) * panelSize / 250 + panelXPadding);
                paint.setAlpha(31);
                canvas.drawLine(left + x - strokeWidth * 2, top, left + x - strokeWidth * 2, top + height, paint);
                paint.setAlpha(63);
                canvas.drawLine(left + x - strokeWidth, top, left + x - strokeWidth, top + height, paint);
                paint.setAlpha(127);
                canvas.drawLine(left + x, top, left + x, top + height, paint);
                paint.setAlpha(255);
                canvas.drawLine(left + x + strokeWidth, top, left + x + strokeWidth, top + height, paint);
                paint.setAlpha(127);
                canvas.drawLine(left + x + strokeWidth * 2, top, left + x + strokeWidth * 2, top + height, paint);
            } else {
                int y = (int)((timeslot * 250 + tickInTimeslot) * panelSize / 250 + panelYPadding);
                paint.setAlpha(31);
                canvas.drawLine(left, top + y - strokeWidth * 2, left + width, top + y - strokeWidth * 2, paint);
                paint.setAlpha(63);
                canvas.drawLine(left, top + y - strokeWidth, left + width, top + y - strokeWidth, paint);
                paint.setAlpha(127);
                canvas.drawLine(left, top + y, left + width, top + y, paint);
                paint.setAlpha(255);
                canvas.drawLine(left, top + y + strokeWidth, left + width, top + y + strokeWidth, paint);
                paint.setAlpha(127);
                canvas.drawLine(left, top + y + strokeWidth * 2, left + width, top + y + strokeWidth * 2, paint);
            }
        }
    }

    public void updateThermalImage(RenderedImage renderedImage) {
        if ((numPanelsWide > 0) && (numPanelsHigh > 0)) {
            short[] thermalData = new short[numPanelsWide * numPanelsHigh];
            long thermalTotal = 0;
            short thermalMinimum = Short.MAX_VALUE;
            short thermalMaximum = Short.MIN_VALUE;
            int renderedImageWidth = renderedImage.width();
            int renderedImageHeight = renderedImage.height();
            short[] pix = renderedImage.thermalPixelData();
            for (int y = 0; y < numPanelsHigh; y++) {
                for (int x = 0; x < numPanelsWide; x++) {
                    float scaleX = renderedImage.width() * 1.0f / width;
                    float scaleY = renderedImage.height() * 1.0f / height;
                    int startX = (int)((panelXPadding + panelSize * x + 10) * scaleX);
                    int endX = (int)((panelXPadding + panelSize * (x + 1) - 10) * scaleX);
                    int startY = (int)((panelYPadding + panelSize * y + 10) * scaleY);
                    int endY = (int)((panelYPadding + panelSize * (y + 1) - 10) * scaleY);
                    long accumulator = 0;
                    for (int y2 = startY; y2 <= endY ; y2 ++) {
                        for (int x2 = startX; x2 <= endX ; x2 ++) {
                            accumulator += pix[x2 + y2 * renderedImageWidth];
                        }
                    }
                    short result = (short)(accumulator / (endX - startX + 1) / (endY - startY + 1));
                    thermalData[x + numPanelsWide * y] = result;
                    thermalTotal += result;
                    thermalMinimum = (short)Math.min(thermalMinimum, result);
                    thermalMaximum = (short)Math.max(thermalMaximum, result);
                }
            }
            /*
            short[] thermalLocalMean = new short[numPanelsWide * numPanelsHigh];
            for (int y = 0; y < numPanelsHigh; y++) {
                for (int x = 0; x < numPanelsWide; x++) {
                    int localTotal = 0;
                    short localCount = 0;
                    if (x > 0) {
                        localTotal += thermalData[x - 1 + numPanelsWide * y];
                        localCount++;
                    }
                    if (y > 0) {
                        localTotal += thermalData[x + numPanelsWide * (y - 1)];
                        localCount++;
                    }
                    if (x < numPanelsWide - 1) {
                        localTotal += thermalData[x + 1 + numPanelsWide * y];
                        localCount++;
                    }
                    if (y < numPanelsHigh - 1) {
                        localTotal += thermalData[x + numPanelsWide * (y + 1)];
                        localCount++;
                    }
                    thermalLocalMean[x + numPanelsWide * y] = (short)(localTotal / localCount);
                }
            }
            */
            /*
            short[] thermalLocalMean2 = new short[numPanelsWide * numPanelsHigh];
            for (int y = 0; y < numPanelsHigh; y++) {
                for (int x = 0; x < numPanelsWide; x++) {
                    int localTotal = 0;
                    short localCount = 0;
                    if (x > 0) {
                        localTotal += thermalLocalMean[x - 1 + numPanelsWide * y];
                        localCount++;
                    }
                    if (y > 0) {
                        localTotal += thermalLocalMean[x + numPanelsWide * (y - 1)];
                        localCount++;
                    }
                    if (x < numPanelsWide - 1) {
                        localTotal += thermalLocalMean[x + 1 + numPanelsWide * y];
                        localCount++;
                    }
                    if (y < numPanelsHigh - 1) {
                        localTotal += thermalLocalMean[x + numPanelsWide * (y + 1)];
                        localCount++;
                    }
                    thermalLocalMean[x + numPanelsWide * y] = (short)(localTotal / localCount);
                }
            }
            */
            /*
            for (int y = 0; y < numPanelsHigh; y++) {
                for (int x = 0; x < numPanelsWide; x++) {
                    short result = (short)(thermalData[x + numPanelsWide * y] - thermalLocalMean[x + numPanelsWide * y]);// - thermalLocalMean2[x + numPanelsWide * y]);
                    thermalData[x + numPanelsWide * y] = result;
                    thermalTotal += result;
                    thermalMinimum = (short)Math.min(thermalMinimum, result);
                    thermalMaximum = (short)Math.max(thermalMaximum, result);
                }
            }
            */
            short thermalAverage = (short)(thermalTotal / (numPanelsWide * numPanelsHigh));
            short thermalThreshold = (short)(thermalAverage + 50);
            boolean[] newToneArray = new boolean[numPanelsWide * numPanelsHigh];
            for (int y = 0; y < numPanelsHigh; y++) {
                for (int x = 0; x < numPanelsWide; x++) {
                    short thermalValue = thermalData[x + numPanelsWide * y];
                    /*
                    int greaterCount = 0;
                    if (thermalValue > thermalThreshold) {
                        if ((x > 0) && (thermalData[x - 1 + numPanelsWide * y] > thermalValue)) {
                            greaterCount++;
                        }
                        if ((y > 0) && (thermalData[x + numPanelsWide * (y - 1)] > thermalValue)) {
                            greaterCount++;
                        }
                        if ((x < numPanelsWide - 1) && (thermalData[x + 1 + numPanelsWide * y] > thermalValue)) {
                            greaterCount++;
                        }
                        if ((y < numPanelsHigh - 1) && (thermalData[x + numPanelsWide * (y + 1)] > thermalValue)) {
                            greaterCount++;
                        }
                    }
                    */
                    newToneArray[x + numPanelsWide * y] = (thermalValue > thermalThreshold);
                }
            }

            synchronized (this) {
                toneArray = newToneArray;
            }
        }
    }

    @Override
    public void invalidateDrawable(Drawable drawable) {}

    @Override
    public void scheduleDrawable(Drawable drawable, Runnable runnable, long l) {}

    @Override
    public void unscheduleDrawable(Drawable drawable, Runnable runnable) {}
}
