/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2016, FrostWire(R). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.frostwire.android.gui.util;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import com.frostwire.android.R;
import com.frostwire.android.gui.services.Engine;
import com.frostwire.android.offers.Offers;
import com.frostwire.logging.Logger;
import com.frostwire.util.Ref;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * @author gubatron
 * @author aldenml
 */
public final class DangerousPermissionsChecker implements ActivityCompat.OnRequestPermissionsResultCallback {

    public interface OnPermissionsGrantedCallback {
        void onPermissionsGranted();
    }

    public interface WritePermissionsChecker {
        DangerousPermissionsChecker getWriteSettingsPermissionChecker();
    }

    private static final Logger LOG = Logger.getLogger(Logger.class);
    public static final int EXTERNAL_STORAGE_PERMISSIONS_REQUEST_CODE = 0x000A;
    public static final int WRITE_SETTINGS_PERMISSIONS_REQUEST_CODE = 0x000B;

    private final WeakReference<Activity> activityRef;
    private final int requestCode;
    private OnPermissionsGrantedCallback onPermissionsGrantedCallback;

    public DangerousPermissionsChecker(Activity activity, int requestCode) {
        if (activity instanceof ActivityCompat.OnRequestPermissionsResultCallback) {
            this.requestCode = requestCode;
            this.activityRef = Ref.weak(activity);
        } else
            throw new IllegalArgumentException("The activity must implement ActivityCompat.OnRequestPermissionsResultCallback");
    }

    public void requestPermissions() {
        if (!Ref.alive(activityRef)) {
            return;
        }
        Activity activity = activityRef.get();

        String[] permissions = null;
        switch (requestCode) {
            case EXTERNAL_STORAGE_PERMISSIONS_REQUEST_CODE:
                permissions = new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                };
                break;
            case WRITE_SETTINGS_PERMISSIONS_REQUEST_CODE:
                if (Build.VERSION.SDK_INT >= 23) {
                    requestWriteSettingsPermissionsAPILevel23(activity);
                    return;
                }
                // this didn't fly on my Android with API Level 23
                // it might fly on previous versions.
                permissions = new String[] {
                        Manifest.permission.WRITE_SETTINGS
                };
                break;
        }

