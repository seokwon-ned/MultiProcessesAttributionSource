package com.example.toolhub;

import android.content.AttributionSource;
import android.os.Bundle;
import android.os.IInterface;

public interface IToolHub extends IInterface {
    String execute(String actionId, Bundle args, boolean reverse,
                   AttributionSource callerSource, IToolHubCallback callback)
            throws android.os.RemoteException;

    void cancel(String requestId) throws android.os.RemoteException;

    /**
     * 액션 메타데이터 조회 — 필수 권한(분류/링크별 grant 스냅샷), 동의 필요
     * 여부. B는 캐시된 C의 describe 응답을 해석 없이 그대로 relay한다.
     * 동기 호출 — 캐시 히트면 IPC 1홉, 미스면 C provider 호출 후 캐싱.
     */
    Bundle describe(String actionId) throws android.os.RemoteException;

    /**
     * 사전 권한 진단 — A가 execute() 호출 전에 "지금 실행 가능한가?" 미리 확인.
     * null 반환 = 실행 가능 (또는 정보 부족)
     * denied Bundle 반환 = grant 또는 AppOps 문제 (PHASE_HUB_PREFLIGHT)
     * 결과는 B에서 ~10초 캐싱하므로 repeat 호출은 빠르다. execute()에서도
     * 동일 로직이 재실행되므로 보안 문제 없음 (캐시 사용으로 중복 방지).
     */
    Bundle preflight(String actionId) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IToolHub {
        public static IToolHub asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface("com.example.toolhub.IToolHub");
            if (iin instanceof IToolHub) return (IToolHub) iin;
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
                    reply.writeString("com.example.toolhub.IToolHub");
                    return true;
                case 1: // execute
                    data.enforceInterface("com.example.toolhub.IToolHub");
                    String actionId = data.readString();
                    Bundle args = data.readBundle();
                    boolean reverse = (0 != data.readInt());
                    AttributionSource source = data.readParcelable(AttributionSource.class.getClassLoader(), AttributionSource.class);
                    IToolHubCallback callback = IToolHubCallback.Stub.asInterface(data.readStrongBinder());
                    String result = execute(actionId, args, reverse, source, callback);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                case 2: // cancel
                    data.enforceInterface("com.example.toolhub.IToolHub");
                    String requestId = data.readString();
                    cancel(requestId);
                    reply.writeNoException();
                    return true;
                case 3: // describe
                    data.enforceInterface("com.example.toolhub.IToolHub");
                    String describeActionId = data.readString();
                    Bundle metadata = describe(describeActionId);
                    reply.writeNoException();
                    reply.writeBundle(metadata);
                    return true;
                case 4: // preflight
                    data.enforceInterface("com.example.toolhub.IToolHub");
                    String preflightActionId = data.readString();
                    Bundle preflightResult = preflight(preflightActionId);
                    reply.writeNoException();
                    reply.writeBundle(preflightResult);
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IToolHub {
            private android.os.IBinder mRemote;

            Proxy(android.os.IBinder remote) {
                mRemote = remote;
            }

            @Override
            public android.os.IBinder asBinder() {
                return mRemote;
            }

            @Override
            public String execute(String actionId, Bundle args, boolean reverse,
                                  AttributionSource source, IToolHubCallback callback)
                    throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.example.toolhub.IToolHub");
                    data.writeString(actionId);
                    data.writeBundle(args);
                    data.writeInt(reverse ? 1 : 0);
                    data.writeParcelable(source, 0);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(1, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void cancel(String requestId) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.example.toolhub.IToolHub");
                    data.writeString(requestId);
                    mRemote.transact(2, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public Bundle describe(String actionId) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.example.toolhub.IToolHub");
                    data.writeString(actionId);
                    mRemote.transact(3, data, reply, 0);
                    reply.readException();
                    return reply.readBundle();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public Bundle preflight(String actionId) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.example.toolhub.IToolHub");
                    data.writeString(actionId);
                    mRemote.transact(4, data, reply, 0);
                    reply.readException();
                    return reply.readBundle();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
