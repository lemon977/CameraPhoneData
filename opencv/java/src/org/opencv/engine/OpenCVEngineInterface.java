package org.opencv.engine;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Binder;
import android.os.RemoteException;

public interface OpenCVEngineInterface extends IInterface {

    public static abstract class Stub extends Binder implements OpenCVEngineInterface {
        private static final String DESCRIPTOR = "org.opencv.engine.OpenCVEngineInterface";

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static OpenCVEngineInterface asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof OpenCVEngineInterface) {
                return (OpenCVEngineInterface) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements OpenCVEngineInterface {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public int getEngineVersion() throws RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getEngineVersion, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getLibPathByVersion(String version) throws RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(version);
                    mRemote.transact(Stub.TRANSACTION_getLibPathByVersion, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean installVersion(String version) throws RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(version);
                    mRemote.transact(Stub.TRANSACTION_installVersion, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getLibraryList(String version) throws RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(version);
                    mRemote.transact(Stub.TRANSACTION_getLibraryList, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }

        static final int TRANSACTION_getEngineVersion = IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_getLibPathByVersion = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_installVersion = IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_getLibraryList = IBinder.FIRST_CALL_TRANSACTION + 3;
    }

    public int getEngineVersion() throws RemoteException;
    public String getLibPathByVersion(String version) throws RemoteException;
    public boolean installVersion(String version) throws RemoteException;
    public String getLibraryList(String version) throws RemoteException;
}