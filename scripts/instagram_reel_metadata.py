#!/usr/bin/env python3
"""
Instagram Reel metadata + official audio attribution probe.

Why this script exists
----------------------
The original snippet read the song from yt-dlp's `track` / `artist` / `album` fields.
yt-dlp's Instagram extractor never emits those keys (verified against yt-dlp 2026.08.19:
`grep -niE "'track'|'artist'|music_info" yt_dlp/extractor/instagram.py` finds nothing,
and a live run on a public reel returns no such keys at all). So `track` and `artist`
were always None and every reel looked like "Original audio".

Instagram does publish the real attribution, in two places this script now reads:

1. App API  https://i.instagram.com/api/v1/media/<media_id>/info/
   `items[0].clips_metadata.music_info.music_metadata.music_info`
   -> song_name / artist_name / album_name.  Richest source, but returns 403 from
   datacenter IPs and from logged-out clients; pass `--sessionid` to use your own.

2. Embed page  https://www.instagram.com/reel/<shortcode>/embed/
   `clips_music_attribution_info` -> song_name / artist_name / uses_original_audio.
   Works anonymously, so this is the fallback that always gets tried.

Note: the embed page only ever carries ONE track (no album), and for a reel built on
user-recorded audio it reports song_name="Original audio" with the creator as
artist_name. `uses_original_audio` tells those two cases apart — that is why
"Original audio" must never be searched for as a song title.

Stdlib only. yt-dlp is optional (used for creator/caption/duration).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request

# --- Instagram constants (same values the Android app uses) ------------------

IG_APP_ID = "936619743392459"
IG_APP_UA = (
    "Instagram 76.0.0.15.395 Android (29/10; 420dpi; 1080x2400; "
    "samsung; SM-A266B; a26x; exynos2100)"
)
BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

_SHORTCODE = re.compile(r"instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", re.I)
_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
_ORIGINAL_AUDIO = re.compile(r"original\s+(audio|sound)", re.I)
_HANDLE_LIKE = re.compile(r"^[A-Za-z0-9._]{1,39}$")
_ATTR_BLOCK = re.compile(r'clips_music_attribution_info\\?"\s*:\s*\{')


# --- URL / id helpers --------------------------------------------------------


def extract_shortcode(url: str | None) -> str | None:
    """`https://www.instagram.com/reel/ABC123/?x=1` -> `ABC123`."""
    match = _SHORTCODE.search(url or "")
    return match.group(1) if match else None


def canonical_url(url: str | None) -> str | None:
    """Strip share/tracking parameters, which break metadata lookups."""
    shortcode = extract_shortcode(url)
    return f"https://www.instagram.com/reel/{shortcode}/" if shortcode else None


def shortcode_to_media_id(shortcode: str) -> str | None:
    """Instagram shortcode -> numeric media id (base64url alphabet)."""
    value = 0
    for char in shortcode:
        if char not in _ALPHABET:
            return None
        value = value * 64 + _ALPHABET.index(char)
    return str(value)


def _cookie_header(sessionid: str | None) -> str | None:
    """Accepts a bare sessionid or a whole `sessionid=...; ds_user_id=...` line."""
    if not sessionid or not sessionid.strip():
        return None
    text = sessionid.strip()
    return text if "sessionid=" in text else f"sessionid={text}"


def _http_get(url: str, headers: dict[str, str], timeout: int = 15):
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return (
            response.status,
            response.headers.get("Content-Type", ""),
            response.read().decode("utf-8", "replace"),
        )


# --- Song-name sanity -------------------------------------------------------


def looks_like_handle(value: str) -> bool:
    """True for Instagram usernames, which are never song titles."""
    candidate = value.strip().lstrip("@")
    if not candidate or not _HANDLE_LIKE.match(candidate):
        return False
    return any(char.isdigit() or char in "._" for char in candidate)


def is_usable_song(name: str | None) -> bool:
    """
    True when a reported song name is a real track.

    Rejects Instagram's "Original audio" / "Original sound" placeholder, leftover
    "… on Instagram" wrappers, JSON/extractor garbage, and creator handles.
    """
    if not name:
        return False
    value = name.strip()
    if not 2 <= len(value) <= 120:
        return False
    if _ORIGINAL_AUDIO.search(value):
        return False
    if "instagram" in value.lower():
        return False
    if value.startswith("{") or value.startswith("["):
        return False
    if not any(char.isalpha() for char in value):
        return False
    if looks_like_handle(value):
        return False
    return True


# --- Embed-page attribution -------------------------------------------------


def _extract_brace_block(body: str, start: int) -> str | None:
    """
    Return the `{...}` starting at `start`, ignoring braces inside escaped strings.

    The embed page embeds JSON inside JSON, so quotes arrive as `\\"` and every
    structural quote is escaped — brace depth is what actually delimits the object.
    """
    depth = 0
    index = start
    while index < len(body):
        char = body[index]
        if char == "\\":
            index += 2
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return body[start : index + 1]
        index += 1
    return None


def _loads_jsonish(text: str, max_passes: int = 4) -> dict | None:
    """
    Parse a JSON fragment whose quoting has been escaped an unknown number of times.

    Instagram nests this object inside JSON that is itself embedded in a script tag,
    so a quote can arrive as `"`, `\"` or `\\\"` depending on the page. Each pass peels
    one level of escaping; the first pass that yields valid JSON is the right one, so
    the original text is tried before anything is modified.
    """
    candidate = text
    for _ in range(max_passes):
        try:
            parsed = json.loads(candidate)
        except Exception:
            parsed = None
        if isinstance(parsed, dict):
            return parsed
        candidate = re.sub(r"\\(.)", r"\1", candidate)
    return None


def _string_field(block: str, name: str) -> str | None:
    """
    Fallback value reader for the still-escaped block.

    The value stops at the first `\"` delimiter. Escapes are deliberately NOT matched
    here: `\"` is the delimiter itself, so treating it as part of the value makes the
    capture run into the next field.
    """
    pattern = re.compile(name + r'\\?"\s*:\s*\\?"([^"\\]*)\\?"')
    match = pattern.search(block)
    return match.group(1) if match else None


def _bool_field(block: str, name: str) -> bool | None:
    pattern = re.compile(name + r'\\?"\s*:\s*(true|false)', re.I)
    match = pattern.search(block)
    return match.group(1).lower() == "true" if match else None  # noqa: E711


def parse_embed_attribution(html: str) -> dict | None:
    """Pull `clips_music_attribution_info` out of a reel embed page."""
    match = _ATTR_BLOCK.search(html)
    if not match:
        return None
    block = _extract_brace_block(html, match.end() - 1)
    if not block:
        return None

    # Preferred: peel the escaping and let JSON do the parsing (handles \uXXXX escapes).
    data = _loads_jsonish(block) or {}

    song = (data.get("song_name") or _string_field(block, "song_name") or "").strip()
    artist = (data.get("artist_name") or _string_field(block, "artist_name") or "").strip()
    uses_original = data.get("uses_original_audio")
    if uses_original is None:
        uses_original = _bool_field(block, "uses_original_audio")

    return {
        "song": song or None,
        "artist": artist or None,
        "uses_original_audio": uses_original,
    }


# --- Sources ----------------------------------------------------------------


def fetch_official_audio(
    url: str, sessionid: str | None = None, timeout: int = 15
) -> dict | None:
    """
    Official audio attribution for a reel.

    Tries the app media-info API first (richest), then the embed page (works
    anonymously). Returns a dict with song/artist/album/source/is_original_audio,
    or None when nothing could be read. Never guesses from the caption.
    """
    shortcode = extract_shortcode(url)
    if not shortcode:
        return None
    cookie = _cookie_header(sessionid)

    # 1. App API — needs a session on most networks.
    media_id = shortcode_to_media_id(shortcode)
    if media_id:
        headers = {"User-Agent": IG_APP_UA, "x-ig-app-id": IG_APP_ID}
        if cookie:
            headers["Cookie"] = cookie
        try:
            _, _, body = _http_get(
                f"https://i.instagram.com/api/v1/media/{media_id}/info/",
                headers,
                timeout,
            )
            item = (json.loads(body).get("items") or [None])[0] or {}
            music = (
                ((item.get("clips_metadata") or {}).get("music_info") or {})
                .get("music_metadata", {})
                .get("music_info")
            ) or {}
            song = (music.get("song_name") or "").strip()
            artist = (music.get("artist_name") or "").strip()
            album = (music.get("album_name") or "").strip()
            if song or artist:
                return {
                    "song": song or None,
                    "artist": artist or None,
                    "album": album or None,
                    "is_original_audio": bool(_ORIGINAL_AUDIO.search(song or "")),
                    "source": "app_api",
                }
        except Exception as exc:  # 403 from datacenter IPs, timeouts, bad JSON
            print(f"  [app API] unavailable: {type(exc).__name__}: {exc}", file=sys.stderr)

    # 2. Embed page — anonymous-friendly.
    headers = {"User-Agent": BROWSER_UA}
    if cookie:
        headers["Cookie"] = cookie
    try:
        status, content_type, body = _http_get(
            f"https://www.instagram.com/reel/{shortcode}/embed/", headers, timeout
        )
    except Exception as exc:
        print(f"  [embed] unavailable: {type(exc).__name__}: {exc}", file=sys.stderr)
        return None

    if status != 200 or "text/html" not in content_type.lower():
        print(f"  [embed] unexpected response: {status} {content_type}", file=sys.stderr)
        return None

    parsed = parse_embed_attribution(body)
    if not parsed:
        return None
    return {
        "song": parsed["song"],
        "artist": parsed["artist"],
        "album": None,  # the embed page carries no album
        "is_original_audio": bool(parsed["uses_original_audio"])
        or bool(_ORIGINAL_AUDIO.search(parsed["song"] or "")),
        "source": "embed",
    }


def fetch_reel_info(url: str, sessionid: str | None = None, timeout: int = 15) -> dict:
    """
    Creator / caption / duration via yt-dlp (`skip_download`, metadata only).

    Deliberately does NOT read `track` / `artist` / `album`: yt-dlp's Instagram
    extractor never sets them, so they are always None.
    """
    try:
        import yt_dlp
    except ImportError:
        return {"_error": "yt-dlp is not installed — run: pip install -U yt-dlp"}

    options = {
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
        "socket_timeout": timeout,
        "retries": 3,
    }
    cookie = _cookie_header(sessionid)
    if cookie:
        options["http_headers"] = {"Cookie": cookie}
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            return ydl.extract_info(url, download=False) or {}
    except Exception as exc:
        return {"_error": f"{type(exc).__name__}: {exc}"}


# --- Reporting --------------------------------------------------------------


def report(
    url: str, sessionid: str | None = None, timeout: int = 15, verbose: bool = True
) -> dict:
    """
    Fetch everything and print a human-readable summary. Returns the data.

    Set `verbose=False` to suppress the summary so stdout carries only machine-readable
    output (used by --json).
    """

    def say(*parts: object) -> None:
        if verbose:
            print(*parts)

    canonical = canonical_url(url)
    if not canonical:
        say(f"Not an Instagram reel/post link: {url}")
        return {}

    say(f"Fetching metadata for: {canonical}\n")

    info = fetch_reel_info(canonical, sessionid, timeout)
    error = info.get("_error")

    say("=== REEL DETAILS ===")
    if error:
        say(f"  (yt-dlp unavailable: {error})")
    else:
        uploader = info.get("uploader") or info.get("channel") or "Unknown"
        description = info.get("description") or "No caption"
        say(f"  Creator : @{info.get('channel') or uploader}")
        say(f"  Views   : {info.get('view_count', 'N/A')}")
        say(f"  Duration: {info.get('duration', 'N/A')} s")
        say(f"  Caption : {description[:150]}{'...' if len(description) > 150 else ''}")
    say()

    audio = fetch_official_audio(canonical, sessionid, timeout)

    say("=== AUDIO INFORMATION ===")
    if not audio:
        say("  No official audio attribution could be read.")
        say("  Tip: pass --sessionid to use your own Instagram session.")
    else:
        song, artist = audio.get("song"), audio.get("artist")
        say(f"  Source  : {audio['source']}")
        # The decision is driven by whether the NAME is a usable song name, not by the
        # `uses_original_audio` flag: verified live, an uploaded sound can still carry a
        # perfectly searchable name (e.g. "Cheap Thrills x Chhod Do Anchal • DJ Infeels")
        # while a licensed-audio slot can carry the useless "Original audio" placeholder.
        if not is_usable_song(song):
            say("  Audio   : Original audio (user-generated sound), not a licensed track.")
            say(f"  Uploader: @{artist or 'unknown'}")
            say("            Do not search this as a song title.")
        else:
            say(f"  Track   : {song}")
            say(f"  Artist  : {artist or 'Unknown artist'}")
            if audio.get("album"):
                say(f"  Album   : {audio['album']}")
            if audio.get("is_original_audio"):
                say("  Note    : Uploaded sound (not licensed audio) — verify before trusting.")
    say()

    return {
        "url": canonical,
        "song": (audio or {}).get("song"),
        "artist": (audio or {}).get("artist"),
        "album": (audio or {}).get("album"),
        "is_original_audio": (audio or {}).get("is_original_audio"),
        "attribution_source": (audio or {}).get("source"),
        "search_query": (
            f"{(audio or {}).get('song')} {(audio or {}).get('artist') or ''}".strip()
            if is_usable_song((audio or {}).get("song"))
            else None
        ),
        "creator": info.get("channel") or info.get("uploader"),
        "caption": info.get("description"),
        "info_error": error,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("url", nargs="?", help="public Instagram Reel URL")
    parser.add_argument(
        "--sessionid",
        help="your Instagram sessionid value (or whole cookie line) for the app API",
    )
    parser.add_argument("--timeout", type=int, default=15)
    parser.add_argument("--json", action="store_true", help="also dump raw JSON")
    args = parser.parse_args(argv)

    url = args.url or input("Enter Instagram Reel URL: ").strip()
    if not url:
        print("No URL given.")
        return 1

    data = report(url, args.sessionid, args.timeout, verbose=not args.json)
    if args.json:
        # stdout carries JSON only, so the output stays pipeable.
        print(json.dumps(data, indent=2, ensure_ascii=False, default=str))
    return 0 if data else 1


if __name__ == "__main__":
    raise SystemExit(main())