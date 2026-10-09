package com.barcodescannerhid.lib;

import android.app.Activity;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.os.Looper;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

@android.support.annotation.Keep
public class BarcodeScannerHIDClass
{
    private static final String SCAN_PREFIX = "@@";

    private Activity mActivity;
    private long mCallObject;
    private final Object mScanLock = new Object();
    private final StringBuilder mScanBuffer = new StringBuilder();
    private String mLastScan = "";
    private String mLastError = "";
    private boolean mIsWatching = false;
    private Window.Callback mOriginalWindowCallback;
    private Window.Callback mWrappedWindowCallback;

    private static native void OnBarcodeScannerData(long pObject, String content);

    public BarcodeScannerHIDClass(Activity activity, long owner)
    {
        mActivity = activity;
        mCallObject = owner;
        try
        {
            System.loadLibrary("ka_soft_BarcodeScannerHID");
        }
        catch (UnsatisfiedLinkError e)
        {
            mLastError = "Error loading library ka_soft_BarcodeScannerHID";
        }
    }

    public void startWatch()
    {
        final Activity activity = mActivity;
        runOnUiThread(activity, new Runnable()
        {
            @Override
            public void run()
            {
                if (activity == null)
                {
                    mLastError = "Activity is unavailable";
                    return;
                }

                Window window = activity.getWindow();
                if (window == null)
                {
                    mLastError = "Window is unavailable";
                    return;
                }

                Window.Callback currentCallback = window.getCallback();
                if (currentCallback == null)
                {
                    mLastError = "Window callback is unavailable";
                    return;
                }

                if (currentCallback == mWrappedWindowCallback)
                {
                    mIsWatching = true;
                    return;
                }

                mOriginalWindowCallback = currentCallback;
                mWrappedWindowCallback = new BarcodeWindowCallback(currentCallback);
                window.setCallback(mWrappedWindowCallback);
                clearBufferedScan();
                mIsWatching = true;
                mLastError = "";
            }
        });
    }

    public void stopWatch()
    {
        final Activity activity = mActivity;
        runOnUiThread(activity, new Runnable()
        {
            @Override
            public void run()
            {
                mIsWatching = false;

                if (activity == null)
                {
                    clearBufferedScan();
                    return;
                }

                Window window = activity.getWindow();
                if (window != null && mWrappedWindowCallback != null && window.getCallback() == mWrappedWindowCallback && mOriginalWindowCallback != null)
                    window.setCallback(mOriginalWindowCallback);

                mWrappedWindowCallback = null;
                mOriginalWindowCallback = null;
                clearBufferedScan();
            }
        });
    }

    public String getBarcodeText()
    {
        synchronized (mScanLock)
        {
            return mLastScan;
        }
    }

    public void clearObjectsReference()
    {
        stopWatch();
        mCallObject = 0;
        mActivity = null;
    }

    public String getErrorDescr()
    {
        return mLastError;
    }

