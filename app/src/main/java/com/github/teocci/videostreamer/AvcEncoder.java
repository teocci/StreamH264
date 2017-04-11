package com.github.teocci.videostreamer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import com.github.teocci.videostreamer.utils.LogHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AvcEncoder
{
    private final static String TAG = AvcEncoder.class.getSimpleName();

    private final static String MIME_TYPE = "video/avc";
    private final static int I_FRAME_INTERVAL = 1;

    private MediaCodec mediaCodec;
    private int width;
    private int height;
    private int timeoutUSec = 10000;
    private long frameIndex = 0;
    private byte[] spsPpsInfo = null;
    private byte[] yuv420 = null;
    private int frameRate;
    private int yStride;
    private int cStride;
    private int ySize;
    private int cSize;
    private int halfWidth;
    private int halfHeight;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    public AvcEncoder()
    {
    }

    public boolean init(int width, int height, int framerate, int bitrate)
    {
        try {
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        } catch (IOException e) {
            LogHelper.e(TAG, "IOException in AvcEncoder.init: " + e);
            e.printStackTrace();
            return false;
        }

        boolean isSupport = false;
        int colorFormat = 0;
        MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(MIME_TYPE);
        for (int i = 0; i < capabilities.colorFormats.length && colorFormat == 0; i++) {
            int format = capabilities.colorFormats[i];
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                isSupport = true;
                break;
            }
        }
        if (!isSupport)
            return false;

        this.width = width;
        this.height = height;
        this.halfWidth = width / 2;
        this.halfHeight = height / 2;
        this.frameRate = framerate;

        this.yStride = (int) Math.ceil(width / 16.0f) * 16;
        this.cStride = (int) Math.ceil(width / 32.0f) * 16;
        this.ySize = yStride * height;
        this.cSize = cStride * height / 2;

        this.yuv420 = new byte[width * height * 3 / 2];

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodec.start();
        return true;
    }

    public void close()
    {
        try {
            mediaCodec.stop();
            mediaCodec.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public byte[] offerEncoder(byte[] input)
    {
        YV12toYUV420PackedSemiPlanar(input, yuv420, width, height);
        try {
            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
            ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                long pts = computePresentationTime(frameIndex, frameRate);
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(yuv420, 0, yuv420.length);
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, yuv420.length, pts, 0);
                frameIndex++;
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUSec);

            while (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                byte[] outData = new byte[bufferInfo.size];
                outputBuffer.get(outData);

                if (spsPpsInfo == null) {
                    ByteBuffer spsPpsBuffer = ByteBuffer.wrap(outData);
                    if (spsPpsBuffer.getInt() == 0x00000001) {
                        spsPpsInfo = new byte[outData.length];
                        System.arraycopy(outData, 0, spsPpsInfo, 0, outData.length);
                    } else {
                        return null;
                    }
                } else {
                    outputStream.write(outData);
                }

                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUSec);
            }
            byte[] ret = outputStream.toByteArray();
            if (ret.length > 5 && ret[4] == 0x65) //key frame need to add sps pps
            {
                outputStream.reset();
                outputStream.write(spsPpsInfo);
                outputStream.write(ret);
            }

        } catch (IOException e) {
            LogHelper.e(TAG, "IOException in offerEncoder: " + e);
            e.printStackTrace();
        }
        byte[] ret = outputStream.toByteArray();
        outputStream.reset();
        return ret;
    }

    public byte[] YV12toYUV420PackedSemiPlanar(final byte[] input, final byte[] output, final int width, final int height)
    {
        for (int i = 0; i < height; i++)
            System.arraycopy(input, yStride * i, output, yStride * i, yStride); // Y

        for (int i = 0; i < halfHeight; i++) {
            for (int j = 0; j < halfWidth; j++) {
                output[ySize + (i * halfWidth + j) * 2] = input[ySize + cSize + i * cStride + j]; // Cb (U)
                output[ySize + (i * halfWidth + j) * 2 + 1] = input[ySize + i * cStride + j]; // Cr (V)
            }
        }

        return output;
    }

    private static MediaCodecInfo selectCodec(String mimeType)
    {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder())
                continue;

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.startsWith("video/")) {
                    LogHelper.e(TAG, "selectCodec: " + codecInfo.getName());
                    return codecInfo;
                }
            }
        }

        return null;
    }

    private long computePresentationTime(long frameIndex, int frameRate)
    {
        return 132 + frameIndex * 1000000 / frameRate;
    }
}
