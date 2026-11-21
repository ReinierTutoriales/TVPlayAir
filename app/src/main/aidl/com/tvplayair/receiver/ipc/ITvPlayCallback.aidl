package com.tvplayair.receiver.ipc;

import android.os.ParcelFileDescriptor;

oneway interface ITvPlayCallback {
    void onSharedMemoryReady(in ParcelFileDescriptor videoFd, long videoSize,
                             in ParcelFileDescriptor audioFd, long audioSize,
                             int generationId);
    void onSessionUnavailable();
    void onVideoConfigChanged(String mimeType, int width, int height, int generationId);
    void onDirectVideoUrl(String url, double startPosition, long playbackId);
    void onDirectVideoCommand(String command, double value);
    void onClientConnected(String clientName, String dacpId, String activeRemote);
    void onClientDisconnected();
    void onAudioStreamStarted(int generationId);
    void onNowPlaying(boolean available, String title, String artist, String album, in byte[] artwork);
}
