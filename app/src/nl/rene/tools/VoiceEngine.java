package nl.rene.tools;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;

/** Dezelfde CPU-spraakmotor voor alle onderdelen. Alleen op de voorleesworker openen/gebruiken/sluiten. */
final class VoiceEngine implements AutoCloseable {
    static final String ENGINE="sherpa-onnx 1.13.8 · Piper VITS";
    private static boolean loaded;
    private final OfflineTts tts;
    static synchronized void load(){if(!loaded){LibraryLoader.setAutoLoadEnabled(false);System.loadLibrary("onnxruntime");System.loadLibrary("sherpa-onnx-jni");loaded=true;}}
    VoiceEngine(File dir,VoiceModels.Voice voice){
        load();
        OfflineTtsVitsModelConfig vits=OfflineTtsVitsModelConfig.builder().setModel(new File(dir,voice.model).getPath()).setTokens(new File(dir,"tokens.txt").getPath()).setDataDir(new File(dir,"espeak-ng-data").getPath()).build();
        OfflineTtsModelConfig model=OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(2).setProvider("cpu").setDebug(false).build();
        tts=new OfflineTts(OfflineTtsConfig.builder().setModel(model).setMaxNumSentences(1).build());
    }
    int rate(){return tts.getSampleRate();}
    float[] generate(String text,float speed){return tts.generate(text,0,speed).getSamples();}
    @Override public void close(){tts.release();}
}
