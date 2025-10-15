package com.whispertflite.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class Recorder {
    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    private static final String TAG = "Recorder";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);
    private RecorderListener mListener;
    private Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }

    public void start() {
        if (mInProgress.get()) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        mInProgress.set(true);
        workerThread = new Thread(this::recordAudio);
        workerThread.start();
    }

    public void stop() {
        mInProgress.set(false);
        if (workerThread != null) {
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Thread interrupted", e);
            }
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null) {
            mListener.onUpdateReceived(message);
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendUpdate("RECORD_AUDIO permission not granted");
            mInProgress.set(false);
            return;
        }

        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);

        AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRateInHz, channelConfig, audioFormat, bufferSize);
        audioRecord.startRecording();
        sendUpdate(MSG_RECORDING);

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        byte[] audioData = new byte[bufferSize];
        int bytesForThirtySeconds = sampleRateInHz * 2 * 30; // 16-bit PCM for 30 seconds

        while (mInProgress.get() && outputBuffer.size() < bytesForThirtySeconds) {
            int bytesRead = audioRecord.read(audioData, 0, bufferSize);
            if (bytesRead > 0) {
                outputBuffer.write(audioData, 0, bytesRead);
            }
        }

        audioRecord.stop();
        audioRecord.release();

        if (outputBuffer.size() > 0) {
            RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }
        mInProgress.set(false); // Ensure recording flag is reset
    }
}