        if (permissions != null) {
            ActivityCompat.requestPermissions(activity, permissions, requestCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean permissionWasGranted = false;
        switch (requestCode) {
            case EXTERNAL_STORAGE_PERMISSIONS_REQUEST_CODE:
                permissionWasGranted = onExternalStoragePermissionsResult(permissions, grantResults);
                break;
            case WRITE_SETTINGS_PERMISSIONS_REQUEST_CODE:
                permissionWasGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                break;
            default:
                break;
        }

        if (this.onPermissionsGrantedCallback != null && permissionWasGranted) {
            onPermissionsGrantedCallback.onPermissionsGranted();
        }
    }

    public void setPermissionsGrantedCallback(OnPermissionsGrantedCallback onPermissionsGrantedCallback) {
        this.onPermissionsGrantedCallback = onPermissionsGrantedCallback;
    }

    // EXTERNAL STORAGE PERMISSIONS

    public boolean noAccess() {
        // simplified until otherwise necessary.
        return requestCode == EXTERNAL_STORAGE_PERMISSIONS_REQUEST_CODE && noExternalStorageAccess();
    }

    private boolean noExternalStorageAccess() {
        if (!Ref.alive(activityRef)) {
            return true;
        }
        Activity activity = activityRef.get();
        return ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED ||
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED;
    }

    // WRITE SETTINGS (Ringtone setting)

    public static void requestPermissionToWriteSettings(final DangerousPermissionsChecker writeSettingsPermissionChecker,
                                                        final OnPermissionsGrantedCallback onPermissionsGrantedCallback) {
        writeSettingsPermissionChecker.setPermissionsGrantedCallback(onPermissionsGrantedCallback);
        writeSettingsPermissionChecker.requestPermissions();
    }

    public static boolean handleOnWriteSettingsActivityResult(Activity handlerActivity, int requestCode, int resultCode, Intent data) {
        if (requestCode == DangerousPermissionsChecker.WRITE_SETTINGS_PERMISSIONS_REQUEST_CODE &&
            handlerActivity instanceof DangerousPermissionsChecker.WritePermissionsChecker) {

            final DangerousPermissionsChecker writeSettingsPermissionChecker =
                    ((DangerousPermissionsChecker.WritePermissionsChecker) handlerActivity).getWriteSettingsPermissionChecker();

            if (writeSettingsPermissionChecker != null) {
                boolean hasWriteSettings =
                        DangerousPermissionsChecker.hasPermissionToWriteSettings(handlerActivity);

                int permissionCheckResult = hasWriteSettings ?
                        PackageManager.PERMISSION_GRANTED :
                        PackageManager.PERMISSION_DENIED;

                // use the existing mechanism on DangerousPermissionsChecker
                writeSettingsPermissionChecker.
                        onRequestPermissionsResult(
                                DangerousPermissionsChecker.WRITE_SETTINGS_PERMISSIONS_REQUEST_CODE,
                                new String[]{Manifest.permission.WRITE_SETTINGS},
                                new int[]{permissionCheckResult});
            }
            return true;
        }
        return false;
    }

    public static boolean hasPermissionToWriteSettings(Context context) {
        return (Build.VERSION.SDK_INT >= 23) ?
                DangerousPermissionsChecker.canWriteSettingsAPILevel23(context) :
                ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean canWriteSettingsAPILevel23(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 23) {
            return false;
        }
        try {
            final Class<?> SystemClass = Class.forName("android.provider.Settings$System");
            final Method canWriteMethod = SystemClass.getMethod("canWrite", Context.class);
            return (boolean) canWriteMethod.invoke(null, context);
        } catch (Throwable t) {
            LOG.error(t.getMessage(), t);
        }
        return false;
    }

    /**
     * This method will invoke an activity that shows the WRITE_SETTINGS capabilities
     * of our app.
     *
     * More unnecessary distractions and time wasting for developers
     * courtesy of Google.
     *
     * https://commonsware.com/blog/2015/08/17/random-musings-android-6p0-sdk.html
     *
     * > Several interesting new Settings screens are now accessible
     * > via Settings action strings. One that will get a lot of
     * > attention is ACTION_MANAGE_WRITE_SETTINGS, where users can indicate
     * > whether apps can write to system settings or not.
     * > If your app requests the WRITE_SETTINGS permission, you may appear
     * > on this list, and you can call canWrite() on Settings.System to
     * > see if you were granted permission.
     *
     * Google geniuses, Make up your minds please.
     */
    private void requestWriteSettingsPermissionsAPILevel23(Activity activity) {
        // Settings.ACTION_MANAGE_WRITE_SETTINGS - won't build if the
        // intellij sdk is set to API 16 Platform, so I'll just hardcode
        // the value.
        // Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        Intent intent = new Intent("android.settings.action.MANAGE_WRITE_SETTINGS");
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivityForResult(intent, DangerousPermissionsChecker.WRITE_SETTINGS_PERMISSIONS_REQUEST_CODE);
    }

    private boolean onExternalStoragePermissionsResult(String[] permissions, int[] grantResults) {
        if (!Ref.alive(activityRef)) {
            return false;
        }
        final Activity activity = activityRef.get();
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                if (permissions[i].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                        permissions[i].equals(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setIcon(R.drawable.sd_card_notification);
                    builder.setTitle(R.string.why_we_need_storage_permissions);
                    builder.setMessage(R.string.why_we_need_storage_permissions_summary);
                    builder.setNegativeButton(R.string.exit, (dialog, which) -> shutdownFrostWire());
                    builder.setPositiveButton(R.string.request_again, (dialog, which) -> requestPermissions());
                    AlertDialog alertDialog = builder.create();
                    alertDialog.show();
                    return false;
                }
            }
        }
        return true;
    }

    private void shutdownFrostWire() {
        if (!Ref.alive(activityRef)) {
            return;
        }
        final Activity activity = activityRef.get();

        Offers.stopAdNetworks(activity);
        activity.finish();
        Engine.instance().shutdown();
    }
}
