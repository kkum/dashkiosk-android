/*
 * Copyright (c) 2013 Vincent Bernat <vbe@deezer.com>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.deezer.android.dashkiosk;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import org.xwalk.core.JavascriptInterface;
import org.xwalk.core.XWalkJavascriptResult;
import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import com.deezer.android.dashkiosk.DashboardWaitscreen;

/**
 * Fullscreen web view that is setup for kiosk mode: no interaction
 * allowed.
 */
public class DashboardWebView extends XWalkView {

    private static final String TAG = "DashKiosk";
    private static final int ALIVE = 1;
    private static final int DEADLINE = 2;
    private Context mContext;
    private Handler mHandler = null;
    private DashboardWaitscreen mWaitscreen = null;

    public DashboardWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    protected void onAttachedToWindow() {
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);
        XWalkPreferences.setValue(XWalkPreferences.JAVASCRIPT_CAN_OPEN_WINDOW, false);

        /* Don't show error dialogs */
        this.setResourceClient(new XWalkResourceClient(this) {
                @Override
                public void onReceivedLoadError(XWalkView view,
                                                int errorCode,
                                                String description,
                                                String failingUrl) {
                    Log.d(TAG, "Load Failed for " + failingUrl + ": " + description);
                }

                @Override
                public void onReceivedSslError(XWalkView view,
                                               ValueCallback<Boolean> callback,
                                               SslError error) {
                    SharedPreferences sharedPref = PreferenceManager
                        .getDefaultSharedPreferences(mContext);
                    Boolean insecure = sharedPref.getBoolean("pref_insecure_ssl", false);
                    callback.onReceiveValue(insecure);
                }
            });

        /* Ignore most interactions */
        this.setUIClient(new XWalkUIClient(this) {
                @Override
                public void onFullscreenToggled(XWalkView view, boolean enterFullscreen) {
                    Log.d(TAG, "Ignore fullscreen request");
                }

                @Override
                public boolean onJavascriptModalDialog(XWalkView view,
                                                       XWalkUIClient.JavascriptMessageType type,
                                                       String url,
                                                       String message,
                                                       String defaultValue,
                                                       XWalkJavascriptResult result) {
                    Log.d(TAG, "Ignore JS modal dialog (type: " + type + ", message: " + message + ")");
                    return false;
                }

                @Override
                public void onJavascriptCloseWindow(XWalkView view) {
                    Log.d(TAG, "Ignore request to close window");
                }

                @Override
                public void openFileChooser(XWalkView view,
                                            ValueCallback<Uri> uploadFile,
                                            String acceptType,
                                            String capture) {
                    Log.d(TAG, "Ignore request to open a file chooser");
                }
            });

        /* Provide an interface for readiness */
        this.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void ready() {
                    mHandler.sendMessage(mHandler.obtainMessage(ALIVE));
                }

                @JavascriptInterface
                public int timeout() {
                    return getTimeout();
                }

                @JavascriptInterface
                public void log(String message) {
                    Log.d(TAG, "Javascript log: " + message);
                }
            }, "JSInterface");

        this.displayWaitScreen();
        this.heartbeat();
        this.loadReceiver();
    }

    private void displayWaitScreen() {
        if (mWaitscreen != null) {
            return;
        }
        mWaitscreen = new DashboardWaitscreen(mContext);
        mWaitscreen.show();
    }

    private void hideWaitScreen() {
        if (mWaitscreen == null) {
            return;
        }
        mWaitscreen.dismiss();
        mWaitscreen = null;
    }

    // Trigger a reload if we miss an heartbeat
    private void heartbeat() {
        this.mHandler = new Handler() {
                @Override
                public void handleMessage(Message input) {
                    switch (input.what) {
                    case ALIVE:
                        // Got a heartbeat, delay deadline
                        Log.d(TAG, "Received heartbeat");
                        hideWaitScreen();
                        mHandler.removeMessages(DEADLINE);
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEADLINE),
                                                    getTimeout());
                        break;
                    case DEADLINE:
                        // We hit the deadline, trigger a reload
                        Log.i(TAG, "No activity from supervised URL. Trigger reload.");
                        displayWaitScreen();
                        stopLoading();
                        loadReceiver();
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEADLINE),
                                                    getTimeout());
                        break;
                    }
                }
            };

        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEADLINE),
                                    getTimeout());
    }

    private void loadReceiver() {
        SharedPreferences sharedPref = PreferenceManager
            .getDefaultSharedPreferences(mContext);
        String pingURL = sharedPref.getString("pref_ping_url", null);
        String appVer = getResources().getString(R.string.app_versionName);
        String url = pingURL + "?v=" + appVer;
        Log.d(TAG, "Loading " + url);
        this.load(url, null);
    }

    private int getTimeout() {
        SharedPreferences sharedPref = PreferenceManager
            .getDefaultSharedPreferences(mContext);
        return Integer.valueOf(sharedPref.getString("pref_ping_timeout", null));
    }

}
