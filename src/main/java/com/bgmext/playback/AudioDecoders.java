package com.bgmext.playback;

import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * 把自定义音频文件变成原版引擎能消费的 PCM 流。
 *
 * 硬性要求（每一条都是踩坑换来的）：
 *
 * 1. **必须是 direct（直连）缓冲**。原版 OggAudioStream 用 MemoryUtil.memAlloc，
 *    而 ByteBuffer.wrap(byte[]) 是堆缓冲——传给 OpenAL 时 JNI 拿不到地址，
 *    会传空指针 + 非零长度，导致 EXCEPTION_ACCESS_VIOLATION 崩掉整个 JVM。
 *
 * 2. **缓冲归音频流自己所有**。原版 SoundBuffer 只调 alBufferData、不会释放缓冲；
 *    OggAudioStream 是在 close() 里自己 memFree。所以用一个小缓冲池轮换复用，
 *    close() 时统一释放；每次 read 都新建会持续泄漏。
 *    用**池**而不是单个缓冲，是为了防止连续几次 read 的内容互相覆盖。
 *
 * 3. **必须流式且不能丢字节**。整首解码缓存会 OutOfMemoryError；
 *    而输出超过一块时截断，就会每块丢几百个采样——听起来是周期性的「嘟嘟」爆音。
 *
 * 4. **统一输出 16bit 小端 PCM**。8/24/32bit 或浮点数据直接喂给 OpenAL
 *    会被按 16bit 解读，全是噪音。
 */
public final class AudioDecoders {

    private static final int MAX_CHUNK = 16384;
    private static final int POOL_SIZE = 4;

    private AudioDecoders() {
    }

    public static AudioStream open(CustomAudioRegistry.Source source) throws Exception {
        // 原版资源：一定是 ogg，直接交给原版流式解码器
        if (source.resource() != null) {
            net.minecraft.server.packs.resources.Resource resource = net.minecraft.client.Minecraft
                    .getInstance().getResourceManager()
                    .getResource(source.resource())
                    .orElseThrow(() -> new java.io.FileNotFoundException(source.resource().toString()));
            return new OggAudioStream(resource.open());
        }

        Path file = source.file();
        if (file == null) {
            throw new IllegalArgumentException("empty audio source");
        }

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";

        switch (ext) {
            case "ogg":
                return new OggAudioStream(new FileInputStream(file.toFile()));
            case "mp3":
                return openMp3(file);
            case "wav":
            case "flac":
                return openViaJdk(file);
            default:
                throw new UnsupportedOperationException("Unsupported format: " + ext);
        }
    }

    /** 向下对齐到 frameSize 的整数倍，避免 OpenAL 读到半个采样帧 */
    private static int align(int length, int frameSize) {
        if (frameSize <= 1) return length;
        int aligned = length - (length % frameSize);
        return aligned > 0 ? aligned : frameSize;
    }

    /** 可轮换复用的 direct 缓冲池；缓冲所有权归流，close() 时释放 */
    private static final class DirectPool {
        private final int frameSize;
        private final ByteBuffer[] buffers = new ByteBuffer[POOL_SIZE];
        private int next;

        DirectPool(int frameSize) {
            this.frameSize = frameSize;
        }

        ByteBuffer put(byte[] src, int length) {
            int want = align(length, frameSize);
            if (want <= 0) want = frameSize;

            int index = next;
            next = (next + 1) % POOL_SIZE;

            ByteBuffer buffer = buffers[index];
            if (buffer == null || buffer.capacity() < want) {
                if (buffer != null) MemoryUtil.memFree(buffer);
                buffer = MemoryUtil.memAlloc(want);
                buffers[index] = buffer;
            }
            buffer.clear();
            buffer.put(src, 0, want);
            buffer.flip();
            return buffer;
        }

