/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.vpndialogs;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.IConnectivityManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Html;
import android.text.Html.ImageGetter;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.net.VpnConfig;

public class ConfirmDialog extends AlertActivity
        implements DialogInterface.OnClickListener, ImageGetter {
    private static final String TAG = "VpnConfirm";

    // Usually the label represents the app name, 150 code points might be enough to display the app
    // name, and 150 code points won't cover the warning message from VpnDialog.
    static final int MAX_VPN_LABEL_LENGTH = 150;

    private String mPackage;

    private IConnectivityManager mService;

    private View mView;

    /**
     * This function will use the string resource to combine the VPN label and the package name.
     *
     * If the VPN label violates the length restriction, the first 30 code points of VPN label and
     * the package name will be returned. Or return the VPN label and the package name directly if
     * the VPN label doesn't violate the length restriction.
     *
     * The result will be something like,
     * - ThisIsAVeryLongVpnAppNameWhich... (com.vpn.app)
     *   if the VPN label violates the length restriction.
     * or
     * - VpnLabelWith&lt;br&gt;HtmlTag (com.vpn.app)
     *   if the VPN label doesn't violate the length restriction.
     *
     */
    private String getSimplifiedLabel(String vpnLabel, String packageName) {
        if (vpnLabel.codePointCount(0, vpnLabel.length()) > 30) {
            return getString(R.string.sanitized_vpn_label_with_ellipsis,
                vpnLabel.substring(0, vpnLabel.offsetByCodePoints(0, 30)),
                packageName);
        }

        return getString(R.string.sanitized_vpn_label, vpnLabel, packageName);
    }

    protected String getSanitizedVpnLabel(String vpnLabel, String packageName) {
        final String sanitizedVpnLabel = Html.escapeHtml(vpnLabel);
        final boolean exceedMaxVpnLabelLength = sanitizedVpnLabel.codePointCount(0,
            sanitizedVpnLabel.length()) > MAX_VPN_LABEL_LENGTH;
        if (exceedMaxVpnLabelLength || !vpnLabel.equals(sanitizedVpnLabel)) {
            return getSimplifiedLabel(sanitizedVpnLabel, packageName);
        }

        return sanitizedVpnLabel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackage = getCallingPackage();
        mService = IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));

        if (prepareVpn()) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        if (UserManager.get(this).hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
            finish();
            return;
        }
        final String alwaysOnVpnPackage = getAlwaysOnVpnPackage();
        // Can't prepare new vpn app when another vpn is always-on
        if (alwaysOnVpnPackage != null && !alwaysOnVpnPackage.equals(mPackage)) {
            finish();
            return;
        }
        mView = View.inflate(this, R.layout.confirm, null);
        ((TextView) mView.findViewById(R.id.warning)).setText(
                Html.fromHtml(getString(R.string.warning, getSanitizedVpnLabel(
                    getVpnLabel().toString(), mPackage)),
                    this /* imageGetter */, null /* tagHandler */));
        mAlertParams.mTitle = getText(R.string.prompt);
        mAlertParams.mPositiveButtonText = getText(android.R.string.ok);
        mAlertParams.mPositiveButtonListener = this;
        mAlertParams.mNegativeButtonText = getText(android.R.string.cancel);
        mAlertParams.mView = mView;
        setupAlert();

        getWindow().setCloseOnTouchOutside(false);
        getWindow().addPrivateFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        Button button = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
        button.setFilterTouchesWhenObscured(true);
    }

    private String getAlwaysOnVpnPackage() {
        try {
           return mService.getAlwaysOnVpnPackage(UserHandle.myUserId());
        } catch (RemoteException e) {
            Log.e(TAG, "fail to call getAlwaysOnVpnPackage", e);
            // Fallback to null to show the dialog
            return null;
        }
    }

    private boolean prepareVpn() {
        try {
            return mService.prepareVpn(mPackage, null, UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    private CharSequence getVpnLabel() {
        try {
            return VpnConfig.getVpnLabel(this, mPackage);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Drawable getDrawable(String source) {
        // Should only reach this when fetching the VPN icon for the warning string.
        final Drawable icon = getDrawable(R.drawable.ic_vpn_dialog);
        icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());

        final TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            icon.setTint(getColor(tv.resourceId));
        } else {
            Log.w(TAG, "Unable to resolve theme color");
        }

        return icon;
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (mService.prepareVpn(null, mPackage, UserHandle.myUserId())) {
                // Authorize this app to initiate VPN connections in the future without user
                // intervention.
                mService.setVpnPackageAuthorization(mPackage, UserHandle.myUserId(), true);
                setResult(RESULT_OK);
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
        }
    }
}
