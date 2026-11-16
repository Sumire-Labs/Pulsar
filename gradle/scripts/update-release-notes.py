"""Update existing Pulsar release notes without uploading or changing artifacts."""

import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid


def request(url, *, method="GET", token=None, token_header="Authorization", body=None, content_type="application/json"):
    headers = {"User-Agent": "Pulsar-release-notes/1.0 (github.com/Sumire-Labs/Pulsar)"}
    if token:
        headers[token_header] = token
    if body is not None:
        headers["Content-Type"] = content_type
    with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers, method=method), timeout=60) as response:
        data = response.read()
        return json.loads(data) if data else None


def validate(plan):
    if not isinstance(plan, list) or not 1 <= len(plan) <= 40:
        raise ValueError("Expected 1..40 release entries")
    seen = set()
    for entry in plan:
        tag, notes = entry["tag"], entry["notes"]
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?", tag) or tag in seen:
            raise ValueError("Invalid or duplicate version")
        if not isinstance(notes, str) or not notes.strip() or len(notes) > 30000:
            raise ValueError("Empty or oversized release notes")
        seen.add(tag)
        if entry.get("modrinth") is not None and not re.fullmatch(r"[a-zA-Z0-9]{8}", entry["modrinth"]):
            raise ValueError("Invalid Modrinth version ID")
        if entry.get("curseforge") is not None and (type(entry["curseforge"]) is not int or entry["curseforge"] <= 0):
            raise ValueError("Invalid CurseForge file ID")
    return plan


def main():
    plan = validate(json.loads(os.environ["NOTES_PLAN"]))
    if "--dry-run" in sys.argv:
        print(json.dumps([{k: e.get(k) for k in ("tag", "modrinth", "curseforge")} for e in plan]))
        return
    for site, variable in (("modrinth", "MODRINTH_TOKEN"), ("curseforge", "CURSEFORGE_TOKEN")):
        if any(e.get(site) is not None for e in plan) and not os.environ.get(variable):
            raise ValueError("Missing " + variable)
    for entry in plan:
        notes, tag = entry["notes"], entry["tag"]
        if entry.get("modrinth"):
            url = "https://api.modrinth.com/v2/version/" + entry["modrinth"]
            before = request(url)
            if before["project_id"] != "gs7RbryI" or before["version_number"] != tag:
                raise ValueError("Modrinth project/version mismatch")
            request(url, method="PATCH", token=os.environ["MODRINTH_TOKEN"], body=json.dumps({"changelog": notes}).encode())
            if request(url)["changelog"] != notes:
                raise ValueError("Modrinth changelog verification failed")
            print("Updated Modrinth " + tag, flush=True)
        if entry.get("curseforge"):
            boundary = "pulsar-notes-" + uuid.uuid4().hex
            metadata = json.dumps({"fileID": entry["curseforge"], "changelog": notes, "changelogType": "markdown"})
            body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'
                    + metadata + "\r\n--" + boundary + "--\r\n").encode()
            result = request("https://minecraft.curseforge.com/api/projects/1641056/update-file", method="POST",
                token=os.environ["CURSEFORGE_TOKEN"], token_header="X-Api-Token", body=body,
                content_type="multipart/form-data; boundary=" + boundary)
            if result.get("id") != entry["curseforge"]:
                raise ValueError("CurseForge file ID mismatch")
            print("Updated CurseForge " + tag, flush=True)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print("Release-note API returned HTTP " + str(error.code), file=sys.stderr)
        sys.exit(1)
