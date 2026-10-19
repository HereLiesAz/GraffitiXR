package com.hereliesaz.graffitixr.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.design.R

@Composable
fun rememberAppStrings(): AppStrings {
    val context = LocalContext.current
    return AppStrings(
        nav = rememberNavStrings(),
        common = AppStrings.Common(
            cancel = stringResource(R.string.cancel_button),
            done = stringResource(R.string.done_button),
            apply = stringResource(R.string.apply_button),
            close = stringResource(R.string.close_button),
            next = stringResource(R.string.next_button),
            save = stringResource(R.string.save_button),
            retake = stringResource(R.string.retake_button),
            capture = stringResource(R.string.capture_button),
            reset = stringResource(R.string.reset_button),
            generating = stringResource(R.string.generating)
        ),
        lib = AppStrings.Library(
            title = stringResource(R.string.lib_app_title),
            description = stringResource(R.string.lib_app_description),
            newProject = stringResource(R.string.lib_new_project),
            importProject = stringResource(R.string.lib_import_project),
            noProjects = stringResource(R.string.lib_no_projects),
            noProjectsHint = stringResource(R.string.lib_no_projects_hint),
            projectThumbnail = stringResource(R.string.lib_project_thumbnail),
            deleteProjectDesc = { name -> context.getString(R.string.lib_delete_project_desc, name) }
        ),
        settings = AppStrings.Settings(
            title = stringResource(R.string.settings_title),
            general = stringResource(R.string.settings_general_section),
            ar = stringResource(R.string.settings_ar_section),
            glowSection = stringResource(R.string.settings_glow_section),
            anticipationSection = stringResource(R.string.settings_anticipation_section),
            anticipationDesc = stringResource(R.string.settings_anticipation_desc),
            anticipationDistance = stringResource(R.string.settings_anticipation_distance),
            anticipationLatitude = stringResource(R.string.settings_anticipation_latitude),
            anticipationLongitude = stringResource(R.string.settings_anticipation_longitude),
            anticipationRoll = stringResource(R.string.settings_anticipation_roll),
            anticipationBattery = stringResource(R.string.settings_anticipation_battery),
            anticipationHeat = stringResource(R.string.settings_anticipation_heat),
            anticipationCompute = stringResource(R.string.settings_anticipation_compute),
            anticipationViews = { n -> context.getString(R.string.settings_anticipation_views, n) },
            fingerprintFeatures = stringResource(R.string.settings_fingerprint_features),
            fingerprintSuperPoint = stringResource(R.string.settings_fingerprint_superpoint),
            fingerprintHalfSphere = stringResource(R.string.settings_fingerprint_half_sphere),
            fingerprintBoth = stringResource(R.string.settings_fingerprint_both),
            fingerprintSuperPointDesc = stringResource(R.string.settings_fingerprint_superpoint_desc),
            fingerprintHalfSphereDesc = stringResource(R.string.settings_fingerprint_half_sphere_desc),
            fingerprintBothDesc = stringResource(R.string.settings_fingerprint_both_desc),
            power = stringResource(R.string.settings_power_section),
            privacy = stringResource(R.string.settings_privacy_section),
            developer = stringResource(R.string.settings_developer_section),
            powerSaving = stringResource(R.string.settings_power_saving_label),
            powerSavingDesc = stringResource(R.string.settings_power_saving_desc),
            languageLabel = stringResource(R.string.settings_language_label),
            dominantHand = stringResource(R.string.settings_hand_label),
            handRight = stringResource(R.string.settings_hand_right),
            handLeft = stringResource(R.string.settings_hand_left),
            diagOverlay = stringResource(R.string.settings_diag_label),
            layerFeaturePoints = stringResource(R.string.settings_layer_feature_points),
            layerPlaneGrids = stringResource(R.string.settings_layer_plane_grids),
            layerVoxels = stringResource(R.string.settings_layer_voxels),
            layerPoints = stringResource(R.string.settings_layer_points),
            layerMesh = stringResource(R.string.settings_layer_mesh),
            parallaxThreshold = stringResource(R.string.settings_parallax_threshold),
            cameraFps = stringResource(R.string.settings_camera_fps),
            on = stringResource(R.string.settings_on),
            off = stringResource(R.string.settings_off),
            scanMode = stringResource(R.string.settings_scan_mode_label),
            pointCloud = stringResource(R.string.settings_scan_mode_point_cloud),
            voxelHash = stringResource(R.string.settings_scan_mode_voxel_hash),
            muralMethod = stringResource(R.string.settings_mural_method_label),
            surfaceMesh = stringResource(R.string.settings_scan_mode_surface_mesh),
            cloudOffset = stringResource(R.string.settings_scan_mode_cloud_offset),
            anchorBoundary = stringResource(R.string.settings_anchor_boundary_label),
            glowColor = stringResource(R.string.settings_glow_color_label),
            glowIntensity = stringResource(R.string.settings_glow_intensity_label),
            glowStyle = stringResource(R.string.settings_glow_style_label),
            glowStyleWash = stringResource(R.string.settings_glow_style_wash),
            glowStyleWashDesc = stringResource(R.string.settings_glow_style_wash_desc),
            glowStyleMissing = stringResource(R.string.settings_glow_style_missing),
            glowStyleMissingDesc = stringResource(R.string.settings_glow_style_missing_desc),
            glowStyleOutline = stringResource(R.string.settings_glow_style_outline),
            glowStyleOutlineDesc = stringResource(R.string.settings_glow_style_outline_desc),
            glowStyleWashOutlined = stringResource(R.string.settings_glow_style_wash_outlined),
            glowStyleWashOutlinedDesc = stringResource(R.string.settings_glow_style_wash_outlined_desc),
            glowColorWhite = stringResource(R.string.settings_glow_color_white),
            glowColorLightGrey = stringResource(R.string.settings_glow_color_light_grey),
            glowColorMidGrey = stringResource(R.string.settings_glow_color_mid_grey),
            glowColorBlack = stringResource(R.string.settings_glow_color_black),
            glowColorPink = stringResource(R.string.settings_glow_color_pink),
            glowColorCyan = stringResource(R.string.settings_glow_color_cyan),
            units = stringResource(R.string.settings_units_label),
            imperial = stringResource(R.string.settings_units_imperial),
            metric = stringResource(R.string.settings_units_metric),
            canvasBg = stringResource(R.string.settings_canvas_bg_label),
            resetTutorials = stringResource(R.string.settings_reset_tutorials_label),
            resetTutorialsValue = stringResource(R.string.settings_reset_tutorials_value),
            appInfo = stringResource(R.string.settings_info_section),
            version = stringResource(R.string.settings_version_label),
            updates = stringResource(R.string.settings_updates_section),
            openReleases = stringResource(R.string.settings_open_releases_label),
            permissions = stringResource(R.string.settings_permissions_section),
            cameraAccess = stringResource(R.string.settings_camera_access),
            photoAccess = stringResource(R.string.settings_photo_access),
            granted = stringResource(R.string.settings_granted),
            denied = stringResource(R.string.settings_denied),
            openAppSettings = stringResource(R.string.settings_open_app_settings),
            copyright = stringResource(R.string.settings_copyright),
            wearable = stringResource(R.string.nav_wearable),
            useArCore = stringResource(R.string.settings_use_arcore),
            arCoreDepth = stringResource(R.string.settings_arcore_depth),
            monocularDepth = stringResource(R.string.settings_monocular_depth),
            ambientScan = stringResource(R.string.settings_ambient_scan),
            changePass = stringResource(R.string.settings_change_pass),
            adaptiveRate = stringResource(R.string.settings_adaptive_rate),
            crashReports = stringResource(R.string.settings_crash_reports),
            crashReportsDesc = stringResource(R.string.settings_crash_reports_desc),
            crashTokenLabel = stringResource(R.string.settings_crash_token_label),
            crashTokenSet = stringResource(R.string.settings_crash_token_set),
            crashTokenNotSet = stringResource(R.string.settings_crash_token_not_set),
            crashTokenDesc = stringResource(R.string.settings_crash_token_desc),
            crashTokenField = stringResource(R.string.settings_crash_token_field),
            canvasBlack = stringResource(R.string.settings_canvas_color_black),
            canvasDark = stringResource(R.string.settings_canvas_color_dark),
            canvasGrey = stringResource(R.string.settings_canvas_color_grey),
            canvasWhite = stringResource(R.string.settings_canvas_color_white),
            canvasNavy = stringResource(R.string.settings_canvas_color_navy),
            stateSelected = stringResource(R.string.state_selected),
            stateExpanded = stringResource(R.string.state_expanded),
            stateCollapsed = stringResource(R.string.state_collapsed),
            fpsDeviceDefault = stringResource(R.string.settings_fps_device_default),
            fpsDeviceMax = stringResource(R.string.settings_fps_device_max)
        ),
        editor = AppStrings.Editor(
            layers = stringResource(R.string.layers_title),
            brushSize = stringResource(R.string.brush_size_title),
            renameHint = stringResource(R.string.rename_hint),
            editTextHint = stringResource(R.string.edit_text_hint),
            copyEdits = stringResource(R.string.copy_edits),
            pasteEdits = stringResource(R.string.paste_edits),
            generatePoster = stringResource(R.string.generate_poster),
            duplicate = stringResource(R.string.duplicate),
            unlinkLayer = stringResource(R.string.unlink_layer),
            linkLayer = stringResource(R.string.link_layer),
            hideLayer = stringResource(R.string.hide_layer),
            showLayer = stringResource(R.string.show_layer),
            flattenAll = stringResource(R.string.flatten_all),
            delete = stringResource(R.string.delete),
            lock = stringResource(R.string.lock_button),
            freeze = stringResource(R.string.freeze_button),
            saveProjectHint = stringResource(R.string.save_project_hint),
            chooseFont = stringResource(R.string.choose_font),
            posterTitle = stringResource(R.string.poster_title),
            posterSelectLayers = stringResource(R.string.poster_select_layers),
            posterPhysicalSize = stringResource(R.string.poster_physical_size),
            posterGeneratePdf = stringResource(R.string.poster_generate_pdf)
        ),
        ar = AppStrings.Ar(
            depthUnsupported = stringResource(R.string.depth_unsupported),
            arcoreRequiredTitle = stringResource(R.string.arcore_required_title),
            arcoreRequiredText = stringResource(R.string.arcore_required_text),
            installArcore = stringResource(R.string.install_arcore),
            cameraPermissionRequired = stringResource(R.string.camera_permission_required),
            openSettings = stringResource(R.string.open_settings),
            targetCreationTitle = stringResource(R.string.target_creation_title),
            targetCreationText = stringResource(R.string.target_creation_text),
            targetReviewTitle = stringResource(R.string.target_review_title),
            fingerprintCloseUpsProgress = { stage, stages, done, total -> context.getString(R.string.fingerprint_closeups_progress, stage, stages, done, total) },
            diagCopied = stringResource(R.string.diag_copied),
            diagTitle = stringResource(R.string.diag_title),
            diagWaiting = stringResource(R.string.diag_waiting),
            planeConfirmQuestion = stringResource(R.string.plane_confirm_question),
            looksCorrect = stringResource(R.string.looks_correct),
            redetect = stringResource(R.string.redetect),
            scanning = stringResource(R.string.scanning_dots),
            relocLooking = stringResource(R.string.reloc_looking),
            relocTurnRight = { deg -> context.getString(R.string.reloc_turn_right, deg) },
            relocTurnLeft = { deg -> context.getString(R.string.reloc_turn_left, deg) },
            relocTiltUp = { deg -> context.getString(R.string.reloc_tilt_up, deg) },
            relocTiltDown = { deg -> context.getString(R.string.reloc_tilt_down, deg) },
            relocFacingIt = stringResource(R.string.reloc_facing_it),
            relocRecapture = stringResource(R.string.reloc_recapture),
            anchorLocked = stringResource(R.string.anchor_locked),
            matchedPercent = { pct -> context.getString(R.string.matched_percent, pct) },
            scanHintRecover = stringResource(R.string.scan_hint_recover),
            scanHintAmbient = { deg -> context.getString(R.string.scan_hint_ambient, deg) },
            scanHintTooDark = stringResource(R.string.scan_hint_too_dark),
            scanHintBuildMap = stringResource(R.string.scan_hint_build_map),
            scanHintCloser = stringResource(R.string.scan_hint_closer),
            scanHintHigherLower = stringResource(R.string.scan_hint_higher_lower),
            targetExclude = stringResource(R.string.target_exclude),
            targetInclude = stringResource(R.string.target_include),
            targetEraseMarks = stringResource(R.string.target_erase_marks),
            targetShowFeatures = stringResource(R.string.target_show_features),
            syncing = stringResource(R.string.syncing),
            reacquiringTarget = stringResource(R.string.reacquiring_target)
        ),
        help = AppStrings.Help(
            modeHost = R.string.help_mode_host,
            ar = R.string.help_ar,
            overlay = R.string.help_overlay,
            mockup = R.string.help_mockup,
            trace = R.string.help_trace,
            create = R.string.help_create,
            addImg = R.string.help_add_img,
            wall = R.string.help_wall,
            projectHost = R.string.help_project_host,
            newProject = R.string.help_new,
            saveProject = R.string.help_save,
            loadProject = R.string.help_load,
            exportImage = R.string.help_export,
            appSettings = R.string.help_settings,
            flashlight = R.string.help_light,
            lockTrace = R.string.help_lock_trace,
        ),
        adj = AppStrings.Adjustments(
            detail = stringResource(R.string.adj_detail),
            opacity = stringResource(R.string.adj_opacity),
            brightness = stringResource(R.string.adj_brightness),
            contrast = stringResource(R.string.adj_contrast),
            saturation = stringResource(R.string.adj_saturation),
            red = stringResource(R.string.adj_red),
            green = stringResource(R.string.adj_green),
            blue = stringResource(R.string.adj_blue),
            undo = stringResource(R.string.adj_undo),
            redo = stringResource(R.string.adj_redo),
            reset = stringResource(R.string.adj_reset),
            restorePlacement = stringResource(R.string.adj_restore_placement),
            magicAlign = stringResource(R.string.adj_magic_align)
        )
    )
}

