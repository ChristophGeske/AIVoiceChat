package com.whispertflite.engine;

import android.content.Context;
import android.util.Log;
import com.whispertflite.asr.RecordBuffer;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.WhisperUtil;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WhisperEngineJava implements WhisperEngine {
    private final String TAG = "WhisperEngineJava";
    private final WhisperUtil mWhisperUtil = new WhisperUtil();
    private final Context mContext;
    private boolean mIsInitialized = false;
    private Interpreter mInterpreter = null;

    public WhisperEngineJava(Context context) {
        mContext = context;
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public void initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        loadModel(modelPath);
        Log.d(TAG, "Model is loaded..." + modelPath);

        boolean ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath);
        if (ret) {
            mIsInitialized = true;
            Log.d(TAG, "Filters and Vocab are loaded..." + vocabPath);
        } else {
            mIsInitialized = false;
            Log.d(TAG, "Failed to load Filters and Vocab...");
        }
    }

    @Override
    public void deinitialize() {
        if (mInterpreter != null) {
            mInterpreter.setCancelled(true);
            mInterpreter.close();
            mInterpreter = null;
        }
    }

    @Override
    public WhisperResult processRecordBuffer(Whisper.Action mAction, int mLangToken) {
        Log.d(TAG, "Calculating Mel spectrogram...");
        float[] melSpectrogram = getMelSpectrogram();
        Log.d(TAG, "Mel spectrogram is calculated...!");

        WhisperResult whisperResult = runInference(melSpectrogram, mAction, mLangToken);
        Log.d(TAG, "Inference is executed...!");

        return whisperResult;
    }

    private void loadModel(String modelPath) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(modelPath);
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = 0;
        long declaredLength = fileChannel.size();
        ByteBuffer tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

        Interpreter.Options options = new Interpreter.Options();
        options.setUseXNNPACK(false);
        options.setNumThreads(Runtime.getRuntime().availableProcessors());
        options.setCancellable(true);

        mInterpreter = new Interpreter(tfliteModel, options);
        Log.d(TAG, "=== MODEL LOADED ===");
        Log.d(TAG, "Available signatures: " + Arrays.toString(mInterpreter.getSignatureKeys()));
    }

    private float[] getMelSpectrogram() {
        float[] samples = RecordBuffer.getSamples();
        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);

        int cores = Runtime.getRuntime().availableProcessors();
        return mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, copyLength, cores);
    }

    private WhisperResult runInference(float[] inputData, Whisper.Action mAction, int mLangToken) {
        Log.d(TAG, "=== STARTING INFERENCE ===");
        Log.d(TAG, "Signatures: " + Arrays.toString(mInterpreter.getSignatureKeys()));

        Tensor inputTensor = mInterpreter.getInputTensor(0);
        Tensor outputTensor = mInterpreter.getOutputTensor(0);
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32);

        int inputSize = inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * Float.BYTES;
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(inputSize);
        inputBuffer.order(ByteOrder.nativeOrder());
        for (float input : inputData) {
            inputBuffer.putFloat(input);
        }

        String signature_key = "serving_default";
        if (mAction == Whisper.Action.TRANSLATE) {
            if (Arrays.asList(mInterpreter.getSignatureKeys()).contains("serving_translate"))
                signature_key = "serving_translate";
        } else if (mAction == Whisper.ACTION_TRANSCRIBE) {
            if (Arrays.asList(mInterpreter.getSignatureKeys()).contains("serving_transcribe_lang") && mLangToken != -1)
                signature_key = "serving_transcribe_lang";
            else if (Arrays.asList(mInterpreter.getSignatureKeys()).contains("serving_transcribe"))
                signature_key = "serving_transcribe";
        }

        Log.d(TAG, "Selected signature: " + signature_key);

        Map<String, Object> inputsMap = new HashMap<>();
        String[] inputs = mInterpreter.getSignatureInputs(signature_key);
        Log.d(TAG, "Required inputs: " + Arrays.toString(inputs));
        inputsMap.put(inputs[0], inputBuffer);

        if (signature_key.equals("serving_transcribe_lang")) {
            Log.d(TAG, "Adding language token: " + mLangToken);
            IntBuffer langTokenBuffer = IntBuffer.allocate(1);
            langTokenBuffer.put(mLangToken);
            langTokenBuffer.rewind();
            inputsMap.put(inputs[1], langTokenBuffer);
        }

        Map<String, Object> outputsMap = new HashMap<>();
        String[] outputs = mInterpreter.getSignatureOutputs(signature_key);
        outputsMap.put(outputs[0], outputBuffer.getBuffer());

        try {
            Log.d(TAG, "Calling runSignature...");
            mInterpreter.runSignature(inputsMap, outputsMap, signature_key);
            Log.d(TAG, "Inference successful!");
        } catch (Exception e) {
            Log.e(TAG, "=== INFERENCE FAILED ===");
            Log.e(TAG, "Signature: " + signature_key);
            Log.e(TAG, "Required inputs: " + Arrays.toString(inputs));
            Log.e(TAG, "Error: " + e.getMessage(), e);
            return new WhisperResult("", "", mAction);
        }

        String language = "";
        Whisper.Action task = null;
        int outputLen = outputBuffer.getIntArray().length;
        Log.d(TAG, "output_len: " + outputLen);
        List<byte[]> resultArray = new ArrayList<>();

        for (int i = 0; i < outputLen; i++) {
            int token = outputBuffer.getBuffer().getInt();
            if (token == mWhisperUtil.getTokenEOT())
                break;

            if (token < mWhisperUtil.getTokenEOT()) {
                byte[] wordBytes = mWhisperUtil.getWordFromToken(token);
                if (wordBytes != null) {
                    resultArray.add(wordBytes);
                }
            } else {
                if (token == mWhisperUtil.getTokenTranscribe()){
                    Log.d(TAG, "Task: Transcription");
                    task = Whisper.Action.TRANSCRIBE;
                }

                if (token == mWhisperUtil.getTokenTranslate()){
                    Log.d(TAG, "Task: Translation");
                    task = Whisper.Action.TRANSLATE;
                }

                if (token >= 50259 && token <= 50357){
                    language = InputLang.INSTANCE.getLanguageCodeById(token);
                    Log.d(TAG, "Detected language: " + language);
                }
            }
        }

        int totalLength = 0;
        for (byte[] byteArray : resultArray) {
            totalLength += byteArray.length;
        }

        byte[] combinedBytes = new byte[totalLength];
        int offset = 0;
        for (byte[] byteArray : resultArray) {
            System.arraycopy(byteArray, 0, combinedBytes, offset, byteArray.length);
            offset += byteArray.length;
        }

        return new WhisperResult(new String(combinedBytes, StandardCharsets.UTF_8), language, task);
    }
}