    private void handleKeyEvent(KeyEvent event)
    {
        if (!mIsWatching || event == null || !isScannerInputEvent(event))
            return;

        if (event.getAction() == KeyEvent.ACTION_MULTIPLE)
        {
            processCharacters(event.getCharacters());
            return;
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        {
            submitBufferedScan();
            return;
        }

        if (keyCode == KeyEvent.KEYCODE_DEL)
        {
            removeLastBufferedChar();
            return;
        }

        int unicodeChar = event.getUnicodeChar(event.getMetaState());
        if (unicodeChar == 0)
            return;

        if (unicodeChar == '\r' || unicodeChar == '\n')
        {
            submitBufferedScan();
            return;
        }

        if (Character.isISOControl(unicodeChar))
            return;

        appendCharacter((char)unicodeChar);
    }

    private boolean isScannerInputEvent(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE)
            return !TextUtils.isEmpty(event.getCharacters());

        if (event.getAction() != KeyEvent.ACTION_DOWN)
            return false;

        InputDevice device = event.getDevice();
        if (device == null)
            return true;

        if (!device.isVirtual())
            return true;

        if (event.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD)
            return true;

        int source = event.getSource();
        return source == 0 || (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
    }

    private void processCharacters(CharSequence characters)
    {
        if (TextUtils.isEmpty(characters))
            return;

        for (int i = 0; i < characters.length(); i++)
        {
            char current = characters.charAt(i);
            if (current == '\r' || current == '\n')
                submitBufferedScan();
            else if (!Character.isISOControl(current))
                appendCharacter(current);
        }
    }

    private void appendCharacter(char value)
    {
        synchronized (mScanLock)
        {
            mScanBuffer.append(value);
        }
    }

    private void removeLastBufferedChar()
    {
        synchronized (mScanLock)
        {
            int length = mScanBuffer.length();
            if (length > 0)
                mScanBuffer.deleteCharAt(length - 1);
        }
    }

    private void clearBufferedScan()
    {
        synchronized (mScanLock)
        {
            mScanBuffer.setLength(0);
            mLastScan = "";
        }
    }

    private void submitBufferedScan()
    {
        String payload;
        synchronized (mScanLock)
        {
            payload = normalizeBufferedScan(mScanBuffer);
            mScanBuffer.setLength(0);

            if (!payload.startsWith(SCAN_PREFIX) || payload.length() <= SCAN_PREFIX.length())
                return;

            payload = payload.substring(SCAN_PREFIX.length());
            mLastScan = payload;
        }

        if (mCallObject != 0)
            OnBarcodeScannerData(mCallObject, payload);
    }

    private String normalizeBufferedScan(CharSequence input)
    {
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++)
        {
            char current = input.charAt(i);
            if (!Character.isWhitespace(current))
                result.append(current);
        }
        return result.toString();
    }

    private void runOnUiThread(Activity activity, Runnable action)
    {
        if (activity == null || action == null)
            return;

        if (Looper.myLooper() == Looper.getMainLooper())
            action.run();
        else
            activity.runOnUiThread(action);
    }

    private final class BarcodeWindowCallback implements Window.Callback
    {
        private final Window.Callback mDelegate;

        private BarcodeWindowCallback(Window.Callback delegate)
        {
            mDelegate = delegate;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event)
        {
            handleKeyEvent(event);
            return mDelegate != null && mDelegate.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event)
        {
            handleKeyEvent(event);
            return mDelegate != null && mDelegate.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event)
        {
            return mDelegate != null && mDelegate.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event)
        {
            return mDelegate != null && mDelegate.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event)
        {
            return mDelegate != null && mDelegate.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event)
        {
            return mDelegate != null && mDelegate.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId)
        {
            return mDelegate != null ? mDelegate.onCreatePanelView(featureId) : null;
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu)
        {
            return mDelegate != null && mDelegate.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu)
        {
            return mDelegate != null && mDelegate.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu)
        {
            return mDelegate != null && mDelegate.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item)
        {
            return mDelegate != null && mDelegate.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs)
        {
            if (mDelegate != null)
                mDelegate.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged()
        {
            if (mDelegate != null)
                mDelegate.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus)
        {
            if (mDelegate != null)
                mDelegate.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow()
        {
            if (mDelegate != null)
                mDelegate.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow()
        {
            if (mDelegate != null)
                mDelegate.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu)
        {
            if (mDelegate != null)
                mDelegate.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested()
        {
            return mDelegate != null && mDelegate.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent)
        {
            return mDelegate != null && mDelegate.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback)
        {
            return mDelegate != null ? mDelegate.onWindowStartingActionMode(callback) : null;
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type)
        {
            return mDelegate != null ? mDelegate.onWindowStartingActionMode(callback, type) : null;
        }

        @Override
        public void onActionModeStarted(ActionMode mode)
        {
            if (mDelegate != null)
                mDelegate.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode)
        {
            if (mDelegate != null)
                mDelegate.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId)
        {
            if (mDelegate != null)
                mDelegate.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture)
        {
            if (mDelegate != null)
                mDelegate.onPointerCaptureChanged(hasCapture);
        }
    }
}
