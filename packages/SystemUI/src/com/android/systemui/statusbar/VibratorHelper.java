/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.os.VibrationAttributes;
import android.os.Handler;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class VibratorHelper {

    private final Vibrator mVibrator;
    private final Context mContext;
    private boolean mHapticFeedbackEnabled;
    private static final AudioAttributes STATUS_BAR_VIBRATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build();
    final private ContentObserver mVibrationObserver = new ContentObserver(Handler.getMain()) {
        @Override
        public void onChange(boolean selfChange) {
            updateHapticFeedBackEnabled();
        }
    };
    private final Executor mExecutor;

    /**
     */
    @Inject
    public VibratorHelper(Context context, @Background Executor executor) {
        mContext = context;
        mExecutor = executor;
        mVibrator = context.getSystemService(Vibrator.class);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), true,
                mVibrationObserver);
        mVibrationObserver.onChange(false /* selfChange */);
    }
    
    private void updateHapticFeedBackEnabled() {
        mHapticFeedbackEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
    }

    /**
     * @see Vibrator#vibrate(long)
     */
    public void vibrate(final int effectId) {
    	if (mHapticFeedbackEnabled) {
        if (!hasVibrator()) {
            return;
        }
            mExecutor.execute(() ->
                   mVibrator.vibrate(VibrationEffect.get(effectId, false /* fallback */),
                            STATUS_BAR_VIBRATION_ATTRIBUTES));
	}
    }

    /**
     * @see Vibrator#vibrate(int, String, VibrationEffect, String, VibrationAttributes)
     */
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe,
            String reason, @NonNull VibrationAttributes attributes) {
    	if (mHapticFeedbackEnabled) {
        if (!hasVibrator()) {
            return;
        }
	}
        mExecutor.execute(() -> mVibrator.vibrate(uid, opPkg, vibe, reason, attributes));
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect, AudioAttributes)
     */
    public void vibrate(@NonNull VibrationEffect effect, @NonNull AudioAttributes attributes) {
    	if (mHapticFeedbackEnabled) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect, attributes));
       }
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect)
     */
    public void vibrate(@NotNull VibrationEffect effect) {
    	if (mHapticFeedbackEnabled) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect));
        }
    }

    /**
     * @see Vibrator#hasVibrator()
     */
    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    /**
     * @see Vibrator#cancel()
     */
    public void cancel() {
    	if (mHapticFeedbackEnabled) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(mVibrator::cancel);
        }
    }
}
