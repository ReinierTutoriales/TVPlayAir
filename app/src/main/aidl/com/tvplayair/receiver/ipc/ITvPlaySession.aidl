package com.tvplayair.receiver.ipc;

import com.tvplayair.receiver.ipc.ITvPlayCallback;

interface ITvPlaySession {
    void registerCallback(ITvPlayCallback callback);
    void unregisterCallback(ITvPlayCallback callback);
    oneway void setProjectionVisible(boolean visible);
    oneway void setAutoOpenVisual(boolean enabled);
    void startServer();
    void updatePlaybackTelemetry(ITvPlayCallback source, long playbackId, long resumePositionMs,
                                 double durationSec, double positionSec,
                                 double livePositionSec, float rate, boolean isPlaying, boolean isBuffering, boolean isReady);
    void setDirectPlaybackIntent(ITvPlayCallback source, long playbackId, String command);
    oneway void finishDirectPlayback(ITvPlayCallback source, long playbackId);
    oneway void sendDacpCommand(ITvPlayCallback source, int audioGeneration, String command);
    oneway void configureAdvertising(int maxWidth, int maxHeight, boolean hevcEnabled, String serviceName);
}