data class AppStrings(
    val nav: NavStrings,
    val common: Common,
    val lib: Library,
    val settings: Settings,
    val editor: Editor,
    val ar: Ar,
    val help: Help,
    val adj: Adjustments
) {
    data class Common(
        val cancel: String,
        val done: String,
        val apply: String,
        val close: String,
        val next: String,
        val save: String,
        val retake: String,
        val capture: String,
        val reset: String,
        val generating: String
    )
    /**
     * Not a `data class`: [deleteProjectDesc] is a capturing lambda that is never referentially
     * (or structurally) equal across instances built from the same strings, which would defeat
     * `equals()`-based memoization (e.g. `remember(strings) {...}`) despite representing identical
     * content. [equals]/[hashCode] are hand-written to compare only the plain string fields.
     */
    class Library(
        val title: String,
        val description: String,
        val newProject: String,
        val importProject: String,
        val noProjects: String,
        val noProjectsHint: String,
        val projectThumbnail: String,
        val deleteProjectDesc: (String) -> String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Library) return false
            return title == other.title &&
                description == other.description &&
                newProject == other.newProject &&
                importProject == other.importProject &&
                noProjects == other.noProjects &&
                noProjectsHint == other.noProjectsHint &&
                projectThumbnail == other.projectThumbnail
        }

        override fun hashCode(): Int {
            var result = title.hashCode()
            result = 31 * result + description.hashCode()
            result = 31 * result + newProject.hashCode()
            result = 31 * result + importProject.hashCode()
            result = 31 * result + noProjects.hashCode()
            result = 31 * result + noProjectsHint.hashCode()
            result = 31 * result + projectThumbnail.hashCode()
            return result
        }
    }
    data class Settings(
        val title: String,
        val general: String,
        val ar: String,
        val glowSection: String,
        val anticipationSection: String = "Anticipation",
        val anticipationDesc: String = "",
        val anticipationDistance: String = "Distance",
        val anticipationLatitude: String = "Latitude",
        val anticipationLongitude: String = "Longitude",
        val anticipationRoll: String = "Roll",
        val anticipationBattery: String = "Battery throttles by",
        val anticipationHeat: String = "Heat throttles by",
        val anticipationCompute: String = "Load throttles by",
        val anticipationViews: (Int) -> String = { n -> "$n views per page at most" },
        val fingerprintFeatures: String = "Doodle",
        val fingerprintSuperPoint: String = "SuperPoint",
        val fingerprintHalfSphere: String = "Half sphere",
        val fingerprintBoth: String = "Both",
        val fingerprintSuperPointDesc: String = "SuperPoint features of the capture only. Holds through light and wear; about 1.5 MB per Doodle. Applies to the next Doodle.",
        val fingerprintHalfSphereDesc: String = "ORB features of the capture and its 49 close-ups. Strong up close and small, under 0.1 MB per Doodle. Applies to the next Doodle.",
        val fingerprintBothDesc: String = "SuperPoint features of the capture and its 49 close-ups. The strongest; about 2–3 MB per Doodle. Applies to the next Doodle.",
        val power: String,
        val privacy: String,
        val developer: String,
        val powerSaving: String,
        val powerSavingDesc: String,
        val languageLabel: String,
        val dominantHand: String,
        val handRight: String,
        val handLeft: String,
        val diagOverlay: String,
        val layerFeaturePoints: String,
        val layerPlaneGrids: String,
        val layerVoxels: String,
        val layerPoints: String,
        val layerMesh: String,
        val parallaxThreshold: String,
        val cameraFps: String,
        val on: String,
        val off: String,
        val scanMode: String,
        val pointCloud: String,
        val voxelHash: String,
        val muralMethod: String,
        val surfaceMesh: String,
        val cloudOffset: String,
        val anchorBoundary: String,
        val glowColor: String,
        val glowIntensity: String,
        val glowStyle: String,
        val glowStyleWash: String,
        val glowStyleWashDesc: String,
        val glowStyleMissing: String,
        val glowStyleMissingDesc: String,
        val glowStyleOutline: String,
        val glowStyleOutlineDesc: String,
        val glowStyleWashOutlined: String,
        val glowStyleWashOutlinedDesc: String,
        val glowColorWhite: String,
        val glowColorLightGrey: String,
        val glowColorMidGrey: String,
        val glowColorBlack: String,
        val glowColorPink: String,
        val glowColorCyan: String,
        val units: String,
        val imperial: String,
        val metric: String,
        val canvasBg: String,
        val resetTutorials: String,
        val resetTutorialsValue: String,
        val appInfo: String,
        val version: String,
        val updates: String,
        val openReleases: String,
        val permissions: String,
        val cameraAccess: String,
        val photoAccess: String,
        val granted: String,
        val denied: String,
        val openAppSettings: String,
        val copyright: String,
        val wearable: String,
        val useArCore: String = "Use ARCore",
        val arCoreDepth: String = "ARCore depth",
        val monocularDepth: String = "Monocular depth (beta)",
        val ambientScan: String = "Ambient scan",
        val changePass: String = "Change detection (needs a clean-wall photo)",
        val adaptiveRate: String = "Adaptive rate (idle)",
        val crashReports: String = "Crash reports",
        val crashReportsDesc: String = "On a crash, send the device model and recent logs to a public bug report? Off by default.",
        val crashTokenLabel: String = "Crash-report token",
        val crashTokenSet: String = "Set",
        val crashTokenNotSet: String = "Not set",
        val crashTokenDesc: String = "A GitHub token with Issues: write on HereLiesAz/GraffitiXR. Stored only on this device, never in the app. Leave blank to disable uploads.",
        val crashTokenField: String = "Token",
        val canvasBlack: String = "Black",
        val canvasDark: String = "Dark",
        val canvasGrey: String = "Grey",
        val canvasWhite: String = "White",
        val canvasNavy: String = "Navy",
        val stateSelected: String = "Selected",
        val stateExpanded: String = "Expanded",
        val stateCollapsed: String = "Collapsed",
        val fpsDeviceDefault: String = "Device default",
        val fpsDeviceMax: String = "Device max"
    )
    data class Editor(
        val layers: String,
        val brushSize: String,
        val renameHint: String,
        val editTextHint: String,
        val copyEdits: String,
        val pasteEdits: String,
        val generatePoster: String,
        val duplicate: String,
        val unlinkLayer: String,
        val linkLayer: String,
        val hideLayer: String,
        val showLayer: String,
        val flattenAll: String,
        val delete: String,
        val lock: String,
        val freeze: String,
        val saveProjectHint: String,
        val chooseFont: String,
        val posterTitle: String,
        val posterSelectLayers: String,
        val posterPhysicalSize: String,
        val posterGeneratePdf: String
    )
    /**
     * Not a `data class`: [matchedPercent] and [scanHintAmbient] are capturing lambdas that are
     * never referentially (or structurally) equal across instances built from the same strings,
     * which would defeat `equals()`-based memoization (e.g. `remember(strings) {...}`) despite
     * representing identical content. [equals]/[hashCode] are hand-written to compare only the
     * plain string fields.
     */
    class Ar(
        val depthUnsupported: String,
        val arcoreRequiredTitle: String,
        val arcoreRequiredText: String,
        val installArcore: String,
        val cameraPermissionRequired: String,
        val openSettings: String,
        val targetCreationTitle: String,
        val targetCreationText: String,
        val targetReviewTitle: String,
        /** "Learning the wall: stage [stage] of [stages] · step [done] of [total]" while the fingerprint builds. */
        val fingerprintCloseUpsProgress: (stage: Int, stages: Int, done: Int, total: Int) -> String =
            { stage, stages, done, total -> "$stage/$stages \u00B7 $done/$total" },
        val diagCopied: String,
        val diagTitle: String,
        val diagWaiting: String,
        val planeConfirmQuestion: String,
        val looksCorrect: String,
        val redetect: String,
        val scanning: String,
        /** Saved target not yet matched: "Looking for your target…". */
        val relocLooking: String,
        val relocTurnRight: (Int) -> String,
        val relocTurnLeft: (Int) -> String,
        val relocTiltUp: (Int) -> String,
        val relocTiltDown: (Int) -> String,
        val relocFacingIt: String,
        val relocRecapture: String,
        val anchorLocked: String,
        val matchedPercent: (Int) -> String,
        val scanHintRecover: String,
        val scanHintAmbient: (Int) -> String,
        val scanHintTooDark: String,
        val scanHintBuildMap: String,
        val scanHintCloser: String,
        val scanHintHigherLower: String,
        val targetExclude: String,
        val targetInclude: String,
        val targetEraseMarks: String,
        val targetShowFeatures: String,
        val syncing: String,
        val reacquiringTarget: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ar) return false
            return depthUnsupported == other.depthUnsupported &&
                arcoreRequiredTitle == other.arcoreRequiredTitle &&
                arcoreRequiredText == other.arcoreRequiredText &&
                installArcore == other.installArcore &&
                cameraPermissionRequired == other.cameraPermissionRequired &&
                openSettings == other.openSettings &&
                targetCreationTitle == other.targetCreationTitle &&
                targetCreationText == other.targetCreationText &&
                targetReviewTitle == other.targetReviewTitle &&
                diagCopied == other.diagCopied &&
                diagTitle == other.diagTitle &&
                diagWaiting == other.diagWaiting &&
                planeConfirmQuestion == other.planeConfirmQuestion &&
                looksCorrect == other.looksCorrect &&
                redetect == other.redetect &&
                scanning == other.scanning &&
                relocLooking == other.relocLooking &&
                relocFacingIt == other.relocFacingIt &&
                relocRecapture == other.relocRecapture &&
                anchorLocked == other.anchorLocked &&
                scanHintRecover == other.scanHintRecover &&
                scanHintTooDark == other.scanHintTooDark &&
                scanHintBuildMap == other.scanHintBuildMap &&
                scanHintCloser == other.scanHintCloser &&
                scanHintHigherLower == other.scanHintHigherLower &&
                targetExclude == other.targetExclude &&
                targetInclude == other.targetInclude &&
                targetEraseMarks == other.targetEraseMarks &&
                targetShowFeatures == other.targetShowFeatures &&
                syncing == other.syncing &&
                reacquiringTarget == other.reacquiringTarget
        }

        override fun hashCode(): Int {
            var result = depthUnsupported.hashCode()
            result = 31 * result + arcoreRequiredTitle.hashCode()
            result = 31 * result + arcoreRequiredText.hashCode()
            result = 31 * result + installArcore.hashCode()
            result = 31 * result + cameraPermissionRequired.hashCode()
            result = 31 * result + openSettings.hashCode()
            result = 31 * result + targetCreationTitle.hashCode()
            result = 31 * result + targetCreationText.hashCode()
            result = 31 * result + targetReviewTitle.hashCode()
            result = 31 * result + diagCopied.hashCode()
            result = 31 * result + diagTitle.hashCode()
            result = 31 * result + diagWaiting.hashCode()
            result = 31 * result + planeConfirmQuestion.hashCode()
            result = 31 * result + looksCorrect.hashCode()
            result = 31 * result + redetect.hashCode()
            result = 31 * result + scanning.hashCode()
            result = 31 * result + relocLooking.hashCode()
            result = 31 * result + relocFacingIt.hashCode()
            result = 31 * result + relocRecapture.hashCode()
            result = 31 * result + anchorLocked.hashCode()
            result = 31 * result + scanHintRecover.hashCode()
            result = 31 * result + scanHintTooDark.hashCode()
            result = 31 * result + scanHintBuildMap.hashCode()
            result = 31 * result + scanHintCloser.hashCode()
            result = 31 * result + scanHintHigherLower.hashCode()
            result = 31 * result + targetExclude.hashCode()
            result = 31 * result + targetInclude.hashCode()
            result = 31 * result + targetEraseMarks.hashCode()
            result = 31 * result + targetShowFeatures.hashCode()
            result = 31 * result + syncing.hashCode()
            result = 31 * result + reacquiringTarget.hashCode()
            return result
        }
    }
    /**
     * Rail-item help text. Fields here are meant to correspond to an actually-registered rail
     * item, but nothing currently checks that automatically: the app module's `RailIntegrityCheck`
     * (a debug-only runtime object, not a test) and its companion `HelpItemsBuilderTest` both
     * operate on hardcoded string-literal keys in `HelpItemsBuilder`'s helpList map, cross-checked
     * against the rail's registered ids — neither ever inspects these `Help` fields themselves. A
     * field that stops being referenced from `HelpItemsBuilder` will silently sit here unused; this
     * is exactly how this class previously accumulated 28 dead fields (targetHost through
     * cloudOffset, an old per-layer authoring toolset) before a manual cleanup: the tools they
     * described belong to the companion design app, not this one.
     */
    data class Help(
        val modeHost: Int,
        val ar: Int,
        val overlay: Int,
        val mockup: Int,
        val trace: Int,
        val create: Int,
        val addImg: Int,
        val wall: Int,
        val projectHost: Int,
        val newProject: Int,
        val saveProject: Int,
        val loadProject: Int,
        val exportImage: Int,
        val appSettings: Int,
        val flashlight: Int,
        val lockTrace: Int,
    )
    data class Adjustments(
        val detail: String,
        val opacity: String,
        val brightness: String,
        val contrast: String,
        val saturation: String,
        val red: String,
        val green: String,
        val blue: String,
        val undo: String,
        val redo: String,
        /** First press of the Reset toggle: flatten placement. */
        val reset: String,
        /** Second press of the same button: put the stashed placement back. */
        val restorePlacement: String,
        val magicAlign: String
    )
}
