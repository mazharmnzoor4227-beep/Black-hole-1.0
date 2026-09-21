"""Isolated extraction process. No cookies, credentials or user-supplied options."""
import ipaddress
import json
import os
import socket
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
    opts = {
        'noplaylist': True, 'playlistend': 1, 'quiet': True, 'no_warnings': True,
        'socket_timeout': 20, 'retries': 2, 'fragment_retries': 2,
        'max_filesize': 1024**3, 'outtmpl': str(folder / 'video.%(ext)s'),
        'cachedir': False, 'proxy': '', 'geo_bypass': False,
        'format': 'bestvideo[vcodec^=avc1][protocol=https]+bestaudio[acodec^=mp4a][protocol=https]/best[ext=mp4][vcodec^=avc1][acodec!=none][protocol=https]/best[ext=mp4][vcodec^=avc1][acodec!=none][protocol=m3u8_native]',
        'format_sort': ['res', 'fps', 'br'], 'format_sort_force': True,
        'merge_output_format': 'mp4', 'hls_prefer_native': True,
        'postprocessor_args': {'ffmpeg_i': ['-protocol_whitelist', 'file,pipe,crypto']},
    }
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
    target = folder / 'video.mp4'
    if not target.is_file() or target.stat().st_size == 0:
        raise ValueError('No compatible public MP4 available')
    metadata = {
        'title': str(info.get('title') or 'Video')[:200],
        'quality': f"{info['width']}×{info['height']} · MP4" if info.get('width') and info.get('height') else 'MP4',
    }
    (folder / 'metadata.json').write_text(json.dumps(metadata), encoding='utf-8')


if __name__ == '__main__':
    main()
