/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY;
import static com.android.systemui.statusbar.notification.interruption.HeadsUpController.alertAgain;

import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.dagger.IncomingHeader;
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Coordinates heads up notification (HUN) interactions with the notification pipeline based on
 * the HUN state reported by the {@link HeadsUpManager}. In this class we only consider one
 * notification, in particular the {@link HeadsUpManager#getTopEntry()}, to be HeadsUpping at a
 * time even though other notifications may be queued to heads up next.
 *
 * The current HUN, but not HUNs that are queued to heads up, will be:
 * - Lifetime extended until it's no longer heads upping.
 * - Promoted out of its group if it's a child of a group.
 * - In the HeadsUpCoordinatorSection. Ordering is configured in {@link NotifCoordinators}.
 * - Removed from HeadsUpManager if it's removed from the NotificationCollection.
 *
 * Note: The inflation callback in {@link PreparationCoordinator} handles showing HUNs.
 */
@CoordinatorScope
public class HeadsUpCoordinator implements Coordinator {
    private static final String TAG = "HeadsUpCoordinator";

    private final HeadsUpManager mHeadsUpManager;
    private final HeadsUpViewBinder mHeadsUpViewBinder;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final NodeController mIncomingHeaderController;
    private final DelayableExecutor mExecutor;

    private NotifLifetimeExtender.OnEndLifetimeExtensionCallback mEndLifetimeExtension;
    // notifs we've extended the lifetime for
    private final ArraySet<NotificationEntry> mNotifsExtendingLifetime = new ArraySet<>();

    @Inject
    public HeadsUpCoordinator(
            HeadsUpManager headsUpManager,
            HeadsUpViewBinder headsUpViewBinder,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationRemoteInputManager remoteInputManager,
            @IncomingHeader NodeController incomingHeaderController,
            @Main DelayableExecutor executor) {
        mHeadsUpManager = headsUpManager;
        mHeadsUpViewBinder = headsUpViewBinder;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mRemoteInputManager = remoteInputManager;
        mIncomingHeaderController = incomingHeaderController;
        mExecutor = executor;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        pipeline.addCollectionListener(mNotifCollectionListener);
        pipeline.addPromoter(mNotifPromoter);
        pipeline.addNotificationLifetimeExtender(mLifetimeExtender);
    }

    public NotifSectioner getSectioner() {
        return mNotifSectioner;
    }

    private void onHeadsUpViewBound(NotificationEntry entry) {
        mHeadsUpManager.showNotification(entry);
    }

    private final NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {
        /**
         * Notification was just added and if it should heads up, bind the view and then show it.
         */
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            if (mNotificationInterruptStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpViewBinder.bindHeadsUpView(
                        entry,
                        HeadsUpCoordinator.this::onHeadsUpViewBound);
            }
        }

        /**
         * Notification could've updated to be heads up or not heads up. Even if it did update to
         * heads up, if the notification specified that it only wants to alert once, don't heads
         * up again.
         */
        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            boolean hunAgain = alertAgain(entry, entry.getSbn().getNotification());
            // includes check for whether this notification should be filtered:
            boolean shouldHeadsUp = mNotificationInterruptStateProvider.shouldHeadsUp(entry);
            final boolean wasHeadsUp = mHeadsUpManager.isAlerting(entry.getKey());
            if (wasHeadsUp) {
                if (shouldHeadsUp) {
                    mHeadsUpManager.updateNotification(entry.getKey(), hunAgain);
                } else if (!mHeadsUpManager.isEntryAutoHeadsUpped(entry.getKey())) {
                    // We don't want this to be interrupting anymore, let's remove it
                    mHeadsUpManager.removeNotification(
                            entry.getKey(), false /* removeImmediately */);
                }
            } else if (shouldHeadsUp && hunAgain) {
                // This notification was updated to be heads up, show it!
                mHeadsUpViewBinder.bindHeadsUpView(
                        entry,
                        HeadsUpCoordinator.this::onHeadsUpViewBound);
            }
        }

        /**
         * Stop alerting HUNs that are removed from the notification collection
         */
        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            final String entryKey = entry.getKey();
            if (mHeadsUpManager.isAlerting(entryKey)) {
                boolean removeImmediatelyForRemoteInput =
                        mRemoteInputManager.isSpinning(entryKey)
                                && !FORCE_REMOTE_INPUT_HISTORY;
                mHeadsUpManager.removeNotification(entry.getKey(), removeImmediatelyForRemoteInput);
            }
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mHeadsUpViewBinder.abortBindCallback(entry);
        }
    };

    private final NotifLifetimeExtender mLifetimeExtender = new NotifLifetimeExtender() {
        @Override
        public @NonNull String getName() {
            return TAG;
        }

        @Override
        public void setCallback(@NonNull OnEndLifetimeExtensionCallback callback) {
            mEndLifetimeExtension = callback;
        }

        @Override
        public boolean shouldExtendLifetime(@NonNull NotificationEntry entry, int reason) {
            boolean isShowingHun = isCurrentlyShowingHun(entry);
            if (isShowingHun) {
                if (isSticky(entry)) {
                    long removeAfterMillis = mHeadsUpManager.getEarliestRemovalTime(entry.getKey());
                    if (removeAfterMillis <= 0) return false;
                    mExecutor.executeDelayed(() -> {
                        // make sure that the entry was not updated
                        long removeAfterMillis2 =
                                mHeadsUpManager.getEarliestRemovalTime(entry.getKey());
                        if (mNotifsExtendingLifetime.contains(entry) && removeAfterMillis2 <= 0) {
                            mHeadsUpManager.removeNotification(entry.getKey(), true);
                        }
                    }, removeAfterMillis);
                }
                mNotifsExtendingLifetime.add(entry);
            }
            return isShowingHun;
        }

        @Override
        public void cancelLifetimeExtension(@NonNull NotificationEntry entry) {
            mNotifsExtendingLifetime.remove(entry);
        }
    };

    private final NotifPromoter mNotifPromoter = new NotifPromoter(TAG) {
        @Override
        public boolean shouldPromoteToTopLevel(NotificationEntry entry) {
            return isCurrentlyShowingHun(entry);
        }
    };

    private final NotifSectioner mNotifSectioner = new NotifSectioner("HeadsUp",
            NotificationPriorityBucketKt.BUCKET_HEADS_UP) {
        @Override
        public boolean isInSection(ListEntry entry) {
            return isCurrentlyShowingHun(entry);
        }

        @Nullable
        @Override
        public NodeController getHeaderNodeController() {
            // TODO: remove SHOW_ALL_SECTIONS, this redundant method, and mIncomingHeaderController
            if (RankingCoordinator.SHOW_ALL_SECTIONS) {
                return mIncomingHeaderController;
            }
            return null;
        }
    };

    private final OnHeadsUpChangedListener mOnHeadsUpChangedListener =
            new OnHeadsUpChangedListener() {
        @Override
        public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
            if (!isHeadsUp) {
                mHeadsUpViewBinder.unbindHeadsUpView(entry);
                endNotifLifetimeExtensionIfExtended(entry);
            }
        }
    };

    private boolean isSticky(NotificationEntry entry) {
        return mHeadsUpManager.isSticky(entry.getKey());
    }

    private boolean isCurrentlyShowingHun(ListEntry entry) {
        return mHeadsUpManager.isAlerting(entry.getKey());
    }

    private void endNotifLifetimeExtensionIfExtended(NotificationEntry entry) {
        if (mNotifsExtendingLifetime.remove(entry)) {
            mEndLifetimeExtension.onEndLifetimeExtension(mLifetimeExtender, entry);
        }
    }
}
