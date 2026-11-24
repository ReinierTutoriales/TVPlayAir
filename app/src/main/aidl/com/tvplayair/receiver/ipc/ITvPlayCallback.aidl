package com.tvplayair.receiver.ipc;

import android.os.ParcelFileDescriptor;

oneway interface ITvPlayCallback {
    void onSharedMemoryReady(in ParcelFileDescriptor videoFd, long videoSize,
                             in ParcelFileDescriptor audioFd, long audioSize,
                             int generationId, long activeDirectPlaybackId);
    void onSessionSnapshot(int generationId, long activeDirectPlaybackId);
    void onSessionUnavailable();
    void onVideoConfigChanged(String mimeType, int width, int height, int generationId,
                              in byte[] codecConfig, boolean opensProjection);
    void onDirectVideoUrl(String url, double startPosition, long playbackId,
                          long resumePositionMs, boolean playWhenReady);
    void onDirectVideoCommand(String command, double value, long playbackId);
    void onClientConnected(String clientName, String dacpId, String activeRemote);
    void onMirrorStreamEnded(int generationId);
    void onAudioStreamStarted(int generationId);
    void onNowPlaying(int generationId, boolean available, String title, String artist, String album, in byte[] artwork);
    void onAudioControlsAvailable(boolean available);
    void onAudioStreamStopped(int generationId);
    void onReceiverAvailability(int state);
}
