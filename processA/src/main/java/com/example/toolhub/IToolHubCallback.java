package com.example.toolhub;

import android.os.Bundle;
import android.os.IInterface;

public interface IToolHubCallback extends IInterface {
    void onResult(String requestId, Bundle result) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IToolHubCallback {
        public static IToolHubCallback asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface("com.example.toolhub.IToolHubCallback");
            if (iin instanceof IToolHubCallback) return (IToolHubCallback) iin;
            return new Proxy(obj);
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString("com.example.toolhub.IToolHubCallback");
                    return true;
                case 1: // onResult (oneway)
                    data.enforceInterface("com.example.toolhub.IToolHubCallback");
                    String requestId = data.readString();
                    Bundle result = data.readBundle();
                    onResult(requestId, result);
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IToolHubCallback {
            private android.os.IBinder mRemote;

            Proxy(android.os.IBinder remote) {
                mRemote = remote;
            }

            @Override
            public android.os.IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onResult(String requestId, Bundle result)
                    throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.example.toolhub.IToolHubCallback");
                    data.writeString(requestId);
                    data.writeBundle(result);
                    mRemote.transact(1, data, null, android.os.IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
