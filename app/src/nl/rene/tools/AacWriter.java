package nl.rene.tools;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 16-bit PCM (mono) als AAC in een .m4a-bestand, met de encoder van Android. Ongeveer tien keer kleiner dan WAV
 * (32 kbit/s is ruim genoeg voor spraak en voor whisper). Gooit een fout als de telefoon het niet kan; de
 * aanroeper bewaart dan gewoon een WAV.
 */
final class AacWriter {

    private AacWriter() { }

    static final int BITRATE = 32000;

    static void write(short[] pcm, int rate, File out) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1, pos = 0;
            boolean inputDone = false, outputDone = false;
            long guard = System.currentTimeMillis() + 60_000;
            while (!outputDone) {
                if (System.currentTimeMillis() > guard) throw new Exception("AAC-encoder reageert niet");
                if (!inputDone) {
                    int ix = enc.dequeueInputBuffer(10_000);
                    if (ix >= 0) {
                        ByteBuffer b = enc.getInputBuffer(ix);
                        b.clear();
                        int n = Math.min(pcm.length - pos, b.remaining() / 2);
                        long pts = pos * 1_000_000L / rate;
                        if (n <= 0) {
                            enc.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            b.order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < n; i++) b.putShort(pcm[pos + i]);
                            pos += n;
                            enc.queueInputBuffer(ix, 0, n * 2, pts, 0);
                        }
                    }
                }
                int ox = enc.dequeueOutputBuffer(info, 10_000);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start(); muxStarted = true;
                } else if (ox >= 0) {
                    ByteBuffer o = enc.getOutputBuffer(ox);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted && o != null) {
                        o.position(info.offset); o.limit(info.offset + info.size);
                        mux.writeSampleData(track, o, info);
                    }
                    enc.releaseOutputBuffer(ox, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (!muxStarted) throw new Exception("Geen geluid geschreven");
        } finally {
            try { enc.stop(); } catch (Throwable ignored) { }
            try { enc.release(); } catch (Throwable ignored) { }
            if (mux != null) {
                try { if (muxStarted) mux.stop(); } catch (Throwable ignored) { }
                try { mux.release(); } catch (Throwable ignored) { }
            }
        }
    }
}
