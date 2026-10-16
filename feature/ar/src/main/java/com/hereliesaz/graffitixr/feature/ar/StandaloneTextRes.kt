package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R as DesignR

/**
 * The string resource (in `core/design`, default + every locale) for each [StandaloneTextKey].
 *
 * Deliberately a plain exhaustive `when` of `DesignR.string.<name>` entries: the compiler forces a
 * new key to be mapped, and `StandaloneTextResourcesTest` reads this file's source to check that
 * every mapped name exists, non-empty, in every `values*` directory with matching format arguments.
 */
@StringRes
internal fun StandaloneTextKey.stringRes(): Int = when (this) {
    StandaloneTextKey.FAILURE_NATIVE_LIBRARY_UNAVAILABLE -> DesignR.string.standalone_failure_native_library_unavailable
    StandaloneTextKey.FAILURE_CAMERA_UNAVAILABLE -> DesignR.string.standalone_failure_camera_unavailable
    StandaloneTextKey.FAILURE_INTRINSICS_UNAVAILABLE -> DesignR.string.standalone_failure_intrinsics_unavailable
    StandaloneTextKey.FAILURE_REFERENCE_TOO_WEAK -> DesignR.string.standalone_failure_reference_too_weak
    StandaloneTextKey.FAILURE_NO_PAGE_MATCH -> DesignR.string.standalone_failure_no_page_match
    StandaloneTextKey.FAILURE_STALE_OBSERVATION -> DesignR.string.standalone_failure_stale_observation
    StandaloneTextKey.FAILURE_REPROJECTION_ERROR -> DesignR.string.standalone_failure_reprojection_error
    StandaloneTextKey.FAILURE_INSUFFICIENT_INLIERS -> DesignR.string.standalone_failure_insufficient_inliers
    StandaloneTextKey.FAILURE_POSE_JUMP -> DesignR.string.standalone_failure_pose_jump
    StandaloneTextKey.FAILURE_PERSISTED_TARGET_CORRUPT -> DesignR.string.standalone_failure_persisted_target_corrupt
    StandaloneTextKey.FAILURE_INTERNAL -> DesignR.string.standalone_failure_internal
    StandaloneTextKey.TRACKING_STOPPED -> DesignR.string.standalone_tracking_stopped
    StandaloneTextKey.STATUS_MAPPING -> DesignR.string.standalone_status_mapping
    StandaloneTextKey.STATUS_PREPARING -> DesignR.string.standalone_status_preparing
    StandaloneTextKey.STATUS_FINDING -> DesignR.string.standalone_status_finding
    StandaloneTextKey.QUALITY_TOO_SMALL -> DesignR.string.standalone_quality_too_small
    StandaloneTextKey.QUALITY_LOW_CONTRAST -> DesignR.string.standalone_quality_low_contrast
    StandaloneTextKey.QUALITY_BLURRY -> DesignR.string.standalone_quality_blurry
    StandaloneTextKey.QUALITY_NOT_STRONG_ENOUGH -> DesignR.string.standalone_quality_not_strong_enough
    StandaloneTextKey.QUALITY_TOO_DARK -> DesignR.string.standalone_quality_too_dark
    StandaloneTextKey.QUALITY_TOO_BRIGHT -> DesignR.string.standalone_quality_too_bright
    StandaloneTextKey.PHOTOSPHERE_NO_SENSOR -> DesignR.string.standalone_photosphere_no_sensor
    StandaloneTextKey.PHOTOSPHERE_COMPASS_NOT_READY -> DesignR.string.standalone_photosphere_compass_not_ready
    StandaloneTextKey.PHOTOSPHERE_NOT_MAPPED -> DesignR.string.standalone_photosphere_not_mapped
    StandaloneTextKey.CAPTURE_FAILED -> DesignR.string.standalone_capture_failed
    StandaloneTextKey.RECTIFY_FAILED -> DesignR.string.standalone_rectify_failed
    StandaloneTextKey.TARGET_TOO_WEAK -> DesignR.string.standalone_target_too_weak
}

/** Resolve to the current locale outside composition (callbacks, view models). */
internal fun StandaloneText.resolve(context: Context): String =
    context.getString(key.stringRes(), *args.toTypedArray())

/** Resolve to the current locale in composition. */
@Composable
internal fun StandaloneText.resolve(): String =
    stringResource(key.stringRes(), *args.toTypedArray())
