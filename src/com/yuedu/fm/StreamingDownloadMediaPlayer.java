package com.yuedu.fm;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;


/**
 * Created by dong on 13-6-17.
 */
public class StreamingDownloadMediaPlayer {

    public enum PlayerState {
        IDLE,
        INITIALIZED,
        PREPARING,
        PREPARED,
        STARTED,
        STOPPED,
        PAUSED,
        COMPLETED,
        END;

        @Override
        public String toString() {
            return readableMap.get(this);
        }

        private static Map<PlayerState, String> readableMap;

        static {
            readableMap = new HashMap<PlayerState, String>(9);
            readableMap.put(IDLE, "IDLE");
            readableMap.put(INITIALIZED, "INITIALIZED");
            readableMap.put(PREPARING, "PREPARING");
            readableMap.put(PREPARED, "PREPARED");
            readableMap.put(STARTED, "STARTED");
            readableMap.put(STOPPED, "STOPPED");
            readableMap.put(PAUSED, "PAUSED");
            readableMap.put(COMPLETED, "COMPLETED");
            readableMap.put(END, "END");
        }
    }

    private URL mURL;

    private PlayerState mState;
    private boolean mLooping;
    private File mCacheDir;
    private AudioTrack mAudioTrack;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private StreamingAsyncTask mStreamingTask;
    private ArrayBlockingQueue<short[]> mBuffer = new ArrayBlockingQueue<short[]>(250);
    private OnPreparedListener mPreparedListener;

    private abstract class StreamingAsyncTask extends AsyncTask<URL, Void, Void> {
        boolean blocking = false;
        boolean isPaused = false;
        boolean isStopped = false;
        boolean isPlaying = false;
        ReentrantLock pauseLock = new ReentrantLock();
        Condition unpaused = pauseLock.newCondition();

        public void pause() {
            pauseLock.lock();
            try {
                isPaused = true;
                isPlaying = false;
            } finally {
                pauseLock.unlock();
            }
        }

        public void start() {
            if (isCancelled()) {
                throw new IllegalStateException("play task has been stopped and cancelled");
            }
            if (isPaused) {
                resume();
            }
            isPlaying = true;
        }

        public void stop() {
            if (isPaused){
                resume();
            }
            isStopped = true;
            isPlaying = false;
            cancel(true);
        }

        public void resume() {
            pauseLock.lock();
            try {
                isPaused = false;
                unpaused.signalAll();
            } finally {
                pauseLock.unlock();
            }
        }
    }

    static public interface OnPreparedListener {
        void onPrepared(StreamingDownloadMediaPlayer mediaPlayer);
    }

    public PlayerState getState() {
        return mState;
    }

    public boolean isLooping() {
        return mLooping;
    }

    public File getCacheDir() {
        return mCacheDir;
    }

    public void setOnPreparedListener(OnPreparedListener mPreparedListener) {
        this.mPreparedListener = mPreparedListener;
    }

