package com.tvplayair.receiver.ipc;

import android.os.ParcelFileDescriptor;

oneway interface ITvPlayCallback {
    void onSharedMemoryReady(in ParcelFileDescriptor videoFd, long videoSize,
                             in ParcelFileDescriptor audioFd, long audioSize);
    void onSessionUnavailable();
}
