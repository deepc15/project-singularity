/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.singular.cast.priv;
/**
 * Runs inside the Shizuku/ADB shell process, which holds the privileges the app
 * itself cannot get: creating a trusted virtual display, launching an activity
 * onto it, and injecting input events into it.
 * 
 * Every method carries an explicit transaction id because Shizuku reserves
 * 16777114 for `destroy`, and AIDL requires either all ids or none.
 */
public interface ISingularService extends android.os.IInterface
{
  /** Default implementation for ISingularService. */
  public static class Default implements com.singular.cast.priv.ISingularService
  {
    /** Human-readable identification of the shell process, for diagnostics. */
    @Override public java.lang.String describe() throws android.os.RemoteException
    {
      return null;
    }
    /**
     * Create a trusted, own-content-only virtual display rendering into
     * {@code surface} (the encoder's input surface).
     * 
     * @return the new display id, or -1 if the shell lacks the privilege.
     */
    @Override public int createDisplay(java.lang.String name, int width, int height, int densityDpi, android.view.Surface surface) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public boolean resizeDisplay(int displayId, int width, int height, int densityDpi) throws android.os.RemoteException
    {
      return false;
    }
    /**
     * Point an existing display at a new encoder surface. This is how a tile
     * resize is handled without destroying the display — and therefore without
     * killing the app running on it.
     */
    @Override public boolean setDisplaySurface(int displayId, android.view.Surface surface) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void releaseDisplay(int displayId) throws android.os.RemoteException
    {
    }
    /** Start the package's launcher activity on {@code displayId}. */
    @Override public boolean launchPackage(java.lang.String packageName, int displayId) throws android.os.RemoteException
    {
      return false;
    }
    /** Task id of the package's current task, or -1 if it has none. */
    @Override public int findTaskId(java.lang.String packageName) throws android.os.RemoteException
    {
      return 0;
    }
    /**
     * Every package that currently owns a task. One call instead of a
     * findTaskId per installed app.
     */
    @Override public java.lang.String[] runningPackages() throws android.os.RemoteException
    {
      return null;
    }
    /** Used both to move an app onto a display and to bring it back to display 0. */
    @Override public boolean moveTaskToDisplay(int taskId, int displayId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean injectTouch(int displayId, int action, float x, float y, long downTimeMs) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean injectKey(int displayId, int action, int keyCode, int metaState, long downTimeMs) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean injectScroll(int displayId, float x, float y, float hScroll, float vScroll) throws android.os.RemoteException
    {
      return false;
    }
    /** Commit a string by synthesising key events for it. */
    @Override public boolean injectText(int displayId, java.lang.String text) throws android.os.RemoteException
    {
      return false;
    }
    /** Shizuku's reserved transaction for tearing down a user service. */
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.singular.cast.priv.ISingularService
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.singular.cast.priv.ISingularService interface,
     * generating a proxy if needed.
     */
    public static com.singular.cast.priv.ISingularService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.singular.cast.priv.ISingularService))) {
        return ((com.singular.cast.priv.ISingularService)iin);
      }
      return new com.singular.cast.priv.ISingularService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
      }
      switch (code)
      {
        case TRANSACTION_describe:
        {
          java.lang.String _result = this.describe();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_createDisplay:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          android.view.Surface _arg4;
          _arg4 = _Parcel.readTypedObject(data, android.view.Surface.CREATOR);
          int _result = this.createDisplay(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_resizeDisplay:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          boolean _result = this.resizeDisplay(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_setDisplaySurface:
        {
          int _arg0;
          _arg0 = data.readInt();
          android.view.Surface _arg1;
          _arg1 = _Parcel.readTypedObject(data, android.view.Surface.CREATOR);
          boolean _result = this.setDisplaySurface(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_releaseDisplay:
        {
          int _arg0;
          _arg0 = data.readInt();
          this.releaseDisplay(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_launchPackage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          boolean _result = this.launchPackage(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_findTaskId:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _result = this.findTaskId(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_runningPackages:
        {
          java.lang.String[] _result = this.runningPackages();
          reply.writeNoException();
          reply.writeStringArray(_result);
          break;
        }
        case TRANSACTION_moveTaskToDisplay:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          boolean _result = this.moveTaskToDisplay(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_injectTouch:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          float _arg2;
          _arg2 = data.readFloat();
          float _arg3;
          _arg3 = data.readFloat();
          long _arg4;
          _arg4 = data.readLong();
          boolean _result = this.injectTouch(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_injectKey:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          long _arg4;
          _arg4 = data.readLong();
          boolean _result = this.injectKey(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_injectScroll:
        {
          int _arg0;
          _arg0 = data.readInt();
          float _arg1;
          _arg1 = data.readFloat();
          float _arg2;
          _arg2 = data.readFloat();
          float _arg3;
          _arg3 = data.readFloat();
          float _arg4;
          _arg4 = data.readFloat();
          boolean _result = this.injectScroll(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_injectText:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          boolean _result = this.injectText(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.singular.cast.priv.ISingularService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      /** Human-readable identification of the shell process, for diagnostics. */
      @Override public java.lang.String describe() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_describe, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Create a trusted, own-content-only virtual display rendering into
       * {@code surface} (the encoder's input surface).
       * 
       * @return the new display id, or -1 if the shell lacks the privilege.
       */
      @Override public int createDisplay(java.lang.String name, int width, int height, int densityDpi, android.view.Surface surface) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(name);
          _data.writeInt(width);
          _data.writeInt(height);
          _data.writeInt(densityDpi);
          _Parcel.writeTypedObject(_data, surface, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createDisplay, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean resizeDisplay(int displayId, int width, int height, int densityDpi) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeInt(width);
          _data.writeInt(height);
          _data.writeInt(densityDpi);
          boolean _status = mRemote.transact(Stub.TRANSACTION_resizeDisplay, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Point an existing display at a new encoder surface. This is how a tile
       * resize is handled without destroying the display — and therefore without
       * killing the app running on it.
       */
      @Override public boolean setDisplaySurface(int displayId, android.view.Surface surface) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _Parcel.writeTypedObject(_data, surface, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDisplaySurface, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void releaseDisplay(int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_releaseDisplay, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Start the package's launcher activity on {@code displayId}. */
      @Override public boolean launchPackage(java.lang.String packageName, int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_launchPackage, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Task id of the package's current task, or -1 if it has none. */
      @Override public int findTaskId(java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_findTaskId, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Every package that currently owns a task. One call instead of a
       * findTaskId per installed app.
       */
      @Override public java.lang.String[] runningPackages() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String[] _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_runningPackages, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createStringArray();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Used both to move an app onto a display and to bring it back to display 0. */
      @Override public boolean moveTaskToDisplay(int taskId, int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(taskId);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_moveTaskToDisplay, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean injectTouch(int displayId, int action, float x, float y, long downTimeMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeInt(action);
          _data.writeFloat(x);
          _data.writeFloat(y);
          _data.writeLong(downTimeMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectTouch, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean injectKey(int displayId, int action, int keyCode, int metaState, long downTimeMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeInt(action);
          _data.writeInt(keyCode);
          _data.writeInt(metaState);
          _data.writeLong(downTimeMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectKey, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean injectScroll(int displayId, float x, float y, float hScroll, float vScroll) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeFloat(x);
          _data.writeFloat(y);
          _data.writeFloat(hScroll);
          _data.writeFloat(vScroll);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectScroll, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Commit a string by synthesising key events for it. */
      @Override public boolean injectText(int displayId, java.lang.String text) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeString(text);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectText, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Shizuku's reserved transaction for tearing down a user service. */
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_describe = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_createDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_resizeDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_setDisplaySurface = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_releaseDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_launchPackage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_findTaskId = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_runningPackages = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_moveTaskToDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_injectTouch = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_injectKey = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_injectScroll = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_injectText = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777114);
  }
  public static final java.lang.String DESCRIPTOR = "com.singular.cast.priv.ISingularService";
  /** Human-readable identification of the shell process, for diagnostics. */
  public java.lang.String describe() throws android.os.RemoteException;
  /**
   * Create a trusted, own-content-only virtual display rendering into
   * {@code surface} (the encoder's input surface).
   * 
   * @return the new display id, or -1 if the shell lacks the privilege.
   */
  public int createDisplay(java.lang.String name, int width, int height, int densityDpi, android.view.Surface surface) throws android.os.RemoteException;
  public boolean resizeDisplay(int displayId, int width, int height, int densityDpi) throws android.os.RemoteException;
  /**
   * Point an existing display at a new encoder surface. This is how a tile
   * resize is handled without destroying the display — and therefore without
   * killing the app running on it.
   */
  public boolean setDisplaySurface(int displayId, android.view.Surface surface) throws android.os.RemoteException;
  public void releaseDisplay(int displayId) throws android.os.RemoteException;
  /** Start the package's launcher activity on {@code displayId}. */
  public boolean launchPackage(java.lang.String packageName, int displayId) throws android.os.RemoteException;
  /** Task id of the package's current task, or -1 if it has none. */
  public int findTaskId(java.lang.String packageName) throws android.os.RemoteException;
  /**
   * Every package that currently owns a task. One call instead of a
   * findTaskId per installed app.
   */
  public java.lang.String[] runningPackages() throws android.os.RemoteException;
  /** Used both to move an app onto a display and to bring it back to display 0. */
  public boolean moveTaskToDisplay(int taskId, int displayId) throws android.os.RemoteException;
  public boolean injectTouch(int displayId, int action, float x, float y, long downTimeMs) throws android.os.RemoteException;
  public boolean injectKey(int displayId, int action, int keyCode, int metaState, long downTimeMs) throws android.os.RemoteException;
  public boolean injectScroll(int displayId, float x, float y, float hScroll, float vScroll) throws android.os.RemoteException;
  /** Commit a string by synthesising key events for it. */
  public boolean injectText(int displayId, java.lang.String text) throws android.os.RemoteException;
  /** Shizuku's reserved transaction for tearing down a user service. */
  public void destroy() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
