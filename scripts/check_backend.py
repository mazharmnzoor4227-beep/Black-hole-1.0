"""Fail delivery unless the configured extraction server completes a real URL."""
import json
import os
from pathlib import Path
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import urlopen


def endpoint():
    value = (os.getenv('BLACK_HOLE_API_URL') or Path('backend-url.txt').read_text()).strip().rstrip('/')
    parsed = urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise SystemExit('A live HTTPS extraction server is required; set backend-url.txt first.')
    return value


def get_json(url):
    with urlopen(url, timeout=120) as response:
        return json.load(response)


def main():
    base = endpoint()
    health = get_json(base + '/health')
    assert health.get('service') == 'black-hole' and health.get('api_version') == 1, 'Wrong server/API'
    sample = os.getenv('BLACK_HOLE_TEST_URL', '').strip()
    if not sample:
        raise SystemExit('Set BLACK_HOLE_TEST_URL to a public social video to verify extraction before delivery.')
    job = get_json(base + '/v1/jobs?' + urlencode({'url': sample}))['id']
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        status = get_json(base + '/v1/jobs/' + job)
        if status['status'] == 'failed':
            raise SystemExit(status.get('error', 'Extraction failed'))
        if status['status'] == 'ready':
            assert status.get('has_audio') is True, 'Backend did not verify an audio track'
            assert int(status.get('width') or 0) > 0 and int(status.get('height') or 0) > 0, 'Missing output dimensions'
            assert status.get('video_codec') == 'h264', 'Output video is not H.264-compatible'
            assert status.get('audio_codec') == 'aac', 'Output audio is not AAC-compatible'
            with urlopen(base + '/v1/media/' + job, timeout=120) as response:
                expected = int(response.headers['Content-Length'])
                count = 0
                header = response.read(32)
                assert b'ftyp' in header, 'Response is not MP4'
                count += len(header)
                while block := response.read(1024 * 1024):
                    count += len(block)
                assert count == expected and count > 32, 'Incomplete video'
            assert count == int(status.get('bytes') or 0), 'Metadata size does not match downloaded media'
            print(json.dumps({'verified_bytes': count, 'quality': status.get('quality'), 'title': status.get('title'), 'audio_codec': status.get('audio_codec'), 'video_codec': status.get('video_codec')}))
            return
        time.sleep(2)
    raise SystemExit('Extraction timed out')


if __name__ == '__main__':
    main()
