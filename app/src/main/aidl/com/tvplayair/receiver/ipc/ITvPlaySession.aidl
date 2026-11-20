package com.tvplayair.receiver.ipc;

import android.os.ParcelFileDescriptor;
import com.tvplayair.receiver.ipc.ITvPlayCallback;

interface ITvPlaySession {
    void registerCallback(ITvPlayCallback callback);
    void unregisterCallback(ITvPlayCallback callback);
    void startServer();
    void stopServer();
    void updatePlaybackTelemetry(double durationSec, double positionSec,
                                 float rate, boolean isPlaying, boolean isBuffering);
    void sendDacpCommand(String command);
    void configureAdvertising(int maxWidth, int maxHeight, boolean hevcEnabled);
}
