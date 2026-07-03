#!/usr/bin/env python3
"""Publish one AAB to multiple Play tracks with per-track status.

Model: one Play Developer API edit → one bundles.upload → N tracks.update
calls (each with its own release object carrying its own status) → one
commit. `r0adkll/upload-google-play@v1` forces a single global status per
call, so it can't do the internal=completed + others=draft fan-out this
script does in one edit transaction.

Env inputs (all required):
    PACKAGE_NAME               applicationId, e.g. com.hereliesaz.graffitixr
    AAB_GLOB                   glob resolving to exactly one .aab
    PLAY_SERVICE_ACCOUNT_JSON  raw JSON contents of the service-account key

Non-zero exit on any failure — release-aab.yml's `steps.play-upload.outcome`
gate reads that as "don't advance versionBuild in main" and the fail-loudly
step re-annotates the run.
"""
import glob
import json
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

# (track_slug, release_status). `internal` goes live immediately; the other
# three land as drafts so a human promotes them from the Play Console when
# ready. Slugs are Play's defaults — override here if the app has custom
# closed-testing tracks (Play Console → Testing → Closed testing → track ID).
TARGETS = [
    ("internal",   "completed"),
    ("alpha",      "draft"),   # closed testing
    ("beta",       "draft"),   # open testing
    ("production", "draft"),
]

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]


def main() -> None:
    pkg = os.environ["PACKAGE_NAME"]
    aab_glob = os.environ["AAB_GLOB"]
    creds_json = os.environ["PLAY_SERVICE_ACCOUNT_JSON"]

    aabs = sorted(glob.glob(aab_glob))
    if len(aabs) != 1:
        sys.exit(f"Expected exactly one AAB from {aab_glob!r}, found {aabs}")
    aab = aabs[0]

    creds = service_account.Credentials.from_service_account_info(
        json.loads(creds_json), scopes=SCOPES,
    )
    svc = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)

    # Play's concurrent-active-edits quota is small — if we fail between insert and commit,
    # the open edit sits on the quota until Play garbage-collects it. Delete it on any
    # exception so repeated failures don't lock us out.
    edit_id = None
    try:
        edit = svc.edits().insert(packageName=pkg, body={}).execute()
        edit_id = edit["id"]
        print(f"::notice::Opened Play edit {edit_id}")

        media = MediaFileUpload(aab, mimetype="application/octet-stream", resumable=True)
        bundle = svc.edits().bundles().upload(
            packageName=pkg, editId=edit_id, media_body=media,
        ).execute()
        version_code = bundle["versionCode"]
        print(f"::notice::Uploaded AAB versionCode={version_code} ({aab})")

        for track, status in TARGETS:
            svc.edits().tracks().update(
                packageName=pkg,
                editId=edit_id,
                track=track,
                body={
                    "track": track,
                    "releases": [{
                        "status": status,
                        "versionCodes": [str(version_code)],
                    }],
                },
            ).execute()
            print(f"::notice::Assigned versionCode={version_code} to '{track}' as {status}")

        svc.edits().commit(packageName=pkg, editId=edit_id).execute()
        print(f"::notice::Committed edit {edit_id} — versionCode={version_code} live on internal, draft on the rest")
    except Exception:
        if edit_id is not None:
            try:
                svc.edits().delete(packageName=pkg, editId=edit_id).execute()
                print(f"::warning::Deleted uncommitted Play edit {edit_id} after failure", file=sys.stderr)
            except Exception as cleanup_err:
                # Best-effort — don't mask the original failure by raising from the cleanup path.
                print(f"::warning::Could not delete edit {edit_id}: {cleanup_err}", file=sys.stderr)
        raise


if __name__ == "__main__":
    main()
