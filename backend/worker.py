"""Isolated extraction process. No cookies, credentials or user-supplied options."""
import ipaddress
import json
import os
import socket
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


def public_address(value):
    address = ipaddress.ip_address(value.split('%')[0])
    if getattr(address, 'ipv4_mapped', None):
        address = address.ipv4_mapped
    return address.is_global


def secure_sockets():
    # Validate the actual numeric destination at connect time, not just initial DNS.
    original = socket.socket.connect
    original_ex = socket.socket.connect_ex
    def check(address):
        if not isinstance(address, tuple) or not public_address(address[0]) or address[1] not in (80, 443):
            raise OSError('Non-public network destination blocked')
    def connect(sock, address):
        check(address)
        return original(sock, address)
    def connect_ex(sock, address):
        check(address)
        return original_ex(sock, address)
    socket.socket.connect = connect
    socket.socket.connect_ex = connect_ex


def safe_error(error):
    exact = str(error)
    if exact in {
        'VIDEO EXCEEDS THE 1 GB SERVER LIMIT',
        'NO COMPATIBLE H.264/AAC MP4 AVAILABLE',
        'DOWNLOADED VIDEO HAS NO AUDIO',
        'DOWNLOADED FILE IS NOT A PLAYABLE MP4',
    }:
        return exact
    text = str(error).lower()
    if 'login' in text or 'private' in text or 'sign in' in text or 'cookies' in text:
        return 'VIDEO REQUIRES LOGIN OR IS PRIVATE'
    if 'timed out' in text or 'timeout' in text:
        return 'VIDEO EXTRACTION TIMED OUT'
    if 'filesize' in text or 'file is larger' in text:
        return 'VIDEO EXCEEDS THE 1 GB SERVER LIMIT'
    return 'VIDEO UNAVAILABLE, RESTRICTED OR NOT SUPPORTED'


def main():
    secure_sockets()
    for name in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy'):
        os.environ.pop(name, None)
    from yt_dlp import YoutubeDL
    url, directory = sys.argv[1:]
    u = urlsplit(url)
    if u.scheme != 'https' or not u.hostname or u.username or u.password or u.port not in (None,443):
        raise ValueError('Public HTTPS URL required')
    folder = Path(directory)
    try:
        opts = {
            'noplaylist': True, 'playlistend': 1, 'quiet': True, 'no_warnings': True,
            'socket_timeout': 20, 'retries': 2, 'fragment_retries': 2,
            'max_filesize': 1024**3, 'outtmpl': str(folder / 'video.%(ext)s'),
            'cachedir': False, 'proxy': '', 'geo_bypass': False,
            'format': "bestvideo[vcodec~='^(avc1|h264)']+bestaudio[acodec~='^(mp4a|aac)']/best[ext=mp4][vcodec~='^(avc1|h264)'][acodec!=none]/best[protocol=m3u8_native][vcodec~='^(avc1|h264)'][acodec!=none]",
            'format_sort': ['res', 'fps', 'br'], 'format_sort_force': True,
            'merge_output_format': 'mp4', 'hls_prefer_native': True,
            'postprocessor_args': {'ffmpeg_i': ['-protocol_whitelist', 'file,pipe,crypto']},
        }
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
        target = folder / 'video.mp4'
        if not target.is_file() or target.stat().st_size == 0:
            raise ValueError('NO COMPATIBLE H.264/AAC MP4 AVAILABLE')
        if target.stat().st_size > 1024**3:
            raise ValueError('VIDEO EXCEEDS THE 1 GB SERVER LIMIT')
        result = subprocess.run(
            ['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(target)],
            capture_output=True, text=True, timeout=30, check=True,
        )
        probe = json.loads(result.stdout)
        videos = [stream for stream in probe.get('streams', []) if stream.get('codec_type') == 'video']
        audios = [stream for stream in probe.get('streams', []) if stream.get('codec_type') == 'audio']
        if not videos:
            raise ValueError('DOWNLOADED FILE IS NOT A PLAYABLE MP4')
        if not audios:
            raise ValueError('DOWNLOADED VIDEO HAS NO AUDIO')
        video = videos[0]
        width, height = int(video.get('width') or 0), int(video.get('height') or 0)
        if width <= 0 or height <= 0:
            raise ValueError('DOWNLOADED FILE IS NOT A PLAYABLE MP4')
        metadata = {
            'title': str(info.get('title') or 'Video')[:200],
            'quality': f'{width}×{height} · MP4',
            'width': width,
            'height': height,
            'has_audio': True,
            'video_codec': str(video.get('codec_name') or ''),
            'audio_codec': str(audios[0].get('codec_name') or ''),
            'bytes': target.stat().st_size,
        }
        (folder / 'metadata.json').write_text(json.dumps(metadata), encoding='utf-8')
    except Exception as error:
        (folder / 'error.json').write_text(json.dumps({'error': safe_error(error)}), encoding='utf-8')
        raise


if __name__ == '__main__':
    main()
