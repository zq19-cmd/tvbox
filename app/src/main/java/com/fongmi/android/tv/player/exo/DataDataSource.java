package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Minimal DataSource that serves a provided byte[] as the response for any requested DataSpec.
 * Used to provide an in-memory manifest (e.g. decoded base64 DASH MPD) to ExoPlayer.
 */
public class DataDataSource implements DataSource {

    private final byte[] data;
    private ByteArrayInputStream stream;
    private Uri uri;

    public DataDataSource(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        this.stream = new ByteArrayInputStream(data);
        return data.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (stream == null) return C.RESULT_END_OF_INPUT;
        int r = stream.read(buffer, offset, readLength);
        return r == -1 ? C.RESULT_END_OF_INPUT : r;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // No-op: this DataSource serves in-memory data and does not need transfer callbacks.
    }

    @Override
    public void close() throws IOException {
        try {
            if (stream != null) stream.close();
        } catch (Exception e) {
            Log.w("DataDataSource", "close error", e);
        } finally {
            stream = null;
            uri = null;
        }
    }
}
