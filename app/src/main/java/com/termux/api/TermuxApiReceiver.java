package com.termux.api;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.termux.api.apis.*;
import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.plugins.TermuxPluginUtils;

public class TermuxApiReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxApiReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        TermuxAPIApplication.setLogConfig(context, false);
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        try {
            doWork(context, intent);
        } catch (Throwable t) {

            String message = "Error in " + LOG_TAG;

            Logger.logStackTraceWithMessage(LOG_TAG, message, t);

            TermuxPluginUtils.sendPluginCommandErrorNotification(
                    context,
                    LOG_TAG,
                    TermuxConstants.TERMUX_API_APP_NAME + " Error",
                    message,
                    t
            );

            ResultReturner.noteDone(this, intent);
        }
    }

    private void doWork(Context context, Intent intent) {

        String apiMethod = intent.getStringExtra("api_method");

        if (apiMethod == null) {
            Logger.logError(LOG_TAG, "Missing 'api_method' extra");
            return;
        }

        switch (apiMethod) {

            case "AudioInfo":
                AudioAPI.onReceive(this, context, intent);
                break;

            case "AutoClick":
                Log.d(LOG_TAG,"AutoClick command received");
                AutoClickAPI.onReceive(this, context, intent);
                break;

            case "BatteryStatus":
                BatteryStatusAPI.onReceive(this, context, intent);
                break;

            case "Brightness":

                if (!Settings.System.canWrite(context)) {

                    TermuxApiPermissionActivity.checkAndRequestPermissions(
                            context,
                            intent,
                            Manifest.permission.WRITE_SETTINGS
                    );

                    Toast.makeText(
                            context,
                            "Please enable permission for Termux:API",
                            Toast.LENGTH_LONG
                    ).show();

                    Intent settingsIntent =
                            new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);

                    context.startActivity(settingsIntent);
                    return;
                }

                BrightnessAPI.onReceive(this, context, intent);
                break;

            case "CameraInfo":
                CameraInfoAPI.onReceive(this, context, intent);
                break;

            case "CameraPhoto":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(
                        context,
                        intent,
                        Manifest.permission.CAMERA
                )) {
                    CameraPhotoAPI.onReceive(this, context, intent);
                }
                break;

            case "Clipboard":
                ClipboardAPI.onReceive(this, context, intent);
                break;

            case "Dialog":
                DialogAPI.onReceive(context, intent);
                break;

            case "Download":
                DownloadAPI.onReceive(this, context, intent);
                break;

            case "Location":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(
                        context,
                        intent,
                        Manifest.permission.ACCESS_FINE_LOCATION
                )) {
                    LocationAPI.onReceive(this, context, intent);
                }
                break;

            case "MediaPlayer":
                MediaPlayerAPI.onReceive(context, intent);
                break;

            case "MicRecorder":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(
                        context,
                        intent,
                        Manifest.permission.RECORD_AUDIO
                )) {
                    MicRecorderAPI.onReceive(context, intent);
                }
                break;

            case "TextToSpeech":
                TextToSpeechAPI.onReceive(context, intent);
                break;

            case "Toast":
                ToastAPI.onReceive(context, intent);
                break;

            case "Torch":
                TorchAPI.onReceive(this, context, intent);
                break;

            case "Vibrate":
                VibrateAPI.onReceive(this, context, intent);
                break;

            case "Volume":
                VolumeAPI.onReceive(this, context, intent);
                break;

            case "Wallpaper":
                WallpaperAPI.onReceive(context, intent);
                break;

            case "WifiConnectionInfo":
                WifiAPI.onReceiveWifiConnectionInfo(this, context, intent);
                break;

            case "WifiScanInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(
                        context,
                        intent,
                        Manifest.permission.ACCESS_FINE_LOCATION
                )) {
                    WifiAPI.onReceiveWifiScanInfo(this, context, intent);
                }
                break;

            case "WifiEnable":
                WifiAPI.onReceiveWifiEnable(this, context, intent);
                break;

            default:
                Logger.logError(
                        LOG_TAG,
                        "Unrecognized 'api_method' extra: '" + apiMethod + "'"
                );
        }
    }
}
