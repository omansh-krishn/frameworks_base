/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.StatsLog;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyDialogBuilder;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyItemControllerKt;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.info.DataUsageView;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback,
        ZenModeController.Callback, Tunable {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private static final String QS_SHOW_AUTO_BRIGHTNESS =
            "system:" + Settings.System.QS_AUTO_BRIGHTNESS_POS;
    private static final String QS_SHOW_BRIGHTNESS_SLIDER =
            "lineagesecure:" + LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER;

    private final Handler mHandler = new Handler();
    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    private QSCarrierGroup mCarrierGroup;
    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;
    private TouchAnimator mPrivacyChipAlphaAnimator;
    private DualToneHandler mDualToneHandler;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    // Data Usage
    private View mDataUsageLayout;
    private ImageView mDataUsageImage;
    private DataUsageView mDataUsageView;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private DateView mDateView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private BatteryMeterView mBatteryIcon;
    private boolean mPermissionsHubEnabled;

    private PrivacyItemController mPrivacyItemController;
    private boolean mLandscape;
    private boolean mHeaderImageEnabled;

    private View mQuickQsBrightness;
    private BrightnessController mBrightnessController;
    private boolean mIsQuickQsBrightnessEnabled;
    private int mIsQsAutoBrightnessEnabled;
    private boolean mBrightnessButton;
    private int mBrightnessSlider;
    private ImageView mMinBrightness;
    private ImageView mMaxBrightness;

    private PrivacyItemController mPrivacyItemController;

    private int mStatusBarBatteryStyle, mQSBatteryStyle;

    private static final String QS_SHOW_BATTERY_PERCENT =
            "system:" + Settings.System.QS_SHOW_BATTERY_PERCENT;
    private static final String QS_SHOW_BATTERY_ESTIMATE =
            "system:" + Settings.System.QS_SHOW_BATTERY_ESTIMATE;
    public static final String STATUS_BAR_BATTERY_STYLE =
            "system:" + Settings.System.STATUS_BAR_BATTERY_STYLE;
    public static final String QS_BATTERY_STYLE =
            "system:" + Settings.System.QS_BATTERY_STYLE;
    public static final String QS_BATTERY_LOCATION =
            "system:" + Settings.System.QS_BATTERY_LOCATION;
    private boolean mHideDragHandle;

    private static final String SHOW_QS_CLOCK =
            "system:" + Settings.System.SHOW_QS_CLOCK;

    private RRSettingsObserver mRRSettingsObserver = new RRSettingsObserver(mHandler);

    private class RRSettingsObserver extends ContentObserver {
        RRSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER), false,
                    this, UserHandle.USER_ALL);
            }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private boolean mForceHideQsStatusBar;

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mRingerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            updateStatusText();
        }
    };
    private boolean mHasTopCutout = false;
    private boolean mPrivacyChipLogged = false;

    private final DeviceConfig.OnPropertyChangedListener mPropertyListener =
            new DeviceConfig.OnPropertyChangedListener() {
                @Override
                public void onPropertyChanged(String namespace, String name, String value) {
                    if (DeviceConfig.NAMESPACE_PRIVACY.equals(namespace)
                            && SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED.equals(
                            name)) {
                        mPermissionsHubEnabled = Boolean.valueOf(value);
                        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
                        iconContainer.setIgnoredSlots(getIgnoredIconSlots());
                    }
                }
            };

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void privacyChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }
    };

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter, PrivacyItemController privacyItemController) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mPrivacyItemController = privacyItemController;
        mDualToneHandler = new DualToneHandler(
                new ContextThemeWrapper(context, R.style.QSHeaderTheme));
        mRRSettingsObserver.observe();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        // Ignore privacy icons because they show in the space above QQS
        iconContainer.addIgnoredSlots(getIgnoredIconSlots());
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);

        mQuickQsBrightness = findViewById(R.id.quick_qs_brightness_bar);
        mMinBrightness = mQuickQsBrightness.findViewById(R.id.brightness_left);
        mMaxBrightness = mQuickQsBrightness.findViewById(R.id.brightness_right);
        mMinBrightness.setOnClickListener(this::onClick);
        mMaxBrightness.setOnClickListener(this::onClick);
        ImageView brightnessIcon = (ImageView) mQuickQsBrightness.findViewById(R.id.brightness_icon);
        brightnessIcon.setVisibility(View.VISIBLE);
        mBrightnessController = new BrightnessController(getContext(),
                mQuickQsBrightness.findViewById(R.id.brightness_icon),
                mQuickQsBrightness.findViewById(R.id.brightness_icon_left),
                mQuickQsBrightness.findViewById(R.id.brightness_slider));

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mNextAlarmContainer.setOnClickListener(this::onClick);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mRingerContainer.setOnClickListener(this::onClick);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this::onClick);
        mCarrierGroup = findViewById(R.id.carrier_group);
        mForceHideQsStatusBar = mContext.getResources().getBoolean(R.bool.qs_status_bar_hidden);

        updateResources();

        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(fillColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(fillColor));

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mClockView.setQsHeader();
        mDateView = findViewById(R.id.date);
        mSpace = findViewById(R.id.space);
        mDataUsageLayout = findViewById(R.id.daily_data_usage_layout);
        mDataUsageImage = findViewById(R.id.daily_data_usage_icon);
        mDataUsageView = findViewById(R.id.data_sim_usage);

        // Set the correct tint for the data uasgae icons so they contrast
        mDataUsageImage.setImageTintList(ColorStateList.valueOf(fillColor));

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        mBatteryIcon = findViewById(R.id.batteryIcon);
        // Don't need to worry about tuner settings for this icon
        mBatteryRemainingIcon.setIgnoreTunerUpdates(true);
        mBatteryIcon.setIgnoreTunerUpdates(true);
        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);

        mPermissionsHubEnabled = PrivacyItemControllerKt.isPermissionsHubEnabled();
        // Change the ignored slots when DeviceConfig flag changes
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                mContext.getMainExecutor(), mPropertyListener);

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_BLACKLIST,
                STATUS_BAR_BATTERY_STYLE,SHOW_QS_CLOCK, QS_SHOW_BATTERY_PERCENT,
                QS_SHOW_BATTERY_ESTIMATE, QS_BATTERY_STYLE,
                Settings.System.QS_DATAUSAGE, QS_SHOW_AUTO_BRIGHTNESS, QSPanel.QS_SHOW_BRIGHTNESS_BUTTONS, 
                QS_BATTERY_LOCATION, QSFooterImpl.QS_SHOW_DRAG_HANDLE, QS_SHOW_BRIGHTNESS_SLIDER);
        updateSettings();
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_camera));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_microphone));
        if (mPermissionsHubEnabled) {
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_location));
        }

        return ignored;
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && mPermissionsHubEnabled) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                        StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_VIEWED);
            }
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private boolean updateRingerStatus() {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!ZenModeConfig.isZenOverridingRinger(mZenController.getZen(),
                mZenController.getConsolidatedPolicy())) {
            if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerContainer.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != ringerVisible ||
                !Objects.equals(originalRingerText, mRingerModeTextView.getText());
    }

    private boolean updateAlarmStatus() {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        updateResources();

        // Update color schemes in landscape to use wallpaperTextColor
        boolean shouldUseWallpaperTextColor =
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
        updateStatusbarProperties();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    /**
     * The height of QQS should always be the status bar height + 128dp. This is normally easy, but
     * when there is a notch involved the status bar can remain a fixed pixel size.
     */
    private void updateMinimumHeight() {
        int sbHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        int qqsHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_quick_header_panel_height);
        if (mHideDragHandle) {
            qqsHeight -= mContext.getResources().getDimensionPixelSize(
                    R.dimen.quick_qs_drag_handle_height);
        }

        if (mIsQuickQsBrightnessEnabled) {
           if (!mHeaderImageEnabled) {
               qqsHeight += mContext.getResources().getDimensionPixelSize(
                       R.dimen.brightness_mirror_height)
                       + mContext.getResources().getDimensionPixelSize(
                       R.dimen.qs_tile_margin_top);
           } else {
               qqsHeight += mContext.getResources().getDimensionPixelSize(
                       R.dimen.brightness_mirror_height)
                       + mContext.getResources().getDimensionPixelSize(
                       R.dimen.qs_tile_margin_top) + mHeaderImageHeight;
           }
        }

        setMinimumHeight(sbHeight + qqsHeight);
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        updateMinimumHeight();

        // Update height for a few views, especially due to landscape mode restricting space.
        mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());

        int topMargin = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) + (mHeaderImageEnabled ?
                resources.getDimensionPixelSize(R.dimen.qs_header_image_offset) : 0);

        mSystemIconsView.getLayoutParams().height = topMargin;
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        RelativeLayout.LayoutParams headerPanel = (RelativeLayout.LayoutParams)
                mHeaderQsPanel.getLayoutParams();
        headerPanel.addRule(RelativeLayout.BELOW, R.id.quick_qs_status_icons);

        if (mIsQuickQsBrightnessEnabled) {
            if (mBrightnessSlider == 3) {
                headerPanel.addRule(RelativeLayout.BELOW, R.id.quick_qs_brightness_bar);
            }
            if (mQuickQsBrightness.getVisibility() == View.GONE) {
                mQuickQsBrightness.setVisibility(View.VISIBLE);
            }
            mMinBrightness.setVisibility(mBrightnessButton ? VISIBLE : GONE);
            mMaxBrightness.setVisibility(mBrightnessButton ? VISIBLE : GONE);
            updateIconPos();
       } else {
            mQuickQsBrightness.setVisibility(View.GONE);
        }

        mHeaderQsPanel.setLayoutParams(headerPanel);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = topMargin;
        } else {
            int qsHeight = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.quick_qs_total_height);

            if (mHeaderImageEnabled) {
                qsHeight += resources.getDimensionPixelSize(R.dimen.qs_header_image_offset);
            }

            if (mHideDragHandle) {
                qsHeight -= resources.getDimensionPixelSize(
                        R.dimen.quick_qs_drag_handle_height);
            }
            lp.height = Math.max(getMinimumHeight(), qsHeight);
        }

        setLayoutParams(lp);

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
        updatePrivacyChipAlphaAnimator();
    }

    public void updateSettings() {
        mHeaderImageEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        updateResources();
        updateDataUsageView();
        updateStatusbarProperties();
    }

    private void updateDataUsageView() {
        if (mDataUsageView.isDataUsageEnabled() != 0) {
            mDataUsageView.setVisibility(View.VISIBLE);
            mDataUsageImage.setVisibility(View.VISIBLE);
            mDataUsageLayout.setVisibility(View.VISIBLE);
        } else {
            mDataUsageView.setVisibility(View.GONE);
            mDataUsageImage.setVisibility(View.GONE);
            mDataUsageLayout.setVisibility(View.GONE);
        }
    }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0, 0)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 0, 1)
                .build();
    }

    private void updatePrivacyChipAlphaAnimator() {
        mPrivacyChipAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mPrivacyChip, "alpha", 1, 0, 1)
                .build();
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (forceExpanded) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
            if (keyguardExpansionFraction > 0) {
                mHeaderTextContainerView.setVisibility(VISIBLE);
            } else {
                mHeaderTextContainerView.setVisibility(INVISIBLE);
            }
        }
        if (mPrivacyChipAlphaAnimator != null) {
            mPrivacyChip.setExpanded(expansionFraction > 0.5);
            mPrivacyChipAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (mIsQuickQsBrightnessEnabled) {
            if (keyguardExpansionFraction > 0) {
                mQuickQsBrightness.setVisibility(INVISIBLE);
            } else {
                mQuickQsBrightness.setVisibility(VISIBLE);
            }
        }
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mHeaderTextContainerView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsStatusIcons.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsBrightness.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                cutout, getDisplay());
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end),
                    0);
        } else {
            mSystemIconsView.setPadding(
                    padding.first,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    padding.second, 0);

        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mSpace.getLayoutParams();
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty()) {
                mHasTopCutout = false;
                lp.width = 0;
                mSpace.setVisibility(View.GONE);
            } else {
                mHasTopCutout = true;
                lp.width = topCutout.width();
                mSpace.setVisibility(View.VISIBLE);
            }
        }
        mSpace.setLayoutParams(lp);
        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
        // Offset container padding to align with QS brightness bar.
        final int sp = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        RelativeLayout.LayoutParams lpQuickQsBrightness = (RelativeLayout.LayoutParams)
                mQuickQsBrightness.getLayoutParams();
        lpQuickQsBrightness.setMargins(sp - mPaddingLeft, 0, sp - mPaddingRight, 0);
        lpQuickQsBrightness.addRule(RelativeLayout.BELOW, R.id.header_text_container);
        if (mBrightnessSlider == 4) {
            lpQuickQsBrightness.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        } else {
            lpQuickQsBrightness.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0);
        }
        mQuickQsBrightness.setLayoutParams(lpQuickQsBrightness);
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mStatusBarIconController.removeIconGroup(mIconManager);
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.removeTunable(this);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        mCarrierGroup.setListening(mListening);

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mContext.registerReceiver(mRingerReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
            mPrivacyItemController.addCallback(mPICCallback);
            mBrightnessController.registerCallbacks();
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mPrivacyItemController.removeCallback(mPICCallback);
            mContext.unregisterReceiver(mRingerReceiver);
            mBrightnessController.unregisterCallbacks();
            mPrivacyChipLogged = false;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
            if (mNextAlarm.getShowIntent() != null) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mNextAlarm.getShowIntent());
            } else {
                Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        } else if (v == mPrivacyChip) {
            // Makes sure that the builder is grabbed as soon as the chip is pressed
            PrivacyDialogBuilder builder = mPrivacyChip.getBuilder();
            if (builder.getAppsAndTypes().size() == 0) return;
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                    StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_CLICKED);
            mUiHandler.post(() -> {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
                mHost.collapsePanels();
            });
        } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Settings.ACTION_SOUND_SETTINGS), 0);
        }
        } else if (v == mMinBrightness) {
            final ContentResolver resolver = getContext().getContentResolver();
            int currentValue = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, 0, UserHandle.USER_CURRENT);
            int brightness = currentValue - 2;
            if (currentValue != 0) {
                int math = Math.max(0, brightness);
                Settings.System.putIntForUser(resolver,
                        Settings.System.SCREEN_BRIGHTNESS, math, UserHandle.USER_CURRENT);
            }
        } else if (v == mMaxBrightness) {
            final ContentResolver resolver = getContext().getContentResolver();
            int currentValue = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, 0, UserHandle.USER_CURRENT);
            int brightness = currentValue + 2;
            if (currentValue != 255) {
                int math = Math.min(255, brightness);
                Settings.System.putIntForUser(resolver,
                        Settings.System.SCREEN_BRIGHTNESS, math, UserHandle.USER_CURRENT);
            }
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    @Override
    public void onZenChanged(int zen) {
        updateStatusText();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateStatusText();
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);


        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);
        mBatteryRemainingIcon.onDarkChanged(tintArea, intensity, fillColor);
        mBatteryIcon.setColorsFromContext(mHost.getContext());
        mBatteryIcon.onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = android.text.format.DateFormat
                .is24HourFormat(mContext, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), skeleton);
        return android.text.format.DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            // Prevents these views from getting set a margin.
            // The Icon views all have the same padding set in XML to be aligned.
            if (v == mSystemIconsView || v == mQuickQsStatusIcons || v == mHeaderQsPanel
                    || v == mHeaderTextContainerView) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }

    private void updateBatteryStyle() {
        int style;
        if (mQSBatteryStyle == -1) {
            style = mStatusBarBatteryStyle;
        } else {
            style = mQSBatteryStyle;
        }
        mBatteryRemainingIcon.mBatteryStyle = style;
        mBatteryIcon.mBatteryStyle = style;
        mBatteryRemainingIcon.updateBatteryStyle();
        mBatteryRemainingIcon.updatePercentView();
        mBatteryRemainingIcon.updateVisibility();
        mBatteryIcon.updateBatteryStyle();
        mBatteryIcon.updatePercentView();
        mBatteryIcon.updateVisibility();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case StatusBarIconController.ICON_BLACKLIST:
                 mClockView.setClockVisibleByUser(!StatusBarIconController.getIconBlacklist(newValue)
                    .contains("clock"));
                break;
            case QS_SHOW_BATTERY_PERCENT:
                mBatteryRemainingIcon.mShowBatteryPercent =
                        TunerService.parseInteger(newValue, 2);
                mBatteryIcon.mShowBatteryPercent =
                        TunerService.parseInteger(newValue, 2);
                mBatteryRemainingIcon.updatePercentView();
                mBatteryRemainingIcon.updateVisibility();
                mBatteryIcon.updatePercentView();
                mBatteryIcon.updateVisibility();
                break;
            case QS_SHOW_BATTERY_ESTIMATE:
                mBatteryRemainingIcon.mShowBatteryEstimate =
                        TunerService.parseInteger(newValue, 0);
                mBatteryIcon.mShowBatteryEstimate =
                        TunerService.parseInteger(newValue, 0);
                mBatteryRemainingIcon.updatePercentView();
                mBatteryRemainingIcon.updateVisibility();
                mBatteryIcon.updatePercentView();
                mBatteryIcon.updateVisibility();
                break;
            case STATUS_BAR_BATTERY_STYLE:
                mStatusBarBatteryStyle =
                        TunerService.parseInteger(newValue, 0);
                updateBatteryStyle();
                break;
            case QS_BATTERY_STYLE:
                mQSBatteryStyle =
                        TunerService.parseInteger(newValue, -1);
                updateBatteryStyle();
                break;
            case QS_BATTERY_LOCATION:
                int location =
                        TunerService.parseInteger(newValue, 0);
                if (location == 0) {
                    mBatteryIcon.setVisibility(View.GONE);
                    mBatteryRemainingIcon.setVisibility(View.VISIBLE);
                } else {
                    mBatteryRemainingIcon.setVisibility(View.GONE);
                    mBatteryIcon.setVisibility(View.VISIBLE);
                }
                break;
            case QSFooterImpl.QS_SHOW_DRAG_HANDLE:
            mHideDragHandle = newValue != null && Integer.parseInt(newValue) == 0;
            updateResources();
                break;
            case SHOW_QS_CLOCK:
                boolean showClock =
                        TunerService.parseIntegerSwitch(newValue, true);
                mClockView.setClockVisibleByUser(showClock);
                break;
            case Settings.System.QS_DATAUSAGE:
                 updateSettings();
                break;
            case QS_SHOW_BRIGHTNESS_SLIDER:
                mBrightnessSlider =
                        TunerService.parseInteger(newValue, 3);
                mIsQuickQsBrightnessEnabled = mBrightnessSlider > 2;
                updateResources();
                break;
            case QS_SHOW_AUTO_BRIGHTNESS:
                mIsQsAutoBrightnessEnabled =
                        TunerService.parseInteger(newValue, 0);
                updateResources();
                break;
            case QSPanel.QS_SHOW_BRIGHTNESS_BUTTONS:
                mBrightnessButton =
                        TunerService.parseIntegerSwitch(newValue, true);
                updateResources();
                break;
            default:
                break;
        }
    }

    // Update color schemes in landscape to use wallpaperTextColor
    private void updateStatusbarProperties() {
        boolean shouldUseWallpaperTextColor = (mLandscape || mForceHideQsStatusBar) && !mHeaderImageEnabled;
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
    }


    private void updateIconPos() {
        boolean available = resources.getBoolean(
                 com.android.internal.R.bool.config_automatic_brightness_available);
        if (!available) return;
        if (mIsQsAutoBrightnessEnabled == 0) {
            mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.GONE);
            mQuickQsBrightness.findViewById(R.id.brightness_icon_left).setVisibility(View.GONE);
        } else if (mIsQsAutoBrightnessEnabled == 0)  {
            mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.GONE);
            mQuickQsBrightness.findViewById(R.id.brightness_icon_left).setVisibility(View.VISIBLE);
        } else if (mIsQsAutoBrightnessEnabled == 0)  {
            mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.VISIBLE);
            mQuickQsBrightness.findViewById(R.id.brightness_icon_left).setVisibility(View.GONE);
        }
    }
}