        void free() {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (buffers[i] != null) {
                    MemoryUtil.memFree(buffers[i]);
                    buffers[i] = null;
                }
            }
        }
    }

    /** 简单的字节队列：取出前 n 字节后剩下的保留，不丢数据 */
    private static final class ByteQueue {
        private byte[] data = new byte[MAX_CHUNK * 2];
        private int length;

        int size() {
            return length;
        }

        void add(byte[] src, int off, int n) {
            if (n <= 0) return;
            if (length + n > data.length) {
                int capacity = Math.max(data.length * 2, length + n);
                byte[] grown = new byte[capacity];
                System.arraycopy(data, 0, grown, 0, length);
                data = grown;
            }
            System.arraycopy(src, off, data, length, n);
            length += n;
        }

        /** 取走前 n 字节，余下的前移 */
        byte[] take(int n) {
            byte[] out = new byte[n];
            System.arraycopy(data, 0, out, 0, n);
            System.arraycopy(data, n, data, 0, length - n);
            length -= n;
            return out;
        }
    }

    /** 用 JDK 的流式 AudioInputStream 包装（wav/flac），并统一转成 16bit 小端 PCM */
    private static AudioStream openViaJdk(Path file) throws Exception {
        AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile());

        // 强制转成 16bit 小端 PCM：8/24/32bit 或浮点直接喂给 OpenAL 会变噪音
        AudioFormat sourceFormat = source.getFormat();
        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.getSampleRate(),
                16,
                Math.max(1, sourceFormat.getChannels()),
                Math.max(1, sourceFormat.getChannels()) * 2,
                sourceFormat.getSampleRate(),
                false);

        AudioInputStream converted;
        try {
            converted = AudioSystem.getAudioInputStream(target, source);
        } catch (Exception e) {
            converted = source; // 转换器不可用就原样用
        }
        final AudioInputStream in = converted;

        AudioFormat format = in.getFormat();
        int frameSize = Math.max(2, format.getFrameSize());
        DirectPool pool = new DirectPool(frameSize);
        byte[] tmp = new byte[MAX_CHUNK];

        return new AudioStream() {
            @Override
            public AudioFormat getFormat() {
                return format;
            }

            @Override
            public ByteBuffer read(int size) throws IOException {
                int want = align(Math.min(Math.max(size, frameSize), tmp.length), frameSize);
                int n = in.read(tmp, 0, want);
                if (n <= 0) return null;
                n = align(n, frameSize);
                if (n <= 0) return null;
                return pool.put(tmp, n);
            }

            @Override
            public void close() throws IOException {
                pool.free();
                in.close();
            }
        };
    }

    /** JLayer 逐帧解码，只保留当前这一小块 */
    private static AudioStream openMp3(Path file) throws Exception {
        javazoom.jl.decoder.Bitstream bitstream =
                new javazoom.jl.decoder.Bitstream(new FileInputStream(file.toFile()));
        javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();

        javazoom.jl.decoder.Header first = bitstream.readFrame();
        if (first == null) throw new IllegalStateException("empty mp3");
        int channels = first.mode() == javazoom.jl.decoder.Header.SINGLE_CHANNEL ? 1 : 2;
        int sampleRate = first.frequency();

        ByteQueue queue = new ByteQueue();
        appendSamples(queue, (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(first, bitstream));
        bitstream.closeFrame();

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        int frameSize = channels * 2;
        DirectPool pool = new DirectPool(frameSize);

        return new AudioStream() {
            @Override
            public AudioFormat getFormat() {
                return format;
            }

            @Override
            public ByteBuffer read(int size) throws IOException {
                int want = align(Math.min(Math.max(size, frameSize), MAX_CHUNK), frameSize);

                // 只解到「够一块」为止
                while (queue.size() < want) {
                    javazoom.jl.decoder.Header frame;
                    try {
                        frame = bitstream.readFrame();
                    } catch (Exception e) {
                        break;
                    }
                    if (frame == null) break;

                    try {
                        javazoom.jl.decoder.SampleBuffer samples = (javazoom.jl.decoder.SampleBuffer)
                                decoder.decodeFrame(frame, bitstream);
                        appendSamples(queue, samples);
                    } catch (Exception e) {
                        // 跳过坏帧
                    }
                    try {
                        bitstream.closeFrame();
                    } catch (Exception ignored) {
                    }
                }

                if (queue.size() == 0) return null;

                // 关键：只取走 want 字节，**余下的留到下次**，不能整块截断丢弃
                int n = align(Math.min(want, queue.size()), frameSize);
                if (n <= 0) n = Math.min(queue.size(), frameSize);
                byte[] out = queue.take(n);
                return pool.put(out, n);
            }

            @Override
            public void close() throws IOException {
                pool.free();
                try {
                    bitstream.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    /** 取有效样本数（不能用 getBuffer().length，那是复用数组的容量） */
    private static void appendSamples(ByteQueue queue, javazoom.jl.decoder.SampleBuffer samples) {
        short[] buffer = samples.getBuffer();
        int count = samples.getBufferLength();
        if (count <= 0) return;
        if (count > buffer.length) count = buffer.length;

        ByteBuffer bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            bb.putShort(buffer[i]);
        }
        queue.add(bb.array(), 0, bb.array().length);
    }
}