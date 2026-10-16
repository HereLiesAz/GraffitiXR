package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.ProjectRestorePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryExportPolicyTest {

    @Test
    fun `a grant runs the request that was waiting`() {
        assertEquals(
            GalleryPermissionOutcome.Run(GalleryExportRequest.RAIL_EXPORT),
            GalleryExportPolicy.onPermissionResult(GalleryExportRequest.RAIL_EXPORT, granted = true),
        )
    }

    @Test
    fun `a denial fails loudly instead of doing nothing`() {
        assertEquals(
            GalleryPermissionOutcome.Fail,
            GalleryExportPolicy.onPermissionResult(GalleryExportRequest.RAIL_EXPORT, granted = false),
        )
    }

    @Test
    fun `a grant whose request was lost also fails loudly`() {
        // The activity was recreated and the saved state did not carry the request: the user must be
        // told to retry rather than left tapping Export with no feedback.
        assertEquals(GalleryPermissionOutcome.Fail, GalleryExportPolicy.onPermissionResult(null, granted = true))
    }

    @Test
    fun `the request round-trips through saved instance state by name`() {
        for (r in GalleryExportRequest.entries) {
            assertEquals(r, GalleryExportRequest.fromSavedName(r.name))
        }
        assertNull(GalleryExportRequest.fromSavedName(null))
        assertNull(GalleryExportRequest.fromSavedName("renamed_in_a_later_version"))
    }
}

class ProjectRestorePolicyTest {
    private val library = "library"

    private fun decide(decided: Boolean = false, route: String?, project: String? = null) =
        ProjectRestorePolicy.decide(decided, route, library, project)

    @Test
    fun `waits until the controller reports a route`() {
        assertEquals(Decision.WAIT, decide(route = null))
    }

    @Test
    fun `a restored editor route with nothing loaded restores`() {
        assertEquals(Decision.RESTORE, decide(route = "DESIGN"))
    }

    @Test
    fun `a normal start on the library never restores`() {
        assertEquals(Decision.SKIP, decide(route = library))
    }

    @Test
    fun `a recreation with the project still loaded does not restore`() {
        assertEquals(Decision.SKIP, decide(route = "AR", project = "p1"))
    }

    @Test
    fun `once decided, later non-library routes with no project never restore (closing a project)`() {
        // The scenario the old effect got wrong: library first (decided), then a project is opened
        // and closed while the route is still an editor route and the stored id is still being
        // forgotten.
        assertEquals(Decision.SKIP, decide(decided = true, route = "DESIGN", project = null))
        assertEquals(Decision.SKIP, decide(decided = true, route = "AR", project = null))
    }
}
