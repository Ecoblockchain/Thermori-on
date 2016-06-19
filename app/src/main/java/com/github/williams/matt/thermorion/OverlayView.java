package com.github.williams.matt.thermorion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.flir.flironesdk.RenderedImage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;

/**
 * Created by mirw on 19/06/16.
 */
public class OverlayView extends View {
    int width;
    int height;
    int panelSize;
    int numPanelsWide;
    int numPanelsHigh;
    int panelXPadding;
    int panelYPadding;
    private long startTime;
    private short[] thermalData;
    private boolean[] animating;
    private int timeslot;
    private short thermalAverage;
    private short thermalMinimum;
    private short thermalMaximum;
    private AudioManager audioManager;
    private SoundPool soundPool;
    private int soundId;
    private boolean soundLoaded = false;

    public OverlayView(Context context) {
        super(context);
        initResources();
    }

    public OverlayView(Context context, AttributeSet set) {
        super(context, set);
        initResources();
    }

    void initResources() {
        // AudioManager audio settings for adjusting the volume
        audioManager = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);

        // Load the sounds
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                soundLoaded = true;
            }
        });
        soundId = soundPool.load(getContext(), R.raw.piano, 1);
    }

    @Override
    public void onSizeChanged (int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        panelSize = (int)Math.floor(Math.min((width * 0.8), (height * 0.8)) / 3) / 2;
        numPanelsWide = (int)(width * 0.8) / panelSize;
        numPanelsHigh = (int)(height * 0.8) / panelSize;
        panelXPadding = (width - (numPanelsWide * panelSize)) / 2;
        panelYPadding = (height - (numPanelsHigh * panelSize)) / 2;
        thermalData = new short[numPanelsWide * numPanelsHigh];
        animating = new boolean[Math.min(numPanelsWide, numPanelsHigh)];
        startTime = new Date().getTime();
        timeslot = 0;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long time = new Date().getTime() - startTime;
        int newTimeslot = (int)((time / 250) % (Math.max(numPanelsWide, numPanelsHigh)));
        int tickInTimeslot = (int)(time % 250);

        if (newTimeslot != timeslot) {
            timeslot = newTimeslot;

            float actVolume = (float)audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            float maxVolume = (float)audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float volume = actVolume / maxVolume;


            if (width > height) {
                for (int y = 0; y < numPanelsHigh; y++) {
                    boolean play = (thermalData[timeslot + y * numPanelsWide] > thermalAverage + 10);
                    animating[y] = play;
                    if ((play) && (soundLoaded)) {
                        soundPool.play(soundId, volume, volume, 1, 0, 1.0f + 2 * y / numPanelsHigh);
                    }
                }
            } else {
                for (int x = 0; x < numPanelsWide; x++) {
                    boolean play = (thermalData[x + timeslot * numPanelsWide] > thermalAverage + 10);
                    animating[x] = play;
                    if ((play) && (soundLoaded)) {
                        soundPool.play(soundId, volume, volume, 1, 0, 1.0f + 2 * x / numPanelsWide);
                    }
                }
            }
        }

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);

        for (int x = 0; x < numPanelsWide; x++) {
            for (int y = 0; y < numPanelsHigh; y++) {
                if ((width > height) && (timeslot == x) && (animating[y]) ||
                        (width <= height) && (timeslot == y) && (animating[x])) {
                    // We'll do this just below, after we've drawn all the "background" entries...
                }
                else if (thermalData[x + y * numPanelsWide] > thermalAverage + 10) {
                    paint.setColor(Color.BLACK);
                    paint.setStrokeWidth(panelSize / 5);
                    canvas.drawRoundRect(panelXPadding + panelSize * x + 10, panelYPadding + panelSize * y + 10, panelXPadding + panelSize * (x + 1) - 10, panelYPadding + panelSize * (y + 1) - 10, 5, 5, paint);
                    paint.setColor(Color.WHITE);
                    paint.setStrokeWidth(panelSize / 10);
                    canvas.drawRoundRect(panelXPadding + panelSize * x + 10, panelYPadding + panelSize * y + 10, panelXPadding + panelSize * (x + 1) - 10, panelYPadding + panelSize * (y + 1) - 10, 5, 5, paint);
                } else {
                    paint.setColor(Color.BLACK);
                    paint.setStrokeWidth(panelSize / 10);
                    canvas.drawRoundRect(panelXPadding + panelSize * x + 10, panelYPadding + panelSize * y + 10, panelXPadding + panelSize * (x + 1) - 10, panelYPadding + panelSize * (y + 1) - 10, 5, 5, paint);
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
                    canvas.drawRoundRect(panelXPadding + panelSize * x + 10 - 10 * effect, panelYPadding + panelSize * y + 10 - 10 * effect, panelXPadding + panelSize * (x + 1) - 10 + 10 * effect, panelYPadding + panelSize * (y + 1) - 10 + 10 * effect, 5, 5, paint);
                }
            }
        }

        float strokeWidth = Math.min(width, height) / 100;
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(Color.GREEN);

        if (width > height) {
            int x = (int)((timeslot * 250 + tickInTimeslot) * panelSize / 250 + panelXPadding);
            paint.setAlpha(31);
            canvas.drawLine(x - strokeWidth * 2, 0, x - strokeWidth * 2, height, paint);
            paint.setAlpha(63);
            canvas.drawLine(x - strokeWidth, 0, x - strokeWidth, height, paint);
            paint.setAlpha(127);
            canvas.drawLine(x, 0, x, height, paint);
            paint.setAlpha(255);
            canvas.drawLine(x + strokeWidth, 0, x + strokeWidth, height, paint);
            paint.setAlpha(127);
            canvas.drawLine(x + strokeWidth * 2, 0, x + strokeWidth * 2, height, paint);
        } else {
            int y = (int)((timeslot * 250 + tickInTimeslot) * panelSize / 250 + panelYPadding);
            paint.setAlpha(31);
            canvas.drawLine(0, y - strokeWidth * 2, width, y - strokeWidth * 2, paint);
            paint.setAlpha(63);
            canvas.drawLine(0, y - strokeWidth, width, y - strokeWidth, paint);
            paint.setAlpha(127);
            canvas.drawLine(0, y, width, y, paint);
            paint.setAlpha(255);
            canvas.drawLine(0, y + strokeWidth, width, y + strokeWidth, paint);
            paint.setAlpha(127);
            canvas.drawLine(0, y + strokeWidth * 2, width, y + strokeWidth * 2, paint);
        }

        //Call the next frame.
        invalidate();
    }

    public void updateThermalImage(RenderedImage renderedImage) {
        short[] newThermalData = new short[numPanelsWide * numPanelsHigh];
        long newThermalTotal = 0;
        short newThermalMinimum = Short.MAX_VALUE;
        short newThermalMaximum = Short.MIN_VALUE;
        short[] pix = new short[renderedImage.pixelData().length / 2];
        ByteBuffer.wrap(renderedImage.pixelData()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pix);
        for (int x = 0; x < numPanelsWide; x++) {
            for (int y = 0; y < numPanelsHigh; y++) {
                float scaleX = renderedImage.width() * 1.0f / width;
                float scaleY = renderedImage.height() * 1.0f / height;
                int startX = (int)((panelXPadding + panelSize * x + 10) * scaleX);
                int endX = (int)((panelXPadding + panelSize * (x + 1) - 10) * scaleX);
                int startY = (int)((panelYPadding + panelSize * y + 10) * scaleY);
                int endY = (int)((panelYPadding + panelSize * (y + 1) - 10) * scaleY);
                long accumulator = 0;
                for (int x2 = startX; x2 <= endX ; x2 += Math.max(5, (endX - startX) * 0.1)) {
                    for (int y2 = startY; y2 <= endY ; y2 += Math.max(5, (endY - startY) * 0.1)) {
                        accumulator += pix[x2 + y2 * renderedImage.width()];
                    }
                }
                short result = (short)(accumulator / (endX - startX + 1) / (endY - startY + 1));
                newThermalData[x + numPanelsWide * y] = result;
                newThermalTotal += result;
                newThermalMinimum = (short)Math.min(newThermalMinimum, result);
                newThermalMaximum = (short)Math.min(newThermalMaximum, result);
            }
        }
        thermalData = newThermalData;
        thermalAverage = (short)(newThermalTotal / (numPanelsWide * numPanelsHigh));
        thermalMinimum = newThermalMinimum;
        thermalMaximum = newThermalMaximum;
    }
}
