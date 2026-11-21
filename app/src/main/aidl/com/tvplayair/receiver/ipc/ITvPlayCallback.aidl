package com.tvplayair.receiver.ipc;

import android.os.ParcelFileDescriptor;

oneway interface ITvPlayCallback {
    void onSharedMemoryReady(in ParcelFileDescriptor videoFd, long videoSize,
                             in ParcelFileDescriptor audioFd, long audioSize,
                             int generationId);
    void onSessionUnavailable();
    void onVideoConfigChanged(String mimeType, int width, int height, int generationId);
    void onDirectVideoUrl(String url, double startPosition);
    void onDirectVideoCommand(String command, double value);
    void onClientConnected(String clientName, String dacpId, String activeRemote);
    void onClientDisconnected();
}
