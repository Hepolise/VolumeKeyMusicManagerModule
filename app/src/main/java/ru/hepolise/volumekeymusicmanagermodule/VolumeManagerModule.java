package ru.hepolise.volumekeymusicmanagermodule;

import static de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class VolumeManagerModule implements IXposedHookLoadPackage {
    private static final String CLASS_PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager";
    private static final String CLASS_IWINDOW_MANAGER = "android.view.IWindowManager";
    private static final String CLASS_WINDOW_MANAGER_FUNCS = "com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs";
    private static final boolean DEBUG = true;

    private static boolean mIsLongPress = false;

    private static boolean mIsDownPressed = false;
    private static boolean mIsUpPressed = false;

//    private static int mButtonsPressed = 0;
    private static AudioManager mAudioManager;
    private static PowerManager mPowerManager;

    private static void log(String text) {
        if (DEBUG)
            XposedBridge.log(text);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        init(lpparam.classLoader);
    }

    private void init(final ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r18/services/core/java/com/android/server/policy/PhoneWindowManager.java#2033
            XposedHelpers.findAndHookMethod(CLASS_PHONE_WINDOW_MANAGER, classLoader, "init",
                    Context.class, CLASS_WINDOW_MANAGER_FUNCS, handleConstructPhoneWindowManager);
        } else {
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-dev/services/core/java/com/android/server/policy/PhoneWindowManager.java#1873
            XposedHelpers.findAndHookMethod(CLASS_PHONE_WINDOW_MANAGER, classLoader, "init",
                    Context.class, CLASS_IWINDOW_MANAGER, CLASS_WINDOW_MANAGER_FUNCS,
                    handleConstructPhoneWindowManager);
        }

        // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r18/services/core/java/com/android/server/policy/PhoneWindowManager.java#4117
        XposedHelpers.findAndHookMethod(CLASS_PHONE_WINDOW_MANAGER, classLoader,
                "interceptKeyBeforeQueueing", KeyEvent.class, int.class, handleInterceptKeyBeforeQueueing);
    }

    private static final XC_MethodHook handleInterceptKeyBeforeQueueing = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {

            final KeyEvent event = (KeyEvent) param.args[0];
            final int keyCode = event.getKeyCode();
            initManagers((Context) XposedHelpers.getObjectField(param.thisObject, "mContext"));
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
                    (event.getFlags() & KeyEvent.FLAG_FROM_SYSTEM) != 0 &&
                    (!mPowerManager.isInteractive() || mIsDownPressed || mIsUpPressed) &&
                    mAudioManager != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                        mIsDownPressed = true;
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                        mIsUpPressed = true;
                    log("down action received, down: " + mIsDownPressed + ", up: " + mIsUpPressed);
                    mIsLongPress = false;
                    if (mIsUpPressed && mIsDownPressed) {
                        log("aborting delayed skip");
                        handleVolumeSkipPressAbort(param.thisObject);
                    } else {
                        // only one button pressed
                        if (isMusicActive()) {
                            log("music is active, creating delayed skip");
                            handleVolumeSkipPress(param.thisObject, keyCode);
                        }
                        log("creating delayed play pause");
                        handleVolumePlayPausePress(param.thisObject);
                    }
                } else {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                        mIsDownPressed = false;
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                        mIsUpPressed = false;
                    log("up action received, down: " + mIsDownPressed + ", up: " + mIsUpPressed);
                    handleVolumeAllPressAbort(param.thisObject);
                    if (!mIsLongPress && isMusicActive()) {
                        log("adjusting music volume");
                        mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                keyCode == KeyEvent.KEYCODE_VOLUME_UP ?
                                        AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                    }
                }
                param.setResult(0);
            }
        }
    };

    private static final XC_MethodHook handleConstructPhoneWindowManager = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(final MethodHookParam param) {

            Runnable mVolumeUpLongPress = () -> {
                log("sending next");
                mIsLongPress = true;
                sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
            };

            Runnable mVolumeDownLongPress = () -> {
                log("sending prev");
                mIsLongPress = true;
                sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            };

            Runnable mVolumeBothLongPress = () -> {
                if (mIsUpPressed && mIsDownPressed) {
                    log("sending play/pause");
                    mIsLongPress = true;
                    sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                } else {
                    log("NOT sending play/pause, down: " + mIsDownPressed + ", up: " + mIsUpPressed);
                }
            };

            setAdditionalInstanceField(param.thisObject, "mVolumeUpLongPress", mVolumeUpLongPress);
            setAdditionalInstanceField(param.thisObject, "mVolumeDownLongPress", mVolumeDownLongPress);
            setAdditionalInstanceField(param.thisObject, "mVolumeBothLongPress", mVolumeBothLongPress);
        }
    };

    private static void initManagers(Context ctx) {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        }
        if (mPowerManager == null) {
            mPowerManager = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        }
    }

    private static boolean isMusicActive() {
        // check local
        if (mAudioManager.isMusicActive())
            return true;
        // check remote
        try {
            if ((boolean) XposedHelpers.callMethod(mAudioManager, "isMusicActiveRemotely"))
                return true;
        } catch (Throwable t) {
            Log.e("xposed", t.getLocalizedMessage());
            t.printStackTrace();
        }
        return false;
    }

    private static void sendMediaButtonEvent(int code) {
        long eventtime = SystemClock.uptimeMillis();
        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent keyEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, code, 0);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        dispatchMediaButtonEvent(keyEvent);

        keyEvent = KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_UP);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        dispatchMediaButtonEvent(keyEvent);
    }

    private static void dispatchMediaButtonEvent(KeyEvent keyEvent) {
        try {
            mAudioManager.dispatchMediaKeyEvent(keyEvent);
        } catch (Throwable t) {
            Log.e("xposed", t.getLocalizedMessage());
            t.printStackTrace();
        }
    }

    private static void handleVolumePlayPausePress(Object phoneWindowManager) {
        Handler mHandler = (Handler) getObjectField(phoneWindowManager, "mHandler");
        Runnable mVolumeBothLongPress = (Runnable) getAdditionalInstanceField(phoneWindowManager, "mVolumeBothLongPress");

        mHandler.postDelayed(mVolumeBothLongPress, ViewConfiguration.getLongPressTimeout());
    }

    private static void handleVolumeSkipPress(Object phoneWindowManager, int keycode) {
        Handler mHandler = (Handler) getObjectField(phoneWindowManager, "mHandler");
        Runnable mVolumeUpLongPress = (Runnable) getAdditionalInstanceField(phoneWindowManager, "mVolumeUpLongPress");
        Runnable mVolumeDownLongPress = (Runnable) getAdditionalInstanceField(phoneWindowManager, "mVolumeDownLongPress");

        mHandler.postDelayed(keycode == KeyEvent.KEYCODE_VOLUME_UP ? mVolumeUpLongPress :
                mVolumeDownLongPress, ViewConfiguration.getLongPressTimeout());
    }

    private static void handleVolumeSkipPressAbort(Object phoneWindowManager) {
        log("aborting skip");
        Handler mHandler = (Handler) getObjectField(phoneWindowManager, "mHandler");
        Runnable mVolumeUpLongPress = (Runnable) getAdditionalInstanceField(phoneWindowManager, "mVolumeUpLongPress");
        Runnable mVolumeDownLongPress = (Runnable) getAdditionalInstanceField(phoneWindowManager, "mVolumeDownLongPress");

        mHandler.removeCallbacks(mVolumeUpLongPress);
        mHandler.removeCallbacks(mVolumeDownLongPress);
    }

    private static void handleVolumePlayPausePressAbort(Object phoneWindowManager) {
        log("aborting play/pause");
        Handler mHandler = (Handler) getObjectField(phoneWindowManager, "mHandler");
        Runnable mVolumeBothLongPress = (Runnable) getAdditionalInstanceField(phoneWindowManager, "mVolumeBothLongPress");

        mHandler.removeCallbacks(mVolumeBothLongPress);
    }

    private static void handleVolumeAllPressAbort(Object phoneWindowManager) {
        log("aborting all");
        handleVolumePlayPausePressAbort(phoneWindowManager);
        handleVolumeSkipPressAbort(phoneWindowManager);
    }
}
