package com.fongmi.android.tv.player.exo;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
// avoid direct DashMediaSource/DataSourceFactory imports to prevent compile errors when dash module is optional
import androidx.media3.common.util.UriUtil;

import android.util.Base64;
import android.net.Uri;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.util.HashMap;
import java.util.Map;

public class MediaSourceFactory implements MediaSource.Factory {

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;

    public MediaSourceFactory() {
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        MediaItem mi = setHeader(mediaItem);

        // support data:application/dash+xml;base64,.... minimal handling
        try {
            Uri uri = mi.playbackProperties == null ? Uri.parse(mi.mediaId) : mi.playbackProperties.uri;
            String s = uri.toString();
            String prefix = "data:application/dash+xml;base64,";
            if (s.startsWith(prefix)) {
                String b64 = s.substring(prefix.length());
                byte[] mpdBytes = Base64.decode(b64, Base64.DEFAULT);
                try {
                    // write MPD to a temporary file in cache and let the existing DefaultMediaSourceFactory handle it
                    java.io.File cacheDir = App.get().getCacheDir();
                    java.io.File mpdFile = new java.io.File(cacheDir, "data_mpd_" + System.currentTimeMillis() + ".mpd");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(mpdFile);
                    fos.write(mpdBytes);
                    fos.flush();
                    fos.close();
                    Uri fileUri = Uri.fromFile(mpdFile);
                    return defaultMediaSourceFactory.createMediaSource(mi.buildUpon().setUri(fileUri).build());
                } catch (Exception e) {
                    // fallback to default behavior
                }
            }
        } catch (Exception ignored) {}

        if (mi.mediaId.contains("***") && mi.mediaId.contains("|||")) {
            return createConcatenatingMediaSource(mi);
        } else {
            return defaultMediaSourceFactory.createMediaSource(mi);
        }
    }

    private MediaItem setHeader(MediaItem mediaItem) {
        Map<String, String> headers = new HashMap<>();
        if (mediaItem.requestMetadata.extras == null) return mediaItem;
        for (String key : mediaItem.requestMetadata.extras.keySet()) headers.put(key, mediaItem.requestMetadata.extras.get(key).toString());
        getHttpDataSourceFactory().setDefaultRequestProperties(headers);
        return mediaItem;
    }

    private MediaSource createConcatenatingMediaSource(MediaItem mediaItem) {
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
        for (String split : mediaItem.mediaId.split("\\*\\*\\*")) {
            String[] info = split.split("\\|\\|\\|");
            if (info.length >= 2) builder.add(defaultMediaSourceFactory.createMediaSource(mediaItem.buildUpon().setUri(Uri.parse(info[0])).build()), Long.parseLong(info[1]));
        }
        return builder.build();
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        return extractorsFactory;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) dataSourceFactory = buildReadOnlyCacheDataSource(new DefaultDataSource.Factory(App.get(), getHttpDataSourceFactory()));
        return dataSourceFactory;
    }

    private CacheDataSource.Factory buildReadOnlyCacheDataSource(DataSource.Factory upstreamFactory) {
        return new CacheDataSource.Factory().setCache(CacheManager.get().getCache()).setUpstreamDataSourceFactory(upstreamFactory).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = new OkHttpDataSource.Factory(OkHttp.client());
        return httpDataSourceFactory;
    }
}
