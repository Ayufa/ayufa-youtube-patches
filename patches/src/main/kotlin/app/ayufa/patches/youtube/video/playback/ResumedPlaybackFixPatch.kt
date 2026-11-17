package app.ayufa.patches.youtube.video.playback

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.ayufa.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE_MODERN
import app.ayufa.patches.youtube.video.livestream.MediaProgressFingerprint

private const val EXTENSION_CLASS = "Lapp/ayufa/extension/youtube/ResumedPlaybackFix;"

/**
 * Built-in workaround for the upstream Morphe bug where a video resumed
 * from the watch history opens with the video frame frozen at the
 * starting position while the audio keeps playing (seeking inside a
 * playing video and opening the same video from Home / Search are not
 * affected).
 *
 * This is deliberately NOT a user-facing patch: it has no name, no
 * options and no settings, so it never appears in the patch list. It is
 * pulled in with `dependsOn` by the "Hide live chat clutter" patch (the
 * patch that targets YouTube 21.30.216), so it is included by default
 * in every build for that target. The extension class itself ships in
 * the same extension file that the clutter patch already extends with.
 *
 * Remove this patch, the `dependsOn` reference and
 * `ResumedPlaybackFix.java` once upstream Morphe fixes the bug.
 */
@Suppress("unused")
val resumedPlaybackFixPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_YOUTUBE_MODERN)

    execute {
        // Hook the media progress callback, the same injection point the
        // live latency keeper uses: its called constructor receives the
        // current playback time as the first parameter (p1, p2), and
        // index 2 is right after the super constructor call.
        MediaProgressFingerprint.instructionMatches.first().getMethodCalled().addInstruction(
            2,
            "invoke-static { p1, p2 }, $EXTENSION_CLASS->videoTimeChanged(J)V"
        )
    }
}