    public void setCacheDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            throw new IllegalArgumentException("input dir is null or not a directory");
        }
        mCacheDir = dir;
    }

    public void setDataSource(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("input stream is null");
        }
        if (mState != PlayerState.IDLE) {
            throw new IllegalStateException("cannot setDataSource in [" + mState + "] state");
        }
        this.mURL = url;
        this.mState = PlayerState.INITIALIZED;
    }

    public void reset() {
        mURL = null;
        mState = PlayerState.IDLE;
        mLooping = false;
        if (mAudioTrack != null && isPlaying()) {
            mAudioTrack.flush();
            mAudioTrack.stop();
        }

        if (mAudioTrack == null) {
            int minBufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            Log.d("yuedu","min buffer size "+minBufferSize);
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize, AudioTrack.MODE_STREAM);
        }
    }

    public void prepare() throws IOException, BitstreamException, DecoderException, InterruptedException {
        if (mState == PlayerState.INITIALIZED || mState == PlayerState.STOPPED || mState == PlayerState.PREPARING) {
            handleInput(mURL, new Decoder(), false);
        } else {
            throw new IllegalStateException("cannot prepare in [" + mState + "] state");
        }
    }

    protected void handleInput(final URL url, final Decoder decoder, final boolean isAsynchronous) throws IOException, BitstreamException, DecoderException, InterruptedException {
        boolean shouldCache = (getCacheDir() != null && getCacheDir().isDirectory());
        FileOutputStream fileOutputStream;
        final BufferedOutputStream bufferedOutputStream;
        if (shouldCache) {
            fileOutputStream = new FileOutputStream(new File(getCacheDir(), SystemClock.elapsedRealtime() + ".mp3"));
            bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
        } else {
            bufferedOutputStream = null;
        }
        mStreamingTask = new StreamingAsyncTask() {
            @Override
            protected Void doInBackground(URL... params) {
                URL url1 = params[0];
                HttpURLConnection connection = null;
                InputStream inputStream;
                Bitstream bitstream;
                try {
                    connection = (HttpURLConnection) url1.openConnection();
                    connection.connect();
                    inputStream = connection.getInputStream();
                    bitstream = new Bitstream(inputStream);
                    Header header;
                    boolean firstPrepared = false;
                    boolean bufferIsEmptyDuringPlaying = false;
                    while (((header = bitstream.readFrame()) != null || !mBuffer.isEmpty()) && !isStopped) {
                        if (isPaused) {
                            pauseLock.lock();
                            unpaused.await();
                        }
                        if (header != null) {//输入流已经读完，停止向buffer中写数据
                            SampleBuffer decoderBuffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                            short[] copyBuffer = new short[decoderBuffer.getBufferLength()];
                            System.arraycopy(decoderBuffer.getBuffer(), 0, copyBuffer, 0, decoderBuffer.getBufferLength());
                            Log.d("yuedu","new frame size "+copyBuffer.length);
                            if (mBuffer.remainingCapacity() > 0) {
                                writeDecodedFrameToBuffer(mBuffer, copyBuffer);
                            }
                            if (bufferedOutputStream != null) {
                                writeDecodedFrameToFile(bufferedOutputStream, copyBuffer);
                            }
                        }
                        //when buffer is empty,we should wait for enough data
                        bufferIsEmptyDuringPlaying = mBuffer.isEmpty() && mBuffer.size() < mBuffer.remainingCapacity()/2;

                        if (isPlaying && !bufferIsEmptyDuringPlaying) {
                            writeDecodedFrameFromBufferToTrack(mBuffer, mAudioTrack);
                        }



                        if (mBuffer.remainingCapacity() == 0 && !firstPrepared) {
                            Log.d("yuedu", "buffer is prepared!!!!!!!!!!!!!");
                            firstPrepared = true;
                            if (blocking) {
                                notifyAll();
                                blocking = false;
                            }
                            notifyPrepared();
                        }
                        bitstream.closeFrame();
                    }
                    if (blocking) {
                        notifyAll();
                        blocking = false;
                    }
                    if (bufferedOutputStream != null) {
                        bufferedOutputStream.flush();
                        bufferedOutputStream.close();
                    }
                    if (bitstream != null) {
                        bitstream.close();
                    }
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (DecoderException e) {
                    e.printStackTrace();
                } catch (BitstreamException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (pauseLock.isLocked()) {
                        pauseLock.unlock();
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
                return null;
            }
        };
        synchronized (mStreamingTask) {
            mStreamingTask.execute(url);
            if (isAsynchronous) {
                mState = PlayerState.PREPARING;
            } else {
                mStreamingTask.wait();
                mStreamingTask.blocking = true;
            }
        }
    }

    private void notifyPrepared() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mState = PlayerState.PREPARED;
                if (mPreparedListener != null) {
                    mPreparedListener.onPrepared(StreamingDownloadMediaPlayer.this);
                }
            }
        });
    }

    static long totalTime = 0;
    static int callNumber = 0;

    private void writeDecodedFrameFromBufferToTrack(final ArrayBlockingQueue<short[]> buffer, final AudioTrack track) {
        try {
            long start = SystemClock.elapsedRealtime();
            short[] frame = readDecodedFrameFromBuffer(buffer);
            track.write(frame, 0, frame.length);
            long end = SystemClock.elapsedRealtime();
            totalTime += end - start;
            callNumber += 1;
//            Log.d("yuedu","writeDecodedFrameFromBufferToTrack time "+(end - start)+" avg "+totalTime/callNumber);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private void writeDecodedFrameToBuffer(final ArrayBlockingQueue<short[]> buffer, short[] frameData) throws InterruptedException {
        buffer.put(frameData);
    }

    private short[] readDecodedFrameFromBuffer(final ArrayBlockingQueue<short[]> buffer) throws InterruptedException {
        short[] frame = buffer.take();
        return frame;
    }

    protected void writeDecodedFrameToFile(final OutputStream outputStream, short[] frameData) throws IOException {
        for (short s : frameData) {
            outputStream.write((byte) (s & 0xff));
            outputStream.write((byte) ((s >> 8) & 0xff));
        }
    }


    public void prepareAsync() throws DecoderException, InterruptedException, BitstreamException, IOException {
        if (mState == PlayerState.INITIALIZED || mState == PlayerState.STOPPED) {
            handleInput(mURL, new Decoder(), true);
        } else {
            throw new IllegalStateException("cannot prepareAsync in [" + mState + "] state");
        }
    }

    public void start() {
        if (mState == PlayerState.PREPARED || mState == PlayerState.COMPLETED || mState == PlayerState.PAUSED) {
            mState = PlayerState.STARTED;
            mAudioTrack.play();
            mStreamingTask.start();
        } else {
            throw new IllegalStateException("cannot start in [" + mState + "] state");
        }
    }

    public void pause() {
        if (mState == PlayerState.STARTED) {
            mState = PlayerState.PAUSED;
            mAudioTrack.pause();
            mStreamingTask.pause();
        } else {
            throw new IllegalStateException("cannot pause in [" + mState + "] state");
        }
    }

    public void stop() {
        if (mState == PlayerState.PREPARED || mState == PlayerState.COMPLETED || mState == PlayerState.PAUSED || mState == PlayerState.STARTED) {
            mState = PlayerState.STOPPED;
            mBuffer.clear();
            mStreamingTask.stop();
            mAudioTrack.flush();
            mAudioTrack.stop();
        } else {
            throw new IllegalStateException("cannot stop in [" + mState + "] state");
        }
    }

    public void seekTo(long millisecond) {
        //TODO
    }

    public long getCurrentPosition() {
        //TODO
        return 0;
    }

    public long getDuration() {
        //TODO
        return 0;
    }

    public boolean isPlaying() {
        return mState == PlayerState.STARTED;
    }

    public boolean isPaused() {
        return mState == PlayerState.PAUSED;
    }

    public boolean isCompleted() {
        return mState == PlayerState.COMPLETED;
    }

    public void release() {
        if (mAudioTrack != null) {
            mAudioTrack.flush();
            mAudioTrack.stop();
            mAudioTrack.release();
            mAudioTrack = null;
        }
        mCacheDir = null;
        mHandler = null;
        mURL = null;
        mPreparedListener = null;
        if (mStreamingTask != null) {
            if (!mStreamingTask.isCancelled()) {
                mStreamingTask.cancel(true);
            }
            mStreamingTask.notifyAll();
            mStreamingTask = null;
        }
    }

}
