/*
 * Copyright 2026 Ayufa.
 * Part of Ayufa Patches (GPL-3.0).
 */

package app.ayufa.extension.youtube;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Workaround for a playback bug in the official Morphe patches:
 * a video resumed from the watch history can open with the video
 * frame frozen at the starting position while the audio keeps
 * playing. Seeking inside an already playing video and opening the
 * same video from Home / Search do not reproduce it, so the freeze
 * happens on the player-start path used when a video is opened from
 * the history feed.
 *
 * <p>This is a built-in workaround, not a user-facing feature: it has
 * no patch entry, no option and no setting, and it is always active
 * (see ResumedPlaybackFixPatch, pulled in by the "Hide live chat
 * clutter" patch). Remove it once upstream Morphe fixes the bug.
 *
 * <p>How it works: the media progress callback fires about once per
 * second during playback. On the first tick of each video, if the
 * video is a regular (non-live, non-Shorts) video that started at a
 * resumed position (well past the beginning), a single tiny relative
 * seek (+250 ms) is issued shortly afterwards. A seek forces the
 * player to re-acquire and re-render the video at the new position,
 * which unsticks the frozen first frame; on a video that was not
 * frozen the seek is a barely noticeable quarter-second jump at the
 * (already approximate) resume point. Nothing is done for videos
 * started from the beginning, live streams, Shorts, or videos that
 * are about to end.
 *
 * <p>Playback state is read from the official Morphe extension class
 * {@code app.morphe.extension.youtube.patches.VideoInformation}
 * through reflection, the same approach as {@link LiveLatencyKeeper},
 * so this class has no compile-time dependency on the official
 * patches. If they are not applied, every call is a no-op.
 */
@SuppressWarnings("unused")
public final class ResumedPlaybackFix {

    private static final String TAG = "AyufaResumeFix";

    /**
     * A first reported position at or past this point means the video
     * was resumed, not started from the beginning.
     */
    private static final long RESUME_THRESHOLD_MS = 10_000L;

    /** Do not touch a video that ends within this time after the resume point. */
    private static final long END_GUARD_MS = 3_000L;

    /** Delay between detecting a resumed start and the unstick seek. */
    private static final long UNSTICK_DELAY_MS = 400L;

    /** The unstick seek distance: enough to force a real seek, small enough to not notice. */
    private static final long UNSTICK_SEEK_FORWARD_MS = 250L;

    // Reflection handles to Morphe's VideoInformation (official patches).
    private static boolean apiResolutionAttempted;
    private static Method videoInformationGetVideoId;
    private static Method videoInformationIsLiveStream;
    private static Method videoInformationLastVideoIdIsShort;
    private static Method videoInformationGetVideoLength;
    private static Method videoInformationGetVideoTime;
    private static Method videoInformationSeekToRelative;
    private static Method videoInformationSeekTo;

    // Per-video state.
    private static String currentVideoId = "";
    private static boolean evaluatedForCurrentVideo;

    private ResumedPlaybackFix() {
    }

    /**
     * Injection point. Called approximately once per second while a video
     * is playing (and while it is loading), with the current playback time.
     *
     * @param playbackTimeMs Current playback position in milliseconds.
     */
    public static void videoTimeChanged(long playbackTimeMs) {
        try {
            if (playbackTimeMs <= 0) {
                return;
            }
            if (!resolveVideoInformationApi()) {
                return;
            }

            String videoId = (String) videoInformationGetVideoId.invoke(null);
            if (videoId == null || videoId.isEmpty()) {
                return;
            }
            if (!videoId.equals(currentVideoId)) {
                currentVideoId = videoId;
                evaluatedForCurrentVideo = false;
            }
            if (evaluatedForCurrentVideo) {
                return;
            }
            evaluatedForCurrentVideo = true;

            if (playbackTimeMs < RESUME_THRESHOLD_MS) {
                // Started (nearly) from the beginning: not a resume.
                return;
            }
            if (isLiveStream() || isShort()) {
                return;
            }
            long videoLength = getVideoLength();
            if (videoLength > 0 && videoLength - playbackTimeMs < END_GUARD_MS) {
                return;
            }

            final String targetVideoId = videoId;
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> unstick(targetVideoId), UNSTICK_DELAY_MS);
        } catch (Throwable ex) {
            // Never break playback because of this workaround.
            Log.e(TAG, "videoTimeChanged failure", ex);
        }
    }

    /**
     * Issues the one-time tiny seek that forces the player to re-render
     * the video frame, unless the player has moved on to another video
     * in the meantime. Runs on the main thread.
     */
    private static void unstick(String targetVideoId) {
        try {
            String videoId = (String) videoInformationGetVideoId.invoke(null);
            if (!targetVideoId.equals(videoId) || isLiveStream() || isShort()) {
                return;
            }
            if (videoInformationSeekToRelative != null) {
                videoInformationSeekToRelative.invoke(null, UNSTICK_SEEK_FORWARD_MS);
                Log.d(TAG, "Resumed playback unstick: seek forward by "
                        + UNSTICK_SEEK_FORWARD_MS + " ms");
                return;
            }
            if (videoInformationSeekTo != null && videoInformationGetVideoTime != null) {
                long now = (Long) videoInformationGetVideoTime.invoke(null);
                videoInformationSeekTo.invoke(null, now + UNSTICK_SEEK_FORWARD_MS);
                Log.d(TAG, "Resumed playback unstick: seek to "
                        + (now + UNSTICK_SEEK_FORWARD_MS) + " ms");
            }
        } catch (Throwable ex) {
            Log.e(TAG, "Unstick seek failed", ex);
        }
    }

    private static boolean isLiveStream() {
        try {
            return videoInformationIsLiveStream != null
                    && (Boolean) videoInformationIsLiveStream.invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isShort() {
        try {
            return videoInformationLastVideoIdIsShort != null
                    && (Boolean) videoInformationLastVideoIdIsShort.invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long getVideoLength() {
        try {
            if (videoInformationGetVideoLength != null) {
                return (Long) videoInformationGetVideoLength.invoke(null);
            }
        } catch (Throwable ignored) {
            // Unknown length: the end guard simply does not apply.
        }
        return 0;
    }

    /**
     * Resolves the static methods of Morphe's VideoInformation class once.
     *
     * @return True if the official Morphe patches are present.
     */
    private static boolean resolveVideoInformationApi() {
        if (apiResolutionAttempted) {
            return videoInformationGetVideoId != null;
        }
        apiResolutionAttempted = true;
        try {
            Class<?> videoInformation =
                    Class.forName("app.morphe.extension.youtube.patches.VideoInformation");
            videoInformationGetVideoId = videoInformation.getMethod("getVideoId");
            videoInformationIsLiveStream = videoInformation.getMethod("isLiveStream");
            try {
                videoInformationSeekToRelative =
                        videoInformation.getMethod("seekToRelative", long.class);
            } catch (Throwable ignored) {
                videoInformationSeekToRelative = null;
            }
            try {
                videoInformationSeekTo = videoInformation.getMethod("seekTo", long.class);
                videoInformationGetVideoTime = videoInformation.getMethod("getVideoTime");
            } catch (Throwable ignored) {
                videoInformationSeekTo = null;
                videoInformationGetVideoTime = null;
            }
            try {
                videoInformationLastVideoIdIsShort =
                        videoInformation.getMethod("lastVideoIdIsShort");
            } catch (Throwable ignored) {
                videoInformationLastVideoIdIsShort = null;
            }
            try {
                videoInformationGetVideoLength =
                        videoInformation.getMethod("getVideoLength");
            } catch (Throwable ignored) {
                videoInformationGetVideoLength = null;
            }
            return videoInformationSeekToRelative != null || videoInformationSeekTo != null;
        } catch (Throwable ex) {
            // Official Morphe patches are not applied (or changed).
            // Silently disable: this workaround is an add-on to them.
            videoInformationGetVideoId = null;
            Log.d(TAG, "Morphe VideoInformation not available; resume fix disabled");
            return false;
        }
    }
}
