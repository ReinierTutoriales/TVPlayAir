package com.tvplayair.receiver.ipc;

import android.os.ParcelFileDescriptor;
import com.tvplayair.receiver.ipc.ITvPlayCallback;

interface ITvPlaySession {
    void registerCallback(ITvPlayCallback callback);
    void unregisterCallback(ITvPlayCallback callback);
    void startServer();
    void stopServer();
}
